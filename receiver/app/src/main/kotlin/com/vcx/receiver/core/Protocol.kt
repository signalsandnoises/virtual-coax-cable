package com.vcx.receiver.core

import java.nio.charset.StandardCharsets

/** VCX protocol v1 constants. See PROTOCOL.md at the repo root (authoritative). */
object Vcx {
    const val VERSION = 1
    const val TYPE_AUDIO = 1
    const val TYPE_DISCOVER = 2
    const val TYPE_DISCOVER_REPLY = 3
    const val TYPE_STATS = 4

    const val HEADER_SIZE = 24
    const val STATS_BODY_SIZE = 56

    const val FORMAT_48K_STEREO_S16 = 1
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4
    const val DEFAULT_FRAMES = 120
    const val MAX_FRAMES_PER_PACKET = 4800 // 100 ms; sanity limit only

    const val FLAG_SILENCE_FILL = 1
    /** DISCOVER_REPLY: Play was just pressed here and no audio has arrived yet; the PC switches to us. */
    const val FLAG_CLAIM = 2

    const val AUDIO_PORT = 47800
    const val DISCOVERY_PORT = 47801

    /** ASCII "VCX1". */
    val MAGIC = byteArrayOf(0x56, 0x43, 0x58, 0x31)
}

data class Header(
    val type: Int,
    val flags: Int,
    /** u32 on the wire, stored as Int bits. Compare with [Seq.diff]. */
    val seq: Int,
    val timestampUs: Long,
    val frames: Int,
    val format: Int,
    val k: Int,
)

/**
 * A parsed AUDIO packet. Payloads are not copied: they are views into [data].
 * Payload 0 is the current one (seq), payload j (1..k) is the redundant copy of seq - j.
 *
 * Mutable so the receive thread can reuse one instance for every packet
 * ([PacketCodec.parseAudioInto]) without allocating.
 */
class AudioPacket {
    var type = 0; private set
    var flags = 0; private set
    var seq = 0; private set
    var timestampUs = 0L; private set
    var frames = 0; private set
    var format = 0; private set
    var k = 0; private set
    var data: ByteArray = EMPTY; private set
    private var bodyOffset = 0

    /** Allocates; for tests and diagnostics only. */
    val header: Header get() = Header(type, flags, seq, timestampUs, frames, format, k)
    val payloadBytes: Int get() = frames * Vcx.BYTES_PER_FRAME

    internal fun set(buf: ByteArray, off: Int) {
        type = PacketCodec.u8(buf, off + 5)
        flags = PacketCodec.u16(buf, off + 6)
        seq = PacketCodec.i32(buf, off + 8)
        timestampUs = PacketCodec.i64(buf, off + 12)
        frames = PacketCodec.u16(buf, off + 20)
        format = PacketCodec.u8(buf, off + 22)
        k = PacketCodec.u8(buf, off + 23)
        data = buf
        bodyOffset = off + Vcx.HEADER_SIZE
    }

    fun payloadOffset(j: Int): Int {
        require(j in 0..k) { "payload index $j out of 0..$k" }
        return bodyOffset + j * payloadBytes
    }

    fun payload(j: Int): ByteArray = data.copyOfRange(payloadOffset(j), payloadOffset(j) + payloadBytes)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** u32 sequence arithmetic with wrap-around. */
object Seq {
    /** Signed distance a - b, correct across the u32 wrap as long as |a - b| < 2^31. */
    fun diff(a: Int, b: Int): Int = a - b

    fun toUnsigned(seq: Int): Long = seq.toLong() and 0xFFFF_FFFFL
}

object PacketCodec {

    fun parseHeader(buf: ByteArray, off: Int, len: Int): Header? {
        if (len < Vcx.HEADER_SIZE || off < 0 || off + len > buf.size) return null
        for (i in 0 until 4) if (buf[off + i] != Vcx.MAGIC[i]) return null
        if (u8(buf, off + 4) != Vcx.VERSION) return null
        return Header(
            type = u8(buf, off + 5),
            flags = u16(buf, off + 6),
            seq = i32(buf, off + 8),
            timestampUs = i64(buf, off + 12),
            frames = u16(buf, off + 20),
            format = u8(buf, off + 22),
            k = u8(buf, off + 23),
        )
    }

    /** Returns null for anything that is not a well-formed AUDIO packet we can play. Allocates. */
    fun parseAudio(buf: ByteArray, off: Int, len: Int): AudioPacket? {
        val p = AudioPacket()
        return if (parseAudioInto(buf, off, len, p)) p else null
    }

    /**
     * Allocation-free AUDIO parse into a reused [out]. Returns false (and leaves [out] undefined) for
     * anything that is not a well-formed AUDIO packet we can play.
     */
    fun parseAudioInto(buf: ByteArray, off: Int, len: Int, out: AudioPacket): Boolean {
        if (len < Vcx.HEADER_SIZE || off < 0 || off + len > buf.size) return false
        for (i in 0 until 4) if (buf[off + i] != Vcx.MAGIC[i]) return false
        if (u8(buf, off + 4) != Vcx.VERSION) return false
        if (u8(buf, off + 5) != Vcx.TYPE_AUDIO) return false
        if (u8(buf, off + 22) != Vcx.FORMAT_48K_STEREO_S16) return false
        val frames = u16(buf, off + 20)
        if (frames <= 0 || frames > Vcx.MAX_FRAMES_PER_PACKET) return false
        val needed = Vcx.HEADER_SIZE.toLong() + frames.toLong() * Vcx.BYTES_PER_FRAME * (1 + u8(buf, off + 23))
        if (len < needed) return false
        out.set(buf, off)
        return true
    }

