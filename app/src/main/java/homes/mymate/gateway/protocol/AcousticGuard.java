package homes.mymate.gateway.protocol;

import java.util.Arrays;

/**
 * Pure-Java Acoustic Pre-Roll Ring Buffer, Self-Hearing Echo Guard, and Barge-In Detector.
 *
 * v2.0 hardening:
 * - Playback active state is anchored explicitly to AudioEngine's active stream lifecycle
 *   (no premature 5-second timeout while long tracks/WAVs play).
 * - Playback reference energy is fed from AudioEngine's writer thread right as PCM enters the DAC
 *   (scaled by hardware volume * channel ducking gain), NOT 20s early on TCP chunk arrival.
 * - Never fabricates fake refLocked=true without measured correlation (honest refLocked=false
 *   until real cross-correlation lock is achieved).
 */
public final class AcousticGuard {

    public static final int SAMPLE_RATE = 16000;
    public static final int PREROLL_MS = 400;
    public static final int PREROLL_SAMPLES = (SAMPLE_RATE * PREROLL_MS) / 1000; // 6400 samples
    public static final int PREROLL_BYTES = PREROLL_SAMPLES * 2; // 12800 bytes

    public static final class BargeInFact {
        public final byte[] prerollPcm16le;
        public final double energyDb;
        public final double refDb;
        public final double residualDb;
        public final double corr;
        public final boolean refLocked;

        public BargeInFact(
                byte[] prerollPcm16le,
                double energyDb,
                double refDb,
                double residualDb,
                double corr,
                boolean refLocked) {
            this.prerollPcm16le = prerollPcm16le;
            this.energyDb = energyDb;
            this.refDb = refDb;
            this.residualDb = residualDb;
            this.corr = corr;
            this.refLocked = refLocked;
        }
    }

    private final byte[] prerollRing = new byte[PREROLL_BYTES];
    private int ringWritePos = 0;
    private int ringFilledBytes = 0;

    private volatile boolean playbackActive = false;
    private volatile double lastRefDbfs = -96.0;
    private volatile long lastRefUpdatedMs = 0L;
    private int consecutiveBargeInFrames = 0;
    private long lastBargeInTriggeredMs = 0L;

    public synchronized void reset() {
        Arrays.fill(prerollRing, (byte) 0);
        ringWritePos = 0;
        ringFilledBytes = 0;
        playbackActive = false;
        lastRefDbfs = -96.0;
        lastRefUpdatedMs = 0L;
        consecutiveBargeInFrames = 0;
    }

    /**
     * Feed playback reference PCM right as AudioEngine writes it to the hardware track,
     * scaled by effective linear output gain (hardware volume * channel ducking).
     */
    public synchronized void onPlaybackReferenceChunk(byte[] pcm16le, double effectiveLinearGain, long nowMs) {
        boolean wasActive = this.playbackActive;
        this.playbackActive = true;
        this.lastRefUpdatedMs = nowMs;
        if (pcm16le != null && pcm16le.length >= 2) {
            double rawRms = NorthboundSession.computeRmsDbfs(pcm16le);
            double gain = Math.max(0.01, Math.min(1.0, effectiveLinearGain));
            double scaledRms = Math.max(-96.0, rawRms + 20.0 * Math.log10(gain));
            if (!wasActive || this.lastRefDbfs <= -90.0) {
                this.lastRefDbfs = scaledRms;
            } else {
                this.lastRefDbfs = 0.55 * scaledRms + 0.45 * this.lastRefDbfs;
            }
        }
    }

    /** Legacy 2-arg overload preserved for unit test compatibility. */
    public synchronized void onPlaybackReferenceChunk(byte[] pcm16le, long nowMs) {
        onPlaybackReferenceChunk(pcm16le, 1.0, nowMs);
    }

    public synchronized void setPlaybackActive(boolean active) {
        this.playbackActive = active;
        if (!active) {
            this.lastRefDbfs = -96.0;
            this.consecutiveBargeInFrames = 0;
        }
    }

    public synchronized void onPlaybackStopped() {
        setPlaybackActive(false);
    }

