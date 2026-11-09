package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Operating station sequences. Through services may run on more than one line. */
final class RailCatalog {
    static final String GD = "44", SH = "31";

    static final class Line {
        final String region, name;
        final int color;
        final String[] stops;
        Line(String region, String name, int color, String stops) {
            this.region = region;
            this.name = name;
            this.color = color;
            this.stops = stops.split(" ");
        }
    }

    static final Line[] LINES = {
        new Line(GD, "广肇城际", 0xff3453a1,
                "肇庆 端州 鼎湖山 鼎湖东 四会 大旺 云东海 三水北 狮山北 狮山 佛山西 张槎 顺德北 北滘西 陈村 番禺"),
        new Line(GD, "广惠城际", 0xff117b68,
                "番禺 广州长隆 东环 官桥北 广州莲花山 麻涌 东莞西 道滘 西平西 东城南 寮步 松山湖北 大朗镇 常平南 常平东 樟木头东 银瓶 沥林北 陈江南 惠环 龙丰 西湖东 云山 小金口 惠州北"),
        new Line(GD, "广州东环城际", 0xffa05113,
                "番禺 大石东 广州大学城 琶洲 科韵路 岑村 龙洞 大源 帽峰山 竹料 白云机场东 白云机场南 白云机场北 花山镇 花城街 花都"),
        new Line(GD, "广清城际", 0xff6345a0,
                "广州白云 白云湖 江高 神山 花都 乐同 狮岭 银盏 龙塘镇 清城 燕湖 洲心 飞霞"),
        new Line(GD, "琶莲城际", 0xffaa3674, "琶洲 深井 化龙南 广州莲花山"),
        new Line(GD, "穗深城际", 0xff00789b,
                "竹料 钟落潭东 九佛 佛塱 新龙 镇龙 永宁北 永宁南 新塘南 中堂 望牛墩 东莞西 洪梅 东莞港 厚街 虎门北 虎门东 长安西 长安 沙井西 福海西 深圳机场北 深圳机场"),
        new Line(SH, "市域机场线", 0xff286c9e,
                "虹桥2号航站楼 中春路 景洪路 三林南 康桥东 上海国际旅游度假区 浦东1号2号航站楼"),
        new Line(SH, "金山铁路", 0xff147873, "上海南 莘庄 春申 新桥 车墩 叶榭 亭林 金山园区 金山卫")
    };

    static final class Transfer {
        final String region, rail, city, metro, lines;
        Transfer(String region, String rail, String city, String metro, String lines) {
            this.region = region;
            this.rail = rail;
            this.city = city;
            this.metro = metro;
            this.lines = lines;
        }
    }

    private static final Transfer[] TRANSFERS = {
        new Transfer(GD, "佛山西", "佛山", "佛山西站", "3号线"),
        new Transfer(GD, "张槎", "佛山", "张槎", "2号线"),
        new Transfer(GD, "顺德北", "佛山", "东平", "广佛线、3号线"),
        new Transfer(GD, "北滘西", "佛山", "北滘西站", "3号线"),
        new Transfer(GD, "陈村", "广州", "陈村", "7号线"),
        new Transfer(GD, "番禺", "广州", "广州南站", "2号线、7号线、22号线"),
        new Transfer(GD, "番禺", "佛山", "广州南站", "2号线"),
        new Transfer(GD, "广州长隆", "广州", "汉溪长隆", "3号线、7号线"),
        new Transfer(GD, "西平西", "东莞", "西平", "2号线"),
        new Transfer(GD, "东莞西", "东莞", "东莞西站", "1号线"),
        new Transfer(GD, "东城南", "东莞", "东城南站", "1号线"),
        new Transfer(GD, "琶洲", "广州", "琶洲", "8号线、11号线"),
        new Transfer(GD, "大石东", "广州", "大石", "3号线"),
        new Transfer(GD, "深井", "广州", "深井", "7号线"),
        new Transfer(GD, "科韵路", "广州", "科韵路", "5号线"),
        new Transfer(GD, "龙洞", "广州", "植物园", "6号线"),
        new Transfer(GD, "竹料", "广州", "竹料", "14号线"),
        new Transfer(GD, "白云机场北", "广州", "机场北", "3号线"),
        new Transfer(GD, "花都", "广州", "广州北站", "9号线"),
        new Transfer(GD, "广州白云", "广州", "广州白云站", "12号线"),
        new Transfer(GD, "广州白云", "广州", "石潭", "8号线"),
        new Transfer(GD, "镇龙", "广州", "镇龙", "14号线知识城支线、21号线"),
        new Transfer(GD, "新塘南", "广州", "新塘", "13号线"),
        new Transfer(GD, "虎门北", "东莞", "虎门火车站", "2号线"),
        new Transfer(GD, "福海西", "深圳", "福海西", "12号线"),
        new Transfer(GD, "深圳机场北", "深圳", "机场北", "11号线、20号线"),
        new Transfer(GD, "深圳机场", "深圳", "机场", "11号线"),
        new Transfer(SH, "虹桥2号航站楼", "上海", "虹桥2号航站楼", "2号线、10号线"),
        new Transfer(SH, "中春路", "上海", "中春路", "9号线"),
        new Transfer(SH, "景洪路", "上海", "景洪路", "15号线"),
        new Transfer(SH, "浦东1号2号航站楼", "上海", "浦东1号2号航站楼", "2号线、磁浮线"),
        new Transfer(SH, "上海南", "上海", "上海南站", "1号线、3号线、15号线"),
        new Transfer(SH, "莘庄", "上海", "莘庄", "1号线、5号线")
    };

