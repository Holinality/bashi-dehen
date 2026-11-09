package com.holi.prdbus;

import android.content.Context;
import android.graphics.PointF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline schematic coordinates. Geographic routing and GPS retain their original coordinates. */
final class MetroDiagramLayout {
    final Map<MetroNetwork.Stop, PointF> stops = new IdentityHashMap<>();
    final Map<MetroDataSource.MetroLine, List<PointF>> lines = new IdentityHashMap<>();
    final Map<MetroNetwork.Stop,List<PointF>> ports=new IdentityHashMap<>();
    final Map<MetroNetwork.Node,PointF> nodePoints=new IdentityHashMap<>();
    final Map<MetroDataSource.MetroLine, List<RoutedEdge>> routedLines = new IdentityHashMap<>();
    final List<RoutedEdge> sharedEdges = new ArrayList<>();
    final Set<MetroNetwork.Stop> sharedStops = new LinkedHashSet<>();
    final List<AuthoredTrack> authoredTracks = new ArrayList<>();
    final Map<MetroNetwork.Stop, LabelAnchor> authoredLabels = new IdentityHashMap<>();
    final List<Badge> authoredBadges = new ArrayList<>();
    final Map<MetroNetwork.Stop, String> authoredNames = new IdentityHashMap<>();
    final List<DisplayStop> displayStops = new ArrayList<>();
    final float authoredStrokeWidth = 11f, authoredTextSize = 30f;
    final float authoredStopRadius = 8f, authoredTransferRadius = 26f;
    private final Map<MetroNetwork.Node, Map<MetroNetwork.Node, List<PointF>>> routedIntervals = new IdentityHashMap<>();

    static final class AuthoredTrack {
        final String service;
        final List<PointF> points;
        final int colour;
        AuthoredTrack(String service, List<PointF> points, int colour) {
            this.service=service;this.points=points;this.colour=colour;
        }
    }
    static final class LabelAnchor {
        final float dx,dy;
        final String anchor;
        LabelAnchor(float dx,float dy,String anchor){this.dx=dx;this.dy=dy;this.anchor=anchor;}
    }
    static final class Badge {
        final String service,text;
        final float x,y,width,height;
        final int colour;
        Badge(String service,String text,float x,float y,float width,float height,int colour){
            this.service=service;this.text=text;this.x=x;this.y=y;this.width=width;this.height=height;this.colour=colour;
        }
    }
    static final class DisplayStop {
        final String name,status;
        final PointF point;
        final LabelAnchor label;
        DisplayStop(String name,String status,PointF point,LabelAnchor label){
            this.name=name;this.status=status;this.point=point;this.label=label;
        }
    }

    static final class RoutedEdge {
        final String firstName;
        final List<PointF> points;
        final List<String> services = new ArrayList<>();

        RoutedEdge(String firstName, List<PointF> points) {
            this.firstName = firstName;
            this.points = points;
        }

        boolean shared() {
            return services.size() > 1;
        }
    }

    static MetroDiagramLayout load(Context context, MetroNetwork.Area area, MetroNetwork model) {
        if (area == MetroNetwork.Area.GUANGFO) {
            try {
                MetroDiagramLayout authored = decodeAuthored(read(context, "metro-guangfo-schematic.json"), model);
                if (authored != null) return authored;
            } catch (Exception unavailable) {
                // Keep existing fallback maps usable if a newer catalogue has
                // stations outside the reviewed offline drawing.
            }
        }
        if (area != MetroNetwork.Area.SHANGHAI) {
            try {
                MetroDiagramLayout routed = decodeTopology(read(context, "metro-topology.json").optJSONObject(area.name()), model);
                if (routed != null) return routed;
            } catch (Exception unavailable) {
                // A refreshed service graph may include stops beyond the bundled drawing.
            }
        }
        try {
            return decode(read(context, "metro-diagrams.json").optJSONArray(area.name()), model);
        } catch (Exception unavailable) { return null; }
    }

