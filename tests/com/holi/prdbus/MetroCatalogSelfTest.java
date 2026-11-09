package com.holi.prdbus;
import java.util.*;

public final class MetroCatalogSelfTest {
    public static void main(String[] args) {
        MetroDataSource.MetroLine old = line("广州1号线", false, "甲", "旧站", "乙");
        List<MetroDataSource.MetroLine> changed = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁1号线(甲--乙)", 1, false, "甲", "新增站", "乙"),
                remote("地铁1号线(乙--甲)", 1, false, "乙", "新增站", "甲")), Arrays.asList(old));
        require(changed.size() == 1, "opposite directions duplicated");
        require(MetroPatterns.index(changed.get(0).stations, "新增站") == 1, "new station not adopted");
        require(MetroPatterns.index(changed.get(0).stations, "旧站") < 0, "deleted station reinserted");
        require(changed.get(0).stations.get(0).segMin == 0, "new adjacency reuses old travel time");
        require(changed.get(0).colour.equals(old.colour), "known line color lost");
        require(changed.get(0).stations.get(0).first.equals("06:20"), "bundled timetable lost");
        require(MetroCatalog.identity("广州", "佛山地铁2号线(广州南站--南庄)").equals("佛山2号线"), "cross-city route mislabeled");
        require(MetroCatalog.identity("杭州", "绍兴1号线").equals("绍兴1号线"), "foreign metro owner");
        require(MetroCatalog.identity("鄂州", "11号线").equals("武汉11号线"), "shared Wuhan line duplicated");
        require(MetroCatalog.identity("许昌", "郑许线").equals("郑州郑许线"), "shared Zhengxu line duplicated");
        require(MetroCatalog.identity("佛山", "7号线").equals("广州7号线"), "shared Guangzhou line duplicated");
        require(MetroCatalog.identity("广州", "地铁14号线支线(知识城线)").equals("广州14号线支线"), "knowledge city branch lost");
        require(MetroCatalog.identity("深圳", "地铁2号线(8号线)(溪涌--赤湾)").equals("深圳2/8号线"), "through-line provider alias lost");
        require(MetroCatalog.identity("深圳", "深圳2/8号线").equals("深圳2/8号线"), "through-line local identity lost");
        List<MetroDataSource.MetroLine> through = MetroCatalog.build("深圳", Arrays.asList(
                remote("地铁2号线(8号线)(甲--乙)", 1, false, "甲", "乙")), Arrays.asList(line("深圳2/8号线", false, "甲", "乙")));
        require(through.size() == 1 && through.get(0).networkId.equals("深圳2/8号线"), "through-line duplicated as local fallback");
        List<MetroDataSource.MetroLine> sections = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁12号线", 1, false, "甲", "乙"), remote("地铁12号线", 1, false, "丙", "丁")), Collections.emptyList());
        require(sections.size() == 2 && sections.get(0).networkId.equals(sections.get(1).networkId), "disconnected sections joined or split into wrong line");
        for (MetroDataSource.MetroLine section : sections) require(section.stations.size() == 2, "invented track between sections");
        List<MetroDataSource.MetroLine> active = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁1号线", 0, false, "甲", "乙"), remote("地铁2号线", 1, false, "丙", "丁")), Arrays.asList(old));
        require(active.size() == 1 && active.get(0).name.equals("广州2号线"), "closed line restored by fallback");
        List<MetroDataSource.MetroLine> fallback = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁2号线", 1, false, "丙", "丁")), Arrays.asList(old));
        require(fallback.contains(old), "absent alias treated as confirmed deletion");
        MetroDataSource.MetroLine ring = line("广州11号线", true, "甲", "乙", "丙", "丁");
        List<MetroDataSource.MetroLine> loops = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁11号线内环", 1, true, "丙", "丁", "甲", "乙", "丙"),
                remote("地铁11号线外环", 1, true, "乙", "甲", "丁", "丙", "乙")), Arrays.asList(ring));
        require(loops.size() == 1 && loops.get(0).stations.size() == 4, "rotated inner/outer ring not deduplicated");
        require(loops.get(0).stations.get(0).name.equals("甲") && loops.get(0).stations.get(1).name.equals("乙"), "ring orientation changed");
        List<MetroDataSource.MetroLine> newLoops = MetroCatalog.build("广州", Arrays.asList(
                remote("地铁11号线内环", 1, true, "丙", "丁", "甲", "乙", "丙"),
                remote("地铁11号线外环", 1, true, "乙", "甲", "丁", "丙", "乙")), Collections.emptyList());
        require(newLoops.size() == 1 && newLoops.get(0).stations.size() == 4, "new ring directions not canonicalized");
        try { MetroCatalog.build("广州", Collections.emptyList(), Arrays.asList(old)); throw new AssertionError("empty city accepted"); }
        catch (IllegalStateException expected) { }
        System.out.println("MetroCatalogSelfTest passed (19 scenarios + section assertions)");
    }
    private static MetroDataSource.MetroLine line(String name, boolean ring, String... names) {
        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(name, "#EE8200", "", ring, "06:00", "23:00", "06:00", "23:00");
        for (String stop : names) line.stations.add(new MetroDataSource.Station(stop, 23, 113, 1, 3, new ArrayList<>(), false, "", "06:20", "23:20", "06:10", "23:10"));
        return line;
    }
    private static ChelaileDataSource.GaodeLine remote(String name, int status, boolean ring, String... stops) {
        List<double[]> points = new ArrayList<>(); for (int i = 0; i < stops.length; i++) points.add(new double[]{23, 113});
        return new ChelaileDataSource.GaodeLine(name, stops[0], stops[stops.length - 1], "0600", "2300", "",
                Arrays.asList(stops), points, new ArrayList<>(), "地铁", status, ring);
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