    /** Returns the PC name (possibly empty) if this is a DISCOVER packet, else null. */
    fun parseDiscover(buf: ByteArray, off: Int, len: Int): String? {
        val h = parseHeader(buf, off, len) ?: return null
        if (h.type != Vcx.TYPE_DISCOVER) return null
        return String(buf, off + Vcx.HEADER_SIZE, len - Vcx.HEADER_SIZE, StandardCharsets.UTF_8)
    }

    fun writeHeader(
        buf: ByteArray, off: Int, type: Int, flags: Int, seq: Int, timestampUs: Long,
        frames: Int, format: Int, k: Int,
    ) {
        System.arraycopy(Vcx.MAGIC, 0, buf, off, 4)
        buf[off + 4] = Vcx.VERSION.toByte()
        buf[off + 5] = type.toByte()
        putU16(buf, off + 6, flags)
        putI32(buf, off + 8, seq)
        putI64(buf, off + 12, timestampUs)
        putU16(buf, off + 20, frames)
        buf[off + 22] = format.toByte()
        buf[off + 23] = k.toByte()
    }

    /** Builds an AUDIO packet: payloads[0] is current, payloads[1..] are seq-1, seq-2, ... */
    fun buildAudio(seq: Int, timestampUs: Long, frames: Int, payloads: List<ByteArray>, flags: Int = 0): ByteArray {
        require(payloads.isNotEmpty())
        val pb = frames * Vcx.BYTES_PER_FRAME
        val out = ByteArray(Vcx.HEADER_SIZE + pb * payloads.size)
        writeHeader(out, 0, Vcx.TYPE_AUDIO, flags, seq, timestampUs, frames, Vcx.FORMAT_48K_STEREO_S16, payloads.size - 1)
        payloads.forEachIndexed { j, p ->
            require(p.size == pb) { "payload $j has ${p.size} bytes, expected $pb" }
            System.arraycopy(p, 0, out, Vcx.HEADER_SIZE + j * pb, pb)
        }
        return out
    }

    fun buildDiscover(seq: Int, timestampUs: Long, pcName: String): ByteArray {
        val name = pcName.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(Vcx.HEADER_SIZE + name.size)
        writeHeader(out, 0, Vcx.TYPE_DISCOVER, 0, seq, timestampUs, 0, 0, 0)
        System.arraycopy(name, 0, out, Vcx.HEADER_SIZE, name.size)
        return out
    }

    fun buildDiscoverReply(seq: Int, timestampUs: Long, audioPort: Int, deviceName: String, flags: Int = 0): ByteArray {
        val name = deviceName.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(Vcx.HEADER_SIZE + 2 + name.size)
        writeHeader(out, 0, Vcx.TYPE_DISCOVER_REPLY, flags, seq, timestampUs, 0, 0, 0)
        putU16(out, Vcx.HEADER_SIZE, audioPort)
        System.arraycopy(name, 0, out, Vcx.HEADER_SIZE + 2, name.size)
        return out
    }

    /** STATS packet: 24-byte header + 56-byte body (PROTOCOL.md). */
    fun buildStats(seq: Int, timestampUs: Long, s: StatsSnapshot): ByteArray {
        val out = ByteArray(Vcx.HEADER_SIZE + Vcx.STATS_BODY_SIZE)
        writeHeader(out, 0, Vcx.TYPE_STATS, 0, seq, timestampUs, 0, 0, 0)
        val b = Vcx.HEADER_SIZE
        putI32(out, b + 0, s.bufferUs)
        putI32(out, b + 4, s.targetUs)
        putI32(out, b + 8, s.received.toInt())
        putI32(out, b + 12, s.lost.toInt())
        putI32(out, b + 16, s.recovered.toInt())
        putI32(out, b + 20, s.concealed.toInt())
        putI32(out, b + 24, s.late.toInt())
        putI32(out, b + 28, s.underruns.toInt())
        putF32(out, b + 32, s.ratePpm)
        putI32(out, b + 36, s.trackFrames)
        putF32(out, b + 40, s.delayP99Ms)
        putF32(out, b + 44, s.delayMaxMs)
        putF32(out, b + 48, s.gapMaxMs)
        putF32(out, b + 52, s.depthMinMs)
        return out
    }

    // --- little-endian helpers ---
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    fun i32(b: ByteArray, o: Int): Int =
        u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)
    fun i64(b: ByteArray, o: Int): Long =
        (i32(b, o).toLong() and 0xFFFF_FFFFL) or (i32(b, o + 4).toLong() shl 32)

    fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte()
    }
    fun putI32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
    fun putF32(b: ByteArray, o: Int, v: Float) = putI32(b, o, java.lang.Float.floatToRawIntBits(v))
    fun f32(b: ByteArray, o: Int): Float = java.lang.Float.intBitsToFloat(i32(b, o))
    fun putI64(b: ByteArray, o: Int, v: Long) {
        putI32(b, o, v.toInt()); putI32(b, o + 4, (v ushr 32).toInt())
    }
}
