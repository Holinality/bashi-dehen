package com.holi.prdbus;

public final class TransitModeSelfTest {
    public static void main(String[] args) {
        String[][] examples = {
                {"普通公交", "桂18路(九龙公园南--地铁西约站)", "bus"},
                {"", "445路(南十里居--地铁望京西站)", "bus"},
                {"", "42路（汽车站—地铁3号线站）", "bus"},
                {"普通公交", "3号线(地铁接驳站--中心)", "bus"},
                {"2", "地铁接驳专线(市桥--广州南站)", "bus"},
                {"", "广佛快线(地铁站--汽车站)", "bus"},
                {"地铁线路", "广州3号线(机场北--海傍)", "metro"},
                {"", "地铁3号线(机场北--海傍)", "metro"},
                {"", "广佛线（新城东--沥滘）", "metro"},
                {"", "深圳2号线/8号线(赤湾--盐田路)", "metro"},
                {"", "地铁11号线内环", "metro"},
                {"", "APM线", "metro"}
        };
        for (String[] fixture : examples) require(fixture[2].equals(
                TransitRules.transitMode(fixture[0], fixture[1])), "wrong mode: " + fixture[1]);
        require(!TransitRules.suggestionKey("西约站", "佛山", 0).equals(
                TransitRules.suggestionKey("西约站", "佛山", 1)), "bus and metro candidates collapsed");
        require(!TransitRules.useInternalMetroCoordinate(1), "bus stop was snapped onto metro station");
        require(!TransitRules.useInternalMetroCoordinate(2), "landmark was snapped onto metro station");
        require(TransitRules.useInternalMetroCoordinate(0), "metro coordinate correction lost");
        require(TransitRules.placeNameKey("车站南路").equals("车站南路"), "meaningful 站 deleted");
        require(!TransitRules.placeNameKey("祖庙(A口)").equals(TransitRules.placeNameKey("祖庙(B口)")), "exits collapsed");
        require(TransitRules.placeNameKey("北京路地铁站").equals("北京路"), "POI suffix not handled");
        System.out.println("TransitModeSelfTest passed (19 cases)");
    }
    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
