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
        testFleetPeerExpansionAndSsrfGuard();
        testParseReflexTableFromModeMatchesCoreHash();
        testV234CrossRepoContractParityAndIsolation();
        System.out.println("PASS: All 13 V2OverhaulTest suites passed.");
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

        // Known task -> HW_STOP MUST happen BEFORE single stop_receipt("device_confirmed")
        // (no premature "accepted" frame that would settle hm-core's oneshot stop waiter early)
        order.clear();
        session.handleIncomingLine("{\"type\":\"stop\",\"task_id\":\"known-task\",\"flush\":true}");
        assertTrue(order.size() == 2 && "HW_STOP:known-task".equals(order.get(0))
                        && "TX:stop_receipt:device_confirmed".equals(order.get(1)),
                "HW flush must run before single device_confirmed stop_receipt: " + order);
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

        // Wrong boot_id must fail fencing and emit Linux/Core-aligned status + error_code fields
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"kws\",\"expected_gateway_boot_id\":\"wrong-boot\",\"values\":{\"threshold\":0.5}}");
        Map<String, Object> failedReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("failed".equals(MiniJson.getString(failedReceipt, "status", "")), "Wrong boot_id must fail config_apply");
        assertTrue("boot_id_mismatch".equals(MiniJson.getString(failedReceipt, "error_code", "")),
                "Failed config_receipt must emit error_code=boot_id_mismatch");
        assertTrue(!failedReceipt.containsKey("state") && !failedReceipt.containsKey("reason"),
                "config_receipt must not emit duplicate state/reason aliases alongside status/error_code");
        assertTrue(appliedBlock[0] == null, "Failed fenced apply must not invoke listener");

        // Correct boot_id + state_revision succeeds
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"kws\",\"expected_gateway_boot_id\":\""
                + session.getGatewayBootId() + "\",\"expected_state_revision\":" + session.getStateRevision()
                + ",\"values\":{\"threshold\":0.33}}");
        Map<String, Object> okReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("active".equals(MiniJson.getString(okReceipt, "status", "")), "Matching fence must succeed");
        assertTrue("kws".equals(appliedBlock[0]), "Successful config_apply must invoke listener");

        // Unsupported legacy audio_eq / led_config frames are safely ignored (dead handlers removed)
        appliedBlock[0] = null;
        session.handleIncomingLine("{\"type\":\"audio_eq\",\"curve\":\"flat\"}");
        session.handleIncomingLine("{\"type\":\"led_config\",\"night_enabled\":true,\"night_start_minute\":1380,\"orientation_rotation\":90}");
        assertTrue(appliedBlock[0] == null, "Unsupported legacy audio_eq/led_config frames must be safely ignored");
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

    private static void testFleetPeerExpansionAndSsrfGuard() throws Exception {
        // Start two loopback mock Cores: seed Core lists sibling Core + an untrusted public IP in /api/v0/fleet.
        final java.net.ServerSocket siblingSrv = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int siblingPort = siblingSrv.getLocalPort();
        final java.net.ServerSocket seedSrv = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int seedPort = seedSrv.getLocalPort();

        Thread siblingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!siblingSrv.isClosed()) {
                    try (java.net.Socket s = siblingSrv.accept()) {
                        s.setSoTimeout(500);
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String reqLine = br.readLine();
                        String body = (reqLine != null && reqLine.contains("/api/v0/fleet"))
                                ? "{\"nodes\":[]}"
                                : "{\"product\":\"Home Mate\",\"assistant_name\":\"儿童房\",\"room_name\":\"儿童房\","
                                + "\"core_id\":\"hmcore_sibling00000001\","
                                + "\"auth\":{\"configured\":true,\"lan_gateway_exempt\":true},"
                                + "\"gateway\":{\"bind_addr\":\"0.0.0.0:8932\",\"voice_gateway_open\":true}}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + bytes.length + "\r\nConnection: close\r\n\r\n";
                        s.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().write(bytes);
                        s.getOutputStream().flush();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        siblingThread.setDaemon(true);
        siblingThread.start();

        Thread seedThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!seedSrv.isClosed()) {
                    try (java.net.Socket s = seedSrv.accept()) {
                        s.setSoTimeout(500);
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String reqLine = br.readLine();
                        String body;
                        if (reqLine != null && reqLine.contains("/api/v0/fleet")) {
                            body = "{\"nodes\":["
                                    + "{\"id\":\"dot2-d\",\"base_url\":\"http://127.0.0.1:" + siblingPort + "\"},"
                                    + "{\"id\":\"evil-pub\",\"base_url\":\"http://8.8.8.8:8930\"}"
                                    + "]}";
                        } else {
                            body = "{\"product\":\"Home Mate\",\"assistant_name\":\"书房\",\"room_name\":\"书房\","
                                    + "\"core_id\":\"hmcore_seed0000000001\","
                                    + "\"auth\":{\"configured\":false,\"lan_gateway_exempt\":false},"
                                    + "\"gateway\":{\"bind_addr\":\"0.0.0.0:8932\",\"voice_gateway_open\":true}}";
                        }
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + bytes.length + "\r\nConnection: close\r\n\r\n";
                        s.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().write(bytes);
                        s.getOutputStream().flush();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        seedThread.setDaemon(true);
        seedThread.start();

        try {
            // 1. Direct fleet fetch must include sibling and drop public IP 8.8.8.8 (SSRF guard)
            List<String[]> eps = CoreDiscovery.fetchFleetPeerEndpoints("127.0.0.1", seedPort, null, 500);
            assertTrue(eps.size() == 1 && "127.0.0.1".equals(eps.get(0)[0])
                            && String.valueOf(siblingPort).equals(eps.get(0)[1]),
                    "fetchFleetPeerEndpoints must include sibling and reject public 8.8.8.8");

            // 2. scanLan seeded with ONLY seed Core must expand fleet and discover sibling Core!
            List<CoreDiscovery.DiscoveredCore> found = CoreDiscovery.scanLan(
                    "127.0.0.1", null, null, seedPort, 8932, 240);
            boolean sawSeed = false;
            boolean sawSibling = false;
            for (CoreDiscovery.DiscoveredCore c : found) {
                if ("hmcore_seed0000000001".equals(c.coreId)) sawSeed = true;
                if ("hmcore_sibling00000001".equals(c.coreId)) sawSibling = true;
            }
            assertTrue(sawSeed && sawSibling,
                    "scanLan must discover both seed and fleet-expanded sibling Core, got " + found);
        } finally {
            try { seedSrv.close(); } catch (Throwable ignored) {}
            try { siblingSrv.close(); } catch (Throwable ignored) {}
        }
    }

    private static void testParseReflexTableFromModeMatchesCoreHash() {
        String modeJson = "{\"default_duplex\":\"half\",\"devices\":[{\"device\":\"android-sm-f9760\","
                + "\"reflex\":{\"words\":["
                + "{\"enabled\":true,\"intent\":\"wake\",\"text\":\"你好小智\",\"tokens\":\"n ǐ h ǎo x iǎo zh ì\"},"
                + "{\"enabled\":true,\"intent\":\"pause\",\"text\":\"暂停播放\",\"tokens\":\"z àn t íng b ō f àng\"},"
                + "{\"enabled\":true,\"intent\":\"continue\",\"text\":\"继续播放\",\"tokens\":\"j ì x ù b ō f àng\"},"
                + "{\"enabled\":true,\"intent\":\"next\",\"text\":\"下一首\",\"tokens\":\"x ià y ì sh ǒu\"},"
                + "{\"enabled\":true,\"intent\":\"retreat\",\"text\":\"停止播放\",\"tokens\":\"t íng zh ǐ b ō f àng\"},"
                + "{\"enabled\":false,\"intent\":\"pause\",\"text\":\"暂停\",\"tokens\":\"z àn t íng\"},"
                + "{\"enabled\":true,\"intent\":\"retreat\",\"text\":\"滚一边儿去\",\"tokens\":\"g ǔn y ì b iān ér q ù\"},"
                + "{\"enabled\":true,\"intent\":\"retreat\",\"text\":\"退下吧\",\"tokens\":\"t uì x ià b a\"},"
                + "{\"enabled\":true,\"intent\":\"retreat\",\"text\":\"退一下吧\",\"tokens\":\"t uì y í x ià b a\"}"
                + "]}}]}";
        CoreApiClient.ModeState ms = CoreApiClient.parseModeResponse(MiniJson.parseObject(modeJson), "android-sm-f9760");
        assertTrue(ms.reflexTable != null, "ModeState must parse reflexTable from /api/v0/mode");
        assertTrue("0ac20ddd9334614d".equals(ms.reflexTable.hash),
                "Compiled reflex table hash must match hm-core exact FNV-1a64 hash 0ac20ddd9334614d, got " + ms.reflexTable.hash);
        assertTrue(!ms.reflexTable.compiledKeywordsTxt.contains("@暂停\n"),
                "Disabled reflex word 暂停 must be excluded");
        assertTrue(ms.reflexTable.wakeWords.size() == 1 && "你好小智".equals(ms.reflexTable.wakeWords.get(0)),
                "Wake words mismatch: " + ms.reflexTable.wakeWords);
        assertTrue(ms.reflexTable.retreatWords.size() == 4
                        && ms.reflexTable.retreatWords.contains("退下吧")
                        && !ms.reflexTable.retreatWords.contains("退下"),
                "Retreat words mismatch: " + ms.reflexTable.retreatWords);
    }

    private static void testV234CrossRepoContractParityAndIsolation() throws Exception {
        RecordingTransport tx = new RecordingTransport();
        final String[] modeChanged = new String[]{null};
        NorthboundSession session = new NorthboundSession("android-2", "Phone", "2.3.4", tx, new NorthboundSession.BaseListener() {
            @Override
            public void onModeChanged(String duplexMode, boolean lowLatency) {
                modeChanged[0] = duplexMode;
            }
        });

        // 1. Honest Hello.capabilities must NOT advertise unimplemented audio_eq/led/acoustics blocks
        session.sendHello();
        Map<String, Object> hello = tx.frames.get(tx.frames.size() - 1);
        List<Object> devs = MiniJson.getList(hello, "devices");
        @SuppressWarnings("unchecked")
        Map<String, Object> dev0 = (Map<String, Object>) devs.get(0);
        List<Object> caps = MiniJson.getList(dev0, "capabilities");
        assertTrue(caps.contains("config_block_kws") && caps.contains("config_block_duplex"),
                "Hello must advertise supported config blocks");
        assertTrue(!caps.contains("audio_eq") && !caps.contains("led_config")
                        && !caps.contains("config_block_audio_eq")
                        && !caps.contains("config_block_led")
                        && !caps.contains("config_block_acoustics"),
                "Hello must not advertise unimplemented blocks: " + caps);

        // 2. Real hm-core ConfigApply(Duplex) payload with "duplex_requested":"full"
        int beforeCount = tx.frames.size();
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"duplex\","
                + "\"core_node_id\":\"dot2-d\",\"data_epoch\":7,\"operation_id\":\"op-dup-1\","
                + "\"desired_revision\":3,\"schema_version\":1,\"block_hash\":\"hash-dup-full\","
                + "\"expected_gateway_boot_id\":\"" + session.getGatewayBootId() + "\","
                + "\"expected_state_revision\":" + session.getStateRevision() + ","
                + "\"values\":{\"duplex_requested\":\"full\"}}");
        assertTrue("full".equals(session.getDuplexMode()), "ConfigApply(duplex) must switch duplexMode to full");
        assertTrue("full".equals(modeChanged[0]), "ConfigApply(duplex) must invoke listener.onModeChanged");
        boolean sawActiveReceipt = false;
        boolean sawFullModeReport = false;
        for (int i = beforeCount; i < tx.frames.size(); i++) {
            Map<String, Object> f = tx.frames.get(i);
            String t = MiniJson.getString(f, "type", "");
            if ("config_receipt".equals(t) && "active".equals(MiniJson.getString(f, "status", ""))) {
                sawActiveReceipt = true;
            }
            if ("mode_report".equals(t)) {
                Map<String, Object> dup = MiniJson.getObject(f, "duplex");
                if (dup != null && "full".equals(MiniJson.getString(dup, "effective", ""))) {
                    sawFullModeReport = true;
                }
            }
        }
        assertTrue(sawActiveReceipt, "ConfigApply(duplex) must emit active config_receipt");
        assertTrue(sawFullModeReport, "ConfigApply(duplex) must emit updated mode_report(full)");

        // Verify sendConfigState reflects tracked data_epoch=7 and operation_id="op-dup-1"
        session.sendConfigState("req-state-1");
        Map<String, Object> stateFrame = tx.frames.get(tx.frames.size() - 1);
        List<Object> blocks = MiniJson.getList(stateFrame, "blocks");
        boolean foundTrackedDuplex = false;
        for (Object bObj : blocks) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) bObj;
            if ("duplex".equals(MiniJson.getString(b, "block", ""))) {
                foundTrackedDuplex = MiniJson.getLong(b, "data_epoch", 0L) == 7L
                        && "op-dup-1".equals(MiniJson.getString(b, "operation_id", ""));
            }
        }
        assertTrue(foundTrackedDuplex, "config_state must preserve data_epoch & operation_id");

        // 3. Unsupported block & fencing failure semantics (retryable=true, no bogus active_hash)
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"audio_eq\",\"block_hash\":\"bad-eq\",\"values\":{\"curve\":\"flat\"}}");
        Map<String, Object> unsupReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("unsupported".equals(MiniJson.getString(unsupReceipt, "status", "")),
                "Unsupported block must return status=unsupported");
        assertTrue(!MiniJson.getBoolean(unsupReceipt, "retryable", true),
                "Unsupported block must be retryable=false");

        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"duplex\",\"block_hash\":\"rejected-hash\","
                + "\"expected_state_revision\":9999,\"values\":{\"duplex_requested\":\"half\"}}");
        Map<String, Object> fenceReceipt = tx.frames.get(tx.frames.size() - 1);
        assertTrue("failed".equals(MiniJson.getString(fenceReceipt, "status", "")), "Fencing failure must return failed");
        assertTrue(MiniJson.getBoolean(fenceReceipt, "retryable", false), "Fencing failure must return retryable=true");
        assertTrue(!"rejected-hash".equals(MiniJson.getString(fenceReceipt, "active_hash", "")),
                "Failed fencing receipt must NOT echo rejected block_hash as active_hash");

        // 4. Multi-gateway mode.media isolation: voice gateway must not inherit sibling Dot2 playback
        String modeJson = "{\"media\":{\"dot2-d\":{\"state\":\"playing\",\"title\":\"晚风信笺\",\"source\":\"library\"}}}";
        CoreApiClient.ModeState voiceMode = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(modeJson), "android-2", false);
        assertTrue(!voiceMode.musicActive && voiceMode.musicTitle.isEmpty(),
                "Connected voice gateway must NOT inherit sibling dot2-d music state");
        CoreApiClient.ModeState manageOnlyMode = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(modeJson), "android-2", true);
        assertTrue(manageOnlyMode.musicActive && "晚风信笺".equals(manageOnlyMode.musicTitle),
                "Manage-Only mode should still observe primary speaker music state");

        // 5. play_finished progress_basis honesty
        session.sendPlayFinished("P-1", 500L, "written");
        Map<String, Object> pf = tx.frames.get(tx.frames.size() - 1);
        assertTrue("written".equals(MiniJson.getString(pf, "progress_basis", "")),
                "play_finished must propagate measured progress_basis=written");

        // 6. AcousticGuard AEC-locked barge-in vs unlocked default
        homes.mymate.gateway.protocol.AcousticGuard guard = new homes.mymate.gateway.protocol.AcousticGuard();
        byte[] refTone = new byte[2560];
        byte[] nearVoice = new byte[2560];
        for (int i = 0; i < 1280; i++) {
            short r = (short) (Math.sin(i * 0.2) * 5000);
            short m = (short) (Math.sin(i * 0.31) * 14000);
            refTone[i * 2] = (byte) (r & 0xFF);
            refTone[i * 2 + 1] = (byte) ((r >> 8) & 0xFF);
            nearVoice[i * 2] = (byte) (m & 0xFF);
            nearVoice[i * 2 + 1] = (byte) ((m >> 8) & 0xFF);
        }
        guard.onPlaybackReferenceChunk(refTone, 1.0, 2000L);
        guard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2040L);
        guard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2120L);
        homes.mymate.gateway.protocol.AcousticGuard.BargeInFact lockedFact =
                guard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2200L);
        assertTrue(lockedFact != null && lockedFact.refLocked,
                "AEC-locked fresh reference barge-in must set refLocked=true");

        // 7. SoftwareAecFilter digital-silence guard (micPower == 0 must stay silent)
        homes.mymate.gateway.protocol.SoftwareAecFilter aec = new homes.mymate.gateway.protocol.SoftwareAecFilter();
        aec.setEnabled(true);
        aec.pushPlaybackReference(refTone, 16000, 1, 1000L);
        byte[] silence = new byte[2560];
        byte[] out = aec.processCaptureBlock(silence, 1080L);
        boolean allZero = true;
        for (byte b : out) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        assertTrue(allZero, "SoftwareAecFilter must not inject echo noise into digital silence");

        // 8. ConfigValidate fencing failure must emit retryable=true (matches hm-core preflight retry)
        session.handleIncomingLine("{\"type\":\"config_validate\",\"request_id\":\"val-1\",\"block\":\"duplex\","
                + "\"expected_state_revision\":9999,\"values\":{\"duplex_requested\":\"full\"}}");
        Map<String, Object> valResp = tx.frames.get(tx.frames.size() - 1);
        assertTrue("failed".equals(MiniJson.getString(valResp, "state", "")), "Fenced config_validate must fail");
        assertTrue(MiniJson.getBoolean(valResp, "retryable", false), "Fenced config_validate must set retryable=true");

        // 9. ConfigApply(kws) must keep retreat words out of wake_keywords
        session.handleIncomingLine("{\"type\":\"config_apply\",\"block\":\"kws\","
                + "\"expected_gateway_boot_id\":\"" + session.getGatewayBootId() + "\","
                + "\"expected_state_revision\":" + session.getStateRevision() + ","
                + "\"values\":{\"keywords\":\"n i h a o @你好小智\\nt u i x i a @退下吧\\n\","
                + "\"retreat_words\":[\"退下吧\"]}}");
        Map<String, Object> kwsReceipt = tx.frames.get(tx.frames.size() - 1);
        Map<String, Object> activeKws = MiniJson.getObject(kwsReceipt, "active_values");
        List<Object> wakeWordsOut = activeKws != null ? MiniJson.getList(activeKws, "wake_keywords") : null;
        assertTrue(wakeWordsOut != null && wakeWordsOut.contains("你好小智") && !wakeWordsOut.contains("退下吧"),
                "wake_keywords must exclude retreat words: " + wakeWordsOut);

        // 10. AcousticGuard stale reference (>300ms) keeps refLocked=false even when aecReferenceLocked=true
        homes.mymate.gateway.protocol.AcousticGuard staleGuard = new homes.mymate.gateway.protocol.AcousticGuard();
        staleGuard.onPlaybackReferenceChunk(refTone, 1.0, 2000L);
        staleGuard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2500L);
        staleGuard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2580L);
        homes.mymate.gateway.protocol.AcousticGuard.BargeInFact staleFact =
                staleGuard.pushCaptureBlockAndCheckBargeIn(nearVoice, -16.0, -55.0, true, true, 2660L);
        assertTrue(staleFact != null && !staleFact.refLocked,
                "Stale playback reference (>300ms) must keep refLocked=false");

        // 11. hello_reject backoff matches real hm-core formatted protocol_no_intersection string
        assertTrue(NorthboundSession.isBackoffRejectReason("unauthorized"), "unauthorized must back off");
        assertTrue(NorthboundSession.isBackoffRejectReason("device_id_reserved"), "device_id_reserved must back off");
        assertTrue(NorthboundSession.isBackoffRejectReason(
                        "protocol_no_intersection: peer accepts [0,2], this side accepts [0,0]"),
                "formatted protocol_no_intersection must back off");
        assertTrue(!NorthboundSession.isBackoffRejectReason("temporary_busy"), "unrelated reason must not back off");

        // 12. CoreDiscovery.probeHost verifies /api/v0/auth unauthenticated BEFORE sending Bearer token,
        //     refuses token transmission on core_id mismatch, and deduplicateByCoreId preserves 401 when lanExempt=true
        final java.net.ServerSocket authSrv = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int authPort = authSrv.getLocalPort();
        final java.util.concurrent.atomic.AtomicInteger authHeaderOnAuthEndpoint = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger statusCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread authThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int n = 0; n < 8; n++) {
                        java.net.Socket s = authSrv.accept();
                        try {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
                            String reqLine = br.readLine();
                            String authHdr = "";
                            String line;
                            while ((line = br.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase(java.util.Locale.US).startsWith("authorization:")) {
                                    authHdr = line.substring("authorization:".length()).trim();
                                }
                            }
                            String body;
                            String status;
                            if (reqLine != null && reqLine.contains("/api/v0/auth")) {
                                if (!authHdr.isEmpty()) {
                                    authHeaderOnAuthEndpoint.incrementAndGet();
                                }
                                status = "200 OK";
                                body = "{\"configured\":true,\"core_id\":\"hmcore_auth_test_1\",\"lan_gateway_exempt\":true}";
                            } else {
                                statusCalls.incrementAndGet();
                                if ("Bearer secret-tok-123".equals(authHdr)) {
                                    status = "200 OK";
                                    body = "{\"product\":\"Home Mate\",\"assistant_name\":\"小智\",\"version\":\"0.31.0\",\"core_id\":\"hmcore_auth_test_1\",\"auth\":{\"configured\":true,\"lan_gateway_exempt\":false},\"gateway\":{\"voice_gateway_open\":true}}";
                                } else {
                                    status = "401 Unauthorized";
                                    body = "{\"error\":\"unauthorized\"}";
                                }
                            }
                            byte[] b = body.getBytes("UTF-8");
                            java.io.OutputStream outSt = s.getOutputStream();
                            outSt.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                            outSt.write(b);
                            outSt.flush();
                        } finally {
                            s.close();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        authThread.setDaemon(true);
        authThread.start();
        try {
            // Mismatched expectedCoreId must return null WITHOUT calling /api/v0/status or leaking token
            CoreDiscovery.DiscoveredCore mismatch = CoreDiscovery.probeHost(
                    "127.0.0.1", authPort, 8932, 800, "secret-tok-123", "hmcore_other_999");
            assertTrue(mismatch == null, "probeHost with mismatched core_id must return null");
            assertTrue(statusCalls.get() == 0, "probeHost must NEVER call /api/v0/status or leak Bearer token on core_id mismatch");
            assertTrue(authHeaderOnAuthEndpoint.get() == 0, "probeHost must NEVER send Authorization header to /api/v0/auth");

            // Matching expectedCoreId verifies /api/v0/auth first, then authenticates /api/v0/status
            CoreDiscovery.DiscoveredCore authed = CoreDiscovery.probeHost(
                    "127.0.0.1", authPort, 8932, 800, "secret-tok-123", "hmcore_auth_test_1");
            assertTrue(authed != null && !authed.authRequired,
                    "probeHost with verified expectedCoreId + valid Bearer token must succeed with authRequired=false");
            CoreDiscovery.DiscoveredCore unauthed = CoreDiscovery.probeHost("127.0.0.1", authPort, 8932, 800);
            assertTrue(unauthed != null && unauthed.authRequired && unauthed.lanGatewayExempt,
                    "probeHost without token on 401 Core must preserve authRequired=true");
            List<CoreDiscovery.DiscoveredCore> deduped = CoreDiscovery.deduplicateByCoreId(
                    java.util.Collections.singletonList(unauthed));
            assertTrue(deduped.size() == 1 && deduped.get(0).authRequired,
                    "deduplicateByCoreId must NOT clear authRequired=true on a 401 Core solely because lanExempt=true");

            // 13. P2-21: CoreDiscovery.main --probe CLI contract used by tools/smoke_with_core.py
            java.io.ByteArrayOutputStream cliBuf = new java.io.ByteArrayOutputStream();
            java.io.PrintStream prevOut = System.out;
            try {
                System.setOut(new java.io.PrintStream(cliBuf, true, "UTF-8"));
                CoreDiscovery.main(new String[]{
                        "--probe", "127.0.0.1",
                        "--web-port", String.valueOf(authPort),
                        "--gw-port", "8932",
                        "--timeout-ms", "800"
                });
            } finally {
                System.setOut(prevOut);
            }
            String cliOut = new String(cliBuf.toByteArray(), StandardCharsets.UTF_8).trim();
            assertTrue(cliOut.contains("DISCOVERED:") && cliOut.contains("8932"),
                    "CoreDiscovery.main --probe must print DISCOVERED: with gw port, got: " + cliOut);
        } finally {
            try { authSrv.close(); } catch (Throwable ignored) {}
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("V2 ASSERTION FAILED: " + msg);
        }
    }
}
