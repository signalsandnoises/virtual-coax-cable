using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Vcx.Sender;

public sealed class Options
{
    public IPAddress? Phone;
    public int Port = Protocol.AudioPort;
    public int Frames = 120;
    public int Redundancy = 0;
    public double DurationS = 0;
    public bool DiscoverOnly;
    public bool Verbose;
    public IPAddress? DiscoverAddr; // advanced/testing: send DISCOVER only to this address

    public const string Usage =
        "usage: vcx-sender [--phone IP] [--port 47800] [--frames 120] [--redundancy 0] [--duration SECONDS]\n" +
        "                  [--discover-only] [--discover-addr IP] [--verbose]\n" +
        "  --phone IP         send to this phone (skips discovery)\n" +
        "  --port N           phone audio port when --phone is given (default 47800)\n" +
        "  --frames N         frames per packet at 48 kHz (default 120 = 2.5 ms)\n" +
        "  --redundancy K     previous payloads appended to each packet (default 0)\n" +
        "  --duration S       stop after S seconds (default 0 = until Ctrl+C)\n" +
        "  --discover-only    print all phones that reply within 3 s, then exit (0 found, 1 none)\n" +
        "  --discover-addr IP send DISCOVER only to this address instead of the broadcasts (testing)\n" +
        "  --verbose          more logging";

    public static Options Parse(string[] args)
    {
        var o = new Options();
        for (int i = 0; i < args.Length; i++)
        {
            string a = args[i];
            string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"{a} needs a value");
            int NextInt(int min, int max)
            {
                var s = Next();
                if (!int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out int v) || v < min || v > max)
                    throw new ArgumentException($"{a}: '{s}' is not an integer in [{min}, {max}]");
                return v;
            }
            switch (a)
            {
                case "--phone": o.Phone = ParseIp(a, Next()); break;
                case "--port": o.Port = NextInt(1, 65535); break;
                case "--frames":
                    // header field is u16; keep a whole packet under a typical 1500-byte MTU-ish budget is the user's call
                    o.Frames = NextInt(1, 4800); break;
                case "--redundancy": o.Redundancy = NextInt(0, 8); break;
                case "--duration":
                    var d = Next();
                    if (!double.TryParse(d, NumberStyles.Float, CultureInfo.InvariantCulture, out o.DurationS) || o.DurationS < 0)
                        throw new ArgumentException($"--duration: '{d}' is not a non-negative number");
                    break;
                case "--discover-only": o.DiscoverOnly = true; break;
                case "--discover-addr": o.DiscoverAddr = ParseIp(a, Next()); break;
                case "--verbose": case "-v": o.Verbose = true; break;
                case "--help": case "-h": case "/?": throw new HelpRequested();
                default: throw new ArgumentException($"unknown argument '{a}'");
            }
        }
        if (Protocol.AudioPacketSize(o.Frames, o.Redundancy) > 65507)
            throw new ArgumentException("--frames x (1 + --redundancy) is too large for one UDP datagram");
        return o;
    }

    private static IPAddress ParseIp(string flag, string s) =>
        IPAddress.TryParse(s, out var ip) && ip.AddressFamily == AddressFamily.InterNetwork
            ? ip : throw new ArgumentException($"{flag}: '{s}' is not an IPv4 address");
}

public sealed class HelpRequested : Exception;

public static class Program
{
    private static readonly object ConsoleLock = new();
    private static void Log(string s)
    {
        lock (ConsoleLock) Console.WriteLine(s);
    }

    public static int Main(string[] args)
    {
        Options opt;
        try { opt = Options.Parse(args); }
        catch (HelpRequested) { Console.WriteLine(Options.Usage); return 0; }
        catch (ArgumentException ex) { Console.Error.WriteLine($"error: {ex.Message}\n{Options.Usage}"); return 2; }

        using var stop = new ManualResetEventSlim(false);
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Set(); };

        // One UDP socket on an ephemeral port for discover, audio and replies (PROTOCOL.md).
        // Bind to loopback when only talking to loopback, so local tests never touch real interfaces.
        bool loopbackOnly = (opt.Phone != null && IPAddress.IsLoopback(opt.Phone) && opt.DiscoverAddr == null) ||
                            (opt.Phone == null && opt.DiscoverAddr != null && IPAddress.IsLoopback(opt.DiscoverAddr));
        using var sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        sock.EnableBroadcast = true;
        try { sock.IOControl(unchecked((int)0x9800000C) /* SIO_UDP_CONNRESET */, new byte[] { 0 }, null); } catch { /* best effort */ }
        sock.Bind(new IPEndPoint(loopbackOnly ? IPAddress.Loopback : IPAddress.Any, 0));
        if (opt.Verbose) Log($"socket bound to {sock.LocalEndPoint}");

