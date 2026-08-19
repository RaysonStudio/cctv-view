# 贡献指南

首先，感谢你考虑为 **央视网 TV** 做出贡献！本文档说明参与本项目的各类方式与流程。

## 行为准则

参与本项目即代表你同意遵守 [行为准则](CODE_OF_CONDUCT.md) 的约定。请在所有相关交流中保持友善与尊重。

## 可以如何贡献

- 提交 [Bug 反馈](https://github.com/RaysonStudio/cctv-view/issues/new?template=bug_report.yml)
- 提交 [功能建议](https://github.com/RaysonStudio/cctv-view/issues/new?template=feature_request.yml)
- 提交 Pull Request 修复问题或实现新功能
- 改进文档与翻译

## 开发环境准备

- Android Studio (最新稳定版)
- JDK 21
- Android SDK 36 (compileSdk)，最低支持 26 (minSdk)
- 一台 Android TV 设备或 Android TV 模拟器用于调试

## 开发流程

1. Fork 本仓库并克隆到本地：

   ```bash
   git clone https://github.com/<你的用户名>/cctv-view.git
   cd cctv-view
   ```

2. 基于最新 `main` 分支创建特性分支：

   ```bash
   git checkout -b feature/my-feature
   ```

3. 编码并确保通过编译：

   ```bash
   ./gradlew assembleDebug
   ```

4. 提交时遵循 [约定式提交](https://www.conventionalcommits.org/zh-hans/) 风格，例如：

   - `feat: 新增 CCTV-5+ 频道`
   - `fix: 修复小米电视全屏闪烁问题`
   - `docs: 更新按键映射说明`
   - `refactor: 拆分 MainActivity 职责`

5. 推送并在 GitHub 上发起 Pull Request，目标分支为 `main`。

6. 在 PR 描述中说明改动目的、测试方式，并关联相关 Issue（如 `Closes #12`）。

## 代码风格

- 使用 4 空格缩进，详见根目录 [`.editorconfig`](.editorconfig)
- 遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)
- 新增源文件需在顶部保留 Apache 2.0 版权头（参考现有 `.kt` 文件）
- 单一职责：一个类只做一件事，Activity 拆分参考 `MainActivity` / `CctvWebConfig` / `CctvWebViewClient` / `VideoPollController` 的分层方式
- 避免空 if 块、避免内联全限定类名、移除已废弃代码

## 提交 Pull Request 前

- [ ] 本地通过 `./gradlew assembleDebug` 编译
- [ ] 已在电视设备或模拟器上测试主要功能
- [ ] 没有引入新的编译警告
- [ ] 提交信息符合约定式提交
- [ ] 如改动 UI，附上截图或录屏

## 报告安全问题

如果你发现安全漏洞，请勿公开提 Issue，参考 [安全策略](SECURITY.md) 通过 GitHub Security Advisory 私密上报。

## 许可证

提交的所有贡献均按 [Apache License 2.0](LICENSE) 协议授权。
