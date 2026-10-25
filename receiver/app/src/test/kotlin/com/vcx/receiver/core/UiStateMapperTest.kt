package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateMapperTest {

    private fun snap(rx: Long, lost: Long = 0, late: Long = 0, concealed: Long = 0, primed: Boolean = true,
                     sender: String? = "10.0.0.223", name: String? = "DESKTOP") =
        EngineSnapshot(true, primed, rx, lost, late, concealed, 41.4, 40, sender, name)

    private fun history(vararg s: EngineSnapshot) = StatsHistory().apply { s.forEach { add(it) } }

    @Test
    fun idleWhenNoServiceOrStopped() {
        val ui = UiStateMapper.map(null, StatsHistory(), wifiConnected = true, idleTargetMs = 55)
        assertEquals(PlayStatus.IDLE, ui.status)
        assertEquals("Idle", ui.title)
        assertEquals("Target 55 ms", ui.stats)
        assertFalse(ui.showPause)
        assertEquals(PlayStatus.IDLE, UiStateMapper.map(snap(0).copy(running = false), StatsHistory(), true, 40).status)
    }

    @Test
    fun waitingUntilPrimedAndReceiving() {
        val first = EngineSnapshot(true, false, 0, 0, 0, 0, 0.0, 40)
        val ui = UiStateMapper.map(first, history(first), true, 40)
        assertEquals(PlayStatus.WAITING, ui.status)
        assertEquals("Waiting for PC…", ui.title)
        assertTrue(ui.showPause)
        assertEquals("", ui.source)
        // Sender stopped: primed but no packets for 2 s.
        val a = snap(4000); val b = snap(4000); val c = snap(4000)
        assertEquals(PlayStatus.WAITING, UiStateMapper.map(c, history(a, b, c), true, 40).status)
    }

    @Test
    fun playingWithStatsAndSource() {
        val a = snap(1000, lost = 1, late = 1); val b = snap(1400, lost = 1, late = 1)
        val ui = UiStateMapper.map(b, history(a, b), true, 40)
        assertEquals(PlayStatus.PLAYING, ui.status)
        assertEquals("Playing", ui.title)
        assertEquals("from DESKTOP (10.0.0.223)", ui.source)
        assertEquals("Buffer 41 ms · target 40 ms · lost 0 · late 0 /min", ui.stats)
        assertEquals("from 10.0.0.223", UiStateMapper.map(b.copy(pcName = null), history(a, b), true, 40).source)
    }

    @Test
    fun hiccupWhenConcealingRecently() {
        val a = snap(1000); val b = snap(1400, lost = 3, late = 3, concealed = 3); val c = snap(1800, lost = 3, late = 3, concealed = 3)
        val ui = UiStateMapper.map(c, history(a, b, c), true, 40)
        assertEquals(PlayStatus.HICCUP, ui.status)
        assertEquals("Wi-Fi hiccup", ui.title)
        assertTrue(ui.stats.endsWith("lost 3 · late 3 /min"))
        // Four quiet seconds later it is "Playing" again; lost/late still counted for the minute.
        val h = history(a, b, c, snap(2200, 3, 3, 3), snap(2600, 3, 3, 3), snap(3000, 3, 3, 3), snap(3400, 3, 3, 3))
        val later = UiStateMapper.map(h.at(0), h, true, 40)
        assertEquals(PlayStatus.PLAYING, later.status)
        assertTrue(later.stats.endsWith("lost 3 · late 3 /min"))
    }

    @Test
    fun lastMinuteWindowDropsOldLosses() {
        val h = StatsHistory()
        h.add(snap(0, lost = 0))
        h.add(snap(400, lost = 5, late = 5, concealed = 5))
        for (i in 2..70) h.add(snap(i * 400L, lost = 5, late = 5, concealed = 5))
        val ui = UiStateMapper.map(h.at(0), h, true, 40)
        assertTrue(ui.stats, ui.stats.endsWith("lost 0 · late 0 /min"))
    }

    @Test
    fun noWifiWins() {
        val a = snap(1000); val b = snap(1400)
        val ui = UiStateMapper.map(b, history(a, b), wifiConnected = false, idleTargetMs = 40)
        assertEquals(PlayStatus.NO_WIFI, ui.status)
        assertTrue(ui.showPause)
    }
}
