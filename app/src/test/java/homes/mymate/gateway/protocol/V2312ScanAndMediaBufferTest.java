package homes.mymate.gateway.protocol;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Focused JVM verification suite for Android Gateway v2.3.12 / v2.3.13 / v2.3.14:
 * 1. CoreDiscovery.probeHost transport fast-fail (no double-timeout) + legacy HTTP 404 /api/v0/auth fallback
 * 2. CoreDiscovery.probeSavedHostsWithFleetFallback seed + fleet peer expansion strictly unauthenticated (P0-1)
 * 3. MediaPlaybackBufferPolicy 1000ms/100ms buffer sizing (incl. 22050Hz), frame alignment, 480ms/EOS/850ms initial prebuffer,
 *    and 320ms/EOS/600ms mid-stream underrun rebuffer trigger/flush (voice never rebuffers)
 * 4. PlaybackStreamDriver behavioral verification (pause-without-flush rebuffer, no duplicate firstChunk,
 *    concurrent stop/barge-in consumedMs & 64-bit head wrap, EOS during empty rebuffer play-before-stop)
 * 5. LocalKwsEngine default 退下 retreat word, northboundKwsSynced reload-race guard, and sendKwsReport loadedHash
 * 6. NorthboundSession.GATEWAY_VERSION == "2.3.15"
 */
public final class V2312ScanAndMediaBufferTest {

    public static void main(String[] args) throws Exception {
        testProbeHostFastFailAndLegacy404Fallback();
        testProbeSavedHostsWithFleetFallbackUnauthenticated();
        testMediaPlaybackBufferPolicy();
        testMediaMidStreamRebufferPolicy();
        testPlaybackStreamDriverBehavioralMidStreamRebufferAndStop();
        testKwsRetreatDefaultAndConfigApplyRaceGuard();
        testGatewayVersion2315();
        System.out.println("PASS: All 7 V2312ScanAndMediaBufferTest suites passed.");
    }

    private static void testProbeHostFastFailAndLegacy404Fallback() throws Exception {
        // 1a. Closed port: must return null quickly (no double-timeout)
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = tmp.getLocalPort();
        }
        long tClosedStart = System.currentTimeMillis();
        CoreDiscovery.DiscoveredCore closedRes =
                CoreDiscovery.probeHost("127.0.0.1", closedPort, 8932, 1200);
        long closedElapsed = System.currentTimeMillis() - tClosedStart;
        assertTrue(closedRes == null, "Closed port probe must return null");
        assertTrue(closedElapsed < 800L, "Closed port probe must fast-fail cleanly, took " + closedElapsed + "ms");

