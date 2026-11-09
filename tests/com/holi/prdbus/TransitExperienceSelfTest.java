package com.holi.prdbus;

public final class TransitExperienceSelfTest {
    static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        check(TransitExperience.strategy(0) == 0, "recommended");
        check(TransitExperience.strategy(1) == 2, "fewest transfers");
        check(TransitExperience.strategy(2) == 3, "least walking");
        check(TransitExperience.strategy(99) == 0, "invalid preference");
        check(TransitExperience.dataState(0, false, 100000).contains("尚未"), "no snapshot");
        check(TransitExperience.dataState(90000, false, 100000).contains("实时"), "fresh");
        check(TransitExperience.dataState(10000, false, 100000).contains("过期"), "stale");
        check(TransitExperience.dataState(90000, true, 100000).contains("保留"), "failed retains snapshot");
        check(TransitExperience.platformHint(23,113,0,0,100000,10,100000).contains("待确认"), "missing platform");
        check(TransitExperience.platformHint(23,113,23.001,113,100000,200,100000).contains("请定位"), "poor fix");
        check(TransitExperience.platformHint(23,113,23.001,113,100000,10,100000).contains("北侧"), "north");
        check(TransitExperience.platformHint(23,113,23,113.001,100000,10,100000).contains("东侧"), "east");
        check(TransitExperience.platformHint(23,113,23,113,100000,10,100000).contains("附近"), "nearby");
        System.out.println("TransitExperienceSelfTest passed (13 scenarios)");
    }
}
