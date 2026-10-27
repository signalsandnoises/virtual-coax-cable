using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using Microsoft.Win32;
using Vcx.Sender;

namespace Vcx.Tray;

public sealed class TrayOptions
{
    public IPAddress? Phone;
    public double ExitAfterS;
    public string ConfigPath = ConfigStore.DefaultPath;
    public string LogDir = RollingFileLog.DefaultDirectory;
    public bool HotspotStatusOnly;
}

/// <summary>The tray application: NotifyIcon, menu, settings, and OS event wiring around <see cref="SenderService"/>.</summary>
public sealed class TrayApp : ApplicationContext
{
    private readonly TrayOptions _opt;
    private readonly RollingFileLog _log;
    private readonly ConfigStore _store;
    private readonly IStartupRegistration _startup = new RunKeyStartupRegistration();
    private readonly SenderService _svc;
    private readonly NotifyIcon _icon;
    private readonly TrayIcons _icons;
    private readonly ContextMenuStrip _menu = new();
    private readonly Control _ui = new();
    private readonly System.Windows.Forms.Timer _uiTimer = new() { Interval = 1000 };
    private System.Windows.Forms.Timer? _exitTimer;
    private TrayState? _shownState;
    private string? _shownTip;
    private HotspotStatus? _hotspot;
    private bool _exiting;
    private bool _hotspotHintShown;

    public TrayApp(TrayOptions opt)
    {
        _opt = opt;
        _log = new RollingFileLog(opt.LogDir);
        _ui.CreateControl(); // marshalling target for background events
        var ver = typeof(TrayApp).Assembly.GetName().Version;
        _log.Write($"vcx-tray {ver} starting (pid {Environment.ProcessId}); config {opt.ConfigPath}");

        _store = new ConfigStore(opt.ConfigPath);
        var cfg = _store.Load(out var note);
        if (note != null) _log.Write(note);
        SyncStartup(cfg);

        _svc = new SenderService(cfg, _log.Write, new ServiceOptions { ForcedPhone = opt.Phone });
        _svc.Notification += (title, text) => OnUi(() => Balloon(title, text));
        _svc.ConfigChanged += c => OnUi(() => Save(c, "remembered phone"));

        _icons = new TrayIcons(SystemInformation.SmallIconSize.Width);
        _icon = new NotifyIcon { Icon = _icons[TrayState.Idle], Text = "VCX", Visible = true, ContextMenuStrip = _menu };
        _icon.DoubleClick += (_, _) => ToggleStreaming();
        _menu.Opening += (_, _) => BuildMenu();
        BuildMenu();

        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;

        if (cfg.StartStreamingOnLaunch || opt.Phone != null) _svc.Start();
        else _log.Write("start streaming on launch is off; waiting for the user");
        _svc.DiscoverNow();

        _uiTimer.Tick += (_, _) => RefreshStatus();
        _uiTimer.Start();
        RefreshStatus();
        _ = RefreshHotspotAsync(announce: true);

        if (opt.ExitAfterS > 0)
        {
            // Debug/smoke run: build (not show) the dialogs once so construction errors show up in the log.
            try
            {
                using (new SettingsForm(_svc.Config, _svc.GetSnapshot().Phones)) { }
                using (new IpPrompt("192.168.137.50")) { }
                _log.Write("ui self-check ok (menu, settings, ip prompt built)");
            }
            catch (Exception ex)
            {
                _log.Write($"ui self-check FAILED: {ex}");
            }

            _exitTimer = new System.Windows.Forms.Timer { Interval = (int)Math.Max(1, opt.ExitAfterS * 1000) };
            _exitTimer.Tick += (_, _) => { _log.Write($"--exit-after {opt.ExitAfterS} s reached"); Exit(); };
            _exitTimer.Start();
        }
    }

    private void OnUi(Action a)
    {
        if (_exiting || !_ui.IsHandleCreated) return;
        try { _ui.BeginInvoke(a); } catch (InvalidOperationException) { /* shutting down */ }
    }

    private void Balloon(string title, string text)
    {
        if (_exiting) return;
        _log.Write($"notification: {title}: {text}");
        if (SilentBalloon.TryShow(_icon, 4000, title, text)) return;
        _log.Write("silent balloon unavailable; showing it with sound");
        _icon.ShowBalloonTip(4000, title, text, ToolTipIcon.Info);
    }

    private void Save(AppConfig cfg, string why)
    {
        try
        {
            _store.Save(cfg);
            _log.Write($"config saved ({why})");
        }
        catch (Exception ex)
        {
            _log.Write($"config save failed: {ex.Message}");
        }
    }

