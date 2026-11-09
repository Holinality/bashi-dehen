package com.holi.prdbus;
import java.util.*;

public final class MetroRoutingSelfTest {
    private static MetroDataSource.Station stop(String name,double lat,double lon,int minutes) {
        return new MetroDataSource.Station(name,lat,lon,1,minutes,Collections.emptyList(),false,"","","","","");
    }
    private static MetroDataSource.MetroLine line(String name,MetroDataSource.Station... stops) {
        MetroDataSource.MetroLine line=new MetroDataSource.MetroLine(name,"2466A8","",false,"06:00","23:00","06:00","23:00");
        line.stations.addAll(Arrays.asList(stops));return line;
    }
    private static void require(boolean condition,String value){if(!condition)throw new AssertionError(value);}
    public static void main(String[] args) {
        MetroDataSource.Station a=stop("起站",23,113,15),m=stop("中站",23.01,113.01,15),b=stop("终站",23.02,113.02,15);
        MetroDataSource.Station fa=stop("起站",23,113,2),x=stop("换乘",23.015,113.015,2),fb=stop("终站",23.02,113.02,2);
        MetroNetwork network=new MetroNetwork(MetroNetwork.Area.GUANGFO,Arrays.asList(line("广州101号线",a,m,b),line("广州102号线",fa,x),line("广州103号线",x,fb)));
        MetroNetwork.Stop origin=network.search("起站").get(0),destination=network.search("终站").get(0);
        MetroNetwork.Journey fastest=network.plan(origin,destination,MetroNetwork.Preference.FASTEST,-1);
        MetroNetwork.Journey simple=network.plan(origin,destination,MetroNetwork.Preference.FEWER_TRANSFERS,-1);
        require(fastest!=null&&fastest.tracks.size()==2&&fastest.plan.durationMin<simple.plan.durationMin,"Fastest path not chosen");
        require(simple.tracks.size()==1,"Fewer-transfer label discarded");
        require(network.plan(destination,origin,MetroNetwork.Preference.FEWER_TRANSFERS,-1).directions[0]==1,"Reverse direction wrong");
        Calendar time=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));time.set(2026,10,6,9,2,0);time.set(Calendar.MILLISECOND,0);
        MetroNetwork.Journey scheduled=network.plan(origin,destination,MetroNetwork.Preference.FASTEST,time.getTimeInMillis());
        require(scheduled!=null&&scheduled.plan.durationMin>=fastest.plan.durationMin,"Waiting shortened a journey");
        int total=0;for(ChelaileDataSource.TransitSegment segment:scheduled.plan.segments)total+=segment.durationMin;
        require(total==scheduled.plan.durationMin,"Wait and transfer cost missing from total");
        require(network.plan(origin,origin,MetroNetwork.Preference.FASTEST,-1)==null,"Same station produced a loop");
        // Two service patterns can have the same displayed name. Re-resolving
        // the finished route by that name must not pick the wrong branch.
        MetroNetwork branches=new MetroNetwork(MetroNetwork.Area.GUANGFO,Arrays.asList(
                line("广州104号线",a,m),line("广州104号线",m,b)));
        require(branches.plan(branches.search("中站").get(0),branches.search("终站").get(0))!=null,"Same-name service branch was lost");
        require("惠州".equals(RailCatalog.cityOfStation(RailCatalog.GD,"惠州北")),"Huizhou picker city wrong");
        require("东莞".equals(RailCatalog.cityOfStation(RailCatalog.GD,"西平西")),"Dongguan picker city wrong");
        require("佛山".equals(RailCatalog.cityOfStation(RailCatalog.GD,"三水北")),"Foshan picker city wrong");
        System.out.println("MetroRoutingSelfTest OK");
    }
}
