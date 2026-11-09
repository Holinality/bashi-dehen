package com.holi.prdbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Passenger calls use minutes from the train's origin day, including midnight rollovers. */
final class RailStopTimes {
    static final class Raw {
        final String name, arrival, departure;
        Raw(String name,String arrival,String departure) {
            this.name=name==null?"":name.replaceAll("\\s+","");
            this.arrival=arrival;this.departure=departure;
        }
    }
    static final class Call {
        final String name;
        final int arrival, departure;
        final boolean first, last;
        Call(String name,int arrival,int departure,boolean first,boolean last) {
            this.name=name;this.arrival=arrival;this.departure=departure;this.first=first;this.last=last;
        }
        String arrivalLabel(int boardingDay) {return arrival<0?(first?"始发":"—"):display(arrival,boardingDay);}
        String departureLabel(int boardingDay) {return departure<0?(last?"终到":"—"):display(departure,boardingDay);}
    }
    static final class Leg {
        final int from,to,boardingDay;
        Leg(int from,int to,int boardingDay) {this.from=from;this.to=to;this.boardingDay=boardingDay;}
    }
    final List<Call> calls;
    private RailStopTimes(List<Call> calls) {this.calls=Collections.unmodifiableList(calls);}

    static RailStopTimes parse(List<Raw> source) {
        if(source.size()>200)throw new IllegalArgumentException("Too many station calls");
        List<Raw> passenger=new ArrayList<>();
        for(Raw row:source) {
            if(row.name.isEmpty()||row.name.length()>64)throw new IllegalArgumentException("Invalid station name");
            if(clock(row.arrival)<0&&clock(row.departure)<0)continue;
            passenger.add(row);
        }
        if(passenger.size()<2)throw new IllegalArgumentException("Incomplete stopping table");
        List<Call> calls=new ArrayList<>();
        int previous=-1,first=-1;
        for(int i=0;i<passenger.size();i++) {
            Raw row=passenger.get(i);
            int arrival=roll(clock(row.arrival),previous);
            if(arrival>=0)previous=arrival;
            int departure=roll(clock(row.departure),previous);
            if(arrival>=0&&departure>=0&&departure-arrival>180)
                throw new IllegalArgumentException("Invalid dwell time");
            if(departure>=0)previous=departure;
            if(first<0)first=previous;
            if(previous-first>=24*60)throw new IllegalArgumentException("Invalid regional journey duration");
            calls.add(new Call(row.name,arrival,departure,i==0,i==passenger.size()-1));
        }
        return new RailStopTimes(calls);
    }
    Leg leg(String from,String to,int departureClock,int arrivalClock,int durationMinutes) {
        for(int i=0;i<calls.size();i++) {
            Call a=calls.get(i);
            if(!same(a.name,from)||a.departure<0||a.departure%1440!=departureClock)continue;
            for(int j=i+1;j<calls.size();j++) {
                Call b=calls.get(j);
                if(same(b.name,to)&&b.arrival>=0&&b.arrival%1440==arrivalClock
                        &&b.arrival-a.departure==durationMinutes)
                    return new Leg(i,j,a.departure/1440);
            }
        }
        return null;
    }
    private static boolean same(String a,String b) {
        return normalize(a).equals(normalize(b));
    }
    private static String normalize(String value) {
        return value==null?"":value.replaceAll("\\s+","").replaceFirst("(城际站|站)$","").replace("仲恺","陈江南");
    }
    private static int roll(int clock,int previous) {
        if(clock<0)return -1;
        if(previous<0)return clock;
        int value=(previous/1440)*1440+clock;
        if(value<previous)value+=1440;
        if(value-previous>6*60)throw new IllegalArgumentException("Inconsistent station chronology");
        return value;
    }
    static int clock(String value) {
        if(value==null||value.isEmpty()||"----".equals(value)||"--:--".equals(value))return -1;
        if(!value.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d"))
            throw new IllegalArgumentException("Invalid station time");
        String[] parts=value.split(":");return Integer.parseInt(parts[0])*60+Integer.parseInt(parts[1]);
    }
    static String display(int value,int boardingDay) {
        int difference=value/1440-boardingDay;
        String day=difference==0?"":difference==1?"次日\n":difference==-1?"前日\n":difference+"日后\n";
        return day+String.format(Locale.CHINA,"%02d:%02d",value%1440/60,value%60);
    }
}