        // 1b. Silent/blackhole TCP endpoint that accepts and hangs without sending HTTP response:
        //     Step 1 (/api/v0/auth) times out once; Step 2 (/api/v0/status) MUST NOT be attempted!
        final AtomicInteger blackholeAccepts = new AtomicInteger(0);
        final ServerSocket blackhole = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final int blackholePort = blackhole.getLocalPort();
        Thread bhThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!blackhole.isClosed()) {
                    try {
                        final Socket s = blackhole.accept();
                        blackholeAccepts.incrementAndGet();
                        Thread holder = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(1500L);
                                } catch (InterruptedException ignored) {
                                } finally {
                                    try { s.close(); } catch (Throwable ignored) {}
                                }
                            }
                        });
                        holder.setDaemon(true);
                        holder.start();
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        });
        bhThread.setDaemon(true);
        bhThread.start();
        try {
            long t0 = System.currentTimeMillis();
            CoreDiscovery.DiscoveredCore bhRes =
                    CoreDiscovery.probeHost("127.0.0.1", blackholePort, 8932, 250);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(bhRes == null, "Blackhole probe must return null");
            assertEquals(1, blackholeAccepts.get(),
                    "Transport-failed Step 1 (/api/v0/auth) MUST NOT open a second connection to /api/v0/status");
            assertTrue(elapsed < 470L,
                    "Blackhole probe must not double-timeout (expected ~250ms, got " + elapsed + "ms)");
        } finally {
            blackhole.close();
        }

        // 1c. Legacy Core returning HTTP 404 on /api/v0/auth MUST still fall back to /api/v0/status
        final AtomicInteger legacyAuthHits = new AtomicInteger(0);
        final AtomicInteger legacyStatusHits = new AtomicInteger(0);
        HttpServer legacyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            int legacyPort = legacyServer.getAddress().getPort();
            legacyServer.createContext("/api/v0/auth", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    legacyAuthHits.incrementAndGet();
                    byte[] resp = "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            legacyServer.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    legacyStatusHits.incrementAndGet();
                    byte[] resp = ("{\"product\":\"Home Mate\",\"core_id\":\"hmcore_legacy_404\","
                            + "\"assistant_name\":\"小智\",\"version\":\"0.28.0\",\"room_name\":\"客厅\","
                            + "\"gateway\":{\"voice_gateway_open\":true,\"bind_addr\":\"0.0.0.0:8932\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            legacyServer.start();

            CoreDiscovery.DiscoveredCore legacy =
                    CoreDiscovery.probeHost("127.0.0.1", legacyPort, 8932, 800);
            assertTrue(legacy != null, "Legacy Core with HTTP 404 on /api/v0/auth must be discovered via /api/v0/status");
            assertEquals(1, legacyAuthHits.get(), "Step 1 /api/v0/auth should be queried once");
            assertEquals(1, legacyStatusHits.get(), "Step 2 /api/v0/status should run after HTTP 404 on /api/v0/auth");
            assertEquals("hmcore_legacy_404", legacy.coreId, "Legacy core_id mismatch");
            assertEquals("客厅", legacy.roomName, "Legacy room_name mismatch");
            assertEquals("0.28.0", legacy.version, "Legacy version mismatch");
            assertTrue(legacy.voiceGatewayOpen && !legacy.authRequired, "Legacy Core flags mismatch");
        } finally {
            legacyServer.stop(0);
        }
    }

    private static void testProbeSavedHostsWithFleetFallbackUnauthenticated() throws Exception {
        // Empty/blank candidate list returns empty cleanly
        assertTrue(CoreDiscovery.probeSavedHostsWithFleetFallback(
                        Collections.<String>emptyList(), 8930, 8932, 500).isEmpty(),
                "Empty candidate list must return empty list");
        assertTrue(CoreDiscovery.probeSavedHostsWithFleetFallback(
                        Arrays.asList(null, "  ", ""), 8930, 8932, 500).isEmpty(),
                "Blank candidate list must return empty list");

        final AtomicInteger authHeadersSeen = new AtomicInteger(0);
        HttpServer peerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer seedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            final int peerPort = peerServer.getAddress().getPort();
            final int seedPort = seedServer.getAddress().getPort();

            peerServer.createContext("/api/v0/auth", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    byte[] resp = ("{\"configured\":true,\"core_id\":\"hmcore_peer_bedroom\","
                            + "\"lan_gateway_exempt\":false,\"voice_gateway_open\":true,"
                            + "\"room_name\":\"主卧\",\"assistant_name\":\"小智\",\"gateway_port\":8932}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            peerServer.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            peerServer.createContext("/api/v0/fleet", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    byte[] resp = "{\"nodes\":[]}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            peerServer.start();

            seedServer.createContext("/api/v0/auth", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    byte[] resp = ("{\"configured\":true,\"core_id\":\"hmcore_seed_living\","
                            + "\"lan_gateway_exempt\":true,\"voice_gateway_open\":true,"
                            + "\"room_name\":\"客厅\",\"assistant_name\":\"小智\",\"gateway_port\":8932}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            seedServer.createContext("/api/v0/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    byte[] resp = ("{\"product\":\"Home Mate\",\"core_id\":\"hmcore_seed_living\","
                            + "\"assistant_name\":\"小智\",\"version\":\"0.35.0\",\"room_name\":\"客厅\","
                            + "\"auth\":{\"configured\":true,\"lan_gateway_exempt\":true},"
                            + "\"gateway\":{\"voice_gateway_open\":true,\"bind_addr\":\"0.0.0.0:8932\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            seedServer.createContext("/api/v0/fleet", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                        authHeadersSeen.incrementAndGet();
                    }
                    // Include valid sibling peer + public SSRF bait that MUST be ignored
                    byte[] resp = ("{\"nodes\":["
                            + "{\"base_url\":\"http://127.0.0.1:" + peerPort + "\"},"
                            + "{\"base_url\":\"http://127.0.0.1:" + seedPort + "\"},"
                            + "{\"base_url\":\"http://8.8.8.8:8930\"}"
                            + "]}").getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                }
            });
            seedServer.start();

            List<CoreDiscovery.DiscoveredCore> discovered = CoreDiscovery.probeSavedHostsWithFleetFallback(
                    Arrays.asList("127.0.0.1", "127.0.0.1"), seedPort, 8932, 500);
            assertEquals(2, discovered.size(),
                    "Saved host fallback must discover seed Core + its /api/v0/fleet sibling peer");
            assertEquals(0, authHeadersSeen.get(),
                    "P0-1: probeSavedHostsWithFleetFallback must NEVER transmit Authorization/Bearer headers");

            CoreDiscovery.DiscoveredCore seedCore = null;
            CoreDiscovery.DiscoveredCore peerCore = null;
            for (CoreDiscovery.DiscoveredCore c : discovered) {
                if ("hmcore_seed_living".equals(c.coreId)) {
                    seedCore = c;
                } else if ("hmcore_peer_bedroom".equals(c.coreId)) {
                    peerCore = c;
                }
            }
            assertTrue(seedCore != null && seedCore.webPort == seedPort && "客厅".equals(seedCore.roomName)
                            && !seedCore.authRequired && seedCore.voiceGatewayOpen,
                    "Seed Core metadata mismatch: " + seedCore);
            assertTrue(peerCore != null && peerCore.webPort == peerPort && "主卧".equals(peerCore.roomName)
                            && peerCore.authRequired && peerCore.voiceGatewayOpen,
                    "Fleet peer Core metadata mismatch: " + peerCore);
        } finally {
            seedServer.stop(0);
            peerServer.stop(0);
        }
    }

    private static void testMediaPlaybackBufferPolicy() {
        // Constants contract
        assertEquals(1000, MediaPlaybackBufferPolicy.MEDIA_BUFFER_MS, "MEDIA_BUFFER_MS must be 1000");
        assertEquals(100, MediaPlaybackBufferPolicy.VOICE_BUFFER_MS, "VOICE_BUFFER_MS must be 100");
        assertEquals(480, MediaPlaybackBufferPolicy.MEDIA_PREBUFFER_MS, "MEDIA_PREBUFFER_MS must be 480");
        assertEquals(850, MediaPlaybackBufferPolicy.MEDIA_PREBUFFER_MAX_WAIT_MS, "MEDIA_PREBUFFER_MAX_WAIT_MS must be 850");
        assertEquals(320, MediaPlaybackBufferPolicy.MEDIA_REBUFFER_MS, "MEDIA_REBUFFER_MS must be 320");
        assertEquals(600, MediaPlaybackBufferPolicy.MEDIA_REBUFFER_MAX_WAIT_MS, "MEDIA_REBUFFER_MAX_WAIT_MS must be 600");
        assertEquals(40, MediaPlaybackBufferPolicy.MEDIA_UNDERRUN_LOW_WATER_MS, "MEDIA_UNDERRUN_LOW_WATER_MS must be 40");

        // AudioTrack buffer sizing & frame alignment across standard rates & mono/stereo (including 22050Hz)
        int[] rates = new int[]{16000, 22050, 24000, 44100, 48000};
        for (int rate : rates) {
            for (int ch = 1; ch <= 2; ch++) {
                int frameBytes = 2 * ch;
                int smallMin = 1024;
                int voiceBytes = MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(false, rate, ch, smallMin);
                int mediaBytes = MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, rate, ch, smallMin);
                int expectedVoice = (int) (((long) rate * frameBytes * 100L) / 1000L);
                int expectedMedia = (int) (((long) rate * frameBytes * 1000L) / 1000L);
                expectedVoice = ((expectedVoice + frameBytes - 1) / frameBytes) * frameBytes;
                expectedMedia = ((expectedMedia + frameBytes - 1) / frameBytes) * frameBytes;

                assertEquals(expectedVoice, voiceBytes,
                        "Voice buffer must be 100ms for " + rate + "Hz/" + ch + "ch");
                assertEquals(expectedMedia, mediaBytes,
                        "Media buffer must be 1000ms for " + rate + "Hz/" + ch + "ch");
                assertEquals(0, voiceBytes % frameBytes, "Voice buffer must be frame-aligned");
                assertEquals(0, mediaBytes % frameBytes, "Media buffer must be frame-aligned");

                // Initial prebuffer target (480ms)
                int prebuf = MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(rate, ch);
                int expectedPrebuf = (int) (((long) rate * frameBytes * 480L) / 1000L);
                expectedPrebuf = ((expectedPrebuf + frameBytes - 1) / frameBytes) * frameBytes;
                assertEquals(expectedPrebuf, prebuf,
                        "Media prebuffer must be 480ms for " + rate + "Hz/" + ch + "ch");
                assertEquals(0, prebuf % frameBytes, "Media prebuffer must be frame-aligned");
                assertTrue(prebuf < mediaBytes,
                        "480ms prebuffer must always fit inside 1000ms AudioTrack ring buffer");

                // Mid-stream rebuffer target (320ms)
                int rebuf = MediaPlaybackBufferPolicy.computeMediaRebufferBytes(rate, ch);
                int expectedRebuf = (int) (((long) rate * frameBytes * 320L) / 1000L);
                expectedRebuf = ((expectedRebuf + frameBytes - 1) / frameBytes) * frameBytes;
                assertEquals(expectedRebuf, rebuf,
                        "Media rebuffer must be 320ms for " + rate + "Hz/" + ch + "ch");
                assertEquals(0, rebuf % frameBytes, "Media rebuffer must be frame-aligned");
                assertTrue(rebuf < mediaBytes,
                        "320ms rebuffer must always fit inside 1000ms AudioTrack ring buffer");
            }
        }

        // Exact expected values for key formats (16kHz, 22.05kHz, 44.1kHz, 48kHz)
        assertEquals(3200, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(false, 16000, 1, 1280),
                "16kHz mono voice must preserve 100ms (3200B)");
        assertEquals(44100, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 22050, 1, 2048),
                "22.05kHz mono media must be 1000ms (44100B)");
        assertEquals(88200, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 22050, 2, 4096),
                "22.05kHz stereo media must be 1000ms (88200B)");
        assertEquals(21168, MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(22050, 1),
                "22.05kHz mono prebuffer must be 480ms (21168B)");
        assertEquals(14112, MediaPlaybackBufferPolicy.computeMediaRebufferBytes(22050, 1),
                "22.05kHz mono rebuffer must be 320ms (14112B)");
        assertEquals(176400, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 44100, 2, 14144),
                "44.1kHz stereo media must be 1000ms (176400B)");
        assertEquals(192000, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 48000, 2, 15360),
                "48kHz stereo media must be 1000ms (192000B)");
        assertEquals(84672, MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(44100, 2),
                "44.1kHz stereo prebuffer must be 480ms (84672B)");
        assertEquals(92160, MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(48000, 2),
                "48kHz stereo prebuffer must be 480ms (92160B)");
        assertEquals(56448, MediaPlaybackBufferPolicy.computeMediaRebufferBytes(44100, 2),
                "44.1kHz stereo rebuffer must be 320ms (56448B)");
        assertEquals(61440, MediaPlaybackBufferPolicy.computeMediaRebufferBytes(48000, 2),
                "48kHz stereo rebuffer must be 320ms (61440B)");

        // Unaligned large HAL minBufBytes must scale 3x for media, 1x for voice, and align UP to frameBytes
        assertEquals(180004, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 44100, 2, 60001),
                "Media buffer must honor 3*minBuf and align up to 4-byte stereo frame");
        assertEquals(40004, MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(false, 44100, 2, 40001),
                "Voice buffer must honor 1*minBuf and align up to 4-byte stereo frame");

        // Invalid/zero inputs fall back safely and remain frame-aligned
        int fallbackBuf = MediaPlaybackBufferPolicy.computeAudioTrackBufferBytes(true, 0, 0, -1);
        assertTrue(fallbackBuf > 0 && (fallbackBuf % 2 == 0), "Fallback buffer must be positive & aligned");
        int oddPrebuf = MediaPlaybackBufferPolicy.computeMediaPrebufferBytes(22051, 2);
        assertEquals(0, oddPrebuf % 4, "Odd rate stereo prebuffer must align to 4-byte frames");
        int oddRebuf = MediaPlaybackBufferPolicy.computeMediaRebufferBytes(22051, 2);
        assertEquals(0, oddRebuf % 4, "Odd rate stereo rebuffer must align to 4-byte frames");

        // Initial prebuffer flush trigger policy (480ms target / 850ms max wait)
        long target = 84672L;
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(0L, target, false, 0L),
                "Empty prebuffer at 0ms without EOS must NOT flush");
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(0L, target, false, 1000L),
                "Empty prebuffer even after 1000ms without EOS must NOT flush");
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(target - 4L, target, false, 849L),
                "Under-target prebuffer at 849ms without EOS must NOT flush yet");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(target, target, false, 0L),
                "Exact 480ms target bytes MUST trigger flush immediately");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(target + 4096L, target, false, 40L),
                "Over-target bytes MUST trigger flush immediately");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(1024L, target, true, 5L),
                "EOS received with partial bytes MUST trigger early flush");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(0L, target, true, 0L),
                "EOS received with 0 bytes returns true cleanly");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaPrebuffer(1024L, target, false, 850L),
                "Partial bytes after 850ms max wait MUST trigger flush");
    }

    private static void testMediaMidStreamRebufferPolicy() {
        // Low-water frame calculation (40ms)
        assertEquals(640L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(16000),
                "16kHz low-water threshold must be 640 frames (40ms)");
        assertEquals(882L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(22050),
                "22.05kHz low-water threshold must be 882 frames (40ms)");
        assertEquals(960L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(24000),
                "24kHz low-water threshold must be 960 frames (40ms)");
        assertEquals(1764L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(44100),
                "44.1kHz low-water threshold must be 1764 frames (40ms)");
        assertEquals(1920L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(48000),
                "48kHz low-water threshold must be 1920 frames (40ms)");
        assertEquals(640L, MediaPlaybackBufferPolicy.computeMediaLowWaterFrames(0),
                "Invalid sampleRate<=0 must default to 16kHz (640 frames)");

        // Voice streams MUST NEVER enter mid-stream rebuffer even at 0 buffered frames
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(false, true, false, 0L, 16000),
                "Voice stream must NEVER enter mid-stream rebuffer at 0 frames");
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(false, true, false, 0L, 44100),
                "Voice stream at 44.1kHz must NEVER enter mid-stream rebuffer");

        // Unstarted or EOS-received media streams MUST NOT enter mid-stream rebuffer
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, false, false, 0L, 44100),
                "Unstarted media stream (still in initial prebuffer) must NOT enter mid-stream rebuffer");
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, true, true, 0L, 44100),
                "EOS-received media stream must NOT enter mid-stream rebuffer");

        // Active media stream enters rebuffer iff bufferedFramesInTrack <= 40ms low-water threshold
        assertTrue(MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, true, false, 0L, 44100),
                "Active media stream at 0 queued frames MUST enter rebuffer");
        assertTrue(MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, true, false, 1764L, 44100),
                "Active media stream at exact 40ms low-water boundary (1764 frames) MUST enter rebuffer");
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, true, false, 1765L, 44100),
                "Active media stream above 40ms low-water boundary (1765 frames) must NOT enter rebuffer");
        assertTrue(!MediaPlaybackBufferPolicy.shouldEnterMediaRebuffer(true, true, false, 13230L, 44100),
                "Active media stream with healthy 300ms cushion must NOT enter rebuffer");

        // Mid-stream rebuffer flush trigger policy (320ms target / 600ms max wait)
        long rebufTarget = 56448L;
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(0L, rebufTarget, false, 0L),
                "Empty rebuffer at 0ms without EOS must NOT flush");
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(0L, rebufTarget, false, 900L),
                "Empty rebuffer even after 900ms without EOS must NOT flush");
        assertTrue(!MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(rebufTarget - 4L, rebufTarget, false, 599L),
                "Under-target rebuffer at 599ms without EOS must NOT flush yet");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(rebufTarget, rebufTarget, false, 0L),
                "Exact 320ms rebuffer target bytes MUST trigger flush immediately");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(rebufTarget + 4096L, rebufTarget, false, 40L),
                "Over-target rebuffer bytes MUST trigger flush immediately");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(1024L, rebufTarget, true, 5L),
                "EOS received during rebuffer with partial bytes MUST trigger early flush");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(0L, rebufTarget, true, 0L),
                "EOS received during rebuffer with 0 bytes returns true cleanly");
        assertTrue(MediaPlaybackBufferPolicy.shouldFlushMediaRebuffer(1024L, rebufTarget, false, 600L),
                "Partial rebuffered bytes after 600ms max wait MUST trigger flush");
    }

    private static final class FakeTrackHandle implements PlaybackStreamDriver.TrackHandle {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger playCount = new AtomicInteger(0);
        final AtomicInteger pauseCount = new AtomicInteger(0);
        final AtomicInteger flushCount = new AtomicInteger(0);
        final AtomicInteger stopCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        final AtomicLong writtenBytes = new AtomicLong(0L);
        final AtomicInteger headPosition = new AtomicInteger(0);
        volatile Runnable onPlayHook = null;
        volatile Runnable onPauseHook = null;
        volatile Runnable onWriteHook = null;

        @Override
        public void play() {
            events.add("PLAY");
            playCount.incrementAndGet();
            Runnable hook = onPlayHook;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void pause() {
            events.add("PAUSE");
            pauseCount.incrementAndGet();
            Runnable hook = onPauseHook;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void flush() {
            events.add("FLUSH");
            flushCount.incrementAndGet();
        }

        @Override
        public void stop() {
            events.add("STOP");
            stopCount.incrementAndGet();
        }

        @Override
        public void release() {
            events.add("RELEASE");
            releaseCount.incrementAndGet();
        }

        @Override
        public int getPlaybackHeadPosition() {
            return headPosition.get();
        }

        @Override
        public int write(byte[] data, int offset, int length) {
            writtenBytes.addAndGet(length);
            Runnable hook = onWriteHook;
            if (hook != null) {
                hook.run();
            }
            return length;
        }
    }

    private static final class RecordingWriterCallbacks implements PlaybackStreamDriver.WriterCallbacks {
        final AtomicInteger firstChunkCalls = new AtomicInteger(0);
        final AtomicInteger errorCalls = new AtomicInteger(0);
        final AtomicLong totalPcmCallbacksBytes = new AtomicLong(0L);

        @Override
        public void onBeforeWriteChunk(byte[] chunk, long nowMs) {}

        @Override
        public void onPcmWritten(byte[] chunk, int offset, int written, long nowMs) {
            totalPcmCallbacksBytes.addAndGet(written);
        }

        @Override
        public void onFirstChunkWritten() {
            firstChunkCalls.incrementAndGet();
        }

        @Override
        public void onAudioError(String stage, String message) {
            errorCalls.incrementAndGet();
        }
    }

    private static void testPlaybackStreamDriverBehavioralMidStreamRebufferAndStop() throws Exception {
        // (a1) Mid-stream underrun (<=40ms queued HW frames on 120ms poll timeout) pauses WITHOUT flush,
        //      accumulates 320ms, resumes play(), and NEVER emits a duplicate firstChunk callback.
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "media-a1", "media", true, 22050, 1, 64, track);
            final CountDownLatch initialPlayLatch = new CountDownLatch(1);
            final CountDownLatch rebufferPauseLatch = new CountDownLatch(1);
            final CountDownLatch resumedPlayLatch = new CountDownLatch(2);
            final CountDownLatch resumedWriteDoneLatch = new CountDownLatch(1);
            track.onPlayHook = new Runnable() {
                @Override
                public void run() {
                    initialPlayLatch.countDown();
                    resumedPlayLatch.countDown();
                }
            };
            track.onPauseHook = new Runnable() {
                @Override
                public void run() {
                    rebufferPauseLatch.countDown();
                }
            };
            track.onWriteHook = new Runnable() {
                @Override
                public void run() {
                    if (track.writtenBytes.get() >= 21168L + 14112L) {
                        resumedWriteDoneLatch.countDown();
                    }
                }
            };
            final AtomicReference<PlaybackStreamDriver.RunResult> resRef =
                    new AtomicReference<PlaybackStreamDriver.RunResult>();
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    resRef.set(PlaybackStreamDriver.runWriterLoop(state, cb));
                }
            }, "test-driver-a1");
            state.writerThread = wt;
            wt.start();

            // Feed 480ms initial prebuffer @ 22.05kHz mono (21168B = 10584 frames)
            state.chunkQueue.offer(new byte[10584]);
            state.chunkQueue.offer(new byte[10584]);
            assertTrue(initialPlayLatch.await(1500L, TimeUnit.MILLISECONDS), "(a1) initial play() must trigger");
            // Wait briefly for both initial chunks to finish writing
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 21168L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            assertEquals(21168L, track.writtenBytes.get(), "(a1) initial 480ms prebuffer must write completely");
            assertEquals(1, cb.firstChunkCalls.get(), "(a1) firstChunk must fire exactly once on initial start");

            // Drain HW head down to exact 40ms low-water boundary (10584 - 882 = 9702 frames)
            track.headPosition.set(9702);
            assertTrue(rebufferPauseLatch.await(1500L, TimeUnit.MILLISECONDS),
                    "(a1) 120ms empty poll at <=40ms HW queued frames MUST call pause()");
            assertEquals(1, track.pauseCount.get(), "(a1) pause() must be called once on underrun");
            assertEquals(0, track.flushCount.get(), "(a1) underrun pause MUST NOT call flush()");
            assertEquals(1, track.playCount.get(), "(a1) play() count must still be 1 while rebuffering");

            // Feed half of 320ms rebuffer (7056B = 160ms < 320ms): MUST stay paused!
            state.chunkQueue.offer(new byte[7056]);
            Thread.sleep(100L);
            assertEquals(1, track.playCount.get(), "(a1) partial 160ms chunk must remain held in rebuffer");
            assertEquals(21168L, track.writtenBytes.get(), "(a1) partial rebuffer chunk must not write prematurely");

            // Feed second half to reach 320ms target (14112B): MUST resume play() and flush both chunks!
            state.chunkQueue.offer(new byte[7056]);
            assertTrue(resumedPlayLatch.await(1500L, TimeUnit.MILLISECONDS),
                    "(a1) reaching 320ms rebuffer target MUST resume play()");
            assertTrue(resumedWriteDoneLatch.await(1500L, TimeUnit.MILLISECONDS),
                    "(a1) resumed rebuffer chunks must write to track");
            assertEquals(2, track.playCount.get(), "(a1) play() must be called twice (initial + resume)");
            assertEquals(0, track.flushCount.get(), "(a1) rebuffer recovery MUST NEVER call flush()");
            assertEquals(1, cb.firstChunkCalls.get(), "(a1) resume MUST NOT emit duplicate firstChunk (streaming) callback");
            assertEquals(21168L + 14112L, track.writtenBytes.get(), "(a1) total written bytes mismatch");

            // Finish stream cleanly with EOS
            track.headPosition.set((21168 + 14112) / 2);
            state.eosReceived = true;
            state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL);
            wt.join(1500L);
            assertTrue(!wt.isAlive(), "(a1) writer thread must exit cleanly after EOS");
            PlaybackStreamDriver.RunResult res = resRef.get();
            assertTrue(res != null && res.reachedEos && !res.wasCancelled && res.measuredHwDelay,
                    "(a1) must finish with reachedEos=true, wasCancelled=false, measuredHwDelay=true");
            assertEquals(Arrays.asList("PLAY", "PAUSE", "PLAY", "STOP"), track.events,
                    "(a1) track lifecycle sequence mismatch");
        }

        // (a2) Mid-stream rebuffer max-wait (600ms) flushes partial chunk cleanly without duplicate firstChunk
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "media-a2", "media", true, 22050, 1, 64, track);
            final CountDownLatch pauseLatch = new CountDownLatch(1);
            final CountDownLatch resumeLatch = new CountDownLatch(2);
            final CountDownLatch partialWriteLatch = new CountDownLatch(1);
            track.onPlayHook = new Runnable() {
                @Override
                public void run() {
                    resumeLatch.countDown();
                }
            };
            track.onPauseHook = new Runnable() {
                @Override
                public void run() {
                    pauseLatch.countDown();
                }
            };
            track.onWriteHook = new Runnable() {
                @Override
                public void run() {
                    if (track.writtenBytes.get() >= 21168L + 2204L) {
                        partialWriteLatch.countDown();
                    }
                }
            };
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    PlaybackStreamDriver.runWriterLoop(state, cb);
                }
            }, "test-driver-a2");
            state.writerThread = wt;
            wt.start();

            state.chunkQueue.offer(new byte[21168]);
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 21168L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            track.headPosition.set(10584); // 0 HW frames queued
            assertTrue(pauseLatch.await(1500L, TimeUnit.MILLISECONDS), "(a2) underrun pause must trigger");

            long partialT0 = System.currentTimeMillis();
            state.chunkQueue.offer(new byte[2204]); // ~50ms (< 320ms target)
            assertTrue(resumeLatch.await(1500L, TimeUnit.MILLISECONDS),
                    "(a2) partial rebuffer chunk MUST resume after 600ms max wait");
            long elapsedMs = System.currentTimeMillis() - partialT0;
            assertTrue(elapsedMs >= 540L,
                    "(a2) partial chunk must wait for ~600ms max wait before flushing, elapsed=" + elapsedMs + "ms");
            assertTrue(partialWriteLatch.await(1000L, TimeUnit.MILLISECONDS), "(a2) partial chunk must write on resume");
            assertEquals(0, track.flushCount.get(), "(a2) timeout rebuffer flush must NOT call track.flush()");
            assertEquals(1, cb.firstChunkCalls.get(), "(a2) timeout resume must NOT duplicate firstChunk callback");

            track.headPosition.set((21168 + 2204) / 2);
            state.eosReceived = true;
            state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL);
            wt.join(1500L);
            assertTrue(!wt.isAlive(), "(a2) writer thread must exit cleanly");
        }

        // (a3) Voice stream (!isMedia) NEVER pauses/rebuffers on empty poll even at 0 HW frames
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "voice-a3", "voice", false, 16000, 1, 64, track);
            state.trackStarted = true; // voice calls track.play() in beginStream
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    PlaybackStreamDriver.runWriterLoop(state, cb);
                }
            }, "test-driver-a3");
            state.writerThread = wt;
            wt.start();

            state.chunkQueue.offer(new byte[3200]); // 100ms
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 3200L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            track.headPosition.set(1600); // 0 HW frames queued
            Thread.sleep(220L); // > 150ms voice poll timeout
            assertEquals(0, track.pauseCount.get(), "(a3) voice stream must NEVER pause for mid-stream rebuffer");
            assertEquals(0, track.flushCount.get(), "(a3) voice stream must NOT flush");
            state.chunkQueue.offer(new byte[1600]); // 50ms -> must write immediately!
            waitDeadline = System.currentTimeMillis() + 500L;
            while (track.writtenBytes.get() < 4800L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            assertEquals(4800L, track.writtenBytes.get(), "(a3) voice chunk must write immediately with 0ms rebuffer");
            track.headPosition.set(2400);
            state.eosReceived = true;
            state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL);
            wt.join(1500L);
            assertTrue(!wt.isAlive(), "(a3) voice writer thread must exit cleanly");
        }

        // (b) Concurrent stop / barge-in during mid-stream rebuffer exits immediately without deadlock
        //     and preserves accurate consumedMs from unwrapped HW head frames (including 32-bit wraparound!).
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "media-b", "media", true, 22050, 1, 64, track);
            final CountDownLatch pauseLatch = new CountDownLatch(1);
            track.onPauseHook = new Runnable() {
                @Override
                public void run() {
                    pauseLatch.countDown();
                }
            };
            final AtomicReference<PlaybackStreamDriver.RunResult> resRef =
                    new AtomicReference<PlaybackStreamDriver.RunResult>();
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    resRef.set(PlaybackStreamDriver.runWriterLoop(state, cb));
                }
            }, "test-driver-b");
            state.writerThread = wt;
            wt.start();

            // Write 480ms (21168B = 10584 frames)
            state.chunkQueue.offer(new byte[21168]);
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 21168L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            assertEquals(480, state.lastQueuedTrackMs,
                    "(b) lastQueuedTrackMs must report exact AudioTrack queued depth (480ms at head=0)");
            // Leave 441 frames (20ms <= 40ms) queued in HW -> headPosition = 10143 frames (460ms consumed)
            track.headPosition.set(10143);
            assertTrue(pauseLatch.await(1500L, TimeUnit.MILLISECONDS), "(b) underrun pause must trigger");

            // Push 1 chunk (< 320ms) into rebuffer so prebuffer is non-empty when barge-in stop arrives
            state.chunkQueue.offer(new byte[7056]);
            Thread.sleep(60L);
            assertEquals(21168L, track.writtenBytes.get(), "(b) rebuffered chunk must not be written yet");

            long stopT0 = System.currentTimeMillis();
            NorthboundSession.StopPlaybackResult stopRes = PlaybackStreamDriver.stopAndMeasure(state);
            wt.join(1000L);
            long stopElapsedMs = System.currentTimeMillis() - stopT0;

            assertTrue(!wt.isAlive(), "(b) writer thread MUST exit immediately on stop during rebuffer");
            assertTrue(stopElapsedMs < 300L, "(b) stop during rebuffer must be instant (<300ms), took " + stopElapsedMs + "ms");
            assertTrue(stopRes.found, "(b) stopRes.found must be true");
            assertEquals("hw_delay", stopRes.basis, "(b) stopRes.basis must be hw_delay");
            assertEquals(480L, stopRes.writtenMs, "(b) writtenMs must reflect only bytes written to track (480ms)");
            assertEquals(460L, stopRes.consumedMs, "(b) consumedMs must match exact HW head frames (10143 @ 22050Hz = 460ms)");
            assertEquals(1, track.flushCount.get(), "(b) flush() must be called only by stopAndMeasure, never by underrun pause");
            assertEquals(1, cb.firstChunkCalls.get(), "(b) firstChunk must fire only once");
            assertTrue(resRef.get() != null && resRef.get().wasCancelled, "(b) RunResult must report wasCancelled=true");

            // Also verify 64-bit unwrapped HW head math across 32-bit unsigned rollover (> 2^32 frames)
            FakeTrackHandle wrapTrack = new FakeTrackHandle();
            PlaybackStreamDriver.StreamState wrapState = new PlaybackStreamDriver.StreamState(
                    "media-wrap", "media", true, 44100, 2, 16, wrapTrack);
            wrapState.trackStarted = true;
            wrapState.pcmBytesWritten = 0x1_0000_2000L * 4L; // > 2^32 frames written
            synchronized (wrapState.trackLock) {
                wrapTrack.headPosition.set(0xFFFF_F000);
                long h1 = wrapState.readUnwrappedHeadFramesLocked();
                assertEquals(0xFFFF_F000L, h1, "(b-wrap) unsigned 32-bit head near rollover must not sign-extend");
                wrapTrack.headPosition.set(0x0000_1000); // wrapped past 2^32!
                long h2 = wrapState.readUnwrappedHeadFramesLocked();
                assertEquals(0x1_0000_1000L, h2, "(b-wrap) unwrapped 64-bit head must advance past 2^32");
            }
            NorthboundSession.StopPlaybackResult wrapStop = PlaybackStreamDriver.stopAndMeasure(wrapState);
            long expectedWrapMs = (0x1_0000_1000L * 1000L) / 44100L;
            assertEquals(expectedWrapMs, wrapStop.consumedMs, "(b-wrap) stopAndMeasure consumedMs must use unwrapped 64-bit head");
        }

        // (c) EOS arriving while mid-stream rebuffer has 0 new bytes MUST call play() BEFORE stop()
        //     when unwrapped HW queued frames remain so tail audio is not clipped!
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "media-c", "media", true, 22050, 1, 64, track);
            final CountDownLatch pauseLatch = new CountDownLatch(1);
            track.onPauseHook = new Runnable() {
                @Override
                public void run() {
                    pauseLatch.countDown();
                }
            };
            track.onPlayHook = new Runnable() {
                @Override
                public void run() {
                    if (track.playCount.get() == 2) {
                        // Resuming on EOS allows HW DAC to finish draining remaining 441 frames (20ms)!
                        track.headPosition.set(10584);
                    }
                }
            };
            final AtomicReference<PlaybackStreamDriver.RunResult> resRef =
                    new AtomicReference<PlaybackStreamDriver.RunResult>();
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    resRef.set(PlaybackStreamDriver.runWriterLoop(state, cb));
                }
            }, "test-driver-c");
            state.writerThread = wt;
            wt.start();

            state.chunkQueue.offer(new byte[21168]); // 480ms = 10584 frames
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 21168L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            // Leave 441 frames (20ms <= 40ms) remaining in HW buffer -> enter rebuffer pause
            track.headPosition.set(10143);
            assertTrue(pauseLatch.await(1500L, TimeUnit.MILLISECONDS), "(c) underrun pause must trigger");

            // Send EOS with ZERO new rebuffered bytes!
            long eosT0 = System.currentTimeMillis();
            state.eosReceived = true;
            state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL);
            wt.join(1500L);
            long eosElapsedMs = System.currentTimeMillis() - eosT0;

            assertTrue(!wt.isAlive(), "(c) writer thread must exit cleanly after EOS");
            assertTrue(eosElapsedMs < 400L, "(c) EOS drain must not stall for 1200ms after resume, took " + eosElapsedMs + "ms");
            assertEquals(Arrays.asList("PLAY", "PAUSE", "PLAY", "STOP"), track.events,
                    "(c) EOS during empty rebuffer with remaining HW frames MUST call play() BEFORE stop()");
            assertEquals(0, track.flushCount.get(), "(c) EOS tail drain must NOT call flush()");
            assertEquals(1, cb.firstChunkCalls.get(), "(c) EOS tail resume must NOT emit duplicate firstChunk");
            PlaybackStreamDriver.RunResult res = resRef.get();
            assertTrue(res != null && res.reachedEos && !res.wasCancelled && res.measuredHwDelay,
                    "(c) must finish cleanly with reachedEos=true & measuredHwDelay=true");
        }

        // (c2) Complementary check: EOS during empty rebuffer with 0 remaining HW frames calls stop() directly
        {
            final FakeTrackHandle track = new FakeTrackHandle();
            final RecordingWriterCallbacks cb = new RecordingWriterCallbacks();
            final PlaybackStreamDriver.StreamState state = new PlaybackStreamDriver.StreamState(
                    "media-c2", "media", true, 22050, 1, 64, track);
            final CountDownLatch pauseLatch = new CountDownLatch(1);
            track.onPauseHook = new Runnable() {
                @Override
                public void run() {
                    pauseLatch.countDown();
                }
            };
            Thread wt = new Thread(new Runnable() {
                @Override
                public void run() {
                    PlaybackStreamDriver.runWriterLoop(state, cb);
                }
            }, "test-driver-c2");
            state.writerThread = wt;
            wt.start();

            state.chunkQueue.offer(new byte[21168]);
            long waitDeadline = System.currentTimeMillis() + 1000L;
            while (track.writtenBytes.get() < 21168L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            track.headPosition.set(10584); // 0 HW frames remaining
            assertTrue(pauseLatch.await(1500L, TimeUnit.MILLISECONDS), "(c2) underrun pause must trigger");

            state.eosReceived = true;
            state.chunkQueue.offer(PlaybackStreamDriver.StreamState.END_OF_STREAM_SENTINEL);
            wt.join(1500L);
            assertTrue(!wt.isAlive(), "(c2) writer thread must exit cleanly");
            assertEquals(Arrays.asList("PLAY", "PAUSE", "STOP"), track.events,
                    "(c2) EOS with 0 remaining HW frames calls stop() directly without extra play()");
        }
    }

    private static void testKwsRetreatDefaultAndConfigApplyRaceGuard() throws Exception {
        // 1. Default vocabulary must include "退下" as both retreat word and compiled keywords entry
        assertTrue(LocalKwsEngine.DEFAULT_RETREAT_WORDS.contains("退下"),
                "DEFAULT_RETREAT_WORDS must contain 退下");
        assertTrue(LocalKwsEngine.DEFAULT_KEYWORDS_TXT.contains("t uì x ià @退下\n"),
                "DEFAULT_KEYWORDS_TXT must contain 't uì x ià @退下'");

        File tmpDir = Files.createTempDirectory("hm-v2314-kws").toFile();
        try {
            File modelDir = new File(tmpDir, "model");
            assertTrue(modelDir.mkdirs(), "create model dir");
            Files.write(new File(modelDir, "tokens.txt").toPath(),
                    "n 0\nǐ 1\nh 2\nǎo 3\nx 4\niǎo 5\nzh 6\nì 7\nt 8\nuì 9\nià 10\n".getBytes(StandardCharsets.UTF_8));

            final LocalKwsEngine engine = new LocalKwsEngine(tmpDir, null, "android-v2314", null);
            assertTrue(engine.isRetreatWord("退下"), "Default LocalKwsEngine.isRetreatWord(\"退下\") must be true");
            assertTrue(engine.isRetreatWord("退下吧"), "Default LocalKwsEngine.isRetreatWord(\"退下吧\") must be true");

            // 2. Reproduce exact reconnect race: onConfigApplyV2(block="kws") enters deployKeywords()
            //    and blocks on reloadBarrierLock while onKwsReady() runs concurrently.
            //    northboundKwsSynced MUST already be true (blocking stale-snapshot HTTP reflex sync)
            //    BEFORE deployKeywords() returns, and sendKwsReport(..., loadedHash) MUST report exact hash.
            final List<Map<String, Object>> sentFrames = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
            NorthboundSession.FrameTransport tx = new NorthboundSession.FrameTransport() {
                @Override
                public void sendJsonLine(String jsonLine) {
                    sentFrames.add(MiniJson.parseObject(jsonLine));
                }
            };
            final NorthboundSession session = new NorthboundSession(
                    "android-v2314", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-v2314", tx,
                    new NorthboundSession.BaseListener());
            session.attachLocalKwsEngine(engine);
            // Simulate hello_ack without config_apply_v2 capability so shouldAllowHttpReflexSync() starts TRUE
            session.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":0,\"capabilities\":[],\"generation\":1}");
            assertTrue(session.shouldAllowHttpReflexSync(),
                    "Precondition: HTTP reflex sync starts allowed before northbound KWS deploy");

            final String lx06Kw = "n ǐ h ǎo x iǎo zh ì @你好小智\nt uì x ià @退下\n";
            final String lx06KwJson = "\"n ǐ h ǎo x iǎo zh ì @你好小智\\nt uì x ià @退下\\n\"";
            final String lx06Hash = NorthboundSession.fnv1a64Hex(lx06Kw);
            engine.configureReloadBarrierForTest(true, 2500L);

            final AtomicReference<Throwable> applyErr = new AtomicReference<Throwable>(null);
            final String applyFrame = "{\"type\":\"config_apply\",\"device\":\"android-v2314\",\"block\":\"kws\","
                    + "\"block_generation\":1,\"data_epoch\":1,\"operation_id\":\"op-lx06\","
                    + "\"values\":{\"keywords\":" + lx06KwJson
                    + ",\"retreat_words\":[\"退下\"],\"threshold\":0.22,\"score\":3.00}}";
            Thread applyThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        session.handleIncomingLine(applyFrame);
                    } catch (Throwable t) {
                        applyErr.set(t);
                    }
                }
            }, "test-config-apply-race");
            applyThread.start();

            // Wait until deployKeywords() has incremented requestedReloadSeq and is blocked inside reloadBarrierLock
            long waitDeadline = System.currentTimeMillis() + 1500L;
            while (engine.getRequestedReloadSeqForTest() < 1L && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            assertEquals(1L, engine.getRequestedReloadSeqForTest(), "deployKeywords must reach reload barrier");
            assertTrue(applyThread.isAlive(), "config_apply thread must still be waiting on reloadBarrierLock");

            // CRITICAL RACE ASSERTION: while deployKeywords() is STILL in-flight, HTTP reflex sync MUST be blocked!
            assertTrue(session.isNorthboundKwsSynced(),
                    "northboundKwsSynced MUST be true BEFORE deployKeywords() returns");
            assertTrue(!session.shouldAllowHttpReflexSync(),
                    "shouldAllowHttpReflexSync() MUST be false during deployKeywords() reload barrier");

            // Concurrent onKwsReady -> sendKwsReport(..., loadedHash) emits the exact loaded hash
            sentFrames.clear();
            session.sendKwsReport("running", "JNI_OFFLINE ready", lx06Hash);
            Map<String, Object> kwsReport = null;
            for (int i = sentFrames.size() - 1; i >= 0; i--) {
                if ("kws_report".equals(sentFrames.get(i).get("type"))) {
                    kwsReport = sentFrames.get(i);
                    break;
                }
            }
            assertTrue(kwsReport != null, "sendKwsReport must emit kws_report frame");
            assertEquals(lx06Hash, MiniJson.getString(kwsReport, "loaded_hash", ""),
                    "sendKwsReport(..., loadedHash) must report the exact loadedHash");

            // Unblock JNI reload barrier and confirm config_apply finishes cleanly with kw.txt intact
            engine.notifyReloadOutcomeForTest(1L, true, "applied");
            applyThread.join(1500L);
            assertTrue(!applyThread.isAlive(), "config_apply thread must finish after reload outcome");
            assertTrue(applyErr.get() == null, "config_apply must not throw: " + applyErr.get());
            assertEquals(lx06Hash, engine.getCurrentKeywordsHash(), "on-disk kw.txt hash must remain lx06Hash");

            // 3. Rollback check: failed config_apply(kws) on fresh session restores northboundKwsSynced=false
            engine.configureReloadBarrierForTest(false, 500L);
            NorthboundSession failSession = new NorthboundSession(
                    "android-v2314", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-v2314-fail", tx,
                    new NorthboundSession.BaseListener());
            failSession.attachLocalKwsEngine(engine);
            failSession.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":0,\"capabilities\":[],\"generation\":1}");
            assertTrue(failSession.shouldAllowHttpReflexSync(), "fresh session starts with HTTP reflex sync allowed");
            failSession.handleIncomingLine("{\"type\":\"config_apply\",\"device\":\"android-v2314\",\"block\":\"kws\","
                    + "\"block_generation\":1,\"data_epoch\":1,\"operation_id\":\"op-bad\","
                    + "\"values\":{\"keywords\":\"bad_unk_tok @无效词\",\"retreat_words\":[\"退下\"]}}");
            assertTrue(!failSession.isNorthboundKwsSynced(),
                    "failed config_apply(kws) must roll back northboundKwsSynced to previous false");
            assertTrue(failSession.shouldAllowHttpReflexSync(),
                    "failed config_apply(kws) must restore shouldAllowHttpReflexSync() == true");

            // 4. Legacy kws_deploy also fences northboundKwsSynced BEFORE reload barrier and rolls back on failure
            NorthboundSession deploySession = new NorthboundSession(
                    "android-v2314", "Phone", NorthboundSession.GATEWAY_VERSION, "boot-v2314-dep", tx,
                    new NorthboundSession.BaseListener());
            deploySession.attachLocalKwsEngine(engine);
            deploySession.handleIncomingLine("{\"type\":\"hello_ack\",\"protocol\":0,\"capabilities\":[],\"generation\":1}");
            engine.configureReloadBarrierForTest(true, 2500L);
            final long seqBeforeDeploy = engine.getRequestedReloadSeqForTest();
            final AtomicReference<Throwable> depErr = new AtomicReference<Throwable>(null);
            Thread deployThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        deploySession.handleIncomingLine("{\"type\":\"kws_deploy\",\"device\":\"android-v2314\","
                                + "\"keywords\":" + lx06KwJson + ",\"retreat_words\":[\"退下\"]}");
                    } catch (Throwable t) {
                        depErr.set(t);
                    }
                }
            }, "test-kws-deploy-race");
            deployThread.start();
            waitDeadline = System.currentTimeMillis() + 1500L;
            while (engine.getRequestedReloadSeqForTest() <= seqBeforeDeploy && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5L);
            }
            assertTrue(deploySession.isNorthboundKwsSynced() && !deploySession.shouldAllowHttpReflexSync(),
                    "kws_deploy must fence northboundKwsSynced=true BEFORE deployKeywords() returns");
            // Fail the reload to verify rollback on kws_deploy as well!
            engine.notifyReloadOutcomeForTest(seqBeforeDeploy + 1L, false, "simulated_reload_fail");
            deployThread.join(1500L);
            assertTrue(depErr.get() == null, "kws_deploy must not throw: " + depErr.get());
            assertTrue(!deploySession.isNorthboundKwsSynced() && deploySession.shouldAllowHttpReflexSync(),
                    "failed kws_deploy must roll back northboundKwsSynced to false");
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        f.delete();
    }

    private static void testGatewayVersion2315() {
        assertEquals("2.3.15", NorthboundSession.GATEWAY_VERSION, "GATEWAY_VERSION must be 2.3.15");
        assertTrue(NorthboundSession.GATEWAY_NAME.contains("2.3.15"), "GATEWAY_NAME must include 2.3.15");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("V2312ScanAndMediaBufferTest FAILED: " + msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("V2312ScanAndMediaBufferTest FAILED: " + msg
                    + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
