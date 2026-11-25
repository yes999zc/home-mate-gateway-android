package homes.mymate.gateway.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JVM Unit & Northbound Protocol v2 Contract Test Suite (Phase 1 + Phase 2 + v2.0 Contract).
 */
public final class NorthboundSessionTest {

    private static final class MockTransport implements NorthboundSession.FrameTransport {
        final List<Map<String, Object>> sentFrames = new ArrayList<Map<String, Object>>();

        @Override
        public void sendJsonLine(String jsonLine) {
            sentFrames.add(MiniJson.parseObject(jsonLine));
        }

        Map<String, Object> findLastByType(String type) {
            for (int i = sentFrames.size() - 1; i >= 0; i--) {
                Map<String, Object> f = sentFrames.get(i);
                if (type.equals(f.get("type"))) {
                    return f;
                }
            }
            return null;
        }

        int indexOfFirstType(String type, int fromIndex) {
            for (int i = fromIndex; i < sentFrames.size(); i++) {
                if (type.equals(sentFrames.get(i).get("type"))) {
                    return i;
                }
            }
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        testMiniJsonRoundTrip();
        testHelloAndHandshakeReports();
        testMicSessionAndAudioChunkPresence();
        testKwsDeployAndConfigV2Pipeline();
        testPlayStreamAndPreemptionStop();
        testWakeAndLocalExitRetreat();
        testLocalKwsEngineParserAndTokenValidation();
        testAcousticGuardPrerollAndBargeIn();
        testHelloAuthTokenAndIdleMicUplinkGate();
        testConfigApplyV2KwsDeployAndKwsAgcNormalization();
        testWave4PrerollOrderDynamicSizingAndWireContracts();
        testWave5UplinkGateSerializationActiveStatesEchoHealthAndFocusLoss();
        testV239SpeakingToListeningFlushesRecentPrerollMonotonically();
        System.out.println("PASS: All 13 NorthboundSessionTest suites passed.");
    }

    private static void testMiniJsonRoundTrip() {
        String raw = "{\"type\":\"hello\",\"protocol\":2,\"supported_versions\":[0,2],\"nested\":{\"ok\":true,\"text\":\"你好小智\\n退下\"}}";
        Map<String, Object> parsed = MiniJson.parseObject(raw);
        assertEquals("hello", MiniJson.getString(parsed, "type", ""));
        assertEquals(2L, MiniJson.getLong(parsed, "protocol", 0));
        Map<String, Object> nested = MiniJson.getObject(parsed, "nested");
        assertTrue(MiniJson.getBoolean(nested, "ok", false), "nested.ok should be true");
        assertEquals("你好小智\n退下", MiniJson.getString(nested, "text", ""));

        String serialized = MiniJson.stringify(parsed);
        Map<String, Object> reparsed = MiniJson.parseObject(serialized);
        assertEquals("hello", MiniJson.getString(reparsed, "type", ""));
    }

    private static void testHelloAndHandshakeReports() throws Exception {
        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-living",
                "LDY-B5-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());

        session.sendHello();
        assertEquals(1L, transport.sentFrames.size());
        Map<String, Object> hello = transport.sentFrames.get(0);
        assertEquals("hello", MiniJson.getString(hello, "type", ""));
        assertEquals(2L, MiniJson.getLong(hello, "protocol", 0));
        assertEquals(NorthboundSession.GATEWAY_VERSION, MiniJson.getString(hello, "gateway_version", ""));
        assertTrue(!hello.containsKey("auth_token"), "hello without token must omit auth_token");
        @SuppressWarnings("unchecked")
        Map<String, Object> dev = (Map<String, Object>) MiniJson.getArray(hello, "devices").get(0);
        List<Object> caps = MiniJson.getArray(dev, "capabilities");
        assertTrue(caps.contains("audio_in"), "hello capabilities must advertise audio_in so hm-core starts mic_session");
        assertTrue(caps.contains("config_apply_v2"), "hello capabilities must advertise config_apply_v2");

        session.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":2,\"min_protocol\":0,\"supported_versions\":[0,2],\"core_version\":\"0.2.0\",\"generation\":7}");
        assertTrue(session.isHelloAckReceived(), "helloAckReceived should be true");

        assertNotNull(transport.findLastByType("mode_report"), "mode_report must be sent on hello_ack");
        assertNotNull(transport.findLastByType("mute_state"), "mute_state must be sent on hello_ack");
        assertNotNull(transport.findLastByType("volume_receipt"), "volume_receipt must be sent on hello_ack");
        Map<String, Object> kwsReport = transport.findLastByType("kws_report");
        assertNotNull(kwsReport, "kws_report must be sent on hello_ack");
        assertEquals((long) LocalKwsEngine.DEFAULT_RETREAT_WORDS.size(), MiniJson.getLong(kwsReport, "local_exit", 0));
        assertTrue(LocalKwsEngine.DEFAULT_RETREAT_WORDS.contains("退下"),
                "退下 must be in default retreat words");
        assertTrue(LocalKwsEngine.DEFAULT_KEYWORDS_TXT.contains("t uì x ià @退下"),
                "DEFAULT_KEYWORDS_TXT must include t uì x ià @退下");
        Map<String, Object> echoHealth = transport.findLastByType("echo_health");
        assertNotNull(echoHealth, "echo_health must be sent on hello_ack");
        assertEquals("unknown", MiniJson.getString(echoHealth, "health", ""));
        assertEquals("aec_disabled", MiniJson.getString(echoHealth, "reason", ""));
        Map<String, Object> cfgState = transport.findLastByType("config_state");
        assertNotNull(cfgState, "config_state must be sent on hello_ack");
        assertEquals(2L, MiniJson.getArray(cfgState, "blocks").size());
        assertNotNull(MiniJson.getArray(cfgState, "capabilities"), "config_state must include capabilities");
        assertNotNull(MiniJson.getObject(cfgState, "block_schemas"), "config_state must include block_schemas");
        assertNotNull(MiniJson.getObject(cfgState, "bounds"), "config_state must include bounds");
    }

    private static void testMicSessionAndAudioChunkPresence() throws Exception {
        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-86-bedroom",
                "86-Wall-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());

        // Gated on micCapturing: should NOT emit audio_chunk before mic_session start
        byte[] pcm = makeSinePcm(1280, 1000.0, 8000);
        session.sendAudioChunkAndPresence(pcm, -58.0);
        assertTrue(transport.findLastByType("audio_chunk") == null, "audio_chunk must be gated on micCapturing");

        session.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-86-bedroom\",\"action\":\"start\",\"mode\":\"continuous\"}");
        assertTrue(session.isMicCapturing(), "micCapturing should be true after mic_session start");
        Map<String, Object> micReport = transport.findLastByType("mic_report");
        assertNotNull(micReport, "mic_report must be emitted");
        assertTrue(MiniJson.getBoolean(micReport, "active", false), "mic_report.active must be true");

        session.sendAudioChunkAndPresence(pcm, -58.0);

        Map<String, Object> chunk = transport.findLastByType("audio_chunk");
        assertNotNull(chunk, "audio_chunk must be emitted");
        assertEquals(16000L, MiniJson.getLong(chunk, "sample_rate", 0));
        assertTrue(!chunk.containsKey("origin"), "audio_chunk must omit unleased origin");

