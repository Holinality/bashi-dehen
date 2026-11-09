package com.holi.prdbus;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 全国地铁/轨道交通线路数据源。
 * 全应用统一高德城市线网优先；临时本地缓存与内置时刻资料补充、断网回退。
 * 覆盖：广州、深圳、佛山、东莞（站名/线路/换乘站）。
 */
public final class MetroDataSource {
    private static volatile List<MetroLine> bundled;
    private static final java.util.concurrent.atomic.AtomicBoolean WARMING = new java.util.concurrent.atomic.AtomicBoolean();

    private final Context context;

    public MetroDataSource(Context context) {
        this.context = context.getApplicationContext();
    }

    public static final class MetroLine {
        public String networkId;
        public String dataSource = "本地线网";
        public long dataAt;
        public String serviceLabel = "";
        public final String name;      // 城市前缀 + 线号（广州1号线）
        public final String colour;
        public final String branchInfo; // Y字形分叉去向（如"往机场北方向"），无=空
        public final boolean ring;      // 环线（如广州11号线）
        public final String firstTime;  // 正向首班（如 06:15，缺失=06:00）
        public final String lastTime;   // 正向末班
        public final String firstTimeR; // 反向首班
        public final String lastTimeR;  // 反向末班
        public final List<Station> stations = new ArrayList<>();
        public final java.util.List<String> exprStops = new ArrayList<>(); // 快车停靠站
        public String exprNote = "";     // 快车说明（间隔/版本，官方口径）

        public MetroLine(String name, String colour, String branchInfo, boolean ring,
                         String firstTime, String lastTime, String firstTimeR, String lastTimeR) {
            this.name = name;
            this.networkId = name;
            this.colour = colour;
            this.branchInfo = branchInfo;
            this.ring = ring;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
            this.firstTimeR = firstTimeR;
            this.lastTimeR = lastTimeR;
        }
    }

    public static final class Station {
        public final String name;
        public final double lat;
        public final double lon;
        public final double segKm;   // 本站→下一站（正向）轨道里程（km）
        public final int segMin;     // 官方首班车区间行驶分钟（含停站，0=未知）
        public final List<String> interchange; // 换乘线路（空=非换乘站）
        public final boolean branch;           // Y字形分叉点（如体育西路）
        public final String branchText;        // 分叉去向（如"往机场北方向"）
        public final String first;  // 正向首班车到达/开出本站
        public final String last;   // 正向末班车
        public final String firstR; // 反向首班车
        public final String lastR;  // 反向末班车

