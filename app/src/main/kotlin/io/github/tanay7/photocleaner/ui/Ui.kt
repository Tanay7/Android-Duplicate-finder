package io.github.tanay7.photocleaner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Material You colours, taken from the wallpaper, for the light or dark theme. */
class Palette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val c = { light: Int, night: Int -> ctx.getColor(if (dark) night else light) }
    val primary = c(android.R.color.system_accent1_600, android.R.color.system_accent1_200)
    val onPrimary = c(android.R.color.system_accent1_0, android.R.color.system_accent1_800)
    val primaryContainer = c(android.R.color.system_accent1_100, android.R.color.system_accent1_700)
    val onPrimaryContainer = c(android.R.color.system_accent1_900, android.R.color.system_accent1_100)
    val secondaryContainer = c(android.R.color.system_accent2_100, android.R.color.system_accent2_700)
    val onSecondaryContainer = c(android.R.color.system_accent2_900, android.R.color.system_accent2_100)
    val background = c(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900)
    val surface = c(android.R.color.system_neutral1_50, android.R.color.system_neutral1_800)
    val surfaceHigh = c(android.R.color.system_neutral2_100, android.R.color.system_neutral2_700)
    val onSurface = c(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100)
    val onSurfaceVariant = c(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200)
    val outline = c(android.R.color.system_neutral2_400, android.R.color.system_neutral2_500)
    val danger = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    val onDanger = if (dark) 0xFF601410.toInt() else Color.WHITE
    val keep = if (dark) 0xFF6DD58C.toInt() else 0xFF146C2E.toInt()
    val onKeep = if (dark) 0xFF00391A.toInt() else Color.WHITE
    val ripple = if (dark) 0x33FFFFFF else 0x1F000000
}

/** Small builders for the app's views, so every screen looks alike. */
class Ui(val ctx: Context) {
    val c = Palette(ctx)
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).roundToInt()
    fun dp(v: Float): Float = v * density

    fun text(s: CharSequence = "", sp: Float = 14f, color: Int = c.onSurface, weight: Int = 400, lines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
        if (strokeDp > 0) setStroke(dp(strokeDp).roundToInt().coerceAtLeast(1), strokeColor)
    }

    /** [content] with a ripple when touched, clipped to the same rounded corners. */
    fun pressable(content: Drawable?, radiusDp: Float, rippleColor: Int = c.ripple): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    enum class Style { FILLED, TONAL, TEXT, DANGER }

    fun button(label: String, style: Style = Style.FILLED, onClick: () -> Unit): TextView {
        val (bg, fg) = when (style) {
            Style.FILLED -> c.primary to c.onPrimary
            Style.TONAL -> c.secondaryContainer to c.onSecondaryContainer
            Style.TEXT -> Color.TRANSPARENT to c.primary
            Style.DANGER -> c.danger to c.onDanger
        }
        return text(label, 14f, fg, 500).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minimumHeight = dp(40)
            val h = if (style == Style.TEXT) 12 else 24
            setPadding(dp(h), dp(10), dp(h), dp(10))
            val content = when (style) {
                Style.TEXT -> null
                else -> rounded(bg, 20f)
            }
            background = pressable(content, 20f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.38f
    }

    fun icon(res: Int, tint: Int = c.onSurfaceVariant, desc: String? = null, sizeDp: Int = 48, padDp: Int = 12, onClick: (() -> Unit)? = null) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            contentDescription = desc
            if (onClick != null) {
                background = pressable(null, sizeDp / 2f)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            } else {
                importantForAccessibility = if (desc == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        }

    fun vbox(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    fun hbox(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    fun card(color: Int = c.surface) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 24f)
        setPadding(dp(20), dp(18), dp(20), dp(18))
    }

    fun space(wDp: Int = 0, hDp: Int = 0, weight: Float = 0f) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else dp(wDp), dp(hDp), weight)
    }

    /** A screen's top bar: optional back arrow, title and subtitle, then action icons. */
    fun topBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, vararg actions: View): TopBar {
        val t = text(title, 22f, c.onSurface, 500, lines = 1)
        val s = text(subtitle ?: "", 13f, c.onSurfaceVariant, lines = 1)
        s.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        val bar = hbox().apply {
            minimumHeight = dp(64)
            setPadding(dp(if (onBack != null) 4 else 20), dp(4), dp(4), dp(4))
        }
        if (onBack != null) bar.addView(icon(io.github.tanay7.photocleaner.R.drawable.ic_arrow_back, c.onSurface, "Back", onClick = onBack))
        bar.addView(vbox(t, s).lp(0, WRAP, 1f, l = if (onBack != null) dp(4) else 0))
        actions.forEach { bar.addView(it) }
        return TopBar(bar, t, s)
    }

    /**
     * A screen's bottom bar: a summary and the main button, stacked, or side by side when the screen
     * is short (a phone on its side), so the list keeps most of the height.
     */
    inner class BottomBar(val summary: TextView, val button: TextView) {
        val view = LinearLayout(ctx).apply { background = rounded(c.surface, 0f) }

        fun layout() {
            val short = ctx.resources.configuration.screenHeightDp < 480
            view.removeAllViews()
            if (short) {
                view.orientation = LinearLayout.HORIZONTAL
                view.gravity = Gravity.CENTER_VERTICAL
                view.setPadding(dp(20), dp(6), dp(16), dp(6))
                summary.maxLines = 2
                view.addView(summary.lp(0, WRAP, 1f, r = dp(16)))
                view.addView(button.lp(WRAP, dp(48)))
            } else {
                view.orientation = LinearLayout.VERTICAL
                view.gravity = Gravity.NO_GRAVITY
                view.setPadding(dp(20), dp(12), dp(20), dp(12))
                summary.maxLines = 3
                view.addView(summary.lp(b = dp(10)))
                view.addView(button.lp(MATCH, dp(52)))
            }
        }

        init {
            layout()
        }
    }

    class TopBar(val view: LinearLayout, val title: TextView, val subtitle: TextView) {
        fun set(title: String, subtitle: String?) {
            this.title.text = title
            this.subtitle.text = subtitle ?: ""
            this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}

/** Linear-layout parameters, margins in pixels. */
fun <T : View> T.lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, gravity: Int = -1): T {
    layoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
        setMargins(l, t, r, b)
        if (gravity != -1) this.gravity = gravity
    }
    return this
}

/** Frame-layout parameters, margins in pixels. */
fun <T : View> T.flp(w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.NO_GRAVITY, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T {
    layoutParams = FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(l, t, r, b) }
    return this
}
