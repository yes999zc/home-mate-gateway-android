package homes.mymate.gateway.protocol;

/**
 * Ultra-Fast Real-Time 16kHz Software Acoustic Echo Canceler (NLMS Adaptive Filter + Delay Tracker).
 *
 * Phase 3 v1.0.2 performance hardening:
 * - Power-of-two ring buffer (32,768 samples = ~2.05s @ 16kHz) using bitmask indexing (`& RING_MASK`),
 *   eliminating all division/modulo and inner-loop method calls.
 * - Sub-millisecond lock hold time: playback push only copies resampled floats into the ring buffer,
 *   while capture processing snapshots the required reference window under lock and runs NLMS lock-free.
 * - 32-tap adaptive NLMS centered around tracked coarse speaker-to-mic delay with double-talk guard.
 */
public final class SoftwareAecFilter {

    public static final int TARGET_SAMPLE_RATE = 16000;
    private static final int REF_RING_SIZE = 32768; // power of 2 (~2.05 seconds @ 16kHz)
    private static final int REF_RING_MASK = REF_RING_SIZE - 1;
    private static final int FILTER_TAPS = 32;
    private static final float NLMS_STEP = 0.28f;
    private static final float NLMS_EPS = 1e-4f;
    private static final float WEIGHT_LEAK = 0.9995f;

    private final Object ringLock = new Object();
    private final float[] refRing = new float[REF_RING_SIZE];
    private int refWriteIdx = 0;
    private long totalRefSamplesWritten = 0;
    private volatile long lastRefWallMs = 0;

    private final float[] weights = new float[FILTER_TAPS];
    private volatile boolean enabled = true;
    private volatile int estimatedDelaySamples = 640; // ~40ms typical Android speaker-to-mic latency
    private volatile double lastErleDb = 0.0;
    private int delayEstimateCounter = 0;

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

    public void reset() {
        synchronized (ringLock) {
            for (int i = 0; i < REF_RING_SIZE; i++) {
                refRing[i] = 0f;
            }
            refWriteIdx = 0;
            totalRefSamplesWritten = 0;
            lastRefWallMs = 0;
        }
        for (int i = 0; i < FILTER_TAPS; i++) {
            weights[i] = 0f;
        }
        lastErleDb = 0.0;
    }

    /**
     * Feed downstream playback PCM (music or TTS) of any sample rate and channel count (1 or 2).
     * Resamples outside the lock and performs a fast O(n) copy into the ring buffer.
     */
    public void pushPlaybackReference(byte[] pcm16le, int sampleRate, int channels, long nowMs) {
        if (!enabled || pcm16le == null || pcm16le.length < 2 || sampleRate <= 0) {
            return;
        }
        int ch = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * ch;
        int inFrames = pcm16le.length / frameBytes;
        if (inFrames <= 0) {
            return;
        }

        double ratio = (double) sampleRate / (double) TARGET_SAMPLE_RATE;
        int outSamples = Math.max(1, (int) Math.floor(inFrames / ratio));
        float[] resampled = new float[outSamples];
        for (int o = 0; o < outSamples; o++) {
            double srcPos = o * ratio;
            int idx0 = (int) srcPos;
            int idx1 = Math.min(inFrames - 1, idx0 + 1);
            float frac = (float) (srcPos - idx0);
            float s0 = readMonoNormalizedFrame(pcm16le, idx0, ch);
            float s1 = readMonoNormalizedFrame(pcm16le, idx1, ch);
            resampled[o] = s0 + (s1 - s0) * frac;
        }

        synchronized (ringLock) {
            int w = refWriteIdx;
            for (int o = 0; o < outSamples; o++) {
                refRing[w] = resampled[o];
                w = (w + 1) & REF_RING_MASK;
            }
            refWriteIdx = w;
            totalRefSamplesWritten += outSamples;
            lastRefWallMs = nowMs;
        }
    }

