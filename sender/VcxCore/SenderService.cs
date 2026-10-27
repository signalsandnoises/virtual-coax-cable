using System.Globalization;
using System.Net;

namespace Vcx.Sender;

public sealed record DiscoveredPhone(string Name, IPEndPoint Endpoint, DateTime LastSeenUtc);

public sealed record ServiceSnapshot(
    bool Streaming,
    TrayState State,
    LinkState Link,
    string? PhoneName,
    IPEndPoint? Target,
    PhoneStats? LastStats,
    double StatsAgeS,
    (uint lost, uint late)? LastMinute,
    string? CaptureDevice,
    string? CaptureError,
    string? NetworkError,
    long PacketsSent,
    IReadOnlyList<DiscoveredPhone> Phones,
    string Tooltip);

public sealed class ServiceOptions
{
    /// <summary>--phone: fixed target that overrides the config (not saved).</summary>
    public IPAddress? ForcedPhone;
    public int ForcedPort = Protocol.AudioPort;
}

/// <summary>
/// Streaming service behind the tray UI: socket (<see cref="VcxLink"/>), capture/stream engine, discovery,
/// reconnect (<see cref="ReconnectMachine"/>), health and periodic logging. Thread-safe; UI calls are cheap
/// except Start/Stop (WASAPI init, ~100 ms).
/// </summary>
public sealed class SenderService : IDisposable
{
    private const double LogStatsEveryS = 10;
    private const double LogSenderEveryS = 30;
    private const double NetworkDebounceS = 2;
    /// <summary>Unicast DISCOVER to Wi-Fi neighbors this often, even while connected, so a phone where Play was
    /// just pressed can claim the stream (<see cref="Protocol.FlagClaim"/>) within a couple of seconds.</summary>
    private const double NeighborSweepEveryS = 2;
    private static readonly TimeSpan PhoneListTtl = TimeSpan.FromSeconds(60);

    private readonly object _gate = new();
    private readonly Action<string> _log;
    private readonly ServiceOptions _opt;
    private readonly VcxLink _link;
    private readonly bool _loopbackOnly;
    private readonly ReconnectMachine _rm;
    private readonly StatsWindow _window = new();
    private readonly ToastDebouncer _toasts = new();
    private readonly Dictionary<string, DiscoveredPhone> _phones = new();
    private readonly Timer _timer;
    private AppConfig _cfg;
    private StreamEngine? _engine;
    private string? _engineError;
    private PhoneStats? _lastStats;
    private PhoneStats? _lastLoggedStats;
    private double _lastStatsLogAt = double.NegativeInfinity;
    private double _lastSenderLogAt;
    private long _lastSenderLogSent;
    private double _rebindAt = double.PositiveInfinity;
    private uint _discoverCounter;
    private double _lastNeighborSweep = double.NegativeInfinity;
    private string _lastNeighbors = "";
    private string? _lastNotifiedError;
    private bool _disposed;

    /// <summary>(title, text) for a toast/balloon.</summary>
    public event Action<string, string>? Notification;
    /// <summary>The service changed the config (e.g. the remembered phone's IP). The owner should save it.</summary>
    public event Action<AppConfig>? ConfigChanged;

    private static double Now() => QpcClock.NowUs() / 1e6;

    public SenderService(AppConfig cfg, Action<string> log, ServiceOptions? options = null)
    {
        _cfg = cfg.Clone().Normalize();
        _log = log;
        _opt = options ?? new ServiceOptions();
        _loopbackOnly = _opt.ForcedPhone != null && IPAddress.IsLoopback(_opt.ForcedPhone);
        _link = new VcxLink(_loopbackOnly ? IPAddress.Loopback : IPAddress.Any, log);
        _link.StatsReceived += OnStats;
        _link.DiscoverReplyReceived += OnDiscoverReply;
        var (target, name, preferred) = InitialTarget();
        _rm = new ReconnectMachine(Now(), target, name, preferred);
        _link.SetTarget(target);
        _link.Rebind();
        _lastSenderLogAt = Now();
        _timer = new Timer(_ => Tick(), null, 250, 250);
    }

