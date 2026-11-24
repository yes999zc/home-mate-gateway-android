package homes.mymate.gateway;

import android.content.Context;

/**
 * Pluggable Hardware Adapter abstraction so one universal APK supports standard Android
 * phones/tablets alongside specialized smart speakers (e.g., Phicomm R1).
 */
public interface HardwareAdapter {

    String getAdapterName();

    boolean isSpecializedHardwareDetected();

    void attach(Context context, R1HardwareHook.HardwareEventListener listener);

    void syncAssistantLedState(String assistantState, boolean muted, boolean connected);

    void detach();

    final class Factory {
        private Factory() {
        }

        public static HardwareAdapter createAndAttach(
                Context context,
                boolean allowVendorHooks,
                R1HardwareHook.HardwareEventListener listener) {
            if (allowVendorHooks) {
                PhicommR1Adapter r1 = new PhicommR1Adapter();
                r1.attach(context, listener);
                if (r1.isSpecializedHardwareDetected()) {
                    return r1;
                }
            }
            GenericAndroidAdapter generic = new GenericAndroidAdapter();
            generic.attach(context, listener);
            return generic;
        }
    }

    final class GenericAndroidAdapter implements HardwareAdapter {
        @Override
        public String getAdapterName() {
            return "Generic-Android";
        }

        @Override
        public boolean isSpecializedHardwareDetected() {
            return false;
        }

        @Override
        public void attach(Context context, R1HardwareHook.HardwareEventListener listener) {
        }

        @Override
        public void syncAssistantLedState(String assistantState, boolean muted, boolean connected) {
        }

        @Override
        public void detach() {
        }
    }

    final class PhicommR1Adapter implements HardwareAdapter {
        private R1HardwareHook hook;

        @Override
        public String getAdapterName() {
            return "Phicomm-R1 (probe-only)";
        }

        @Override
        public boolean isSpecializedHardwareDetected() {
            return hook != null && hook.isPhicommR1HardwareAvailable();
        }

        @Override
        public void attach(Context context, R1HardwareHook.HardwareEventListener listener) {
            this.hook = new R1HardwareHook(context, listener);
            this.hook.probeAndAttach();
        }

        @Override
        public void syncAssistantLedState(String assistantState, boolean muted, boolean connected) {
            // Probe-only until vendor LED IPC reflection is wired
        }

        @Override
        public void detach() {
            this.hook = null;
        }
    }
}
