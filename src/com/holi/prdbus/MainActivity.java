package com.holi.prdbus;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 巴士得很：地图 + 附近站点（车来了数据源，双向实时）。 */
public final class MainActivity extends ThemedActivity {
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final int MAX_NEARBY_SHOWN = 12;

    // 淡色系优雅配色
    private static final int LINE_COLOR = Color.rgb(55, 132, 201);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService nearbyWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService cityWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService mapWorker = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> pendingNearby;
    private java.util.concurrent.Future<?> pendingMap;
    private long lastNearbyStartedAt;
    private double lastNearbyLat, lastNearbyLon;
    private String lastNearbyCity = "";
    private long lastFixTimestamp;
    private final android.os.Handler locationHandler = new android.os.Handler();
    private final Runnable stopLocationTask = this::stopLocationUpdates;

    private MapView mapView;
    private TextView cityLabel;
    private EditText stationInput;
    private Button clearSearchButton;
    private String submittedSearch = "";
    private Button queryButton;
    private Button favBtn;
    private Button nearbyNav;
    private volatile boolean favoritesView; // 收藏列表视图开关（再点一次/返回键回到主页）
    private final List<FavoriteArrival> favoriteArrivals = new ArrayList<>();
    private boolean favoriteRefreshInFlight;
    private volatile boolean favoritePageActive;
    private List<ChelaileDataSource.StopDetail> confirmedPlatforms = java.util.Collections.emptyList();
    private String confirmedPlatformId = "";
    private View progress;
    private TextView status;
    private View homeStateRow;
    private TextView nearbyUpdateLabel;
    private long nearbyUpdatedElapsed;
    private long manualFixRequestAt;
    private LinearLayout resultList;
    private ScrollView resultsScroll;
    private LinearLayout headerBox;
    private LinearLayout mapArea;
    private LinearLayout linesBox;
    private String forcedCityId = null;
    private String forcedCityName = null;
    private double forcedSearchLat = Double.NaN;
    private double forcedSearchLon = Double.NaN;
    private String lastSearchCityId = null;
    private String lastSearchCityName = null;
    private double lastSearchLat;
    private double lastSearchLon;
    private int mapSmallHeight;
    private int mapExpandedHeight;
    private boolean mapExpanded;

    private List<ChelaileDataSource.NearbyStop> nearbyStops = new ArrayList<>();
    private List<ChelaileDataSource.NearbyStop> visibleStops = new ArrayList<>();
    private ChelaileDataSource.NearbyStop selectedStop = null;
    private String selectedCityId = "019";
    private double selectedStopLat = 0;
    private double selectedStopLon = 0;

    private CityInfo currentCity = CityInfo.PRD_CITIES[2]; // 默认佛山
    /** 手动选城后 GPS 只更新用户标记，不再覆盖正在浏览的城市。 */
    private boolean manualCitySelection;
    private LinearLayout primaryBar;
    private boolean resumedBefore;
    private boolean privacyReady, mainResumed;
    private int citySelectionGeneration;
    private double lastCityLookupLat, lastCityLookupLon;
    private long lastCityLookupAt;
    private double currentLat = 23.021;
    private double currentLon = 113.122;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private android.content.SharedPreferences prefs;
    private long lastAcceptedLocationAt;
    private float lastAcceptedAccuracy = Float.MAX_VALUE;
    private long stationDataUpdatedAt;
    private volatile int nearbyRequestGeneration;
    private volatile int stopRequestGeneration;
    private boolean restoredWarmLocation;
    private boolean suppressNextNearbyAnimation;
    private boolean suppressNextStopAnimation;
    private boolean homeRefreshInFlight;
    /** 搜索结果展示期间锁住列表，避免附近站 15 秒刷新把用户正在看的结果顶掉。 */
    private boolean searchResultsActive;
    private volatile int searchRequestGeneration;
    private java.util.concurrent.Future<?> pendingSearch;
    private View searchSnapshot;
    private String searchSnapshotStatus;
    private List<ChelaileDataSource.NearbyStop> searchSnapshotStops;
    private List<NearbyStation> searchSnapshotMarkers;
    private boolean selectedFromSearch;
    private ValueAnimator mapSizeAnimator;

