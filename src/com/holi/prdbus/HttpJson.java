package com.holi.prdbus;
import java.net.*;
import java.io.*;
import org.json.JSONObject;
final class HttpJson {
    static JSONObject get(String address) throws Exception {
        return get(address, 128 * 1024);
    }
    static JSONObject get(String address, int maxBytes) throws Exception {
        URL url = new URL(address);
        if (!PrivacyNotice.locationAllowed()) throw new IOException("请先查看应用隐私说明");
        if ("restapi.amap.com".equalsIgnoreCase(url.getHost()) && !PrivacyNotice.amapNoticeSeen())
            throw new IOException("请先查看高德 Key 使用说明");
        if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Service unavailable");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (bytes.size() + count > maxBytes) throw new IOException("Response too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
