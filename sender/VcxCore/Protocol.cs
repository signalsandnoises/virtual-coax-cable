using System.Buffers.Binary;
using System.Text;

namespace Vcx.Sender;

/// <summary>VCX protocol v1 constants and (de)serialization. See PROTOCOL.md (authoritative).</summary>
public static class Protocol
{
    public const int HeaderSize = 24;
    public const byte Version = 1;
    public const byte TypeAudio = 1;
    public const byte TypeDiscover = 2;
    public const byte TypeDiscoverReply = 3;
    public const byte TypeStats = 4;
    public const ushort FlagSilenceFill = 0x0001;
    public const byte Format48kStereoS16 = 1;
    public const int SampleRate = 48000;
    public const int Channels = 2;
    public const int BytesPerFrame = 4; // stereo s16
    public const int AudioPort = 47800;
    public const int DiscoverPort = 47801;
    public const int StatsBodySize = 40;
    public const int StatsExtBodySize = 56; // milestone 2: + delay_p99, delay_max, gap_max, depth_min (f32 ms)

    public static ReadOnlySpan<byte> Magic => "VCX1"u8;

    public static void WriteHeader(Span<byte> dst, byte type, ushort flags, uint seq, ulong timestampUs,
        ushort frames, byte formatId, byte k)
    {
        if (dst.Length < HeaderSize) throw new ArgumentException("buffer too small", nameof(dst));
        Magic.CopyTo(dst);
        dst[4] = Version;
        dst[5] = type;
        BinaryPrimitives.WriteUInt16LittleEndian(dst.Slice(6), flags);
        BinaryPrimitives.WriteUInt32LittleEndian(dst.Slice(8), seq);
        BinaryPrimitives.WriteUInt64LittleEndian(dst.Slice(12), timestampUs);
        BinaryPrimitives.WriteUInt16LittleEndian(dst.Slice(20), frames);
        dst[22] = formatId;
        dst[23] = k;
    }

    /// <summary>Size of an AUDIO packet with <paramref name="frames"/> frames and K redundant payloads.</summary>
    public static int AudioPacketSize(int frames, int k) => HeaderSize + (1 + k) * frames * BytesPerFrame;

    /// <summary>
    /// Writes an AUDIO packet: header, current payload, then the previous payloads most recent first
    /// (previous[0] = payload of seq-1, previous[1] = seq-2, ...). Returns the packet length.
    /// </summary>
    public static int WriteAudioPacket(Span<byte> dst, uint seq, ulong timestampUs, int frames, bool silenceFill,
        ReadOnlySpan<byte> payload, IReadOnlyList<byte[]> previous)
    {
        int payloadBytes = frames * BytesPerFrame;
        if (payload.Length != payloadBytes) throw new ArgumentException("payload length != frames*4", nameof(payload));
        if (previous.Count > 255) throw new ArgumentException("too many redundant payloads", nameof(previous));
        int len = AudioPacketSize(frames, previous.Count);
        if (dst.Length < len) throw new ArgumentException("buffer too small", nameof(dst));
        WriteHeader(dst, TypeAudio, silenceFill ? FlagSilenceFill : (ushort)0, seq, timestampUs,
            checked((ushort)frames), Format48kStereoS16, (byte)previous.Count);
        payload.CopyTo(dst.Slice(HeaderSize));
        int off = HeaderSize + payloadBytes;
        for (int i = 0; i < previous.Count; i++)
        {
            var p = previous[i];
            if (p.Length != payloadBytes) throw new ArgumentException("redundant payload length mismatch", nameof(previous));
            p.AsSpan().CopyTo(dst.Slice(off));
            off += payloadBytes;
        }
        return len;
    }

    public static byte[] BuildDiscover(string pcName, uint counter, ulong timestampUs)
    {
        var name = Encoding.UTF8.GetBytes(pcName ?? "");
        var buf = new byte[HeaderSize + name.Length];
        WriteHeader(buf, TypeDiscover, 0, counter, timestampUs, 0, 0, 0);
        name.CopyTo(buf, HeaderSize);
        return buf;
    }

