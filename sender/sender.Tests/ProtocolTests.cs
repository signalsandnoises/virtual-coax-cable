using System.Buffers.Binary;
using System.Text;
using Vcx.Sender;
using Xunit;

namespace Vcx.Sender.Tests;

public class ProtocolTests
{
    private static byte[] Payload(int frames, byte fill)
    {
        var p = new byte[frames * 4];
        Array.Fill(p, fill);
        return p;
    }

    [Fact]
    public void AudioHeader_LayoutByteByByte()
    {
        var buf = new byte[2000];
        int len = Protocol.WriteAudioPacket(buf, 0x11223344u, 0x0102030405060708UL, 120, silenceFill: true,
            Payload(120, 0xAB), Array.Empty<byte[]>());

        Assert.Equal(24 + 480, len);
        byte[] expected =
        {
            (byte)'V', (byte)'C', (byte)'X', (byte)'1', // magic
            1,                                          // version
            1,                                          // type AUDIO
            0x01, 0x00,                                 // flags: bit 0 silence fill
            0x44, 0x33, 0x22, 0x11,                     // seq LE
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, // timestamp LE
            120, 0,                                     // frames LE
            1,                                          // format id
            0,                                          // K
        };
        Assert.Equal(expected, buf.AsSpan(0, 24).ToArray());
        Assert.All(buf.AsSpan(24, 480).ToArray(), b => Assert.Equal(0xAB, b));
    }

    [Fact]
    public void AudioHeader_RealAudioHasNoFlags()
    {
        var buf = new byte[2000];
        Protocol.WriteAudioPacket(buf, 7, 0, 120, silenceFill: false, Payload(120, 0), Array.Empty<byte[]>());
        Assert.Equal(0, BinaryPrimitives.ReadUInt16LittleEndian(buf.AsSpan(6)));
    }

    [Theory]
    [InlineData(120, 0, 504)]
    [InlineData(120, 1, 984)]
    [InlineData(120, 2, 1464)]
    [InlineData(60, 1, 504)]
    [InlineData(240, 0, 984)]
    public void AudioPacketSize_MatchesSpec(int frames, int k, int expected)
    {
        Assert.Equal(expected, Protocol.AudioPacketSize(frames, k));
        var prev = Enumerable.Range(0, k).Select(_ => Payload(frames, 0)).ToArray();
        var buf = new byte[4000];
        Assert.Equal(expected, Protocol.WriteAudioPacket(buf, 0, 0, frames, false, Payload(frames, 0), prev));
        Assert.Equal(frames, BinaryPrimitives.ReadUInt16LittleEndian(buf.AsSpan(20)));
        Assert.Equal(k, buf[23]);
    }

