package com.holi.prdbus;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class RailTimetable {
    private static final String HOST = "https://kyfw.12306.cn/otn/";
    private static final TimeZone CHINA = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long MINUTE = 60_000L;
    static final int BOARDING_BUFFER_MINUTES = 15;
    private static final long CACHE_TTL = 120_000L;
    private static final Map<String, Cache> CACHE = new LinkedHashMap<String, Cache>(24, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cache> entry) { return size() > 24; }
    };
    private static Map<String, String> codes;
    private static java.net.CookieManager session;
    private static long stopsUnavailableUntil;
    private static final Map<String, Service> STOP_CACHE = new LinkedHashMap<String, Service>(96, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Service> entry) { return size() > 96; }
    };
    private static final class Service {
        final StopDetails details;
        final long expires;
        Service(StopDetails details, long expires) { this.details = details; this.expires = expires; }
    }

    static final class StopDetails {
        final RailStopTimes table;
        final String service,message;
        StopDetails(RailStopTimes table,String service,String message) {
            this.table=table;this.service=service;this.message=message;
        }
        RailStopTimes.Leg leg(Train train) {
            return table==null?null:table.leg(train.from,train.to,clockMinutes(train.departure),
                    clockMinutes(train.arrival),(int)((train.arrival-train.departure)/MINUTE));
        }
    }

    static final class Train implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        final String number, from, to, service, stopQuery;
        final long departure, arrival;
        Train(String number, String from, String to, long departure, long arrival, String service, String stopQuery) {
            this.number = number;
            this.from = from;
            this.to = to;
            this.departure = departure;
            this.arrival = arrival;
            this.service = service;
            this.stopQuery = stopQuery;
        }
        String label(long now) {
            return number + " · " + service + "  " + times(now);
        }
        String times(long now) {
            return from + " " + display(departure, now) + " 发"
                    + " → " + to + " " + display(arrival, now) + " 到";
        }
        String departureLabel(long now){return display(departure,now);}
        String arrivalLabel(long now){return display(arrival,now);}
    }

    static final class Result {
        final List<Train> trains;
        final String message;
        Result(List<Train> trains, String message) { this.trains = trains; this.message = message; }
        String summary(long now) {
            if (trains.isEmpty()) return message;
            StringBuilder text = new StringBuilder();
            for (Train train : trains) {
                if (text.length() > 0) text.append('\n');
                text.append(train.label(now));
            }
            return text.toString();
        }
    }

    private static final class Cache {
        final long savedAt;
        final List<Train> trains;
        Cache(List<Train> trains) { this.savedAt = System.currentTimeMillis(); this.trains = trains; }
    }

    static long readyAt(List<ChelaileDataSource.TransitSegment> segments, int index, long now) {
        long minutes = BOARDING_BUFFER_MINUTES;
        for (int i = 0; i < index; i++) {
            ChelaileDataSource.TransitSegment segment = segments.get(i);
            if ("walk".equals(segment.type)) {
                minutes += segment.durationMin > 0 ? segment.durationMin : (Math.max(0, segment.distance) + 74) / 75;
            } else if (segment.durationMin > 0) {
                minutes += segment.durationMin;
                if ("intercity".equals(segment.type)
                        || RailCatalog.GD.equals(RailCatalog.regionForTrip(segment.name,
                                segment.from, segment.to, segment.cityHint)))
                    minutes += BOARDING_BUFFER_MINUTES;
            } else {
                return -1;
            }
        }
        return now + minutes * MINUTE;
    }

    static Result upcoming(Context context, String fromInput, String toInput, long ready) {
        return upcoming(context, fromInput, toInput, ready, 3);
    }

    static Result upcoming(Context context, String fromInput, String toInput, long ready, int limit) {
        if (ready <= 0) return empty("上车前的行程时间不完整，请核对后查询班次");
        String from = RailCatalog.station(RailCatalog.GD, fromInput);
        String to = RailCatalog.station(RailCatalog.GD, toInput);
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) return empty("请选择不同的城际上、下车站");
        if (!RailCatalog.boardingAllowed(RailCatalog.GD,from) || !RailCatalog.boardingAllowed(RailCatalog.GD,to))
            return empty("所选车站暂停服务，请重新选择");
        try {
            Map<String, String> stationCodes = stationCodes(context);
            String start = stationCodes.get(from), end = stationCodes.get(to);
            if (start == null || end == null) return empty("班次服务暂未收录该城际站");
            Calendar day = Calendar.getInstance(CHINA);
            day.setTimeInMillis(ready);
            List<Train> next = new ArrayList<>();
            for (Train train : queryDay(day, start, end, from, to)) if (train.departure >= ready) next.add(train);
            if (next.isEmpty()) {
                day.add(Calendar.DATE, 1);
                for (Train train : queryDay(day, start, end, from, to)) if (train.departure >= ready) next.add(train);
            }
            Collections.sort(next, (a, b) -> {
                int arrival = Long.compare(a.arrival, b.arrival);
                return arrival == 0 ? Long.compare(a.departure, b.departure) : arrival;
            });
            if (next.isEmpty()) return empty("暂无该站间可查班次");
            List<Train> shown = new ArrayList<>();
            for (Train train : next.subList(0, Math.min(Math.max(1, limit), next.size()))) {
                String service = cachedService(train);
                shown.add(new Train(train.number, train.from, train.to, train.departure, train.arrival, service, train.stopQuery));
            }
            return new Result(shown, "");
        } catch (Exception unavailable) {
            return empty("班次服务暂不可用，可稍后重试");
        }
    }

    private static Result empty(String message) { return new Result(Collections.emptyList(), message); }

    static synchronized String cachedService(Train train) {
        Service cached = STOP_CACHE.get(train.stopQuery);
        return cached != null && cached.details.table!=null && cached.details.leg(train)!=null
                && System.currentTimeMillis() < cached.expires ? cached.details.service : train.service;
    }

    static synchronized String service(Train train) {
        StopDetails details=stopDetails(train,false);
        return details.table==null?train.service:details.service;
    }

    static synchronized StopDetails stopDetails(Train train,boolean retry) {
        if (train.stopQuery.isEmpty()) return missingStops(train,"该班次暂未提供停站资料");
        Service cached = STOP_CACHE.get(train.stopQuery);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expires) {
            if(cached.details.table!=null && cached.details.leg(train)!=null)return cached.details;
            if(!retry)return cached.details.table==null?cached.details:missingStops(train,"停站资料与本次行程不一致，请重试");
        }
        if (now < stopsUnavailableUntil && !retry) return missingStops(train,"停站资料暂不可用，请稍后重试");
        try {
            if(session==null) {
                java.net.CookieManager opened=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER);
                PublicTransportHttp.get(HOST+"leftTicket/init",null,opened);
                session=opened;
            }
            JSONObject response = new JSONObject(PublicTransportHttp.get(HOST + "czxx/queryByTrainNo"
                    + train.stopQuery, HOST + "leftTicket/init", session));
            JSONObject data = response.optJSONObject("data");
            JSONArray stations = data == null ? null : data.optJSONArray("data");
            if (!response.optBoolean("status") || stations == null) throw new java.io.IOException("Missing train stops");
            StopDetails details=decodeStops(stations,train);
            STOP_CACHE.put(train.stopQuery,new Service(details,now+12*60*MINUTE));
            stopsUnavailableUntil=0;
            return details;
        } catch (Exception unavailable) {
            session=null;
            stopsUnavailableUntil = now + CACHE_TTL;
            StopDetails missing=missingStops(train,"停站资料暂不可用，请重试");
            STOP_CACHE.put(train.stopQuery,new Service(missing,now+CACHE_TTL));
            return missing;
        }
    }

    static StopDetails decodeStops(JSONArray stations,Train train) throws Exception {
            if(stations.length()<2 || stations.length()>200)throw new java.io.IOException("Invalid station count");
            List<RailStopTimes.Raw> rows=new ArrayList<>();
            for (int i = 0; i < stations.length(); i++) {
                JSONObject station = stations.getJSONObject(i);
                String number=station.optString("station_train_code");
                if(!number.isEmpty()&&!java.util.Arrays.asList(number.split("/")).contains(train.number))
                    throw new java.io.IOException("Wrong train stopping table");
                rows.add(new RailStopTimes.Raw(station.optString("station_name"),
                        station.optString("arrive_time"),station.optString("start_time")));
            }
            RailStopTimes table=RailStopTimes.parse(rows);
            List<String> names=new ArrayList<>();
            for(RailStopTimes.Call call:table.calls)names.add(call.name);
            StopDetails details=new StopDetails(table,RailCatalog.serviceForStops(names),"");
            if(details.leg(train)==null)throw new java.io.IOException("Stopping table does not match requested interval");
            return details;
    }

    private static StopDetails missingStops(Train train,String message) {
        return new StopDetails(null,train.service,message);
    }

    private static int clockMinutes(long time) {
        Calendar clock=Calendar.getInstance(CHINA);clock.setTimeInMillis(time);
        return clock.get(Calendar.HOUR_OF_DAY)*60+clock.get(Calendar.MINUTE);
    }

    private static synchronized Map<String, String> stationCodes(Context context) throws Exception {
        if (codes != null) return codes;
        Map<String, String> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("regional-rail-telecodes.tsv"), "UTF-8"))) {
            for (String row; (row = reader.readLine()) != null;) {
                String[] fields = row.split("\t");
                if (fields.length == 2 && fields[1].matches("[A-Z]{3}")) loaded.put(fields[0], fields[1]);
            }
        }
        codes = loaded;
        return codes;
    }

    private static synchronized List<Train> queryDay(Calendar day, String fromCode, String toCode,
                                                       String from, String to) throws Exception {
        String date = date(day.getTimeInMillis());
        String key = date + fromCode + toCode;
        Cache cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.savedAt < CACHE_TTL) {
            if (cached.trains == null) throw new java.io.IOException("12306 retry backoff");
            return cached.trains;
        }
        String query = "?leftTicketDTO.train_date=" + date + "&leftTicketDTO.from_station="
                + fromCode + "&leftTicketDTO.to_station=" + toCode + "&purpose_codes=ADULT";
        try {
            String init = HOST + "leftTicket/init";
            if (session == null) {
                java.net.CookieManager opened = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER);
                PublicTransportHttp.get(init, null, opened);
                session = opened;
            }
            JSONObject response;
            try {
                response = new JSONObject(PublicTransportHttp.get(HOST + "leftTicket/query" + query, init, session));
            } catch (PublicTransportHttp.Redirect redirect) {
                String path = queryVariant(redirect.location);
                if (path.isEmpty()) throw redirect;
                response = new JSONObject(PublicTransportHttp.get(HOST + path + query, init, session));
            }
            String variant = queryVariant(response.optString("c_url", ""));
            if (!variant.isEmpty()) {
                response = new JSONObject(PublicTransportHttp.get(HOST + variant + query, init, session));
            }
            JSONObject data = response.optJSONObject("data");
            JSONArray rows = data == null ? null : data.optJSONArray("result");
            if (!response.optBoolean("status") || rows == null) throw new java.io.IOException("Missing 12306 results");
            List<Train> trains = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            JSONObject labels = data.optJSONObject("map");
            for (int i = 0; i < rows.length(); i++) {
                Train train = parse(rows.optString(i), day, fromCode, toCode, from, to, labels);
                if (train != null && seen.add(train.number + "/" + train.departure)) trains.add(train);
            }
            CACHE.put(key, new Cache(trains));
            return trains;
        } catch (Exception unavailable) {
            session = null;
            // A failed request also backs off, so a visible plan does not hit 12306 on every refresh.
            CACHE.put(key, new Cache(null));
            throw unavailable;
        }
    }

    static String queryVariant(String location) {
        if (location == null) return "";
        String path = location.split("\\?", 2)[0];
        if (path.startsWith(HOST)) path = path.substring(HOST.length());
        else if (path.startsWith("/otn/")) path = path.substring(5);
        if (path.startsWith("leftTicket/")) path = path.substring(11);
        return path.matches("query[A-Z]") ? "leftTicket/" + path : "";
    }

    static Train parse(String row, Calendar day, String fromCode, String toCode, String from, String to,
                       JSONObject labels) {
        String[] fields = row.split("\\|", -1);
        if (fields.length < 18 || !fields[3].matches("[CS]\\d{1,5}")
                || !sameStation(fields[6], fromCode, from, labels)
                || !sameStation(fields[7], toCode, to, labels)) return null;
        int departure = timeMinutes(fields[8], true);
        int arrival = timeMinutes(fields[9], true);
        int duration = timeMinutes(fields[10], false);
        if (departure < 0 || arrival < 0 || duration <= 0 || duration >= 24 * 60
                || (departure + duration) % (24 * 60) != arrival) return null;
        Calendar start = (Calendar) day.clone();
        start.set(Calendar.HOUR_OF_DAY, departure / 60);
        start.set(Calendar.MINUTE, departure % 60);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long leaves = start.getTimeInMillis();
        int first = stopOrdinal(fields[16]), last = stopOrdinal(fields[17]);
        return new Train(fields[3], from, to, leaves, leaves + duration * MINUTE,
                RailCatalog.serviceForInterval(from, to, first > 0 ? last - first : -1),
                fields[2].matches("[A-Za-z0-9]{1,24}") ? "?train_no=" + fields[2]
                        + "&from_station_telecode=" + (fields[4].matches("[A-Z]{3}") ? fields[4] : fromCode)
                        + "&to_station_telecode=" + (fields[5].matches("[A-Z]{3}") ? fields[5] : toCode)
                        + "&depart_date=" + originDate(fields[13],leaves) : "");
    }

    static String originDate(String value,long fallback) {
        if(value!=null&&value.matches("\\d{8}"))try {
            SimpleDateFormat parser=new SimpleDateFormat("yyyyMMdd",Locale.CHINA);
            parser.setTimeZone(CHINA);parser.setLenient(false);
            java.util.Date parsed=parser.parse(value);
            return date(parsed.getTime());
        } catch(java.text.ParseException invalid) { }
        return date(fallback);
    }

    private static int stopOrdinal(String value) {
        return value.matches("\\d{1,3}") ? Integer.parseInt(value) : -1;
    }

    private static boolean sameStation(String returnedCode, String requestedCode, String name, JSONObject labels) {
        // 12306 uses a separate code with embedded spaces for the intercity platforms at 惠州北.
        return requestedCode.equals(returnedCode) || (labels != null
                && name.equals(labels.optString(returnedCode).replaceAll("\\s+", "")));
    }

    private static int timeMinutes(String value, boolean clock) {
        if (!value.matches("\\d{1,2}:[0-5]\\d")) return -1;
        String[] parts = value.split(":");
        int hour = Integer.parseInt(parts[0]);
        return clock && hour > 23 ? -1 : hour * 60 + Integer.parseInt(parts[1]);
    }

    private static String date(long time) { return format("yyyy-MM-dd", time); }
    private static String display(long time, long now) {
        return format(date(time).equals(date(now)) ? "HH:mm" : "M月d日 HH:mm", time);
    }
    private static String format(String pattern, long time) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
        format.setTimeZone(CHINA);
        return format.format(time);
    }

    private RailTimetable() { }
}
