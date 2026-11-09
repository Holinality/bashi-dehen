package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MetroLineResolverSelfTest {
    public static void main(String[] args) {
        require(MetroLineResolver.interchangeLabel("10号线").equals("10号线"), "无城市前缀的换乘标签丢失线路号");
        require(MetroLineResolver.interchangeLabel("广州22号线").equals("22号线"), "城市前缀没有正确移除");
        require(MetroLineResolver.interchangeLabel("深圳2/8号线").equals("2/8号线"), "贯通线标签丢失线路号");
        require(MetroLineResolver.interchangeLabel("广佛线").equals("广佛线"), "广佛线被错误裁剪");
        MetroDataSource.MetroLine gz1 = line("广州1号线", "西塱", "体育西路", "广州东站");
        MetroDataSource.MetroLine sz1 = line("深圳1号线/罗宝线", "机场东", "车公庙", "罗湖");
        MetroDataSource.MetroLine sz28 = line("深圳2号线/8号线", "赤湾", "黄贝岭", "盐田路");
        MetroDataSource.MetroLine gf = line("广佛线", "新城东", "祖庙", "沥滘");
        List<MetroDataSource.MetroLine> all = Arrays.asList(gz1, sz1, sz28, gf);

        require(MetroLineResolver.best(all, "地铁1号线(西塱--广州东站)",
                "西塱站", "广州东站", "佛山") == gz1, "广州1号线被同号线误配");
        require(MetroLineResolver.best(all, "地铁2号线/8号线(往盐田路方向)",
                "黄贝岭", "盐田路", "深圳") == sz28, "深圳2/8号线别名映射失败");
        require(MetroLineResolver.best(all, "广佛线(新城东--沥滘)",
                "祖庙", "沥滘", "佛山") == gf, "广佛线映射失败");
        require(MetroLineResolver.stationIndex(gz1, "西朗地铁站") == 0,
                "西朗/西塱站名兼容失败");
        MetroDataSource.MetroLine combined = line("深圳2/8号线", "赤湾", "黄贝岭", "溪涌");
        List<MetroDataSource.MetroLine> compact = Arrays.asList(line("广州2号线", "广州南站", "嘉禾望岗"), combined);
        require(MetroLineResolver.best(compact, "地铁2号线", "", "", "深圳") == combined,
                "压缩贯通线名丢失2号线身份");
        require(MetroLineResolver.best(compact, "地铁8号线", "", "", "深圳") == combined,
                "压缩贯通线名丢失8号线身份");
        require(MetroPatterns.sameLineIdentity(combined.name, "地铁2号线"), "贯通线同线识别失败");
        List<MetroDataSource.MetroLine> sections = Arrays.asList(
                line("广州12号线（甲—乙）", "甲", "乙"), line("广州12号线（丙—丁）", "丙", "丁"));
        require(MetroLineResolver.best(sections, "广州12号线", "甲", "丁", "广州") == null,
                "两个未连通区段被匹配为同一趟车");
        System.out.println("MetroLineResolverSelfTest passed");
    }

    private static MetroDataSource.MetroLine line(String name, String... stops) {
        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(
                name, "#3E8FB8", "", false,
                "06:00", "23:00", "06:00", "23:00");
        for (String stop : stops) {
            line.stations.add(new MetroDataSource.Station(
                    stop, 23, 113, 1, 2, new ArrayList<String>(), false, "",
                    "06:00", "23:00", "06:00", "23:00"));
        }
        return line;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
