package io.github.pumpkinredbean.appagent.example.weather;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.pumpkinredbean.appagent.ToolApprovalRequest;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(7, 16, 28);
    private static final int CARD = Color.rgb(15, 31, 49);
    private static final int CARD_ALT = Color.rgb(10, 24, 39);
    private static final int TEXT = Color.rgb(240, 248, 255);
    private static final int MUTED = Color.rgb(151, 174, 196);
    private static final int ACCENT = Color.rgb(72, 184, 255);

    private final List<RuntimeEvent> events = new ArrayList<>();
    private final Set<String> visibleApprovals = new HashSet<>();
    private AgentToolService service;
    private boolean bound;
    private boolean rawVisible;
    private RuntimeEvent lastEvent;
    private ScrollView scrollView;
    private TextView statusView;
    private TextView eventView;
    private TextView rawView;
    private Button localCallButton;
    private Button rawToggleButton;

    private final AgentToolService.RuntimeListener runtimeListener = event ->
        runOnUiThread(() -> appendEvent(event));
    private final AgentToolService.ApprovalListener approvalListener = request ->
        runOnUiThread(() -> showApproval(request));

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AgentToolService.LocalBinder) binder).getService();
            bound = true;
            events.clear();
            service.registerListener(runtimeListener);
            service.registerApprovalListener(approvalListener);
            localCallButton.setEnabled(true);
            setOnlineStatus(service.isReady() ? "Waiting for requests" : "Starting service");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            localCallButton.setEnabled(false);
            statusView.setText("● OFFLINE · SERVICE DISCONNECTED");
            statusView.setTextColor(Color.rgb(255, 122, 122));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(26), dp(22), dp(34));
        content.setBackgroundColor(BACKGROUND);

        TextView eyebrow = text("WEATHER AGENT  ·  APP TOOL BRIDGE", 12, ACCENT, Typeface.BOLD);
        content.addView(eyebrow, matchWrap());

        TextView title = text("Mobile execution log", 28, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, 0);
        content.addView(title, matchWrap());

        TextView description = text(
            "Desktop and in-app agent calls execute through\nthe same application-scoped Kotlin runtime.",
            15,
            MUTED,
            Typeface.NORMAL
        );
        description.setLineSpacing(0, 1.25f);
        description.setPadding(0, dp(10), 0, dp(20));
        content.addView(description, matchWrap());

        statusView = text("● STARTING · 127.0.0.1:" + AgentToolService.PORT, 13, ACCENT, Typeface.BOLD);
        statusView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusView.setBackground(cardBackground(CARD, 12));
        content.addView(statusView, matchWrap());

        localCallButton = new Button(this);
        localCallButton.setText("Run in-app current weather for Seoul");
        localCallButton.setTextSize(14);
        localCallButton.setAllCaps(false);
        localCallButton.setEnabled(false);
        localCallButton.setTextColor(BACKGROUND);
        localCallButton.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        localCallButton.setOnClickListener(view -> invokeFromInApp());
        LinearLayout.LayoutParams localButtonParams = matchWrap();
        localButtonParams.topMargin = dp(14);
        content.addView(localCallButton, localButtonParams);

        TextView logTitle = text("Execution timeline", 17, TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams logTitleParams = matchWrap();
        logTitleParams.topMargin = dp(26);
        content.addView(logTitle, logTitleParams);

        TextView logCaption = text("The same #callId appears in the desktop trace.", 13, MUTED, Typeface.NORMAL);
        logCaption.setPadding(0, dp(5), 0, dp(10));
        content.addView(logCaption, matchWrap());

        eventView = text("No requests have run yet.", 14, TEXT, Typeface.NORMAL);
        eventView.setTypeface(Typeface.MONOSPACE);
        eventView.setLineSpacing(dp(2), 1.12f);
        eventView.setTextIsSelectable(true);
        eventView.setPadding(dp(16), dp(16), dp(16), dp(16));
        eventView.setBackground(cardBackground(CARD, 12));
        content.addView(eventView, matchWrap());

        rawToggleButton = new Button(this);
        rawToggleButton.setText("Show latest raw JSON");
        rawToggleButton.setTextSize(13);
        rawToggleButton.setAllCaps(false);
        rawToggleButton.setEnabled(false);
        rawToggleButton.setTextColor(TEXT);
        rawToggleButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(42, 65, 88)));
        rawToggleButton.setOnClickListener(view -> {
            rawVisible = !rawVisible;
            renderRawEvent();
        });
        LinearLayout.LayoutParams rawButtonParams = matchWrap();
        rawButtonParams.topMargin = dp(10);
        content.addView(rawToggleButton, rawButtonParams);

        rawView = text("", 11, MUTED, Typeface.NORMAL);
        rawView.setTypeface(Typeface.MONOSPACE);
        rawView.setTextIsSelectable(true);
        rawView.setPadding(dp(14), dp(14), dp(14), dp(14));
        rawView.setBackground(cardBackground(CARD_ALT, 10));
        rawView.setVisibility(View.GONE);
        LinearLayout.LayoutParams rawParams = matchWrap();
        rawParams.topMargin = dp(8);
        content.addView(rawView, rawParams);

        TextView boundary = text(
            "The model can call only the six weather tools registered by this app.\n"
                + "Create and delete actions pause for approval on this screen.",
            12,
            MUTED,
            Typeface.NORMAL
        );
        boundary.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams boundaryParams = matchWrap();
        boundaryParams.topMargin = dp(22);
        content.addView(boundary, boundaryParams);

        TextView attribution = text(
            "Weather data by https://open-meteo.com/ · CC BY 4.0",
            11,
            MUTED,
            Typeface.NORMAL
        );
        attribution.setAutoLinkMask(Linkify.WEB_URLS);
        LinearLayout.LayoutParams attributionParams = matchWrap();
        attributionParams.topMargin = dp(12);
        content.addView(attribution, attributionParams);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);
        scrollView.addView(content);
        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1001);
        }

        Intent serviceIntent = new Intent(this, AgentToolService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void invokeFromInApp() {
        localCallButton.setEnabled(false);
        new Thread(() -> {
            try {
                if (service == null) throw new IllegalStateException("Tool Runtime service is not connected");
                JSONObject arguments = new JSONObject().put("location", "Seoul");
                service.invokeLocal("weather_get_current", arguments, "in-app-ui");
            } catch (Exception error) {
                runOnUiThread(() -> appendEvent(RuntimeEvent.error("In-app request failed", safeMessage(error))));
            } finally {
                runOnUiThread(() -> localCallButton.setEnabled(bound && service != null && service.isReady()));
            }
        }, "in-app-tool-call").start();
    }

    private void showApproval(ToolApprovalRequest request) {
        String callId = request.getCall().getId();
        if (!bound || service == null || isFinishing() || !visibleApprovals.add(callId)) {
            if (service != null) service.resolveApproval(callId, false);
            return;
        }
        String message = RuntimeEvent.toolLabel(request.getCall().getName())
            + "\n\nArguments\n" + request.getCall().getInput().toString()
            + "\n\nThis action changes alert rules stored by the app.";
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Approve app action?")
            .setMessage(message)
            .setNegativeButton("Deny", (currentDialog, which) -> resolveApproval(callId, false))
            .setPositiveButton("Approve", (currentDialog, which) -> resolveApproval(callId, true))
            .setOnCancelListener(currentDialog -> resolveApproval(callId, false))
            .create();
        dialog.setOnDismissListener(currentDialog -> visibleApprovals.remove(callId));
        dialog.show();
    }

    private void resolveApproval(String callId, boolean approved) {
        AgentToolService currentService = service;
        if (currentService != null) currentService.resolveApproval(callId, approved);
    }

    private void appendEvent(RuntimeEvent event) {
        events.add(event);
        while (events.size() > 32) events.remove(0);
        lastEvent = event;
        if (event.getKind() == RuntimeEvent.Kind.ERROR) {
            statusView.setText("● ONLINE · latest execution failed");
            statusView.setTextColor(Color.rgb(255, 152, 152));
        } else if (event.getKind() == RuntimeEvent.Kind.APPROVAL) {
            setOnlineStatus("Waiting for mobile approval");
        } else {
            setOnlineStatus(event.getKind() == RuntimeEvent.Kind.RECEIVED ? "Executing tool" : "Waiting for requests");
        }
        renderTimeline();
        renderRawEvent();
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void renderTimeline() {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < events.size(); index += 1) {
            if (index > 0) output.append("\n\n");
            output.append(events.get(index).displayText());
        }
        eventView.setText(output.length() == 0 ? "No requests have run yet." : output.toString());
    }

    private void renderRawEvent() {
        String raw = lastEvent == null ? "" : lastEvent.getRawJson();
        boolean hasRaw = !raw.isEmpty();
        rawToggleButton.setEnabled(hasRaw);
        if (!hasRaw) rawVisible = false;
        rawView.setVisibility(rawVisible && hasRaw ? View.VISIBLE : View.GONE);
        rawView.setText(raw);
        rawToggleButton.setText(rawVisible && hasRaw ? "Hide raw JSON" : "Show latest raw JSON");
    }

    private void setOnlineStatus(String detail) {
        statusView.setText("● ONLINE · " + detail + "\n  127.0.0.1:" + AgentToolService.PORT + " · FOREGROUND SERVICE");
        statusView.setTextColor(ACCENT);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            service.unregisterApprovalListener(approvalListener);
            service.unregisterListener(runtimeListener);
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private GradientDrawable cardBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), Color.rgb(33, 59, 82));
        return background;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
