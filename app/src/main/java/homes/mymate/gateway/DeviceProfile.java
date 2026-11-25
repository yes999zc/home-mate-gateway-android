package homes.mymate.gateway;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Universal Android Device Form-Factor & Acoustic Preset Profile (v2.0).
 *
 * v2.0 upgrades:
 * - Uses {@link Configuration#screenWidthDp} / {@link Configuration#screenHeightDp} (fallback DisplayMetrics)
 *   for accurate multi-window & foldable geometry.
 * - Explicitly honors manual {@code PHONE} override (never forces dualPane when PHONE is selected).
 * - Exposes {@code isHeadless} so headless speakers skip screen keep-on and particle animation overhead.
 */
public final class DeviceProfile {

    public enum FormFactor {
        AUTO("auto", "自动识别", "Auto Detect"),
        PHONE("phone", "智能手机", "Smartphone"),
        FOLDABLE("foldable", "折叠屏 / 平板", "Foldable / Tablet"),
        SMART_DISPLAY("smart_display", "桌面带屏音箱", "Smart Display"),
        WALL_PANEL_86("wall_panel_86", "86 墙面中控屏", "86 Wall Panel"),
        HEADLESS_SPEAKER("headless_speaker", "无屏智能音箱", "Smart Speaker");

        public final String id;
        public final String labelZh;
        public final String labelEn;

        FormFactor(String id, String labelZh, String labelEn) {
            this.id = id;
            this.labelZh = labelZh;
            this.labelEn = labelEn;
        }

        public String getLabel(String lang) {
            return I18n.LANG_EN.equalsIgnoreCase(lang) ? labelEn : labelZh;
        }

        public static FormFactor fromId(String id) {
            if (id != null) {
                for (FormFactor f : values()) {
                    if (f.id.equalsIgnoreCase(id.trim())) {
                        return f;
                    }
                }
            }
            return AUTO;
        }
    }

    public final FormFactor effectiveFormFactor;
    public final boolean dualPane;
    public final boolean compactSquare;
    public final boolean isHeadless;
    public final int widthDp;
    public final int heightDp;
    public final float recommendedGain;
    public final float recommendedKwsThreshold;

    public DeviceProfile(
            FormFactor effectiveFormFactor,
            boolean dualPane,
            boolean compactSquare,
            boolean isHeadless,
            int widthDp,
            int heightDp,
            float recommendedGain,
            float recommendedKwsThreshold) {
        this.effectiveFormFactor = effectiveFormFactor;
        this.dualPane = dualPane;
        this.compactSquare = compactSquare;
        this.isHeadless = isHeadless;
        this.widthDp = widthDp;
        this.heightDp = heightDp;
        this.recommendedGain = recommendedGain;
        this.recommendedKwsThreshold = recommendedKwsThreshold;
    }

    public static DeviceProfile resolve(Context context, String configuredId, boolean r1Detected) {
        Configuration cfg = context.getResources().getConfiguration();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density > 0f ? dm.density : 1f;
        int wDp = cfg.screenWidthDp > 0 ? cfg.screenWidthDp : Math.round(dm.widthPixels / density);
        int hDp = cfg.screenHeightDp > 0 ? cfg.screenHeightDp : Math.round(dm.heightPixels / density);
        boolean isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || wDp > hDp;
        float aspect = Math.max(wDp, hDp) / (float) Math.max(1, Math.min(wDp, hDp));

        FormFactor selected = FormFactor.fromId(configuredId);
        FormFactor effective = selected;
        if (selected == FormFactor.AUTO) {
            if (r1Detected) {
                effective = FormFactor.HEADLESS_SPEAKER;
            } else if (Math.min(wDp, hDp) <= 440 && aspect < 1.25f) {
                effective = FormFactor.WALL_PANEL_86;
            } else if (wDp >= 600 && hDp >= 500) {
                effective = FormFactor.FOLDABLE;
            } else if (isLandscape && wDp >= 620) {
                effective = FormFactor.SMART_DISPLAY;
            } else {
                effective = FormFactor.PHONE;
            }
        }

        boolean dualPane;
        if (effective == FormFactor.PHONE || effective == FormFactor.WALL_PANEL_86 || effective == FormFactor.HEADLESS_SPEAKER) {
            dualPane = false;
        } else {
            dualPane = (wDp >= 600)
                    || (effective == FormFactor.SMART_DISPLAY && wDp >= 560)
                    || (effective == FormFactor.FOLDABLE && wDp >= 580);
        }
        boolean compactSquare = (effective == FormFactor.WALL_PANEL_86)
                || (selected == FormFactor.AUTO && Math.min(wDp, hDp) <= 440 && aspect < 1.25f);
        boolean headless = (effective == FormFactor.HEADLESS_SPEAKER);

        float gain = 1.0f;
        float kwsThr = 0.22f;
        if (effective == FormFactor.HEADLESS_SPEAKER || effective == FormFactor.SMART_DISPLAY) {
            gain = 1.0f;
            kwsThr = 0.22f;
        } else if (effective == FormFactor.WALL_PANEL_86) {
            gain = 1.2f;
            kwsThr = 0.24f;
        }

        return new DeviceProfile(effective, dualPane, compactSquare, headless, wDp, hDp, gain, kwsThr);
    }
}
