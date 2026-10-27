package com.vcx.receiver.core

/**
 * Jitter buffer keyed by u32 seq (wrap-safe). Produces one packet per read(); latency is held by
 * [PlayoutChain] (start-up alignment + resampling), not here.
 *
 * - push() is called by the receive thread, read() by the playout thread; both are synchronized.
 * - Depth = slots from the next playout seq up to the highest received seq (gaps count, since they
 *   will be played as fill) plus pending inserted packets, times frames per packet.
 * - Priming: read() returns 0 (caller plays silence) until depth >= target. Every primed read()
 *   plays exactly one slot and advances (conceal-and-advance when the slot is empty).
 * - Missing slot: redundant copy if one arrived (recovered); else the last good packet repeated with
 *   a fade-out to zero (lost + concealed), silence for further missing slots, and a fade-in on the
 *   first real packet after any gap. K (redundant payloads) is read per packet.
 * - Packet for an already played slot: late, discarded.
 * - Whole packets are only dropped/inserted by [dropOldest]/[insertSilence] (start-up alignment),
 *   by [requestCut] (a crossfade from the next packet into the one n later; depth spikes during
 *   music) and by the pathological hard cap (depth > target + [capExcessMs]: trim to target, logged).
 * - Concealing past all received data for [stallMs] in a row (sender gone): stop and re-prime on
 *   the next packet (logged as rebuffer).
 * - A seq jump of more than capacity/2 (sender restarted): resync, i.e. re-prime from that packet.
 *
 * Each (re-)prime starts a new [epoch] so the playout chain knows to re-align.
 * Counters are cumulative for the lifetime of this object (= one service START).
 */
