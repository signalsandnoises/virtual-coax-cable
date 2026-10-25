using System.Globalization;

namespace Vcx.Sender;

/// <summary>Tray icon states.</summary>
public enum TrayState
{
    Idle,      // not streaming
    Healthy,   // streaming, STATS arriving
    NoStats,   // streaming, but the phone is not answering (or not found yet)
    Error,     // streaming requested but capture/network is broken
}

public static class TrayHealth
{
    /// <summary>STATS come about once a second; older than this counts as "not answering".</summary>
    public const double HealthyStatsAgeS = 3.0;

    public static TrayState Compute(bool streaming, string? captureError, string? networkError, double statsAgeS)
    {
        if (!streaming) return TrayState.Idle;
        if (captureError != null || networkError != null) return TrayState.Error;
        return statsAgeS <= HealthyStatsAgeS ? TrayState.Healthy : TrayState.NoStats;
    }

    /// <summary>Tooltip text, at most 127 characters (NotifyIcon limit).</summary>
    public static string Tooltip(TrayState state, string? phoneName, PhoneStats? last, (uint lost, uint late)? lastMinute,
        string? error)
    {
        var inv = CultureInfo.InvariantCulture;
        string s = state switch
        {
            TrayState.Idle => "VCX: stopped",
            TrayState.Error => $"VCX error: {error}",
            TrayState.NoStats => phoneName == null ? "VCX: looking for phone…" : $"VCX: {phoneName} not answering",
            _ => phoneName == null ? "VCX: streaming" : $"VCX → {phoneName}",
        };
        if (state == TrayState.Healthy && last is { } st)
        {
            s += string.Create(inv, $"\nbuf {st.BufferUs / 1000.0:F0}/{st.TargetUs / 1000.0:F0} ms");
            if (lastMinute is { } m) s += string.Create(inv, $"\nlast min: lost {m.lost}, late {m.late}");
        }
        return s.Length <= 127 ? s : s[..126] + "…";
    }
}

/// <summary>Turns cumulative lost/late counters from STATS into "in the last minute" values.</summary>
public sealed class StatsWindow
{
    private readonly double _windowS;
    private readonly Queue<(double t, uint lost, uint late)> _samples = new();

    public StatsWindow(double windowS = 60) => _windowS = windowS;

    public void Clear() => _samples.Clear();

    public void Add(double now, uint lost, uint late)
    {
        // Counters went backwards → phone restarted its stream; start over.
        if (_samples.Count > 0)
        {
            var last = _samples.Last();
            if (lost < last.lost || late < last.late) _samples.Clear();
        }
        _samples.Enqueue((now, lost, late));
        // Keep exactly one sample at or before the window start as the baseline.
        while (_samples.Count >= 2 && _samples.ElementAt(1).t <= now - _windowS) _samples.Dequeue();
    }

    /// <summary>Increase over (at most) the last window; null without samples.</summary>
    public (uint lost, uint late)? LastWindow()
    {
        if (_samples.Count == 0) return null;
        var first = _samples.Peek();
        var last = _samples.Last();
        return (last.lost - first.lost, last.late - first.late);
    }
}
