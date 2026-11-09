package com.holi.prdbus;

/** 巴士得很城市目录，包含本地目录和服务商城市编号。 */
final class CityInfo {
    private static android.content.SharedPreferences saved;

    static synchronized void initialize(android.content.Context context) {
        if (saved != null) return;
        saved = context.getApplicationContext().getSharedPreferences("bus_city_directory", 0);
        try {
            org.json.JSONArray rows = new org.json.JSONArray(saved.getString("cities", "[]"));
            for (int i = 0; i < rows.length() && i < 2000; i++) {
                org.json.JSONObject row = rows.getJSONObject(i);
                String name = row.optString("name"), id = row.optString("id");
                if (name.isEmpty() || id.isEmpty()) continue;
                Regions.remember(name, row.optString("province"));
                register(name, id, row.optDouble("lat", 0), row.optDouble("lon", 0));
            }
        } catch (Exception ignored) { }
    }

    static synchronized void persist() {
        if (saved == null) return;
        try {
            org.json.JSONArray rows = new org.json.JSONArray();
            for (CityInfo city : DIRECTORY.values()) {
                org.json.JSONObject row = new org.json.JSONObject();
                row.put("name", city.name).put("id", city.chelaileId);
                row.put("lat", city.lat).put("lon", city.lon).put("province", Regions.province(city.name));
                rows.put(row);
            }
            saved.edit().putString("cities", rows.toString()).apply();
        } catch (Exception ignored) { }
    }
    static synchronized java.util.List<ChelaileDataSource.City> recentPrimaryCatalog() {
        java.util.List<ChelaileDataSource.City> result = new java.util.ArrayList<>();
        if (saved == null) return result;
        long age = System.currentTimeMillis() - saved.getLong("primary_at", 0);
        if (age < 0 || age >= 24 * 60 * 60_000L) return result;
        for (CityInfo city : DIRECTORY.values()) if (!city.chelaileId.startsWith("mg:"))
            result.add(new ChelaileDataSource.City(city.chelaileId, city.name));
        return result;
    }

    static synchronized void primaryRefreshed() {
        if (saved != null) saved.edit().putLong("primary_at", System.currentTimeMillis()).apply();
    }
    private static final java.util.Map<String, CityInfo> DIRECTORY = new java.util.LinkedHashMap<>();
    final String name;
    final double lat;
    final double lon;
    /** 车来了 cityId。 */
    final String chelaileId;

    CityInfo(String name, double lat, double lon, String chelaileId) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.chelaileId = chelaileId;
    }

    static final CityInfo[] PRD_CITIES = {
            new CityInfo("广州", 23.129, 113.264, "040"),
            new CityInfo("深圳", 22.543, 114.058, "014"),
            new CityInfo("佛山", 23.021, 113.122, "019"),
            new CityInfo("东莞", 23.020, 113.752, "008"),
            new CityInfo("中山", 22.517, 113.393, "021"),
            new CityInfo("珠海", 22.271, 113.577, "242"),
            new CityInfo("江门", 22.579, 113.082, "134"),
            new CityInfo("惠州", 23.111, 114.416, "016"),
            new CityInfo("肇庆", 23.047, 112.473, "521"),
    };

    static {
        for (CityInfo city : PRD_CITIES) DIRECTORY.put(city.name, city);
        DIRECTORY.put("香港", new CityInfo("香港", 22.3193, 114.1694, "hk:bus"));
        DIRECTORY.put("澳门", new CityInfo("澳门", 22.1987, 113.5439, "mo:bus"));
    }

    static synchronized CityInfo[] all() { return DIRECTORY.values().toArray(new CityInfo[0]); }

    static synchronized CityInfo find(String city) {
        return DIRECTORY.get(Regions.normalizeCity(city));
    }

    static synchronized CityInfo register(String city, String id, double lat, double lon) {
        String name = Regions.normalizeCity(city);
        CityInfo old = DIRECTORY.get(name);
        if (old != null && RegionalBusActivity.supports(old.chelaileId)) return old;
        if (old != null && id.startsWith("mg:") && !old.chelaileId.startsWith("mg:")) id = old.chelaileId;
        if (old != null && id.startsWith("mg:") && old.chelaileId.equals("mg:" + name)) id = old.chelaileId;
        if (!TransitRules.validCoordinate(lat, lon) && old != null) { lat = old.lat; lon = old.lon; }
        CityInfo value = new CityInfo(name, lat, lon, id);
        DIRECTORY.put(name, value);
        return value;
    }

    /** 返回距离最近的城市，超过 120 公里视为没有可靠的候选城市。 */
    static CityInfo nearest(double lat, double lon) {
        CityInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CityInfo city : all()) {
            if (!TransitRules.validCoordinate(city.lat, city.lon)) continue;
            double distance = GeoUtils.distanceKm(lat, lon, city.lat, city.lon);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = city;
            }
        }
        return bestDistance <= 120.0 ? best : null;
    }
}
