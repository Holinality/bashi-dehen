package com.holi.prdbus;

/** Shared, Android-independent rules for search scope and arrival presentation. */
final class TransitRules {
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern STATION_SUFFIX = java.util.regex.Pattern.compile("(公交站|地铁站)$");
    private static final java.util.regex.Pattern PLACE_QUALIFIER = java.util.regex.Pattern.compile("[（(](公交站|地铁站)[）)]$");
    private static final java.util.regex.Pattern PLACE_SUFFIX = java.util.regex.Pattern.compile("(公交站|地铁站|站)$");
    private TransitRules() { }

    /** Provider mode wins; stop names in parentheses are never evidence of a rail line. */
    static String transitMode(String providerType, String lineName) {
        String type = providerType == null ? "" : providerType.trim().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("公交") || type.contains("巴士") || type.contains("大巴")
                || type.contains("专车") || type.contains("专线") || "bus".equals(type)) return "bus";
        if (type.contains("地铁") || type.contains("轻轨") || "metro".equals(type)
                || "subway".equals(type)) return "metro";
        String name = routeIdentity(lineName);
        if (name.contains("接驳") || name.contains("公交") || name.contains("巴士")
                || name.contains("大巴") || name.contains("专线") || name.contains("快线")) return "bus";
        if (name.matches("(?:广州|深圳|佛山|东莞)?(?:地铁|轨道交通)?"
                + "[0-9一二三四五六七八九十]+号线(?:/[0-9一二三四五六七八九十]+号线)?"
                + "(?:北延段|南延段|东延段|西延段|支线|主线)?"
                + "(?:快车|慢车|内环|外环)?")) return "metro";
        if (name.matches("(?:广州|佛山)?(?:地铁)?广佛线")
                || name.matches("(?i)(?:广州)?(?:地铁)?APM(?:线)?")) return "metro";
        return "bus";
    }

    static String routeIdentity(String lineName) {
        if (lineName == null) return "";
        return lineName.trim().split("[（(]", 2)[0].replaceAll("\\s+", "");
    }

    static boolean useInternalMetroCoordinate(int placeType) {
        // 0=metro, 1=bus stop, 2=place. A nearby rail line in the subtitle does not change the POI.
        return placeType == 0;
    }

    static String placeNameKey(String value) {
        if (value == null) return "";
        String clean = SPACES.matcher(value.trim()).replaceAll("");
        clean = PLACE_QUALIFIER.matcher(clean).replaceAll("");
        return PLACE_SUFFIX.matcher(clean).replaceAll("");
    }

    static String suggestionKey(String name, String city, int placeType) {
        return placeNameKey(name) + "|" + (city == null ? "" : city) + "|" + placeType;
    }

    static boolean validCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat > -90 && lat < 90 && lon >= -180 && lon <= 180
                && lat != 0 && lon != 0;
    }

    static double[] stationCoordinate(double wgsLat, double wgsLon, double lat, double lon) {
        if (validCoordinate(wgsLat, wgsLon)) return new double[]{wgsLat, wgsLon};
        if (validCoordinate(lat, lon)) return new double[]{lat, lon};
        return new double[]{0, 0};
    }

    static String stationKey(String value) {
        if (value == null) return "";
        // Preserve direction and station qualifiers: 公交站A and 公交站B are not interchangeable.
        String clean = value.replace("（公交站）", "").replace("(公交站)", "")
                .replace("（地铁站）", "").replace("(地铁站)", "");
        return SPACES.matcher(STATION_SUFFIX.matcher(clean).replaceAll("")).replaceAll("");
    }

    static boolean sameStation(String a, String b) {
        String key = stationKey(a);
        return !key.isEmpty() && key.equals(stationKey(b));
    }

    /** Direction labels may differ, but real place-name suffixes must remain intact. */
    static String platformKey(String name) {
        return stationKey(name).replaceAll("[（(](上行|下行|东行|西行|南行|北行|东侧|西侧|南侧|北侧|路东|路西|路南|路北)[）)]", "");
    }

    static boolean samePlatformGroup(String a, String b) {
        String key = platformKey(a);
        return !key.isEmpty() && key.equals(platformKey(b));
    }

    static boolean acceptsPlatform(String wantedName, String wantedId, String actualName,
                                   String actualId, boolean serverLinked, double distanceM) {
        if (wantedId != null && !wantedId.isEmpty() && wantedId.equals(actualId)) return true;
        if (sameStation(wantedName, actualName)) return true;
        // A namesake/physical group is explicit server evidence, unlike fuzzy name search.
        return serverLinked && (distanceM < 0 || distanceM <= 800);
    }

    static boolean canArrive(int seconds, int nextOrder, int targetOrder) {
        return seconds >= 0 && !(targetOrder > 0 && nextOrder > targetOrder);
    }

    static boolean confirmedArrival(int seconds, int distanceM) {
        return seconds == 0 && distanceM >= 0 && distanceM <= 50;
    }

    static String etaLabel(int seconds) {
        if (seconds < 0) return "暂无预测";
        if (seconds < 60) return "即将到站";
        return ((seconds + 59L) / 60L) + " 分钟";
    }

    static CityInfo explicitCity(String query) {
        if (query == null) return null;
        String value = Regions.stripProvince(query);
        CityInfo named = Regions.explicitCity(value);
        if (named != null) return named;
        for (CityInfo city : CityInfo.all()) {
            if (value.startsWith(city.name) && value.length() > city.name.length()
                    && value.substring(city.name.length()).matches("^[市\\s·:：/].*")) return city;
        }
        return null;
    }

    static String keyword(String query) {
        if (query == null) return "";
        String value = Regions.stripProvince(query);
        CityInfo city = explicitCity(value);
        if (city == null) return value;
        return value.substring(city.name.length()).replaceFirst("^市", "")
                .replaceFirst("^[\\s·:：/]+", "").trim();
    }
}
