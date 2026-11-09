package com.holi.prdbus;

import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 线路详情页：同一条线路的两个方向（去/回）车辆都显示。 */
public final class LineDetailActivity extends ThemedActivity {
    private Button quickDirectionButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService arrivalWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService roadWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService stationWorker = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> stationRequest;
    private int stationSequence;
    private android.app.Dialog stationDialog;

    private String cityId;
    private String lineId;
    private String lineName;
    private String stationName = "";
    private String confirmedStationId = "";
    private String confirmedPhysicalId = "", confirmedNamesakeId = "";
    private double stopLat = 0;
    private double stopLon = 0;
    private double userLat = 0;
    private double userLon = 0;

    private LinearLayout listRoot;
    private ScrollView scroll;
    private View progress;
    private TextView updateAgeLabel;
    private LinearLayout headerBox;
    private int statusBarH = 0;
    private TextView favoriteButton;
    private MapView lineMap;
    private android.widget.FrameLayout lineMapFrame;
    private int lineMapSmallHeight;
    private int lineMapExpandedHeight;
    private boolean lineMapExpanded;
    private android.animation.ValueAnimator lineMapAnimator;
    private boolean loadInFlight;
    private int selectionVersion;
    private boolean silentRender;
    private long arrivalsUpdatedAt;
    private long arrivalsUpdatedElapsed;
    private java.util.Map<String, List<ChelaileDataSource.Bus>> targetArrivals = new java.util.HashMap<>();
    private boolean mapFocusedInitially;
    private boolean roadSearchStarted;
    private final java.util.Map<String, ChelaileDataSource.GaodeLine> roadLines =
            new java.util.concurrent.ConcurrentHashMap<>();
    private double nearestVehicleLat;
    private double nearestVehicleLon;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        cityId = intent.getStringExtra("cityId");
        lineId = intent.getStringExtra("lineId");
        lineName = intent.getStringExtra("lineName");
        stationName = intent.getStringExtra("stationName");
        confirmedStationId = intent.getStringExtra("stationId");
        if (confirmedStationId == null) confirmedStationId = "";
        confirmedPhysicalId = intent.getStringExtra("physicalStId");
        confirmedNamesakeId = intent.getStringExtra("namesakeStId");
        stopLat = intent.getDoubleExtra("stopLat", 0);
        stopLon = intent.getDoubleExtra("stopLon", 0);
        userLat = intent.getDoubleExtra("userLat", stopLat);
        userLon = intent.getDoubleExtra("userLon", stopLon);
        if (cityId == null) cityId = "019";
        if (lineName == null) lineName = "";

