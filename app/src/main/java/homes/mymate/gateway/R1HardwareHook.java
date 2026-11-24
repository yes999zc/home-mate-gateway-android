package homes.mymate.gateway;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Optional Phicomm R1 hardware message bus probe (probe-only unless vendor IPC is wired).
 *
 * When this universal APK runs on a standard Android phone, tablet, 86 wall panel, or smart screen,
 * {@link #isPhicommR1HardwareAvailable()} returns {@code false} and this class is a zero-cost no-op.
 * When installed on a Phicomm R1 speaker, it probes Android's ServiceManager for {@code msgcenter}
 * and honestly reports probe-only status until vendor IPC subscription is active.
 */
public final class R1HardwareHook {

    private static final String TAG = "HomeMateR1Hook";
    public static final int MSG_TYPE_WAKEUP = 33554432;
    public static final int MSG_WHAT_WAKE_TRIGGERED = 4;

    public interface HardwareEventListener {
        void onHardwareWakeTriggered(String keyword);
        void onHardwareTopButtonShortPress();
    }

    private final Context context;
    private final HardwareEventListener listener;
    private volatile boolean r1Detected = false;

    public R1HardwareHook(Context context, HardwareEventListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean probeAndAttach() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "msgcenter");
            r1Detected = (binder != null);
            if (r1Detected) {
                Log.i(TAG, "Detected Phicomm R1 msgcenter binder (probe-only mode)");
            }
        } catch (Throwable ignored) {
            r1Detected = false;
        }
        return r1Detected;
    }

    public boolean isPhicommR1HardwareAvailable() {
        return r1Detected;
    }
}
