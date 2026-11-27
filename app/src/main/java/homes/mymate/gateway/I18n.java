package homes.mymate.gateway;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bilingual Simplified Chinese & English localization catalog for Home Mate Android Client (v2.0).
 * 100% coverage across UI screens, dialogs, toasts, quick prompts, and system notifications.
 */
public final class I18n {

    public static final String LANG_ZH = "zh";
    public static final String LANG_EN = "en";

    private static final Map<String, String> ZH = new HashMap<String, String>();
    private static final Map<String, String> EN = new HashMap<String, String>();

    static {
        // Navigation tabs
        put("tab_voice", "对话", "Voice");
        put("tab_rooms", "全屋", "Rooms");
        put("tab_console", "控制台", "Console");
        put("tab_settings", "设置", "Settings");

        // Top bar & connection
        put("status_connecting", "连接中", "Connecting");
        put("status_connected", "在线", "Online");
        put("status_voice_manage", "在线 · 语音+管理", "Online · Voice & Manage");
        put("status_manage_only", "在线 · 管理模式", "Online · Manage Mode");
        put("status_offline", "离线重连中", "Reconnecting");
        put("voice_disconnected_reconnect", "语音网关未连接，正在重连，请稍后再试", "Voice gateway disconnected — reconnecting, please retry");
        put("status_unconfigured", "未配置 Core · 点击扫描", "No Core · Tap to Scan");
        put("status_auth_required", "需要配对令牌 (401)", "Pairing Token Required (401)");
        put("badge_voice_manage", "语音+管理", "Voice + Manage");
        put("badge_manage_only", "仅管理", "Manage Only");
        put("badge_lan_exempt", "局域网免密", "LAN Exempt");
        put("badge_active", "当前", "Active");
        put("switcher_title", "切换房间 / Core", "Switch Room / Core");
        put("standby_mode", "床头待机", "StandBy");
        put("exit_standby", "退出待机", "Exit StandBy");
        put("onboarding_title", "连接你的 Home Mate 大脑", "Connect to Home Mate Core");
        put("onboarding_desc", "自动扫描局域网内的 Home Mate Core，或手动输入主机地址与端口", "Scan your local network for Home Mate Core, or enter a custom host and port");
        put("auth_banner_title", "当前 Core 需要配对令牌", "Pairing Token Required");
        put("auth_banner_desc", "请输入管理员令牌，或在 Core 控制台设置中开启「局域网免密」", "Enter your Bearer token, or enable LAN Exemption in Core Console Settings");
        put("auth_banner_btn", "输入令牌", "Enter Token");
        put("auth_action_required_toast", "当前操作需要配对令牌 (401)，请点击主页横幅输入令牌", "Pairing token required (401) — tap the banner to enter your Bearer token");

        // Assistant states
        put("state_idle", "待命", "Ready");
        put("state_listening", "正在聆听", "Listening");
        put("state_hearing", "捕捉语音中", "Hearing");
        put("state_thinking", "思考中", "Thinking");
        put("state_speaking", "正在回复", "Speaking");
        put("state_music", "正在播放", "Playing");
        put("state_muted", "已静音", "Muted");
        put("state_offline", "等待连接", "Offline");
        put("history_title", "对话记录", "Conversation");
        put("history_empty", "暂无对话记录", "No messages yet");
        put("history_subtitle_fmt", "%s · %d 条记录", "%s · %d messages");
        put("history_refreshing", "%s · 正在刷新...", "%s · Refreshing...");
        put("history_empty_sub", "说「你好高达」唤醒，或在首页输入指令即可开始对话", "Say \"Hey Mate\" / \"Ni Hao Gao Da\" or type a message on the Voice tab to start");
        put("history_refresh", "刷新", "Refresh");
        put("history_earlier_session", "更早对话", "Earlier conversation");
        put("history_current_session", "当前对话", "Current conversation");
        put("history_auth_required", "需要配对令牌才能查看对话记录", "Pairing token required to view conversation history");
        put("history_clear_title", "清空对话记录", "Clear Conversation History");
        put("history_clear_confirm", "确定清空全部对话记录？首页底部的对话记录也会同步清除。", "Clear all conversation history? The home screen preview will also be cleared.");
        put("history_clear_btn", "清空", "Clear");
        put("history_cleared", "已清空对话记录", "Conversation history cleared");
        put("history_clear_failed", "清空对话失败", "Failed to clear conversation");
        put("dialogue_ticker_hint", "说“你好高达”开始对话", "Say \"Hey Mate\" or tap to begin");
        put("dialogue_tap_more", "查看全部", "History");

        // Voice stage
        put("orb_hint_idle", "轻触唤醒 · 长按停止播放", "Tap to wake · Hold to stop");
        put("orb_hint_active", "轻触继续对话 · 长按立即退下", "Tap to prompt · Hold to stop");
        put("btn_wake", "唤醒", "Wake");
        put("btn_stop", "退下", "Stop");
        put("btn_mute", "静音", "Mute");
        put("btn_unmute", "取消静音", "Unmute");
        put("input_hint", "输入文字直接对 Home Mate 说话...", "Type a message to Home Mate...");
        put("btn_send", "发送", "Send");
        put("now_playing", "正在播放", "Now Playing");
        put("music_paused", "已暂停", "Paused");
        put("quick_weather", "今天天气", "Weather");
        put("quick_weather_cmd", "今天天气怎么样", "What is the weather today?");
        put("quick_music", "放首歌", "Play Music");
        put("quick_music_cmd", "放一首周杰伦的简单爱", "Play some relaxing music");
        put("quick_next", "下一首", "Next Track");
        put("quick_next_cmd", "下一首", "Next track");
        put("quick_pause", "暂停播放", "Pause");
        put("quick_pause_cmd", "暂停播放", "Pause playback");
        put("quick_joke", "讲个笑话", "Tell a Joke");
        put("quick_joke_cmd", "讲个笑话", "Tell me a short joke");
        put("transcript_empty", "说出 “你好高达” 或轻触上方星云球开始对话", "Say \"Hey Mate\" / \"Ni Hao Gao Da\" or tap the particle orb to begin");
        put("role_user", "我", "You");
        put("role_assistant", "Home Mate", "Home Mate");

        // Rooms & Mode controls
        put("rooms_whole_home_title", "全屋设备", "Rooms");
        put("rooms_count_fmt", "全屋设备 · %d 台", "Rooms · %d");
        put("rooms_scan_btn", "扫描局域网", "Scan LAN");
        put("rooms_scan_short", "扫描", "Scan");
        put("rooms_scan_primary", "扫描局域网设备", "Scan LAN Devices");
        put("rooms_add_manual", "手动添加", "Add Manually");
        put("rooms_scanning", "正在扫描局域网全部 Core...", "Scanning LAN for all Cores...");
        put("rooms_found_fmt", "已发现 %d 台局域网 Core", "Found %d LAN Core(s)");
        put("rooms_cmd_btn", "发指令", "Command");
        put("rooms_switch_btn", "设为当前", "Set Active");
        put("rooms_switch_here_btn", "切换至此", "Switch");
        put("rooms_switch_short", "切换", "Switch");
        put("rooms_token_btn", "配置令牌", "Set Token");
        put("rooms_enter_token_btn", "输入配对令牌", "Enter Token");
        put("rooms_token_title", "配置房间配对令牌", "Configure Room Bearer Token");
        put("rooms_token_desc", "请输入首次配对 Core 时保存的 Bearer 令牌（如已忘记，可在 Core 控制台「设置 → 安全与令牌」点击轮换生成新令牌）", "Enter the Bearer token saved during Core pairing (if lost, rotate a new one in Core Console → Settings → Security & Token)");
        put("rooms_token_verify_btn", "验证并配对", "Verify & Pair");
        put("rooms_token_verifying", "正在验证令牌...", "Verifying token...");
        put("rooms_token_verified_ok", "配对成功", "Paired successfully");
        put("rooms_token_empty_err", "请输入配对令牌", "Please enter a pairing token");
        put("rooms_token_invalid_err", "令牌无效，请核对设备令牌后重试", "Invalid token — please double-check and try again");
        put("rooms_token_unreachable_err", "无法连接设备，请检查局域网连接", "Unable to reach device — check your local network");
        put("rooms_need_token", "需要配对令牌", "Pairing token required");
        put("rooms_active_online", "当前连接 · 在线", "Active · Online");
        put("rooms_vol_short", "音量", "Vol");
        put("rooms_active_controls_title", "当前房间控制", "Active Room Controls");
        put("rooms_details_toggle", "设备与声学详情", "Devices & Acoustics");
        put("rooms_cmd_title_fmt", "向 %s 发送指令", "Command %s");
        put("rooms_volume_title_fmt", "调节 %s 音量", "Volume · %s");
        put("rooms_cmd_sent", "指令已发送", "Command sent");
        put("rooms_cmd_failed", "指令发送失败", "Command failed");
        put("rooms_room_offline", "离线", "Offline");
        put("rooms_room_idle", "在线 · 待机", "Online · Idle");
        put("rooms_devices_title", "当前 Core 已连接终端", "Connected Devices");
        put("rooms_fleet_title", "多房间集群节点", "Multi-Room Fleet");
        put("rooms_mode_title", "当前终端交互模式", "Interaction Mode");
        put("mode_duplex", "对话双工模式", "Duplex Mode");
        put("mode_half", "半双工 (日常)", "Half Duplex");
        put("mode_full", "全双工 (开发中)", "Full Duplex (In Dev)");
        put("mode_full_dev_hint", "全双工仍在开发中，日常推荐使用半双工", "Full-duplex is in development; Half-Duplex is recommended daily");
        put("mode_full_dev_confirm_title", "全双工模式开发中", "Full-Duplex In Development");
        put("mode_full_dev_confirm_msg", "全双工随时打断功能仍在持续开发中，可能出现回声或误打断。日常使用推荐保持「半双工」。确定要体验全双工吗？", "Full-duplex interruption is still in active development and may experience echo or false triggers. Half-Duplex is recommended for daily use. Try it anyway?");
        put("mode_full_dev_confirm_ok", "继续体验", "Try Anyway");
        put("mode_barge_in", "播放中语音打断", "Barge-In During Playback");
        put("mode_wake_ack", "唤醒应答提示音", "Wake Ack Tone");
        put("mode_wake_arb", "多房间唤醒仲裁", "Multi-Room Arbitration");
        put("arb_off", "关闭", "Off");
        put("arb_observe", "观察", "Observe");
        put("arb_enforce", "强制", "Enforce");
        put("mode_audio_title", "本机声学与音量", "Local Acoustics & Volume");
        put("volume_label", "媒体与语音音量", "Speaker Volume");
        put("meter_label", "实时声学监测", "Live Acoustic Monitor");
        put("no_fleet_nodes", "当前为单 Core 运行模式（无邻接节点）", "Single-Core mode (no peer fleet nodes)");
        put("mode_saved", "交互模式已同步至 Core", "Mode applied to Core");
        put("mode_save_failed", "同步模式失败", "Failed to apply mode");

        // Console
        put("console_reload", "刷新", "Reload");
        put("console_open_ext", "浏览器打开", "Open External");
        put("console_unavailable", "当前系统未安装 WebView 组件，请使用外部浏览器打开", "System WebView unavailable; please open in external browser");
        put("console_unconfigured", "请先扫描局域网或自定义添加 Home Mate Core", "Please scan your LAN or add a Home Mate Core first");

        // Settings
        put("settings_profiles_title", "Core 服务器档案", "Core Server Profiles");
        put("settings_auto_failover", "局域网 / Tailscale 自动故障切换", "Auto-Failover (LAN / Tailscale)");
        put("settings_scan_lan", "扫描局域网 Core", "Scan LAN");
        put("settings_add_profile", "自定义添加大脑", "Add Custom Core");
        put("settings_edit_profile", "编辑 Core 档案", "Edit Core Profile");
        put("settings_delete_profile", "删除档案", "Delete Profile");
        put("settings_delete_confirm", "确定删除该服务器档案？", "Remove this server profile?");
        put("settings_profile_name_hint", "档案名称 (如 家里 NAS)", "Profile Name (e.g. Home NAS)");
        put("settings_profile_host_hint", "主机 IP 或域名 (如 192.168.1.10)", "Host IP or hostname (e.g. 192.168.1.10)");
        put("settings_profile_gw_hint", "网关端口 (8932)", "Gateway Port (8932)");
        put("settings_profile_web_hint", "Web API 端口 (8930)", "Web API Port (8930)");
        put("settings_profile_token_hint", "配对令牌 (可选 Bearer Token)", "Bearer Token (optional)");
        put("settings_profile_manage_only", "仅管理模式 (仅 HTTP，不连接语音网关)", "Manage-Only Mode (HTTP only, no voice gateway)");
        put("settings_routes_fmt", "备用路由: %s", "Fallback route: %s");
        put("settings_advanced_toggle", "高级声学与开发者诊断", "Advanced Acoustics & Diagnostics");
        put("profile_paired", "已配对", "Paired");
        put("settings_no_lan_core", "局域网未发现 Home Mate Core (:8930)", "No LAN Home Mate Core found on :8930");
        put("settings_device_title", "外观与设备适配", "Appearance & Device");
        put("settings_theme", "外观模式", "Appearance");
        put("theme_system", "跟随系统", "System");
        put("theme_light", "浅色白天", "Light");
        put("theme_dark", "深色夜间", "Dark");
        put("settings_device_profile", "设备形态档案", "Device Form Factor");
        put("settings_language", "界面语言 / Language", "Language / 界面语言");
        put("settings_room_name", "房间名称", "Room Name");
        put("settings_device_id", "设备 ID", "Device ID");
        put("settings_acoustics_title", "离线 KWS 与回声消除", "Offline KWS & Echo Cancellation");
        put("settings_sw_aec", "实验性软件回声消除 (PBFDAF)", "Experimental Software PBFDAF AEC");
        put("settings_hw_aec", "硬件语音通信回声消除", "Hardware VoiceComm AEC");
        put("settings_uplink_gain", "麦克风增益", "Mic Uplink Gain");
        put("settings_kws_threshold", "KWS 触发阈值", "KWS Threshold");
        put("settings_kws_score", "KWS 关键词加权", "KWS Score Boost");
        put("settings_calibrate", "3 秒一键环境底噪校准", "3s Ambient Noise Calibration");
        put("settings_calibrating", "正在采样环境底噪 (请保持安静 3 秒)...", "Sampling ambient noise (stay quiet 3s)...");
        put("settings_calibrated_fmt", "环境底噪 %.1f dBFS  →  推荐增益 %.2fx", "Ambient %.1f dBFS  →  Gain %.2fx");
        put("settings_reset_preset", "恢复设备推荐声学预设", "Reset to Recommended Preset");
        put("settings_preset_applied", "已恢复推荐声学参数", "Recommended acoustic preset applied");
        put("settings_system_title", "后台常驻与系统", "Background & System");
        put("settings_battery_opt", "关闭电池优化 (后台防杀)", "Disable Battery Optimization");
        put("settings_battery_ok", "已豁免 · 点击管理", "Exempt · Manage");
        put("settings_battery_recommended", "建议开启", "Recommended");
        put("settings_battery_manage_toast", "请在系统「电池」中调整或关闭后台不受限制", "Adjust or turn off battery exemption in System App Info → Battery");
        put("settings_kiosk_home", "86 墙面屏桌面常驻模式 (Kiosk Launcher)", "86 Wall Panel Kiosk Home Mode");
        put("settings_check_update", "检查更新 (GitHub)", "Check Updates (GitHub)");
        put("settings_update_available", "发现新版本 v", "New Version v");
        put("settings_update_download", "下载 APK", "Download APK");
        put("settings_update_latest", "已是最新版本: v", "Already latest: v");
        put("settings_update_no_release", "GitHub 暂未发布正式版，当前为测试版 v", "No public GitHub Release yet; running preview v");
        put("settings_update_failed", "检查更新失败: ", "Update check failed: ");
        put("settings_diag_logs", "开发者协议与 KWS 诊断日志", "Developer Protocol & KWS Diagnostics");
        put("settings_save_reconnect", "保存并立即重连", "Save & Reconnect");

        // Notifications & KWS Badges
        put("notif_boot_resume", "轻触恢复 Home Mate 离线语音守护", "Tap to resume Home Mate voice gateway");
        put("kws_jni", "JNI 离线唤醒", "JNI Offline KWS");
        put("kws_sidecar", "Sidecar 离线唤醒", "Sidecar KWS");
        put("kws_hybrid", "云端混合唤醒", "Hybrid Wake");

        // Manage-Only Confirmation Prompt
        put("confirm_action_title", "操作需要确认", "Action Requires Confirmation");
        put("confirm_action_approve", "批准并执行", "Approve & Execute");
        put("confirm_action_deny", "拒绝", "Reject");
        put("confirm_action_pending", "待确认操作：", "Pending confirmation: ");
        put("confirm_action_pending_generic", "该操作需要你的确认后才会执行。", "This action requires your confirmation before executing.");
        put("confirm_action_approved", "已批准并执行该操作。", "Action approved and executed.");
        put("confirm_action_denied", "已拒绝该操作。", "Action rejected.");
        put("confirm_action_approve_failed", "批准/执行操作失败（请检查配对令牌或前往控制台）。", "Failed to approve/execute action (check pairing token or open Console).");
        put("confirm_action_deny_failed", "拒绝操作失败。", "Failed to reject action.");
    }

    private static void put(String key, String zh, String en) {
        ZH.put(key, zh);
        EN.put(key, en);
    }

    private I18n() {
    }

    public static String t(String lang, String key) {
        boolean isEn = LANG_EN.equalsIgnoreCase(lang);
        Map<String, String> table = isEn ? EN : ZH;
        String val = table.get(key);
        if (val != null) {
            return val;
        }
        String fallback = ZH.get(key);
        return fallback != null ? fallback : key;
    }

    public static String defaultLanguage() {
        Locale loc = Locale.getDefault();
        if (loc != null && "en".equalsIgnoreCase(loc.getLanguage())) {
            return LANG_EN;
        }
        return LANG_ZH;
    }
}
