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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import homes.mymate.gateway.protocol.AcousticGuard;
import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.CoreDiscovery;
import homes.mymate.gateway.protocol.LocalKwsEngine;
import homes.mymate.gateway.protocol.MiniJson;
import homes.mymate.gateway.protocol.NorthboundSession;

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
    private static final int AUDIO_TX_QUEUE_CAP = 16;
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
        void onModeApplied(boolean ok);
    }

    public interface RoomSummariesCallback {
        void onRoomSummaries(List<CoreApiClient.RoomNodeSummary> summaries);
    }

    public interface ActionResultCallback {
        void onActionDone(boolean ok);
    }

    public final class LocalBinder extends Binder {
        public GatewayService getService() {
            return GatewayService.this;
        }
    }

    /**
     * 2-tier non-blocking outbound transport decoupling audio capture from socket flush latency.
     */
    private final class TwoTierOutboundTransport implements NorthboundSession.FrameTransport {
        private final Socket socket;
        private final BufferedWriter writer;
        private final LinkedBlockingQueue<String> controlQueue = new LinkedBlockingQueue<String>(256);
        private final ArrayBlockingQueue<String> audioQueue = new ArrayBlockingQueue<String>(AUDIO_TX_QUEUE_CAP);
        private final Semaphore permits = new Semaphore(0);
        private volatile boolean alive = true;
        private volatile long writeStartedMs = 0L;
        private final Thread txThread;

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
            if (!alive) {
                throw new IOException("Outbound transport closed");
            }
            boolean isAudioBulk = jsonLine.contains("\"type\":\"audio_chunk\"")
                    || jsonLine.contains("\"type\":\"near_end_presence\"");
            if (isAudioBulk) {
                while (!audioQueue.offer(jsonLine)) {
                    audioQueue.poll(); // drop oldest audio frame under Tailscale jitter
                }
                permits.release();
            } else {
                if (!controlQueue.offer(jsonLine)) {
                    controlQueue.poll();
                    controlQueue.offer(jsonLine);
                }
                permits.release();
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
            permits.release();
            txThread.interrupt();
        }

        private void runTxLoop() {
            try {
                while (alive && !Thread.currentThread().isInterrupted()) {
                    permits.tryAcquire(250L, TimeUnit.MILLISECONDS);
                    String line = controlQueue.poll();
                    if (line == null) {
                        line = audioQueue.poll();
                    }
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
    private final AtomicBoolean intentionalReconnect = new AtomicBoolean(false);
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
    private volatile String assistantState = "idle";
    private volatile double lastRmsDbfs = -72.0;
    private volatile double lastNoiseFloorDbfs = -58.0;
    private volatile double lastSnrDb = 0.0;
    private volatile long blocksSent = 0;
    private volatile long lastAudioUiNotifyMs = 0L;
    private volatile boolean localVadSpeech = false;
    private volatile String lastActionSubtitle = "";
    private volatile CoreApiClient.CoreSnapshot coreSnapshot = CoreApiClient.CoreSnapshot.offline("init");
    private volatile List<CoreDiscovery.DiscoveredCore> lastDiscoveredCores = java.util.Collections.emptyList();
    private volatile long lastAutoScanMs = 0L;
    private final AtomicBoolean lanScanInFlight = new AtomicBoolean(false);
    private final Deque<String> protocolLogs = new ArrayDeque<String>(MAX_LOG_LINES);

    @Override
    public void onCreate() {
        super.onCreate();
        config = new GatewayConfig(this);
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
                triggerManualRetreat("停止播放");
            }
        });
        startInForeground();

        // Prepare KWS workspace on background hm-kws-engine thread (never blocks Main thread)
        File kwsWorkDir = KwsAssetInstaller.getKwsWorkDir(this);
        String nativeDirPath = getApplicationInfo().nativeLibraryDir;
        File nativeDir = nativeDirPath != null ? new File(nativeDirPath) : null;

        kwsEngine = new LocalKwsEngine(kwsWorkDir, nativeDir, config.getDeviceId(), new LocalKwsEngine.EventListener() {
            @Override
            public void onKwsReady(double threshold, double score, String loadedHash) {
                appendLog("KWS", String.format(Locale.US, "engine=%s ready (thr=%.2f score=%.2f)",
                        kwsEngine.getActiveEngineMode(), threshold, score));
                NorthboundSession s = activeSession;
                if (s != null) {
                    s.setLocalOfflineKwsReady(true);
                    if (connected) {
                        try {
                            s.sendKwsReport("running", String.format(Locale.US,
                                    "%s ready (threshold=%.2f score=%.2f)",
                                    kwsEngine.getActiveEngineMode(), threshold, score));
                        } catch (IOException ignored) {
                        }
                    }
                }
                notifyUi();
            }

            @Override
            public void onKeywordDetected(final LocalKwsEngine.KwsEvent hit, final boolean isRetreatWord) {
                final long now = System.currentTimeMillis();
                final boolean selfHeard = !isRetreatWord
                        && !audioEngine.isSoftwareAecEnabled()
                        && acousticGuard.isSelfHeardEcho(lastRmsDbfs, now);
                if (isRetreatWord) {
                    final long t0 = System.currentTimeMillis();
                    final List<String> flushed = audioEngine.stopAndFlush();
                    final long latencyMs = Math.max(1L, System.currentTimeMillis() - t0);
                    acousticGuard.onPlaybackStopped();
                    assistantState = "idle";
                    lastActionSubtitle = "KWS: " + hit.keyword;
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
                    assistantState = "listening";
                    lastActionSubtitle = "KWS: " + hit.keyword;
                    syncHardwareAndMedia();
                    notifyUi();
                    sendAsync(new Runnable() {
                        @Override
                        public void run() {
                            NorthboundSession s = activeSession;
                            if (s != null && connected) {
                                try {
                                    // Flush recent local preroll PCM BEFORE sending keyword so one-breath utterances are preserved
                                    s.notifyWakeForUplinkAndFlushPreroll(now);
                                    s.sendKwsKeywordFact(hit, lastRmsDbfs, selfHeard);
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    });
                }
            }

            @Override
            public void onVadSpeechChanged(boolean speech) {
                localVadSpeech = speech;
                notifyUi();
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
                KwsAssetInstaller.prepareKwsWorkspace(GatewayService.this);
            }
        });
        kwsEngine.setSensitivity(config.getKwsThreshold(), config.getKwsScore());
        kwsEngine.start();

        audioEngine = new AudioEngine(this, new AudioEngine.CaptureCallback() {
            @Override
            public void onAudioBlockCaptured(byte[] pcm16leBytes, double rmsDbfs, double noiseFloorDbfs, double snrDb, boolean aecActive) {
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
                    kwsEngine.feedPcmBlock(pcm16leBytes, nowMs, snrDb < 5.0);
                }

                NorthboundSession session = activeSession;
                boolean bargeInAllowed = session != null
                        && "full".equalsIgnoreCase(session.getDuplexMode())
                        && audioEngine.hasActiveVoicePlayback();

                final AcousticGuard.BargeInFact bargeFact = acousticGuard.pushCaptureBlockAndCheckBargeIn(
                        pcm16leBytes, rmsDbfs, noiseFloorDbfs, bargeInAllowed, nowMs);

                if (session != null && connected && session.isHelloAckReceived()) {
                    try {
                        if (session.sendAudioChunkWithGate(pcm16leBytes, rmsDbfs, noiseFloorDbfs, bargeInAllowed, nowMs)) {
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
            public void onPlaybackFinished(String taskId, long playedMs) {
                syncHardwareAndMedia();
                NorthboundSession session = activeSession;
                if (session != null && connected) {
                    try {
                        session.sendPlayFinished(taskId, playedMs);
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onAudioError(String stage, String message) {
                appendLog("AUDIO", stage + ": " + message);
                lastActionSubtitle = message;
                notifyUi();
            }
        });

        hardwareAdapter = HardwareAdapter.Factory.createAndAttach(
                this,
                config.isR1HookEnabled(),
                new R1HardwareHook.HardwareEventListener() {
                    @Override
                    public void onHardwareWakeTriggered(String keyword) {
                        triggerManualWake(keyword);
                    }

                    @Override
                    public void onHardwareTopButtonShortPress() {
                        triggerManualRetreat("退下");
                    }
                });

        registerNetworkCallback();
        startGatewayLoop();
        triggerAutoLanDiscoveryIfNeeded(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) {
            fromBootRestricted = (Build.VERSION.SDK_INT >= 30);
        } else {
            fromBootRestricted = false;
        }
        startInForeground();
        if (!serviceRunning) {
            startGatewayLoop();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        fromBootRestricted = false;
        startInForeground();
        return binder;
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

    public GatewayConfig getConfig() { return config; }
    public boolean isConnected() { return connected; }
    public boolean isVoiceAuthRequired() { return voiceAuthRequired; }
    public boolean isAuthRequired() { return voiceAuthRequired || (coreSnapshot != null && coreSnapshot.authRequired); }
    public boolean isHttpManageOnline() { return coreSnapshot != null && coreSnapshot.reachable && !coreSnapshot.authRequired; }
    public boolean isAnyOnline() { return connected || isHttpManageOnline(); }
    public List<CoreDiscovery.DiscoveredCore> getLastDiscoveredCores() { return lastDiscoveredCores; }
    public String getConnectionStatus() { return connectionStatus; }
    public String getAssistantState() { return assistantState; }
    public double getLastRmsDbfs() { return lastRmsDbfs; }
    public double getLastNoiseFloorDbfs() { return lastNoiseFloorDbfs; }
    public double getLastSnrDb() { return lastSnrDb; }
    public long getBlocksSent() { return blocksSent; }
    public boolean isLocalVadSpeech() { return localVadSpeech; }
    public String getLastActionSubtitle() { return lastActionSubtitle; }
    public CoreApiClient.CoreSnapshot getCoreSnapshot() { return coreSnapshot; }
    public String getKwsEngineMode() { return kwsEngine != null ? kwsEngine.getActiveEngineMode() : LocalKwsEngine.MODE_ASR_HYBRID; }
    public long getKwsDetectionCount() { return kwsEngine != null ? kwsEngine.getDetectionCount() : 0L; }
    public boolean isHardwareAecActive() { return audioEngine != null && audioEngine.isHardwareAecEnabled(); }
    public boolean isSoftwareAecActive() { return audioEngine != null && audioEngine.isSoftwareAecEnabled(); }
    public double getSoftwareAecErleDb() { return audioEngine != null ? audioEngine.getSoftwareAecErleDb() : 0.0; }
    public boolean isHardwareNsActive() { return audioEngine != null && audioEngine.isHardwareNsEnabled(); }
    public boolean isLocalMediaPlaying() { return audioEngine != null && audioEngine.hasActiveMediaPlayback(); }
    public String getOutputRouteName() { return audioEngine != null ? audioEngine.getOutputRouteName() : "Speaker"; }
    public boolean isR1HardwareDetected() { return hardwareAdapter != null && hardwareAdapter.isSpecializedHardwareDetected(); }
    public String getHardwareAdapterName() { return hardwareAdapter != null ? hardwareAdapter.getAdapterName() : "Generic-Android"; }

    public boolean isMuted() {
        NorthboundSession s = activeSession;
        return s != null && s.isMuted();
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
        CoreApiClient.CoreSnapshot snap = coreSnapshot;
        if (!connected && snap != null && snap.devices != null && !snap.devices.isEmpty()) {
            for (CoreApiClient.DeviceInfo d : snap.devices) {
                if (d.connected && d.id != null && !d.id.isEmpty()) {
                    return d.id;
                }
            }
            String first = snap.devices.get(0).id;
            if (first != null && !first.isEmpty()) {
                return first;
            }
        }
        return config.getDeviceId();
    }

    public void reloadConfigAndReconnect() {
        fromBootRestricted = false;
        startInForeground();
        audioEngine.configure(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(), config.getUplinkGain());
        if (kwsEngine != null) {
            kwsEngine.setSensitivity(config.getKwsThreshold(), config.getKwsScore());
        }
        if (hasRecordAudioPermission()) {
            audioEngine.startCapture();
        }
        // Reset stale snapshot & assistant state immediately so failover/Manage-Only checks never read previous Core state
        coreSnapshot = CoreApiClient.CoreSnapshot.offline("switching");
        voiceAuthRequired = false;
        assistantState = "idle";
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
        notifyUi();
    }

    public void refreshCoreSnapshotNow() {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                CoreApiClient.CoreSnapshot snap = CoreApiClient.fetchSnapshot(
                        config.getCoreHost(), config.getWebPort(), config.getDeviceId(),
                        config.getBearerToken(), true);
                if (snap != null) {
                    coreSnapshot = snap;
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
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String targetDev = resolveEffectiveDeviceId();
                boolean ok = CoreApiClient.updateDeviceMode(
                        config.getCoreHost(), config.getWebPort(), targetDev,
                        duplex, bargeIn, wakeAck, wakeArbitration, config.getBearerToken());
                if (ok) {
                    coreSnapshot = CoreApiClient.fetchSnapshot(
                            config.getCoreHost(), config.getWebPort(), targetDev,
                            config.getBearerToken(), true);
                    notifyUi();
                }
                if (callback != null) {
                    callback.onModeApplied(ok);
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

    private void sendUtteranceInternal(final String text, final String source, final boolean prependWake) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final String clean = text.trim();
        lastActionSubtitle = clean;
        if (prependWake) {
            assistantState = "thinking";
        }
        syncHardwareAndMedia();
        notifyUi();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                NorthboundSession s = activeSession;
                if (s != null && connected) {
                    try {
                        s.sendUtterance(clean, source, prependWake);
                        return;
                    } catch (IOException e) {
                        appendLog("ERR", "sendUtterance failed: " + e.getMessage());
                    }
                }
                CoreApiClient.sendChatText(
                        config.getCoreHost(), config.getWebPort(), resolveEffectiveDeviceId(),
                        config.getBearerToken(), clean);
                // In Manage-Only mode (:8932 absent), no assistant_state frame will arrive; reset cleanly & refresh snapshot
                if (!connected && "thinking".equalsIgnoreCase(assistantState)) {
                    assistantState = "idle";
                    syncHardwareAndMedia();
                    notifyUi();
                }
                refreshCoreSnapshotNow();
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
                    List<CoreDiscovery.DiscoveredCore> found = CoreDiscovery.scanLan(
                            config.getCoreHost(), collectSavedCandidateHosts(),
                            config.getWebPort(), config.getCorePort(), 240);
                    lastDiscoveredCores = found;
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
                List<CoreDiscovery.DiscoveredCore> found = CoreDiscovery.scanLan(
                        config.getCoreHost(), collectSavedCandidateHosts(),
                        config.getWebPort(), config.getCorePort(), 240);
                lastDiscoveredCores = found;
                int added = config.mergeDiscoveredCores(found);
                if (!found.isEmpty()) {
                    CoreDiscovery.DiscoveredCore best = found.get(0);
                    appendLog("DISC", "Scan found " + found.size() + " Core(s) (+" + added + "), best=" + best.toString());
                    if (autoBindFirst || (!hadCoreBefore && config.hasConfiguredCore())) {
                        config.upsertProfileAndSelect(
                                best.displayLabel(), best.host, best.gatewayPort, best.webPort,
                                null, best.coreId, best.alternateHosts);
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
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, targets.size())));
                List<java.util.concurrent.Future<CoreApiClient.RoomNodeSummary>> futures =
                        new java.util.ArrayList<java.util.concurrent.Future<CoreApiClient.RoomNodeSummary>>();
                for (final GatewayConfig.ServerProfile p : targets.values()) {
                    futures.add(pool.submit(new java.util.concurrent.Callable<CoreApiClient.RoomNodeSummary>() {
                        @Override
                        public CoreApiClient.RoomNodeSummary call() {
                            String tok = !p.bearerToken.isEmpty() ? p.bearerToken : config.getBearerTokenForHost(p.host);
                            CoreApiClient.RoomNodeSummary primary = CoreApiClient.fetchRoomSummary(
                                    p.host, p.webPort, p.gatewayPort, p.name, tok);
                            if (primary.online || p.alternateHosts.isEmpty()) {
                                return primary;
                            }
                            // Try fallback routes (e.g. Tailscale) for the same logical Core
                            for (String alt : p.alternateHosts) {
                                CoreApiClient.RoomNodeSummary fallback = CoreApiClient.fetchRoomSummary(
                                        alt, p.webPort, p.gatewayPort, p.name, tok);
                                if (fallback.online) {
                                    return fallback;
                                }
                            }
                            return primary;
                        }
                    }));
                }
                List<CoreApiClient.RoomNodeSummary> results = new java.util.ArrayList<CoreApiClient.RoomNodeSummary>();
                for (java.util.concurrent.Future<CoreApiClient.RoomNodeSummary> f : futures) {
                    try {
                        CoreApiClient.RoomNodeSummary s = f.get(3000L, TimeUnit.MILLISECONDS);
                        if (s != null) {
                            results.add(s);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                pool.shutdownNow();
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
                boolean ok = CoreApiClient.sendChatText(
                        host, webPort, config.getDeviceId(), config.getBearerTokenForHost(host), text);
                if (callback != null) callback.onActionDone(ok);
            }
        });
    }

    public void stopRemoteRoomAsync(
            final String host, final int webPort, final String deviceId, final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String tok = config.getBearerTokenForHost(host);
                boolean ok = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.stopRemoteDevice(host, webPort, deviceId, tok)
                        : CoreApiClient.sendChatText(host, webPort, config.getDeviceId(), tok, "停止播放");
                if (callback != null) callback.onActionDone(ok);
            }
        });
    }

    public void pauseRemoteRoomAsync(
            final String host, final int webPort, final String deviceId, final ActionResultCallback callback) {
        sendAsync(new Runnable() {
            @Override
            public void run() {
                String tok = config.getBearerTokenForHost(host);
                boolean ok = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.pauseRemoteDevice(host, webPort, deviceId, tok)
                        : CoreApiClient.sendChatText(host, webPort, config.getDeviceId(), tok, "暂停播放");
                if (callback != null) callback.onActionDone(ok);
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
                boolean ok = (deviceId != null && !deviceId.isEmpty())
                        ? CoreApiClient.setRemoteDeviceVolume(host, webPort, deviceId, linearVol, tok)
                        : false;
                if (callback != null) callback.onActionDone(ok);
            }
        });
    }

    public void triggerManualWake(final String keyword) {
        final long now = System.currentTimeMillis();
        lastActionSubtitle = keyword;
        assistantState = "listening";
        syncHardwareAndMedia();
        notifyUi();
        sendAsync(new Runnable() {
            @Override
            public void run() {
                NorthboundSession s = activeSession;
                if (s != null && connected) {
                    try {
                        s.notifyWakeForUplinkAndFlushPreroll(now);
                        s.sendWake(keyword, 3.20, "touch");
                    } catch (IOException e) {
                        appendLog("ERR", "sendWake failed: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void triggerManualRetreat(final String keyword) {
        final long t0 = System.currentTimeMillis();
        final List<String> flushed = audioEngine.stopAndFlush();
        final long latencyMs = Math.max(1L, System.currentTimeMillis() - t0);
        acousticGuard.onPlaybackStopped();
        lastActionSubtitle = keyword;
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
        final NorthboundSession s = activeSession;
        if (s == null) {
            return;
        }
        final boolean nextMuted = !s.isMuted();
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
            boolean mediaPlaying = audioEngine.hasActiveMediaPlayback() || coreSnapshot.musicActive;
            String title = coreSnapshot.musicTitle != null && !coreSnapshot.musicTitle.isEmpty()
                    ? coreSnapshot.musicTitle
                    : config.getRoomName();
            String sub = coreSnapshot.assistantName != null && !coreSnapshot.assistantName.isEmpty()
                    ? coreSnapshot.assistantName
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
                    for (GatewayConfig.ServerProfile p : profiles) {
                        if (!p.matchesHost(activeHostNow)) {
                            continue;
                        }
                        for (String candidate : p.allCandidateHosts()) {
                            if (CoreDiscovery.isRfc1918PrivateIp(candidate)) {
                                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                                        candidate, p.webPort, p.gatewayPort, 900);
                                if (probed != null && probed.voiceGatewayOpen) {
                                    appendLog("NET", "Wi-Fi LAN route on active Core available; failing back to " + candidate);
                                    config.upsertProfileAndSelect(
                                            p.name, candidate, probed.gatewayPort, p.webPort,
                                            p.bearerToken, !probed.coreId.isEmpty() ? probed.coreId : p.coreId,
                                            p.allCandidateHosts());
                                    reloadConfigAndReconnect();
                                    return;
                                }
                            }
                        }
                    }
                    // 2. Fallback: scan other saved profiles' LAN routes
                    for (GatewayConfig.ServerProfile p : profiles) {
                        if (p.matchesHost(activeHostNow)) {
                            continue;
                        }
                        for (String candidate : p.allCandidateHosts()) {
                            if (CoreDiscovery.isRfc1918PrivateIp(candidate)) {
                                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(
                                        candidate, p.webPort, p.gatewayPort, 900);
                                if (probed != null && probed.voiceGatewayOpen) {
                                    appendLog("NET", "Wi-Fi LAN Core available; failing back to " + candidate);
                                    config.upsertProfileAndSelect(
                                            p.name, candidate, probed.gatewayPort, p.webPort,
                                            p.bearerToken, !probed.coreId.isEmpty() ? probed.coreId : p.coreId,
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
        if (hasRecordAudioPermission()) {
            audioEngine.startCapture();
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
                if (uiForeground || audioEngine.hasActiveMediaPlayback()) {
                    boolean fullFetch = (pollCount % 3 == 0);
                    pollCount++;
                    CoreApiClient.CoreSnapshot prev = coreSnapshot;
                    String pollDevId = resolveEffectiveDeviceId();
                    CoreApiClient.CoreSnapshot snap = CoreApiClient.fetchSnapshot(
                            config.getCoreHost(), config.getWebPort(), pollDevId,
                            config.getBearerToken(), fullFetch);
                    if (snap != null) {
                        if (!fullFetch && snap.reachable && !snap.authRequired && prev != null && prev.reachable) {
                            // Preserve cached devices/fleet/assistantName (and non-empty Manage-Only transcript) on lightweight polls
                            List<CoreApiClient.ChatEntry> txEntries = (snap.transcript != null && !snap.transcript.isEmpty())
                                    ? snap.transcript : prev.transcript;
                            snap = new CoreApiClient.CoreSnapshot(
                                    snap.reachable, snap.authRequired,
                                    prev.assistantName, prev.buildId,
                                    snap.duplexMode, snap.bargeIn, snap.wakeAckEnabled, snap.wakeArbitration,
                                    snap.musicActive, snap.musicTitle, snap.musicSource,
                                    prev.devices, prev.fleetNodes, txEntries, snap.error);
                        }
                        coreSnapshot = snap;
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
        for (GatewayConfig.ServerProfile p : profiles) {
            if (p.matchesHost(currentHost)) {
                for (String alt : p.allCandidateHosts()) {
                    if (alt.equalsIgnoreCase(currentHost)) {
                        continue;
                    }
                    CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(alt, p.webPort, p.gatewayPort, 1200);
                    if (probed != null) {
                        appendLog("NET", "Route failover on " + p.name + " -> " + alt);
                        config.upsertProfileAndSelect(
                                p.name, alt, probed.gatewayPort, p.webPort,
                                p.bearerToken, !probed.coreId.isEmpty() ? probed.coreId : p.coreId,
                                p.allCandidateHosts());
                        return;
                    }
                }
            }
        }
        // 2. Otherwise try other saved Core profiles via HTTP probe only (zero raw :8932 probing)
        for (GatewayConfig.ServerProfile p : profiles) {
            if (p.matchesHost(currentHost)) {
                continue;
            }
            for (String candidate : p.allCandidateHosts()) {
                CoreDiscovery.DiscoveredCore probed = CoreDiscovery.probeHost(candidate, p.webPort, p.gatewayPort, 1200);
                if (probed != null && probed.voiceGatewayOpen) {
                    appendLog("NET", "Auto-Failover switched to " + p.name + " (" + candidate + ")");
                    config.upsertProfileAndSelect(
                            p.name, candidate, probed.gatewayPort, p.webPort,
                            p.bearerToken, !probed.coreId.isEmpty() ? probed.coreId : p.coreId,
                            p.allCandidateHosts());
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

            connected = false;
            connectionStatus = host + ":" + port;
            notifyUi();

            boolean skipBackoff = false;
            boolean manageOnlyCore = false;
            final boolean[] rejectedUnauthorized = new boolean[]{false};
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                socket.connect(new InetSocketAddress(host, port), 3500);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(45_000);
                consecutiveFailures = 0;
                intentionalReconnect.set(false);

                final BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                final TwoTierOutboundTransport txTransport = new TwoTierOutboundTransport(socket, writer);
                activeTxTransport = txTransport;

                NorthboundSession session = new NorthboundSession(
                        deviceId,
                        model,
                        "2.3.0-android",
                        txTransport,
                        new NorthboundSession.BaseListener() {
                            @Override
                            public void onHelloAck(long generation, int negotiatedProtocol) {
                                connected = true;
                                voiceAuthRequired = false;
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
                                    rejectedUnauthorized[0] = true;
                                }
                                connectionStatus = "Rejected: " + reason;
                                appendLog("NET", "hello_reject: " + reason + " (core supports v" + minProtocol + "-v" + maxProtocol + ")");
                                notifyUi();
                                closeActiveSocket();
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
                                syncHardwareAndMedia();
                                notifyUi();
                            }

                            @Override
                            public void onPlayWav(String taskId, String channel, byte[] wavBytes) {
                                assistantState = "speaking";
                                syncHardwareAndMedia();
                                notifyUi();
                                audioEngine.playWav(taskId, channel, wavBytes);
                            }

                            @Override
                            public void onPlayStreamBegin(String taskId, String channel, int sampleRate, int channels, String format) {
                                audioEngine.beginStream(taskId, channel, sampleRate, channels);
                                if (!"media".equalsIgnoreCase(channel) && !"music".equalsIgnoreCase(channel)) {
                                    assistantState = "speaking";
                                }
                                syncHardwareAndMedia();
                                notifyUi();
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
                                config.saveAcoustics(
                                        config.isUseVoiceCommAec(),
                                        config.isSoftwareAecEnabled(),
                                        config.getUplinkGain(),
                                        (float) threshold,
                                        (float) score);
                                notifyUi();
                            }

                            @Override
                            public void onConfigBlockApplied(String block, Map<String, Object> values) {
                                if ("kws".equals(block)) {
                                    double thr = MiniJson.getDouble(values, "threshold", config.getKwsThreshold());
                                    double sc = MiniJson.getDouble(values, "score", config.getKwsScore());
                                    config.saveAcoustics(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(),
                                            config.getUplinkGain(), (float) thr, (float) sc);
                                    applyAcousticsOnly();
                                } else if ("acoustics".equals(block)) {
                                    double gain = MiniJson.getDouble(values, "uplink_gain", config.getUplinkGain());
                                    config.saveAcoustics(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(),
                                            (float) gain, config.getKwsThreshold(), config.getKwsScore());
                                    applyAcousticsOnly();
                                }
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

                session.setAecTailMs((audioEngine.isHardwareAecEnabled() || config.isSoftwareAecEnabled()) ? 160 : 0);
                session.setInitialVolumeLinear(audioEngine.getCurrentHardwareVolumeLinear());
                session.setKwsSensitivity(config.getKwsThreshold(), config.getKwsScore());
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
                        // Send lightweight keepalive probe to verify half-open socket without dropping healthy links
                        try {
                            session.sendEchoHealth("healthy", 0L);
                        } catch (IOException probeErr) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                boolean wasIntentional = intentionalReconnect.getAndSet(false);
                connected = false;
                if (!wasIntentional) {
                    boolean httpReachable = isHttpManageOnline()
                            || CoreApiClient.probeCoreHttp(host, config.getWebPort(), config.getBearerToken(), 650);
                    if (httpReachable) {
                        // Household speaker Core with HTTP :8930 open and :8932 loopback-only -> calm Manage-Only Mode!
                        manageOnlyCore = true;
                        consecutiveFailures = 0;
                        connectionStatus = "Manage · " + host;
                        updateNotification("Online (Manage) · " + host);
                        syncHardwareAndMedia();
                        notifyUi();
                    } else {
                        consecutiveFailures++;
                        connectionStatus = "Offline (" + host + ")";
                        appendLog("NET", "Disconnected [" + host + ":" + port + "]: " + e.getClass().getSimpleName());
                        updateNotification("Reconnecting...");
                        syncHardwareAndMedia();
                        notifyUi();

                        if (consecutiveFailures >= 1) {
                            tryProfileFailover();
                        }
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
                        : (manageOnlyCore
                        ? 30_000L
                        : Math.min(8000L, 1500L + (long) consecutiveFailures * 1000L));
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
                int fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                if (hasRecordAudioPermission() && !fromBootRestricted) {
                    fgsType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, fgsType);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException se) {
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
