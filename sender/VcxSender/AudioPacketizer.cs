namespace Vcx.Sender;

/// <summary>
/// Accumulates s16le stereo frames into fixed-size AUDIO packets and hands each finished packet to
/// <see cref="Send"/> immediately. Keeps the last K payloads for redundancy. Not thread-safe: it is
/// owned by the capture thread.
/// </summary>
public sealed class AudioPacketizer
{
    public delegate void SendHandler(ReadOnlySpan<byte> packet, bool silenceFill);

    private readonly int _frames;
    private readonly int _payloadBytes;
    private readonly int _k;
    private readonly byte[] _acc;
    private int _accBytes;
    private readonly byte[][] _history;   // ring of previous payloads
    private int _historyHead;             // index of the most recent entry
    private int _historyCount;
    private readonly List<byte[]> _prevView = new();
    private readonly byte[] _packet;
    private readonly byte[] _zeros;
    private readonly Func<ulong> _clockUs;
    private readonly SendHandler _send;

    public uint NextSeq { get; private set; }
    public int Frames => _frames;
    public int Redundancy => _k;
    public int PendingFrames => _accBytes / Protocol.BytesPerFrame;

    public AudioPacketizer(int frames, int redundancy, Func<ulong> clockUs, SendHandler send, uint firstSeq = 0)
    {
        if (frames <= 0 || frames > ushort.MaxValue) throw new ArgumentOutOfRangeException(nameof(frames));
        if (redundancy < 0 || redundancy > 255) throw new ArgumentOutOfRangeException(nameof(redundancy));
        _frames = frames;
        _payloadBytes = frames * Protocol.BytesPerFrame;
        _k = redundancy;
        _acc = new byte[_payloadBytes];
        _history = new byte[Math.Max(redundancy, 1)][];
        for (int i = 0; i < _history.Length; i++) _history[i] = new byte[_payloadBytes];
        _packet = new byte[Protocol.AudioPacketSize(frames, redundancy)];
        _zeros = new byte[_payloadBytes];
        _clockUs = clockUs;
        _send = send;
        NextSeq = firstSeq;
    }

    /// <summary>Adds interleaved s16le stereo bytes (length must be a multiple of 4).</summary>
    public void AddBytes(ReadOnlySpan<byte> s16le)
    {
        while (!s16le.IsEmpty)
        {
            int n = Math.Min(s16le.Length, _payloadBytes - _accBytes);
            s16le.Slice(0, n).CopyTo(_acc.AsSpan(_accBytes));
            _accBytes += n;
            s16le = s16le.Slice(n);
            if (_accBytes == _payloadBytes)
            {
                Emit(_acc, silenceFill: false);
                _accBytes = 0;
            }
        }
    }

    /// <summary>Adds <paramref name="frames"/> frames of zeros into the real-audio stream (not flagged).</summary>
    public void AddZeroFrames(int frames)
    {
        long bytes = (long)frames * Protocol.BytesPerFrame;
        while (bytes > 0)
        {
            int n = (int)Math.Min(bytes, _zeros.Length);
            AddBytes(_zeros.AsSpan(0, n));
            bytes -= n;
        }
    }

    /// <summary>Pads a partially filled packet with zeros and sends it. Returns the number of padding frames.</summary>
    public int FlushPartial()
    {
        if (_accBytes == 0) return 0;
        int pad = _payloadBytes - _accBytes;
        _acc.AsSpan(_accBytes).Clear();
        Emit(_acc, silenceFill: false);
        _accBytes = 0;
        return pad / Protocol.BytesPerFrame;
    }

    /// <summary>Sends one whole packet of sender-generated silence (flag bit 0). Requires an empty accumulator.</summary>
    public void EmitSilence()
    {
        if (_accBytes != 0) throw new InvalidOperationException("flush the partial packet first");
        Emit(_zeros, silenceFill: true);
    }

    private void Emit(byte[] payload, bool silenceFill)
    {
        // Previous payloads, most recent first. Before K packets exist, send only what we have.
        _prevView.Clear();
        int kEff = Math.Min(_k, _historyCount);
        for (int i = 0; i < kEff; i++)
            _prevView.Add(_history[(_historyHead - i + _history.Length) % _history.Length]);

        int len = Protocol.WriteAudioPacket(_packet, NextSeq, _clockUs(), _frames, silenceFill, payload, _prevView);
        _send(_packet.AsSpan(0, len), silenceFill);
        NextSeq = unchecked(NextSeq + 1);

        if (_k > 0)
        {
            _historyHead = (_historyHead + 1) % _history.Length;
            payload.AsSpan().CopyTo(_history[_historyHead]);
            if (_historyCount < _k) _historyCount++;
        }
    }
}
