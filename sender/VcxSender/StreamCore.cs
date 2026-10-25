namespace Vcx.Sender;

/// <summary>
/// Clock-free stream logic (no WASAPI, no sockets), so it can be unit-tested with a fake capture source.
///
/// Rules:
/// - While capture is active the stream contains exactly the captured frames. WASAPI period jitter
///   (late deliveries of 20-30 ms) changes nothing. Zeros are inserted only for a device discontinuity:
///   a forward jump of the WASAPI device position between consecutive chunks (counted as DiscontFill).
/// - Silence fill starts only after capture has delivered nothing for <see cref="SilenceThresholdUs"/>
///   (render idle). It then keeps the stream on the real-time clock: its anchor is where the captured audio
///   ended (arrival time of the last batch), it catches up to "now" once, and then emits one packet per
///   frames/48000 s on the QPC clock.
/// - On resume, the captured batch is placed on the same clock (batch start = arrival time minus its
///   duration). Frames that silence already covered are trimmed from the start of the batch (Trimmed).
///   Gaps are not filled.
/// Seq numbers come from one packetizer, so they never jump.
/// </summary>
public sealed class StreamCore
{
    public const double SilenceThresholdUs = 100_000;
    public const int MaxDiscontFillFrames = Protocol.SampleRate / 5; // jumps beyond 200 ms are not filled

    private readonly AudioPacketizer _pk;
    private readonly double _periodUs;
    private readonly Action<string>? _log;

    private bool _silence = true;
    private double _silenceAnchorUs;
    private long _silenceCount;
    private double _lastArrivalUs;

    private bool _havePrevPos;
    private ulong _expectedPos;

    private byte[] _batch = new byte[16384];
    private int _batchBytes;

    // Counters (read from other threads; written only by the owning thread).
    public long CapturedFrames;
    public long FillFrames;        // silence fill, including the zero padding of the last partial packet
    public long DiscontFill;       // zeros inserted for device-position jumps
    public long DiscontEvents;     // chunks flagged DATA_DISCONTINUITY or with a position jump
    public long Trimmed;           // captured frames dropped because silence already covered that time
    public long Resumes;

    public bool InSilence => _silence;
    public AudioPacketizer Packetizer => _pk;

    public StreamCore(AudioPacketizer packetizer, double startUs, Action<string>? log = null)
    {
        _pk = packetizer;
        _periodUs = packetizer.Frames * 1_000_000.0 / Protocol.SampleRate;
        _log = log;
        _silenceAnchorUs = startUs;
    }

    private static double FramesToUs(long frames) => frames * 1_000_000.0 / Protocol.SampleRate;

    /// <summary>Forget the device position (call after (re)opening the capture client).</summary>
    public void ResetCapturePosition() => _havePrevPos = false;

    /// <summary>Adds one WASAPI chunk (s16le stereo) to the current batch.</summary>
    public void OnChunk(ReadOnlySpan<byte> s16le, int frames, ulong devicePosition, bool discontinuityFlag)
    {
        if (_havePrevPos)
        {
            long jump = (long)(devicePosition - _expectedPos);
            if (jump > 0 && jump <= MaxDiscontFillFrames)
            {
                AppendZeros((int)jump);
                DiscontFill += jump;
                DiscontEvents++;
                _log?.Invoke($"device discontinuity: position jumped {jump} frames, inserted zeros");
            }
            else if (jump != 0 || discontinuityFlag)
            {
                DiscontEvents++;
                _log?.Invoke($"device discontinuity: flag={discontinuityFlag} jump={jump} frames (not filled)");
            }
        }
        _expectedPos = devicePosition + (ulong)frames;
        _havePrevPos = true;
        CapturedFrames += frames;
        Append(s16le.Slice(0, frames * Protocol.BytesPerFrame));
    }

    /// <summary>Sends the batch collected by <see cref="OnChunk"/>; <paramref name="nowUs"/> is its arrival time.</summary>
    public void EndBatch(double nowUs)
    {
        if (_batchBytes == 0) return;
        int batchFrames = _batchBytes / Protocol.BytesPerFrame;
        int skipFrames = 0;
        if (_silence)
        {
            double silenceEnd = _silenceAnchorUs + _silenceCount * _periodUs;
            double batchStart = nowUs - FramesToUs(batchFrames);
            double overlapUs = silenceEnd - batchStart;
            skipFrames = (int)Math.Clamp(Math.Round(overlapUs * Protocol.SampleRate / 1_000_000.0), 0, batchFrames);
            Trimmed += skipFrames;
            Resumes++;
            _silence = false;
            _log?.Invoke($"capture resumed: trimmed={skipFrames} frames (batch {batchFrames} frames, overlap {overlapUs / 1000.0:F2} ms)");
        }
        _pk.AddBytes(_batch.AsSpan(skipFrames * Protocol.BytesPerFrame, _batchBytes - skipFrames * Protocol.BytesPerFrame));
        _batchBytes = 0;
        _lastArrivalUs = nowUs;
    }

    /// <summary>When the owner should call <see cref="OnTick"/> next if no capture data arrives.</summary>
    public double NextDeadlineUs() => _silence
        ? _silenceAnchorUs + (_silenceCount + 1) * _periodUs
        : _lastArrivalUs + SilenceThresholdUs;

    /// <summary>Enters silence fill after the idle threshold and emits every silence packet that is due.</summary>
    public void OnTick(double nowUs)
    {
        if (!_silence && nowUs >= _lastArrivalUs + SilenceThresholdUs) // same expression as NextDeadlineUs
        {
            int pad = _pk.FlushPartial();
            FillFrames += pad;
            _silenceAnchorUs = _lastArrivalUs + FramesToUs(pad);
            _silenceCount = 0;
            _silence = true;
            _havePrevPos = false;
            _log?.Invoke($"capture idle {(nowUs - _lastArrivalUs) / 1000.0:F0} ms: silence fill on (pad {pad} frames)");
        }
        if (_silence)
        {
            while (_silenceAnchorUs + (_silenceCount + 1) * _periodUs <= nowUs)
            {
                _pk.EmitSilence();
                _silenceCount++;
                FillFrames += _pk.Frames;
            }
        }
    }

    private void AppendZeros(int frames)
    {
        int bytes = frames * Protocol.BytesPerFrame;
        Ensure(bytes);
        _batch.AsSpan(_batchBytes, bytes).Clear();
        _batchBytes += bytes;
    }

    private void Append(ReadOnlySpan<byte> data)
    {
        Ensure(data.Length);
        data.CopyTo(_batch.AsSpan(_batchBytes));
        _batchBytes += data.Length;
    }

    private void Ensure(int extra)
    {
        if (_batchBytes + extra <= _batch.Length) return;
        Array.Resize(ref _batch, Math.Max(_batch.Length * 2, _batchBytes + extra));
    }
}
