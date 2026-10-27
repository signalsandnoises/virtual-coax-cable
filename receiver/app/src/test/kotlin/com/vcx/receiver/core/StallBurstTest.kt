package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Network stall + burst simulations with the real JitterBuffer, resampler, controller and DelayStats.
 * Phone time advances 2.5 ms per playout write; packets are pushed when their arrival time has passed.
 */
class StallBurstTest {

    private class Result(
        val chain: PlayoutChain,
        val logs: List<String>,
        val t: DoubleArray,          // per update (every 40 writes = 100 ms): time, µs
        val depthMs: DoubleArray,    // smoothed depth
        val leadMs: DoubleArray,     // smoothed lead (steering variable)
        val ppm: DoubleArray,
    ) {
        val c get() = chain.jitter.counters()
    }

    /**
     * @param arrival arrival time (µs) of packet k
     * @param silent whether packet k is sender silence fill (flag bit 0, zero payload); else payload = 1000
     * @param useLead false = legacy depth steering (no DelayStats), i.e. the pre-fix behaviour
     */
    private fun simulate(
        seconds: Int, arrival: (Int) -> Double, useLead: Boolean = true, k: Int = 0,
        silent: (Int) -> Boolean = { false }, drop: (Int) -> Boolean = { false },
    ): Result {
        val logs = ArrayList<String>()
        var now = 0L
        val jb = JitterBuffer(40, log = { logs += it + " @" + now / 1000 })
        val delay = DelayStats()
        val chain = if (useLead) {
            PlayoutChain(jb, delay = delay, clockUs = { now }, log = { logs += it + " @" + now / 1000 })
        } else {
            PlayoutChain(jb, log = { logs += it })
        }
        val n = seconds * 400 + 800
        val arr = DoubleArray(n) { arrival(it) }
        val order = (0 until n).sortedBy { arr[it] }
        val loud = ByteArray(480) { if (it % 2 == 0) 0xE8.toByte() else 0x03 } // 1000
        val quiet = ByteArray(480)
        val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)
        var next = 0
        val ts = ArrayList<Double>(); val ds = ArrayList<Double>(); val ls = ArrayList<Double>(); val ps = ArrayList<Double>()
        for (w in 0 until seconds * 400) {
            now = w * 2500L
            while (next < n && arr[order[next]] <= now) {
                val s = order[next++]
                if (drop(s)) continue
                val payloads = (0..minOf(k, s)).map { j -> if (silent(s - j)) quiet else loud }
                val raw = PacketCodec.buildAudio(s, 0, 120, payloads, flags = if (silent(s)) Vcx.FLAG_SILENCE_FILL else 0)
                val p = PacketCodec.parseAudio(raw, 0, raw.size)!!
                delay.onArrival(p.seq, p.frames, arr[s].toLong())
                jb.push(p)
            }
            chain.read(out)
            if (w % 40 == 39) {
                ts += now.toDouble(); ds += chain.lastFilteredDepthFrames / 48.0
                ls += chain.lastFilteredLeadFrames / 48.0; ps += chain.ratePpm.toDouble()
            }
        }
        return Result(chain, logs, ts.toDoubleArray(), ds.toDoubleArray(), ls.toDoubleArray(), ps.toDoubleArray())
    }

    /** 150 ms stall every 2 s (packets queue, then arrive as a burst), 3-5 ms base delay, 4-packet micro-bursts. */
    private fun stallEvery2s(seed: Long): (Int) -> Double {
        val rnd = java.util.Random(seed)
        val jitter = DoubleArray(20_000) { rnd.nextDouble() * 2000 }
        return { k ->
            val send = 1000.0 + k * 2500.0
            var a = send + 3000 + jitter[k % jitter.size]
            a = maxOf(a, send + 3000 + (3 - k % 4) * 2500.0) // sender/AP micro-bursts of 4 packets
            val ph = (send + 1_000_000) % 2_000_000.0
            if (ph < 150_000) a = maxOf(a, send - ph + 150_000 + 3000 + jitter[(k * 7) % jitter.size] / 2)
            a
        }
    }

    /** Stall windows (send time 1 s mod 2 s, 150 ms) plus a 10 ms margin each side. */
    private fun inStall(tUs: Double): Boolean {
        val ph = (tUs + 1_000_000) % 2_000_000.0
        return ph < 160_000 || ph > 2_000_000 - 10_000
    }

    @Test
    fun stallEvery2sDepthStaysOnTargetAndSteeringDoesNotSaturate() {
        val r = simulate(30, stallEvery2s(1))
        var worst = 0.0
        for (i in r.t.indices) {
            if (r.t[i] < 1_500_000 || inStall(r.t[i])) continue
            worst = maxOf(worst, abs(r.depthMs[i] - 40.0))
        }
        val maxPpm = r.ppm.maxOf { abs(it) }
        println("stall/2s: worst |depth-40| outside stalls = %.2f ms, max |ppm| = %.0f, lost=%d late=%d"
            .format(worst, maxPpm, r.c.lost, r.c.late))
        assertTrue("depth off target by $worst ms", worst <= 3.0)
        assertTrue("steering saturated: $maxPpm", maxPpm < 900)
        assertEquals("late packets for concealed slots must be discarded", r.c.lost, r.c.late)
        assertTrue(r.c.lost > 0) // the stalls are longer than the buffer
        assertEquals(1, r.logs.count { it.startsWith("VCX-ALIGN") })
        assertTrue("no VCX-JUMP for plain stalls: ${r.logs.filter { it.startsWith("VCX-JUMP") }}",
            r.logs.none { it.startsWith("VCX-JUMP") })
    }

    @Test
    fun stallEvery2sWithK2AndRandomLoss() {
        val rnd = java.util.Random(5)
        val lossy = BooleanArray(20_000) { rnd.nextDouble() < 0.01 }
        val r = simulate(30, stallEvery2s(2), k = 2, drop = { lossy[it] })
        var worst = 0.0
        for (i in r.t.indices) if (r.t[i] >= 1_500_000 && !inStall(r.t[i])) worst = maxOf(worst, abs(r.depthMs[i] - 40.0))
        println("stall/2s K=2: worst |depth-40| = %.2f ms, recovered=%d lost=%d late=%d".format(worst, r.c.recovered, r.c.lost, r.c.late))
        assertTrue(worst <= 3.0)
        assertTrue(r.c.recovered > 50)
        assertTrue(r.ppm.all { abs(it) < 900 })
    }

    /**
     * The phone pattern: the delay floor moves around stalls. 4 s cycle: 2 s at base delay, 1.85 s at
     * +30 ms (slow / power-save phase), then a 150 ms stall whose burst flushes back to base delay.
     */
    private fun floorShift(seed: Long): (Int) -> Double {
        val rnd = java.util.Random(seed)
        val jitter = DoubleArray(20_000) { rnd.nextDouble() * 2000 }
        return { k ->
            val send = 1000.0 + k * 2500.0
            var a = send + 3000 + jitter[k % jitter.size]
            val ph = (send + 1_000_000) % 4_000_000.0
            if (ph in 2_000_000.0..3_850_000.0) a += 30_000
            if (ph > 3_850_000) a = maxOf(a, send - ph + 4_000_000 + 3000 + jitter[(k * 3) % jitter.size] / 2)
            a
        }
    }

    @Test
    fun legacyDepthSteeringReproducesPhoneSymptom() {
        // Pre-fix behaviour: steering on smoothed depth chases the delay floor. After every stall +
        // burst, depth lands ~30 ms above where it was at the end of the slow phase and steering pins.
        val r = simulate(30, floorShift(3), useLead = false)
        val saturatedFrac = r.ppm.count { abs(it) >= 999.0 }.toDouble() / r.ppm.size
        val maxDepth = r.depthMs.drop(20).maxOrNull()!!
        println("legacy: saturated %.0f %% of the time, max smoothed depth %.1f ms".format(saturatedFrac * 100, maxDepth))
        assertTrue(saturatedFrac > 0.3)
        assertTrue(maxDepth > 45.0)
    }

    @Test
    fun leadSteeringIgnoresDelayFloorShifts() {
        val r = simulate(30, floorShift(3))
        val maxPpm = r.ppm.drop(10).maxOf { abs(it) }
        var worstLow = 0.0
        var worstLead = 0.0
        for (i in r.t.indices) {
            if (r.t[i] < 3_000_000) continue
            worstLead = maxOf(worstLead, abs(r.leadMs[i] - 40.0))
            val ph = (r.t[i] + 1_000_000) % 4_000_000.0
            // Base-delay phase, once the 1 s window holds only base-delay samples.
            if (ph in 1_150_000.0..1_990_000.0) worstLow = maxOf(worstLow, abs(r.depthMs[i] - 40.0))
        }
        println("lead steering: max |ppm| = %.0f, worst |lead-40| = %.2f ms, worst |depth-40| at base delay = %.2f ms, lost=%d late=%d"
            .format(maxPpm, worstLead, worstLow, r.c.lost, r.c.late))
        assertTrue("steering saturated: $maxPpm", maxPpm < 900)
        assertTrue("lead moved $worstLead ms", worstLead <= 3.0)
        assertTrue("depth after stall+burst off target by $worstLow ms", worstLow <= 3.0)
        assertEquals(r.c.lost, r.c.late)
    }

    @Test
    fun silenceTrimAbsorbsSenderModeSwitch() {
        // Sender in silence-fill mode sends packets ~25 ms after their timeline position; in capture
        // mode it sends them at once. Switch to audio at 6 s (packets arrive 25 ms earlier: lead +25 ms),
        // back to silence fill at 14 s (25 ms later: lead -25 ms once the old d_min leaves the 10 s window).
        val rnd = java.util.Random(9)
        val isSilent = { k: Int -> val send = k * 2500.0; send < 6_000_000 || send >= 14_000_000 }
        val r = simulate(30, { k ->
            val send = 1000.0 + k * 2500.0
            send + 3000 + rnd.nextDouble() * 2000 + if (isSilent(k)) 25_000 else 0
        }, silent = isSilent)
        val c = r.c
        val jumps = r.logs.filter { it.startsWith("VCX-JUMP") }
        val siltrims = r.logs.filter { it.startsWith("VCX-SILTRIM") }
        println("mode switch: sil_dropped=${c.silDropped} sil_inserted=${c.silInserted} lost=${c.lost}\n  " +
            (jumps + siltrims).joinToString("\n  "))
        assertTrue(c.silDropped in 8L..12L)
        assertTrue(c.silInserted in 8L..12L)
        assertTrue(jumps.isNotEmpty())
        // Back near target within 1 s of the switch, and steering never pinned for more than one update.
        for (i in r.t.indices) {
            val t = r.t[i]
            if (t in 7_100_000.0..13_900_000.0 || t > 27_500_000) assertEquals("lead at t=$t", 40.0, r.leadMs[i], 3.0)
        }
        assertTrue(r.ppm.count { abs(it) >= 999.0 } <= 2)
        assertEquals(0L, c.lost)
    }

    /**
     * Sender catch-up during music: from packet 4000 (t = 10 s) every packet arrives 80 ms earlier, so
     * 32 extra packets land in one burst and real latency rises 80 ms with no silence to trim.
     * Fast cut must bring lead to <= target + 40 ms within 2 s, in one crossfaded cut.
     */
    @Test
    fun fastCutRecoversLatencySpikeDuringMusic() {
        val r = simulate(20, { k ->
            val nominal = 1000.0 + k * 2500.0 + 3000
            if (k < 4000) nominal else maxOf(nominal - 80_000, 1000.0 + 3999 * 2500.0 + 3000)
        })
        val peak = r.leadMs.indices.filter { r.t[it] in 10_000_000.0..11_000_000.0 }.maxOf { r.leadMs[it] }
        val after = r.leadMs.indices.filter { r.t[it] in 12_000_000.0..13_000_000.0 }.maxOf { r.leadMs[it] }
        println("fast cut: peak lead %.1f ms, lead 2-3 s after spike %.1f ms, cuts=%d packets=%d; ${r.logs.filter { it.startsWith("VCX-CUT") || it.startsWith("VCX-FASTCUT") }}".format(peak, after, r.c.cuts, r.c.cutPackets))
        assertTrue("spike not reproduced: $peak", peak > 100)
        assertTrue("lead still $after ms 2 s after the spike", after <= 80.0 + 3.0)
        assertEquals(1L, r.c.cuts)
        assertEquals(0L, r.c.silDropped)
        assertEquals(0L, r.c.lost)
    }
}
