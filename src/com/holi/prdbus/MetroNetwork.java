package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Shared station identity, geographic diagram geometry and offline metro routes. */
final class MetroNetwork {
    enum Area {
        BEIJING("北京", "11", "https://map.bjsubway.com/mobile", "北京"),
        SHANGHAI("上海", "31", "https://www.shhk.gov.cn/xwzx/002008/002008040/20240922/5bca6d54-4f4d-4a6f-b307-e93a5f31bd6f.html", "上海"),
        GUANGFO("广佛", "44", "https://www.huadu.gov.cn/gzhdjt/img/1/1542/1542142/10586818.jpg", "广州", "佛山"),
        SHENZHEN("深圳", "44", "https://www.szmc.net/shentieyunying/yunyingfuwu/szsgdjtyyxlwlt/", "深圳");

        final String label, province, official;
        final List<String> cities;
        Area(String label, String province, String official, String... cities) {
            this.label = label;
            this.province = province;
            this.official = official;
            this.cities = Arrays.asList(cities);
        }
        static Area forCity(String city) {
            for (Area area : values()) if (area.cities.contains(Regions.normalizeCity(city))) return area;
            return null;
        }
    }

    static final class Stop {
        final String key, name, city;
        final double lat, lon;
        final List<Node> services = new ArrayList<>();

        Stop(String key, String name, String city, double lat, double lon) {
            this.key = key;
            this.name = name;
            this.city = city;
            this.lat = lat;
            this.lon = lon;
        }

        boolean transfer() {
            String network = services.get(0).line.networkId;
            for (Node node : services) if (!network.equals(node.line.networkId)) return true;
            return false;
        }
    }

    static final class Node {
        final MetroDataSource.MetroLine line;
        final MetroDataSource.Station station;
        final Stop stop;
        final List<Edge> edges = new ArrayList<>(4);

        Node(MetroDataSource.MetroLine line, MetroDataSource.Station station, Stop stop) {
            this.line = line;
            this.station = station;
            this.stop = stop;
        }
    }

    private static final class Edge {
        final Node from, to;
        final int minutes, direction;
        Edge(Node from, Node to, int minutes, int direction) {
            this.from = from;
            this.to = to;
            this.minutes = minutes;
            this.direction = direction;
        }
    }

    enum Preference { FASTEST, FEWER_TRANSFERS }

    private static final class Step implements Comparable<Step> {
        final Node node;
        final int minutes,transfers,direction,waiting;
        final Step previous;
        final Edge via;
        Step(Node node,int minutes,int transfers,int direction,Step previous,Edge via,int waiting) {
            this.node=node;this.minutes=minutes;this.transfers=transfers;this.direction=direction;
            this.previous=previous;this.via=via;this.waiting=waiting;
        }
        @Override public int compareTo(Step other) {
            int time=Integer.compare(minutes,other.minutes);
            return time==0?Integer.compare(transfers,other.transfers):time;
        }
    }

    static final class Journey {
        final ChelaileDataSource.TransitPlan plan;
        final int[] directions;
        final List<List<Node>> tracks;
        Journey(ChelaileDataSource.TransitPlan plan, int[] directions, List<List<Node>> tracks) {
            this.plan = plan;
            this.directions = directions;
            this.tracks = tracks;
        }
    }

    final List<Stop> stops = new ArrayList<>();
    final Map<MetroDataSource.MetroLine, List<Node>> lines = new LinkedHashMap<>();
    private final Map<String, List<Stop>> byName = new HashMap<>();
    private final Map<String, Stop> byKey = new HashMap<>();
    private final Map<MetroDataSource.MetroLine,MetroSchedule[]> schedules=new java.util.IdentityHashMap<>();

