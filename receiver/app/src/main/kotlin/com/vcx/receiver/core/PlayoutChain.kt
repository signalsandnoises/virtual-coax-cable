package com.vcx.receiver.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * JitterBuffer -> CubicResampler, plus start-up alignment, depth steering, silence-aware trim and
 * jump diagnostics. The playout thread calls read() once per AudioTrack write; each call is one sample.
 *
 * Two quantities are sampled after every read:
 *  - depth: frames queued in the jitter buffer (gaps and pending inserts included) + frames held in
 *    the resampler. Reported as buf_ms / depth_min_ms.
 *  - lead: the depth the buffer would have if every packet arrived at the best delay seen in the
 *    last 10 s (d_min from [delay]): depth + (now - bestCaseArrival(highest seq)). Delay excursions
 *    (Wi-Fi stalls, power-save bursts, slow phases) lower depth but leave lead unchanged, so the
 *    buffer absorbs them instead of the controller chasing them. Lead is what we steer to target.
 *    Without [delay]/[clockUs] (unit tests), lead = depth.
 *  - gap = lead - depth (age of the newest packet + its delay above d_min). Its p30 over 10 s is the
 *    normal arrival spread (e.g. the sender's 4-packet bursts per 10 ms capture chunk). The steering
 *    variable is lead - p30(gap), so mean depth sits on target in normal operation, while excursions
 *    covering < 70 % of the last 10 s do not move it. When d_min rises (old minimum leaves the window)
 *    the gap history is rebased so the same delay shift is not corrected twice.
 *
 * Why lead and not depth: depth = latency - current arrival delay. Every time the delay floor moves
 * (Wi-Fi leaving a slow/power-save phase via a stall + burst, sender switching between silence fill
 * and capture), depth steps by tens of ms with no latency change, and a depth controller saturates
 * chasing it. Lead only moves with real playout-vs-sender drift, or when d_min itself changes.
 *
 * Filter: interquartile mean of the last 1 s of samples (robust to bursts like a median, keeps
 * sub-packet resolution unlike a median).
 *
 * Per (re-)prime: ALIGNING for [alignWaitMs], then one whole-packet drop/insert to put lead on
 * target (VCX-ALIGN); then RUNNING: every [updateMs] the PI controller sets the resampler ratio
 * (steer), silent packets are dropped/held when lead is > 5 ms off target (siltrim), and a lead rise
 * of > 5 ms within 500 ms is logged as VCX-JUMP.
 *
 * Fast cut: steering drains only 1 ms/s, so a Wi-Fi stall that leaves lead 80 ms high during music
 * (no silence to trim) would take over a minute to recover. When lead stays more than
 * [FAST_CUT_EXCESS_MS] above target for [FAST_CUT_HOLD_MS] (siltrim gets that long first), the
 * excess beyond [FAST_CUT_EXCESS_MS] is cut in one crossfade ([JitterBuffer.requestCut]); steering
 * drains the rest.
 */
class PlayoutChain(
    val jitter: JitterBuffer,
    val steer: Boolean = true,
    val align: Boolean = true,
    val silTrim: Boolean = true,
    val fastCut: Boolean = true,
    private val delay: DelayStats? = null,
    private val clockUs: UsClock? = null,
    private val controller: DepthController = DepthController(),
    private val alignWaitMs: Int = 300,
    private val updateMs: Int = 100,
    private val log: (String) -> Unit = {},
) : FrameSource {

    val resampler = CubicResampler(jitter)
    override val ratePpm: Float get() = resampler.ratePpm

    private enum class State { IDLE, ALIGNING, RUNNING }
    private var state = State.IDLE
    private var epoch = -1
    private val targetFrames = jitter.targetMs * Vcx.SAMPLE_RATE / 1000.0

    private val leadWin = Window()
    private val depthWin = Window()
    private val gapHist = GapHistory()
    private var lastDminUs = Double.NaN
    private var gapRefFrames = 0.0
    private var editAccFrames = 0.0
    private var overSinceFrames = -1L
    private var readsSinceSilCheck = 0
    private var framesInState = 0L
    private var framesSinceUpdate = 0L
    private var totalFrames = 0L

    // Jump detection: snapshots every update (100 ms), 6 deep = 500 ms back.
    private class Snap(var lead: Double = 0.0, var depth: Double = 0.0, var received: Long = 0, var concealed: Long = 0,
                       var late: Long = 0, var highest: Int = 0, var dMinUs: Double = Double.NaN, var edits: Double = 0.0,
                       var valid: Boolean = false)
    private val snaps = Array(6) { Snap() }
    private var snapIdx = 0
    private var lastJumpLogFrames = Long.MIN_VALUE / 2
    private var lastSilLogFrames = Long.MIN_VALUE / 2
    private var silLogDropped = 0
    private var silLogInserted = 0
    private var lastHighest = 0

    /** Last sampled depth / lead, frames (for stats; written by the playout thread). */
    @Volatile var lastDepthFrames = 0.0
        private set
    @Volatile var lastLeadFrames = 0.0
        private set
    @Volatile var lastFilteredDepthFrames = 0.0
        private set
    @Volatile var lastFilteredLeadFrames = 0.0
        private set
    private var depthMinFrames = Double.MAX_VALUE
    private val minLock = Any()
    private val jumpCounters = LongArray(3)

    /** One playout write of the default 120 frames. */
    override fun read(out: ByteArray): Int = read(out, Vcx.DEFAULT_FRAMES)

    /**
     * One playout write: produces exactly [frames] output frames (any size; the jitter buffer stays
     * in packets), or 0 while not primed. Allocation-free.
     */
    fun read(out: ByteArray, frames: Int): Int {
        val n = resampler.read(out, frames)
        if (n == 0) {
            if (state != State.IDLE) {
                state = State.IDLE
                resampler.ratePpm = 0f
                controller.reset()
                jitter.setSilenceBudget(0, 0)
            }
            lastDepthFrames = jitter.queuedFrames().toDouble()
            lastLeadFrames = lastDepthFrames
            return 0
        }
        totalFrames += n
        val ep = jitter.epoch()
        if (state == State.IDLE || ep != epoch) {
            epoch = ep
            enterPrimed()
        }
        // Silence edits change depth and lead by whole packets: restart the windows so the filters
        // only see post-edit samples (otherwise the next check would trim again).
        val edits = jitter.pollSilenceEdits()
        if (edits != 0) {
            leadWin.reset(); depthWin.reset()
            editAccFrames += edits.toDouble() * jitter.framesPerPacket
            if (edits < 0) silLogDropped -= edits else silLogInserted += edits
        }
        val cut = jitter.pollCutEdits()
        if (cut != 0) {
            leadWin.reset(); depthWin.reset()
            editAccFrames -= cut.toDouble() * jitter.framesPerPacket
            overSinceFrames = -1
        }

        sample(n)
        framesInState += n
        when (state) {
            State.ALIGNING -> if (framesInState >= alignWaitMs * Vcx.SAMPLE_RATE / 1000) doAlign()
            State.RUNNING -> {
                if (silTrim && ++readsSinceSilCheck >= SIL_CHECK_READS && leadWin.count >= SIL_SHORT_SAMPLES) {
                    readsSinceSilCheck = 0
                    silenceCheck()
                }
                framesSinceUpdate += n
                val updateFrames = updateMs * Vcx.SAMPLE_RATE / 1000
                if (framesSinceUpdate >= updateFrames && leadWin.count >= MIN_SAMPLES) {
                    val dt = framesSinceUpdate.toDouble() / Vcx.SAMPLE_RATE
                    framesSinceUpdate = 0
                    update(dt)
                }
            }
            State.IDLE -> {}
        }
        return n
    }

    private fun sample(frames: Int) {
        val st = jitter.leadState()
        val raw = (st shr 32).toInt()
        val highest = st.toInt()
        lastHighest = highest
        val res = resampler.bufferedFrames()
        val depth = raw.coerceAtLeast(0) + res
        var lead = depth
        if (delay != null && clockUs != null) {
            val now = clockUs.nowUs()
            val e = delay.expectedArrivalUs(highest, now)
            if (!e.isNaN()) {
                lead = raw + res + (now - e) * Vcx.SAMPLE_RATE / 1e6
                val dMin = delay.dMinUs()
                if (!lastDminUs.isNaN() && dMin > lastDminUs) {
                    // Old minimum left the window: rebase everything measured against it.
                    val d = -(dMin - lastDminUs) * Vcx.SAMPLE_RATE / 1e6
                    gapHist.shift(d); leadWin.shift(d); gapRefFrames += d
                }
                lastDminUs = dMin
                gapHist.offer(lead - depth, frames)
            }
        }
        lastDepthFrames = depth
        lastLeadFrames = lead
        depthWin.add(depth)
        leadWin.add(lead)
        synchronized(minLock) { if (depth < depthMinFrames) depthMinFrames = depth }
    }

    private fun gapRef(): Double = if (gapHist.count >= MIN_GAP_SAMPLES) gapHist.percentile(GAP_PERCENTILE) else 0.0

    private fun update(dt: Double) {
        gapRefFrames = gapRef()
        val lead = leadWin.iqm() - gapRefFrames
        val depth = depthWin.iqm()
        lastFilteredLeadFrames = lead
        lastFilteredDepthFrames = depth
        checkJump(lead - editAccFrames, depth)

        val errFrames = lead - targetFrames
        val errMs = errFrames * 1000.0 / Vcx.SAMPLE_RATE
        if (steer) resampler.ratePpm = controller.update(errMs, dt).toFloat()
        if (fastCut) fastCutCheck(errFrames)
        if ((silLogDropped > 0 || silLogInserted > 0) && totalFrames - lastSilLogFrames >= Vcx.SAMPLE_RATE) {
            val parts = ArrayList<String>()
            if (silLogDropped > 0) parts += "dropped=$silLogDropped"
            if (silLogInserted > 0) parts += "inserted=$silLogInserted"
            log("VCX-SILTRIM ${parts.joinToString(" ")} lead_ms=${ms(lead)} target_ms=${jitter.targetMs}")
            silLogDropped = 0
            silLogInserted = 0
            lastSilLogFrames = totalFrames
        }
    }

    private fun fastCutCheck(errFrames: Double) {
        val excess = errFrames - FAST_CUT_EXCESS_MS * Vcx.SAMPLE_RATE / 1000.0
        if (excess <= 0) { overSinceFrames = -1; return }
        if (overSinceFrames < 0) { overSinceFrames = totalFrames; return }
        if (totalFrames - overSinceFrames < FAST_CUT_HOLD_MS * Vcx.SAMPLE_RATE / 1000 || jitter.cutPending()) return
        val packets = (excess / jitter.framesPerPacket).toInt()
        if (packets > 0) {
            log("VCX-FASTCUT lead_ms=${ms(errFrames + targetFrames)} target_ms=${jitter.targetMs} packets=$packets")
            jitter.requestCut(packets)
        }
        overSinceFrames = -1
    }

    /** Fast silence-trim decision on the last 20 ms of lead (lead is smooth; bursts do not move it). */
    private fun silenceCheck() {
        val errFrames = leadWin.iqmRecent(SIL_SHORT_SAMPLES) - gapRefFrames - targetFrames
        val errMs = errFrames * 1000.0 / Vcx.SAMPLE_RATE
        val f = jitter.framesPerPacket
        when {
            errMs > SIL_THRESHOLD_MS -> jitter.setSilenceBudget((errFrames / f).roundToInt(), 0)
            errMs < -SIL_THRESHOLD_MS -> jitter.setSilenceBudget(0, (-errFrames / f).roundToInt())
            else -> jitter.setSilenceBudget(0, 0)
        }
    }

    /** [lead] here excludes silence edits, so a jump that siltrim corrected is still reported. */
    private fun checkJump(lead: Double, depth: Double) {
        val c = jumpCounters
        jitter.fillJumpCounters(c)
        val dMin = if (delay != null) delay.dMinUs() else Double.NaN
        // Oldest valid snapshot within the last 500 ms and the minimum lead over them.
        var minLead = Double.MAX_VALUE
        var oldest: Snap? = null
        for (k in 1..snaps.size) {
            val s = snaps[(snapIdx - k + snaps.size * 2) % snaps.size]
            if (!s.valid) break
            if (s.lead < minLead) minLead = s.lead
            oldest = s
        }
        if (oldest != null && lead - minLead > JUMP_FRAMES && totalFrames - lastJumpLogFrames >= Vcx.SAMPLE_RATE) {
            val shift = if (dMin.isNaN() || oldest.dMinUs.isNaN()) "na" else
                String.format(Locale.US, "%.1f", (dMin - oldest.dMinUs) / 1000.0)
            log(
                "VCX-JUMP lead_ms=${ms(minLead)}->${ms(lead)} depth_ms=${ms(oldest.depth)}->${ms(depth)} " +
                    "seq=${Seq.toUnsigned(oldest.highest + 1)}..${Seq.toUnsigned(lastHighest)} " +
                    "arrived=${c[0] - oldest.received} expected=${500 * Vcx.SAMPLE_RATE / 1000 / maxOf(1, jitter.framesPerPacket)} " +
                    "concealed=${c[1] - oldest.concealed} late=${c[2] - oldest.late} dmin_shift_ms=$shift " +
                    "sil_edits=${((editAccFrames - oldest.edits) / maxOf(1, jitter.framesPerPacket)).roundToInt()}",
            )
            lastJumpLogFrames = totalFrames
        }
        val s = snaps[snapIdx]
        s.lead = lead; s.depth = depth; s.received = c[0]; s.concealed = c[1]; s.late = c[2]
        s.highest = lastHighest; s.dMinUs = dMin; s.edits = editAccFrames; s.valid = true
        snapIdx = (snapIdx + 1) % snaps.size
    }

    /** Minimum sampled depth since the last call, in ms. Called by the stats thread. */
    fun takeDepthMinMs(): Float = synchronized(minLock) {
        val m = depthMinFrames
        depthMinFrames = Double.MAX_VALUE
        if (m == Double.MAX_VALUE) (lastDepthFrames * 1000.0 / Vcx.SAMPLE_RATE).toFloat()
        else (m * 1000.0 / Vcx.SAMPLE_RATE).toFloat()
    }

    fun depthUs(): Int = (lastDepthFrames * 1_000_000.0 / Vcx.SAMPLE_RATE).toInt()

    fun leadMs(): Double = lastFilteredLeadFrames * 1000.0 / Vcx.SAMPLE_RATE

    private fun enterPrimed() {
        leadWin.reset(); depthWin.reset(); gapHist.reset()
        lastDminUs = Double.NaN
        gapRefFrames = 0.0
        editAccFrames = 0.0
        overSinceFrames = -1
        jitter.requestCut(0)
        for (s in snaps) s.valid = false
        controller.reset()
        resampler.ratePpm = 0f
        jitter.setSilenceBudget(0, 0)
        framesInState = 0
        framesSinceUpdate = 0
        state = if (align) State.ALIGNING else State.RUNNING
    }

    private fun doAlign() {
        val f = jitter.framesPerPacket
        gapRefFrames = gapRef()
        val measured = leadWin.iqm() - gapRefFrames
        val packets = ((measured - targetFrames) / f).roundToInt()
        val action: String
        val done: Int
        when {
            packets > 0 -> { done = jitter.dropOldest(packets); action = "drop" }
            packets < 0 -> { jitter.insertSilence(-packets); done = -packets; action = "insert" }
            else -> { done = 0; action = "none" }
        }
        log(
            String.format(
                Locale.US, "VCX-ALIGN lead_ms=%.1f depth_ms=%.1f target_ms=%d action=%s packets=%d (%.1f ms)",
                measured * 1000.0 / Vcx.SAMPLE_RATE, depthWin.iqm() * 1000.0 / Vcx.SAMPLE_RATE, jitter.targetMs,
                action, done, done * f * 1000.0 / Vcx.SAMPLE_RATE,
            ),
        )
        leadWin.reset(); depthWin.reset()
        framesInState = 0
        framesSinceUpdate = 0
        state = State.RUNNING
    }

    /** IQM of the lead window (frames). */
    fun filteredDepth(): Double = leadWin.iqm()

    private fun ms(frames: Double) = String.format(Locale.US, "%.1f", frames * 1000.0 / Vcx.SAMPLE_RATE)

    private class Window {
        private val v = DoubleArray(WINDOW_SAMPLES)
        private val sortBuf = DoubleArray(WINDOW_SAMPLES)
        var count = 0
            private set
        private var idx = 0
        fun add(x: Double) {
            v[idx] = x
            idx = (idx + 1) % WINDOW_SAMPLES
            if (count < WINDOW_SAMPLES) count++
        }
        fun reset() { count = 0; idx = 0 }
        fun shift(d: Double) {
            for (i in 0 until count) v[(idx - 1 - i + WINDOW_SAMPLES * 2) % WINDOW_SAMPLES] += d
        }
        fun iqm(): Double = iqmRecent(count)

        /** IQM of the most recent [n] samples. */
        fun iqmRecent(n: Int): Double {
            val m = minOf(n, count)
            if (m == 0) return 0.0
            for (i in 0 until m) sortBuf[i] = v[(idx - 1 - i + WINDOW_SAMPLES * 2) % WINDOW_SAMPLES]
            DoubleSort.sort(sortBuf, m)
            val lo = m / 4
            val hi = maxOf(lo + 1, m - m / 4)
            var s = 0.0
            for (i in lo until hi) s += sortBuf[i]
            return s / (hi - lo)
        }
    }

    /**
     * 10 s of gap samples, each the frame-weighted mean over [AVG_FRAMES] of output (20 ms), whatever
     * the write size. Averaging (not plain decimation) avoids aliasing with the sender's packet
     * bursts (4 packets per 10 ms capture chunk).
     */
    private class GapHistory {
        private val v = DoubleArray(500)
        private val sortBuf = DoubleArray(500)
        var count = 0
            private set
        private var idx = 0
        private var acc = 0.0
        private var accN = 0
        fun offer(x: Double, frames: Int) {
            acc += x * frames
            accN += frames
            if (accN < AVG_FRAMES) return
            val m = acc / accN
            acc = 0.0; accN = 0
            v[idx] = m
            idx = (idx + 1) % v.size
            if (count < v.size) count++
        }
        fun shift(d: Double) {
            for (i in 0 until count) v[(idx - 1 - i + v.size * 2) % v.size] += d
            acc += d * accN
        }
        fun reset() { count = 0; idx = 0; acc = 0.0; accN = 0 }
        fun percentile(p: Double): Double {
            if (count == 0) return 0.0
            for (i in 0 until count) sortBuf[i] = v[(idx - 1 - i + v.size * 2) % v.size]
            DoubleSort.sort(sortBuf, count)
            return sortBuf[((count - 1) * p).roundToInt()]
        }
        companion object { const val AVG_FRAMES = 960 }
    }

    companion object {
        const val MIN_GAP_SAMPLES = 10
        const val GAP_PERCENTILE = 0.3
        const val SIL_CHECK_READS = 2
        const val SIL_SHORT_SAMPLES = 8
        /** 1 s of samples at one sample per 120-frame write (1.6 s at 192-frame writes). */
        const val WINDOW_SAMPLES = 400
        const val MIN_SAMPLES = 40
        const val SIL_THRESHOLD_MS = 5.0
        const val FAST_CUT_EXCESS_MS = 40
        const val FAST_CUT_HOLD_MS = 500
        const val JUMP_FRAMES = 5.0 * Vcx.SAMPLE_RATE / 1000
    }
}
