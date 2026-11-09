package com.holi.prdbus;

import android.app.Activity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Button;
import android.widget.Toast;

final class RideCode {
    private static final String[] LABELS = {"支付宝", "云闪付"};
    private static final String[] PACKAGES = {"com.eg.android.AlipayGphone", "com.unionpay"};
    // Deep-link targets referenced from Trackr's TransitCodeHelper (MIT).
    private static final String[] LINKS = {
            "alipays://platformapi/startapp?appId=200011235", "upwallet://rn/rnhtmlridingcode"
    };

    private static SharedPreferences preferences(Activity activity) {
        return activity.getSharedPreferences("app_settings", Activity.MODE_PRIVATE);
    }

    static Button icon(Activity activity) {
        Button button = Ui.button(activity, "", Ui.RAISED, Ui.PRIMARY);
        Ui.icon(button, "ride-code", Ui.PRIMARY);
        button.setPadding(Ui.dp(activity, 14), 0, Ui.dp(activity, 14), 0);
        button.setCompoundDrawablePadding(0);
        button.setContentDescription("乘车码");
        button.setOnClickListener(v -> {
            int choice = preferences(activity).getInt("ride_code_provider", -1);
            if (choice >= 0 && choice < LABELS.length) launch(activity, choice);
            else choose(activity, true);
        });
        return button;
    }

    static void choose(Activity activity, boolean openAfterSave) {
        SharedPreferences prefs = preferences(activity);
        int[] selection = {prefs.getInt("ride_code_provider", -1)};
        new TransitDialog.Builder(activity)
                .setTitle("乘车码")
                .setSingleChoiceItems(LABELS, selection[0], (d, index) -> selection[0] = index)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, which) -> {
                    if (selection[0] < 0 || selection[0] >= LABELS.length) return;
                    prefs.edit().putInt("ride_code_provider", selection[0]).apply();
                    if (openAfterSave) launch(activity, selection[0]);
                }).show();

    }

    private static void launch(Activity activity, int choice) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LINKS[choice]))
                    .setPackage(PACKAGES[choice]));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Intent fallback = activity.getPackageManager().getLaunchIntentForPackage(PACKAGES[choice]);
            if (fallback != null) {
                try { activity.startActivity(fallback); return; }
                catch (ActivityNotFoundException | SecurityException ignored) { }
            }
            Toast.makeText(activity, "无法打开" + LABELS[choice], Toast.LENGTH_SHORT).show();
        }
    }
}
