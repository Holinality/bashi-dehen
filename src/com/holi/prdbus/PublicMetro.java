package com.holi.prdbus;

import java.util.*;
import org.json.*;

final class PublicMetro {
    private static final String[][] CITIES = {
        {"北京", "1100", "beijing"},
        {"广州", "4401", "guangzhou"},
        {"上海", "3100", "shanghai"},
        {"天津", "1200", "tianjin"},
        {"重庆", "5000", "chongqing"},
        {"沈阳", "2101", "shenyang"},
        {"南京", "3201", "nanjing"},
        {"武汉", "4201", "wuhan"},
        {"成都", "5101", "chengdu"},
        {"西安", "6101", "xian"},
        {"石家庄", "1301", "shijiazhuang"},
        {"太原", "1401", "taiyuan"},
        {"郑州", "4101", "zhengzhou"},
        {"洛阳", "4103", "luoyang"},
        {"许昌", "4110", "xuchang"},
        {"资阳", "5120", "ziyang"},
        {"大连", "2102", "dalian"},
        {"长春", "2201", "changchun"},
        {"哈尔滨", "2301", "haerbin"},
        {"呼和浩特", "1501", "huhehaote"},
        {"无锡", "3202", "wuxi"},
        {"苏州", "3205", "suzhou"},
        {"徐州", "3203", "xuzhou"},
        {"常州", "3204", "changzhou"},
        {"济南", "3701", "jinan"},
        {"青岛", "3702", "qingdao"},
        {"合肥", "3401", "hefei"},
        {"芜湖", "3402", "wuhu"},
        {"滁州", "3411", "chuzhou"},
        {"杭州", "3301", "hangzhou"},
        {"宁波", "3302", "ningbo"},
        {"绍兴", "3306", "shaoxing"},
        {"温州", "3303", "wenzhou"},
        {"金华", "3307", "jinhua"},
        {"台州", "3310", "taizhou"},
        {"福州", "3501", "fuzhou"},
        {"厦门", "3502", "xiamen"},
        {"长沙", "4301", "changsha"},
        {"湘潭", "4303", "xiangtan"},
        {"湘西", "4331", "xiangxi"},
        {"深圳", "4403", "shenzhen"},
        {"佛山", "4406", "foshan"},
        {"东莞", "4419", "dongguan"},
        {"南宁", "4501", "nanning"},
        {"南昌", "3601", "nanchang"},
        {"贵阳", "5201", "guiyang"},
        {"昆明", "5301", "kunming"},
        {"兰州", "6201", "lanzhou"},
        {"南通", "3206", "nantong"},
        {"乌鲁木齐", "6501", "wulumuqi"},
        {"香港", "8100", "xianggang"},
        {"澳门", "8200", "aomen"},
        {"鄂州", "4207", "ezhou"},
        {"清远", "4418", "qingyuan"},
        {"台北", "7101", "taibei"},
        {"高雄", "7102", "gaoxiong"},
        {"桃园", "7106", "taoyuan"},
        {"台中", "7104", "taizhong"},
    };

    static String[] cities(String province) {
        List<String> names = new ArrayList<>();
        if (!province.isEmpty()) for (String[] city : CITIES)
            if (city[1].startsWith(province)) names.add(city[0]);
        return names.toArray(new String[0]);
    }

    static String owner(String line) {
        String owner = "";
        for (String[] city : CITIES)
            if (line.startsWith(city[0]) && city[0].length() > owner.length()) owner = city[0];
        return owner;
    }

    static String assetName(String city) {
        for (String[] entry : CITIES)
            if (city.equals(entry[0])) return "metro-bundled-" + entry[1] + ".json";
        throw new IllegalArgumentException("Unknown metro city");
    }

    private static JSONObject fetch(String[] city, String kind) throws Exception {
        return HttpJson.get("https://map.amap.com/service/subway?srhdata="
                + city[1] + "_" + kind + "_" + city[2] + ".json", 8 * 1024 * 1024);
    }

