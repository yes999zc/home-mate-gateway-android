package homes.mymate.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import homes.mymate.gateway.protocol.AcousticGuard;
import homes.mymate.gateway.protocol.BoundedTwoTierQueue;
import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.CoreDiscovery;
import homes.mymate.gateway.protocol.DeviceRoutingPolicy;
import homes.mymate.gateway.protocol.LocalKwsEngine;
import homes.mymate.gateway.protocol.MiniJson;
import homes.mymate.gateway.protocol.NorthboundSession;
import homes.mymate.gateway.protocol.TranscriptStateStore;

/**
 * Persistent 24/7 Foreground Service for Home Mate Android Universal Client (v2.0).
 *
 * v2.0 architectural overhaul:
 * - Dedicated {@code hm-northbound-tx} outbound writer thread with 2-tier bounded queues
 *   (lossless {@code controlQueue} always prioritized ahead of bounded drop-oldest {@code audioQueue})
 *   plus a 4-second TCP write-stall watchdog — completely eliminating audio-capture / TCP deadlocks.
 * - {@link ConnectivityManager.NetworkCallback} for immediate reconnect and LAN-priority failback.
 * - {@code intentionalReconnect} flag prevents manual Core switches from triggering auto-failover.
 * - Background ONNX model unpacking on {@code hm-kws-engine} thread (zero Main-thread ANR).
 * - Strict Android 10-16 FGS type compliance & mute privacy gate.
 * - Bounded {@link ExecutorService} for async control/HTTP calls.
 */
public final class GatewayService extends Service {

    public static final String EXTRA_FROM_BOOT = "extra_from_boot";
    private static final String CHANNEL_ID = "home_mate_gateway_channel";
    private static final int NOTIFICATION_ID = 8932;
    private static final int MAX_LOG_LINES = 120;
    private static final int AUDIO_TX_QUEUE_CAP = 32;
    private static final long TX_STALL_WATCHDOG_MS = 4000L;

    public interface UiTelemetryListener {
        void onTelemetryUpdated();
    }

    public interface DiscoveryCallback {
        void onCoresDiscovered(List<CoreDiscovery.DiscoveredCore> cores);
    }

    public interface CalibrationCallback {
        void onCalibrationDone(double measuredNoiseFloorDbfs, float recommendedGain);
    }

    public interface ModeApplyCallback {
        void onModeApplied(boolean ok, boolean authRequired);
    }

    public interface RoomSummariesCallback {
        void onRoomSummaries(List<CoreApiClient.RoomNodeSummary> summaries);
    }

    public interface ActionResultCallback {
        void onActionDone(boolean ok, boolean authRequired);
    }

    public interface HistoryCallback {
        void onHistoryLoaded(
                List<CoreApiClient.ChatEntry> messages,
                String currentSessionId,
                boolean authRequired,
                boolean ok);
    }

    public interface TokenVerifyCallback {
        void onTokenVerified(CoreApiClient.TokenVerifyResult result, String sanitizedToken);
    }

    public final class LocalBinder extends Binder {
        public GatewayService getService() {
            return GatewayService.this;
        }
    }

    /**
     * 2-tier non-blocking outbound transport decoupling audio capture from socket flush latency.
     * Backed by {@link BoundedTwoTierQueue} so concurrent producers and writer never leak permits
     * or stall on underflow.
     */
    private final class TwoTierOutboundTransport implements NorthboundSession.FrameTransport {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BoundedTwoTierQueue queue = new BoundedTwoTierQueue(256, AUDIO_TX_QUEUE_CAP);
        private final ThreadLocal<Boolean> orderedControlBurst = new ThreadLocal<Boolean>();
        private volatile boolean alive = true;
        private volatile long writeStartedMs = 0L;
        private final Thread txThread;

        void beginOrderedBurst() {
            orderedControlBurst.set(Boolean.TRUE);
        }

        void endOrderedBurst() {
            orderedControlBurst.remove();
        }

