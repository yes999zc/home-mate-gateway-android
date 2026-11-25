package homes.mymate.gateway;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import homes.mymate.gateway.protocol.BoundedTwoTierQueue;
import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.DeviceRoutingPolicy;
import homes.mymate.gateway.protocol.LocalKwsEngine;
import homes.mymate.gateway.protocol.MiniJson;
import homes.mymate.gateway.protocol.NorthboundSession;
import homes.mymate.gateway.protocol.SoftwareAecFilter;

/**
 * JVM Unit Tests for Phase 3 + v2.0 Compatibility.
 */
public final class Phase3UnitTest {

    public static void main(String[] args) throws Exception {
        testSoftwareAecCancelsInterleavedWidebandEcho();
        testSoftwareAecStereoMusicEchoAndNonNegativeGuard();
        testSherpaJniFieldContract();
        testKwsTimestampMetrics();
        testCoreApiWireSchemas();
        testI18nBilingual();
        testChatSseParsingAndRemoteMediaControls();
        testVoiceGatewayConversationPrivacyIsolation();
        testInitialMutedAndPlayFailedReceipts();
        testLocalKwsDeployConcurrentReloadRollbackAndTimeout();
        testBoundedTwoTierQueueStressAndPriority();
        testModeResponseMusicPausedAndSiblingFallback();
        testManageOnlyTranscriptMergeAndRepeatedMessages();
        testChatSseCancelledAndErrorReplies();
        testWave3SecurityAudioAndProtocolHardening();
        testWave4DeviceIsolationAndUtteranceRouting();
        testWave5PerDeviceModeAuth401PropagationAndKwsWorkspaceFence();
        testV239Dot2KwsSensitivityMigrationGuard();
        System.out.println("PASS: All 18 Phase 3 unit tests passed.");
    }

    private static void testSoftwareAecCancelsInterleavedWidebandEcho() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int sampleRate = 16000;
        int samplesPerBlock = 1280; // 80ms
        int delaySamples = 640;     // 40ms acoustic propagation delay
        int totalBlocks = 30;
        float[] playbackHistory = new float[totalBlocks * samplesPerBlock];
        Random rng = new Random(42L);
        long now = 10000L;

        double rawRms = -96.0;
        double cleanRms = -96.0;
        double lastErle = 0.0;

