<div align="center">

# 央视网 TV

**CCTV View for Android TV**

在电视上轻松收看央视与卫视直播的 Android TV 应用。

[![Release](https://img.shields.io/github/v/release/RaysonStudio/cctv-view?logo=github&color=blue)](https://github.com/RaysonStudio/cctv-view/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-brightgreen)](https://developer.android.com/tv)
[![Android](https://img.shields.io/badge/Android-4.2%2B-green)](https://developer.android.com/about/versions/android-4.2)
[![License](https://img.shields.io/github/license/RaysonStudio/cctv-view?color=orange)](LICENSE)
[![Stars](https://img.shields.io/github/stars/RaysonStudio/cctv-view?style=social)](https://github.com/RaysonStudio/cctv-view/stargazers)
[![Linux DO](https://img.shields.io/badge/Linux%20DO-社区讨论-blue)](https://linux.do)

</div>

---

## 项目简介

央视网 TV 是一款面向 Android TV / 电视盒子的直播应用。它基于 **腾讯 X5 (TBS) 内核**加载 [央视频](https://www.yangshipin.cn/) 网页并播放直播流，同时针对电视大屏做了大量改造：

- **全屏净化**：在 HTML `<head>` 阶段同步注入全屏 CSS，从渲染管线源头隐藏导航、广告、侧栏等非播放器元素
- **快速换台**：内存 + 磁盘双层缓存主文档 HTML，换台不再在渲染关键路径上同步抓网
- **低延迟直播**：包装页面内的 `Hls` 构造器，覆盖直播同步与缓冲策略，压低延迟并限制内存占用
- **遥控器优先**：方向键、菜单键、确定键、返回键全部按电视使用习惯重新映射
- **节目单 (EPG)**：侧边栏直接显示每个频道「正在播出」的节目名

> 本项目由 AI 辅助开发完成。

## 功能特性

| 功能 | 说明 |
|------|------|
| 频道覆盖 | 内置 **55** 个频道：CCTV-1 ~ CCTV-17、CCTV-5+、CCTV-16 (HD/4K)、CCTV-4K / 8K、CGTN 及 30 个省级卫视 |
| 收藏夹 | 侧边栏顶部固定展示收藏频道，遥控器右键一键加/取消收藏，收藏顺序持久化保存 |
| 节目单 (EPG) | 后台拉取并解码央视频 EPG 接口（protobuf），侧边栏每行显示「正在播出」节目名，超长名称跑马灯滚动 |
| 全屏优化 | 注入自定义 CSS，自动隐藏网页边框、导航、广告、控制条，视频占满屏幕 |
| 同步 CSS 注入 | 通过 `shouldInterceptRequest` 拦截主文档，在 `<head>` 阶段写入全屏样式，避免与页面渲染竞争 |
| 主文档双层缓存 | 内存缓存即时返回，磁盘缓存支撑冷启动；后台定时刷新，缓存失效不影响播放 |
| 低延迟播放 | 覆盖 `hls.js` 直播同步配置（`liveSyncDurationCount` 等），回收后台缓冲，降低延迟并抑制内存膨胀 |
| X5 (TBS) 内核 | 启动即初始化腾讯 X5 内核并按设备 CPU 架构自动下载；内核未就绪时自动回退系统 WebView |
| 资源拦截 | 拦截监控、统计、第三方图片/字体等非必要请求，省流量并减少干扰 |
| 稳定性增强 | 提供视频加载超时检测、JS 层重载与整页刷新重试机制（当前版本默认关闭轮询，代码保留可随时启用） |

## 系统要求

| 项目 | 值 |
|------|-----|
| 最低系统版本 | Android 4.2 (API 17) |
| 编译 SDK | Android SDK 36 (compileSdk 36) |
| 目标 SDK | API 28 (targetSdk 28) |
| 构建工具 | JDK 21、Gradle + Kotlin DSL、Kotlin 2.1.20 |
| 目标设备 | Android TV / 电视盒子（声明 `android.software.leanback` 必需，触摸屏非必需） |
| 已测试设备 | 小米电视 S85 Mini LED |
| 已测试系统 | Xiaomi HyperOS 3.0.103.0 (Android 14) |

> 说明：项目为兼容老旧电视盒子而将 `minSdk` 定为 17（Android 4.2）。AndroidX 依赖与腾讯 X5 内核的最低要求均为 API 14，因此无法继续下探到 Android 2.x。

## 快速开始

### 普通用户

1. 前往 [Releases](https://github.com/RaysonStudio/cctv-view/releases) 下载最新版 `com.raysonstudio.cctv_view_<版本号>.apk`。
2. 将 APK 拷贝到 U 盘并插入电视。
3. 在电视上使用文件管理器打开 APK 并安装（首次安装需允许「安装未知来源应用」）。
4. 安装完成后，在应用列表中找到 **央视网** 并打开。

首次启动时应用会在后台下载并安装腾讯 X5 内核，安装完成后的**下一次冷启动**会自动切换到 X5 内核，届时网页兼容性与播放表现最佳。

### 开发者

```bash
# 克隆仓库
git clone https://github.com/RaysonStudio/cctv-view.git
cd cctv-view

# 使用 Android Studio 打开项目，或命令行编译
./gradlew assembleDebug

# 通过 ADB 安装到电视/盒子（调试产物为 app-debug.apk）
adb connect <电视IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 构建 Release 版本

Release 签名参数优先从环境变量读取，其次读取 `local.properties`（该文件已被 `.gitignore` 忽略）：

| 环境变量 | `local.properties` 键 | 说明 |
|----------|----------------------|------|
| `KEYSTORE_FILE` | `KEYSTORE_FILE` | 密钥库路径 |
| `KEYSTORE_PASSWORD` | `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | `KEY_PASSWORD` | 密钥口令 |

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

推送 `v*` 形式的 Tag 会触发 [Release 工作流](.github/workflows/release.yml) 自动构建并发布，APK 会被重命名为 `com.raysonstudio.cctv_view_<tag>.apk`。

## 遥控器按键映射

| 场景 | 按键 | 功能 |
|------|------|------|
| 播放中 | 上 / 下 | 切换上一个 / 下一个频道（首尾循环） |
| 播放中 | 确定 / 回车 | 重新加载当前频道 |
| 播放中 | 菜单键 | 打开频道列表 |
| 播放中 | 返回键 | 打开列表时关闭列表；否则 2 秒内连按两次退出应用 |
| 频道列表打开 | 上 / 下 | 在可选行之间移动焦点（自动跳过分隔线与标题） |
| 频道列表打开 | 右 | 将当前行频道加入 / 移出收藏夹 |
| 频道列表打开 | 确定 / 回车 | 播放选中频道并关闭列表 |
| 频道列表打开 | 菜单键 / 返回键 | 关闭频道列表 |

播放画面中左右方向键被显式屏蔽，避免网页内控件抢走遥控器焦点。

## 技术要点

- **X5 (TBS) 内核与回退**：`CctvApplication` 启动时调用 `QbSdk.initX5Environment`；若官方下载失败，`X5KernelManager` 会按检测到的 CPU 架构（ARM64 / ARM32 / X86_64 / X86）通过 `DownloadManager` 显式下载对应版本内核并本地安装。内核未就绪期间 `com.tencent.smtt.sdk.WebView` 自动回退到系统 WebView。
- **主文档缓存**：央视频 TV 页是同一个 Vue SPA 壳（各频道共用 HTML，`pid` 仅存在于 URL），因此一份注入后的 HTML 可对所有频道复用。内存缓存 30 分钟有效、磁盘缓存 7 天有效，后台最少 5 分钟刷新一次。
- **桌面 UA**：必须使用桌面 Chrome UA，否则会被 302 重定向到移动端页面（无 `video.video-js` 元素，WASM 加载方式不同导致 MIME 错误）。
- **HLS 延迟补丁**：`Hls` 默认 `liveSyncDurationCount=3`、`liveMaxLatencyDurationCount=Infinity`、`backBufferLength=Infinity`，会导致延迟累积到分钟级且内存持续膨胀。补丁在 `</head>` 之前注入并包装构造器（含 `defineProperty` setter 陷阱，兼容脚本未加载的情况），并将小分片流（如 CCTV-8K）在 `MANIFEST_PARSED` 后自适应调整同步片数。
- **EPG 解码**：零依赖手写 protobuf 解析器，读取 `https://capi.yangshipin.cn/api/yspepg/program/{pid}`，按 `st <= now < et` 判定「正在播出」，每 10 分钟刷新一轮，并优先拉取当前频道与收藏频道。
- **单 WebView 策略**：只维护一个 WebView，避免双播放器导致的双解码与音频焦点抢占；`PreloadWatcher` 是预留的后台预载探测器，当前版本尚未接入双 WebView 流程。

## 项目结构

```text
cctv-view/
├── .github/
│   ├── ISSUE_TEMPLATE/            # Bug 反馈与功能请求模板
│   ├── workflows/release.yml      # Tag 触发的 Release 构建与发布
│   └── PULL_REQUEST_TEMPLATE.md
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/raysonstudio/cctv_view/
│   │   │   ├── CctvApplication.kt      # Application：MultiDex 安装 + X5 内核初始化
│   │   │   ├── MainActivity.kt         # 主界面：视图绑定、抽屉菜单、按键分发、收藏夹、生命周期
│   │   │   ├── ChannelManager.kt       # 频道清单（pid）与频道 URL 生成
│   │   │   ├── ChannelAdapter.kt       # 侧边栏混合列表适配器（分隔线/标题/收藏/频道 + EPG 绑定）
│   │   │   ├── EpgManager.kt           # EPG 后台拉取 + 零依赖 protobuf 解码
│   │   │   ├── CctvWebConfig.kt        # WebView 配置、HTML 缓存、全屏 CSS 与 HLS 补丁注入、清理脚本
│   │   │   ├── CctvWebViewClient.kt    # WebViewClient：主文档拦截与资源放行/拦截策略
│   │   │   ├── VideoPollController.kt  # 视频就绪轮询与分层重试状态机（默认关闭，保留启用）
│   │   │   ├── PreloadWatcher.kt       # 后台预载就绪探测器（预留给双 WebView 换台）
│   │   │   └── X5KernelManager.kt      # 腾讯 X5 内核初始化与按架构下载安装
│   │   ├── keepRules/rules.keep
│   │   └── res/                        # 布局、图标、主题、字符串资源
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml         # 依赖版本目录
├── build.gradle.kts
├── settings.gradle.kts
├── .editorconfig
├── CHANGELOG.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── SECURITY.md
├── ROADMAP.md
├── LICENSE
└── README.md
```

## 技术栈

- Kotlin 2.1.20
- Android SDK：compileSdk 36 / targetSdk 28 / minSdk 17
- 腾讯 X5 (TBS) 内核 SDK（`com.tencent.tbs:tbssdk`）
- AndroidX（core-ktx、appcompat、activity-ktx、drawerlayout、multidex）
- Gradle + Kotlin DSL，依赖版本集中管理于 `gradle/libs.versions.toml`

## 贡献

欢迎提交 Issue、Pull Request 或改进文档：

- 贡献流程与代码风格见 [CONTRIBUTING.md](CONTRIBUTING.md)
- 功能规划与优先级见 [ROADMAP.md](ROADMAP.md)
- 行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全漏洞上报见 [SECURITY.md](SECURITY.md)
- 版本变更记录见 [CHANGELOG.md](CHANGELOG.md)

## 致谢

感谢以下 AI 模型在开发过程中提供的协助：

- ChatGPT
- Qwen3.7
- Kimi
- Deepseek

## 免责声明

- 本项目是第三方客户端，不存储、不转发、不代理任何音视频内容，所有直播流均来自央视频官方页面。
- 应用内出现的频道、节目单、商标等内容的版权归原权利人所有。
- 请遵守当地法律法规及内容服务条款，仅将本项目用于个人学习与研究。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

项目中引用的腾讯 X5 (TBS) 内核 SDK 遵循其自身的授权条款，不在本项目的 Apache 2.0 授权范围内。

---

<div align="center">

如果这个项目对你有帮助，欢迎点个 **Star** 支持一下！

</div>
