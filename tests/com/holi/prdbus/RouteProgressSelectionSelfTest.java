package com.holi.prdbus;

public final class RouteProgressSelectionSelfTest {
    static void check(int actual, int expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": " + actual);
    }
    public static void main(String[] args) {
        check(RouteProgressSelection.select(new double[]{.8,.1,.7}, new float[]{.2f,.5f,.8f}, -1), 1,
                "one closest interval");
        check(RouteProgressSelection.select(new double[]{-1,-1}, new float[2], 0), -1,
                "no match clears position");
        check(RouteProgressSelection.select(new double[]{.15,.10}, new float[]{.5f,0}, 0), 0,
                "GPS jitter keeps current interval");
        check(RouteProgressSelection.select(new double[]{.02,.02}, new float[]{1,0}, 0), 1,
                "shared transfer moves to next leg");
        check(RouteProgressSelection.select(new double[]{1,.1}, new float[]{.6f,.3f}, 0), 1,
                "clear movement changes interval");
        check(RouteProgressSelection.select(new double[]{Double.NaN,2}, new float[2], -1), -1,
                "invalid or distant fixes excluded");
        check(RouteProgressSelection.select(new double[]{.1,-1,.2}, new float[3], -1), 0,
                "walking gap excluded");
        System.out.println("RouteProgressSelectionSelfTest passed (7 scenarios)");
    }
}
