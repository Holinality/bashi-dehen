package com.holi.prdbus;
import java.util.*;
public final class BusRouteMatchSelfTest {
 public static void main(String[] args){
  ChelaileDataSource.TransitSegment request=new ChelaileDataSource.TransitSegment("bus","桂18路","地铁西约站","九龙公园南",0,8,27);
  request.cityHint="佛山";request.terminal="九龙公园南";
  require(BusRouteMatch.cities("040",request).get(0).equals("019"),"origin city overrides bus boarding city");
  require(BusRouteMatch.cityHint("440600").equals("019"),"city adcode lost");
  require(BusRouteMatch.cityHint("0757").equals("019"),"city code lost");
  require(BusRouteMatch.lineScore("17路","117路","117")==0,"partial line number mismatch");
  require(BusRouteMatch.lineScore("旅游专线","","")==0,"empty route identity accepted");
  require(BusRouteMatch.lineScore("桂18路","桂18路(西约--九龙公园南)","桂18")==120,"route identity lost");
  List<ChelaileDataSource.LineStation> stops=Arrays.asList(stop("西约(地铁站)",23,113),stop("中间站",23.01,113.01),stop("九龙公园南",23.02,113.02));
  ChelaileDataSource.LineDetail forward=line(stops,"九龙公园南");
  require(BusRouteMatch.directionBoard(forward,request)==0,"boarding alias cannot open correct direction");
  List<ChelaileDataSource.LineStation> reversed=new ArrayList<>(stops);Collections.reverse(reversed);
  require(BusRouteMatch.directionBoard(line(reversed,"西约"),request)<0,"reverse vehicle accepted");
  request=new ChelaileDataSource.TransitSegment("bus","桂18路","地铁西约站","另一种下车站名称",0,8,27);
  request.terminal="九龙公园南";
  require(BusRouteMatch.directionBoard(forward,request)==0,"explicit terminal proof ignored");
  request.terminal="其他终点";
  require(BusRouteMatch.directionBoard(forward,request)<0,"ambiguous direction accepted");
  require(BusRouteMatch.station(stops,"西约站(北侧)",0,0)<0,"unproven fuzzy station accepted");
  require(BusRouteMatch.station(stops,"西约站(北侧)",23,113)==0,"coordinate-backed boarding alias rejected");
  require(BusRouteMatch.station(Arrays.asList(stop("九龙公园南（公交总站）",23,113)),
          "九龙公园南(公交总站)",0,0)==0,"full-width qualifier punctuation changed station identity");
  request=new ChelaileDataSource.TransitSegment("bus","桂18路","地铁西约站","九龙公园南站(东侧)",0,8,27);
  require(BusRouteMatch.directionBoard(forward,request,false)<0,"unproven alighting alias accepted");
  request.alightLat=23.02;request.alightLon=113.02;
  require(BusRouteMatch.directionBoard(forward,request)==0,"coordinate-backed alighting alias rejected");
  require(BusRouteMatch.directionBoard(line(reversed,"西约"),request)<0,"alighting coordinates accepted reverse direction");
  request.alightLat=24;request.alightLon=114;
  require(BusRouteMatch.directionBoard(forward,request)<0,"distant alighting alias accepted");
  List<ChelaileDataSource.LineStation> repeated=Arrays.asList(
          stop("终点",23,113),stop("上车点",23.01,113.01),stop("终点",23.02,113.02));
  request=new ChelaileDataSource.TransitSegment("bus","环线","上车点","终点",0,1,4);
  require(BusRouteMatch.directionBoard(line(repeated,"终点"),request)==1,"repeated alighting name ignored after boarding");
  request.alightLat=23;request.alightLon=113;
  require(BusRouteMatch.directionBoard(line(repeated,"终点"),request)<0,"located stop before boarding accepted");
  List<ChelaileDataSource.LineStation> loop=Arrays.asList(
          stop("终点",23,113),stop("上车点",23.01,113.01),stop("终点",23,113));
  require(BusRouteMatch.directionBoard(line(loop,"终点"),request)==1,"same physical loop terminal ignored after boarding");
  List<ChelaileDataSource.LineStation> repeatedBoard=Arrays.asList(
          stop("上车点",23,113),stop("中途",23.01,113.01),
          stop("上车点",23.02,113.02),stop("终点",23.03,113.03));
  request=new ChelaileDataSource.TransitSegment("bus","环线","上车点","终点",0,1,4);
  request.boardLat=23.02;request.boardLon=113.02;
  require(BusRouteMatch.directionBoard(line(repeatedBoard,"终点"),request)==2,"repeated boarding name picked first occurrence");
  List<ChelaileDataSource.LineStation> guangzhou508=Arrays.asList(
          stop("广州火车东站总站",23.15,113.32),stop("林和西路③",23.149,113.321),
          stop("时代广场②",23.147,113.319),stop("家和天曜总站",23.19,113.4));
  request=new ChelaileDataSource.TransitSegment("bus","508路(广州火车东站总站--家和天曜总站)",
          "林和西路3","时代广场2",0,1,9);
  require(BusRouteMatch.directionBoard(line(guangzhou508,"家和天曜总站"),request)==1,
          "Guangzhou platform digits differ from circled provider digits");
  require(BusRouteMatch.station(guangzhou508,"林和西路1",23.149,113.321)<0,
          "different platform number accepted at the same coordinate");
  request=new ChelaileDataSource.TransitSegment("bus","508路(广州火车东站总站--家和天曜总站)",
          "林和西路3","未提供的下车别名",0,1,9);
  require(BusRouteMatch.directionBoard(line(guangzhou508,"家和天曜总站"),request)==1,
          "terminal from provider route name not used for direction");
  List<ChelaileDataSource.LineStation> back508=new ArrayList<>(guangzhou508);Collections.reverse(back508);
  require(BusRouteMatch.directionBoard(line(back508,"广州火车东站总站"),request)<0,
          "terminal proof accepted the reverse direction");
  List<ChelaileDataSource.LineStation> foshan210=Arrays.asList(
          stop("芳村大道西（滘口客运站）总站",23.118216,113.202496),
          stop("大转弯（黄岐凯民茶博城）",23.107996,113.176346),
          stop("大沥城区南（新都会）",23.112296,113.120456),
          stop("大沥汽车客运站",23.123656,113.097566));
  require(BusRouteMatch.lineScore("210路","佛210","佛263短线")>=72,"cross-city public route alias rejected");
  require(BusRouteMatch.station(foshan210,"芳村大道西总站",0,0)==0,"infix landmark annotation rejected");
  require(BusRouteMatch.station(foshan210,"大转弯",0,0)==1,"suffix landmark annotation rejected");
  for(String annotated:new String[]{"大转弯[黄岐凯民茶博城]","大转弯【黄岐凯民茶博城】"})
   require(BusRouteMatch.station(Arrays.asList(stop(annotated,23,113)),"大转弯",0,0)==0,
           "descriptive square bracket alias rejected");
  require(BusRouteMatch.sameTerminal("芳村大道西总站","芳村大道西（滘口客运站）总站"),
          "annotated terminal not equivalent");
  request=new ChelaileDataSource.TransitSegment("bus","210路","芳村大道西总站","岭南",0,10,35);
  ChelaileDataSource.LineDetail out210=new ChelaileDataSource.LineDetail("out","佛210",0,
          "芳村大道西（滘口客运站）总站","大沥汽车客运站","06:00","00:00","2",
          new ArrayList<>(),foshan210,"back");
  require(BusRouteMatch.directionBoard(out210,request)==0,"verified origin terminal direction rejected unknown alight");
  require(BusRouteMatch.directionBoard(out210,request,false)<0,"ambiguous branch bypassed origin guard");
  request.terminal="其他总站";
  require(BusRouteMatch.directionBoard(out210,request)<0,"origin proof overrode explicit conflicting terminal");
  request.terminal="大沥汽车客运站";
  require(BusRouteMatch.directionBoard(out210,request)==0,"explicit destination proof rejected");
  List<ChelaileDataSource.LineStation> back210=new ArrayList<>(foshan210);Collections.reverse(back210);
  ChelaileDataSource.LineDetail in210=new ChelaileDataSource.LineDetail("back","佛210",1,
          "大沥汽车客运站","芳村大道西（滘口客运站）总站","06:00","00:00","2",
          new ArrayList<>(),back210,"out");
  request.terminal="";
  require(BusRouteMatch.directionBoard(in210,request)<0,"arrival terminal mistaken for departing direction");
  request.alightLat=23.118;request.alightLon=113.09;
  List<ChelaileDataSource.LineStation> sparseRoute=Arrays.asList(
          stop("芳村大道西（滘口客运站）总站",23.118,113.2),
          stop("大沥汽车客运站",23.118,113.0));
  ChelaileDataSource.LineDetail sparse210=new ChelaileDataSource.LineDetail("sparse","佛210",0,
          sparseRoute.get(0).name,sparseRoute.get(1).name,"06:00","00:00","2",new ArrayList<>(),sparseRoute,"back");
  require(BusRouteMatch.directionBoard(sparse210,request)==0,"unknown station between distant stops blocked direction browsing");
  request.alightLat=24;request.alightLon=114;
  require(BusRouteMatch.directionBoard(sparse210,request)<0,"far-away unknown stop bypassed route check");
  List<ChelaileDataSource.LineStation> describedTwice=Arrays.asList(
          stop("大转弯（市场）",23.10,113.10),stop("大转弯（社区）",23.11,113.11));
  require(BusRouteMatch.station(describedTwice,"大转弯",0,0)<0,"ambiguous landmark aliases guessed");
  require(BusRouteMatch.station(describedTwice,"大转弯",23.11,113.11)==1,"location-backed alias could not choose station");
  for(String[] qualifiers:new String[][]{{"1","2"},{"A","B"},{"a","b"},{"北门","南门"},{"上行","下行"},{"内环","外环"},{"内","外"},{"一号站台","二号站台"},{"一","二"}}){
   require(BusRouteMatch.station(Arrays.asList(stop("公交站("+qualifiers[1]+")",23,113)),
           "公交站("+qualifiers[0]+")",0,0)<0,"different platform qualifier erased");
   require(!BusRouteMatch.sameTerminal("总站("+qualifiers[0]+")","总站("+qualifiers[1]+")"),
           "different terminal qualifier erased");
  }
  System.out.println("BusRouteMatchSelfTest passed (city, numeric identity, direction, canonical boarding station)");
 }
 static ChelaileDataSource.LineStation stop(String name,double lat,double lon){return new ChelaileDataSource.LineStation(1,name,"id",lat,lon);}
 static ChelaileDataSource.LineDetail line(List<ChelaileDataSource.LineStation> stops,String end){return new ChelaileDataSource.LineDetail("line","桂18",0,"西约",end,"06:00","23:00","2",new ArrayList<>(),stops,"reverse");}
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