    [Fact]
    public void Packetizer_RedundancyOrder_MostRecentFirst()
    {
        var packets = new List<byte[]>();
        var pk = new AudioPacketizer(120, 2, () => 0, (p, _) => packets.Add(p.ToArray()));
        for (byte i = 1; i <= 4; i++) pk.AddBytes(Payload(120, i)); // payload of seq n is filled with n+1

        Assert.Equal(4, packets.Count);
        // seq 0: no history yet -> K=0
        Assert.Equal(0, packets[0][23]);
        Assert.Equal(504, packets[0].Length);
        // seq 1: K=1 (only one previous exists)
        Assert.Equal(1, packets[1][23]);
        Assert.Equal(0x01, packets[1][24 + 480]);
        // seq 3: current = 4, then seq-1 (=3), then seq-2 (=2)
        var p3 = packets[3];
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32LittleEndian(p3.AsSpan(8)));
        Assert.Equal(2, p3[23]);
        Assert.Equal(24 + 3 * 480, p3.Length);
        Assert.All(p3.AsSpan(24, 480).ToArray(), b => Assert.Equal(4, b));
        Assert.All(p3.AsSpan(24 + 480, 480).ToArray(), b => Assert.Equal(3, b));
        Assert.All(p3.AsSpan(24 + 960, 480).ToArray(), b => Assert.Equal(2, b));
    }

    [Fact]
    public void Packetizer_SeqWrapsAround()
    {
        var seqs = new List<uint>();
        var pk = new AudioPacketizer(120, 1, () => 0, (p, _) => seqs.Add(BinaryPrimitives.ReadUInt32LittleEndian(p.Slice(8))),
            firstSeq: uint.MaxValue - 1);
        for (int i = 0; i < 4; i++) pk.EmitSilence();
        Assert.Equal(new uint[] { uint.MaxValue - 1, uint.MaxValue, 0, 1 }, seqs);
        Assert.Equal(2u, pk.NextSeq);
    }

    [Fact]
    public void Packetizer_SplitsChunksAndFlushPads_WithContinuousSeq()
    {
        var packets = new List<(byte[] p, bool sil)>();
        var pk = new AudioPacketizer(120, 0, () => 42, (p, s) => packets.Add((p.ToArray(), s)));
        pk.AddBytes(Payload(100, 9));          // partial
        Assert.Empty(packets);
        pk.AddBytes(Payload(50, 9));           // completes one packet, 30 frames left
        Assert.Single(packets);
        Assert.Equal(30, pk.PendingFrames);
        Assert.Equal(90, pk.FlushPartial());   // padded with 90 frames of zeros
        pk.EmitSilence();
        pk.AddZeroFrames(240);

        Assert.Equal(5, packets.Count);
        for (int i = 0; i < packets.Count; i++)
            Assert.Equal((uint)i, BinaryPrimitives.ReadUInt32LittleEndian(packets[i].p.AsSpan(8)));
        var padded = packets[1].p;
        Assert.All(padded.AsSpan(24, 30 * 4).ToArray(), b => Assert.Equal(9, b));
        Assert.All(padded.AsSpan(24 + 120, 90 * 4).ToArray(), b => Assert.Equal(0, b));
        Assert.False(packets[1].sil);
        Assert.True(packets[2].sil);
        Assert.Equal(1, packets[2].p[6]);
        Assert.False(packets[3].sil);
        Assert.Equal(42ul, BinaryPrimitives.ReadUInt64LittleEndian(packets[0].p.AsSpan(12)));
    }

    [Theory]
    [InlineData(0f, 0)]
    [InlineData(1f, 32767)]
    [InlineData(-1f, -32767)]
    [InlineData(0.5f, 16384)]   // 16383.5 rounds away from zero
    [InlineData(-0.5f, -16384)]
    [InlineData(1.5f, 32767)]   // clip
    [InlineData(-1.5f, -32768)] // clip
    [InlineData(1e9f, 32767)]
    [InlineData(float.PositiveInfinity, 32767)]
    [InlineData(float.NegativeInfinity, -32768)]
    [InlineData(float.NaN, 0)]
    [InlineData(1f / 32767f, 1)]
    public void FloatToS16_ConvertsAndClips(float x, short expected) => Assert.Equal(expected, Pcm.FloatToS16(x));

    [Fact]
    public void FloatToS16Le_IsLittleEndianInterleaved()
    {
        var dst = new byte[8];
        Pcm.FloatToS16Le(new[] { 1f, -1f, 0f, 2f }, dst);
        Assert.Equal(new byte[] { 0xFF, 0x7F, 0x01, 0x80, 0x00, 0x00, 0xFF, 0x7F }, dst);
    }

    [Fact]
    public void Discover_Build()
    {
        var p = Protocol.BuildDiscover("PC-1", 5, 99);
        Assert.Equal(24 + 4, p.Length);
        Assert.True(Protocol.TryParseHeader(p, out var type));
        Assert.Equal(Protocol.TypeDiscover, type);
        Assert.Equal(5u, BinaryPrimitives.ReadUInt32LittleEndian(p.AsSpan(8)));
        Assert.Equal("PC-1", Encoding.UTF8.GetString(p, 24, 4));
    }

    private static byte[] Header(byte type, int bodyLen)
    {
        var b = new byte[24 + bodyLen];
        Protocol.WriteHeader(b, type, 0, 0, 0, 0, 0, 0);
        return b;
    }

    [Fact]
    public void DiscoverReply_Parse()
    {
        var name = Encoding.UTF8.GetBytes("SM-S911U1");
        var p = Header(Protocol.TypeDiscoverReply, 2 + name.Length);
        BinaryPrimitives.WriteUInt16LittleEndian(p.AsSpan(24), 47800);
        name.CopyTo(p, 26);
        Assert.True(Protocol.TryParseDiscoverReply(p, out var port, out var dev));
        Assert.Equal(47800, port);
        Assert.Equal("SM-S911U1", dev);

        // empty name is allowed
        var q = Header(Protocol.TypeDiscoverReply, 2);
        BinaryPrimitives.WriteUInt16LittleEndian(q.AsSpan(24), 1234);
        Assert.True(Protocol.TryParseDiscoverReply(q, out port, out dev));
        Assert.Equal(1234, port);
        Assert.Equal("", dev);
    }

    [Fact]
    public void Parse_RejectsBadMagicVersionTypeAndShortPackets()
    {
        var p = Header(Protocol.TypeDiscoverReply, 2);
        var bad = (byte[])p.Clone(); bad[0] = (byte)'X';
        Assert.False(Protocol.TryParseDiscoverReply(bad, out _, out _));
        bad = (byte[])p.Clone(); bad[4] = 2;
        Assert.False(Protocol.TryParseDiscoverReply(bad, out _, out _));
        Assert.False(Protocol.TryParseDiscoverReply(p.AsSpan(0, 25), out _, out _));
        Assert.False(Protocol.TryParseStats(p, out _));
        Assert.False(Protocol.TryParseStats(Header(Protocol.TypeStats, 39), out _));
    }

    [Fact]
    public void Stats_Parse()
    {
        var p = Header(Protocol.TypeStats, 40);
        var b = p.AsSpan(24);
        BinaryPrimitives.WriteInt32LittleEndian(b, 41234);
        BinaryPrimitives.WriteInt32LittleEndian(b.Slice(4), 40000);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(8), 400);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(12), 1);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(16), 2);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(20), 3);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(24), 4);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(28), 5);
        BinaryPrimitives.WriteSingleLittleEndian(b.Slice(32), -12.5f);
        BinaryPrimitives.WriteUInt32LittleEndian(b.Slice(36), 192);

        Assert.True(Protocol.TryParseStats(p, out var s));
        Assert.Equal(new PhoneStats(41234, 40000, 400, 1, 2, 3, 4, 5, -12.5f, 192), s);
        Assert.Equal("phone: buf=41.2ms tgt=40 rx=400 lost=1 rec=2 conc=3 late=4 und=5 ppm=-12.5 track=192", s.Format());
    }
}
