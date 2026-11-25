package homes.mymate.gateway.protocol;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;

import homes.mymate.gateway.I18n;

/**
 * Focused JVM unit & concurrency verification suite for v2.3.11 Final Audit Remediation:
 * 1. P1-1: Atomic Conversation Clear vs Manage-Only SSE & HTTP Polling / History races
 * 2. P1-2: Mobile KWS sensitivity guard applied BEFORE JNI reload, persistence & receipts
 * 3. P1-3 + P2-1: Time-aligned dual-stream voice + ducked media AEC mixer @ 16kHz & 400ms reverb tail
 * 4. P1-5: Whole-House offline RoomNodeSummary card emission on dual-route timeout
 * 5. P2: ReleaseUpdater.isHttpUrl scheme guard, PBFDAF I18n label, and v2.3.11 version bump
 */
public final class FinalAuditRemediationTest {

    private static final class RecordingTransport implements NorthboundSession.FrameTransport {
        final List<Map<String, Object>> frames = new ArrayList<Map<String, Object>>();

        @Override
        public synchronized void sendJsonLine(String jsonLine) {
            frames.add(MiniJson.parseObject(jsonLine));
        }

        synchronized Map<String, Object> findLastByType(String type) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (type.equals(frames.get(i).get("type"))) {
                    return frames.get(i);
                }
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        testP1_1ClearVsManageOnlySseAndPollingRace();
        testP1_1MultiThreadedClearPollSseStress();
        testP1_2MobileKwsGuardBeforeReloadPersistenceAndReceipts();
        testP1_3DualStreamVoiceAndDuckedMediaAecMixer16kHz();
        testP2_1AecPreserves400msReverbTailThenResets();
        testP1_5WholeHouseOfflineCardOnDualRouteTimeout();
        testP2SchemeGuardI18nAndVersion();
        System.out.println("PASS: All 7 FinalAuditRemediationTest suites passed.");
    }

