package com.vcx.receiver.core

import java.util.Locale

/** Everything reported once a second (logcat VCX-STATS line + binary STATS packet). Counters are cumulative. */
data class StatsSnapshot(
    val bufferUs: Int,
    val targetUs: Int,
    val targetMs: Int,
    val received: Long,
    val lost: Long,
    val recovered: Long,
    val concealed: Long,
    val late: Long,
    val underruns: Long,
    val ratePpm: Float,
    val trackFrames: Int,
    val delayP99Ms: Float = 0f,
    val delayMaxMs: Float = 0f,
    val gapMaxMs: Float = 0f,
    val depthMinMs: Float = 0f,
)

object StatsFormat {
    /** Exactly the PROTOCOL.md line format, e.g.
     *  `VCX-STATS buf_ms=41.2 target_ms=40 rx=400 lost=0 recovered=0 concealed=0 late=0 underruns=0 rate_ppm=0.0 track_frames=192 delay_p99_ms=3.1 delay_max_ms=7.9 gap_max_ms=9.0 depth_min_ms=35.0` */
    fun logLine(s: StatsSnapshot): String = String.format(
        Locale.US,
        "VCX-STATS buf_ms=%.1f target_ms=%d rx=%d lost=%d recovered=%d concealed=%d late=%d underruns=%d rate_ppm=%.1f track_frames=%d" +
            " delay_p99_ms=%.1f delay_max_ms=%.1f gap_max_ms=%.1f depth_min_ms=%.1f",
        s.bufferUs / 1000.0, s.targetMs, s.received, s.lost, s.recovered, s.concealed, s.late, s.underruns,
        s.ratePpm.toDouble(), s.trackFrames,
        s.delayP99Ms.toDouble(), s.delayMaxMs.toDouble(), s.gapMaxMs.toDouble(), s.depthMinMs.toDouble(),
    )
}
