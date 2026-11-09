package com.holi.prdbus;

import java.util.Arrays;
import java.util.Collections;

public final class RailStopTimesSelfTest {
    public static void main(String[] args) {
        RailStopTimes table=RailStopTimes.parse(Arrays.asList(
                row("番 禺","----","23:52"),row("东莞西","23:59","00:01"),row("惠州 北","00:08","----")));
        require(table.calls.size()==3,"passenger calls");
        RailStopTimes.Leg all=table.leg("番禺","惠州北",23*60+52,8,16);
        require(all!=null&&all.from==0&&all.to==2&&all.boardingDay==0,"midnight journey");
        require("始发".equals(table.calls.get(0).arrivalLabel(0)),"origin arrival");
        require("终到".equals(table.calls.get(2).departureLabel(0)),"terminal departure");
        require("次日\n00:01".equals(table.calls.get(1).departureLabel(0)),"midnight dwell");
        RailStopTimes.Leg onward=table.leg("东莞西站","惠州北",1,8,7);
        require(onward!=null&&onward.boardingDay==1,"boarding after midnight");
        require("前日\n23:52".equals(table.calls.get(0).departureLabel(onward.boardingDay)),"previous origin day");
        require(table.leg("惠州北","番禺",8,23*60+52,16)==null,"reverse interval accepted");
        require(table.leg("番禺","惠州北",23*60+53,8,15)==null,"wrong departure accepted");
        require(table.leg("番禺","惠州北",23*60+52,8,17)==null,"wrong duration accepted");
        RailStopTimes duplicates=RailStopTimes.parse(Arrays.asList(
                row("甲","----","10:00"),row("同名","10:10","10:11"),row("中间","10:20","10:21"),
                row("同名","10:30","10:31"),row("乙","10:40","----")));
        require(duplicates.leg("同名","乙",10*60+31,10*60+40,9).from==3,"duplicate station binding");
        RailStopTimes through=RailStopTimes.parse(Arrays.asList(row("甲","----","10:00"),
                row("通过站","----","----"),row("乙","10:20","----")));
        require(through.calls.size()==2,"passing station became passenger stop");
        invalid(Arrays.asList(row("甲","----","24:00"),row("乙","01:00","----")));
        invalid(Arrays.asList(row("甲","----","12:00"),row("乙","12:15","12:12")));
        invalid(Arrays.asList(row("甲","----","01:00"),row("乙","22:00","----")));
        invalid(Collections.singletonList(row("甲","----","10:00")));
        require("2026-11-04".equals(RailTimetable.originDate("20261104",0)),"origin date for overnight query");
        require("1970-01-01".equals(RailTimetable.originDate("20261304",0)),"invalid service date accepted");
        System.out.println("RailStopTimesSelfTest passed (midnight, endpoint identity, repeated stops, chronology, service date)");
    }
    private static RailStopTimes.Raw row(String name,String a,String d){return new RailStopTimes.Raw(name,a,d);}
    private static void invalid(java.util.List<RailStopTimes.Raw> rows) {
        try{RailStopTimes.parse(rows);throw new AssertionError("Invalid timetable accepted");}
        catch(IllegalArgumentException expected){ }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
