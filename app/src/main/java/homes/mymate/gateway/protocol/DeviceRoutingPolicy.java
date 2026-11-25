package homes.mymate.gateway.protocol;

/**
 * Pure device isolation & utterance/control routing policy shared by {@code GatewayService},
 * {@code MainActivity}, and JVM regression tests (MG-P0-01 & MG-P0-02).
 *
 * <p>Invariants:
 * <ul>
 *   <li>A Voice Gateway profile ({@code manageOnly == false}) MUST NEVER fall back to a
 *       sibling speaker device (e.g. {@code dot2-d}) on transient {@code :8932} disconnect —
 *       for polling, mode PUTs, stop/retreat, volume read/write, or MediaSession controls.</li>
 *   <li>A Voice Gateway profile MUST NEVER silently fall back to unbound HTTP
 *       {@code POST /api/v0/chat} while disconnected.</li>
 *   <li>Sibling fallback ({@code snap.devices[0]} / {@code allowSiblingMediaFallback}) and
 *       Manage-Only HTTP chat/remote stop/remote volume are enabled ONLY when the active
 *       profile is explicitly configured as Manage-Only ({@code manageOnly == true}).</li>
 *   <li>Switching active Core from an offline, loading, or auth-gated room summary card
 *       returns {@code null} for {@code manageOnly} so saved Voice Gateway profiles are
 *       never silently overwritten to {@code manageOnly = true}.</li>
 * </ul>
 */
public final class DeviceRoutingPolicy {

    public enum UtteranceRoute {
        NORTHBOUND_SESSION,
        MANAGE_ONLY_MEDIA_CONTROL,
        MANAGE_ONLY_HTTP_CHAT,
        REJECT_VOICE_DISCONNECTED
    }

    public enum StopRoute {
        LOCAL_RETREAT_NORTHBOUND,
        MANAGE_ONLY_REMOTE_STOP,
        LOCAL_ONLY_DISCONNECTED
    }

    private DeviceRoutingPolicy() {
    }

    /**
     * Sibling media/conversation fallback is permitted ONLY in explicit Manage-Only mode.
     * Transient {@code :8932} disconnects on a Voice Gateway profile MUST return {@code false}.
     */
    public static boolean allowSiblingMediaFallback(boolean manageOnly) {
        return manageOnly;
    }

    /**
     * Resolves the target device ID for HTTP polling, mode PUTs, and remote media actions.
     * In Voice Gateway mode ({@code !manageOnly}), ALWAYS returns {@code ownDeviceId}, even
     * when {@code :8932} is transiently disconnected.
     */
    public static String resolveEffectiveDeviceId(
            String ownDeviceId,
            boolean manageOnly,
            CoreApiClient.CoreSnapshot snapshot) {
        if (manageOnly) {
            String remote = resolvePrimaryRemoteDeviceId(true, snapshot);
            if (!remote.isEmpty()) {
                return remote;
            }
        }
        return ownDeviceId;
    }

    /**
     * Resolves the primary speaker device ID on the active Core for Manage-Only remote actions
     * (stop, volume). Returns {@code ""} whenever {@code manageOnly == false} so Voice Gateway
     * profiles can NEVER target a sibling speaker.
     */
    public static String resolvePrimaryRemoteDeviceId(
            boolean manageOnly,
            CoreApiClient.CoreSnapshot snapshot) {
        if (!manageOnly || snapshot == null || snapshot.devices == null || snapshot.devices.isEmpty()) {
            return "";
        }
        for (CoreApiClient.DeviceInfo d : snapshot.devices) {
            if (d != null && d.connected && d.id != null && !d.id.isEmpty()) {
                return d.id;
            }
        }
        CoreApiClient.DeviceInfo first = snapshot.devices.get(0);
        return (first != null && first.id != null) ? first.id : "";
    }

    /**
     * Determines how an outbound user text utterance or MediaSession command must be routed.
     */
    public static UtteranceRoute resolveUtteranceRoute(
            boolean manageOnly,
            boolean northboundConnected,
            boolean isDirectMediaAction) {
        if (!manageOnly) {
            return northboundConnected
                    ? UtteranceRoute.NORTHBOUND_SESSION
                    : UtteranceRoute.REJECT_VOICE_DISCONNECTED;
        }
        return isDirectMediaAction
                ? UtteranceRoute.MANAGE_ONLY_MEDIA_CONTROL
                : UtteranceRoute.MANAGE_ONLY_HTTP_CHAT;
    }

    /**
     * Determines how the UI Stop button / Orb long-press must act on the active Core.
     */
    public static StopRoute resolveActiveCoreStopRoute(boolean manageOnly, boolean northboundConnected) {
        if (manageOnly) {
            return StopRoute.MANAGE_ONLY_REMOTE_STOP;
        }
        return northboundConnected
                ? StopRoute.LOCAL_RETREAT_NORTHBOUND
                : StopRoute.LOCAL_ONLY_DISCONNECTED;
    }

