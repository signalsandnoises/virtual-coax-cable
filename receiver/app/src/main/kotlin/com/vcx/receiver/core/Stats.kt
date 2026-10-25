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
)

object StatsFormat {
    /** Exactly the PROTOCOL.md line format, e.g.
     *  `VCX-STATS buf_ms=41.2 target_ms=40 rx=400 lost=0 recovered=0 concealed=0 late=0 underruns=0 rate_ppm=0.0 track_frames=192` */
    fun logLine(s: StatsSnapshot): String = String.format(
        Locale.US,
        "VCX-STATS buf_ms=%.1f target_ms=%d rx=%d lost=%d recovered=%d concealed=%d late=%d underruns=%d rate_ppm=%.1f track_frames=%d",
        s.bufferUs / 1000.0, s.targetMs, s.received, s.lost, s.recovered, s.concealed, s.late, s.underruns,
        s.ratePpm.toDouble(), s.trackFrames,
    )
}
