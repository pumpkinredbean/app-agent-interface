package io.github.pumpkinredbean.appagent.example.weather;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class OpenMeteoClient {
    private static final String GEOCODING_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final String CURRENT_VARIABLES = String.join(",",
        "temperature_2m",
        "apparent_temperature",
        "relative_humidity_2m",
        "precipitation",
        "weather_code",
        "wind_speed_10m",
        "is_day"
    );
    private static final String DAILY_VARIABLES = String.join(",",
        "weather_code",
        "temperature_2m_max",
        "temperature_2m_min",
        "precipitation_probability_max",
        "sunrise",
        "sunset"
    );

    JSONObject current(String locationQuery) throws WeatherToolException {
        return current(resolveLocation(locationQuery));
    }

    JSONObject forecast(String locationQuery, int days) throws WeatherToolException {
        return forecast(resolveLocation(locationQuery), days);
    }

    JSONObject compare(JSONArray locationQueries) throws WeatherToolException {
        try {
            JSONArray locations = new JSONArray();
            JSONObject warmest = null;
            JSONObject wettest = null;
            double warmestTemperature = -Double.MAX_VALUE;
            double wettestPrecipitation = -Double.MAX_VALUE;

            for (int index = 0; index < locationQueries.length(); index += 1) {
                String query = locationQueries.getString(index).trim();
                JSONObject currentResult = current(query);
                JSONObject item = new JSONObject()
                    .put("location", currentResult.getJSONObject("location"))
                    .put("observedAt", currentResult.getString("observedAt"))
                    .put("weather", currentResult.getJSONObject("weather"));
                locations.put(item);

                JSONObject weather = item.getJSONObject("weather");
                double temperature = weather.getDouble("temperatureCelsius");
                double precipitation = weather.getDouble("precipitationMm");
                if (temperature > warmestTemperature) {
                    warmest = item;
                    warmestTemperature = temperature;
                }
                if (precipitation > wettestPrecipitation) {
                    wettest = item;
                    wettestPrecipitation = precipitation;
                }
            }

            JSONObject comparison = providerEnvelope()
                .put("locationCount", locations.length())
                .put("locations", locations);
            if (warmest != null) {
                comparison.put("warmest", new JSONObject()
                    .put("location", warmest.getJSONObject("location").getString("name"))
                    .put("temperatureCelsius", warmestTemperature));
            }
            if (wettest != null) {
                comparison.put("highestCurrentPrecipitation", new JSONObject()
                    .put("location", wettest.getJSONObject("location").getString("name"))
                    .put("precipitationMm", wettestPrecipitation));
            }
            return comparison;
        } catch (JSONException error) {
            throw schemaError(error);
        }
    }

    ResolvedLocation resolveLocation(String query) throws WeatherToolException {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            throw new WeatherToolException("INVALID_ARGUMENT", "location is required", false);
        }
        String url = GEOCODING_ENDPOINT
            + "?name=" + encode(normalized)
            + "&count=1&language=en&format=json";
        JSONObject response = fetch(url);
        try {
            JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) {
                throw new WeatherToolException(
                    "LOCATION_NOT_FOUND",
                    "Open-Meteo could not resolve the location: " + normalized,
                    false
                );
            }
            JSONObject result = results.getJSONObject(0);
            String timezone = result.optString("timezone", "GMT");
            return new ResolvedLocation(
                normalized,
                result.getString("name"),
                result.optString("admin1"),
                result.optString("country"),
                result.getDouble("latitude"),
                result.getDouble("longitude"),
                timezone.isBlank() ? "GMT" : timezone
            );
        } catch (JSONException error) {
            throw schemaError(error);
        }
    }

    JSONObject current(ResolvedLocation location) throws WeatherToolException {
        JSONObject response = fetch(forecastUrl(location, 1, true));
        try {
            JSONObject current = response.getJSONObject("current");
            int weatherCode = current.getInt("weather_code");
            JSONObject weather = new JSONObject()
                .put("temperatureCelsius", current.getDouble("temperature_2m"))
                .put("apparentTemperatureCelsius", current.getDouble("apparent_temperature"))
                .put("relativeHumidityPercent", current.getInt("relative_humidity_2m"))
                .put("precipitationMm", current.getDouble("precipitation"))
                .put("weatherCode", weatherCode)
                .put("condition", conditionLabel(weatherCode))
                .put("windSpeedKmh", current.getDouble("wind_speed_10m"))
                .put("isDay", current.optInt("is_day", 0) == 1);

            return providerEnvelope()
                .put("location", location.toJson())
                .put("observedAt", current.getString("time"))
                .put("weather", weather);
        } catch (JSONException error) {
            throw schemaError(error);
        }
    }

    JSONObject forecast(ResolvedLocation location, int days) throws WeatherToolException {
        if (days < 1 || days > 16) {
            throw new WeatherToolException("INVALID_ARGUMENT", "days must be between 1 and 16", false);
        }
        JSONObject response = fetch(forecastUrl(location, days, false));
        try {
            JSONObject daily = response.getJSONObject("daily");
            JSONArray dates = daily.getJSONArray("time");
            JSONArray weatherCodes = daily.getJSONArray("weather_code");
            JSONArray maximums = daily.getJSONArray("temperature_2m_max");
            JSONArray minimums = daily.getJSONArray("temperature_2m_min");
            JSONArray probabilities = daily.getJSONArray("precipitation_probability_max");
            JSONArray sunrises = daily.getJSONArray("sunrise");
            JSONArray sunsets = daily.getJSONArray("sunset");
            JSONArray forecastDays = new JSONArray();

            for (int index = 0; index < dates.length(); index += 1) {
                boolean weatherAvailable = !weatherCodes.isNull(index);
                int weatherCode = weatherAvailable ? weatherCodes.getInt(index) : -1;
                JSONObject day = new JSONObject()
                    .put("date", dates.getString(index))
                    .put("weatherCode", weatherAvailable ? weatherCode : JSONObject.NULL)
                    .put("condition", weatherAvailable ? conditionLabel(weatherCode) : "Unavailable")
                    .put("maximumTemperatureCelsius", nullableNumber(maximums, index))
                    .put("minimumTemperatureCelsius", nullableNumber(minimums, index))
                    .put("maximumPrecipitationProbabilityPercent", nullableNumber(probabilities, index))
                    .put("sunrise", nullableString(sunrises, index))
                    .put("sunset", nullableString(sunsets, index));
                forecastDays.put(day);
            }

            return providerEnvelope()
                .put("location", location.toJson())
                .put("forecastDays", forecastDays.length())
                .put("days", forecastDays);
        } catch (JSONException error) {
            throw schemaError(error);
        }
    }

    JSONObject dailyForDate(ResolvedLocation location, String date) throws WeatherToolException {
        JSONObject forecast = forecast(location, 16);
        try {
            JSONArray days = forecast.getJSONArray("days");
            for (int index = 0; index < days.length(); index += 1) {
                JSONObject day = days.getJSONObject(index);
                if (date.equals(day.getString("date"))) return day;
            }
            throw new WeatherToolException(
                "FORECAST_DATE_UNAVAILABLE",
                "Open-Meteo did not return a forecast for " + date,
                true
            );
        } catch (JSONException error) {
            throw schemaError(error);
        }
    }

    static double alertMetric(WeatherAlert alert, JSONObject day) throws WeatherToolException {
        try {
            return switch (alert.condition) {
                case WeatherAlert.PRECIPITATION_ABOVE ->
                    day.getDouble("maximumPrecipitationProbabilityPercent");
                case WeatherAlert.TEMPERATURE_ABOVE ->
                    day.getDouble("maximumTemperatureCelsius");
                case WeatherAlert.TEMPERATURE_BELOW ->
                    day.getDouble("minimumTemperatureCelsius");
                default -> throw new WeatherToolException(
                    "INVALID_ALERT_CONDITION",
                    "Unsupported alert condition: " + alert.condition,
                    false
                );
            };
        } catch (JSONException error) {
            throw new WeatherToolException("WEATHER_UPSTREAM_SCHEMA_ERROR", "Weather data was incomplete", true, error);
        }
    }

    static boolean isTriggered(WeatherAlert alert, double actual) {
        return WeatherAlert.TEMPERATURE_BELOW.equals(alert.condition)
            ? actual <= alert.threshold
            : actual >= alert.threshold;
    }

    private String forecastUrl(ResolvedLocation location, int days, boolean includeCurrent) {
        List<String> query = new ArrayList<>();
        query.add("latitude=" + location.latitude);
        query.add("longitude=" + location.longitude);
        if (includeCurrent) query.add("current=" + encode(CURRENT_VARIABLES));
        else query.add("daily=" + encode(DAILY_VARIABLES));
        query.add("timezone=" + encode(location.timezone));
        query.add("forecast_days=" + days);
        return FORECAST_ENDPOINT + "?" + String.join("&", query);
    }

    private JSONObject providerEnvelope() throws JSONException {
        return new JSONObject()
            .put("provider", "Open-Meteo")
            .put("attribution", new JSONObject()
                .put("text", "Weather data by Open-Meteo.com")
                .put("url", "https://open-meteo.com/")
                .put("license", "CC BY 4.0"));
    }

    private JSONObject fetch(String url) throws WeatherToolException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AppAgentInterface-WeatherAgent-Example/0.1");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            String body = readBody(stream);
            if (status < 200 || status >= 300) {
                boolean retryable = status == 429 || status >= 500;
                String upstreamReason = "";
                try {
                    upstreamReason = new JSONObject(body).optString("reason", "");
                } catch (JSONException ignored) {
                    // The controlled error below never exposes an upstream HTML response.
                }
                String message = "Open-Meteo request failed with HTTP " + status;
                if (!upstreamReason.isBlank()) message += ": " + upstreamReason;
                throw new WeatherToolException("WEATHER_UPSTREAM_HTTP_ERROR", message, retryable);
            }
            return new JSONObject(body);
        } catch (WeatherToolException error) {
            throw error;
        } catch (IOException error) {
            throw new WeatherToolException(
                "WEATHER_UPSTREAM_UNAVAILABLE",
                "Open-Meteo could not be reached",
                true,
                error
            );
        } catch (IllegalArgumentException | JSONException error) {
            throw new WeatherToolException(
                "WEATHER_UPSTREAM_SCHEMA_ERROR",
                "Open-Meteo returned an invalid response",
                true,
                error
            );
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Object nullableNumber(JSONArray values, int index) throws JSONException {
        return values.isNull(index) ? JSONObject.NULL : values.getDouble(index);
    }

    private static Object nullableString(JSONArray values, int index) throws JSONException {
        return values.isNull(index) ? JSONObject.NULL : values.getString(index);
    }

    private static WeatherToolException schemaError(JSONException error) {
        return new WeatherToolException(
            "WEATHER_UPSTREAM_SCHEMA_ERROR",
            "Open-Meteo returned incomplete weather data",
            true,
            error
        );
    }

    static String conditionLabel(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snowfall";
            case 77 -> "Snow grains";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }
}
