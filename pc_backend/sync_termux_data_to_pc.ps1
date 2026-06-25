param(
    [string]$DataDir = "$env:USERPROFILE\SciTodayData",
    [string]$DevicePath = "/storage/emulated/0/RssAiPush"
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$resolvedDataDir = (Resolve-Path $DataDir).Path

$items = @(
    "config.json",
    "feedly.opml",
    "rss_ai.db",
    "pending_papers.db",
    "pdf_seen.db",
    "digest_messages.db",
    "inbox"
)

foreach ($item in $items) {
    $remote = "$DevicePath/$item"
    Write-Host "Pull $remote -> $resolvedDataDir"
    & adb pull $remote $resolvedDataDir
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Skipped or failed: $remote"
    }
}

Write-Host "Sync finished: $resolvedDataDir"
