using System.Net;
using System.Text.Json.Nodes;
using Vcx.Sender;
using Xunit;

namespace Vcx.Sender.Tests;

public sealed class TempDir : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "vcx-tests-" + Guid.NewGuid().ToString("N"));
    public TempDir() => Directory.CreateDirectory(Path);
    public string File(string name) => System.IO.Path.Combine(Path, name);
    public void Dispose() { try { Directory.Delete(Path, true); } catch { } }
}

public class ConfigTests
{
    [Fact]
    public void MissingFile_GivesDefaults()
    {
        using var d = new TempDir();
        var cfg = new ConfigStore(d.File("config.json")).Load(out var note);
        Assert.NotNull(note);
        Assert.Equal(PhoneMode.Auto, cfg.PhoneMode);
        Assert.Equal(120, cfg.Frames);
        Assert.Equal(0, cfg.Redundancy);
        Assert.False(cfg.StartWithWindows);
        Assert.True(cfg.StartStreamingOnLaunch);
        Assert.Null(cfg.ConfiguredEndpoint());
    }

    [Fact]
    public void SaveLoad_RoundTrip()
    {
        using var d = new TempDir();
        var store = new ConfigStore(d.File("sub/config.json"));
        var cfg = new AppConfig
        {
            PhoneMode = PhoneMode.Device, PhoneName = "SM-S911U1", PhoneIp = "192.168.137.50", PhonePort = 47800,
            Frames = 240, Redundancy = 1, StartWithWindows = true, StartStreamingOnLaunch = false, Notifications = false,
        };
        store.Save(cfg);
        var json = JsonNode.Parse(File.ReadAllText(store.Path))!;
        Assert.Equal(1, json["version"]!.GetValue<int>());
        Assert.Equal("Device", json["phoneMode"]!.GetValue<string>());
        var back = store.Load(out var note);
        Assert.Null(note);
        Assert.Equal(PhoneMode.Device, back.PhoneMode);
        Assert.Equal("SM-S911U1", back.PhoneName);
        Assert.Equal("SM-S911U1", back.PreferredName());
        Assert.Equal(new IPEndPoint(IPAddress.Parse("192.168.137.50"), 47800), back.ConfiguredEndpoint());
        Assert.Equal(240, back.Frames);
        Assert.Equal(1, back.Redundancy);
        Assert.True(back.StartWithWindows);
        Assert.False(back.StartStreamingOnLaunch);
        Assert.False(back.Notifications);
        Assert.False(File.Exists(store.Path + ".tmp"));
    }

    [Fact]
    public void Version0_IsMigrated()
    {
        using var d = new TempDir();
        var path = d.File("config.json");
        File.WriteAllText(path, """{ "phone": "10.0.0.202:47900", "frames": 60, "redundancy": 2, "autostart": true }""");
        var cfg = new ConfigStore(path).Load(out var note);
        Assert.Contains("migrated", note);
        Assert.Equal(PhoneMode.Manual, cfg.PhoneMode);
        Assert.Equal("10.0.0.202", cfg.PhoneIp);
        Assert.Equal(47900, cfg.PhonePort);
        Assert.Equal(60, cfg.Frames);
        Assert.Equal(2, cfg.Redundancy);
        Assert.True(cfg.StartWithWindows);
        Assert.Equal(AppConfig.CurrentVersion, cfg.Version);
        Assert.Null(cfg.PreferredName());
    }

    [Fact]
    public void CorruptFile_IsBackedUpAndDefaultsUsed()
    {
        using var d = new TempDir();
        var path = d.File("config.json");
        File.WriteAllText(path, "{ not json");
        var cfg = new ConfigStore(path).Load(out var note);
        Assert.Contains("unreadable", note);
        Assert.Equal(120, cfg.Frames);
        Assert.True(File.Exists(path + ".bad"));
    }

    [Fact]
    public void OutOfRangeValues_AreClamped_AndUnknownFieldsIgnored()
    {
        using var d = new TempDir();
        var path = d.File("config.json");
        File.WriteAllText(path, """{ "version": 1, "frames": 0, "redundancy": 99, "phoneMode": "Manual", "phoneIp": "not-an-ip", "futureThing": 5 }""");
        var cfg = new ConfigStore(path).Load(out _);
        Assert.Equal(1, cfg.Frames);
        Assert.Equal(8, cfg.Redundancy);
        Assert.Null(cfg.PhoneIp);
        Assert.Equal(PhoneMode.Auto, cfg.PhoneMode); // manual without a valid IP falls back to auto
    }

