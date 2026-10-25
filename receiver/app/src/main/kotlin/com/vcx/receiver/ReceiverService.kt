package com.vcx.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.vcx.receiver.core.ConfigResolver
import com.vcx.receiver.core.EffectiveConfig
import com.vcx.receiver.core.EngineSnapshot
import com.vcx.receiver.core.LocationGuard

/**
 * Foreground service (type mediaPlayback) that owns the whole receive/playout pipeline. The UI, the
 * Quick Settings tile, the media session (notification, lock screen, headset button) and adb all
 * drive it with the same intents:
 *
 *   am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.START [extras]
 *   am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.STOP
 *   ... -a com.vcx.receiver.TOGGLE
 *
 * START settings come from the saved settings; extras present on the intent override them (adb tests).
 * Every onStartCommand calls startForeground() first (also for STOP), as the system requires.
 */
class ReceiverService : Service() {

    companion object {
        const val ACTION_START = "com.vcx.receiver.START"
        const val ACTION_STOP = "com.vcx.receiver.STOP"
        const val ACTION_TOGGLE = "com.vcx.receiver.TOGGLE"

        private const val CHANNEL_ID = "vcx_receiver"
        private const val NOTIFICATION_ID = 1

        @Volatile private var current: ReceiverService? = null

        /** True while an engine is running. */
        fun isStreaming(): Boolean = current?.engine != null

        /** Sends [action] to the service; returns false if Android refused a background FGS start. */
        fun send(context: Context, action: String): Boolean = try {
            context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(action))
            true
        } catch (e: Exception) {
            Log.w(TAG, "start-foreground-service $action refused: $e")
            false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: ReceiverEngine? = null
    private var config: EffectiveConfig? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var locationGuard: LocationGuard
    private lateinit var session: MediaSession
    private var noisyRegistered = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSourceText = ""

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && engine != null && config?.stopOnUnplug == true) {
                Log.i(TAG, "VCX-NOISY output unplugged, stopping so audio doesn't go to the phone speaker")
                stopStreaming("unplugged")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        locationGuard = androidLocationGuard(this)
        locationGuard.recoverIfNeeded(streaming = false) // restore after a crash / force-stop
        session = MediaSession(this, "vcx").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (engine == null) startStreaming(null) }
                override fun onPause() { stopStreaming("media session pause") }
                override fun onStop() { stopStreaming("media session stop") }
            })
            setMediaButtonBroadcastReceiver(ComponentName(this@ReceiverService, MediaButtonReceiver::class.java))
            setSessionActivity(openAppIntent())
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var action = intent?.action ?: ACTION_START
        if (action == ACTION_TOGGLE) action = if (engine != null) ACTION_STOP else ACTION_START
        goForeground(if (action == ACTION_STOP) "Stopping" else "Starting", playing = false)
        when (action) {
            ACTION_STOP -> { Log.i(TAG, "STOP requested"); stopStreaming("stop") }
            ACTION_START -> startStreaming(intent)
            else -> { Log.w(TAG, "unknown action $action, treating as START"); startStreaming(intent) }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent?) {
        val cfg = ConfigResolver.resolve(SettingsRepo.load(this), IntentExtras(intent))
        shutdown() // START while running restarts with the new settings (Location stays as it is)
        acquireLocks(cfg.wifiMode)
        locationGuard.engage(cfg.locationOff)
        try {
            val e = ReceiverEngine(
                applicationContext, cfg,
                onSnapshot = { s -> PlayerHub.publish(s); main.post { onSnapshot(s) } },
                onSender = { ip, name -> PlayerHub.noteSender(this, ip, name) },
            )
            e.start()
            engine = e
            config = cfg
            registerNoisy()
            lastSourceText = ""
            PlayerHub.publish(EngineSnapshot(true, false, 0, 0, 0, 0, 0.0, cfg.targetMs))
            setSessionPlaying(true)
            goForeground("Waiting for PC…", playing = true)
        } catch (e: Exception) {
            Log.e(TAG, "VCX-START failed: $e", e)
            engine = null
            releaseLocks()
            locationGuard.restore("start failed")
            stopStreaming("start failed")
        }
    }

    private fun stopStreaming(reason: String) {
        shutdown()
        locationGuard.restore(reason)
        setSessionPlaying(false)
        PlayerHub.publish(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onSnapshot(s: EngineSnapshot) {
        if (engine == null) return
        val src = when {
            s.pcName != null -> "Playing from ${s.pcName}"
            s.sender != null -> "Playing from ${s.sender}"
            else -> "Waiting for PC…"
        }
        if (src != lastSourceText) {
            lastSourceText = src
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "VCX Receiver")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, src)
                    .build(),
            )
            goForeground(src, playing = true)
        }
    }

    override fun onDestroy() {
        shutdown()
        locationGuard.restore("destroy")
        PlayerHub.publish(null)
        unregisterNoisy()
        netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        session.release()
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
        unregisterNoisy()
        releaseLocks()
    }

    private fun setSessionPlaying(playing: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        if (playing && lastSourceText.isEmpty()) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "VCX Receiver")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Waiting for PC…")
                    .build(),
            )
        }
        session.isActive = playing
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(noisyReceiver, f)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    /** Logs Wi-Fi loss/return. Resuming needs no action: sockets stay bound, the rx socket re-accepts a sender after 2 s idle. */
    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Log.i(TAG, "VCX-NET wifi available") }
            override fun onLost(network: Network) { Log.i(TAG, "VCX-NET wifi lost") }
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
            )
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback failed: $e")
        }
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

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun goForeground(text: String, playing: Boolean) {
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vcx)
            .setContentTitle("VCX Receiver")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (playing) {
            val pause = PendingIntent.getForegroundService(
                this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pause), "Pause", pause).build())
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
        } else {
            b.setCategory(Notification.CATEGORY_SERVICE)
        }
        startForeground(NOTIFICATION_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
