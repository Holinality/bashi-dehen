package com.holi.prdbus;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

final class CityLocator {
    private static final RequestReuse<Area> AREAS = new RequestReuse<>(45 * 60_000L, 20);

    private static final class Area {
        final String city, province;
        final double lat, lon;
        Area(String city, String province, double lat, double lon) {
            this.city = Regions.normalizeCity(city);
            this.province = province;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static CityInfo locate(Context context, double lat, double lon) throws Exception {
        String key = "near|" + Math.floor(lat * 20) + "|" + Math.floor(lon * 20);
        Area area = AREAS.get(key, () -> reverse(context, lat, lon));
        String id = ChelaileDataSource.resolveCityId(area.city, "");
        if (id.isEmpty()) {
            if (!MygolbsDataSource.supports(area.city)) throw new IllegalStateException("暂未覆盖该城市");
            id = "mg:" + area.city;
        }
        Regions.remember(area.city, area.province);
        CityInfo known = CityInfo.find(area.city);
        boolean hasCenter = known != null && TransitRules.validCoordinate(known.lat, known.lon);
        return CityInfo.register(area.city, id, hasCenter ? known.lat : lat, hasCenter ? known.lon : lon);
    }

    static CityInfo choose(Context context, ChelaileDataSource.City city) {
        CityInfo known = CityInfo.find(city.name);
        if (known != null && TransitRules.validCoordinate(known.lat, known.lon)) return known;
        try {
            Area area = AREAS.get("name|" + city.name, () -> center(context, city.name));
            Regions.remember(city.name, area.province);
            return CityInfo.register(city.name, city.cityId, area.lat, area.lon);
        } catch (Exception unavailable) {
            return CityInfo.register(city.name, city.cityId, 0, 0);
        }
    }

    private static Area reverse(Context context, double lat, double lon) throws Exception {
        try {
            List<Address> hits = new Geocoder(context, Locale.SIMPLIFIED_CHINESE).getFromLocation(lat, lon, 1);
            if (hits != null && !hits.isEmpty()) {
                Address a = hits.get(0);
                String name = a.getLocality();
                if (name == null || name.isEmpty()) name = a.getSubAdminArea();
                if (name == null || name.isEmpty()) name = a.getAdminArea();
                String code = Regions.provinceCode(a.getAdminArea());
                if (name != null && !name.isEmpty() && !code.isEmpty()) return new Area(name, code, lat, lon);
            }
        } catch (Exception ignored) { }
        try {
            String city = MygolbsDataSource.cityAt(lat, lon);
            String code = Regions.province(city);
            if (!city.isEmpty() && (!code.isEmpty() || !AppSettings.hasMapKey()))
                return new Area(city, code, lat, lon);
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception ignored) { }
        if (AppSettings.hasMapKey()) {
            double[] point = Coord.wgsToGcj(lat, lon);
            JSONObject root = HttpJson.get(AppSettings.credentialUrl(
                    "https://restapi.amap.com/v3/geocode/regeo?key=&extensions=base&location="
                            + point[1] + "," + point[0]));
            JSONObject a = root.getJSONObject("regeocode").getJSONObject("addressComponent");
            String name = a.optString("city", "");
            if (name.isEmpty() || "[]".equals(name)) name = a.optString("province");
            return new Area(name, Regions.provinceCode(a.optString("province")), lat, lon);
        }
        throw new IllegalStateException("无法确定所在城市");
    }

    private static Area center(Context context, String city) throws Exception {
        if (AppSettings.hasMapKey()) {
            try {
                JSONObject root = HttpJson.get(AppSettings.credentialUrl("https://restapi.amap.com/v3/geocode/geo?key=&address="
                        + java.net.URLEncoder.encode(city, "UTF-8") + "&city=" + java.net.URLEncoder.encode(city, "UTF-8")));
                JSONObject result = root.getJSONArray("geocodes").getJSONObject(0);
                String[] point = result.getString("location").split(",");
                double[] wgs = Coord.gcjToWgs(Double.parseDouble(point[1]), Double.parseDouble(point[0]));
                return new Area(city, Regions.provinceCode(result.optString("province")), wgs[0], wgs[1]);
            } catch (Exception ignored) { }
        }
        List<Address> hits = new Geocoder(context, Locale.SIMPLIFIED_CHINESE).getFromLocationName(city, 1);
        if (hits != null && !hits.isEmpty()) {
            Address a = hits.get(0);
            if (a.hasLatitude() && a.hasLongitude())
                return new Area(city, Regions.provinceCode(a.getAdminArea()), a.getLatitude(), a.getLongitude());
        }
        throw new IllegalStateException("没有城市坐标");
    }
}
