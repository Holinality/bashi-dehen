package com.holi.prdbus;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class PublicTransportHttp {
    static final class Redirect extends IOException {
        final String location;
        Redirect(String location) { super("Unexpected HTTP redirect"); this.location = location; }
    }

    static String get(String url, String referer) throws Exception {
        return get(url, referer, null);
    }

    static String get(String url, String referer, java.net.CookieManager cookies) throws Exception {
        if (!PrivacyNotice.locationAllowed()) throw new IOException("请先查看应用隐私说明");
        URL endpoint = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(6500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json, */*");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BusTransit");
        if (referer != null) connection.setRequestProperty("Referer", referer);
        if (cookies != null) {
            for (java.util.Map.Entry<String, java.util.List<String>> header :
                    cookies.get(endpoint.toURI(), java.util.Collections.emptyMap()).entrySet()) {
                for (String value : header.getValue()) connection.addRequestProperty(header.getKey(), value);
            }
        }
        try {
            int status = connection.getResponseCode();
            if (cookies != null) cookies.put(endpoint.toURI(), connection.getHeaderFields());
            if (status == 301 || status == 302 || status == 307 || status == 308)
                throw new Redirect(connection.getHeaderField("Location"));
            if (status != 200) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 5 * 1024 * 1024) throw new IOException("Response too large");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject json(String url, String referer) throws Exception {
        return new JSONObject(get(url, referer));
    }

    private PublicTransportHttp() { }
}