        var replies = new BlockingCollection<(IPEndPoint ep, string name)>();
        var rx = new Thread(() => ReceiveLoop(sock, replies, opt.Verbose)) { IsBackground = true, Name = "vcx-rx" };
        rx.Start();

        try
        {
            if (opt.DiscoverOnly)
                return DiscoverAll(sock, replies, opt, stop);

            IPEndPoint target;
            if (opt.Phone != null)
            {
                target = new IPEndPoint(opt.Phone, opt.Port);
            }
            else
            {
                var found = DiscoverFirst(sock, replies, opt, stop);
                if (found == null)
                {
                    Console.Error.WriteLine("error: no phone replied to DISCOVER within 5 s (is the receiver running? or use --phone IP)");
                    return 1;
                }
                target = found.Value.ep;
                Log($"found {found.Value.name} at {target.Address}:{target.Port}");
            }

            return Stream(sock, target, opt, stop);
        }
        finally
        {
            sock.Close();
            rx.Join(1000);
        }
    }

    private static int Stream(Socket sock, IPEndPoint target, Options opt, ManualResetEventSlim stop)
    {
        var targetSa = target.Serialize();
        long lastErrLogUs = 0;
        StreamEngine? engine = null;
        engine = new StreamEngine(opt.Frames, opt.Redundancy, opt.Verbose, Log, (pkt, _) =>
        {
            try { sock.SendTo(pkt, SocketFlags.None, targetSa); }
            catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
            {
                Interlocked.Increment(ref engine!.Stats.SendErrors);
                long now = (long)QpcClock.NowUs();
                if (now - lastErrLogUs > 1_000_000) { lastErrLogUs = now; Log($"send error: {ex.Message}"); }
            }
        });

        try { engine.Start(); }
        catch (UnsupportedFormatException ex) { Console.Error.WriteLine($"error: {ex.Message}"); return 2; }
        catch (Exception ex) { Console.Error.WriteLine($"error: cannot open loopback capture: {ex.Message}"); return 2; }

        Log($"capturing '{engine.DeviceName}' ({engine.DeviceFormat}); mmcss={(engine.MmcssEnabled ? "Pro Audio" : "FAILED")} " +
            $"hires-timer={(engine.HighResTimer ? "yes" : "no")}");
        Log($"streaming to {target} from {sock.LocalEndPoint}: {opt.Frames} frames/packet " +
            $"({opt.Frames * 1000.0 / Protocol.SampleRate:0.###} ms), K={opt.Redundancy}, " +
            $"{Protocol.AudioPacketSize(opt.Frames, opt.Redundancy)} B/packet. Ctrl+C to stop.");

        var st = engine.Stats;
        double startUs = QpcClock.NowUs();
        double endUs = opt.DurationS > 0 ? startUs + opt.DurationS * 1e6 : double.MaxValue;
        var core = engine.Core!;
        double nominalRate = (double)Protocol.SampleRate / opt.Frames;
        long lastSent = 0, lastSil = 0, lastCb = 0, lastCap = 0, lastFill = 0, lastDisc = 0, lastTrim = 0;
        double lastPrintUs = startUs;
        while (true)
        {
            double now = QpcClock.NowUs();
            double nextPrint = lastPrintUs + 5e6;
            double waitUs = Math.Min(nextPrint, endUs) - now;
            if (waitUs > 0 && stop.Wait(TimeSpan.FromMilliseconds(Math.Min(waitUs / 1000.0, 1000)))) break;
            now = QpcClock.NowUs();
            if (now >= nextPrint || now >= endUs)
            {
                long sent = Interlocked.Read(ref st.PacketsSent), sil = Interlocked.Read(ref st.SilencePackets),
                     cb = Interlocked.Read(ref st.CaptureCallbacks), cap = Interlocked.Read(ref core.CapturedFrames),
                     fill = Interlocked.Read(ref core.FillFrames), disc = Interlocked.Read(ref core.DiscontFill),
                     trim = Interlocked.Read(ref core.Trimmed);
                double secs = (now - lastPrintUs) / 1e6;
                if (secs > 0.5 || now < endUs)
                    Log(string.Create(CultureInfo.InvariantCulture,
                        $"sender: sent=+{sent - lastSent} ({sent}) ratio={(sent - lastSent) / secs / nominalRate:F4} silence=+{sil - lastSil} " +
                        $"captures=+{cb - lastCb} cap_frames=+{cap - lastCap} fill_frames=+{fill - lastFill} " +
                        $"discont_fill=+{disc - lastDisc} trimmed=+{trim - lastTrim} maxgap={st.TakeMaxGapMs():F2}ms " +
                        $"senderr={Interlocked.Read(ref st.SendErrors)}"));
                lastSent = sent; lastSil = sil; lastCb = cb; lastCap = cap; lastFill = fill; lastDisc = disc; lastTrim = trim;
                lastPrintUs = now;
            }
            if (now >= endUs) break;
        }

        double total = (QpcClock.NowUs() - startUs) / 1e6;
        engine.Stop();
        Log(string.Create(CultureInfo.InvariantCulture,
            $"stopped after {total:F1} s: sent={st.PacketsSent} ratio={st.PacketsSent / total / nominalRate:F4} " +
            $"silence={st.SilencePackets} captures={st.CaptureCallbacks} cap_frames={core.CapturedFrames} " +
            $"fill_frames={core.FillFrames} discont_fill={core.DiscontFill} discont_events={core.DiscontEvents} " +
            $"trimmed={core.Trimmed} resumes={core.Resumes} senderr={st.SendErrors}"));
        return 0;
    }

    private static List<IPAddress> BroadcastTargets(Options opt)
    {
        if (opt.DiscoverAddr != null) return [opt.DiscoverAddr];
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
                    if (mask.All(b => b == 0)) continue;
                    var b = new byte[4];
                    for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                    var bc = new IPAddress(b);
                    if (!list.Contains(bc)) list.Add(bc);
                }
            }
        }
        catch (NetworkInformationException) { /* fall back to the limited broadcast only */ }
        return list;
    }

    private static void SendDiscover(Socket sock, List<IPAddress> targets, uint counter, bool verbose)
    {
        var pkt = Protocol.BuildDiscover(Environment.MachineName, counter, QpcClock.NowUsInt());
        foreach (var t in targets)
        {
            try { sock.SendTo(pkt, new IPEndPoint(t, Protocol.DiscoverPort)); }
            catch (SocketException ex) { if (verbose) Log($"DISCOVER to {t} failed: {ex.Message}"); }
        }
        if (verbose && counter == 0) Log($"DISCOVER -> {string.Join(", ", targets)} :{Protocol.DiscoverPort}");
    }

    private static (IPEndPoint ep, string name)? DiscoverFirst(Socket sock, BlockingCollection<(IPEndPoint, string)> replies,
        Options opt, ManualResetEventSlim stop)
    {
        var targets = BroadcastTargets(opt);
        Log("discovering phones...");
        for (uint i = 0; i < 10 && !stop.IsSet; i++) // every 500 ms for up to 5 s
        {
            SendDiscover(sock, targets, i, opt.Verbose);
            if (replies.TryTake(out var r, 500)) return r;
        }
        return null;
    }

    private static int DiscoverAll(Socket sock, BlockingCollection<(IPEndPoint, string)> replies, Options opt, ManualResetEventSlim stop)
    {
        var targets = BroadcastTargets(opt);
        var seen = new HashSet<string>();
        double end = QpcClock.NowUs() + 3e6, nextSend = 0;
        uint counter = 0;
        while (!stop.IsSet)
        {
            double now = QpcClock.NowUs();
            if (now >= end) break;
            if (now >= nextSend) { SendDiscover(sock, targets, counter++, opt.Verbose); nextSend = now + 500_000; }
            int waitMs = (int)Math.Max(1, (Math.Min(end, nextSend) - now) / 1000);
            if (replies.TryTake(out var r, waitMs) && seen.Add(r.Item1.ToString()))
                Log($"found {r.Item2} at {r.Item1.Address}:{r.Item1.Port}");
        }
        if (seen.Count == 0) Log("no phones found");
        return seen.Count > 0 ? 0 : 1;
    }

    private static void ReceiveLoop(Socket sock, BlockingCollection<(IPEndPoint, string)> replies, bool verbose)
    {
        var buf = new byte[2048];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        while (true)
        {
            int n;
            try { n = sock.ReceiveFrom(buf, SocketFlags.None, ref from); }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionReset || ex.SocketErrorCode == SocketError.MessageSize) { continue; }
            catch (Exception) { return; } // socket closed
            var pkt = buf.AsSpan(0, n);
            var src = (IPEndPoint)from;
            if (!Protocol.TryParseHeader(pkt, out byte type))
            {
                if (verbose) Log($"ignored {n}-byte non-VCX datagram from {src}");
                continue;
            }
            switch (type)
            {
                case Protocol.TypeStats when Protocol.TryParseStats(pkt, out var s):
                    Log(verbose ? $"{s.Format()} from={src}" : s.Format());
                    break;
                case Protocol.TypeDiscoverReply when Protocol.TryParseDiscoverReply(pkt, out ushort port, out string name):
                    replies.Add((new IPEndPoint(src.Address, port), name.Length > 0 ? name : src.Address.ToString()));
                    break;
                default:
                    if (verbose) Log($"ignored VCX type {type} ({n} B) from {src}");
                    break;
            }
        }
    }
}
