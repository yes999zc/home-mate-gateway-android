package homes.mymate.gateway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import homes.mymate.gateway.protocol.CoreApiClient;
import homes.mymate.gateway.protocol.CoreDiscovery;
import homes.mymate.gateway.protocol.DeviceRoutingPolicy;

/**
 * Home Mate Android Universal Client v2.2.0 — Apple Design Language (Light / Dark / System Dual Theme)
 * & Whole-Home Multi-Core Manager + Voice Speaker Client.
 *
 * v2.2.0 upgrades:
 * - Authentic Apple HIG Dual Theme: Daytime Light (#F2F2F7 / #FFFFFF / #0071E3), Nighttime Dark
 *   (#000000 / #1C1C1E / #0A84FF), and Follow System, with dynamic status/nav bar icon polarity
 *   and Light-Mode adaptive SRC_OVER 3D Particle Nebula Orb.
 * - Whole-Home LAN Auto-Scan & Per-Room Fleet Management Cards: discovers open, paired (401), and
 *   Manage-Only household speaker Cores (plus UDP 8933 beacons) and renders direct per-room controls.
 * - Dual-Mode Online Operation: seamlessly supports both Voice+Manage (:8932 + :8930) and
 *   Manage-Only (:8930) Cores with a tappable top-bar Room/Core Switcher.
 */
public final class MainActivity extends Activity implements GatewayService.UiTelemetryListener {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int TAB_VOICE = 0;
    private static final int TAB_ROOMS = 1;
    private static final int TAB_CONSOLE = 2;
    private static final int TAB_SETTINGS = 3;

    private GatewayService gatewayService;
    private boolean serviceBound = false;
    private GatewayConfig config;
    private DeviceProfile deviceProfile;
    private UiTheme.Palette theme = UiTheme.DARK;
    private int activeTab = TAB_VOICE;
    private boolean standByActive = false;
    private boolean imeVisible = false;
    private boolean musicStageMode = false;
    private Dialog activeDialog;

    // Root shell
    private FrameLayout rootFrame;
    private LinearLayout shellContainer;
    private FrameLayout tabContentHost;
    private View bottomNavBar;
    private StandByOverlayView standByOverlay;

    // Top Header widgets (Apple Navigation Bar with tappable Room/Core switcher)
    private View connectionDot;
    private GradientDrawable connectionDotBg;
    private int lastDotColor = 0;
    private TextView tvHeaderTitle;
    private TextView tvHeaderSub;

