package io.github.pumpkinredbean.appagent.example.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import io.github.pumpkinredbean.appagent.ToolApprovalRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AgentToolService extends Service {
    public static final int PORT = 8765;

    private static final String RUNTIME_CHANNEL_ID = "app-tool-runtime";
    private static final String ALERT_CHANNEL_ID = "weather-agent-alerts";
    private static final int RUNTIME_NOTIFICATION_ID = 8765;
    private static final long APPROVAL_TIMEOUT_SECONDS = 60;

    public interface RuntimeListener {
        void onRuntimeEvent(RuntimeEvent event);
    }

    public interface ApprovalListener {
        void onApprovalRequested(ToolApprovalRequest request);
    }

    public final class LocalBinder extends Binder {
        public AgentToolService getService() {
            return AgentToolService.this;
        }
    }

    private static final class PendingApproval {
        final CountDownLatch decisionReady = new CountDownLatch(1);
        volatile boolean approved;

        synchronized void resolve(boolean decision) {
            if (decisionReady.getCount() == 0) return;
            approved = decision;
            decisionReady.countDown();
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Set<RuntimeListener> listeners = new CopyOnWriteArraySet<>();
    private final Set<ApprovalListener> approvalListeners = new CopyOnWriteArraySet<>();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final ArrayDeque<RuntimeEvent> eventHistory = new ArrayDeque<>();
    private final ScheduledExecutorService alertScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "weather-alert-evaluator");
        thread.setDaemon(true);
        return thread;
    });
    private AgentToolServer server;
    private volatile boolean ready;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(RUNTIME_NOTIFICATION_ID, runtimeNotification("Starting on 127.0.0.1:" + PORT));

        server = new AgentToolServer(
            this,
            PORT,
            this::publish,
            this::requestApproval,
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

    public void registerApprovalListener(ApprovalListener listener) {
        approvalListeners.add(listener);
    }

    public void unregisterApprovalListener(ApprovalListener listener) {
        approvalListeners.remove(listener);
        if (approvalListeners.isEmpty()) resolveAllApprovals(false);
    }

    public void resolveApproval(String callId, boolean approved) {
        PendingApproval pending = pendingApprovals.get(callId);
        if (pending != null) pending.resolve(approved);
    }

    public JSONObject invokeLocal(String tool, JSONObject arguments, String source) throws JSONException {
        if (!ready || server == null) throw new IllegalStateException("Tool Runtime service is not ready");
        return server.invokeLocal(tool, arguments, source);
    }

    @Override
    public void onDestroy() {
        ready = false;
        resolveAllApprovals(false);
        alertScheduler.shutdownNow();
        if (server != null) server.close();
        approvalListeners.clear();
        listeners.clear();
        super.onDestroy();
    }

    private boolean requestApproval(ToolApprovalRequest request) {
        if (approvalListeners.isEmpty()) return false;
        PendingApproval pending = new PendingApproval();
        pendingApprovals.put(request.getCall().getId(), pending);
        if (approvalListeners.isEmpty()) {
            pendingApprovals.remove(request.getCall().getId(), pending);
            return false;
        }
        publish(RuntimeEvent.approvalRequested(request));
        for (ApprovalListener listener : approvalListeners) listener.onApprovalRequested(request);

        boolean decided = false;
        try {
            decided = pending.decisionReady.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return decided && pending.approved;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pendingApprovals.remove(request.getCall().getId(), pending);
            publish(RuntimeEvent.approvalResolved(request, decided && pending.approved));
        }
    }

    private void resolveAllApprovals(boolean approved) {
        for (PendingApproval pending : pendingApprovals.values()) pending.resolve(approved);
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
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new Notification.Builder(this, RUNTIME_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Weather Agent Tool Runtime")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void updateRuntimeNotification(String text) {
        getSystemService(NotificationManager.class).notify(RUNTIME_NOTIFICATION_ID, runtimeNotification(text));
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