    private void invalidateHomeRequests() {
        lastNearbyCity = "";
        if (pendingNearby != null) pendingNearby.cancel(true);
        if (pendingMap != null) pendingMap.cancel(true);
        nearbyRequestGeneration++;
        stopRequestGeneration++;
        searchRequestGeneration++;
        if (pendingSearch != null) pendingSearch.cancel(true);
        homeRefreshInFlight = false;
        setLoading(false);
    }
    private final Handler homeRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable homeRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (favoritesView && !isFinishing()) refreshFavoriteArrivals();
            if (!isFinishing() && !favoritesView && !searchResultsActive) {
                if (selectedStop != null && !selectedStop.subway) {
                    // 展开的公交站：仅静默重取下面所有线路的双向到站时间。
                    onStopSelected(selectedStop, true);
                }
            }
            homeRefreshHandler.postDelayed(this, 15_000L);
        }
    };
    private final Runnable homeAgeTask = new Runnable() {
        @Override public void run() {
            updateNearbyAge();
            homeRefreshHandler.postDelayed(this, 1_000L);
        }
    };

    private int statusBarH = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyEdgeToEdge();
        android.widget.FrameLayout waiting=new android.widget.FrameLayout(this);
        waiting.setBackgroundColor(Ui.BG);setContentView(waiting);
        PrivacyNotice.ensureStartup(this,()->{
            if(privacyReady||isFinishing()||isDestroyed())return;
            privacyReady=true;initializeHome();
            if(mainResumed)resumeHome();
        });
    }

    private void initializeHome() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        prefs = getSharedPreferences("prdbus", MODE_PRIVATE);
        restoreCity();
        restoreWarmLocation();
        applyEdgeToEdge();
        buildUi();
        applyInsets();
        mapView.centerOn(currentLat, currentLon, 16);
        if (manualCitySelection && TransitRules.validCoordinate(currentCity.lat, currentCity.lon)) {
            mapView.centerOn(currentCity.lat, currentCity.lon, 15);
        }
        if (restoredWarmLocation) {
            mapView.setUserLocation(currentLat, currentLon);
            status.setText("已显示上次定位 · 正在更新附近站点…");
            loadNearbyStations();
            resolveLocatedCity(currentLat, currentLon);
        }
        requestLocationIfNeeded();
        MetroDataSource.prefetch(this);
        applyPrimaryTab(getIntent());
        offerMapSetup();
    }

    private void restoreCity() {
        String name = AppSettings.activeCity(this);
        String id = prefs.getString("chosen_city_id", "");
        if (id.isEmpty() || name.isEmpty()) return;
        Regions.remember(name, AppSettings.activeProvince(this));
        currentCity = CityInfo.register(name, id,
                Double.longBitsToDouble(prefs.getLong("chosen_city_lat", 0)),
                Double.longBitsToDouble(prefs.getLong("chosen_city_lon", 0)));
        selectedCityId = currentCity.chelaileId;
        manualCitySelection = prefs.getBoolean("chosen_city_manual", false);
    }

    private void offerMapSetup() {
        if (prefs.getBoolean("amap_onboarding_v1", false)) return;
        prefs.edit().putBoolean("amap_onboarding_v1", true).apply();
        if (AppSettings.hasMapKey()) return;
        new TransitDialog.Builder(this).setTitle("启用地图与路线规划")
                .setMessage("可在高德开放平台申请个人 Web 服务 Key，使用免费额度。绑定后可启用高德地图和路线规划。")
                .setPositiveButton("去绑定", (dialog, which) -> startActivity(new Intent(this, SettingsActivity.class)))
                .setNegativeButton("稍后", null).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (privacyReady) applyPrimaryTab(intent);
    }

    private void applyPrimaryTab(Intent intent) {
        if (intent == null || !intent.hasExtra(PrimaryNavigation.TAB)) return;
        int selected = intent.getIntExtra(PrimaryNavigation.TAB, 0);
        if (selected == 2 && !favoritesView) showFavorites();
        else if (selected == 0 && (favoritesView || searchResultsActive || selectedStop != null)) backToHome();
    }

    private void applyEdgeToEdge() { Ui.edgeToEdge(this); }

    /** 读取开屏阶段预热的定位，让首页第一帧就落在用户附近。 */
    private void restoreWarmLocation() {
        WarmLocationStore.discardLegacyFix(this);
        android.content.SharedPreferences location = WarmLocationStore.preferences(this);
        long at = location.getLong("warm_location_at", 0L);
        if (at <= 0L || at > System.currentTimeMillis()
                || System.currentTimeMillis() - at > 30 * 60_000L) return;
        double lat = Double.longBitsToDouble(location.getLong("warm_location_lat", 0L));
        double lon = Double.longBitsToDouble(location.getLong("warm_location_lon", 0L));
        if (!TransitRules.validCoordinate(lat, lon)) return;
        currentLat = lat;
        currentLon = lon;
        CityInfo city = CityInfo.nearest(lat, lon);
        if (city != null && !manualCitySelection && !prefs.contains("chosen_city_id")) {
            currentCity = city;
            selectedCityId = city.chelaileId;
        }
        restoredWarmLocation = true;
    }

    private void applyInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                statusBarH = bars.top;
                findViewById(android.R.id.content).setPadding(0, 0, 0, bars.bottom);
            } else {
                statusBarH = insets.getSystemWindowInsetTop();
                findViewById(android.R.id.content).setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            }
            if (headerBox != null) {
                headerBox.setPadding(dp(16), dp(7) + statusBarH, dp(16), dp(9));
            }
            return insets;
        });
    }

    private void buildUi() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.TRANSPARENT);

        // ── 标题栏 ────────────────────────────────────
        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(dp(16), dp(7), dp(16), dp(9));
        headerBox.setBackgroundColor(Ui.SURFACE);

        PrimaryHeader titleRow = new PrimaryHeader(this, getString(R.string.app_name), currentCity.name, v -> showCityPicker());
        cityLabel = titleRow.cityLabel;
        headerBox.addView(titleRow, matchWrap());

        // 搜索行（融入标题栏）
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        stationInput = new EditText(this);
        stationInput.setSingleLine(true);
        stationInput.setHint("搜索线路或站点");
        stationInput.setContentDescription("搜索公交线路或站点");
        stationInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        stationInput.setTextSize(15);
        stationInput.setTextColor(TEXT);
        stationInput.setHintTextColor(MUTED);
        stationInput.setInputType(InputType.TYPE_CLASS_TEXT);
        stationInput.setPadding(dp(16), 0, dp(48), 0);
        stationInput.setBackground(Ui.rounded(this, Ui.RAISED, 15));
        searchRow.setPadding(0, 0, 0, 0);
        android.widget.FrameLayout searchField = new android.widget.FrameLayout(this);
        searchField.addView(stationInput,new android.widget.FrameLayout.LayoutParams(-1,-1));
        clearSearchButton = Ui.button(this,"",Color.TRANSPARENT,MUTED);
        Ui.icon(clearSearchButton,"close",MUTED); clearSearchButton.setCompoundDrawablePadding(0);
        clearSearchButton.setPadding(dp(14),0,dp(14),0);
        clearSearchButton.setContentDescription("清除搜索并返回附近站点");
        clearSearchButton.setVisibility(View.GONE);
        clearSearchButton.setOnClickListener(v -> {
            searchResultsActive=false; submittedSearch=""; searchSnapshot=null;
            stationInput.setText(""); stationInput.clearFocus(); hideKeyboard();
            forcedCityId=null;forcedCityName=null;forcedSearchLat=Double.NaN;forcedSearchLon=Double.NaN;
            mapView.setStations(java.util.Collections.emptyList());
            if(TransitRules.validCoordinate(currentLat,currentLon))mapView.centerOn(currentLat,currentLon,16);
            backToHome();
        });
        searchField.addView(clearSearchButton,new android.widget.FrameLayout.LayoutParams(dp(48),-1,Gravity.END));
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, dp(48), 1f));
        stationInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count) {
                clearSearchButton.setVisibility(value.length()>0?View.VISIBLE:View.GONE);
                if(searchResultsActive && !value.toString().trim().equals(submittedSearch)) {
                    searchRequestGeneration++; if(pendingSearch!=null)pendingSearch.cancel(true);
                    setLoading(false); searchSnapshot=null; selectedStop=null; clearResults();
                    status.setText("点击搜索查看结果");
                }
            }
            public void afterTextChanged(android.text.Editable value){}
        });
        queryButton = button("搜索", PRIMARY, Color.WHITE);
        queryButton.setTextSize(14);
        LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(dp(72), dp(48));
        queryLp.setMargins(dp(10), 0, 0, 0);
        searchRow.addView(queryButton, queryLp);
        queryButton.setOnClickListener(v -> queryByInput());
        stationInput.setOnEditorActionListener((v, actionId, event) -> {
            if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || actionId==android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || (event!=null&&event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER
                        &&event.getAction()==android.view.KeyEvent.ACTION_DOWN)){
                queryByInput();return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchLp = matchWrap();
        searchLp.topMargin = dp(7);
        headerBox.addView(searchRow, searchLp);

        page.addView(headerBox, matchWrap());
        View divider = new View(this);
        divider.setBackgroundColor(Ui.OUTLINE);
        page.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 地图与线路详情保持相同高度：默认 190dp，操作时展开到屏幕 3/8。
        mapArea = new LinearLayout(this);
        mapArea.setOrientation(LinearLayout.VERTICAL);
        mapArea.setBackground(Ui.edgeCard(this, Ui.EDGE_CYAN, 22));
        mapArea.setPadding(dp(3), dp(3), dp(3), dp(3));
        mapArea.setElevation(0);
        mapArea.setClipToOutline(true);
        mapSmallHeight = dp(190);
        mapExpandedHeight = Math.max(mapSmallHeight, screenH * 3 / 8);
        mapExpanded = false;
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mapSmallHeight);
        mapLp.setMargins(dp(16), dp(2), dp(16), dp(8));

        android.widget.FrameLayout mapFrame = new android.widget.FrameLayout(this);
        mapView = new MapView(this);
        mapView.setAvailabilityTarget(mapArea);
        mapView.setMapGestureListener(this::expandMap);
        // 点地图标记 = 点下方列表对应站点（相互对应）
        mapView.setStationTapListener(station -> {
            ChelaileDataSource.NearbyStop target = null;
            for (ChelaileDataSource.NearbyStop s : visibleStops) {
                if (s.name != null && s.name.equals(station.name)) {
                    target = s;
                    break;
                }
            }
            if (target == null) return;
            onStopSelected(target);
        });
        mapFrame.addView(mapView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(4), dp(4), dp(4), dp(4));
        controls.setElevation(dp(6));
        addControlButton(controls, "+", v -> mapView.zoomIn());
        addControlButton(controls, "−", v -> mapView.zoomOut());
        addControlButton(controls, "◎", v -> relocateFromMap());
        android.widget.FrameLayout.LayoutParams controlLp = new android.widget.FrameLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        controlLp.rightMargin = dp(16);
        controlLp.bottomMargin = dp(14);
        mapFrame.addView(controls, controlLp);
        mapArea.addView(mapFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page.addView(mapArea, mapLp);

        // ── 状态行 ────────────────────────────────────
        LinearLayout stateRow = new LinearLayout(this);
        homeStateRow = stateRow;
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setPadding(dp(20), dp(8), dp(20), dp(10));
        progress = Ui.loadingDots(this);
        progress.setVisibility(View.GONE);
        stateRow.addView(progress, new LinearLayout.LayoutParams(dp(74), dp(22)));
        status = new TextView(this);
        status.setText("定位后显示附近站点实时车辆");
        status.setTextSize(14);
        status.setTextColor(TEXT);
        status.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        status.setLetterSpacing(0f);
        status.setTextSize(Ui.META);
        status.setTextColor(MUTED);
        status.setPadding(dp(8), 0, 0, 0);
        stateRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(stateRow, matchWrap());

        // ── 结果区 ────────────────────────────────────
        ScrollView resultsScroll = new ScrollView(this);
        this.resultsScroll = resultsScroll;
        final float[] scrollTouchY = new float[1];
        resultsScroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scrollTouchY[0] = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && scrollTouchY[0] - event.getY() > dp(12)) {
                collapseMap();
                scrollTouchY[0] = event.getY();
            }
            return false;
        });
        resultList = new LinearLayout(this);
        resultList.setOrientation(LinearLayout.VERTICAL);
        resultList.setPadding(dp(16), dp(2), dp(16), dp(28));
        resultsScroll.addView(resultList, matchWrap());
        page.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(8), 0, dp(8), 0);
        navigation.setBackgroundColor(Ui.SURFACE);
        nearbyNav = Ui.navButton(this, "附近", "pin", true);
        nearbyNav.setOnClickListener(v -> PrimaryNavigation.switchContent(this, 0, this::backToHome));
        Button metroNav = Ui.navButton(this, "地铁", "metro", false);
        metroNav.setOnClickListener(v -> { rememberPrimaryContext(); PrimaryNavigation.open(this, 1); });
        favBtn = Ui.navButton(this, "收藏", "star", false);
        favBtn.setOnClickListener(v -> { if (!favoritesView) PrimaryNavigation.switchContent(this, 2, this::showFavorites); });
        Button plannerNav = Ui.navButton(this, "规划", "route", false);
        plannerNav.setOnClickListener(v -> openTransitPlanner());
        for (Button item : new Button[]{nearbyNav, metroNav, favBtn, plannerNav})
            navigation.addView(item, new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 64), 1f));
        page.addView(navigation, matchWrap());
        PrimaryNavigation.bind(page, navigation, 0);
        primaryBar = navigation;
        PrimaryNavigation.updateAvailability(this, primaryBar);

        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shell);
        progress = Ui.centerLoader(this, progress);

    }

    private void openTransitPlanner() {
        rememberPrimaryContext();
        PrimaryNavigation.open(this, 3);
    }

    private void rememberPrimaryContext() {
        PrimaryNavigation.remember(this, selectedCityId, currentCity.name, currentLat, currentLon,
                restoredWarmLocation || lastAcceptedLocationAt > 0);
    }

    private void rememberCity() {
        CityInfo.persist();
        rememberPrimaryContext();
        prefs.edit().putString("chosen_city_id", selectedCityId)
                .putBoolean("chosen_city_manual", manualCitySelection)
                .putLong("chosen_city_lat", Double.doubleToRawLongBits(currentCity.lat))
                .putLong("chosen_city_lon", Double.doubleToRawLongBits(currentCity.lon)).apply();
        PrimaryNavigation.updateAvailability(this, primaryBar);
    }

    private void addControlButton(LinearLayout parent, String label, View.OnClickListener listener) {
        String kind = "+".equals(label) ? "plus" : "−".equals(label) ? "minus" : "locate";
        Ui.mapControl(parent,kind,"+".equals(label)?"放大地图":"−".equals(label)?"缩小地图":"定位到我的位置",
                v -> {expandMap();listener.onClick(v);});
    }
    private void collapseMap() { animateMapHeight(mapSmallHeight,false); }
    private void expandMap() { animateMapHeight(mapExpandedHeight,true); }
    private void animateMapHeight(int target,boolean expanded) {
        if(mapArea==null||mapArea.getVisibility()!=View.VISIBLE||mapExpanded==expanded)return;
        ViewGroup.LayoutParams params=mapArea.getLayoutParams();
        if(params==null||params.height==target){mapExpanded=expanded;return;}
        if(mapSizeAnimator!=null)mapSizeAnimator.cancel();
        mapSizeAnimator=ValueAnimator.ofInt(params.height,target);
        mapSizeAnimator.setDuration(Ui.duration(this,220));
        mapSizeAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mapSizeAnimator.addUpdateListener(animation->{ViewGroup.LayoutParams current=mapArea.getLayoutParams();
            current.height=(Integer)animation.getAnimatedValue();mapArea.setLayoutParams(current);});
        mapExpanded=expanded;mapSizeAnimator.start();
    }
    private void focusStationOnMap(String name) {
        if(name==null)return;
        mapView.setSelectedStation(name);NearbyStation station=mapView.findStation(name);
        if(station!=null)mapView.centerOn(station.lat,station.lon,16);
    }
    private void showCityPicker() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(12), dp(20), dp(26));
        sheet.setBackground(rounded(Ui.SURFACE, 24));

        View handle = new View(this);
        handle.setBackground(rounded(Color.rgb(218, 224, 231), 3));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(42), dp(5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(18);
        sheet.addView(handle, handleLp);

        TextView title = new TextView(this);
        title.setText("选择出行城市");
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sheet.addView(title, matchWrap());

        TextView note = new TextView(this);
        note.setText("当前 " + currentCity.name);
        note.setTextSize(12);
        note.setTextColor(MUTED);
        note.setPadding(0, dp(4), 0, dp(14));
        sheet.addView(note, matchWrap());

        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("搜索省份");
        filter.setTextColor(TEXT);
        filter.setHintTextColor(MUTED);
        sheet.addView(filter, matchWrap());
        android.widget.ListView cities = new android.widget.ListView(this);
        sheet.addView(cities, new LinearLayout.LayoutParams(-1, dp(290)));
        java.util.Map<String, ChelaileDataSource.City> available = new java.util.TreeMap<>(
                java.text.Collator.getInstance(java.util.Locale.SIMPLIFIED_CHINESE));
        for (CityInfo city : CityInfo.all()) {
            available.put(city.name, new ChelaileDataSource.City(city.chelaileId, city.name));
        }
        List<ChelaileDataSource.City> visible = new ArrayList<>();
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(TEXT);
                return row;
            }
        };
        cities.setAdapter(adapter);
        final String[] selectedProvince = {""};
        List<String> visibleProvinces = new ArrayList<>();
        TextView provinceBack = new TextView(this);
        provinceBack.setTextColor(PRIMARY);
        provinceBack.setTextSize(16);
        provinceBack.setPadding(0, dp(12), 0, dp(12));
        sheet.addView(provinceBack, 3, matchWrap());
        Runnable update = () -> {
            visible.clear();
            visibleProvinces.clear();
            adapter.clear();
            String keyword = Regions.normalizeCity(filter.getText().toString());
            provinceBack.setVisibility(selectedProvince[0].isEmpty() ? View.GONE : View.VISIBLE);
            provinceBack.setText("‹  省份  ·  " + ("other".equals(selectedProvince[0]) ? "其他地区" : Regions.provinceName(selectedProvince[0])));
            if (selectedProvince[0].isEmpty()) {
                java.util.Set<String> codes = new java.util.TreeSet<>();
                for (ChelaileDataSource.City city : available.values()) {
                    String code = Regions.province(city.name);
                    codes.add(code.isEmpty() ? "other" : code);
                }
                for (String code : codes) {
                    String name = "other".equals(code) ? "其他地区" : Regions.provinceName(code);
                    if (!name.contains(keyword)) continue;
                    visibleProvinces.add(code);
                    adapter.add(name + "  ›");
                }
                return;
            }
            for (ChelaileDataSource.City city : available.values()) {
                String code = Regions.province(city.name);
                if (!selectedProvince[0].equals(code.isEmpty() ? "other" : code)) continue;
                if (!city.name.contains(keyword)) continue;
                visible.add(city);
                adapter.add(city.name.equals(currentCity.name) ? city.name + "  ✓" : city.name);
            }
        };
        provinceBack.setOnClickListener(v -> {
            selectedProvince[0] = "";
            filter.setHint("搜索省份");
            filter.setText("");
            update.run();
        });
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        cities.setOnItemClickListener((parent, row, position, id) -> {
            if (selectedProvince[0].isEmpty()) {
                selectedProvince[0] = visibleProvinces.get(position);
                filter.setHint("搜索市县");
                filter.setText("");
                update.run();
                return;
            }
            ChelaileDataSource.City chosen = visible.get(position);
            dialog.dismiss();
            int generation = ++citySelectionGeneration;
            CityInfo known = CityInfo.find(chosen.name);
            switchCity(CityInfo.register(chosen.name, chosen.cityId,
                    known == null ? 0 : known.lat, known == null ? 0 : known.lon));
            if (!TransitRules.validCoordinate(currentCity.lat, currentCity.lon)) cityWorker.execute(() -> {
                CityInfo resolved = CityLocator.choose(this, chosen);
                postUi(() -> {
                    if (!isFinishing() && !isDestroyed() && generation == citySelectionGeneration
                            && TransitRules.validCoordinate(resolved.lat, resolved.lon)) switchCity(resolved);
                });
            });
        });
        update.run();
        cityWorker.execute(() -> {
            List<ChelaileDataSource.City> supported = new ArrayList<>();
            try { supported.addAll(ChelaileDataSource.queryCities()); } catch (Exception ignored) { }
            try { supported.addAll(MygolbsDataSource.cities()); } catch (Exception ignored) { }
            postUi(() -> {
                if (!dialog.isShowing()) return;
                for (ChelaileDataSource.City city : supported) {
                    String name = Regions.normalizeCity(city.name);
                    if (name.isEmpty()) continue;
                    if (city.cityId.startsWith("mg:") && available.containsKey(name)) continue;
                    available.put(name, new ChelaileDataSource.City(city.cityId, name));
                    CityInfo known = CityInfo.find(name);
                    CityInfo.register(name, city.cityId, known == null ? 0 : known.lat, known == null ? 0 : known.lon);
                }
                update.run();
                CityInfo.persist();
            });
        });

        dialog.setContentView(sheet);
        sheet.setFocusableInTouchMode(true);
        sheet.requestFocus();
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = .46f;
            attrs.gravity = Gravity.BOTTOM;
            window.setAttributes(attrs);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        sheet.setTranslationY(dp(80));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0f).alpha(1f).setDuration(Ui.duration(this, 220))
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void switchCity(CityInfo city) {
        invalidateHomeRequests();
        favoritesView = false;
        favBtn.setText("收藏");
        currentCity = city;
        selectedCityId = city.chelaileId;
        manualCitySelection = true;
        searchResultsActive = false;
        cityLabel.setText(city.name + "  ▾");
        if (TransitRules.validCoordinate(city.lat, city.lon)) mapView.centerOn(city.lat, city.lon, 15);
        rememberCity();
        selectedStop = null;
        expandMap();
        clearResults();
        if (RegionalBusActivity.supports(city.chelaileId)) {
            status.setText("搜索线路号或巴士站名");
            RegionalBusActivity.open(this, city.chelaileId, "");
            return;
        }
        status.setText("已切换至" + city.name + " · 加载附近站点…");
        loadNearbyStations();
    }

    // ── 定位 ──────────────────────────────────────────
    private void requestLocationIfNeeded() {
        if (!PrivacyNotice.locationAllowed() || !privacyReady) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION_PERMISSION);
        } else {
            locate();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (hasLocationPermission()) {
                locate();
            } else {
                Toast.makeText(this, "未授予定位权限，默认定位佛山", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void locate() {
        if (!PrivacyNotice.locationAllowed() || !privacyReady) return;
        if (!hasLocationPermission()) {
            Toast.makeText(this, "需要定位权限才能显示附近站点", Toast.LENGTH_SHORT).show();
            requestLocationIfNeeded();
            return;
        }
        invalidateHomeRequests();
        selectedStop = null;
        favoritesView = false;
        PrimaryNavigation.selected(this, 0);
        favBtn.setText("收藏");
        status.setText("正在定位…");
        stopLocationUpdates();
        try {
            // 用户主动点击定位时恢复跟随 GPS 所在城市。
            manualCitySelection = false;
            prefs.edit().putBoolean("chosen_city_manual", false).apply();
            citySelectionGeneration++;
            lastCityLookupAt = 0;
            searchResultsActive = false;
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (network != null && (last == null || network.getTime() - last.getTime() > 30_000L
                    || (Math.abs(network.getTime() - last.getTime()) <= 30_000L
                    && network.hasAccuracy() && (!last.hasAccuracy()
                    || network.getAccuracy() < last.getAccuracy())))) last = network;
            if (last != null && System.currentTimeMillis() - last.getTime() < 5 * 60 * 1000L) {
                acceptLocation(last);
            }
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        acceptLocation(location);
                        // Keep listening for GPS refinement after a coarse network fix.
                        if (lastFixTimestamp == location.getTime()
                                && location.hasAccuracy() && location.getAccuracy() <= 25
                                && System.currentTimeMillis() - location.getTime() < 30_000L)
                            stopLocationUpdates();
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            boolean requested = false;
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
                    requested = true;
                }
            } catch (Exception ignored) {
            }
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 1000, 0, locationListener);
                    requested = true;
                }
            } catch (Exception ignored) {
            }
            if (requested) locationHandler.postDelayed(stopLocationTask, 30_000L);
            if (!requested && last == null) {
                status.setText("定位服务未开启 · 可手动选择城市");
                Toast.makeText(this, "请开启系统定位服务", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException ignored) {
            Toast.makeText(this, "定位失败，请检查权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void relocateFromMap() {
        invalidateHomeRequests();
        manualFixRequestAt = System.currentTimeMillis();
        searchResultsActive = false;
        stationInput.setText("");
        stationInput.clearFocus();
        hideKeyboard();
        forcedCityId = null; forcedCityName = null;
        forcedSearchLat = Double.NaN; forcedSearchLon = Double.NaN;
        if (lastAcceptedLocationAt > 0 || restoredWarmLocation) {
            mapView.setUserLocation(currentLat, currentLon);
            mapView.centerOn(currentLat, currentLon, 16);
        }
        locate();
    }

    /** 接受更近/更新的定位，过滤网络与 GPS 连续回调造成的重复请求。 */
    private void acceptLocation(Location location) {
        long now = System.currentTimeMillis();
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 1000f;
        if (!LocationQuality.accept(location.getLatitude(), location.getLongitude(), accuracy,
                location.getTime(), now, lastFixTimestamp,
                lastAcceptedLocationAt, lastAcceptedAccuracy)) return;
        if (manualFixRequestAt <= 0 && lastAcceptedLocationAt > 0 && now - lastAcceptedLocationAt < 15_000L) {
            double moved = GeoUtils.distanceKm(currentLat, currentLon,
                    location.getLatitude(), location.getLongitude()) * 1000.0;
            if (moved < 30 && accuracy >= lastAcceptedAccuracy) return;
        }
        lastAcceptedLocationAt = now;
        lastAcceptedAccuracy = accuracy;
        lastFixTimestamp = location.getTime();
        if (manualFixRequestAt > 0 && location.getTime() >= manualFixRequestAt - 2_000L) manualFixRequestAt = 0;
        onLocation(location.getLatitude(), location.getLongitude(), accuracy);
    }

    private void stopLocationUpdates() {
        locationHandler.removeCallbacks(stopLocationTask);
        try {
            if (locationManager != null && locationListener != null)
                locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) { }
        locationListener = null;
    }

    private void onLocation(double lat, double lon, float accuracyM) {
        currentLat = lat;
        currentLon = lon;
        mapView.setUserLocation(lat, lon);
        if (!manualCitySelection && !searchResultsActive && selectedStop == null && !favoritesView) {
            mapView.centerOn(lat, lon, 16);
        }
        cityLabel.setText(currentCity.name + "  ▾");
        WarmLocationStore.preferences(this).edit()
                .putLong("warm_location_lat", Double.doubleToRawLongBits(lat))
                .putLong("warm_location_lon", Double.doubleToRawLongBits(lon))
                .putLong("warm_location_at", lastFixTimestamp)
                .putFloat("warm_location_accuracy", accuracyM)
                .apply();
        resolveLocatedCity(lat, lon);
        if (!searchResultsActive && selectedStop == null && !favoritesView) {
            status.setText("定位成功" + (accuracyM < 500 ? " · 精度约 " + Math.round(accuracyM) + " 米" : "")
                    + " · 加载附近站点…");
            loadNearbyStations();
        }
    }

    private void resolveLocatedCity(double lat, double lon) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (manualCitySelection || (lastCityLookupAt != 0 && now - lastCityLookupAt < 30 * 60_000L
                && GeoUtils.distanceKm(lat, lon, lastCityLookupLat, lastCityLookupLon) < 4)) return;
        lastCityLookupAt = now;
        lastCityLookupLat = lat;
        lastCityLookupLon = lon;
        int generation = ++citySelectionGeneration;
        cityWorker.execute(() -> {
            try {
                CityInfo located = CityLocator.locate(this, lat, lon);
                postUi(() -> {
                    if (isFinishing() || isDestroyed() || manualCitySelection || generation != citySelectionGeneration) return;
                    boolean changed = !located.chelaileId.equals(selectedCityId);
                    currentCity = located;
                    selectedCityId = located.chelaileId;
                    cityLabel.setText(located.name + "  ▾");
                    rememberCity();
                    if (changed && selectedStop == null && !searchResultsActive && !favoritesView) {
                        mapView.setStations(java.util.Collections.emptyList());
                        loadNearbyStations();
                    }
                });
            } catch (Exception unavailable) {
                postUi(() -> {
                    if (generation == citySelectionGeneration)
                        lastCityLookupAt = now - 29 * 60_000L;
                });
            }
        });
    }

    // ── 附近站点（车来了：双向站台都会返回）────────────
    private void loadNearbyStations() {
        loadNearbyStations(false);
    }

    private void loadNearbyStations(boolean silent) {
        if (RegionalBusActivity.supports(selectedCityId)) {
            homeRefreshInFlight = false;
            status.setText("搜索线路号或巴士站名");
            return;
        }
        if (searchResultsActive || favoritesView) {
            if (silent) homeRefreshInFlight = false;
            return;
        }
        if (silent && homeRefreshInFlight) return;
        if (silent) homeRefreshInFlight = true;
        final String requestedCityId = selectedCityId;
        final String requestedCityName = currentCity.name;
        CityInfo gpsCity = CityInfo.nearest(currentLat, currentLon);
        final boolean useGps = !manualCitySelection || (gpsCity != null
                && gpsCity.name.equals(currentCity.name));
        final double lat = useGps ? currentLat : currentCity.lat;
        final double lon = useGps ? currentLon : currentCity.lon;
        if (!TransitRules.validCoordinate(lat, lon)) {
            if (silent) homeRefreshInFlight = false;
            else status.setText("可搜索该城市的公交线路和站点");
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!silent && requestedCityId.equals(lastNearbyCity)
                && now - lastNearbyStartedAt < 4000L
                && GeoUtils.distanceKm(lat, lon, lastNearbyLat, lastNearbyLon) < .05) return;
        lastNearbyStartedAt = now;
        lastNearbyLat = lat;
        lastNearbyLon = lon;
        lastNearbyCity = requestedCityId;
        final int requestGeneration = ++nearbyRequestGeneration;
        final int savedScrollY = resultsScroll == null ? 0 : resultsScroll.getScrollY();
        if (pendingNearby != null) pendingNearby.cancel(true);
        pendingNearby = nearbyWorker.submit(() -> {
            if (requestGeneration != nearbyRequestGeneration) return;
            try {
                String resolvedCityId = ChelaileDataSource.resolveCityId(
                        requestedCityName, requestedCityId);
                List<ChelaileDataSource.NearbyStop> rawStops =
                        ChelaileDataSource.queryNearby(resolvedCityId, lat, lon);
                if (rawStops.isEmpty() && !resolvedCityId.equals(requestedCityId)) {
                    rawStops = ChelaileDataSource.queryNearby(requestedCityId, lat, lon);
                }
                final List<ChelaileDataSource.NearbyStop> stops = new ArrayList<>(rawStops);
                java.util.Collections.sort(stops, (a, b) -> {
                    int ad = a.distanceM < 0 ? Integer.MAX_VALUE : a.distanceM;
                    int bd = b.distanceM < 0 ? Integer.MAX_VALUE : b.distanceM;
                    int c = Integer.compare(ad, bd);
                    return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
                });
                if (silent) {
                    postUi(() -> {
                        homeRefreshInFlight = false;
                        if (requestGeneration != nearbyRequestGeneration || selectedStop != null
                                || searchResultsActive || favoritesView) return;
                        List<ChelaileDataSource.NearbyStop> shown = stops.size() > MAX_NEARBY_SHOWN
                                ? stops.subList(0, MAX_NEARBY_SHOWN) : stops;
                        nearbyStops = new ArrayList<>(shown);
                        visibleStops = new ArrayList<>(shown);
                        nearbyUpdatedElapsed = android.os.SystemClock.elapsedRealtime();
                        status.setText("");
                        suppressNextNearbyAnimation = true;
                        clearResults();
                        renderNearbyBlock(shown);
                        if (resultsScroll != null && savedScrollY > 0) {
                            resultsScroll.post(() -> resultsScroll.scrollTo(0, savedScrollY));
                        }
                    });
                    return;
                }
                // Show usable transit results immediately; map enrichment must not block the list.
                postUi(() -> {
                    if (requestGeneration != nearbyRequestGeneration || selectedStop != null
                            || searchResultsActive || favoritesView) return;
                    List<ChelaileDataSource.NearbyStop> shown = stops.size() > MAX_NEARBY_SHOWN
                            ? stops.subList(0, MAX_NEARBY_SHOWN) : stops;
                    nearbyStops = new ArrayList<>(shown);
                    visibleStops = new ArrayList<>(shown);
                    nearbyUpdatedElapsed = android.os.SystemClock.elapsedRealtime();
                    status.setText("");
                    clearResults();
                    renderNearbyBlock(shown);
                });
                if (pendingMap != null) pendingMap.cancel(true);
                pendingMap = mapWorker.submit(() -> {
                if (requestGeneration != nearbyRequestGeneration || Thread.currentThread().isInterrupted()) return;
                try {
                // 高德圆形搜索周围站坐标 → 地图显示站台位置（车来了附近站无坐标）
                double[] centerGcj = Coord.wgsToGcj(lat, lon);
                boolean needsCoordinates = false;
                for (ChelaileDataSource.NearbyStop stop : stops) {
                    if (!stop.subway && !TransitRules.validCoordinate(stop.lat, stop.lon)) {
                        needsCoordinates = true;
                        break;
                    }
                }
                final java.util.Map<String, double[]> gaodeCoords = needsCoordinates && AppSettings.hasMapKey()
                        ? ChelaileDataSource.gaodeAroundStops(centerGcj[1], centerGcj[0], 5000)
                        : java.util.Collections.emptyMap();
                // gaodeAroundStops 已经完成 GCJ-02 → WGS-84；不可再次转换。
                final java.util.Map<String, double[]> wgsCoords = new java.util.HashMap<>();
                if (gaodeCoords != null) {
                    for (java.util.Map.Entry<String, double[]> e : gaodeCoords.entrySet()) {
                        wgsCoords.put(e.getKey(), e.getValue());
                    }
                }
                // 内置地铁数据（地铁站坐标兜底，未匹配到高德时仍能上地图）
                final List<MetroDataSource.MetroLine> metroLines = new java.util.ArrayList<>();
                try {
                    metroLines.addAll(new MetroDataSource(this).queryLines());
                } catch (Exception ignored) {
                }
                postUi(() -> {
                    if (silent) homeRefreshInFlight = false;
                    if (requestGeneration != nearbyRequestGeneration || searchResultsActive || favoritesView) return;
                    if (selectedStop != null) return;
                    List<ChelaileDataSource.NearbyStop> shown = stops.size() > MAX_NEARBY_SHOWN
                            ? stops.subList(0, MAX_NEARBY_SHOWN) : stops;
                    nearbyStops = new ArrayList<>(shown);
                    visibleStops = new ArrayList<>(shown);
                    // 地图显示与下方列表一致的站点
                    java.util.List<NearbyStation> mapStops =
                            buildMapStops(shown, wgsCoords, metroLines);
                    mapView.setStations(mapStops);
                    mapView.setSelectedStation(null);
                });
                } catch (Exception ignored) {
                    // Transit results remain usable when map coordinates are unavailable.
                }
                });
            } catch (Exception error) {
                final String detail = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
                postUi(() -> {
                    if (silent) homeRefreshInFlight = false;
                    if (requestGeneration != nearbyRequestGeneration) return;
                    if (silent) return;
                    status.setText("附近站点获取失败");
                    Toast.makeText(this, "附近站点获取失败\n" + detail, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 站点名近似匹配（高德/车来了与内置数据可能略有差异）。 */
    private boolean sameStationName(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return a.contains(b) || b.contains(a);
    }

    /** 内置地铁数据中匹配站名，返回 WGS-84 坐标（地铁站无高德坐标时的地图兜底）。 */
    private double[] findMetroCoord(String name, List<MetroDataSource.MetroLine> metroLines) {
        if (metroLines == null || name == null) return null;
        for (MetroDataSource.MetroLine line : metroLines) {
            for (MetroDataSource.Station s : line.stations) {
                if (s.lat != 0 && sameStationName(s.name, name)) {
                    return Coord.gcjToWgs(s.lat, s.lon);
                }
            }
        }
        return null;
    }

    /** 由站点列表 + 坐标表（WGS-84）+ 内置地铁数据生成地图标记，与下方列表一一对应。 */
    private java.util.List<NearbyStation> buildMapStops(
            List<ChelaileDataSource.NearbyStop> stops,
            java.util.Map<String, double[]> wgsCoords,
            List<MetroDataSource.MetroLine> metroLines) {
        java.util.List<NearbyStation> result = new java.util.ArrayList<>();
        if (stops == null) return result;
        for (ChelaileDataSource.NearbyStop s : stops) {
            double[] coord = TransitRules.validCoordinate(s.lat, s.lon) ? new double[]{s.lat, s.lon} : null;
            if (coord == null && wgsCoords != null) {
                coord = wgsCoords.get(s.name);
                if (coord == null) {
                    for (java.util.Map.Entry<String, double[]> e : wgsCoords.entrySet()) {
                        if (e.getKey().contains(s.name) || s.name.contains(e.getKey())) {
                            coord = e.getValue();
                            break;
                        }
                    }
                }
            }
            if (coord == null && s.subway) coord = findMetroCoord(s.name, metroLines);
            if (coord != null) {
                result.add(new NearbyStation(s.name, coord[0], coord[1],
                        s.distanceM / 1000.0, s.subway, 0, null));
            }
        }
        return result;
    }

    private void clearResults() {
        Ui.navSelected(nearbyNav, "pin", !favoritesView);
        Ui.navSelected(favBtn, "star", favoritesView);
        resultList.removeAllViews();
        nearbyUpdateLabel = null;
        if (homeStateRow != null) homeStateRow.setVisibility(View.VISIBLE);
        linesBox = null;
    }

    /** 附近站点卡片：站名 + 距离 + 经过线路（带实时ETA）。 */
    private void renderNearbyBlock(List<ChelaileDataSource.NearbyStop> stops) {
        if (homeStateRow != null) homeStateRow.setVisibility(View.GONE);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(6), dp(10), dp(6), dp(8));
        TextView header = new TextView(this);
        header.setText("附近站点 · " + stops.size() + " 个");
        Ui.type(header, Ui.BODY, true);
        header.setTextColor(TEXT);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -2, 1f);
        headingLp.rightMargin = dp(12);
        heading.addView(header, headingLp);
        nearbyUpdateLabel = new TextView(this);
        Ui.type(nearbyUpdateLabel, 12, false);
        nearbyUpdateLabel.setTextColor(MUTED);
        nearbyUpdateLabel.setSingleLine(true);
        nearbyUpdateLabel.setMinWidth(dp(80));
        nearbyUpdateLabel.setGravity(Gravity.RIGHT);
        heading.addView(nearbyUpdateLabel, new LinearLayout.LayoutParams(-2, -2));
        block.addView(heading, matchWrap());
        updateNearbyAge();
        if (stops.isEmpty()) block.addView(emptyCard("附近暂无站点，可重新定位或切换城市"), cardParams());
        for (ChelaileDataSource.NearbyStop stop : stops) {
            View card = stopCard(stop);
            block.addView(card, cardParams());
            if (!suppressNextNearbyAnimation) animateIn(card);
        }
        suppressNextNearbyAnimation = false;
        resultList.addView(block, cardParams());
    }

    private void updateNearbyAge() {
        if (nearbyUpdateLabel == null) return;
        nearbyUpdateLabel.setText(nearbyUpdatedElapsed <= 0 ? "等待更新"
                : Math.max(0, (android.os.SystemClock.elapsedRealtime() - nearbyUpdatedElapsed) / 1_000L) + "秒前更新");
    }

    private View stopCard(ChelaileDataSource.NearbyStop stop) {
        final ChelaileDataSource.NearbyStop s = stop;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.edgeCard(this, stop.subway ? ACCENT : PRIMARY, 18));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(this);
        icon.setText("");
        Ui.icon(icon, stop.subway ? "metro" : "bus", MUTED);
        icon.setTextSize(20);
        head.addView(icon);
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(10), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(stop.name);
        name.setTextSize(17);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textBox.addView(name);
        TextView dist = new TextView(this);
        String distText = stop.distanceM > 0 ? "距离约 " + stop.distanceM + " 米" : "";
        if (stop.lines != null && !stop.lines.isEmpty()) {
            distText = distText.isEmpty() ? stop.lines.size() + " 条线路" : distText + " · " + stop.lines.size() + " 条线路";
        }
        dist.setText(distText);
        dist.setTextSize(12);
        dist.setTextColor(MUTED);
        dist.setPadding(0, dp(2), 0, 0);
        textBox.addView(dist);
        head.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 收藏按钮
        final TextView star = new CenteredIconView(this,"star",PRIMARY);
        star.setText("");
        Ui.icon(star, isFavorite(s.sId) ? "star-filled" : "star", PRIMARY);
        star.setContentDescription((isFavorite(s.sId) ? "取消收藏" : "收藏") + s.name);
        star.setMinWidth(dp(48));
        Ui.target(star);
        star.setCompoundDrawablePadding(0);
        star.setPadding(dp(14), 0, 0, 0);
        star.setClickable(true);
        star.setFocusable(true);
        star.setOnClickListener(v -> {
            toggleFavorite(s);
            Ui.icon(star, isFavorite(s.sId) ? "star-filled" : "star", PRIMARY);
            star.setContentDescription((isFavorite(s.sId) ? "取消收藏" : "收藏") + s.name);
            Toast.makeText(this, isFavorite(s.sId) ? "已收藏 " + s.name : "已取消收藏 " + s.name,
                    Toast.LENGTH_SHORT).show();
        });
        head.addView(star);
        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(stop.subway ? Color.rgb(222, 129, 62) : LINE_COLOR);
        head.addView(arrow);
        card.addView(head);

        // 站名下面小字显示经过的线路（公交线路名 / 地铁站提示）
        if (stop.subway) {
            TextView lines = new TextView(this);
            lines.setText("地铁站 · 内置线路时刻表");
            lines.setTextSize(12);
            lines.setTextColor(ACCENT);
            lines.setSingleLine(true);
            lines.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lines.setPadding(0, dp(3), 0, 0);
            card.addView(lines);
        } else if (stop.lines != null && !stop.lines.isEmpty()) {
            // 车来了站牌式：线路 chips 横向滚动
            java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
            for (ChelaileDataSource.LineArrival li : stop.lines) {
                if (li.name != null && !li.name.isEmpty()) seenNames.add(li.name);
            }
            if (!seenNames.isEmpty()) {
                HorizontalScrollView scroll = new HorizontalScrollView(this);
                scroll.setHorizontalScrollBarEnabled(false);
                LinearLayout chipRow = new LinearLayout(this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);
                chipRow.setPadding(0, dp(6), 0, dp(2));
                for (String lineName : seenNames) {
                    TextView chip = new TextView(this);
                    chip.setText(lineName);
                    chip.setTextSize(12);
                    chip.setTextColor(LINE_COLOR);
                    chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    chip.setPadding(dp(10), dp(3), dp(10), dp(3));
                    chip.setBackground(rounded(Ui.SURFACE, 12));
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.setMargins(0, 0, dp(6), 0);
                    chipRow.addView(chip, clp);
                }
                scroll.addView(chipRow);
                card.addView(scroll);
            }
        }
        card.setOnClickListener(v -> onStopSelected(s));
        pressFeedback(card);
        return card;
    }

    // ── 选中站点 → 双向线路列表 ────────────────────────
    private void onStopSelected(ChelaileDataSource.NearbyStop stop) {
        onStopSelected(stop, false);
    }

    private void onStopSelected(ChelaileDataSource.NearbyStop stop, boolean silent) {
        if (stop == null) return;
        if (selectedStop != stop) {
            confirmedPlatforms = java.util.Collections.emptyList();
            confirmedPlatformId = "";
        }
        if (!silent) {
            selectedFromSearch = searchResultsActive;
            invalidateHomeRequests();
            favoritesView = false;
            favBtn.setText("收藏");
        }
        if (silent && homeRefreshInFlight) return;
        if (silent) homeRefreshInFlight = true;
        final int requestGeneration = ++stopRequestGeneration;
        if (!silent) searchResultsActive = false;
        // 地图标记与下方列表相互对应：高亮并居中该站，地图缩到 1/4 保留可见
        if (!silent && stop != null && stop.name != null) {
            focusStationOnMap(stop.name);
        }
        // 跨城市收藏/搜索的站：自动切到所属城市
        if (stop.cityId != null && !stop.cityId.isEmpty() && !stop.cityId.equals(selectedCityId)) {
            selectedCityId = stop.cityId;
            for (CityInfo c : CityInfo.all()) {
                if (c.chelaileId.equals(stop.cityId)) {
                    currentCity = c;
                    break;
                }
            }
            cityLabel.setText(currentCity.name + "  ▾");
        }
        if (stop.subway) {
            showSubwayStop(stop);
            return;
        }
        selectedStop = stop;
        if (!silent) {
            selectedStopLat = 0;
            selectedStopLon = 0;
            collapseMap();
            showStopDetail("加载中…");
        }
        final String queryCityId = selectedCityId;
        final NearbyStation mapStation = mapView.findStation(stop.name);
        worker.execute(() -> {
            if (requestGeneration != stopRequestGeneration) return;
            try {
                List<ChelaileDataSource.StopDetail> details = ChelaileDataSource.queryStopWithFallback(queryCityId, stop);
                double targetLat = mapStation == null ? stop.lat : mapStation.lat;
                double targetLon = mapStation == null ? stop.lon : mapStation.lon;
                List<ChelaileDataSource.LineArrival> allLines = new ArrayList<>();
                // Only current platform responses may supply ETA; cached summary can be stale.
                for (ChelaileDataSource.StopDetail detail : details) {
                    if (detail.lines != null) allLines.addAll(detail.lines);
                    // 保存站台坐标（用于线路详情的到站时间计算）
                    if (targetLat == 0 && detail.lat != 0 && detail.lon != 0
                            && (stop.sId.equals(detail.sId) || TransitRules.sameStation(stop.name, detail.name))) {
                        targetLat = detail.lat;
                        targetLon = detail.lon;
                    }
                }
                // 同线路同实际目的地去重（保留车辆多的）。上游 direction/startSn 偶尔
                // 会变化，不能据此把同一个“开往 XX”误判为两个方向。
                java.util.Map<String, ChelaileDataSource.LineArrival> best =
                        new java.util.LinkedHashMap<>();
                for (ChelaileDataSource.LineArrival li : allLines) {
                    String destination = normalizeStationName(li.endSn);
                    String key = normalizeBusLineName(li.name) + "|"
                            + (destination.isEmpty()
                            ? li.direction + "|" + li.lineId : destination);
                    ChelaileDataSource.LineArrival prev = best.get(key);
                    if (prev == null || lineArrivalQuality(li) > lineArrivalQuality(prev)) {
                        best.put(key, li);
                    }
                }
                final List<ChelaileDataSource.LineArrival> result =
                        new ArrayList<>(best.values());
                boolean secondary = !details.isEmpty() && details.get(0).sId.startsWith("mg:");
                if (!secondary) hydrateMissingVehicleDetails(result, targetLat, targetLon, queryCityId, stop.name);
                // 先完成反向补查，再一次性渲染；避免页面从单向跳成双向。
                if (!secondary) supplementMissingReverseDirections(result, targetLat, targetLon, queryCityId, stop.name);
                if (!secondary) MygolbsDataSource.fillMissingStopStatus(queryCityId, stop.name, result);
                java.util.Collections.sort(result, (a, b) -> {
                    int c = normalizeBusLineName(a.name).compareToIgnoreCase(
                            normalizeBusLineName(b.name));
                    if (c != 0) return c;
                    return a.endSn.compareToIgnoreCase(b.endSn);
                });
                final double resultLat = targetLat, resultLon = targetLon;
                postUi(() -> {
                    if (silent) homeRefreshInFlight = false;
                    if (requestGeneration != stopRequestGeneration || selectedStop != stop) return;
                    selectedStopLat = resultLat;
                    selectedStopLon = resultLon;
                    confirmedPlatforms = details;
                    stationDataUpdatedAt = System.currentTimeMillis();
                    suppressNextStopAnimation = silent;
                    renderStopLines(stop, result);
                });
            } catch (final Exception error) {
                final String detail = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
                postUi(() -> {
                    if (silent) homeRefreshInFlight = false;
                    if (requestGeneration != stopRequestGeneration || selectedStop != stop) return;
                    if (silent) {
                        status.setText(TransitExperience.dataState(stationDataUpdatedAt, true,
                                System.currentTimeMillis()));
                        return;
                    }
                    status.setText("查询失败 · 尚未取得本站实时数据");
                    if (linesBox != null) {
                        linesBox.addView(emptyCard("查询失败\n" + detail + "\n\n请检查网络或稍后重试"), cardParams());
                    }
                });
            }
        });
    }

    private static String normalizeBusLineName(String name) {
        if (name == null) return "";
        return name.replaceAll("[（(](上行|下行|内环|外环)[）)]", "")
                .replaceAll("\\s", "").trim();
    }

    private static String normalizeStationName(String name) {
        if (name == null) return "";
        return name.replaceAll("[（(](总站|公交枢纽站)[）)]$", "")
                .replaceAll("\\s", "").trim();
    }

    private static int lineArrivalQuality(ChelaileDataSource.LineArrival line) {
        int score = line.buses == null ? 0 : line.buses.size() * 10;
        if (line.startSn != null && !line.startSn.isEmpty()) score += 2;
        if (line.endSn != null && !line.endSn.isEmpty()) score += 2;
        if (line.firstTime != null && !line.firstTime.isEmpty()) score++;
        if (line.lastTime != null && !line.lastTime.isEmpty()) score++;
        return score;
    }

    /** 站台摘要无车时，再用线路详情补取一次；只替换确实取得更多车辆的数据。 */
    private void hydrateMissingVehicleDetails(
            List<ChelaileDataSource.LineArrival> lines, double lat, double lon,
            String queryCityId, String stopName) {
        if (lat == 0 || lon == 0) return;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.Map<Integer, ChelaileDataSource.LineArrival> replacements =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<Runnable> jobs = new java.util.ArrayList<>();
        int checked = 0;
        for (int i = 0; i < lines.size() && checked < 8; i++) {
            final int index = i;
            final ChelaileDataSource.LineArrival old = lines.get(i);
            if (old.buses != null && !old.buses.isEmpty()) continue;
            checked++;
            jobs.add(() -> {
                try {
                    ChelaileDataSource.LineDetail detail = ChelaileDataSource.queryLineDetail(
                            queryCityId, old.lineId, lat, lon);
                    if (detail.buses == null || detail.buses.isEmpty()) return;
                    int targetOrder = old.targetOrder;
                    if (targetOrder <= 0 && !stopName.isEmpty() && detail.stations != null) {
                        for (ChelaileDataSource.LineStation station : detail.stations) {
                            if (normalizeStationName(station.name).equals(
                                    normalizeStationName(stopName))) {
                                targetOrder = station.order;
                                break;
                            }
                        }
                    }
                    List<ChelaileDataSource.Bus> arrivals = ChelaileDataSource.queryArrivalsAtStation(
                            queryCityId, detail.lineId, stopName, "", lat, lon);
                    if (arrivals == null || arrivals.isEmpty()) return;
                    replacements.put(index, new ChelaileDataSource.LineArrival(
                            detail.lineId, old.name, detail.direction, detail.endSn,
                            detail.startSn, old.status, targetOrder,
                            detail.stations == null ? old.stationsNum : detail.stations.size(),
                            detail.firstTime, detail.lastTime, arrivals));
                } catch (Exception ignored) { }
            });
        }
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(jobs.size());
        for (Runnable job : jobs) {
            pool.execute(() -> {
                try { job.run(); } finally { latch.countDown(); }
            });
        }
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        for (java.util.Map.Entry<Integer, ChelaileDataSource.LineArrival> entry
                : replacements.entrySet()) {
            if (entry.getKey() >= 0 && entry.getKey() < lines.size()) {
                lines.set(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 站台接口偶尔只给一个物理方向；沿线路 reverseLineId 补回真正反向。 */
    private void supplementMissingReverseDirections(
            List<ChelaileDataSource.LineArrival> lines, double lat, double lon,
            String queryCityId, String selectedName) {
        if (lat == 0 || lon == 0) return;
        java.util.Map<String, java.util.List<ChelaileDataSource.LineArrival>> groups =
                new java.util.LinkedHashMap<>();
        for (ChelaileDataSource.LineArrival line : lines) {
            String key = normalizeBusLineName(line.name);
            java.util.List<ChelaileDataSource.LineArrival> group = groups.get(key);
            if (group == null) {
                group = new java.util.ArrayList<>();
                groups.put(key, group);
            }
            group.add(line);
        }
        boolean needsReverse = false;
        for (java.util.List<ChelaileDataSource.LineArrival> group : groups.values()) {
            if (group.size() == 1) { needsReverse = true; break; }
        }
        if (!needsReverse) return;
        final java.util.Map<String, List<ChelaileDataSource.Bus>> stationArrivals = new java.util.HashMap<>();
        try {
            stationArrivals.putAll(ChelaileDataSource.queryStationArrivals(
                    queryCityId, selectedName, "", lat, lon));
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted()) return;
        }
        java.util.List<ChelaileDataSource.LineArrival> additions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.ExecutorService reversePool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<Runnable> jobs = new java.util.ArrayList<>();
        // All lines share one station ETA snapshot; metadata requests remain bounded by pool/time.
        for (java.util.List<ChelaileDataSource.LineArrival> group : groups.values()) {
            java.util.Set<String> destinations = new java.util.HashSet<>();
            for (ChelaileDataSource.LineArrival item : group) {
                String dest = normalizeStationName(item.endSn);
                if (!dest.isEmpty()) destinations.add(dest);
            }
            if (destinations.size() >= 2 || group.isEmpty()) continue;
            final ChelaileDataSource.LineArrival seed = group.get(0);
            final java.util.Set<String> knownDestinations =
                    new java.util.HashSet<>(destinations);
            jobs.add(() -> {
                try {
                    ChelaileDataSource.LineDetail current =
                            ChelaileDataSource.queryLineDetail(queryCityId, seed.lineId, lat, lon);
                    if (current.reverseLineId == null || current.reverseLineId.isEmpty()) return;
                    ChelaileDataSource.LineDetail reverse = ChelaileDataSource.queryLineDetail(
                            queryCityId, current.reverseLineId, lat, lon);
                    java.util.Map<String, List<ChelaileDataSource.Bus>> local = new java.util.HashMap<>(stationArrivals);
                    ChelaileDataSource.fillDirectionArrivals(queryCityId, reverse, selectedName, local);
                    List<ChelaileDataSource.Bus> reverseArrivals = local.get(reverse.lineId);
                    if (reverseArrivals == null) return;
                    String reverseDest = normalizeStationName(reverse.endSn);
                    if (reverseDest.isEmpty() || knownDestinations.contains(reverseDest)) return;
                    int targetOrder = -1;
                    if (reverse.stations != null && !selectedName.isEmpty()) {
                        for (ChelaileDataSource.LineStation station : reverse.stations) {
                            if (normalizeStationName(station.name).equals(
                                    normalizeStationName(selectedName))) {
                                targetOrder = station.order;
                                break;
                            }
                        }
                    }
                    // The linked opposite platform may have a different name and ID.
                    // Its physical-platform response, not a forward-name lookup, verifies ETA.
                    additions.add(new ChelaileDataSource.LineArrival(
                            reverse.lineId, seed.name, reverse.direction, reverse.endSn,
                            reverse.startSn, "", targetOrder,
                            reverse.stations == null ? -1 : reverse.stations.size(),
                            reverse.firstTime, reverse.lastTime, reverseArrivals));
                } catch (Exception ignored) {
                    // 补查失败仍保留当前方向，不让整个站台页失败。
                }
            });
        }
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(jobs.size());
        for (Runnable job : jobs) {
            reversePool.execute(() -> {
                try { job.run(); } finally { latch.countDown(); }
            });
        }
        try {
            latch.await(14, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        reversePool.shutdownNow();
        synchronized (additions) { lines.addAll(additions); }
    }

    /** 地铁站：内置地铁线路卡（像公交线路卡一样列不同线路）+ 附近公交站。 */
    private void showSubwayStop(ChelaileDataSource.NearbyStop stop) {
        selectedStop = stop;
        final int requestGeneration = stopRequestGeneration;
        if (stop != null && stop.name != null) {
            focusStationOnMap(stop.name);
        }
        collapseMap();
        showStopDetail("加载中…");
        if (linesBox == null) return;
        linesBox.removeAllViews();
        worker.execute(() -> {
            try {
                final List<MetroDataSource.MetroLine> allLines =
                        new MetroDataSource(this).queryLines();
                final List<MetroDataSource.MetroLine> hits = new ArrayList<>();
                for (MetroDataSource.MetroLine line : allLines) {
                    for (MetroDataSource.Station s : line.stations) {
                        if (sameStationName(s.name, stop.name)) {
                            hits.add(line);
                            break;
                        }
                    }
                }
                final List<MetroDataSource.MetroLine> snapshot =
                        new ArrayList<>(allLines);
                postUi(() -> {
                    if (requestGeneration == stopRequestGeneration && selectedStop == stop)
                        renderSubwayStopContent(stop, hits, snapshot);
                });
            } catch (final Exception error) {
                final String detail = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
                postUi(() -> {
                    status.setText("内置地铁数据加载失败");
                    if (linesBox != null) {
                        linesBox.addView(emptyCard("内置地铁数据加载失败\n" + detail
                                + "\n\n可点击顶部「🚇 地铁」查看线路图"), cardParams());
                    }
                    renderNearbyBusStops(stop);
                });
            }
        });
    }

    /** 渲染地铁站内容：经过线路卡（内置数据）+ 附近公交站。 */
    private void renderSubwayStopContent(ChelaileDataSource.NearbyStop stop,
                                         List<MetroDataSource.MetroLine> hits,
                                         List<MetroDataSource.MetroLine> allLines) {
        if (linesBox == null) return;
        linesBox.removeAllViews();
        LinearLayout metroCard = new LinearLayout(this);
        metroCard.setOrientation(LinearLayout.VERTICAL);
        metroCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        metroCard.setBackground(rounded(Ui.SURFACE, 16));
        metroCard.setLayoutParams(cardParams());
        TextView mTitle = new TextView(this);
        mTitle.setText("🚇 " + stop.name + " · 经过线路");
        mTitle.setTextSize(14);
        mTitle.setTextColor(Color.rgb(84, 138, 196));
        mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTitle.setPadding(0, 0, 0, dp(4));
        metroCard.addView(mTitle);
        if (hits.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("内置地铁数据暂未收录该站\n可点击顶部「🚇 地铁」查看线路图");
            none.setTextSize(12);
            none.setTextColor(MUTED);
            none.setPadding(0, dp(4), 0, 0);
            metroCard.addView(none);
        } else {
            for (MetroDataSource.MetroLine line : hits) {
                metroCard.addView(metroLineCard(stop, line), cardParams());
            }
            TextView metroHint = new TextView(this);
            metroHint.setText("点线路卡查看该线路站点与双向时刻表 ›");
            metroHint.setTextSize(12);
            metroHint.setTextColor(MUTED);
            metroHint.setPadding(0, dp(2), 0, 0);
            metroCard.addView(metroHint);
        }
        linesBox.addView(metroCard, cardParams());
        animateIn(metroCard);
        int busCount = countNearbyBusStops(stop);
        renderNearbyBusStops(stop);
        status.setText(stop.name + " · 地铁站 · " + hits.size() + " 条线路"
                + (busCount > 0 ? " · " + busCount + " 个附近公交站" : ""));
    }

    /** 地铁线路卡：线路名 + 两个方向（端点/本站首末班/下一班预测），点开线路页。 */
    private View metroLineCard(ChelaileDataSource.NearbyStop stop, MetroDataSource.MetroLine line) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(rounded(Ui.RAISED, 12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(15);
        int lc = metroLineColor(line.colour);
        dot.setTextColor(lc);
        nameRow.addView(dot);
        TextView name = new TextView(this);
        name.setText(line.name);
        name.setTextSize(15);
        name.setTextColor(lc);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(5), 0, 0, 0);
        nameRow.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        textBox.addView(nameRow);

        int idx = metroStationIndex(line, stop.name);
        MetroDataSource.Station st = idx >= 0 && idx < line.stations.size()
                ? line.stations.get(idx) : null;
        int nowMin = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60
                + java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);
        String fEnd = line.stations.isEmpty() ? "" : line.stations.get(line.stations.size() - 1).name;
        String rEnd = line.stations.isEmpty() ? "" : line.stations.get(0).name;
        for (int dir = 0; dir < 2; dir++) {
            boolean forward = dir == 0;
            String end = forward ? fEnd : rEnd;
            String firstT;
            String lastT;
            int off;
            if (st != null) {
                firstT = MetroTimes.stationFirst(line, st, forward);
                lastT = MetroTimes.stationLast(line, st, forward);
                off = 0;
            } else {
                firstT = forward ? line.firstTime : line.firstTimeR;
                lastT = forward ? line.lastTime : line.lastTimeR;
                off = 0;
            }
            String next = MetroTimes.nextTrainAt(firstT, lastT, off, nowMin,
                    line.name);
            if (st != null) {
                java.util.List<String> upcoming = new MetroSchedule(line, line.stations, forward,
                        MetroTimes.speedKmPerMin(line.name)).arrivals(idx, MetroTimes.nowMinuteExact(), 1);
                next = upcoming.isEmpty() ? null : upcoming.get(0);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(forward ? "▸ 往 " : "◂ 往 ").append(end)
                    .append("（首班 ").append(firstT).append(" · 末班 ").append(lastT).append("）");
            if (next != null) sb.append(" · 预计下一班 ").append(next);
            TextView dirText = new TextView(this);
            dirText.setText(sb.toString());
            dirText.setTextSize(12);
            dirText.setTextColor(MUTED);
            dirText.setMaxLines(2);
            dirText.setPadding(0, dp(2), 0, 0);
            textBox.addView(dirText);
        }
        card.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(10), 0, 0, 0);
        card.addView(arrow);

        card.setOnClickListener(v -> openMetroLine(line.name, line.colour));
        pressFeedback(card);
        return card;
    }

    private int metroStationIndex(MetroDataSource.MetroLine line, String name) {
        for (int i = 0; i < line.stations.size(); i++) {
            if (sameStationName(line.stations.get(i).name, name)) return i;
        }
        return -1;
    }

    private int metroLineColor(String colour) {
        try {
            return Color.parseColor(MetroDataSource.normalizeColour(colour));
        } catch (Exception e) {
            return PRIMARY;
        }
    }

    /** 地铁线路名归一化（去"地铁"与城市前缀：广州地铁1号线→1号线）。 */
    private static String normMetroCore(String name) {
        if (name == null) return "";
        return name.replace("地铁", "").replaceAll("(广州|深圳|佛山|东莞)", "")
                .replaceAll("\\s", "").trim();
    }

    /** 站名归一化（去"站"字、兼容西朗/西塱写法差异）。 */
    private static String normStopName(String name) {
        if (name == null) return "";
        return name.replace("站", "").replace("朗", "塱").trim();
    }

    /** 地铁站搜索卡：列出经过线路，点卡片进地铁站页、点线路 chip 进线路页。 */
    private View metroStationCard(String stationName, List<MetroDataSource.MetroLine> allLines) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(this);
        icon.setText("🚇");
        icon.setTextSize(16);
        head.addView(icon);
        TextView name = new TextView(this);
        name.setText(" " + stationName + " · 地铁站");
        name.setTextSize(17);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(10), 0, 0, 0);
        head.addView(arrow);
        card.addView(head);

        java.util.List<MetroDataSource.MetroLine> passing = new java.util.ArrayList<>();
        for (MetroDataSource.MetroLine line : allLines) {
            for (MetroDataSource.Station s : line.stations) {
                if (normStopName(s.name).equals(normStopName(stationName))) {
                    passing.add(line);
                    break;
                }
            }
        }
        if (!passing.isEmpty()) {
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setPadding(0, dp(6), 0, 0);
            for (MetroDataSource.MetroLine line : passing) {
                TextView chip = new TextView(this);
                chip.setText(line.name);
                chip.setTextSize(12);
                chip.setTextColor(Color.WHITE);
                chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                chip.setPadding(dp(9), dp(3), dp(9), dp(3));
                chip.setBackground(rounded(metroLineColor(line.colour), 9));
                chip.setTextColor(ReadableColors.foreground(metroLineColor(line.colour)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 0, dp(6), 0);
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> openMetroLineColour(line.name,
                        MetroDataSource.normalizeColour(line.colour)));
                chipRow.addView(chip, clp);
            }
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(chipRow);
            card.addView(scroll);
        }
        TextView hint = new TextView(this);
        hint.setText("点卡片查看经过线路与双向时刻表 ›");
        hint.setTextSize(12);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(5), 0, 0);
        card.addView(hint);

        final String sn = stationName;
        card.setOnClickListener(v -> {
            ChelaileDataSource.NearbyStop fake = new ChelaileDataSource.NearbyStop(
                    "metro_" + sn, sn, 0, true, "", "",
                    selectedCityId, new java.util.ArrayList<>(),
                    new java.util.ArrayList<>());
            showSubwayStop(fake);
        });
        pressFeedback(card);
        return card;
    }

    /** 地铁线路搜索卡：直接打开线路页。 */
    private View metroSearchLineCard(MetroDataSource.MetroLine line) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("🚇 " + line.name);
        name.setTextSize(17);
        name.setTextColor(metroLineColor(line.colour));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textBox.addView(name);
        if (line.stations.size() >= 2) {
            TextView route = new TextView(this);
            route.setText(line.stations.get(0).name + " → "
                    + line.stations.get(line.stations.size() - 1).name
                    + (line.ring ? " · 环线（外环/内环）" : ""));
            route.setTextSize(12);
            route.setTextColor(MUTED);
            route.setPadding(0, dp(2), 0, 0);
            textBox.addView(route);
        }
        TextView tip = new TextView(this);
        tip.setText(line.ring ? "环线 · 外环/内环切换 · 双向时刻表预测"
                : "双向时刻表预测 · 点击查看");
        tip.setTextSize(12);
        tip.setTextColor(MUTED);
        tip.setPadding(0, dp(2), 0, 0);
        textBox.addView(tip);
        card.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(10), 0, 0, 0);
        card.addView(arrow);

        final String ln = line.name;
        final String lc = MetroDataSource.normalizeColour(line.colour);
        card.setOnClickListener(v -> openMetroLineColour(ln, lc));
        pressFeedback(card);
        return card;
    }

    private void openMetroLineColour(String lineName, String colourHex) {
        Intent intent = new Intent(this, MetroLineActivity.class);
        intent.putExtra("lineName", lineName);
        intent.putExtra("colour", colourHex == null ? "#7EB0DB" : colourHex);
        startActivity(intent);
        Ui.transitionForward(this);
    }

    private int countNearbyBusStops(ChelaileDataSource.NearbyStop stop) {
        int count = 0;
        for (ChelaileDataSource.NearbyStop s : nearbyStops) {
            if (!s.subway && s != stop) count++;
        }
        return count;
    }

    /** 地铁站详情里的附近公交站列表（点击进入公交流程）。 */
    private void renderNearbyBusStops(ChelaileDataSource.NearbyStop stop) {
        if (linesBox == null) return;
        List<ChelaileDataSource.NearbyStop> busStops = new ArrayList<>();
        for (ChelaileDataSource.NearbyStop s : nearbyStops) {
            if (!s.subway && s != stop) busStops.add(s);
        }
        if (busStops.isEmpty()) {
            linesBox.addView(emptyCard("附近没有公交站\n可点击顶部「🚇 地铁」查看线路图"), cardParams());
            return;
        }
        TextView title = new TextView(this);
        title.setText("🚏 附近公交站（" + busStops.size() + "）");
        title.setTextSize(14);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), dp(10), dp(4), dp(6));
        linesBox.addView(title);
        for (ChelaileDataSource.NearbyStop s : busStops) {
            View card = stopCard(s);
            linesBox.addView(card, cardParams());
            animateIn(card);
        }
    }

    private void showStopDetail(String loadingText) {
        clearResults();
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headRow = new LinearLayout(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(dp(4), dp(4), dp(4), dp(6));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText((selectedStop.subway ? "🚇 " : "🚌 ") + selectedStop.name);
        title.setTextSize(18);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleBox.addView(title);
        headRow.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = button("到这去", LIGHT_BLUE, PRIMARY);
        go.setTextSize(12);
        go.setOnClickListener(v -> {
            if (selectedStop == null) return;
            if (selectedStopLat == 0 || selectedStopLon == 0) {
                Toast.makeText(this, "站点坐标尚未获取，请稍候再试", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, TransitActivity.class);
            intent.putExtra("cityId", selectedCityId);
            intent.putExtra("cityName", currentCity.name);
            intent.putExtra("currentLat", currentLat);
            intent.putExtra("currentLon", currentLon);
            intent.putExtra("hasLocation", restoredWarmLocation || lastAcceptedLocationAt > 0);
            intent.putExtra("goName", selectedStop.name);
            intent.putExtra("goLat", selectedStopLat);
            intent.putExtra("goLon", selectedStopLon);
            startActivity(intent);
            Ui.transitionForward(this);
        });
        headRow.addView(go, new LinearLayout.LayoutParams(dp(64), dp(48)));
        go.setVisibility(AppSettings.hasMapKey() ? View.VISIBLE : View.GONE);
        Button refresh = button("刷新", LIGHT_BLUE, PRIMARY);
        refresh.setTextSize(12);
        for (Button action : new Button[]{go, refresh}) {
            action.setMinWidth(0);
            action.setMinimumWidth(0);
            action.setMinHeight(0);
            action.setMinimumHeight(0);
            action.setSingleLine(true);
            action.setIncludeFontPadding(false);
            action.setBackground(new android.graphics.drawable.InsetDrawable(
                    action.getBackground(), 0, dp(8), 0, dp(8)));
            action.setPadding(dp(6), 0, dp(6), 0);
            action.setElevation(0);
        }
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                dp(52), dp(48));
        refreshLp.setMargins(dp(4), 0, 0, 0);
        refresh.setOnClickListener(v -> onStopSelected(selectedStop));
        headRow.addView(refresh, refreshLp);
        block.addView(headRow, matchWrap());

        linesBox = new LinearLayout(this);
        linesBox.setOrientation(LinearLayout.VERTICAL);
        linesBox.addView(Ui.loadingCard(this), matchWrap());
        block.addView(linesBox, matchWrap());

        resultList.addView(block, cardParams());
    }

    private void collapseStop() {
        invalidateHomeRequests();
        if (selectedFromSearch && searchSnapshot != null) {
            selectedFromSearch = false;
            selectedStop = null;
            searchResultsActive = true;
            clearResults();
            if (searchSnapshot.getParent() instanceof ViewGroup) {
                ((ViewGroup) searchSnapshot.getParent()).removeView(searchSnapshot);
            }
            resultList.addView(searchSnapshot, cardParams());
            status.setText(searchSnapshotStatus);
            visibleStops = new ArrayList<>(searchSnapshotStops);
            mapView.setStations(searchSnapshotMarkers);
            mapView.setSelectedStation(null);
            return;
        }
        stopRequestGeneration++;
        favoritesView = false;
        searchResultsActive = false;
        if (favBtn != null) favBtn.setText("收藏");
        selectedStop = null;
        mapView.setSelectedStation(null);
        expandMap();
        clearResults();
        if (!nearbyStops.isEmpty()) {
            renderNearbyBlock(nearbyStops);
            status.setText("");
        } else {
            status.setText("定位后显示附近站点实时车辆");
        }
    }

    /** 打开地铁线路站点页（线路名 → 双向时刻表）。 */
    private void openMetroLine(String lineName, String rgbCsv) {
        String colour = "#7EB0DB";
        if (rgbCsv != null && !rgbCsv.isEmpty()) {
            try {
                String[] parts = rgbCsv.split(",");
                if (parts.length >= 3) {
                    colour = String.format("#%02X%02X%02X",
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()));
                }
            } catch (Exception ignored) {
            }
        }
        Intent intent = new Intent(this, MetroLineActivity.class);
        intent.putExtra("lineName", lineName);
        intent.putExtra("colour", colour);
        startActivity(intent);
        Ui.transitionForward(this);
    }

    /** 渲染站台双向线路卡（同线路 dir 0/1 都显示）。 */
    private void renderStopLines(ChelaileDataSource.NearbyStop stop, List<ChelaileDataSource.LineArrival> allLines) {
        if (linesBox == null) return;
        homeStateRow.setVisibility(View.GONE);
        linesBox.removeAllViews();
        java.util.Map<String, ChelaileDataSource.StopDetail> platforms = new java.util.TreeMap<>();
        for (ChelaileDataSource.StopDetail item : confirmedPlatforms) {
            if (item.sId != null && !item.sId.isEmpty() && item.lines != null && !item.lines.isEmpty())
                platforms.put(item.sId, item);
        }
        if (platforms.size() > 1 || !confirmedPlatformId.isEmpty()) {
            android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout choices = new LinearLayout(this);
            addPlatformChoice(choices, "全部", "", stop, allLines);
            int number = 0;
            for (ChelaileDataSource.StopDetail item : platforms.values()) {
                ChelaileDataSource.LineArrival example = item.lines.get(0);
                String end = example.endSn;
                String label = "站台 " + (++number) + (end == null || end.isEmpty() ? ""
                        : " · " + example.name + " 往 " + end);
                addPlatformChoice(choices, label, item.sId, stop, allLines);
            }
            strip.addView(choices);
            linesBox.addView(strip, cardParams());
        }
        ChelaileDataSource.StopDetail confirmed = platforms.get(confirmedPlatformId);
        if (confirmed != null) allLines = confirmed.lines;
        else if (!confirmedPlatformId.isEmpty()) allLines = java.util.Collections.emptyList();
        String stopName = stop.name;
        // 地铁站：顶部附加地铁信息卡（公交线路照常显示）
        if (stop.subway && stop.metroLines != null && !stop.metroLines.isEmpty()) {
            LinearLayout metroCard = new LinearLayout(this);
            metroCard.setOrientation(LinearLayout.VERTICAL);
            metroCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            metroCard.setBackground(rounded(Ui.SURFACE, 16));
            metroCard.setLayoutParams(cardParams());
            TextView mTitle = new TextView(this);
            mTitle.setText("🚇 " + stopName + " · 地铁");
            mTitle.setTextSize(17);
            mTitle.setTextColor(Color.rgb(84, 138, 196));
            mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mTitle.setPadding(0, 0, 0, dp(4));
            metroCard.addView(mTitle);
            for (ChelaileDataSource.MetroLineInfo mi : stop.metroLines) {
                TextView line = new TextView(this);
                line.setText("● " + mi.lineName + " 开往 " + mi.destName
                        + "（首班 " + mi.firstTime + " · 末班 " + mi.lastTime + "）");
                line.setTextSize(14);
                line.setTextColor(TEXT);
                line.setLineSpacing(dp(3), 1.05f);
                line.setPadding(0, dp(6), 0, dp(4));
                line.setClickable(true);
                line.setFocusable(true);
                line.setOnClickListener(v -> openMetroLine(mi.lineName, mi.bgColor));
                metroCard.addView(line);
            }
            TextView metroHint = new TextView(this);
            metroHint.setText("点线路名可查看该线路站点与双向时刻表 ›");
            metroHint.setTextSize(12);
            metroHint.setTextColor(MUTED);
            metroHint.setPadding(0, dp(4), 0, 0);
            metroCard.addView(metroHint);
            linesBox.addView(metroCard, cardParams());
            if (!suppressNextStopAnimation) animateIn(metroCard);
        }
        if (allLines.isEmpty()) {
            status.setText(stopName + " 暂无车辆数据");
            linesBox.addView(emptyCard("暂无到站"), cardParams());
            suppressNextStopAnimation = false;
            return;
        }
        // 按线路名分组（同线路两个方向合成一张卡）
        Map<String, List<ChelaileDataSource.LineArrival>> groups = new LinkedHashMap<>();
        for (ChelaileDataSource.LineArrival li : allLines) {
            String groupKey = normalizeBusLineName(li.name);
            List<ChelaileDataSource.LineArrival> list = groups.get(groupKey);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(groupKey, list);
            }
            list.add(li);
        }
        for (Map.Entry<String, List<ChelaileDataSource.LineArrival>> entry : groups.entrySet()) {
            java.util.Collections.sort(entry.getValue(),
                    (a, b) -> Integer.compare(a.direction, b.direction));
            View card = lineCard(entry.getValue());
            linesBox.addView(card, cardParams());
            if (!suppressNextStopAnimation) animateIn(card);
        }
        suppressNextStopAnimation = false;
    }

    /** 线路卡（车来了式）：左线路名+方向，右最近车大号时间。 */
    private void addPlatformChoice(LinearLayout choices, String label, String id,
                                   ChelaileDataSource.NearbyStop stop, List<ChelaileDataSource.LineArrival> lines) {
        boolean selected = id.equals(confirmedPlatformId);
        Button choice = Ui.button(this, label, selected ? PRIMARY : Ui.RAISED,
                selected ? Ui.ON_PRIMARY : TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.textRowHeight(this, 48));
        lp.rightMargin = dp(8);
        choices.addView(choice, lp);
        choice.setOnClickListener(v -> {
            confirmedPlatformId = id;
            suppressNextStopAnimation = true;
            renderStopLines(stop, lines);
        });
    }

    private View lineCard(List<ChelaileDataSource.LineArrival> arrivals) {
        ChelaileDataSource.LineArrival first = arrivals.get(0);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Ui.edgeCard(this, PRIMARY, 16));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        line.setText(first.name);
        Ui.type(line, 20, true);
        line.setTextColor(TEXT);
        heading.addView(line, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = new TextView(this);
        count.setText(first.stationsNum > 0 ? first.stationsNum + "站" : "");
        Ui.type(count, Ui.META, false);
        count.setTextColor(MUTED);
        heading.addView(count);
        card.addView(heading);
        for (ChelaileDataSource.LineArrival arrival : arrivals) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.textRowHeight(this, 48));
            row.setPadding(0, dp(8), 0, dp(8));
            TextView destination = new TextView(this);
            destination.setText("往 " + arrival.endSn);
            Ui.type(destination, Ui.BODY, true);
            destination.setTextColor(TEXT);
            destination.setMaxLines(2);
            destination.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(destination, new LinearLayout.LayoutParams(0, -2, 1f));
            ChelaileDataSource.Bus bus = nearestBus(arrival.buses, arrival.targetOrder);
            TextView eta = new TextView(this);
            Ui.type(eta, bus == null ? Ui.META : 22, true);
            eta.setText(bus != null ? bus.travelTime < 60 ? "即将到" : (bus.travelTime + 59)/60 + "分"
                    : arrival.status == null || arrival.status.isEmpty() ? "暂无预测" : arrival.status);
            eta.setTextColor(bus != null ? ACCENT : MUTED);
            eta.setGravity(Gravity.END);
            eta.setPadding(dp(12), 0, 0, 0);
            row.addView(eta, new LinearLayout.LayoutParams(dp(84), -2));
            row.setOnClickListener(v -> openArrivalLine(arrival));
            row.setContentDescription(arrival.name + "，开往" + arrival.endSn + "，" + eta.getText());
            row.setBackground(Ui.control(this, Color.TRANSPARENT));
            Ui.pressFeedback(row);
            card.addView(row);
        }
        card.setOnClickListener(v -> openArrivalLine(first));
        return card;
    }

    private void openArrivalLine(ChelaileDataSource.LineArrival arrival) {
        Intent intent = new Intent(this, LineDetailActivity.class);
        intent.putExtra("cityId", selectedCityId);
        intent.putExtra("lineId", arrival.lineId);
        intent.putExtra("lineName", arrival.name);
        intent.putExtra("stationName", selectedStop == null ? "" : selectedStop.name);
        intent.putExtra("stopLat", selectedStopLat != 0 ? selectedStopLat : currentLat);
        intent.putExtra("stopLon", selectedStopLon != 0 ? selectedStopLon : currentLon);
        for (ChelaileDataSource.StopDetail platform : confirmedPlatforms) {
            if (!confirmedPlatformId.isEmpty() && confirmedPlatformId.equals(platform.sId)) {
                intent.putExtra("stationId", platform.sId);
                intent.putExtra("stationName", platform.name);
                intent.putExtra("stopLat", platform.lat);
                intent.putExtra("stopLon", platform.lon);
                break;
            }
        }
        intent.putExtra("userLat", currentLat);
        intent.putExtra("userLon", currentLon);
        startActivity(intent);
        Ui.transitionForward(this);
    }

    /** 取 travelTime 最小（>=0）的车 = 最近到站的车；0 表示已进站。 */
    private static ChelaileDataSource.Bus nearestBus(List<ChelaileDataSource.Bus> buses, int targetOrder) {
        if (buses == null || buses.isEmpty()) return null;
        ChelaileDataSource.Bus best = null;
        for (ChelaileDataSource.Bus bus : buses) {
            if (!TransitRules.canArrive(bus.travelTime, bus.order, targetOrder)) continue;
            if (best == null || bus.travelTime < best.travelTime) best = bus;
        }
        return best;
    }

    private void queryByInput() {
        final String input = stationInput.getText().toString().trim();
        final String station = TransitRules.keyword(input);
        if (station.isEmpty()) {
            stationInput.setError("请输入站名");
            return;
        }
        CityInfo explicitCity = TransitRules.explicitCity(input);
        final CityInfo scopeCity = explicitCity == null ? currentCity : explicitCity;
        if (RegionalBusActivity.supports(scopeCity.chelaileId)) {
            RegionalBusActivity.open(this, scopeCity.chelaileId, station);
            return;
        }
        String railRegion = RailCatalog.regionForLine(station);
        if (station.equals("上海市域铁路")) railRegion = RailCatalog.SH;
        if (!railRegion.isEmpty()) {
            RailActivity.open(this, railRegion, station, "", "");
            return;
        }
        final String searchCityId = forcedCityId != null ? forcedCityId : scopeCity.chelaileId;
        final String searchCityName = forcedCityName != null ? forcedCityName : scopeCity.name;
        final double originLat = searchCityName.equals(currentCity.name) && !manualCitySelection
                ? currentLat : scopeCity.lat;
        final double originLon = searchCityName.equals(currentCity.name) && !manualCitySelection
                ? currentLon : scopeCity.lon;
        final double selectedSearchLat = forcedSearchLat;
        final double selectedSearchLon = forcedSearchLon;
        forcedCityId = null;
        forcedCityName = null;
        forcedSearchLat = Double.NaN;
        forcedSearchLon = Double.NaN;
        lastSearchCityId = searchCityId;
        lastSearchCityName = searchCityName;
        invalidateHomeRequests();
        favBtn.setText("收藏");
        searchSnapshot = null;
        searchResultsActive = true;
        favoritesView = false;
        submittedSearch = station;
        selectedStop = null;
        nearbyRequestGeneration++;
        final int requestGeneration = ++searchRequestGeneration;
        hideKeyboard();
        collapseMap();
        if (resultsScroll != null) resultsScroll.scrollTo(0, 0);
        setLoading(true);
        status.setText("正在搜索 " + station + " …");
        pendingSearch = worker.submit(() -> {
            // 1. 高德搜站名拿坐标 → 2. 查车来了附近站（真正的公交站）
            final List<ChelaileDataSource.NearbyStop> stopResults = new ArrayList<>();
            final List<ChelaileDataSource.GaodeStop> stopPois = new ArrayList<>();
            final List<ChelaileDataSource.SearchLine> searchLines = new ArrayList<>();
            final List<ChelaileDataSource.SearchPoi> searchPois = new ArrayList<>();
            final List<ChelaileDataSource.GaodeLine> lines = new ArrayList<>();
            final StringBuilder errors = new StringBuilder();
            double queryLat = Double.isNaN(selectedSearchLat) ? originLat : selectedSearchLat;
            double queryLon = Double.isNaN(selectedSearchLon) ? originLon : selectedSearchLon;
            String effectiveCityId = ChelaileDataSource.resolveCityId(searchCityName, searchCityId);
            try {
                ChelaileDataSource.SearchResult sr = ChelaileDataSource.searchKeywords(
                        effectiveCityId, station, queryLat, queryLon);
                searchLines.addAll(sr.lines);
                for (ChelaileDataSource.SearchPoi poi : sr.pois) {
                    if (poi.type == 1 && "掌上公交".equals(poi.route)) {
                        String id = "mg:" + MygolbsDataSource.cityName(effectiveCityId) + ":" + poi.name;
                        double[] wgs = Coord.gcjToWgs(poi.lat, poi.lon);
                        stopResults.add(new ChelaileDataSource.NearbyStop(id, poi.name, -1, false, id, id,
                                effectiveCityId, java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                                wgs[0], wgs[1]));
                    } else searchPois.add(poi);
                }
            } catch (Exception error) { if (Thread.currentThread().isInterrupted()) return; }
            try {
                boolean useGaode = AppSettings.hasMapKey() && stopResults.isEmpty() && searchLines.isEmpty();
                List<ChelaileDataSource.GaodeStop> pois = useGaode
                        ? ChelaileDataSource.gaodeSearchStops(searchCityName, station)
                        : java.util.Collections.emptyList();
                if (useGaode && pois.isEmpty()) {
                    for (ChelaileDataSource.GaodeStop poi : ChelaileDataSource.gaodeSearchPlaceCrossCity(station, searchCityName)) {
                        if (poi.typePriority <= 1) stopPois.add(poi);
                    }
                }
                stopPois.addAll(pois);
                // 每个坐标查车来了附近站，按 sId 合并去重
                java.util.Map<String, ChelaileDataSource.NearbyStop> stopMap =
                        new java.util.LinkedHashMap<>();
                if (!Double.isNaN(selectedSearchLat) && !Double.isNaN(selectedSearchLon)) {
                    List<ChelaileDataSource.NearbyStop> nearby =
                            ChelaileDataSource.queryNearby(effectiveCityId,
                                    selectedSearchLat, selectedSearchLon);
                    for (ChelaileDataSource.NearbyStop s : nearby) {
                        stopMap.put(s.cityId + "|" + s.sId, s);
                    }
                }
                int used = Double.isNaN(selectedSearchLat) ? Math.min(2, pois.size()) : 0;
                for (int i = 0; i < used; i++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    ChelaileDataSource.GaodeStop poi = pois.get(i);
                    String queryKey = TransitRules.stationKey(station);
                    String poiKey = TransitRules.stationKey(poi.name);
                    // Fuzzy POIs are choices for the user, not automatic coordinate matches.
                    if (!poiKey.contains(queryKey) && !queryKey.contains(poiKey)) continue;
                    // POI 城市 → 车来了 cityId
                    String cityId = searchCityId;
                    for (CityInfo c : CityInfo.all()) {
                        if (poi.cityName != null && poi.cityName.contains(c.name)) {
                            cityId = c.chelaileId;
                            break;
                        }
                    }
                    cityId = ChelaileDataSource.resolveCityId(poi.cityName, cityId);
                    double[] wgs = ChelaileDataSource.gcjToWgs(poi.lat, poi.lon);
                    if (i == 0 && Double.isNaN(selectedSearchLat)) {
                        queryLat = wgs[0];
                        queryLon = wgs[1];
                        effectiveCityId = cityId;
                    }
                    try {
                        List<ChelaileDataSource.NearbyStop> nearby =
                                ChelaileDataSource.queryNearby(cityId, wgs[0], wgs[1]);
                        for (ChelaileDataSource.NearbyStop s : nearby) {
                            stopMap.put(s.cityId + "|" + s.sId, s);
                        }
                    } catch (Exception ignored) { }
                }
                stopResults.addAll(stopMap.values());
                java.util.Collections.sort(stopResults, (a, b) -> {
                    int ar = TransitRules.sameStation(a.name, station) ? 0 : 1;
                    int br = TransitRules.sameStation(b.name, station) ? 0 : 1;
                    return ar != br ? Integer.compare(ar, br)
                            : Integer.compare(a.distanceM < 0 ? Integer.MAX_VALUE : a.distanceM,
                                    b.distanceM < 0 ? Integer.MAX_VALUE : b.distanceM);
                });
                while (stopResults.size() > 10) {
                    stopResults.remove(stopResults.size() - 1);
                }
            } catch (Exception e) {
                errors.append(e.getMessage()).append("\n");
            }
            if (Thread.currentThread().isInterrupted()) return;
            if (AppSettings.hasMapKey() && searchLines.isEmpty() && searchPois.isEmpty()
                    && stopResults.isEmpty() && stopPois.isEmpty()) {
                try {
                    lines.addAll(ChelaileDataSource.gaodeSearchLines(searchCityName, station));
                } catch (Exception e) {
                    errors.append(e.getMessage()).append("\n");
                }
            }
            // 内置地铁数据（地铁站坐标兜底 + 搜索结果上地图）
            final List<MetroDataSource.MetroLine> metroLines = new java.util.ArrayList<>();
            try {
                metroLines.addAll(new MetroDataSource(this).queryLines());
            } catch (Exception ignored) {
            }
            // 内置地铁匹配：站名/线路名离线兜底，并把车来了误报成公交的地铁线路过滤掉
            final java.util.List<MetroDataSource.MetroLine> metroLineHits =
                    new java.util.ArrayList<>();
            final java.util.List<String> metroStationHits = new java.util.ArrayList<>();
            if (!metroLines.isEmpty()) {
                java.util.Set<String> metroCores = new java.util.HashSet<>();
                for (MetroDataSource.MetroLine l : metroLines) {
                    metroCores.add(normMetroCore(l.name));
                }
                java.util.Iterator<ChelaileDataSource.SearchLine> lineIt =
                        searchLines.iterator();
                while (lineIt.hasNext()) {
                    if (metroCores.contains(normMetroCore(lineIt.next().name))) {
                        lineIt.remove();
                    }
                }
                String qc = normMetroCore(station);
                String qs = normStopName(station);
                for (MetroDataSource.MetroLine l : metroLines) {
                    if (!qc.isEmpty() && qc.equals(normMetroCore(l.name))
                            || qc.length() >= 2 && !qc.endsWith("号线")
                            && l.name.contains(qc)) {
                        if (!metroLineHits.contains(l)) metroLineHits.add(l);
                    }
                    for (MetroDataSource.Station s : l.stations) {
                        String sn = normStopName(s.name);
                        if (qs.length() >= 2 && sn.contains(qs)) {
                            if (!metroStationHits.contains(s.name)) {
                                metroStationHits.add(s.name);
                            }
                        }
                    }
                }
                // 车来了已返回的同名地铁站不重复展示
                java.util.Set<String> existingSubway = new java.util.HashSet<>();
                for (ChelaileDataSource.NearbyStop st : stopResults) {
                    if (st.subway) existingSubway.add(normStopName(st.name));
                }
                java.util.Iterator<String> stIt = metroStationHits.iterator();
                while (stIt.hasNext()) {
                    if (existingSubway.contains(normStopName(stIt.next()))) stIt.remove();
                }
                while (metroLineHits.size() > 6) {
                    metroLineHits.remove(metroLineHits.size() - 1);
                }
                while (metroStationHits.size() > 8) {
                    metroStationHits.remove(metroStationHits.size() - 1);
                }
            }
            final String errorMsg = errors.toString();
            final String resultCityId = effectiveCityId;
            final double resultSearchLat = queryLat, resultSearchLon = queryLon;
            boolean matchedChelaileStopValue = false;
            String normalizedQuery = normStopName(station);
            for (ChelaileDataSource.NearbyStop candidate : stopResults) {
                String normalizedCandidate = normStopName(candidate.name);
                if (!normalizedQuery.isEmpty() && (normalizedCandidate.contains(normalizedQuery)
                        || normalizedQuery.contains(normalizedCandidate))) {
                    matchedChelaileStopValue = true;
                    break;
                }
            }
            final boolean matchedChelaileStop = matchedChelaileStopValue;
            postUi(() -> {
                if (requestGeneration != searchRequestGeneration || !searchResultsActive) return;
                lastSearchCityId = resultCityId;
                lastSearchLat = resultSearchLat;
                lastSearchLon = resultSearchLon;
                setLoading(false);
                if (stopResults.isEmpty() && searchLines.isEmpty()
                        && searchPois.isEmpty() && lines.isEmpty()
                        && metroLineHits.isEmpty() && metroStationHits.isEmpty()
                        && stopPois.isEmpty()) {
                    status.setText("未找到 " + station);
                    clearResults();
                    String tip = errorMsg.isEmpty()
                            ? "没有找到该站或线路\n请确认站名/线路名"
                            : "该站暂无数据\n" + errorMsg.trim();
                    resultList.addView(emptyCard(tip), cardParams());
                    return;
                }
                int lineGroupCount = searchLines.isEmpty()
                        ? (searchPois.isEmpty() ? lines.size() : searchPois.size())
                        : searchLines.size();
                int metroCount = metroLineHits.size() + metroStationHits.size();
                status.setText("找到 " + (stopResults.size() + metroStationHits.size()
                                + (!matchedChelaileStop ? stopPois.size() : 0))
                        + " 个站点 · " + (lineGroupCount + metroLineHits.size()) + " 条线路"
                        + " · " + searchCityName);
                clearResults();
                // 搜索结果与地图相互对应：站点标记（高德 POI 坐标或内置地铁坐标）
                java.util.Map<String, double[]> searchCoords = new java.util.HashMap<>();
                for (ChelaileDataSource.GaodeStop poi : stopPois) {
                    double[] wgs = ChelaileDataSource.gcjToWgs(poi.lat, poi.lon);
                    searchCoords.put(poi.name, wgs);
                }
                java.util.List<NearbyStation> mapStops =
                        buildMapStops(stopResults, searchCoords, metroLines);
                mapView.setStations(mapStops);
                mapView.setSelectedStation(null);
                visibleStops = new ArrayList<>(stopResults);
                LinearLayout block = new LinearLayout(this);
                block.setOrientation(LinearLayout.VERTICAL);
                // 车来了没有返回站台时，保留高德的精确站点结果；点选坐标后再桥接实时查询。
                if (!matchedChelaileStop && !stopPois.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("站点 · 点击查看实时公交");
                    header.setTextSize(13);
                    header.setTextColor(Color.rgb(63, 126, 183));
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    int shown = Math.min(8, stopPois.size());
                    for (int i = 0; i < shown; i++) {
                        View card = gaodeStopBridgeCard(stopPois.get(i));
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                }
                // 站点组（车来了站台信息）
                if (!stopResults.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("公交站点及周边站（车来了）");
                    header.setTextSize(13);
                    header.setTextColor(TEXT);
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    for (ChelaileDataSource.NearbyStop stop : stopResults) {
                        View card = stopCard(stop);
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                }
                // 内置地铁组：站名/线路名离线兜底，点击进入地铁站/线路页
                if (!metroStationHits.isEmpty() || !metroLineHits.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("地铁（内置数据 · 离线可用）");
                    header.setTextSize(13);
                    header.setTextColor(Color.rgb(84, 138, 196));
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    for (String sn : metroStationHits) {
                        View card = metroStationCard(sn, metroLines);
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                }
                // 线路组：车来了真实线路优先，其次模糊 POI，最后高德静态
                int stationSectionCount = block.getChildCount();
                if (!searchLines.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("线路（车来了 · 实时到站）");
                    header.setTextSize(13);
                    header.setTextColor(TEXT);
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    Map<String, List<ChelaileDataSource.SearchLine>> groups =
                            new LinkedHashMap<>();
                    for (ChelaileDataSource.SearchLine sl : searchLines) {
                        List<ChelaileDataSource.SearchLine> list = groups.get(sl.name);
                        if (list == null) {
                            list = new ArrayList<>();
                            groups.put(sl.name, list);
                        }
                        list.add(sl);
                    }
                    for (Map.Entry<String, List<ChelaileDataSource.SearchLine>> entry
                            : groups.entrySet()) {
                        View card = searchLineCard(entry.getValue());
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                }
                if (!searchPois.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("相关站点与线路");
                    header.setTextSize(13);
                    header.setTextColor(TEXT);
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    for (ChelaileDataSource.SearchPoi poi : searchPois) {
                        View card = searchPoiCard(poi);
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                } else if (searchLines.isEmpty() && !lines.isEmpty()) {
                    TextView header = new TextView(this);
                    header.setText("线路");
                    header.setTextSize(13);
                    header.setTextColor(TEXT);
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(4), dp(8), dp(4), dp(6));
                    block.addView(header);
                    for (ChelaileDataSource.GaodeLine line : lines) {
                        View card = gaodeLineCard(line);
                        block.addView(card, cardParams());
                        animateIn(card);
                    }
                }
                java.util.List<View> stationSections = new java.util.ArrayList<>();
                for (int i = 0; i < stationSectionCount; i++) {
                    View child = block.getChildAt(0);
                    block.removeViewAt(0);
                    stationSections.add(child);
                }
                for (MetroDataSource.MetroLine line : metroLineHits) {
                    block.addView(metroSearchLineCard(line), cardParams());
                }
                for (View child : stationSections) block.addView(child);
                resultList.addView(block, cardParams());
                searchSnapshot = block;
                searchSnapshotStatus = status.getText().toString();
                searchSnapshotStops = new ArrayList<>(stopResults);
                searchSnapshotMarkers = new ArrayList<>(mapStops);
                if (resultsScroll != null) {
                    resultsScroll.post(() -> resultsScroll.smoothScrollTo(0, 0));
                }
            });
        });
    }

    /** 高德仅负责确认站名与坐标；点击后把准确坐标交给车来了查附近站和实时线路。 */
    private View gaodeStopBridgeCard(ChelaileDataSource.GaodeStop poi) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(13), dp(14), dp(13));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("📍 " + poi.name);
        name.setTextSize(17);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(name, matchWrap());
        TextView detail = new TextView(this);
        String city = poi.cityName == null || poi.cityName.isEmpty() ? lastSearchCityName : poi.cityName;
        detail.setText(city + " · 使用该站坐标查询车来了");
        detail.setTextSize(12);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(4), 0, 0);
        textBox.addView(detail, matchWrap());
        card.addView(textBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("查询 ›");
        arrow.setTextSize(13);
        arrow.setTextColor(PRIMARY);
        arrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(arrow);

        card.setOnClickListener(v -> {
            String cityName = poi.cityName == null || poi.cityName.isEmpty()
                    ? lastSearchCityName : poi.cityName;
            String cityId = lastSearchCityId == null ? selectedCityId : lastSearchCityId;
            for (CityInfo c : CityInfo.all()) {
                if (cityName != null && cityName.contains(c.name)) {
                    cityId = c.chelaileId;
                    cityName = c.name;
                    break;
                }
            }
            double[] wgs = ChelaileDataSource.gcjToWgs(poi.lat, poi.lon);
            forcedCityId = cityId;
            forcedCityName = cityName;
            forcedSearchLat = wgs[0];
            forcedSearchLon = wgs[1];
            stationInput.setText(poi.name);
            queryByInput();
        });
        pressFeedback(card);
        return card;
    }

    /** 线路搜索结果卡：真实线路 + 双向起终点，点开实时到站详情。 */
    private View searchLineCard(List<ChelaileDataSource.SearchLine> arrivals) {
        ChelaileDataSource.SearchLine first = arrivals.get(0);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView line = new TextView(this);
        line.setText("🚌 " + first.name);
        line.setTextSize(18);
        line.setTextColor(LINE_COLOR);
        line.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(line);
        for (ChelaileDataSource.SearchLine li : arrivals) {
            TextView dir = new TextView(this);
            dir.setText((li.direction == 0 ? "▸" : "◂") + " "
                    + li.startSn + " → " + li.endSn + "   ›");
            dir.setTextSize(12);
            dir.setTextColor(TEXT);
            dir.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            dir.setMaxLines(2);
            dir.setPadding(dp(10), dp(7), dp(10), dp(7));
            dir.setBackground(rounded(LIGHT_BLUE, 10));
            LinearLayout.LayoutParams dlp = matchWrap();
            dlp.topMargin = dp(5);
            dir.setLayoutParams(dlp);
            dir.setClickable(true);
            dir.setFocusable(true);
            dir.setOnClickListener(v -> openSearchLine(li));
            pressFeedback(dir);
            textBox.addView(dir);
        }
        TextView tip = new TextView(this);
        tip.setText("车来了实时数据 · 请点选具体方向");
        tip.setTextSize(12);
        tip.setTextColor(LINE_COLOR);
        tip.setPadding(0, dp(3), 0, 0);
        textBox.addView(tip);
        card.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView eta = new TextView(this);
        eta.setText("›");
        eta.setTextSize(20);
        eta.setTextColor(MUTED);
        eta.setPadding(dp(11), 0, 0, 0);
        card.addView(eta);

        card.setOnClickListener(v -> {
            if (arrivals.size() == 1) openSearchLine(first);
            else Toast.makeText(this, "请点选正确的开往方向", Toast.LENGTH_SHORT).show();
        });
        pressFeedback(card);
        return card;
    }

    /** 使用车来了返回的方向专属 lineId 打开，避免同线路默认进入错误上下行。 */
    private void openSearchLine(ChelaileDataSource.SearchLine line) {
        Intent intent = new Intent(this, LineDetailActivity.class);
        String targetCityId = lastSearchCityId != null ? lastSearchCityId : selectedCityId;
        intent.putExtra("cityId", targetCityId);
        intent.putExtra("lineId", line.lineId);
        intent.putExtra("lineName", line.name);
        intent.putExtra("stationName", "");
        double stopLat = lastSearchLat != 0 ? lastSearchLat : currentLat;
        double stopLon = lastSearchLon != 0 ? lastSearchLon : currentLon;
        intent.putExtra("stopLat", stopLat);
        intent.putExtra("stopLon", stopLon);
        intent.putExtra("userLat", currentLat);
        intent.putExtra("userLon", currentLon);
        startActivity(intent);
        Ui.transitionForward(this);
    }

    /** 模糊线路 POI 卡：显示线路名+走向，点击用该线路名重新搜索实时数据。 */
    private View searchPoiCard(ChelaileDataSource.SearchPoi poi) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("🚌 " + poi.name);
        name.setTextSize(17);
        name.setTextColor(LINE_COLOR);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(name);
        TextView route = new TextView(this);
        route.setText(poi.route == null || poi.route.isEmpty() ? "公交线路" : poi.route);
        route.setTextSize(12);
        route.setTextColor(MUTED);
        route.setMaxLines(2);
        route.setPadding(0, dp(2), 0, 0);
        textBox.addView(route);
        TextView tip = new TextView(this);
        tip.setText("点此搜索该线路实时车辆");
        tip.setTextSize(12);
        tip.setTextColor(MUTED);
        tip.setPadding(0, dp(3), 0, 0);
        textBox.addView(tip);
        card.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(11), 0, 0, 0);
        card.addView(arrow);

        card.setOnClickListener(v -> {
            // 用上次搜索结果所在城市重新搜索（候选站来自广州）
            forcedCityId = lastSearchCityId;
            forcedCityName = lastSearchCityName;
            if (poi.lat != 0 && poi.lon != 0) {
                double[] point = Coord.gcjToWgs(poi.lat, poi.lon);
                forcedSearchLat = point[0];
                forcedSearchLon = point[1];
            }
            stationInput.setText(poi.name);
            queryByInput();
        });
        pressFeedback(card);
        return card;
    }

    /** 高德线路卡片：点击显示静态线路详情（站点时间轴）。 */
    private View gaodeLineCard(ChelaileDataSource.GaodeLine line) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("🚌 " + line.name);
        name.setTextSize(17);
        name.setTextColor(LINE_COLOR);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textBox.addView(name);
        TextView route = new TextView(this);
        route.setText(line.startSn + " → " + line.endSn);
        route.setTextSize(12);
        route.setTextColor(MUTED);
        route.setPadding(0, dp(2), 0, 0);
        textBox.addView(route);
        card.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(MUTED);
        card.addView(arrow);

        card.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            sb.append(line.startSn).append(" → ").append(line.endSn).append("\n");
            if (!line.firstTime.isEmpty()) {
                sb.append("首班 ").append(line.firstTime).append(" · 末班 ").append(line.lastTime);
            }
            if (!line.price.isEmpty()) {
                String fare = ChelaileDataSource.formatFare(line.price);
                sb.append(" · ").append(fare).append(fare.contains("元") ? "" : "元");
            }
            sb.append("\n\n途经 ").append(line.stops.size()).append(" 站：\n");
            for (int i = 0; i < line.stops.size(); i++) {
                sb.append(i + 1).append(". ").append(line.stops.get(i)).append("\n");
            }
            sb.append("\n静态线路资料 · 实时到站请在站点页查询");
            new TransitDialog.Builder(this)
                    .setTitle("🚌 " + line.name)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("好的", null)
                    .show();
        });
        pressFeedback(card);
        return card;
    }

    // ── 收藏（车来了式）──────────────────────────────
    private static final String FAV_KEY = "favorites";

    private java.util.Set<String> loadFavoriteIds() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        String raw = prefs.getString(FAV_KEY, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    private boolean isFavorite(String sId) {
        return loadFavoriteIds().contains(sId);
    }

    private java.util.Set<String> loadFavoriteLineKeys() {
        return new java.util.LinkedHashSet<>(prefs.getStringSet(
                "favorite_bus_lines", java.util.Collections.<String>emptySet()));
    }

    private void toggleFavorite(ChelaileDataSource.NearbyStop stop) {
        java.util.Set<String> set = loadFavoriteIds();
        if (set.contains(stop.sId)) {
            set.remove(stop.sId);
        } else {
            set.add(stop.sId);
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String id : set) arr.put(id);
        prefs.edit().putString(FAV_KEY, arr.toString()).apply();
        // 保存站信息（重建时可用）
        prefs.edit().putString("fav_" + stop.sId,
                stop.name + "|" + (stop.cityId == null || stop.cityId.isEmpty() ? selectedCityId : stop.cityId)
                        + "|" + stop.physicalStId + "|" + stop.namesakeStId
                        + "|" + (stop.subway ? 1 : 0))
                .apply();
    }

    /** 显示收藏站点和公交线路。 */
    private void showFavorites() {
        favoriteArrivals.clear();
        PrimaryNavigation.selected(this, 2);
        invalidateHomeRequests();
        selectedStop = null;
        favoritesView = true;
        searchResultsActive = false;
        favBtn.setText("收藏");
        nearbyNav.setTextColor(MUTED);
        favBtn.setTextColor(PRIMARY);
        java.util.Set<String> ids = loadFavoriteIds();
        java.util.Set<String> lineKeys = loadFavoriteLineKeys();
        clearResults();
        if (ids.isEmpty() && lineKeys.isEmpty()) {
            status.setText("暂无收藏");
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.addView(favoritesBackLink(), matchWrap());
            block.addView(emptyCard("暂无收藏"), cardParams());
            resultList.addView(block, cardParams());
            return;
        }
        String countText = "收藏";
        if (!ids.isEmpty()) countText += " · " + ids.size() + " 个站点";
        if (!lineKeys.isEmpty()) countText += " · " + lineKeys.size() + " 条线路";
        status.setText(countText);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(favoritesBackLink(), matchWrap());
        if (!ids.isEmpty()) block.addView(favoriteSectionHeader("站点"));
        for (String id : ids) {
            String info = prefs.getString("fav_" + id, "");
            if (info.isEmpty()) continue;
            String[] parts = info.split("\\|", -1);
            if (parts.length < 4) continue;
            String name = parts[0];
            String cityId = parts[1];
            String physicalStId = parts[2];
            String namesakeStId = parts[3];
            boolean subway = parts.length > 4 && parts[4].equals("1");
            // 重建 NearbyStop（无实时线路，点击时再查）
            ChelaileDataSource.NearbyStop stop = new ChelaileDataSource.NearbyStop(
                    id, name, -1, subway, physicalStId, namesakeStId, cityId,
                    new ArrayList<ChelaileDataSource.LineArrival>(),
                    new ArrayList<ChelaileDataSource.MetroLineInfo>());
            View card = stopCard(stop);
            if (!subway) {
                TextView arrivals = favoriteArrivalLabel();
                ((LinearLayout) card).addView(arrivals, matchWrap());
                favoriteArrivals.add(new FavoriteArrival(stop, "", "", arrivals));
            }
            block.addView(card, cardParams());
            animateIn(card);
        }
        if (!lineKeys.isEmpty()) {
            block.addView(favoriteSectionHeader("线路"));
            java.util.List<String> sortedKeys = new java.util.ArrayList<>(lineKeys);
            java.util.Collections.sort(sortedKeys, (left, right) ->
                    favoriteLineName(left).compareToIgnoreCase(favoriteLineName(right)));
            for (String key : sortedKeys) {
                View card = favoriteLineCard(key);
                if (card == null) continue;
                block.addView(card, cardParams());
                animateIn(card);
            }
        }
        resultList.addView(block, cardParams());
        resultsScroll.post(this::refreshFavoriteArrivals);
    }

    private static final class FavoriteArrival {
        final ChelaileDataSource.NearbyStop stop;
        final String lineId, destination;
        final TextView label;

        FavoriteArrival(ChelaileDataSource.NearbyStop stop, String lineId, String destination, TextView label) {
            this.stop = stop;
            this.lineId = lineId;
            this.destination = destination;
            this.label = label;
        }
    }

    private TextView favoriteArrivalLabel() {
        TextView label = new TextView(this);
        Ui.type(label, Ui.META, true);
        label.setTextColor(ACCENT);
        label.setPadding(0, dp(8), 0, 0);
        label.setText("…");
        return label;
    }

    private void refreshFavoriteArrivals() {
        if (!favoritePageActive || !favoritesView || favoriteRefreshInFlight) return;
        List<FavoriteArrival> visible = new ArrayList<>();
        for (FavoriteArrival arrival : favoriteArrivals) {
            if (arrival.label.getLocalVisibleRect(new android.graphics.Rect())) visible.add(arrival);
            if (visible.size() == 8) break;
        }
        if (visible.isEmpty()) return;
        favoriteRefreshInFlight = true;
        nearbyWorker.execute(() -> {
            java.util.Map<String, List<ChelaileDataSource.StopDetail>> snapshots = new java.util.HashMap<>();
            List<String> values = new ArrayList<>();
            for (FavoriteArrival favorite : visible) {
                if (!favoritePageActive || !favoritesView || Thread.currentThread().isInterrupted()) break;
                ChelaileDataSource.NearbyStop stop = favorite.stop;
                String key = stop.cityId + "|" + stop.physicalStId + "|" + stop.namesakeStId + "|" + stop.sId;
                try {
                    List<ChelaileDataSource.StopDetail> platforms = snapshots.get(key);
                    if (platforms == null) {
                        platforms = favorite.lineId.isEmpty()
                                ? ChelaileDataSource.queryStopWithFallback(stop.cityId, stop)
                                : ChelaileDataSource.queryStopDetail(stop.cityId, stop.physicalStId,
                                        stop.namesakeStId, stop.sId);
                        snapshots.put(key, platforms);
                    }
                    values.add(favoriteArrivalText(favorite, platforms));
                } catch (Exception unavailable) {
                    values.add("暂不可用");
                }
            }
            postUi(() -> {
                favoriteRefreshInFlight = false;
                if (!favoritePageActive || !favoritesView) return;
                for (int i = 0; i < values.size(); i++) {
                    FavoriteArrival favorite = visible.get(i);
                    if (favoriteArrivals.contains(favorite)) favorite.label.setText(values.get(i));
                }
            });
        });
    }

    private String favoriteArrivalText(FavoriteArrival favorite, List<ChelaileDataSource.StopDetail> platforms) {
        java.util.Map<String, ChelaileDataSource.LineArrival> directions = new java.util.LinkedHashMap<>();
        for (ChelaileDataSource.StopDetail platform : platforms) {
            if (!favorite.lineId.isEmpty() && !favorite.stop.sId.equals(platform.sId)) continue;
            if (!TransitRules.samePlatformGroup(favorite.stop.name, platform.name)
                    && !favorite.stop.sId.equals(platform.sId)) continue;
            for (ChelaileDataSource.LineArrival line : platform.lines) {
                if (!favorite.lineId.isEmpty() && !favorite.lineId.equals(line.lineId)) continue;
                ChelaileDataSource.LineArrival old = directions.get(line.lineId);
                if (old == null || lineArrivalQuality(line) > lineArrivalQuality(old)) directions.put(line.lineId, line);
            }
        }
        List<ChelaileDataSource.LineArrival> lines = new ArrayList<>(directions.values());
        lines.sort(java.util.Comparator.comparingInt(line -> {
            ChelaileDataSource.Bus bus = nearestBus(line.buses, line.targetOrder);
            return bus == null ? Integer.MAX_VALUE : bus.travelTime;
        }));
        List<String> rows = new ArrayList<>();
        for (ChelaileDataSource.LineArrival line : lines) {
            if (rows.size() == 4) break;
            List<Integer> times = new ArrayList<>();
            for (ChelaileDataSource.Bus bus : line.buses)
                if (TransitRules.canArrive(bus.travelTime, bus.order, line.targetOrder)) times.add(bus.travelTime);
            java.util.Collections.sort(times);
            String eta = times.isEmpty() ? (line.status == null || line.status.isEmpty() ? "暂无到站" : line.status)
                    : TransitRules.etaLabel(times.get(0));
            if (!favorite.lineId.isEmpty() && times.size() > 1) eta += " / " + TransitRules.etaLabel(times.get(1));
            rows.add(favorite.lineId.isEmpty() ? line.name + " · 往 " + line.endSn + "   " + eta : eta);
        }
        return rows.isEmpty() ? "暂无到站" : android.text.TextUtils.join("\n", rows);
    }

    private TextView favoriteSectionHeader(String value) {
        TextView header = new TextView(this);
        header.setText(value);
        header.setTextSize(13);
        header.setTextColor(TEXT);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(dp(4), dp(10), dp(4), dp(8));
        return header;
    }

    private String favoriteLineName(String key) {
        String info = prefs.getString("favorite_bus_line_" + key.hashCode(), "");
        String[] parts = info.split("\\|", 3);
        return parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : "公交线路";
    }

    /** 收藏线路卡：保留收藏时的方向 lineId，避免再次搜索后打开到反方向。 */
    private View favoriteLineCard(String key) {
        String info = prefs.getString("favorite_bus_line_" + key.hashCode(), "");
        String[] keyParts = key.split("\\|", 2);
        String[] parts = info.split("\\|", 3);
        String cityId = parts.length >= 2 ? parts[0] : (keyParts.length >= 1 ? keyParts[0] : "");
        String lineId = parts.length >= 2 ? parts[1] : (keyParts.length >= 2 ? keyParts[1] : "");
        String lineName = parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : "公交线路";
        if (cityId.isEmpty() || lineId.isEmpty()) return null;

        String cityName = currentCity.name;
        double lat = currentLat;
        double lon = currentLon;
        for (CityInfo city : CityInfo.all()) {
            if (city.chelaileId.equals(cityId)) {
                cityName = city.name;
                if (TransitRules.validCoordinate(city.lat, city.lon)) {
                    lat = city.lat;
                    lon = city.lon;
                }
                break;
            }
        }
        final double targetLat = lat;
        final double targetLon = lon;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackground(rounded(Ui.SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("🚌 " + lineName);
        name.setTextSize(18);
        name.setTextColor(PRIMARY_DARK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(name, matchWrap());
        TextView detail = new TextView(this);
        String[] target = prefs.getString("favorite_bus_target_" + key, "").split("\\|", -1);
        boolean hasTarget = target.length >= 5 && !target[0].isEmpty();
        detail.setText(hasTarget ? target[1] + " · 往 " + target[4] : cityName + " · 请选择站点");
        detail.setTextSize(12);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(4), 0, 0);
        textBox.addView(detail, matchWrap());
        if (hasTarget) {
            TextView arrivals = favoriteArrivalLabel();
            textBox.addView(arrivals, matchWrap());
            favoriteArrivals.add(new FavoriteArrival(new ChelaileDataSource.NearbyStop(
                    target[0], target[1], -1, false, target[2], target[3], cityId,
                    java.util.Collections.emptyList(), java.util.Collections.emptyList()),
                    lineId, target[4], arrivals));
        }
        card.addView(textBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new CenteredIconView(this,"chevron",Ui.MUTED);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(12), 0, dp(2), 0);
        card.addView(arrow);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, LineDetailActivity.class);
            intent.putExtra("cityId", cityId);
            intent.putExtra("lineId", lineId);
            intent.putExtra("lineName", lineName);
            intent.putExtra("stationName", hasTarget ? target[1] : "");
            if (hasTarget) {
                intent.putExtra("stationId", target[0]);
                intent.putExtra("physicalStId", target[2]);
                intent.putExtra("namesakeStId", target[3]);
            }
            double boardingLat = hasTarget ? 0 : targetLat;
            double boardingLon = hasTarget ? 0 : targetLon;
            if (hasTarget && target.length >= 7) {
                try {
                    double savedLat = Double.parseDouble(target[5]);
                    double savedLon = Double.parseDouble(target[6]);
                    if (TransitRules.validCoordinate(savedLat, savedLon)) {
                        boardingLat = savedLat;
                        boardingLon = savedLon;
                    }
                } catch (NumberFormatException ignored) { }
            }
            intent.putExtra("stopLat", boardingLat);
            intent.putExtra("stopLon", boardingLon);
            intent.putExtra("userLat", currentLat);
            intent.putExtra("userLon", currentLon);
            startActivity(intent);
            Ui.transitionForward(this);
        });
        pressFeedback(card);
        return card;
    }

    /** 收藏页顶部返回主页入口。 */
    private TextView favoritesBackLink() {
        TextView back = new TextView(this);
        back.setText("‹ 返回主页");
        back.setTextSize(13);
        back.setTextColor(PRIMARY);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        back.setPadding(0, dp(6), 0, dp(6));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> backToHome());
        return back;
    }

    /** 收藏按钮开关：收藏视图时再点一次回到主页。 */
    private void toggleFavorites() {
        if (favoritesView) {
            backToHome();
        } else {
            showFavorites();
        }
    }

    /** 回到主页：恢复附近站点列表（地图标记不变）。 */
    private void backToHome() {
        PrimaryNavigation.selected(this, 0);
        if (nearbyNav != null) nearbyNav.setTextColor(PRIMARY);
        if (favBtn != null) favBtn.setTextColor(MUTED);
        invalidateHomeRequests();
        favoritesView = false;
        searchResultsActive = false;
        favBtn.setText("收藏");
        selectedStop = null;
        mapView.setSelectedStation(null);
        clearResults();
        expandMap();
        if (nearbyStops != null && !nearbyStops.isEmpty()) {
            status.setText("");
            renderNearbyBlock(nearbyStops);
        } else {
            status.setText("定位后显示附近站点实时车辆");
        }
        loadNearbyStations();
    }

    // ── 通用工具 ──────────────────────────────────────
    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        queryButton.setEnabled(!loading);
        queryButton.setAlpha(loading ? .55f : 1f);
    }

    private void postUi(Runnable action) {
        uiHandler.post(() -> {
            if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            action.run();
        });
    }

    private View emptyCard(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextSize(14);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(38), dp(20), dp(38));
        empty.setBackground(rounded(Ui.SURFACE, 16));
        empty.setLayoutParams(cardParams());
        return empty;
    }

    private void animateIn(View view) {
        Ui.animateIn(view, this);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(foreground);
        button.setTextSize(12);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(rounded(background, 12));
        Ui.decorateButton(button, background, foreground);
        return button;
    }

    /** 按压反馈：按下缩小，松开回弹。 */
    private void pressFeedback(View view) {
        Ui.pressFeedback(view);
    }

    private void legacyPressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(.93f).scaleY(.93f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                    break;
            }
            return false;
        });
    }

    private Drawable rounded(int color, int radiusDp) {
        if (color == Color.WHITE || color == 0xFFFFFFFF) {
            return Ui.edgeCard(this, PRIMARY, radiusDp);
        }
        return Ui.rounded(this, color, radiusDp);
    }

    private LinearLayout.LayoutParams marginLp(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(leftDp), 0, dp(leftDp), 0);
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(12));
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainResumed=true;
        if(!privacyReady)return;
        resumeHome();
    }

    private void resumeHome() {
        PrimaryNavigation.updateAvailability(this, primaryBar);
        if (mapView != null) mapView.updateMapCredential();
        favoritePageActive = true;
        // 从线路详情取消收藏返回时，立即移除卡片，不保留过期列表。
        if (favoritesView && resultList != null) showFavorites();
        homeRefreshHandler.removeCallbacks(homeRefreshTask);
        homeRefreshHandler.postDelayed(homeRefreshTask, resumedBefore ? 0L : 15_000L);
        resumedBefore = true;
        homeRefreshHandler.removeCallbacks(homeAgeTask);
        homeRefreshHandler.post(homeAgeTask);
    }

    @Override
    protected void onPause() {
        mainResumed=false;
        favoritePageActive = false;
        super.onPause();
        homeRefreshHandler.removeCallbacks(homeRefreshTask);
        homeRefreshHandler.removeCallbacks(homeAgeTask);
        manualFixRequestAt = 0;
        stopLocationUpdates();
    }

    @Override
    public void onBackPressed() {
        if (favoritesView) {
            backToHome();
            return;
        }
        if (searchResultsActive) {
            backToHome();
            return;
        }
        if (selectedStop != null) {
            collapseStop();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        homeRefreshHandler.removeCallbacks(homeRefreshTask);
        homeRefreshHandler.removeCallbacks(homeAgeTask);
        uiHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        nearbyWorker.shutdownNow();
        cityWorker.shutdownNow();
        mapWorker.shutdownNow();
        stopLocationUpdates();
        if (mapSizeAnimator != null) mapSizeAnimator.cancel();
        try {
            if (locationListener != null) locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
    }
}
