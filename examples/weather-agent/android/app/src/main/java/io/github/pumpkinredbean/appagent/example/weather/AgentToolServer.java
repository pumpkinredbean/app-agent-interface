package io.github.pumpkinredbean.appagent.example.weather;

import android.content.Context;
import android.util.Log;

import io.github.pumpkinredbean.appagent.ToolApproval;
import io.github.pumpkinredbean.appagent.ToolApprovalController;
import io.github.pumpkinredbean.appagent.ToolCall;
import io.github.pumpkinredbean.appagent.ToolExecutionException;
import io.github.pumpkinredbean.appagent.ToolJson;
import io.github.pumpkinredbean.appagent.ToolProtocol;
import io.github.pumpkinredbean.appagent.ToolResult;
import io.github.pumpkinredbean.appagent.ToolRuntime;
import io.github.pumpkinredbean.appagent.ToolSpec;
import io.github.pumpkinredbean.appagent.transport.loopback.LoopbackToolServer;
import io.github.pumpkinredbean.appagent.transport.loopback.LoopbackToolServerListener;

import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Weather-specific sample registrations built on the domain-neutral Kotlin ToolCall SDK. */
public final class AgentToolServer implements Closeable {
    public static final String PROTOCOL = ToolProtocol.VERSION;
    private static final String TAG = "WeatherAlertRuntime";

    public interface EventListener {
        void onEvent(RuntimeEvent event);
    }

    public interface AlertMutationListener {
        void onAlertsChanged();
    }

    public interface TriggeredAlertListener {
        void onTriggered(WeatherAlert alert, double actualValue);
    }

    private final EventListener listener;
    private final AlertMutationListener alertMutationListener;
    private final OpenMeteoClient weatherClient = new OpenMeteoClient();
    private final WeatherAlertRepository alertRepository;
    private final ToolRuntime runtime;
    private final LoopbackToolServer server;

    public AgentToolServer(
        Context context,
        int port,
        EventListener listener,
        ToolApprovalController approvalController,
        AlertMutationListener alertMutationListener
    ) {
        this.listener = listener;
        this.alertMutationListener = alertMutationListener;
        this.alertRepository = new WeatherAlertRepository(context.getApplicationContext());
        this.runtime = ToolRuntime.withDecisionApproval(approvalController);
        registerTools();
        this.server = new LoopbackToolServer(
            runtime,
            port,
            "weather-agent-android-example",
            null,
            new LoopbackToolServerListener() {
                @Override
                public void onCallReceived(ToolCall call) {
                    publishReceived(call, "desktop-agent");
                }

                @Override
                public void onCallCompleted(ToolCall call, ToolResult result, long durationMs) {
                    publishCompleted(call, result, "desktop-agent");
                }

                @Override
                public void onServerError(Throwable error) {
                    listener.onEvent(RuntimeEvent.error("Server error", safeMessage(error)));
                }
            }
        );
    }

    public void start() throws IOException {
        server.start();
    }

    public JSONObject invokeLocal(String tool, JSONObject arguments, String source) throws JSONException {
        ToolCall call = new ToolCall(
            UUID.randomUUID().toString(),
            tool,
            ToolJson.parseObject(arguments.toString())
        );
        publishReceived(call, source);
        ToolResult result = runtime.executeBlocking(call, Map.of("source", source));
        publishCompleted(call, result, source);
        return new JSONObject(ToolProtocol.encodeResult(result));
    }

    public void evaluateAlerts(TriggeredAlertListener triggeredListener) {
        try {
            List<WeatherAlert> alerts = alertRepository.list();
            for (WeatherAlert alert : alerts) {
                try {
                    LocalDate today = LocalDate.now(ZoneId.of(alert.location.timezone));
                    if (alert.triggeredAtEpochMs > 0 || LocalDate.parse(alert.date).isBefore(today)) continue;
                    JSONObject daily = weatherClient.dailyForDate(alert.location, alert.date);
                    double actual = OpenMeteoClient.alertMetric(alert, daily);
                    if (!OpenMeteoClient.isTriggered(alert, actual)) continue;
                    WeatherAlert triggered = alertRepository.markTriggered(alert.id, System.currentTimeMillis());
                    triggeredListener.onTriggered(triggered, actual);
                    listener.onEvent(RuntimeEvent.alertTriggered(triggered, actual));
                } catch (WeatherToolException | DateTimeException error) {
                    // A transient evaluation failure is retried on the next service interval.
                    Log.w(TAG, "Alert evaluation deferred for " + alert.id + ": " + safeMessage(error));
                }
            }
        } catch (WeatherToolException error) {
            // A corrupt or unavailable local store is surfaced through direct tool calls.
            Log.e(TAG, "Alert store unavailable during evaluation: " + safeMessage(error));
        }
    }

    @Override
    public void close() {
        server.close();
    }

