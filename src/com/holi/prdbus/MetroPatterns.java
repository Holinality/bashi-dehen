package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Physical line identity is separate from the ordered stations of a train service. */
final class MetroPatterns {
    static final String GZ3 = "广州3号线";
    static final String GZ3_BRANCH = "广州3号线支线";
    private static final java.util.regex.Pattern TERMINAL_QUALIFIER = java.util.regex.Pattern.compile(
            "[（(](?:[12]号航站楼|T[12]|航站楼[12])[）)]");
    private MetroPatterns() { }

    static boolean isGuangzhou3(String name) {
        return name != null && (name.equals(GZ3) || name.equals(GZ3_BRANCH)
                || name.equals("广州3号线北延段"));
    }

    static MetroDataSource.MetroLine find(List<MetroDataSource.MetroLine> lines, String name) {
        String wanted = "广州3号线北延段".equals(name) ? GZ3 : name;
        for (MetroDataSource.MetroLine line : lines) if (line.name.equals(wanted)) return line;
        return lines.isEmpty() ? null : MetroLineResolver.best(lines, name, "", "", name.length() >= 2 ? name.substring(0, 2) : "广州");
    }

    static List<MetroDataSource.MetroLine> normalize(List<MetroDataSource.MetroLine> input) {
        MetroDataSource.MetroLine southern = null, northern = null;
        for (MetroDataSource.MetroLine line : input) {
            if (!isGuangzhou3(line.name)) continue;
            if (index(line.stations, "天河客运站") >= 0 && index(line.stations, "海傍") >= 0) southern = line;
            if (index(line.stations, "机场北") >= 0 && index(line.stations, "体育西路") >= 0
                    && index(line.stations, "海傍") < 0) northern = line;
        }
        if (southern == null || northern == null) return new ArrayList<>(input);
        int junction = index(southern.stations, "体育西路");
        if (junction < 0) return new ArrayList<>(input);
        // Baseline orientation is Tianhe Coach Terminal -> Haibang and Tiyu Xilu -> Airport North.
        if (index(southern.stations, "天河客运站") > junction
                || index(southern.stations, "海傍") < junction) return new ArrayList<>(input);
        List<MetroDataSource.Station> north = new ArrayList<>(northern.stations);
        boolean reverseNorth = index(north, "机场北") > index(north, "体育西路");
        if (reverseNorth) {
            List<MetroDataSource.Station> reversed = new ArrayList<>();
            for (int i = north.size() - 1; i >= 0; i--) {
                MetroDataSource.Station source = north.get(i);
                MetroDataSource.Station segment = i > 0 ? north.get(i - 1) : source;
                reversed.add(copy(source, segment.segKm, segment.segMin,
                        source.firstR, source.lastR, source.first, source.last));
            }
            north = reversed;
        }
        if (index(north, "机场北") != 0 || index(north, "体育西路") != north.size() - 1)
            return new ArrayList<>(input);
        MetroDataSource.Station airport = north.get(0);
        MetroDataSource.Station haibang = southern.stations.get(southern.stations.size() - 1);
        MetroDataSource.Station tianhe = southern.stations.get(0);
        MetroDataSource.MetroLine through = new MetroDataSource.MetroLine(GZ3, southern.colour,
                "体育西路分岔 · 北行请确认机场北或天河客运站方向", false,
                terminalTime(airport.first, reverseNorth ? northern.firstTimeR : northern.firstTime),
                terminalTime(airport.last, reverseNorth ? northern.lastTimeR : northern.lastTime),
                terminalTime(haibang.firstR, southern.firstTimeR), terminalTime(haibang.lastR, southern.lastTimeR));
        through.networkId = GZ3;
        through.serviceLabel = "机场北 ↔ 海傍";
        through.dataSource = "高德查询".equals(northern.dataSource) || "高德查询".equals(southern.dataSource)
                ? "高德查询" : "本地线网";
        through.dataAt = Math.max(northern.dataAt, southern.dataAt);
        through.stations.addAll(north.subList(0, north.size() - 1));
        MetroDataSource.Station southJunction = southern.stations.get(junction);
        MetroDataSource.Station northJunction = north.get(north.size() - 1);
        through.stations.add(copy(southJunction, southJunction.segKm, southJunction.segMin,
                southJunction.first, southJunction.last, northJunction.firstR, northJunction.lastR));
        through.stations.addAll(southern.stations.subList(junction + 1, southern.stations.size()));

        MetroDataSource.MetroLine branch = new MetroDataSource.MetroLine(GZ3_BRANCH, southern.colour,
                "天河客运站交路 · 经体育西路前往海傍", false,
                terminalTime(tianhe.first, southern.firstTime), terminalTime(tianhe.last, southern.lastTime),
                terminalTime(haibang.firstR, southern.firstTimeR), terminalTime(haibang.lastR, southern.lastTimeR));
        branch.networkId = GZ3;
        branch.serviceLabel = "天河客运站 ↔ 海傍";
        branch.dataSource = southern.dataSource;
        branch.dataAt = southern.dataAt;
        branch.stations.addAll(southern.stations);
        cleanInterchanges(through);
        cleanInterchanges(branch);
        List<MetroDataSource.MetroLine> result = new ArrayList<>();
        boolean added = false;
        for (MetroDataSource.MetroLine line : input) {
            if (isGuangzhou3(line.name)) {
                if (!added) { result.add(through); result.add(branch); added = true; }
            } else result.add(line);
        }
        rebuildTransfers(result);
        return result;
    }

