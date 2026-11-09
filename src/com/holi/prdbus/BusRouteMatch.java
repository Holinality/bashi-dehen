package com.holi.prdbus;
import java.util.*;

/** Match within a known bus route; do not use these aliases to classify a POI as metro/bus. */
final class BusRouteMatch {
    private static final java.util.regex.Pattern PARENTHESES =
            java.util.regex.Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*\\]|【[^【】]*】");
    private static final java.util.regex.Pattern PLATFORM_QUALIFIER =
            java.util.regex.Pattern.compile(".*(?:[0-9]|[A-Za-z]|站台|上行|下行|内环|外环|入口|出口|[一二三四五六七八九十百]+号|[东西南北](?:侧|行|向|门|口|区|路)|路[东西南北]).*|[东西南北内外一二三四五六七八九十]");
    static String stopKey(String text) {
        String normalized=java.text.Normalizer.normalize(text==null?"":text,java.text.Normalizer.Form.NFKC);
        String value=TransitRules.stationKey(normalized).replace("朗","塱");
        return value.replace('（','(').replace('）',')')
                .replaceAll("^地铁(.+?)站$", "$1").replaceFirst("公交站$", "");
    }
    /** Optional landmark explanations may differ across providers. Platform
     * numbers, entrance letters and directional qualifiers retain identity. */
    private static String descriptionKey(String text) {
        java.util.regex.Matcher parts=PARENTHESES.matcher(stopKey(text));
        StringBuffer result=new StringBuffer();
        while(parts.find()){
            String note=parts.group().substring(1,parts.group().length()-1);
            parts.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(
                    PLATFORM_QUALIFIER.matcher(note).matches()?parts.group():""));
        }
        parts.appendTail(result);
        return result.toString();
    }
    static int station(List<ChelaileDataSource.LineStation> stops,String name,double lat,double lon) {
        String wanted=stopKey(name); if(wanted.isEmpty())return -1;
        boolean located=TransitRules.validCoordinate(lat,lon);
        int exact=-1;double bestExact=Double.POSITIVE_INFINITY;
        for(int i=0;i<stops.size();i++)if(wanted.equals(stopKey(stops.get(i).name))){
            ChelaileDataSource.LineStation stop=stops.get(i);
            if(exact<0)exact=i;
            if(located&&TransitRules.validCoordinate(stop.lat,stop.lon)){
                double distance=GeoUtils.distanceKm(lat,lon,stop.lat,stop.lon);
                if(distance<bestExact){bestExact=distance;exact=i;}
            }
        }
        if(exact>=0)return exact;
        String description=descriptionKey(name);
        List<Integer> aliases=new ArrayList<>();
        if(!description.isEmpty())for(int i=0;i<stops.size();i++)
            if(description.equals(descriptionKey(stops.get(i).name)))aliases.add(i);
        if(!aliases.isEmpty()){
            if(aliases.size()==1){
                int index=aliases.get(0);
                ChelaileDataSource.LineStation stop=stops.get(index);
                if(!located||!TransitRules.validCoordinate(stop.lat,stop.lon)
                        ||GeoUtils.distanceKm(lat,lon,stop.lat,stop.lon)<=.3)return index;
                return -1;
            }
            if(!located)return -1;
            int selected=-1;double nearest=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
            for(int index:aliases){
                ChelaileDataSource.LineStation stop=stops.get(index);
                if(!TransitRules.validCoordinate(stop.lat,stop.lon))continue;
                double distance=GeoUtils.distanceKm(lat,lon,stop.lat,stop.lon);
                if(distance<nearest){second=nearest;nearest=distance;selected=index;}
                else second=Math.min(second,distance);
            }
            return selected>=0&&nearest<=.3&&second-nearest>.015?selected:-1;
        }
        if(!located)return -1;
        int best=-1; double nearest=.3;
        for(int i=0;i<stops.size();i++){
            ChelaileDataSource.LineStation stop=stops.get(i);String actual=stopKey(stop.name);
            boolean related=actual.equals(wanted+"站")||wanted.equals(actual+"站")
                    || (Math.min(actual.length(),wanted.length())>=3 && (actual.contains(wanted)||wanted.contains(actual)));
            String qualifiedWanted=wanted.replaceFirst("站(?=\\()", "");
            String qualifiedActual=actual.replaceFirst("站(?=\\()", "");
            related|=qualifiedWanted.startsWith(qualifiedActual+"(")
                    ||qualifiedActual.startsWith(qualifiedWanted+"(");
            if(!related || !TransitRules.validCoordinate(stop.lat,stop.lon))continue;
            double distance=GeoUtils.distanceKm(lat,lon,stop.lat,stop.lon);
            if(distance<nearest){nearest=distance;best=i;}
        }
        return best;
    }
    static boolean sameTerminal(String a,String b) {
        if(a==null||b==null||a.isEmpty()||b.isEmpty())return false;
        String x=descriptionKey(a),y=descriptionKey(b);
        if(x.isEmpty()||y.isEmpty())return false;
        if(x.equals(y))return true;
        x=x.replaceAll("(公交)?总站$","");y=y.replaceAll("(公交)?总站$","");
        return !x.isEmpty()&&x.equals(y);
    }
    static String terminal(ChelaileDataSource.TransitSegment segment) {
        String explicit=segment.terminal==null?"":segment.terminal.trim();
        if(!explicit.isEmpty()&&!explicit.equals("[]")&&!explicit.equals("null"))return explicit;
        java.util.regex.Matcher brackets=java.util.regex.Pattern.compile("[（(]([^()（）]+)[）)]")
                .matcher(segment.name==null?"":segment.name);
        while(brackets.find()){
            String[] ends=brackets.group(1).split("--|→|—|↔|⇄|至|-");
            if(ends.length>=2)return ends[ends.length-1].trim();
        }
        return "";
    }
    static int directionBoard(ChelaileDataSource.LineDetail detail,ChelaileDataSource.TransitSegment request) {
        return directionBoard(detail,request,true);
    }
    static int directionBoard(ChelaileDataSource.LineDetail detail,ChelaileDataSource.TransitSegment request,
                              boolean allowOriginProof) {
        if(detail==null||detail.stations==null)return -1;
        int board=station(detail.stations,request.from,request.boardLat,request.boardLon);
        int alight=station(detail.stations,request.to,request.alightLat,request.alightLon);
        if(board>=0 && alight>=0 && alight<=board){
            int later=station(detail.stations.subList(board+1,detail.stations.size()),
                    request.to,request.alightLat,request.alightLon);
            if(later>=0){
                later+=board+1;
                if(!TransitRules.validCoordinate(request.alightLat,request.alightLon)){
                    alight=later;
                }else{
                    ChelaileDataSource.LineStation first=detail.stations.get(alight),next=detail.stations.get(later);
                    if(TransitRules.validCoordinate(first.lat,first.lon)
                            &&TransitRules.validCoordinate(next.lat,next.lon)
                            &&GeoUtils.distanceKm(request.alightLat,request.alightLon,next.lat,next.lon)
                            <=GeoUtils.distanceKm(request.alightLat,request.alightLon,first.lat,first.lon)+.015)
                        alight=later;
                }
            }
        }
        String terminal=terminal(request);
        boolean end=sameTerminal(detail.endSn,terminal);
        boolean origin=allowOriginProof&&terminal.isEmpty()&&board==0&&alight<0
                &&sameTerminal(detail.startSn,request.from)
                &&sameTerminal(detail.stations.get(0).name,detail.startSn)
                &&!sameTerminal(detail.startSn,detail.endSn)
                &&nearRoute(detail.stations,request.alightLat,request.alightLon);
        return board>=0 && board<detail.stations.size()-1
                && (alight>board || (alight<0 && end) || origin) ? board : -1;
    }
    private static boolean nearRoute(List<ChelaileDataSource.LineStation> stops,double lat,double lon) {
        if(!TransitRules.validCoordinate(lat,lon))return true;
        ChelaileDataSource.LineStation previous=null;
        double longitudeScale=Math.cos(Math.toRadians(lat));
        for(ChelaileDataSource.LineStation stop:stops){
            if(!TransitRules.validCoordinate(stop.lat,stop.lon)){previous=null;continue;}
            if(GeoUtils.distanceKm(lat,lon,stop.lat,stop.lon)<=1)return true;
            if(previous!=null){
                double dx=(stop.lon-previous.lon)*longitudeScale,dy=stop.lat-previous.lat;
                double length=dx*dx+dy*dy;
                double position=length==0?0:((lon-previous.lon)*longitudeScale*dx+(lat-previous.lat)*dy)/length;
                position=Math.max(0,Math.min(1,position));
                if(GeoUtils.distanceKm(lat,lon,previous.lat+position*(stop.lat-previous.lat),
                        previous.lon+position*(stop.lon-previous.lon))<=1)return true;
            }
            previous=stop;
        }
        return false;
    }
    static String cityHint(String value) {
        if(value==null)return "";
        String[] codes={"020","0755","0757","0769","0760","0756","0750","0752","0758"};
        String[] adcodes={"4401","4403","4406","4419","4420","4404","4407","4413","4412"};
        for(int i=0;i<CityInfo.PRD_CITIES.length;i++)if(value.equals(codes[i])||value.startsWith(adcodes[i])||value.startsWith(CityInfo.PRD_CITIES[i].name))return CityInfo.PRD_CITIES[i].chelaileId;
        for (CityInfo city : CityInfo.all()) if (value.startsWith(city.name)) return city.chelaileId;
        return "";
    }
    static List<String> cities(String preferred,ChelaileDataSource.TransitSegment segment) {
        LinkedHashSet<String> cities=new LinkedHashSet<>();
        String explicit=cityHint(segment.cityHint); if(!explicit.isEmpty())cities.add(explicit);
        String named=cityHint(segment.name); if(!named.isEmpty())cities.add(named);
        CityInfo near=TransitRules.validCoordinate(segment.boardLat,segment.boardLon)?CityInfo.nearest(segment.boardLat,segment.boardLon):null;
        if(near!=null)cities.add(near.chelaileId);
        if(preferred!=null&&!preferred.isEmpty())cities.add(preferred);
        CityInfo scope = near;
        if (scope == null) for (CityInfo city : CityInfo.all())
            if (city.chelaileId.equals(preferred)) { scope = city; break; }
        String province = scope == null ? "" : Regions.province(scope.name);
        List<CityInfo> rest = new ArrayList<>();
        for (CityInfo city : CityInfo.all()) {
            if (!province.isEmpty() && province.equals(Regions.province(city.name))
                    && TransitRules.validCoordinate(city.lat, city.lon)) rest.add(city);
        }
        if(near!=null)Collections.sort(rest,(a,b)->Double.compare(GeoUtils.distanceKm(segment.boardLat,segment.boardLon,a.lat,a.lon),GeoUtils.distanceKm(segment.boardLat,segment.boardLon,b.lat,b.lon)));
        for(CityInfo city:rest) {
            if (cities.size() >= 4) break;
            cities.add(city.chelaileId);
        }
        return new ArrayList<>(cities);
    }
    static String routeKey(String value) {
        return MetroLineResolver.displayName(value==null?"":value).replaceAll("[（(].*?[）)]","")
                .replace("公交","").replace("路","").replace("线","").replace(" ","").trim().toUpperCase(Locale.ROOT);
    }
    static int lineScore(String wanted,String actual,String lineNo) {
        String a=routeKey(wanted),b=routeKey(actual),c=routeKey(lineNo);
        if(a.isEmpty())return 0;
        if(a.equals(b)||a.equals(c))return 120;
        java.util.regex.Matcher number=java.util.regex.Pattern.compile("\\d+[A-Z]?").matcher(a);
        if(number.find()){
            String token=number.group();
            if(!token.equals(firstNumber(b)) && !token.equals(firstNumber(c)))return 0;
        }
        return (!b.isEmpty()&&(b.contains(a)||a.contains(b)))||(!c.isEmpty()&&(c.contains(a)||a.contains(c)))?72:0;
    }
    private static String firstNumber(String value){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\d+[A-Z]?").matcher(value);return m.find()?m.group():"";}
}
