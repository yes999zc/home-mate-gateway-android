package homes.mymate.gateway.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Home Mate Northbound Protocol v2 Session & State Machine (v2.0).
 *
 * v2.0 architectural hardening:
 * - Lock-free transport sends: internal {@code stateLock} guards ONLY fast in-memory state updates,
 *   never socket writes — eliminating bidirectional TCP reader/capture deadlocks on Tailscale jitter.
 * - API 21+ compatible {@link Base64Codec} (zero {@code java.util.Base64} dependency).
 * - Exact hm-core playback & stop receipt contract:
 *   {@code play_receipt("accepted")} -> {@code play_receipt("streaming")} on first DAC write ->
 *   {@code play_receipt("device_confirmed")} + {@code play_finished} on real hardware drain,
 *   and {@code play_receipt("unknown", "superseded by a new stream")} on same-channel preemption.
 * - Post-flush {@code stop_receipt} with real hardware DAC {@code consumed_ms} (or
 *   {@code stage="unknown", detail="not_found"} when task is absent).
 * - Per-channel active task tracking ({@code "voice"} vs {@code "media"}).
 * - Correct {@code local_exit_flushed} BEFORE {@code keyword} ordering on local KWS retreat.
 * - Strict {@code config_apply_v2} capability, boot_id/state_revision fencing, and {@code hello_reject}.
 * - 2Hz coalesced {@code near_end_presence} telemetry and {@code micCapturing}-gated {@code audio_chunk}.
 */
public final class NorthboundSession {

    public static final int PROTOCOL_V2 = 2;
    public static final int MIN_PROTOCOL = 0;
    public static final String GATEWAY_VERSION = "2.3.0";
    public static final String GATEWAY_NAME = "home-mate-gateway-android " + GATEWAY_VERSION;
    public static final String PROFILE_VERSION = "android-v2";
    public static final String AUDIO_PROFILE = "android_farfield_v1";

    private static final long PRESENCE_COALESCE_INTERVAL_MS = 500L;
    private static final int IDLE_PREROLL_CAPACITY_BLOCKS = 20; // 1.6s @ 80ms/block
    private static final int WAKE_PREROLL_FLUSH_BLOCKS = 15;    // 1.2s preroll on wake
    private static final long WAKE_UPLINK_GRACE_MS = 12_000L;
    private static final long IDLE_TAIL_GRACE_MS = 1_500L;

    public interface FrameTransport {
        void sendJsonLine(String jsonLine) throws IOException;
    }

    /** Synchronous hardware playback stop report returned by {@link Listener#onStopPlayback}. */
    public static final class StopPlaybackResult {
        public final boolean found;
        public final long writtenMs;
        public final long consumedMs;
        public final String basis;

        public StopPlaybackResult(boolean found, long writtenMs, long consumedMs, String basis) {
            this.found = found;
            this.writtenMs = Math.max(0L, writtenMs);
            this.consumedMs = Math.max(0L, consumedMs);
            this.basis = (basis != null && !basis.isEmpty()) ? basis : "hw_delay";
        }
    }

    public interface Listener {
        void onHelloAck(long generation, int negotiatedProtocol);
        void onHelloRejected(String reason, int minProtocol, int maxProtocol);
        void onMicSessionChanged(boolean capturing, String mode, String captureEpoch, String ticket, String inputId);
        void onAssistantStateChanged(String state);
        double onVolumeChanged(double volume, String taskId);
        void onMuteChanged(boolean muted, String source);
        void onModeChanged(String duplexMode, boolean lowLatency);
        void onPlayWav(String taskId, String channel, byte[] wavBytes);
        void onPlayStreamBegin(String taskId, String channel, int sampleRate, int channels, String format);
        void onPlayStreamChunk(String taskId, long seq, byte[] pcmBytes);
        void onPlayStreamEnd(String taskId);
        StopPlaybackResult onStopPlayback(String taskId, boolean flush, String reason);
        void onKwsConfigUpdated(double threshold, double score, List<String> keywords);
        void onConfigBlockApplied(String block, Map<String, Object> values);
        void onProtocolFrameLog(String direction, String frameType, String rawJson);
    }

    /** Default no-op listener implementation for tests or headless sessions. */
    public static class BaseListener implements Listener {
        @Override public void onHelloAck(long generation, int negotiatedProtocol) {}
        @Override public void onHelloRejected(String reason, int minProtocol, int maxProtocol) {}
        @Override public void onMicSessionChanged(boolean capturing, String mode, String captureEpoch, String ticket, String inputId) {}
        @Override public void onAssistantStateChanged(String state) {}
        @Override public double onVolumeChanged(double volume, String taskId) { return volume; }
        @Override public void onMuteChanged(boolean muted, String source) {}
        @Override public void onModeChanged(String duplexMode, boolean lowLatency) {}
        @Override public void onPlayWav(String taskId, String channel, byte[] wavBytes) {}
        @Override public void onPlayStreamBegin(String taskId, String channel, int sampleRate, int channels, String format) {}
        @Override public void onPlayStreamChunk(String taskId, long seq, byte[] pcmBytes) {}
        @Override public void onPlayStreamEnd(String taskId) {}
        @Override public StopPlaybackResult onStopPlayback(String taskId, boolean flush, String reason) { return null; }
        @Override public void onKwsConfigUpdated(double threshold, double score, List<String> keywords) {}
        @Override public void onConfigBlockApplied(String block, Map<String, Object> values) {}
        @Override public void onProtocolFrameLog(String direction, String frameType, String rawJson) {}
    }

    private final Object stateLock = new Object();
    private final String deviceId;
    private final String deviceModel;
    private final String firmwareVersion;
    private final FrameTransport transport;
    private final Listener listener;
    private final AtomicLong seqCounter = new AtomicLong(1);
    private final AtomicLong micEpochCounter = new AtomicLong(1);
    private final String gatewayBootId;

    // Session state
    private volatile boolean helloAckReceived = false;
    private volatile long generation = 0;
    private volatile int negotiatedProtocol = PROTOCOL_V2;
    private volatile long clockOffsetMs = 0;
    private volatile long stateRevision = 1;
    private volatile int aecTailMs = 0;
    private volatile String authToken = "";

    // Idle-mic uplink gate & local PCM preroll ring buffer (eliminates idle "嗯。" ASR loops)
    private static final class PrerollBlock {
        final byte[] pcm;
        final double rmsDbfs;
        final double noiseFloorDbfs;

        PrerollBlock(byte[] pcm, double rmsDbfs, double noiseFloorDbfs) {
            this.pcm = pcm;
            this.rmsDbfs = rmsDbfs;
            this.noiseFloorDbfs = noiseFloorDbfs;
        }
    }

    private volatile boolean localOfflineKwsReady = false;
    private volatile long uplinkOpenUntilMs = 0L;
    private final java.util.ArrayDeque<PrerollBlock> idlePrerollRing = new java.util.ArrayDeque<PrerollBlock>(IDLE_PREROLL_CAPACITY_BLOCKS);

    // Microphone session state
    private volatile boolean micCapturing = false;
    private volatile String micMode = "continuous";
    private volatile String captureEpoch = "epoch-1";
    private volatile String captureTicket = "ticket-1";
    private volatile String captureInputId = "input-1";
    private volatile long nextSampleCursor = 0;
    private volatile long lastPresenceSentMs = 0L;

    // Device control & acoustic state
    private volatile boolean muted = false;
    private volatile double volumeLinear = 0.65;
    private volatile String duplexMode = "half";
    private volatile boolean lowLatency = false;
    private volatile String assistantState = "idle";
    private volatile double kwsThreshold = 0.28;
    private volatile double kwsScore = 2.60;
    private volatile String kwsLoadedHash = "a1b2c3d4e5f60718";
    private volatile String kwsIntentsHash = "f0e1d2c3b4a59687";
    private final List<String> wakeKeywords = new ArrayList<String>(Arrays.asList("你好小智", "小爱同学", "你好高达"));
    private final List<String> retreatKeywords = new ArrayList<String>(LocalKwsEngine.DEFAULT_RETREAT_WORDS);

    // Active playback tracking per task_id & per normalized channel ("voice" | "media")
    public static final class PlayTaskState {
        public final String taskId;
        public final String channel;
        public final int sampleRate;
        public final int channels;
        public final long startedMs;
        public volatile long bytesConsumed;

        public PlayTaskState(String taskId, String channel, int sampleRate, int channels, long startedMs, long bytesConsumed) {
            this.taskId = taskId;
            this.channel = normalizeChannel(channel);
            this.sampleRate = sampleRate > 0 ? sampleRate : 16000;
            this.channels = channels >= 2 ? 2 : 1;
            this.startedMs = startedMs;
            this.bytesConsumed = bytesConsumed;
        }
    }

    private final Map<String, PlayTaskState> activePlays = new LinkedHashMap<String, PlayTaskState>();
    private final Map<String, String> activeTaskByChannel = new LinkedHashMap<String, String>();

    // Config v2 blocks (kws, audio_eq, led, duplex, acoustics)
    private final Map<String, Map<String, Object>> configBlocks = new LinkedHashMap<String, Map<String, Object>>();
    private final Map<String, Long> blockGenerations = new LinkedHashMap<String, Long>();
    private volatile LocalKwsEngine localKwsEngine = null;

