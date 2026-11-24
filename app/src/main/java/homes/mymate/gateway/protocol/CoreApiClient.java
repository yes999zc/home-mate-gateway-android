package homes.mymate.gateway.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java HTTP Management & Whole-Home Telemetry Client for {@code hm-core} (/api/v0/*).
 *
 * v2.2.0 upgrades:
 * - Whole-home {@link RoomNodeSummary} fetcher for multi-room management cards across all LAN Cores.
 * - Direct per-room remote device controls: {@code stop}, {@code program/pause}, {@code program/resume},
 *   {@code volume}, and {@code chat} (with per-Core Bearer token support).
 * - Manage-Only media fallback: detects active media playback on a Core's primary speaker device even
 *   when the Android client's own device_id is not attached to :8932.
 */
public final class CoreApiClient {

    public static final class DeviceInfo {
        public final String id;
        public final String model;
        public final boolean connected;
        public final boolean aecEnabled;
        public final String audioProfile;

        public DeviceInfo(String id, String model, boolean connected, boolean aecEnabled, String audioProfile) {
            this.id = id;
            this.model = model;
            this.connected = connected;
            this.aecEnabled = aecEnabled;
            this.audioProfile = audioProfile;
        }
    }

    public static final class FleetNodeInfo {
        public final String id;
        public final String url;
        public final boolean healthy;

        public FleetNodeInfo(String id, String url, boolean healthy) {
            this.id = id;
            this.url = url;
            this.healthy = healthy;
        }
    }

    public static final class RoomNodeSummary {
        public final String host;
        public final int webPort;
        public final int gatewayPort;
        public final String displayName;
        public final String assistantName;
        public final boolean online;
        public final boolean authRequired;
        public final boolean voiceGatewayOpen;
        public final boolean musicActive;
        public final String musicTitle;
        public final String musicSource;
        public final String duplexMode;
        public final String activeDeviceId;
        public final int deviceCount;
        public final String error;
        public final String coreId;
        public final boolean lanGatewayExempt;

        public RoomNodeSummary(
                String host,
                int webPort,
                int gatewayPort,
                String displayName,
                String assistantName,
                boolean online,
                boolean authRequired,
                boolean voiceGatewayOpen,
                boolean musicActive,
                String musicTitle,
                String musicSource,
                String duplexMode,
                String activeDeviceId,
                int deviceCount,
                String error,
                String coreId,
                boolean lanGatewayExempt) {
            this.host = host;
            this.webPort = webPort;
            this.gatewayPort = gatewayPort;
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : host;
            this.assistantName = assistantName != null ? assistantName : "Home Mate";
            this.online = online;
            this.authRequired = authRequired;
            this.voiceGatewayOpen = voiceGatewayOpen;
            this.musicActive = musicActive;
            this.musicTitle = musicTitle != null ? musicTitle : "";
            this.musicSource = musicSource != null ? musicSource : "";
            this.duplexMode = duplexMode != null ? duplexMode : "half";
            this.activeDeviceId = activeDeviceId != null ? activeDeviceId : "";
            this.deviceCount = deviceCount;
            this.error = error;
            this.coreId = coreId != null ? coreId.trim() : "";
            this.lanGatewayExempt = lanGatewayExempt;
        }

        public RoomNodeSummary(
                String host,
                int webPort,
                int gatewayPort,
                String displayName,
                String assistantName,
                boolean online,
                boolean authRequired,
                boolean voiceGatewayOpen,
                boolean musicActive,
                String musicTitle,
                String musicSource,
                String duplexMode,
                String activeDeviceId,
                int deviceCount,
                String error) {
            this(host, webPort, gatewayPort, displayName, assistantName, online, authRequired,
                    voiceGatewayOpen, musicActive, musicTitle, musicSource, duplexMode,
                    activeDeviceId, deviceCount, error, "", false);
        }
    }

    public static final class ChatEntry {
        public final String role; // "user" | "assistant"
        public final String text;
        public final long tsMs;

        public ChatEntry(String role, String text, long tsMs) {
            this.role = role;
            this.text = text;
            this.tsMs = tsMs;
        }
    }

    public static final class ModeState {
        public final String duplex;
        public final boolean bargeIn;
        public final boolean wakeAck;
        public final String wakeArbitration;
        public final boolean musicActive;
        public final String musicTitle;
        public final String musicSource;

        public ModeState(
                String duplex,
                boolean bargeIn,
                boolean wakeAck,
                String wakeArbitration,
                boolean musicActive,
                String musicTitle,
                String musicSource) {
            this.duplex = duplex;
            this.bargeIn = bargeIn;
            this.wakeAck = wakeAck;
            this.wakeArbitration = wakeArbitration;
            this.musicActive = musicActive;
            this.musicTitle = musicTitle != null ? musicTitle : "";
            this.musicSource = musicSource != null ? musicSource : "";
        }
    }

    public static final class CoreSnapshot {
        public final boolean reachable;
        public final boolean authRequired;
        public final String assistantName;
        public final String buildId;
        public final String duplexMode;
        public final boolean bargeIn;
        public final boolean wakeAckEnabled;
        public final String wakeArbitration;
        public final boolean musicActive;
        public final String musicTitle;
        public final String musicSource;
        public final List<DeviceInfo> devices;
        public final List<FleetNodeInfo> fleetNodes;
        public final List<ChatEntry> transcript;
        public final String error;

        public CoreSnapshot(
                boolean reachable,
                boolean authRequired,
                String assistantName,
                String buildId,
                String duplexMode,
                boolean bargeIn,
                boolean wakeAckEnabled,
                String wakeArbitration,
                boolean musicActive,
                String musicTitle,
                String musicSource,
                List<DeviceInfo> devices,
                List<FleetNodeInfo> fleetNodes,
                List<ChatEntry> transcript,
                String error) {
            this.reachable = reachable;
            this.authRequired = authRequired;
            this.assistantName = assistantName;
            this.buildId = buildId;
            this.duplexMode = duplexMode;
            this.bargeIn = bargeIn;
            this.wakeAckEnabled = wakeAckEnabled;
            this.wakeArbitration = wakeArbitration;
            this.musicActive = musicActive;
            this.musicTitle = musicTitle;
            this.musicSource = musicSource;
            this.devices = devices;
            this.fleetNodes = fleetNodes;
            this.transcript = transcript;
            this.error = error;
        }

        public static CoreSnapshot offline(String err) {
            return new CoreSnapshot(
                    false, false, "Home Mate", "", "half", false, true, "off",
                    false, "", "",
                    Collections.<DeviceInfo>emptyList(),
                    Collections.<FleetNodeInfo>emptyList(),
                    Collections.<ChatEntry>emptyList(),
                    err);
        }

        public static CoreSnapshot unauthorized() {
            return new CoreSnapshot(
                    true, true, "Home Mate", "", "half", false, true, "off",
                    false, "", "",
                    Collections.<DeviceInfo>emptyList(),
                    Collections.<FleetNodeInfo>emptyList(),
                    Collections.<ChatEntry>emptyList(),
                    "HTTP 401 Unauthorized");
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private CoreApiClient() {
    }

    public static boolean probeCoreHttp(String host, int webPort, int timeoutMs) {
        return probeCoreHttp(host, webPort, null, timeoutMs);
    }

    /**
     * Auth-aware Core reachability probe: queries /api/v0/auth (explicitly auth-exempt on hm-core)
     * with fallback to /api/v0/status so paired Cores are also discovered cleanly.
     */
    public static boolean probeCoreHttp(String host, int webPort, String bearerToken, int timeoutMs) {
        try {
            HttpResult authRes = httpRequest("GET", host, webPort, "/api/v0/auth", null, bearerToken, timeoutMs, false);
            if (authRes != null && authRes.code == 200 && authRes.body != null && authRes.body.contains("configured")) {
                return true;
            }
            HttpResult stRes = httpRequest("GET", host, webPort, "/api/v0/status", null, bearerToken, timeoutMs, false);
            return stRes != null && (stRes.code == 200 || stRes.code == 401);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Fetches a live per-room summary card for any household Core node (supports open, paired,
     * Option 1A LAN-exempt, and Manage-Only Cores). Uses HTTP metadata exclusively (zero raw :8932 probes).
     */
    public static RoomNodeSummary fetchRoomSummary(
            String host,
            int webPort,
            int gatewayPort,
            String fallbackName,
            String bearerToken) {
        String label = (fallbackName != null && !fallbackName.trim().isEmpty()) ? fallbackName.trim() : host;
        if (host == null || host.trim().isEmpty()) {
            return new RoomNodeSummary("", webPort, gatewayPort, label, "Home Mate",
                    false, false, false, false, "", "", "half", "", 0, "unconfigured");
        }
        try {
            HttpResult statusRes = httpRequest("GET", host, webPort, "/api/v0/status", null, bearerToken, 1600, false);
            if (statusRes == null) {
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        false, false, false, false, "", "", "half", "", 0, "unreachable");
            }
            if (statusRes.code == 401) {
                String coreId = "";
                boolean lanExempt = false;
                boolean voiceOpen = false;
                try {
                    HttpResult authRes = httpRequest("GET", host, webPort, "/api/v0/auth", null, bearerToken, 1200, false);
                    if (authRes != null && authRes.code == 200 && authRes.body != null && authRes.body.trim().startsWith("{")) {
                        Map<String, Object> authObj = MiniJson.parseObject(authRes.body);
                        coreId = MiniJson.getString(authObj, "core_id", "");
                        lanExempt = MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
                        voiceOpen = MiniJson.getBoolean(authObj, "voice_gateway_open", false);
                    }
                } catch (Throwable ignored) {
                }
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        true, true, voiceOpen, false, "", "", "half", "", 0, "HTTP 401", coreId, lanExempt);
            }
            if (statusRes.code < 200 || statusRes.code >= 300 || statusRes.body == null) {
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        false, false, false, false, "", "", "half", "", 0, "HTTP " + statusRes.code);
            }
            Map<String, Object> statusObj = MiniJson.parseObject(statusRes.body);
            String assistantName = MiniJson.getString(statusObj, "assistant_name", "Home Mate");
            String coreId = MiniJson.getString(statusObj, "core_id", "");
            Map<String, Object> authObj = MiniJson.getObject(statusObj, "auth");
            boolean lanExempt = authObj != null && MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
            boolean voiceOpen = CoreDiscovery.inferVoiceGatewayOpenFromStatus(host, statusObj);
            List<DeviceInfo> devs = parseDevicesFromStatus(statusObj);
            String activeDev = "";
            for (DeviceInfo d : devs) {
                if (d.connected) {
                    activeDev = d.id;
                    break;
                }
            }
            if (activeDev.isEmpty() && !devs.isEmpty()) {
                activeDev = devs.get(0).id;
            }

            ModeState ms = new ModeState("half", false, true, "off", false, "", "");
            try {
                HttpResult modeRes = httpRequest("GET", host, webPort, "/api/v0/mode", null, bearerToken, 1400, false);
                if (modeRes != null && modeRes.code == 200 && modeRes.body != null) {
                    ms = parseModeResponse(MiniJson.parseObject(modeRes.body), activeDev);
                }
            } catch (Throwable ignored) {
            }

            return new RoomNodeSummary(
                    host, webPort, gatewayPort, label, assistantName,
                    true, false, voiceOpen,
                    ms.musicActive, ms.musicTitle, ms.musicSource,
                    ms.duplex, activeDev, devs.size(), null, coreId, lanExempt);
        } catch (Throwable t) {
            return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                    false, false, false, false, "", "", "half", "", 0, t.getMessage());
        }
    }

    public static CoreSnapshot fetchSnapshot(String host, int webPort, String deviceId) {
        return fetchSnapshot(host, webPort, deviceId, null, true);
    }

    public static CoreSnapshot fetchSnapshot(
            String host,
            int webPort,
            String deviceId,
            String bearerToken,
            boolean includeFleetAndDevices) {
        if (host == null || host.trim().isEmpty()) {
            return CoreSnapshot.offline("unconfigured");
        }
        try {
            // 1. /api/v0/mode gives duplex, barge_in, wake_ack, wake_arbitration AND authoritative media[deviceId]
            HttpResult modeRes = httpRequest("GET", host, webPort, "/api/v0/mode", null, bearerToken, 2200, false);
            if (modeRes == null) {
                return CoreSnapshot.offline("unreachable");
            }
            if (modeRes.code == 401) {
                return CoreSnapshot.unauthorized();
            }
            if (modeRes.code == 429) {
                return CoreSnapshot.offline("rate_limited");
            }
            ModeState ms = (modeRes.code >= 200 && modeRes.code < 300 && modeRes.body != null)
                    ? parseModeResponse(MiniJson.parseObject(modeRes.body), deviceId)
                    : new ModeState("half", false, true, "off", false, "", "");

            // 2. /api/v0/devices/{id}/conversation already includes last 50 messages directly
            List<ChatEntry> transcript = fetchConversationMessages(host, webPort, deviceId, bearerToken);

            // 3. Optional status + fleet (needed on Rooms tab or periodic refresh)
            String assistantName = "Home Mate";
            String buildId = "";
            List<DeviceInfo> devices = Collections.emptyList();
            List<FleetNodeInfo> fleetNodes = Collections.emptyList();

            if (includeFleetAndDevices) {
                try {
                    HttpResult statusRes = httpRequest("GET", host, webPort, "/api/v0/status", null, bearerToken, 2200, false);
                    if (statusRes != null && statusRes.code == 200 && statusRes.body != null) {
                        Map<String, Object> statusObj = MiniJson.parseObject(statusRes.body);
                        assistantName = MiniJson.getString(statusObj, "assistant_name", "Home Mate");
                        Map<String, Object> buildObj = MiniJson.getObject(statusObj, "build");
                        buildId = buildObj != null ? MiniJson.getString(buildObj, "id", "") : "";
                        devices = parseDevicesFromStatus(statusObj);
                        // In Manage-Only mode, if own deviceId has no conversation yet, read the primary speaker's conversation!
                        if (transcript.isEmpty() && !devices.isEmpty()) {
                            transcript = fetchConversationMessages(host, webPort, devices.get(0).id, bearerToken);
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    HttpResult fleetRes = httpRequest("GET", host, webPort, "/api/v0/fleet", null, bearerToken, 1800, false);
                    if (fleetRes != null && fleetRes.code == 200 && fleetRes.body != null) {
                        fleetNodes = parseFleetNodes(MiniJson.parseObject(fleetRes.body));
                    }
                } catch (Throwable ignored) {
                }
            }

            return new CoreSnapshot(
                    true, false, assistantName, buildId,
                    ms.duplex, ms.bargeIn, ms.wakeAck, ms.wakeArbitration,
                    ms.musicActive, ms.musicTitle, ms.musicSource,
                    devices, fleetNodes, transcript, null);
        } catch (Throwable t) {
            return CoreSnapshot.offline(t.getMessage());
        }
    }

    public static ModeState parseModeResponse(Map<String, Object> modeObj, String deviceId) {
        String duplex = MiniJson.getString(modeObj, "default_duplex", "half");
        boolean bargeIn = MiniJson.getBoolean(modeObj, "barge_in", false);
        boolean wakeAck = true;
        String wakeArb = "off";
        boolean musicActive = false;
        String musicTitle = "";
        String musicSource = "";

        Map<String, Object> cue = MiniJson.getObject(modeObj, "cue_readiness");
        if (cue != null) {
            wakeAck = MiniJson.getBoolean(cue, "wake_ack_enabled", true);
        }
        Map<String, Object> arbObj = MiniJson.getObject(modeObj, "wake_arbitration");
        if (arbObj != null) {
            wakeArb = MiniJson.getString(arbObj, "mode", wakeArb);
        } else if (modeObj.containsKey("wake_arbitration")) {
            wakeArb = MiniJson.getString(modeObj, "wake_arbitration", wakeArb);
        }

        // Authoritative live media map: prefer modeObj.media[deviceId], fallback to any playing device on that Core
        Map<String, Object> mediaMap = MiniJson.getObject(modeObj, "media");
        if (mediaMap != null) {
            Map<String, Object> devMedia = (deviceId != null && !deviceId.isEmpty())
                    ? MiniJson.getObject(mediaMap, deviceId) : null;
            if (devMedia != null) {
                String state = MiniJson.getString(devMedia, "state", "");
                musicActive = "playing".equalsIgnoreCase(state) || MiniJson.getBoolean(devMedia, "playing", false);
                musicTitle = MiniJson.getString(devMedia, "title", "");
                musicSource = MiniJson.getString(devMedia, "source", "");
            } else {
                for (Object val : mediaMap.values()) {
                    if (val instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> anyMedia = (Map<String, Object>) val;
                        String state = MiniJson.getString(anyMedia, "state", "");
                        boolean active = "playing".equalsIgnoreCase(state)
                                || MiniJson.getBoolean(anyMedia, "playing", false);
                        if (active) {
                            musicActive = true;
                            musicTitle = MiniJson.getString(anyMedia, "title", "");
                            musicSource = MiniJson.getString(anyMedia, "source", "");
                            break;
                        }
                    }
                }
            }
        }

        List<Object> devList = MiniJson.getList(modeObj, "devices");
        if (devList != null && deviceId != null) {
            for (Object item : devList) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dm = (Map<String, Object>) item;
                    if (deviceId.equals(MiniJson.getString(dm, "device", ""))) {
                        Map<String, Object> dupObj = MiniJson.getObject(dm, "duplex");
                        if (dupObj != null) {
                            String eff = MiniJson.getString(dupObj, "effective", null);
                            String req = MiniJson.getString(dupObj, "requested", null);
                            if (eff != null && !eff.isEmpty()) {
                                duplex = eff;
                            } else if (req != null && !req.isEmpty()) {
                                duplex = req;
                            }
                        }
                        Map<String, Object> wakeAckObj = MiniJson.getObject(dm, "wake_ack");
                        if (wakeAckObj != null && wakeAckObj.containsKey("enabled")) {
                            wakeAck = MiniJson.getBoolean(wakeAckObj, "enabled", wakeAck);
                        }
                        break;
                    }
                }
            }
        }
        return new ModeState(duplex, bargeIn, wakeAck, wakeArb, musicActive, musicTitle, musicSource);
    }

    public static List<FleetNodeInfo> parseFleetNodes(Map<String, Object> fleetObj) {
        List<FleetNodeInfo> out = new ArrayList<FleetNodeInfo>();
        List<Object> nodes = MiniJson.getList(fleetObj, "nodes");
        if (nodes == null) {
            return out;
        }
        for (Object nObj : nodes) {
            if (nObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nm = (Map<String, Object>) nObj;
                String nid = MiniJson.getString(nm, "id", "");
                String nurl = MiniJson.getString(nm, "base_url", MiniJson.getString(nm, "url", ""));
                String lastErr = MiniJson.getString(nm, "last_error", null);
                long lastOkMs = MiniJson.getLong(nm, "last_ok_ms", 0L);
                boolean healthy;
                if (nm.containsKey("healthy")) {
                    healthy = MiniJson.getBoolean(nm, "healthy", false);
                } else {
                    healthy = lastOkMs > 0L && (lastErr == null || lastErr.trim().isEmpty());
                }
                if (!nid.isEmpty()) {
                    out.add(new FleetNodeInfo(nid, nurl, healthy));
                }
            }
        }
        return out;
    }

    public static String buildModePutBody(
            String duplexRequested,
            Boolean bargeIn,
            Boolean wakeAck,
            String wakeArbitration) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (duplexRequested != null) {
            body.put("duplex_requested", duplexRequested);
        }
        if (bargeIn != null) {
            body.put("barge_in", bargeIn);
        }
        if (wakeAck != null) {
            Map<String, Object> wa = new LinkedHashMap<String, Object>();
            wa.put("enabled", wakeAck);
            body.put("wake_ack", wa);
        }
        if (wakeArbitration != null) {
            body.put("wake_arbitration", wakeArbitration);
        }
        return MiniJson.stringify(body);
    }

    public static String buildModePutBody(String duplexRequested, Boolean wakeAck, String wakeArbitration) {
        return buildModePutBody(duplexRequested, null, wakeAck, wakeArbitration);
    }

    public static String buildChatPostBody(String text) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("text", text != null ? text.trim() : "");
        body.put("source", "android_app");
        return MiniJson.stringify(body);
    }

    public static String buildVolumePostBody(double linearVolume) {
        double clamped = Math.max(0.0, Math.min(1.0, linearVolume));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("value", Math.round(clamped * 100.0) / 100.0);
        return MiniJson.stringify(body);
    }

    public static boolean stopRemoteDevice(String host, int webPort, String deviceId, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/stop",
                    "{}", bearerToken, 2500, false);
            return res != null && res.code >= 200 && res.code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean pauseRemoteDevice(String host, int webPort, String deviceId, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/program/pause",
                    "{}", bearerToken, 2500, false);
            return res != null && res.code >= 200 && res.code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setRemoteDeviceVolume(
            String host, int webPort, String deviceId, double linearVolume, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/volume",
                    buildVolumePostBody(linearVolume), bearerToken, 2500, false);
            return res != null && res.code >= 200 && res.code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean updateDeviceMode(
            String host,
            int webPort,
            String deviceId,
            String duplexRequested,
            Boolean bargeIn,
            Boolean wakeAck,
            String wakeArbitration,
            String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        try {
            String encDev = encodePathSegment(deviceId);
            HttpResult res = httpRequest(
                    "PUT",
                    host,
                    webPort,
                    "/api/v0/mode/" + encDev,
                    buildModePutBody(duplexRequested, bargeIn, wakeAck, wakeArbitration),
                    bearerToken,
                    3000,
                    false);
            if (res == null || res.code < 200 || res.code >= 300) {
                return false;
            }
            if (res.body != null && !res.body.trim().isEmpty()) {
                Map<String, Object> obj = MiniJson.parseObject(res.body);
                if (obj.containsKey("error") && obj.get("error") != null) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean updateDeviceMode(
            String host,
            int webPort,
            String deviceId,
            String duplexRequested,
            Boolean wakeAck,
            String wakeArbitration) {
        return updateDeviceMode(host, webPort, deviceId, duplexRequested, null, wakeAck, wakeArbitration, null);
    }

    public static boolean sendChatText(String host, int webPort, String deviceId, String text) {
        return sendChatText(host, webPort, deviceId, null, text);
    }

    public static boolean sendChatText(String host, int webPort, String deviceId, String bearerToken, String text) {
        if (host == null || text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            // /api/v0/chat returns an SSE stream; accept immediately once HTTP 200 headers arrive!
            HttpResult res = httpRequest(
                    "POST",
                    host,
                    webPort,
                    "/api/v0/chat",
                    buildChatPostBody(text),
                    bearerToken,
                    5000,
                    true);
            return res != null && res.code >= 200 && res.code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static List<ChatEntry> parseConversationMessages(Map<String, Object> convObj) {
        List<ChatEntry> out = new ArrayList<ChatEntry>();
        if (convObj == null) {
            return out;
        }
        List<Object> msgs = MiniJson.getList(convObj, "messages");
        if (msgs == null) {
            return out;
        }
        int start = Math.max(0, msgs.size() - 60);
        for (int i = start; i < msgs.size(); i++) {
            if (msgs.get(i) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) msgs.get(i);
                String role = MiniJson.getString(m, "role", "");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                String content = MiniJson.getString(m, "content", "");
                if (content.isEmpty()) {
                    content = MiniJson.getString(m, "text", "");
                }
                String cleaned = sanitizeDialogueText(content);
                long ts = MiniJson.getLong(m, "ts_ms", MiniJson.getLong(m, "created_ms", 0L));
                if (!cleaned.isEmpty()) {
                    out.add(new ChatEntry(role, cleaned, ts));
                }
            }
        }
        return out;
    }

    /**
     * Strips internal tool-call logs ({@code [调用 ...]}, {@code [回执 ...]}), reasoning blocks
     * ({@code <think>...</think>}, {@code [思考 ...]}), and raw JSON traces so the user only sees
     * clean spoken dialogue ("what I said" and "what Home Mate answered").
     */
    public static String sanitizeDialogueText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw;
        text = stripXmlTagBlock(text, "think");
        text = stripXmlTagBlock(text, "thinking");
        text = stripXmlTagBlock(text, "thought");

        String[] lines = text.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isInternalTraceLine(t)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    private static String stripXmlTagBlock(String input, String tag) {
        String lower = input.toLowerCase(java.util.Locale.US);
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        StringBuilder out = new StringBuilder(input.length());
        int pos = 0;
        while (pos < input.length()) {
            int openIdx = lower.indexOf(openTag, pos);
            if (openIdx < 0) {
                out.append(input, pos, input.length());
                break;
            }
            out.append(input, pos, openIdx);
            int closeIdx = lower.indexOf(closeTag, openIdx + openTag.length());
            if (closeIdx < 0) {
                break;
            }
            pos = closeIdx + closeTag.length();
        }
        return out.toString();
    }

    private static boolean isInternalTraceLine(String trimmed) {
        if (trimmed.startsWith("[调用 ") || trimmed.startsWith("[调用:")
                || trimmed.startsWith("[回执 ") || trimmed.startsWith("[回执:")
                || trimmed.startsWith("[思考 ") || trimmed.startsWith("[思考:")
                || trimmed.startsWith("[工具 ") || trimmed.startsWith("[工具:")
                || trimmed.startsWith("[tool ") || trimmed.startsWith("[tool:")
                || trimmed.startsWith("[call ") || trimmed.startsWith("[call:")
                || trimmed.startsWith("[thought ") || trimmed.startsWith("[thought:")
                || trimmed.startsWith("[thinking")) {
            return true;
        }
        if (trimmed.startsWith("{\"tool") || trimmed.startsWith("{\"name\":\"") && trimmed.contains("\"arguments\"")) {
            return true;
        }
        return false;
    }

    private static List<ChatEntry> fetchConversationMessages(
            String host, int webPort, String deviceId, String bearerToken) {
        if (deviceId == null || deviceId.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String encDev = encodePathSegment(deviceId);
            HttpResult convRes = httpRequest(
                    "GET", host, webPort, "/api/v0/devices/" + encDev + "/conversation",
                    null, bearerToken, 2000, false);
            if (convRes != null && convRes.code == 200 && convRes.body != null) {
                return parseConversationMessages(MiniJson.parseObject(convRes.body));
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    public static List<DeviceInfo> parseDevicesFromStatus(Map<String, Object> statusObj) {
        List<DeviceInfo> out = new ArrayList<DeviceInfo>();
        Map<String, Object> gw = MiniJson.getObject(statusObj, "gateway");
        if (gw == null) {
            return out;
        }
        List<Object> devs = MiniJson.getList(gw, "devices");
        if (devs == null) {
            return out;
        }
        for (Object dObj : devs) {
            if (dObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dm = (Map<String, Object>) dObj;
                String id = MiniJson.getString(dm, "stable_id",
                        MiniJson.getString(dm, "id", MiniJson.getString(dm, "device", "")));
                String model = MiniJson.getString(dm, "model", "");
                boolean online = MiniJson.getBoolean(dm, "online", MiniJson.getBoolean(dm, "connected", true));
                boolean aec = MiniJson.getBoolean(dm, "aec_enabled", false);
                String profile = MiniJson.getString(dm, "audio_profile", "");
                if (!id.isEmpty()) {
                    out.add(new DeviceInfo(id, model, online, aec, profile));
                }
            }
        }
        return out;
    }

    public static boolean isPrivateOrTailscaleHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim();
        return h.startsWith("192.168.")
                || h.startsWith("10.")
                || h.startsWith("100.")
                || h.startsWith("127.")
                || "localhost".equalsIgnoreCase(h)
                || h.startsWith("172.16.") || h.startsWith("172.17.") || h.startsWith("172.18.")
                || h.startsWith("172.19.") || h.startsWith("172.2") || h.startsWith("172.30.")
                || h.startsWith("172.31.");
    }

    private static String encodePathSegment(String segment) throws IOException {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
    }

    private static HttpResult httpRequest(
            String method,
            String host,
            int webPort,
            String path,
            String jsonBody,
            String bearerToken,
            int timeoutMs,
            boolean headersOnlySse) throws IOException {
        URL url = new URL("http://" + host + ":" + webPort + path);
        HttpURLConnection conn = isPrivateOrTailscaleHost(host)
                ? (HttpURLConnection) url.openConnection(Proxy.NO_PROXY)
                : (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        if (bearerToken != null && !bearerToken.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
        }
        if (jsonBody != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        }
        int code = conn.getResponseCode();
        if (headersOnlySse) {
            try {
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                if (is != null) {
                    is.close();
                }
            } catch (Throwable ignored) {
            }
            return new HttpResult(code, "");
        }
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            return new HttpResult(code, null);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return new HttpResult(code, sb.toString());
    }
}