    private void SyncStartup(AppConfig cfg)
    {
        try
        {
            string? change = StartupSync.Apply(cfg.StartWithWindows, Environment.ProcessPath ?? Application.ExecutablePath, _startup);
            if (change != null) _log.Write($"start with Windows: {change}");
        }
        catch (Exception ex)
        {
            _log.Write($"start with Windows: registry update failed: {ex.Message}");
        }
    }

    private void RefreshStatus()
    {
        var s = _svc.GetSnapshot();
        if (_shownState != s.State)
        {
            _icon.Icon = _icons[s.State];
            _shownState = s.State;
        }
        if (_shownTip != s.Tooltip)
        {
            _icon.Text = s.Tooltip;
            _shownTip = s.Tooltip;
        }
    }

    private void ToggleStreaming()
    {
        if (_svc.Streaming) _svc.Stop(); else _svc.Start();
        RefreshStatus();
    }

    private void BuildMenu()
    {
        _svc.DiscoverNow(); // fill the phone list while the menu is open
        var s = _svc.GetSnapshot();
        var cfg = _svc.Config;
        _menu.Items.Clear();

        string head = s.State switch
        {
            TrayState.Healthy => $"Streaming to {s.PhoneName}" + (s.LastStats is { } st ? $"  (buf {st.BufferUs / 1000.0:F0}/{st.TargetUs / 1000.0:F0} ms)" : ""),
            TrayState.NoStats => s.PhoneName == null ? "Looking for the phone…" : $"{s.PhoneName} is not answering",
            TrayState.Error => $"Problem: {s.CaptureError ?? s.NetworkError}",
            _ => "Stopped",
        };
        _menu.Items.Add(new ToolStripMenuItem(Trim(head, 70)) { Enabled = false });
        if (s.CaptureDevice != null)
            _menu.Items.Add(new ToolStripMenuItem(Trim($"Capturing: {s.CaptureDevice}", 70)) { Enabled = false });
        _menu.Items.Add(new ToolStripSeparator());

        var toggle = new ToolStripMenuItem(s.Streaming ? "Stop streaming" : "Start streaming") { Font = new Font(_menu.Font, FontStyle.Bold) };
        toggle.Click += (_, _) => ToggleStreaming();
        _menu.Items.Add(toggle);

        var phone = new ToolStripMenuItem("Phone");
        var auto = new ToolStripMenuItem("Automatic (first phone that answers)") { Checked = cfg.PhoneMode == PhoneMode.Auto && _opt.Phone == null };
        auto.Click += (_, _) => ChoosePhone(c => c.PhoneMode = PhoneMode.Auto);
        phone.DropDownItems.Add(auto);
        var names = s.Phones.Select(p => p.Name).ToList();
        if (cfg.PhoneMode == PhoneMode.Device && cfg.PhoneName != null && !names.Contains(cfg.PhoneName)) names.Add(cfg.PhoneName);
        foreach (var name in names)
        {
            var found = s.Phones.FirstOrDefault(p => p.Name == name);
            var item = new ToolStripMenuItem(found != null ? $"{name}  ({found.Endpoint.Address})" : $"{name}  (not seen)")
            {
                Checked = cfg.PhoneMode == PhoneMode.Device && cfg.PhoneName == name && _opt.Phone == null,
            };
            item.Click += (_, _) => ChoosePhone(c =>
            {
                if (c.PhoneName != name) c.PhoneIp = found?.Endpoint.Address.ToString();
                if (found != null) { c.PhoneIp = found.Endpoint.Address.ToString(); c.PhonePort = found.Endpoint.Port; }
                c.PhoneMode = PhoneMode.Device;
                c.PhoneName = name;
            });
            phone.DropDownItems.Add(item);
        }
        if (names.Count == 0) phone.DropDownItems.Add(new ToolStripMenuItem("(no phones found yet)") { Enabled = false });
        phone.DropDownItems.Add(new ToolStripSeparator());
        var manual = new ToolStripMenuItem(cfg.PhoneMode == PhoneMode.Manual ? $"Manual IP… ({cfg.PhoneIp})" : "Manual IP…")
        {
            Checked = cfg.PhoneMode == PhoneMode.Manual && _opt.Phone == null,
        };
        manual.Click += (_, _) => PromptManualIp(cfg);
        phone.DropDownItems.Add(manual);
        var search = new ToolStripMenuItem("Search for phones now");
        search.Click += (_, _) => _svc.DiscoverNow();
        phone.DropDownItems.Add(search);
        if (_opt.Phone != null)
            phone.DropDownItems.Add(new ToolStripMenuItem($"(--phone {_opt.Phone} overrides this)") { Enabled = false });
        _menu.Items.Add(phone);

        // Mobile hotspot
        var hs = _hotspot;
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add(new ToolStripMenuItem(Trim(hs?.MenuText ?? "Mobile hotspot: checking…", 70)) { Enabled = false });
        if (hs is { Available: true, On: false })
        {
            var start = new ToolStripMenuItem("Start mobile hotspot");
            start.Click += async (_, _) => { Balloon("Mobile hotspot", await Hotspot.StartAsync()); await RefreshHotspotAsync(false); };
            _menu.Items.Add(start);
        }
        if (hs is { Available: true, TurnsOffWhenIdle: true })
        {
            var keep = new ToolStripMenuItem("Keep hotspot on when no devices are connected");
            keep.Click += async (_, _) => { Balloon("Mobile hotspot", await Hotspot.KeepOnWhenIdleAsync()); await RefreshHotspotAsync(false); };
            _menu.Items.Add(keep);
        }
        _ = RefreshHotspotAsync(false);

        _menu.Items.Add(new ToolStripSeparator());
        var settings = new ToolStripMenuItem("Settings…");
        settings.Click += (_, _) => OpenSettings();
        _menu.Items.Add(settings);
        var logs = new ToolStripMenuItem("Open log folder");
        logs.Click += (_, _) => OpenLogFolder();
        _menu.Items.Add(logs);
        var exit = new ToolStripMenuItem("Exit");
        exit.Click += (_, _) => Exit();
        _menu.Items.Add(exit);
    }

