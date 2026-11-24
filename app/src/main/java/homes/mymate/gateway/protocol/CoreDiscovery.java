package homes.mymate.gateway.protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java Whole-Home LAN Auto-Discovery Engine for Home Mate Core nodes (v2.3.0).
 *
 * v2.3.0 upgrades:
 * - Zero raw TCP :8932 probing: reads {@code voice_gateway_open}, {@code core_id}, and
 *   {@code lan_gateway_exempt} exclusively from UDP 8933 beacons and HTTP {@code /api/v0/auth}
 *   / {@code /api/v0/status}, eliminating {@code connection closed before hello} warnings on hm-core.
 * - Multi-IP / Tailscale deduplication by persistent {@code core_id}: collapses dual-homed LAN +
 *   Tailscale ({@code 100.64.0.0/10}) endpoints for the same Core into one logical node, choosing
 *   the lowest-RTT RFC1918 LAN IP as primary and preserving fallback addresses in {@code alternateHosts}.
 */
public final class CoreDiscovery {

    public static final int DISCOVERY_UDP_PORT = 8933;
    private static final byte[] UDP_DISCOVER_PAYLOAD =
            "{\"type\":\"home_mate_discover\"}".getBytes(StandardCharsets.UTF_8);

    public static final class DiscoveredCore {
        public final String host;
        public final int webPort;
        public final int gatewayPort;
        public final String assistantName;
        public final String version;
        public final long rttMs;
        public final boolean authRequired;
        public final boolean voiceGatewayOpen;
        public final String roomName;
        public final String coreId;
        public final boolean lanGatewayExempt;
        public final List<String> alternateHosts;

        public DiscoveredCore(
                String host,
                int webPort,
                int gatewayPort,
                String assistantName,
                String version,
                long rttMs,
                boolean authRequired,
                boolean voiceGatewayOpen,
                String roomName,
                String coreId,
                boolean lanGatewayExempt,
                List<String> alternateHosts) {
            this.host = host != null ? host.trim() : "";
            this.webPort = webPort > 0 ? webPort : 8930;
            this.gatewayPort = gatewayPort > 0 ? gatewayPort : 8932;
            this.assistantName = (assistantName != null && !assistantName.trim().isEmpty())
                    ? assistantName.trim() : "Home Mate";
            this.version = version != null ? version.trim() : "";
            this.rttMs = Math.max(1L, rttMs);
            this.authRequired = authRequired;
            this.voiceGatewayOpen = voiceGatewayOpen;
            this.roomName = roomName != null ? roomName.trim() : "";
            this.coreId = coreId != null ? coreId.trim() : "";
            this.lanGatewayExempt = lanGatewayExempt;
            List<String> alts = new ArrayList<String>();
            if (alternateHosts != null) {
                for (String alt : alternateHosts) {
                    if (alt != null) {
                        String clean = alt.trim();
                        if (!clean.isEmpty() && !clean.equalsIgnoreCase(this.host) && !alts.contains(clean)) {
                            alts.add(clean);
                        }
                    }
                }
            }
            this.alternateHosts = Collections.unmodifiableList(alts);
        }

        public DiscoveredCore(
                String host,
                int webPort,
                int gatewayPort,
                String assistantName,
                String version,
                long rttMs,
                boolean authRequired,
                boolean voiceGatewayOpen,
                String roomName) {
            this(host, webPort, gatewayPort, assistantName, version, rttMs,
                    authRequired, voiceGatewayOpen, roomName, "", false, null);
        }

        public DiscoveredCore(
                String host,
                int webPort,
                int gatewayPort,
                String assistantName,
                String version,
                long rttMs,
                boolean authRequired) {
            this(host, webPort, gatewayPort, assistantName, version, rttMs,
                    authRequired, !authRequired, "", "", false, null);
        }

        public DiscoveredCore(
                String host,
                int webPort,
                int gatewayPort,
                String assistantName,
                String version,
                long rttMs) {
            this(host, webPort, gatewayPort, assistantName, version, rttMs,
                    false, true, "", "", false, null);
        }

        public String displayLabel() {
            if (!roomName.isEmpty()) {
                return roomName;
            }
            String shortOctet = host;
            int dot = host != null ? host.lastIndexOf('.') : -1;
            if (dot > 0 && dot + 1 < host.length()) {
                shortOctet = "." + host.substring(dot + 1);
            }
            return assistantName + " (" + shortOctet + ")";
        }