    private static String terminalTime(String station, String fallback) {
        return MetroTimes.validTime(station) ? station : fallback;
    }

    static void rebuildTransfers(List<MetroDataSource.MetroLine> lines) {
        java.util.Map<String, java.util.Map<MetroDataSource.MetroLine, MetroDataSource.Station>> byStation =
                new java.util.HashMap<>();
        for (MetroDataSource.MetroLine line : lines) for (MetroDataSource.Station station : line.stations) {
            String key = stationKey(station.name);
            if (!byStation.containsKey(key)) byStation.put(key, new java.util.LinkedHashMap<>());
            if (!byStation.get(key).containsKey(line)) byStation.get(key).put(line, station);
        }
        for (MetroDataSource.MetroLine line : lines) for (int i = 0; i < line.stations.size(); i++) {
            MetroDataSource.Station station = line.stations.get(i);
            java.util.Set<String> changes = new java.util.TreeSet<>();
            for (java.util.Map.Entry<MetroDataSource.MetroLine, MetroDataSource.Station> link
                    : byStation.get(stationKey(station.name)).entrySet()) {
                MetroDataSource.MetroLine other = link.getKey();
                if (other.networkId.equals(line.networkId)) continue;
                MetroDataSource.Station point = link.getValue();
                if (TransitRules.validCoordinate(station.lat, station.lon)
                        && TransitRules.validCoordinate(point.lat, point.lon)
                        && GeoUtils.distanceKm(station.lat, station.lon, point.lat, point.lon) <= 1.0)
                    changes.add(other.networkId);
            }
            line.stations.set(i, new MetroDataSource.Station(station.name, station.lat, station.lon,
                    station.segKm, station.segMin, new ArrayList<>(changes), station.branch, station.branchText,
                    station.first, station.last, station.firstR, station.lastR));
        }
    }

    private static void cleanInterchanges(MetroDataSource.MetroLine line) {
        for (int i = 0; i < line.stations.size(); i++) {
            MetroDataSource.Station old = line.stations.get(i);
            line.stations.set(i, copy(old, old.segKm, old.segMin, old.first, old.last, old.firstR, old.lastR));
        }
    }

    private static MetroDataSource.Station copy(MetroDataSource.Station s, double km, int minutes,
                                                String first, String last, String firstR, String lastR) {
        List<String> changes = new ArrayList<>();
        if (s.interchange != null) for (String name : s.interchange)
            if (!isGuangzhou3(name)) changes.add(name);
        boolean junction = "体育西路".equals(s.name);
        return new MetroDataSource.Station(s.name, s.lat, s.lon, km, minutes, changes,
                junction || s.branch, junction ? "北行分为机场北、天河客运站方向，请确认列车终到站" : s.branchText,
                first, last, firstR, lastR);
    }

    static int index(List<MetroDataSource.Station> stations, String name) {
        String wanted = stationKey(name);
        for (int i = 0; i < stations.size(); i++)
            if (stationKey(stations.get(i).name).equals(wanted)) return i;
        return -1;
    }

    static String stationKey(String name) {
        return TERMINAL_QUALIFIER.matcher(TransitRules.placeNameKey(name).replace("朗", "塱")).replaceAll("");
    }

    private static List<String> alignedNames(MetroDataSource.MetroLine local,
                                              ChelaileDataSource.GaodeLine remote) {
        List<String> names = new ArrayList<>();
        for (String name : remote.stops) {
            int old = index(local.stations, name);
            names.add(old < 0 ? name : local.stations.get(old).name);
        }
        return names;
    }

