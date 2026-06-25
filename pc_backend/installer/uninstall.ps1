$ErrorActionPreference = "Stop"
$installDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
Remove-ItemProperty -Path $runKey -Name "SciTodayBackend" -ErrorAction SilentlyContinue
Remove-ItemProperty -Path $runKey -Name "RssAiPushBackend" -ErrorAction SilentlyContinue
Get-Process SciTodayTray -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process RssAiPushTray -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "已删除开机自启并停止托盘程序。"
Write-Host "程序目录: $installDir"
Write-Host "数据库/数据目录不会自动删除。"
Read-Host "确认删除程序目录请按回车"
Start-Sleep -Seconds 1
Start-Process powershell.exe -ArgumentList @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "Start-Sleep -Seconds 2; Remove-Item -LiteralPath '$installDir' -Recurse -Force"
) -WindowStyle Hidden
