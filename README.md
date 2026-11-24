<div align="center">
  <h1>Home Mate Android Universal Client &amp; Voice Gateway</h1>
  <p>
    <strong>一个 APK 自适应全品类安卓设备的 Home Mate 通用客户端与远场语音网关（v2.3.0）</strong>
  </p>
  <p>
    <em>One adaptive, zero-dependency APK turning Android phones, tablets, smart displays, 86 wall panels, and Android speakers into Home Mate voice companions and multi-room controllers.</em>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Version-v2.3.0-0071E3?style=flat-square" alt="Version" />
    <img src="https://img.shields.io/badge/Android-5.0%20(API%2021)%20~%2016-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 5-16" />
    <img src="https://img.shields.io/badge/Protocol-Northbound%20v2-2563EB?style=flat-square" alt="Northbound v2" />
    <img src="https://img.shields.io/badge/License-AGPL--3.0--only%20%2B%20Commercial-16A34A?style=flat-square" alt="License" />
  </p>
</div>

---

## 生态定位与前置条件 (Prerequisites)

本仓库是 **Home Mate** 开源生态三大核心仓库中的 **Android 通用端与语音网关**：

| 仓库 | 定位 | 关系 |
|---|---|---|
| **[`yes999zc/home-mate`](https://github.com/yes999zc/home-mate)** | 核心大脑（`hm-core` + Vue 3 控制台） | **必需前置条件**：需先在 NAS / Docker / 主机 / 音箱上运行至少一台 `hm-core` |
| **[`yes999zc/home-mate-gateway-linux`](https://github.com/yes999zc/home-mate-gateway-linux)** | Linux 多板卡硬件语音网关 | 适用于 Echo Dot 2 / 小米小爱 Pro (LX06) 等 Linux 音箱 |
| **`yes999zc/home-mate-gateway-android`**（本仓） | Android 通用自适应 APK | 连接局域网或 Tailscale 中的 `hm-core`，兼具语音端 + 多房间控制器 |

### 使用前置条件
1. **一台正在运行的 Home Mate Core（`hm-core`）**：
   - 若部署在家庭 NAS / Docker / Mac / Linux 服务器上，需确保映射 HTTP 管理口（默认 `:8930`）与 Northbound 语音网关口（默认 `:8932`，设置 `HM_GATEWAY_ALLOW_LAN=1` 以接收局域网安卓语音端接入），并建议开放 UDP `:8933` 以启用一键自动发现。
   - 若连接独立运行的硬件音箱节点（默认仅开放 `:8930`、关闭外部 `:8932`），APK 会自动以 **管理模式（Manage-Only）** 在线，支持文字指令、媒体控制与内嵌控制台，不会对关闭的 `:8932` 刷重连告警。
2. **认证方式（二选一）**：
   - **安全配对 Token（推荐）**：在 `hm-core` Web 控制台生成家庭管理员 Token，在 App 卡片或「自定义添加核心」中填入；TCP `:8932` `Hello` 帧与 HTTP `:8930` API 统一携带该 Token。
   - **局域网免密豁免（方案 1A）**：在 `hm-core` 控制台「安全设置」开启「局域网可信设备免密豁免（`lan_gateway_exempt`）」后，同局域网 RFC1918 或 Tailscale (`100.64.0.0/10`) 下的安卓端无需 Token 即可直接连接语音网关与日常对话/媒体接口（高危管理接口仍严格要求管理员 Token）。

---

## 自适应设备矩阵 (Universal Adaptive Device Matrix)

单个 APK 自动识别屏幕尺寸、DPI、宽高比、横竖屏与硬件 ABI（`arm64-v8a` + `armeabi-v7a`），无需针对不同设备下载不同版本：

| 设备形态 | 系统要求 | 自适应布局与特性 |
|---|---|---|
| **智能手机 / 折叠屏** | Android 8 ~ 16 | 底部 Apple 风格 3-Tab（语音 / 房间 / 设置）、3D 粒子星云球、对话气泡流、完整会话抽屉 |
| **平板电脑 / 横屏桌面时钟** | Android 7 ~ 16 | 横屏分栏布局、StandBy 纯黑 OLED 床头时钟、35Hz 律动音乐播放舞台 |
| **带屏智能音箱（小爱触屏等）** | Android 6 ~ 14 | 远场离线唤醒词（Sherpa-ONNX Zipformer int8 JNI）、免提全语音对话与打断 |
| **4 寸 86 墙面智能中控屏** | Android 6 ~ 11 | 紧凑单屏自适应缩放、房间快捷控播与语音快捷入口 |
| **无屏安卓音箱（斐讯 R1 等）** | Android 5.0 (API 21)+ | 前台保活服务、纯语音远场网关 + 局域网管理接入 |

---

## 核心特性 (`v2.3.0`)

- **零个人配置开箱引导**：全新安装干净启动，提供一键「扫描局域网核心」与「手动添加核心（支持 IP / 域名 / Tailscale + 端口 + Token）」引导卡片。
- **`core_id` 多路由去重与局域网优先回切**：自动识别同一台 `hm-core` 的局域网 IP 与 Tailscale IP，合并为单一核心档案并保留备用路由；网络切换时自动优先回切低延迟局域网，断连时无缝切换备用地址。
- **双模式稳态连接（Voice + Manage / Manage-Only）**：基于 `/api/v0/auth` 与 UDP `:8933` 发现探测，彻底取消无意义原始 TCP `:8932` 探测；鉴权拒绝时自动 60 秒退避并在顶部给出清晰内联提示。
- **离线 KWS 与静音防误触**：内置 `arm64-v8a` / `armeabi-v7a` Sherpa-ONNX 离线唤醒词引擎，关闭系统 AGC 并通过本地 1.6 秒唤醒前滚环形缓冲 + 门控上行，彻底消除空闲麦克风反复触发空语音识别（`"嗯。"`）的问题。
- **Apple 设计语言双主题与中英双语**：完整支持浅色 / OLED 深色 / 跟随系统三态切换，全界面 100% 简体中文 / English 双语，高级声学参数与调试日志默认折叠收口。

---

## 本地构建与测试 (Build & Verify)

> 前置要求：JDK 17+、Android SDK（`ANDROID_HOME` 指向已安装 `build-tools` 与 `platforms/android-34` 或更高版本的目录）。

```bash
# 1. 运行全部本地 JVM 单元与协议回归测试（Northbound v2 + 发现去重 + 音频门控）
./tools/test_jvm.sh

# 2. 构建签名 APK（输出 slim、arm64-v8a 全量与通用 arm64+armv7 全量 hm.apk）
./tools/build_apk.sh
```

---

## 许可协议 (License)

本项目与 [`yes999zc/home-mate`](https://github.com/yes999zc/home-mate) 保持统一双许可策略：

- **开源使用**：遵循 **GNU Affero General Public License v3.0 (`AGPL-3.0-only`)**（见 [`LICENSE`](LICENSE)），面向个人、家庭自托管、学术与开源社区免费开放。
- **Northbound v2 协议例外**：线协议规范与契约定义另行适用 **Apache License 2.0**（见 [`LICENSE-PROTOCOL-APACHE-2.0`](LICENSE-PROTOCOL-APACHE-2.0)），允许第三方硬件或自定义客户端自由对接。
- **商业授权 / OEM 预装**：如需商业硬件预装、OEM 分发或闭源衍生，请联系版权方：`zhangcong1985@gmail.com` / `yes999zc@163.com`。
- 第三方开源组件与模型致谢见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
