package homes.mymate.gateway.protocol;

import java.util.Locale;

/**
 * Real-Time 16kHz Partitioned Block Frequency-Domain Adaptive Filter (PBFDAF) Software AEC.
 *
 * v2.3.15 DSP upgrade:
 * - 8-partition overlap-save PBFDAF (FFT_SIZE=256, HOP_SIZE=128 / 8ms @ 16kHz, 1,024 taps = 64ms
 *   echo tail) using pure-Java radix-2 Cooley-Tukey FFT with zero external dependencies.
 * - 65,536-sample (~4.1s @ 16kHz) power-of-two ring buffer supporting up to 1800ms speaker-to-mic
 *   playback latency (covering 1000ms Android USAGE_MEDIA AudioTrack buffering + HAL/DSP latency).
 * - Hardware-guided two-domain delay estimator: uses live AudioTrack queued-buffer delay hints
 *   (writtenFrames - playbackHeadPosition) to focus search on [hint-100ms..hint+450ms] (with full
 *   0..1800ms fallback), combining 320ms rolling 1ms-envelope Pearson correlation (immune to phone
 *   speaker phase dispersion, HPF rolloff, and nonlinearities) with step-2 fine waveform refinement.
 * - Double-talk protection (block energy ratio + post-convergence hop DTD + bin-wise normalized
 *   step with gentle weight leakage and clamping) + non-negative ERLE safety guard.
 * - Live diagnostic telemetry snapshot ({@link AecTelemetry}: erle_db, delay_ms, hw_q_ms, locked,
 *   lock_mode, peak/env corr, mic_rms_dbfs, post_aec_rms_dbfs) for real-device observability.
 */
public final class SoftwareAecFilter {

    public static final int TARGET_SAMPLE_RATE = 16000;
    public static final int CHANNEL_VOICE = 0;
    public static final int CHANNEL_MEDIA = 1;
    public static final long REVERB_TAIL_HOLD_MS = 400L;
    private static final long ACTIVE_REF_STALE_CUTOFF_MS = 2000L; // covers 1000ms MEDIA_BUFFER_MS + HAL drain

    private static final int NUM_CHANNELS = 2;
    private static final int MAX_STREAM_LAG_REALIGN_SAMPLES = 4800; // 300ms @ 16kHz
    private static final long CHANNEL_IDLE_REANCHOR_MS = 300L;

    private static final int FFT_SIZE = 256;
    private static final int FFT_LOG2 = 8;
    private static final int HOP_SIZE = 128; // 8ms @ 16kHz
    private static final int HALF_BINS = (FFT_SIZE / 2) + 1; // 129 bins
    private static final int NUM_PARTITIONS = 8; // 8 * 128 = 1024 taps (64ms tail)
    private static final int FILTER_TAPS = NUM_PARTITIONS * HOP_SIZE;

    private static final int REF_RING_SIZE = 65536; // power of 2 (~4.1s @ 16kHz)
    private static final int REF_RING_MASK = REF_RING_SIZE - 1;
    private static final int MAX_DELAY_SAMPLES = 28800; // 1800ms @ 16kHz
    private static final int DELAY_ANCHOR_LEAD = 64; // 4ms inside 64ms filter window
    private static final int ENVELOPE_ANCHOR_LEAD = 96; // 6ms causal lead for envelope-locked phase learning

    private static final int ENV_HOP_SAMPLES = 16; // 1ms @ 16kHz
    private static final int ENV_WINDOW_MAX_BINS = 320; // 320ms rolling envelope history (4 x 80ms blocks)
    private static final double WAVEFORM_LOCK_CORR = 0.16; // restored from proven v2.3.9 phone-speaker baseline
    private static final double ENVELOPE_LOCK_CORR = 0.45;
    private static final int HW_HINT_PRE_MARGIN_SAMPLES = 1600; // -100ms
    private static final int HW_HINT_POST_MARGIN_SAMPLES = 7200; // +450ms (HAL + DSP + air path)

    private static final float PBFDAF_STEP = 0.65f;
    private static final float DTD_SLOW_STEP = 0.03f;
    private static final float BIN_POWER_SMOOTH = 0.72f;
    private static final float BIN_POWER_EPS = 2e-3f;
    private static final float WEIGHT_LEAK = 0.9998f;
    private static final float MAX_WEIGHT_MAG = 12.0f;

    /** Immutable snapshot of live AEC convergence & delay-lock telemetry for diagnostics. */
    public static final class AecTelemetry {
        public final double erleDb;
        public final int delayMs;
        public final int hwQueueHintMs;
        public final boolean delayLocked;
        public final String lockMode;
        public final double peakCorr;
        public final double envCorr;
        public final double micRmsDbfs;
        public final double postAecRmsDbfs;

        public AecTelemetry(
                double erleDb,
                int delayMs,
                int hwQueueHintMs,
                boolean delayLocked,
                String lockMode,
                double peakCorr,
                double envCorr,
                double micRmsDbfs,
                double postAecRmsDbfs) {
            this.erleDb = erleDb;
            this.delayMs = delayMs;
            this.hwQueueHintMs = hwQueueHintMs;
            this.delayLocked = delayLocked;
            this.lockMode = lockMode != null ? lockMode : "unlocked";
            this.peakCorr = peakCorr;
            this.envCorr = envCorr;
            this.micRmsDbfs = micRmsDbfs;
            this.postAecRmsDbfs = postAecRmsDbfs;
        }

        public String formatCompact() {
            return String.format(Locale.US,
                    "erle=%.1fdB delay=%dms hw_q=%dms locked=%s(%s) corr=%.2f/%.2f rms=%.1f->%.1fdBFS",
                    erleDb, delayMs, hwQueueHintMs, delayLocked ? "1" : "0", lockMode,
                    peakCorr, envCorr, micRmsDbfs, postAecRmsDbfs);
        }

        public String formatEchoHealthSuffix() {
            return String.format(Locale.US,
                    "kws_aec=erle:%.1fdB,delay:%dms,hw_q:%dms,locked:%d,mode:%s,corr:%.2f/%.2f,mic:%.1f,post:%.1f",
                    erleDb, delayMs, hwQueueHintMs, delayLocked ? 1 : 0, lockMode,
                    peakCorr, envCorr, micRmsDbfs, postAecRmsDbfs);
        }
    }

    // Precomputed static radix-2 FFT tables (zero trigonometry in hot audio path)
    private static final int[] BIT_REV = new int[FFT_SIZE];
    private static final float[] TWIDDLE_COS = new float[FFT_SIZE / 2];
    private static final float[] TWIDDLE_SIN = new float[FFT_SIZE / 2];

    static {
        for (int i = 0; i < FFT_SIZE; i++) {
            int rev = 0;
            int x = i;
            for (int b = 0; b < FFT_LOG2; b++) {
                rev = (rev << 1) | (x & 1);
                x >>= 1;
            }
            BIT_REV[i] = rev;
        }
        for (int k = 0; k < FFT_SIZE / 2; k++) {
            double angle = -2.0 * Math.PI * k / FFT_SIZE;
            TWIDDLE_COS[k] = (float) Math.cos(angle);
            TWIDDLE_SIN[k] = (float) Math.sin(angle);
        }
    }

    private final Object ringLock = new Object();
    private final float[] refRing = new float[REF_RING_SIZE];
    private final float[][] chanRings = new float[NUM_CHANNELS][REF_RING_SIZE];
    private final long[] chanWriteCursor = new long[NUM_CHANNELS];
    private final long[] chanLastWallMs = new long[NUM_CHANNELS];
    private final boolean[] chanActive = new boolean[NUM_CHANNELS];
    private final int[] chanQueueHintMs = new int[]{-1, -1};
    // Per-channel exact-rational zero-drift resampler state (guarded by ringLock):
    // fractional input position = chanResamplePhaseNum[ch] / (double) TARGET_SAMPLE_RATE
    private final long[] chanResamplePhaseNum = new long[NUM_CHANNELS];
    private final float[] chanPrevSample = new float[NUM_CHANNELS];
    private final float[] chanPrev2Sample = new float[NUM_CHANNELS];
    private final float[] chanPrev3Sample = new float[NUM_CHANNELS];
    private final boolean[] chanHasPrevSample = new boolean[NUM_CHANNELS];
    private final int[] chanResampleRate = new int[NUM_CHANNELS];
    private long globalHeadSample = 0L;
    private long timelineEndWallMs = 0L;
    private long tailPadWallMs = 0L;
    private int refWriteIdx = 0;
    private long totalRefSamplesWritten = 0L;
    private volatile long lastRefWallMs = 0L;
    private volatile boolean pendingIdleReset = false;
    private volatile boolean pendingDelayRescan = false;