    /**
     * Whether the active-Core volume slider reads/writes a remote speaker device via HTTP
     * instead of local Android hardware volume. Strictly {@code true} ONLY in Manage-Only mode.
     */
    public static boolean shouldUseRemoteVolumeControl(boolean manageOnly) {
        return manageOnly;
    }

    /**
     * Resolves the remote speaker's reported volume for UI display in Manage-Only mode.
     * Always returns {@code null} for Voice Gateway profiles ({@code !manageOnly}) so the UI
     * never displays a sibling speaker's volume on transient {@code :8932} disconnect.
     */
    public static Double resolveRemoteDisplayVolume(
            boolean manageOnly,
            CoreApiClient.CoreSnapshot snapshot) {
        if (!manageOnly || snapshot == null || snapshot.devices == null) {
            return null;
        }
        for (CoreApiClient.DeviceInfo d : snapshot.devices) {
            if (d != null && d.connected && d.volume != null) {
                return d.volume;
            }
        }
        for (CoreApiClient.DeviceInfo d : snapshot.devices) {
            if (d != null && d.volume != null) {
                return d.volume;
            }
        }
        return null;
    }

    public static final float DOT2_DEFAULT_KWS_THRESHOLD = 0.32f;
    public static final float DOT2_DEFAULT_KWS_SCORE = 2.50f;
    public static final float MOBILE_CALIBRATED_KWS_THRESHOLD = 0.22f;
    public static final float MOBILE_CALIBRATED_KWS_SCORE = 3.00f;

    /**
     * Returns {@code true} when a KWS sensitivity pair matches Core's generic Dot2 7-mic default
     * ({@code threshold ~0.32, score ~2.50}), which degrades mid-playback wake/barge-in recall
     * on single/dual-mic Android phones.
     */
    public static boolean isDot2DefaultKwsSensitivity(double threshold, double score) {
        return Math.abs(threshold - DOT2_DEFAULT_KWS_THRESHOLD) < 0.015
                && Math.abs(score - DOT2_DEFAULT_KWS_SCORE) < 0.05;
    }

    /**
     * One-time v2.3.9 migration helper: restores KWS sensitivity clobbered to Dot2's generic
     * {@code 0.32 / 2.50} back to mobile-calibrated {@code 0.22 / 3.00}, while preserving any
     * deliberate custom user or per-device tuning.
     */
    public static float[] migrateClobberedDot2KwsSensitivity(float threshold, float score) {
        if (isDot2DefaultKwsSensitivity(threshold, score)) {
            return new float[]{MOBILE_CALIBRATED_KWS_THRESHOLD, MOBILE_CALIBRATED_KWS_SCORE};
        }
        return new float[]{threshold, score};
    }

    /**
     * Returns whether raw PCM uplink should stay open during active playback for mid-playback
     * barge-in. Strictly {@code true} ONLY in {@code full} duplex: in {@code half} duplex,
     * {@code hm-core} defaults {@code barge_in=true} while suppressing direct near-end barge-in
     * (relying on local KWS keywords), so half-duplex uplink MUST remain gated during playback.
     */
    public static boolean shouldAllowBargeInUplink(String duplexMode, boolean anyPlayback) {
        return anyPlayback && "full".equalsIgnoreCase(duplexMode);
    }

    /**
     * Returns whether {@code AcousticGuard} near-end energy barge-in detection should fire.
     * Enabled strictly in {@code full} duplex during active playback when Core's {@code barge_in}
     * flag is enabled; half-duplex interrupts via local KWS keywords instead.
     */
    public static boolean shouldAllowAcousticBargeIn(
            String duplexMode, boolean coreBargeInEnabled, boolean anyPlayback) {
        return anyPlayback && "full".equalsIgnoreCase(duplexMode) && coreBargeInEnabled;
    }

    /**
     * Resolves the pinned {@code core_id} for a saved server profile after live token verification:
     * adopts a non-empty {@code verifiedCoreId} returned by an authenticated HTTP 200 response,
     * or preserves {@code existingCoreId} otherwise.
     */
    public static String resolvePinnedCoreId(String existingCoreId, String verifiedCoreId) {
        if (verifiedCoreId != null && !verifiedCoreId.trim().isEmpty()) {
            return verifiedCoreId.trim();
        }
        return existingCoreId != null ? existingCoreId.trim() : "";
    }

    /**
     * Resolves the {@code manageOnly} override when tapping "Set Active" on a Whole-Home
     * room summary card. Returns {@code null} (preserving the saved profile's existing
     * {@code manageOnly} setting, or defaulting to {@code false} for new profiles) whenever
     * the card is offline, still loading, or auth-gated ({@code !online || authRequired}).
     */
    public static Boolean resolveManageOnlyForRoomSwitch(
            boolean online,
            boolean authRequired,
            boolean voiceGatewayOpen) {
        if (!online || authRequired) {
            return null;
        }
        return Boolean.valueOf(!voiceGatewayOpen);
    }
}