        applyEdgeToEdge();
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Ui.NIGHT)
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildUi();
        applyInsets();
        load(false);
    }

    private void applyEdgeToEdge() { Ui.edgeToEdge(this); }

    private void applyInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                statusBarH = bars.top;
                findViewById(android.R.id.content).setPadding(0, 0, 0, bars.bottom);
            } else {
                statusBarH = insets.getSystemWindowInsetTop();
                findViewById(android.R.id.content).setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            }
            if (headerBox != null) {
                headerBox.setPadding(dp(16), dp(4) + statusBarH, dp(16), dp(4));
            }
            return insets;
        });
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.TRANSPARENT);

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(dp(16), dp(10), dp(16), dp(12));
        headerBox.setBackgroundColor(Ui.SURFACE);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("返回");
        Ui.icon(back, "back", Ui.TEXT);
        Ui.target(back);
        back.setTextSize(16);
        back.setTextColor(TEXT);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(v -> {
            finish();
            Ui.transitionBack(this);
        });
        top.addView(back);
        TextView lineNameText = new TextView(this);
        lineNameText.setText(lineName.isEmpty() ? "线路" : lineName);
        lineNameText.setTextSize(18);
        lineNameText.setTextColor(TEXT);
        lineNameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lineNameText.setSingleLine(true);
        lineNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(lineNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        favoriteButton = new CenteredIconView(this,"star",PRIMARY);
        favoriteButton.setTextSize(25);
        favoriteButton.setTextColor(PRIMARY);
        favoriteButton.setGravity(Gravity.CENTER);
        favoriteButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        favoriteButton.setBackground(rounded(0x2FFFFFFF, 18));
        favoriteButton.setOnClickListener(v -> toggleLineFavorite());
        Ui.pressFeedback(favoriteButton);
        top.addView(favoriteButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        updateFavoriteButton();
        headerBox.addView(top);

        page.addView(headerBox, matchWrap());
        progress = Ui.loadingDots(this);
        progress.setVisibility(View.GONE);

        lineMapFrame = new android.widget.FrameLayout(this);
        lineMap = new MapView(this);
        lineMap.setBackground(Ui.edgeCard(this, PRIMARY, 20));
        lineMap.setClipToOutline(true);
        // MapView 自身不能高于兄弟控制层，否则 elevation 会让地图拦住按钮触摸。
        lineMap.setElevation(dp(1));
        if (userLat != 0 && userLon != 0) {
            lineMap.setUserLocation(userLat, userLon);
            lineMap.centerOn(userLat, userLon, 16);
        }
        lineMap.setMapGestureListener(this::expandLineMap);
        lineMapFrame.addView(lineMap, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout mapControls = new LinearLayout(this);
        mapControls.setOrientation(LinearLayout.VERTICAL);
        mapControls.setClickable(true);
        mapControls.setFocusable(true);
        mapControls.setElevation(dp(6));
        mapControls.setPadding(dp(4),dp(4),dp(4),dp(4));
        addMapControl(mapControls, "+", v -> lineMap.zoomIn());
        addMapControl(mapControls, "−", v -> lineMap.zoomOut());
        addMapControl(mapControls, "◎", v -> focusLineMap());
        android.widget.FrameLayout.LayoutParams controlsLp = new android.widget.FrameLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM);
        controlsLp.rightMargin = dp(10);
        controlsLp.bottomMargin = dp(8);
        lineMapFrame.addView(mapControls, controlsLp);
        mapControls.bringToFront();
        lineMapSmallHeight = dp(190);
        lineMapExpandedHeight = Math.max(lineMapSmallHeight,
                getResources().getDisplayMetrics().heightPixels * 3 / 8);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, lineMapSmallHeight);
        mapLp.setMargins(dp(16), dp(2), dp(16), dp(8));
        page.addView(lineMapFrame, mapLp);

        ScrollView scroll = new ScrollView(this);
        this.scroll = scroll;
        final float[] scrollTouchY = new float[1];
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scrollTouchY[0] = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && scrollTouchY[0] - event.getY() > dp(12)) {
                collapseLineMap();
                scrollTouchY[0] = event.getY();
            }
            return false;
        });
        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        // Scroll the last station above the thumb controls instead of leaving it underneath.
        listRoot.setPadding(dp(16), dp(2), dp(16), Ui.textRowHeight(this, 48) + dp(40));
        scroll.addView(listRoot, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Keep a compact, reachable pair above the system gesture area.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setElevation(dp(8));
        quickDirectionButton = quickAction("", "swap", false);
        quickDirectionButton.setContentDescription("切换公交线路方向");
        quickDirectionButton.setEnabled(false);
        quickDirectionButton.setAlpha(.45f);
        quickDirectionButton.setOnClickListener(v -> selectDirection(1 - activeTab));
        LinearLayout.LayoutParams directionLp = new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 48), 1f);
        directionLp.rightMargin = dp(8);
        quickActions.addView(quickDirectionButton, directionLp);
        Button quickRefresh = quickAction("", "refresh", true);
        quickRefresh.setContentDescription("刷新公交双向到站时间");
        quickRefresh.setOnClickListener(v -> load(true));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 48), 1f);
        refreshLp.rightMargin = dp(8);
        quickActions.addView(quickRefresh, refreshLp);
        quickActions.addView(RideCode.icon(this), new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 48), 1f));
        android.widget.FrameLayout.LayoutParams quickLp = new android.widget.FrameLayout.LayoutParams(
                dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        quickLp.rightMargin = dp(16);
        quickLp.bottomMargin = dp(16);
        root.addView(quickActions, quickLp);
        quickActions.bringToFront();

        setContentView(root);
        progress = Ui.centerLoader(this, progress);
    }

    private Button quickAction(String text, String icon, boolean primary) {
        Button button = Ui.button(this, text, primary ? PRIMARY : Ui.SURFACE,
                primary ? Ui.ON_PRIMARY : PRIMARY);
        Ui.icon(button, icon, primary ? Ui.ON_PRIMARY : PRIMARY);
        if (text.isEmpty()) button.setCompoundDrawablePadding(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setTextSize(14);
        button.setElevation(dp(2));
        return button;
    }

    private void addMapControl(LinearLayout parent, String label, View.OnClickListener action) {
        String kind = "+".equals(label) ? "plus" : "−".equals(label) ? "minus" : "locate";
        Ui.mapControl(parent,kind,"+".equals(label)?"放大地图":"−".equals(label)?"缩小地图":"定位到我的位置",
                v -> {expandLineMap();action.onClick(v);});
    }
    private void expandLineMap() {
        animateLineMapHeight(lineMapExpandedHeight, true);
    }

    private void collapseLineMap() {
        animateLineMapHeight(lineMapSmallHeight, false);
    }

    private void animateLineMapHeight(int target, boolean expanded) {
        if (lineMapFrame == null || lineMapExpanded == expanded) return;
        ViewGroup.LayoutParams params = lineMapFrame.getLayoutParams();
        if (params == null || params.height == target) {
            lineMapExpanded = expanded;
            return;
        }
        if (lineMapAnimator != null) lineMapAnimator.cancel();
        lineMapAnimator = android.animation.ValueAnimator.ofInt(params.height, target);
        lineMapAnimator.setDuration(Ui.duration(this, 220));
        lineMapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        lineMapAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams current = lineMapFrame.getLayoutParams();
            current.height = (Integer) animation.getAnimatedValue();
            lineMapFrame.setLayoutParams(current);
        });
        lineMapAnimator.start();
        lineMapExpanded = expanded;
    }

    private void focusLineMap() {
        if (lineMap == null || userLat == 0 || userLon == 0) return;
        lineMap.focusUserAndTarget(userLat, userLon,
                nearestVehicleLat, nearestVehicleLon, 24);
    }

    private void load(boolean silent) {
        if (loadInFlight) return;
        final int version = selectionVersion;
        final String stationName = this.stationName;
        final String stationId = confirmedStationId;
        final String physicalId = confirmedPhysicalId, namesakeId = confirmedNamesakeId;
        final double stopLat = this.stopLat, stopLon = this.stopLon;
        loadInFlight = true;
        silentRender = silent;
        // 记住滚动位置（自动刷新时保持）
        final int[] savedScroll = new int[]{0};
        if (scroll != null) savedScroll[0] = scroll.getScrollY();
        if (!silent) {
            progress.setVisibility(View.VISIBLE);
            refreshUpdateAge();
            updateAgeLabel = null;
            listRoot.removeAllViews();
        }
        worker.execute(() -> {
            final ChelaileDataSource.LineDetail[] currentHolder = new ChelaileDataSource.LineDetail[1];
            final ChelaileDataSource.LineDetail[] reverseHolder = new ChelaileDataSource.LineDetail[1];
            final Exception[] errorHolder = new Exception[1];
            final java.util.Map<String, List<ChelaileDataSource.Bus>> arrivals = new java.util.HashMap<>();
            boolean fromSecondary = lineId.startsWith("mg:");
            java.util.concurrent.Future<java.util.Map<String, List<ChelaileDataSource.Bus>>> arrivalRequest =
                    fromSecondary || stationName == null || stationName.isEmpty() ? null : arrivalWorker.submit(() ->
                            stationId.isEmpty()
                                    ? ChelaileDataSource.queryStationArrivals(cityId, stationName, "", stopLat, stopLon)
                                    : ChelaileDataSource.queryExactPlatformArrivals(cityId, stationId, physicalId, namesakeId));
            try {
                currentHolder[0] = ChelaileDataSource.queryLineDetail(cityId, lineId, stopLat, stopLon, stationName);
                String reverseId = currentHolder[0].reverseLineId;
                if (reverseId != null && !reverseId.isEmpty() && !reverseId.equals(lineId)) {
                    try {
                        reverseHolder[0] = ChelaileDataSource.queryLineDetail(cityId, reverseId, stopLat, stopLon, stationName);
                    } catch (Exception ignored) { }
                }
                if (stationName != null && !stationName.isEmpty()) {
                    try {
                        if (arrivalRequest != null)
                            arrivals.putAll(arrivalRequest.get(8, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (Exception ignored) { }
                    if (stationId.isEmpty() && !fromSecondary) {
                        ChelaileDataSource.fillDirectionArrivals(cityId, currentHolder[0], stationName, arrivals);
                        ChelaileDataSource.fillDirectionArrivals(cityId, reverseHolder[0], stationName, arrivals);
                    }
                    if (stationId.isEmpty() || fromSecondary) {
                        ChelaileDataSource.fillLineDetailArrivals(currentHolder[0], stationName, arrivals);
                        ChelaileDataSource.fillLineDetailArrivals(reverseHolder[0], stationName, arrivals);
                    }
                    if (!fromSecondary) {
                        MygolbsDataSource.fillMissingArrivals(cityId, currentHolder[0], stationName, stopLat, stopLon, arrivals);
                        MygolbsDataSource.fillMissingArrivals(cityId, reverseHolder[0], stationName, stopLat, stopLon, arrivals);
                    }
                }
            } catch (Exception error) {
                errorHolder[0] = error;
            } finally {
                if (arrivalRequest != null) arrivalRequest.cancel(true);
            }
            runOnUiThread(() -> {
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                loadInFlight = false;
                if (version != selectionVersion) { progress.setVisibility(View.GONE); load(true); return; }
                progress.setVisibility(View.GONE);
                if (currentHolder[0] == null) {
                    if (silent) {
                        refreshUpdateAge();
                        renderTabContent();
                        silentRender = false;
                        return;
                    }
                    refreshUpdateAge();
                    String msg = errorHolder[0] == null ? "未知错误"
                            : errorHolder[0].getClass().getSimpleName() + ": " + errorHolder[0].getMessage();
                    listRoot.addView(emptyCard("查询失败\n" + msg + "\n\n请检查网络或稍后重试"), cardParams());
                    return;
                }
                targetArrivals = arrivals;
                arrivalsUpdatedAt = System.currentTimeMillis();
                arrivalsUpdatedElapsed = android.os.SystemClock.elapsedRealtime();
                // Preserve direction metadata on transient failure, never stale vehicles.
                if (reverseHolder[0] == null && reverseDetail != null
                        && reverseDetail.lineId.equals(currentHolder[0].reverseLineId)) {
                    reverseHolder[0] = withBuses(reverseDetail, java.util.Collections.emptyList());
                }
                render(currentHolder[0], reverseHolder[0]);
                // 自动刷新时恢复滚动位置
                if (savedScroll[0] > 0 && scroll != null) {
                    scroll.post(() -> scroll.scrollTo(0, savedScroll[0]));
                }
            });
        });
    }

    // ── 车辆实时定位：每 15 秒静默刷新 ─────────────────
    private final android.os.Handler timerHandler = new android.os.Handler();
    private final Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                load(true);
            }
            timerHandler.postDelayed(this, 15_000L);
        }
    };

    private final Runnable updateAgeTask = new Runnable() {
        @Override public void run() {
            refreshUpdateAge();
            timerHandler.postDelayed(this, 1_000L);
        }
    };

    private void refreshUpdateAge() {
        if (updateAgeLabel == null) return;
        if (arrivalsUpdatedAt <= 0) {
            updateAgeLabel.setText("等待更新");
            return;
        }
        long age = arrivalsUpdatedElapsed > 0
                ? android.os.SystemClock.elapsedRealtime() - arrivalsUpdatedElapsed
                : System.currentTimeMillis() - arrivalsUpdatedAt;
        updateAgeLabel.setText(Math.max(0, age / 1_000L) + "秒前更新");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentDetail != null) load(true);
        timerHandler.removeCallbacks(timerTask);
        timerHandler.postDelayed(timerTask, 15_000L);
        timerHandler.removeCallbacks(updateAgeTask);
        timerHandler.post(updateAgeTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        timerHandler.removeCallbacks(timerTask);
        timerHandler.removeCallbacks(updateAgeTask);
        stationSequence++;
        if (stationRequest != null) stationRequest.cancel(true);
    }

    private ChelaileDataSource.LineDetail currentDetail;
    private ChelaileDataSource.LineDetail reverseDetail;
    private int activeTab = 0;

    private void render(ChelaileDataSource.LineDetail current, ChelaileDataSource.LineDetail reverse) {
        if (current == null) return;
        currentDetail = current;
        reverseDetail = reverse;
        loadRoadGeometryIfNeeded(current, reverse);
        // 每个方向的车辆按到站时间短→长排序（最近的在上）
        sortBuses(current);
        if (reverse != null) sortBuses(reverse);
        refreshUpdateAge();
        renderTabContent();
    }

    /** 渲染：双向切换tab + 当前方向内容（车辆+时间轴）。 */
    private void renderTabContent() {
        if (reverseDetail == null) activeTab = 0;
        if (quickDirectionButton != null) {
            boolean available = currentDetail != null && reverseDetail != null;
            quickDirectionButton.setEnabled(available);
            quickDirectionButton.setAlpha(available ? 1f : .45f);
        }
        boolean animateContent = !silentRender;
        if (stationStrip != null) stripOffsets.put(stationStripLine, stationStrip.getScrollX());
        listRoot.removeAllViews();
        ChelaileDataSource.LineDetail active = activeTab == 1 && reverseDetail != null
                ? reverseDetail : currentDetail;
        if (active == null) active = currentDetail;
        if (active == null) {
            updateAgeLabel = null;
            listRoot.addView(emptyCard("暂无线路信息，请点击刷新重试"), cardParams());
            silentRender = false;
            return;
        }
        updateLineMap(active);
        // 当前方向车辆区块
        View directionCard = directionBlock(active, active == currentDetail);
        listRoot.addView(directionCard, cardParams());
        if (animateContent) Ui.animateIn(directionCard, this);
        // 站点时间轴
        if (active.stations != null && !active.stations.isEmpty()) {
            View timeline = stationTimeline(active);
            listRoot.addView(timeline, cardParams());
            if (animateContent) Ui.animateIn(timeline, this);
        }
        silentRender = false;
    }

    private void selectDirection(int tabIndex) {
        if (currentDetail == null || activeTab == tabIndex
                || (tabIndex == 1 && reverseDetail == null)) return;
        activeTab = tabIndex;
        stationSequence++;
        if (stationRequest != null) stationRequest.cancel(true);
        updateFavoriteButton();
        if (lineMap != null) {
            lineMap.animate().cancel();
            lineMap.setAlpha(1f);
        }
        renderTabContent();
        focusLineMap();
    }

    private android.widget.HorizontalScrollView stationStrip;
    private String stationStripLine = "";
    private final java.util.Map<String, Integer> stripOffsets = new java.util.HashMap<>();

    /** Equal-width stations, continuous rail, with vehicles BETWEEN their previous/next stops. */
    private View stationTimeline(ChelaileDataSource.LineDetail detail) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(rounded(Ui.SURFACE, 18));
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        stationStrip = strip;
        stationStripLine = detail.lineId;
        LinearLayout row = new LinearLayout(this);
        int viewport = getResources().getDisplayMetrics().widthPixels - dp(32);
        int anchor = viewport * 13 / 16;
        row.setPadding(dp(8), 0,
                Math.max(dp(8), viewport - anchor - dp(32)), dp(14));
        int selected = -1;
        int longest = 0;
        for (ChelaileDataSource.LineStation s : detail.stations)
            longest = Math.max(longest, s.name.codePointCount(0, s.name.length()));
        int height = dp(88) + Ui.textRowHeight(this, Math.max(5, longest) * 25);
        for (int i = 0; i < detail.stations.size(); i++) {
            final int index = i;
            final ChelaileDataSource.LineStation station = detail.stations.get(i);
            final boolean current = TransitRules.samePlatformGroup(stationName, station.name);
            if (current) selected = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            View rail = new View(this) {
                private final android.graphics.Paint paint = new android.graphics.Paint(3);
                @Override protected void onDraw(android.graphics.Canvas c) {
                    float mid = getWidth() / 2f, y = dp(56);
                    paint.setColor(PRIMARY);
                    paint.setStrokeWidth(dp(5));
                    c.drawLine(index == 0 ? mid : 0, y,
                            index == detail.stations.size() - 1 ? mid : getWidth(), y, paint);
                    paint.setColor(current ? PRIMARY : Color.WHITE);
                    c.drawCircle(mid, y, dp(current ? 10 : 6), paint);
                    paint.setColor(PRIMARY);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    c.drawCircle(mid, y, dp(current ? 10 : 6), paint);
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    if (current) {
                        paint.setColor(Color.WHITE);
                        paint.setTextSize(dp(15));
                        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                        c.drawText("›", mid, y + dp(5), paint);
                    }
                    int vehicles = 0;
                    for (ChelaileDataSource.Bus b : detail.buses)
                        if (b.order == station.order && b.order > 0) vehicles++;
                    if (vehicles > 0) {
                        paint.setColor(TEXT);
                        paint.setTextSize(dp(19));
                        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                        float vehicleX = index == 0 ? mid : dp(14);
                        c.save();
                        c.scale(-1, 1, vehicleX, dp(24));
                        c.drawText("🚌", vehicleX, dp(30), paint);
                        c.restore();
                        if (vehicles > 1) {
                            paint.setTextSize(dp(10));
                            c.drawText("×" + vehicles, vehicleX + dp(20), dp(30), paint);
                        }
                    }
                }
            };
            cell.addView(rail, new android.widget.FrameLayout.LayoutParams(-1, dp(72)));
            StringBuilder vertical = new StringBuilder();
            for (int charIndex = 0; charIndex < station.name.length();) {
                int cp = station.name.codePointAt(charIndex);
                if (vertical.length() > 0) vertical.append('\n');
                if (cp == '(' || cp == '（') cp = '︵';
                else if (cp == ')' || cp == '）') cp = '︶';
                vertical.appendCodePoint(cp);
                charIndex += Character.charCount(cp);
            }
            TextView name = detailText(vertical.toString(), current ? 17 : 15,
                    current ? TEXT : MUTED, current);
            name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            name.setLineSpacing(dp(2), 1);
            android.widget.FrameLayout.LayoutParams nameLp =
                    new android.widget.FrameLayout.LayoutParams(-1, -2);
            nameLp.topMargin = dp(76);
            cell.addView(name, nameLp);
            cell.setContentDescription(station.name + "，点击查询双向到站");
            cell.setFocusable(true);
            cell.setOnClickListener(v -> selectStation(detail, station));
            Ui.pressFeedback(cell);
            row.addView(cell, new LinearLayout.LayoutParams(dp(64), height));
        }
        strip.addView(row);
        block.addView(strip);
        Integer saved = stripOffsets.get(detail.lineId);
        final int offset = saved == null ? Math.max(0, dp(selected * 64 + 40) - anchor) : saved;
        strip.post(() -> strip.scrollTo(offset, 0));
        return block;
    }

    private TextView detailText(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        Ui.type(view, size, bold);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private void selectStation(ChelaileDataSource.LineDetail detail, ChelaileDataSource.LineStation station) {
        selectionVersion++;
        stationSequence++;
        if (stationRequest != null) stationRequest.cancel(true);
        if (stationDialog != null) stationDialog.dismiss();
        stationName = station.name;
        confirmedStationId = "";
        arrivalsUpdatedAt = 0;
        arrivalsUpdatedElapsed = 0;
        stopLat = station.lat;
        stopLon = station.lon;
        targetArrivals = new java.util.HashMap<>();
        stationStrip = null;
        stripOffsets.remove(detail.lineId);
        silentRender = true;
        renderTabContent();
        refreshUpdateAge();
        load(true);
    }

    private void updateLineMap(ChelaileDataSource.LineDetail detail) {
        if (lineMap == null || detail == null) return;
        java.util.List<double[]> route = new java.util.ArrayList<>();
        java.util.List<double[]> stationDots = new java.util.ArrayList<>();
        java.util.List<NearbyStation> markers = new java.util.ArrayList<>();
        ChelaileDataSource.GaodeLine roadLine = roadLines.get(detail.lineId);
        if (roadLine != null && roadLine.path != null && roadLine.path.size() > 1) {
            route.addAll(roadLine.path);
        }
        if (detail.stations != null) {
            for (int index = 0; index < detail.stations.size(); index++) {
                ChelaileDataSource.LineStation station = detail.stations.get(index);
                double[] wgs = roadStopPoint(roadLine, station.name);
                // 地图层只接受高德线路自带的站点坐标，不采用车来了站点坐标。
                if (wgs == null) continue;
                stationDots.add(wgs);
                boolean endpoint = index == 0 || index == detail.stations.size() - 1;
                boolean selected = stationName != null && stationName.equals(station.name);
                if (endpoint || selected) {
                    markers.add(new NearbyStation(station.name, wgs[0], wgs[1],
                            0, false, 1, null));
                }
            }
        }
        java.util.List<double[]> vehicles = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        if (detail.buses != null) {
            for (ChelaileDataSource.Bus bus : detail.buses) {
                if (bus.lat == 0 || bus.lon == 0) continue;
                vehicles.add(new double[]{bus.lat, bus.lon});
                labels.add(bus.licence == null ? "" : bus.licence);
            }
        }
        lineMap.setStations(markers);
        lineMap.setLineRoute(route, stationDots, vehicles, labels, PRIMARY_DARK);
        // 必须按当前方向重新计算；无车时清零，不能沿用另一方向的最近车辆。
        nearestVehicleLat = 0;
        nearestVehicleLon = 0;
        if (userLat != 0 && userLon != 0) {
            double nearestKm = Double.MAX_VALUE;
            for (double[] vehicle : vehicles) {
                double distance = GeoUtils.distanceKm(userLat, userLon, vehicle[0], vehicle[1]);
                if (distance < nearestKm) {
                    nearestKm = distance;
                    nearestVehicleLat = vehicle[0];
                    nearestVehicleLon = vehicle[1];
                }
            }
            if (!mapFocusedInitially) {
                mapFocusedInitially = true;
                lineMap.focusUserAndTarget(userLat, userLon,
                        nearestVehicleLat, nearestVehicleLon, 24);
            }
        }
    }

    private double[] roadStopPoint(ChelaileDataSource.GaodeLine line, String station) {
        if (line == null || line.stops == null || line.stopPoints == null) return null;
        String target = normalizeStationForMatch(station);
        int count = Math.min(line.stops.size(), line.stopPoints.size());
        for (int i = 0; i < count; i++) {
            if (target.equals(normalizeStationForMatch(line.stops.get(i)))) {
                return line.stopPoints.get(i);
            }
        }
        return null;
    }

    /** 异步取得真正沿道路的公交 polyline；失败时仍保留实时车辆和站序降级线。 */
    private void loadRoadGeometryIfNeeded(ChelaileDataSource.LineDetail current,
                                          ChelaileDataSource.LineDetail reverse) {
        if (roadSearchStarted) return;
        roadSearchStarted = true;
        final String cityName = amapCityCodeForId(cityId);
        roadWorker.execute(() -> {
            try {
                List<ChelaileDataSource.GaodeLine> candidates =
                        ChelaileDataSource.gaodeSearchLines(cityName, lineName);
                ChelaileDataSource.GaodeLine a = bestRoadLine(candidates, current);
                ChelaileDataSource.GaodeLine b = bestRoadLine(candidates, reverse);
                if (a != null) roadLines.put(current.lineId, a);
                if (b != null && reverse != null) roadLines.put(reverse.lineId, b);
                runOnUiThread(() -> {
                    if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    ChelaileDataSource.LineDetail active = activeTab == 1 && reverseDetail != null
                            ? reverseDetail : currentDetail;
                    updateLineMap(active);
                    focusLineMap();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private ChelaileDataSource.GaodeLine bestRoadLine(List<ChelaileDataSource.GaodeLine> lines,
                                                       ChelaileDataSource.LineDetail detail) {
        if (detail == null || lines == null) return null;
        ChelaileDataSource.GaodeLine best = null;
        int bestScore = Integer.MIN_VALUE;
        String start = normalizeStationForMatch(detail.startSn);
        String end = normalizeStationForMatch(detail.endSn);
        for (ChelaileDataSource.GaodeLine line : lines) {
            if (line.path == null || line.path.size() < 2) continue;
            int score = 0;
            String candidateStart = normalizeStationForMatch(line.startSn);
            String candidateEnd = normalizeStationForMatch(line.endSn);
            if (stationNamesMatch(start, candidateStart)) score += 80;
            if (stationNamesMatch(end, candidateEnd)) score += 80;
            if (stationNamesMatch(start, candidateEnd)) score -= 50;
            if (stationNamesMatch(end, candidateStart)) score -= 50;
            int startIndex = stationIndexInGaode(line, start);
            int endIndex = stationIndexInGaode(line, end);
            if (startIndex >= 0 && endIndex >= 0) {
                score += startIndex < endIndex ? 100 : -80;
            }
            int overlap = stationOverlap(line, detail);
            score += Math.min(90, overlap * 10);
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return bestScore >= 25 ? best : null;
    }

    private int stationIndexInGaode(ChelaileDataSource.GaodeLine line, String name) {
        if (line == null || line.stops == null) return -1;
        for (int i = 0; i < line.stops.size(); i++) {
            if (stationNamesMatch(name, normalizeStationForMatch(line.stops.get(i)))) return i;
        }
        return -1;
    }

    private int stationOverlap(ChelaileDataSource.GaodeLine line,
                               ChelaileDataSource.LineDetail detail) {
        if (line.stops == null || detail.stations == null) return 0;
        int count = 0;
        for (ChelaileDataSource.LineStation station : detail.stations) {
            String name = normalizeStationForMatch(station.name);
            if (stationIndexInGaode(line, name) >= 0) count++;
        }
        return count;
    }

    private boolean stationNamesMatch(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || (a.length() >= 3 && b.contains(a))
                || (b.length() >= 3 && a.contains(b));
    }

    private String amapCityCodeForId(String id) {
        if ("040".equals(id)) return "020";
        if ("014".equals(id)) return "0755";
        if ("019".equals(id)) return "0757";
        if ("008".equals(id)) return "0769";
        if ("021".equals(id)) return "0760";
        if ("242".equals(id)) return "0756";
        if ("134".equals(id)) return "0750";
        if ("016".equals(id)) return "0752";
        if ("521".equals(id)) return "0758";
        return "0757";
    }

    /** 按所点站坐标重新请求双向 ETA，并用站序过滤已经驶过该站的车辆。 */
    private void loadBusStationSheet(ChelaileDataSource.LineDetail detail,
                                     ChelaileDataSource.LineStation station) {
        final int sequence = ++stationSequence;
        if (stationRequest != null) stationRequest.cancel(true);
        if (stationDialog != null) stationDialog.dismiss();
        refreshUpdateAge();
        final double[] roadPoint = roadStopPoint(roadLines.get(detail.lineId), station.name);
        final double lat = roadPoint == null ? station.lat : roadPoint[0];
        final double lon = roadPoint == null ? station.lon : roadPoint[1];
        final ChelaileDataSource.LineDetail knownCurrent = currentDetail;
        final ChelaileDataSource.LineDetail knownReverse = reverseDetail;
        stationRequest = stationWorker.submit(() -> {
            ChelaileDataSource.LineDetail first = null;
            ChelaileDataSource.LineDetail second = null;
            try {
                String reverseId = detail.reverseLineId;
                if (detail.lineId.startsWith("mg:")) {
                    first = ChelaileDataSource.queryLineDetail(cityId, detail.lineId, lat, lon, station.name);
                    if (reverseId != null && !reverseId.isEmpty()) {
                        second = ChelaileDataSource.queryLineDetail(cityId, reverseId, lat, lon, station.name);
                    }
                } else {
                    java.util.Map<String, List<ChelaileDataSource.Bus>> arrivals = new java.util.HashMap<>();
                    try {
                        arrivals.putAll(ChelaileDataSource.queryStationArrivals(
                                cityId, station.name, station.sId, lat, lon));
                    } catch (Exception unavailable) {
                        if (Thread.currentThread().isInterrupted()) return;
                    }
                    ChelaileDataSource.fillDirectionArrivals(cityId, knownCurrent, station.name, arrivals);
                    ChelaileDataSource.fillDirectionArrivals(cityId, knownReverse, station.name, arrivals);
                    List<ChelaileDataSource.Bus> firstBuses = arrivals.get(detail.lineId);
                    if (firstBuses != null) first = withBuses(detail, firstBuses);
                    if ((reverseId == null || reverseId.isEmpty()) && knownCurrent != null
                            && knownReverse != null) {
                        reverseId = detail.lineId.equals(knownCurrent.lineId)
                                ? knownReverse.lineId : knownCurrent.lineId;
                    }
                    if (reverseId != null && !reverseId.isEmpty()
                            && !reverseId.equals(detail.lineId)) {
                        List<ChelaileDataSource.Bus> secondBuses = arrivals.get(reverseId);
                        if (secondBuses != null) {
                            ChelaileDataSource.LineDetail reverse = knownCurrent != null
                                    && reverseId.equals(knownCurrent.lineId) ? knownCurrent :
                                    knownReverse != null && reverseId.equals(knownReverse.lineId)
                                            ? knownReverse : null;
                            if (reverse == null) reverse = ChelaileDataSource.queryLineDetail(
                                    cityId, reverseId, lat, lon);
                            second = withBuses(reverse, secondBuses);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            // 请求失败时不再回退到“进入页面时目标站”的 ETA，否则会把其他站的时间
            // 错标成所点站时间。
            final ChelaileDataSource.LineDetail firstResult = first;
            final ChelaileDataSource.LineDetail secondResult = second;
            runOnUiThread(() -> {
                if (sequence != stationSequence) return;
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                showBusStationSheet(station, firstResult, secondResult);
                refreshUpdateAge();
            });
        });
    }

    private static ChelaileDataSource.LineDetail withBuses(ChelaileDataSource.LineDetail detail,
                                                          List<ChelaileDataSource.Bus> buses) {
        return new ChelaileDataSource.LineDetail(detail.lineId, detail.name, detail.direction,
                detail.startSn, detail.endSn, detail.firstTime, detail.lastTime, detail.price,
                buses, detail.stations, detail.reverseLineId, detail.serviceWindows);
    }

    /** 公交站点双向详情底部卡。 */
    private void showBusStationSheet(ChelaileDataSource.LineStation station,
                                     ChelaileDataSource.LineDetail first,
                                     ChelaileDataSource.LineDetail second) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        stationDialog = dialog;
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(12), dp(18), dp(24));
        sheet.setBackground(rounded(Ui.SURFACE, 24));

        View handle = new View(this);
        handle.setBackground(rounded(Color.rgb(215, 222, 230), 3));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(42), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(16);
        sheet.addView(handle, hlp);

        TextView title = new TextView(this);
        title.setText(station.name);
        title.setTextSize(24);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        sheet.addView(title, matchWrap());

        TextView route = new TextView(this);
        route.setText(Ui.updatedAt(arrivalsUpdatedAt));
        route.setTextSize(14);
        route.setTextColor(PRIMARY_DARK);
        route.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        route.setPadding(0, dp(5), 0, dp(12));
        sheet.addView(route, matchWrap());

        if (first != null) {
            sheet.addView(stationDirectionCard(first, true, station.name, station.sId), cardParams());
        } else {
            sheet.addView(stationUnavailableCard("暂无到站信息"), cardParams());
        }
        if (second != null) {
            sheet.addView(stationDirectionCard(second, false, station.name, station.sId), cardParams());
        } else {
            sheet.addView(stationUnavailableCard("暂无到站信息"), cardParams());
        }

        TextView close = new TextView(this);
        close.setText("完成");
        close.setTextSize(15);
        close.setTextColor(Ui.ON_PRIMARY);
        close.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(PRIMARY_DARK, 14));
        close.setOnClickListener(v -> dialog.dismiss());
        Ui.pressFeedback(close);
        sheet.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = .46f;
            attrs.gravity = Gravity.BOTTOM;
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(attrs);
        }
        dialog.show();
        sheet.setAlpha(0f);
        sheet.setTranslationY(dp(20));
        sheet.animate().alpha(1f).translationY(0f).setDuration(Ui.duration(this, 200))
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
    }

    private View stationUnavailableCard(String value) {
        TextView unavailable = new TextView(this);
        unavailable.setText(value);
        unavailable.setTextSize(14);
        unavailable.setTextColor(MUTED);
        unavailable.setGravity(Gravity.CENTER_VERTICAL);
        unavailable.setPadding(dp(15), dp(14), dp(15), dp(14));
        unavailable.setBackground(rounded(Ui.RAISED, 16));
        return unavailable;
    }

    private View stationDirectionCard(ChelaileDataSource.LineDetail detail, boolean forward,
                                      String targetName, String targetSid) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(rounded(forward ? LIGHT_BLUE : Ui.RAISED, 16));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView direction = new TextView(this);
        direction.setText((forward ? "▸ " : "◂ ") + detail.endSn);
        direction.setTextSize(16);
        direction.setTextColor(TEXT);
        direction.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        direction.setSingleLine(true);
        direction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(direction, matchWrap());

        int targetOrder = stationOrderFor(detail, targetName, targetSid);
        ChelaileDataSource.Bus nearest = null;
        if (detail.buses != null) {
            for (ChelaileDataSource.Bus bus : detail.buses) {
                if (!TransitRules.canArrive(bus.travelTime, bus.order, targetOrder)) continue;
                // 车辆站序已经超过目标站时，接口偶尔仍保留上一轮 ETA；必须排除。
                if (targetOrder > 0 && bus.order > 0 && bus.order > targetOrder) continue;
                if (nearest == null || bus.travelTime < nearest.travelTime) nearest = bus;
            }
        }
        TextView vehicle = new TextView(this);
        vehicle.setText(nearest == null ? "暂无车辆"
                : (nearest.licence == null || nearest.licence.isEmpty()
                ? "最近车辆" : nearest.licence));
        vehicle.setTextSize(12);
        vehicle.setTextColor(MUTED);
        vehicle.setPadding(0, dp(4), 0, 0);
        text.addView(vehicle, matchWrap());
        card.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView eta = new TextView(this);
        eta.setText(nearest == null ? "暂无时间" : TransitRules.etaLabel(nearest.travelTime));
        eta.setTextSize(nearest == null ? 14 : 21);
        eta.setTextColor(nearest == null ? MUTED : ACCENT);
        eta.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eta.setSingleLine(true);
        eta.setIncludeFontPadding(false);
        eta.setPadding(dp(12), 0, 0, 0);
        card.addView(eta);
        return card;
    }

    /** 优先用稳定的站点 ID 定位，反向站 ID 不同则退回规范化站名。 */
    private int stationOrderFor(ChelaileDataSource.LineDetail detail,
                                String targetName, String targetSid) {
        if (detail == null || detail.stations == null) return -1;
        if (targetSid != null && !targetSid.isEmpty()) {
            for (ChelaileDataSource.LineStation station : detail.stations) {
                if (targetSid.equals(station.sId)) return station.order;
            }
        }
        String normalizedTarget = normalizeStationForMatch(targetName);
        for (ChelaileDataSource.LineStation station : detail.stations) {
            if (normalizeStationForMatch(station.name).equals(normalizedTarget)) return station.order;
        }
        return -1;
    }

    private static String normalizeStationForMatch(String value) {
        if (value == null) return "";
        return TransitRules.stationKey(value);
    }

    private static void sortBuses(ChelaileDataSource.LineDetail detail) {
        if (detail.buses == null) return;
        java.util.Collections.sort(detail.buses, (a, b) -> {
            int ta = a.travelTime < 0 ? Integer.MAX_VALUE : a.travelTime;
            int tb = b.travelTime < 0 ? Integer.MAX_VALUE : b.travelTime;
            return Integer.compare(ta, tb);
        });
    }

    /** 当前查询站在该方向线路上的序号，找不到返回 -1。 */
    private int targetOrderFor(ChelaileDataSource.LineDetail detail) {
        if (detail.stations == null) return -1;
        for (ChelaileDataSource.LineStation st : detail.stations) {
            if (stationName != null && stationName.equals(st.name)) {
                return st.order;
            }
        }
        return -1;
    }

    /** 已核对到当前查询站的车辆。 */
    private List<ChelaileDataSource.Bus> arrivalBuses(ChelaileDataSource.LineDetail detail) {
        if (stationName == null || stationName.isEmpty()) return java.util.Collections.emptyList();
        List<ChelaileDataSource.Bus> verified = targetArrivals.get(detail.lineId);
        return verified == null ? java.util.Collections.emptyList() : verified;
    }

    private View directionBlock(ChelaileDataSource.LineDetail detail, boolean primary) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(16), dp(16), dp(16), dp(16));
        block.setBackground(rounded(Ui.SURFACE, 18));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        LinearLayout routeInfo = new LinearLayout(this);
        routeInfo.setOrientation(LinearLayout.VERTICAL);
        TextView title = detailText(detail.name, Ui.TITLE, TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        routeInfo.addView(title);
        TextView route = detailText(detail.startSn + " → " + detail.endSn, 15, TEXT, false);
        route.setPadding(0, dp(8), 0, 0);
        routeInfo.addView(route);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -2, 1f);
        infoLp.rightMargin = dp(12);
        head.addView(routeInfo, infoLp);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        Button swap = quickAction("换向", "swap", false);
        swap.setContentDescription("切换公交线路方向");
        swap.setEnabled(currentDetail != null && reverseDetail != null);
        swap.setAlpha(swap.isEnabled() ? 1f : .45f);
        swap.setOnClickListener(v -> selectDirection(1 - activeTab));
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(-1, Ui.textRowHeight(this, 48));
        swapLp.bottomMargin = dp(8);
        actions.addView(swap, swapLp);
        head.addView(actions, new LinearLayout.LayoutParams(dp(104), -2));
        block.addView(head);
        String fare = ChelaileDataSource.formatFare(detail.price);
        boolean splitService = detail.serviceWindows.size() > 1;
        String hours = splitService ? "运营时段 " + android.text.TextUtils.join("  /  ", detail.serviceWindows)
                : "首班 " + (detail.firstTime.isEmpty() ? "未知" : detail.firstTime)
                + "  ·  末班 " + (detail.lastTime.isEmpty() ? "未知" : detail.lastTime);
        TextView meta = detailText(hours
                + (detail.price.isEmpty() ? "" : "  ·  " + fare + (fare.contains("元") ? "" : "元")),
                13, MUTED, false);
        meta.setSingleLine(!splitService);
        if (!splitService) meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!splitService && android.os.Build.VERSION.SDK_INT >= 26)
            meta.setAutoSizeTextTypeUniformWithConfiguration(12, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        meta.setPadding(0, dp(8), 0, 0);
        block.addView(meta);
        LinearLayout stationRow = new LinearLayout(this);
        stationRow.setGravity(Gravity.CENTER_VERTICAL);
        stationRow.setPadding(0, dp(16), 0, dp(12));
        TextView stop = detailText(stationName == null || stationName.isEmpty()
                ? "请选择站点查看到站时间" : stationName, 19, TEXT, true);
        stop.setMaxLines(3);
        stop.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, -2, 1f);
        stopLp.rightMargin = dp(12);
        stationRow.addView(stop, stopLp);
        updateAgeLabel = detailText("", 12, MUTED, false);
        updateAgeLabel.setSingleLine(true);
        updateAgeLabel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        updateAgeLabel.setMinWidth(dp(80));
        stationRow.addView(updateAgeLabel, new LinearLayout.LayoutParams(-2, -2));
        block.addView(stationRow);
        refreshUpdateAge();
        android.widget.HorizontalScrollView cards = new android.widget.HorizontalScrollView(this);
        cards.setHorizontalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        List<ChelaileDataSource.Bus> arrivals = new java.util.ArrayList<>(arrivalBuses(detail));
        java.util.Collections.sort(arrivals, (x, y) -> Integer.compare(
                x.travelTime < 0 ? Integer.MAX_VALUE : x.travelTime,
                y.travelTime < 0 ? Integer.MAX_VALUE : y.travelTime));
        int shown = 0;
        for (ChelaileDataSource.Bus bus : arrivals) {
            if (shown >= 6) break;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(14), dp(12), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(shown == 0 ? Ui.LIGHT_BLUE : Ui.RAISED);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), shown == 0 ? PRIMARY : Ui.OUTLINE);
            card.setBackground(bg);
            card.addView(detailText(bus.travelTime < 0 ? "暂无时间" : TransitRules.etaLabel(bus.travelTime),
                    bus.travelTime < 0 ? 17 : 21, PRIMARY, true));
            String distanceText = bus.distanceToDest < 0 ? "距离暂无数据"
                    : bus.distanceToDest < 1000 ? "距站 " + bus.distanceToDest + " 米"
                    : String.format(java.util.Locale.CHINA, "距站 %.1f 公里", bus.distanceToDest / 1000.0);
            TextView distance = detailText((bus.stopsAway >= 0 ? bus.stopsAway + "站 · " : "")
                    + distanceText, 13, MUTED, false);
            distance.setPadding(0, dp(9), 0, 0);
            card.addView(distance);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(136), -2);
            lp.rightMargin = dp(8);
            items.addView(card, lp);
            shown++;
        }
        if (shown == 0) items.addView(detailText("暂无到站信息", 14, MUTED, false));
        cards.addView(items);
        block.addView(cards);
        return block;
    }

    private View busRow(ChelaileDataSource.Bus bus, int targetOrder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(LIGHT_BLUE, 12));
        row.setLayoutParams(cardParams());

        TextView eta = new TextView(this);
        String etaText;
        if (bus.travelTime < 0) {
            etaText = "待定";
        } else if (bus.travelTime < 60) {
            etaText = "即将到站";
        } else {
            etaText = TransitRules.etaLabel(bus.travelTime);
        }
        eta.setText(etaText);
        eta.setTextSize(27);
        eta.setTextColor(ACCENT);
        eta.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eta.setIncludeFontPadding(false);
        eta.setSingleLine(true);
        eta.setPadding(0, 0, dp(14), 0);
        row.addView(eta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String distText = "";
        if (bus.order > 0 && targetOrder > 0) {
            // order 是下一站。车辆正在驶向目标站时仍应显示“还有1站”，
            // 不能因为序号相等就误报已经到站。
            int remaining = targetOrder - bus.order + 1;
            if (remaining > 1) {
                distText = "还有 " + remaining + " 站";
            } else if (remaining == 1) {
                distText = TransitRules.confirmedArrival(bus.travelTime, bus.distanceToDest)
                        ? "已到本站" : "正在驶向本站";
            } else {
                distText = "已过站";
            }
        }
        if (distText.isEmpty() && bus.distanceToDest > 0) {
            distText = "距站 " + bus.distanceToDest + " 米";
        }
        StringBuilder infoText = new StringBuilder();
        if (bus.recommTip != null && !bus.recommTip.isEmpty()) {
            infoText.append("预计 ").append(bus.recommTip).append(" 到站");
        }
        if (!distText.isEmpty()) {
            if (infoText.length() > 0) infoText.append(" · ");
            infoText.append(distText);
        }
        if (bus.licence != null && !bus.licence.isEmpty()) {
            if (infoText.length() > 0) infoText.append(" · ");
            infoText.append(bus.licence);
        }
        TextView info = new TextView(this);
        info.setText(infoText.length() == 0 ? "车辆" : infoText.toString());
        info.setTextSize(13);
        info.setTextColor(MUTED);
        info.setSingleLine(true);
        info.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View emptyCard(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextSize(14);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(38), dp(20), dp(38));
        empty.setBackground(rounded(Ui.SURFACE, 16));
        empty.setLayoutParams(cardParams());
        return empty;
    }

    private Drawable rounded(int color, int radiusDp) {
        if (color == Color.WHITE || color == 0xFFFFFFFF) {
            return Ui.edgeCard(this, PRIMARY, radiusDp);
        }
        return Ui.rounded(this, color, radiusDp);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(9));
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String lineFavoriteKey() {
        String activeId = activeTab == 1 && reverseDetail != null ? reverseDetail.lineId : lineId;
        return cityId + "|" + (activeId == null ? "" : activeId);
    }

    private boolean isLineFavorite() {
        java.util.Set<String> stored = getSharedPreferences("prdbus", MODE_PRIVATE)
                .getStringSet("favorite_bus_lines", java.util.Collections.<String>emptySet());
        return stored.contains(lineFavoriteKey());
    }

    private void updateFavoriteButton() {
        if (favoriteButton == null) return;
        boolean saved = isLineFavorite();
        favoriteButton.setText("");
        Ui.icon(favoriteButton, saved ? "star-filled" : "star", PRIMARY);
        favoriteButton.setCompoundDrawablePadding(0);
        favoriteButton.setPadding(dp(14), 0, 0, 0);
        favoriteButton.setContentDescription(saved ? "取消收藏线路" : "收藏线路");
    }

    private void toggleLineFavorite() {
        android.content.SharedPreferences prefs = getSharedPreferences("prdbus", MODE_PRIVATE);
        java.util.Set<String> lines = new java.util.HashSet<>(prefs.getStringSet(
                "favorite_bus_lines", java.util.Collections.<String>emptySet()));
        String key = lineFavoriteKey();
        String metadataKey = "favorite_bus_line_" + key.hashCode();
        String targetKey = "favorite_bus_target_" + key;
        ChelaileDataSource.LineDetail active = activeTab == 1 ? reverseDetail : currentDetail;
        ChelaileDataSource.LineStation target = favoriteBoardingStation(active);
        boolean bindOldFavorite = lines.contains(key) && !prefs.contains(targetKey) && target != null;
        boolean added;
        if (lines.contains(key) && !bindOldFavorite) {
            lines.remove(key);
            added = false;
        } else {
            lines.add(key);
            added = true;
        }
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putStringSet("favorite_bus_lines", lines);
        if (added) {
            String activeId = activeTab == 1 && reverseDetail != null ? reverseDetail.lineId : lineId;
            editor.putString(metadataKey, cityId + "|" + activeId + "|" + lineName);
            if (target != null) editor.putString(targetKey, target.sId + "|" + target.name + "|"
                    + target.physicalStId + "|" + target.namesakeStId + "|" + active.endSn
                    + "|" + target.lat + "|" + target.lon);
        } else {
            editor.remove(metadataKey);
            editor.remove(targetKey);
        }
        editor.apply();
        updateFavoriteButton();
        refreshUpdateAge();
    }

    private ChelaileDataSource.LineStation favoriteBoardingStation(ChelaileDataSource.LineDetail active) {
        if (active == null || active.stations == null || stationName == null || stationName.isEmpty()) return null;
        ChelaileDataSource.LineStation match = null;
        for (ChelaileDataSource.LineStation station : active.stations) {
            if (station.sId == null || station.sId.isEmpty()) continue;
            if (!confirmedStationId.isEmpty()) {
                if (confirmedStationId.equals(station.sId)) return station;
            } else if (stationName.equals(station.name)) {
                if (match != null) return null;
                match = station;
            }
        }
        return match;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerTask);
        timerHandler.removeCallbacks(updateAgeTask);
        worker.shutdownNow();
        arrivalWorker.shutdownNow();
        roadWorker.shutdownNow();
        stationSequence++;
        stationWorker.shutdownNow();
        if (stationDialog != null) stationDialog.dismiss();
        if (lineMapAnimator != null) lineMapAnimator.cancel();
    }
}
