package com.holi.prdbus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MetroNetworkView extends View {
    interface OnStation { void select(MetroNetwork.Stop stop); }

    private static final class Track {
        final Path path;
        final RectF bounds=new RectF();
        final int colour;
        final String name;
        final List<RailSegment> segments;
        Track(Path path, int colour, String name, List<RailSegment> segments) {
            this.path = path;
            this.colour = colour;
            this.name = name;
            this.segments = segments;
            path.computeBounds(bounds,true);bounds.inset(-.001f,-.001f);
        }
    }

    private static final class RailSegment {
        final float x1, y1, x2, y2;
        RailSegment(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
        boolean crosses(RectF box) {
            return MapGeometry.crosses(x1,y1,x2,y2,box.left,box.top,box.right,box.bottom);
        }
    }

    /** Parallel stripes have a fixed screen width, including at maximum zoom. */
    private static final class SharedTrack {
        final List<PointF> points;
        final int[] colours;
        final Path[] lanes;
        float spacing = -1;

        SharedTrack(List<PointF> points, int[] colours) {
            this.points = points;
            this.colours = colours;
            lanes = new Path[colours.length];
            for (int i = 0; i < lanes.length; i++) lanes[i] = new Path();
        }

        void draw(Canvas canvas, Paint paint, float laneSpacing, int alpha) {
            if (spacing != laneSpacing) {
                spacing = laneSpacing;
                for (int lane = 0; lane < lanes.length; lane++) {
                    Path path = lanes[lane];
                    path.rewind();
                    float offset = (lane - (lanes.length - 1) / 2f) * spacing;
                    boolean closed = points.size() > 2 && samePoint(points.get(0), points.get(points.size() - 1));
                    int count = closed ? points.size() - 1 : points.size();
                    for (int i = 0; i < count; i++) {
                        PointF point = points.get(i);
                        PointF before = points.get(closed ? (i + count - 1) % count : Math.max(0, i - 1));
                        PointF after = points.get(closed ? (i + 1) % count : Math.min(count - 1, i + 1));
                        float dx = after.x - before.x, dy = after.y - before.y;
                        float length = Math.max(.001f, (float) Math.hypot(dx, dy));
                        float x = point.x - offset * dy / length;
                        float y = point.y + offset * dx / length;
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    if (closed) path.close();
                }
            }
            for (int i = 0; i < lanes.length; i++) {
                paint.setColor(colours[i]);
                paint.setAlpha(alpha);
                canvas.drawPath(lanes[i], paint);
            }
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CornerPathEffect schematicCorners;
    private float schematicCornerRadius;
    private final android.graphics.Typeface stationTypeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);
    private final android.graphics.Typeface authoredTypeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);
    private final Map<String,Integer> authoredColours=new java.util.HashMap<>();
    private final List<Track> tracks = new ArrayList<>();
    private final List<SharedTrack> sharedTracks = new ArrayList<>();
    private final List<Track> routeTracks = new ArrayList<>();
    private final Set<MetroNetwork.Stop> routeStops=new HashSet<>();
    private final List<RailSegment> edges = new ArrayList<>();
    private final Map<Long, List<RailSegment>> edgeIndex = new java.util.HashMap<>();
    private float edgeCell = 1f;
    private final List<RectF> labels = new ArrayList<>();
    private final List<RectF> authoredMarkers = new ArrayList<>();
    private final Map<MetroNetwork.Stop, RectF> stationLabels = new java.util.LinkedHashMap<>();
    private final Set<String> visibleLineLabels = new HashSet<>();
    private final Path districtOutline = new Path();
    private final Path land = new Path();
    private final Path water = new Path();
    private final Path coast = new Path();
    private final List<Path> rivers = new ArrayList<>();
    private List<String> riverKinds=Collections.emptyList();
    private List<MetroDistricts.Label> districtLabels = Collections.emptyList();
    private final RectF bounds = new RectF();
    private final RectF label = new RectF();
    private final RectF trackClearance = new RectF();
    private final RectF viewport=new RectF();
    private final Map<MetroNetwork.Stop,PointF> stationPoints=new java.util.IdentityHashMap<>();
    private final MetroBackdrop backdrop=new MetroBackdrop(this);
    private boolean moving;
    private boolean backgroundPrepared;
    private final GestureDetector pan;
    private final ScaleGestureDetector pinch;
    private final OnStation onStation;
    private MetroNetwork model;
    private MetroDiagramLayout layout;
    private MetroGeography geography=new MetroGeography();
    private MetroNetwork.Stop selected, origin;
    private double referenceLat, referenceLon, longitudeFactor, userLat, userLon;
    private float userAccuracy, zoom = 1, fit = 1, x, y;
    private boolean userVisible;

    MetroNetworkView(Context context, OnStation onStation) {
        super(context);
        this.onStation = onStation;
        setContentDescription("地铁线网图。可拖动或双指缩放，也可通过搜索选择站点");
        pan = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!pinch.isInProgress()) {
                    x -= dx;
                    y -= dy;
                    clampPan();
                    invalidate();
                }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (pinch.isInProgress() || model == null) return false;
                MetroNetwork.Stop hit = null;
                for (Map.Entry<MetroNetwork.Stop, RectF> entry : stationLabels.entrySet()) {
                    if (entry.getValue().contains(event.getX(), event.getY())) {
                        performClick();
                        onStation.select(entry.getKey());
                        return true;
                    }
                }
                boolean overview = zoom < fit * 1.5f;
                if (overview && (layout == null || (!layout.routed() && !layout.authored()))) return false;
                float best = Float.MAX_VALUE;
                for (MetroNetwork.Stop stop : model.stops) {
                    if (overview && stop != selected && stop != origin) continue;
                    PointF point = point(stop);
                    if(point==null)continue;
                    float dx = x + point.x * zoom - event.getX(), dy = y + point.y * zoom - event.getY();
                    float distance = dx * dx + dy * dy;
                    float radius=dp(26);
                    if(layout!=null&&layout.authored())radius=Math.max(radius,
                            (stop.transfer()?layout.authoredTransferRadius:layout.authoredStopRadius)*zoom+dp(4));
                    if (distance < radius*radius && distance < best) { best = distance; hit = stop; }
                }
                if (hit == null) return false;
                performClick();
                onStation.select(hit);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                scaleBy(1.7f, event.getX(), event.getY());
                return true;
            }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    void setNetwork(MetroNetwork network, MetroDistricts districts) {
        setNetwork(network, districts, null);
    }

    void setGeography(MetroGeography geography){this.geography=geography;}
    MetroNetwork.Stop geographicNearest(double lat,double lon,double radius){return geography.nearest(lat,lon,radius);}

    void setNetwork(MetroNetwork network, MetroDistricts districts, MetroDiagramLayout layout) {
        if(model!=network)backgroundPrepared=false;
        model = network;
        this.layout = layout;
        if (network.stops.isEmpty()) return;
        MetroNetwork.Stop first = network.stops.get(0);
        referenceLat = first.lat;
        referenceLon = first.lon;
        longitudeFactor = 111.32 * Math.cos(Math.toRadians(referenceLat));
        stationPoints.clear();
        for(MetroNetwork.Stop stop:network.stops) {
            PointF position;
            if(layout!=null)position=layout.stops.get(stop);
            else {
                MetroGeography.Position p=geography.positions.get(stop);
                position=p==null?null:new PointF(east(p.lon),south(p.lat));
            }
            if(position!=null)stationPoints.put(stop,position);
        }
        boolean bounded=false;
        bounds.set(0,0,1,1);
        for (MetroNetwork.Stop stop : network.stops) {
            PointF location = point(stop);
            if(location==null)continue;
            float east = location.x, north = location.y;
            if(!bounded){bounds.set(east,north,east,north);bounded=true;}
            bounds.set(Math.min(bounds.left, east), Math.min(bounds.top, north),
                    Math.max(bounds.right, east), Math.max(bounds.bottom, north));
        }
        tracks.clear();
        sharedTracks.clear();
        edges.clear();
        authoredColours.clear();
        if(layout!=null&&layout.authored()) {
            for(MetroDiagramLayout.AuthoredTrack geometry:layout.authoredTracks) {
                Path path=new Path();List<RailSegment> segments=new ArrayList<>();
                PointF previous=null;
                for(PointF node:geometry.points) {
                    if(previous==null)path.moveTo(node.x,node.y);
                    else {path.lineTo(node.x,node.y);segments.add(new RailSegment(previous.x,previous.y,node.x,node.y));}
                    bounds.union(node.x,node.y);previous=node;
                }
                tracks.add(new Track(path,geometry.colour,geometry.service,segments));edges.addAll(segments);
                authoredColours.put(geometry.service,geometry.colour);
            }
            for(MetroDiagramLayout.DisplayStop stop:layout.displayStops)bounds.union(stop.point.x,stop.point.y);
        } else {
        if (layout != null && layout.routed()) {
            Map<String, Integer> colours = new java.util.HashMap<>();
            for (MetroDataSource.MetroLine line : network.lines.keySet()) colours.put(line.networkId, lineColour(line));
            buildSharedTracks(colours);
        }
        for (Map.Entry<MetroDataSource.MetroLine, List<MetroNetwork.Node>> line : network.lines.entrySet()) {
            Path path = new Path();
            List<RailSegment> segments = new ArrayList<>();
            if (layout != null) {
                List<PointF> geometry = layout.lines.get(line.getKey());
                for (int i = 0; i < geometry.size(); i++) {
                    PointF node = geometry.get(i);
                    if (i == 0) path.moveTo(node.x, node.y); else {
                        PointF previousPoint = geometry.get(i - 1);
                        path.lineTo(node.x, node.y);
                        segments.add(new RailSegment(previousPoint.x, previousPoint.y, node.x, node.y));
                    }
                    bounds.union(node.x, node.y);
                }
                if (layout.routed()) {
                    path.rewind();
                    PointF last = null;
                    List<MetroDiagramLayout.RoutedEdge> intervals = layout.routedLines.get(line.getKey());
                    List<MetroNetwork.Node> nodes = line.getValue();
                    for (int i = 0; i < intervals.size(); i++) {
                        if (intervals.get(i).shared()) { last = null; continue; }
                        List<PointF> section = layout.between(nodes.get(i), nodes.get((i + 1) % nodes.size()));
                        if (section.size() < 2) { last = null; continue; }
                        for (int j = 0; j < section.size(); j++) {
                            PointF point = section.get(j);
                            if (j == 0) {
                                if (!samePoint(last, point)) path.moveTo(point.x, point.y);
                            } else {
                                path.lineTo(point.x, point.y);
                            }
                            last = point;
                        }
                    }
                }
            } else {
                PointF previous = null;
                for (MetroGeography.Position sample : geography.path(line.getKey(),line.getValue())) {
                    if (sample == null) { previous = null; continue; }
                    PointF next = new PointF(east(sample.lon),south(sample.lat));
                    if (previous == null) path.moveTo(next.x, next.y);
                    else {
                        path.lineTo(next.x, next.y);
                        segments.add(new RailSegment(previous.x, previous.y, next.x, next.y));
                    }
                    if(!bounded){bounds.set(next.x,next.y,next.x,next.y);bounded=true;}else bounds.union(next.x,next.y);
                    previous = next;
                }
            }
            edges.addAll(segments);
            tracks.add(new Track(path, lineColour(line.getKey()), displayLine(line.getKey()), segments));
        }
        }
        edgeIndex.clear();
        edgeCell = Math.max(.01f, Math.max(bounds.width(), bounds.height()) / 48f);
        for (RailSegment edge : edges) {
            for (int cx = cell(Math.min(edge.x1, edge.x2)); cx <= cell(Math.max(edge.x1, edge.x2)); cx++)
                for (int cy = cell(Math.min(edge.y1, edge.y2)); cy <= cell(Math.max(edge.y1, edge.y2)); cy++) {
                    long key = cellKey(cx, cy);
                    List<RailSegment> bucket = edgeIndex.get(key);
                    if (bucket == null) { bucket = new ArrayList<>(); edgeIndex.put(key, bucket); }
                    bucket.add(edge);
                }
        }
        districtLabels = districts.labels;
        if(layout==null&&!backgroundPrepared) {
        districtOutline.reset();
        for (double[] path : districts.paths) {
            districtOutline.moveTo(east(path[1]), south(path[0]));
            for (int i = 2; i < path.length; i += 2)
                districtOutline.lineTo(east(path[i + 1]), south(path[i]));
        }
        land.reset();
        land.setFillType(Path.FillType.EVEN_ODD);
        for (double[] polygon : districts.land) appendGeography(land, polygon, true);
        water.reset();water.setFillType(Path.FillType.EVEN_ODD);
        for(double[] polygon:districts.water)appendGeography(water,polygon,true);
        coast.reset();for(double[] line:districts.coastlines)appendGeography(coast,line,false);
        rivers.clear();
        List<String> kinds=new ArrayList<>();
        for(int i=0;i<districts.rivers.size();i++) {
            String kind=i<districts.riverKinds.size()?districts.riverKinds.get(i):"river";
            if(!"stream".equals(kind)&&!"canal".equals(kind))kind="river";
            int index=kinds.indexOf(kind);
            if(index<0){index=kinds.size();kinds.add(kind);rivers.add(new Path());}
            appendGeography(rivers.get(index),districts.rivers.get(i),false);
        }
        riverKinds=kinds;
        backgroundPrepared=true;
        }
        Path majorRivers=new Path();
        if(layout==null)for(int i=0;i<rivers.size();i++)if("river".equals(riverKinds.get(i)))majorRivers.addPath(rivers.get(i));
        backdrop.set(land,water,majorRivers,coast,districtOutline,layout==null);
        reset();
    }

    private void appendGeography(Path path, double[] points, boolean close) {
        if (points.length < 4) return;
        path.moveTo(east(points[1]), south(points[0]));
        for (int i = 2; i < points.length; i += 2) path.lineTo(east(points[i + 1]), south(points[i]));
        if (close) path.close();
    }

    void reset() {
        if (getWidth() == 0 || getHeight() == 0) return;
        fit = Math.max(.001f, Math.min((getWidth() - dp(34)) / Math.max(1f, bounds.width()),
                (getHeight() - dp(86)) / Math.max(1f, bounds.height())));
        zoom = fit;
        x = getWidth() / 2f - bounds.centerX() * zoom;
        y = getHeight() / 2f + dp(20) - bounds.centerY() * zoom;
        invalidate();
    }

    private static boolean samePoint(PointF first, PointF second) {
        return first != null && second != null && first.x == second.x && first.y == second.y;
    }

    private void buildSharedTracks(Map<String, Integer> colours) {
        Map<List<String>, List<MetroDiagramLayout.RoutedEdge>> groups = new java.util.LinkedHashMap<>();
        for (MetroDiagramLayout.RoutedEdge edge : layout.sharedEdges) {
            List<MetroDiagramLayout.RoutedEdge> group = groups.get(edge.services);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(edge.services, group);
            }
            group.add(edge);
        }
        for (Map.Entry<List<String>, List<MetroDiagramLayout.RoutedEdge>> entry : groups.entrySet()) {
            int[] stripes = new int[entry.getKey().size()];
            for (int i = 0; i < stripes.length; i++) stripes[i] = colours.get(entry.getKey().get(i));
            Set<MetroDiagramLayout.RoutedEdge> used = new HashSet<>();
            for (MetroDiagramLayout.RoutedEdge seed : entry.getValue()) {
                if (!used.add(seed)) continue;
                List<PointF> points = new ArrayList<>(seed.points);
                for (int direction = 0; direction < 2; direction++) {
                    MetroDiagramLayout.RoutedEdge next;
                    while ((next = sharedContinuation(points.get(points.size() - 1), entry.getValue(), used)) != null) {
                        PointF end = points.get(points.size() - 1);
                        boolean forward = samePoint(end, next.points.get(0));
                        for (int i = 1; i < next.points.size(); i++)
                            points.add(next.points.get(forward ? i : next.points.size() - 1 - i));
                        used.add(next);
                    }
                    Collections.reverse(points);
                }
                sharedTracks.add(new SharedTrack(points, stripes));
            }
        }
    }

    private static MetroDiagramLayout.RoutedEdge sharedContinuation(PointF end,
            List<MetroDiagramLayout.RoutedEdge> group, Set<MetroDiagramLayout.RoutedEdge> used) {
        int degree = 0;
        MetroDiagramLayout.RoutedEdge following = null;
        for (MetroDiagramLayout.RoutedEdge edge : group) {
            if (samePoint(end, edge.points.get(0)) || samePoint(end, edge.points.get(edge.points.size() - 1))) {
                degree++;
                if (!used.contains(edge)) following = edge;
            }
        }
        return degree == 2 ? following : null;
    }

    void zoom(float factor) { scaleBy(factor, getWidth() / 2f, getHeight() / 2f); }

    boolean focusStop(MetroNetwork.Stop stop) {
        PointF point=point(stop);if(point==null)return false;
        focusPoint(point);return true;
    }

    void focus(double lat, double lon) {
        PointF location = locationPoint(lat, lon);
        if(location==null)return;
        focusPoint(location);
    }

    private void focusPoint(PointF location) {
        zoom = Math.max(zoom, fit * 5f);
        x = getWidth() / 2f - location.x * zoom;
        y = getHeight() / 2f - location.y * zoom;
        clampPan();
        invalidate();
    }

    void showSelection(MetroNetwork.Stop selection, MetroNetwork.Stop start) {
        selected = selection;
        origin = start;
        invalidate();
    }

    void showRoute(MetroNetwork.Journey route) {
        routeTracks.clear();
        routeStops.clear();
        if(route==null){invalidate();return;}
        RectF extents=new RectF();boolean bounded=false;
        for(List<MetroNetwork.Node> leg:route.tracks) {
            for(MetroNetwork.Node node:leg)routeStops.add(node.stop);
            if(leg.size()<2)continue;
            Path path=new Path();boolean drawn=false;
            PointF previous=null;
            for(int i=1;i<leg.size();i++) {
                List<PointF> section=new ArrayList<>();
                if(layout==null) {
                    for(MetroGeography.Position sample:geography.section(leg.get(i-1),leg.get(i)))
                        section.add(new PointF(east(sample.lon),south(sample.lat)));
                }else section=layout.between(leg.get(i-1),leg.get(i));
                if(section.size()<2){previous=null;continue;}
                if(layout==null||!layout.routed())previous=null;
                // Only adjacent schematic intervals with the same endpoint
                // share a contour. Missing samples still break the path.
                for(int j=0;j<section.size();j++) {
                    PointF sample=section.get(j);
                    if(sample==null){previous=null;continue;}
                    if(previous==null||(j==0&&!samePoint(previous,sample)))path.moveTo(sample.x,sample.y);
                    else if(!samePoint(previous,sample)){path.lineTo(sample.x,sample.y);drawn=true;}
                    previous=sample;
                    if(!bounded){extents.set(sample.x,sample.y,sample.x,sample.y);bounded=true;}
                    else extents.union(sample.x,sample.y);
                }
            }
            if(drawn)routeTracks.add(new Track(path,routeColour(leg.get(0).line),"",Collections.emptyList()));
        }
        if(bounded&&getHeight()>dp(64)) {
            zoom=Math.max(fit,Math.min(fit*24,Math.min((getWidth()-dp(64))/Math.max(1f,extents.width()),
                    (getHeight()-dp(64))/Math.max(1f,extents.height()))));
            x=getWidth()/2f-extents.centerX()*zoom;y=getHeight()/2f-extents.centerY()*zoom;clampPan();
        }
        invalidate();
    }

    void showLocation(double lat, double lon, float accuracy, boolean visible) {
        userLat = lat;
        userLon = lon;
        userAccuracy = accuracy;
        userVisible = visible;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (oldW <= 0 || oldH <= 0 || model == null) {
            reset();
        } else {
            PointF anchor = selected == null ? null : point(selected);
            float horizontal = anchor == null ? .5f : Math.max(0, Math.min(1, (x+anchor.x*zoom)/oldW));
            float vertical = anchor == null ? .5f : Math.max(0, Math.min(1, (y+anchor.y*zoom)/oldH));
            x += (w - oldW) * horizontal;
            y += (h - oldH) * vertical;
            clampPan();
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)moving=true;
        pinch.onTouchEvent(event);
        pan.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL) {
            moving=false;invalidate();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }

    private void scaleBy(float factor, float centerX, float centerY) {
        if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0) return;
        float maxZoom=fit*(layout!=null&&layout.authored()?48:24);
        float next = Math.max(fit, Math.min(maxZoom, zoom * factor));
        x = centerX - (centerX - x) * next / zoom;
        y = centerY - (centerY - y) * next / zoom;
        zoom = next;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        x = clampAxis(x, getWidth(), bounds.left, bounds.right);
        y = clampAxis(y, getHeight(), bounds.top, bounds.bottom);
    }

    private float clampAxis(float shift, int available, float minimum, float maximum) {
        float padding = dp(22);
        float low = available - padding - maximum * zoom, high = padding - minimum * zoom;
        if (low > high) return available / 2f - (maximum + minimum) * zoom / 2f;
        return Math.max(low, Math.min(high, shift));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(layout==null?(Ui.NIGHT?0xff202c28:0xfff3f6f1):Ui.SURFACE);
        if (model == null) return;
        if(layout!=null&&layout.authored()){drawAuthored(canvas);return;}
        if(layout==null)backdrop.draw(canvas,x,y,zoom,moving);
        if(layout==null)drawDistrictLabels(canvas);
        canvas.save();canvas.translate(x,y);canvas.scale(zoom,zoom);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setAlpha(255);
        if (layout != null && layout.routed()) {
            float radius = Math.min(4f, dp(6) / zoom);
            if (radius != schematicCornerRadius) {
                schematicCorners = new CornerPathEffect(radius);
                schematicCornerRadius = radius;
            }
            paint.setPathEffect(schematicCorners);
        }
        paint.setStrokeWidth(dp(3.25f) / zoom);
        viewport.set((-x-dp(12))/zoom,(-y-dp(12))/zoom,(getWidth()-x+dp(12))/zoom,(getHeight()-y+dp(12))/zoom);
        for (Track track : tracks) {
            if(!RectF.intersects(viewport,track.bounds))continue;
            paint.setColor(track.colour);
            if (!routeTracks.isEmpty()) paint.setAlpha(Ui.NIGHT?35:26);
            canvas.drawPath(track.path, paint);
        }
        paint.setStrokeWidth(dp(2.75f) / zoom);
        for (SharedTrack track : sharedTracks) {
            track.draw(canvas, paint, dp(3.25f) / zoom, routeTracks.isEmpty() ? 255 : Ui.NIGHT ? 35 : 26);
        }
        for (Track track : routeTracks) {
            paint.setStrokeWidth(dp(8) / zoom);
            paint.setColor(Ui.SURFACE);
            canvas.drawPath(track.path, paint);
            paint.setStrokeWidth(dp(5) / zoom);
            paint.setColor(track.colour);
            canvas.drawPath(track.path, paint);
        }
        canvas.restore();
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        for (MetroNetwork.Stop stop : model.stops)
            if (mapped(stop) && (zoom >= fit * 1.5f || stop == selected || stop == origin))
                drawStop(canvas, stop);

        labels.clear();
        stationLabels.clear();
        if (selected != null) drawLabel(canvas, selected, true);
        if (origin != null && origin != selected) drawLabel(canvas, origin, true);
        for (MetroNetwork.Stop stop : model.stops)
            if (routeStops.contains(stop) && stop != selected && stop != origin) drawLabel(canvas, stop, false);
        if (routeTracks.isEmpty() && zoom >= fit * 1.8f) {
            for (MetroNetwork.Stop stop : model.stops) {
                if (stop != selected && stop != origin && stop.transfer()) drawLabel(canvas, stop, false);
            }
        }
        for (MetroNetwork.Stop stop : model.stops)
            if (routeTracks.isEmpty() && stop != selected && stop != origin && zoom >= fit * 2.2f
                    && !stop.transfer() && mapped(stop))
                drawLabel(canvas, stop, false);
        if (routeTracks.isEmpty() && zoom >= fit * 4.2f) drawLineLabels(canvas);
        if (userVisible) drawUser(canvas);
    }

    /** 规整图模式下，只绘制确实拿到了图纸坐标的车站。 */
    private void drawAuthored(Canvas canvas) {
        authoredMarkers.clear();
        paint.setPathEffect(null);paint.setTextScaleX(1);paint.setAlpha(255);
        viewport.set((-x-dp(24))/zoom,(-y-dp(24))/zoom,(getWidth()-x+dp(24))/zoom,(getHeight()-y+dp(24))/zoom);
        canvas.save();canvas.translate(x,y);canvas.scale(zoom,zoom);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(layout.authoredStrokeWidth);
        for(Track track:tracks) {
            if(!RectF.intersects(viewport,track.bounds))continue;
            paint.setColor(track.colour);paint.setAlpha(routeTracks.isEmpty()?255:Ui.NIGHT?35:26);
            canvas.drawPath(track.path,paint);
        }
        paint.setAlpha(255);
        for(Track track:routeTracks) {
            if(!RectF.intersects(viewport,track.bounds))continue;
            paint.setStrokeWidth(layout.authoredStrokeWidth+8);paint.setColor(Ui.SURFACE);canvas.drawPath(track.path,paint);
            paint.setStrokeWidth(layout.authoredStrokeWidth+3);paint.setColor(track.colour);canvas.drawPath(track.path,paint);
        }
        if(zoom>=fit*1.5f) {
            for(MetroNetwork.Stop stop:model.stops)drawAuthoredStop(canvas,stop);
            for(MetroDiagramLayout.DisplayStop stop:layout.displayStops) {
                PointF point=stop.point;
                if(!viewport.contains(point.x,point.y))continue;
                rememberAuthoredMarker(point,layout.authoredStopRadius);
                paint.setStyle(Paint.Style.FILL);paint.setColor(Ui.SURFACE);canvas.drawCircle(point.x,point.y,layout.authoredStopRadius,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setColor(Ui.MUTED);
                canvas.drawCircle(point.x,point.y,layout.authoredStopRadius,paint);
            }
        } else {
            if(selected!=null)drawAuthoredStop(canvas,selected);
            if(origin!=null&&origin!=selected)drawAuthoredStop(canvas,origin);
        }
        canvas.restore();paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
        labels.clear();stationLabels.clear();
        float textSize=layout.authoredTextSize*zoom;
        boolean readable=textSize>=dp(10.5f);
        if(readable) {
            if(selected!=null)drawAuthoredLabel(canvas,selected,true);
            if(origin!=null&&origin!=selected)drawAuthoredLabel(canvas,origin,true);
            for(MetroNetwork.Stop stop:model.stops)
                if(stop!=selected&&stop!=origin)drawAuthoredLabel(canvas,stop,false);
            for(MetroDiagramLayout.DisplayStop stop:layout.displayStops)
                drawAuthoredDisplayLabel(canvas,stop);
            if(routeTracks.isEmpty())drawAuthoredBadges(canvas);
        } else {
            // The geometry remains proportional while zoomed out. Only the
            // selected station gets a readable screen-sized label at overview.
            if(selected!=null)drawAuthoredSelectionLabel(canvas,selected);
            if(origin!=null&&origin!=selected)drawAuthoredSelectionLabel(canvas,origin);
        }
        if(userVisible)drawUser(canvas);
        paint.setTextScaleX(1);paint.setAlpha(255);
    }

    private void drawAuthoredStop(Canvas canvas,MetroNetwork.Stop stop) {
        PointF point=point(stop);if(point==null||!viewport.contains(point.x,point.y))return;
        boolean emphasized=stop==selected||stop==origin;
        boolean muted=!routeTracks.isEmpty()&&!routeStops.contains(stop)&&!emphasized;
        float radius=stop.transfer()?layout.authoredTransferRadius:layout.authoredStopRadius;
        if(emphasized)radius=Math.max(radius,dp(8.5f)/zoom);
        rememberAuthoredMarker(point,radius);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Ui.SURFACE);paint.setAlpha(255);
        canvas.drawCircle(point.x,point.y,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(emphasized?Math.max(3.5f,dp(2)/zoom):2.2f);
        paint.setColor(emphasized?Ui.PRIMARY:Ui.TEXT);paint.setAlpha(muted?55:255);
        canvas.drawCircle(point.x,point.y,radius,paint);paint.setAlpha(255);
    }

    private void authoredTextPaint() {
        paint.setTypeface(authoredTypeface);
        paint.setTextSize(StationLabelPlacement.textSize(layout.authoredTextSize,zoom,
                getResources().getDisplayMetrics().scaledDensity));paint.setTextScaleX(1);
    }

    private String authoredName(MetroNetwork.Stop stop) {
        String name=layout.authoredNames.get(stop);return name==null?stop.name:name;
    }

    private void drawAuthoredLabel(Canvas canvas,MetroNetwork.Stop stop,boolean important) {
        PointF point=point(stop);if(point==null)return;
        float sx=x+point.x*zoom,sy=y+point.y*zoom;
        float radius=(stop.transfer()?layout.authoredTransferRadius:layout.authoredStopRadius)*zoom;
        if(!authoredMarkerVisible(sx,sy,radius))return;
        MetroDiagramLayout.LabelAnchor anchor=layout.authoredLabels.get(stop);
        authoredTextPaint();String text=authoredName(stop);float width=paint.measureText(text);
        float anchorScale=Math.min(zoom,paint.getTextSize()/layout.authoredTextSize);
        float baseline=anchor==null?sy-(paint.ascent()+paint.descent())/2:sy+anchor.dy*anchorScale;
        float textX=anchor==null?sx+radius+8*zoom:sx+anchor.dx*anchorScale;
        float left=textX-(anchor!=null&&"middle".equals(anchor.anchor)?width/2:anchor!=null&&"end".equals(anchor.anchor)?width:0);
        if(drawPlacedAuthoredName(canvas,text,sx,sy,left,baseline,radius,important?Ui.PRIMARY:Ui.TEXT,
                !routeTracks.isEmpty()&&!routeStops.contains(stop)&&!important?75:255)) {
            stationLabels.put(stop,new RectF(label));return;
        }
        if(important)drawAuthoredSelectionLabel(canvas,stop);
    }

    private void rememberAuthoredMarker(PointF point,float radius) {
        float sx=x+point.x*zoom,sy=y+point.y*zoom,r=radius*zoom;
        authoredMarkers.add(new RectF(sx-r,sy-r,sx+r,sy+r));
    }

    private boolean authoredMarkerVisible(float sx,float sy,float radius) {
        return sx+radius>=0&&sy+radius>=0&&sx-radius<=getWidth()&&sy-radius<=getHeight();
    }

    /** Keep the approved anchor where it fits; otherwise search beside the same dot. */
    private boolean drawPlacedAuthoredName(Canvas canvas,String text,float sx,float sy,float left,
                                           float baseline,float radius,int colour,int alpha) {
        String[] rows={text};float width=paint.measureText(text),extra=0;
        StationLabelPlacement.Box box=placeAuthoredName(sx,sy,left,baseline,width,extra,radius);
        int count=text.codePointCount(0,text.length());
        if(box==null&&count>=4) {
            int split=text.offsetByCodePoints(0,(count+1)/2);
            rows=new String[]{text.substring(0,split),text.substring(split)};
            width=Math.max(paint.measureText(rows[0]),paint.measureText(rows[1]));extra=paint.getFontSpacing();
            box=placeAuthoredName(sx,sy,sx-width/2,baseline,width,extra,radius);
        }
        if(box==null&&count>=5) {
            // Large system fonts can leave only a two-character-wide corridor.
            int perRow=(count+2)/3;
            rows=new String[(count+perRow-1)/perRow];width=0;
            for(int i=0,from=0;i<rows.length;i++) {
                int to=text.offsetByCodePoints(from,Math.min(perRow,count-i*perRow));
                rows[i]=text.substring(from,to);from=to;width=Math.max(width,paint.measureText(rows[i]));
            }
            extra=(rows.length-1)*paint.getFontSpacing();
            box=placeAuthoredName(sx,sy,sx-width/2,baseline,width,extra,radius);
        }
        if(box==null)return false;
        for(int i=0;i<rows.length;i++)drawAuthoredText(canvas,rows[i],
                box.left+(width-paint.measureText(rows[i]))/2,box.baseline+i*paint.getFontSpacing(),colour,alpha);
        label.set(box.left-2*zoom,box.top,box.left+width+2*zoom,box.bottom);
        labels.add(new RectF(label));return true;
    }

    private StationLabelPlacement.Box placeAuthoredName(float sx,float sy,float left,float baseline,
                                                       float width,float extra,float radius) {
        // Reserve the text halo and screen inset before clamping an edge label.
        float inset=dp(3)+2*zoom;
        StationLabelPlacement.Box box=StationLabelPlacement.place(sx-inset,sy-inset,left-inset,baseline-inset,
                width,paint.ascent(),paint.descent(),extra,getWidth()-2*inset,getHeight()-2*inset,
                radius+4*zoom,radius+4*zoom,Math.max(dp(4),6*zoom),(l,t,r,b)-> {
                    label.set(l+inset-2*zoom,t+inset,r+inset+2*zoom,b+inset);
                    return authoredLabelFits(label,3);
                });
        return box==null?null:new StationLabelPlacement.Box(box.left+inset,box.baseline+inset,
                width,paint.ascent(),paint.descent(),extra);
    }

    private void drawAuthoredText(Canvas canvas,String text,float left,float baseline,int colour,int alpha) {
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.min(3*zoom,dp(2.2f)));paint.setColor(Ui.SURFACE);paint.setAlpha(255);
        canvas.drawText(text,left,baseline,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(colour);paint.setAlpha(alpha);canvas.drawText(text,left,baseline,paint);paint.setAlpha(255);
    }

    private void drawAuthoredDisplayLabel(Canvas canvas,MetroDiagramLayout.DisplayStop stop) {
        if(stop.label==null)return;float sx=x+stop.point.x*zoom,sy=y+stop.point.y*zoom;
        if(!authoredMarkerVisible(sx,sy,layout.authoredStopRadius*zoom))return;
        authoredTextPaint();float width=paint.measureText(stop.name);
        float anchorScale=Math.min(zoom,paint.getTextSize()/layout.authoredTextSize);
        float textX=sx+stop.label.dx*anchorScale;
        float left=textX-("middle".equals(stop.label.anchor)?width/2:"end".equals(stop.label.anchor)?width:0);
        float baseline=sy+stop.label.dy*anchorScale;
        drawPlacedAuthoredName(canvas,stop.name,sx,sy,left,baseline,layout.authoredStopRadius*zoom,Ui.MUTED,255);
    }

    private void drawAuthoredSelectionLabel(Canvas canvas,MetroNetwork.Stop stop) {
        PointF point=point(stop);if(point==null)return;
        float sx=x+point.x*zoom,sy=y+point.y*zoom;if(!visible(sx,sy))return;
        paint.setTypeface(authoredTypeface);paint.setTextScaleX(1);paint.setTextSize(17*getResources().getDisplayMetrics().scaledDensity);
        placeStationLabel(canvas,stop,true,new String[]{authoredName(stop)},sx,sy,dp(14),dp(14),0,40);
    }

    private boolean authoredLabelFits(RectF box,float padding) {
        if(box.left<dp(3)||box.top<dp(3)||box.right>getWidth()-dp(3)||box.bottom>getHeight()-dp(3))return false;
        for(RectF used:labels)if(RectF.intersects(box,used))return false;
        for(RectF marker:authoredMarkers) {
            float cx=marker.centerX(),cy=marker.centerY(),radius=marker.width()/2+2*zoom;
            float dx=Math.max(box.left,Math.min(cx,box.right))-cx;
            float dy=Math.max(box.top,Math.min(cy,box.bottom))-cy;
            if(dx*dx+dy*dy<radius*radius)return false;
        }
        float clearance=layout.authoredStrokeWidth/2+padding;
        trackClearance.set((box.left-x)/zoom-clearance,(box.top-y)/zoom-clearance,
                (box.right-x)/zoom+clearance,(box.bottom-y)/zoom+clearance);
        for(int cx=cell(trackClearance.left);cx<=cell(trackClearance.right);cx++)
            for(int cy=cell(trackClearance.top);cy<=cell(trackClearance.bottom);cy++) {
                List<RailSegment> bucket=edgeIndex.get(cellKey(cx,cy));
                if(bucket!=null)for(RailSegment edge:bucket)if(edge.crosses(trackClearance))return false;
            }
        return true;
    }

    private void drawAuthoredBadges(Canvas canvas) {
        authoredTextPaint();
        for(MetroDiagramLayout.Badge badge:layout.authoredBadges) {
            float left=x+(badge.x-badge.width/2)*zoom,top=y+(badge.y-layout.authoredTextSize)*zoom;
            label.set(left,top,left+badge.width*zoom,top+badge.height*zoom);
            if(!authoredLabelFits(label,8))continue;
            boolean collision=false;
            for(MetroNetwork.Stop stop:model.stops) {
                PointF point=point(stop);if(point==null)continue;
                float radius=(stop.transfer()?layout.authoredTransferRadius:layout.authoredStopRadius)*zoom;
                float sx=x+point.x*zoom,sy=y+point.y*zoom;
                float dx=Math.max(label.left,Math.min(sx,label.right))-sx;
                float dy=Math.max(label.top,Math.min(sy,label.bottom))-sy;
                if(dx*dx+dy*dy<(radius+8*zoom)*(radius+8*zoom)){collision=true;break;}
            }
            if(collision)continue;
            paint.setColor(badge.colour);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(label,5*zoom,5*zoom,paint);
            float available=Math.max(1,(badge.width-16)*zoom),width=paint.measureText(badge.text);
            paint.setTextScaleX(Math.min(1,available/Math.max(1,width)));
            width=paint.measureText(badge.text);paint.setColor(ReadableColors.foreground(badge.colour));
            canvas.drawText(badge.text,x+badge.x*zoom-width/2,y+badge.y*zoom,paint);
            paint.setTextScaleX(1);labels.add(new RectF(label));
        }
    }

    private int routeColour(MetroDataSource.MetroLine line) {
        if(layout!=null&&layout.authored()) {
            String service=line.networkId.replace("广州14号线支线","广州14号线");
            Integer colour=authoredColours.get(service);if(colour!=null)return colour;
        }
        return lineColour(line);
    }

    private boolean mapped(MetroNetwork.Stop stop) {
        return point(stop)!=null;
    }

    private void drawStop(Canvas canvas, MetroNetwork.Stop stop) {
        PointF point = point(stop);
        if (point == null) return;
        float sx = x + point.x * zoom, sy = y + point.y * zoom;
        if (!visible(sx, sy)) return;
        boolean emphasized = stop == selected || stop == origin;
        boolean muted=!routeTracks.isEmpty()&&!routeStops.contains(stop)&&!emphasized;
        float radius = dp(emphasized ? 8.5f : stop.transfer() ? (zoom < fit * 1.5f ? 4.2f : 6.1f) : 3f);
        List<PointF> ports=layout==null?null:layout.ports.get(stop);
        if(ports!=null&&ports.size()>1) {
            PointF first=ports.get(0),last=ports.get(1);float widest=-1;
            for(PointF a:ports)for(PointF b:ports) {
                float squared=(a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y);
                if(squared>widest){widest=squared;first=a;last=b;}
            }
            float x1=x+first.x*zoom,y1=y+first.y*zoom,x2=x+last.x*zoom,y2=y+last.y*zoom;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(radius*2);paint.setColor(emphasized?Ui.PRIMARY:Ui.TEXT);
            if(muted)paint.setAlpha(45);canvas.drawLine(x1,y1,x2,y2,paint);
            paint.setStrokeWidth(Math.max(dp(2),radius*2-dp(2.5f)));paint.setColor(Ui.SURFACE);
            if(muted)paint.setAlpha(100);canvas.drawLine(x1,y1,x2,y2,paint);
            paint.setStyle(Paint.Style.FILL);return;
        }
        paint.setColor(emphasized ? Ui.PRIMARY : stop.transfer() ? Ui.TEXT : lineColour(stop.services.get(0).line));
        if(muted)paint.setAlpha(45);
        canvas.drawCircle(sx, sy, radius, paint);
        paint.setColor(Ui.SURFACE);
        if(muted)paint.setAlpha(100);
        canvas.drawCircle(sx, sy, Math.max(dp(1.2f), radius - dp(emphasized ? 2.5f : 1.25f)), paint);
    }

    private void drawLabel(Canvas canvas, MetroNetwork.Stop stop, boolean important) {
        PointF point = point(stop);
        if(point==null)return;
        float sx = x + point.x * zoom, sy = y + point.y * zoom;
        if (!visible(sx, sy)) return;
        paint.setTypeface(stationTypeface);
        paint.setTextSize((important ? 17f : 15f) * getResources().getDisplayMetrics().scaledDensity);
        float horizontalGap = dp(important ? 13 : stop.transfer() ? 11 : 8);
        float verticalGap = horizontalGap;
        if(layout!=null) {
            List<PointF> ports=layout.ports.get(stop);
            if (ports != null) for (PointF port : ports) {
                horizontalGap = Math.max(horizontalGap, Math.abs(port.x-point.x)*zoom+dp(8));
                verticalGap = Math.max(verticalGap, Math.abs(port.y-point.y)*zoom+dp(8));
            }
        }
        if (placeStationLabel(canvas, stop, important, new String[]{stop.name},
                sx, sy, horizontalGap, verticalGap, 0, 24)) return;
        // Short horizontal rows fit beside a diagonal or several intersecting
        // rails without moving the station name toward a neighbouring stop.
        int count = stop.name.codePointCount(0, stop.name.length());
        if (count >= 4 && (important || zoom >= fit * 4f)) {
            int split = stop.name.offsetByCodePoints(0, (count + 1) / 2);
            String[] rows = {stop.name.substring(0, split), stop.name.substring(split)};
            if (placeStationLabel(canvas, stop, important, rows,
                    sx, sy, horizontalGap, verticalGap, 0, 24)) return;
        }
        // Dense interchanges may need a little more space. Only try these
        // outer slots after the compact single- and two-row labels fail.
        placeStationLabel(canvas, stop, important, new String[]{stop.name},
                sx, sy, horizontalGap, verticalGap, 24, 40);
    }

    private boolean placeStationLabel(Canvas canvas, MetroNetwork.Stop stop, boolean important,
                                      String[] rows, float sx, float sy, float horizontalGap, float verticalGap,
                                      int firstAttempt, int attempts) {
        float width = 0;
        for (String row : rows) width = Math.max(width, paint.measureText(row));
        float extraHeight = (rows.length - 1) * paint.getFontSpacing();
        float textHeight = paint.descent() - paint.ascent() + extraHeight;
        // Every mode gets close cardinal and quadrant positions. Geographic
        // diagonal tracks previously had only four centred slots and lost names.
        for (int attempt = firstAttempt; attempt < attempts; attempt++) {
            int slot = attempt % 8;
            boolean corner = slot >= 4;
            int side = slot % 4;
            float horizontal = horizontalGap + dp(4) * (attempt / 8);
            float vertical = verticalGap + dp(4) * (attempt / 8);
            float left = side < 2 ? sx - width / 2 : side == 2 ? sx + horizontal : sx - width - horizontal;
            float baseline = side == 0 ? sy - vertical - paint.descent() - extraHeight
                    : side == 1 ? sy + vertical - paint.ascent() : sy - textHeight / 2 - paint.ascent();
            if (corner) {
                left = side % 2 == 0 ? sx + horizontal : sx - width - horizontal;
                baseline = side < 2 ? sy - vertical - paint.descent() - extraHeight
                        : sy + vertical - paint.ascent();
            }
            if (!corner) {
                // A small viewport-edge adjustment keeps the centred slot near
                // its dot. Large moves would make the name belong to another stop.
                float clamped = Math.max(dp(5), Math.min(left, getWidth() - width - dp(5)));
                if (Math.abs(clamped - left) <= dp(8)) left = clamped;
            }
            if (!labelFits(left, baseline, width, extraHeight)) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setColor(Ui.RAISED);
            for (int i = 0; i < rows.length; i++)
                canvas.drawText(rows[i], left + (width-paint.measureText(rows[i])) / 2,
                        baseline + i * paint.getFontSpacing(), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(important ? Ui.PRIMARY : Ui.TEXT);
            for (int i = 0; i < rows.length; i++)
                canvas.drawText(rows[i], left + (width-paint.measureText(rows[i])) / 2,
                        baseline + i * paint.getFontSpacing(), paint);
            labels.add(new RectF(label));
            stationLabels.put(stop, new RectF(label));
            return true;
        }
        return false;
    }

    private boolean labelFits(float left, float baseline, float width) {
        return labelFits(left, baseline, width, 0);
    }

    private boolean labelFits(float left, float baseline, float width, float extraHeight) {
        label.set(left - dp(2), baseline + paint.ascent(), left + width + dp(2),
                baseline + paint.descent() + extraHeight);
        if (label.left < dp(3) || label.right > getWidth() - dp(3)
                || label.top < dp(3) || label.bottom > getHeight() - dp(3)) return false;
        for (RectF used : labels) if (RectF.intersects(label, used)) return false;
        float clearance = dp(!routeTracks.isEmpty() ? 5 : layout != null && layout.routed() ? 4.25f : 3);
        trackClearance.set((label.left - clearance - x) / zoom, (label.top - clearance - y) / zoom,
                (label.right + clearance - x) / zoom, (label.bottom + clearance - y) / zoom);
        for (int cx = cell(trackClearance.left); cx <= cell(trackClearance.right); cx++)
            for (int cy = cell(trackClearance.top); cy <= cell(trackClearance.bottom); cy++) {
                List<RailSegment> bucket = edgeIndex.get(cellKey(cx, cy));
                if (bucket != null) for (RailSegment edge : bucket) if (edge.crosses(trackClearance)) return false;
            }
        return true;
    }

    private int cell(float value) { return (int) Math.floor(value / edgeCell); }
    private static long cellKey(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }

    private void drawLineLabels(Canvas canvas) {
        visibleLineLabels.clear();
        paint.setTypeface(stationTypeface);
        paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);
        for (Track track : tracks) {
            if (visibleLineLabels.contains(track.name)) continue;
            float width = paint.measureText(track.name);
            for (RailSegment segment : track.segments) {
                float sx = x + (segment.x1 + segment.x2) * zoom / 2;
                float sy = y + (segment.y1 + segment.y2) * zoom / 2;
                if (!visible(sx, sy)) continue;
                if (lineLabel(canvas, track, sx - width / 2, sy - dp(8) - paint.descent(), width)
                        || lineLabel(canvas, track, sx - width / 2, sy + dp(8) - paint.ascent(), width)
                        || lineLabel(canvas, track, sx + dp(8), sy - paint.ascent() / 2, width)
                        || lineLabel(canvas, track, sx - dp(8) - width, sy - paint.ascent() / 2, width)) {
                    visibleLineLabels.add(track.name);
                    break;
                }
            }
        }
    }

    private boolean lineLabel(Canvas canvas, Track track, float left, float baseline, float width) {
        if (!labelFits(left, baseline, width)) return false;
        paint.setColor(track.colour);
        canvas.drawRoundRect(label, dp(4), dp(4), paint);
        paint.setColor(ReadableColors.foreground(track.colour));
        canvas.drawText(track.name, left, baseline, paint);
        labels.add(new RectF(label));
        return true;
    }

    private void drawDistrictLabels(Canvas canvas) {
        paint.setTextSize(15*getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        List<RectF> used=new ArrayList<>();
        for (MetroDistricts.Label district : districtLabels) {
            float sx = screenX(district.lon), sy = screenY(district.lat);
            if (!visible(sx, sy)) continue;
            float width = paint.measureText(district.name);
            float left=sx-width/2,baseline=sy-(paint.ascent()+paint.descent())/2;
            RectF box=new RectF(left,baseline+paint.ascent(),left+width,baseline+paint.descent());
            if(box.left<dp(4)||box.right>getWidth()-dp(4)||box.top<dp(4)||box.bottom>getHeight()-dp(4))continue;
            boolean collision=false;for(RectF previous:used)if(RectF.intersects(box,previous)){collision=true;break;}
            if(collision)continue;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(Ui.NIGHT?0xff202c28:0xfff3f6f1);
            canvas.drawText(district.name,left,baseline,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Ui.NIGHT ? 0xff9ba7b2 : 0xff7b858c);paint.setAlpha(205);
            canvas.drawText(district.name, left, baseline, paint);
            used.add(box);
        }
        paint.setAlpha(255);
    }

    private void drawUser(Canvas canvas) {
        PointF point = locationPoint(userLat, userLon);
        if(point==null)return;
        float sx = x + point.x * zoom, sy = y + point.y * zoom;
        if (!visible(sx, sy)) return;
        paint.setColor(0x342980f1);
        canvas.drawCircle(sx, sy, layout == null
                ? Math.max(dp(12), Math.min(dp(50), userAccuracy * zoom / 1000f)) : dp(13), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(sx, sy, dp(7), paint);
        paint.setColor(0xff2478e5);
        canvas.drawCircle(sx, sy, dp(4.8f), paint);
    }

    private boolean visible(float sx, float sy) {
        return sx >= 0 && sx <= getWidth() && sy >= 0 && sy <= getHeight();
    }

    private PointF point(MetroNetwork.Stop stop) {
        return stationPoints.get(stop);
    }

    private PointF locationPoint(double lat, double lon) {
        if (layout == null) return new PointF(east(lon), south(lat));
        MetroNetwork.Stop nearest = model.nearest(lat, lon, 8);
        if (nearest != null) return point(nearest);
        return new PointF(-100000, -100000);
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
    private float east(double lon) { return (float) ((lon - referenceLon) * longitudeFactor); }
    private float south(double lat) { return (float) ((referenceLat - lat) * 111.32); }
    private float screenX(double lon) { return x + east(lon) * zoom; }
    private float screenY(double lat) { return y + south(lat) * zoom; }

    static int lineColour(MetroDataSource.MetroLine line) {
        try { return Color.parseColor(MetroDataSource.normalizeColour(line.colour)); }
        catch (IllegalArgumentException invalid) { return Ui.PRIMARY; }
    }

    static String displayLine(MetroDataSource.MetroLine line) {
        return line.networkId.startsWith("佛山") ? line.networkId : MetroLineResolver.interchangeLabel(line.networkId);
    }
    @Override protected void onDetachedFromWindow(){backdrop.close();super.onDetachedFromWindow();}
}
