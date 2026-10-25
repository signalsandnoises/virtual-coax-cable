package com.vcx.receiver.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {

    private fun payload(frames: Int, fill: Int) = ByteArray(frames * 4) { (fill + it).toByte() }

    @Test
    fun parsesHeaderFieldsLittleEndian() {
        val raw = PacketCodec.buildAudio(
            seq = 0x89ABCDEF.toInt(), timestampUs = 0x0102030405060708L, frames = 120,
            payloads = listOf(payload(120, 1)), flags = Vcx.FLAG_SILENCE_FILL,
        )
        assertEquals(24 + 480, raw.size)
        // Spot-check the raw layout.
        assertArrayEquals(byteArrayOf(0x56, 0x43, 0x58, 0x31), raw.copyOfRange(0, 4))
        assertEquals(1, raw[4].toInt()); assertEquals(1, raw[5].toInt())
        assertEquals(0x01, raw[6].toInt()); assertEquals(0x00, raw[7].toInt())
        assertEquals(0xEF.toByte(), raw[8]); assertEquals(0x89.toByte(), raw[11])
        assertEquals(0x08.toByte(), raw[12]); assertEquals(0x01.toByte(), raw[19])
        assertEquals(120, raw[20].toInt()); assertEquals(0, raw[21].toInt())
        assertEquals(1, raw[22].toInt()); assertEquals(0, raw[23].toInt())

        val p = PacketCodec.parseAudio(raw, 0, raw.size)!!
        assertEquals(Vcx.TYPE_AUDIO, p.header.type)
        assertEquals(Vcx.FLAG_SILENCE_FILL, p.flags)
        assertEquals(0x89ABCDEFL, Seq.toUnsigned(p.seq))
        assertEquals(0x0102030405060708L, p.header.timestampUs)
        assertEquals(120, p.frames)
        assertEquals(1, p.header.format)
        assertEquals(0, p.k)
        assertArrayEquals(payload(120, 1), p.payload(0))
    }

    @Test
    fun rejectsBadMagicVersionTypeFormatAndTruncation() {
        val good = PacketCodec.buildAudio(5, 0, 120, listOf(payload(120, 0)))
        assertNotNull(PacketCodec.parseAudio(good, 0, good.size))

        good.copyOf().also { it[0] = 'X'.code.toByte() }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) }
        good.copyOf().also { it[3] = '2'.code.toByte() }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) }
        good.copyOf().also { it[4] = 2 }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) } // version
        good.copyOf().also { it[5] = 4 }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) } // type STATS
        good.copyOf().also { it[22] = 2 }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) } // format
        good.copyOf().also { it[20] = 0; it[21] = 0 }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) } // 0 frames
        assertNull(PacketCodec.parseAudio(good, 0, good.size - 1)) // truncated payload
        assertNull(PacketCodec.parseAudio(good, 0, 23)) // shorter than header
        // K = 1 announced but only one payload present.
        good.copyOf().also { it[23] = 1 }.let { assertNull(PacketCodec.parseAudio(it, 0, it.size)) }
    }

    @Test
    fun extractsRedundantPayloadsMostRecentFirst() {
        val cur = payload(120, 10); val prev1 = payload(120, 20); val prev2 = payload(120, 30)
        val raw = PacketCodec.buildAudio(100, 0, 120, listOf(cur, prev1, prev2))
        assertEquals(24 + 3 * 480, raw.size)
        val p = PacketCodec.parseAudio(raw, 0, raw.size)!!
        assertEquals(2, p.k)
        assertArrayEquals(cur, p.payload(0))
        assertArrayEquals(prev1, p.payload(1))
        assertArrayEquals(prev2, p.payload(2))
        assertEquals(24, p.payloadOffset(0))
        assertEquals(24 + 480, p.payloadOffset(1))
    }

    @Test
    fun k1PacketIs984Bytes() {
        val raw = PacketCodec.buildAudio(1, 0, 120, listOf(payload(120, 0), payload(120, 1)))
        assertEquals(984, raw.size)
    }

    @Test
    fun discoverAndReply() {
        val d = PacketCodec.buildDiscover(0, 0, "MY-PC")
        assertEquals("MY-PC", PacketCodec.parseDiscover(d, 0, d.size))
        val empty = PacketCodec.buildDiscover(0, 0, "")
        assertEquals("", PacketCodec.parseDiscover(empty, 0, empty.size))
        val audio = PacketCodec.buildAudio(1, 0, 120, listOf(payload(120, 0)))
        assertNull(PacketCodec.parseDiscover(audio, 0, audio.size))

        val r = PacketCodec.buildDiscoverReply(7, 99, 47800, "SM-S911U1")
        val h = PacketCodec.parseHeader(r, 0, r.size)!!
        assertEquals(Vcx.TYPE_DISCOVER_REPLY, h.type)
        assertEquals(7, h.seq)
        assertEquals(47800, PacketCodec.u16(r, 24))
        assertEquals("SM-S911U1", String(r, 26, r.size - 26, Charsets.UTF_8))
        assertEquals(0, h.flags)
        val c = PacketCodec.buildDiscoverReply(8, 99, 47800, "SM-S911U1", Vcx.FLAG_CLAIM)
        assertEquals(Vcx.FLAG_CLAIM, PacketCodec.parseHeader(c, 0, c.size)!!.flags)
    }

    @Test
    fun seqDiffHandlesWrap() {
        assertEquals(1, Seq.diff(0, -1)) // 0 follows 0xFFFFFFFF
        assertEquals(-1, Seq.diff(-1, 0))
        assertEquals(5, Seq.diff(2, 0xFFFFFFFDL.toInt()))
        assertTrue(Seq.diff(0x7FFFFFFF, 0x7FFFFFFE) > 0)
        assertTrue(Seq.diff(0x80000000L.toInt(), 0x7FFFFFFF) > 0)
    }
}
