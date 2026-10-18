<div align="center">

# 央视网 TV

**CCTV View for Android TV**

在电视上轻松收看央视与卫视直播的 Android TV 应用。

> **项目官网**：<https://raysonstudio.github.io/cctv-view/>（下载、功能演示与安装指引）

[![Website](https://img.shields.io/badge/website-raysonstudio.github.io%2Fcctv--view-ff3d51?logo=googlechrome&logoColor=white)](https://raysonstudio.github.io/cctv-view/)
[![Release](https://img.shields.io/github/v/release/RaysonStudio/cctv-view?logo=github&color=blue)](https://github.com/RaysonStudio/cctv-view/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-brightgreen)](https://developer.android.com/tv)
[![Android](https://img.shields.io/badge/Android-5.0%2B-green)](https://developer.android.com/about/versions/android-5.0)
[![License](https://img.shields.io/github/license/RaysonStudio/cctv-view?color=orange)](LICENSE)
[![Stars](https://img.shields.io/github/stars/RaysonStudio/cctv-view?style=social)](https://github.com/RaysonStudio/cctv-view/stargazers)
[![Linux DO](https://img.shields.io/badge/Linux%20DO-社区讨论-blue)](https://linux.do)

</div>

---

## 项目简介

央视网 TV 是一款面向 Android TV / 电视盒子的直播应用。它**不再依赖网页播放**，而是逆向央视频直播链路实现了**原生播放**：

- **原生播放**：应用内置本地 HLS 代理（127.0.0.1），通过 **JCE 时移协议**（jacc.ysp.cctv.cn）为主、**bkliveinfo + cKey**（TEA 加密）备用，换取央视频明文 HLS 直播流地址，由 **Media3 ExoPlayer** 播放、系统硬件解码——无 JS、无 WASM、无逐片解密，这是零卡顿的根本保证
- **本地代理取分片**：播放列表与分片统一走应用内代理（qqlive UA + Referer 头），规避部分 CDN 对非网页客户端的 403 拦截
- **双协议容灾**：JCE 返回死域名或频道在黑名单时自动切换 bkliveinfo；bkliveinfo 播放列表地址为一次性令牌，代理每轮刷新自动换新；streamId（cnlid）随央视频后台变化，App 每 6 小时自动从 page/PG00000004 接口刷新
- **WebView 兜底**：原生播放多次失败自动回退到旧的 X5 内核网页方案（hls.js），换台缓存、全屏净化等旧能力完整保留
- **遥控器优先**：方向键、菜单键、确定键、返回键全部按电视使用习惯重新映射
- **节目单 (EPG)**：侧边栏直接显示每个频道「正在播出」的节目名

> 本项目由 AI 辅助开发完成。

## 功能特性

| 功能 | 说明 |
|------|------|
| 频道覆盖 | 内置 **55** 个频道：CCTV-1 ~ CCTV-17、CCTV-5+、CCTV-16 (HD/4K)、CCTV-4K / 8K、CGTN 及 30 个省级卫视 |
| 原生播放 | 本地 HLS 代理 + Media3 ExoPlayer，系统硬件解码，无 JS/WASM/逐片解密 |
| 双协议取流 | JCE 时移协议（TEA+gzip 打包）为主，bkliveinfo+cKey（TEA 加密）备用，死域名自动切换 |
| 零卡顿 | 明文 TS + 硬件解码 + 无网页渲染开销；代理滑窗合成滚动直播列表（约 20s 延迟换绝对流畅） |
| 自动容灾 | streamId 每 6 小时自动刷新；原生播放失败自动重试并回退 X5 WebView 网页方案 |
| 收藏夹 | 侧边栏顶部固定展示收藏频道，遥控器右键一键加/取消收藏，收藏顺序持久化保存 |
| 节目单 (EPG) | 后台拉取并解码央视频 EPG 接口（protobuf），侧边栏每行显示「正在播出」节目名，超长名称跑马灯滚动 |
| 全屏优化 | 注入自定义 CSS，自动隐藏网页边框、导航、广告、控制条，视频占满屏幕（WebView 兜底路径） |
| X5 (TBS) 内核 | 启动即初始化腾讯 X5 内核并按设备 CPU 架构自动下载（仅 WebView 兜底路径使用） |
| 稳定性增强 | 原生播放错误自动重试 + WebView 兜底；网页路径含播放守护（冻结检测 + 分层自动恢复） |

## 频道播放状态

| 协议 | 覆盖频道 | 说明 |
|------|----------|------|
| JCE 时移（原生） | CCTV-1~10、CCTV-13、CCTV-8K、CGTN、30 个省级卫视、教育台等 | 稳定，明文 TS 直连央视 CDN |
| bkliveinfo（原生备用） | CCTV-11、12、14、15、16、17、4K 等 JCE 死域名频道 | 播放列表地址为一次性令牌，代理每轮自动换新；部分网络环境下 CDN 会以 403（腾讯 CDN WAF）拒绝，此时自动回退网页方案 |
| WebView 兜底 | 以上任意频道原生失败时 | X5 内核 + hls.js 网页播放，保证始终有画面 |

## 系统要求

| 项目 | 值 |
|------|-----|
| 最低系统版本 | Android 5.0 (API 21) |
| 编译 SDK | Android SDK 36 (compileSdk 36) |
| 目标 SDK | API 28 (targetSdk 28) |
| 构建工具 | JDK 21、Gradle + Kotlin DSL、Kotlin 2.1.20 |
| 目标设备 | Android TV / 电视盒子（声明 `android.software.leanback` 必需，触摸屏非必需） |
| 已测试设备 | 小米电视 S85 Mini LED |
| 已测试系统 | Xiaomi HyperOS 3.0.103.0 (Android 14) |

> 说明：原生播放使用 Media3 ExoPlayer（要求 API 21+），因此最低系统版本由 Android 4.2 上调到 Android 5.0。老盒子仍可通过 WebView 兜底路径获得与旧版本一致的功能。

## 快速开始

### 普通用户

1. 前往 [Releases](https://github.com/RaysonStudio/cctv-view/releases) 下载最新版 `com.raysonstudio.cctv_view_<版本号>.apk`。
2. 将 APK 拷贝到 U 盘并插入电视。
3. 在电视上使用文件管理器打开 APK 并安装（首次安装需允许「安装未知来源应用」）。
4. 安装完成后，在应用列表中找到 **央视网** 并打开。

首次启动应用即可直接播放（原生方案无需任何初始化）。应用会在后台下载腾讯 X5 内核作为**网页兜底路径**的备用内核（原生播放失败时才用到）。

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

- **JCE 时移协议（主取流协议）**：向 `jacc.ysp.cctv.cn` POST TEA 加密 + gzip 压缩的 JCE 请求包（cmd 25312），换取指定频道 180 秒时移窗口的明文 m3u8。分片为明文 TS，无任何加密。
- **bkliveinfo + cKey（备用协议）**：JCE 返回死域名或频道在黑名单（CCTV-11/12/14/15/16/17/4K 等）时，用 TEA 加密的 cKey 参数请求 `bkliveinfo.ysp.cctv.cn` 换取明文 HLS 地址（bklive- 域名优先）。**播放列表地址是一次性令牌**（首次抓取 200、再次 403），因此代理每个刷新周期都重新请求 bkliveinfo 换新地址。
- **本地 HLS 代理**：`YspPlaylistServer` 在 127.0.0.1 起极简 HTTP 服务（每连接一线程）：
  - `/chN.m3u8`：每 3 秒（JCE）/ 6 秒（bk）刷新上游，滑窗（150 片，约 5 分钟）合成带 MEDIA-SEQUENCE 的滚动直播列表；按**分片尾部序号**去重（部分 CDN 每次请求给同一分片加随机中间段，不能用完整文件名去重）；
  - `/seg?u=`：分片也统一走代理转发（qqlive UA + Referer 头），规避 CDN 对非网页客户端的 403；
  - 延迟约 20 秒，换来直播列表永不失效、换台零等待。
- **CDN 反爬限制**：部分 bklive CDN 在特定网络环境下会对代理请求返回 403（腾讯 CDN WAF），此类频道自动回退网页方案。网页 UA 与 Referer 是取流成功的关键头。
- **streamId 自动刷新**：streamId（cnlid）随央视频后台调整而变化。`YspChannelSidRefresher` 每 6 小时请求 `page/PG00000004` 并解析 protobuf（pageModel → dataTvChannelList → pid/streamId），热更新全部频道映射。
- **原生失败自动兜底**：ExoPlayer 出错或 25 秒未就绪时自动重试 2 次，仍失败则回退到 WebView 网页方案（X5 内核 + hls.js 流畅补丁 + 播放守护），保证任何环境下都有画面。
- **X5 (TBS) 内核与回退**（仅网页兜底路径）：`CctvApplication` 启动时调用 `QbSdk.initX5Environment`；若官方下载失败，`X5KernelManager` 会按检测到的 CPU 架构（ARM64 / ARM32 / X86_64 / X86）通过 `DownloadManager` 显式下载对应版本内核并本地安装。内核未就绪期间 `com.tencent.smtt.sdk.WebView` 自动回退到系统 WebView。
- **主文档缓存**（仅网页兜底路径）：央视频 TV 页是同一个 Vue SPA 壳（各频道共用 HTML，`pid` 仅存在于 URL），因此一份注入后的 HTML 可对所有频道复用。内存缓存 30 分钟有效、磁盘缓存 7 天有效，后台最少 5 分钟刷新一次。
- **HLS 流畅补丁**（仅网页兜底路径）：网页路径的 `Hls` 构造器补丁强制「流畅优先」配置（落后边缘 3 片起播、卡顿后同步点自动后退、不强制跳边），并在 `MANIFEST_PARSED` 后锁定清晰度上限（默认 ≤1080p）。
- **EPG 解码**：零依赖手写 protobuf 解析器，读取 `https://capi.yangshipin.cn/api/yspepg/program/{pid}`，按 `st <= now < et` 判定「正在播出」，每 10 分钟刷新一轮，并优先拉取当前频道与收藏频道。
- **单播放器策略**：任意时刻只保留一个播放器（原生或网页），避免双解码与音频焦点抢占；`PreloadWatcher` 是预留的后台预载探测器，当前版本尚未接入。
- **诊断工具**（仅 debug 构建）：本地代理内置 `/test` 诊断端点（`/test?url=...` 抓取任意 URL 返回结果，`/test?jce=pid,sid` 连续两次 JCE 取流对比分片名），配合 `adb logcat -s CCTV_PROXY CCTV_JCE CCTV_BK CCTV_NATIVE` 可定位取流问题。

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
│   │   │   ├── CctvApplication.kt        # Application：MultiDex 安装 + X5 内核初始化
│   │   │   ├── MainActivity.kt           # 主界面：原生播放 + 网页兜底、抽屉菜单、按键分发、收藏夹
│   │   │   ├── YspCrypto.kt              # TEA 分组加密 / 数据包加密 / cksum / gzip / base64url
│   │   │   ├── YspJceProtocol.kt         # JCE 时移协议：打包器/解析器 + 时移地址请求（主取流）
│   │   │   ├── YspCKeyProtocol.kt        # cKey（TEA 加密）+ bkliveinfo 取流（备用协议）
│   │   │   ├── YspPlaylistServer.kt      # 本地 HLS 代理：滑窗合成滚动直播列表，双协议容灾
│   │   │   ├── YspChannelSidRefresher.kt # 后台刷新 streamId（page/PG00000004 protobuf 解析）
│   │   │   ├── ChannelManager.kt         # 频道清单（pid + streamId）与频道 URL 生成
│   │   │   ├── ChannelAdapter.kt         # 侧边栏混合列表适配器（分隔线/标题/收藏/频道 + EPG 绑定）
│   │   │   ├── EpgManager.kt             # EPG 后台拉取 + 零依赖 protobuf 解码
│   │   │   ├── CctvWebConfig.kt          # 网页兜底：WebView 配置、HTML 缓存、全屏 CSS 与 HLS 补丁
│   │   │   ├── CctvWebViewClient.kt      # 网页兜底：WebViewClient 主文档拦截与资源放行策略
│   │   │   ├── VideoPollController.kt    # 网页兜底：视频就绪轮询与分层重试状态机（默认关闭）
│   │   │   ├── PreloadWatcher.kt         # 后台预载就绪探测器（预留）
│   │   │   └── X5KernelManager.kt        # 腾讯 X5 内核初始化与按架构下载安装
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
- Android SDK：compileSdk 36 / targetSdk 28 / minSdk 21
- Media3 ExoPlayer 1.4.1（exoplayer / exoplayer-hls / ui）
- 腾讯 X5 (TBS) 内核 SDK（`com.tencent.tbs:tbssdk`，仅网页兜底路径）
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
