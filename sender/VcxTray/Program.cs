using System.Globalization;
using System.Net;
using System.Net.Sockets;
using Vcx.Sender;

namespace Vcx.Tray;

internal static class Program
{
    /// <summary>
    /// vcx-tray [--phone IP] [--exit-after SECONDS] [--config PATH] [--log-dir DIR] [--hotspot-status]
    /// Debug/test flags: --phone overrides the configured phone (not saved); --exit-after quits cleanly after N s;
    /// --config / --log-dir redirect files; --hotspot-status logs the Mobile Hotspot state and exits.
    /// </summary>
    [STAThread]
    private static int Main(string[] args)
    {
        var opt = new TrayOptions();
        try
        {
            for (int i = 0; i < args.Length; i++)
            {
                string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"{args[i]} needs a value");
                switch (args[i])
                {
                    case "--phone":
                        var s = Next();
                        if (!IPAddress.TryParse(s, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork)
                            throw new ArgumentException($"--phone: '{s}' is not an IPv4 address");
                        opt.Phone = ip;
                        break;
                    case "--exit-after":
                        opt.ExitAfterS = double.Parse(Next(), CultureInfo.InvariantCulture);
                        break;
                    case "--config": opt.ConfigPath = Path.GetFullPath(Next()); break;
                    case "--log-dir": opt.LogDir = Path.GetFullPath(Next()); break;
                    case "--hotspot-status": opt.HotspotStatusOnly = true; break;
                    default: throw new ArgumentException($"unknown argument '{args[i]}'");
                }
            }
        }
        catch (Exception ex) when (ex is ArgumentException or FormatException)
        {
            MessageBox.Show(ex.Message + "\n\nvcx-tray [--phone IP] [--exit-after S] [--config PATH] [--log-dir DIR] [--hotspot-status]",
                "vcx-tray", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }

        if (opt.HotspotStatusOnly)
        {
            using var log = new RollingFileLog(opt.LogDir);
            var hs = Hotspot.Query();
            log.Write($"hotspot-status: {hs}");
            return hs.Available ? 0 : 1;
        }

        using var mutex = new Mutex(true, @"Local\vcx-tray-single-instance", out bool first);
        if (!first)
        {
            using var log = new RollingFileLog(opt.LogDir);
            log.Write("another vcx-tray is already running; exiting");
            return 3;
        }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => CrashLog(opt, e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => CrashLog(opt, e.ExceptionObject as Exception);
        Application.Run(new TrayApp(opt));
        return 0;
    }

    private static void CrashLog(TrayOptions opt, Exception? ex)
    {
        try
        {
            using var log = new RollingFileLog(opt.LogDir, "crash-vcx-tray");
            log.Write($"unhandled exception: {ex}");
        }
        catch { /* nothing left to do */ }
    }
}
