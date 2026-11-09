package com.holi.prdbus;

import android.graphics.PointF;
import java.util.*;

/** Original topology-only layout. No operator-map pixels or geographic claims. */
final class RailMapStations {
    private RailMapStations() {}
    static Map<String, PointF> forRegion(String region) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (RailCatalog.Line line : RailCatalog.LINES) {
            if (!region.equals(line.region)) continue;
            for (String stop : line.stops) if (!graph.containsKey(stop)) graph.put(stop, new LinkedHashSet<>());
            for (int i = 1; i < line.stops.length; i++) {
                String a = line.stops[i-1], b = line.stops[i];
                graph.get(a).add(b); graph.get(b).add(a);
            }
        }
        List<Map<Integer, List<String>>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String seed : graph.keySet()) {
            if (!visited.add(seed)) continue;
            Map<Integer, List<String>> columns = new LinkedHashMap<>();
            Map<String, Integer> levels = new HashMap<>();
            ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(seed); levels.put(seed, 0);
            while (!queue.isEmpty()) {
                String stop = queue.removeFirst(); int level = levels.get(stop);
                List<String> column = columns.get(level);
                if (column == null) { column = new ArrayList<>(); columns.put(level, column); }
                column.add(stop);
                for (String next : graph.get(stop)) if (visited.add(next)) {
                    levels.put(next, level+1); queue.addLast(next);
                }
            }
            components.add(columns);
        }
        Map<String, PointF> result = new LinkedHashMap<>();
        for (int c = 0; c < components.size(); c++) {
            Map<Integer, List<String>> columns = components.get(c);
            int maximum = Math.max(1, columns.size()-1);
            for (Map.Entry<Integer, List<String>> entry : columns.entrySet()) {
                List<String> names = entry.getValue();
                for (int r = 0; r < names.size(); r++) {
                    float x = .055f + .89f * entry.getKey()/maximum;
                    float y = .08f + .84f * (c+(r+1f)/(names.size()+1f))/components.size();
                    result.put(names.get(r), new PointF(x,y));
                }
            }
        }
        return result;
    }
}
