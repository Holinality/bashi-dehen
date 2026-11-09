package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MetroTimesSelfTest {
    public static void main(String[] args) {
        require(!MetroTimes.validTime("--"), "invalid placeholder accepted");
        require(!MetroTimes.validTime("25:99"), "invalid clock accepted");
        require(MetroTimes.validTime("00:02"), "cross-midnight time rejected");

        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(
                "广州1号线", "#F3D03E", "", false,
                "06:00", "23:00", "06:00", "23:00");
        List<String> none = new ArrayList<>();
        line.stations.add(new MetroDataSource.Station(
                "起点", 23.0, 113.0, 1.0, 2, none, false, "",
                "06:00", "23:00", "06:04", "23:04"));
        line.stations.add(new MetroDataSource.Station(
                "中间站", 23.01, 113.01, 1.0, 2, none, false, "",
                "06:03", "23:03", "06:02", "23:02"));
        line.stations.add(new MetroDataSource.Station(
                "终点", 23.02, 113.02, 0, 0, none, false, "",
                "06:06", "23:06", "06:00", "23:00"));

        List<String> arrivals = MetroTimes.stationArrivals(
                line, line.stations, 1, true, 6 * 60 + 2, 3, 0.55);
        require(!arrivals.isEmpty(), "no station arrivals generated");
        require("06:03".equals(arrivals.get(0)),
                "station-level first train was not used as prediction anchor: " + arrivals);
        require(MetroTimes.intervalAt(23 * 60, "广州1号线")
                        > MetroTimes.intervalAt(8 * 60, "广州1号线"),
                "late-night headway should be wider than peak headway");

        System.out.println("MetroTimesSelfTest passed " + Arrays.toString(arrivals.toArray()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
