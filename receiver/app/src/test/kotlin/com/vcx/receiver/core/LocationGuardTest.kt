package com.vcx.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationGuardTest {

    private class FakeStore : GuardStore {
        override var restoreNeeded = false
        override var previousMode = 0
        override fun save(previousMode: Int) { this.previousMode = previousMode; restoreNeeded = true }
        override fun clear() { restoreNeeded = false }
    }

    private class FakeBackend(
        var mode: Int,
        var permission: Boolean = true,
        var secureWorks: Boolean = true,
        var reflectWorks: Boolean = true,
        val store: FakeStore,
    ) : LocationBackend {
        val calls = ArrayList<String>()
        var flagSetWhenTurnedOff: Boolean? = null
        override fun hasPermission() = permission
        override fun currentMode() = mode
        override fun isEnabled() = mode != LocationBackend.MODE_OFF
        override fun setModeSecure(mode: Int): Boolean {
            calls += "secure($mode)"
            if (mode == LocationBackend.MODE_OFF) flagSetWhenTurnedOff = store.restoreNeeded
            if (secureWorks) this.mode = mode
            return true // putInt "succeeds" even when it has no effect
        }
        override fun setEnabledReflect(enabled: Boolean): Boolean {
            calls += "reflect($enabled)"
            if (!enabled) flagSetWhenTurnedOff = store.restoreNeeded
            if (!reflectWorks) return false
            mode = if (enabled) LocationBackend.MODE_HIGH_ACCURACY else LocationBackend.MODE_OFF
            return true
        }
        override fun awaitEnabled(enabled: Boolean) = isEnabled() == enabled
    }

    private val logs = ArrayList<String>()
    private val store = FakeStore()
    private fun guard(b: FakeBackend) = LocationGuard(b, store) { logs += it }

    @Test
    fun turnsOffAndRestoresPersistingFlagFirst() {
        val b = FakeBackend(mode = 3, store = store)
        val g = guard(b)
        g.engage(true)
        assertEquals(0, b.mode)
        assertEquals(true, b.flagSetWhenTurnedOff) // persisted before the change
        assertTrue(store.restoreNeeded); assertEquals(3, store.previousMode)
        assertTrue(logs.last(), logs.last() == "VCX-LOCATION off (was mode=3, via=secure_setting, verified=true)")
        g.restore("stop")
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
        assertTrue(logs.last().startsWith("VCX-LOCATION restored mode=3 via=secure_setting verified=true"))
    }

    @Test
    fun neverEnablesLocationThatWasOff() {
        val b = FakeBackend(mode = 0, store = store)
        val g = guard(b)
        g.engage(true)
        g.restore("stop")
        g.recoverIfNeeded(streaming = false)
        assertEquals(0, b.mode)
        assertFalse(store.restoreNeeded)
        assertTrue(b.calls.isEmpty())
        assertTrue(logs.any { it.contains("already off") })
    }

    @Test
    fun crashRecoveryRestoresOnlyWhenNotStreaming() {
        // Previous process turned Location off and died: flag persisted, Location still off.
        store.save(3)
        val b = FakeBackend(mode = 0, store = store)
        val g = guard(b)
        g.recoverIfNeeded(streaming = true)
        assertEquals(0, b.mode)
        g.recoverIfNeeded(streaming = false)
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
        assertTrue(logs.last().contains("(recovery)"))
    }

    @Test
    fun restartWhileStreamingKeepsOriginalMode() {
        val b = FakeBackend(mode = 1, store = store) // e.g. battery-saving mode
        val g = guard(b)
        g.engage(true)
        g.engage(true) // START while running
        assertEquals(1, store.previousMode)
        assertEquals(listOf("secure(0)"), b.calls)
        g.restore("stop")
        assertEquals(1, b.mode)
    }

    @Test
    fun noPermissionSkipsAndChangesNothing() {
        val b = FakeBackend(mode = 3, permission = false, store = store)
        guard(b).engage(true)
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
        assertEquals("VCX-LOCATION skipped: no WRITE_SECURE_SETTINGS", logs.single())
    }

    @Test
    fun fallsBackToReflectionWhenSecureSettingHasNoEffect() {
        val b = FakeBackend(mode = 3, secureWorks = false, store = store)
        val g = guard(b)
        g.engage(true)
        assertEquals(0, b.mode)
        assertTrue(logs.last().contains("via=reflection"))
        g.restore("stop")
        assertTrue(b.isEnabled())
        assertTrue(logs.last().contains("via=reflection"))
    }

    @Test
    fun bothMechanismsFailingClearsFlagAndKeepsStreaming() {
        val b = FakeBackend(mode = 3, secureWorks = false, reflectWorks = false, store = store)
        guard(b).engage(true)
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
        assertTrue(logs.last().contains("FAILED"))
    }

    @Test
    fun locationOffZeroSkipsAndRestoresIfWeHadTurnedItOff() {
        val b = FakeBackend(mode = 3, store = store)
        val g = guard(b)
        g.engage(false)
        assertEquals(3, b.mode)
        assertEquals("VCX-LOCATION skipped: location_off=0", logs.last())
        g.engage(true)
        assertEquals(0, b.mode)
        g.engage(false) // restart with location_off=0
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
    }

    @Test
    fun taskRemovedWhileStreamingKeepsLocationOff() {
        val b = FakeBackend(mode = 3, store = store)
        val g = guard(b)
        g.engage(true)
        g.recoverIfNeeded(streaming = true) // onTaskRemoved: UI swiped away, stream still running
        assertEquals(0, b.mode)
        assertTrue(store.restoreNeeded)
        g.restore("stop") // streaming actually stops
        assertEquals(3, b.mode)
        assertFalse(store.restoreNeeded)
    }
}
