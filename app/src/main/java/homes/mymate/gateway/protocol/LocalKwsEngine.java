package homes.mymate.gateway.protocol;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-Device Offline Sherpa-ONNX Keyword Spotter Engine ({@code LocalKwsEngine} v2.0).
 *
 * v2.0 hardening:
 * - Tagged PcmBlock(pcm, capturedWallMs, rmsDbfs) computes real queue+decode lagMs and stamps
 *   detection timestamps immediately at spotter match time.
 * - Runs getResult() + reset() INSIDE while(spotter.isReady(stream)) matching reference hm-kws driver.c.
 * - Enforces min_wake_ms >= 280ms and +80ms frame length in L2 acoustic verification.
 * - Periodically resets OnlineStream every 6 minutes during confirmed silence to prevent 24/7 native RSS drift.
 * - Atomic kw.txt write (kw.txt.tmp -> renameTo) + .bak rollback & backoff retry (never kills supervisor permanently).
 * - Always hashes actual on-disk kw.txt bytes for truthful loaded_hash telemetry.
 */
public final class LocalKwsEngine {

    public static final String MODE_JNI_OFFLINE = "JNI_OFFLINE";
    public static final String MODE_SIDECAR_OFFLINE = "SIDECAR_OFFLINE";
    public static final String MODE_ASR_HYBRID = "ASR_HYBRID";

    public static final List<String> DEFAULT_RETREAT_WORDS = Arrays.asList(
            "退下", "退下吧", "退一下吧", "停止播放", "滚一边儿去");

    public static final String DEFAULT_KEYWORDS_TXT =
            "n ǐ h ǎo x iǎo zh ì @你好小智\n"
            + "z àn t íng b ō f àng @暂停播放\n"
            + "j ì x ù b ō f àng @继续播放\n"
            + "x ià y ì sh ǒu @下一首\n"
            + "t íng zh ǐ b ō f àng @停止播放\n"
            + "g ǔn y ì b iān ér q ù @滚一边儿去\n"
            + "t uì x ià @退下\n"
            + "t uì x ià b a @退下吧\n"
            + "t uì y í x ià b a @退一下吧\n";

    private static final float KWS_AGC_TARGET_RMS = 0.10f; // ~ -20 dBFS optimal for Zipformer KWS
    private static final float KWS_AGC_MIN_SPEECH_RMS = 0.002f; // ~ -54 dBFS
    private static final float KWS_AGC_IDLE_FLOOR_GAIN = 1.5f;
    private static final float KWS_AGC_MAX_GAIN = 3.5f;
    private static final long MIN_WAKE_DURATION_MS = 280L;

    private static final long IDLE_STREAM_RESET_INTERVAL_MS = 6L * 60L * 1000L; // 6 minutes
    private static final int SILENCE_BLOCKS_FOR_RESET = 25; // 2.0s of quiet @ 80ms/block

    private static final class PcmBlock {
        final byte[] pcm;
        final long capturedWallMs;
        final boolean quiet;

        PcmBlock(byte[] pcm, long capturedWallMs, boolean quiet) {
            this.pcm = pcm;
            this.capturedWallMs = capturedWallMs;
            this.quiet = quiet;
        }
    }

    public static final class KwsEvent {
        public final String eventType;
        public final String keyword;
        public final long fedMs;
        public final long lagMs;
        public final long wakeDurMs;
        public final long maxGapMs;
        public final String tier;
        public final Boolean l2Passed;
        public final boolean vadSpeech;
        public final double threshold;
        public final double score;
        public final String errorStage;
        public final long detectedWallMs;

        public KwsEvent(
                String eventType,
                String keyword,
                long fedMs,
                long lagMs,
                long wakeDurMs,
                long maxGapMs,
                String tier,
                Boolean l2Passed,
                boolean vadSpeech,
                double threshold,
                double score,
                String errorStage,
                long detectedWallMs) {
            this.eventType = eventType;
            this.keyword = keyword;
            this.fedMs = fedMs;
            this.lagMs = lagMs;
            this.wakeDurMs = wakeDurMs;
            this.maxGapMs = maxGapMs;
            this.tier = tier;
            this.l2Passed = l2Passed;
            this.vadSpeech = vadSpeech;
            this.threshold = threshold;
            this.score = score;
            this.errorStage = errorStage;
            this.detectedWallMs = detectedWallMs;
        }

        public KwsEvent(
                String eventType,
                String keyword,
                long fedMs,
                long lagMs,
                long wakeDurMs,
                long maxGapMs,
                String tier,
                Boolean l2Passed,
                boolean vadSpeech,
                double threshold,
                double score,
                String errorStage) {
            this(eventType, keyword, fedMs, lagMs, wakeDurMs, maxGapMs,
                    tier, l2Passed, vadSpeech, threshold, score, errorStage, System.currentTimeMillis());
        }
    }

