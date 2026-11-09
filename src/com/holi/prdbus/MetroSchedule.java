package com.holi.prdbus;
import java.util.*;
/** One offline service model for both arrival labels and marker interpolation. Estimates, not live trains. */
final class MetroSchedule {
    final double[] offsets;
    final List<Integer> departures = new ArrayList<>();
    final boolean forward;
    MetroSchedule(MetroDataSource.MetroLine line, List<MetroDataSource.Station> raw, boolean forward, double speed) {
        this.forward=forward; offsets=new double[raw.size()];
        if(raw.isEmpty())return;
        MetroDataSource.Station origin=raw.get(forward?0:raw.size()-1);
        String first=forward?origin.first:origin.firstR, last=forward?origin.last:origin.lastR;
        if(!MetroTimes.validTime(first))first=forward?line.firstTime:line.firstTimeR;
        if(!MetroTimes.validTime(last))last=forward?line.lastTime:line.lastTimeR;
        if(!MetroTimes.validTime(first)||!MetroTimes.validTime(last))return;
        int start=MetroTimes.toMinutes(first), end=MetroTimes.toMinutes(last);
        if(end<start)end+=1440;
        for(int i=1;i<raw.size();i++){
            MetroDataSource.Station a=raw.get(forward?i-1:raw.size()-i), b=raw.get(forward?i:raw.size()-i-1);
            double next=offsets[i-1]+MetroTimes.travelMinutes(a,b,forward,speed);
            String hint=forward?b.first:b.firstR;
            if(MetroTimes.validTime(hint)){
                int delta=MetroTimes.toMinutes(hint)-start; if(delta<0)delta+=1440;
                // A station's first train may start from a different terminal.
                double tolerance = Math.max(1, (next - offsets[i-1]) * .35);
                if(delta>offsets[i-1] && Math.abs(delta-next)<=tolerance)next=delta;
            }
            offsets[i]=next;
        }
        for(int time=start;time<=end && departures.size()<1500;){
            departures.add(time);int step=MetroTimes.intervalAt(time,line.name);if(step<=0)break;time+=step;
        }
        if (!departures.isEmpty() && departures.get(departures.size()-1) < end) {
            int lastIndex = departures.size() - 1;
            int previous = departures.get(lastIndex);
            int minimumGap = Math.max(2, MetroTimes.intervalAt(previous, line.name) / 2);
            if (lastIndex > 0 && end - previous < minimumGap) departures.set(lastIndex, end);
            else departures.add(end);
        }
    }
    double serviceNow(double now) {
        if(departures.isEmpty())return now;
        if(now<departures.get(0) && now+1440<=departures.get(departures.size()-1)+offsets[offsets.length-1])return now+1440;
        return now;
    }
    int waitingMinutes(int rawIndex,double ready,String lineName) {
        if(rawIndex<0||rawIndex>=offsets.length)return 0;
        if(departures.isEmpty())return Math.max(1,(MetroTimes.intervalAt((int)(ready%1440),lineName)+1)/2);
        int index=forward?rawIndex:offsets.length-rawIndex-1;
        double nearest=Double.POSITIVE_INFINITY;
        int base=(int)Math.floor(ready/1440);
        for(int day=base-1;day<=base+1;day++)for(int departure:departures) {
            double arrival=day*1440+departure+offsets[index];
            if(arrival>=ready&&arrival<nearest)nearest=arrival;
        }
        return Double.isInfinite(nearest)?0:Math.max(0,(int)Math.ceil(nearest-ready));
    }
    List<String> arrivals(int rawIndex,double now,int count){
        List<String> result=new ArrayList<>();
        if(rawIndex<0 || rawIndex>=offsets.length || count<=0)return result;
        int index=forward?rawIndex:offsets.length-rawIndex-1; now=serviceNow(now);
        for(int departure:departures){
            int time=(int)Math.round(departure+offsets[index]);
            if(time>=now){result.add(MetroTimes.formatTime(time));if(result.size()>=count)break;}
        }
        return result;
    }
    List<Double> positions(double now){
        List<Double> result=new ArrayList<>(); if(offsets.length<2)return result;
        double[] lead = new double[offsets.length - 1];
        Arrays.fill(lead, -1);
        now=serviceNow(now);
        for(int departure:departures){
            double elapsed=now-departure;
            if(elapsed<0 || elapsed>offsets[offsets.length-1])continue;
            int i=0;while(i<offsets.length-2 && offsets[i+1]<=elapsed)i++;
            double span=offsets[i+1]-offsets[i];
            double pos=i+(span>0?(elapsed-offsets[i])/span:0);
            lead[i] = Math.max(lead[i], pos);
        }
        for (double position : lead) if (position >= 0) result.add(position);
        return result;
    }
}
