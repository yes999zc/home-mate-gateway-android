package homes.mymate.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import homes.mymate.gateway.protocol.LocalKwsEngine;
import homes.mymate.gateway.protocol.NorthboundSession;

/**
 * Unpacks bundled Sherpa-ONNX WenetSpeech Streaming Zipformer int8 KWS model assets
 * ({@code assets/kws/model/*}) into app-private storage cleanly on a background thread.
 *
 * v2.0 hardening:
 * - Atomic copy via {@code .tmp} + {@code renameTo} with byte-length verification against
 *   {@link AssetFileDescriptor#getLength()} so interrupted installs never leave truncated ONNX files.
 * - Versioned {@code kw.txt} upgrade: tracks {@code kws_bundled_hash} in prefs so upgrading the APK
 *   automatically refreshes bundled defaults (e.g. adding "退下吧") while preserving Core-deployed tables.
 */
public final class KwsAssetInstaller {

    private static final String TAG = "HomeMateKwsInstall";
    private static final String PREFS_NAME = "home_mate_kws_assets";
    private static final String KEY_BUNDLED_KW_HASH = "kws_bundled_hash";

    private static final String[] MODEL_FILES = new String[]{
            "tokens.txt",
            "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
    };

    private static final String[][] LEGACY_SIDECAR_SYMLINKS = new String[][]{
            {"libhm_ld_linux.so", "ld-linux-aarch64.so.1"},
            {"libhm_kws_driver.so", "hm-kws"},
            {"libhm_libc.so", "libc.so.6"},
            {"libhm_libm.so", "libm.so.6"},
            {"libhm_libstdc++.so", "libstdc++.so.6"},
            {"libhm_libgcc_s.so", "libgcc_s.so.1"},
            {"libhm_libpthread.so", "libpthread.so.0"},
            {"libhm_libdl.so", "libdl.so.2"},
            {"libhm_librt.so", "librt.so.1"},
            {"libsherpa-onnx-c-api.so", "libsherpa-onnx-c-api.so"},
            {"libonnxruntime.so", "libonnxruntime.so"}
    };

    private KwsAssetInstaller() {
    }

    public static File getKwsWorkDir(Context context) {
        return new File(context.getFilesDir(), "kws");
    }

    public static File prepareKwsWorkspace(Context context) {
        return prepareKwsWorkspace(context, null);
    }

    public static File prepareKwsWorkspace(Context context, LocalKwsEngine engine) {
        File kwsDir = getKwsWorkDir(context);
        File modelDir = new File(kwsDir, "model");
        File libDir = new File(kwsDir, "lib");
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
        if (!libDir.exists()) {
            libDir.mkdirs();
        }

        AssetManager am = context.getAssets();
        for (String modelName : MODEL_FILES) {
            String assetPath = "kws/model/" + modelName;
            File target = new File(modelDir, modelName);
            long expectedLen = getAssetLength(am, assetPath);
            if (target.exists() && target.length() > 0 && (expectedLen < 0 || target.length() == expectedLen)) {
                continue;
            }
            copyAssetAtomically(am, assetPath, target, expectedLen);
        }

        syncDefaultKeywordsFile(context, new File(kwsDir, "kw.txt"), engine);

        String nativeDirPath = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDirPath != null) {
            File nativeDir = new File(nativeDirPath);
            for (String[] pair : LEGACY_SIDECAR_SYMLINKS) {
                File src = new File(nativeDir, pair[0]);
                File dst = new File(libDir, pair[1]);
                if (src.exists()) {
                    try {
                        if (dst.exists()) {
                            dst.delete();
                        }
                        Os.symlink(src.getAbsolutePath(), dst.getAbsolutePath());
                    } catch (Throwable t) {
                        Log.w(TAG, "symlink " + pair[1] + " skipped: " + t.getMessage());
                    }
                }
            }
        }

        return kwsDir;
    }

    private static void syncDefaultKeywordsFile(Context context, File kwFile, LocalKwsEngine engine) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String prevDefaultHash = prefs.getString(KEY_BUNDLED_KW_HASH, "");

        if (engine != null) {
            String writtenHash = engine.syncDefaultKeywordsIfNotCustom(prevDefaultHash);
            if (writtenHash != null && !writtenHash.isEmpty()) {
                prefs.edit().putString(KEY_BUNDLED_KW_HASH, writtenHash).apply();
            }
            return;
        }

        File customMarker = new File(kwFile.getParentFile(), ".kw.custom");
        if (customMarker.exists()) {
            return;
        }
        String newDefaultHash = NorthboundSession.fnv1a64Hex(LocalKwsEngine.DEFAULT_KEYWORDS_TXT);
        boolean shouldWrite = false;
        if (!kwFile.exists() || kwFile.length() == 0) {
            shouldWrite = true;
        } else if (!prevDefaultHash.isEmpty() && !newDefaultHash.equals(prevDefaultHash)) {
            // Upgrade bundled kw.txt ONLY if the current on-disk file matches a previously recorded
            // bundled default hash; NEVER overwrite an unknown or Core-deployed kw.txt!
            String currentDiskHash = readFileHash(kwFile);
            if (prevDefaultHash.equals(currentDiskHash)) {
                shouldWrite = true;
            }
        }

        if (shouldWrite) {
            File tmp = new File(kwFile.getParentFile(), "kw.default.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(LocalKwsEngine.DEFAULT_KEYWORDS_TXT.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            } catch (IOException e) {
                Log.w(TAG, "Failed writing default kw.txt: " + e.getMessage());
                tmp.delete();
                return;
            }
            if (kwFile.exists()) {
                kwFile.delete();
            }
            if (!tmp.renameTo(kwFile)) {
                tmp.delete();
                return;
            }
            prefs.edit().putString(KEY_BUNDLED_KW_HASH, newDefaultHash).apply();
        }
    }

    private static String readFileHash(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 131072L)];
            int off = 0;
            int n;
            while (off < data.length && (n = fis.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            return NorthboundSession.fnv1a64Hex(new String(data, 0, off, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return "";
        }
    }

    private static long getAssetLength(AssetManager am, String assetPath) {
        try (AssetFileDescriptor afd = am.openFd(assetPath)) {
            return afd.getLength();
        } catch (IOException ignored) {
            return -1L; // Compressed asset; length unknown upfront
        }
    }

    private static void copyAssetAtomically(AssetManager am, String assetPath, File dest, long expectedLen) {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        long copied = 0L;
        try (InputStream is = am.open(assetPath);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                copied += n;
            }
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            Log.w(TAG, "copyAssetAtomically failed for " + assetPath + ": " + e.getMessage());
            tmp.delete();
            return;
        }
        if (copied <= 0 || (expectedLen > 0 && copied != expectedLen)) {
            Log.w(TAG, "Asset length mismatch for " + assetPath + ": copied=" + copied + " expected=" + expectedLen);
            tmp.delete();
            return;
        }
        if (dest.exists()) {
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            Log.w(TAG, "renameTo failed for " + dest.getName());
            tmp.delete();
        }
    }
}
