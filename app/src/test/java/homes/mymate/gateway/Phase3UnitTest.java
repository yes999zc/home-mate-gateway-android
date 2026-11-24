package homes.mymate.gateway;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Random;

import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.LocalKwsEngine;
import homes.mymate.gateway.protocol.MiniJson;
import homes.mymate.gateway.protocol.NorthboundSession;
import homes.mymate.gateway.protocol.SoftwareAecFilter;

/**
 * JVM Unit Tests for Phase 3 + v2.0 Compatibility.
 */
public final class Phase3UnitTest {

    public static void main(String[] args) throws Exception {
        testSoftwareAecCancelsInterleavedWidebandEcho();
        testSherpaJniFieldContract();
        testKwsTimestampMetrics();
        testCoreApiWireSchemas();
        testI18nBilingual();
        System.out.println("PASS: All 5 Phase 3 unit tests passed.");
    }

    private static void testSoftwareAecCancelsInterleavedWidebandEcho() {
        SoftwareAecFilter aec = new SoftwareAecFilter();
        int sampleRate = 16000;
        int samplesPerBlock = 1280; // 80ms
        int delaySamples = 640;     // 40ms acoustic propagation delay
        int totalBlocks = 30;
        float[] playbackHistory = new float[totalBlocks * samplesPerBlock];
        Random rng = new Random(42L);
        long now = 10000L;

        double rawRms = -96.0;
        double cleanRms = -96.0;
        double lastErle = 0.0;

        float lp = 0f;
        for (int b = 0; b < totalBlocks; b++) {
            byte[] refChunk = new byte[samplesPerBlock * 2];
            for (int i = 0; i < samplesPerBlock; i++) {
                float white = (rng.nextFloat() * 2f - 1f) * 0.65f;
                lp = 0.65f * lp + 0.35f * white;
                playbackHistory[b * samplesPerBlock + i] = lp;
                short s = (short) Math.round(lp * 32767.0f);
                refChunk[i * 2] = (byte) (s & 0xFF);
                refChunk[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            long blockTs = now + b * 80L;
            aec.pushPlaybackReference(refChunk, sampleRate, 1, blockTs);

            byte[] micBlock = new byte[samplesPerBlock * 2];
            for (int i = 0; i < samplesPerBlock; i++) {
                int srcIdx = b * samplesPerBlock + i - delaySamples;
                float echo = 0f;
                if (srcIdx >= 1) {
                    echo = 0.45f * playbackHistory[srcIdx] + 0.12f * playbackHistory[srcIdx - 1];
                }
                short s = (short) Math.round(echo * 32767.0f);
                micBlock[i * 2] = (byte) (s & 0xFF);
                micBlock[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            byte[] cleaned = aec.processCaptureBlock(micBlock, blockTs);
            if (b == totalBlocks - 1) {
                rawRms = NorthboundSession.computeRmsDbfs(micBlock);
                cleanRms = NorthboundSession.computeRmsDbfs(cleaned);
                lastErle = aec.getLastErleDb();
            }
        }
        assertTrue(cleanRms < rawRms - 6.0,
                "Expected interleaved wideband NLMS AEC attenuation >6dB (raw="
                        + rawRms + "dB, clean=" + cleanRms + "dB, erle=" + lastErle + "dB)");
    }

    private static void testSherpaJniFieldContract() throws Exception {
        OnlineTransducerModelConfig trans = new OnlineTransducerModelConfig("enc.onnx", "dec.onnx", "join.onnx");
        OnlineModelConfig model = new OnlineModelConfig(trans, "tokens.txt", 2, false, "cpu");
        model.setModelType("zipformer2");
        KeywordSpotterConfig cfg = new KeywordSpotterConfig(new FeatureConfig(16000, 80, 0f), model, 4, "kw.txt", 2.6f, 0.28f, 0);
        KeywordSpotterResult res = new KeywordSpotterResult("你好小智", new String[]{"n", "ǐ"}, new float[]{0.12f, 0.56f});

        assertFieldExists(KeywordSpotterConfig.class, "featConfig");
        assertFieldExists(KeywordSpotterConfig.class, "modelConfig");
        assertFieldExists(KeywordSpotterConfig.class, "maxActivePaths");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsFile");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsScore");
        assertFieldExists(KeywordSpotterConfig.class, "keywordsThreshold");
        assertFieldExists(KeywordSpotterConfig.class, "numTrailingBlanks");
        assertFieldExists(OnlineModelConfig.class, "transducer");
        assertFieldExists(OnlineModelConfig.class, "tokens");
        assertFieldExists(OnlineModelConfig.class, "numThreads");
        assertFieldExists(OnlineModelConfig.class, "provider");
        assertFieldExists(OnlineModelConfig.class, "modelType");
        assertFieldExists(OnlineTransducerModelConfig.class, "encoder");
        assertFieldExists(OnlineTransducerModelConfig.class, "decoder");
        assertFieldExists(OnlineTransducerModelConfig.class, "joiner");
        assertFieldExists(OnlineTransducerModelConfig.class, "qnnConfig");

        assertTrue("kw.txt".equals(cfg.getKeywordsFile()), "keywordsFile mismatch");
        assertTrue("你好小智".equals(res.getKeyword()), "result keyword mismatch");
    }

    private static void testKwsTimestampMetrics() {
        float[] ts = new float[]{1.10f, 1.25f, 1.48f, 1.72f};
        long dur = LocalKwsEngine.computeWakeDurationMs(ts);
        long maxGap = LocalKwsEngine.computeMaxGapMs(ts);
        // v2.0 includes +80ms last frame width: (1.72 - 1.10) * 1000 + 80 = 700ms
        assertTrue(dur == 700L, "Expected wake duration 700ms (with +80ms frame), got " + dur);
        assertTrue(maxGap == 240L, "Expected max gap 240ms, got " + maxGap);
    }

    private static void testCoreApiWireSchemas() {
        String sampleStatus = "{\"assistant_name\":\"Home Mate\",\"gateway\":{\"devices\":[{\"stable_id\":\"android-sm-f9760\",\"model\":\"samsung-SM-F9760\",\"online\":true,\"aec_enabled\":true,\"audio_profile\":\"android_farfield_v1\"}]}}";
        List<CoreApiClient.DeviceInfo> devs = CoreApiClient.parseDevicesFromStatus(MiniJson.parseObject(sampleStatus));
        assertTrue(devs.size() == 1 && "android-sm-f9760".equals(devs.get(0).id) && devs.get(0).connected && devs.get(0).aecEnabled,
                "Status device parsing failed");

        String sampleMode = "{\"default_duplex\":\"half\",\"barge_in\":true,\"wake_arbitration\":{\"mode\":\"observe\",\"peers\":3},\"cue_readiness\":{\"wake_ack_enabled\":true},\"media\":{\"android-sm-f9760\":{\"state\":\"playing\",\"title\":\"北京没有你\",\"source\":\"netease\"}},\"devices\":[{\"device\":\"android-sm-f9760\",\"duplex\":{\"requested\":\"full\",\"effective\":\"full\"},\"wake_ack\":{\"enabled\":false}}]}";
        CoreApiClient.ModeState ms = CoreApiClient.parseModeResponse(MiniJson.parseObject(sampleMode), "android-sm-f9760");
        assertTrue("full".equals(ms.duplex), "Expected duplex=full, got " + ms.duplex);
        assertTrue(ms.bargeIn, "Expected bargeIn=true");
        assertTrue("observe".equals(ms.wakeArbitration), "Expected wakeArbitration=observe, got " + ms.wakeArbitration);
        assertTrue(!ms.wakeAck, "Expected wakeAck=false from device wake_ack.enabled");
        assertTrue(ms.musicActive && "北京没有你".equals(ms.musicTitle) && "netease".equals(ms.musicSource),
                "Expected live music state from mode.media");

        String putJson = CoreApiClient.buildModePutBody("full", Boolean.TRUE, Boolean.TRUE, "off");
        Map<String, Object> putObj = MiniJson.parseObject(putJson);
        Map<String, Object> wakeAckObj = MiniJson.getObject(putObj, "wake_ack");
        assertTrue(wakeAckObj != null && MiniJson.getBoolean(wakeAckObj, "enabled", false),
                "PUT mode wake_ack must be nested object {\"enabled\":true}, got: " + putJson);
        assertTrue(MiniJson.getBoolean(putObj, "barge_in", false), "PUT mode barge_in must be true");

        String sampleFleet = "{\"nodes\":[{\"id\":\"dot2-d\",\"base_url\":\"http://192.168.1.73:8930\",\"last_ok_ms\":1000,\"last_error\":null},{\"id\":\"dot2-e\",\"base_url\":\"http://192.168.1.69:8930\",\"last_error\":\"timeout\"}]}";
        List<CoreApiClient.FleetNodeInfo> fleet = CoreApiClient.parseFleetNodes(MiniJson.parseObject(sampleFleet));
        assertTrue(fleet.size() == 2, "Expected 2 fleet nodes");
        assertTrue("http://192.168.1.73:8930".equals(fleet.get(0).url) && fleet.get(0).healthy, "Healthy fleet node mismatch");
        assertTrue("http://192.168.1.69:8930".equals(fleet.get(1).url) && !fleet.get(1).healthy, "Unhealthy fleet node mismatch");

        String chatJson = CoreApiClient.buildChatPostBody("今天天气");
        Map<String, Object> chatObj = MiniJson.parseObject(chatJson);
        assertTrue(!chatObj.containsKey("device_id"), "POST /api/v0/chat must omit device_id");
        assertTrue("今天天气".equals(MiniJson.getString(chatObj, "text", "")), "Chat text mismatch");
    }

    private static void testI18nBilingual() {
        String[] keys = new String[]{
                "tab_voice", "tab_rooms", "tab_console", "tab_settings",
                "standby_mode", "mode_duplex", "mode_barge_in", "settings_sw_aec",
                "kws_jni", "arb_off", "arb_observe", "arb_enforce",
                "settings_kiosk_home", "settings_reset_preset", "notif_boot_resume",
                "badge_lan_exempt", "status_unconfigured", "onboarding_title",
                "auth_banner_title", "settings_edit_profile", "settings_advanced_toggle",
                "console_unconfigured"
        };
        for (String k : keys) {
            String zh = I18n.t(I18n.LANG_ZH, k);
            String en = I18n.t(I18n.LANG_EN, k);
            assertTrue(!zh.equals(k) && !zh.isEmpty(), "Missing ZH translation for " + k);
            assertTrue(!en.equals(k) && !en.isEmpty(), "Missing EN translation for " + k);
        }
    }

    private static void assertFieldExists(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        assertTrue(f != null, "Missing JNI field " + cls.getSimpleName() + "." + name);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ASSERTION FAILED: " + msg);
        }
    }
}