    private sealed class FakeStartup : IStartupRegistration
    {
        public string? Value;
        public int Writes;
        public string? Get() => Value;
        public void Set(string commandLine) { Value = commandLine; Writes++; }
        public void Remove() { Value = null; Writes++; }
    }

    [Fact]
    public void StartupSync_RegistersUpdatesAndRemoves()
    {
        var reg = new FakeStartup();
        Assert.Null(StartupSync.Apply(false, @"C:\x\vcx-tray.exe", reg));
        Assert.Equal(0, reg.Writes);
        Assert.Equal("registered", StartupSync.Apply(true, @"C:\x\vcx-tray.exe", reg));
        Assert.Equal("\"C:\\x\\vcx-tray.exe\"", reg.Value);
        Assert.Null(StartupSync.Apply(true, @"C:\x\vcx-tray.exe", reg));
        Assert.Equal("updated path", StartupSync.Apply(true, @"D:\y\vcx-tray.exe", reg));
        Assert.Equal("removed", StartupSync.Apply(false, @"D:\y\vcx-tray.exe", reg));
        Assert.Null(reg.Value);
        Assert.Equal(3, reg.Writes);
    }
}

public class ReconnectTests
{
    private static readonly IPEndPoint A = new(IPAddress.Parse("192.168.137.50"), 47800);
    private static readonly IPEndPoint B = new(IPAddress.Parse("192.168.137.77"), 47800);

    [Fact]
    public void NoTarget_DiscoversImmediatelyAndEvery3s_ThenSwitchesToFirstReply()
    {
        var rm = new ReconnectMachine(0, null, null, null);
        Assert.Equal(LinkState.NoTarget, rm.State);
        Assert.True(rm.Tick(0, out _));
        Assert.False(rm.Tick(2.9, out _));
        Assert.True(rm.Tick(3.0, out _));
        Assert.True(rm.OnDiscoverReply(3.1, A, "phone-a"));
        Assert.Equal(A, rm.Target);
        Assert.Equal("phone-a", rm.TargetName);
        Assert.Equal(LinkState.Waiting, rm.State);
        Assert.False(rm.OnDiscoverReply(3.2, B, "phone-b")); // not searching any more
        Assert.Equal(A, rm.Target);
    }

    [Fact]
    public void Connected_ThenSilent5s_SearchesButKeepsTarget_ThenSwitches()
    {
        var rm = new ReconnectMachine(0, A, "phone", null);
        Assert.False(rm.Tick(1, out _));              // grace period, no discovery
        Assert.True(rm.OnStats(1.5, A));              // first STATS = connected
        Assert.False(rm.OnStats(2.5, A));
        Assert.Equal(LinkState.Connected, rm.State);
        Assert.False(rm.Tick(7.4, out var lost));
        Assert.False(lost);
        Assert.True(rm.Tick(7.5, out lost));          // 5 s since last STATS
        Assert.True(lost);
        Assert.Equal(LinkState.Searching, rm.State);
        Assert.Equal(A, rm.Target);                   // keep sending to the last IP meanwhile
        Assert.False(rm.Tick(8, out lost));
        Assert.False(lost);                           // "lost" fires once
        Assert.True(rm.Tick(10.5, out _));            // next round 3 s later
        Assert.True(rm.OnDiscoverReply(10.6, B, "phone")); // the phone came back with a new IP
        Assert.Equal(B, rm.Target);
        Assert.False(rm.Tick(15.5, out _));           // new grace period
        Assert.True(rm.Tick(15.6, out _));            // no STATS from B within 5 s → search again
    }

    [Fact]
    public void SameEndpointReply_RearmsWithoutSwitch()
    {
        var rm = new ReconnectMachine(0, A, null, null);
        Assert.True(rm.Tick(5, out _));
        Assert.Equal(LinkState.Searching, rm.State);
        Assert.False(rm.OnDiscoverReply(5.1, A, "phone"));
        Assert.Equal(LinkState.Waiting, rm.State);
        Assert.Equal("phone", rm.TargetName);
    }

    [Fact]
    public void PreferredName_OnlyAcceptsThatPhone()
    {
        var rm = new ReconnectMachine(0, null, null, "SM-S911U1");
        Assert.True(rm.Tick(0, out _));
        Assert.False(rm.OnDiscoverReply(0.1, B, "someone-else"));
        Assert.Null(rm.Target);
        Assert.True(rm.OnDiscoverReply(0.2, A, "SM-S911U1"));
        Assert.Equal(A, rm.Target);
    }

