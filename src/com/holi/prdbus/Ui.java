package com.holi.prdbus;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.animation.ValueAnimator;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 2.0 native design tokens: one surface system, one motion grammar. */
final class Ui {
    static int PRIMARY = 0xFF2466A8;
    static int PRIMARY_DARK = 0xFF224F81;
    static int BG = 0xFFF3F7FC;
    static int SURFACE = Color.WHITE;
    static int RAISED = 0xFFEBF2FA;
    static int OUTLINE = 0xFFD6E3F1;
    static int TEXT = 0xFF25364A;
    static int MUTED = 0xFF506477;
    static int ACCENT = 0xFFA8521C;
    static int LIGHT_BLUE = 0xFFDFEDFC;
    static int LIGHT_ORANGE = 0xFFFCF0E5;
    static int GREEN = PRIMARY;
    static int VIOLET = PRIMARY;
    static int EDGE_CYAN = PRIMARY;
    static final int TITLE = 22, SECTION = 18, BODY = 15, META = 13, ARRIVAL = 26;
    static boolean NIGHT;
    static int ON_PRIMARY = 0xFFFFFFFF;
    static void setNight(boolean night) {
        NIGHT = night;
        PRIMARY = night ? 0xFF91BFFF : 0xFF2466A8;
        PRIMARY_DARK = night ? 0xFFABD1FF : 0xFF224F81;
        BG = night ? 0xFF121416 : 0xFFF3F7FC;
        SURFACE = night ? 0xFF1C1F22 : 0xFFFFFFFF;
        RAISED = night ? 0xFF272B30 : 0xFFEBF2FA;
        OUTLINE = night ? 0xFF41474E : 0xFFD6E3F1;
        TEXT = night ? 0xFFE7E9ED : 0xFF25364A;
        MUTED = night ? 0xFFB8BEC6 : 0xFF506477;
        ACCENT = night ? 0xFFFFB36B : 0xFFA8521C;
        LIGHT_BLUE = night ? 0xFF29313B : 0xFFDFEDFC;
        LIGHT_ORANGE = night ? 0xFF31281F : 0xFFFCF0E5;
        GREEN = VIOLET = EDGE_CYAN = PRIMARY;
        ON_PRIMARY = night ? 0xFF102747 : 0xFFFFFFFF;
    }
    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static int textRowHeight(Context context, int baseDp) {
        return dp(context, baseDp * Math.max(1f, context.getResources().getConfiguration().fontScale));
    }
    static Drawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(dp(context, Math.min(radiusDp, 16)));
        return surface;
    }
    static void routeBadge(TextView view, int colour, boolean metro, int radiusDp) {
        view.setBackground(rounded(view.getContext(), metro ? colour : LIGHT_BLUE, radiusDp));
        view.setTextColor(metro ? ReadableColors.foreground(colour) : PRIMARY_DARK);
    }
    static GradientDrawable edgeHeader(Context context, int accent) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(SURFACE);
        return surface;
    }
    static Drawable edgeCard(Context context, int accent, int radiusDp) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(SURFACE);
        surface.setCornerRadius(dp(context, 16));
        surface.setStroke(Math.max(1, dp(context, .6f)), OUTLINE);
        return surface;
    }
    static View edgeBackdrop(Context context) {
        View ground = new View(context);
        ground.setBackgroundColor(BG);
        ground.setClickable(false);
        return ground;
    }
    static GradientDrawable gradient(int[] colors) {
        GradientDrawable ground = new GradientDrawable();
        ground.setColor(colors.length == 0 ? BG : colors[0]);
        return ground;
    }
    static float motionScale(Context context) {
        try { return Math.max(0, Math.min(2, Settings.Global.getFloat(
                context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1))); }
        catch (Exception ignored) { return 1; }
    }
    static long duration(Context context, int millis) {
        return Math.round(millis * motionScale(context));
    }
    static android.view.animation.Interpolator easeOut() {
        return new PathInterpolator(.23f, 1f, .32f, 1f);
    }
    static void type(TextView view, int size, boolean emphasis) {
        view.setTextSize(size);
        view.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0f);
        view.setLineSpacing(dp(view.getContext(), 4), 1f);
    }
    static void icon(TextView view, String kind, int color) {
        if (view instanceof CenteredIconView) {((CenteredIconView)view).setIcon(kind,color);return;}
        Drawable icon = new TransitIcon(kind, color);
        int size = dp(view.getContext(), 20);
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawables(icon, null, null, null);
        view.setCompoundDrawablePadding(dp(view.getContext(), 8));
    }
    static int lineInk(int color) {
        int ink = color;
        if (NIGHT) {
            for (int i = 0; i < 24 && ReadableColors.contrast(ink, BG) < 4.5; i++)
                ink = 0xFF000000 | (Math.min(255, ((ink>>16)&255)+12)<<16)
                        | (Math.min(255, ((ink>>8)&255)+12)<<8) | Math.min(255, (ink&255)+12);
            return ink;
        }
        for (int i = 0; i < 20 && ReadableColors.contrast(ink, BG) < 4.5; i++)
            ink = 0xFF000000 | ((int)(((ink>>16)&255)*.88f)<<16)
                    | ((int)(((ink>>8)&255)*.88f)<<8) | (int)((ink&255)*.88f);
        return ink;
    }
    static void target(View view) {
        view.setMinimumHeight(dp(view.getContext(), 48));
        if (view instanceof TextView) ((TextView)view).setGravity(Gravity.CENTER);
        view.setFocusable(true);
    }
    static Drawable control(Context context, int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x182466A8),
                rounded(context, color, 12), null);
    }
    static void decorateButton(Button button, int fill, int ink) {
        button.setAllCaps(false);
        button.setTextColor(NIGHT && fill == PRIMARY ? ON_PRIMARY : ink);
        type(button, 14, true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(button.getContext(), 48));
        button.setPadding(dp(button.getContext(), 12), 0, dp(button.getContext(), 12), 0);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackground(control(button.getContext(), fill));
        pressFeedback(button);
    }
    static Button button(Context context, String text, int background, int foreground) {
        Button button = new Button(context);
        button.setText(text);
        decorateButton(button, background, foreground);
        return button;
    }
    static Button navButton(Context context, String label, String kind, boolean selected) {
        Button button = button(context, label, Color.TRANSPARENT, selected ? PRIMARY : MUTED);
        button.setSelected(selected);
        type(button, 12, selected);
        Drawable icon = new TransitIcon(kind, selected ? PRIMARY : MUTED);
        icon.setBounds(0, 0, dp(context, 22), dp(context, 22));
        button.setCompoundDrawables(null, icon, null, null);
        button.setCompoundDrawablePadding(dp(context, 5));
        button.setPadding(dp(context, 2), dp(context, 8), dp(context, 2), dp(context, 8));
        button.setOnTouchListener(null);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }
    static void navSelected(Button button, String kind, boolean selected) {
        if (button == null) return;
        button.setSelected(selected);
        button.setTextColor(selected ? PRIMARY : MUTED);
        Drawable icon = new TransitIcon(kind, selected ? PRIMARY : MUTED);
        icon.setBounds(0, 0, dp(button.getContext(),22), dp(button.getContext(),22));
        button.setCompoundDrawables(null, icon, null, null);
    }
    static void pressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            if (motionScale(v.getContext()) == 0) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !v.isInTouchMode()) return false;
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate().scaleX(.98f).scaleY(.98f).setDuration(duration(v.getContext(), 100))
                        .setInterpolator(easeOut()).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.animate().scaleX(1f).scaleY(1f).setDuration(duration(v.getContext(), 150))
                        .setInterpolator(easeOut()).start();
            }
            return false;
        });
    }
    // Frequently refreshed data never performs an entrance or moves away from the reader.
    static void animateIn(View view, Context context) {
        view.animate().cancel();
        view.setAlpha(1); view.setTranslationY(0); view.setRotationX(0);
    }
    static void springIn(View view, Context context) { animateIn(view, context); }
    static void staggerIn(View view, Context context, int index) { animateIn(view, context); }
    static void toggleReveal(View view, boolean show) {
        view.animate().cancel();
        if (motionScale(view.getContext()) == 0) {
            view.setAlpha(1); view.setVisibility(show ? View.VISIBLE : View.GONE); return;
        }
        if (show) {
            view.setAlpha(.7f); view.setVisibility(View.VISIBLE);
            view.animate().alpha(1).setDuration(duration(view.getContext(), 180))
                    .setInterpolator(easeOut()).start();
        } else view.animate().alpha(0).setDuration(duration(view.getContext(), 120))
                .withEndAction(() -> { view.setVisibility(View.GONE); view.setAlpha(1); }).start();
    }
    static LoadingDots loadingDots(Context context) { return new LoadingDots(context); }
    static View loadingCard(Context context) {
        android.widget.FrameLayout card = new android.widget.FrameLayout(context);
        card.setMinimumHeight(dp(context,160));
        android.widget.ProgressBar ring = new android.widget.ProgressBar(context);
        ring.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        card.addView(ring,new android.widget.FrameLayout.LayoutParams(dp(context,36),dp(context,36),Gravity.CENTER));
        card.setContentDescription("正在加载，请稍候"); return card;
    }
    static void mapControl(LinearLayout parent,String kind,String description,View.OnClickListener action) {
        Context context=parent.getContext();
        android.widget.ImageButton button=mapIcon(context,kind,description,action);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(context,48),dp(context,48));
        if(parent.getChildCount()>0)lp.topMargin=dp(context,4);parent.addView(button,lp);
    }
    static android.widget.ImageButton mapIcon(Context context,String kind,String description,View.OnClickListener action) {
        android.widget.ImageButton button=new android.widget.ImageButton(context);
        button.setImageDrawable(new TransitIcon(kind,TEXT));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(context,12),dp(context,12),dp(context,12),dp(context,12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x282466A8),edgeCard(context,PRIMARY,12),null));
        button.setMinimumWidth(dp(context,48));button.setMinimumHeight(dp(context,48));button.setStateListAnimator(null);
        button.setContentDescription(description);button.setOnClickListener(action);pressFeedback(button);
        return button;
    }
    static View centerLoader(Activity activity, View previous) {
        int visibility = previous.getVisibility();
        if (previous.getParent() instanceof ViewGroup) ((ViewGroup)previous.getParent()).removeView(previous);
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(activity);
        android.widget.FrameLayout panel = new android.widget.FrameLayout(activity);
        panel.setBackground(edgeCard(activity,PRIMARY,16));
        android.widget.ProgressBar ring = new android.widget.ProgressBar(activity);
        ring.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        panel.addView(ring,new android.widget.FrameLayout.LayoutParams(dp(activity,36),dp(activity,36),Gravity.CENTER));
        overlay.addView(panel,new android.widget.FrameLayout.LayoutParams(dp(activity,88),dp(activity,88),Gravity.CENTER));
        panel.setClickable(true);
        overlay.setClickable(false); overlay.setContentDescription("正在加载，请稍候"); overlay.setVisibility(visibility);
        ((ViewGroup)activity.findViewById(android.R.id.content)).addView(overlay,new ViewGroup.LayoutParams(-1,-1));
        return overlay;
    }
    static String updatedAt(long at) {
        return "更新 " + (at <= 0 ? "--:--:--" : new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(new java.util.Date(at)));
    }
    static final class LoadingDots extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        float phase;
        LoadingDots(Context context) {
            super(context);
            paint.setColor(PRIMARY);
            setContentDescription("正在加载");
            animator.setDuration(900);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> { phase = (Float)a.getAnimatedValue(); invalidate(); });
        }
        @Override protected void onVisibilityChanged(View v, int visibility) {
            super.onVisibilityChanged(v, visibility);
            if (animator == null) return;
            if (visibility == VISIBLE && isAttachedToWindow() && motionScale(getContext()) > 0) animator.start();
            else animator.cancel();
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (getVisibility() == VISIBLE && motionScale(getContext()) > 0) animator.start();
        }
        @Override protected void onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas canvas) {
            for (int i = 0; i < 3; i++) {
                paint.setAlpha((int)(90 + 165 * (.5 + .5 * Math.sin((phase - i * .2) * Math.PI * 2))));
                canvas.drawCircle(getWidth()/2f + dp(getContext(), (i-1)*9),
                        getHeight()/2f, dp(getContext(), 2.5f), paint);
            }
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(resolveSize(dp(getContext(), 38), w), resolveSize(dp(getContext(), 22), h));
        }
    }
    static View shimmerBar(Context context, int color) {
        View line = new View(context); line.setBackgroundColor(OUTLINE); return line;
    }
    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    static LinearLayout.LayoutParams cardParams(Context context) {
        LinearLayout.LayoutParams lp = matchWrap(); lp.bottomMargin = dp(context, 12); return lp;
    }
    static TextView emptyCard(Context context, String message) {
        TextView view = new TextView(context);
        view.setText(message); type(view, BODY, false); view.setTextColor(MUTED);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 20), dp(context, 24), dp(context, 20), dp(context, 24));
        view.setBackground(edgeCard(context, PRIMARY, 16)); return view;
    }
    static void transitionForward(Activity activity) {
        if (motionScale(activity) == 0) activity.overridePendingTransition(0,0);
        else activity.overridePendingTransition(R.anim.ios_push_in, R.anim.ios_push_out);
    }
    static void transitionBack(Activity activity) {
        if (motionScale(activity) == 0) activity.overridePendingTransition(0,0);
        else activity.overridePendingTransition(R.anim.ios_pop_in, R.anim.ios_pop_out);
    }
    static void transitionZoom(Activity activity) { transitionForward(activity); }
    static void edgeToEdge(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) activity.getWindow().setDecorFitsSystemWindows(false);
        else activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) flags = NIGHT ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags = NIGHT ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(SURFACE);
    }
    static void insets(Activity activity, View header, int left, int right, int top, int bottom) {
        activity.getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets) -> {
            int t, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                t = bars.top; b = bars.bottom;
            } else { t = insets.getSystemWindowInsetTop(); b = insets.getSystemWindowInsetBottom(); }
            if (header != null) header.setPadding(dp(activity,left), dp(activity,top)+t,
                    dp(activity,right), dp(activity,bottom));
            activity.findViewById(android.R.id.content).setPadding(0,0,0,b);
            return insets;
        });
    }
}
