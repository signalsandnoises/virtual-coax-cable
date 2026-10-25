package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    private val snap = StatsSnapshot(
        bufferUs = 41_200, targetUs = 40_000, targetMs = 40, received = 400, lost = 3, recovered = 2,
        concealed = 3, late = 1, underruns = 5, ratePpm = -12.5f, trackFrames = 192,
        delayP99Ms = 3.25f, delayMaxMs = 7.5f, gapMaxMs = 9.0f, depthMinMs = 35.5f,
    )

    @Test
    fun statsPacketByteLayout() {
        val b = PacketCodec.buildStats(9, 123456789L, snap)
        assertEquals(24 + 56, b.size)
        val h = PacketCodec.parseHeader(b, 0, b.size)!!
        assertEquals(Vcx.TYPE_STATS, h.type)
        assertEquals(9, h.seq)
        assertEquals(123456789L, h.timestampUs)
        val o = 24
        assertEquals(41_200, PacketCodec.i32(b, o + 0))
        assertEquals(40_000, PacketCodec.i32(b, o + 4))
        assertEquals(400, PacketCodec.i32(b, o + 8))
        assertEquals(3, PacketCodec.i32(b, o + 12))
        assertEquals(2, PacketCodec.i32(b, o + 16))
        assertEquals(3, PacketCodec.i32(b, o + 20))
        assertEquals(1, PacketCodec.i32(b, o + 24))
        assertEquals(5, PacketCodec.i32(b, o + 28))
        assertEquals(-12.5f, java.lang.Float.intBitsToFloat(PacketCodec.i32(b, o + 32)), 0f)
        assertEquals(192, PacketCodec.i32(b, o + 36))
        assertEquals(3.25f, PacketCodec.f32(b, o + 40), 0f)
        assertEquals(7.5f, PacketCodec.f32(b, o + 44), 0f)
        assertEquals(9.0f, PacketCodec.f32(b, o + 48), 0f)
        assertEquals(35.5f, PacketCodec.f32(b, o + 52), 0f)
        // Little-endian check on one field: 41200 = 0x0000A0F0.
        assertEquals(0xF0.toByte(), b[o]); assertEquals(0xA0.toByte(), b[o + 1]); assertEquals(0, b[o + 3].toInt())
    }

    @Test
    fun statsCountersAreU32() {
        val b = PacketCodec.buildStats(0, 0, snap.copy(received = 0xFFFF_FFFFL))
        assertEquals(0xFFFF_FFFFL, PacketCodec.i32(b, 24 + 8).toLong() and 0xFFFF_FFFFL)
    }

    @Test
    fun logLineMatchesProtocolExample() {
        val s = StatsSnapshot(41_200, 40_000, 40, 400, 0, 0, 0, 0, 0, 0f, 192, 3.1f, 7.9f, 9.0f, 35.0f)
        assertEquals(
            "VCX-STATS buf_ms=41.2 target_ms=40 rx=400 lost=0 recovered=0 concealed=0 late=0 underruns=0 rate_ppm=0.0 track_frames=192 delay_p99_ms=3.1 delay_max_ms=7.9 gap_max_ms=9.0 depth_min_ms=35.0",
            StatsFormat.logLine(s),
        )
    }

    @Test
    fun logLineFormatsAllFields() {
        assertEquals(
            "VCX-STATS buf_ms=41.2 target_ms=40 rx=400 lost=3 recovered=2 concealed=3 late=1 underruns=5 rate_ppm=-12.5 track_frames=192 delay_p99_ms=3.3 delay_max_ms=7.5 gap_max_ms=9.0 depth_min_ms=35.5",
            StatsFormat.logLine(snap),
        )
    }
}
