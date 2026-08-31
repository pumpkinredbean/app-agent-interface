package io.github.pumpkinredbean.appagent.example.weather;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class WeatherAlertRepository {
    private static final String PREFERENCES = "weather-agent-alerts";
    private static final String ALERTS_KEY = "alerts-json-v1";

    private final SharedPreferences preferences;

    WeatherAlertRepository(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized List<WeatherAlert> list() throws WeatherToolException {
        try {
            JSONArray stored = new JSONArray(preferences.getString(ALERTS_KEY, "[]"));
            List<WeatherAlert> alerts = new ArrayList<>();
            for (int index = 0; index < stored.length(); index += 1) {
                alerts.add(WeatherAlert.fromStoredJson(stored.getJSONObject(index)));
            }
            return alerts;
        } catch (JSONException error) {
            throw new WeatherToolException(
                "ALERT_STORE_CORRUPT",
                "The local weather alert store could not be read",
                false,
                error
            );
        }
    }

    synchronized WeatherAlert create(WeatherAlert alert) throws WeatherToolException {
        List<WeatherAlert> alerts = list();
        alerts.add(alert);
        persist(alerts);
        return alert;
    }

    synchronized WeatherAlert delete(String alertId) throws WeatherToolException {
        List<WeatherAlert> alerts = list();
        WeatherAlert deleted = null;
        for (WeatherAlert alert : alerts) {
            if (alert.id.equals(alertId)) {
                deleted = alert;
                break;
            }
        }
        if (deleted == null) {
            throw new WeatherToolException("ALERT_NOT_FOUND", "No local weather alert matched: " + alertId, false);
        }
        alerts.remove(deleted);
        persist(alerts);
        return deleted;
    }

    synchronized WeatherAlert markTriggered(String alertId, long triggeredAtEpochMs) throws WeatherToolException {
        List<WeatherAlert> alerts = list();
        for (int index = 0; index < alerts.size(); index += 1) {
            WeatherAlert alert = alerts.get(index);
            if (!alert.id.equals(alertId)) continue;
            WeatherAlert triggered = alert.triggered(triggeredAtEpochMs);
            alerts.set(index, triggered);
            persist(alerts);
            return triggered;
        }
        throw new WeatherToolException("ALERT_NOT_FOUND", "No local weather alert matched: " + alertId, false);
    }

    private void persist(List<WeatherAlert> alerts) throws WeatherToolException {
        try {
            JSONArray stored = new JSONArray();
            for (WeatherAlert alert : alerts) stored.put(alert.toStoredJson());
            if (!preferences.edit().putString(ALERTS_KEY, stored.toString()).commit()) {
                throw new WeatherToolException("ALERT_STORE_FAILED", "The local weather alert could not be saved", true);
            }
        } catch (JSONException error) {
            throw new WeatherToolException("ALERT_STORE_FAILED", "The local weather alert could not be saved", false, error);
        }
    }
}
