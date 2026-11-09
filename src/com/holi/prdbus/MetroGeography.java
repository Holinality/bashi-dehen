package com.holi.prdbus;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

/** Bundled WGS84 OSM track sections. Missing intervals remain gaps, never invented chords. */
final class MetroGeography {
    static final class Position {
        final double lat,lon;
        Position(double lat,double lon){this.lat=lat;this.lon=lon;}
    }
    final Map<MetroNetwork.Stop,Position> positions=new IdentityHashMap<>();
    private final Map<String,List<Position>> sections=new HashMap<>();

    static MetroGeography load(Context context,MetroNetwork.Area area,MetroNetwork network) {
        InputStream source;
        try {source=context.getAssets().open("metro-geography-"+area.name()+".json");}
        catch(java.io.IOException absent) {
            try {source=context.getAssets().open("metro-geography.json");}
            catch(java.io.IOException unavailable){return new MetroGeography();}
        }
        try(InputStream in=source;ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)bytes.write(buffer,0,n);
            JSONObject root=new JSONObject(bytes.toString("UTF-8"));
            if(!"WGS84".equals(root.optString("coordinates")))throw new IllegalArgumentException("Unexpected map coordinates");
            return decode(root.has("areas")?root.getJSONObject("areas").getJSONObject(area.name()):root,network);
        }catch(Exception unavailable){return new MetroGeography();}
    }
    static MetroGeography decode(JSONObject data,MetroNetwork network) throws Exception {
        MetroGeography result=new MetroGeography();
        JSONArray stops=data.getJSONArray("stations");
        Map<String,Position> points=new HashMap<>();
        for(int i=0;i<stops.length();i++) {
            JSONArray row=stops.getJSONArray(i);
            double lat=row.getDouble(2),lon=row.getDouble(3);
            if(TransitRules.validCoordinate(lat,lon))points.put(row.getString(0)+"|"+key(row.getString(1)),new Position(lat,lon));
        }
        for(Map.Entry<MetroDataSource.MetroLine,List<MetroNetwork.Node>> line:network.lines.entrySet())
            for(MetroNetwork.Node node:line.getValue())if(node!=null&&!result.positions.containsKey(node.stop)) {
                Position point=points.get(line.getKey().networkId+"|"+key(node.stop.name));
                if(point!=null)result.positions.put(node.stop,point);
            }
        JSONArray intervals=data.getJSONArray("sections");
        for(int i=0;i<intervals.length();i++) {
            JSONArray row=intervals.getJSONArray(i),coords=row.getJSONArray(3);
            if(coords.length()<4||coords.length()%2!=0)continue;
            List<Position> path=new ArrayList<>(coords.length()/2);
            boolean valid=true;
            for(int j=0;j<coords.length();j+=2) {
                double lat=coords.getDouble(j),lon=coords.getDouble(j+1);
                if(!TransitRules.validCoordinate(lat,lon)){valid=false;break;}
                path.add(new Position(lat,lon));
            }
            if(valid)result.sections.put(sectionKey(row.getString(0),row.getString(1),row.getString(2)),path);
        }
        return result;
    }
    private static String key(String name){return MetroNetwork.normalize(name.replaceFirst("[（(][^）)]*[）)]$",""));}
    private static String sectionKey(String line,String a,String b){return line+"|"+key(a)+"|"+key(b);}
    List<Position> section(MetroNetwork.Node a,MetroNetwork.Node b) {
        List<Position> path=sections.get(sectionKey(a.line.networkId,a.stop.name,b.stop.name));
        if(path!=null)return path;
        path=sections.get(sectionKey(a.line.networkId,b.stop.name,a.stop.name));
        if(path==null)return Collections.emptyList();
        List<Position> reverse=new ArrayList<>(path);Collections.reverse(reverse);return reverse;
    }
    List<Position> path(MetroDataSource.MetroLine line,List<MetroNetwork.Node> nodes) {
        List<Position> path=new ArrayList<>();
        int count=nodes.size()-1+(line.ring?1:0);
        for(int i=0;i<count;i++) {
            MetroNetwork.Node a=nodes.get(i%nodes.size()),b=nodes.get((i+1)%nodes.size());
            List<Position> section=a==null||b==null?Collections.emptyList():section(a,b);
            if(section.isEmpty()){if(!path.isEmpty()&&path.get(path.size()-1)!=null)path.add(null);continue;}
            if(!path.isEmpty()) {
                Position previous=path.get(path.size()-1),next=section.get(0);
                if(previous!=null&&GeoUtils.distanceKm(previous.lat,previous.lon,next.lat,next.lon)>.005)path.add(null);
            }
            path.addAll(section);
        }return path;
    }
    MetroNetwork.Stop nearest(double lat,double lon,double maximumKm) {
        MetroNetwork.Stop closest=null;double best=maximumKm;
        for(Map.Entry<MetroNetwork.Stop,Position> stop:positions.entrySet()) {
            Position p=stop.getValue();double distance=GeoUtils.distanceKm(lat,lon,p.lat,p.lon);
            if(distance<best){best=distance;closest=stop.getKey();}
        }return closest;
    }
}