    public void attachLocalKwsEngine(LocalKwsEngine engine) {
        this.localKwsEngine = engine;
    }

    public NorthboundSession(
            String deviceId,
            String deviceModel,
            String firmwareVersion,
            FrameTransport transport,
            Listener listener) {
        this.deviceId = (deviceId == null || deviceId.trim().isEmpty()) ? "android-panel-1" : deviceId.trim();
        this.deviceModel = (deviceModel == null || deviceModel.trim().isEmpty()) ? "android-smart-panel" : deviceModel.trim();
        this.firmwareVersion = (firmwareVersion == null || firmwareVersion.trim().isEmpty()) ? GATEWAY_VERSION : firmwareVersion.trim();
        this.transport = transport;
        this.listener = listener != null ? listener : new BaseListener();
        this.gatewayBootId = "android-boot-" + System.currentTimeMillis();
        initDefaultConfigBlocks();
    }

    private void initDefaultConfigBlocks() {
        Map<String, Object> kws = new LinkedHashMap<String, Object>();
        kws.put("threshold", Double.valueOf(kwsThreshold));
        kws.put("score", Double.valueOf(kwsScore));
        kws.put("wake_keywords", new ArrayList<String>(wakeKeywords));
        kws.put("exit_keywords", new ArrayList<String>(retreatKeywords));
        configBlocks.put("kws", kws);
        blockGenerations.put("kws", Long.valueOf(1L));

        Map<String, Object> eq = new LinkedHashMap<String, Object>();
        eq.put("curve", "official");
        eq.put("bands", new ArrayList<Object>());
        configBlocks.put("audio_eq", eq);
        blockGenerations.put("audio_eq", Long.valueOf(1L));

        Map<String, Object> led = new LinkedHashMap<String, Object>();
        led.put("night_enabled", Boolean.FALSE);
        led.put("night_brightness_pct", Integer.valueOf(80));
        led.put("night_start_minute", Integer.valueOf(22 * 60));
        led.put("night_end_minute", Integer.valueOf(7 * 60));
        led.put("orientation_rotation", Integer.valueOf(0));
        led.put("orientation_reverse", Boolean.FALSE);
        configBlocks.put("led", led);
        blockGenerations.put("led", Long.valueOf(1L));

        Map<String, Object> duplex = new LinkedHashMap<String, Object>();
        duplex.put("mode", duplexMode);
        duplex.put("low_latency", Boolean.valueOf(lowLatency));
        configBlocks.put("duplex", duplex);
        blockGenerations.put("duplex", Long.valueOf(1L));

        Map<String, Object> acoustics = new LinkedHashMap<String, Object>();
        acoustics.put("volume_cap", Double.valueOf(1.0));
        acoustics.put("uplink_gain", Double.valueOf(1.0));
        configBlocks.put("acoustics", acoustics);
        blockGenerations.put("acoustics", Long.valueOf(1L));
    }

    public String getDeviceId() { return deviceId; }
    public String getGatewayBootId() { return gatewayBootId; }
    public long getStateRevision() { return stateRevision; }
    public boolean isHelloAckReceived() { return helloAckReceived; }
    public boolean isMicCapturing() { return micCapturing; }
    public boolean isMuted() { return muted; }
    public double getVolumeLinear() { return volumeLinear; }
    public String getDuplexMode() { return duplexMode; }
    public String getAssistantState() { return assistantState; }
    public double getKwsThreshold() { return kwsThreshold; }
    public double getKwsScore() { return kwsScore; }

    public void setAecTailMs(int tailMs) {
        this.aecTailMs = Math.max(0, tailMs);
    }

    public void setAuthToken(String token) {
        this.authToken = token != null ? token.trim() : "";
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setLocalOfflineKwsReady(boolean ready) {
        this.localOfflineKwsReady = ready;
        if (!ready) {
            synchronized (stateLock) {
                idlePrerollRing.clear();
            }
        }
    }

    public boolean isLocalOfflineKwsReady() {
        return localOfflineKwsReady;
    }

    /**
     * Returns whether PCM {@code audio_chunk} uplink streaming should be open right now.
     * When on-device offline KWS is ready and the assistant is completely idle (no active turn,
     * no wake grace window, and no full-duplex playback barge-in), uplink stays closed while
     * recent PCM is buffered locally — preventing quiet-room noise from triggering spurious ASR.
     */
    public boolean isUplinkAudioOpen(boolean allowBargeInPlayback, long nowWallMs) {
        if (!localOfflineKwsReady) {
            return true;
        }
        if (allowBargeInPlayback) {
            return true;
        }
        String st = this.assistantState;
        if (st != null && !"idle".equalsIgnoreCase(st)) {
            return true;
        }
        return nowWallMs <= this.uplinkOpenUntilMs;
    }

    /**
     * Opens the idle-mic uplink gate on local KWS or manual wake and flushes recent local PCM
     * preroll blocks so one-breath utterances immediately after the wake word are preserved.
     */
    public int notifyWakeForUplinkAndFlushPreroll(long nowWallMs) throws IOException {
        this.uplinkOpenUntilMs = Math.max(this.uplinkOpenUntilMs, nowWallMs + WAKE_UPLINK_GRACE_MS);
        List<PrerollBlock> toFlush = new ArrayList<PrerollBlock>();
        synchronized (stateLock) {
            while (idlePrerollRing.size() > WAKE_PREROLL_FLUSH_BLOCKS) {
                idlePrerollRing.pollFirst();
            }
            while (!idlePrerollRing.isEmpty()) {
                toFlush.add(idlePrerollRing.pollFirst());
            }
        }
        int sent = 0;
        for (PrerollBlock b : toFlush) {
            sendAudioChunkAndPresence(b.pcm, b.rmsDbfs, b.noiseFloorDbfs);
            sent++;
        }
        return sent;
    }

    public void closeUplinkGateImmediately() {
        this.uplinkOpenUntilMs = 0L;
        synchronized (stateLock) {
            idlePrerollRing.clear();
        }
    }

    /**
     * Gate-aware PCM uplink entry point used by {@code GatewayService}. Returns {@code true}
     * if an {@code audio_chunk} frame was transmitted, or {@code false} if retained locally
     * in the idle preroll ring buffer.
     */
    public boolean sendAudioChunkWithGate(
            byte[] pcm16leBytes,
            double rmsDbfs,
            double noiseFloorDbfs,
            boolean allowBargeInPlayback,
            long nowWallMs) throws IOException {
        if (pcm16leBytes == null || pcm16leBytes.length == 0 || muted || !micCapturing) {
            return false;
        }
        if (!isUplinkAudioOpen(allowBargeInPlayback, nowWallMs)) {
            synchronized (stateLock) {
                while (idlePrerollRing.size() >= IDLE_PREROLL_CAPACITY_BLOCKS) {
                    idlePrerollRing.pollFirst();
                }
                idlePrerollRing.addLast(new PrerollBlock(pcm16leBytes, rmsDbfs, noiseFloorDbfs));
            }
            return false;
        }
        sendAudioChunkAndPresence(pcm16leBytes, rmsDbfs, noiseFloorDbfs);
        return true;
    }

    public void setInitialVolumeLinear(double linearVolume) {
        this.volumeLinear = Math.max(0.0, Math.min(1.0, linearVolume));
    }

    public void setKwsSensitivity(double threshold, double score) {
        synchronized (stateLock) {
            this.kwsThreshold = threshold;
            this.kwsScore = score;
            Map<String, Object> kws = configBlocks.get("kws");
            if (kws != null) {
                kws.put("threshold", Double.valueOf(threshold));
                kws.put("score", Double.valueOf(score));
            }
        }
    }

    public long nowMs() {
        return System.currentTimeMillis() + clockOffsetMs;
    }

    private long nextSeq() {
        return seqCounter.getAndIncrement();
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.trim().isEmpty()) {
            return "voice";
        }
        String c = channel.trim().toLowerCase(Locale.US);
        if ("media".equals(c) || "music".equals(c)) {
            return "media";
        }
        return "voice";
    }

