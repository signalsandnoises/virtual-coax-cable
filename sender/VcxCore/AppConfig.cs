using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Vcx.Sender;

/// <summary>How the tray app picks the phone.</summary>
public enum PhoneMode
{
    /// <summary>Discover; stream to whichever phone answers.</summary>
    Auto,
    /// <summary>A discovered phone, remembered by name (its IP may change; rediscovered by name).</summary>
    Device,
    /// <summary>A fixed IP typed by the user (discovery still runs if it stops answering).</summary>
    Manual,
}

/// <summary>Tray app settings, stored as JSON in %APPDATA%\vcx\config.json.</summary>
public sealed class AppConfig
{
    public const int CurrentVersion = 1;

    public int Version { get; set; } = CurrentVersion;
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public PhoneMode PhoneMode { get; set; } = PhoneMode.Auto;
    /// <summary>Device name for <see cref="PhoneMode.Device"/>.</summary>
    public string? PhoneName { get; set; }
    /// <summary>IP for <see cref="PhoneMode.Manual"/>; for Device mode the last IP it was seen at.</summary>
    public string? PhoneIp { get; set; }
    public int PhonePort { get; set; } = Protocol.AudioPort;
    public int Frames { get; set; } = 120;
    public int Redundancy { get; set; } = 0;
    public bool StartWithWindows { get; set; } = false;
    public bool StartStreamingOnLaunch { get; set; } = true;
    public bool Notifications { get; set; } = true;

    public AppConfig Clone() => (AppConfig)MemberwiseClone();

    /// <summary>Clamps values into valid ranges; drops an unparsable IP.</summary>
    public AppConfig Normalize()
    {
        Version = CurrentVersion;
        Frames = Math.Clamp(Frames, 1, 4800);
        Redundancy = Math.Clamp(Redundancy, 0, 8);
        if (PhonePort is < 1 or > 65535) PhonePort = Protocol.AudioPort;
        if (PhoneIp != null && !(IPAddress.TryParse(PhoneIp, out var ip) && ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork))
            PhoneIp = null;
        if (string.IsNullOrWhiteSpace(PhoneName)) PhoneName = null;
        if (PhoneMode == PhoneMode.Manual && PhoneIp == null) PhoneMode = PhoneMode.Auto;
        if (PhoneMode == PhoneMode.Device && PhoneName == null) PhoneMode = PhoneMode.Auto;
        return this;
    }

    /// <summary>The initial target: the manual IP, or (Device/Auto) the IP the phone was last seen at.</summary>
    public IPEndPoint? ConfiguredEndpoint() =>
        PhoneIp != null && IPAddress.TryParse(PhoneIp, out var ip) ? new IPEndPoint(ip, PhonePort) : null;

    /// <summary>Only replies from this device name are accepted when rediscovering (Device mode).</summary>
    public string? PreferredName() => PhoneMode == PhoneMode.Device ? PhoneName : null;
}

/// <summary>Loads/saves <see cref="AppConfig"/> with version migration and corrupt-file recovery.</summary>
public sealed class ConfigStore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public string Path { get; }
    public ConfigStore(string path) => Path = path;

    public static string DefaultPath =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vcx", "config.json");

    /// <summary>Loads the config. Missing file → defaults. Unreadable/corrupt → renamed to *.bad and defaults.
    /// <paramref name="note"/> says what happened (null when the file loaded cleanly).</summary>
    public AppConfig Load(out string? note)
    {
        note = null;
        if (!File.Exists(Path)) { note = "no config file; using defaults"; return new AppConfig(); }
        try
        {
            var node = JsonNode.Parse(File.ReadAllText(Path)) as JsonObject
                       ?? throw new JsonException("top level is not an object");
            int version = node["version"]?.GetValue<int>() ?? 0;
            if (version > AppConfig.CurrentVersion)
                note = $"config version {version} is newer than this app ({AppConfig.CurrentVersion}); unknown fields ignored";
            if (version < 1) { Migrate0To1(node); note = "migrated config from version 0"; }
            var cfg = node.Deserialize<AppConfig>(Json) ?? new AppConfig();
            return cfg.Normalize();
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or FormatException or IOException or UnauthorizedAccessException)
        {
            string bad = Path + ".bad";
            try { File.Copy(Path, bad, overwrite: true); } catch { /* best effort */ }
            note = $"config unreadable ({ex.Message}); saved a copy as {bad} and using defaults";
            return new AppConfig();
        }
    }

    /// <summary>
    /// Version 0 = hand-written/early files without "version": {"phone": "IP" or "IP:port", "frames": N,
    /// "redundancy": K, "autostart": bool}. Mapped onto version 1 field names.
    /// </summary>
    internal static void Migrate0To1(JsonObject n)
    {
        if (n["phone"] is JsonValue pv && pv.TryGetValue<string>(out var phone) && !string.IsNullOrWhiteSpace(phone))
        {
            string ip = phone;
            int colon = phone.LastIndexOf(':');
            if (colon > 0 && int.TryParse(phone[(colon + 1)..], out int port)) { ip = phone[..colon]; n["phonePort"] = port; }
            n["phoneIp"] = ip;
            n["phoneMode"] = nameof(PhoneMode.Manual);
        }
        n.Remove("phone");
        if (n["autostart"] is JsonValue av && av.TryGetValue<bool>(out var autostart))
        {
            n["startWithWindows"] = autostart;
            n.Remove("autostart");
        }
        n["version"] = 1;
    }

    /// <summary>Atomic save (write temp file, then replace).</summary>
    public void Save(AppConfig cfg)
    {
        cfg.Version = AppConfig.CurrentVersion;
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
        string tmp = Path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(cfg, Json));
        File.Move(tmp, Path, overwrite: true);
    }
}

/// <summary>"Start with Windows" storage (HKCU Run key in the app; a fake in tests).</summary>
public interface IStartupRegistration
{
    /// <summary>The registered command line, or null.</summary>
    string? Get();
    void Set(string commandLine);
    void Remove();
}

public static class StartupSync
{
    /// <summary>Makes the registration match the config. Returns what changed (null if nothing).</summary>
    public static string? Apply(bool enabled, string exePath, IStartupRegistration reg)
    {
        string wanted = $"\"{exePath}\"";
        string? current = reg.Get();
        if (enabled && current != wanted) { reg.Set(wanted); return current == null ? "registered" : "updated path"; }
        if (!enabled && current != null) { reg.Remove(); return "removed"; }
        return null;
    }
}

/// <summary>HKCU\Software\Microsoft\Windows\CurrentVersion\Run (per-user, no admin).</summary>
public sealed class RunKeyStartupRegistration(string valueName = "vcx-tray") : IStartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public string? Get()
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue(valueName) as string;
    }

    public void Set(string commandLine)
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);
        k.SetValue(valueName, commandLine);
    }

    public void Remove()
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        k?.DeleteValue(valueName, throwOnMissingValue: false);
    }
}
