package homes.mymate.gateway;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-Screen Ambient / Bedside StandBy Clock Overlay (v2.0).
 *
 * v2.0 upgrades:
 * - Embeds {@link ParticleOrbView} in battery-friendly OLED ambient mode (120 particles, 30fps idle cap,
 *   10-minute ±6dp Lissajous burn-in shift).
 * - Caches minute string ({@code HH:mm}) so {@code Date}/{@code SimpleDateFormat} runs once per minute.
 * - Adapts typography & orb size for compact 4-inch 86 wall control panels.
 * - Exposes {@link #setPaused(boolean)} & {@link #applyTopInset(int)} for cutout/status-bar clearance.
 */
public final class StandByOverlayView extends FrameLayout {

    public interface Callback {
        void onExitStandBy();
        void onTapWake();
        void onLongPressRetreat();
        void onMediaNext();
        void onMediaTogglePause();
    }

    private final TextView tvClock;
    private final TextView tvDate;
    private final TextView tvState;
    private final TextView tvMusicTitle;
    private final LinearLayout musicBar;
    private final ParticleOrbView orbView;
    private final TextView tvExit;
    private final FrameLayout.LayoutParams exitLp;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmtZh = new SimpleDateFormat("M月d日 · EEEE", Locale.CHINA);
    private final SimpleDateFormat dateFmtEn = new SimpleDateFormat("EEE, MMM d", Locale.US);
    private long lastRenderedMinute = -1L;
    private String lastLang = "";

    public StandByOverlayView(Context context, String lang, boolean compactSquare, final Callback callback) {
        super(context);
        setBackgroundColor(Color.parseColor("#050506"));
        setClickable(true);

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int pad = UiTheme.dp(context, compactSquare ? 14 : 28);
        center.setPadding(pad, pad, pad, pad);

        tvClock = new TextView(context);
        tvClock.setTextColor(UiTheme.TEXT_PRIMARY);
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, compactSquare ? 48 : 76);
        tvClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tvClock.setFontFeatureSettings("tnum");
        tvClock.setGravity(Gravity.CENTER);
        center.addView(tvClock);

        tvDate = new TextView(context);
        tvDate.setTextColor(UiTheme.TEXT_SECONDARY);
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, compactSquare ? 13 : 16);
        tvDate.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.bottomMargin = UiTheme.dp(context, compactSquare ? 12 : 24);
        center.addView(tvDate, dateLp);

        orbView = new ParticleOrbView(context);
        orbView.setAmbientMode(true);
        orbView.setPaused(true);
        int orbSz = UiTheme.dp(context, compactSquare ? 116 : 164);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(orbSz, orbSz);
        orbLp.bottomMargin = UiTheme.dp(context, 12);
        orbView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) callback.onTapWake();
            }
        });
        orbView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (callback != null) callback.onLongPressRetreat();
                return true;
            }
        });
        center.addView(orbView, orbLp);

        tvState = new TextView(context);
        tvState.setTextColor(UiTheme.ACCENT_CYAN);
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvState.setGravity(Gravity.CENTER);
        center.addView(tvState);

        musicBar = new LinearLayout(context);
        musicBar.setOrientation(LinearLayout.HORIZONTAL);
        musicBar.setGravity(Gravity.CENTER_VERTICAL);
        musicBar.setBackground(UiTheme.pill(UiTheme.BG_SURFACE, UiTheme.BORDER_SUBTLE, 2));
        musicBar.setPadding(UiTheme.dp(context, 16), UiTheme.dp(context, 10), UiTheme.dp(context, 12), UiTheme.dp(context, 10));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = UiTheme.dp(context, compactSquare ? 12 : 20);

        tvMusicTitle = new TextView(context);
        tvMusicTitle.setTextColor(UiTheme.TEXT_PRIMARY);
        tvMusicTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvMusicTitle.setMaxLines(1);
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtLp.rightMargin = UiTheme.dp(context, 14);
        musicBar.addView(tvMusicTitle, mtLp);

        musicBar.addView(UiTheme.circleIconButton(context, Icons.Name.PAUSE, UiTheme.TEXT_PRIMARY, UiTheme.BG_ELEVATED, 44,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (callback != null) callback.onMediaTogglePause();
                    }
                }));
        View spacer = new View(context);
        musicBar.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(context, 8), 1));
        musicBar.addView(UiTheme.circleIconButton(context, Icons.Name.SKIP_NEXT, UiTheme.TEXT_PRIMARY, UiTheme.BG_ELEVATED, 44,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (callback != null) callback.onMediaNext();
                    }
                }));
        musicBar.setVisibility(View.GONE);
        center.addView(musicBar, mLp);

        addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvExit = new TextView(context);
        tvExit.setText(I18n.t(lang, "exit_standby"));
        tvExit.setTextColor(UiTheme.TEXT_PRIMARY);
        tvExit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvExit.setGravity(Gravity.CENTER);
        tvExit.setMinHeight(UiTheme.dp(context, 48));
        tvExit.setBackground(UiTheme.ripplePill(UiTheme.BG_SURFACE, UiTheme.BORDER_SUBTLE, 2));
        tvExit.setPadding(UiTheme.dp(context, 16), UiTheme.dp(context, 10), UiTheme.dp(context, 16), UiTheme.dp(context, 10));
        tvExit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) callback.onExitStandBy();
            }
        });
        exitLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        exitLp.topMargin = UiTheme.dp(context, 24);
        exitLp.rightMargin = UiTheme.dp(context, 20);
        addView(tvExit, exitLp);
    }

    public void applyTopInset(int topInsetPx) {
        exitLp.topMargin = Math.max(UiTheme.dp(getContext(), 20), topInsetPx + UiTheme.dp(getContext(), 12));
        tvExit.setLayoutParams(exitLp);
    }

    public void setPaused(boolean paused) {
        orbView.setPaused(paused);
    }

    public void setLiveAudioEnergy(double rmsDbfs, double bassDbfs, boolean isMusic) {
        orbView.setLiveAudioEnergy(rmsDbfs, bassDbfs, isMusic);
    }

    public void update(
            String lang,
            boolean connected,
            boolean muted,
            String assistantState,
            double rmsDbfs,
            String stateLabel,
            boolean musicActive,
            String musicTitle) {
        long curMinute = System.currentTimeMillis() / 60_000L;
        if (curMinute != lastRenderedMinute || !lang.equals(lastLang)) {
            lastRenderedMinute = curMinute;
            lastLang = lang;
            Date now = new Date();
            tvClock.setText(timeFmt.format(now));
            tvDate.setText(I18n.LANG_EN.equalsIgnoreCase(lang) ? dateFmtEn.format(now) : dateFmtZh.format(now));
            tvExit.setText(I18n.t(lang, "exit_standby"));
        }
        if (!stateLabel.equals(tvState.getText().toString())) {
            tvState.setText(stateLabel);
        }
        orbView.updateState(connected, muted, assistantState, musicActive, rmsDbfs);

        if (musicActive && musicTitle != null && !musicTitle.isEmpty()) {
            if (!musicTitle.equals(tvMusicTitle.getText().toString())) {
                tvMusicTitle.setText(musicTitle);
            }
            if (musicBar.getVisibility() != View.VISIBLE) {
                musicBar.setVisibility(View.VISIBLE);
            }
        } else if (musicBar.getVisibility() != View.GONE) {
            musicBar.setVisibility(View.GONE);
        }
    }
}