    private (IPEndPoint? target, string? name, string? preferred) InitialTarget()
    {
        if (_opt.ForcedPhone != null) return (new IPEndPoint(_opt.ForcedPhone, _opt.ForcedPort), null, null);
        return (_cfg.ConfiguredEndpoint(), _cfg.PhoneName, _cfg.PreferredName());
    }

    public AppConfig Config { get { lock (_gate) return _cfg.Clone(); } }
    public bool Streaming { get { lock (_gate) return _engine != null; } }

    public void Start()
    {
        lock (_gate)
        {
            if (_engine != null || _disposed) return;
            var e = new StreamEngine(_cfg.Frames, _cfg.Redundancy, false, _log, (pkt, _) => _link.SendAudio(pkt));
            try
            {
                e.Start(requireCapture: false);
                _engine = e;
                _engineError = null;
                _rm.Rearm(Now());
                _log($"streaming started: {_cfg.Frames} frames/packet, K={_cfg.Redundancy}, target {_rm.Target?.ToString() ?? "(discovering)"}; " +
                     $"capture '{e.DeviceName ?? "-"}' ({e.DeviceFormat ?? e.CaptureError}); mmcss={(e.MmcssEnabled ? "on" : "FAILED")}");
            }
            catch (Exception ex)
            {
                _engineError = $"cannot start: {ex.Message}";
                _log($"streaming failed to start: {ex}");
            }
        }
    }

    public void Stop()
    {
        StreamEngine? e;
        lock (_gate) { e = _engine; _engine = null; }
        if (e == null) return;
        e.Stop();
        _log($"streaming stopped: sent={e.Stats.PacketsSent} silence={e.Stats.SilencePackets} captures={e.Stats.CaptureCallbacks}");
    }

    /// <summary>Applies new settings; restarts the stream if frames/K changed while streaming.</summary>
    public void ApplyConfig(AppConfig cfg)
    {
        cfg = cfg.Clone().Normalize();
        bool restart;
        lock (_gate)
        {
            restart = _engine != null && (cfg.Frames != _cfg.Frames || cfg.Redundancy != _cfg.Redundancy);
            bool phoneChanged = cfg.PhoneMode != _cfg.PhoneMode || cfg.PhoneIp != _cfg.PhoneIp || cfg.PhoneName != _cfg.PhoneName
                                || cfg.PhonePort != _cfg.PhonePort;
            _cfg = cfg;
            if (phoneChanged && _opt.ForcedPhone == null)
            {
                var (t, n, p) = InitialTarget();
                _rm.Reset(Now(), t, n, p);
                _link.SetTarget(t);
                _window.Clear();
                _lastStats = null;
                _log($"phone selection: {cfg.PhoneMode} {cfg.PhoneName} {cfg.PhoneIp}");
            }
        }
        if (restart) { Stop(); Start(); }
    }

    /// <summary>Sends one DISCOVER round now (menu "Search for phones").</summary>
    public void DiscoverNow()
    {
        lock (_gate) SendDiscoverLocked();
    }

    private void SendDiscoverLocked()
    {
        var targets = _loopbackOnly ? new List<IPAddress> { IPAddress.Loopback } : VcxLink.BroadcastTargets();
        if (!_loopbackOnly) targets.AddRange(NeighborTargetsLocked());
        _link.SendDiscover(targets, _discoverCounter++);
        _lastNeighborSweep = Now();
    }

    private List<IPAddress> NeighborTargetsLocked()
    {
        var list = VcxLink.NeighborTargets();
        string s = string.Join(" ", list);
        if (s != _lastNeighbors) { _log($"neighbors for unicast discover: {(s.Length > 0 ? s : "none")}"); _lastNeighbors = s; }
        return list;
    }