    [Fact]
    public void StatsFromOtherAddress_Ignored()
    {
        var rm = new ReconnectMachine(0, A, null, null);
        Assert.False(rm.OnStats(1, B));
        Assert.Equal(LinkState.Waiting, rm.State);
        Assert.True(rm.OnStats(1, A));
    }

    [Fact]
    public void Rearm_GivesNewGracePeriod()
    {
        var rm = new ReconnectMachine(0, A, null, null);
        rm.OnStats(1, A);
        rm.Rearm(4);                                 // e.g. socket rebound after a network change
        Assert.False(rm.Tick(8.9, out _));
        Assert.True(rm.Tick(9.0, out var lost));
        Assert.True(lost);
    }

    [Fact]
    public void Reset_ForNewUserChoice()
    {
        var rm = new ReconnectMachine(0, A, "a", null);
        rm.OnStats(1, A);
        rm.Reset(2, null, null, "b");
        Assert.Equal(LinkState.NoTarget, rm.State);
        Assert.Null(rm.Target);
        Assert.Equal("b", rm.PreferredName);
        Assert.False(rm.EverConnected);
    }
}

public class HealthTests
{
    [Theory]
    [InlineData(false, null, null, 0.5, TrayState.Idle)]
    [InlineData(false, "fmt", null, 0.5, TrayState.Idle)]
    [InlineData(true, null, null, 0.5, TrayState.Healthy)]
    [InlineData(true, null, null, 3.0, TrayState.Healthy)]
    [InlineData(true, null, null, 3.1, TrayState.NoStats)]
    [InlineData(true, null, null, double.PositiveInfinity, TrayState.NoStats)]
    [InlineData(true, "mix format is 44100 Hz", null, 0.5, TrayState.Error)]
    [InlineData(true, null, "send failing", 10, TrayState.Error)]
    public void IconState(bool streaming, string? capErr, string? netErr, double age, TrayState expected) =>
        Assert.Equal(expected, TrayHealth.Compute(streaming, capErr, netErr, age));

    [Fact]
    public void Tooltip_FitsAndShowsNumbers()
    {
        var st = new PhoneStats(41_200, 40_000, 400, 3, 0, 0, 1, 0, 0, 1152);
        var tip = TrayHealth.Tooltip(TrayState.Healthy, "SM-S911U1", st, (2u, 1u), null);
        Assert.Equal("VCX → SM-S911U1\nbuf 41/40 ms\nlast min: lost 2, late 1", tip);
        Assert.Equal("VCX: stopped", TrayHealth.Tooltip(TrayState.Idle, "x", st, null, null));
        Assert.Equal("VCX: looking for phone…", TrayHealth.Tooltip(TrayState.NoStats, null, null, null, null));
        var longErr = TrayHealth.Tooltip(TrayState.Error, null, null, null, new string('x', 300));
        Assert.True(longErr.Length <= 127);
    }

    [Fact]
    public void StatsWindow_CountsLastMinute()
    {
        var w = new StatsWindow(60);
        Assert.Null(w.LastWindow());
        w.Add(0, 10, 5);
        Assert.Equal((0u, 0u), w.LastWindow());
        w.Add(30, 12, 5);
        w.Add(59, 13, 6);
        Assert.Equal((3u, 1u), w.LastWindow());
        w.Add(90, 13, 9);                 // baseline moves to t=30 (12, 5)
        Assert.Equal((1u, 4u), w.LastWindow());
        w.Add(200, 20, 9);                // only t=90 remains as baseline
        Assert.Equal((7u, 0u), w.LastWindow());
        w.Add(201, 1, 0);                 // counters reset (phone restarted)
        Assert.Equal((0u, 0u), w.LastWindow());
    }
}

public class RollingLogTests
{
    [Fact]
    public void RotatesAndPrunes()
    {
        using var d = new TempDir();
        using (var log = new RollingFileLog(d.Path, "t", maxBytes: 500, maxFiles: 3))
        {
            for (int i = 0; i < 200; i++) log.Write($"line {i} " + new string('x', 40));
        }
        var files = Directory.GetFiles(d.Path, "t-*.log");
        Assert.Equal(3, files.Length);
        Assert.All(files, f => Assert.True(new FileInfo(f).Length < 700));
        Assert.Contains(files, f => File.ReadAllText(f).Contains("line 199 "));
    }
}
