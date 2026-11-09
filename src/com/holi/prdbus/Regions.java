package com.holi.prdbus;

import java.util.LinkedHashMap;
import java.util.Map;

/** Administrative search scope and initial metro discovery candidates. */
final class Regions {
    private static final String[][] AREAS = {
        {"11","北京","北京"},{"12","天津","天津"},{"13","河北","石家庄"},
        {"14","山西","太原"},{"15","内蒙古","呼和浩特"},
        {"21","辽宁","沈阳 大连"},{"22","吉林","长春"},{"23","黑龙江","哈尔滨"},
        {"31","上海","上海"},{"32","江苏","南京 苏州 无锡 常州 徐州 南通"},
        {"33","浙江","杭州 宁波 温州 绍兴 金华 台州"},
        {"34","安徽","合肥 芜湖"},{"35","福建","福州 厦门"},
        {"36","江西","南昌"},{"37","山东","济南 青岛"},
        {"41","河南","郑州 洛阳"},{"42","湖北","武汉"},{"43","湖南","长沙"},
        {"44","广东","广州 深圳 佛山 东莞"},{"45","广西","南宁"},
        {"46","海南",""},{"50","重庆","重庆"},{"51","四川","成都"},
        {"52","贵州","贵阳"},{"53","云南","昆明"},{"54","西藏",""},
        {"61","陕西","西安"},{"62","甘肃","兰州"},{"63","青海",""},
        {"64","宁夏",""},{"65","新疆","乌鲁木齐"},{"71","台湾",""},
        {"81","香港","香港"},{"82","澳门","澳门"}
    };
    private static final Map<String,String> CITY_PROVINCES = new LinkedHashMap<>();
    static {
        for (String[] region : NationalCities.PROVINCES) {
            String code = provinceCode(region[0]);
            for (String city : region[1].split(" ")) CITY_PROVINCES.put(city, code);
        }
        for (String[] region : AREAS) for (String city : region[2].split(" "))
            if (!city.isEmpty()) CITY_PROVINCES.put(city, region[0]);
        for (String[] region : AREAS)
            for (String city : PublicMetro.cities(region[0])) CITY_PROVINCES.put(city, region[0]);
        for (CityInfo city : CityInfo.PRD_CITIES) CITY_PROVINCES.put(city.name, "44");
    }

    static String normalizeCity(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("(?:\\s*(?:\\([^()]*\\)|（[^（）]*）))+\\s*$", "")
                .trim().replaceFirst("(特别行政区|特別行政區|市)$", "").replace("澳門", "澳门");
    }

    static String stripProvince(String value) {
        String query = BusSearch.normalize(value);
        return query.replaceFirst("^(?:广东|江苏|浙江|山东|四川|湖北|湖南|福建|河南|河北|安徽|辽宁|陕西|江西|云南|山西|黑龙江|吉林|贵州|甘肃|海南|青海|台湾)省", "")
                .replaceFirst("^(?:广西壮族|宁夏回族|新疆维吾尔|内蒙古|西藏)自治区", "").trim();
    }

    static CityInfo explicitCity(String query) {
        CityInfo best = null;
        for (String[] region : NationalCities.PROVINCES) for (String name : region[1].split(" ")) {
            if (!query.startsWith(name) || query.length() <= name.length()
                    || !query.substring(name.length()).matches("^[市\\s·:：/].*")) continue;
            if (best == null || name.length() > best.name.length()) {
                CityInfo known = CityInfo.find(name);
                best = known == null ? new CityInfo(name, 0, 0, "mg:" + name) : known;
            }
        }
        for (String name : new String[]{"北京", "上海", "天津", "重庆", "香港", "澳门"}) {
            if (query.startsWith(name) && query.length() > name.length()
                    && query.substring(name.length()).matches("^[市\\s·:：/].*")) {
                CityInfo known = CityInfo.find(name);
                return known == null ? new CityInfo(name, 0, 0, "mg:" + name) : known;
            }
        }
        return best;
    }

    static synchronized String province(String city) {
        String code = CITY_PROVINCES.get(normalizeCity(city));
        return code == null ? "" : code;
    }

    static synchronized void remember(String city, String code) {
        if ("香港".equals(normalizeCity(city)) || "澳门".equals(normalizeCity(city))) return;
        if (code != null && code.matches("[0-9]{2}") && !normalizeCity(city).isEmpty())
            CITY_PROVINCES.put(normalizeCity(city), code);
    }

    static String provinceCode(String name) {
        if (name == null) return "";
        for (String[] region : AREAS) if (name.startsWith(region[1])) return region[0];
        return "";
    }

    static String provinceName(String code) {
        for (String[] region : AREAS) if (region[0].equals(code)) return region[1];
        return "";
    }

    static String[] metroCities(String province) {
        return PublicMetro.cities(province);
    }

    static boolean accepts(String code, String adcode, String pcode, String district) {
        if (code.isEmpty()) return false;
        if (adcode != null && adcode.matches("[0-9]{6}") && !adcode.equals("000000"))
            return adcode.startsWith(code);
        if (pcode != null && pcode.matches("[0-9]{6}") && !pcode.equals("000000"))
            return pcode.startsWith(code);
        String name = provinceName(code);
        return district != null && !name.isEmpty() && district.startsWith(name);
    }
}
