package com.holi.prdbus;

import android.Manifest;
import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** 轻量开屏：预热定位，初始化完成即进入，只有短暂过渡而不强制停留数秒。 */
public final class SplashActivity extends ThemedActivity {
    private final android.os.Handler handler = new android.os.Handler();
    private LocationManager locationManager;
    private LocationListener locationListener;
    private TextView locationState;
    private boolean leaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WarmLocationStore.discardLegacyFix(this);
        VerifiedUpdate.clearInstalled(this);
        Ui.edgeToEdge(this);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.gradient(new int[]{
                Ui.BG, Ui.BG, Ui.BG}));

        ParticleView particles = new ParticleView(this);
        frame.addView(particles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);

        TransitLogoView train = new TransitLogoView(this);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 92), Ui.dp(this, 92));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(train, logoLp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(30);
        title.setTextColor(Ui.TEXT);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 10), 0, 0);
        content.addView(title);


        locationState = new TextView(this);
        locationState.setText("正在准备公交查询…");
        locationState.setTextSize(12);
        locationState.setTextColor(Ui.PRIMARY);
        locationState.setGravity(Gravity.CENTER);
        locationState.setPadding(0, Ui.dp(this, 9), 0, 0);
        content.addView(locationState);

        Ui.LoadingDots dots = Ui.loadingDots(this);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.topMargin = Ui.dp(this, 26);
        dotsLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(dots, dotsLp);

        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);

        // 入场动画序列
        train.setAlpha(0f);
        train.setScaleX(.3f);
        train.setScaleY(.3f);
        train.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        title.setAlpha(0f);
        title.setTranslationY(Ui.dp(this, 30));
        title.animate().alpha(1f).translationY(0f).setDuration(260)
                .setStartDelay(40).setInterpolator(new OvershootInterpolator(1.1f)).start();
        locationState.setAlpha(0f);
        locationState.animate().alpha(1f).setDuration(220).setStartDelay(100).start();
        dots.setAlpha(0f);
        dots.animate().alpha(1f).setDuration(180).setStartDelay(120).start();

        // The home page owns live positioning; startup never waits for a GPS fix.
        PrivacyNotice.ensureStartup(this,()->{
            locationState.setText("正在提前获取位置…");
            warmLocation();
            handler.post(this::enterHome);
        });
    }

    private void enterHome() {
        if (leaving || isFinishing() || !PrivacyNotice.locationAllowed()) return;
        leaving = true;
        startActivity(new Intent(this, MainActivity.class));
        Ui.transitionZoom(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception ignored) {
        }
    }

    /** 已有定位权限时在开屏期间预热定位，首页无需再从默认城市跳动。 */
    private void warmLocation() {
        if (!PrivacyNotice.locationAllowed()) return;
        boolean granted = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            locationState.setText("进入首页后授权定位，即可查附近站点");
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        try {
            Location best = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (network != null && (best == null || network.getTime() > best.getTime())) best = network;
            if (best != null) saveWarmLocation(best);
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    saveWarmLocation(location);
                    try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        500, 0, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        500, 0, locationListener);
            }
        } catch (Exception ignored) {
            locationState.setText("定位将在首页继续");
        }
    }

    private void saveWarmLocation(Location location) {
        long age = System.currentTimeMillis() - location.getTime();
        if (age < -5000 || age > 120_000L || !location.hasAccuracy()
                || location.getAccuracy() > 500) return;
        WarmLocationStore.preferences(this).edit()
                .putLong("warm_location_lat", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("warm_location_lon", Double.doubleToRawLongBits(location.getLongitude()))
                .putLong("warm_location_at", location.getTime())
                .putFloat("warm_location_accuracy", location.getAccuracy())
                .apply();
        if (locationState != null) runOnUiThread(() -> {
            locationState.setText("位置已就绪 · 正在进入首页");
            handler.postDelayed(this::enterHome, 180);
        });
    }

    /** 自绘交通标志，避免不同手机 Emoji 风格破坏视觉一致性。 */
    private static final class TransitLogoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TransitLogoView(android.content.Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            paint.setColor(0x24238D7C);
            canvas.drawCircle(w / 2, w / 2, w * .48f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(0x55238D7C);
            canvas.drawCircle(w / 2, w / 2, w * .40f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(w * .25f, w * .20f, w * .75f, w * .70f,
                    12 * d, 12 * d, paint);
            paint.setColor(Ui.PRIMARY);
            canvas.drawRoundRect(w * .31f, w * .29f, w * .69f, w * .46f,
                    4 * d, 4 * d, paint);
            canvas.drawCircle(w * .36f, w * .61f, 4 * d, paint);
            canvas.drawCircle(w * .64f, w * .61f, 4 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4 * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(w * .36f, w * .73f, w * .27f, w * .82f, paint);
            canvas.drawLine(w * .64f, w * .73f, w * .73f, w * .82f, paint);
        }
    }

    /** 上升漂浮粒子。 */
    private static final class ParticleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private final float[] x;
        private final float[] y;
        private final float[] speed;
        private final float[] radius;
        private final int[] alpha;
        private final android.animation.ValueAnimator animator;

        ParticleView(android.content.Context context) {
            super(context);
            int n = 22;
            x = new float[n];
            y = new float[n];
            speed = new float[n];
            radius = new float[n];
            alpha = new int[n];
            for (int i = 0; i < n; i++) {
                reset(i, true);
            }
            paint.setColor(Ui.PRIMARY);
            animator = android.animation.ValueAnimator.ofInt(0, 100);
            animator.setDuration(6000);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> postInvalidateOnAnimation());
        }

        private void reset(int i, boolean first) {
            float w = getWidth() > 0 ? getWidth() : 1080;
            float h = getHeight() > 0 ? getHeight() : 2280;
            x[i] = random.nextFloat() * w;
            y[i] = first ? random.nextFloat() * h : h + Ui.dp(getContext(), 20);
            speed[i] = 0.4f + random.nextFloat() * 1.1f;
            radius[i] = 2f + random.nextFloat() * 5f;
            alpha[i] = 40 + random.nextInt(120);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            animator.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float h = getHeight();
            for (int i = 0; i < x.length; i++) {
                y[i] -= speed[i];
                if (y[i] < -20) reset(i, false);
                paint.setAlpha(alpha[i]);
                canvas.drawCircle(x[i], y[i], radius[i], paint);
            }
        }
    }
}
