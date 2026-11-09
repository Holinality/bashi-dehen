package com.holi.prdbus;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class RegionalBusData {
    private static final String KMB = "https://data.etabus.gov.hk/v1/transport/kmb/";
    private static final String CTB = "https://rt.data.gov.hk/v2/transport/citybus/";
    private static final Map<String, Catalog> CATALOGS = new LinkedHashMap<>();
    private static final Map<String, JSONObject> STATIC_API = new LinkedHashMap<String, JSONObject>(80, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> entry) { return size() > 80; }
    };
    private static JSONObject kmbStops;
    private static JSONArray kmbRoutes;

    static final class Catalog {
        final JSONObject stops;
        final JSONArray routes;
        Catalog(JSONObject data) throws Exception {
            stops = data.getJSONObject("stops");
            routes = data.getJSONArray("routes");
        }
        Stop stop(String id) {
            JSONArray row = stops.optJSONArray(id);
            return row == null ? new Stop(id, "", "", 0, 0, "")
                    : new Stop(id, row.optString(0), row.optString(1), row.optDouble(2), row.optDouble(3), row.optString(4));
        }
    }

    static final class Stop {
        final String id, name, alias, code;
        final double lat, lon;
        Stop(String id, String name, String alias, double lat, double lon, String code) {
            this.id = id;
            this.name = name;
            this.alias = alias;
            this.lat = lat;
            this.lon = lon;
            this.code = code;
        }
        boolean matches(String value) {
            return key(name).contains(value) || key(alias).contains(value) || key(code).contains(value);
        }
    }

    static final class Match {
        final JSONArray route;
        final int stopIndex;
        Match(JSONArray route, int stopIndex) { this.route = route; this.stopIndex = stopIndex; }
    }

    static synchronized Catalog catalog(Context context, boolean hk) throws Exception {
        String name = hk ? "hk-bus-catalog.json" : "mo-bus-catalog.json";
        Catalog existing = CATALOGS.get(name);
        if (existing != null) return existing;
        Catalog loaded = new Catalog(asset(context, name));
        CATALOGS.put(name, loaded);
        return loaded;
    }

    static List<Match> search(Catalog catalog, String input) {
        String keyword = key(input).replaceFirst("路$", "");
        List<Match> exact = new ArrayList<>(), prefix = new ArrayList<>(), stations = new ArrayList<>();
        for (int i = 0; i < catalog.routes.length(); i++) {
            JSONArray route = catalog.routes.optJSONArray(i);
            String number = key(route.optString(0));
            JSONArray stops = route.optJSONArray(3);
            if (keyword.isEmpty() || number.equals(keyword)) exact.add(new Match(route, -1));
            else if (number.startsWith(keyword)) prefix.add(new Match(route, -1));
            else {
                for (int j = 0; j < stops.length(); j++) {
                    if (catalog.stop(stops.optString(j)).matches(keyword)) {
                        stations.add(new Match(route, j));
                        break;
                    }
                }
            }
        }
        exact.addAll(prefix);
        exact.addAll(stations);
        return new ArrayList<>(exact.subList(0, Math.min(60, exact.size())));
    }

    static String operator(String code) {
        String value = code.replace("KMB", "九巴").replace("CTB", "城巴")
                .replace("LWB", "龙运").replace("NLB", "屿巴")
                .replace("TCM", "澳巴").replace("Transmac", "新福利")
                .replace("LRTFeeder", "港铁巴士");
        return value.replace("+", " / ");
    }

    static boolean liveSupported(JSONArray route) {
        String code = route.optString(1);
        return code.contains("KMB") || code.contains("LWB") || code.contains("CTB");
    }

    static final class EtaSource {
        final String url, direction, operator;
        final int sequence;
        EtaSource(String url, String direction, String operator, int sequence) {
            this.url = url;
            this.direction = direction;
            this.operator = operator;
            this.sequence = sequence;
        }
        String arrivals() throws Exception {
            JSONArray rows = PublicTransportHttp.json(url, null).optJSONArray("data");
            if (rows == null) throw new java.io.IOException("No bus ETA data");
            List<Long> times = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !direction.equals(row.optString("dir"))
                        || row.optInt("seq", sequence) != sequence) continue;
                long eta = parseEta(row.optString("eta"));
                if (eta >= now - 20_000 && eta <= now + 4 * 60 * 60_000L && !times.contains(eta)) times.add(eta);
            }
            Collections.sort(times);
            List<String> values = new ArrayList<>();
            for (long eta : times.subList(0, Math.min(3, times.size()))) {
                long minutes = Math.max(0, (eta - now + 59_999) / 60_000);
                values.add(minutes == 0 ? "即将到站" : minutes + " 分钟");
            }
            return values.isEmpty() ? "" : operator + "  " + android.text.TextUtils.join("  ·  ", values);
        }
    }

    static List<EtaSource> resolve(Context context, Catalog catalog, JSONArray route, int index) throws Exception {
        String number = route.optString(0), operator = route.optString(1);
        if (!number.matches("[A-Z0-9]{1,8}")) return Collections.emptyList();
        JSONArray sequence = route.getJSONArray(3);
        Stop target = catalog.stop(sequence.getString(index));
        Stop origin = catalog.stop(sequence.getString(0));
        Stop terminal = catalog.stop(sequence.getString(sequence.length() - 1));
        List<EtaSource> sources = new ArrayList<>();
        if (operator.contains("KMB") || operator.contains("LWB")) {
            try {
                loadKmb(context);
                for (int i = 0; i < kmbRoutes.length(); i++) {
                    JSONArray line = kmbRoutes.optJSONArray(i);
                    if (!number.equals(line.optString(0))) continue;
                    String bound = line.optString(1), service = line.optString(2);
                    if (!bound.matches("[OI]") || !service.matches("[0-9]{1,2}")) continue;
                    JSONArray stops = api(KMB + "route-stop/" + number + "/" + direction(bound) + "/" + service)
                            .optJSONArray("data");
                    if (stops == null || stops.length() < 2) continue;
                    if (!matchesKmb(stops.getJSONObject(0).optString("stop"), origin)
                            || !matchesKmb(stops.getJSONObject(stops.length() - 1).optString("stop"), terminal)) continue;
                    JSONObject hit = matchStop(stops, index, sequence.length(), id -> matchesKmb(id, target));
                    if (hit != null) {
                        sources.add(new EtaSource(KMB + "eta/" + hit.getString("stop") + "/" + number + "/" + service,
                                bound, operator.contains("LWB") ? "龙运" : "九巴", hit.getInt("seq")));
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        if (operator.contains("CTB")) {
            try {
                for (String bound : new String[]{"O", "I"}) {
                    JSONArray stops = api(CTB + "route-stop/ctb/" + number + "/" + direction(bound))
                            .optJSONArray("data");
                    if (stops == null || stops.length() < 2) continue;
                    if (!matchesCtb(stops.getJSONObject(0).optString("stop"), origin)
                            || !matchesCtb(stops.getJSONObject(stops.length() - 1).optString("stop"), terminal)) continue;
                    JSONObject hit = matchStop(stops, index, sequence.length(), id -> matchesCtb(id, target));
                    if (hit != null) {
                        sources.add(new EtaSource(CTB + "eta/ctb/" + hit.getString("stop") + "/" + number,
                                bound, "城巴", hit.getInt("seq")));
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        return sources;
    }

    private interface StopCheck { boolean matches(String id) throws Exception; }

    private static JSONObject matchStop(JSONArray stops, int index, int offlineCount, StopCheck check) throws Exception {
        int guess = (int) Math.round(index * (stops.length() - 1.0) / (offlineCount - 1.0));
        // Diversions can insert a few stops. Require a verified location and direction even then.
        for (int shift : new int[]{0, -1, 1, -2, 2, -3, 3}) {
            JSONObject stop = stops.optJSONObject(guess + shift);
            if (stop == null || stop.optInt("seq", -1) < 0) continue;
            String id = stop.optString("stop");
            if (id.matches("[A-Za-z0-9]{4,24}") && check.matches(id)) return stop;
        }
        return null;
    }

    private static synchronized void loadKmb(Context context) throws Exception {
        if (kmbStops != null && kmbRoutes != null) return;
        JSONObject directory = asset(context, "hk-kmb-index.json");
        kmbStops = directory.getJSONObject("stops");
        kmbRoutes = directory.getJSONArray("routes");
    }

    private static boolean matchesKmb(String id, Stop expected) {
        JSONArray row = kmbStops.optJSONArray(id);
        return row != null && sameStop(expected, row.optString(0), row.optString(1), row.optDouble(2), row.optDouble(3));
    }

    private static boolean matchesCtb(String id, Stop expected) throws Exception {
        if (!id.matches("[0-9]{4,12}")) return false;
        JSONObject row = api(CTB + "stop/" + id).optJSONObject("data");
        return row != null && sameStop(expected, row.optString("name_sc"), row.optString("name_tc"),
                row.optDouble("lat"), row.optDouble("long"));
    }

    private static boolean sameStop(Stop expected, String sc, String tc, double lat, double lon) {
        if (!TransitRules.validCoordinate(lat, lon) || !TransitRules.validCoordinate(expected.lat, expected.lon)) return false;
        double km = GeoUtils.distanceKm(expected.lat, expected.lon, lat, lon);
        return km < .025 || (km < .12 && (key(expected.name).equals(key(sc)) || key(expected.alias).equals(key(tc))));
    }

    private static String direction(String bound) { return "O".equals(bound) ? "outbound" : "inbound"; }

    private static synchronized JSONObject api(String url) throws Exception {
        JSONObject cached = STATIC_API.get(url);
        if (cached != null) return cached;
        JSONObject data = PublicTransportHttp.json(url, null);
        STATIC_API.put(url, data);
        return data;
    }

    static long parseEta(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")) return -1;
        String clock = raw.substring(0, 19);
        String zone = raw.endsWith("Z") ? "+0000" : raw.substring(raw.length() - 6).replace(":", "");
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        parser.setTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong"));
        parser.setLenient(false);
        try { return parser.parse(clock + zone).getTime(); }
        catch (Exception invalid) { return -1; }
    }

    private static String key(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s·,，;；（）()/]", "").toUpperCase(Locale.ROOT);
    }

    private static JSONObject asset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return new JSONObject(output.toString("UTF-8"));
        }
    }

    private RegionalBusData() { }
}
