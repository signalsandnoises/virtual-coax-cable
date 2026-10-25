package com.vcx.receiver.core

/**
 * Turns the phone's Location off while streaming and restores it afterwards (Samsung geofencing
 * triggers full Wi-Fi scans every few seconds while Location is on, each stalling the stream).
 *
 * Rules:
 *  - Only acts with WRITE_SECURE_SETTINGS (granted once via `pm grant`); otherwise logs and skips.
 *  - The "restore needed" flag and previous mode are persisted BEFORE Location is changed, so a crash
 *    or force-stop can be recovered on the next service/activity start ([recoverIfNeeded]).
 *  - Never turns Location on unless we turned it off: the flag is only set when it was on.
 *  - A START while already streaming keeps the original saved mode (no restore/re-disable toggle).
 *
 * Android-free; the framework calls live behind [LocationBackend] and [GuardStore].
 */
class LocationGuard(
    private val backend: LocationBackend,
    private val store: GuardStore,
    private val log: (String) -> Unit,
) {
    /** Called on stream START. [enabled] is the location_off extra. */
    fun engage(enabled: Boolean) {
        if (!enabled) {
            if (store.restoreNeeded) restore("location_off=0") else log("VCX-LOCATION skipped: location_off=0")
            return
        }
        if (!backend.hasPermission()) {
            log("VCX-LOCATION skipped: no WRITE_SECURE_SETTINGS")
            return
        }
        if (store.restoreNeeded && !backend.isEnabled()) {
            log("VCX-LOCATION still off (saved mode=${store.previousMode})")
            return
        }
        val mode = backend.currentMode()
        if (!backend.isEnabled() && mode == LocationBackend.MODE_OFF) {
            if (store.restoreNeeded) store.clear()
            log("VCX-LOCATION already off, leaving it")
            return
        }
        val previous = if (mode == LocationBackend.MODE_OFF) LocationBackend.MODE_HIGH_ACCURACY else mode
        // Persist first: if we die after switching it off, the next start restores it.
        store.save(previous)

        var via = "none"
        if (backend.setModeSecure(LocationBackend.MODE_OFF) && backend.awaitEnabled(false)) {
            via = "secure_setting"
        } else if (backend.setEnabledReflect(false) && backend.awaitEnabled(false)) {
            via = "reflection"
        }
        val verified = !backend.isEnabled()
        if (!verified) {
            store.clear() // nothing changed, nothing to restore
            log("VCX-LOCATION off FAILED (was mode=$previous, secure_setting and reflection had no effect)")
            return
        }
        log("VCX-LOCATION off (was mode=$previous, via=$via, verified=true)")
    }

    /** Called when streaming stops (STOP, onDestroy) and by crash recovery. */
    fun restore(reason: String) {
        if (!store.restoreNeeded) return
        val previous = store.previousMode
        if (previous == LocationBackend.MODE_OFF) {
            store.clear()
            return
        }
        if (!backend.hasPermission()) {
            log("VCX-LOCATION cannot restore mode=$previous ($reason): no WRITE_SECURE_SETTINGS; will retry")
            return
        }
        var via = "none"
        if (backend.setModeSecure(previous) && backend.awaitEnabled(true)) {
            via = "secure_setting"
        } else if (backend.setEnabledReflect(true) && backend.awaitEnabled(true)) {
            via = "reflection"
        }
        val verified = backend.isEnabled()
        if (verified) {
            store.clear()
            log("VCX-LOCATION restored mode=$previous via=$via verified=true ($reason)")
        } else {
            log("VCX-LOCATION restore mode=$previous FAILED ($reason); flag kept, will retry on next start")
        }
    }

    /**
     * On every service/activity start (restore after a crash or force-stop) and on onTaskRemoved:
     * restores only if nothing is streaming.
     */
    fun recoverIfNeeded(streaming: Boolean) {
        if (store.restoreNeeded && !streaming) restore("recovery")
    }
}

/** Framework access for [LocationGuard]. */
interface LocationBackend {
    fun hasPermission(): Boolean
    /** Settings.Secure.LOCATION_MODE value. */
    fun currentMode(): Int
    fun isEnabled(): Boolean
    /** Settings.Secure.putInt(LOCATION_MODE, mode); false if it threw. */
    fun setModeSecure(mode: Int): Boolean
    /** Hidden LocationManager.setLocationEnabledForUser via reflection; false if unavailable. */
    fun setEnabledReflect(enabled: Boolean): Boolean
    /** Waits briefly until isEnabled() == [enabled]; returns whether it got there. */
    fun awaitEnabled(enabled: Boolean): Boolean

    companion object {
        const val MODE_OFF = 0
        const val MODE_HIGH_ACCURACY = 3
    }
}

/** Persistent "restore needed" flag + previous mode (SharedPreferences on Android, committed synchronously). */
interface GuardStore {
    val restoreNeeded: Boolean
    val previousMode: Int
    fun save(previousMode: Int)
    fun clear()
}