    public synchronized boolean isPlaybackActive(long nowMs) {
        return playbackActive;
    }

    public synchronized BargeInFact pushCaptureBlockAndCheckBargeIn(
            byte[] pcm16le,
            double micRmsDbfs,
            double noiseFloorDbfs,
            boolean bargeInEnabled,
            long nowMs) {
        return pushCaptureBlockAndCheckBargeIn(
                pcm16le, micRmsDbfs, noiseFloorDbfs, bargeInEnabled, false, nowMs);
    }

    public synchronized BargeInFact pushCaptureBlockAndCheckBargeIn(
            byte[] pcm16le,
            double micRmsDbfs,
            double noiseFloorDbfs,
            boolean bargeInEnabled,
            boolean aecReferenceLocked,
            long nowMs) {
        if (pcm16le == null || pcm16le.length == 0) {
            return null;
        }
        appendToRing(pcm16le);

        if (!bargeInEnabled || !playbackActive) {
            consecutiveBargeInFrames = 0;
            return null;
        }

        double refDb = this.lastRefDbfs;
        double residualDb = micRmsDbfs - refDb;
        double snrDb = micRmsDbfs - noiseFloorDbfs;

        boolean frameIsNearEndSpeech = (snrDb >= 14.0 && micRmsDbfs >= -34.0 && residualDb >= -12.0);
        if (frameIsNearEndSpeech) {
            consecutiveBargeInFrames++;
        } else {
            consecutiveBargeInFrames = Math.max(0, consecutiveBargeInFrames - 1);
        }

        if (consecutiveBargeInFrames >= 3 && (nowMs - lastBargeInTriggeredMs) > 1200L) {
            consecutiveBargeInFrames = 0;
            lastBargeInTriggeredMs = nowMs;
            boolean freshRef = (this.lastRefUpdatedMs > 0L)
                    && (nowMs - this.lastRefUpdatedMs) <= 300L
                    && refDb > -50.0;
            boolean refLocked = aecReferenceLocked && freshRef;
            return new BargeInFact(
                    snapshotPreroll(),
                    micRmsDbfs,
                    refDb,
                    residualDb,
                    0.0,
                    refLocked);
        }
        return null;
    }

    public synchronized boolean isSelfHeardEcho(double micRmsDbfs, long nowMs) {
        if (!playbackActive) {
            return false;
        }
        double residualDb = micRmsDbfs - this.lastRefDbfs;
        return this.lastRefDbfs > -35.0 && residualDb < -15.0;
    }

    public synchronized byte[] snapshotPreroll() {
        if (ringFilledBytes == 0) {
            return new byte[0];
        }
        byte[] out = new byte[ringFilledBytes];
        if (ringFilledBytes < PREROLL_BYTES) {
            System.arraycopy(prerollRing, 0, out, 0, ringFilledBytes);
        } else {
            int firstPart = PREROLL_BYTES - ringWritePos;
            System.arraycopy(prerollRing, ringWritePos, out, 0, firstPart);
            if (ringWritePos > 0) {
                System.arraycopy(prerollRing, 0, out, firstPart, ringWritePos);
            }
        }
        return out;
    }

    private void appendToRing(byte[] src) {
        int len = src.length;
        if (len >= PREROLL_BYTES) {
            System.arraycopy(src, len - PREROLL_BYTES, prerollRing, 0, PREROLL_BYTES);
            ringWritePos = 0;
            ringFilledBytes = PREROLL_BYTES;
            return;
        }
        int firstCopy = Math.min(len, PREROLL_BYTES - ringWritePos);
        System.arraycopy(src, 0, prerollRing, ringWritePos, firstCopy);
        int secondCopy = len - firstCopy;
        if (secondCopy > 0) {
            System.arraycopy(src, firstCopy, prerollRing, 0, secondCopy);
        }
        ringWritePos = (ringWritePos + len) % PREROLL_BYTES;
        ringFilledBytes = Math.min(PREROLL_BYTES, ringFilledBytes + len);
    }
}
