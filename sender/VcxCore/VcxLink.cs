using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Vcx.Sender;

/// <summary>
/// The one UDP socket (ephemeral port) used for AUDIO, DISCOVER and receiving STATS / DISCOVER_REPLY,
/// plus its receive thread. <see cref="Rebind"/> swaps in a fresh socket (network change, wake from sleep).
/// </summary>
public sealed class VcxLink : IDisposable
{
    private sealed class Bound(Socket socket) { public readonly Socket Socket = socket; }

    private readonly IPAddress _bindAddress;
    private readonly Action<string> _log;
    private readonly object _gate = new();
    private volatile Bound? _bound;
    private volatile SocketAddress? _target;
    private int _consecutiveSendErrors;
    private volatile string? _sendError;
    private volatile string? _bindError;
    private long _lastErrLogTicks;
    private bool _disposed;

    public event Action<PhoneStats, IPEndPoint>? StatsReceived;
    /// <summary>(audio endpoint = reply source IP + port from the reply body, device name)</summary>
    public event Action<IPEndPoint, string>? DiscoverReplyReceived;

    public long SendErrors;
    public EndPoint? LocalEndPoint => _bound?.Socket.LocalEndPoint;
    /// <summary>Null when the socket is bound and sends succeed.</summary>
    public string? Error => _bindError ?? _sendError;

    public VcxLink(IPAddress bindAddress, Action<string> log)
    {
        _bindAddress = bindAddress;
        _log = log;
    }

    /// <summary>Opens (or re-opens) the socket. Errors are reported via <see cref="Error"/>, not thrown.</summary>
    public void Rebind()
    {
        lock (_gate)
        {
            if (_disposed) return;
            Socket? s = null;
            try
            {
                s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { EnableBroadcast = true };
                try { s.IOControl(unchecked((int)0x9800000C) /* SIO_UDP_CONNRESET off */, new byte[] { 0 }, null); } catch { /* best effort */ }
                s.Bind(new IPEndPoint(_bindAddress, 0));
            }
            catch (Exception ex)
            {
                s?.Dispose();
                _bindError = $"socket: {ex.Message}";
                _log($"socket bind failed: {ex.Message}");
                return;
            }
            var old = _bound;
            var b = new Bound(s);
            _bound = b;
            _bindError = null;
            _sendError = null;
            _consecutiveSendErrors = 0;
            old?.Socket.Dispose(); // its receive thread exits
            new Thread(() => ReceiveLoop(b)) { IsBackground = true, Name = "vcx-rx" }.Start();
            _log($"socket bound to {s.LocalEndPoint}");
        }
    }

    public void SetTarget(IPEndPoint? ep) => _target = ep?.Serialize();

    public void SendAudio(ReadOnlySpan<byte> packet)
    {
        var b = _bound;
        var t = _target;
        if (b == null || t == null) return;
        try
        {
            b.Socket.SendTo(packet, SocketFlags.None, t);
            if (_consecutiveSendErrors != 0) { _consecutiveSendErrors = 0; _sendError = null; }
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
            Interlocked.Increment(ref SendErrors);
            if (ex is ObjectDisposedException) return; // racing a rebind
            if (++_consecutiveSendErrors >= 200) _sendError = $"send failing: {ex.Message}"; // ~0.5 s of failures
            long now = Environment.TickCount64;
            if (now - _lastErrLogTicks > 5000) { _lastErrLogTicks = now; _log($"send error: {ex.Message}"); }
        }
    }

    public void SendDiscover(IReadOnlyList<IPAddress> targets, uint counter)
    {
        var b = _bound;
        if (b == null) return;
        var pkt = Protocol.BuildDiscover(Environment.MachineName, counter, QpcClock.NowUsInt());
        foreach (var t in targets)
        {
            try { b.Socket.SendTo(pkt, new IPEndPoint(t, Protocol.DiscoverPort)); }
            catch (Exception ex) when (ex is SocketException or ObjectDisposedException) { /* interface down; next round */ }
        }
    }

    /// <summary>255.255.255.255 plus each up IPv4 interface's subnet broadcast (e.g. 192.168.137.255 for the hotspot).</summary>
    public static List<IPAddress> BroadcastTargets()
    {
        var list = new List<IPAddress> { IPAddress.Broadcast };
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    byte[] ip = ua.Address.GetAddressBytes(), mask = ua.IPv4Mask.GetAddressBytes();
                    if (mask.All(x => x == 0)) continue;
                    var bc = new byte[4];
                    for (int i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | ~mask[i]);
                    var a = new IPAddress(bc);
                    if (!list.Contains(a)) list.Add(a);
                }
            }
        }
        catch (NetworkInformationException) { /* limited broadcast only */ }
        return list;
    }

    private void ReceiveLoop(Bound b)
    {
        var buf = new byte[2048];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        while (true)
        {
            int n;
            try { n = b.Socket.ReceiveFrom(buf, SocketFlags.None, ref from); }
            catch (SocketException ex) when (ex.SocketErrorCode is SocketError.ConnectionReset or SocketError.MessageSize) { continue; }
            catch (Exception) { return; } // closed (rebind/dispose)
            var pkt = buf.AsSpan(0, n);
            var src = (IPEndPoint)from;
            if (!Protocol.TryParseHeader(pkt, out byte type)) continue;
            try
            {
                if (type == Protocol.TypeStats && Protocol.TryParseStats(pkt, out var st))
                    StatsReceived?.Invoke(st, src);
                else if (type == Protocol.TypeDiscoverReply && Protocol.TryParseDiscoverReply(pkt, out ushort port, out string name))
                    DiscoverReplyReceived?.Invoke(new IPEndPoint(src.Address, port), name.Length > 0 ? name : src.Address.ToString());
            }
            catch (Exception ex)
            {
                _log($"receive handler error: {ex.Message}");
            }
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _disposed = true;
            _bound?.Socket.Dispose();
            _bound = null;
        }
    }
}