class JitterBuffer(
    val targetMs: Int,
    private val capacity: Int = 4096,
    private val capExcessMs: Int = 500,
    private val stallMs: Int = 1000,
    private val fades: Boolean = true,
    private val log: (String) -> Unit = {},
) : FrameSource {

    init {
        require(capacity >= 64 && (capacity and (capacity - 1)) == 0) { "capacity must be a power of two >= 64" }
        require(targetMs > 0)
    }

    private val mask = capacity - 1
    private val slotSeq = IntArray(capacity)
    private val slotState = ByteArray(capacity)
    private val slotSilent = BooleanArray(capacity)
    private var slots: Array<ByteArray> = emptyArray()
    private var lastGood = ByteArray(0)

    var framesPerPacket = 0
        @Synchronized get
        private set
    private var payloadBytes = 0
    private var targetPackets = 0
    private var capPackets = 0
    private var stallPackets = 0

    private var started = false
    private var primed = false
    private var nextSeq = 0
    private var highest = 0
    private var emptyRun = 0
    private var pendingInserts = 0
    private var lastWasGood = false
    private var fadeInNext = false
    private var epoch = 0
    private var silDropBudget = 0
    private var silInsertBudget = 0
    private var silNetEdits = 0
    private var cutPending = 0
    private var cutNetEdits = 0

    // Cumulative counters.
    private var received = 0L
    private var lost = 0L
    private var recovered = 0L
    private var concealed = 0L
    private var late = 0L
    private var duplicates = 0L
    private var caps = 0L
    private var cappedPackets = 0L
    private var alignDropped = 0L
    private var alignInserted = 0L
    private var rebuffers = 0L
    private var resyncs = 0L
    private var silDropped = 0L
    private var silInserted = 0L
    private var cuts = 0L
    private var cutPackets = 0L
    private var maxK = 0

    data class Counters(
        val received: Long, val lost: Long, val recovered: Long, val concealed: Long, val late: Long,
        val duplicates: Long, val caps: Long, val cappedPackets: Long, val alignDropped: Long,
        val alignInserted: Long, val rebuffers: Long, val resyncs: Long, val maxK: Int,
        val silDropped: Long, val silInserted: Long, val cuts: Long, val cutPackets: Long,
        val depthUs: Int, val targetUs: Int, val primed: Boolean, val framesPerPacket: Int,
    )

    @Synchronized
    fun push(pkt: AudioPacket) {
        received++
        if (pkt.k > maxK) maxK = pkt.k
        if (pkt.frames != framesPerPacket) configure(pkt.frames)
        if (!started) begin(pkt.seq)

        var d = Seq.diff(pkt.seq, nextSeq)
        if (d > capacity / 2 || d < -capacity / 2) {
            log("VCX-RESYNC seq=${Seq.toUnsigned(pkt.seq)} expected=${Seq.toUnsigned(nextSeq)} (stream restart?)")
            resyncs++
            begin(pkt.seq)
            d = 0
        }
        if (d < 0) {
            if (!primed && Seq.diff(highest, pkt.seq) < capacity / 2) {
                nextSeq = pkt.seq // reordered packet during priming: extend the start backwards
            } else {
                late++
                return
            }
        }

        // Current payload.
        val i = pkt.seq and mask
        if (slotState[i] == PRIMARY && slotSeq[i] == pkt.seq) {
            duplicates++
        } else {
            System.arraycopy(pkt.data, pkt.payloadOffset(0), slots[i], 0, payloadBytes)
            slotSeq[i] = pkt.seq
            slotState[i] = PRIMARY
            slotSilent[i] = (pkt.flags and Vcx.FLAG_SILENCE_FILL) != 0 || Pcm.isNearSilent(slots[i], framesPerPacket)
        }
        // Redundant copies of seq-1 .. seq-K, only for slots not yet played and not yet filled.
        for (j in 1..pkt.k) {
            val rs = pkt.seq - j
            if (Seq.diff(rs, nextSeq) < 0) break
            val ri = rs and mask
            if (slotState[ri] != EMPTY && slotSeq[ri] == rs) continue
            System.arraycopy(pkt.data, pkt.payloadOffset(j), slots[ri], 0, payloadBytes)
            slotSeq[ri] = rs
            slotState[ri] = REDUNDANT
            slotSilent[ri] = Pcm.isNearSilent(slots[ri], framesPerPacket)
        }
        if (Seq.diff(pkt.seq, highest) > 0) highest = pkt.seq

        if (!primed && span() >= targetPackets) {
            primed = true
            log("VCX-PRIMED seq=${Seq.toUnsigned(nextSeq)} depth_packets=${span()} target_packets=$targetPackets frames=$framesPerPacket epoch=$epoch")
        }
        if (primed && span() > targetPackets + capPackets) hardCap()
    }

    @Synchronized
    override fun read(out: ByteArray): Int {
        if (!primed) return 0
        require(out.size >= payloadBytes) { "out buffer too small" }
        if (pendingInserts > 0) {
            // Start-up alignment insert: silence without consuming a slot.
            pendingInserts--
            java.util.Arrays.fill(out, 0, payloadBytes, 0)
            lastWasGood = false
            fadeInNext = true
            return framesPerPacket
        }
        var i = nextSeq and mask
        // Silence-aware depth correction (budgets set by PlayoutChain): drop or hold silent packets.
        while (slotState[i] != EMPTY && slotSeq[i] == nextSeq && slotSilent[i]) {
            if (silDropBudget > 0 && span() > 1) {
                slotState[i] = EMPTY
                nextSeq++
                silDropBudget--
                silDropped++
                silNetEdits--
                i = nextSeq and mask
                continue
            }
            if (silInsertBudget > 0) {
                silInsertBudget--
                silInserted++
                silNetEdits++
                java.util.Arrays.fill(out, 0, payloadBytes, 0)
                return framesPerPacket
            }
            break
        }
        if (cutPending > 0 && tryCut(i, out)) return framesPerPacket
        if (slotState[i] != EMPTY && slotSeq[i] == nextSeq) {
            System.arraycopy(slots[i], 0, out, 0, payloadBytes)
            if (slotState[i] == REDUNDANT) recovered++
            slotState[i] = EMPTY
            emptyRun = 0
            if (fades) {
                if (fadeInNext) Pcm.fadeIn(out, framesPerPacket)
                System.arraycopy(out, 0, lastGood, 0, payloadBytes)
            }
            fadeInNext = false
            lastWasGood = true
        } else {
            if (fades && lastWasGood) {
                System.arraycopy(lastGood, 0, out, 0, payloadBytes)
                Pcm.fadeOut(out, framesPerPacket)
            } else {
                java.util.Arrays.fill(out, 0, payloadBytes, 0)
            }
            lastWasGood = false
            fadeInNext = true
            lost++
            concealed++
            if (Seq.diff(highest, nextSeq) < 0) {
                // Nothing at or after this slot has arrived: we are starving.
                emptyRun++
                if (emptyRun >= stallPackets) {
                    rebuffers++
                    log("VCX-REBUFFER no data for $emptyRun slots, re-priming at next packet")
                    started = false
                    primed = false
                }
            }
        }
        nextSeq++
        return framesPerPacket
    }

    /** Start-up alignment: discard up to [n] of the oldest queued packets. Returns how many were dropped. */
    @Synchronized
    fun dropOldest(n: Int): Int {
        if (!primed || n <= 0) return 0
        val k = minOf(n, span().coerceAtLeast(0))
        for (q in 0 until k) {
            val s = nextSeq + q
            val i = s and mask
            if (slotSeq[i] == s) slotState[i] = EMPTY
        }
        nextSeq += k
        alignDropped += k
        if (k > 0) fadeInNext = true
        return k
    }

    /** Start-up alignment: play [n] packets of silence before the next queued packet. */
    @Synchronized
    fun insertSilence(n: Int) {
        if (!primed || n <= 0) return
        pendingInserts += n
        alignInserted += n
    }

    /**
     * Skip [n] packets on a later read(): that read plays the next packet crossfaded into the one [n]
     * after it. Waits (pending) until both packets are here; replaces any pending request.
     */
    @Synchronized
    fun requestCut(n: Int) {
        cutPending = maxOf(0, n)
    }

    @Synchronized
    fun cutPending(): Boolean = cutPending > 0

    /** Packets removed by cuts since the last call (>= 0). */
    @Synchronized
    fun pollCutEdits(): Int {
        val n = cutNetEdits
        cutNetEdits = 0
        return n
    }

    /** Pending cut at slot [i]: crossfade it into slot i + n and skip the n between. False if not possible yet. */
    private fun tryCut(i: Int, out: ByteArray): Boolean {
        val n = cutPending
        if (span() <= n + 1) { cutPending = 0; return false }
        val toSeq = nextSeq + n
        val j = toSeq and mask
        if (slotState[i] == EMPTY || slotSeq[i] != nextSeq || slotState[j] == EMPTY || slotSeq[j] != toSeq) return false
        Pcm.crossfade(slots[i], slots[j], out, framesPerPacket)
        for (q in 0..n) {
            val k = (nextSeq + q) and mask
            if (slotSeq[k] == nextSeq + q) slotState[k] = EMPTY
        }
        nextSeq = toSeq + 1
        cutPending = 0
        cuts++
        cutPackets += n
        cutNetEdits += n
        emptyRun = 0
        if (fades) System.arraycopy(out, 0, lastGood, 0, payloadBytes)
        fadeInNext = false
        lastWasGood = true
        log("VCX-CUT dropped=$n packets (${n * framesPerPacket * 1000.0 / Vcx.SAMPLE_RATE} ms) crossfaded")
        return true
    }

    /**
     * Allow up to [drop] silent packets to be skipped or [insert] packets of silence to be played in
     * front of silent packets. Replaces any previous budget.
     */
    @Synchronized
    fun setSilenceBudget(drop: Int, insert: Int) {
        silDropBudget = maxOf(0, drop)
        silInsertBudget = maxOf(0, insert)
    }

    /** Fills [out] with received, concealed, late (allocation-free). */
    @Synchronized
    fun fillJumpCounters(out: LongArray) {
        out[0] = received; out[1] = concealed; out[2] = late
    }

    /** Net packets added (+) / removed (-) by silence edits since the last call. */
    @Synchronized
    fun pollSilenceEdits(): Int {
        val n = silNetEdits
        silNetEdits = 0
        return n
    }

    /**
     * Unclamped lead (span incl. pending inserts, frames; negative while starving) in the high 32 bits
     * and the highest received seq in the low 32 bits, read atomically.
     */
    @Synchronized
    fun leadState(): Long {
        val raw = if (!started || framesPerPacket == 0) 0 else (span() + pendingInserts) * framesPerPacket
        return (raw.toLong() shl 32) or (highest.toLong() and 0xFFFF_FFFFL)
    }

    /** Queued frames not yet handed to the next stage (slots incl. gaps, plus pending inserts). */
    @Synchronized
    fun queuedFrames(): Int {
        if (!started || framesPerPacket == 0) return 0
        return (span().coerceAtLeast(0) + pendingInserts) * framesPerPacket
    }

    @Synchronized
    fun depthUs(): Int = (queuedFrames().toLong() * 1_000_000L / Vcx.SAMPLE_RATE).toInt()

    @Synchronized
    fun epoch(): Int = epoch

    @Synchronized
    fun isPrimed(): Boolean = primed

    @Synchronized
    fun counters(): Counters = Counters(
        received, lost, recovered, concealed, late, duplicates, caps, cappedPackets, alignDropped,
        alignInserted, rebuffers, resyncs, maxK, silDropped, silInserted, cuts, cutPackets, depthUs(), targetMs * 1000, primed,
        framesPerPacket,
    )

    private fun span(): Int = Seq.diff(highest, nextSeq) + 1

    private fun configure(frames: Int) {
        if (framesPerPacket != 0) log("VCX-RECONFIG frames_per_packet $framesPerPacket -> $frames, re-priming")
        framesPerPacket = frames
        payloadBytes = frames * Vcx.BYTES_PER_FRAME
        slots = Array(capacity) { ByteArray(payloadBytes) }
        lastGood = ByteArray(payloadBytes)
        targetPackets = ceilDiv(targetMs * Vcx.SAMPLE_RATE / 1000, frames).coerceAtLeast(1)
        capPackets = ceilDiv(capExcessMs * Vcx.SAMPLE_RATE / 1000, frames).coerceAtLeast(1)
        stallPackets = maxOf(targetPackets, ceilDiv(stallMs * Vcx.SAMPLE_RATE / 1000, frames))
        require(targetPackets + capPackets + 64 < capacity / 2) { "target $targetMs ms too large for capacity" }
        started = false
        primed = false
    }

    private fun begin(seq: Int) {
        java.util.Arrays.fill(slotState, EMPTY)
        started = true
        primed = false
        nextSeq = seq
        highest = seq - 1
        emptyRun = 0
        pendingInserts = 0
        lastWasGood = false
        fadeInNext = false
        silDropBudget = 0
        silInsertBudget = 0
        cutPending = 0
        epoch++
    }

    private fun hardCap() {
        val newNext = highest - targetPackets + 1
        val drop = Seq.diff(newNext, nextSeq)
        for (q in 0 until drop) {
            val s = nextSeq + q
            val i = s and mask
            if (slotSeq[i] == s) slotState[i] = EMPTY
        }
        nextSeq = newNext
        pendingInserts = 0
        fadeInNext = true
        caps++
        cappedPackets += drop
        log("VCX-CAP depth over target+$capExcessMs ms: dropped=$drop packets (${drop * framesPerPacket * 1000 / Vcx.SAMPLE_RATE} ms) to target_packets=$targetPackets")
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private companion object {
        const val EMPTY: Byte = 0
        const val PRIMARY: Byte = 1
        const val REDUNDANT: Byte = 2
    }
}

/** In-place gain ramps on interleaved stereo s16le. */
object Pcm {
    /** True if every sample is within +-[lsb] of zero. */
    fun isNearSilent(b: ByteArray, frames: Int, lsb: Int = 2): Boolean {
        for (k in 0 until frames * Vcx.CHANNELS) {
            val s = ((b[k * 2].toInt() and 0xFF) or (b[k * 2 + 1].toInt() shl 8)).toShort().toInt()
            if (s > lsb || s < -lsb) return false
        }
        return true
    }

    /** [out] = [from] fading out + [to] fading in, linear over the packet (gains sum to 1). */
    fun crossfade(from: ByteArray, to: ByteArray, out: ByteArray, frames: Int) {
        for (f in 0 until frames) {
            val g = f.toFloat() / frames
            for (ch in 0 until Vcx.CHANNELS) {
                val o = (f * Vcx.CHANNELS + ch) * 2
                val a = ((from[o].toInt() and 0xFF) or (from[o + 1].toInt() shl 8)).toShort().toInt()
                val b = ((to[o].toInt() and 0xFF) or (to[o + 1].toInt() shl 8)).toShort().toInt()
                val v = Math.round(a * (1 - g) + b * g)
                out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
            }
        }
    }

    /** Gain ramps 0 -> (frames-1)/frames across the packet. */
    fun fadeIn(b: ByteArray, frames: Int) = ramp(b, frames, rising = true)

    /** Gain ramps 1 -> 1/frames... down to 0 at the end of the packet. */
    fun fadeOut(b: ByteArray, frames: Int) = ramp(b, frames, rising = false)

    private fun ramp(b: ByteArray, frames: Int, rising: Boolean) {
        for (f in 0 until frames) {
            val g = if (rising) f.toFloat() / frames else (frames - 1 - f).toFloat() / frames
            for (ch in 0 until Vcx.CHANNELS) {
                val o = (f * Vcx.CHANNELS + ch) * 2
                val s = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
                val v = Math.round(s * g)
                b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
            }
        }
    }
}
