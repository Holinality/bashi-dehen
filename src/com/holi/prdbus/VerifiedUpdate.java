package com.holi.prdbus;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

final class VerifiedUpdate {
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private VerifiedUpdate() {}

    static File directory(Context context) {
        return new File(context.getCacheDir(), "verified-updates");
    }

    static File publishedFile(Context context, String name) {
        if (name == null || !name.matches("update-[1-9][0-9]{0,9}-[a-f0-9]{64}\\.apk")) return null;
        File file = new File(directory(context), name);
        return file.isFile() ? file : null;
    }

    static void clearInstalled(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null || files.length == 0) return;
        try {
            long installed = version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
            for (File file : files) {
                String name = file.getName();
                if (publishedFile(context, name) == null) continue;
                long code = Long.parseLong(name.substring(7, name.indexOf('-', 7)));
                if (code <= installed) file.delete();
            }
        } catch (Exception unavailable) {
            // The cache can also be reclaimed by Android.
        }
    }

    static synchronized File download(Context context, String url, String hash, int versionCode,
                                      String versionName) throws Exception {
        if (!UpdatePolicy.validDownload(url, hash)) throw new IOException("更新地址无效");
        PackageManager manager = context.getPackageManager();
        int signingFlag = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), signingFlag);
        if (versionCode <= version(installed)) throw new IOException("安装包版本不是更新版本");
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新缓存");
        String name = "update-" + versionCode + "-" + hash.toLowerCase(Locale.ROOT) + ".apk";
        File destination = new File(directory, name);
        File part = File.createTempFile("download-", ".part.apk", directory);
        HttpsURLConnection connection = null;
        long deadline = BoundedStreams.deadlineAfter(120_000);
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) throw new IOException("下载服务暂不可用");
            int length = connection.getContentLength();
            if (length == 0 || length > MAX_BYTES) throw new IOException("安装包大小异常");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream file = new FileOutputStream(part);
                 DigestOutputStream output = new DigestOutputStream(file, digest)) {
                bytes = BoundedStreams.copy(input, output, MAX_BYTES, deadline);
                output.flush();
            }
            if (bytes == 0 || (length >= 0 && bytes != length) || !UpdatePolicy.matchesSha256(hash, digest.digest())) {
                throw new IOException("安装包哈希校验失败");
            }
            PackageInfo downloaded = manager.getPackageArchiveInfo(part.getAbsolutePath(), signingFlag);
            if (downloaded == null || !installed.packageName.equals(downloaded.packageName)
                    || version(downloaded) != versionCode || !versionName.equals(downloaded.versionName)) {
                throw new IOException("安装包身份或版本不符");
            }
            Signature[] current = signers(installed), candidate = signers(downloaded);
            if (current == null || candidate == null || current.length == 0 || candidate.length != current.length
                    || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(candidate)))) {
                throw new IOException("安装包签名与当前应用不符");
            }
            BoundedStreams.checkDeadline(deadline);
            // The provider can only open final names created after all checks.
            Os.rename(part.getAbsolutePath(), destination.getAbsolutePath());
            File[] files = directory.listFiles();
            if (files != null) {
                for (File older : files) {
                    if (!older.equals(destination) && publishedFile(context, older.getName()) != null) older.delete();
                }
            }
            return destination;
        } finally {
            if (connection != null) connection.disconnect();
            part.delete();
        }
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }
}
