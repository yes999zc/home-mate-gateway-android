package homes.mymate.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import homes.mymate.gateway.protocol.CoreDiscovery;
import homes.mymate.gateway.protocol.DeviceRoutingPolicy;

/**
 * Persistent configuration & Multi-Core Profile store for Home Mate Android Client (v2.3.0).
 *
 * v2.3.0 upgrades:
 * - Zero hardcoded personal IPs: fresh installs start cleanly and guide users through 1-tap LAN
 *   discovery or custom Core setup.
 * - Multi-IP / Tailscale deduplication via persistent {@code coreId} + {@code alternateHosts}:
 *   one logical Core profile tracks both its RFC1918 LAN primary IP and Tailscale fallback route.
 * - Full profile CRUD (create, edit, set Bearer token, delete).
 * - Conservative 1.0x default mic gain (reduces quiet-room noise pumping into ASR).
 */
public final class GatewayConfig {

    public static final class ServerProfile {
        public final String name;
        public final String host;
        public final int gatewayPort;
        public final int webPort;
        public final String bearerToken;
        public final String coreId;
        public final List<String> alternateHosts;
        public final boolean manageOnly;

        public ServerProfile(String name, String host, int gatewayPort, int webPort) {
            this(name, host, gatewayPort, webPort, "", "", null, false);
        }

        public ServerProfile(String name, String host, int gatewayPort, int webPort, String bearerToken) {
            this(name, host, gatewayPort, webPort, bearerToken, "", null, false);
        }

        public ServerProfile(
                String name,
                String host,
                int gatewayPort,
                int webPort,
                String bearerToken,
                String coreId,
                List<String> alternateHosts) {
            this(name, host, gatewayPort, webPort, bearerToken, coreId, alternateHosts, false);
        }

        public ServerProfile(
                String name,
                String host,
                int gatewayPort,
                int webPort,
                String bearerToken,
                String coreId,
                List<String> alternateHosts,
                boolean manageOnly) {
            this.name = name != null ? name.trim() : "";
            this.host = host != null ? host.trim() : "";
            this.gatewayPort = gatewayPort > 0 ? gatewayPort : 8932;
            this.webPort = webPort > 0 ? webPort : 8930;
            this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
            this.coreId = coreId != null ? coreId.trim() : "";
            List<String> alts = new ArrayList<String>();
            if (alternateHosts != null) {
                for (String alt : alternateHosts) {
                    if (alt != null) {
                        String clean = alt.trim();
                        if (!clean.isEmpty() && !clean.equalsIgnoreCase(this.host) && !alts.contains(clean)) {
                            alts.add(clean);
                        }
                    }
                }
            }
            this.alternateHosts = Collections.unmodifiableList(alts);
            this.manageOnly = manageOnly;
        }

        public boolean matchesHost(String candidateHost) {
            if (candidateHost == null || candidateHost.trim().isEmpty()) {
                return false;
            }
            String h = candidateHost.trim();
            if (host.equalsIgnoreCase(h)) {
                return true;
            }
            for (String alt : alternateHosts) {
                if (alt.equalsIgnoreCase(h)) {
                    return true;
                }
            }
            return false;
        }

        public List<String> allCandidateHosts() {
            List<String> out = new ArrayList<String>();
            if (!host.isEmpty()) {
                out.add(host);
            }
            out.addAll(alternateHosts);
            return out;
        }

        public String serialize() {
            StringBuilder altCsv = new StringBuilder();
            for (int i = 0; i < alternateHosts.size(); i++) {
                if (i > 0) altCsv.append(',');
                altCsv.append(alternateHosts.get(i).replace("|", "").replace("\n", ""));
            }
            return name.replace("|", " ").replace("\n", " ") + "|"
                    + host + "|" + gatewayPort + "|" + webPort + "|"
                    + bearerToken.replace("|", "").replace("\n", "") + "|"
                    + coreId.replace("|", "").replace("\n", "") + "|"
                    + altCsv.toString() + "|"
                    + (manageOnly ? "1" : "0");
        }

