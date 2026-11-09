package com.holi.prdbus;

import android.content.Context;
import android.net.ConnectivityManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Shared provider-first repository for all existing metro cities and all app entry points. */
final class MetroRepository {
    static final String[] CITIES = {"广州", "深圳", "佛山", "东莞"};
    private static final Object LOCK = new Object();
    private static final ExecutorService COORDINATOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2);
    private static final Map<String, CitySnapshot> SNAPSHOTS = new LinkedHashMap<>();
    private static final Map<String, Long> ATTEMPTS = new HashMap<>();
    private static final Set<String> LOADED_CITIES = new LinkedHashSet<>();
    private static final Set<String> REFRESHING_CITIES = new HashSet<>();
    private static File folder;
    private static android.content.SharedPreferences checks;
    private static List<MetroDataSource.MetroLine> base;
    private static List<MetroDataSource.MetroLine> assembled;
    private static long expiresAt;
    private static final java.util.Set<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArraySet<>();
    static void observe(Runnable listener) { LISTENERS.add(listener); }
    static void unobserve(Runnable listener) { LISTENERS.remove(listener); }
    static boolean isRefreshing(String province) {
        synchronized (LOCK) {
            for (String city : Regions.metroCities(province))
                if (REFRESHING_CITIES.contains(city)) return true;
            return false;
        }
    }
    static boolean hasSavedProvince(Context context, String province) {
        File dir = new File(context.getFilesDir(), "metro-gaode-v1");
        for (String city : Regions.metroCities(province))
            if (new File(dir, fileName(city)).isFile()) return true;
        return false;
    }
    static long cityDataAt(String city) {
        synchronized (LOCK) {
            CitySnapshot snapshot = SNAPSHOTS.get(city);
            long at = snapshot != null && TemporaryMetroCache.usable(snapshot.at, System.currentTimeMillis()) ? snapshot.at : 0;
            long now = System.currentTimeMillis();
            if (assembled != null) for (MetroDataSource.MetroLine line : assembled)
                if (MetroDataSource.cityOfLine(line).equals(city) && TemporaryMetroCache.usable(line.dataAt, now))
                    at = Math.max(at, line.dataAt);
            return at;
        }
    }

    private static final class CitySnapshot {
        final long at;
        final List<ChelaileDataSource.GaodeLine> rows;
        final List<MetroDataSource.MetroLine> lines;
        CitySnapshot(long at, List<ChelaileDataSource.GaodeLine> rows, List<MetroDataSource.MetroLine> lines) {
            this.at = at; this.rows = rows; this.lines = lines;
        }
    }

    static List<MetroDataSource.MetroLine> load(Context context, List<MetroDataSource.MetroLine> bundled) {
        return load(context, bundled, AppSettings.activeProvince(context));
    }

    static List<MetroDataSource.MetroLine> load(Context context, List<MetroDataSource.MetroLine> bundled, String province) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (folder == null) initialize(context, bundled);
            String[] scoped = Regions.metroCities(province);
            for (String city : scoped) {
                if (!LOADED_CITIES.add(city)) continue;
                ATTEMPTS.put(city, checks.getLong(city, 0));
                loadLocalSnapshot(context, city, now);
                assembled = null;
            }
            if (assembled == null || now >= expiresAt) assemble(now);
            List<String> due = new ArrayList<>();
            for (String city : scoped) {
                CitySnapshot snapshot = SNAPSHOTS.get(city);
                if ((snapshot == null || !hasColours(snapshot.rows) || !TemporaryMetroCache.fresh(snapshot.at, now))
                        && TemporaryMetroCache.retryDue(ATTEMPTS.containsKey(city) ? ATTEMPTS.get(city) : 0, now)) due.add(city);
            }
            due.removeAll(REFRESHING_CITIES);
            if (!due.isEmpty() && online(context)
                    && (AppSettings.hasMapKey() || PublicBuildConfig.ALLOW_UNDOCUMENTED_METRO_FETCH)) {
                android.content.SharedPreferences.Editor editor = checks.edit();
                for (String city : due) editor.putLong(city, now);
                if (!editor.commit()) return new ArrayList<>(assembled);
                for (String city : due) ATTEMPTS.put(city, now);
                REFRESHING_CITIES.addAll(due);
                COORDINATOR.execute(() -> refresh(due));
            }
            // Local data is immediately usable while monthly revalidation
            // publishes later changes through the observers.
            return new ArrayList<>(assembled);
        }
    }

    private static void initialize(Context context, List<MetroDataSource.MetroLine> bundled) {
        folder = new File(context.getFilesDir(), "metro-gaode-v1");
        checks = context.getSharedPreferences("metro-monthly-colour-checks", Context.MODE_PRIVATE);
        base = bundled;
    }

    private static TemporaryMetroCache.Entry migrateSnapshot(Context context, String city, long now) {
        TemporaryMetroCache.Entry cache = TemporaryMetroCache.read(
                new File(new File(context.getCacheDir(), "metro-gaode-v1"), file(city).getName()), now);
        if (cache != null) {
            try { TemporaryMetroCache.write(file(city), cache.at, cache.payload); } catch (Exception ignored) { }
            return cache;
        }
        for (String legacyCity : CITIES) {
            if (!legacyCity.equals(city)) continue;
            try {
                folder.mkdirs();
                try (java.io.InputStream input = context.getAssets().open("metro-" + file(city).getName().replace(".cache", ".dat"));
                     java.io.OutputStream output = new java.io.FileOutputStream(file(city))) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                return TemporaryMetroCache.read(file(city), now);
            } catch (Exception unavailable) { return null; }
        }
        return null;
    }

    private static void loadLocalSnapshot(Context context, String city, long now) {
        TemporaryMetroCache.Entry saved = TemporaryMetroCache.read(file(city), now);
        List<ChelaileDataSource.GaodeLine> rows = null;
        long at = 0;
        if (saved != null) try {
            rows = decode(city, saved.payload);
            at = saved.at;
        } catch (Exception invalid) { }
        try (java.io.InputStream input = context.getAssets().open(PublicMetro.assetName(city));
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject bundledRoot = new JSONObject(bytes.toString("UTF-8"));
            long bundledAt = bundledRoot.getLong("at");
            if (rows == null || bundledAt > at || !hasColours(rows)) {
                List<ChelaileDataSource.GaodeLine> bundledRows = decode(city, bundledRoot);
                rows = bundledRows;
                at = bundledAt;
            }
        } catch (Exception unavailable) { }
        if (rows == null) {
            TemporaryMetroCache.Entry legacy = migrateSnapshot(context, city, now);
            if (legacy != null) try {
                rows = decode(city, legacy.payload);
                at = legacy.at;
            } catch (Exception invalid) { }
        }
        // Select the newest usable raw snapshot before constructing services.
        // Old .dat seeds and the bundled JSON must not build the same city twice.
        if (rows != null) try {
            SNAPSHOTS.put(city, new CitySnapshot(at, rows, MetroCatalog.build(city, rows, base)));
        } catch (Exception invalid) { }
    }

    private static void refresh(List<String> cities) {
        Collections.sort(cities, (a, b) -> Integer.compare(lineCount(a), lineCount(b)));
        Map<String, Future<CitySnapshot>> requests = new LinkedHashMap<>();
        for (String city : cities) requests.put(city, NETWORK.submit(() -> {
            CitySnapshot previous;
            synchronized (LOCK) {
                previous = SNAPSHOTS.get(city);
            }
            List<MetroDataSource.MetroLine> known = new ArrayList<>(base);
            if (previous != null) known.addAll(previous.lines);
            List<ChelaileDataSource.GaodeLine> rows;
            try {
                rows = PublicMetro.lines(city);
                if (rows.isEmpty()) throw new IllegalStateException("暂无公开地铁线路");
            } catch (Exception unavailable) {
                if (!AppSettings.hasMapKey()) throw unavailable;
                rows = ChelaileDataSource.gaodeMetroCity(city, known);
            }
            if (previous != null && !rows.isEmpty()) {
                Set<String> supplied = new HashSet<>();
                for (ChelaileDataSource.GaodeLine row : rows)
                    supplied.add(MetroCatalog.identity(city, row.name));
                for (ChelaileDataSource.GaodeLine row : previous.rows)
                    if (!supplied.contains(MetroCatalog.identity(city, row.name))) rows.add(row);
            }
            List<MetroDataSource.MetroLine> lines = MetroCatalog.build(city, rows, base);
            return new CitySnapshot(System.currentTimeMillis(), rows, lines);
        }));
        for (Map.Entry<String, Future<CitySnapshot>> request : requests.entrySet()) {
            try {
                CitySnapshot completed = request.getValue().get();
                try { TemporaryMetroCache.write(file(request.getKey()), completed.at, encode(request.getKey(), completed.rows)); }
                catch (Exception diskUnavailable) { /* Current provider result is still usable in memory. */ }
                synchronized (LOCK) { SNAPSHOTS.put(request.getKey(), completed); assemble(System.currentTimeMillis()); }
            } catch (Exception failedCity) { /* Keep that city's last good snapshot and every other city. */ }
            finally {
                synchronized (LOCK) { REFRESHING_CITIES.remove(request.getKey()); }
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                for (Runnable listener : LISTENERS) main.post(listener);
            }
        }
    }

    private static int lineCount(String city) {
        int count = 0;
        for (MetroDataSource.MetroLine line : base) if (MetroDataSource.cityOfLine(line).equals(city)) count++;
        return count;
    }

    private static void assemble(long now) {
        List<MetroDataSource.MetroLine> combined = new ArrayList<>();
        expiresAt = Long.MAX_VALUE;
        for (String city : LOADED_CITIES) {
            CitySnapshot snapshot = SNAPSHOTS.get(city);
            if (snapshot != null && TemporaryMetroCache.usable(snapshot.at, now)) {
                for (MetroDataSource.MetroLine line : snapshot.lines) {
                    MetroDataSource.MetroLine copy = copy(line);
                    if (copy.dataSource.startsWith("高德")) copy.dataAt = snapshot.at;
                    combined.add(copy);
                }
                expiresAt = Math.min(expiresAt, snapshot.at + TemporaryMetroCache.MAX_AGE_MS);
            } else {
                for (MetroDataSource.MetroLine line : base)
                    if (MetroDataSource.cityOfLine(line).equals(city)) combined.add(copy(line));
            }
        }
        Map<String, MetroDataSource.MetroLine> unique = new LinkedHashMap<>();
        for (MetroDataSource.MetroLine line : combined) {
            MetroDataSource.MetroLine old = unique.get(line.name);
            if (old == null || (!old.dataSource.startsWith("高德") && line.dataSource.startsWith("高德"))
                    || (line.dataSource.startsWith("高德") && line.dataAt > old.dataAt)) unique.put(line.name, line);
        }
        assembled = MetroPatterns.normalize(new ArrayList<>(unique.values()));
        MetroPatterns.rebuildTransfers(assembled);
    }

    private static MetroDataSource.MetroLine copy(MetroDataSource.MetroLine line) {
        MetroDataSource.MetroLine copy = new MetroDataSource.MetroLine(line.name, line.colour, line.branchInfo,
                line.ring, line.firstTime, line.lastTime, line.firstTimeR, line.lastTimeR);
        copy.networkId = line.networkId; copy.serviceLabel = line.serviceLabel;
        copy.dataSource = line.dataSource; copy.dataAt = line.dataAt;
        copy.stations.addAll(line.stations); copy.exprStops.addAll(line.exprStops); copy.exprNote = line.exprNote;
        return copy;
    }

    private static File file(String city) {
        return new File(folder, fileName(city));
    }

    private static String fileName(String city) {
        for (int i = 0; i < CITIES.length; i++) if (CITIES[i].equals(city)) return "city-" + i + ".cache";
        if (!city.matches("[\\p{IsHan}]{2,12}")) throw new IllegalArgumentException("unsupported metro city");
        return "city-" + city + ".cache";
    }

    private static boolean online(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && manager.getActiveNetworkInfo() != null && manager.getActiveNetworkInfo().isConnected();
        } catch (Exception unknown) { return true; }
    }

    private static boolean hasColours(List<ChelaileDataSource.GaodeLine> rows) {
        for (ChelaileDataSource.GaodeLine row : rows) if (!row.colour.isEmpty()) return true;
        return false;
    }

    static String encode(String city, List<ChelaileDataSource.GaodeLine> rows) throws Exception {
        JSONArray lines = new JSONArray();
        for (ChelaileDataSource.GaodeLine row : rows) {
            if (!"metro".equals(TransitRules.transitMode(row.transportType, row.name))) continue;
            JSONObject line = new JSONObject();
            line.put("name", row.name); line.put("type", row.transportType); line.put("status", row.operatingStatus);
            line.put("ring", row.ring); line.put("first", row.firstTime); line.put("last", row.lastTime);
            line.put("start", row.startSn); line.put("end", row.endSn);
            line.put("colour", row.colour);
            JSONArray stops = new JSONArray();
            for (int i = 0; i < row.stops.size(); i++) {
                double[] point = i < row.stopPoints.size() ? row.stopPoints.get(i) : null;
                if (point == null) throw new IllegalStateException("incomplete metro point");
                JSONObject stop = new JSONObject().put("name", row.stops.get(i)).put("lat", point[0]).put("lon", point[1]);
                String[] hours = row.stationHours.get(row.stops.get(i));
                if (hours != null) stop.put("first", hours[0]).put("last", hours[1]);
                stops.put(stop);
            }
            line.put("stops", stops); lines.put(line);
        }
        return new JSONObject().put("schema", 1).put("city", city).put("at", System.currentTimeMillis())
                .put("lines", lines).toString();
    }

    private static List<ChelaileDataSource.GaodeLine> decode(String city, String payload) throws Exception {
        return decode(city, new JSONObject(payload));
    }

    private static List<ChelaileDataSource.GaodeLine> decode(String city, JSONObject root) throws Exception {
        if (root.optInt("schema", 0) != 1 || !city.equals(root.optString("city", "")))
            throw new IllegalStateException("wrong metro snapshot");
        JSONArray lines = root.getJSONArray("lines");
        List<ChelaileDataSource.GaodeLine> rows = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.getJSONObject(i);
            JSONArray stops = line.getJSONArray("stops");
            List<String> names = new ArrayList<>(); List<double[]> points = new ArrayList<>();
            for (int j = 0; j < stops.length(); j++) {
                JSONObject stop = stops.getJSONObject(j);
                names.add(stop.getString("name")); points.add(new double[]{stop.getDouble("lat"), stop.getDouble("lon")});
            }
            ChelaileDataSource.GaodeLine row = new ChelaileDataSource.GaodeLine(line.getString("name"), line.optString("start", ""),
                    line.optString("end", ""), line.optString("first", ""), line.optString("last", ""), "",
                    names, points, new ArrayList<double[]>(), line.getString("type"), line.optInt("status", 1), line.optBoolean("ring", false));
            row.colour = line.optString("colour", "");
            for (int j = 0; j < stops.length(); j++) {
                JSONObject stop = stops.getJSONObject(j);
                if (stop.has("first") && stop.has("last")) row.stationHours.put(names.get(j),
                        new String[]{stop.optString("first"), stop.optString("last")});
            }
            rows.add(row);
        }
        return rows;
    }
}
