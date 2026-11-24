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
 * - Enforces min_wake_ms >= 400ms and +80ms frame length in L2 acoustic verification.
 * - Periodically resets OnlineStream every 6 minutes during confirmed silence to prevent 24/7 native RSS drift.
 * - Atomic kw.txt write (kw.txt.tmp -> renameTo) + .bak rollback & backoff retry (never kills supervisor permanently).
 * - Always hashes actual on-disk kw.txt bytes for truthful loaded_hash telemetry.
 */
public final class LocalKwsEngine {

    public static final String MODE_JNI_OFFLINE = "JNI_OFFLINE";
    public static final String MODE_SIDECAR_OFFLINE = "SIDECAR_OFFLINE";
    public static final String MODE_ASR_HYBRID = "ASR_HYBRID";

    public static final List<String> DEFAULT_RETREAT_WORDS = Arrays.asList(
            "退下", "退下吧", "停止播放", "别说了", "闭嘴");

    public static final String DEFAULT_KEYWORDS_TXT =
            "n ǐ h ǎo x iǎo zh ì @你好小智\n"
            + "x iǎo ài t óng x ué @小爱同学\n"
            + "n ǐ h ǎo g āo d á @你好高达\n"
            + "t uì x ià @退下\n"
            + "t uì x ià b a @退下吧\n"
            + "t íng zh ǐ b ō f àng @停止播放\n"
            + "b ié sh uō l e @别说了\n"
            + "b ì z uǐ @闭嘴\n";

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
    private double threshold = 0.28;
    private double score = 2.60;
    private String currentKeywordsHash = "";
    private volatile Process activeProcess;
    private final AtomicBoolean reloadRequested = new AtomicBoolean(false);
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
        double thr = MiniJson.getDouble(obj, "threshold", 0.28);
        double sc = MiniJson.getDouble(obj, "score", 2.60);
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

    public synchronized ValidationResult deployKeywords(
            String keywordsTxt,
            List<String> retreatWords,
            double nextThreshold,
            double nextScore) {
        reloadTokensSetIfPresent();
        ValidationResult vr = validateKeywordsTable(keywordsTxt, validTokenSet);
        if (!vr.valid) {
            return vr;
        }
        this.threshold = Math.max(0.10, Math.min(0.80, nextThreshold));
        this.score = Math.max(0.50, Math.min(5.00, nextScore));
        setRetreatWords(retreatWords);

        if (workDir != null) {
            File kwFile = new File(workDir, "kw.txt");
            File bakFile = new File(workDir, "kw.txt.bak");
            File tmpFile = new File(workDir, "kw.txt.tmp");
            try {
                if (kwFile.exists() && kwFile.length() > 0) {
                    copyFile(kwFile, bakFile);
                }
                writeUtf8Atomically(tmpFile, kwFile, keywordsTxt);
            } catch (IOException e) {
                return new ValidationResult(false, vr.keywordCount, "write_failed:" + e.getMessage(), vr.invalidTokens);
            }
        }
        this.currentKeywordsHash = NorthboundSession.fnv1a64Hex(keywordsTxt);
        requestHotReload();
        return vr;
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
        if (assetPreparer != null) {
            try {
                assetPreparer.prepareWorkspace();
            } catch (Throwable ignored) {
            }
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
                    int sampleCount = pcm.length / 2;
                    float[] samples = new float[sampleCount];
                    for (int i = 0; i < sampleCount; i++) {
                        int lo = pcm[i * 2] & 0xFF;
                        int hi = pcm[i * 2 + 1];
                        samples[i] = (short) ((hi << 8) | lo) / 32768.0f;
                    }
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
                                    || (tokenCount >= 4 && wakeDurMs >= 400L && wakeDurMs <= 1800L && maxGapMs <= 500L);
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
        File kwFile = new File(workDir, "kw.txt");
        if (!kwFile.exists() || kwFile.length() == 0) {
            try {
                writeUtf8Atomically(new File(workDir, "kw.txt.tmp"), kwFile, DEFAULT_KEYWORDS_TXT);
            } catch (IOException ignored) {
            }
        }
        synchronized (this) {
            currentKeywordsHash = readAndHashKeywordsFile(kwFile);
        }
        return kwFile;
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
