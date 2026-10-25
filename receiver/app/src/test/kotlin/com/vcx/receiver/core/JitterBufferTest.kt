package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private val frames = 120
    private val logs = mutableListOf<String>()
    private val out = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * 4)

    /** 40 ms at 120 frames/packet = 16 packets. */
    private fun jb(targetMs: Int = 40, fades: Boolean = false) = JitterBuffer(targetMs, fades = fades, log = { logs += it })

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
    fun hardCapTrimsOnlyBeyondTargetPlus500ms() {
        val b = jb()
        // target 16 packets + 500 ms (200 packets) = 216 allowed; 217 trips the cap.
        for (s in 0 until 216) b.push(pkt(s))
        assertEquals(0, b.counters().caps)
        b.push(pkt(216))
        assertEquals(40_000, b.depthUs())
        val c = b.counters()
        assertEquals(1, c.caps); assertEquals(201, c.cappedPackets)
        assertEquals(201, readSeq(b)) // oldest kept
        assertTrue(logs.any { it.startsWith("VCX-CAP") })
    }

    @Test
    fun noTrimAtTargetPlus100msAnyMore() {
        val b = jb()
        for (s in 0 until 57) b.push(pkt(s))
        assertEquals(0, b.counters().caps)
        assertEquals(0, readSeq(b))
    }

    @Test
    fun alignmentDropsOldest() {
        val b = jb()
        for (s in 0 until 24) b.push(pkt(s))
        assertEquals(60_000, b.depthUs())
        assertEquals(8, b.dropOldest(8))
        assertEquals(40_000, b.depthUs())
        assertEquals(8, readSeq(b))
        assertEquals(8, b.counters().alignDropped)
        assertEquals(0, b.counters().lost)
    }

    @Test
    fun alignmentInsertsSilenceWithoutConsumingSlots() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        b.insertSilence(4)
        assertEquals(50_000, b.depthUs())
        repeat(4) { assertEquals(null, readSeq(b)) }
        for (s in 0 until 16) assertEquals(s, readSeq(b))
        val c = b.counters()
        assertEquals(4, c.alignInserted); assertEquals(0, c.lost); assertEquals(0, c.concealed)
    }

    @Test
    fun concealmentRepeatsLastPacketWithFadeOutThenFadesIn() {
        val b = jb(fades = true)
        val loud = ByteArray(frames * 4) { if (it % 2 == 0) 0x10 else 0x27 } // every sample = 0x2710 = 10000
        fun loudPkt(seq: Int): AudioPacket {
            val raw = PacketCodec.buildAudio(seq, 0, frames, listOf(loud))
            return PacketCodec.parseAudio(raw, 0, raw.size)!!
        }
        for (s in 0 until 20) if (s != 3 && s != 4) b.push(loudPkt(s))
        fun sample(frame: Int, ch: Int = 0) = PacketCodec.u16(out, (frame * 2 + ch) * 2).toShort().toInt()
        repeat(3) { b.read(out); assertEquals(10000, sample(0)); assertEquals(10000, sample(frames - 1)) }
        b.read(out) // seq 3 missing: repeat of last good, fading out
        assertEquals(10000 * (frames - 1) / frames.toDouble(), sample(0).toDouble(), 1.0)
        assertEquals(0, sample(frames - 1))
        assertTrue(sample(60) in 4000..6000)
        b.read(out) // seq 4 missing: silence
        for (f in 0 until frames) assertEquals(0, sample(f, 1))
        b.read(out) // seq 5: fade in
        assertEquals(0, sample(0))
        assertEquals(10000 * (frames - 1) / frames.toDouble(), sample(frames - 1).toDouble(), 1.0)
        b.read(out) // seq 6: full level
        assertEquals(10000, sample(0))
        val c = b.counters()
        assertEquals(2, c.lost); assertEquals(2, c.concealed)
    }

    @Test
    fun redundancyKReadPerPacketGrowingFromZero() {
        val b = jb()
        // K = 0 for the first packets, then 1, then 2 (sender --redundancy 2 after start).
        val ks = IntArray(40) { if (it < 10) 0 else if (it < 20) 1 else 2 }
        val drop = setOf(5, 15, 25, 26)
        for (s in 0 until 40) if (s !in drop) b.push(pkt(s, ks[s]))
        for (s in 0 until 40) {
            val got = readSeq(b)
            if (s == 5) assertEquals(null, got) else assertEquals(s, got)
        }
        val c = b.counters()
        assertEquals(3, c.recovered) // 15 via K=1, 25 and 26 via K=2 of packet 27
        assertEquals(1, c.lost)
        assertEquals(2, c.maxK)
    }

    @Test
    fun starvationConcealsThenRebuffers() {
        val b = jb()
        for (s in 0 until 16) b.push(pkt(s))
        repeat(16) { readSeq(b) }
        // Sender silent: conceal up to the stall limit (max(target, 1000 ms) = 400 slots), then re-prime.
        repeat(400) { assertEquals(null, readSeq(b)) }
        assertEquals(0, b.read(out)) // not primed any more
        assertEquals(1, b.counters().rebuffers)
        assertEquals(400, b.counters().lost)
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

    @Test
    fun nearSilenceDetectionWithin2Lsb() {
        val b = ByteArray(480)
        assertTrue(Pcm.isNearSilent(b, 120))
        PacketCodec.putU16(b, 100, 2); PacketCodec.putU16(b, 200, -2)
        assertTrue(Pcm.isNearSilent(b, 120))
        PacketCodec.putU16(b, 300, 3)
        assertTrue(!Pcm.isNearSilent(b, 120))
    }

    private fun silentPkt(seq: Int, flagged: Boolean): AudioPacket {
        val raw = PacketCodec.buildAudio(seq, 0, frames, listOf(ByteArray(frames * 4)), flags = if (flagged) Vcx.FLAG_SILENCE_FILL else 0)
        return PacketCodec.parseAudio(raw, 0, raw.size)!!
    }

    @Test
    fun silenceBudgetDropsOnlySilentPackets() {
        val b = jb()
        for (s in 0 until 24) b.push(if (s in 2..6) silentPkt(s, flagged = s % 2 == 0) else pkt(s))
        b.setSilenceBudget(drop = 3, insert = 0)
        assertEquals(0, readSeq(b)); assertEquals(1, readSeq(b))
        assertEquals(null, readSeq(b)) // seqs 2,3,4 dropped; plays silent seq 5
        assertEquals(null, readSeq(b)) // silent seq 6
        assertEquals(7, readSeq(b))
        assertEquals(-3, b.pollSilenceEdits())
        assertEquals(3, b.counters().silDropped)
        assertEquals(0, b.counters().lost)
    }

    @Test
    fun silenceBudgetInsertsOnlyBeforeSilentPackets() {
        val b = jb()
        for (s in 0 until 20) b.push(if (s == 3) silentPkt(s, flagged = true) else pkt(s))
        b.setSilenceBudget(drop = 0, insert = 2)
        assertEquals(0, readSeq(b)); assertEquals(1, readSeq(b)); assertEquals(2, readSeq(b))
        repeat(3) { assertEquals(null, readSeq(b)) } // 2 inserted + silent seq 3
        assertEquals(4, readSeq(b))
        assertEquals(2, b.pollSilenceEdits())
        assertEquals(2, b.counters().silInserted)
    }
}
