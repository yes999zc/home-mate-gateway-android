package homes.mymate.gateway.protocol;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for v2.3.9 {@link CoreApiClient} additions:
 * 1. {@link CoreApiClient#sanitizeBearerToken(String)} edge cases
 * 2. {@link CoreApiClient#fetchConversationHistory} current + archived_contexts fallback & ordering
 * 3. {@link CoreApiClient#verifyBearerToken} & {@link CoreApiClient#clearDeviceConversation}
 * 4. {@link CoreApiClient.RoomNodeSummary#musicPaused} parsing via {@link CoreApiClient#fetchRoomSummary}
 */
public final class CoreApiClientTest {

    public static void main(String[] args) throws Exception {
        testSanitizeBearerToken();
        testFetchConversationHistoryAndArchivedFallback();
        testVerifyBearerTokenAndClearDeviceConversation();
        testVerifyBearerTokenWithIdentityAndCoreIdPinningForRoomSummary();
        testRoomNodeSummaryMusicPaused();
        testReleaseUpdaterGitHubAndMirror();
        System.out.println("PASS: All 6 CoreApiClientTest suites passed.");
    }

    private static void testSanitizeBearerToken() {
        assertTrue("".equals(CoreApiClient.sanitizeBearerToken(null)), "null must sanitize to empty");
        assertTrue("".equals(CoreApiClient.sanitizeBearerToken("   \r\n  ")), "whitespace must sanitize to empty");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("  hm1_abc123 \n")),
                "leading/trailing whitespace & newline must be stripped");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("\"hm1_abc123\"")),
                "double quotes must be stripped");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("'hm1_abc123'")),
                "single quotes must be stripped");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("`hm1_abc123`")),
                "backticks must be stripped");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("Bearer hm1_abc123")),
                "Bearer prefix must be stripped");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("  bearer   \"hm1_abc123\"\r\n")),
                "lowercase bearer + quotes + CRLF must sanitize cleanly");
        assertTrue("hm1_abc123".equals(CoreApiClient.sanitizeBearerToken("\"Bearer hm1_abc123\"")),
                "quoted Bearer prefix must sanitize cleanly");
    }

    private static void testFetchConversationHistoryAndArchivedFallback() throws Exception {
        final List<String> requestedQueries = new ArrayList<String>();
        final boolean[] emptyCurrentMode = new boolean[]{false};
        final boolean[] return401 = new boolean[]{false};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/devices/android-fold/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (return401[0]) {
                        byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(401, resp.length);
                        ex.getResponseBody().write(resp);
                        ex.close();
                        return;
                    }
                    String query = ex.getRequestURI().getRawQuery();
                    requestedQueries.add(query != null ? query : "");
                    String body;
                    if (query == null || query.isEmpty()) {
                        String curMsgs = emptyCurrentMode[0]
                                ? "[]"
                                : "[{\"role\":\"user\",\"content\":\"现在几点\",\"ts_ms\":5000},"
                                + "{\"role\":\"assistant\",\"content\":\"<think>check time</think>\\n[调用 time.now {}]\\n现在晚上八点。\",\"ts_ms\":5100}]";
                        // archived_contexts ordered newest-first (4 archives: S-arch-4 .. S-arch-1)
                        body = "{\"session_id\":\"S-cur\",\"messages\":" + curMsgs + ","
                                + "\"archived_contexts\":["
                                + "{\"session_id\":\"S-arch-4\",\"generation\":4},"
                                + "{\"session_id\":\"S-arch-3\",\"generation\":3},"
                                + "{\"session_id\":\"S-arch-2\",\"generation\":2},"
                                + "{\"session_id\":\"S-arch-1\",\"generation\":1}"
                                + "]}";
                    } else if (query.contains("S-arch-4")) {
                        body = "{\"session_id\":\"S-arch-4\",\"messages\":[{\"role\":\"user\",\"content\":\"归档4问题\",\"ts_ms\":4000},{\"role\":\"assistant\",\"content\":\"归档4回答\",\"ts_ms\":4100}]}";
                    } else if (query.contains("S-arch-3")) {
                        body = "{\"session_id\":\"S-arch-3\",\"messages\":[{\"role\":\"user\",\"content\":\"归档3问题\",\"ts_ms\":3000}]}";
                    } else if (query.contains("S-arch-2")) {
                        body = "{\"session_id\":\"S-arch-2\",\"messages\":[{\"role\":\"user\",\"content\":\"归档2问题\",\"ts_ms\":2000}]}";
                    } else {
                        body = "{\"session_id\":\"S-arch-1\",\"messages\":[{\"role\":\"user\",\"content\":\"不应该抓取的更旧归档1\",\"ts_ms\":1000}]}";
                    }
                    byte[] resp = body.getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // 1. Current has 2 messages (< 24) + 4 archived contexts -> fetches top 3 archives, ordered oldest -> newest!
            CoreApiClient.ConversationHistoryResult res = CoreApiClient.fetchConversationHistory(
                    "127.0.0.1", port, "android-fold", "hm1_tok");
            assertTrue(res.ok && !res.authRequired, "fetchConversationHistory must succeed");
            assertTrue("S-cur".equals(res.currentSessionId), "Expected currentSessionId=S-cur, got " + res.currentSessionId);
            assertTrue(res.messages.size() == 6, "Expected 6 messages (1+1+2 archived + 2 current), got " + res.messages.size());
            // Chronological order check: S-arch-2 -> S-arch-3 -> S-arch-4 -> S-cur
            assertTrue("归档2问题".equals(res.messages.get(0).text) && res.messages.get(0).archived && "S-arch-2".equals(res.messages.get(0).sessionId),
                    "Entry 0 must be oldest fetched archive S-arch-2");
            assertTrue("归档3问题".equals(res.messages.get(1).text) && res.messages.get(1).archived && "S-arch-3".equals(res.messages.get(1).sessionId),
                    "Entry 1 must be S-arch-3");
            assertTrue("归档4问题".equals(res.messages.get(2).text) && res.messages.get(2).archived && "S-arch-4".equals(res.messages.get(2).sessionId),
                    "Entry 2 must be S-arch-4 user");
            assertTrue("归档4回答".equals(res.messages.get(3).text) && res.messages.get(3).archived && "S-arch-4".equals(res.messages.get(3).sessionId),
                    "Entry 3 must be S-arch-4 assistant");
            assertTrue("现在几点".equals(res.messages.get(4).text) && !res.messages.get(4).archived && "S-cur".equals(res.messages.get(4).sessionId),
                    "Entry 4 must be current user message");
            assertTrue("现在晚上八点。".equals(res.messages.get(5).text) && !res.messages.get(5).archived,
                    "Entry 5 must sanitize <think> and [调用], got: " + res.messages.get(5).text);
            // Verify S-arch-1 (4th oldest archive) was NOT fetched (cap = 3)
            for (String q : requestedQueries) {
                assertTrue(!q.contains("S-arch-1"), "Must fetch at most 3 archived contexts, but fetched: " + q);
            }

            // 2. Empty current session (after retreat / context TTL rotation) still returns archived turns!
            emptyCurrentMode[0] = true;
            CoreApiClient.ConversationHistoryResult afterReset = CoreApiClient.fetchConversationHistory(
                    "127.0.0.1", port, "android-fold", "hm1_tok");
            assertTrue(afterReset.ok && afterReset.messages.size() == 4,
                    "Empty current session must still show recent archived messages, got " + afterReset.messages.size());

            // 3. HTTP 401 -> authRequired=true, ok=false
            return401[0] = true;
            CoreApiClient.ConversationHistoryResult unauthed = CoreApiClient.fetchConversationHistory(
                    "127.0.0.1", port, "android-fold", "");
            assertTrue(!unauthed.ok && unauthed.authRequired, "401 must return authRequired=true, ok=false");
        } finally {
            server.stop(0);
        }
    }

    private static void testVerifyBearerTokenAndClearDeviceConversation() throws Exception {
        final AtomicReference<String> seenClearBody = new AtomicReference<String>("");
        final AtomicReference<String> seenClearAuth = new AtomicReference<String>("");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    if ("Bearer hm1_valid_token".equals(auth)) {
                        byte[] resp = "{\"ok\":true,\"assistant_name\":\"小智\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, resp.length);
                        ex.getResponseBody().write(resp);
                    } else {
                        byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(401, resp.length);
                        ex.getResponseBody().write(resp);
                    }
                    ex.close();
                }
            });
            final AtomicReference<String> seenClearMethod = new AtomicReference<String>("");
            server.createContext("/api/v0/devices/android-fold/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    seenClearMethod.set(ex.getRequestMethod());
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    seenClearAuth.set(auth);
                    String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    seenClearBody.set(bodyStr);
                    if (!"Bearer hm1_valid_token".equals(auth)) {
                        byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(401, resp.length);
                        ex.getResponseBody().write(resp);
                    } else if (!bodyStr.contains("\"S-cur\"")) {
                        byte[] resp = "{\"error\":\"SESSION_CHANGED\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(409, resp.length);
                        ex.getResponseBody().write(resp);
                    } else {
                        byte[] resp = "{\"session_id\":\"S-cur\",\"cleared\":4}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, resp.length);
                        ex.getResponseBody().write(resp);
                    }
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // Valid token with messy whitespace, quotes, and Bearer prefix -> sanitized & accepted!
            CoreApiClient.ActionOutcome okOut = CoreApiClient.verifyBearerToken(
                    "127.0.0.1", port, "  Bearer \"hm1_valid_token\"\n");
            assertTrue(okOut.ok && !okOut.authRequired && okOut.httpCode == 200,
                    "verifyBearerToken must sanitize and succeed on HTTP 200");

            // Wrong token -> authRequired=true
            CoreApiClient.ActionOutcome badOut = CoreApiClient.verifyBearerToken(
                    "127.0.0.1", port, "hm1_wrong");
            assertTrue(!badOut.ok && badOut.authRequired && badOut.httpCode == 401,
                    "verifyBearerToken must report authRequired=true on 401");

            // Unreachable port -> ok=false, authRequired=false
            int deadPort;
            try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                deadPort = ss.getLocalPort();
            }
            CoreApiClient.ActionOutcome unreachableOut = CoreApiClient.verifyBearerToken(
                    "127.0.0.1", deadPort, "hm1_valid_token");
            assertTrue(!unreachableOut.ok && !unreachableOut.authRequired,
                    "Unreachable port must report ok=false, authRequired=false");

            // clearDeviceConversation sends DELETE /api/v0/devices/{id}/conversation with exact {"expected_session_id": "..."}
            CoreApiClient.ActionOutcome clearOut = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "android-fold", "S-cur", "Bearer hm1_valid_token");
            assertTrue(clearOut.ok && clearOut.httpCode == 200, "clearDeviceConversation must succeed on HTTP 200");
            assertTrue("DELETE".equals(seenClearMethod.get()),
                    "clearDeviceConversation must use HTTP DELETE, got: " + seenClearMethod.get());
            assertTrue("Bearer hm1_valid_token".equals(seenClearAuth.get()),
                    "clearDeviceConversation must send sanitized Bearer header");
            Map<String, Object> parsedClear = MiniJson.parseObject(seenClearBody.get());
            assertTrue("S-cur".equals(MiniJson.getString(parsedClear, "expected_session_id", ""))
                            && parsedClear.size() == 1,
                    "clearDeviceConversation must send strict expected_session_id JSON body, got: " + seenClearBody.get());

            // 401 Unauthorized on DELETE -> authRequired=true, ok=false
            CoreApiClient.ActionOutcome clear401 = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "android-fold", "S-cur", "hm1_wrong");
            assertTrue(!clear401.ok && clear401.authRequired && clear401.httpCode == 401,
                    "clearDeviceConversation 401 must return authRequired=true");

            // 409 Conflict (SESSION_CHANGED) on DELETE -> ok=false, authRequired=false, httpCode=409
            CoreApiClient.ActionOutcome clear409 = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "android-fold", "S-stale", "Bearer hm1_valid_token");
            assertTrue(!clear409.ok && !clear409.authRequired && clear409.httpCode == 409,
                    "clearDeviceConversation 409 must return ok=false, authRequired=false, httpCode=409");

            // includeArchived=true sends {"expected_session_id": "S-cur", "include_archived": true}
            CoreApiClient.ActionOutcome clearAllOut = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "android-fold", "S-cur", true, "Bearer hm1_valid_token");
            assertTrue(clearAllOut.ok && clearAllOut.httpCode == 200,
                    "clearDeviceConversation(includeArchived=true) must succeed on HTTP 200");
            Map<String, Object> parsedClearAll = MiniJson.parseObject(seenClearBody.get());
            assertTrue("S-cur".equals(MiniJson.getString(parsedClearAll, "expected_session_id", ""))
                            && MiniJson.getBoolean(parsedClearAll, "include_archived", false),
                    "clearDeviceConversation(includeArchived=true) must send include_archived=true, got: " + seenClearBody.get());

            // Legacy Core fallback: when server rejects include_archived with HTTP 422, retry with legacy body
            final int[] legacyCalls = new int[]{0};
            server.createContext("/api/v0/devices/legacy-dot2/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    legacyCalls[0]++;
                    String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (bodyStr.contains("include_archived")) {
                        byte[] resp = "{\"error\":\"unknown field include_archived\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(422, resp.length);
                        ex.getResponseBody().write(resp);
                    } else {
                        byte[] resp = "{\"session_id\":\"S-leg\",\"cleared\":2}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, resp.length);
                        ex.getResponseBody().write(resp);
                    }
                    ex.close();
                }
            });
            CoreApiClient.ActionOutcome legacyFallback = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "legacy-dot2", "S-leg", true, "Bearer hm1_valid_token");
            assertTrue(legacyFallback.ok && legacyFallback.httpCode == 200 && legacyCalls[0] == 2,
                    "clearDeviceConversation(includeArchived=true) must fallback on 422 legacy Core");
        } finally {
            server.stop(0);
        }
    }

    private static void testVerifyBearerTokenWithIdentityAndCoreIdPinningForRoomSummary() throws Exception {
        final int[] statusAuthHits = new int[]{0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/auth", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = "{\"configured\":true,\"core_id\":\"hmcore_pinned_99\",\"lan_gateway_exempt\":false,\"voice_gateway_open\":true}"
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    if ("Bearer hm1_room_secret".equals(auth)) {
                        statusAuthHits[0]++;
                        byte[] resp = ("{\"ok\":true,\"core_id\":\"hmcore_pinned_99\",\"assistant_name\":\"客厅小智\","
                                + "\"auth\":{\"configured\":true,\"lan_gateway_exempt\":false},"
                                + "\"gateway\":{\"voice_gateway_open\":true,\"devices\":[{\"stable_id\":\"dot2-a\",\"online\":true,\"volume\":0.60}]}}")
                                .getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, resp.length);
                        ex.getResponseBody().write(resp);
                    } else {
                        byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(401, resp.length);
                        ex.getResponseBody().write(resp);
                    }
                    ex.close();
                }
            });
            server.createContext("/api/v0/mode", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = "{\"default_duplex\":\"half\",\"media\":{},\"devices\":[]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // 1. Invalid token -> ok=false, authRequired=true, coreId empty
            CoreApiClient.TokenVerifyResult bad = CoreApiClient.verifyBearerTokenWithIdentity(
                    "127.0.0.1", port, "hm1_bad");
            assertTrue(!bad.ok && bad.authRequired && bad.httpCode == 401 && bad.coreId.isEmpty(),
                    "Invalid token must return authRequired=true and empty coreId");

            // 2. Valid token -> ok=true, extracts core_id + assistant_name
            CoreApiClient.TokenVerifyResult good = CoreApiClient.verifyBearerTokenWithIdentity(
                    "127.0.0.1", port, "  Bearer \"hm1_room_secret\"\r\n");
            assertTrue(good.ok && !good.authRequired && good.httpCode == 200
                            && "hmcore_pinned_99".equals(good.coreId)
                            && "客厅小智".equals(good.assistantName),
                    "Valid token must extract core_id=hmcore_pinned_99 and assistant_name=客厅小智, got "
                            + good.coreId + "/" + good.assistantName);

            // 3. Pinning helper adopts verified core_id when profile previously had empty core_id
            String pinnedCid = DeviceRoutingPolicy.resolvePinnedCoreId("", good.coreId);
            assertTrue("hmcore_pinned_99".equals(pinnedCid), "Expected pinned coreId hmcore_pinned_99");
            assertTrue("existing_cid".equals(DeviceRoutingPolicy.resolvePinnedCoreId("existing_cid", "")),
                    "Empty verifiedCoreId must preserve existing coreId");

            // 4. Before pinning (empty expectedCoreId), fetchRoomSummary stays fail-closed (no token sent -> 401)
            int hitsBeforeUnpinned = statusAuthHits[0];
            CoreApiClient.RoomNodeSummary unpinned = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "客厅", "hm1_room_secret", "");
            assertTrue(unpinned.authRequired && statusAuthHits[0] == hitsBeforeUnpinned,
                    "Unpinned fetchRoomSummary must remain fail-closed without leaking Bearer token");

            // 5. After pinning verified core_id, fetchRoomSummary sends token and succeeds!
            CoreApiClient.RoomNodeSummary pinnedSummary = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "客厅", "hm1_room_secret", pinnedCid);
            assertTrue(pinnedSummary.online && !pinnedSummary.authRequired
                            && "hmcore_pinned_99".equals(pinnedSummary.coreId)
                            && "客厅小智".equals(pinnedSummary.assistantName)
                            && pinnedSummary.volume != null && Math.abs(pinnedSummary.volume - 0.60) < 1e-6
                            && statusAuthHits[0] == hitsBeforeUnpinned + 1,
                    "Pinned fetchRoomSummary must transmit Bearer token and return authenticated room summary");
        } finally {
            server.stop(0);
        }
    }

    private static void testRoomNodeSummaryMusicPaused() throws Exception {
        final String[] mediaState = new String[]{"paused"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = ("{\"product\":\"Home Mate\",\"assistant_name\":\"小智\",\"core_id\":\"hmcore_room_p\","
                            + "\"auth\":{\"configured\":false,\"lan_gateway_exempt\":false},"
                            + "\"gateway\":{\"voice_gateway_open\":true,\"devices\":[{\"stable_id\":\"dot2-d\",\"online\":true,\"volume\":0.55}]}}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/mode", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    String mediaJson;
                    if ("paused".equals(mediaState[0])) {
                        mediaJson = "{\"dot2-d\":{\"state\":\"paused\",\"title\":\"夜曲\",\"source\":\"netease\"}}";
                    } else if ("playing".equals(mediaState[0])) {
                        mediaJson = "{\"dot2-d\":{\"state\":\"playing\",\"title\":\"晴天\",\"source\":\"netease\"}}";
                    } else {
                        mediaJson = "{}";
                    }
                    byte[] resp = ("{\"default_duplex\":\"half\",\"media\":" + mediaJson + ",\"devices\":[]}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // 1. Paused room
            CoreApiClient.RoomNodeSummary pausedRoom = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "书房", "");
            assertTrue(pausedRoom.online && !pausedRoom.musicActive && pausedRoom.musicPaused
                            && "夜曲".equals(pausedRoom.musicTitle),
                    "Paused room must report musicActive=false, musicPaused=true, musicTitle=夜曲");

            // 2. Playing room
            mediaState[0] = "playing";
            CoreApiClient.RoomNodeSummary playingRoom = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "书房", "");
            assertTrue(playingRoom.online && playingRoom.musicActive && !playingRoom.musicPaused
                            && "晴天".equals(playingRoom.musicTitle),
                    "Playing room must report musicActive=true, musicPaused=false");

            // 3. Idle room
            mediaState[0] = "idle";
            CoreApiClient.RoomNodeSummary idleRoom = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "书房", "");
            assertTrue(idleRoom.online && !idleRoom.musicActive && !idleRoom.musicPaused,
                    "Idle room must report musicActive=false, musicPaused=false");
        } finally {
            server.stop(0);
        }
    }

    private static void testReleaseUpdaterGitHubAndMirror() throws Exception {
        assertTrue(ReleaseUpdater.compareSemver("v2.3.11", "2.3.10") > 0, "2.3.11 > 2.3.10");
        assertTrue(ReleaseUpdater.compareSemver("v2.3.10", "2.3.10") == 0, "2.3.10 == 2.3.10");
        assertTrue(ReleaseUpdater.compareSemver("2.3.9", "2.3.10") < 0, "2.3.9 < 2.3.10");
        assertTrue(ReleaseUpdater.compareSemver("2.3.10-rc1", "2.3.10") < 0, "prerelease < release");
        assertTrue(ReleaseUpdater.isGitHubReleasesUrl(ReleaseUpdater.DEFAULT_GITHUB_RELEASES_API),
                "Default update URL must be GitHub Releases API");
        assertTrue(ReleaseUpdater.DEFAULT_GITHUB_RELEASES_API.contains("yes999zc/home-mate-gateway-android"),
                "Default update URL must target public home-mate-gateway-android repo");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/repos/yes999zc/home-mate-gateway-android/releases/latest", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    String json = "{\"tag_name\":\"v2.4.0\",\"html_url\":\"https://github.com/yes999zc/home-mate-gateway-android/releases/tag/v2.4.0\","
                            + "\"body\":\"Release notes for v2.4.0\",\"assets\":["
                            + "{\"name\":\"HomeMate-Gateway-v2.4.0-universal.apk\",\"browser_download_url\":\"https://github.com/yes999zc/home-mate-gateway-android/releases/download/v2.4.0/HomeMate-Gateway-v2.4.0-universal.apk\"},"
                            + "{\"name\":\"HomeMate-Gateway-v2.4.0-full-arm64.apk\",\"browser_download_url\":\"https://github.com/yes999zc/home-mate-gateway-android/releases/download/v2.4.0/HomeMate-Gateway-v2.4.0-full-arm64.apk\"}"
                            + "]}";
                    byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/repos/yes999zc/empty-repo/releases/latest", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/repos/yes999zc/rate-limited/releases/latest", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = "{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(403, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/yes999zc/rate-limited/releases/latest", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    ex.getResponseHeaders().add("Location",
                            "https://github.com/yes999zc/rate-limited/releases/tag/v2.3.19");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
            });
            server.createContext("/yes999zc/no-release-web/releases/latest", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    ex.getResponseHeaders().add("Location",
                            "https://github.com/yes999zc/no-release-web/releases");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
            });
            server.createContext("/apk/version.json", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] resp = "{\"version_code\":441,\"version_name\":\"2.3.11\",\"apk_url\":\"hm.apk\",\"notes\":\"LAN mirror update\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // 1. GitHub Releases API: arm64 picks full-arm64 APK
            ReleaseUpdater.UpdateResult ghArm64 = ReleaseUpdater.checkSync(
                    440, "2.3.10",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/home-mate-gateway-android/releases/latest",
                    true);
            assertTrue(ghArm64.available && !ghArm64.noReleaseYet && ghArm64.error == null,
                    "GitHub newer release must be available");
            assertTrue("2.4.0".equals(ghArm64.latestVersionName),
                    "Expected version 2.4.0, got: " + ghArm64.latestVersionName);
            assertTrue(ghArm64.downloadUrl.endsWith("full-arm64.apk"),
                    "arm64 device must prefer full-arm64.apk, got: " + ghArm64.downloadUrl);

            // 2. Non-arm64 picks universal APK
            ReleaseUpdater.UpdateResult ghUniv = ReleaseUpdater.checkSync(
                    440, "2.3.10",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/home-mate-gateway-android/releases/latest",
                    false);
            assertTrue(ghUniv.available && ghUniv.downloadUrl.endsWith("universal.apk"),
                    "non-arm64 device must prefer universal.apk, got: " + ghUniv.downloadUrl);

            // 3. Up-to-date when current >= latest
            ReleaseUpdater.UpdateResult ghSame = ReleaseUpdater.checkSync(
                    450, "2.4.0",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/home-mate-gateway-android/releases/latest",
                    true);
            assertTrue(!ghSame.available && ghSame.error == null && !ghSame.noReleaseYet,
                    "Equal version must report up-to-date");

            // 4. GitHub 404 (no public release yet) -> honest noReleaseYet=true, error=null
            ReleaseUpdater.UpdateResult gh404 = ReleaseUpdater.checkSync(
                    440, "2.3.10",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/empty-repo/releases/latest",
                    true);
            assertTrue(!gh404.available && gh404.noReleaseYet && gh404.error == null,
                    "GitHub 404 must report noReleaseYet=true without raw error");

            // 5. Custom LAN/NAS version.json mirror still works cleanly
            ReleaseUpdater.UpdateResult mirrorRes = ReleaseUpdater.checkSync(
                    440, "2.3.10",
                    "http://127.0.0.1:" + port + "/apk",
                    true);
            assertTrue(mirrorRes.available && mirrorRes.latestVersionCode == 441
                            && mirrorRes.downloadUrl.equals("http://127.0.0.1:" + port + "/apk/hm.apk"),
                    "Custom version.json mirror must resolve relative hm.apk, got: " + mirrorRes.downloadUrl);

            // 6. Empty currentVersionName must NOT false-positive when latestCode <= currentVersionCode
            ReleaseUpdater.UpdateResult mirrorSameCode = ReleaseUpdater.checkSync(
                    441, "",
                    "http://127.0.0.1:" + port + "/apk",
                    true);
            assertTrue(!mirrorSameCode.available && mirrorSameCode.error == null,
                    "Empty currentVersionName with latestCode <= currentCode must not false-positive");

            // 7. 32-bit (!preferArm64) must pick slim.apk over full-arm64.apk when no universal exists
            ReleaseUpdater.UpdateResult nonArm64Slim = ReleaseUpdater.parseGitHubReleaseJson(
                    "{\"tag_name\":\"v2.4.0\",\"html_url\":\"https://github.com/yes999zc/home-mate-gateway-android/releases/tag/v2.4.0\","
                            + "\"assets\":["
                            + "{\"name\":\"HomeMate-Gateway-v2.4.0-full-arm64.apk\",\"browser_download_url\":\"https://example.com/full-arm64.apk\"},"
                            + "{\"name\":\"HomeMate-Gateway-v2.4.0-slim.apk\",\"browser_download_url\":\"https://example.com/slim.apk\"}"
                            + "]}",
                    440, "2.3.10", false);
            assertTrue(nonArm64Slim.available && nonArm64Slim.downloadUrl.endsWith("slim.apk"),
                    "32-bit device must pick slim.apk over arm64-only APK, got: " + nonArm64Slim.downloadUrl);

            // 8. Non-http(s) download URLs (javascript:/file:) must be rejected; normalizeBaseUrl is case-insensitive
            ReleaseUpdater.UpdateResult badSchemeAsset = ReleaseUpdater.parseGitHubReleaseJson(
                    "{\"tag_name\":\"v2.4.0\",\"html_url\":\"javascript:alert(1)\","
                            + "\"assets\":[{\"name\":\"app.apk\",\"browser_download_url\":\"file:///etc/passwd\"}]}",
                    440, "2.3.10", true);
            assertTrue(!badSchemeAsset.available && badSchemeAsset.downloadUrl.isEmpty(),
                    "Non-http(s) asset & html_url schemes must be rejected");
            assertTrue("HTTPS://EXAMPLE.COM/apk".equals(ReleaseUpdater.normalizeBaseUrl("HTTPS://EXAMPLE.COM/apk/")),
                    "normalizeBaseUrl must preserve case-insensitive HTTPS scheme");
            ReleaseUpdater.UpdateResult badApiScheme = ReleaseUpdater.checkSync(
                    440, "2.3.10", "file:///repos/x/y/releases/latest", true);
            assertTrue(!badApiScheme.available && badApiScheme.error != null,
                    "Non-http(s) GitHub API URL must be rejected cleanly");

            // 9. GitHub API HTTP 403 rate-limit automatically falls back to rate-limit-free 302 redirect
            assertTrue(
                    "https://github.com/yes999zc/home-mate-gateway-android/releases/latest".equals(
                            ReleaseUpdater.deriveWebReleasesLatestUrl(ReleaseUpdater.DEFAULT_GITHUB_RELEASES_API)),
                    "deriveWebReleasesLatestUrl must map api.github.com to github.com");
            assertTrue("v2.3.19".equals(ReleaseUpdater.parseTagFromRedirectLocation(
                    "https://github.com/yes999zc/home-mate-gateway-android/releases/tag/v2.3.19")),
                    "parseTagFromRedirectLocation must extract tag");
            ReleaseUpdater.UpdateResult rlArm64 = ReleaseUpdater.checkSync(
                    446, "2.3.16",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/rate-limited/releases/latest",
                    true);
            assertTrue(rlArm64.available && rlArm64.error == null && !rlArm64.noReleaseYet,
                    "403 rate-limit must seamlessly fall back to 302 redirect, got err=" + rlArm64.error);
            assertTrue("2.3.19".equals(rlArm64.latestVersionName),
                    "403 fallback expected 2.3.19, got: " + rlArm64.latestVersionName);
            assertTrue(rlArm64.downloadUrl.endsWith("/releases/download/v2.3.19/HomeMate-Gateway-v2.3.19-full-arm64.apk"),
                    "403 fallback arm64 URL mismatch: " + rlArm64.downloadUrl);

            ReleaseUpdater.UpdateResult rlUniv = ReleaseUpdater.checkSync(
                    446, "2.3.16",
                    "http://127.0.0.1:" + port + "/repos/yes999zc/rate-limited/releases/latest",
                    false);
            assertTrue(rlUniv.available && rlUniv.error == null
                            && rlUniv.downloadUrl.endsWith("/releases/download/v2.3.19/HomeMate-Gateway-v2.3.19-universal.apk"),
                    "403 fallback universal URL mismatch: " + rlUniv.downloadUrl);

            // Direct web /releases/latest URL works directly (without REST API) and handles no-release redirect
            ReleaseUpdater.UpdateResult directWeb = ReleaseUpdater.checkSync(
                    449, "2.3.19",
                    "http://127.0.0.1:" + port + "/yes999zc/rate-limited/releases/latest",
                    true);
            assertTrue(!directWeb.available && directWeb.error == null && !directWeb.noReleaseYet,
                    "Direct web redirect equal version must report up-to-date");
            ReleaseUpdater.UpdateResult emptyWeb = ReleaseUpdater.checkSync(
                    446, "2.3.16",
                    "http://127.0.0.1:" + port + "/yes999zc/no-release-web/releases/latest",
                    true);
            assertTrue(!emptyWeb.available && emptyWeb.noReleaseYet && emptyWeb.error == null,
                    "Web redirect to /releases without /tag/ must report noReleaseYet=true");

            // 10. Empty expected_session_id rejected when includeArchived=false, permitted when includeArchived=true
            CoreApiClient.ActionOutcome emptyRejected = CoreApiClient.clearDeviceConversation(
                    "127.0.0.1", port, "android-fold", "", false, "Bearer hm1_valid_token");
            assertTrue(!emptyRejected.ok && emptyRejected.httpCode == 0,
                    "Empty expected_session_id must be rejected when includeArchived=false");
        } finally {
            server.stop(0);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("CoreApiClientTest FAILED: " + msg);
        }
    }
}
