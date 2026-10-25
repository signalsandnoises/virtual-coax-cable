using System.Runtime.InteropServices;

namespace Vcx.Sender;

/// <summary>Sender-side counters, written by the capture thread, read by the stats printer.</summary>
public sealed class SenderStats
{
    public long PacketsSent;
    public long SilencePackets;
    public long CaptureCallbacks;
    public long SendErrors;
    private long _maxGapTicks;  // in 0.1 µs, reset by the reader
    private double _lastSendUs;

    public void OnSend(bool silence, double nowUs)
    {
        Interlocked.Increment(ref PacketsSent);
        if (silence) Interlocked.Increment(ref SilencePackets);
        if (_lastSendUs > 0)
        {
            long gap = (long)((nowUs - _lastSendUs) * 10);
            long cur;
            while (gap > (cur = Interlocked.Read(ref _maxGapTicks)) &&
                   Interlocked.CompareExchange(ref _maxGapTicks, gap, cur) != cur) { }
        }
        _lastSendUs = nowUs;
    }

    /// <summary>Returns the max gap between consecutive sends (ms) since the previous call and resets it.</summary>
    public double TakeMaxGapMs() => Interlocked.Exchange(ref _maxGapTicks, 0) / 10_000.0;
}

/// <summary>
/// Owns the capture thread (MMCSS "Pro Audio"): waits on the WASAPI event, a high-resolution waitable timer
/// and a 2 ms poll fallback; converts float32 → s16; hands chunks to <see cref="StreamCore"/>, which decides
/// what is sent (exact captured frames, discontinuity fill, idle silence fill, resume trim).
/// </summary>
public sealed class StreamEngine
{
    private const double PollUs = 2_000;           // fallback poll in case the loopback event is not signaled
    private const double CaptureRetryUs = 2_000_000;

    private readonly int _frames;
    private readonly int _redundancy;
    private readonly bool _verbose;
    private readonly Action<string> _log;
    private readonly AudioPacketizer.SendHandler _send;
    private Thread? _thread;
    private volatile bool _stop;
    private readonly ManualResetEventSlim _initDone = new(false);
    private Exception? _initError;
    private StreamCore? _core;

    public SenderStats Stats { get; } = new();
    public StreamCore? Core => _core;
    public string? DeviceName { get; private set; }
    public string? DeviceFormat { get; private set; }
    public bool MmcssEnabled { get; private set; }
    public bool HighResTimer { get; private set; }

    public StreamEngine(int frames, int redundancy, bool verbose, Action<string> log, AudioPacketizer.SendHandler send)
    {
        _frames = frames;
        _redundancy = redundancy;
        _verbose = verbose;
        _log = log;
        _send = send;
    }

    /// <summary>Starts the capture thread and waits until the capture device is open. Throws if it can't be opened.</summary>
    public void Start()
    {
        _thread = new Thread(Run) { Name = "vcx-capture", IsBackground = true, Priority = ThreadPriority.Highest };
        _thread.SetApartmentState(ApartmentState.MTA);
        _thread.Start();
        _initDone.Wait();
        if (_initError != null)
        {
            _thread.Join();
            throw _initError;
        }
    }

    public void Stop()
    {
        _stop = true;
        _thread?.Join(2000);
    }

    private byte[] _s16 = new byte[8192];

