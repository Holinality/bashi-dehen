package com.holi.prdbus;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared, bounded reachability check for online OSM maps; never used for local rail data. */
final class OsmMapAccess {
    interface Listener { void onOsmAvailabilityChanged(); }

    private static final OsmAccessState STATE = new OsmAccessState();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // The idle worker exits, and no Activity or View is captured by the probe.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            0, 1, 20L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    private static final Set<Listener> LISTENERS = Collections.newSetFromMap(
            new WeakHashMap<Listener, Boolean>());
    private static Object network;
    private static boolean networkKnown;

    private OsmMapAccess() {}

    static void observe(Listener listener) { LISTENERS.add(listener); }
    static void unobserve(Listener listener) { LISTENERS.remove(listener); }
    static boolean available() { return STATE.available(); }
    static long generation() { return STATE.generation(); }

    /** Called only on the UI thread when a map enters the foreground. */
    static void refresh(Context context) {
        if (!PrivacyNotice.locationAllowed()) return;
        Object current = networkIdentity(context);
        if (networkKnown && (network == null ? current != null : !network.equals(current))) {
            STATE.networkChanged();
            notifyListeners();
        }
        network = current;
        networkKnown = true;
        long ticket = STATE.beginCheck(SystemClock.elapsedRealtime());
        if (ticket == 0L) return;
        long generation = STATE.generation();
        WORKER.execute(() -> {
            if (STATE.generation() != generation || !PrivacyNotice.locationAllowed()) return;
            boolean reachable = probe();
            if (STATE.completeCheck(ticket, reachable, SystemClock.elapsedRealtime())) {
                MAIN.post(OsmMapAccess::notifyListeners);
            }
        });
    }

    static void tileResult(long generation, boolean success) {
        if (STATE.recordTileResult(generation, success, SystemClock.elapsedRealtime())) {
            MAIN.post(OsmMapAccess::notifyListeners);
        }
    }

    private static void notifyListeners() {
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            if (listener != null) listener.onOsmAvailabilityChanged();
        }
    }

    private static Object networkIdentity(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            if (Build.VERSION.SDK_INT >= 23) return manager.getActiveNetwork();
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected() ? info.getType() + ":" + info.getSubtype() : null;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /** Validate an actual small tile, including its image header; a captive portal's HTTP 200 is not success.
     * This world tile does not disclose the user's location. At most one check runs per process.
     */
    private static boolean probe() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://tile.openstreetmap.org/0/0/0.png").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "BashiDeHen/2.1.26 (+https://github.com/Holinality/bashi-dehen)");
            int limit = 256 * 1024;
            long deadline = BoundedStreams.deadlineAfter(6000);
            if (connection.getResponseCode() != 200 || connection.getContentLength() > limit) return false;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                BoundedStreams.copy(input, bytes, limit, deadline);
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            return options.outWidth == 256 && options.outHeight == 256
                    && "image/png".equals(options.outMimeType);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
