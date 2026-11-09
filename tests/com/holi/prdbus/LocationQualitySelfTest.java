package com.holi.prdbus;

public final class LocationQualitySelfTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        long now = 1_000_000L;
        check(LocationQuality.accept(23, 113, 300, now, now, 0, 0, Float.MAX_VALUE), "initial network fix");
        check(LocationQuality.accept(23, 113, 12, now, now, now-1000, now-1000, 300), "GPS refinement");
        check(!LocationQuality.accept(23, 113, 800, now, now, now-1000, now-1000, 12), "reject degrading network fix");
        check(!LocationQuality.accept(23, 113, 10, now-121000, now, 0, 0, 0), "reject stale cache");
        check(!LocationQuality.accept(23, 113, 10, now-2000, now, now-1000, 0, 0), "reject out-of-order fix");
        check(!LocationQuality.accept(Double.NaN, 113, 10, now, now, 0, 0, 0), "reject NaN");
        check(!LocationQuality.accept(23, 113, Float.NaN, now, now, 0, 0, 0), "reject invalid accuracy");
        check(!LocationQuality.accept(23, 113, 10, now+6000, now, 0, 0, 0), "reject future timestamp");
        check(LocationQuality.accept(23, 113, 300, now, now, now-60000, now-60000, 12), "recover when GPS unavailable");
        System.out.println("LocationQualitySelfTest passed (9 scenarios)");
    }
}
