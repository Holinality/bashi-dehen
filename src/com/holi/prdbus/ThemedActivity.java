package com.holi.prdbus;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
public abstract class ThemedActivity extends Activity {
    private boolean createdNight;
    @Override protected void attachBaseContext(Context base) {
        AppSettings.initialize(base);
        createdNight = AppSettings.night(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (createdNight ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        super.attachBaseContext(base.createConfigurationContext(config));
        Ui.setNight(createdNight);
    }
    @Override protected void onCreate(Bundle state) {
        setTheme(createdNight ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (AppSettings.night(getApplicationContext()) != createdNight) { recreate(); return; }
        else PrimaryNavigation.arrive(this);
        if (!(this instanceof MainActivity) && !(this instanceof SplashActivity)
                && !(this instanceof PrivacyActivity) && PrivacyNotice.needsStartup())
            PrivacyNotice.ensureStartup(this,this::recreate);
    }
    @Override protected void onPause() {
        PrimaryNavigation.stop(this);
        super.onPause();
    }
    @Override protected void onDestroy() {
        PrivacyNotice.detach(this);
        super.onDestroy();
    }
}