        float lp = 0f;
        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[samplesPerBlock * 2];
            for (int i = 0; i < samplesPerBlock; i++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.65f;
                lp = 0.65f * lp + 0.35f * white;
                playbackHistory[b * samplesPerBlock + i] = lp;
                short s = (short) Math.round(lp * 32767.0f);
                refChunk[i * 2] = (byte) (s & 0xFF);
                refChunk[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            long blockTs = now + b * 80L;
            aec.pushPlaybackReference(refChunk, sampleRate, 1, blockTs);

            byte[] micBlock = new byte[samplesPerBlock * 2];
            for (int i = 0; i < samplesPerBlock; i++) {
                int srcIdx = b * samplesPerBlock + i - delaySamples;
                float echo = 0f;
                if (srcIdx >= 1) {
                    echo = 0.45f * playbackHistory[srcIdx] + 0.12f * playbackHistory[srcIdx - 1];
                }
                short s = (short) Math.round(echo * 32767.0f);
                micBlock[i * 2] = (byte) (s & 0xFF);
                micBlock[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, blockTs);
            if (b == totalBlocks - 1) {
                rawRms = NorthboundSession.computeRmsDbfs(micBlock);
                cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
                lastErle = aec.getLastErleDb();
            }
        }
        assertTrue(cleanRms < rawRms - 6.0,
                "Expected interleaved wideband NLMS AEC attenuation >6dB (raw="
                        + rawRms + "dB, clean=" + cleanRms + "dB, erle=" + lastErle + "dB)");
    }

    private static void testSoftwareAecStereoMusicEchoAndNonNegativeGuard() {
        // 1. Verify 44.1kHz stereo music playback reference suppresses music echo in 16kHz mic capture (>5dB ERLE)
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int musicRate = 44100;
        int micRate = 16000;
        int micBlockSamples = 1280; // 80ms @ 16kHz
        int musicBlockFrames = 3528; // 80ms @ 44.1kHz
        int totalBlocks = 35;
        float[] mono16kHistory = new float[totalBlocks * micBlockSamples];
        Random rng = new Random(1337L);
        long now = 50000L;

        float lp = 0f;
        double rawRms = -96.0;
        double cleanRms = -96.0;
        for (int b = 0; b < totalBlocks; b++) {
            byte[] stereo44k = new byte[musicBlockFrames * 4];
            for (int f = 0; f < musicBlockFrames; f++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.70f;
                lp = 0.80f * lp + 0.20f * white;
                short s = (short) Math.round(lp * 32767.0f);
                int pos = f * 4;
                stereo44k[pos] = (byte) (s & 0xFF);
                stereo44k[pos + 1] = (byte) ((s >> 8) & 0xFF);
                stereo44k[pos + 2] = (byte) (s & 0xFF);
                stereo44k[pos + 3] = (byte) ((s >> 8) & 0xFF);
                int idx16k = (int) ((f * (double) micRate) / (double) musicRate);
                if (idx16k < micBlockSamples) {
                    mono16kHistory[b * micBlockSamples + idx16k] = lp;
                }
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(stereo44k, musicRate, 2, ts);

            byte[] micBlock = new byte[micBlockSamples * 2];
            int delaySamples = 640; // 40ms
            for (int i = 0; i < micBlockSamples; i++) {
                int srcIdx = b * micBlockSamples + i - delaySamples;
                float echo = (srcIdx >= 0) ? (0.42f * mono16kHistory[srcIdx]) : 0f;
                short s = (short) Math.round(echo * 32767.0f);
                micBlock[i * 2] = (byte) (s & 0xFF);
                micBlock[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, ts);
            if (b == totalBlocks - 1) {
                rawRms = NorthboundSession.computeRmsDbfs(micBlock);
                cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
            }
        }
        assertTrue(cleanRms < rawRms - 5.0,
                "44.1kHz stereo music AEC must attenuate echo >5dB (raw=" + rawRms + ", clean=" + cleanRms + ")");

        // 2. Non-negative ERLE safety guard: uncorrelated mic signal must NEVER increase energy
        byte[] uncorrelatedVoice = new byte[micBlockSamples * 2];
        for (int i = 0; i < micBlockSamples; i++) {
            short s = (short) Math.round(Math.sin(2.0 * Math.PI * 520.0 * i / 16000.0) * 6000.0);
            uncorrelatedVoice[i * 2] = (byte) (s & 0xFF);
            uncorrelatedVoice[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        double inVoiceRms = NorthboundSession.computeRmsDbfs(uncorrelatedVoice);
        byte[] outVoice = aec.processCaptureBlock(uncorrelatedVoice, now + totalBlocks * 80L);
        double outVoiceRms = NorthboundSession.computeRmsDbfs(outVoice);
        assertTrue(outVoiceRms <= inVoiceRms + 0.05,
                "Non-negative ERLE guard must never amplify uncorrelated speech (in=" + inVoiceRms + ", out=" + outVoiceRms + ")");
    }

    private static void testSherpaJniFieldContract() throws Exception {
        OnlineTransducerModelConfig trans = new OnlineTransducerModelConfig("enc.onnx", "dec.onnx", "join.onnx");
        OnlineModelConfig model = new OnlineModelConfig(trans, "tokens.txt", 2, false, "cpu");
        model.setModelType("zipformer2");
        KeywordSpotterConfig cfg = new KeywordSpotterConfig(new FeatureConfig(16000, 80, 0f), model, 4, "kw.txt", 2.6f, 0.28f, 0);
        KeywordSpotterResult res = new KeywordSpotterResult("你好小智", new String[]{"n", "ǐ"}, new float[]{0.12f, 0.56f});

        assertFieldExists(KeywordSpotterConfig.class, "featConfig");
        assertFieldExists(KeywordSpotterConfig.class, "modelConfig");
        assertFieldExists(KeywordSpotterConfig.class, "maxActivePaths");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsFile");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsScore");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsThreshold");
        assertFieldExists(KeywordSpotterConfig.class, "numTrailingBlanks");
        assertFieldExists(OnlineModelConfig.class, "transducer");
        assertFieldExists(OnlineModelConfig.class, "tokens");
        assertFieldExists(OnlineModelConfig.class, "numThreads");
        assertFieldExists(OnlineModelConfig.class, "provider");
        assertFieldExists(OnlineModelConfig.class, "modelType");
        assertFieldExists(OnlineTransducerModelConfig.class, "encoder");
        assertFieldExists(OnlineTransducerModelConfig.class, "decoder");
        assertFieldExists(OnlineTransducerModelConfig.class, "joiner");
        assertFieldExists(OnlineTransducerModelConfig.class, "qnnConfig");

        assertTrue("kw.txt".equals(cfg.getKeywordsFile()), "keywordsFile mismatch");
        assertTrue("你好小智".equals(res.getKeyword()), "result keyword mismatch");
    }

    private static void testKwsTimestampMetrics() {
        float[] ts = new float[]{1.10f, 1.25f, 1.48f, 1.72f};
        long dur = LocalKwsEngine.computeWakeDurationMs(ts);
        long maxGap = LocalKwsEngine.computeMaxGapMs(ts);
        // v2.0 includes +80ms last frame width: (1.72 - 1.10) * 1000 + 80 = 700ms
        assertTrue(dur == 700L, "Expected wake duration 700ms (with +80ms frame), got " + dur);
        assertTrue(maxGap == 240L, "Expected max gap 240ms, got " + maxGap);
    }

    private static void testCoreApiWireSchemas() {
        String sampleStatus = "{\"assistant_name\":\"Home Mate\",\"gateway\":{\"devices\":[{\"stable_id\":\"android-sm-f9760\",\"model\":\"samsung-SM-F9760\",\"online\":true,\"aec_enabled\":true,\"audio_profile\":\"android_farfield_v1\",\"volume\":0.42}]}}";
        List<CoreApiClient.DeviceInfo> devs = CoreApiClient.parseDevicesFromStatus(MiniJson.parseObject(sampleStatus));
        assertTrue(devs.size() == 1 && "android-sm-f9760".equals(devs.get(0).id) && devs.get(0).connected && devs.get(0).aecEnabled,
                "Status device parsing failed");
        assertTrue(devs.get(0).volume != null && Math.abs(devs.get(0).volume.doubleValue() - 0.42) < 1e-6,
                "Expected DeviceInfo.volume=0.42, got " + devs.get(0).volume);

        String sampleMode = "{\"default_duplex\":\"half\",\"barge_in\":true,\"wake_arbitration\":{\"mode\":\"observe\",\"peers\":3},\"cue_readiness\":{\"wake_ack_enabled\":true},\"media\":{\"android-sm-f9760\":{\"state\":\"playing\",\"title\":\"北京没有你\",\"source\":\"netease\"}},\"devices\":[{\"device\":\"android-sm-f9760\",\"duplex\":{\"requested\":\"full\",\"effective\":\"full\"},\"wake_ack\":{\"enabled\":false}}]}";
        CoreApiClient.ModeState ms = CoreApiClient.parseModeResponse(MiniJson.parseObject(sampleMode), "android-sm-f9760");
        assertTrue("full".equals(ms.duplex), "Expected duplex=full, got " + ms.duplex);
        assertTrue(ms.bargeIn, "Expected bargeIn=true");
        assertTrue("observe".equals(ms.wakeArbitration), "Expected wakeArbitration=observe, got " + ms.wakeArbitration);
        assertTrue(!ms.wakeAck, "Expected wakeAck=false from device wake_ack.enabled");
        assertTrue(ms.musicActive && "北京没有你".equals(ms.musicTitle) && "netease".equals(ms.musicSource),
                "Expected live music state from mode.media");

        String putJson = CoreApiClient.buildModePutBody("full", Boolean.TRUE, Boolean.TRUE, "off");
        Map<String, Object> putObj = MiniJson.parseObject(putJson);
        Map<String, Object> wakeAckObj = MiniJson.getObject(putObj, "wake_ack");
        assertTrue(wakeAckObj != null && MiniJson.getBoolean(wakeAckObj, "enabled", false),
                "PUT mode wake_ack must be nested object {\"enabled\":true}, got: " + putJson);
        assertTrue(MiniJson.getBoolean(putObj, "barge_in", false), "PUT mode barge_in must be true");

        String sampleFleet = "{\"nodes\":[{\"id\":\"dot2-d\",\"base_url\":\"http://192.168.1.73:8930\",\"last_ok_ms\":1000,\"last_error\":null},{\"id\":\"dot2-e\",\"base_url\":\"http://192.168.1.69:8930\",\"last_error\":\"timeout\"}]}";
        List<CoreApiClient.FleetNodeInfo> fleet = CoreApiClient.parseFleetNodes(MiniJson.parseObject(sampleFleet));
        assertTrue(fleet.size() == 2, "Expected 2 fleet nodes");
        assertTrue("http://192.168.1.73:8930".equals(fleet.get(0).url) && fleet.get(0).healthy, "Healthy fleet node mismatch");
        assertTrue("http://192.168.1.69:8930".equals(fleet.get(1).url) && !fleet.get(1).healthy, "Unhealthy fleet node mismatch");

        String chatJson = CoreApiClient.buildChatPostBody("今天天气");
        Map<String, Object> chatObj = MiniJson.parseObject(chatJson);
        assertTrue(!chatObj.containsKey("device_id"), "POST /api/v0/chat must omit device_id");
        assertTrue("今天天气".equals(MiniJson.getString(chatObj, "text", "")), "Chat text mismatch");
    }

    private static void testI18nBilingual() {
        String[] keys = new String[]{
                "tab_voice", "tab_rooms", "tab_console", "tab_settings",
                "standby_mode", "mode_duplex", "mode_barge_in", "settings_sw_aec",
                "kws_jni", "arb_off", "arb_observe", "arb_enforce",
                "settings_kiosk_home", "settings_reset_preset", "notif_boot_resume",
                "badge_lan_exempt", "status_unconfigured", "onboarding_title",
                "auth_banner_title", "settings_edit_profile", "settings_advanced_toggle",
                "console_unconfigured", "music_paused",
                "voice_disconnected_reconnect", "settings_profile_manage_only",
                "auth_action_required_toast",
                "history_subtitle_fmt", "history_refreshing", "history_empty_sub",
                "history_refresh", "history_earlier_session", "history_current_session",
                "history_auth_required", "rooms_count_fmt", "rooms_scan_short",
                "rooms_scan_primary", "rooms_add_manual", "rooms_active_controls_title",
                "rooms_details_toggle", "rooms_need_token", "rooms_active_online",
                "rooms_vol_short", "rooms_switch_here_btn", "rooms_switch_short",
                "rooms_enter_token_btn", "rooms_token_desc", "rooms_token_verify_btn",
                "rooms_token_verifying", "rooms_token_verified_ok", "rooms_token_empty_err",
                "rooms_token_invalid_err", "rooms_token_unreachable_err",
                "history_clear_title", "history_clear_confirm", "history_clear_btn",
                "history_cleared", "history_clear_failed",
                "mode_full_dev_hint", "mode_full_dev_confirm_title",
                "mode_full_dev_confirm_msg", "mode_full_dev_confirm_ok",
                "settings_battery_manage_toast", "settings_update_no_release"
        };
        for (String k : keys) {
            String zh = I18n.t(I18n.LANG_ZH, k);
            String en = I18n.t(I18n.LANG_EN, k);
            assertTrue(!zh.equals(k) && !zh.isEmpty(), "Missing ZH translation for " + k);
            assertTrue(!en.equals(k) && !en.isEmpty(), "Missing EN translation for " + k);
        }
    }

    private static void testChatSseParsingAndRemoteMediaControls() throws Exception {
        String sse = "data: {\"type\":\"session\",\"session_id\":\"S-42\"}\n\n"
                + "data: {\"type\":\"token\",\"text\":\"你好\"}\n\n"
                + "data: {\"type\":\"answer\",\"text\":\"你好，我是书房助手。\"}\n\n"
                + "data: {\"type\":\"turn_end\",\"trace_id\":\"tr-1\",\"status\":\"ok\",\"duration_ms\":120}\n\n";
        CoreApiClient.ChatReply parsed = CoreApiClient.parseChatSseStream(new BufferedReader(new StringReader(sse)));
        assertTrue(parsed.ok, "SSE parse must succeed");
        assertTrue("S-42".equals(parsed.sessionId), "Expected session_id=S-42, got " + parsed.sessionId);
        assertTrue("你好，我是书房助手。".equals(parsed.answerText), "Unexpected answerText: " + parsed.answerText);

        String bodyWithSid = CoreApiClient.buildChatPostBody("继续说说", "S-42");
        Map<String, Object> bodyMap = MiniJson.parseObject(bodyWithSid);
        assertTrue("S-42".equals(MiniJson.getString(bodyMap, "session_id", "")), "session_id must be included");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/devices/dot2-d/program/pause", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"paused\":false}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/devices/dot2-d/media/control", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"ok\":true,\"action\":\"next\",\"target\":\"media\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();
            assertTrue(!CoreApiClient.pauseRemoteDevice("127.0.0.1", port, "dot2-d", null),
                    "pauseRemoteDevice must return false when Core replies {\"paused\":false}");
            assertTrue(CoreApiClient.controlRemoteMedia("127.0.0.1", port, "dot2-d", "next", null),
                    "controlRemoteMedia must succeed on HTTP 200 {\"ok\":true}");
        } finally {
            server.stop(0);
        }
    }

    private static void testVoiceGatewayConversationPrivacyIsolation() throws Exception {
        final AtomicInteger siblingConvHits = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v0/mode", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"default_duplex\":\"half\",\"devices\":[]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"assistant_name\":\"HM\",\"gateway\":{\"devices\":[{\"stable_id\":\"dot2-d\",\"online\":true,\"volume\":0.35}]}}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/devices/android-2/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/devices/dot2-d/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    siblingConvHits.incrementAndGet();
                    byte[] resp = "{\"messages\":[{\"role\":\"user\",\"content\":\"dot2私密对话\"}]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.createContext("/api/v0/fleet", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"nodes\":[]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            server.start();
            int port = server.getAddress().getPort();

            // Voice Gateway mode (allowSiblingMediaFallback = false) must NEVER leak dot2-d's conversation!
            CoreApiClient.CoreSnapshot voiceSnap = CoreApiClient.fetchSnapshot(
                    "127.0.0.1", port, "android-2", null, true, false);
            assertTrue(voiceSnap.transcript.isEmpty(), "Voice mode must not inherit sibling dot2-d transcript");
            assertTrue(siblingConvHits.get() == 0, "Voice mode must not even query dot2-d conversation");

            // Manage-Only mode (allowSiblingMediaFallback = true) DOES show primary speaker conversation.
            CoreApiClient.CoreSnapshot manageSnap = CoreApiClient.fetchSnapshot(
                    "127.0.0.1", port, "android-2", null, true, true);
            assertTrue(manageSnap.transcript.size() == 1 && "dot2私密对话".equals(manageSnap.transcript.get(0).text),
                    "Manage-Only mode should fall back to primary online speaker conversation");
        } finally {
            server.stop(0);
        }
    }

    private static void testInitialMutedAndPlayFailedReceipts() throws Exception {
        final List<Map<String, Object>> sent = new ArrayList<Map<String, Object>>();
        NorthboundSession session = new NorthboundSession(
                "android-2", "Phone", "2.3.5-android",
                new NorthboundSession.FrameTransport() {
                    @Override
                    public void sendJsonLine(String jsonLine) {
                        sent.add(MiniJson.parseObject(jsonLine));
                    }
                },
                new NorthboundSession.BaseListener());
        session.setInitialMuted(true);
        session.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":2,\"generation\":1}");
        boolean sawMutedTrue = false;
        for (Map<String, Object> f : sent) {
            if ("mute_state".equals(MiniJson.getString(f, "type", ""))
                    && MiniJson.getBoolean(f, "muted", false)) {
                sawMutedTrue = true;
            }
        }
        assertTrue(sawMutedTrue, "setInitialMuted(true) must report muted=true after hello_ack");
        assertTrue(session.isMuted(), "session.isMuted() must remain true");

        sent.clear();
        session.handleIncomingLine("{\"type\":\"play_begin\",\"task_id\":\"P-fail-1\",\"device\":\"android-2\",\"channel\":\"voice\",\"sample_rate\":24000,\"channels\":1,\"format\":\"s16le\"}");
        session.sendPlayFailed("P-fail-1", "AudioTrack uninitialized");
        boolean sawFailedReceipt = false;
        boolean sawDeviceConfirmed = false;
        for (Map<String, Object> f : sent) {
            if ("play_receipt".equals(MiniJson.getString(f, "type", ""))
                    && "P-fail-1".equals(MiniJson.getString(f, "task_id", ""))) {
                String st = MiniJson.getString(f, "stage", "");
                if ("failed".equals(st)) sawFailedReceipt = true;
                if ("device_confirmed".equals(st)) sawDeviceConfirmed = true;
            }
        }
        assertTrue(sawFailedReceipt && !sawDeviceConfirmed,
                "sendPlayFailed must emit play_receipt(stage=failed) and NEVER device_confirmed");
    }

    private static void testLocalKwsDeployConcurrentReloadRollbackAndTimeout() throws Exception {
        File tmpDir = Files.createTempDirectory("hm-kws-deploy-test").toFile();
        try {
            final LocalKwsEngine engine = new LocalKwsEngine(tmpDir, null, "android-test", null);
            String kwV1 = "n ǐ h ǎo x iǎo zh ì @你好小智\nt uì x ià b a @退下吧\n";
            String kwV2 = "n ǐ h ǎo x iǎo zh ì @你好小智\nt íng zh ǐ b a @停止吧\n";
            String kwBad = "n ǐ h ǎo x iǎo zh ì @你好小智\nx īn t uì x ià @新退下\n";

            // Seed initial state (non-blocking)
            LocalKwsEngine.ValidationResult initVr = engine.deployKeywords(
                    kwV1, Collections.singletonList("退下吧"), 0.22, 3.00);
            assertTrue(initVr.valid, "Initial deployKeywords must succeed: " + initVr.detail);
            String hashV1 = engine.getCurrentKeywordsHash();
            assertTrue(!hashV1.isEmpty(), "Initial keywords hash must be non-empty");

            // 1. Concurrent reload with worker thread acquiring synchronized(engine): proves ZERO monitor deadlock
            engine.configureReloadBarrierForTest(true, 1500L);
            final long seqBeforeSuccess = engine.getRequestedReloadSeqForTest();
            final AtomicBoolean workerAcquiredMonitor = new AtomicBoolean(false);
            Thread okWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.currentTimeMillis() + 1200L;
                    while (System.currentTimeMillis() < deadline) {
                        long seq = engine.getRequestedReloadSeqForTest();
                        if (seq > seqBeforeSuccess) {
                            // Explicitly acquire synchronized(this) on engine while deployKeywords is waiting!
                            String h = engine.getCurrentKeywordsHash();
                            boolean r = engine.isRetreatWord("停止吧");
                            if (h != null && !h.isEmpty() && r) {
                                workerAcquiredMonitor.set(true);
                            }
                            engine.notifyReloadOutcomeForTest(seq, true, "applied");
                            return;
                        }
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }, "test-kws-ok-worker");
            okWorker.start();
            long t0 = System.currentTimeMillis();
            LocalKwsEngine.ValidationResult okVr = engine.deployKeywords(
                    kwV2, Collections.singletonList("停止吧"), 0.35, 2.40);
            long elapsedSuccess = System.currentTimeMillis() - t0;
            okWorker.join(1000L);

            assertTrue(okVr.valid, "Concurrent deployKeywords must succeed: " + okVr.detail);
            assertTrue(workerAcquiredMonitor.get(), "Worker thread must acquire synchronized(engine) without deadlocking");
            assertTrue(elapsedSuccess < 1000L, "Concurrent deploy must complete quickly without timeout, got " + elapsedSuccess + "ms");
            assertTrue(Math.abs(engine.getThresholdForTest() - 0.35) < 1e-6, "Threshold must update to 0.35");
            assertTrue(Math.abs(engine.getScoreForTest() - 2.40) < 1e-6, "Score must update to 2.40");
            assertTrue(engine.isRetreatWord("停止吧") && !engine.isRetreatWord("退下吧"), "Retreat words must update");
            String hashV2 = engine.getCurrentKeywordsHash();
            File kwFile = new File(tmpDir, "kw.txt");
            String diskV2 = new String(Files.readAllBytes(kwFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(kwV2.equals(diskV2), "kw.txt must reflect V2 after success");

            // 2. Reload failure -> MUST roll back threshold, score, retreatWords, kw.txt, and currentKeywordsHash
            final long seqBeforeFail = engine.getRequestedReloadSeqForTest();
            Thread failWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.currentTimeMillis() + 1200L;
                    while (System.currentTimeMillis() < deadline) {
                        long seq = engine.getRequestedReloadSeqForTest();
                        if (seq > seqBeforeFail) {
                            engine.getCurrentKeywordsHash(); // prove monitor is unlocked
                            engine.notifyReloadOutcomeForTest(seq, false, "jni_reload_failed:simulated");
                            return;
                        }
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }, "test-kws-fail-worker");
            failWorker.start();
            LocalKwsEngine.ValidationResult failVr = engine.deployKeywords(
                    kwBad, Collections.singletonList("新退下"), 0.55, 1.50);
            failWorker.join(1000L);

            assertTrue(!failVr.valid && failVr.detail.contains("jni_reload_failed"),
                    "Failed reload must return valid=false with failure detail, got " + failVr.detail);
            assertTrue(Math.abs(engine.getThresholdForTest() - 0.35) < 1e-6, "Threshold must roll back to 0.35");
            assertTrue(Math.abs(engine.getScoreForTest() - 2.40) < 1e-6, "Score must roll back to 2.40");
            assertTrue(engine.isRetreatWord("停止吧") && !engine.isRetreatWord("新退下"),
                    "Retreat words must roll back on reload failure");
            String diskAfterFail = new String(Files.readAllBytes(kwFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(kwV2.equals(diskAfterFail), "kw.txt must be restored from kw.txt.bak on reload failure");
            assertTrue(hashV2.equals(engine.getCurrentKeywordsHash()),
                    "currentKeywordsHash must roll back to V2 hash on reload failure");

            // 3. Reload timeout -> MUST roll back threshold, score, retreatWords, kw.txt, and currentKeywordsHash
            engine.configureReloadBarrierForTest(true, 120L);
            LocalKwsEngine.ValidationResult timeoutVr = engine.deployKeywords(
                    kwBad, Collections.singletonList("新退下"), 0.60, 1.20);
            assertTrue(!timeoutVr.valid && "reload_timeout".equals(timeoutVr.detail),
                    "Timed-out reload must return reload_timeout, got " + timeoutVr.detail);
            assertTrue(Math.abs(engine.getThresholdForTest() - 0.35) < 1e-6, "Threshold must roll back on timeout");
            assertTrue(Math.abs(engine.getScoreForTest() - 2.40) < 1e-6, "Score must roll back on timeout");
            assertTrue(engine.isRetreatWord("停止吧") && !engine.isRetreatWord("新退下"),
                    "Retreat words must roll back on timeout");
            String diskAfterTimeout = new String(Files.readAllBytes(kwFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(kwV2.equals(diskAfterTimeout), "kw.txt must be restored on timeout");
            assertTrue(hashV2.equals(engine.getCurrentKeywordsHash()),
                    "currentKeywordsHash must roll back on timeout");
        } finally {
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tmpDir.delete();
        }
    }

    private static void testBoundedTwoTierQueueStressAndPriority() throws Exception {
        // 1. Deterministic priority + bounded audio drop
        BoundedTwoTierQueue q = new BoundedTwoTierQueue(4, 4);
        for (int i = 0; i < 6; i++) {
            assertTrue(q.enqueue("audio-" + i, true), "audio enqueue must succeed");
        }
        assertTrue(q.enqueue("ctrl-0", false), "ctrl-0 enqueue must succeed");
        assertTrue(q.enqueue("ctrl-1", false), "ctrl-1 enqueue must succeed");
        assertTrue(q.getDroppedAudio() == 2L, "Expected 2 dropped oldest audio frames, got " + q.getDroppedAudio());
        assertTrue(q.getDroppedControl() == 0L, "Expected 0 dropped control frames");
        assertTrue("ctrl-0".equals(q.takeNext(10)), "Control frame 0 must drain before audio");
        assertTrue("ctrl-1".equals(q.takeNext(10)), "Control frame 1 must drain before audio");
        assertTrue("audio-2".equals(q.takeNext(10)), "Oldest retained audio must be audio-2");
        assertTrue("audio-3".equals(q.takeNext(10)), "Expected audio-3");
        assertTrue("audio-4".equals(q.takeNext(10)), "Expected audio-4");
        assertTrue("audio-5".equals(q.takeNext(10)), "Expected audio-5");
        assertTrue(q.takeNext(0) == null, "Queue must be empty after draining");

        // 2. Multi-threaded burst stress: zero underflow stalls, zero control drops, fast close wakeup
        final BoundedTwoTierQueue stress = new BoundedTwoTierQueue(256, 64);
        final int ctrlPerThread = 50;
        final int audioPerThread = 200;
        final int expectedCtrl = ctrlPerThread * 2;
        final AtomicInteger receivedCtrl = new AtomicInteger(0);
        final AtomicInteger receivedAudio = new AtomicInteger(0);
        final CountDownLatch producersDone = new CountDownLatch(4);
        final CountDownLatch consumerDone = new CountDownLatch(1);

        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!stress.isClosed() || stress.size() > 0) {
                        String msg = stress.takeNext(200);
                        if (msg == null) {
                            if (producersDone.getCount() == 0 && stress.size() == 0) {
                                break;
                            }
                            continue;
                        }
                        if (msg.startsWith("C:")) {
                            receivedCtrl.incrementAndGet();
                        } else if (msg.startsWith("A:")) {
                            receivedAudio.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    consumerDone.countDown();
                }
            }
        }, "test-queue-consumer");
        consumer.start();

        long startMs = System.currentTimeMillis();
        Thread[] producers = new Thread[4];
        for (int p = 0; p < 2; p++) {
            final int id = p;
            producers[p] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < audioPerThread; i++) {
                            stress.enqueue("A:" + id + ":" + i, true);
                            if ((i & 15) == 0) {
                                Thread.yield();
                            }
                        }
                    } finally {
                        producersDone.countDown();
                    }
                }
            }, "test-audio-prod-" + p);
            producers[p].start();
        }
        for (int p = 0; p < 2; p++) {
            final int id = p;
            producers[2 + p] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < ctrlPerThread; i++) {
                            stress.enqueue("C:" + id + ":" + i, false);
                            if ((i & 7) == 0) {
                                Thread.yield();
                            }
                        }
                    } finally {
                        producersDone.countDown();
                    }
                }
            }, "test-ctrl-prod-" + p);
            producers[2 + p].start();
        }
        assertTrue(producersDone.await(2000L, TimeUnit.MILLISECONDS), "Producers must finish promptly");
        // Wait briefly for consumer to drain remaining buffered items, then close()
        long waitDrainDeadline = System.currentTimeMillis() + 1000L;
        while (stress.size() > 0 && System.currentTimeMillis() < waitDrainDeadline) {
            Thread.sleep(5);
        }
        long closeStart = System.currentTimeMillis();
        stress.close();
        assertTrue(consumerDone.await(500L, TimeUnit.MILLISECONDS), "Consumer must wake immediately on close()");
        long closeLatency = System.currentTimeMillis() - closeStart;
        long totalElapsed = System.currentTimeMillis() - startMs;

        assertTrue(receivedCtrl.get() == expectedCtrl,
                "All " + expectedCtrl + " control frames must be delivered, got " + receivedCtrl.get());
        assertTrue(stress.getDroppedControl() == 0L, "Control queue must drop 0 frames");
        assertTrue(receivedAudio.get() + stress.getDroppedAudio() == (long) (audioPerThread * 2),
                "Audio accounting mismatch: received=" + receivedAudio.get() + " dropped=" + stress.getDroppedAudio());
        assertTrue(closeLatency < 200L, "close() must wake blocked takeNext immediately, took " + closeLatency + "ms");
        assertTrue(totalElapsed < 1500L, "BoundedTwoTierQueue stress must run without stalls, took " + totalElapsed + "ms");
        assertTrue(!stress.enqueue("after-close", false), "enqueue after close must return false");
    }

    private static void testModeResponseMusicPausedAndSiblingFallback() {
        // 1. Own device paused
        String ownPausedJson = "{\"default_duplex\":\"half\",\"media\":{\"android-2\":{\"state\":\"paused\",\"title\":\"夜曲\",\"source\":\"netease\"}}}";
        CoreApiClient.ModeState ownPaused = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(ownPausedJson), "android-2", false);
        assertTrue(!ownPaused.musicActive && ownPaused.musicPaused
                        && "夜曲".equals(ownPaused.musicTitle) && "netease".equals(ownPaused.musicSource),
                "Own paused media must set musicPaused=true, musicActive=false");

        // 2. Sibling paused: visible in Manage-Only (allowSiblingMediaFallback=true), hidden in Voice Gateway (false)
        String siblingPausedJson = "{\"default_duplex\":\"half\",\"media\":{\"dot2-d\":{\"state\":\"paused\",\"title\":\"晴天\",\"source\":\"netease\"}}}";
        CoreApiClient.ModeState managePaused = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(siblingPausedJson), "android-2", true);
        assertTrue(!managePaused.musicActive && managePaused.musicPaused && "晴天".equals(managePaused.musicTitle),
                "Manage-Only fallback must surface paused sibling media so Play/Resume stays reachable");
        CoreApiClient.ModeState voiceIsolated = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(siblingPausedJson), "android-2", false);
        assertTrue(!voiceIsolated.musicActive && !voiceIsolated.musicPaused && voiceIsolated.musicTitle.isEmpty(),
                "Voice Gateway mode must NOT leak sibling paused media");

        // 3. Playing sibling takes precedence over paused sibling
        String mixedJson = "{\"default_duplex\":\"half\",\"media\":{\"dot2-d\":{\"state\":\"paused\",\"title\":\"旧歌\",\"source\":\"netease\"},\"dot2-e\":{\"state\":\"playing\",\"title\":\"新歌\",\"source\":\"local\"}}}";
        CoreApiClient.ModeState mixed = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(mixedJson), "android-2", true);
        assertTrue(mixed.musicActive && !mixed.musicPaused && "新歌".equals(mixed.musicTitle),
                "Playing sibling must win over paused sibling");
    }

    private static void testManageOnlyTranscriptMergeAndRepeatedMessages() {
        List<CoreApiClient.ChatEntry> rawServer = new ArrayList<CoreApiClient.ChatEntry>();
        // 10 turns = 20 entries (> 6, previously caused compounding duplication bug!)
        rawServer.add(new CoreApiClient.ChatEntry("user", "几点了", 100_000L));
        rawServer.add(new CoreApiClient.ChatEntry("assistant", "现在上午十点。", 101_000L));
        for (int i = 1; i < 10; i++) {
            long base = 100_000L + i * 30_000L;
            rawServer.add(new CoreApiClient.ChatEntry("user", "问题" + i, base));
            rawServer.add(new CoreApiClient.ChatEntry("assistant", "回答" + i, base + 1_000L));
        }

        List<CoreApiClient.ChatEntry> local = Arrays.asList(
                // Already synced on server at index 0 (within 15s window) -> must deduplicate even beyond last 6!
                new CoreApiClient.ChatEntry("user", "几点了", 102_000L),
                // Legitimate repeated question much later (>15s apart) -> must be preserved!
                new CoreApiClient.ChatEntry("user", "几点了", 900_000L),
                // Brand new local assistant reply -> must be preserved!
                new CoreApiClient.ChatEntry("assistant", "现在下午三点。", 901_000L)
        );

        // Repeat merge 5 times from rawServer (exact GatewayService contract) -> zero compounding duplication
        List<CoreApiClient.ChatEntry> first = null;
        for (int round = 0; round < 5; round++) {
            List<CoreApiClient.ChatEntry> merged = CoreApiClient.mergeManageOnlyTranscript(rawServer, local);
            assertTrue(merged.size() == 22,
                    "Expected deterministic 22 entries (20 server + 2 new local) on round " + round + ", got " + merged.size());
            if (first == null) {
                first = merged;
            }
        }
        int timeQuestionCount = 0;
        for (CoreApiClient.ChatEntry e : first) {
            if ("user".equals(e.role) && "几点了".equals(e.text)) {
                timeQuestionCount++;
            }
        }
        assertTrue(timeQuestionCount == 2,
                "Expected both original (ts=100s) and repeated (ts=900s) '几点了' preserved, got " + timeQuestionCount);
        assertTrue("现在下午三点。".equals(first.get(first.size() - 1).text),
                "Newest local assistant reply must appear at end of merged transcript");
    }

    private static void testChatSseCancelledAndErrorReplies() throws Exception {
        String cancelledSse = "data: {\"type\":\"session\",\"session_id\":\"S-9\"}\n\n"
                + "data: {\"type\":\"token\",\"text\":\"部分回答\"}\n\n"
                + "data: {\"type\":\"cancelled\"}\n\n"
                + "data: {\"type\":\"turn_end\"}\n\n";
        CoreApiClient.ChatReply cancelled = CoreApiClient.parseChatSseStream(
                new BufferedReader(new StringReader(cancelledSse)));
        assertTrue(!cancelled.ok && "cancelled".equals(cancelled.error),
                "SSE cancelled event must mark reply ok=false, error=cancelled");

        String errSse = "data: {\"type\":\"error\",\"message\":\"upstream_500\"}\n\n";
        CoreApiClient.ChatReply err = CoreApiClient.parseChatSseStream(
                new BufferedReader(new StringReader(errSse)));
        assertTrue(!err.ok && "upstream_500".equals(err.error),
                "SSE error event must mark reply ok=false with message");
    }

    private static void testWave3SecurityAudioAndProtocolHardening() throws Exception {
        // 1. P0-1: UDP beacon ignores spoofed JSON "host" and rejects non-private/non-Tailscale sender IP
        String spoofBeacon = "{\"type\":\"home_mate_beacon\",\"host\":\"evil.attacker.com\",\"web_port\":8930,\"gateway_port\":8932,\"core_id\":\"hmcore_real\"}";
        homes.mymate.gateway.protocol.CoreDiscovery.DiscoveredCore lanParsed =
                homes.mymate.gateway.protocol.CoreDiscovery.parseUdpBeacon(spoofBeacon, "192.168.1.73", 4L);
        assertTrue(lanParsed != null && "192.168.1.73".equals(lanParsed.host),
                "parseUdpBeacon must bind to senderHost and ignore untrusted JSON host");
        homes.mymate.gateway.protocol.CoreDiscovery.DiscoveredCore pubRejected =
                homes.mymate.gateway.protocol.CoreDiscovery.parseUdpBeacon(spoofBeacon, "8.8.8.8", 4L);
        assertTrue(pubRejected == null,
                "parseUdpBeacon must reject public internet sender IP");

        // 2. P1-12 & P1-13: turn_end status=failed marks ok=false; real hm-core confirmation_required shape parsed
        String failedTurnSse = "data: {\"type\":\"session\",\"session_id\":\"S-fail\"}\n\n"
                + "data: {\"type\":\"turn_end\",\"trace_id\":\"TR-1\",\"status\":\"failed\"}\n\n";
        CoreApiClient.ChatReply failedTurn = CoreApiClient.parseChatSseStream(
                new BufferedReader(new StringReader(failedTurnSse)));
        assertTrue(!failedTurn.ok && "turn_failed".equals(failedTurn.error),
                "turn_end status=failed must set ok=false, error=turn_failed");

        String confirmSse = "data: {\"type\":\"session\",\"session_id\":\"S-conf\"}\n\n"
                + "data: {\"type\":\"confirmation_required\",\"action\":{\"id\":\"ACT-42\",\"state\":\"pending\",\"revision\":1,"
                + "\"preview\":{\"tool\":\"openapi.call\",\"service\":\"nas\",\"method\":\"POST\",\"operation\":\"prune_snapshots\",\"target\":\"/snapshots\"}}}\n\n"
                + "data: {\"type\":\"turn_end\",\"trace_id\":\"TR-2\",\"status\":\"confirmation_required\"}\n\n";
        CoreApiClient.ChatReply confirmReply = CoreApiClient.parseChatSseStream(
                new BufferedReader(new StringReader(confirmSse)));
        assertTrue(confirmReply.ok && confirmReply.hasConfirmation(),
                "confirmation_required SSE event must produce ok=true with hasConfirmation=true");
        assertTrue("ACT-42".equals(confirmReply.confirmationId)
                        && "pending".equals(confirmReply.confirmationState)
                        && confirmReply.confirmationRevision == 1L
                        && "openapi.call".equals(confirmReply.confirmationTool)
                        && confirmReply.confirmationSummary.contains("prune_snapshots")
                        && confirmReply.confirmationSummary.contains("nas"),
                "hm-core preview/state/revision confirmation fields must be extracted, got: " + confirmReply.confirmationSummary);

        // 3. P2-13: NorthboundSession mute_set emits source="core_set" (not "remote_command")
        final List<String> sentFrames = new ArrayList<String>();
        final List<String> muteSources = new ArrayList<String>();
        NorthboundSession ns = new NorthboundSession("android-test", "TestModel", "2.3.6", new NorthboundSession.FrameTransport() {
            @Override
            public void sendJsonLine(String jsonLine) {
                sentFrames.add(jsonLine);
            }
        }, new NorthboundSession.BaseListener() {
            @Override public void onMuteChanged(boolean m, String src) { muteSources.add(src); }
        });
        ns.handleIncomingLine("{\"type\":\"mute_set\",\"device\":\"android-test\",\"muted\":true,\"task_id\":\"M-1\"}");
        assertTrue(muteSources.size() == 1 && "core_set".equals(muteSources.get(0)),
                "mute_set listener source must be core_set");
        assertTrue(!sentFrames.isEmpty() && sentFrames.get(sentFrames.size() - 1).contains("\"source\":\"core_set\""),
                "mute_state frame must carry source=core_set");

        // 4. P2-15: MiniJson nesting depth cap throws IllegalArgumentException (never StackOverflowError)
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append("{\"a\":");
        deep.append("1");
        for (int i = 0; i < 100; i++) deep.append("}");
        boolean threwDepthErr = false;
        try {
            MiniJson.parseObject(deep.toString());
        } catch (IllegalArgumentException ex) {
            threwDepthErr = ex.getMessage() != null && ex.getMessage().contains("depth");
        }
        assertTrue(threwDepthErr, "MiniJson must reject >64 nested objects with IllegalArgumentException");

        // 5. P2-15: CoreApiClient.isPrivateOrTailscaleHost enforces 100.64.0.0/10 (rejects 100.1.2.3)
        assertTrue(!CoreApiClient.isPrivateOrTailscaleHost("100.1.2.3"),
                "100.1.2.3 is public and must NOT be treated as Tailscale CGNAT");
        assertTrue(CoreApiClient.isPrivateOrTailscaleHost("100.64.0.1"),
                "100.64.0.1 must be accepted as Tailscale CGNAT");
        assertTrue(CoreApiClient.isPrivateOrTailscaleHost("100.127.255.254"),
                "100.127.255.254 must be accepted as Tailscale CGNAT");

        // 6. P0-1, P1-13 & P2-14: Real HTTP verification of probeCoreHttp, fetchRoomSummary,
        //    fetchSnapshot identity preflight, sendChatTextWithReply confirmation propagation,
        //    and hm-core /api/v1/confirmations/{id}/approve -> /execute & /reject contracts.
        final AtomicInteger statusHits = new AtomicInteger(0);
        final AtomicInteger modeHits = new AtomicInteger(0);
        final AtomicInteger authHeaderLeaksOnAuth = new AtomicInteger(0);
        final AtomicInteger authHeaderSeenOnStatus = new AtomicInteger(0);
        final AtomicBoolean v1ApproveHit = new AtomicBoolean(false);
        final AtomicBoolean v1ExecuteHit = new AtomicBoolean(false);
        final AtomicBoolean v1RejectHit = new AtomicBoolean(false);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = srv.getAddress().getPort();
        srv.createContext("/api/v0/auth", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    authHeaderLeaksOnAuth.incrementAndGet();
                }
                byte[] b = "{\"configured\":true,\"core_id\":\"hmcore_room_1\",\"lan_gateway_exempt\":false}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v0/status", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                statusHits.incrementAndGet();
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    authHeaderSeenOnStatus.incrementAndGet();
                }
                byte[] b = ("{\"product\":\"Home Mate\",\"assistant_name\":\"小智\",\"core_id\":\"hmcore_room_1\","
                        + "\"auth\":{\"configured\":true,\"lan_gateway_exempt\":false},"
                        + "\"gateway\":{\"voice_gateway_open\":true,\"devices\":[{\"stable_id\":\"dot2-d\",\"online\":true,\"volume\":0.72}]}}")
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v0/mode", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                modeHits.incrementAndGet();
                byte[] b = "{\"devices\":[{\"device\":\"dot2-d\",\"duplex_effective\":\"half\"}]}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v0/chat", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                String sse = "data: {\"type\":\"session\",\"session_id\":\"S-http-1\"}\n\n"
                        + "data: {\"type\":\"confirmation_required\",\"action\":{\"id\":\"ACT-42\",\"state\":\"pending\",\"revision\":1,"
                        + "\"preview\":{\"tool\":\"openapi.call\",\"service\":\"nas\",\"method\":\"POST\",\"operation\":\"prune_snapshots\",\"target\":\"/snapshots\"}}}\n\n"
                        + "data: {\"type\":\"turn_end\",\"trace_id\":\"TR-9\",\"status\":\"confirmation_required\"}\n\n";
                byte[] b = sse.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v1/confirmations/ACT-42/approve", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(reqBody);
                if ("POST".equals(ex.getRequestMethod())
                        && "pending".equals(MiniJson.getString(parsed, "expected_state", ""))
                        && MiniJson.getLong(parsed, "expected_revision", -1L) == 1L
                        && parsed.size() == 2) {
                    v1ApproveHit.set(true);
                }
                byte[] b = "{\"id\":\"ACT-42\",\"state\":\"approved\",\"revision\":2}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v1/confirmations/ACT-42/execute", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(reqBody);
                if ("POST".equals(ex.getRequestMethod())
                        && "approved".equals(MiniJson.getString(parsed, "expected_state", ""))
                        && MiniJson.getLong(parsed, "expected_revision", -1L) == 2L
                        && parsed.size() == 2) {
                    v1ExecuteHit.set(true);
                }
                byte[] b = "{\"id\":\"ACT-42\",\"state\":\"consumed\",\"revision\":3}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.createContext("/api/v1/confirmations/ACT-43/reject", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(reqBody);
                if ("POST".equals(ex.getRequestMethod())
                        && "pending".equals(MiniJson.getString(parsed, "expected_state", ""))
                        && MiniJson.getLong(parsed, "expected_revision", -1L) == 1L
                        && parsed.size() == 2) {
                    v1RejectHit.set(true);
                }
                byte[] b = "{\"id\":\"ACT-43\",\"state\":\"rejected\",\"revision\":2}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        srv.start();
        try {
            // probeCoreHttp NEVER sends Authorization and rejects foreign core_id
            assertTrue(!CoreApiClient.probeCoreHttp("127.0.0.1", port, "hmcore_other", 1000),
                    "probeCoreHttp must return false when pinned expectedCoreId mismatches");
            assertTrue(CoreApiClient.probeCoreHttp("127.0.0.1", port, "hmcore_room_1", 1000),
                    "probeCoreHttp must return true when pinned expectedCoreId matches");
            assertTrue(authHeaderLeaksOnAuth.get() == 0,
                    "probeCoreHttp / preflight must NEVER send Authorization header to /api/v0/auth");

            // fetchRoomSummary with EMPTY expectedCoreId must fail closed (NEVER transmit Bearer token!)
            CoreApiClient.RoomNodeSummary unpinned = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "Room", "hm1_secret", "");
            assertTrue(unpinned.online && authHeaderSeenOnStatus.get() == 0,
                    "fetchRoomSummary with empty expectedCoreId must NOT transmit Bearer token");

            // fetchRoomSummary with mismatched expectedCoreId must abort BEFORE hitting /api/v0/status!
            int beforeMismatch = statusHits.get();
            CoreApiClient.RoomNodeSummary wrong = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "Room", "hm1_secret", "hmcore_other");
            assertTrue(!wrong.online && "core_id_mismatch".equals(wrong.error) && statusHits.get() == beforeMismatch,
                    "fetchRoomSummary must abort on core_id mismatch without transmitting token");

            // fetchSnapshot with mismatched expectedCoreId must abort BEFORE hitting /api/v0/mode!
            int modeBefore = modeHits.get();
            CoreApiClient.CoreSnapshot wrongSnap = CoreApiClient.fetchSnapshot(
                    "127.0.0.1", port, "android-1", "hm1_secret", true, true, "hmcore_other");
            assertTrue(!wrongSnap.reachable && "core_id_mismatch".equals(wrongSnap.error) && modeHits.get() == modeBefore,
                    "fetchSnapshot must abort on core_id mismatch before transmitting token to /api/v0/mode");

            // Matching expectedCoreId -> succeeds, sends Bearer token, and populates volume = 0.72!
            CoreApiClient.RoomNodeSummary ok = CoreApiClient.fetchRoomSummary(
                    "127.0.0.1", port, 8932, "Room", "hm1_secret", "hmcore_room_1");
            assertTrue(ok.online && ok.volume != null && Math.abs(ok.volume - 0.72) < 1e-6 && authHeaderSeenOnStatus.get() == 1,
                    "fetchRoomSummary must verify core_id, send token, and expose device volume (0.72), got " + ok.volume);

            // sendChatTextWithReply MUST propagate all confirmation fields (id, state, revision, tool, summary)
            CoreApiClient.ChatReply httpChat = CoreApiClient.sendChatTextWithReply(
                    "127.0.0.1", port, null, "hm1_secret", "清理快照");
            assertTrue(httpChat.ok && httpChat.hasConfirmation()
                            && "S-http-1".equals(httpChat.sessionId)
                            && "ACT-42".equals(httpChat.confirmationId)
                            && "pending".equals(httpChat.confirmationState)
                            && httpChat.confirmationRevision == 1L
                            && "openapi.call".equals(httpChat.confirmationTool)
                            && httpChat.confirmationSummary.contains("prune_snapshots"),
                    "sendChatTextWithReply must preserve all confirmation fields over real HTTP SSE");

            // decideConfirmation approve executes real two-step /api/v1/confirmations/{id}/approve -> /execute
            assertTrue(CoreApiClient.decideConfirmation(
                            "127.0.0.1", port, httpChat.confirmationId,
                            httpChat.confirmationState, httpChat.confirmationRevision, true, "hm1_secret")
                            && v1ApproveHit.get() && v1ExecuteHit.get(),
                    "decideConfirmation(approve=true) must POST /api/v1/confirmations/{id}/approve then /execute with strict expected_state & expected_revision");

            // decideConfirmation reject executes /api/v1/confirmations/{id}/reject
            assertTrue(CoreApiClient.decideConfirmation(
                            "127.0.0.1", port, "ACT-43", "pending", 1L, false, "hm1_secret")
                            && v1RejectHit.get(),
                    "decideConfirmation(approve=false) must POST /api/v1/confirmations/{id}/reject with strict expected_state & expected_revision");
        } finally {
            srv.stop(0);
        }
    }

    private static void testWave4DeviceIsolationAndUtteranceRouting() {
        List<CoreApiClient.DeviceInfo> devicesWithSibling = Arrays.asList(
                new CoreApiClient.DeviceInfo("dot2-d", "EchoDot2", true, true, "dot2_farfield_v1", 0.50),
                new CoreApiClient.DeviceInfo("android-phone-1", "Phone", false, true, "android_farfield_v1", 0.65)
        );
        CoreApiClient.CoreSnapshot snap = new CoreApiClient.CoreSnapshot(
                true, false, "小智", "build-1", "half", false, true, "off",
                false, "", "", devicesWithSibling,
                Collections.<CoreApiClient.FleetNodeInfo>emptyList(),
                Collections.<CoreApiClient.ChatEntry>emptyList(),
                null);

        // MG-P0-02: Voice Gateway profile (manageOnly=false) MUST NEVER hijack sibling dot2-d on :8932 disconnect
        assertTrue("android-phone-1".equals(
                        DeviceRoutingPolicy.resolveEffectiveDeviceId("android-phone-1", false, snap)),
                "Voice Gateway must ALWAYS resolve ownDeviceId even when disconnected and sibling dot2-d is online");
        assertTrue(!DeviceRoutingPolicy.allowSiblingMediaFallback(false),
                "Voice Gateway must NEVER enable allowSiblingMediaFallback");

        // MG-P0-01: Voice Gateway profile (manageOnly=false) MUST NEVER fall back to unbound /api/v0/chat or sibling media when disconnected
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(false, false, false)
                        == DeviceRoutingPolicy.UtteranceRoute.REJECT_VOICE_DISCONNECTED,
                "Disconnected Voice Gateway chat must reject instead of falling back to /api/v0/chat");
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(false, false, true)
                        == DeviceRoutingPolicy.UtteranceRoute.REJECT_VOICE_DISCONNECTED,
                "Disconnected Voice Gateway MediaSession button must NOT hijack sibling dot2-d");
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(false, true, false)
                        == DeviceRoutingPolicy.UtteranceRoute.NORTHBOUND_SESSION,
                "Connected Voice Gateway chat must route over NorthboundSession");
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(false, true, true)
                        == DeviceRoutingPolicy.UtteranceRoute.NORTHBOUND_SESSION,
                "Connected Voice Gateway MediaSession button must route over NorthboundSession");

        // Explicit Manage-Only profile (manageOnly=true) DOES target primary speaker and allow HTTP chat / media control
        assertTrue("dot2-d".equals(
                        DeviceRoutingPolicy.resolveEffectiveDeviceId("android-phone-1", true, snap)),
                "Explicit Manage-Only profile should resolve primary online speaker dot2-d");
        assertTrue(DeviceRoutingPolicy.allowSiblingMediaFallback(true),
                "Explicit Manage-Only profile should allow sibling media fallback");
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(true, false, true)
                        == DeviceRoutingPolicy.UtteranceRoute.MANAGE_ONLY_MEDIA_CONTROL,
                "Manage-Only media button must route to MANAGE_ONLY_MEDIA_CONTROL");
        assertTrue(DeviceRoutingPolicy.resolveUtteranceRoute(true, false, false)
                        == DeviceRoutingPolicy.UtteranceRoute.MANAGE_ONLY_HTTP_CHAT,
                "Manage-Only text chat must route to MANAGE_ONLY_HTTP_CHAT");

        // Blocker #1: Stop / Orb long-press MUST NOT target sibling dot2-d on Voice Gateway disconnect
        assertTrue(DeviceRoutingPolicy.resolveActiveCoreStopRoute(false, true)
                        == DeviceRoutingPolicy.StopRoute.LOCAL_RETREAT_NORTHBOUND,
                "Connected Voice Gateway stop must send local retreat over northbound");
        assertTrue(DeviceRoutingPolicy.resolveActiveCoreStopRoute(false, false)
                        == DeviceRoutingPolicy.StopRoute.LOCAL_ONLY_DISCONNECTED,
                "Disconnected Voice Gateway stop must flush local audio only (never remote stop dot2-d)");
        assertTrue(DeviceRoutingPolicy.resolveActiveCoreStopRoute(true, false)
                        == DeviceRoutingPolicy.StopRoute.MANAGE_ONLY_REMOTE_STOP,
                "Manage-Only stop must target remote speaker");
        assertTrue("".equals(DeviceRoutingPolicy.resolvePrimaryRemoteDeviceId(false, snap)),
                "Voice Gateway must NEVER resolve sibling dot2-d as primary remote device");
        assertTrue("dot2-d".equals(DeviceRoutingPolicy.resolvePrimaryRemoteDeviceId(true, snap)),
                "Manage-Only must resolve connected sibling dot2-d as primary remote device");

        // Blocker #2: Volume slider MUST NOT read/write sibling dot2-d on Voice Gateway disconnect
        assertTrue(!DeviceRoutingPolicy.shouldUseRemoteVolumeControl(false),
                "Voice Gateway volume slider must control local audio only");
        assertTrue(DeviceRoutingPolicy.resolveRemoteDisplayVolume(false, snap) == null,
                "Voice Gateway must NEVER display sibling dot2-d volume on disconnect");
        assertTrue(DeviceRoutingPolicy.shouldUseRemoteVolumeControl(true),
                "Manage-Only volume slider must control remote speaker volume");
        Double manageVol = DeviceRoutingPolicy.resolveRemoteDisplayVolume(true, snap);
        assertTrue(manageVol != null && Math.abs(manageVol.doubleValue() - 0.50) < 1e-6,
                "Manage-Only must display connected speaker dot2-d volume (0.50), got " + manageVol);

        // Blocker #3: Offline, loading, or auth-gated Whole-Home room cards MUST preserve saved manageOnly (return null)
        assertTrue(DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(false, false, false) == null,
                "Offline/loading room card must return null manageOnly to preserve saved Voice Gateway profile");
        assertTrue(DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(true, true, false) == null,
                "Auth-gated (401) room card must return null manageOnly to avoid flipping Voice Gateway to Manage-Only");
        assertTrue(Boolean.FALSE.equals(DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(true, false, true)),
                "Online Voice+Manage room card must set manageOnly=false");
        assertTrue(Boolean.TRUE.equals(DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(true, false, false)),
                "Online Manage-Only room card must set manageOnly=true");
    }

    private static void testWave5PerDeviceModeAuth401PropagationAndKwsWorkspaceFence() throws Exception {
        // 1. P1-8: Per-device barge_in & wake_arbitration in parseModeResponse + ?device= URL on GET /api/v0/mode
        assertTrue("/api/v0/mode?device=android-dev+1".equals(CoreApiClient.buildModeGetPath("android-dev 1"))
                        || "/api/v0/mode?device=android-dev%201".equals(CoreApiClient.buildModeGetPath("android-dev 1")),
                "buildModeGetPath must URL-encode deviceId");
        assertTrue("/api/v0/mode".equals(CoreApiClient.buildModeGetPath("")),
                "empty deviceId must omit ?device=");

        String multiDevModeJson = "{\"default_duplex\":\"half\",\"barge_in\":false,"
                + "\"wake_arbitration\":{\"mode\":\"off\",\"peers\":2},"
                + "\"devices\":["
                + "{\"device\":\"dot2-d\",\"duplex\":{\"effective\":\"half\"},\"barge_in\":false,\"wake_arbitration\":\"off\"},"
                + "{\"device\":\"android-w5\",\"duplex\":{\"effective\":\"full\"},\"barge_in\":true,\"wake_arbitration\":\"enforce\",\"wake_ack\":{\"enabled\":false}}"
                + "]}";
        CoreApiClient.ModeState devMs = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(multiDevModeJson), "android-w5", false);
        assertTrue("full".equals(devMs.duplex), "Expected per-device duplex=full");
        assertTrue(devMs.bargeIn, "Expected per-device barge_in=true from devices[] overriding top-level false");
        assertTrue("enforce".equals(devMs.wakeArbitration),
                "Expected per-device wake_arbitration=enforce from devices[], got " + devMs.wakeArbitration);
        assertTrue(!devMs.wakeAck, "Expected per-device wake_ack=false");

        // Fallback to top-level when absent on devices[] entry
        String fallbackModeJson = "{\"default_duplex\":\"half\",\"barge_in\":true,"
                + "\"wake_arbitration\":{\"mode\":\"observe\"},"
                + "\"devices\":[{\"device\":\"android-w5\",\"duplex\":{\"effective\":\"half\"}}]}";
        CoreApiClient.ModeState fbMs = CoreApiClient.parseModeResponse(
                MiniJson.parseObject(fallbackModeJson), "android-w5", false);
        assertTrue(fbMs.bargeIn && "observe".equals(fbMs.wakeArbitration),
                "Must fall back to top-level barge_in/wake_arbitration when absent on devices[]");

        // 2. P1-8 + P1-9: Real HTTP server verifying ?device= query AND 401/403 propagation on lan_gateway_exempt Core
        final List<String> seenModeQueries = new ArrayList<String>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            srv.createContext("/api/v0/mode", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
                        byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(401, resp.length);
                        ex.getResponseBody().write(resp);
                        ex.close();
                        return;
                    }
                    seenModeQueries.add(ex.getRequestURI().getRawQuery());
                    byte[] resp = ("{\"default_duplex\":\"half\",\"barge_in\":false,"
                            + "\"wake_arbitration\":{\"mode\":\"off\"},"
                            + "\"devices\":[{\"device\":\"android-w5\",\"duplex\":{\"effective\":\"full\"},"
                            + "\"barge_in\":true,\"wake_arbitration\":\"enforce\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.createContext("/api/v0/devices/android-w5/conversation", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.createContext("/api/v0/devices/dot2-d/media/control", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(403, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.createContext("/api/v0/devices/dot2-d/volume", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.createContext("/api/v0/devices/dot2-d/stop", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.createContext("/api/v0/chat", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws java.io.IOException {
                    byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            srv.start();
            int port = srv.getAddress().getPort();

            CoreApiClient.CoreSnapshot snap = CoreApiClient.fetchSnapshot(
                    "127.0.0.1", port, "android-w5", null, false, false);
            assertTrue(seenModeQueries.size() == 1 && "device=android-w5".equals(seenModeQueries.get(0)),
                    "fetchSnapshot MUST send GET /api/v0/mode?device=android-w5, got " + seenModeQueries);
            assertTrue(snap.bargeIn && "enforce".equals(snap.wakeArbitration),
                    "fetchSnapshot must populate per-device bargeIn=true & wakeArbitration=enforce");
            assertTrue(snap.reachable && snap.authRequired,
                    "401 on conversation endpoint MUST flip CoreSnapshot.authRequired=true on lan_gateway_exempt Core");

            CoreApiClient.ActionOutcome modePut = CoreApiClient.updateDeviceModeWithResult(
                    "127.0.0.1", port, "android-w5", "full", Boolean.TRUE, null, "enforce", null);
            assertTrue(!modePut.ok && modePut.authRequired && modePut.httpCode == 401,
                    "401 on PUT /api/v0/mode/{dev} must report authRequired=true");

            CoreApiClient.ActionOutcome mediaCtrl = CoreApiClient.controlRemoteMediaWithResult(
                    "127.0.0.1", port, "dot2-d", "pause", null);
            assertTrue(!mediaCtrl.ok && mediaCtrl.authRequired && mediaCtrl.httpCode == 403,
                    "403 on media/control must report authRequired=true");

            CoreApiClient.ActionOutcome volSet = CoreApiClient.setRemoteDeviceVolumeWithResult(
                    "127.0.0.1", port, "dot2-d", 0.5, null);
            assertTrue(!volSet.ok && volSet.authRequired && volSet.httpCode == 401,
                    "401 on remote volume must report authRequired=true");

            CoreApiClient.ActionOutcome stopDev = CoreApiClient.stopRemoteDeviceWithResult(
                    "127.0.0.1", port, "dot2-d", null);
            assertTrue(!stopDev.ok && stopDev.authRequired && stopDev.httpCode == 401,
                    "401 on remote stop must report authRequired=true");

            CoreApiClient.ChatReply chatRes = CoreApiClient.sendChatTextWithReply(
                    "127.0.0.1", port, null, null, "你好");
            assertTrue(!chatRes.ok && chatRes.authRequired,
                    "401 on POST /api/v0/chat must set ChatReply.authRequired=true");
        } finally {
            srv.stop(0);
        }

        // 3. P1-10: Cold-boot KWS workspace prep awaits latch AND never overwrites Core-deployed custom kw.txt
        final File tmpDir = Files.createTempDirectory("hm-kws-w5-race-test").toFile();
        try {
            final CountDownLatch prepStarted = new CountDownLatch(1);
            final CountDownLatch allowPrepFinish = new CountDownLatch(1);
            final AtomicBoolean prepFinishedBeforeDeploy = new AtomicBoolean(false);
            final AtomicBoolean prepDone = new AtomicBoolean(false);
            final LocalKwsEngine engine = new LocalKwsEngine(tmpDir, null, "android-w5-kws", null);
            engine.setAssetPreparer(new LocalKwsEngine.AssetPreparer() {
                @Override
                public void prepareWorkspace() {
                    prepStarted.countDown();
                    try {
                        allowPrepFinish.await(2000L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    // Simulate unpacking model/tokens.txt and syncing bundled defaults
                    File modelDir = new File(tmpDir, "model");
                    modelDir.mkdirs();
                    try {
                        Files.write(new File(modelDir, "tokens.txt").toPath(),
                                "n 1\nǐ 2\nh 3\nǎo 4\ng 5\nāo 6\nd 7\ná 8\nt 9\nuì 10\nx 11\nià 12\nb 13\na 14\n"
                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                    }
                    engine.syncDefaultKeywordsIfNotCustom("");
                    prepDone.set(true);
                }
            });
            engine.start();
            assertTrue(prepStarted.await(2000L, TimeUnit.MILLISECONDS), "AssetPreparer must start on KWS thread");

            final String customCoreKw = "n ǐ h ǎo g āo d á @你好高达\nt uì x ià b a @退下吧\n";
            final LocalKwsEngine.ValidationResult[] deployRes = new LocalKwsEngine.ValidationResult[1];
            Thread deployThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    deployRes[0] = engine.deployKeywords(
                            customCoreKw, Collections.singletonList("退下吧"), 0.25, 2.80);
                    prepFinishedBeforeDeploy.set(prepDone.get());
                }
            }, "test-w5-kws-deploy");
            deployThread.start();
            Thread.sleep(60L);
            assertTrue(deployThread.isAlive(),
                    "deployKeywords MUST wait for workspaceReadyLatch while prepareWorkspace is running");

            allowPrepFinish.countDown();
            deployThread.join(3000L);
            engine.stop();

            assertTrue(prepFinishedBeforeDeploy.get(), "prepareWorkspace must complete before deployKeywords finishes");
            assertTrue(deployRes[0] != null && deployRes[0].valid,
                    "deployKeywords must succeed after tokens.txt unpacked: " + (deployRes[0] != null ? deployRes[0].detail : "null"));

            File kwFile = new File(tmpDir, "kw.txt");
            String onDisk = new String(Files.readAllBytes(kwFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(customCoreKw.equals(onDisk),
                    "Core custom kw.txt MUST survive cold-boot prepareWorkspace intact, got: " + onDisk);

            // Even if syncDefaultKeywordsIfNotCustom runs again (empty or old hash), custom kw.txt is NEVER clobbered!
            assertTrue(engine.syncDefaultKeywordsIfNotCustom("") == null,
                    "syncDefaultKeywordsIfNotCustom(\"\") must NOT overwrite Core custom kw.txt");
            assertTrue(engine.syncDefaultKeywordsIfNotCustom("deadbeef00000000") == null,
                    "syncDefaultKeywordsIfNotCustom(oldHash) must NOT overwrite Core custom kw.txt");
            String onDiskAfter = new String(Files.readAllBytes(kwFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(customCoreKw.equals(onDiskAfter), "Core custom kw.txt must remain byte-identical");
        } finally {
            File modelDir = new File(tmpDir, "model");
            if (modelDir.exists()) {
                File[] mFiles = modelDir.listFiles();
                if (mFiles != null) {
                    for (File f : mFiles) f.delete();
                }
                modelDir.delete();
            }
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tmpDir.delete();
        }
    }

    private static void testV239Dot2KwsSensitivityMigrationGuard() {
        // 1. Exact Dot2 7-mic default (0.32 / 2.50) and near-float values are detected and restored to 0.22 / 3.00
        assertTrue(DeviceRoutingPolicy.isDot2DefaultKwsSensitivity(0.32, 2.50),
                "Exact Dot2 default 0.32/2.50 must be detected");
        assertTrue(DeviceRoutingPolicy.isDot2DefaultKwsSensitivity(0.325, 2.48),
                "Near Dot2 default within tolerance must be detected");
        float[] restored = DeviceRoutingPolicy.migrateClobberedDot2KwsSensitivity(0.32f, 2.50f);
        assertTrue(Math.abs(restored[0] - 0.22f) < 1e-5f && Math.abs(restored[1] - 3.00f) < 1e-5f,
                "Dot2 0.32/2.50 must migrate back to mobile-calibrated 0.22/3.00, got "
                        + restored[0] + "/" + restored[1]);

        // 2. Deliberate custom per-device tuning (e.g. 0.30/2.40, 0.22/3.00, 0.35/2.80) must be preserved untouched
        assertTrue(!DeviceRoutingPolicy.isDot2DefaultKwsSensitivity(0.22, 3.00),
                "Mobile default 0.22/3.00 must NOT be flagged as Dot2 default");
        assertTrue(!DeviceRoutingPolicy.isDot2DefaultKwsSensitivity(0.30, 2.40),
                "Custom smoke sensitivity 0.30/2.40 must NOT be flagged as Dot2 default");
        float[] customKept = DeviceRoutingPolicy.migrateClobberedDot2KwsSensitivity(0.26f, 2.80f);
        assertTrue(Math.abs(customKept[0] - 0.26f) < 1e-5f && Math.abs(customKept[1] - 2.80f) < 1e-5f,
                "Custom sensitivity 0.26/2.80 must be preserved untouched");

        // 3. Half-duplex + Core default barge_in=true MUST keep both barge-in uplink and acoustic barge-in strictly false
        assertTrue(!DeviceRoutingPolicy.shouldAllowBargeInUplink("half", true),
                "Half-duplex playback must NEVER open raw barge-in uplink");
        assertTrue(!DeviceRoutingPolicy.shouldAllowAcousticBargeIn("half", true, true),
                "Half-duplex playback with Core default barge_in=true must NEVER enable direct acoustic barge-in");

        // 4. Full-duplex + barge_in=true enables both; full-duplex + barge_in=false keeps acoustic barge-in false
        assertTrue(DeviceRoutingPolicy.shouldAllowBargeInUplink("full", true),
                "Full-duplex playback must allow barge-in uplink");
        assertTrue(DeviceRoutingPolicy.shouldAllowAcousticBargeIn("full", true, true),
                "Full-duplex playback with barge_in=true must enable acoustic barge-in");
        assertTrue(DeviceRoutingPolicy.shouldAllowBargeInUplink("full", true)
                        && !DeviceRoutingPolicy.shouldAllowAcousticBargeIn("full", false, true),
                "Full-duplex playback with barge_in=false must keep acoustic barge-in disabled");
        assertTrue(!DeviceRoutingPolicy.shouldAllowBargeInUplink("full", false)
                        && !DeviceRoutingPolicy.shouldAllowAcousticBargeIn("full", true, false),
                "Without active playback, both barge-in flags must be false");
    }

    private static void assertFieldExists(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        assertTrue(f != null, "Missing JNI field " + cls.getSimpleName() + "." + name);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ASSERTION FAILED: " + msg);
        }
    }
}
