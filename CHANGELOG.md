# 更新日志

本项目所有重要变更都将记录在本文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [4.0] - 2026-10-17

### 新增

- **原生播放（不再依赖网页）**：逆向央视频直播链路，实现本地 HLS 代理 + Media3 ExoPlayer 原生播放
  - `YspJceProtocol`：JCE 时移协议（TEA 加密 + gzip 打包，cmd 25312），向 `jacc.ysp.cctv.cn` 换取明文 m3u8（主取流协议）
  - `YspCKeyProtocol`：cKey（TEA 加密 + base64url）+ `bkliveinfo.ysp.cctv.cn` 备用取流协议，死域名自动切换
  - `YspPlaylistServer`：127.0.0.1 本地 HLS 代理，滑窗合成滚动直播列表，分片统一经代理转发（qqlive UA + Referer 规避 CDN 403）
  - `YspChannelSidRefresher`：每 6 小时从 `page/PG00000004`（protobuf）自动刷新全频道 streamId 映射
  - 零解密：央视 CDN 分片为明文 TS，系统硬件解码，彻底消除 JS/WASM 解密与网页渲染开销
- 原生播放失败自动重试并回退 WebView 网页方案（X5 内核 + hls.js），任意环境保证有画面
- 本地代理内置 `/test` 诊断端点（任意 URL 抓取 / JCE 连续取流对比）
- 引入 Media3 ExoPlayer 1.4.1（exoplayer / exoplayer-hls / ui），最低系统版本上调至 Android 5.0 (API 21)

### 变更

- `MainActivity` 改为原生播放为主、WebView 兜底的双路径架构；遥控器按键、收藏夹、EPG 全部不变
- `ChannelManager` 频道表增加 streamId（cnlid）字段，支持后台热更新
- 布局替换为 `PlayerView`（WebView 隐藏保留作兜底）

### 修复（实测验证）

- 修复 bkliveinfo 播放列表**一次性令牌**问题：地址首次抓取 200、再次 403，代理改为每轮刷新重新请求 bkliveinfo 换新地址
- 修复分片去重：部分 CDN 每次请求给同一分片加随机中间段，改为按分片尾部序号去重，消除窗口重复导致的 BEHIND_LIVE_WINDOW
- 修复部分 CDN 对非网页客户端的 403：分片统一走本地代理，带 qqlive UA + Referer 头转发
- 修复网页路径 HLS 补丁导致的卡顿（流畅优先配置，详见 v3.0 之后修复记录）
- 修复直播列表播放卡顿：HLS 补丁改为「流畅优先」策略——起播落后直播边缘 3 片、卡顿后同步点自动后退 1 片（`liveSyncOnStallIncrease=1`）、落后超过分钟级才重同步（`liveMaxLatencyDurationCount=30`，正常观看绝不跳边）、缓冲空洞小幅前跳恢复（`nudgeOnVideoHole=true`），前向缓冲上限放宽至 90s
- 锁定清晰度上限（默认 ≤1080p 最高码率档），避免 4K/8K 解码压力导致的卡顿；页面里设置 `window.__CCTV_CAP_HEIGHT=0` 可关闭封顶
- 新增播放守护：每 5 秒检测播放冻结，分层自动恢复（`play()` → `v.load()` → 整页重载，每页面最多 2 次），覆盖网络抖动、WASM DRM 卡死与待机恢复场景
- 播放建立后自动断开 DOM 清理 MutationObserver，减少播放期间主线程空转与 GC 压力
- WebView 渲染优先级提升为 HIGH 并关闭缩放，降低视频播放掉帧
- 主文档缓存文件名升级为 v3，旧缓存（含旧延迟补丁）自动失效

### 已知问题

- 部分走 bkliveinfo 的频道（CCTV-11/12/14/15/16/17/4K 等）在特定网络环境下，央视 CDN 会对代理请求返回 403（腾讯 CDN WAF 反爬），此类频道自动回退 WebView 网页方案播放

## [v3.0] - 2026-10-01

### 新增

- 侧边栏 EPG 节目单：新增 `EpgManager`，后台拉取并解码央视频 EPG 接口（零依赖手写 protobuf 解析器），每个频道显示「正在播出」的节目名
- 节目名跑马灯：侧边栏打开时超宽节目名滚动显示，关闭时停止，避免后台空转耗电
- 直播延迟优化：在 HTML `<head>` 中注入 `Hls` 延迟补丁，包装播放器构造器覆盖 `liveSyncDurationCount` 等配置，压低直播延迟并回收后台缓冲
- 小分片流自适应：`MANIFEST_PARSED` 后按 `targetduration` 自动调整同步片数（覆盖 CCTV-8K 等 2s 分片流）
- 侧边栏收藏夹分组：列表顶部固定展示收藏频道，遥控器右键一键加/取消收藏并持久化

### 变更

- 菜单定位优化：首次打开菜单滚回顶部展示收藏夹，之后停留在当前频道附近
- 播放中左右方向键显式屏蔽，避免网页控件抢走遥控器焦点

### 修复

- 修复 Release 构建失败：关闭 `lintVital` 并改用阿里云镜像仓库，规避网络不稳导致的打包中断
- 修复 GitHub Actions：替换已失效的 `android-actions/setup-android`，改用预装 SDK 并增加带重试的兜底安装

### 文档

- 更新 README：修正最低系统版本等过时信息，补充技术要点与项目结构

## [v2.1] - 2026-08-19

### 新增

- 添加星影频道并支持切换 TV 源
- 新增 `ROADMAP.md` 项目路线图
- README 增加 Linux DO 社区讨论徽章

### 修复

- 修正 README 中的 Android 版本徽章

### 文档

- 更新 README 项目说明与使用方式

## [v2.0] - 2026-08-12

### 新增

- 添加星影频道并支持切换 TV 源
- 增强 DOM 清理：扩展 CSS 选择器、安全移除冗余节点、防抖 MutationObserver、新增更多触发时机
- 完善 Release 工作流：新增预处理任务与 APK 处理流程

### 修复

- 同步注入全屏 CSS，消除页面渲染与 JS 异步注入的竞争
- 修复全屏竞态条件：缓存 UserAgent，避免在后台线程访问 WebView
- 修复应用图标显示问题

### 文档

- 更新 README：补充项目概述、徽章、按键映射说明
- 在 README 中对过时信息添加警告提示

## [v1.0.0] - 2026-07-29

### 新增

- 央视网 TV 首个公开版本
- 内置 CCTV-1 综合、CCTV-2 财经、CCTV-3 综艺、CCTV-5 体育、CCTV-13 新闻等主流频道
- 针对电视遥控器方向键、菜单键、返回键的焦点与按键映射
- 通过 `shouldInterceptRequest` 拦截主文档并同步注入全屏 CSS，自动隐藏网页边框、导航、广告
- 视频加载超时检测、JS 层重载与整页刷新重试机制
- GitHub Actions 自动构建并发布 APK 的 CI 流程

[4.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v4.0
[v3.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v3.0
[v2.1]: https://github.com/RaysonStudio/cctv-view/releases/tag/v2.1
[v2.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v2.0
[v1.0.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v1.0.0
