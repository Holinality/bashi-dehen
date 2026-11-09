package com.holi.prdbus;

/** WGS-84 与 GCJ-02（火星坐标）互转。高德/国内瓦片用 GCJ-02，GPS 与车来了数据用 WGS-84。 */
final class Coord {
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private Coord() {
    }

    /** WGS-84 → GCJ-02（仅中国大陆范围内偏移）。 */
    static double[] wgsToGcj(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** GCJ-02 → WGS-84（迭代逼近）。 */
    static double[] gcjToWgs(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double wgsLat = lat;
        double wgsLon = lon;
        for (int i = 0; i < 3; i++) {
            double[] converted = wgsToGcj(wgsLat, wgsLon);
            wgsLat -= converted[0] - lat;
            wgsLon -= converted[1] - lon;
        }
        return new double[]{wgsLat, wgsLon};
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
