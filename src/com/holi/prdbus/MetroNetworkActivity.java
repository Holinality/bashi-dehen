package com.holi.prdbus;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MetroNetworkActivity extends ThemedActivity {
    private static final int LOCATION_PERMISSION = 31;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MetroNetwork.Area area;
    private MetroNetwork network;
    private MetroNetworkView diagram;
    private MetroNetwork.Stop selected, start, destination;
    private Boolean pickingStart;
    private MetroNetwork.Preference routePreference=MetroNetwork.Preference.FASTEST;
    private MetroNetwork.Stop previewStart;
    private MetroNetwork.Journey preview;
    private boolean previewOnline;
    private boolean planningOnline;
    private volatile int routeRequest;
    private String savedSelection, savedStart, savedDestination;
    private TextView status;
    private TextView mapCredit;
    private android.widget.ProgressBar loading;
    private LinearLayout stationCard;
    private MetroDiagramLayout schematic;
    private MetroDistricts districts;
    private boolean geographic;
    private Button schematicButton, geographicButton;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location fix;
    private boolean centerOnFix, routing;
    private final Runnable hideNotice=() -> status.setVisibility(View.GONE);
    private final Runnable locationTimeout = () -> {
        if (validFix(fix)) showLocation(false);
        else notice("未取得当前位置 · 可继续查看线网");
    };
    private final Runnable expireFix = () -> {
        if (!validFix(fix)) {
            diagram.showLocation(0, 0, 0, false);
            if (locationListener == null) showLocation(false);
        }
    };

    static void open(Activity activity, MetroNetwork.Area area) {
        activity.startActivity(new Intent(activity, MetroNetworkActivity.class).putExtra("area", area.name()));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { area = MetroNetwork.Area.valueOf(getIntent().getStringExtra("area")); }
        catch (RuntimeException invalid) { finish(); return; }
        if (state != null) {
            savedSelection = state.getString("station");
            savedStart = state.getString("start");
            savedDestination=state.getString("destination");
            geographic = state.getBoolean("geographic", false);
            try{routePreference=MetroNetwork.Preference.valueOf(state.getString("preference","FASTEST"));}catch(RuntimeException ignored){}
        }
        Ui.edgeToEdge(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        LinearLayout page = column();
        page.setBackgroundColor(Ui.SURFACE);
        LinearLayout header = column();
        LinearLayout top = row();
        top.addView(Ui.mapIcon(this, "back", "返回", view -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label(area.label + "地铁线网", 20, Ui.TEXT, true);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(button("官方图", this::openOfficial));
        header.addView(top, Ui.matchWrap());
        status = label("正在加载线网…", 13, Ui.MUTED, false);
        status.setPadding(0, 0, dp(8), 0);
        status.setMaxLines(2);
        status.setVisibility(View.GONE);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        page.addView(header, Ui.matchWrap());
        LinearLayout modes = row();
        modes.setPadding(dp(12), dp(4), dp(12), dp(8));
        schematicButton = button("规整图", () -> changeMode(false));
        geographicButton = button("位置线网", () -> changeMode(true));
        modes.addView(schematicButton, weighted());
        modes.addView(geographicButton, weighted());
        modes.addView(button("查站", this::findStation), weighted());
        page.addView(modes, Ui.matchWrap());

        android.widget.FrameLayout map = new android.widget.FrameLayout(this);
        map.setBackground(Ui.rounded(this, Ui.RAISED, 17));
        map.setClipToOutline(true);
        diagram = new MetroNetworkView(this, this::select);
        map.addView(diagram, new android.widget.FrameLayout.LayoutParams(-1, -1));
        loading = new android.widget.ProgressBar(this);
        map.addView(loading, new android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-1, 0, 1f);
        mapSize.setMargins(dp(10), 0, dp(10), 0);
        page.addView(map, mapSize);
        LinearLayout tools = row();
        tools.setPadding(dp(4),dp(4),dp(4),dp(4));
        tools.setBackground(Ui.rounded(this,Ui.SURFACE,14));
        tools.addView(Ui.mapIcon(this, "fit", "显示全图", view -> diagram.reset()), iconParams());
        tools.addView(Ui.mapIcon(this, "minus", "缩小", view -> mapZoom(1 / 1.65f)), iconParams());
        tools.addView(Ui.mapIcon(this, "plus", "放大", view -> mapZoom(1.65f)), iconParams());
        tools.addView(Ui.mapIcon(this, "locate", "定位", view -> startLocation(true)), iconParams());
        android.widget.FrameLayout.LayoutParams toolPosition=new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
        toolPosition.setMargins(dp(8),dp(8),dp(8),dp(8));
        map.addView(tools,toolPosition);
        status.setPadding(dp(12),dp(8),dp(12),dp(8));
        status.setBackground(Ui.rounded(this,Ui.SURFACE,10));
        android.widget.FrameLayout.LayoutParams noticePosition=new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        noticePosition.setMargins(dp(8),dp(8),dp(8),0);map.addView(status,noticePosition);
        mapCredit=label("离线矢量线网 · 按站点位置绘制",11,Ui.MUTED,false);
        mapCredit.setPadding(dp(12),dp(3),dp(12),dp(3));
        mapCredit.setVisibility(View.VISIBLE);
        page.addView(mapCredit,Ui.matchWrap());
        // All-line colour strip removed: individual station sheets retain the
        // relevant services, without taking another row away from the map.

        stationCard = column();
        stationCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams footer = Ui.matchWrap();
        footer.setMargins(dp(10), 0, dp(10), dp(8));
        ScrollView selectedScroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int maximum = Math.round(getResources().getDisplayMetrics().heightPixels * .37f);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST));
            }
        };
        selectedScroll.setFillViewport(false);
        selectedScroll.addView(stationCard, new android.widget.FrameLayout.LayoutParams(-1, -2));
        page.addView(selectedScroll, footer);
        setContentView(page);
        Ui.insets(this, header, 10, 10, 5, 4);
        renderStation();
        load();
    }

    private void load() {
        worker.execute(() -> {
            try {
                MetroNetwork result = new MetroNetwork(area, new MetroDataSource(this).queryLines(area.province));
                MetroDistricts districts = MetroDistricts.load(this,area);
                MetroGeography geometry=MetroGeography.load(this,area,result);
                MetroDiagramLayout layout = MetroDiagramLayout.load(this, area, result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                    if (result.stops.isEmpty()) {notice("暂无线网数据");return;}
                    network = result;
                    this.districts = districts;
                    schematic = layout;
                    diagram.setGeography(geometry);
                    geographicButton.setEnabled(!geometry.positions.isEmpty());
                    if(geographic&&geometry.positions.isEmpty()&&schematic!=null)geographic=false;
                    if (schematic==null) geographic=true;
                    diagram.setNetwork(network,districts,geographic?null:schematic);
                    styleModes();
                    start = network.byKey(savedStart);
                    destination=network.byKey(savedDestination);
                    selected = network.byKey(savedSelection);
                    renderStation();
                    showLocation(centerOnFix);
                    if (selected != null) diagram.post(() -> focusStation(selected));
                });
            } catch (Exception unavailable) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        loading.setVisibility(View.GONE);
                        notice("线网加载失败");
                    }
                });
            }
        });
    }

    private void changeMode(boolean geographic) {
        if (network == null) return;
        if (!geographic && schematic==null) {notice("规整图暂不可用，可查看位置线网");return;}
        this.geographic = geographic;
        clearPreview();
        diagram.setNetwork(network,districts,geographic?null:schematic);
        styleModes();
        renderStation();
        showLocation(false);
    }

    private void styleModes() {
        diagram.setVisibility(View.VISIBLE);
        mapCredit.setText("© OpenStreetMap contributors · 离线地图");
        mapCredit.setVisibility(geographic?View.VISIBLE:View.GONE);
        schematicButton.setBackground(Ui.rounded(this, !geographic ? Ui.PRIMARY : Ui.RAISED, 14));
        schematicButton.setTextColor(!geographic ? Ui.ON_PRIMARY : Ui.TEXT);
        geographicButton.setBackground(Ui.rounded(this, geographic ? Ui.PRIMARY : Ui.RAISED, 14));
        geographicButton.setTextColor(geographic ? Ui.ON_PRIMARY : Ui.TEXT);
    }

    private void mapZoom(float factor) {
        diagram.zoom(factor);
    }

    private void focusStation(MetroNetwork.Stop stop) {
        if (stop==null) return;
        if(!diagram.focusStop(stop))notice("该站的开放地图坐标暂未收录");
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.leftMargin = dp(4);
        return params;
    }

    private void select(MetroNetwork.Stop stop) {
        selected = stop;
        diagram.showSelection(selected,start);
        if(pickingStart!=null) {
            boolean origin=pickingStart;pickingStart=null;assign(stop,origin);return;
        }
        showStation(stop);
    }

    private void assign(MetroNetwork.Stop stop,boolean origin) {
        if(origin){start=stop;if(destination==start)destination=null;}
        else {destination=stop;if(start==destination)start=null;}
        clearPreview();
        renderStation();
    }

    private void renderStation() {
        stationCard.removeAllViews();
        stationCard.setBackground(Ui.edgeCard(this,Ui.PRIMARY,16));
        diagram.showSelection(destination==null?selected:destination, start);
        LinearLayout headline = row();
        headline.addView(label("地铁路线",16,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        Button clear=button("清空",() -> {start=null;destination=null;clearPreview();renderStation();});
        clear.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        clear.setPadding(dp(8), 0, dp(8), 0);
        headline.addView(clear,new LinearLayout.LayoutParams(-2,-2));
        stationCard.addView(headline, Ui.matchWrap());
        LinearLayout endpoints=row();
        endpoints.setBaselineAligned(false);
        endpoints.addView(endpoint(start,"起点",true),new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams swapSpace = new LinearLayout.LayoutParams(dp(48), dp(48));
        swapSpace.setMargins(dp(8), 0, dp(8), 0);
        swapSpace.gravity = Gravity.CENTER_VERTICAL;
        endpoints.addView(Ui.mapIcon(this,"swap","交换起终点",v -> {
            MetroNetwork.Stop previous=start;start=destination;destination=previous;
            clearPreview();renderStation();
        }),swapSpace);
        endpoints.addView(endpoint(destination,"终点",false),new LinearLayout.LayoutParams(0,-1,1));
        stationCard.addView(endpoints,Ui.matchWrap());
        LinearLayout preferences=row();
        preferences.setBackground(Ui.rounded(this, Ui.RAISED, 12));
        preferences.setPadding(dp(4), dp(4), dp(4), dp(4));
        for(MetroNetwork.Preference option:MetroNetwork.Preference.values()) {
            boolean active=routePreference==option;
            Button choice=Ui.button(this,option==MetroNetwork.Preference.FASTEST?"时间较短":"少换乘",
                    active?Ui.LIGHT_BLUE:android.graphics.Color.TRANSPARENT,active?Ui.PRIMARY:Ui.MUTED);
            choice.setOnClickListener(v -> {routePreference=option;clearPreview();renderStation();});
            preferences.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
        }
        LinearLayout.LayoutParams preferenceSpace=Ui.matchWrap();preferenceSpace.topMargin=dp(8);
        stationCard.addView(preferences,preferenceSpace);
        LinearLayout actions = row();
        actions.addView(plannerButton(false), new LinearLayout.LayoutParams(0, -2, 1));
        if (AppSettings.hasMapKey()) {
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.leftMargin = dp(8);
            actions.addView(plannerButton(true), right);
        }
        LinearLayout.LayoutParams querySpace = Ui.matchWrap();
        querySpace.topMargin = dp(8);
        stationCard.addView(actions, querySpace);
    }

    private Button plannerButton(boolean online) {
        boolean hasResult = preview != null && previewOnline == online;
        String title = online ? "高德规划" : "本地规划";
        if (routing && planningOnline == online) title = "正在" + title + "…";
        if (hasResult) {
            title += " · " + preview.plan.durationMin + " 分\n"
                    + Math.max(0, preview.tracks.size() - 1) + " 次换乘 · 查看";
        }
        boolean primary = hasResult || (preview == null && !online);
        Button button = Ui.button(this, title, primary ? Ui.PRIMARY : Ui.LIGHT_BLUE,
                primary ? Ui.ON_PRIMARY : Ui.PRIMARY);
        button.setMinimumHeight(Ui.textRowHeight(this, preview == null ? 48 : 64));
        button.setEnabled(!routing);
        button.setOnClickListener(v -> {
            if (hasResult) openPreview();
            else planToSelected(online);
        });
        return button;
    }

    private View endpoint(MetroNetwork.Stop stop,String caption,boolean origin) {
        LinearLayout box=column();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.control(this,Ui.RAISED));
        box.setPadding(dp(12),dp(9),dp(12),dp(9));
        box.setMinimumHeight(Ui.textRowHeight(this, 64));box.setClickable(true);box.setFocusable(true);
        box.addView(label(caption,12,Ui.MUTED,false),Ui.matchWrap());
        TextView value=label(stop==null?"选择站点":stop.name,15,stop==null?Ui.MUTED:Ui.TEXT,stop!=null);
        value.setMaxLines(2);value.setPadding(0,dp(5),0,0);box.addView(value,Ui.matchWrap());
        box.setContentDescription(caption+"，"+(stop==null?"选择站点":stop.name));
        box.setOnClickListener(v -> {pickingStart=origin;findStation();});
        return box;
    }

    private void showStation(MetroNetwork.Stop stop) {
        Dialog sheet=new Dialog(this);
        LinearLayout box=column();box.setPadding(dp(16),dp(16),dp(16),dp(16));
        box.setBackground(Ui.rounded(this,Ui.SURFACE,16));
        LinearLayout heading=row();
        heading.addView(label(stop.name,20,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(Ui.mapIcon(this,"close","关闭",v -> sheet.dismiss()),iconParams());
        box.addView(heading,Ui.matchWrap());
        TagFlowLayout serviceRow=new TagFlowLayout(this);serviceRow.setPadding(0,dp(8),0,dp(12));
        Set<String> added = new LinkedHashSet<>();
        for (MetroNetwork.Node node : stop.services) {
            if (!added.add(node.line.networkId)) continue;
            int colour = MetroNetworkView.lineColour(node.line);
            Button detail = Ui.button(this, MetroNetworkView.displayLine(node.line) + "  ›",
                    colour, ReadableColors.foreground(colour));
            detail.setOnClickListener(view -> {sheet.dismiss();stationDetails(node);});
            serviceRow.addView(detail);
        }
        box.addView(serviceRow,Ui.matchWrap());
        LinearLayout actions=row();
        actions.addView(button("设为起点",() -> {assign(stop,true);sheet.dismiss();}),weighted());
        Button finish=Ui.button(this,"设为终点",Ui.PRIMARY,Ui.ON_PRIMARY);
        finish.setOnClickListener(v -> {assign(stop,false);sheet.dismiss();});actions.addView(finish,weighted());
        box.addView(actions,Ui.matchWrap());
        if(AppSettings.hasMapKey()) {
            Button transit=button("公交及步行规划  ›",() -> {sheet.dismiss();openTransit();});
            LinearLayout.LayoutParams gap=Ui.matchWrap();gap.topMargin=dp(10);box.addView(transit,gap);
        }
        sheet.setContentView(box);sheet.show();
        if(sheet.getWindow()!=null) {
            sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            sheet.getWindow().setGravity(Gravity.BOTTOM);
            sheet.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels-dp(20),-2);
        }
    }

    private void clearPreview() {
        routeRequest++;
        routing = false;
        preview = null;
        previewStart = null;
        diagram.showRoute(null);
    }

    private void stationDetails(MetroNetwork.Node node) {
        startActivity(new Intent(this, MetroLineActivity.class)
                .putExtra("lineName", node.line.name)
                .putExtra("colour", MetroDataSource.normalizeColour(node.line.colour))
                .putExtra("highlightStation", node.station.name).putExtra("openStationSheet", true));
    }

    private void planToSelected(boolean online) {
        if (network == null || routing) return;
        if(destination==null){pickingStart=false;findStation();return;}
        MetroNetwork.Stop departure = start;
        if (departure == null && validFix(fix)) departure = network.nearest(fix.getLatitude(), fix.getLongitude(), 2);
        if (departure == null) {
            pickingStart=true;findStation();
            return;
        }
        if (departure == destination) {
            notice("请选择不同的起点和终点");
            return;
        }
        final MetroNetwork.Stop from = departure, to = destination;
        final MetroNetwork.Stop chosenStart = start;
        final Location gps = chosenStart == null && validFix(fix) ? fix : null;
        final MetroNetwork.Preference preference=routePreference;
        clearPreview();
        int request = routeRequest;
        routing = true;
        planningOnline = online;
        renderStation();
        worker.execute(() -> {
            if (request != routeRequest) return;
            MetroNetwork.Journey result = online ? queryOnline(from, to, gps, preference)
                    : network.plan(from, to, preference, System.currentTimeMillis());
            runOnUiThread(() -> {
                if (request != routeRequest || isDestroyed() || isFinishing()) return;
                routing = false;
                if (result == null) {
                    notice(online ? "高德暂未返回可在线网显示的地铁方案" : "本地暂未找到这两站之间的地铁方案");
                    renderStation();
                    return;
                }
                preview = result;
                previewStart = from;
                previewOnline = online;
                showLocation(false);
                renderStation();
                diagram.showSelection(destination, from);
                diagram.post(() -> { if (preview == result) diagram.showRoute(result); });
            });
        });
    }

    private MetroNetwork.Journey queryOnline(MetroNetwork.Stop from, MetroNetwork.Stop to, Location gps,MetroNetwork.Preference preference) {
        double[] origin = Coord.wgsToGcj(gps == null ? from.lat : gps.getLatitude(),
                gps == null ? from.lon : gps.getLongitude());
        double[] destination = Coord.wgsToGcj(to.lat, to.lon);
        try {
            List<ChelaileDataSource.TransitPlan> plans = ChelaileDataSource.transitQuery(from.city,
                    gps == null ? from.name : "我的位置", origin[0], origin[1],
                    to.name, destination[0], destination[1], from.city, to.city);
            MetroNetwork.Journey chosen=null;
            for (ChelaileDataSource.TransitPlan plan : plans) {
                MetroNetwork.Journey match = network.matchOnline(plan, from.city);
                if(match!=null&&(chosen==null
                        ||(preference==MetroNetwork.Preference.FEWER_TRANSFERS&&match.tracks.size()<chosen.tracks.size())
                        ||((preference==MetroNetwork.Preference.FASTEST||match.tracks.size()==chosen.tracks.size())&&match.plan.durationMin<chosen.plan.durationMin)))chosen=match;
            }
            return chosen;
        } catch (Exception unavailable) { }
        return null;
    }

    private void openPreview() {
        if (preview == null || previewStart == null || destination == null) return;
        CityInfo city = CityInfo.find(destination.city);
        Intent detail = new Intent(this, TransitDetailActivity.class)
                .putExtra("plan", preview.plan)
                .putExtra("origin", previewStart.name).putExtra("destination", destination.name)
                .putExtra("cityName", destination.city).putExtra("cityId", city == null ? "" : city.chelaileId)
                .putExtra("resolvedDirections", preview.directions).putExtra("offlineMetro", !previewOnline);
        int[] colours = new int[preview.plan.segments.size()];
        int leg = 0;
        for (int i = 0; i < colours.length; i++)
            if ("metro".equals(preview.plan.segments.get(i).type))
                colours[i] = MetroNetworkView.lineColour(preview.tracks.get(leg++).get(0).line);
        detail.putExtra("resolvedColours", colours);
        startActivity(detail);
    }

    private void openTransit() {
        if (selected == null || !AppSettings.hasMapKey()) return;
        CityInfo city = CityInfo.find(selected.city);
        startActivity(new Intent(this, TransitActivity.class)
                .putExtra("cityName", selected.city).putExtra("cityId", city == null ? "" : city.chelaileId)
                .putExtra("goName", selected.name).putExtra("goLat", selected.lat)
                .putExtra("goLon", selected.lon).putExtra("goMetro", true));
    }

    private void findStation() {
        if (network == null) return;
        Dialog dialog = new Dialog(this);
        LinearLayout box = column();
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(Ui.rounded(this, Ui.SURFACE, 16));
        TextView caption=label(pickingStart==null?"查找车站":pickingStart?"选择起点":"选择终点",19,Ui.TEXT,true);
        caption.setPadding(0,0,0,dp(12));box.addView(caption,Ui.matchWrap());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入站名");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(Ui.rounded(this, Ui.RAISED, 10));
        box.addView(input, Ui.matchWrap());
        HorizontalScrollView services=new HorizontalScrollView(this);
        services.setHorizontalScrollBarEnabled(false);
        LinearLayout serviceRow=row();serviceRow.setPadding(0,dp(8),0,dp(8));
        services.addView(serviceRow,new android.widget.FrameLayout.LayoutParams(-2,-2));
        box.addView(services,Ui.matchWrap());
        final String[] lineFilter={""};
        java.util.Map<String, Button> lineButtons = new java.util.LinkedHashMap<>();
        LinearLayout matches = column();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(matches, Ui.matchWrap());
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        box.addView(button("关闭", dialog::dismiss), Ui.matchWrap());
        Runnable filter=() -> {
            for (java.util.Map.Entry<String, Button> entry : lineButtons.entrySet()) {
                Button chip = entry.getValue();
                boolean active = entry.getKey().equals(lineFilter[0]);
                chip.setSelected(active);
                chip.setText((active ? "✓ " : "") + chip.getTag());
            }
            matches.removeAllViews();
            scroll.scrollTo(0, 0);
            List<MetroNetwork.Stop> found=input.length()==0?network.stops:network.search(input.getText().toString());
            int count=0;
            for(MetroNetwork.Stop stop:found) {
                boolean allowed=lineFilter[0].isEmpty();
                for(MetroNetwork.Node node:stop.services)if(node.line.networkId.equals(lineFilter[0]))allowed=true;
                if(!allowed)continue;
                LinearLayout item=column();item.setPadding(dp(12),dp(12),dp(12),dp(12));
                item.setMinimumHeight(dp(56));item.setBackground(Ui.control(this,Ui.SURFACE));
                item.setClickable(true);item.setFocusable(true);
                item.addView(label(stop.name,16,Ui.TEXT,true),Ui.matchWrap());
                LinkedHashSet<String> names=new LinkedHashSet<>();
                for(MetroNetwork.Node node:stop.services)names.add(MetroNetworkView.displayLine(node.line));
                TextView lines=label(stop.city+" · "+String.join(" / ",names),12,Ui.MUTED,false);
                lines.setPadding(0,dp(5),0,0);item.addView(lines,Ui.matchWrap());
                item.setOnClickListener(v -> {select(stop);dialog.dismiss();diagram.post(() -> focusStation(stop));});
                matches.addView(item,Ui.matchWrap());
                if(++count>=80)break;
            }
            if(count==0)matches.addView(label("没有匹配站点",14,Ui.MUTED,false),Ui.matchWrap());
        };
        Button all=button("全部线路",() -> {lineFilter[0]="";filter.run();});
        all.setTag("全部线路");
        lineButtons.put("", all);
        serviceRow.addView(all);
        Set<String> identities=new LinkedHashSet<>();
        for(MetroDataSource.MetroLine line:network.lines.keySet())if(identities.add(line.networkId)) {
            String name = MetroNetworkView.displayLine(line);
            int colour = MetroNetworkView.lineColour(line);
            Button choice = Ui.button(this, name, colour, ReadableColors.foreground(colour));
            choice.setTag(name);
            choice.setOnClickListener(v -> {lineFilter[0]=line.networkId;filter.run();});
            lineButtons.put(line.networkId, choice);
            LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-2,-2);gap.leftMargin=dp(8);serviceRow.addView(choice,gap);
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void afterTextChanged(Editable text) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter.run();
            }
        });
        filter.run();
        dialog.setOnDismissListener(v -> pickingStart=null);
        dialog.setContentView(box);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels-dp(24),Math.round(getResources().getDisplayMetrics().heightPixels*.74f));
        }
    }

    private void openOfficial() {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(area.official))); }
        catch (android.content.ActivityNotFoundException unavailable) { notice("没有可打开官方页面的浏览器"); }
    }

    private void startLocation(boolean requested) {
        if (!PrivacyNotice.locationAllowed()) return;
        if (locationManager == null) return;
        if (checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (requested && android.os.Build.VERSION.SDK_INT >= 23)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        centerOnFix |= requested;
        if (requested && validFix(fix) && network != null) showLocation(true);
        if (locationListener != null) {
            if (requested && !validFix(fix)) {
                notice("正在定位…");
                diagram.removeCallbacks(locationTimeout);
                diagram.postDelayed(locationTimeout, 15_000L);
            }
            return;
        }
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { accept(location); }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        boolean enabled = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                accept(locationManager.getLastKnownLocation(provider));
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 15_000L, 0, locationListener);
                    enabled = true;
                }
            } catch (RuntimeException ignored) { }
        }
        if (!enabled) stopLocation();
        if (requested && !validFix(fix)) notice(enabled ? "正在定位…" : "请打开系统定位");
        if (enabled) diagram.postDelayed(locationTimeout, 15_000L);
    }

    private void accept(Location location) {
        if (!validFix(location)) return;
        if (validFix(fix) && (fix.getTime() > location.getTime()
                || location.getAccuracy() > Math.max(100, fix.getAccuracy() * 2))) return;
        fix = location;
        diagram.removeCallbacks(expireFix);
        diagram.postDelayed(expireFix, Math.max(1000, 60_001L - (System.currentTimeMillis() - location.getTime())));
        showLocation(centerOnFix);
    }

    private boolean validFix(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 350
                || !TransitRules.validCoordinate(location.getLatitude(), location.getLongitude())) return false;
        long age = System.currentTimeMillis() - location.getTime();
        return age >= 0 && age <= 60_000;
    }

    private void showLocation(boolean center) {
        if (network == null) return;
        if (!validFix(fix)) {
            diagram.showLocation(0,0,0,false);
            return;
        }
        double lat = fix.getLatitude(), lon = fix.getLongitude();
        MetroNetwork.Stop near = geographic?diagram.geographicNearest(lat,lon,8):network.nearest(lat, lon, 8);
        diagram.showLocation(lat, lon, fix.getAccuracy(), near != null);
        if (near == null) {if(center)notice("当前位置在线网范围外");}
        else {
            if (center) {
                diagram.focus(lat,lon);
                centerOnFix = false;
            }
        }
    }

    private void stopLocation() {
        if (locationListener != null && locationManager != null) locationManager.removeUpdates(locationListener);
        locationListener = null;
        if (diagram != null) diagram.removeCallbacks(locationTimeout);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == LOCATION_PERMISSION) for (int grant : grants)
            if (grant == PackageManager.PERMISSION_GRANTED) { startLocation(true); break; }
    }
    @Override protected void onResume() {
        super.onResume();
        if (diagram != null) {
            if (!validFix(fix)) diagram.showLocation(0, 0, 0, false);
            startLocation(false);
        }
    }
    @Override protected void onPause() { stopLocation(); super.onPause(); }
    @Override protected void onDestroy() {
        if (diagram != null) diagram.removeCallbacks(expireFix);
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString("station", selected.key);
        if (start != null) state.putString("start", start.key);
        if(destination!=null)state.putString("destination",destination.key);
        state.putBoolean("geographic", geographic);
        state.putString("preference",routePreference.name());
    }

    private int dp(int n) { return Ui.dp(this, n); }
    private void notice(String value) {
        status.setText(value);status.setVisibility(View.VISIBLE);
        status.removeCallbacks(hideNotice);status.postDelayed(hideNotice,5000);
        status.announceForAccessibility(value);
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }
    private TextView label(String value, int size, int colour, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(colour);
        Ui.type(view, size, bold);
        return view;
    }
    private Button button(String value, Runnable click) {
        Button view = Ui.button(this, value, Ui.LIGHT_BLUE, Ui.PRIMARY);
        view.setMinHeight(dp(44));
        view.setMinimumHeight(dp(44));
        view.setOnClickListener(v -> click.run());
        return view;
    }
}
