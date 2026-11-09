package com.holi.prdbus;

/** 经纬度计算与 Web Mercator 投影工具。 */
final class GeoUtils {
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
    }

    /** 两个经纬度点之间的近似距离（公里）。 */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /** 根据中心点与缩放级别计算 Web Mercator 像素坐标。 */
    static double[] toPixel(double lat, double lon, int zoom) {
        double n = Math.pow(2, zoom);
        double x = (lon + 180.0) / 360.0 * n * 256.0;
        double latRad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * 256.0;
        return new double[]{x, y};
    }

    /** 像素坐标反算经纬度。 */
    static double[] toLatLon(double x, double y, int zoom) {
        double n = Math.pow(2, zoom);
        double lon = x / 256.0 / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1 - 2 * y / 256.0 / n)));
        return new double[]{Math.toDegrees(latRad), lon};
    }
}
