package com.vcx.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service (type mediaPlayback) that owns the whole receive/playout pipeline.
 *
 * adb control:
 *   am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.START \
 *       --ei target_ms 40 --es wifi_mode low_latency --ei track_bursts 2 --ei steer 1 --ei align 1 --ei siltrim 1 --ei write_frames 0 --ei location_off 1
 *   am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.STOP
 *
 * Every onStartCommand calls startForeground() first (also for STOP), because the system requires it
 * after startForegroundService() even when we are about to stop.
 */
class ReceiverService : Service() {

    companion object {
        const val ACTION_START = "com.vcx.receiver.START"
        const val ACTION_STOP = "com.vcx.receiver.STOP"
        const val EXTRA_TARGET_MS = "target_ms"
        const val EXTRA_WIFI_MODE = "wifi_mode"
        const val EXTRA_TRACK_BURSTS = "track_bursts"
        const val EXTRA_STEER = "steer"
        const val EXTRA_ALIGN = "align"
        const val EXTRA_SILTRIM = "siltrim"
        const val EXTRA_WRITE_FRAMES = "write_frames"
        const val EXTRA_LOCATION_OFF = "location_off"

        private const val CHANNEL_ID = "vcx_receiver"
        private const val NOTIFICATION_ID = 1

        @Volatile private var current: ReceiverService? = null

        /** True while an engine is running (for crash-recovery decisions). */
        fun isStreaming(): Boolean = current?.engine != null

        /** For the activity's status text. */
        fun statusText(): String {
            val e = current?.engine ?: return "stopped"
            return "running target_ms=${e.targetMs} wifi_mode=${e.wifiMode} track_bursts=${e.trackBursts} steer=${e.steer} align=${e.align} siltrim=${e.silTrim} write_frames=${e.writeFrames}\n${e.statusLine}"
        }
    }

    private var engine: ReceiverEngine? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var locationGuard: com.vcx.receiver.core.LocationGuard

    override fun onCreate() {
        super.onCreate()
        current = this
        locationGuard = androidLocationGuard(this)
        locationGuard.recoverIfNeeded(streaming = false) // restore after a crash / force-stop
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio receiver", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        goForeground(if (action == ACTION_STOP) "Stopping" else "Starting")

        if (action == ACTION_STOP) {
            Log.i(TAG, "STOP requested")
            shutdown()
            locationGuard.restore("stop")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != ACTION_START) Log.w(TAG, "unknown action $action, treating as START")

        val targetMs = (intent?.getIntExtra(EXTRA_TARGET_MS, 40) ?: 40).coerceIn(5, 1000)
        val wifiMode = when (val m = intent?.getStringExtra(EXTRA_WIFI_MODE) ?: "low_latency") {
            "low_latency", "high_perf", "none" -> m
            else -> { Log.w(TAG, "unknown wifi_mode '$m', using low_latency"); "low_latency" }
        }
        val trackBursts = (intent?.getIntExtra(EXTRA_TRACK_BURSTS, 2) ?: 2).coerceIn(1, 32)
        val steer = (intent?.getIntExtra(EXTRA_STEER, 1) ?: 1) != 0
        val align = (intent?.getIntExtra(EXTRA_ALIGN, 1) ?: 1) != 0
        val silTrim = (intent?.getIntExtra(EXTRA_SILTRIM, 1) ?: 1) != 0
        val writeFrames = (intent?.getIntExtra(EXTRA_WRITE_FRAMES, 0) ?: 0).coerceIn(0, 4800)
        val locationOff = (intent?.getIntExtra(EXTRA_LOCATION_OFF, 1) ?: 1) != 0

        shutdown() // START while running restarts with the new settings (Location stays as it is)
        acquireLocks(wifiMode)
        locationGuard.engage(locationOff)
        try {
            engine = ReceiverEngine(applicationContext, targetMs, wifiMode, trackBursts, steer, align, silTrim, writeFrames, locationOff).also { it.start() }
            goForeground("Receiving, target $targetMs ms, Wi-Fi $wifiMode")
        } catch (e: Exception) {
            Log.e(TAG, "VCX-START failed: $e", e)
            engine = null
            releaseLocks()
            locationGuard.restore("start failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        locationGuard.restore("destroy")
        current = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the UI away does not stop the stream, so Location stays off (restoring it would bring
        // the Wi-Fi scans back). Restore only if nothing is streaming any more.
        locationGuard.recoverIfNeeded(streaming = engine != null)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        engine?.let {
            try { it.stop() } catch (e: Exception) { Log.e(TAG, "engine stop failed: $e", e) }
        }
        engine = null
        releaseLocks()
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is deprecated on API 34+, kept as an experiment knob
    private fun acquireLocks(wifiMode: String) {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val mode = when (wifiMode) {
            "low_latency" -> WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            "high_perf" -> WifiManager.WIFI_MODE_FULL_HIGH_PERF
            else -> null
        }
        if (mode != null) {
            wifiLock = wm.createWifiLock(mode, "vcx:wifi").apply { setReferenceCounted(false); acquire() }
        }
        // Lets DISCOVER broadcasts through the Wi-Fi driver's filter on devices that drop them.
        multicastLock = wm.createMulticastLock("vcx:discovery").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vcx:receiver").apply {
            setReferenceCounted(false); acquire()
        }
        Log.i(TAG, "locks acquired wifi_mode=$wifiMode wifi_lock=${wifiLock?.isHeld ?: false} wake_lock=${wakeLock?.isHeld}")
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        multicastLock = null
        wakeLock = null
    }

    private fun goForeground(text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("VCX receiver")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
