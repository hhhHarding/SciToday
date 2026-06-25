param(
    [string]$PayloadZip = "$(Split-Path -Parent $MyInvocation.MyCommand.Path)\payload.zip"
)

$ErrorActionPreference = "Stop"

function Read-WithDefault($Prompt, $Default) {
    $value = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value.Trim('"')
}

$defaultInstall = Join-Path $env:LOCALAPPDATA "Programs\SciTodayBackend"
$defaultData = Join-Path $env:USERPROFILE "SciTodayData"
$installDir = Read-WithDefault "安装路径" $defaultInstall
$dataDir = Read-WithDefault "数据库/数据路径" $defaultData
$port = Read-WithDefault "本机端口" "5200"
$authToken = Read-WithDefault "后端访问 Token，留空自动生成" ([guid]::NewGuid().ToString("N"))
$tunnelMode = "Quick"
$tunnelToken = ""
$tunnelUrl = ""

$tmp = Join-Path $env:TEMP ("SciTodayInstall_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Expand-Archive -Path $PayloadZip -DestinationPath $tmp -Force
$payload = Join-Path $tmp "payload"

New-Item -ItemType Directory -Force -Path $installDir, $dataDir | Out-Null
Copy-Item -Path (Join-Path $payload "backend\*") -Destination $installDir -Recurse -Force
Copy-Item -Path (Join-Path $payload "SciTodayTray.exe") -Destination (Join-Path $installDir "SciTodayTray.exe") -Force
if (Test-Path (Join-Path $payload "cloudflared.exe")) {
    Copy-Item -Path (Join-Path $payload "cloudflared.exe") -Destination (Join-Path $installDir "cloudflared.exe") -Force
}
if (Test-Path (Join-Path $payload "SciToday.ico")) {
    Copy-Item -Path (Join-Path $payload "SciToday.ico") -Destination (Join-Path $installDir "SciToday.ico") -Force
}
if (Test-Path (Join-Path $payload "SciToday_logo.svg")) {
    Copy-Item -Path (Join-Path $payload "SciToday_logo.svg") -Destination (Join-Path $installDir "SciToday_logo.svg") -Force
}
if (Test-Path (Join-Path $payload "wheels")) {
    Remove-Item -LiteralPath (Join-Path $installDir "wheels") -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -Path (Join-Path $payload "wheels") -Destination (Join-Path $installDir "wheels") -Recurse -Force
}

$venvPython = Join-Path $installDir ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
    py -3 -m venv (Join-Path $installDir ".venv")
    if ($LASTEXITCODE -ne 0) {
        throw "创建 Python venv 失败，退出码 $LASTEXITCODE"
    }
}
if (Test-Path (Join-Path $installDir "wheels")) {
    & $venvPython -m pip install --no-index --find-links (Join-Path $installDir "wheels") -r (Join-Path $installDir "requirements.txt")
} else {
    & $venvPython -m pip install -r (Join-Path $installDir "requirements.txt")
}
if ($LASTEXITCODE -ne 0) {
    throw "Python 依赖安装失败，退出码 $LASTEXITCODE。请保留上方 pip 错误信息用于排查。"
}

$downloads = Join-Path $env:USERPROFILE "Downloads"
$downloadDirs = "$downloads;$downloads\dlmanager"
@"
InstallDir=$installDir
DataDir=$dataDir
HostAddress=127.0.0.1
Port=$port
AuthToken=$authToken
DownloadDirs=$downloadDirs
TunnelMode=$tunnelMode
TunnelToken=$tunnelToken
TunnelUrl=$tunnelUrl
"@ | Set-Content -Path (Join-Path $installDir "tray_config.env") -Encoding UTF8

try {
    icacls (Join-Path $installDir "tray_config.env") /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
} catch {}

$configPath = Join-Path $dataDir "config.json"
if (-not (Test-Path $configPath)) {
    $opmlPath = (Join-Path $dataDir "feedly.opml").Replace("\", "\\")
    $configJson = @"
{
  "ai": {
    "api_key": "",
    "base_url": "https://api.deepseek.com",
    "model": "deepseek-chat",
    "system_prompt": "你是严谨的科研论文阅读助理。必须基于输入信息总结，不能编造。",
    "rss_prompt": "",
    "pdf_prompt": ""
  },
  "rss": {
    "opml_path": "$opmlPath",
    "per_feed_limit": 3,
    "max_push_items": 20
  },
  "schedule": {
    "rss_discovery_interval_minutes": 10,
    "rss_interval_minutes": 30,
    "pdf_interval_minutes": 5,
    "enabled": true
  },
  "server": {
    "host": "127.0.0.1",
    "port": $port,
    "auth_token": "$authToken"
  },
  "pc": {
    "tunnel_mode": "quick",
    "cloudflare_tunnel_url": "$tunnelUrl"
  }
}
"@
    [System.IO.File]::WriteAllText(
        $configPath,
        $configJson,
        [System.Text.UTF8Encoding]::new($false)
    )
}

$opmlFile = Join-Path $dataDir "feedly.opml"
if (-not (Test-Path $opmlFile)) {
    @"
<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head><title>SciToday</title></head>
  <body></body>
</opml>
"@ | Set-Content -Path $opmlFile -Encoding UTF8
}

$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
New-Item -Path $runKey -Force | Out-Null
Set-ItemProperty -Path $runKey -Name "SciTodayBackend" -Value "`"$(Join-Path $installDir "SciTodayTray.exe")`""

Start-Process -FilePath (Join-Path $installDir "SciTodayTray.exe")
Write-Host "安装完成。"
Write-Host "本机网页: http://127.0.0.1:$port/admin/"
Write-Host "Quick Tunnel URL 会在托盘启动 cloudflared 后显示在网页总览页。"
Read-Host "按回车退出安装器"
