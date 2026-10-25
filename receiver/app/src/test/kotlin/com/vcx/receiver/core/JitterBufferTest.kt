package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private val frames = 120
    private val logs = mutableListOf<String>()
    private val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)

    /** 40 ms at 120 frames/packet = 16 packets. */
    private fun jb(targetMs: Int = 40) = JitterBuffer(targetMs, log = { logs += it })

    /** Payload whose first 4 bytes are the seq, rest a constant, so playout order can be checked. */
    private fun payloadFor(seq: Int) = ByteArray(frames * 4).also {
        PacketCodec.putI32(it, 0, seq); it[4] = 0x55
    }

    private fun pkt(seq: Int, k: Int = 0): AudioPacket {
        val payloads = (0..k).map { payloadFor(seq - it) }
        val raw = PacketCodec.buildAudio(seq, 0, frames, payloads)
        return PacketCodec.parseAudio(raw, 0, raw.size)!!
    }

    /** Reads one slot and returns the seq stored in its payload, or null for silence. */
    private fun readSeq(b: JitterBuffer): Int? {
        assertEquals(frames, b.read(out))
        return if (out[4] == 0x55.toByte()) PacketCodec.i32(out, 0) else {
            for (i in 0 until frames * 4) assertEquals("concealment must be silence", 0, out[i].toInt())
            null
        }
    }

    @Test
    fun primesAtTargetThenPlaysInOrder() {
        val b = jb()
        for (s in 0 until 15) b.push(pkt(s))
        assertEquals(0, b.read(out)) // 15 < 16 packets: still priming
        assertEquals(15 * 2500, b.depthUs())
        b.push(pkt(15))
        assertEquals(40_000, b.depthUs())
        for (s in 0 until 16) assertEquals(s, readSeq(b))
        val c = b.counters()
        assertEquals(16, c.received); assertEquals(0, c.lost); assertEquals(0, c.concealed)
        assertTrue(logs.any { it.startsWith("VCX-PRIMED") })
    }

    @Test
    fun steadyStateKeepsDepthAtTarget() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        for (s in 16 until 1000) {
            assertEquals(s - 16, readSeq(b))
            b.push(pkt(s))
            assertEquals(40_000, b.depthUs())
        }
    }

    @Test
    fun orderingAcrossU32Wrap() {
        val b = jb()
        val start = 0xFFFFFFF8L.toInt()
        for (q in 0 until 16) b.push(pkt(start + q)) // ...FFF8 .. 0x00000007
        for (q in 0 until 16) assertEquals(start + q, readSeq(b))
        assertEquals(0, b.counters().lost)
    }

    @Test
    fun reorderedPacketsArePlayedInSeqOrder() {
        val b = jb()
        val order = listOf(1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14)
        order.forEach { b.push(pkt(it)) }
        for (s in 0 until 16) assertEquals(s, readSeq(b))
        assertEquals(0, b.counters().lost)
    }

    @Test
    fun gapIsConcealedAndCountedLost() {
        val b = jb()
        for (s in 0 until 20) if (s != 5) b.push(pkt(s))
        for (s in 0 until 20) {
            val got = readSeq(b)
            if (s == 5) assertEquals(null, got) else assertEquals(s, got)
        }
        val c = b.counters()
        assertEquals(1, c.lost); assertEquals(1, c.concealed); assertEquals(0, c.recovered)
    }

    @Test
    fun redundantCopyRecoversLostPacket() {
        val b = jb()
        for (s in 0 until 20) if (s != 5) b.push(pkt(s, k = 1))
        for (s in 0 until 20) assertEquals(s, readSeq(b)) // seq 5 played from packet 6's redundant copy
        val c = b.counters()
        assertEquals(1, c.recovered); assertEquals(0, c.lost); assertEquals(0, c.concealed)
    }

    @Test
    fun primaryArrivingAfterRedundantCopyIsNotCountedRecovered() {
        val b = jb()
        for (s in 0 until 20) if (s != 5) b.push(pkt(s, k = 1))
        b.push(pkt(5, k = 1)) // reordered, before its slot
        for (s in 0 until 20) assertEquals(s, readSeq(b))
        assertEquals(0, b.counters().recovered)
    }

    @Test
    fun latePacketIsDiscardedAndCounted() {
        val b = jb()
        for (s in 0 until 20) if (s != 5) b.push(pkt(s))
        repeat(6) { readSeq(b) } // plays 0..4 and conceals 5
        b.push(pkt(5)) // too late
        for (s in 6 until 20) assertEquals(s, readSeq(b))
        val c = b.counters()
        assertEquals(1, c.late); assertEquals(1, c.lost); assertEquals(20, c.received)
    }

    @Test
    fun duplicateIsIgnored() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        b.push(pkt(10))
        for (s in 0 until 16) assertEquals(s, readSeq(b))
        assertEquals(1, b.counters().duplicates)
        assertEquals(17, b.counters().received)
    }

    @Test
    fun overflowTrimsOldestDownToTarget() {
        val b = jb()
        // A burst after a stall: 16 + 40 (100 ms) + 1 packets -> over the limit, trimmed to 16.
        for (s in 0 until 57) b.push(pkt(s))
        assertEquals(40_000, b.depthUs())
        val c = b.counters()
        assertEquals(1, c.trims); assertEquals(41, c.trimmedPackets)
        assertEquals(41, readSeq(b)) // oldest kept
        assertTrue(logs.any { it.startsWith("VCX-TRIM") })
    }

    @Test
    fun noTrimAtExactlyTargetPlusExcess() {
        val b = jb()
        for (s in 0 until 56) b.push(pkt(s))
        assertEquals(0, b.counters().trims)
        assertEquals(0, readSeq(b))
    }

    @Test
    fun starvationConcealsThenRebuffers() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        repeat(16) { readSeq(b) }
        // Sender silent: conceal up to the stall limit (max(target, 200 ms) = 80 slots), then re-prime.
        repeat(80) { assertEquals(null, readSeq(b)) }
        assertEquals(0, b.read(out)) // not primed any more
        assertEquals(1, b.counters().rebuffers)
        assertEquals(80, b.counters().lost)
        // Stream resumes at an arbitrary seq and primes again.
        for (s in 500 until 516) b.push(pkt(s))
        assertEquals(500, readSeq(b))
    }

    @Test
    fun seqJumpResyncs() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        repeat(4) { readSeq(b) }
        for (s in 100_000 until 100_016) b.push(pkt(s)) // sender restarted with a new seq base
        assertEquals(1, b.counters().resyncs)
        assertEquals(100_000, readSeq(b))
    }

    @Test
    fun targetRoundsUpToWholePackets() {
        val b = jb(targetMs = 21) // 1008 frames -> 9 packets
        for (s in 0 until 8) b.push(pkt(s))
        assertEquals(0, b.read(out))
        b.push(pkt(8))
        assertEquals(0, readSeq(b))
    }
}
