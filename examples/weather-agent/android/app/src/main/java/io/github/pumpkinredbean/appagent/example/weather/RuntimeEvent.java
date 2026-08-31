package io.github.pumpkinredbean.appagent.example.weather;

import io.github.pumpkinredbean.appagent.ToolApprovalRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RuntimeEvent {
    public enum Kind { READY, RECEIVED, APPROVAL, COMPLETED, ERROR }

    private final Kind kind;
    private final long occurredAtEpochMs;
    private final String requestId;
    private final String title;
    private final String detail;
    private final String rawJson;

    private RuntimeEvent(
        Kind kind,
        long occurredAtEpochMs,
        String requestId,
        String title,
        String detail,
        String rawJson
    ) {
        this.kind = kind;
        this.occurredAtEpochMs = occurredAtEpochMs;
        this.requestId = requestId == null ? "" : requestId;
        this.title = title;
        this.detail = detail == null ? "" : detail;
        this.rawJson = rawJson == null ? "" : rawJson;
    }

    public static RuntimeEvent ready(String detail) {
        return new RuntimeEvent(Kind.READY, System.currentTimeMillis(), "", "Waiting for requests", detail, "");
    }

    public static RuntimeEvent received(JSONObject call) {
        String source = call.optString("source", "remote-agent");
        String sourceLabel = switch (source) {
            case "desktop-agent" -> "Desktop agent request";
            case "in-app-ui" -> "In-app agent request";
            case "test" -> "Test request";
            default -> "External request";
        };
        String tool = call.optString("name", "unknown");
        JSONObject arguments = call.optJSONObject("input");
        return new RuntimeEvent(
            Kind.RECEIVED,
            System.currentTimeMillis(),
            call.optString("id"),
            sourceLabel + " received",
            toolLabel(tool) + " · " + argumentSummary(tool, arguments),
            call.toString()
        );
    }

    public static RuntimeEvent approvalRequested(ToolApprovalRequest request) {
        return new RuntimeEvent(
            Kind.APPROVAL,
            System.currentTimeMillis(),
            request.getCall().getId(),
            "Mobile approval required",
            toolLabel(request.getCall().getName()) + " is waiting for a decision on this screen",
            request.getCall().getInput().toString()
        );
    }

    public static RuntimeEvent approvalResolved(ToolApprovalRequest request, boolean approved) {
        return new RuntimeEvent(
            Kind.APPROVAL,
            System.currentTimeMillis(),
            request.getCall().getId(),
            approved ? "Mobile action approved" : "Mobile action denied",
            toolLabel(request.getCall().getName()),
            request.getCall().getInput().toString()
        );
    }

    public static RuntimeEvent completed(JSONObject call, JSONObject result) {
        boolean ok = result.optBoolean("ok", false);
        String tool = call.optString("name", "unknown");
        if (!ok) {
            JSONObject error = result.optJSONObject("error");
            String message = error == null ? "Unknown error" : error.optString("message", "Unknown error");
            return new RuntimeEvent(
                Kind.ERROR,
                System.currentTimeMillis(),
                call.optString("id"),
                "App tool execution failed",
                toolLabel(tool) + " · " + message,
                result.toString()
            );
        }
        return new RuntimeEvent(
            Kind.COMPLETED,
            System.currentTimeMillis(),
            call.optString("id"),
            "App tool execution completed",
            resultSummary(tool, result.optJSONObject("output")),
            result.toString()
        );
    }

    public static RuntimeEvent alertTriggered(WeatherAlert alert, double actualValue) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("alert", alert.toJson()).put("actualValue", actualValue);
        } catch (JSONException ignored) {
            // Display text still contains the useful result.
        }
        return new RuntimeEvent(
            Kind.COMPLETED,
            System.currentTimeMillis(),
            alert.id,
            "Weather alert triggered",
            alert.notificationText(actualValue),
            raw.toString()
        );
    }

    public static RuntimeEvent error(String title, String detail) {
        return new RuntimeEvent(Kind.ERROR, System.currentTimeMillis(), "", title, detail, "");
    }

    public Kind getKind() {
        return kind;
    }

    public String getRawJson() {
        return rawJson;
    }

    public String displayText() {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(occurredAtEpochMs));
        String badge = switch (kind) {
            case READY -> "READY";
            case RECEIVED -> "RECEIVED";
            case APPROVAL -> "APPROVAL";
            case COMPLETED -> "COMPLETED";
            case ERROR -> "ERROR";
        };
        String id = requestId.isEmpty() ? "" : "  #" + shortId(requestId);
        return time + "  [" + badge + "]" + id + "\n"
            + title + (detail.isEmpty() ? "" : "\n" + detail);
    }

    static String toolLabel(String tool) {
        return switch (tool) {
            case "weather_get_current" -> "Current weather";
            case "weather_get_forecast" -> "Daily forecast";
            case "weather_compare_locations" -> "Location comparison";
            case "weather_alert_list" -> "Saved alerts";
            case "weather_alert_create" -> "Create weather alert";
            case "weather_alert_delete" -> "Delete weather alert";
            default -> tool;
        };
    }

    private static String argumentSummary(String tool, JSONObject arguments) {
        if (arguments == null) return "No arguments";
        if ("weather_get_current".equals(tool) || "weather_get_forecast".equals(tool)) {
            String location = arguments.optString("location", "Location not specified");
            int days = arguments.optInt("days", 0);
            return days > 0 ? location + " · " + days + " days" : location;
        }
        if ("weather_compare_locations".equals(tool)) {
            JSONArray locations = arguments.optJSONArray("locations");
            return locations == null ? "Locations not specified" : join(locations, " ↔ ");
        }
        if ("weather_alert_create".equals(tool)) {
            return arguments.optString("location") + " · " + arguments.optString("date")
                + " · " + arguments.optString("condition");
        }
        if ("weather_alert_delete".equals(tool)) return arguments.optString("alertId", "Alert not specified");
        if ("weather_alert_list".equals(tool)) return "All local alert rules";
        return "Arguments validated";
    }

    private static String resultSummary(String tool, JSONObject payload) {
        if (payload == null) return "ToolResult received";
        if ("weather_get_current".equals(tool)) {
            JSONObject location = payload.optJSONObject("location");
            JSONObject weather = payload.optJSONObject("weather");
            if (location != null && weather != null) {
                return String.format(
                    Locale.US,
                    "%s · %.1f°C · %s",
                    location.optString("name"),
                    weather.optDouble("temperatureCelsius"),
                    weather.optString("condition")
                );
            }
        }
        if ("weather_get_forecast".equals(tool)) {
            JSONObject location = payload.optJSONObject("location");
            return (location == null ? "Forecast" : location.optString("name"))
                + " · " + payload.optInt("forecastDays") + " days";
        }
        if ("weather_compare_locations".equals(tool)) {
            return payload.optInt("locationCount") + " locations compared";
        }
        if ("weather_alert_list".equals(tool)) return payload.optInt("alertCount") + " local alerts";
        if ("weather_alert_create".equals(tool)) {
            JSONObject alert = payload.optJSONObject("alert");
            return alert == null ? "Alert scheduled" : "Alert scheduled · " + shortId(alert.optString("alertId"));
        }
        if ("weather_alert_delete".equals(tool)) return "Local alert deleted";
        return "ToolResult received";
    }

    private static String join(JSONArray values, String separator) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length(); index += 1) {
            if (index > 0) result.append(separator);
            result.append(values.optString(index));
        }
        return result.toString();
    }

    private static String shortId(String value) {
        return value.substring(0, Math.min(8, value.length()));
    }
}
