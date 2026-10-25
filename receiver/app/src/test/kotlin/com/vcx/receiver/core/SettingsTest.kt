package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {

    private class MapExtras(vararg pairs: Pair<String, Any>) : ExtrasReader {
        val m = mapOf(*pairs)
        override fun has(key: String) = m.containsKey(key)
        override fun int(key: String, def: Int) = when (val v = m[key]) {
            is Int -> v; is Boolean -> if (v) 1 else 0; is String -> v.toIntOrNull() ?: def; else -> def
        }
        override fun string(key: String) = m[key]?.toString()
    }

    @Test
    fun defaultsAreTheBestMeasuredConfig() {
        val s = ReceiverSettings.load(MemoryStore())
        assertEquals(40, s.targetMs)
        assertEquals(6, s.trackBursts)
        assertEquals(0, s.writeFrames) // = one burst
        assertEquals(true, s.locationOff)
        assertEquals("low_latency", s.wifiMode)
        assertEquals(true, s.silTrim); assertEquals(true, s.steer); assertEquals(true, s.align)
        assertEquals(ReceiverSettings.AUTO, s.pc)
        assertEquals(ReceiverSettings.DEFAULT_DEVICE, s.outputDevice)
    }

    @Test
    fun saveLoadRoundTripAndClamping() {
        val kv = MemoryStore()
        ReceiverSettings(targetMs = 75, trackBursts = 3, wifiMode = "none", locationOff = false, pc = "10.0.0.223",
            outputDevice = "22:USB-C DAC", steer = false, align = false, silTrim = false, writeFrames = 240,
            stopOnUnplug = false, keepScreenOn = true).save(kv)
        val s = ReceiverSettings.load(kv)
        assertEquals(75, s.targetMs); assertEquals(3, s.trackBursts); assertEquals("none", s.wifiMode)
        assertEquals(false, s.locationOff); assertEquals("10.0.0.223", s.pc); assertEquals("22:USB-C DAC", s.outputDevice)
        assertEquals(false, s.steer); assertEquals(240, s.writeFrames); assertEquals(true, s.keepScreenOn)

        kv.put(mapOf("target_ms" to 5, "wifi_mode" to "bogus", "pc" to ""))
        val c = ReceiverSettings.load(kv)
        assertEquals(ReceiverSettings.TARGET_MIN, c.targetMs)
        assertEquals("low_latency", c.wifiMode)
        assertEquals(ReceiverSettings.AUTO, c.pc)
    }

    @Test
    fun absentExtrasUseSavedSettings() {
        val s = ReceiverSettings(targetMs = 60, trackBursts = 4, wifiMode = "high_perf", locationOff = false, pc = "10.0.0.5")
        val c = ConfigResolver.resolve(s, ExtrasReader.None)
        assertEquals(60, c.targetMs); assertEquals(4, c.trackBursts); assertEquals("high_perf", c.wifiMode)
        assertEquals(false, c.locationOff); assertEquals("10.0.0.5", c.pcIp); assertNull(c.outputDevice)
    }

    @Test
    fun presentExtrasOverrideSavedSettings() {
        val s = ReceiverSettings(targetMs = 60, trackBursts = 4, steer = true, pc = "10.0.0.5", outputDevice = "22:DAC")
        val c = ConfigResolver.resolve(
            s,
            MapExtras("target_ms" to 15, "track_bursts" to 2, "steer" to 0, "align" to false, "wifi_mode" to "none",
                "pc" to "auto", "output_device" to "default", "location_off" to "0", "write_frames" to 192),
        )
        assertEquals(15, c.targetMs) // adb may go below the UI's 20 ms minimum
        assertEquals(2, c.trackBursts)
        assertEquals(false, c.steer)
        assertEquals(false, c.align)
        assertEquals("none", c.wifiMode)
        assertNull(c.pcIp)
        assertNull(c.outputDevice)
        assertEquals(false, c.locationOff)
        assertEquals(192, c.writeFrames)
        assertEquals(true, c.silTrim) // not overridden
    }

    @Test
    fun invalidExtrasFallBackOrClamp() {
        val s = ReceiverSettings(wifiMode = "low_latency")
        val c = ConfigResolver.resolve(s, MapExtras("wifi_mode" to "turbo", "target_ms" to 99_999, "track_bursts" to 0))
        assertEquals("low_latency", c.wifiMode)
        assertEquals(1000, c.targetMs)
        assertEquals(1, c.trackBursts)
    }

    @Test
    fun knownSendersNewestFirstKeepNamesAndCap() {
        val kv = MemoryStore()
        KnownSenders.note(kv, "10.0.0.223", "DESKTOP", 1000)
        KnownSenders.note(kv, "10.0.0.50", null, 2000)
        KnownSenders.note(kv, "10.0.0.223", null, 3000) // seen again without a name
        val l = KnownSenders.load(kv)
        assertEquals(listOf("10.0.0.223", "10.0.0.50"), l.map { it.ip })
        assertEquals("DESKTOP", l[0].name)
        assertEquals(3000, l[0].lastSeenMs)
        for (i in 0 until 20) KnownSenders.note(kv, "192.168.1.$i", "pc\twith\ttabs", 10_000L + i)
        val capped = KnownSenders.load(kv)
        assertEquals(KnownSenders.MAX, capped.size)
        assertEquals("192.168.1.19", capped[0].ip)
        assertEquals("pc with tabs", capped[0].name)
        KnownSenders.clear(kv)
        assertEquals(0, KnownSenders.load(kv).size)
    }
}
