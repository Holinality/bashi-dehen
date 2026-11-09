package com.holi.prdbus;

import android.graphics.PointF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bind reviewed schematic geometry to the current service catalogue.
 * Geographic coordinates and routing identities are never replaced by pixels. */
final class AuthoredMetroDiagram {
    private static final class Station {
        final String rawName;
        final PointF point;
        final Set<String> services=new HashSet<>();
        final int index;
        Station(String name,PointF point,int index){this.rawName=name;this.point=point;this.index=index;}
    }
    private static final class Track {
        final MetroDiagramLayout.AuthoredTrack drawing;
        final float[] coordinates;
        final boolean closed;
        Track(MetroDiagramLayout.AuthoredTrack drawing){
            this.drawing=drawing;
            this.coordinates=new float[drawing.points.size()*2];
            for(int i=0;i<drawing.points.size();i++){
                coordinates[i*2]=drawing.points.get(i).x;coordinates[i*2+1]=drawing.points.get(i).y;
            }
            PointF first=drawing.points.get(0),last=drawing.points.get(drawing.points.size()-1);
            closed=Math.hypot(first.x-last.x,first.y-last.y)<.1;
        }
    }
    private static final class Interval {
        final List<PointF> points;
        final double score;
        Interval(List<PointF> points,double score){this.points=points;this.score=score;}
    }

    static MetroDiagramLayout decode(JSONObject source,MetroNetwork model) throws Exception {
        if(source==null||model==null||model.stops.isEmpty()||source.optInt("schema",1)!=1)return null;
        JSONArray stationRows=source.getJSONArray("stations"),trackRows=source.getJSONArray("authoredRoutes");
        if(stationRows.length()==0||stationRows.length()>3000||trackRows.length()==0||trackRows.length()>128)return null;
        JSONObject services=source.optJSONObject("stationServices"),labels=source.optJSONObject("labels");
        JSONObject names=source.optJSONObject("displayNames"),colours=source.optJSONObject("colors");
        Map<String,List<Station>> stationIndex=new HashMap<>();
        for(int i=0;i<stationRows.length();i++){
            JSONArray row=stationRows.getJSONArray(i);
            String name=row.getString(0);PointF point=point(row.getDouble(1),row.getDouble(2));
            Station station=new Station(name,point,i);
            JSONArray member=services==null?null:services.optJSONArray(String.valueOf(i));
            if(member!=null)for(int j=0;j<member.length();j++)station.services.add(family(member.getString(j)));
            String key=key(name);List<Station> matching=stationIndex.get(key);
            if(matching==null){matching=new ArrayList<>();stationIndex.put(key,matching);}
            matching.add(station);
        }
        MetroDiagramLayout layout=new MetroDiagramLayout();
        Map<String,List<Track>> tracks=new HashMap<>();
        for(int i=0;i<trackRows.length();i++){
            JSONObject data=trackRows.getJSONObject(i);String service=data.getString("name");
            JSONArray samples=data.getJSONArray("points");
            if(samples.length()<2||samples.length()>20000)return null;
            List<PointF> path=new ArrayList<>();
            for(int j=0;j<samples.length();j++){
                JSONArray pair=samples.getJSONArray(j);PointF p=point(pair.getDouble(0),pair.getDouble(1));
                if(path.isEmpty()||distance(path.get(path.size()-1),p)>.0001)path.add(p);
            }
            if(path.size()<2)return null;
            MetroDiagramLayout.AuthoredTrack drawing=new MetroDiagramLayout.AuthoredTrack(service,
                    Collections.unmodifiableList(path),colour(colours,service));
            layout.authoredTracks.add(drawing);
            String family=family(service);List<Track> candidates=tracks.get(family);
            if(candidates==null){candidates=new ArrayList<>();tracks.put(family,candidates);}
            candidates.add(new Track(drawing));
        }
        for(MetroNetwork.Stop stop:model.stops){
            Station station=match(stop,stationIndex.get(key(stop.name)));
            if(station==null)return null;
            layout.stops.put(stop,station.point);
            // One circular marker, including transfers between separated rails.
            layout.ports.put(stop,Collections.singletonList(station.point));
            JSONObject anchor=labels==null?null:labels.optJSONObject(String.valueOf(station.index));
            if(anchor==null&&labels!=null)anchor=labels.optJSONObject(station.rawName);
            if(anchor!=null)layout.authoredLabels.put(stop,label(anchor));
            String display=names==null?station.rawName:names.optString(station.rawName,station.rawName);
            layout.authoredNames.put(stop,display);
        }
        for(Map.Entry<MetroDataSource.MetroLine,List<MetroNetwork.Node>> entry:model.lines.entrySet()){
            MetroDataSource.MetroLine line=entry.getKey();List<MetroNetwork.Node> nodes=entry.getValue();
            List<Track> candidates=tracks.get(family(line.networkId));
            if(candidates==null||nodes.size()<2)return null;
            for(MetroNetwork.Node node:nodes){
                if(node==null)return null;
                PointF anchor=layout.stops.get(node.stop);
                DiagramPath.Projection best=null;double distance=Double.MAX_VALUE;
                for(Track track:candidates){
                    DiagramPath.Projection p=DiagramPath.project(track.coordinates,anchor.x,anchor.y);
                    if(p==null)continue;double d=Math.hypot(p.x-anchor.x,p.y-anchor.y);
                    if(d<distance){distance=d;best=p;}
                }
                if(best==null||distance>160)return null;
                layout.nodePoints.put(node,new PointF(best.x,best.y));
            }
            List<PointF> logicalPath=new ArrayList<>();
            int count=nodes.size()-1+(line.ring?1:0);
            for(int i=0;i<count;i++){
                MetroNetwork.Node a=nodes.get(i),b=nodes.get((i+1)%nodes.size());
                PointF first=layout.stops.get(a.stop),last=layout.stops.get(b.stop);
                if(a.stop==b.stop||distance(first,last)<.01)return null;
                Interval chosen=null;
                for(Track candidate:candidates){
                    Interval interval=interval(candidate,first,last);
                    if(interval!=null&&(chosen==null||interval.score<chosen.score))chosen=interval;
                }
                if(chosen==null)return null;
                List<PointF> forward=Collections.unmodifiableList(chosen.points);
                List<PointF> reverse=new ArrayList<>(forward);Collections.reverse(reverse);
                layout.putInterval(a,b,forward);
                layout.putInterval(b,a,Collections.unmodifiableList(reverse));
                // Kept for older geometry consumers. The renderer draws each
                // authored component separately, never this composite sequence.
                logicalPath.addAll(forward.subList(logicalPath.isEmpty()?0:1,forward.size()));
            }
            layout.lines.put(line,Collections.unmodifiableList(logicalPath));
        }
        JSONArray badgeRows=source.optJSONArray("lineLabels");
        if(badgeRows!=null)for(int i=0;i<badgeRows.length();i++){
            JSONObject badge=badgeRows.getJSONObject(i);String service=badge.getString("name");
            String text=badge.optString("text",service.replace("广州",""));
            float x=finite(badge.getDouble("x")),y=finite(badge.getDouble("y"));
            float width=finite(badge.optDouble("width",text.length()*30+16));
            float height=finite(badge.optDouble("height",40.5));
            if(width<=0||height<=0)return null;
            layout.authoredBadges.add(new MetroDiagramLayout.Badge(service,text,x,y,width,height,colour(colours,service)));
        }
        JSONArray displayRows=source.optJSONArray("displayStations");
        if(displayRows!=null)for(int i=0;i<displayRows.length();i++){
            JSONObject stop=displayRows.getJSONObject(i);String name=stop.getString("name");
            PointF point=point(stop.getDouble("x"),stop.getDouble("y"));
            JSONObject anchor=stop.optJSONObject("label");
            layout.displayStops.add(new MetroDiagramLayout.DisplayStop(name,stop.optString("status",""),point,
                    anchor==null?new MetroDiagramLayout.LabelAnchor(28,10,"start"):label(anchor)));
        }
        return layout.stops.size()==model.stops.size()&&layout.lines.size()==model.lines.size()?layout:null;
    }

