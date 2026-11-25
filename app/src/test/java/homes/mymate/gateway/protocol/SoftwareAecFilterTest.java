package homes.mymate.gateway.protocol;

import java.util.Random;

/**
 * Unit tests for v2.3.9 / v2.3.13 / v2.3.14 8-partition PBFDAF {@link SoftwareAecFilter}:
 * 1. 40ms reverberant voice/TTS echo convergence (ERLE >= 8dB)
 * 2. 300ms stereo 44.1kHz music AudioTrack delay convergence (ERLE >= 8dB) via slice push overload
 * 3. Double-talk protection: near-end speech preserved with high correlation while echo stays cancelled
 * 4. Delay jump (40ms -> 280ms) re-lock & non-negative ERLE safety guard
 * 5. 1150ms echo delay (22.05kHz media playback w/ 1000ms AudioTrack + HAL) & 750ms+ push-gap survival
 * 6. Zero-drift fractional-phase resampler across uneven WRITE_NON_BLOCKING partial writes at 22050Hz & 44100Hz
 */
public final class SoftwareAecFilterTest {

    public static void main(String[] args) {
        testPbfdafCancels40msReverberantEcho();
        testPbfdafCancels300msMusicAudioTrackEcho();
        testPbfdafDoubleTalkPreservesNearEndSpeech();
        testPbfdafDelayJumpReconvergesAndNonNegativeGuard();
        testPbfdafCancels1150msEchoAndSurvives750msGap();
        testPartialWriteZeroDriftAndErle22050And44100();
        testPhaseDispersedPhoneSpeaker1150msWithHwHintAndTelemetry();
        testVoiceToMediaChannelSwitchReLocksViaHwHint();
        System.out.println("PASS: All 8 SoftwareAecFilterTest suites passed.");
    }

    private static void testPbfdafCancels40msReverberantEcho() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int sampleRate = 16000;
        int blockSamples = 1280; // 80ms
        int delaySamples = 640;  // 40ms direct path
        int totalBlocks = 30;
        float[] refHistory = new float[totalBlocks * blockSamples];
        Random rng = new Random(101L);
        long now = 10_000L;

        // Multi-tap room impulse response spanning ~22ms (352 samples) after direct path
        int[] tapOffsets = new int[]{0, 3, 11, 29, 67, 130, 220, 340};
        float[] tapGains = new float[]{0.48f, -0.18f, 0.14f, -0.10f, 0.08f, -0.05f, 0.04f, -0.025f};

        double rawRms = -96.0;
        double cleanRms = -96.0;
        double lastErle = 0.0;

        float lp = 0f;
        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.65f;
                lp = 0.55f * lp + 0.45f * white;
                refHistory[b * blockSamples + i] = lp;
                writeS16(refChunk, i * 2, lp);
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(refChunk, sampleRate, 1, ts);

