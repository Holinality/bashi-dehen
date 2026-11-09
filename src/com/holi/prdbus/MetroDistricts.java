package com.holi.prdbus;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class MetroDistricts {
    static final class Label {
        final String name;
        final double lat, lon;
        Label(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    final List<Label> labels = new ArrayList<>();
    final List<double[]> paths = new ArrayList<>();
    final List<double[]> land = new ArrayList<>();
    final List<double[]> water = new ArrayList<>();
    final List<double[]> rivers = new ArrayList<>();
    final List<String> riverKinds = new ArrayList<>();
    final List<double[]> coastlines = new ArrayList<>();

    static MetroDistricts load(Context context, MetroNetwork.Area area) {
        try {
            return decode(readAsset(context,"metro-districts-"+area.name()+".json"));
        } catch (Exception missingArea) {
            // Compatibility for older internal bundles; production packages contain
            // one area per file so opening a city never parses the other cities.
            try { return decode(readAsset(context,"metro-districts.json").getJSONObject(area.name())); }
            catch (Exception unavailable) { return new MetroDistricts(); }
        }
    }

    private static JSONObject readAsset(Context context,String name) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count);
            return new JSONObject(output.toString("UTF-8"));
        }
    }

    static MetroDistricts decode(JSONObject object) throws Exception {
        if (!"WGS84".equalsIgnoreCase(object.optString("crs")))
            throw new IllegalArgumentException("Offline OSM background must declare WGS84");
        MetroDistricts districts = new MetroDistricts();
        JSONArray names = object.getJSONArray("labels");
        for (int i = 0; i < names.length(); i++) {
            JSONArray entry = names.getJSONArray(i);
            double lat=entry.getDouble(1),lon=entry.getDouble(2);
            if (coordinate(lat,lon)) districts.labels.add(new Label(entry.getString(0),lat,lon));
        }
        readPaths(object.optJSONArray("paths"), districts.paths, false);
        readPaths(object.optJSONArray("land"), districts.land, true);
        readPaths(object.optJSONArray("water"), districts.water, true);
        readPaths(object.optJSONArray("rivers"), districts.rivers, false);
        readPaths(object.optJSONArray("coastlines"), districts.coastlines, false);
        JSONArray kinds=object.optJSONArray("riverKinds");
        for (int i=0;i<districts.rivers.size();i++) districts.riverKinds.add(kinds==null?"river":kinds.optString(i,"river"));
        return districts;
    }

    private static boolean coordinate(double lat,double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && !Double.isInfinite(lat) && !Double.isInfinite(lon)
                && lat>=-90 && lat<=90 && lon>=-180 && lon<=180;
    }

    private static void readPaths(JSONArray paths, List<double[]> result, boolean closed) throws Exception {
        if (paths == null) return;
        for (int i = 0; i < paths.length(); i++) {
            JSONArray entry = paths.getJSONArray(i);
            if (entry.length() < (closed?8:4) || entry.length() % 2 != 0) continue;
            double[] points = new double[entry.length()];
            boolean valid=true;
            for (int j = 0; j < points.length; j += 2) {
                points[j]=entry.getDouble(j);
                points[j+1]=entry.getDouble(j+1);
                if (!coordinate(points[j],points[j+1])) valid=false;
            }
            if (closed && (points[0]!=points[points.length-2] || points[1]!=points[points.length-1])) valid=false;
            if (valid) result.add(points);
        }
    }
}
