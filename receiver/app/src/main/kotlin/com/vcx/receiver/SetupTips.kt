package com.vcx.receiver

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Read-only checks of phone settings that hurt streaming, with links to where the user can change
 * them. Never writes any of these. Samsung keys may be unreadable for third-party apps on Android 12+
 * (non-@Readable settings throw SecurityException); those show as "unknown".
 */
object SetupTips {
    const val GRANT_COMMAND = "adb shell pm grant com.vcx.receiver android.permission.WRITE_SECURE_SETTINGS"

    enum class State { OK, WARN, UNKNOWN }

    data class Tip(val title: String, val detail: String, val state: State, val actionLabel: String?, val action: ((Context) -> Unit)?)

    fun hasSecureSettingsPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun ignoringBatteryOptimizations(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** Media volume as a fraction 0..1. */
    fun mediaVolume(c: Context): Float {
        val am = c.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** Integer setting from Global, then Secure, then System; null if absent or unreadable. */
    fun readSetting(c: Context, key: String): Int? {
        val cr = c.contentResolver
        val readers = listOf<() -> String?>(
            { Settings.Global.getString(cr, key) }, { Settings.Secure.getString(cr, key) }, { Settings.System.getString(cr, key) },
        )
        for (r in readers) {
            val v = try { r() } catch (_: Exception) { null }
            v?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    fun open(c: Context, vararg actions: String) {
        for (a in actions) {
            val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    private fun settingTip(c: Context, title: String, key: String, badWhen: Int, goodText: String, badText: String,
                           actionLabel: String, vararg actions: String): Tip {
        val v = readSetting(c, key)
        val state = when (v) { null -> State.UNKNOWN; badWhen -> State.WARN; else -> State.OK }
        val detail = when (state) { State.OK -> goodText; State.WARN -> badText; State.UNKNOWN -> "Can't read this setting; check it manually." }
        return Tip(title, detail, state, actionLabel) { ctx -> open(ctx, *actions) }
    }

    fun tips(c: Context, locationOffEnabled: Boolean): List<Tip> {
        val list = ArrayList<Tip>()
        if (LocalNetwork.required()) {
            val ok = LocalNetwork.granted(c)
            list += Tip(
                "Local network access",
                if (ok) "Allowed." else "Needed to receive audio from your PC on the local network.",
                if (ok) State.OK else State.WARN,
                if (ok) null else "Allow",
            ) { ctx -> if (ctx is android.app.Activity) LocalNetwork.request(ctx) else LocalNetwork.openAppSettings(ctx) }
        }
        if (locationOffEnabled) {
            val granted = hasSecureSettingsPermission(c)
            list += Tip(
                "Location off while playing",
                if (granted) "Permission granted: Location is switched off while playing and restored after."
                else "Needs a one-time grant from a PC:\n$GRANT_COMMAND",
                if (granted) State.OK else State.WARN, null, null,
            )
        }
        val battery = ignoringBatteryOptimizations(c)
        list += Tip(
            "Battery optimization",
            if (battery) "Unrestricted: Android won't throttle playback." else "Allow unrestricted battery use so playback isn't throttled with the screen off.",
            if (battery) State.OK else State.WARN,
            if (battery) null else "Allow",
        ) { ctx ->
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) { open(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        }
        list += settingTip(
            c, "Wi-Fi power saving", "wifi_power_save", 1, "Off.", "On: it delays packets. Turn it off in Wi-Fi › Intelligent Wi-Fi.",
            "Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS,
        )
        list += settingTip(
            c, "Switch to better Wi-Fi", "sem_wifi_switch_to_better_wifi_enabled", 1, "Off.",
            "On: the phone scans for other networks. Turn it off in Wi-Fi › Intelligent Wi-Fi.",
            "Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS,
        )
        list += settingTip(
            c, "Wi-Fi scanning", "wifi_scan_always_enabled", 1, "Off.", "On: background scans can stall the stream.",
            "Scanning settings", "android.settings.LOCATION_SCANNING_SETTINGS", Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        )
        list += settingTip(
            c, "Bluetooth scanning", "ble_scan_always_enabled", 1, "Off.", "On: background Bluetooth scans share the radio with Wi-Fi.",
            "Scanning settings", "android.settings.LOCATION_SCANNING_SETTINGS", Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        )
        list += settingTip(
            c, "Nearby device scanning", "nearby_scanning_enabled", 1, "Off.", "On: periodic scans. Turn it off in Connections › More connection settings.",
            "Connection settings", Settings.ACTION_WIRELESS_SETTINGS,
        )
        return list
    }

    fun warningCount(c: Context, locationOffEnabled: Boolean) = tips(c, locationOffEnabled).count { it.state == State.WARN }
}
