package com.holi.prdbus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RegionalBusActivity extends ThemedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean hk, active, loading;
    private RegionalBusData.Catalog catalog;
    private EditText search;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView eta;
    private JSONArray route;
    private int selected = -1, generation;
    private List<RegionalBusData.EtaSource> sources;
    private final Runnable tick = () -> updateEta();
    private final Runnable filter = () -> showResults();

    static boolean supports(String id) { return "hk:bus".equals(id) || "mo:bus".equals(id); }

    static void open(Activity activity, String cityId, String query) {
        activity.startActivity(new Intent(activity, RegionalBusActivity.class)
                .putExtra("cityId", cityId).putExtra("query", query));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        hk = "hk:bus".equals(getIntent().getStringExtra("cityId"));
        Ui.edgeToEdge(this);
        LinearLayout page = column();
        page.setBackgroundColor(Ui.SURFACE);
        LinearLayout header = column();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(button("‹ 返回", () -> {
            if (route == null) finish();
            else showResults();
        }));
        TextView title = text(hk ? "香港巴士" : "澳门巴士", 23, Ui.TEXT, true);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title);
        header.addView(top, Ui.matchWrap());
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(hk ? "输入线路号或站名" : "输入线路号、站名或站码");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(Ui.rounded(this, Ui.RAISED, 10));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setText(state == null ? getIntent().getStringExtra("query") : state.getString("query"));
        header.addView(search, Ui.cardParams(this));
        page.addView(header, Ui.matchWrap());
        Ui.insets(this, header, 16, 16, 6, 4);

        scroll = new ScrollView(this);
        content = column();
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(content, Ui.matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        page.setFocusableInTouchMode(true);
        page.requestFocus();

        content.addView(Ui.loadingCard(this), Ui.cardParams(this));
        worker.execute(() -> {
            try {
                RegionalBusData.Catalog result = RegionalBusData.catalog(this, hk);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    catalog = result;
                    showResults();
                });
            } catch (Exception unavailable) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    content.removeAllViews();
                    content.addView(Ui.emptyCard(this, "无法读取离线巴士线路"), Ui.cardParams(this));
                });
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(filter);
                handler.postDelayed(filter, 160);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void showResults() {
        if (catalog == null) return;
        generation++;
        handler.removeCallbacks(tick);
        route = null;
        eta = null;
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        content.addView(text(hk ? "运输署站线 · 九巴、龙运、城巴到站"
                : "交通事务局站线 · 2026 年 10 月", 13, Ui.MUTED, false), Ui.matchWrap());
        if (!hk) content.addView(button("打开官方巴士报站  ›", this::openMacau), Ui.cardParams(this));
        List<RegionalBusData.Match> matches = RegionalBusData.search(catalog, search.getText().toString());
        for (RegionalBusData.Match match : matches) {
            JSONArray stops = match.route.optJSONArray(3);
            LinearLayout card = card();
            card.addView(text(match.route.optString(0) + "  ·  "
                    + RegionalBusData.operator(match.route.optString(1)), 17, Ui.PRIMARY, true));
            card.addView(text(routeTitle(stops), 15, Ui.TEXT, false));
            if (match.stopIndex >= 0) card.addView(text("经 " + catalog.stop(stops.optString(match.stopIndex)).name,
                    13, Ui.MUTED, false));
            card.setOnClickListener(view -> showRoute(match.route, match.stopIndex));
            content.addView(card, Ui.cardParams(this));
        }
        if (matches.isEmpty()) content.addView(Ui.emptyCard(this, "没有匹配的线路或站点"), Ui.cardParams(this));
    }

    private void showRoute(JSONArray direction, int station) {
        handler.removeCallbacks(filter);
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        route = direction;
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        JSONArray stops = route.optJSONArray(3);
        LinearLayout card = card();
        card.addView(text(route.optString(0) + "  ·  " + RegionalBusData.operator(route.optString(1)), 20, Ui.PRIMARY, true));
        card.addView(text(routeTitle(stops), 15, Ui.TEXT, false));
        eta = text(hk ? "选择站台查看到站时间" : "离线站序", 15, Ui.PRIMARY, false);
        eta.setPadding(0, dp(10), 0, dp(6));
        card.addView(eta, Ui.matchWrap());
        if (!hk) card.addView(button("官方巴士报站  ›", this::openMacau));
        content.addView(card, Ui.cardParams(this));
        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < stops.length(); i++) {
            final int index = i;
            RegionalBusData.Stop stop = catalog.stop(stops.optString(i));
            TextView row = text((i + 1) + "   " + stop.name + (stop.code.isEmpty() ? "" : "  ·  " + stop.code),
                    15, Ui.TEXT, false);
            row.setPadding(dp(13), dp(12), dp(13), dp(12));
            row.setOnClickListener(view -> {
                for (TextView sibling : rows) sibling.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                row.setBackground(Ui.rounded(this, Ui.RAISED, 8));
                select(index);
                if (scroll.getScrollY() > dp(60)) scroll.smoothScrollTo(0, 0);
            });
            rows.add(row);
            content.addView(row, Ui.matchWrap());
        }
        select(station);
        if (station >= 0 && station < rows.size()) rows.get(station).setBackground(Ui.rounded(this, Ui.RAISED, 8));
    }

    private String routeTitle(JSONArray stops) {
        return catalog.stop(stops.optString(0)).name + " → "
                + catalog.stop(stops.optString(stops.length() - 1)).name;
    }

    private void select(int index) {
        generation++;
        selected = index;
        sources = null;
        loading = false;
        handler.removeCallbacks(tick);
        if (index >= 0) {
            if (hk && RegionalBusData.liveSupported(route)) {
                eta.setText(stopName() + "\n正在查询到站…");
                updateEta();
            } else {
                eta.setText(stopName() + (hk ? "\n该运营商暂只提供离线站序" : "\n到站时间请查看官方巴士报站"));
            }
        }
    }

    private String stopName() { return catalog.stop(route.optJSONArray(3).optString(selected)).name; }

    private void updateEta() {
        if (!active || loading || route == null || selected < 0 || !hk || !RegionalBusData.liveSupported(route)) return;
        loading = true;
        final JSONArray line = route;
        final int id = generation, station = selected;
        final List<RegionalBusData.EtaSource> existing = sources;
        worker.execute(() -> {
            List<RegionalBusData.EtaSource> resolved = existing;
            String result;
            try {
                if (resolved == null) resolved = RegionalBusData.resolve(this, catalog, line, station);
                List<String> times = new ArrayList<>();
                boolean replied = false;
                for (RegionalBusData.EtaSource source : resolved) {
                    try {
                        String arrivals = source.arrivals();
                        replied = true;
                        if (!arrivals.isEmpty()) times.add(arrivals);
                    } catch (Exception unavailable) { }
                }
                result = resolved.isEmpty() ? "暂未核对到实时站台"
                        : !replied ? "到站服务暂不可用"
                        : times.isEmpty() ? "暂无预计到站班次" : android.text.TextUtils.join("\n", times);
            } catch (Exception unavailable) { result = "到站服务暂不可用"; }
            final String message = result;
            final List<RegionalBusData.EtaSource> next = resolved;
            runOnUiThread(() -> {
                if (id != generation || isDestroyed()) return;
                loading = false;
                sources = next;
                eta.setText(stopName() + "\n" + message);
                if (active && (next == null || !next.isEmpty())) handler.postDelayed(tick, 15_000L);
            });
        });
    }

    private void openMacau() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dsat.gov.mo/bus/site/index.aspx")));
        } catch (android.content.ActivityNotFoundException ignored) { }
    }

    @Override protected void onResume() { super.onResume(); active = true; updateEta(); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", search.getText().toString());
    }

    private int dp(int size) { return Ui.dp(this, size); }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }
    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(13), dp(11), dp(13), dp(11));
        box.setBackground(Ui.rounded(this, Ui.RAISED, 12));
        return box;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        Ui.type(text, size, bold);
        return text;
    }
    private Button button(String title, Runnable tap) {
        Button button = Ui.button(this, title, Ui.RAISED, Ui.PRIMARY);
        button.setOnClickListener(view -> tap.run());
        return button;
    }
}
