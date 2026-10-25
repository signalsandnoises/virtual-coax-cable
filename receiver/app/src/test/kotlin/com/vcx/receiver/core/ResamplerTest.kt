package com.vcx.receiver.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

class ResamplerTest {

    /** Endless stereo source in 120-frame packets; L = generator(n), R = -generator(n). */
    private class GenSource(private val gen: (Long) -> Double) : FrameSource {
        var frame = 0L
        override fun read(out: ByteArray): Int {
            for (f in 0 until 120) {
                val v = gen(frame++).roundToInt().coerceIn(-32768, 32767)
                PacketCodec.putU16(out, f * 4, v)
                PacketCodec.putU16(out, f * 4 + 2, -v)
            }
            return 120
        }
    }

    private fun sample(b: ByteArray, frame: Int, ch: Int) = PacketCodec.u16(b, (frame * 2 + ch) * 2).toShort().toInt()

    @Test
    fun unityRatioIsBitExact() {
        val rnd = java.util.Random(1)
        val input = IntArray(48_000) { rnd.nextInt(65536) - 32768 }
        val src = GenSource { input[(it % input.size).toInt()].toDouble() }
        val r = CubicResampler(src)
        val out = ByteArray(120 * 4)
        val got = IntArray(input.size)
        for (c in 0 until input.size / 120) {
            assertEquals(120, r.read(out))
            for (f in 0 until 120) {
                got[c * 120 + f] = sample(out, f, 0)
                assertEquals((-got[c * 120 + f]).toShort().toInt(), sample(out, f, 1))
            }
        }
        assertArrayEquals(input, got)
    }

    @Test
    fun outputCountMatchesRatioOverLongRun() {
        for (ppm in listOf(500f, -500f, 1000f, 37.5f)) {
            val r = CubicResampler(GenSource { 0.0 })
            r.ratePpm = ppm
            val out = ByteArray(120 * 4)
            val chunks = 48_000 * 100 / 120 // 100 s of output
            repeat(chunks) { r.read(out) }
            val produced = chunks * 120.0
            val consumed = r.inputFramesPulled - r.bufferedFrames() // read position in input frames
            val expected = produced * (1 + ppm * 1e-6)
            assertEquals("ppm=$ppm", expected, consumed, 1.0)
        }
    }

    /** THD+N in dB of a sine through the resampler (residual after a least-squares sine fit). */
    private fun thdDb(freqHz: Double, ppm: Float): Double {
        val amp = 16000.0
        val w = 2 * PI * freqHz / 48_000
        val r = CubicResampler(GenSource { amp * sin(w * it) })
        r.ratePpm = ppm
        val out = ByteArray(120 * 4)
        repeat(100) { r.read(out) } // settle
        val n = 48_000 * 2
        val y = DoubleArray(n)
        for (c in 0 until n / 120) {
            r.read(out)
            for (f in 0 until 120) y[c * 120 + f] = sample(out, f, 0).toDouble()
        }
        // Output frequency per output sample is w * step.
        val wo = w * (1 + ppm * 1e-6)
        // Least squares for a*sin + b*cos + c (3x3 normal equations).
        val m = Array(3) { DoubleArray(3) }
        val v = DoubleArray(3)
        for (i in 0 until n) {
            val basis = doubleArrayOf(sin(wo * i), cos(wo * i), 1.0)
            for (p in 0 until 3) {
                v[p] += basis[p] * y[i]
                for (q in 0 until 3) m[p][q] += basis[p] * basis[q]
            }
        }
        val coef = solve3(m, v)
        var sig = 0.0
        var res = 0.0
        for (i in 0 until n) {
            val fit = coef[0] * sin(wo * i) + coef[1] * cos(wo * i) + coef[2]
            sig += fit * fit
            res += (y[i] - fit) * (y[i] - fit)
        }
        return 10 * log10(res / sig)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (c in 0 until 3) {
            val p = (c until 3).maxBy { kotlin.math.abs(m[it][c]) }
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until 3) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c until 4) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    @Test
    fun sineStaysCleanAtPlus500ppm() {
        val thd1k = thdDb(1000.0, 500f)
        val thd1kNeg = thdDb(1000.0, -500f)
        val thd5k = thdDb(5000.0, 500f)
        val thd1kUnity = thdDb(1000.0, 0f)
        println("THD+N 1 kHz +500 ppm = %.1f dB, -500 ppm = %.1f dB, 5 kHz +500 ppm = %.1f dB, 1 kHz 0 ppm = %.1f dB"
            .format(thd1k, thd1kNeg, thd5k, thd1kUnity))
        assertTrue("THD+N at 1 kHz +500 ppm = $thd1k dB", thd1k < -60)
        assertTrue("THD+N at 1 kHz -500 ppm = $thd1kNeg dB", thd1kNeg < -60)
    }

    @Test
    fun fractionalPhaseContinuesAcrossPackets() {
        // A slow ramp must come out monotonic and without steps at packet boundaries.
        val r = CubicResampler(GenSource { it * 5.0 - 12_000 })
        r.ratePpm = 1000f
        val out = ByteArray(120 * 4)
        r.read(out) // first chunk sees the zero history frame
        var prev = Int.MIN_VALUE
        repeat(40) {
            r.read(out)
            for (f in 0 until 120) {
                val s = sample(out, f, 0)
                if (prev != Int.MIN_VALUE) assertEquals(5.005, (s - prev).toDouble(), 1.01)
                prev = s
            }
        }
    }
}