    // Nav tabs
    private final LinearLayout[] navItems = new LinearLayout[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabels = new TextView[4];

    // Tab views
    private View voiceTabView;
    private ScrollView roomsTabView;
    private LinearLayout consoleTabView;
    private ScrollView settingsTabView;

    // Voice / Music Dual-Mode Home Stage widgets
    private LinearLayout onboardingCard;
    private LinearLayout authBannerCard;
    private LinearLayout orbStageContainer;
    private ParticleOrbView heroOrb;
    private TextView tvOrbState;
    private LinearLayout voiceActionRow;
    private ImageView btnMuteCircle;
    private boolean lastMuteRendered = false;

    // Music Immersion Transport Bar
    private LinearLayout musicTransportDock;
    private TextView tvMusicTitle;
    private TextView tvMusicSub;
    private ImageView btnMusicPlayPause;
    private ImageView btnMusicMute;
    private boolean lastMusicPlayPauseRenderedPlaying = true;
    private String lastPromptedConfirmationId = "";

    // Ultra-slim Dialogue Ticker & Capsule Composer
    private LinearLayout voiceFooterDock;
    private LinearLayout tickerContainer;
    private ScrollView tickerScroll;
    private EditText etComposer;

    // Rooms & Whole-Home Fleet widgets
    private TextView tvRoomsSectionTitle;
    private LinearLayout roomsHeaderActions;
    private LinearLayout wholeHomeRoomsContainer;
    private LinearLayout roomsActiveControlsSection;
    private LinearLayout roomsArbSection;
    private LinearLayout roomsDetailsBody;
    private ImageView ivRoomsDetailsChevron;
    private boolean roomsDetailsExpanded = false;
    private LinearLayout devicesListContainer;
    private TextView tvModeDuplexHalf;
    private TextView tvModeDuplexFull;
    private Switch swBargeIn;
    private Switch swWakeAck;
    private TextView tvArbOff;
    private TextView tvArbObserve;
    private TextView tvArbEnforce;
    private SeekBar roomsVolumeSlider;
    private TextView tvRoomsVolumePct;
    private TextView tvRoomsAcousticBar;
    private boolean updatingControlsFromSnapshot = false;
    private List<CoreApiClient.RoomNodeSummary> latestRoomSummaries = new ArrayList<CoreApiClient.RoomNodeSummary>();

    // Console widgets
    private TextView tvConsoleUrl;
    private FrameLayout webViewHost;
    private WebView webView;
    private String lastLoadedWebUrl = "";
    private String lastLoadedWebToken = "";

    // Settings widgets
    private LinearLayout profilesContainer;
    private boolean advancedSettingsExpanded = false;
    private TextView tvCalibrateStatus;
    private TextView tvGainValue;
    private SeekBar sbUplinkGain;
    private TextView tvKwsThrValue;
    private SeekBar sbKwsThr;
    private TextView tvKwsScoreValue;
    private SeekBar sbKwsScore;
    private TextView tvBatteryStatus;

    // Diff fingerprints
    private String lastTranscriptFingerprint = "";
    private String lastDevicesFingerprint = "";
    private List<CoreApiClient.ChatEntry> latestCleanTranscript = new ArrayList<CoreApiClient.ChatEntry>();

    // Coalesced single-Runnable UI refresh
    private final AtomicBoolean uiRefreshPending = new AtomicBoolean(false);
    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            uiRefreshPending.set(false);
            if (!isFinishing() && !isDestroyed()) {
                renderLiveTelemetryNow();
            }
        }
    };

    private final AudioEngine.EnergyListener liveEnergyListener = new AudioEngine.EnergyListener() {
        @Override
        public void onLiveAudioEnergy(double rmsDbfs, double bassDbfs, boolean isMusic) {
            ParticleOrbView orb = heroOrb;
            if (orb != null && !standByActive && activeTab == TAB_VOICE) {
                orb.setLiveAudioEnergy(rmsDbfs, bassDbfs, isMusic);
            }
            StandByOverlayView sb = standByOverlay;
            if (sb != null && standByActive) {
                sb.setLiveAudioEnergy(rmsDbfs, bassDbfs, isMusic);
            }
        }
    };

    // Queued LAN scan tap if user taps scan before GatewayService finishes binding (0=none, 1=dialog, 2=rooms)
    private static final int PENDING_LAN_SCAN_NONE = 0;
    private static final int PENDING_LAN_SCAN_DIALOG = 1;
    private static final int PENDING_LAN_SCAN_ROOMS = 2;
    private int pendingLanScanAction = PENDING_LAN_SCAN_NONE;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            GatewayService.LocalBinder lb = (GatewayService.LocalBinder) binder;
            gatewayService = lb.getService();
            serviceBound = true;
            gatewayService.setUiTelemetryListener(MainActivity.this);
            gatewayService.setLiveEnergyListener(liveEnergyListener);
            gatewayService.ensureCaptureRunningIfPermitted();
            scheduleUiRefresh();
            if (activeTab == TAB_ROOMS) {
                refreshWholeHomeRoomCards();
            }
            int queuedScan = pendingLanScanAction;
            pendingLanScanAction = PENDING_LAN_SCAN_NONE;
            if (queuedScan == PENDING_LAN_SCAN_DIALOG) {
                runLanScanWithDialog(false);
            } else if (queuedScan == PENDING_LAN_SCAN_ROOMS) {
                scanLanAndRefreshRooms(false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (gatewayService != null) {
                gatewayService.setUiTelemetryListener(null);
                gatewayService.setLiveEnergyListener(null);
            }
            gatewayService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new GatewayConfig(this);
        rebuildFullUi();
        requestRuntimePermissionsIfNeeded();
        startAndBindGatewayService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (gatewayService != null) {
            gatewayService.setUiTelemetryListener(this);
            gatewayService.setLiveEnergyListener(liveEnergyListener);
        }
        updateKeepScreenOnFlag();
        syncOrbAnimationStates();
        scheduleUiRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        if (gatewayService != null) {
            gatewayService.ensureCaptureRunningIfPermitted();
        }
        if (tvBatteryStatus != null) {
            tvBatteryStatus.setText(isIgnoringBatteryOptimizations()
                    ? tr("settings_battery_ok") : tr("settings_battery_recommended"));
        }
        syncOrbAnimationStates();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        dismissActiveDialog();
        if (heroOrb != null) {
            heroOrb.setPaused(true);
        }
        if (standByOverlay != null) {
            standByOverlay.setPaused(true);
        }
        if (gatewayService != null) {
            gatewayService.setUiTelemetryListener(null);
            gatewayService.setLiveEnergyListener(null);
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (standByActive) {
            setStandByVisible(false);
            return;
        }
        if (activeTab == TAB_CONSOLE && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (activeTab != TAB_VOICE) {
            selectTab(TAB_VOICE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuildFullUi();
        scheduleUiRefresh();
    }

    @Override
    protected void onDestroy() {
        dismissActiveDialog();
        if (serviceBound) {
            if (gatewayService != null) {
                gatewayService.setUiTelemetryListener(null);
                gatewayService.setLiveEnergyListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (webView != null) {
            try {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    private String lang() { return config != null ? config.getLanguage() : I18n.LANG_ZH; }
    private String tr(String key) { return I18n.t(lang(), key); }
    private int dp(int v) { return UiTheme.dp(this, v); }

    private void showTrackedDialog(Dialog dialog) {
        if (isFinishing() || isDestroyed()) return;
        dismissActiveDialog();
        activeDialog = dialog;
        dialog.show();
    }

    private void dismissActiveDialog() {
        if (activeDialog != null) {
            try {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
            activeDialog = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarTheme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(standByActive ? Color.BLACK : theme.bgRoot);
            getWindow().setNavigationBarColor(standByActive ? Color.BLACK : theme.navBg);
        }
        try {
            View decor = getWindow().getDecorView();
            if (standByActive) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                int flags = View.SYSTEM_UI_FLAG_VISIBLE;
                if (theme.isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                if (theme.isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateKeepScreenOnFlag() {
        boolean shouldKeepOn = standByActive
                || (deviceProfile != null && !deviceProfile.isHeadless
                && (deviceProfile.effectiveFormFactor == DeviceProfile.FormFactor.WALL_PANEL_86
                || deviceProfile.effectiveFormFactor == DeviceProfile.FormFactor.SMART_DISPLAY));
        if (shouldKeepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void syncOrbAnimationStates() {
        boolean headless = deviceProfile != null && deviceProfile.isHeadless;
        if (heroOrb != null) {
            heroOrb.setPaused(headless || standByActive || activeTab != TAB_VOICE);
        }
        if (standByOverlay != null) {
            standByOverlay.setPaused(headless || !standByActive);
        }
    }

    private void rebuildFullUi() {
        dismissActiveDialog();

        String savedDraft = "";
        int savedSel = 0;
        if (etComposer != null) {
            savedDraft = etComposer.getText().toString();
            savedSel = Math.max(0, Math.min(savedDraft.length(), etComposer.getSelectionStart()));
        }

        if (webView != null && webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }

        lastTranscriptFingerprint = "";
        lastDevicesFingerprint = "";
        lastDotColor = 0;
        lastMuteRendered = false;
        musicStageMode = false;

        theme = UiTheme.resolve(this, config.getThemeMode());
        applySystemBarTheme();

        boolean r1 = gatewayService != null && gatewayService.isR1HardwareDetected();
        deviceProfile = DeviceProfile.resolve(this, config.getDeviceProfileId(), r1);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(theme.bgRoot);

        shellContainer = new LinearLayout(this);
        shellContainer.setOrientation(deviceProfile.dualPane ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        shellContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bottomNavBar = null;
        if (deviceProfile.dualPane) {
            shellContainer.addView(buildSideNavRail());
            LinearLayout mainArea = new LinearLayout(this);
            mainArea.setOrientation(LinearLayout.VERTICAL);
            mainArea.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            mainArea.addView(buildTopHeader());
            tabContentHost = new FrameLayout(this);
            mainArea.addView(tabContentHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            shellContainer.addView(mainArea);
        } else {
            shellContainer.addView(buildTopHeader());
            tabContentHost = new FrameLayout(this);
            shellContainer.addView(tabContentHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            bottomNavBar = buildBottomNavBar();
            shellContainer.addView(bottomNavBar);
        }

        voiceTabView = buildHomeStageTab();
        roomsTabView = buildRoomsTab();
        consoleTabView = buildConsoleTab();
        settingsTabView = buildSettingsTab();

        tabContentHost.addView(voiceTabView);
        tabContentHost.addView(roomsTabView);
        tabContentHost.addView(consoleTabView);
        tabContentHost.addView(settingsTabView);

        rootFrame.addView(shellContainer);

        standByOverlay = new StandByOverlayView(this, lang(), deviceProfile.compactSquare, new StandByOverlayView.Callback() {
            @Override public void onExitStandBy() { setStandByVisible(false); }
            @Override public void onTapWake() {
                if (gatewayService != null) {
                    gatewayService.triggerManualWake(gatewayService.resolvePreferredWakeKeyword());
                }
            }
            @Override public void onLongPressRetreat() { if (gatewayService != null) gatewayService.triggerManualRetreat(null); }
            @Override public void onMediaNext() { if (gatewayService != null) gatewayService.triggerMediaCommand("下一首"); }
            @Override public void onMediaTogglePause() { triggerMusicPlayPauseToggle(); }
        });
        standByOverlay.setVisibility(standByActive ? View.VISIBLE : View.GONE);
        rootFrame.addView(standByOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootFrame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int top = insets.getSystemWindowInsetTop();
                    int bottom = insets.getSystemWindowInsetBottom();
                    int left = insets.getSystemWindowInsetLeft();
                    int right = insets.getSystemWindowInsetRight();
                    shellContainer.setPadding(left, top, right, bottom);
                    if (standByOverlay != null) {
                        standByOverlay.applyTopInset(top);
                    }
                    boolean nextIme = bottom > dp(120);
                    if (nextIme != imeVisible) {
                        imeVisible = nextIme;
                        applyImeLayoutAdaptation();
                    }
                    return insets;
                }
            });
        }

        if (etComposer != null && !savedDraft.isEmpty()) {
            etComposer.setText(savedDraft);
            etComposer.setSelection(savedSel);
        }

        if (webView != null) {
            webView.setBackgroundColor(theme.bgRoot);
        }

        setContentView(rootFrame);
        updateKeepScreenOnFlag();
        selectTab(activeTab);
        renderWholeHomeRoomCards(latestRoomSummaries);
    }

    private void applyImeLayoutAdaptation() {
        if (!deviceProfile.dualPane) {
            if (bottomNavBar != null) {
                bottomNavBar.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            }
            if (orbStageContainer != null && activeTab == TAB_VOICE) {
                orbStageContainer.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            }
        }
    }

    private void setStandByVisible(boolean visible) {
        standByActive = visible;
        if (standByOverlay != null) {
            standByOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        applySystemBarTheme();
        updateKeepScreenOnFlag();
        syncOrbAnimationStates();
        scheduleUiRefresh();
    }

    private String getActiveCoreDisplayName() {
        String activeHost = config.getCoreHost();
        if (activeHost.isEmpty()) {
            return "Home Mate";
        }
        for (GatewayConfig.ServerProfile p : config.getServerProfiles()) {
            if (p.matchesHost(activeHost) && p.name != null && !p.name.trim().isEmpty()) {
                return p.name.trim();
            }
        }
        return activeHost;
    }

    // =========================================================================
    // Apple HIG Navigation Header & Clean iOS Tab Bar
    // =========================================================================

    private View buildTopHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.bgRoot);
        bar.setPadding(dp(18), dp(10), dp(12), dp(8));

        // Tappable Room / Core Switcher Cluster
        LinearLayout switcherBtn = new LinearLayout(this);
        switcherBtn.setOrientation(LinearLayout.HORIZONTAL);
        switcherBtn.setGravity(Gravity.CENTER_VERTICAL);
        switcherBtn.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(12), 0));
        switcherBtn.setPadding(dp(4), dp(4), dp(8), dp(4));
        switcherBtn.setClickable(true);
        switcherBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showCoreSwitcherDialog(); }
        });

        connectionDot = new View(this);
        connectionDotBg = UiTheme.pill(theme.textMuted, 0, 0);
        connectionDot.setBackground(connectionDotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(10);
        switcherBtn.addView(connectionDot, dotLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);

        tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setTextColor(theme.textPrimary);
        tvHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvHeaderTitle.setMaxLines(1);
        tvHeaderTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvHeaderTitle.setText(getActiveCoreDisplayName());
        titleLine.addView(tvHeaderTitle);

        ImageView chevron = new ImageView(this);
        int chevSz = dp(15);
        chevron.setImageDrawable(Icons.of(Icons.Name.CHEVRON_DOWN, theme.textMuted, chevSz));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(chevSz, chevSz);
        clp.leftMargin = dp(4);
        titleLine.addView(chevron, clp);

        titleCol.addView(titleLine);

        tvHeaderSub = new TextView(this);
        tvHeaderSub.setTextColor(theme.textMuted);
        tvHeaderSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvHeaderSub.setMaxLines(1);
        tvHeaderSub.setEllipsize(TextUtils.TruncateAt.END);
        tvHeaderSub.setText(tr("status_connecting"));
        titleCol.addView(tvHeaderSub);

        switcherBtn.addView(titleCol);
        bar.addView(switcherBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Subtle Apple-style icon actions (History + StandBy)
        bar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.HISTORY, theme.accent, Color.TRANSPARENT, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showConversationHistoryDialog(); }
                }));

        bar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.CLOCK, theme.textSecondary, Color.TRANSPARENT, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { setStandByVisible(true); }
                }));

        return bar;
    }

    private View buildBottomNavBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(theme.navBg);
        wrap.addView(UiTheme.hairlineDivider(this, theme));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(8), dp(6), dp(8), dp(8));

        Icons.Name[] icons = new Icons.Name[]{Icons.Name.MIC, Icons.Name.ROOMS, Icons.Name.CONSOLE, Icons.Name.SETTINGS};
        String[] labels = new String[]{tr("tab_voice"), tr("tab_rooms"), tr("tab_console"), tr("tab_settings")};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(48));
            item.setPadding(dp(6), dp(5), dp(6), dp(5));
            item.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(12), 0));
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { selectTab(idx); }
            });

            ImageView iv = new ImageView(this);
            int sz = dp(21);
            iv.setImageDrawable(Icons.of(icons[i], theme.textMuted, sz));
            item.addView(iv, new LinearLayout.LayoutParams(sz, sz));

            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTextColor(theme.textMuted);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(3);
            item.addView(tv, tlp);

            navItems[i] = item;
            navIcons[i] = iv;
            navLabels[i] = tv;
            row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        wrap.addView(row);
        return wrap;
    }

    private View buildSideNavRail() {
        LinearLayout railWrap = new LinearLayout(this);
        railWrap.setOrientation(LinearLayout.HORIZONTAL);
        railWrap.setBackgroundColor(theme.navBg);

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(8), dp(20), dp(8), dp(20));
        rail.setLayoutParams(new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT));

        Icons.Name[] icons = new Icons.Name[]{Icons.Name.MIC, Icons.Name.ROOMS, Icons.Name.CONSOLE, Icons.Name.SETTINGS};
        String[] labels = new String[]{tr("tab_voice"), tr("tab_rooms"), tr("tab_console"), tr("tab_settings")};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(56));
            item.setPadding(dp(6), dp(10), dp(6), dp(10));
            item.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(12), 0));
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { selectTab(idx); }
            });

            ImageView iv = new ImageView(this);
            int sz = dp(21);
            iv.setImageDrawable(Icons.of(icons[i], theme.textMuted, sz));
            item.addView(iv, new LinearLayout.LayoutParams(sz, sz));

            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTextColor(theme.textMuted);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            item.addView(tv, tlp);

            navItems[i] = item;
            navIcons[i] = iv;
            navLabels[i] = tv;
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = dp(8);
            rail.addView(item, ilp);
        }
        railWrap.addView(rail);

        View divider = new View(this);
        divider.setBackgroundColor(theme.borderSubtle);
        railWrap.addView(divider, new LinearLayout.LayoutParams(UiTheme.hairlinePx(this), ViewGroup.LayoutParams.MATCH_PARENT));
        return railWrap;
    }

    private void selectTab(int tab) {
        activeTab = tab;
        voiceTabView.setVisibility(tab == TAB_VOICE ? View.VISIBLE : View.GONE);
        roomsTabView.setVisibility(tab == TAB_ROOMS ? View.VISIBLE : View.GONE);
        consoleTabView.setVisibility(tab == TAB_CONSOLE ? View.VISIBLE : View.GONE);
        settingsTabView.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);

        Icons.Name[] icons = new Icons.Name[]{Icons.Name.MIC, Icons.Name.ROOMS, Icons.Name.CONSOLE, Icons.Name.SETTINGS};
        for (int i = 0; i < 4; i++) {
            boolean selected = (i == tab);
            int fg = selected ? theme.accent : theme.textMuted;
            // Classic iOS tab bar: clean icon+label accent tint on phones, soft translucent pill on dual-pane rail
            navItems[i].setBackground(selected && deviceProfile.dualPane
                    ? UiTheme.rippleRoundedRect(theme, theme.accentSoft, 0, dp(12), 0)
                    : UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(12), 0));
            navIcons[i].setImageDrawable(Icons.of(icons[i], fg, dp(21)));
            navLabels[i].setTextColor(fg);
            navLabels[i].setTypeface(selected ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        }

        syncOrbAnimationStates();

        if (tab == TAB_CONSOLE) {
            ensureWebViewLoaded();
        }
        if (tab == TAB_ROOMS && gatewayService != null) {
            gatewayService.refreshCoreSnapshotNow();
            gatewayService.triggerAutoLanDiscoveryIfNeeded(false);
            refreshWholeHomeRoomCards();
        }
        if (tab == TAB_SETTINGS) {
            refreshProfilesList();
        }
    }

    // =========================================================================
    // Tab 1: Apple HomePod / Apple Music Home Stage
    // =========================================================================

    private View buildHomeStageTab() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(12));

        // First-launch onboarding card (shown only when no Core is configured yet)
        onboardingCard = UiTheme.insetGroupCard(this, theme, 16, 14);
        TextView tvOnbTitle = new TextView(this);
        tvOnbTitle.setText(tr("onboarding_title"));
        tvOnbTitle.setTextColor(theme.textPrimary);
        tvOnbTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvOnbTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        onboardingCard.addView(tvOnbTitle);

        TextView tvOnbDesc = new TextView(this);
        tvOnbDesc.setText(tr("onboarding_desc"));
        tvOnbDesc.setTextColor(theme.textSecondary);
        tvOnbDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams odLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        odLp.topMargin = dp(4);
        odLp.bottomMargin = dp(10);
        onboardingCard.addView(tvOnbDesc, odLp);

        LinearLayout onbActions = new LinearLayout(this);
        onbActions.setOrientation(LinearLayout.HORIZONTAL);
        onbActions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.WIFI, tr("settings_scan_lan"),
                theme.primaryBg, theme.primaryFg, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runLanScanWithDialog(); }
                }));
        onbActions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        onbActions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.SETTINGS, tr("settings_add_profile"),
                theme.bgElevated, theme.textPrimary, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showCustomProfileDialog(null); }
                }));
        onboardingCard.addView(onbActions);
        LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ocLp.bottomMargin = dp(8);
        onboardingCard.setVisibility(config.hasConfiguredCore() ? View.GONE : View.VISIBLE);
        root.addView(onboardingCard, ocLp);

        // Auth-required action banner (shown on 401 or :8932 hello_reject unauthorized)
        authBannerCard = UiTheme.insetGroupCard(this, theme, 16, 12);
        LinearLayout abRow = new LinearLayout(this);
        abRow.setOrientation(LinearLayout.HORIZONTAL);
        abRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout abCol = new LinearLayout(this);
        abCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvAbTitle = new TextView(this);
        tvAbTitle.setText(tr("auth_banner_title"));
        tvAbTitle.setTextColor(theme.accentWarm);
        tvAbTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvAbTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        abCol.addView(tvAbTitle);
        TextView tvAbDesc = new TextView(this);
        tvAbDesc.setText(tr("auth_banner_desc"));
        tvAbDesc.setTextColor(theme.textSecondary);
        tvAbDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        abCol.addView(tvAbDesc);
        abRow.addView(abCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        abRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        abRow.addView(UiTheme.iconTextPill(this, theme, Icons.Name.KEY, tr("auth_banner_btn"),
                theme.primaryBg, theme.primaryFg, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showActiveCoreTokenDialog(); }
                }));
        authBannerCard.addView(abRow);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.bottomMargin = dp(8);
        authBannerCard.setVisibility(View.GONE);
        root.addView(authBannerCard, abLp);

        orbStageContainer = new LinearLayout(this);
        orbStageContainer.setOrientation(LinearLayout.VERTICAL);
        orbStageContainer.setGravity(Gravity.CENTER);

        heroOrb = new ParticleOrbView(this);
        heroOrb.setLightTheme(theme.isLight);
        int initialOrbDp = deviceProfile.compactSquare ? 156 : (deviceProfile.dualPane ? 268 : 236);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(initialOrbDp), dp(initialOrbDp));
        heroOrb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gatewayService == null) return;
                if (gatewayService.isConnected()) {
                    gatewayService.triggerManualWake(gatewayService.resolvePreferredWakeKeyword());
                } else if (etComposer != null) {
                    etComposer.requestFocus();
                }
            }
        });
        heroOrb.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (gatewayService != null) {
                    triggerStopActiveCore();
                }
                return true;
            }
        });
        orbStageContainer.addView(heroOrb, orbLp);

        tvOrbState = new TextView(this);
        tvOrbState.setText(tr("state_idle"));
        tvOrbState.setTextColor(theme.textSecondary);
        tvOrbState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvOrbState.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(8);
        orbStageContainer.addView(tvOrbState, stLp);

        // Clean Apple floating circular controls (Mute · Wake · Stop)
        voiceActionRow = new LinearLayout(this);
        voiceActionRow.setOrientation(LinearLayout.HORIZONTAL);
        voiceActionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(20);

        btnMuteCircle = UiTheme.circleIconButton(this, theme, Icons.Name.MIC_OFF, theme.textSecondary,
                theme.bgSurface, theme.borderSubtle, 46,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService != null) gatewayService.toggleMute();
                    }
                });
        voiceActionRow.addView(btnMuteCircle);

        voiceActionRow.addView(new View(this), new LinearLayout.LayoutParams(dp(18), 1));

        ImageView wakeBtn = UiTheme.circleIconButton(this, theme, Icons.Name.MIC, theme.primaryFg,
                theme.primaryBg, 0, 54,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (heroOrb != null) heroOrb.triggerShockwave();
                        if (gatewayService != null && gatewayService.isConnected()) {
                            gatewayService.triggerManualWake(gatewayService.resolvePreferredWakeKeyword());
                        } else if (etComposer != null) {
                            etComposer.requestFocus();
                        }
                    }
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wakeBtn.setElevation(dp(3));
        }
        voiceActionRow.addView(wakeBtn);

        voiceActionRow.addView(new View(this), new LinearLayout.LayoutParams(dp(18), 1));

        voiceActionRow.addView(UiTheme.circleIconButton(this, theme, Icons.Name.STOP, theme.textSecondary,
                theme.bgSurface, theme.borderSubtle, 46,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { triggerStopActiveCore(); }
                }));

        orbStageContainer.addView(voiceActionRow, rowLp);

        root.addView(orbStageContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        musicTransportDock = buildMusicTransportDock();
        musicTransportDock.setVisibility(View.GONE);
        root.addView(musicTransportDock);

        voiceFooterDock = buildSlimDialogueFooter();
        root.addView(voiceFooterDock);

        return root;
    }

    private void triggerStopActiveCore() {
        if (gatewayService == null) return;
        boolean manageOnly = gatewayService.isManageOnlyMode();
        DeviceRoutingPolicy.StopRoute route = DeviceRoutingPolicy.resolveActiveCoreStopRoute(
                manageOnly, gatewayService.isConnected());
        if (route == DeviceRoutingPolicy.StopRoute.LOCAL_RETREAT_NORTHBOUND) {
            gatewayService.triggerManualRetreat(null);
        } else if (route == DeviceRoutingPolicy.StopRoute.MANAGE_ONLY_REMOTE_STOP) {
            String devId = DeviceRoutingPolicy.resolvePrimaryRemoteDeviceId(true, gatewayService.getCoreSnapshot());
            if (!devId.isEmpty()) {
                gatewayService.stopRemoteRoomAsync(config.getCoreHost(), config.getWebPort(), devId, null);
            }
        } else {
            // Voice Gateway transiently disconnected: flush any local audio only;
            // never target a sibling speaker or fall back to unbound /api/v0/chat.
            gatewayService.triggerManualRetreat(null);
            Toast.makeText(this, tr("voice_disconnected_reconnect"), Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout buildMusicTransportDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        dock.setPadding(dp(8), dp(8), dp(8), dp(12));

        tvMusicTitle = new TextView(this);
        tvMusicTitle.setTextColor(theme.textPrimary);
        tvMusicTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvMusicTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvMusicTitle.setMaxLines(1);
        tvMusicTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvMusicTitle.setGravity(Gravity.CENTER);
        dock.addView(tvMusicTitle);

        tvMusicSub = new TextView(this);
        tvMusicSub.setTextColor(theme.textMuted);
        tvMusicSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvMusicSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(20);
        dock.addView(tvMusicSub, subLp);

        // Apple Music-style sculpted capsule transport dock
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(UiTheme.roundedRect(theme.bgSurface, theme.borderSubtle, dp(32), UiTheme.hairlinePx(this)));
        bar.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (theme.isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bar.setElevation(dp(2));
        }

        bar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.SKIP_PREV, theme.textPrimary,
                Color.TRANSPARENT, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService != null) gatewayService.triggerMediaCommand("上一首");
                    }
                }));
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        btnMusicPlayPause = UiTheme.circleIconButton(this, theme, Icons.Name.PAUSE, theme.primaryFg,
                theme.primaryBg, 0, 50,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        triggerMusicPlayPauseToggle();
                    }
                });
        lastMusicPlayPauseRenderedPlaying = true;
        bar.addView(btnMusicPlayPause);
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        bar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.SKIP_NEXT, theme.textPrimary,
                Color.TRANSPARENT, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService != null) gatewayService.triggerMediaCommand("下一首");
                    }
                }));
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        bar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.STOP, theme.textSecondary,
                theme.bgElevated, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { triggerStopActiveCore(); }
                }));
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        btnMusicMute = UiTheme.circleIconButton(this, theme, Icons.Name.MIC_OFF, theme.textSecondary,
                theme.bgElevated, theme.borderSubtle, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService != null) gatewayService.toggleMute();
                    }
                });
        bar.addView(btnMusicMute);

        dock.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return dock;
    }

    private LinearLayout buildSlimDialogueFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);

        footer.addView(UiTheme.hairlineDivider(this, theme));

        // Ultra-slim scrollable human dialogue ticker (tap for full Apple Messages-style history)
        tickerScroll = new ScrollView(this);
        tickerScroll.setVerticalScrollBarEnabled(false);
        tickerScroll.setPadding(dp(2), dp(8), dp(2), dp(6));

        tickerContainer = new LinearLayout(this);
        tickerContainer.setOrientation(LinearLayout.VERTICAL);
        tickerContainer.setClickable(true);
        tickerContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showConversationHistoryDialog(); }
        });
        tickerScroll.addView(tickerContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int tickerHeightDp = deviceProfile.compactSquare ? 62 : 84;
        footer.addView(tickerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tickerHeightDp)));

        // Apple iMessage-style capsule text composer
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackground(UiTheme.pill(theme.bgSurface, theme.borderSubtle, UiTheme.hairlinePx(this)));
        composer.setPadding(dp(16), dp(3), dp(4), dp(3));
        if (theme.isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            composer.setElevation(dp(1));
        }
        LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        compLp.topMargin = dp(4);

        etComposer = new EditText(this);
        etComposer.setHint(tr("input_hint"));
        etComposer.setHintTextColor(theme.textMuted);
        etComposer.setTextColor(theme.textPrimary);
        etComposer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        etComposer.setBackgroundColor(Color.TRANSPARENT);
        etComposer.setSingleLine(true);
        etComposer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        etComposer.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendComposerText();
                    return true;
                }
                return false;
            }
        });
        composer.addView(etComposer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        composer.addView(UiTheme.circleIconButton(this, theme, Icons.Name.SEND, theme.primaryFg, theme.primaryBg, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { sendComposerText(); }
                }));

        footer.addView(composer, compLp);
        return footer;
    }

    private void triggerMusicPlayPauseToggle() {
        if (gatewayService == null) {
            return;
        }
        CoreApiClient.CoreSnapshot snap = gatewayService.getCoreSnapshot();
        boolean playingNow = gatewayService.isLocalMediaPlaying()
                || (snap != null && snap.musicActive);
        gatewayService.triggerMediaCommand(playingNow ? "暂停播放" : "继续播放");
    }

    private void updateMusicStagePresentation(
            boolean musicActive, boolean musicPaused, String musicTitle, String musicSource) {
        if (heroOrb == null || musicTransportDock == null || voiceFooterDock == null) {
            return;
        }
        boolean showMusicDock = musicActive || musicPaused;
        if (btnMusicPlayPause != null && musicActive != lastMusicPlayPauseRenderedPlaying) {
            lastMusicPlayPauseRenderedPlaying = musicActive;
            btnMusicPlayPause.setImageDrawable(Icons.of(
                    musicActive ? Icons.Name.PAUSE : Icons.Name.PLAY,
                    theme.primaryFg,
                    dp(20)));
        }
        if (showMusicDock) {
            String title = (musicTitle != null && !musicTitle.trim().isEmpty()) ? musicTitle.trim() : tr("state_music");
            setTextIfChanged(tvMusicTitle, title);
            String prefix = musicActive ? tr("now_playing") : tr("music_paused");
            String sub = (musicSource != null && !musicSource.trim().isEmpty())
                    ? prefix + " · " + musicSource.trim()
                    : prefix;
            setTextIfChanged(tvMusicSub, sub);
        }

        if (showMusicDock == musicStageMode) {
            return;
        }
        musicStageMode = showMusicDock;

        int targetOrbDp;
        if (showMusicDock) {
            targetOrbDp = deviceProfile.compactSquare ? 188 : (deviceProfile.dualPane ? 336 : 300);
        } else {
            targetOrbDp = deviceProfile.compactSquare ? 156 : (deviceProfile.dualPane ? 268 : 236);
        }
        ViewGroup.LayoutParams lp = heroOrb.getLayoutParams();
        if (lp != null) {
            lp.width = dp(targetOrbDp);
            lp.height = dp(targetOrbDp);
            heroOrb.setLayoutParams(lp);
        }

        if (voiceActionRow != null) {
            voiceActionRow.setVisibility(showMusicDock ? View.GONE : View.VISIBLE);
        }
        if (tvOrbState != null) {
            tvOrbState.setVisibility(showMusicDock ? View.GONE : View.VISIBLE);
        }
        musicTransportDock.setVisibility(showMusicDock ? View.VISIBLE : View.GONE);
        // Keep the compact text composer reachable during Music Stage (including Manage-Only
        // Orb tap focus), collapsing only the dialogue ticker to preserve visual breathing room.
        if (tickerScroll != null) {
            tickerScroll.setVisibility(showMusicDock ? View.GONE : View.VISIBLE);
        }
        voiceFooterDock.setVisibility(View.VISIBLE);
    }

    private String formatChatTimestamp(long tsMs, boolean includeDateIfOlder) {
        if (tsMs <= 0L) {
            return "";
        }
        Date d = new Date(tsMs);
        if (includeDateIfOlder) {
            Calendar nowCal = Calendar.getInstance();
            Calendar msgCal = Calendar.getInstance();
            msgCal.setTimeInMillis(tsMs);
            boolean sameDay = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)
                    && nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR);
            if (!sameDay) {
                return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(d);
            }
        }
        return new SimpleDateFormat("HH:mm", Locale.US).format(d);
    }

    private void showConversationHistoryDialog() {
        final Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setCanceledOnTouchOutside(true);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int sheetWidthPx = Math.min(dp(520), Math.round(dm.widthPixels * 0.92f));
        final int sheetHeightPx = Math.round(dm.heightPixels * 0.82f);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundedRect(
                theme.bgRoot, theme.borderSubtle, dp(22), UiTheme.hairlinePx(this)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setClipToOutline(true);
            card.setElevation(dp(8));
        }

        // Sheet Header Bar (bgSurface + hairline bottom divider)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setColor(theme.bgSurface);
        float topR = dp(22);
        headerBg.setCornerRadii(new float[]{topR, topR, topR, topR, 0f, 0f, 0f, 0f});
        header.setBackground(headerBg);
        header.setPadding(dp(20), dp(14), dp(12), dp(14));

        LinearLayout headerTitles = new LinearLayout(this);
        headerTitles.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(tr("history_title"));
        tvTitle.setTextColor(theme.textPrimary);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerTitles.addView(tvTitle);

        final String coreName = getActiveCoreDisplayName();
        final TextView tvSub = new TextView(this);
        tvSub.setText(String.format(Locale.US, tr("history_refreshing"), coreName));
        tvSub.setTextColor(theme.textMuted);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSub.setFontFeatureSettings("tnum");
        tvSub.setMaxLines(1);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        headerTitles.addView(tvSub, subLp);

        header.addView(headerTitles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Runnable[] refreshHolder = new Runnable[1];
        final String[] currentSessionIdHolder = new String[]{""};
        final Dialog[] nestedConfirm = new Dialog[1];
        sheet.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (nestedConfirm[0] != null) {
                    try {
                        if (nestedConfirm[0].isShowing()) {
                            nestedConfirm[0].dismiss();
                        }
                    } catch (Throwable ignored) {
                    }
                    nestedConfirm[0] = null;
                }
            }
        });
        final List<CoreApiClient.ChatEntry> initial = latestCleanTranscript != null
                ? new ArrayList<CoreApiClient.ChatEntry>(latestCleanTranscript)
                : new ArrayList<CoreApiClient.ChatEntry>();
        final boolean[] clearInFlight = new boolean[]{false};
        final Runnable[] clearVisUpdater = new Runnable[1];
        final View btnClear = UiTheme.iconTextPill(
                this, theme, Icons.Name.TRASH, tr("history_clear_btn"),
                theme.destructiveSoft, theme.accentWarm, 0,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService == null || clearInFlight[0]) {
                            return;
                        }
                        final String sid = currentSessionIdHolder[0];
                        if (nestedConfirm[0] != null) {
                            try {
                                if (nestedConfirm[0].isShowing()) {
                                    nestedConfirm[0].dismiss();
                                }
                            } catch (Throwable ignored) {
                            }
                            nestedConfirm[0] = null;
                        }
                        AlertDialog confirmDlg = UiTheme.dialog(MainActivity.this, theme)
                                .setTitle(tr("history_clear_title"))
                                .setMessage(tr("history_clear_confirm"))
                                .setPositiveButton(tr("history_clear_btn"), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        if (gatewayService == null || clearInFlight[0]) return;
                                        clearInFlight[0] = true;
                                        v.setEnabled(false);
                                        v.setAlpha(0.45f);
                                        gatewayService.clearActiveConversationAsync(sid, true, new GatewayService.ActionResultCallback() {
                                            @Override
                                            public void onActionDone(final boolean ok, final boolean authRequired) {
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        clearInFlight[0] = false;
                                                        v.setEnabled(true);
                                                        v.setAlpha(1.0f);
                                                        if (isFinishing() || isDestroyed()) return;
                                                        boolean authNeeded = !ok && authRequired;
                                                        Toast.makeText(MainActivity.this,
                                                                ok ? tr("history_cleared")
                                                                        : (authNeeded ? tr("auth_action_required_toast") : tr("history_clear_failed")),
                                                                Toast.LENGTH_SHORT).show();
                                                        if (authNeeded) {
                                                            scheduleUiRefresh();
                                                        }
                                                        if (ok) {
                                                            currentSessionIdHolder[0] = "";
                                                            initial.clear();
                                                            latestCleanTranscript = new ArrayList<CoreApiClient.ChatEntry>();
                                                            renderSlimTickerIfChanged(latestCleanTranscript);
                                                            if (clearVisUpdater[0] != null) {
                                                                clearVisUpdater[0].run();
                                                            }
                                                            if (sheet.isShowing() && refreshHolder[0] != null) {
                                                                refreshHolder[0].run();
                                                            }
                                                        }
                                                    }
                                                });
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .create();
                        confirmDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialog) {
                                if (nestedConfirm[0] == dialog) {
                                    nestedConfirm[0] = null;
                                }
                            }
                        });
                        nestedConfirm[0] = confirmDlg;
                        confirmDlg.show();
                    }
                });
        btnClear.setVisibility(View.GONE);
        header.addView(btnClear);
        final View clearSpacer = new View(this);
        clearSpacer.setVisibility(View.GONE);
        header.addView(clearSpacer, new LinearLayout.LayoutParams(dp(6), 1));

        final ImageView btnRefresh = UiTheme.circleIconButton(
                this, theme, Icons.Name.REFRESH, theme.accent, theme.bgElevated, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (refreshHolder[0] != null) {
                            refreshHolder[0].run();
                        }
                    }
                });
        header.addView(btnRefresh);
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        header.addView(UiTheme.circleIconButton(
                this, theme, Icons.Name.CLOSE, theme.textSecondary, theme.bgElevated, 0, 44,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                    }
                }));

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(UiTheme.hairlineDivider(this, theme));

        // Scrollable Timeline Body
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(14), dp(16), dp(20));
        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int userMaxW = Math.max(dp(180), Math.round(sheetWidthPx * 0.78f));
        final int asstMaxW = Math.max(dp(200), Math.round(sheetWidthPx * 0.82f));

        final class HistoryRenderer {
            void render(List<CoreApiClient.ChatEntry> entries, boolean loading, boolean authRequired) {
                list.removeAllViews();
                if (loading) {
                    tvSub.setText(String.format(Locale.US, tr("history_refreshing"), coreName));
                } else {
                    tvSub.setText(String.format(Locale.US, tr("history_subtitle_fmt"), coreName, entries.size()));
                }

                if (entries.isEmpty()) {
                    LinearLayout emptyBox = new LinearLayout(MainActivity.this);
                    emptyBox.setOrientation(LinearLayout.VERTICAL);
                    emptyBox.setGravity(Gravity.CENTER);
                    emptyBox.setPadding(dp(24), dp(48), dp(24), dp(48));

                    FrameLayout badge = new FrameLayout(MainActivity.this);
                    badge.setBackground(UiTheme.pill(authRequired ? theme.destructiveSoft : theme.accentSoft, 0, 0));
                    ImageView iconView = new ImageView(MainActivity.this);
                    int isz = dp(24);
                    iconView.setImageDrawable(Icons.of(
                            authRequired ? Icons.Name.KEY : Icons.Name.HISTORY,
                            authRequired ? theme.accentWarm : theme.accent,
                            isz));
                    FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(isz, isz, Gravity.CENTER);
                    badge.addView(iconView, ilp);
                    emptyBox.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));

                    TextView emptyTitle = new TextView(MainActivity.this);
                    emptyTitle.setText(loading
                            ? tr("status_connecting")
                            : (authRequired ? tr("status_auth_required") : tr("history_empty")));
                    emptyTitle.setTextColor(theme.textPrimary);
                    emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    emptyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    emptyTitle.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    etLp.topMargin = dp(16);
                    emptyBox.addView(emptyTitle, etLp);

                    TextView emptySub = new TextView(MainActivity.this);
                    emptySub.setText(authRequired ? tr("history_auth_required") : tr("history_empty_sub"));
                    emptySub.setTextColor(theme.textSecondary);
                    emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    emptySub.setGravity(Gravity.CENTER);
                    emptySub.setMaxWidth(dp(260));
                    emptySub.setLineSpacing(dp(2), 1.0f);
                    LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    esLp.topMargin = dp(6);
                    esLp.bottomMargin = dp(18);
                    emptyBox.addView(emptySub, esLp);

                    if (!loading) {
                        if (authRequired) {
                            emptyBox.addView(UiTheme.iconTextPill(
                                    MainActivity.this, theme, Icons.Name.KEY, tr("auth_banner_btn"),
                                    theme.primaryBg, theme.primaryFg, 0,
                                    new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            sheet.dismiss();
                                            showActiveCoreTokenDialog();
                                        }
                                    }));
                        } else {
                            emptyBox.addView(UiTheme.iconTextPill(
                                    MainActivity.this, theme, Icons.Name.REFRESH, tr("history_refresh"),
                                    theme.bgSurface, theme.accent, theme.borderSubtle,
                                    new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            if (refreshHolder[0] != null) {
                                                refreshHolder[0].run();
                                            }
                                        }
                                    }));
                        }
                    }

                    list.addView(emptyBox, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    return;
                }

                boolean hasMultipleSessions = false;
                String firstSid = entries.get(0).sessionId;
                boolean firstArch = entries.get(0).archived;
                for (int i = 1; i < entries.size(); i++) {
                    CoreApiClient.ChatEntry item = entries.get(i);
                    if (item.archived != firstArch || !item.sessionId.equals(firstSid)) {
                        hasMultipleSessions = true;
                        break;
                    }
                }

                String prevSid = null;
                Boolean prevArchived = null;
                String prevRole = null;
                for (int i = 0; i < entries.size(); i++) {
                    CoreApiClient.ChatEntry e = entries.get(i);
                    boolean sessionChanged = (prevArchived == null)
                            ? (hasMultipleSessions || e.archived)
                            : (prevArchived.booleanValue() != e.archived
                                || (!e.sessionId.isEmpty() && !e.sessionId.equals(prevSid)));

                    if (sessionChanged) {
                        String chipText = e.archived ? tr("history_earlier_session") : tr("history_current_session");
                        String tsLabel = formatChatTimestamp(e.tsMs, true);
                        if (!tsLabel.isEmpty()) {
                            chipText = chipText + " · " + tsLabel;
                        }
                        LinearLayout divRow = new LinearLayout(MainActivity.this);
                        divRow.setOrientation(LinearLayout.HORIZONTAL);
                        divRow.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        dLp.topMargin = (i == 0) ? dp(2) : dp(16);
                        dLp.bottomMargin = dp(12);

                        View leftLine = UiTheme.hairlineDivider(MainActivity.this, theme);
                        divRow.addView(leftLine, new LinearLayout.LayoutParams(0, UiTheme.hairlinePx(MainActivity.this), 1f));

                        TextView chip = new TextView(MainActivity.this);
                        chip.setText(chipText);
                        chip.setTextColor(theme.textMuted);
                        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                        chip.setFontFeatureSettings("tnum");
                        chip.setBackground(UiTheme.pill(theme.bgElevated, 0, 0));
                        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
                        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        cLp.leftMargin = dp(10);
                        cLp.rightMargin = dp(10);
                        divRow.addView(chip, cLp);

                        View rightLine = UiTheme.hairlineDivider(MainActivity.this, theme);
                        divRow.addView(rightLine, new LinearLayout.LayoutParams(0, UiTheme.hairlinePx(MainActivity.this), 1f));
                        list.addView(divRow, dLp);
                        prevRole = null;
                    }

                    boolean isUser = "user".equalsIgnoreCase(e.role);
                    boolean sameSpeakerAsPrev = (prevRole != null && prevRole.equalsIgnoreCase(e.role));

                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(isUser ? Gravity.END : Gravity.START);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = sameSpeakerAsPrev ? dp(4) : dp(10);

                    TextView bubble = new TextView(MainActivity.this);
                    bubble.setText(e.text);
                    bubble.setTextColor(isUser ? theme.primaryFg : theme.textPrimary);
                    bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    bubble.setLineSpacing(dp(3), 1.0f);
                    bubble.setMaxWidth(isUser ? userMaxW : asstMaxW);
                    bubble.setTextIsSelectable(true);
                    bubble.setBackground(UiTheme.roundedRect(
                            isUser ? theme.primaryBg : theme.bgSurface,
                            isUser ? 0 : theme.borderSubtle,
                            dp(18), isUser ? 0 : UiTheme.hairlinePx(MainActivity.this)));
                    bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
                    row.addView(bubble, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    String timeStr = formatChatTimestamp(e.tsMs, false);
                    if (!timeStr.isEmpty()) {
                        TextView tvTs = new TextView(MainActivity.this);
                        tvTs.setText(timeStr);
                        tvTs.setTextColor(theme.textMuted);
                        tvTs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                        tvTs.setFontFeatureSettings("tnum");
                        tvTs.setPadding(dp(6), dp(3), dp(6), 0);
                        row.addView(tvTs);
                    }

                    list.addView(row, rlp);
                    prevSid = e.sessionId;
                    prevArchived = Boolean.valueOf(e.archived);
                    prevRole = e.role;
                }

                sv.post(new Runnable() {
                    @Override
                    public void run() {
                        sv.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }

        final HistoryRenderer renderer = new HistoryRenderer();
        clearVisUpdater[0] = new Runnable() {
            @Override
            public void run() {
                boolean hasAny = latestCleanTranscript != null && !latestCleanTranscript.isEmpty();
                int clearVis = hasAny ? View.VISIBLE : View.GONE;
                btnClear.setVisibility(clearVis);
                clearSpacer.setVisibility(clearVis);
                if (!hasAny && sheet.isShowing()) {
                    renderer.render(new ArrayList<CoreApiClient.ChatEntry>(), false, false);
                }
            }
        };
        if (!initial.isEmpty() && !(gatewayService != null && gatewayService.isAuthRequired())) {
            btnClear.setVisibility(View.VISIBLE);
            clearSpacer.setVisibility(View.VISIBLE);
        }
        renderer.render(initial, initial.isEmpty() && gatewayService != null, false);

        refreshHolder[0] = new Runnable() {
            @Override
            public void run() {
                if (gatewayService == null) {
                    renderer.render(initial, false, false);
                    return;
                }
                btnRefresh.setEnabled(false);
                btnRefresh.setAlpha(0.45f);
                tvSub.setText(String.format(Locale.US, tr("history_refreshing"), coreName));
                gatewayService.fetchConversationHistoryNowAsync(new GatewayService.HistoryCallback() {
                    @Override
                    public void onHistoryLoaded(
                            final List<CoreApiClient.ChatEntry> messages,
                            final String currentSessionId,
                            final boolean authRequired,
                            final boolean ok) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || isDestroyed() || !sheet.isShowing()) {
                                    return;
                                }
                                btnRefresh.setEnabled(true);
                                btnRefresh.setAlpha(1.0f);
                                List<CoreApiClient.ChatEntry> toShow = (messages != null && !messages.isEmpty())
                                        ? messages
                                        : (ok ? new ArrayList<CoreApiClient.ChatEntry>() : initial);
                                if (ok && messages != null) {
                                    latestCleanTranscript = messages;
                                    renderSlimTickerIfChanged(messages);
                                }
                                currentSessionIdHolder[0] = (currentSessionId != null) ? currentSessionId.trim() : "";
                                boolean hasAnyEntries = toShow != null && !toShow.isEmpty();
                                int clearVis = (hasAnyEntries && !authRequired) ? View.VISIBLE : View.GONE;
                                btnClear.setVisibility(clearVis);
                                clearSpacer.setVisibility(clearVis);
                                renderer.render(toShow, false, authRequired);
                            }
                        });
                    }
                });
            }
        };

        sheet.setContentView(card, new ViewGroup.LayoutParams(sheetWidthPx, sheetHeightPx));
        showTrackedDialog(sheet);
        Window win = sheet.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.width = sheetWidthPx;
            wlp.height = sheetHeightPx;
            wlp.gravity = Gravity.CENTER;
            wlp.dimAmount = theme.isLight ? 0.42f : 0.68f;
            win.setAttributes(wlp);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        // Trigger immediate live + archived fetch from Core
        refreshHolder[0].run();
    }

    private void sendComposerText() {
        if (etComposer == null || gatewayService == null) {
            return;
        }
        String text = etComposer.getText().toString().trim();
        if (!text.isEmpty()) {
            if (!gatewayService.isManageOnlyMode() && !gatewayService.isConnected()) {
                Toast.makeText(this, tr("voice_disconnected_reconnect"), Toast.LENGTH_SHORT).show();
                return;
            }
            etComposer.setText("");
            gatewayService.triggerUtterance(text);
        }
    }

    // =========================================================================
    // Tab 2: Apple Home Whole-Home Core Cards & Live Mode Controls
    // =========================================================================

    private ScrollView buildRoomsTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(2), dp(16), dp(28));

        // 1. Whole-Home Devices Section Header + Compact Scan/Refresh (hidden when list is empty)
        LinearLayout whHeader = new LinearLayout(this);
        whHeader.setOrientation(LinearLayout.HORIZONTAL);
        whHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams whLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        whLp.topMargin = dp(10);
        whLp.bottomMargin = dp(10);

        tvRoomsSectionTitle = new TextView(this);
        tvRoomsSectionTitle.setText(tr("rooms_whole_home_title"));
        tvRoomsSectionTitle.setTextColor(theme.textSecondary);
        tvRoomsSectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvRoomsSectionTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wtLp.leftMargin = dp(6);
        whHeader.addView(tvRoomsSectionTitle, wtLp);

        roomsHeaderActions = new LinearLayout(this);
        roomsHeaderActions.setOrientation(LinearLayout.HORIZONTAL);
        roomsHeaderActions.setGravity(Gravity.CENTER_VERTICAL);
        roomsHeaderActions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.WIFI, tr("rooms_scan_short"),
                theme.accentSoft, theme.accent, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { scanLanAndRefreshRooms(); }
                }));
        roomsHeaderActions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        roomsHeaderActions.addView(UiTheme.circleIconButton(this, theme, Icons.Name.REFRESH, theme.accent,
                theme.bgSurface, theme.borderSubtle, 44,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshWholeHomeRoomCards(); }
                }));
        whHeader.addView(roomsHeaderActions);
        body.addView(whHeader, whLp);

        wholeHomeRoomsContainer = new LinearLayout(this);
        wholeHomeRoomsContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(wholeHomeRoomsContainer);

        // 2. Unified Active Room Controls (hidden until a Core is configured)
        roomsActiveControlsSection = new LinearLayout(this);
        roomsActiveControlsSection.setOrientation(LinearLayout.VERTICAL);
        roomsActiveControlsSection.setVisibility(config.hasConfiguredCore() ? View.VISIBLE : View.GONE);

        roomsActiveControlsSection.addView(UiTheme.sectionHeading(this, theme, tr("rooms_active_controls_title")));
        LinearLayout controlsCard = UiTheme.insetGroupCard(this, theme, 16, 14);

        // Volume slider
        LinearLayout volHeader = new LinearLayout(this);
        volHeader.setOrientation(LinearLayout.HORIZONTAL);
        volHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvVolLbl = new TextView(this);
        tvVolLbl.setText(tr("volume_label"));
        tvVolLbl.setTextColor(theme.textPrimary);
        tvVolLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        volHeader.addView(tvVolLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvRoomsVolumePct = new TextView(this);
        tvRoomsVolumePct.setText("65%");
        tvRoomsVolumePct.setTextColor(theme.textSecondary);
        tvRoomsVolumePct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvRoomsVolumePct.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvRoomsVolumePct.setFontFeatureSettings("tnum");
        volHeader.addView(tvRoomsVolumePct);
        controlsCard.addView(volHeader);

        roomsVolumeSlider = new SeekBar(this);
        roomsVolumeSlider.setMax(100);
        roomsVolumeSlider.setProgress(65);
        UiTheme.styleSeekBar(roomsVolumeSlider, theme);
        roomsVolumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRoomsVolumePct.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (gatewayService != null) {
                    double targetVol = seekBar.getProgress() / 100.0;
                    boolean manageOnly = gatewayService.isManageOnlyMode();
                    if (DeviceRoutingPolicy.shouldUseRemoteVolumeControl(manageOnly)) {
                        String devId = DeviceRoutingPolicy.resolvePrimaryRemoteDeviceId(true, gatewayService.getCoreSnapshot());
                        if (!devId.isEmpty()) {
                            gatewayService.setRemoteRoomVolumeAsync(
                                    config.getCoreHost(), config.getWebPort(), devId, targetVol,
                                    new GatewayService.ActionResultCallback() {
                                        @Override
                                        public void onActionDone(boolean ok, boolean authRequired) {
                                            if (ok && gatewayService != null) {
                                                gatewayService.refreshCoreSnapshotNow();
                                            }
                                        }
                                    });
                        }
                    } else {
                        // Voice Gateway (connected or transiently disconnected): control ONLY local audio volume!
                        gatewayService.setVolumeFromUi(targetVol);
                    }
                }
            }
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(8);
        sLp.bottomMargin = dp(12);
        controlsCard.addView(roomsVolumeSlider, sLp);

        controlsCard.addView(UiTheme.hairlineDivider(this, theme));

        // Duplex mode segmented control
        TextView tvDupLbl = new TextView(this);
        tvDupLbl.setText(tr("mode_duplex"));
        tvDupLbl.setTextColor(theme.textSecondary);
        tvDupLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams dupLblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dupLblLp.topMargin = dp(12);
        controlsCard.addView(tvDupLbl, dupLblLp);

        LinearLayout dupRow = buildAppleSegmentedTrack();
        LinearLayout.LayoutParams dRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dRowLp.topMargin = dp(6);
        dRowLp.bottomMargin = dp(12);

        tvModeDuplexHalf = buildSegmentPill(tr("mode_half"), new View.OnClickListener() {
            @Override public void onClick(View v) { applyModeUpdate("half", null, null, null); }
        });
        tvModeDuplexFull = buildSegmentPill(tr("mode_full"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gatewayService != null && gatewayService.getCoreSnapshot() != null
                        && "full".equalsIgnoreCase(gatewayService.getCoreSnapshot().duplexMode)) {
                    return;
                }
                showTrackedDialog(UiTheme.dialog(MainActivity.this, theme)
                        .setTitle(tr("mode_full_dev_confirm_title"))
                        .setMessage(tr("mode_full_dev_confirm_msg"))
                        .setPositiveButton(tr("mode_full_dev_confirm_ok"), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                applyModeUpdate("full", null, null, null);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create());
            }
        });
        dupRow.addView(tvModeDuplexHalf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dupRow.addView(tvModeDuplexFull, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dRowLp.bottomMargin = dp(4);
        controlsCard.addView(dupRow, dRowLp);

        TextView tvDupHint = new TextView(this);
        tvDupHint.setText(tr("mode_full_dev_hint"));
        tvDupHint.setTextColor(theme.textMuted);
        tvDupHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams dupHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dupHintLp.bottomMargin = dp(10);
        controlsCard.addView(tvDupHint, dupHintLp);

        controlsCard.addView(UiTheme.hairlineDivider(this, theme));
        swBargeIn = addSwitchRow(controlsCard, tr("mode_barge_in"), false, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!updatingControlsFromSnapshot) {
                    applyModeUpdate(null, Boolean.valueOf(isChecked), null, null);
                }
            }
        });
        controlsCard.addView(UiTheme.hairlineDivider(this, theme));
        swWakeAck = addSwitchRow(controlsCard, tr("mode_wake_ack"), true, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!updatingControlsFromSnapshot) {
                    applyModeUpdate(null, null, Boolean.valueOf(isChecked), null);
                }
            }
        });

        // Multi-room wake arbitration (visible only when >1 room or fleet peers exist)
        roomsArbSection = new LinearLayout(this);
        roomsArbSection.setOrientation(LinearLayout.VERTICAL);
        roomsArbSection.setVisibility(View.GONE);
        roomsArbSection.addView(UiTheme.hairlineDivider(this, theme));

        TextView tvArbLbl = new TextView(this);
        tvArbLbl.setText(tr("mode_wake_arb"));
        tvArbLbl.setTextColor(theme.textSecondary);
        tvArbLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams aLblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLblLp.topMargin = dp(12);
        roomsArbSection.addView(tvArbLbl, aLblLp);

        LinearLayout arbRow = buildAppleSegmentedTrack();
        LinearLayout.LayoutParams aRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aRowLp.topMargin = dp(6);
        aRowLp.bottomMargin = dp(10);
        tvArbOff = buildSegmentPill(tr("arb_off"), new View.OnClickListener() {
            @Override public void onClick(View v) { applyModeUpdate(null, null, null, "off"); }
        });
        tvArbObserve = buildSegmentPill(tr("arb_observe"), new View.OnClickListener() {
            @Override public void onClick(View v) { applyModeUpdate(null, null, null, "observe"); }
        });
        tvArbEnforce = buildSegmentPill(tr("arb_enforce"), new View.OnClickListener() {
            @Override public void onClick(View v) { applyModeUpdate(null, null, null, "enforce"); }
        });
        arbRow.addView(tvArbOff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        arbRow.addView(tvArbObserve, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        arbRow.addView(tvArbEnforce, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        roomsArbSection.addView(arbRow, aRowLp);
        controlsCard.addView(roomsArbSection);

        // Collapsible Devices & Acoustics disclosure row (keeps Rooms screen scannable and calm)
        controlsCard.addView(UiTheme.hairlineDivider(this, theme));
        LinearLayout detailsToggleRow = new LinearLayout(this);
        detailsToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        detailsToggleRow.setGravity(Gravity.CENTER_VERTICAL);
        detailsToggleRow.setMinimumHeight(dp(46));
        detailsToggleRow.setPadding(0, dp(10), 0, dp(4));
        detailsToggleRow.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(8), 0));
        detailsToggleRow.setClickable(true);

        TextView tvDetailsLbl = new TextView(this);
        tvDetailsLbl.setText(tr("rooms_details_toggle"));
        tvDetailsLbl.setTextColor(theme.textSecondary);
        tvDetailsLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvDetailsLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        detailsToggleRow.addView(tvDetailsLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ivRoomsDetailsChevron = new ImageView(this);
        int chevSz = dp(16);
        ivRoomsDetailsChevron.setImageDrawable(Icons.of(
                roomsDetailsExpanded ? Icons.Name.CHEVRON_UP : Icons.Name.CHEVRON_DOWN,
                theme.textMuted, chevSz));
        detailsToggleRow.addView(ivRoomsDetailsChevron, new LinearLayout.LayoutParams(chevSz, chevSz));

        roomsDetailsBody = new LinearLayout(this);
        roomsDetailsBody.setOrientation(LinearLayout.VERTICAL);
        roomsDetailsBody.setVisibility(roomsDetailsExpanded ? View.VISIBLE : View.GONE);

        tvRoomsAcousticBar = new TextView(this);
        tvRoomsAcousticBar.setTextColor(theme.textMuted);
        tvRoomsAcousticBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvRoomsAcousticBar.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams acBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acBarLp.topMargin = dp(8);
        acBarLp.bottomMargin = dp(10);
        roomsDetailsBody.addView(tvRoomsAcousticBar, acBarLp);

        roomsDetailsBody.addView(UiTheme.hairlineDivider(this, theme));

        devicesListContainer = new LinearLayout(this);
        devicesListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlLp.topMargin = dp(4);
        roomsDetailsBody.addView(devicesListContainer, dlLp);

        detailsToggleRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                roomsDetailsExpanded = !roomsDetailsExpanded;
                roomsDetailsBody.setVisibility(roomsDetailsExpanded ? View.VISIBLE : View.GONE);
                ivRoomsDetailsChevron.setImageDrawable(Icons.of(
                        roomsDetailsExpanded ? Icons.Name.CHEVRON_UP : Icons.Name.CHEVRON_DOWN,
                        theme.textMuted, dp(16)));
            }
        });

        controlsCard.addView(detailsToggleRow);
        controlsCard.addView(roomsDetailsBody);
        roomsActiveControlsSection.addView(controlsCard);
        body.addView(roomsActiveControlsSection);

        sv.addView(body);
        return sv;
    }

    private void scanLanAndRefreshRooms() {
        scanLanAndRefreshRooms(true);
    }

    private void scanLanAndRefreshRooms(boolean showStartToast) {
        if (gatewayService == null) {
            pendingLanScanAction = PENDING_LAN_SCAN_ROOMS;
            Toast.makeText(this, tr("rooms_scanning"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (showStartToast) {
            Toast.makeText(this, tr("rooms_scanning"), Toast.LENGTH_SHORT).show();
        }
        gatewayService.discoverLanCoresAsync(false, new GatewayService.DiscoveryCallback() {
            @Override
            public void onCoresDiscovered(final List<CoreDiscovery.DiscoveredCore> cores) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (cores.isEmpty()) {
                            Toast.makeText(MainActivity.this, tr("settings_no_lan_core"), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    String.format(Locale.US, tr("rooms_found_fmt"), cores.size()),
                                    Toast.LENGTH_SHORT).show();
                        }
                        refreshProfilesList();
                        refreshWholeHomeRoomCards();
                        scheduleUiRefresh();
                    }
                });
            }
        });
    }

    private void refreshWholeHomeRoomCards() {
        if (gatewayService == null) return;
        gatewayService.fetchAllRoomSummariesAsync(new GatewayService.RoomSummariesCallback() {
            @Override
            public void onRoomSummaries(final List<CoreApiClient.RoomNodeSummary> summaries) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        latestRoomSummaries = summaries;
                        renderWholeHomeRoomCards(summaries);
                    }
                });
            }
        });
    }

    private void renderWholeHomeRoomCards(List<CoreApiClient.RoomNodeSummary> summaries) {
        if (wholeHomeRoomsContainer == null) return;
        wholeHomeRoomsContainer.removeAllViews();

        List<CoreApiClient.RoomNodeSummary> displayList = summaries;
        if (displayList == null || displayList.isEmpty()) {
            // Show saved profiles immediately while live summaries load
            displayList = new ArrayList<CoreApiClient.RoomNodeSummary>();
            for (GatewayConfig.ServerProfile p : config.getServerProfiles()) {
                displayList.add(new CoreApiClient.RoomNodeSummary(
                        p.host, p.webPort, p.gatewayPort, p.name, "Home Mate",
                        false, false, !p.manageOnly, false, "", "", "half", "", 0, "loading", p.coreId, false));
            }
        }

        boolean configured = config.hasConfiguredCore();
        if (roomsActiveControlsSection != null) {
            roomsActiveControlsSection.setVisibility(configured ? View.VISIBLE : View.GONE);
        }

        if (displayList.isEmpty()) {
            if (roomsHeaderActions != null) {
                roomsHeaderActions.setVisibility(View.GONE);
            }
            if (tvRoomsSectionTitle != null) {
                tvRoomsSectionTitle.setText(tr("rooms_whole_home_title"));
            }
            if (roomsArbSection != null) {
                roomsArbSection.setVisibility(View.GONE);
            }

            LinearLayout emptyCard = UiTheme.insetGroupCard(this, theme, 20, 24);
            emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);

            FrameLayout badge = new FrameLayout(this);
            badge.setBackground(UiTheme.pill(theme.accentSoft, 0, 0));
            ImageView ivBadge = new ImageView(this);
            int bsz = dp(22);
            ivBadge.setImageDrawable(Icons.of(Icons.Name.ROOMS, theme.accent, bsz));
            badge.addView(ivBadge, new FrameLayout.LayoutParams(bsz, bsz, Gravity.CENTER));
            emptyCard.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));

            TextView tvTitle = new TextView(this);
            tvTitle.setText(tr("onboarding_title"));
            tvTitle.setTextColor(theme.textPrimary);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tvTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(14);
            emptyCard.addView(tvTitle, tlp);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(tr("onboarding_desc"));
            tvDesc.setTextColor(theme.textSecondary);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvDesc.setGravity(Gravity.CENTER);
            tvDesc.setLineSpacing(dp(2), 1.0f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(6);
            dlp.bottomMargin = dp(18);
            emptyCard.addView(tvDesc, dlp);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER);
            btns.addView(UiTheme.iconTextPill(this, theme, Icons.Name.WIFI, tr("rooms_scan_primary"),
                    theme.primaryBg, theme.primaryFg, 0,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { scanLanAndRefreshRooms(); }
                    }));
            btns.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
            btns.addView(UiTheme.iconTextPill(this, theme, null, tr("rooms_add_manual"),
                    theme.bgElevated, theme.textPrimary, 0,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { showCustomProfileDialog(null); }
                    }));
            emptyCard.addView(btns);
            wholeHomeRoomsContainer.addView(emptyCard);
            return;
        }

        if (roomsHeaderActions != null) {
            roomsHeaderActions.setVisibility(View.VISIBLE);
        }
        if (tvRoomsSectionTitle != null) {
            tvRoomsSectionTitle.setText(String.format(Locale.US, tr("rooms_count_fmt"), displayList.size()));
        }
        if (roomsArbSection != null) {
            CoreApiClient.CoreSnapshot snapNow = gatewayService != null ? gatewayService.getCoreSnapshot() : null;
            boolean showArb = displayList.size() > 1
                    || (snapNow != null && snapNow.fleetNodes != null && !snapNow.fleetNodes.isEmpty());
            roomsArbSection.setVisibility(showArb ? View.VISIBLE : View.GONE);
        }

        String activeHost = config.getCoreHost();
        for (final CoreApiClient.RoomNodeSummary r : displayList) {
            boolean isActive = r.host.equalsIgnoreCase(activeHost);
            Integer volPct = (r.volume != null && !Double.isNaN(r.volume) && !Double.isInfinite(r.volume))
                    ? Integer.valueOf(Math.max(0, Math.min(100, (int) Math.round(r.volume.doubleValue() * 100.0))))
                    : null;

            LinearLayout card = UiTheme.insetGroupCard(this, theme, 16, 14);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);

            // Row 1: Status dot + Room Name + Single-line subtitle + Restrained badges
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(this);
            int dotColor = r.authRequired ? theme.accentWarm
                    : (r.online ? theme.statusOnline : theme.textMuted);
            dot.setBackground(UiTheme.pill(dotColor, 0, 0));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dlp.rightMargin = dp(10);
            top.addView(dot, dlp);

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvName = new TextView(this);
            tvName.setText(r.displayName);
            tvName.setTextColor(theme.textPrimary);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tvName.setMaxLines(1);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            titleCol.addView(tvName);

            TextView tvMeta = new TextView(this);
            String statusText;
            if (r.authRequired) {
                statusText = r.host + " · " + tr("rooms_need_token");
            } else if (!r.online) {
                statusText = r.host + " · " + ("loading".equals(r.error) ? tr("status_connecting") : tr("rooms_room_offline"));
            } else {
                String baseStatus = isActive ? tr("rooms_active_online") : tr("rooms_room_idle");
                statusText = (volPct != null)
                        ? (baseStatus + " · " + tr("rooms_vol_short") + " " + volPct + "%")
                        : baseStatus;
            }
            tvMeta.setText(statusText);
            tvMeta.setTextColor(r.authRequired ? theme.accentWarm : theme.textMuted);
            tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvMeta.setFontFeatureSettings("tnum");
            tvMeta.setMaxLines(1);
            tvMeta.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.topMargin = dp(2);
            titleCol.addView(tvMeta, mLp);
            top.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean showManageBadge = r.online && !r.authRequired && !r.voiceGatewayOpen;
            if (isActive) {
                TextView activeBadge = UiTheme.pillBadge(this, theme, tr("badge_active"),
                        theme.accentSoft, theme.accent);
                LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                abLp.rightMargin = showManageBadge ? dp(6) : 0;
                top.addView(activeBadge, abLp);
            }
            if (showManageBadge) {
                TextView capBadge = UiTheme.pillBadge(this, theme, tr("badge_manage_only"),
                        theme.bgElevated, theme.textSecondary);
                top.addView(capBadge);
            }
            card.addView(top);

            // Compact inline Now-Playing bar ONLY when media is actively playing or paused on this room
            if (r.online && !r.authRequired && (r.musicActive || r.musicPaused)) {
                LinearLayout npBar = new LinearLayout(this);
                npBar.setOrientation(LinearLayout.HORIZONTAL);
                npBar.setGravity(Gravity.CENTER_VERTICAL);
                npBar.setBackground(UiTheme.roundedRect(theme.bgInput, 0, dp(12), 0));
                npBar.setPadding(dp(12), dp(6), dp(8), dp(6));
                LinearLayout.LayoutParams npLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                npLp.topMargin = dp(10);

                ImageView ivMusic = new ImageView(this);
                int msz = dp(16);
                ivMusic.setImageDrawable(Icons.of(Icons.Name.MUSIC, theme.accent, msz));
                LinearLayout.LayoutParams miLp = new LinearLayout.LayoutParams(msz, msz);
                miLp.rightMargin = dp(8);
                npBar.addView(ivMusic, miLp);

                String trackTitle = !r.musicTitle.isEmpty()
                        ? r.musicTitle
                        : (r.musicActive ? tr("now_playing") : tr("music_paused"));
                if (r.musicPaused && !r.musicTitle.isEmpty()) {
                    trackTitle = trackTitle + " · " + tr("music_paused");
                }
                TextView tvTrack = new TextView(this);
                tvTrack.setText(trackTitle);
                tvTrack.setTextColor(theme.textPrimary);
                tvTrack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tvTrack.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                tvTrack.setMaxLines(1);
                tvTrack.setEllipsize(TextUtils.TruncateAt.END);
                npBar.addView(tvTrack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (!r.activeDeviceId.isEmpty()) {
                    npBar.addView(UiTheme.circleIconButton(this, theme,
                            r.musicActive ? Icons.Name.PAUSE : Icons.Name.PLAY, theme.accent,
                            theme.accentSoft, 0, 44,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (gatewayService != null) {
                                        gatewayService.controlRemoteRoomMediaAsync(
                                                r.host, r.webPort, r.activeDeviceId,
                                                r.musicActive ? "pause" : "resume", makeActionToast());
                                    }
                                }
                            }));
                    npBar.addView(new View(this), new LinearLayout.LayoutParams(dp(4), 1));
                    npBar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.SKIP_NEXT, theme.textPrimary,
                            Color.TRANSPARENT, 0, 44,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (gatewayService != null) {
                                        gatewayService.controlRemoteRoomMediaAsync(
                                                r.host, r.webPort, r.activeDeviceId, "next", makeActionToast());
                                    }
                                }
                            }));
                    npBar.addView(new View(this), new LinearLayout.LayoutParams(dp(4), 1));
                    npBar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.STOP, theme.destructive,
                            theme.destructiveSoft, 0, 44,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (gatewayService != null) {
                                        gatewayService.stopRemoteRoomAsync(r.host, r.webPort, r.activeDeviceId, makeActionToast());
                                    }
                                }
                            }));
                }
                card.addView(npBar, npLp);
            }

            // Row 2: Clean, restrained contextual actions (no media player clutter on idle rooms)
            HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
            actionsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aLp.topMargin = dp(12);

            if (r.authRequired) {
                actions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.KEY, tr("rooms_enter_token_btn"),
                        theme.primaryBg, theme.primaryFg, 0,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { showRoomTokenDialog(r); }
                        }));
            } else if (r.online) {
                if (!isActive) {
                    actions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.CHECK, tr("rooms_switch_here_btn"),
                            theme.accentSoft, theme.accent, 0,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    switchActiveCore(r.displayName, r.host, r.gatewayPort, r.webPort, r.coreId, null,
                                            DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(
                                                    r.online, r.authRequired, r.voiceGatewayOpen));
                                }
                            }));
                    actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
                }
                if (!r.activeDeviceId.isEmpty()) {
                    String volLabel = (volPct != null)
                            ? (tr("rooms_vol_short") + " " + volPct + "%")
                            : tr("rooms_vol_short");
                    actions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.VOLUME, volLabel,
                            theme.bgElevated, theme.textPrimary, 0,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) { showRoomVolumeDialog(r); }
                            }));
                    actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
                }
                actions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.SEND, tr("rooms_cmd_btn"),
                        theme.bgElevated, theme.textPrimary, 0,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { showRoomCommandDialog(r); }
                        }));
            } else if (!isActive) {
                actions.addView(UiTheme.iconTextPill(this, theme, null, tr("rooms_switch_short"),
                        theme.bgElevated, theme.textSecondary, 0,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                switchActiveCore(r.displayName, r.host, r.gatewayPort, r.webPort, r.coreId, null,
                                        DeviceRoutingPolicy.resolveManageOnlyForRoomSwitch(
                                                r.online, r.authRequired, r.voiceGatewayOpen));
                            }
                        }));
            }

            if (actions.getChildCount() > 0) {
                actionsScroll.addView(actions);
                card.addView(actionsScroll, aLp);
            }
            wholeHomeRoomsContainer.addView(card, clp);
        }
    }

    private GatewayService.ActionResultCallback makeActionToast() {
        return new GatewayService.ActionResultCallback() {
            @Override
            public void onActionDone(final boolean ok, final boolean authRequired) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            boolean authNeeded = !ok && authRequired;
                            Toast.makeText(MainActivity.this,
                                    ok ? tr("rooms_cmd_sent")
                                            : (authNeeded ? tr("auth_action_required_toast") : tr("rooms_cmd_failed")),
                                    Toast.LENGTH_SHORT).show();
                            if (ok) {
                                refreshWholeHomeRoomCards();
                                if (gatewayService != null) {
                                    gatewayService.refreshCoreSnapshotNow();
                                }
                            } else if (authNeeded) {
                                scheduleUiRefresh();
                            }
                        }
                    }
                });
            }
        };
    }

    private void switchActiveCore(
            String name, String host, int gwPort, int webPort, String coreId, List<String> altHosts) {
        switchActiveCore(name, host, gwPort, webPort, coreId, altHosts, null);
    }

    private void switchActiveCore(
            String name, String host, int gwPort, int webPort, String coreId, List<String> altHosts, Boolean manageOnly) {
        String existingTok = config.getBearerTokenForHost(host);
        config.upsertProfileAndSelect(name, host, gwPort, webPort, existingTok, coreId, altHosts, manageOnly);
        if (tvConsoleUrl != null) tvConsoleUrl.setText(config.getWebConsoleUrl());
        if (gatewayService != null) gatewayService.reloadConfigAndReconnect();
        if (activeTab == TAB_CONSOLE || webView != null) {
            ensureWebViewLoaded(true);
        }
        refreshProfilesList();
        renderWholeHomeRoomCards(latestRoomSummaries);
        scheduleUiRefresh();
    }

    private void showActiveCoreTokenDialog() {
        String host = config.getCoreHost();
        if (host.isEmpty()) {
            showCustomProfileDialog(null);
            return;
        }
        CoreApiClient.RoomNodeSummary dummy = new CoreApiClient.RoomNodeSummary(
                host, config.getWebPort(), config.getCorePort(), getActiveCoreDisplayName(), "Home Mate",
                true, true, true, false, "", "", "half", "", 0, null);
        showRoomTokenDialog(dummy);
    }

    private void showRoomTokenDialog(final CoreApiClient.RoomNodeSummary room) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(6));

        TextView tvHelper = new TextView(this);
        tvHelper.setText(tr("rooms_token_desc"));
        tvHelper.setTextColor(theme.textSecondary);
        tvHelper.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvHelper.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(12);
        form.addView(tvHelper, hlp);

        final EditText etToken = UiTheme.themedEditText(this, theme,
                tr("settings_profile_token_hint"), config.getBearerTokenForHost(room.host));
        etToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etToken.setSingleLine(true);
        form.addView(etToken);

        final TextView tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        form.addView(tvStatus, slp);

        final AlertDialog dialog = UiTheme.dialog(this, theme)
                .setTitle(tr("rooms_token_title") + " · " + room.displayName)
                .setView(form)
                .setPositiveButton(tr("rooms_token_verify_btn"), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showTrackedDialog(dialog);

        final Button btnVerify = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (btnVerify != null) {
            btnVerify.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String sanitized = CoreApiClient.sanitizeBearerToken(etToken.getText().toString());
                    if (sanitized.isEmpty()) {
                        tvStatus.setText(tr("rooms_token_empty_err"));
                        tvStatus.setTextColor(theme.destructive);
                        tvStatus.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (gatewayService == null) {
                        config.setProfileBearerToken(room.host, sanitized);
                        dialog.dismiss();
                        return;
                    }
                    btnVerify.setEnabled(false);
                    etToken.setEnabled(false);
                    tvStatus.setText(tr("rooms_token_verifying"));
                    tvStatus.setTextColor(theme.textSecondary);
                    tvStatus.setVisibility(View.VISIBLE);

                    gatewayService.verifyRoomTokenAsync(
                            room.host, room.webPort, sanitized,
                            new GatewayService.TokenVerifyCallback() {
                                @Override
                                public void onTokenVerified(
                                        final CoreApiClient.TokenVerifyResult result,
                                        final String cleanToken) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
                                                return;
                                            }
                                            if (result != null && result.ok) {
                                                String verifiedCoreId = !result.coreId.isEmpty() ? result.coreId : room.coreId;
                                                config.setProfileBearerToken(room.host, cleanToken, verifiedCoreId);
                                                if (config.activeProfileMatchesHost(room.host)) {
                                                    if (gatewayService != null) {
                                                        gatewayService.reloadConfigAndReconnect();
                                                    }
                                                    if (activeTab == TAB_CONSOLE || webView != null) {
                                                        ensureWebViewLoaded(true);
                                                    }
                                                }
                                                refreshProfilesList();
                                                refreshWholeHomeRoomCards();
                                                scheduleUiRefresh();
                                                dialog.dismiss();
                                                Toast.makeText(MainActivity.this,
                                                        tr("rooms_token_verified_ok"),
                                                        Toast.LENGTH_SHORT).show();
                                            } else {
                                                btnVerify.setEnabled(true);
                                                etToken.setEnabled(true);
                                                boolean invalid = result != null && result.authRequired;
                                                tvStatus.setText(invalid
                                                        ? tr("rooms_token_invalid_err")
                                                        : tr("rooms_token_unreachable_err"));
                                                tvStatus.setTextColor(theme.destructive);
                                                tvStatus.setVisibility(View.VISIBLE);
                                            }
                                        }
                                    });
                                }
                            });
                }
            });
        }
    }

    private void showRoomVolumeDialog(final CoreApiClient.RoomNodeSummary room) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(8));

        int initialPct = (room.volume != null && !Double.isNaN(room.volume) && !Double.isInfinite(room.volume))
                ? Math.max(0, Math.min(100, (int) Math.round(room.volume * 100.0)))
                : 50;

        final TextView tvPct = new TextView(this);
        tvPct.setText(initialPct + "%");
        tvPct.setTextColor(theme.textPrimary);
        tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvPct.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvPct.setGravity(Gravity.CENTER);
        tvPct.setFontFeatureSettings("tnum");
        box.addView(tvPct);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(initialPct);
        UiTheme.styleSeekBar(sb, theme);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPct.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (gatewayService != null) {
                    gatewayService.setRemoteRoomVolumeAsync(
                            room.host, room.webPort, room.activeDeviceId, seekBar.getProgress() / 100.0, makeActionToast());
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        box.addView(sb, slp);

        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(String.format(Locale.US, tr("rooms_volume_title_fmt"), room.displayName))
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    private void showRoomCommandDialog(final CoreApiClient.RoomNodeSummary room) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(14), dp(20), dp(6));

        final EditText etCmd = UiTheme.themedEditText(this, theme, tr("input_hint"), "");
        form.addView(etCmd);

        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(String.format(Locale.US, tr("rooms_cmd_title_fmt"), room.displayName))
                .setView(form)
                .setPositiveButton(tr("btn_send"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String cmd = etCmd.getText().toString().trim();
                        if (!cmd.isEmpty() && gatewayService != null) {
                            gatewayService.sendRemoteRoomCommandAsync(room.host, room.webPort, cmd, makeActionToast());
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Apple iOS-style Segmented Control recessed track. */
    private LinearLayout buildAppleSegmentedTrack() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(UiTheme.roundedRect(theme.segTrack, 0, dp(10), 0));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        return row;
    }

    private TextView buildSegmentPill(String label, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(dp(36));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(theme.textSecondary);
        tv.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(8), 0));
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setClickable(true);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private void styleSegment(TextView tv, boolean active) {
        if (tv == null) return;
        Boolean prev = (tv.getTag() instanceof Boolean) ? (Boolean) tv.getTag() : null;
        if (prev != null && prev.booleanValue() == active) {
            return;
        }
        tv.setTag(Boolean.valueOf(active));
        tv.setTextColor(active ? theme.segThumbFg : theme.textSecondary);
        tv.setTypeface(active ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        tv.setBackground(UiTheme.rippleRoundedRect(
                theme,
                active ? theme.segThumb : Color.TRANSPARENT,
                0,
                dp(8),
                0));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setElevation(active && theme.isLight ? dp(1) : 0f);
        }
    }

    private void applyModeUpdate(String duplex, Boolean bargeIn, Boolean wakeAck, String wakeArb) {
        if (gatewayService == null) return;
        gatewayService.applyDeviceModeAsync(duplex, bargeIn, wakeAck, wakeArb, new GatewayService.ModeApplyCallback() {
            @Override
            public void onModeApplied(final boolean ok, final boolean authRequired) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            boolean authNeeded = !ok && authRequired;
                            Toast.makeText(MainActivity.this,
                                    ok ? tr("mode_saved")
                                            : (authNeeded ? tr("auth_action_required_toast") : tr("mode_save_failed")),
                                    Toast.LENGTH_SHORT).show();
                            if (authNeeded) {
                                scheduleUiRefresh();
                            }
                        }
                    }
                });
            }
        });
    }

    // =========================================================================
    // Tab 3: Embedded Full Home Mate Web Console
    // =========================================================================

    private LinearLayout buildConsoleTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(6), dp(16), dp(8));

        tvConsoleUrl = new TextView(this);
        tvConsoleUrl.setText(config.getWebConsoleUrl());
        tvConsoleUrl.setTextColor(theme.textMuted);
        tvConsoleUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvConsoleUrl.setSingleLine(true);
        toolbar.addView(tvConsoleUrl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        toolbar.addView(UiTheme.iconTextPill(this, theme, Icons.Name.REFRESH, tr("console_reload"),
                theme.bgSurface, theme.accent, theme.borderSubtle,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { ensureWebViewLoaded(true); }
                }));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        toolbar.addView(UiTheme.circleIconButton(this, theme, Icons.Name.EXTERNAL, theme.accent,
                theme.bgSurface, theme.borderSubtle, 44,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { AppUpdater.openDownloadInBrowser(MainActivity.this, config.getWebConsoleUrl()); }
                }));
        wrap.addView(toolbar);

        webViewHost = new FrameLayout(this);
        if (webView != null) {
            webViewHost.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        wrap.addView(webViewHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrap;
    }

    private void ensureWebViewLoaded() { ensureWebViewLoaded(false); }

    private void ensureWebViewLoaded(boolean forceReload) {
        String url = config.getWebConsoleUrl();
        if (tvConsoleUrl != null) {
            tvConsoleUrl.setText(url.isEmpty() ? tr("status_unconfigured") : url);
        }
        if (url.isEmpty()) {
            if (webViewHost != null) {
                webViewHost.removeAllViews();
                TextView empty = new TextView(this);
                empty.setText(tr("console_unconfigured"));
                empty.setTextColor(theme.textSecondary);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(24), dp(40), dp(24), dp(40));
                webViewHost.addView(empty);
            }
            return;
        }
        if (webView == null && webViewHost != null) {
            try {
                webView = new WebView(this);
                webView.setBackgroundColor(theme.bgRoot);
                WebSettings ws = webView.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setCacheMode(WebSettings.LOAD_DEFAULT);
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                webView.setWebChromeClient(new WebChromeClient());
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String loadedUrl) {
                        super.onPageFinished(view, loadedUrl);
                        injectConsoleTokenIfOriginMatches(view, loadedUrl);
                    }
                });
                webViewHost.removeAllViews();
                webViewHost.addView(webView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) {
                webViewHost.removeAllViews();
                TextView fallback = new TextView(this);
                fallback.setText(tr("console_unavailable"));
                fallback.setTextColor(theme.textSecondary);
                fallback.setGravity(Gravity.CENTER);
                fallback.setPadding(dp(24), dp(40), dp(24), dp(40));
                webViewHost.addView(fallback);
                return;
            }
        } else if (webView != null && webViewHost != null && webView.getParent() != webViewHost) {
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webViewHost.removeAllViews();
            webViewHost.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        String activeToken = config.getBearerToken();
        if (webView != null && (forceReload || !url.equals(lastLoadedWebUrl) || !activeToken.equals(lastLoadedWebToken))) {
            lastLoadedWebUrl = url;
            lastLoadedWebToken = activeToken;
            webView.loadUrl(url);
        }
    }

    private static String normalizedOrigin(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return "";
        }
        try {
            java.net.URI u = new java.net.URI(rawUrl.trim());
            String scheme = u.getScheme() != null ? u.getScheme().toLowerCase(java.util.Locale.US) : "";
            String host = u.getHost() != null ? u.getHost().toLowerCase(java.util.Locale.US) : "";
            if (scheme.isEmpty() || host.isEmpty()) {
                return "";
            }
            int port = u.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Seeds or clears {@code window.localStorage['hm.api.token']} ONLY when the WebView is on
     * the currently selected Core's exact normalized origin (scheme + host + port), reloading once
     * if the stored token changed so the Vue Console unlocks automatically with the Android app's
     * paired Bearer token (P1-15). When Android has no saved token for the active Core, any token
     * entered directly inside the Web Console is preserved unless Android previously injected one.
     */
    private void injectConsoleTokenIfOriginMatches(WebView view, String loadedUrl) {
        if (view == null || loadedUrl == null || loadedUrl.isEmpty()) {
            return;
        }
        String expectedOrigin = normalizedOrigin(config.getWebConsoleUrl());
        String actualOrigin = normalizedOrigin(loadedUrl);
        if (expectedOrigin.isEmpty() || !expectedOrigin.equals(actualOrigin)) {
            return;
        }
        String desired = config.getBearerToken();
        String desiredJson = homes.mymate.gateway.protocol.MiniJson.stringify(desired != null ? desired : "");
        String js = "(function(){try{"
                + "var k='hm.api.token',m='hm.android.injected';"
                + "var d=" + desiredJson + ";"
                + "var c=window.localStorage.getItem(k)||'';"
                + "if(d){if(c!==d){window.localStorage.setItem(k,d);window.localStorage.setItem(m,'1');window.location.reload();}}"
                + "else if(window.localStorage.getItem(m)==='1'){window.localStorage.removeItem(m);if(c){window.localStorage.removeItem(k);window.location.reload();}}"
                + "}catch(e){}})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(js, null);
        } else {
            view.loadUrl("javascript:" + js);
        }
    }

    // =========================================================================
    // Tab 4: Apple Settings-Style Inset Grouped Preferences
    // =========================================================================

    private ScrollView buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(2), dp(16), dp(32));

        // 1. Appearance (Light / Dark / System), Language & Device Adaptation
        body.addView(UiTheme.sectionHeading(this, theme, tr("settings_device_title")));
        LinearLayout devCard = UiTheme.insetGroupCard(this, theme, 16, 14);

        TextView tvThemeLbl = new TextView(this);
        tvThemeLbl.setText(tr("settings_theme"));
        tvThemeLbl.setTextColor(theme.textSecondary);
        tvThemeLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        devCard.addView(tvThemeLbl);

        LinearLayout themeRow = buildAppleSegmentedTrack();
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(6);
        trLp.bottomMargin = dp(14);
        String curTheme = config.getThemeMode();
        TextView btnSys = buildSegmentPill(tr("theme_system"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setThemeMode(GatewayConfig.THEME_SYSTEM);
                rebuildFullUi();
                scheduleUiRefresh();
            }
        });
        TextView btnLight = buildSegmentPill(tr("theme_light"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setThemeMode(GatewayConfig.THEME_LIGHT);
                rebuildFullUi();
                scheduleUiRefresh();
            }
        });
        TextView btnDark = buildSegmentPill(tr("theme_dark"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setThemeMode(GatewayConfig.THEME_DARK);
                rebuildFullUi();
                scheduleUiRefresh();
            }
        });
        styleSegment(btnSys, GatewayConfig.THEME_SYSTEM.equalsIgnoreCase(curTheme));
        styleSegment(btnLight, GatewayConfig.THEME_LIGHT.equalsIgnoreCase(curTheme));
        styleSegment(btnDark, GatewayConfig.THEME_DARK.equalsIgnoreCase(curTheme));
        themeRow.addView(btnSys, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        themeRow.addView(btnLight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        themeRow.addView(btnDark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        devCard.addView(themeRow, trLp);

        TextView tvLangLbl = new TextView(this);
        tvLangLbl.setText(tr("settings_language"));
        tvLangLbl.setTextColor(theme.textSecondary);
        tvLangLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        devCard.addView(tvLangLbl);

        LinearLayout langRow = buildAppleSegmentedTrack();
        LinearLayout.LayoutParams lrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrLp.topMargin = dp(6);
        lrLp.bottomMargin = dp(12);
        TextView btnZh = buildSegmentPill("简体中文", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setLanguage(I18n.LANG_ZH);
                rebuildFullUi();
                scheduleUiRefresh();
            }
        });
        TextView btnEn = buildSegmentPill("English", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setLanguage(I18n.LANG_EN);
                rebuildFullUi();
                scheduleUiRefresh();
            }
        });
        styleSegment(btnZh, I18n.LANG_ZH.equalsIgnoreCase(lang()));
        styleSegment(btnEn, I18n.LANG_EN.equalsIgnoreCase(lang()));
        langRow.addView(btnZh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        langRow.addView(btnEn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        devCard.addView(langRow, lrLp);

        devCard.addView(UiTheme.hairlineDivider(this, theme));
        DeviceProfile.FormFactor curFf = DeviceProfile.FormFactor.fromId(config.getDeviceProfileId());
        devCard.addView(buildSettingActionRow(
                tr("settings_device_profile"),
                curFf.getLabel(lang()),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showDeviceProfilePicker(); }
                }));

        devCard.addView(UiTheme.hairlineDivider(this, theme));
        devCard.addView(buildSettingActionRow(
                tr("settings_room_name") + " / " + tr("settings_device_id"),
                config.getRoomName() + " · " + config.getDeviceId(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showIdentityEditDialog(); }
                }));
        body.addView(devCard);

        // 2. Multi-Core Server Profiles
        body.addView(UiTheme.sectionHeading(this, theme, tr("settings_profiles_title")));
        LinearLayout profileCard = UiTheme.insetGroupCard(this, theme, 16, 14);

        profilesContainer = new LinearLayout(this);
        profilesContainer.setOrientation(LinearLayout.VERTICAL);
        profileCard.addView(profilesContainer);

        profileCard.addView(UiTheme.hairlineDivider(this, theme));
        addSwitchRow(profileCard, tr("settings_auto_failover"), config.isAutoFailoverEnabled(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        config.setAutoFailoverEnabled(isChecked);
                    }
                });

        LinearLayout profileActions = new LinearLayout(this);
        profileActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paLp.topMargin = dp(10);

        profileActions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.SETTINGS, tr("settings_add_profile"),
                theme.primaryBg, theme.primaryFg, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showCustomProfileDialog(null); }
                }));
        profileActions.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        profileActions.addView(UiTheme.iconTextPill(this, theme, Icons.Name.WIFI, tr("rooms_scan_short"),
                theme.bgElevated, theme.textSecondary, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runLanScanWithDialog(); }
                }));
        profileCard.addView(profileActions, paLp);
        body.addView(profileCard);

        // 3. Offline KWS & Acoustic Calibration (clean top-level actions + collapsible advanced tuning)
        body.addView(UiTheme.sectionHeading(this, theme, tr("settings_acoustics_title")));
        LinearLayout acCard = UiTheme.insetGroupCard(this, theme, 16, 14);

        LinearLayout calBtn = UiTheme.iconTextPill(this, theme, Icons.Name.MIC, tr("settings_calibrate"),
                theme.accentSoft, theme.accent, 0,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (gatewayService == null) return;
                        tvCalibrateStatus.setText(tr("settings_calibrating"));
                        tvCalibrateStatus.setVisibility(View.VISIBLE);
                        gatewayService.runAmbientCalibrationAsync(new GatewayService.CalibrationCallback() {
                            @Override
                            public void onCalibrationDone(final double floorDb, final float recGain) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing() || isDestroyed()) return;
                                        tvCalibrateStatus.setText(String.format(Locale.US,
                                                tr("settings_calibrated_fmt"), floorDb, recGain));
                                        if (tvGainValue != null) {
                                            tvGainValue.setText(String.format(Locale.US, "%.2fx", recGain));
                                        }
                                        if (sbUplinkGain != null) {
                                            sbUplinkGain.setProgress(Math.max(0, Math.min(70, Math.round((recGain - 0.5f) * 20f))));
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
        acCard.addView(calBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvCalibrateStatus = new TextView(this);
        tvCalibrateStatus.setTextColor(theme.textSecondary);
        tvCalibrateStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvCalibrateStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        csLp.topMargin = dp(6);
        acCard.addView(tvCalibrateStatus, csLp);

        final LinearLayout advContainer = new LinearLayout(this);
        advContainer.setOrientation(LinearLayout.VERTICAL);
        advContainer.setVisibility(advancedSettingsExpanded ? View.VISIBLE : View.GONE);

        final Switch swSwAec = addSwitchRow(advContainer, tr("settings_sw_aec"), config.isSoftwareAecEnabled(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        config.saveAcoustics(config.isUseVoiceCommAec(), isChecked,
                                config.getUplinkGain(), config.getKwsThreshold(), config.getKwsScore());
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                    }
                });
        advContainer.addView(UiTheme.hairlineDivider(this, theme));
        final Switch swHwAec = addSwitchRow(advContainer, tr("settings_hw_aec"), config.isUseVoiceCommAec(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        config.saveAcoustics(isChecked, swSwAec.isChecked(),
                                config.getUplinkGain(), config.getKwsThreshold(), config.getKwsScore());
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                    }
                });

        SliderWidgets gainW = addSliderRow(advContainer, tr("settings_uplink_gain"),
                String.format(Locale.US, "%.2fx", config.getUplinkGain()),
                70, Math.round((config.getUplinkGain() - 0.5f) * 20f),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                        tvGainValue.setText(String.format(Locale.US, "%.2fx", 0.5f + (p / 20.0f)));
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {
                        float gain = 0.5f + (s.getProgress() / 20.0f);
                        config.saveAcoustics(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(),
                                gain, config.getKwsThreshold(), config.getKwsScore());
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                    }
                });
        tvGainValue = gainW.valueView;
        sbUplinkGain = gainW.seekBar;

        SliderWidgets thrW = addSliderRow(advContainer, tr("settings_kws_threshold"),
                String.format(Locale.US, "%.2f", config.getKwsThreshold()),
                55, Math.round((config.getKwsThreshold() - 0.10f) * 100f),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                        tvKwsThrValue.setText(String.format(Locale.US, "%.2f", 0.10f + (p / 100.0f)));
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {
                        float thr = 0.10f + (s.getProgress() / 100.0f);
                        config.saveAcoustics(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(),
                                config.getUplinkGain(), thr, config.getKwsScore());
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                    }
                });
        tvKwsThrValue = thrW.valueView;
        sbKwsThr = thrW.seekBar;

        SliderWidgets scW = addSliderRow(advContainer, tr("settings_kws_score"),
                String.format(Locale.US, "%.2f", config.getKwsScore()),
                35, Math.round((config.getKwsScore() - 1.0f) * 10f),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                        tvKwsScoreValue.setText(String.format(Locale.US, "%.2f", 1.0f + (p / 10.0f)));
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {
                        float sc = 1.0f + (s.getProgress() / 10.0f);
                        config.saveAcoustics(config.isUseVoiceCommAec(), config.isSoftwareAecEnabled(),
                                config.getUplinkGain(), config.getKwsThreshold(), sc);
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                    }
                });
        tvKwsScoreValue = scW.valueView;
        sbKwsScore = scW.seekBar;

        advContainer.addView(UiTheme.hairlineDivider(this, theme));
        advContainer.addView(buildSettingActionRow(
                tr("settings_diag_logs"),
                gatewayService != null ? gatewayService.getKwsEngineMode() : "Northbound v2",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showDiagnosticsDialog(); }
                }));

        LinearLayout resetBtn = UiTheme.iconTextPill(this, theme, Icons.Name.REFRESH, tr("settings_reset_preset"),
                theme.bgElevated, theme.textSecondary, 0,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        float g = deviceProfile.recommendedGain;
                        float t = deviceProfile.recommendedKwsThreshold;
                        float s = 3.00f;
                        config.saveAcoustics(true, false, g, t, s);
                        swHwAec.setChecked(true);
                        swSwAec.setChecked(false);
                        if (sbUplinkGain != null) sbUplinkGain.setProgress(Math.round((g - 0.5f) * 20f));
                        if (sbKwsThr != null) sbKwsThr.setProgress(Math.round((t - 0.10f) * 100f));
                        if (sbKwsScore != null) sbKwsScore.setProgress(Math.round((s - 1.0f) * 10f));
                        if (gatewayService != null) gatewayService.applyAcousticsOnly();
                        Toast.makeText(MainActivity.this, tr("settings_preset_applied"), Toast.LENGTH_SHORT).show();
                    }
                });
        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rstLp.topMargin = dp(8);
        acCard.addView(resetBtn, rstLp);

        LinearLayout advDividerWrap = new LinearLayout(this);
        advDividerWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adLp.topMargin = dp(10);
        advDividerWrap.addView(UiTheme.hairlineDivider(this, theme));
        final View[] advToggleRowRef = new View[1];
        advToggleRowRef[0] = buildSettingActionRow(
                tr("settings_advanced_toggle"),
                advancedSettingsExpanded ? "▲" : "▼",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        advancedSettingsExpanded = !advancedSettingsExpanded;
                        advContainer.setVisibility(advancedSettingsExpanded ? View.VISIBLE : View.GONE);
                        if (advToggleRowRef[0] instanceof ViewGroup && ((ViewGroup) advToggleRowRef[0]).getChildCount() >= 2) {
                            View indicator = ((ViewGroup) advToggleRowRef[0]).getChildAt(1);
                            if (indicator instanceof TextView) {
                                ((TextView) indicator).setText(advancedSettingsExpanded ? "▲" : "▼");
                            }
                        }
                    }
                });
        advDividerWrap.addView(advToggleRowRef[0]);
        acCard.addView(advDividerWrap, adLp);
        acCard.addView(advContainer);
        body.addView(acCard);

        // 4. Background Keep-Alive, Kiosk Home Mode & In-App Update
        body.addView(UiTheme.sectionHeading(this, theme, tr("settings_system_title")));
        LinearLayout sysCard = UiTheme.insetGroupCard(this, theme, 16, 10);

        addSwitchRow(sysCard, tr("settings_kiosk_home"), config.isKioskHomeEnabled(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        setKioskHomeLauncherEnabled(isChecked);
                    }
                });
        sysCard.addView(UiTheme.hairlineDivider(this, theme));

        View batRow = buildSettingActionRow(
                tr("settings_battery_opt"),
                isIgnoringBatteryOptimizations() ? tr("settings_battery_ok") : tr("settings_battery_recommended"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { requestBatteryOptimizationExemption(); }
                });
        if (batRow instanceof ViewGroup && ((ViewGroup) batRow).getChildCount() >= 2) {
            View v2 = ((ViewGroup) batRow).getChildAt(1);
            if (v2 instanceof TextView) {
                tvBatteryStatus = (TextView) v2;
            }
        }
        sysCard.addView(batRow);
        sysCard.addView(UiTheme.hairlineDivider(this, theme));

        sysCard.addView(buildSettingActionRow(
                tr("settings_check_update"),
                "v" + AppUpdater.getCurrentVersionName(this),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { checkAppUpdates(); }
                }));
        body.addView(sysCard);

        sv.addView(body);
        refreshProfilesList();
        return sv;
    }

    private void setKioskHomeLauncherEnabled(boolean enabled) {
        config.setKioskHomeEnabled(enabled);
        try {
            ComponentName alias = new ComponentName(this, getPackageName() + ".KioskHomeAlias");
            getPackageManager().setComponentEnabledSetting(
                    alias,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }

    private static final class SliderWidgets {
        final TextView valueView;
        final SeekBar seekBar;
        SliderWidgets(TextView valueView, SeekBar seekBar) { this.valueView = valueView; this.seekBar = seekBar; }
    }

    private Switch addSwitchRow(LinearLayout parent, String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(theme.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        UiTheme.styleSwitch(sw, theme);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private SliderWidgets addSliderRow(
            LinearLayout parent, String label, String initialValue, int max, int progress,
            SeekBar.OnSeekBarChangeListener listener) {
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);

        TextView tvLbl = new TextView(this);
        tvLbl.setText(label);
        tvLbl.setTextColor(theme.textSecondary);
        tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hdr.addView(tvLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvVal = new TextView(this);
        tvVal.setText(initialValue);
        tvVal.setTextColor(theme.textPrimary);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvVal.setFontFeatureSettings("tnum");
        hdr.addView(tvVal);
        parent.addView(hdr, hlp);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, progress)));
        UiTheme.styleSeekBar(sb, theme);
        sb.setOnSeekBarChangeListener(listener);
        parent.addView(sb);
        return new SliderWidgets(tvVal, sb);
    }

    private View buildSettingActionRow(String title, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(11), 0, dp(11));
        row.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(8), 0));
        row.setClickable(true);
        row.setOnClickListener(onClick);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(theme.textPrimary);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(theme.textMuted);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvVal.setMaxLines(1);
        tvVal.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tvVal);

        ImageView chev = new ImageView(this);
        int csz = dp(15);
        chev.setImageDrawable(Icons.of(Icons.Name.CHEVRON_RIGHT, theme.borderActive, csz));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(csz, csz);
        clp.leftMargin = dp(4);
        row.addView(chev, clp);
        return row;
    }

    private void refreshProfilesList() {
        if (profilesContainer == null) return;
        profilesContainer.removeAllViews();
        String activeHost = config.getCoreHost();
        List<GatewayConfig.ServerProfile> profiles = config.getServerProfiles();

        if (profiles.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(tr("onboarding_desc"));
            empty.setTextColor(theme.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(dp(2), dp(6), dp(2), dp(10));
            profilesContainer.addView(empty);
            return;
        }

        for (int idx = 0; idx < profiles.size(); idx++) {
            final GatewayConfig.ServerProfile p = profiles.get(idx);
            if (idx > 0) {
                profilesContainer.addView(UiTheme.hairlineDivider(this, theme));
            }
            boolean active = p.matchesHost(activeHost);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            row.setBackground(UiTheme.rippleRoundedRect(theme, Color.TRANSPARENT, 0, dp(8), 0));
            row.setPadding(dp(2), dp(10), dp(2), dp(10));

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            TextView tvName = new TextView(this);
            String modeTag = p.manageOnly ? (" · " + tr("badge_manage_only")) : "";
            tvName.setText(p.name + modeTag + (!p.bearerToken.isEmpty() ? " · " + tr("profile_paired") : ""));
            tvName.setTextColor(active ? theme.accent : theme.textPrimary);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvName.setTypeface(active ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
            meta.addView(tvName);

            TextView tvHost = new TextView(this);
            String hostMeta = p.host + "  (GW :" + p.gatewayPort + " · Web :" + p.webPort + ")";
            if (!p.alternateHosts.isEmpty()) {
                hostMeta = hostMeta + "\n" + String.format(Locale.US, tr("settings_routes_fmt"),
                        TextUtils.join(", ", p.alternateHosts));
            }
            tvHost.setText(hostMeta);
            tvHost.setTextColor(theme.textMuted);
            tvHost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvHost.setFontFeatureSettings("tnum");
            meta.addView(tvHost);
            row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (active) {
                ImageView chk = new ImageView(this);
                int csz = dp(18);
                chk.setImageDrawable(Icons.of(Icons.Name.CHECK, theme.accent, csz));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(csz, csz);
                clp.rightMargin = dp(4);
                row.addView(chk, clp);
            }

            // Edit profile & Bearer token button
            row.addView(UiTheme.circleIconButton(this, theme, Icons.Name.KEY, theme.textSecondary, Color.TRANSPARENT, 0, 42,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showCustomProfileDialog(p);
                        }
                    }));

            ImageView del = UiTheme.circleIconButton(this, theme, Icons.Name.TRASH, theme.textMuted, Color.TRANSPARENT, 0, 42,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showTrackedDialog(UiTheme.dialog(MainActivity.this, theme)
                                    .setTitle(tr("settings_delete_profile"))
                                    .setMessage(tr("settings_delete_confirm") + "\n" + p.name + " (" + p.host + ")")
                                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            if (config.deleteServerProfile(p.host)) {
                                                if (gatewayService != null) gatewayService.reloadConfigAndReconnect();
                                                refreshProfilesList();
                                                scheduleUiRefresh();
                                            }
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .create());
                        }
                    });
            row.addView(del);

            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchActiveCore(p.name, p.host, p.gatewayPort, p.webPort, p.coreId, p.alternateHosts,
                            Boolean.valueOf(p.manageOnly));
                }
            });
            profilesContainer.addView(row);
        }
    }

    // =========================================================================
    // Dialogs & Quick Core Switcher
    // =========================================================================

    private void showCoreSwitcherDialog() {
        final List<GatewayConfig.ServerProfile> profiles = config.getServerProfiles();
        String activeHost = config.getCoreHost();
        List<CoreDiscovery.DiscoveredCore> discovered = gatewayService != null
                ? gatewayService.getLastDiscoveredCores() : java.util.Collections.<CoreDiscovery.DiscoveredCore>emptyList();

        if (profiles.isEmpty()) {
            runLanScanWithDialog();
            return;
        }

        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            GatewayConfig.ServerProfile p = profiles.get(i);
            boolean active = p.matchesHost(activeHost);
            String capTag = p.manageOnly ? (" · " + tr("badge_manage_only")) : "";
            for (CoreDiscovery.DiscoveredCore d : discovered) {
                boolean match = (!d.coreId.isEmpty() && d.coreId.equalsIgnoreCase(p.coreId))
                        || p.matchesHost(d.host);
                if (match) {
                    capTag = " · " + ((d.voiceGatewayOpen && !p.manageOnly) ? tr("badge_voice_manage") : tr("badge_manage_only"));
                    if (d.lanGatewayExempt && !d.authRequired) {
                        capTag = capTag + " · " + tr("badge_lan_exempt");
                    }
                    break;
                }
            }
            labels[i] = (active ? "● " : "") + p.name + " (" + p.host + ")" + capTag
                    + (!p.bearerToken.isEmpty() ? " · " + tr("profile_paired") : "");
        }

        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(tr("switcher_title"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        GatewayConfig.ServerProfile p = profiles.get(which);
                        switchActiveCore(p.name, p.host, p.gatewayPort, p.webPort, p.coreId, p.alternateHosts,
                                Boolean.valueOf(p.manageOnly));
                    }
                })
                .setPositiveButton(tr("settings_add_profile"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { showCustomProfileDialog(null); }
                })
                .setNeutralButton(tr("settings_scan_lan"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { runLanScanWithDialog(); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void runLanScanWithDialog() {
        runLanScanWithDialog(true);
    }

    private void runLanScanWithDialog(boolean showStartToast) {
        if (gatewayService == null) {
            pendingLanScanAction = PENDING_LAN_SCAN_DIALOG;
            Toast.makeText(this, tr("rooms_scanning"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (showStartToast) {
            Toast.makeText(this, tr("rooms_scanning"), Toast.LENGTH_SHORT).show();
        }
        gatewayService.discoverLanCoresAsync(false, new GatewayService.DiscoveryCallback() {
            @Override
            public void onCoresDiscovered(final List<CoreDiscovery.DiscoveredCore> cores) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        refreshProfilesList();
                        refreshWholeHomeRoomCards();
                        scheduleUiRefresh();
                        if (cores.isEmpty()) {
                            Toast.makeText(MainActivity.this, tr("settings_no_lan_core"), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] labels = new String[cores.size()];
                        for (int i = 0; i < cores.size(); i++) labels[i] = cores.get(i).toString();
                        showTrackedDialog(UiTheme.dialog(MainActivity.this, theme)
                                .setTitle(String.format(Locale.US, tr("rooms_found_fmt"), cores.size()))
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        CoreDiscovery.DiscoveredCore c = cores.get(which);
                                        switchActiveCore(c.displayLabel(), c.host, c.gatewayPort, c.webPort, c.coreId, c.alternateHosts,
                                                Boolean.valueOf(!c.voiceGatewayOpen));
                                        if (c.authRequired && config.getBearerTokenForHost(c.host).isEmpty()) {
                                            showActiveCoreTokenDialog();
                                        }
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .create());
                    }
                });
            }
        });
    }

    private void showCustomProfileDialog(final GatewayConfig.ServerProfile editing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(14), dp(20), dp(6));

        final EditText etName = UiTheme.themedEditText(this, theme, tr("settings_profile_name_hint"),
                editing != null ? editing.name : "");
        form.addView(etName);
        final EditText etHost = UiTheme.themedEditText(this, theme, tr("settings_profile_host_hint"),
                editing != null ? editing.host : "");
        form.addView(etHost);
        final EditText etGwPort = UiTheme.themedEditText(this, theme, tr("settings_profile_gw_hint"),
                String.valueOf(editing != null ? editing.gatewayPort : 8932));
        etGwPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(etGwPort);
        final EditText etWebPort = UiTheme.themedEditText(this, theme, tr("settings_profile_web_hint"),
                String.valueOf(editing != null ? editing.webPort : 8930));
        etWebPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(etWebPort);
        final EditText etToken = UiTheme.themedEditText(this, theme, tr("settings_profile_token_hint"),
                editing != null ? editing.bearerToken : "");
        form.addView(etToken);

        final Switch swManageOnly = new Switch(this);
        swManageOnly.setText(tr("settings_profile_manage_only"));
        swManageOnly.setTextColor(theme.textPrimary);
        swManageOnly.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        swManageOnly.setChecked(editing != null && editing.manageOnly);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.topMargin = dp(10);
        form.addView(swManageOnly, swLp);

        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(editing != null ? tr("settings_edit_profile") : tr("settings_add_profile"))
                .setView(form)
                .setPositiveButton(tr("settings_save_reconnect"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String host = etHost.getText().toString().trim();
                        if (host.isEmpty()) {
                            return;
                        }
                        String name = etName.getText().toString().trim();
                        int gw = parseIntSafe(etGwPort.getText().toString(), 8932);
                        int web = parseIntSafe(etWebPort.getText().toString(), 8930);
                        String tok = CoreApiClient.sanitizeBearerToken(etToken.getText().toString());
                        Boolean manageOnly = Boolean.valueOf(swManageOnly.isChecked());
                        if (editing != null) {
                            config.updateServerProfile(editing.host, name.isEmpty() ? host : name, host, gw, web, tok, manageOnly);
                        } else {
                            config.upsertProfileAndSelect(name.isEmpty() ? host : name, host, gw, web, tok, null, null, manageOnly);
                        }
                        boolean needsCoreIdPin = !tok.isEmpty()
                                && (editing == null || editing.coreId.isEmpty() || !editing.matchesHost(host));
                        if (needsCoreIdPin && gatewayService != null) {
                            final String savedHost = host;
                            gatewayService.verifyRoomTokenAsync(savedHost, web, tok, new GatewayService.TokenVerifyCallback() {
                                @Override
                                public void onTokenVerified(final CoreApiClient.TokenVerifyResult result, final String cleanToken) {
                                    if (result != null && result.ok && !result.coreId.isEmpty()) {
                                        config.setProfileBearerToken(savedHost, cleanToken, result.coreId);
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (!isFinishing() && !isDestroyed()) {
                                                    refreshWholeHomeRoomCards();
                                                }
                                            }
                                        });
                                    }
                                }
                            });
                        }
                        if (gatewayService != null) gatewayService.reloadConfigAndReconnect();
                        refreshProfilesList();
                        refreshWholeHomeRoomCards();
                        scheduleUiRefresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void showDeviceProfilePicker() {
        final DeviceProfile.FormFactor[] values = DeviceProfile.FormFactor.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].getLabel(lang());
        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(tr("settings_device_profile"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        config.setDeviceProfileId(values[which].id);
                        rebuildFullUi();
                        scheduleUiRefresh();
                    }
                })
                .create());
    }

    private void showIdentityEditDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(14), dp(20), dp(6));

        final EditText etRoom = UiTheme.themedEditText(this, theme, tr("settings_room_name"), config.getRoomName());
        form.addView(etRoom);
        final EditText etId = UiTheme.themedEditText(this, theme, tr("settings_device_id"), config.getDeviceId());
        form.addView(etId);

        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(tr("settings_device_title"))
                .setView(form)
                .setPositiveButton(tr("settings_save_reconnect"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        config.saveIdentity(etId.getText().toString().trim(), etRoom.getText().toString().trim());
                        if (gatewayService != null) gatewayService.reloadConfigAndReconnect();
                        rebuildFullUi();
                        scheduleUiRefresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void showDiagnosticsDialog() {
        StringBuilder sb = new StringBuilder();
        if (gatewayService != null) {
            sb.append("KWS Engine: ").append(gatewayService.getKwsEngineMode())
              .append(" (hits=").append(gatewayService.getKwsDetectionCount()).append(")\n")
              .append("Hardware Adapter: ").append(gatewayService.getHardwareAdapterName()).append("\n")
              .append("Mode: ").append(gatewayService.isManageOnlyMode()
                      ? (gatewayService.isHttpManageOnline() ? "Manage-Only" : "Manage-Only (Offline)")
                      : (gatewayService.isConnected() ? "Voice+Manage" : "Voice (Reconnecting)")).append("\n")
              .append("Form Factor: ").append(deviceProfile.effectiveFormFactor.id)
              .append(" (").append(deviceProfile.widthDp).append("x").append(deviceProfile.heightDp).append("dp)\n")
              .append("Audio Route: ").append(gatewayService.getOutputRouteName())
              .append(" · HW-AEC=").append(gatewayService.isHardwareAecActive())
              .append(" · SW-AEC=").append(gatewayService.isSoftwareAecActive()).append("\n")
              .append("KWS/SW AEC: ").append(gatewayService.getActiveAecTelemetry().formatCompact()).append("\n")
              .append("Blocks Sent: ").append(gatewayService.getBlocksSent()).append("\n\n")
              .append(gatewayService.getProtocolLogText());
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(theme.textPrimary);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        sv.addView(tv);
        showTrackedDialog(UiTheme.dialog(this, theme)
                .setTitle(tr("settings_diag_logs"))
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    private void checkAppUpdates() {
        Toast.makeText(this, tr("settings_check_update") + "...", Toast.LENGTH_SHORT).show();
        AppUpdater.checkAsync(this, config.getUpdateServerUrl(), new AppUpdater.Callback() {
            @Override
            public void onResult(final AppUpdater.UpdateInfo info) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (info.error != null) {
                            Toast.makeText(MainActivity.this, tr("settings_update_failed") + info.error, Toast.LENGTH_SHORT).show();
                        } else if (info.noReleaseYet) {
                            Toast.makeText(MainActivity.this,
                                    tr("settings_update_no_release") + AppUpdater.getCurrentVersionName(MainActivity.this),
                                    Toast.LENGTH_LONG).show();
                        } else if (info.available) {
                            showTrackedDialog(UiTheme.dialog(MainActivity.this, theme)
                                    .setTitle(tr("settings_update_available") + info.latestVersionName)
                                    .setMessage(info.notes)
                                    .setPositiveButton(tr("settings_update_download"), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            AppUpdater.openDownloadInBrowser(MainActivity.this, info.downloadUrl);
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .create());
                        } else {
                            Toast.makeText(MainActivity.this,
                                    tr("settings_update_latest") + AppUpdater.getCurrentVersionName(MainActivity.this),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isIgnoringBatteryOptimizations()) {
                // Android does not allow apps to self-revoke battery exemption; firing
                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS when already exempt is a silent no-op.
                // Open App Details (where Battery -> Unrestricted/Optimized/Restricted lives) with
                // fallback to the system Battery Optimization list.
                Toast.makeText(this, tr("settings_battery_manage_toast"), Toast.LENGTH_LONG).show();
                try {
                    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    details.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(details);
                } catch (Throwable ignored) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Throwable ignored2) {
                    }
                }
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Throwable ignored2) {
                    try {
                        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        details.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(details);
                    } catch (Throwable ignored3) {
                    }
                }
            }
        }
    }

    // =========================================================================
    // Coalesced Zero-Thrash Live Telemetry Rendering
    // =========================================================================

    @Override
    public void onTelemetryUpdated() { scheduleUiRefresh(); }

    private void scheduleUiRefresh() {
        if (uiRefreshPending.compareAndSet(false, true)) {
            runOnUiThread(uiRefreshRunnable);
        }
    }

    private static void setTextIfChanged(TextView tv, String text) {
        if (tv != null && text != null && !text.equals(tv.getText().toString())) {
            tv.setText(text);
        }
    }

    private void renderLiveTelemetryNow() {
        if (gatewayService == null || tvHeaderTitle == null) {
            return;
        }
        boolean voiceConnected = gatewayService.isConnected();
        boolean manageOnly = gatewayService.isManageOnlyMode();
        boolean manageOnline = manageOnly && gatewayService.isHttpManageOnline();
        boolean anyOnline = gatewayService.isAnyOnline();
        String transientErr = gatewayService.consumeTransientError();
        if (transientErr != null && !transientErr.isEmpty()) {
            Toast.makeText(this, transientErr, Toast.LENGTH_SHORT).show();
        }
        boolean muted = gatewayService.isMuted();
        String state = gatewayService.getAssistantState();
        double rms = gatewayService.getLastRmsDbfs();
        double floor = gatewayService.getLastNoiseFloorDbfs();
        double snr = gatewayService.getLastSnrDb();
        CoreApiClient.CoreSnapshot snap = gatewayService.getCoreSnapshot();
        boolean musicActive = gatewayService.isLocalMediaPlaying() || snap.musicActive;

        // Apple Navigation Bar Header + Onboarding / Auth Banners
        boolean configured = config.hasConfiguredCore();
        boolean authReq = configured && gatewayService.isAuthRequired();
        if (onboardingCard != null) {
            onboardingCard.setVisibility(configured ? View.GONE : View.VISIBLE);
        }
        if (authBannerCard != null) {
            authBannerCard.setVisibility(authReq ? View.VISIBLE : View.GONE);
        }

        setTextIfChanged(tvHeaderTitle, getActiveCoreDisplayName());
        String subText;
        if (!configured) {
            subText = tr("status_unconfigured");
        } else if (authReq) {
            subText = tr("status_auth_required");
        } else if (voiceConnected) {
            subText = tr("status_voice_manage") + " · " + config.getCoreHost();
        } else if (manageOnline) {
            subText = tr("status_manage_only") + " · " + config.getCoreHost();
        } else {
            subText = tr("status_offline");
        }
        setTextIfChanged(tvHeaderSub, subText);

        int dotColor = !configured ? theme.textMuted
                : (authReq ? theme.accentWarm
                : (anyOnline ? theme.statusOnline : theme.destructive));
        if (dotColor != lastDotColor && connectionDotBg != null) {
            lastDotColor = dotColor;
            connectionDotBg.setColor(dotColor);
        }

        // Home Stage: Orb + Automatic Music Immersion Transition
        String stateLabel = formatAssistantState(voiceConnected, manageOnline, muted, state, musicActive);
        if (heroOrb != null) {
            heroOrb.updateState(anyOnline, muted, state, musicActive, rms);
        }
        setTextIfChanged(tvOrbState, stateLabel);
        if (muted != lastMuteRendered) {
            lastMuteRendered = muted;
            if (btnMuteCircle != null) {
                btnMuteCircle.setImageDrawable(Icons.of(
                        Icons.Name.MIC_OFF,
                        muted ? theme.destructive : theme.textSecondary,
                        dp(20)));
                btnMuteCircle.setBackground(UiTheme.ripplePill(
                        theme,
                        muted ? theme.destructiveSoft : theme.bgSurface,
                        muted ? theme.destructive : theme.borderSubtle,
                        UiTheme.hairlinePx(this)));
            }
            if (btnMusicMute != null) {
                btnMusicMute.setImageDrawable(Icons.of(
                        Icons.Name.MIC_OFF,
                        muted ? theme.destructive : theme.textSecondary,
                        dp(18)));
                btnMusicMute.setBackground(UiTheme.ripplePill(
                        theme,
                        muted ? theme.destructiveSoft : theme.bgElevated,
                        muted ? theme.destructive : theme.borderSubtle,
                        UiTheme.hairlinePx(this)));
            }
        }

        updateMusicStagePresentation(musicActive, snap.musicPaused, snap.musicTitle, snap.musicSource);

        // Manage-Only pending confirmation prompt (P1-13)
        final GatewayService.PendingConfirmation pendingConfirm = gatewayService.getPendingConfirmation();
        if (pendingConfirm != null
                && config.activeProfileMatchesHost(pendingConfirm.host)
                && !pendingConfirm.actionId.equals(lastPromptedConfirmationId)) {
            lastPromptedConfirmationId = pendingConfirm.actionId;
            String msg = !pendingConfirm.summary.isEmpty()
                    ? pendingConfirm.summary
                    : (!pendingConfirm.tool.isEmpty() ? pendingConfirm.tool : pendingConfirm.actionId);
            showTrackedDialog(UiTheme.dialog(this, theme)
                    .setTitle(tr("confirm_action_title"))
                    .setMessage(msg)
                    .setPositiveButton(tr("confirm_action_approve"), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (gatewayService != null) {
                                gatewayService.decidePendingConfirmationAsync(pendingConfirm, true, makeActionToast());
                            }
                        }
                    })
                    .setNegativeButton(tr("confirm_action_deny"), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (gatewayService != null) {
                                gatewayService.decidePendingConfirmationAsync(pendingConfirm, false, makeActionToast());
                            }
                        }
                    })
                    .setNeutralButton(tr("tab_console"), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (gatewayService != null) {
                                gatewayService.dismissPendingConfirmation(pendingConfirm.actionId);
                            }
                            selectTab(TAB_CONSOLE);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            if (gatewayService != null) {
                                gatewayService.dismissPendingConfirmation(pendingConfirm.actionId);
                            }
                        }
                    })
                    .create());
        }

        // Clean Human-Only Slim Dialogue Ticker
        latestCleanTranscript = snap.transcript;
        renderSlimTickerIfChanged(snap.transcript);

        // StandBy Overlay (always pure OLED dark)
        if (standByActive && standByOverlay != null) {
            standByOverlay.update(lang(), anyOnline, muted, state, rms, stateLabel,
                    musicActive, snap.musicPaused, snap.musicTitle);
        }

        // Rooms Tab
        if (activeTab == TAB_ROOMS) {
            renderRoomsTab(snap, rms, floor, snr);
        }
    }

    private void renderSlimTickerIfChanged(List<CoreApiClient.ChatEntry> entries) {
        if (tickerContainer == null) return;
        StringBuilder fp = new StringBuilder();
        fp.append(entries.size()).append(':');
        if (!entries.isEmpty()) {
            CoreApiClient.ChatEntry last = entries.get(entries.size() - 1);
            fp.append(last.role).append('|').append(last.text).append('|').append(last.tsMs);
        }
        String nextFp = fp.toString();
        if (nextFp.equals(lastTranscriptFingerprint)) {
            return;
        }
        lastTranscriptFingerprint = nextFp;
        tickerContainer.removeAllViews();

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(tr("dialogue_ticker_hint"));
            empty.setTextColor(theme.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(22), dp(8), dp(22));
            tickerContainer.addView(empty);
            return;
        }

        int start = Math.max(0, entries.size() - 8);
        for (int i = start; i < entries.size(); i++) {
            CoreApiClient.ChatEntry e = entries.get(i);
            boolean isUser = "user".equalsIgnoreCase(e.role);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(2), dp(3), dp(2), dp(3));

            TextView prefix = new TextView(this);
            prefix.setText(isUser ? (tr("role_user") + "  ") : "HM  ");
            prefix.setTextColor(isUser ? theme.accent : theme.textMuted);
            prefix.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            prefix.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            line.addView(prefix);

            TextView body = new TextView(this);
            body.setText(e.text.replace('\n', ' '));
            body.setTextColor(isUser ? theme.textSecondary : theme.textPrimary);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            tickerContainer.addView(line);
        }

        tickerScroll.post(new Runnable() {
            @Override
            public void run() {
                if (tickerScroll != null) {
                    tickerScroll.fullScroll(View.FOCUS_DOWN);
                }
            }
        });
    }

    private void renderRoomsTab(CoreApiClient.CoreSnapshot snap, double rms, double floor, double snr) {
        updatingControlsFromSnapshot = true;
        try {
            if (roomsActiveControlsSection != null) {
                roomsActiveControlsSection.setVisibility(config.hasConfiguredCore() ? View.VISIBLE : View.GONE);
            }
            if (roomsArbSection != null) {
                boolean showArb = latestRoomSummaries.size() > 1
                        || (snap != null && snap.fleetNodes != null && !snap.fleetNodes.isEmpty());
                roomsArbSection.setVisibility(showArb ? View.VISIBLE : View.GONE);
            }
            double displayVol = gatewayService.getVolumeLinear();
            Double remoteVol = DeviceRoutingPolicy.resolveRemoteDisplayVolume(
                    gatewayService.isManageOnlyMode(), snap);
            if (remoteVol != null) {
                displayVol = remoteVol.doubleValue();
            }
            int volPct = (int) Math.round(displayVol * 100.0);
            if (!roomsVolumeSlider.isPressed()) {
                if (roomsVolumeSlider.getProgress() != volPct) {
                    roomsVolumeSlider.setProgress(volPct);
                }
                setTextIfChanged(tvRoomsVolumePct, volPct + "%");
            }
            setTextIfChanged(tvRoomsAcousticBar, String.format(Locale.US,
                    "RMS %.1f dBFS  ·  Floor %.1f dBFS  ·  SNR +%.1f dB", rms, floor, snr));

            styleSegment(tvModeDuplexHalf, !"full".equalsIgnoreCase(snap.duplexMode));
            styleSegment(tvModeDuplexFull, "full".equalsIgnoreCase(snap.duplexMode));
            if (swBargeIn != null && swBargeIn.isChecked() != snap.bargeIn) {
                swBargeIn.setChecked(snap.bargeIn);
            }
            if (swWakeAck != null && swWakeAck.isChecked() != snap.wakeAckEnabled) {
                swWakeAck.setChecked(snap.wakeAckEnabled);
            }
            styleSegment(tvArbOff, "off".equalsIgnoreCase(snap.wakeArbitration));
            styleSegment(tvArbObserve, "observe".equalsIgnoreCase(snap.wakeArbitration));
            styleSegment(tvArbEnforce, "enforce".equalsIgnoreCase(snap.wakeArbitration));

            StringBuilder devFp = new StringBuilder();
            for (CoreApiClient.DeviceInfo d : snap.devices) {
                devFp.append(d.id).append(':').append(d.connected).append(':').append(d.aecEnabled).append(';');
            }
            String nextDevFp = devFp.toString();
            if (!nextDevFp.equals(lastDevicesFingerprint)) {
                lastDevicesFingerprint = nextDevFp;
                devicesListContainer.removeAllViews();
                if (snap.devices.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText(config.getDeviceId() + " (" + config.getDeviceModel() + ")");
                    tv.setTextColor(theme.textSecondary);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    tv.setPadding(0, dp(6), 0, dp(4));
                    devicesListContainer.addView(tv);
                } else {
                    for (int i = 0; i < snap.devices.size(); i++) {
                        CoreApiClient.DeviceInfo d = snap.devices.get(i);
                        if (i > 0) devicesListContainer.addView(UiTheme.hairlineDivider(this, theme));
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(0, dp(8), 0, dp(8));

                        View dot = new View(this);
                        dot.setBackground(UiTheme.pill(d.connected ? theme.statusOnline : theme.textMuted, 0, 0));
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
                        dlp.rightMargin = dp(10);
                        row.addView(dot, dlp);

                        LinearLayout info = new LinearLayout(this);
                        info.setOrientation(LinearLayout.VERTICAL);
                        TextView tv = new TextView(this);
                        tv.setText(d.id + (d.model.isEmpty() ? "" : " · " + d.model));
                        tv.setTextColor(theme.textPrimary);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                        info.addView(tv);
                        if (d.audioProfile != null && !d.audioProfile.isEmpty()) {
                            TextView sub = new TextView(this);
                            sub.setText(d.audioProfile);
                            sub.setTextColor(theme.textMuted);
                            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                            info.addView(sub);
                        }
                        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        TextView tag = UiTheme.pillBadge(this, theme, d.aecEnabled ? "AEC" : "MIC",
                                theme.bgElevated, theme.textSecondary);
                        row.addView(tag);
                        devicesListContainer.addView(row);
                    }
                }
            }
        } finally {
            updatingControlsFromSnapshot = false;
        }
    }

    private String formatAssistantState(
            boolean voiceConnected, boolean manageOnline, boolean muted, String state, boolean musicActive) {
        if (!voiceConnected && !manageOnline) return tr("state_offline");
        if (muted && voiceConnected) return tr("state_muted");
        if ("listening".equalsIgnoreCase(state)) return tr("state_listening");
        if ("hearing".equalsIgnoreCase(state)) return tr("state_hearing");
        if ("thinking".equalsIgnoreCase(state)) return tr("state_thinking");
        if ("speaking".equalsIgnoreCase(state)) return tr("state_speaking");
        if (musicActive || "music".equalsIgnoreCase(state)) return tr("state_music");
        if (!voiceConnected && manageOnline) return tr("status_manage_only");
        return tr("state_idle");
    }

    private void requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<String>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && gatewayService != null) {
            gatewayService.reloadConfigAndReconnect();
        }
    }

    private void startAndBindGatewayService() {
        Intent intent = new Intent(this, GatewayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable ignored) {
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
