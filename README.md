<div align="center">

# 央视网 TV

**CCTV View for Android TV**

在电视上轻松收看央视直播的 Android TV 应用。

[![Release](https://img.shields.io/github/v/release/RaysonStudio/cctv-view?logo=github&color=blue)](https://github.com/RaysonStudio/cctv-view/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-brightgreen)](https://developer.android.com/tv)
[![Android](https://img.shields.io/badge/Android-14%2B-green)](https://developer.android.com/about/versions/14)
[![License](https://img.shields.io/github/license/RaysonStudio/cctv-view?color=orange)](LICENSE)
[![Stars](https://img.shields.io/github/stars/RaysonStudio/cctv-view?style=social)](https://github.com/RaysonStudio/cctv-view/stargazers)

</div>

---

## 项目简介

央视网 TV 是一款面向 Android TV 的直播应用，基于 Android WebView 加载 [央视频](https://www.yangshipin.cn/) 网页，并针对电视大屏做了以下优化：

- 自动全屏播放，隐藏网页中的菜单、广告、节目单等冗余元素
- 同步在 HTML 中注入全屏 CSS，避免页面渲染与 JS 异步注入的竞争
- 针对小米电视 / 盒子等常见设备优化视频 Surface 渲染与图标显示

> 本项目由 AI 辅助开发完成。

## 功能特性

| 功能 | 说明 |
|------|------|
| 央视全频道 | 内置 CCTV-1 综合、CCTV-2 财经、CCTV-3 综艺、CCTV-5 体育、CCTV-13 新闻等主流央视频道 |
| 电视遥控适配 | 针对遥控器方向键、菜单键、返回键做了焦点与按键映射 |
| 全屏优化 | 注入自定义 CSS，自动隐藏网页边框、导航、广告，视频占满屏幕 |
| 同步 CSS 注入 | 通过 `shouldInterceptRequest` 拦截主文档，在 `<head>` 阶段写入全屏样式 |
| 稳定性增强 | 添加视频加载超时检测、JS 层重载与整页刷新重试机制 |

## 系统要求

- Android 14 或更高版本
- 目标设备：Android TV / 电视盒子
- 已测试设备：小米电视 S85 Mini LED
- 已测试系统：Xiaomi HyperOS 3.0.103.0

## 快速开始

### 普通用户

1. 前往 [Releases](https://github.com/RaysonStudio/cctv-view/releases) 下载最新版 `app-debug.apk`。
2. 将 APK 拷贝到 U 盘并插入电视。
3. 在电视上使用文件管理器打开 APK 并安装。
4. 安装完成后，在应用列表中找到 **央视网** 并打开。

### 开发者

```bash
# 克隆仓库
git clone https://github.com/RaysonStudio/cctv-view.git
cd cctv-view

# 使用 Android Studio 打开项目，或命令行编译
./gradlew assembleDebug

# 通过 ADB 安装到电视/盒子
adb connect <电视IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 遥控器按键映射

| 按键 | 功能 |
|------|------|
| 上 / 下 | 切换上一个 / 下一个频道 |
| 菜单键 | 打开 / 关闭频道列表 |
| 确定键 | 频道列表打开时选择频道并播放；列表关闭时刷新当前频道 |

## 项目结构

```text
cctv-view/
├── app/
│   ├── src/main/java/com/raysonstudio/cctv_view/
│   │   ├── MainActivity.kt      # 主界面与 WebView 逻辑
│   │   └── ChannelManager.kt    # 频道数据管理
│   └── src/main/res/            # 布局、图标、资源
├── build.gradle.kts
└── README.md
```

## 技术栈

- Kotlin
- Android SDK 14+
- Android WebView
- Gradle + Kotlin DSL

## 致谢

感谢以下 AI 模型在开发过程中提供的协助：

- ChatGPT
- Qwen3.7
- Kimi

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

---

<div align="center">

如果这个项目对你有帮助，欢迎点个 **Star** 支持一下！

</div>