    // PBFDAF state (accessed solely on capture thread)
    private final float[][] wRe = new float[NUM_PARTITIONS][HALF_BINS];
    private final float[][] wIm = new float[NUM_PARTITIONS][HALF_BINS];
    private final float[][] fdlRe = new float[NUM_PARTITIONS][HALF_BINS];
    private final float[][] fdlIm = new float[NUM_PARTITIONS][HALF_BINS];
    private final float[] smoothBinPower = new float[HALF_BINS];
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] errSpecRe = new float[HALF_BINS];
    private final float[] errSpecIm = new float[HALF_BINS];
    private float[] refSnapBuf = new float[36864];
    private float[] micBuf = new float[1280];
    private float[] outErrBuf = new float[1280];
    private final float[] micEnvHistory = new float[ENV_WINDOW_MAX_BINS];
    private int micEnvCount = 0;
    private float[] refEnvBuf = new float[2400];

    private volatile boolean pendingWeightReset = false;
    private volatile boolean enabled = true;
    private volatile int estimatedDelaySamples = 640; // ~40ms initial guess until locked
    private volatile double lastErleDb = 0.0;
    private volatile boolean delayLocked = false;
    private volatile int lastHwQueueHintMs = -1;
    private volatile double lastPeakCorr = 0.0;
    private volatile double lastEnvCorr = 0.0;
    private volatile double lastMicRmsDbfs = -96.0;
    private volatile double lastPostAecRmsDbfs = -96.0;
    private volatile String lockMode = "unlocked";
    private long refPlayEndSample = 0L;
    private int delayEstimateCounter = 0;
    private int consecutiveZeroErleBlocks = 0;
    private int convergedBlocks = 0;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getLastErleDb() {
        return lastErleDb;
    }

    public int getEstimatedDelayMs() {
        return (estimatedDelaySamples * 1000) / TARGET_SAMPLE_RATE;
    }

    public boolean isDelayLocked() {
        return delayLocked;
    }

    public int getLastHwQueueHintMs() {
        return lastHwQueueHintMs;
    }

    public double getLastPeakCorr() {
        return lastPeakCorr;
    }

    public double getLastEnvCorr() {
        return lastEnvCorr;
    }

    public double getLastMicRmsDbfs() {
        return lastMicRmsDbfs;
    }

    public double getLastPostAecRmsDbfs() {
        return lastPostAecRmsDbfs;
    }

    public String getLockMode() {
        return lockMode;
    }

    public AecTelemetry getTelemetrySnapshot() {
        return new AecTelemetry(
                lastErleDb,
                getEstimatedDelayMs(),
                lastHwQueueHintMs,
                delayLocked,
                lockMode,
                lastPeakCorr,
                lastEnvCorr,
                lastMicRmsDbfs,
                lastPostAecRmsDbfs);
    }

    public long getTotalRefSamplesWrittenForTest() {
        synchronized (ringLock) {
            return totalRefSamplesWritten;
        }
    }

    public boolean isInReverbTailForTest() {
        return pendingIdleReset && lastRefWallMs > 0L;
    }

    private void resetChannelResamplerLocked(int chIdx) {
        chanResamplePhaseNum[chIdx] = 0L;
        chanPrevSample[chIdx] = 0f;
        chanPrev2Sample[chIdx] = 0f;
        chanPrev3Sample[chIdx] = 0f;
        chanHasPrevSample[chIdx] = false;
        chanResampleRate[chIdx] = 0;
    }

    private void clearRingsLocked() {
        for (int i = 0; i < REF_RING_SIZE; i++) {
            refRing[i] = 0f;
            chanRings[0][i] = 0f;
            chanRings[1][i] = 0f;
        }
        chanWriteCursor[0] = 0L;
        chanWriteCursor[1] = 0L;
        chanLastWallMs[0] = 0L;
        chanLastWallMs[1] = 0L;
        chanActive[0] = false;
        chanActive[1] = false;
        chanQueueHintMs[0] = -1;
        chanQueueHintMs[1] = -1;
        resetChannelResamplerLocked(0);
        resetChannelResamplerLocked(1);
        globalHeadSample = 0L;
        timelineEndWallMs = 0L;
        tailPadWallMs = 0L;
        refWriteIdx = 0;
        totalRefSamplesWritten = 0L;
        lastRefWallMs = 0L;
        pendingIdleReset = false;
        pendingDelayRescan = false;
    }

    public void reset() {
        synchronized (ringLock) {
            clearRingsLocked();
        }
        pendingWeightReset = true;
        lastErleDb = 0.0;
        delayLocked = false;
        lastHwQueueHintMs = -1;
        lastPeakCorr = 0.0;
        lastEnvCorr = 0.0;
        lockMode = "unlocked";
    }

    /**
     * Marks a single playback channel ({@link #CHANNEL_VOICE} or {@link #CHANNEL_MEDIA}) inactive
     * without disturbing the other active channel or killing the post-playback acoustic reverb tail.
     */
    public void notifyChannelStopped(int channelId) {
        int chIdx = (channelId == CHANNEL_MEDIA) ? CHANNEL_MEDIA : CHANNEL_VOICE;
        int otherIdx = 1 - chIdx;
        synchronized (ringLock) {
            chanActive[chIdx] = false;
            chanQueueHintMs[chIdx] = -1;
            resetChannelResamplerLocked(chIdx);
            if (chanActive[otherIdx]) {
                // E.g. short 100ms Voice TTS finished while 1000ms Media continues:
                // force immediate re-lock onto the surviving channel's acoustic delay.
                pendingDelayRescan = true;
            }
        }
    }

    /**
     * Marks all playback streams ended while preserving {@code refRing}, filter weights, and
     * {@code lastRefWallMs} for the {@link #REVERB_TAIL_HOLD_MS} (400ms) acoustic reverb tail
     * before resetting lazily once the tail elapses (P2-1).
     */
    public void notifyPlaybackStopped() {
        synchronized (ringLock) {
            chanActive[CHANNEL_VOICE] = false;
            chanActive[CHANNEL_MEDIA] = false;
            chanQueueHintMs[CHANNEL_VOICE] = -1;
            chanQueueHintMs[CHANNEL_MEDIA] = -1;
            resetChannelResamplerLocked(CHANNEL_VOICE);
            resetChannelResamplerLocked(CHANNEL_MEDIA);
            if (lastRefWallMs > 0L) {
                pendingIdleReset = true;
            }
        }
    }

    /**
     * Feed downstream playback PCM (music or TTS) of any sample rate and channel count (1 or 2).
     * Decodes PCM outside the lock and runs a zero-drift fractional-phase resampler into the ring.
     */
    public void pushPlaybackReference(byte[] pcm16le, int sampleRate, int channels, long nowMs) {
        if (pcm16le == null) {
            return;
        }
        pushPlaybackReference(CHANNEL_VOICE, pcm16le, 0, pcm16le.length, sampleRate, channels, 1.0f, nowMs, -1);
    }

    /**
     * Zero-copy slice overload for feeding the exact byte range written to {@code AudioTrack}.
     */
    public void pushPlaybackReference(
            byte[] pcm16le, int offset, int length, int sampleRate, int channels, long nowMs) {
        pushPlaybackReference(CHANNEL_VOICE, pcm16le, offset, length, sampleRate, channels, 1.0f, nowMs, -1);
    }

    /**
     * Time-aligned dual-channel ({@link #CHANNEL_VOICE} + {@link #CHANNEL_MEDIA}) playback
     * reference mixer (P1-3) with zero-drift exact-rational fractional-phase resampling (v2.3.14).
     */
    public void pushPlaybackReference(
            int channelId,
            byte[] pcm16le,
            int offset,
            int length,
            int sampleRate,
            int channels,
            float linearGain,
            long nowMs) {
        pushPlaybackReference(channelId, pcm16le, offset, length, sampleRate, channels, linearGain, nowMs, -1);
    }

    /**
     * Dual-channel reference mixer accepting the live hardware {@code AudioTrack} queued-buffer
     * delay hint ({@code queuedTrackMs >= 0} from {@code writtenFrames - playbackHeadPosition})
     * so the delay estimator focuses directly around the real DAC queue depth (v2.3.15).
     */
    public void pushPlaybackReference(
            int channelId,
            byte[] pcm16le,
            int offset,
            int length,
            int sampleRate,
            int channels,
            float linearGain,
            long nowMs,
            int queuedTrackMs) {
        if (!enabled || pcm16le == null || sampleRate <= 0 || offset < 0 || length < 2) {
            return;
        }
        int avail = Math.min(length, pcm16le.length - offset);
        if (avail < 2) {
            return;
        }
        int ch = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * ch;
        int inFrames = avail / frameBytes;
        if (inFrames <= 0) {
            return;
        }

        final float gain = Math.max(0.0f, Math.min(2.0f, linearGain));
        final float[] inMono = new float[inFrames];
        if (ch == 1) {
            for (int f = 0; f < inFrames; f++) {
                int p = offset + (f << 1);
                int lo = pcm16le[p] & 0xFF;
                int hi = pcm16le[p + 1];
                inMono[f] = ((short) ((hi << 8) | lo)) / 32768.0f;
            }
        } else {
            for (int f = 0; f < inFrames; f++) {
                int p = offset + (f << 2);
                int lo0 = pcm16le[p] & 0xFF;
                int hi0 = pcm16le[p + 1];
                float s0 = ((short) ((hi0 << 8) | lo0)) / 32768.0f;
                int lo1 = pcm16le[p + 2] & 0xFF;
                int hi1 = pcm16le[p + 3];
                float s1 = ((short) ((hi1 << 8) | lo1)) / 32768.0f;
                inMono[f] = 0.5f * (s0 + s1);
            }
        }

        final int chIdx = (channelId == CHANNEL_MEDIA) ? CHANNEL_MEDIA : CHANNEL_VOICE;
        final int otherIdx = 1 - chIdx;
        final double ratio = (double) sampleRate / (double) TARGET_SAMPLE_RATE;

        synchronized (ringLock) {
            if (pendingIdleReset && lastRefWallMs > 0L && (nowMs - lastRefWallMs) > REVERB_TAIL_HOLD_MS) {
                clearRingsLocked();
                pendingWeightReset = true;
            }
            pendingIdleReset = false;

            boolean continuing = chanActive[chIdx]
                    && chanLastWallMs[chIdx] > 0L
                    && (nowMs - chanLastWallMs[chIdx]) <= CHANNEL_IDLE_REANCHOR_MS;
            boolean otherLive = chanActive[otherIdx]
                    && timelineEndWallMs > 0L
                    && (nowMs - chanLastWallMs[otherIdx]) <= CHANNEL_IDLE_REANCHOR_MS;

            if (!continuing || chanResampleRate[chIdx] != sampleRate) {
                resetChannelResamplerLocked(chIdx);
                chanResampleRate[chIdx] = sampleRate;
            }

            final float prev1 = chanHasPrevSample[chIdx] ? chanPrevSample[chIdx] : inMono[0];
            final float prev2 = chanHasPrevSample[chIdx] ? chanPrev2Sample[chIdx] : prev1;
            final float prev3 = chanHasPrevSample[chIdx] ? chanPrev3Sample[chIdx] : prev2;

            final int outSamples;
            final long startPhaseNum;
            if (sampleRate == TARGET_SAMPLE_RATE) {
                startPhaseNum = 0L;
                chanResamplePhaseNum[chIdx] = 0L;
                outSamples = inFrames;
            } else {
                startPhaseNum = chanResamplePhaseNum[chIdx];
                long limitNum = (long) inFrames * (long) TARGET_SAMPLE_RATE;
                if (startPhaseNum >= limitNum) {
                    outSamples = 0;
                    chanResamplePhaseNum[chIdx] = startPhaseNum - limitNum;
                } else {
                    outSamples = (int) (((limitNum - 1L - startPhaseNum) / (long) sampleRate) + 1L);
                    chanResamplePhaseNum[chIdx] = startPhaseNum + (long) outSamples * (long) sampleRate - limitNum;
                }
            }

            // Advance 3-sample cross-boundary history unconditionally (even if outSamples == 0)
            if (inFrames >= 3) {
                chanPrev3Sample[chIdx] = inMono[inFrames - 3];
                chanPrev2Sample[chIdx] = inMono[inFrames - 2];
                chanPrevSample[chIdx] = inMono[inFrames - 1];
            } else if (inFrames == 2) {
                chanPrev3Sample[chIdx] = prev1;
                chanPrev2Sample[chIdx] = inMono[0];
                chanPrevSample[chIdx] = inMono[1];
            } else {
                chanPrev3Sample[chIdx] = prev2;
                chanPrev2Sample[chIdx] = prev1;
                chanPrevSample[chIdx] = inMono[0];
            }
            chanHasPrevSample[chIdx] = true;

            if (outSamples <= 0) {
                chanLastWallMs[chIdx] = nowMs;
                chanActive[chIdx] = true;
                if (queuedTrackMs >= 0) {
                    chanQueueHintMs[chIdx] = queuedTrackMs;
                }
                lastRefWallMs = nowMs;
                return;
            }

            long startSample;
            if (continuing) {
                startSample = chanWriteCursor[chIdx];
                if (otherLive && (globalHeadSample - startSample) > MAX_STREAM_LAG_REALIGN_SAMPLES) {
                    long deltaSamples = ((nowMs - timelineEndWallMs) * (long) TARGET_SAMPLE_RATE) / 1000L;
                    startSample = globalHeadSample + deltaSamples - outSamples;
                    long minAllowed = Math.max(0L, globalHeadSample - MAX_STREAM_LAG_REALIGN_SAMPLES);
                    long maxAllowed = globalHeadSample + MAX_STREAM_LAG_REALIGN_SAMPLES;
                    if (startSample < minAllowed) startSample = minAllowed;
                    if (startSample > maxAllowed) startSample = maxAllowed;
                }
            } else if (otherLive) {
                // Concurrent second stream starting while first stream is playing:
                // align onto the shared 16kHz timeline via wall-clock end timestamp.
                long deltaSamples = ((nowMs - timelineEndWallMs) * (long) TARGET_SAMPLE_RATE) / 1000L;
                startSample = globalHeadSample + deltaSamples - outSamples;
                long minAllowed = Math.max(0L, globalHeadSample - MAX_STREAM_LAG_REALIGN_SAMPLES);
                long maxAllowed = globalHeadSample + MAX_STREAM_LAG_REALIGN_SAMPLES;
                if (startSample < minAllowed) startSample = minAllowed;
                if (startSample > maxAllowed) startSample = maxAllowed;
            } else {
                startSample = globalHeadSample;
            }
            if (startSample < 0L) {
                startSample = 0L;
            }

            long endSample = startSample + outSamples;
            if (startSample > globalHeadSample) {
                long gap = Math.min(startSample - globalHeadSample, (long) REF_RING_SIZE);
                long clearStart = startSample - gap;
                for (long pos = clearStart; pos < startSample; pos++) {
                    int idx = (int) (pos & REF_RING_MASK);
                    chanRings[0][idx] = 0f;
                    chanRings[1][idx] = 0f;
                    refRing[idx] = 0f;
                }
            }
            if (endSample > globalHeadSample) {
                long clearFrom = Math.max(startSample, globalHeadSample);
                for (long pos = clearFrom; pos < endSample; pos++) {
                    chanRings[otherIdx][(int) (pos & REF_RING_MASK)] = 0f;
                }
            }

            float[] myRing = chanRings[chIdx];
            float[] otherRing = chanRings[otherIdx];
            if (sampleRate == TARGET_SAMPLE_RATE) {
                for (int o = 0; o < outSamples; o++) {
                    int idx = (int) ((startSample + o) & REF_RING_MASK);
                    float mine = inMono[o] * gain;
                    myRing[idx] = mine;
                    float mixed = mine + otherRing[idx];
                    if (mixed > 1.0f) mixed = 1.0f;
                    else if (mixed < -1.0f) mixed = -1.0f;
                    refRing[idx] = mixed;
                }
            } else if (ratio > 1.8) {
                // High-ratio decimation (44.1 / 48 / 96 kHz -> 16 kHz): seamless cross-boundary
                // 3-tap [0.25, 0.5, 0.25] anti-aliasing filter + exact fractional linear interp.
                long phaseNum = startPhaseNum;
                final float invTargetRate = 1.0f / (float) TARGET_SAMPLE_RATE;
                for (int o = 0; o < outSamples; o++) {
                    int whole = (int) (phaseNum / TARGET_SAMPLE_RATE);
                    float frac = (float) (phaseNum - (long) whole * TARGET_SAMPLE_RATE) * invTargetRate;
                    // Evaluate at causal index whole - 1 so both endpoints [whole-1, whole] and
                    // their 3-tap [k-2, k-1, k] neighborhoods are 100% in-bounds across chunks.
                    int i0 = whole - 1;
                    int i1 = whole;
                    float a0 = sampleWithHistory(inMono, i0 - 2, prev3, prev2, prev1);
                    float a1 = sampleWithHistory(inMono, i0 - 1, prev3, prev2, prev1);
                    float a2 = sampleWithHistory(inMono, i0, prev3, prev2, prev1);
                    float a3 = sampleWithHistory(inMono, i1, prev3, prev2, prev1);
                    float s0 = 0.25f * a0 + 0.50f * a1 + 0.25f * a2;
                    float s1 = 0.25f * a1 + 0.50f * a2 + 0.25f * a3;
                    float mine = (s0 + (s1 - s0) * frac) * gain;

                    int idx = (int) ((startSample + o) & REF_RING_MASK);
                    myRing[idx] = mine;
                    float mixed = mine + otherRing[idx];
                    if (mixed > 1.0f) mixed = 1.0f;
                    else if (mixed < -1.0f) mixed = -1.0f;
                    refRing[idx] = mixed;
                    phaseNum += sampleRate;
                }
            } else {
                // Moderate/low ratio (22.05 / 24 / 8 / 12 kHz -> 16 kHz): seamless cross-boundary
                // exact fractional-phase linear interpolation with zero integer-boxcar jitter.
                long phaseNum = startPhaseNum;
                final float invTargetRate = 1.0f / (float) TARGET_SAMPLE_RATE;
                for (int o = 0; o < outSamples; o++) {
                    int whole = (int) (phaseNum / TARGET_SAMPLE_RATE);
                    float frac = (float) (phaseNum - (long) whole * TARGET_SAMPLE_RATE) * invTargetRate;
                    float s0 = (whole == 0) ? prev1 : inMono[whole - 1];
                    float s1 = inMono[whole];
                    float mine = (s0 + (s1 - s0) * frac) * gain;

                    int idx = (int) ((startSample + o) & REF_RING_MASK);
                    myRing[idx] = mine;
                    float mixed = mine + otherRing[idx];
                    if (mixed > 1.0f) mixed = 1.0f;
                    else if (mixed < -1.0f) mixed = -1.0f;
                    refRing[idx] = mixed;
                    phaseNum += sampleRate;
                }
            }

            chanWriteCursor[chIdx] = endSample;
            chanLastWallMs[chIdx] = nowMs;
            chanActive[chIdx] = true;
            if (queuedTrackMs >= 0) {
                chanQueueHintMs[chIdx] = queuedTrackMs;
            }
            if (endSample >= globalHeadSample) {
                globalHeadSample = endSample;
                refWriteIdx = (int) (globalHeadSample & REF_RING_MASK);
                totalRefSamplesWritten = globalHeadSample;
                timelineEndWallMs = nowMs;
                tailPadWallMs = nowMs;
            }
            lastRefWallMs = nowMs;
        }
    }

    private static float sampleWithHistory(
            float[] inMono, int idx, float prev3, float prev2, float prev1) {
        if (idx >= 0) {
            return inMono[idx];
        }
        if (idx == -1) {
            return prev1;
        }
        if (idx == -2) {
            return prev2;
        }
        return prev3;
    }

    /**
     * Cancel speaker playback echo from a 16kHz mono S16LE capture block.
     * Snapshots the reference tail under a brief lock, then executes hardware-guided two-domain
     * delay tracking and 8-partition PBFDAF completely lock-free.
     */
    public byte[] processCaptureBlock(byte[] micPcm16le, long nowMs) {
        if (micPcm16le == null || micPcm16le.length < 2) {
            return micPcm16le;
        }
        int n = micPcm16le.length / 2;
        if (micBuf.length < n) {
            micBuf = new float[n];
            outErrBuf = new float[n];
        }
        float[] mic = micBuf;
        float[] outErr = outErrBuf;
        double micPower = 0.0;
        for (int i = 0; i < n; i++) {
            int lo = micPcm16le[i * 2] & 0xFF;
            int hi = micPcm16le[i * 2 + 1];
            float s = (short) ((hi << 8) | lo) / 32768.0f;
            mic[i] = s;
            micPower += (double) s * s;
        }
        double micRmsDb = powerToDbfs(micPower, n);
        lastMicRmsDbfs = micRmsDb;

        if (pendingWeightReset) {
            pendingWeightReset = false;
            clearFilterWeights();
            estimatedDelaySamples = 640;
            delayLocked = false;
            refPlayEndSample = 0L;
            delayEstimateCounter = 0;
            consecutiveZeroErleBlocks = 0;
            convergedBlocks = 0;
            micEnvCount = 0;
            lastPeakCorr = 0.0;
            lastEnvCorr = 0.0;
            lockMode = "unlocked";
        }
        if (!enabled || lastRefWallMs == 0L || (nowMs - lastRefWallMs) > ACTIVE_REF_STALE_CUTOFF_MS) {
            if (pendingIdleReset && lastRefWallMs > 0L) {
                synchronized (ringLock) {
                    if (pendingIdleReset && lastRefWallMs > 0L && (nowMs - lastRefWallMs) > REVERB_TAIL_HOLD_MS) {
                        clearRingsLocked();
                    }
                }
                clearFilterWeights();
                estimatedDelaySamples = 640;
                delayLocked = false;
                refPlayEndSample = 0L;
                delayEstimateCounter = 0;
                consecutiveZeroErleBlocks = 0;
                convergedBlocks = 0;
                micEnvCount = 0;
                lastHwQueueHintMs = -1;
                lastPeakCorr = 0.0;
                lastEnvCorr = 0.0;
                lockMode = "unlocked";
            }
            lastErleDb = 0.0;
            lastPostAecRmsDbfs = micRmsDb;
            return micPcm16le;
        }

        boolean inReverbTail = false;
        int snapshotLen = ENV_WINDOW_MAX_BINS * ENV_HOP_SAMPLES + MAX_DELAY_SAMPLES + FILTER_TAPS + FFT_SIZE + 64;
        if (snapshotLen > REF_RING_SIZE) {
            snapshotLen = REF_RING_SIZE;
        }
        if (refSnapBuf.length < snapshotLen) {
            refSnapBuf = new float[snapshotLen];
        }
        float[] refSnap = refSnapBuf;
        long refWritten;
        int activeHwHintMs;
        int voiceHwHintMs;
        int mediaHwHintMs;
        boolean forceRescan;
        synchronized (ringLock) {
            if (pendingIdleReset && lastRefWallMs > 0L) {
                if ((nowMs - lastRefWallMs) > REVERB_TAIL_HOLD_MS) {
                    clearRingsLocked();
                    pendingWeightReset = true;
                    lastErleDb = 0.0;
                    lastPostAecRmsDbfs = micRmsDb;
                    lockMode = "unlocked";
                    return micPcm16le;
                }
                inReverbTail = true;
                long padMs = nowMs - tailPadWallMs;
                if (padMs > 0L) {
                    int padSamples = (int) Math.min((long) (n * 2), (padMs * (long) TARGET_SAMPLE_RATE) / 1000L);
                    for (int i = 0; i < padSamples; i++) {
                        int idx = (int) ((globalHeadSample + i) & REF_RING_MASK);
                        chanRings[0][idx] = 0f;
                        chanRings[1][idx] = 0f;
                        refRing[idx] = 0f;
                    }
                    globalHeadSample += padSamples;
                    refWriteIdx = (int) (globalHeadSample & REF_RING_MASK);
                    totalRefSamplesWritten = globalHeadSample;
                    tailPadWallMs = nowMs;
                }
            }
            refWritten = totalRefSamplesWritten;
            // Decay each active channel's AudioTrack queue-depth hint by wall time elapsed since
            // its last write so active-stream push gaps (320..800ms) track real DAC drain cleanly.
            mediaHwHintMs = (chanActive[CHANNEL_MEDIA] && chanQueueHintMs[CHANNEL_MEDIA] >= 0)
                    ? Math.max(0, chanQueueHintMs[CHANNEL_MEDIA] - (int) Math.max(0L, nowMs - chanLastWallMs[CHANNEL_MEDIA]))
                    : -1;
            voiceHwHintMs = (chanActive[CHANNEL_VOICE] && chanQueueHintMs[CHANNEL_VOICE] >= 0)
                    ? Math.max(0, chanQueueHintMs[CHANNEL_VOICE] - (int) Math.max(0L, nowMs - chanLastWallMs[CHANNEL_VOICE]))
                    : -1;
            // When Voice TTS (gain=1.0) overlaps ducked Media (gain=0.20), Voice dominates acoustic
            // echo so prefer Voice's hint for focused search; once Voice stops, notifyChannelStopped
            // sets pendingDelayRescan=true and Media's hint takes over immediately.
            if (voiceHwHintMs >= 0) {
                activeHwHintMs = voiceHwHintMs;
            } else {
                activeHwHintMs = mediaHwHintMs;
            }
            forceRescan = pendingDelayRescan;
            pendingDelayRescan = false;
            if (refWritten < n) {
                lastErleDb = 0.0;
                lastPostAecRmsDbfs = micRmsDb;
                lastHwQueueHintMs = activeHwHintMs;
                return micPcm16le;
            }
            int start = (refWriteIdx - snapshotLen) & REF_RING_MASK;
            int firstChunk = Math.min(snapshotLen, REF_RING_SIZE - start);
            System.arraycopy(refRing, start, refSnap, 0, firstChunk);
            int rem = snapshotLen - firstChunk;
            if (rem > 0) {
                System.arraycopy(refRing, 0, refSnap, firstChunk, rem);
            }
        }
        lastHwQueueHintMs = activeHwHintMs;

        // Append current block's 1ms amplitude envelope bins to the 320ms rolling history
        appendMicEnvelopeBins(mic, n);

        // Advance hardware-clock playback read cursor by exact capture block length n.
        // Because refRing is sample-exact at 16kHz (zero-drift resampler), tracking the absolute
        // sample position refPlayEndSample eliminates both uneven WRITE_NON_BLOCKING partial-write
        // jitter AND active-stream push-gap drain drift with zero wall-clock quantization noise.
        if (delayLocked) {
            refPlayEndSample += n;
        }

        // Check whether tracked cursor drifted outside physically possible bounds for ANY live
        // active channel's decayed AudioTrack queue depth (validates either Voice or Media during
        // overlap so Voice+Media coexistence never falsely evicts a converged lock).
        boolean cursorOutOfRange = delayLocked
                && ((refWritten - refPlayEndSample) > (MAX_DELAY_SAMPLES + 256L)
                    || (refPlayEndSample > refWritten + FILTER_TAPS));
        if (delayLocked && (voiceHwHintMs >= 0 || mediaHwHintMs >= 0) && !inReverbTail) {
            long trackedLagMs = ((refWritten - refPlayEndSample) * 1000L) / TARGET_SAMPLE_RATE;
            boolean matchesVoice = (voiceHwHintMs >= 0)
                    && trackedLagMs >= (voiceHwHintMs - 140L)
                    && trackedLagMs <= (voiceHwHintMs + 520L);
            boolean matchesMedia = (mediaHwHintMs >= 0)
                    && trackedLagMs >= (mediaHwHintMs - 140L)
                    && trackedLagMs <= (mediaHwHintMs + 520L);
            if (!matchesVoice && !matchesMedia) {
                forceRescan = true;
            }
        }
        if (forceRescan && !inReverbTail) {
            delayLocked = false;
            lockMode = "unlocked";
        }

        // Run hardware-guided two-domain delay estimation immediately when unlocked, every 6 blocks (~480ms),
        // or immediately whenever ERLE drops to 0 for 3 consecutive blocks.
        // Freeze delay re-scan during post-playback reverb tail so converged echo cancellation stays locked.
        boolean needDelayScan = !inReverbTail
                && (!delayLocked
                    || cursorOutOfRange
                    || (delayEstimateCounter % 6 == 0)
                    || (consecutiveZeroErleBlocks >= 3));
        delayEstimateCounter++;
        if (needDelayScan) {
            estimateDelayTwoStage(mic, n, refSnap, snapshotLen, refWritten, activeHwHintMs);
        }
        if (inReverbTail && delayLocked) {
            lockMode = "reverb_tail";
        }

        int baseDelay;
        if (delayLocked) {
            long lagSamples = refWritten - refPlayEndSample;
            baseDelay = (int) Math.max((long) -(FILTER_TAPS + FFT_SIZE), Math.min((long) MAX_DELAY_SAMPLES, lagSamples));
            estimatedDelaySamples = Math.max(0, Math.min(MAX_DELAY_SAMPLES, baseDelay));
        } else {
            baseDelay = estimatedDelaySamples;
        }
        int latestIdx = snapshotLen - 1;

        // Measure reference energy around the expected direct-path window
        int directDelay = baseDelay + DELAY_ANCHOR_LEAD;
        double refWindowEnergy = 0.0;
        for (int i = 0; i < n; i++) {
            int rIdx = latestIdx - (n - 1 - i) - directDelay;
            if (rIdx >= 0 && rIdx < snapshotLen) {
                float r = refSnap[rIdx];
                refWindowEnergy += (double) r * r;
            }
        }
        // Also check baseDelay in case direct path sits near lag 0
        if (refWindowEnergy <= 1e-5) {
            for (int i = 0; i < n; i++) {
                int rIdx = latestIdx - (n - 1 - i) - baseDelay;
                if (rIdx >= 0 && rIdx < snapshotLen) {
                    float r = refSnap[rIdx];
                    refWindowEnergy += (double) r * r;
                }
            }
        }
        boolean allowBlockAdapt = delayLocked
                && !inReverbTail
                && refWindowEnergy > 1e-5
                && (micPower < refWindowEnergy * 6.0);

        // Prime all P partitions of the Frequency-Domain Delay Line (FDL) from refSnap at pos=0
        // so asynchronous writer chunk jitter or resampling drift never desyncs the FDL.
        int fdlHead = 0;
        for (int p = 0; p < NUM_PARTITIONS; p++) {
            loadRefBlockIntoFft(refSnap, snapshotLen, latestIdx, n, -p * HOP_SIZE, baseDelay);
            fft(fftRe, fftIm);
            System.arraycopy(fftRe, 0, fdlRe[p], 0, HALF_BINS);
            System.arraycopy(fftIm, 0, fdlIm[p], 0, HALF_BINS);
        }

        double errPower = 0.0;
        int numHops = (n + HOP_SIZE - 1) / HOP_SIZE;

        for (int h = 0; h < numHops; h++) {
            int pos = h * HOP_SIZE;
            int hopCount = Math.min(HOP_SIZE, n - pos);

            if (h > 0) {
                // Shift circular FDL by 1 slot and compute only ONE 256-pt FFT for the new hop
                fdlHead = (fdlHead - 1) & (NUM_PARTITIONS - 1);
                loadRefBlockIntoFft(refSnap, snapshotLen, latestIdx, n, pos, baseDelay);
                fft(fftRe, fftIm);
                System.arraycopy(fftRe, 0, fdlRe[fdlHead], 0, HALF_BINS);
                System.arraycopy(fftIm, 0, fdlIm[fdlHead], 0, HALF_BINS);
            }

            // 1. Frequency-domain overlap-save filtering: Y[k] = sum_p W[p][k] * X[p][k]
            for (int k = 0; k < HALF_BINS; k++) {
                float sumRe = 0f;
                float sumIm = 0f;
                for (int p = 0; p < NUM_PARTITIONS; p++) {
                    int slot = (fdlHead + p) & (NUM_PARTITIONS - 1);
                    float xr = fdlRe[slot][k];
                    float xi = fdlIm[slot][k];
                    float wr = wRe[p][k];
                    float wi = wIm[p][k];
                    sumRe += wr * xr - wi * xi;
                    sumIm += wr * xi + wi * xr;
                }
                fftRe[k] = sumRe;
                fftIm[k] = sumIm;
            }
            // Enforce real-signal Hermitian symmetry for IFFT
            fftIm[0] = 0f;
            fftIm[FFT_SIZE / 2] = 0f;
            for (int k = 1; k < FFT_SIZE / 2; k++) {
                fftRe[FFT_SIZE - k] = fftRe[k];
                fftIm[FFT_SIZE - k] = -fftIm[k];
            }
            ifft(fftRe, fftIm);

            // 2. Time-domain echo subtraction over the valid second half [HOP_SIZE .. FFT_SIZE-1]
            double hopMicPow = 0.0;
            double hopYHatPow = 0.0;
            double hopErrPow = 0.0;
            for (int m = 0; m < hopCount; m++) {
                float yHat = fftRe[HOP_SIZE + m];
                float ms = mic[pos + m];
                float err = ms - yHat;
                if (err > 1.0f) {
                    err = 1.0f;
                } else if (err < -1.0f) {
                    err = -1.0f;
                }
                outErr[pos + m] = err;
                hopMicPow += (double) ms * ms;
                hopYHatPow += (double) yHat * yHat;
                hopErrPow += (double) err * err;
            }
            errPower += hopErrPow;

            // 3. Constrained PBFDAF weight update with double-talk protection
            if (allowBlockAdapt) {
                boolean hopDoubleTalk = (convergedBlocks >= 3)
                        && (hopYHatPow > 1e-6)
                        && (hopMicPow > hopYHatPow * 2.2)
                        && (hopErrPow > hopYHatPow * 1.5);
                float step = hopDoubleTalk ? DTD_SLOW_STEP : PBFDAF_STEP;

                // E = FFT([0..0 (128), e[0..127]])
                for (int i = 0; i < HOP_SIZE; i++) {
                    fftRe[i] = 0f;
                    fftIm[i] = 0f;
                }
                for (int m = 0; m < HOP_SIZE; m++) {
                    fftRe[HOP_SIZE + m] = (m < hopCount) ? outErr[pos + m] : 0f;
                    fftIm[HOP_SIZE + m] = 0f;
                }
                fft(fftRe, fftIm);
                System.arraycopy(fftRe, 0, errSpecRe, 0, HALF_BINS);
                System.arraycopy(fftIm, 0, errSpecIm, 0, HALF_BINS);

                // Update per-bin smoothed total reference power across all partitions
                for (int k = 0; k < HALF_BINS; k++) {
                    float pSum = 0f;
                    for (int p = 0; p < NUM_PARTITIONS; p++) {
                        int slot = (fdlHead + p) & (NUM_PARTITIONS - 1);
                        float xr = fdlRe[slot][k];
                        float xi = fdlIm[slot][k];
                        pSum += xr * xr + xi * xi;
                    }
                    smoothBinPower[k] = BIN_POWER_SMOOTH * smoothBinPower[k]
                            + (1.0f - BIN_POWER_SMOOTH) * pSum;
                }

                // Partition-wise normalized frequency-domain gradient with time-domain constraint
                for (int p = 0; p < NUM_PARTITIONS; p++) {
                    int slot = (fdlHead + p) & (NUM_PARTITIONS - 1);
                    float[] xRe = fdlRe[slot];
                    float[] xIm = fdlIm[slot];
                    for (int k = 0; k < HALF_BINS; k++) {
                        float invDenom = step / (smoothBinPower[k] + BIN_POWER_EPS);
                        // Clamp bin-wise step to prevent instability on narrowband spikes
                        if (invDenom > 20.0f) {
                            invDenom = 20.0f;
                        }
                        float xr = xRe[k];
                        float xi = xIm[k];
                        float er = errSpecRe[k];
                        float ei = errSpecIm[k];
                        // conj(X) * E = (xr*er + xi*ei) + j*(xr*ei - xi*er)
                        fftRe[k] = invDenom * (xr * er + xi * ei);
                        fftIm[k] = invDenom * (xr * ei - xi * er);
                    }
                    fftIm[0] = 0f;
                    fftIm[FFT_SIZE / 2] = 0f;
                    for (int k = 1; k < FFT_SIZE / 2; k++) {
                        fftRe[FFT_SIZE - k] = fftRe[k];
                        fftIm[FFT_SIZE - k] = -fftIm[k];
                    }
                    ifft(fftRe, fftIm);

                    // Enforce linear-convolution gradient constraint: keep taps [0..127], zero [128..255]
                    for (int i = 0; i < HOP_SIZE; i++) {
                        fftIm[i] = 0f;
                    }
                    for (int i = HOP_SIZE; i < FFT_SIZE; i++) {
                        fftRe[i] = 0f;
                        fftIm[i] = 0f;
                    }
                    fft(fftRe, fftIm);

                    float[] wpRe = wRe[p];
                    float[] wpIm = wIm[p];
                    for (int k = 0; k < HALF_BINS; k++) {
                        float nextRe = wpRe[k] * WEIGHT_LEAK + fftRe[k];
                        float nextIm = wpIm[k] * WEIGHT_LEAK + fftIm[k];
                        if (nextRe > MAX_WEIGHT_MAG) nextRe = MAX_WEIGHT_MAG;
                        else if (nextRe < -MAX_WEIGHT_MAG) nextRe = -MAX_WEIGHT_MAG;
                        if (nextIm > MAX_WEIGHT_MAG) nextIm = MAX_WEIGHT_MAG;
                        else if (nextIm < -MAX_WEIGHT_MAG) nextIm = -MAX_WEIGHT_MAG;
                        wpRe[k] = nextRe;
                        wpIm[k] = nextIm;
                    }
                }
            }
        }

        if (!delayLocked || (errPower >= micPower && errPower > 1e-9)) {
            // Non-negative ERLE safety guard (including unlocked state & digital silence):
            // while PBFDAF is converging or delay is unlocked, pass original PCM unmodified.
            lastErleDb = 0.0;
            lastPostAecRmsDbfs = micRmsDb;
            consecutiveZeroErleBlocks++;
            return micPcm16le;
        }

        byte[] out = new byte[micPcm16le.length];
        for (int i = 0; i < n; i++) {
            int s16 = Math.round(outErr[i] * 32767.0f);
            if (s16 > 32767) {
                s16 = 32767;
            } else if (s16 < -32768) {
                s16 = -32768;
            }
            out[i * 2] = (byte) (s16 & 0xFF);
            out[i * 2 + 1] = (byte) ((s16 >> 8) & 0xFF);
        }

        lastPostAecRmsDbfs = powerToDbfs(errPower, n);
        if (micPower > 1e-7 && errPower > 1e-9) {
            double erle = 10.0 * Math.log10(micPower / errPower);
            double clampedErle = Math.max(0.0, Math.min(35.0, erle));
            lastErleDb = clampedErle;
            if (clampedErle >= 1.5) {
                consecutiveZeroErleBlocks = 0;
                if (convergedBlocks < 1000) {
                    convergedBlocks++;
                }
            } else {
                consecutiveZeroErleBlocks++;
            }
        } else {
            lastErleDb = 0.0;
            consecutiveZeroErleBlocks++;
        }
        return out;
    }

    private static double powerToDbfs(double sumSq, int count) {
        if (count <= 0 || sumSq <= 1e-10) {
            return -96.0;
        }
        double meanSq = sumSq / count;
        if (meanSq <= 1e-10) {
            return -96.0;
        }
        double db = 10.0 * Math.log10(meanSq);
        return Math.max(-96.0, Math.min(0.0, db));
    }

    private void appendMicEnvelopeBins(float[] mic, int n) {
        int newBins = n / ENV_HOP_SAMPLES;
        if (newBins <= 0) {
            return;
        }
        if (newBins >= ENV_WINDOW_MAX_BINS) {
            int startBin = newBins - ENV_WINDOW_MAX_BINS;
            for (int b = 0; b < ENV_WINDOW_MAX_BINS; b++) {
                micEnvHistory[b] = computeAbsMean(mic, (startBin + b) * ENV_HOP_SAMPLES, ENV_HOP_SAMPLES);
            }
            micEnvCount = ENV_WINDOW_MAX_BINS;
            return;
        }
        int keep = Math.min(micEnvCount, ENV_WINDOW_MAX_BINS - newBins);
        if (keep > 0 && micEnvCount > 0) {
            int srcPos = micEnvCount - keep;
            System.arraycopy(micEnvHistory, srcPos, micEnvHistory, 0, keep);
        }
        for (int b = 0; b < newBins; b++) {
            micEnvHistory[keep + b] = computeAbsMean(mic, b * ENV_HOP_SAMPLES, ENV_HOP_SAMPLES);
        }
        micEnvCount = keep + newBins;
    }

    private static float computeAbsMean(float[] buf, int offset, int len) {
        float sum = 0f;
        int end = Math.min(buf.length, offset + len);
        int count = end - offset;
        if (count <= 0) {
            return 0f;
        }
        for (int i = offset; i < end; i++) {
            float v = buf[i];
            sum += (v >= 0f ? v : -v);
        }
        return sum / count;
    }

    /**
     * Loads a 256-sample overlap-save reference block {@code x[u] = x_aligned[pos - 128 + u]}
     * into {@code fftRe[0..255]} and zeroes {@code fftIm[0..255]}.
     */
    private void loadRefBlockIntoFft(
            float[] refSnap, int snapshotLen, int latestIdx, int blockLen, int pos, int baseDelay) {
        // refIdx(t) = latestIdx - (blockLen - 1 - t) - baseDelay
        // For t = pos - HOP_SIZE + u:
        int startIdx = latestIdx - (blockLen - 1) - baseDelay + pos - HOP_SIZE;
        for (int u = 0; u < FFT_SIZE; u++) {
            int idx = startIdx + u;
            fftRe[u] = (idx >= 0 && idx < snapshotLen) ? refSnap[idx] : 0f;
            fftIm[u] = 0f;
        }
    }

    /**
     * Hardware-guided two-domain delay estimator covering 0..1800ms (0..28800 samples).
     *
     * When already locked and converging ({@code consecutiveZeroErleBlocks < 3}), verifies locally
     * within ±64 samples around the tracked absolute playback cursor {@code refPlayEndSample}.
     * When unlocked or stalled, focuses first on the live AudioTrack queue-depth window
     * {@code [hwHint - 100ms .. hwHint + 450ms]} (falling back to full {@code [0 .. maxLag]} if
     * no hint or no lock), combining 320ms rolling 1ms-envelope Pearson correlation (phase- and
     * HPF-invariant for real phone speakers) with step-2 fine waveform refinement.
     */
    private void estimateDelayTwoStage(
            float[] mic, int n, float[] refSnap, int snapshotLen, long refWritten, int hwQueueHintMs) {
        int latestIdx = snapshotLen - 1;
        int availMax = (int) Math.min(
                (long) (snapshotLen - n - FILTER_TAPS - FFT_SIZE),
                refWritten - n);
        int maxLag = Math.min(MAX_DELAY_SAMPLES, availMax);
        if (maxLag < 0) {
            return;
        }

        long trackedLag = delayLocked ? (refWritten - refPlayEndSample) : (long) estimatedDelaySamples;
        int anchorOffset = "envelope".equals(lockMode) ? ENVELOPE_ANCHOR_LEAD : DELAY_ANCHOR_LEAD;
        int expectedLag = (int) Math.max(0L, Math.min((long) maxLag, trackedLag + anchorOffset));
        boolean needFullScan = !delayLocked || consecutiveZeroErleBlocks >= 3
                || trackedLag < -ENVELOPE_ANCHOR_LEAD || trackedLag > maxLag;

        if (!needFullScan) {
            // Fast fine local verification around expectedLag ± 64 samples (±4ms) at step 2.
            int localStart = Math.max(0, expectedLag - 64);
            int localEnd = Math.min(maxLag, expectedLag + 64);
            double bestLocalWave = 0.0;
            int bestLocalLag = expectedLag;
            for (int lag = localStart; lag <= localEnd; lag += 2) {
                double corr = computeNormalizedCorrelation(mic, n, refSnap, snapshotLen, latestIdx, lag, 2);
                if (corr > bestLocalWave) {
                    bestLocalWave = corr;
                    bestLocalLag = lag;
                }
            }
            lastPeakCorr = bestLocalWave;
            if (bestLocalWave >= WAVEFORM_LOCK_CORR) {
                int anchoredDelay = Math.max(0, bestLocalLag - DELAY_ANCHOR_LEAD);
                long measuredPlayEnd = refWritten - anchoredDelay;
                if (Math.abs(measuredPlayEnd - refPlayEndSample) > 64L) {
                    refPlayEndSample = measuredPlayEnd;
                    estimatedDelaySamples = anchoredDelay;
                    clearFilterWeights();
                    convergedBlocks = 0;
                    consecutiveZeroErleBlocks = 0;
                }
                delayLocked = true;
                lockMode = "waveform";
            }
            return;
        }

        // Try hardware-guided window [hwHint - 100ms .. hwHint + 450ms] first when AudioTrack
        // reported live queued frames, then fall back to full [0 .. maxLag] if unlocked.
        if (hwQueueHintMs >= 0) {
            int hintSamples = (hwQueueHintMs * TARGET_SAMPLE_RATE) / 1000;
            int winMin = Math.max(0, hintSamples - HW_HINT_PRE_MARGIN_SAMPLES);
            int winMax = Math.min(maxLag, hintSamples + HW_HINT_POST_MARGIN_SAMPLES);
            if (winMin <= winMax && scanWindowForDelayLock(mic, n, refSnap, snapshotLen, latestIdx, refWritten, winMin, winMax)) {
                return;
            }
        }
        scanWindowForDelayLock(mic, n, refSnap, snapshotLen, latestIdx, refWritten, 0, maxLag);
    }

    private boolean scanWindowForDelayLock(
            float[] mic,
            int n,
            float[] refSnap,
            int snapshotLen,
            int latestIdx,
            long refWritten,
            int winMin,
            int winMax) {
        // Stage 1A: 320ms rolling 1ms-envelope zero-mean Pearson correlation (phase/HPF invariant).
        // Bound envMaxAllowed by (refWritten - envWindowSamples) so pre-playback zero padding in
        // refRing never creates a false step-edge correlation before real playback fills the window.
        double bestEnvCorr = 0.0;
        int bestEnvLag = winMin;
        int envBins = micEnvCount;
        int envWindowSamples = envBins * ENV_HOP_SAMPLES;
        int envMaxAllowed = (int) Math.min(
                (long) Math.min(winMax, snapshotLen - envWindowSamples - ENV_HOP_SAMPLES),
                refWritten - envWindowSamples);
        if (envBins >= 40 && envMaxAllowed >= winMin) {
            int minLagBins = winMin / ENV_HOP_SAMPLES;
            int maxLagBins = envMaxAllowed / ENV_HOP_SAMPLES;
            int refBaseStart = latestIdx - (envWindowSamples - 1);
            int envOrigin = refBaseStart - maxLagBins * ENV_HOP_SAMPLES;
            int totalRefBins = maxLagBins - minLagBins + envBins;
            if (envOrigin >= 0 && totalRefBins > 0) {
                if (refEnvBuf.length < totalRefBins) {
                    refEnvBuf = new float[totalRefBins];
                }
                for (int j = 0; j < totalRefBins; j++) {
                    refEnvBuf[j] = computeAbsMean(refSnap, envOrigin + j * ENV_HOP_SAMPLES, ENV_HOP_SAMPLES);
                }
                double micSum = 0.0;
                int strideBins = envBins >= 160 ? 2 : 1;
                int evalCount = 0;
                for (int b = 0; b < envBins; b += strideBins) {
                    micSum += micEnvHistory[b];
                    evalCount++;
                }
                double micMean = micSum / Math.max(1, evalCount);
                double micVar = 0.0;
                for (int b = 0; b < envBins; b += strideBins) {
                    double d = micEnvHistory[b] - micMean;
                    micVar += d * d;
                }
                if (micVar > 1e-6) {
                    for (int lagB = minLagBins; lagB <= maxLagBins; lagB++) {
                        int rOff = maxLagBins - lagB;
                        double rSum = 0.0;
                        for (int b = 0; b < envBins; b += strideBins) {
                            rSum += refEnvBuf[rOff + b];
                        }
                        double rMean = rSum / evalCount;
                        double rVar = 0.0;
                        double cov = 0.0;
                        for (int b = 0; b < envBins; b += strideBins) {
                            double dm = micEnvHistory[b] - micMean;
                            double dr = refEnvBuf[rOff + b] - rMean;
                            rVar += dr * dr;
                            cov += dm * dr;
                        }
                        if (rVar > 1e-6 && cov > 0.0) {
                            double corr = cov / Math.sqrt(micVar * rVar);
                            if (corr > bestEnvCorr) {
                                bestEnvCorr = corr;
                                bestEnvLag = lagB * ENV_HOP_SAMPLES;
                            }
                        }
                    }
                }
            }
        }

        // Stage 1B: Fine waveform refinement around envelope peak ± 96 samples (±6ms) at step 2
        double bestWaveCorr = 0.0;
        int bestWaveLag = bestEnvLag;
        if (bestEnvCorr >= 0.25) {
            int fStart = Math.max(winMin, bestEnvLag - 96);
            int fEnd = Math.min(winMax, bestEnvLag + 96);
            for (int lag = fStart; lag <= fEnd; lag += 2) {
                double corr = computeNormalizedCorrelation(mic, n, refSnap, snapshotLen, latestIdx, lag, 2);
                if (corr > bestWaveCorr) {
                    bestWaveCorr = corr;
                    bestWaveLag = lag;
                }
            }
        }

        // Stage 1C: Coarse-to-fine waveform scan across [winMin .. winMax] whenever fine refinement
        // around the envelope peak did not already find a strong waveform lock. Ensures sample-exact
        // waveform lock on clean/stationary signals while preserving envelope lock on phase-dispersed
        // phone speakers where raw waveform correlation stays < WAVEFORM_LOCK_CORR.
        if (bestWaveCorr < WAVEFORM_LOCK_CORR) {
            double bestCoarse = 0.0;
            int bestCoarseLag = winMin;
            for (int lag = winMin; lag <= winMax; lag += 8) {
                double corr = computeNormalizedCorrelation(mic, n, refSnap, snapshotLen, latestIdx, lag, 4);
                if (corr > bestCoarse) {
                    bestCoarse = corr;
                    bestCoarseLag = lag;
                }
            }
            int fStart = Math.max(winMin, bestCoarseLag - 16);
            int fEnd = Math.min(winMax, bestCoarseLag + 16);
            for (int lag = fStart; lag <= fEnd; lag += 2) {
                double corr = computeNormalizedCorrelation(mic, n, refSnap, snapshotLen, latestIdx, lag, 2);
                if (corr > bestWaveCorr) {
                    bestWaveCorr = corr;
                    bestWaveLag = lag;
                }
            }
        }

        lastPeakCorr = bestWaveCorr;
        lastEnvCorr = bestEnvCorr;

        if (bestWaveCorr >= WAVEFORM_LOCK_CORR) {
            int anchoredDelay = Math.max(0, bestWaveLag - DELAY_ANCHOR_LEAD);
            long measuredPlayEnd = refWritten - anchoredDelay;
            if (!delayLocked || Math.abs(measuredPlayEnd - refPlayEndSample) > 64L) {
                refPlayEndSample = measuredPlayEnd;
                estimatedDelaySamples = anchoredDelay;
                clearFilterWeights();
                convergedBlocks = 0;
                consecutiveZeroErleBlocks = 0;
            }
            delayLocked = true;
            lockMode = "waveform";
            return true;
        }
        if (bestEnvCorr >= ENVELOPE_LOCK_CORR) {
            int anchoredDelay = Math.max(0, bestEnvLag - ENVELOPE_ANCHOR_LEAD);
            long measuredPlayEnd = refWritten - anchoredDelay;
            if (!delayLocked || Math.abs(measuredPlayEnd - refPlayEndSample) > 96L) {
                refPlayEndSample = measuredPlayEnd;
                estimatedDelaySamples = anchoredDelay;
                clearFilterWeights();
                convergedBlocks = 0;
                consecutiveZeroErleBlocks = 0;
            }
            delayLocked = true;
            lockMode = "envelope";
            return true;
        }
        return false;
    }

    private static double computeNormalizedCorrelation(
            float[] mic, int n, float[] refSnap, int snapshotLen, int latestIdx, int lag, int stride) {
        double dot = 0.0;
        double mPow = 1e-8;
        double rPow = 1e-8;
        int baseOffset = latestIdx - (n - 1) - lag;
        for (int i = 0; i < n; i += stride) {
            int rIdx = baseOffset + i;
            if (rIdx >= 0 && rIdx < snapshotLen) {
                float m = mic[i];
                float r = refSnap[rIdx];
                dot += (double) m * r;
                mPow += (double) m * m;
                rPow += (double) r * r;
            }
        }
        if (rPow <= 1e-6 || mPow <= 1e-7) {
            return 0.0;
        }
        return Math.abs(dot) / Math.sqrt(mPow * rPow);
    }

    private void clearFilterWeights() {
        for (int p = 0; p < NUM_PARTITIONS; p++) {
            float[] wr = wRe[p];
            float[] wi = wIm[p];
            for (int k = 0; k < HALF_BINS; k++) {
                wr[k] = 0f;
                wi[k] = 0f;
            }
        }
        for (int k = 0; k < HALF_BINS; k++) {
            smoothBinPower[k] = 0f;
        }
    }

    /** In-place radix-2 Cooley-Tukey forward FFT of length {@code FFT_SIZE} (256). */
    private static void fft(float[] re, float[] im) {
        for (int i = 0; i < FFT_SIZE; i++) {
            int j = BIT_REV[i];
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= FFT_SIZE; len <<= 1) {
            int half = len >> 1;
            int step = FFT_SIZE / len;
            for (int i = 0; i < FFT_SIZE; i += len) {
                int twIdx = 0;
                for (int j = 0; j < half; j++) {
                    float wr = TWIDDLE_COS[twIdx];
                    float wi = TWIDDLE_SIN[twIdx];
                    twIdx += step;
                    int u = i + j;
                    int v = u + half;
                    float vr = re[v] * wr - im[v] * wi;
                    float vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] = re[u] + vr;
                    im[u] = im[u] + vi;
                }
            }
        }
    }

    /** In-place radix-2 inverse FFT of length {@code FFT_SIZE} (256), scaled by {@code 1/FFT_SIZE}. */
    private static void ifft(float[] re, float[] im) {
        for (int i = 0; i < FFT_SIZE; i++) {
            im[i] = -im[i];
        }
        fft(re, im);
        final float invN = 1.0f / FFT_SIZE;
        for (int i = 0; i < FFT_SIZE; i++) {
            re[i] *= invN;
            im[i] = -im[i] * invN;
        }
    }
}