    /// <summary>PC woke from sleep: rebind the socket and re-open capture.</summary>
    public void OnPowerResume()
    {
        _log("system resumed from sleep: rebinding socket and restarting capture");
        bool wasStreaming;
        lock (_gate)
        {
            wasStreaming = _engine != null;
            _link.Rebind();
            _rm.Rearm(Now());
        }
        if (wasStreaming) { Stop(); Start(); }
    }

    /// <summary>Network addresses changed: rebind after a short debounce.</summary>
    public void OnNetworkChanged()
    {
        lock (_gate) _rebindAt = Now() + NetworkDebounceS;
    }

    private void OnStats(PhoneStats st, IPEndPoint from)
    {
        string? toast = null;
        lock (_gate)
        {
            double now = Now();
            bool connected = _rm.OnStats(now, from);
            if (_rm.Target != null && !from.Address.Equals(_rm.Target.Address)) return;
            _lastStats = st;
            _window.Add(now, st.Lost, st.Late);
            if (connected)
            {
                _log($"phone connected: {DisplayNameLocked()} at {from}");
                if (_toasts.OnConnected()) toast = $"Streaming to {DisplayNameLocked()}";
                RememberPhoneLocked();
            }
            var prev = _lastLoggedStats;
            bool changed = prev is { } p && (p.Lost != st.Lost || p.Late != st.Late || p.Underruns != st.Underruns || p.Concealed != st.Concealed);
            if (changed || now - _lastStatsLogAt >= LogStatsEveryS || prev == null)
            {
                _log(st.Format());
                _lastLoggedStats = st;
                _lastStatsLogAt = now;
            }
        }
        if (toast != null) Notify("Phone connected", toast);
    }

    private void OnDiscoverReply(IPEndPoint ep, string name, bool claim)
    {
        string? toast = null;
        lock (_gate)
        {
            bool isNew = !_phones.ContainsKey(name);
            _phones[name] = new DiscoveredPhone(name, ep, DateTime.UtcNow);
            if (isNew) _log($"discovered {name} at {ep}");
            if (_opt.ForcedPhone != null) return;
            var old = _rm.Target;
            if (_rm.OnDiscoverReply(Now(), ep, name, claim))
            {
                _link.SetTarget(ep);
                _log($"target -> {name} at {ep} (was {old?.ToString() ?? "none"}){(claim ? " [claimed: Play pressed on the phone]" : "")}");
                if (old != null) toast = $"Switched to {name} at {ep.Address}";
            }
        }
        if (toast != null) Notify("Phone found", toast);
    }

    /// <summary>Target name; else the name a DISCOVER_REPLY gave for the target address; else the IP.</summary>
    private string DisplayNameLocked()
    {
        if (_rm.TargetName != null) return _rm.TargetName;
        var t = _rm.Target;
        if (t == null) return "?";
        return _phones.Values.FirstOrDefault(p => p.Endpoint.Address.Equals(t.Address))?.Name ?? t.Address.ToString();
    }

    /// <summary>Remember where the phone is, so the next launch starts sending right away.</summary>
    private void RememberPhoneLocked()
    {
        if (_opt.ForcedPhone != null || _rm.Target == null || _cfg.PhoneMode == PhoneMode.Manual) return;
        string ip = _rm.Target.Address.ToString();
        if (_cfg.PhoneIp == ip && _cfg.PhonePort == _rm.Target.Port && (_rm.TargetName == null || _cfg.PhoneName == _rm.TargetName)) return;
        _cfg.PhoneIp = ip;
        _cfg.PhonePort = _rm.Target.Port;
        if (_rm.TargetName != null) _cfg.PhoneName = _rm.TargetName;
        var copy = _cfg.Clone();
        ThreadPool.QueueUserWorkItem(_ => ConfigChanged?.Invoke(copy));
    }

