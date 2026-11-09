package com.holi.prdbus;

import java.util.*;
import java.util.regex.*;

/** Builds provider station order first; the bundled database contributes missing timetable/color fields. */
final class MetroCatalog {
    private MetroCatalog() { }

    private static final class Baseline {
        final MetroDataSource.MetroLine line;
        final String identity;
        final Map<String, Integer> stations = new HashMap<>();

        Baseline(String city, MetroDataSource.MetroLine line) {
            this.line = line;
            identity = MetroCatalog.identity(city, line.name);
            for (int i = 0; i < line.stations.size(); i++) {
                String key = MetroPatterns.stationKey(line.stations.get(i).name);
                if (!stations.containsKey(key)) stations.put(key, i);
            }
        }

        int index(String name) {
            Integer value = stations.get(MetroPatterns.stationKey(name));
            return value == null ? -1 : value;
        }
    }

    static List<MetroDataSource.MetroLine> build(String city, List<ChelaileDataSource.GaodeLine> rows,
                                                List<MetroDataSource.MetroLine> baseline) {
        Map<String, MetroDataSource.MetroLine> services = new LinkedHashMap<>();
        Set<String> supplied = new HashSet<>();
        Map<ChelaileDataSource.GaodeLine, String> identities = new IdentityHashMap<>();
        for (ChelaileDataSource.GaodeLine row : rows) identities.put(row, identity(city, row.name));
        List<Baseline> baselines = new ArrayList<>();
        for (MetroDataSource.MetroLine line : baseline)
            baselines.add(new Baseline(city, line));
        for (ChelaileDataSource.GaodeLine row : rows) {
            if (!"metro".equals(TransitRules.transitMode(row.transportType, row.name))) continue;
            String identity = identities.get(row);
            if (identity.isEmpty()) continue;
            String owner = PublicMetro.owner(identity);
            if (!owner.isEmpty() && !owner.equals(city) && !shared(city, identity)) continue;
            supplied.add(identity);
            if (row.operatingStatus != 1) continue;
            Baseline old = matchingBaseline(city, row, baselines);
            MetroDataSource.MetroLine line = buildLine(city, identity, row, rows, identities, old);
            String forward = stationSequence(line), reverse = reverseSequence(line);
            String key = identity + "|" + (forward.compareTo(reverse) < 0 ? forward : reverse);
            if (!services.containsKey(key)) services.put(key, line);
        }
        if (services.isEmpty()) throw new IllegalStateException("高德未返回有效地铁线网");
        List<MetroDataSource.MetroLine> result = new ArrayList<>(services.values());
        Map<String, Integer> counts = new HashMap<>();
        for (MetroDataSource.MetroLine line : result) counts.put(line.name, counts.containsKey(line.name) ? counts.get(line.name) + 1 : 1);
        for (int i = 0; i < result.size(); i++) {
            MetroDataSource.MetroLine line = result.get(i);
            if (counts.get(line.name) > 1) {
                if ("广州3号线".equals(line.name) && MetroPatterns.index(line.stations, "天河客运站") >= 0)
                    result.set(i, renamed(line, MetroPatterns.GZ3_BRANCH));
                else if (!"广州3号线".equals(line.name)) result.set(i, renamed(line, line.name + "（" + line.serviceLabel + "）"));
            }
        }
        // An absent alias is not proof of a closed line; retain it explicitly as local fallback.
        for (MetroDataSource.MetroLine old : baseline) {
            if (!MetroDataSource.cityOfLine(old).equals(city)) continue;
            String own = identity(city, old.name);
            boolean covered = supplied.contains(own);
            if (!covered && own.contains("/")) {
                Matcher numbers = Pattern.compile("(\\d+)(?=/|号线)").matcher(own);
                covered = true;
                while (numbers.find()) if (!supplied.contains(city + numbers.group(1) + "号线")) covered = false;
            }
            if (!covered) result.add(old);
        }
        return result;
    }

