package homes.mymate.gateway.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
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
        public final Double volume;

        public DeviceInfo(String id, String model, boolean connected, boolean aecEnabled, String audioProfile) {
            this(id, model, connected, aecEnabled, audioProfile, null);
        }

        public DeviceInfo(
                String id,
                String model,
                boolean connected,
                boolean aecEnabled,
                String audioProfile,
                Double volume) {
            this.id = id;
            this.model = model;
            this.connected = connected;
            this.aecEnabled = aecEnabled;
            this.audioProfile = audioProfile;
            this.volume = volume;
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
        public final Double volume;
        public final boolean musicPaused;

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
                boolean lanGatewayExempt,
                Double volume,
                boolean musicPaused) {
            this.host = host;
            this.webPort = webPort;
            this.gatewayPort = gatewayPort;
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : host;
            this.assistantName = assistantName != null ? assistantName : "Home Mate";
            this.online = online;
            this.authRequired = authRequired;
            this.voiceGatewayOpen = voiceGatewayOpen;
            this.musicActive = musicActive;
            this.musicPaused = musicPaused;
            this.musicTitle = musicTitle != null ? musicTitle : "";
            this.musicSource = musicSource != null ? musicSource : "";
            this.duplexMode = duplexMode != null ? duplexMode : "half";
            this.activeDeviceId = activeDeviceId != null ? activeDeviceId : "";
            this.deviceCount = deviceCount;
            this.error = error;
            this.coreId = coreId != null ? coreId.trim() : "";
            this.lanGatewayExempt = lanGatewayExempt;
            this.volume = volume;
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
                String error,
                String coreId,
                boolean lanGatewayExempt,
                Double volume) {
            this(host, webPort, gatewayPort, displayName, assistantName, online, authRequired,
                    voiceGatewayOpen, musicActive, musicTitle, musicSource, duplexMode,
                    activeDeviceId, deviceCount, error, coreId, lanGatewayExempt, volume, false);
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
                String error,
                String coreId,
                boolean lanGatewayExempt) {
            this(host, webPort, gatewayPort, displayName, assistantName, online, authRequired,
                    voiceGatewayOpen, musicActive, musicTitle, musicSource, duplexMode,
                    activeDeviceId, deviceCount, error, coreId, lanGatewayExempt, null, false);
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
                    activeDeviceId, deviceCount, error, "", false, null, false);
        }

        public static RoomNodeSummary offline(
                String host,
                int webPort,
                int gatewayPort,
                String displayName,
                String coreId,
                String error) {
            String h = host != null ? host.trim() : "";
            String label = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : h;
            String err = (error != null && !error.trim().isEmpty()) ? error.trim() : "offline";
            return new RoomNodeSummary(
                    h, webPort, gatewayPort, label, "Home Mate",
                    false, false, false, false, "", "", "half", "", 0,
                    err, coreId, false, null, false);
        }
    }

    /** Multi-route target descriptor for Whole-House concurrent room summary fetching (P1-5). */
    public static final class RoomTarget {
        public final String host;
        public final int webPort;
        public final int gatewayPort;
        public final String displayName;
        public final String bearerToken;
        public final String coreId;
        public final List<String> alternateHosts;

        public RoomTarget(
                String host,
                int webPort,
                int gatewayPort,
                String displayName,
                String bearerToken,
                String coreId,
                List<String> alternateHosts) {
            this.host = host != null ? host.trim() : "";
            this.webPort = webPort;
            this.gatewayPort = gatewayPort;
            this.displayName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : this.host;
            this.bearerToken = bearerToken != null ? bearerToken : "";
            this.coreId = coreId != null ? coreId.trim() : "";
            this.alternateHosts = alternateHosts != null
                    ? Collections.unmodifiableList(new ArrayList<String>(alternateHosts))
                    : Collections.<String>emptyList();
        }
    }

    public interface RoomProbeFn {
        RoomNodeSummary probe(
                String host,
                int webPort,
                int gatewayPort,
                String fallbackName,
                String bearerToken,
                String expectedCoreId,
                int perRequestTimeoutMs);
    }

    public static final class ChatEntry {
        public final String role; // "user" | "assistant"
        public final String text;
        public final long tsMs;
        public final String sessionId;
        public final boolean archived;

        public ChatEntry(String role, String text, long tsMs) {
            this(role, text, tsMs, "", false);
        }

        public ChatEntry(String role, String text, long tsMs, String sessionId, boolean archived) {
            this.role = role;
            this.text = text;
            this.tsMs = tsMs;
            this.sessionId = sessionId != null ? sessionId : "";
            this.archived = archived;
        }
    }

    public static final class ConversationHistoryResult {
        public final List<ChatEntry> messages;
        public final String currentSessionId;
        public final boolean authRequired;
        public final boolean ok;

        public ConversationHistoryResult(
                List<ChatEntry> messages,
                String currentSessionId,
                boolean authRequired,
                boolean ok) {
            this.messages = messages != null ? messages : Collections.<ChatEntry>emptyList();
            this.currentSessionId = currentSessionId != null ? currentSessionId : "";
            this.authRequired = authRequired;
            this.ok = ok;
        }
    }

    public static final class ReflexSyncTable {
        public final String compiledKeywordsTxt;
        public final List<String> wakeWords;
        public final List<String> retreatWords;
        public final String hash;

        public ReflexSyncTable(
                String compiledKeywordsTxt,
                List<String> wakeWords,
                List<String> retreatWords,
                String hash) {
            this.compiledKeywordsTxt = compiledKeywordsTxt != null ? compiledKeywordsTxt : "";
            this.wakeWords = wakeWords != null
                    ? Collections.unmodifiableList(new ArrayList<String>(wakeWords))
                    : Collections.<String>emptyList();
            this.retreatWords = retreatWords != null
                    ? Collections.unmodifiableList(new ArrayList<String>(retreatWords))
                    : Collections.<String>emptyList();
            this.hash = hash != null ? hash : "";
        }
    }

    public static final class ModeState {
        public final String duplex;
        public final boolean bargeIn;
        public final boolean wakeAck;
        public final String wakeArbitration;
        public final boolean musicActive;
        public final boolean musicPaused;
        public final String musicTitle;
        public final String musicSource;
        public final ReflexSyncTable reflexTable;

        public ModeState(
                String duplex,
                boolean bargeIn,
                boolean wakeAck,
                String wakeArbitration,
                boolean musicActive,
                String musicTitle,
                String musicSource) {
            this(duplex, bargeIn, wakeAck, wakeArbitration, musicActive, false, musicTitle, musicSource, null);
        }

        public ModeState(
                String duplex,
                boolean bargeIn,
                boolean wakeAck,
                String wakeArbitration,
                boolean musicActive,
                String musicTitle,
                String musicSource,
                ReflexSyncTable reflexTable) {
            this(duplex, bargeIn, wakeAck, wakeArbitration, musicActive, false, musicTitle, musicSource, reflexTable);
        }

        public ModeState(
                String duplex,
                boolean bargeIn,
                boolean wakeAck,
                String wakeArbitration,
                boolean musicActive,
                boolean musicPaused,
                String musicTitle,
                String musicSource,
                ReflexSyncTable reflexTable) {
            this.duplex = duplex;
            this.bargeIn = bargeIn;
            this.wakeAck = wakeAck;
            this.wakeArbitration = wakeArbitration;
            this.musicActive = musicActive;
            this.musicPaused = musicPaused;
            this.musicTitle = musicTitle != null ? musicTitle : "";
            this.musicSource = musicSource != null ? musicSource : "";
            this.reflexTable = reflexTable;
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
        public final boolean musicPaused;
        public final String musicTitle;
        public final String musicSource;
        public final List<DeviceInfo> devices;
        public final List<FleetNodeInfo> fleetNodes;
        public final List<ChatEntry> transcript;
        public final String error;
        public final ReflexSyncTable reflexTable;

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
            this(reachable, authRequired, assistantName, buildId, duplexMode, bargeIn,
                    wakeAckEnabled, wakeArbitration, musicActive, false, musicTitle, musicSource,
                    devices, fleetNodes, transcript, error, null);
        }

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
                String error,
                ReflexSyncTable reflexTable) {
            this(reachable, authRequired, assistantName, buildId, duplexMode, bargeIn,
                    wakeAckEnabled, wakeArbitration, musicActive, false, musicTitle, musicSource,
                    devices, fleetNodes, transcript, error, reflexTable);
        }

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
                boolean musicPaused,
                String musicTitle,
                String musicSource,
                List<DeviceInfo> devices,
                List<FleetNodeInfo> fleetNodes,
                List<ChatEntry> transcript,
                String error,
                ReflexSyncTable reflexTable) {
            this.reachable = reachable;
            this.authRequired = authRequired;
            this.assistantName = assistantName;
            this.buildId = buildId;
            this.duplexMode = duplexMode;
            this.bargeIn = bargeIn;
            this.wakeAckEnabled = wakeAckEnabled;
            this.wakeArbitration = wakeArbitration;
            this.musicActive = musicActive;
            this.musicPaused = musicPaused;
            this.musicTitle = musicTitle;
            this.musicSource = musicSource;
            this.devices = devices;
            this.fleetNodes = fleetNodes;
            this.transcript = transcript;
            this.error = error;
            this.reflexTable = reflexTable;
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

    public static final class ActionOutcome {
        public final boolean ok;
        public final boolean authRequired;
        public final int httpCode;

        public ActionOutcome(boolean ok, boolean authRequired, int httpCode) {
            this.ok = ok;
            this.authRequired = authRequired;
            this.httpCode = httpCode;
        }

        public static ActionOutcome success(int code) {
            return new ActionOutcome(true, false, code);
        }

        public static ActionOutcome failure(int code) {
            return new ActionOutcome(false, isAuthStatusCode(code), code);
        }
    }

    private static final class ConversationFetchResult {
        final List<ChatEntry> messages;
        final boolean authRequired;

        ConversationFetchResult(List<ChatEntry> messages, boolean authRequired) {
            this.messages = messages;
            this.authRequired = authRequired;
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

    private static boolean isAuthStatusCode(int code) {
        return code == 401 || code == 403;
    }

    private CoreApiClient() {
    }

    /**
     * Normalizes a user-pasted Bearer token by trimming whitespace/newlines, removing
     * matching surrounding quotes ({@code "}, {@code '}, {@code `}), and stripping an
     * optional case-insensitive {@code Bearer } prefix.
     */
    public static String sanitizeBearerToken(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r", "").replace("\n", "").trim();
        s = stripMatchingQuotes(s);
        if (s.length() >= 7 && s.substring(0, 7).equalsIgnoreCase("bearer ")) {
            s = s.substring(7).trim();
            s = stripMatchingQuotes(s);
        }
        return s;
    }

    private static String stripMatchingQuotes(String input) {
        String s = input;
        while (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    public static final class TokenVerifyResult {
        public final boolean ok;
        public final boolean authRequired;
        public final int httpCode;
        public final String coreId;
        public final String assistantName;

        public TokenVerifyResult(
                boolean ok,
                boolean authRequired,
                int httpCode,
                String coreId,
                String assistantName) {
            this.ok = ok;
            this.authRequired = authRequired;
            this.httpCode = httpCode;
            this.coreId = coreId != null ? coreId.trim() : "";
            this.assistantName = assistantName != null ? assistantName.trim() : "";
        }

        public ActionOutcome toActionOutcome() {
            return new ActionOutcome(ok, authRequired, httpCode);
        }
    }

    /**
     * Live-validates a candidate Bearer token against {@code GET /api/v0/status} and extracts
     * the authenticated Core's verified {@code core_id} and {@code assistant_name} on HTTP 200
     * so saved profiles can pin {@code core_id} immediately for fail-closed {@code fetchRoomSummary}.
     */
    public static TokenVerifyResult verifyBearerTokenWithIdentity(String host, int webPort, String rawToken) {
        if (host == null || host.trim().isEmpty()) {
            return new TokenVerifyResult(false, false, 0, "", "");
        }
        String cleanHost = host.trim();
        String clean = sanitizeBearerToken(rawToken);
        try {
            HttpResult res = httpRequest("GET", cleanHost, webPort, "/api/v0/status", null, clean, 3500);
            if (res == null) {
                return new TokenVerifyResult(false, false, 0, "", "");
            }
            if (res.code == 200) {
                String coreId = "";
                String assistantName = "";
                if (res.body != null && res.body.trim().startsWith("{")) {
                    try {
                        Map<String, Object> obj = MiniJson.parseObject(res.body);
                        coreId = MiniJson.getString(obj, "core_id", "").trim();
                        assistantName = MiniJson.getString(obj, "assistant_name", "").trim();
                    } catch (Throwable ignored) {
                    }
                }
                if (coreId.isEmpty()) {
                    try {
                        HttpResult authRes = httpRequest("GET", cleanHost, webPort, "/api/v0/auth", null, null, 1500);
                        if (authRes != null && authRes.code == 200 && authRes.body != null
                                && authRes.body.trim().startsWith("{")) {
                            Map<String, Object> authObj = MiniJson.parseObject(authRes.body);
                            coreId = MiniJson.getString(authObj, "core_id", "").trim();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return new TokenVerifyResult(true, false, 200, coreId, assistantName);
            }
            return new TokenVerifyResult(false, isAuthStatusCode(res.code), res.code, "", "");
        } catch (Throwable ignored) {
            return new TokenVerifyResult(false, false, 0, "", "");
        }
    }

    /**
     * Live-validates a candidate Bearer token against {@code GET /api/v0/status}.
     * Returns {@code ok=true} on HTTP 200, {@code authRequired=true} on HTTP 401/403,
     * and {@code ok=false, authRequired=false} when the Core is unreachable.
     */
    public static ActionOutcome verifyBearerToken(String host, int webPort, String rawToken) {
        return verifyBearerTokenWithIdentity(host, webPort, rawToken).toActionOutcome();
    }

    /**
     * Clears the active device conversation on {@code hm-core} via
     * {@code DELETE /api/v0/devices/{id}/conversation} with JSON body
     * {@code {"expected_session_id": ...}}.
     */
    public static ActionOutcome clearDeviceConversation(
            String host, int webPort, String deviceId, String expectedSessionId, String bearerToken) {
        return clearDeviceConversation(host, webPort, deviceId, expectedSessionId, false, bearerToken);
    }

    /**
     * Clears the device conversation on {@code hm-core} via
     * {@code DELETE /api/v0/devices/{id}/conversation}. When {@code includeArchived} is {@code true},
     * requests clearing both the active context and all archived contexts for this device (with
     * graceful fallback to current-only payload on older Cores that reject unknown fields with 400/422).
     */
    public static ActionOutcome clearDeviceConversation(
            String host,
            int webPort,
            String deviceId,
            String expectedSessionId,
            boolean includeArchived,
            String bearerToken) {
        String cleanSid = expectedSessionId != null ? expectedSessionId.trim() : "";
        if (host == null || host.trim().isEmpty()
                || deviceId == null || deviceId.trim().isEmpty()
                || (!includeArchived && cleanSid.isEmpty())) {
            return ActionOutcome.failure(0);
        }
        try {
            String path = "/api/v0/devices/" + encodePathSegment(deviceId.trim()) + "/conversation";
            String cleanToken = sanitizeBearerToken(bearerToken);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("expected_session_id", cleanSid);
            if (includeArchived) {
                body.put("include_archived", Boolean.TRUE);
            }
            HttpResult res = httpDeleteJson(
                    host.trim(), webPort, path, MiniJson.stringify(body), cleanToken, 3000);
            // Graceful fallback when connected to an older Core that uses deny_unknown_fields
            // without `include_archived` support (returns 400 or 422).
            if (includeArchived && !cleanSid.isEmpty()
                    && res != null && (res.code == 400 || res.code == 422)) {
                Map<String, Object> legacyBody = new LinkedHashMap<String, Object>();
                legacyBody.put("expected_session_id", cleanSid);
                res = httpDeleteJson(
                        host.trim(), webPort, path, MiniJson.stringify(legacyBody), cleanToken, 3000);
            }
            if (res != null && res.code >= 200 && res.code < 300) {
                return ActionOutcome.success(res.code);
            }
            return ActionOutcome.failure(res != null ? res.code : 0);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
        }
    }

    /**
     * Bounded HTTP/1.1 {@code DELETE} with JSON body over a raw {@link Socket}, because
     * Android / JDK {@link HttpURLConnection} rejects {@code setRequestMethod("DELETE")}
     * combined with {@code setDoOutput(true)} ({@code ProtocolException}).
     */
    private static HttpResult httpDeleteJson(
            String host,
            int webPort,
            String path,
            String jsonBody,
            String bearerToken,
            int timeoutMs) throws IOException {
        if (host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IOException("Invalid CR/LF in HTTP target");
        }
        byte[] bodyBytes = (jsonBody != null ? jsonBody : "").getBytes(StandardCharsets.UTF_8);
        String cleanTok = sanitizeBearerToken(bearerToken);
        String hostHeader = host.contains(":") ? ("[" + host + "]:" + webPort) : (host + ":" + webPort);
        StringBuilder req = new StringBuilder(256);
        req.append("DELETE ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("Content-Type: application/json; charset=UTF-8\r\n");
        req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if (!cleanTok.isEmpty() && cleanTok.indexOf('\r') < 0 && cleanTok.indexOf('\n') < 0) {
            req.append("Authorization: Bearer ").append(cleanTok).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        byte[] headerBytes = req.toString().getBytes(StandardCharsets.UTF_8);

        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(host, webPort), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            OutputStream os = sock.getOutputStream();
            os.write(headerBytes);
            if (bodyBytes.length > 0) {
                os.write(bodyBytes);
            }
            os.flush();

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = br.readLine();
            if (statusLine == null) {
                return null;
            }
            int code = 0;
            String[] parts = statusLine.trim().split("\\s+");
            if (parts.length >= 2) {
                try {
                    code = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    code = 0;
                }
            }
            // Skip response headers
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                // drain headers
            }
            // Read bounded response body (<= 16KB)
            StringBuilder respBody = new StringBuilder();
            char[] buf = new char[1024];
            int r;
            while ((r = br.read(buf)) != -1 && respBody.length() < 16384) {
                int take = Math.min(r, 16384 - respBody.length());
                respBody.append(buf, 0, take);
            }
            return new HttpResult(code, respBody.toString());
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static boolean probeCoreHttp(String host, int webPort, int timeoutMs) {
        return probeCoreHttp(host, webPort, null, timeoutMs);
    }

    /**
     * Unauthenticated Core reachability & optional identity probe (P0-1): queries
     * {@code GET /api/v0/auth} (auth-exempt on hm-core) WITHOUT ever sending an
     * {@code Authorization: Bearer} header. When {@code expectedCoreId} is non-empty,
     * rejects a colliding foreign Core whose {@code core_id} does not match.
     */
    public static boolean probeCoreHttp(String host, int webPort, String expectedCoreId, int timeoutMs) {
        try {
            String expId = expectedCoreId != null ? expectedCoreId.trim() : "";
            HttpResult authRes = httpRequest("GET", host, webPort, "/api/v0/auth", null, null, timeoutMs);
            if (authRes != null && authRes.code == 200 && authRes.body != null && authRes.body.contains("configured")) {
                if (!expId.isEmpty() && authRes.body.trim().startsWith("{")) {
                    Map<String, Object> obj = MiniJson.parseObject(authRes.body);
                    String actualCoreId = MiniJson.getString(obj, "core_id", "").trim();
                    if (!actualCoreId.equalsIgnoreCase(expId)) {
                        return false;
                    }
                }
                return true;
            }
            if (!expId.isEmpty()) {
                return false;
            }
            HttpResult stRes = httpRequest("GET", host, webPort, "/api/v0/status", null, null, timeoutMs);
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
        return fetchRoomSummary(host, webPort, gatewayPort, fallbackName, bearerToken, null);
    }

    /**
     * Identity-verified room summary fetch (P0-1 fail-closed): NEVER transmits a saved
     * {@code bearerToken} unless {@code expectedCoreId} is pinned and verified via unauthenticated
     * {@code GET /api/v0/auth} first, and re-verifies {@code core_id} in {@code /api/v0/status}.
     */
    public static RoomNodeSummary fetchRoomSummary(
            String host,
            int webPort,
            int gatewayPort,
            String fallbackName,
            String bearerToken,
            String expectedCoreId) {
        return fetchRoomSummary(host, webPort, gatewayPort, fallbackName, bearerToken, expectedCoreId, 1600);
    }

    /**
     * Identity-verified room summary fetch with explicit per-request HTTP timeout budget.
     */
    public static RoomNodeSummary fetchRoomSummary(
            String host,
            int webPort,
            int gatewayPort,
            String fallbackName,
            String bearerToken,
            String expectedCoreId,
            int perRequestTimeoutMs) {
        int statusTimeoutMs = Math.max(400, perRequestTimeoutMs);
        int authTimeoutMs = Math.max(400, Math.min(statusTimeoutMs, 1200));
        int modeTimeoutMs = Math.max(400, Math.min(statusTimeoutMs, 1400));
        String label = (fallbackName != null && !fallbackName.trim().isEmpty()) ? fallbackName.trim() : host;
        if (host == null || host.trim().isEmpty()) {
            return new RoomNodeSummary("", webPort, gatewayPort, label, "Home Mate",
                    false, false, false, false, "", "", "half", "", 0, "unconfigured");
        }
        try {
            String rawToken = sanitizeBearerToken(bearerToken);
            String expId = expectedCoreId != null ? expectedCoreId.trim() : "";
            String effectiveToken = "";
            if (!rawToken.isEmpty() && !expId.isEmpty()) {
                HttpResult preAuth = httpRequest("GET", host, webPort, "/api/v0/auth", null, null, authTimeoutMs);
                if (preAuth == null || preAuth.code != 200 || preAuth.body == null || !preAuth.body.trim().startsWith("{")) {
                    return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                            false, false, false, false, "", "", "half", "", 0, "unreachable", expId, false, null, false);
                }
                Map<String, Object> preObj = MiniJson.parseObject(preAuth.body);
                String actualCoreId = MiniJson.getString(preObj, "core_id", "").trim();
                if (!actualCoreId.equalsIgnoreCase(expId)) {
                    return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                            false, false, false, false, "", "", "half", "", 0, "core_id_mismatch", expId, false, null, false);
                }
                effectiveToken = rawToken;
            }
            HttpResult statusRes = httpRequest("GET", host, webPort, "/api/v0/status", null, effectiveToken, statusTimeoutMs);
            if (statusRes == null) {
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        false, false, false, false, "", "", "half", "", 0, "unreachable", expId, false, null, false);
            }
            if (isAuthStatusCode(statusRes.code)) {
                String coreId = "";
                boolean lanExempt = false;
                boolean voiceOpen = false;
                try {
                    HttpResult authRes = httpRequest("GET", host, webPort, "/api/v0/auth", null, null, authTimeoutMs);
                    if (authRes != null && authRes.code == 200 && authRes.body != null && authRes.body.trim().startsWith("{")) {
                        Map<String, Object> authObj = MiniJson.parseObject(authRes.body);
                        coreId = MiniJson.getString(authObj, "core_id", "");
                        lanExempt = MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
                        voiceOpen = MiniJson.getBoolean(authObj, "voice_gateway_open", false);
                    }
                } catch (Throwable ignored) {
                }
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        true, true, voiceOpen, false, "", "", "half", "", 0, "HTTP " + statusRes.code, coreId, lanExempt, null);
            }
            if (statusRes.code < 200 || statusRes.code >= 300 || statusRes.body == null) {
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        false, false, false, false, "", "", "half", "", 0, "HTTP " + statusRes.code, expId, false, null, false);
            }
            Map<String, Object> statusObj = MiniJson.parseObject(statusRes.body);
            String assistantName = MiniJson.getString(statusObj, "assistant_name", "Home Mate");
            String coreId = MiniJson.getString(statusObj, "core_id", "");
            if (!expId.isEmpty() && !coreId.isEmpty() && !coreId.equalsIgnoreCase(expId)) {
                return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                        false, false, false, false, "", "", "half", "", 0, "core_id_mismatch", expId, false, null, false);
            }
            Map<String, Object> authObj = MiniJson.getObject(statusObj, "auth");
            boolean lanExempt = authObj != null && MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
            boolean voiceOpen = CoreDiscovery.inferVoiceGatewayOpenFromStatus(host, statusObj);
            List<DeviceInfo> devs = parseDevicesFromStatus(statusObj);
            String activeDev = "";
            Double activeVolume = null;
            for (DeviceInfo d : devs) {
                if (d.connected) {
                    activeDev = d.id;
                    activeVolume = d.volume;
                    break;
                }
            }
            if (activeDev.isEmpty() && !devs.isEmpty()) {
                activeDev = devs.get(0).id;
                activeVolume = devs.get(0).volume;
            }

            ModeState ms = new ModeState("half", false, true, "off", false, "", "");
            try {
                String modePath = buildModeGetPath(activeDev);
                HttpResult modeRes = httpRequest("GET", host, webPort, modePath, null, effectiveToken, modeTimeoutMs);
                if (modeRes != null && modeRes.code == 200 && modeRes.body != null) {
                    ms = parseModeResponse(MiniJson.parseObject(modeRes.body), activeDev);
                }
            } catch (Throwable ignored) {
            }

            return new RoomNodeSummary(
                    host, webPort, gatewayPort, label, assistantName,
                    true, false, voiceOpen,
                    ms.musicActive, ms.musicTitle, ms.musicSource,
                    ms.duplex, activeDev, devs.size(), null, coreId, lanExempt, activeVolume, ms.musicPaused);
        } catch (Throwable t) {
            return new RoomNodeSummary(host, webPort, gatewayPort, label, "Home Mate",
                    false, false, false, false, "", "", "half", "", 0, t.getMessage(),
                    expectedCoreId != null ? expectedCoreId.trim() : "", false, null, false);
        }
    }

    /**
     * Fetches summaries for all Whole-House room targets concurrently with bounded per-host probe
     * timeouts and a shared deadline. Guarantees EVERY target emits a card — constructing an
     * explicit offline {@link RoomNodeSummary} on timeout/failure instead of silently dropping it (P1-5).
     */
    public static List<RoomNodeSummary> fetchRoomSummariesConcurrently(
            List<RoomTarget> targets,
            long totalWaitBudgetMs) {
        return fetchRoomSummariesConcurrently(targets, totalWaitBudgetMs, new RoomProbeFn() {
            @Override
            public RoomNodeSummary probe(
                    String host,
                    int webPort,
                    int gatewayPort,
                    String fallbackName,
                    String bearerToken,
                    String expectedCoreId,
                    int perRequestTimeoutMs) {
                return fetchRoomSummary(host, webPort, gatewayPort, fallbackName, bearerToken, expectedCoreId, perRequestTimeoutMs);
            }
        });
    }

    public static List<RoomNodeSummary> fetchRoomSummariesConcurrently(
            List<RoomTarget> targets,
            long totalWaitBudgetMs,
            final RoomProbeFn probeFn) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }
        final long budgetMs = Math.max(200L, totalWaitBudgetMs);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(6, targets.size())));
        List<java.util.concurrent.Future<RoomNodeSummary>> futures =
                new ArrayList<java.util.concurrent.Future<RoomNodeSummary>>(targets.size());
        try {
            for (final RoomTarget target : targets) {
                futures.add(pool.submit(new java.util.concurrent.Callable<RoomNodeSummary>() {
                    @Override
                    public RoomNodeSummary call() {
                        int routeCount = 1 + target.alternateHosts.size();
                        int perHostBudget = routeCount > 1 ? 1100 : 1500;
                        RoomNodeSummary primary = probeFn.probe(
                                target.host, target.webPort, target.gatewayPort,
                                target.displayName, target.bearerToken, target.coreId, perHostBudget);
                        if (primary != null && primary.online) {
                            return primary;
                        }
                        for (String alt : target.alternateHosts) {
                            if (Thread.currentThread().isInterrupted()) {
                                break;
                            }
                            RoomNodeSummary fallback = probeFn.probe(
                                    alt, target.webPort, target.gatewayPort,
                                    target.displayName, target.bearerToken, target.coreId, perHostBudget);
                            if (fallback != null && fallback.online) {
                                return fallback;
                            }
                        }
                        return primary != null
                                ? primary
                                : RoomNodeSummary.offline(
                                        target.host, target.webPort, target.gatewayPort,
                                        target.displayName, target.coreId, "unreachable");
                    }
                }));
            }

            List<RoomNodeSummary> results = new ArrayList<RoomNodeSummary>(targets.size());
            long deadlineMs = System.currentTimeMillis() + budgetMs;
            for (int i = 0; i < targets.size(); i++) {
                RoomTarget target = targets.get(i);
                java.util.concurrent.Future<RoomNodeSummary> f = futures.get(i);
                long remainingMs = Math.max(1L, deadlineMs - System.currentTimeMillis());
                try {
                    RoomNodeSummary s = f.get(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (s != null) {
                        results.add(s);
                    } else {
                        results.add(RoomNodeSummary.offline(
                                target.host, target.webPort, target.gatewayPort,
                                target.displayName, target.coreId, "unreachable"));
                    }
                } catch (java.util.concurrent.TimeoutException te) {
                    f.cancel(true);
                    results.add(RoomNodeSummary.offline(
                            target.host, target.webPort, target.gatewayPort,
                            target.displayName, target.coreId, "timeout"));
                } catch (Throwable t) {
                    f.cancel(true);
                    results.add(RoomNodeSummary.offline(
                            target.host, target.webPort, target.gatewayPort,
                            target.displayName, target.coreId, "unreachable"));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    public static String buildModeGetPath(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return "/api/v0/mode";
        }
        try {
            return "/api/v0/mode?device=" + URLEncoder.encode(deviceId.trim(), "UTF-8");
        } catch (IOException ignored) {
            return "/api/v0/mode";
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
        return fetchSnapshot(host, webPort, deviceId, bearerToken, includeFleetAndDevices, true);
    }

    public static CoreSnapshot fetchSnapshot(
            String host,
            int webPort,
            String deviceId,
            String bearerToken,
            boolean includeFleetAndDevices,
            boolean allowSiblingMediaFallback) {
        return fetchSnapshot(host, webPort, deviceId, bearerToken, includeFleetAndDevices, allowSiblingMediaFallback, null);
    }

    public static CoreSnapshot fetchSnapshot(
            String host,
            int webPort,
            String deviceId,
            String bearerToken,
            boolean includeFleetAndDevices,
            boolean allowSiblingMediaFallback,
            String expectedCoreId) {
        if (host == null || host.trim().isEmpty()) {
            return CoreSnapshot.offline("unconfigured");
        }
        try {
            String effectiveToken = sanitizeBearerToken(bearerToken);
            String expId = expectedCoreId != null ? expectedCoreId.trim() : "";
            // Security (P0-1): verify unauthenticated /api/v0/auth BEFORE sending Bearer token
            // so polling a colliding RFC1918 IP on foreign Wi-Fi never leaks credentials.
            if (!effectiveToken.isEmpty()) {
                HttpResult preAuth = httpRequest("GET", host, webPort, "/api/v0/auth", null, null, 1500);
                if (preAuth == null || preAuth.code != 200 || preAuth.body == null || !preAuth.body.trim().startsWith("{")) {
                    return CoreSnapshot.offline("unreachable");
                }
                Map<String, Object> preObj = MiniJson.parseObject(preAuth.body);
                if (!preObj.containsKey("configured")) {
                    return CoreSnapshot.offline("not_home_mate_core");
                }
                if (!expId.isEmpty()) {
                    String actualCoreId = MiniJson.getString(preObj, "core_id", "").trim();
                    if (!actualCoreId.equalsIgnoreCase(expId)) {
                        return CoreSnapshot.offline("core_id_mismatch");
                    }
                }
            }
            // 1. /api/v0/mode?device=<id> returns device-scoped duplex, barge_in, wake_ack, wake_arbitration & media
            String modePath = buildModeGetPath(deviceId);
            HttpResult modeRes = httpRequest("GET", host, webPort, modePath, null, effectiveToken, 2200);
            if (modeRes == null) {
                return CoreSnapshot.offline("unreachable");
            }
            if (isAuthStatusCode(modeRes.code)) {
                return CoreSnapshot.unauthorized();
            }
            if (modeRes.code == 429) {
                return CoreSnapshot.offline("rate_limited");
            }
            ModeState ms = (modeRes.code >= 200 && modeRes.code < 300 && modeRes.body != null)
                    ? parseModeResponse(MiniJson.parseObject(modeRes.body), deviceId, allowSiblingMediaFallback)
                    : new ModeState("half", false, true, "off", false, "", "");

            // 2. /api/v0/devices/{id}/conversation already includes last 50 messages directly
            boolean authRequired = false;
            ConversationFetchResult convRes = fetchConversationMessagesWithAuth(host, webPort, deviceId, effectiveToken);
            List<ChatEntry> transcript = convRes.messages;
            if (convRes.authRequired) {
                authRequired = true;
            }

            // 3. Optional status + fleet (needed on Rooms tab or periodic refresh)
            String assistantName = "Home Mate";
            String buildId = "";
            List<DeviceInfo> devices = Collections.emptyList();
            List<FleetNodeInfo> fleetNodes = Collections.emptyList();

            if (includeFleetAndDevices) {
                try {
                    HttpResult statusRes = httpRequest("GET", host, webPort, "/api/v0/status", null, effectiveToken, 2200);
                    if (statusRes != null && isAuthStatusCode(statusRes.code)) {
                        authRequired = true;
                    } else if (statusRes != null && statusRes.code == 200 && statusRes.body != null) {
                        Map<String, Object> statusObj = MiniJson.parseObject(statusRes.body);
                        assistantName = MiniJson.getString(statusObj, "assistant_name", "Home Mate");
                        Map<String, Object> buildObj = MiniJson.getObject(statusObj, "build");
                        buildId = buildObj != null ? MiniJson.getString(buildObj, "id", "") : "";
                        devices = parseDevicesFromStatus(statusObj);
                        // In Manage-Only mode ONLY (allowSiblingMediaFallback == true), if own deviceId
                        // has no conversation yet, read the primary speaker's conversation.
                        // In Voice Gateway mode, NEVER leak another room's private conversation!
                        if (allowSiblingMediaFallback && transcript.isEmpty() && !devices.isEmpty()) {
                            String primaryId = devices.get(0).id;
                            for (DeviceInfo d : devices) {
                                if (d.connected && d.id != null && !d.id.isEmpty()) {
                                    primaryId = d.id;
                                    break;
                                }
                            }
                            ConversationFetchResult primaryConv = fetchConversationMessagesWithAuth(
                                    host, webPort, primaryId, effectiveToken);
                            transcript = primaryConv.messages;
                            if (primaryConv.authRequired) {
                                authRequired = true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    HttpResult fleetRes = httpRequest("GET", host, webPort, "/api/v0/fleet", null, effectiveToken, 1800);
                    if (fleetRes != null && fleetRes.code == 200 && fleetRes.body != null) {
                        fleetNodes = parseFleetNodes(MiniJson.parseObject(fleetRes.body));
                    }
                } catch (Throwable ignored) {
                }
            }

            return new CoreSnapshot(
                    true, authRequired, assistantName, buildId,
                    ms.duplex, ms.bargeIn, ms.wakeAck, ms.wakeArbitration,
                    ms.musicActive, ms.musicPaused, ms.musicTitle, ms.musicSource,
                    devices, fleetNodes, transcript, authRequired ? "HTTP 401 Unauthorized" : null, ms.reflexTable);
        } catch (Throwable t) {
            return CoreSnapshot.offline(t.getMessage());
        }
    }

    public static ModeState parseModeResponse(Map<String, Object> modeObj, String deviceId) {
        return parseModeResponse(modeObj, deviceId, true);
    }

    public static ModeState parseModeResponse(
            Map<String, Object> modeObj,
            String deviceId,
            boolean allowSiblingMediaFallback) {
        String duplex = MiniJson.getString(modeObj, "default_duplex", "half");
        boolean bargeIn = MiniJson.getBoolean(modeObj, "barge_in", false);
        boolean wakeAck = true;
        String wakeArb = "off";
        boolean musicActive = false;
        boolean musicPaused = false;
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

        // Authoritative live media map: prefer modeObj.media[deviceId], fallback to any playing/paused sibling in Manage-Only mode
        Map<String, Object> mediaMap = MiniJson.getObject(modeObj, "media");
        if (mediaMap != null) {
            Map<String, Object> devMedia = (deviceId != null && !deviceId.isEmpty())
                    ? MiniJson.getObject(mediaMap, deviceId) : null;
            if (devMedia != null) {
                String state = MiniJson.getString(devMedia, "state", "");
                musicActive = "playing".equalsIgnoreCase(state) || MiniJson.getBoolean(devMedia, "playing", false);
                musicPaused = !musicActive && "paused".equalsIgnoreCase(state);
                musicTitle = MiniJson.getString(devMedia, "title", "");
                musicSource = MiniJson.getString(devMedia, "source", "");
            } else if (allowSiblingMediaFallback) {
                Map<String, Object> pausedCandidate = null;
                for (Object val : mediaMap.values()) {
                    if (val instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> anyMedia = (Map<String, Object>) val;
                        String state = MiniJson.getString(anyMedia, "state", "");
                        boolean active = "playing".equalsIgnoreCase(state)
                                || MiniJson.getBoolean(anyMedia, "playing", false);
                        if (active) {
                            musicActive = true;
                            musicPaused = false;
                            musicTitle = MiniJson.getString(anyMedia, "title", "");
                            musicSource = MiniJson.getString(anyMedia, "source", "");
                            pausedCandidate = null;
                            break;
                        }
                        if (pausedCandidate == null && "paused".equalsIgnoreCase(state)) {
                            pausedCandidate = anyMedia;
                        }
                    }
                }
                if (!musicActive && pausedCandidate != null) {
                    musicPaused = true;
                    musicTitle = MiniJson.getString(pausedCandidate, "title", "");
                    musicSource = MiniJson.getString(pausedCandidate, "source", "");
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
                        if (dm.containsKey("barge_in")) {
                            bargeIn = MiniJson.getBoolean(dm, "barge_in", bargeIn);
                        }
                        Map<String, Object> devArbObj = MiniJson.getObject(dm, "wake_arbitration");
                        if (devArbObj != null) {
                            wakeArb = MiniJson.getString(devArbObj, "mode", wakeArb);
                        } else if (dm.containsKey("wake_arbitration")) {
                            String devArb = MiniJson.getString(dm, "wake_arbitration", "").trim();
                            if (!devArb.isEmpty()) {
                                wakeArb = devArb;
                            }
                        }
                        break;
                    }
                }
            }
        }
        ReflexSyncTable reflexTable = parseReflexTableFromMode(modeObj, deviceId);
        return new ModeState(duplex, bargeIn, wakeAck, wakeArb, musicActive, musicPaused, musicTitle, musicSource, reflexTable);
    }

    /**
     * Compiles Core's active {@code reflex.words} from {@code /api/v0/mode} into the exact
     * Sherpa-ONNX {@code kw.txt} format ({@code <tokens> @<text>\n}) plus retreat word list,
     * allowing the Android KWS engine to stay byte-aligned with Core's configured vocabulary
     * even under Option 1A LAN-exempt access (where {@code /api/v0/reflex} remains token-gated).
     */
    public static ReflexSyncTable parseReflexTableFromMode(Map<String, Object> modeObj, String deviceId) {
        if (modeObj == null) {
            return null;
        }
        List<Object> devList = MiniJson.getList(modeObj, "devices");
        if (devList == null || devList.isEmpty()) {
            return null;
        }
        List<Object> selectedWords = null;
        List<Object> fallbackWords = null;
        for (Object item : devList) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> dm = (Map<String, Object>) item;
            Map<String, Object> reflex = MiniJson.getObject(dm, "reflex");
            if (reflex == null) {
                continue;
            }
            List<Object> words = MiniJson.getList(reflex, "words");
            if (words == null || words.isEmpty()) {
                continue;
            }
            if (deviceId != null && deviceId.equals(MiniJson.getString(dm, "device", ""))) {
                selectedWords = words;
                break;
            }
            if (fallbackWords == null) {
                fallbackWords = words;
            }
        }
        List<Object> words = selectedWords != null ? selectedWords : fallbackWords;
        if (words == null || words.isEmpty()) {
            return null;
        }
        StringBuilder compiled = new StringBuilder();
        List<String> wakeWords = new ArrayList<String>();
        List<String> retreatWords = new ArrayList<String>();
        for (Object wObj : words) {
            if (!(wObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> wm = (Map<String, Object>) wObj;
            if (!MiniJson.getBoolean(wm, "enabled", true)) {
                continue;
            }
            String tokens = MiniJson.getString(wm, "tokens", "").trim().replaceAll("\\s+", " ");
            String text = MiniJson.getString(wm, "text", "").trim();
            String intent = MiniJson.getString(wm, "intent", "").trim();
            if (tokens.isEmpty() || text.isEmpty()) {
                continue;
            }
            compiled.append(tokens).append(" @").append(text).append('\n');
            if ("retreat".equalsIgnoreCase(intent)) {
                retreatWords.add(text);
            } else if ("wake".equalsIgnoreCase(intent)) {
                wakeWords.add(text);
            }
        }
        if (compiled.length() == 0) {
            return null;
        }
        String compiledStr = compiled.toString();
        return new ReflexSyncTable(compiledStr, wakeWords, retreatWords, NorthboundSession.fnv1a64Hex(compiledStr));
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

    public static final class ChatReply {
        public final boolean ok;
        public final boolean authRequired;
        public final String sessionId;
        public final String answerText;
        public final String error;
        public final String confirmationId;
        public final String confirmationSummary;
        public final String confirmationTool;
        public final String confirmationState;
        public final long confirmationRevision;

        public ChatReply(boolean ok, String sessionId, String answerText, String error) {
            this(ok, sessionId, answerText, error, "", "", "", "", 0L);
        }

        public ChatReply(
                boolean ok,
                String sessionId,
                String answerText,
                String error,
                String confirmationId,
                String confirmationSummary,
                String confirmationTool) {
            this(ok, sessionId, answerText, error, confirmationId, confirmationSummary, confirmationTool, "pending", 1L);
        }

        public ChatReply(
                boolean ok,
                String sessionId,
                String answerText,
                String error,
                String confirmationId,
                String confirmationSummary,
                String confirmationTool,
                String confirmationState,
                long confirmationRevision) {
            this.ok = ok;
            this.authRequired = "http_401".equals(error) || "http_403".equals(error);
            this.sessionId = sessionId != null ? sessionId : "";
            this.answerText = answerText != null ? answerText : "";
            this.error = error;
            this.confirmationId = confirmationId != null ? confirmationId : "";
            this.confirmationSummary = confirmationSummary != null ? confirmationSummary : "";
            this.confirmationTool = confirmationTool != null ? confirmationTool : "";
            this.confirmationState = confirmationState != null && !confirmationState.isEmpty() ? confirmationState : "pending";
            this.confirmationRevision = Math.max(0L, confirmationRevision);
        }

        public boolean hasConfirmation() {
            return !confirmationId.isEmpty();
        }
    }

    /**
     * Transitions an hm-core v1 confirmation action ({@code POST /api/v1/confirmations/{id}/...})
     * using strict {@code {"expected_state": ..., "expected_revision": ...}} optimistic locking.
     * On {@code approve == true}, executes the required two-step {@code /approve} -> {@code /execute}
     * sequence; on {@code approve == false}, transitions directly to {@code /reject}.
     */
    public static boolean decideConfirmation(
            String host,
            int webPort,
            String actionId,
            String expectedState,
            long expectedRevision,
            boolean approve,
            String bearerToken) {
        return decideConfirmationWithResult(
                host, webPort, actionId, expectedState, expectedRevision, approve, bearerToken).ok;
    }

    public static ActionOutcome decideConfirmationWithResult(
            String host,
            int webPort,
            String actionId,
            String expectedState,
            long expectedRevision,
            boolean approve,
            String bearerToken) {
        if (host == null || host.trim().isEmpty() || actionId == null || actionId.trim().isEmpty()) {
            return ActionOutcome.failure(0);
        }
        String initState = (expectedState != null && !expectedState.trim().isEmpty()) ? expectedState.trim() : "pending";
        long initRev = Math.max(0L, expectedRevision);
        try {
            String encId = encodePathSegment(actionId.trim());
            Map<String, Object> bodyMap = new LinkedHashMap<String, Object>();
            bodyMap.put("expected_state", initState);
            bodyMap.put("expected_revision", initRev);
            String path = "/api/v1/confirmations/" + encId + (approve ? "/approve" : "/reject");
            HttpResult res = httpRequest("POST", host, webPort, path, MiniJson.stringify(bodyMap), bearerToken, 6000);
            if (res == null || res.code < 200 || res.code >= 300) {
                return ActionOutcome.failure(res != null ? res.code : 0);
            }
            if (!approve) {
                return ActionOutcome.success(res.code);
            }
            // hm-core /approve transitions pending -> approved; execute using returned state & revision.
            String nextState = "approved";
            long nextRev = initRev + 1L;
            if (res.body != null && res.body.trim().startsWith("{")) {
                Map<String, Object> approvedObj = MiniJson.parseObject(res.body);
                String parsedState = MiniJson.getString(approvedObj, "state", "").trim();
                if (!parsedState.isEmpty()) {
                    nextState = parsedState;
                }
                long parsedRev = MiniJson.getLong(approvedObj, "revision", -1L);
                if (parsedRev >= 0L) {
                    nextRev = parsedRev;
                }
            }
            Map<String, Object> execBody = new LinkedHashMap<String, Object>();
            execBody.put("expected_state", nextState);
            execBody.put("expected_revision", nextRev);
            HttpResult execRes = httpRequest(
                    "POST", host, webPort,
                    "/api/v1/confirmations/" + encId + "/execute",
                    MiniJson.stringify(execBody),
                    bearerToken, 10_000);
            if (execRes != null && execRes.code >= 200 && execRes.code < 300) {
                return ActionOutcome.success(execRes.code);
            }
            return ActionOutcome.failure(execRes != null ? execRes.code : 0);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
        }
    }

    public static String buildChatPostBody(String text) {
        return buildChatPostBody(text, null);
    }

    public static String buildChatPostBody(String text, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("text", text != null ? text.trim() : "");
        body.put("source", "android_app");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            body.put("session_id", sessionId.trim());
        }
        return MiniJson.stringify(body);
    }

    public static String buildVolumePostBody(double linearVolume) {
        double clamped = Math.max(0.0, Math.min(1.0, linearVolume));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("value", Math.round(clamped * 100.0) / 100.0);
        return MiniJson.stringify(body);
    }

    public static String buildMediaControlPostBody(String action) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", action != null ? action.trim().toLowerCase(java.util.Locale.US) : "");
        return MiniJson.stringify(body);
    }

    public static boolean stopRemoteDevice(String host, int webPort, String deviceId, String bearerToken) {
        return stopRemoteDeviceWithResult(host, webPort, deviceId, bearerToken).ok;
    }

    public static ActionOutcome stopRemoteDeviceWithResult(
            String host, int webPort, String deviceId, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return ActionOutcome.failure(0);
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/stop",
                    "{}", bearerToken, 2500);
            if (res != null && res.code >= 200 && res.code < 300) {
                return ActionOutcome.success(res.code);
            }
            return ActionOutcome.failure(res != null ? res.code : 0);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
        }
    }

    public static boolean pauseRemoteDevice(String host, int webPort, String deviceId, String bearerToken) {
        return pauseRemoteDeviceWithResult(host, webPort, deviceId, bearerToken).ok;
    }

    public static ActionOutcome pauseRemoteDeviceWithResult(
            String host, int webPort, String deviceId, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return ActionOutcome.failure(0);
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/program/pause",
                    "{}", bearerToken, 2500);
            if (res == null || res.code < 200 || res.code >= 300) {
                return ActionOutcome.failure(res != null ? res.code : 0);
            }
            if (res.body != null && !res.body.trim().isEmpty()) {
                Map<String, Object> obj = MiniJson.parseObject(res.body);
                if (obj.containsKey("paused")) {
                    return MiniJson.getBoolean(obj, "paused", false)
                            ? ActionOutcome.success(res.code)
                            : ActionOutcome.failure(res.code);
                }
            }
            return ActionOutcome.success(res.code);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
        }
    }

    /**
     * Direct device media control ({@code pause} | {@code resume} | {@code next} |
     * {@code previous} | {@code stop}) via {@code POST /api/v0/devices/{id}/media/control},
     * with clean fallback to legacy {@code /program/pause}, {@code /program/resume},
     * and {@code /stop} endpoints on older Cores.
     */
    public static boolean controlRemoteMedia(
            String host, int webPort, String deviceId, String action, String bearerToken) {
        return controlRemoteMediaWithResult(host, webPort, deviceId, action, bearerToken).ok;
    }

    public static ActionOutcome controlRemoteMediaWithResult(
            String host, int webPort, String deviceId, String action, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty() || action == null || action.trim().isEmpty()) {
            return ActionOutcome.failure(0);
        }
        String norm = action.trim().toLowerCase(java.util.Locale.US);
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/media/control",
                    buildMediaControlPostBody(norm), bearerToken, 3000);
            if (res != null && res.code >= 200 && res.code < 300) {
                if (res.body != null && !res.body.trim().isEmpty()) {
                    Map<String, Object> obj = MiniJson.parseObject(res.body);
                    return MiniJson.getBoolean(obj, "ok", true)
                            ? ActionOutcome.success(res.code)
                            : ActionOutcome.failure(res.code);
                }
                return ActionOutcome.success(res.code);
            }
            // Fallback for older Cores without /media/control
            if (res != null && res.code == 404) {
                if ("pause".equals(norm)) {
                    return pauseRemoteDeviceWithResult(host, webPort, deviceId, bearerToken);
                }
                if ("resume".equals(norm)) {
                    HttpResult r2 = httpRequest("POST", host, webPort,
                            "/api/v0/devices/" + encodePathSegment(deviceId) + "/program/resume",
                            "{}", bearerToken, 2500);
                    if (r2 != null && r2.code >= 200 && r2.code < 300 && r2.body != null) {
                        return MiniJson.getBoolean(MiniJson.parseObject(r2.body), "resumed", false)
                                ? ActionOutcome.success(r2.code)
                                : ActionOutcome.failure(r2.code);
                    }
                    return ActionOutcome.failure(r2 != null ? r2.code : 0);
                }
                if ("stop".equals(norm)) {
                    return stopRemoteDeviceWithResult(host, webPort, deviceId, bearerToken);
                }
            }
            return ActionOutcome.failure(res != null ? res.code : 0);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
        }
    }

    public static boolean setRemoteDeviceVolume(
            String host, int webPort, String deviceId, double linearVolume, String bearerToken) {
        return setRemoteDeviceVolumeWithResult(host, webPort, deviceId, linearVolume, bearerToken).ok;
    }

    public static ActionOutcome setRemoteDeviceVolumeWithResult(
            String host, int webPort, String deviceId, double linearVolume, String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return ActionOutcome.failure(0);
        }
        try {
            HttpResult res = httpRequest("POST", host, webPort,
                    "/api/v0/devices/" + encodePathSegment(deviceId) + "/volume",
                    buildVolumePostBody(linearVolume), bearerToken, 2500);
            if (res != null && res.code >= 200 && res.code < 300) {
                return ActionOutcome.success(res.code);
            }
            return ActionOutcome.failure(res != null ? res.code : 0);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
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
        return updateDeviceModeWithResult(
                host, webPort, deviceId, duplexRequested, bargeIn, wakeAck, wakeArbitration, bearerToken).ok;
    }

    public static ActionOutcome updateDeviceModeWithResult(
            String host,
            int webPort,
            String deviceId,
            String duplexRequested,
            Boolean bargeIn,
            Boolean wakeAck,
            String wakeArbitration,
            String bearerToken) {
        if (host == null || deviceId == null || deviceId.isEmpty()) {
            return ActionOutcome.failure(0);
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
                    3000);
            if (res == null || res.code < 200 || res.code >= 300) {
                return ActionOutcome.failure(res != null ? res.code : 0);
            }
            if (res.body != null && !res.body.trim().isEmpty()) {
                Map<String, Object> obj = MiniJson.parseObject(res.body);
                if (obj.containsKey("error") && obj.get("error") != null) {
                    return ActionOutcome.failure(res.code);
                }
            }
            return ActionOutcome.success(res.code);
        } catch (Throwable ignored) {
            return ActionOutcome.failure(0);
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
        return sendChatTextWithReply(host, webPort, null, bearerToken, text).ok;
    }

    public static ChatReply parseChatSseStream(BufferedReader br) throws IOException {
        String sessionId = "";
        String answer = "";
        String error = null;
        String confirmationId = "";
        String confirmationSummary = "";
        String confirmationTool = "";
        String confirmationState = "pending";
        long confirmationRevision = 1L;
        StringBuilder streamed = new StringBuilder();
        boolean sawEvent = false;
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                Map<String, Object> ev = MiniJson.parseObject(payload);
                sawEvent = true;
                String type = MiniJson.getString(ev, "type", "");
                if ("session".equals(type)) {
                    String sid = MiniJson.getString(ev, "session_id", "");
                    if (!sid.isEmpty()) {
                        sessionId = sid;
                    }
                } else if ("answer".equals(type)) {
                    String txt = MiniJson.getString(ev, "text", "");
                    if (!txt.isEmpty()) {
                        answer = txt;
                    }
                } else if ("token".equals(type)) {
                    String tok = MiniJson.getString(ev, "text", "");
                    if (!tok.isEmpty()) {
                        streamed.append(tok);
                    }
                } else if ("fact".equals(type)) {
                    String fact = MiniJson.getString(ev, "text", "");
                    if (!fact.isEmpty()) {
                        if (streamed.length() > 0) {
                            streamed.append('\n');
                        }
                        streamed.append(fact);
                    }
                } else if ("confirmation_required".equals(type)) {
                    Map<String, Object> action = MiniJson.getObject(ev, "action");
                    if (action != null) {
                        confirmationId = MiniJson.getString(action, "id", "").trim();
                        String st = MiniJson.getString(action, "state", "").trim();
                        if (!st.isEmpty()) {
                            confirmationState = st;
                        }
                        long rev = MiniJson.getLong(action, "revision", -1L);
                        if (rev >= 0L) {
                            confirmationRevision = rev;
                        }
                        Map<String, Object> preview = MiniJson.getObject(action, "preview");
                        if (preview != null) {
                            confirmationTool = MiniJson.getString(preview, "tool", "").trim();
                            String service = MiniJson.getString(preview, "service", "").trim();
                            String method = MiniJson.getString(preview, "method", "").trim();
                            String operation = MiniJson.getString(preview, "operation", "").trim();
                            String target = MiniJson.getString(preview, "target", "").trim();
                            StringBuilder sb = new StringBuilder();
                            if (!service.isEmpty()) {
                                sb.append('[').append(service).append("] ");
                            }
                            if (!method.isEmpty()) {
                                sb.append(method).append(' ');
                            }
                            if (!operation.isEmpty()) {
                                sb.append(operation);
                            } else if (!confirmationTool.isEmpty()) {
                                sb.append(confirmationTool);
                            }
                            if (!target.isEmpty()) {
                                sb.append(" (").append(target).append(')');
                            }
                            confirmationSummary = sb.toString().trim();
                        }
                        if (confirmationTool.isEmpty()) {
                            confirmationTool = MiniJson.getString(action, "tool", "").trim();
                        }
                        if (confirmationSummary.isEmpty()) {
                            confirmationSummary = MiniJson.getString(action, "summary", "").trim();
                        }
                        if (confirmationSummary.isEmpty()) {
                            confirmationSummary = MiniJson.getString(action, "description", "").trim();
                        }
                        if (confirmationSummary.isEmpty() && !confirmationTool.isEmpty()) {
                            confirmationSummary = confirmationTool;
                        }
                    }
                } else if ("error".equals(type)) {
                    error = MiniJson.getString(ev, "message", "chat_error");
                } else if ("cancelled".equals(type)) {
                    error = "cancelled";
                } else if ("turn_end".equals(type)) {
                    String status = MiniJson.getString(ev, "status", "");
                    if ("failed".equalsIgnoreCase(status) && (error == null || error.isEmpty())) {
                        error = "turn_failed";
                    }
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        String finalText = sanitizeDialogueText(!answer.isEmpty() ? answer : streamed.toString());
        boolean ok = (error == null) && (sawEvent || !finalText.isEmpty() || !confirmationId.isEmpty());
        return new ChatReply(
                ok, sessionId, finalText, error,
                confirmationId, confirmationSummary, confirmationTool,
                confirmationState, confirmationRevision);
    }

    public static ChatReply sendChatTextWithReply(
            String host, int webPort, String sessionId, String bearerToken, String text) {
        if (host == null || text == null || text.trim().isEmpty()) {
            return new ChatReply(false, sessionId, "", "empty_text");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + webPort + "/api/v0/chat");
            conn = isPrivateOrTailscaleHost(host)
                    ? (HttpURLConnection) url.openConnection(Proxy.NO_PROXY)
                    : (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30_000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            String cleanTok = sanitizeBearerToken(bearerToken);
            if (!cleanTok.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cleanTok);
            }
            conn.setDoOutput(true);
            byte[] bytes = buildChatPostBody(text, sessionId).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return new ChatReply(false, sessionId, "", "http_" + code);
            }
            InputStream is = conn.getInputStream();
            if (is == null) {
                return new ChatReply(true, sessionId, "", null);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                ChatReply reply = parseChatSseStream(br);
                String sid = !reply.sessionId.isEmpty() ? reply.sessionId : (sessionId != null ? sessionId : "");
                return new ChatReply(
                        reply.ok, sid, reply.answerText, reply.error,
                        reply.confirmationId, reply.confirmationSummary, reply.confirmationTool,
                        reply.confirmationState, reply.confirmationRevision);
            }
        } catch (Throwable t) {
            return new ChatReply(false, sessionId, "", t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Deterministically merges raw server transcript entries with locally buffered Manage-Only
     * chat entries. Always takes the raw (unmerged) server list so repeated merges never
     * compound duplicates regardless of history length, while still allowing legitimately
     * repeated user messages (e.g. asking "几点了" twice at different times).
     */
    public static List<ChatEntry> mergeManageOnlyTranscript(
            List<ChatEntry> rawServerEntries,
            List<ChatEntry> localEntries) {
        List<ChatEntry> merged = new ArrayList<ChatEntry>();
        if (rawServerEntries != null) {
            merged.addAll(rawServerEntries);
        }
        if (localEntries == null || localEntries.isEmpty()) {
            return merged;
        }
        for (ChatEntry local : localEntries) {
            boolean matchedOnServer = false;
            if (rawServerEntries != null) {
                for (ChatEntry srv : rawServerEntries) {
                    if (srv.role.equals(local.role) && srv.text.equals(local.text)) {
                        if (srv.tsMs <= 0L || local.tsMs <= 0L
                                || Math.abs(srv.tsMs - local.tsMs) <= 15_000L) {
                            matchedOnServer = true;
                            break;
                        }
                    }
                }
            }
            if (!matchedOnServer) {
                merged.add(local);
            }
        }
        return merged;
    }

    public static List<ChatEntry> parseConversationMessages(Map<String, Object> convObj) {
        String sid = convObj != null ? MiniJson.getString(convObj, "session_id", "").trim() : "";
        return parseConversationMessages(convObj, sid, false);
    }

    public static List<ChatEntry> parseConversationMessages(
            Map<String, Object> convObj, String sessionId, boolean archived) {
        List<ChatEntry> out = new ArrayList<ChatEntry>();
        if (convObj == null) {
            return out;
        }
        String resolvedSid = (sessionId != null && !sessionId.trim().isEmpty())
                ? sessionId.trim()
                : MiniJson.getString(convObj, "session_id", "").trim();
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
                    out.add(new ChatEntry(role, cleaned, ts, resolvedSid, archived));
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

    /**
     * Fetches a device's current conversation plus up to 3 most recent {@code archived_contexts}
     * (in chronological oldest-to-newest order) whenever the current context has fewer than 24
     * clean dialogue messages.
     */
    public static ConversationHistoryResult fetchConversationHistory(
            String host, int webPort, String deviceId, String bearerToken) {
        if (host == null || host.trim().isEmpty() || deviceId == null || deviceId.trim().isEmpty()) {
            return new ConversationHistoryResult(Collections.<ChatEntry>emptyList(), "", false, false);
        }
        String cleanHost = host.trim();
        String cleanToken = sanitizeBearerToken(bearerToken);
        try {
            String encDev = encodePathSegment(deviceId.trim());
            HttpResult convRes = httpRequest(
                    "GET", cleanHost, webPort, "/api/v0/devices/" + encDev + "/conversation",
                    null, cleanToken, 2500);
            if (convRes == null) {
                return new ConversationHistoryResult(Collections.<ChatEntry>emptyList(), "", false, false);
            }
            if (isAuthStatusCode(convRes.code)) {
                return new ConversationHistoryResult(Collections.<ChatEntry>emptyList(), "", true, false);
            }
            if (convRes.code != 200 || convRes.body == null) {
                return new ConversationHistoryResult(Collections.<ChatEntry>emptyList(), "", false, false);
            }
            Map<String, Object> root = MiniJson.parseObject(convRes.body);
            String currentSid = MiniJson.getString(root, "session_id", "").trim();
            if (currentSid.isEmpty()) {
                Map<String, Object> curCtx = MiniJson.getObject(root, "current_context");
                if (curCtx != null) {
                    currentSid = MiniJson.getString(curCtx, "session_id", "").trim();
                }
            }
            List<ChatEntry> currentMsgs = parseConversationMessages(root, currentSid, false);

            List<List<ChatEntry>> archivedNewestFirst = new ArrayList<List<ChatEntry>>();
            if (currentMsgs.size() < 24) {
                List<Object> archivedCtxs = MiniJson.getList(root, "archived_contexts");
                if (archivedCtxs != null && !archivedCtxs.isEmpty()) {
                    List<String> targetArchiveSids = new ArrayList<String>();
                    for (Object item : archivedCtxs) {
                        if (targetArchiveSids.size() >= 3) {
                            break;
                        }
                        String sid = "";
                        if (item instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ctxMap = (Map<String, Object>) item;
                            sid = MiniJson.getString(ctxMap, "session_id", "").trim();
                        } else if (item instanceof String) {
                            sid = ((String) item).trim();
                        }
                        if (!sid.isEmpty() && !sid.equals(currentSid) && !targetArchiveSids.contains(sid)) {
                            targetArchiveSids.add(sid);
                        }
                    }
                    for (String sid : targetArchiveSids) {
                        try {
                            String archPath = "/api/v0/devices/" + encDev
                                    + "/conversation?context_session_id=" + URLEncoder.encode(sid, "UTF-8");
                            HttpResult archRes = httpRequest("GET", cleanHost, webPort, archPath, null, cleanToken, 2200);
                            if (archRes != null && archRes.code == 200 && archRes.body != null) {
                                List<ChatEntry> archMsgs = parseConversationMessages(
                                        MiniJson.parseObject(archRes.body), sid, true);
                                if (!archMsgs.isEmpty()) {
                                    archivedNewestFirst.add(archMsgs);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            List<ChatEntry> combined = new ArrayList<ChatEntry>();
            for (int i = archivedNewestFirst.size() - 1; i >= 0; i--) {
                combined.addAll(archivedNewestFirst.get(i));
            }
            combined.addAll(currentMsgs);
            return new ConversationHistoryResult(combined, currentSid, false, true);
        } catch (Throwable ignored) {
            return new ConversationHistoryResult(Collections.<ChatEntry>emptyList(), "", false, false);
        }
    }

    private static ConversationFetchResult fetchConversationMessagesWithAuth(
            String host, int webPort, String deviceId, String bearerToken) {
        ConversationHistoryResult res = fetchConversationHistory(host, webPort, deviceId, bearerToken);
        return new ConversationFetchResult(res.messages, res.authRequired);
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
                Double vol = null;
                Object rawVol = dm.get("volume");
                if (rawVol instanceof Number) {
                    double dv = ((Number) rawVol).doubleValue();
                    if (!Double.isNaN(dv) && !Double.isInfinite(dv) && dv >= 0.0 && dv <= 1.0) {
                        vol = Double.valueOf(dv);
                    }
                }
                if (!id.isEmpty()) {
                    out.add(new DeviceInfo(id, model, online, aec, profile, vol));
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
        return CoreDiscovery.isPrivateOrTailscaleIp(h)
                || h.startsWith("127.")
                || "localhost".equalsIgnoreCase(h);
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
            int timeoutMs) throws IOException {
        URL url = new URL("http://" + host + ":" + webPort + path);
        HttpURLConnection conn = isPrivateOrTailscaleHost(host)
                ? (HttpURLConnection) url.openConnection(Proxy.NO_PROXY)
                : (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            String cleanTok = sanitizeBearerToken(bearerToken);
            if (!cleanTok.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cleanTok);
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
        } finally {
            conn.disconnect();
        }
    }
}
