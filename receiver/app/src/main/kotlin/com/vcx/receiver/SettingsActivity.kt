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
        private const val RECOMMENDED_TARGET = 40
        private const val RECOMMENDED_BURSTS = 6
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

    /**
     * Labels under a SeekBar at fractions of its track. Positioned in the row's own onLayout, when the
     * row and the labels have their real widths (the row is as wide as the SeekBar above it).
     */
    private fun tickLabels(seek: SeekBar, labels: List<Pair<Float, String>>, boldIndex: Int = -1): FrameLayout {
        val fractions = labels.map { it.first }
        val row = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val w = r - l
                val track = w - seek.paddingStart - seek.paddingEnd
                for (i in 0 until childCount) {
                    val tv = getChildAt(i)
                    val x = seek.paddingStart + fractions[i] * track - tv.width / 2f
                    tv.translationX = x.coerceIn(0f, (w - tv.width).toFloat().coerceAtLeast(0f))
                }
            }
        }
        labels.forEachIndexed { i, (_, text) ->
            row.addView(
                Ui.text(this, text, 13f, if (i == boldIndex) R.color.vcx_primary else R.color.vcx_on_surface_variant, bold = i == boldIndex),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
        return row
    }

    private fun tintedSeekBar(max: Int, progress: Int) = SeekBar(this).apply {
        this.max = max
        this.progress = progress
        progressTintList = ColorStateList.valueOf(Ui.color(context, R.color.vcx_primary))
        thumbTintList = ColorStateList.valueOf(Ui.color(context, R.color.vcx_primary))
        minimumHeight = Ui.dp(context, 48f)
    }

    private fun onSeek(f: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = f(p, fromUser)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun bufferMs(bursts: Int) = bursts * burstFrames() * 1000 / 48000

    private fun playbackSection() {
        val card = section("Playback")

        // Buffer target: 20..120 ms in 5 ms steps, min/max labelled; the title says when it is the recommended 40.
        val target = Ui.text(this, "", 17f, bold = true)
        fun showTarget(ms: Int) { target.text = "Buffer target: $ms ms" + if (ms == RECOMMENDED_TARGET) " (recommended)" else "" }
        showTarget(s.targetMs)
        card.addView(target)
        card.addView(Ui.text(this, "Lower = less delay. Higher = fewer dropouts on busy Wi-Fi.", 14f, R.color.vcx_on_surface_variant))
        val targetBar = tintedSeekBar((ReceiverSettings.TARGET_MAX - ReceiverSettings.TARGET_MIN) / 5, (s.targetMs - ReceiverSettings.TARGET_MIN) / 5)
        targetBar.setOnSeekBarChangeListener(onSeek { p, fromUser ->
            val ms = ReceiverSettings.TARGET_MIN + p * 5
            showTarget(ms)
            if (fromUser) save { it.copy(targetMs = ms) }
        })
        card.addView(targetBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(
            tickLabels(
                targetBar,
                listOf(
                    0f to "${ReceiverSettings.TARGET_MIN} ms",
                    1f to "${ReceiverSettings.TARGET_MAX} ms",
                ),
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        // Audio buffer size: discrete stops labelled in ms.
        gap(card, 20f)
        val opts = ReceiverSettings.TRACK_BURST_OPTIONS
        val audio = Ui.text(this, "", 17f, bold = true)
        fun showAudio(bursts: Int) {
            audio.text = "Audio buffer: ${bufferMs(bursts)} ms" + if (bursts == RECOMMENDED_BURSTS) " (recommended)" else ""
        }
        val current = opts.indexOf(s.trackBursts).takeIf { it >= 0 } ?: opts.indexOf(RECOMMENDED_BURSTS)
        showAudio(opts[current])
        card.addView(audio)
        card.addView(Ui.text(this, "Extra safety against phone audio glitches. Bigger = more delay.", 14f, R.color.vcx_on_surface_variant))
        val audioBar = tintedSeekBar(opts.size - 1, current).apply {
            tickMark = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Ui.color(context, R.color.vcx_outline))
                val d = Ui.dp(context, 6f)
                setSize(d, d)
            }
        }
        audioBar.setOnSeekBarChangeListener(onSeek { p, fromUser ->
            showAudio(opts[p])
            if (fromUser) save { it.copy(trackBursts = opts[p]) }
        })
        card.addView(audioBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(
            tickLabels(
                audioBar,
                listOf(0, opts.indexOf(RECOMMENDED_BURSTS), opts.size - 1).map { i ->
                    i.toFloat() / (opts.size - 1) to "${bufferMs(opts[i])} ms"
                },
                boldIndex = 1,
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        gap(card, 20f)
        label(card, "Wi-Fi mode", "How hard the phone keeps Wi-Fi awake while playing.")
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
        card.addView(Ui.text(this, "Audio buffer: ${s.trackBursts} × ${burstFrames()}-frame hardware bursts = ${s.trackBursts * burstFrames()} frames " +
            "(track_bursts=${s.trackBursts}). Writes are one burst each (write_frames=${if (s.writeFrames == 0) "burst" else s.writeFrames.toString()}).",
            13f, R.color.vcx_on_surface_variant))
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
