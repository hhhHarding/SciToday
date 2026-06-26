# SciToday PC Backend

This directory contains the Windows PC backend, web console, tray app source, and ZIP package source for SciToday.

## What It Runs

- `app.py`: Flask API and admin web entry points.
- `tasks.py`: RSS discovery/publish queue, PDF processing, config, local settings, and status APIs.
- `admin_web/`: SciToday web console.
- `installer/`: Windows tray app and ZIP package source.
- `config.example.json`: public-safe configuration template.

## Source Install

```powershell
cd pc_backend
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item config.example.json config.json
```

Edit `config.json`, then start:

```powershell
.\start_server_pc.ps1 -InstallDeps -Restart -DataDir "$env:USERPROFILE\SciTodayData" -Port 5200 -AuthToken "<long-random-token>"
```

Open `http://127.0.0.1:5200/admin/`.

## Release ZIP Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer\build_package.ps1
```

The default build skips wheel bundling and installs Python dependencies online at install time. Add `-IncludeWheelCache` only for a controlled offline build where the target Python version matches the build machine.

The build creates `dist\SciToday-PC-Backend-V1.0.1.zip`. Public releases use this transparent ZIP package and no longer generate a self-extracting installer EXE.

To install from a release, extract the ZIP and run `install.cmd`. If Defender blocks the tray executable, extract `payload.zip`, open `payload\backend`, and use `start_server_pc.ps1` to run the backend directly.

## Runtime Files

Runtime data should live outside the repository, for example:

```text
$env:USERPROFILE\SciTodayData
```

Do not commit these files:

- `config.json`
- `tray_config.env`
- `quick_tunnel.json`
- SQLite databases
- logs
- inbox summaries
- uploaded PDFs
- `dist/`, `.venv/`, wheel caches, release ZIPs, installers, or cloudflared binaries

## Public Configuration

Use `config.example.json` as the template. It intentionally contains no API key, no real backend token, and no local personal path.

The web settings page masks existing secrets. Saving a blank secret field or `********` keeps the existing secret unchanged.

## Legacy Helpers

`start_server.sh`, `adb_bridge.sh`, and `sync_termux_data_to_pc.ps1` are retained for migration and debugging from earlier Android/Termux workflows. The supported PC backend path is Windows + Python + Flask.
