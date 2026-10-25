using System.Net;

namespace Vcx.Sender;

public enum LinkState
{
    /// <summary>No phone known yet; discovering.</summary>
    NoTarget,
    /// <summary>Sending to a phone, waiting for its first STATS (grace period after start or a switch).</summary>
    Waiting,
    /// <summary>STATS arriving.</summary>
    Connected,
    /// <summary>No STATS for <see cref="ReconnectMachine.StatsTimeoutS"/>: discovering again, still sending to the last target.</summary>
    Searching,
}

/// <summary>
/// Pure reconnect logic (times in seconds, supplied by the caller). Not thread-safe: the owner serializes calls.
/// - Connected while STATS arrive. After <see cref="StatsTimeoutS"/> without STATS (or without a target),
///   it asks for a DISCOVER round every <see cref="DiscoverIntervalS"/> and keeps the current target.
/// - A DISCOVER_REPLY while searching switches the target, if it matches the preferred device name (when set).
/// - After a switch the new target gets a fresh grace period before searching resumes.
/// - A claim (DISCOVER_REPLY with <see cref="Protocol.FlagClaim"/>: Play was just pressed on that phone) switches
///   in any state, so the phone you last pressed Play on wins. Phones stop claiming once audio arrives, so two
///   playing phones can't keep taking the stream from each other.
/// </summary>
public sealed class ReconnectMachine
{
    public const double StatsTimeoutS = 5.0;
    public const double DiscoverIntervalS = 3.0;

    private double _lastStats = double.NegativeInfinity;
    private double _targetSince;
    private double _lastDiscover = double.NegativeInfinity;
    private bool _everConnected;

    public IPEndPoint? Target { get; private set; }
    public string? TargetName { get; private set; }
    /// <summary>When set, only replies from a phone with this name are accepted while searching.</summary>
    public string? PreferredName { get; private set; }
    public LinkState State { get; private set; }

    public ReconnectMachine(double now, IPEndPoint? target, string? targetName, string? preferredName)
    {
        Reset(now, target, targetName, preferredName);
    }

    /// <summary>New user choice (phone picker / settings) or restart.</summary>
    public void Reset(double now, IPEndPoint? target, string? targetName, string? preferredName)
    {
        Target = target;
        TargetName = targetName ?? preferredName;
        PreferredName = preferredName;
        _targetSince = now;
        _lastStats = double.NegativeInfinity;
        _lastDiscover = double.NegativeInfinity;
        _everConnected = false;
        State = target == null ? LinkState.NoTarget : LinkState.Waiting;
    }

    /// <summary>Restart the grace period (e.g. after the socket was rebound or the PC woke up).</summary>
    public void Rearm(double now)
    {
        _targetSince = now;
        _lastStats = double.NegativeInfinity;
        if (Target != null && State != LinkState.Connected) State = LinkState.Waiting;
    }

    /// <summary>STATS arrived from <paramref name="from"/>. Returns true if this is a (re)connection.</summary>
    public bool OnStats(double now, IPEndPoint from)
    {
        // STATS come from the phone's audio socket (= the target); ignore strays from other phones.
        if (Target != null && !from.Address.Equals(Target.Address)) return false;
        bool wasConnected = State == LinkState.Connected;
        _lastStats = now;
        State = LinkState.Connected;
        _everConnected = true;
        return !wasConnected;
    }

    /// <summary>Advance time. Returns true when the owner should send a DISCOVER round now.
    /// <paramref name="lost"/> is true exactly once when a connected phone stops answering.</summary>
    public bool Tick(double now, out bool lost)
    {
        lost = false;
        double quietSince = Math.Max(_lastStats, _targetSince);
        if (Target == null)
        {
            State = LinkState.NoTarget;
        }
        else if (now - quietSince >= StatsTimeoutS)
        {
            if (State == LinkState.Connected) lost = true;
            State = LinkState.Searching;
        }
        if (State is LinkState.NoTarget or LinkState.Searching && now - _lastDiscover >= DiscoverIntervalS)
        {
            _lastDiscover = now;
            return true;
        }
        return false;
    }

    /// <summary>A DISCOVER_REPLY arrived. Returns true if the target switched to it.</summary>
    public bool OnDiscoverReply(double now, IPEndPoint audioEndpoint, string name, bool claim = false)
    {
        if (PreferredName != null && !string.Equals(name, PreferredName, StringComparison.Ordinal)) return false;
        bool same = Target != null && Target.Equals(audioEndpoint);
        if (claim && same && State is LinkState.Connected or LinkState.Waiting) return false;
        if (!claim && State is not (LinkState.NoTarget or LinkState.Searching)) return false;
        Target = audioEndpoint;
        TargetName = name;
        _targetSince = now;
        State = LinkState.Waiting;
        return !same;
    }

    public bool EverConnected => _everConnected;
    public double SecondsSinceStats(double now) => now - _lastStats;
}
