# SciToday PC Staging Workflow

This keeps the Termux backend as the default backend and runs the PC backend as a staging target.

## 1. Sync Termux Data Once

Connect the phone with ADB enabled, then run from this directory:

```powershell
.\sync_termux_data_to_pc.ps1 -DataDir "$env:USERPROFILE\SciTodayData"
```

This copies `config.json`, SQLite databases, `feedly.opml`, and `inbox` into the PC data directory.

## 2. Start the PC Backend

Generate a token and keep it private:

```powershell
$env:RSSAI_AUTH_TOKEN = [guid]::NewGuid().ToString("N")
.\start_server_pc.ps1 -InstallDeps -AuthToken $env:RSSAI_AUTH_TOKEN
```

After code or config changes, restart the local backend:

```powershell
.\start_server_pc.ps1 -Restart -AuthToken $env:RSSAI_AUTH_TOKEN
```

The script sets:

- `RSSAI_BASE_DIR` to the PC data directory.
- `RSSAI_DOWNLOAD_DIRS` to the Windows Downloads folders.
- `RSSAI_SERVER_HOST` to `127.0.0.1`.
- `RSSAI_AUTH_TOKEN` when provided.

Health check:

```powershell
Invoke-RestMethod http://127.0.0.1:5200/
Invoke-RestMethod http://127.0.0.1:5200/healthz
```

Authenticated API check:

```powershell
Invoke-RestMethod http://127.0.0.1:5200/api/status -Headers @{ Authorization = "Bearer $env:RSSAI_AUTH_TOKEN" }
```

## 3. Expose with Cloudflare Tunnel

Example `cloudflared` config target:

```yaml
ingress:
  - hostname: your-scitoday-domain.example.com
    service: http://127.0.0.1:5200
  - service: http_status:404
```

Use the resulting HTTPS URL in the Android app settings under `PC 后端`, together with the same token.

## 4. Switch the App Manually

The app still defaults to `Termux 默认` (`http://127.0.0.1:5000`). To test PC staging:

1. Open `设置`.
2. Choose `PC 后端`.
3. Enter the Cloudflare HTTPS URL.
4. Enter the token.
5. Tap `测试`, then `保存`.

Switch back to `Termux 默认` at any time to return to the existing phone backend.
