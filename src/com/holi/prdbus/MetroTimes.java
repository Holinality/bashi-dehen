package com.holi.prdbus;

import java.util.Calendar;
import java.util.List;

/**
 * 地铁时刻表/预测共用工具。
 * 与 MetroLineActivity、MainActivity（地铁站线路列表）共享，避免重复逻辑。
 * 预测逻辑：首末班为官方/内置数据，中间班次按高峰 3 分 / 平峰 6 分间隔推算。
 */
final class MetroTimes {
    /** 站间估算时计入的停站时间（分钟）：约30秒开门/关门/启停。 */
    static final double DWELL_MIN = 0.5;

    private MetroTimes() {
    }

    static boolean validTime(String v) {
        if (v == null || !v.matches("\\d{1,2}:\\d{2}")) return false;
        String[] p = v.split(":");
        try {
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            return h >= 0 && h <= 29 && m >= 0 && m < 60;
        } catch (Exception ignored) {
            return false;
        }
    }

    static int toMinutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    static String formatTime(int minute) {
        int h = (minute / 60) % 24;
        int m = minute % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** 快线识别：广州18/22号线、深圳11/14号线为大站快线，发车间隔更大。 */
    static boolean isExpressLine(String lineName) {
        if (lineName == null) return false;
        if (lineName.contains("18号线") || lineName.contains("22号线")) return true;
        return lineName.startsWith("深圳")
                && (lineName.contains("11号线") || lineName.contains("14号线"));
    }

    /** 按线路类型校准的旅行速度（km/min），用于官方时间缺失时的站间估算。 */
    static double speedKmPerMin(String lineName) {
        if (lineName == null) return 0.55;
        if (lineName.contains("18号线")) return 1.3;   // 160km/h 快线，全程约 78km/h
        if (lineName.contains("22号线")) return 0.9;   // 全程约 54km/h
        if (lineName.startsWith("深圳")
                && (lineName.contains("11号线") || lineName.contains("14号线"))) return 0.95;
        if (lineName.contains("APM")) return 0.42;     // 胶轮捷运，速度较低
        return 0.55;                                   // 普通市区线约 33km/h
    }

    /** 高峰窗口：早 7:00-9:00、晚 17:00-20:00。 */
    static boolean isPeak(int minute) {
        return (minute >= 420 && minute <= 540)
                || (minute >= 1020 && minute <= 1200);
    }

    /** 该发车时刻是否仍按高峰间隔发车：下一班必须仍落在高峰窗口内，
     *  避免 09:00→09:03→09:09 这类相位跳变（高峰末班须落在 09:00 整，之后直接转平峰）。 */
    static boolean isPeakDeparture(int minute, int peakStep) {
        return (minute >= 420 && minute + peakStep <= 540)
                || (minute >= 1020 && minute + peakStep <= 1200);
    }

    /** 官方口径的线路行车间隔（高峰/平峰，分钟）；null = 按普通线/快线默认值。
     *  顺序注意：14号线先于4号线判断（14号线也包含"4号线"子串）。 */
    static int[] lineHeadway(String lineName) {
        if (lineName == null) return null;
        if (lineName.startsWith("香港")) {
            if (lineName.contains("机场快线")) return new int[]{10, 10};
            if (lineName.contains("迪士尼线")) return new int[]{5, 10};
            if (lineName.contains("东铁线")) return lineName.contains("落马洲")
                    ? new int[]{10, 12} : new int[]{5, 8};
            if (lineName.contains("将军澳线")) return lineName.contains("康城")
                    ? new int[]{7, 10} : new int[]{4, 5};
            if (lineName.contains("东涌线")) return new int[]{7, 9};
            if (lineName.contains("屯马线") || lineName.contains("南港岛线")) return new int[]{3, 7};
            return new int[]{3, 5};
        }
        if (lineName.startsWith("上海市域机场线")) return new int[]{15, 15};
        String key = lineName.replaceFirst("^广州", "").replaceFirst("[（(].*$", "");
        if (key.equals("14号线") || key.equals("14号线支线")) return new int[]{6, 10};
        if (key.equals("21号线")) return new int[]{8, 10};
        if (key.equals("4号线")) return new int[]{5, 8};
        return null;
    }

    /** 发车间隔：普通线高峰3分/平峰6分；快线高峰6分/平峰10分；个别线路按官方口径覆盖。 */
    static int intervalAt(int minute, boolean express) {
        return intervalAt(minute, express, null);
    }

    static int intervalAt(int minute, String lineName) {
        return intervalAt(minute, isExpressLine(lineName), lineName);
    }

    static int intervalAt(int minute, boolean express, String lineName) {
        int[] hw = lineHeadway(lineName);
        int peak = hw != null ? hw[0] : express ? 6 : 3;
        int offPeak = hw != null ? hw[1] : express ? 10 : 6;
        if (hw != null && peak == offPeak) return peak;
        if (isPeakDeparture(minute, peak)) return peak;
        int dayMinute = ((minute % 1440) + 1440) % 1440;
        // 首班初段与夜间通常比日间平峰更疏，避免统一 6 分钟造成虚假密集列车。
        if (dayMinute < 390 || dayMinute >= 1320) return offPeak + 2;
        // 高峰前后采用肩峰间隔，让 7:00/9:00、17:00/20:00 附近平滑过渡。
        if ((dayMinute >= 390 && dayMinute < 420)
                || (dayMinute > 540 && dayMinute <= 630)
                || (dayMinute >= 960 && dayMinute < 1020)
                || (dayMinute > 1200 && dayMinute < 1320)) {
            return Math.max(peak + 1, offPeak - 1);
        }
        return offPeak;
    }

    static int intervalAt(int minute) {
        return intervalAt(minute, false);
    }

    /** 发车间隔文案（按官方口径）。 */
    static String headwayText(String lineName) {
        int[] hw = lineHeadway(lineName);
        if (hw != null) return "高峰约 " + hw[0] + "分 · 平峰约 " + hw[1] + "分";
        return isExpressLine(lineName)
                ? "高峰约 6分 · 平峰约 10分（快线）"
                : "高峰约 3分 · 平峰约 6分";
    }

    /** 站时刻序列：首末班（+该站累计分钟）→ 分时段间隔 → 最近 count 班。 */
    static String nextTrains(String first, String last, int cumMin, int nowMin, int count) {
        return nextTrains(first, last, cumMin, nowMin, count, false, null);
    }

    static String nextTrains(String first, String last, int cumMin, int nowMin,
                             int count, boolean express) {
        return nextTrains(first, last, cumMin, nowMin, count, express, null);
    }

    static String nextTrains(String first, String last, int cumMin, int nowMin,
                             int count, String lineName) {
        return nextTrains(first, last, cumMin, nowMin, count,
                isExpressLine(lineName), lineName);
    }

    static String nextTrains(String first, String last, int cumMin, int nowMin,
                             int count, boolean express, String lineName) {
        if (!validTime(first) || !validTime(last)) {
            return "今日已无班次";
        }
        int f = toMinutes(first) + cumMin;
        int l = toMinutes(last) + cumMin;
        if (l <= f) l += 1440;
        int now = nowMin;
        if (l > 1440 && now < f) now += 1440;
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (int t = f; t <= l && added < count; ) {
            if (t >= now) {
                if (added > 0) sb.append("  ");
                sb.append(formatTime(t));
                added++;
            }
            int step = intervalAt(t, express, lineName);
            if (step <= 0) break;
            t += step;
        }
        if (added == 0) sb.append("今日已无班次");
        return sb.toString();
    }

    /** 当前时刻最近一班（单班），无班次返回 null。 */
    static String nextTrainAt(String first, String last, int cumMin, int nowMin) {
        return nextTrainAt(first, last, cumMin, nowMin, false, null);
    }

    static String nextTrainAt(String first, String last, int cumMin, int nowMin,
                              boolean express) {
        return nextTrainAt(first, last, cumMin, nowMin, express, null);
    }

    static String nextTrainAt(String first, String last, int cumMin, int nowMin,
                              String lineName) {
        return nextTrainAt(first, last, cumMin, nowMin, isExpressLine(lineName), lineName);
    }

    static String nextTrainAt(String first, String last, int cumMin, int nowMin,
                              boolean express, String lineName) {
        String s = nextTrains(first, last, cumMin, nowMin, 1, express, lineName);
        return "今日已无班次".equals(s) ? null : s;
    }

    /** 从方向起点站的发车序列推算某站接下来 count 趟的预计到站时间。
     *  班次 = 起点首班 + 按线路行车间隔生成的发车时刻；
     *  到站 = 发车 + 到该站的累计行驶分钟（站距/官方区间分钟）。
     *  返回按时间递增的 "HH:MM" 列表。 */
    static java.util.List<String> stationArrivals(
            MetroDataSource.MetroLine line, List<MetroDataSource.Station> raw,
            int idx, boolean forward, int nowMin, int count, double kmPerMin) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (line == null || raw == null || raw.isEmpty() || count <= 0
                || idx < 0 || idx >= raw.size()) {
            return out;
        }
        return new MetroSchedule(line, raw, forward, kmPerMin).arrivals(idx, nowMin, count);
    }

