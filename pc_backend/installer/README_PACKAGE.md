# SciToday PC Backend ZIP Package

Build from the PC backend directory:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer\build_package.ps1
```

The build creates:

- `dist\SciToday-PC-Backend-V1.0.1.zip`

The release package is a transparent ZIP instead of a self-extracting EXE. Extract it to a normal folder and run:

```powershell
.\install.cmd
```

The installer is per-user and does not require administrator rights. It asks for:

- backend install directory
- database/data directory
- local port
- API auth token

Quick Tunnel is used by default when `cloudflared.exe` is available on the system or bundled in the package. The backend still works on the local network without Quick Tunnel.

If Windows Defender or SmartScreen blocks the tray executable, extract `payload.zip`, open `payload\backend`, and start the backend directly:

```powershell
.\start_server_pc.ps1 -InstallDeps -Restart -DataDir "$env:USERPROFILE\SciTodayData" -Port 5200 -AuthToken "<long-random-token>"
```

After install, `SciTodayTray.exe` starts at login through `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
Use the tray menu to open the web console, debug page, settings page, data directory, restart, or exit.
The current `trycloudflare.com` URL and backend token are shown in the web console dashboard.
