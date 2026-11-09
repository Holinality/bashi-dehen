package com.holi.prdbus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Coalesces equal in-flight reads and briefly reuses successful responses. */
final class RequestReuse<T> {
    private final Map<String, FutureTask<T>> inFlight = new HashMap<>();
    private final LinkedHashMap<String, Entry<T>> cache = new LinkedHashMap<>(16, .75f, true);
    private final long ttlNanos;
    private final int capacity;

    private static final class Entry<T> {
        final long time = System.nanoTime();
        final T value;
        Entry(T value) { this.value = value; }
    }

    RequestReuse(long ttlMillis, int capacity) {
        this.ttlNanos = ttlMillis * 1_000_000L;
        this.capacity = capacity;
    }

    T get(String key, Callable<T> loader) throws Exception {
        FutureTask<T> task;
        boolean owner;
        synchronized (this) {
            Entry<T> hit = cache.get(key);
            if (hit != null && System.nanoTime() - hit.time < ttlNanos) return hit.value;
            cache.remove(key);
            task = inFlight.get(key);
            owner = task == null;
            if (owner) {
                task = new FutureTask<>(loader);
                inFlight.put(key, task);
            }
        }
        try {
            if (owner) task.run();
            T value = task.get();
            if (owner) synchronized (this) {
                cache.put(key, new Entry<>(value));
                while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            }
            return value;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        } finally {
            if (owner) synchronized (this) { inFlight.remove(key); }
        }
    }
}
