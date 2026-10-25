package com.vcx.receiver

/**
 * Per-second playout-path counters. record()/markUnderrun() run on the playout thread for every
 * write and never allocate; take()/takeUnderrun() run on the stats thread once a second.
 *
 * q = frames queued in the AudioTrack: frames written - playbackHeadPosition, sampled just before a
 * write (the low point, starvation risk) and just after it (the high point). qts = frames written -
 * the AudioTimestamp position extrapolated to now (includes the HAL/DSP pipeline).
 */
class PlayStats {
    private var writes = 0L
    private var maxGapNs = 0L
    private var maxBlockNs = 0L
    private var shortWrites = 0L
    private var qMin = Int.MAX_VALUE
    private var qMax = Int.MIN_VALUE
    private var qtsMin = Int.MAX_VALUE
    private var qtsMax = Int.MIN_VALUE

    // First underrun of the current second, with the per-second values up to that moment.
    private var pendingUnderruns = 0
    private var uTotal = 0
    private var uDepthFrames = 0.0
    private var uLeadFrames = 0.0
    private val uVals = LongArray(8)

    /** Snapshot layout for take()/takeUnderrun(): writes, gapNs, blockNs, short, qMin, qMax, qtsMin, qtsMax. */
    val snap = LongArray(8)

    @Synchronized
    fun record(gapNs: Long, blockNs: Long, short: Boolean, qBefore: Int, qAfter: Int, qts: Int) {
        writes++
        if (gapNs > maxGapNs) maxGapNs = gapNs
        if (blockNs > maxBlockNs) maxBlockNs = blockNs
        if (short) shortWrites++
        if (qBefore < qMin) qMin = qBefore
        if (qAfter > qMax) qMax = qAfter
        if (qts != Int.MIN_VALUE) {
            if (qts < qtsMin) qtsMin = qts
            if (qts > qtsMax) qtsMax = qts
        }
    }

    @Synchronized
    fun markUnderrun(delta: Int, total: Int, depthFrames: Double, leadFrames: Double) {
        if (pendingUnderruns == 0) {
            fill(uVals)
            uDepthFrames = depthFrames
            uLeadFrames = leadFrames
        }
        pendingUnderruns += delta
        uTotal = total
    }

    /** Copies this second's values into [snap] and resets them. */
    @Synchronized
    fun take() {
        fill(snap)
        writes = 0; maxGapNs = 0; maxBlockNs = 0; shortWrites = 0
        qMin = Int.MAX_VALUE; qMax = Int.MIN_VALUE; qtsMin = Int.MAX_VALUE; qtsMax = Int.MIN_VALUE
    }

    /** If an underrun happened since the last call, copies its snapshot into [out] and returns the count. */
    @Synchronized
    fun takeUnderrun(out: LongArray, depthLead: DoubleArray, total: IntArray): Int {
        val n = pendingUnderruns
        if (n == 0) return 0
        System.arraycopy(uVals, 0, out, 0, uVals.size)
        depthLead[0] = uDepthFrames; depthLead[1] = uLeadFrames
        total[0] = uTotal
        pendingUnderruns = 0
        return n
    }

    private fun fill(a: LongArray) {
        a[0] = writes; a[1] = maxGapNs; a[2] = maxBlockNs; a[3] = shortWrites
        a[4] = if (qMin == Int.MAX_VALUE) -1 else qMin.toLong()
        a[5] = if (qMax == Int.MIN_VALUE) -1 else qMax.toLong()
        a[6] = if (qtsMin == Int.MAX_VALUE) -1 else qtsMin.toLong()
        a[7] = if (qtsMax == Int.MIN_VALUE) -1 else qtsMax.toLong()
    }
}
