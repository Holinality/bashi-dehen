package com.holi.prdbus;

import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全国地铁/轨道交通线路图（按城市分组）。 */
public final class MetroActivity extends ThemedActivity {
    private static final String[] CITY_ORDER = {"广州", "深圳", "佛山", "东莞"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MetroDataSource dataSource;

    private android.widget.ListView listRoot;
    private interface DirectoryItem { View create(); }
    private final List<DirectoryItem> directoryRows = new ArrayList<>();
    private final android.widget.BaseAdapter directoryAdapter = new android.widget.BaseAdapter() {
        @Override public int getCount() { return directoryRows.size(); }
        @Override public Object getItem(int position) { return directoryRows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            DirectoryItem item = directoryRows.get(position);
            if (recycled != null && recycled.getTag() == item) return recycled;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(MetroActivity.this);
            cell.setPadding(0, 0, 0, Ui.dp(MetroActivity.this, 12));
            cell.addView(item.create(), new android.widget.FrameLayout.LayoutParams(-1, -2));
            cell.setTag(item);
            return cell;
        }
    };
    private View progress;
    private LinearLayout headerBox;
    private PrimaryHeader metroHeader;
    private LinearLayout navigation;
    private boolean resumed;
    private int loadSequence;
    private List<MetroDataSource.MetroLine> displayedLines = java.util.Collections.emptyList();
    private String displayedProvince = "", displayedCity = "";
    private final Runnable dataChanged = () -> { if (resumed && !isFinishing()) loadLines(); };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dataSource = new MetroDataSource(this);
        Ui.edgeToEdge(this);
        buildUi();
        Ui.insets(this, headerBox, 16, 16, 7, 9);
        loadLines();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.TRANSPARENT);

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 9));
        headerBox.setBackgroundColor(Ui.SURFACE);

        metroHeader = new PrimaryHeader(this, "地铁",
                Regions.provinceName(AppSettings.activeProvince(this)), null);
        headerBox.addView(metroHeader, Ui.matchWrap());
        page.addView(headerBox, Ui.matchWrap());

        progress = Ui.loadingDots(this);
        progress.setVisibility(View.GONE);

        listRoot = new android.widget.ListView(this);
        listRoot.setDivider(null);
        listRoot.setDividerHeight(0);
        listRoot.setSelector(android.R.color.transparent);
        listRoot.setCacheColorHint(Color.TRANSPARENT);
        listRoot.setClipToPadding(false);
        listRoot.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 24));
        listRoot.setAdapter(directoryAdapter);
        page.addView(listRoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        navigation = PrimaryNavigation.create(this, 1);
        page.addView(navigation, Ui.matchWrap());
        PrimaryNavigation.bind(page, navigation, 1);

        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shell);
        progress = Ui.centerLoader(this, progress);
    }

    private void loadLines() {
        final int sequence = ++loadSequence;
        final int firstVisible = listRoot.getFirstVisiblePosition();
        final View firstChild = listRoot.getChildAt(0);
        final int firstTop = firstChild == null ? 0 : firstChild.getTop();
        final String province = AppSettings.activeProvince(this);
        final String city = AppSettings.activeCity(this);
        final boolean keepPosition = province.equals(displayedProvince) && city.equals(displayedCity);
        progress.setVisibility(directoryRows.isEmpty() ? View.VISIBLE : View.GONE);
        executor.execute(() -> {
            try {
                List<MetroDataSource.MetroLine> lines = new ArrayList<>();
                for (MetroDataSource.MetroLine line : dataSource.queryLines(province)) {
                    if (province.equals(Regions.province(MetroDataSource.cityOfLine(line)))) lines.add(line);
                }
                runOnUiThread(() -> {
                    if (sequence != loadSequence) return;
                    if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    if (lines.isEmpty() && MetroRepository.isRefreshing(province)) {
                        if (!province.equals(displayedProvince) || !city.equals(displayedCity))
                            directoryRows.clear();
                        directoryAdapter.notifyDataSetChanged();
                        progress.setVisibility(directoryRows.isEmpty() ? View.VISIBLE : View.GONE);
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    if (province.equals(displayedProvince) && city.equals(displayedCity)
                            && lines.equals(displayedLines) && !directoryRows.isEmpty()) return;
                    displayedLines = lines;
                    displayedProvince = province;
                    displayedCity = city;
                    if (lines.isEmpty()) {
                        directoryRows.clear();
                        addServiceEntries();
                        directoryRows.add(() -> Ui.emptyCard(this, "暂未获取到本省地铁线路"));
                        directoryAdapter.notifyDataSetChanged();
                        return;
                    }
                    renderByCity(lines);
                    listRoot.post(() -> listRoot.setSelectionFromTop(
                            keepPosition ? firstVisible : 0, keepPosition ? firstTop : 0));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (sequence != loadSequence) return;
                    if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    progress.setVisibility(View.GONE);
                    if (!directoryRows.isEmpty()) return;
                    addServiceEntries();
                    String msg = error.getMessage();
                    directoryRows.add(() -> Ui.emptyCard(this,
                            "无法连接地铁数据\n" + (msg == null ? "网络错误" : msg)));
                    directoryAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    /** 按城市分组渲染（线路按数字大小排序）。 */
    private void renderByCity(List<MetroDataSource.MetroLine> lines) {
        directoryRows.clear();
        addServiceEntries();
        java.util.Set<String> included = new java.util.HashSet<>();
        Map<String, List<MetroDataSource.MetroLine>> byCity = new LinkedHashMap<>();
        for (MetroDataSource.MetroLine line : lines) {
            if (!included.add(line.networkId)) continue;
            String city = MetroDataSource.cityOfLine(line);
            List<MetroDataSource.MetroLine> list = byCity.get(city);
            if (list == null) {
                list = new ArrayList<>();
                byCity.put(city, list);
            }
            list.add(line);
        }
        for (List<MetroDataSource.MetroLine> list : byCity.values()) {
            java.util.Collections.sort(list, (a, b) -> {
                int na = lineNumber(a.name);
                int nb = lineNumber(b.name);
                if (na != nb) return Integer.compare(na, nb);
                return a.name.compareTo(b.name);
            });
        }
        List<String> orderedCities = new ArrayList<>();
        for (String city : CITY_ORDER) {
            if (byCity.containsKey(city)) orderedCities.add(city);
        }
        for (String city : byCity.keySet()) {
            if (!orderedCities.contains(city)) orderedCities.add(city);
        }

        for (String city : orderedCities) {
            List<MetroDataSource.MetroLine> cityLines = byCity.get(city);
            directoryRows.add(() -> cityHeader(city, cityLines.size()));
            for (MetroDataSource.MetroLine line : cityLines) {
                directoryRows.add(() -> lineCard(line));
            }
        }
        directoryAdapter.notifyDataSetChanged();
    }

    private void addServiceEntries() {
        MetroNetwork.Area area = MetroNetwork.Area.forCity(AppSettings.activeCity(this));
        if (area != null) directoryRows.add(() -> serviceEntry(area.label + "地铁线网", "规整图 · 位置线网", "route",
                () -> MetroNetworkActivity.open(this, area)));
        String region = AppSettings.activeProvince(this);
        if (RailCatalog.GD.equals(region) || RailCatalog.SH.equals(region))
            directoryRows.add(() -> serviceEntry(RailActivity.title(region), "线网换乘 · 班次时刻", "metro",
                    () -> RailActivity.open(this, region, "", "", "")));
    }

    private View serviceEntry(String title, String description, String icon, Runnable action) {
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        entry.setMinimumHeight(Ui.textRowHeight(this, 76));
        entry.setBackground(Ui.edgeCard(this, Ui.PRIMARY, 16));
        entry.setClickable(true);
        entry.setFocusable(true);
        entry.setContentDescription(title + "，" + description);

        android.widget.ImageView symbol = new android.widget.ImageView(this);
        symbol.setImageDrawable(new TransitIcon(icon, Ui.PRIMARY));
        symbol.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        symbol.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        symbol.setBackground(Ui.rounded(this, Ui.RAISED, 12));
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        entry.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Ui.TEXT);
        Ui.type(heading, 16, true);
        copy.addView(heading, Ui.matchWrap());
        TextView detail = new TextView(this);
        detail.setText(description);
        detail.setTextColor(Ui.MUTED);
        Ui.type(detail, 13, false);
        LinearLayout.LayoutParams descriptionSize = Ui.matchWrap();
        descriptionSize.topMargin = Ui.dp(this, 4);
        copy.addView(detail, descriptionSize);
        entry.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        CenteredIconView arrow = new CenteredIconView(this, "chevron", Ui.MUTED);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        entry.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 48)));
        entry.setOnClickListener(view -> action.run());
        Ui.pressFeedback(entry);
        return entry;
    }

    /** 从线路名提取线号数字（广佛线等无数字 → 极大值排最后）。 */
    private static int lineNumber(String name) {
        if (name == null) return Integer.MAX_VALUE;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        try { return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE; }
        catch (NumberFormatException invalidNumber) { return Integer.MAX_VALUE; }
    }

    private View cityHeader(String city, int count) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView dot = new TextView(this);
        dot.setText("");
        dot.setTextSize(10);
        dot.setTextColor(Ui.PRIMARY);
        header.addView(dot);
        TextView name = new TextView(this);
        name.setText(city + " · " + count + "条线路");
        Ui.type(name, Ui.SECTION, true);
        name.setTextSize(14);
        name.setTextColor(Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(name);
        return header;
    }

    private View lineCard(MetroDataSource.MetroLine line) {
        final MetroDataSource.MetroLine item = line;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        int colour = Color.parseColor(MetroDataSource.normalizeColour(item.colour));
        card.setBackground(Ui.edgeCard(this, colour, 18));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(Ui.cardParams(this));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View colorBar = new View(this);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(colour);
        bar.setCornerRadius(Ui.dp(this, 4));
        colorBar.setBackground(bar);
        top.addView(colorBar, new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 42)));

        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(item.networkId);
        name.setTextSize(18);
        name.setTextColor(Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameBox.addView(name);
        if (item.stations.size() >= 2) {
            TextView route = new TextView(this);
            route.setText(MetroPatterns.isGuangzhou3(item.name) ? "机场北 / 天河客运站 ↔ 海傍"
                    : item.stations.get(0).name + " → " + item.stations.get(item.stations.size() - 1).name);
            route.setTextSize(13);
            route.setTextColor(Ui.MUTED);
            route.setPadding(0, Ui.dp(this, 8), 0, 0);
            route.setLineSpacing(Ui.dp(this,2),1f);
            nameBox.addView(route);

            TextView meta = new TextView(this);
            meta.setText("首末班 ▸ " + item.firstTime + "–" + item.lastTime
                    + " · ◂ " + item.firstTimeR + "–" + item.lastTimeR);
            if (!MetroTimes.validTime(item.firstTime) || !MetroTimes.validTime(item.lastTime))
                meta.setText("线路与站点资料 · 暂无首末班时刻表");
            if (MetroPatterns.isGuangzhou3(item.name)) meta.setText("两种交路 · 体育西路分岔 · 点击选择");
            meta.setTextSize(12);
            meta.setTextColor(Ui.MUTED);
            meta.setPadding(0, Ui.dp(this, 4), 0, 0);
            meta.setLineSpacing(Ui.dp(this,2),1f);
            nameBox.addView(meta);

            if (item.branchInfo != null && !item.branchInfo.isEmpty()) {
                TextView branch = new TextView(this);
                branch.setText(item.branchInfo);
                branch.setTextSize(12);
                branch.setTextColor(Ui.ACCENT);
                branch.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                branch.setPadding(0, Ui.dp(this, 4), 0, 0);
                nameBox.addView(branch);
            }

            int xCount = 0;
            for (MetroDataSource.Station st : item.stations) {
                if (st.interchange != null && !st.interchange.isEmpty()) xCount++;
            }
            if (xCount > 0) {
                TextView xLabel = new TextView(this);
                xLabel.setText("⇄ 换乘 " + xCount + " 站");
                xLabel.setTextSize(12);
                xLabel.setTextColor(Ui.ACCENT);
                xLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                xLabel.setPadding(0, Ui.dp(this, 4), 0, 0);
                nameBox.addView(xLabel);
            }
        }
        top.addView(nameBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CenteredIconView arrow = new CenteredIconView(this, "chevron", Ui.MUTED);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 48));
        arrowSize.leftMargin = Ui.dp(this, 8);
        top.addView(arrow, arrowSize);
        card.addView(top);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, MetroLineActivity.class);
            intent.putExtra("lineName", item.name);
            intent.putExtra("colour", MetroDataSource.normalizeColour(item.colour));
            startActivity(intent);
            Ui.transitionForward(this);
        });
        Ui.pressFeedback(card);
        return card;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume(); resumed = true; MetroRepository.observe(dataChanged);
        metroHeader.cityLabel.setText(Regions.provinceName(AppSettings.activeProvince(this)));
        PrimaryNavigation.updateAvailability(this, navigation);
        if (!directoryRows.isEmpty()) loadLines();
    }

    @Override
    protected void onPause() {
        resumed = false; MetroRepository.unobserve(dataChanged); super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