    static String identity(String city, String raw) {
        if ("深圳".equals(city) && raw.contains("坪山云巴")) return "深圳坪山云巴1号线";
        if (raw.contains("有轨电车")) return raw.startsWith(city) ? raw : city + raw;
        if ("香港".equals(city)) raw = raw.replace('綫', '线').replace('線', '线')
                .replace("港鐵", "").replace("港铁", "").replace("機場快", "机场快").replace("机场快轨", "机场快线");
        if ("金华".equals(city) && raw.matches("金义东线(?:金义段|义东段)")) return city + raw;
        if ("桃园".equals(city) && raw.matches("机场线(?:普通车|直达车)")) return city + raw;
        if ("资阳".equals(city) && "S3(资阳)线".equals(raw)) return city + "S3线";
        String base = TransitRules.routeIdentity(raw).replace("地铁", "").replace("轨道交通", "");
        String namedCity = PublicMetro.owner(base);
        if (!namedCity.isEmpty()) city = namedCity;
        if (base.contains("广佛")) return "广佛线";
        if (base.endsWith("郑许线") && ("郑州".equals(city) || "许昌".equals(city))) return "郑州郑许线";
        if (base.toUpperCase(Locale.ROOT).contains("APM")) return city + "APM线";
        String local = base.startsWith(city) ? base.substring(city.length()) : base;
        if (local.matches("(?i)S[0-9]+(?:号)?线.*"))
            return city + local.replaceFirst("(?i)^(S[0-9]+).*", "$1").toUpperCase(Locale.ROOT) + "线";
        Matcher m = Pattern.compile("(\\d+)(?=/|号线)").matcher(base);
        List<String> numbers = new ArrayList<>();
        while (m.find()) if (!numbers.contains(m.group(1))) numbers.add(m.group(1));
        Matcher alias = Pattern.compile("[（(](\\d+)号线[）)]").matcher(raw);
        while (alias.find()) if (!numbers.contains(alias.group(1))) numbers.add(alias.group(1));
        if (numbers.isEmpty()) return local.matches("[\\p{IsHan}]{2,16}线") ? city + local : "";
        Collections.sort(numbers, (a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        String name = city + join(numbers, "/") + "号线";
        if ("佛山7号线".equals(name)) name = "广州7号线";
        if ("鄂州11号线".equals(name)) name = "武汉11号线";
        if (base.contains("支线") || base.contains("知识城") || raw.contains("知识城线")) name += "支线";
        return name;
    }

    private static boolean shared(String city, String identity) {
        return ("佛山".equals(city) && "广州7号线".equals(identity))
                || ("鄂州".equals(city) && "武汉11号线".equals(identity))
                || ("许昌".equals(city) && "郑州郑许线".equals(identity));
    }

    private static Baseline matchingBaseline(String city, ChelaileDataSource.GaodeLine row,
                                             List<Baseline> baseline) {
        Baseline best = null;
        int score = -1;
        String wanted = identity(city, row.name);
        String owner = PublicMetro.owner(wanted);
        if (owner.isEmpty()) owner = city;
        for (Baseline old : baseline) {
            if (!MetroDataSource.cityOfLine(old.line).equals(owner) && !"广佛线".equals(old.line.name)) continue;
            String own = old.identity;
            if (!own.equals(wanted) && !MetroPatterns.sameLineIdentity(old.line.name, row.name)) continue;
            int common = 0;
            for (String station : row.stops) if (old.index(station) >= 0) common++;
            int value = common * 10 + (own.equals(wanted) ? 5 : 0);
            if (value > score) { score = value; best = old; }
        }
        return best;
    }

    private static MetroDataSource.MetroLine buildLine(String city, String identity,
                    ChelaileDataSource.GaodeLine row, List<ChelaileDataSource.GaodeLine> rows,
                    Map<ChelaileDataSource.GaodeLine, String> identities,
                    Baseline baseline) {
        MetroDataSource.MetroLine old = baseline == null ? null : baseline.line;
        if (row.stops.size() < 2 || row.stops.size() != row.stopPoints.size() || row.stops.size() > 200)
            throw new IllegalStateException("高德站序不完整");
        List<String> names = new ArrayList<>();
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < row.stops.size(); i++) {
            String name = row.stops.get(i);
            int index = old == null ? -1 : baseline.index(name);
            names.add(index < 0 ? name : old.stations.get(index).name);
            double[] wgs = row.stopPoints.get(i);
            if (wgs == null || !TransitRules.validCoordinate(wgs[0], wgs[1]))
                throw new IllegalStateException("高德站点坐标缺失");
            points.add(Coord.wgsToGcj(wgs[0], wgs[1]));
        }
        boolean ring = row.ring || (old != null && old.ring);
        if (ring && names.size() > 2 && names.get(0).equals(names.get(names.size() - 1))) {
            names.remove(names.size() - 1); points.remove(points.size() - 1);
        }
        if (new HashSet<>(names).size() != names.size()) throw new IllegalStateException("高德重复站序需要核对");
        boolean flip = false;
        if (old != null) {
            int first = -1, last = -1;
            for (String name : names) {
                int index = baseline.index(name);
                if (index >= 0) { if (first < 0) first = index; last = index; }
            }
            flip = first >= 0 && last >= 0 && first > last;
            if (ring && old.stations.size() > 2) {
                int forward = 0, reverse = 0;
                for (int i = 0; i < names.size(); i++) {
                    int a = baseline.index(names.get(i));
                    int b = baseline.index(names.get((i + 1) % names.size()));
                    if (a < 0 || b < 0) continue;
                    int delta = (b - a + old.stations.size()) % old.stations.size();
                    if (delta == 1) forward++;
                    if (delta == old.stations.size() - 1) reverse++;
                }
                flip = reverse > forward;
            }
        } else flip = names.get(0).compareTo(names.get(names.size() - 1)) > 0;
        if (flip) { Collections.reverse(names); Collections.reverse(points); }
        if (ring) {
            int start = 0;
            if (old != null) {
                for (MetroDataSource.Station anchor : old.stations) {
                    int at = names.indexOf(anchor.name);
                    if (at >= 0) { start = at; break; }
                }
            } else for (int i = 1; i < names.size(); i++) if (names.get(i).compareTo(names.get(start)) < 0) start = i;
            Collections.rotate(names, -start); Collections.rotate(points, -start);
            if (old == null) {
                List<String> reversed = new ArrayList<>(names.subList(1, names.size()));
                Collections.reverse(reversed); reversed.add(0, names.get(0));
                if (join(reversed, "|").compareTo(join(names, "|")) < 0) {
                    Collections.reverse(names.subList(1, names.size()));
                    Collections.reverse(points.subList(1, points.size()));
                    flip = !flip;
                }
            }
        }
        ChelaileDataSource.GaodeLine opposite = null;
        List<String> reversedOriginal = new ArrayList<>(row.stops); Collections.reverse(reversedOriginal);
        for (ChelaileDataSource.GaodeLine other : rows)
            if (other.operatingStatus == 1 && identities.get(other).equals(identity)
                    && "metro".equals(TransitRules.transitMode(other.transportType, other.name))
                    && other.stops.equals(reversedOriginal)) { opposite = other; break; }
        ChelaileDataSource.GaodeLine forward = flip ? opposite : row, backward = flip ? row : opposite;
        String name = identity;
        if ("广州3号线".equals(identity) && names.contains("体育西路") && names.contains("机场北")
                && !names.contains("海傍")) name = "广州3号线北延段";
        MetroDataSource.MetroLine line = new MetroDataSource.MetroLine(name,
                row.colour.matches("#?[0-9a-fA-F]{6}") ? row.colour
                        : old == null ? "2466A8" : old.colour, old == null ? "" : old.branchInfo, ring,
                time(forward == null ? "" : forward.firstTime, old == null ? "" : old.firstTime),
                time(forward == null ? "" : forward.lastTime, old == null ? "" : old.lastTime),
                time(backward == null ? "" : backward.firstTime, old == null ? "" : old.firstTimeR),
                time(backward == null ? "" : backward.lastTime, old == null ? "" : old.lastTimeR));
        line.networkId = identity;
        line.serviceLabel = names.get(0) + " ↔ " + names.get(names.size() - 1);
        line.dataSource = "高德查询";
        if (old != null) { line.exprNote = old.exprNote; for (String stop : old.exprStops) if (names.contains(stop)) line.exprStops.add(stop); }
        for (int i = 0; i < names.size(); i++) {
            int previous = old == null ? -1 : baseline.index(names.get(i));
            MetroDataSource.Station station = previous < 0 ? null : old.stations.get(previous);
            boolean sameEdge = station != null && previous + 1 < old.stations.size() && i + 1 < names.size()
                    && old.stations.get(previous + 1).name.equals(names.get(i + 1));
            String[] forwardHours = stationHours(forward, names.get(i));
            String[] reverseHours = stationHours(backward, names.get(i));
            line.stations.add(new MetroDataSource.Station(names.get(i), points.get(i)[0], points.get(i)[1],
                    sameEdge ? station.segKm : 0, sameEdge ? station.segMin : 0, new ArrayList<String>(),
                    station != null && station.branch, station == null ? "" : station.branchText,
                    time(station == null ? "" : station.first, forwardHours[0]),
                    time(station == null ? "" : station.last, forwardHours[1]),
                    time(station == null ? "" : station.firstR, reverseHours[0]),
                    time(station == null ? "" : station.lastR, reverseHours[1])));
        }
        return line;
    }

    private static String[] stationHours(ChelaileDataSource.GaodeLine row, String name) {
        if (row != null) {
            String[] exact = row.stationHours.get(name);
            if (exact != null) return exact;
            for (Map.Entry<String, String[]> entry : row.stationHours.entrySet())
                if (TransitRules.sameStation(entry.getKey(), name)) return entry.getValue();
        }
        return new String[]{"", ""};
    }

    private static MetroDataSource.MetroLine renamed(MetroDataSource.MetroLine line, String name) {
        MetroDataSource.MetroLine copy = new MetroDataSource.MetroLine(name, line.colour, line.branchInfo, line.ring,
                line.firstTime, line.lastTime, line.firstTimeR, line.lastTimeR);
        copy.networkId = line.networkId; copy.serviceLabel = line.serviceLabel; copy.dataSource = line.dataSource;
        copy.stations.addAll(line.stations); copy.exprStops.addAll(line.exprStops); copy.exprNote = line.exprNote;
        return copy;
    }
    private static String time(String value, String fallback) {
        String v = value == null ? "" : value.replace(":", "").trim();
        if (v.matches("\\d{4}")) v = v.substring(0, 2) + ":" + v.substring(2);
        return MetroTimes.validTime(v) ? v : fallback;
    }
    private static String stationSequence(MetroDataSource.MetroLine line) {
        List<String> names = new ArrayList<>(); for (MetroDataSource.Station s : line.stations) names.add(s.name);
        return join(names, "|");
    }
    private static String reverseSequence(MetroDataSource.MetroLine line) {
        List<String> names = new ArrayList<>(); for (MetroDataSource.Station s : line.stations) names.add(s.name);
        Collections.reverse(names); return join(names, "|");
    }
    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); }
        return result.toString();
    }
}