    static List<ChelaileDataSource.GaodeLine> lines(String name) throws Exception {
        if (!PublicBuildConfig.ALLOW_UNDOCUMENTED_METRO_FETCH)
            throw new IllegalStateException("未启用未文档化地铁数据接口；请配置自己的高德 Web 服务 Key");
        String[] city = null;
        for (String[] candidate : CITIES) if (name.equals(candidate[0])) { city = candidate; break; }
        if (city == null) return Collections.emptyList();
        JSONArray lines = fetch(city, "drw").getJSONArray("l");
        Map<String, JSONArray> hours = new HashMap<>();
        try {
            JSONArray groups = fetch(city, "info").getJSONArray("l");
            for (int i = 0; i < groups.length(); i++) {
                JSONArray stops = groups.getJSONObject(i).getJSONArray("st");
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject stop = stops.getJSONObject(j);
                    hours.put(stop.getString("si"), stop.optJSONArray("d"));
                }
            }
        } catch (Exception unavailable) { /* A line map is useful without service hours. */ }
        List<ChelaileDataSource.GaodeLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject row = lines.getJSONObject(i);
            if (!"1".equals(row.optString("su"))) continue;
            String title = row.getString("ln");
            if (MetroCatalog.identity(name, title).isEmpty()) continue;
            JSONArray stops = row.getJSONArray("st");
            List<String> names = new ArrayList<>(), ids = new ArrayList<>();
            List<double[]> points = new ArrayList<>();
            for (int j = 0; j < stops.length(); j++) {
                JSONObject stop = stops.getJSONObject(j);
                if ("0".equals(stop.optString("su"))) continue;
                String[] point = stop.optString("sl").split(",");
                if (point.length != 2) continue;
                try {
                    double lat = Double.parseDouble(point[1]), lon = Double.parseDouble(point[0]);
                    if (!TransitRules.validCoordinate(lat, lon)) continue;
                    names.add(stop.getString("n"));
                    ids.add(stop.getString("si"));
                    points.add(Coord.gcjToWgs(lat, lon));
                } catch (NumberFormatException ignored) { }
            }
            if (names.size() < 2) continue;
            boolean ring = "1".equals(row.optString("lo"));
            int expectedUnique = names.size();
            if (ring && names.get(0).equals(names.get(names.size() - 1))) expectedUnique--;
            if (new HashSet<>(names).size() != expectedUnique) continue;
            String[] directionIds = row.optString("li").split("\\|");
            for (int direction = 0; direction < (ring ? 1 : 2); direction++) {
                if (direction == 1) { Collections.reverse(names); Collections.reverse(points); Collections.reverse(ids); }
                String[] time = ring ? new String[]{"", "", ""} : terminalHours(hours.get(ids.get(0)), directionIds);
                ChelaileDataSource.GaodeLine line = new ChelaileDataSource.GaodeLine(title, names.get(0), names.get(names.size()-1),
                        time[0], time[1], "", new ArrayList<>(names), new ArrayList<>(points),
                        Collections.emptyList(), "地铁", 1, ring);
                line.colour = row.optString("cl");
                if (!time[2].isEmpty()) for (int j = 0; j < ids.size(); j++) {
                    String[] atStop = terminalHours(hours.get(ids.get(j)), new String[]{time[2]});
                    if (!atStop[0].isEmpty()) line.stationHours.put(names.get(j), atStop);
                }
                result.add(line);
            }
        }
        return result;
    }

    private static String[] terminalHours(JSONArray hours, String[] lineIds) {
        String[] time = {"", "", ""};
        if (hours == null) return time;
        List<String> ids = Arrays.asList(lineIds);
        for (int i = 0; i < hours.length(); i++) {
            JSONObject row = hours.optJSONObject(i);
            if (row == null || !ids.contains(row.optString("ls"))) continue;
            String first = row.optString("ft"), last = row.optString("lt");
            if (!first.matches("\\d{2}:\\d{2}") || !last.matches("\\d{2}:\\d{2}")) continue;
            if (!time[0].isEmpty() && (!time[0].equals(first) || !time[1].equals(last)
                    || !time[2].equals(row.optString("ls")))) return new String[]{"", "", ""};
            time[0] = first; time[1] = last; time[2] = row.optString("ls");
        }
        return time;
    }
}
