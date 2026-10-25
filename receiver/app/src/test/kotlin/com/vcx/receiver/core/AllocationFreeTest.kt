package com.vcx.receiver.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Proves the per-packet hot path does not allocate: rx parse into a reused AudioPacket, DelayStats,
 * JitterBuffer push, and playout reads through the resampler and PlayoutChain (incl. its 100 ms
 * controller updates, silence checks and jump checks). Uses HotSpot's per-thread allocation counter;
 * the unit-test JVM runs with -XX:-DoEscapeAnalysis so the JIT cannot hide allocations.
 */
class AllocationFreeTest {

    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocatedBytes(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Runs [packets] packets through the hot path and returns bytes allocated by this thread. */
    private class HotPath(val writeFrames: Int, val k: Int) {
        val jb = JitterBuffer(40, log = {})
        val delay = DelayStats()
        var now = 0L
        val chain = PlayoutChain(jb, delay = delay, clockUs = { now }, log = {})
        val wire = ByteArray(Vcx.HEADER_SIZE + 480 * (k + 1))
        val payload = ByteArray(480) { (it * 7 + 1).toByte() } // not silent: no silence edits
        val pkt = AudioPacket()
        val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)
        var seq = 0
        var inFrames = 0L
        var outFrames = 0L

        fun run(packets: Int) {
            for (i in 0 until packets) {
                PacketCodec.writeHeader(wire, 0, Vcx.TYPE_AUDIO, 0, seq, 0L, 120, Vcx.FORMAT_48K_STEREO_S16, k)
                for (j in 0..k) System.arraycopy(payload, 0, wire, Vcx.HEADER_SIZE + j * 480, 480)
                check(PacketCodec.parseAudioInto(wire, 0, wire.size, pkt))
                now = seq * 2500L + 3000L + (seq % 4) * 300L
                delay.onArrival(pkt.seq, pkt.frames, now)
                jb.push(pkt)
                seq++
                inFrames += 120
                while (outFrames < inFrames - 2400) {
                    chain.read(out, writeFrames)
                    outFrames += writeFrames
                }
            }
        }
    }

    private fun measure(writeFrames: Int, k: Int): Long {
        val h = HotPath(writeFrames, k)
        h.run(30_000) // warm-up: priming, alignment, JIT, all windows full
        allocatedBytes()
        val before = allocatedBytes()
        h.run(20_000)
        val after = allocatedBytes()
        return after - before
    }

    @Test
    fun hotPathAllocatesNothingPerPacket() {
        for ((wf, k) in listOf(120 to 0, 192 to 0, 192 to 2, 480 to 1)) {
            val bytes = measure(wf, k)
            println("write_frames=$wf K=$k: $bytes bytes allocated over 20000 packets")
            // Any per-packet allocation would be >= 16 B x 20000 = 320 kB.
            assertTrue("write_frames=$wf K=$k allocated $bytes bytes", bytes < 2048)
        }
    }

    @Test
    fun measurementDetectsAllocations() {
        // Sanity check of the method: the allocating parse is caught.
        val wire = PacketCodec.buildAudio(1, 0, 120, listOf(ByteArray(480)))
        repeat(1000) { PacketCodec.parseAudio(wire, 0, wire.size) }
        val before = allocatedBytes()
        var sink = 0
        for (i in 0 until 20_000) sink += PacketCodec.parseAudio(wire, 0, wire.size)!!.frames
        val bytes = allocatedBytes() - before
        println("allocating parse: $bytes bytes over 20000 packets (sink=$sink)")
        assertTrue(bytes > 100_000)
    }
}
