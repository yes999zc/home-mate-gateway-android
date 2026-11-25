package homes.mymate.gateway.protocol;

/**
 * Pure-Java playback buffer sizing, initial pre-buffering & mid-stream underrun rebuffering policy
 * for Android AudioTrack streams (v2.3.13).
 *
 * Keeps voice/TTS replies at ultra-low 100ms latency (zero prebuffer/rebuffer delay) while giving
 * media streams (music/radio/story) a 1000ms AudioTrack ring buffer, 480ms initial prebuffer
 * cushion, and automatic 320ms mid-stream rebuffer recovery to eliminate Tailscale/cellular clicks
 * and persistent 0ms-cushion stuttering.
 */
public final class MediaPlaybackBufferPolicy {

    public static final int MEDIA_BUFFER_MS = 1000;
    public static final int VOICE_BUFFER_MS = 100;
    public static final int MEDIA_PREBUFFER_MS = 480;
    public static final int MEDIA_PREBUFFER_MAX_WAIT_MS = 850;
    public static final int MEDIA_REBUFFER_MS = 320;
    public static final int MEDIA_REBUFFER_MAX_WAIT_MS = 600;
    public static final int MEDIA_UNDERRUN_LOW_WATER_MS = 40;

    private MediaPlaybackBufferPolicy() {
    }

    /**
     * Computes the frame-aligned {@code AudioTrack} buffer size in bytes.
     *
     * @param isMedia     true for media streams (music/radio/story), false for low-latency voice TTS
     * @param sampleRate  PCM sample rate in Hz (defaults to 16000 when <= 0)
     * @param channels    PCM channel count (clamped to 1 or 2)
     * @param minBufBytes HAL minimum buffer size from {@code AudioTrack.getMinBufferSize(...)}
     * @return frame-aligned buffer size in bytes
     */
    public static int computeAudioTrackBufferBytes(
            boolean isMedia,
            int sampleRate,
            int channels,
            int minBufBytes) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        int numCh = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * numCh;
        int minBuf = minBufBytes > 0
                ? minBufBytes
                : Math.max(4096, (int) (((long) rate * frameBytes) / 10L));
        int desiredBytes;
        if (isMedia) {
            int mediaTarget = (int) (((long) rate * frameBytes * MEDIA_BUFFER_MS) / 1000L);
            desiredBytes = Math.max(minBuf * 3, mediaTarget);
        } else {
            int voiceTarget = (int) (((long) rate * frameBytes * VOICE_BUFFER_MS) / 1000L);
            desiredBytes = Math.max(minBuf, voiceTarget);
        }
        return alignUpToFrame(desiredBytes, frameBytes);
    }

    /**
     * Computes the target in-memory initial prebuffer size in bytes for media streams (480ms),
     * aligned up to the PCM16 frame size.
     */
    public static int computeMediaPrebufferBytes(int sampleRate, int channels) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        int numCh = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * numCh;
        int rawBytes = (int) (((long) rate * frameBytes * MEDIA_PREBUFFER_MS) / 1000L);
        return alignUpToFrame(rawBytes, frameBytes);
    }

    /**
     * Returns true once the initial media prebuffer should start {@code AudioTrack} and flush to the
     * DAC: when the 480ms byte target is reached, when EOS has arrived, or when at least one chunk
     * is buffered and {@link #MEDIA_PREBUFFER_MAX_WAIT_MS} (850ms) has elapsed since chunk #0.
     */
    public static boolean shouldFlushMediaPrebuffer(
            long bufferedBytes,
            long targetBytes,
            boolean eosReceived,
            long elapsedSinceFirstChunkMs) {
        if (eosReceived) {
            return true;
        }
        if (bufferedBytes <= 0L) {
            return false;
        }
        return bufferedBytes >= targetBytes
                || elapsedSinceFirstChunkMs >= MEDIA_PREBUFFER_MAX_WAIT_MS;
    }

    /**
     * Computes the target in-memory mid-stream rebuffer size in bytes for media streams (320ms),
     * aligned up to the PCM16 frame size.
     */
    public static int computeMediaRebufferBytes(int sampleRate, int channels) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        int numCh = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * numCh;
        int rawBytes = (int) (((long) rate * frameBytes * MEDIA_REBUFFER_MS) / 1000L);
        return alignUpToFrame(rawBytes, frameBytes);
    }

    /**
     * Computes the hardware queued-frame low-water threshold ({@link #MEDIA_UNDERRUN_LOW_WATER_MS},
     * 40ms) below which an empty media chunk poll is treated as a real DAC underrun.
     */
    public static long computeMediaLowWaterFrames(int sampleRate) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        return ((long) rate * MEDIA_UNDERRUN_LOW_WATER_MS) / 1000L;
    }

    /**
     * Returns true ONLY when an active media stream without EOS has drained its hardware AudioTrack
     * queue down to or below the 40ms low-water threshold and should pause to rebuffer cleanly.
     * Voice/TTS streams ({@code isMedia == false}) NEVER enter mid-stream rebuffer.
     */
    public static boolean shouldEnterMediaRebuffer(
            boolean isMedia,
            boolean trackStarted,
            boolean eosReceived,
            long bufferedFramesInTrack,
            int sampleRate) {
        return isMedia
                && trackStarted
                && !eosReceived
                && bufferedFramesInTrack <= computeMediaLowWaterFrames(sampleRate);
    }

    /**
     * Returns true once a mid-stream media rebuffer should resume {@code AudioTrack} and flush to
     * the DAC: when EOS has arrived, or when at least one chunk is buffered and either the 320ms
     * rebuffer byte target is reached or {@link #MEDIA_REBUFFER_MAX_WAIT_MS} (600ms) has elapsed.
     */
    public static boolean shouldFlushMediaRebuffer(
            long bufferedBytes,
            long targetBytes,
            boolean eosReceived,
            long elapsedSinceRebufferStartMs) {
        if (eosReceived) {
            return true;
        }
        if (bufferedBytes <= 0L) {
            return false;
        }
        return bufferedBytes >= targetBytes
                || elapsedSinceRebufferStartMs >= MEDIA_REBUFFER_MAX_WAIT_MS;
    }

    private static int alignUpToFrame(int bytes, int frameBytes) {
        if (bytes <= 0) {
            return frameBytes;
        }
        return ((bytes + frameBytes - 1) / frameBytes) * frameBytes;
    }
}
