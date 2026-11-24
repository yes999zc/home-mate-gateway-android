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
        put("dialogue_ticker_hint", "说“你好小智”开始对话", "Say \"Ni Hao Xiao Zhi\" to begin");
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
        put("transcript_empty", "说出 “你好小智” 或轻触上方星云球开始对话", "Say \"Ni Hao Xiao Zhi\" or tap the particle orb to begin");
        put("role_user", "我", "You");
        put("role_assistant", "Home Mate", "Home Mate");

        // Rooms & Mode controls
        put("rooms_whole_home_title", "全屋 Core 与房间控制", "Whole-Home Cores");
        put("rooms_scan_btn", "扫描局域网", "Scan LAN");
        put("rooms_scanning", "正在扫描局域网全部 Core...", "Scanning LAN for all Cores...");
        put("rooms_found_fmt", "已发现 %d 台局域网 Core", "Found %d LAN Core(s)");
        put("rooms_cmd_btn", "发指令", "Command");
        put("rooms_switch_btn", "设为当前", "Set Active");
        put("rooms_token_btn", "配置令牌", "Set Token");
        put("rooms_token_title", "配置房间配对令牌", "Configure Room Bearer Token");
        put("rooms_cmd_title_fmt", "向 %s 发送指令", "Command %s");
        put("rooms_volume_title_fmt", "调节 %s 音量", "Volume · %s");
        put("rooms_cmd_sent", "指令已发送", "Command sent");
        put("rooms_cmd_failed", "指令发送失败", "Command failed");
        put("rooms_room_offline", "离线", "Offline");
        put("rooms_room_idle", "在线待命", "Online · Ready");
        put("rooms_devices_title", "当前 Core 已连接终端", "Connected Devices");
        put("rooms_fleet_title", "多房间集群节点", "Multi-Room Fleet");
        put("rooms_mode_title", "当前终端交互模式", "Interaction Mode");
        put("mode_duplex", "对话双工模式", "Duplex Mode");
        put("mode_half", "半双工 (日常)", "Half Duplex");
        put("mode_full", "全双工 (随时打断)", "Full Duplex");
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
        put("settings_sw_aec", "实验性软件回声消除 (NLMS)", "Experimental Software NLMS AEC");
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
        put("settings_battery_ok", "已豁免", "Exempt");
        put("settings_battery_recommended", "建议开启", "Recommended");
        put("settings_kiosk_home", "86 墙面屏桌面常驻模式 (Kiosk Launcher)", "86 Wall Panel Kiosk Home Mode");
        put("settings_check_update", "检查客户端更新", "Check App Updates");
        put("settings_update_available", "发现新版本 v", "New Version v");
        put("settings_update_download", "下载 APK", "Download APK");
        put("settings_update_latest", "已是最新版本: v", "Already latest: v");
        put("settings_update_failed", "检查更新失败: ", "Update check failed: ");
        put("settings_diag_logs", "开发者协议与 KWS 诊断日志", "Developer Protocol & KWS Diagnostics");
        put("settings_save_reconnect", "保存并立即重连", "Save & Reconnect");

        // Notifications & KWS Badges
        put("notif_boot_resume", "轻触恢复 Home Mate 离线语音守护", "Tap to resume Home Mate voice gateway");
        put("kws_jni", "JNI 离线唤醒", "JNI Offline KWS");
        put("kws_sidecar", "Sidecar 离线唤醒", "Sidecar KWS");
        put("kws_hybrid", "云端混合唤醒", "Hybrid Wake");
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
