package com.holi.prdbus;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** 将规划结果中的乘车段解析到应用内部的地铁/公交详情，并严格匹配运行方向。 */
final class TransitSegmentNavigator {
    private TransitSegmentNavigator() { }
    private static final java.util.Map<String, BusCandidate> RESOLVED =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, BusCandidate>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, BusCandidate> entry) {
                    return size() > 32;
                }
            });

    interface ProgressCallback { void onResult(String text); }
    static final class ProgressResult {
        final String text;
        final double distanceKm;
        final float fraction;
        ProgressResult(String text, double distanceKm, float fraction) {
            this.text = text;
            this.distanceKm = distanceKm;
            this.fraction = Math.max(0, Math.min(1, fraction));
        }
    }
    interface RouteProgressCallback { void onResult(ProgressResult result); }

    static void loadBoardArrivals(Activity activity, ExecutorService executor,
                                   ChelaileDataSource.TransitSegment segment,
                                   String cityId, ProgressCallback callback) {
        executor.execute(() -> {
            String message;
            try {
                BusCandidate match = resolveBusDirection(busCandidates(cityId, segment), segment.from, segment.to);
                if (match == null) message = "未匹配到对应方向 · 点击重试";
                else {
                    ChelaileDataSource.LineDetail line = ChelaileDataSource.queryLineDetail(
                            match.cityId, match.resolvedLineId, match.boardLat, match.boardLon, match.boardName);
                    java.util.Map<String, List<ChelaileDataSource.Bus>> arrivals = new java.util.HashMap<>();
                    ChelaileDataSource.fillDirectionArrivals(match.cityId, line, match.boardName, arrivals);
                    List<ChelaileDataSource.Bus> buses = arrivals.get(line.lineId);
                    int best = Integer.MAX_VALUE;
                    if (buses != null) for (ChelaileDataSource.Bus bus : buses)
                        if (bus.travelTime >= 0) best = Math.min(best, bus.travelTime);
                    message = best != Integer.MAX_VALUE ? "上车站最近一辆 · " + TransitRules.etaLabel(best)
                            : buses != null && !buses.isEmpty() ? "有车辆 · 到站时间待更新"
                            : "暂无上车站预测 · 点击查看线路";
                }
            } catch (Exception error) { message = "到站查询失败 · 点击查看或重试"; }
            final String value = message;
            post(activity, () -> callback.onResult(value));
        });
    }

    /** 根据当前 GPS 推断乘车段进度；地铁走内置站距，公交走车来了站序。 */
    static void loadProgress(Activity activity, ExecutorService worker,
                             ChelaileDataSource.TransitSegment segment,
                             String cityId, String cityName,
                             double userLat, double userLon,
                             RouteProgressCallback callback) {
        worker.execute(() -> {
            ProgressResult text = null;
            try {
                if ("metro".equals(segment.type)) {
                    List<MetroDataSource.MetroLine> all =
                            new MetroDataSource(activity).queryLines();
                    MetroDataSource.MetroLine line = MetroLineResolver.best(all,
                            segment.name, segment.from, segment.to, cityName);
                    text = metroProgress(line, segment, userLat, userLon);
                } else if ("bus".equals(segment.type)) {
                    List<BusCandidate> candidates = busCandidates(cityId, segment);
                    BusCandidate candidate = resolveBusDirection(
                            candidates, segment.from, segment.to);
                    if (candidate != null) {
                        ChelaileDataSource.LineDetail detail = ChelaileDataSource.queryLineDetail(
                                candidate.cityId, candidate.resolvedLineId, userLat, userLon);
                        text = busProgress(detail, segment, userLat, userLon);
                    }
                }
            } catch (Exception ignored) {
            }
            final ProgressResult result = text;
            post(activity, () -> callback.onResult(result));
        });
    }

    static void open(Activity activity, ExecutorService worker,
                     ChelaileDataSource.TransitSegment segment,
                     String cityId, String cityName, double userLat, double userLon) {
        open(activity, worker, segment, cityId, cityName, userLat, userLon, -1);
    }

    static void open(Activity activity, ExecutorService worker,
                     ChelaileDataSource.TransitSegment segment,
                     String cityId, String cityName, double userLat, double userLon, int direction) {
        String railRegion = railRegion(segment);
        if (!railRegion.isEmpty()) {
            openRegional(activity, segment, 0);
        } else if ("metro".equals(segment.type)) {
            openMetro(activity, worker, segment, cityName, direction);
        } else if ("railway".equals(segment.type)) {
            Toast.makeText(activity, "请通过铁路 12306 核对该班次", Toast.LENGTH_SHORT).show();
        } else {
            openBus(activity, worker, segment, cityId, userLat, userLon);
        }
    }

    static String railRegion(ChelaileDataSource.TransitSegment segment) {
        if ("walk".equals(segment.type)) return "";
        if ("intercity".equals(segment.type)) return RailCatalog.GD;
        if ("suburban".equals(segment.type)) return RailCatalog.SH;
        String knownLine = RailCatalog.regionForLine(segment.name);
        if (!knownLine.isEmpty()) return knownLine;
        return RailCatalog.regionForTrip(segment.name, segment.from, segment.to, segment.cityHint);
    }

    static void openRegional(Activity activity, ChelaileDataSource.TransitSegment segment, long ready) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String region = railRegion(segment);
        if (region.isEmpty()) return;
        String from = RailCatalog.station(region, segment.from);
        String to = RailCatalog.station(region, segment.to);
        boolean wrongCity = segment.cityHint != null && !segment.cityHint.trim().isEmpty()
                && RailCatalog.regionForTrip(segment.name, segment.from, segment.to, segment.cityHint).isEmpty();
        if (wrongCity) from = "";
        RailActivity.open(activity, region, segment.name, from, to, ready);
        if (wrongCity || from.isEmpty() || to.isEmpty())
            toast(activity, "部分城际站点未匹配，请重新选择上、下车站");
    }

    private static void openMetro(Activity activity, ExecutorService worker,
                                  ChelaileDataSource.TransitSegment segment,
                                  String cityName, int requestedDirection) {
        worker.execute(() -> {
            try {
                List<MetroDataSource.MetroLine> all =
                        new MetroDataSource(activity).queryLines();
                MetroDataSource.MetroLine line = MetroLineResolver.best(all,
                        segment.name, segment.from, segment.to, cityName);
                if (line == null) {
                    post(activity, () -> toast(activity, "未找到对应的内部地铁线路"));
                    return;
                }
                int direction = requestedDirection == 0 || requestedDirection == 1 ? requestedDirection
                        : resolveMetroDirection(line, segment.from, segment.to, directionHint(segment.name));
                post(activity, () -> {
                    Intent intent = new Intent(activity, MetroLineActivity.class);
                    intent.putExtra("lineName", line.name);
                    intent.putExtra("colour", MetroDataSource.normalizeColour(line.colour));
                    intent.putExtra("direction", direction);
                    activity.startActivity(intent);
                    Ui.transitionForward(activity);
                });
            } catch (Exception error) {
                post(activity, () -> toast(activity, "地铁线路数据加载失败"));
            }
        });
    }

    private static void openBus(Activity activity, ExecutorService worker,
                                ChelaileDataSource.TransitSegment segment,
                                String preferredCityId, double userLat, double userLon) {
        worker.execute(() -> {
            try {
                List<BusCandidate> candidates = busCandidates(preferredCityId, segment);
                BusCandidate resolved = resolveBusDirection(candidates,
                        segment.from, segment.to);
                if (resolved == null) {
                    post(activity, () -> toast(activity, "未找到与“" + segment.from + " → "
                            + segment.to + "”方向一致的公交线路"));
                    return;
                }
                BusCandidate target = resolved;
                post(activity, () -> {
                    activity.startActivity(busIntent(activity, target, userLat, userLon));
                    Ui.transitionForward(activity);
                });
            } catch (Exception error) {
                post(activity, () -> toast(activity, "公交线路匹配失败，请稍后重试"));
            }
        });
    }

    private static Intent busIntent(Activity activity, BusCandidate target, double userLat, double userLon) {
        Intent intent = new Intent(activity, LineDetailActivity.class);
        intent.putExtra("cityId", target.cityId);
        intent.putExtra("lineId", target.resolvedLineId);
        intent.putExtra("lineName", target.line.name);
        intent.putExtra("stationName", target.boardName);
        intent.putExtra("stopLat", target.boardLat);
        intent.putExtra("stopLon", target.boardLon);
        intent.putExtra("userLat", userLat);
        intent.putExtra("userLon", userLon);
        return intent;
    }

    private static List<BusCandidate> busCandidates(String preferredCityId,
                                                     ChelaileDataSource.TransitSegment segment) throws Exception {
        String from=segment.from,to=segment.to;
        String cacheKey=preferredCityId+"|"+segment.name+"|"+from+"|"+to+"|"+segment.terminal+"|"+segment.cityHint
                +"|"+Math.round(segment.boardLat*100000)+"|"+Math.round(segment.boardLon*100000)
                +"|"+Math.round(segment.alightLat*100000)+"|"+Math.round(segment.alightLon*100000);
        BusCandidate cached=RESOLVED.get(cacheKey);
        if(cached!=null && System.currentTimeMillis()-cached.resolvedAt<10*60_000L)
            return java.util.Collections.singletonList(cached);
        String wanted=BusRouteMatch.routeKey(segment.name);
        for(CityInfo city:CityInfo.all())if(wanted.startsWith(city.name)) {
            wanted=wanted.substring(city.name.length()); break;
        }
        for(String candidateCityId:BusRouteMatch.cities(preferredCityId,segment)){
            if(Thread.currentThread().isInterrupted())break;
            CityInfo city=cityById(candidateCityId);
            double lat=TransitRules.validCoordinate(segment.boardLat,segment.boardLon)?segment.boardLat:city==null?23:city.lat;
            double lon=TransitRules.validCoordinate(segment.boardLat,segment.boardLon)?segment.boardLon:city==null?113:city.lon;
            BusCandidate match = null;
            if (!candidateCityId.startsWith("mg:")) {
                try {
                    match = verifyBusCandidates(candidateCityId, wanted, segment,
                            ChelaileDataSource.searchPrimary(candidateCityId, wanted, lat, lon).lines);
                } catch (Exception unavailable) {
                    if (Thread.currentThread().isInterrupted()) throw unavailable;
                }
            }
            if (match == null && !Thread.currentThread().isInterrupted()) {
                try {
                    match = resolveSecondaryBus(candidateCityId, wanted, segment);
                } catch (Exception unavailable) {
                    if (Thread.currentThread().isInterrupted()) throw unavailable;
                }
            }
            if(match!=null){RESOLVED.put(cacheKey,match);return java.util.Collections.singletonList(match);}
        }
        return java.util.Collections.emptyList();
    }

    private static BusCandidate resolveSecondaryBus(String cityId, String wanted,
            ChelaileDataSource.TransitSegment segment) throws Exception {
        List<ChelaileDataSource.SearchLine> lines;
        try {
            lines = MygolbsDataSource.search(cityId, wanted).lines;
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            lines = Collections.emptyList();
        }
        BusCandidate match = verifyBusCandidates(cityId, wanted, segment, lines);
        String city = MygolbsDataSource.cityName(cityId);
        if (match != null || city.isEmpty() || segment.from.isEmpty()
                || Thread.currentThread().isInterrupted()) return match;

        // A station's route list can include directions absent from keyword search.
        java.util.Set<String> known = new java.util.HashSet<>();
        for (ChelaileDataSource.SearchLine line : lines) known.add(line.lineId);
        List<ChelaileDataSource.SearchLine> fromStop = new ArrayList<>();
        for (ChelaileDataSource.StopDetail stop : MygolbsDataSource.stop("mg:" + city + ":" + segment.from)) {
            for (ChelaileDataSource.LineArrival line : stop.lines) {
                if (known.add(line.lineId))
                    fromStop.add(new ChelaileDataSource.SearchLine(line.lineId, line.name, line.name,
                            line.startSn, line.endSn, line.direction));
            }
        }
        return verifyBusCandidates(cityId, wanted, segment, fromStop);
    }

    private static BusCandidate verifyBusCandidates(String cityId, String wanted,
            ChelaileDataSource.TransitSegment segment, List<ChelaileDataSource.SearchLine> lines) {
        List<BusCandidate> candidates = new ArrayList<>();
        String terminal = terminalHint(segment);
        for (ChelaileDataSource.SearchLine line : lines) {
            int score = BusRouteMatch.lineScore(wanted, line.name, line.lineNo);
            if (score < 72) continue;
            if (BusRouteMatch.sameTerminal(line.endSn, terminal)) score += 40;
            BusCandidate candidate = new BusCandidate(cityId, line, score);
            candidate.request = segment;
            candidates.add(candidate);
        }
        if(terminal.isEmpty()){
            List<String> destinations=new ArrayList<>();
            for(BusCandidate candidate:candidates){
                ChelaileDataSource.SearchLine line=candidate.line;
                String destination="";
                if(BusRouteMatch.sameTerminal(line.startSn,segment.from))
                    destination=line.endSn;
                else if(BusRouteMatch.sameTerminal(line.endSn,segment.from))
                    destination=line.startSn;
                if(destination==null||destination.isEmpty())continue;
                boolean known=false;
                for(String existing:destinations)if(BusRouteMatch.sameTerminal(existing,destination)){known=true;break;}
                if(!known)destinations.add(destination);
            }
            // A terminal establishes travel direction, but cannot choose
            // between different branches departing from that same terminal.
            for(BusCandidate candidate:candidates)candidate.allowOriginProof=destinations.size()==1;
        }
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));
        return resolveBusDirection(candidates, segment.from, segment.to);
    }
    private static String terminalHint(ChelaileDataSource.TransitSegment segment) {
        return BusRouteMatch.terminal(segment);
    }
    private static ProgressResult metroProgress(MetroDataSource.MetroLine line,
                                        ChelaileDataSource.TransitSegment segment,
                                        double userLat, double userLon) {
        if (line == null || userLat == 0 || userLon == 0) return null;
        int from = MetroLineResolver.stationIndex(line, segment.from);
        int to = MetroLineResolver.stationIndex(line, segment.to);
        if (from < 0 || to < 0 || from == to) return null;
        double[] gcj = Coord.wgsToGcj(userLat, userLon);
        int nearest = nearestMetroStation(line, gcj[0], gcj[1]);
        if (nearest < 0) return null;
        double distance = GeoUtils.distanceKm(gcj[0], gcj[1],
                line.stations.get(nearest).lat, line.stations.get(nearest).lon);
        int low = Math.min(from, to), high = Math.max(from, to);
        if (distance > 1.5 || nearest < low || nearest > high) return null;
        int remaining = Math.abs(to - nearest);
        int minutes = metroMinutes(line, nearest, to);
        return new ProgressResult("定位在 " + line.stations.get(nearest).name + " 附近 · 到 "
                + segment.to + " 还有 " + remaining + " 站 · 约 " + minutes + " 分钟",
                distance, Math.abs(nearest - from) / (float)Math.abs(to - from));
    }

    private static int nearestMetroStation(MetroDataSource.MetroLine line,
                                           double lat, double lon) {
        int best = -1;
        double bestKm = Double.MAX_VALUE;
        for (int i = 0; i < line.stations.size(); i++) {
            MetroDataSource.Station station = line.stations.get(i);
            if (station.lat == 0 || station.lon == 0) continue;
            double km = GeoUtils.distanceKm(lat, lon, station.lat, station.lon);
            if (km < bestKm) { bestKm = km; best = i; }
        }
        return best;
    }

    private static int metroMinutes(MetroDataSource.MetroLine line, int from, int to) {
        int minutes = 0;
        int low = Math.min(from, to), high = Math.max(from, to);
        for (int i = low; i < high; i++) {
            MetroDataSource.Station station = line.stations.get(i);
            minutes += station.segMin > 0 ? station.segMin
                    : Math.max(1, (int) Math.round(station.segKm / 0.55));
        }
        return Math.max(0, minutes);
    }

    private static ProgressResult busProgress(ChelaileDataSource.LineDetail detail,
                                      ChelaileDataSource.TransitSegment segment,
                                      double userLat, double userLon) {
        if (detail == null || detail.stations == null || detail.stations.isEmpty()
                || userLat == 0 || userLon == 0) return null;
        int from = stationIndex(detail, segment.from);
        int to = stationIndex(detail, segment.to);
        int nearest = nearestBusStation(detail, userLat, userLon);
        if (from < 0 || to <= from || nearest < from || nearest > to) return null;
        ChelaileDataSource.LineStation current = detail.stations.get(nearest);
        double distance = current.lat == 0 || current.lon == 0 ? Double.MAX_VALUE
                : GeoUtils.distanceKm(userLat, userLon, current.lat, current.lon);
        if (distance > 1.5) return null;
        int remaining = Math.max(0, to - nearest);
        int fullStops = Math.max(1, to - from);
        int estimate = segment.durationMin > 0
                ? Math.max(0, (int) Math.ceil(segment.durationMin * remaining / (double) fullStops))
                : remaining * 3;
        return new ProgressResult("定位在 " + current.name + " 附近 · 到 " + segment.to + " 还有 "
                + remaining + " 站 · 按行程估算约 " + estimate + " 分钟",
                distance, (nearest - from) / (float)(to - from));
    }

    private static int nearestBusStation(ChelaileDataSource.LineDetail detail,
                                         double lat, double lon) {
        int best = -1;
        double bestKm = Double.MAX_VALUE;
        for (int i = 0; i < detail.stations.size(); i++) {
            ChelaileDataSource.LineStation station = detail.stations.get(i);
            if (station.lat == 0 || station.lon == 0) continue;
            double km = GeoUtils.distanceKm(lat, lon, station.lat, station.lon);
            if (km < bestKm) { bestKm = km; best = i; }
        }
        return best;
    }

    private static BusCandidate resolveBusDirection(List<BusCandidate> candidates,String from,String to) {
        int checked=0;
        java.util.Set<String> checkedIds=new java.util.HashSet<>();
        for(BusCandidate candidate:candidates){
            if(candidate.resolvedLineId!=null)return candidate;
            if(Thread.currentThread().isInterrupted()||checked>=8)break;
            if(!checkedIds.add(candidate.cityId+"|"+candidate.line.lineId))continue;
            checked++;
            ChelaileDataSource.TransitSegment request=candidate.request;
            double lat=request==null?0:request.boardLat,lon=request==null?0:request.boardLon;
            try {
                ChelaileDataSource.LineDetail detail=ChelaileDataSource.queryLineDetail(candidate.cityId,candidate.line.lineId,lat,lon,from);
                if(acceptDirection(candidate,detail,from,to))return candidate;
                if(detail.reverseLineId!=null&&!detail.reverseLineId.isEmpty()
                        &&checkedIds.add(candidate.cityId+"|"+detail.reverseLineId)){
                    ChelaileDataSource.LineDetail reverse=ChelaileDataSource.queryLineDetail(candidate.cityId,detail.reverseLineId,lat,lon,from);
                    if(acceptDirection(candidate,reverse,from,to))return candidate;
                }
            } catch(Exception unavailable) { }
        }
        return null;
    }
    private static boolean acceptDirection(BusCandidate candidate,ChelaileDataSource.LineDetail detail,String from,String to) {
        if(detail==null||detail.stations==null)return false;
        ChelaileDataSource.TransitSegment request=candidate.request;
        if(request==null)return false;
        ChelaileDataSource.TransitSegment proof=new ChelaileDataSource.TransitSegment("bus",request.name,from,to,0,0,0);
        proof.boardLat=request.boardLat;proof.boardLon=request.boardLon;proof.terminal=terminalHint(request);
        proof.alightLat=request.alightLat;proof.alightLon=request.alightLon;
        int board=BusRouteMatch.directionBoard(detail,proof,candidate.allowOriginProof);
        if(board<0)return false;
        ChelaileDataSource.LineStation station=detail.stations.get(board);
        candidate.resolvedLineId=detail.lineId;candidate.boardName=station.name;
        candidate.boardLat=station.lat;candidate.boardLon=station.lon;
        if(request!=null&&TransitRules.validCoordinate(request.boardLat,request.boardLon)){
            candidate.boardLat=request.boardLat;candidate.boardLon=request.boardLon;
        }
        candidate.resolvedAt=System.currentTimeMillis();candidate.resolvedTerminal=detail.endSn;
        return true;
    }
    private static int resolveMetroDirection(MetroDataSource.MetroLine line,
                                             String from, String to, String hint) {
        if (line.ring) {
            if ("内环".equals(hint)) return 1;
            if ("外环".equals(hint)) return 0;
        }
        int fromIndex = MetroLineResolver.stationIndex(line, from);
        int toIndex = MetroLineResolver.stationIndex(line, to);
        return fromIndex >= 0 && toIndex >= 0 && fromIndex > toIndex ? 1 : 0;
    }

    private static String directionHint(String name) {
        if (name == null) return "";
        if (name.contains("内环")) return "内环";
        if (name.contains("外环")) return "外环";
        return "";
    }

    private static int stationIndex(ChelaileDataSource.LineDetail detail, String station) {
        if (detail == null || detail.stations == null) return -1;
        String wanted = normStop(station);
        for (int i = 0; i < detail.stations.size(); i++) {
            String actual = normStop(detail.stations.get(i).name);
            if (!wanted.isEmpty() && actual.equals(wanted)) return i;
        }
        return -1;
    }

    private static String normStop(String value) {
        return TransitRules.stationKey(value).replace("朗", "塱");
    }

    private static String normalizeBusName(String value) {
        return MetroLineResolver.displayName(value == null ? "" : value)
                .replaceAll("[（(].*?[）)]", "")
                .replace("公交", "").replace("路", "").replace("线", "")
                .replace(" ", "").trim();
    }

    private static int busNameScore(String wanted, String name, String lineNo) {
        String a = normalizeBusName(wanted);
        String b = normalizeBusName(name);
        String c = normalizeBusName(lineNo);
        if (a.equals(b) || a.equals(c)) return 120;
        if ((!a.isEmpty() && b.contains(a)) || (!b.isEmpty() && a.contains(b))) return 72;
        if ((!a.isEmpty() && c.contains(a)) || (!c.isEmpty() && a.contains(c))) return 62;
        return 0;
    }

    private static CityInfo cityById(String id) {
        for (CityInfo city : CityInfo.all()) {
            if (city.chelaileId.equals(id)) return city;
        }
        return null;
    }

    private static void post(Activity activity, Runnable action) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            action.run();
        });
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static final class BusCandidate {
        final String cityId;
        final ChelaileDataSource.SearchLine line;
        int score;
        String resolvedLineId;
        String boardName, resolvedTerminal;
        ChelaileDataSource.TransitSegment request;
        boolean allowOriginProof=true;
        double boardLat;
        double boardLon;
        long resolvedAt;
        BusCandidate(String cityId, ChelaileDataSource.SearchLine line, int score) {
            this.cityId = cityId;
            this.line = line;
            this.score = score;
        }
    }
}