    private void registerTools() {
        runtime.registerBlocking(
            spec(
                "weather_get_current",
                "Returns current weather conditions for one named location using Open-Meteo. "
                    + "Use it when the user asks about weather now, current temperature, humidity, rain, or wind. "
                    + "Use weather_get_forecast for future dates and weather_compare_locations for two or more places.",
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {
                      "type": "string",
                      "minLength": 1,
                      "description": "City or place name, for example Seoul or Berlin"
                    }
                  },
                  "required": ["location"],
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.NONE,
            (input, context) -> invokeWeather("weather_get_current", input)
        );

        runtime.registerBlocking(
            spec(
                "weather_get_forecast",
                "Returns a daily weather forecast for one named location using Open-Meteo. "
                    + "Use it for future weather, an outlook, or a specific upcoming day. "
                    + "Use weather_get_current when the request is only about conditions now.",
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {
                      "type": "string",
                      "minLength": 1,
                      "description": "City or place name"
                    },
                    "days": {
                      "type": "integer",
                      "minimum": 1,
                      "maximum": 7,
                      "description": "Number of forecast days. Omit to use 3 days."
                    }
                  },
                  "required": ["location"],
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.NONE,
            (input, context) -> invokeWeather("weather_get_forecast", input)
        );

        runtime.registerBlocking(
            spec(
                "weather_compare_locations",
                "Returns current weather for two to four named locations in one comparison. "
                    + "Use it when the user compares places, asks which is warmer, or asks where it is raining. "
                    + "Do not use it for only one location.",
                """
                {
                  "type": "object",
                  "properties": {
                    "locations": {
                      "type": "array",
                      "minItems": 2,
                      "maxItems": 4,
                      "items": { "type": "string", "minLength": 1 },
                      "description": "Two to four city or place names to compare"
                    }
                  },
                  "required": ["locations"],
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.NONE,
            (input, context) -> invokeWeather("weather_compare_locations", input)
        );

        runtime.registerBlocking(
            spec(
                "weather_alert_list",
                "Lists weather alert rules saved locally by this app, including scheduled and triggered rules. "
                    + "Use it when the user asks which weather alerts or reminders already exist. "
                    + "Do not use it to create or remove an alert.",
                """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.NONE,
            (input, context) -> invokeWeather("weather_alert_list", input)
        );

        runtime.registerBlocking(
            spec(
                "weather_alert_create",
                "Creates a one-time weather alert stored by this app and evaluated by its Android service. "
                    + "Use it only when the user explicitly asks to alert, remind, or notify them about forecast conditions. "
                    + "PRECIPITATION_PROBABILITY_ABOVE uses a percentage threshold; temperature conditions use Celsius. "
                    + "Do not use it for a forecast question that does not request an alert.",
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {
                      "type": "string",
                      "minLength": 1,
                      "description": "City or place name"
                    },
                    "date": {
                      "type": "string",
                      "pattern": "^\\\\d{4}-\\\\d{2}-\\\\d{2}$",
                      "description": "Local forecast date in YYYY-MM-DD format, within the next 16 days"
                    },
                    "condition": {
                      "type": "string",
                      "enum": [
                        "PRECIPITATION_PROBABILITY_ABOVE",
                        "TEMPERATURE_ABOVE",
                        "TEMPERATURE_BELOW"
                      ]
                    },
                    "threshold": {
                      "type": "number",
                      "minimum": -100,
                      "maximum": 100,
                      "description": "Percentage for precipitation or degrees Celsius for temperature"
                    }
                  },
                  "required": ["location", "date", "condition", "threshold"],
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.MOBILE,
            (input, context) -> invokeWeather("weather_alert_create", input)
        );

        runtime.registerBlocking(
            spec(
                "weather_alert_delete",
                "Deletes one weather alert rule stored locally by this app. "
                    + "Use it only when the user explicitly asks to cancel, delete, or remove an existing alert and provides its alertId. "
                    + "Use weather_alert_list first when the alertId is unknown.",
                """
                {
                  "type": "object",
                  "properties": {
                    "alertId": {
                      "type": "string",
                      "minLength": 1,
                      "description": "Identifier returned by weather_alert_create or weather_alert_list"
                    }
                  },
                  "required": ["alertId"],
                  "additionalProperties": false
                }
                """
            ),
            ToolApproval.MOBILE,
            (input, context) -> invokeWeather("weather_alert_delete", input)
        );
    }

    private ToolSpec spec(String name, String description, String inputSchema) {
        return new ToolSpec(name, description, ToolJson.parseObject(inputSchema));
    }

    private JsonElement invokeWeather(String tool, JsonObject input) {
        try {
            JSONObject arguments = new JSONObject(input.toString());
            JSONObject result = switch (tool) {
                case "weather_get_current" -> weatherClient.current(arguments.getString("location"));
                case "weather_get_forecast" -> weatherClient.forecast(
                    arguments.getString("location"),
                    arguments.optInt("days", 3)
                );
                case "weather_compare_locations" -> weatherClient.compare(arguments.getJSONArray("locations"));
                case "weather_alert_list" -> listAlerts();
                case "weather_alert_create" -> createAlert(arguments);
                case "weather_alert_delete" -> deleteAlert(arguments);
                default -> throw new WeatherToolException("TOOL_NOT_FOUND", "Unknown tool: " + tool, false);
            };
            return ToolJson.parseElement(result.toString());
        } catch (WeatherToolException error) {
            throw new ToolExecutionException(error.code, error.getMessage(), error.retryable, error);
        } catch (JSONException error) {
            throw new ToolExecutionException("INVALID_APP_DATA", "The app returned invalid JSON", false, error);
        }
    }

    private JSONObject listAlerts() throws WeatherToolException, JSONException {
        List<WeatherAlert> alerts = alertRepository.list();
        JSONArray values = new JSONArray();
        for (WeatherAlert alert : alerts) values.put(alert.toJson());
        return new JSONObject()
            .put("storage", "ANDROID_SHARED_PREFERENCES")
            .put("alertCount", alerts.size())
            .put("alerts", values);
    }

    private JSONObject createAlert(JSONObject arguments) throws WeatherToolException, JSONException {
        String condition = arguments.getString("condition");
        double threshold = arguments.getDouble("threshold");
        validateCondition(condition, threshold);
        ResolvedLocation location = weatherClient.resolveLocation(arguments.getString("location"));
        String dateText = arguments.getString("date");
        validateAlertDate(dateText, location.timezone);

        WeatherAlert alert = new WeatherAlert(
            "alert_" + UUID.randomUUID(),
            location,
            dateText,
            condition,
            threshold,
            System.currentTimeMillis(),
            0
        );
        alertRepository.create(alert);
        alertMutationListener.onAlertsChanged();
        return new JSONObject()
            .put("scheduled", true)
            .put("evaluation", "Android foreground service checks the rule periodically")
            .put("alert", alert.toJson());
    }

    private JSONObject deleteAlert(JSONObject arguments) throws WeatherToolException, JSONException {
        String alertId = arguments.getString("alertId").trim();
        if (alertId.isEmpty()) throw new WeatherToolException("INVALID_ARGUMENT", "alertId is required", false);
        WeatherAlert deleted = alertRepository.delete(alertId);
        alertMutationListener.onAlertsChanged();
        return new JSONObject()
            .put("deleted", true)
            .put("alert", deleted.toJson());
    }

    private void validateCondition(String condition, double threshold) throws WeatherToolException {
        boolean supported = WeatherAlert.PRECIPITATION_ABOVE.equals(condition)
            || WeatherAlert.TEMPERATURE_ABOVE.equals(condition)
            || WeatherAlert.TEMPERATURE_BELOW.equals(condition);
        if (!supported) {
            throw new WeatherToolException("INVALID_ALERT_CONDITION", "Unsupported alert condition: " + condition, false);
        }
        if (WeatherAlert.PRECIPITATION_ABOVE.equals(condition) && (threshold < 0 || threshold > 100)) {
            throw new WeatherToolException(
                "INVALID_ALERT_THRESHOLD",
                "A precipitation probability threshold must be between 0 and 100",
                false
            );
        }
    }

    private void validateAlertDate(String dateText, String timezone) throws WeatherToolException {
        try {
            LocalDate date = LocalDate.parse(dateText);
            LocalDate today = LocalDate.now(ZoneId.of(timezone));
            if (date.isBefore(today) || date.isAfter(today.plusDays(15))) {
                throw new WeatherToolException(
                    "ALERT_DATE_OUT_OF_RANGE",
                    "date must be between " + today + " and " + today.plusDays(15) + " for the resolved location",
                    false
                );
            }
        } catch (DateTimeException error) {
            throw new WeatherToolException("INVALID_ALERT_DATE", "date must be a valid YYYY-MM-DD value", false, error);
        }
    }

    private void publishReceived(ToolCall call, String source) {
        try {
            JSONObject callJson = new JSONObject(ToolProtocol.encodeCall(call)).put("source", source);
            listener.onEvent(RuntimeEvent.received(callJson));
        } catch (JSONException error) {
            listener.onEvent(RuntimeEvent.error("Event conversion failed", safeMessage(error)));
        }
    }

    private void publishCompleted(ToolCall call, ToolResult result, String source) {
        try {
            JSONObject callJson = new JSONObject(ToolProtocol.encodeCall(call)).put("source", source);
            JSONObject resultJson = new JSONObject(ToolProtocol.encodeResult(result));
            listener.onEvent(RuntimeEvent.completed(callJson, resultJson));
        } catch (JSONException error) {
            listener.onEvent(RuntimeEvent.error("Event conversion failed", safeMessage(error)));
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
