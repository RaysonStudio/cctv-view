# 更新日志

本项目所有重要变更都将记录在本文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [v2.0] - 2026

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

## [v1.0.0] - 2026

### 新增

- 央视网 TV 首个公开版本
- 内置 CCTV-1 综合、CCTV-2 财经、CCTV-3 综艺、CCTV-5 体育、CCTV-13 新闻等主流频道
- 针对电视遥控器方向键、菜单键、返回键的焦点与按键映射
- 通过 `shouldInterceptRequest` 拦截主文档并同步注入全屏 CSS，自动隐藏网页边框、导航、广告
- 视频加载超时检测、JS 层重载与整页刷新重试机制
- GitHub Actions 自动构建并发布 APK 的 CI 流程

[v2.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v2.0
[v1.0.0]: https://github.com/RaysonStudio/cctv-view/releases/tag/v1.0.0