        Map<String, Object> presence = transport.findLastByType("near_end_presence");
        assertNotNull(presence, "near_end_presence must be emitted");
        double snr = MiniJson.getDouble(presence, "snr_db", 0.0);
        assertTrue(snr > 20.0, "SNR dB should be > 20dB");
    }

    private static void testKwsDeployAndConfigV2Pipeline() throws Exception {
        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-1",
                "Smart-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());

        session.handleIncomingLine("{\"type\":\"kws_deploy\",\"device\":\"android-panel-1\",\"keywords\":\"# comment\\nn i h ao @你好小智\\nx iao ai @小爱同学\",\"retreat_words\":[\"退下\",\"闭嘴\"],\"intents_hash\":\"abc12345\",\"threshold\":0.30,\"score\":2.40}");
        Map<String, Object> deployReceipt = transport.findLastByType("kws_deploy_receipt");
        assertNotNull(deployReceipt, "kws_deploy_receipt must be sent");
        assertTrue(MiniJson.getBoolean(deployReceipt, "accepted", false), "accepted must be true");
        assertEquals(2L, MiniJson.getLong(deployReceipt, "keywords", 0));
        assertEquals(2L, MiniJson.getLong(deployReceipt, "local_exit", 0));
        assertEquals(0.30, session.getKwsThreshold());
        assertEquals(2.40, session.getKwsScore());

        session.handleIncomingLine("{\"type\":\"config_validate\",\"request_id\":\"req-100\",\"device\":\"android-panel-1\",\"block\":\"kws\",\"schema_version\":1,\"block_hash\":\"h1\",\"values\":{\"threshold\":0.28,\"score\":2.35}}");
        Map<String, Object> validated = transport.findLastByType("config_validated");
        assertNotNull(validated, "config_validated must be sent");
        assertTrue(MiniJson.getBoolean(validated, "ok", false), "config_validated.ok must be true");
        assertEquals("validated", MiniJson.getString(validated, "state", ""));

        session.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-panel-1\",\"core_node_id\":\"core-1\",\"data_epoch\":1,\"application_id\":\"app-1\",\"operation_id\":\"op-100\",\"desired_revision\":2,\"block_generation\":2,\"block\":\"kws\",\"schema_version\":1,\"block_hash\":\"h1\",\"values\":{\"threshold\":0.28,\"score\":2.35}}");
        Map<String, Object> receipt = transport.findLastByType("config_receipt");
        assertNotNull(receipt, "config_receipt must be sent");
        assertEquals("active", MiniJson.getString(receipt, "status", ""));
        assertTrue(!receipt.containsKey("state"), "config_receipt must not emit duplicate state+status keys");
        assertEquals(0.28, session.getKwsThreshold());
    }

    private static void testPlayStreamAndPreemptionStop() throws Exception {
        MockTransport transport = new MockTransport();
        final boolean[] stopped = new boolean[]{false};
        NorthboundSession session = new NorthboundSession(
                "android-panel-1",
                "Smart-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener() {
                    @Override
                    public NorthboundSession.StopPlaybackResult onStopPlayback(String taskId, boolean flush, String reason) {
                        stopped[0] = flush;
                        return new NorthboundSession.StopPlaybackResult(true, 100L, 64L, "hw_delay");
                    }
                });

        session.handleIncomingLine("{\"type\":\"play_begin\",\"device\":\"android-panel-1\",\"task_id\":\"play-42\",\"content_ref\":\"tts:1\",\"audio\":{\"format\":\"s16le\",\"sample_rate\":16000,\"channels\":1},\"channel\":\"voice\"}");
        Map<String, Object> playReceipt = transport.findLastByType("play_receipt");
        assertNotNull(playReceipt, "play_receipt must be sent");
        assertEquals("accepted", MiniJson.getString(playReceipt, "stage", ""));

        session.sendPlayStreaming("play-42");
        assertEquals("streaming", MiniJson.getString(transport.findLastByType("play_receipt"), "stage", ""));

        String b64Pcm = Base64Codec.encodeToString(new byte[3200]); // 100ms @ 16kHz
        session.handleIncomingLine("{\"type\":\"play_chunk\",\"task_id\":\"play-42\",\"seq\":0,\"audio_b64\":\"" + b64Pcm + "\"}");
        session.handleIncomingLine("{\"type\":\"stop\",\"task_id\":\"play-42\",\"flush\":true}");

        assertTrue(stopped[0], "onStopPlayback listener must be called with flush=true");
        Map<String, Object> stopReceipt = transport.findLastByType("stop_receipt");
        assertNotNull(stopReceipt, "stop_receipt must be sent");
        assertEquals("device_confirmed", MiniJson.getString(stopReceipt, "stage", ""));
        assertEquals(100L, MiniJson.getLong(stopReceipt, "played_ms", 0));
        assertEquals(64L, MiniJson.getLong(stopReceipt, "consumed_ms", 0));

        byte[] wav = new byte[44 + 640];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        ByteBuffer.wrap(wav, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16);
        ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(24000);
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(640);
        assertEquals(24000L, NorthboundSession.parseWavSampleRate(wav, 16000));
        assertEquals(640L, NorthboundSession.stripWavHeaderToPcm16le(wav).length);
    }

    private static void testWakeAndLocalExitRetreat() throws Exception {
        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-1",
                "Smart-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());

        session.sendWake("小爱同学", 3.10, "touch");
        assertEquals(1L, transport.sentFrames.size());
        assertEquals("wake", MiniJson.getString(transport.sentFrames.get(0), "type", ""));

        int beforeRetreat = transport.sentFrames.size();
        session.sendRetreat("退下");
        int exitIdx = transport.indexOfFirstType("local_exit_flushed", beforeRetreat);
        int kwIdx = transport.indexOfFirstType("keyword", beforeRetreat);
        assertTrue(exitIdx >= beforeRetreat && kwIdx > exitIdx,
                "local_exit_flushed MUST precede keyword frame (exitIdx=" + exitIdx + ", kwIdx=" + kwIdx + ")");
        assertEquals("退下", MiniJson.getString(transport.sentFrames.get(exitIdx), "keyword", ""));
        assertTrue(transport.sentFrames.get(kwIdx).containsKey("seq"), "retreat keyword frame must include required seq");
    }

    private static void testLocalKwsEngineParserAndTokenValidation() throws Exception {
        String driverHitJson = "{\"event\":\"keyword\",\"keyword\":\"你好小智\",\"fed_samples\":48000,\"fed_ms\":3000,\"last_token_s\":0.720,\"lag_ms\":12,\"wake_dur_ms\":680,\"max_gap_ms\":160,\"tier\":\"real\",\"l2_passed\":true,\"l2_ms\":18,\"wall_ms\":1024,\"n\":1}";
        LocalKwsEngine.KwsEvent ev = LocalKwsEngine.parseDriverStdoutLine(driverHitJson);
        assertNotNull(ev, "KwsEvent must be parsed");
        assertEquals("keyword", ev.eventType);
        assertEquals("你好小智", ev.keyword);
        assertEquals(3000L, ev.fedMs);
        assertEquals(12L, ev.lagMs);
        assertEquals(680L, ev.wakeDurMs);
        assertEquals(160L, ev.maxGapMs);
        assertEquals("real", ev.tier);
        assertTrue(Boolean.TRUE.equals(ev.l2Passed), "l2Passed must be true");

        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-1",
                "Smart-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());
        session.sendKwsKeywordFact(ev, -21.5, false);
        Map<String, Object> kwFrame = transport.findLastByType("keyword");
        assertNotNull(kwFrame, "keyword frame must be sent");
        assertTrue(kwFrame.containsKey("seq"), "keyword frame must include required seq");
        assertEquals("你好小智", MiniJson.getString(kwFrame, "keyword", ""));
        assertEquals("real", MiniJson.getString(kwFrame, "kws_tier", ""));
        assertEquals(680L, MiniJson.getLong(kwFrame, "wake_dur_ms", 0));
        assertEquals(160L, MiniJson.getLong(kwFrame, "max_gap_ms", 0));
        assertTrue(MiniJson.getBoolean(kwFrame, "l2_passed", false), "l2_passed must be true");

        Set<String> tokens = new HashSet<String>(Arrays.asList("n", "ǐ", "h", "ǎo", "x", "iǎo", "zh", "ì", "t", "uì", "ià"));
        LocalKwsEngine.ValidationResult okVr = LocalKwsEngine.validateKeywordsTable(
                "n ǐ h ǎo x iǎo zh ì @你好小智\nt uì x ià @退下\n",
                tokens);
        assertTrue(okVr.valid, "valid keyword table must pass validation");
        assertEquals(2L, okVr.keywordCount);

        LocalKwsEngine.ValidationResult badVr = LocalKwsEngine.validateKeywordsTable(
                "n ǐ INVALID_PHONEME @坏词\n",
                tokens);
        assertTrue(!badVr.valid, "invalid phoneme token must be rejected");
    }

    private static void testAcousticGuardPrerollAndBargeIn() throws Exception {
        AcousticGuard guard = new AcousticGuard();

        byte[] quietBlock = makeSinePcm(1280, 440.0, 200);
        for (int i = 0; i < 6; i++) {
            guard.pushCaptureBlockAndCheckBargeIn(quietBlock, -50.0, -58.0, true, 1000L + i * 80L);
        }
        byte[] preroll = guard.snapshotPreroll();
        assertEquals(AcousticGuard.PREROLL_BYTES, preroll.length);

        byte[] refBlock = makeSinePcm(1280, 440.0, 2500);
        guard.onPlaybackReferenceChunk(refBlock, 1.0, 2000L);
        assertTrue(guard.isPlaybackActive(2050L), "playback should be active");
        assertTrue(guard.isPlaybackActive(12000L), "playback should stay active until explicitly stopped");

        assertTrue(guard.isSelfHeardEcho(-45.0, 2050L), "weak mic signal during loud playback must be flagged as self-heard echo");

        byte[] loudVoice = makeSinePcm(1280, 800.0, 6500);
        assertTrue(guard.pushCaptureBlockAndCheckBargeIn(loudVoice, -18.0, -55.0, true, 2100L) == null, "frame 1 should not trigger yet");
        assertTrue(guard.pushCaptureBlockAndCheckBargeIn(loudVoice, -18.0, -55.0, true, 2180L) == null, "frame 2 should not trigger yet");
        AcousticGuard.BargeInFact fact = guard.pushCaptureBlockAndCheckBargeIn(loudVoice, -18.0, -55.0, true, 2260L);
        assertNotNull(fact, "3rd consecutive near-end speech frame must trigger BargeInFact");
        assertEquals(AcousticGuard.PREROLL_BYTES, fact.prerollPcm16le.length);
        assertTrue(fact.residualDb > -12.0, "residualDb must exceed -12 dB");
        assertTrue(!fact.refLocked, "honest refLocked must be false without cross-correlation lock");

        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-1",
                "Smart-Panel",
                "2.0.0",
                transport,
                new NorthboundSession.BaseListener());
        session.handleIncomingLine("{\"type\":\"play_begin\",\"device\":\"android-panel-1\",\"task_id\":\"tts-99\",\"content_ref\":\"tts:99\",\"audio\":{\"format\":\"s16le\",\"sample_rate\":16000,\"channels\":1}}");
        session.sendBargeInAndStoppedLocally(fact, true, 7L);

        Map<String, Object> stoppedLocally = transport.findLastByType("stopped_locally");
        assertNotNull(stoppedLocally, "stopped_locally must be emitted when cutLocally=true");
        assertEquals("tts-99", MiniJson.getString(stoppedLocally, "task_id", ""));
        Map<String, Object> bargeIn = transport.findLastByType("barge_in");
        assertNotNull(bargeIn, "barge_in frame must be emitted");
        assertTrue(bargeIn.containsKey("seq"), "barge_in frame must include required seq");
        assertTrue(!MiniJson.getBoolean(bargeIn, "ref_locked", true), "barge_in.ref_locked must be false");
    }

    private static void testHelloAuthTokenAndIdleMicUplinkGate() throws Exception {
        MockTransport transport = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-panel-auth",
                "Smart-Panel",
                "2.3.0-android",
                transport,
                new NorthboundSession.BaseListener());
        session.setAuthToken("hm1_test_token_12345");
        session.sendHello();
        Map<String, Object> hello = transport.findLastByType("hello");
        assertNotNull(hello, "hello must be sent");
        assertEquals("hm1_test_token_12345", MiniJson.getString(hello, "auth_token", ""));

        session.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-panel-auth\",\"action\":\"start\",\"mode\":\"continuous\"}");
        transport.sentFrames.clear();

        // Enable local offline KWS -> while idle, sendAudioChunkWithGate MUST NOT send audio_chunk over TCP
        session.setLocalOfflineKwsReady(true);
        byte[] quietPcm = makeSinePcm(1280, 200.0, 120);
        for (int i = 0; i < 6; i++) {
            boolean sent = session.sendAudioChunkWithGate(quietPcm, -55.0, -58.0, false, 10000L + i * 80L);
            assertTrue(!sent, "idle mic with offline KWS must stay locally gated");
        }
        assertTrue(transport.findLastByType("audio_chunk") == null,
                "zero audio_chunk frames may be transmitted while idle with offline KWS ready");

        // Local KWS wake flushes recent preroll blocks and opens uplink gate
        int flushed = session.notifyWakeForUplinkAndFlushPreroll(11000L);
        assertEquals(6L, flushed);
        int chunksAfterWake = 0;
        for (Map<String, Object> f : transport.sentFrames) {
            if ("audio_chunk".equals(f.get("type"))) chunksAfterWake++;
        }
        assertEquals(6L, chunksAfterWake);

        // Live speech while awake streams immediately
        boolean liveSent = session.sendAudioChunkWithGate(quietPcm, -25.0, -58.0, false, 11080L);
        assertTrue(liveSent, "live PCM must stream after wake opens gate");

        // Retreat closes gate immediately back to idle
        session.sendRetreat("退下吧");
        boolean postRetreat = session.sendAudioChunkWithGate(quietPcm, -55.0, -58.0, false, 12000L);
        assertTrue(!postRetreat, "uplink gate must close immediately after retreat");

        // Buffering 24 idle blocks (1.92s) must cap default wake preroll flush to 15 blocks (1.20s)
        transport.sentFrames.clear();
        for (int i = 0; i < 24; i++) {
            session.sendAudioChunkWithGate(quietPcm, -55.0, -58.0, false, 13000L + i * 80L);
        }
        int defaultFlush = session.notifyWakeForUplinkAndFlushPreroll(15500L);
        assertEquals(15L, defaultFlush);
    }

    private static void testConfigApplyV2KwsDeployAndKwsAgcNormalization() throws Exception {
        // 1. Real temp workDir + tokens.txt to prove config_apply(block="kws") deploys keywords & retreat_words
        java.io.File tmpDir = java.nio.file.Files.createTempDirectory("hm-kws-v232-test").toFile();
        java.io.File modelDir = new java.io.File(tmpDir, "model");
        modelDir.mkdirs();
        java.io.File tokensFile = new java.io.File(modelDir, "tokens.txt");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tokensFile)) {
            fos.write("n 1\nǐ 2\nh 3\nǎo 4\nx 5\niǎo 6\nzh 7\nì 8\nt 9\nuì 10\nià 11\nb 12\na 13\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        try {
            LocalKwsEngine engine = new LocalKwsEngine(tmpDir, null, "android-test-kws", null);
            MockTransport transport = new MockTransport();
            NorthboundSession session = new NorthboundSession(
                    "android-test-kws", "Phone", "2.3.2-android", transport, new NorthboundSession.BaseListener());
            session.attachLocalKwsEngine(engine);

            String compiledKw = "n ǐ h ǎo x iǎo zh ì @你好小智\nt uì x ià b a @退下吧\n";
            String applyOk = "{\"type\":\"config_apply\",\"device\":\"android-test-kws\",\"block\":\"kws\","
                    + "\"operation_id\":\"op-kws-1\",\"desired_revision\":2,\"block_generation\":2,"
                    + "\"values\":{\"keywords\":\"n ǐ h ǎo x iǎo zh ì @你好小智\\nt uì x ià b a @退下吧\\n\","
                    + "\"retreat_words\":[\"退下吧\"],\"intents_hash\":\"h-intent-1\",\"threshold\":0.24,\"score\":2.90}}";
            session.handleIncomingLine(applyOk);

            Map<String, Object> okReceipt = transport.findLastByType("config_receipt");
            assertNotNull(okReceipt, "config_receipt must be emitted for valid kws config_apply");
            assertEquals("active", MiniJson.getString(okReceipt, "status", ""));
            assertEquals(NorthboundSession.fnv1a64Hex(compiledKw), engine.getCurrentKeywordsHash());
            assertTrue(engine.isRetreatWord("退下吧"), "retreat word 退下吧 must be armed");
            assertTrue(!engine.isRetreatWord("退下"), "unconfigured 2-char 退下 must NOT be armed");
            assertEquals(0.24, session.getKwsThreshold());
            assertEquals(2.90, session.getKwsScore());

            // Verify invalid phoneme token in config_apply(block="kws") fails truthfully and preserves prior kw.txt/stateRevision/sensitivity
            long revBeforeBad = session.getStateRevision();
            String applyBad = "{\"type\":\"config_apply\",\"device\":\"android-test-kws\",\"block\":\"kws\","
                    + "\"operation_id\":\"op-kws-2\",\"desired_revision\":3,\"block_generation\":3,"
                    + "\"values\":{\"keywords\":\"n ǐ BOGUS_TOK @坏词\\n\",\"retreat_words\":[\"退下吧\"],\"threshold\":0.20,\"score\":3.10}}";
            session.handleIncomingLine(applyBad);
            Map<String, Object> badReceipt = transport.findLastByType("config_receipt");
            assertEquals("failed", MiniJson.getString(badReceipt, "status", ""));
            assertTrue(MiniJson.getString(badReceipt, "error_code", "").contains("unknown_tokens"),
                    "failed config_receipt must explain unknown_tokens");
            assertEquals(NorthboundSession.fnv1a64Hex(compiledKw), engine.getCurrentKeywordsHash());
            assertEquals(revBeforeBad, session.getStateRevision());
            assertEquals(0.24, session.getKwsThreshold());
            assertEquals(2.90, session.getKwsScore());
        } finally {
            for (java.io.File f : new java.io.File[]{
                    new java.io.File(modelDir, "tokens.txt"),
                    modelDir,
                    new java.io.File(tmpDir, "kw.txt"),
                    new java.io.File(tmpDir, "kw.txt.bak"),
                    new java.io.File(tmpDir, "kw.txt.tmp"),
                    tmpDir}) {
                f.delete();
            }
        }

        // 2. KWS-only AGC normalization: boosts quiet phone speech (-38 dBFS), leaves loud speech unclipped,
        //    never mutates raw uplink PCM bytes, and stays bounded on digital silence.
        byte[] quietSpeech = makeSinePcm(1280, 600.0, 580); // ~ -38 dBFS
        byte[] quietSpeechSnapshot = Arrays.copyOf(quietSpeech, quietSpeech.length);
        float[] agcState = new float[]{1.5f};
        float[] out1 = LocalKwsEngine.convertAndNormalizePcmForKws(quietSpeech, agcState);
        float[] out2 = LocalKwsEngine.convertAndNormalizePcmForKws(quietSpeech, agcState);
        assertTrue(Arrays.equals(quietSpeech, quietSpeechSnapshot),
                "KWS AGC normalization must NEVER mutate raw uplink PCM bytes");
        assertTrue(agcState[0] > 2.2f && agcState[0] <= 3.51f,
                "quiet -38 dBFS speech must boost KWS gain into (2.2x, 3.5x], got " + agcState[0]);
        float peakQuiet = 0f;
        for (float s : out2) {
            peakQuiet = Math.max(peakQuiet, Math.abs(s));
        }
        assertTrue(peakQuiet > 0.04f && peakQuiet <= 0.98f, "boosted quiet speech peak out of range: " + peakQuiet);

        byte[] digitalSilence = new byte[1280 * 2];
        for (int i = 0; i < 15; i++) {
            float[] silOut = LocalKwsEngine.convertAndNormalizePcmForKws(digitalSilence, agcState);
            for (float s : silOut) {
                assertTrue(s == 0f, "digital silence must remain exact zero");
            }
        }
        assertTrue(agcState[0] >= 1.45f && agcState[0] <= 2.2f,
                "digital silence must relax toward warm idle floor without pumping gain, got " + agcState[0]);

        byte[] loudSpeech = makeSinePcm(1280, 600.0, 18000); // ~ -8 dBFS
        for (int i = 0; i < 6; i++) {
            float[] loudOut = LocalKwsEngine.convertAndNormalizePcmForKws(loudSpeech, agcState);
            for (float s : loudOut) {
                assertTrue(Math.abs(s) <= 0.9801f, "KWS AGC must soft-clamp samples <= 0.98");
            }
        }
        assertTrue(agcState[0] < 1.15f, "loud speech must relax KWS gain near 1.0x, got " + agcState[0]);
    }

    private static void testWave4PrerollOrderDynamicSizingAndWireContracts() throws Exception {
        // 1. Field Bug 1: Dynamic preroll flush sizing clamp((wakeDurMs + 400) / 80, 12, 20) & audio_chunk BEFORE keyword
        assertEquals(15L, NorthboundSession.computeWakePrerollFlushBlocks(0L));
        assertEquals(12L, NorthboundSession.computeWakePrerollFlushBlocks(400L));
        assertEquals(12L, NorthboundSession.computeWakePrerollFlushBlocks(560L));
        assertEquals(13L, NorthboundSession.computeWakePrerollFlushBlocks(680L));
        assertEquals(16L, NorthboundSession.computeWakePrerollFlushBlocks(880L));
        assertEquals(16L, NorthboundSession.computeWakePrerollFlushBlocks(920L));
        assertEquals(20L, NorthboundSession.computeWakePrerollFlushBlocks(1500L));

        MockTransport tx = new MockTransport();
        NorthboundSession session = new NorthboundSession(
                "android-w4", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w4-stable",
                tx, new NorthboundSession.BaseListener());
        session.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w4\",\"action\":\"start\",\"mode\":\"continuous\"}");
        session.setLocalOfflineKwsReady(true);
        tx.sentFrames.clear();

        byte[] speechPcm = makeSinePcm(1280, 440.0, 4000);
        for (int i = 0; i < 24; i++) {
            boolean sent = session.sendAudioChunkWithGate(speechPcm, -26.0, -58.0, false, 20000L + i * 80L);
            assertTrue(!sent, "idle blocks must stay buffered until wake");
        }
        assertEquals(0L, tx.sentFrames.size());

        LocalKwsEngine.KwsEvent realHit = LocalKwsEngine.parseDriverStdoutLine(
                "{\"event\":\"keyword\",\"keyword\":\"你好小智\",\"fed_ms\":3200,\"lag_ms\":10,\"wake_dur_ms\":880,\"max_gap_ms\":140,\"tier\":\"real\",\"l2_passed\":true,\"wall_ms\":22000}");
        int flushed = session.notifyWakeForUplinkAndFlushPreroll(22000L, realHit.wakeDurMs);
        session.sendKwsKeywordFact(realHit, -21.0, false);

        assertEquals(16L, flushed);
        int kwIdx = tx.indexOfFirstType("keyword", 0);
        assertTrue(kwIdx > 0, "keyword must appear AFTER preroll audio_chunk frames");
        int audioBeforeKw = 0;
        long prevSeq = -1L;
        for (int i = 0; i < kwIdx; i++) {
            Map<String, Object> f = tx.sentFrames.get(i);
            if ("audio_chunk".equals(f.get("type"))) {
                long seq = MiniJson.getLong(f, "seq", -1L);
                assertTrue(seq > prevSeq, "preroll audio_chunk seq must be strictly increasing");
                prevSeq = seq;
                audioBeforeKw++;
            }
        }
        assertEquals(16L, audioBeforeKw);
        long kwSeq = MiniJson.getLong(tx.sentFrames.get(kwIdx), "seq", -1L);
        assertTrue(kwSeq > prevSeq, "keyword seq must follow all preroll audio_chunk seqs");

        // Suspect hit without flush keeps idle uplink gate closed (zero PCM spam)
        session.closeUplinkGateImmediately();
        tx.sentFrames.clear();
        LocalKwsEngine.KwsEvent suspectHit = LocalKwsEngine.parseDriverStdoutLine(
                "{\"event\":\"keyword\",\"keyword\":\"你好小智\",\"fed_ms\":4000,\"lag_ms\":10,\"wake_dur_ms\":200,\"tier\":\"suspect\",\"l2_passed\":false,\"wall_ms\":25000}");
        session.sendKwsKeywordFact(suspectHit, -35.0, false);
        assertTrue(!session.isUplinkAudioOpen(false, 25000L), "suspect hit must keep idle uplink gate closed");
        assertTrue(tx.findLastByType("audio_chunk") == null, "suspect hit must emit zero audio_chunk frames");

        // 1b. Blocker #5: Real BoundedTwoTierQueue + orderedControlBurst + deterministic mid-flush concurrent capture
        //     proving (a) preroll audio_chunk drains BEFORE keyword under 2-tier priority, and
        //     (b) strictly monotonic seq across preroll -> keyword -> mid-flush capture -> live capture (zero audio_chunk_stale).
        final BoundedTwoTierQueue twoTier = new BoundedTwoTierQueue(256, 32);
        final ThreadLocal<Boolean> orderedBurst = new ThreadLocal<Boolean>();
        final NorthboundSession[] burstSessionRef = new NorthboundSession[1];
        final byte[] liveBlockA = makeSinePcm(1280, 660.0, 5000);
        final byte[] liveBlockB = makeSinePcm(1280, 880.0, 5000);
        final int[] injectedMidFlush = new int[]{0};
        final boolean[] midFlushReturnedTrue = new boolean[]{false};
        NorthboundSession burstSession = new NorthboundSession(
                "android-w4-q", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w4-q",
                new NorthboundSession.FrameTransport() {
                    private int audioCount = 0;
                    @Override
                    public void sendJsonLine(String jsonLine) throws java.io.IOException {
                        boolean inBurst = Boolean.TRUE.equals(orderedBurst.get());
                        boolean isAudioBulk = !inBurst
                                && (jsonLine.contains("\"type\":\"audio_chunk\"")
                                    || jsonLine.contains("\"type\":\"near_end_presence\""));
                        twoTier.enqueue(jsonLine, isAudioBulk);
                        // Deterministically simulate concurrent capture thread arriving mid-preroll-flush
                        // AND right when keyword is emitted — must buffer into idlePrerollRing (return false),
                        // NEVER racing into audioQueue with an out-of-order seq!
                        if (jsonLine.contains("\"type\":\"audio_chunk\"")) {
                            audioCount++;
                            if (audioCount == 4 && injectedMidFlush[0] == 0) {
                                injectedMidFlush[0]++;
                                if (burstSessionRef[0].sendAudioChunkWithGate(liveBlockA, -22.0, -58.0, false, 32080L)) {
                                    midFlushReturnedTrue[0] = true;
                                }
                            }
                        } else if (jsonLine.contains("\"type\":\"keyword\"") && injectedMidFlush[0] == 1) {
                            injectedMidFlush[0]++;
                            if (burstSessionRef[0].sendAudioChunkWithGate(liveBlockB, -22.0, -58.0, false, 32160L)) {
                                midFlushReturnedTrue[0] = true;
                            }
                        }
                    }
                },
                new NorthboundSession.BaseListener());
        burstSessionRef[0] = burstSession;
        burstSession.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w4-q\",\"action\":\"start\",\"mode\":\"continuous\"}");
        while (twoTier.takeNext(0L) != null) { /* drain mic_report */ }
        burstSession.setLocalOfflineKwsReady(true);

        for (int i = 0; i < 20; i++) {
            assertTrue(!burstSession.sendAudioChunkWithGate(speechPcm, -26.0, -58.0, false, 30000L + i * 80L),
                    "idle blocks must buffer locally");
        }
        orderedBurst.set(Boolean.TRUE);
        try {
            int burstFlushed = burstSession.flushPrerollAndSendKwsKeywordFact(32000L, realHit, -20.0, false);
            // 16 preroll blocks + 2 mid-flush capture blocks (liveBlockA before keyword, liveBlockB after keyword)
            assertEquals(18L, burstFlushed);
        } finally {
            orderedBurst.remove();
        }
        assertTrue(!midFlushReturnedTrue[0], "mid-flush capture must buffer into ring instead of bypassing into audioQueue");
        assertEquals(2L, injectedMidFlush[0]);
        // Subsequent live capture block goes to audioQueue AFTER burst completes
        assertTrue(burstSession.sendAudioChunkWithGate(liveBlockA, -20.0, -58.0, false, 32240L),
                "post-flush live capture must stream immediately");

        List<Map<String, Object>> drained = new ArrayList<Map<String, Object>>();
        String qLine;
        while ((qLine = twoTier.takeNext(0L)) != null) {
            drained.add(MiniJson.parseObject(qLine));
        }
        assertEquals(0L, twoTier.getDroppedAudio());
        int drainedKwIdx = -1;
        int drainedAudioBeforeKw = 0;
        int drainedAudioAfterKw = 0;
        long lastDrainedSeq = -1L;
        for (int i = 0; i < drained.size(); i++) {
            Map<String, Object> f = drained.get(i);
            String t = MiniJson.getString(f, "type", "");
            if ("audio_chunk".equals(t) || "keyword".equals(t)) {
                long seq = MiniJson.getLong(f, "seq", -1L);
                assertTrue(seq > lastDrainedSeq,
                        "Drained 2-tier wire seq MUST be strictly monotonic (got " + seq + " after " + lastDrainedSeq + " for " + t + ")");
                lastDrainedSeq = seq;
            }
            if ("keyword".equals(t)) {
                drainedKwIdx = i;
            } else if ("audio_chunk".equals(t)) {
                if (drainedKwIdx < 0) {
                    drainedAudioBeforeKw++;
                } else {
                    drainedAudioAfterKw++;
                }
            }
        }
        assertTrue(drainedKwIdx > 0, "keyword must drain strictly after preroll audio_chunk frames");
        assertEquals(17L, drainedAudioBeforeKw); // 16 preroll + 1 mid-preroll block drained before keyword
        assertEquals(2L, drainedAudioAfterKw);   // 1 mid-keyword tail block + 1 post-flush live block

        // 2. MG-P2-03 #1: config_apply_v2 persistence across reconnect + strict `<` stale generation rejection
        tx.sentFrames.clear();
        session.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-w4\",\"core_node_id\":\"core-1\","
                + "\"data_epoch\":3,\"application_id\":\"app-1\",\"operation_id\":\"op-dup-5\","
                + "\"desired_revision\":5,\"block_generation\":5,\"block\":\"duplex\",\"schema_version\":1,"
                + "\"block_hash\":\"h-dup-5\",\"values\":{\"duplex_requested\":\"full\",\"low_latency\":true}}");
        Map<String, Object> dupOk = tx.findLastByType("config_receipt");
        assertEquals("active", MiniJson.getString(dupOk, "status", ""));
        assertEquals("full", session.getDuplexMode());
        assertEquals(5L, session.getBlockGeneration("duplex"));

        String persistedJson = session.exportPersistedConfigStateJson();
        final int[] appliedCount = new int[]{0};
        MockTransport tx2 = new MockTransport();
        NorthboundSession reconnected = new NorthboundSession(
                "android-w4", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w4-stable",
                tx2, new NorthboundSession.BaseListener() {
                    @Override
                    public void onConfigBlockApplied(String block, Map<String, Object> values) {
                        appliedCount[0]++;
                    }
                });
        assertTrue(reconnected.restorePersistedConfigStateJson(persistedJson), "restorePersistedConfigStateJson must succeed");
        assertEquals(session.getStateRevision(), reconnected.getStateRevision());
        assertEquals("full", reconnected.getDuplexMode());
        assertEquals(5L, reconnected.getBlockGeneration("duplex"));

        // Strictly lower generation (4 < 5) MUST return status=superseded, error_code=stale_generation with zero mutation
        long revBeforeStale = reconnected.getStateRevision();
        reconnected.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-w4\",\"core_node_id\":\"core-1\","
                + "\"data_epoch\":3,\"application_id\":\"app-2\",\"operation_id\":\"op-dup-stale-lt\","
                + "\"desired_revision\":4,\"block_generation\":4,\"block\":\"duplex\",\"schema_version\":1,"
                + "\"block_hash\":\"h-dup-old\",\"values\":{\"duplex_requested\":\"half\"}}");
        Map<String, Object> staleLt = tx2.findLastByType("config_receipt");
        assertEquals("superseded", MiniJson.getString(staleLt, "status", ""));
        assertEquals("stale_generation", MiniJson.getString(staleLt, "error_code", ""));
        assertTrue(!MiniJson.getBoolean(staleLt, "retryable", true), "superseded receipt must set retryable=false");
        assertTrue(!staleLt.containsKey("state") && !staleLt.containsKey("reason"),
                "config_receipt must use status/error_code without duplicate state/reason aliases");
        assertNotNull(MiniJson.getObject(staleLt, "evidence"), "superseded receipt must include evidence.reason");
        assertEquals("full", reconnected.getDuplexMode());
        assertEquals(revBeforeStale, reconnected.getStateRevision());
        assertEquals(0L, appliedCount[0]);

        // Strictly lower data_epoch (2 < 3) even with higher block_generation MUST return status=superseded
        reconnected.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-w4\",\"core_node_id\":\"core-1\","
                + "\"data_epoch\":2,\"application_id\":\"app-2b\",\"operation_id\":\"op-dup-stale-epoch\","
                + "\"desired_revision\":99,\"block_generation\":99,\"block\":\"duplex\",\"schema_version\":1,"
                + "\"block_hash\":\"h-dup-old-epoch\",\"values\":{\"duplex_requested\":\"half\"}}");
        Map<String, Object> staleEpoch = tx2.findLastByType("config_receipt");
        assertEquals("superseded", MiniJson.getString(staleEpoch, "status", ""));
        assertEquals("stale_generation", MiniJson.getString(staleEpoch, "error_code", ""));
        assertEquals("full", reconnected.getDuplexMode());
        assertEquals(revBeforeStale, reconnected.getStateRevision());
        assertEquals(0L, appliedCount[0]);

        // Equal clamped generation (5 == 5, matching hm-core dispatch_block_v2_clamped on reconnect / repeated reflex deploy)
        // MUST be accepted and applied cleanly!
        reconnected.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-w4\",\"core_node_id\":\"core-1\","
                + "\"data_epoch\":3,\"application_id\":\"app-3\",\"operation_id\":\"op-dup-clamped-eq\","
                + "\"desired_revision\":5,\"block_generation\":5,\"block\":\"duplex\",\"schema_version\":1,"
                + "\"block_hash\":\"h-dup-5-reapply\",\"values\":{\"duplex_requested\":\"full\",\"low_latency\":false}}");
        Map<String, Object> clampedEq = tx2.findLastByType("config_receipt");
        assertEquals("active", MiniJson.getString(clampedEq, "status", ""));
        assertEquals("full", reconnected.getDuplexMode());
        assertEquals(5L, reconnected.getBlockGeneration("duplex"));
        assertEquals(revBeforeStale + 1L, reconnected.getStateRevision());
        assertEquals(1L, appliedCount[0]);

        // Newer generation (6 > 5) succeeds cleanly
        reconnected.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-w4\",\"core_node_id\":\"core-1\","
                + "\"data_epoch\":3,\"application_id\":\"app-4\",\"operation_id\":\"op-dup-6\","
                + "\"desired_revision\":6,\"block_generation\":6,\"block\":\"duplex\",\"schema_version\":1,"
                + "\"block_hash\":\"h-dup-6\",\"values\":{\"duplex_requested\":\"half\"}}");
        Map<String, Object> nextOk = tx2.findLastByType("config_receipt");
        assertEquals("active", MiniJson.getString(nextOk, "status", ""));
        assertEquals("half", reconnected.getDuplexMode());
        assertEquals(6L, reconnected.getBlockGeneration("duplex"));
        assertEquals(2L, appliedCount[0]);

        // 3. MG-P2-03 #2: Complete config_state payload with capabilities, block_schemas, bounds, request_id
        reconnected.handleIncomingLine("{\"type\":\"config_state_get\",\"request_id\":\"req-w4-state\",\"device\":\"android-w4\"}");
        Map<String, Object> cfgState = tx2.findLastByType("config_state");
        assertEquals("req-w4-state", MiniJson.getString(cfgState, "request_id", ""));
        assertEquals("boot-w4-stable", MiniJson.getString(cfgState, "gateway_boot_id", ""));
        List<Object> stateCaps = MiniJson.getArray(cfgState, "capabilities");
        assertTrue(stateCaps != null && stateCaps.contains("config_apply_v2")
                && stateCaps.contains("config_block_kws") && stateCaps.contains("config_block_duplex"),
                "config_state.capabilities must match hello capabilities");
        assertTrue(!MiniJson.getString(cfgState, "capability_fingerprint", "").isEmpty(),
                "config_state must include capability_fingerprint");
        Map<String, Object> schemas = MiniJson.getObject(cfgState, "block_schemas");
        assertNotNull(schemas, "config_state must include block_schemas");
        assertEquals(1L, MiniJson.getLong(schemas, "kws", 0L));
        assertEquals(1L, MiniJson.getLong(schemas, "duplex", 0L));
        Map<String, Object> bounds = MiniJson.getObject(cfgState, "bounds");
        assertNotNull(bounds, "config_state must include bounds");
        Map<String, Object> kwsBounds = MiniJson.getObject(bounds, "kws");
        assertNotNull(kwsBounds, "bounds.kws required");
        assertEquals(2L, MiniJson.getArray(kwsBounds, "threshold").size());
        assertEquals(2L, MiniJson.getArray(kwsBounds, "score").size());
        Map<String, Object> dupBounds = MiniJson.getObject(bounds, "duplex");
        assertTrue(dupBounds != null && MiniJson.getArray(dupBounds, "modes").contains("full"),
                "bounds.duplex.modes must include half/full");

        // 4. MG-P2-03 #3: audio_chunk.origin schema matching Core frames.rs + echo_health.reason
        tx2.sentFrames.clear();
        reconnected.sendEchoHealth("degraded", 1250L, "aec_diverged");
        Map<String, Object> ehDegraded = tx2.findLastByType("echo_health");
        assertEquals("degraded", MiniJson.getString(ehDegraded, "health", ""));
        assertEquals(1250L, MiniJson.getLong(ehDegraded, "degraded_ms", 0L));
        assertEquals("aec_diverged", MiniJson.getString(ehDegraded, "reason", ""));

        reconnected.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w4\",\"action\":\"start\",\"mode\":\"continuous\",\"lease\":\"capture-lease-42\"}");
        tx2.sentFrames.clear();
        reconnected.sendAudioChunkAndPresence(speechPcm, -24.0, -58.0);
        reconnected.sendAudioChunkAndPresence(speechPcm, -24.0, -58.0);
        List<Map<String, Object>> leasedChunks = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> f : tx2.sentFrames) {
            if ("audio_chunk".equals(f.get("type"))) leasedChunks.add(f);
        }
        assertEquals(2L, leasedChunks.size());
        Map<String, Object> origin1 = MiniJson.getObject(leasedChunks.get(0), "origin");
        assertNotNull(origin1, "leased mic_session audio_chunk must include origin");
        assertEquals("leased", MiniJson.getString(origin1, "kind", ""));
        assertEquals("capture-lease-42", MiniJson.getString(origin1, "lease", ""));
        Map<String, Object> inner1 = MiniJson.getObject(origin1, "origin");
        assertNotNull(inner1, "InputOrigin::Leased must wrap InputOrigin::Audio");
        assertEquals("audio", MiniJson.getString(inner1, "kind", ""));
        assertTrue(!MiniJson.getString(inner1, "capture_epoch", "").isEmpty(), "capture_epoch required");
        assertTrue(!MiniJson.getString(inner1, "ticket", "").isEmpty(), "ticket required");
        assertTrue(!MiniJson.getString(inner1, "input_id", "").isEmpty(), "input_id required");
        assertEquals(0L, MiniJson.getLong(inner1, "start_sample", -1L));
        assertEquals(1280L, MiniJson.getLong(inner1, "end_sample", -1L));
        Map<String, Object> inner2 = MiniJson.getObject(MiniJson.getObject(leasedChunks.get(1), "origin"), "origin");
        assertEquals(1280L, MiniJson.getLong(inner2, "start_sample", -1L));
        assertEquals(2560L, MiniJson.getLong(inner2, "end_sample", -1L));

        // 5. MG-P2-03 #4: Suppress 2s HTTP /api/v0/mode reflex poll on v2 Cores / after northbound sync
        MockTransport txV2 = new MockTransport();
        NorthboundSession v2Session = new NorthboundSession("android-v2", "Phone", "2.3.7", txV2, new NorthboundSession.BaseListener());
        assertTrue(v2Session.shouldAllowHttpReflexSync(), "before hello_ack HTTP fallback allowed");
        v2Session.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":2,\"capabilities\":[\"config_apply_v2\"],\"generation\":1}");
        assertTrue(v2Session.isCoreSupportsConfigApplyV2(), "v2 hello_ack must mark coreSupportsConfigApplyV2");
        assertTrue(!v2Session.shouldAllowHttpReflexSync(), "v2 Core with config_apply_v2 must suppress 2s HTTP reflex poll");

        MockTransport txV0 = new MockTransport();
        NorthboundSession v0Session = new NorthboundSession("android-v0", "Phone", "2.3.7", txV0, new NorthboundSession.BaseListener());
        v0Session.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":0,\"capabilities\":[],\"generation\":1}");
        assertTrue(!v0Session.isCoreSupportsConfigApplyV2(), "legacy v0 Core must not claim config_apply_v2");
        assertTrue(v0Session.shouldAllowHttpReflexSync(), "legacy v0 Core before kws_deploy must retain HTTP fallback");
        v0Session.handleIncomingLine("{\"type\":\"kws_deploy\",\"device\":\"android-v0\",\"keywords\":\"n i h ao @你好小智\",\"retreat_words\":[\"退下吧\"]}");
        assertTrue(!v0Session.shouldAllowHttpReflexSync(), "after northbound kws_deploy, HTTP reflex poll must be suppressed");
    }

    private static void testWave5UplinkGateSerializationActiveStatesEchoHealthAndFocusLoss() throws Exception {
        // 1. P2-17: Uplink audio stays CLOSED during idle/music/paused/error/half-duplex speaking,
        //    and OPEN during wake/listening/hearing/thinking and full-duplex speaking.
        MockTransport gateTx = new MockTransport();
        NorthboundSession gateSession = new NorthboundSession(
                "android-w5", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w5",
                gateTx, new NorthboundSession.BaseListener());
        gateSession.setLocalOfflineKwsReady(true);
        gateSession.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w5\",\"action\":\"start\",\"mode\":\"continuous\"}");
        long now = System.currentTimeMillis();

        // Active listening/turn states must open uplink
        for (String activeSt : new String[]{"wake", "listening", "hearing", "thinking"}) {
            gateSession.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"" + activeSt + "\"}");
            assertTrue(gateSession.isUplinkAudioOpen(false, now + 100L),
                    "isUplinkAudioOpen must be true in state=" + activeSt);
        }

        // Half-duplex speaking, music, paused, error must all CLOSE uplink immediately (zero PCM spam!)
        for (String quietSt : new String[]{"speaking", "music", "paused", "error"}) {
            gateSession.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"listening\"}");
            assertTrue(gateSession.isUplinkAudioOpen(false, now + 100L), "listening must be open");
            gateSession.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"" + quietSt + "\"}");
            assertTrue(!gateSession.isUplinkAudioOpen(false, now + 100L),
                    "isUplinkAudioOpen MUST be false in half-duplex state=" + quietSt);
            gateTx.sentFrames.clear();
            boolean streamed = gateSession.sendAudioChunkWithGate(
                    makeSinePcm(1280, 440.0, 1000), -30.0, -58.0, false, now + 180L);
            assertTrue(!streamed && gateTx.findLastByType("audio_chunk") == null,
                    "sendAudioChunkWithGate must NOT stream PCM in state=" + quietSt);
        }

        // Full-duplex speaking MUST open uplink, and switching back to half-duplex closes it
        gateSession.handleIncomingLine("{\"type\":\"mode_set\",\"duplex_requested\":\"full\"}");
        gateSession.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"speaking\"}");
        assertTrue(gateSession.isUplinkAudioOpen(false, now + 100L),
                "full-duplex speaking must keep uplink open");
        gateSession.handleIncomingLine("{\"type\":\"mode_set\",\"duplex_requested\":\"half\"}");
        assertTrue(!gateSession.isUplinkAudioOpen(false, now + 100L),
                "switching full -> half during speaking must close uplink immediately");

        // 2. P2-16: Concurrent flushPrerollInternal vs closeUplinkGateImmediately serialization
        //    AND finally reset of prerollFlushInProgress on Error/Throwable.
        final java.util.concurrent.CountDownLatch enteredFlushSend = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowFlushResume = new java.util.concurrent.CountDownLatch(1);
        final NorthboundSession[] raceRef = new NorthboundSession[1];
        NorthboundSession raceSession = new NorthboundSession(
                "android-w5-race", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w5-race",
                new NorthboundSession.FrameTransport() {
                    private int sends = 0;
                    @Override
                    public void sendJsonLine(String jsonLine) {
                        if (jsonLine.contains("\"type\":\"audio_chunk\"")) {
                            sends++;
                            if (sends == 1) {
                                enteredFlushSend.countDown();
                                try {
                                    allowFlushResume.await(2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }
                },
                new NorthboundSession.BaseListener());
        raceRef[0] = raceSession;
        raceSession.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w5-race\",\"action\":\"start\",\"mode\":\"continuous\"}");
        raceSession.setLocalOfflineKwsReady(true);
        byte[] samplePcm = makeSinePcm(1280, 440.0, 2000);
        for (int i = 0; i < 6; i++) {
            raceSession.sendAudioChunkWithGate(samplePcm, -28.0, -58.0, false, 50000L + i * 80L);
        }
        Thread flushThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    raceRef[0].notifyWakeForUplinkAndFlushPreroll(51000L, 640L);
                } catch (Exception ignored) {
                }
            }
        }, "test-w5-flush");
        flushThread.start();
        assertTrue(enteredFlushSend.await(2000L, java.util.concurrent.TimeUnit.MILLISECONDS),
                "flush thread must enter send");
        Thread closeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                raceRef[0].closeUplinkGateImmediately();
            }
        }, "test-w5-close");
        closeThread.start();
        Thread.sleep(30L);
        allowFlushResume.countDown();
        flushThread.join(2000L);
        closeThread.join(2000L);
        assertTrue(!raceSession.isUplinkAudioOpen(false, 51050L),
                "Concurrent closeUplinkGateImmediately must NEVER be overwritten open by in-flight flush");
        assertTrue(!raceSession.isPrerollFlushInProgressForTest(),
                "prerollFlushInProgress must be false after serialized flush+close");

        // Error during transport write MUST still clear prerollFlushInProgress via finally!
        NorthboundSession errSession = new NorthboundSession(
                "android-w5-err", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-w5-err",
                new NorthboundSession.FrameTransport() {
                    @Override
                    public void sendJsonLine(String jsonLine) {
                        if (jsonLine.contains("\"type\":\"audio_chunk\"")) {
                            throw new AssertionError("simulated_fatal_error");
                        }
                    }
                },
                new NorthboundSession.BaseListener());
        errSession.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-w5-err\",\"action\":\"start\",\"mode\":\"continuous\"}");
        errSession.setLocalOfflineKwsReady(true);
        errSession.sendAudioChunkWithGate(samplePcm, -28.0, -58.0, false, 60000L);
        boolean caughtError = false;
        try {
            errSession.notifyWakeForUplinkAndFlushPreroll(60500L, 640L);
        } catch (AssertionError expected) {
            caughtError = "simulated_fatal_error".equals(expected.getMessage());
        }
        assertTrue(caughtError, "Error must propagate out of flushPrerollInternal");
        assertTrue(!errSession.isPrerollFlushInProgressForTest(),
                "prerollFlushInProgress MUST be reset in finally even when Error is thrown");

        // 3. P2-18: Honest echo_health when AEC is disabled vs active
        MockTransport ehOffTx = new MockTransport();
        NorthboundSession ehOff = new NorthboundSession(
                "android-eh-off", "Phone", NorthboundSession.GATEWAY_VERSION, ehOffTx, new NorthboundSession.BaseListener());
        ehOff.setAecActive(false);
        ehOff.setAecTailMs(0);
        ehOff.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":2,\"generation\":1}");
        Map<String, Object> ehOffFrame = ehOffTx.findLastByType("echo_health");
        assertEquals("unknown", MiniJson.getString(ehOffFrame, "health", ""));
        assertEquals("aec_disabled", MiniJson.getString(ehOffFrame, "reason", ""));
        ehOff.handleIncomingLine("{\"type\":\"mic_session\",\"action\":\"start\"}");
        ehOff.sendAudioChunkAndPresence(samplePcm, -30.0, -58.0);
        Map<String, Object> presOff = ehOffTx.findLastByType("near_end_presence");
        assertEquals("unknown", MiniJson.getString(presOff, "echo_health", ""));

        MockTransport ehOnTx = new MockTransport();
        NorthboundSession ehOn = new NorthboundSession(
                "android-eh-on", "Phone", NorthboundSession.GATEWAY_VERSION, ehOnTx, new NorthboundSession.BaseListener());
        ehOn.setAecActive(true);
        ehOn.setAecTailMs(160);
        ehOn.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":2,\"generation\":1}");
        Map<String, Object> ehOnFrame = ehOnTx.findLastByType("echo_health");
        assertEquals("healthy", MiniJson.getString(ehOnFrame, "health", ""));
        assertEquals("ok", MiniJson.getString(ehOnFrame, "reason", ""));
        ehOn.handleIncomingLine("{\"type\":\"mic_session\",\"action\":\"start\"}");
        ehOn.sendAudioChunkAndPresence(samplePcm, -30.0, -58.0);
        Map<String, Object> presOn = ehOnTx.findLastByType("near_end_presence");
        assertEquals("healthy", MiniJson.getString(presOn, "echo_health", ""));

        // 4. P1-13: AUDIOFOCUS_LOSS sends pause utterance + stop_receipt (or play_receipt unknown), NEVER play_finished!
        MockTransport focusTx = new MockTransport();
        NorthboundSession focusSession = new NorthboundSession(
                "android-w5-focus", "Phone", NorthboundSession.GATEWAY_VERSION, focusTx, new NorthboundSession.BaseListener());
        focusSession.handleIncomingLine("{\"type\":\"play_begin\",\"device\":\"android-w5-focus\",\"task_id\":\"media-77\",\"channel\":\"media\",\"audio\":{\"format\":\"s16le\",\"sample_rate\":44100,\"channels\":2}}");
        focusTx.sentFrames.clear();
        focusSession.sendAudioFocusLostMediaPause(
                java.util.Collections.singletonList("media-77"),
                java.util.Collections.singletonList(new NorthboundSession.StopPlaybackResult(true, 12400L, 12100L, "hw_delay")));

        assertTrue(focusTx.findLastByType("play_finished") == null,
                "AUDIOFOCUS_LOSS MUST NOT emit play_finished");
        assertTrue(focusTx.findLastByType("wake") == null,
                "AUDIOFOCUS_LOSS pause utterance MUST NOT emit wake");
        Map<String, Object> utt = focusTx.findLastByType("utterance");
        assertNotNull(utt, "AUDIOFOCUS_LOSS must emit pause utterance");
        assertEquals("android_media_session", MiniJson.getString(utt, "source", ""));
        assertEquals("暂停播放", MiniJson.getString(utt, "text", ""));
        Map<String, Object> stopRec = focusTx.findLastByType("stop_receipt");
        assertNotNull(stopRec, "AUDIOFOCUS_LOSS must emit stop_receipt for stopped media task");
        assertEquals("media-77", MiniJson.getString(stopRec, "task_id", ""));
        assertEquals("device_confirmed", MiniJson.getString(stopRec, "stage", ""));
        assertEquals(12100L, MiniJson.getLong(stopRec, "consumed_ms", 0L));
        assertTrue(!stopRec.containsKey("detail"), "device_confirmed stop_receipt must omit null detail");

        // Fallback when stopResult is absent/not found -> play_receipt(stage="unknown", detail="audio_focus_lost")
        focusTx.sentFrames.clear();
        focusSession.sendAudioFocusLostMediaPause(
                java.util.Collections.singletonList("media-88"),
                java.util.Collections.<NorthboundSession.StopPlaybackResult>emptyList());
        assertTrue(focusTx.findLastByType("play_finished") == null, "fallback focus loss must not emit play_finished");
        Map<String, Object> playRec = focusTx.findLastByType("play_receipt");
        assertNotNull(playRec, "fallback focus loss must emit play_receipt");
        assertEquals("media-88", MiniJson.getString(playRec, "task_id", ""));
        assertEquals("unknown", MiniJson.getString(playRec, "stage", ""));
        assertEquals("audio_focus_lost", MiniJson.getString(playRec, "detail", ""));
    }

    private static void testV239SpeakingToListeningFlushesRecentPrerollMonotonically() throws Exception {
        // 1. Half-duplex speaking -> listening flushes at most 6 recent blocks (480ms) in strict monotonic seq order
        final List<Map<String, Object>> sent = new ArrayList<Map<String, Object>>();
        final NorthboundSession[] sessionRef = new NorthboundSession[1];
        final boolean[] midFlushInjected = new boolean[]{false};
        final boolean[] midFlushReturnedTrue = new boolean[]{false};
        final byte[] midFlushBlock = makeSinePcm(1280, 950.0, 4200);

        NorthboundSession session = new NorthboundSession(
                "android-v239-sp", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-v239-sp",
                new NorthboundSession.FrameTransport() {
                    private int flushedSeen = 0;
                    @Override
                    public void sendJsonLine(String jsonLine) throws java.io.IOException {
                        Map<String, Object> f = MiniJson.parseObject(jsonLine);
                        sent.add(f);
                        // Deterministically simulate concurrent capture arriving mid-flush on speaking->listening
                        if ("audio_chunk".equals(f.get("type"))) {
                            flushedSeen++;
                            if (flushedSeen == 2 && !midFlushInjected[0]) {
                                midFlushInjected[0] = true;
                                if (sessionRef[0].sendAudioChunkWithGate(midFlushBlock, -20.0, -58.0, false, 81_000L)) {
                                    midFlushReturnedTrue[0] = true;
                                }
                            }
                        }
                    }
                },
                new NorthboundSession.BaseListener());
        sessionRef[0] = session;
        session.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-v239-sp\",\"action\":\"start\",\"mode\":\"continuous\"}");
        session.setLocalOfflineKwsReady(true);
        sent.clear();

        // Enter half-duplex speaking (e.g. Core's short "在呢" wake ack)
        session.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"speaking\"}");
        assertTrue(!session.isUplinkAudioOpen(false, 80_000L), "half-duplex speaking must keep uplink closed");

        // User starts speaking during "在呢" -> 10 blocks buffered locally, 0 streamed while speaking
        for (int i = 0; i < 10; i++) {
            byte[] blk = makeSinePcm(1280, 300.0 + i * 30.0, 3500);
            boolean streamed = session.sendAudioChunkWithGate(blk, -24.0, -58.0, false, 80_000L + i * 80L);
            assertTrue(!streamed, "blocks during half-duplex speaking must buffer into idlePrerollRing");
        }
        assertEquals(0L, sent.size());

        // Speaking finishes -> listening: MUST flush 6 recent blocks + 1 mid-flush block atomically in monotonic seq order!
        session.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"listening\"}");
        assertTrue(midFlushInjected[0], "mid-flush capture must have fired during flush");
        assertTrue(!midFlushReturnedTrue[0], "mid-flush capture must buffer into ring instead of racing live uplink");
        assertTrue(!session.isPrerollFlushInProgressForTest(), "prerollFlushInProgress must clear after flush");
        assertTrue(session.isUplinkAudioOpen(false, 81_100L), "listening state must reopen live uplink");

        // Subsequent live capture streams immediately after flushed blocks
        assertTrue(session.sendAudioChunkWithGate(makeSinePcm(1280, 1200.0, 4000), -20.0, -58.0, false, 81_160L),
                "post-resume live block must stream immediately");

        int audioChunks = 0;
        long prevSeq = -1L;
        for (Map<String, Object> f : sent) {
            if ("audio_chunk".equals(f.get("type"))) {
                long seq = MiniJson.getLong(f, "seq", -1L);
                assertTrue(seq > prevSeq,
                        "speaking->listening flushed + mid-flush + live audio_chunk seq must be strictly monotonic (got "
                                + seq + " after " + prevSeq + ")");
                prevSeq = seq;
                audioChunks++;
            }
        }
        // 6 capped preroll blocks + 1 mid-flush buffered block + 1 post-resume live block = 8
        assertEquals(8L, audioChunks);

        // 2. speaking -> hearing ALSO flushes up to 6 recent blocks
        MockTransport tx2 = new MockTransport();
        NorthboundSession s2 = new NorthboundSession(
                "android-v239-hr", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-v239-hr",
                tx2, new NorthboundSession.BaseListener());
        s2.handleIncomingLine("{\"type\":\"mic_session\",\"device\":\"android-v239-hr\",\"action\":\"start\",\"mode\":\"continuous\"}");
        s2.setLocalOfflineKwsReady(true);
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"speaking\"}");
        tx2.sentFrames.clear();
        for (int i = 0; i < 4; i++) {
            s2.sendAudioChunkWithGate(makeSinePcm(1280, 500.0, 3000), -25.0, -58.0, false, 90_000L + i * 80L);
        }
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"hearing\"}");
        int hearingChunks = 0;
        for (Map<String, Object> f : tx2.sentFrames) {
            if ("audio_chunk".equals(f.get("type"))) hearingChunks++;
        }
        assertEquals(4L, hearingChunks);

        // 3. music -> idle, idle -> idle, and music -> listening MUST NOT flush preroll!
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"music\"}");
        tx2.sentFrames.clear();
        for (int i = 0; i < 5; i++) {
            s2.sendAudioChunkWithGate(makeSinePcm(1280, 440.0, 2000), -30.0, -58.0, false, 95_000L + i * 80L);
        }
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"idle\"}");
        assertTrue(tx2.findLastByType("audio_chunk") == null, "music -> idle MUST NOT flush preroll");

        s2.sendAudioChunkWithGate(makeSinePcm(1280, 440.0, 2000), -30.0, -58.0, false, 96_000L);
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"idle\"}");
        assertTrue(tx2.findLastByType("audio_chunk") == null, "idle -> idle MUST NOT flush preroll");

        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"music\"}");
        s2.sendAudioChunkWithGate(makeSinePcm(1280, 440.0, 2000), -30.0, -58.0, false, 97_000L);
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"listening\"}");
        assertTrue(tx2.findLastByType("audio_chunk") == null, "music -> listening MUST NOT flush music preroll");

        // 4. Full-duplex speaking -> listening MUST NOT double-flush idlePrerollRing
        //    (full-duplex speaking already streams live PCM continuously)
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"idle\"}");
        // Buffer stale idle blocks first
        s2.sendAudioChunkWithGate(makeSinePcm(1280, 440.0, 1500), -35.0, -58.0, false, 100_000L);
        s2.sendAudioChunkWithGate(makeSinePcm(1280, 440.0, 1500), -35.0, -58.0, false, 100_080L);
        s2.handleIncomingLine("{\"type\":\"mode_set\",\"duplex_requested\":\"full\"}");
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"speaking\"}");
        assertTrue(s2.isUplinkAudioOpen(false, 100_160L), "full-duplex speaking must stream live");
        tx2.sentFrames.clear();
        assertTrue(s2.sendAudioChunkWithGate(makeSinePcm(1280, 600.0, 3000), -25.0, -58.0, true, 100_240L),
                "full-duplex speaking chunk must transmit directly");
        tx2.sentFrames.clear();
        s2.handleIncomingLine("{\"type\":\"assistant_state\",\"state\":\"listening\"}");
        assertTrue(tx2.findLastByType("audio_chunk") == null,
                "full-duplex speaking -> listening MUST NOT flush stale idlePrerollRing blocks");
    }

    private static byte[] makeSinePcm(int samples, double freqHz, int amplitude) {
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short v = (short) (Math.sin(2.0 * Math.PI * freqHz * i / 16000.0) * amplitude);
            pcm[i * 2] = (byte) (v & 0xFF);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return pcm;
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) {
            throw new AssertionError("Expected non-null: " + msg);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected instanceof Number && actual instanceof Number) {
            double d1 = ((Number) expected).doubleValue();
            double d2 = ((Number) actual).doubleValue();
            if (Math.abs(d1 - d2) < 1e-6) {
                return;
            }
        }
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }
}
