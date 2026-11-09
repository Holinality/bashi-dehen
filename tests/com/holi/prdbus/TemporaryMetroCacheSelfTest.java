package com.holi.prdbus;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public final class TemporaryMetroCacheSelfTest {
    public static void main(String[] args) throws Exception {
        long now = 1_900_000_000_000L;
        require(TemporaryMetroCache.fresh(now - 29 * 24 * 60 * 60_000L, now), "must not recheck inside thirty days");
        require(!TemporaryMetroCache.fresh(now - 30 * 24 * 60 * 60_000L, now), "thirty day boundary");
        require(TemporaryMetroCache.usable(now - 8 * 24 * 60 * 60_000L, now), "offline grace period");
        require(!TemporaryMetroCache.usable(now - TemporaryMetroCache.MAX_AGE_MS, now), "maximum retention boundary");
        require(!TemporaryMetroCache.usable(now + 1, now), "future timestamp not fresh");
        require(!TemporaryMetroCache.usable(0, now), "missing timestamp not usable");
        require(!TemporaryMetroCache.retryDue(now - 1000, now), "failure backoff");
        require(TemporaryMetroCache.retryDue(now - TemporaryMetroCache.RETRY_MS, now), "failure retry boundary");
        require(TemporaryMetroCache.retryDue(now + 1, now), "clock rollback must not block retries");
        File folder = Files.createTempDirectory("metro-cache-test-").toFile();
        File file = new File(folder, "city.cache"), backup = new File(file.getPath() + ".bak");
        try {
            TemporaryMetroCache.write(file, now - 1000, "{\"city\":\"广州\",\"stations\":[\"北京路\"]}");
            TemporaryMetroCache.Entry first = TemporaryMetroCache.read(file, now);
            require(first != null && first.payload.contains("北京路"), "UTF8 cache roundtrip");
            require(first.at == now - 1000, "reading extends provider freshness");
            try { TemporaryMetroCache.write(file, now, ""); throw new AssertionError("empty cache accepted"); }
            catch (IOException expected) { }
            require(TemporaryMetroCache.read(file, now).payload.equals(first.payload), "failed write destroyed old data");
            char[] oversized = new char[TemporaryMetroCache.MAX_BYTES + 1]; Arrays.fill(oversized, 'x');
            try { TemporaryMetroCache.write(file, now, new String(oversized)); throw new AssertionError("oversize accepted"); }
            catch (IOException expected) { }
            require(TemporaryMetroCache.read(file, now).payload.equals(first.payload), "oversize destroyed old data");
            TemporaryMetroCache.write(file, now, "replacement");
            require(TemporaryMetroCache.read(file, now).payload.equals("replacement"), "replace existing snapshot");
            require(file.renameTo(backup), "backup simulation failed");
            require(TemporaryMetroCache.read(file, now).payload.equals("replacement"), "recover interrupted replacement");
            try (RandomAccessFile edit = new RandomAccessFile(file, "rw")) { edit.seek(file.length() - 1); edit.write(0); }
            require(TemporaryMetroCache.read(file, now) == null, "tampered payload accepted");
            TemporaryMetroCache.write(file, now, "replacement");
            try (RandomAccessFile edit = new RandomAccessFile(file, "rw")) { edit.setLength(20); }
            require(TemporaryMetroCache.read(file, now) == null, "truncated file accepted");
            TemporaryMetroCache.write(file, now - TemporaryMetroCache.MAX_AGE_MS, "expired");
            require(TemporaryMetroCache.read(file, now) == null && !file.exists(), "expired snapshot retained");
            System.out.println("TemporaryMetroCacheSelfTest passed (18 checks + write failure assertions)");
        } finally {
            new File(file.getPath() + ".part").delete(); backup.delete(); file.delete(); folder.delete();
        }
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
