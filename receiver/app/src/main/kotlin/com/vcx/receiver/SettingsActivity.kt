package com.vcx.receiver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.vcx.receiver.core.KnownSenders
import com.vcx.receiver.core.ReceiverSettings
import android.content.res.ColorStateList

/**
 * Settings. Every change is saved immediately and used at the next play (adb extras still override).
 * Debug: `am start -n com.vcx.receiver/.MainActivity --es screen settings` (or `tips`).
 */
class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_SCROLL_TO = "scroll_to"
    }

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var tipsCard: View? = null
    private var s = ReceiverSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            contentDescription = "Back"
            minimumWidth = Ui.dp(context, 56f); minimumHeight = Ui.dp(context, 56f)
            setOnClickListener { finish() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(Ui.text(context, "Settings", 22f, bold = true))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 16f)
            setPadding(p, 0, p, p * 2)
        }
        scroll = ScrollView(this).apply { addView(content) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val frame = FrameLayout(this).apply { addView(root) }
        Ui.applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        rebuild() // permission / battery / Wi-Fi settings may have changed while away
        if (intent.getStringExtra(EXTRA_SCROLL_TO) == "tips") {
            intent.removeExtra(EXTRA_SCROLL_TO)
            scroll.post { tipsCard?.let { scroll.smoothScrollTo(0, it.top) } }
        }
    }

    private fun save(f: (ReceiverSettings) -> ReceiverSettings) {
        s = f(s).normalized()
        SettingsRepo.save(this, s)
    }

    private fun rebuild() {
        s = SettingsRepo.load(this)
        content.removeAllViews()
        if (ReceiverService.isStreaming()) {
            content.addView(Ui.text(this, "Changes apply the next time you press play.", 14f, R.color.vcx_on_surface_variant).apply {
                setPadding(0, 0, 0, Ui.dp(context, 8f))
            })
        }
        playbackSection()
        locationSection()
        pcSection()
        outputSection()
        tipsSection()
        behaviourSection()
        advancedSection()
    }

    // --- building blocks ---

    private fun section(title: String): LinearLayout {
        content.addView(Ui.text(this, title, 15f, R.color.vcx_primary, bold = true).apply {
            setPadding(Ui.dp(context, 4f), Ui.dp(context, 24f), 0, Ui.dp(context, 8f))
        })
        val card = Ui.card(this)
        content.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun label(card: LinearLayout, title: String, detail: String? = null) {
        card.addView(Ui.text(this, title, 17f))
        if (detail != null) card.addView(Ui.text(this, detail, 14f, R.color.vcx_on_surface_variant))
    }

    private fun gap(card: LinearLayout, dp: Float = 12f) = card.addView(View(this), LinearLayout.LayoutParams(1, Ui.dp(this, dp)))

    private fun switchRow(card: LinearLayout, title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = null
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, title, 17f))
            if (detail != null) addView(Ui.text(context, detail, 14f, R.color.vcx_on_surface_variant))
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(context, 56f)
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        })
    }

    private fun radioGroup(card: LinearLayout, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        val g = RadioGroup(this)
        val tint = ColorStateList.valueOf(Ui.color(this, R.color.vcx_primary))
        for ((value, text) in options) {
            g.addView(RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 16f
                setTextColor(Ui.color(context, R.color.vcx_on_surface))
                buttonTintList = tint
                minHeight = Ui.dp(context, 48f)
                isChecked = value == selected
                tag = value
            })
        }
        g.setOnCheckedChangeListener { grp, id -> grp.findViewById<RadioButton>(id)?.let { onSelect(it.tag as String) } }
        card.addView(g)
    }

    // --- sections ---

    private fun burstFrames(): Int =
        getSystemService(AudioManager::class.java).getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: 192

    private fun playbackSection() {
        val card = section("Playback")
        val value = Ui.text(this, "", 17f, bold = true)
        fun show(ms: Int) { value.text = "Buffer target: $ms ms" }
        show(s.targetMs)
        card.addView(value)
        card.addView(Ui.text(this, "Lower = less delay, higher = fewer dropouts on busy Wi-Fi.", 14f, R.color.vcx_on_surface_variant))
        val steps = (ReceiverSettings.TARGET_MAX - ReceiverSettings.TARGET_MIN) / 5
        card.addView(SeekBar(this).apply {
            max = steps
            progress = (s.targetMs - ReceiverSettings.TARGET_MIN) / 5
            progressTintList = ColorStateList.valueOf(Ui.color(context, R.color.vcx_primary))
            thumbTintList = ColorStateList.valueOf(Ui.color(context, R.color.vcx_primary))
            minimumHeight = Ui.dp(context, 48f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val ms = ReceiverSettings.TARGET_MIN + p * 5
                    show(ms)
                    if (fromUser) save { it.copy(targetMs = ms) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        gap(card)
        val burst = burstFrames()
        label(card, "Audio buffer size", "AudioTrack buffer in hardware bursts of $burst frames. Bigger survives CPU hiccups but adds delay.")
        radioGroup(
            card,
            ReceiverSettings.TRACK_BURST_OPTIONS.map { n ->
                n.toString() to "$n × $burst frames ≈ ${n * burst * 1000 / 48000} ms" + if (n == 6) " (recommended)" else ""
            },
            s.trackBursts.toString(),
        ) { v -> save { it.copy(trackBursts = v.toInt()) } }

        gap(card)
        label(card, "Wi-Fi mode", "Wi-Fi lock held while playing.")
        radioGroup(
            card,
            listOf("low_latency" to "Low latency (recommended)", "high_perf" to "High performance (legacy)", "none" to "None"),
            s.wifiMode,
        ) { v -> save { it.copy(wifiMode = v) } }
    }

    private fun locationSection() {
        val card = section("Location")
        val granted = SetupTips.hasSecureSettingsPermission(this)
        switchRow(
            card, "Turn Location off while playing",
            "Stops Samsung's location Wi-Fi scans, which cause dropouts. Restored when you stop.",
            s.locationOff,
        ) { v -> save { it.copy(locationOff = v) }; rebuild() }
        gap(card, 8f)
        if (granted) {
            card.addView(Ui.text(this, "✓ Permission granted", 15f, R.color.vcx_ok, bold = true))
        } else {
            card.addView(Ui.text(this, "Permission not granted. Run this once from a PC with adb:", 15f, R.color.vcx_warn))
            card.addView(Ui.text(this, SetupTips.GRANT_COMMAND, 14f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                val p = Ui.dp(context, 10f)
                setPadding(p, p, p, p)
                background = Ui.roundRect(context, R.color.vcx_surface, 12f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 8f) })
            gap(card, 8f)
            card.addView(Ui.pillButton(this, "Copy command") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb grant", SetupTips.GRANT_COMMAND))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun pcSection() {
        val card = section("PC")
        val known = KnownSenders.load(PrefsStore.of(this))
        val now = System.currentTimeMillis()
        val options = ArrayList<Pair<String, String>>()
        options += ReceiverSettings.AUTO to "Any PC (auto)"
        for (k in known) {
            val ago = DateUtils.getRelativeTimeSpanString(k.lastSeenMs, now, DateUtils.MINUTE_IN_MILLIS)
            options += k.ip to "${k.name ?: "PC"} · ${k.ip} · seen $ago"
        }
        if (s.pc != ReceiverSettings.AUTO && known.none { it.ip == s.pc }) options += s.pc to "${s.pc} (saved)"
        label(card, "Receive from", "With a specific PC selected, other senders are ignored and not answered.")
        radioGroup(card, options, s.pc) { v -> save { it.copy(pc = v) } }
        if (known.isNotEmpty()) {
            gap(card, 8f)
            card.addView(Ui.pillButton(this, "Forget list") {
                KnownSenders.clear(PrefsStore.of(this))
                rebuild()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun outputSection() {
        val card = section("Output device")
        val devices = OutputDevices.list(this)
        val options = ArrayList<Pair<String, String>>()
        options += ReceiverSettings.DEFAULT_DEVICE to "Default (system routing)"
        for (d in devices) options += OutputDevices.key(d) to OutputDevices.label(d)
        if (s.outputDevice != ReceiverSettings.DEFAULT_DEVICE && devices.none { OutputDevices.key(it) == s.outputDevice }) {
            options += s.outputDevice to "Saved device (not connected)"
        }
        label(card, "Play through", "Pick the USB/aux adapter to keep audio off the phone speaker.")
        radioGroup(card, options, s.outputDevice) { v -> save { it.copy(outputDevice = v) } }
    }

    private fun tipsSection() {
        val card = section("Wi-Fi tips")
        tipsCard = card
        card.addView(Ui.text(this, "Settings that cause dropouts. The app only reads these; change them in system Settings.", 14f, R.color.vcx_on_surface_variant))
        for (t in SetupTips.tips(this, s.locationOff).filter { it.title != "Location off while playing" }) {
            gap(card, 14f)
            val (mark, colorId) = when (t.state) {
                SetupTips.State.OK -> "✓" to R.color.vcx_ok
                SetupTips.State.WARN -> "!" to R.color.vcx_warn
                SetupTips.State.UNKNOWN -> "?" to R.color.vcx_on_surface_variant
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.text(context, mark, 18f, colorId, bold = true).apply { minWidth = Ui.dp(context, 28f) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(context, t.title, 17f))
                    addView(Ui.text(context, t.detail, 14f, R.color.vcx_on_surface_variant))
                    if (t.actionLabel != null && t.action != null && t.state != SetupTips.State.OK) {
                        addView(Ui.pillButton(context, t.actionLabel) { t.action.invoke(this@SettingsActivity) },
                            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.dp(context, 6f) })
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
    }

    private fun behaviourSection() {
        val card = section("Behaviour")
        switchRow(card, "Stop when the cable is unplugged", "So music never switches to the phone speaker.", s.stopOnUnplug) { v ->
            save { it.copy(stopOnUnplug = v) }
        }
        switchRow(card, "Keep screen on in this app", "Only while the app is open.", s.keepScreenOn) { v ->
            save { it.copy(keepScreenOn = v) }
        }
    }

    private fun advancedSection() {
        val card = section("Advanced")
        switchRow(card, "Drift steering", "Tiny resampling keeps the delay constant (steer).", s.steer) { v -> save { it.copy(steer = v) } }
        switchRow(card, "Start-up alignment", "Puts the buffer on target right after start (align).", s.align) { v -> save { it.copy(align = v) } }
        switchRow(card, "Silence trim", "Fixes the buffer during silence (siltrim).", s.silTrim) { v -> save { it.copy(silTrim = v) } }
        gap(card, 8f)
        card.addView(Ui.text(this, "adb START extras (target_ms, track_bursts, wifi_mode, location_off, pc, output_device, steer, align, siltrim, write_frames) override these for one run.", 13f, R.color.vcx_on_surface_variant))
        gap(card, 8f)
        card.addView(Ui.pillButton(this, "Reset to defaults") {
            val keepPc = s.pc
            save { ReceiverSettings(pc = keepPc) }
            rebuild()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        gap(card, 8f)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        card.addView(Ui.text(this, "Version $version", 13f, R.color.vcx_on_surface_variant))
    }
}
