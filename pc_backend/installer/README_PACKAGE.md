# SciToday PC Backend Installer

Run from the `pc_backend` directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\installer\build_package.ps1
```

The build creates:

- `dist\SciTodayBackendInstaller.exe`
- `dist\SciTodayBackendPackage.zip`

The installer is per-user and does not require administrator rights. It asks for:

- backend install directory
- database/data directory
- local port
- API auth token
- Quick Tunnel is used by default; no domain, Named Tunnel token, or GitHub upload is required

After install, `SciTodayTray.exe` starts at login through `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
Use the tray menu to open the web console, restart the backend, open the data directory, or exit.
The current `trycloudflare.com` URL and backend token are shown in the web console dashboard.
