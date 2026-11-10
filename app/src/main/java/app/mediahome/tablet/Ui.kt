package app.mediahome.tablet

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Phones (public edition): Media Home is designed for a 10-inch tablet seen from across the room. On a smaller screen
 * everything is drawn a little smaller, as if the screen were at least [MIN_DP] dp on its short side, so the same
 * layout fits. Only the density changes; orientation and language keep following Android.
 */
object Fit {
    const val MIN_DP = 560

    /** The density override for this screen, or null on tablets (nothing changes there). */
    fun override(base: Context): Configuration? {
        val cfg = base.resources.configuration
        val sw = cfg.smallestScreenWidthDp
        if (sw <= 0 || sw >= MIN_DP || cfg.densityDpi <= 0) return null
        return Configuration().apply { densityDpi = (cfg.densityDpi * sw / MIN_DP).coerceAtLeast(120) }
    }

    /** For activities: call right after super.attachBaseContext(...). */
    fun apply(activity: android.view.ContextThemeWrapper, base: Context) { override(base)?.let { activity.applyOverrideConfiguration(it) } }

    /** For the screen saver service, which has no activity. */
    fun context(base: Context): Context = override(base)?.let { base.createConfigurationContext(it) } ?: base
}

/**
 * Shared look and widgets. Design rules: high contrast, large text, and every state is shown with
 * text or shape (outline thickness, On/Off labels) - never by colour alone. Colours are decorative.
 */
