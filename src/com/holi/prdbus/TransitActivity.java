package com.holi.prdbus;

import android.app.Activity;
import static com.holi.prdbus.Ui.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 换乘规划页（车来了式）：输入起终点站名 → 高德换乘方案。 */
public class TransitActivity extends ThemedActivity {

    private static final int LINE_COLOR = Color.rgb(82, 138, 196);

    private String cityId = "019";
    private String cityName = "佛山";
    private double currentLat;
    private double currentLon;
    private boolean hasLocation;
    private int routePreference;
    private final java.util.List<Button> preferenceButtons = new java.util.ArrayList<>();

    private EditText originInput;
    private EditText destInput;
    private TextView originErrorMark;
    private TextView destErrorMark;
    private LinearLayout listRoot;
    private LinearLayout headerBox;
    private LinearLayout plannerPage;
    private TextView cityHeading;
    private View progress;
    private TextView status;

    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService suggestWorker =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private final android.os.Handler suggestHandler = new android.os.Handler();
    private LinearLayout originSuggestBox;
    private LinearLayout destSuggestBox;
    private LinearLayout historyBox;
    private volatile int originSuggestSequence;
    private volatile int destSuggestSequence;
    private boolean suppressSuggestion;
    private ChelaileDataSource.GaodeTip selectedOriginTip;
    private ChelaileDataSource.GaodeTip selectedDestTip;
    private int planSequence;
    private java.util.concurrent.Future<?> pendingPlan;
    private android.location.LocationManager gps;
    private android.location.LocationListener freshListener;
    private Runnable freshTimeout;
    private static final String MY_LOCATION = "我的位置";

