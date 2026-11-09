package com.holi.prdbus;
import android.content.Context;
import android.content.res.Configuration;
final class AppSettings {
    private static android.content.SharedPreferences prefs;
    static volatile String customKey = "";
    static boolean keyConfigured() { return !customKey.isEmpty(); }
    static boolean hasMapKey() { return PrivacyNotice.amapAllowed(); }
    static String activeCity(android.content.Context context) {
        return context.getSharedPreferences("primary_navigation", Context.MODE_PRIVATE)
                .getString("cityName", "佛山");
    }

    static String activeProvince(Context context) {
        String city = activeCity(context);
        String saved = context.getSharedPreferences("primary_navigation", Context.MODE_PRIVATE)
                .getString("province", "");
        return saved.isEmpty() ? Regions.province(city) : saved;
    }

    static boolean hasMetro(Context context) {
        String province = activeProvince(context);
        return Regions.metroCities(province).length > 0;
    }
    static synchronized void initialize(Context context) {
        CityInfo.initialize(context);
        PrivacyNotice.initialize(context);
        prefs = context.getApplicationContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        customKey = prefs.getString("gaode_key", "");
    }
    static int mode(Context context) { return context.getSharedPreferences("app_settings", 0).getInt("theme", 0); }
    static boolean night(Context context) {
        int mode = mode(context);
        return mode == 2 || (mode == 0 && (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }
    static void setMode(Context context, int mode) {
        context.getSharedPreferences("app_settings", 0).edit().putInt("theme", mode).commit();
    }
    static synchronized boolean saveKey(String key) {
        if (key == null || !key.matches("[a-fA-F0-9]{32}")) return false;
        if (prefs == null || !prefs.edit().putString("gaode_key", key).commit()) return false;
        customKey = key; return true;
    }
    static String credentialUrl(String url) {
        String key = customKey;
        if (key.isEmpty() || !url.startsWith("https://restapi.amap.com/")) return url;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("([?&]key=)[^&]*").matcher(url);
        StringBuffer result = new StringBuffer();
        while (match.find()) match.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(match.group(1) + key));
        return match.appendTail(result).toString();
    }
}
