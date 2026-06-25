param(
    [string]$DataDir = "$env:USERPROFILE\SciTodayData",
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 5200,
    [string]$AuthToken = $env:RSSAI_AUTH_TOKEN,
    [string]$DownloadDirs = "$env:USERPROFILE\Downloads;$env:USERPROFILE\Downloads\dlmanager",
    [switch]$InstallDeps,
    [switch]$Restart
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$venvPython = Join-Path $root ".venv\Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$resolvedDataDir = (Resolve-Path $DataDir).Path

$env:RSSAI_BASE_DIR = $resolvedDataDir
$env:RSSAI_SERVER_HOST = $HostAddress
$env:RSSAI_SERVER_PORT = [string]$Port
$env:RSSAI_DOWNLOAD_DIRS = $DownloadDirs

if ($AuthToken) {
    $env:RSSAI_AUTH_TOKEN = $AuthToken
}

if (-not (Test-Path $venvPython)) {
    py -3 -m venv (Join-Path $root ".venv")
}

if ($InstallDeps) {
    & $venvPython -m pip install -r (Join-Path $root "requirements.txt")
}

if ($Restart) {
    Get-NetTCPConnection -LocalAddress $HostAddress -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object {
            Write-Host "Stopping existing process on $HostAddress`:$Port (PID $_)"
            Stop-Process -Id $_ -Force
        }
    Start-Sleep -Seconds 1
}

Write-Host "SciToday PC backend"
Write-Host "Data dir: $resolvedDataDir"
Write-Host "Listen: http://$HostAddress`:$Port"
Write-Host "Auth: $(if ($env:RSSAI_AUTH_TOKEN) { 'enabled' } else { 'disabled' })"

Set-Location $root
& $venvPython app.py
