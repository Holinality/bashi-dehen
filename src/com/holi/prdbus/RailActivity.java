package com.holi.prdbus;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RailActivity extends ThemedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String region, from = "", to = "", located = "", selected = "";
    private long plannedReady;
    private LinearLayout stationCard, results;
    private ScrollView scroll;
    private TextView trains, position;
    private Button originChoice, destinationChoice;
    private Boolean pickingStart;
    private RailMapView map;
    private android.widget.ProgressBar loading;
    private LocationManager locationManager;
    private LocationListener listener;
    private volatile int lookup;
    private List<MetroDataSource.MetroLine> metroLines = java.util.Collections.emptyList();
    private List<RailTimetable.Train> available = java.util.Collections.emptyList();
    private final Map<String,String> services = new java.util.HashMap<>();
    private final Map<String,Button> serviceButtons = new java.util.LinkedHashMap<>();
    private String serviceFilter = "全部";
    private boolean classifying;
    private int visibleTrains = 6;
    private static String trainKey(RailTimetable.Train train) {return train.number+"/"+train.departure;}

    static String title(String region) { return RailCatalog.SH.equals(region) ? "上海市域铁路" : "广东城际"; }

    static void open(Activity activity, String region, String query, String from, String to) {
        open(activity, region, query, from, to, 0);
    }

    static void open(Activity activity, String region, String query, String from, String to, long ready) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.startActivity(new Intent(activity, RailActivity.class)
                .putExtra("region", region).putExtra("query", query)
                .putExtra("from", from).putExtra("to", to).putExtra("ready", ready));
        Ui.transitionForward(activity);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.edgeToEdge(this);
        region = RailCatalog.SH.equals(getIntent().getStringExtra("region")) ? RailCatalog.SH : RailCatalog.GD;
        from = RailCatalog.station(region, getIntent().getStringExtra("from"));
        to = RailCatalog.station(region, getIntent().getStringExtra("to"));
        plannedReady = getIntent().getLongExtra("ready", 0);
        if (state != null) {
            from = RailCatalog.station(region, state.getString("from"));
            to = RailCatalog.station(region, state.getString("to"));
            plannedReady = state.getLong("ready", 0);
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        LinearLayout page = column();
        page.setBackgroundColor(Ui.SURFACE);
        LinearLayout header = column();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.mapIcon(this,"back","返回",view -> finish()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title = label(title(region), 20, Ui.TEXT, true);
        title.setPadding(dp(12), 0, 0, 0);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(Ui.mapIcon(this,"search","查找车站",view -> findStation()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout.LayoutParams locationSize = new LinearLayout.LayoutParams(dp(48),dp(48));
        locationSize.leftMargin=dp(8);
        heading.addView(Ui.mapIcon(this,"locate","定位到附近车站",view -> locate()),locationSize);
        header.addView(heading, Ui.matchWrap());
        page.addView(header, Ui.matchWrap());
        Ui.insets(this, header, 12, 12, 6, 8);

        scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(12), dp(4), dp(12), dp(20));
        scroll.addView(body, Ui.matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);

        LinearLayout mapCard = card();
        position = label("拓扑示意 · 点站名选择起终点", 13, Ui.MUTED, false);
        position.setPadding(0, 0, 0, dp(6));
        boolean shanghai = RailCatalog.SH.equals(region);
        try {
            Map<String, PointF> points = RailMapStations.forRegion(region);
            map = new RailMapView(this, shanghai ? "rail-map-sh.jpg" : "rail-map-gd.jpg", points, this::select);
            map.setBackground(Ui.rounded(this,Ui.RAISED,14));
            map.setClipToOutline(true);
            mapCard.addView(map, new LinearLayout.LayoutParams(-1, dp(320)));
            LinearLayout tools = new LinearLayout(this);
            tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            position.setMaxLines(2);
            position.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tools.addView(position,new LinearLayout.LayoutParams(0,-2,1));
            tools.addView(Ui.mapIcon(this,"fit","显示全图",view -> map.reset()),iconParams());
            tools.addView(Ui.mapIcon(this,"minus","缩小",view -> map.zoom(1/1.6f)),iconParams());
            tools.addView(Ui.mapIcon(this,"plus","放大",view -> map.zoom(1.6f)),iconParams());
            mapCard.addView(tools, Ui.matchWrap());
        } catch (java.io.IOException unavailable) {
            position.setText("线网图暂不可用，可通过查站选择车站");
            mapCard.addView(position,Ui.matchWrap());
        }
        body.addView(mapCard, Ui.cardParams(this));

        stationCard = card();
        body.addView(stationCard, Ui.cardParams(this));
        LinearLayout timetable = card();
        TextView section=label(shanghai ? "市域铁路行程" : "城际班次",18,Ui.TEXT,true);
        section.setPadding(0,0,0,dp(12));
        timetable.addView(section,Ui.matchWrap());
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        originChoice=action("选择起点",() -> { pickingStart=true; findStation(); });
        destinationChoice=action("选择终点",() -> { pickingStart=false; findStation(); });
        originChoice.setMaxLines(2);
        destinationChoice.setMaxLines(2);
        actions.addView(originChoice,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(Ui.mapIcon(this,"swap","交换起终点",view -> {
            if (from.isEmpty() || to.isEmpty()) return;
            String previous = from;
            from = to;
            to = previous;
            plannedReady = 0;
            showPair();
            queryTrains();
        }),iconParams());
        actions.addView(destinationChoice,new LinearLayout.LayoutParams(0,-2,1));
        timetable.addView(actions, Ui.matchWrap());
        Button query=action(shanghai ? "查看行程" : "查询班次",this::queryTrains);
        query.setBackground(Ui.rounded(this,Ui.PRIMARY,14));
        query.setTextColor(Ui.ON_PRIMARY);
        LinearLayout.LayoutParams querySize=Ui.matchWrap();
        querySize.topMargin=dp(10);
        timetable.addView(query,querySize);
        if (!shanghai) {
            TagFlowLayout filters = new TagFlowLayout(this);
            filters.setPadding(0,dp(10),0,dp(4));
            for (String name : new String[]{"全部","站站停","快车","特快"}) {
                Button option=action(name,() -> {
                    serviceFilter=name;
                    visibleTrains=6;
                    renderTrains();
                    if (!"全部".equals(name)) classifyBatch(lookup);
                });
                option.setPadding(dp(12),dp(8),dp(12),dp(8));
                serviceButtons.put(name,option);
                filters.addView(option);
            }
            timetable.addView(filters,Ui.matchWrap());
        }
        loading = new android.widget.ProgressBar(this);
        loading.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerSize = new LinearLayout.LayoutParams(dp(26),dp(26));
        spinnerSize.gravity=Gravity.CENTER_HORIZONTAL;
        spinnerSize.topMargin=dp(8);
        timetable.addView(loading, spinnerSize);
        trains = label("从线网图选取起终点，也可以直接查站", 14, Ui.MUTED, false);
        trains.setPadding(0, dp(6), 0, dp(4));
        timetable.addView(trains, Ui.matchWrap());
        results = column();
        timetable.addView(results, Ui.matchWrap());
        body.addView(timetable, Ui.cardParams(this));
        if (plannedReady != 0) {
            body.removeView(timetable);
            body.addView(timetable, 0, Ui.cardParams(this));
        }
        String initial = state == null ? getIntent().getStringExtra("query") : state.getString("selected");
        selected = RailCatalog.station(region, initial);
        if (selected.isEmpty()) selected = from;
        if (!selected.isEmpty() && map != null) map.post(() -> map.focus(selected));
        renderStation();
        showPair();
        worker.execute(() -> {
            try {
                List<MetroDataSource.MetroLine> loaded=new MetroDataSource(this).queryLines(region);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    metroLines=loaded;
                    renderStation();
                });
            } catch (Exception unavailable) { }
        });
        if (!from.isEmpty() && !to.isEmpty()) queryTrains();
    }

    private void select(String station) {
        selected = station;
        if (pickingStart!=null) {
            boolean start=pickingStart;
            pickingStart=null;
            assign(start);
            selected="";renderStation();showPair();return;
        }
        renderStation();
        showPair();
        stationCard.announceForAccessibility(station + "，可设为起点或终点");
        stationCard.post(() -> {
            int target = Math.max(0, stationCard.getBottom()-scroll.getHeight()+dp(16));
            if (target > scroll.getScrollY()) scroll.smoothScrollTo(0,target);
        });
    }

    private void renderStation() {
        stationCard.removeAllViews();
        stationCard.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        if (selected.isEmpty()) return;
        stationCard.addView(label(selected, 19, Ui.TEXT, true), Ui.matchWrap());
        if (!RailCatalog.boardingAllowed(region,selected)) {
            TextView closed=label("暂停服务 · 列车不停站通过",14,Ui.ARRIVAL,true);
            closed.setPadding(0,dp(10),0,dp(4));
            stationCard.addView(closed,Ui.matchWrap());
            return;
        }
        List<String> lines = new ArrayList<>();
        for (RailCatalog.Line line : RailCatalog.search(region, "")) {
            if (java.util.Arrays.asList(line.stops).contains(selected)) lines.add(line.name);
        }
        stationCard.addView(label(String.join(" · ", lines), 13, Ui.MUTED, false), Ui.matchWrap());
        LinearLayout endpoints = new LinearLayout(this);
        LinearLayout.LayoutParams firstEndpoint=new LinearLayout.LayoutParams(0,-2,1);
        firstEndpoint.rightMargin=dp(8);
        firstEndpoint.topMargin=dp(12);
        endpoints.addView(action("设为起点", () -> assign(true)), firstEndpoint);
        LinearLayout.LayoutParams secondEndpoint=new LinearLayout.LayoutParams(0,-2,1);
        secondEndpoint.topMargin=dp(12);
        endpoints.addView(action("设为终点", () -> assign(false)), secondEndpoint);
        stationCard.addView(endpoints, Ui.matchWrap());
        for (RailCatalog.Transfer transfer : RailCatalog.fromRail(region, selected)) {
            TextView transferTitle=label(transfer.city+" · "+transfer.metro,14,Ui.TEXT,true);
            transferTitle.setPadding(0,dp(16),0,dp(8));
            stationCard.addView(transferTitle,Ui.matchWrap());
            TagFlowLayout options = new TagFlowLayout(this);
            for (String metro : transfer.lines.split("、")) {
                String identity=MetroCatalog.identity(transfer.city,metro);
                MetroDataSource.MetroLine matched=null;
                for (MetroDataSource.MetroLine candidate:metroLines)
                    if (identity.equals(candidate.networkId)) { matched=candidate; break; }
                int color=matched==null ? Ui.PRIMARY : MetroNetworkView.lineColour(matched);
                TextView link = label(metro+"  ›",14,ReadableColors.foreground(color),true);
                link.setGravity(Gravity.CENTER);
                link.setMinHeight(dp(48));
                link.setPadding(dp(12),dp(10),dp(12),dp(10));
                link.setBackground(Ui.rounded(this,color,8));
                link.setOnClickListener(view -> openMetro(transfer.city, metro, transfer.metro));
                options.addView(link);
            }
            stationCard.addView(options, Ui.matchWrap());
        }
    }

    private void assign(boolean start) {
        if (selected.isEmpty() || !RailCatalog.boardingAllowed(region,selected)) return;
        if (start) {
            from = selected;
            if (from.equals(to)) to = "";
        } else {
            to = selected;
            if (to.equals(from)) from = "";
        }
        plannedReady = 0;
        showPair();
        queryTrains();
    }

    private void findStation() {
        android.app.Dialog dialog=new android.app.Dialog(this);
        LinearLayout sheet=column();sheet.setPadding(dp(16),dp(16),dp(16),dp(16));
        sheet.setBackground(Ui.rounded(this,Ui.SURFACE,16));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(label(pickingStart==null?"选择车站":pickingStart?"选择起点":"选择终点",19,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        titleRow.addView(Ui.mapIcon(this,"close","关闭",v -> dialog.dismiss()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        sheet.addView(titleRow,Ui.matchWrap());
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("输入站名");Ui.type(input,15,false);
        input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.MUTED);input.setPadding(dp(12),dp(12),dp(12),dp(12));
        input.setBackground(Ui.rounded(this,Ui.RAISED,12));
        LinearLayout.LayoutParams inputSpace=Ui.matchWrap();inputSpace.setMargins(0,dp(8),0,dp(12));sheet.addView(input,inputSpace);
        LinearLayout columns=new LinearLayout(this);
        ScrollView cityScroll=new ScrollView(this),stationScroll=new ScrollView(this);
        LinearLayout cities=column(),matches=column();cityScroll.addView(cities,Ui.matchWrap());stationScroll.addView(matches,Ui.matchWrap());
        columns.addView(cityScroll,new LinearLayout.LayoutParams(dp(84),-1));
        LinearLayout.LayoutParams stationSize=new LinearLayout.LayoutParams(0,-1,1);stationSize.leftMargin=dp(12);columns.addView(stationScroll,stationSize);
        sheet.addView(columns,new LinearLayout.LayoutParams(-1,0,1));
        LinkedHashSet<String> stations=new LinkedHashSet<>(),cityNames=new LinkedHashSet<>();
        for(RailCatalog.Line line:RailCatalog.search(region,""))for(String stop:line.stops) {
            stations.add(stop);cityNames.add(RailCatalog.cityOfStation(region,stop));
        }
        final String[] city={""};
        if(RailCatalog.SH.equals(region))city[0]="上海";
        Map<String,Button> choices=new java.util.LinkedHashMap<>();
        Runnable filter=() -> {
            for(Map.Entry<String,Button> entry:choices.entrySet()) {
                boolean active=entry.getKey().equals(city[0]);
                entry.getValue().setBackground(Ui.rounded(this,active?Ui.LIGHT_BLUE:Ui.SURFACE,10));
                entry.getValue().setTextColor(active?Ui.PRIMARY:Ui.MUTED);
            }
            matches.removeAllViews();String key=RailCatalog.normalize(input.getText().toString());int count=0;
            if(city[0].isEmpty()&&key.isEmpty()){matches.addView(label("先选择城市",14,Ui.MUTED,false),Ui.matchWrap());return;}
            for(String station:stations) {
                String local=RailCatalog.cityOfStation(region,station);
                if(!city[0].isEmpty()&&!city[0].equals(local)||!station.contains(key))continue;
                LinearLayout item=column();item.setPadding(dp(12),dp(12),dp(12),dp(12));item.setMinimumHeight(dp(54));
                item.setBackground(Ui.control(this,Ui.SURFACE));item.setClickable(true);item.setFocusable(true);
                item.addView(label(station,16,Ui.TEXT,true),Ui.matchWrap());
                if(city[0].isEmpty()){TextView sub=label(local,12,Ui.MUTED,false);sub.setPadding(0,dp(5),0,0);item.addView(sub,Ui.matchWrap());}
                item.setOnClickListener(v -> {select(station);dialog.dismiss();if(map!=null)map.focus(station);});
                matches.addView(item,Ui.matchWrap());count++;
            }
            if(count==0)matches.addView(label("没有匹配的车站",14,Ui.MUTED,false),Ui.matchWrap());
        };
        for(String name:cityNames) {
            Button option=action(name,() -> {city[0]=name;filter.run();stationScroll.scrollTo(0,0);});
            choices.put(name,option);LinearLayout.LayoutParams gap=Ui.matchWrap();gap.bottomMargin=dp(8);cities.addView(option,gap);
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}
        });filter.run();
        dialog.setOnDismissListener(v -> pickingStart=null);dialog.setContentView(sheet);dialog.show();
        if(dialog.getWindow()!=null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels-dp(20),Math.round(getResources().getDisplayMetrics().heightPixels*.7f));
        }
    }

    private void showJinshan(String direction) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.jinshan.gov.cn/"))); }
        catch (android.content.ActivityNotFoundException unavailable) {
            android.widget.Toast.makeText(this,"没有可打开官方页面的浏览器",android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showPair() {
        originChoice.setText(from.isEmpty() ? "选择起点" : from);
        destinationChoice.setText(to.isEmpty() ? "选择终点" : to);
        if (map != null) map.mark(from, to, selected, located);
    }

    private void queryTrains() {
        final int id = ++lookup;
        available=java.util.Collections.emptyList();
        services.clear();
        classifying=false;
        visibleTrains=6;
        results.removeAllViews();
        loading.setVisibility(View.GONE);
        if (from.isEmpty() || to.isEmpty()) {
            trains.setText(from.isEmpty() ? "请选起点站" : "请选终点站");
            return;
        }
        if (from.equals(to)) {
            trains.setText("请选择不同的上、下车站");
            return;
        }
        if (!RailCatalog.boardingAllowed(region,from) || !RailCatalog.boardingAllowed(region,to)) {
            trains.setText("所选车站暂停服务，请重新选择");return;
        }
        if (RailCatalog.SH.equals(region)) {
            showShanghaiTrip();
            return;
        }
        final String start = from, end = to;
        final long ready = plannedReady;
        trains.setText("正在查询班次");
        loading.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            long now = System.currentTimeMillis();
            RailTimetable.Result result = RailTimetable.upcoming(this, start, end,
                    ready < 0 ? ready : Math.max(now, ready), Integer.MAX_VALUE);
            runOnUiThread(() -> {
                if (id != lookup || isDestroyed() || isFinishing()) return;
                loading.setVisibility(View.GONE);
                available=result.trains;
                if (available.isEmpty()) trains.setText(result.message.replace("12306","班次服务"));
                else {
                    renderTrains();
                    classifyBatch(id);
                }
            });
        });
    }

    private void renderTrains() {
        final int retainedScroll=scroll.getScrollY();
        for (Map.Entry<String,Button> entry:serviceButtons.entrySet()) {
            boolean active=entry.getKey().equals(serviceFilter);
            entry.getValue().setBackground(Ui.rounded(this,active?Ui.PRIMARY:Ui.RAISED,10));
            entry.getValue().setTextColor(active?Ui.ON_PRIMARY:Ui.PRIMARY);
        }
        results.removeAllViews();
        if (available.isEmpty()) return;
        List<RailTimetable.Train> matched=new ArrayList<>();
        int pending=0;
        for (RailTimetable.Train train:available) {
            String service=services.containsKey(trainKey(train))?services.get(trainKey(train)):train.service;
            if (!services.containsKey(trainKey(train))) pending++;
            if (RailCatalog.matchesService(service,serviceFilter)) matched.add(train);
        }
        trains.setText(matched.isEmpty() ? (classifying ? "正在核对"+serviceFilter+"班次" : "当前已核对班次中暂无"+serviceFilter)
                : matched.size()+" 趟"+("全部".equals(serviceFilter)?"":" · "+serviceFilter)+" · 按到达时间排序");
        long now=System.currentTimeMillis();
        for (RailTimetable.Train train:matched.subList(0,Math.min(visibleTrains,matched.size()))) {
            String service=services.containsKey(trainKey(train))?services.get(trainKey(train)):train.service;
            LinearLayout trainCard=column();
            trainCard.setPadding(dp(14),dp(14),dp(14),dp(14));
            trainCard.setBackground(Ui.control(this,Ui.RAISED));
            LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView title=label(train.number+" · "+service,15,Ui.PRIMARY,true);
            title.setPadding(0,0,dp(8),0);
            heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            TextView departure=label(train.departureLabel(now)+" 发",18,Ui.PRIMARY,true);
            heading.addView(departure,new LinearLayout.LayoutParams(-2,-2));
            CenteredIconView arrow=new CenteredIconView(this,"chevron",Ui.MUTED);
            LinearLayout.LayoutParams arrowSize=new LinearLayout.LayoutParams(dp(18),dp(24));arrowSize.leftMargin=dp(8);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);heading.addView(arrow,arrowSize);
            trainCard.addView(heading,Ui.matchWrap());
            TextView route=label(train.from+" → "+train.to+"  ·  "+train.arrivalLabel(now)+" 到",14,Ui.TEXT,false);
            route.setPadding(0,dp(9),0,0);trainCard.addView(route,Ui.matchWrap());
            trainCard.setClickable(true);trainCard.setFocusable(true);
            trainCard.setContentDescription(train.number+"，"+service+"，"+train.times(now)+"，查看全程停站时刻");
            trainCard.setOnClickListener(v -> RailTrainActivity.open(this,train,services.containsKey(trainKey(train))?services.get(trainKey(train)):train.service));
            LinearLayout.LayoutParams space=Ui.matchWrap();space.topMargin=dp(8);results.addView(trainCard,space);
        }
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        boolean moreTrains=matched.size()>visibleTrains;
        if(moreTrains) {
            Button more=action("查看更多班次",() -> {visibleTrains+=6;renderTrains();classifyBatch(lookup);});
            more.setBackground(Ui.control(this,Ui.SURFACE));
            footer.addView(more,new LinearLayout.LayoutParams(0,-2,1));
        }
        if(pending>0) {
            Button verify=action(classifying?"正在核对车型…":"核对更多车型",() -> classifyBatch(lookup));
            verify.setEnabled(!classifying);verify.setBackground(Ui.control(this,Ui.SURFACE));
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);if(moreTrains)size.leftMargin=dp(8);
            footer.addView(verify,size);
        }
        if(footer.getChildCount()>0) {
            LinearLayout.LayoutParams gap=Ui.matchWrap();gap.topMargin=dp(10);results.addView(footer,gap);
        }
        // Replacing classification labels must not jump the user's scroll position.
        scroll.post(() -> {if(!isDestroyed())scroll.scrollTo(0,retainedScroll);});
    }

    private void classifyBatch(int id) {
        if (classifying || available.isEmpty()) return;
        List<RailTimetable.Train> batch=new ArrayList<>();
        for (RailTimetable.Train train:available) {
            if (!services.containsKey(trainKey(train))) batch.add(train);
            if (batch.size()==6) break;
        }
        if (batch.isEmpty()) return;
        classifying=true;
        renderTrains();
        worker.execute(() -> {
            Map<String,String> checked=new java.util.HashMap<>();
            for (RailTimetable.Train train:batch) {
                if (id!=lookup || isDestroyed()) return;
                checked.put(trainKey(train),RailTimetable.service(train));
            }
            runOnUiThread(() -> {
                if (id!=lookup || isDestroyed() || isFinishing()) return;
                classifying=false;
                services.putAll(checked);
                renderTrains();
            });
        });
    }

    private void showShanghaiTrip() {
        for (RailCatalog.Line line : RailCatalog.search(region, "")) {
            List<String> stops = java.util.Arrays.asList(line.stops);
            int start = stops.indexOf(from), end = stops.indexOf(to);
            if (start < 0 || end < 0) continue;
            trains.setText(line.name + " · " + Math.abs(start - end) + " 个站间区间");
            if (line.name.equals("金山铁路")) {
                results.addView(action("前往官网查看时刻表", () -> showJinshan(start < end ? "a" : "b")), Ui.matchWrap());
            } else {
                results.addView(action("查看上车站与时刻", () -> openMetro("上海", line.name, from)), Ui.matchWrap());
            }
            return;
        }
        trains.setText("两站分属不同线路，需换乘。点站名可查看接驳地铁");
        if (AppSettings.hasMapKey())
            results.addView(action("规划换乘行程", this::planShanghaiTransfer), Ui.matchWrap());
    }

    private void planShanghaiTransfer() {
        final String start = from, end = to;
        final int id = ++lookup;
        loading.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            double[] origin = stationLocation(start), destination = stationLocation(end);
            List<ChelaileDataSource.TransitPlan> plans = java.util.Collections.emptyList();
            if (origin != null && destination != null) {
                double[] a = Coord.wgsToGcj(origin[0], origin[1]), b = Coord.wgsToGcj(destination[0], destination[1]);
                try { plans = ChelaileDataSource.transitQuery("上海", start, a[0], a[1],
                        end, b[0], b[1], "上海", "上海"); }
                catch (Exception unavailable) { }
            }
            List<ChelaileDataSource.TransitPlan> found = plans;
            runOnUiThread(() -> {
                if (id != lookup || isDestroyed() || isFinishing()) return;
                loading.setVisibility(View.GONE);
                if (found.isEmpty()) { trains.setText("暂未取得这两站的换乘方案"); return; }
                CityInfo city = CityInfo.find("上海");
                startActivity(new Intent(this, TransitDetailActivity.class).putExtra("plan", found.get(0))
                        .putExtra("origin", start).putExtra("destination", end).putExtra("cityName", "上海")
                        .putExtra("cityId", city == null ? "" : city.chelaileId));
            });
        });
    }

    private double[] stationLocation(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("regional-rail-locations.tsv"), "UTF-8"))) {
            for (String row; (row = reader.readLine()) != null;) {
                String[] fields = row.split("\t");
                if (fields.length == 4 && region.equals(fields[0]) && name.equals(fields[1]))
                    return new double[]{Double.parseDouble(fields[2]), Double.parseDouble(fields[3])};
            }
        } catch (Exception unavailable) { }
        return null;
    }

    private void openMetro(String city, String name, String station) {
        worker.execute(() -> {
            try {
                List<MetroDataSource.MetroLine> lines = new MetroDataSource(this).queryLines(region);
                List<MetroDataSource.MetroLine> candidates = new ArrayList<>();
                for (MetroDataSource.MetroLine candidate : lines) {
                    if (!region.equals(Regions.province(MetroDataSource.cityOfLine(candidate)))) continue;
                    if (!station.isEmpty() && MetroPatterns.index(candidate.stations, station) < 0) continue;
                    if (!MetroCatalog.identity(city, name).equals(candidate.networkId)) continue;
                    candidates.add(candidate);
                }
                MetroDataSource.MetroLine line = MetroLineResolver.best(candidates, name, station, "", city);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (line == null) {
                        android.widget.Toast.makeText(this, "暂未找到对应的地铁线路", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        startActivity(new Intent(this, MetroLineActivity.class)
                                .putExtra("lineName", line.name).putExtra("colour", line.colour)
                                .putExtra("highlightStation", station).putExtra("openStationSheet", !station.isEmpty()));
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void locate() {
        if (!PrivacyNotice.locationAllowed()) return;
        if (locationManager == null) return;
        if (checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (android.os.Build.VERSION.SDK_INT >= 23)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 4);
            return;
        }
        stopLocation();
        position.setText("正在定位…");
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { place(location); }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        Location best = null;
        boolean requested = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null && (best == null || last.getTime() > best.getTime())) best = last;
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000, 0, listener);
                    requested = true;
                }
            } catch (RuntimeException ignored) { }
        }
        if (best != null) place(best);
        if (!requested && located.isEmpty()) position.setText("请打开系统定位");
        final LocationListener pending = listener;
        position.postDelayed(() -> {
            if (listener != pending) return;
            stopLocation();
            if (position.getText().toString().equals("正在定位…")) position.setText("暂未取得准确位置");
        }, 15000);
    }

    private void place(Location location) {
        long age = System.currentTimeMillis() - location.getTime();
        if (age < 0 || age > 60_000 || !location.hasAccuracy() || location.getAccuracy() > 350) return;
        double best = 3;
        String station = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("regional-rail-locations.tsv"), "UTF-8"))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\t");
                if (fields.length != 4 || !region.equals(fields[0])) continue;
                double distance = GeoUtils.distanceKm(location.getLatitude(), location.getLongitude(),
                        Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
                if (distance < best) {
                    best = distance;
                    station = fields[1];
                }
            }
        } catch (Exception ignored) { }
        if (station.equals(located) && !station.isEmpty()) return;
        located = station;
        position.setText(station.isEmpty() ? "附近 3 公里内未匹配到已校核的车站"
                : "● 你在 " + station + " 附近 · " + Math.round(best * 1000) + " 米");
        showPair();
        if (!station.isEmpty() && map != null) map.focus(station);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 4) for (int grant : grants) if (grant == PackageManager.PERMISSION_GRANTED) { locate(); break; }
    }

    private void stopLocation() {
        if (locationManager != null && listener != null) locationManager.removeUpdates(listener);
        listener = null;
    }

    @Override protected void onStop() { stopLocation(); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        boolean changed=false;
        for(RailTimetable.Train train:available) {
            String cached=RailTimetable.cachedService(train);
            if(!cached.equals(train.service)&&!cached.equals(services.get(trainKey(train)))) {
                services.put(trainKey(train),cached);changed=true;
            }
        }
        if(changed&&results!=null)renderTrains();
    }
    @Override protected void onDestroy() { lookup++; worker.shutdownNow(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("from", from);
        state.putString("to", to);
        state.putString("selected", selected);
        state.putLong("ready", plannedReady);
    }

    private int dp(int value) { return Ui.dp(this, value); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(Ui.edgeCard(this,Ui.RAISED,16));
        return view;
    }
    private TextView label(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        Ui.type(text, size, strong);
        return text;
    }
    private Button action(String title, Runnable run) {
        Button button = Ui.button(this, title, Ui.RAISED, Ui.PRIMARY);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> run.run());
        return button;
    }
    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(48),dp(48));
        params.setMargins(dp(4),dp(4),dp(4),dp(4));
        return params;
    }

}
