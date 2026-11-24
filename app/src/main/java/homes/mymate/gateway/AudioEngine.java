package homes.mymate.gateway;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import homes.mymate.gateway.protocol.NorthboundSession;
import homes.mymate.gateway.protocol.SoftwareAecFilter;

/**
 * Low-Latency Android Audio Engine for Home Mate Universal Gateway (v2.0).
 *
 * v2.0 architectural hardening:
 * - Per-session {@code trackLock} + {@code released} guard eliminates all native AudioTrack
 *   use-after-free SIGSEGV races between writer thread, stop(), ducking, and release().
 * - Calls {@code track.stop()} BEFORE 64-bit wrap-aware {@code getPlaybackHeadPosition()} drain
 *   polling with 1.2s stall detection so short TTS replies never hang or clip tails.
 * - {@link #stopStream(String)} reads exact hardware DAC head position BEFORE {@code pause()+flush()}
 *   and returns {@link NorthboundSession.StopPlaybackResult} synchronously.
 * - Single-owner {@code AudioRecord} lifecycle (released exclusively on capture thread) + error backoff.
 * - Synchronized ref-counted AudioFocus (transient-duck for voice, full gain for media, pre-Oreo fallback,
 *   real duck/loss reaction).
 * - Feeds {@link CaptureCallback#onPlaybackReferenceChunk} directly from the DAC writer loop scaled by
 *   effective output gain, and notifies {@link EnergyListener} at 12.5-50Hz with zero allocations.
 */
public final class AudioEngine {

    private static final String TAG = "HomeMateAudio";
    public static final int SAMPLE_RATE_HZ = 16000;
    public static final int BLOCK_SAMPLES = 1280; // 80ms @ 16kHz
    public static final int BLOCK_BYTES = BLOCK_SAMPLES * 2;
    private static final int STREAM_QUEUE_CAPACITY = 512;
    private static final long CANCEL_TOMBSTONE_TTL_MS = 30_000L;

    public interface CaptureCallback {
        void onAudioBlockCaptured(byte[] pcm16leBytes, double rmsDbfs, double noiseFloorDbfs, double snrDb, boolean aecActive);
        void onPlaybackFirstChunk(String taskId);
        void onPlaybackReferenceChunk(byte[] pcm16leBytes, double effectiveLinearGain, long nowMs);
        void onPlaybackActiveChanged(boolean anyActive);
        void onPlaybackSuperseded(String supersededTaskId);
        void onPlaybackFinished(String taskId, long playedMs);
        void onAudioError(String stage, String message);
    }

    /** Zero-allocation live audio energy hook for the 3D Particle Orb UI (mic, TTS, & beat-reactive music). */
    public interface EnergyListener {
        void onLiveAudioEnergy(double rmsDbfs, double bassDbfs, boolean isMusic);
    }

    private static final byte[] END_OF_STREAM_SENTINEL = new byte[0];

    private static final class StreamSession {
        final String taskId;
        final String channel;
        final boolean isMedia;
        final int sampleRate;
        final int channels;
        final AudioTrack track;
        final Object trackLock = new Object();
        final ArrayBlockingQueue<byte[]> chunkQueue = new ArrayBlockingQueue<byte[]>(STREAM_QUEUE_CAPACITY);
        volatile Thread writerThread;
        volatile long pcmBytesWritten = 0;
        volatile float currentTrackGain = 1.0f;
        volatile boolean cancelled = false;
        boolean released = false;
        long lastRawHead = 0L;
        long headWrapOffset = 0L;

        StreamSession(String taskId, String channel, boolean isMedia, int sampleRate, int channels, AudioTrack track) {
            this.taskId = taskId;
            this.channel = channel;
            this.isMedia = isMedia;
            this.sampleRate = sampleRate > 0 ? sampleRate : SAMPLE_RATE_HZ;
            this.channels = channels >= 2 ? 2 : 1;
            this.track = track;
        }

