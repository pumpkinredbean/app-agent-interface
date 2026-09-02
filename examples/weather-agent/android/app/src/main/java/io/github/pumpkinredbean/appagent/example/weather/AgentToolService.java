package io.github.pumpkinredbean.appagent.example.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import io.github.pumpkinredbean.appagent.ToolApprovalController;
import io.github.pumpkinredbean.appagent.ui.compose.ToolApprovalNotificationBridge;
import io.github.pumpkinredbean.appagent.ui.compose.ToolApprovalNotificationConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AgentToolService extends Service {
    public static final int PORT = 8765;

    private static final String RUNTIME_CHANNEL_ID = "app-tool-runtime";
    private static final String ALERT_CHANNEL_ID = "weather-agent-alerts";
    private static final int RUNTIME_NOTIFICATION_ID = 8765;

    public interface RuntimeListener {
        void onRuntimeEvent(RuntimeEvent event);
    }

    public final class LocalBinder extends Binder {
        public AgentToolService getService() {
            return AgentToolService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Set<RuntimeListener> listeners = new CopyOnWriteArraySet<>();
    private final ArrayDeque<RuntimeEvent> eventHistory = new ArrayDeque<>();
    private final ToolApprovalController approvalController = new ToolApprovalController();
    private final ScheduledExecutorService alertScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "weather-alert-evaluator");
        thread.setDaemon(true);
        return thread;
    });
    private AgentToolServer server;
    private ToolApprovalNotificationBridge approvalNotificationBridge;
    private volatile boolean ready;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(RUNTIME_NOTIFICATION_ID, runtimeNotification("Starting on 127.0.0.1:" + PORT));
        approvalNotificationBridge = new ToolApprovalNotificationBridge(
            this,
            approvalController,
            new ToolApprovalNotificationConfiguration(
                android.R.drawable.ic_dialog_alert,
                mainActivityPendingIntent()
            )
        );

        server = new AgentToolServer(
            this,
            PORT,
            this::publish,
            approvalController,
            this::evaluateAlertsSoon
        );
        try {
            server.start();
            ready = true;
            publish(RuntimeEvent.ready("127.0.0.1:" + PORT + " · waiting for desktop requests"));
            updateRuntimeNotification("Waiting on 127.0.0.1:" + PORT);
            alertScheduler.schedule(this::evaluateAlerts, 10, TimeUnit.SECONDS);
            alertScheduler.scheduleWithFixedDelay(this::evaluateAlerts, 15, 15, TimeUnit.MINUTES);
        } catch (Exception error) {
            publish(RuntimeEvent.error("Service startup failed", safeMessage(error)));
            updateRuntimeNotification("Startup failed: " + safeMessage(error));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean isReady() {
        return ready;
    }

    public void registerListener(RuntimeListener listener) {
        listeners.add(listener);
        List<RuntimeEvent> snapshot;
        synchronized (eventHistory) {
            snapshot = new ArrayList<>(eventHistory);
        }
        for (RuntimeEvent event : snapshot) listener.onRuntimeEvent(event);
    }

    public void unregisterListener(RuntimeListener listener) {
        listeners.remove(listener);
    }

    public ToolApprovalController getApprovalController() {
        return approvalController;
    }

    public JSONObject invokeLocal(String tool, JSONObject arguments, String source) throws JSONException {
        if (!ready || server == null) throw new IllegalStateException("Tool Runtime service is not ready");
        return server.invokeLocal(tool, arguments, source);
    }

    @Override
    public void onDestroy() {
        ready = false;
        alertScheduler.shutdownNow();
        if (server != null) server.close();
        if (approvalNotificationBridge != null) approvalNotificationBridge.close();
        approvalController.close();
        listeners.clear();
        super.onDestroy();
    }

    private void evaluateAlertsSoon() {
        if (!alertScheduler.isShutdown()) alertScheduler.execute(this::evaluateAlerts);
    }

    private void evaluateAlerts() {
        AgentToolServer currentServer = server;
        if (!ready || currentServer == null) return;
        currentServer.evaluateAlerts(this::showTriggeredAlert);
    }

    private void showTriggeredAlert(WeatherAlert alert, double actualValue) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            alert.id.hashCode(),
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification notification = new Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Weather alert for " + alert.location.name)
            .setContentText(alert.notificationText(actualValue))
            .setStyle(new Notification.BigTextStyle().bigText(alert.notificationText(actualValue)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();
        getSystemService(NotificationManager.class).notify(alert.id.hashCode(), notification);
    }

    private void publish(RuntimeEvent event) {
        synchronized (eventHistory) {
            eventHistory.addLast(event);
            while (eventHistory.size() > 32) eventHistory.removeFirst();
        }
        for (RuntimeListener listener : listeners) listener.onRuntimeEvent(event);
    }

    private void createNotificationChannels() {
        NotificationChannel runtimeChannel = new NotificationChannel(
            RUNTIME_CHANNEL_ID,
            "App Tool Runtime",
            NotificationManager.IMPORTANCE_LOW
        );
        runtimeChannel.setDescription("Application-scoped ToolCall runtime status");
        NotificationChannel alertChannel = new NotificationChannel(
            ALERT_CHANNEL_ID,
            "Weather alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        alertChannel.setDescription("Weather rules created through app tools");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(runtimeChannel);
        manager.createNotificationChannel(alertChannel);
    }

    private Notification runtimeNotification(String text) {
        return new Notification.Builder(this, RUNTIME_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Weather Agent Tool Runtime")
            .setContentText(text)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .build();
    }

    private PendingIntent mainActivityPendingIntent() {
        Intent activityIntent = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private void updateRuntimeNotification(String text) {
        getSystemService(NotificationManager.class).notify(RUNTIME_NOTIFICATION_ID, runtimeNotification(text));
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
