package com.holi.prdbus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One fixed provider per credential state, with provider-specific coordinates and caches. */
final class MapView extends View {
    interface OnStationTapListener {
        void onStationTap(NearbyStation station);
    }

    interface OnMapGestureListener {
        void onMapGesture();
    }

    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 10;
    // 高德当前瓦片源在部分珠三角区域的 z19 返回 HTTP 200 空白图；z18 稳定。
    // 限制到真实可用层级，避免“最大缩放全白、移动才零散出现瓦片”。
    private static final int MAX_ZOOM = 18;
    private static final int MARKER_TOUCH_RADIUS = 60;
    private static final long MAX_DISK_CACHE_BYTES = 44L * 1024L * 1024L;
    private static final long TRIMMED_DISK_CACHE_BYTES = 40L * 1024L * 1024L;
    private static final Object DISK_CACHE_LOCK = new Object();
    private static long knownDiskCacheBytes = -1L;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rectPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // 站点标记复用画笔（避免每次绘制 new Paint 造成卡顿）
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final android.animation.ValueAnimator pulseAnimator;
    private long lastPulseFrameAt;

    private double centerLat = 23.129;
    private double centerLon = 113.264;
    private int zoom = 16;

    private final Paint selectedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String selectedStationName;

    private final Map<String, Bitmap> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 128;
        }
    });
    private final Set<String> pendingTiles = new HashSet<>();
    private final Map<String,Long> failedTiles=Collections.synchronizedMap(new LinkedHashMap<String,Long>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> entry) {return size()>256;}
    });
    private final Map<String, String> tileSources = Collections.synchronizedMap(
            new LinkedHashMap<String, String>());
    private ExecutorService tileExecutor = Executors.newFixedThreadPool(4);
    private final File tileDir;
    private String queuedViewport = "";
    /** 当前整张地图只使用一个瓦片源，避免 GCJ 高德瓦片与 WGS OSM 瓦片混绘。 */
    private boolean mapKeyBound = AppSettings.hasMapKey();
    private volatile String activeProvider = mapKeyBound ? "amap" : "osm";

    void updateMapCredential() {
        boolean bound = AppSettings.hasMapKey();
        if (bound == mapKeyBound) return;
        mapKeyBound = bound;
        activeProvider = bound ? "amap" : "osm";
        memoryCache.clear();
        failedTiles.clear();
        tileSources.clear();
        queuedViewport = "";
        invalidate();
    }

    private List<NearbyStation> stations = new ArrayList<>();
    private boolean hasUserLocation;
    private double userLat;
    private double userLon;
    private float userPulse;
    private OnStationTapListener stationTapListener;
    private OnMapGestureListener mapGestureListener;

    // 线路绘制（站点连线 + 车辆位置）
    private List<double[]> routePoints = new ArrayList<>();       // 站点坐标 [lat, lon]
    private List<double[]> routeStationPoints = new ArrayList<>();// 仅真实站台圆点
    private List<double[]> vehiclePoints = new ArrayList<>();     // 车辆坐标 [lat, lon]
    private List<String> vehicleLabels = new ArrayList<>();       // 车辆标签（车牌）
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vehiclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vehicleLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int routeColor = Color.rgb(126, 176, 219);
    private String routeSignature = "";

    private float lastX;
    private float lastY;
    private boolean moved;
    private final ScaleGestureDetector scaleDetector;
    /** 双指缩放的累积倍数：跨事件累加，避免每次手势微动都跳一级缩放。 */
    private float pinchAccum = 1f;

    MapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        // v3 使用国内可直连的 GCJ-02 瓦片，与旧 OSM/WGS 缓存隔离。
        tileDir = new File(context.getCacheDir(), "map_tiles_v4");
        tileDir.mkdirs();
        // 启动即处理旧版本遗留缓存，不必等到下一张瓦片写入才清理。
        tileExecutor.execute(this::trimDiskCacheIfNeeded);
        rectPaint.setStyle(Paint.Style.FILL);
        rectPaint.setColor(Color.rgb(230, 234, 238));
        textPaint.setColor(Color.rgb(33, 33, 33));
        textPaint.setTextSize(12 * density);
        userPaint.setColor(Color.rgb(46, 125, 50));
        // 定位点脉冲光环
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1400);
        pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(a -> {
            userPulse = (Float) a.getAnimatedValue();
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPulseFrameAt >= 48L) {
                lastPulseFrameAt = now;
                postInvalidateOnAnimation();
            }
        });
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinchAccum = 1f;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                pinchAccum *= detector.getScaleFactor();
                // 累积放大约 18% 或缩小约 15% 才跳一级，降低灵敏度
                if (pinchAccum >= 1.18f && zoom < MAX_ZOOM) {
                    zoomAround(detector.getFocusX(), detector.getFocusY(), zoom + 1);
                    pinchAccum = 1f;
                } else if (pinchAccum <= 0.85f && zoom > MIN_ZOOM) {
                    zoomAround(detector.getFocusX(), detector.getFocusY(), zoom - 1);
                    pinchAccum = 1f;
                }
                return true;
            }
        });
    }

    void setStationTapListener(OnStationTapListener listener) {
        this.stationTapListener = listener;
    }

    /** 选中站（地图变色高亮）。 */
    void setSelectedStation(String name) {
        this.selectedStationName = name;
        postInvalidate();
    }

    void setStations(List<NearbyStation> list) {
        this.stations = list == null ? new ArrayList<NearbyStation>() : list;
        postInvalidate();
    }

    void setMapGestureListener(OnMapGestureListener listener) {
        this.mapGestureListener = listener;
    }

    /** 按站名查找地图标记（选中站点定位用）。 */
    NearbyStation findStation(String name) {
        if (name == null) return null;
        for (NearbyStation s : stations) {
            if (name.equals(s.name)) return s;
        }
        return null;
    }

    void setUserLocation(double lat, double lon) {
        this.userLat = lat;
        this.userLon = lon;
        this.hasUserLocation = true;
        if (isAttachedToWindow() && !pulseAnimator.isStarted()) pulseAnimator.start();
        postInvalidate();
    }

    void centerOn(double lat, double lon) {
        this.centerLat = lat;
        this.centerLon = lon;
        postInvalidate();
    }

    void centerOn(double lat, double lon, int targetZoom) {
        this.zoom = clampZoom(targetZoom);
        this.centerLat = lat;
        this.centerLon = lon;
        postInvalidate();
    }

    /** 放大一级（缩放按钮用）。 */
    void zoomIn() {
        zoomAround(getWidth() / 2f, getHeight() / 2f, clampZoom(zoom + 1));
    }

    /** 缩小一级（缩放按钮用）。 */
    void zoomOut() {
        zoomAround(getWidth() / 2f, getHeight() / 2f, clampZoom(zoom - 1));
    }

    private void zoomAround(float focusX, float focusY, int newZoom) {
        double[] centerPixel = pxWgs(centerLat, centerLon);
        double focalPixelX = centerPixel[0] + (focusX - getWidth() / 2f);
        double focalPixelY = centerPixel[1] + (focusY - getHeight() / 2f);
        double[] focalGcj = GeoUtils.toLatLon(focalPixelX, focalPixelY, zoom);
        zoom = clampZoom(newZoom);
        double[] focalPixel2 = GeoUtils.toPixel(focalGcj[0], focalGcj[1], zoom);
        double[] newCenter = wgsFromPx(
                focalPixel2[0] - (focusX - getWidth() / 2f),
                focalPixel2[1] - (focusY - getHeight() / 2f));
        centerLat = newCenter[0];
        centerLon = newCenter[1];
        postInvalidate();
    }

    /**
     * 以用户位置为固定中心，选择仍能看见目标车辆的最大缩放级别。
     * 与普通 bounds fit 不同，这里不会把用户和车辆的中点当中心。
     */
    void focusUserAndTarget(double lat, double lon, double targetLat, double targetLon,
                            int paddingDp) {
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            post(() -> focusUserAndTarget(lat, lon, targetLat, targetLon, paddingDp));
            return;
        }
        centerLat = lat;
        centerLon = lon;
        if (targetLat == 0 || targetLon == 0) {
            zoom = 16;
            postInvalidate();
            return;
        }
        int padding = Math.max(0, Math.round(paddingDp * density));
        int bestZoom = MIN_ZOOM;
        for (int candidate = MAX_ZOOM; candidate >= MIN_ZOOM; candidate--) {
            double[] center = pxWgsAtZoom(lat, lon, candidate);
            double[] target = pxWgsAtZoom(targetLat, targetLon, candidate);
            double maxX = Math.max(24, width / 2.0 - padding);
            double maxY = Math.max(24, height / 2.0 - padding);
            if (Math.abs(target[0] - center[0]) <= maxX
                    && Math.abs(target[1] - center[1]) <= maxY) {
                bestZoom = candidate;
                break;
            }
        }
        zoom = clampZoom(Math.min(bestZoom, 17));
        postInvalidate();
    }

    private double[] pxWgsAtZoom(double lat, double lon, int atZoom) {
        if ("osm".equals(activeProvider)) return GeoUtils.toPixel(lat, lon, atZoom);
        double[] gcj = Coord.wgsToGcj(lat, lon);
        return GeoUtils.toPixel(gcj[0], gcj[1], atZoom);
    }

    /** 设置线路站点与车辆位置（车来了式线路地图）。自动缩放包含用户位置。 */
    void setLineRoute(List<double[]> points, List<double[]> vehicles, List<String> labels) {
        setLineRoute(points, vehicles, labels, Color.rgb(126, 176, 219));
    }

    void setLineRoute(List<double[]> points, List<double[]> vehicles, List<String> labels,
                      int color) {
        setLineRoute(points, points, vehicles, labels, color);
    }

    void setLineRoute(List<double[]> points, List<double[]> stationPoints,
                      List<double[]> vehicles, List<String> labels, int color) {
        String nextSignature = routeSignatureOf(points);
        boolean routeChanged = !nextSignature.equals(routeSignature);
        routeSignature = nextSignature;
        routePoints = points == null ? new ArrayList<double[]>() : points;
        routeStationPoints = stationPoints == null
                ? new ArrayList<double[]>() : stationPoints;
        vehiclePoints = vehicles == null ? new ArrayList<double[]>() : vehicles;
        vehicleLabels = labels == null ? new ArrayList<String>() : labels;
        routeColor = color;
        // 自动框住线路范围（含车辆与用户位置）
        if (routeChanged && !routePoints.isEmpty()) {
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (double[] p : routePoints) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            for (double[] p : vehiclePoints) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            if (hasUserLocation) {
                minLat = Math.min(minLat, userLat);
                maxLat = Math.max(maxLat, userLat);
                minLon = Math.min(minLon, userLon);
                maxLon = Math.max(maxLon, userLon);
            }
            centerLat = (minLat + maxLat) / 2;
            centerLon = (minLon + maxLon) / 2;
            double latSpan = Math.max(maxLat - minLat, 0.008);
            double lonSpan = Math.max(maxLon - minLon, 0.008);
            int z = 13;
            while (z < MAX_ZOOM && (latSpan * 111000 > getHeight() / 2.2 || lonSpan * 95000 > getWidth() / 2.2)) {
                latSpan /= 2;
                lonSpan /= 2;
                z++;
            }
            zoom = clampZoom(z);
        }
        postInvalidate();
    }

    private static String routeSignatureOf(List<double[]> points) {
        if (points == null || points.isEmpty()) return "";
        double[] first = points.get(0);
        double[] last = points.get(points.size() - 1);
        return points.size() + ":" + first[0] + ":" + first[1]
                + ":" + last[0] + ":" + last[1];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        double[] centerPixel = pxWgs(centerLat, centerLon);
        int left = (int) Math.floor((centerPixel[0] - getWidth() / 2.0) / TILE_SIZE);
        int top = (int) Math.floor((centerPixel[1] - getHeight() / 2.0) / TILE_SIZE);
        int right = (int) Math.floor((centerPixel[0] + getWidth() / 2.0) / TILE_SIZE);
        int bottom = (int) Math.floor((centerPixel[1] + getHeight() / 2.0) / TILE_SIZE);

        // 当前视野中心优先，并预取外圈一格；下载和磁盘解码都不阻塞 UI 线程。
        String viewportKey = zoom + ":" + left + ":" + top + ":" + right + ":" + bottom;
        if (!viewportKey.equals(queuedViewport)) {
            queuedViewport = viewportKey;
            requestViewport(left - 1, top - 1, right + 1, bottom + 1);
        }

        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                Bitmap tile = getTile(zoom, x, y);
                float screenX = (float) (x * TILE_SIZE - centerPixel[0] + getWidth() / 2.0);
                float screenY = (float) (y * TILE_SIZE - centerPixel[1] + getHeight() / 2.0);
                if (tile == null) {
                    canvas.drawRect(screenX, screenY, screenX + TILE_SIZE, screenY + TILE_SIZE, rectPaint);
                } else {
                    canvas.drawBitmap(tile, screenX, screenY, tilePaint);
                }
            }
        }

        for (NearbyStation station : stations) {
            if (station.name != null && station.name.equals(selectedStationName)) {
                // 选中站：橙色大圆点 + 白描边 + 橙色标签（变色高亮）
                double[] selPixel = pxWgs(centerLat, centerLon);
                float[] selScreen = toScreen(station.lat, station.lon, selPixel);
                selectedStroke.setStyle(Paint.Style.STROKE);
                selectedStroke.setStrokeWidth(4 * density);
                selectedStroke.setColor(Color.WHITE);
                selectedFill.setStyle(Paint.Style.FILL);
                selectedFill.setColor(Color.rgb(255, 109, 0));
                canvas.drawCircle(selScreen[0], selScreen[1], 13 * density, selectedStroke);
                canvas.drawCircle(selScreen[0], selScreen[1], 13 * density, selectedFill);
                markerLabel.setColor(Color.rgb(255, 109, 0));
                markerLabel.setTextSize(13 * density);
                markerLabel.setStrokeWidth(3 * density);
                markerLabel.setStyle(Paint.Style.STROKE);
                markerLabel.setColor(Color.WHITE);
                canvas.drawText((station.subway ? "地铁 " : "公交 ") + station.name,
                        selScreen[0] + 16 * density, selScreen[1] - 12 * density, markerLabel);
                markerLabel.setStyle(Paint.Style.FILL);
                markerLabel.setColor(Color.rgb(255, 109, 0));
                canvas.drawText((station.subway ? "地铁 " : "公交 ") + station.name,
                        selScreen[0] + 16 * density, selScreen[1] - 12 * density, markerLabel);
            } else {
                drawMarker(canvas, station.lat, station.lon,
                        station.subway ? Color.rgb(230, 81, 0) : Color.rgb(21, 101, 192),
                        station.name, station.subway ? "地铁" : "公交");
            }
        }

        // 线路：站点连线 + 站点圆点 + 车辆位置（车来了式）
        if (!routePoints.isEmpty()) {
            routePaint.setStyle(Paint.Style.STROKE);
            routePaint.setStrokeCap(Paint.Cap.ROUND);
            routePaint.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path path = new android.graphics.Path();
            boolean first = true;
            for (double[] p : routePoints) {
                float[] s = toScreen(p[0], p[1], centerPixel);
                if (first) {
                    path.moveTo(s[0], s[1]);
                    first = false;
                } else {
                    path.lineTo(s[0], s[1]);
                }
            }
            // 白色细描边把线路从复杂底图中托起来，再画主题色主线。
            routePaint.setStrokeWidth(7 * density);
            routePaint.setColor(0xDDFFFFFF);
            canvas.drawPath(path, routePaint);
            routePaint.setStrokeWidth(4 * density);
            routePaint.setColor(routeColor);
            canvas.drawPath(path, routePaint);
            // 只画真实公交站：白色外环 + 主题色中心，polyline 拐点不画圆点。
            stationPaint.setStyle(Paint.Style.FILL);
            for (double[] p : routeStationPoints) {
                float[] s = toScreen(p[0], p[1], centerPixel);
                stationPaint.setColor(Color.WHITE);
                canvas.drawCircle(s[0], s[1], 6.5f * density, stationPaint);
                stationPaint.setColor(routeColor);
                canvas.drawCircle(s[0], s[1], 4.2f * density, stationPaint);
            }
        }
        if (!vehiclePoints.isEmpty()) {
            vehiclePaint.setStyle(Paint.Style.FILL);
            vehiclePaint.setColor(Color.rgb(230, 129, 62));
            for (int i = 0; i < vehiclePoints.size(); i++) {
                double[] p = vehiclePoints.get(i);
                float[] s = toScreen(p[0], p[1], centerPixel);
                // 白描边 + 橙色车身（醒目）
                vehiclePaint.setStyle(Paint.Style.STROKE);
                vehiclePaint.setStrokeWidth(3 * density);
                vehiclePaint.setColor(Color.WHITE);
                canvas.drawCircle(s[0], s[1], 11 * density, vehiclePaint);
                vehiclePaint.setStyle(Paint.Style.FILL);
                vehiclePaint.setColor(Color.rgb(230, 129, 62));
                canvas.drawCircle(s[0], s[1], 9 * density, vehiclePaint);
                // Infer heading from the nearest directed route segment, not screen left/right.
                float bestDistance = Float.MAX_VALUE, headingX = 0, headingY = 0;
                for (int r = 1; r < routePoints.size(); r++) {
                    double[] a = routePoints.get(r - 1), b = routePoints.get(r);
                    float[] aa = toScreen(a[0], a[1], centerPixel), bb = toScreen(b[0], b[1], centerPixel);
                    float dx = bb[0] - aa[0], dy = bb[1] - aa[1], length = dx * dx + dy * dy;
                    if (length < .01f) continue;
                    float t = Math.max(0, Math.min(1, ((s[0]-aa[0])*dx + (s[1]-aa[1])*dy)/length));
                    float ex = s[0]-aa[0]-t*dx, ey = s[1]-aa[1]-t*dy;
                    if (ex*ex + ey*ey < bestDistance) {
                        bestDistance = ex*ex + ey*ey;
                        float norm = (float)Math.sqrt(length);
                        headingX = dx/norm; headingY = dy/norm;
                    }
                }
                if (bestDistance < 6400 * density * density) {
                    android.graphics.Path arrow = new android.graphics.Path();
                    float ux = headingX * 7 * density, uy = headingY * 7 * density;
                    arrow.moveTo(s[0]+ux, s[1]+uy);
                    arrow.lineTo(s[0]-ux*.7f-uy*.65f, s[1]-uy*.7f+ux*.65f);
                    arrow.lineTo(s[0]-ux*.25f, s[1]-uy*.25f);
                    arrow.lineTo(s[0]-ux*.7f+uy*.65f, s[1]-uy*.7f-ux*.65f);
                    arrow.close();
                    vehiclePaint.setColor(Color.WHITE);
                    canvas.drawPath(arrow, vehiclePaint);
                }
                if (i < vehicleLabels.size() && !vehicleLabels.get(i).isEmpty()) {
                    vehicleLabel.setColor(Color.rgb(230, 129, 62));
                    vehicleLabel.setTextSize(10 * density);
                    vehicleLabel.setStrokeWidth(3 * density);
                    vehicleLabel.setStyle(Paint.Style.STROKE);
                    vehicleLabel.setColor(Color.WHITE);
                    canvas.drawText(vehicleLabels.get(i),
                            s[0] + 14 * density, s[1] - 8 * density, vehicleLabel);
                    vehicleLabel.setStyle(Paint.Style.FILL);
                    vehicleLabel.setColor(Color.rgb(230, 129, 62));
                    canvas.drawText(vehicleLabels.get(i),
                            s[0] + 14 * density, s[1] - 8 * density, vehicleLabel);
                }
            }
        }

        if (hasUserLocation) {
            float[] screen = toScreen(userLat, userLon, centerPixel);
            // 脉冲光环（扩散渐隐）
            userPaint.setStyle(Paint.Style.STROKE);
            userPaint.setStrokeWidth(2.5f * density);
            userPaint.setColor(Color.argb((int) (150 * (1 - userPulse)),
                    Color.red(Color.rgb(46, 125, 50)),
                    Color.green(Color.rgb(46, 125, 50)),
                    Color.blue(Color.rgb(46, 125, 50))));
            canvas.drawCircle(screen[0], screen[1],
                    12 * density + userPulse * 18 * density, userPaint);
            userPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(screen[0], screen[1], 12 * density, userPaint);
            userPaint.setStyle(Paint.Style.STROKE);
            userPaint.setStrokeWidth(3 * density);
            userPaint.setColor(Color.WHITE);
            canvas.drawCircle(screen[0], screen[1], 12 * density, userPaint);
            userPaint.setColor(Color.rgb(46, 125, 50));
            // 我的位置标签
            vehicleLabel.setTextSize(10 * density);
            vehicleLabel.setStrokeWidth(3 * density);
            vehicleLabel.setStyle(Paint.Style.STROKE);
            vehicleLabel.setColor(Color.WHITE);
            canvas.drawText("我的位置", screen[0] + 15 * density, screen[1] - 10 * density, vehicleLabel);
            vehicleLabel.setStyle(Paint.Style.FILL);
            vehicleLabel.setColor(Color.rgb(46, 125, 50));
            canvas.drawText("我的位置", screen[0] + 15 * density, screen[1] - 10 * density, vehicleLabel);
        }

        textPaint.setColor(Color.rgb(90, 90, 90));
        String attribution = "osm".equals(activeProvider)
                ? "© OpenStreetMap contributors" : "© 高德地图";
        canvas.drawText(attribution, 8 * density,
                getHeight() - 8 * density, textPaint);
    }

    private void drawMarker(Canvas canvas, double lat, double lon, int color, String name, String type) {
        double[] centerPixel = pxWgs(centerLat, centerLon);
        float[] screen = toScreen(lat, lon, centerPixel);
        float radius = 9 * density;
        markerStroke.setStyle(Paint.Style.STROKE);
        markerStroke.setStrokeWidth(3 * density);
        markerStroke.setColor(Color.WHITE);
        markerFill.setStyle(Paint.Style.FILL);
        markerFill.setColor(color);
        canvas.drawCircle(screen[0], screen[1], radius, markerStroke);
        canvas.drawCircle(screen[0], screen[1], radius, markerFill);
        markerLabel.setColor(color);
        markerLabel.setTextSize(11 * density);
        markerLabel.setStrokeWidth(2 * density);
        markerLabel.setStyle(Paint.Style.FILL);
        canvas.drawText(type + " " + name, screen[0] + radius + 3 * density, screen[1] - radius, markerLabel);
    }

    private float[] toScreen(double lat, double lon, double[] centerPixel) {
        double[] pixel = pxWgs(lat, lon);
        return new float[]{
                (float) (pixel[0] - centerPixel[0] + getWidth() / 2.0),
                (float) (pixel[1] - centerPixel[1] + getHeight() / 2.0)
        };
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mapGestureListener != null) mapGestureListener.onMapGesture();
                lastX = event.getX();
                lastY = event.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.abs(dx) + Math.abs(dy) > 8) moved = true;
                if (moved) {
                    double[] centerPixel = pxWgs(centerLat, centerLon);
                    double[] newCenter = wgsFromPx(centerPixel[0] - dx, centerPixel[1] - dy);
                    centerLat = newCenter[0];
                    centerLon = newCenter[1];
                    lastX = event.getX();
                    lastY = event.getY();
                    postInvalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && stationTapListener != null) {
                    handleTap(event.getX(), event.getY());
                }
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (tileExecutor.isShutdown()) {
            tileExecutor = Executors.newFixedThreadPool(4);
            queuedViewport = "";
            synchronized (pendingTiles) { pendingTiles.clear(); }
        }
        if (hasUserLocation) pulseAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        pulseAnimator.cancel();
        // 释放瓦片下载线程，避免 Activity 重建时线程泄漏
        tileExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void handleTap(float x, float y) {
        double[] centerPixel = pxWgs(centerLat, centerLon);
        NearbyStation best = null;
        float bestDistance = Float.MAX_VALUE;
        for (NearbyStation station : stations) {
            float[] screen = toScreen(station.lat, station.lon, centerPixel);
            float distance = (float) Math.hypot(screen[0] - x, screen[1] - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = station;
            }
        }
        if (best != null && bestDistance < MARKER_TOUCH_RADIUS * density) {
            selectedStationName = best.name;
            postInvalidate();
            if (stationTapListener != null) {
                stationTapListener.onStationTap(best);
            }
        }
    }

    private Bitmap getTile(int z, int x, int y) {
        int world = 1 << z;
        int wrappedX = ((x % world) + world) % world;
        String key = activeProvider + "/" + z + "/" + wrappedX + "/" + y;
        Bitmap cached = memoryCache.get(key);
        if (cached != null) return cached;
        requestTile(z, wrappedX, y);
        return null;
    }

    private void requestTile(int z, int x, int y) {
        if (!PrivacyNotice.locationAllowed()) return;
        int world = 1 << z;
        if (y < 0 || y >= world || tileExecutor.isShutdown()) return;
        x = ((x % world) + world) % world;
        final String provider = activeProvider;
        String key = provider + "/" + z + "/" + x + "/" + y;
        if (memoryCache.containsKey(key)) return;
        Long retry=failedTiles.get(key);
        if (retry!=null && android.os.SystemClock.uptimeMillis()<retry) return;
        File file = new File(tileDir, key + ".png");
        synchronized (pendingTiles) {
            if (!pendingTiles.contains(key)) {
                pendingTiles.add(key);
                final int tileX = x;
                final int tileY = y;
                tileExecutor.execute(() -> loadTile(z, tileX, tileY, key, file, provider));
            }
        }
    }

    /** 按距视野中心的曼哈顿距离排队，让四个线程先填满用户正在看的区域。 */
    private void requestViewport(int left, int top, int right, int bottom) {
        int cx = (left + right) / 2;
        int cy = (top + bottom) / 2;
        int radius = Math.max(right - left, bottom - top) + 1;
        for (int distance = 0; distance <= radius; distance++) {
            for (int x = left; x <= right; x++) {
                for (int y = top; y <= bottom; y++) {
                    if (Math.abs(x - cx) + Math.abs(y - cy) == distance) {
                        requestTile(zoom, x, y);
                    }
                }
            }
        }
    }

    private void loadTile(int z, int x, int y, String key, File file, String provider) {
        try {
            // Discard queued work for old zoom levels/providers before disk/network I/O.
            if (z != zoom || !provider.equals(activeProvider)
                    || Thread.currentThread().isInterrupted()) return;
            if (file.exists()) {
                Bitmap disk = decodeTile(file);
                if (disk != null) {
                    // 磁盘命中即更新访问时间，清理时优先保留用户最近查看的区域。
                    file.setLastModified(System.currentTimeMillis());
                    File source = new File(file.getAbsolutePath() + ".source");
                    if (source.exists()) source.setLastModified(file.lastModified());
                    memoryCache.put(key, disk);
                    tileSources.put(key, readTileSource(file));
                    return;
                }
            }
            if ("amap".equals(provider)) {
                int shard = ((x + y) & 3) + 1;
                String domestic = "https://webrd0" + shard
                        + ".is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7"
                        + "&x=" + x + "&y=" + y + "&z=" + z;
                if (downloadTile(domestic, file, key, "amap")) return;
                // Keep the chosen provider. A transient failure must never switch
                // the map, its coordinate system or its attribution to another source.
            } else {
                String fallback = "https://tile.openstreetmap.org/"
                        + z + "/" + x + "/" + y + ".png";
                downloadTile(fallback, file, key, "osm");
            }
        } catch (Exception ignored) {
            // 瓦片加载失败时保留空白底图，不打断使用
        } finally {
            if (!memoryCache.containsKey(key)) failedTiles.put(key,android.os.SystemClock.uptimeMillis()+30_000L);
            else failedTiles.remove(key);
            synchronized (pendingTiles) {
                pendingTiles.remove(key);
            }
            postInvalidate();
        }
    }

    private boolean downloadTile(String url, File file, String key, String provider) {
        HttpURLConnection connection = null;
        File part = null;
        Bitmap decoded = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent",
                    "BashiDeHen/2.1.5 (Android; com.holi.prdbus)");
            long deadline = BoundedStreams.deadlineAfter(20_000);
            int limit = 2 * 1024 * 1024;
            if (connection.getResponseCode() != 200 || connection.getContentLength() > limit) return false;
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            part = File.createTempFile("tile-", ".part", dir);
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(part)) {
                BoundedStreams.copy(input, output, limit, deadline);
            }
            decoded = decodeTile(part);
            if (decoded == null || !provider.equals(activeProvider)) return false;
            synchronized (DISK_CACHE_LOCK) {
                File source = new File(file.getAbsolutePath() + ".source");
                long replacedBytes = file.length() + source.length();
                // Both files are in the same directory. A failed rename leaves the
                // previous tile in place, including its provider marker.
                android.system.Os.rename(part.getAbsolutePath(), file.getAbsolutePath());
                writeTileSource(file, provider);
                registerDiskWrite(replacedBytes, file.length() + source.length());
            }
            memoryCache.put(key, decoded);
            decoded = null;
            tileSources.put(key, provider);
            return file.isFile();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (part != null && part.exists()) part.delete();
            if (decoded != null) decoded.recycle();
        }
    }

    private static Bitmap decodeTile(File file) {
        String path = file.getAbsolutePath();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        int width = options.outWidth, height = options.outHeight;
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(width, height) > 512 ? 2 : 1;
        return BitmapFactory.decodeFile(path, options);
    }

    private String readTileSource(File file) {
        File source = new File(file.getAbsolutePath() + ".source");
        if (!source.exists()) return "amap";
        try {
            InputStream input = new java.io.FileInputStream(source);
            int value = input.read();
            input.close();
            return value == 'o' ? "osm" : "amap";
        } catch (Exception ignored) {
            return "amap";
        }
    }

    private void writeTileSource(File file, String provider) {
        try {
            FileOutputStream output = new FileOutputStream(
                    new File(file.getAbsolutePath() + ".source"));
            output.write(provider.startsWith("o") ? 'o' : 'a');
            output.close();
        } catch (Exception ignored) {
        }
    }

    /** 维护近似字节计数，只有达到上限时才扫描目录，避免每张瓦片都遍历文件树。 */
    private void registerDiskWrite(long replacedBytes, long completedBytes) {
        synchronized (DISK_CACHE_LOCK) {
            if (knownDiskCacheBytes < 0) {
                knownDiskCacheBytes = directoryBytes(tileDir, null);
            } else {
                knownDiskCacheBytes = Math.max(0L,
                        knownDiskCacheBytes - replacedBytes + completedBytes);
            }
            if (knownDiskCacheBytes > MAX_DISK_CACHE_BYTES) {
                trimDiskCacheLocked();
            }
        }
    }

    private void trimDiskCacheIfNeeded() {
        synchronized (DISK_CACHE_LOCK) {
            knownDiskCacheBytes = directoryBytes(tileDir, null);
            if (knownDiskCacheBytes > MAX_DISK_CACHE_BYTES) trimDiskCacheLocked();
        }
    }

    /** 超过 44MiB 时按最后访问时间清理至 40MiB，为并发下载及地铁暂存预留余量。 */
    private void trimDiskCacheLocked() {
        java.util.List<File> files = new java.util.ArrayList<>();
        long total = directoryBytes(tileDir, files);
        java.util.Collections.sort(files,
                (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long now = System.currentTimeMillis();
        for (File candidate : files) {
            if (total <= TRIMMED_DISK_CACHE_BYTES) break;
            // 不碰其他线程刚写入的临时文件；完成后 registerDiskWrite 会再次检查。
            if (candidate.getName().endsWith(".part")
                    && now - candidate.lastModified() < 2 * 60_000L) continue;
            long size = candidate.length();
            if (candidate.delete()) total = Math.max(0L, total - size);
        }
        removeEmptyCacheDirectories(tileDir);
        knownDiskCacheBytes = directoryBytes(tileDir, null);
    }

    private static long directoryBytes(File directory, java.util.List<File> files) {
        if (directory == null || !directory.exists()) return 0L;
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            if (child.isDirectory()) {
                total += directoryBytes(child, files);
            } else {
                total += Math.max(0L, child.length());
                if (files != null) files.add(child);
            }
        }
        return total;
    }

    private static void removeEmptyCacheDirectories(File directory) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            removeEmptyCacheDirectories(child);
            File[] remaining = child.listFiles();
            if (remaining != null && remaining.length == 0) child.delete();
        }
    }

    /** WGS-84 业务坐标 → GCJ-02 国内底图 Web Mercator 像素。 */
    private double[] pxWgs(double lat, double lon) {
        if ("osm".equals(activeProvider)) return GeoUtils.toPixel(lat, lon, zoom);
        double[] gcj = Coord.wgsToGcj(lat, lon);
        return GeoUtils.toPixel(gcj[0], gcj[1], zoom);
    }

    /** 国内底图 Web Mercator 像素 → WGS-84 业务坐标。 */
    private double[] wgsFromPx(double x, double y) {
        double[] point = GeoUtils.toLatLon(x, y, zoom);
        if ("osm".equals(activeProvider)) return point;
        return Coord.gcjToWgs(point[0], point[1]);
    }

    private static int clampZoom(int value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }
}