    static String normalize(String name) {
        if (name == null) return "";
        return name.replaceAll("\\s+", "").replaceFirst("^(?:城际铁路|市域铁路|城际)", "")
                .replaceFirst("[（(][^）)]*[）)]$", "")
                .replaceFirst("(城际铁路站|城际车站|城际站|城轨站|火车站|铁路站|站)$", "").trim();
    }

    static String station(String region, String input) {
        String key = normalize(input);
        if (GD.equals(region) && key.equals("仲恺")) key = "陈江南";
        for (Line line : LINES) if (line.region.equals(region))
            for (String stop : line.stops) if (stop.equals(key)) return stop;
        return "";
    }

    static String cityOfStation(String region,String station) {
        if(SH.equals(region))return "上海";
        if(containsStation("肇庆 端州 鼎湖山 鼎湖东 四会 大旺",station))return "肇庆";
        if(containsStation("云东海 三水北 狮山北 狮山 佛山西 张槎 顺德北 北滘西 陈村",station))return "佛山";
        if(containsStation("麻涌 东莞西 道滘 西平西 东城南 寮步 松山湖北 大朗镇 常平南 常平东 樟木头东 银瓶 中堂 望牛墩 洪梅 东莞港 厚街 虎门北 虎门东 长安西 长安",station))return "东莞";
        if(containsStation("沥林北 陈江南 惠环 龙丰 西湖东 云山 小金口 惠州北",station))return "惠州";
        if(containsStation("银盏 龙塘镇 清城 燕湖 洲心 飞霞",station))return "清远";
        if(containsStation("沙井西 福海西 深圳机场北 深圳机场",station))return "深圳";
        return station(GD,station).isEmpty()?"":"广州";
    }
    private static boolean containsStation(String list,String station) {
        return (" "+list+" ").contains(" "+station+" ")&&!station.isEmpty();
    }

    static List<Line> search(String region, String input) {
        String key = normalize(input);
        List<Line> found = new ArrayList<>();
        for (Line line : LINES) {
            if (!line.region.equals(region)) continue;
            if (key.isEmpty() || line.name.contains(key) || matchesLine(line, key)) {
                found.add(line);
                continue;
            }
            for (String stop : line.stops) {
                if (stop.contains(key)) {
                    found.add(line);
                    break;
                }
            }
        }
        return found;
    }

    static String regionForLine(String name) {
        String key = TransitRules.routeIdentity(name);
        if (key.contains("接驳") || key.contains("公交") || key.contains("巴士")) return "";
        if (key.matches("广佛环线(?:南环|东环|北环|西环)?(?:段|铁路)?")
                || key.equals("广佛南环") || key.equals("广佛东环")) return GD;
        for (Line line : LINES) if (matchesLine(line, key)) return line.region;
        return key.equals("广东城际") || key.equals("广东城际铁路") ? GD
                : key.equals("上海市域铁路") ? SH : "";
    }

