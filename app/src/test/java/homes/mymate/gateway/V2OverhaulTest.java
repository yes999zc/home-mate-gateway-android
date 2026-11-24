package homes.mymate.gateway;

import com.k2fsa.sherpa.onnx.OnlineStream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import homes.mymate.gateway.protocol.Base64Codec;
import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.CoreDiscovery;
import homes.mymate.gateway.protocol.MiniJson;
import homes.mymate.gateway.protocol.NorthboundSession;

/**
 * Comprehensive v2.0.0 Deep Architecture Overhaul Unit Test Suite:
 * 1. API 21+ Pure-Java Base64Codec round-trip & padding edge cases
 * 2. Play receipt lifecycle (accepted -> streaming -> device_confirmed + play_finished) & superseded
 * 3. Stop receipt unknown/not_found (omits played_ms) & post-flush ordering
 * 4. hello_reject & config_apply_v2 boot_id / state_revision fencing & led_config preservation
 * 5. RFC1918 LAN IP filter (rejects Tailscale 100.x, loopback, public IPs)
 * 6. Sherpa-ONNX OnlineStream AtomicLong double-release safety (zero native SIGSEGV)
 * 7. Utterance prependWake flag (true for voice/UI vs false for MediaSession)
 * 8. 2Hz near_end_presence coalescing
 */
public final class V2OverhaulTest {

    private static final class RecordingTransport implements NorthboundSession.FrameTransport {
        final List<Map<String, Object>> frames = new ArrayList<Map<String, Object>>();

        @Override
        public void sendJsonLine(String jsonLine) {
            frames.add(MiniJson.parseObject(jsonLine));
        }
    }

    public static void main(String[] args) throws Exception {
        testBase64CodecAllLengths();
        testPlayReceiptLifecycleAndSupersede();
        testStopReceiptUnknownAndOrdering();
        testHelloRejectAndConfigFencing();
        testRfc1918Filter();
        testOnlineStreamDoubleReleaseSafety();
        testUtterancePrependWakeControl();
        testPresenceCoalescingAndVolumeReadback();
        testDialogueSanitizerStripsToolsAndThinking();
        testV22UdpBeaconAndManageOnlyDiscoveryAndRoomControls();
        System.out.println("PASS: All 10 V2OverhaulTest suites passed.");
    }

