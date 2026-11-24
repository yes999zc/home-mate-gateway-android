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
        System.out.println("PASS: All 9 NorthboundSessionTest suites passed.");
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
        assertEquals("2.3.0", MiniJson.getString(hello, "gateway_version", ""));
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
        assertEquals(5L, MiniJson.getLong(kwsReport, "local_exit", 0));
        assertNotNull(transport.findLastByType("echo_health"), "echo_health must be sent on hello_ack");
        Map<String, Object> cfgState = transport.findLastByType("config_state");
        assertNotNull(cfgState, "config_state must be sent on hello_ack");
        assertEquals(5L, MiniJson.getArray(cfgState, "blocks").size());
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
        assertEquals("active", MiniJson.getString(receipt, "state", ""));
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
        session.sendRetreat("退下");
        boolean postRetreat = session.sendAudioChunkWithGate(quietPcm, -55.0, -58.0, false, 12000L);
        assertTrue(!postRetreat, "uplink gate must close immediately after retreat");
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
