package com.holi.prdbus;

import android.Manifest;
import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 独立换乘步骤页：用连续的线路轴展示上下车、站数、耗时和步行换乘。 */
public final class TransitDetailActivity extends ThemedActivity {
    private LinearLayout header;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService progressWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService boardWorker = Executors.newFixedThreadPool(2);
    private final android.util.SparseArray<TextView> boardViews = new android.util.SparseArray<>();
    private final android.util.SparseBooleanArray boardBusy = new android.util.SparseBooleanArray();
    private int pendingProgress;
    private int activeProgressSegment = -1;
    private float activeProgressFraction;
    private TextView locationStatus;
    private View openingProgress;
    private boolean openingSegment;
    private final android.util.SparseArray<View> segmentFrames = new android.util.SparseArray<>();
    private String cityId = "019";
    private String cityName = "佛山";
    private double currentLat;
    private double currentLon;
    private boolean hasLocation;
    private java.util.ArrayList<String> resolvedNames;
    private int[] resolvedColours;
    private int[] resolvedDirections;
    private boolean offlineMetro;
    private ChelaileDataSource.TransitPlan currentPlan;
    private final android.util.SparseArray<TextView> liveProgressViews =
            new android.util.SparseArray<>();
    private LocationManager locationManager;
    private LocationListener locationListener;
    private long lastProgressAt;
    private long fixElapsed;
    private float fixAccuracy;
    private int progressGeneration;
    private final android.os.Handler progressTimer = new android.os.Handler();
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            refreshLiveProgress(true);
            refreshBoardArrivals();
            progressTimer.postDelayed(this, 15_000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.edgeToEdge(this);
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Ui.NIGHT)
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ChelaileDataSource.TransitPlan plan = (ChelaileDataSource.TransitPlan)
                getIntent().getSerializableExtra("plan");
        if (plan == null) { finish(); return; }
        currentPlan = plan;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        String origin = getIntent().getStringExtra("origin");
        String destination = getIntent().getStringExtra("destination");
        String incomingCityId = getIntent().getStringExtra("cityId");
        String incomingCityName = getIntent().getStringExtra("cityName");
        if (incomingCityId != null) cityId = incomingCityId;
        if (incomingCityName != null) cityName = incomingCityName;
        currentLat = getIntent().getDoubleExtra("currentLat", 0);
        currentLon = getIntent().getDoubleExtra("currentLon", 0);
        hasLocation = getIntent().getBooleanExtra("hasLocation", false)
                && currentLat != 0 && currentLon != 0;
        resolvedNames = getIntent().getStringArrayListExtra("resolvedNames");
        resolvedColours = getIntent().getIntArrayExtra("resolvedColours");
        resolvedDirections = getIntent().getIntArrayExtra("resolvedDirections");
        offlineMetro = getIntent().getBooleanExtra("offlineMetro", false);
        build(plan, origin == null ? "起点" : origin,
                destination == null ? "终点" : destination);
        View placeholder=Ui.loadingDots(this);placeholder.setVisibility(View.GONE);
        openingProgress=Ui.centerLoader(this,placeholder);
        Ui.insets(this, header, 14, 14, 8, 10);
        refreshLiveProgress(true);
    }

    private void build(ChelaileDataSource.TransitPlan plan, String origin, String destination) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.SURFACE);

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(10));
        header.setBackgroundColor(Ui.SURFACE);
        TextView back = text("返回", 15, Ui.TEXT, true);
        Ui.icon(back, "back", Ui.TEXT);
        Ui.target(back);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> {
            finish();
            Ui.transitionBack(this);
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("行程详情", Ui.TITLE, Ui.TEXT, true);
        TextView subtitle = text(origin + "  →  " + destination, 12, Ui.MUTED, false);
        subtitle.setPadding(0,dp(4),0,0);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(RideCode.icon(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(28));
        body.addView(summary(plan), matchWrap());
        locationStatus = text("正在获取当前位置…", 13, Ui.MUTED, false);
        locationStatus.setPadding(dp(4), dp(8), dp(4), dp(4));
        body.addView(locationStatus, matchWrap());

        TextView section = text("行程步骤", Ui.SECTION, Ui.MUTED, true);
        section.setLetterSpacing(.04f);
        section.setPadding(dp(4), dp(18), 0, dp(8));
        body.addView(section);

        TextView start = text("起点  ·  " + origin, 15, Ui.TEXT, true);
        start.setPadding(dp(42), dp(12), 0, dp(8));
        body.addView(start);
        for (int i = 0; i < plan.segments.size(); i++) {
            ChelaileDataSource.TransitSegment segment = plan.segments.get(i);
            View step = "walk".equals(segment.type) ? walkStep(segment)
                    : rideStep(segment, i);
            body.addView(step, "walk".equals(segment.type) ? matchWrap() : stepParams());
            step.setAlpha(1f);
            step.setTranslationY(0);
            step.animate().alpha(1f).translationY(0).setStartDelay(i * 45L)
                    .setDuration(0).start();
        }
        TextView end = text("终点  ·  " + destination, 15, Ui.TEXT, true);
        end.setPadding(dp(42), dp(10), 0, dp(18));
        body.addView(end);
        TextView hint = text("点击任一公交或地铁线路，可查看对应方向的线路详情", 12,
                Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), dp(12), dp(10), dp(8));
        body.addView(hint);
        scroll.addView(body, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shell);
    }

    private View summary(ChelaileDataSource.TransitPlan plan) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.rounded(this, Ui.RAISED, 12));
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.BOTTOM);
        TextView duration = text("约 " + plan.durationMin + " 分钟", 23, Ui.TEXT, true);
        headline.addView(duration);
        int rides = 0;
        for (ChelaileDataSource.TransitSegment segment : plan.segments) {
            if (!"walk".equals(segment.type)) rides++;
        }
        TextView overview = text("  " + (rides <= 1 ? "直达" : (rides - 1) + "次换乘")
                + (plan.cost == null || plan.cost.isEmpty() ? "" : "  ·  " + plan.cost + "元"), 13, Ui.MUTED, false);
        overview.setPadding(0, 0, 0, dp(3));
        headline.addView(overview, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(headline, matchWrap());
        TagFlowLayout chips = new TagFlowLayout(this);
        chips.setPadding(0, dp(12), 0, 0);
        for (int i = 0; i < plan.segments.size(); i++) {
            ChelaileDataSource.TransitSegment segment = plan.segments.get(i);
            if ("walk".equals(segment.type)) continue;
            TextView chip = text(segmentName(segment, i), 13, Color.WHITE, true);
            chip.setSingleLine(true);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.routeBadge(chip, routeColor(segment, i),
                    "metro".equals(segment.type) && TransitSegmentNavigator.railRegion(segment).isEmpty(), 12);
            chips.addView(chip);
        }
        card.addView(chips, matchWrap());
        TextView meta = text(offlineMetro ? "站到站估算 · 含换乘时间 · 候车另计"
                : "共 " + rides + " 段乘车   ·   步行 " + plan.walkingDistance + " 米", 13, Ui.MUTED, false);
        meta.setPadding(0, dp(12), 0, 0);
        card.addView(meta);
        return card;
    }

    private View rideStep(ChelaileDataSource.TransitSegment segment, int index) {
        int color = routeColor(segment, index);
        final TextView from = text(nonEmpty(segment.from, "上车站"), 18, Ui.TEXT, true);
        final TextView to = text(nonEmpty(segment.to, "下车站"), 18, Ui.TEXT, true);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(Canvas canvas) {
                float x = dp(18);
                float top = from.getTop() + from.getHeight() / 2f;
                float bottom = to.getTop() + to.getHeight() / 2f;
                paint.setColor(color);
                paint.setStrokeWidth(dp(7));
                canvas.drawLine(x, top, x, bottom, paint);
                for (float y : new float[]{top, bottom}) {
                    paint.setColor(color);
                    canvas.drawCircle(x, y, dp(8), paint);
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(x, y, dp(5), paint);
                }
                super.dispatchDraw(canvas);
                if (activeProgressSegment == index) {
                    float position = top + (bottom - top) * activeProgressFraction;
                    paint.setColor(0x332E9FE6);
                    canvas.drawCircle(x, position, dp(13), paint);
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(x, position, dp(9), paint);
                    paint.setColor(0xFF258FD2);
                    canvas.drawCircle(x, position, dp(6), paint);
                }
            }
        };
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(42), dp(10), dp(4), dp(10));
        from.setPadding(0, dp(5), 0, dp(5));
        content.addView(from);
        LinearLayout lineRow = new LinearLayout(this);
        lineRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(segmentName(segment, index), 13, Color.WHITE, true);
        badge.setMaxWidth(dp(150));
        badge.setMaxLines(2);
        badge.setPadding(dp(7), dp(4), dp(7), dp(4));
        Ui.routeBadge(badge, color,
                "metro".equals(segment.type) && TransitSegmentNavigator.railRegion(segment).isEmpty(), 4);
        lineRow.addView(badge);
        // A segment's alighting stop is not necessarily the train's terminal direction.
        TextView direction = text("  乘至 " + nonEmpty(segment.to, "下车站"), 13, Ui.MUTED, false);
        direction.setMaxLines(2);
        direction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lineRow.addView(direction, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(lineRow);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(12), dp(12), dp(12));
        info.setBackground(Ui.rounded(this, Ui.RAISED, 7));
        String railRegion = TransitSegmentNavigator.railRegion(segment);
        boolean intercity = RailCatalog.GD.equals(railRegion);
        boolean suburban = RailCatalog.SH.equals(railRegion);
        String infoTitle = intercity ? "城际班次与线路  ›"
                : suburban ? "市域铁路线路  ›"
                : "railway".equals(segment.type) ? "铁路班次  ›"
                : "metro".equals(segment.type) ? "地铁时刻与线路  ›" : "公交实时到站与线路  ›";
        info.addView(text(infoTitle, 14, Ui.TEXT, false));
        TextView note = text(intercity ? "正在查询可赶上的班次…"
                : "metro".equals(segment.type) ? "时刻表预测，非实时列车定位"
                : "点击查看上车站对应方向", 13, Ui.PRIMARY, false);
        note.setPadding(0, dp(6), 0, 0);
        info.addView(note);
        final long[] lastClick = {0};
        View.OnClickListener open = v -> {
            if(openingSegment)return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastClick[0] < 2000) return;
            lastClick[0] = now;
            note.setText("正在匹配线路与方向…");
            openingSegment=true;openingProgress.setVisibility(View.VISIBLE);
            if (intercity && currentPlan != null) {
                TransitSegmentNavigator.openRegional(this, segment,
                        RailTimetable.readyAt(currentPlan.segments, index, System.currentTimeMillis()));
            } else {
                TransitSegmentNavigator.open(this, worker, segment,
                        cityId, cityName, hasLocation ? currentLat : 0, hasLocation ? currentLon : 0,
                        resolvedDirection(index));
            }
            worker.execute(()->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){
                openingSegment=false;openingProgress.setVisibility(View.GONE);
            }}));
        };
        frame.setOnClickListener(open);
        from.setOnClickListener(open);
        to.setOnClickListener(open);
        lineRow.setOnClickListener(open);
        if (intercity || "bus".equals(segment.type) || "metro".equals(segment.type)) boardViews.put(index,note);
        info.setOnClickListener(open);
        info.setContentDescription("查看" + segmentName(segment, index) + "对应方向详情");
        Ui.pressFeedback(info);
        LinearLayout.LayoutParams boxLp = matchWrap();
        boxLp.topMargin = dp(12);
        boxLp.bottomMargin = dp(10);
        content.addView(info, boxLp);
        TextView live = text("正在获取当前位置…", 18, Ui.TEXT, true);
        live.setLineSpacing(dp(5), 1.05f);
        live.setPadding(0, dp(8), 0, dp(8));
        live.setVisibility(View.GONE);
        content.addView(live);
        liveProgressViews.put(index, live);
        segmentFrames.put(index, frame);
        TextView stats = text((segment.stops > 0 ? segment.stops + "站" : "乘车")
                + (segment.durationMin > 0 ? "（约" + segment.durationMin + "分钟）" : "")
                + "  ·  查看线路 ›", 14, Ui.MUTED, false);
        stats.setPadding(0, dp(22), 0, dp(22));
        stats.setOnClickListener(open);
        content.addView(stats);
        to.setPadding(0, dp(6), 0, dp(5));
        content.addView(to);
        frame.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    private View walkStep(ChelaileDataSource.TransitSegment segment) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView dots = text("·\n·\n·", 20, Color.rgb(218, 225, 235), true);
        dots.setGravity(Gravity.CENTER);
        row.addView(dots, new LinearLayout.LayoutParams(dp(36), dp(76)));
        String label = segment.distance > 0
                ? "🚶  步行 " + segment.distance + " 米"
                : offlineMetro ? "🚶  换乘" : "🚶  站内换乘";
        if (segment.durationMin > 0) label += "（约" + segment.durationMin + "分钟）";
        TextView walk = text(label, 13, Ui.MUTED, false);
        walk.setPadding(dp(12), dp(9), dp(12), dp(9));
        walk.setBackgroundColor(Ui.SURFACE);
        row.addView(walk, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int routeColor(ChelaileDataSource.TransitSegment segment, int index) {
        if (resolvedColours != null && index >= 0 && index < resolvedColours.length
                && resolvedColours[index] != 0) return resolvedColours[index];
        if (!"metro".equals(segment.type)) return Color.rgb(55, 153, 210);
        String n = segment.name == null ? "" : segment.name;
        if (n.contains("广佛")) return Color.rgb(182, 204, 0);
        if (n.contains("11号")) return Color.rgb(246, 178, 45);
        if (n.contains("2号")) return Color.rgb(0, 131, 167);
        if (n.contains("3号")) return Color.rgb(238, 130, 0);
        if (n.contains("6号")) return Color.rgb(128, 30, 82);
        return Color.rgb(69, 137, 194);
    }

    private int resolvedDirection(int index) {
        return resolvedDirections != null && index >= 0 && index < resolvedDirections.length
                ? resolvedDirections[index] : -1;
    }

    private String segmentName(ChelaileDataSource.TransitSegment segment, int index) {
        if (resolvedNames != null && index >= 0 && index < resolvedNames.size()) {
            String value = resolvedNames.get(index);
            if (value != null && !value.trim().isEmpty()) return shortName(value);
        }
        return shortName(segment.name);
    }

    private static String shortName(String value) {
        String name = MetroLineResolver.displayName(value == null ? "线路" : value);
        int bracket = name.indexOf('(');
        if (bracket > 0) name = name.substring(0, bracket);
        return name.length() > 12 ? name.substring(0, 12) + "…" : name;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        Ui.type(view, Math.round(size), bold);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(float value) { return Ui.dp(this, value); }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams stepParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(9);
        return params;
    }

    private boolean hasLocationPermission() {
        return android.os.Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void hideSegmentProgress(String message) {
        activeProgressSegment = -1;
        for (int i = 0; i < liveProgressViews.size(); i++)
            liveProgressViews.valueAt(i).setVisibility(View.GONE);
        for (int i = 0; i < segmentFrames.size(); i++) segmentFrames.valueAt(i).invalidate();
        if (locationStatus != null) {
            locationStatus.setText(message);
            locationStatus.setVisibility(View.VISIBLE);
        }
    }

    private void applyRouteProgress(TransitSegmentNavigator.ProgressResult[] results,
                                    int generation, long queryFix) {
        if (generation != progressGeneration) return;
        if (android.os.SystemClock.elapsedRealtime() - queryFix > 30_000L) {
            hideSegmentProgress("定位已过期，等待更新…");
            return;
        }
        double[] distances = new double[results.length];
        float[] fractions = new float[results.length];
        java.util.Arrays.fill(distances, -1);
        for (int i = 0; i < results.length; i++) if (results[i] != null) {
            distances[i] = results[i].distanceKm;
            fractions[i] = results[i].fraction;
        }
        int chosen = RouteProgressSelection.select(distances, fractions, activeProgressSegment);
        if (chosen < 0) {
            hideSegmentProgress("当前位置暂未匹配到乘车区间");
            return;
        }
        activeProgressSegment = chosen;
        activeProgressFraction = fractions[chosen];
        for (int i = 0; i < liveProgressViews.size(); i++) {
            int index = liveProgressViews.keyAt(i);
            TextView target = liveProgressViews.valueAt(i);
            target.setVisibility(index == chosen ? View.VISIBLE : View.GONE);
            if (index == chosen) target.setText(formatProgress(results[chosen].text));
        }
        if (locationStatus != null) locationStatus.setVisibility(View.GONE);
        for (int i = 0; i < segmentFrames.size(); i++) segmentFrames.valueAt(i).invalidate();
    }

    private void refreshLiveProgress(boolean force) {
        if (fixElapsed == 0 || android.os.SystemClock.elapsedRealtime() - fixElapsed > 30_000L) {
            progressGeneration++;
            hideSegmentProgress("定位未就绪或已过期，等待更新…");
            return;
        }
        if (!hasLocation || currentPlan == null || pendingProgress > 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastProgressAt < 15_000L) return;
        lastProgressAt = now;
        final int generation = ++progressGeneration;
        final long queryFix = fixElapsed;
        final double queryLat = currentLat, queryLon = currentLon;
        final TransitSegmentNavigator.ProgressResult[] results =
                new TransitSegmentNavigator.ProgressResult[currentPlan.segments.size()];
        // Count the entire batch first; only publish a single selection after all candidates return.
        for (int i = 0; i < currentPlan.segments.size(); i++)
            if (liveProgressViews.get(i) != null) pendingProgress++;
        if (pendingProgress == 0) {
            hideSegmentProgress("当前行程为步行区间");
            return;
        }
        for (int i = 0; i < currentPlan.segments.size(); i++) {
            final int index = i;
            ChelaileDataSource.TransitSegment segment = currentPlan.segments.get(i);
            if (liveProgressViews.get(i) == null) continue;
            TransitSegmentNavigator.loadProgress(this, progressWorker, segment,
                    cityId, cityName, queryLat, queryLon, result -> {
                        pendingProgress = Math.max(0, pendingProgress - 1);
                        results[index] = result;
                        if (pendingProgress == 0) applyRouteProgress(results, generation, queryFix);
                    });
        }
    }

    private void refreshBoardArrivals() {
        if(currentPlan==null)return;
        for(int i=0;i<boardViews.size();i++){
            final int index=boardViews.keyAt(i); final TextView target=boardViews.valueAt(i);
            if(boardBusy.get(index))continue;
            final ChelaileDataSource.TransitSegment segment=currentPlan.segments.get(index);
            boardBusy.put(index,true);
            TransitSegmentNavigator.ProgressCallback done=value->{
                boardBusy.put(index,false);
                target.setText(value+"\n"+Ui.updatedAt(System.currentTimeMillis()));
            };
            if (RailCatalog.GD.equals(TransitSegmentNavigator.railRegion(segment))) {
                if (!RailCatalog.GD.equals(RailCatalog.regionForTrip(segment.name,
                        segment.from, segment.to, segment.cityHint))) {
                    done.onResult("城际站点信息待确认 · 点击重新选择");
                    continue;
                }
                long now = System.currentTimeMillis();
                long ready = RailTimetable.readyAt(currentPlan.segments, index, now);
                boardWorker.execute(() -> {
                    RailTimetable.Result result = RailTimetable.upcoming(this, segment.from, segment.to, ready);
                    runOnUiThread(() -> {
                        boardBusy.put(index, false);
                        if (!isFinishing() && !isDestroyed() && boardViews.get(index) == target) {
                            target.setText(result.summary(System.currentTimeMillis())
                                    + (result.trains.isEmpty() ? "" : "\n按前段行程加 15 分钟进站余量筛选"));
                        }
                    });
                });
            }
            else if("bus".equals(segment.type))TransitSegmentNavigator.loadBoardArrivals(this,boardWorker,segment,cityId,done);
            else boardWorker.execute(()->{
                String value="暂无班次";
                try {
                    java.util.List<MetroDataSource.MetroLine> lines=new MetroDataSource(this).queryLines();
                    MetroDataSource.MetroLine line=MetroLineResolver.best(lines,segment.name,segment.from,segment.to,cityName);
                    if(line!=null){
                        int from=MetroLineResolver.stationIndex(line,segment.from),to=MetroLineResolver.stationIndex(line,segment.to);
                        int direction = resolvedDirection(index);
                        java.util.List<String> times=new MetroSchedule(line,line.stations,direction < 0 ? from < to : direction == 0,
                                MetroTimes.speedKmPerMin(line.name)).arrivals(from,MetroTimes.nowMinuteExact(),3);
                        if(!times.isEmpty())value="约 "+android.text.TextUtils.join("  /  ",times);
                    }
                } catch(Exception unavailable){}
                final String text=value;runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())done.onResult(text);});
            });
        }
    }

    private CharSequence formatProgress(String value) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "到 (.+?) 还有 (\\d+) 站 · (?:按行程估算)?约 (\\d+) 分钟").matcher(value);
        String summary = match.find() ? "到 " + match.group(1) + " 还有 " + match.group(2)
                + " 站，约 " + match.group(3) + " 分钟" : value;
        String stamp = "\n" + Ui.updatedAt(System.currentTimeMillis());
        android.text.SpannableString styled = new android.text.SpannableString(summary + stamp);
        java.util.regex.Matcher emphasis = java.util.regex.Pattern.compile(
                "^到 (.+?) 还有 (\\d+) 站，约 (\\d+) 分钟$").matcher(summary);
        if (emphasis.find()) {
            for (int group = 1; group <= 3; group++) styled.setSpan(
                    new android.text.style.ForegroundColorSpan(Ui.ACCENT), emphasis.start(group),
                    emphasis.end(group), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        styled.setSpan(new android.text.style.AbsoluteSizeSpan(11, true), summary.length(),
                styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), summary.length(),
                styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        targetNormalWeight(styled, summary.length());
        return styled;
    }

    private void targetNormalWeight(android.text.SpannableString text, int start) {
        text.setSpan(new android.text.style.TypefaceSpan("sans-serif-light"), start,
                text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void startLiveLocation() {
        if (!PrivacyNotice.locationAllowed()) return;
        if (!hasLocationPermission() || locationManager == null) return;
        if (locationListener == null) {
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (location == null) return;
                    long elapsed = android.os.SystemClock.elapsedRealtime();
                    long captured = location.getElapsedRealtimeNanos() / 1_000_000L;
                    if (captured <= 0 || elapsed - captured > 30_000L || captured > elapsed
                            || !location.hasAccuracy() || location.getAccuracy() > 100
                            || !TransitRules.validCoordinate(location.getLatitude(), location.getLongitude())) return;
                    if (captured < fixElapsed) return;
                    if (elapsed - fixElapsed < 15_000L && fixAccuracy > 0
                            && location.getAccuracy() > fixAccuracy * 2) return;
                    boolean firstFix = fixElapsed == 0;
                    fixElapsed = captured;
                    fixAccuracy = location.getAccuracy();
                    currentLat = location.getLatitude();
                    currentLon = location.getLongitude();
                    hasLocation = currentLat != 0 && currentLon != 0;
                    if (firstFix) refreshLiveProgress(true);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            };
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 15_000L, 0f, locationListener);
            }
        } catch (Exception ignored) { }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 15_000L, 0f, locationListener);
            }
        } catch (Exception ignored) { }
    }

    private void stopLiveLocation() {
        if (locationManager == null || locationListener == null) return;
        try { locationManager.removeUpdates(locationListener); }
        catch (Exception ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        startLiveLocation();
        progressTimer.removeCallbacks(progressTick);
        progressTimer.post(progressTick);
    }

    @Override protected void onPause() {
        progressTimer.removeCallbacks(progressTick);
        progressGeneration++;
        stopLiveLocation();
        super.onPause();
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
        Ui.transitionBack(this);
    }

    @Override protected void onDestroy() {
        progressTimer.removeCallbacks(progressTick);
        stopLiveLocation();
        worker.shutdownNow();
        progressWorker.shutdownNow();
        boardWorker.shutdownNow();
        super.onDestroy();
    }

    private final class RouteRail extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        RouteRail(int color) { super(TransitDetailActivity.this); this.color = color; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() * .62f;
            float top = dp(16), bottom = getHeight() - dp(16), radius = dp(9);
            paint.setColor(color);
            paint.setStrokeWidth(dp(8));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, top, radius + dp(2), paint);
            canvas.drawCircle(x, bottom, radius + dp(2), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, top, radius - dp(2), paint);
            canvas.drawCircle(x, bottom, radius - dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