        @Override
        public String toString() {
            String title = !roomName.isEmpty() ? (roomName + " · " + assistantName) : assistantName;
            String mode = voiceGatewayOpen ? "Voice+Manage" : "Manage";
            String authTag = lanGatewayExempt ? " · LAN-exempt" : (authRequired ? " · paired" : "");
            String altTag = !alternateHosts.isEmpty() ? (" +" + alternateHosts.size() + " route") : "";
            return title + " (" + host + " · " + mode + authTag + altTag + " · " + rttMs + "ms)";
        }
    }

    private CoreDiscovery() {
    }

    /**
     * Parses a UDP {@code home_mate_beacon} JSON packet emitted by hm-core.
     */
    public static DiscoveredCore parseUdpBeacon(String rawJson, String senderHost, long rttMs) {
        if (rawJson == null || !rawJson.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> obj = MiniJson.parseObject(rawJson);
            String type = MiniJson.getString(obj, "type", "");
            if (!"home_mate_beacon".equalsIgnoreCase(type)) {
                return null;
            }
            String host = MiniJson.getString(obj, "host", "");
            if (host.isEmpty()) {
                host = senderHost != null ? senderHost.trim() : "";
            }
            if (host.isEmpty()) {
                return null;
            }
            int webPort = MiniJson.getInt(obj, "web_port", 8930);
            int gwPort = MiniJson.getInt(obj, "gateway_port", 8932);
            String assistant = MiniJson.getString(obj, "assistant_name", "Home Mate");
            String room = MiniJson.getString(obj, "room_name", "");
            String version = MiniJson.getString(obj, "version", "");
            String coreId = MiniJson.getString(obj, "core_id", "");
            boolean authReq = MiniJson.getBoolean(obj, "auth_required", false);
            boolean lanExempt = MiniJson.getBoolean(obj, "lan_gateway_exempt", false);
            boolean voiceOpen = MiniJson.getBoolean(obj, "voice_gateway_open", false);
            return new DiscoveredCore(
                    host, webPort, gwPort, assistant, version, rttMs,
                    authReq, voiceOpen, room, coreId, lanExempt, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Sends a UDP discovery probe on port 8933 and collects {@code home_mate_beacon} replies
     * for {@code listenMs} milliseconds. Fails gracefully to empty list when UDP broadcast is blocked.
     */
    public static List<DiscoveredCore> discoverViaUdpBeacon(int listenMs) {
        Map<String, DiscoveredCore> byHost = new LinkedHashMap<String, DiscoveredCore>();
        int waitMs = Math.max(80, Math.min(1500, listenMs));
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(Math.min(120, waitMs));

            Set<String> targets = new LinkedHashSet<String>();
            targets.add("255.255.255.255");
            for (String prefix : detectRfc1918LanPrefixes()) {
                targets.add(prefix + ".255");
            }
            long startNs = System.nanoTime();
            for (String target : targets) {
                try {
                    InetAddress addr = InetAddress.getByName(target);
                    DatagramPacket probe = new DatagramPacket(
                            UDP_DISCOVER_PAYLOAD, UDP_DISCOVER_PAYLOAD.length, addr, DISCOVERY_UDP_PORT);
                    sock.send(probe);
                } catch (Throwable ignored) {
                }
            }

            byte[] buf = new byte[2048];
            long deadlineMs = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadlineMs) {
                try {
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    sock.receive(reply);
                    long rtt = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                    String json = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                    String senderIp = reply.getAddress() != null ? reply.getAddress().getHostAddress() : "";
                    DiscoveredCore core = parseUdpBeacon(json, senderIp, rtt);
                    if (core != null) {
                        byHost.put(core.host, core);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<DiscoveredCore>(byHost.values());
    }

    /**
     * Probes a single host over HTTP ONLY ({@code /api/v0/status} and {@code /api/v0/auth}).
     * Never opens a raw TCP socket to the voice gateway port (:8932).
     */
    public static DiscoveredCore probeHost(String host, int webPort, int defaultGatewayPort, int timeoutMs) {
        long startNs = System.nanoTime();
        try {
            URL url = new URL("http://" + host + ":" + webPort + "/api/v0/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            long rttMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

            if (code == 401) {
                // Paired Home Mate Core! Query auth-exempt /api/v0/auth for core_id, lan_gateway_exempt & voice_gateway_open
                conn.disconnect();
                URL authUrl = new URL("http://" + host + ":" + webPort + "/api/v0/auth");
                HttpURLConnection authConn = (HttpURLConnection) authUrl.openConnection(Proxy.NO_PROXY);
                authConn.setConnectTimeout(timeoutMs);
                authConn.setReadTimeout(timeoutMs);
                authConn.setRequestMethod("GET");
                try {
                    if (authConn.getResponseCode() == 200) {
                        String body = readBody(authConn);
                        if (body.contains("configured")) {
                            Map<String, Object> authObj = MiniJson.parseObject(body);
                            String coreId = MiniJson.getString(authObj, "core_id", "");
                            boolean lanExempt = MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
                            boolean voiceOpen = MiniJson.getBoolean(authObj, "voice_gateway_open", false);
                            int gwPort = MiniJson.getInt(authObj, "gateway_port", defaultGatewayPort);
                            String roomName = MiniJson.getString(authObj, "room_name", "");
                            String assistantName = MiniJson.getString(authObj, "assistant_name", "Home Mate");
                            return new DiscoveredCore(
                                    host, webPort, gwPort,
                                    assistantName, "", rttMs,
                                    true, voiceOpen, roomName, coreId, lanExempt, null);
                        }
                    }
                } finally {
                    authConn.disconnect();
                }
                return null;
            }
            if (code != 200) {
                conn.disconnect();
                return null;
            }

            String body = readBody(conn);
            conn.disconnect();
            if (body.isEmpty() || !body.trim().startsWith("{")) {
                return null;
            }
            Map<String, Object> obj = MiniJson.parseObject(body);
            String product = MiniJson.getString(obj, "product", "");
            String assistantName = MiniJson.getString(obj, "assistant_name", "");
            if (!"Home Mate".equalsIgnoreCase(product) && assistantName.isEmpty() && !obj.containsKey("gateway")) {
                return null;
            }
            String version = MiniJson.getString(obj, "version", "");
            String roomName = MiniJson.getString(obj, "room_name", "");
            String coreId = MiniJson.getString(obj, "core_id", "");
            Map<String, Object> authObj = MiniJson.getObject(obj, "auth");
            boolean paired = authObj != null && MiniJson.getBoolean(authObj, "configured", false);
            boolean lanExempt = authObj != null && MiniJson.getBoolean(authObj, "lan_gateway_exempt", false);
            int gwPort = resolveGatewayPort(host, obj, defaultGatewayPort, 0);
            boolean voiceOpen = inferVoiceGatewayOpenFromStatus(host, obj);
            // If paired AND lanExempt allowed /api/v0/status to return 200, authRequired is false for this LAN peer!
            boolean authRequiredForPeer = paired && !lanExempt;
            return new DiscoveredCore(
                    host,
                    webPort,
                    gwPort,
                    assistantName.isEmpty() ? "Home Mate" : assistantName,
                    version,
                    rttMs,
                    authRequiredForPeer,
                    voiceOpen,
                    roomName,
                    coreId,
                    lanExempt,
                    null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Infers whether a Core accepts LAN voice gateways on its Northbound TCP port using ONLY
     * {@code /api/v0/status} metadata (never opens a raw TCP probe socket).
     */
    public static boolean inferVoiceGatewayOpenFromStatus(String host, Map<String, Object> statusObj) {
        if (statusObj == null) {
            return false;
        }
        Map<String, Object> gw = MiniJson.getObject(statusObj, "gateway");
        if (gw == null) {
            return false;
        }
        if (gw.containsKey("voice_gateway_open")) {
            return MiniJson.getBoolean(gw, "voice_gateway_open", false);
        }
        String bindAddr = MiniJson.getString(gw, "bind_addr", "").trim();
        if (bindAddr.isEmpty()) {
            return false;
        }
        int colon = bindAddr.lastIndexOf(':');
        String bindHost = colon > 0 ? bindAddr.substring(0, colon).trim() : bindAddr;
        boolean bindIsLoopback = bindHost.startsWith("127.") || "localhost".equalsIgnoreCase(bindHost) || "[::1]".equals(bindHost);
        boolean targetIsLoopback = host != null && (host.startsWith("127.") || "localhost".equalsIgnoreCase(host));
        return !bindIsLoopback || targetIsLoopback;
    }

    /**
     * Resolves the externally reachable gateway port from {@code /api/v0/status}.
     * Never adopts a loopback {@code bind_addr} port (e.g. {@code 127.0.0.1:8931}) for a remote
     * LAN host and never issues raw TCP probes.
     */
    public static int resolveGatewayPort(String host, Map<String, Object> statusObj, int defaultGatewayPort, int tcpTimeoutMs) {
        int gwPort = defaultGatewayPort > 0 ? defaultGatewayPort : 8932;
        if (statusObj == null) {
            return gwPort;
        }
        Map<String, Object> gw = MiniJson.getObject(statusObj, "gateway");
        if (gw == null) {
            return gwPort;
        }
        int explicitPort = MiniJson.getInt(gw, "port", 0);
        String bindAddr = MiniJson.getString(gw, "bind_addr", "").trim();
        int colon = bindAddr.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= bindAddr.length()) {
            return explicitPort > 0 ? explicitPort : gwPort;
        }
        String bindHost = bindAddr.substring(0, colon).trim();
        boolean bindIsLoopback = bindHost.startsWith("127.") || "localhost".equalsIgnoreCase(bindHost) || "[::1]".equals(bindHost);
        boolean targetIsLoopback = host != null && (host.startsWith("127.") || "localhost".equalsIgnoreCase(host));
        if (bindIsLoopback && !targetIsLoopback) {
            return gwPort;
        }
        try {
            int parsed = Integer.parseInt(bindAddr.substring(colon + 1));
            if (parsed > 0 && parsed <= 65535) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        return gwPort;
    }

    /**
     * Deduplicates discovered Cores by persistent {@code core_id} (falling back to normalized host
     * for older Cores without {@code core_id}). When the same Core is reachable over both RFC1918 LAN
     * and Tailscale ({@code 100.64.0.0/10}), picks the lowest-RTT RFC1918 LAN IP as primary and keeps
     * secondary routes in {@code alternateHosts}.
     */
    public static List<DiscoveredCore> deduplicateByCoreId(List<DiscoveredCore> rawList) {
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<DiscoveredCore>> groups = new LinkedHashMap<String, List<DiscoveredCore>>();
        for (DiscoveredCore c : rawList) {
            if (c == null || c.host.isEmpty()) {
                continue;
            }
            String key = !c.coreId.isEmpty()
                    ? ("id:" + c.coreId.toLowerCase(java.util.Locale.US))
                    : ("host:" + c.host.toLowerCase(java.util.Locale.US) + ":" + c.webPort);
            List<DiscoveredCore> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<DiscoveredCore>();
                groups.put(key, bucket);
            }
            bucket.add(c);
        }

        List<DiscoveredCore> merged = new ArrayList<DiscoveredCore>(groups.size());
        for (List<DiscoveredCore> bucket : groups.values()) {
            Collections.sort(bucket, new Comparator<DiscoveredCore>() {
                @Override
                public int compare(DiscoveredCore a, DiscoveredCore b) {
                    int ra = hostRouteRank(a.host);
                    int rb = hostRouteRank(b.host);
                    if (ra != rb) {
                        return Integer.compare(ra, rb);
                    }
                    return Long.compare(a.rttMs, b.rttMs);
                }
            });
            DiscoveredCore primary = bucket.get(0);
            String assistant = primary.assistantName;
            String version = primary.version;
            String room = primary.roomName;
            String coreId = primary.coreId;
            boolean voiceOpen = primary.voiceGatewayOpen;
            boolean lanExempt = primary.lanGatewayExempt;
            boolean authRequired = primary.authRequired;
            long bestRtt = primary.rttMs;
            int gwPort = primary.gatewayPort;
            int webPort = primary.webPort;

            List<String> alts = new ArrayList<String>(primary.alternateHosts);
            for (int i = 1; i < bucket.size(); i++) {
                DiscoveredCore other = bucket.get(i);
                if (!other.host.equalsIgnoreCase(primary.host) && !alts.contains(other.host)) {
                    alts.add(other.host);
                }
                for (String oa : other.alternateHosts) {
                    if (!oa.equalsIgnoreCase(primary.host) && !alts.contains(oa)) {
                        alts.add(oa);
                    }
                }
                if (room.isEmpty() && !other.roomName.isEmpty()) room = other.roomName;
                if ("Home Mate".equals(assistant) && !"Home Mate".equals(other.assistantName)) assistant = other.assistantName;
                if (version.isEmpty() && !other.version.isEmpty()) version = other.version;
                if (coreId.isEmpty() && !other.coreId.isEmpty()) coreId = other.coreId;
                voiceOpen = voiceOpen || other.voiceGatewayOpen;
                lanExempt = lanExempt || other.lanGatewayExempt;
                // If any route to this Core succeeded without auth (e.g. LAN exemption), don't force 401
                authRequired = authRequired && other.authRequired;
                bestRtt = Math.min(bestRtt, other.rttMs);
            }
            if (lanExempt && isPrivateOrTailscaleIp(primary.host)) {
                authRequired = false;
            }
            merged.add(new DiscoveredCore(
                    primary.host, webPort, gwPort, assistant, version, bestRtt,
                    authRequired, voiceOpen, room, coreId, lanExempt, alts));
        }

        Collections.sort(merged, new Comparator<DiscoveredCore>() {
            @Override
            public int compare(DiscoveredCore a, DiscoveredCore b) {
                if (a.voiceGatewayOpen != b.voiceGatewayOpen) {
                    return a.voiceGatewayOpen ? -1 : 1;
                }
                if (a.authRequired != b.authRequired) {
                    return a.authRequired ? 1 : -1;
                }
                return Long.compare(a.rttMs, b.rttMs);
            }
        });
        return merged;
    }

    /** Route priority: 0 = RFC1918 LAN, 1 = Tailscale CGNAT (100.64.0.0/10), 2 = loopback/other. */
    public static int hostRouteRank(String host) {
        if (isRfc1918PrivateIp(host)) {
            return 0;
        }
        if (isTailscaleIp(host)) {
            return 1;
        }
        return 2;
    }

    public static boolean isPrivateOrTailscaleIp(String ip) {
        return isRfc1918PrivateIp(ip) || isTailscaleIp(ip);
    }

    public static boolean isTailscaleIp(String ip) {
        if (ip == null) {
            return false;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                return false;
            }
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isRfc1918PrivateIp(String ip) {
        if (ip == null) {
            return false;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                return false;
            }
            if (a == 10) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            return a == 172 && b >= 16 && b <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static List<DiscoveredCore> scanLan(
            String preferredHost,
            int webPort,
            int defaultGatewayPort,
            int timeoutMs) {
        return scanLan(preferredHost, null, webPort, defaultGatewayPort, timeoutMs);
    }

    public static List<DiscoveredCore> scanLan(
            String preferredHost,
            List<String> extraCandidateHosts,
            int webPort,
            int defaultGatewayPort,
            int timeoutMs) {
        List<DiscoveredCore> raw = new ArrayList<DiscoveredCore>();

        // 1. Instant UDP beacon discovery (for hm-core builds supporting UDP 8933 active beacons)
        raw.addAll(discoverViaUdpBeacon(240));

        // 2. Active RFC1918 subnet HTTP probe + saved/preferred candidate hosts (e.g. Tailscale)
        Set<String> candidates = new LinkedHashSet<String>();
        if (preferredHost != null && !preferredHost.trim().isEmpty()) {
            candidates.add(preferredHost.trim());
        }
        if (extraCandidateHosts != null) {
            for (String h : extraCandidateHosts) {
                if (h != null && !h.trim().isEmpty()) {
                    candidates.add(h.trim());
                }
            }
        }

        List<String> prefixes = detectRfc1918LanPrefixes();
        for (String prefix : prefixes) {
            for (int lastOctet = 1; lastOctet <= 254; lastOctet++) {
                candidates.add(prefix + "." + lastOctet);
            }
        }

        if (!candidates.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(36);
            List<Future<DiscoveredCore>> futures = new ArrayList<Future<DiscoveredCore>>(candidates.size());
            for (final String ip : candidates) {
                futures.add(pool.submit(new Callable<DiscoveredCore>() {
                    @Override
                    public DiscoveredCore call() {
                        return probeHost(ip, webPort, defaultGatewayPort, timeoutMs);
                    }
                }));
            }

            for (Future<DiscoveredCore> f : futures) {
                try {
                    DiscoveredCore c = f.get(timeoutMs + 900L, TimeUnit.MILLISECONDS);
                    if (c != null) {
                        raw.add(c);
                    }
                } catch (Throwable ignored) {
                }
            }
            pool.shutdownNow();
        }

        return deduplicateByCoreId(raw);
    }

    public static List<String> detectRfc1918LanPrefixes() {
        Set<String> prefixes = new LinkedHashSet<String>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                String name = iface.getName() != null ? iface.getName().toLowerCase() : "";
                // Skip Tailscale/VPN tunnels and cellular interfaces
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("tailscale")
                        || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null) {
                            int lastDot = ip.lastIndexOf('.');
                            if (lastDot > 0) {
                                prefixes.add(ip.substring(0, lastDot));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<String>(prefixes);
    }

    private static String readBody(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                if (sb.length() > 8192) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
