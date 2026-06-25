# SciToday PC Web Console and Installer

## Local Web Console

Start or restart the PC backend:

```powershell
$env:RSSAI_AUTH_TOKEN = [Environment]::GetEnvironmentVariable("RSSAI_AUTH_TOKEN", "User")
.\start_server_pc.ps1 -Restart -AuthToken $env:RSSAI_AUTH_TOKEN
```

Open:

- `http://127.0.0.1:5200/admin/`
- `http://127.0.0.1:5200/admin/?view=monitor`
- `http://127.0.0.1:5200/admin/?view=settings`

The web console stores the token in browser localStorage and can also receive it from
`?token=...`; the URL token is removed from browser history immediately by the page.

## New Admin APIs

- `GET /api/admin/overview`
- `GET /api/admin/events`
- `GET /api/admin/feed-health`
- `GET/POST /api/admin/settings`
- `POST /api/admin/run/rss-discovery`
- `POST /api/admin/run/rss-publish`
- `POST /api/app/heartbeat`

`/api/run/rss` remains compatible and still performs immediate discover + publish.
Scheduled PC mode now uses discovery and publish as separate jobs.

## Installer Build

Build the current backend package:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer\build_package.ps1
```

Outputs:

- `dist\SciTodayBackendInstaller.exe`
- `dist\SciTodayBackendPackage.zip`

The installer is per-user and writes HKCU startup entry `SciTodayBackend`.
It prompts for install path, data path, local port, and auth token. Quick Tunnel
is used by default, so no domain, Named Tunnel token, or GitHub upload is required.
Existing data directories are preserved.

## Cloudflare Quick Tunnel

The tray app starts:

```powershell
cloudflared tunnel --url http://127.0.0.1:5200
```

The tray app parses the generated `https://*.trycloudflare.com` URL from
`cloudflared`, writes it to `quick_tunnel.json` in the data directory, and the web
console dashboard displays the current URL and backend token for the mobile App.