        public static ServerProfile deserialize(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 2) {
                return null;
            }
            String name = parts[0].trim();
            String host = parts[1].trim();
            int gw = parts.length >= 3 ? parseIntSafe(parts[2], 8932) : 8932;
            int web = parts.length >= 4 ? parseIntSafe(parts[3], 8930) : 8930;
            String token = parts.length >= 5 ? parts[4].trim() : "";
            String cid = parts.length >= 6 ? parts[5].trim() : "";
            List<String> alts = new ArrayList<String>();
            if (parts.length >= 7 && !parts[6].trim().isEmpty()) {
                for (String a : parts[6].split(",")) {
                    if (!a.trim().isEmpty()) {
                        alts.add(a.trim());
                    }
                }
            }
            boolean manageOnly = parts.length >= 8
                    && ("1".equals(parts[7].trim()) || "true".equalsIgnoreCase(parts[7].trim()));
            if (host.isEmpty()) {
                return null;
            }
            return new ServerProfile(name.isEmpty() ? host : name, host, gw, web, token, cid, alts, manageOnly);
        }
    }

    private static final String PREFS_NAME = "home_mate_gateway_prefs";

    private static final String KEY_CORE_HOST = "core_host";
    private static final String KEY_CORE_PORT = "core_port";
    private static final String KEY_WEB_PORT = "web_port";
    private static final String KEY_BEARER_TOKEN = "core_bearer_token";
    private static final String KEY_MANAGE_ONLY = "core_manage_only";
    private static final String KEY_CONFIG_V2_STATE = "config_v2_state_v1";
    private static final String KEY_PROFILES = "core_profiles_v1";
    private static final String KEY_AUTO_FAILOVER = "auto_failover";
    private static final String KEY_DEVICE_ID = "device_id";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String KEY_ROOM_NAME = "room_name";
    private static final String KEY_LANGUAGE = "ui_language";
    private static final String KEY_THEME_MODE = "ui_theme_mode";
    private static final String KEY_DEVICE_PROFILE = "device_profile";
    private static final String KEY_USE_VOICE_COMM_AEC = "use_voice_comm_aec";
    private static final String KEY_SOFTWARE_AEC = "software_aec_enabled";
    private static final String KEY_SOFTWARE_AEC_V2_MIGRATED = "software_aec_v2_migrated";
    private static final String KEY_UPLINK_GAIN_V23_MIGRATED = "uplink_gain_v23_migrated";
    private static final String KEY_DESIRED_MUTED = "desired_muted";
    private static final String KEY_KWS_V232_MIGRATED = "kws_v232_migrated";
    private static final String KEY_KWS_V239_RESTORED = "kws_v239_restored";
    private static final String KEY_UPLINK_GAIN = "uplink_gain";
    private static final String KEY_KWS_THRESHOLD = "kws_threshold";
    private static final String KEY_KWS_SCORE = "kws_score";
    private static final String KEY_AUTO_START_BOOT = "auto_start_boot";
    private static final String KEY_R1_HOOK_ENABLED = "r1_hook_enabled";
    private static final String KEY_KIOSK_HOME = "kiosk_home_enabled";
    private static final String KEY_UPDATE_SERVER_URL = "update_server_url";

    private final SharedPreferences prefs;

    public GatewayConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureDefaultsInitialized();
    }

    private void ensureDefaultsInitialized() {
        SharedPreferences.Editor ed = prefs.edit();
        boolean dirty = false;

        String existingId = prefs.getString(KEY_DEVICE_ID, null);
        if (existingId == null || existingId.trim().isEmpty()) {
            String rawModel = Build.MODEL != null ? Build.MODEL : "panel";
            String slug = rawModel.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (slug.isEmpty()) {
                slug = "panel";
            }
            int rand16 = new SecureRandom().nextInt(0x10000);
            String suffix = String.format(Locale.US, "%04x", rand16);
            ed.putString(KEY_DEVICE_ID, "android-" + slug + "-" + suffix);
            dirty = true;
        }

        // v2.0 migration: reset experimental software NLMS AEC default to OFF once on upgrade from v1.x
        if (!prefs.getBoolean(KEY_SOFTWARE_AEC_V2_MIGRATED, false)) {
            ed.putBoolean(KEY_SOFTWARE_AEC, false);
            ed.putBoolean(KEY_SOFTWARE_AEC_V2_MIGRATED, true);
            dirty = true;
        }

        // v2.3 migration: relax old 1.5x default gain to 1.0x so quiet-room noise does not trigger ASR
        if (!prefs.getBoolean(KEY_UPLINK_GAIN_V23_MIGRATED, false)) {
            if (prefs.contains(KEY_UPLINK_GAIN)) {
                float curGain = prefs.getFloat(KEY_UPLINK_GAIN, 1.0f);
                if (Math.abs(curGain - 1.5f) < 0.03f) {
                    ed.putFloat(KEY_UPLINK_GAIN, 1.0f);
                }
            }
            ed.putBoolean(KEY_UPLINK_GAIN_V23_MIGRATED, true);
            dirty = true;
        }

        // v2.3.2 migration: upgrade untouched old 0.28 / 2.60 KWS defaults to mobile-tuned 0.22 / 3.00
        if (!prefs.getBoolean(KEY_KWS_V232_MIGRATED, false)) {
            if (prefs.contains(KEY_KWS_THRESHOLD) && prefs.contains(KEY_KWS_SCORE)) {
                float curThr = prefs.getFloat(KEY_KWS_THRESHOLD, 0.22f);
                float curSc = prefs.getFloat(KEY_KWS_SCORE, 3.00f);
                if (Math.abs(curThr - 0.28f) < 0.015f && Math.abs(curSc - 2.60f) < 0.05f) {
                    ed.putFloat(KEY_KWS_THRESHOLD, 0.22f);
                    ed.putFloat(KEY_KWS_SCORE, 3.00f);
                }
            }
            ed.putBoolean(KEY_KWS_V232_MIGRATED, true);
            dirty = true;
        }

        // v2.3.9 migration: restore mobile-calibrated 0.22 / 3.00 if v2.3.7/v2.3.8 persisted
        // Core's generic Dot2 7-mic default (0.32 / 2.50) onto this Android device.
        if (!prefs.getBoolean(KEY_KWS_V239_RESTORED, false)) {
            if (prefs.contains(KEY_KWS_THRESHOLD) && prefs.contains(KEY_KWS_SCORE)) {
                float curThr = prefs.getFloat(KEY_KWS_THRESHOLD, DeviceRoutingPolicy.MOBILE_CALIBRATED_KWS_THRESHOLD);
                float curSc = prefs.getFloat(KEY_KWS_SCORE, DeviceRoutingPolicy.MOBILE_CALIBRATED_KWS_SCORE);
                float[] migrated = DeviceRoutingPolicy.migrateClobberedDot2KwsSensitivity(curThr, curSc);
                if (migrated[0] != curThr || migrated[1] != curSc) {
                    ed.putFloat(KEY_KWS_THRESHOLD, migrated[0]);
                    ed.putFloat(KEY_KWS_SCORE, migrated[1]);
                }
            }
            ed.putBoolean(KEY_KWS_V239_RESTORED, true);
            dirty = true;
        }

        if (dirty) {
            ed.apply();
        }
    }

    public boolean hasConfiguredCore() {
        return !getCoreHost().isEmpty();
    }

    public String getCoreHost() {
        String h = prefs.getString(KEY_CORE_HOST, "");
        return h != null ? h.trim() : "";
    }

    public int getCorePort() {
        return prefs.getInt(KEY_CORE_PORT, 8932);
    }

    public int getWebPort() {
        return prefs.getInt(KEY_WEB_PORT, 8930);
    }

    public String getBearerToken() {
        String activeHost = getCoreHost();
        if (!activeHost.isEmpty()) {
            for (ServerProfile p : getServerProfiles()) {
                if (p.matchesHost(activeHost) && !p.bearerToken.isEmpty()) {
                    return p.bearerToken;
                }
            }
        }
        String tok = prefs.getString(KEY_BEARER_TOKEN, "");
        return tok != null ? tok.trim() : "";
    }

    public void setBearerToken(String token) {
        String clean = token != null ? token.trim() : "";
        prefs.edit().putString(KEY_BEARER_TOKEN, clean).apply();
        String activeHost = getCoreHost();
        if (activeHost.isEmpty()) {
            return;
        }
        List<ServerProfile> list = getServerProfiles();
        boolean changed = false;
        List<ServerProfile> updated = new ArrayList<ServerProfile>();
        for (ServerProfile p : list) {
            if (p.matchesHost(activeHost)) {
                updated.add(new ServerProfile(p.name, p.host, p.gatewayPort, p.webPort, clean, p.coreId, p.alternateHosts, p.manageOnly));
                changed = true;
            } else {
                updated.add(p);
            }
        }
        if (changed) {
            saveServerProfiles(updated);
        }
    }

    /**
     * Returns whether the currently active Core profile is explicitly configured as Manage-Only
     * (HTTP :8930 only, no Northbound :8932 Voice Gateway). Defaults to {@code false} (Voice Gateway).
     */
    public boolean isManageOnly() {
        String activeHost = getCoreHost();
        if (!activeHost.isEmpty()) {
            for (ServerProfile p : getServerProfiles()) {
                if (p.matchesHost(activeHost)) {
                    return p.manageOnly;
                }
            }
        }
        return prefs.getBoolean(KEY_MANAGE_ONLY, false);
    }

    public void setManageOnly(boolean manageOnly) {
        prefs.edit().putBoolean(KEY_MANAGE_ONLY, manageOnly).apply();
        String activeHost = getCoreHost();
        if (activeHost.isEmpty()) {
            return;
        }
        List<ServerProfile> list = getServerProfiles();
        List<ServerProfile> updated = new ArrayList<ServerProfile>();
        boolean changed = false;
        for (ServerProfile p : list) {
            if (p.matchesHost(activeHost)) {
                updated.add(new ServerProfile(
                        p.name, p.host, p.gatewayPort, p.webPort,
                        p.bearerToken, p.coreId, p.alternateHosts, manageOnly));
                changed = true;
            } else {
                updated.add(p);
            }
        }
        if (changed) {
            saveServerProfiles(updated);
        }
    }

    private String activeConfigV2StatePrefKey() {
        String cid = getActiveCoreId();
        if (cid != null && !cid.isEmpty()) {
            return KEY_CONFIG_V2_STATE + "_id_" + cid.toLowerCase(Locale.US);
        }
        String host = getCoreHost();
        if (host == null || host.isEmpty()) {
            return KEY_CONFIG_V2_STATE;
        }
        return KEY_CONFIG_V2_STATE + "_host_" + host.toLowerCase(Locale.US) + "_" + getCorePort();
    }

    public String getConfigV2StateJson() {
        String raw = prefs.getString(activeConfigV2StatePrefKey(), "");
        return raw != null ? raw : "";
    }

    public void setConfigV2StateJson(String json) {
        prefs.edit().putString(activeConfigV2StatePrefKey(), json != null ? json : "").apply();
    }

    public boolean isAutoFailoverEnabled() {
        return prefs.getBoolean(KEY_AUTO_FAILOVER, true);
    }

    public void setAutoFailoverEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_FAILOVER, enabled).apply();
    }

    public boolean isDesiredMuted() {
        return prefs.getBoolean(KEY_DESIRED_MUTED, false);
    }

    public void setDesiredMuted(boolean muted) {
        prefs.edit().putBoolean(KEY_DESIRED_MUTED, muted).apply();
    }

    public List<ServerProfile> getServerProfiles() {
        String raw = prefs.getString(KEY_PROFILES, "");
        List<ServerProfile> out = new ArrayList<ServerProfile>();
        if (raw != null && !raw.trim().isEmpty()) {
            String[] lines = raw.split("\\n");
            for (String line : lines) {
                ServerProfile p = ServerProfile.deserialize(line);
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    public void saveServerProfiles(List<ServerProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (ServerProfile p : profiles) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p.serialize());
        }
        prefs.edit().putString(KEY_PROFILES, sb.toString()).apply();
    }

    public void upsertProfileAndSelect(String name, String host, int gwPort, int webPort) {
        upsertProfileAndSelect(name, host, gwPort, webPort, null, null, null);
    }

    public void upsertProfileAndSelect(String name, String host, int gwPort, int webPort, String bearerToken) {
        upsertProfileAndSelect(name, host, gwPort, webPort, bearerToken, null, null);
    }

    public void upsertProfileAndSelect(
            String name,
            String host,
            int gwPort,
            int webPort,
            String bearerToken,
            String coreId,
            List<String> alternateHosts) {
        upsertProfileAndSelect(name, host, gwPort, webPort, bearerToken, coreId, alternateHosts, null);
    }

    public void upsertProfileAndSelect(
            String name,
            String host,
            int gwPort,
            int webPort,
            String bearerToken,
            String coreId,
            List<String> alternateHosts,
            Boolean manageOnly) {
        String cleanHost = host != null ? host.trim() : "";
        if (cleanHost.isEmpty()) {
            return;
        }
        String cleanCoreId = coreId != null ? coreId.trim() : "";
        List<ServerProfile> list = getServerProfiles();
        List<ServerProfile> updated = new ArrayList<ServerProfile>();
        boolean replaced = false;
        String resolvedToken = bearerToken != null ? bearerToken.trim() : "";
        boolean resolvedManageOnly = manageOnly != null && manageOnly.booleanValue();
        for (ServerProfile p : list) {
            // Security (P0-1): NEVER match an existing profile solely by unauthenticated core_id,
            // which would inherit its saved bearerToken onto an untrusted host.
            boolean sameHost = p.matchesHost(cleanHost);
            if (!replaced && sameHost) {
                String tok = bearerToken != null ? bearerToken.trim() : p.bearerToken;
                resolvedToken = tok;
                resolvedManageOnly = manageOnly != null ? manageOnly.booleanValue() : p.manageOnly;
                String finalCid = (!cleanCoreId.isEmpty() && p.coreId.isEmpty() && p.bearerToken.isEmpty())
                        ? cleanCoreId
                        : p.coreId;
                List<String> mergedAlts = mergeAltHosts(cleanHost, p.host, p.alternateHosts, alternateHosts);
                updated.add(new ServerProfile(
                        name != null && !name.trim().isEmpty() ? name.trim() : p.name,
                        cleanHost, gwPort, webPort, tok, finalCid, mergedAlts, resolvedManageOnly));
                replaced = true;
            } else if (!sameHost) {
                updated.add(p);
            }
        }
        if (!replaced) {
            updated.add(new ServerProfile(
                    name != null && !name.trim().isEmpty() ? name.trim() : cleanHost,
                    cleanHost, gwPort, webPort, resolvedToken, cleanCoreId, alternateHosts, resolvedManageOnly));
        }
        saveServerProfiles(updated);
        prefs.edit()
                .putString(KEY_CORE_HOST, cleanHost)
                .putInt(KEY_CORE_PORT, gwPort > 0 ? gwPort : 8932)
                .putInt(KEY_WEB_PORT, webPort > 0 ? webPort : 8930)
                .putString(KEY_BEARER_TOKEN, resolvedToken)
                .putBoolean(KEY_MANAGE_ONLY, resolvedManageOnly)
                .apply();
    }

    /**
     * Updates an existing server profile (supports renaming, changing IP/host, ports, Bearer token, or mode).
     */
    public void updateServerProfile(
            String originalHost,
            String newName,
            String newHost,
            int gwPort,
            int webPort,
            String bearerToken) {
        updateServerProfile(originalHost, newName, newHost, gwPort, webPort, bearerToken, null);
    }

    public void updateServerProfile(
            String originalHost,
            String newName,
            String newHost,
            int gwPort,
            int webPort,
            String bearerToken,
            Boolean manageOnly) {
        String cleanNewHost = newHost != null ? newHost.trim() : "";
        if (cleanNewHost.isEmpty()) {
            return;
        }
        String cleanTok = bearerToken != null ? bearerToken.trim() : "";
        List<ServerProfile> list = getServerProfiles();
        List<ServerProfile> updated = new ArrayList<ServerProfile>();
        String activeHost = getCoreHost();
        boolean wasActive = originalHost != null && originalHost.trim().equalsIgnoreCase(activeHost);
        boolean found = false;
        boolean resolvedManageOnly = manageOnly != null && manageOnly.booleanValue();
        for (ServerProfile p : list) {
            if (!found && originalHost != null && p.matchesHost(originalHost.trim())) {
                if (p.matchesHost(activeHost)) {
                    wasActive = true;
                }
                resolvedManageOnly = manageOnly != null ? manageOnly.booleanValue() : p.manageOnly;
                boolean hostChanged = !p.matchesHost(cleanNewHost);
                String nextCoreId = hostChanged ? "" : p.coreId;
                List<String> nextAltHosts = hostChanged ? null : p.alternateHosts;
                updated.add(new ServerProfile(
                        newName != null && !newName.trim().isEmpty() ? newName.trim() : cleanNewHost,
                        cleanNewHost, gwPort, webPort, cleanTok, nextCoreId, nextAltHosts, resolvedManageOnly));
                found = true;
            } else if (!p.host.equalsIgnoreCase(cleanNewHost)) {
                updated.add(p);
            }
        }
        if (!found) {
            updated.add(new ServerProfile(
                    newName != null && !newName.trim().isEmpty() ? newName.trim() : cleanNewHost,
                    cleanNewHost, gwPort, webPort, cleanTok, "", null, resolvedManageOnly));
            wasActive = true;
        }
        saveServerProfiles(updated);
        if (wasActive || !hasConfiguredCore()) {
            prefs.edit()
                    .putString(KEY_CORE_HOST, cleanNewHost)
                    .putInt(KEY_CORE_PORT, gwPort > 0 ? gwPort : 8932)
                    .putInt(KEY_WEB_PORT, webPort > 0 ? webPort : 8930)
                    .putString(KEY_BEARER_TOKEN, cleanTok)
                    .putBoolean(KEY_MANAGE_ONLY, resolvedManageOnly)
                    .apply();
        }
    }

    public boolean deleteServerProfile(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String target = host.trim();
        String activeHost = getCoreHost();
        List<ServerProfile> list = getServerProfiles();
        List<ServerProfile> remaining = new ArrayList<ServerProfile>();
        boolean removed = false;
        boolean removedWasActive = target.equalsIgnoreCase(activeHost);
        for (ServerProfile p : list) {
            if (p.matchesHost(target)) {
                removed = true;
                if (p.matchesHost(activeHost)) {
                    removedWasActive = true;
                }
            } else {
                remaining.add(p);
            }
        }
        if (!removed) {
            return false;
        }
        saveServerProfiles(remaining);
        if (removedWasActive) {
            if (!remaining.isEmpty()) {
                ServerProfile next = remaining.get(0);
                prefs.edit()
                        .putString(KEY_CORE_HOST, next.host)
                        .putInt(KEY_CORE_PORT, next.gatewayPort)
                        .putInt(KEY_WEB_PORT, next.webPort)
                        .putString(KEY_BEARER_TOKEN, next.bearerToken)
                        .putBoolean(KEY_MANAGE_ONLY, next.manageOnly)
                        .apply();
            } else {
                prefs.edit()
                        .putString(KEY_CORE_HOST, "")
                        .putString(KEY_BEARER_TOKEN, "")
                        .putBoolean(KEY_MANAGE_ONLY, false)
                        .apply();
            }
        }
        return true;
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "android-panel-1");
    }

    public String getDeviceModel() {
        String brand = Build.BRAND != null ? Build.BRAND.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Android-Panel";
        if (!brand.isEmpty() && !model.toLowerCase(Locale.US).startsWith(brand.toLowerCase(Locale.US))) {
            return brand + "-" + model;
        }
        return model;
    }

    public String getRoomName() {
        return prefs.getString(KEY_ROOM_NAME, "客厅中控屏");
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, I18n.defaultLanguage());
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, I18n.LANG_EN.equalsIgnoreCase(lang) ? I18n.LANG_EN : I18n.LANG_ZH).apply();
    }

    public String getThemeMode() {
        String m = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM);
        if (THEME_LIGHT.equalsIgnoreCase(m)) return THEME_LIGHT;
        if (THEME_DARK.equalsIgnoreCase(m)) return THEME_DARK;
        return THEME_SYSTEM;
    }

    public void setThemeMode(String mode) {
        String clean = THEME_SYSTEM;
        if (THEME_LIGHT.equalsIgnoreCase(mode)) clean = THEME_LIGHT;
        else if (THEME_DARK.equalsIgnoreCase(mode)) clean = THEME_DARK;
        prefs.edit().putString(KEY_THEME_MODE, clean).apply();
    }

    public String getBearerTokenForHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        String h = host.trim();
        for (ServerProfile p : getServerProfiles()) {
            if (p.matchesHost(h) && !p.bearerToken.isEmpty()) {
                return p.bearerToken;
            }
        }
        if (h.equalsIgnoreCase(getCoreHost())) {
            String tok = prefs.getString(KEY_BEARER_TOKEN, "");
            return tok != null ? tok.trim() : "";
        }
        return "";
    }

    public String getActiveCoreId() {
        String activeHost = getCoreHost();
        if (activeHost == null || activeHost.trim().isEmpty()) {
            return "";
        }
        for (ServerProfile p : getServerProfiles()) {
            if (p.matchesHost(activeHost) && p.coreId != null && !p.coreId.isEmpty()) {
                return p.coreId;
            }
        }
        return "";
    }

    public boolean activeProfileMatchesHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String h = host.trim();
        String activeHost = getCoreHost();
        if (h.equalsIgnoreCase(activeHost)) {
            return true;
        }
        for (ServerProfile p : getServerProfiles()) {
            if (p.matchesHost(activeHost) && p.matchesHost(h)) {
                return true;
            }
        }
        return false;
    }

    public void setProfileBearerToken(String host, String token) {
        setProfileBearerToken(host, token, null);
    }

    public void setProfileBearerToken(String host, String token, String verifiedCoreId) {
        if (host == null || host.trim().isEmpty()) {
            return;
        }
        String h = host.trim();
        String clean = token != null ? token.trim() : "";
        String cleanCid = verifiedCoreId != null ? verifiedCoreId.trim() : "";
        List<ServerProfile> list = getServerProfiles();
        List<ServerProfile> updated = new ArrayList<ServerProfile>();
        boolean found = false;
        boolean matchesActive = h.equalsIgnoreCase(getCoreHost());
        for (ServerProfile p : list) {
            if (p.matchesHost(h)) {
                String pinnedCid = DeviceRoutingPolicy.resolvePinnedCoreId(p.coreId, cleanCid);
                updated.add(new ServerProfile(p.name, p.host, p.gatewayPort, p.webPort, clean, pinnedCid, p.alternateHosts, p.manageOnly));
                if (p.matchesHost(getCoreHost())) {
                    matchesActive = true;
                }
                found = true;
            } else {
                updated.add(p);
            }
        }
        if (!found) {
            updated.add(new ServerProfile(h, h, 8932, 8930, clean, cleanCid, null, false));
        }
        saveServerProfiles(updated);
        if (matchesActive) {
            prefs.edit().putString(KEY_BEARER_TOKEN, clean).apply();
        }
    }

    /**
     * Merges auto-discovered LAN Cores into saved profiles with strict credential isolation (P0-1):
     * - NEVER attaches an existing profile's {@code bearerToken} to a newly discovered host, and
     *   NEVER overwrites {@code primaryHost}, {@code gatewayPort}, {@code webPort}, or {@code alternateHosts}
     *   of a paired profile solely via an unauthenticated {@code core_id} claim.
     * - Only updates an existing profile on exact host match (filling an empty {@code coreId} on first sight).
     * - Newly discovered hosts are added as unauthenticated profiles ({@code bearerToken = ""}) so the user
     *   explicitly confirms/pairs any new route before credentials are ever transmitted to it.
     */
    public synchronized int mergeDiscoveredCores(List<CoreDiscovery.DiscoveredCore> cores) {
        if (cores == null || cores.isEmpty()) {
            return 0;
        }
        // Security (P0-1): deduplicate discovered candidates strictly by exact host (NOT by
        // unauthenticated core_id) so a rogue LAN beacon claiming a real Tailscale Core's core_id
        // can never absorb or poison that Core's primaryHost or alternateHosts.
        List<CoreDiscovery.DiscoveredCore> deduped = new ArrayList<CoreDiscovery.DiscoveredCore>();
        java.util.Set<String> seenHosts = new java.util.LinkedHashSet<String>();
        for (CoreDiscovery.DiscoveredCore c : cores) {
            if (c == null || c.host == null || c.host.trim().isEmpty()) {
                continue;
            }
            String key = c.host.trim().toLowerCase(java.util.Locale.US);
            if (seenHosts.add(key)) {
                deduped.add(c);
            }
        }
        List<ServerProfile> merged = new ArrayList<ServerProfile>(getServerProfiles());
        int added = 0;
        boolean changed = false;

        for (CoreDiscovery.DiscoveredCore c : deduped) {
            String discoveredHost = c.host.trim();
            int hostMatchIdx = -1;
            for (int i = 0; i < merged.size(); i++) {
                ServerProfile p = merged.get(i);
                if (p.matchesHost(discoveredHost)) {
                    hostMatchIdx = i;
                    break;
                }
            }
            if (hostMatchIdx < 0) {
                // Never merge an unauthenticated host into an existing paired profile by core_id alone,
                // and never inherit another profile's bearerToken.
                merged.add(new ServerProfile(
                        c.displayLabel(), discoveredHost, c.gatewayPort, c.webPort,
                        "", c.coreId, Collections.<String>emptyList(), !c.voiceGatewayOpen));
                added++;
                changed = true;
            } else {
                ServerProfile existing = merged.get(hostMatchIdx);
                // Exact host match: preserve primaryHost, ports, alternateHosts, bearerToken, and manageOnly.
                // NEVER pin an unauthenticated core_id onto an already-paired profile; only fill
                // coreId on an unpaired profile whose coreId was previously unknown.
                String finalCoreId = (existing.coreId.isEmpty() && existing.bearerToken.isEmpty() && !c.coreId.isEmpty())
                        ? c.coreId
                        : existing.coreId;
                if (!finalCoreId.equals(existing.coreId)) {
                    merged.set(hostMatchIdx, new ServerProfile(
                            existing.name, existing.host, existing.gatewayPort, existing.webPort,
                            existing.bearerToken, finalCoreId, existing.alternateHosts, existing.manageOnly));
                    changed = true;
                }
            }
        }
        if (changed) {
            saveServerProfiles(merged);
        }
        // First-launch onboarding: if no active Core is configured yet, auto-bind the best discovered Core
        if (!hasConfiguredCore() && !deduped.isEmpty()) {
            CoreDiscovery.DiscoveredCore best = deduped.get(0);
            prefs.edit()
                    .putString(KEY_CORE_HOST, best.host)
                    .putInt(KEY_CORE_PORT, best.gatewayPort)
                    .putInt(KEY_WEB_PORT, best.webPort)
                    .putString(KEY_BEARER_TOKEN, getBearerTokenForHost(best.host))
                    .putBoolean(KEY_MANAGE_ONLY, !best.voiceGatewayOpen)
                    .apply();
        }
        return added;
    }

    private static List<String> mergeAltHosts(
            String primaryHost,
            String secondaryHost,
            List<String> a,
            List<String> b) {
        List<String> out = new ArrayList<String>();
        addAlt(out, primaryHost, secondaryHost);
        if (a != null) {
            for (String s : a) addAlt(out, primaryHost, s);
        }
        if (b != null) {
            for (String s : b) addAlt(out, primaryHost, s);
        }
        return out;
    }

    private static void addAlt(List<String> out, String primaryHost, String candidate) {
        if (candidate == null) return;
        String clean = candidate.trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase(primaryHost)) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(clean)) return;
        }
        out.add(clean);
    }

    public String getDeviceProfileId() {
        return prefs.getString(KEY_DEVICE_PROFILE, DeviceProfile.FormFactor.AUTO.id);
    }

    public void setDeviceProfileId(String profileId) {
        prefs.edit().putString(KEY_DEVICE_PROFILE, DeviceProfile.FormFactor.fromId(profileId).id).apply();
    }

    public boolean isUseVoiceCommAec() {
        return prefs.getBoolean(KEY_USE_VOICE_COMM_AEC, true);
    }

    public boolean isSoftwareAecEnabled() {
        return prefs.getBoolean(KEY_SOFTWARE_AEC, false);
    }

    public float getUplinkGain() {
        return prefs.getFloat(KEY_UPLINK_GAIN, 1.0f);
    }

    public float getKwsThreshold() {
        return prefs.getFloat(KEY_KWS_THRESHOLD, 0.22f);
    }

    public float getKwsScore() {
        return prefs.getFloat(KEY_KWS_SCORE, 3.00f);
    }

    public boolean isAutoStartOnBoot() {
        return prefs.getBoolean(KEY_AUTO_START_BOOT, true);
    }

    public void setAutoStartOnBoot(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START_BOOT, enabled).apply();
    }

    public boolean isR1HookEnabled() {
        return prefs.getBoolean(KEY_R1_HOOK_ENABLED, true);
    }

    public boolean isKioskHomeEnabled() {
        return prefs.getBoolean(KEY_KIOSK_HOME, false);
    }

    public void setKioskHomeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KIOSK_HOME, enabled).apply();
    }

    public String getUpdateServerUrl() {
        String custom = prefs.getString(KEY_UPDATE_SERVER_URL, "");
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        return homes.mymate.gateway.protocol.ReleaseUpdater.DEFAULT_GITHUB_RELEASES_API;
    }

    public void setUpdateServerUrl(String url) {
        prefs.edit().putString(KEY_UPDATE_SERVER_URL, url != null ? url.trim() : "").apply();
    }

    public String getWebConsoleUrl() {
        String host = getCoreHost();
        if (host.isEmpty()) {
            return "";
        }
        return "http://" + host + ":" + getWebPort() + "/";
    }

    public void saveAcoustics(boolean hwAec, boolean swAec, float uplinkGain, float kwsThr, float kwsScore) {
        prefs.edit()
                .putBoolean(KEY_USE_VOICE_COMM_AEC, hwAec)
                .putBoolean(KEY_SOFTWARE_AEC, swAec)
                .putFloat(KEY_UPLINK_GAIN, Math.max(0.5f, Math.min(6.0f, uplinkGain)))
                .putFloat(KEY_KWS_THRESHOLD, Math.max(0.10f, Math.min(0.80f, kwsThr)))
                .putFloat(KEY_KWS_SCORE, Math.max(0.50f, Math.min(5.00f, kwsScore)))
                .apply();
    }

    public void saveIdentity(String deviceId, String roomName) {
        prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId != null && !deviceId.trim().isEmpty() ? deviceId.trim() : getDeviceId())
                .putString(KEY_ROOM_NAME, roomName != null && !roomName.trim().isEmpty() ? roomName.trim() : getRoomName())
                .apply();
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
