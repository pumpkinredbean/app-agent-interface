package io.github.pumpkinredbean.appagent.example.weather;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class WeatherAlert {
    static final String PRECIPITATION_ABOVE = "PRECIPITATION_PROBABILITY_ABOVE";
    static final String TEMPERATURE_ABOVE = "TEMPERATURE_ABOVE";
    static final String TEMPERATURE_BELOW = "TEMPERATURE_BELOW";

    final String id;
    final ResolvedLocation location;
    final String date;
    final String condition;
    final double threshold;
    final long createdAtEpochMs;
    final long triggeredAtEpochMs;

    WeatherAlert(
        String id,
        ResolvedLocation location,
        String date,
        String condition,
        double threshold,
        long createdAtEpochMs,
        long triggeredAtEpochMs
    ) {
        this.id = id;
        this.location = location;
        this.date = date;
        this.condition = condition;
        this.threshold = threshold;
        this.createdAtEpochMs = createdAtEpochMs;
        this.triggeredAtEpochMs = triggeredAtEpochMs;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
            .put("alertId", id)
            .put("location", location.toJson())
            .put("date", date)
            .put("condition", condition)
            .put("threshold", threshold)
            .put("thresholdUnit", thresholdUnit())
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("status", triggeredAtEpochMs > 0 ? "TRIGGERED" : "SCHEDULED")
            .put("triggeredAtEpochMs", triggeredAtEpochMs > 0 ? triggeredAtEpochMs : JSONObject.NULL);
    }

    JSONObject toStoredJson() throws JSONException {
        return new JSONObject()
            .put("id", id)
            .put("query", location.query)
            .put("name", location.name)
            .put("admin1", location.admin1)
            .put("country", location.country)
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("timezone", location.timezone)
            .put("date", date)
            .put("condition", condition)
            .put("threshold", threshold)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("triggeredAtEpochMs", triggeredAtEpochMs);
    }

    static WeatherAlert fromStoredJson(JSONObject value) throws JSONException {
        ResolvedLocation location = new ResolvedLocation(
            value.getString("query"),
            value.getString("name"),
            value.optString("admin1"),
            value.optString("country"),
            value.getDouble("latitude"),
            value.getDouble("longitude"),
            value.getString("timezone")
        );
        return new WeatherAlert(
            value.getString("id"),
            location,
            value.getString("date"),
            value.getString("condition"),
            value.getDouble("threshold"),
            value.getLong("createdAtEpochMs"),
            value.optLong("triggeredAtEpochMs", 0)
        );
    }

    WeatherAlert triggered(long epochMs) {
        return new WeatherAlert(id, location, date, condition, threshold, createdAtEpochMs, epochMs);
    }

    String thresholdUnit() {
        return PRECIPITATION_ABOVE.equals(condition) ? "percent" : "celsius";
    }

    String notificationText(double actual) {
        String metric = PRECIPITATION_ABOVE.equals(condition)
            ? String.format(Locale.US, "precipitation probability %.0f%%", actual)
            : String.format(Locale.US, "temperature %.1f°C", actual);
        String thresholdText = PRECIPITATION_ABOVE.equals(condition)
            ? String.format(Locale.US, "%.0f%%", threshold)
            : String.format(Locale.US, "%.1f°C", threshold);
        String comparison = TEMPERATURE_BELOW.equals(condition) ? "fell below" : "reached";
        return location.name + ": " + metric + " " + comparison + " your " + thresholdText + " threshold.";
    }
}
