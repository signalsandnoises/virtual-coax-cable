using System.Buffers.Binary;
using Vcx.Sender;
using Xunit;

namespace Vcx.Sender.Tests;

/// <summary>Drives <see cref="StreamCore"/> with a fake WASAPI loopback source on a virtual clock.</summary>
public class StreamCoreTests
{
    private const int Frames = 120;
    private const double PeriodUs = 10_000;   // WASAPI engine period
    private const int ChunkFrames = 480;

    private sealed record Chunk(ulong DevPos, int Frames, bool Flag);
    private sealed record Delivery(double TimeUs, List<Chunk> Chunks);
    private sealed record Packet(uint Seq, bool Silence, short[] Left);

    /// <summary>Sample value for device frame k: never zero, so inserted zeros are visible.</summary>
    private static short Val(ulong k) => (short)(k % 30000 + 1);

    private sealed class Sim
    {
        public readonly List<Packet> Packets = new();
        public readonly StreamCore Core;
        public readonly AudioPacketizer Pk;
        public double Now;

        public Sim()
        {
            Pk = new AudioPacketizer(Frames, 0, () => 0, (p, sil) =>
            {
                var left = new short[Frames];
                for (int i = 0; i < Frames; i++) left[i] = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(24 + i * 4));
                Packets.Add(new Packet(BinaryPrimitives.ReadUInt32LittleEndian(p.Slice(8)), sil, left));
            });
            Core = new StreamCore(Pk, 0);
        }

        /// <summary>Runs the engine loop (2 ms poll + core deadline + deliveries) until <paramref name="endUs"/>.</summary>
        public void Run(IEnumerable<Delivery> deliveries, double endUs)
        {
            var queue = new Queue<Delivery>(deliveries.OrderBy(d => d.TimeUs));
            var buf = new byte[ChunkFrames * 4 * 4];
            long iterations = 0;
            while (Now < endUs)
            {
                if (++iterations > 50_000_000)
                    throw new InvalidOperationException($"sim stuck at {Now} us, silence={Core.InSilence}, deadline={Core.NextDeadlineUs()}, queue={queue.Count}");
                double next = Math.Min(Now + 2_000, Core.NextDeadlineUs());
                if (queue.Count > 0) next = Math.Min(next, queue.Peek().TimeUs);
                Now = Math.Max(Now, Math.Min(next, endUs));
                if (queue.Count > 0 && queue.Peek().TimeUs <= Now)
                {
                    var d = queue.Dequeue();
                    foreach (var c in d.Chunks)
                    {
                        for (int i = 0; i < c.Frames; i++)
                        {
                            short v = Val(c.DevPos + (ulong)i);
                            BinaryPrimitives.WriteInt16LittleEndian(buf.AsSpan(i * 4), v);
                            BinaryPrimitives.WriteInt16LittleEndian(buf.AsSpan(i * 4 + 2), v);
                        }
                        Core.OnChunk(buf.AsSpan(0, c.Frames * 4), c.Frames, c.DevPos, c.Flag);
                    }
                    Core.EndBatch(Now);
                }
                Core.OnTick(Now);
            }
        }

        public long StreamFrames => Packets.Count * (long)Frames + Pk.PendingFrames;

