# 更新日志

本项目所有重要变更都将记录在本文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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

[v3.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v3.0
[v2.1]: https://github.com/RaysonStudio/cctv-view/releases/tag/v2.1
[v2.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v2.0
[v1.0.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v1.0.0