    MetroNetwork(Area area, List<MetroDataSource.MetroLine> source) {
        for (MetroDataSource.MetroLine line : source) {
            if (line.networkId.contains("有轨电车") || line.networkId.contains("云巴")) continue;
            String city = MetroDataSource.cityOfLine(line);
            if (!area.cities.contains(city) || line.stations.size() < 2) continue;
            List<Node> ordered = new ArrayList<>(line.stations.size());
            lines.put(line, ordered);
            double speed = MetroTimes.speedKmPerMin(line.name);
            for (int i = 0; i < line.stations.size(); i++) {
                MetroDataSource.Station station = line.stations.get(i);
                if (!TransitRules.validCoordinate(station.lat, station.lon)) {
                    ordered.add(null);
                    continue;
                }
                double[] wgs = Coord.gcjToWgs(station.lat, station.lon);
                Stop stop = stopFor(station.name, stationCity(line, city, i), wgs[0], wgs[1]);
                Node node = new Node(line, station, stop);
                stop.services.add(node);
                if (!ordered.isEmpty() && ordered.get(i - 1) != null) {
                    Node previous = ordered.get(i - 1);
                    addRide(previous, node, MetroTimes.travelMinutes(previous.station, station, true, speed), 0);
                    addRide(node, previous, MetroTimes.travelMinutes(station, previous.station, false, speed), 1);
                }
                ordered.add(node);
            }
            Node first = ordered.get(0), last = ordered.get(ordered.size() - 1);
            if (line.ring && first != null && last != null && first.stop != last.stop) {
                int minutes = Math.max(1, (int) Math.round(GeoUtils.distanceKm(
                        first.stop.lat, first.stop.lon, last.stop.lat, last.stop.lon) / speed + 0.5));
                addRide(last, first, minutes, 0);
                addRide(first, last, minutes, 1);
            }
        }
        for (Stop stop : stops) {
            for (Node from : stop.services) for (Node to : stop.services) {
                if (from.line != to.line) from.edges.add(new Edge(from, to, 4, -1));
            }
        }
    }

    private static String stationCity(MetroDataSource.MetroLine line, String city, int index) {
        String station = normalize(line.stations.get(index).name);
        if ("佛山2号线".equals(line.networkId) && "广州南".equals(station)) return "广州";
        if ("广佛线".equals(line.networkId) || "广州7号线".equals(line.networkId)) {
            boolean guangfo = "广佛线".equals(line.networkId);
            int border = MetroPatterns.index(line.stations, guangfo ? "龙溪" : "广州南站");
            int foshan = MetroPatterns.index(line.stations, guangfo ? "金融高新区" : "美的大道");
            if (border >= 0 && foshan >= 0 && (foshan < border ? index < border : index > border)) return "佛山";
        }
        return city;
    }

    private Stop stopFor(String name, String city, double lat, double lon) {
        String normalized = normalize(name);
        List<Stop> named = byName.get(normalized);
        if (named == null) {
            named = new ArrayList<>();
            byName.put(normalized, named);
        }
        for (Stop stop : named)
            if (GeoUtils.distanceKm(lat, lon, stop.lat, stop.lon) < .60) return stop;
        String key = normalized + "/" + Math.round(lat * 10000) + "/" + Math.round(lon * 10000);
        Stop stop = new Stop(key, name, city, lat, lon);
        named.add(stop);
        stops.add(stop);
        byKey.put(key, stop);
        return stop;
    }

    Stop byKey(String key) { return byKey.get(key); }

    List<Stop> search(String query) {
        String wanted = normalize(query);
        List<Stop> matches = new ArrayList<>();
        if (wanted.isEmpty()) return matches;
        for (Stop stop : stops) if (normalize(stop.name).contains(wanted)) matches.add(stop);
        Collections.sort(matches, (a, b) -> {
            int exact = Boolean.compare(normalize(b.name).equals(wanted), normalize(a.name).equals(wanted));
            return exact == 0 ? a.name.compareTo(b.name) : exact;
        });
        return matches;
    }

    Stop nearest(double lat, double lon, double maximumKm) {
        if (!TransitRules.validCoordinate(lat, lon)) return null;
        Stop nearest = null;
        for (Stop stop : stops) {
            double distance = GeoUtils.distanceKm(lat, lon, stop.lat, stop.lon);
            if (distance < maximumKm) {
                maximumKm = distance;
                nearest = stop;
            }
        }
        return nearest;
    }

    Journey plan(Stop origin, Stop destination) {
        return plan(origin,destination,Preference.FASTEST,-1);
    }

