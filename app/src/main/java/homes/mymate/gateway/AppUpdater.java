package homes.mymate.gateway;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import homes.mymate.gateway.protocol.MiniJson;

/**
 * Lightweight In-App Update Checker querying {@code version.json} on the configured Home Mate
 * APK distribution host (e.g. {@code http://<coreHost>:8931/version.json}).
 *
 * v2.0 hardening:
 * - Uses {@link Proxy#NO_PROXY} for LAN & Tailscale private hosts.
 * - Resolves relative {@code apk_url} (e.g. {@code "hm.apk"}) against the responding base URL.
 * - Guards {@link #openDownloadInBrowser} against {@link ActivityNotFoundException} on headless /
 *   86 wall panel ROMs without a browser.
 */
public final class AppUpdater {

    public static final class UpdateInfo {
        public final boolean available;
        public final int currentVersionCode;
        public final int latestVersionCode;
        public final String latestVersionName;
        public final String downloadUrl;
        public final String notes;
        public final String error;

        public UpdateInfo(
                boolean available,
                int currentVersionCode,
                int latestVersionCode,
                String latestVersionName,
                String downloadUrl,
                String notes,
                String error) {
            this.available = available;
            this.currentVersionCode = currentVersionCode;
            this.latestVersionCode = latestVersionCode;
            this.latestVersionName = latestVersionName;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
            this.error = error;
        }
    }

    public interface Callback {
        void onResult(UpdateInfo info);
    }

    private AppUpdater() {
    }

    @SuppressWarnings("deprecation")
    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Throwable ignored) {
            return 400;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "2.0.0";
        } catch (Throwable ignored) {
            return "2.0.0";
        }
    }

    public static void checkAsync(final Context context, final String baseUrlOrHost, final Callback callback) {
        final int curCode = getCurrentVersionCode(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateInfo result = checkSync(curCode, baseUrlOrHost);
                if (callback != null) {
                    callback.onResult(result);
                }
            }
        }, "hm-app-updater").start();
    }

    public static UpdateInfo checkSync(int currentVersionCode, String baseUrlOrHost) {
        String baseUrl = normalizeBaseUrl(baseUrlOrHost);
        if (baseUrl.isEmpty()) {
            return new UpdateInfo(false, currentVersionCode, currentVersionCode, "", "", "", "No Core configured");
        }
        String urlStr = baseUrl + "/version.json";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) (isPrivateHost(url.getHost())
                    ? url.openConnection(Proxy.NO_PROXY)
                    : url.openConnection());
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) {
                return new UpdateInfo(false, currentVersionCode, currentVersionCode, "", "", "", "HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            Map<String, Object> obj = MiniJson.parseObject(sb.toString());
            int latestCode = (int) MiniJson.getLong(obj, "version_code", currentVersionCode);
            String latestName = MiniJson.getString(obj, "version_name", "2.0.0");
            String rawApkUrl = MiniJson.getString(obj, "apk_url", "hm.apk");
            String resolvedApkUrl = resolveApkUrl(baseUrl, rawApkUrl);
            String notes = MiniJson.getString(obj, "notes", "");
            return new UpdateInfo(
                    latestCode > currentVersionCode,
                    currentVersionCode,
                    latestCode,
                    latestName,
                    resolvedApkUrl,
                    notes,
                    null);
        } catch (Throwable t) {
            return new UpdateInfo(false, currentVersionCode, currentVersionCode, "", "", "", t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String normalizeBaseUrl(String baseUrlOrHost) {
        String s = baseUrlOrHost != null ? baseUrlOrHost.trim() : "";
        if (s.isEmpty()) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        return "http://" + s + ":8931";
    }

    public static String resolveApkUrl(String baseUrl, String rawApkUrl) {
        String raw = rawApkUrl != null ? rawApkUrl.trim() : "";
        if (raw.isEmpty()) {
            return baseUrl + "/hm.apk";
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("/")) {
            return baseUrl + raw;
        }
        return baseUrl + "/" + raw;
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) {
            return false;
        }
        return host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("100.") || host.startsWith("172.")
                || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    public static void openDownloadInBrowser(Context context, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Toast.makeText(context, downloadUrl.trim(), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
