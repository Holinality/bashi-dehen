package com.holi.prdbus;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 车来了 H5 接口数据源（免费、双向实时到站）。
 * 签名：MD5(所有参数 "k"="v" 用 & 连接 + 盐)，响应 AES-256-ECB 解密。
 */
public final class ChelaileDataSource {
    private static final String BASE = "https://web.chelaile.net.cn";
    private static final String SIGN_SALT = "qwihrnbtmj";
    private static final String AES_KEY = "FF32AE65FBFD19414EAAFF6291A54B42";

    /** 高德结果内存缓存：静态地理数据无需随着实时车辆刷新重复计费。 */
    private static final class TimedValue<T> {
        final long at;
        final T value;
        TimedValue(T value) { this.at = System.currentTimeMillis(); this.value = value; }
    }
    private static final Object GAODE_CACHE_LOCK = new Object();
    private static final Map<String, TimedValue<List<GaodeStop>>> PLACE_CACHE = new LinkedHashMap<>();
    private static final Map<String, TimedValue<List<GaodeTip>>> TIP_CACHE = new LinkedHashMap<>();
    private static final Map<String, TimedValue<Map<String, double[]>>> AROUND_CACHE = new LinkedHashMap<>();
    private static final Map<String, TimedValue<List<GaodeLine>>> LINE_CACHE = new LinkedHashMap<>();
    private static final Map<String, TimedValue<List<TransitPlan>>> TRANSIT_CACHE = new LinkedHashMap<>();
    private static volatile List<City> CITY_CACHE;
    private static volatile long CITY_CACHE_AT;
    private static final RequestReuse<JSONObject> LINE_RESPONSES = new RequestReuse<>(6_000, 64);
    private static final RequestReuse<JSONObject> STOP_RESPONSES = new RequestReuse<>(5_000, 48);
    private static final RequestReuse<JSONObject> TIP_RESPONSES = new RequestReuse<>(20_000, 48);

    private static <T> T cached(Map<String, TimedValue<T>> cache, String key, long ttlMs) {
        synchronized (GAODE_CACHE_LOCK) {
            TimedValue<T> item = cache.get(key);
            if (item == null) return null;
            if (System.currentTimeMillis() - item.at > ttlMs) {
                cache.remove(key);
                return null;
            }
            return item.value;
        }
    }

