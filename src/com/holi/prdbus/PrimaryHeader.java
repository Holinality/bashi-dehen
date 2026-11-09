package com.holi.prdbus;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared first-level title, city/scope and settings, based on the home header. */
final class PrimaryHeader extends LinearLayout {
    final TextView cityLabel;
    private final TextView title;

    PrimaryHeader(Activity activity, String label, String city, View.OnClickListener cityAction) {
        super(activity);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Ui.dp(activity, 48));
        LinearLayout copy = new LinearLayout(activity);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(activity);
        title.setText(label);
        Ui.type(title, Ui.TITLE, true);
        title.setTextColor(Ui.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title, new LayoutParams(-2, -2));

        cityLabel = new TextView(activity);
        cityLabel.setText(city + (cityAction == null ? "" : "  ▾"));
        Ui.type(cityLabel, 15, true);
        cityLabel.setTextColor(Ui.PRIMARY);
        cityLabel.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cityLabel.setSingleLine(true);
        cityLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cityLabel.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6));
        cityLabel.setMinHeight(Ui.dp(activity, 48));
        if (cityAction != null) {
            cityLabel.setContentDescription("选择出行城市");
            cityLabel.setOnClickListener(cityAction);
        }
        copy.addView(cityLabel, new LayoutParams(0, -2, 1f));
        addView(copy, new LayoutParams(0, -2, 1f));

        CenteredIconView settings = new CenteredIconView(activity, "settings", Ui.TEXT);
        settings.setBackground(Ui.control(activity, Ui.SURFACE));
        settings.setContentDescription("设置");
        settings.setOnClickListener(v -> activity.startActivity(new Intent(activity, SettingsActivity.class)));
        LayoutParams settingsSize = new LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48));
        settingsSize.leftMargin = Ui.dp(activity, 8);
        addView(settings, settingsSize);
        Ui.pressFeedback(settings);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int copyWidth = Math.max(0, View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight()
                - Ui.dp(getContext(), 56));
        title.setMaxWidth(Math.max(0, Math.round(copyWidth * .65f)));
        super.onMeasure(widthSpec, heightSpec);
    }
}
