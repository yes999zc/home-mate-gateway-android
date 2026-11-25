package homes.mymate.gateway.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Atomic conversation transcript, session-ID, and {@link CoreApiClient.CoreSnapshot} state store
 * shared by {@code GatewayService} and JVM concurrency tests (P1-1).
 *
 * <p>All reads, epoch checks, and commits across {@code transcriptEpoch},
 * {@code lastRawServerTranscript}, {@code localVoiceTurns}, {@code localManageTranscripts},
 * {@code manageOnlySessionIds}, {@code lastHistoryDeviceId}, and {@code coreSnapshot} execute
 * strictly under {@code transcriptSyncLock} (never held across network I/O). Consequently, an
 * in-flight HTTP poll, snapshot refresh, mode refresh, history fetch, or Manage-Only SSE reply
 * that began before {@link #clearConversation(String, long)} can never resurrect pre-clear
 * messages or stale session IDs.
 */
public final class TranscriptStateStore {

    public static final class ManageSendTicket {
        public final boolean accepted;
        public final long transcriptEpoch;
        public final String prevSessionId;

        public ManageSendTicket(boolean accepted, long transcriptEpoch, String prevSessionId) {
            this.accepted = accepted;
            this.transcriptEpoch = transcriptEpoch;
            this.prevSessionId = prevSessionId;
        }
    }

    public static final class HistoryCommitResult {
        public final List<CoreApiClient.ChatEntry> merged;
        public final String currentSessionId;
        public final boolean fresh;

        public HistoryCommitResult(
                List<CoreApiClient.ChatEntry> merged,
                String currentSessionId,
                boolean fresh) {
            this.merged = merged != null ? merged : Collections.<CoreApiClient.ChatEntry>emptyList();
            this.currentSessionId = currentSessionId != null ? currentSessionId : "";
            this.fresh = fresh;
        }
    }

    private final Object transcriptSyncLock;
    private long coreSwitchEpoch = 0L;
    private long transcriptEpoch = 0L;
    private CoreApiClient.CoreSnapshot coreSnapshot = CoreApiClient.CoreSnapshot.offline("init");
    private List<CoreApiClient.ChatEntry> lastRawServerTranscript = Collections.emptyList();
    private String lastHistoryDeviceId = "";
    private final Map<String, String> manageOnlySessionIds = new HashMap<String, String>();
    private final Map<String, List<CoreApiClient.ChatEntry>> localManageTranscripts =
            new HashMap<String, List<CoreApiClient.ChatEntry>>();
    private final Map<String, List<CoreApiClient.ChatEntry>> localVoiceTurns =
            new HashMap<String, List<CoreApiClient.ChatEntry>>();

    public TranscriptStateStore(Object transcriptSyncLock) {
        this.transcriptSyncLock = transcriptSyncLock != null ? transcriptSyncLock : new Object();
    }

    public CoreApiClient.CoreSnapshot getSnapshot() {
        synchronized (transcriptSyncLock) {
            return coreSnapshot;
        }
    }

    public long getCoreSwitchEpoch() {
        synchronized (transcriptSyncLock) {
            return coreSwitchEpoch;
        }
    }

    public long getTranscriptEpoch() {
        synchronized (transcriptSyncLock) {
            return transcriptEpoch;
        }
    }

    public String getLastHistoryDeviceId() {
        synchronized (transcriptSyncLock) {
            return lastHistoryDeviceId;
        }
    }

    public String getManageOnlySessionId(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        synchronized (transcriptSyncLock) {
            return manageOnlySessionIds.get(host);
        }
    }

    public boolean isLastRawServerTranscriptEmpty() {
        synchronized (transcriptSyncLock) {
            return lastRawServerTranscript.isEmpty();
        }
    }

    public void resetForCoreSwitch() {
        synchronized (transcriptSyncLock) {
            coreSwitchEpoch++;
            transcriptEpoch++;
            coreSnapshot = CoreApiClient.CoreSnapshot.offline("switching");
            lastRawServerTranscript = Collections.emptyList();
            lastHistoryDeviceId = "";
        }
    }

    public void appendLocalVoiceTurn(
            String host,
            String role,
            String text,
            long expectedCoreEpoch,
            boolean manageOnly) {
        if (host == null || host.isEmpty() || text == null || text.trim().isEmpty()) {
            return;
        }
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch) {
                return;
            }
            appendToCacheLocked(localVoiceTurns, host, role, text.trim());
            coreSnapshot = buildMergedSnapshotLocked(host, manageOnly, coreSnapshot, lastRawServerTranscript);
        }
    }

    public boolean appendLocalManageChatEntry(
            String host,
            String role,
            String text,
            long expectedCoreEpoch,
            long expectedTranscriptEpoch,
            boolean manageOnly) {
        if (host == null || host.isEmpty() || text == null || text.trim().isEmpty()) {
            return false;
        }
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch) {
                return false;
            }
            if (expectedTranscriptEpoch >= 0L && transcriptEpoch != expectedTranscriptEpoch) {
                return false;
            }
            appendToCacheLocked(localManageTranscripts, host, role, text.trim());
            coreSnapshot = buildMergedSnapshotLocked(host, manageOnly, coreSnapshot, lastRawServerTranscript);
            return true;
        }
    }

    /**
     * Atomically records a Manage-Only user turn, reads the current {@code manageOnlySessionIds}
     * value, and captures {@code transcriptEpoch} BEFORE initiating the in-flight HTTP SSE call.
     */
    public ManageSendTicket beginManageOnlyChatTurn(
            String host,
            String userText,
            long expectedCoreEpoch,
            boolean manageOnly) {
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch) {
                return new ManageSendTicket(false, transcriptEpoch, null);
            }
            if (host != null && !host.isEmpty() && userText != null && !userText.trim().isEmpty()) {
                appendToCacheLocked(localManageTranscripts, host, "user", userText.trim());
                coreSnapshot = buildMergedSnapshotLocked(host, manageOnly, coreSnapshot, lastRawServerTranscript);
            }
            String prevSid = (host != null && !host.isEmpty()) ? manageOnlySessionIds.get(host) : null;
            return new ManageSendTicket(true, transcriptEpoch, prevSid);
        }
    }

    /**
     * Atomically commits an in-flight Manage-Only SSE reply ONLY if neither {@code coreSwitchEpoch}
     * nor {@code transcriptEpoch} changed while the HTTP SSE call was executing. If a conversation
     * Clear occurred in-flight, both {@code newSessionId} and {@code assistantText} are discarded.
     */
    public boolean commitManageOnlyChatReply(
            String host,
            long expectedCoreEpoch,
            long expectedTranscriptEpoch,
            boolean manageOnly,
            String newSessionId,
            String assistantText) {
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch || transcriptEpoch != expectedTranscriptEpoch) {
                return false;
            }
            if (host != null && !host.isEmpty()) {
                if (newSessionId != null && !newSessionId.isEmpty()) {
                    manageOnlySessionIds.put(host, newSessionId);
                }
                if (assistantText != null && !assistantText.trim().isEmpty()) {
                    appendToCacheLocked(localManageTranscripts, host, "assistant", assistantText.trim());
                    coreSnapshot = buildMergedSnapshotLocked(host, manageOnly, coreSnapshot, lastRawServerTranscript);
                }
            }
            return true;
        }
    }

    /**
     * Atomically commits a fetched {@link CoreApiClient.CoreSnapshot} from {@code runApiPollLoop},
     * {@code refreshCoreSnapshotNow}, or {@code applyDeviceModeAsync}. If {@code transcriptEpoch}
     * changed while the HTTP request was in-flight, the stale polled transcript is discarded and
     * replaced with the current post-clear {@code lastRawServerTranscript}.
     */
    public CoreApiClient.CoreSnapshot commitPolledSnapshot(
            String host,
            long expectedCoreEpoch,
            long expectedTranscriptEpoch,
            boolean manageOnly,
            boolean fullFetch,
            CoreApiClient.CoreSnapshot fetchedSnap) {
        if (fetchedSnap == null) {
            return null;
        }
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch) {
                return null;
            }
            CoreApiClient.CoreSnapshot prev = coreSnapshot;
            boolean transcriptFresh = (transcriptEpoch == expectedTranscriptEpoch);
            List<CoreApiClient.ChatEntry> rawServer = transcriptFresh
                    ? (fetchedSnap.transcript != null
                        ? fetchedSnap.transcript
                        : Collections.<CoreApiClient.ChatEntry>emptyList())
                    : lastRawServerTranscript;

            CoreApiClient.CoreSnapshot snap = fetchedSnap;
            if (!fullFetch && snap.reachable && !snap.authRequired && prev != null && prev.reachable) {
                if (!transcriptFresh) {
                    rawServer = lastRawServerTranscript;
                } else if (rawServer.isEmpty() && !lastRawServerTranscript.isEmpty()) {
                    rawServer = lastRawServerTranscript;
                } else {
                    lastRawServerTranscript = rawServer;
                }
                snap = new CoreApiClient.CoreSnapshot(
                        snap.reachable, snap.authRequired,
                        prev.assistantName, prev.buildId,
                        snap.duplexMode, snap.bargeIn, snap.wakeAckEnabled, snap.wakeArbitration,
                        snap.musicActive, snap.musicPaused, snap.musicTitle, snap.musicSource,
                        prev.devices, prev.fleetNodes, rawServer, snap.error,
                        snap.reflexTable != null ? snap.reflexTable : prev.reflexTable);
            } else if (transcriptFresh) {
                lastRawServerTranscript = rawServer;
            } else {
                snap = withTranscript(snap, rawServer);
            }

            coreSnapshot = buildMergedSnapshotLocked(host, manageOnly, snap, rawServer);
            return coreSnapshot;
        }
    }

    /**
     * Atomically commits a {@code fetchConversationHistory} response if both epochs remain fresh.
     */
    public HistoryCommitResult commitConversationHistory(
            String host,
            long expectedCoreEpoch,
            long expectedTranscriptEpoch,
            boolean manageOnly,
            String resolvedDeviceId,
            CoreApiClient.ConversationHistoryResult res) {
        synchronized (transcriptSyncLock) {
            boolean fresh = (coreSwitchEpoch == expectedCoreEpoch && transcriptEpoch == expectedTranscriptEpoch);
            if (!fresh) {
                return new HistoryCommitResult(Collections.<CoreApiClient.ChatEntry>emptyList(), "", false);
            }
            List<CoreApiClient.ChatEntry> serverMsgs = (res != null && res.messages != null)
                    ? res.messages
                    : Collections.<CoreApiClient.ChatEntry>emptyList();
            List<CoreApiClient.ChatEntry> merged = mergeLocalCacheForHostLocked(host, manageOnly, serverMsgs);
            if (res != null && res.ok) {
                lastHistoryDeviceId = resolvedDeviceId != null ? resolvedDeviceId : "";
                lastRawServerTranscript = serverMsgs;
                if (coreSnapshot != null) {
                    coreSnapshot = withTranscript(coreSnapshot, merged);
                }
            }
            return new HistoryCommitResult(merged, res != null ? res.currentSessionId : "", true);
        }
    }

    /**
     * Atomically advances {@code transcriptEpoch} and wipes all local/server transcript caches,
     * {@code manageOnlySessionIds}, and {@code coreSnapshot.transcript}.
     */
    public boolean clearConversation(String host, long expectedCoreEpoch) {
        synchronized (transcriptSyncLock) {
            if (coreSwitchEpoch != expectedCoreEpoch) {
                return false;
            }
            transcriptEpoch++;
            if (host != null && !host.isEmpty()) {
                localVoiceTurns.remove(host);
                localManageTranscripts.remove(host);
                manageOnlySessionIds.remove(host);
            }
            lastRawServerTranscript = Collections.emptyList();
            if (coreSnapshot != null) {
                coreSnapshot = withTranscript(coreSnapshot, Collections.<CoreApiClient.ChatEntry>emptyList());
            }
            return true;
        }
    }

    private static void appendToCacheLocked(
            Map<String, List<CoreApiClient.ChatEntry>> map,
            String host,
            String role,
            String text) {
        List<CoreApiClient.ChatEntry> list = map.get(host);
        if (list == null) {
            list = new ArrayList<CoreApiClient.ChatEntry>();
            map.put(host, list);
        }
        list.add(new CoreApiClient.ChatEntry(role, text, System.currentTimeMillis()));
        while (list.size() > 40) {
            list.remove(0);
        }
    }

    private List<CoreApiClient.ChatEntry> mergeLocalCacheForHostLocked(
            String host,
            boolean manageOnly,
            List<CoreApiClient.ChatEntry> rawServerList) {
        List<CoreApiClient.ChatEntry> base = rawServerList != null
                ? rawServerList
                : Collections.<CoreApiClient.ChatEntry>emptyList();
        if (host == null || host.isEmpty()) {
            return base;
        }
        Map<String, List<CoreApiClient.ChatEntry>> cacheMap = manageOnly ? localManageTranscripts : localVoiceTurns;
        List<CoreApiClient.ChatEntry> local = cacheMap.get(host);
        if (local == null || local.isEmpty()) {
            return base;
        }
        return CoreApiClient.mergeManageOnlyTranscript(base, new ArrayList<CoreApiClient.ChatEntry>(local));
    }

    private CoreApiClient.CoreSnapshot buildMergedSnapshotLocked(
            String host,
            boolean manageOnly,
            CoreApiClient.CoreSnapshot snap,
            List<CoreApiClient.ChatEntry> rawServerList) {
        if (snap == null || host == null || host.isEmpty()) {
            return snap;
        }
        Map<String, List<CoreApiClient.ChatEntry>> cacheMap = manageOnly ? localManageTranscripts : localVoiceTurns;
        List<CoreApiClient.ChatEntry> local = cacheMap.get(host);
        if (local == null || local.isEmpty()) {
            return snap;
        }
        List<CoreApiClient.ChatEntry> merged = mergeLocalCacheForHostLocked(host, manageOnly, rawServerList);
        return withTranscript(snap, merged);
    }

    private static CoreApiClient.CoreSnapshot withTranscript(
            CoreApiClient.CoreSnapshot snap,
            List<CoreApiClient.ChatEntry> transcript) {
        return new CoreApiClient.CoreSnapshot(
                snap.reachable, snap.authRequired,
                snap.assistantName, snap.buildId,
                snap.duplexMode, snap.bargeIn, snap.wakeAckEnabled, snap.wakeArbitration,
                snap.musicActive, snap.musicPaused, snap.musicTitle, snap.musicSource,
                snap.devices, snap.fleetNodes, transcript, snap.error, snap.reflexTable);
    }
}
