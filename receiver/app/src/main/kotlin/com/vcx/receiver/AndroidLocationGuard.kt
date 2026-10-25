package com.vcx.receiver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.vcx.receiver.core.GuardStore
import com.vcx.receiver.core.LocationBackend
import com.vcx.receiver.core.LocationGuard

/** Builds a [LocationGuard] wired to the real framework. */
fun androidLocationGuard(context: Context): LocationGuard {
    val app = context.applicationContext
    return LocationGuard(AndroidLocationBackend(app), PrefsGuardStore(app)) { Log.i(TAG, it) }
}

private class AndroidLocationBackend(private val context: Context) : LocationBackend {
    private val lm = context.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override fun currentMode(): Int =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, LocationBackend.MODE_OFF)

    override fun isEnabled(): Boolean = lm.isLocationEnabled

    @Suppress("DEPRECATION")
    override fun setModeSecure(mode: Int): Boolean = try {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
    } catch (e: Exception) {
        Log.w(TAG, "VCX-LOCATION secure_setting write failed: $e")
        false
    }

    override fun setEnabledReflect(enabled: Boolean): Boolean = try {
        val m = LocationManager::class.java.getMethod(
            "setLocationEnabledForUser", Boolean::class.javaPrimitiveType, UserHandle::class.java,
        )
        m.invoke(lm, enabled, Process.myUserHandle())
        true
    } catch (e: Throwable) {
        Log.w(TAG, "VCX-LOCATION reflection setLocationEnabledForUser failed: $e")
        false
    }

    override fun awaitEnabled(enabled: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 1000
        while (true) {
            if (lm.isLocationEnabled == enabled) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return lm.isLocationEnabled == enabled }
        }
    }
}

private class PrefsGuardStore(context: Context) : GuardStore {
    private val prefs = context.getSharedPreferences("vcx_location_guard", Context.MODE_PRIVATE)
    override val restoreNeeded: Boolean get() = prefs.getBoolean("restore_needed", false)
    override val previousMode: Int get() = prefs.getInt("previous_mode", LocationBackend.MODE_OFF)
    // commit(), not apply(): must be on disk before Location is switched off.
    override fun save(previousMode: Int) {
        prefs.edit().putInt("previous_mode", previousMode).putBoolean("restore_needed", true).commit()
    }
    override fun clear() {
        prefs.edit().putBoolean("restore_needed", false).commit()
    }
}
