package com.vcx.receiver.core

/**
 * Arrival-delay diagnostics and the best-case arrival reference used for steering.
 *
 * For each arrival d = arrival_us - seq_unwrapped * frames / 48 kHz. d_min is the minimum over a
 * sliding window of [windowSec] one-second buckets (bucketed by arrival time), so slow clock drift
 * does not accumulate. Per second we report p99 and max of (d - d_min), and the max inter-arrival gap
 * (including a gap still open at report time).
 *
 * [expectedArrivalUs] gives the time a packet would arrive at the best delay seen in the window,
 * which lets the playout chain measure its lead independently of current delay excursions.
 *
 * onArrival() is called by the receive thread, takeSecond() by the stats thread, expectedArrivalUs()
 * and dMinUs() by the playout thread.
 */
class DelayStats(private val windowSec: Int = 10) {

    data class Second(val delayP99Ms: Float, val delayMaxMs: Float, val gapMaxMs: Float)

    private var hasLast = false
    private var lastSeq = 0
    private var unwrapped = 0L
    private var frames = 0
    private var lastArrivalUs = -1L

    private val bucketMin = DoubleArray(windowSec) { Double.POSITIVE_INFINITY }
    private var curSec = Long.MIN_VALUE

    private var rel = FloatArray(1024)
    private var relCount = 0
    private var gapMaxUs = 0L

    @Synchronized
    fun onArrival(seq: Int, framesPerPacket: Int, arrivalUs: Long) {
        advanceTo(arrivalUs)
        if (!hasLast || framesPerPacket != frames) {
            resetTimeline(seq, framesPerPacket)
        } else {
            val d = Seq.diff(seq, lastSeq)
            if (d > RESYNC || d < -RESYNC) resetTimeline(seq, framesPerPacket) else unwrapped += d
            lastSeq = seq
        }
        val d = arrivalUs - unwrapped * frames * 1_000_000.0 / Vcx.SAMPLE_RATE
        val b = Math.floorMod(curSec, windowSec.toLong()).toInt()
        if (d < bucketMin[b]) bucketMin[b] = d
        val dMin = minBucket()
        if (relCount == rel.size) rel = rel.copyOf(rel.size * 2)
        rel[relCount++] = ((d - dMin) / 1000.0).toFloat()

        if (lastArrivalUs >= 0) {
            val gap = arrivalUs - lastArrivalUs
            if (gap > gapMaxUs) gapMaxUs = gap
        }
        lastArrivalUs = arrivalUs
    }

    @Synchronized
    fun takeSecond(nowUs: Long): Second {
        var p99 = 0f
        var max = 0f
        if (relCount > 0) {
            java.util.Arrays.sort(rel, 0, relCount)
            val idx = (Math.ceil(0.99 * relCount).toInt() - 1).coerceIn(0, relCount - 1)
            p99 = rel[idx]
            max = rel[relCount - 1]
        }
        var gap = gapMaxUs
        if (lastArrivalUs >= 0 && nowUs - lastArrivalUs > gap) gap = nowUs - lastArrivalUs
        relCount = 0
        gapMaxUs = 0
        return Second(p99, max, gap / 1000f)
    }

    /**
     * Arrival time (µs, same clock as onArrival) that [seq] has at the window's best delay d_min,
     * or NaN if there is no reference yet (no arrivals in the window).
     */
    @Synchronized
    fun expectedArrivalUs(seq: Int, nowUs: Long): Double {
        advanceTo(nowUs)
        if (!hasLast) return Double.NaN
        val dMin = minBucket()
        if (dMin == Double.POSITIVE_INFINITY) return Double.NaN
        val u = unwrapped + Seq.diff(seq, lastSeq)
        return u * frames * 1_000_000.0 / Vcx.SAMPLE_RATE + dMin
    }

    /** Current d_min in µs (NaN if none). For diagnostics. */
    @Synchronized
    fun dMinUs(): Double {
        val m = minBucket()
        return if (m == Double.POSITIVE_INFINITY) Double.NaN else m
    }

    private fun minBucket(): Double {
        var m = Double.POSITIVE_INFINITY
        for (v in bucketMin) if (v < m) m = v
        return m
    }

    private fun advanceTo(us: Long) {
        val sec = Math.floorDiv(us, 1_000_000L)
        if (curSec == Long.MIN_VALUE) { curSec = sec; return }
        if (sec <= curSec) return
        val steps = minOf(sec - curSec, windowSec.toLong())
        for (q in 1..steps) {
            bucketMin[Math.floorMod(curSec + q, windowSec.toLong()).toInt()] = Double.POSITIVE_INFINITY
        }
        curSec = sec
    }

    private fun resetTimeline(seq: Int, framesPerPacket: Int) {
        hasLast = true
        lastSeq = seq
        unwrapped = 0
        frames = framesPerPacket
        java.util.Arrays.fill(bucketMin, Double.POSITIVE_INFINITY)
    }

    private companion object {
        const val RESYNC = 2048
    }
}