    /// <summary>True if the datagram starts with a valid v1 header; returns its type.</summary>
    public static bool TryParseHeader(ReadOnlySpan<byte> pkt, out byte type)
    {
        type = 0;
        if (pkt.Length < HeaderSize || !pkt.Slice(0, 4).SequenceEqual(Magic) || pkt[4] != Version) return false;
        type = pkt[5];
        return true;
    }

    public static bool TryParseDiscoverReply(ReadOnlySpan<byte> pkt, out ushort audioPort, out string deviceName)
    {
        audioPort = 0;
        deviceName = "";
        if (!TryParseHeader(pkt, out var type) || type != TypeDiscoverReply || pkt.Length < HeaderSize + 2) return false;
        audioPort = BinaryPrimitives.ReadUInt16LittleEndian(pkt.Slice(HeaderSize));
        deviceName = Encoding.UTF8.GetString(pkt.Slice(HeaderSize + 2));
        return true;
    }

    public static bool TryParseStats(ReadOnlySpan<byte> pkt, out PhoneStats stats)
    {
        stats = default;
        if (!TryParseHeader(pkt, out var type) || type != TypeStats || pkt.Length < HeaderSize + StatsBodySize) return false;
        var b = pkt.Slice(HeaderSize);
        stats = new PhoneStats(
            BinaryPrimitives.ReadInt32LittleEndian(b),
            BinaryPrimitives.ReadInt32LittleEndian(b.Slice(4)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(8)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(12)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(16)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(20)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(24)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(28)),
            BinaryPrimitives.ReadSingleLittleEndian(b.Slice(32)),
            BinaryPrimitives.ReadUInt32LittleEndian(b.Slice(36)));
        if (b.Length >= StatsExtBodySize)
            stats = stats with
            {
                Ext = new PhoneStatsExt(
                    BinaryPrimitives.ReadSingleLittleEndian(b.Slice(40)),
                    BinaryPrimitives.ReadSingleLittleEndian(b.Slice(44)),
                    BinaryPrimitives.ReadSingleLittleEndian(b.Slice(48)),
                    BinaryPrimitives.ReadSingleLittleEndian(b.Slice(52)))
            };
        return true;
    }
}

public readonly record struct PhoneStats(
    int BufferUs, int TargetUs, uint Received, uint Lost, uint Recovered, uint Concealed,
    uint Late, uint Underruns, float RatePpm, uint TrackFrames)
{
    /// <summary>Extended fields (STATS body >= 56 bytes), null for the 40-byte form.</summary>
    public PhoneStatsExt? Ext { get; init; }

    public string Format()
    {
        var inv = System.Globalization.CultureInfo.InvariantCulture;
        string s = string.Create(inv,
            $"phone: buf={BufferUs / 1000.0:F1}ms tgt={TargetUs / 1000.0:0.#} rx={Received} lost={Lost} rec={Recovered} " +
            $"conc={Concealed} late={Late} und={Underruns} ppm={RatePpm:F1} track={TrackFrames}");
        if (Ext is { } e)
            s += string.Create(inv, $" dly99={e.DelayP99Ms:F1} dlymax={e.DelayMaxMs:F1} gap={e.GapMaxMs:F1} dmin={e.DepthMinMs:F1}");
        return s;
    }
}

public readonly record struct PhoneStatsExt(float DelayP99Ms, float DelayMaxMs, float GapMaxMs, float DepthMinMs);

public static class Pcm
{
    /// <summary>float32 [-1,1] to s16 with clipping (scale 32767, round to nearest, NaN to 0).</summary>
    public static short FloatToS16(float x)
    {
        if (float.IsNaN(x)) return 0;
        float s = x * 32767f;
        if (s >= 32767f) return 32767;
        if (s <= -32768f) return -32768;
        return (short)MathF.Round(s, MidpointRounding.AwayFromZero);
    }

    /// <summary>Converts interleaved float32 samples to interleaved s16le bytes.</summary>
    public static void FloatToS16Le(ReadOnlySpan<float> src, Span<byte> dst)
    {
        if (dst.Length < src.Length * 2) throw new ArgumentException("dst too small", nameof(dst));
        for (int i = 0; i < src.Length; i++)
            BinaryPrimitives.WriteInt16LittleEndian(dst.Slice(i * 2), FloatToS16(src[i]));
    }
}
