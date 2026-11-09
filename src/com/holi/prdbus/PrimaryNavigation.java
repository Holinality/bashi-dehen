package com.holi.prdbus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

/** The same four destinations on every first-level surface, without stacking duplicate roots. */
final class PrimaryNavigation {
    static final String TAB = "primaryTab";
    private static final String MOTION = "primaryMotion";
    private static final String DIRECTION = "primaryDirection";
    private static final String[] LABELS = {"附近", "地铁", "收藏", "规划"};
    private static final String[] ICONS = {"pin", "metro", "star", "route"};
    private PrimaryNavigation() { }

    static void bind(LinearLayout page, LinearLayout navigation, int tab) {
        LinearLayout content = new LinearLayout(page.getContext());
        content.setId(R.id.primary_content);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag(tab);
        page.removeView(navigation);
        while (page.getChildCount() > 0) {
            View child = page.getChildAt(0);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            page.removeViewAt(0);
            content.addView(child, params);
        }
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(navigation, Ui.matchWrap());
    }

    static void selected(Activity activity, int tab) {
        View content = activity.findViewById(R.id.primary_content);
        if (content != null) content.setTag(tab);
    }

    private static int selected(Activity activity) {
        View content = activity.findViewById(R.id.primary_content);
        if (content != null && content.getTag() instanceof Integer) return (Integer)content.getTag();
        return activity instanceof MetroActivity ? 1 : activity instanceof TransitActivity ? 3 : 0;
    }

    static void switchContent(Activity activity, int tab, Runnable update) {
        int previous = selected(activity);
        update.run();
        selected(activity, tab);
        if (previous != tab) enter(activity, Integer.compare(tab, previous));
    }

    static void arrive(Activity activity) {
        if (activity.findViewById(R.id.primary_content) == null) return;
        Intent intent = activity.getIntent();
        if (intent != null && intent.getBooleanExtra(MOTION, false)) {
            int direction = intent.getIntExtra(DIRECTION, 1);
            selected(activity, intent.getIntExtra(TAB, selected(activity)));
            intent.removeExtra(MOTION);
            intent.removeExtra(DIRECTION);
            enter(activity, direction);
        } else stop(activity);
    }

    static void stop(Activity activity) {
        View content = activity.findViewById(R.id.primary_content);
        if (content != null) {
            content.animate().cancel();
            restore(content);
        }
    }

    private static void restore(View content) {
        content.setAlpha(1f);
        content.setTranslationX(0f);
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.setRotationY(0f);
    }

    private static void enter(Activity activity, int direction) {
        View content = activity.findViewById(R.id.primary_content);
        if (content == null) return;
        content.animate().cancel();
        restore(content);
        if (Ui.motionScale(activity) == 0) return;
        content.setAlpha(.9f);
        content.setTranslationX(Ui.dp(activity,12)*direction);
        content.animate().alpha(1f).translationX(0)
                .setDuration(Ui.duration(activity, 200)).setInterpolator(Ui.easeOut())
                .withEndAction(() -> restore(content)).start();
    }

    static LinearLayout create(Activity activity, int selected) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        bar.setBackgroundColor(Ui.SURFACE);
        for (int i = 0; i < LABELS.length; i++) {
            final int tab = i;
            Button button = Ui.navButton(activity, LABELS[i], ICONS[i], selected == i);
            button.setContentDescription(LABELS[i] + (selected == i ? "，当前页面" : ""));
            button.setOnClickListener(v -> { if (tab != selected) open(activity, tab); });
            bar.addView(button, new LinearLayout.LayoutParams(0, Ui.textRowHeight(activity, 64), 1));
        }
        updateAvailability(activity, bar);
        return bar;
    }

    static void updateAvailability(Activity activity, LinearLayout bar) {
        if (bar == null || bar.getChildCount() != 4) return;
        bar.getChildAt(1).setVisibility(AppSettings.hasMetro(activity) ? View.VISIBLE : View.GONE);
        bar.getChildAt(3).setVisibility(AppSettings.hasMapKey() ? View.VISIBLE : View.GONE);
    }

    static void remember(Context context, String cityId, String city, double lat, double lon, boolean located) {
        context.getSharedPreferences("primary_navigation", Context.MODE_PRIVATE).edit()
                .putString("cityId", cityId).putString("cityName", city)
                .putString("province", Regions.province(city))
                .putLong("lat", Double.doubleToRawLongBits(lat)).putLong("lon", Double.doubleToRawLongBits(lon))
                .putBoolean("located", located).apply();
    }

    static void open(Activity activity, int tab) {
        if (tab == 3 && !AppSettings.hasMapKey()) return;
        Class<?> destination = tab == 1 ? MetroActivity.class : tab == 3 ? TransitActivity.class : MainActivity.class;
        Intent intent = new Intent(activity, destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(TAB, tab);
        intent.putExtra(MOTION, true);
        intent.putExtra(DIRECTION, Integer.compare(tab, selected(activity)) >= 0 ? 1 : -1);
        SharedPreferences state = activity.getSharedPreferences("primary_navigation", Context.MODE_PRIVATE);
        intent.putExtra("cityId", state.getString("cityId", "019"));
        intent.putExtra("cityName", state.getString("cityName", "佛山"));
        intent.putExtra("currentLat", Double.longBitsToDouble(state.getLong("lat", 0)));
        intent.putExtra("currentLon", Double.longBitsToDouble(state.getLong("lon", 0)));
        intent.putExtra("hasLocation", state.getBoolean("located", false));
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null && activity.getCurrentFocus() != null)
            keyboard.hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(), 0);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }
}