    /**
     * Cancel speaker playback echo from a 16kHz mono S16LE capture block.
     * Snapshots the reference tail under a brief lock, then executes NLMS completely lock-free.
     */
    public byte[] processCaptureBlock(byte[] micPcm16le, long nowMs) {
        if (micPcm16le == null || micPcm16le.length < 2) {
            return micPcm16le;
        }
        if (!enabled || (nowMs - lastRefWallMs) > 450L) {
            lastErleDb = 0.0;
            return micPcm16le;
        }

        int n = micPcm16le.length / 2;
        int snapshotLen = n + 3600 + FILTER_TAPS; // covers up to ~225ms delay + block + taps
        float[] refSnap = new float[snapshotLen];
        long refWritten;
        synchronized (ringLock) {
            refWritten = totalRefSamplesWritten;
            if (refWritten < 2048) {
                lastErleDb = 0.0;
                return micPcm16le;
            }
            int start = (refWriteIdx - snapshotLen) & REF_RING_MASK;
            for (int i = 0; i < snapshotLen; i++) {
                refSnap[i] = refRing[(start + i) & REF_RING_MASK];
            }
        }

        float[] mic = new float[n];
        double micPower = 0.0;
        for (int i = 0; i < n; i++) {
            int lo = micPcm16le[i * 2] & 0xFF;
            int hi = micPcm16le[i * 2 + 1];
            float s = (short) ((hi << 8) | lo) / 32768.0f;
            mic[i] = s;
            micPower += s * s;
        }

        // Refine coarse delay every 8 blocks (~640ms)
        if (delayEstimateCounter++ % 8 == 0) {
            estimateCoarseDelay(mic, refSnap, snapshotLen, refWritten);
        }

        int baseDelay = estimatedDelaySamples;
        int latestIdx = snapshotLen - 1;
        double refWindowEnergy = 0.0;
        for (int i = 0; i < n; i++) {
            int rIdx = latestIdx - ((n - 1 - i) + baseDelay);
            if (rIdx >= 0 && rIdx < snapshotLen) {
                float r = refSnap[rIdx];
                refWindowEnergy += r * r;
            }
        }
        boolean allowAdapt = refWindowEnergy > 1e-5 && (micPower < refWindowEnergy * 6.0);

        byte[] out = new byte[micPcm16le.length];
        double errPower = 0.0;
        float[] w = this.weights;

        for (int i = 0; i < n; i++) {
            int rBase = latestIdx - ((n - 1 - i) + baseDelay);
            float yHat = 0.0f;
            float norm = NLMS_EPS;

            if (rBase >= FILTER_TAPS && rBase < snapshotLen) {
                for (int k = 0; k < FILTER_TAPS; k++) {
                    float xk = refSnap[rBase - k];
                    yHat += w[k] * xk;
                    norm += xk * xk;
                }
            }

            float err = mic[i] - yHat;
            if (err > 1.0f) {
                err = 1.0f;
            } else if (err < -1.0f) {
                err = -1.0f;
            }
            errPower += err * err;

            if (allowAdapt && rBase >= FILTER_TAPS && rBase < snapshotLen) {
                float step = (NLMS_STEP * err) / norm;
                for (int k = 0; k < FILTER_TAPS; k++) {
                    w[k] = w[k] * WEIGHT_LEAK + step * refSnap[rBase - k];
                }
            }

            int s16 = Math.round(err * 32767.0f);
            if (s16 > 32767) {
                s16 = 32767;
            } else if (s16 < -32768) {
                s16 = -32768;
            }
            out[i * 2] = (byte) (s16 & 0xFF);
            out[i * 2 + 1] = (byte) ((s16 >> 8) & 0xFF);
        }

        if (micPower > 1e-7 && errPower > 1e-9) {
            double erle = 10.0 * Math.log10(micPower / errPower);
            lastErleDb = Math.max(0.0, Math.min(35.0, erle));
        } else {
            lastErleDb = 0.0;
        }
        return out;
    }

    private void estimateCoarseDelay(float[] mic, float[] refSnap, int snapshotLen, long refWritten) {
        int n = mic.length;
        int latestIdx = snapshotLen - 1;
        double bestCorr = 0.0;
        int bestLag = estimatedDelaySamples;
        int minLag = 160;
        int maxLag = Math.min(3520, (int) Math.min(snapshotLen - n - FILTER_TAPS - 2, refWritten - n));
        if (maxLag <= minLag) {
            return;
        }
        for (int lag = minLag; lag <= maxLag; lag += 48) {
            double dot = 0.0;
            double mPow = 1e-8;
            double rPow = 1e-8;
            for (int i = 0; i < n; i += 8) {
                float m = mic[i];
                int rIdx = latestIdx - ((n - 1 - i) + lag);
                if (rIdx >= 0 && rIdx < snapshotLen) {
                    float r = refSnap[rIdx];
                    dot += m * r;
                    mPow += m * m;
                    rPow += r * r;
                }
            }
            double corr = Math.abs(dot) / Math.sqrt(mPow * rPow);
            if (corr > bestCorr) {
                bestCorr = corr;
                bestLag = lag;
            }
        }
        if (bestCorr > 0.18) {
            estimatedDelaySamples = Math.max(0, bestLag - (FILTER_TAPS / 4));
        }
    }

    private static float readMonoNormalizedFrame(byte[] pcm16le, int frameIdx, int channels) {
        int byteOff = frameIdx * 2 * channels;
        if (byteOff + 1 >= pcm16le.length) {
            return 0f;
        }
        int lo0 = pcm16le[byteOff] & 0xFF;
        int hi0 = pcm16le[byteOff + 1];
        float ch0 = (short) ((hi0 << 8) | lo0) / 32768.0f;
        if (channels < 2 || byteOff + 3 >= pcm16le.length) {
            return ch0;
        }
        int lo1 = pcm16le[byteOff + 2] & 0xFF;
        int hi1 = pcm16le[byteOff + 3];
        float ch1 = (short) ((hi1 << 8) | lo1) / 32768.0f;
        return 0.5f * (ch0 + ch1);
    }
}
