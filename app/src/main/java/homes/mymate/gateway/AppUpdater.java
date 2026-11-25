package homes.mymate.gateway;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import homes.mymate.gateway.protocol.ReleaseUpdater;

/**
 * Lightweight In-App Update Checker backed by {@link ReleaseUpdater}.
 *
 * Defaults to public GitHub Releases ({@link ReleaseUpdater#DEFAULT_GITHUB_RELEASES_API}) so
 * external releases never point at a private LAN IP or dev port, while retaining full support
 * for optional custom {@code version.json} LAN/NAS mirrors.
 */
public final class AppUpdater {

    public static final class UpdateInfo {
        public final boolean available;
        public final boolean noReleaseYet;
        public final int currentVersionCode;
        public final int latestVersionCode;
        public final String latestVersionName;
        public final String downloadUrl;
        public final String notes;
        public final String error;

        public UpdateInfo(
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
            this.latestVersionName = latestVersionName;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
            this.error = error;
        }

        static UpdateInfo from(ReleaseUpdater.UpdateResult r) {
            return new UpdateInfo(
                    r.available,
                    r.noReleaseYet,
                    r.currentVersionCode,
                    r.latestVersionCode,
                    r.latestVersionName,
                    r.downloadUrl,
                    r.notes,
                    r.error);
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

    private static boolean devicePrefersArm64() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build.SUPPORTED_ABIS != null) {
            for (String abi : Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equalsIgnoreCase(abi)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public static void checkAsync(final Context context, final String updateUrl, final Callback callback) {
        final int curCode = getCurrentVersionCode(context);
        final String curName = getCurrentVersionName(context);
        final boolean arm64 = devicePrefersArm64();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateInfo result = UpdateInfo.from(
                        ReleaseUpdater.checkSync(curCode, curName, updateUrl, arm64));
                if (callback != null) {
                    callback.onResult(result);
                }
            }
        }, "hm-app-updater").start();
    }

    public static UpdateInfo checkSync(int currentVersionCode, String updateUrl) {
        return UpdateInfo.from(ReleaseUpdater.checkSync(currentVersionCode, "", updateUrl, true));
    }

    public static String normalizeBaseUrl(String baseUrlOrHost) {
        return ReleaseUpdater.normalizeBaseUrl(baseUrlOrHost);
    }

    public static String resolveApkUrl(String baseUrl, String rawApkUrl) {
        return ReleaseUpdater.resolveApkUrl(baseUrl, rawApkUrl);
    }

    public static void openDownloadInBrowser(Context context, String downloadUrl) {
        if (context == null || downloadUrl == null) {
            return;
        }
        String trimmed = downloadUrl.trim();
        if (trimmed.isEmpty() || !ReleaseUpdater.isHttpUrl(trimmed)) {
            return;
        }
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || host == null || host.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Toast.makeText(context, trimmed, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
