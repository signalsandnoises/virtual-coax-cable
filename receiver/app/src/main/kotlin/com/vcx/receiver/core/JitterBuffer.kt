package com.vcx.receiver.core

/**
 * Milestone-1 jitter buffer: fixed target depth, keyed by u32 seq (wrap-safe), no drift control.
 *
 * - push() is called by the receive thread, read() by the playout thread; both are synchronized.
 * - Depth = slots from the next playout seq up to the highest received seq (gaps count, since they
 *   will be played as fill), times frames per packet.
 * - Priming: read() returns 0 (caller plays silence) until depth >= target. Then every read() plays
 *   exactly one slot and advances, so latency stays fixed at the target.
 * - Missing slot: redundant copy if one arrived (recovered), else silence (lost + concealed).
 * - Packet for an already played slot: late, discarded.
 * - Depth > target + maxExcess (start-up / after a stall): drop the oldest down to target (logged).
 * - Concealing past all received data for [stallPackets] slots in a row (sender gone / long stall):
 *   stop and re-prime on the next packet (logged as rebuffer).
 * - A seq jump of more than capacity/2 (sender restarted): resync, i.e. re-prime from that packet.
 *
 * Counters are cumulative for the lifetime of this object (= one service START).
 */
class JitterBuffer(
    val targetMs: Int,
    private val capacity: Int = 2048,
    private val maxExcessMs: Int = 100,
    private val minStallMs: Int = 200,
    private val log: (String) -> Unit = {},
) : FrameSource {

    init {
        require(capacity >= 64 && (capacity and (capacity - 1)) == 0) { "capacity must be a power of two >= 64" }
        require(targetMs > 0)
    }

    private val mask = capacity - 1
    private val slotSeq = IntArray(capacity)
    private val slotState = ByteArray(capacity)
    private var slots: Array<ByteArray> = emptyArray()

    private var framesPerPacket = 0
    private var payloadBytes = 0
    private var targetPackets = 0
    private var excessPackets = 0
    private var stallPackets = 0

    private var started = false
    private var primed = false
    private var nextSeq = 0
    private var highest = 0
    private var emptyRun = 0

    // Cumulative counters.
    private var received = 0L
    private var lost = 0L
    private var recovered = 0L
    private var concealed = 0L
    private var late = 0L
    private var duplicates = 0L
    private var trims = 0L
    private var trimmedPackets = 0L
    private var rebuffers = 0L
    private var resyncs = 0L

    data class Counters(
        val received: Long, val lost: Long, val recovered: Long, val concealed: Long, val late: Long,
        val duplicates: Long, val trims: Long, val trimmedPackets: Long, val rebuffers: Long, val resyncs: Long,
        val depthUs: Int, val targetUs: Int, val primed: Boolean, val framesPerPacket: Int,
    )

    @Synchronized
    fun push(pkt: AudioPacket) {
        received++
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
        }
        if (Seq.diff(pkt.seq, highest) > 0) highest = pkt.seq

        if (!primed && span() >= targetPackets) {
            primed = true
            log("VCX-PRIMED seq=${Seq.toUnsigned(nextSeq)} depth_packets=${span()} target_packets=$targetPackets frames=$framesPerPacket")
        }
        if (primed && span() > targetPackets + excessPackets) trim()
    }

    @Synchronized
    override fun read(out: ByteArray): Int {
        if (!primed) return 0
        require(out.size >= payloadBytes) { "out buffer too small" }
        val i = nextSeq and mask
        if (slotState[i] != EMPTY && slotSeq[i] == nextSeq) {
            System.arraycopy(slots[i], 0, out, 0, payloadBytes)
            if (slotState[i] == REDUNDANT) recovered++
            slotState[i] = EMPTY
            emptyRun = 0
        } else {
            // M1 concealment: silence. (Repeat-with-fade can come later; it is counted either way.)
            java.util.Arrays.fill(out, 0, payloadBytes, 0)
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

    @Synchronized
    fun counters(): Counters = Counters(
        received, lost, recovered, concealed, late, duplicates, trims, trimmedPackets, rebuffers, resyncs,
        depthUs(), (targetMs * 1000), primed, framesPerPacket,
    )

    /** Queued audio not yet handed to the output, in µs. */
    @Synchronized
    fun depthUs(): Int {
        if (!started || framesPerPacket == 0) return 0
        val s = span().coerceAtLeast(0).toLong()
        return (s * framesPerPacket * 1_000_000L / Vcx.SAMPLE_RATE).toInt()
    }

    private fun span(): Int = Seq.diff(highest, nextSeq) + 1

    private fun configure(frames: Int) {
        if (framesPerPacket != 0) log("VCX-RECONFIG frames_per_packet $framesPerPacket -> $frames, re-priming")
        framesPerPacket = frames
        payloadBytes = frames * Vcx.BYTES_PER_FRAME
        slots = Array(capacity) { ByteArray(payloadBytes) }
        targetPackets = ceilDiv(targetMs * Vcx.SAMPLE_RATE / 1000, frames).coerceAtLeast(1)
        excessPackets = ceilDiv(maxExcessMs * Vcx.SAMPLE_RATE / 1000, frames).coerceAtLeast(1)
        stallPackets = maxOf(targetPackets, ceilDiv(minStallMs * Vcx.SAMPLE_RATE / 1000, frames))
        require(targetPackets + excessPackets + 64 < capacity / 2) { "target $targetMs ms too large for capacity" }
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
    }

    private fun trim() {
        val newNext = highest - targetPackets + 1
        val drop = Seq.diff(newNext, nextSeq)
        for (q in 0 until drop) {
            val s = nextSeq + q
            val i = s and mask
            if (slotSeq[i] == s) slotState[i] = EMPTY
        }
        nextSeq = newNext
        trims++
        trimmedPackets += drop
        log("VCX-TRIM dropped=$drop packets (${drop * framesPerPacket * 1000 / Vcx.SAMPLE_RATE} ms) to target_packets=$targetPackets")
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private companion object {
        const val EMPTY: Byte = 0
        const val PRIMARY: Byte = 1
        const val REDUNDANT: Byte = 2
    }
}
