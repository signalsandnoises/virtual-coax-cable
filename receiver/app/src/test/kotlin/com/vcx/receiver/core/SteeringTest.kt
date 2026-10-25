package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SteeringTest {

    /** Plant: d(err_ms)/dt = (clockOffsetPpm - ratePpm) / 1000. */
    private fun simulatePlant(e0: Double, offsetPpm: Double, seconds: Double, c: DepthController = DepthController()):
        List<Pair<Double, Double>> {
        val dt = 0.1
        var e = e0
        val trace = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        while (t < seconds) {
            val r = c.update(e, dt)
            e += (offsetPpm - r) / 1000.0 * dt
            t += dt
            trace += e to r
        }
        return trace
    }

    @Test
    fun tenMsErrorSettlesUnder20sWithoutOscillation() {
        val tr = simulatePlant(10.0, 0.0, 120.0)
        val settleIdx = tr.indexOfFirst { abs(it.first) < 1.0 }
        val settleSec = (settleIdx + 1) * 0.1
        val undershoot = tr.minOf { it.first }
        println("10 ms step: inside 1 ms after %.1f s, max undershoot %.2f ms, max ppm %.0f".format(settleSec, undershoot, tr.maxOf { it.second }))
        assertTrue("settled in $settleSec s", settleSec < 20.0)
        assertTrue("stays inside 1 ms after settling", tr.drop(settleIdx).all { abs(it.first) < 1.0 })
        assertTrue("no oscillation: undershoot $undershoot", undershoot > -0.5)
        assertTrue(tr.all { abs(it.second) <= 1000.0 })
    }

    @Test
    fun plantLearnsClockOffset() {
        val tr = simulatePlant(0.0, 200.0, 600.0)
        assertEquals(200.0, tr.last().second, 2.0)
        assertEquals(0.0, tr.last().first, 0.05)
    }

    /**
     * Full chain: real JitterBuffer + resampler + controller, sender clock offset [offsetPpm]
     * (sender produces that many ppm more frames per phone-second), jittered arrivals with periodic
     * Wi-Fi-style stalls that release a burst. Writes happen every 2.5 ms of phone time.
     */
    private fun simulateChain(offsetPpm: Double, seconds: Int, seed: Long): Triple<PlayoutChain, List<Double>, List<Double>> {
        val logs = ArrayList<String>()
        val jb = JitterBuffer(40, log = { logs += it })
        val chain = PlayoutChain(jb, log = { logs += it })
        val rnd = java.util.Random(seed)
        val nPackets = (seconds * 400 * (1 + offsetPpm * 1e-6)).toInt() + 400
        val periodUs = 2500.0 / (1 + offsetPpm * 1e-6)
        val arrivals = DoubleArray(nPackets)
        for (k in 0 until nPackets) {
            val send = 1000.0 + k * periodUs
            var a = send + 3000 + rnd.nextDouble() * 2000 // 3..5 ms base + jitter
            val inStall = (send % 2_000_000.0) // every 2 s a 15 ms stall: packets queue and release together
            if (inStall < 15_000) a = maxOf(a, send - inStall + 15_000 + 3000)
            arrivals[k] = a
        }
        val order = (0 until nPackets).sortedBy { arrivals[it] }
        val payload = ByteArray(120 * 4)
        val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)
        var next = 0
        val ppmTrace = ArrayList<Double>()
        val depthTrace = ArrayList<Double>()
        val writes = seconds * 400
        for (w in 0 until writes) {
            val t = w * 2500.0
            while (next < order.size && arrivals[order[next]] <= t) {
                val k = order[next++]
                val raw = PacketCodec.buildAudio(k, 0, 120, listOf(payload))
                jb.push(PacketCodec.parseAudio(raw, 0, raw.size)!!)
            }
            chain.read(out)
            if (w % 400 == 399) {
                ppmTrace += chain.ratePpm.toDouble()
                depthTrace += chain.lastFilteredDepthFrames / 48.0
            }
        }
        assertTrue("aligned once: $logs", logs.count { it.startsWith("VCX-ALIGN") } == 1)
        return Triple(chain, ppmTrace, depthTrace)
    }

    @Test
    fun chainConvergesUnderPlus200ppmClockOffset() {
        val (chain, ppm, depth) = simulateChain(+200.0, 400, 7)
        val lastPpm = ppm.takeLast(60).average()
        val lastDepth = depth.takeLast(60).average()
        println("+200 ppm: rate_ppm(last 60 s) = %.1f, depth = %.2f ms, lost=%d".format(lastPpm, lastDepth, chain.jitter.counters().lost))
        assertEquals(200.0, lastPpm, 25.0)
        assertEquals(40.0, lastDepth, 1.0)
        assertEquals(0L, chain.jitter.counters().lost)
        assertEquals(0L, chain.jitter.counters().caps)
    }

    @Test
    fun chainConvergesUnderMinus200ppmClockOffset() {
        val (chain, ppm, depth) = simulateChain(-200.0, 400, 11)
        val lastPpm = ppm.takeLast(60).average()
        val lastDepth = depth.takeLast(60).average()
        println("-200 ppm: rate_ppm(last 60 s) = %.1f, depth = %.2f ms".format(lastPpm, lastDepth))
        assertEquals(-200.0, lastPpm, 25.0)
        assertEquals(40.0, lastDepth, 1.0)
        assertEquals(0L, chain.jitter.counters().lost)
    }

    @Test
    fun alignmentPutsDepthOnTarget() {
        // Prime at 16 packets, then a burst of 12 extra packets before playout starts consuming:
        // alignment must drop the excess so depth lands on target.
        val logs = ArrayList<String>()
        val jb = JitterBuffer(40, log = { logs += it })
        val chain = PlayoutChain(jb, steer = false, log = { logs += it })
        val payload = ByteArray(480)
        fun push(k: Int) {
            val raw = PacketCodec.buildAudio(k, 0, 120, listOf(payload))
            jb.push(PacketCodec.parseAudio(raw, 0, raw.size)!!)
        }
        var k = 0
        repeat(28) { push(k++) }
        val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)
        repeat(200) { chain.read(out); push(k++) } // 500 ms of steady playout
        // Depth is sampled right after each read (before the next arrival): 27 packets = 67.5 ms -> drop 11.
        val align = logs.single { it.startsWith("VCX-ALIGN") }
        assertTrue(align, align.contains("depth_ms=67.5") && align.contains("action=drop packets=11"))
        assertEquals(40.0, chain.lastDepthFrames / 48.0, 2.6)
        assertEquals(0f, chain.ratePpm)
    }

    @Test
    fun steerOffKeepsRatioZero() {
        val jb = JitterBuffer(40)
        val chain = PlayoutChain(jb, steer = false, align = false)
        val payload = ByteArray(480)
        val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)
        var k = 0
        fun push() { val raw = PacketCodec.buildAudio(k++, 0, 120, listOf(payload)); jb.push(PacketCodec.parseAudio(raw, 0, raw.size)!!) }
        repeat(30) { push() }
        repeat(2000) { chain.read(out); push() }
        assertEquals(0f, chain.ratePpm)
    }
}