        public Station(String name, double lat, double lon, double segKm, int segMin,
                       List<String> interchange, boolean branch, String branchText,
                       String first, String last, String firstR, String lastR) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.segKm = segKm;
            this.segMin = segMin;
            this.interchange = interchange;
            this.branch = branch;
            this.branchText = branchText;
            this.first = first;
            this.last = last;
            this.firstR = firstR;
            this.lastR = lastR;
        }
    }

    /** Shared provider-first snapshot. Call on a worker thread. */
    public List<MetroLine> queryLines() throws Exception {
        return queryLines(AppSettings.activeProvince(context));
    }

    List<MetroLine> queryLines(String province) throws Exception {
        if (bundled == null) synchronized (MetroDataSource.class) {
            if (bundled == null) {
                String asset = readAsset("metro_data.json");
                bundled = asset == null ? new ArrayList<MetroLine>() : parseData(asset);
            }
        }
        return MetroRepository.load(context, bundled, province);
    }

    public MetroLine queryPreferredLine(String requestedName) throws Exception {
        return MetroPatterns.find(queryLines(), requestedName);
    }

    static void prefetch(Context context) {
        if (!WARMING.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try { new MetroDataSource(application).queryLines(); }
            catch (Exception unavailable) { }
        }, "metro-prefetch");
        thread.start();
    }

    /** Parses bundled metadata only; provider snapshots use their own ordered station arrays. */
    static List<MetroLine> parseData(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        boolean wgs84 = "WGS84".equalsIgnoreCase(root.optString("crs", ""))
                || "WGS-84".equalsIgnoreCase(root.optString("crs", ""));
        JSONArray linesArr = root.optJSONArray("lines");
        List<MetroLine> lines = new ArrayList<>();
        if (linesArr == null) return lines;
        // 1. 线路名归一化 + 同名重复线路合并（广佛线/佛山2号线/广州7号线）
        java.util.Map<String, JSONObject> byName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < linesArr.length(); i++) {
            JSONObject lo = linesArr.optJSONObject(i);
            if (lo == null) continue;
            String name = canonicalLineName(lo.optString("name", ""));
            if (name.isEmpty()) continue;
            lo.put("name", name);
            JSONObject prev = byName.get(name);
            if (prev == null) {
                byName.put(name, lo);
            } else {
                mergeLineStops(prev, lo);
            }
        }
        List<JSONObject> lineObjs = new ArrayList<>(byName.values());
        // 2. 换乘计算：同城站名 → 线路集合（跨市线路按所属城市集合匹配）
        java.util.Map<String, java.util.Set<String>> stopToLines = new java.util.HashMap<>();
        for (JSONObject lo : lineObjs) {
            String lineName = lo.optString("name", "");
            if (lineName.isEmpty()) continue;
            java.util.Set<String> cities = citiesOfLine(lineName);
            JSONArray stops = lo.optJSONArray("stops");
            if (stops != null) {
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject s = stops.optJSONObject(j);
                    if (s == null) continue;
                    String sn = s.optString("n", "");
                    if (sn.isEmpty()) continue;
                    for (String city : cities) {
                        String key = city + "|" + sn;
                        java.util.Set<String> set = stopToLines.get(key);
                        if (set == null) {
                            set = new java.util.HashSet<>();
                            stopToLines.put(key, set);
                        }
                        set.add(lineName);
                    }
                }
            }
        }
        for (JSONObject lo : lineObjs) {
            String lineName = lo.optString("name", "");
            java.util.Set<String> cities = citiesOfLine(lineName);
            MetroLine line = new MetroLine(lineName, lo.optString("colour", ""),
                    lo.optString("brn", ""), lo.optInt("ring", 0) == 1,
                    sc(lo, "f"), sc(lo, "l"), sc(lo, "fr"), sc(lo, "lr"));
            line.networkId = lo.optString("networkId", lineName);
            line.serviceLabel = lo.optString("serviceLabel", "");
            line.dataSource = lo.optString("dataSource", root.optString("source", "本地线网"));
            JSONObject expr = lo.optJSONObject("expr");
            if (expr != null) {
                JSONArray es = expr.optJSONArray("stops");
                if (es != null) {
                    for (int j = 0; j < es.length(); j++) {
                        String sn = es.optString(j, "");
                        if (!sn.isEmpty()) line.exprStops.add(sn);
                    }
                }
                line.exprNote = expr.optString("note", "");
            }
            JSONArray stops = lo.optJSONArray("stops");
            if (stops != null) {
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject s = stops.optJSONObject(j);
                    if (s == null) continue;
                    String sn = s.optString("n", "");
                    java.util.Set<String> others = new java.util.HashSet<>();
                    for (String city : cities) {
                        java.util.Set<String> set = stopToLines.get(city + "|" + sn);
                        if (set != null) others.addAll(set);
                    }
                    others.remove(lineName);
                    List<String> x = new ArrayList<>();
                    for (String ol : others) {
                        x.add(ol);
                    }
                    java.util.Collections.sort(x);
                    double lat = s.optDouble("lat", 0), lon = s.optDouble("lon", 0);
                    if (wgs84 && TransitRules.validCoordinate(lat, lon)) {
                        double[] gcj = Coord.wgsToGcj(lat, lon); lat = gcj[0]; lon = gcj[1];
                    }
                    line.stations.add(new Station(
                            sn,
                            lat,
                            lon,
                            s.optDouble("segKm", 0),
                            s.optInt("segMin", 0),
                            x,
                            s.optInt("br", 0) == 1,
                            s.optString("brt", ""),
                            s.optString("f", ""),
                            s.optString("l", ""),
                            s.optString("fr", ""),
                            s.optString("lr", "")
                    ));
                }
            }
            lines.add(line);
        }
        return lines;
    }

    /** Missing timetable fields stay unknown; a topology dataset is not a timetable. */
    private static String sc(JSONObject line, String key) {
        JSONObject scObj = line.optJSONObject("sc");
        String v = scObj == null ? "" : scObj.optString(key, "");
        if (v.isEmpty() || "--".equals(v)) {
            return "";
        }
        return v;
    }

    /** 线路所属城市（跨市线路归到主要城市，如 广佛线 → 广州）。 */
    public static String cityOfLine(MetroLine line) {
        if ("广佛线".equals(line.name)) return "广州";
        String owner = PublicMetro.owner(line.name);
        if (!owner.isEmpty()) return owner;
        java.util.regex.Matcher prefix = java.util.regex.Pattern.compile("^([\\p{IsHan}]+?)(?=[0-9]|APM)").matcher(line.name);
        if (prefix.find() && !Regions.province(prefix.group(1)).isEmpty()) return prefix.group(1);
        return line.name.length() >= 2 ? line.name.substring(0, 2) : "广州";
    }

    /** 跨市/重复线路名归一化（资产与热更新数据通用）。 */
    private static final java.util.Map<String, String> LINE_ALIASES = new java.util.HashMap<>();
    static {
        LINE_ALIASES.put("广州广佛线", "广佛线");
        LINE_ALIASES.put("佛山广佛线", "广佛线");
        LINE_ALIASES.put("广州佛山2号线", "佛山2号线");
        LINE_ALIASES.put("佛山佛山2号线", "佛山2号线");
        LINE_ALIASES.put("佛山7号线", "广州7号线");
    }

    private static String canonicalLineName(String name) {
        String c = LINE_ALIASES.get(name);
        return c == null ? name : c;
    }

    /** 跨市线路所属城市集合（换乘匹配用），默认线路名前两字。 */
    private static java.util.Set<String> citiesOfLine(String name) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if ("广佛线".equals(name)) {
            set.add("广州");
            set.add("佛山");
            return set;
        }
        if ("佛山2号线".equals(name)) {
            set.add("佛山");
            set.add("广州");
            return set;
        }
        if ("广州7号线".equals(name)) {
            set.add("广州");
            set.add("佛山");
            return set;
        }
        set.add(name.length() >= 2 ? name.substring(0, 2) : "广州");
        return set;
    }

    /** 合并同名重复线路的站点（保留基线站序，追加缺失站）。 */
    private static void mergeLineStops(JSONObject base, JSONObject other) {
        JSONArray baseStops = base.optJSONArray("stops");
        JSONArray otherStops = other.optJSONArray("stops");
        if (baseStops == null || otherStops == null) return;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < baseStops.length(); i++) {
            JSONObject s = baseStops.optJSONObject(i);
            if (s != null) names.add(s.optString("n", ""));
        }
        for (int i = 0; i < otherStops.length(); i++) {
            JSONObject s = otherStops.optJSONObject(i);
            if (s == null) continue;
            String n = s.optString("n", "");
            if (n.isEmpty() || names.contains(n)) continue;
            names.add(n);
            baseStops.put(s);
        }
    }

    /** 归一化线路颜色："FCD600" / "#FCD600" / 空 → 默认浅蓝。 */
    public static String normalizeColour(String colour) {
        if (colour == null) return "#7EB0DB";
        String c = colour.trim();
        if (c.startsWith("#")) {
            return c.length() >= 7 ? c.substring(0, 7) : "#7EB0DB";
        }
        if (c.length() == 6) {
            try {
                Long.parseLong(c, 16);
                return "#" + c;
            } catch (Exception ignored) {
            }
        }
        return "#7EB0DB";
    }

    private String readAsset(String name) {
        try {
            InputStream is = context.getAssets().open(name);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }


}
