package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayStatsTest {

    @Test
    fun constantDelayGivesZeroJitter() {
        val d = DelayStats()
        for (k in 0 until 400) d.onArrival(k, 120, 5_000L + k * 2500L)
        val s = d.takeSecond(5_000L + 399 * 2500L)
        assertEquals(0f, s.delayP99Ms, 0.001f)
        assertEquals(0f, s.delayMaxMs, 0.001f)
        assertEquals(2.5f, s.gapMaxMs, 0.001f)
    }

    @Test
    fun spikeShowsInMaxAndGap() {
        val d = DelayStats()
        for (k in 0 until 400) {
            val extra = if (k == 200) 8_000L else 0L
            d.onArrival(k, 120, 5_000L + k * 2500L + extra)
        }
        val s = d.takeSecond(5_000L + 399 * 2500L)
        assertEquals(8f, s.delayMaxMs, 0.001f)
        assertEquals(0f, s.delayP99Ms, 0.001f) // a single packet is below p99 of 400
        assertEquals(10.5f, s.gapMaxMs, 0.001f)
    }

    @Test
    fun handlesSeqWrapAndOpenGap() {
        val d = DelayStats()
        val start = 0xFFFFFF00L.toInt()
        for (q in 0 until 512) d.onArrival(start + q, 120, q * 2500L)
        val s = d.takeSecond(511 * 2500L + 50_000L) // 50 ms since the last packet
        assertEquals(0f, s.delayMaxMs, 0.001f)
        assertEquals(50f, s.gapMaxMs, 0.001f)
    }
}
