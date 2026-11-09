package com.holi.prdbus;

final class TransitExperience {
    static final String[] PREFERENCES = {"推荐", "少换乘", "少步行"};
    static int strategy(int preference) {
        return preference == 1 ? 2 : preference == 2 ? 3 : 0;
    }
    static String platformHint(double userLat, double userLon, double lat, double lon,
                               long fixAt, float accuracy, long now) {
        if (lat == 0 || lon == 0) return "站台坐标待确认 · 请核对站牌方向";
        if (fixAt <= 0 || now < fixAt || now - fixAt > 120000 || accuracy <= 0 || accuracy > 60)
            return "请定位后确认站台方位 · 上车前核对开往方向";
        double distance = GeoUtils.distanceKm(userLat, userLon, lat, lon) * 1000;
        if (distance < Math.max(25, accuracy))
            return "已在站台附近 · 请核对站牌开往方向";
        double a = Math.toRadians(userLat), b = Math.toRadians(lat);
        double d = Math.toRadians(lon - userLon);
        double bearing = (Math.toDegrees(Math.atan2(Math.sin(d) * Math.cos(b),
                Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(d))) + 360) % 360;
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return "站台在您" + names[(int)Math.round(bearing / 45) % 8] + "侧约"
                + Math.round(distance) + "米 · 直线距离";
    }
    static String dataState(long successAt, boolean failed, long now) {
        if (successAt <= 0) return "尚未取得实时数据";
        long seconds = Math.max(0, (now - successAt) / 1000);
        if (failed) return "更新失败 · 保留上次数据（" + seconds + "秒前）";
        return seconds >= 45 ? "数据已过期 · " + seconds + "秒前更新" : "实时预测 · 刚刚更新";
    }
}
