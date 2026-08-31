package io.github.pumpkinredbean.appagent.example.weather;

import org.json.JSONException;
import org.json.JSONObject;

final class ResolvedLocation {
    final String query;
    final String name;
    final String admin1;
    final String country;
    final double latitude;
    final double longitude;
    final String timezone;

    ResolvedLocation(
        String query,
        String name,
        String admin1,
        String country,
        double latitude,
        double longitude,
        String timezone
    ) {
        this.query = query;
        this.name = name;
        this.admin1 = admin1;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timezone = timezone;
    }

    JSONObject toJson() throws JSONException {
        JSONObject value = new JSONObject()
            .put("query", query)
            .put("name", name)
            .put("country", country)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timezone", timezone);
        if (!admin1.isBlank() && !admin1.equalsIgnoreCase(name)) value.put("region", admin1);
        return value;
    }

    String displayName() {
        if (country.isBlank()) return name;
        return name + ", " + country;
    }
}