object Ui {
    const val BG_TOP = 0xFF13151C.toInt()
    const val BG_BOTTOM = 0xFF07080B.toInt()
    const val BG = 0xFF0E0F12.toInt()
    const val CARD = 0xFF1B1D23.toInt()
    const val CARD_PRESSED = 0xFF2B2F39.toInt()
    const val STROKE = 0xFF3C414D.toInt()
    const val STROKE_SOFT = 0x33FFFFFF
    const val STROKE_STRONG = 0xFFF2F3F5.toInt()
    const val TEXT = 0xFFF5F6F8.toInt()
    const val TEXT_DIM = 0xFFC3C7D0.toInt()
    const val GLASS = 0x1FFFFFFF

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun pageBackground(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))

    /** Card background: pressed/focused cards get a thick bright outline (shape change, not just colour). */
    fun cardBackground(ctx: Context, radiusDp: Int = 28): StateListDrawable {
        fun shape(fill: Int, stroke: Int, widthDp: Int) = GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(ctx, widthDp), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(CARD_PRESSED, STROKE_STRONG, 4))
            addState(intArrayOf(android.R.attr.state_focused), shape(CARD_PRESSED, STROKE_STRONG, 4))
            addState(intArrayOf(), shape(CARD, STROKE, 2))
        }
    }

    /** Gradient tile background; pressed adds a thick white outline. */
    fun tileBackground(ctx: Context, colors: IntArray, radiusDp: Int = 32): StateListDrawable {
        fun shape(strokeColor: Int, widthDp: Int) =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = dp(ctx, radiusDp).toFloat()
                setStroke(dp(ctx, widthDp), strokeColor)
            }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(STROKE_STRONG, 4))
            addState(intArrayOf(android.R.attr.state_focused), shape(STROKE_STRONG, 4))
            addState(intArrayOf(), shape(STROKE_SOFT, 1))
        }
    }

    /** Translucent "glass" background for header pills and round buttons. */
    fun glassBackground(ctx: Context, oval: Boolean): StateListDrawable {
        fun shape(fill: Int, stroke: Int, widthDp: Int) = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(ctx, 999).toFloat()
            setColor(fill)
            setStroke(dp(ctx, widthDp), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x40FFFFFF, STROKE_STRONG, 3))
            addState(intArrayOf(), shape(GLASS, STROKE_SOFT, 1))
        }
    }

    fun dialog(ctx: Context): AlertDialog.Builder =
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)

    fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        Toast.makeText(activity, R.string.could_not_open, Toast.LENGTH_LONG).show()
        false
    }

    /** Like [tryStart] but silent, for a first choice that has a fallback (some devices lack a settings page). */
    fun tryStartQuiet(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        false
    }

    fun text(ctx: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A pill-shaped header button with an icon and a text label. */
    fun pill(ctx: Context, iconRes: Int, label: String, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBackground(ctx, oval = false)
            val ph = dp(ctx, 22)
            val pv = dp(ctx, 12)
            setPadding(ph, pv, ph, pv)
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(TEXT)
            }, LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)))
            addView(text(ctx, label, 19f, bold = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 10) })
            setOnClickListener { onClick() }
        }

    /** Sends a media key (play/pause, next, previous) to whatever app is playing. No permission needed. */
    fun mediaKey(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

/**
 * A home-screen tile: large glyph, title, and the real icon + name of the app it opens.
 * The hero (Music) tile is bigger and adds previous / play-pause / next buttons.
 */
class TileView(ctx: Context, private val hero: Boolean, colors: IntArray) : LinearLayout(ctx) {
    private val glyph = ImageView(ctx)
    private val title = Ui.text(ctx, "", if (hero) 46f else 30f, bold = true)
    private val appIcon = ImageView(ctx)
    private val appLabel = Ui.text(ctx, "", if (hero) 21f else 17f, Ui.TEXT_DIM)

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        background = Ui.tileBackground(ctx, colors)
        val pad = Ui.dp(ctx, if (hero) 34 else 24)
        setPadding(pad, pad, pad, pad)

        glyph.imageTintList = ColorStateList.valueOf(Ui.TEXT)
        val g = Ui.dp(ctx, if (hero) 92 else 56)
        addView(glyph, LayoutParams(g, g))
        addView(Space(ctx), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // One line in every language: long titles ("Netzwerk-Mediathek", "ネットワークライブラリ") shrink to fit.
        title.maxLines = 1
        title.setAutoSizeTextTypeUniformWithConfiguration(16, if (hero) 46 else 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val i = Ui.dp(ctx, if (hero) 34 else 28)
        row.addView(appIcon, LayoutParams(i, i).apply { marginEnd = Ui.dp(ctx, 10) })
        appLabel.maxLines = 1
        row.addView(appLabel)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) })

        if (hero) addView(controls(ctx), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 26)
        })
    }

    private fun controls(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        fun button(icon: Int, desc: Int, key: Int, sizeDp: Int) = ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.TEXT)
            background = Ui.glassBackground(ctx, oval = true)
            val p = Ui.dp(ctx, sizeDp / 4)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(desc)
            setOnClickListener { Ui.mediaKey(ctx, key) }
        }
        val small = 64
        val big = 80
        addView(button(R.drawable.ic_prev, R.string.media_prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS, small),
            LayoutParams(Ui.dp(ctx, small), Ui.dp(ctx, small)))
        addView(button(R.drawable.ic_play_pause, R.string.media_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, big),
            LayoutParams(Ui.dp(ctx, big), Ui.dp(ctx, big)).apply { marginStart = Ui.dp(ctx, 18); marginEnd = Ui.dp(ctx, 18) })
        addView(button(R.drawable.ic_next, R.string.media_next, KeyEvent.KEYCODE_MEDIA_NEXT, small),
            LayoutParams(Ui.dp(ctx, small), Ui.dp(ctx, small)))
    }

    fun bind(iconRes: Int, titleText: String, icon: Drawable?, label: String) {
        glyph.setImageResource(iconRes)
        title.text = titleText
        appLabel.text = label
        if (icon != null) {
            appIcon.setImageDrawable(icon)
            appIcon.visibility = View.VISIBLE
        } else {
            appIcon.visibility = View.GONE
        }
        contentDescription = "$titleText. $label"
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.97f else 1f
        animate().scaleX(s).scaleY(s).setDuration(90).start()
    }
}

/**
 * Takes the Browser tile's place on the home screen while the web remote is on and no phone or PC has it open:
 * the sign-in QR code (address + PIN), with a button that still opens the browser.
 * The code is drawn at the size it is shown, so its edges stay sharp.
 */
