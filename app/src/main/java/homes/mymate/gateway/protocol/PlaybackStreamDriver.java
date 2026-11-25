package homes.mymate.gateway.protocol;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java playback stream writer loop & synchronous stop/drain driver shared by
 * {@code AudioEngine} and deterministic JVM behavioral tests (v2.3.13).
 *
 * Encapsulates:
 * - Initial media pre-buffering (480ms target / 850ms max wait / EOS) while keeping voice/TTS at 0ms.
 * - Mid-stream media underrun detection (120ms play poll -> <=40ms HW queued frames under trackLock)
 *   with clean {@code pause()} (no flush, no focus loss) and 320ms / 600ms / EOS rebuffer recovery.
 * - EOS drain with 64-bit wrap-aware HW playback head tracking, resuming {@code play()} before
 *   {@code stop()} if EOS arrives during empty rebuffer with remaining HW buffered frames.
 * - Thread-safe synchronous {@link #stopAndMeasure(StreamState)} reporting exact unwrapped HW
 *   {@code consumedMs} without deadlocking against active writes or mid-stream rebuffering.
 */
public final class PlaybackStreamDriver {

    public static final long DEFAULT_MEDIA_PLAY_POLL_MS = 120L;
    public static final long DEFAULT_VOICE_PLAY_POLL_MS = 150L;
    public static final long DEFAULT_BUFFER_POLL_MS = 40L;
    public static final long DEFAULT_DRAIN_POLL_MS = 20L;
    public static final long DEFAULT_DRAIN_STALL_MS = 1_200L;
    public static final long DEFAULT_WRITE_STALL_MS = 2_500L;
    public static final long DEFAULT_ZERO_WRITE_SLEEP_MS = 8L;

    /** Minimal pure-Java handle over Android {@code AudioTrack}. */
    public interface TrackHandle {
        void play();
        void pause();
        void flush();
        void stop();
        void release();
        int getPlaybackHeadPosition();
        int write(byte[] data, int offset, int length);
    }

    /** Per-chunk and error hooks implemented by {@code AudioEngine} (or test fakes). */
    public interface WriterCallbacks {
        void onBeforeWriteChunk(byte[] chunk, long nowMs);
        void onPcmWritten(byte[] chunk, int offset, int written, long nowMs);
        void onFirstChunkWritten();
        void onAudioError(String stage, String message);
    }

    /** Clock & sleep abstraction (production uses wall clock + {@code Thread.sleep}). */
    public interface Clock {
        long currentTimeMillis();
        void sleep(long millis) throws InterruptedException;
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    /** Shared mutable per-stream playback state guarded by {@code trackLock} / volatile flags. */
    public static final class StreamState {
        public static final byte[] END_OF_STREAM_SENTINEL = new byte[0];

        public final String taskId;
        public final String channel;
        public final boolean isMedia;
        public final int sampleRate;
        public final int channels;
        public final TrackHandle track;
        public final Object trackLock = new Object();
        public final ArrayBlockingQueue<byte[]> chunkQueue;
        public volatile Thread writerThread;
        public volatile long pcmBytesWritten = 0L;
        public volatile boolean cancelled = false;
        public volatile boolean eosReceived = false;
        public volatile boolean trackStarted = false;
        public volatile int lastQueuedTrackMs = -1;
        public boolean released = false;
        private long lastRawHead = 0L;
        private long headWrapOffset = 0L;

        public StreamState(
                String taskId,
                String channel,
                boolean isMedia,
                int sampleRate,
                int channels,
                int queueCapacity,
                TrackHandle track) {
            this.taskId = taskId;
            this.channel = channel;
            this.isMedia = isMedia;
            this.sampleRate = sampleRate > 0 ? sampleRate : 16000;
            this.channels = channels >= 2 ? 2 : 1;
            this.chunkQueue = new ArrayBlockingQueue<byte[]>(Math.max(8, queueCapacity));
            this.track = track;
        }

        /** Must be called while holding {@code trackLock} and when {@code !released}. */
        public long readUnwrappedHeadFramesLocked() {
            long raw = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            if (raw < lastRawHead && (lastRawHead - raw) > 0x80000000L) {
                headWrapOffset += 0x100000000L;
            }
            lastRawHead = raw;
            return headWrapOffset + raw;
        }
    }

    /** Terminal outcome of {@link #runWriterLoop(StreamState, WriterCallbacks)}. */
    public static final class RunResult {
        public final boolean reachedEos;
        public final boolean wasCancelled;
        public final boolean measuredHwDelay;
        public final long totalPlayedMs;
        public final String playbackFailedDetail;

        public RunResult(
                boolean reachedEos,
                boolean wasCancelled,
                boolean measuredHwDelay,
                long totalPlayedMs,
                String playbackFailedDetail) {
            this.reachedEos = reachedEos;
            this.wasCancelled = wasCancelled;
            this.measuredHwDelay = measuredHwDelay;
            this.totalPlayedMs = totalPlayedMs;
            this.playbackFailedDetail = playbackFailedDetail;
        }
    }

    private static final class WriteLoopContext {
        boolean firstChunkSent = false;
        String playbackFailedDetail = null;
    }

    private PlaybackStreamDriver() {
    }

    public static RunResult runWriterLoop(
            final StreamState state,
            final WriterCallbacks callbacks) {
        return runWriterLoop(
                state,
                callbacks,
                SYSTEM_CLOCK,
                DEFAULT_MEDIA_PLAY_POLL_MS,
                DEFAULT_VOICE_PLAY_POLL_MS,
                DEFAULT_BUFFER_POLL_MS,
                DEFAULT_DRAIN_POLL_MS,
                DEFAULT_DRAIN_STALL_MS,
                DEFAULT_WRITE_STALL_MS,
                DEFAULT_ZERO_WRITE_SLEEP_MS);
    }

    static RunResult runWriterLoop(
            final StreamState state,
            final WriterCallbacks callbacks,
            final Clock clock,
            final long mediaPlayPollMs,
            final long voicePlayPollMs,
            final long bufferPollMs,
            final long drainPollMs,
            final long drainStallMs,
            final long writeStallMs,
            final long zeroWriteSleepMs) {
        boolean reachedEos = false;
        boolean measuredHwDelay = false;
        boolean trackStarted = !state.isMedia;
        final int mediaPrebufferTargetBytes = state.isMedia
                ? MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(state.sampleRate, state.channels)
                : 0;
        final int mediaRebufferTargetBytes = state.isMedia
                ? MediaPlaybackBufferPolicy.computeMediaRebufferBytes(state.sampleRate, state.channels)
                : 0;
        final ArrayList<byte[]> prebuffer = state.isMedia ? new ArrayList<byte[]>(16) : null;
        long prebufferedBytes = 0L;
        long firstPrebufferChunkWallMs = 0L;
        boolean rebuffering = false;
        long rebufferStartWallMs = 0L;
        long rebufferedBytes = 0L;
        final WriteLoopContext writeState = new WriteLoopContext();
        try {
            while (!state.cancelled && !Thread.currentThread().isInterrupted()) {
                long pollTimeoutMs = (!trackStarted || rebuffering)
                        ? bufferPollMs
                        : (state.isMedia ? mediaPlayPollMs : voicePlayPollMs);
                byte[] chunk = state.chunkQueue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    boolean eosNow = state.eosReceived && state.chunkQueue.isEmpty();
                    if (!trackStarted) {
                        long elapsedMs = (firstPrebufferChunkWallMs > 0L)
                                ? (clock.currentTimeMillis() - firstPrebufferChunkWallMs)
                                : 0L;
                        if (MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(
                                prebufferedBytes, mediaPrebufferTargetBytes, eosNow, elapsedMs)) {
                            if (prebufferedBytes > 0L) {
                                if (!startMediaTrackAndFlushPrebuffer(
                                        state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                                    break;
                                }
                                trackStarted = true;
                                prebufferedBytes = 0L;
                                if (state.cancelled) {
                                    break;
                                }
                            }
                            if (eosNow) {
                                reachedEos = true;
                                break;
                            }
                        } else if (eosNow) {
                            reachedEos = true;
                            break;
                        }
                        continue;
                    }
                    if (rebuffering) {
                        long elapsedMs = (rebufferStartWallMs > 0L)
                                ? (clock.currentTimeMillis() - rebufferStartWallMs)
                                : 0L;
                        if (MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(
                                rebufferedBytes, mediaRebufferTargetBytes, eosNow, elapsedMs)) {
                            if (rebufferedBytes > 0L) {
                                if (!startMediaTrackAndFlushPrebuffer(
                                        state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                                    break;
                                }
                                rebuffering = false;
                                rebufferStartWallMs = 0L;
                                rebufferedBytes = 0L;
                                if (state.cancelled) {
                                    break;
                                }
                            }
                            if (eosNow) {
                                reachedEos = true;
                                break;
                            }
                        } else if (eosNow) {
                            reachedEos = true;
                            break;
                        }
                        continue;
                    }
                    if (eosNow) {
                        reachedEos = true;
                        break;
                    }
                    if (state.isMedia) {
                        boolean enterRebuffer = false;
                        synchronized (state.trackLock) {
                            if (!state.released && !state.cancelled && !state.eosReceived) {
                                try {
                                    long totalWrittenFrames = state.pcmBytesWritten / (2L * state.channels);
                                    long headFrames = state.readUnwrappedHeadFramesLocked();
                                    long queuedFrames = Math.max(0L, totalWrittenFrames - headFrames);
                                    if (MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(
                                            true, trackStarted, state.eosReceived, queuedFrames, state.sampleRate)) {
                                        state.track.pause();
                                        enterRebuffer = true;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        if (enterRebuffer) {
                            rebuffering = true;
                            rebufferStartWallMs = 0L;
                            rebufferedBytes = 0L;
                            prebuffer.clear();
                        }
                    }
                    continue;
                }
                if (chunk == StreamState.END_OF_STREAM_SENTINEL) {
                    if (!trackStarted && prebufferedBytes > 0L) {
                        if (!startMediaTrackAndFlushPrebuffer(
                                state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                            break;
                        }
                        trackStarted = true;
                        prebufferedBytes = 0L;
                        if (state.cancelled) {
                            break;
                        }
                    } else if (rebuffering && rebufferedBytes > 0L) {
                        if (!startMediaTrackAndFlushPrebuffer(
                                state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                            break;
                        }
                        rebuffering = false;
                        rebufferStartWallMs = 0L;
                        rebufferedBytes = 0L;
                        if (state.cancelled) {
                            break;
                        }
                    }
                    reachedEos = true;
                    break;
                }
                if (state.cancelled) {
                    break;
                }
                if (!trackStarted) {
                    if (firstPrebufferChunkWallMs == 0L) {
                        firstPrebufferChunkWallMs = clock.currentTimeMillis();
                    }
                    prebuffer.add(chunk);
                    prebufferedBytes += chunk.length;
                    long elapsedMs = clock.currentTimeMillis() - firstPrebufferChunkWallMs;
                    if (MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(
                            prebufferedBytes, mediaPrebufferTargetBytes, state.eosReceived, elapsedMs)) {
                        if (!startMediaTrackAndFlushPrebuffer(
                                state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                            break;
                        }
                        trackStarted = true;
                        prebufferedBytes = 0L;
                        if (state.cancelled) {
                            break;
                        }
                    }
                    continue;
                }
                if (rebuffering) {
                    if (rebufferStartWallMs == 0L) {
                        rebufferStartWallMs = clock.currentTimeMillis();
                    }
                    prebuffer.add(chunk);
                    rebufferedBytes += chunk.length;
                    long elapsedMs = clock.currentTimeMillis() - rebufferStartWallMs;
                    if (MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(
                            rebufferedBytes, mediaRebufferTargetBytes, state.eosReceived, elapsedMs)) {
                        if (!startMediaTrackAndFlushPrebuffer(
                                state, prebuffer, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                            break;
                        }
                        rebuffering = false;
                        rebufferStartWallMs = 0L;
                        rebufferedBytes = 0L;
                        if (state.cancelled) {
                            break;
                        }
                    }
                    continue;
                }
                if (!writeChunkToTrack(state, chunk, writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                    break;
                }
            }

            if (reachedEos && !state.cancelled && trackStarted) {
                final long totalFrames = state.pcmBytesWritten / (2L * state.channels);
                long headFrames = 0L;
                synchronized (state.trackLock) {
                    if (!state.released && !state.cancelled) {
                        try {
                            headFrames = state.readUnwrappedHeadFramesLocked();
                            if (headFrames >= 0L) {
                                measuredHwDelay = true;
                            }
                            if (rebuffering && totalFrames > headFrames) {
                                try {
                                    state.track.play();
                                } catch (Throwable ignored) {
                                }
                                rebuffering = false;
                            }
                            state.track.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                long remainingFrames = Math.max(0L, totalFrames - headFrames);
                long remainingMs = (remainingFrames * 1000L) / Math.max(1, state.sampleRate);
                long deadline = clock.currentTimeMillis() + Math.min(15_000L, Math.max(150L, remainingMs + 350L));
                long lastHead = headFrames;
                long lastAdvanceMs = clock.currentTimeMillis();

                while (!state.cancelled && clock.currentTimeMillis() < deadline) {
                    long curHead;
                    synchronized (state.trackLock) {
                        if (state.released) {
                            break;
                        }
                        try {
                            curHead = state.readUnwrappedHeadFramesLocked();
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                    if (totalFrames > 0 && curHead >= totalFrames) {
                        break;
                    }
                    long now = clock.currentTimeMillis();
                    if (curHead > lastHead) {
                        lastHead = curHead;
                        lastAdvanceMs = now;
                    } else if (now - lastAdvanceMs > drainStallMs) {
                        break; // HAL head stalled after drain
                    }
                    clock.sleep(drainPollMs);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (prebuffer != null) {
                prebuffer.clear();
            }
        }

        long totalPlayedMs = NorthboundSession.computeConsumedAudioMs(
                state.pcmBytesWritten,
                state.sampleRate,
                state.channels);
        return new RunResult(
                reachedEos,
                state.cancelled,
                measuredHwDelay,
                totalPlayedMs,
                writeState.playbackFailedDetail);
    }

    /**
     * Synchronously stops a stream, reading exact unwrapped hardware DAC consumed progress
     * BEFORE calling {@code pause() + flush()}, and interrupts the writer thread.
     */
    public static NorthboundSession.StopPlaybackResult stopAndMeasure(final StreamState state) {
        state.cancelled = true;
        state.chunkQueue.clear();

        long writtenMs = NorthboundSession.computeConsumedAudioMs(
                state.pcmBytesWritten, state.sampleRate, state.channels);
        long consumedMs = writtenMs;
        String basis = "written";

        synchronized (state.trackLock) {
            if (!state.released) {
                if (state.trackStarted) {
                    try {
                        long headFrames = state.readUnwrappedHeadFramesLocked();
                        long hwMs = (headFrames * 1000L) / Math.max(1, state.sampleRate);
                        if (hwMs >= 0L) {
                            consumedMs = Math.min(writtenMs, hwMs);
                            basis = "hw_delay";
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        state.track.pause();
                        state.track.flush();
                    } catch (Throwable ignored) {
                    }
                } else {
                    consumedMs = 0L;
                    basis = "hw_delay";
                }
            }
        }

        Thread wt = state.writerThread;
        if (wt != null) {
            wt.interrupt();
        }
        return new NorthboundSession.StopPlaybackResult(true, writtenMs, consumedMs, basis);
    }

    public static void releaseTrackSafely(final StreamState state, final boolean flushFirst) {
        synchronized (state.trackLock) {
            if (state.released) {
                return;
            }
            state.released = true;
            if (state.trackStarted) {
                try {
                    if (flushFirst) {
                        state.track.pause();
                        state.track.flush();
                    }
                    state.track.stop();
                } catch (Throwable ignored) {
                }
            }
            try {
                state.track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean startMediaTrackAndFlushPrebuffer(
            final StreamState state,
            final ArrayList<byte[]> prebuffer,
            final WriteLoopContext writeState,
            final WriterCallbacks callbacks,
            final Clock clock,
            final long writeStallMs,
            final long zeroWriteSleepMs) throws InterruptedException {
        synchronized (state.trackLock) {
            if (state.released || state.cancelled) {
                prebuffer.clear();
                return false;
            }
            try {
                state.track.play();
                state.trackStarted = true;
            } catch (Throwable t) {
                String err = "AudioTrack.play failed: " + t.getMessage();
                callbacks.onAudioError("playback_play", err);
                state.cancelled = true;
                writeState.playbackFailedDetail = err;
                prebuffer.clear();
                return false;
            }
        }
        for (int i = 0; i < prebuffer.size(); i++) {
            if (state.cancelled) {
                prebuffer.clear();
                return false;
            }
            if (!writeChunkToTrack(state, prebuffer.get(i), writeState, callbacks, clock, writeStallMs, zeroWriteSleepMs)) {
                prebuffer.clear();
                return false;
            }
        }
        prebuffer.clear();
        return true;
    }

    private static boolean writeChunkToTrack(
            final StreamState state,
            final byte[] chunk,
            final WriteLoopContext writeState,
            final WriterCallbacks callbacks,
            final Clock clock,
            final long writeStallMs,
            final long zeroWriteSleepMs) throws InterruptedException {
        callbacks.onBeforeWriteChunk(chunk, clock.currentTimeMillis());

        int offset = 0;
        long zeroWriteStartMs = 0L;
        while (offset < chunk.length && !state.cancelled) {
            int written;
            int queuedMsSnapshot = -1;
            synchronized (state.trackLock) {
                if (state.released || state.cancelled) {
                    return false;
                }
                written = state.track.write(chunk, offset, chunk.length - offset);
                if (written > 0 && state.trackStarted) {
                    try {
                        long totalWrittenFrames = (state.pcmBytesWritten + written) / (2L * state.channels);
                        long headFrames = state.readUnwrappedHeadFramesLocked();
                        long queuedFrames = Math.max(0L, totalWrittenFrames - headFrames);
                        queuedMsSnapshot = (int) Math.min(4000L, (queuedFrames * 1000L) / Math.max(1, state.sampleRate));
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (written > 0) {
                zeroWriteStartMs = 0L;
                int writeStart = offset;
                offset += written;
                state.pcmBytesWritten += written;
                if (queuedMsSnapshot >= 0) {
                    state.lastQueuedTrackMs = queuedMsSnapshot;
                }
                long writeNowMs = clock.currentTimeMillis();
                callbacks.onPcmWritten(chunk, writeStart, written, writeNowMs);
                if (!writeState.firstChunkSent) {
                    writeState.firstChunkSent = true;
                    callbacks.onFirstChunkWritten();
                }
            } else if (written == 0) {
                long nowZeroMs = clock.currentTimeMillis();
                if (zeroWriteStartMs == 0L) {
                    zeroWriteStartMs = nowZeroMs;
                } else if ((nowZeroMs - zeroWriteStartMs) >= writeStallMs) {
                    callbacks.onAudioError("playback_write",
                            "AudioTrack.write returned 0 for >" + writeStallMs + "ms; aborting stream");
                    state.cancelled = true;
                    writeState.playbackFailedDetail = "playback_write_stalled";
                    return false;
                }
                clock.sleep(zeroWriteSleepMs);
            } else {
                String writeErr = "AudioTrack.write returned " + written;
                callbacks.onAudioError("playback_write", writeErr);
                state.cancelled = true;
                writeState.playbackFailedDetail = writeErr;
                return false;
            }
        }
        return !state.cancelled;
    }
}