    private static string Trim(string s, int max) => s.Length <= max ? s : s[..(max - 1)] + "…";

    private void ChoosePhone(Action<AppConfig> change)
    {
        var c = _svc.Config;
        change(c);
        c.Normalize();
        Save(c, "phone selection");
        _svc.ApplyConfig(c);
    }

    private void PromptManualIp(AppConfig cfg)
    {
        using var dlg = new IpPrompt(cfg.PhoneMode == PhoneMode.Manual ? cfg.PhoneIp : _svc.GetSnapshot().Target?.Address.ToString());
        if (dlg.ShowDialog() == DialogResult.OK && dlg.Result != null)
            ChoosePhone(c => { c.PhoneMode = PhoneMode.Manual; c.PhoneIp = dlg.Result.ToString(); c.PhonePort = Protocol.AudioPort; });
    }

    private SettingsForm? _settings;

    private void OpenSettings()
    {
        if (_settings != null) { _settings.Activate(); return; }
        _svc.DiscoverNow();
        using var f = new SettingsForm(_svc.Config, _svc.GetSnapshot().Phones);
        _settings = f;
        try
        {
            if (f.ShowDialog() != DialogResult.OK) return;
            var c = f.Result;
            Save(c, "settings");
            SyncStartup(c);
            _svc.ApplyConfig(c);
        }
        finally
        {
            _settings = null;
        }
    }

    private void OpenLogFolder()
    {
        try
        {
            Directory.CreateDirectory(_log.Directory);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_log.Directory}\"") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            Balloon("VCX", $"Can't open the log folder: {ex.Message}");
        }
    }

    private async Task RefreshHotspotAsync(bool announce)
    {
        var hs = await Task.Run(Hotspot.Query);
        bool changed = _hotspot?.MenuText != hs.MenuText || _hotspot?.TurnsOffWhenIdle != hs.TurnsOffWhenIdle;
        _hotspot = hs;
        if (changed || announce)
            _log.Write($"hotspot: {hs.MenuText}; turns off when idle: {hs.TurnsOffWhenIdle?.ToString() ?? "unknown"}{(hs.Error != null ? $"; {hs.Error}" : "")}");
        if (announce && !_hotspotHintShown && hs.Available && _svc.Config.Notifications)
        {
            _hotspotHintShown = true;
            if (!hs.On) Balloon("Mobile hotspot is off", "Right-click the VCX icon to start it, if the phone connects through it.");
            else if (hs.TurnsOffWhenIdle == true)
                Balloon("Hotspot power saving is on", "Windows turns the hotspot off when no devices are connected. Right-click the VCX icon to keep it on.");
        }
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        _log.Write($"power mode: {e.Mode}");
        if (e.Mode == PowerModes.Resume) Task.Run(_svc.OnPowerResume);
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        _log.Write("network addresses changed");
        _svc.OnNetworkChanged();
        OnUi(() => _ = RefreshHotspotAsync(false));
    }

    private void Exit()
    {
        if (_exiting) return;
        _exiting = true;
        _log.Write("exiting");
        _uiTimer.Stop();
        _exitTimer?.Stop();
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        try { _svc.Dispose(); } catch (Exception ex) { _log.Write($"shutdown error: {ex.Message}"); }
        _icon.Visible = false;
        _icon.Dispose();
        _icons.Dispose();
        _menu.Dispose();
        _log.Write("exited cleanly");
        _log.Dispose();
        ExitThread();
    }
}
