namespace Vcx.Sender;

/// <summary>
/// Holds back "phone lost" until the phone has been gone for <see cref="LostDelayS"/>, and only says
/// "phone connected" for the first connection or after a lost toast was shown. A phone that stops and
/// restarts (e.g. to apply a setting) therefore makes no toast at all. Not thread-safe; callers lock.
/// </summary>
public sealed class ToastDebouncer
{
    /// <summary>Seconds after the "lost" event (itself 5 s without STATS) before the toast shows.</summary>
    public const double LostDelayS = 10;

    private bool _everConnected;
    private bool _lostShown;
    private double _lostAt = double.NaN;
    private string? _lostText;

    /// <summary>Phone (re)connected: true when a toast should say so.</summary>
    public bool OnConnected()
    {
        bool show = !_everConnected || _lostShown;
        _everConnected = true;
        _lostShown = false;
        _lostAt = double.NaN;
        _lostText = null;
        return show;
    }

    /// <summary>The phone stopped answering: arm the delayed toast.</summary>
    public void OnLost(double now, string text)
    {
        if (_lostShown || !double.IsNaN(_lostAt)) return;
        _lostAt = now;
        _lostText = text;
    }

    /// <summary>The pending lost toast once it is due, else null.</summary>
    public string? Tick(double now)
    {
        if (double.IsNaN(_lostAt) || now - _lostAt < LostDelayS) return null;
        var text = _lostText;
        _lostAt = double.NaN;
        _lostText = null;
        _lostShown = true;
        return text;
    }
}
