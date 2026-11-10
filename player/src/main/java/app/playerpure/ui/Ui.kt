package app.playerpure.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.playerpure.R

/**
 * Phones (Media Home Pure): Player Pure is laid out for a 10-inch tablet. On a smaller screen it is drawn a little smaller,
 * as if the short side were at least [MIN_DP] dp, so the same layout fits. Only the density changes.
 */
object Fit {
    const val MIN_DP = 560
    fun override(base: android.content.Context): android.content.res.Configuration? {
        val cfg = base.resources.configuration
        val sw = cfg.smallestScreenWidthDp
        if (sw <= 0 || sw >= MIN_DP || cfg.densityDpi <= 0) return null
        return android.content.res.Configuration().apply { densityDpi = (cfg.densityDpi * sw / MIN_DP).coerceAtLeast(120) }
    }
}

/**
 * Look and widgets. Dark, calm, large type for a tablet across the room. Every state is shown with text or shape
 * (outlines, labels such as "Bit-perfect"), never with colour alone.
 */
object Ui {
    const val BG = 0xFF0B0C10.toInt()
    const val BG2 = 0xFF101218.toInt()
    const val SURFACE = 0xFF15171D.toInt()
    const val SURFACE2 = 0xFF1E2129.toInt()
    const val PRESSED = 0xFF2A2E39.toInt()
    const val STROKE = 0xFF343844.toInt()
    const val TEXT = 0xFFF4F5F7.toInt()
    const val DIM = 0xFFB4B9C4.toInt()
    const val FAINT = 0xFF7D8391.toInt()
    const val ACCENT = 0xFFE9D7B0.toInt()
    const val ON_ACCENT = 0xFF17130B.toInt()

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Number, stroke: Int = 0, strokeDp: Number = 0) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat(); setColor(fill)
        if (strokeDp.toFloat() > 0) setStroke(dp(ctx, strokeDp), stroke)
    }

    /** Pressed and focused states get a thick light outline. */
    fun pressable(ctx: Context, fill: Int, radiusDp: Number, stroke: Int = STROKE, strokeDp: Number = 0): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, PRESSED, radiusDp, TEXT, 2))
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, PRESSED, radiusDp, TEXT, 2))
            addState(intArrayOf(), rounded(ctx, fill, radiusDp, stroke, strokeDp))
        }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = TEXT, face: Typeface = REGULAR, lines: Int = 1): TextView =
        TextView(ctx).apply {
            text = s; textSize = sp; setTextColor(color); typeface = face
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            includeFontPadding = false
        }

    /** Small capital label above a group. */
    fun label(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), 13f, FAINT, MEDIUM).apply { letterSpacing = 0.12f }

    fun icon(ctx: Context, res: Int, sizeDp: Int, tint: Int = TEXT): ImageView = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    /** Round icon button with a text description for TalkBack. */
    fun roundButton(ctx: Context, res: Int, desc: String, sizeDp: Int, iconDp: Int, filled: Boolean = false, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            val fill = if (filled) ACCENT else 0x00000000
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (filled) 0xFFF6EBD3.toInt() else PRESSED); setStroke(dp(ctx, 2), TEXT) })
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (filled) 0xFFF6EBD3.toInt() else PRESSED); setStroke(dp(ctx, 2), TEXT) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) })
            }
            contentDescription = desc
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            addView(icon(ctx, res, iconDp, if (filled) ON_ACCENT else TEXT).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, iconDp), dp(ctx, iconDp), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    /** Pill button with an optional icon. [selected] draws an outline and bold text. */
    fun pill(ctx: Context, label: String, iconRes: Int? = null, primary: Boolean = false, selected: Boolean = false, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val h = dp(ctx, 48)
            minimumHeight = h
            setPadding(dp(ctx, if (iconRes != null) 16 else 20), 0, dp(ctx, 20), 0)
            background = when {
                primary -> pressable(ctx, ACCENT, 999)
                selected -> pressable(ctx, SURFACE2, 999, TEXT, 2)
                else -> pressable(ctx, SURFACE2, 999, STROKE, 1)
            }
            val fg = if (primary) ON_ACCENT else TEXT
            if (iconRes != null) addView(icon(ctx, iconRes, 22, fg).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(ctx, 8) })
            addView(text(ctx, label, 16f, fg, if (selected || primary) MEDIUM else REGULAR))
            isClickable = true; isFocusable = true
            contentDescription = if (selected) "$label, selected" else label
            setOnClickListener { onClick() }
        }

    /** Square cover with rounded corners. */
    fun cover(ctx: Context, radiusDp: Int): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        val r = dp(ctx, radiusDp).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) }
        }
        clipToOutline = true
        setBackgroundColor(SURFACE2)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Placeholder cover: a soft gradient from the album name with a note symbol. */
    fun placeholder(ctx: Context, seed: String, sizePx: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val h = (seed.hashCode() and 0x7fffffff) % 360
        val c1 = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.35f, 0.32f))
        val c2 = android.graphics.Color.HSVToColor(floatArrayOf(((h + 40) % 360).toFloat(), 0.45f, 0.16f))
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), c1, c2, Shader.TileMode.CLAMP) })
        ctx.getDrawable(R.drawable.pp_ic_note)?.let { d ->
            val s = sizePx / 3
            d.setTint(0x66FFFFFF); d.setBounds((sizePx - s) / 2, (sizePx - s) / 2, (sizePx + s) / 2, (sizePx + s) / 2); d.draw(c)
        }
        return b
    }

    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