    /** A train number alone is national. Both stops must belong to the same
     * regional catalog before it can open a regional journey. */
    static String regionForTrip(String name, String from, String to, String cityHint) {
        String region = regionForLine(name);
        if (!region.isEmpty()) return tripProvinceMatches(region, cityHint) ? region : "";
        String number = TransitRules.routeIdentity(name).toUpperCase(java.util.Locale.ROOT)
                .replaceFirst("(?:次|次列车)$", "");
        boolean regionalLabel = number.contains("城际") || number.contains("城轨") || number.contains("市域");
        if (number.contains("接驳") || number.contains("公交") || number.contains("巴士")
                || number.contains("专线") || number.contains("快线")) return "";
        if (!number.matches("[CS][0-9]{1,5}") && !regionalLabel) return "";
        String found = "";
        for (String candidate : new String[]{GD, SH}) {
            String start = station(candidate, from), end = station(candidate, to);
            if (start.isEmpty() || end.isEmpty() || start.equals(end)
                    || !tripProvinceMatches(candidate, cityHint)) continue;
            if (!found.isEmpty()) return "";
            found = candidate;
        }
        return found;
    }

    private static boolean tripProvinceMatches(String region, String cityHint) {
        String hint = cityHint == null ? "" : cityHint.trim();
        if (hint.isEmpty()) return true;
        if (hint.equals(region) || hint.equals(region + "0000")) return true;
        if (hint.matches("[0-9]{6}") && !hint.startsWith(region)) return false;
        String[] cities = {"广州", "深圳", "佛山", "东莞", "惠州", "肇庆", "清远", "上海"};
        String[] adcodes = {"4401", "4403", "4406", "4419", "4413", "4412", "4418", "31"};
        String[] telephone = {"020", "0755", "0757", "0769", "0752", "0758", "0763", "021"};
        for (int i = 0; i < cities.length; i++) {
            if (hint.contains(cities[i]) || hint.equals(telephone[i])
                    || hint.matches("[0-9]{4,6}") && hint.startsWith(adcodes[i]))
                return (i == cities.length - 1 ? SH : GD).equals(region);
        }
        if (hint.contains("广东")) return GD.equals(region);
        String province = Regions.province(hint);
        if (province.isEmpty()) province = Regions.provinceCode(hint);
        if (!province.isEmpty()) return region.equals(province);
        if (hint.matches("0[0-9]{2,3}")) return false;
        if (hint.matches("[0-9]{4,6}")) return hint.startsWith(region);
        return true;
    }

    private static boolean matchesLine(Line line, String key) {
        return key.equals(line.name) || key.startsWith(line.name + "铁路")
                || key.startsWith(line.name + "(") || key.startsWith(line.name + "（")
                || key.startsWith(line.name + "线")
                || (line.name.equals("穗深城际") && (key.startsWith("穗莞深城际")
                    || key.equals("新白广城际") || key.equals("新白广城际铁路")))
                || (line.name.equals("广州东环城际") && (key.startsWith("广州东环线")
                    || key.startsWith("广佛东环城际") || key.startsWith("广佛环线东环")))
                || (line.name.equals("广肇城际") && (key.startsWith("广佛肇城际") || key.startsWith("佛肇城际")))
                || (line.name.equals("广惠城际") && (key.startsWith("莞惠城际") || key.startsWith("佛莞城际")))
                || (line.name.equals("市域机场线") && (key.startsWith("机场联络线")
                    || key.startsWith("上海市域机场线") || key.startsWith("市域铁路机场联络线")));
    }

    static List<Transfer> fromRail(String region, String station) {
        List<Transfer> found = new ArrayList<>();
        for (Transfer link : TRANSFERS)
            if (link.region.equals(region) && link.rail.equals(station)) found.add(link);
        return found;
    }

    static boolean boardingAllowed(String region,String station) {
        // Operator closure from 2026-05-07; retain physical map geometry, exclude boarding.
        return !(GD.equals(region) && "白云机场南".equals(station));
    }

    private static final Map<String, Set<String>> GD_TRACKS = guangdongTracks();