    private static <T> void cache(Map<String, TimedValue<T>> target, String key, T value) {
        synchronized (GAODE_CACHE_LOCK) {
            target.put(key, new TimedValue<>(value));
            while (target.size() > 96) {
                java.util.Iterator<String> iterator = target.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static String cacheText(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.CHINA);
    }

    private static final Map<String, String> DEFAULT_PARAMS = new LinkedHashMap<>();
    static {
        DEFAULT_PARAMS.put("s", "h5");
        DEFAULT_PARAMS.put("wxs", "wx_app");
        DEFAULT_PARAMS.put("sign", "1");
        DEFAULT_PARAMS.put("h5RealData", "1");
        DEFAULT_PARAMS.put("v", "3.11.28");
        DEFAULT_PARAMS.put("src", "weixinapp_cx");
        DEFAULT_PARAMS.put("ctm_mp", "mp_wx");
        DEFAULT_PARAMS.put("vc", "2");
        DEFAULT_PARAMS.put("favoriteGray", "1");
        DEFAULT_PARAMS.put("gpstype", "wgs");
        DEFAULT_PARAMS.put("geo_type", "wgs");
        DEFAULT_PARAMS.put("scene", "1256");
    }

    private static final Map<String, String> HEADERS = new LinkedHashMap<>();
    static {
        HEADERS.put("Host", "web.chelaile.net.cn");
        HEADERS.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) UnifiedPCWindowsWechat(0xf254160a) XWEB/18055");
        HEADERS.put("xweb_xhr", "1");
        HEADERS.put("Content-Type", "text");
        HEADERS.put("Accept", "*/*");
        HEADERS.put("Sec-Fetch-Site", "cross-site");
        HEADERS.put("Sec-Fetch-Mode", "cors");
        HEADERS.put("Sec-Fetch-Dest", "empty");
        HEADERS.put("Referer", "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html");
        HEADERS.put("Accept-Language", "zh-CN,zh;q=0.9");
        // 强制服务器返回明文（不做 gzip/br 压缩，避免 Android 端解压问题）
        HEADERS.put("Accept-Encoding", "identity");
    }

    // ── 数据模型 ──────────────────────────────────────

    /** 城市。 */
    public static final class City {
        public final String cityId;
        public final String name;

        City(String cityId, String name) {
            this.cityId = cityId;
            this.name = name;
        }
    }

    /** 一辆车。travelTime 为距到站的秒数。order 为车辆当前位置（站点序号）。 */
    public static final class Bus {
        public int stopsAway = -1;
        public final String busId;
        public final int travelTime;   // 秒，-1 未知
        public final int distanceToDest; // 距目标站米数
        public final String recommTip; // 预计到站时刻（如 14:52）
        public final String licence;   // 车牌
        public final int order;        // 车辆当前位置所在站点序号（0 未知）
        public final double lat;       // 车辆实时位置
        public final double lon;

        Bus(String busId, int travelTime, int distanceToDest, String recommTip,
            String licence, int order, double lat, double lon) {
            this.busId = busId;
            this.travelTime = travelTime;
            this.distanceToDest = distanceToDest;
            this.recommTip = recommTip;
            this.licence = licence;
            this.order = order;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** 线路上的一个站点。 */
    public static final class LineStation {
        public String physicalStId = "";
        public String namesakeStId = "";
        public final int order;
        public final String name;
        public final String sId;
        public final double lat;
        public final double lon;

        LineStation(int order, String name, String sId, double lat, double lon) {
            this.order = order;
            this.name = name;
            this.sId = sId;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** 线路详情（一个方向）：站点 + 实时车辆。 */
    public static final class LineDetail {
        public final String lineId;
        public final String name;
        public final int direction;    // 0/1
        public final String startSn;
        public final String endSn;
        public final String firstTime;
        public final String lastTime;
        public final String price;
        public final List<String> serviceWindows;
        public final List<Bus> buses;
        public final List<LineStation> stations; // 该方向全部站点（时间轴）
        public final String reverseLineId; // 反向线路 lineId

        LineDetail(String lineId, String name, int direction, String startSn, String endSn,
                   String firstTime, String lastTime, String price,
                   List<Bus> buses, List<LineStation> stations, String reverseLineId) {
            this(lineId, name, direction, startSn, endSn, firstTime, lastTime, price,
                    buses, stations, reverseLineId, java.util.Collections.emptyList());
        }

        LineDetail(String lineId, String name, int direction, String startSn, String endSn,
                   String firstTime, String lastTime, String price, List<Bus> buses,
                   List<LineStation> stations, String reverseLineId, List<String> serviceWindows) {
            this.lineId = lineId;
            this.name = name;
            this.direction = direction;
            this.startSn = startSn;
            this.endSn = endSn;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
            this.price = price;
            this.serviceWindows = serviceWindows;
            this.buses = buses;
            this.stations = stations;
            this.reverseLineId = reverseLineId;
        }
    }

    /** 线路搜索结果（车来了 query!encryptedNSearch）。 */
    public static final class SearchLine {
        public final String lineId;
        public final String name;
        public final String lineNo;
        public final String startSn;
        public final String endSn;
        public final int direction; // 0/1

        SearchLine(String lineId, String name, String lineNo,
                   String startSn, String endSn, int direction) {
            this.lineId = lineId;
            this.name = name;
            this.lineNo = lineNo;
            this.startSn = startSn;
            this.endSn = endSn;
            this.direction = direction;
        }
    }

    /** 搜索返回的模糊 POI（线路名/站名联想）。 */
    public static final class SearchPoi {
        public final String name;
        public final String route;
        public final double lat; // GCJ-02
        public final double lon;
        public final int type;   // 0=线路 1=站点

        SearchPoi(String name, String route, double lat, double lon, int type) {
            this.name = name;
            this.route = route;
            this.lat = lat;
            this.lon = lon;
            this.type = type;
        }
    }

    /** 关键字搜索汇总。 */
    public static final class SearchResult {
        public final List<SearchLine> lines;
        public final List<SearchPoi> pois;

        SearchResult(List<SearchLine> lines, List<SearchPoi> pois) {
            this.lines = lines;
            this.pois = pois;
        }
    }

    /** 经过某站的一条线路（含该站方向的实时车辆）。direction 0/1 两个方向。 */
    public static final class LineArrival {
        public final String lineId;
        public final String name;      // 线路名（如 沥11）
        public final int direction;    // 0=上行 1=下行
        public final String endSn;     // 终点站名
        public final String startSn;   // 起点站名
        public final String status;    // 运营状态描述（等待发车等）
        public final int targetOrder;  // 目标站在线路中的序号
        public final int stationsNum;  // 线路总站数，-1 未知
        public final String firstTime; // 首班
        public final String lastTime;  // 末班
        public final List<Bus> buses;  // 实时车辆

        LineArrival(String lineId, String name, int direction, String endSn, String startSn,
                   String status, int targetOrder, int stationsNum,
                   String firstTime, String lastTime, List<Bus> buses) {
            this.lineId = lineId;
            this.name = name;
            this.direction = direction;
            this.endSn = endSn;
            this.startSn = startSn;
            this.status = status;
            this.targetOrder = targetOrder;
            this.stationsNum = stationsNum;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
            this.buses = buses;
        }
    }

    /** 地铁线路信息（车来了 subwayV2Lines）。 */
    public static final class MetroLineInfo {
        public final String lineName;   // 线路名（如 APM线）
        public final String bgColor;    // 线路颜色 "47,210,255"
        public final String destName;   // 方向终点
        public final String firstTime;  // 首班
        public final String lastTime;   // 末班
        public final int orderNum;      // 当前站在该方向的序号
        public final int maxOrder;      // 该方向总站数

        MetroLineInfo(String lineName, String bgColor, String destName,
                      String firstTime, String lastTime, int orderNum, int maxOrder) {
            this.lineName = lineName;
            this.bgColor = bgColor;
            this.destName = destName;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
            this.orderNum = orderNum;
            this.maxOrder = maxOrder;
        }
    }

    /** 附近站点。cityId 记录所属城市（跨城市收藏用）。 */
    public static final class NearbyStop {
        public final String sId;
        public final String name;
        public final int distanceM;
        public final boolean subway;
        public final String physicalStId;
        public final String namesakeStId;
        public final String cityId;
        public final List<LineArrival> lines;
        public final List<MetroLineInfo> metroLines; // 地铁线路（含方向/首末班）
        public final double lat, lon;

        NearbyStop(String sId, String name, int distanceM, boolean subway,
                   String physicalStId, String namesakeStId, String cityId,
                   List<LineArrival> lines, List<MetroLineInfo> metroLines) {
            this(sId, name, distanceM, subway, physicalStId, namesakeStId, cityId, lines, metroLines, 0, 0);
        }

        NearbyStop(String sId, String name, int distanceM, boolean subway,
                   String physicalStId, String namesakeStId, String cityId,
                   List<LineArrival> lines, List<MetroLineInfo> metroLines, double lat, double lon) {
            this.sId = sId;
            this.name = name;
            this.distanceM = distanceM;
            this.subway = subway;
            this.physicalStId = physicalStId;
            this.namesakeStId = namesakeStId;
            this.cityId = cityId;
            this.lines = lines;
            this.metroLines = metroLines;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** 站台详情（物理站所有线路，双向）。 */
    public static final class StopDetail {
        public final String sId;
        public final String name;
        public final double lat;
        public final double lon;
        public final List<LineArrival> lines;

        StopDetail(String sId, String name, double lat, double lon, List<LineArrival> lines) {
            this.sId = sId;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.lines = lines;
        }
    }

    // ── 城市列表（含缓存）──────────────────────────────
    public static synchronized List<City> queryCities() throws Exception {
        if (CITY_CACHE == null) {
            List<City> stored = CityInfo.recentPrimaryCatalog();
            if (!stored.isEmpty()) { CITY_CACHE = stored; CITY_CACHE_AT = System.currentTimeMillis(); }
        }
        List<City> cachedCities = CITY_CACHE;
        long cityCacheTtl = cachedCities == null || cachedCities.isEmpty()
                ? 30 * 60_000L : 24 * 60 * 60_000L;
        if (cachedCities != null && System.currentTimeMillis() - CITY_CACHE_AT < cityCacheTtl) {
            return new ArrayList<>(cachedCities);
        }
        JSONObject data = requestPlain(BASE + "/wwd/ncitylist", DEFAULT_PARAMS);
        JSONArray list = data.optJSONArray("cityList");
        List<City> cities = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("cityId", ""), name = c.optString("cityName", "");
                if (!id.isEmpty() && !name.isEmpty()) {
                    name = Regions.normalizeCity(name);
                    cities.add(new City(id, name));
                    CityInfo.register(name, id, 0, 0);
                }
            }
        }
        if (!cities.isEmpty()) {
            CITY_CACHE = new ArrayList<>(cities);
            CITY_CACHE_AT = System.currentTimeMillis();
            CityInfo.persist();
            CityInfo.primaryRefreshed();
        }
        return cities;
    }

    /** 按服务端最新城市表校正硬编码 cityId；接口失败时安全保留原值。 */
    public static String resolveCityId(String cityName, String fallback) {
        if (cityName == null || cityName.trim().isEmpty()) return fallback;
        try {
            String wanted = Regions.normalizeCity(cityName);
            for (City city : queryCities()) {
                String actual = Regions.normalizeCity(city.name);
                if (wanted.equals(actual) && city.cityId != null && !city.cityId.isEmpty()) {
                    return city.cityId;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 去掉站名尾部的方向字（东/南/西/北）。「九龙公园南」→「九龙公园」。 */
    static String stripDirectionSuffix(String name) {
        if (name == null || name.length() <= 2) return name;
        char last = name.charAt(name.length() - 1);
        if (last == '东' || last == '南' || last == '西' || last == '北') {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    // ── 关键字搜索（线路 + 站点联想，真实 lineId）────────
    private static final RequestReuse<JSONObject> SEARCH_RESPONSES = new RequestReuse<>(60_000L, 64);

    public static SearchResult searchKeywords(String cityId, String keyword, double lat, double lon) throws Exception {
        keyword = BusSearch.normalize(keyword);
        if (keyword.isEmpty() || cityId == null || cityId.isEmpty())
            return new SearchResult(new ArrayList<>(), new ArrayList<>());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (cityId.startsWith("mg:"))
            return BusSearch.merge(keyword, lat, lon, MygolbsDataSource.search(cityId, keyword), null);
        SearchResult primary = null;
        try {
            primary = searchPrimary(cityId, keyword, lat, lon);
            if (BusSearch.exact(keyword, primary)) return BusSearch.merge(keyword, lat, lon, primary, null);
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failed) { if (Thread.currentThread().isInterrupted()) throw failed; }
        try {
            return BusSearch.merge(keyword, lat, lon, primary, MygolbsDataSource.search(cityId, keyword));
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failed) {
            if (Thread.currentThread().isInterrupted() || primary == null) throw failed;
            return BusSearch.merge(keyword, lat, lon, primary, null);
        }
    }

    static SearchResult searchPrimary(String cityId, String keyword,
                                              double lat, double lon) throws Exception {
        Map<String, String> params = new LinkedHashMap<>(DEFAULT_PARAMS);
        params.put("cityId", cityId);
        params.put("lat", String.valueOf(lat));
        params.put("lng", String.valueOf(lon));
        params.put("geo_lat", String.valueOf(lat));
        params.put("geo_lng", String.valueOf(lon));
        params.put("key", keyword);
        String cacheKey = cityId + "|" + keyword + "|" + Math.round(lat * 1000) + "|" + Math.round(lon * 1000);
        JSONObject data = SEARCH_RESPONSES.get(cacheKey,
                () -> request(BASE + "/api/bus/query!encryptedNSearch.action", params));
        JSONObject result = data.optJSONObject("result");
        if (result == null) {
            return new SearchResult(new ArrayList<SearchLine>(), new ArrayList<SearchPoi>());
        }
        List<SearchLine> lines = new ArrayList<>();
        JSONArray linesArr = result.optJSONArray("lines");
        if (linesArr != null) {
            for (int i = 0; i < linesArr.length(); i++) {
                JSONObject li = linesArr.optJSONObject(i);
                if (li == null) continue;
                String lineId = li.optString("lineId", "");
                String name = li.optString("name", "");
                if (lineId.isEmpty() || name.isEmpty()) continue;
                lines.add(new SearchLine(
                        lineId,
                        name,
                        li.optString("lineNo", name),
                        li.optString("startSn", ""),
                        li.optString("endSn", ""),
                        li.optInt("direction", 0)
                ));
            }
        }
        List<SearchPoi> pois = new ArrayList<>();
        JSONArray poisArr = result.optJSONArray("pois");
        if (poisArr != null) {
            for (int i = 0; i < poisArr.length(); i++) {
                JSONObject p = poisArr.optJSONObject(i);
                if (p == null) continue;
                String name = p.optString("sn1", "");
                if (name.isEmpty()) continue;
                String route = p.optString("sn1Address", "");
                double lat2 = p.optDouble("lat", 0);
                double lon2 = p.optDouble("lng", 0);
                pois.add(new SearchPoi(name, route, lat2, lon2, p.optInt("sn1Type", 0)));
            }
        }
        return new SearchResult(lines, pois);
    }

    // ── Code410 站名搜索（佛山站名实时数据兜底）────────
    private static final String CODE410_ENDPOINT = "https://api.code410.com/api/traffic/bus";

    /** Code410 查询结果中的一辆车。 */
    public static final class Code410Arrival {
        public final String line;      // 线路名（如 101路🚌）
        public final String destination; // 开往
        public final String price;     // 票价
        public final String licence;   // 车牌
        public final String expectedArrival; // 预计到站时间
        public final String estimatedTime;   // "9分⏱"
        public final String remainingStations; // "3站"

        Code410Arrival(String line, String destination, String price, String licence,
                       String expectedArrival, String estimatedTime, String remainingStations) {
            this.line = line;
            this.destination = destination;
            this.price = price;
            this.licence = licence;
            this.expectedArrival = expectedArrival;
            this.estimatedTime = estimatedTime;
            this.remainingStations = remainingStations;
        }
    }

    /** 按站名查 Code410（精确匹配）。返回空 = 该站无数据。失败自动重试一次。 */
    public static List<Code410Arrival> code410Query(String cityName, String station) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String url = CODE410_ENDPOINT
                        + "?city=" + java.net.URLEncoder.encode(cityName, "UTF-8")
                        + "&site=" + java.net.URLEncoder.encode(station, "UTF-8");
                JSONObject root = requestPlainNoWx(url);
                JSONObject data = root.optJSONObject("data");
                List<Code410Arrival> result = new ArrayList<>();
                if (data == null) return result;
                java.util.Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray list = data.optJSONArray(key);
                    if (list == null) continue;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject v = list.optJSONObject(i);
                        if (v == null) continue;
                        result.add(new Code410Arrival(
                                v.optString("line", ""),
                                v.optString("destination", ""),
                                v.optString("price", ""),
                                v.optString("licence_plate", ""),
                                v.optString("expected_arrival", ""),
                                v.optString("estimated_time", ""),
                                v.optString("remaining_stations", "")
                        ));
                    }
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw lastError;
    }

    // ── 换乘规划（高德 transit，车来了式）──────────────
    public static final class TransitSegment implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String type;   // walk / bus / metro / railway
        public final String name;   // 线路名（公交/地铁）
        public final String from;   // 上车站
        public final String to;     // 下车站
        public final int distance;  // 步行米数
        public final int stops;     // 乘坐站数（-1 未知）
        public final int durationMin; // 乘坐分钟（-1 未知）
        public double boardLat, boardLon, alightLat, alightLon;
        public String cityHint = "", terminal = "";

        TransitSegment(String type, String name, String from, String to, int distance,
                       int stops, int durationMin) {
            this.type = type;
            this.name = name;
            this.from = from;
            this.to = to;
            this.distance = distance;
            this.stops = stops;
            this.durationMin = durationMin;
        }
    }

    public static final class TransitPlan implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final int durationMin;
        public final String cost;
        public final int walkingDistance;
        public final List<TransitSegment> segments;

        TransitPlan(int durationMin, String cost, int walkingDistance, List<TransitSegment> segments) {
            this.durationMin = durationMin;
            this.cost = cost;
            this.walkingDistance = walkingDistance;
            this.segments = segments;
        }
    }

    /** 换乘规划：起点站名 → 终点站名（广东省内跨市）。返回方案列表。 */
    public static List<TransitPlan> transitQuery(String cityName, String originName,
                                                 String destName) throws Exception {
        return transitQuery(cityName, originName, 0, 0, destName, 0, 0);
    }

    /** 换乘规划：支持联想选中后的精确坐标（GCJ-02），坐标缺失时退回省内 POI 搜索。 */
    public static List<TransitPlan> transitQuery(String cityName, String originName,
                                                 double originLat, double originLon,
                                                 String destName,
                                                 double destLat, double destLon) throws Exception {
        return transitQuery(cityName, originName, originLat, originLon, destName,
                destLat, destLon, "", "");
    }

    public static List<TransitPlan> transitQuery(String cityName, String originName,
                                                 double originLat, double originLon,
                                                 String destName, double destLat, double destLon,
                                                 String selectedOriginCity, String selectedDestCity) throws Exception {
        return transitQuery(cityName, originName, originLat, originLon, destName,
                destLat, destLon, selectedOriginCity, selectedDestCity, 0);
    }

    public static List<TransitPlan> transitQuery(String cityName, String originName,
                                                 double originLat, double originLon,
                                                 String destName, double destLat, double destLon,
                                                 String selectedOriginCity, String selectedDestCity,
                                                 int preference) throws Exception {
        int strategy = TransitExperience.strategy(preference);
        String transitKey = strategy + "|" + cacheText(cityName) + "|" + cacheText(selectedOriginCity)
                + "|" + cacheText(selectedDestCity) + "|" + cacheText(originName) + "|"
                + Math.round(originLat * 100000) + "|" + Math.round(originLon * 100000) + "|"
                + cacheText(destName) + "|" + Math.round(destLat * 100000) + "|"
                + Math.round(destLon * 100000);
        List<TransitPlan> cachedPlans = cached(TRANSIT_CACHE, transitKey, 5 * 60_000L);
        if (cachedPlans != null) return new ArrayList<>(cachedPlans);
        // 1. 高德省内 POI 搜起点/终点坐标（不限城市，跨市换乘可用）
        GaodeStop o = null;
        GaodeStop d = null;
        if (originLat != 0 && originLon != 0) {
            o = new GaodeStop(originName, originLat, originLon,
                    firstNonEmpty(selectedOriginCity, cityForCoordinate(originLat, originLon, cityName)));
        }
        if (destLat != 0 && destLon != 0) {
            d = new GaodeStop(destName, destLat, destLon,
                    firstNonEmpty(selectedDestCity, cityForCoordinate(destLat, destLon, cityName)));
        }
        if (o == null) {
            List<GaodeStop> origins = gaodeSearchPlaceCrossCity(originName, cityName);
            if (origins.isEmpty()) throw new IllegalStateException("未找到起点坐标");
            o = origins.get(0);
        }
        if (d == null) {
            List<GaodeStop> dests = gaodeSearchPlaceCrossCity(destName, cityName);
            if (dests.isEmpty()) throw new IllegalStateException("未找到终点坐标");
            d = dests.get(0);
        }
        // 起终点各自城市（adname，如 禅城区/广州市），用于跨城规划
        String originCity = (o.cityName == null || o.cityName.isEmpty()) ? cityName : o.cityName;
        String destCity = (d.cityName == null || d.cityName.isEmpty()) ? cityName : d.cityName;
        // 2. transit 请求
        String url = "https://restapi.amap.com/v3/direction/transit/integrated?key=" + GAODE_KEY
                + "&origin=" + o.lon + "," + o.lat
                + "&destination=" + d.lon + "," + d.lat
                + "&city=" + java.net.URLEncoder.encode(originCity, "UTF-8")
                + "&cityd=" + java.net.URLEncoder.encode(destCity, "UTF-8")
                + "&strategy=" + strategy + "&nightflag=0";
        JSONObject root = requestPlainNoWx(url);
        JSONObject route = root.optJSONObject("route");
        if (route == null) return new ArrayList<TransitPlan>();
        JSONArray transits = route.optJSONArray("transits");
        List<TransitPlan> plans = new ArrayList<>();
        if (transits != null) {
            for (int i = 0; i < transits.length(); i++) {
                JSONObject t = transits.optJSONObject(i);
                if (t == null) continue;
                int duration = t.optInt("duration", 0) / 60;
                String cost = formatFare(t.optString("cost", "0"));
                int walk = t.optInt("walking_distance", 0);
                List<TransitSegment> segs = new ArrayList<>();
                JSONArray segments = t.optJSONArray("segments");
                if (segments != null) {
                    for (int j = 0; j < segments.length(); j++) {
                        JSONObject seg = segments.optJSONObject(j);
                        if (seg == null) continue;
                        // 步行段
                        JSONObject walking = seg.optJSONObject("walking");
                        if (walking != null) {
                            int wd = walking.optInt("distance", 0);
                            if (wd > 0) {
                                segs.add(new TransitSegment("walk", "", "", "", wd, -1, -1));
                            }
                        }
                        // 公交段
                        JSONObject bus = seg.optJSONObject("bus");
                        if (bus != null) {
                            JSONArray buslines = bus.optJSONArray("buslines");
                            if (buslines != null && buslines.length() > 0) {
                                JSONObject bl = buslines.optJSONObject(0);
                                if (bl != null) {
                                    String name = bl.optString("name", "");
                                    JSONObject dep = bl.optJSONObject("departure_stop");
                                    JSONObject arr = bl.optJSONObject("arrival_stop");
                                    String from = dep == null ? "" : dep.optString("name", "");
                                    String to = arr == null ? "" : arr.optString("name", "");
                                    String cityHint = bl.optString("adcode", bl.optString("citycode", ""));
                                    String providerType = bl.optString("type", "");
                                    String region = RailCatalog.regionForLine(name);
                                    if (region.isEmpty() && !providerType.contains("公交")
                                            && !providerType.contains("巴士"))
                                        region = RailCatalog.regionForTrip(name, from, to, cityHint);
                                    String type = RailCatalog.GD.equals(region) ? "intercity"
                                            : RailCatalog.SH.equals(region) ? "suburban"
                                            : TransitRules.transitMode(providerType, name);
                                    TransitSegment ride = new TransitSegment(type, name,
                                            from, to,
                                            0,
                                            bl.optInt("via_num", -1),
                                            transitMinutes(bl.optInt("duration", -1)));
                                    double[] board = dep == null ? null : parseGaodePointWgs(dep.optString("location", ""));
                                    if (board != null) { ride.boardLat=board[0]; ride.boardLon=board[1]; }
                                    double[] alight = arr == null ? null : parseGaodePointWgs(arr.optString("location", ""));
                                    if (alight != null) { ride.alightLat=alight[0]; ride.alightLon=alight[1]; }
                                    ride.cityHint=cityHint;
                                    ride.terminal=bl.optString("end_stop","");
                                    segs.add(ride);
                                }
                            }
                        }
                        // 铁路段可能直接给出区间，也可能附带一组备选车次。
                        JSONObject railway = seg.optJSONObject("railway");
                        TransitSegment rail = parseRailway(railway);
                        if (rail != null) segs.add(rail);
                    }
                }
                plans.add(new TransitPlan(duration, cost, walk, segs));
            }
        }
        cache(TRANSIT_CACHE, transitKey, new ArrayList<>(plans));
        return plans;
    }

    static TransitSegment parseRailway(JSONObject railway) {
        if (railway == null || railway.length() == 0) return null;
        JSONArray trips = railway.optJSONArray("trips");
        JSONObject trip = trips == null ? railway.optJSONObject("trip") : trips.optJSONObject(0);
        String name = railway.optString("name", "");
        if (name.isEmpty() && trip != null) name = trip.optString("trip_name", trip.optString("name", ""));
        String from = transitStationName(railway, "departure_stop");
        String to = transitStationName(railway, "arrival_stop");
        if (trip != null) {
            if (from.isEmpty()) from = transitStationName(trip, "departure_stop");
            if (to.isEmpty()) to = transitStationName(trip, "arrival_stop");
        }
        if (from.isEmpty() || to.isEmpty()) return null;
        JSONObject board = railway.optJSONObject("departure_stop");
        if (board == null && trip != null) board = trip.optJSONObject("departure_stop");
        String cityHint = board == null ? "" : board.optString("adcode", board.optString("citycode", ""));
        String region = RailCatalog.regionForTrip(name, from, to, cityHint);
        String type = RailCatalog.GD.equals(region) ? "intercity"
                : RailCatalog.SH.equals(region) ? "suburban" : "railway";
        if (name.isEmpty()) name = "铁路";
        int duration = railway.optInt("duration", trip == null ? -1 : trip.optInt("duration", -1));
        TransitSegment result = new TransitSegment(type, name, from, to, 0,
                railway.optInt("via_num", -1), transitMinutes(duration));
        result.cityHint = cityHint;
        if (board != null) {
            double[] point = parseGaodePointWgs(board.optString("location", ""));
            if (point != null) { result.boardLat = point[0]; result.boardLon = point[1]; }
        }
        return result;
    }

    /** 高德不同城市会把铁路站点返回成字符串或对象，统一提取真实站名。 */
    private static String transitStationName(JSONObject object, String key) {
        if (object == null) return "";
        Object value = object.opt(key);
        if (value instanceof JSONObject) {
            JSONObject station = (JSONObject) value;
            String name = station.optString("name", "");
            if (name.isEmpty()) name = station.optString("station_name", "");
            return name;
        }
        return value instanceof String ? (String) value : "";
    }

    /** 金额统一为一位小数；非纯数字票价说明保持原样。 */
    static String formatFare(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace("元", "").replace("￥", "");
        if (value.matches("[-+]?\\d+(\\.\\d+)?")) {
            try {
                return String.format(java.util.Locale.CHINA, "%.1f", Double.parseDouble(value));
            } catch (Exception ignored) {
            }
        }
        return raw.trim();
    }

    /** 高德坐标与 WGS-84 在城市级判断上的偏差可忽略，按最近已知城市补齐跨城参数。 */
    private static String cityForCoordinate(double lat, double lon, String fallback) {
        CityInfo city = CityInfo.nearest(lat, lon);
        return city == null ? fallback : city.name;
    }

    /** 高德部分线路把地铁（尤其广佛线）返回成公交段，这里按线路名识别地铁。 */
    private static boolean isMetroLikeName(String name) {
        return "metro".equals(TransitRules.transitMode("", name));
    }

    private static int transitMinutes(int seconds) {
        return seconds < 0 ? -1 : (int) ((seconds + 59L) / 60L);
    }

    // ── 高德搜索（车来了搜索接口不可用，用高德兜底）──────
    private static final String GAODE_KEY = "";

    /** 广东省内 POI 搜索（不限市，用于跨市换乘起终点）。 */
    public static List<GaodeStop> gaodeSearchPlaceCrossCity(String keyword) throws Exception {
        return gaodeSearchPlaceCrossCity(keyword, "佛山");
    }

    public static List<GaodeStop> gaodeSearchPlaceCrossCity(String keyword, String cityName) throws Exception {
        String scope = searchScope(cityName);
        String cacheKey = "province|" + scope + "|" + cacheText(keyword);
        List<GaodeStop> cachedStops = cached(PLACE_CACHE, cacheKey, 60 * 60_000L);
        if (cachedStops != null) return new ArrayList<>(cachedStops);
        String url = "https://restapi.amap.com/v3/place/text?key=" + GAODE_KEY
                + "&keywords=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&city=" + java.net.URLEncoder.encode(scope, "UTF-8") + "&citylimit=true&offset=10";
        JSONObject data = requestPlainNoWx(url);
        List<GaodeStop> result = new ArrayList<>();
        JSONArray pois = data.optJSONArray("pois");
        if (pois != null) {
            for (int i = 0; i < pois.length(); i++) {
                JSONObject poi = pois.optJSONObject(i);
                if (poi == null) continue;
                if (!inSearchScope(poi, cityName, poi.optString("pname", "")
                        + poi.optString("cityname", "") + poi.optString("adname", ""))) continue;
                String name = poi.optString("name", "");
                String loc = poi.optString("location", "");
                if (name.isEmpty() || loc.isEmpty()) continue;
                try {
                    String[] parts = loc.split(",");
                    double lon = Double.parseDouble(parts[0]);
                    double lat = Double.parseDouble(parts[1]);
                    result.add(new GaodeStop(name, lat, lon,
                            poi.optString("cityname", poi.optString("adname", "")),
                            typePriority(poi.optString("typecode", ""))));
                } catch (Exception ignored) {
                }
            }
        }
        // 优先地铁站/公交站，避免同名道路/商场排在前面
        java.util.Collections.sort(result, (a, b) ->
                Integer.compare(a.typePriority, b.typePriority));
        cache(PLACE_CACHE, cacheKey, new ArrayList<>(result));
        return result;
    }

    /** POI 类型优先级：地铁站(1505xx) > 公交站(1507xx) > 其他。 */
    private static int typePriority(String typecode) {
        if (typecode == null) return 2;
        if (typecode.startsWith("1505")) return 0;
        if (typecode.startsWith("1507")) return 1;
        return 2;
    }

    /** 通用POI搜索（限定城市，用于换乘起终点：景点/地标/站都可）。 */
    public static List<GaodeStop> gaodeSearchPlace(String cityName, String keyword) throws Exception {
        String url = "https://restapi.amap.com/v3/place/text?key=" + GAODE_KEY
                + "&keywords=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&city=" + java.net.URLEncoder.encode(cityName, "UTF-8")
                + "&citylimit=true&offset=5";
        JSONObject data = requestPlainNoWx(url);
        List<GaodeStop> result = new ArrayList<>();
        JSONArray pois = data.optJSONArray("pois");
        if (pois != null) {
            for (int i = 0; i < pois.length(); i++) {
                JSONObject poi = pois.optJSONObject(i);
                if (poi == null) continue;
                String name = poi.optString("name", "");
                String loc = poi.optString("location", "");
                if (name.isEmpty() || loc.isEmpty()) continue;
                try {
                    String[] parts = loc.split(",");
                    double lon = Double.parseDouble(parts[0]);
                    double lat = Double.parseDouble(parts[1]);
                    result.add(new GaodeStop(
                            name, lat, lon,
                            poi.optString("cityname", poi.optString("adname", ""))
                    ));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    /** 高德输入联想（换乘起终点用）：按当前城市联想，返回 站名+线路副标题+坐标（GCJ）。 */
    public static List<GaodeTip> gaodeInputTips(String keyword, String cityName) throws Exception {
        return gaodeInputTips(keyword, cityName, true);
    }

    public static List<GaodeTip> gaodeInputTips(String keyword, String cityName, boolean allowPoiFallback) throws Exception {
        String scope = searchScope(cityName);
        String cacheKey = "tip|" + scope + "|" + cacheText(keyword) + "|" + allowPoiFallback;
        List<GaodeTip> cachedTips = cached(TIP_CACHE, cacheKey, 30 * 60_000L);
        if (cachedTips != null) return new ArrayList<>(cachedTips);
        List<GaodeTip> result = new java.util.ArrayList<>();
        String key = "";
        String url = "https://restapi.amap.com/v3/assistant/inputtips?key=" + key
                + "&keywords=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&city=" + java.net.URLEncoder.encode(scope, "UTF-8") + "&citylimit=true&datatype=all";
        JSONObject data = TIP_RESPONSES.get(url, () -> requestPlainNoWx(url));
        JSONArray tips = data.optJSONArray("tips");
        for (int i = 0; tips != null && i < tips.length(); i++) {
            JSONObject t = tips.optJSONObject(i);
            if (t == null) continue;
            String district = t.optString("district", "");
            if (!inSearchScope(t, cityName, district)) continue;
            String name = cleanGaodeStopName(t.optString("name", ""));
            String loc = t.optString("location", "");
            if (name.isEmpty() || loc.isEmpty()) continue;
            String[] ll = loc.split(",");
            if (ll.length < 2) continue;
            try {
                double lon = Double.parseDouble(ll[0]);
                double lat = Double.parseDouble(ll[1]);
                String city = cityFromAdcode(t.optString("adcode", ""));
                if (city.isEmpty()) city = cityFromDistrict(district);
                if (city.isEmpty()) city = cityName;
                String sub = t.optString("address", "");
                if ("[]".equals(sub) || "null".equalsIgnoreCase(sub)) sub = "";
                if (sub.isEmpty()) sub = district;
                String typecode = t.optString("typecode", "");
                int priority = typePriority(typecode);
                if (priority == 2 && t.optString("name", "").matches(".*(?:地铁站|地铁站[）)])$")) {
                    priority = 0;
                }
                result.add(new GaodeTip(name, sub, lat, lon, city, priority));
            } catch (Exception ignored) {
            }
        }

        if (allowPoiFallback && result.isEmpty()) {
            try {
                java.util.List<GaodeStop> poiFallback = gaodeSearchPlaceCrossCity(keyword, cityName);
                java.util.Set<String> known = new java.util.HashSet<>();
                for (GaodeTip tip : result) {
                    known.add(cleanGaodeStopName(tip.name) + "|" + tip.city);
                }
                for (GaodeStop poi : poiFallback) {
                    String city = trimCitySuffix(poi.cityName);
                    String candidateKey = cleanGaodeStopName(poi.name) + "|" + city;
                    if (known.contains(candidateKey)) continue;
                    String subtitle = "地点";
                    result.add(new GaodeTip(cleanGaodeStopName(poi.name), subtitle,
                            poi.lat, poi.lon, city, poi.typePriority));
                    known.add(candidateKey);
                    if (result.size() >= 20) break;
                }
            } catch (Exception ignored) {
                // 联想接口已有候选时，POI 补充失败不影响原结果。
            }
        }
        cache(TIP_CACHE, cacheKey, new ArrayList<>(result));
        return result;
    }

    private static String searchScope(String cityName) {
        String province = Regions.province(cityName);
        return province.isEmpty() ? cityName : province + "0000";
    }

    private static boolean inSearchScope(JSONObject row, String cityName, String district) {
        String province = Regions.province(cityName);
        if (!province.isEmpty()) return Regions.accepts(province,
                row.optString("adcode"), row.optString("pcode"), district);
        String matched = trimCitySuffix(row.optString("cityname"));
        if (matched.isEmpty()) matched = cityFromDistrict(district);
        return !cityName.isEmpty() && (cityName.equals(matched) || district.startsWith(cityName));
    }

    private static String trimCitySuffix(String city) {
        if (city == null) return "";
        String value = city.trim();
        return value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }

    /** 输入联想项：名称 + 副标题（线路/区域）+ 坐标（GCJ-02）+ 城市 + 类型优先级。 */
    public static final class GaodeTip {
        public final String name;
        public final String sub;
        public final double lat;
        public final double lon;
        public final String city;
        public final int typePriority;

        GaodeTip(String name, String sub, double lat, double lon, String city,
                 int typePriority) {
            this.name = name;
            this.sub = sub;
            this.lat = lat;
            this.lon = lon;
            this.city = city == null ? "" : city;
            this.typePriority = typePriority;
        }
    }

    /** 高德返回值的广东硬校验：行政区码优先，省名/完整 district 作为兜底。 */
    static boolean isGuangdongAdministrative(String adcode, String pcode,
                                              String province, String district) {
        String a = adcode == null ? "" : adcode.trim();
        String p = pcode == null ? "" : pcode.trim();
        if (a.startsWith("44") || p.startsWith("44")) return true;
        String area = (province == null ? "" : province)
                + (district == null ? "" : district);
        return area.contains("广东") || area.contains("Guangdong");
    }

    /** 由高德 adcode 判断广东地级市。 */
    private static String cityFromAdcode(String adcode) {
        if (adcode == null || adcode.length() < 4) return "";
        String a = adcode.substring(0, 4);
        if (a.equals("4401")) return "广州";
        if (a.equals("4403")) return "深圳";
        if (a.equals("4406")) return "佛山";
        if (a.equals("4402")) return "韶关";
        if (a.equals("4419")) return "东莞";
        if (a.equals("4404")) return "珠海";
        if (a.equals("4405")) return "汕头";
        if (a.equals("4407")) return "江门";
        if (a.equals("4408")) return "湛江";
        if (a.equals("4409")) return "茂名";
        if (a.equals("4412")) return "肇庆";
        if (a.equals("4413")) return "惠州";
        if (a.equals("4414")) return "梅州";
        if (a.equals("4415")) return "汕尾";
        if (a.equals("4416")) return "河源";
        if (a.equals("4417")) return "阳江";
        if (a.equals("4418")) return "清远";
        if (a.equals("4420")) return "中山";
        if (a.equals("4451")) return "潮州";
        if (a.equals("4452")) return "揭阳";
        if (a.equals("4453")) return "云浮";
        return "";
    }

    /** 从高德 district（如"广东省广州市天河区"）提取城市名。 */
    private static String cityFromDistrict(String district) {
        if (district == null) return "";
        String rest = district.replaceFirst("^(?:[^市]+省|[^市]+自治区)", "");
        int q = rest.indexOf("市");
        return q > 0 ? rest.substring(0, q) : "";
    }

    /** 高德圆形搜索：周围公交站/地铁站（用于地图显示站台位置）。返回 清理站名 → WGS坐标。 */
    public static java.util.Map<String, double[]> gaodeAroundStops(double lon, double lat, int radiusM)
            throws Exception {
        String cacheKey = Math.round(lon * 1000) + "|" + Math.round(lat * 1000) + "|" + radiusM;
        Map<String, double[]> cachedStops = cached(AROUND_CACHE, cacheKey, 12 * 60 * 60_000L);
        if (cachedStops != null) return new java.util.HashMap<>(cachedStops);
        java.util.Map<String, double[]> result = new java.util.HashMap<>();
        String key = "";
        // 首页只展示最近12站，最多50个候选足够；由4页降至2页直接减半调用。
        for (int page = 1; page <= 2; page++) {
            String url = "https://restapi.amap.com/v3/place/around?key=" + key
                    + "&location=" + lon + "," + lat
                    + "&radius=" + radiusM
                    + "&types=150500|170400"
                    + "&offset=25&page=" + page
                    + "&extensions=base";
            JSONObject data = requestPlainNoWx(url);
            JSONArray pois = data.optJSONArray("pois");
            if (pois == null || pois.length() == 0) break;
            for (int i = 0; i < pois.length(); i++) {
                JSONObject p = pois.optJSONObject(i);
                if (p == null) continue;
                String raw = p.optString("name", "");
                String loc = p.optString("location", "");
                if (raw.isEmpty() || loc.isEmpty()) continue;
                String[] ll = loc.split(",");
                if (ll.length < 2) continue;
                try {
                    double glon = Double.parseDouble(ll[0]);
                    double glat = Double.parseDouble(ll[1]);
                    // GCJ→WGS
                    double[] wgs = gcjToWgs(glat, glon);
                    String name = cleanGaodeStopName(raw);
                    if (!name.isEmpty() && !result.containsKey(name)) {
                        result.put(name, new double[]{wgs[0], wgs[1]});
                    }
                } catch (Exception ignored) {
                }
            }
        }
        cache(AROUND_CACHE, cacheKey, new java.util.HashMap<>(result));
        return result;
    }

    /** 高德站名清理："祖庙(地铁站)"→"祖庙"、"祖庙地铁站D口"→"祖庙"。 */
    private static String cleanGaodeStopName(String name) {
        String n = name.replaceAll("\\(.*?\\)", "");
        n = n.replaceAll("地铁站[ABCDEFG\\d]*口", "");
        n = n.replaceAll("地铁站", "");
        n = n.replaceAll("公交站", "");
        return n.trim();
    }
    public static final class GaodeStop {
        public final String name;
        public final double lat; // GCJ-02
        public final double lon;
        public final String cityName; // 所属城市（adname）
        public final int typePriority; // 0=地铁站 1=公交站 2=其他（用于优先选站）

        GaodeStop(String name, double lat, double lon, String cityName) {
            this(name, lat, lon, cityName, 2);
        }

        GaodeStop(String name, double lat, double lon, String cityName, int typePriority) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.cityName = cityName;
            this.typePriority = typePriority;
        }
    }

    public static List<GaodeStop> gaodeSearchStops(String cityName, String keyword) throws Exception {
        String url = "https://restapi.amap.com/v3/place/text?key=" + GAODE_KEY
                + "&keywords=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&city=" + java.net.URLEncoder.encode(cityName, "UTF-8")
                + "&citylimit=true&offset=10&types="
                + java.net.URLEncoder.encode("150500|150700", "UTF-8");
        JSONObject data = requestPlainNoWx(url);
        List<GaodeStop> result = new ArrayList<>();
        JSONArray pois = data.optJSONArray("pois");
        if (pois != null) {
            for (int i = 0; i < pois.length(); i++) {
                JSONObject poi = pois.optJSONObject(i);
                if (poi == null) continue;
                if (!inSearchScope(poi, cityName, poi.optString("pname", "")
                        + poi.optString("cityname", "") + poi.optString("adname", ""))) continue;
                String name = poi.optString("name", "");
                if (name.isEmpty()) continue;
                String loc = poi.optString("location", "");
                if (loc.isEmpty()) continue;
                try {
                    String[] parts = loc.split(",");
                    double lon = Double.parseDouble(parts[0]);
                    double lat = Double.parseDouble(parts[1]);
                    result.add(new GaodeStop(
                            name, lat, lon,
                            poi.optString("cityname", poi.optString("adname", "")),
                            typePriority(poi.optString("typecode", ""))
                    ));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    /** 高德搜索线路（linename）→ 线路静态信息。 */
    public static final class GaodeLine {
        String colour = "";
        final Map<String, String[]> stationHours = new LinkedHashMap<>();
        public final String transportType;
        public final int operatingStatus;
        public final boolean ring;
        public final String name;
        public final String startSn;
        public final String endSn;
        public final String firstTime;
        public final String lastTime;
        public final String price;
        public final List<String> stops; // 站点名列表
        public final List<double[]> stopPoints; // 与 stops 对齐，WGS-84 [lat, lon]
        public final List<double[]> path; // 沿道路的高德折线，WGS-84

        GaodeLine(String name, String startSn, String endSn, String firstTime,
                  String lastTime, String price, List<String> stops,
                  List<double[]> stopPoints, List<double[]> path, String transportType) {
            this(name, startSn, endSn, firstTime, lastTime, price, stops, stopPoints, path, transportType, 1, false);
        }

        GaodeLine(String name, String startSn, String endSn, String firstTime,
                  String lastTime, String price, List<String> stops,
                  List<double[]> stopPoints, List<double[]> path, String transportType, int operatingStatus, boolean ring) {
            this.operatingStatus = operatingStatus;
            this.ring = ring;
            this.transportType = transportType;
            this.name = name;
            this.startSn = startSn;
            this.endSn = endSn;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
            this.price = price;
            this.stops = stops;
            this.stopPoints = stopPoints;
            this.path = path;
        }
    }

    public static List<GaodeLine> gaodeSearchLines(String cityName, String keyword) throws Exception {
        String cacheKey = cacheText(cityName) + "|" + cacheText(keyword);
        List<GaodeLine> cachedLines = cached(LINE_CACHE, cacheKey, 24 * 60 * 60_000L);
        if (cachedLines != null) return new ArrayList<>(cachedLines);
        JSONObject data = gaodeLinePage(cityName, keyword, 5, 1);
        List<GaodeLine> result = parseGaodeLines(data.optJSONArray("buslines"), true);
        cache(LINE_CACHE, cacheKey, new ArrayList<>(result));
        return result;
    }

    /** Complete paginated city query. A failed/truncated page never becomes a replacement snapshot. */
    static List<GaodeLine> gaodeMetroCity(String cityName, List<MetroDataSource.MetroLine> baseline) throws Exception {
        List<GaodeLine> result = new ArrayList<>();
        java.util.Set<String> keywords = new java.util.LinkedHashSet<>();
        for (MetroDataSource.MetroLine line : baseline) {
            if (!MetroDataSource.cityOfLine(line).equals(cityName)) continue;
            if (line.name.contains("广佛")) keywords.add("广佛线");
            else if (line.name.contains("APM")) keywords.add("APM线");
            else {
                java.util.regex.Matcher numbers = java.util.regex.Pattern.compile("(\\d+)(?=/|号线)").matcher(line.name);
                while (numbers.find()) keywords.add("地铁" + numbers.group(1) + "号线");
            }
        }
        // Broad discovery also checks for new lines absent from the previous local catalog.
        keywords.add("地铁");
        for (String keyword : keywords) {
            int received = 0;
            boolean complete = false;
            for (int page = 1; page <= 10; page++) {
                JSONObject response = gaodeLinePage(cityName, keyword, 100, page);
                JSONArray rows = response.optJSONArray("buslines");
                if (rows == null) throw new IllegalStateException("高德未返回线路列表");
                received += rows.length(); result.addAll(parseGaodeLines(rows, false));
                int total = response.optInt("count", received);
                if (received >= total) { complete = true; break; }
                if (rows.length() == 0) throw new IllegalStateException("高德分页不完整");
            }
            if (!complete) throw new IllegalStateException("高德分页未完成，保留旧线网");
        }
        return result;
    }

    private static final Object LINE_REQUEST_LOCK = new Object();
    private static long lastLineRequestNanos;

    private static JSONObject gaodeLinePage(String city, String keyword, int limit, int page) throws Exception {
        String url = "https://restapi.amap.com/v3/bus/linename?key=" + GAODE_KEY
                + "&city=" + java.net.URLEncoder.encode(city, "UTF-8")
                + "&keywords=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&offset=" + limit + "&page=" + page + "&extensions=all";
        for (int attempt = 0; attempt < 3; attempt++) {
            synchronized (LINE_REQUEST_LOCK) {
                long waitNanos = 1_500_000_000L - (System.nanoTime() - lastLineRequestNanos);
                if (waitNanos > 0) Thread.sleep((waitNanos + 999_999L) / 1_000_000L);
                lastLineRequestNanos = System.nanoTime();
            }
            try {
                JSONObject response = requestPlainNoWx(url);
                if (!"1".equals(response.optString("status", "")))
                    throw new IllegalStateException("高德线路查询不可用：" + response.optString("infocode", "unknown"));
                return response;
            } catch (IllegalStateException limited) {
                String message = limited.getMessage();
                // 10021 is QPS throttling; a daily quota error (10003) cannot recover after seconds.
                if (attempt == 2 || message == null || !message.contains("10021")) throw limited;
                Thread.sleep(2000L << attempt);
            }
        }
        throw new IllegalStateException("高德线路查询暂不可用");
    }

    private static List<GaodeLine> parseGaodeLines(JSONArray lines, boolean withPath) {
        List<GaodeLine> result = new ArrayList<>();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject li = lines.optJSONObject(i);
                if (li == null) continue;
                String name = li.optString("name", "");
                if (name.isEmpty()) continue;
                List<String> stops = new ArrayList<>();
                List<double[]> stopPoints = new ArrayList<>();
                JSONArray busstops = li.optJSONArray("busstops");
                if (busstops != null) {
                    for (int j = 0; j < busstops.length(); j++) {
                        JSONObject bs = busstops.optJSONObject(j);
                        if (bs == null) continue;
                        String sn = bs.optString("name", "");
                        if (!sn.isEmpty()) {
                            stops.add(sn);
                            stopPoints.add(parseGaodePointWgs(bs.optString("location", "")));
                        }
                    }
                }
                List<double[]> path = new ArrayList<>();
                String rawPath = withPath ? li.optString("polyline", "") : "";
                for (String point : rawPath.replace('|', ';').split(";")) {
                    double[] wgs = parseGaodePointWgs(point);
                    if (wgs != null) path.add(wgs);
                }
                // 部分城市不返回 polyline，但 busstops.location 仍然完整；
                // 此时只用高德站点坐标生成可见线路，不回退到车来了坐标。
                if (path.size() < 2) {
                    for (double[] point : stopPoints) {
                        if (point != null) path.add(point);
                    }
                }
                result.add(new GaodeLine(
                        name,
                        li.optString("start_stop", ""),
                        li.optString("end_stop", ""),
                        li.optString("start_time", ""),
                        li.optString("end_time", ""),
                        li.optString("basic_price", ""),
                        stops,
                        stopPoints,
                        path,
                        li.optString("type", ""), li.optInt("status", 1), li.optInt("loop", 0) == 1
                ));
            }
        }
        return result;
    }

    private static double[] parseGaodePointWgs(String value) {
        if (value == null || value.isEmpty()) return null;
        String[] pair = value.split(",");
        if (pair.length < 2) return null;
        try {
            double lon = Double.parseDouble(pair[0]);
            double lat = Double.parseDouble(pair[1]);
            return gcjToWgs(lat, lon);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** GCJ-02 转 WGS-84（高德坐标 → 车来了坐标）。 */
    public static double[] gcjToWgs(double lat, double lon) {
        double a = 6378245.0;
        double ee = 0.00669342162296594323;
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat - dLat, lon - dLon};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    // ── 附近站点+实时（两方向站台都会返回）────────────────
    public static List<NearbyStop> queryNearby(String cityId, double lat, double lon) throws Exception {
        if (!TransitRules.validCoordinate(lat, lon)) return java.util.Collections.emptyList();
        if (cityId.startsWith("mg:")) return MygolbsDataSource.nearby(cityId, lat, lon);
        try {
            List<NearbyStop> primary = queryPrimaryNearby(cityId, lat, lon);
            if (!primary.isEmpty()) return primary;
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failed) { if (Thread.currentThread().isInterrupted()) throw failed; }
        return MygolbsDataSource.nearby(cityId, lat, lon);
    }

    private static List<NearbyStop> queryPrimaryNearby(String cityId, double lat, double lon) throws Exception {
        Map<String, String> params = new LinkedHashMap<>(DEFAULT_PARAMS);
        params.put("cityId", cityId);
        // 部分城市（惠州等）会拒绝 localCityId=undefined 的首页附近站请求。
        params.put("localCityId", cityId);
        params.put("lat", String.valueOf(lat));
        params.put("lng", String.valueOf(lon));
        params.put("geo_lat", String.valueOf(lat));
        params.put("geo_lng", String.valueOf(lon));
        params.put("type", "5");
        params.put("permission", "0");
        JSONObject data = request(BASE + "/api/bus/stop!encryptedHomePage.action", params);
        JSONArray nearSts = data.optJSONArray("nearSts");
        List<NearbyStop> result = new ArrayList<>();
        if (nearSts == null) return result;
        for (int i = 0; i < nearSts.length(); i++) {
            JSONObject s = nearSts.optJSONObject(i);
            if (s == null) continue;
            String sn = s.optString("sn", "");
            if (sn.isEmpty()) continue;
            List<LineArrival> lines = parseLines(s.optJSONArray("lines"));
            boolean subway = s.optInt("isSubway", 0) == 1;
            // 地铁线路（subwayV2Lines：线路名/方向/首末班/站序号）
            List<MetroLineInfo> metroLines = new ArrayList<>();
            if (subway) {
                JSONArray v2 = s.optJSONArray("subwayV2Lines");
                if (v2 != null) {
                    for (int j = 0; j < v2.length(); j++) {
                        JSONObject entry = v2.optJSONObject(j);
                        if (entry == null) continue;
                        JSONObject line = entry.optJSONObject("line");
                        String lineName = line == null ? "" : line.optString("lineName", "");
                        String bgColor = line == null ? "" : line.optString("bgColor", "");
                        JSONArray sublines = entry.optJSONArray("sublines");
                        if (sublines != null) {
                            for (int k = 0; k < sublines.length(); k++) {
                                JSONObject sub = sublines.optJSONObject(k);
                                if (sub == null) continue;
                                metroLines.add(new MetroLineInfo(
                                        lineName,
                                        bgColor,
                                        sub.optString("destName", ""),
                                        sub.optString("firstTime", ""),
                                        sub.optString("lastTime", ""),
                                        sub.optInt("orderNum", 0),
                                        sub.optInt("maxOrder", 0)
                                ));
                            }
                        }
                    }
                }
            }
            result.add(new NearbyStop(
                    s.optString("sId", ""),
                    sn,
                    s.optInt("distance", -1),
                    subway,
                    s.optString("physicalStId", ""),
                    s.optString("namesakeStId", ""),
                    cityId,
                    lines,
                    metroLines
            ));
        }
        return result;
    }

    // ── 站台详情（该站所有线路+双向+车辆）────────────────
    public static List<StopDetail> queryStopDetail(String cityId, String physicalStId,
                                                   String namesakeStId) throws Exception {
        return queryStopDetail(cityId, physicalStId, namesakeStId, "");
    }

    static List<StopDetail> queryStopDetail(String cityId, String physicalStId,
                                                   String namesakeStId, String stationId) throws Exception {
        for (String id : new String[]{stationId, physicalStId, namesakeStId})
            if (id != null && id.startsWith("mg:")) return MygolbsDataSource.stop(id);
        Map<String, String> params = new LinkedHashMap<>(DEFAULT_PARAMS);
        params.put("cityId", cityId);
        params.put("localCityId", cityId);
        params.put("physicalStId", physicalStId == null ? "" : physicalStId);
        params.put("namesakeStId", namesakeStId == null ? "" : namesakeStId);
        params.put("firstLineId", "");
        params.put("stationId", stationId == null ? "" : stationId);
        params.put("lat", "");
        params.put("lng", "");
        params.put("geo_lat", "");
        params.put("geo_lng", "");
        params.put("actionState", "1");
        params.put("permission", "0");
        String cacheKey = cityId + "|" + params.get("physicalStId") + "|"
                + params.get("namesakeStId") + "|" + params.get("stationId");
        JSONObject data = STOP_RESPONSES.get(cacheKey,
                () -> request(BASE + "/api/bus/stop!encryptedPhyStnDetail.action", params));
        JSONArray stationList = data.optJSONArray("stationList");
        List<StopDetail> result = new ArrayList<>();
        if (stationList == null) return result;
        for (int i = 0; i < stationList.length(); i++) {
            JSONObject st = stationList.optJSONObject(i);
            if (st == null) continue;
            String sn = st.optString("sn", "");
            if (sn.isEmpty()) continue;
            List<LineArrival> lines = parseLines(st.optJSONArray("lines"));
            result.add(new StopDetail(
                    st.optString("sId", ""),
                    sn,
                    st.optDouble("lat", 0),
                    st.optDouble("lng", 0),
                    lines
            ));
        }
        return result;
    }

    // ── 线路详情（一个方向：站点+全部实时车辆）──────────
    // 返回的 reverseLineId 是反向线路，用于双向展示。
    // lat/lon 传目标站台坐标，travelTime 即到该站台的时间。
    public static LineDetail queryLineDetail(String cityId, String lineId, double lat, double lon) throws Exception {
        return queryLineDetail(cityId, lineId, lat, lon, "");
    }

    public static LineDetail queryLineDetail(String cityId, String lineId, double lat, double lon,
                                             String stationName) throws Exception {
        if (lineId != null && lineId.startsWith("mg:")) return MygolbsDataSource.line(lineId, lat, lon, stationName);
        Map<String, String> params = new LinkedHashMap<>(DEFAULT_PARAMS);
        params.put("cityId", cityId);
        params.put("localCityId", cityId);
        params.put("lineId", lineId);
        params.put("lat", String.valueOf(lat));
        params.put("lng", String.valueOf(lon));
        params.put("geo_lat", String.valueOf(lat));
        params.put("geo_lng", String.valueOf(lon));
        String cacheKey = cityId + "|" + lineId + "|" + lat + "|" + lon;
        JSONObject data = LINE_RESPONSES.get(cacheKey,
                () -> request(BASE + "/api/bus/line!encryptedLineDetail.action", params));
        JSONObject line = data.optJSONObject("line");
        List<Bus> buses = new ArrayList<>();
        JSONArray busArr = data.optJSONArray("buses");
        if (busArr != null) {
            for (int i = 0; i < busArr.length(); i++) {
                JSONObject b = busArr.optJSONObject(i);
                if (b == null) continue;
                int travelTime = -1;
                String recommTip = "";
                JSONArray travels = b.optJSONArray("travels");
                if (travels != null && travels.length() > 0) {
                    JSONObject t = travels.optJSONObject(0);
                    if (t != null) {
                        travelTime = t.optInt("travelTime", -1);
                        recommTip = t.optString("recommTip", "");
                    }
                }
                buses.add(new Bus(
                        b.optString("busId", ""),
                        travelTime,
                        b.optInt("distanceToWaitStn", -1),
                        recommTip,
                        b.optString("licence", ""),
                        b.optInt("order", 0),
                        b.optDouble("lat", 0),
                        b.optDouble("lng", 0)
                ));
            }
        }
        // 站点列表（时间轴用）
        List<LineStation> stations = new ArrayList<>();
        JSONArray stArr = data.optJSONArray("stations");
        if (stArr != null) {
            for (int i = 0; i < stArr.length(); i++) {
                JSONObject st = stArr.optJSONObject(i);
                if (st == null) continue;
                String sn = st.optString("sn", "");
                if (sn.isEmpty()) continue;
                double[] position = TransitRules.stationCoordinate(st.optDouble("wgsLat", 0),
                        st.optDouble("wgsLng", 0), st.optDouble("lat", 0), st.optDouble("lng", 0));
                stations.add(new LineStation(
                        st.optInt("order", i + 1),
                        sn,
                        st.optString("sId", ""),
                        position[0], position[1]
                ));
                LineStation parsed = stations.get(stations.size() - 1);
                parsed.physicalStId = st.optString("physicalStId", "");
                parsed.namesakeStId = st.optString("namesakeStId", "");
            }
        }
        // 反向线路（otherlines[0]）
        String reverseLineId = "";
        JSONArray others = data.optJSONArray("otherlines");
        if (others != null && others.length() > 0) {
            JSONObject rev = others.optJSONObject(0);
            if (rev != null) reverseLineId = rev.optString("lineId", "");
        }
        return new LineDetail(
                line == null ? lineId : line.optString("lineId", lineId),
                line == null ? "" : line.optString("name", ""),
                line == null ? 0 : line.optInt("direction", 0),
                line == null ? "" : line.optString("startSn", ""),
                line == null ? "" : line.optString("endSn", ""),
                line == null ? "" : line.optString("firstTime", ""),
                line == null ? "" : line.optString("lastTime", ""),
                line == null ? "" : line.optString("price", ""),
                buses,
                stations,
                reverseLineId
        );
    }

    static List<StopDetail> queryStopWithFallback(String cityId, NearbyStop stop) throws Exception {
        try {
            List<StopDetail> primary = queryStopDetail(cityId, stop.physicalStId, stop.namesakeStId, stop.sId);
            for (StopDetail detail : primary) if (!detail.lines.isEmpty()) return primary;
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failed) { if (Thread.currentThread().isInterrupted()) throw failed; }
        String city = MygolbsDataSource.cityName(cityId);
        if (city.isEmpty() || stop.sId.startsWith("mg:")) return java.util.Collections.emptyList();
        return MygolbsDataSource.stop("mg:" + city + ":" + stop.name);
    }

    /** Resolve physical platform first; never relabel an ETA for the nearest unrelated stop. */
    public static List<Bus> queryArrivalsAtStation(String cityId, String lineId,
                                                  String name, String sid, double lat, double lon) throws Exception {
        return queryStationArrivals(cityId, name, sid, lat, lon).get(lineId);
    }

    public static Map<String, List<Bus>> queryStationArrivals(String cityId, String name,
                                                              String sid, double lat, double lon) throws Exception {
        Map<String, List<Bus>> result = new LinkedHashMap<>();
        // A line station ID must not depend on being present in the nearby-homepage list.
        if (sid != null && !sid.isEmpty()) {
            try {
                for (StopDetail detail : queryStopDetail(cityId, "", "", sid)) {
                    if (sid.equals(detail.sId) || TransitRules.samePlatformGroup(name, detail.name))
                        mergeStationArrivals(result, detail.lines);
                }
            } catch (Exception unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
            }
        }
        if (lat == 0 || lon == 0) return result;
        List<NearbyStop> stops;
        try { stops = queryNearby(cityId, lat, lon); }
        catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted() || result.isEmpty()) throw unavailable;
            return result;
        }
        int checked = 0;
        for (NearbyStop stop : stops) {
            boolean exactId = sid != null && !sid.isEmpty() && sid.equals(stop.sId);
            if (!exactId && (!TransitRules.samePlatformGroup(name, stop.name)
                    || stop.distanceM < 0 || stop.distanceM > 350)) continue;
            if (++checked > 3) break;
            // Home response already carries target-station predictions; retain them if
            // the secondary physical-platform endpoint is empty or temporarily fails.
            mergeStationArrivals(result, stop.lines);
            List<StopDetail> details;
            try {
                details = queryStopDetail(cityId, stop.physicalStId, stop.namesakeStId);
            } catch (Exception failedPlatform) {
                if (Thread.currentThread().isInterrupted()) throw failedPlatform;
                continue; // One platform failure must not discard the other direction.
            }
            boolean linked = (stop.namesakeStId != null && !stop.namesakeStId.isEmpty())
                    || (stop.physicalStId != null && !stop.physicalStId.isEmpty());
            for (StopDetail detail : details) {
                double distance = detail.lat == 0 || detail.lon == 0 ? -1
                        : GeoUtils.distanceKm(lat, lon, detail.lat, detail.lon) * 1000;
                if (!TransitRules.acceptsPlatform(name, sid, detail.name, detail.sId, linked, distance)) continue;
                for (LineArrival arrival : detail.lines) {
                    if (!arrival.lineId.isEmpty()) {
                        List<Bus> valid = new ArrayList<>();
                        for (Bus bus : arrival.buses) {
                            if (!(arrival.targetOrder > 0 && bus.order > arrival.targetOrder)) valid.add(bus);
                        }
                        List<Bus> existing = result.get(arrival.lineId);
                        result.put(arrival.lineId, mergeArrivalBuses(existing, valid));
                    }
                }
            }
        }
        return result;
    }

    static Map<String, List<Bus>> queryExactPlatformArrivals(String cityId, String stationId,
                                                            String physicalId, String namesakeId) throws Exception {
        Map<String, List<Bus>> arrivals = new LinkedHashMap<>();
        for (StopDetail detail : queryStopDetail(cityId, physicalId, namesakeId, stationId)) {
            if (stationId.equals(detail.sId)) mergeStationArrivals(arrivals, detail.lines);
        }
        return arrivals;
    }

    private static void mergeStationArrivals(Map<String, List<Bus>> result, List<LineArrival> lines) {
        for (LineArrival arrival : lines) {
            if (arrival.lineId.isEmpty()) continue;
            List<Bus> valid = new ArrayList<>();
            for (Bus bus : arrival.buses)
                if (!(arrival.targetOrder > 0 && bus.order > arrival.targetOrder)) valid.add(bus);
            List<Bus> previous = result.get(arrival.lineId);
            result.put(arrival.lineId, mergeArrivalBuses(previous, valid));
        }
    }

    // Merge only predictions for the same direction and target station, within one refresh.
    // Never match vehicles by array index: ordering differs between endpoints.
    static List<Bus> mergeArrivalBuses(List<Bus> previous, List<Bus> incoming) {
        if (previous == null || previous.isEmpty()) return new ArrayList<>(incoming);
        List<Bus> result = new ArrayList<>(previous);
        for (Bus bus : incoming) {
            if (bus.busId == null || bus.busId.isEmpty()) continue;
            int match = -1;
            for (int i = 0; i < result.size(); i++)
                if (bus.busId.equals(result.get(i).busId)) { match = i; break; }
            if (match < 0) result.add(bus);
            else result.set(match, mergeBusFields(result.get(match), bus));
        }
        // Anonymous responses cannot safely be combined vehicle by vehicle.
        boolean anonymous = false;
        for (Bus bus : previous) if (bus.busId == null || bus.busId.isEmpty()) anonymous = true;
        for (Bus bus : incoming) if (bus.busId == null || bus.busId.isEmpty()) anonymous = true;
        if (anonymous) {
            int oldTimes = 0, newTimes = 0;
            for (Bus bus : previous) if (bus.travelTime >= 0) oldTimes++;
            for (Bus bus : incoming) if (bus.travelTime >= 0) newTimes++;
            return new ArrayList<>(newTimes > oldTimes
                    || (newTimes == oldTimes && incoming.size() > previous.size()) ? incoming : previous);
        }
        return result;
    }

    /** 合并同一辆车的互补字段：站点接口常有站数，线路接口常有后续车辆时间。 */
    private static Bus mergeBusFields(Bus old, Bus fresh) {
        Bus merged = new Bus(
                old.busId == null || old.busId.isEmpty() ? fresh.busId : old.busId,
                fresh.travelTime >= 0 ? fresh.travelTime : old.travelTime,
                fresh.distanceToDest >= 0 ? fresh.distanceToDest : old.distanceToDest,
                fresh.recommTip == null || fresh.recommTip.isEmpty() ? old.recommTip : fresh.recommTip,
                fresh.licence == null || fresh.licence.isEmpty() ? old.licence : fresh.licence,
                fresh.order > 0 ? fresh.order : old.order,
                fresh.lat != 0 ? fresh.lat : old.lat,
                fresh.lon != 0 ? fresh.lon : old.lon);
        merged.stopsAway = fresh.stopsAway >= 0 ? fresh.stopsAway : old.stopsAway;
        return merged;
    }

    /**
     * 线路详情请求带有目标站坐标，其 travels 时间可补齐站点接口缺失的后续车辆。
     * 仅合并目标站之前的车辆，并以车辆 ID 对齐，避免把另一站/另一方向时间套进来。
     */
    static void fillLineDetailArrivals(LineDetail line, String stationName,
                                       Map<String, List<Bus>> arrivals) {
        if (line == null || stationName == null || stationName.isEmpty()
                || line.buses == null || line.buses.isEmpty()) return;
        int targetOrder = -1;
        for (LineStation station : line.stations) {
            if (TransitRules.samePlatformGroup(stationName, station.name)) {
                targetOrder = station.order;
                break;
            }
        }
        if (targetOrder < 0) return;
        List<Bus> incoming = new ArrayList<>();
        for (Bus bus : line.buses) {
            if (bus.order <= 0 || bus.order <= targetOrder) incoming.add(bus);
        }
        arrivals.put(line.lineId, mergeArrivalBuses(arrivals.get(line.lineId), incoming));
    }

    private static boolean allArrivalTimesKnown(List<Bus> buses) {
        if (buses == null || buses.isEmpty()) return false;
        for (Bus bus : buses) if (bus.travelTime < 0) return false;
        return true;
    }

    interface StationArrivalLoader {
        Map<String, List<Bus>> load(LineStation station, boolean physical) throws Exception;
    }

    public static void fillDirectionArrivals(String cityId, LineDetail line, String name,
                                              Map<String, List<Bus>> arrivals) {
        fillDirectionArrivals(line, name, arrivals, (station, physical) -> {
            if (!physical) return queryStationArrivals(cityId, station.name, station.sId,
                    station.lat, station.lon);
            Map<String, List<Bus>> exact = new LinkedHashMap<>();
            for (StopDetail st : queryStopDetail(cityId, station.physicalStId, station.namesakeStId))
                mergeStationArrivals(exact, st.lines);
            return exact;
        });
    }

    /** Isolate each endpoint failure; require the requested direction's line ID in all responses. */
    static void fillDirectionArrivals(LineDetail line, String name,
                                      Map<String, List<Bus>> arrivals, StationArrivalLoader loader) {
        if (line == null || Thread.currentThread().isInterrupted()) return;
        List<Bus> existing = arrivals.get(line.lineId);
        if (allArrivalTimesKnown(existing)) return;
        for (LineStation station : line.stations) {
            if (!TransitRules.samePlatformGroup(name, station.name)) continue;
            for (boolean physical : new boolean[]{true, false}) {
                if (physical && station.physicalStId.isEmpty()) continue;
                try {
                    List<Bus> found = loader.load(station, physical).get(line.lineId);
                    if (found != null) {
                        List<Bus> merged = mergeArrivalBuses(arrivals.get(line.lineId), found);
                        arrivals.put(line.lineId, merged);
                        if (allArrivalTimesKnown(merged)) return;
                        // A partial/empty physical response must not suppress the fallback.
                    }
                } catch (Exception unavailable) {
                    if (Thread.currentThread().isInterrupted()) return;
                }
            }
            return; // Do not guess another namesake stop on this line.
        }
    }

    // ── 解析 helpers ──────────────────────────────────
    private static List<LineArrival> parseLines(JSONArray lines) {
        List<LineArrival> result = new ArrayList<>();
        if (lines == null) return result;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject li = lines.optJSONObject(i);
            if (li == null) continue;
            JSONObject line = li.optJSONObject("line");
            if (line == null) continue;
            String name = line.optString("name", "");
            String lineId = line.optString("lineId", "");
            if (name.isEmpty() && lineId.isEmpty()) continue;
            List<Bus> buses = new ArrayList<>();
            JSONArray stnStates = li.optJSONArray("stnStates");
            if (stnStates != null) {
                for (int j = 0; j < stnStates.length(); j++) {
                    JSONObject b = stnStates.optJSONObject(j);
                    if (b == null) continue;
                    buses.add(new Bus(
                            b.optString("busId", ""),
                            b.optInt("travelTime", -1),
                            b.optInt("distanceToDest", -1),
                            "",
                            "",
                            0,
                            0,
                            0
                    ));
                    buses.get(buses.size() - 1).stopsAway = b.optInt("value", -1);
                }
            }
            JSONObject target = li.optJSONObject("targetStation");
            int targetOrder = target == null ? -1 : target.optInt("order", -1);
            // 过滤 stub 线路：无起终点信息且无车辆（同名站空数据）。
            // 注意：真实收班线路（有起终点）必须保留显示"已收班"
            String endSn = line.optString("endSn", "");
            String startSn = line.optString("startSn", "");
            if (endSn.isEmpty() && startSn.isEmpty() && buses.isEmpty()) continue;
            result.add(new LineArrival(
                    lineId,
                    name,
                    line.optInt("direction", 0),
                    endSn,
                    startSn,
                    firstNonEmpty(li.optString("shortDesc", ""), li.optString("nextOperationTimeDesc", "")),
                    targetOrder,
                    line.optInt("stationsNum", -1),
                    line.optString("firstTime", ""),
                    line.optString("lastTime", ""),
                    buses
            ));
        }
        return result;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    // ── 网络层 ────────────────────────────────────────
    private static JSONObject request(String url, Map<String, String> params) throws Exception {
        String body = httpGet(url, params, true);
        return parseEnvelope(body);
    }

    private static JSONObject requestPlain(String url, Map<String, String> params) throws Exception {
        String body = httpGet(url, params, false);
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IllegalStateException("车来了数据异常");
        return data;
    }

    /** 普通请求（不带车来了微信头，用于 Code410/高德）。 */
    private static JSONObject requestPlainNoWx(String url) throws Exception {
        if (url.startsWith("https://restapi.amap.com/") && !AppSettings.hasMapKey())
            throw new IllegalStateException("请先配置高德 Web 服务 Key");
        url = AppSettings.credentialUrl(url);
        String body = httpGetInternal(url, null, false, false);
        JSONObject root = new JSONObject(body);
        if (url.startsWith("https://restapi.amap.com/") && "0".equals(root.optString("status", ""))) {
            throw new IllegalStateException("地图服务暂不可用（" + root.optString("infocode", "未知") + "）");
        }
        return root;
    }

    private static String httpGet(String url, Map<String, String> params, boolean sign) throws Exception {
        return httpGetInternal(url, params, sign, true);
    }

    private static String httpGetInternal(String url, Map<String, String> params, boolean sign,
                                          boolean chelaileHeaders) throws Exception {
        if (!PrivacyNotice.locationAllowed()) throw new IllegalStateException("请先查看应用隐私说明");
        if (url.startsWith("https://restapi.amap.com/") && !PrivacyNotice.amapNoticeSeen())
            throw new IllegalStateException("请先查看高德 Key 使用说明");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("查询已取消");
        // 签名参数放在最后（与上游校验一致）
        Map<String, String> query = params == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(params);
        if (sign) {
            query.put("cryptoSign", cryptoSign(params));
        }
        // URL 已带 ? 时不再追加（高德/Code410 对末尾多余 ? 会 404/403）
        StringBuilder target = new StringBuilder(url);
        boolean first = url.indexOf('?') < 0;
        for (Map.Entry<String, String> e : query.entrySet()) {
            target.append(first ? '?' : '&');
            first = false;
            target.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(target.toString()).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        if (chelaileHeaders) {
            for (Map.Entry<String, String> h : HEADERS.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
        } else {
            // 普通浏览器头（高德/Code410 不带车来了微信特征，避免风控 403/404）
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
        }
        long deadline = BoundedStreams.deadlineAfter(20_000);
        int limit = url.startsWith("https://restapi.amap.com/v3/direction/") ? 4 * 1024 * 1024 : 2 * 1024 * 1024;
        try {
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                // These callers only use the status on failure. Discard the error body.
                throw new java.io.IOException("请求失败(" + conn.getURL().getHost() + ") HTTP " + status);
            }
            if (conn.getContentLength() > limit) throw new java.io.IOException("响应数据超过大小限制");
            try (InputStream stream = conn.getInputStream();
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                BoundedStreams.copy(stream, bytes, limit, deadline);
                return bytes.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject parseEnvelope(String raw) throws Exception {
        JSONObject envelope = readEnvelopeObject(raw);
        JSONObject jsonr = envelope.optJSONObject("jsonr");
        if (jsonr == null) throw new IllegalStateException("车来了响应缺少 jsonr");
        JSONObject data = jsonr.optJSONObject("data");
        if (data == null) throw new IllegalStateException("车来了响应缺少 data");
        String encryptResult = data.optString("encryptResult", "");
        if (!encryptResult.isEmpty()) {
            return new JSONObject(aesDecrypt(encryptResult));
        }
        return data;
    }

    // ── 签名 & 解密 ───────────────────────────────────
    private static String cryptoSign(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append('"').append(e.getKey()).append('"').append('=')
                    .append('"').append(e.getValue()).append('"').append('&');
        }
        sb.append(SIGN_SALT);
        return md5(sb.toString());
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String aesDecrypt(String ciphertext) throws Exception {
        SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.DEFAULT));
        return new String(decrypted, "UTF-8");
    }

    static JSONObject readEnvelopeObject(String raw) throws org.json.JSONException {
        String body = raw.trim();
        if (body.startsWith("\uFEFF")) body = body.substring(1).trim();
        if (body.startsWith("/**/")) body = body.substring(4).trim();
        // The encrypted bus endpoints surround the JSON with these two markers.
        String prefix = "**YGKJ";
        String suffix = "YGKJ##";
        if (body.startsWith(prefix)) {
            if (!body.endsWith(suffix) || body.length() <= prefix.length() + suffix.length()) {
                throw new org.json.JSONException("车来了响应外壳不完整");
            }
            body = body.substring(prefix.length(), body.length() - suffix.length()).trim();
        }
        boolean callback = !body.startsWith("{");
        if (callback) {
            int start = body.indexOf('(');
            if (start < 1 || start > 128 || !body.substring(0, start).trim().matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                throw new org.json.JSONException("车来了响应外壳异常");
            }
            body = body.substring(start + 1);
        }
        org.json.JSONTokener parser = new org.json.JSONTokener(body);
        Object value = parser.nextValue();
        char end = parser.nextClean();
        if (callback) {
            if (end != ')') throw new org.json.JSONException("回调外壳未结束");
            end = parser.nextClean();
            if (end == ';') end = parser.nextClean();
        }
        if (!(value instanceof JSONObject) || end != 0) throw new org.json.JSONException("车来了响应异常");
        return (JSONObject) value;
    }
}