    /** 该站正向/反向首班（站级缺失时回退线路级）。 */
    static String stationFirst(MetroDataSource.MetroLine line, MetroDataSource.Station station,
                               boolean forward) {
        String v = forward ? station.first : station.firstR;
        if (validTime(v)) return v;
        return estimatedWindow(line, station, forward, false);
    }

    /** 该站正向/反向末班（站级缺失时回退线路级）。 */
    static String stationLast(MetroDataSource.MetroLine line, MetroDataSource.Station station,
                              boolean forward) {
        String v = forward ? station.last : station.lastR;
        if (validTime(v)) return v;
        return estimatedWindow(line, station, forward, true);
    }

    private static String estimatedWindow(MetroDataSource.MetroLine line, MetroDataSource.Station station,
                                          boolean forward, boolean last) {
        MetroSchedule schedule = new MetroSchedule(line, line.stations, forward, speedKmPerMin(line.name));
        int rawIndex = MetroPatterns.index(line.stations, station.name);
        if (rawIndex < 0 || schedule.departures.isEmpty()) return "--";
        int index = forward ? rawIndex : line.stations.size() - rawIndex - 1;
        int departure = schedule.departures.get(last ? schedule.departures.size()-1 : 0);
        return formatTime(departure + (int)Math.round(schedule.offsets[index]));
    }

