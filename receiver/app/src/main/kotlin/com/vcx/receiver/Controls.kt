package com.vcx.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent

/**
 * Headset / Bluetooth play button when no session is active (after a pause the service is gone).
 * Registered with MediaSession.setMediaButtonBroadcastReceiver so Android can route the button here.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (ev == null || ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return
        val action = when (ev.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> ReceiverService.ACTION_START
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> ReceiverService.ACTION_STOP
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> ReceiverService.ACTION_TOGGLE
            else -> return
        }
        ReceiverService.send(context, action)
    }
}

/** Quick Settings tile: tap to play/pause. */
class VcxTileService : TileService() {
    private val listener = Runnable { refresh() }

    override fun onStartListening() {
        PlayerHub.addListener(listener)
        refresh()
    }

    override fun onStopListening() {
        PlayerHub.removeListener(listener)
    }

    override fun onClick() {
        val playing = ReceiverService.isStreaming()
        if (!ReceiverService.send(this, if (playing) ReceiverService.ACTION_STOP else ReceiverService.ACTION_START)) {
            // Android refused a background start: open the app and let it start playback.
            val i = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOPLAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(i)
            }
            return
        }
        qsTile?.let {
            it.state = if (playing) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val s = PlayerHub.latest
        val playing = s != null && s.running
        t.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "VCX Receiver"
        t.subtitle = when {
            !playing -> "Paused"
            s?.pcName != null -> s.pcName
            s?.sender != null -> s.sender
            else -> "Waiting for PC"
        }
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat_vcx)
        t.updateTile()
    }
}
