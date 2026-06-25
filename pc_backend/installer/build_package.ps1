param(
    [string]$OutputDir = "$(Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))\dist",
    [switch]$IncludeWheelCache
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$installerDir = Join-Path $repo "installer"
$dist = [IO.Path]::GetFullPath($OutputDir)
$stage = Join-Path $dist "stage"
$payloadRoot = Join-Path $stage "payload"
$backendOut = Join-Path $payloadRoot "backend"

if (-not $dist.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must stay under repo: $repo"
}

Remove-Item -LiteralPath $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $backendOut | Out-Null

$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { throw "csc.exe not found: $csc" }
$iconPath = Join-Path $installerDir "SciToday.ico"
if (-not (Test-Path $iconPath)) { throw "Icon not found: $iconPath" }
$trayExe = Join-Path $payloadRoot "SciTodayTray.exe"
& $csc @(
    "/nologo",
    "/target:winexe",
    "/out:$trayExe",
    "/win32icon:$iconPath",
    "/reference:System.Windows.Forms.dll",
    "/reference:System.Drawing.dll",
    (Join-Path $installerDir "RssAiPushTray.cs")
)
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $trayExe)) {
    throw "Tray compilation failed."
}

$backendFiles = @(
    "app.py",
    "tasks.py",
    "push.py",
    "pdf_watch_summarize.py",
    "requirements.txt",
    "start_server_pc.ps1",
    "sync_termux_data_to_pc.ps1",
    "PC_MIGRATION.md",
    "PC_WEB_CONSOLE_AND_INSTALLER.md"
)
foreach ($file in $backendFiles) {
    Copy-Item -LiteralPath (Join-Path $repo $file) -Destination $backendOut -Force
}
Copy-Item -LiteralPath (Join-Path $repo "admin_web") -Destination (Join-Path $backendOut "admin_web") -Recurse -Force
Copy-Item -LiteralPath (Join-Path $installerDir "uninstall.ps1") -Destination (Join-Path $backendOut "uninstall.ps1") -Force

$cloudflaredCandidates = @(
    "C:\Program Files (x86)\cloudflared\cloudflared.exe",
    "C:\Program Files\cloudflared\cloudflared.exe"
)
$cloudflared = $cloudflaredCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($cloudflared) {
    Copy-Item -LiteralPath $cloudflared -Destination (Join-Path $payloadRoot "cloudflared.exe") -Force
} else {
    Write-Warning "cloudflared.exe not found; installer will still work for local backend."
}
Copy-Item -LiteralPath $iconPath -Destination (Join-Path $payloadRoot "SciToday.ico") -Force
$logoSvg = Join-Path $installerDir "SciToday_logo.svg"
if (Test-Path $logoSvg) {
    Copy-Item -LiteralPath $logoSvg -Destination (Join-Path $payloadRoot "SciToday_logo.svg") -Force
}

if ($IncludeWheelCache) {
    $wheels = Join-Path $payloadRoot "wheels"
    New-Item -ItemType Directory -Force -Path $wheels | Out-Null
    try {
        py -3 -m pip wheel -r (Join-Path $repo "requirements.txt") -w $wheels
        if ($LASTEXITCODE -ne 0) {
            throw "pip wheel failed with exit code $LASTEXITCODE"
        }
        $sourcePackages = Get-ChildItem -Path $wheels -Include *.tar.gz,*.zip -File -Recurse -ErrorAction SilentlyContinue
        if ($sourcePackages) {
            throw "Wheel cache contains source packages: $($sourcePackages.Name -join ', ')"
        }
    } catch {
        Write-Warning "pip wheel failed; installer will fall back to online pip install. $_"
        Remove-Item -LiteralPath $wheels -Recurse -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Host "Skipping wheel cache; installer will use online pip install for better Python-version compatibility."
}

$payloadZip = Join-Path $stage "payload.zip"
Compress-Archive -Path $payloadRoot -DestinationPath $payloadZip -Force
Copy-Item -LiteralPath (Join-Path $installerDir "install.ps1") -Destination (Join-Path $stage "install.ps1") -Force
Copy-Item -LiteralPath (Join-Path $installerDir "install.cmd") -Destination (Join-Path $stage "install.cmd") -Force

$target = Join-Path $dist "SciTodayBackendInstaller.exe"
& $csc @(
    "/nologo",
    "/target:winexe",
    "/out:$target",
    "/win32icon:$iconPath",
    "/reference:System.Windows.Forms.dll",
    "/resource:$payloadZip,payload.zip",
    "/resource:$(Join-Path $stage "install.ps1"),install.ps1",
    (Join-Path $installerDir "RssAiPushSetup.cs")
)
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $target)) {
    throw "Setup compilation failed."
}

$zipOut = Join-Path $dist "SciTodayBackendPackage.zip"
Compress-Archive -Path $target, (Join-Path $installerDir "README_PACKAGE.md") -DestinationPath $zipOut -Force

Write-Host "Installer: $target"
Write-Host "Package zip: $zipOut"
