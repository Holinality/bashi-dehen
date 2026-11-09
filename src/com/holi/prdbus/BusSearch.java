package com.holi.prdbus;

import java.text.Normalizer;
import java.util.*;

/** Shared search ranking and provider merging for 巴士得很. */
final class BusSearch {
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
    }

    static boolean exact(String query, ChelaileDataSource.SearchResult result) {
        for (ChelaileDataSource.SearchLine line : result.lines)
            if (BusRouteMatch.lineScore(query, line.name, line.lineNo) == 120) return true;
        for (ChelaileDataSource.SearchPoi poi : result.pois)
            if (TransitRules.stationKey(query).equals(TransitRules.stationKey(poi.name))) return true;
        return false;
    }

    static ChelaileDataSource.SearchResult merge(String query, double lat, double lon,
            ChelaileDataSource.SearchResult primary, ChelaileDataSource.SearchResult secondary) {
        Map<String, ChelaileDataSource.SearchLine> routes = new LinkedHashMap<>();
        Map<String, ChelaileDataSource.SearchPoi> places = new LinkedHashMap<>();
        for (ChelaileDataSource.SearchResult result : new ChelaileDataSource.SearchResult[]{primary, secondary}) {
            if (result == null) continue;
            for (ChelaileDataSource.SearchLine line : result.lines) {
                String key = BusRouteMatch.routeKey(line.name) + "|" + TransitRules.platformKey(line.startSn)
                        + "|" + TransitRules.platformKey(line.endSn);
                if (line.startSn.isEmpty() || line.endSn.isEmpty()) key = line.lineId;
                if (!routes.containsKey(key)) routes.put(key, line);
            }
            for (ChelaileDataSource.SearchPoi poi : result.pois) {
                String key = poi.type + "|" + poi.name + "|" + Math.round(poi.lat * 10000)
                        + "|" + Math.round(poi.lon * 10000);
                if (!places.containsKey(key)) places.put(key, poi);
            }
        }
        List<ChelaileDataSource.SearchLine> lines = new ArrayList<>(routes.values());
        Collections.sort(lines, (a, b) -> Integer.compare(
                BusRouteMatch.lineScore(query, b.name, b.lineNo), BusRouteMatch.lineScore(query, a.name, a.lineNo)));
        List<ChelaileDataSource.SearchPoi> pois = new ArrayList<>(places.values());
        double[] gcj = TransitRules.validCoordinate(lat, lon) ? Coord.wgsToGcj(lat, lon) : new double[]{0, 0};
        Collections.sort(pois, (a, b) -> {
            int exactA = TransitRules.stationKey(query).equals(TransitRules.stationKey(a.name)) ? 0 : 1;
            int exactB = TransitRules.stationKey(query).equals(TransitRules.stationKey(b.name)) ? 0 : 1;
            if (exactA != exactB) return Integer.compare(exactA, exactB);
            if (gcj[0] == 0) return 0;
            return Double.compare(distance(a, gcj), distance(b, gcj));
        });
        return new ChelaileDataSource.SearchResult(lines, pois);
    }

    private static double distance(ChelaileDataSource.SearchPoi poi, double[] point) {
        return TransitRules.validCoordinate(poi.lat, poi.lon)
                ? GeoUtils.distanceKm(point[0], point[1], poi.lat, poi.lon) : Double.MAX_VALUE;
    }
}
