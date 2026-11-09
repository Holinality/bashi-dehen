package com.holi.prdbus;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Station-level official estimates. The feed does not report train coordinates. */
final class MtrArrivals {
    private static final String API = "https://rt.data.gov.hk/v1/transport/mtr/getSchedule.php";
    private static final Map<String, String> LINES = new LinkedHashMap<>();
    private static final Map<String, String> STATIONS = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        add(LINES, "机场快线:AEL 东涌线:TCL 屯马线:TML 将军澳线:TKL 东铁线:EAL 南港岛线:SIL 荃湾线:TWL 港岛线:ISL 观塘线:KTL 迪士尼线:DRL");
        add(STATIONS, "香港:HOK 九龙:KOW 青衣:TSY 机场:AIR 博览馆:AWE 奥运:OLY 南昌:NAC 荔景:LAK 欣澳:SUN 东涌:TUC");
        add(STATIONS, "乌溪沙:WKS 马鞍山:MOS 恒安:HEO 大水坑:TSH 石门:SHM 第一城:CIO 沙田围:STW 车公庙:CKT 大围:TAW 显径:HIK 钻石山:DIH 启德:KAT 宋皇台:SUW 土瓜湾:TKW 何文田:HOM 红磡:HUH 尖东:ETS 柯士甸:AUS 美孚:MEF 荃湾西:TWW 锦上路:KSR 元朗:YUL 朗屏:LOP 天水围:TIS 兆康:SIH 屯门:TUM");
        add(STATIONS, "北角:NOP 鲗鱼涌:QUB 油塘:YAT 调景岭:TIK 将军澳:TKO 康城:LHP 坑口:HAH 宝琳:POA");
        add(STATIONS, "金钟:ADM 会展:EXC 旺角东:MKK 九龙塘:KOT 沙田:SHT 火炭:FOT 马场:RAC 大学:UNI 大埔墟:TAP 太和:TWO 粉岭:FAN 上水:SHS 罗湖:LOW 落马洲:LMC");
        add(STATIONS, "海洋公园:OCP 黄竹坑:WCH 利东:LET 海怡半岛:SOH 中环:CEN 尖沙咀:TST 佐敦:JOR 油麻地:YMT 旺角:MOK 太子:PRE 深水埗:SSP 长沙湾:CSW 荔枝角:LCK 葵芳:KWF 葵兴:KWH 大窝口:TWH 荃湾:TSW");
        add(STATIONS, "坚尼地城:KET 香港大学:HKU 西营盘:SYP 上环:SHW 湾仔:WAC 铜锣湾:CAB 天后:TIH 炮台山:FOH 太古:TAK 西湾河:SWH 筲箕湾:SKW 杏花邨:HFC 柴湾:CHW");
        add(STATIONS, "黄埔:WHA 石硖尾:SKM 乐富:LOF 黄大仙:WTS 彩虹:CHH 九龙湾:KOB 牛头角:NTK 观塘:KWT 蓝田:LAT 迪士尼:DIS");
        for (Map.Entry<String, String> station : STATIONS.entrySet()) NAMES.put(station.getValue(), station.getKey());
    }

    private static void add(Map<String, String> map, String pairs) {
        for (String pair : pairs.split(" ")) {
            int colon = pair.indexOf(':');
            map.put(pair.substring(0, colon), pair.substring(colon + 1));
        }
    }

    static String lineCode(MetroDataSource.MetroLine line) {
        if (line == null || !line.networkId.startsWith("香港")) return null;
        return LINES.get(line.networkId.substring(2));
    }

    static String stationCode(String station) {
        return STATIONS.get(station.replaceFirst("站$", ""));
    }

    static String load(String line, String station) throws Exception {
        if (!LINES.containsValue(line) || !NAMES.containsKey(station)) return "";
        JSONObject root = PublicTransportHttp.json(API + "?line=" + line + "&sta=" + station, null);
        if (root.optInt("status") == 0) return "港铁当前未提供本站到站时间";
        JSONObject data = root.optJSONObject("data");
        JSONObject times = data == null ? null : data.optJSONObject(line + "-" + station);
        if (times == null) return "";
        return format(times, System.currentTimeMillis());
    }

    static String format(JSONObject times, long now) {
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        parser.setTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong"));
        parser.setLenient(false);
        Map<String, StringBuilder> directions = new LinkedHashMap<>();
        for (String bound : new String[]{"UP", "DOWN"}) {
            JSONArray rows = times.optJSONArray(bound);
            if (rows == null) continue;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !"Y".equals(row.optString("valid", "Y"))) continue;
                String dest = NAMES.get(row.optString("dest"));
                if (dest == null) continue;
                String arrival = row.optString("time");
                if (!arrival.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) continue;
                try {
                    long moment = parser.parse(arrival).getTime();
                    if (moment < now - 30_000 || moment > now + 2 * 60 * 60_000) continue;
                    StringBuilder text = directions.get(dest);
                    if (text == null) {
                        text = new StringBuilder("往 ").append(dest).append("  ");
                        directions.put(dest, text);
                    } else text.append("  ·  ");
                    text.append(arrival, 11, 16);
                    String platform = row.optString("plat");
                    if (platform.matches("[0-9]{1,2}")) text.append("（").append(platform).append(" 号月台）");
                } catch (java.text.ParseException ignored) { }
            }
        }
        return directions.isEmpty() ? "港铁暂未返回本站预计到站"
                : android.text.TextUtils.join("\n", directions.values());
    }

    private MtrArrivals() { }
}
