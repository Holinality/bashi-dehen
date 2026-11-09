package com.holi.prdbus;

import android.content.Context;
import android.content.SharedPreferences;

final class WarmLocationStore {
    private static final String[] LEGACY_KEYS = {
            "warm_location_lat", "warm_location_lon", "warm_location_at", "warm_location_accuracy"
    };

    private WarmLocationStore() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("prdbus_location", Context.MODE_PRIVATE);
    }

    static void discardLegacyFix(Context context) {
        SharedPreferences legacy = context.getSharedPreferences("prdbus", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = null;
        for (String key : LEGACY_KEYS) {
            if (!legacy.contains(key)) continue;
            if (edit == null) edit = legacy.edit();
            edit.remove(key);
        }
        if (edit != null) edit.apply();
    }
}
