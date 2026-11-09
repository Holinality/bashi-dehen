package com.holi.prdbus;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full stopping table lives on its own page, leaving the train list stable. */
public final class RailTrainActivity extends ThemedActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private RailTimetable.Train train;
    private LinearLayout content;
    private int request;

    static void open(android.app.Activity activity,RailTimetable.Train train,String service) {
        activity.startActivity(new Intent(activity,RailTrainActivity.class)
                .putExtra("train",train).putExtra("service",service));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);Ui.edgeToEdge(this);
        Object value=getIntent().getSerializableExtra("train");
        if(!(value instanceof RailTimetable.Train)){finish();return;}
        train=(RailTimetable.Train)value;
        LinearLayout page=column();page.setBackgroundColor(Ui.SURFACE);
        LinearLayout header=row();
        header.addView(Ui.mapIcon(this,"back","返回班次",v -> finish()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text(train.number+" · 停站时刻",20,Ui.TEXT,true);
        title.setPadding(dp(12),0,dp(8),0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(header,Ui.matchWrap());
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        content=column();content.setPadding(dp(16),dp(12),dp(16),dp(24));
        scroll.addView(content,Ui.matchWrap());page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page);Ui.insets(this,header,12,12,6,8);
        load(false);
    }

    private void load(boolean retry) {
        final int id=++request;
        content.removeAllViews();summary(getIntent().getStringExtra("service"));
        android.widget.FrameLayout box=new android.widget.FrameLayout(this);
        android.widget.ProgressBar spinner=new android.widget.ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        box.addView(spinner,new android.widget.FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER));
        box.setContentDescription("正在获取全程停站时刻");content.addView(box,new LinearLayout.LayoutParams(-1,dp(100)));
        worker.execute(() -> {
            RailTimetable.StopDetails result=RailTimetable.stopDetails(train,retry);
            runOnUiThread(() -> {if(id==request&&!isDestroyed()&&!isFinishing())render(result);});
        });
    }

    private void summary(String service) {
        long now=System.currentTimeMillis();
        LinearLayout times=row();
        times.addView(endpoint(train.from,train.departureLabel(now),"发车"),new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("→",20,Ui.MUTED,false);arrow.setGravity(Gravity.CENTER);
        times.addView(arrow,new LinearLayout.LayoutParams(dp(40),-2));
        times.addView(endpoint(train.to,train.arrivalLabel(now),"到达"),new LinearLayout.LayoutParams(0,-2,1));
        content.addView(times,Ui.matchWrap());
        long duration=Math.max(0,(train.arrival-train.departure)/60000);
        TextView meta=text((service==null?"":service+" · ")+duration+" 分钟",13,Ui.MUTED,false);
        meta.setPadding(0,dp(12),0,dp(20));content.addView(meta,Ui.matchWrap());
    }

    private View endpoint(String name,String time,String caption) {
        LinearLayout box=column();
        box.addView(text(time,26,Ui.PRIMARY,true),Ui.matchWrap());
        TextView station=text(name,17,Ui.TEXT,true);station.setPadding(0,dp(8),0,dp(5));
        box.addView(station,Ui.matchWrap());box.addView(text(caption,12,Ui.MUTED,false),Ui.matchWrap());return box;
    }

    private void render(RailTimetable.StopDetails result) {
        content.removeAllViews();summary(result.service);
        RailStopTimes.Leg leg=result.leg(train);
        if(result.table==null||leg==null) {
            TextView failure=text(result.message.isEmpty()?"停站资料与本次行程不一致，请重试":result.message,15,Ui.MUTED,false);
            failure.setPadding(0,dp(12),0,dp(12));content.addView(failure,Ui.matchWrap());
            Button retry=Ui.button(this,"重新获取",Ui.RAISED,Ui.PRIMARY);retry.setOnClickListener(v -> load(true));
            content.addView(retry,Ui.matchWrap());return;
        }
        TextView count=text("全程 "+result.table.calls.size()+" 站 · 本次乘坐 "+(leg.to-leg.from)+" 个区间",13,Ui.MUTED,false);
        count.setPadding(0,0,0,dp(12));content.addView(count,Ui.matchWrap());
        LinearLayout columns=row();columns.setPadding(dp(12),dp(10),dp(12),dp(10));
        columns.addView(text("站点",13,Ui.MUTED,false),new LinearLayout.LayoutParams(0,-2,1.35f));
        columns.addView(timeCell("到达",Ui.MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        columns.addView(timeCell("发车",Ui.MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        content.addView(columns,Ui.matchWrap());
        for(int i=0;i<result.table.calls.size();i++) {
            RailStopTimes.Call call=result.table.calls.get(i);
            boolean board=i==leg.from,alight=i==leg.to,emphasis=board||alight;
            int accent=alight?Ui.ACCENT:Ui.PRIMARY;
            LinearLayout row=row();row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setMinimumHeight(dp(58));
            if(emphasis)row.setBackground(Ui.rounded(this,alight?Ui.LIGHT_ORANGE:Ui.LIGHT_BLUE,12));
            LinearLayout name=column();name.setPadding(0,0,dp(8),0);
            name.addView(text(call.name,16,emphasis?accent:Ui.TEXT,emphasis),Ui.matchWrap());
            if(emphasis) {
                TextView badge=text(board?"在此上车":"在此下车",12,ReadableColors.foreground(accent),true);
                badge.setPadding(dp(7),dp(4),dp(7),dp(4));badge.setBackground(Ui.rounded(this,accent,6));
                LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-2,-2);space.topMargin=dp(7);name.addView(badge,space);
                row.setContentDescription(call.name+"，"+(board?"在此上车":"在此下车")+"，到达 "+call.arrivalLabel(leg.boardingDay)+"，发车 "+call.departureLabel(leg.boardingDay));
            }
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1.35f));
            row.addView(timeCell(call.arrivalLabel(leg.boardingDay),emphasis?accent:Ui.TEXT,emphasis),new LinearLayout.LayoutParams(0,-2,1));
            row.addView(timeCell(call.departureLabel(leg.boardingDay),emphasis?accent:Ui.TEXT,emphasis),new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams space=Ui.matchWrap();space.topMargin=dp(4);content.addView(row,space);
        }
    }

    private TextView timeCell(String value,int color,boolean strong) {
        TextView view=text(value,15,color,strong);view.setGravity(Gravity.CENTER);return view;
    }
    private TextView text(String value,int size,int color,boolean strong) {
        TextView view=new TextView(this);view.setText(value);view.setTextColor(color);Ui.type(view,size,strong);return view;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private int dp(int value){return Ui.dp(this,value);}
    @Override protected void onDestroy(){request++;worker.shutdownNow();super.onDestroy();}
}
