# SciToday

<p align="center">
  <img src="SciToday_logo.svg" alt="SciToday logo" width="140">
</p>

SciToday is a personal research reading assistant. It combines an Android app with an optional Windows PC backend:

- Android app: mobile reading, notifications, and connection to the PC backend.
- PC backend: Flask API, RSS discovery/publish queue, PDF monitoring and summarization.
- Web console: local admin UI for RSS feeds, RSS queue, PDF queue, settings, logs, and Quick Tunnel connection info.
- Quick Tunnel: optional Cloudflare tunnel that lets the Android app reach the PC backend when both devices are not on the same LAN.

This repository does not include API keys, backend tokens, local databases, logs, private RSS data, PDFs, or generated installers.

## Repository Layout

```text
app/                    Android application
pc_backend/             Windows PC backend and web console
pc_backend/admin_web/   SciToday web console
pc_backend/installer/   Windows tray app and installer source
SciToday_logo.svg       Project logo
```

## Windows PC Backend

### Requirements

- Windows 10/11 x64.
- Python 3.10 or newer. Python 3.11/3.12 are recommended for broad package compatibility.
- Internet access for the first `pip install`.
- Optional: `cloudflared.exe` if you want Quick Tunnel support. The backend still works locally without it.

### Run From Source

```powershell
cd pc_backend
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item config.example.json config.json
```

Edit `config.json`:

- `ai.api_key`: your model provider API key.
- `ai.base_url` and `ai.model`: your model endpoint and model name.
- `rss.opml_path`: your OPML file path, for example `$env:USERPROFILE\SciTodayData\feedly.opml`.
- `server.auth_token`: a long random token used by the web console and Android app.

Start the backend:

```powershell
.\start_server_pc.ps1 `
  -InstallDeps `
  -Restart `
  -DataDir "$env:USERPROFILE\SciTodayData" `
  -Port 5200 `
  -AuthToken "<long-random-token>"
```

Open the console:

```text
http://127.0.0.1:5200/admin/
```

### Build Windows Installer

The installer source is in `pc_backend/installer/`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\pc_backend\installer\build_package.ps1
```

By default the installer does not bundle a Python wheel cache, so it can install dependencies online for the user's Python version. Use `-IncludeWheelCache` only when you intentionally build for an offline Windows x64 deployment with a matching Python version.

Generated installer files are written to `pc_backend/dist/` and are ignored by Git. Public releases should attach installers as GitHub Release assets rather than committing binaries to the repository.

## Connect Android App To PC Backend

1. Start the Windows backend.
2. Open the web console dashboard.
3. If Quick Tunnel is enabled, copy the current Quick Tunnel URL. Otherwise use the LAN or localhost URL that your Android device can reach.
4. Copy the backend access token.
5. In the Android app, set the PC backend URL and token to the values shown in the console.

Quick Tunnel URLs can change after restart. For long-term deployment, use a Cloudflare Named Tunnel or another fixed HTTPS endpoint.

## RSS Workflow

SciToday separates RSS processing into two steps:

- Discovery: fetch RSS feeds and enqueue new candidate items into `rss_queue`.
- Publish: summarize queued items and write the generated digest to the inbox/API for the app and console.

The web console shows queue status:

- `pending`: discovered but not summarized/published yet.
- `published`: summarized and visible in the inbox.
- `error`: failed during summarization or publish; check logs and retry after fixing configuration.

Use the RSS Sources page to import or edit OPML feeds. The Android app and web console read the same backend API, so they see the same feeds and summaries when connected to the same PC backend.

## PDF Workflow

The backend watches configured download folders and uploaded PDFs. New PDFs are tracked, matched to pending papers when possible, summarized with the configured AI provider, and exposed in the PDF Reading page.

PDF extraction quality depends on the PDF text layer. Scanned image-only PDFs may require OCR before SciToday can summarize them accurately.

## Privacy And Security

- `pc_backend/config.json` is intentionally ignored. Use `pc_backend/config.example.json` as a template.
- Do not commit API keys, Cloudflare tunnel tokens, backend auth tokens, databases, logs, PDFs, inbox exports, or generated installers.
- Use a long random `server.auth_token` if the backend is reachable beyond localhost.
- Quick Tunnel exposes the backend through a public HTTPS URL. Keep token authentication enabled.
- The repository's secret-scan rules reject common API keys, GitHub tokens, Cloudflare tokens, temporary `trycloudflare.com` URLs, and local private paths.

## Compatibility Notes

- Android app: standard Android project under `app/`.
- PC backend: primarily maintained for Windows 10/11.
- Python: 3.10+ supported in source mode; online installation is recommended.
- Offline installer builds are Python-version-sensitive if a wheel cache is bundled.
- `cloudflared.exe` is a third-party binary and is not committed to this repository.
- Legacy Termux helper scripts are kept for migration/debug use, but the main open-source PC backend path is Windows.

