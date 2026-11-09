package com.holi.prdbus;

/** Pure location acceptance policy, independent of Android callbacks. */
final class LocationQuality {
    static boolean accept(double lat, double lon, float accuracy, long timestamp,
                          long now, long previousTimestamp, long acceptedAt, float previousAccuracy) {
        if (Double.isNaN(lat) || Double.isInfinite(lat) || Double.isNaN(lon) || Double.isInfinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || Float.isNaN(accuracy) || Float.isInfinite(accuracy) || accuracy < 0 || accuracy > 1500
                || timestamp > now + 5000 || now - timestamp > 120_000L
                || timestamp < previousTimestamp) return false;
        return !(acceptedAt > 0 && now - acceptedAt < 30_000L
                && accuracy > Math.max(100, previousAccuracy * 2));
    }
}
