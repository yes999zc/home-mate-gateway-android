package homes.mymate.gateway.protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java update checker supporting both public GitHub Releases API
 * ({@link #DEFAULT_GITHUB_RELEASES_API}) and optional self-hosted LAN/NAS {@code version.json}
 * mirrors. Zero Android dependencies so it is deterministically tested on the JVM.
 */
public final class ReleaseUpdater {

    public static final String DEFAULT_GITHUB_RELEASES_API =
            "https://api.github.com/repos/yes999zc/home-mate-gateway-android/releases/latest";

    public static final class UpdateResult {
        public final boolean available;
        public final boolean noReleaseYet;
        public final int currentVersionCode;
        public final int latestVersionCode;
        public final String latestVersionName;
        public final String downloadUrl;
        public final String notes;
        public final String error;

        public UpdateResult(
                boolean available,
                boolean noReleaseYet,
                int currentVersionCode,
                int latestVersionCode,
                String latestVersionName,
                String downloadUrl,
                String notes,
                String error) {
            this.available = available;
            this.noReleaseYet = noReleaseYet;
            this.currentVersionCode = currentVersionCode;
            this.latestVersionCode = latestVersionCode;
            this.latestVersionName = latestVersionName != null ? latestVersionName : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
            this.notes = notes != null ? notes : "";
            this.error = error;
        }
    }

    private ReleaseUpdater() {
    }

    public static UpdateResult checkSync(
            int currentVersionCode,
            String currentVersionName,
            String configuredUrl,
            boolean preferArm64) {
        String raw = configuredUrl != null ? configuredUrl.trim() : "";
        String target = raw.isEmpty() ? DEFAULT_GITHUB_RELEASES_API : raw;
        if (isGitHubReleasesUrl(target)) {
            return checkGitHubRelease(currentVersionCode, currentVersionName, target, preferArm64);
        }
        return checkVersionJsonMirror(currentVersionCode, currentVersionName, target);
    }

    public static boolean isGitHubReleasesUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.US);
        return lower.contains("/releases/latest") || lower.contains("api.github.com/repos/");
    }

    private static UpdateResult checkGitHubRelease(
            int currentVersionCode,
            String currentVersionName,
            String apiUrl,
            boolean preferArm64) {
        if (!isHttpUrl(apiUrl)) {
            return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                    "", "", "", "Invalid update URL scheme");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) (CoreApiClient.isPrivateOrTailscaleHost(url.getHost())
                    ? url.openConnection(Proxy.NO_PROXY)
                    : url.openConnection());
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "HomeMate-Gateway-Android/"
                    + (currentVersionName != null ? currentVersionName : "2.0.0"));
            int code = conn.getResponseCode();
            if (code == 404) {
                return new UpdateResult(false, true, currentVersionCode, currentVersionCode,
                        "", "", "", null);
            }
            if (code != 200) {
                return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                        "", "", "", "HTTP " + code);
            }
            String body = readResponseBody(conn);
            return parseGitHubReleaseJson(body, currentVersionCode, currentVersionName, preferArm64);
        } catch (Throwable t) {
            return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                    "", "", "", t.getMessage() != null ? t.getMessage() : "network error");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static UpdateResult parseGitHubReleaseJson(
            String jsonBody,
            int currentVersionCode,
            String currentVersionName,
            boolean preferArm64) {
        Map<String, Object> obj = MiniJson.parseObject(jsonBody);
        String tagName = MiniJson.getString(obj, "tag_name", "").trim();
        String latestName = stripVersionPrefix(tagName);
        if (latestName.isEmpty()) {
            latestName = stripVersionPrefix(MiniJson.getString(obj, "name", "").trim());
        }
        String htmlUrl = MiniJson.getString(obj, "html_url", "").trim();
        String notes = MiniJson.getString(obj, "body", "").trim();
        String downloadUrl = selectBestApkAssetUrl(MiniJson.getList(obj, "assets"), preferArm64);
        if (downloadUrl.isEmpty() && isHttpUrl(htmlUrl)) {
            downloadUrl = htmlUrl;
        }
        boolean newer = !latestName.isEmpty()
                && compareSemver(latestName, stripVersionPrefix(currentVersionName)) > 0
                && !downloadUrl.isEmpty();
        return new UpdateResult(
                newer,
                false,
                currentVersionCode,
                newer ? currentVersionCode + 1 : currentVersionCode,
                latestName,
                downloadUrl,
                notes,
                null);
    }

    private static String selectBestApkAssetUrl(List<Object> assets, boolean preferArm64) {
        if (assets == null || assets.isEmpty()) {
            return "";
        }
        String arm64Url = "";
        String universalUrl = "";
        String nonArm64ApkUrl = "";
        String anyApkUrl = "";
        for (Object item : assets) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> asset = (Map<String, Object>) item;
            String name = MiniJson.getString(asset, "name", "").trim().toLowerCase(Locale.US);
            String url = MiniJson.getString(asset, "browser_download_url", "").trim();
            if (!name.endsWith(".apk") || !isHttpUrl(url)) {
                continue;
            }
            if (anyApkUrl.isEmpty()) {
                anyApkUrl = url;
            }
            if (name.contains("arm64")) {
                if (arm64Url.isEmpty()) {
                    arm64Url = url;
                }
            } else if (name.contains("universal")) {
                if (universalUrl.isEmpty()) {
                    universalUrl = url;
                }
            } else if (nonArm64ApkUrl.isEmpty()) {
                nonArm64ApkUrl = url;
            }
        }
        if (preferArm64) {
            if (!arm64Url.isEmpty()) return arm64Url;
            if (!universalUrl.isEmpty()) return universalUrl;
            if (!nonArm64ApkUrl.isEmpty()) return nonArm64ApkUrl;
            return anyApkUrl;
        }
        // 32-bit / non-arm64 devices: never choose an arm64-only APK when universal or slim exists.
        if (!universalUrl.isEmpty()) return universalUrl;
        if (!nonArm64ApkUrl.isEmpty()) return nonArm64ApkUrl;
        return "";
    }

    private static UpdateResult checkVersionJsonMirror(
            int currentVersionCode,
            String currentVersionName,
            String baseUrlOrHost) {
        String baseUrl = normalizeBaseUrl(baseUrlOrHost);
        if (baseUrl.isEmpty()) {
            return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                    "", "", "", "No update source configured");
        }
        String urlStr = baseUrl.endsWith(".json") ? baseUrl : (baseUrl + "/version.json");
        String assetBase = baseUrl.endsWith(".json")
                ? baseUrl.substring(0, Math.max(0, baseUrl.lastIndexOf('/')))
                : baseUrl;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) (CoreApiClient.isPrivateOrTailscaleHost(url.getHost())
                    ? url.openConnection(Proxy.NO_PROXY)
                    : url.openConnection());
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            int code = conn.getResponseCode();
            if (code != 200) {
                return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                        "", "", "", "HTTP " + code);
            }
            Map<String, Object> obj = MiniJson.parseObject(readResponseBody(conn));
            int latestCode = (int) MiniJson.getLong(obj, "version_code", currentVersionCode);
            String latestName = stripVersionPrefix(MiniJson.getString(obj, "version_name", "2.0.0"));
            String rawApkUrl = MiniJson.getString(obj, "apk_url", "hm.apk");
            String resolvedApkUrl = resolveApkUrl(assetBase, rawApkUrl);
            String notes = MiniJson.getString(obj, "notes", "");
            String curClean = stripVersionPrefix(currentVersionName);
            boolean newer = latestCode > currentVersionCode
                    || (!curClean.isEmpty() && compareSemver(latestName, curClean) > 0);
            return new UpdateResult(
                    newer && isHttpUrl(resolvedApkUrl),
                    false,
                    currentVersionCode,
                    latestCode,
                    latestName,
                    resolvedApkUrl,
                    notes,
                    null);
        } catch (Throwable t) {
            return new UpdateResult(false, false, currentVersionCode, currentVersionCode,
                    "", "", "", t.getMessage() != null ? t.getMessage() : "network error");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readResponseBody(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int r;
            while ((r = br.read(buf)) != -1 && sb.length() < 131072) {
                int take = Math.min(r, 131072 - sb.length());
                sb.append(buf, 0, take);
            }
        }
        return sb.toString();
    }

    public static String stripVersionPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1).trim();
        }
        return s;
    }

    /**
     * Compares dotted numeric semantic versions (e.g., {@code 2.3.10} vs {@code 2.3.9}).
     * Pre-release suffixes (e.g., {@code -rc1}) sort lower than the corresponding release.
     */
    public static int compareSemver(String a, String b) {
        String sa = stripVersionPrefix(a);
        String sb = stripVersionPrefix(b);
        if (sa.equals(sb)) {
            return 0;
        }
        if (sa.isEmpty()) {
            return -1;
        }
        if (sb.isEmpty()) {
            return 1;
        }
        String[] aMainPre = sa.split("-", 2);
        String[] bMainPre = sb.split("-", 2);
        String[] aParts = aMainPre[0].split("\\.");
        String[] bParts = bMainPre[0].split("\\.");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int ai = i < aParts.length ? parseIntSafe(aParts[i]) : 0;
            int bi = i < bParts.length ? parseIntSafe(bParts[i]) : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        boolean aPre = aMainPre.length > 1 && !aMainPre[1].isEmpty();
        boolean bPre = bMainPre.length > 1 && !bMainPre[1].isEmpty();
        if (aPre && !bPre) {
            return -1;
        }
        if (!aPre && bPre) {
            return 1;
        }
        if (aPre) {
            return aMainPre[1].compareToIgnoreCase(bMainPre[1]);
        }
        return 0;
    }

    private static int parseIntSafe(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return 0;
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
        if (isHttpUrl(s)) {
            return s;
        }
        return "http://" + s;
    }

    public static String resolveApkUrl(String baseUrl, String rawApkUrl) {
        String raw = rawApkUrl != null ? rawApkUrl.trim() : "";
        if (raw.isEmpty()) {
            return baseUrl.isEmpty() ? "" : (baseUrl + "/hm.apk");
        }
        if (isHttpUrl(raw)) {
            return raw;
        }
        if (baseUrl.isEmpty()) {
            return "";
        }
        if (raw.startsWith("/")) {
            return baseUrl + raw;
        }
        return baseUrl + "/" + raw;
    }

    public static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