    private static JSONObject read(Context context, String asset) throws Exception {
        try (InputStream input = context.getAssets().open(asset);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) != -1;) buffer.write(bytes, 0, count);
            return new JSONObject(buffer.toString("UTF-8"));
        }
    }

    static MetroDiagramLayout decodeTopology(JSONObject source, MetroNetwork model) throws Exception {
        if (source == null) return null;
        MetroDiagramLayout layout = new MetroDiagramLayout();
        JSONArray stations = source.getJSONArray("stations"), edges = source.getJSONArray("edges");
        PointF[] positions = new PointF[stations.length()];
        String[] names = new String[stations.length()];
        for (int i = 0; i < positions.length; i++) {
            JSONArray row = stations.getJSONArray(i);
            names[i] = key(row.getString(0));
            positions[i] = new PointF((float) row.getDouble(1), (float) row.getDouble(2));
        }
        Map<String, RoutedEdge> index = new HashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONArray row = edges.getJSONArray(i);
            int a = row.getInt(0), b = row.getInt(1);
            JSONArray bends = row.getJSONArray(2), services = row.getJSONArray(3);
            if (a == b || names[a].equals(names[b]) || bends.length() % 2 != 0) return null;
            List<PointF> points = new ArrayList<>();
            points.add(positions[a]);
            for (int j = 0; j < bends.length(); j += 2) {
                points.add(new PointF((float) bends.getDouble(j), (float) bends.getDouble(j + 1)));
            }
            points.add(positions[b]);
            RoutedEdge edge = new RoutedEdge(names[a], points);
            for (int j = 0; j < services.length(); j++) {
                String line = services.getString(j);
                if (index.put(intervalKey(line, names[a], names[b]), edge) != null) return null;
            }
        }
        Map<RoutedEdge, List<MetroNetwork.Stop>> endpoints = new LinkedHashMap<>();
        for (Map.Entry<MetroDataSource.MetroLine, List<MetroNetwork.Node>> entry : model.lines.entrySet()) {
            MetroDataSource.MetroLine line = entry.getKey();
            List<MetroNetwork.Node> nodes = entry.getValue();
            List<PointF> path = new ArrayList<>();
            List<RoutedEdge> lineEdges = new ArrayList<>();
            int count = nodes.size() - 1 + (line.ring ? 1 : 0);
            for (int i = 0; i < count; i++) {
                MetroNetwork.Node a = nodes.get(i), b = nodes.get((i + 1) % nodes.size());
                if (a == null || b == null) return null;
                String first = key(a.stop.name), last = key(b.stop.name);
                RoutedEdge edge = index.get(intervalKey(line.networkId, first, last));
                if (edge == null) return null;
                List<PointF> forward = edge.points;
                if (!first.equals(edge.firstName)) {
                    forward = new ArrayList<>(edge.points);
                    Collections.reverse(forward);
                }
                if (!layout.bind(a, forward.get(0)) || !layout.bind(b, forward.get(forward.size() - 1))) return null;
                layout.putInterval(a, b, forward);
                List<PointF> reverse = new ArrayList<>(forward);
                Collections.reverse(reverse);
                layout.putInterval(b, a, reverse);
                path.addAll(forward.subList(path.isEmpty() ? 0 : 1, forward.size()));
                lineEdges.add(edge);
                if (!edge.services.contains(line.networkId)) edge.services.add(line.networkId);
                endpoints.put(edge, java.util.Arrays.asList(a.stop, b.stop));
            }
            layout.lines.put(line, path);
            layout.routedLines.put(line, lineEdges);
        }
        for (Map.Entry<RoutedEdge, List<MetroNetwork.Stop>> entry : endpoints.entrySet()) {
            if (!entry.getKey().shared()) continue;
            Collections.sort(entry.getKey().services);
            layout.sharedEdges.add(entry.getKey());
            layout.sharedStops.addAll(entry.getValue());
        }
        return layout.stops.size() == model.stops.size() ? layout : null;
    }

    private static String intervalKey(String line, String a, String b) {
        return a.compareTo(b) < 0 ? line + "\n" + a + "\n" + b : line + "\n" + b + "\n" + a;
    }

    private boolean bind(MetroNetwork.Node node, PointF position) {
        PointF previous = stops.put(node.stop, position);
        if (previous != null && previous != position) return false;
        nodePoints.put(node, position);
        ports.put(node.stop, Collections.singletonList(position));
        return true;
    }

    void putInterval(MetroNetwork.Node from, MetroNetwork.Node to, List<PointF> points) {
        Map<MetroNetwork.Node, List<PointF>> next = routedIntervals.get(from);
        if (next == null) {
            next = new IdentityHashMap<>();
            routedIntervals.put(from, next);
        }
        next.put(to, points);
    }

    boolean routed() {
        return !routedLines.isEmpty();
    }

    boolean authored() { return !authoredTracks.isEmpty(); }

    static MetroDiagramLayout decodeAuthored(JSONObject source, MetroNetwork model) throws Exception {
        return AuthoredMetroDiagram.decode(source, model);
    }

    static MetroDiagramLayout decode(JSONArray source, MetroNetwork model) throws Exception {
        if (source == null) return null;
        MetroDiagramLayout layout = new MetroDiagramLayout();
        for (Map.Entry<MetroDataSource.MetroLine, List<MetroNetwork.Node>> entry : model.lines.entrySet()) {
            MetroDataSource.MetroLine line = entry.getKey();
                List<Source> candidates = new ArrayList<>();
                Map<String, PointF> positions = new HashMap<>();
                for (int i = 0; i < source.length(); i++) {
                    JSONObject candidate = source.getJSONObject(i);
                    if (!MetroCatalog.identity(candidate.getString("city"), candidate.getString("name")).equals(line.networkId)) continue;
                    Source shape = new Source(candidate);
                    candidates.add(shape);
                    positions.putAll(shape.stations);
                }
                if (candidates.isEmpty()) return null;
                Map<MetroNetwork.Stop, PointF> stops = new HashMap<>();
                for (MetroNetwork.Node node : entry.getValue()) {
                    if (node == null) throw new IllegalArgumentException("Missing station coordinates");
                    PointF point = positions.get(key(node.stop.name));
                    if (point == null) throw new IllegalArgumentException("No diagram station");
                    stops.put(node.stop, point);
                }
                List<PointF> path = new ArrayList<>();
                List<MetroNetwork.Node> nodes = entry.getValue();
                int intervals = nodes.size() - 1 + (line.ring ? 1 : 0);
                for (int i = 0; i < intervals; i++) {
                    MetroNetwork.Node a = nodes.get(i % nodes.size()), b = nodes.get((i + 1) % nodes.size());
                    Source segment = null;
                    for (Source candidate : candidates)
                        if (candidate.stations.containsKey(key(a.stop.name)) && candidate.stations.containsKey(key(b.stop.name))) {
                            segment = candidate;
                            break;
                        }
                    if (segment == null) throw new IllegalArgumentException("Missing diagram interval");
                    path.addAll(section(segment.path, segment.stations.get(key(a.stop.name)),
                            segment.stations.get(key(b.stop.name)), line.ring));
                }
                for (Map.Entry<MetroNetwork.Stop, PointF> stop : stops.entrySet())
                    if (!layout.stops.containsKey(stop.getKey())) layout.stops.put(stop.getKey(), stop.getValue());
                layout.lines.put(line, path);
                float[] coordinates=coordinates(path);
                for(MetroNetwork.Node node:nodes) {
                    PointF anchor=stops.get(node.stop);
                    DiagramPath.Projection projected=DiagramPath.project(coordinates,anchor.x,anchor.y);
                    if(projected==null)continue;
                    PointF point=new PointF(projected.x,projected.y);layout.nodePoints.put(node,point);
                    List<PointF> ports=layout.ports.get(node.stop);
                    if(ports==null){ports=new ArrayList<>();layout.ports.put(node.stop,ports);}
                    boolean duplicate=false;
                    for(PointF port:ports)if(Math.hypot(port.x-point.x,port.y-point.y)<.5)duplicate=true;
                    if(!duplicate)ports.add(point);
                }
        }
        for(Map.Entry<MetroNetwork.Stop,List<PointF>> entry:layout.ports.entrySet()) {
            float x=0,y=0;for(PointF p:entry.getValue()){x+=p.x;y+=p.y;}
            layout.stops.put(entry.getKey(),new PointF(x/entry.getValue().size(),y/entry.getValue().size()));
        }
        return layout.lines.size()!=model.lines.size() || layout.stops.size()!=model.stops.size() ? null : layout;
    }

    private static final class Source {
        final Map<String, PointF> stations = new HashMap<>();
        final List<PointF> path = new ArrayList<>();
        Source(JSONObject data) throws Exception {
            JSONArray stops = data.getJSONArray("stops");
            for (int i = 0; i < stops.length(); i++) {
                JSONArray point = stops.getJSONArray(i);
                stations.put(key(point.getString(0)), new PointF((float) point.getDouble(1), (float) point.getDouble(2)));
            }
            JSONArray points = data.getJSONArray("points");
            for (int i = 0; i < points.length(); i++) {
                JSONArray point = points.getJSONArray(i);
                path.add(new PointF((float) point.getDouble(0), (float) point.getDouble(1)));
            }
        }
    }

    private static String key(String name) {
        return MetroNetwork.normalize(name.replaceFirst("[（(][^）)]*[）)]$", ""));
    }

    List<PointF> between(MetroNetwork.Node from, MetroNetwork.Node to) {
        if (routed() || authored()) {
            Map<MetroNetwork.Node, List<PointF>> next = routedIntervals.get(from);
            return next == null || !next.containsKey(to) ? Collections.emptyList() : next.get(to);
        }
        List<PointF> path = lines.get(from.line);
        PointF a = nodePoints.get(from), b = nodePoints.get(to);
        if(a==null||b==null)return java.util.Collections.emptyList();
        return section(path, a, b, from.line.ring);
    }

    static List<PointF> section(List<PointF> path, PointF a, PointF b, boolean ring) {
        if(path==null||path.isEmpty()||a==null||b==null)return java.util.Collections.emptyList();
        List<PointF> result = new ArrayList<>();
        float[] points=DiagramPath.between(coordinates(path),a.x,a.y,b.x,b.y,ring);
        for(int i=0;i<points.length;i+=2)result.add(new PointF(points[i],points[i+1]));
        return result;
    }
    private static float[] coordinates(List<PointF> path) {
        float[] result=new float[path.size()*2];
        for(int i=0;i<path.size();i++){result[i*2]=path.get(i).x;result[i*2+1]=path.get(i).y;}
        return result;
    }
}