    /**
     * Step 1: Send the Northbound v2 {@code hello} handshake frame immediately after TCP connect.
     */
    public void sendHello() throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "hello");
        frame.put("protocol", Integer.valueOf(PROTOCOL_V2));
        frame.put("min_protocol", Integer.valueOf(MIN_PROTOCOL));
        frame.put("supported_versions", Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)));
        frame.put("gateway", GATEWAY_NAME);
        frame.put("gateway_version", GATEWAY_VERSION);
        frame.put("aec_tail_ms", Integer.valueOf(aecTailMs));
        frame.put("profile_version", PROFILE_VERSION);
        String tok = this.authToken;
        if (tok != null && !tok.isEmpty()) {
            frame.put("auth_token", tok);
        }

        Map<String, Object> dev = new LinkedHashMap<String, Object>();
        dev.put("stable_id", deviceId);
        dev.put("model", deviceModel);
        dev.put("firmware", firmwareVersion);
        dev.put("capabilities", Arrays.asList(
                "audio_in",
                "mic_input",
                "audio_out",
                "play_stream",
                "button",
                "led",
                "volume",
                "volume_control",
                "remote_mute",
                "kws_reconfigure",
                "audio_eq",
                "led_config",
                "config_apply_v2",
                "config_block_kws",
                "config_block_audio_eq",
                "config_block_led",
                "config_block_duplex",
                "config_block_acoustics"
        ));
        dev.put("audio_profile", AUDIO_PROFILE);

        List<Object> devices = new ArrayList<Object>(1);
        devices.add(dev);
        frame.put("devices", devices);

        sendFrame(frame);
    }

    /**
     * Step 2: Send initial device state reports right after {@code hello_ack}.
     */
    public void sendInitialReports() throws IOException {
        sendModeReport();
        sendMuteState("device_boot", null);
        sendVolumeReceipt("vol:boot:1", volumeLinear, "device_confirmed");
        LocalKwsEngine eng = this.localKwsEngine;
        String kwsState;
        String kwsDetail;
        if (eng == null) {
            kwsState = "running";
            kwsDetail = String.format(Locale.US, "ready (threshold=%.2f score=%.2f)", kwsThreshold, kwsScore);
        } else if (eng.isProcessReady()) {
            kwsState = "running";
            kwsDetail = String.format(Locale.US, "%s ready (threshold=%.2f score=%.2f)",
                    eng.getActiveEngineMode(), kwsThreshold, kwsScore);
        } else {
            kwsState = "starting";
            kwsDetail = "initializing on-device KWS";
        }
        sendKwsReport(kwsState, kwsDetail);
        sendEchoHealth("healthy", 0L);
        sendConfigState(null);
    }

    /**
     * Handle an incoming newline-delimited JSON frame from {@code hm-core}.
     */
    public void handleIncomingLine(String line) throws IOException {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        Map<String, Object> frame = MiniJson.parseObject(trimmed);
        String type = MiniJson.getString(frame, "type", "");
        listener.onProtocolFrameLog("IN", type, trimmed);

        switch (type) {
            case "hello_ack":
                onHelloAckFrame(frame);
                break;
            case "hello_reject":
                onHelloRejectFrame(frame);
                break;
            case "time_set":
                onTimeSetFrame(frame);
                break;
            case "mic_session":
                onMicSessionFrame(frame);
                break;
            case "mode_set":
                onModeSetFrame(frame);
                break;
            case "mute_set":
                onMuteSetFrame(frame);
                break;
            case "volume_set":
                onVolumeSetFrame(frame);
                break;
            case "play":
                onPlayFrame(frame);
                break;
            case "play_begin":
                onPlayBeginFrame(frame);
                break;
            case "play_chunk":
                onPlayChunkFrame(frame);
                break;
            case "play_end":
                onPlayEndFrame(frame);
                break;
            case "stop":
                onStopFrame(frame);
                break;
            case "assistant_state":
                onAssistantStateFrame(frame);
                break;
            case "kws_deploy":
                onKwsDeployFrame(frame);
                break;
            case "audio_eq":
                onAudioEqFrame(frame);
                break;
            case "led_config":
                onLedConfigFrame(frame);
                break;
            case "config_state_get":
                onConfigStateGetFrame(frame);
                break;
            case "config_validate":
                onConfigValidateFrame(frame);
                break;
            case "config_apply":
                onConfigApplyFrame(frame);
                break;
            default:
                break;
        }
    }

    private void onHelloAckFrame(Map<String, Object> frame) throws IOException {
        this.negotiatedProtocol = MiniJson.getInt(frame, "protocol", PROTOCOL_V2);
        this.generation = MiniJson.getLong(frame, "generation", 1L);
        this.helloAckReceived = true;
        sendInitialReports();
        listener.onHelloAck(generation, negotiatedProtocol);
    }

    private void onHelloRejectFrame(Map<String, Object> frame) {
        String reason = MiniJson.getString(frame, "reason", "rejected");
        int minP = MiniJson.getInt(frame, "min_protocol", 0);
        int maxP = frame.containsKey("protocol")
                ? MiniJson.getInt(frame, "protocol", PROTOCOL_V2)
                : MiniJson.getInt(frame, "max_protocol", PROTOCOL_V2);
        this.helloAckReceived = false;
        listener.onHelloRejected(reason, minP, maxP);
    }

    private void onTimeSetFrame(Map<String, Object> frame) {
        long coreTsMs = MiniJson.getLong(frame, "ts_ms", System.currentTimeMillis());
        this.clockOffsetMs = coreTsMs - System.currentTimeMillis();
    }

    private void onMicSessionFrame(Map<String, Object> frame) throws IOException {
        String action = MiniJson.getString(frame, "action", "start");
        String mode = MiniJson.getString(frame, "mode", "continuous");
        long epochId = micEpochCounter.getAndIncrement();

        boolean capturing;
        String epoch;
        String ticket;
        String inputId;
        synchronized (stateLock) {
            this.micMode = mode;
            if ("start".equalsIgnoreCase(action)) {
                this.micCapturing = true;
                this.captureEpoch = "epoch-" + epochId;
                this.captureTicket = "ticket-" + epochId;
                this.captureInputId = "input-" + epochId;
                this.nextSampleCursor = 0;
            } else if ("stop".equalsIgnoreCase(action)) {
                this.micCapturing = false;
            }
            capturing = this.micCapturing;
            epoch = this.captureEpoch;
            ticket = this.captureTicket;
            inputId = this.captureInputId;
        }
        sendMicReport(capturing);
        listener.onMicSessionChanged(capturing, mode, epoch, ticket, inputId);
    }

    private void onModeSetFrame(Map<String, Object> frame) throws IOException {
        String nextDuplex = MiniJson.getString(frame, "duplex_requested",
                MiniJson.getString(frame, "duplex_mode", this.duplexMode));
        boolean nextLowLatency = MiniJson.getBoolean(frame, "low_latency", this.lowLatency);
        synchronized (stateLock) {
            this.duplexMode = nextDuplex;
            this.lowLatency = nextLowLatency;
            Map<String, Object> duplexBlock = configBlocks.get("duplex");
            if (duplexBlock != null) {
                duplexBlock.put("mode", nextDuplex);
                duplexBlock.put("low_latency", Boolean.valueOf(nextLowLatency));
            }
        }
        sendModeReport();
        listener.onModeChanged(nextDuplex, nextLowLatency);
    }

    private void onMuteSetFrame(Map<String, Object> frame) throws IOException {
        boolean nextMuted = MiniJson.getBoolean(frame, "muted", false);
        String taskId = MiniJson.getString(frame, "task_id", null);
        synchronized (stateLock) {
            this.muted = nextMuted;
        }
        sendMuteState("remote_command", taskId);
        listener.onMuteChanged(nextMuted, "remote_command");
    }

    private void onVolumeSetFrame(Map<String, Object> frame) throws IOException {
        String taskId = MiniJson.getString(frame, "task_id", "vol:" + nowMs());
        double vol = MiniJson.getDouble(frame, "value",
                MiniJson.getDouble(frame, "volume", this.volumeLinear));
        double clamped = Math.max(0.0, Math.min(1.0, vol));
        sendVolumeReceipt(taskId, clamped, "accepted");
        double actual = listener.onVolumeChanged(clamped, taskId);
        double effective = Math.max(0.0, Math.min(1.0, Double.isNaN(actual) ? clamped : actual));
        synchronized (stateLock) {
            this.volumeLinear = effective;
        }
        sendVolumeReceipt(taskId, effective, "device_confirmed");
    }

    private String resolveFallbackTaskIdLocked() {
        String voiceTask = activeTaskByChannel.get("voice");
        if (voiceTask != null && activePlays.containsKey(voiceTask)) {
            return voiceTask;
        }
        String mediaTask = activeTaskByChannel.get("media");
        if (mediaTask != null && activePlays.containsKey(mediaTask)) {
            return mediaTask;
        }
        return null;
    }

    private void onPlayFrame(Map<String, Object> frame) throws IOException {
        String taskId = MiniJson.getString(frame, "task_id", "play:" + nowMs());
        String channel = normalizeChannel(MiniJson.getString(frame, "channel", "voice"));
        Map<String, Object> audioMeta = MiniJson.getObject(frame, "audio");
        String audioB64 = MiniJson.getString(frame, "audio_b64", "");
        byte[] wavBytes = audioB64.isEmpty() ? new byte[0] : Base64Codec.decode(audioB64);
        byte[] pcmBytes = stripWavHeaderToPcm16le(wavBytes);
        int metaRate = audioMeta != null ? MiniJson.getInt(audioMeta, "sample_rate", 16000) : 16000;
        int metaChannels = audioMeta != null ? MiniJson.getInt(audioMeta, "channels", 1) : 1;
        int sampleRate = parseWavSampleRate(wavBytes, metaRate);
        int channels = parseWavChannels(wavBytes, metaChannels);

        String supersededTask = null;
        synchronized (stateLock) {
            String prev = activeTaskByChannel.get(channel);
            if (prev != null && !prev.equals(taskId) && activePlays.containsKey(prev)) {
                supersededTask = prev;
            }
            PlayTaskState state = new PlayTaskState(taskId, channel, sampleRate, channels, nowMs(), pcmBytes.length);
            activePlays.put(taskId, state);
            activeTaskByChannel.put(channel, taskId);
        }
        if (supersededTask != null) {
            sendPlaySuperseded(supersededTask);
        }
        sendPlayReceipt(taskId, "accepted", null);
        listener.onPlayWav(taskId, channel, wavBytes);
    }

    private void onPlayBeginFrame(Map<String, Object> frame) throws IOException {
        String taskId = MiniJson.getString(frame, "task_id", "stream:" + nowMs());
        String channel = normalizeChannel(MiniJson.getString(frame, "channel", "voice"));
        Map<String, Object> audioMeta = MiniJson.getObject(frame, "audio");
        int sampleRate = audioMeta != null
                ? MiniJson.getInt(audioMeta, "sample_rate", 16000)
                : MiniJson.getInt(frame, "sample_rate", 16000);
        int channels = audioMeta != null
                ? MiniJson.getInt(audioMeta, "channels", 1)
                : MiniJson.getInt(frame, "channels", 1);
        String format = audioMeta != null
                ? MiniJson.getString(audioMeta, "format", "s16le")
                : MiniJson.getString(frame, "format", "s16le");

        String supersededTask = null;
        synchronized (stateLock) {
            String prev = activeTaskByChannel.get(channel);
            if (prev != null && !prev.equals(taskId) && activePlays.containsKey(prev)) {
                supersededTask = prev;
            }
            PlayTaskState state = new PlayTaskState(taskId, channel, sampleRate, channels, nowMs(), 0L);
            activePlays.put(taskId, state);
            activeTaskByChannel.put(channel, taskId);
        }
        if (supersededTask != null) {
            sendPlaySuperseded(supersededTask);
        }
        sendPlayReceipt(taskId, "accepted", null);
        listener.onPlayStreamBegin(taskId, channel, sampleRate, channels, format);
    }

    private void onPlayChunkFrame(Map<String, Object> frame) {
        String rawTaskId = MiniJson.getString(frame, "task_id", null);
        long seq = MiniJson.getLong(frame, "seq", 0L);
        String b64 = MiniJson.getString(frame, "audio_b64",
                MiniJson.getString(frame, "pcm_b64", ""));
        byte[] pcmBytes = b64.isEmpty() ? new byte[0] : Base64Codec.decode(b64);
        String taskId;
        synchronized (stateLock) {
            taskId = (rawTaskId != null && !rawTaskId.isEmpty()) ? rawTaskId : resolveFallbackTaskIdLocked();
            PlayTaskState state = taskId != null ? this.activePlays.get(taskId) : null;
            if (state != null) {
                state.bytesConsumed += pcmBytes.length;
            }
        }
        listener.onPlayStreamChunk(taskId, seq, pcmBytes);
    }

    private void onPlayEndFrame(Map<String, Object> frame) {
        String rawTaskId = MiniJson.getString(frame, "task_id", null);
        String taskId;
        synchronized (stateLock) {
            taskId = (rawTaskId != null && !rawTaskId.isEmpty()) ? rawTaskId : resolveFallbackTaskIdLocked();
        }
        listener.onPlayStreamEnd(taskId);
    }

    private void onStopFrame(Map<String, Object> frame) throws IOException {
        String rawTaskId = MiniJson.getString(frame, "task_id", null);
        boolean flush = MiniJson.getBoolean(frame, "flush", true);
        String reason = MiniJson.getString(frame, "reason", "core_stop");

        String taskId;
        PlayTaskState removedState;
        synchronized (stateLock) {
            if (rawTaskId != null && !rawTaskId.isEmpty()) {
                taskId = rawTaskId;
            } else {
                String fallback = resolveFallbackTaskIdLocked();
                taskId = fallback != null ? fallback : "stop:0";
            }
            removedState = this.activePlays.remove(taskId);
            if (removedState != null) {
                String chTask = activeTaskByChannel.get(removedState.channel);
                if (taskId.equals(chTask)) {
                    activeTaskByChannel.remove(removedState.channel);
                }
            }
        }

        // First flush hardware synchronously so DAC position is measured BEFORE confirming
        StopPlaybackResult hwResult = listener.onStopPlayback(taskId, flush, reason);

        if (hwResult != null && hwResult.found) {
            sendStopReceipt(taskId, "accepted", hwResult.writtenMs, hwResult.consumedMs, hwResult.basis, null);
            sendStopReceipt(taskId, "device_confirmed", hwResult.writtenMs, hwResult.consumedMs, hwResult.basis, null);
        } else if (removedState != null) {
            long fallbackMs = computeConsumedAudioMs(removedState.bytesConsumed, removedState.sampleRate, removedState.channels);
            sendStopReceipt(taskId, "accepted", fallbackMs, fallbackMs, "written", null);
            sendStopReceipt(taskId, "device_confirmed", fallbackMs, fallbackMs, "written", null);
        } else {
            sendStopReceiptUnknown(taskId, "not_found");
        }
    }

    private void onAssistantStateFrame(Map<String, Object> frame) {
        String state = MiniJson.getString(frame, "state", "idle");
        String prev = this.assistantState;
        this.assistantState = state;
        if ("idle".equalsIgnoreCase(state)) {
            if (prev != null && !"idle".equalsIgnoreCase(prev)) {
                this.uplinkOpenUntilMs = System.currentTimeMillis() + IDLE_TAIL_GRACE_MS;
            }
        } else {
            this.uplinkOpenUntilMs = Math.max(this.uplinkOpenUntilMs, System.currentTimeMillis() + WAKE_UPLINK_GRACE_MS);
        }
        listener.onAssistantStateChanged(state);
    }

    private void onKwsDeployFrame(Map<String, Object> frame) throws IOException {
        String keywordsText = MiniJson.getString(frame, "keywords",
                MiniJson.getString(frame, "keywords_txt", ""));
        String intentsHash = MiniJson.getString(frame, "intents_hash", this.kwsIntentsHash);
        double nextThreshold = MiniJson.getDouble(frame, "threshold", this.kwsThreshold);
        double nextScore = MiniJson.getDouble(frame, "score", this.kwsScore);
        boolean hasRetreatField = frame.containsKey("retreat_words");
        List<Object> rawRetreatWords = hasRetreatField ? MiniJson.getArray(frame, "retreat_words") : null;

        List<String> parsedKeywords = new ArrayList<String>();
        int keywordLineCount = 0;
        if (!keywordsText.trim().isEmpty()) {
            String[] lines = keywordsText.split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                keywordLineCount++;
                int atIdx = t.lastIndexOf('@');
                if (atIdx >= 0 && atIdx + 1 < t.length()) {
                    parsedKeywords.add(t.substring(atIdx + 1).trim());
                } else {
                    String[] parts = t.split("\\s+");
                    parsedKeywords.add(parts[parts.length - 1]);
                }
            }
        }

        List<String> nextRetreatList;
        synchronized (stateLock) {
            if (hasRetreatField) {
                this.retreatKeywords.clear();
                if (rawRetreatWords != null) {
                    for (Object rw : rawRetreatWords) {
                        if (rw != null && !String.valueOf(rw).trim().isEmpty()) {
                            this.retreatKeywords.add(String.valueOf(rw).trim());
                        }
                    }
                }
            }
            nextRetreatList = new ArrayList<String>(this.retreatKeywords);
            if (keywordLineCount == 0) {
                keywordLineCount = wakeKeywords.size() + retreatKeywords.size();
            }
        }

        boolean accepted = true;
        String detail = "applied";
        String fnvHash = keywordsText.trim().isEmpty() ? this.kwsLoadedHash : fnv1a64Hex(keywordsText);
        LocalKwsEngine engine = this.localKwsEngine;

        if (engine != null) {
            if (!keywordsText.trim().isEmpty()) {
                LocalKwsEngine.ValidationResult vr = engine.deployKeywords(
                        keywordsText,
                        nextRetreatList,
                        nextThreshold,
                        nextScore);
                accepted = vr.valid;
                detail = vr.detail;
                if (vr.keywordCount > 0) {
                    keywordLineCount = vr.keywordCount;
                }
                if (accepted) {
                    String diskHash = engine.getCurrentKeywordsHash();
                    if (diskHash != null && !diskHash.isEmpty()) {
                        fnvHash = diskHash;
                    }
                }
            } else {
                // Apply sensitivity and retreat words even when keywords table is omitted
                engine.setRetreatWords(nextRetreatList);
                engine.setSensitivity(nextThreshold, nextScore);
            }
        }

        List<String> wakeSnapshot;
        int retreatCount;
        synchronized (stateLock) {
            if (accepted) {
                this.kwsLoadedHash = fnvHash;
                this.kwsIntentsHash = intentsHash;
                this.kwsThreshold = nextThreshold;
                this.kwsScore = nextScore;
                if (!parsedKeywords.isEmpty()) {
                    this.wakeKeywords.clear();
                    this.wakeKeywords.addAll(parsedKeywords);
                }
                Map<String, Object> kwsBlock = configBlocks.get("kws");
                if (kwsBlock != null) {
                    kwsBlock.put("threshold", Double.valueOf(this.kwsThreshold));
                    kwsBlock.put("score", Double.valueOf(this.kwsScore));
                    kwsBlock.put("wake_keywords", new ArrayList<String>(this.wakeKeywords));
                    kwsBlock.put("exit_keywords", new ArrayList<String>(this.retreatKeywords));
                }
            }
            wakeSnapshot = new ArrayList<String>(this.wakeKeywords);
            retreatCount = this.retreatKeywords.size();
        }

        sendKwsDeployReceipt(accepted, accepted ? fnvHash : "", keywordLineCount, detail, retreatCount, intentsHash);
        // Note: Do NOT emit premature kws_report("running") when LocalKwsEngine is attached —
        // LocalKwsEngine emits truthful kws_report("running") once its native JNI hot-reload completes.
        if (engine == null && accepted) {
            sendKwsReport("running", String.format(Locale.US, "ready (threshold=%.2f score=%.2f)", this.kwsThreshold, this.kwsScore));
        }
        listener.onKwsConfigUpdated(this.kwsThreshold, this.kwsScore, wakeSnapshot);
    }

    private void onAudioEqFrame(Map<String, Object> frame) {
        String curve = MiniJson.getString(frame, "curve", "official");
        List<Object> bands = MiniJson.getArray(frame, "bands");
        Map<String, Object> snapshot;
        synchronized (stateLock) {
            Map<String, Object> eq = configBlocks.get("audio_eq");
            if (eq != null) {
                eq.put("curve", curve);
                if (bands != null) {
                    eq.put("bands", bands);
                }
                snapshot = new LinkedHashMap<String, Object>(eq);
            } else {
                snapshot = null;
            }
        }
        if (snapshot != null) {
            listener.onConfigBlockApplied("audio_eq", snapshot);
        }
    }

    private void onLedConfigFrame(Map<String, Object> frame) {
        Map<String, Object> snapshot;
        synchronized (stateLock) {
            Map<String, Object> led = configBlocks.get("led");
            if (led != null) {
                if (frame.containsKey("night_enabled")) {
                    led.put("night_enabled", Boolean.valueOf(MiniJson.getBoolean(frame, "night_enabled", false)));
                }
                if (frame.containsKey("night_brightness_pct")) {
                    led.put("night_brightness_pct", Integer.valueOf(MiniJson.getInt(frame, "night_brightness_pct", 80)));
                }
                if (frame.containsKey("night_start_minute")) {
                    led.put("night_start_minute", Integer.valueOf(MiniJson.getInt(frame, "night_start_minute", 22 * 60)));
                }
                if (frame.containsKey("night_end_minute")) {
                    led.put("night_end_minute", Integer.valueOf(MiniJson.getInt(frame, "night_end_minute", 7 * 60)));
                }
                if (frame.containsKey("orientation_rotation")) {
                    led.put("orientation_rotation", Integer.valueOf(MiniJson.getInt(frame, "orientation_rotation", 0)));
                }
                if (frame.containsKey("orientation_reverse")) {
                    led.put("orientation_reverse", Boolean.valueOf(MiniJson.getBoolean(frame, "orientation_reverse", false)));
                }
                snapshot = new LinkedHashMap<String, Object>(led);
            } else {
                snapshot = null;
            }
        }
        if (snapshot != null) {
            listener.onConfigBlockApplied("led", snapshot);
        }
    }

    private void onConfigStateGetFrame(Map<String, Object> frame) throws IOException {
        String requestId = MiniJson.getString(frame, "request_id", null);
        sendConfigState(requestId);
    }

    private void onConfigValidateFrame(Map<String, Object> frame) throws IOException {
        String requestId = MiniJson.getString(frame, "request_id",
                MiniJson.getString(frame, "tx_id", "req:" + nowMs()));
        String block = MiniJson.getString(frame, "block", "kws");
        int schemaVersion = MiniJson.getInt(frame, "schema_version", 1);
        Map<String, Object> values = MiniJson.getObject(frame, "values");
        String expectedBootId = MiniJson.getString(frame, "expected_gateway_boot_id", null);
        Long expectedRev = frame.containsKey("expected_state_revision")
                ? Long.valueOf(MiniJson.getLong(frame, "expected_state_revision", -1L))
                : null;

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        synchronized (stateLock) {
            boolean supported = configBlocks.containsKey(block);
            List<String> blockers = new ArrayList<String>();
            if (!supported) {
                blockers.add("unsupported_block:" + block);
            }
            if (expectedBootId != null && !expectedBootId.isEmpty() && !gatewayBootId.equals(expectedBootId)) {
                blockers.add("boot_id_mismatch");
            }
            if (expectedRev != null && expectedRev.longValue() >= 0 && expectedRev.longValue() != stateRevision) {
                blockers.add("state_revision_mismatch");
            }
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            if (supported && configBlocks.get(block) != null) {
                merged.putAll(configBlocks.get(block));
            }
            if (values != null) {
                merged.putAll(values);
            }
            String blockHash = MiniJson.getString(frame, "block_hash", computeBlockHash(block, merged));

            resp.put("type", "config_validated");
            resp.put("request_id", requestId);
            resp.put("device", deviceId);
            resp.put("block", block);
            resp.put("schema_version", Integer.valueOf(schemaVersion));
            resp.put("block_hash", blockHash);
            resp.put("ok", Boolean.valueOf(supported && blockers.isEmpty()));
            resp.put("supported", Boolean.valueOf(supported));
            resp.put("blockers", blockers);
            resp.put("state", (supported && blockers.isEmpty()) ? "validated" : "failed");
            resp.put("gateway_boot_id", gatewayBootId);
            resp.put("state_revision", Long.valueOf(stateRevision));
            resp.put("ts", Long.valueOf(nowMs()));
        }
        sendFrame(resp);
    }

    private void onConfigApplyFrame(Map<String, Object> frame) throws IOException {
        String block = MiniJson.getString(frame, "block", "kws");
        String coreNodeId = MiniJson.getString(frame, "core_node_id", "hm-core");
        long dataEpoch = MiniJson.getLong(frame, "data_epoch", 1L);
        String applicationId = MiniJson.getString(frame, "application_id", "app-1");
        String operationId = MiniJson.getString(frame, "operation_id",
                MiniJson.getString(frame, "tx_id", "op-" + nowMs()));
        long desiredRevision = MiniJson.getLong(frame, "desired_revision", 1L);
        int schemaVersion = MiniJson.getInt(frame, "schema_version", 1);
        Map<String, Object> values = MiniJson.getObject(frame, "values");
        String expectedBootId = MiniJson.getString(frame, "expected_gateway_boot_id", null);
        Long expectedRev = frame.containsKey("expected_state_revision")
                ? Long.valueOf(MiniJson.getLong(frame, "expected_state_revision", -1L))
                : null;

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        Map<String, Object> appliedSnapshot = null;
        boolean fenceFailed = false;
        String fenceReason = null;

        synchronized (stateLock) {
            if (expectedBootId != null && !expectedBootId.isEmpty() && !gatewayBootId.equals(expectedBootId)) {
                fenceFailed = true;
                fenceReason = "boot_id_mismatch";
            } else if (expectedRev != null && expectedRev.longValue() >= 0 && expectedRev.longValue() != stateRevision) {
                fenceFailed = true;
                fenceReason = "state_revision_mismatch";
            }

            long blockGen = MiniJson.getLong(frame, "block_generation",
                    blockGenerations.containsKey(block) ? blockGenerations.get(block).longValue() + 1L : 1L);
            Map<String, Object> target = configBlocks.get(block);
            if (!fenceFailed) {
                if (target == null) {
                    target = new LinkedHashMap<String, Object>();
                    configBlocks.put(block, target);
                }
                if (values != null) {
                    target.putAll(values);
                }
                blockGenerations.put(block, Long.valueOf(blockGen));
                stateRevision++;

                if ("kws".equals(block)) {
                    this.kwsThreshold = MiniJson.getDouble(target, "threshold", this.kwsThreshold);
                    this.kwsScore = MiniJson.getDouble(target, "score", this.kwsScore);
                } else if ("duplex".equals(block)) {
                    this.duplexMode = MiniJson.getString(target, "mode", this.duplexMode);
                    this.lowLatency = MiniJson.getBoolean(target, "low_latency", this.lowLatency);
                }
                appliedSnapshot = new LinkedHashMap<String, Object>(target);
            }

            Map<String, Object> activeMap = target != null ? new LinkedHashMap<String, Object>(target) : new LinkedHashMap<String, Object>();
            String hash = MiniJson.getString(frame, "block_hash", computeBlockHash(block, activeMap));

            receipt.put("type", "config_receipt");
            receipt.put("device", deviceId);
            receipt.put("core_node_id", coreNodeId);
            receipt.put("data_epoch", Long.valueOf(dataEpoch));
            receipt.put("application_id", applicationId);
            receipt.put("operation_id", operationId);
            receipt.put("desired_revision", Long.valueOf(desiredRevision));
            receipt.put("block_generation", Long.valueOf(blockGen));
            receipt.put("block", block);
            receipt.put("schema_version", Integer.valueOf(schemaVersion));
            receipt.put("block_hash", hash);
            receipt.put("state", fenceFailed ? "failed" : "active");
            if (fenceReason != null) {
                receipt.put("reason", fenceReason);
            }
            receipt.put("gateway_boot_id", gatewayBootId);
            receipt.put("state_revision", Long.valueOf(stateRevision));
            receipt.put("persisted_hash", hash);
            receipt.put("active_hash", hash);
            receipt.put("active_values", activeMap);
            receipt.put("ts", Long.valueOf(nowMs()));
        }

        if (appliedSnapshot != null) {
            listener.onConfigBlockApplied(block, appliedSnapshot);
        }
        sendFrame(receipt);
    }

    // =========================================================================
    // Outbound GatewayFrame senders (lock-free socket I/O)
    // =========================================================================

    public void sendMicReport(boolean active) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "mic_report");
        frame.put("device", deviceId);
        frame.put("active", Boolean.valueOf(active));
        frame.put("layout", "mono");
        frame.put("sample_rate", Integer.valueOf(16000));
        frame.put("channels", Integer.valueOf(1));
        sendFrame(frame);
    }

    public void sendModeReport() throws IOException {
        String mode;
        synchronized (stateLock) {
            mode = duplexMode;
        }
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "mode_report");
        frame.put("device", deviceId);

        Map<String, Object> duplex = new LinkedHashMap<String, Object>();
        duplex.put("capability", "full");
        duplex.put("requested", mode);
        duplex.put("negotiated", mode);
        duplex.put("effective", mode);
        frame.put("duplex", duplex);

        Map<String, Object> aec = new LinkedHashMap<String, Object>();
        aec.put("owner", "device");
        aec.put("reference", "voice_communication");
        frame.put("aec", aec);
        sendFrame(frame);
    }

    public void sendMuteState(String source, String taskId) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "mute_state");
        frame.put("device", deviceId);
        frame.put("ts", Long.valueOf(nowMs()));
        frame.put("seq", Long.valueOf(nextSeq()));
        frame.put("muted", Boolean.valueOf(muted));
        frame.put("source", source != null ? source : "user");
        if (taskId != null) {
            frame.put("task_id", taskId);
        }
        sendFrame(frame);
    }

    public void setMutedAndReport(boolean nextMuted, String source) throws IOException {
        synchronized (stateLock) {
            this.muted = nextMuted;
        }
        sendMuteState(source, null);
    }

    public void sendVolumeReceipt(String taskId, double effectiveVolume, String stage) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "volume_receipt");
        frame.put("task_id", taskId);
        frame.put("effective", Double.valueOf(effectiveVolume));
        frame.put("unit", "linear");
        frame.put("stage", stage);
        frame.put("device", deviceId);
        sendFrame(frame);
    }

    public void sendPlayReceipt(String taskId, String stage, String detail) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "play_receipt");
        frame.put("task_id", taskId);
        frame.put("stage", stage);
        if (detail != null) {
            frame.put("detail", detail);
        }
        frame.put("ts", Long.valueOf(nowMs()));
        sendFrame(frame);
    }

    public void sendPlayStreaming(String taskId) throws IOException {
        if (taskId != null && !taskId.isEmpty()) {
            sendPlayReceipt(taskId, "streaming", null);
        }
    }

    public void sendPlaySuperseded(String taskId) throws IOException {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        boolean removed;
        synchronized (stateLock) {
            PlayTaskState s = activePlays.remove(taskId);
            removed = (s != null);
            if (s != null && taskId.equals(activeTaskByChannel.get(s.channel))) {
                activeTaskByChannel.remove(s.channel);
            }
        }
        if (removed) {
            sendPlayReceipt(taskId, "unknown", "superseded by a new stream");
        }
    }

    public void sendPlayFinished(String taskId, long playedMs) throws IOException {
        if (taskId != null) {
            synchronized (stateLock) {
                PlayTaskState s = this.activePlays.remove(taskId);
                if (s != null && taskId.equals(activeTaskByChannel.get(s.channel))) {
                    activeTaskByChannel.remove(s.channel);
                }
            }
        }
        // Emit play_receipt("device_confirmed") only on actual hardware drain completion
        sendPlayReceipt(taskId, "device_confirmed", null);

        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "play_finished");
        frame.put("device", deviceId);
        frame.put("task_id", taskId);
        frame.put("played_ms", Long.valueOf(playedMs));
        frame.put("consumed_ms", Long.valueOf(playedMs));
        frame.put("progress_basis", "hw_delay");
        frame.put("ts", Long.valueOf(nowMs()));
        sendFrame(frame);
    }

    public void sendStopReceipt(String taskId, String stage, long playedMs, long consumedMs, String detail) throws IOException {
        sendStopReceipt(taskId, stage, playedMs, consumedMs, "hw_delay", detail);
    }

    public void sendStopReceipt(
            String taskId,
            String stage,
            long playedMs,
            long consumedMs,
            String basis,
            String detail) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "stop_receipt");
        frame.put("task_id", taskId != null ? taskId : "stop:0");
        frame.put("stage", stage);
        frame.put("played_ms", Long.valueOf(playedMs));
        frame.put("consumed_ms", Long.valueOf(consumedMs));
        frame.put("progress_basis", basis != null ? basis : "hw_delay");
        if (detail != null) {
            frame.put("detail", detail);
        }
        sendFrame(frame);
    }

    public void sendStopReceiptUnknown(String taskId, String detail) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "stop_receipt");
        frame.put("task_id", taskId != null ? taskId : "stop:0");
        frame.put("stage", "unknown");
        frame.put("detail", detail != null ? detail : "not_found");
        sendFrame(frame);
    }

    public void sendKwsDeployReceipt(
            boolean accepted,
            String hash,
            int keywordCount,
            String detail,
            int localExitCount,
            String intentsHash) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "kws_deploy_receipt");
        frame.put("device", deviceId);
        frame.put("accepted", Boolean.valueOf(accepted));
        frame.put("hash", hash);
        frame.put("keywords", Integer.valueOf(keywordCount));
        frame.put("detail", detail);
        frame.put("local_exit", Integer.valueOf(localExitCount));
        frame.put("intents_hash", intentsHash != null ? intentsHash : "");
        frame.put("threshold", Double.valueOf(kwsThreshold));
        frame.put("score", Double.valueOf(kwsScore));
        sendFrame(frame);
    }

    public void sendKwsReport(String state, String detail) throws IOException {
        LocalKwsEngine eng = this.localKwsEngine;
        String hash;
        int localExit;
        synchronized (stateLock) {
            String engHash = eng != null ? eng.getCurrentKeywordsHash() : null;
            hash = (engHash != null && !engHash.isEmpty()) ? engHash : kwsLoadedHash;
            localExit = retreatKeywords.size();
        }
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "kws_report");
        frame.put("device", deviceId);
        frame.put("state", state);
        frame.put("detail", detail);
        frame.put("loaded_hash", hash);
        frame.put("loaded_ms", Long.valueOf(nowMs()));
        frame.put("local_exit", Integer.valueOf(localExit));
        frame.put("threshold", Double.valueOf(kwsThreshold));
        frame.put("score", Double.valueOf(kwsScore));
        if (eng != null) {
            frame.put("detections", Long.valueOf(eng.getDetectionCount()));
            frame.put("restarts", Long.valueOf(eng.getRestartCount()));
        }
        sendFrame(frame);
    }

    public void sendEchoHealth(String health, long degradedMs) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "echo_health");
        frame.put("device", deviceId);
        frame.put("health", health);
        frame.put("degraded_ms", Long.valueOf(degradedMs));
        sendFrame(frame);
    }

    public void sendConfigState(String requestId) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        synchronized (stateLock) {
            frame.put("type", "config_state");
            if (requestId != null) {
                frame.put("request_id", requestId);
            }
            frame.put("device", deviceId);
            frame.put("gateway_boot_id", gatewayBootId);
            frame.put("state_revision", Long.valueOf(stateRevision));

            List<Object> blocksList = new ArrayList<Object>();
            for (Map.Entry<String, Map<String, Object>> entry : configBlocks.entrySet()) {
                String blockName = entry.getKey();
                Map<String, Object> values = new LinkedHashMap<String, Object>(entry.getValue());
                String hash = computeBlockHash(blockName, values);
                long gen = blockGenerations.containsKey(blockName) ? blockGenerations.get(blockName).longValue() : 1L;

                Map<String, Object> blockState = new LinkedHashMap<String, Object>();
                blockState.put("block", blockName);
                blockState.put("block_generation", Long.valueOf(gen));
                blockState.put("operation_id", "boot-" + blockName);
                blockState.put("data_epoch", Long.valueOf(1L));
                blockState.put("persisted_hash", hash);
                blockState.put("active_hash", hash);
                blockState.put("active_values", values);
                blockState.put("state", "active");
                blocksList.add(blockState);
            }
            frame.put("blocks", blocksList);
            frame.put("ts", Long.valueOf(nowMs()));
        }
        sendFrame(frame);
    }

    /**
     * Send a 16kHz 16-bit mono S16LE {@code audio_chunk} frame (gated on {@code micCapturing})
     * and coalesced ~2Hz {@code near_end_presence} telemetry.
     */
    public void sendAudioChunkAndPresence(byte[] pcm16leBytes, double noiseFloorDbfs) throws IOException {
        double rmsDbfs = computeRmsDbfs(pcm16leBytes);
        sendAudioChunkAndPresence(pcm16leBytes, rmsDbfs, noiseFloorDbfs);
    }

    public void sendAudioChunkAndPresence(byte[] pcm16leBytes, double rmsDbfs, double noiseFloorDbfs) throws IOException {
        if (pcm16leBytes == null || pcm16leBytes.length == 0 || muted || !micCapturing) {
            return;
        }
        int sampleCount = pcm16leBytes.length / 2;
        long tsMs = nowMs();
        boolean emitPresence;
        synchronized (stateLock) {
            this.nextSampleCursor += sampleCount;
            emitPresence = (lastPresenceSentMs == 0L || (tsMs - lastPresenceSentMs) >= PRESENCE_COALESCE_INTERVAL_MS);
            if (emitPresence) {
                lastPresenceSentMs = tsMs;
            }
        }

        Map<String, Object> chunkFrame = new LinkedHashMap<String, Object>();
        chunkFrame.put("type", "audio_chunk");
        chunkFrame.put("device", deviceId);
        chunkFrame.put("ts", Long.valueOf(tsMs));
        chunkFrame.put("seq", Long.valueOf(nextSeq()));
        chunkFrame.put("pcm_b64", Base64Codec.encodeToString(pcm16leBytes));
        chunkFrame.put("format", "s16le");
        chunkFrame.put("sample_rate", Integer.valueOf(16000));
        chunkFrame.put("channels", Integer.valueOf(1));
        chunkFrame.put("layout", "mono");
        sendFrame(chunkFrame);

        if (emitPresence) {
            double snrDb = Math.max(0.0, rmsDbfs - noiseFloorDbfs);
            boolean clipped = isClipped(pcm16leBytes);
            long durationMs = Math.max(10L, (sampleCount * 1000L) / 16000L);

            Map<String, Object> presenceFrame = new LinkedHashMap<String, Object>();
            presenceFrame.put("type", "near_end_presence");
            presenceFrame.put("device", deviceId);
            presenceFrame.put("ts_ms", Long.valueOf(tsMs));
            presenceFrame.put("duration_ms", Long.valueOf(durationMs));
            presenceFrame.put("rms_dbfs", Double.valueOf(round2(rmsDbfs)));
            presenceFrame.put("noise_floor_dbfs", Double.valueOf(round2(noiseFloorDbfs)));
            presenceFrame.put("snr_db", Double.valueOf(round2(snrDb)));
            presenceFrame.put("clipped", Boolean.valueOf(clipped));
            presenceFrame.put("echo_health", "healthy");
            presenceFrame.put("profile_version", PROFILE_VERSION);
            sendFrame(presenceFrame);
        }
    }

    /**
     * Send a manual/touch/hardware {@code wake} event (single frame; does NOT emit a duplicate
     * synthetic {@code keyword} frame).
     */
    public void sendWake(String keyword, double score, String source) throws IOException {
        long tsMs = nowMs();
        Map<String, Object> wakeFrame = new LinkedHashMap<String, Object>();
        wakeFrame.put("type", "wake");
        wakeFrame.put("device", deviceId);
        wakeFrame.put("ts", Long.valueOf(tsMs));
        wakeFrame.put("seq", Long.valueOf(nextSeq()));
        sendFrame(wakeFrame);
    }

    /**
     * Send an on-device Sherpa-ONNX {@code hm-kws} keyword fact with full XiaoAi L1+L2 acoustic evidence.
     */
    public void sendKwsKeywordFact(
            LocalKwsEngine.KwsEvent hit,
            double rmsDbfs,
            boolean selfHeard) throws IOException {
        if (hit == null || hit.keyword == null || hit.keyword.isEmpty()) {
            return;
        }
        long tsMs = hit.detectedWallMs > 0 ? (hit.detectedWallMs + clockOffsetMs) : nowMs();

        Map<String, Object> kwFrame = new LinkedHashMap<String, Object>();
        kwFrame.put("type", "keyword");
        kwFrame.put("device", deviceId);
        kwFrame.put("ts", Long.valueOf(tsMs));
        kwFrame.put("seq", Long.valueOf(nextSeq()));
        kwFrame.put("keyword", hit.keyword);
        if (hit.fedMs > 0) {
            kwFrame.put("fed_ms", Long.valueOf(hit.fedMs));
        }
        kwFrame.put("self_heard", Boolean.valueOf(selfHeard));
        kwFrame.put("rms_dbfs", Double.valueOf(round2(rmsDbfs)));
        kwFrame.put("lag_ms", Long.valueOf(Math.max(0L, hit.lagMs)));
        if (hit.tier != null && !hit.tier.isEmpty()) {
            kwFrame.put("kws_tier", hit.tier);
        }
        if (hit.wakeDurMs > 0) {
            kwFrame.put("wake_dur_ms", Long.valueOf(hit.wakeDurMs));
        }
        if (hit.maxGapMs > 0) {
            kwFrame.put("max_gap_ms", Long.valueOf(hit.maxGapMs));
        }
        if (hit.l2Passed != null) {
            kwFrame.put("l2_passed", hit.l2Passed);
        }
        sendFrame(kwFrame);
    }

    /**
     * Send an acoustic near-end {@code barge_in} fact with the 400ms pre-roll S16LE slice
     * and {@code stopped_locally} when local playback was preempted.
     */
    public void sendBargeInAndStoppedLocally(
            AcousticGuard.BargeInFact fact,
            boolean cutLocally,
            long latencyMs) throws IOException {
        if (fact == null || fact.prerollPcm16le == null) {
            return;
        }
        long tsMs = nowMs();
        String taskId = null;
        long playedMs = 0L;
        if (cutLocally) {
            synchronized (stateLock) {
                taskId = resolveFallbackTaskIdLocked();
                PlayTaskState state = taskId != null ? this.activePlays.remove(taskId) : null;
                if (state != null) {
                    playedMs = computeConsumedAudioMs(state.bytesConsumed, state.sampleRate, state.channels);
                    if (taskId.equals(activeTaskByChannel.get(state.channel))) {
                        activeTaskByChannel.remove(state.channel);
                    }
                }
            }
            if (taskId != null) {
                Map<String, Object> stoppedFrame = new LinkedHashMap<String, Object>();
                stoppedFrame.put("type", "stopped_locally");
                stoppedFrame.put("device", deviceId);
                stoppedFrame.put("task_id", taskId);
                stoppedFrame.put("reason", "barge_in_onset");
                stoppedFrame.put("played_ms", Long.valueOf(playedMs));
                stoppedFrame.put("latency_ms", Long.valueOf(Math.max(1L, latencyMs)));
                stoppedFrame.put("ahead_ms", Long.valueOf(0L));
                stoppedFrame.put("ts", Long.valueOf(tsMs));
                sendFrame(stoppedFrame);
            }
        }

        Map<String, Object> bargeFrame = new LinkedHashMap<String, Object>();
        bargeFrame.put("type", "barge_in");
        bargeFrame.put("device", deviceId);
        bargeFrame.put("ts", Long.valueOf(tsMs));
        bargeFrame.put("seq", Long.valueOf(nextSeq()));
        bargeFrame.put("pcm_b64", Base64Codec.encodeToString(fact.prerollPcm16le));
        bargeFrame.put("sample_rate", Integer.valueOf(16000));
        bargeFrame.put("channels", Integer.valueOf(1));
        bargeFrame.put("corr", Double.valueOf(round2(fact.corr)));
        bargeFrame.put("energy_db", Double.valueOf(round2(fact.energyDb)));
        bargeFrame.put("ref_db", Double.valueOf(round2(fact.refDb)));
        bargeFrame.put("residual_db", Double.valueOf(round2(fact.residualDb)));
        bargeFrame.put("ref_locked", Boolean.valueOf(fact.refLocked));
        sendFrame(bargeFrame);
    }

    /**
     * Send a local retreat reflex: {@code local_exit_flushed} MUST precede {@code keyword}
     * so {@code hm-core} still owns the playback session when validating {@code local_exit_flushed}.
     */
    public void sendRetreat(String keyword) throws IOException {
        sendRetreat(keyword, 6L, Arrays.asList("voice", "music"), 0L, 0L, -30.0);
    }

    public void sendRetreat(
            String keyword,
            long latencyMs,
            List<String> flushedChannels,
            long fedMs,
            long lagMs,
            double rmsDbfs) throws IOException {
        long tsMs = nowMs();
        String kw = (keyword == null || keyword.isEmpty()) ? "退下" : keyword;
        List<String> chs = (flushedChannels != null && !flushedChannels.isEmpty())
                ? flushedChannels
                : Arrays.asList("voice", "music");

        synchronized (stateLock) {
            this.activePlays.clear();
            this.activeTaskByChannel.clear();
        }
        closeUplinkGateImmediately();
        this.assistantState = "idle";

        // 1. local_exit_flushed FIRST (before keyword tears down core session ownership)
        Map<String, Object> exitFrame = new LinkedHashMap<String, Object>();
        exitFrame.put("type", "local_exit_flushed");
        exitFrame.put("device", deviceId);
        exitFrame.put("keyword", kw);
        exitFrame.put("ts", Long.valueOf(tsMs));
        exitFrame.put("latency_ms", Long.valueOf(Math.max(1L, latencyMs)));
        exitFrame.put("channels", chs);
        sendFrame(exitFrame);

        // 2. keyword fact SECOND (seq is required by hm-core GatewayFrame::Keyword)
        Map<String, Object> kwFrame = new LinkedHashMap<String, Object>();
        kwFrame.put("type", "keyword");
        kwFrame.put("device", deviceId);
        kwFrame.put("ts", Long.valueOf(tsMs));
        kwFrame.put("seq", Long.valueOf(nextSeq()));
        kwFrame.put("keyword", kw);
        if (fedMs > 0) {
            kwFrame.put("fed_ms", Long.valueOf(fedMs));
        }
        kwFrame.put("self_heard", Boolean.FALSE);
        kwFrame.put("rms_dbfs", Double.valueOf(round2(rmsDbfs)));
        kwFrame.put("lag_ms", Long.valueOf(Math.max(0L, lagMs)));
        sendFrame(kwFrame);
    }

    public void sendButton(String action) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "button");
        frame.put("device", deviceId);
        frame.put("ts", Long.valueOf(nowMs()));
        frame.put("seq", Long.valueOf(nextSeq()));
        frame.put("action", action);
        sendFrame(frame);
    }

    /**
     * Send a direct spoken or touch utterance ({@code utterance}) to {@code hm-core}.
     */
    public void sendUtterance(String text, String source) throws IOException {
        sendUtterance(text, source, true);
    }

    public void sendUtterance(String text, String source, boolean prependWake) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        long tsMs = nowMs();
        if (prependWake) {
            Map<String, Object> wakeFrame = new LinkedHashMap<String, Object>();
            wakeFrame.put("type", "wake");
            wakeFrame.put("device", deviceId);
            wakeFrame.put("ts", Long.valueOf(tsMs));
            wakeFrame.put("seq", Long.valueOf(nextSeq()));
            sendFrame(wakeFrame);
        }

        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("type", "utterance");
        frame.put("device", deviceId);
        frame.put("ts", Long.valueOf(tsMs));
        frame.put("seq", Long.valueOf(nextSeq()));
        frame.put("source", source != null ? source : "android_ui");
        frame.put("text", text.trim());
        sendFrame(frame);
    }

    private void sendFrame(Map<String, Object> frame) throws IOException {
        String json = MiniJson.stringify(frame);
        String type = MiniJson.getString(frame, "type", "unknown");
        listener.onProtocolFrameLog("OUT", type, json);
        transport.sendJsonLine(json);
    }

    // =========================================================================
    // Audio & Hash Utilities
    // =========================================================================

    public static double computeRmsDbfs(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < 2) {
            return -96.0;
        }
        int samples = pcm16le.length / 2;
        double sumSquares = 0.0;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            double norm = s / 32768.0;
            sumSquares += norm * norm;
        }
        double rms = Math.sqrt(sumSquares / samples);
        if (rms <= 1e-5) {
            return -96.0;
        }
        return Math.max(-96.0, Math.min(0.0, 20.0 * Math.log10(rms)));
    }

    public static boolean isClipped(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < 2) {
            return false;
        }
        int samples = pcm16le.length / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            if (s >= 32700 || s <= -32700) {
                return true;
            }
        }
        return false;
    }

    public static byte[] stripWavHeaderToPcm16le(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length <= 44) {
            return wavBytes != null ? wavBytes : new byte[0];
        }
        if (wavBytes[0] == 'R' && wavBytes[1] == 'I' && wavBytes[2] == 'F' && wavBytes[3] == 'F') {
            int offset = 12;
            while (offset + 8 <= wavBytes.length) {
                boolean isData = wavBytes[offset] == 'd'
                        && wavBytes[offset + 1] == 'a'
                        && wavBytes[offset + 2] == 't'
                        && wavBytes[offset + 3] == 'a';
                int chunkSize = ByteBuffer.wrap(wavBytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                offset += 8;
                if (isData) {
                    int end = Math.min(wavBytes.length, offset + Math.max(0, chunkSize));
                    return Arrays.copyOfRange(wavBytes, offset, end);
                }
                if (chunkSize < 0 || offset + chunkSize > wavBytes.length) {
                    break;
                }
                offset += chunkSize;
            }
            return Arrays.copyOfRange(wavBytes, 44, wavBytes.length);
        }
        return wavBytes;
    }

    public static int parseWavSampleRate(byte[] wavBytes, int fallbackRate) {
        if (wavBytes != null && wavBytes.length >= 44
                && wavBytes[0] == 'R' && wavBytes[1] == 'I' && wavBytes[2] == 'F' && wavBytes[3] == 'F') {
            int rate = ByteBuffer.wrap(wavBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (rate >= 8000 && rate <= 96000) {
                return rate;
            }
        }
        return fallbackRate;
    }

    public static int parseWavChannels(byte[] wavBytes, int fallbackChannels) {
        if (wavBytes != null && wavBytes.length >= 44
                && wavBytes[0] == 'R' && wavBytes[1] == 'I' && wavBytes[2] == 'F' && wavBytes[3] == 'F') {
            int ch = ByteBuffer.wrap(wavBytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            if (ch == 1 || ch == 2) {
                return ch;
            }
        }
        return fallbackChannels >= 2 ? 2 : 1;
    }

    public static long computeConsumedAudioMs(long pcmBytes, int sampleRate) {
        return computeConsumedAudioMs(pcmBytes, sampleRate, 1);
    }

    public static long computeConsumedAudioMs(long pcmBytes, int sampleRate, int channels) {
        int rate = sampleRate > 0 ? sampleRate : 16000;
        int ch = channels >= 2 ? 2 : 1;
        long frames = pcmBytes / (2L * ch);
        return (frames * 1000L) / rate;
    }

    public static String fnv1a64Hex(String input) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = (input != null ? input : "").getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.US, "%016x", hash);
    }

    public static String computeBlockHash(String blockName, Map<String, Object> values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = blockName + ":" + MiniJson.stringify(values);
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(MiniJson.stringify(values).hashCode());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Standalone CLI mode for headless smoke testing against a live {@code hm-core} server.
     */
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8932;
        String deviceId = "android-panel-1";
        String model = "android-smart-panel";
        int chunksToSend = 12;
        long holdAliveMs = 4000;
        boolean triggerWake = false;

        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--device".equals(args[i]) && i + 1 < args.length) {
                deviceId = args[++i];
            } else if ("--model".equals(args[i]) && i + 1 < args.length) {
                model = args[++i];
            } else if ("--chunks".equals(args[i]) && i + 1 < args.length) {
                chunksToSend = Integer.parseInt(args[++i]);
            } else if ("--hold-ms".equals(args[i]) && i + 1 < args.length) {
                holdAliveMs = Long.parseLong(args[++i]);
            } else if ("--wake".equals(args[i])) {
                triggerWake = true;
            }
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            final BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            FrameTransport transport = new FrameTransport() {
                @Override
                public synchronized void sendJsonLine(String jsonLine) throws IOException {
                    writer.write(jsonLine);
                    writer.write('\n');
                    writer.flush();
                }
            };

            final NorthboundSession[] ref = new NorthboundSession[1];
            Listener listener = new BaseListener() {
                @Override
                public void onPlayWav(String taskId, String channel, byte[] wavBytes) {
                    try {
                        byte[] pcm = stripWavHeaderToPcm16le(wavBytes);
                        int rate = parseWavSampleRate(wavBytes, 16000);
                        long ms = computeConsumedAudioMs(pcm.length, rate);
                        ref[0].sendPlayStreaming(taskId);
                        ref[0].sendPlayFinished(taskId, ms);
                    } catch (IOException ignored) {
                    }
                }

                @Override
                public void onPlayStreamEnd(String taskId) {
                    try {
                        ref[0].sendPlayFinished(taskId, 320L);
                    } catch (IOException ignored) {
                    }
                }
            };

            final NorthboundSession session = new NorthboundSession(deviceId, model, GATEWAY_VERSION, transport, listener);
            ref[0] = session;

            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            session.handleIncomingLine(line);
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "nb-cli-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            session.sendHello();

            long deadline = System.currentTimeMillis() + 3000;
            while (!session.isHelloAckReceived() && System.currentTimeMillis() < deadline) {
                Thread.sleep(30);
            }
            if (!session.isHelloAckReceived()) {
                throw new IllegalStateException("Timed out waiting for hello_ack from hm-core at " + host + ":" + port);
            }

            byte[] pcmChunk = new byte[2560];
            for (int s = 0; s < 1280; s++) {
                short sample = (short) (Math.sin(2.0 * Math.PI * 440.0 * s / 16000.0) * 6000);
                pcmChunk[s * 2] = (byte) (sample & 0xFF);
                pcmChunk[s * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }

            for (int c = 0; c < chunksToSend; c++) {
                session.sendAudioChunkAndPresence(pcmChunk, -55.0);
                Thread.sleep(40);
            }

            if (triggerWake) {
                session.sendWake("你好小智", 3.15, "kws");
            }

            long holdUntil = System.currentTimeMillis() + holdAliveMs;
            while (System.currentTimeMillis() < holdUntil && !socket.isClosed()) {
                session.sendAudioChunkAndPresence(pcmChunk, -55.0);
                Thread.sleep(150);
            }
        }
    }
}