    private static void testP1_1ClearVsManageOnlySseAndPollingRace() {
        Object lock = new Object();
        TranscriptStateStore store = new TranscriptStateStore(lock);
        String host = "192.168.1.50";
        long coreEpoch = store.getCoreSwitchEpoch();

        // Seed initial online snapshot with pre-clear server message
        CoreApiClient.CoreSnapshot initialSnap = makeSnap(Arrays.asList(
                new CoreApiClient.ChatEntry("user", "pre-clear-server-q", 1000L, "s-old", false),
                new CoreApiClient.ChatEntry("assistant", "pre-clear-server-a", 1001L, "s-old", false)
        ));
        long initialTEpoch = store.getTranscriptEpoch();
        store.commitPolledSnapshot(host, coreEpoch, initialTEpoch, true, true, initialSnap);
        assertEquals(2, store.getSnapshot().transcript.size(), "initial transcript must have 2 server turns");

        // 1. Start Manage-Only SSE turn (captures sendTranscriptEpoch BEFORE network call)
        TranscriptStateStore.ManageSendTicket ticket =
                store.beginManageOnlyChatTurn(host, "in-flight-question", coreEpoch, true);
        assertTrue(ticket.accepted, "beginManageOnlyChatTurn must accept on matching coreEpoch");
        assertEquals(3, store.getSnapshot().transcript.size(), "optimistic user turn must append immediately");

        // Also simulate an in-flight HTTP poll & history fetch started BEFORE Clear
        long pollStartTEpoch = store.getTranscriptEpoch();

        // 2. User taps Clear while SSE, HTTP poll, and History fetch are all in-flight!
        assertTrue(store.clearConversation(host, coreEpoch), "clearConversation must succeed");
        assertTrue(store.getSnapshot().transcript.isEmpty(), "Clear must wipe transcript immediately");
        assertTrue(store.getManageOnlySessionId(host) == null, "Clear must wipe manageOnlySessionId");

        // 3. Stale SSE reply arrives after Clear -> MUST be discarded (both reply text & sessionId!)
        boolean committedReply = store.commitManageOnlyChatReply(
                host, coreEpoch, ticket.transcriptEpoch, true, "sess-pre-clear-999", "stale-sse-answer");
        assertTrue(!committedReply, "Stale SSE reply after Clear MUST be rejected");
        assertTrue(store.getManageOnlySessionId(host) == null,
                "Stale reply.sessionId MUST NOT resurrect into manageOnlySessionIds");
        assertTrue(store.getSnapshot().transcript.isEmpty(),
                "Stale SSE reply MUST NOT resurrect into coreSnapshot.transcript");

        // 4. Stale full poll & lightweight poll return pre-clear server transcript -> MUST NOT resurrect!
        CoreApiClient.CoreSnapshot afterStaleFull = store.commitPolledSnapshot(
                host, coreEpoch, pollStartTEpoch, true, true, initialSnap);
        assertTrue(afterStaleFull != null && afterStaleFull.reachable, "non-transcript snapshot metadata still updates");
        assertTrue(afterStaleFull.transcript.isEmpty(), "Stale full poll MUST NOT resurrect pre-clear transcript");

        CoreApiClient.CoreSnapshot afterStaleLite = store.commitPolledSnapshot(
                host, coreEpoch, pollStartTEpoch, true, false, initialSnap);
        assertTrue(afterStaleLite != null && afterStaleLite.transcript.isEmpty(),
                "Stale lightweight poll MUST NOT resurrect pre-clear transcript");

        // 5. Stale history fetch returns pre-clear messages -> MUST return fresh=false & keep transcript empty!
        CoreApiClient.ConversationHistoryResult staleHist = new CoreApiClient.ConversationHistoryResult(
                initialSnap.transcript, "s-old", false, true);
        TranscriptStateStore.HistoryCommitResult hRes = store.commitConversationHistory(
                host, coreEpoch, pollStartTEpoch, true, "dot2-a", staleHist);
        assertTrue(!hRes.fresh && hRes.merged.isEmpty(), "Stale history fetch MUST be rejected after Clear");
        assertTrue(store.getSnapshot().transcript.isEmpty(), "Transcript must remain empty after stale history fetch");

        // 6. Post-clear fresh SSE turn succeeds and binds the new session ID cleanly
        TranscriptStateStore.ManageSendTicket freshTicket =
                store.beginManageOnlyChatTurn(host, "post-clear-q", coreEpoch, true);
        boolean freshCommitted = store.commitManageOnlyChatReply(
                host, coreEpoch, freshTicket.transcriptEpoch, true, "sess-fresh-1", "post-clear-a");
        assertTrue(freshCommitted, "Post-clear SSE reply must commit cleanly");
        assertEquals("sess-fresh-1", store.getManageOnlySessionId(host), "Post-clear sessionId must be stored");
        assertEquals(2, store.getSnapshot().transcript.size(), "Post-clear turn must contain user + assistant");
    }

