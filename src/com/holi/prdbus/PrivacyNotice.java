package com.holi.prdbus;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.WeakHashMap;

/** Independent, device-local notice revisions. Neither is reset by an APK version or Key change. */
final class PrivacyNotice {
    static final String GENERAL = "定位用于查找附近站点、地图定位和行程进度。最近位置、收藏、搜索/规划历史和自填高德 Key 保存在本机。系统定位权限仍由你在 Android 中决定，未授权时可手动查站和选城市。\n\n"
            + "查询公交时，按功能需要向车来了、掌上公交等服务发送城市、关键词及查询位置；地图瓦片服务会接收视野对应的区域请求和 IP。高德功能另有一次使用说明。城际班次查询会将日期和起终站区间发给 12306。各服务按自己的政策处理请求。\n\n"
            + "应用没有登录和广告，不在后台记录或向开发者上传 GPS 轨迹。检查更新及下载安装包会访问开发者服务器；普通访问日志可能包含 IP、时间和请求路径。\n\n"
            + "可随时在“设置 → 关于隐私”重新查看说明。系统清除应用数据或卸载会清理本机记录；升级保留本机配置。";
    static final String AMAP = "验证或使用高德功能时，请求会将你的 Web 服务 Key 发送给高德；按所用功能还会包含搜索词、城市、起终点或相关位置，以及网络请求的 IP。高德将依据其隐私政策处理这些数据。\n\n"
            + "该 Key 使用你自己的服务权限和配额，免费额度、超额收费及接口可用性以高德控制台为准。Key 只在本机保存，不发送到开发者更新服务器；已有 Key 不会在设置中直接展示。\n\n"
            + "未配置 Key 仍可使用不依赖高德的公交查询和内置线网。此说明确认后，后续启动、升级或更换 Key 不重复弹出；可在“关于隐私”再次查看。";
    private static SharedPreferences prefs;
    private static volatile int noticeSeen, amapSeen;
    private static final Map<Activity, Dialog> active = new WeakHashMap<>();
    private PrivacyNotice() {}
    static synchronized void initialize(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences("privacy_notice", Context.MODE_PRIVATE);
        try { noticeSeen = prefs.getInt("notice_revision",0); amapSeen = prefs.getInt("amap_revision",0); }
        catch (RuntimeException invalid) { noticeSeen=0; amapSeen=0; }
    }
    static boolean locationAllowed() { return PrivacyRules.locationAllowed(noticeSeen); }
    static boolean amapNoticeSeen() { return amapSeen >= PrivacyRules.AMAP_REVISION; }
    static boolean amapAllowed() {
        return locationAllowed() && PrivacyRules.amapAllowed(AppSettings.keyConfigured(),amapSeen);
    }
    static boolean needsStartup() {
        return PrivacyRules.needsNotice(noticeSeen) || PrivacyRules.needsAmap(AppSettings.keyConfigured(),amapSeen);
    }
    static void ensureStartup(Activity activity,Runnable ready) {
        ensureGeneral(activity,()->{
            if(AppSettings.keyConfigured())ensureAmap(activity,ready);
            else run(activity,ready);
        });
    }
    private static void ensureGeneral(Activity activity,Runnable ready) {
        if(!valid(activity))return;
        if(!PrivacyRules.needsNotice(noticeSeen)){run(activity,ready);return;}
        show(activity,false,ready);
    }
    static void ensureAmap(Activity activity,Runnable ready) {
        if(!valid(activity))return;
        if(!locationAllowed()){ensureGeneral(activity,()->ensureAmap(activity,ready));return;}
        if(amapSeen>=PrivacyRules.AMAP_REVISION){run(activity,ready);return;}
        show(activity,true,ready);
    }
    private static void show(Activity activity,boolean amap,Runnable ready) {
        if(!valid(activity)||active.containsKey(activity))return;
        Dialog shown=new TransitDialog.Builder(activity)
                .setTitle(amap?"高德 Key 与数据使用说明":"关于本机记录与网络查询")
                .setMessage(amap?AMAP:GENERAL).setCancelable(false)
                .setPositiveButton("知道了",(dialog,which)->{
                    // Dismiss listeners may be delivered later by Android. Remove
                    // this notice before opening the next stage on the same Activity.
                    if(active.get(activity)==dialog)active.remove(activity);
                    if(amap)amapSeen=PrivacyRules.AMAP_REVISION; else noticeSeen=PrivacyRules.NOTICE_REVISION;
                    if(prefs!=null)prefs.edit().putInt(amap?"amap_revision":"notice_revision",
                            amap?amapSeen:noticeSeen).commit();
                    run(activity,ready);
                }).setOnDismissListener(dialog->{
                    if(active.get(activity)==dialog)active.remove(activity);
                }).show();
        active.put(activity,shown);
    }
    static void detach(Activity activity) {
        Dialog shown=active.remove(activity);
        if(shown!=null&&shown.isShowing())shown.dismiss();
    }
    private static boolean valid(Activity activity) { return activity!=null&&!activity.isFinishing()&&!activity.isDestroyed(); }
    private static void run(Activity activity,Runnable ready) { if(valid(activity)&&ready!=null)ready.run(); }
}