    public static final class ValidationResult {
        public final boolean valid;
        public final int keywordCount;
        public final String detail;
        public final List<String> invalidTokens;

        public ValidationResult(boolean valid, int keywordCount, String detail, List<String> invalidTokens) {
            this.valid = valid;
            this.keywordCount = keywordCount;
            this.detail = detail;
            this.invalidTokens = invalidTokens;
        }
    }

    public interface EventListener {
        void onKwsReady(double threshold, double score, String loadedHash);
        void onKeywordDetected(KwsEvent hit, boolean isRetreatWord);
        void onVadSpeechChanged(boolean speech);
        void onKwsStateChanged(String state, String detail);
    }

    /** Optional hook invoked on the background KWS thread before loading models (keeps asset unpacking off Main UI thread). */
    public interface AssetPreparer {
        void prepareWorkspace();
    }

    private final File workDir;
    private final File nativeLibDir;
    private final String deviceId;
    private final EventListener listener;
    private volatile AssetPreparer assetPreparer;

    private final ArrayBlockingQueue<PcmBlock> pcmQueue = new ArrayBlockingQueue<PcmBlock>(64);
    private final Set<String> validTokenSet = new HashSet<String>();
    private final Set<String> retreatWordSet = new HashSet<String>(DEFAULT_RETREAT_WORDS);

    private volatile boolean running = false;
    private volatile boolean processReady = false;
    private volatile String activeEngineMode = MODE_ASR_HYBRID;
    private double threshold = 0.22;
    private double score = 3.00;
    private String currentKeywordsHash = "";
    private volatile Process activeProcess;
    private final AtomicBoolean reloadRequested = new AtomicBoolean(false);
    private final Object deployLock = new Object();
    private final Object reloadBarrierLock = new Object();
    private volatile CountDownLatch workspaceReadyLatch = null;
    private volatile long workspaceWaitTimeoutMs = 15_000L;
    private volatile boolean customKeywordsDeployed = false;
    private long requestedReloadSeq = 0L;
    private long completedReloadSeq = 0L;
    private boolean lastReloadSucceeded = true;
    private String lastReloadDetail = "applied";
    private volatile boolean forceAwaitReloadForTest = false;
    private volatile long reloadWaitTimeoutMs = 3500L;
    private final AtomicLong detectionCount = new AtomicLong(0);
    private final AtomicLong restartCount = new AtomicLong(0);
    private Thread supervisorThread;

    public LocalKwsEngine(File workDir, File nativeLibDir, String deviceId, EventListener listener) {
        this.workDir = workDir;
        this.nativeLibDir = nativeLibDir;
        this.deviceId = deviceId != null ? deviceId : "android-panel-1";
        this.listener = listener;
        if (this.workDir != null && !this.workDir.exists()) {
            this.workDir.mkdirs();
        }
        reloadTokensSetIfPresent();
    }

    public void setAssetPreparer(AssetPreparer preparer) {
        this.assetPreparer = preparer;
        this.workspaceReadyLatch = (preparer != null) ? new CountDownLatch(1) : null;
    }

    public void setWorkspaceWaitTimeoutForTest(long timeoutMs) {
        this.workspaceWaitTimeoutMs = Math.max(50L, timeoutMs);
    }