        /** Must be called while holding {@code trackLock} and when {@code !released}. */
        long readUnwrappedHeadFramesLocked() {
            long raw = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            if (raw < lastRawHead && (lastRawHead - raw) > 0x80000000L) {
                headWrapOffset += 0x100000000L;
            }
            lastRawHead = raw;
            return headWrapOffset + raw;
        }
    }

    private final Context context;
    private final CaptureCallback callback;
    private final SoftwareAecFilter softwareAec = new SoftwareAecFilter();

    private volatile boolean running = false;
    private volatile boolean useVoiceCommSource = true;
    private volatile boolean softwareAecEnabled = false;
    private volatile float uplinkGain = 1.0f;
    private volatile double noiseFloorDbfs = -58.0;
    private volatile EnergyListener energyListener;

    // Only captureThread creates/releases AudioRecord & effects; volatile ref used solely to unblock read() via stop()
    private volatile AudioRecord activeRecorderForUnblock;
    private volatile boolean hardwareAecEnabled = false;
    private volatile boolean hardwareNsEnabled = false;
    private Thread captureThread;

    // AudioFocus state (synchronized on focusLock)
    private final Object focusLock = new Object();
    private AudioFocusRequest voiceFocusRequest;
    private AudioFocusRequest mediaFocusRequest;
    private boolean hasVoiceFocus = false;
    private boolean hasMediaFocus = false;
    private volatile boolean focusDucked = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                focusDucked = true;
                updateMediaDucking();
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN
                    || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                focusDucked = false;
                updateMediaDucking();
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                focusDucked = false;
                stopMediaStreamsOnly();
            }
        }
    };

    // Concurrent per-taskId playback streams ("voice" and "media")
    private final ConcurrentHashMap<String, StreamSession> activeStreams = new ConcurrentHashMap<String, StreamSession>();
    // TTL-evicted tombstones for early-arriving Stop frames
    private final ConcurrentHashMap<String, Long> cancelledTaskIds = new ConcurrentHashMap<String, Long>();

    public AudioEngine(Context context, CaptureCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void setEnergyListener(EnergyListener listener) {
        this.energyListener = listener;
    }

    public void configure(boolean useVoiceCommSource, boolean softwareAecEnabled, float uplinkGain) {
        boolean sourceChanged = (this.useVoiceCommSource != useVoiceCommSource);
        this.useVoiceCommSource = useVoiceCommSource;
        this.softwareAecEnabled = softwareAecEnabled;
        this.softwareAec.setEnabled(softwareAecEnabled);
        this.uplinkGain = Math.max(0.5f, Math.min(6.0f, uplinkGain));
        if (sourceChanged && running) {
            stopCapture();
            startCapture();
        }
    }

    public boolean isHardwareAecEnabled() { return hardwareAecEnabled; }
    public boolean isSoftwareAecEnabled() { return softwareAecEnabled; }
    public double getSoftwareAecErleDb() { return softwareAec.getLastErleDb(); }
    public boolean isHardwareNsEnabled() { return hardwareNsEnabled; }
    public double getNoiseFloorDbfs() { return noiseFloorDbfs; }

    public boolean hasActiveMediaPlayback() {
        for (StreamSession s : activeStreams.values()) {
            if (!s.cancelled && s.isMedia) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActiveVoicePlayback() {
        for (StreamSession s : activeStreams.values()) {
            if (!s.cancelled && !s.isMedia) {
                return true;
            }
        }
        return false;
    }

    public String getOutputRouteName() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : devs) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        return "Bluetooth";
                    }
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        return "Wired";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Speaker";
    }

    public synchronized void startCapture() {
        if (running) {
            return;
        }
        running = true;
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runCaptureLoop();
            }
        }, "hm-audio-capture");
        captureThread.setPriority(Thread.NORM_PRIORITY + 2);
        captureThread.start();
    }

    public synchronized void stopCapture() {
        running = false;
        AudioRecord rec = activeRecorderForUnblock;
        if (rec != null) {
            try {
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                }
            } catch (Throwable ignored) {
            }
        }
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500L);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void runCaptureLoop() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = BLOCK_BYTES * 4;
        }
        int bufferSize = Math.max(minBuf * 2, BLOCK_BYTES * 4);

        while (running && !Thread.currentThread().isInterrupted()) {
            AudioRecord localRecord = null;
            AcousticEchoCanceler localAec = null;
            NoiseSuppressor localNs = null;
            AutomaticGainControl localAgc = null;

            try {
                int audioSource = useVoiceCommSource
                        ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        : MediaRecorder.AudioSource.MIC;

                localRecord = new AudioRecord(
                        audioSource,
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (localRecord.getState() != AudioRecord.STATE_INITIALIZED && useVoiceCommSource) {
                    localRecord.release();
                    localRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);
                }

                if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    callback.onAudioError("init", "AudioRecord initialization failed (check RECORD_AUDIO permission)");
                    running = false;
                    return;
                }

                activeRecorderForUnblock = localRecord;
                int sessionId = localRecord.getAudioSessionId();
                hardwareAecEnabled = false;
                hardwareNsEnabled = false;
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        localAec = AcousticEchoCanceler.create(sessionId);
                        if (localAec != null) {
                            localAec.setEnabled(true);
                            hardwareAecEnabled = localAec.getEnabled();
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        localNs = NoiseSuppressor.create(sessionId);
                        if (localNs != null) {
                            localNs.setEnabled(true);
                            hardwareNsEnabled = localNs.getEnabled();
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (AutomaticGainControl.isAvailable()) {
                        localAgc = AutomaticGainControl.create(sessionId);
                        if (localAgc != null) {
                            // Explicitly disable AGC so quiet room noise floors are not amplified into false ASR turns
                            localAgc.setEnabled(false);
                        }
                    }
                } catch (Throwable ignored) {
                }

                localRecord.startRecording();

                byte[] readBuffer = new byte[BLOCK_BYTES];
                int consecutiveReadErrors = 0;
                final int halfBlock = BLOCK_BYTES / 2;

                while (running && !Thread.currentThread().isInterrupted()) {
                    int offset = 0;
                    boolean halfEmitted = false;
                    boolean readFailed = false;
                    while (offset < BLOCK_BYTES && running) {
                        int targetChunk = (!halfEmitted && offset < halfBlock)
                                ? (halfBlock - offset)
                                : (BLOCK_BYTES - offset);
                        int r = localRecord.read(readBuffer, offset, targetChunk);
                        if (r > 0) {
                            offset += r;
                            consecutiveReadErrors = 0;
                            if (!halfEmitted && offset >= halfBlock) {
                                halfEmitted = true;
                                EnergyListener el = energyListener;
                                if (el != null && !hasActiveMediaPlayback()) {
                                    double rms = computeSubBlockRmsDbfs(readBuffer, 0, halfBlock);
                                    el.onLiveAudioEnergy(rms, rms, false);
                                }
                            }
                        } else {
                            readFailed = true;
                            consecutiveReadErrors++;
                            break;
                        }
                    }
                    if (!running) {
                        break;
                    }
                    if (readFailed || offset < BLOCK_BYTES) {
                        if (consecutiveReadErrors >= 8) {
                            callback.onAudioError("read_recover", "AudioRecord read stalled; re-initializing");
                            break; // Re-initialize recorder cleanly
                        }
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        continue;
                    }

                    byte[] block = new byte[BLOCK_BYTES];
                    System.arraycopy(readBuffer, 0, block, 0, BLOCK_BYTES);

                    if (softwareAecEnabled) {
                        block = softwareAec.processCaptureBlock(block, System.currentTimeMillis());
                    }

                    if (Math.abs(uplinkGain - 1.0f) > 0.02f) {
                        applyPcmGainInPlace(block, uplinkGain);
                    }

                    double rmsDbfs = NorthboundSession.computeRmsDbfs(block);
                    if (rmsDbfs < noiseFloorDbfs) {
                        noiseFloorDbfs = 0.85 * noiseFloorDbfs + 0.15 * rmsDbfs;
                    } else if (rmsDbfs < noiseFloorDbfs + 6.0) {
                        noiseFloorDbfs = 0.995 * noiseFloorDbfs + 0.005 * rmsDbfs;
                    }
                    noiseFloorDbfs = Math.max(-85.0, Math.min(-30.0, noiseFloorDbfs));
                    double snrDb = Math.max(0.0, rmsDbfs - noiseFloorDbfs);

                    EnergyListener el = energyListener;
                    if (el != null && !hasActiveMediaPlayback()) {
                        // Second 40ms half-block completes genuine 25Hz (40ms cadence) orb voice reactivity
                        double halfRms = computeSubBlockRmsDbfs(block, halfBlock, halfBlock);
                        el.onLiveAudioEnergy(halfRms, halfRms, false);
                    }

                    callback.onAudioBlockCaptured(block, rmsDbfs, noiseFloorDbfs, snrDb,
                            hardwareAecEnabled || softwareAecEnabled);
                }
            } catch (SecurityException se) {
                callback.onAudioError("permission", "RECORD_AUDIO permission not granted: " + se.getMessage());
                running = false;
                return;
            } catch (Throwable t) {
                callback.onAudioError("capture", "Capture error: " + t.getMessage());
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                    break;
                }
            } finally {
                activeRecorderForUnblock = null;
                releaseEffectSafely(localAec);
                releaseEffectSafely(localNs);
                releaseEffectSafely(localAgc);
                if (localRecord != null) {
                    try {
                        if (localRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            localRecord.stop();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        localRecord.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void releaseEffectSafely(android.media.audiofx.AudioEffect effect) {
        if (effect != null) {
            try {
                effect.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void applyPcmGainInPlace(byte[] pcm16le, float gain) {
        int samples = pcm16le.length / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            int amplified = Math.round(s * gain);
            if (amplified > 32767) {
                amplified = 32767;
            } else if (amplified < -32768) {
                amplified = -32768;
            }
            pcm16le[i * 2] = (byte) (amplified & 0xFF);
            pcm16le[i * 2 + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    // =========================================================================
    // Multi-Channel Low-Latency Playback ("voice" & "media")
    // =========================================================================

    /** Apply hardware volume and return the actual stepped linear volume read back from AudioManager. */
    public double setHardwareVolume(double linearVolume) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (max > 0) {
                    int target = (int) Math.round(Math.max(0.0, Math.min(1.0, linearVolume)) * max);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                    int actual = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    return Math.max(0.0, Math.min(1.0, (double) actual / (double) max));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "setHardwareVolume failed: " + t.getMessage());
        }
        return Math.max(0.0, Math.min(1.0, linearVolume));
    }

    public double getCurrentHardwareVolumeLinear() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (max > 0) {
                    return Math.max(0.0, Math.min(1.0, (double) cur / (double) max));
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.65;
    }

    public void playWav(final String taskId, final String channel, final byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length == 0) {
            callback.onPlaybackFinished(taskId, 0L);
            return;
        }
        final int sampleRate = NorthboundSession.parseWavSampleRate(wavBytes, SAMPLE_RATE_HZ);
        final int channels = NorthboundSession.parseWavChannels(wavBytes, 1);
        final byte[] pcmBytes = NorthboundSession.stripWavHeaderToPcm16le(wavBytes);
        beginStream(taskId, channel, sampleRate, channels);
        writeStreamChunk(taskId, pcmBytes);
        endStream(taskId);
    }

    public void beginStream(String taskId, String channel, int sampleRate, int channels) {
        if (taskId == null) {
            return;
        }
        pruneExpiredTombstones();
        if (cancelledTaskIds.remove(taskId) != null) {
            return;
        }
        boolean isMedia = isMediaChannel(channel);
        String chName = isMedia ? "media" : "voice";

        // Preempt any existing stream on the same normalized channel class and report superseded
        for (Map.Entry<String, StreamSession> entry : activeStreams.entrySet()) {
            StreamSession existing = entry.getValue();
            if (existing != null && existing.isMedia == isMedia && !taskId.equals(existing.taskId)) {
                stopStream(existing.taskId);
                callback.onPlaybackSuperseded(existing.taskId);
            }
        }

        requestPlaybackAudioFocus(isMedia);

        int rate = sampleRate > 0 ? sampleRate : SAMPLE_RATE_HZ;
        int numCh = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * numCh;
        int channelMask = (numCh == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = Math.max(4096, (rate * frameBytes) / 10);
        }

        int desiredBytes = isMedia ? Math.max(minBuf * 2, (rate * frameBytes) / 3)
                : Math.max(minBuf, (rate * frameBytes) / 10);
        int bufSize = ((desiredBytes + frameBytes - 1) / frameBytes) * frameBytes;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(isMedia ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();

        AudioTrack track = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                track = new AudioTrack(
                        attrs, fmt, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            }
        } catch (Throwable t) {
            callback.onAudioError("playback_init", "AudioTrack create failed: " + t.getMessage());
            callback.onPlaybackFinished(taskId, 0L);
            return;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            callback.onAudioError("playback_init", "AudioTrack uninitialized for " + rate + "Hz/" + numCh + "ch");
            callback.onPlaybackFinished(taskId, 0L);
            return;
        }

        final StreamSession session = new StreamSession(taskId, chName, isMedia, rate, numCh, track);
        activeStreams.put(taskId, session);
        updateMediaDucking();
        callback.onPlaybackActiveChanged(true);

        synchronized (session.trackLock) {
            if (!session.released) {
                try {
                    track.play();
                } catch (Throwable t) {
                    activeStreams.remove(taskId, session);
                    releaseSessionTrackSafely(session, true);
                    callback.onAudioError("playback_play", "AudioTrack.play failed: " + t.getMessage());
                    callback.onPlaybackFinished(taskId, 0L);
                    return;
                }
            }
        }

        Thread wt = new Thread(new Runnable() {
            @Override
            public void run() {
                runStreamWriterLoop(session);
            }
        }, "hm-play-" + chName + "-" + taskId);
        wt.setPriority(Thread.NORM_PRIORITY + 1);
        wt.setDaemon(true);
        session.writerThread = wt;
        wt.start();
    }

    public void writeStreamChunk(String taskId, final byte[] pcmBytes) {
        if (taskId == null || pcmBytes == null || pcmBytes.length == 0) {
            return;
        }
        final StreamSession session = activeStreams.get(taskId);
        if (session == null || session.cancelled) {
            return;
        }
        if (!session.chunkQueue.offer(pcmBytes)) {
            // Bounded backpressure: wait briefly rather than silent OOM or hard drop
            try {
                session.chunkQueue.offer(pcmBytes, 250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public void endStream(final String taskId) {
        if (taskId == null) {
            return;
        }
        final StreamSession session = activeStreams.get(taskId);
        if (session == null || session.cancelled) {
            return;
        }
        if (!session.chunkQueue.offer(END_OF_STREAM_SENTINEL)) {
            try {
                session.chunkQueue.offer(END_OF_STREAM_SENTINEL, 250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void runStreamWriterLoop(final StreamSession session) {
        boolean reachedEos = false;
        boolean firstChunkSent = false;
        long lastRefFeedMs = 0L;
        try {
            while (!session.cancelled && !Thread.currentThread().isInterrupted()) {
                byte[] chunk = session.chunkQueue.poll(150, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    continue;
                }
                if (chunk == END_OF_STREAM_SENTINEL) {
                    reachedEos = true;
                    break;
                }

                long nowMs = System.currentTimeMillis();
                if (softwareAecEnabled) {
                    softwareAec.pushPlaybackReference(chunk, session.sampleRate, session.channels, nowMs);
                }
                double effGain = getCurrentHardwareVolumeLinear() * session.currentTrackGain;
                callback.onPlaybackReferenceChunk(chunk, effGain, nowMs);

                if (nowMs - lastRefFeedMs >= 28L) {
                    lastRefFeedMs = nowMs;
                    EnergyListener el = energyListener;
                    if (el != null) {
                        if (session.isMedia) {
                            // Real-time stereo music full-band + low-pass bass energy (~35Hz) for orb rhythm
                            double fullRms = NorthboundSession.computeRmsDbfs(chunk);
                            double bassRms = computeLowPassBassRmsDbfs(chunk, session.channels);
                            el.onLiveAudioEnergy(fullRms, bassRms, true);
                        } else {
                            double ttsRms = NorthboundSession.computeRmsDbfs(chunk);
                            el.onLiveAudioEnergy(ttsRms, ttsRms, false);
                        }
                    }
                }

                int offset = 0;
                while (offset < chunk.length && !session.cancelled) {
                    int written;
                    synchronized (session.trackLock) {
                        if (session.released || session.cancelled) {
                            break;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            written = session.track.write(
                                    chunk, offset, chunk.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        } else {
                            written = session.track.write(chunk, offset, chunk.length - offset);
                        }
                    }
                    if (written > 0) {
                        offset += written;
                        session.pcmBytesWritten += written;
                        if (!firstChunkSent) {
                            firstChunkSent = true;
                            callback.onPlaybackFirstChunk(session.taskId);
                        }
                    } else if (written == 0) {
                        Thread.sleep(8L);
                    } else {
                        callback.onAudioError("playback_write", "AudioTrack.write returned " + written);
                        session.cancelled = true;
                        break;
                    }
                }
            }

            if (reachedEos && !session.cancelled) {
                final long totalFrames = session.pcmBytesWritten / (2L * session.channels);
                // Signal MODE_STREAM AudioTrack to drain all buffered frames cleanly
                long headFrames = 0L;
                synchronized (session.trackLock) {
                    if (!session.released && !session.cancelled) {
                        try {
                            headFrames = session.readUnwrappedHeadFramesLocked();
                            session.track.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                long remainingFrames = Math.max(0L, totalFrames - headFrames);
                long remainingMs = (remainingFrames * 1000L) / Math.max(1, session.sampleRate);
                long deadline = System.currentTimeMillis() + Math.min(15_000L, Math.max(150L, remainingMs + 350L));
                long lastHead = headFrames;
                long lastAdvanceMs = System.currentTimeMillis();

                while (!session.cancelled && System.currentTimeMillis() < deadline) {
                    long curHead;
                    synchronized (session.trackLock) {
                        if (session.released) {
                            break;
                        }
                        try {
                            curHead = session.readUnwrappedHeadFramesLocked();
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                    if (totalFrames > 0 && curHead >= totalFrames) {
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (curHead > lastHead) {
                        lastHead = curHead;
                        lastAdvanceMs = now;
                    } else if (now - lastAdvanceMs > 1200L) {
                        break; // HAL head stalled after drain
                    }
                    Thread.sleep(20L);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            boolean wasCancelled = session.cancelled;
            final long totalPlayedMs = NorthboundSession.computeConsumedAudioMs(
                    session.pcmBytesWritten,
                    session.sampleRate,
                    session.channels);
            activeStreams.remove(session.taskId, session);
            session.chunkQueue.clear();
            releaseSessionTrackSafely(session, wasCancelled);
            updateMediaDucking();
            boolean anyActive = hasActiveMediaPlayback() || hasActiveVoicePlayback();
            callback.onPlaybackActiveChanged(anyActive);
            updateFocusAfterStreamChange();
            if (reachedEos && !wasCancelled) {
                callback.onPlaybackFinished(session.taskId, totalPlayedMs);
            }
        }
    }

    /**
     * Synchronously stops a stream, reading exact hardware DAC consumed progress BEFORE flushing.
     */
    public NorthboundSession.StopPlaybackResult stopStream(String taskId) {
        if (taskId == null) {
            stopAndFlush();
            return new NorthboundSession.StopPlaybackResult(false, 0L, 0L, "hw_delay");
        }
        StreamSession session = activeStreams.remove(taskId);
        if (session == null) {
            pruneExpiredTombstones();
            cancelledTaskIds.put(taskId, Long.valueOf(System.currentTimeMillis()));
            return new NorthboundSession.StopPlaybackResult(false, 0L, 0L, "hw_delay");
        }

        session.cancelled = true;
        session.chunkQueue.clear();

        long writtenMs = NorthboundSession.computeConsumedAudioMs(
                session.pcmBytesWritten, session.sampleRate, session.channels);
        long consumedMs = writtenMs;
        String basis = "written_bytes";

        synchronized (session.trackLock) {
            if (!session.released) {
                try {
                    long headFrames = session.readUnwrappedHeadFramesLocked();
                    long hwMs = (headFrames * 1000L) / Math.max(1, session.sampleRate);
                    if (hwMs >= 0L) {
                        consumedMs = Math.min(writtenMs, hwMs);
                        basis = "hw_delay";
                    }
                } catch (Throwable ignored) {
                }
                try {
                    session.track.pause();
                    session.track.flush();
                } catch (Throwable ignored) {
                }
            }
        }

        Thread wt = session.writerThread;
        if (wt != null) {
            wt.interrupt();
        }
        updateMediaDucking();
        callback.onPlaybackActiveChanged(hasActiveMediaPlayback() || hasActiveVoicePlayback());
        updateFocusAfterStreamChange();
        return new NorthboundSession.StopPlaybackResult(true, writtenMs, consumedMs, basis);
    }

    public List<String> stopAndFlush() {
        List<String> flushedChannels = new ArrayList<String>();
        boolean hadVoice = false;
        boolean hadMedia = false;
        for (String key : new ArrayList<String>(activeStreams.keySet())) {
            StreamSession session = activeStreams.remove(key);
            if (session != null) {
                if (session.isMedia) {
                    hadMedia = true;
                } else {
                    hadVoice = true;
                }
                session.cancelled = true;
                session.chunkQueue.clear();
                synchronized (session.trackLock) {
                    if (!session.released) {
                        try {
                            session.track.pause();
                            session.track.flush();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                Thread wt = session.writerThread;
                if (wt != null) {
                    wt.interrupt();
                }
            }
        }
        if (hadVoice) {
            flushedChannels.add("voice");
        }
        if (hadMedia) {
            flushedChannels.add("music");
        }
        callback.onPlaybackActiveChanged(false);
        updateFocusAfterStreamChange();
        return flushedChannels;
    }

    private void stopMediaStreamsOnly() {
        for (String key : new ArrayList<String>(activeStreams.keySet())) {
            StreamSession s = activeStreams.get(key);
            if (s != null && s.isMedia) {
                stopStream(key);
            }
        }
    }

    private void updateMediaDucking() {
        boolean voiceActive = hasActiveVoicePlayback();
        float mediaGain = (voiceActive || focusDucked) ? 0.20f : 1.0f;
        for (StreamSession s : activeStreams.values()) {
            if (!s.cancelled && s.isMedia) {
                s.currentTrackGain = mediaGain;
                synchronized (s.trackLock) {
                    if (!s.released) {
                        try {
                            s.track.setVolume(mediaGain);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void requestPlaybackAudioFocus(boolean isMedia) {
        synchronized (focusLock) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return;
                }
                if (isMedia) {
                    if (hasMediaFocus) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (mediaFocusRequest == null) {
                            AudioAttributes attrs = new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build();
                            mediaFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                    .setAudioAttributes(attrs)
                                    .setAcceptsDelayedFocusGain(true)
                                    .setOnAudioFocusChangeListener(focusChangeListener)
                                    .build();
                        }
                        am.requestAudioFocus(mediaFocusRequest);
                    } else {
                        am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    }
                    hasMediaFocus = true;
                } else {
                    if (hasVoiceFocus) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (voiceFocusRequest == null) {
                            AudioAttributes attrs = new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build();
                            voiceFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                    .setAudioAttributes(attrs)
                                    .setOnAudioFocusChangeListener(focusChangeListener)
                                    .build();
                        }
                        am.requestAudioFocus(voiceFocusRequest);
                    } else {
                        am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                    }
                    hasVoiceFocus = true;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void updateFocusAfterStreamChange() {
        synchronized (focusLock) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return;
                }
                if (hasVoiceFocus && !hasActiveVoicePlayback()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && voiceFocusRequest != null) {
                        am.abandonAudioFocusRequest(voiceFocusRequest);
                    } else {
                        am.abandonAudioFocus(focusChangeListener);
                    }
                    hasVoiceFocus = false;
                }
                if (hasMediaFocus && !hasActiveMediaPlayback()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaFocusRequest != null) {
                        am.abandonAudioFocusRequest(mediaFocusRequest);
                    } else {
                        am.abandonAudioFocus(focusChangeListener);
                    }
                    hasMediaFocus = false;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void pruneExpiredTombstones() {
        long cutoff = System.currentTimeMillis() - CANCEL_TOMBSTONE_TTL_MS;
        Iterator<Map.Entry<String, Long>> it = cancelledTaskIds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() == null || e.getValue().longValue() < cutoff) {
                it.remove();
            }
        }
    }

    /**
     * Lightweight single-pole low-pass filter (~180Hz cutoff at 44.1kHz) to extract bass/kick energy
     * from active stereo/mono music PCM chunks for beat-reactive ParticleOrbView animation.
     */
    private static double computeLowPassBassRmsDbfs(byte[] pcm16le, int channels) {
        if (pcm16le == null || pcm16le.length < 4) {
            return -96.0;
        }
        int stepBytes = (channels >= 2) ? 4 : 2;
        int frames = pcm16le.length / stepBytes;
        // Stride by 2 frames for ultra-fast <0.1ms execution on mobile CPUs
        double lp = 0.0;
        double alpha = 0.055; // low-pass smoothing emphasizing kick/bass
        double sumSq = 0.0;
        int count = 0;
        for (int f = 0; f < frames; f += 2) {
            int pos = f * stepBytes;
            int lo = pcm16le[pos] & 0xFF;
            int hi = pcm16le[pos + 1];
            double sample = ((short) ((hi << 8) | lo)) / 32768.0;
            if (channels >= 2 && pos + 3 < pcm16le.length) {
                int loR = pcm16le[pos + 2] & 0xFF;
                int hiR = pcm16le[pos + 3];
                double sampleR = ((short) ((hiR << 8) | loR)) / 32768.0;
                sample = 0.5 * (sample + sampleR);
            }
            lp += alpha * (sample - lp);
            sumSq += lp * lp;
            count++;
        }
        if (count == 0) {
            return -96.0;
        }
        double rms = Math.sqrt(sumSq / count);
        if (rms <= 1e-5) {
            return -96.0;
        }
        return Math.max(-96.0, Math.min(0.0, 20.0 * Math.log10(rms)));
    }

    private static double computeSubBlockRmsDbfs(byte[] pcm16le, int offset, int length) {
        if (pcm16le == null || length < 2 || offset + length > pcm16le.length) {
            return -96.0;
        }
        int samples = length / 2;
        double sumSq = 0.0;
        for (int i = 0; i < samples; i++) {
            int pos = offset + i * 2;
            int lo = pcm16le[pos] & 0xFF;
            int hi = pcm16le[pos + 1];
            short s = (short) ((hi << 8) | lo);
            double norm = s / 32768.0;
            sumSq += norm * norm;
        }
        double rms = Math.sqrt(sumSq / samples);
        if (rms <= 1e-5) {
            return -96.0;
        }
        return Math.max(-96.0, Math.min(0.0, 20.0 * Math.log10(rms)));
    }

    private static boolean isMediaChannel(String channel) {
        return "media".equalsIgnoreCase(channel) || "music".equalsIgnoreCase(channel);
    }

    private static void releaseSessionTrackSafely(StreamSession session, boolean flushFirst) {
        synchronized (session.trackLock) {
            if (session.released) {
                return;
            }
            session.released = true;
            try {
                if (flushFirst) {
                    session.track.pause();
                    session.track.flush();
                }
                session.track.stop();
            } catch (Throwable ignored) {
            }
            try {
                session.track.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