    private void invalidatePlan() {
        planSequence++;
        stopFreshLocation();
        if (pendingPlan != null) pendingPlan.cancel(true);
        if (progress != null) progress.setVisibility(View.GONE);
        if (listRoot != null) listRoot.removeAllViews();
        if (status != null) status.setText("地点或偏好已更改 · 请查询新方案");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppSettings.hasMapKey()) { finish(); return; }
        if (getIntent() != null) {
            cityId = getIntent().getStringExtra("cityId");
            if (cityId == null) cityId = "019";
            cityName = getIntent().getStringExtra("cityName");
            if (cityName == null) cityName = "佛山";
            currentLat = getIntent().getDoubleExtra("currentLat", 0);
            currentLon = getIntent().getDoubleExtra("currentLon", 0);
            hasLocation = getIntent().getBooleanExtra("hasLocation", false)
                    && currentLat != 0 && currentLon != 0;
        }
        applyEdgeToEdge();
        buildUi();
        applyInsets();
        String goName = getIntent().getStringExtra("goName");
        if (goName != null && savedInstanceState == null) {
            double[] destinationPoint = Coord.wgsToGcj(getIntent().getDoubleExtra("goLat", 0),
                    getIntent().getDoubleExtra("goLon", 0));
            suppressSuggestion = true;
            destInput.setText(goName);
            boolean metro = getIntent().getBooleanExtra("goMetro", false);
            selectedDestTip = new ChelaileDataSource.GaodeTip(goName, metro ? "地铁站" : "公交站点",
                    destinationPoint[0], destinationPoint[1], cityName, metro ? 0 : 100);
            originInput.setText(MY_LOCATION);
            suppressSuggestion = false;
            dismissSuggest();
            query();
        }
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        if (originInput == null) return;
        suppressSuggestion = true;
        originInput.setText(state.getString("draftOrigin", ""));
        destInput.setText(state.getString("draftDest", ""));
        selectedOriginTip = restoreTip(state.getBundle("originTip"));
        selectedDestTip = restoreTip(state.getBundle("destTip"));
        suppressSuggestion = false; dismissSuggest();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (originInput == null) { super.onSaveInstanceState(state); return; }
        state.putString("draftOrigin", originInput.getText().toString());
        state.putString("draftDest", destInput.getText().toString());
        state.putBundle("originTip", saveTip(selectedOriginTip)); state.putBundle("destTip", saveTip(selectedDestTip));
        super.onSaveInstanceState(state);
    }
    private Bundle saveTip(ChelaileDataSource.GaodeTip tip) {
        if (tip == null) return null;
        Bundle state = new Bundle(); state.putString("name", tip.name); state.putString("sub", tip.sub);
        state.putDouble("lat", tip.lat); state.putDouble("lon", tip.lon); state.putString("city", tip.city); state.putInt("type", tip.typePriority); return state;
    }
    private ChelaileDataSource.GaodeTip restoreTip(Bundle state) {
        return state == null ? null : new ChelaileDataSource.GaodeTip(state.getString("name"), state.getString("sub"),
                state.getDouble("lat"), state.getDouble("lon"), state.getString("city"), state.getInt("type"));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || !intent.hasExtra(PrimaryNavigation.TAB)) return;
        currentLat = intent.getDoubleExtra("currentLat", currentLat);
        currentLon = intent.getDoubleExtra("currentLon", currentLon);
        hasLocation = intent.getBooleanExtra("hasLocation", hasLocation)
                && TransitRules.validCoordinate(currentLat, currentLon);
        String city = intent.getStringExtra("cityName"), id = intent.getStringExtra("cityId");
        if (city != null && !city.equals(cityName)) {
            invalidatePlan();
            dismissSuggest();
            if (city != null) cityName = city;
            if (id != null) cityId = id;
            if (cityHeading != null) cityHeading.setText(cityName);
        }
    }

    private void applyEdgeToEdge() { Ui.edgeToEdge(this); }

    private void applyInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                if (headerBox != null) {
                    headerBox.setPadding(dp(16), dp(7) + bars.top, dp(16), dp(9));
                }
                int keyboard = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if (plannerPage != null) plannerPage.setPadding(0, 0, 0, Math.max(bars.bottom, keyboard));
            } else {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (headerBox != null) {
                    headerBox.setPadding(dp(16), dp(7) + top, dp(16), dp(9));
                }
                if (plannerPage != null) plannerPage.setPadding(0, 0, 0, bottom);
            }
            return insets;
        });
    }

    private ScrollView pageScroll;
    private View resultsStatusRow;

    private void buildUi() {
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout page = new LinearLayout(this);
        plannerPage = page;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(dp(16), dp(7), dp(16), dp(9));
        headerBox.setBackgroundColor(Ui.SURFACE);
        PrimaryHeader heading = new PrimaryHeader(this, "路线规划", cityName, null);
        cityHeading = heading.cityLabel;
        headerBox.addView(heading, matchWrap());
        page.addView(headerBox, matchWrap());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.VERTICAL);
        inputBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        inputBox.setBackground(Ui.edgeCard(this, PRIMARY, 16));
        inputBox.addView(endpointRow(true), matchWrap());
        LinearLayout.LayoutParams destinationLp = matchWrap();
        destinationLp.topMargin = dp(8);
        inputBox.addView(endpointRow(false), destinationLp);

        originSuggestBox = createSuggestBox();
        destSuggestBox = createSuggestBox();
        inputBox.addView(suggestionViewport(originSuggestBox), matchWrap());
        inputBox.addView(suggestionViewport(destSuggestBox), matchWrap());
        setupAutoComplete(originInput);
        setupAutoComplete(destInput);

        TextView preferenceLabel = plannerText("路线偏好", Ui.META, MUTED, false);
        preferenceLabel.setPadding(0, dp(16), 0, dp(8));
        inputBox.addView(preferenceLabel, matchWrap());
        routePreference = Math.max(0, Math.min(2, getSharedPreferences("prdbus", MODE_PRIVATE)
                .getInt("route_preference", 0)));
        LinearLayout preferenceRow = new LinearLayout(this);
        for (int i = 0; i < TransitExperience.PREFERENCES.length; i++) {
            final int choice = i;
            Button option = Ui.button(this, TransitExperience.PREFERENCES[i],
                    i == routePreference ? PRIMARY : Ui.RAISED,
                    i == routePreference ? Ui.ON_PRIMARY : TEXT);
            option.setPadding(dp(6), 0, dp(6), 0);
            option.setTextSize(13);
            option.setMaxLines(2);
            option.setSelected(i == routePreference);
            preferenceButtons.add(option);
            option.setOnClickListener(v -> {
                if (routePreference == choice) return;
                routePreference = choice;
                getSharedPreferences("prdbus", MODE_PRIVATE).edit()
                        .putInt("route_preference", choice).apply();
                for (int j = 0; j < preferenceButtons.size(); j++) {
                    Button button = preferenceButtons.get(j);
                    button.setTextColor(j == choice ? Ui.ON_PRIMARY : TEXT);
                    button.setBackground(Ui.rounded(this, j == choice ? PRIMARY : Ui.RAISED, 12));
                    button.setSelected(j == choice);
                }
                invalidatePlan();
                status.setText("已选择" + TransitExperience.PREFERENCES[choice] + " · 点击查询路线");
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 48), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            preferenceRow.addView(option, lp);
        }
        inputBox.addView(preferenceRow, matchWrap());
        Button queryButton = Ui.button(this, "查询路线", PRIMARY, Color.WHITE);
        queryButton.setTextSize(15);
        queryButton.setOnClickListener(v -> query());
        LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(-1, Ui.textRowHeight(this, 48));
        queryLp.topMargin = dp(12);
        inputBox.addView(queryButton, queryLp);
        LinearLayout.LayoutParams inputLp = matchWrap();
        inputLp.setMargins(dp(16), dp(12), dp(16), dp(8));
        content.addView(inputBox, inputLp);

        LinearLayout stateRow = new LinearLayout(this);
        resultsStatusRow = stateRow;
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        progress = Ui.loadingDots(this);
        progress.setVisibility(View.GONE);
        stateRow.addView(progress, new LinearLayout.LayoutParams(dp(38), dp(22)));
        status = plannerText("选择起点和终点", Ui.META, MUTED, false);
        status.setPadding(dp(4), 0, 0, 0);
        stateRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(stateRow, matchWrap());

        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(dp(16), 0, dp(16), dp(12));
        content.addView(listRoot, matchWrap());
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setPadding(dp(16), dp(4), dp(16), dp(16));
        historyBox.setVisibility(View.GONE);
        content.addView(historyBox, matchWrap());
        loadHistory();

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        pageScroll.addView(content, matchWrap());
        page.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navigation = PrimaryNavigation.create(this, 3);
        page.addView(navigation, matchWrap());
        PrimaryNavigation.bind(page, navigation, 3);
        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(Ui.edgeBackdrop(this), new android.widget.FrameLayout.LayoutParams(-1, -1));
        shell.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        progress = Ui.centerLoader(this, progress);
    }

    private TextView plannerText(String value, int size, int color, boolean emphasis) {
        TextView view = new TextView(this);
        view.setText(value);
        Ui.type(view, size, emphasis);
        view.setTextColor(color);
        return view;
    }

    private Button endpointAction(String icon, String description) {
        Button button = Ui.button(this, "", LIGHT_BLUE, PRIMARY);
        Ui.icon(button, icon, PRIMARY);
        button.setContentDescription(description);
        button.setPadding(dp(14), 0, 0, 0);
        button.setCompoundDrawablePadding(0);
        return button;
    }

    private View endpointRow(boolean origin) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = plannerText(origin ? "起" : "终", 13, origin ? PRIMARY : ACCENT, true);
        label.setGravity(Gravity.CENTER);
        label.setBackground(rounded(origin ? LIGHT_BLUE : Ui.LIGHT_ORANGE, 12));
        row.addView(label, new LinearLayout.LayoutParams(dp(28), dp(28)));

        android.widget.FrameLayout field = new android.widget.FrameLayout(this);
        field.setBackground(rounded(Ui.RAISED, 12));
        EditText input = new EditText(this);
        input.setHint(origin ? "输入起点" : "输入终点");
        input.setContentDescription(origin ? "起点，搜索站点或地点" : "终点，搜索站点或地点");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        Ui.type(input,Ui.BODY,false);
        input.setSingleLine(true);
        input.setPadding(dp(12), dp(8), dp(48), dp(8));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(origin ? android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                : android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.addView(input, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView error = endpointErrorMark();
        android.widget.FrameLayout.LayoutParams errorLp = new android.widget.FrameLayout.LayoutParams(
                dp(48), -1, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        error.setBackgroundColor(Color.TRANSPARENT);
        field.addView(error, errorLp);
        error.setOnClickListener(v -> {
            input.requestFocus();
            status.setText("请从候选列表重新选择" + (origin ? "起点" : "终点"));
            String keyword = input.getText().toString().trim();
            if (keyword.length() >= 2) fetchSuggest(input, keyword, nextSuggestSequence(input));
        });
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, Ui.textRowHeight(this, 52), 1f);
        fieldLp.leftMargin = dp(8);
        row.addView(field, fieldLp);
        Button action = endpointAction(origin ? "locate" : "swap",
                origin ? "使用我的位置作为起点" : "交换起点和终点");
        action.setOnClickListener(v -> { if (origin) useMyLocation(); else swapEndpoints(); });
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(dp(48), Ui.textRowHeight(this, 52));
        actionLp.leftMargin = dp(8);
        row.addView(action, actionLp);
        if (origin) { originInput = input; originErrorMark = error; }
        else { destInput = input; destErrorMark = error; }
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (origin && actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                destInput.requestFocus();
                return true;
            }
            if (!origin && actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                query();
                return true;
            }
            return false;
        });
        return row;
    }

    /** 提交时获取新鲜定位。 */
    private void useMyLocation() {
        invalidatePlan();
        suppressSuggestion = true;
        originInput.setText(MY_LOCATION);
        originInput.setSelection(originInput.length());
        suppressSuggestion = false;
        selectedOriginTip = null;
        setEndpointError(originErrorMark, false);
        dismissSuggest(originInput);
        destInput.requestFocus();
    }

    private void swapEndpoints() {
        invalidatePlan();
        String origin = originInput.getText().toString();
        String destination = destInput.getText().toString();
        ChelaileDataSource.GaodeTip oldOrigin = selectedOriginTip;
        suppressSuggestion = true;
        originInput.setText(destination);
        destInput.setText(origin);
        originInput.setSelection(originInput.length());
        destInput.setSelection(destInput.length());
        suppressSuggestion = false;
        selectedOriginTip = selectedDestTip;
        selectedDestTip = oldOrigin;
        setEndpointError(originErrorMark, false);
        setEndpointError(destErrorMark, false);
        dismissSuggest(originInput);
        dismissSuggest(destInput);
        originInput.setTranslationX(dp(12));
        destInput.setTranslationX(-dp(12));
        originInput.animate().translationX(0).setDuration(Ui.duration(this, 180)).setInterpolator(Ui.easeOut()).start();
        destInput.animate().translationX(0).setDuration(Ui.duration(this, 180)).setInterpolator(Ui.easeOut()).start();
    }

    private ScrollView suggestionViewport(LinearLayout contents) {
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int available = View.MeasureSpec.getSize(height);
                int cap = dp(240);
                if (available > 0 && View.MeasureSpec.getMode(height) != View.MeasureSpec.UNSPECIFIED)
                    cap = Math.min(cap, available);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setNestedScrollingEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(contents, matchWrap());
        return scroll;
    }

    private LinearLayout createSuggestBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.edgeCard(this, PRIMARY, 14));
        box.setPadding(dp(6), dp(5), dp(6), dp(6));
        box.setVisibility(View.GONE);
        return box;
    }

    private TextView endpointErrorMark() {
        TextView mark = new TextView(this);
        mark.setText("×");
        mark.setTextSize(22);
        mark.setTextColor(Color.rgb(210, 72, 72));
        mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mark.setGravity(Gravity.CENTER);
        mark.setContentDescription("未选择精确地点，请重新选择地点");
        mark.setBackground(rounded(Color.rgb(255, 237, 237), 11));
        mark.setVisibility(View.GONE);
        return mark;
    }

    private LinearLayout.LayoutParams endpointErrorLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(50));
        lp.leftMargin = dp(5);
        return lp;
    }

    private void setEndpointError(TextView marker, boolean show) {
        if (marker != null) marker.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 输入联想：独立容器 + 请求序号，旧请求不能覆盖新关键字。 */
    private void setupAutoComplete(final EditText input) {
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                invalidatePlan();
                if (suppressSuggestion) return;
                if (historyBox != null) historyBox.setVisibility(View.GONE);
                if (input == originInput) selectedOriginTip = null;
                else selectedDestTip = null;
                setEndpointError(input == originInput ? originErrorMark : destErrorMark, false);
                final String kw = s.toString().trim();
                suggestBoxFor(input).setVisibility(View.GONE);
                suggestHandler.removeCallbacksAndMessages(null);
                if (kw.length() < 2) {
                    dismissSuggest(input);
                    return;
                }
                final int sequence = nextSuggestSequence(input);
                // 减少连续输入时对高德联想接口的无效请求，同时保持体感及时。
                suggestHandler.postDelayed(() -> fetchSuggest(input, kw, sequence), 450);
            }
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                if (historyBox != null) historyBox.setVisibility(View.GONE);
                String keyword = input.getText().toString().trim();
                ChelaileDataSource.GaodeTip chosen = input == originInput ? selectedOriginTip : selectedDestTip;
                if (keyword.length() >= 2 && (chosen == null || !keyword.equals(chosen.name))) {
                    int sequence = nextSuggestSequence(input);
                    suggestHandler.postDelayed(() -> {
                        if (isCurrentSuggestion(input, keyword, sequence)) fetchSuggest(input, keyword, sequence);
                    }, 200);
                }
            } else {
                suggestHandler.postDelayed(() -> {
                    if (!input.hasFocus()) dismissSuggest(input);
                }, 180);
            }
        });
    }

    private int nextSuggestSequence(EditText input) {
        return input == originInput ? ++originSuggestSequence : ++destSuggestSequence;
    }

    private boolean isCurrentSuggestion(EditText input, String keyword, int sequence) {
        int current = input == originInput ? originSuggestSequence : destSuggestSequence;
        return sequence == current && input.hasFocus()
                && keyword.equals(input.getText().toString().trim());
    }

    private LinearLayout suggestBoxFor(EditText input) {
        return input == originInput ? originSuggestBox : destSuggestBox;
    }

    private void fetchSuggest(final EditText input, final String kw, final int sequence) {
        if (MY_LOCATION.equals(kw)) return;
        final boolean origin = input == originInput;
        final String searchCity = cityName;
        suggestWorker.execute(() -> {
            if (sequence != (origin ? originSuggestSequence : destSuggestSequence)) return;
            java.util.List<ChelaileDataSource.GaodeTip> tips = new java.util.ArrayList<>();
            try { metroCache = new MetroDataSource(this).queryLines(); }
            catch (Exception ignored) { }
            // 内置地铁先加入，确保“北京路”等精确站名不被外市同名道路挤掉。
            appendLocalMetroSuggestions(kw, tips, metroCache);
            try {
                if (sequence != (origin ? originSuggestSequence : destSuggestSequence)) return;
                boolean exactLocalMetro = false;
                for (ChelaileDataSource.GaodeTip tip : tips) {
                    if (normalizeSuggestName(kw).equals(normalizeSuggestName(tip.name))) {
                        exactLocalMetro = true;
                        break;
                    }
                }
                if (!exactLocalMetro) tips.addAll(ChelaileDataSource.gaodeInputTips(kw, searchCity, tips.isEmpty()));
            } catch (Exception ignored) {
            }
            final java.util.List<ChelaileDataSource.GaodeTip> ranked =
                    rankSuggestions(kw, tips);
            postUi(() -> {
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                if (isCurrentSuggestion(input, kw, sequence)) {
                    showSuggest(input, ranked, sequence);
                }
            });
        });
    }

    private void appendLocalMetroSuggestions(String keyword,
                                              java.util.List<ChelaileDataSource.GaodeTip> out,
                                              java.util.List<MetroDataSource.MetroLine> lines) {
        if (lines == null) return;
        String query = normalizeSuggestName(keyword);
        if (query.isEmpty()) return;
        java.util.Set<String> added = new java.util.HashSet<>();
        for (MetroDataSource.MetroLine line : lines) {
            if (!sameMetroProvince(line)) continue;
            for (MetroDataSource.Station station : line.stations) {
                String city = metroCityForStation(line, station.lat, station.lon);
                String stationKey = normalizeSuggestName(station.name);
                if (!stationKey.equals(query) && !stationKey.startsWith(query)
                        && !stationKey.contains(query)) continue;
                String unique = station.name + "|" + city;
                if (!added.add(unique)) continue;
                out.add(new ChelaileDataSource.GaodeTip(
                        station.name, "内置地铁 · " + line.name,
                        station.lat, station.lon, city, 0));
            }
        }
    }

    private boolean sameMetroProvince(MetroDataSource.MetroLine line) {
        String lineCity = MetroDataSource.cityOfLine(line);
        String province = Regions.province(cityName);
        return province.isEmpty() ? lineCity.equals(cityName) : province.equals(Regions.province(lineCity));
    }

    /** 广佛线等跨城线路名不带城市前缀，按站点坐标显示实际城市。 */
    private static String metroCityForStation(MetroDataSource.MetroLine line, double lat, double lon) {
        if (!"广佛线".equals(line.name)) return MetroDataSource.cityOfLine(line);
        CityInfo city = CityInfo.nearest(lat, lon);
        return city == null || !"44".equals(Regions.province(city.name)) ? "广州" : city.name;
    }

    private java.util.List<ChelaileDataSource.GaodeTip> rankSuggestions(
            final String keyword, java.util.List<ChelaileDataSource.GaodeTip> tips) {
        java.util.Map<String, ChelaileDataSource.GaodeTip> unique =
                new java.util.LinkedHashMap<>();
        for (ChelaileDataSource.GaodeTip tip : tips) {
            String key = TransitRules.suggestionKey(tip.name, tip.city, tip.typePriority);
            ChelaileDataSource.GaodeTip old = unique.get(key);
            if (old == null || suggestionScore(keyword, tip) < suggestionScore(keyword, old)) {
                unique.put(key, tip);
            }
        }
        java.util.List<ChelaileDataSource.GaodeTip> out =
                new java.util.ArrayList<>(unique.values());
        java.util.Collections.sort(out, (a, b) -> {
            int c = Integer.compare(suggestionScore(keyword, a), suggestionScore(keyword, b));
            if (c != 0) return c;
            c = a.name.compareToIgnoreCase(b.name);
            if (c != 0) return c;
            return a.city.compareToIgnoreCase(b.city);
        });
        return out;
    }

    private int suggestionScore(String keyword, ChelaileDataSource.GaodeTip tip) {
        String q = normalizeSuggestName(keyword);
        String name = normalizeSuggestName(tip.name);
        int score = tip.typePriority * 30;
        if (name.equals(q)) score -= 100;
        else if (name.startsWith(q)) score -= 65;
        else if (name.contains(q)) score -= 35;
        if (cityName.equals(tip.city)) score -= 20;
        if (hasLocation && TransitRules.validCoordinate(tip.lat, tip.lon)) {
            double[] point = Coord.gcjToWgs(tip.lat, tip.lon);
            score += (int) Math.min(42, GeoUtils.distanceKm(currentLat, currentLon, point[0], point[1]) / 2);
        }
        if (tip.sub != null && tip.sub.contains("内置地铁")) score -= 60;
        if (tip.sub != null && tip.sub.contains("号线")) score -= 10;
        return score;
    }

    private static String normalizeSuggestName(String value) {
        if (value == null) return "";
        return TransitRules.placeNameKey(value);
    }

    private void showSuggest(final EditText input,
                             java.util.List<ChelaileDataSource.GaodeTip> tips,
                             int sequence) {
        if (!isCurrentSuggestion(input, input.getText().toString().trim(), sequence)) return;
        dismissSuggest(input == originInput ? destInput : originInput);
        if (tips == null || tips.isEmpty()) return;
        LinearLayout suggestBox = suggestBoxFor(input);
        // Bounded full-width suggestion list, below both endpoints.
        suggestBox.removeAllViews();
        suggestBox.setVisibility(View.VISIBLE);
        // 关闭按钮（✕）
        LinearLayout closeRow = new LinearLayout(this);
        closeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView candidateTitle = plannerText(input == originInput ? "选择起点" : "选择终点",
                Ui.META, MUTED, true);
        candidateTitle.setPadding(dp(12), 0, 0, 0);
        closeRow.addView(candidateTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeBtn = new TextView(this);
        closeBtn.setText("");
        Ui.icon(closeBtn, "close", MUTED);
        closeBtn.setMinWidth(dp(48));
        Ui.target(closeBtn);
        closeBtn.setContentDescription("关闭地点候选");
        closeBtn.setTextSize(14);
        closeBtn.setTextColor(MUTED);
        closeBtn.setPadding(dp(8), 0, dp(2), dp(2));
        closeBtn.setClickable(true);
        closeBtn.setOnClickListener(v -> dismissSuggest(input));
        closeRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        suggestBox.addView(closeRow, matchWrap());
        final List<MetroDataSource.MetroLine> metroSnapshot = metroCache == null
                ? new java.util.ArrayList<MetroDataSource.MetroLine>() : metroCache;
        int maxShow = Math.min(6, tips.size());
        for (int p = 0; p < maxShow; p++) {
            final ChelaileDataSource.GaodeTip tip = tips.get(p);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            item.setClickable(true);
            item.setFocusable(false);
            item.setOnClickListener(v -> {
                suppressSuggestion = true;
                input.setText(tip.name);
                input.setSelection(tip.name.length());
                if (input == originInput) selectedOriginTip = tip;
                else selectedDestTip = tip;
                setEndpointError(input == originInput ? originErrorMark : destErrorMark, false);
                suppressSuggestion = false;
                dismissSuggest(input);
                status.setText((input == originInput ? "起点" : "终点") + "已选择 · 点击查询路线");
                if (input == originInput && destInput.length() == 0) destInput.requestFocus();
            });
            TextView name = new TextView(this);
            name.setText(tip.name);
            Ui.type(name, Ui.BODY, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(TEXT);
            item.addView(name);
            TextView sub = new TextView(this);
            String lineInfo = tip.sub == null ? "" : tip.sub.replace(";", "/");
            String candidateCity = tip.city == null || tip.city.isEmpty() ? cityName : tip.city;
            sub.setText(candidateCity + (lineInfo.isEmpty() ? "" : " · " + lineInfo));
            Ui.type(sub, Ui.META, false);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setTextColor(MUTED);
            item.addView(sub);
            // 线路明细区（地铁：本地秒出；公交：车来了异步）
            final TextView detail = new TextView(this);
            Ui.type(detail, Ui.META, false);
            detail.setTextColor(MUTED);
            detail.setPadding(0, dp(4), 0, 0);
            item.addView(detail);
            String metroDetail = tip.typePriority == 0 ? metroDetailText(tip, metroSnapshot) : "";
            if (!metroDetail.isEmpty()) {
                detail.setText(metroDetail);
            } else {
                detail.setText(tip.typePriority == 0 ? "地铁站" :
                        tip.typePriority == 1 ? "公交站" : "地点");
            }
            suggestBox.addView(item, matchWrap());
            if (p < maxShow - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Ui.OUTLINE);
                suggestBox.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
    }

    private volatile List<MetroDataSource.MetroLine> metroCache;

    /** 地铁站线路明细：本地数据算"坐几站 · 几分钟"。 */
    private String metroDetailText(ChelaileDataSource.GaodeTip tip, List<MetroDataSource.MetroLine> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean isMetro = tip.sub != null && tip.sub.contains("号线");
        String candidateCity = tip.city == null || tip.city.isEmpty() ? cityName : tip.city;
        for (MetroDataSource.MetroLine line : lines) {
            if (!line.name.startsWith(candidateCity)) continue;
            java.util.List<MetroDataSource.Station> stations = line.stations;
            for (int i = 0; i < stations.size(); i++) {
                if (!stations.get(i).name.equals(tip.name)) continue;
                int remain = stations.size() - 1 - i;
                if (remain < 1) break;
                // 到终点累计时间（站距/0.55km/min）
                int cum = 0;
                for (int k = i; k < stations.size() - 1; k++) {
                    MetroDataSource.Station a = stations.get(k);
                    MetroDataSource.Station b = stations.get(k + 1);
                    double d = (a.lat != 0 && b.lat != 0)
                            ? Math.sqrt((a.lat - b.lat) * (a.lat - b.lat)
                            + (a.lon - b.lon) * (a.lon - b.lon)) * 111 : 1.5;
                    cum += Math.max(1, (int) Math.round(d / 0.55));
                }
                sb.append(isMetro ? "🚇 " : "🚆 ")
                        .append(line.name.substring(2)).append(" · 往 ")
                        .append(stations.get(stations.size() - 1).name)
                        .append(" ").append(remain).append(" 站 · 约 ").append(cum)
                        .append(" 分钟\n");
                break;
            }
        }
        return sb.toString();
    }

    private void dismissSuggest(EditText input) {
        LinearLayout box = suggestBoxFor(input);
        if (box != null) {
            box.setVisibility(View.GONE);
            box.removeAllViews();
        }
    }

    private void dismissSuggest() {
        suggestHandler.removeCallbacksAndMessages(null);
        dismissSuggest(originInput);
        dismissSuggest(destInput);
    }

    // ── 历史搜索（最近10次）────────────────────────────
    private static final String HISTORY_PREFS = "prdbus_transit";
    private static final String HISTORY_KEY = "history";

    private void saveHistory(String origin, String dest,
                             ChelaileDataSource.GaodeTip originTip,
                             ChelaileDataSource.GaodeTip destTip) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("origin", origin);
            entry.put("dest", dest);
            putHistoryTip(entry, "origin", originTip);
            putHistoryTip(entry, "dest", destTip);
            String identity = origin + " → " + dest;
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString(HISTORY_KEY, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            out.put(entry);
            for (int i = 0; i < arr.length() && out.length() < 10; i++) {
                Object old = arr.opt(i);
                if (!identity.equals(historyIdentity(old))) out.put(old);
            }
            prefs.edit().putString(HISTORY_KEY, out.toString()).apply();
            loadHistory();
        } catch (Exception ignored) {
        }
    }

    private static void putHistoryTip(org.json.JSONObject entry, String prefix,
                                      ChelaileDataSource.GaodeTip tip) throws Exception {
        if (tip == null || MY_LOCATION.equals(tip.name) || tip.lat == 0 || tip.lon == 0) return;
        entry.put(prefix + "Lat", tip.lat);
        entry.put(prefix + "Lon", tip.lon);
        entry.put(prefix + "City", tip.city == null ? "" : tip.city);
        entry.put(prefix + "Sub", tip.sub == null ? "" : tip.sub);
        entry.put(prefix + "Type", tip.typePriority);
    }

    private static String historyIdentity(Object raw) {
        if (raw instanceof org.json.JSONObject) {
            org.json.JSONObject object = (org.json.JSONObject) raw;
            return object.optString("origin", "") + " → " + object.optString("dest", "");
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private void loadHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString(HISTORY_KEY, "[]"));
            if (arr.length() == 0) {
                historyBox.setVisibility(View.GONE);
                return;
            }
            historyBox.setVisibility(View.VISIBLE);

            LinearLayout historyHeader = new LinearLayout(this);
            historyHeader.setGravity(Gravity.CENTER_VERTICAL);
            historyHeader.setPadding(dp(2), dp(3), dp(2), dp(7));
            TextView label = new TextView(this);
            label.setText("最近路线");
            label.setTextSize(13);
            label.setTextColor(TEXT);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            historyHeader.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView clear = new TextView(this);
            clear.setText("清除");
            clear.setTextSize(12);
            clear.setTextColor(MUTED);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(10), dp(5), dp(10), dp(5));
            clear.setBackground(rounded(Ui.RAISED, 12));
            clear.setClickable(true);
            clear.setOnClickListener(v -> {
                getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
                        .edit().remove(HISTORY_KEY).apply();
                loadHistory();
            });
            clear.setOnTouchListener(pressFeedback());
            historyHeader.addView(clear);
            historyBox.addView(historyHeader, matchWrap());

            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < arr.length(); i++) {
                Object rawEntry = arr.opt(i);
                final org.json.JSONObject saved = rawEntry instanceof org.json.JSONObject
                        ? (org.json.JSONObject) rawEntry : null;
                final String originName = saved == null ? ""
                        : saved.optString("origin", "");
                final String destinationName = saved == null ? ""
                        : saved.optString("dest", "");
                final String legacy = saved == null && rawEntry != null
                        ? String.valueOf(rawEntry) : "";
                final String[] parts = saved == null
                        ? legacy.split(" → ", 2)
                        : new String[]{originName, destinationName};
                if (parts.length != 2) continue;
                final ChelaileDataSource.GaodeTip savedOrigin = historyTip(saved, "origin", parts[0]);
                final ChelaileDataSource.GaodeTip savedDest = historyTip(saved, "dest", parts[1]);
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.VERTICAL);
                chip.setPadding(dp(12), dp(12), dp(12), dp(12));
                chip.setBackground(rounded(Ui.SURFACE, 14));
                chip.setElevation(dp(1));
                TextView from = new TextView(this);
                from.setText("●  " + parts[0]);
                from.setTextSize(13);
                from.setTextColor(PRIMARY_DARK);
                from.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                from.setSingleLine(true);
                from.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.addView(from, matchWrap());
                TextView to = new TextView(this);
                to.setText("↳  " + parts[1]);
                to.setTextSize(12);
                to.setTextColor(MUTED);
                to.setSingleLine(true);
                to.setEllipsize(android.text.TextUtils.TruncateAt.END);
                to.setPadding(0, dp(4), 0, 0);
                chip.addView(to, matchWrap());
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> {
                    if (parts.length == 2) {
                        suppressSuggestion = true;
                        originInput.setText(parts[0]);
                        destInput.setText(parts[1]);
                        selectedOriginTip = null;
                        selectedDestTip = null;
                        suppressSuggestion = false;
                        dismissSuggest();
                        if ((savedOrigin != null || MY_LOCATION.equals(parts[0]))
                                && (savedDest != null || MY_LOCATION.equals(parts[1]))) {
                            selectedOriginTip = savedOrigin;
                            selectedDestTip = savedDest;
                            query();
                        } else {
                            resolveHistoryAndQuery(parts[0], parts[1]);
                        }
                    }
                });
                chip.setOnTouchListener(pressFeedback());
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        dp(184), ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(8);
                clp.bottomMargin = dp(2);
                chips.addView(chip, clp);
            }
            scroll.addView(chips);
            historyBox.addView(scroll, matchWrap());
        } catch (Exception ignored) {
        }
    }

    private static ChelaileDataSource.GaodeTip historyTip(org.json.JSONObject saved,
                                                           String prefix, String name) {
        if (saved == null || MY_LOCATION.equals(name)) return null;
        double lat = saved.optDouble(prefix + "Lat", 0);
        double lon = saved.optDouble(prefix + "Lon", 0);
        if (lat == 0 || lon == 0) return null;
        return new ChelaileDataSource.GaodeTip(name,
                saved.optString(prefix + "Sub", "历史路线"), lat, lon,
                saved.optString(prefix + "City", ""),
                saved.optInt(prefix + "Type", 2));
    }

    /**
     * 旧历史记录只保存起终点文字。点击时主动走一遍与联想框相同的广东省内
     * 候选排序并恢复坐标，避免必须删一个字再手动点候选才能正确识别。
     */
    private void resolveHistoryAndQuery(final String origin, final String destination) {
        final int sequence = planSequence;
        progress.setVisibility(View.VISIBLE);
        status.setText("正在识别历史路线地点…");
        suggestWorker.execute(() -> {
            ChelaileDataSource.GaodeTip originResolved = resolveHistoryTip(origin);
            ChelaileDataSource.GaodeTip destinationResolved = resolveHistoryTip(destination);
            postUi(() -> {
                if (sequence != planSequence) return;
                if (isFinishing()
                        || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                selectedOriginTip = originResolved;
                selectedDestTip = destinationResolved;
                query();
            });
        });
    }

    private ChelaileDataSource.GaodeTip resolveHistoryTip(String keyword) {
        if (MY_LOCATION.equals(keyword)) return null;
        java.util.List<ChelaileDataSource.GaodeTip> tips = new java.util.ArrayList<>();
        if (metroCache == null) {
            try {
                metroCache = new MetroDataSource(this).queryLines();
            } catch (Exception ignored) {
            }
        }
        appendLocalMetroSuggestions(keyword, tips, metroCache);
        try {
            tips.addAll(ChelaileDataSource.gaodeInputTips(keyword, cityName));
        } catch (Exception ignored) {
        }
        java.util.List<ChelaileDataSource.GaodeTip> ranked = rankSuggestions(keyword, tips);
        if (ranked.isEmpty()) return null;
        String target = normalizeSuggestName(keyword);
        // 历史记录没有坐标时，只恢复精确同名地点。
        for (ChelaileDataSource.GaodeTip tip : ranked) {
            if (target.equals(normalizeSuggestName(tip.name))
                    && cityName.equals(tip.city)) return tip;
        }
        for (ChelaileDataSource.GaodeTip tip : ranked) {
            if (target.equals(normalizeSuggestName(tip.name))) return tip;
        }
        return null;
    }

    private static boolean hasCoordinate(ChelaileDataSource.GaodeTip tip) {
        return tip != null && TransitRules.validCoordinate(tip.lat, tip.lon);
    }

    /** 未点联想时只接受内置地铁中的精确站名，不能用模糊包含关系冒充坐标。 */
    private ChelaileDataSource.GaodeTip matchInternalMetroStation(String input) {
        return matchInternalMetroStation(input, cityName);
    }

    private ChelaileDataSource.GaodeTip matchInternalMetroStation(String input, String preferredCity) {
        if (metroCache == null || input == null) return null;
        String target = normalizeSuggestName(TransitRules.keyword(input));
        if (target.isEmpty()) return null;
        ChelaileDataSource.GaodeTip fallback = null;
        for (MetroDataSource.MetroLine line : metroCache) {
            if (!sameMetroProvince(line)) continue;
            for (MetroDataSource.Station station : line.stations) {
                if (!target.equals(normalizeSuggestName(station.name))) continue;
                if (station.lat == 0 || station.lon == 0) continue;
                String metroCity = metroCityForStation(line, station.lat, station.lon);
                ChelaileDataSource.GaodeTip tip = new ChelaileDataSource.GaodeTip(
                        station.name, "内置地铁 · " + line.name,
                        station.lat, station.lon, metroCity, 0);
                if (preferredCity != null && preferredCity.replace("市", "").equals(metroCity)) return tip;
                if (fallback == null) fallback = tip;
            }
        }
        return fallback;
    }

    /**
     * 同名地铁候选最终提交前吸附到内置站点坐标。联想服务偶尔会返回线路中心、
     * 出入口或同名 POI 坐标；线路和站序仍使用内置数据，仅修正规划入口坐标。
     */
    private ChelaileDataSource.GaodeTip snapExactMetroStation(
            String input, ChelaileDataSource.GaodeTip selected) {
        CityInfo explicitCity = TransitRules.explicitCity(input);
        String preferred = selected != null && selected.city != null && !selected.city.isEmpty()
                ? selected.city : explicitCity == null ? cityName : explicitCity.name;
        ChelaileDataSource.GaodeTip internal = matchInternalMetroStation(input, preferred);
        if (hasCoordinate(selected) && internal != null && !preferred.replace("市", "").equals(internal.city)) {
            return selected;
        }
        if (!hasCoordinate(internal)) return selected;
        if (!hasCoordinate(selected)) return internal;
        boolean selectedIsMetro = TransitRules.useInternalMetroCoordinate(selected.typePriority);
        if (!selectedIsMetro) return selected;
        if (!normalizeSuggestName(input).equals(normalizeSuggestName(selected.name))) {
            return selected;
        }
        return new ChelaileDataSource.GaodeTip(selected.name,
                selected.sub, internal.lat, internal.lon, internal.city, 0);
    }

    private void showEndpointValidation(boolean originMissing, boolean destinationMissing) {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        progress.setVisibility(View.GONE);
        setEndpointError(originErrorMark, originMissing);
        setEndpointError(destErrorMark, destinationMissing);
        String missing = originMissing && destinationMissing ? "起点和终点"
                : originMissing ? "起点" : "终点";
        status.setText("× " + missing + "未取得精确坐标");
        listRoot.removeAllViews();
        listRoot.addView(emptyCard(missing + "无法对应到内置地铁站坐标\n"
                + "请重新输入，并从联想候选中选择地点"), cardParams());
        EditText target = originMissing ? originInput : destInput;
        target.requestFocus();
        target.setSelection(target.length());
    }

    private void query() {
        final String origin = originInput.getText().toString().trim();
        final String dest = destInput.getText().toString().trim();
        if (origin.isEmpty() || dest.isEmpty()) {
            Toast.makeText(this, "请填写起点和终点站名", Toast.LENGTH_SHORT).show();
            return;
        }
        invalidatePlan();
        final int sequence = planSequence;
        final int preference = routePreference;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(originInput.getWindowToken(), 0);
        originInput.clearFocus();
        destInput.clearFocus();
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在规划 " + origin + " → " + dest + " …");
        listRoot.removeAllViews();
        dismissSuggest();
        setEndpointError(originErrorMark, false);
        setEndpointError(destErrorMark, false);
        if (MY_LOCATION.equals(origin) || MY_LOCATION.equals(dest)) {
            status.setText("正在获取当前位置…");
            fetchFreshLocation(location -> {
                if (sequence != planSequence || isFinishing() || isDestroyed()) return;
                if (location == null) {
                    progress.setVisibility(View.GONE);
                    status.setText("未取得当前位置，请开启定位后重试");
                    setEndpointError(originErrorMark, MY_LOCATION.equals(origin));
                    setEndpointError(destErrorMark, MY_LOCATION.equals(dest));
                    return;
                }
                currentLat = location.getLatitude();
                currentLon = location.getLongitude();
                hasLocation = true;
                submitPlan(origin, dest, sequence, preference, true);
            });
        } else submitPlan(origin, dest, sequence, preference, false);
    }

    private void submitPlan(String origin, String dest, int sequence, int preference, boolean useLocation) {
        final double lat = currentLat, lon = currentLon;
        final ChelaileDataSource.GaodeTip selectedOrigin = selectedOriginTip != null
                && origin.equals(selectedOriginTip.name) ? selectedOriginTip : null;
        final ChelaileDataSource.GaodeTip selectedDestination = selectedDestTip != null
                && dest.equals(selectedDestTip.name) ? selectedDestTip : null;
        pendingPlan = worker.submit(() -> {
            try {
                try { metroCache = new MetroDataSource(this).queryLines(); }
                catch (Exception ignored) { }
                ChelaileDataSource.GaodeTip fresh = useLocation ? locationTip(lat, lon) : null;
                final ChelaileDataSource.GaodeTip originTip = MY_LOCATION.equals(origin) ? fresh
                        : snapExactMetroStation(origin, selectedOrigin);
                final ChelaileDataSource.GaodeTip destTip = MY_LOCATION.equals(dest) ? fresh
                        : snapExactMetroStation(dest, selectedDestination);
                if (originTip == null || destTip == null) {
                    postUi(() -> {
                        if (sequence == planSequence) showEndpointValidation(originTip == null, destTip == null);
                    });
                    return;
                }
                final List<ChelaileDataSource.TransitPlan> plans =
                        ChelaileDataSource.transitQuery(cityName,
                                origin, originTip.lat, originTip.lon,
                                dest, destTip.lat, destTip.lon, originTip.city, destTip.city, preference);
                postUi(() -> {
                    if (sequence != planSequence) return;
                    if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    progress.setVisibility(View.GONE);
                    if (plans.isEmpty()) {
                        status.setText("未找到出行方案");
                        listRoot.addView(emptyCard("没有找到 " + origin + " → " + dest + " 的出行方案\n请确认站名是否正确"), cardParams());
                        return;
                    }
                    selectedOriginTip = originTip;
                    selectedDestTip = destTip;
                    status.setText(TransitExperience.PREFERENCES[preference] + " · 找到 " + plans.size()
                            + " 个方案 · 路线耗时为规划估算");
                    saveHistory(origin, dest, originTip, destTip);
                    for (int i = 0; i < plans.size(); i++) {
                        View card = planCard(plans.get(i), i == 0, origin, dest);
                        listRoot.addView(card, cardParams());
                        animateIn(card);
                    }
                    if (pageScroll != null && resultsStatusRow != null) pageScroll.post(() ->
                            pageScroll.smoothScrollTo(0, Math.max(0, resultsStatusRow.getTop() - dp(8))));
                });
            } catch (Exception error) {
                postUi(() -> {
                    if (sequence != planSequence) return;
                    if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    progress.setVisibility(View.GONE);
                    status.setText("查询失败");
                    listRoot.addView(emptyCard("查询失败\n" + error.getMessage() + "\n\n请检查网络或稍后重试"), cardParams());
                });
            }
        });
    }

    private ChelaileDataSource.GaodeTip locationTip(double lat, double lon) {
        CityInfo nearby = CityInfo.nearest(lat, lon);
        if (nearby == null || !nearby.name.equals(cityName)) {
            try { nearby = CityLocator.locate(this, lat, lon); } catch (Exception ignored) { }
        }
        double[] gcj = Coord.wgsToGcj(lat, lon);
        return new ChelaileDataSource.GaodeTip(MY_LOCATION, "", gcj[0], gcj[1],
                nearby == null ? cityName : nearby.name, 2);
    }

    private interface LocationResult { void accept(android.location.Location location); }

    private void fetchFreshLocation(LocationResult result) {
        if (!PrivacyNotice.locationAllowed()) { result.accept(null); return; }
        gps = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (gps == null) { result.accept(null); return; }
        String[] providers = {android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER};
        android.location.Location best = null;
        for (String provider : providers) {
            try {
                android.location.Location fix = gps.getLastKnownLocation(provider);
                if (freshEnough(fix) && (best == null || fix.getAccuracy() < best.getAccuracy())) best = fix;
            } catch (RuntimeException ignored) { }
        }
        if (best != null) { result.accept(best); return; }
        freshListener = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location fix) {
                if (freshListener != this || !freshEnough(fix)) return;
                stopFreshLocation();
                result.accept(fix);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        boolean started = false;
        for (String provider : providers) {
            try {
                if (!gps.isProviderEnabled(provider)) continue;
                gps.requestLocationUpdates(provider, 500L, 0, freshListener, getMainLooper());
                started = true;
            } catch (RuntimeException ignored) { }
        }
        freshTimeout = () -> { stopFreshLocation(); result.accept(null); };
        if (started) suggestHandler.postDelayed(freshTimeout, 9_000L);
        else freshTimeout.run();
    }

    private static boolean freshEnough(android.location.Location fix) {
        if (fix == null || !fix.hasAccuracy() || fix.getAccuracy() > 500) return false;
        long age = System.currentTimeMillis() - fix.getTime();
        return age >= 0 && age <= 20_000L && TransitRules.validCoordinate(fix.getLatitude(), fix.getLongitude());
    }

    private void stopFreshLocation() {
        if (freshTimeout != null) suggestHandler.removeCallbacks(freshTimeout);
        if (gps != null && freshListener != null) {
            try { gps.removeUpdates(freshListener); } catch (RuntimeException ignored) { }
        }
        freshTimeout = null;
        freshListener = null;
    }

    /** 方案卡：推荐标记 + 摘要，点击展开每步该坐什么车、在哪换乘。 */
    private View planCard(ChelaileDataSource.TransitPlan plan, boolean recommend,
                          final String plannedOrigin, final String plannedDestination) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(recommend ? LIGHT_BLUE : Ui.SURFACE, 16));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setLayoutParams(cardParams());

        // 头行：时间大字 + 票价 + 推荐标记 + 展开箭头
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = new TextView(this);
        time.setText(plan.durationMin + "分钟");
        time.setTextSize(21);
        time.setTextColor(ACCENT);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Ui.type(time, 24, true);
        head.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        TextView meta = new TextView(this);
        int rideCount = 0;
        for (ChelaileDataSource.TransitSegment seg : plan.segments) {
            if (!seg.type.equals("walk")) rideCount++;
        }
        String metaText = "票价" + plan.cost + "元 · 步行" + plan.walkingDistance + "米"
                + " · " + (rideCount - 1 <= 0 ? "直达" : (rideCount - 1) + "次换乘");
        meta.setText(metaText);
        Ui.type(meta, Ui.META, false);
        meta.setTextColor(MUTED);
        meta.setPadding(0, dp(8), 0, dp(8));
        meta.setMaxLines(2);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (recommend) {
            TextView badge = new TextView(this);
            badge.setText("推荐");
            badge.setTextSize(12);
            badge.setTextColor(Color.WHITE);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(rounded(PRIMARY, 10));
            head.addView(badge);
        }
        final TextView expandMark = new TextView(this);
        expandMark.setText("›");
        expandMark.setTextSize(18);
        expandMark.setTextColor(MUTED);
        expandMark.setPadding(dp(8), 0, 0, 0);
        head.addView(expandMark);
        card.addView(head);
        card.addView(meta, matchWrap());

        // 摘要：高德原版式——每段一行（色块徽标 + 线路名 + 站数分钟 + 起终点）
        for (ChelaileDataSource.TransitSegment seg : plan.segments) {
            if (seg.type.equals("walk")) continue;
            card.addView(segmentLine(seg), matchWrap());
        }
        if (plan.segments.size() == 1 && plan.segments.get(0).type.equals("walk")) {
            TextView walk = new TextView(this);
            walk.setText("🚶 全程步行 " + plan.walkingDistance + " 米");
            walk.setTextSize(13);
            walk.setTextColor(MUTED);
            walk.setPadding(0, dp(6), 0, 0);
            card.addView(walk);
        }

        card.setOnClickListener(v -> {
                        Intent intent = new Intent(this, TransitDetailActivity.class);
                        intent.putExtra("plan", plan);
                        intent.putExtra("origin", plannedOrigin);
                        intent.putExtra("destination", plannedDestination);
                        intent.putExtra("cityId", cityId);
                        intent.putExtra("cityName", cityName);
                        intent.putExtra("currentLat", currentLat);
                        intent.putExtra("currentLon", currentLon);
                        intent.putExtra("hasLocation", hasLocation);
                        java.util.ArrayList<String> resolvedNames = new java.util.ArrayList<>();
                        int[] resolvedColours = new int[plan.segments.size()];
                        int[] resolvedDirections = new int[plan.segments.size()];
                        for (int s = 0; s < plan.segments.size(); s++) {
                            ChelaileDataSource.TransitSegment segment = plan.segments.get(s);
                            resolvedNames.add(resolvedSegmentName(segment));
                            resolvedColours[s] = metroColour(segment);
                            resolvedDirections[s] = -1;
                            if ("metro".equals(segment.type) && metroCache != null) {
                                MetroDataSource.MetroLine local = MetroLineResolver.best(
                                        metroCache, segment.name, segment.from, segment.to, cityName);
                                if (local != null) {
                                    resolvedDirections[s] = resolveDirection(
                                            local, segment.from, segment.to, segment.name);
                                }
                            }
                        }
                        intent.putStringArrayListExtra("resolvedNames", resolvedNames);
                        intent.putExtra("resolvedColours", resolvedColours);
                        intent.putExtra("resolvedDirections", resolvedDirections);
                        startActivity(intent);
                        Ui.transitionForward(this);
        });
        Ui.pressFeedback(card);
        return card;
    }

    /** 乘车段一行：线路色块徽标 + 线路名（站数分钟）+ 起终点（高德原版式）。 */
    private View segmentLine(ChelaileDataSource.TransitSegment seg) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(2));
        // 线路色块徽标（地铁用官方色，公交用蓝色）
        TextView badge = new TextView(this);
        String shortName = resolvedSegmentName(seg);
        badge.setText(shortName);
        badge.setTextSize(12);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setSingleLine(true);
        badge.setMaxWidth(dp(112));
        badge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        Ui.routeBadge(badge, metroColour(seg),
                "metro".equals(seg.type) && TransitSegmentNavigator.railRegion(seg).isEmpty(), 8);
        row.addView(badge);
        TextView text = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (seg.stops > 0 || seg.durationMin > 0) {
            sb.append("（");
            if (seg.stops > 0) sb.append(seg.stops).append("站");
            if (seg.stops > 0 && seg.durationMin > 0) sb.append("·");
            if (seg.durationMin > 0) sb.append(seg.durationMin).append("分钟");
            sb.append("） ");
        }
        sb.append(seg.from).append(" → ").append(seg.to);
        text.setText(sb.toString());
        text.setTextSize(13);
        text.setTextColor(TEXT);
        text.setPadding(dp(8), 0, 0, 0);
        text.setMaxLines(2);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 路线卡直接显示内部线路标准名，避免高德别名与线路页标题看起来不一致。 */
    private String resolvedSegmentName(ChelaileDataSource.TransitSegment seg) {
        if (seg.type.equals("metro") && TransitSegmentNavigator.railRegion(seg).isEmpty() && metroCache != null) {
            MetroDataSource.MetroLine line = MetroLineResolver.best(
                    metroCache, seg.name, seg.from, seg.to, cityName);
            if (line != null) return line.name;
        }
        return MetroLineResolver.displayName(seg.name);
    }

    /** 线路色块颜色：地铁用线路官方色（本地数据），公交用蓝色。 */
    private int metroColour(ChelaileDataSource.TransitSegment seg) {
        if (seg.type.equals("metro") && TransitSegmentNavigator.railRegion(seg).isEmpty() && metroCache != null) {
            MetroDataSource.MetroLine line = MetroLineResolver.best(
                    metroCache, seg.name, seg.from, seg.to, cityName);
            if (line != null) {
                String c = line.colour;
                if (c != null && c.matches("#?[0-9A-Fa-f]{6}")) {
                    c = c.replace("#", "");
                    return Color.rgb(Integer.parseInt(c.substring(0, 2), 16),
                            Integer.parseInt(c.substring(2, 4), 16),
                            Integer.parseInt(c.substring(4, 6), 16));
                }
            }
        }
        return Color.rgb(82, 138, 196); // 公交默认蓝
    }

    /** 折叠摘要：第一步坐什么 + 终点。 */
    private String summaryText(ChelaileDataSource.TransitPlan plan) {
        String firstRide = "";
        String lastRide = "";
        for (ChelaileDataSource.TransitSegment seg : plan.segments) {
            if (seg.type.equals("walk")) continue;
            if (firstRide.isEmpty()) {
                firstRide = (seg.type.equals("metro") ? "🚇 " : "🚌 ") + seg.name
                        + "（" + seg.from + " → " + seg.to + "）";
            }
            lastRide = (seg.type.equals("metro") ? "🚇 " : "🚌 ") + seg.name
                    + "（" + seg.from + " → " + seg.to + "）";
        }
        if (firstRide.isEmpty()) return "全程步行 " + plan.walkingDistance + " 米";
        return "先乘 " + firstRide + (lastRide.equals(firstRide) ? "" : "，最后 " + lastRide)
                + " · 点击卡片查看每步方案";
    }

    /** 详细步骤：步行/乘车/换乘逐条说明。 */
    private void addSteps(LinearLayout box, ChelaileDataSource.TransitPlan plan) {
        List<ChelaileDataSource.TransitSegment> segs = plan.segments;
        int step = 0;
        for (int i = 0; i < segs.size(); i++) {
            ChelaileDataSource.TransitSegment seg = segs.get(i);
            if (seg.type.equals("walk")) {
                // 步行到下一段上车站
                String toWhere = "";
                if (i + 1 < segs.size()) {
                    ChelaileDataSource.TransitSegment next = segs.get(i + 1);
                    if (!next.type.equals("walk") && !next.from.isEmpty()) {
                        toWhere = "，走到 " + next.from;
                    }
                }
                addStep(box, ++step, "🚶 步行 " + seg.distance + " 米" + toWhere, MUTED, false);
            } else {
                boolean metro = seg.type.equals("metro");
                String icon = metro ? "🚇" : "🚌";
                StringBuilder sb = new StringBuilder();
                String displayLine = resolvedSegmentName(seg);
                sb.append(icon).append(" 乘坐 ").append(displayLine.isEmpty() ? "公交" : displayLine);
                // 站数 + 分钟（坐几站 · 几分钟）
                if (seg.stops > 0 || seg.durationMin > 0) {
                    sb.append("（");
                    if (seg.stops > 0) sb.append("坐 ").append(seg.stops).append(" 站");
                    if (seg.stops > 0 && seg.durationMin > 0) sb.append(" · ");
                    if (seg.durationMin > 0) sb.append("约 ").append(seg.durationMin).append(" 分钟");
                    sb.append("）");
                }
                sb.append("：").append(seg.from).append(" → ").append(seg.to);
                // 换乘说明
                if (i + 1 < segs.size()) {
                    ChelaileDataSource.TransitSegment next = segs.get(i + 1);
                    if (!next.type.equals("walk")) {
                        sb.append("\n    在 ").append(seg.to).append(" 换乘 ")
                                .append(next.name.isEmpty() ? "公交" : next.name);
                    } else if (i + 2 < segs.size()) {
                        ChelaileDataSource.TransitSegment after = segs.get(i + 2);
                        sb.append("\n    在 ").append(seg.to).append(" 下车，步行 ")
                                .append(next.distance).append(" 米到 ")
                                .append(after.from.isEmpty() ? "下一站" : after.from)
                                .append(" 换乘 ").append(after.name.isEmpty() ? "公交" : after.name);
                    } else {
                        sb.append("\n    在 ").append(seg.to).append(" 下车到达");
                    }
                } else {
                    sb.append("\n    在 ").append(seg.to).append(" 下车到达");
                }
                final TextView stepView = addStep(box, ++step, sb.toString(),
                        metro ? Color.rgb(84, 138, 196) : LINE_COLOR, true);
                final String stepLineName = seg.name;
                stepView.setClickable(true);
                stepView.setFocusable(true);
                stepView.setOnClickListener(v -> {
                    if (stepLineName == null || stepLineName.isEmpty()) return;
                    TransitSegmentNavigator.open(this, worker, seg, cityId, cityName, currentLat, currentLon);
                });
            }
        }
    }

    private TextView addStep(LinearLayout box, int index, String text, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(index + ". " + text);
        tv.setTextSize(13);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(0, dp(4), 0, 0);
        box.addView(tv);
        return tv;
    }

    /** 点击地铁段 → 打开对应地铁线路页：按名称/起终点/方向名自动判定城市、线路与运行方向。 */
    private void openMetroSegment(final String segmentName,
                                  final String fromStop, final String toStop) {
        worker.execute(() -> {
            try {
                List<MetroDataSource.MetroLine> all = new MetroDataSource(this).queryLines();
                // 从段名提取方向提示；线路本体由城市、编号/别名和上下车站综合打分。
                String dirHint = null;
                java.util.regex.Matcher paren = java.util.regex.Pattern
                        .compile("[（(]([^）)]*)[）)]").matcher(segmentName);
                if (paren.find()) {
                    String inner = paren.group(1).trim();
                    if (inner.contains("内环")) dirHint = "内环";
                    else if (inner.contains("外环")) dirHint = "外环";
                    else if (inner.contains("方向")) {
                        dirHint = inner.replace("方向", "").replace("往", "").trim();
                    }
                }
                java.util.List<MetroLineResolver.Match> ranked = MetroLineResolver.rank(
                        all, segmentName, fromStop, toStop, cityName);
                java.util.List<MetroDataSource.MetroLine> hits = new java.util.ArrayList<>();
                for (MetroLineResolver.Match match : ranked) hits.add(match.line);
                final java.util.List<MetroDataSource.MetroLine> snapshot = hits;
                final String dirHintFinal = dirHint;
                postUi(() -> {
                    if (snapshot.isEmpty()) {
                        Toast.makeText(this, "未找到线路「" + segmentName + "」", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.util.List<MetroDataSource.MetroLine> pick = snapshot;
                    if (pick.size() == 1) {
                        openMetroLine(pick.get(0), fromStop, toStop, dirHintFinal);
                        return;
                    }
                    String[] names = new String[pick.size()];
                    for (int i = 0; i < pick.size(); i++) {
                        names[i] = describeLine(pick.get(i), fromStop, toStop, dirHintFinal);
                    }
                    new TransitDialog.Builder(this)
                            .setTitle("选择线路")
                            .setItems(names, (d, w) ->
                                    openMetroLine(pick.get(w), fromStop, toStop, dirHintFinal))
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                postUi(() -> Toast.makeText(this, "线路数据加载失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 站名归一化：去"站"字、兼容"西朗/西塱"这类写法差异。 */
    private static String normStop(String s) {
        if (s == null) return "";
        return s.trim().replace("站", "").replace("朗", "塱");
    }

    private static int indexOfStation(MetroDataSource.MetroLine line, String stopName) {
        return MetroLineResolver.stationIndex(line, stopName);
    }

    /** 候选线路描述：城市/线名 + 起终点 + 方向，避免只显示"1号线"无法区分。 */
    private static String describeLine(MetroDataSource.MetroLine line,
                                       String fromStop, String toStop, String dirHint) {
        StringBuilder sb = new StringBuilder(line.name);
        if (line.stations.size() >= 2) {
            sb.append("（").append(line.stations.get(0).name).append("→")
                    .append(line.stations.get(line.stations.size() - 1).name).append("）");
        }
        if (line.ring) {
            sb.append(" · ").append("内环".equals(dirHint) ? "内环"
                    : "外环".equals(dirHint) ? "外环" : "环线");
        } else if (!fromStop.isEmpty() || !toStop.isEmpty()) {
            sb.append(" · ").append(fromStop.isEmpty() ? "?" : fromStop)
                    .append("→").append(toStop.isEmpty() ? "?" : toStop);
        }
        return sb.toString();
    }

    /** 按换乘段方向自动判定列车运行方向：普通线按起终点在站点顺序中的前后；
     *  环线按官方口径（11号线数据按外环/逆时针排列，内环为反向）。 */
    private static int resolveDirection(MetroDataSource.MetroLine line,
                                        String fromStop, String toStop, String dirHint) {
        if (line.ring) {
            if ("内环".equals(dirHint)) return 1;
            if ("外环".equals(dirHint)) return 0;
        }
        int fi = indexOfStation(line, fromStop);
        int ti = indexOfStation(line, toStop);
        if (fi >= 0 && ti >= 0 && fi != ti) return fi < ti ? 0 : 1;
        return 0;
    }

    private void openMetroLine(MetroDataSource.MetroLine line,
                               String fromStop, String toStop, String dirHint) {
        Intent intent = new Intent(this, MetroLineActivity.class);
        intent.putExtra("lineName", line.name);
        intent.putExtra("colour", MetroDataSource.normalizeColour(line.colour));
        intent.putExtra("direction", resolveDirection(line, fromStop, toStop, dirHint));
        startActivity(intent);
        Ui.transitionForward(this);
    }

    private void animateIn(View v) {
        Ui.animateIn(v, this);
    }

    private View emptyCard(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(MUTED);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(20), dp(28), dp(20), dp(28));
        tv.setBackground(rounded(Ui.SURFACE, 16));
        tv.setLayoutParams(cardParams());
        return tv;
    }

    private View.OnTouchListener pressFeedback() {
        return (v, event) -> {
            if (Ui.motionScale(this) == 0) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.98f).scaleY(.98f).setDuration(Ui.duration(this, 100))
                        .setInterpolator(Ui.easeOut()).start();
            else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1).scaleY(1).setDuration(Ui.duration(this, 150))
                        .setInterpolator(Ui.easeOut()).start();
            return false;
        };
    }

    private Drawable rounded(int color, int radius) {
        if (color == Color.WHITE || color == 0xFFFFFFFF) {
            return Ui.edgeCard(this, Ui.VIOLET, radius);
        }
        return Ui.rounded(this, color, radius);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    @Override
    protected void onDestroy() {
        stopFreshLocation();
        suggestHandler.removeCallbacksAndMessages(null);
        dismissSuggest();
        suggestWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            action.run();
        });
    }
}
