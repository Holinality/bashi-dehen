package com.holi.prdbus;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Bounded, checksummed and replace-safe temporary app cache; contains no API credentials. */
final class TemporaryMetroCache {
    static final long FRESH_MS = 30 * 24 * 60 * 60_000L;
    static final long MAX_AGE_MS = 3650 * 24 * 60 * 60_000L;
    static final long RETRY_MS = FRESH_MS;
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x4D545231;
    static final class Entry {
        final long at;
        final String payload;
        Entry(long at, String payload) { this.at = at; this.payload = payload; }
    }
    static boolean usable(long at, long now) { return at > 0 && at <= now && now - at < MAX_AGE_MS; }
    static boolean fresh(long at, long now) { return usable(at, now) && now - at < FRESH_MS; }
    static boolean retryDue(long at, long now) { return at <= 0 || at > now || now - at >= RETRY_MS; }

    static synchronized Entry read(File file, long now) {
        File backup = new File(file.getPath() + ".bak");
        if (!file.exists() && backup.exists()) backup.renameTo(file);
        boolean expired = false;
        try {
            if (!file.isFile() || file.length() > MAX_BYTES + 48) return null;
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                if (input.readInt() != MAGIC) return null;
                long at = input.readLong();
                int length = input.readInt();
                if (!usable(at, now)) { expired = true; return null; }
                if (length <= 0 || length > MAX_BYTES || file.length() != length + 48L) return null;
                byte[] expected = new byte[32], content = new byte[length];
                input.readFully(expected); input.readFully(content);
                if (!Arrays.equals(expected, MessageDigest.getInstance("SHA-256").digest(content))) return null;
                return new Entry(at, new String(content, "UTF-8"));
            }
        } catch (Exception unavailable) { return null; }
        finally {
            // Close the input before removing expired files (also works on Windows).
            if (expired) { file.delete(); backup.delete(); }
        }
    }

    static synchronized void write(File file, long at, String payload) throws Exception {
        byte[] content = payload.getBytes("UTF-8");
        if (at <= 0 || content.length == 0 || content.length > MAX_BYTES) throw new IOException("地铁暂存超过上限");
        File folder = file.getParentFile();
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new IOException("暂存目录不可写");
        File part = new File(file.getPath() + ".part"), backup = new File(file.getPath() + ".bak");
        try {
            try (FileOutputStream stream = new FileOutputStream(part);
                 DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(MAGIC); output.writeLong(at); output.writeInt(content.length);
                output.write(MessageDigest.getInstance("SHA-256").digest(content)); output.write(content);
                output.flush(); stream.getFD().sync();
            }
            if (file.exists()) {
                if (backup.exists() && !backup.delete()) throw new IOException("旧备份不可替换");
                if (!file.renameTo(backup)) throw new IOException("保留旧暂存失败");
            }
            if (!part.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file);
                throw new IOException("地铁暂存写入失败");
            }
            backup.delete();
        } finally { part.delete(); }
    }
}
