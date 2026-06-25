# SciToday

研究人员需要持续获取最新发表的论文信息，但现有 RSS 订阅 App 存在系统老旧、AI 总结收费、使用需挂载 VPN 等缺点。

本项目旨在为研究人员提供一个好用的 RSS 订阅 App，可以通过购买 AI 模型的 API 实现自定义化的 AI 辅助阅读。

本项目的绝大部分工程由 AI 模型及智能体实现。

本项目秉持开源精神，欢迎所有社区成员取用，同时也欢迎社区对本项目持续维护。

---

## 功能特性

- **RSS 抓取 + AI 总结**：订阅期刊 RSS，自动抓取新论文并用 AI 生成中文总结（题目、关键词、要点）。
- **PDF 全文总结**：扫描下载目录中的 PDF 论文，与已抓取的 RSS 条目匹配后生成全文级总结。
- **AI 追问对话**：在阅读页就单篇论文向 AI 追问，对话基于论文原文（而非仅总结），并按文章本地保存历史。
- **自定义 AI**：在设置中填入自己的 API Key / Base URL / Model（兼容 OpenAI 风格接口，如 DeepSeek），提示词可自定义。
- **亮/暗双主题**：跟随系统自动切换，统一的视觉语言。
- **交互**：下拉刷新（连续两次下拉触发强制抓取）、滑动删除、骨架屏加载、推送通知点击直达详情。

## 项目结构

本仓库是 **Android 前端**（Jetpack Compose）。它通过 HTTP 与一个**后端服务**通信，后端负责实际的 RSS 抓取、AI 调用、PDF 处理与摘要存储。

```
SciToday/
├── app/                  # Android 前端 Gradle 工程
│   ├── app/src/main/...  # Kotlin 源码与资源
│   └── build.gradle.kts
├── README.md
├── LICENSE               # GPL-3.0
└── SciToday_logo.svg
```

> **注意**：App 需配合后端使用。后端服务（Flask）不在本仓库内，需自行部署并在 App 设置中填入其地址。无后端时 App 可安装启动，但无法加载内容。

## 架构

标准的 **UI (Compose) → ViewModel → Repository → 数据源 (Retrofit / 本地 Store)** 分层，依赖注入使用 Hilt。

- `data/` — `ApiService`/`ApiClient`（Retrofit，支持运行时切换后端地址与 Token 鉴权）、`repository/DigestRepository`（统一入口）、`local/`（已读状态、对话历史等本地存储）。
- `ui/` — 各屏幕及其 `ViewModel`，`common/` 复用组件，`theme/` 主题。
- `navigation/` — 导航与推送 deep link。

## 下载安装

前往 [Releases](https://github.com/hhhHarding/SciToday/releases) 下载最新 APK 安装。

- 支持 **Android 8.0（API 26）及以上**。
- 首次安装可能需在系统设置中允许“安装未知来源应用”。
- 安装后进入「设置」填写后端地址与 AI 配置后即可使用。

## 从源码构建

需要 **JDK 17** 和 Android SDK（compileSdk 35）。推荐用 Android Studio（Ladybug 或更新）打开 `app/` 目录，或命令行构建：

```bash
cd app
# Windows
./gradlew.bat assembleDebug
# Linux / macOS
./gradlew assembleDebug
```

产物位于 `app/app/build/outputs/apk/debug/app-debug.apk`。

> 首次构建会自动从 `services.gradle.org` 下载 Gradle 8.9 与各依赖，请保持联网。

## 后端配置

App 在「设置 → 后端连接」支持两种模式：

- **Termux 默认**：连接本机 `http://127.0.0.1:5000`（后端与 App 跑在同一台 Android 设备上，例如通过 Termux）。
- **PC 后端**：填入后端的 HTTPS 地址（如内网穿透隧道）与访问 Token。

后端需提供 `/api/*`（数据接口）与 `/inbox/`（摘要 HTML）等路由。具体后端实现需自行准备。

## 兼容性评估

| 项目 | 说明 |
|---|---|
| 系统版本 | Android 8.0 (API 26) 及以上；targetSdk 35 |
| 架构 | 纯 Kotlin / Compose，无 native 依赖，支持所有主流 ABI |
| 构建环境 | JDK 17；联网拉取 Gradle 8.9 与依赖 |
| 后端依赖 | **必须**配合后端服务才能加载内容 |
| 已知限制 | 仅中文界面；PDF 总结依赖设备下载目录扫描权限；明文 HTTP 默认允许（见下） |

**结论**：作为开源前端可正常构建、安装、运行。普通使用者要完整体验，需要：① 安装 APK；② 准备并部署后端；③ 在设置中填好后端地址与自己的 AI API。仅安装 App 而无后端时，界面可用但无数据。

## 隐私与安全

- App 本身不内置任何 API Key 或 Token，全部由用户在设置中填入，存于设备本地。
- 当前 `network_security_config` 默认允许明文 HTTP（便于本机 / 内网穿透调试）。若部署到公网，建议改为仅 HTTPS。
- WebView 加载本机后端的摘要 HTML，外部链接转交系统浏览器。

## 技术栈

Kotlin · Jetpack Compose (Material 3) · Hilt · Retrofit + Moshi · OkHttp · Navigation Compose · WebKit

## 许可

本项目以 [GNU GPL-3.0](LICENSE) 许可证开源。
