package com.holi.prdbus;

import java.util.*;

/** Offline regression of Huizhou's shared station ID and independent failure paths. */
public final class DirectionArrivalsSelfTest {
    static ChelaileDataSource.LineDetail line(String id, String physical) {
        ChelaileDataSource.LineStation st = new ChelaileDataSource.LineStation(
                6, "南门公园", "0752-753", 23.077446, 114.399666);
        st.physicalStId = physical;
        return new ChelaileDataSource.LineDetail(id, "12", 0, "起点", "终点", "", "", "",
                new ArrayList<>(), Arrays.asList(st), "");
    }
    static Map<String, List<ChelaileDataSource.Bus>> response(String id, int seconds) {
        Map<String, List<ChelaileDataSource.Bus>> map = new HashMap<>();
        map.put(id, Arrays.asList(new ChelaileDataSource.Bus("bus", seconds, 500, "", "", 0, 0, 0)));
        return map;
    }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        Map<String, List<ChelaileDataSource.Bus>> map = new HashMap<>();
        ChelaileDataSource.fillDirectionArrivals(line("forward", "platform-a"), "南门公园", map,
                (st, physical) -> {
                    check(physical && st.physicalStId.equals("platform-a"), "forward physical ID");
                    return response("forward", 390);
                });
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "platform-b"), "南门公园", map,
                (st, physical) -> {
                    check(physical && st.physicalStId.equals("platform-b"), "reverse physical ID");
                    return response("reverse", 108);
                });
        check(map.get("forward").get(0).travelTime == 390, "forward retained");
        check(map.get("reverse").get(0).travelTime == 108, "reverse independent");
        map.clear();
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> response("forward", 390));
        check(!map.containsKey("reverse"), "wrong direction rejected");
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> { if (physical) throw new Exception("offline"); return response("reverse",108); });
        check(map.get("reverse").get(0).travelTime == 108, "fallback after failure");
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> { throw new AssertionError("unnecessary query"); });
        map.clear();
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "其他站", map,
                (st, physical) -> { throw new AssertionError("wrong station queried"); });
        check(map.isEmpty(), "unmatched name stays unknown");
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> { Map<String,List<ChelaileDataSource.Bus>> empty = new HashMap<>();
                    empty.put("reverse", Collections.emptyList()); return empty; });
        check(map.containsKey("reverse") && map.get("reverse").isEmpty(), "empty distinct from failure");
        map.clear();
        Thread.currentThread().interrupt();
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> { throw new AssertionError("cancelled query"); });
        Thread.interrupted();
        check(map.isEmpty(), "cancel preserved");
        double[] coords = TransitRules.stationCoordinate(23.077446,114.399666,23.080704,114.411210);
        check(coords[0] == 23.077446 && coords[1] == 114.399666, "explicit WGS wins");
        coords = TransitRules.stationCoordinate(23.077446,0,23.08,114.4);
        check(coords[0] == 23.08 && coords[1] == 114.4, "never mix coordinate pairs");
        coords = TransitRules.stationCoordinate(Double.NaN,114,91,181);
        check(coords[0] == 0 && coords[1] == 0, "invalid coordinates rejected");
        map.clear();
        map.put("reverse", Arrays.asList(bus("a", 60), bus("b", -1), bus("c", -1)));
        final int[] calls = {0};
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> {
                    calls[0]++;
                    Map<String,List<ChelaileDataSource.Bus>> answer = new HashMap<>();
                    answer.put("reverse", physical
                        ? Arrays.asList(bus("a", 55), bus("b", 180), bus("c", -1))
                        : Arrays.asList(bus("c", 300), bus("b", -1)));
                    return answer;
                });
        check(calls[0] == 2, "partial physical response must continue fallback");
        check(map.get("reverse").size() == 3, "vehicles deduplicated");
        check(map.get("reverse").get(1).travelTime == 180, "second vehicle filled");
        check(map.get("reverse").get(2).travelTime == 300, "third vehicle filled despite reversed array");
        check(map.get("reverse").get(0).travelTime == 55, "first vehicle preserved");
        List<ChelaileDataSource.Bus> merged = ChelaileDataSource.mergeArrivalBuses(
                Arrays.asList(bus("a", 60), bus("b", -1)), Arrays.asList(bus("b", 120)));
        check(merged.size() == 2 && merged.get(1).travelTime == 120, "shorter response fills time");
        merged = ChelaileDataSource.mergeArrivalBuses(
                Arrays.asList(bus("", -1)), Arrays.asList(bus("", 120)));
        check(merged.size() == 1 && merged.get(0).travelTime == 120, "anonymous choose richer response");
        map.clear();
        map.put("reverse", Arrays.asList(bus("a", 60), bus("b", -1)));
        ChelaileDataSource.fillDirectionArrivals(line("reverse", "b"), "南门公园", map,
                (st, physical) -> { throw new Exception("offline"); });
        check(map.get("reverse").get(1).travelTime == -1, "failure never fabricates ETA");
        ChelaileDataSource.LineStation target = new ChelaileDataSource.LineStation(
                6, "南门公园", "0752-753", 23.077446, 114.399666);
        ChelaileDataSource.Bus lineA = fullBus("a", 55, 5);
        ChelaileDataSource.Bus lineB = fullBus("b", 180, 3);
        ChelaileDataSource.Bus passed = fullBus("passed", 20, 8);
        ChelaileDataSource.LineDetail detail = new ChelaileDataSource.LineDetail(
                "reverse", "12", 0, "起点", "终点", "", "", "",
                Arrays.asList(lineA, lineB, passed), Arrays.asList(target), "");
        map.clear();
        ChelaileDataSource.Bus stationA = bus("a", -1);
        stationA.stopsAway = 4;
        ChelaileDataSource.Bus stationB = bus("b", -1);
        stationB.stopsAway = 7;
        map.put("reverse", Arrays.asList(stationA, stationB));
        ChelaileDataSource.fillLineDetailArrivals(detail, "南门公园", map);
        check(map.get("reverse").size() == 2, "passed vehicle excluded");
        check(map.get("reverse").get(0).travelTime == 55, "line detail restores first ETA");
        check(map.get("reverse").get(1).travelTime == 180, "line detail restores later ETA");
        check(map.get("reverse").get(1).stopsAway == 7, "station stop count preserved");
        System.out.println("DirectionArrivalsSelfTest passed (25 scenarios)");
    }
    static ChelaileDataSource.Bus bus(String id, int seconds) {
        return new ChelaileDataSource.Bus(id, seconds, -1, "", "", 0, 0, 0);
    }
    static ChelaileDataSource.Bus fullBus(String id, int seconds, int order) {
        return new ChelaileDataSource.Bus(id, seconds, 500, "", "", order, 23.1, 114.4);
    }
}
