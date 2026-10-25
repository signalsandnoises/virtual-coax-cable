package com.vcx.receiver.core

/**
 * Variable-ratio resampler, 4-point cubic Hermite (Catmull-Rom), interleaved stereo s16le.
 *
 * Pulls packets from [source] and always produces [chunkFrames] output frames per read() (or 0 when
 * the source has nothing yet). The fractional read position is kept across packets.
 *
 * Ratio convention: [ratePpm] > 0 consumes input faster than nominal (step = 1 + ppm * 1e-6 input
 * frames per output frame), which drains the jitter buffer and raises pitch by that many ppm.
 * At ratePpm == 0 the position stays integral and the output is a bit-exact copy of the input
 * (delayed by the 2-frame lookahead the interpolator needs).
 */
class CubicResampler(
    private val source: FrameSource,
    private val chunkFrames: Int = Vcx.DEFAULT_FRAMES,
) : FrameSource {

    @Volatile
    override var ratePpm: Float = 0f

    private var buf = FloatArray(2 * 4096) // interleaved L R; frame 0 is history
    private var count = 0                  // frames in buf
    private var pos = 1.0                  // position of the next output frame, in buf frames
    private var active = false
    private val scratch = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * Vcx.BYTES_PER_FRAME)

    /** Total input frames pulled from the source (for tests/diagnostics). */
    var inputFramesPulled = 0L
        private set

    /** Input frames held here and not yet consumed (fractional). Counts toward buffer depth. */
    fun bufferedFrames(): Double = if (active) (count - pos).coerceAtLeast(0.0) else 0.0

    fun reset() {
        active = false
        count = 0
        pos = 1.0
    }

    override fun read(out: ByteArray): Int {
        val step = 1.0 + ratePpm * 1e-6
        var produced = 0
        while (produced < chunkFrames) {
            val i = pos.toInt()
            if (i + 2 >= count) {
                if (!pull()) break
                continue
            }
            val mu = (pos - i).toFloat()
            val b = (i - 1) * 2
            for (ch in 0 until 2) {
                val xm1 = buf[b + ch]
                val x0 = buf[b + 2 + ch]
                val x1 = buf[b + 4 + ch]
                val x2 = buf[b + 6 + ch]
                val y = x0 + 0.5f * mu * (x1 - xm1 + mu * (2f * xm1 - 5f * x0 + 4f * x1 - x2 +
                    mu * (3f * (x0 - x1) + x2 - xm1)))
                val v = Math.round(y).coerceIn(-32768, 32767)
                val o = (produced * 2 + ch) * 2
                out[o] = v.toByte()
                out[o + 1] = (v shr 8).toByte()
            }
            produced++
            pos += step
        }
        if (produced == 0) return 0
        if (produced < chunkFrames) {
            // Source ran dry mid-chunk (rebuffer): pad with silence.
            java.util.Arrays.fill(out, produced * Vcx.BYTES_PER_FRAME, chunkFrames * Vcx.BYTES_PER_FRAME, 0)
        }
        compact()
        return chunkFrames
    }

    private fun pull(): Boolean {
        val n = source.read(scratch)
        if (n <= 0) {
            if (active) reset()
            return false
        }
        if (!active) {
            active = true
            count = 1
            pos = 1.0
            buf[0] = 0f; buf[1] = 0f
        }
        if ((count + n) * 2 > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, (count + n) * 2))
        var o = count * 2
        for (s in 0 until n * 2) {
            buf[o++] = ((scratch[s * 2].toInt() and 0xFF) or (scratch[s * 2 + 1].toInt() shl 8)).toShort().toFloat()
        }
        count += n
        inputFramesPulled += n
        return true
    }

    /** Keep one history frame before the read position. */
    private fun compact() {
        if (!active) return
        val drop = pos.toInt() - 1
        if (drop > 0) {
            System.arraycopy(buf, drop * 2, buf, 0, (count - drop) * 2)
            count -= drop
            pos -= drop
        }
    }
}