            byte[] micBlock = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                int base = b * blockSamples + i - delaySamples;
                float echo = 0f;
                for (int t = 0; t < tapOffsets.length; t++) {
                    int idx = base - tapOffsets[t];
                    if (idx >= 0) {
                        echo += tapGains[t] * refHistory[idx];
                    }
                }
                writeS16(micBlock, i * 2, echo);
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, ts);
            if (b == totalBlocks - 1) {
                rawRms = NorthboundSession.computeRmsDbfs(micBlock);
                cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
                lastErle = aec.getLastErleDb();
            }
        }
        double attenDb = rawRms - cleanRms;
        assertTrue(attenDb >= 8.0 && lastErle >= 8.0,
                "Expected 40ms reverberant PBFDAF ERLE >= 8dB (atten=" + attenDb
                        + "dB, erle=" + lastErle + "dB, estDelay=" + aec.getEstimatedDelayMs() + "ms)");
        int estMs = aec.getEstimatedDelayMs();
        assertTrue(estMs >= 25 && estMs <= 50,
                "Expected estimated delay near 36ms (40ms - 4ms anchor), got " + estMs + "ms");
    }

    private static void testPbfdafCancels300msMusicAudioTrackEcho() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int musicRate = 44100;
        int micRate = 16000;
        int micBlockSamples = 1280;  // 80ms @ 16kHz
        int musicBlockFrames = 3528; // 80ms @ 44.1kHz
        int delaySamples = 4800;     // 300ms speaker+AudioTrack latency @ 16kHz!
        int totalBlocks = 45;
        float[] mono16kHistory = new float[totalBlocks * micBlockSamples];
        Random rng = new Random(2026L);
        long now = 50_000L;

        double rawRms = -96.0;
        double cleanRms = -96.0;
        double lastErle = 0.0;

        float lp = 0f;
        for (int b = 0; b < totalBlocks; b++) {
            // Test zero-copy slice overload by placing audio inside a padded buffer with non-zero offset
            int padBytes = 64;
            byte[] paddedStereo = new byte[padBytes + musicBlockFrames * 4 + 32];
            float[] binSum = new float[micBlockSamples];
            int[] binCnt = new int[micBlockSamples];
            for (int f = 0; f < musicBlockFrames; f++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.72f;
                lp = 0.70f * lp + 0.30f * white;
                int pos = padBytes + f * 4;
                writeS16(paddedStereo, pos, lp);
                writeS16(paddedStereo, pos + 2, lp);
                int idx16k = (int) ((f * (double) micRate) / (double) musicRate);
                if (idx16k < micBlockSamples) {
                    binSum[idx16k] += lp;
                    binCnt[idx16k]++;
                }
            }
            for (int i = 0; i < micBlockSamples; i++) {
                mono16kHistory[b * micBlockSamples + i] = binCnt[i] > 0 ? (binSum[i] / binCnt[i]) : 0f;
            }
            long ts = now + b * 80L;
            // Push in two partial slices just like Android AudioTrack.WRITE_NON_BLOCKING
            int firstBytes = (musicBlockFrames / 2) * 4;
            int secondBytes = musicBlockFrames * 4 - firstBytes;
            aec.pushPlaybackReference(paddedStereo, padBytes, firstBytes, musicRate, 2, ts);
            aec.pushPlaybackReference(paddedStereo, padBytes + firstBytes, secondBytes, musicRate, 2, ts + 20L);

            byte[] micBlock = new byte[micBlockSamples * 2];
            for (int i = 0; i < micBlockSamples; i++) {
                int srcIdx = b * micBlockSamples + i - delaySamples;
                float echo = 0f;
                if (srcIdx >= 80) {
                    echo = 0.44f * mono16kHistory[srcIdx]
                            - 0.14f * mono16kHistory[srcIdx - 19]
                            + 0.09f * mono16kHistory[srcIdx - 80];
                }
                writeS16(micBlock, i * 2, echo);
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, ts + 20L);
            if (b == totalBlocks - 1) {
                rawRms = NorthboundSession.computeRmsDbfs(micBlock);
                cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
                lastErle = aec.getLastErleDb();
            }
        }
        double attenDb = rawRms - cleanRms;
        assertTrue(attenDb >= 8.0 && lastErle >= 8.0,
                "Expected 300ms 44.1kHz stereo music PBFDAF ERLE >= 8dB (atten=" + attenDb
                        + "dB, erle=" + lastErle + "dB, estDelay=" + aec.getEstimatedDelayMs() + "ms)");
        int estMs = aec.getEstimatedDelayMs();
        assertTrue(estMs >= 275 && estMs <= 315,
                "Expected 300ms music delay lock near 296ms, got " + estMs + "ms");
    }

    private static void testPbfdafDoubleTalkPreservesNearEndSpeech() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int sampleRate = 16000;
        int blockSamples = 1280;
        int delaySamples = 4800; // 300ms music echo
        int convergeBlocks = 35;
        int doubleTalkBlocks = 8;
        int totalBlocks = convergeBlocks + doubleTalkBlocks;
        float[] refHistory = new float[totalBlocks * blockSamples];
        Random rng = new Random(777L);
        long now = 100_000L;
        float lp = 0f;

        double erleBeforeDoubleTalk = 0.0;
        double speechCorrSum = 0.0;
        double residualErrPower = 0.0;
        double rawEchoPower = 0.0;

        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.60f;
                lp = 0.60f * lp + 0.40f * white;
                refHistory[b * blockSamples + i] = lp;
                writeS16(refChunk, i * 2, lp);
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(refChunk, sampleRate, 1, ts);

            boolean inDoubleTalk = (b >= convergeBlocks);
            float[] nearEnd = new float[blockSamples];
            byte[] micBlock = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                int globalIdx = b * blockSamples + i;
                int srcIdx = globalIdx - delaySamples;
                float echo = (srcIdx >= 20)
                        ? (0.45f * refHistory[srcIdx] + 0.10f * refHistory[srcIdx - 20])
                        : 0f;
                float voice = 0f;
                if (inDoubleTalk) {
                    // Simulated near-end voice formants (480Hz + 1120Hz)
                    double tSec = globalIdx / (double) sampleRate;
                    voice = (float) (0.20 * Math.sin(2.0 * Math.PI * 480.0 * tSec)
                            + 0.14 * Math.sin(2.0 * Math.PI * 1120.0 * tSec));
                }
                nearEnd[i] = voice;
                writeS16(micBlock, i * 2, echo + voice);
                if (inDoubleTalk) {
                    rawEchoPower += (double) echo * echo;
                }
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, ts);
            if (b == convergeBlocks - 1) {
                erleBeforeDoubleTalk = aec.getLastErleDb();
            }
            if (inDoubleTalk) {
                double dot = 0.0;
                double vPow = 1e-9;
                double cPow = 1e-9;
                for (int i = 0; i < blockSamples; i++) {
                    float c = readS16(cleaned, i * 2);
                    float v = nearEnd[i];
                    dot += (double) c * v;
                    vPow += (double) v * v;
                    cPow += (double) c * c;
                    double diff = c - v;
                    residualErrPower += diff * diff;
                }
                speechCorrSum += dot / Math.sqrt(vPow * cPow);
            }
        }

        double avgSpeechCorr = speechCorrSum / doubleTalkBlocks;
        double echoRejectionDuringDoubleTalkDb = 10.0 * Math.log10(rawEchoPower / Math.max(1e-9, residualErrPower));
        assertTrue(erleBeforeDoubleTalk >= 8.0,
                "Expected pre-double-talk convergence ERLE >= 8dB, got " + erleBeforeDoubleTalk + "dB");
        assertTrue(avgSpeechCorr >= 0.85,
                "Double-talk guard must preserve near-end speech (corr >= 0.85), got " + avgSpeechCorr);
        assertTrue(echoRejectionDuringDoubleTalkDb >= 8.0,
                "Echo must remain strongly cancelled during double-talk (>= 8dB), got "
                        + echoRejectionDuringDoubleTalkDb + "dB");
    }

    private static void testPbfdafDelayJumpReconvergesAndNonNegativeGuard() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int sampleRate = 16000;
        int blockSamples = 1280;
        int phase1Blocks = 20;
        int phase2Blocks = 30;
        int totalBlocks = phase1Blocks + phase2Blocks;
        float[] refHistory = new float[totalBlocks * blockSamples];
        Random rng = new Random(909L);
        long now = 200_000L;
        float lp = 0f;

        double erlePhase1 = 0.0;
        double erlePhase2 = 0.0;
        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.65f;
                lp = 0.60f * lp + 0.40f * white;
                refHistory[b * blockSamples + i] = lp;
                writeS16(refChunk, i * 2, lp);
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(refChunk, sampleRate, 1, ts);

            int curDelay = (b < phase1Blocks) ? 640 : 4480; // 40ms -> 280ms sudden jump!
            byte[] micBlock = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                int srcIdx = b * blockSamples + i - curDelay;
                float echo = (srcIdx >= 0) ? (0.45f * refHistory[srcIdx]) : 0f;
                writeS16(micBlock, i * 2, echo);
            }
            aec.processCaptureBlock(micBlock, ts);
            if (b == phase1Blocks - 1) {
                erlePhase1 = aec.getLastErleDb();
            }
            if (b == totalBlocks - 1) {
                erlePhase2 = aec.getLastErleDb();
            }
        }
        assertTrue(erlePhase1 >= 8.0, "Phase 1 (40ms) ERLE must reach >= 8dB, got " + erlePhase1);
        assertTrue(erlePhase2 >= 8.0, "Phase 2 (280ms after delay jump) ERLE must reconverge >= 8dB, got " + erlePhase2);

        // Non-negative ERLE guard on digital silence must return original silent PCM unmodified
        byte[] silence = new byte[blockSamples * 2];
        byte[] outSilence = aec.processCaptureBlock(silence, now + totalBlocks * 80L);
        assertTrue(NorthboundSession.computeRmsDbfs(outSilence) <= -95.0,
                "Non-negative ERLE guard must never inject echo noise into silent capture");
    }

    private static void testPbfdafCancels1150msEchoAndSurvives750msGap() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int mediaRate = 22050;
        int micRate = 16000;
        int micBlockSamples = 1280;  // 80ms @ 16kHz
        int mediaBlockFrames = 1764; // exact 80ms @ 22.05kHz (1764 / (22050/16000) == 1280)
        int delaySamples = 18400;    // 1150ms total delay (1000ms AudioTrack + 150ms HAL) @ 16kHz
        int convergeBlocks = 50;     // 4000ms active streaming (echo arrives after 1150ms -> 2850ms to converge)
        int gapBlocks = 10;          // 800ms push gap (exceeds both 600ms legacy cutoff and 750ms requirement)
        int resumeBlocks = 6;        // 480ms resumed media streaming after gap
        int totalBlocks = convergeBlocks + gapBlocks + resumeBlocks;

        double ratio = (double) mediaRate / (double) micRate;
        int maxPushBlocks = convergeBlocks + resumeBlocks;
        float[] mono16kTimeline = new float[maxPushBlocks * micBlockSamples];
        byte[][] media22kChunks = new byte[maxPushBlocks][mediaBlockFrames * 2];
        Random rng = new Random(2313L);
        float lp = 0f;
        for (int p = 0; p < maxPushBlocks; p++) {
            float[] raw22k = new float[mediaBlockFrames];
            for (int f = 0; f < mediaBlockFrames; f++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.72f;
                lp = 0.68f * lp + 0.32f * white;
                raw22k[f] = lp;
                writeS16(media22kChunks[p], f * 2, lp);
            }
            // Continuous-time DAC -> 16kHz mic truth waveform (exact fractional position o * ratio)
            for (int o = 0; o < micBlockSamples; o++) {
                double srcPos = o * ratio;
                int idx0 = (int) Math.floor(srcPos);
                int idx1 = Math.min(mediaBlockFrames - 1, idx0 + 1);
                float frac = (float) (srcPos - idx0);
                mono16kTimeline[p * micBlockSamples + o] = raw22k[idx0] + (raw22k[idx1] - raw22k[idx0]) * frac;
            }
        }

        long now = 300_000L;
        int nextPushBlock = 0;
        double convergeErle = 0.0;
        double convergeAttenDb = 0.0;
        double minGapErle = Double.MAX_VALUE;
        double gap720msErle = 0.0;
        double gap800msErle = 0.0;
        double gap800msAttenDb = 0.0;
        double resumeEndErle = 0.0;

        for (int b = 0; b < totalBlocks; b++) {
            long ts = now + b * 80L;
            boolean inGap = (b >= convergeBlocks && b < convergeBlocks + gapBlocks);
            if (!inGap) {
                byte[] chunk = media22kChunks[nextPushBlock++];
                // Pass live AudioTrack queue depth hint (1000ms before gap, 200ms after 800ms drain)
                // to verify wall-clock hint decay keeps converged lock intact across the 800ms gap.
                int liveTrackQueueMs = (b < convergeBlocks) ? 1000 : 200;
                aec.pushPlaybackReference(
                        SoftwareAecFilter.CHANNEL_MEDIA, chunk, 0, chunk.length,
                        mediaRate, 1, 1.0f, ts, liveTrackQueueMs);
            }

            // Speaker plays continuously from mono16kTimeline at exact 1150ms (18,400 samples) delay
            // even while pushPlaybackReference pauses for the 800ms gap (1000ms AudioTrack drains smoothly).
            byte[] micBlock = new byte[micBlockSamples * 2];
            for (int i = 0; i < micBlockSamples; i++) {
                int srcIdx = b * micBlockSamples + i - delaySamples;
                float echo = 0f;
                if (srcIdx >= 64) {
                    echo = 0.46f * mono16kTimeline[srcIdx]
                            - 0.14f * mono16kTimeline[srcIdx - 19]
                            + 0.08f * mono16kTimeline[srcIdx - 64];
                }
                writeS16(micBlock, i * 2, echo);
            }

            byte[] cleaned = aec.processCaptureBlock(micBlock, ts);
            double rawRms = NorthboundSession.computeRmsDbfs(micBlock);
            double cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
            double attenDb = rawRms - cleanRms;
            double erle = aec.getLastErleDb();

            if (b == convergeBlocks - 1) {
                convergeErle = erle;
                convergeAttenDb = attenDb;
                int estMs = aec.getEstimatedDelayMs();
                assertTrue(estMs >= 1120 && estMs <= 1170,
                        "Expected 1150ms media delay lock near 1146ms, got " + estMs + "ms");
            }
            if (inGap) {
                assertTrue(aec.isDelayLocked(),
                        "Decayed hwQueueHintMs must NEVER falsely evict delayLocked during 800ms push gap at block " + b);
                if (erle < minGapErle) {
                    minGapErle = erle;
                }
                if (b == convergeBlocks + 8) { // +720ms since last push
                    gap720msErle = erle;
                }
                if (b == convergeBlocks + gapBlocks - 1) { // +800ms since last push (>750ms)
                    gap800msErle = erle;
                    gap800msAttenDb = attenDb;
                }
            }
            if (b == totalBlocks - 1) {
                resumeEndErle = erle;
            }
        }

        assertTrue(convergeErle >= 8.0 && convergeAttenDb >= 8.0,
                "Expected 1150ms 22.05kHz PBFDAF convergence ERLE >= 8dB (erle="
                        + convergeErle + "dB, atten=" + convergeAttenDb + "dB)");
        assertTrue(minGapErle >= 8.0 && gap720msErle >= 8.0 && gap800msErle >= 8.0 && gap800msAttenDb >= 8.0,
                "Expected sustained ERLE >= 8dB across entire 750ms+ push gap (min=" + minGapErle
                        + "dB, @720ms=" + gap720msErle + "dB, @800ms=" + gap800msErle
                        + "dB, atten@800ms=" + gap800msAttenDb + "dB)");
        assertTrue(resumeEndErle >= 8.0,
                "Expected ERLE >= 8dB to remain converged after resuming pushes post-gap, got " + resumeEndErle + "dB");
    }

    private static void testPartialWriteZeroDriftAndErle22050And44100() {
        runPartialWriteZeroDriftScenario(22050, 1, 23141L);
        runPartialWriteZeroDriftScenario(44100, 2, 23142L);

        // Edge case: tiny 1-frame partial writes at 44100Hz (ratio=2.75625) MUST emit 0 samples
        // when fractional phase >= 1.0 instead of writing a bogus 1-sample duplicate per write!
        SoftwareAecFilter tiny = new SoftwareAecFilter();
        byte[] oneFrameStereo = new byte[4];
        writeS16(oneFrameStereo, 0, 0.25f);
        writeS16(oneFrameStereo, 2, 0.25f);
        long t0 = 500_000L;
        for (int f = 0; f < 441; f++) { // 10ms @ 44.1kHz -> exact 160 samples @ 16kHz
            tiny.pushPlaybackReference(SoftwareAecFilter.CHANNEL_MEDIA, oneFrameStereo, 0, 4, 44100, 2, 1.0f, t0 + (f / 45));
        }
        long writtenTiny = tiny.getTotalRefSamplesWrittenForTest();
        assertTrue(Math.abs(writtenTiny - 160L) <= 1L,
                "441 single-frame 44.1kHz writes must produce 160±1 samples (not 441!), got " + writtenTiny);
    }

    private static void runPartialWriteZeroDriftScenario(int playbackRate, int channels, long seed) {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        final int durationSec = 4;
        final int totalInFrames = playbackRate * durationSec;
        final int expectedOut16k = 16000 * durationSec; // 64,000 samples
        final int micBlockSamples = 1280; // 80ms @ 16kHz
        final int totalMicBlocks = expectedOut16k / micBlockSamples; // 50 blocks
        final int delaySamples = 2560; // 160ms speaker-to-mic delay @ 16kHz

        // Generate unbroken 4-second colored music signal at playbackRate
        float[] fullMono = new float[totalInFrames];
        byte[] fullPcm = new byte[totalInFrames * 2 * channels];
        Random rng = new Random(seed);
        float lp = 0f;
        for (int f = 0; f < totalInFrames; f++) {
            float white = (rng.nextFloat() * 2f - 1f) * 0.70f;
            lp = 0.72f * lp + 0.28f * white;
            fullMono[f] = lp;
            if (channels == 1) {
                writeS16(fullPcm, f * 2, lp);
            } else {
                writeS16(fullPcm, f * 4, lp);
                writeS16(fullPcm, f * 4 + 2, lp);
            }
        }

        // Synthesize continuous unbroken 16kHz mic-ADC truth waveform (real 16kHz ADC applies
        // hardware anti-aliasing before sampling >28.8kHz playback to avoid >8kHz foldback).
        float[] dacSource = fullMono;
        double ratio = (double) playbackRate / 16000.0;
        if (ratio > 1.8) {
            dacSource = new float[totalInFrames];
            for (int f = 0; f < totalInFrames; f++) {
                float fm2 = fullMono[Math.max(0, f - 2)];
                float fm1 = fullMono[Math.max(0, f - 1)];
                float f0 = fullMono[f];
                float fp1 = fullMono[Math.min(totalInFrames - 1, f + 1)];
                float fp2 = fullMono[Math.min(totalInFrames - 1, f + 2)];
                // Smooth 5-tap raised-cosine ADC anti-aliasing LPF (distinct from AEC's 3-tap AA)
                dacSource[f] = 0.10f * fm2 + 0.22f * fm1 + 0.36f * f0 + 0.22f * fp1 + 0.10f * fp2;
            }
        }
        float[] trueDac16k = new float[expectedOut16k];
        for (int o = 0; o < expectedOut16k; o++) {
            double srcPos = o * ratio;
            int idx0 = (int) Math.floor(srcPos);
            int idx1 = Math.min(totalInFrames - 1, idx0 + 1);
            float frac = (float) (srcPos - idx0);
            trueDac16k[o] = dacSource[idx0] + (dacSource[idx1] - dacSource[idx0]) * frac;
        }

        // Feed playback into SoftwareAecFilter using realistic uneven WRITE_NON_BLOCKING partial slices
        final int[] slicePattern = new int[]{137, 350, 532, 701, 219};
        int patternIdx = 0;
        int frameCursor = 0;
        int nextMicBlock = 0;
        long baseWallMs = 400_000L;
        double minTailErle = Double.MAX_VALUE;
        double minTailAtten = Double.MAX_VALUE;

        while (frameCursor < totalInFrames) {
            int sliceFrames = Math.min(slicePattern[patternIdx % slicePattern.length], totalInFrames - frameCursor);
            patternIdx++;
            int byteOff = frameCursor * 2 * channels;
            int byteLen = sliceFrames * 2 * channels;
            frameCursor += sliceFrames;
            long pushWallMs = baseWallMs + ((long) frameCursor * 1000L) / playbackRate;

            aec.pushPlaybackReference(
                    SoftwareAecFilter.CHANNEL_MEDIA, fullPcm, byteOff, byteLen,
                    playbackRate, channels, 1.0f, pushWallMs);

            // Process every 80ms capture block whose timeline timestamp has been reached
            while (nextMicBlock < totalMicBlocks
                    && ((long) (nextMicBlock + 1) * micBlockSamples) <= aec.getTotalRefSamplesWrittenForTest()) {
                int b = nextMicBlock++;
                long capWallMs = baseWallMs + (long) (b + 1) * 80L;
                byte[] micBlock = new byte[micBlockSamples * 2];
                for (int i = 0; i < micBlockSamples; i++) {
                    int srcIdx = b * micBlockSamples + i - delaySamples;
                    float echo = 0f;
                    if (srcIdx >= 48) {
                        echo = 0.45f * trueDac16k[srcIdx]
                                - 0.13f * trueDac16k[srcIdx - 17]
                                + 0.08f * trueDac16k[srcIdx - 48];
                    }
                    writeS16(micBlock, i * 2, echo);
                }
                byte[] cleaned = aec.processCaptureBlock(micBlock, capWallMs);
                // Verify sustained convergence over final 10 blocks (3.2s..4.0s, well after drift would destroy old AEC!)
                if (b >= totalMicBlocks - 10) {
                    double erle = aec.getLastErleDb();
                    double atten = NorthboundSession.computeRmsDbfs(micBlock)
                            - NorthboundSession.computeRmsDbfs(cleaned);
                    if (erle < minTailErle) minTailErle = erle;
                    if (atten < minTailAtten) minTailAtten = atten;
                }
            }
        }

        long totalWritten = aec.getTotalRefSamplesWrittenForTest();
        assertTrue(Math.abs(totalWritten - (long) expectedOut16k) <= 1L,
                playbackRate + "Hz partial-write zero-drift check failed: expected "
                        + expectedOut16k + "±1 samples, got " + totalWritten);
        assertTrue(nextMicBlock >= totalMicBlocks - 1,
                playbackRate + "Hz must process full 4s timeline, processed " + nextMicBlock + "/" + totalMicBlocks);
        assertTrue(minTailErle >= 10.0 && minTailAtten >= 10.0,
                playbackRate + "Hz uneven partial-write PBFDAF must sustain ERLE >= 10dB across final 800ms "
                        + "(minErle=" + minTailErle + "dB, minAtten=" + minTailAtten + "dB)");
    }

    /**
     * Simulates a real phone micro-speaker (400Hz HPF + 2nd-order all-pass phase dispersion +
     * 25ms multi-tap room reverb) at 1150ms delay (1000ms AudioTrack queue hint + 150ms HAL/air),
     * verifying hardware-guided envelope/waveform lock, >=8dB ERLE, truthful AecTelemetry fields,
     * and near-end double-talk preservation (Android #20).
     */
    private static void testPhaseDispersedPhoneSpeaker1150msWithHwHintAndTelemetry() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        final int rate = 16000;
        final int blockSamples = 1280; // 80ms
        final int hwQueueHintMs = 1000;
        final int delaySamples = 18400; // 1150ms (1000ms AudioTrack + 150ms HAL/air)
        final int convergeBlocks = 55; // 4.4s (echo starts at 1.15s -> 3.25s to lock & converge)
        final int doubleTalkBlocks = 6;
        final int totalBlocks = convergeBlocks + doubleTalkBlocks;
        final int totalSamples = totalBlocks * blockSamples;

        // Generate dynamic amplitude-modulated music-like broadband signal (beats + transients)
        float[] refSig = new float[totalSamples];
        Random rng = new Random(20261125L);
        float lp = 0f;
        for (int i = 0; i < totalSamples; i++) {
            double tSec = i / (double) rate;
            // 3.2Hz musical beat envelope + 6.7Hz phrase modulation
            double env = 0.25 + 0.75 * Math.pow(0.5 + 0.5 * Math.sin(2.0 * Math.PI * 3.2 * tSec), 2.0)
                    * (0.4 + 0.6 * Math.abs(Math.sin(2.0 * Math.PI * 1.1 * tSec)));
            float white = (rng.nextFloat() * 2f - 1f);
            lp = 0.55f * lp + 0.45f * white;
            refSig[i] = (float) (lp * env * 0.75);
        }

        // Apply realistic phone micro-speaker transfer function:
        // 1) 400Hz HPF (removes bass, shifts phase)
        // 2) Cascaded 1st-order all-pass filters (heavily disperses broadband phase)
        // 3) Multi-tap 25ms room impulse response
        float[] dispersed = new float[totalSamples];
        float hpPrevX = 0f, hpPrevY = 0f;
        float ap1X = 0f, ap1Y = 0f;
        float ap2X = 0f, ap2Y = 0f;
        float ap3X = 0f, ap3Y = 0f;
        final float apA = 0.72f;
        for (int i = 0; i < totalSamples; i++) {
            float x = refSig[i];
            float hp = 0.86f * (hpPrevY + x - hpPrevX);
            hpPrevX = x;
            hpPrevY = hp;
            // 3 cascaded all-pass stages scramble waveform phase while preserving amplitude envelope
            float y1 = -apA * hp + ap1X + apA * ap1Y;
            ap1X = hp; ap1Y = y1;
            float y2 = -apA * y1 + ap2X + apA * ap2Y;
            ap2X = y1; ap2Y = y2;
            float y3 = -apA * y2 + ap3X + apA * ap3Y;
            ap3X = y2; ap3Y = y3;
            dispersed[i] = y3;
        }

        int[] taps = new int[]{0, 17, 53, 128, 240, 380};
        float[] gains = new float[]{0.44f, -0.18f, 0.14f, -0.09f, 0.06f, -0.03f};
        long now = 600_000L;

        double convergedErle = 0.0;
        SoftwareAecFilter.AecTelemetry convergedSnap = null;
        double speechCorrSum = 0.0;
        double rawEchoPow = 0.0;
        double residualErrPow = 0.0;

        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                writeS16(refChunk, i * 2, refSig[b * blockSamples + i]);
            }
            long ts = now + b * 80L;
            aec.pushPlaybackReference(
                    SoftwareAecFilter.CHANNEL_MEDIA, refChunk, 0, refChunk.length,
                    rate, 1, 1.0f, ts, hwQueueHintMs);

            boolean inDoubleTalk = (b >= convergeBlocks);
            float[] nearEnd = new float[blockSamples];
            byte[] micBlock = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                int g = b * blockSamples + i;
                int base = g - delaySamples;
                float echo = 0f;
                for (int t = 0; t < taps.length; t++) {
                    int idx = base - taps[t];
                    if (idx >= 0) {
                        echo += gains[t] * dispersed[idx];
                    }
                }
                float voice = 0f;
                if (inDoubleTalk) {
                    double tSec = g / (double) rate;
                    voice = (float) (0.18 * Math.sin(2.0 * Math.PI * 520.0 * tSec)
                            + 0.12 * Math.sin(2.0 * Math.PI * 1240.0 * tSec));
                }
                nearEnd[i] = voice;
                writeS16(micBlock, i * 2, echo + voice);
                if (inDoubleTalk) {
                    rawEchoPow += (double) echo * echo;
                }
            }

            byte[] cleaned = aec.processCaptureBlock(micBlock, ts);
            if (b == convergeBlocks - 1) {
                convergedErle = aec.getLastErleDb();
                convergedSnap = aec.getTelemetrySnapshot();
            }
            if (inDoubleTalk) {
                double dot = 0.0, vPow = 1e-9, cPow = 1e-9;
                for (int i = 0; i < blockSamples; i++) {
                    float c = readS16(cleaned, i * 2);
                    float v = nearEnd[i];
                    dot += (double) c * v;
                    vPow += (double) v * v;
                    cPow += (double) c * c;
                    double diff = c - v;
                    residualErrPow += diff * diff;
                }
                speechCorrSum += dot / Math.sqrt(vPow * cPow);
            }
        }

        assertTrue(convergedSnap != null, "Expected converged telemetry snapshot");
        assertTrue(convergedSnap.delayLocked,
                "Phase-dispersed 1150ms phone speaker echo MUST lock via hw-guided envelope/waveform estimator: "
                        + convergedSnap.formatCompact());
        assertTrue(convergedSnap.hwQueueHintMs == hwQueueHintMs,
                "Telemetry must report live hwQueueHintMs=" + hwQueueHintMs + ", got " + convergedSnap.hwQueueHintMs);
        assertTrue(convergedSnap.delayMs >= 1100 && convergedSnap.delayMs <= 1180,
                "Estimated delay must lock near 1144ms, got " + convergedSnap.delayMs + "ms ("
                        + convergedSnap.formatCompact() + ")");
        assertTrue(convergedErle >= 8.0 && (convergedSnap.micRmsDbfs - convergedSnap.postAecRmsDbfs) >= 7.5,
                "Expected >= 8dB ERLE & >= 7.5dB post-AEC RMS drop on phase-dispersed 1150ms phone speaker ("
                        + convergedSnap.formatCompact() + ")");
        String compact = convergedSnap.formatCompact();
        assertTrue(compact.contains("erle=") && compact.contains("delay=") && compact.contains("hw_q=")
                && compact.contains("locked=1") && compact.contains("rms="),
                "Compact telemetry must contain all required diagnostic fields: " + compact);
        String suffix = convergedSnap.formatEchoHealthSuffix();
        assertTrue(suffix.startsWith("kws_aec=erle:") && suffix.contains(",locked:1,") && suffix.contains(",post:"),
                "Echo-health telemetry suffix malformed: " + suffix);

        double avgVoiceCorr = speechCorrSum / doubleTalkBlocks;
        double dtRejectionDb = 10.0 * Math.log10(rawEchoPow / Math.max(1e-9, residualErrPow));
        assertTrue(avgVoiceCorr >= 0.80 && dtRejectionDb >= 7.5,
                "Double-talk during 1150ms phase-dispersed music must preserve near-end voice (corr="
                        + avgVoiceCorr + ", rejection=" + dtRejectionDb + "dB)");
    }

    /**
     * Simulates "正在播放..." (100ms Voice TTS) overlapping 1000ms Music, then Voice ends while
     * Music continues: verifies notifyChannelStopped(CHANNEL_VOICE) + hwQueueHintMs triggers
     * immediate re-lock onto the 1100ms Music acoustic path.
     */
    private static void testVoiceToMediaChannelSwitchReLocksViaHwHint() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        final int rate = 16000;
        final int blockSamples = 1280;
        final int voiceBlocks = 25; // 2.0s of TTS at 140ms delay (enough to verify convergence DURING overlap!)
        final int mediaOnlyBlocks = 45; // 3.6s of music at 1100ms delay after TTS ends
        final int totalBlocks = voiceBlocks + mediaOnlyBlocks;
        final int totalSamples = totalBlocks * blockSamples;

        float[] voiceSig = new float[totalSamples];
        float[] mediaSig = new float[totalSamples];
        Random rng = new Random(2315L);
        float lpV = 0f, lpM = 0f;
        for (int i = 0; i < totalSamples; i++) {
            lpV = 0.62f * lpV + 0.38f * (rng.nextFloat() * 2f - 1f);
            lpM = 0.66f * lpM + 0.34f * (rng.nextFloat() * 2f - 1f);
            voiceSig[i] = lpV * 0.65f;
            mediaSig[i] = lpM * 0.70f;
        }

        long now = 900_000L;
        final int voiceDelay = 2240; // 140ms
        final int mediaDelay = 17600; // 1100ms (960ms track + 140ms HAL)
        double overlapVoiceErle = 0.0;
        boolean overlapVoiceLocked = false;
        double finalErle = 0.0;

        for (int b = 0; b < totalBlocks; b++) {
            long ts = now + b * 80L;
            byte[] mediaChunk = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                writeS16(mediaChunk, i * 2, mediaSig[b * blockSamples + i]);
            }
            float mediaGain = (b < voiceBlocks) ? 0.20f : 1.0f;
            aec.pushPlaybackReference(
                    SoftwareAecFilter.CHANNEL_MEDIA, mediaChunk, 0, mediaChunk.length,
                    rate, 1, mediaGain, ts, 960);

            if (b < voiceBlocks) {
                byte[] voiceChunk = new byte[blockSamples * 2];
                for (int i = 0; i < blockSamples; i++) {
                    writeS16(voiceChunk, i * 2, voiceSig[b * blockSamples + i]);
                }
                aec.pushPlaybackReference(
                        SoftwareAecFilter.CHANNEL_VOICE, voiceChunk, 0, voiceChunk.length,
                        rate, 1, 1.0f, ts, 80);
            } else if (b == voiceBlocks) {
                aec.notifyChannelStopped(SoftwareAecFilter.CHANNEL_VOICE);
            }

            byte[] micBlock = new byte[blockSamples * 2];
            for (int i = 0; i < blockSamples; i++) {
                int g = b * blockSamples + i;
                float echo = 0f;
                if (b < voiceBlocks) {
                    int vi = g - voiceDelay;
                    if (vi >= 0) echo += 0.45f * voiceSig[vi];
                } else {
                    int mi = g - mediaDelay;
                    if (mi >= 0) echo += 0.48f * mediaSig[mi];
                }
                writeS16(micBlock, i * 2, echo);
            }
            aec.processCaptureBlock(micBlock, ts);
            if (b == voiceBlocks - 1) {
                overlapVoiceErle = aec.getLastErleDb();
                overlapVoiceLocked = aec.isDelayLocked();
            }
            if (b == totalBlocks - 1) {
                finalErle = aec.getLastErleDb();
            }
        }
        assertTrue(overlapVoiceLocked && overlapVoiceErle >= 6.0,
                "During concurrent Voice TTS + ducked Media overlap, AEC must stay locked on Voice without thrashing (locked="
                        + overlapVoiceLocked + ", erle=" + overlapVoiceErle + "dB)");
        assertTrue(aec.isDelayLocked() && finalErle >= 8.0,
                "After Voice TTS ends over active Music, AEC must re-lock onto 1100ms Music path with ERLE >= 8dB ("
                        + aec.getTelemetrySnapshot().formatCompact() + ")");
        assertTrue(aec.getEstimatedDelayMs() >= 1060 && aec.getEstimatedDelayMs() <= 1130,
                "Expected post-TTS Music delay lock near 1096ms, got " + aec.getEstimatedDelayMs() + "ms");
    }

    private static void writeS16(byte[] buf, int byteOff, float sample) {
        int s = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32767.0f);
        buf[byteOff] = (byte) (s & 0xFF);
        buf[byteOff + 1] = (byte) ((s >> 8) & 0xFF);
    }

    private static float readS16(byte[] buf, int byteOff) {
        int lo = buf[byteOff] & 0xFF;
        int hi = buf[byteOff + 1];
        return (short) ((hi << 8) | lo) / 32768.0f;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("SoftwareAecFilterTest FAILED: " + msg);
        }
    }
}
