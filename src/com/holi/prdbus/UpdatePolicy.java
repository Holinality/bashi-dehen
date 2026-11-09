package com.holi.prdbus;
import java.net.URI;
import java.security.MessageDigest;
final class UpdatePolicy {
    static final String HOST = "bus.holinality.com";
    static final String MANIFEST_URL = "https://" + HOST + "/prdbus/update.json";
    static boolean validDownload(String url, String hash) {
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().matches("/prdbus/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.apk")
                    && uri.getQuery() == null && uri.getFragment() == null && hash.matches("[a-fA-F0-9]{64}");
        } catch (Exception invalid) { return false; }
    }

    static boolean matchesSha256(String expected, byte[] actual) {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}") || actual == null) return false;
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(expected.substring(i * 2, i * 2 + 2), 16);
        }
        return MessageDigest.isEqual(bytes, actual);
    }
}
