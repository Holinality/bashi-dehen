package com.holi.prdbus;
import java.util.*;
public final class MetroScheduleSelfTest {
 public static void main(String[] args){
  MetroDataSource.MetroLine line=new MetroDataSource.MetroLine("广州1号线","FF0000","",false,"06:00","23:00","06:00","23:00");
  line.stations.add(stop("甲","06:00","06:06"));line.stations.add(stop("乙","06:03","06:03"));line.stations.add(stop("丙","06:06","06:00"));
  MetroSchedule forward=new MetroSchedule(line,line.stations,true,.55),reverse=new MetroSchedule(line,line.stations,false,.55);
  require(forward.arrivals(1,362,1).get(0).equals("06:03"),"station first anchor lost");
  require(Math.abs(forward.positions(362.5).get(0)-2.5/3)<.0001,"seconds interpolation mismatch");
  require(forward.positions(363).contains(1.0),"arrival label and vehicle disagree");
  require(reverse.positions(363).contains(1.0),"reverse direction mismatch");
  forward.departures.add(361);
  require(forward.positions(362.5).size()==1,"a station interval must have at most one marker");
  require(!forward.arrivals(1,363.1,1).get(0).equals("06:03"),"departed train remains next arrival");
  require(forward.positions(350).isEmpty(),"train before service");
  require(forward.positions(1439).isEmpty(),"train after service");
  MetroDataSource.MetroLine missing=new MetroDataSource.MetroLine("广州1号线","FF0000","",false,"06:00","23:00","06:00","23:00");
  missing.stations.add(stop("甲","",""));missing.stations.add(stop("乙","",""));
  require(new MetroSchedule(missing,missing.stations,true,.55).arrivals(1,360,1).get(0).equals("06:02"),"line fallback has zero station offset");
  require(MetroTimes.stationFirst(missing, missing.stations.get(1), true).equals("06:02"),"offline station window ignores travel time");
  MetroDataSource.MetroLine midnight=new MetroDataSource.MetroLine("广州1号线","FF0000","",false,"23:58","00:10","23:58","00:10");
  midnight.stations.add(stop("甲","",""));midnight.stations.add(stop("乙","",""));
  MetroSchedule overnight=new MetroSchedule(midnight,midnight.stations,true,.55);
  require(overnight.arrivals(1,0,1).get(0).equals("00:00"),"midnight service missing");
  require(overnight.arrivals(0,-1440,1500).get(0).equals("23:58"),"whole-day table loses pre-midnight trains");
  MetroDataSource.MetroLine hk=new MetroDataSource.MetroLine("香港机场快线","008577","",false,"10:00","10:11","10:00","10:11");
  hk.stations.add(new MetroDataSource.Station("香港",22.285,114.16,1,0,new ArrayList<>(),false,"","10:00","10:11","10:00","10:11"));
  hk.stations.add(new MetroDataSource.Station("九龙",22.303,114.16,1,0,new ArrayList<>(),false,"","10:18","10:11","10:02","10:11"));
  MetroSchedule airport=new MetroSchedule(hk,hk.stations,true,.55);
  require(airport.offsets[1]==2,"another service's first train inflated the segment travel time");
  require(airport.departures.equals(Arrays.asList(600,611)),"last train was appended next to an estimated train");
  require(MetroTimes.intervalAt(400,"香港机场快线")==10,"Airport Express constant headway changed");
  MetroDataSource.MetroLine topology=new MetroDataSource.MetroLine("示例地铁线","2466A8","",false,"","","","");
  topology.stations.add(new MetroDataSource.Station("示例甲",23,113,1,0,new ArrayList<>(),false,"","","","",""));
  topology.stations.add(new MetroDataSource.Station("示例乙",23.01,113.01,0,0,new ArrayList<>(),false,"","","","",""));
  MetroSchedule unknown=new MetroSchedule(topology,topology.stations,true,.55);
  require(unknown.departures.isEmpty()&&unknown.positions(720).isEmpty()&&unknown.arrivals(0,720,3).isEmpty(),
          "topology-only public data invented departure times or train positions");
  require(MetroTimes.stationFirst(topology,topology.stations.get(0),true).equals("--"),
          "missing timetable was replaced with a default service window");
  System.out.println("MetroScheduleSelfTest passed (shared labels/positions, seconds, reverse, fallback)");
 }
 static MetroDataSource.Station stop(String name,String first,String reverse){return new MetroDataSource.Station(name,23,113,1,2,new ArrayList<>(),false,"",first,"23:10",reverse,"23:10");}
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