    private static void testP1_1MultiThreadedClearPollSseStress() throws Exception {
        final TranscriptStateStore store = new TranscriptStateStore(new Object());
        final String host = "192.168.1.77";
        final long coreEpoch = store.getCoreSwitchEpoch();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicReference<String> failureReason = new AtomicReference<String>(null);

        for (int round = 0; round < 120 && !failed.get(); round++) {
            final int r = round;
            final String staleTag = "PRE_CLEAR_ROUND_" + r;
            final CountDownLatch started = new CountDownLatch(2);
            final CountDownLatch goCommit = new CountDownLatch(1);

            // Seed pre-clear turn and begin SSE + poll BEFORE Clear
            final TranscriptStateStore.ManageSendTicket sseTicket =
                    store.beginManageOnlyChatTurn(host, staleTag + "_USER", coreEpoch, true);
            final long pollTEpoch = store.getTranscriptEpoch();
            final CoreApiClient.CoreSnapshot stalePollSnap = makeSnap(Collections.singletonList(
                    new CoreApiClient.ChatEntry("assistant", staleTag + "_POLL", 2000L + r, "s-" + r, false)));

            Thread sseThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    awaitQuietly(goCommit);
                    store.commitManageOnlyChatReply(
                            host, coreEpoch, sseTicket.transcriptEpoch, true,
                            "stale-sid-" + r, staleTag + "_SSE");
                }
            });
            Thread pollThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    awaitQuietly(goCommit);
                    store.commitPolledSnapshot(host, coreEpoch, pollTEpoch, true, (r % 2 == 0), stalePollSnap);
                }
            });
            sseThread.start();
            pollThread.start();
            assertTrue(started.await(2000L, TimeUnit.MILLISECONDS), "worker threads must start");

            // Race Clear simultaneously against SSE commit and Poll commit!
            store.clearConversation(host, coreEpoch);
            goCommit.countDown();
            sseThread.join(2000L);
            pollThread.join(2000L);

            List<CoreApiClient.ChatEntry> after = store.getSnapshot().transcript;
            if (!after.isEmpty()) {
                failed.set(true);
                failureReason.set("Round " + r + " resurrected transcript entry: " + after.get(0).text);
            }
            if (store.getManageOnlySessionId(host) != null) {
                failed.set(true);
                failureReason.set("Round " + r + " resurrected stale sessionId: " + store.getManageOnlySessionId(host));
            }
        }
        assertTrue(!failed.get(), "Concurrent Clear stress test failed: " + failureReason.get());
    }

    private static void testP1_2MobileKwsGuardBeforeReloadPersistenceAndReceipts() throws Exception {
        File tmpDir = Files.createTempDirectory("hm-p12-kws").toFile();
        try {
            File modelDir = new File(tmpDir, "model");
            assertTrue(modelDir.mkdirs(), "create model dir");
            Files.write(new File(modelDir, "tokens.txt").toPath(),
                    "n 0\ni 1\nh 2\nao 3\nt 4\nui 5\n".getBytes(StandardCharsets.UTF_8));

            LocalKwsEngine engine = new LocalKwsEngine(tmpDir, null, "android-p12", null);
            engine.setSensitivity(0.22, 3.00);
            assertEquals(0L, engine.getRequestedReloadSeqForTest(), "initial reload count must be 0");

            RecordingTransport tx = new RecordingTransport();
            final double[] callbackThr = new double[]{-1.0};
            final double[] callbackScore = new double[]{-1.0};
            final int[] kwsUpdatedCalls = new int[]{0};

            NorthboundSession session = new NorthboundSession(
                    "android-p12", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-p12", tx,
                    new NorthboundSession.BaseListener() {
                        @Override
                        public void onConfigBlockApplied(String block, Map<String, Object> values) {
                            if ("kws".equals(block)) {
                                callbackThr[0] = MiniJson.getDouble(values, "threshold", -1.0);
                                callbackScore[0] = MiniJson.getDouble(values, "score", -1.0);
                            }
                        }

                        @Override
                        public void onKwsConfigUpdated(double threshold, double score, List<String> keywords) {
                            kwsUpdatedCalls[0]++;
                            callbackThr[0] = threshold;
                            callbackScore[0] = score;
                        }
                    });
            session.attachLocalKwsEngine(engine);

            // 1. Core pushes Dot2 7-mic default 0.32/2.50 via config_apply(block="kws")
            String applyDot2 = "{\"type\":\"config_apply\",\"device\":\"android-p12\",\"block\":\"kws\","
                    + "\"block_generation\":1,\"data_epoch\":1,\"operation_id\":\"op-dot2\","
                    + "\"values\":{\"keywords\":\"n i h ao @你好小智\\nt ui @退下\",\"retreat_words\":[\"退下\"],"
                    + "\"threshold\":0.32,\"score\":2.50}}";
            session.handleIncomingLine(applyDot2);

            // Single JNI reload with guarded mobile sensitivity 0.22 / 3.00 (NEVER 0.32/2.50!)
            assertEquals(1L, engine.getRequestedReloadSeqForTest(),
                    "config_apply(kws) must trigger EXACTLY ONE JNI reload (no double reload)");
            assertNear(0.22, engine.getThresholdForTest(), 1e-4, "LocalKwsEngine threshold must stay 0.22");
            assertNear(3.00, engine.getScoreForTest(), 1e-4, "LocalKwsEngine score must stay 3.00");
            assertNear(0.22, session.getKwsThreshold(), 1e-4, "NorthboundSession threshold must be 0.22");
            assertNear(3.00, session.getKwsScore(), 1e-4, "NorthboundSession score must be 3.00");
            assertNear(0.22, callbackThr[0], 1e-4, "onConfigBlockApplied must receive guarded threshold 0.22");
            assertNear(3.00, callbackScore[0], 1e-4, "onConfigBlockApplied must receive guarded score 3.00");

            Map<String, Object> receipt = tx.findLastByType("config_receipt");
            assertTrue(receipt != null && "active".equals(MiniJson.getString(receipt, "status", "")),
                    "config_receipt must succeed as active");
            Map<String, Object> activeVals = MiniJson.getObject(receipt, "active_values");
            assertNear(0.22, MiniJson.getDouble(activeVals, "threshold", -1.0), 1e-4,
                    "config_receipt.active_values.threshold must match live LocalKwsEngine (0.22)");
            assertNear(3.00, MiniJson.getDouble(activeVals, "score", -1.0), 1e-4,
                    "config_receipt.active_values.score must match live LocalKwsEngine (3.00)");

            // Persisted JSON & kws_report must also match 0.22 / 3.00 100%
            String persisted = session.exportPersistedConfigStateJson();
            Map<String, Object> persistedObj = MiniJson.parseObject(persisted);
            assertNear(0.22, MiniJson.getDouble(persistedObj, "kws_threshold", -1.0), 1e-4,
                    "persisted kws_threshold must be 0.22");
            assertNear(3.00, MiniJson.getDouble(persistedObj, "kws_score", -1.0), 1e-4,
                    "persisted kws_score must be 3.00");

            session.sendKwsReport("running", "ready");
            Map<String, Object> report = tx.findLastByType("kws_report");
            assertNear(0.22, MiniJson.getDouble(report, "threshold", -1.0), 1e-4,
                    "kws_report.threshold must match live LocalKwsEngine (0.22)");
            assertNear(3.00, MiniJson.getDouble(report, "score", -1.0), 1e-4,
                    "kws_report.score must match live LocalKwsEngine (3.00)");

            // 2. Legacy kws_deploy pushing Dot2 0.32/2.50 without keywords must NOT reload or overwrite!
            tx.frames.clear();
            session.handleIncomingLine("{\"type\":\"kws_deploy\",\"device\":\"android-p12\",\"threshold\":0.32,\"score\":2.50}");
            assertEquals(1L, engine.getRequestedReloadSeqForTest(),
                    "Guarded no-op kws_deploy must not trigger an extra reload");
            assertEquals(1, kwsUpdatedCalls[0], "onKwsConfigUpdated must still fire with guarded values");
            assertNear(0.22, callbackThr[0], 1e-4, "onKwsConfigUpdated threshold must be guarded 0.22");
            assertNear(3.00, callbackScore[0], 1e-4, "onKwsConfigUpdated score must be guarded 3.00");
            Map<String, Object> deployReceipt = tx.findLastByType("kws_deploy_receipt");
            assertNear(0.22, MiniJson.getDouble(deployReceipt, "threshold", -1.0), 1e-4,
                    "kws_deploy_receipt.threshold must be guarded 0.22");
            assertNear(3.00, MiniJson.getDouble(deployReceipt, "score", -1.0), 1e-4,
                    "kws_deploy_receipt.score must be guarded 3.00");

            // 3. Deliberate custom per-device sensitivity (0.26 / 2.85) MUST be accepted verbatim
            tx.frames.clear();
            session.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-p12\",\"block\":\"kws\","
                    + "\"block_generation\":2,\"data_epoch\":1,\"operation_id\":\"op-custom\","
                    + "\"values\":{\"threshold\":0.26,\"score\":2.85}}");
            assertNear(0.26, engine.getThresholdForTest(), 1e-4, "Custom sensitivity threshold must apply");
            assertNear(2.85, engine.getScoreForTest(), 1e-4, "Custom sensitivity score must apply");
            Map<String, Object> customRec = tx.findLastByType("config_receipt");
            Map<String, Object> customVals = MiniJson.getObject(customRec, "active_values");
            assertNear(0.26, MiniJson.getDouble(customVals, "threshold", -1.0), 1e-4,
                    "Custom config_receipt threshold must be 0.26");
            assertNear(2.85, MiniJson.getDouble(customVals, "score", -1.0), 1e-4,
                    "Custom config_receipt score must be 2.85");

            // 4. Explicit per-device override of 0.32 / 2.50 MUST be honored when device_override=true
            session.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-p12\",\"block\":\"kws\","
                    + "\"block_generation\":3,\"data_epoch\":1,\"operation_id\":\"op-override\","
                    + "\"values\":{\"threshold\":0.32,\"score\":2.50,\"device_override\":true}}");
            assertNear(0.32, engine.getThresholdForTest(), 1e-4, "Explicit device_override threshold must apply");
            assertNear(2.50, engine.getScoreForTest(), 1e-4, "Explicit device_override score must apply");

            // 5. Restoring older persisted JSON containing clobbered 0.32/2.50 clamps cleanly to 0.22/3.00
            NorthboundSession restored = new NorthboundSession(
                    "android-p12", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-p12", tx, null);
            assertTrue(restored.restorePersistedConfigStateJson(
                    "{\"version\":1,\"state_revision\":4,\"kws_threshold\":0.32,\"kws_score\":2.50}"),
                    "restorePersistedConfigStateJson must succeed");
            assertNear(0.22, restored.getKwsThreshold(), 1e-4, "Restored Dot2 default threshold must clamp to 0.22");
            assertNear(3.00, restored.getKwsScore(), 1e-4, "Restored Dot2 default score must clamp to 3.00");
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void testP1_3DualStreamVoiceAndDuckedMediaAecMixer16kHz() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int musicRate = 44100;
        int voiceRate = 16000;
        int blockSamples16k = 1280;  // 80ms @ 16kHz
        int musicBlockFrames = 3528; // 80ms @ 44.1kHz
        int delaySamples = 2560;     // 160ms speaker-to-mic delay @ 16kHz
        int dualBlocks = 40;
        int soloMediaBlocks = 20;
        int totalBlocks = dualBlocks + soloMediaBlocks;

        float[] mixedTruthHistory = new float[totalBlocks * blockSamples16k];
        Random rngVoice = new Random(303L);
        Random rngMusic = new Random(909L);
        float lpVoice = 0f;
        float lpMusic = 0f;
        long now = 100_000L;

        double erleDualEnd = 0.0;
        double erleSoloMediaEnd = 0.0;

        for (int b = 0; b < totalBlocks; b++) {
            boolean voicePlaying = (b < dualBlocks);
            float mediaGain = voicePlaying ? 0.20f : 1.0f;
            float voiceGain = 1.0f;

            // Generate 44.1kHz stereo music chunk & compute 16kHz boxcar reference truth
            byte[] stereoMusic = new byte[musicBlockFrames * 4];
            float[] music16kSum = new float[blockSamples16k];
            int[] music16kCnt = new int[blockSamples16k];
            for (int f = 0; f < musicBlockFrames; f++) {
                float w = (rngMusic.nextFloat() * 2f - 1f) * 0.70f;
                lpMusic = 0.68f * lpMusic + 0.32f * w;
                writeS16(stereoMusic, f * 4, lpMusic);
                writeS16(stereoMusic, f * 4 + 2, lpMusic);
                int idx = (int) ((f * (double) voiceRate) / (double) musicRate);
                if (idx < blockSamples16k) {
                    music16kSum[idx] += lpMusic;
                    music16kCnt[idx]++;
                }
            }

            byte[] voicePcm = new byte[blockSamples16k * 2];
            for (int i = 0; i < blockSamples16k; i++) {
                float v = 0f;
                if (voicePlaying) {
                    float w = (rngVoice.nextFloat() * 2f - 1f) * 0.55f;
                    lpVoice = 0.50f * lpVoice + 0.50f * w;
                    v = lpVoice;
                    writeS16(voicePcm, i * 2, v);
                }
                float m16 = music16kCnt[i] > 0 ? (music16kSum[i] / music16kCnt[i]) : 0f;
                float mixed = Math.max(-1.0f, Math.min(1.0f, voiceGain * v + mediaGain * m16));
                mixedTruthHistory[b * blockSamples16k + i] = mixed;
            }

            long ts = now + b * 80L;
            // Push BOTH media (ducked to 0.20f during TTS) and voice (1.0f) concurrently!
            aec.pushPlaybackReference(
                    SoftwareAecFilter.CHANNEL_MEDIA, stereoMusic, 0, stereoMusic.length,
                    musicRate, 2, mediaGain, ts);
            if (voicePlaying) {
                aec.pushPlaybackReference(
                        SoftwareAecFilter.CHANNEL_VOICE, voicePcm, 0, voicePcm.length,
                        voiceRate, 1, voiceGain, ts);
            } else if (b == dualBlocks) {
                aec.notifyChannelStopped(SoftwareAecFilter.CHANNEL_VOICE);
            }

            // Synthesize mic echo from true mixed speaker output (voice * 1.0 + music * 0.20)
            byte[] micBlock = new byte[blockSamples16k * 2];
            for (int i = 0; i < blockSamples16k; i++) {
                int src = b * blockSamples16k + i - delaySamples;
                float echo = 0f;
                if (src >= 24) {
                    echo = 0.46f * mixedTruthHistory[src] - 0.12f * mixedTruthHistory[src - 24];
                }
                writeS16(micBlock, i * 2, echo);
            }
            aec.processCaptureBlock(micBlock, ts);

            if (b == dualBlocks - 1) {
                // Verify refRing advanced at TRUE 16kHz (40 * 1280 = 51200), NEVER 32kHz (102400)!
                assertEquals((long) dualBlocks * blockSamples16k, aec.getTotalRefSamplesWrittenForTest(),
                        "Concurrent voice + media MUST advance refRing at true 16kHz, not 32kHz");
                erleDualEnd = aec.getLastErleDb();
            }
            if (b == totalBlocks - 1) {
                assertEquals((long) totalBlocks * blockSamples16k, aec.getTotalRefSamplesWrittenForTest(),
                        "Transition from dual-stream to solo media MUST stay at true 16kHz");
                erleSoloMediaEnd = aec.getLastErleDb();
            }
        }

        assertTrue(erleDualEnd >= 8.0,
                "Concurrent voice + ducked media PBFDAF ERLE must reach >= 8dB, got " + erleDualEnd + "dB");
        assertTrue(erleSoloMediaEnd >= 8.0,
                "Post-TTS un-ducked media PBFDAF ERLE must remain >= 8dB, got " + erleSoloMediaEnd + "dB");
    }

    private static void testP2_1AecPreserves400msReverbTailThenResets() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int blockSamples = 1280; // 80ms @ 16kHz
        int delaySamples = 2560; // 160ms speaker + room delay
        int activeBlocks = 30;
        float[] refHistory = new float[(activeBlocks + 6) * blockSamples];
        Random rng = new Random(404L);
        float lp = 0f;
        long now = 200_000L;

        for (int b = 0; b < activeBlocks; b++) {
            byte[] refPcm = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                float w = (rng.nextFloat() * 2f - 1f) * 0.60f;
                lp = 0.55f * lp + 0.45f * w;
                refHistory[b * blockSamples + i] = lp;
                writeS16(refPcm, i * 2, lp);
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(
                    SoftwareAecFilter.CHANNEL_VOICE, refPcm, 0, refPcm.length, 16000, 1, 1.0f, ts);

            byte[] mic = buildDelayedEcho(refHistory, b * blockSamples, blockSamples, delaySamples);
            aec.processCaptureBlock(mic, ts);
        }
        assertTrue(aec.getLastErleDb() >= 8.0,
                "Active playback must converge >= 8dB before stop, got " + aec.getLastErleDb());

        // Playback stops -> notifyPlaybackStopped() MUST preserve the 400ms reverb tail!
        long stopWallMs = now + (activeBlocks - 1) * 80L;
        aec.notifyPlaybackStopped();
        assertTrue(aec.isInReverbTailForTest(), "notifyPlaybackStopped must enter reverb tail mode");

        // Block +80ms and +160ms after stop: acoustic echo from buffered speaker output still arrives!
        byte[] tailMic1 = buildDelayedEcho(refHistory, activeBlocks * blockSamples, blockSamples, delaySamples);
        aec.processCaptureBlock(tailMic1, stopWallMs + 80L);
        double tailErle1 = aec.getLastErleDb();
        assertTrue(tailErle1 >= 6.0,
                "80ms post-stop reverb tail must still be cancelled (>= 6dB ERLE), got " + tailErle1 + "dB");

        byte[] tailMic2 = buildDelayedEcho(refHistory, (activeBlocks + 1) * blockSamples, blockSamples, delaySamples);
        aec.processCaptureBlock(tailMic2, stopWallMs + 160L);
        double tailErle2 = aec.getLastErleDb();
        assertTrue(tailErle2 >= 6.0,
                "160ms post-stop reverb tail must still be cancelled (>= 6dB ERLE), got " + tailErle2 + "dB");

        // Once > 400ms elapses, AEC resets cleanly and returns PCM untouched
        byte[] postTailMic = new byte[blockSamples * 2];
        for (int i = 0; i < blockSamples; i++) {
            writeS16(postTailMic, i * 2, 0.15f * (float) Math.sin(2.0 * Math.PI * 500.0 * i / 16000.0));
        }
        byte[] postTailOut = aec.processCaptureBlock(postTailMic, stopWallMs + 480L);
        assertTrue(!aec.isInReverbTailForTest(), "Reverb tail must expire and clear after >400ms");
        assertNear(0.0, aec.getLastErleDb(), 1e-6, "ERLE must reset to 0 after tail expires");
        assertTrue(Arrays.equals(postTailMic, postTailOut), "Post-tail capture must pass unmodified");
    }

    private static void testP1_5WholeHouseOfflineCardOnDualRouteTimeout() {
        // 1. Dual-route room where primary LAN fails quickly and Tailscale fallback succeeds
        CoreApiClient.RoomTarget dualRecover = new CoreApiClient.RoomTarget(
                "192.168.1.10", 8930, 8932, "客厅", "", "core-living",
                Collections.singletonList("100.64.0.10"));
        // 2. Dual-route room where BOTH routes hang past the wait budget
        CoreApiClient.RoomTarget dualTimeout = new CoreApiClient.RoomTarget(
                "192.168.1.99", 8930, 8932, "主卧", "", "core-bedroom",
                Collections.singletonList("100.64.0.99"));

        CoreApiClient.RoomProbeFn fakeProbe = new CoreApiClient.RoomProbeFn() {
            @Override
            public CoreApiClient.RoomNodeSummary probe(
                    String host, int webPort, int gatewayPort,
                    String fallbackName, String bearerToken, String expectedCoreId, int perRequestTimeoutMs) {
                if ("192.168.1.10".equals(host)) {
                    return CoreApiClient.RoomNodeSummary.offline(host, webPort, gatewayPort, fallbackName, expectedCoreId, "unreachable");
                }
                if ("100.64.0.10".equals(host)) {
                    return new CoreApiClient.RoomNodeSummary(
                            host, webPort, gatewayPort, fallbackName, "客厅小智",
                            true, false, true, false, "", "", "half", "dot2-living", 1,
                            null, expectedCoreId, false, 0.60, false);
                }
                // Simulate blackholed dual-route room hanging longer than overall wait budget
                try {
                    Thread.sleep(4000L);
                } catch (InterruptedException ignored) {
                }
                return CoreApiClient.RoomNodeSummary.offline(host, webPort, gatewayPort, fallbackName, expectedCoreId, "unreachable");
            }
        };

        long t0 = System.currentTimeMillis();
        List<CoreApiClient.RoomNodeSummary> cards = CoreApiClient.fetchRoomSummariesConcurrently(
                Arrays.asList(dualRecover, dualTimeout), 450L, fakeProbe);
        long elapsedMs = System.currentTimeMillis() - t0;

        assertEquals(2, cards.size(), "Timed-out dual-route room MUST NOT be silently omitted from Whole-House list");
        CoreApiClient.RoomNodeSummary living = cards.get(0);
        CoreApiClient.RoomNodeSummary bedroom = cards.get(1);

        assertTrue(living.online && "100.64.0.10".equals(living.host) && "客厅".equals(living.displayName),
                "Dual-route room with reachable fallback must render online on fallback host");
        assertTrue(!bedroom.online && !bedroom.authRequired
                        && "192.168.1.99".equals(bedroom.host)
                        && "主卧".equals(bedroom.displayName)
                        && "core-bedroom".equals(bedroom.coreId)
                        && "timeout".equals(bedroom.error),
                "Timed-out dual-route room must render explicit offline card with host/name/coreId/timeout error");
        assertTrue(elapsedMs < 2000L, "Shared deadline must bound wait time cleanly, took " + elapsedMs + "ms");
    }

    private static void testP2SchemeGuardI18nAndVersion() {
        // ReleaseUpdater.isHttpUrl scheme guard (also used by AppUpdater.openDownloadInBrowser)
        assertTrue(ReleaseUpdater.isHttpUrl("https://github.com/yes999zc/home-mate-gateway-android/releases"),
                "https URL must be accepted");
        assertTrue(ReleaseUpdater.isHttpUrl("HTTP://192.168.1.10:8930/hm.apk"),
                "case-insensitive http URL must be accepted");
        assertTrue(!ReleaseUpdater.isHttpUrl("javascript:alert(1)"), "javascript: must be rejected");
        assertTrue(!ReleaseUpdater.isHttpUrl("file:///etc/passwd"), "file: must be rejected");
        assertTrue(!ReleaseUpdater.isHttpUrl("intent://scan/#Intent;end"), "intent: must be rejected");
        assertTrue(!ReleaseUpdater.isHttpUrl("content://media/external/file/1"), "content: must be rejected");
        assertTrue(!ReleaseUpdater.isHttpUrl(""), "empty URL must be rejected");
        assertTrue(!ReleaseUpdater.isHttpUrl(null), "null URL must be rejected");

        // I18n settings_sw_aec PBFDAF label
        String zhAec = I18n.t(I18n.LANG_ZH, "settings_sw_aec");
        String enAec = I18n.t(I18n.LANG_EN, "settings_sw_aec");
        assertTrue(zhAec.contains("PBFDAF") && !zhAec.contains("NLMS"),
                "ZH settings_sw_aec must say PBFDAF, got: " + zhAec);
        assertTrue(enAec.contains("PBFDAF") && !enAec.contains("NLMS"),
                "EN settings_sw_aec must say PBFDAF, got: " + enAec);

        // Version bump check
        assertEquals("2.3.15", NorthboundSession.GATEWAY_VERSION, "GATEWAY_VERSION must be 2.3.15");
    }

    private static CoreApiClient.CoreSnapshot makeSnap(List<CoreApiClient.ChatEntry> transcript) {
        return new CoreApiClient.CoreSnapshot(
                true, false, "小智", "build-1", "half", true, true, "off",
                false, false, "", "",
                Collections.<CoreApiClient.DeviceInfo>emptyList(),
                Collections.<CoreApiClient.FleetNodeInfo>emptyList(),
                transcript, null, null);
    }

    private static byte[] buildDelayedEcho(float[] history, int blockOffset, int blockSamples, int delaySamples) {
        byte[] mic = new byte[blockSamples * 2];
        for (int i = 0; i < blockSamples; i++) {
            int src = blockOffset + i - delaySamples;
            float echo = (src >= 16 && src < history.length)
                    ? (0.48f * history[src] - 0.14f * history[src - 16])
                    : 0f;
            writeS16(mic, i * 2, echo);
        }
        return mic;
    }

    private static void writeS16(byte[] buf, int byteOff, float sample) {
        int s = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32767.0f);
        buf[byteOff] = (byte) (s & 0xFF);
        buf[byteOff + 1] = (byte) ((s >> 8) & 0xFF);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        f.delete();
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FinalAuditRemediationTest FAILED: " + msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FinalAuditRemediationTest FAILED: " + msg
                    + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void assertNear(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) {
            throw new AssertionError("FinalAuditRemediationTest FAILED: " + msg
                    + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