class QrTileView(ctx: Context, colors: IntArray, onBrowser: () -> Unit) : LinearLayout(ctx) {
    private val title = Ui.text(ctx, ctx.getString(R.string.qr_tile_title), 30f, bold = true)
    private val sub = Ui.text(ctx, ctx.getString(R.string.qr_tile_sub), 16f, Ui.TEXT_DIM)
    private val where = Ui.text(ctx, "", 14f, Ui.TEXT_DIM)
    private val qr = ImageView(ctx)
    private val qrFrame = SquareFrame(ctx)
    val browserButton: View
    private var link: String? = null
    private var drawn: Pair<String, Int>? = null

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        background = Ui.tileBackground(ctx, colors)
        val pad = Ui.dp(ctx, 24)
        setPadding(pad, pad, pad, pad)

        // Top: "Remote" and a compact button that still opens the browser.
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.maxLines = 1
        title.setAutoSizeTextTypeUniformWithConfiguration(16, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        top.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        browserButton = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.glassBackground(ctx, oval = false)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
            isClickable = true
            isFocusable = true
            val label = ctx.getString(R.string.tile_browser)
            contentDescription = label
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_browser)
                imageTintList = ColorStateList.valueOf(Ui.TEXT)
            }, LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22)))
            addView(Ui.text(ctx, label, 16f, bold = true).apply { maxLines = 1 },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 8) })
            setOnClickListener { onBrowser() }
        }
        top.addView(browserButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 10) })
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Below: the words on the left, the code on the right, as big as the tile allows.
        val body = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val words = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.BOTTOM }
        sub.maxLines = 5
        words.addView(sub, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        where.maxLines = 3
        words.addView(where, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10) })
        body.addView(words, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        qrFrame.background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = Ui.dp(ctx, 14).toFloat() }
        val inset = Ui.dp(ctx, 6)
        qrFrame.setPadding(inset, inset, inset, inset)   // keeps the code's square corners inside the rounded frame
        qr.scaleType = ImageView.ScaleType.CENTER
        qr.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        qrFrame.addView(qr, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(qrFrame, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginStart = Ui.dp(ctx, 16) })
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(ctx, 12) })
        qrFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { draw() } }
    }

    /** [link] is the sign-in link inside the code; [address] and [pin] are shown as text for typing on a PC. */
    fun bind(link: String, address: String, pin: String) {
        val w = context.getString(R.string.qr_tile_where, address.removePrefix("http://"), pin)
        if (link == this.link && w == where.text.toString()) return
        this.link = link
        where.text = w
        contentDescription = context.getString(R.string.qr_tile_desc, address, pin)
        draw()
    }

    private fun draw() {
        val l = link ?: return
        val side = qrFrame.width - qrFrame.paddingLeft - qrFrame.paddingRight
        if (side <= 0 || drawn == l to side) return
        drawn = l to side
        qr.setImageBitmap(Qr.bitmap(l, side))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.97f else 1f
        animate().scaleX(s).scaleY(s).setDuration(90).start()
    }

    /** As tall as the space allows, but at most a little over half its width, and always square. */
    private class SquareFrame(ctx: Context) : android.widget.FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val side = minOf(h, (w * 0.56f).toInt()).coerceAtLeast(0)
            val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            super.onMeasure(exact, exact)
        }
    }
}

/** A simple scrolling settings-style page with a Back button, section headings and large rows. */
class Page(private val activity: Activity, titleText: String) {
    private val ctx: Context = activity
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val root: View