    private void Tick()
    {
        string? lostToast = null, errorToast = null;
        try
        {
            lock (_gate)
            {
                if (_disposed) return;
                double now = Now();
                if (now >= _rebindAt)
                {
                    _rebindAt = double.PositiveInfinity;
                    _log("network change: rebinding socket");
                    _link.Rebind();
                    _rm.Rearm(now);
                }
                if (_engine == null) return;

                if (_rm.Tick(now, out bool lost)) SendDiscoverLocked();
                else if (!_loopbackOnly && _opt.ForcedPhone == null && now - _lastNeighborSweep >= NeighborSweepEveryS)
                {
                    _link.SendDiscover(NeighborTargetsLocked(), _discoverCounter++);
                    _lastNeighborSweep = now;
                }
                if (lost)
                {
                    _log($"phone not answering for {ReconnectMachine.StatsTimeoutS:F0} s; rediscovering (still sending to {_rm.Target})");
                    _toasts.OnLost(now, $"{_rm.TargetName ?? _rm.Target?.Address.ToString()} stopped answering; searchingâ€¦");
                }
                lostToast = _toasts.Tick(now);

                string? err = _engine.CaptureError ?? _link.Error ?? _engineError;
                if (err != _lastNotifiedError)
                {
                    if (err != null) errorToast = err;
                    _lastNotifiedError = err;
                }

                if (now - _lastSenderLogAt >= LogSenderEveryS)
                {
                    var st = _engine.Stats;
                    long sent = Interlocked.Read(ref st.PacketsSent);
                    double ratio = (sent - _lastSenderLogSent) / (now - _lastSenderLogAt) / (Protocol.SampleRate / (double)_cfg.Frames);
                    var core = _engine.Core;
                    _log(string.Create(CultureInfo.InvariantCulture,
                        $"sender: sent={sent} ratio={ratio:F4} silence={st.SilencePackets} captures={st.CaptureCallbacks} " +
                        $"fill_frames={core?.FillFrames} discont_fill={core?.DiscontFill} trimmed={core?.Trimmed} " +
                        $"maxgap={st.TakeMaxGapMs():F2}ms senderr={_link.SendErrors} link={_rm.State} target={_rm.Target}"));
                    _lastSenderLogAt = now;
                    _lastSenderLogSent = sent;
                }
            }
        }
        catch (Exception ex)
        {
            _log($"service tick error: {ex}");
        }
        if (lostToast != null) Notify("Phone lost", lostToast);
        if (errorToast != null) Notify("VCX problem", errorToast);
    }

    private void Notify(string title, string text)
    {
        bool on;
        lock (_gate) on = _cfg.Notifications;
        if (on) Notification?.Invoke(title, text);
    }

    public ServiceSnapshot GetSnapshot()
    {
        lock (_gate)
        {
            double now = Now();
            bool streaming = _engine != null;
            string? capErr = _engine?.CaptureError;
            string? netErr = _link.Error ?? _engineError;
            double age = _rm.SecondsSinceStats(now);
            var state = TrayHealth.Compute(streaming || _engineError != null, capErr, netErr, age);
            var minute = _window.LastWindow();
            var stats = age <= TrayHealth.HealthyStatsAgeS ? _lastStats : null;
            var cutoff = DateTime.UtcNow - PhoneListTtl;
            var phones = _phones.Values.Where(p => p.LastSeenUtc >= cutoff).OrderBy(p => p.Name, StringComparer.OrdinalIgnoreCase).ToList();
            string? name = _rm.Target == null && _rm.TargetName == null ? null : DisplayNameLocked();
            return new ServiceSnapshot(streaming, state, _rm.State, name, _rm.Target, stats, age, minute,
                _engine?.DeviceName, capErr, netErr, _engine?.Stats.PacketsSent ?? 0, phones,
                TrayHealth.Tooltip(state, name, stats, minute, capErr ?? netErr));
        }
    }

    public void Dispose()
    {
        lock (_gate) _disposed = true;
        _timer.Dispose();
        Stop();
        _link.Dispose();
    }
}
