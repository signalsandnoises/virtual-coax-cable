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
/// Owns the capture thread: WASAPI loopback capture, float→s16, packetizing, sending, and silence fill
/// paced by QPC + a high-resolution waitable timer when loopback delivers nothing.
///
/// Timeline model: every frame we send covers a span of "audio time" on the QPC clock. Real chunks carry
/// their QPC position from WASAPI. When no capture data has arrived for <see cref="SilenceThresholdUs"/>,
/// we pad the partial packet, then emit silence packets so that the stream position trails real time by
/// the threshold (exactly 48 kHz on QPC). When capture resumes, the gap between the end of the silence and
/// the first real frame is filled with zeros, so the phone's buffer depth is the same before and after.
/// Sequence numbers come from one packetizer, so they never jump.
/// </summary>
public sealed class StreamEngine
{
    public const double SilenceThresholdUs = 20_000;
    private const double PollUs = 2_000;           // fallback poll in case the loopback event is not signaled
    private const double MaxGapFillUs = 200_000;   // larger resume gaps are not filled (logged instead)
    private const double CaptureRetryUs = 2_000_000;

    private readonly int _frames;
    private readonly int _redundancy;
    private readonly bool _verbose;
    private readonly Action<string> _log;
    private readonly AudioPacketizer.SendHandler _send;
    private readonly double _periodUs;
    private Thread? _thread;
    private volatile bool _stop;
    private readonly ManualResetEventSlim _initDone = new(false);
    private Exception? _initError;

    public SenderStats Stats { get; } = new();
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
        _periodUs = frames * 1_000_000.0 / Protocol.SampleRate;
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

    // ---- capture-thread state ----
    private enum Mode { Real, Silence }
    private Mode _mode;
    private double _silenceAnchorUs;
    private long _silenceCount;
    private double _lastDataArrivalUs;
    private double _lastChunkEndUs;
    private AudioPacketizer _pk = null!;
    private byte[] _s16 = new byte[4096];

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

            _pk = new AudioPacketizer(_frames, _redundancy, QpcClock.NowUsInt, SendAndCount);

            try
            {
                cap = new LoopbackCapture();
                DeviceName = cap.DeviceName;
                DeviceFormat = cap.FormatDescription;
            }
            catch (Exception ex)
            {
                _initError = ex;
                return;
            }
        }
        catch (Exception ex)
        {
            _initError ??= ex;
            return;
        }
        finally
        {
            _initDone.Set();
            if (_initError != null)
            {
                cap?.Dispose();
                timer?.Dispose();
                if (avrt != IntPtr.Zero) Native.AvRevertMmThreadCharacteristics(avrt);
            }
        }

        try
        {
            Loop(ref cap, timer);
        }
        catch (Exception ex)
        {
            _log($"capture thread crashed: {ex}");
        }
        finally
        {
            cap?.Dispose();
            timer.Dispose();
            if (avrt != IntPtr.Zero) Native.AvRevertMmThreadCharacteristics(avrt);
        }
    }

    private void Loop(ref LoopbackCapture? cap, HighResTimer timer)
    {
        double now = QpcClock.NowUs();
        // Start in silence mode, anchored so the first silence packet is due immediately.
        _mode = Mode.Silence;
        _silenceAnchorUs = now - SilenceThresholdUs - _periodUs;
        _silenceCount = 0;
        double nextRetryUs = 0;
        var handles1 = new IntPtr[1];
        var handles2 = new IntPtr[2];

        while (!_stop)
        {
            now = QpcClock.NowUs();
            double deadline = _mode == Mode.Silence
                ? _silenceAnchorUs + (_silenceCount + 1) * _periodUs + SilenceThresholdUs
                : _lastDataArrivalUs + SilenceThresholdUs;
            deadline = Math.Min(deadline, now + PollUs);
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
                    if (n > 0) Interlocked.Add(ref Stats.CaptureCallbacks, n);
                }
                catch (Exception ex)
                {
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
                    _log($"capture reopened: {cap.DeviceName} ({cap.FormatDescription})");
                }
                catch (Exception ex)
                {
                    if (_verbose) _log($"capture reopen failed: {ex.Message}");
                    nextRetryUs = QpcClock.NowUs() + CaptureRetryUs;
                }
            }

            now = QpcClock.NowUs();
            if (_mode == Mode.Real && now - _lastDataArrivalUs >= SilenceThresholdUs)
                EnterSilence(now);

            if (_mode == Mode.Silence)
            {
                while (_silenceAnchorUs + (_silenceCount + 1) * _periodUs + SilenceThresholdUs <= now)
                {
                    _pk.EmitSilence();
                    _silenceCount++;
                }
            }
        }
    }

    private void EnterSilence(double now)
    {
        int pad = _pk.FlushPartial();
        double anchor = _lastChunkEndUs + pad * 1_000_000.0 / Protocol.SampleRate;
        // Bound the catch-up burst if the capture timestamps were odd.
        double minAnchor = now - SilenceThresholdUs - 4 * _periodUs;
        double maxAnchor = now - SilenceThresholdUs;
        _silenceAnchorUs = Math.Clamp(anchor, minAnchor, maxAnchor);
        _silenceCount = 0;
        _mode = Mode.Silence;
        if (_verbose) _log($"silence fill on (pad {pad} frames, anchor {(anchor - now) / 1000.0:F1} ms)");
    }

    private void OnChunk(ReadOnlySpan<float> samples, int frames, double firstFrameQpcUs, bool silent)
    {
        double now = QpcClock.NowUs();
        double durUs = frames * 1_000_000.0 / Protocol.SampleRate;
        double t0 = firstFrameQpcUs;
        if (t0 <= 0 || Math.Abs(now - (t0 + durUs)) > 500_000) t0 = now - durUs; // missing/odd timestamp

        if (_mode == Mode.Silence)
        {
            double silenceEnd = _silenceAnchorUs + _silenceCount * _periodUs;
            double gapUs = t0 - silenceEnd;
            int fill = 0;
            if (gapUs > 0 && gapUs <= MaxGapFillUs)
            {
                fill = (int)Math.Round(gapUs * Protocol.SampleRate / 1_000_000.0);
                _pk.AddZeroFrames(fill);
            }
            _mode = Mode.Real;
            if (_verbose) _log($"capture resumed (gap {gapUs / 1000.0:F1} ms, filled {fill} frames)");
        }

        int bytes = frames * Protocol.BytesPerFrame;
        if (_s16.Length < bytes) _s16 = new byte[bytes * 2];
        var dst = _s16.AsSpan(0, bytes);
        if (silent) dst.Clear();
        else Pcm.FloatToS16Le(samples, dst);
        _pk.AddBytes(dst);

        _lastDataArrivalUs = now;
        _lastChunkEndUs = t0 + durUs;
    }

    private void SendAndCount(ReadOnlySpan<byte> packet, bool silence)
    {
        _send(packet, silence);
        Stats.OnSend(silence, QpcClock.NowUs());
    }
}