    /** Adopt only the same line and a coherent order; new adjacency never inherits stale segment minutes. */
    static MetroDataSource.MetroLine preferRemote(MetroDataSource.MetroLine local,
                                                  List<ChelaileDataSource.GaodeLine> choices) {
        if (local.stations.size() < 2) return local;
        ChelaileDataSource.GaodeLine best = null;
        int bestScore = -1;
        for (ChelaileDataSource.GaodeLine candidate : choices) {
            if (!"metro".equals(TransitRules.transitMode(candidate.transportType, candidate.name))) continue;
            if (!sameLineIdentity(local.name, candidate.name)) continue;
            String first = local.stations.get(0).name, last = local.stations.get(local.stations.size() - 1).name;
            List<String> candidateNames = alignedNames(local, candidate);
            if (!candidateNames.contains(first) || !candidateNames.contains(last)) continue;
            int common = 0;
            for (MetroDataSource.Station station : local.stations) if (candidateNames.contains(station.name)) common++;
            if (common < Math.max(2, local.stations.size() * 3 / 5)) continue;
            if (common > bestScore) { best = candidate; bestScore = common; }
        }
        if (best == null) return local;
        List<String> names = alignedNames(local, best);
        List<double[]> points = new ArrayList<>(best.stopPoints);
        if (names.indexOf(local.stations.get(0).name) > names.indexOf(local.stations.get(local.stations.size() - 1).name)) {
            Collections.reverse(names); Collections.reverse(points);
        }
        if ("广州3号线北延段".equals(local.name)) {
            int first = names.indexOf(local.stations.get(0).name);
            int last = names.indexOf(local.stations.get(local.stations.size() - 1).name);
            if (first < 0 || last <= first || points.size() != names.size()) return local;
            names = new ArrayList<>(names.subList(first, last + 1));
            points = new ArrayList<>(points.subList(first, last + 1));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : names) if (!seen.add(name)) return local; // Ambiguous loop order: keep verified baseline.
        MetroDataSource.MetroLine merged = new MetroDataSource.MetroLine(local.name, local.colour, local.branchInfo,
                local.ring, local.firstTime, local.lastTime, local.firstTimeR, local.lastTimeR);
        merged.networkId = local.networkId;
        merged.serviceLabel = local.serviceLabel;
        merged.dataSource = "高德查询";
        merged.exprStops.addAll(local.exprStops);
        merged.exprNote = local.exprNote;
        for (int i = 0; i < names.size(); i++) {
            int oldIndex = index(local.stations, names.get(i));
            MetroDataSource.Station old = oldIndex < 0 ? null : local.stations.get(oldIndex);
            double[] point = i < points.size() ? points.get(i) : null;
            double lat = old == null ? 0 : old.lat, lon = old == null ? 0 : old.lon;
            if (point != null && TransitRules.validCoordinate(point[0], point[1])) {
                double[] gcj = Coord.wgsToGcj(point[0], point[1]); lat = gcj[0]; lon = gcj[1];
            }
            if (!TransitRules.validCoordinate(lat, lon)) return local;
            boolean sameEdge = old != null && oldIndex + 1 < local.stations.size() && i + 1 < names.size()
                    && local.stations.get(oldIndex + 1).name.equals(names.get(i + 1));
            merged.stations.add(new MetroDataSource.Station(names.get(i), lat, lon,
                    sameEdge ? old.segKm : 0, sameEdge ? old.segMin : 0,
                    old == null ? new ArrayList<String>() : new ArrayList<>(old.interchange),
                    old != null && old.branch, old == null ? "" : old.branchText,
                    old == null ? "" : old.first, old == null ? "" : old.last,
                    old == null ? "" : old.firstR, old == null ? "" : old.lastR));
        }
        return merged;
    }

    static boolean sameLineIdentity(String local, String remote) {
        java.util.Set<String> numbers = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)(?=/|号线)").matcher(local);
        while (matcher.find()) numbers.add(matcher.group(1));
        matcher = java.util.regex.Pattern.compile("(\\d+)(?=/|号线)").matcher(TransitRules.routeIdentity(remote));
        while (matcher.find()) if (numbers.contains(matcher.group(1))) return true;
        return numbers.isEmpty() && ((local.contains("广佛") && remote.contains("广佛"))
                || (local.toUpperCase(java.util.Locale.ROOT).contains("APM")
                && TransitRules.routeIdentity(remote).toUpperCase(java.util.Locale.ROOT).contains("APM")));
    }
}