    /** Pareto labels preserve both time and transfers at each direction state.
     * Unlike one cheapest label per station, a slightly slower, lower-transfer
     * arrival is not thrown away before the destination is reached.
     * now < 0 gives deterministic topology-only estimates for legacy callers.
     */
    Journey plan(Stop origin,Stop destination,Preference preference,long now) {
        if (origin == null || destination == null || origin == destination) return null;
        Map<Node,List<Step>> best=new java.util.IdentityHashMap<>();
        PriorityQueue<Step> queue = new PriorityQueue<>();
        for (Node node : origin.services) {
            Step first=new Step(node,0,0,-1,null,null,0);
            queue.add(first);List<Step> labels=new ArrayList<>();labels.add(first);best.put(node,labels);
        }
        Step chosen=null;
        java.util.Calendar clock=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        if(now>=0)clock.setTimeInMillis(now);
        double minute=clock.get(java.util.Calendar.HOUR_OF_DAY)*60+clock.get(java.util.Calendar.MINUTE)+clock.get(java.util.Calendar.SECOND)/60.0;
        while (!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())return null;
            Step step = queue.remove();
            if(!best.get(step.node).contains(step))continue;
            if(step.node.stop==destination) {
                if(chosen==null||better(step,chosen,preference))chosen=step;
                continue;
            }
            for (Edge edge : step.node.edges) {
                boolean transfer=edge.direction<0;
                if(transfer&&(step.previous==null||(step.via!=null&&step.via.direction<0)))continue;
                if(!transfer&&step.direction>=0&&step.direction!=edge.direction)continue;
                int transfers=step.transfers+(transfer?1:0);
                if(transfers>8)continue;
                int waiting=now<0||transfer||step.direction>=0?0:boardingWait(step.node,edge.direction,minute+step.minutes);
                int minutes=step.minutes+edge.minutes+waiting;
                Step candidate=new Step(edge.to,minutes,transfers,edge.direction,step,edge,waiting);
                List<Step> labels=best.get(edge.to);
                if(labels==null){labels=new ArrayList<>();best.put(edge.to,labels);}
                boolean dominated=false;
                for(Step known:labels)if(known.direction==candidate.direction&&known.minutes<=minutes&&known.transfers<=transfers){dominated=true;break;}
                if(dominated)continue;
                for(int i=labels.size()-1;i>=0;i--){Step old=labels.get(i);if(old.direction==candidate.direction&&minutes<=old.minutes&&transfers<=old.transfers)labels.remove(i);}
                labels.add(candidate);queue.add(candidate);
            }
        }
        return chosen==null?null:journey(chosen);
    }

    private static boolean better(Step a,Step b,Preference preference) {
        if(preference==Preference.FEWER_TRANSFERS&&a.transfers!=b.transfers)return a.transfers<b.transfers;
        return a.minutes==b.minutes?a.transfers<b.transfers:a.minutes<b.minutes;
    }

    private int boardingWait(Node node,int direction,double minute) {
        MetroSchedule[] pair=schedules.get(node.line);
        if(pair==null) {
            double speed=MetroTimes.speedKmPerMin(node.line.name);
            pair=new MetroSchedule[]{new MetroSchedule(node.line,node.line.stations,true,speed),
                    new MetroSchedule(node.line,node.line.stations,false,speed)};schedules.put(node.line,pair);
        }
        int index=lines.get(node.line).indexOf(node);
        return pair[direction].waitingMinutes(index,minute,node.line.name);
    }

    private Journey journey(Step end) {
        List<Step> path = new ArrayList<>();
        for(Step step=end;step.via!=null;step=step.previous)path.add(step);
        Collections.reverse(path);
        List<ChelaileDataSource.TransitSegment> segments = new ArrayList<>();
        List<Integer> directions = new ArrayList<>();
        List<List<Node>> rideTracks=new ArrayList<>();
        for (int i = 0; i < path.size();) {
            Step firstStep=path.get(i++);Edge start=firstStep.via;
            if (start.direction < 0) {
                segments.add(new ChelaileDataSource.TransitSegment("walk", "",
                        start.from.station.name, start.to.station.name, 0, -1, start.minutes));
                directions.add(-1);
                continue;
            }
            Edge last = start;
            List<Node> ride=new ArrayList<>();ride.add(start.from);ride.add(start.to);
            int minutes = start.minutes+firstStep.waiting, count = 1;
            while (i < path.size()) {
                Step nextStep=path.get(i);Edge next=nextStep.via;
                if (next.direction != start.direction || next.from.line != start.from.line) break;
                i++;
                last = next;
                ride.add(next.to);
                minutes += next.minutes+nextStep.waiting;
                count++;
            }
            ChelaileDataSource.TransitSegment segment = new ChelaileDataSource.TransitSegment("metro",
                    start.from.line.name, start.from.station.name, last.to.station.name, 0, count, minutes);
            segment.boardLat = start.from.stop.lat;
            segment.boardLon = start.from.stop.lon;
            segments.add(segment);
            directions.add(start.direction);
            rideTracks.add(ride);
        }
        int[] result = new int[directions.size()];
        for (int i = 0; i < result.length; i++) result[i] = directions.get(i);
        // Retain the actual service-pattern nodes selected by the router.
        // Resolving by display name again can select a different same-name
        // branch (notably split services) and discard a perfectly valid route.
        return new Journey(new ChelaileDataSource.TransitPlan(end.minutes,"",-1,segments),result,rideTracks);
    }

    Journey matchOnline(ChelaileDataSource.TransitPlan plan, String city) {
        return trace(plan, city, null);
    }

    private Journey trace(ChelaileDataSource.TransitPlan plan, String city, int[] known) {
        List<List<Node>> paths = new ArrayList<>();
        List<ChelaileDataSource.TransitSegment> matched = new ArrayList<>(plan.segments.size());
        int[] directions = new int[plan.segments.size()];
        Arrays.fill(directions, -1);
        List<MetroDataSource.MetroLine> candidates = new ArrayList<>(lines.keySet());
        for (int i = 0; i < plan.segments.size(); i++) {
            ChelaileDataSource.TransitSegment segment = plan.segments.get(i);
            if ("walk".equals(segment.type)) { matched.add(segment); continue; }
            if (!"metro".equals(segment.type)) return null;
            MetroDataSource.MetroLine line = null;
            for (MetroDataSource.MetroLine candidate : candidates)
                if(candidate.name.equals(segment.name)
                        &&MetroPatterns.index(candidate.stations,segment.from)>=0
                        &&MetroPatterns.index(candidate.stations,segment.to)>=0){line=candidate;break;}
            if (line == null) line = MetroLineResolver.best(candidates, segment.name, segment.from, segment.to, city);
            if (line == null) return null;
            List<Node> route = lines.get(line);
            int from = MetroPatterns.index(line.stations, segment.from);
            int to = MetroPatterns.index(line.stations, segment.to);
            if (from < 0 || to < 0 || from == to) return null;
            int direction = from < to ? 0 : 1;
            if (known != null) direction = known[i];
            else if (line.ring) {
                if (segment.name.contains("外环")) direction = 0;
                else if (segment.name.contains("内环")) direction = 1;
                else {
                    int forward = (to - from + route.size()) % route.size();
                    int reverse = route.size() - forward;
                    int forwardDifference = Math.min(Math.abs(forward - segment.stops), Math.abs(forward - 1 - segment.stops));
                    int reverseDifference = Math.min(Math.abs(reverse - segment.stops), Math.abs(reverse - 1 - segment.stops));
                    direction = segment.stops > 0 && forwardDifference != reverseDifference
                            ? (forwardDifference < reverseDifference ? 0 : 1) : (forward <= reverse ? 0 : 1);
                }
            }
            List<Node> path = traceLeg(route, from, to, direction, line.ring);
            if (path.isEmpty()) return null;
            paths.add(path);
            directions[i] = direction;
            Node board = path.get(0), alight = path.get(path.size() - 1);
            ChelaileDataSource.TransitSegment verified = new ChelaileDataSource.TransitSegment("metro",
                    line.name, board.station.name, alight.station.name, segment.distance, path.size() - 1,
                    segment.durationMin);
            verified.boardLat = board.stop.lat;
            verified.boardLon = board.stop.lon;
            verified.terminal = segment.terminal;
            verified.cityHint = segment.cityHint;
            matched.add(verified);
        }
        return paths.isEmpty() ? null : new Journey(new ChelaileDataSource.TransitPlan(
                plan.durationMin, plan.cost, plan.walkingDistance, matched), directions, paths);
    }

    private static List<Node> traceLeg(List<Node> route, int from, int to, int direction, boolean ring) {
        List<Node> path = new ArrayList<>();
        int step = direction == 0 ? 1 : -1;
        int index = from;
        for (int count = 0; count < route.size(); count++) {
            Node node = route.get(index);
            if (node == null) break;
            path.add(node);
            if (index == to) return path;
            index += step;
            if (ring) index = (index + route.size()) % route.size();
            else if (index < 0 || index >= route.size()) break;
        }
        return Collections.emptyList();
    }

    private static void addRide(Node from, Node to, int minutes, int direction) {
        from.edges.add(new Edge(from, to, Math.max(1, minutes), direction));
    }

    static String normalize(String name) {
        return name == null ? "" : name.trim().replaceFirst("(地铁站|站)$", "").replace(" ", "");
    }
}
