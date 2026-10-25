package com.vcx.receiver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.vcx.receiver.core.EngineSnapshot
import com.vcx.receiver.core.PlayStatus
import com.vcx.receiver.core.StatsHistory
import com.vcx.receiver.core.UiState
import com.vcx.receiver.core.UiStateMapper

/**
 * Main screen: one giant play/pause button, a status line readable from across the room, a small
 * stats line and the sending PC. The service owns playback; this screen observes [PlayerHub].
 *
 * Debug builds only: `--es screen settings|tips` opens Settings directly, `--ez demo_playing true`
 * (or `--ez demo_hiccup true`)
 * shows a fake "Playing" state (for screenshots without a sender).
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_DEMO_PLAYING = "demo_playing"
        const val EXTRA_DEMO_HICCUP = "demo_hiccup"
    }

    private lateinit var button: PlayButtonView
    private lateinit var status: TextView
    private lateinit var source: TextView
    private lateinit var stats: TextView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var demo = false
    private var demoHiccup = false
    /** Play was pressed without the local network permission; start once it is granted. */
    private var startAfterGrant = false
    private val hubListener = Runnable { render() }
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore Location if a previous stream died without doing so (crash / force-stop).
        androidLocationGuard(this).recoverIfNeeded(streaming = ReceiverService.isStreaming())

        val debug = Ui.isDebuggable(this)
        readDemo(intent)
        buildUi()

        if (savedInstanceState == null) {
            handleLaunchExtras(intent)
            val ask = ArrayList<String>()
            if (!LocalNetwork.granted(this)) ask += LocalNetwork.PERMISSION
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) ask += Manifest.permission.POST_NOTIFICATIONS
            if (LocalNetwork.PERMISSION in ask) LocalNetwork.markAsked(this)
            if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDemo(intent)
        handleLaunchExtras(intent)
        render()
    }

    private fun readDemo(i: Intent) {
        val debug = Ui.isDebuggable(this)
        demoHiccup = debug && i.getBooleanExtra(EXTRA_DEMO_HICCUP, false)
        demo = debug && (i.getBooleanExtra(EXTRA_DEMO_PLAYING, false) || demoHiccup)
    }

    private fun handleLaunchExtras(i: Intent) {
        if (Ui.isDebuggable(this)) when (i.getStringExtra(EXTRA_SCREEN)) {
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "tips" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCROLL_TO, "tips"))
        }
        if (i.getBooleanExtra(EXTRA_AUTOPLAY, false) && !ReceiverService.isStreaming()) {
            ReceiverService.send(this, ReceiverService.ACTION_START)
        }
    }

    private fun buildUi() {
        val pad = Ui.dp(this, 16f)
        val gear = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = null
            contentDescription = "Settings"
            minimumWidth = Ui.dp(this@MainActivity, 56f); minimumHeight = Ui.dp(this@MainActivity, 56f)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(context, "VCX Receiver", 20f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(gear)
        }
        button = PlayButtonView(this).apply { setOnClickListener { onToggle() } }
        status = Ui.text(this, "", 34f, bold = true).apply { gravity = Gravity.CENTER }
        source = Ui.text(this, "", 17f, R.color.vcx_on_surface_variant).apply { gravity = Gravity.CENTER }
        stats = Ui.text(this, "", 15f, R.color.vcx_on_surface_variant).apply { gravity = Gravity.CENTER }
        hint = Ui.text(this, "", 15f, R.color.vcx_warn).apply {
            gravity = Gravity.CENTER
            val v = Ui.dp(context, 12f)
            setPadding(pad, v, pad, v)
            minHeight = Ui.dp(context, 48f)
            setOnClickListener {
                if (!LocalNetwork.granted(this@MainActivity)) LocalNetwork.request(this@MainActivity)
                else startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCROLL_TO, "tips"))
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(source, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })
            addView(stats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8f) })
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4f) })
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val buttonBox = if (!landscape) null else FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setPadding(pad, pad, pad, pad)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (landscape) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(buttonBox!!, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            } else {
                // Button (80 % of the width) + status block, vertically centred as one group.
                addView(CenteredGroup(context, button, texts), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        }
        val frame = FrameLayout(this).apply { addView(root) }
        Ui.applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        if (!demo) PlayerHub.addListener(hubListener)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (SettingsRepo.load(this).keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        PlayerHub.removeListener(hubListener)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun onToggle() {
        if (demo) { demo = false; demoHiccup = false; PlayerHub.addListener(hubListener); render(); return }
        val playing = PlayerHub.latest?.running == true || ReceiverService.isStreaming()
        if (!playing && !LocalNetwork.granted(this)) {
            startAfterGrant = true
            LocalNetwork.request(this)
            return
        }
        ReceiverService.send(this, if (playing) ReceiverService.ACTION_STOP else ReceiverService.ACTION_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LocalNetwork.REQUEST_CODE && startAfterGrant && LocalNetwork.granted(this) &&
            !ReceiverService.isStreaming()
        ) {
            ReceiverService.send(this, ReceiverService.ACTION_START)
        }
        startAfterGrant = false
        render()
    }

    private fun wifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun demoState(): UiState {
        val h = StatsHistory()
        val base = EngineSnapshot(true, true, 24_000, 0, 0, 0, 41.0, 40, "10.0.0.223", "DESKTOP-RC3RE47")
        h.add(base)
        val hiccup = if (demoHiccup) 12L else 0L
        val now = base.copy(received = 24_400, lost = hiccup, late = hiccup, concealed = hiccup, bufferMs = if (demoHiccup) 18.0 else 41.0)
        h.add(now)
        return UiStateMapper.map(now, h, wifiConnected = true, idleTargetMs = 40)
    }

    private fun render() {
        val settings = SettingsRepo.load(this)
        val ui = if (demo) demoState() else UiStateMapper.map(PlayerHub.latest, PlayerHub.history, wifiConnected(), settings.targetMs)
        button.playing = ui.showPause
        button.look = when (ui.status) {
            PlayStatus.IDLE -> PlayButtonView.Look.IDLE
            PlayStatus.WAITING -> PlayButtonView.Look.WAITING
            PlayStatus.PLAYING -> PlayButtonView.Look.PLAYING
            PlayStatus.HICCUP -> PlayButtonView.Look.HICCUP
            PlayStatus.NO_WIFI -> PlayButtonView.Look.WARN
        }
        val lanBlocked = !demo && !LocalNetwork.granted(this)
        if (lanBlocked) button.look = PlayButtonView.Look.WARN
        status.text = if (lanBlocked) "Local network permission needed" else ui.title
        status.setTextColor(Ui.color(this, if (ui.status == PlayStatus.HICCUP) R.color.vcx_hiccup else R.color.vcx_on_surface))
        source.text = ui.source
        source.visibility = if (ui.source.isEmpty()) View.GONE else View.VISIBLE
        stats.text = ui.stats

        val vol = SetupTips.mediaVolume(this)
        val tipCount = SetupTips.warningCount(this, settings.locationOff)
        hint.text = when {
            lanBlocked -> "Needed to receive audio from your PC · tap to allow"
            vol <= 0.05f -> "Media volume is muted"
            vol < 0.3f -> "Media volume is low (${(vol * 100).toInt()}%)"
            tipCount > 0 -> "$tipCount setup tip${if (tipCount > 1) "s" else ""} · tap to review"
            else -> ""
        }
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
    }
}
