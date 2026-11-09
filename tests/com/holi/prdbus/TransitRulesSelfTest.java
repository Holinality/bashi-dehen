package com.holi.prdbus;

/** Regression cases from cross-city searches and misleading arrival reports. */
public final class TransitRulesSelfTest {
    public static void main(String[] args) {
        check(TransitRules.explicitCity("惠州 西湖公园").name.equals("惠州"), "explicit city");
        check(TransitRules.keyword("广东省惠州市 西湖公园").equals("西湖公园"), "province prefix");
        check(TransitRules.keyword("广州：北京路").equals("北京路"), "colon prefix");
        check(TransitRules.explicitCity("广州南站") == null, "station containing city must stay intact");
        check(TransitRules.keyword("广州南站").equals("广州南站"), "preserve full station name");
        check(TransitRules.explicitCity("北京 西单").name.equals("北京"), "nationwide explicit city");
        check(TransitRules.keyword("江苏省南京市 新街口").equals("新街口"), "national province prefix");
        check(TransitRules.explicitCity("北京路") == null, "preserve local road name");
        check(Regions.province("柳州").equals("45"), "non-metro city province");
        check(Regions.normalizeCity(" 临沂市(公交) ").equals("临沂"), "provider suffix display");
        check(Regions.province("临沂市（公交）").equals("37"), "provider suffix province");
        check(TransitRules.sameStation("西湖公园(公交站)", "西湖公园"), "POI type suffix");
        check(!TransitRules.sameStation("西湖公园南", "西湖公园"), "distinct directional platforms");
        check(!TransitRules.sameStation("火车站", "火车"), "do not remove meaningful characters");
        check(!TransitRules.sameStation("祖庙(A口)", "祖庙(B口)"), "preserve platform qualifiers");
        check(!TransitRules.sameStation("", ""), "missing station is not a match");
        check(TransitRules.canArrive(180, 4, 5), "approaching bus");
        check(TransitRules.canArrive(15, 5, 5), "approaching selected stop");
        check(!TransitRules.canArrive(60, 6, 5), "passed bus with stale ETA");
        check(!TransitRules.canArrive(-1, 4, 5), "unknown ETA");
        check(!TransitRules.confirmedArrival(30, 20), "30 seconds is not arrived");
        check(!TransitRules.confirmedArrival(0, 500), "zero ETA alone is not arrived");
        check(!TransitRules.confirmedArrival(0, -1), "unknown distance is not arrived");
        check(TransitRules.confirmedArrival(0, 0), "confirmed near stop");
        check(TransitRules.etaLabel(61).equals("2 分钟"), "round consistently upward");
        check(TransitRules.etaLabel(60).equals("1 分钟"), "one minute boundary");
        check(TransitRules.etaLabel(-1).equals("暂无预测"), "unknown distinct from no vehicles");
        check(TransitRules.samePlatformGroup("公园(上行)", "公园（下行）"), "platform qualifiers");
        check(!TransitRules.samePlatformGroup("公园南", "公园"), "preserve meaningful suffix");
        check(TransitRules.acceptsPlatform("公园", "a", "公园对面", "b", true, 80), "linked opposite platform");
        check(!TransitRules.acceptsPlatform("公园", "a", "公园对面", "b", false, 80), "unlinked nearby platform");
        check(!TransitRules.acceptsPlatform("公园", "a", "公园对面", "b", true, 2000), "distant namesake");
        check(TransitRules.acceptsPlatform("公园", "a", "公园对面", "b", true, -1), "server association without coordinates");
        check(TransitRules.acceptsPlatform("旧名", "a", "新名", "a", false, 0), "exact platform identity");
        System.out.println("TransitRulesSelfTest passed (29 cases)");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