        public void AssertSeqContinuous()
        {
            for (int i = 0; i < Packets.Count; i++) Assert.Equal((uint)i, Packets[i].Seq);
        }
    }

    /// <summary>
    /// Chunks of 480 frames rendered every 10 ms from <paramref name="startUs"/> to <paramref name="endUs"/>,
    /// delivered at render-end time + jitter in [0, jitterUs], plus an extra 10-15 ms on ~5 % of periods
    /// (giving 20-25 ms delivery gaps). Late chunks are delivered together with the next ones, like WASAPI.
    /// </summary>
    private static List<Delivery> Capture(double startUs, double endUs, ulong firstPos, double jitterUs, int seed,
        Func<int, (ulong skip, bool flag)>? discont = null)
    {
        var rnd = new Random(seed);
        var result = new List<Delivery>();
        ulong pos = firstPos;
        double lastTime = 0;
        int i = 0;
        for (double t = startUs + PeriodUs; t <= endUs; t += PeriodUs, i++)
        {
            double j = rnd.NextDouble() * jitterUs;
            if (jitterUs > 0 && rnd.NextDouble() < 0.05) j += 10_000 + rnd.NextDouble() * 5_000;
            double at = Math.Max(t + j, lastTime);
            var (skip, flag) = discont?.Invoke(i) ?? (0, false);
            pos += skip;
            var chunk = new Chunk(pos, ChunkFrames, flag);
            pos += ChunkFrames;
            if (result.Count > 0 && result[^1].TimeUs >= at) result[^1].Chunks.Add(chunk);
            else result.Add(new Delivery(at, new List<Chunk> { chunk }));
            lastTime = at;
        }
        return result;
    }

    [Fact]
    public void JitteryCapture_SendsExactlyTheCapturedFrames()
    {
        var sim = new Sim();
        var deliveries = Capture(0, 60_000_000, 1000, 10_000, seed: 1);
        double maxGap = deliveries.Zip(deliveries.Skip(1), (a, b) => b.TimeUs - a.TimeUs).Max();
        Assert.InRange(maxGap, 20_000, 36_000); // the fake source really has 20-25 ms gaps
        sim.Run(deliveries, 60_050_000); // all deliveries (some are late), but not long enough to go idle

        var c = sim.Core;
        Assert.Equal(1, c.Resumes);           // only the initial resume, never back into silence
        Assert.Equal(0, c.DiscontFill);
        Assert.Equal(0, c.DiscontEvents);
        long captured = c.CapturedFrames;
        Assert.Equal(6000L * ChunkFrames, captured);

        // After the initial silence, every packet is real and holds exactly the captured frames, in order.
        var real = sim.Packets.SkipWhile(p => p.Silence).ToList();
        Assert.All(real, p => Assert.False(p.Silence));
        Assert.Equal((captured - c.Trimmed) / Frames, real.Count);
        Assert.Equal((captured - c.Trimmed) % Frames, sim.Pk.PendingFrames);
        ulong k = 1000 + (ulong)c.Trimmed;
        foreach (var p in real)
            foreach (var v in p.Left)
                Assert.Equal(Val(k++), v);
        sim.AssertSeqContinuous();
        Assert.Equal(c.FillFrames, sim.Packets.Count(p => p.Silence) * (long)Frames); // only the start-up silence
    }

    [Fact]
    public void IdleSilenceResume_NoOverlap_SeqContinuous_TracksWallClock()
    {
        var sim = new Sim();
        // capture 0-2 s, render idle 2-3 s, capture again 3-5 s (device position jumps across the idle gap)
        var d = Capture(0, 2_000_000, 0, 0, seed: 2);
        d.AddRange(Capture(3_000_000, 5_000_000, 500_000, 0, seed: 3));
        sim.Run(d, 5_000_000);

        var c = sim.Core;
        Assert.Equal(2, c.Resumes);
        Assert.Equal(0, c.DiscontFill);
        sim.AssertSeqContinuous();
        // stream position == wall clock (last delivery at 5.0 s) within one packet
        Assert.InRange(sim.StreamFrames, 5 * 48_000 - Frames, 5 * 48_000 + Frames);

        // Silence only after >= 100 ms idle: no silence packets between the first resume and 2.1 s worth of stream.
        int firstReal = sim.Packets.FindIndex(p => !p.Silence);
        int firstIdleSilence = sim.Packets.FindIndex(firstReal, p => p.Silence);
        long framesBeforeIdle = firstIdleSilence * (long)Frames;
        Assert.InRange(framesBeforeIdle, 2_000_000 * 48 / 1000 - Frames, 2_000_000 * 48 / 1000 + Frames);

        // Resumed audio: the first real packet after the idle silence starts exactly at frame (500000 + trimmed
        // of that resume), i.e. nothing silence covered is sent again, and it continues without gaps.
        int resumeIdx = sim.Packets.FindIndex(firstIdleSilence, p => !p.Silence);
        var resumed = sim.Packets.Skip(resumeIdx).ToList();
        Assert.All(resumed, p => Assert.False(p.Silence));
        short first = resumed[0].Left[0];
        ulong startPos = (ulong)(first - 1); // Val(k) = k % 30000 + 1 and 500000 % 30000 = 20000
        Assert.InRange((long)startPos, 20_000, 20_000 + 480);
        ulong k = 500_000 + (startPos - 20_000);
        foreach (var p in resumed)
            foreach (var v in p.Left)
                Assert.Equal(Val(k++), v);
        // silence covered (3.0 s - 2.0 s) of time; the resumed batch overlapped it by < one chunk
        Assert.InRange(c.Trimmed, 0, 2 * ChunkFrames);
    }

    [Fact]
    public void IdleSilenceResume_WithJitter_StaysNearWallClock()
    {
        var sim = new Sim();
        var d = Capture(0, 2_000_000, 0, 10_000, seed: 4);
        d.AddRange(Capture(3_000_000, 5_000_000, 500_000, 10_000, seed: 5));
        sim.Run(d, 5_100_000);
        sim.AssertSeqContinuous();
        // delivery jitter (<= 25 ms) shifts where the resumed audio lands, but never accumulates
        Assert.InRange(sim.StreamFrames, 5 * 48_000 - 30 * 48, 5 * 48_000 + 30 * 48);
    }

    [Fact]
    public void DeviceDiscontinuity_InsertsAndCountsZeros()
    {
        var sim = new Sim();
        // chunk 100 arrives with DATA_DISCONTINUITY and the device position skipped one chunk (480 frames)
        var d = Capture(0, 2_000_000, 0, 5_000, seed: 6, i => i == 100 ? (480UL, true) : (0UL, false));
        sim.Run(d, 2_000_000);

        var c = sim.Core;
        Assert.Equal(480, c.DiscontFill);
        Assert.Equal(1, c.DiscontEvents);
        var real = sim.Packets.SkipWhile(p => p.Silence).ToList();
        Assert.All(real, p => Assert.False(p.Silence));
        Assert.Equal((c.CapturedFrames + 480 - c.Trimmed) / Frames, real.Count);
        // the 480 zeros sit exactly where device frames 48000..48479 would have been
        var samples = real.SelectMany(p => p.Left).ToArray();
        long zeroStart = 100 * 480 - c.Trimmed;
        Assert.All(samples.Skip((int)zeroStart).Take(480), v => Assert.Equal(0, v));
        Assert.Equal(Val(100 * 480 + 480), samples[zeroStart + 480]);
        Assert.Equal(Val(100 * 480 - 1), samples[zeroStart - 1]);
        sim.AssertSeqContinuous();
    }

    [Fact]
    public void DiscontinuityFlagWithoutPositionJump_InsertsNothing()
    {
        var sim = new Sim();
        var d = Capture(0, 1_000_000, 0, 0, seed: 7, i => i == 50 ? (0UL, true) : (0UL, false));
        sim.Run(d, 1_000_000);
        Assert.Equal(0, sim.Core.DiscontFill);
        Assert.Equal(1, sim.Core.DiscontEvents);
        Assert.Equal(sim.Core.CapturedFrames - sim.Core.Trimmed,
            sim.Packets.Count(p => !p.Silence) * (long)Frames + sim.Pk.PendingFrames);
    }
}