## 中文说明

SciToday 是一个个人科研阅读助手，由 Android App 和可选的 Windows PC 后端组成：

- Android App：移动端阅读、通知和连接 PC 后端。
- PC 后端：Flask API、RSS 发现/发布队列、PDF 监控和 AI 摘要。
- Web 控制台：本地管理 RSS 源、RSS 队列、PDF 队列、设置、日志和 Quick Tunnel 连接信息。
- Quick Tunnel：可选的 Cloudflare Tunnel，用于让手机 App 在非同一局域网环境下访问 PC 后端。

本仓库不会内置 API Key、后端 Token、本地数据库、日志、私人 RSS 数据、PDF 或生成后的安装包。

### 目录结构

```text
app/                    Android 应用
pc_backend/             Windows PC 后端和 Web 控制台
pc_backend/admin_web/   SciToday Web 控制台
pc_backend/installer/   Windows 托盘程序和安装器源码
SciToday_logo.svg       项目 Logo
```

### Windows PC 后端要求

- Windows 10/11 x64。
- Python 3.10 或更新版本，推荐 Python 3.11/3.12。
- 首次安装依赖需要联网。
- 如需 Quick Tunnel，可安装 `cloudflared.exe`；不安装时后端仍可在本地使用。

### 从源码运行

```powershell
cd pc_backend
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item config.example.json config.json
```

编辑 `config.json`：

- `ai.api_key`：你的模型服务 API Key。
- `ai.base_url` 和 `ai.model`：模型接口地址和模型名。
- `rss.opml_path`：OPML 文件路径，例如 `$env:USERPROFILE\SciTodayData\feedly.opml`。
- `server.auth_token`：用于 Web 控制台和 Android App 的长随机访问 Token。

启动后端：

```powershell
.\start_server_pc.ps1 `
  -InstallDeps `
  -Restart `
  -DataDir "$env:USERPROFILE\SciTodayData" `
  -Port 5200 `
  -AuthToken "<long-random-token>"
```

打开控制台：

```text
http://127.0.0.1:5200/admin/
```

### 构建 Windows 安装包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\pc_backend\installer\build_package.ps1
```

默认构建不会打包 Python wheel 缓存，而是在目标机器上联网安装依赖，减少 Python 版本不兼容问题。只有在确认目标环境和构建环境的 Windows x64/Python 版本一致时，才建议使用 `-IncludeWheelCache` 构建离线包。

### Android App 连接 PC 后端

1. 启动 Windows 后端。
2. 打开 Web 控制台总览页。
3. 如果启用了 Quick Tunnel，复制当前 Quick Tunnel URL；否则使用 Android 设备可访问的局域网地址。
4. 复制后端访问 Token。
5. 在 Android App 中填写 PC 后端 URL 和 Token。

Quick Tunnel URL 重启后可能变化。长期部署建议使用 Cloudflare Named Tunnel 或其他固定 HTTPS 地址。

### RSS 流程

SciToday 将 RSS 分为两个步骤：

- Discovery：抓取 RSS 源，把新条目加入 `rss_queue`。
- Publish：对队列条目生成摘要，并写入 inbox/API，供 App 和控制台读取。

队列状态含义：

- `pending`：已发现但尚未摘要/发布。
- `published`：已摘要并可在 inbox 中查看。
- `error`：摘要或发布失败，需要检查配置和日志后重试。

### PDF 流程

PC 后端会监控配置的下载目录和上传的 PDF。新 PDF 会被记录、尽量匹配待处理论文、调用 AI 生成摘要，并在 PDF 阅读页展示。

PDF 摘要质量取决于 PDF 文本层。扫描版图片 PDF 可能需要先 OCR。

### 隐私与安全

- `pc_backend/config.json` 被故意忽略，请使用 `pc_backend/config.example.json` 作为模板。
- 不要提交 API Key、Cloudflare Tunnel Token、后端访问 Token、数据库、日志、PDF、inbox 导出或生成后的安装包。
- 如果后端可被 localhost 之外的设备访问，务必使用足够长的 `server.auth_token`。
- Quick Tunnel 会把后端暴露为公网 HTTPS URL，必须保持 Token 认证开启。

### 兼容性说明

- Android App：标准 Android 项目，位于 `app/`。
- PC 后端：主要面向 Windows 10/11。
- Python：源码模式支持 Python 3.10+，推荐联网安装依赖。
- 离线安装包如打包 wheel 缓存，会受到 Python 版本影响。
- `cloudflared.exe` 是第三方二进制文件，不提交到本仓库。

## Contributors

- [hhhHarding](https://github.com/hhhHarding)
- OpenAI Codex

## License

SciToday is released under the repository license, GPL-3.0.
