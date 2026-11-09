package com.holi.prdbus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** 掌上公交网页接口。请求字段和站序规则参考 Trackr MygolbsApiClient（MIT）。 */
final class MygolbsDataSource {
    private static final String HOST = "https://h5.mygolbs.com";
    private static final RequestReuse<JSONObject> LIVE = new RequestReuse<>(6_000, 48);
    private static final RequestReuse<JSONObject> STATIC = new RequestReuse<>(12 * 60 * 60_000L, 40);
    private static final RequestReuse<JSONObject> SEARCH = new RequestReuse<>(60_000, 32);
    private static final java.util.regex.Pattern FARE = java.util.regex.Pattern.compile(
            "票价\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?(?:\\s*元)?"
                    + "(?:\\s*[-–—~～至]\\s*[0-9]+(?:\\.[0-9]+)?(?:\\s*元)?)?)");
    private static volatile Map<String, String> apiCityNames = Collections.emptyMap();
    private static volatile Map<String, String> apiCityCodes = Collections.emptyMap();

    static String cityName(String id) {
        if (id.startsWith("mg:")) return id.substring(3);
        for (CityInfo city : CityInfo.all()) if (city.chelaileId.equals(id)) return city.name;
        return "";
    }

    static List<ChelaileDataSource.City> cities() throws Exception {
        JSONArray rows = array(request(STATIC, "101"), "data", "list");
        Map<String, ChelaileDataSource.City> cities = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String name = first(row, "cityname", "cityName", "CITYNAME");
            String normalized = Regions.normalizeCity(name);
            if (normalized.isEmpty()) continue;
            String key = providerCityKey(name);
            String code = first(row, "cityCode", "CITYCODE", "code");
            names.put(key, name);
            codes.put(key, code);
            String previous = names.get(normalized);
            if (previous == null || key.equals(normalized)
                    || (!providerCityKey(previous).equals(normalized) && name.compareTo(previous) < 0)) {
                names.put(normalized, name);
                codes.put(normalized, code);
            }
            if (!cities.containsKey(normalized)) {
                cities.put(normalized, new ChelaileDataSource.City("mg:" + normalized, normalized));
                CityInfo.register(normalized, "mg:" + normalized, 0, 0);
            }
        }
        if (!names.isEmpty()) { apiCityNames = names; apiCityCodes = codes; CityInfo.persist(); }
        return new ArrayList<>(cities.values());
    }

    private static String providerCityKey(String name) {
        // Keep identifiers already used by saved stations and lines.
        return name.trim().replaceFirst("市$", "");
    }

    private static String providerCity(String city) {
        if (apiCityNames.isEmpty()) {
            try { cities(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { }
        }
        String matched = apiCityNames.get(city);
        if (matched != null) return matched;
        return city.equals(Regions.normalizeCity(city)) ? city + "市" : city;
    }

    private static String providerCode(String city) {
        String code = apiCityCodes.get(city);
        return code == null || code.isEmpty() ? "0" : code;
    }

    static boolean supports(String city) throws Exception {
        for (ChelaileDataSource.City known : cities())
            if (Regions.normalizeCity(known.name).equals(Regions.normalizeCity(city))) return true;
        return false;
    }

    static String cityAt(double lat, double lon) throws Exception {
        String name = request(LIVE, "108", "LAT", "" + lat, "LNG", "" + lon,
                "MYLAT", "" + lat, "MYLNG", "" + lon).optString("msg").trim();
        return name.matches("[\\p{IsHan}]{2,20}") ? Regions.normalizeCity(name) : "";
    }

    static List<ChelaileDataSource.NearbyStop> nearby(String cityId, double lat, double lon) throws Exception {
        String city = cityName(cityId);
        if (city.isEmpty()) return Collections.emptyList();
        JSONArray rows = array(request(LIVE, "106", "CITYNAME", providerCity(city), "LAT", "" + lat, "LNG", "" + lon,
                "MYLAT", "" + lat, "MYLNG", "" + lon), "data");
        List<ChelaileDataSource.NearbyStop> stops = new ArrayList<>();
        for (int i = 0; i < rows.length() && stops.size() < 12; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String name = row.optString("name").trim();
            if (name.isEmpty()) continue;
            String id = "mg:" + city + ":" + name;
            // NearbyStop stores WGS-84 directly. SearchPoi uses GCJ-02 for all providers.
            stops.add(new ChelaileDataSource.NearbyStop(id, name, row.optInt("dis", -1), false,
                    id, id, cityId, Collections.emptyList(), Collections.emptyList(),
                    row.optDouble("lat", 0), row.optDouble("lon", 0)));
        }
        return stops;
    }

    static ChelaileDataSource.SearchResult search(String cityId, String keyword) throws Exception {
        String city = cityName(cityId);
        List<ChelaileDataSource.SearchLine> lines = new ArrayList<>();
        List<ChelaileDataSource.SearchPoi> stops = new ArrayList<>();
        if (city.isEmpty()) return new ChelaileDataSource.SearchResult(lines, stops);
        JSONObject response = request(SEARCH, "102", "CITYNAME", providerCity(city), "KEYWORD", keyword, "CITYKEY", "");
        JSONArray routes = array(response, "buslines", "list");
        java.util.Set<String> seenLines = new java.util.HashSet<>();
        for (int i = 0; i < routes.length(); i++) {
            JSONObject row = routes.optJSONObject(i);
            if (row == null) continue;
            String name = first(row, "lineName", "routeName");
            if (name.isEmpty() || name.contains("地铁")) continue;
            String direction = row.optString("upperOrDown", "1");
            if (!"2".equals(direction)) direction = "1";
            String id = lineId(city, name, direction);
            if (!seenLines.add(id)) continue;
            lines.add(new ChelaileDataSource.SearchLine(id, name, name,
                    row.optString("from"), row.optString("to"), "2".equals(direction) ? 1 : 0));
        }
        Collections.sort(lines, (a, b) -> Integer.compare(
                BusRouteMatch.lineScore(keyword, b.name, b.lineNo),
                BusRouteMatch.lineScore(keyword, a.name, a.lineNo)));
        if (lines.size() > 24) lines.subList(24, lines.size()).clear();
        JSONArray rows = array(response, "busstations");
        for (int i = 0; i < rows.length() && stops.size() < 24; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String name = row.optString("stationName");
            double lat = row.optDouble("station_lat", 0), lon = row.optDouble("station_lon", 0);
            if (name.isEmpty()) continue;
            double[] point = Coord.wgsToGcj(lat, lon);
            stops.add(new ChelaileDataSource.SearchPoi(name, "掌上公交", point[0], point[1], 1));
        }
        return new ChelaileDataSource.SearchResult(lines, stops);
    }

    static void fillMissingArrivals(String cityId, ChelaileDataSource.LineDetail primary,
                                    String station, double lat, double lon,
                                    Map<String, List<ChelaileDataSource.Bus>> arrivals) {
        if (primary == null || primary.stations.isEmpty() || primary.endSn.isEmpty() || primary.startSn.isEmpty()) return;
        List<ChelaileDataSource.Bus> existing = arrivals.get(primary.lineId);
        if (existing != null) for (ChelaileDataSource.Bus bus : existing) if (bus.travelTime >= 0) return;
        int primaryTarget = BusRouteMatch.station(primary.stations, station, lat, lon);
        if (primaryTarget < 0) return;
        try {
            for (ChelaileDataSource.SearchLine candidate : search(cityId, primary.name).lines) {
                if (BusRouteMatch.lineScore(primary.name, candidate.name, candidate.lineNo) != 120
                        || !BusRouteMatch.sameTerminal(primary.startSn, candidate.startSn)
                        || !BusRouteMatch.sameTerminal(primary.endSn, candidate.endSn)) continue;
                ChelaileDataSource.LineDetail fallback = line(candidate.lineId, lat, lon, station);
                if (!BusRouteMatch.sameTerminal(primary.startSn, fallback.startSn)
                        || !BusRouteMatch.sameTerminal(primary.endSn, fallback.endSn)) continue;
                List<ChelaileDataSource.Bus> confirmed = new ArrayList<>();
                int order = primary.stations.get(primaryTarget).order;
                for (ChelaileDataSource.Bus bus : fallback.buses) {
                    if (bus.travelTime < 0 || bus.stopsAway < 0) continue;
                    ChelaileDataSource.Bus arrival = new ChelaileDataSource.Bus(bus.busId, bus.travelTime,
                            bus.distanceToDest, bus.recommTip, bus.licence, Math.max(0, order - bus.stopsAway), bus.lat, bus.lon);
                    arrival.stopsAway = bus.stopsAway;
                    confirmed.add(arrival);
                }
                if (!confirmed.isEmpty()) arrivals.put(primary.lineId, confirmed);
                return;
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { }
    }

    static void fillMissingStopStatus(String cityId, String station, List<ChelaileDataSource.LineArrival> lines) {
        String city = cityName(cityId);
        if (city.isEmpty() || lines.isEmpty()) return;
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ChelaileDataSource.LineArrival line = lines.get(i);
            if (line.buses.isEmpty() && (line.status == null || line.status.trim().isEmpty())) missing.add(i);
        }
        if (missing.isEmpty()) return;
        try {
            List<ChelaileDataSource.StopDetail> rows = stop("mg:" + city + ":" + station);
            if (rows.isEmpty()) return;
            for (int index : missing) {
                ChelaileDataSource.LineArrival line = lines.get(index);
                for (ChelaileDataSource.LineArrival fallback : rows.get(0).lines) {
                    if (fallback.status.isEmpty()
                            || BusRouteMatch.lineScore(line.name, fallback.name, fallback.name) != 120
                            || !BusRouteMatch.sameTerminal(line.endSn, fallback.endSn)) continue;
                    lines.set(index, new ChelaileDataSource.LineArrival(line.lineId, line.name, line.direction,
                            line.endSn, line.startSn, fallback.status, line.targetOrder, line.stationsNum,
                            line.firstTime, line.lastTime, line.buses));
                    break;
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { }
    }

    static List<ChelaileDataSource.StopDetail> stop(String id) throws Exception {
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return Collections.emptyList();
        String city = parts[1], name = parts[2];
        JSONArray rows = array(request(LIVE, "105", "CITYNAME", providerCity(city), "STATIONNAME", name,
                "MYLAT", "", "MYLNG", "", "CITYKEY", ""), "data");
        List<ChelaileDataSource.LineArrival> lines = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String line = first(row, "lineName", "routeName");
            String dir = row.optString("upperOrDown");
            String terminal = row.optString("to");
            if (line.isEmpty() || terminal.isEmpty() || !("1".equals(dir) || "2".equals(dir))) continue;
            JSONObject route = row.optJSONObject("routeBean");
            if (route != null && "3".equals(route.optString("routeType"))) continue;
            int order = row.optInt("stationOrder", 0);
            if (order <= 0) order = row.optInt("stationID", -1) + 1;
            String near = row.optString("neartext").split("[\\[【]", 2)[0].trim();
            String time = row.optString("neartime").trim();
            if (route != null && route.optInt("hasReal", 1) == 0) { near = ""; time = ""; }
            if (time.matches("[0-9]{1,2}:[0-5][0-9]")) near = time + " 到站";
            else if (!near.matches("[0-9]+站(?:到达)?|即将到站|已到站|等待发车")) near = "";
            lines.add(new ChelaileDataSource.LineArrival(lineId(city, line, dir), line,
                    "2".equals(dir) ? 1 : 0, terminal, row.optString("from"), near, order, -1,
                    route == null ? "" : route.optString("1".equals(dir) ? "suTime" : "sdTime"),
                    route == null ? "" : route.optString("1".equals(dir) ? "euTime" : "edTime"),
                    Collections.emptyList()));
        }
        return lines.isEmpty() ? Collections.emptyList() :
                Collections.singletonList(new ChelaileDataSource.StopDetail(id, name, 0, 0, lines));
    }

    static ChelaileDataSource.LineDetail line(String id, double lat, double lon, String targetName) throws Exception {
        String[] parts = id.split(":", 4);
        if (parts.length != 4 || !parts[3].matches("[12]")) throw new IOException("线路标识无效");
        String city = parts[1], name = parts[2], dir = parts[3];
        String apiCity = providerCity(city);
        String apiCode = providerCode(city);
        JSONObject route = request(STATIC, "103", "CITYNAME", apiCity, "CITYCODE", apiCode, "LINENAME", name,
                "DIRECTION", dir, "CITYKEY", "");
        JSONArray rows = array(route, "data");
        List<ChelaileDataSource.LineStation> stations = new ArrayList<>();
        int order = 0;
        double nearest = .30;
        double runnerUp = Double.POSITIVE_INFINITY;
        int namedOrder = 0, namedCount = 0;
        boolean namedTarget = targetName != null && !targetName.trim().isEmpty();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optString("stationName").isEmpty()) continue;
            String stationName = row.optString("stationName");
            ChelaileDataSource.LineStation station = new ChelaileDataSource.LineStation(
                    row.optInt("stationOrder", i + 1), stationName, "mg:" + city + ":" + stationName,
                    row.optDouble("station_lat", 0), row.optDouble("station_lon", 0));
            stations.add(station);
            if (TransitRules.sameStation(targetName, stationName)) {
                namedOrder = station.order;
                namedCount++;
            }
            if (TransitRules.validCoordinate(lat, lon) && TransitRules.validCoordinate(station.lat, station.lon)) {
                if (namedTarget && !TransitRules.sameStation(targetName, stationName)) continue;
                double distance = GeoUtils.distanceKm(lat, lon, station.lat, station.lon);
                if (distance < nearest) {
                    if (order > 0) runnerUp = nearest;
                    nearest = distance;
                    order = station.order;
                } else {
                    runnerUp = Math.min(runnerUp, distance);
                }
            }
        }
        if (stations.isEmpty()) throw new IOException("暂无线路站序");
        if (namedCount > 1 && runnerUp - nearest < .015) order = 0;
        if (order == 0 && namedCount == 1) order = namedOrder;
        List<ChelaileDataSource.Bus> buses = new ArrayList<>();
        // A terminal anchor can load the vehicle map when no station was requested.
        // An unresolved explicit station must not trigger an ETA request for the terminal.
        if (!namedTarget || order > 0) try {
            JSONObject live = request(LIVE, "104", "CITYNAME", apiCity, "CITYCODE", apiCode, "LINENAME", name,
                    "DIRECTION", dir, "STATIONORDER", "" + (order > 0 ? order : stations.get(stations.size() - 1).order), "CITYKEY", "");
            Map<String, JSONObject> times = new HashMap<>();
            JSONArray estimates = array(live, "routeOnStationRTimeInfoList");
            for (int i = 0; i < estimates.length(); i++) {
                JSONObject estimate = estimates.optJSONObject(i);
                if (estimate != null && !estimate.optString("busNumber").isEmpty())
                    times.put(estimate.optString("busNumber"), estimate);
            }
            JSONArray vehicles = array(live, "list");
            for (int i = 0; i < vehicles.length(); i++) {
                JSONObject vehicle = vehicles.optJSONObject(i);
                if (vehicle == null) continue;
                String busId = vehicle.optString("busNumber");
                int busOrder = vehicle.optInt("index", -1) + 1;
                JSONObject estimate = times.get(busId);
                double minutes = estimate == null ? -1 : estimate.optDouble("busToStationTime", -1);
                boolean atTarget = order > 0 && order == busOrder && "0".equals(vehicle.optString("statusType"));
                int seconds = arrivalSeconds(order, busOrder, minutes, atTarget);
                ChelaileDataSource.Bus bus = new ChelaileDataSource.Bus(busId, seconds,
                        estimate == null ? -1 : estimate.optInt("busToStationDistance", -1),
                        "", busId, busOrder, vehicle.optDouble("bus_lat", 0), vehicle.optDouble("bus_lng", 0));
                if (order > 0 && busOrder > 0 && busOrder <= order) bus.stopsAway = order - busOrder;
                buses.add(bus);
            }
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception unavailable) { /* Keep static stop sequence usable. */ }
        List<String> windows = serviceWindows(route);
        String[] single = windows.size() == 1 ? windows.get(0).split("–", 2) : null;
        return new ChelaileDataSource.LineDetail(id, name, "2".equals(dir) ? 1 : 0,
                stations.get(0).name, stations.get(stations.size() - 1).name,
                single == null ? route.optString("beginTime") : single[0],
                single == null ? route.optString("endTime") : single[1].replace("次日 ", ""),
                fare(route.optString("commonts")), buses, stations,
                lineId(city, name, "1".equals(dir) ? "2" : "1"), windows);
    }

    static int arrivalSeconds(int targetOrder, int busOrder, double minutes, boolean atTarget) {
        if (atTarget) return 0;
        if (targetOrder <= 0 || busOrder <= 0 || busOrder > targetOrder
                || !(minutes >= 0 && minutes < 180)) return -1;
        return (int) Math.ceil(minutes * 60);
    }

    static String fare(String comments) {
        java.util.regex.Matcher match = FARE.matcher(comments);
        return match.find() ? match.group(1).replaceAll("\\s+", "") : "";
    }

    static List<String> serviceWindows(JSONObject route) {
        JSONArray source = array(route, "firstLast");
        List<String> windows = new ArrayList<>();
        String begin = serviceTime(route.optString("beginTime"));
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            String first = serviceTime(row.optString("first"));
            String last = serviceTime(row.optString("last"));
            if (first.isEmpty() || last.isEmpty()) continue;
            String window = first + "–" + (last.compareTo(first) < 0 ? "次日 " : "") + last;
            if (windows.contains(window)) continue;
            if (first.equals(begin)) windows.add(0, window);
            else windows.add(window);
        }
        return Collections.unmodifiableList(windows);
    }

    private static String serviceTime(String value) {
        String time = value.trim();
        if (!time.matches("(?:[01]?[0-9]|2[0-3]):[0-5][0-9]|24:00")) return "";
        return time.length() == 4 ? "0" + time : time;
    }

    private static String lineId(String city, String line, String direction) {
        return "mg:" + city + ":" + line.replace(':', '：') + ":" + direction;
    }

    private static JSONArray array(JSONObject root, String... keys) {
        for (String key : keys) {
            JSONArray value = root.optJSONArray(key);
            if (value != null) return value;
        }
        return new JSONArray();
    }

    private static String first(JSONObject row, String... keys) {
        for (String key : keys) {
            String value = row.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static JSONObject request(RequestReuse<JSONObject> cache, String cmd, String... fields) throws Exception {
        if (!PrivacyNotice.locationAllowed()) throw new IllegalStateException("请先查看应用隐私说明");
        StringBuilder form = new StringBuilder("CMD=").append(cmd);
        for (int i = 0; i < fields.length; i += 2)
            form.append('&').append(fields[i]).append('=').append(URLEncoder.encode(fields[i + 1], "UTF-8"));
        String body = form.toString();
        return cache.get(body, () -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(HOST + "/ApiData.do").openConnection();
            try {
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(4500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Origin", HOST);
                connection.setRequestProperty("Referer", HOST + "/");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] data = body.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
                if (connection.getResponseCode() != 200) throw new IOException("掌上公交暂不可用");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (buffer.size() + n > 512 * 1024) throw new IOException("公交响应过大");
                        buffer.write(chunk, 0, n);
                    }
                    String payload = buffer.toString("UTF-8").trim();
                    if (!payload.isEmpty() && payload.charAt(0) == '\uFEFF') payload = payload.substring(1);
                    JSONObject value = new JSONObject(payload);
                    if (!"1".equals(value.optString("status"))) throw new IOException("掌上公交暂无数据");
                    return value;
                }
            } finally { connection.disconnect(); }
        });
    }
}
