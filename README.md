# SciToday

![SciToday logo](SciToday_logo.svg)

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

## License

SciToday is released under the repository license, GPL-3.0.
