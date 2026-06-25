using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;

namespace RssAiPushSetup
{
    static class Program
    {
        [STAThread]
        static int Main()
        {
            try
            {
                var temp = Path.Combine(Path.GetTempPath(), "SciTodaySetup_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(temp);
                var payload = Path.Combine(temp, "payload.zip");
                var install = Path.Combine(temp, "install.ps1");
                ExtractResource("payload.zip", payload);
                ExtractResource("install.ps1", install);
                var psi = new ProcessStartInfo("powershell.exe");
                psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File " + Q(install) + " -PayloadZip " + Q(payload);
                psi.UseShellExecute = true;
                var p = Process.Start(psi);
                p.WaitForExit();
                return p.ExitCode;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.ToString(), "SciToday 安装器失败");
                return 1;
            }
        }

        static void ExtractResource(string suffix, string dest)
        {
            var asm = Assembly.GetExecutingAssembly();
            var name = asm.GetManifestResourceNames().FirstOrDefault(n => n.EndsWith(suffix, StringComparison.OrdinalIgnoreCase));
            if (name == null) throw new FileNotFoundException("Missing embedded resource: " + suffix);
            using (var input = asm.GetManifestResourceStream(name))
            using (var output = File.Create(dest))
            {
                input.CopyTo(output);
            }
        }

        static string Q(string value)
        {
            return "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        }
    }
}
