using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace RssAiPushTray
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new TrayContext());
        }
    }

    sealed class TrayContext : ApplicationContext
    {
        readonly NotifyIcon tray;
        readonly Dictionary<string, string> cfg;
        readonly object tunnelStateLock = new object();
        readonly System.Threading.Timer retryTimer;
        readonly System.Threading.Timer commandTimer;
        Process backend;
        Process tunnel;
        string currentTunnelUrl = "";
        string lastTunnelMessage = "";
        string processedCommandId = "";
        int tunnelRunId = 0;
        int retryTunnelRunId = 0;
        bool exiting = false;

        public TrayContext()
        {
            cfg = LoadConfig();
            tray = new NotifyIcon();
            tray.Text = "SciToday Backend";
            tray.Icon = LoadTrayIcon();
            tray.Visible = true;
            tray.ContextMenuStrip = BuildMenu();
            tray.DoubleClick += delegate { OpenAdmin(""); };
            retryTimer = new System.Threading.Timer(delegate
            {
                try
                {
                    if (!exiting && retryTunnelRunId == tunnelRunId)
                    {
                        AppendTunnelLog("自动重试 Quick Tunnel");
                        StartTunnel();
                    }
                }
                catch (Exception ex)
                {
                    AppendTunnelLog("自动重试 Quick Tunnel 失败: " + ex.Message);
                }
            }, null, Timeout.Infinite, Timeout.Infinite);
            commandTimer = new System.Threading.Timer(delegate
            {
                try { CheckTrayCommand(); }
                catch (Exception ex) { AppendTunnelLog("读取托盘命令失败: " + ex.Message); }
            }, null, 2000, 2000);
            StartAll();
        }

        ContextMenuStrip BuildMenu()
        {
            var menu = new ContextMenuStrip();
            menu.Items.Add("打开控制台", null, delegate { OpenAdmin(""); });
            menu.Items.Add("重启后台", null, delegate { RestartAll(); });
            menu.Items.Add("打开数据目录", null, delegate { OpenDataDir(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate { Exit(); });
            return menu;
        }

        System.Drawing.Icon LoadTrayIcon()
        {
            try
            {
                var iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "SciToday.ico");
                if (File.Exists(iconPath)) return new System.Drawing.Icon(iconPath);
            }
            catch { }
            return System.Drawing.SystemIcons.Application;
        }

        Dictionary<string, string> LoadConfig()
        {
            var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "tray_config.env");
            if (!File.Exists(path)) return result;
            foreach (var line in File.ReadAllLines(path))
            {
                var trimmed = line.Trim();
                if (trimmed.Length == 0 || trimmed.StartsWith("#")) continue;
                var idx = trimmed.IndexOf('=');
                if (idx <= 0) continue;
                result[trimmed.Substring(0, idx).Trim()] = trimmed.Substring(idx + 1).Trim();
            }
            return result;
        }

        string Get(string key, string fallback)
        {
            string value;
            return cfg.TryGetValue(key, out value) && value.Length > 0 ? value : fallback;
        }

        void StartAll()
        {
            StartBackend();
            StartTunnel();
        }

        void RestartAll()
        {
            StopProcess(ref tunnel);
            StopProcess(ref backend);
            StartAll();
        }

        void RefreshTunnel()
        {
            AppendTunnelLog("手动刷新 Quick Tunnel URL");
            StopProcess(ref tunnel);
            currentTunnelUrl = "";
            lastTunnelMessage = "";
            StartTunnel();
        }

        void StartBackend()
        {
            var installDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            var script = Path.Combine(installDir, "start_server_pc.ps1");
            if (!File.Exists(script))
            {
                MessageBox.Show("找不到 start_server_pc.ps1", "SciToday");
                return;
            }
            var dataDir = Get("DataDir", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "SciTodayData"));
            var host = Get("HostAddress", "127.0.0.1");
            var port = Get("Port", "5200");
            var token = Get("AuthToken", "");
            var downloadDirs = Get("DownloadDirs", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads") + ";" + Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "dlmanager"));
            var args = "-NoProfile -ExecutionPolicy Bypass -File " + Q(script) +
                       " -Restart -DataDir " + Q(dataDir) +
                       " -HostAddress " + Q(host) +
                       " -Port " + Q(port) +
                       " -DownloadDirs " + Q(downloadDirs);
            if (token.Length > 0) args += " -AuthToken " + Q(token);
            backend = StartHidden("powershell.exe", args, installDir);
        }

        void StartTunnel()
        {
            var installDir = AppDomain.CurrentDomain.BaseDirectory;
            var exe = Path.Combine(installDir, "cloudflared.exe");
            if (!File.Exists(exe)) exe = "cloudflared.exe";

            var host = Get("HostAddress", "127.0.0.1");
            if (host == "0.0.0.0" || host == "::") host = "127.0.0.1";
            var port = Get("Port", "5200");
            var localUrl = Get("TunnelLocalUrl", "http://" + host + ":" + port);
            int portNumber;
            if (Int32.TryParse(port, out portNumber)) WaitForPort(host, portNumber, 30);
            var mode = Get("TunnelMode", "Quick");
            var token = Get("TunnelToken", "");
            var runId = ++tunnelRunId;
            if (mode.Equals("Named", StringComparison.OrdinalIgnoreCase) && token.Length > 0)
            {
                currentTunnelUrl = Get("TunnelUrl", "");
                tunnel = StartTunnelProcess(exe, "tunnel --no-autoupdate run --token " + Q(token), installDir, "named", localUrl, currentTunnelUrl, runId);
                return;
            }

            currentTunnelUrl = "";
            lastTunnelMessage = "";
            tunnel = StartTunnelProcess(exe, "tunnel --url " + Q(localUrl), installDir, "quick", localUrl, "", runId);
        }

        Process StartHidden(string file, string args, string cwd)
        {
            var psi = new ProcessStartInfo(file, args);
            psi.WorkingDirectory = cwd;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.WindowStyle = ProcessWindowStyle.Hidden;
            try { return Process.Start(psi); }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "SciToday 启动失败");
                return null;
            }
        }

        Process StartTunnelProcess(string file, string args, string cwd, string mode, string localUrl, string configuredUrl, int runId)
        {
            var psi = new ProcessStartInfo(file, args);
            psi.WorkingDirectory = cwd;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.WindowStyle = ProcessWindowStyle.Hidden;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;

            var process = new Process();
            process.StartInfo = psi;
            process.EnableRaisingEvents = true;
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { HandleTunnelLine(e.Data, mode, localUrl); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { HandleTunnelLine(e.Data, mode, localUrl); };
            process.Exited += delegate
            {
                AppendTunnelLog("cloudflared 已退出");
                if (runId != tunnelRunId) return;
                if (currentTunnelUrl.Length > 0)
                {
                    WriteTunnelState(mode, localUrl, configuredUrl, "stopped", "cloudflared 已停止");
                }
                else
                {
                    WriteTunnelState(mode, localUrl, configuredUrl, "error", "cloudflared 已退出，最后输出: " + lastTunnelMessage);
                    ScheduleQuickTunnelRestart(runId);
                }
            };

            try
            {
                AppendTunnelLog("启动 cloudflared: mode=" + mode + " localUrl=" + localUrl);
                WriteTunnelState(mode, localUrl, configuredUrl, "starting", "cloudflared 正在启动");
                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                return process;
            }
            catch (Exception ex)
            {
                WriteTunnelState(mode, localUrl, configuredUrl, "error", ex.Message);
                MessageBox.Show(ex.Message, "SciToday Tunnel 启动失败");
                return null;
            }
        }

        void ScheduleQuickTunnelRestart(int runId)
        {
            if (exiting || runId != tunnelRunId) return;
            retryTunnelRunId = runId;
            retryTimer.Change(60000, Timeout.Infinite);
        }

        void HandleTunnelLine(string line, string mode, string localUrl)
        {
            if (String.IsNullOrWhiteSpace(line)) return;
            AppendTunnelLog(line);
            var match = Regex.Match(line, @"https://[A-Za-z0-9-]+\.trycloudflare\.com", RegexOptions.IgnoreCase);
            if (match.Success)
            {
                WriteTunnelState(mode, localUrl, match.Value, "connected", line);
                return;
            }
            if (line.IndexOf("error", StringComparison.OrdinalIgnoreCase) >= 0 ||
                line.IndexOf("failed", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                WriteTunnelState(mode, localUrl, "", "error", line);
                return;
            }
            WriteTunnelState(mode, localUrl, "", currentTunnelUrl.Length > 0 ? "connected" : "starting", line);
        }

        string GetTunnelStatePath()
        {
            var dataDir = Get("DataDir", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "SciTodayData"));
            Directory.CreateDirectory(dataDir);
            return Path.Combine(dataDir, "quick_tunnel.json");
        }

        string GetTunnelLogPath()
        {
            var dataDir = Get("DataDir", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "SciTodayData"));
            Directory.CreateDirectory(dataDir);
            return Path.Combine(dataDir, "quick_tunnel.log");
        }

        string GetTrayCommandPath()
        {
            var dataDir = Get("DataDir", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "SciTodayData"));
            Directory.CreateDirectory(dataDir);
            return Path.Combine(dataDir, "tray_command.json");
        }

        void CheckTrayCommand()
        {
            if (exiting) return;
            var path = GetTrayCommandPath();
            if (!File.Exists(path)) return;
            var text = File.ReadAllText(path, Encoding.UTF8);
            var command = JsonStringValue(text, "command");
            var requestId = JsonStringValue(text, "request_id");
            if (requestId.Length == 0) requestId = File.GetLastWriteTimeUtc(path).Ticks.ToString();
            if (requestId == processedCommandId) return;
            processedCommandId = requestId;

            if (command == "refresh_tunnel")
            {
                RefreshTunnel();
                try { File.Delete(path); } catch { }
            }
        }

        void AppendTunnelLog(string line)
        {
            try
            {
                var text = DateTimeOffset.Now.ToString("o") + " " + (line ?? "") + "\r\n";
                File.AppendAllText(GetTunnelLogPath(), text, Encoding.UTF8);
            }
            catch { }
        }

        void WriteTunnelState(string mode, string localUrl, string url, string status, string message)
        {
            lock (tunnelStateLock)
            {
                if (!String.IsNullOrWhiteSpace(url)) currentTunnelUrl = url;
                if (!String.IsNullOrWhiteSpace(message)) lastTunnelMessage = message;
                var effectiveUrl = String.IsNullOrWhiteSpace(url) ? currentTunnelUrl : url;
                var json = "{\r\n" +
                    "  \"mode\": \"" + JsonEscape(mode) + "\",\r\n" +
                    "  \"url\": \"" + JsonEscape(effectiveUrl) + "\",\r\n" +
                    "  \"localUrl\": \"" + JsonEscape(localUrl) + "\",\r\n" +
                    "  \"status\": \"" + JsonEscape(status) + "\",\r\n" +
                    "  \"message\": \"" + JsonEscape(message) + "\",\r\n" +
                    "  \"updatedAt\": \"" + JsonEscape(DateTimeOffset.Now.ToString("o")) + "\"\r\n" +
                    "}\r\n";
                try { File.WriteAllText(GetTunnelStatePath(), json, Encoding.UTF8); }
                catch { }
            }
        }

        bool WaitForPort(string host, int port, int timeoutSeconds)
        {
            var deadline = DateTime.UtcNow.AddSeconds(timeoutSeconds);
            while (DateTime.UtcNow < deadline)
            {
                try
                {
                    using (var client = new TcpClient())
                    {
                        var result = client.BeginConnect(host, port, null, null);
                        if (result.AsyncWaitHandle.WaitOne(TimeSpan.FromMilliseconds(500)))
                        {
                            client.EndConnect(result);
                            return true;
                        }
                    }
                }
                catch { }
                Thread.Sleep(500);
            }
            return false;
        }

        void OpenAdmin(string query)
        {
            var port = Get("Port", "5200");
            var token = Get("AuthToken", "");
            var url = "http://127.0.0.1:" + port + "/admin/" + query;
            if (token.Length > 0)
            {
                url += (url.Contains("?") ? "&" : "?") + "token=" + Uri.EscapeDataString(token);
            }
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }

        void OpenDataDir()
        {
            var dataDir = Get("DataDir", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "SciTodayData"));
            Directory.CreateDirectory(dataDir);
            Process.Start(new ProcessStartInfo(dataDir) { UseShellExecute = true });
        }

        void StopProcess(ref Process process)
        {
            try
            {
                if (process != null && !process.HasExited) process.Kill();
            }
            catch { }
            process = null;
        }

        void Exit()
        {
            exiting = true;
            retryTimer.Change(Timeout.Infinite, Timeout.Infinite);
            commandTimer.Change(Timeout.Infinite, Timeout.Infinite);
            retryTimer.Dispose();
            commandTimer.Dispose();
            StopProcess(ref tunnel);
            StopProcess(ref backend);
            tray.Visible = false;
            tray.Dispose();
            Application.Exit();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) tray.Dispose();
            base.Dispose(disposing);
        }

        static string Q(string value)
        {
            return "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        }

        static string JsonEscape(string value)
        {
            return (value ?? "")
                .Replace("\\", "\\\\")
                .Replace("\"", "\\\"")
                .Replace("\r", "\\r")
                .Replace("\n", "\\n");
        }

        static string JsonStringValue(string json, string key)
        {
            if (String.IsNullOrEmpty(json) || String.IsNullOrEmpty(key)) return "";
            var pattern = "\"" + Regex.Escape(key) + "\"\\s*:\\s*\"(?<v>(?:\\\\.|[^\"])*)\"";
            var match = Regex.Match(json, pattern);
            if (!match.Success) return "";
            return match.Groups["v"].Value
                .Replace("\\\"", "\"")
                .Replace("\\\\", "\\")
                .Replace("\\r", "\r")
                .Replace("\\n", "\n");
        }
    }
}