    /** 若该站没有真实时刻表，返回用线路首班 + 累计行驶时间估算的偏移量。 */
    static int stationOffset(MetroDataSource.MetroLine line, List<MetroDataSource.Station> raw,
                             int rawIndex, boolean forward) {
        return stationOffset(line, raw, rawIndex, forward, speedKmPerMin(line.name));
    }

    static int stationOffset(MetroDataSource.MetroLine line, List<MetroDataSource.Station> raw,
                             int rawIndex, boolean forward, double kmPerMin) {
        if (rawIndex < 0 || rawIndex >= raw.size()) return 0;
        MetroDataSource.Station station = raw.get(rawIndex);
        String v = forward ? station.first : station.firstR;
        if (validTime(v)) return 0;
        return rawCumulative(raw, rawIndex, forward, kmPerMin);
    }

    /** 从线路端点（正向/反向）到 rawIndex 站的累计行驶分钟。 */
    static int rawCumulative(List<MetroDataSource.Station> raw, int rawIndex, boolean forward) {
        return rawCumulative(raw, rawIndex, forward, 0.55);
    }

    static int rawCumulative(List<MetroDataSource.Station> raw, int rawIndex, boolean forward,
                             double kmPerMin) {
        int n = raw.size();
        if (n < 2) return 0;
        if (rawIndex < 0) rawIndex = 0;
        if (rawIndex >= n) rawIndex = n - 1;
        int cum = 0;
        if (forward) {
            for (int k = 0; k < rawIndex && k < n - 1; k++) {
                cum += travelMinutes(raw.get(k), raw.get(k + 1), true, kmPerMin);
            }
        } else {
            for (int k = n - 2; k >= rawIndex; k--) {
                cum += travelMinutes(raw.get(k + 1), raw.get(k), false, kmPerMin);
            }
        }
        return cum;
    }

    /** 相邻两站行驶分钟：优先用真实首班时间差，异常/缺失时用距离估算。 */
    static int travelMinutes(MetroDataSource.Station a, MetroDataSource.Station b, boolean forward) {
        return travelMinutes(a, b, forward, 0.55);
    }

    static int travelMinutes(MetroDataSource.Station a, MetroDataSource.Station b,
                             boolean forward, double kmPerMin) {
        // 官方首班车区间分钟（含停站）优先
        int real = forward ? a.segMin : b.segMin;
        if (real > 0) return real;
        int estimate = distanceMinutes(a, b, forward, kmPerMin);
        String af = forward ? a.first : a.firstR;
        String bf = forward ? b.first : b.firstR;
        if (validTime(af) && validTime(bf)) {
            int diff = toMinutes(bf) - toMinutes(af);
            if (diff < 0) diff += 1440;
            if (diff > 0 && Math.abs(diff - estimate) <= Math.max(2, estimate / 2)) return diff;
        }
        return estimate;
    }

    static int distanceMinutes(MetroDataSource.Station a, MetroDataSource.Station b) {
        return distanceMinutes(a, b, 0.55);
    }

    static int distanceMinutes(MetroDataSource.Station a, MetroDataSource.Station b,
                               double kmPerMin) {
        return distanceMinutes(a, b, true, kmPerMin);
    }

    static int distanceMinutes(MetroDataSource.Station a, MetroDataSource.Station b,
                               boolean forward, double kmPerMin) {
        double d = segmentKm(a, b, forward);
        double speed = kmPerMin > 0.1 ? kmPerMin : 0.55;
        return Math.max(1, (int) Math.round(d / speed + DWELL_MIN));
    }

    /** 站间轨道里程：segKm（OSM/站连线）→ 球面距离兜底。 */
    static double segmentKm(MetroDataSource.Station a, MetroDataSource.Station b,
                            boolean forward) {
        double s = forward ? a.segKm : b.segKm;
        if (s > 0) return s;
        return haversineKm(a, b);
    }

    /** 球面距离（km）。 */
    static double haversineKm(MetroDataSource.Station a, MetroDataSource.Station b) {
        if (a.lat == 0 || b.lat == 0) return 1.5;
        double r = 6371.0;
        double p1 = Math.toRadians(a.lat);
        double p2 = Math.toRadians(b.lat);
        double dp = Math.toRadians(b.lat - a.lat);
        double dl = Math.toRadians(b.lon - a.lon);
        double x = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(x)));
    }

    static int nowMinutes() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }
    static double nowMinuteExact() {
        Calendar now = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0;
    }
}
