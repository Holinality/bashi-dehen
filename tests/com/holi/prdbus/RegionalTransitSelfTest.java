package com.holi.prdbus;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

public final class RegionalTransitSelfTest {
    public static void main(String[] args) {
        require(RailCatalog.search("44", "").size() == 6, "Guangdong regional rail inventory");
        require(RailCatalog.search("31", "").size() == 2, "Shanghai regional rail inventory");
        require(RailCatalog.station("44", "广州南站").isEmpty(), "Guangzhou South is not Panyu station");
        require("番禺".equals(RailCatalog.station("44", "番禺城际站")), "intercity suffix");
        require("惠州北".equals(RailCatalog.station("44", " 惠州北火车站 ")), "regional rail station suffix");
        require("44".equals(RailCatalog.regionForTrip("S4782", "惠州北站", "番禺城际站", "441302")),
                "Guangdong S express was treated as ordinary railway");
        require("44".equals(RailCatalog.regionForTrip("C4742", "番禺", "惠州北", "020")),
                "Guangdong C train region");
        require("31".equals(RailCatalog.regionForTrip("S1601", "上海南站", "金山卫站", "310104")),
                "Shanghai suburban train region");
        require(RailCatalog.regionForTrip("S4782", "惠州北", "番禺", "310104").isEmpty(),
                "foreign province metadata accepted");
        require(RailCatalog.regionForTrip("S4782", "惠州北", "番禺", "北京").isEmpty(),
                "foreign named city accepted");
        require("44".equals(RailCatalog.regionForTrip("S4782", "惠州北", "番禺", "440106")),
                "cross-city regional service rejected its provider city");
        require("44".equals(RailCatalog.regionForTrip("S4782(惠州北--番禺)", "惠州北", "番禺", "020")),
                "regional train with direction suffix rejected");
        require("44".equals(RailCatalog.regionForLine("广佛环线(番禺--花都)")),
                "Guangfo regional ring treated as metro");
        require(RailCatalog.regionForLine("广惠城际接驳巴士").isEmpty(),
                "regional feeder bus treated as railway");
        require("44".equals(RailCatalog.regionForTrip("城际铁路", "西平西站", "寮步站", "0769")),
                "generic regional provider label rejected a known interval");
        require(RailCatalog.regionForTrip("城际快线1路", "西平西", "寮步", "0769").isEmpty(),
                "regional bus label accepted as railway");
        require(RailCatalog.regionForTrip("C4742", "广州南站", "惠州北", "").isEmpty(),
                "nearby Guangzhou South substituted for Panyu");
        require(RailCatalog.regionForTrip("C4742", "未知站", "番禺", "").isEmpty(),
                "unknown boarding stop guessed");
        require(RailCatalog.regionForTrip("G123", "番禺", "惠州北", "").isEmpty(),
                "high-speed train treated as regional");
        require(RailCatalog.regionForLine("公交1路(城际站)").isEmpty(), "a bus became a train");
        require(RailCatalog.fromMetro("广州", "广州南站").size() == 1, "regional transfer missing");
        require(RailCatalog.fromMetro("上海", "广州南站").isEmpty(), "cross-city transfer");
        require("区间站站停".equals(RailCatalog.serviceForInterval("西平西", "寮步", 2)), "all-stop interval");
        require("区间快车".equals(RailCatalog.serviceForInterval("西平西", "寮步", 1)), "express interval");
        require("大站快车".equals(RailCatalog.serviceForStops(Arrays.asList("番禺", "广州长隆", "东莞西", "西平西", "松山湖北", "常平南", "陈江南", "西湖东", "云山", "惠州北"))), "express stopping pattern");
        require("特快".equals(RailCatalog.serviceForStops(Arrays.asList("番禺", "东莞西", "西平西", "云山", "惠州北"))), "limited express stopping pattern");
        require("特快".equals(RailCatalog.serviceForStops(Arrays.asList("惠州北", "云山", "西平西", "东莞西", "番禺"))), "reverse limited express");
        require(!"特快".equals(RailCatalog.serviceForStops(Arrays.asList("花都", "白云机场北"))), "short interval cannot prove limited express");
        require(RailCatalog.matchesService("大站快车","快车"),"express filter");
        require(!RailCatalog.matchesService("区间快车","快车"),"unverified interval cannot be filtered as confirmed express");
        require(!RailCatalog.matchesService("特快","快车"),"limited express lost in express filter");
        require(RailCatalog.fromRail("44","番禺").size()==2,"Guangzhou and Foshan transfers must be separate");
        require("广州".equals(RailCatalog.fromRail("44","陈村").get(0).city),"line 7 ownership");
        require(!RailCatalog.boardingAllowed("44","白云机场南"),"closed airport T1 boarding");
        require("站站停".equals(RailCatalog.serviceForStops(Arrays.asList("白云机场东","白云机场北","花山镇"))),"closed station caused false express classification");
        require("站站停".equals(RailCatalog.serviceForStops(Arrays.asList("西平西", "东城南", "寮步"))), "all-stop pattern");
        require("停站待核对".equals(RailCatalog.serviceForStops(Arrays.asList("西平西", "未知站"))), "unknown stop guessed");
        require("深圳坪山云巴1号线".equals(MetroCatalog.identity("深圳", "坪山云巴1号线")),
                "Pingshan guideway conflated with Shenzhen line 1");

        Calendar date = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        date.clear();
        date.set(2026, Calendar.NOVEMBER, 5, 23, 0);
        String[] fields = new String[18];
        Arrays.fill(fields, "");
        fields[3] = "C4723";
        fields[6] = "PYA";
        fields[7] = "HUA";
        fields[8] = "23:55";
        fields[9] = "00:25";
        fields[10] = "00:30";
        String row = String.join("|", fields);
        RailTimetable.Train train = RailTimetable.parse(row, date, "PYA", "HUA", "番禺", "惠州北", null);
        require(train != null && train.arrival - train.departure == 30 * 60_000, "midnight crossing");
        require(train.label(date.getTimeInMillis()).contains("11月6日 00:25"), "arrival date not shown");
        require(RailTimetable.parse(row, date, "HUA", "PYA", "惠州北", "番禺", null) == null, "wrong direction");
        fields[3]="S4755";
        require(RailTimetable.parse(String.join("|",fields),date,"PYA","HUA","番禺","惠州北",null)!=null,
                "S-prefixed Guangdong express service was discarded");
        fields[3] = "G123";
        require(RailTimetable.parse(String.join("|", fields), date, "PYA", "HUA", "番禺", "惠州北", null) == null,
                "regional ticket query included high-speed rail");
        long now = date.getTimeInMillis();
        require(RailTimetable.readyAt(Arrays.asList(
                new ChelaileDataSource.TransitSegment("walk", "", "", "", 225, -1, -1),
                new ChelaileDataSource.TransitSegment("bus", "", "", "", 0, -1, 12)), 2, now)
                == now + 30 * 60_000L, "preceding travel and boarding buffer");
        require(RailTimetable.readyAt(Arrays.asList(
                new ChelaileDataSource.TransitSegment("railway", "S4782", "惠州北", "番禺", 0, 5, 60)),
                1, now) == now + 90 * 60_000L, "cached regional rail transfer buffer");
        require(RegionalBusData.parseEta("2026-11-05T23:00:00+08:00") == now, "HK ETA timezone");
        require(RegionalBusData.parseEta("2026-13-05T23:00:00+08:00") < 0, "invalid provider time");
        require("TKO".equals(MtrArrivals.stationCode("将军澳站")), "MTR station binding");
        require("leftTicket/queryG".equals(RailTimetable.queryVariant("queryG?leftTicketDTO.from_station=XXX")),
                "12306 relative query variant");
        require("leftTicket/queryA".equals(RailTimetable.queryVariant("/otn/leftTicket/queryA")), "12306 absolute path");
        require(RailTimetable.queryVariant("https://invalid.example/otn/leftTicket/queryG").isEmpty(), "external redirect accepted");
        checkMetroNetwork();
        System.out.println("RegionalTransitSelfTest passed");
    }

