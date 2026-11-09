package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MetroPatternsSelfTest {
    public static void main(String[] args) {
        MetroDataSource.MetroLine south = line("广州3号线", "天河客运站", "体育西路", "市桥", "海傍");
        MetroDataSource.MetroLine north = line("广州3号线北延段", "体育西路", "嘉禾望岗", "机场北");
        List<MetroDataSource.MetroLine> original = Arrays.asList(south, north);
        List<MetroDataSource.MetroLine> normalized = MetroPatterns.normalize(original);
        MetroDataSource.MetroLine main = MetroPatterns.find(normalized, "广州3号线");
        MetroDataSource.MetroLine branch = MetroPatterns.find(normalized, "广州3号线支线");
        require(main != null && branch != null && normalized.size() == 2, "missing train patterns");
        require(names(main).equals(Arrays.asList("机场北", "嘉禾望岗", "体育西路", "市桥", "海傍")), "airport through order");
        require(names(branch).equals(names(south)), "Tianhe through order changed");
        require(main.networkId.equals(branch.networkId), "same physical line split into transfers");
        require(main.colour.equals(branch.colour), "line 3 colors diverged");
        require(main.lastTime.equals("23:10"), "airport departure reused junction last arrival");
        require(main.lastTimeR.equals("23:10"), "Haibang departure reused line-level last arrival");
        require(branch.lastTime.equals("23:00"), "Tianhe departure reused stale line-level time");
        require(MetroPatterns.find(normalized, "广州3号线北延段") == main, "old entry point no longer works");
        require(MetroLineResolver.best(normalized, "地铁3号线", "机场北", "海傍", "广州") == main, "through trip not resolved");
        require(MetroLineResolver.best(normalized, "地铁3号线", "天河客运站", "海傍", "广州") == branch, "branch trip not resolved");
        require(MetroLineResolver.best(normalized, "地铁3号线", "机场北", "天河客运站", "广州") == null, "invented direct branch-to-branch train");
        require(MetroPatterns.normalize(normalized).size() == 2, "normalization not idempotent");
        require(south.name.equals("广州3号线") && south.stations.size() == 4, "baseline mutated");
        require(main.stations.get(0).segMin == north.stations.get(1).segMin, "reversed segment time off by one");
        for (MetroDataSource.Station station : main.stations)
            require(!station.interchange.contains("广州3号线北延段"), "fake same-line interchange retained");
        require(MetroPatterns.normalize(Arrays.asList(south)).get(0) == south, "partial data discarded");

        ChelaileDataSource.GaodeLine remote = remote("地铁3号线(天河客运站--海傍)", "地铁线路",
                "海傍", "市桥", "新站", "体育西路", "天河客运站");
        MetroDataSource.MetroLine refreshed = MetroPatterns.preferRemote(south, Arrays.asList(remote));
        require(names(refreshed).equals(Arrays.asList("天河客运站", "体育西路", "新站", "市桥", "海傍")), "remote reverse order / new stop");
        require("高德查询".equals(refreshed.dataSource), "source attribution wrong");
        require(refreshed.stations.get(1).segMin == 0, "stale adjacency time reused across new stop");
        require(refreshed.stations.get(0).segMin == south.stations.get(0).segMin, "unchanged edge lost official minutes");
        require(refreshed.stations.get(0).first.equals(south.stations.get(0).first), "official timetable overwritten");
        require(MetroPatterns.preferRemote(south, Arrays.asList(remote("3路(天河客运站--海傍)", "普通公交",
                "天河客运站", "体育西路", "市桥", "海傍"))) == south, "bus route adopted as metro");
        require(MetroPatterns.preferRemote(south, Arrays.asList(remote("地铁3号线", "地铁线路", "另一个站", "海傍"))) == south,
                "partial query deleted baseline");
        MetroDataSource.MetroLine cropped = MetroPatterns.preferRemote(north, Arrays.asList(remote("地铁3号线", "地铁线路",
                "机场北(2号航站楼)", "嘉禾望岗", "体育西路", "市桥", "海傍")));
        require(names(cropped).equals(names(north)), "full through query corrupted northern topology");
        require(MetroPatterns.normalize(Arrays.asList(south, cropped)).size() == 2, "full highde query broke Y model");
        require(MetroPatterns.index(line("广州3号线北延段", "机场南", "机场北").stations,
                "机场南(T1)") == 0, "airport T1 alias lost station metadata");
        System.out.println("MetroPatternsSelfTest passed (27 scenarios + station assertions)");
    }

    private static List<String> names(MetroDataSource.MetroLine line) {
        List<String> names = new ArrayList<>();
        for (MetroDataSource.Station station : line.stations) names.add(station.name);
        return names;
    }

    private static MetroDataSource.MetroLine line(String name, String... names) {
        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(name, "#EE8200", "", false,
                "06:00", "22:00", "06:10", "22:10");
        for (int i = 0; i < names.length; i++) line.stations.add(new MetroDataSource.Station(names[i],
                23 + i * .01, 113, 1 + i, 2 + i, new ArrayList<>(Arrays.asList("广州3号线北延段")),
                names[i].equals("体育西路"), "", "06:00", "23:00", "06:10", "23:10"));
        return line;
    }

    private static ChelaileDataSource.GaodeLine remote(String name, String type, String... stops) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < stops.length; i++) points.add(new double[]{23 + .01 * i, 113});
        return new ChelaileDataSource.GaodeLine(name, stops[0], stops[stops.length - 1], "", "", "",
                Arrays.asList(stops), points, new ArrayList<>(), type);
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
