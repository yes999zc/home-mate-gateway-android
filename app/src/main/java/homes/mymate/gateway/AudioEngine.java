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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import homes.mymate.gateway.protocol.MediaPlaybackBufferPolicy;
import homes.mymate.gateway.protocol.NorthboundSession;
import homes.mymate.gateway.protocol.PlaybackStreamDriver;
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
    private static final long INIT_RETRY_MIN_BACKOFF_MS = 500L;
    private static final long INIT_RETRY_MAX_BACKOFF_MS = 5_000L;

    public interface CaptureCallback {
        void onAudioBlockCaptured(byte[] pcm16leBytes, byte[] kwsPcm16leBytes, double rmsDbfs, double noiseFloorDbfs, double snrDb, boolean aecActive);
        void onPlaybackFirstChunk(String taskId);
        void onPlaybackReferenceChunk(byte[] pcm16leBytes, double effectiveLinearGain, long nowMs);
        void onPlaybackActiveChanged(boolean anyActive);
        void onPlaybackSuperseded(String supersededTaskId);
        void onPlaybackFinished(String taskId, long playedMs, String progressBasis);
        void onPlaybackFailed(String taskId, String detail);
        void onAudioFocusGained();
        void onAudioFocusLostMedia(List<String> stoppedTaskIds, List<NorthboundSession.StopPlaybackResult> stopResults);
        void onAudioError(String stage, String message);
    }

    /** Zero-allocation live audio energy hook for the 3D Particle Orb UI (mic, TTS, & beat-reactive music). */
    public interface EnergyListener {
        void onLiveAudioEnergy(double rmsDbfs, double bassDbfs, boolean isMusic);
    }

    private static final class StreamSession {
        final PlaybackStreamDriver.StreamState state;
        final AudioTrack track;
        volatile float currentTrackGain = 1.0f;

        StreamSession(PlaybackStreamDriver.StreamState state, AudioTrack track) {
            this.state = state;
            this.track = track;
        }
    }

    private static PlaybackStreamDriver.TrackHandle wrapAudioTrack(final AudioTrack track) {
        return new PlaybackStreamDriver.TrackHandle() {
            @Override
            public void play() {
                track.play();
            }

            @Override
            public void pause() {
                track.pause();
            }

            @Override
            public void flush() {
                track.flush();
            }

            @Override
            public void stop() {
                track.stop();
            }

            @Override
            public void release() {
                track.release();
            }

            @Override
            public int getPlaybackHeadPosition() {
                return track.getPlaybackHeadPosition();
            }

            @Override
            public int write(byte[] data, int offset, int length) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    return track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING);
                }
                return track.write(data, offset, length);
            }
        };
    }

    private final Context context;
    private final CaptureCallback callback;
    private final SoftwareAecFilter softwareAec = new SoftwareAecFilter();
    // Dedicated KWS-only PBFDAF echo suppressor active during BOTH voice (TTS) and media (music)
    // playback so mid-playback wake/retreat barge-in works cleanly without altering raw uplink PCM.
    private final SoftwareAecFilter kwsPlaybackAec = new SoftwareAecFilter();
    private volatile long lastPlaybackWriteWallMs = 0L;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile boolean waitingForInitRetry = false;
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
                callback.onAudioFocusGained();
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
        boolean swAecChanged = (this.softwareAecEnabled != softwareAecEnabled);
        this.useVoiceCommSource = useVoiceCommSource;
        this.softwareAecEnabled = softwareAecEnabled;
        this.softwareAec.setEnabled(softwareAecEnabled);
        if (swAecChanged || sourceChanged) {
            this.softwareAec.reset();
            this.kwsPlaybackAec.reset();
        }
        this.uplinkGain = Math.max(0.5f, Math.min(6.0f, uplinkGain));
        if (sourceChanged && running) {
            stopCapture();
            startCapture();
        }
    }

    public boolean isHardwareAecEnabled() { return hardwareAecEnabled; }
    public boolean isSoftwareAecEnabled() { return softwareAecEnabled; }
    public double getSoftwareAecErleDb() {
        return softwareAecEnabled ? softwareAec.getLastErleDb() : kwsPlaybackAec.getLastErleDb();
    }
    public double getKwsPlaybackAecErleDb() { return kwsPlaybackAec.getLastErleDb(); }
    public SoftwareAecFilter.AecTelemetry getActiveAecTelemetry() {
        return softwareAecEnabled ? softwareAec.getTelemetrySnapshot() : kwsPlaybackAec.getTelemetrySnapshot();
    }
    public boolean isHardwareNsEnabled() { return hardwareNsEnabled; }
    public double getNoiseFloorDbfs() { return noiseFloorDbfs; }

    public boolean hasActiveAnyPlayback() {
        return hasActiveVoicePlayback() || hasActiveMediaPlayback();
    }

    public boolean hasActiveMediaPlayback() {
        for (StreamSession s : activeStreams.values()) {
            if (!s.state.cancelled && s.state.isMedia) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActiveVoicePlayback() {
        for (StreamSession s : activeStreams.values()) {
            if (!s.state.cancelled && !s.state.isMedia) {
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
        if (running && !stopRequested && captureThread != null && captureThread.isAlive()) {
            return;
        }
        stopRequested = false;
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

    /**
     * Recovers microphone capture after transient AudioRecord contention, focus loss, or late
     * permission grant without interrupting an already-recording capture session.
     */
    public synchronized void ensureCaptureRunning() {
        if (stopRequested) {
            return;
        }
        if (!running || captureThread == null || !captureThread.isAlive()) {
            startCapture();
        } else if (waitingForInitRetry) {
            captureThread.interrupt();
        }
    }

    public synchronized boolean isCaptureRunning() {
        return running && !stopRequested && captureThread != null && captureThread.isAlive();
    }

    /**
     * Cleanly tears down any existing {@code AudioRecord} (such as one silenced during Android 11-14
     * boot-restricted FGS) and creates a fresh recorder on a new capture thread (P1-4).
     */
    public synchronized void restartCapture() {
        stopCapture();
        startCapture();
    }

    public synchronized void stopCapture() {
        stopRequested = true;
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
        long initBackoffMs = INIT_RETRY_MIN_BACKOFF_MS;

        while (running && !stopRequested) {
            if (Thread.currentThread().isInterrupted()) {
                if (stopRequested || !running) {
                    break;
                }
                Thread.interrupted();
            }
            AudioRecord localRecord = null;
            AcousticEchoCanceler localAec = null;
            NoiseSuppressor localNs = null;
            AutomaticGainControl localAgc = null;
            boolean shouldRetryBackoff = false;

            try {
                int audioSource = useVoiceCommSource
                        ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        : MediaRecorder.AudioSource.VOICE_RECOGNITION;

                localRecord = new AudioRecord(
                        audioSource,
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    localRecord.release();
                    localRecord = null;
                    localRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);
                }

                if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    callback.onAudioError("init",
                            "AudioRecord initialization failed (retrying in " + initBackoffMs + "ms)");
                    shouldRetryBackoff = true;
                } else {
                    activeRecorderForUnblock = localRecord;
                    int sessionId = localRecord.getAudioSessionId();
                    hardwareAecEnabled = false;
                    hardwareNsEnabled = false;
                    if (useVoiceCommSource) {
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
                    }
                    // Keep platform NoiseSuppressor OFF: OEM telephony NS aggressively attenuates far-field
                    // and noisy-room voice and clips wake-word onset consonants before local KWS runs.
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            localNs = NoiseSuppressor.create(sessionId);
                            if (localNs != null) {
                                localNs.setEnabled(false);
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
                    initBackoffMs = INIT_RETRY_MIN_BACKOFF_MS;

                    byte[] readBuffer = new byte[BLOCK_BYTES];
                    int consecutiveReadErrors = 0;
                    final int halfBlock = BLOCK_BYTES / 2;

                    while (running && !stopRequested && !Thread.currentThread().isInterrupted()) {
                        int offset = 0;
                        boolean halfEmitted = false;
                        boolean readFailed = false;
                        while (offset < BLOCK_BYTES && running && !stopRequested) {
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
                        if (!running || stopRequested) {
                            break;
                        }
                        if (readFailed || offset < BLOCK_BYTES) {
                            if (consecutiveReadErrors >= 8) {
                                callback.onAudioError("read_recover", "AudioRecord read stalled; re-initializing");
                                shouldRetryBackoff = true;
                                break; // Re-initialize recorder cleanly with bounded backoff
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

                        long captureNowMs = System.currentTimeMillis();
                        if (softwareAecEnabled) {
                            block = softwareAec.processCaptureBlock(block, captureNowMs);
                        }

                        if (Math.abs(uplinkGain - 1.0f) > 0.02f) {
                            applyPcmGainInPlace(block, uplinkGain);
                        }

                        long lastPlayWrite = lastPlaybackWriteWallMs;
                        boolean kwsAecNeeded = !softwareAecEnabled
                                && (hasActiveMediaPlayback()
                                    || hasActiveVoicePlayback()
                                    || (lastPlayWrite > 0L && (captureNowMs - lastPlayWrite) <= 400L));
                        byte[] kwsBlock = kwsAecNeeded
                                ? kwsPlaybackAec.processCaptureBlock(block, captureNowMs)
                                : block;

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

                        callback.onAudioBlockCaptured(block, kwsBlock, rmsDbfs, noiseFloorDbfs, snrDb,
                                hardwareAecEnabled || softwareAecEnabled);
                    }
                }
            } catch (SecurityException se) {
                callback.onAudioError("permission", "RECORD_AUDIO permission not granted: " + se.getMessage());
                running = false;
                return;
            } catch (Throwable t) {
                callback.onAudioError("capture", "Capture error: " + t.getMessage());
                shouldRetryBackoff = true;
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

            if (shouldRetryBackoff && running && !stopRequested) {
                long sleepMs = initBackoffMs;
                initBackoffMs = Math.min(INIT_RETRY_MAX_BACKOFF_MS, initBackoffMs * 2L);
                waitingForInitRetry = true;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    if (stopRequested || !running) {
                        break;
                    }
                    // Woken early by ensureCaptureRunning() (e.g. AUDIOFOCUS_GAIN / Activity resume)
                    Thread.interrupted();
                    initBackoffMs = INIT_RETRY_MIN_BACKOFF_MS;
                } finally {
                    waitingForInitRetry = false;
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
            callback.onPlaybackFinished(taskId, 0L, "written");
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
            if (existing != null && existing.state.isMedia == isMedia && !taskId.equals(existing.state.taskId)) {
                stopStream(existing.state.taskId);
                callback.onPlaybackSuperseded(existing.state.taskId);
            }
        }

        int rate = sampleRate > 0 ? sampleRate : SAMPLE_RATE_HZ;
        int numCh = channels >= 2 ? 2 : 1;
        int frameBytes = 2 * numCh;
        int channelMask = (numCh == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = Math.max(4096, (rate * frameBytes) / 10);
        }

        int bufSize = MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(isMedia, rate, numCh, minBuf);

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
            updateMediaDucking();
            updateFocusAfterStreamChange();
            callback.onPlaybackActiveChanged(hasActiveVoicePlayback() || hasActiveMediaPlayback());
            String err = "AudioTrack create failed: " + t.getMessage();
            callback.onAudioError("playback_init", err);
            callback.onPlaybackFailed(taskId, err);
            return;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            updateMediaDucking();
            updateFocusAfterStreamChange();
            callback.onPlaybackActiveChanged(hasActiveVoicePlayback() || hasActiveMediaPlayback());
            String err = "AudioTrack uninitialized for " + rate + "Hz/" + numCh + "ch";
            callback.onAudioError("playback_init", err);
            callback.onPlaybackFailed(taskId, err);
            return;
        }

        // Register session BEFORE requesting AudioFocus under focusLock so a concurrently
        // finishing stream's updateFocusAfterStreamChange() can never abandon the new focus.
        final PlaybackStreamDriver.StreamState streamState = new PlaybackStreamDriver.StreamState(
                taskId, chName, isMedia, rate, numCh, STREAM_QUEUE_CAPACITY, wrapAudioTrack(track));
        final StreamSession session = new StreamSession(streamState, track);
        synchronized (focusLock) {
            activeStreams.put(taskId, session);
            requestPlaybackAudioFocus(isMedia);
        }
        updateMediaDucking();
        callback.onPlaybackActiveChanged(true);

        // Voice streams start immediately for ultra-low TTS latency; media streams defer
        // track.play() until PlaybackStreamDriver.runWriterLoop() finishes memory pre-buffering.
        if (!isMedia) {
            synchronized (session.state.trackLock) {
                if (!session.state.released) {
                    try {
                        track.play();
                        session.state.trackStarted = true;
                    } catch (Throwable t) {
                        activeStreams.remove(taskId, session);
                        PlaybackStreamDriver.releaseTrackSafely(session.state, true);
                        updateMediaDucking();
                        updateFocusAfterStreamChange();
                        callback.onPlaybackActiveChanged(hasActiveVoicePlayback() || hasActiveMediaPlayback());
                        String err = "AudioTrack.play failed: " + t.getMessage();
                        callback.onAudioError("playback_play", err);
                        callback.onPlaybackFailed(taskId, err);
                        return;
                    }
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
        session.state.writerThread = wt;
        wt.start();
    }

    public void writeStreamChunk(String taskId, final byte[] pcmBytes) {
        if (taskId == null || pcmBytes == null || pcmBytes.length == 0) {
            return;
        }
        final StreamSession session = activeStreams.get(taskId);
        if (session == null || session.state.cancelled) {
            return;
        }
        if (!session.state.chunkQueue.offer(pcmBytes)) {
            // Bounded backpressure: wait briefly rather than silent OOM or hard drop
            try {
                session.state.chunkQueue.offer(pcmBytes, 250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public void endStream(final String taskId) {
        if (taskId == null) {
            return;
        }
        final StreamSession session = activeStreams.get(taskId);
        if (session == null || session.state.cancelled) {
            return;
        }
        // Always mark EOS unconditionally so a full chunkQueue can never wedge
        // runStreamWriterLoop(), AudioTrack, AudioFocus, or play_finished.
        session.state.eosReceived = true;
        if (!session.state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL)) {
            try {
                session.state.chunkQueue.offer(
                        PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL, 250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void runStreamWriterLoop(final StreamSession session) {
        final PlaybackStreamDriver.StreamState st = session.state;
        final long[] lastRefFeedMs = new long[]{0L};
        PlaybackStreamDriver.RunResult result = null;
        try {
            result = PlaybackStreamDriver.runWriterLoop(st, new PlaybackStreamDriver.WriterCallbacks() {
                @Override
                public void onBeforeWriteChunk(byte[] chunk, long nowMs) {
                    if (nowMs - lastRefFeedMs[0] >= 28L) {
                        lastRefFeedMs[0] = nowMs;
                        EnergyListener el = energyListener;
                        if (el != null) {
                            if (st.isMedia) {
                                // Real-time stereo music full-band + low-pass bass energy (~35Hz) for orb rhythm
                                double fullRms = NorthboundSession.computeRmsDbfs(chunk);
                                double bassRms = computeLowPassBassRmsDbfs(chunk, st.channels);
                                el.onLiveAudioEnergy(fullRms, bassRms, true);
                            } else {
                                double ttsRms = NorthboundSession.computeRmsDbfs(chunk);
                                el.onLiveAudioEnergy(ttsRms, ttsRms, false);
                            }
                        }
                    }
                }

                @Override
                public void onPcmWritten(byte[] chunk, int writeStart, int written, long writeNowMs) {
                    lastPlaybackWriteWallMs = writeNowMs;
                    int aecChannel = st.isMedia
                            ? SoftwareAecFilter.CHANNEL_MEDIA
                            : SoftwareAecFilter.CHANNEL_VOICE;
                    float trackGain = session.currentTrackGain;
                    int queuedTrackMs = st.lastQueuedTrackMs;
                    if (softwareAecEnabled) {
                        softwareAec.pushPlaybackReference(
                                aecChannel, chunk, writeStart, written,
                                st.sampleRate, st.channels, trackGain, writeNowMs, queuedTrackMs);
                    } else {
                        kwsPlaybackAec.pushPlaybackReference(
                                aecChannel, chunk, writeStart, written,
                                st.sampleRate, st.channels, trackGain, writeNowMs, queuedTrackMs);
                    }
                    double effGain = getCurrentHardwareVolumeLinear() * session.currentTrackGain;
                    byte[] refSlice = (writeStart == 0 && written == chunk.length)
                            ? chunk
                            : java.util.Arrays.copyOfRange(chunk, writeStart, writeStart + written);
                    callback.onPlaybackReferenceChunk(refSlice, effGain, writeNowMs);
                }

                @Override
                public void onFirstChunkWritten() {
                    callback.onPlaybackFirstChunk(st.taskId);
                }

                @Override
                public void onAudioError(String stage, String message) {
                    callback.onAudioError(stage, message);
                }
            });
        } finally {
            boolean wasCancelled = st.cancelled;
            final long totalPlayedMs = (result != null)
                    ? result.totalPlayedMs
                    : NorthboundSession.computeConsumedAudioMs(st.pcmBytesWritten, st.sampleRate, st.channels);
            activeStreams.remove(st.taskId, session);
            st.chunkQueue.clear();
            PlaybackStreamDriver.releaseTrackSafely(st, wasCancelled);
            updateMediaDucking();
            int stoppedChan = st.isMedia
                    ? SoftwareAecFilter.CHANNEL_MEDIA
                    : SoftwareAecFilter.CHANNEL_VOICE;
            softwareAec.notifyChannelStopped(stoppedChan);
            kwsPlaybackAec.notifyChannelStopped(stoppedChan);
            boolean anyActive = hasActiveAnyPlayback();
            if (!anyActive) {
                softwareAec.notifyPlaybackStopped();
                kwsPlaybackAec.notifyPlaybackStopped();
            }
            callback.onPlaybackActiveChanged(anyActive);
            updateFocusAfterStreamChange();
            if (result != null && result.playbackFailedDetail != null) {
                callback.onPlaybackFailed(st.taskId, result.playbackFailedDetail);
            } else if (result != null && result.reachedEos && !wasCancelled) {
                callback.onPlaybackFinished(st.taskId, totalPlayedMs, result.measuredHwDelay ? "hw_delay" : "written");
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

        NorthboundSession.StopPlaybackResult res = PlaybackStreamDriver.stopAndMeasure(session.state);
        updateMediaDucking();
        int stoppedChan = session.state.isMedia
                ? SoftwareAecFilter.CHANNEL_MEDIA
                : SoftwareAecFilter.CHANNEL_VOICE;
        softwareAec.notifyChannelStopped(stoppedChan);
        kwsPlaybackAec.notifyChannelStopped(stoppedChan);
        boolean anyActiveAfterStop = hasActiveAnyPlayback();
        if (!anyActiveAfterStop) {
            softwareAec.notifyPlaybackStopped();
            kwsPlaybackAec.notifyPlaybackStopped();
        }
        callback.onPlaybackActiveChanged(anyActiveAfterStop);
        updateFocusAfterStreamChange();
        return res;
    }

    public List<String> stopAndFlush() {
        List<String> flushedChannels = new ArrayList<String>();
        boolean hadVoice = false;
        boolean hadMedia = false;
        for (String key : new ArrayList<String>(activeStreams.keySet())) {
            StreamSession session = activeStreams.remove(key);
            if (session != null) {
                if (session.state.isMedia) {
                    hadMedia = true;
                } else {
                    hadVoice = true;
                }
                PlaybackStreamDriver.stopAndMeasure(session.state);
            }
        }
        if (hadVoice) {
            flushedChannels.add("voice");
        }
        if (hadMedia) {
            flushedChannels.add("music");
        }
        if (hadVoice || hadMedia) {
            softwareAec.notifyPlaybackStopped();
            kwsPlaybackAec.notifyPlaybackStopped();
        }
        callback.onPlaybackActiveChanged(false);
        updateFocusAfterStreamChange();
        return flushedChannels;
    }

    private void stopMediaStreamsOnly() {
        List<String> stoppedIds = new ArrayList<String>();
        List<NorthboundSession.StopPlaybackResult> stoppedResults = new ArrayList<NorthboundSession.StopPlaybackResult>();
        for (String key : new ArrayList<String>(activeStreams.keySet())) {
            StreamSession s = activeStreams.get(key);
            if (s != null && s.state.isMedia) {
                NorthboundSession.StopPlaybackResult res = stopStream(key);
                if (res != null && res.found) {
                    stoppedIds.add(key);
                    stoppedResults.add(res);
                }
            }
        }
        if (!stoppedIds.isEmpty()) {
            // NEVER emit play_finished on AUDIOFOCUS_LOSS (that would destroy Core's music session
            // or auto-advance to the next track and steal audio focus back). Delegate clean pause
            // + stop_receipt reporting to GatewayService instead.
            callback.onAudioFocusLostMedia(stoppedIds, stoppedResults);
        }
    }

    private void updateMediaDucking() {
        boolean voiceActive = hasActiveVoicePlayback();
        float mediaGain = (voiceActive || focusDucked) ? 0.20f : 1.0f;
        for (StreamSession s : activeStreams.values()) {
            if (!s.state.cancelled && s.state.isMedia) {
                s.currentTrackGain = mediaGain;
                synchronized (s.state.trackLock) {
                    if (!s.state.released) {
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
                    } else if (!hasActiveMediaPlayback()) {
                        am.abandonAudioFocus(focusChangeListener);
                    }
                    hasVoiceFocus = false;
                }
                if (hasMediaFocus && !hasActiveMediaPlayback()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaFocusRequest != null) {
                        am.abandonAudioFocusRequest(mediaFocusRequest);
                    } else if (!hasActiveVoicePlayback()) {
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
}
