package com.holi.prdbus;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.*;

public final class SettingsActivity extends ThemedActivity {
    private static final int ALLOW_UPDATE_INSTALL = 51, INSTALL_UPDATE = 52;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content, header;
    private boolean validating;
    private TextView keyState, updateState;
    private EditText keyInput;
    private Button validate, update;
    private File verifiedApk;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.edgeToEdge(this);
        if (state != null) verifiedApk = VerifiedUpdate.publishedFile(this, state.getString("verifiedApk"));
        LinearLayout page = new LinearLayout(this); page.setOrientation(1); page.setBackgroundColor(Ui.BG);
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundColor(Ui.SURFACE);
        Button back = Ui.button(this, "", Ui.SURFACE, Ui.PRIMARY); Ui.icon(back, "back", Ui.PRIMARY);
        back.setContentDescription("返回"); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this,48), Ui.dp(this,48)));
        header.addView(label("设置", 22, true)); page.addView(header, Ui.matchWrap());
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(1); content.setPadding(d(16),d(16),d(16),d(28));
        scroll.addView(content, Ui.matchWrap()); page.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page); Ui.insets(this,header,8,16,4,6);
        appearance(); credentials();
        action(card("乘车码"), "更改默认应用", v -> RideCode.choose(this, false));
        action(card("关于隐私"), "本机记录与第三方数据使用", v -> startActivity(new Intent(this,PrivacyActivity.class)));
        updates();
    }
    private void appearance() {
        LinearLayout card = card("外观"); note(card, "选择白色、黑色，或跟随系统自动切换。");
        LinearLayout options = new LinearLayout(this); int selected = AppSettings.mode(this);
        String[] labels = {"自动", "白色", "黑色"};
        for(int i=0;i<3;i++) {
            final int mode=i;
            Button button=Ui.button(this, labels[i], i==selected ? Ui.PRIMARY : Ui.RAISED,
                    i==selected ? Ui.ON_PRIMARY : Ui.TEXT);
            button.setSelected(i==selected); button.setContentDescription(labels[i]+(i==selected?"，已选择":""));
            button.setOnClickListener(v->{ if(mode!=AppSettings.mode(this)){ AppSettings.setMode(this,mode); recreate(); } });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.textRowHeight(this,48),1); if(i>0)lp.leftMargin=d(8);
            options.addView(button,lp);
        }
        card.addView(options,Ui.matchWrap());
    }
    private void credentials() {
        LinearLayout card = card("高德 API");
        note(card,"可使用自己的 Web 服务 Key。注册和创建 Key 有免费配额；超额或部分服务可能收费，以控制台为准。已有 Key 不会在这里显示。");
        keyState=note(card,AppSettings.hasMapKey()?"已绑定个人 Key":"未绑定");
        keyInput=new EditText(this); keyInput.setSingleLine(true); keyInput.setTextSize(15);
        keyInput.setTextColor(Ui.TEXT); keyInput.setHintTextColor(Ui.MUTED);
        keyInput.setHint("粘贴新的 Web 服务 Key"); keyInput.setContentDescription("新的高德 Web 服务 Key");
        keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false); keyInput.setPadding(d(12),0,d(12),0);
        keyInput.setBackground(Ui.rounded(this,Ui.RAISED,12));
        card.addView(keyInput,new LinearLayout.LayoutParams(-1,Ui.textRowHeight(this,52)));
        validate=action(card,"验证并保存",v->validateKey());
        note(card,"1. 注册高德开放平台账号并完成所需认证。\n2. 控制台 → 应用管理 → 我的应用 → 创建应用。\n3. 添加 Key，服务平台选择「Web 服务」。\n4. 复制 Key 到上方验证。请确认线路查询、搜索及路线规划的权限和配额。");
        action(card,"打开注册与 Key 指南",v->open("https://lbs.amap.com/api/webservice/create-project-and-key"));
        action(card,"查看官方配额与计费",v->open("https://lbs.amap.com/upgrade"));
    }
    private void validateKey() {
        if(validating)return;
        final String key=keyInput.getText().toString().trim();
        if(!key.matches("[a-fA-F0-9]{32}")){keyState.setText("请输入完整的 32 位 Web 服务 Key");return;}
        if(!PrivacyNotice.amapNoticeSeen()) {
            PrivacyNotice.ensureAmap(this,this::validateKey);
            return;
        }
        validating=true; validate.setEnabled(false); keyInput.setEnabled(false);
        keyState.setText("正在验证 Web 服务 Key…");
        worker.execute(()->{
            String result; boolean accepted=false;
            try {
                JSONObject json=HttpJson.get("https://restapi.amap.com/v3/geocode/regeo?location=113.264,23.129&extensions=base&key="+key);
                if("1".equals(json.optString("status")) && json.has("regeocode")){
                    accepted=AppSettings.saveKey(key);
                    result=accepted?"已绑定。各接口的权限和免费额度请在高德控制台查看。":"验证通过，但保存失败，请重试";
                } else {
                    String code=json.optString("infocode","");
                    result="10001".equals(code)?"Key 无效或已过期":"10009".equals(code)?"Key 平台不匹配，请选择 Web 服务":
                            "10003".equals(code)?"该 Key 的日配额已用尽，请检查控制台":"验证未通过，请检查 Key 权限、白名单与配额";
                }
            } catch(Exception unavailable){result="暂时无法验证，请检查网络；现有配置仍保留";}
            final String message=result; final boolean saved=accepted;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return; validating=false;validate.setEnabled(true);keyInput.setEnabled(true);
                keyState.setText(message);if(saved)keyInput.setText("");});
        });
    }
    private void updates() {
        LinearLayout card=card("应用更新");
        String version=""; try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}
        note(card,"当前版本 "+version);
        if (!PublicBuildConfig.OFFICIAL_UPDATES) {
            note(card,"这是使用独立签名的源码构建版。请使用同一签名重新构建更新；正式版安装包使用维护者签名。");
            action(card,"打开项目下载页面",v->open("https://bus.holinality.com/download"));
            return;
        }
        updateState=note(card,"手动检查新版本，不在打开页面时自动请求。");
        update = action(card, verifiedApk == null ? "检查更新" : "继续安装", v -> {
            if (verifiedApk == null) checkUpdate();
            else installVerifiedUpdate();
        });
    }
    private void checkUpdate() {
        update.setEnabled(false);updateState.setText("正在检查…");
        worker.execute(()->{
            try {
                JSONObject data=HttpJson.get(UpdatePolicy.MANIFEST_URL);
                int local=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
                int remote=data.getInt("versionCode");
                JSONObject target=data.getJSONObject("packages").getJSONObject(getPackageName());
                String url=target.getString("url"), hash=target.getString("sha256");
                if(data.optInt("schema",0)!=1 || !UpdatePolicy.validDownload(url,hash))throw new IllegalStateException();
                String version=data.getString("versionName"), notes=data.optString("notes","");
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;update.setEnabled(true);
                    updateState.setText(remote<=local?"当前已是最新版本":"发现新版本 "+version);
                    if(remote>local)new TransitDialog.Builder(this).setTitle("新版本 "+version)
                            .setMessage(notes.length()>1500?notes.substring(0,1500):notes)
                            .setNegativeButton("稍后",null)
                            .setPositiveButton("下载并校验",(dialog,which)->downloadUpdate(url, hash, remote, version)).show();
                });
            } catch(Exception unavailable){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;update.setEnabled(true);updateState.setText("暂时无法检查更新，请稍后重试");});}
        });
    }

    private void downloadUpdate(String url, String hash, int versionCode, String versionName) {
        update.setEnabled(false);
        updateState.setText("正在下载安装包并核对版本、哈希与签名…");
        worker.execute(() -> {
            try {
                File result = VerifiedUpdate.download(getApplicationContext(), url, hash, versionCode, versionName);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    verifiedApk = result;
                    update.setEnabled(true);
                    update.setText("继续安装");
                    if (hasWindowFocus()) installVerifiedUpdate();
                    else updateState.setText("校验通过，返回此页可继续安装");
                });
            } catch (Exception unavailable) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    update.setEnabled(true);
                    updateState.setText("下载或校验失败，请重新检查更新");
                });
            }
        });
    }

    private void installVerifiedUpdate() {
        if (verifiedApk == null || !verifiedApk.isFile()) {
            verifiedApk = null;
            update.setText("检查更新");
            updateState.setText("安装包缓存已清理，请重新下载");
            return;
        }
        update.setEnabled(false);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                updateState.setText("校验通过。请在系统设置允许安装，返回后继续。");
                Intent permission = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(permission, ALLOW_UPDATE_INSTALL);
                return;
            }
            Uri uri = UpdateFileProvider.uri(this, verifiedApk);
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, UpdateFileProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(android.content.ClipData.newRawUri("已校验的安装包", uri));
            install.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            updateState.setText("校验通过，请在系统安装器确认更新");
            startActivityForResult(install, INSTALL_UPDATE);
        } catch (Exception unavailable) {
            update.setEnabled(true);
            updateState.setText("无法打开系统安装器，可稍后继续安装");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ALLOW_UPDATE_INSTALL) {
            update.setEnabled(true);
            if (android.os.Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
                installVerifiedUpdate();
            }
        } else if (request == INSTALL_UPDATE && verifiedApk != null) {
            update.setEnabled(true);
            if (result != RESULT_OK) {
                updateState.setText("可继续安装已校验的更新");
                return;
            }
            revokeUriPermission(UpdateFileProvider.uri(this, verifiedApk), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            verifiedApk.delete();
            verifiedApk = null;
            update.setText("检查更新");
            updateState.setText("更新安装已完成");
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (verifiedApk != null) state.putString("verifiedApk", verifiedApk.getName());
    }
    private LinearLayout card(String title) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(d(16),d(16),d(16),d(16));
        card.setBackground(Ui.edgeCard(this,Ui.PRIMARY,16));card.addView(label(title,18,true));content.addView(card,Ui.cardParams(this));return card;
    }
    private TextView label(String value,int size,boolean bold){TextView v=new TextView(this);v.setText(value);Ui.type(v,size,bold);v.setTextColor(Ui.TEXT);return v;}
    private TextView note(LinearLayout box,String text){TextView v=label(text,14,false);v.setTextColor(Ui.MUTED);v.setPadding(0,d(8),0,d(12));box.addView(v,Ui.matchWrap());return v;}
    private Button action(LinearLayout box,String text,View.OnClickListener listener){Button b=Ui.button(this,text,Ui.RAISED,Ui.PRIMARY);b.setOnClickListener(listener);LinearLayout.LayoutParams lp=Ui.matchWrap();lp.topMargin=d(8);box.addView(b,lp);return b;}
    private void open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception unavailable){Toast.makeText(this,"没有可用的浏览器",Toast.LENGTH_SHORT).show();}}
    private int d(int value){return Ui.dp(this,value);}
    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (isFinishing() && verifiedApk != null) verifiedApk.delete();
        super.onDestroy();
    }
}