    private static void checkMetroNetwork() {
        require(MetroNetwork.Area.forCity("佛山市") == MetroNetwork.Area.GUANGFO, "Foshan must share the Guangfo map");
        require(MetroNetwork.Area.forCity("东莞") == null, "map scope was widened");
        MetroDataSource.MetroLine guangzhou = line("广州7号线", false,
                stop("广州南站", 23.0, 113.27), stop("陈村", 23.0, 113.22));
        MetroDataSource.MetroLine foshan = line("佛山3号线", false,
                stop("陈村站", 23.0002, 113.22), stop("大良", 22.95, 113.22));
        MetroDataSource.MetroLine separate = line("佛山2号线", false,
                stop("大良", 23.20, 113.12), stop("另一站", 23.23, 113.12));
        MetroDataSource.MetroLine shenzhen = line("深圳1号线", false,
                stop("机场东", 22.64, 113.82), stop("后瑞", 22.61, 113.82));
        MetroNetwork network = new MetroNetwork(MetroNetwork.Area.GUANGFO,
                Arrays.asList(guangzhou, foshan, separate, shenzhen));
        require(network.search("机场东").isEmpty(), "Shenzhen was mixed into Guangfo");
        require(network.search("陈村").size() == 1, "same physical hub was duplicated");
        require(network.search("大良").size() == 2, "distant namesakes were merged");
        MetroNetwork.Stop from = network.search("广州南").get(0);
        MetroNetwork.Stop to = network.nearest(22.95, 113.22, 2);
        MetroNetwork.Journey trip = network.plan(from, to);
        require(trip != null && trip.plan.segments.size() == 3 && trip.plan.durationMin == 8,
                "Guangfo route did not account for the transfer");
        require(trip.directions[0] == 0 && trip.directions[2] == 0, "forward direction lost");
        require(network.plan(to, from).directions[0] == 1, "reverse direction lost");
        require(network.plan(from, network.search("另一站").get(0)) == null, "crossed disconnected namesake");
        ChelaileDataSource.TransitSegment amapRide = new ChelaileDataSource.TransitSegment(
                "metro", "地铁7号线", "广州南站", "陈村", 0, 1, 3);
        MetroNetwork.Journey online = network.matchOnline(
                new ChelaileDataSource.TransitPlan(3, "2.0", 0, Arrays.asList(amapRide)), "广州");
        require(online != null && online.tracks.size() == 1 && online.tracks.get(0).size() == 2,
                "Amap metro route could not be drawn using local stations");
        ChelaileDataSource.TransitSegment bus = new ChelaileDataSource.TransitSegment(
                "bus", "公交7路", "广州南站", "陈村", 0, 1, 3);
        require(network.matchOnline(new ChelaileDataSource.TransitPlan(
                3, "", 0, Arrays.asList(bus)), "广州") == null, "bus route shown on metro tracks");
    }

    private static MetroDataSource.MetroLine line(String name, boolean ring, MetroDataSource.Station... stations) {
        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(name, "2B71BC", "", ring,
                "06:00", "23:00", "06:00", "23:00");
        line.stations.addAll(Arrays.asList(stations));
        return line;
    }

    private static MetroDataSource.Station stop(String name, double lat, double lon) {
        return new MetroDataSource.Station(name, lat, lon, 1, 2,
                java.util.Collections.emptyList(), false, "", "", "", "", "");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
