package com.holi.prdbus;

import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 地铁线路站点时间轴页：双向（去程/回程）切换 + 时刻表预测列车位置。 */
public final class MetroLineActivity extends ThemedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int loadSequence;
    private boolean resumed;
    private long observedDataAt;
    private final Runnable dataChanged = () -> {
        if (!resumed || this.metroLine == null || isFinishing()) return;
        long version = MetroRepository.cityDataAt(MetroDataSource.cityOfLine(this.metroLine));
        if (version != observedDataAt) load();
    };
    private TextView lineTitle;
    private LinearLayout serviceTabs;
    private final android.os.Handler predictionHandler = new android.os.Handler();
    private android.app.Dialog stationSheet;
    private Runnable updateStationSheet;

    private String lineName = "";
    private String lineColour = "#8FB0C9";
    private boolean ringLine = false;
    private MetroDataSource.MetroLine metroLine;
    private String firstStopName = "";
    private String lastStopName = "";
    private double lineSpeed = 0.55;
    private java.util.Map<String, String> lineColours = new java.util.HashMap<>();
    private List<Double> predictedTrains = java.util.Collections.emptyList();
    private List<MetroDataSource.Station> rawStations = new ArrayList<>();
    private List<MetroDataSource.Station> lastStations = new ArrayList<>();
    private final java.util.List<View> stationRows = new ArrayList<>();

    /** 0 = 去程（正向），1 = 回程（反向）。 */
    private int direction = 0;

    private final Runnable predictionTask = new Runnable() {
        @Override
        public void run() {
            if (lastStations.size() > 1) {
                refreshPrediction();
                predictionHandler.postDelayed(this, 15_000L);
            }
        }
    };

    private LinearLayout listRoot;
    private ScrollView stationScroll;
    private String pendingStation;
    private boolean openPendingStation;
    private android.widget.FrameLayout trainOverlay;
    private View solidMetroRail;
    private View progress;
    private LinearLayout headerBox;
    private LinearLayout directionTabs;
    private Button tabForward;
    private Button tabReverse;
    private Button refreshFab;
    private boolean animateNextRender = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        lineName = intent.getStringExtra("lineName");
        lineColour = intent.getStringExtra("colour");
        pendingStation = intent.getStringExtra("highlightStation");
        openPendingStation = intent.getBooleanExtra("openStationSheet", false) && state == null;
        if (lineName == null) lineName = "";
        if (lineColour == null || lineColour.isEmpty()) lineColour = "#8FB0C9";
        // 换乘规划等入口传入方向（0=去程/外环，1=回程/内环），默认去程
        int dirHint = intent.getIntExtra("direction", -1);
        if (dirHint == 0 || dirHint == 1) direction = dirHint;
        if (state != null) { lineName = state.getString("metroLine", lineName); direction = state.getInt("metroDirection", direction); }

        Ui.edgeToEdge(this);
        buildUi();
        Ui.insets(this, headerBox, 16, 16, 8, 12);
        load();
    }

    private int lineColor() {
        try {
            return Color.parseColor(lineColour);
        } catch (Exception e) {
            return Ui.PRIMARY;
        }
    }

    private void buildUi() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.TRANSPARENT);

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 12));
        headerBox.setBackgroundColor(Ui.SURFACE);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("返回");
        Ui.icon(back, "back", Ui.TEXT);
        Ui.target(back);
        back.setTextSize(16);
        back.setTextColor(Ui.PRIMARY);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        back.setPadding(0, 0, Ui.dp(this, 12), 0);
        back.setOnClickListener(v -> {
            finish();
            Ui.transitionBack(this);
        });
        top.addView(back);
        TextView lineNameText = new TextView(this);
        lineTitle = lineNameText;
        lineNameText.setText(lineName.isEmpty() ? "地铁线路" : lineName);
        lineNameText.setTextSize(22);
        lineNameText.setTextColor(Ui.TEXT);
        lineNameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(lineNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refreshFab = Ui.button(this, "", Ui.SURFACE, Ui.PRIMARY);
        Ui.icon(refreshFab, "refresh", Ui.PRIMARY);
        refreshFab.setContentDescription("刷新地铁时刻表估算");
        refreshFab.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        refreshFab.setCompoundDrawablePadding(0);
        refreshFab.setOnClickListener(v -> {
            if (lastStations.size() > 1) refreshPrediction();
            else load();
        });
        top.addView(refreshFab, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        top.addView(RideCode.icon(this), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        headerBox.addView(top);

        serviceTabs = new LinearLayout(this);
        serviceTabs.setPadding(0, Ui.dp(this, 8), 0, 0);
        serviceTabs.setVisibility(View.GONE);
        headerBox.addView(serviceTabs);

        // 双向切换（去程 / 回程）
        directionTabs = new LinearLayout(this);
        directionTabs.setOrientation(LinearLayout.HORIZONTAL);
        directionTabs.setPadding(0, Ui.dp(this, 8), 0, 0);
        tabForward = directionTab("▸ 去程");
        tabReverse = directionTab("◂ 回程");
        directionTabs.addView(tabForward);
        directionTabs.addView(tabReverse);
        ((LinearLayout.LayoutParams)tabReverse.getLayoutParams()).rightMargin = 0;
        headerBox.addView(directionTabs);

        page.addView(headerBox, Ui.matchWrap());
        progress = Ui.loadingDots(this);
        progress.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        stationScroll = scroll;
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        // 底部留白，避免被右下角刷新按钮遮挡
        listRoot.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 24));
        stage.addView(listRoot, Ui.matchWrap());
        // 一根位于各站点行背后的连续实线，消除 MATCH_PARENT 二次测量产生的行间断口。
        solidMetroRail = new View(this);
        solidMetroRail.setBackgroundColor(lineColor());
        solidMetroRail.setVisibility(View.INVISIBLE);
        stage.addView(solidMetroRail, 0, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 4), 1));
        // 列车图标覆盖层：按小数站间位置精确摆放在两站之间
        trainOverlay = new android.widget.FrameLayout(this);
        trainOverlay.setClickable(false);
        trainOverlay.setFocusable(false);
        trainOverlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        trainOverlay.setClipChildren(false);
        trainOverlay.setClipToPadding(false);
        stage.addView(trainOverlay);
        scroll.addView(stage, Ui.matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        progress = Ui.centerLoader(this, progress);
    }

    private Button directionTab(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(Ui.TEXT);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setStateListAnimator(null);
        button.setBackground(Ui.rounded(this, Ui.RAISED, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.textRowHeight(this, 52), 1f);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        button.setLayoutParams(lp);
        Ui.pressFeedback(button);
        return button;
    }

    private void updateDirectionTabs() {
        boolean forward = direction == 0;
        tabForward.setText(directionLabel(0));
        tabReverse.setText(directionLabel(1));
        tabForward.setContentDescription(directionLabel(0)); tabReverse.setContentDescription(directionLabel(1));
        tabForward.setBackground(Ui.rounded(this, forward ? lineColor() : Ui.RAISED, 10));
        tabForward.setTextColor(forward ? ReadableColors.foreground(lineColor()) : Ui.TEXT);
        tabReverse.setBackground(Ui.rounded(this, forward ? Ui.RAISED : lineColor(), 10));
        tabReverse.setTextColor(forward ? Ui.TEXT : ReadableColors.foreground(lineColor()));
    }

    /** 方向标签：普通线路显示真实端点（如 西塱-广州东站），环线显示去程/回程。 */
    private String directionLabel(int dir) {
        if (ringLine) return dir == 0 ? "◐ 外环" : "◑ 内环";
        if (firstStopName.isEmpty() || lastStopName.isEmpty()
                || firstStopName.equals(lastStopName)) {
            return dir == 0 ? "▸ 去程" : "◂ 回程";
        }
        return dir == 0 ? "往 " + lastStopName : "往 " + firstStopName;
    }

    private void bindDirectionTabs() {
        tabForward.setOnClickListener(v -> {
            if (direction != 0) {
                direction = 0;
                animateNextRender = true;
                updateDirectionTabs();
                renderStations();
            }
        });
        tabReverse.setOnClickListener(v -> {
            if (direction != 1) {
                direction = 1;
                animateNextRender = true;
                updateDirectionTabs();
                renderStations();
            }
        });
        updateDirectionTabs();
    }

    private void load() {
        final int sequence = ++loadSequence;
        final String requested = lineName;
        predictionHandler.removeCallbacks(predictionTask);
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                MetroDataSource source = new MetroDataSource(this);
                List<MetroDataSource.MetroLine> all = source.queryLines();
                MetroDataSource.MetroLine local = MetroPatterns.find(all, requested);
                runOnUiThread(() -> {
                    if (!currentLoad(sequence)) return;
                    if (local == null) {
                        progress.setVisibility(View.GONE);
                        showLoadError("暂无对应线路");
                        return;
                    }
                    showLine(local, all);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!currentLoad(sequence)) return;
                    progress.setVisibility(View.GONE);
                    showLoadError("线路加载失败，请点击顶部刷新重试");
                });
            }
        });
    }

    private boolean currentLoad(int sequence) {
        return sequence == loadSequence && !isFinishing()
                && !(android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed());
    }

    private void showLoadError(String message) {
        if (!lastStations.isEmpty()) return;
        listRoot.removeAllViews();
        listRoot.addView(Ui.emptyCard(this, message), Ui.cardParams(this));
    }

    private void showLine(MetroDataSource.MetroLine target, List<MetroDataSource.MetroLine> all) {
        progress.setVisibility(View.GONE);
        lineColours.clear();
        for (MetroDataSource.MetroLine item : all)
            lineColours.put(item.name, MetroDataSource.normalizeColour(item.colour));
        metroLine = target;
        observedDataAt = MetroRepository.cityDataAt(MetroDataSource.cityOfLine(target));
        lineName = target.name;
        lineColour = MetroDataSource.normalizeColour(target.colour);
        ringLine = target.ring;
        lineSpeed = MetroTimes.speedKmPerMin(lineName);
        rawStations = new ArrayList<>(target.stations);
        if (rawStations.size() < 2) { showLoadError("暂无站点数据"); return; }
        firstStopName = rawStations.get(0).name;
        lastStopName = rawStations.get(rawStations.size() - 1).name;
        lineTitle.setText(target.networkId);
        lineTitle.setMaxLines(2);
        bindServiceTabs(all);
        bindDirectionTabs();
        renderStations();
        startPredictionTimer();
    }

    private void bindServiceTabs(List<MetroDataSource.MetroLine> all) {
        serviceTabs.removeAllViews();
        for (MetroDataSource.MetroLine option : all) {
            if (!metroLine.networkId.equals(option.networkId) || option.serviceLabel.isEmpty()) continue;
            Button button = Ui.button(this, option.serviceLabel,
                    option.name.equals(lineName) ? Ui.LIGHT_BLUE : Ui.RAISED,
                    option.name.equals(lineName) ? Ui.PRIMARY : Ui.MUTED);
            button.setTextSize(13);
            button.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            button.setMaxLines(2);
            button.setSelected(option.name.equals(lineName));
            button.setOnClickListener(v -> {
                if (option.name.equals(lineName)) return;
                lineName = option.name;
                direction = 0;
                load();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 48), 1);
            if (serviceTabs.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 6);
            serviceTabs.addView(button, lp);
        }
        serviceTabs.setVisibility(serviceTabs.getChildCount() > 1 ? View.VISIBLE : View.GONE);
    }

    private void refreshPrediction() {
        if (lastStations.size() > 1) {
            predictedTrains = predictTrainPositions();
            renderStations();
            if(stationSheet!=null&&stationSheet.isShowing()&&updateStationSheet!=null)updateStationSheet.run();
        }
    }

    private void startPredictionTimer() {
        if (!resumed) return;
        predictionHandler.removeCallbacks(predictionTask);
        predictionHandler.postDelayed(predictionTask, 15_000L);
    }

    /** 当前方向显示的站点顺序。 */
    private List<MetroDataSource.Station> displayStations() {
        if (direction == 1) {
            List<MetroDataSource.Station> reversed = new ArrayList<>(rawStations);
            java.util.Collections.reverse(reversed);
            return reversed;
        }
        return rawStations;
    }

    /** 时刻表预测列车位置：用站点自己的首末班与站间耗时推算。
     *  每个区间只画最靠前的预测位置。 */
    private List<Double> predictTrainPositions() {
        if (metroLine == null) return java.util.Collections.emptyList();
        java.util.Calendar now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        double minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                + now.get(java.util.Calendar.SECOND) / 60.0;
        return new MetroSchedule(metroLine, rawStations, direction == 0, lineSpeed).positions(minute);
    }

    /** 当前方向该站离当前时刻最近的预测到站时间。 */
    private String nextArrivalAt(MetroDataSource.Station station, boolean forward, double nowMin) {
        if (metroLine == null) return null;
        if (!MetroTimes.validTime(forward ? metroLine.firstTime : metroLine.firstTimeR)
                || !MetroTimes.validTime(forward ? metroLine.lastTime : metroLine.lastTimeR))
            return "暂无时刻表";
        int ridx = rawIndexOf(station);
        java.util.List<String> arr = new MetroSchedule(metroLine, rawStations, forward, lineSpeed).arrivals(ridx, nowMin, 1);
        return arr.isEmpty() ? "今日已无班次" : arr.get(0);
    }

    /** 在覆盖层上按小数站间位置摆放列车图标：整站位对齐站点，小数位插在两站之间。 */
    private void positionTrainMarkers() {
        trainOverlay.removeAllViews();
        if (predictedTrains.isEmpty() || stationRows.size() < 2) return;
        int n = stationRows.size();
        double[] centers = new double[n];
        double railX = 0;
        boolean haveRailX = false;
        for (int i = 0; i < n; i++) {
            View row = stationRows.get(i);
            centers[i] = row.getTop() + stationAnchor(row);
            // 轨道轴在行内第 2 个子控件（railCol），图标贴轴左侧摆放
            if (!haveRailX && row instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) row;
                if (group.getChildCount() > 1) {
                    railX = row.getLeft() + group.getChildAt(1).getLeft();
                    haveRailX = true;
                }
            }
        }
        if (!haveRailX) railX = Ui.dp(this, 100);
        int markerW = Ui.dp(this, 44);
        int markerH = Ui.dp(this, 34);
        // 图标贴在轨道轴左侧（railCol 固定 42dp，轴线在正中）。
        double axisX = railX + Ui.dp(this, 21);
        int x = (int) (axisX - Ui.dp(this, 8) - markerW);
        if (x < 0) x = 0;
        for (double pos : predictedTrains) {
            if (pos < 0) pos = 0;
            if (pos > n - 1) pos = n - 1;
            double y;
            if (pos <= 0) y = centers[0];
            else if (pos >= n - 1) y = centers[n - 1];
            else {
                int i = (int) Math.floor(pos);
                double f = pos - i;
                y = centers[i] + f * (centers[i + 1] - centers[i]);
            }
            TextView marker = new TextView(this);
            marker.setText("🚇");
            marker.setTextSize(22);
            marker.setGravity(Gravity.CENTER);
            marker.setSingleLine(true);
            // Emoji 字体自带的上下留白不对称；关闭 font padding 后视觉中心才与预测坐标一致。
            marker.setIncludeFontPadding(false);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            markerW, markerH);
            lp.leftMargin = x;
            lp.topMargin = (int) Math.round(y - markerH / 2.0 - Ui.dp(this, 1));
            trainOverlay.addView(marker, lp);
            marker.setAlpha(1f);
        }
        trainOverlay.bringToFront();
    }

    /** 站点时间轴：左列车 + 中轴 + 右站名（每次渲染前清空，避免定时刷新重复追加）。 */
    private void renderStations() {
        listRoot.removeAllViews();
        final List<MetroDataSource.Station> stations = displayStations();
        lastStations = stations;
        int color = lineColor();

        if (metroLine != null) {
            TextView sched = new TextView(this);
            boolean forward = direction == 0;
            String firstHead = stationFirst(stations.get(0), forward);
            // 首末班统一用起点站发车时间，避免“首班起点发车、末班终点到达”口径混乱
            String lastHead = stationLast(stations.get(0), forward);
            String active = forward
                    ? (ringLine ? "◐ 外环（当前）" : "▸ 往 " + lastStopName + "（当前）")
                    : (ringLine ? "◑ 内环（当前）" : "◂ 往 " + firstStopName + "（当前）");
            sched.setText(active
                    + "：首班 " + firstHead + " · 末班 " + lastHead
                    + "\n点站名查看该站双向班次 · 预测非实时");
            if (!hasTimetable()) sched.setText(active + "\n当前线网未提供运营时刻表");
            sched.setTextSize(12);
            sched.setTextColor(Ui.TEXT);
            sched.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            sched.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
            // 左线路色条（美化）
            LinearLayout schedRow = new LinearLayout(this);
            schedRow.setOrientation(LinearLayout.HORIZONTAL);
            schedRow.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
            View colorBar = new View(this);
            colorBar.setBackground(Ui.rounded(this, lineColor(), 4));
            schedRow.addView(colorBar, new LinearLayout.LayoutParams(
                    Ui.dp(this, 5), ViewGroup.LayoutParams.MATCH_PARENT));
            schedRow.addView(sched, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            listRoot.addView(schedRow, Ui.cardParams(this));

            // 快车信息卡（18/14/21 快慢车、22号线站站停说明）
            if (!metroLine.exprStops.isEmpty() || !metroLine.exprNote.isEmpty()) {
                TextView exprCard = new TextView(this);
                StringBuilder eb = new StringBuilder("🚄 快车信息");
                if (!metroLine.exprStops.isEmpty()) {
                    eb.append("：停靠站 ");
                    StringBuilder stops = new StringBuilder();
                    java.util.List<String> exprDisplay =
                            new ArrayList<>(metroLine.exprStops);
                    // 回程时快车停靠站随方向倒序展示
                    if (direction == 1) {
                        java.util.Collections.reverse(exprDisplay);
                    }
                    for (String sn : exprDisplay) {
                        if (stops.length() > 0) stops.append("、");
                        stops.append(sn);
                    }
                    eb.append(stops);
                }
                if (!metroLine.exprNote.isEmpty()) {
                    eb.append("\n").append(metroLine.exprNote);
                }
                exprCard.setText(eb.toString());
                exprCard.setTextSize(12);
                exprCard.setTextColor(Ui.ACCENT);
                exprCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
                exprCard.setBackground(Ui.rounded(this, Ui.LIGHT_ORANGE, 14));
                listRoot.addView(exprCard, Ui.cardParams(this));
            }
        }

        predictedTrains = predictTrainPositions();
        stationRows.clear();
        // 轴线中心固定在屏幕宽度约 1/6；扣除列表左内边距和轴列半宽。
        // 使用像素直接计算，横竖屏与不同 density 下的位置保持一致。
        final int railWidth = Ui.dp(this, 42);
        final int axisScreenX = getResources().getDisplayMetrics().widthPixels / 6;
        final int railLeftWidth = Math.max(Ui.dp(this, 26),
                axisScreenX - listRoot.getPaddingLeft() - railWidth / 2);

        for (int i = 0; i < stations.size(); i++) {
            MetroDataSource.Station station = stations.get(i);
            boolean first = i == 0;
            boolean last = i == stations.size() - 1;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Normal pitch is 96dp; wrapped interchange chips and larger fonts can expand it.
            row.setMinimumHeight(Ui.textRowHeight(this, 96));
            row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

            LinearLayout leftBox = new LinearLayout(this);
            leftBox.setOrientation(LinearLayout.VERTICAL);
            leftBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            leftBox.setPadding(0, Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
            row.addView(leftBox, new LinearLayout.LayoutParams(
                    railLeftWidth, ViewGroup.LayoutParams.MATCH_PARENT));

            TimelineAxisView railCol = new TimelineAxisView(
                    this, color, color, first, last, first || last);
            row.addView(railCol, new LinearLayout.LayoutParams(
                    railWidth, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout nameBox = new LinearLayout(this);
            nameBox.setOrientation(LinearLayout.VERTICAL);
            nameBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), 0, Ui.dp(this, 12));
            LinearLayout nameRow = new LinearLayout(this);
            boolean compactNames = getResources().getConfiguration().screenWidthDp < 340
                    || getResources().getConfiguration().fontScale > 1.15f;
            nameRow.setOrientation(compactNames ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(station.name);
            name.setTextSize(18);
            name.setTextColor(Ui.TEXT);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (first || last) {
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            }
            nameRow.addView(name, compactNames ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1f));
            // 下一班时间放在站名右侧，与站名保持间隔
            if (metroLine != null) {
                double nowMin = MetroTimes.nowMinuteExact();
                String next = nextArrivalAt(station, direction == 0, nowMin);
                TextView nt = new TextView(this);
                nt.setText(next == null || next.startsWith("今日已无班次")
                        ? "暂无班次" : "暂无时刻表".equals(next) ? next : "约 " + next);
                nt.setTextSize(14);
                nt.setTextColor(Ui.ACCENT);
                nt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                nt.setMaxLines(1);
                nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.setMargins(compactNames ? 0 : Ui.dp(this, 10),
                        compactNames ? Ui.dp(this, 4) : 0, 0, 0);
                nt.setLayoutParams(nlp);
                nameRow.addView(nt);
            }
            nameBox.addView(nameRow);
            row.setTag(nameRow);
            if (first || last) {
                TextView tag = new TextView(this);
                if (ringLine) {
                    tag.setText(first ? "🔁 环线（外环）" : last ? "🔁 环线（内环）" : "");
                } else {
                    tag.setText(first ? "起点站" : "终点站");
                }
                tag.setTextSize(12);
                tag.setTextColor(Ui.lineInk(color));
                tag.setPadding(0, Ui.dp(this, 1), 0, 0);
                nameBox.addView(tag);
            }
            if (station.branch) {
                TextView brTag = new TextView(this);
                String brText = station.branchText == null || station.branchText.isEmpty()
                        ? "⇄ Y字形分叉" : "⇄ 分叉：" + station.branchText;
                brTag.setText("分岔站 · 留意列车终到方向");
                brTag.setMaxLines(1); brTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
                brTag.setContentDescription(brText);
                brTag.setTextSize(12);
                brTag.setTextColor(Ui.ACCENT);
                brTag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                brTag.setPadding(0, Ui.dp(this, 2), 0, 0);
                nameBox.addView(brTag);
            }
            if (metroLine != null && metroLine.exprStops.contains(station.name)) {
                TextView exTag = new TextView(this);
                exTag.setText("🚄 快车停靠站");
                exTag.setTextSize(12);
                exTag.setTextColor(Ui.ACCENT);
                exTag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                exTag.setPadding(0, Ui.dp(this, 2), 0, 0);
                nameBox.addView(exTag);
            }
            if (station.interchange != null && !station.interchange.isEmpty()) {
                TagFlowLayout xRow = new TagFlowLayout(this);
                xRow.setPadding(0, Ui.dp(this, 8), 0, 0);
                for (String xLine : station.interchange) {
                    TextView xTag = new TextView(this);
                    xTag.setText(MetroLineResolver.interchangeLabel(xLine));
                    xTag.setTextSize(12);
                    xTag.setMaxLines(1); xTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    xTag.setContentDescription("换乘 " + xLine);
                    xTag.setTextColor(Color.WHITE);
                    xTag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    xTag.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
                    String xColour = lineColours.get(xLine);
                    int xc;
                    try {
                        xc = Color.parseColor(xColour == null ? "#7EB0DB" : xColour);
                    } catch (Exception e) {
                        xc = Color.parseColor("#7EB0DB");
                    }
                    xTag.setBackground(Ui.rounded(this, xc, 8));
                    xTag.setTextColor(ReadableColors.foreground(xc));
                    android.view.ViewGroup.LayoutParams xlp = new android.view.ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    xRow.addView(xTag, xlp);
                }
                // Wrap interchange chips with a shared horizontal and vertical gap.
                nameBox.addView(xRow, Ui.matchWrap());
            }
            row.addView(nameBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final String stationName = station.name;
            for (RailCatalog.Transfer transfer : RailCatalog.fromMetroLine(metroLine, stationName)) {
                TextView rail = new TextView(this);
                rail.setText((RailCatalog.GD.equals(transfer.region) ? "城际" : "市域") + " · " + transfer.rail + "  ›");
                rail.setTextColor(Ui.PRIMARY);
                Ui.type(rail, 13, true);
                rail.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
                rail.setBackground(Ui.rounded(this, Ui.SURFACE, 8));
                rail.setOnClickListener(view -> RailActivity.open(this, transfer.region, transfer.rail, transfer.rail, ""));
                TagFlowLayout railTags = new TagFlowLayout(this);
                railTags.setPadding(0, Ui.dp(this, 6), 0, 0);
                railTags.addView(rail);
                nameBox.addView(railTags, Ui.matchWrap());
            }
            final String xInfo = station.interchange == null || station.interchange.isEmpty()
                    ? "" : "\n换乘：" + java.util.Arrays.toString(station.interchange.toArray())
                    .replace("[", "").replace("]", "");
            final int stationIndex = i;
            final MetroDataSource.Station stationObj = station;
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showStationDialog(stationObj, stationName, xInfo, stationIndex));
            Ui.pressFeedback(row);
            stationRows.add(row);
            listRoot.addView(row, Ui.matchWrap());
            if (animateNextRender && i < 10) Ui.staggerIn(row, this, i);
        }

        animateNextRender = false;

        // 布局完成后按小数站间位置摆放列车图标（站间插值）
        listRoot.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                listRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                positionSolidMetroRail();
                positionTrainMarkers();
                if (pendingStation != null && !pendingStation.isEmpty()) {
                    int index = MetroPatterns.index(stations, pendingStation);
                    if (index >= 0 && index < stationRows.size()) {
                        View target = stationRows.get(index);
                        target.setBackground(Ui.rounded(MetroLineActivity.this, Ui.RAISED, 10));
                        stationScroll.scrollTo(0, Math.max(0, target.getTop() - Ui.dp(MetroLineActivity.this, 20)));
                        if (openPendingStation) target.post(() -> {
                            if (!isFinishing() && !isDestroyed() && target.isAttachedToWindow()) target.performClick();
                        });
                    }
                    pendingStation = null;
                    openPendingStation = false;
                }
                return true;
            }
        });

        TextView tip = new TextView(this);
        tip.setText("※ 地铁列车位置/到站为时刻表预测，非实时GPS · 点击站名查看该站双向班次");
        tip.setTextSize(10);
        tip.setTextColor(Ui.MUTED);
        tip.setPadding(0, Ui.dp(this, 10), 0, 0);
        listRoot.addView(tip, Ui.cardParams(this));
    }

    private float stationAnchor(View row) {
        Object anchor = row.getTag();
        if (anchor instanceof View && row instanceof ViewGroup) {
            View view = (View)anchor;
            android.graphics.Rect bounds = new android.graphics.Rect(0,0,view.getWidth(),view.getHeight());
            ((ViewGroup)row).offsetDescendantRectToMyCoords(view,bounds);
            return bounds.exactCenterY();
        }
        return row.getHeight()/2f;
    }

    private void positionSolidMetroRail() {
        if (solidMetroRail == null || stationRows.isEmpty()) return;
        View first = stationRows.get(0);
        View last = stationRows.get(stationRows.size() - 1);
        for (View row : stationRows) {
            if (row instanceof ViewGroup && ((ViewGroup)row).getChildAt(1) instanceof TimelineAxisView) {
                TimelineAxisView rail = (TimelineAxisView)((ViewGroup)row).getChildAt(1);
                rail.setAnchor(stationAnchor(row)-rail.getTop());
            }
        }
        int top = first.getTop() + Math.round(stationAnchor(first));
        int bottom = last.getTop() + Math.round(stationAnchor(last));
        if (bottom <= top) return;
        int axisX = getResources().getDisplayMetrics().widthPixels / 6;
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) solidMetroRail.getLayoutParams();
        lp.width = Ui.dp(this, 4);
        lp.height = bottom - top;
        lp.leftMargin = axisX - lp.width / 2;
        lp.topMargin = top;
        solidMetroRail.setLayoutParams(lp);
        solidMetroRail.setBackgroundColor(lineColor());
        solidMetroRail.setVisibility(View.VISIBLE);
    }

    private void showStationDialog(MetroDataSource.Station station,
                                   String stationName, String xInfo, int stationIndex) {
        boolean first = stationIndex == 0;
        boolean last = stationIndex == displayStations().size() - 1;
        int ridx = rawIndexOf(station);
        double currentMinute = MetroTimes.nowMinuteExact();
        String fF = metroLine == null ? "--" : stationFirst(station, true);
        String fL = metroLine == null ? "--" : stationLast(station, true);
        String rF = metroLine == null ? "--" : stationFirst(station, false);
        String rL = metroLine == null ? "--" : stationLast(station, false);
        java.util.List<String> fArr = metroLine == null ? new java.util.ArrayList<String>()
                : new MetroSchedule(metroLine, rawStations, true, lineSpeed).arrivals(ridx, currentMinute, 8);
        java.util.List<String> rArr = metroLine == null ? new java.util.ArrayList<String>()
                : new MetroSchedule(metroLine, rawStations, false, lineSpeed).arrivals(ridx, currentMinute, 8);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Ui.dp(this, 18), Ui.dp(this, 12),
                Ui.dp(this, 18), Ui.dp(this, 24));
        sheet.setBackground(Ui.rounded(this, Ui.SURFACE, 24));

        View handle = new View(this);
        handle.setBackground(Ui.rounded(this, Color.rgb(215, 222, 230), 3));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = Ui.dp(this, 16);
        sheet.addView(handle, hlp);

        TextView title = new TextView(this);
        title.setText(stationName);
        title.setTextSize(24);
        title.setTextColor(Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        sheet.addView(title, Ui.matchWrap());

        TextView meta = new TextView(this);
        String type = first ? "起点站" : last ? "终点站" : "中间站";
        String exchange = xInfo == null ? "" : xInfo.trim().replace("\n", " · ");
        String extra = metroLine != null && metroLine.exprStops.contains(station.name)
                ? " · 快车停靠" : "";
        meta.setText(lineName + " · " + type + extra + (exchange.isEmpty() ? "" : " · " + exchange));
        if (station.branch && station.branchText != null && !station.branchText.isEmpty())
            meta.append("\n" + station.branchText);
        meta.setTextSize(13);
        meta.setTextColor(Ui.MUTED);
        meta.setLineSpacing(Ui.dp(this, 2), 1.05f);
        meta.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        sheet.addView(meta, Ui.matchWrap());
        for (RailCatalog.Transfer transfer : RailCatalog.fromMetroLine(metroLine, stationName)) {
            android.widget.Button rail = Ui.button(this, "换乘 " + RailActivity.title(transfer.region)
                    + " · " + transfer.rail + "  ›", Ui.RAISED, Ui.PRIMARY);
            rail.setOnClickListener(view -> {
                dialog.dismiss();
                RailActivity.open(this, transfer.region, transfer.rail, transfer.rail, "");
            });
            sheet.addView(rail, Ui.cardParams(this));
        }

        View forwardCard=stationScheduleCard("▸ 往 " + lastStopName,fF,fL,fArr,direction==0);
        View reverseCard=stationScheduleCard("◂ 往 " + firstStopName,rF,rL,rArr,direction==1);
        sheet.addView(forwardCard, Ui.cardParams(this)); sheet.addView(reverseCard,Ui.cardParams(this));
        String mtrLine = MtrArrivals.lineCode(metroLine);
        String mtrStation = MtrArrivals.stationCode(stationName);
        TextView official = new TextView(this);
        official.setTextColor(Ui.TEXT);
        official.setBackground(Ui.rounded(this, Ui.RAISED, 12));
        official.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        official.setVisibility(View.GONE);
        sheet.addView(official, Ui.cardParams(this));
        final boolean[] officialLoading = {false};
        if(stationSheet!=null)stationSheet.dismiss(); stationSheet=dialog;
        updateStationSheet=()->{
            if(metroLine==null)return;
            double now=MetroTimes.nowMinuteExact();
            java.util.List<String> forwardTimes=new MetroSchedule(metroLine,rawStations,true,lineSpeed).arrivals(ridx,now,8);
            java.util.List<String> reverseTimes=new MetroSchedule(metroLine,rawStations,false,lineSpeed).arrivals(ridx,now,8);
            ((TextView)((ViewGroup)forwardCard).getChildAt(2)).setText(stationArrivalLabel(forwardTimes));
            ((TextView)((ViewGroup)reverseCard).getChildAt(2)).setText(stationArrivalLabel(reverseTimes));
            if (mtrLine != null && mtrStation != null && !officialLoading[0] && dialog.isShowing()) {
                officialLoading[0] = true;
                worker.execute(() -> {
                    String result;
                    try { result = MtrArrivals.load(mtrLine, mtrStation); }
                    catch (Exception unavailable) { result = ""; }
                    final String arrivals = result;
                    runOnUiThread(() -> {
                        officialLoading[0] = false;
                        if (stationSheet != dialog || !dialog.isShowing() || isDestroyed()) return;
                        official.setText(arrivals.isEmpty() ? "" : "港铁官方预计到站\n" + arrivals);
                        official.setVisibility(arrivals.isEmpty() ? View.GONE : View.VISIBLE);
                        forwardCard.setVisibility(arrivals.isEmpty() ? View.VISIBLE : View.GONE);
                        reverseCard.setVisibility(arrivals.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                });
            }
        };
        dialog.setOnDismissListener(d->{if(stationSheet==dialog){stationSheet=null;updateStationSheet=null;}});

        TextView note = new TextView(this);
        note.setText(hasTimetable()
                ? "首末班资料已保存在应用内；中间班次按运行间隔离线估算，并非实时到站"
                : "当前资料仅含线路与站点，未提供运营时刻表，不生成预测列车位置或到站班次");
        note.setTextSize(12);
        note.setTextColor(Ui.MUTED);
        note.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 12));
        sheet.addView(note, Ui.matchWrap());
        TextView close = new TextView(this);
        close.setText("完成");
        close.setTextSize(15);
        close.setTextColor(ReadableColors.foreground(lineColor()));
        close.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.rounded(this, lineColor(), 14));
        close.setOnClickListener(v -> dialog.dismiss());
        Ui.pressFeedback(close);
        sheet.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

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
        updateStationSheet.run();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        sheet.setTranslationY(Ui.dp(this, 90));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0f).alpha(1f).setDuration(Ui.duration(this, 220))
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private View stationScheduleCard(String directionTitle, String first, String last,
                                     java.util.List<String> arrivals, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 15), Ui.dp(this, 13),
                Ui.dp(this, 15), Ui.dp(this, 13));
        card.setBackground(Ui.rounded(this,
                active ? Ui.LIGHT_BLUE : Ui.RAISED, 16));

        TextView title = new TextView(this);
        title.setText(directionTitle + (active ? "  · 当前方向" : ""));
        title.setTextSize(17);
        title.setTextColor(active ? Ui.lineInk(lineColor()) : Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, Ui.matchWrap());

        TextView hours = new TextView(this);
        hours.setText(MetroTimes.validTime(first) && MetroTimes.validTime(last)
                ? "首班 " + first + "   ·   末班 " + last : "暂无首末班资料");
        hours.setTextSize(13);
        hours.setTextColor(Ui.MUTED);
        hours.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 8));
        card.addView(hours, Ui.matchWrap());

        TextView next = new TextView(this);
        next.setText(stationArrivalLabel(arrivals));
        next.setTextSize(15);
        next.setTextColor(active ? Ui.ACCENT : Ui.TEXT);
        next.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        next.setLineSpacing(Ui.dp(this, 3), 1.08f);
        card.addView(next, Ui.matchWrap());
        return card;
    }

    private static boolean validTime(String v) {
        return MetroTimes.validTime(v);
    }
    private String stationArrivalLabel(java.util.List<String> arrivals) {
        if (arrivals.isEmpty() && !hasTimetable()) return "暂无时刻表";
        return arrivals.isEmpty()?"今日已无后续班次":"约 "+android.text.TextUtils.join("   ",arrivals);
    }

    private boolean hasTimetable() {
        return metroLine != null && ((MetroTimes.validTime(metroLine.firstTime)
                && MetroTimes.validTime(metroLine.lastTime))
                || (MetroTimes.validTime(metroLine.firstTimeR) && MetroTimes.validTime(metroLine.lastTimeR)));
    }

    /** 不依赖彩色 Emoji 字体的地铁车辆图标，所有 Android 版本都能稳定显示。 */
    private static final class TrainMarkerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int colour;
        private final int count;

        TrainMarkerView(android.content.Context context, int colour, int count) {
            super(context);
            this.colour = colour;
            this.count = count;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float right = count > 1 ? getWidth() - 8 * d : getWidth() - 2 * d;
            paint.setShadowLayer(4 * d, 0, 2 * d, 0x44000000);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(2 * d, 2 * d, right, getHeight() - 3 * d,
                    7 * d, 7 * d, paint);
            paint.clearShadowLayer();
            paint.setColor(colour);
            canvas.drawRoundRect(4 * d, 4 * d, right - 2 * d, getHeight() - 5 * d,
                    5 * d, 5 * d, paint);
            paint.setColor(0xEFFFFFFF);
            canvas.drawRoundRect(7 * d, 7 * d, right - 5 * d, 13 * d,
                    2 * d, 2 * d, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(8 * d, getHeight() - 8 * d, 1.7f * d, paint);
            canvas.drawCircle(right - 6 * d, getHeight() - 8 * d, 1.7f * d, paint);
            if (count > 1) {
                paint.setColor(0xFF172B4D);
                paint.setTextSize(9 * d);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                canvas.drawText("×" + count, right - 1 * d, 11 * d, paint);
            }
        }
    }

    private int rawIndexOf(MetroDataSource.Station station) {
        for (int i = 0; i < rawStations.size(); i++) {
            if (rawStations.get(i).name.equals(station.name)) return i;
        }
        return 0;
    }

    private String stationFirst(MetroDataSource.Station station, boolean forward) {
        return MetroTimes.stationFirst(metroLine, station, forward);
    }

    private String stationLast(MetroDataSource.Station station, boolean forward) {
        return MetroTimes.stationLast(metroLine, station, forward);
    }

    /** 若该站没有真实时刻表，返回用线路首班 + 累计行驶时间估算的偏移量。 */
    private int stationOffset(MetroDataSource.Station station, boolean forward) {
        return MetroTimes.stationOffset(metroLine, rawStations, rawIndexOf(station),
                forward, lineSpeed);
    }

    private int cumulativeForDirection(MetroDataSource.Station station, boolean forward) {
        return MetroTimes.rawCumulative(rawStations, rawIndexOf(station), forward, lineSpeed);
    }

    /** 从线路端点（正向/反向）到 rawIndex 站的累计行驶分钟。 */
    private int rawCumulative(int rawIndex, boolean forward) {
        return MetroTimes.rawCumulative(rawStations, rawIndex, forward, lineSpeed);
    }

    /** 相邻两站行驶分钟：优先用真实首班时间差，异常/缺失时用距离估算。 */
    private int travelMinutes(MetroDataSource.Station a, MetroDataSource.Station b, boolean forward) {
        return MetroTimes.travelMinutes(a, b, forward, lineSpeed);
    }

    private int distanceMinutes(MetroDataSource.Station a, MetroDataSource.Station b) {
        return MetroTimes.distanceMinutes(a, b, lineSpeed);
    }

    private static int toMinutes(String hhmm) {
        return MetroTimes.toMinutes(hhmm);
    }

    private static String formatTime(int minute) {
        return MetroTimes.formatTime(minute);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        MetroRepository.observe(dataChanged);
        if (metroLine != null) worker.execute(() -> {
            try {
                new MetroDataSource(this).queryLines();
                runOnUiThread(dataChanged);
            } catch (Exception unavailable) { /* Keep the visible, usable line. */ }
        });
        if (lastStations.size() > 1) { refreshPrediction(); startPredictionTimer(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        MetroRepository.unobserve(dataChanged);
        predictionHandler.removeCallbacks(predictionTask);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("metroLine", lineName); state.putInt("metroDirection", direction);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if(stationSheet!=null)stationSheet.dismiss();
        super.onDestroy();
        predictionHandler.removeCallbacks(predictionTask);
        worker.shutdownNow();
    }
}