    private static Station match(MetroNetwork.Stop stop,List<Station> candidates){
        if(candidates==null||candidates.isEmpty())return null;
        if(candidates.size()==1)return candidates.get(0);
        Station chosen=null;int score=-1;boolean ambiguous=false;
        for(Station candidate:candidates){
            int value=candidate.rawName.equals(stop.name)?100:0;
            for(MetroNetwork.Node node:stop.services)if(candidate.services.contains(family(node.line.networkId)))value+=10;
            if(value>score){score=value;chosen=candidate;ambiguous=false;}
            else if(value==score&&chosen!=null&&distance(chosen.point,candidate.point)>.01)ambiguous=true;
        }
        return ambiguous?null:chosen;
    }

    private static Interval interval(Track track,PointF first,PointF last){
        DiagramPath.Projection a=DiagramPath.project(track.coordinates,first.x,first.y);
        DiagramPath.Projection b=DiagramPath.project(track.coordinates,last.x,last.y);
        if(a==null||b==null)return null;
        double da=Math.hypot(a.x-first.x,a.y-first.y),db=Math.hypot(b.x-last.x,b.y-last.y);
        if(da>160||db>160)return null;
        float[] section=DiagramPath.between(track.coordinates,a.x,a.y,b.x,b.y,track.closed);
        List<PointF> points=new ArrayList<>();double length=0;
        for(int i=0;i<section.length;i+=2){
            PointF p=new PointF(section[i],section[i+1]);
            if(!points.isEmpty())length+=distance(points.get(points.size()-1),p);
            points.add(p);
        }
        if(points.size()<2||length<.01)return null;
        return new Interval(points,da+db+length*.001);
    }

    private static MetroDiagramLayout.LabelAnchor label(JSONObject data) throws Exception {
        String anchor=data.optString("anchor","start");
        if(!"start".equals(anchor)&&!"middle".equals(anchor)&&!"end".equals(anchor))throw new IllegalArgumentException("Invalid label anchor");
        return new MetroDiagramLayout.LabelAnchor(finite(data.optDouble("dx",28)),finite(data.optDouble("dy",10)),anchor);
    }
    private static String key(String name){return MetroNetwork.normalize(name.replaceFirst("[（(][^）)]*[）)]$",""));}
    private static String family(String service){return service.contains("广州14号线支线")?"广州14号线":service;}
    private static PointF point(double x,double y){return new PointF(finite(x),finite(y));}
    private static float finite(double value){
        if(Double.isNaN(value)||Double.isInfinite(value)||Math.abs(value)>1000000)throw new IllegalArgumentException("Invalid diagram coordinate");
        return (float)value;
    }
    private static double distance(PointF a,PointF b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static int colour(JSONObject colours,String service){
        String value=colours==null?"64748B":colours.optString(service,colours.optString(family(service),"64748B"));
        value=value.replace("#","");
        try{return (int)(0xff000000L|Long.parseLong(value,16));}
        catch(NumberFormatException invalid){return 0xff64748b;}
    }
}