    private void Run()
    {
        IntPtr avrt = IntPtr.Zero;
        LoopbackCapture? cap = null;
        HighResTimer? timer = null;
        try
        {
            uint taskIndex = 0;
            avrt = Native.AvSetMmThreadCharacteristicsW("Pro Audio", ref taskIndex);
            if (avrt != IntPtr.Zero)
            {
                Native.AvSetMmThreadPriority(avrt, Native.AVRT_PRIORITY_HIGH);
                MmcssEnabled = true;
            }
            Thread.CurrentThread.Priority = ThreadPriority.Highest;

            timer = new HighResTimer();
            HighResTimer = timer.IsHighResolution;

            cap = new LoopbackCapture();
            DeviceName = cap.DeviceName;
            DeviceFormat = cap.FormatDescription;
        }
        catch (Exception ex)
        {
            _initError = ex;
        }
        finally
        {
            if (_initError != null)
            {
                cap?.Dispose();
                timer?.Dispose();
                if (avrt != IntPtr.Zero) Native.AvRevertMmThreadCharacteristics(avrt);
            }
            else
            {
                var pk = new AudioPacketizer(_frames, _redundancy, QpcClock.NowUsInt, SendAndCount);
                _core = new StreamCore(pk, QpcClock.NowUs(), _log);
            }
            _initDone.Set();
        }
        if (_initError != null) return;

        try
        {
            Loop(ref cap, timer!);
        }
        catch (Exception ex)
        {
            _log($"capture thread crashed: {ex}");
        }
        finally
        {
            cap?.Dispose();
            timer!.Dispose();
            if (avrt != IntPtr.Zero) Native.AvRevertMmThreadCharacteristics(avrt);
        }
    }

    private void Loop(ref LoopbackCapture? cap, HighResTimer timer)
    {
        var core = _core!;
        double nextRetryUs = 0;
        var handles1 = new IntPtr[1];
        var handles2 = new IntPtr[2];

        while (!_stop)
        {
            double now = QpcClock.NowUs();
            double deadline = Math.Min(core.NextDeadlineUs(), now + PollUs);
            if (cap == null) deadline = Math.Min(deadline, Math.Max(now, nextRetryUs));

            if (deadline > now)
            {
                timer.ArmRelativeUs(deadline - now);
                IntPtr[] h;
                if (cap != null) { handles2[0] = timer.Handle; handles2[1] = cap.EventHandle; h = handles2; }
                else { handles1[0] = timer.Handle; h = handles1; }
                uint r = Native.WaitForMultipleObjects((uint)h.Length, h, false, 50);
                if (r == Native.WAIT_FAILED)
                    throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "WaitForMultipleObjects failed");
            }

            if (cap != null)
            {
                try
                {
                    int n = cap.Drain(OnChunk);
                    if (n > 0)
                    {
                        Interlocked.Add(ref Stats.CaptureCallbacks, n);
                        core.EndBatch(QpcClock.NowUs());
                    }
                }
                catch (Exception ex)
                {
                    core.EndBatch(QpcClock.NowUs()); // send whatever was read before the error
                    _log($"capture error ({ex.GetType().Name}: {ex.Message}); continuing with silence fill, retrying in 2 s");
                    cap.Dispose();
                    cap = null;
                    nextRetryUs = QpcClock.NowUs() + CaptureRetryUs;
                }
            }
            else if (QpcClock.NowUs() >= nextRetryUs)
            {
                try
                {
                    cap = new LoopbackCapture();
                    core.ResetCapturePosition();
                    _log($"capture reopened: {cap.DeviceName} ({cap.FormatDescription})");
                }
                catch (Exception ex)
                {
                    if (_verbose) _log($"capture reopen failed: {ex.Message}");
                    nextRetryUs = QpcClock.NowUs() + CaptureRetryUs;
                }
            }

            core.OnTick(QpcClock.NowUs());
        }
    }

    private void OnChunk(ReadOnlySpan<float> samples, int frames, ulong devicePosition, bool silent, bool discontinuity)
    {
        int bytes = frames * Protocol.BytesPerFrame;
        if (_s16.Length < bytes) _s16 = new byte[bytes * 2];
        var dst = _s16.AsSpan(0, bytes);
        if (silent) dst.Clear();
        else Pcm.FloatToS16Le(samples, dst);
        _core!.OnChunk(dst, frames, devicePosition, discontinuity);
    }

    private void SendAndCount(ReadOnlySpan<byte> packet, bool silence)
    {
        _send(packet, silence);
        Stats.OnSend(silence, QpcClock.NowUs());
    }
}
