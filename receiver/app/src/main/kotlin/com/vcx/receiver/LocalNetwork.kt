package com.vcx.receiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 17 (API 37) local network protection: apps targeting 37 need the runtime permission
 * ACCESS_LOCAL_NETWORK (Nearby devices group) to send or receive LAN traffic. Without it the PC's
 * AUDIO never arrives and our DISCOVER_REPLY / STATS never leave, so the PC sits at "Searching".
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val REQUEST_CODE = 37
    private const val PREFS = "local_network"
    private const val KEY_ASKED = "asked"

    fun required() = Build.VERSION.SDK_INT >= 37

    fun granted(c: Context) = !required() || c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** For the VCX-START line. */
    fun state(c: Context) = when {
        !required() -> "not_required"
        granted(c) -> "granted"
        else -> "denied"
    }

    /**
     * Shows the system prompt, or opens the app's settings page once Android stops showing it
     * ("Don't allow" twice). [Activity.shouldShowRequestPermissionRationale] is false both before the
     * first ask and after a permanent denial, so remember whether we have asked.
     */
    fun request(a: Activity) {
        if (granted(a)) return
        val asked = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
        if (asked && !a.shouldShowRequestPermissionRationale(PERMISSION)) {
            openAppSettings(a)
        } else {
            markAsked(a)
            a.requestPermissions(arrayOf(PERMISSION), REQUEST_CODE)
        }
    }

    fun markAsked(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun openAppSettings(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
