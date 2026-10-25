package com.vcx.receiver

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Small view helpers for the code-built UI (no layout XML, no extra libraries). */
object Ui {
    fun dp(c: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
    fun color(c: Context, id: Int) = c.getColor(id)

    fun isDebuggable(c: Context) = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun text(c: Context, s: CharSequence = "", sp: Float = 16f, colorId: Int = R.color.vcx_on_surface, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color(c, colorId))
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun roundRect(c: Context, colorId: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color(c, colorId))
        cornerRadius = dp(c, radiusDp).toFloat()
    }

    /** A filled Material-style card. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(c, R.color.vcx_surface_container, 24f)
        val p = dp(c, 16f)
        setPadding(p, p, p, p)
    }

    /** Tonal pill button. */
    fun pillButton(c: Context, label: String, onClick: () -> Unit) = text(c, label, 15f, R.color.vcx_on_primary_container, bold = true).apply {
        val h = dp(c, 16f); val v = dp(c, 10f)
        setPadding(h, v, h, v)
        minHeight = dp(c, 48f)
        gravity = android.view.Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(color(c, R.color.vcx_on_primary_container) and 0x33FFFFFF),
            roundRect(c, R.color.vcx_primary_container, 24f), null,
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Pads [root] by the system bar / cutout insets (edge-to-edge is enforced on targetSdk 35+). */
    fun applySystemInsets(root: View, extraDp: Float = 0f) {
        val extra = dp(root.context, extraDp)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left + extra, i.top + extra, i.right + extra, i.bottom + extra)
            WindowInsets.CONSUMED
        }
    }
}

/**
 * The giant circular play/pause button. Draws itself (no image assets). Idle is an outlined neutral
 * circle; active states are filled with the state colour (blue waiting, green playing, amber hiccup,
 * red no Wi-Fi) so the state reads from across the room. Only touches inside the circle count.
 */
class PlayButtonView(context: Context) : View(context) {
    enum class Look { IDLE, WAITING, PLAYING, HICCUP, WARN }

    var playing = false
        set(v) { field = v; contentDescription = if (v) "Pause" else "Play"; invalidate() }
    var look = Look.IDLE
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Play"
        foreground = RippleDrawable(ColorStateList.valueOf(0x40808080), null, ShapeDrawable(OvalShape()))
        setOnTouchListener { _, e -> e.action == MotionEvent.ACTION_DOWN && !insideCircle(e.x, e.y) }
    }

    private fun radius() = min(width, height) / 2f * 0.98f

    private fun insideCircle(x: Float, y: Float): Boolean {
        val dx = x - width / 2f; val dy = y - height / 2f
        val r = radius()
        return dx * dx + dy * dy <= r * r
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return super.performClick()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val rad = radius().toInt()
        foreground?.setBounds(width / 2 - rad, height / 2 - rad, width / 2 + rad, height / 2 + rad)
    }

    override fun onDraw(canvas: Canvas) {
        val c = context
        val (fillId, glyphId) = when (look) {
            Look.IDLE -> R.color.vcx_surface_container to R.color.vcx_on_surface
            Look.WAITING -> R.color.vcx_primary to R.color.vcx_on_primary
            Look.PLAYING -> R.color.vcx_btn_ok to R.color.vcx_on_btn_ok
            Look.HICCUP -> R.color.vcx_btn_hiccup to R.color.vcx_on_btn_hiccup
            Look.WARN -> R.color.vcx_btn_warn to R.color.vcx_on_btn_warn
        }
        fill.color = Ui.color(c, fillId)
        icon.color = Ui.color(c, glyphId)
        val cx = width / 2f; val cy = height / 2f
        val r = radius()
        val stroke = r * 0.045f
        canvas.drawCircle(cx, cy, r - stroke / 2, fill)
        if (look == Look.IDLE) {
            ring.color = Ui.color(c, R.color.vcx_outline)
            ring.strokeWidth = stroke
            canvas.drawCircle(cx, cy, r - stroke / 2, ring)
        } else {
            // Soft halo inside the edge, same hue, so filled states still have a defined rim.
            ring.color = (Ui.color(c, glyphId) and 0x00FFFFFF) or 0x33000000
            ring.strokeWidth = stroke
            canvas.drawCircle(cx, cy, r - stroke * 1.5f, ring)
        }
        val s = r * 0.30f
        if (playing) {
            val w = s * 0.42f; val gap = s * 0.36f; val rr = w * 0.25f
            canvas.drawRoundRect(cx - gap - w, cy - s, cx - gap, cy + s, rr, rr, icon)
            canvas.drawRoundRect(cx + gap, cy - s, cx + gap + w, cy + s, rr, rr, icon)
        } else {
            path.reset()
            val off = s * 0.18f
            path.moveTo(cx - s * 0.8f + off, cy - s)
            path.lineTo(cx + s + off, cy)
            path.lineTo(cx - s * 0.8f + off, cy + s)
            path.close()
            canvas.drawPath(path, icon)
        }
    }
}

/**
 * Lays out the play button and the status block as one vertically centred group: the button is a
 * square of [widthFraction] of the width (shrunk if the group would not fit), then [gapDp], then the
 * status block.
 */
class CenteredGroup(context: Context, private val button: View, private val below: View,
                    private val widthFraction: Float = 0.8f, gapDp: Float = 24f) : android.view.ViewGroup(context) {
    private val gap = Ui.dp(context, gapDp)

    init {
        addView(button)
        addView(below)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        below.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val size = min((w * widthFraction).toInt(), h - below.measuredHeight - gap).coerceAtLeast(0)
        button.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        val total = button.measuredHeight + gap + below.measuredHeight
        val top = ((h - total) / 2).coerceAtLeast(0)
        val bx = (w - button.measuredWidth) / 2
        button.layout(bx, top, bx + button.measuredWidth, top + button.measuredHeight)
        val by = top + button.measuredHeight + gap
        below.layout(0, by, w, by + below.measuredHeight)
    }
}