    private static void testV22UdpBeaconAndManageOnlyDiscoveryAndRoomControls() throws Exception {
        // 1. UDP beacon parsing (aligned with hm-core #271 + #274 core_id & lan_gateway_exempt)
        String beaconJson = "{\"type\":\"home_mate_beacon\",\"version\":\"0.31.0\","
                + "\"core_id\":\"hmcore_0123456789abcdef\","
                + "\"assistant_name\":\"小智\",\"room_name\":\"客厅\","
                + "\"web_port\":8930,\"gateway_port\":8932,"
                + "\"voice_gateway_open\":true,\"auth_required\":true,"
                + "\"lan_gateway_exempt\":true,\"multi_gateway\":true}";
        CoreDiscovery.DiscoveredCore parsed = CoreDiscovery.parseUdpBeacon(beaconJson, "192.168.1.71", 4L);
        assertTrue(parsed != null, "Valid UDP beacon must parse");
        assertTrue("192.168.1.71".equals(parsed.host), "Fallback sender host mismatch");
        assertTrue("客厅".equals(parsed.roomName), "room_name must parse");
        assertTrue("hmcore_0123456789abcdef".equals(parsed.coreId), "core_id must parse");
        assertTrue(parsed.voiceGatewayOpen && parsed.authRequired && parsed.lanGatewayExempt,
                "voiceGatewayOpen, authRequired, and lanGatewayExempt flags must parse");
        assertTrue("客厅".equals(parsed.displayLabel()), "displayLabel must prefer roomName");

        // 1b. Multi-IP / Tailscale deduplication by core_id: RFC1918 LAN wins primary, Tailscale becomes alternate
        CoreDiscovery.DiscoveredCore tsRoute = new CoreDiscovery.DiscoveredCore(
                "100.64.10.20", 8930, 8932, "小智", "0.31.0", 3L, true, true, "客厅",
                "hmcore_0123456789abcdef", true, null);
        CoreDiscovery.DiscoveredCore lanRoute = new CoreDiscovery.DiscoveredCore(
                "192.168.1.71", 8930, 8932, "小智", "0.31.0", 8L, false, true, "客厅",
                "hmcore_0123456789abcdef", true, null);
        List<CoreDiscovery.DiscoveredCore> deduped = CoreDiscovery.deduplicateByCoreId(
                Arrays.asList(tsRoute, lanRoute));
        assertTrue(deduped.size() == 1, "Same core_id on LAN + Tailscale must collapse into 1 entry");
        assertTrue("192.168.1.71".equals(deduped.get(0).host), "RFC1918 LAN must win primary over Tailscale");
        assertTrue(deduped.get(0).alternateHosts.size() == 1
                && "100.64.10.20".equals(deduped.get(0).alternateHosts.get(0)),
                "Tailscale route must be preserved in alternateHosts");
        assertTrue(!deduped.get(0).authRequired && deduped.get(0).lanGatewayExempt,
                "LAN-exempt private route must allow 1-tap connect without 401");

        // Non-beacon UDP packets must be ignored safely
        assertTrue(CoreDiscovery.parseUdpBeacon("{\"type\":\"other\"}", "192.168.1.99", 2L) == null,
                "Non-beacon UDP packet must be ignored");

        // 2. Real loopback HTTP behavior test for CoreDiscovery.probeHost & CoreApiClient.fetchRoomSummary
        //    proving ZERO raw TCP connections ever hit the :8932 gateway socket during discovery/refresh!
        final java.util.concurrent.atomic.AtomicInteger rawGatewayConnects = new java.util.concurrent.atomic.AtomicInteger(0);
        try (final java.net.ServerSocket spyGw = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))) {
            spyGw.setSoTimeout(80);
            final java.util.concurrent.atomic.AtomicBoolean spyRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread spyThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (spyRunning.get()) {
                        try (java.net.Socket s = spyGw.accept()) {
                            rawGatewayConnects.incrementAndGet();
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                }
            });
            spyThread.start();
            try {
                // 2a. Open Manage-Only Core (voice_gateway_open=false in status)
                com.sun.net.httpserver.HttpServer openServer = com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                try {
                    final int openWebPort = openServer.getAddress().getPort();
                    final boolean[] exposeVoice = new boolean[]{false};
                    openServer.createContext("/api/v0/status", new com.sun.net.httpserver.HttpHandler() {
                        @Override
                        public void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
                            byte[] resp = ("{\"product\":\"Home Mate\",\"core_id\":\"hmcore_aaaa1111bbbb2222\","
                                    + "\"assistant_name\":\"小智\",\"version\":\"0.31.0\",\"room_name\":\"书房\","
                                    + "\"auth\":{\"configured\":true,\"lan_gateway_exempt\":true},"
                                    + "\"gateway\":{\"voice_gateway_open\":" + exposeVoice[0] + ",\"bind_addr\":\"0.0.0.0:8932\"}}")
                                    .getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(200, resp.length);
                            ex.getResponseBody().write(resp);
                            ex.close();
                        }
                    });
                    openServer.start();

                    CoreDiscovery.DiscoveredCore manageOpen = CoreDiscovery.probeHost("127.0.0.1", openWebPort, spyGw.getLocalPort(), 500);
                    assertTrue(manageOpen != null, "Open Manage-Only Core must NOT be filtered out");
                    assertTrue(!manageOpen.voiceGatewayOpen && !manageOpen.authRequired && manageOpen.lanGatewayExempt,
                            "Open Manage-Only Core flags mismatch");
                    assertTrue("hmcore_aaaa1111bbbb2222".equals(manageOpen.coreId), "core_id must parse from /status");
                    assertTrue("书房".equals(manageOpen.roomName) && "小智".equals(manageOpen.assistantName),
                            "Open Manage-Only Core metadata mismatch: " + manageOpen);

                    // 2b. Voice+Manage Core (voice_gateway_open=true in /status)
                    exposeVoice[0] = true;
                    CoreDiscovery.DiscoveredCore voiceOpen = CoreDiscovery.probeHost("127.0.0.1", openWebPort, spyGw.getLocalPort(), 500);
                    assertTrue(voiceOpen != null && voiceOpen.voiceGatewayOpen,
                            "Voice+Manage Core must report voiceGatewayOpen=true from HTTP status");

                    CoreApiClient.RoomNodeSummary summary = CoreApiClient.fetchRoomSummary(
                            "127.0.0.1", openWebPort, spyGw.getLocalPort(), "书房", "");
                    assertTrue(summary.online && summary.voiceGatewayOpen && summary.lanGatewayExempt
                            && "hmcore_aaaa1111bbbb2222".equals(summary.coreId),
                            "fetchRoomSummary must parse voiceGatewayOpen, lanGatewayExempt, and coreId without TCP probing");
                } finally {
                    openServer.stop(0);
                }

                // 2c. Paired Core (401 /api/v0/status + 200 /api/v0/auth with voice_gateway_open & core_id)
                com.sun.net.httpserver.HttpServer pairedServer = com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                try {
                    final int pairedWebPort = pairedServer.getAddress().getPort();
                    pairedServer.createContext("/api/v0/status", new com.sun.net.httpserver.HttpHandler() {
                        @Override
                        public void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
                            byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(401, resp.length);
                            ex.getResponseBody().write(resp);
                            ex.close();
                        }
                    });
                    pairedServer.createContext("/api/v0/auth", new com.sun.net.httpserver.HttpHandler() {
                        @Override
                        public void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
                            byte[] resp = "{\"configured\":true,\"core_id\":\"hmcore_cccc3333dddd4444\",\"lan_gateway_exempt\":false,\"voice_gateway_open\":true}"
                                    .getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(200, resp.length);
                            ex.getResponseBody().write(resp);
                            ex.close();
                        }
                    });
                    pairedServer.start();

                    CoreDiscovery.DiscoveredCore pairedCore = CoreDiscovery.probeHost("127.0.0.1", pairedWebPort, spyGw.getLocalPort(), 500);
                    assertTrue(pairedCore != null, "Paired Core (401 + /api/v0/auth) must be discovered");
                    assertTrue(pairedCore.authRequired && pairedCore.voiceGatewayOpen && !pairedCore.lanGatewayExempt
                            && "hmcore_cccc3333dddd4444".equals(pairedCore.coreId),
                            "Paired Core must parse core_id, voice_gateway_open, lan_gateway_exempt from /api/v0/auth");

                    CoreApiClient.RoomNodeSummary pairedSummary = CoreApiClient.fetchRoomSummary(
                            "127.0.0.1", pairedWebPort, spyGw.getLocalPort(), "NAS", "");
                    assertTrue(pairedSummary.online && pairedSummary.authRequired && pairedSummary.voiceGatewayOpen
                            && "hmcore_cccc3333dddd4444".equals(pairedSummary.coreId),
                            "fetchRoomSummary on 401 must read /api/v0/auth without raw TCP probe");
                } finally {
                    pairedServer.stop(0);
                }

                assertTrue(rawGatewayConnects.get() == 0,
                        "ZERO raw TCP probes may hit :8932 during HTTP discovery/refresh, got " + rawGatewayConnects.get());
            } finally {
                spyRunning.set(false);
                spyThread.join(400);
            }
        }

        // 3. Manage-Only media fallback: phone's own deviceId not in media map, speaker device IS playing
        String modeJson = "{\"default_duplex\":\"half\",\"barge_in\":false,"
                + "\"media\":{\"dot2-livingroom\":{\"state\":\"playing\",\"title\":\"晴天\",\"source\":\"netease\"}}}";
        CoreApiClient.ModeState ms = CoreApiClient.parseModeResponse(MiniJson.parseObject(modeJson), "android-phone-1");
        assertTrue(ms.musicActive && "晴天".equals(ms.musicTitle),
                "Manage-Only mode must detect playing media on Core's primary speaker");

        // 4. Remote volume body clamping
        String volBody = CoreApiClient.buildVolumePostBody(1.4);
        assertTrue(volBody.contains("\"value\":1.0") || volBody.contains("\"value\":1"),
                "Volume body must clamp to 1.0, got: " + volBody);
        String volMid = CoreApiClient.buildVolumePostBody(0.65);
        assertTrue(volMid.contains("0.65"), "Volume body must carry 0.65, got: " + volMid);
    }

    private static void testDialogueSanitizerStripsToolsAndThinking() {
        String sampleConv = "{\"messages\":["
                + "{\"role\":\"user\",\"content\":\"放一首周杰伦的简单爱\",\"ts_ms\":1},"
                + "{\"role\":\"assistant\",\"content\":\"[调用 music.search {\\\"query\\\":\\\"简单爱\\\"}]\",\"ts_ms\":2},"
                + "{\"role\":\"tool\",\"content\":\"[回执 music.search completed]\",\"ts_ms\":3},"
                + "{\"role\":\"assistant\",\"content\":\"<think>用户想听简单爱，调用播放</think>\\n[调用 music.play {}]\\n已开始播放《简单爱》。\",\"ts_ms\":4}"
                + "]}";
        List<CoreApiClient.ChatEntry> clean = CoreApiClient.parseConversationMessages(MiniJson.parseObject(sampleConv));
        assertTrue(clean.size() == 2, "Expected strictly 2 clean dialogue entries, got " + clean.size());
        assertTrue("user".equals(clean.get(0).role) && "放一首周杰伦的简单爱".equals(clean.get(0).text),
                "User entry mismatch: " + clean.get(0).text);
        assertTrue("assistant".equals(clean.get(1).role) && "已开始播放《简单爱》。".equals(clean.get(1).text),
                "Assistant entry must strip <think> and [调用], got: " + clean.get(1).text);
    }

    private static void testBase64CodecAllLengths() {
        for (int len = 0; len <= 300; len++) {
            byte[] src = new byte[len];
            for (int i = 0; i < len; i++) {
                src[i] = (byte) ((i * 37 + 13) & 0xFF);
            }
            String enc = Base64Codec.encodeToString(src);
            byte[] dec = Base64Codec.decode(enc);
            assertTrue(Arrays.equals(src, dec), "Base64 round-trip failed for len=" + len);
        }
        assertTrue("SGVsbG8=".equals(Base64Codec.encodeToString("Hello".getBytes(StandardCharsets.UTF_8))),
                "Known vector 'Hello' mismatch");
        assertTrue(Arrays.equals("Hello".getBytes(StandardCharsets.UTF_8), Base64Codec.decode("SGVs\nbG8=\r\n")),
                "Whitespace-tolerant Base64 decode failed");
    }

    private static void testPlayReceiptLifecycleAndSupersede() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        NorthboundSession session = new NorthboundSession("dev-1", "Phone", "2.0.0", tx, new NorthboundSession.BaseListener());

        // Stream 1 on "voice"
        session.handleIncomingLine("{\"type\":\"play_begin\",\"task_id\":\"t1\",\"channel\":\"voice\",\"audio\":{\"sample_rate\":16000,\"channels\":1}}");
        assertTrue(tx.frames.size() == 1, "Only accepted should be emitted at play_begin");
        assertTrue("accepted".equals(MiniJson.getString(tx.frames.get(0), "stage", "")), "Stage 1 must be accepted");

        // Stream 2 on same "voice" channel -> supersedes t1!
        session.handleIncomingLine("{\"type\":\"play_begin\",\"task_id\":\"t2\",\"channel\":\"voice\",\"audio\":{\"sample_rate\":16000,\"channels\":1}}");
        // Calling sendPlaySuperseded("t1") again (simulating AudioEngine callback) MUST be idempotent (no duplicate!)
        session.sendPlaySuperseded("t1");
        assertTrue(tx.frames.size() == 3, "Expected single superseded(t1) + accepted(t2), got " + tx.frames.size());
        assertTrue("t1".equals(MiniJson.getString(tx.frames.get(1), "task_id", "")), "Superseded task must be t1");
        assertTrue("unknown".equals(MiniJson.getString(tx.frames.get(1), "stage", "")), "Superseded stage must be unknown");
        assertTrue(MiniJson.getString(tx.frames.get(1), "detail", "").contains("superseded"), "Superseded detail required");
        assertTrue("t2".equals(MiniJson.getString(tx.frames.get(2), "task_id", ""))
                && "accepted".equals(MiniJson.getString(tx.frames.get(2), "stage", "")), "t2 must be accepted");

        // Stream 3 on "media" does NOT supersede "voice" t2
        session.handleIncomingLine("{\"type\":\"play_begin\",\"task_id\":\"m1\",\"channel\":\"media\",\"audio\":{\"sample_rate\":44100,\"channels\":2}}");
        assertTrue(tx.frames.size() == 4 && "m1".equals(MiniJson.getString(tx.frames.get(3), "task_id", "")),
                "Concurrent media stream must not supersede voice stream");

        // First DAC chunk -> streaming, drain complete -> device_confirmed + play_finished
        session.sendPlayStreaming("t2");
        session.sendPlayFinished("t2", 840L);
        assertTrue("streaming".equals(MiniJson.getString(tx.frames.get(4), "stage", "")), "Streaming stage expected");
        assertTrue("play_receipt".equals(MiniJson.getString(tx.frames.get(5), "type", ""))
                && "device_confirmed".equals(MiniJson.getString(tx.frames.get(5), "stage", "")),
                "device_confirmed receipt must precede play_finished");
        assertTrue("play_finished".equals(MiniJson.getString(tx.frames.get(6), "type", ""))
                && MiniJson.getLong(tx.frames.get(6), "played_ms", 0) == 840L,
                "play_finished must carry exact played_ms");
    }

    private static void testStopReceiptUnknownAndOrdering() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        final List<String> order = new ArrayList<String>();
        NorthboundSession session = new NorthboundSession("dev-1", "Phone", "2.0.0", new NorthboundSession.FrameTransport() {
            @Override
            public void sendJsonLine(String jsonLine) {
                Map<String, Object> f = MiniJson.parseObject(jsonLine);
                tx.frames.add(f);
                order.add("TX:" + MiniJson.getString(f, "type", "") + ":" + MiniJson.getString(f, "stage", ""));
            }
        }, new NorthboundSession.BaseListener() {
            @Override
            public NorthboundSession.StopPlaybackResult onStopPlayback(String taskId, boolean flush, String reason) {
                order.add("HW_STOP:" + taskId);
                return "known-task".equals(taskId)
                        ? new NorthboundSession.StopPlaybackResult(true, 500L, 320L, "hw_delay")
                        : new NorthboundSession.StopPlaybackResult(false, 0L, 0L, "hw_delay");
            }
        });

        // Unknown task -> stage="unknown", detail="not_found", NO played_ms!
        session.handleIncomingLine("{\"type\":\"stop\",\"task_id\":\"missing-task\",\"flush\":true}");
        Map<String, Object> unk = tx.frames.get(tx.frames.size() - 1);
        assertTrue("unknown".equals(MiniJson.getString(unk, "stage", "")), "Unknown stop must emit stage=unknown");
        assertTrue("not_found".equals(MiniJson.getString(unk, "detail", "")), "Unknown stop must emit detail=not_found");
        assertTrue(!unk.containsKey("played_ms"), "Unknown stop must omit played_ms");

        // Known task -> HW_STOP MUST happen BEFORE stop_receipt("device_confirmed")
        order.clear();
        session.handleIncomingLine("{\"type\":\"stop\",\"task_id\":\"known-task\",\"flush\":true}");
        assertTrue(order.size() == 3 && "HW_STOP:known-task".equals(order.get(0))
                        && "TX:stop_receipt:accepted".equals(order.get(1))
                        && "TX:stop_receipt:device_confirmed".equals(order.get(2)),
                "HW flush must run before stop_receipts: " + order);
    }

    private static void testHelloRejectAndConfigFencing() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        final String[] rejectedReason = new String[]{null};
        final String[] appliedBlock = new String[]{null};
        final int[] rejectedMaxProto = new int[]{0};
        NorthboundSession session = new NorthboundSession("dev-1", "Phone", "2.0.0", tx, new NorthboundSession.BaseListener() {
            @Override
            public void onHelloRejected(String reason, int minProtocol, int maxProtocol) {
                rejectedReason[0] = reason;
                rejectedMaxProto[0] = maxProtocol;
            }

            @Override
            public void onConfigBlockApplied(String block, Map<String, Object> values) {
                appliedBlock[0] = block;
            }
        });

        session.handleIncomingLine("{\"type\":\"hello_reject\",\"reason\":\"unsupported_version\",\"min_protocol\":0,\"protocol\":3}");
        assertTrue("unsupported_version".equals(rejectedReason[0]) && rejectedMaxProto[0] == 3,
                "hello_reject must parse Core protocol field");

        // Wrong boot_id must fail fencing and emit Core-aligned "reason" field
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"kws\",\"expected_gateway_boot_id\":\"wrong-boot\",\"values\":{\"threshold\":0.5}}");
        Map<String, Object> failedReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("failed".equals(MiniJson.getString(failedReceipt, "state", "")), "Wrong boot_id must fail config_apply");
        assertTrue("boot_id_mismatch".equals(MiniJson.getString(failedReceipt, "reason", "")),
                "Failed config_receipt must emit reason=boot_id_mismatch");
        assertTrue(appliedBlock[0] == null, "Failed fenced apply must not invoke listener");

        // Correct boot_id + state_revision succeeds
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"kws\",\"expected_gateway_boot_id\":\""
                + session.getGatewayBootId() + "\",\"expected_state_revision\":" + session.getStateRevision()
                + ",\"values\":{\"threshold\":0.33}}");
        Map<String, Object> okReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("active".equals(MiniJson.getString(okReceipt, "state", "")), "Matching fence must succeed");
        assertTrue("kws".equals(appliedBlock[0]), "Successful config_apply must invoke listener");

        // led_config preserves night_start_minute & orientation fields
        session.handleIncomingLine("{\"type\":\"led_config\",\"night_enabled\":true,\"night_start_minute\":1380,\"orientation_rotation\":90}");
        assertTrue("led".equals(appliedBlock[0]), "led_config must invoke onConfigBlockApplied");
    }

    private static void testRfc1918Filter() {
        assertTrue(CoreDiscovery.isRfc1918PrivateIp("192.168.1.10"), "192.168.x must be RFC1918");
        assertTrue(CoreDiscovery.isRfc1918PrivateIp("10.0.0.5"), "10.x must be RFC1918");
        assertTrue(CoreDiscovery.isRfc1918PrivateIp("172.16.1.2"), "172.16.x must be RFC1918");
        assertTrue(CoreDiscovery.isRfc1918PrivateIp("172.31.255.1"), "172.31.x must be RFC1918");
        assertTrue(!CoreDiscovery.isRfc1918PrivateIp("100.64.10.20"), "Tailscale CGNAT 100.x must NOT be scanned as LAN");
        assertTrue(CoreDiscovery.isTailscaleIp("100.64.10.20"), "100.64.10.20 must be recognized as Tailscale CGNAT");
        assertTrue(!CoreDiscovery.isTailscaleIp("100.128.0.1"), "100.128.x must NOT be Tailscale CGNAT");
        assertTrue(!CoreDiscovery.isRfc1918PrivateIp("172.32.0.1"), "172.32.x must NOT be RFC1918");
        assertTrue(!CoreDiscovery.isRfc1918PrivateIp("8.8.8.8"), "Public IP must NOT be RFC1918");

        // Regression guard: remote LAN host reporting loopback 127.0.0.1:8931 must keep default 8932
        Map<String, Object> loopbackStatus = MiniJson.parseObject("{\"gateway\":{\"bind_addr\":\"127.0.0.1:8931\"}}");
        assertTrue(CoreDiscovery.resolveGatewayPort("192.168.1.10", loopbackStatus, 8932, 0) == 8932,
                "Remote LAN host with loopback bind_addr must preserve default gateway port 8932");
        assertTrue(CoreDiscovery.resolveGatewayPort("127.0.0.1", loopbackStatus, 8932, 0) == 8931,
                "Localhost target may adopt loopback bind_addr port 8931");
        Map<String, Object> lanStatus = MiniJson.parseObject("{\"gateway\":{\"bind_addr\":\"0.0.0.0:9932\"}}");
        assertTrue(CoreDiscovery.resolveGatewayPort("192.168.1.10", lanStatus, 8932, 0) == 9932,
                "Non-loopback custom gateway port should be adopted");
    }

    private static void testOnlineStreamDoubleReleaseSafety() {
        // Creating a 0-pointer OnlineStream and calling release() twice must be a safe no-op
        OnlineStream s = new OnlineStream(0L);
        assertTrue(s.getPtr() == 0L, "Zero pointer expected");
        s.release();
        s.release();
    }

    private static void testUtterancePrependWakeControl() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        NorthboundSession session = new NorthboundSession("dev-1", "Phone", "2.0.0", tx, new NorthboundSession.BaseListener());

        session.sendUtterance("下一首", "android_media_session", false);
        assertTrue(tx.frames.size() == 1 && "utterance".equals(MiniJson.getString(tx.frames.get(0), "type", "")),
                "MediaSession utterance with prependWake=false must NOT emit wake frame");

        session.sendUtterance("今天天气", "android_app", true);
        assertTrue(tx.frames.size() == 3
                && "wake".equals(MiniJson.getString(tx.frames.get(1), "type", ""))
                && "utterance".equals(MiniJson.getString(tx.frames.get(2), "type", "")),
                "User prompt with prependWake=true must emit wake + utterance");
    }

    private static void testPresenceCoalescingAndVolumeReadback() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        NorthboundSession session = new NorthboundSession("dev-1", "Phone", "2.0.0", tx, new NorthboundSession.BaseListener() {
            @Override
            public double onVolumeChanged(double volume, String taskId) {
                // Simulate 15-step Android AudioManager quantization
                return Math.round(volume * 15.0) / 15.0;
            }
        });

        session.handleIncomingLine("{\"type\":\"mic_session\",\"action\":\"start\"}");
        tx.frames.clear();
        byte[] pcm = new byte[2560];
        // Sending 5 chunks rapidly within <50ms should emit 5 audio_chunks but only 1 near_end_presence
        for (int i = 0; i < 5; i++) {
            session.sendAudioChunkAndPresence(pcm, -45.0, -58.0);
        }
        int audioCount = 0;
        int presenceCount = 0;
        for (Map<String, Object> f : tx.frames) {
            String t = MiniJson.getString(f, "type", "");
            if ("audio_chunk".equals(t)) audioCount++;
            if ("near_end_presence".equals(t)) presenceCount++;
        }
        assertTrue(audioCount == 5, "Expected 5 audio_chunks, got " + audioCount);
        assertTrue(presenceCount == 1, "Expected coalesced 1 near_end_presence, got " + presenceCount);

        tx.frames.clear();
        session.handleIncomingLine("{\"type\":\"volume_set\",\"task_id\":\"v1\",\"value\":0.50}");
        Map<String, Object> confirmed = tx.frames.get(tx.frames.size() - 1);
        double eff = MiniJson.getDouble(confirmed, "effective", 0.0);
        assertTrue(Math.abs(eff - (8.0 / 15.0)) < 1e-4, "volume_receipt must report quantized hardware step, got " + eff);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("V2 ASSERTION FAILED: " + msg);
        }
    }
}