    private void awaitWorkspacePreparedBounded() {
        CountDownLatch latch = this.workspaceReadyLatch;
        if (latch == null || !running) {
            return;
        }
        try {
            latch.await(workspaceWaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Installs or upgrades bundled default {@code kw.txt} under {@code deployLock} using an
     * isolated temp file ({@code kw.default.tmp}), guaranteeing cold-boot asset preparation NEVER
     * overwrites or races custom keywords already deployed by Core.
     *
     * @param prevDefaultHash previously recorded bundled-default FNV hash (empty on fresh install)
     * @return the newly installed bundled-default hash if written, or {@code null} if skipped
     */
    public String syncDefaultKeywordsIfNotCustom(String prevDefaultHash) {
        if (workDir == null) {
            return null;
        }
        synchronized (deployLock) {
            File kwFile = new File(workDir, "kw.txt");
            File customMarker = new File(workDir, ".kw.custom");
            String newDefaultHash = NorthboundSession.fnv1a64Hex(DEFAULT_KEYWORDS_TXT);

            if (customKeywordsDeployed || customMarker.exists()) {
                return null;
            }

            boolean shouldWrite = false;
            if (!kwFile.exists() || kwFile.length() == 0) {
                shouldWrite = true;
            } else if (prevDefaultHash != null && !prevDefaultHash.isEmpty()
                    && !newDefaultHash.equals(prevDefaultHash)) {
                String currentDiskHash = readAndHashKeywordsFile(kwFile);
                if (prevDefaultHash.equals(currentDiskHash)) {
                    shouldWrite = true;
                }
            }

            if (!shouldWrite) {
                return null;
            }

            File tmpFile = new File(workDir, "kw.default.tmp");
            try {
                writeUtf8Atomically(tmpFile, kwFile, DEFAULT_KEYWORDS_TXT);
                synchronized (this) {
                    this.currentKeywordsHash = newDefaultHash;
                }
                return newDefaultHash;
            } catch (IOException e) {
                tmpFile.delete();
                return null;
            }
        }
    }

    public boolean isProcessReady() {
        return processReady;
    }

    public String getActiveEngineMode() {
        return activeEngineMode;
    }

    public long getDetectionCount() {
        return detectionCount.get();
    }

    public long getRestartCount() {
        return restartCount.get();
    }

    public synchronized String getCurrentKeywordsHash() {
        return currentKeywordsHash;
    }

    public synchronized void setSensitivity(double nextThreshold, double nextScore) {
        double clampedThr = Math.max(0.10, Math.min(0.80, nextThreshold));
        double clampedScore = Math.max(0.50, Math.min(5.00, nextScore));
        if (Math.abs(clampedThr - this.threshold) > 0.005 || Math.abs(clampedScore - this.score) > 0.02) {
            this.threshold = clampedThr;
            this.score = clampedScore;
            requestHotReload();
        }
    }

    public synchronized void setRetreatWords(List<String> words) {
        retreatWordSet.clear();
        if (words != null) {
            for (String w : words) {
                if (w != null && !w.trim().isEmpty()) {
                    retreatWordSet.add(w.trim());
                }
            }
        } else {
            retreatWordSet.addAll(DEFAULT_RETREAT_WORDS);
        }
    }

    public synchronized boolean isRetreatWord(String keyword) {
        if (keyword == null) {
            return false;
        }
        String k = keyword.trim();
        if (retreatWordSet.contains(k)) {
            return true;
        }
        // Only match explicit "吧" suffix variants (e.g. "退下吧" when "退下" is configured)
        if (k.endsWith("吧") && k.length() > 1 && retreatWordSet.contains(k.substring(0, k.length() - 1))) {
            return true;
        }
        return false;
    }

    public static KwsEvent parseDriverStdoutLine(String jsonLine) {
        if (jsonLine == null || jsonLine.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> obj = MiniJson.parseObject(jsonLine.trim());
        String ev = MiniJson.getString(obj, "event", "");
        if (ev.isEmpty()) {
            return null;
        }
        String keyword = MiniJson.getString(obj, "keyword", null);
        long fedMs = MiniJson.getLong(obj, "fed_ms", 0L);
        long lagMs = MiniJson.getLong(obj, "lag_ms", 0L);
        long wakeDurMs = MiniJson.getLong(obj, "wake_dur_ms", 0L);
        long maxGapMs = MiniJson.getLong(obj, "max_gap_ms", 0L);
        String tier = MiniJson.getString(obj, "tier", null);
        Boolean l2Passed = obj.containsKey("l2_passed")
                ? Boolean.valueOf(MiniJson.getBoolean(obj, "l2_passed", false))
                : null;
        boolean vadSpeech = MiniJson.getBoolean(obj, "speech", false);
        double thr = MiniJson.getDouble(obj, "threshold", 0.22);
        double sc = MiniJson.getDouble(obj, "score", 3.00);
        String errorStage = MiniJson.getString(obj, "stage", null);

        return new KwsEvent(
                ev, keyword, fedMs, lagMs, wakeDurMs, maxGapMs,
                tier, l2Passed, vadSpeech, thr, sc, errorStage, System.currentTimeMillis());
    }

    public static ValidationResult validateKeywordsTable(String keywordsTxt, Set<String> knownTokens) {
        if (keywordsTxt == null || keywordsTxt.trim().isEmpty()) {
            return new ValidationResult(false, 0, "empty_keywords_table", new ArrayList<String>());
        }
        String[] lines = keywordsTxt.split("\\r?\\n");
        int count = 0;
        List<String> badTokens = new ArrayList<String>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int atIdx = line.indexOf('@');
            if (atIdx <= 0 || atIdx == line.length() - 1) {
                return new ValidationResult(false, count, "missing_label_separator:@", badTokens);
            }
            String tokenPart = line.substring(0, atIdx).trim();
            String labelPart = line.substring(atIdx + 1).trim();
            if (tokenPart.isEmpty() || labelPart.isEmpty()) {
                return new ValidationResult(false, count, "empty_token_or_label", badTokens);
            }
            String[] toks = tokenPart.split("\\s+");
            int validInLine = 0;
            for (String tok : toks) {
                if (tok.startsWith(":") || tok.startsWith("#")) {
                    continue;
                }
                validInLine++;
                if (knownTokens != null && !knownTokens.isEmpty() && !knownTokens.contains(tok)) {
                    badTokens.add(tok);
                }
            }
            if (validInLine == 0) {
                return new ValidationResult(false, count, "no_phoneme_tokens", badTokens);
            }
            count++;
        }

        if (!badTokens.isEmpty()) {
            return new ValidationResult(false, count, "unknown_tokens:" + badTokens.get(0), badTokens);
        }
        if (count == 0) {
            return new ValidationResult(false, 0, "zero_valid_lines", badTokens);
        }
        return new ValidationResult(true, count, "applied", badTokens);
    }

    public synchronized void reloadTokensSetIfPresent() {
        if (workDir == null) {
            return;
        }
        File tokensFile = new File(new File(workDir, "model"), "tokens.txt");
        if (!tokensFile.exists()) {
            return;
        }
        validTokenSet.clear();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(tokensFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 1) {
                        validTokenSet.add(parts[0]);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Validates, atomically writes {@code kw.txt} (backing up {@code kw.txt.bak}), and waits for
     * the JNI worker thread to finish reloading before returning.
     *
     * <p>Lock discipline: {@code deployLock} serializes concurrent deployments, while {@code this}
     * is held ONLY for quick in-memory/file state transitions and released BEFORE waiting on
     * {@code reloadBarrierLock} so {@code hm-kws-engine} can safely acquire {@code synchronized (this)}
     * without deadlocking.
     */
    public ValidationResult deployKeywords(
            String keywordsTxt,
            List<String> retreatWords,
            double nextThreshold,
            double nextScore) {
        // Wait for cold-boot workspace asset prep BEFORE taking deployLock so KwsAssetInstaller
        // finishes unpacking tokens.txt and syncing defaults without deadlocking or racing kw.txt.
        awaitWorkspacePreparedBounded();
        synchronized (deployLock) {
            ValidationResult vr;
            double prevThreshold;
            double prevScore;
            List<String> prevRetreats;
            File kwFile = workDir != null ? new File(workDir, "kw.txt") : null;
            File bakFile = workDir != null ? new File(workDir, "kw.txt.bak") : null;
            boolean activeNativeRuntime;

            synchronized (this) {
                reloadTokensSetIfPresent();
                vr = validateKeywordsTable(keywordsTxt, validTokenSet);
                if (!vr.valid) {
                    return vr;
                }
                prevThreshold = this.threshold;
                prevScore = this.score;
                prevRetreats = new ArrayList<String>(this.retreatWordSet);

                this.threshold = Math.max(0.10, Math.min(0.80, nextThreshold));
                this.score = Math.max(0.50, Math.min(5.00, nextScore));
                setRetreatWords(retreatWords);

                if (workDir != null && kwFile != null && bakFile != null) {
                    File tmpFile = new File(workDir, "kw.deploy.tmp");
                    try {
                        if (kwFile.exists() && kwFile.length() > 0) {
                            copyFile(kwFile, bakFile);
                        }
                        writeUtf8Atomically(tmpFile, kwFile, keywordsTxt);
                        this.customKeywordsDeployed = true;
                        File marker = new File(workDir, ".kw.custom");
                        if (!marker.exists()) {
                            try {
                                marker.createNewFile();
                            } catch (IOException ignored) {
                            }
                        }
                    } catch (IOException e) {
                        this.threshold = prevThreshold;
                        this.score = prevScore;
                        setRetreatWords(prevRetreats);
                        return new ValidationResult(false, vr.keywordCount, "write_failed:" + e.getMessage(), vr.invalidTokens);
                    }
                } else {
                    this.customKeywordsDeployed = true;
                }
                this.currentKeywordsHash = NorthboundSession.fnv1a64Hex(keywordsTxt);
                activeNativeRuntime = forceAwaitReloadForTest
                        || (running && MODE_JNI_OFFLINE.equals(activeEngineMode));
            }

            long targetSeq;
            synchronized (reloadBarrierLock) {
                requestedReloadSeq++;
                targetSeq = requestedReloadSeq;
            }
            requestHotReload();
            if (!activeNativeRuntime) {
                return vr;
            }

            // Wait OUTSIDE `synchronized (this)` so the JNI thread can acquire `synchronized (this)`
            // to read the new thresholds/keywords and signal completion without deadlocking.
            long deadline = System.currentTimeMillis() + reloadWaitTimeoutMs;
            boolean completed = false;
            boolean ok = false;
            String detail = "reload_timeout";
            synchronized (reloadBarrierLock) {
                while (completedReloadSeq < targetSeq) {
                    long rem = deadline - System.currentTimeMillis();
                    if (rem <= 0L) {
                        break;
                    }
                    try {
                        reloadBarrierLock.wait(rem);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (completedReloadSeq >= targetSeq) {
                    completed = true;
                    ok = lastReloadSucceeded;
                    detail = lastReloadDetail;
                }
            }
            if (!completed || !ok) {
                synchronized (this) {
                    this.threshold = prevThreshold;
                    this.score = prevScore;
                    setRetreatWords(prevRetreats);
                    if (kwFile != null && bakFile != null && bakFile.exists() && bakFile.length() > 0) {
                        try {
                            copyFile(bakFile, kwFile);
                        } catch (IOException ignored) {
                        }
                    }
                    if (kwFile != null) {
                        this.currentKeywordsHash = readAndHashKeywordsFile(kwFile);
                    }
                }
                if (!completed) {
                    requestHotReload();
                }
                return new ValidationResult(false, vr.keywordCount, completed ? detail : "reload_timeout", vr.invalidTokens);
            }
            return vr;
        }
    }

    public synchronized double getThresholdForTest() {
        return threshold;
    }

    public synchronized double getScoreForTest() {
        return score;
    }

    public void configureReloadBarrierForTest(boolean forceAwait, long timeoutMs) {
        this.forceAwaitReloadForTest = forceAwait;
        this.reloadWaitTimeoutMs = Math.max(50L, timeoutMs);
    }

    public long getRequestedReloadSeqForTest() {
        synchronized (reloadBarrierLock) {
            return requestedReloadSeq;
        }
    }

    public void notifyReloadOutcomeForTest(long seq, boolean succeeded, String detail) {
        notifyReloadOutcome(seq, succeeded, detail);
    }

    private void notifyReloadOutcome(long seq, boolean succeeded, String detail) {
        synchronized (reloadBarrierLock) {
            if (seq > completedReloadSeq) {
                completedReloadSeq = seq;
                lastReloadSucceeded = succeeded;
                lastReloadDetail = detail != null ? detail : (succeeded ? "applied" : "reload_failed");
                reloadBarrierLock.notifyAll();
            }
        }
    }

    private void requestHotReload() {
        reloadRequested.set(true);
        Process proc = activeProcess;
        if (proc != null) {
            proc.destroy();
        }
    }

    public void feedPcmBlock(byte[] pcm16leBytes) {
        feedPcmBlock(pcm16leBytes, System.currentTimeMillis(), false);
    }

    public void feedPcmBlock(byte[] pcm16leBytes, long capturedWallMs, boolean quiet) {
        if (!running || !processReady || pcm16leBytes == null || pcm16leBytes.length == 0) {
            return;
        }
        PcmBlock block = new PcmBlock(pcm16leBytes, capturedWallMs, quiet);
        if (!pcmQueue.offer(block)) {
            pcmQueue.poll();
            pcmQueue.offer(block);
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        supervisorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runSupervisorLoop();
            }
        }, "hm-kws-engine");
        supervisorThread.setPriority(Thread.NORM_PRIORITY + 2);
        supervisorThread.setDaemon(true);
        supervisorThread.start();
    }

    public synchronized void stop() {
        running = false;
        processReady = false;
        CountDownLatch latch = this.workspaceReadyLatch;
        if (latch != null) {
            latch.countDown();
        }
        Process proc = activeProcess;
        if (proc != null) {
            proc.destroy();
            activeProcess = null;
        }
        if (supervisorThread != null) {
            supervisorThread.interrupt();
            supervisorThread = null;
        }
    }

    private void runSupervisorLoop() {
        AssetPreparer preparer = this.assetPreparer;
        CountDownLatch latch = this.workspaceReadyLatch;
        if (preparer != null) {
            try {
                preparer.prepareWorkspace();
            } catch (Throwable ignored) {
            } finally {
                if (latch != null) {
                    latch.countDown();
                }
            }
        } else if (latch != null) {
            latch.countDown();
        }
        reloadTokensSetIfPresent();

        if (workDir == null) {
            enterHybridMode("no_workdir");
            return;
        }
        File modelDir = new File(workDir, "model");
        File tokens = new File(modelDir, "tokens.txt");
        File encoder = new File(modelDir, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx");
        File decoder = new File(modelDir, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx");
        File joiner = new File(modelDir, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx");
        File kwFile = ensureKeywordsFileAndHash();

        boolean modelsPresent = tokens.exists() && encoder.exists() && decoder.exists() && joiner.exists();
        if (!modelsPresent) {
            enterHybridMode("slim_apk_no_model");
            return;
        }

        if (KeywordSpotter.isNativeAvailable()) {
            runInProcessJniLoop(tokens, encoder, decoder, joiner, kwFile);
            if (!running) {
                return;
            }
        }

        runSidecarFallbackLoop(tokens, encoder, decoder, joiner, kwFile);
    }

    private void runInProcessJniLoop(File tokens, File encoder, File decoder, File joiner, File kwFile) {
        long[] backoffMs = new long[]{1000L, 2000L, 5000L, 10000L};
        int failureStreak = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            reloadRequested.set(false);
            long loadingReloadSeq;
            synchronized (reloadBarrierLock) {
                loadingReloadSeq = requestedReloadSeq;
            }
            double snapThr;
            double snapScore;
            String snapHash;
            synchronized (this) {
                snapThr = this.threshold;
                snapScore = this.score;
                snapHash = readAndHashKeywordsFile(kwFile);
                this.currentKeywordsHash = snapHash;
            }

            KeywordSpotter spotter = null;
            OnlineStream stream = null;
            try {
                OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
                        encoder.getAbsolutePath(),
                        decoder.getAbsolutePath(),
                        joiner.getAbsolutePath());
                int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
                OnlineModelConfig modelConfig = new OnlineModelConfig(
                        transducer,
                        tokens.getAbsolutePath(),
                        threads,
                        false,
                        "cpu");
                modelConfig.setModelType("zipformer2");
                KeywordSpotterConfig config = new KeywordSpotterConfig(
                        new FeatureConfig(16000, 80, 0.0f),
                        modelConfig,
                        4,
                        kwFile.getAbsolutePath(),
                        (float) snapScore,
                        (float) snapThr,
                        0);

                spotter = new KeywordSpotter(config);
                stream = spotter.createStream("");
                failureStreak = 0;
                activeEngineMode = MODE_JNI_OFFLINE;
                processReady = true;
                notifyReloadOutcome(loadingReloadSeq, true, "applied");

                if (listener != null) {
                    listener.onKwsReady(snapThr, snapScore, snapHash);
                    listener.onKwsStateChanged("running",
                            String.format(Locale.US, "ready (thr=%.2f score=%.2f · JNI native offline)", snapThr, snapScore));
                }

                long fedSamples = 0L;
                long lastHitWallMs = 0L;
                String lastHitKeyword = "";
                long streamCreatedMs = System.currentTimeMillis();
                int consecutiveQuietBlocks = 0;
                float[] kwsAgcGain = new float[]{KWS_AGC_IDLE_FLOOR_GAIN};

                while (running && !reloadRequested.get() && !Thread.currentThread().isInterrupted()) {
                    PcmBlock block = pcmQueue.poll(120, TimeUnit.MILLISECONDS);
                    if (block == null || block.pcm == null || block.pcm.length < 2) {
                        continue;
                    }
                    if (block.quiet) {
                        consecutiveQuietBlocks++;
                        if (consecutiveQuietBlocks >= SILENCE_BLOCKS_FOR_RESET
                                && (System.currentTimeMillis() - streamCreatedMs) >= IDLE_STREAM_RESET_INTERVAL_MS) {
                            // Prevent 24/7 native OnlineStream RSS growth & float32 timestamp drift
                            stream.release();
                            stream = spotter.createStream("");
                            streamCreatedMs = System.currentTimeMillis();
                            consecutiveQuietBlocks = 0;
                        }
                    } else {
                        consecutiveQuietBlocks = 0;
                    }

                    byte[] pcm = block.pcm;
                    float[] samples = convertAndNormalizePcmForKws(pcm, kwsAgcGain);
                    int sampleCount = samples.length;
                    stream.acceptWaveform(samples, 16000);
                    fedSamples += sampleCount;

                    while (spotter.isReady(stream)) {
                        spotter.decode(stream);
                        KeywordSpotterResult res = spotter.getResult(stream);
                        if (res != null && res.getKeyword() != null && !res.getKeyword().trim().isEmpty()) {
                            String kw = res.getKeyword().trim();
                            long detectedMs = System.currentTimeMillis();
                            spotter.reset(stream);

                            if (kw.equals(lastHitKeyword) && (detectedMs - lastHitWallMs) < 750L) {
                                continue;
                            }
                            lastHitKeyword = kw;
                            lastHitWallMs = detectedMs;

                            long fedMs = (fedSamples * 1000L) / 16000L;
                            long realLagMs = Math.max(0L, Math.min(2000L, detectedMs - block.capturedWallMs));
                            float[] ts = res.getTimestamps();
                            long wakeDurMs = computeWakeDurationMs(ts);
                            long maxGapMs = computeMaxGapMs(ts);
                            int tokenCount = res.getTokens() != null ? res.getTokens().length : 0;
                            boolean isRetreat = isRetreatWord(kw);
                            boolean l2Passed = isRetreat
                                    || (tokenCount >= 4 && wakeDurMs >= MIN_WAKE_DURATION_MS && wakeDurMs <= 1800L && maxGapMs <= 500L);
                            String tier = l2Passed ? "real" : "suspect";

                            detectionCount.incrementAndGet();
                            KwsEvent hit = new KwsEvent(
                                    "keyword", kw, fedMs, realLagMs, wakeDurMs, maxGapMs,
                                    tier, Boolean.valueOf(l2Passed), true, snapThr, snapScore, null, detectedMs);
                            if (listener != null) {
                                listener.onKeywordDetected(hit, isRetreat);
                            }
                        }
                    }
                }
                if (reloadRequested.get()) {
                    restartCount.incrementAndGet();
                }
            } catch (Throwable t) {
                processReady = false;
                restartCount.incrementAndGet();
                // Roll back kw.txt from .bak if a bad kw.txt caused JNI init failure
                File bakFile = new File(workDir, "kw.txt.bak");
                if (bakFile.exists() && bakFile.length() > 0) {
                    try {
                        copyFile(bakFile, kwFile);
                    } catch (IOException ignored) {
                    }
                }
                notifyReloadOutcome(loadingReloadSeq, false, "jni_reload_failed:" + t.getMessage());
                if (listener != null) {
                    listener.onKwsStateChanged("down", "JNI KWS reload error (retrying): " + t.getMessage());
                }
                long waitMs = backoffMs[Math.min(failureStreak, backoffMs.length - 1)];
                failureStreak++;
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ignored) {
                    break;
                }
            } finally {
                processReady = false;
                if (stream != null) {
                    try {
                        stream.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (spotter != null) {
                    try {
                        spotter.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void runSidecarFallbackLoop(File tokens, File encoder, File decoder, File joiner, File kwFile) {
        List<String> cmd = resolveSidecarCommand(tokens, encoder, decoder, joiner, kwFile);
        if (cmd == null) {
            enterHybridMode("no_native_runtime");
            return;
        }
        long[] backoffMs = new long[]{1000L, 2000L, 5000L, 10000L, 30000L};
        int attempt = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            long startedMs = System.currentTimeMillis();
            try {
                if (listener != null) {
                    listener.onKwsStateChanged("starting", "spawning hm-kws sidecar...");
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(workDir);
                final Process proc = pb.start();
                activeProcess = proc;

                Thread writerThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            OutputStream os = proc.getOutputStream();
                            while (running && proc.isAlive()) {
                                PcmBlock block = pcmQueue.poll(200, TimeUnit.MILLISECONDS);
                                if (block != null && block.pcm != null) {
                                    os.write(block.pcm);
                                    os.flush();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }, "hm-kws-stdin");
                writerThread.setDaemon(true);
                writerThread.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        handleSidecarLine(line);
                    }
                }
                proc.waitFor();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("Permission denied") || msg.contains("error=13")) {
                    enterHybridMode("selinux_exec_blocked");
                    return;
                }
                if (listener != null) {
                    listener.onKwsStateChanged("down", "hm-kws exited: " + msg);
                }
            } finally {
                processReady = false;
                activeProcess = null;
            }

            if (!running) {
                break;
            }
            if (System.currentTimeMillis() - startedMs > 60000L) {
                attempt = 0;
            }
            long sleepMs = backoffMs[Math.min(attempt, backoffMs.length - 1)];
            attempt++;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    private void enterHybridMode(String reason) {
        activeEngineMode = MODE_ASR_HYBRID;
        processReady = false;
        synchronized (this) {
            currentKeywordsHash = "";
        }
        if (listener != null) {
            listener.onKwsStateChanged("down",
                    String.format(Locale.US, "ASR+Touch hybrid fallback (%s)", reason));
        }
    }

    private void handleSidecarLine(String line) {
        KwsEvent ev = parseDriverStdoutLine(line);
        if (ev == null || listener == null) {
            return;
        }
        switch (ev.eventType) {
            case "ready":
                activeEngineMode = MODE_SIDECAR_OFFLINE;
                processReady = true;
                listener.onKwsReady(ev.threshold, ev.score, getCurrentKeywordsHash());
                listener.onKwsStateChanged("running",
                        String.format(Locale.US, "ready (thr=%.2f score=%.2f · hm-kws sidecar)", ev.threshold, ev.score));
                break;
            case "keyword":
                if (ev.keyword != null && !ev.keyword.isEmpty()) {
                    detectionCount.incrementAndGet();
                    listener.onKeywordDetected(ev, isRetreatWord(ev.keyword));
                }
                break;
            case "vad":
                listener.onVadSpeechChanged(ev.vadSpeech);
                break;
            case "error":
                processReady = false;
                listener.onKwsStateChanged("down", "hm-kws error at stage: " + ev.errorStage);
                break;
            default:
                break;
        }
    }

    private File ensureKeywordsFileAndHash() {
        synchronized (deployLock) {
            File kwFile = new File(workDir, "kw.txt");
            if (!kwFile.exists() || kwFile.length() == 0) {
                try {
                    writeUtf8Atomically(new File(workDir, "kw.default.tmp"), kwFile, DEFAULT_KEYWORDS_TXT);
                } catch (IOException ignored) {
                }
            }
            synchronized (this) {
                currentKeywordsHash = readAndHashKeywordsFile(kwFile);
            }
            return kwFile;
        }
    }

    private static String readAndHashKeywordsFile(File kwFile) {
        if (kwFile == null || !kwFile.exists()) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(kwFile)) {
            byte[] buf = new byte[(int) Math.min(262144L, kwFile.length())];
            int total = 0;
            while (total < buf.length) {
                int r = fis.read(buf, total, buf.length - total);
                if (r <= 0) break;
                total += r;
            }
            return NorthboundSession.fnv1a64Hex(new String(buf, 0, total, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void writeUtf8Atomically(File tmpFile, File targetFile, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (IOException ignored) {
            }
        }
        if (!tmpFile.renameTo(targetFile)) {
            // Fallback copy if rename across mount/existing target fails
            copyFile(tmpFile, targetFile);
            tmpFile.delete();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
            fos.flush();
        }
    }

    private List<String> resolveSidecarCommand(File tokens, File encoder, File decoder, File joiner, File kwFile) {
        File runtimeLibDir = new File(workDir, "lib");
        if (nativeLibDir != null) {
            File loader = new File(nativeLibDir, "libhm_ld_linux.so");
            File driver = new File(nativeLibDir, "libhm_kws_driver.so");
            if (loader.exists() && driver.exists() && runtimeLibDir.exists()) {
                double thr;
                double sc;
                synchronized (this) {
                    thr = this.threshold;
                    sc = this.score;
                }
                return Arrays.asList(
                        loader.getAbsolutePath(),
                        "--library-path",
                        runtimeLibDir.getAbsolutePath() + ":" + nativeLibDir.getAbsolutePath(),
                        driver.getAbsolutePath(),
                        "--tokens", tokens.getAbsolutePath(),
                        "--encoder", encoder.getAbsolutePath(),
                        "--decoder", decoder.getAbsolutePath(),
                        "--joiner", joiner.getAbsolutePath(),
                        "--keywords-file", kwFile.getAbsolutePath(),
                        "--threshold", String.format(Locale.US, "%.3f", thr),
                        "--score", String.format(Locale.US, "%.2f", sc),
                        "--trailing-blanks", "0",
                        "--name", deviceId
                );
            }
        }
        return null;
    }

    /**
     * Converts raw S16LE PCM bytes to float32 in [-1, 1] and applies gentle KWS-only software
     * AGC normalization (targeting ~ -20 dBFS, bounded to [1.0x, 3.5x]) so quiet phone mic
     * capture achieves strong Zipformer wake recall without amplifying uplink ASR noise.
     */
    public static float[] convertAndNormalizePcmForKws(byte[] pcm16le, float[] gainState) {
        if (pcm16le == null || pcm16le.length < 2) {
            return new float[0];
        }
        int sampleCount = pcm16le.length / 2;
        float[] samples = new float[sampleCount];
        double sumSq = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            float s = (short) ((hi << 8) | lo) / 32768.0f;
            samples[i] = s;
            sumSq += (double) s * (double) s;
        }
        float rms = (float) Math.sqrt(sumSq / Math.max(1, sampleCount));
        float prevGain = (gainState != null && gainState.length > 0 && gainState[0] >= 1.0f)
                ? gainState[0] : KWS_AGC_IDLE_FLOOR_GAIN;
        float nextGain;
        if (rms >= KWS_AGC_MIN_SPEECH_RMS) {
            float desired = KWS_AGC_TARGET_RMS / rms;
            if (desired < 1.0f) {
                desired = 1.0f;
            } else if (desired > KWS_AGC_MAX_GAIN) {
                desired = KWS_AGC_MAX_GAIN;
            }
            nextGain = 0.55f * prevGain + 0.45f * desired;
        } else {
            // Gently relax toward warm idle floor so opening syllables start boosted without pumping silence
            nextGain = 0.90f * prevGain + 0.10f * KWS_AGC_IDLE_FLOOR_GAIN;
        }
        if (gainState != null && gainState.length > 0) {
            gainState[0] = nextGain;
        }
        if (nextGain > 1.01f) {
            for (int i = 0; i < sampleCount; i++) {
                float boosted = samples[i] * nextGain;
                if (boosted > 0.98f) {
                    boosted = 0.98f;
                } else if (boosted < -0.98f) {
                    boosted = -0.98f;
                }
                samples[i] = boosted;
            }
        }
        return samples;
    }

    public static long computeWakeDurationMs(float[] timestamps) {
        if (timestamps == null || timestamps.length == 0) {
            return 0L;
        }
        if (timestamps.length == 1) {
            return 80L;
        }
        float durSec = timestamps[timestamps.length - 1] - timestamps[0];
        return Math.max(80L, Math.round(durSec * 1000.0f) + 80L);
    }

    public static long computeMaxGapMs(float[] timestamps) {
        if (timestamps == null || timestamps.length < 2) {
            return 0L;
        }
        float maxGap = 0f;
        for (int i = 1; i < timestamps.length; i++) {
            float gap = timestamps[i] - timestamps[i - 1];
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        return Math.max(0L, Math.round(maxGap * 1000.0f));
    }
}