    init {
        val pad = Ui.dp(ctx, 28)
        list.setPadding(pad, pad, pad, pad)
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = Ui.text(ctx, "‹  " + ctx.getString(R.string.back), 22f, bold = true).apply {
            background = Ui.cardBackground(ctx, 18)
            val p = Ui.dp(ctx, 14)
            setPadding(p * 2, p, p * 2, p)
            isClickable = true
            isFocusable = true
            setOnClickListener { activity.finish() }
        }
        header.addView(back)
        header.addView(Ui.text(ctx, titleText, 34f, bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 24) })
        list.addView(header)
        root = ScrollView(ctx).apply {
            background = Ui.pageBackground()
            isFillViewport = true
            addView(list)
        }
    }

    fun section(textRes: Int) {
        list.addView(Ui.text(ctx, ctx.getString(textRes).uppercase(), 16f, Ui.TEXT_DIM, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 32); bottomMargin = Ui.dp(ctx, 10)
            })
    }

    fun paragraph(text: CharSequence) {
        list.addView(Ui.text(ctx, text, 19f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 12)
            })
    }

    private fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.cardBackground(ctx, 20)
        val p = Ui.dp(ctx, 20)
        setPadding(p, p, p, p)
        isClickable = true
        isFocusable = true
    }

    private fun addCard(card: View) {
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(ctx, 12)
        })
    }

    private fun texts(title: String, sub: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.text(ctx, title, 21f, bold = true))
        if (sub != null) addView(Ui.text(ctx, sub, 16f, Ui.TEXT_DIM))
    }

    fun row(title: String, sub: String?, onClick: () -> Unit) {
        val c = card()
        c.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(Ui.text(ctx, "›", 30f, Ui.TEXT_DIM))
        c.setOnClickListener { onClick() }
        c.contentDescription = if (sub != null) "$title. $sub" else title
        addCard(c)
    }

    fun row(titleRes: Int, subRes: Int?, onClick: () -> Unit) =
        row(ctx.getString(titleRes), subRes?.let { ctx.getString(it) }, onClick)

    /** A switch row. The current state is always written out as "On" / "Off". */
    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(titleRes: Int, subRes: Int?, prefKey: String, default: Boolean = false, onChange: (Boolean) -> Unit = {}) {
        val prefs = Prefs.of(ctx)
        val c = card()
        val title = ctx.getString(titleRes)
        val sub = subRes?.let { ctx.getString(it) }
        val state = Ui.text(ctx, "", 18f, Ui.TEXT, bold = true)
        val sw = Switch(ctx).apply { isChecked = prefs.getBoolean(prefKey, default); isClickable = false; isFocusable = false }
        fun render() {
            state.text = ctx.getString(if (sw.isChecked) R.string.on else R.string.off)
            c.contentDescription = "$title. ${state.text}"
        }
        c.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = Ui.dp(ctx, 12)
        })
        c.addView(sw)
        c.setOnClickListener {
            sw.isChecked = !sw.isChecked
            prefs.edit().putBoolean(prefKey, sw.isChecked).apply()
            render()
            onChange(sw.isChecked)
        }
        render()
        addCard(c)
    }

    /** A card with a picture on the left (the QR code) and a title and explanation on the right. Not clickable. */
    fun picture(bmp: Bitmap, sizePx: Int, title: String, sub: String) {
        val c = card().apply { isClickable = false; isFocusable = false }
        c.addView(ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER   // no resampling: keeps the QR modules sharp
            contentDescription = title
        }, LinearLayout.LayoutParams(sizePx, sizePx))
        c.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(ctx, 24)
        })
        addCard(c)
    }

    /**
     * A numbered set-up step: a round badge (the number, or a tick when done), the title, why it is useful,
     * and its state written out ("Done" / "Not yet - tap to set up", or [state] when given). Tapping opens the step.
     */
    fun step(number: Int, title: String, why: String, done: Boolean, state: String? = null, onClick: () -> Unit) {
        val c = card()
        val badge = Ui.text(ctx, if (done) "\u2713" else number.toString(), 20f, if (done) Ui.BG else Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(Ui.TEXT) else { setColor(Ui.CARD); setStroke(Ui.dp(ctx, 2), Ui.TEXT_DIM) }
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = Ui.dp(ctx, 40)
        c.addView(badge, LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(ctx, 16) })
        val status = state ?: ctx.getString(if (done) R.string.setup_done else R.string.setup_todo)
        val texts = texts(title, why)
        texts.addView(Ui.text(ctx, status, 17f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6) })
        c.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(Ui.text(ctx, "\u203a", 30f, Ui.TEXT_DIM))
        c.setOnClickListener { onClick() }
        c.contentDescription = "$number. $title. $status. $why"
        addCard(c)
    }

    fun info(lines: List<String>) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 20).toFloat(); setColor(Ui.CARD); setStroke(Ui.dp(ctx, 2), Ui.STROKE)
            }
            val p = Ui.dp(ctx, 20)
            setPadding(p, p, p, p)
        }
        for (l in lines) box.addView(Ui.text(ctx, l, 17f, Ui.TEXT).apply { setTextIsSelectable(true) })
        addCard(box)
    }
}