    private static Map<String, Set<String>> guangdongTracks() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (Line line : LINES) if (GD.equals(line.region)) {
            String previous=null;
            for (String stop:line.stops) {
                if (!boardingAllowed(GD,stop)) continue;
                if (previous!=null) link(graph,previous,stop);
                previous=stop;
            }
        }
        // 东莞西联络线 allows through trains to bypass the 东莞西 platform.
        link(graph, "麻涌", "洪梅");
        return graph;
    }

    private static void link(Map<String, Set<String>> graph, String a, String b) {
        graph.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
        graph.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
    }

    static String serviceForInterval(String from, String to, int reportedIntervals) {
        if (reportedIntervals < 1) return "停站待核对";
        int[] range = {Integer.MAX_VALUE, 0};
        if (GD_TRACKS.containsKey(from) && GD_TRACKS.containsKey(to))
            stopIntervalRange(from, to, 0, new HashSet<>(), range);
        if (reportedIntervals < range[0] && range[1] > 0) return "区间快车";
        if (reportedIntervals == range[0] && reportedIntervals == range[1]) return "区间站站停";
        return "停站待核对";
    }

    static String serviceForStops(List<String> stops) {
        if (stops.size() < 2) return "停站待核对";
        boolean express = false;
        for (int i = 1; i < stops.size(); i++) {
            String from = station(GD, stops.get(i - 1)), to = station(GD, stops.get(i));
            if (from.isEmpty() || to.isEmpty() || from.equals(to)
                    || !GD_TRACKS.containsKey(from) || !GD_TRACKS.containsKey(to)) return "停站待核对";
            int[] range = {Integer.MAX_VALUE, 0};
            stopIntervalRange(from, to, 0, new HashSet<>(), range);
            if (range[1] == 0) return "停站待核对";
            if (range[0] > 1) express = true;
        }
        if (!express) return "站站停";
        // Full passenger stopping tables only. A short queried interval is not enough
        // to distinguish an express train from a limited express train.
        // Operator pattern: 2025-09-29, amended 2026-07-21 (东莞西 added).
        Set<String> limited = new HashSet<>(java.util.Arrays.asList(
                "清城 花都 白云机场北 白云机场南 白云机场东 竹料 琶洲 番禺 广州莲花山 佛山西 肇庆 东莞西 西平西 云山 惠州北".split(" ")));
        boolean allLimited = true, distinctive = false;
        for (String stop : stops) if (!limited.contains(station(GD, stop))) allLimited = false;
        for (int i = 1; i < stops.size(); i++) {
            String a = station(GD, stops.get(i - 1)), b = station(GD, stops.get(i));
            // These skips bypass mandatory major express stops on unambiguous corridors.
            if (pair(a,b,"西平西","云山") || pair(a,b,"佛山西","肇庆")) distinctive = true;
        }
        return allLimited && distinctive ? "特快" : "大站快车";
    }

    private static boolean pair(String a, String b, String c, String d) {
        return (a.equals(c) && b.equals(d)) || (a.equals(d) && b.equals(c));
    }

    static boolean matchesService(String service, String filter) {
        return "全部".equals(filter) || filter.equals(service)
                || ("快车".equals(filter) && "大站快车".equals(service));
    }

    private static void stopIntervalRange(String current, String target, int intervals,
                                          Set<String> visited, int[] range) {
        if (current.equals(target)) {
            range[0] = Math.min(range[0], intervals);
            range[1] = Math.max(range[1], intervals);
            return;
        }
        visited.add(current);
        for (String next : GD_TRACKS.get(current))
            if (!visited.contains(next)) stopIntervalRange(next, target, intervals + 1, visited, range);
        visited.remove(current);
    }

    static List<Transfer> fromMetro(String city, String station) {
        if (city == null || station == null) return Collections.emptyList();
        List<Transfer> found = new ArrayList<>();
        for (Transfer link : TRANSFERS) {
            boolean cityMatch = link.city.equals(city);
            if (cityMatch && normalize(link.metro).equals(normalize(station))) found.add(link);
        }
        return found;
    }

    static List<Transfer> fromMetroLine(MetroDataSource.MetroLine line, String station) {
        if (line == null) return Collections.emptyList();
        String network = line.networkId;
        boolean foshanSection = "广佛线".equals(network) && "东平".equals(normalize(station));
        return fromMetro(foshanSection ? "佛山" : MetroDataSource.cityOfLine(line), station);
    }

    private RailCatalog() { }
}