        TwoTierOutboundTransport(Socket socket, BufferedWriter writer) {
            this.socket = socket;
            this.writer = writer;
            this.txThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runTxLoop();
                }
            }, "hm-northbound-tx");
            this.txThread.setDaemon(true);
            this.txThread.start();
        }

        @Override
        public void sendJsonLine(String jsonLine) throws IOException {
            if (!alive || queue.isClosed()) {
                throw new IOException("Outbound transport closed");
            }
            boolean inOrderedBurst = Boolean.TRUE.equals(orderedControlBurst.get());
            boolean isAudioBulk = !inOrderedBurst
                    && (jsonLine.contains("\"type\":\"audio_chunk\"")
                        || jsonLine.contains("\"type\":\"near_end_presence\""));
            if (!queue.enqueue(jsonLine, isAudioBulk)) {
                throw new IOException("Outbound transport closed");
            }
        }

        void checkWriteStall() {
            long started = writeStartedMs;
            if (alive && started > 0L && (System.currentTimeMillis() - started) > TX_STALL_WATCHDOG_MS) {
                appendLog("NET", "TX write stalled >" + TX_STALL_WATCHDOG_MS + "ms; resetting socket");
                stop();
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        void stop() {
            alive = false;
            queue.close();
            txThread.interrupt();
        }

        private void runTxLoop() {
            try {
                while (alive && !Thread.currentThread().isInterrupted()) {
                    String line = queue.takeNext(250L);
                    if (line == null) {
                        continue;
                    }
                    writeStartedMs = System.currentTimeMillis();
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                    writeStartedMs = 0L;
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                if (alive) {
                    appendLog("NET", "TX write failed: " + e.getMessage());
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                writeStartedMs = 0L;
                alive = false;
                queue.close();
            }
        }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);
    private GatewayConfig config;
    private AudioEngine audioEngine;
    private LocalKwsEngine kwsEngine;
    private final AcousticGuard acousticGuard = new AcousticGuard();
    private HardwareAdapter hardwareAdapter;
    private MediaSessionController mediaSessionController;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile boolean serviceRunning = false;
    private volatile boolean uiForeground = false;
    private volatile boolean fromBootRestricted = false;
    private volatile boolean micFgsGranted = false;
    private volatile boolean captureStartedWhileBootRestricted = false;
    private volatile boolean desiredMuted = false;
    private volatile long lastAecDiagLogWallMs = 0L;
    private volatile long lastAecEchoHealthWallMs = 0L;
    private final AtomicBoolean intentionalReconnect = new AtomicBoolean(false);
    // Dedicated lock guarding all transcriptEpoch, lastRawServerTranscript, localManageTranscripts,
    // localVoiceTurns, manageOnlySessionIds, and coreSnapshot transcript commits atomically (P1-1).
    private final Object transcriptSyncLock = new Object();
    private final TranscriptStateStore transcriptState = new TranscriptStateStore(transcriptSyncLock);
    private Thread connectionThread;
    private Thread apiPollThread;
    private volatile Socket activeSocket;
    private volatile TwoTierOutboundTransport activeTxTransport;
    private volatile NorthboundSession activeSession;
    private volatile UiTelemetryListener uiListener;

    // Live telemetry
    private volatile String connectionStatus = "Connecting...";
    private volatile boolean connected = false;
    private volatile boolean voiceAuthRequired = false;
    private volatile boolean apiAuthRequired = false;
    private volatile String assistantState = "idle";
    private volatile double lastRmsDbfs = -72.0;
    private volatile double lastNoiseFloorDbfs = -58.0;
    private volatile double lastSnrDb = 0.0;
    private volatile long blocksSent = 0;
    private volatile long lastAudioUiNotifyMs = 0L;
    public static final class PendingConfirmation {
        public final String host;
        public final int webPort;
        public final String actionId;
        public final String summary;
        public final String tool;
        public final String expectedState;
        public final long expectedRevision;

        public PendingConfirmation(
                String host,
                int webPort,
                String actionId,
                String summary,
                String tool,
                String expectedState,
                long expectedRevision) {
            this.host = host != null ? host : "";
            this.webPort = webPort;
            this.actionId = actionId != null ? actionId : "";
            this.summary = summary != null ? summary : "";
            this.tool = tool != null ? tool : "";
            this.expectedState = (expectedState != null && !expectedState.isEmpty()) ? expectedState : "pending";
            this.expectedRevision = Math.max(0L, expectedRevision);
        }
    }

    private volatile PendingConfirmation pendingConfirmation = null;

    public PendingConfirmation getPendingConfirmation() {
        return pendingConfirmation;
    }

    public void dismissPendingConfirmation(String actionId) {
        PendingConfirmation cur = pendingConfirmation;
        if (cur != null && (actionId == null || actionId.equals(cur.actionId))) {
            pendingConfirmation = null;
        }
    }

    public void decidePendingConfirmationAsync(
            final PendingConfirmation req,
            final boolean approve,
            final ActionResultCallback callback) {
        if (req == null || req.actionId.isEmpty()) {
            if (callback != null) {
                callback.onActionDone(false, false);
            }
            return;
        }
        final long confCoreEpoch = transcriptState.getCoreSwitchEpoch();
        final long confTranscriptEpoch = transcriptState.getTranscriptEpoch();
        final boolean manageOnly = config.isManageOnly();
        dismissPendingConfirmation(req.actionId);
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String token = config.getBearerTokenForHost(req.host);
                CoreApiClient.ActionOutcome outcome = CoreApiClient.decideConfirmationWithResult(
                        req.host, req.webPort, req.actionId,
                        req.expectedState, req.expectedRevision,
                        approve, token);
                boolean ok = outcome.ok;
                if (outcome.authRequired) {
                    noteApiAuthFailure(req.host, true, confCoreEpoch, false);
                }
                String lang = config.getLanguage();
                String feedbackKey = ok
                        ? (approve ? "confirm_action_approved" : "confirm_action_denied")
                        : (approve ? "confirm_action_approve_failed" : "confirm_action_deny_failed");
                transcriptState.appendLocalManageChatEntry(
                        req.host,
                        "assistant",
                        I18n.t(lang, feedbackKey),
                        confCoreEpoch,
                        confTranscriptEpoch,
                        manageOnly);
                notifyUi();
                refreshCoreSnapshotNow();
                if (callback != null) {
                    callback.onActionDone(ok, outcome.authRequired);
                }
            }
        });
    }

    private volatile String lastTransientError = null;
    private final String processBootId = "android-boot-" + System.currentTimeMillis();
    private volatile List<CoreDiscovery.DiscoveredCore> lastDiscoveredCores = java.util.Collections.emptyList();
    private volatile long lastAutoScanMs = 0L;
    private final AtomicBoolean lanScanInFlight = new AtomicBoolean(false);
    private final Deque<String> protocolLogs = new ArrayDeque<String>(MAX_LOG_LINES);

    @Override
    public void onCreate() {
        super.onCreate();
        config = new GatewayConfig(this);
        desiredMuted = config.isDesiredMuted();
        acquireWakeLock();

        mediaSessionController = new MediaSessionController(this, new MediaSessionController.MediaActionListener() {
            @Override
            public void onMediaPlayPause(boolean currentlyPlaying) {
                triggerMediaCommand(currentlyPlaying ? "暂停播放" : "继续播放");
            }

            @Override
            public void onMediaNext() {
                triggerMediaCommand("下一首");
            }

            @Override
            public void onMediaPrevious() {
                triggerMediaCommand("上一首");
            }

            @Override
            public void onMediaStop() {
                triggerManualRetreat(null);
            }
        });
        // Defer startInForeground() to onStartCommand() / onBind() so Android 14 boot-restricted
        // intents can set `fromBootRestricted` before requesting FOREGROUND_SERVICE_TYPE_MICROPHONE.

        // Prepare KWS workspace on background hm-kws-engine thread (never blocks Main thread)
        File kwsWorkDir = KwsAssetInstaller.getKwsWorkDir(this);
        String nativeDirPath = getApplicationInfo().nativeLibraryDir;
        File nativeDir = nativeDirPath != null ? new File(nativeDirPath) : null;

        kwsEngine = new LocalKwsEngine(kwsWorkDir, nativeDir, config.getDeviceId(), new LocalKwsEngine.EventListener() {
            @Override
            public void onKwsReady(double threshold, double score, String loadedHash) {
                appendLog("KWS", String.format(Locale.US, "engine=%s ready (thr=%.2f score=%.2f hash=%s)",
                        kwsEngine.getActiveEngineMode(), threshold, score,
                        loadedHash != null ? loadedHash : ""));
                NorthboundSession s = activeSession;
                if (s != null) {
                    s.setLocalOfflineKwsReady(true);
                    if (connected) {
                        try {
                            s.sendKwsReport("running", String.format(Locale.US,
                                    "%s ready (threshold=%.2f score=%.2f)",
                                    kwsEngine.getActiveEngineMode(), threshold, score), loadedHash);
                        } catch (IOException ignored) {
                        }
                    }
                }
                final CoreApiClient.CoreSnapshot snapNow = transcriptState.getSnapshot();
                if (connected && snapNow != null && snapNow.reflexTable != null) {
                    sendAsync(new Runnable() {
                        @Override
                        public void run() {
                            syncReflexTableFromSnapshot(snapNow);
                        }
                    });
                }
                notifyUi();
            }

            @Override
            public void onKeywordDetected(final LocalKwsEngine.KwsEvent hit, final boolean isRetreatWord) {
                final long now = System.currentTimeMillis();
                final homes.mymate.gateway.protocol.SoftwareAecFilter.AecTelemetry aecSnap =
                        audioEngine.getActiveAecTelemetry();
                final boolean anyPlayAtHit = audioEngine.hasActiveAnyPlayback();
                final String kwsDiag = String.format(Locale.US,
                        "hit=%s retreat=%s playing=%s lag=%dms %s",
                        hit.keyword, isRetreatWord ? "1" : "0", anyPlayAtHit ? "1" : "0",
                        hit.lagMs, aecSnap.formatCompact());
                appendLog("KWS", kwsDiag);
                android.util.Log.i("HomeMateGateway", "KWS " + kwsDiag);
                final boolean aecCancelling = audioEngine.isSoftwareAecEnabled()
                        || aecSnap.erleDb >= 3.0;
                final boolean selfHeard = !isRetreatWord
                        && !aecCancelling
                        && acousticGuard.isSelfHeardEcho(lastRmsDbfs, now);
                if (isRetreatWord) {
                    final long t0 = System.currentTimeMillis();
                    final List<String> flushed = audioEngine.stopAndFlush();
                    final long latencyMs = Math.max(1L, System.currentTimeMillis() - t0);
                    acousticGuard.onPlaybackStopped();
                    assistantState = "idle";
                    syncHardwareAndMedia();
                    notifyUi();
                    sendAsync(new Runnable() {
                        @Override
                        public void run() {
                            NorthboundSession s = activeSession;
                            if (s != null && connected) {
                                try {
                                    s.sendRetreat(hit.keyword, latencyMs, flushed, hit.fedMs, hit.lagMs, lastRmsDbfs);
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    });
                } else {
                    final boolean isSuspect = "suspect".equalsIgnoreCase(hit.tier);
                    if (connected && !isSuspect) {
                        assistantState = "listening";
                    }
                    syncHardwareAndMedia();
                    notifyUi();
                    sendAsync(new Runnable() {
                        @Override
                        public void run() {
                            NorthboundSession s = activeSession;
                            if (s != null && connected) {
                                TwoTierOutboundTransport tx = activeTxTransport;
                                if (!isSuspect && tx != null) {
                                    tx.beginOrderedBurst();
                                }
                                try {
                                    if (!isSuspect) {
                                        // Atomically flush full wake-word preroll PCM BEFORE keyword and
                                        // reopen live capture ONLY after both are queued (zero seq inversion).
                                        s.flushPrerollAndSendKwsKeywordFact(now, hit, lastRmsDbfs, selfHeard);
                                    } else {
                                        s.sendKwsKeywordFact(hit, lastRmsDbfs, selfHeard);
                                    }
                                } catch (IOException ignored) {
                                } finally {
                                    if (!isSuspect && tx != null) {
                                        tx.endOrderedBurst();
                                    }
                                }
                            }
                        }
                    });
                }
            }

            @Override
            public void onVadSpeechChanged(boolean speech) {
                // Local VAD hint handled inside LocalKwsEngine; UI orb reacts directly to live PCM RMS.
            }

            @Override
            public void onKwsStateChanged(String state, String detail) {
                appendLog("KWS", state + ": " + detail);
                NorthboundSession s = activeSession;
                if (s != null) {
                    s.setLocalOfflineKwsReady(kwsEngine != null && kwsEngine.isProcessReady());
                    // onKwsReady already emits the authoritative "running" kws_report; avoid duplicate reports
                    if (!"running".equalsIgnoreCase(state) && connected) {
                        try {
                            s.sendKwsReport(state, detail);
                        } catch (IOException ignored) {
                        }
                    }
                }
                notifyUi();
            }
        });
        kwsEngine.setAssetPreparer(new LocalKwsEngine.AssetPreparer() {
            @Override
            public void prepareWorkspace() {
                KwsAssetInstaller.prepareKwsWorkspace(GatewayService.this, kwsEngine);
            }
        });
        kwsEngine.setSensitivity(config.getKwsThreshold(), config.getKwsScore());
        kwsEngine.start();

        audioEngine = new AudioEngine(this, new AudioEngine.CaptureCallback() {
            @Override
            public void onAudioBlockCaptured(byte[] pcm16leBytes, byte[] kwsPcm16leBytes, double rmsDbfs, double noiseFloorDbfs, double snrDb, boolean aecActive) {
                lastRmsDbfs = rmsDbfs;
                lastNoiseFloorDbfs = noiseFloorDbfs;
                lastSnrDb = snrDb;

                TwoTierOutboundTransport tx = activeTxTransport;
                if (tx != null) {
                    tx.checkWriteStall();
                }

                // Mute privacy gate: never process KWS, barge-in, or uplink audio while muted
                if (isMuted()) {
                    long nowMuted = System.currentTimeMillis();
                    if (nowMuted - lastAudioUiNotifyMs >= 300L) {
                        lastAudioUiNotifyMs = nowMuted;
                        notifyUi();
                    }
                    return;
                }

                long nowMs = System.currentTimeMillis();
                if (kwsEngine != null) {
                    byte[] kwsInput = (kwsPcm16leBytes != null) ? kwsPcm16leBytes : pcm16leBytes;
                    kwsEngine.feedPcmBlock(kwsInput, nowMs, snrDb < 5.0);
                }

                NorthboundSession session = activeSession;
                if (session != null) {
                    boolean prevAec = session.isAecActive();
                    session.setAecActive(aecActive);
                    session.setAecTailMs(aecActive ? 160 : 0);
                    if (prevAec != aecActive && connected && session.isHelloAckReceived()) {
                        try {
                            session.sendEchoHealth(session.currentEchoHealth(), 0L, session.currentEchoHealthReason());
                        } catch (IOException ignored) {
                        }
                    }
                }
                boolean anyPlayback = audioEngine.hasActiveVoicePlayback() || audioEngine.hasActiveMediaPlayback();
                if (anyPlayback && (nowMs - lastAecDiagLogWallMs) >= 2000L) {
                    lastAecDiagLogWallMs = nowMs;
                    homes.mymate.gateway.protocol.SoftwareAecFilter.AecTelemetry snap =
                            audioEngine.getActiveAecTelemetry();
                    String diagLine = snap.formatCompact();
                    appendLog("AEC", diagLine);
                    android.util.Log.i("HomeMateGateway", "AEC " + diagLine);
                    if (session != null && connected && session.isHelloAckReceived()
                            && (nowMs - lastAecEchoHealthWallMs) >= 5000L) {
                        lastAecEchoHealthWallMs = nowMs;
                        try {
                            String eh = session.currentEchoHealth();
                            String er = session.currentEchoHealthReason() + ";" + snap.formatEchoHealthSuffix();
                            session.sendEchoHealth(eh, 0L, er);
                        } catch (IOException ignored) {
                        }
                    }
                }
                CoreApiClient.CoreSnapshot snapNow = transcriptState.getSnapshot();
                String activeDuplex = session != null ? session.getDuplexMode() : "half";
                boolean coreBargeIn = snapNow != null && snapNow.bargeIn;
                boolean bargeInUplinkAllowed = session != null
                        && DeviceRoutingPolicy.shouldAllowBargeInUplink(activeDuplex, anyPlayback);
                boolean acousticBargeInAllowed = session != null
                        && DeviceRoutingPolicy.shouldAllowAcousticBargeIn(activeDuplex, coreBargeIn, anyPlayback);
                boolean aecRefLocked = audioEngine.isHardwareAecEnabled()
                        || (audioEngine.isSoftwareAecEnabled() && audioEngine.getSoftwareAecErleDb() > 0.5)
                        || (audioEngine.getKwsPlaybackAecErleDb() > 2.0);

                final AcousticGuard.BargeInFact bargeFact = acousticGuard.pushCaptureBlockAndCheckBargeIn(
                        pcm16leBytes, rmsDbfs, noiseFloorDbfs, acousticBargeInAllowed, aecRefLocked, nowMs);

                if (session != null && connected && session.isHelloAckReceived()) {
                    try {
                        if (session.sendAudioChunkWithGate(pcm16leBytes, rmsDbfs, noiseFloorDbfs, bargeInUplinkAllowed, nowMs)) {
                            blocksSent++;
                        }
                        if (bargeFact != null) {
                            session.sendBargeInAndStoppedLocally(bargeFact, false, 8L);
                            appendLog("BARGE", String.format(Locale.US,
                                    "barge_in (energy=%.1f ref=%.1f res=%.1fdB)",
                                    bargeFact.energyDb, bargeFact.refDb, bargeFact.residualDb));
                        }
                    } catch (IOException ignored) {
                    }
                }
                if (nowMs - lastAudioUiNotifyMs >= 250L) {
                    lastAudioUiNotifyMs = nowMs;
                    notifyUi();
                }
            }

            @Override
            public void onPlaybackFirstChunk(final String taskId) {
                NorthboundSession s = activeSession;
                if (s != null && connected) {
                    try {
                        s.sendPlayStreaming(taskId);
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onPlaybackReferenceChunk(byte[] pcm16leBytes, double effectiveLinearGain, long nowMs) {
                acousticGuard.onPlaybackReferenceChunk(pcm16leBytes, effectiveLinearGain, nowMs);
            }

            @Override
            public void onPlaybackActiveChanged(boolean anyActive) {
                acousticGuard.setPlaybackActive(anyActive);
            }

            @Override
            public void onPlaybackSuperseded(String supersededTaskId) {
                NorthboundSession s = activeSession;
                if (s != null && connected) {
                    try {
                        s.sendPlaySuperseded(supersededTaskId);
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onPlaybackFinished(String taskId, long playedMs, String progressBasis) {
                syncHardwareAndMedia();
                NorthboundSession session = activeSession;
                if (session != null && connected) {
                    try {
                        session.sendPlayFinished(taskId, playedMs, progressBasis);
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onPlaybackFailed(String taskId, String detail) {
                if (!audioEngine.hasActiveVoicePlayback() && !audioEngine.hasActiveMediaPlayback()) {
                    acousticGuard.onPlaybackStopped();
                    if ("speaking".equalsIgnoreCase(assistantState)) {
                        assistantState = "idle";
                    }
                }
                syncHardwareAndMedia();
                NorthboundSession session = activeSession;
                if (session != null && connected) {
                    try {
                        session.sendPlayFailed(taskId, detail);
                    } catch (IOException ignored) {
                    }
                }
                notifyUi();
            }

            @Override
            public void onAudioFocusGained() {
                ensureCaptureRunningIfPermitted();
            }

            @Override
            public void onAudioFocusLostMedia(
                    final List<String> stoppedTaskIds,
                    final List<NorthboundSession.StopPlaybackResult> stopResults) {
                appendLog("AUDIO", "AUDIOFOCUS_LOSS: paused " + stoppedTaskIds.size() + " media stream(s) cleanly");
                syncHardwareAndMedia();
                notifyUi();
                sendAsync(new Runnable() {
                    @Override
                    public void run() {
                        NorthboundSession session = activeSession;
                        if (session != null && connected) {
                            try {
                                session.sendAudioFocusLostMediaPause(stoppedTaskIds, stopResults);
                            } catch (IOException e) {
                                appendLog("ERR", "sendAudioFocusLostMediaPause failed: " + e.getMessage());
                            }
                        }
                    }
                });
            }

            @Override
            public void onAudioError(String stage, String message) {
                appendLog("AUDIO", stage + ": " + message);
                notifyUi();
            }
        });

        hardwareAdapter = HardwareAdapter.Factory.createAndAttach(config.isR1HookEnabled());

        registerNetworkCallback();
        // P1-4: Defer startGatewayLoop() (and AudioRecord creation) until onStartCommand() / onBind()
        // inspects EXTRA_FROM_BOOT and establishes the true FGS type (avoiding silenced boot AudioRecord).
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean prevMicFgs = micFgsGranted;
        if (intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) {
            fromBootRestricted = (Build.VERSION.SDK_INT >= 30);
        } else {
            fromBootRestricted = false;
        }
        startInForeground();
        if (!serviceRunning) {
            startGatewayLoop();
        } else {
            syncCaptureWithFgsState(prevMicFgs);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        boolean prevMicFgs = micFgsGranted;
        fromBootRestricted = false;
        startInForeground();
        if (!serviceRunning) {
            startGatewayLoop();
        } else {
            syncCaptureWithFgsState(prevMicFgs);
        }
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        boolean prevMicFgs = micFgsGranted;
        fromBootRestricted = false;
        startInForeground();
        if (!serviceRunning) {
            startGatewayLoop();
        } else {
            syncCaptureWithFgsState(prevMicFgs);
        }
    }

    @Override
    public void onDestroy() {
        unregisterNetworkCallback();
        stopGatewayLoop();
        ioExecutor.shutdownNow();
        if (kwsEngine != null) {
            kwsEngine.stop();
        }
        if (hardwareAdapter != null) {
            hardwareAdapter.detach();
        }
        if (mediaSessionController != null) {
            mediaSessionController.release();
        }
        audioEngine.stopCapture();
        audioEngine.stopAndFlush();
        releaseWakeLock();
        super.onDestroy();
    }

    public void setUiTelemetryListener(UiTelemetryListener listener) {
        this.uiListener = listener;
        this.uiForeground = (listener != null);
        if (uiForeground) {
            refreshCoreSnapshotNow();
        }
    }

    public void setLiveEnergyListener(AudioEngine.EnergyListener listener) {
        if (audioEngine != null) {
            audioEngine.setEnergyListener(listener);
        }
    }

    public boolean isConnected() { return connected; }
    public boolean isManageOnlyMode() { return config != null && config.isManageOnly(); }
    public boolean isAuthRequired() {
        CoreApiClient.CoreSnapshot snap = transcriptState.getSnapshot();
        return voiceAuthRequired || apiAuthRequired || (snap != null && snap.authRequired);
    }
    public boolean isHttpManageOnline() {
        CoreApiClient.CoreSnapshot snap = transcriptState.getSnapshot();
        return snap != null && snap.reachable && !snap.authRequired;
    }
    public boolean isAnyOnline() { return connected || (isManageOnlyMode() && isHttpManageOnline()); }
    public String consumeTransientError() {
        String err = lastTransientError;
        lastTransientError = null;
        return err;
    }
    public List<CoreDiscovery.DiscoveredCore> getLastDiscoveredCores() { return lastDiscoveredCores; }
    public String getAssistantState() { return assistantState; }
    public double getLastRmsDbfs() { return lastRmsDbfs; }
    public double getLastNoiseFloorDbfs() { return lastNoiseFloorDbfs; }
    public double getLastSnrDb() { return lastSnrDb; }
    public long getBlocksSent() { return blocksSent; }
    public CoreApiClient.CoreSnapshot getCoreSnapshot() { return transcriptState.getSnapshot(); }
    public String getKwsEngineMode() { return kwsEngine != null ? kwsEngine.getActiveEngineMode() : LocalKwsEngine.MODE_ASR_HYBRID; }
    public long getKwsDetectionCount() { return kwsEngine != null ? kwsEngine.getDetectionCount() : 0L; }
    public boolean isHardwareAecActive() { return audioEngine != null && audioEngine.isHardwareAecEnabled(); }
    public boolean isSoftwareAecActive() { return audioEngine != null && audioEngine.isSoftwareAecEnabled(); }
    public double getSoftwareAecErleDb() { return audioEngine != null ? audioEngine.getSoftwareAecErleDb() : 0.0; }
    public homes.mymate.gateway.protocol.SoftwareAecFilter.AecTelemetry getActiveAecTelemetry() {
        return audioEngine != null
                ? audioEngine.getActiveAecTelemetry()
                : new homes.mymate.gateway.protocol.SoftwareAecFilter.AecTelemetry(
                        0.0, 0, -1, false, "unlocked", 0.0, 0.0, -96.0, -96.0);
    }
    public boolean isLocalMediaPlaying() { return audioEngine != null && audioEngine.hasActiveMediaPlayback(); }
    public String getOutputRouteName() { return audioEngine != null ? audioEngine.getOutputRouteName() : "Speaker"; }
    public boolean isR1HardwareDetected() { return hardwareAdapter != null && hardwareAdapter.isSpecializedHardwareDetected(); }
    public String getHardwareAdapterName() { return hardwareAdapter != null ? hardwareAdapter.getAdapterName() : "Generic-Android"; }

    private boolean canStartLiveMicCapture() {
        if (!hasRecordAudioPermission()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return !fromBootRestricted && micFgsGranted;
        }
        return true;
    }

    private void syncCaptureWithFgsState(boolean prevMicFgsGranted) {
        if (audioEngine == null || !hasRecordAudioPermission()) {
            return;
        }
        if (!canStartLiveMicCapture()) {
            return;
        }
        if (captureStartedWhileBootRestricted || (!prevMicFgsGranted && audioEngine.isCaptureRunning())) {
            captureStartedWhileBootRestricted = false;
            appendLog("AUDIO", "FGS upgraded to MICROPHONE; recreating AudioRecord cleanly");
            audioEngine.restartCapture();
        } else {
            audioEngine.ensureCaptureRunning();
        }
    }

    public void ensureCaptureRunningIfPermitted() {
        if (audioEngine == null || !hasRecordAudioPermission()) {
            return;
        }
        boolean prevMicFgs = micFgsGranted;
        if (Build.VERSION.SDK_INT >= 30 && !fromBootRestricted && !micFgsGranted) {
            startInForeground();
        }
        syncCaptureWithFgsState(prevMicFgs);
    }

    private void noteApiAuthFailure(String host, boolean authFailed, long expectedEpoch, boolean emitTransientToast) {
        if (!authFailed) {
            return;
        }
        if (expectedEpoch >= 0L && transcriptState.getCoreSwitchEpoch() != expectedEpoch) {
            return;
        }
        if (host == null || config.activeProfileMatchesHost(host)) {
            apiAuthRequired = true;
            if (emitTransientToast) {
                lastTransientError = I18n.t(config.getLanguage(), "auth_action_required_toast");
            }
            appendLog("AUTH", "HTTP 401/403 from " + (host != null ? host : config.getCoreHost())
                    + " — pairing token required");
            notifyUi();
        }
    }

    public boolean isMuted() {
        NorthboundSession s = activeSession;
        return s != null ? s.isMuted() : desiredMuted;
    }

    public double getVolumeLinear() {
        if (audioEngine != null) {
            return audioEngine.getCurrentHardwareVolumeLinear();
        }
        NorthboundSession s = activeSession;
        return s != null ? s.getVolumeLinear() : 0.65;
    }

    public synchronized String getProtocolLogText() {
        StringBuilder sb = new StringBuilder();
        for (String line : protocolLogs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String resolveEffectiveDeviceId() {
        return DeviceRoutingPolicy.resolveEffectiveDeviceId(
                config.getDeviceId(),
                config.isManageOnly(),
                transcriptState.getSnapshot());
    }

    private void resetCoreSwitchState() {
        transcriptState.resetForCoreSwitch();
        pendingConfirmation = null;
        lastFailedReflexSyncHash = "";
        voiceAuthRequired = false;
        apiAuthRequired = false;
        assistantState = "idle";
    }

    public void reloadConfigAndReconnect() {
        boolean prevMicFgs = micFgsGranted;
        fromBootRestricted = false;
        startInForeground();
        audioEngine.configure(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(), config.getUplinkGain());
        if (kwsEngine != null) {
            kwsEngine.setSensitivity(config.getKwsThreshold(), config.getKwsScore());
        }
        syncCaptureWithFgsState(prevMicFgs);
        // Advance Core-switch epoch BEFORE resetting snapshot so in-flight HTTP polls for the
        // previous Core cannot overwrite the newly selected Core's snapshot or KWS reflex table.
        resetCoreSwitchState();
        intentionalReconnect.set(true);
        closeActiveSocket();
        Thread ct = connectionThread;
        if (ct != null) {
            ct.interrupt();
        }
        refreshCoreSnapshotNow();
    }

    public void applyAcousticsOnly() {
        audioEngine.configure(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(), config.getUplinkGain());
        if (kwsEngine != null) {
            kwsEngine.setSensitivity(config.getKwsThreshold(), config.getKwsScore());
        }
        final NorthboundSession s = activeSession;
        if (s != null) {
            boolean prevAec = s.isAecActive();
            boolean aecOn = audioEngine.isHardwareAecEnabled() || config.isSoftwareAecEnabled();
            s.setAecActive(aecOn);
            s.setAecTailMs(aecOn ? 160 : 0);
            s.setKwsSensitivity(config.getKwsThreshold(), config.getKwsScore());
            if (prevAec != aecOn && connected && s.isHelloAckReceived()) {
                sendAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            s.sendEchoHealth(s.currentEchoHealth(), 0L, s.currentEchoHealthReason());
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
        }
        notifyUi();
    }

    private final Object reflexSyncLock = new Object();
    private volatile String lastFailedReflexSyncHash = "";

    private void syncReflexTableFromSnapshot(CoreApiClient.CoreSnapshot snap) {
        if (!connected || snap == null || snap.reflexTable == null || kwsEngine == null) {
            return;
        }
        NorthboundSession liveSession = activeSession;
        if (liveSession != null && !liveSession.shouldAllowHttpReflexSync()) {
            return;
        }
        // Wait until workspace prep & initial JNI/sidecar load finish so we never race KwsAssetInstaller
        // on kw.txt.tmp, never validate against an empty validTokenSet, and never deploy on slim APKs.
        if (!kwsEngine.isProcessReady()) {
            return;
        }
        CoreApiClient.ReflexSyncTable table = snap.reflexTable;
        if (table.compiledKeywordsTxt.isEmpty() || table.hash.isEmpty()) {
            return;
        }
        synchronized (reflexSyncLock) {
            NorthboundSession lockedSession = activeSession;
            if (!connected || !kwsEngine.isProcessReady()
                    || (lockedSession != null && !lockedSession.shouldAllowHttpReflexSync())) {
                return;
            }
            String currentHash = kwsEngine.getCurrentKeywordsHash();
            if (table.hash.equals(currentHash)) {
                // On-disk kw.txt already matches Core, but in-memory retreatWordSet resets on app restart:
                // perform O(1) in-memory sync with zero disk I/O and zero JNI reload.
                kwsEngine.setRetreatWords(table.retreatWords);
                NorthboundSession s = activeSession;
                if (s != null) {
                    s.syncLocalKwsMetadata(currentHash, table.wakeWords, table.retreatWords);
                }
                return;
            }
            if (table.hash.equals(lastFailedReflexSyncHash)) {
                return;
            }
            LocalKwsEngine.ValidationResult vr = kwsEngine.deployKeywords(
                    table.compiledKeywordsTxt,
                    table.retreatWords,
                    config.getKwsThreshold(),
                    config.getKwsScore());
            if (vr.valid) {
                lastFailedReflexSyncHash = "";
                NorthboundSession s = activeSession;
                if (s != null) {
                    s.syncLocalKwsMetadata(kwsEngine.getCurrentKeywordsHash(), table.wakeWords, table.retreatWords);
                }
                appendLog("KWS", "Synced " + vr.keywordCount + " reflex word(s) from Core (hash=" + table.hash + ")");
            } else {
                lastFailedReflexSyncHash = table.hash;
                appendLog("KWS", "Skipped Core reflex sync (" + vr.detail + ")");
            }
        }
    }

    public void refreshCoreSnapshotNow() {
        final long epoch = transcriptState.getCoreSwitchEpoch();
        final long tEpoch = transcriptState.getTranscriptEpoch();
        final String host = config.getCoreHost();
        final int webPort = config.getWebPort();
        final boolean manageOnly = config.isManageOnly();
        final String devId = resolveEffectiveDeviceId();
        final String token = config.getBearerToken();
        final String expectedCoreId = config.getActiveCoreId();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                CoreApiClient.CoreSnapshot snap = CoreApiClient.fetchSnapshot(
                        host, webPort, devId, token, true,
                        DeviceRoutingPolicy.allowSiblingMediaFallback(manageOnly),
                        expectedCoreId);
                CoreApiClient.CoreSnapshot committed = transcriptState.commitPolledSnapshot(
                        host, epoch, tEpoch, manageOnly, true, snap);
                if (committed != null) {
                    syncReflexTableFromSnapshot(committed);
                    syncHardwareAndMedia();
                    notifyUi();
                }
            }
        });
    }

    public void applyDeviceModeAsync(
            final String duplex,
            final Boolean bargeIn,
            final Boolean wakeAck,
            final String wakeArbitration,
            final ModeApplyCallback callback) {
        final long epoch = transcriptState.getCoreSwitchEpoch();
        final String host = config.getCoreHost();
        final int webPort = config.getWebPort();
        final boolean manageOnly = config.isManageOnly();
        final String token = config.getBearerToken();
        final String expectedCoreId = config.getActiveCoreId();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String targetDev = resolveEffectiveDeviceId();
                CoreApiClient.ActionOutcome outcome = CoreApiClient.updateDeviceModeWithResult(
                        host, webPort, targetDev,
                        duplex, bargeIn, wakeAck, wakeArbitration, token);
                boolean ok = outcome.ok;
                if (outcome.authRequired) {
                    noteApiAuthFailure(host, true, epoch, false);
                } else if (ok && transcriptState.getCoreSwitchEpoch() == epoch) {
                    apiAuthRequired = false;
                    long tEpoch = transcriptState.getTranscriptEpoch();
                    CoreApiClient.CoreSnapshot refreshed = CoreApiClient.fetchSnapshot(
                            host, webPort, targetDev, token, true,
                            DeviceRoutingPolicy.allowSiblingMediaFallback(manageOnly),
                            expectedCoreId);
                    CoreApiClient.CoreSnapshot committed = transcriptState.commitPolledSnapshot(
                            host, epoch, tEpoch, manageOnly, true, refreshed);
                    if (committed != null) {
                        notifyUi();
                    }
                }
                if (callback != null) {
                    callback.onModeApplied(ok, outcome.authRequired);
                }
            }
        });
    }

    public void runAmbientCalibrationAsync(final CalibrationCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                double sum = 0.0;
                int count = 0;
                for (int i = 0; i < 30; i++) {
                    sum += lastRmsDbfs;
                    count++;
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                double avgFloor = count > 0 ? (sum / count) : -58.0;
                float recGain;
                if (avgFloor < -65.0) {
                    recGain = 2.0f;
                } else if (avgFloor < -52.0) {
                    recGain = 1.5f;
                } else if (avgFloor < -42.0) {
                    recGain = 1.15f;
                } else {
                    recGain = 0.85f;
                }
                config.saveAcoustics(
                        config.isUseVoiceCommAec(),
                        config.isSoftwareAecEnabled(),
                        recGain,
                        config.getKwsThreshold(),
                        config.getKwsScore());
                applyAcousticsOnly();
                appendLog("CAL", String.format(Locale.US, "ambient=%.1fdBFS -> gain=%.2fx", avgFloor, recGain));
                if (callback != null) {
                    callback.onCalibrationDone(avgFloor, recGain);
                }
            }
        });
    }

    public void triggerUtterance(final String text) {
        sendUtteranceInternal(text, "android_app", true);
    }

    public void triggerMediaCommand(final String text) {
        sendUtteranceInternal(text, "android_media_session", false);
    }

    private static String mapMediaCommandToAction(String clean) {
        if ("暂停播放".equals(clean) || "暂停".equals(clean)) return "pause";
        if ("继续播放".equals(clean) || "继续".equals(clean)) return "resume";
        if ("下一首".equals(clean)) return "next";
        if ("上一首".equals(clean)) return "previous";
        if ("停止播放".equals(clean)) return "stop";
        return null;
    }

    private void sendUtteranceInternal(final String text, final String source, final boolean prependWake) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final String clean = text.trim();
        final String host = config.getCoreHost();
        final int webPort = config.getWebPort();
        final String token = config.getBearerToken();
        final boolean manageOnly = config.isManageOnly();
        final long epoch = transcriptState.getCoreSwitchEpoch();
        final String directAction = "android_media_session".equals(source)
                ? mapMediaCommandToAction(clean) : null;
        if (!manageOnly && (activeSession == null || !connected)) {
            String errMsg = I18n.t(config.getLanguage(), "voice_disconnected_reconnect");
            lastTransientError = errMsg;
            appendLog("CHAT", "Blocked unbound HTTP fallback while Voice Gateway disconnected (" + source + ")");
            syncHardwareAndMedia();
            notifyUi();
            return;
        }
        if (prependWake) {
            assistantState = "thinking";
        }
        syncHardwareAndMedia();
        notifyUi();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                NorthboundSession s = activeSession;
                DeviceRoutingPolicy.UtteranceRoute route = DeviceRoutingPolicy.resolveUtteranceRoute(
                        manageOnly, s != null && connected, directAction != null);
                if (route == DeviceRoutingPolicy.UtteranceRoute.NORTHBOUND_SESSION) {
                    try {
                        s.sendUtterance(clean, source, prependWake);
                        if (prependWake) {
                            transcriptState.appendLocalVoiceTurn(host, "user", clean, epoch, false);
                            notifyUi();
                        }
                        return;
                    } catch (IOException e) {
                        appendLog("ERR", "sendUtterance failed on Voice Gateway (no /api/v0/chat fallback): " + e.getMessage());
                        if (transcriptState.getCoreSwitchEpoch() == epoch) {
                            String errMsg = I18n.t(config.getLanguage(), "voice_disconnected_reconnect");
                            lastTransientError = errMsg;
                            if ("thinking".equalsIgnoreCase(assistantState)) {
                                assistantState = "idle";
                            }
                            syncHardwareAndMedia();
                            notifyUi();
                        }
                        return;
                    }
                }
                if (route == DeviceRoutingPolicy.UtteranceRoute.REJECT_VOICE_DISCONNECTED) {
                    appendLog("CHAT", "Voice Gateway disconnected; refused unbound HTTP /api/v0/chat fallback");
                    if (transcriptState.getCoreSwitchEpoch() == epoch) {
                        String errMsg = I18n.t(config.getLanguage(), "voice_disconnected_reconnect");
                        lastTransientError = errMsg;
                        if ("thinking".equalsIgnoreCase(assistantState)) {
                            assistantState = "idle";
                        }
                        syncHardwareAndMedia();
                        notifyUi();
                    }
                    return;
                }
                if (route == DeviceRoutingPolicy.UtteranceRoute.MANAGE_ONLY_MEDIA_CONTROL) {
                    CoreApiClient.ActionOutcome mediaOutcome = CoreApiClient.controlRemoteMediaWithResult(
                            host, webPort, resolveEffectiveDeviceId(), directAction, token);
                    if (mediaOutcome.authRequired) {
                        noteApiAuthFailure(host, true, epoch, true);
                    }
                    refreshCoreSnapshotNow();
                    return;
                }
                // P1-1: Atomically record the user turn, read manageOnlySessionIds, and capture
                // transcriptEpoch BEFORE calling sendChatTextWithReply so a concurrent Clear
                // cleanly discards the stale in-flight SSE reply & reply.sessionId.
                TranscriptStateStore.ManageSendTicket ticket =
                        transcriptState.beginManageOnlyChatTurn(host, clean, epoch, true);
                if (!ticket.accepted) {
                    return;
                }
                notifyUi();
                CoreApiClient.ChatReply reply = CoreApiClient.sendChatTextWithReply(
                        host, webPort, ticket.prevSessionId, token, clean);
                if (transcriptState.getCoreSwitchEpoch() == epoch) {
                    if (reply.authRequired) {
                        noteApiAuthFailure(host, true, epoch, true);
                    }
                    String assistantText = null;
                    if (reply.hasConfirmation()) {
                        String lang = config.getLanguage();
                        assistantText = reply.answerText != null && !reply.answerText.isEmpty()
                                ? reply.answerText
                                : (!reply.confirmationSummary.isEmpty()
                                        ? I18n.t(lang, "confirm_action_pending") + reply.confirmationSummary
                                        : I18n.t(lang, "confirm_action_pending_generic"));
                    } else if (reply.answerText != null && !reply.answerText.isEmpty()) {
                        assistantText = reply.answerText;
                    } else if (!reply.ok) {
                        String errText = (reply.error != null && !reply.error.isEmpty()) ? reply.error : "request_failed";
                        appendLog("CHAT", "Manage-Only chat failed: " + errText);
                        assistantText = "(" + errText + ")";
                    }
                    boolean committed = transcriptState.commitManageOnlyChatReply(
                            host, epoch, ticket.transcriptEpoch, true, reply.sessionId, assistantText);
                    if (committed && reply.hasConfirmation()) {
                        pendingConfirmation = new PendingConfirmation(
                                host, webPort, reply.confirmationId,
                                reply.confirmationSummary, reply.confirmationTool,
                                reply.confirmationState, reply.confirmationRevision);
                    }
                    if (!connected && "thinking".equalsIgnoreCase(assistantState)) {
                        assistantState = "idle";
                        syncHardwareAndMedia();
                    }
                    notifyUi();
                    if (committed) {
                        refreshCoreSnapshotNow();
                    }
                }
            }
        });
    }

    private List<String> collectSavedCandidateHosts() {
        List<String> extra = new java.util.ArrayList<String>();
        for (GatewayConfig.ServerProfile p : config.getServerProfiles()) {
            extra.addAll(p.allCandidateHosts());
        }
        return extra;
    }

    public void triggerAutoLanDiscoveryIfNeeded(final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastAutoScanMs) < 60_000L) {
            return;
        }
        if (!lanScanInFlight.compareAndSet(false, true)) {
            return;
        }
        lastAutoScanMs = now;
        sendAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    if (CoreDiscovery.detectRfc1918LanPrefixes().isEmpty() && config.getServerProfiles().isEmpty()) {
                        return;
                    }
                    boolean hadCoreBefore = config.hasConfiguredCore();
                    // Security (P0-1): NEVER pass saved Bearer tokens into unauthenticated LAN scans.
                    List<CoreDiscovery.DiscoveredCore> found = CoreDiscovery.scanLan(
                            config.getCoreHost(), collectSavedCandidateHosts(),
                            config.getWebPort(), config.getCorePort(), 450);
                    if (!found.isEmpty() || lastDiscoveredCores.isEmpty()) {
                        lastDiscoveredCores = found;
                    }
                    int added = config.mergeDiscoveredCores(found);
                    if (!found.isEmpty()) {
                        appendLog("DISC", "Auto-scan found " + found.size() + " Core(s) (deduped), merged +" + added);
                        if (!hadCoreBefore && config.hasConfiguredCore()) {
                            reloadConfigAndReconnect();
                        } else {
                            notifyUi();
                        }
                    }
                } finally {
                    lanScanInFlight.set(false);
                }
            }
        });
    }

    public void discoverLanCoresAsync(final boolean autoBindFirst, final DiscoveryCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                boolean hadCoreBefore = config.hasConfiguredCore();
                List<String> savedHosts = collectSavedCandidateHosts();
                String activeHost = config.getCoreHost();
                // Security (P0-1): NEVER pass saved Bearer tokens into unauthenticated LAN scans.
                List<CoreDiscovery.DiscoveredCore> found = CoreDiscovery.scanLan(
                        activeHost, savedHosts,
                        config.getWebPort(), config.getCorePort(), 450);
                if (found.isEmpty()) {
                    List<String> fallbackHosts = new java.util.ArrayList<String>(savedHosts);
                    if (activeHost != null && !activeHost.trim().isEmpty()) {
                        fallbackHosts.add(0, activeHost.trim());
                    }
                    if (!fallbackHosts.isEmpty()) {
                        List<CoreDiscovery.DiscoveredCore> fallbackFound =
                                CoreDiscovery.probeSavedHostsWithFleetFallback(
                                        fallbackHosts, config.getWebPort(), config.getCorePort(), 5000);
                        if (!fallbackFound.isEmpty()) {
                            found = fallbackFound;
                        }
                    }
                }
                if (!found.isEmpty() || lastDiscoveredCores.isEmpty()) {
                    lastDiscoveredCores = found;
                }
                int added = config.mergeDiscoveredCores(found);
                if (!found.isEmpty()) {
                    CoreDiscovery.DiscoveredCore best = found.get(0);
                    appendLog("DISC", "Scan found " + found.size() + " Core(s) (+" + added + "), best=" + best.toString());
                    if (autoBindFirst || (!hadCoreBefore && config.hasConfiguredCore())) {
                        config.upsertProfileAndSelect(
                                best.displayLabel(), best.host, best.gatewayPort, best.webPort,
                                null, best.coreId, best.alternateHosts,
                                Boolean.valueOf(!best.voiceGatewayOpen));
                        reloadConfigAndReconnect();
                    }
                }
                notifyUi();
                if (callback != null) {
                    callback.onCoresDiscovered(found);
                }
            }
        });
    }

    public void fetchAllRoomSummariesAsync(final RoomSummariesCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                List<GatewayConfig.ServerProfile> profiles = config.getServerProfiles();
                java.util.Map<String, GatewayConfig.ServerProfile> targets =
                        new java.util.LinkedHashMap<String, GatewayConfig.ServerProfile>();
                for (GatewayConfig.ServerProfile p : profiles) {
                    if (p.host != null && !p.host.isEmpty()) {
                        String key = !p.coreId.isEmpty()
                                ? ("id:" + p.coreId.toLowerCase(Locale.US))
                                : ("host:" + p.host.toLowerCase(Locale.US));
                        if (!targets.containsKey(key)) {
                            targets.put(key, p);
                        }
                    }
                }
                for (CoreDiscovery.DiscoveredCore c : lastDiscoveredCores) {
                    String key = !c.coreId.isEmpty()
                            ? ("id:" + c.coreId.toLowerCase(Locale.US))
                            : ("host:" + c.host.toLowerCase(Locale.US));
                    boolean alreadyCovered = targets.containsKey(key);
                    if (!alreadyCovered) {
                        for (GatewayConfig.ServerProfile p : targets.values()) {
                            if (p.matchesHost(c.host)) {
                                alreadyCovered = true;
                                break;
                            }
                        }
                    }
                    if (!alreadyCovered) {
                        targets.put(key, new GatewayConfig.ServerProfile(
                                c.displayLabel(), c.host, c.gatewayPort, c.webPort,
                                config.getBearerTokenForHost(c.host), c.coreId, c.alternateHosts));
                    }
                }
                if (targets.isEmpty()) {
                    if (callback != null) {
                        callback.onRoomSummaries(java.util.Collections.<CoreApiClient.RoomNodeSummary>emptyList());
                    }
                    return;
                }
                List<CoreApiClient.RoomTarget> roomTargets =
                        new java.util.ArrayList<CoreApiClient.RoomTarget>(targets.size());
                for (GatewayConfig.ServerProfile p : targets.values()) {
                    String tok = !p.bearerToken.isEmpty() ? p.bearerToken : config.getBearerTokenForHost(p.host);
                    roomTargets.add(new CoreApiClient.RoomTarget(
                            p.host, p.webPort, p.gatewayPort, p.name, tok, p.coreId, p.alternateHosts));
                }
                List<CoreApiClient.RoomNodeSummary> results =
                        CoreApiClient.fetchRoomSummariesConcurrently(roomTargets, 5500L);
                if (callback != null) {
                    callback.onRoomSummaries(results);
                }
            }
        });
    }

    public void sendRemoteRoomCommandAsync(
            final String host, final int webPort, final String text, final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                CoreApiClient.ChatReply reply = CoreApiClient.sendChatTextWithReply(
                        host, webPort, null, config.getBearerTokenForHost(host), text);
                if (reply.authRequired) {
                    noteApiAuthFailure(host, true, -1L, false);
                }
                if (callback != null) callback.onActionDone(reply.ok, reply.authRequired);
            }
        });
    }

    public void stopRemoteRoomAsync(
            final String host, final int webPort, final String deviceId, final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String tok = config.getBearerTokenForHost(host);
                // MG-P0-01/02: require an explicit target deviceId for remote HTTP stop;
                // never fall back to unbound POST /api/v0/chat.
                CoreApiClient.ActionOutcome outcome = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.stopRemoteDeviceWithResult(host, webPort, deviceId, tok)
                        : CoreApiClient.ActionOutcome.failure(0);
                if (outcome.authRequired) {
                    noteApiAuthFailure(host, true, -1L, false);
                }
                if (callback != null) callback.onActionDone(outcome.ok, outcome.authRequired);
            }
        });
    }

    public void controlRemoteRoomMediaAsync(
            final String host,
            final int webPort,
            final String deviceId,
            final String action,
            final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String tok = config.getBearerTokenForHost(host);
                CoreApiClient.ActionOutcome outcome = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.controlRemoteMediaWithResult(host, webPort, deviceId, action, tok)
                        : CoreApiClient.ActionOutcome.failure(0);
                if (outcome.authRequired) {
                    noteApiAuthFailure(host, true, -1L, false);
                }
                if (callback != null) callback.onActionDone(outcome.ok, outcome.authRequired);
            }
        });
    }

    public void setRemoteRoomVolumeAsync(
            final String host, final int webPort, final String deviceId, final double linearVol,
            final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String tok = config.getBearerTokenForHost(host);
                CoreApiClient.ActionOutcome outcome = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.setRemoteDeviceVolumeWithResult(host, webPort, deviceId, linearVol, tok)
                        : CoreApiClient.ActionOutcome.failure(0);
                if (outcome.authRequired) {
                    noteApiAuthFailure(host, true, -1L, false);
                }
                if (callback != null) callback.onActionDone(outcome.ok, outcome.authRequired);
            }
        });
    }

    public void triggerManualWake(final String keyword) {
        final NorthboundSession s = activeSession;
        if (s == null || !connected) {
            return;
        }
        final long now = System.currentTimeMillis();
        assistantState = "listening";
        syncHardwareAndMedia();
        notifyUi();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                NorthboundSession cur = activeSession;
                if (cur != null && connected) {
                    try {
                        cur.sendWake(keyword, 3.20, "touch");
                        cur.notifyWakeForUplinkAndFlushPreroll(now);
                    } catch (IOException e) {
                        appendLog("ERR", "sendWake failed: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void fetchConversationHistoryNowAsync(final HistoryCallback callback) {
        final long epoch = transcriptState.getCoreSwitchEpoch();
        final long tEpoch = transcriptState.getTranscriptEpoch();
        final String host = config.getCoreHost();
        final int webPort = config.getWebPort();
        final boolean manageOnly = config.isManageOnly();
        final String token = config.getBearerToken();
        final String targetDev = resolveEffectiveDeviceId();
        final CoreApiClient.CoreSnapshot snapAtCall = transcriptState.getSnapshot();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                if (host == null || host.isEmpty()) {
                    if (callback != null) {
                        callback.onHistoryLoaded(
                                java.util.Collections.<CoreApiClient.ChatEntry>emptyList(), "", false, false);
                    }
                    return;
                }
                String resolvedDev = targetDev;
                CoreApiClient.ConversationHistoryResult res =
                        CoreApiClient.fetchConversationHistory(host, webPort, targetDev, token);

                // In Manage-Only mode ONLY, if initial device has 0 messages, try first online device
                if (manageOnly && res.ok && res.messages.isEmpty()) {
                    String fallbackDev = "";
                    if (snapAtCall != null && snapAtCall.devices != null) {
                        for (CoreApiClient.DeviceInfo d : snapAtCall.devices) {
                            if (d != null && d.connected && d.id != null && !d.id.isEmpty()
                                    && !d.id.equals(targetDev)) {
                                fallbackDev = d.id;
                                break;
                            }
                        }
                    }
                    if (!fallbackDev.isEmpty()) {
                        CoreApiClient.ConversationHistoryResult alt =
                                CoreApiClient.fetchConversationHistory(host, webPort, fallbackDev, token);
                        if (alt.authRequired || (alt.ok && !alt.messages.isEmpty())) {
                            res = alt;
                            resolvedDev = fallbackDev;
                        }
                    }
                }

                if (res.authRequired) {
                    noteApiAuthFailure(host, true, epoch, false);
                }

                TranscriptStateStore.HistoryCommitResult hRes = transcriptState.commitConversationHistory(
                        host, epoch, tEpoch, manageOnly, resolvedDev, res);
                if (res.ok && hRes.fresh) {
                    notifyUi();
                }
                if (callback != null) {
                    callback.onHistoryLoaded(
                            hRes.merged,
                            hRes.currentSessionId,
                            res.authRequired,
                            res.ok && hRes.fresh);
                }
            }
        });
    }

    public void clearActiveConversationAsync(
            final String expectedSessionId,
            final ActionResultCallback callback) {
        clearActiveConversationAsync(expectedSessionId, true, callback);
    }

    public void clearActiveConversationAsync(
            final String expectedSessionId,
            final boolean includeArchived,
            final ActionResultCallback callback) {
        final long epoch = transcriptState.getCoreSwitchEpoch();
        final String host = config.getCoreHost();
        final int webPort = config.getWebPort();
        final String token = config.getBearerToken();
        final String pinnedDev = transcriptState.getLastHistoryDeviceId();
        final String targetDev = (pinnedDev != null && !pinnedDev.isEmpty())
                ? pinnedDev
                : resolveEffectiveDeviceId();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String sid = expectedSessionId != null ? expectedSessionId.trim() : "";
                boolean ok;
                boolean authRequired = false;
                if (host == null || host.isEmpty() || targetDev == null || targetDev.isEmpty()) {
                    ok = true;
                } else {
                    // If the UI did not yet have a session_id (e.g., cleared before initial
                    // history fetch finished), resolve the live head from Core first so the
                    // optimistic lock succeeds when a Core session exists.
                    if (sid.isEmpty()) {
                        CoreApiClient.ConversationHistoryResult probe =
                                CoreApiClient.fetchConversationHistory(host, webPort, targetDev, token);
                        if (probe.authRequired) {
                            noteApiAuthFailure(host, true, epoch, false);
                            if (callback != null) {
                                callback.onActionDone(false, true);
                            }
                            return;
                        }
                        if (probe.ok && probe.currentSessionId != null && !probe.currentSessionId.trim().isEmpty()) {
                            sid = probe.currentSessionId.trim();
                        } else if (!probe.ok && !transcriptState.isLastRawServerTranscriptEmpty()) {
                            if (callback != null) {
                                callback.onActionDone(false, false);
                            }
                            return;
                        }
                    }

                    if (sid.isEmpty() && transcriptState.isLastRawServerTranscriptEmpty()) {
                        // Local-only cached turns with no server session on Core.
                        ok = true;
                    } else {
                        CoreApiClient.ActionOutcome outcome = CoreApiClient.clearDeviceConversation(
                                host, webPort, targetDev, sid, includeArchived, token);
                        authRequired = outcome.authRequired;
                        if (outcome.authRequired) {
                            noteApiAuthFailure(host, true, epoch, false);
                        }
                        ok = outcome.ok;
                    }
                }
                boolean cleared = ok && transcriptState.clearConversation(host, epoch);
                if (cleared) {
                    pendingConfirmation = null;
                    notifyUi();
                    refreshCoreSnapshotNow();
                }
                if (callback != null) {
                    callback.onActionDone(cleared, authRequired);
                }
            }
        });
    }

    public void verifyRoomTokenAsync(
            final String host,
            final int webPort,
            final String rawToken,
            final TokenVerifyCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String clean = CoreApiClient.sanitizeBearerToken(rawToken);
                CoreApiClient.TokenVerifyResult result = CoreApiClient.verifyBearerTokenWithIdentity(host, webPort, clean);
                if (callback != null) {
                    callback.onTokenVerified(result, clean);
                }
            }
        });
    }

    /**
     * Resolve a wake keyword guaranteed to match the active Core's reflex table
     * (preferring "你好小智" when present, else first configured wake word, else "你好小智").
     */
    public String resolvePreferredWakeKeyword() {
        List<String> candidates = new java.util.ArrayList<String>();
        CoreApiClient.CoreSnapshot snap = transcriptState.getSnapshot();
        if (snap != null && snap.reflexTable != null && snap.reflexTable.wakeWords != null) {
            candidates.addAll(snap.reflexTable.wakeWords);
        }
        if (candidates.isEmpty()) {
            NorthboundSession s = activeSession;
            if (s != null) {
                candidates.addAll(s.getWakeKeywordsSnapshot());
            }
        }
        if (candidates.contains("你好小智")) {
            return "你好小智";
        }
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return "你好小智";
    }

    /**
     * Resolve a retreat keyword that is guaranteed to exist in the active Core's
     * reflex table (preferring "退下吧", then "停止播放", then first configured retreat word).
     */
    public String resolvePreferredRetreatKeyword() {
        List<String> candidates = new java.util.ArrayList<String>();
        NorthboundSession s = activeSession;
        if (s != null) {
            candidates.addAll(s.getRetreatKeywordsSnapshot());
        }
        CoreApiClient.CoreSnapshot snap = transcriptState.getSnapshot();
        if (candidates.isEmpty() && snap != null && snap.reflexTable != null && snap.reflexTable.retreatWords != null) {
            candidates.addAll(snap.reflexTable.retreatWords);
        }
        if (candidates.contains("退下吧")) {
            return "退下吧";
        }
        if (candidates.contains("停止播放")) {
            return "停止播放";
        }
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return "退下吧";
    }

    public void triggerManualRetreat(final String requestedKeyword) {
        final String keyword = (requestedKeyword != null && !requestedKeyword.trim().isEmpty())
                ? requestedKeyword.trim()
                : resolvePreferredRetreatKeyword();
        final long t0 = System.currentTimeMillis();
        final List<String> flushed = audioEngine.stopAndFlush();
        final long latencyMs = Math.max(1L, System.currentTimeMillis() - t0);
        acousticGuard.onPlaybackStopped();
        assistantState = "idle";
        syncHardwareAndMedia();
        notifyUi();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                NorthboundSession s = activeSession;
                if (s != null && connected) {
                    try {
                        s.sendRetreat(keyword, latencyMs, flushed, 0L, 0L, lastRmsDbfs);
                    } catch (IOException e) {
                        appendLog("ERR", "sendRetreat failed: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void toggleMute() {
        final boolean nextMuted = !isMuted();
        desiredMuted = nextMuted;
        config.setDesiredMuted(nextMuted);
        final NorthboundSession s = activeSession;
        if (s == null || !connected) {
            if (s != null) {
                s.setInitialMuted(nextMuted);
            }
            syncHardwareAndMedia();
            notifyUi();
            return;
        }
        sendAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    s.setMutedAndReport(nextMuted, "android_ui");
                    syncHardwareAndMedia();
                    notifyUi();
                } catch (IOException e) {
                    appendLog("ERR", "setMuted failed: " + e.getMessage());
                }
            }
        });
    }

    public void setVolumeFromUi(final double linearVolume) {
        final double actual = audioEngine.setHardwareVolume(linearVolume);
        final NorthboundSession s = activeSession;
        if (s != null && connected) {
            sendAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        s.sendVolumeReceipt("vol:ui:" + System.currentTimeMillis(), actual, "device_confirmed");
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }

    private void syncHardwareAndMedia() {
        if (hardwareAdapter != null) {
            hardwareAdapter.syncAssistantLedState(assistantState, isMuted(), connected);
        }
        if (mediaSessionController != null) {
            CoreApiClient.CoreSnapshot snap = transcriptState.getSnapshot();
            boolean mediaPlaying = audioEngine.hasActiveMediaPlayback()
                    || ((connected || config.isManageOnly()) && snap != null && snap.musicActive);
            String title = snap != null && snap.musicTitle != null && !snap.musicTitle.isEmpty()
                    ? snap.musicTitle
                    : config.getRoomName();
            String sub = snap != null && snap.assistantName != null && !snap.assistantName.isEmpty()
                    ? snap.assistantName
                    : "Home Mate";
            mediaSessionController.updatePlaybackState(mediaPlaying, title, sub);
        }
    }

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    onNetworkTopologyChanged(false);
                }

                @Override
                public void onLost(Network network) {
                    onNetworkTopologyChanged(true);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        onNetworkTopologyChanged(false);
                        triggerAutoLanDiscoveryIfNeeded(false);
                    }
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || networkCallback == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {
        }
        networkCallback = null;
    }

    private void onNetworkTopologyChanged(boolean networkLost) {
        if (!serviceRunning) {
            return;
        }
        if (networkLost && connected && CoreDiscovery.isRfc1918PrivateIp(config.getCoreHost())) {
            // Wi-Fi dropped while on LAN Core: close dead LAN socket immediately for instant Tailscale handoff
            appendLog("NET", "Active network lost while on LAN Core; triggering immediate handoff");
            closeActiveSocket();
            Thread ct = connectionThread;
            if (ct != null) {
                ct.interrupt();
            }
            return;
        }
        if (!connected) {
            Thread ct = connectionThread;
            if (ct != null) {
                ct.interrupt();
            }
            return;
        }
        // LAN-priority failback when returning home onto Wi-Fi while connected over Tailscale (100.64.0.0/10)
        if (config.isAutoFailoverEnabled() && CoreDiscovery.isTailscaleIp(config.getCoreHost())) {
            final String activeHostNow = config.getCoreHost();
            sendAsync(new Runnable() {
                @Override
                public void run() {
                    List<GatewayConfig.ServerProfile> profiles = config.getServerProfiles();
                    // 1. Prioritize RFC1918 LAN routes on the CURRENTLY ACTIVE profile first
                    // Security (P0-1): require pinned coreId and verify /api/v0/auth BEFORE transmitting bearerToken.
                    for (GatewayConfig.ServerProfile p : profiles) {
                        if (!p.matchesHost(activeHostNow) || p.coreId.isEmpty()) {
                            continue;
                        }
                        for (String candidate : p.allCandidateHosts()) {
                            if (CoreDiscovery.isRfc1918PrivateIp(candidate)) {
                                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                                        candidate, p.webPort, p.gatewayPort, 900, p.bearerToken, p.coreId);
                                if (probed != null && probed.voiceGatewayOpen
                                        && probed.coreId.equalsIgnoreCase(p.coreId)) {
                                    appendLog("NET", "Wi-Fi LAN route on active Core verified; failing back to " + candidate);
                                    config.upsertProfileAndSelect(
                                            p.name, candidate, probed.gatewayPort, p.webPort,
                                            p.bearerToken, p.coreId,
                                            p.allCandidateHosts());
                                    reloadConfigAndReconnect();
                                    return;
                                }
                            }
                        }
                    }
                    // 2. Fallback: scan other saved profiles' LAN routes (only with pinned coreId verification)
                    for (GatewayConfig.ServerProfile p : profiles) {
                        if (p.matchesHost(activeHostNow) || p.coreId.isEmpty()) {
                            continue;
                        }
                        for (String candidate : p.allCandidateHosts()) {
                            if (CoreDiscovery.isRfc1918PrivateIp(candidate)) {
                                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                                        candidate, p.webPort, p.gatewayPort, 900, p.bearerToken, p.coreId);
                                if (probed != null && probed.voiceGatewayOpen
                                        && probed.coreId.equalsIgnoreCase(p.coreId)) {
                                    appendLog("NET", "Wi-Fi LAN Core verified; failing back to " + candidate);
                                    config.upsertProfileAndSelect(
                                            p.name, candidate, probed.gatewayPort, p.webPort,
                                            p.bearerToken, p.coreId,
                                            p.allCandidateHosts());
                                    reloadConfigAndReconnect();
                                    return;
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private synchronized void startGatewayLoop() {
        if (serviceRunning) {
            return;
        }
        serviceRunning = true;
        audioEngine.configure(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(), config.getUplinkGain());
        if (canStartLiveMicCapture()) {
            captureStartedWhileBootRestricted = false;
            audioEngine.startCapture();
        } else if (hasRecordAudioPermission()) {
            // P1-4: On Android 11-14 boot-restricted FGS, do NOT open a silenced AudioRecord
            // before foreground FGS upgrades to FOREGROUND_SERVICE_TYPE_MICROPHONE.
            captureStartedWhileBootRestricted = false;
            appendLog("FGS", "Boot-restricted FGS active; deferring AudioRecord until MICROPHONE upgrade");
        } else {
            appendLog("PERM", "Waiting for RECORD_AUDIO permission");
        }

        connectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runConnectionLoop();
            }
        }, "hm-northbound-v2");
        connectionThread.setDaemon(true);
        connectionThread.start();

        apiPollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runApiPollLoop();
            }
        }, "hm-core-api-poll");
        apiPollThread.setDaemon(true);
        apiPollThread.start();

        triggerAutoLanDiscoveryIfNeeded(false);
    }

    private synchronized void stopGatewayLoop() {
        serviceRunning = false;
        closeActiveSocket();
        if (connectionThread != null) {
            connectionThread.interrupt();
            connectionThread = null;
        }
        if (apiPollThread != null) {
            apiPollThread.interrupt();
            apiPollThread = null;
        }
    }

    private void closeActiveSocket() {
        TwoTierOutboundTransport tx = activeTxTransport;
        activeTxTransport = null;
        if (tx != null) {
            tx.stop();
        }
        Socket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void runApiPollLoop() {
        int pollCount = 0;
        while (serviceRunning && !Thread.currentThread().isInterrupted()) {
            try {
                // Keep TX write-stall watchdog alive even when AudioRecord capture is paused/restricted
                TwoTierOutboundTransport tx = activeTxTransport;
                if (tx != null) {
                    tx.checkWriteStall();
                }
                if (uiForeground || audioEngine.hasActiveMediaPlayback()) {
                    boolean fullFetch = (pollCount % 3 == 0);
                    pollCount++;
                    final long pollEpoch = transcriptState.getCoreSwitchEpoch();
                    final long pollTranscriptEpoch = transcriptState.getTranscriptEpoch();
                    final String pollHost = config.getCoreHost();
                    final int pollWebPort = config.getWebPort();
                    final String pollToken = config.getBearerToken();
                    final String pollExpectedCoreId = config.getActiveCoreId();
                    boolean pollManageOnly = config.isManageOnly();
                    String pollDevId = resolveEffectiveDeviceId();
                    CoreApiClient.CoreSnapshot snap = CoreApiClient.fetchSnapshot(
                            pollHost, pollWebPort, pollDevId,
                            pollToken, fullFetch,
                            DeviceRoutingPolicy.allowSiblingMediaFallback(pollManageOnly),
                            pollExpectedCoreId);
                    CoreApiClient.CoreSnapshot committed = transcriptState.commitPolledSnapshot(
                            pollHost, pollEpoch, pollTranscriptEpoch, pollManageOnly, fullFetch, snap);
                    if (committed != null) {
                        syncReflexTableFromSnapshot(committed);
                        syncHardwareAndMedia();
                        notifyUi();
                    }
                }
                Thread.sleep(uiForeground ? 2000L : 8000L);
            } catch (InterruptedException ignored) {
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void tryProfileFailover() {
        if (!config.isAutoFailoverEnabled()) {
            return;
        }
        String currentHost = config.getCoreHost();
        List<GatewayConfig.ServerProfile> profiles = config.getServerProfiles();
        // 1. First try alternate routes (e.g. Tailscale <-> LAN) on the SAME active Core profile
        // Security (P0-1): verify /api/v0/auth core_id match before transmitting bearerToken or switching.
        for (GatewayConfig.ServerProfile p : profiles) {
            if (p.matchesHost(currentHost) && !p.coreId.isEmpty()) {
                for (String alt : p.allCandidateHosts()) {
                    if (alt.equalsIgnoreCase(currentHost)) {
                        continue;
                    }
                    CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                            alt, p.webPort, p.gatewayPort, 1200, p.bearerToken, p.coreId);
                    if (probed != null && probed.coreId.equalsIgnoreCase(p.coreId)) {
                        appendLog("NET", "Route failover on " + p.name + " -> " + alt);
                        config.upsertProfileAndSelect(
                                p.name, alt, probed.gatewayPort, p.webPort,
                                p.bearerToken, p.coreId,
                                p.allCandidateHosts());
                        resetCoreSwitchState();
                        refreshCoreSnapshotNow();
                        return;
                    }
                }
            }
        }
        // 2. Otherwise try other saved Core profiles via identity-verified HTTP probe only
        for (GatewayConfig.ServerProfile p : profiles) {
            if (p.matchesHost(currentHost) || p.coreId.isEmpty()) {
                continue;
            }
            for (String candidate : p.allCandidateHosts()) {
                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                        candidate, p.webPort, p.gatewayPort, 1200, p.bearerToken, p.coreId);
                if (probed != null && probed.voiceGatewayOpen
                        && probed.coreId.equalsIgnoreCase(p.coreId)) {
                    appendLog("NET", "Auto-Failover switched to " + p.name + " (" + candidate + ")");
                    config.upsertProfileAndSelect(
                            p.name, candidate, probed.gatewayPort, p.webPort,
                            p.bearerToken, p.coreId,
                            p.allCandidateHosts());
                    resetCoreSwitchState();
                    refreshCoreSnapshotNow();
                    return;
                }
            }
        }
    }

    private void runConnectionLoop() {
        int consecutiveFailures = 0;
        while (serviceRunning) {
            Thread.interrupted(); // clear any stale wakeup interrupt before connecting
            String host = config.getCoreHost();
            int port = config.getCorePort();
            String deviceId = config.getDeviceId();
            String model = config.getDeviceModel();

            if (host == null || host.trim().isEmpty()) {
                connected = false;
                voiceAuthRequired = false;
                connectionStatus = "Unconfigured";
                updateNotification("Waiting for Home Mate Core...");
                notifyUi();
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
                continue;
            }

            if (config.isManageOnly()) {
                connected = false;
                voiceAuthRequired = false;
                boolean httpReachable = isHttpManageOnline()
                        || CoreApiClient.probeCoreHttp(host, config.getWebPort(), config.getActiveCoreId(), 800);
                if (httpReachable) {
                    connectionStatus = "Manage · " + host;
                    updateNotification("Online (Manage) · " + host);
                } else {
                    connectionStatus = "Offline (" + host + ")";
                    updateNotification("Offline · " + host);
                }
                syncHardwareAndMedia();
                notifyUi();
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
                continue;
            }

            connected = false;
            connectionStatus = host + ":" + port;
            notifyUi();

            boolean skipBackoff = false;
            final boolean[] rejectedUnauthorized = new boolean[]{false};
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                try {
                    socket.setReceiveBufferSize(262_144);
                } catch (Throwable ignored) {
                }
                socket.connect(new InetSocketAddress(host, port), 3500);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(45_000);
                consecutiveFailures = 0;
                intentionalReconnect.set(false);

                final BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 65536);

                final TwoTierOutboundTransport txTransport = new TwoTierOutboundTransport(socket, writer);
                activeTxTransport = txTransport;

                NorthboundSession session = new NorthboundSession(
                        deviceId,
                        model,
                        NorthboundSession.GATEWAY_VERSION + "-android",
                        processBootId,
                        txTransport,
                        new NorthboundSession.BaseListener() {
                            @Override
                            public void onHelloAck(long generation, int negotiatedProtocol) {
                                connected = true;
                                voiceAuthRequired = false;
                                lastFailedReflexSyncHash = "";
                                connectionStatus = host + " · v" + negotiatedProtocol;
                                updateNotification("Online · " + host);
                                syncHardwareAndMedia();
                                refreshCoreSnapshotNow();
                                notifyUi();
                            }

                            @Override
                            public void onHelloRejected(String reason, int minProtocol, int maxProtocol) {
                                connected = false;
                                if ("unauthorized".equalsIgnoreCase(reason)) {
                                    voiceAuthRequired = true;
                                }
                                if (NorthboundSession.isBackoffRejectReason(reason)) {
                                    rejectedUnauthorized[0] = true;
                                }
                                connectionStatus = "Rejected: " + reason;
                                appendLog("NET", "hello_reject: " + reason + " (core supports v" + minProtocol + "-v" + maxProtocol + ")");
                                notifyUi();
                                closeActiveSocket();
                            }

                            @Override
                            public void onModeChanged(String duplexMode, boolean lowLatency) {
                                NorthboundSession cur = activeSession;
                                if (cur != null) {
                                    config.setConfigV2StateJson(cur.exportPersistedConfigStateJson());
                                }
                                notifyUi();
                            }

                            @Override
                            public void onAssistantStateChanged(String state) {
                                assistantState = state;
                                syncHardwareAndMedia();
                                notifyUi();
                            }

                            @Override
                            public double onVolumeChanged(double volume, String taskId) {
                                double actual = audioEngine.setHardwareVolume(volume);
                                notifyUi();
                                return actual;
                            }

                            @Override
                            public void onMuteChanged(boolean muted, String source) {
                                desiredMuted = muted;
                                config.setDesiredMuted(muted);
                                syncHardwareAndMedia();
                                notifyUi();
                            }

                            @Override
                            public void onPlayWav(String taskId, String channel, byte[] wavBytes) {
                                if (!"media".equalsIgnoreCase(channel) && !"music".equalsIgnoreCase(channel)) {
                                    assistantState = "speaking";
                                }
                                syncHardwareAndMedia();
                                notifyUi();
                                audioEngine.playWav(taskId, channel, wavBytes);
                            }

                            @Override
                            public void onPlayStreamBegin(String taskId, String channel, int sampleRate, int channels, String format) {
                                if (!"media".equalsIgnoreCase(channel) && !"music".equalsIgnoreCase(channel)) {
                                    assistantState = "speaking";
                                }
                                syncHardwareAndMedia();
                                notifyUi();
                                audioEngine.beginStream(taskId, channel, sampleRate, channels);
                            }

                            @Override
                            public void onPlayStreamChunk(String taskId, long seq, byte[] pcmBytes) {
                                audioEngine.writeStreamChunk(taskId, pcmBytes);
                            }

                            @Override
                            public void onPlayStreamEnd(String taskId) {
                                audioEngine.endStream(taskId);
                            }

                            @Override
                            public NorthboundSession.StopPlaybackResult onStopPlayback(String taskId, boolean flush, String reason) {
                                NorthboundSession.StopPlaybackResult res = audioEngine.stopStream(taskId);
                                if (!audioEngine.hasActiveMediaPlayback() && !audioEngine.hasActiveVoicePlayback()) {
                                    acousticGuard.onPlaybackStopped();
                                    assistantState = "idle";
                                }
                                syncHardwareAndMedia();
                                notifyUi();
                                return res;
                            }

                            @Override
                            public void onKwsConfigUpdated(double threshold, double score, List<String> keywords) {
                                // P1-2: Mobile KWS sensitivity guard already executed inside NorthboundSession
                                // BEFORE JNI reload, persistence, and receipt emission (single JNI reload).
                                float nextThr = (float) threshold;
                                float nextSc = (float) score;
                                if (Math.abs(nextThr - config.getKwsThreshold()) > 0.005f
                                        || Math.abs(nextSc - config.getKwsScore()) > 0.02f) {
                                    config.saveAcoustics(
                                            config.isUseVoiceCommAec(),
                                            config.isSoftwareAecEnabled(),
                                            config.getUplinkGain(),
                                            nextThr,
                                            nextSc);
                                }
                                NorthboundSession cur = activeSession;
                                if (cur != null) {
                                    config.setConfigV2StateJson(cur.exportPersistedConfigStateJson());
                                }
                                notifyUi();
                            }

                            @Override
                            public void onConfigBlockApplied(String block, Map<String, Object> values) {
                                NorthboundSession cur = activeSession;
                                if (cur != null) {
                                    config.setConfigV2StateJson(cur.exportPersistedConfigStateJson());
                                }
                                if ("kws".equals(block)) {
                                    // P1-2: Guarded effective threshold/score already applied to LocalKwsEngine,
                                    // session state, configBlocks["kws"], and config_receipt by NorthboundSession.
                                    float nextThr = (float) MiniJson.getDouble(values, "threshold", config.getKwsThreshold());
                                    float nextSc = (float) MiniJson.getDouble(values, "score", config.getKwsScore());
                                    if (Math.abs(nextThr - config.getKwsThreshold()) > 0.005f
                                            || Math.abs(nextSc - config.getKwsScore()) > 0.02f) {
                                        config.saveAcoustics(
                                                config.isUseVoiceCommAec(),
                                                config.isSoftwareAecEnabled(),
                                                config.getUplinkGain(),
                                                nextThr,
                                                nextSc);
                                    }
                                    notifyUi();
                                }
                                // Note: "acoustics" (config_block_acoustics) is intentionally NOT advertised by
                                // NorthboundSession — Android manages local AEC/uplink gain via GatewayConfig, while
                                // hm-core owns per-unit volume_cap clamping and mic_gain_offset_db wake calibration.
                            }

                            @Override
                            public void onProtocolFrameLog(String direction, String frameType, String rawJson) {
                                if ("audio_chunk".equals(frameType) || "near_end_presence".equals(frameType) || "play_chunk".equals(frameType)) {
                                    return;
                                }
                                String summary = rawJson.length() > 140 ? rawJson.substring(0, 140) + "..." : rawJson;
                                appendLog(direction, frameType + " " + summary);
                            }
                        });

                // Restore persisted config_apply_v2 state_revision, per-block block_generation,
                // active_values, and duplex mode across reconnects BEFORE sending hello/config_state.
                session.restorePersistedConfigStateJson(config.getConfigV2StateJson());
                boolean aecOn = audioEngine.isHardwareAecEnabled() || config.isSoftwareAecEnabled();
                session.setAecActive(aecOn);
                session.setAecTailMs(aecOn ? 160 : 0);
                session.setInitialMuted(desiredMuted);
                session.setInitialVolumeLinear(audioEngine.getCurrentHardwareVolumeLinear());
                if (session.getBlockGeneration("kws") == 0L
                        || DeviceRoutingPolicy.isDot2DefaultKwsSensitivity(
                                session.getKwsThreshold(), session.getKwsScore())) {
                    session.setKwsSensitivity(config.getKwsThreshold(), config.getKwsScore());
                }
                session.setAuthToken(config.getBearerToken());
                session.setLocalOfflineKwsReady(kwsEngine != null && kwsEngine.isProcessReady());
                session.attachLocalKwsEngine(kwsEngine);
                activeSession = session;
                session.sendHello();

                while (serviceRunning && !socket.isClosed()) {
                    try {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        try {
                            session.handleIncomingLine(line);
                        } catch (IllegalArgumentException badJson) {
                            appendLog("WARN", "Ignored malformed frame: " + badJson.getMessage());
                        }
                    } catch (SocketTimeoutException idleTimeout) {
                        // Check half-open TX write stall even when mic capture is inactive
                        TwoTierOutboundTransport tx = activeTxTransport;
                        if (tx != null) {
                            tx.checkWriteStall();
                        }
                        if (socket.isClosed()) {
                            break;
                        }
                        // Send honest lightweight keepalive probe to verify half-open socket
                        try {
                            String kh = session.currentEchoHealth();
                            String kr = "healthy".equals(kh) ? "keepalive_ok" : "aec_disabled";
                            session.sendEchoHealth(kh, 0L, kr);
                        } catch (IOException probeErr) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                boolean wasIntentional = intentionalReconnect.getAndSet(false);
                connected = false;
                if (!wasIntentional) {
                    // MG-P0-01/02: A Voice Gateway profile (!config.isManageOnly()) MUST stay in
                    // Voice Gateway reconnect mode on transient :8932 disconnect and NEVER silently
                    // morph into Manage-Only mode or hijack a sibling speaker.
                    consecutiveFailures++;
                    connectionStatus = "Reconnecting (" + host + ")";
                    appendLog("NET", "Disconnected [" + host + ":" + port + "]: " + e.getClass().getSimpleName());
                    updateNotification("Reconnecting...");
                    syncHardwareAndMedia();
                    notifyUi();

                    if (consecutiveFailures >= 1) {
                        tryProfileFailover();
                    }
                } else {
                    skipBackoff = true;
                    appendLog("NET", "Reconnecting to " + config.getCoreHost() + ":" + config.getCorePort());
                }
            } finally {
                connected = false;
                activeSession = null;
                closeActiveSocket();
                audioEngine.stopAndFlush();
                acousticGuard.reset();
            }

            if (serviceRunning) {
                if (skipBackoff || intentionalReconnect.getAndSet(false)) {
                    Thread.interrupted(); // clear interrupt flag from reloadConfigAndReconnect()
                    continue; // Immediate reconnect on explicit user switch
                }
                long backoffMs = rejectedUnauthorized[0]
                        ? 60_000L
                        : Math.min(8000L, 1500L + (long) consecutiveFailures * 1000L);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ignored) {
                    // Interrupted intentionally to reconnect immediately
                    Thread.interrupted();
                }
            }
        }
    }

    private void sendAsync(Runnable task) {
        try {
            ioExecutor.execute(task);
        } catch (Throwable ignored) {
        }
    }

    private synchronized void appendLog(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        String entry = sdf.format(new Date()) + " [" + tag + "] " + message;
        while (protocolLogs.size() >= MAX_LOG_LINES) {
            protocolLogs.pollFirst();
        }
        protocolLogs.addLast(entry);
    }

    private void notifyUi() {
        UiTelemetryListener l = uiListener;
        if (l != null) {
            l.onTelemetryUpdated();
        }
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "Home Mate Voice Gateway",
                    NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("Keeps Home Mate offline KWS and voice gateway active");
            nm.createNotificationChannel(chan);
        }
        Notification notification = buildNotification(
                connected ? ("Online · " + config.getCoreHost()) : "Connecting to Home Mate Core...");
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                boolean wantsMicFgs = hasRecordAudioPermission() && !fromBootRestricted;
                int fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                if (wantsMicFgs) {
                    fgsType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, fgsType);
                micFgsGranted = wantsMicFgs;
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                micFgsGranted = hasRecordAudioPermission();
            } else {
                startForeground(NOTIFICATION_ID, notification);
                micFgsGranted = hasRecordAudioPermission();
            }
        } catch (SecurityException se) {
            micFgsGranted = false;
            appendLog("FGS", "Microphone FGS restricted (" + se.getMessage() + "), falling back to mediaPlayback");
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Throwable fatal) {
                appendLog("FGS", "Unable to enter foreground: " + fatal.getMessage());
                stopSelf();
            }
        } catch (Throwable t) {
            micFgsGranted = false;
            appendLog("FGS", "startForeground failed: " + t.getMessage());
            stopSelf();
        }
    }

    private void updateNotification(String statusText) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(statusText));
            } catch (Throwable ignored) {
            }
        }
    }

    private Notification buildNotification(String statusText) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("Home Mate")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_home_mate)
                .setContentIntent(contentIntent)
                .setOngoing(true);
        if (mediaSessionController != null && mediaSessionController.getSessionToken() != null
                && (audioEngine != null && audioEngine.hasActiveMediaPlayback())) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSessionController.getSessionToken()));
        }
        return builder.build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeMateGateway:VoiceLoop");
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
