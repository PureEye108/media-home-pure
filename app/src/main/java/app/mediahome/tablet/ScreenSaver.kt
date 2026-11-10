package app.mediahome.tablet

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import app.mediahome.tablet.remote.MediaBridge
import kotlin.random.Random

/** What is playing right now, for the screen saver. [signal] and [bitPerfect] come from Player Pure only. */
data class NowPlaying(
    val title: String, val artist: String, val album: String, val app: String,
    val playing: Boolean, val positionMs: Long, val durationMs: Long, val art: Bitmap?,
    val signal: String? = null, val bitPerfect: Boolean = false,
)

/** Where the screen saver gets its music and weather from (replaced by fixed data in the screen renders). */
interface SaverSource {
    fun nowPlaying(): NowPlaying?
    fun weather(): WeatherReport?
}

/** The real data: Android's media sessions (any music app; Player Pure adds its signal path) and the cached forecast. */
class LiveSaverSource(ctx: Context) : SaverSource {
    private val app = ctx.applicationContext
    private val bridge = MediaBridge(app)
    @Volatile private var loading = false

    override fun nowPlaying(): NowPlaying? = try { bridge.nowPlaying() } catch (e: Exception) { null }

    /** The cached forecast; refreshed in the background when older than 30 minutes (for the place found last). */
    override fun weather(): WeatherReport? {
        val r = Weather.cached(app)
        val stale = r == null || System.currentTimeMillis() - r.fetchedAt >= Weather.REFRESH_MS
        if (stale && !loading && Weather.enabled(app)) {
            loading = true
            Thread { try { Weather.refresh(app) } catch (e: Exception) { } finally { loading = false } }.start()
        }
        return r
    }
}

/** Screen saver settings and Android's own screen-saver state. */
object Saver {
    /** Android's component names for this app's screen saver (long and short form), as stored in Android's settings. */
    fun components(ctx: Context): Set<String> {
        val c = android.content.ComponentName(ctx, ScreenSaverDream::class.java)
        return setOf(c.flattenToString(), c.flattenToShortString())
    }
    val START_AFTER_MIN = listOf(1, 2, 5, 10, 15, 30)

    fun showMusic(ctx: Context) = Prefs.of(ctx).getBoolean(Prefs.SAVER_MUSIC, true)
    fun showWeather(ctx: Context) = Prefs.of(ctx).getBoolean(Prefs.SAVER_WEATHER, true) && Weather.enabled(ctx)

    /** true = Media Home is Android's screen saver and it starts while charging; false = not; null = Android doesn't say. */
    fun selected(ctx: Context): Boolean? = try {
        val cr = ctx.contentResolver
        val enabled = Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1
        val comps = Settings.Secure.getString(cr, "screensaver_components") ?: ""
        val onSleep = Settings.Secure.getInt(cr, "screensaver_activate_on_sleep", 0) == 1
        val ours = components(ctx)
        enabled && onSleep && comps.split(',').any { it.trim() in ours }
    } catch (e: Exception) {
        null
    }

    /** Android's screen timeout in minutes (the screen saver starts then, while plugged in). */
    fun startAfterMinutes(ctx: Context): Int =
        (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000).coerceAtLeast(1)

    fun canSetStartAfter(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun setStartAfter(ctx: Context, minutes: Int): Boolean = try {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000)
    } catch (e: Exception) {
        false
    }

    /** "Stay awake while charging" keeps the screen on, so the screen saver would never start. */
    fun stayAwakeWhileCharging(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0

    /** "3:07" or "1:02:03". */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

/**
 * The screen saver: a big clock and date, the weather, and the music that is playing (cover, title, artist, progress,
 * and Player Pure's signal path). Pure black background; everything drifts a little every minute so nothing stays
 * in the same place for hours. Used by Android's screen saver ([ScreenSaverDream]) and by the preview in Settings.
 */
class ScreenSaverView(ctx: Context, private val source: SaverSource) : FrameLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val stage = LinearLayout(ctx)
    private val clock = TextClock(ctx).apply {
        format24Hour = "HH:mm"; format12Hour = "h:mm"
        setTextColor(Ui.TEXT)
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        includeFontPadding = false
    }
    private val date = TextClock(ctx).apply {
        Lang.datePattern(ctx).let { format24Hour = it; format12Hour = it }
        setTextColor(Ui.TEXT_DIM)
    }
    private val weatherBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val music = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val art = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setColor(0xFF1B1D23.toInt()); cornerRadius = Ui.dp(ctx, 22).toFloat() }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = Ui.text(ctx, "", 30f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val sub = Ui.text(ctx, "", 20f, Ui.TEXT_DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Ui.TEXT)
        progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
    }
    private val pos = Ui.text(ctx, "", 16f, Ui.TEXT_DIM)
    private val dur = Ui.text(ctx, "", 16f, Ui.TEXT_DIM)
    private val appName = Ui.text(ctx, "", 16f, Ui.TEXT_DIM, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val signal = Ui.text(ctx, "", 16f, Ui.TEXT_DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val badge = Ui.text(ctx, "BIT-PERFECT", 13f, 0xFFE9D7B0.toInt(), bold = true).apply {
        letterSpacing = 0.12f
        background = GradientDrawable().apply { cornerRadius = Ui.dp(ctx, 999).toFloat(); setStroke(Ui.dp(ctx, 2), 0xFFE9D7B0.toInt()) }
        setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 3), Ui.dp(ctx, 12), Ui.dp(ctx, 3))
    }
    private var layoutKey = ""
    private var artKey: String? = null
    private var weatherAt = -1L
    private var running = false
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            update()
            if (++ticks % 60 == 0) drift()
            if (running) main.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(stage, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        update()
    }

    fun start() {
        if (running) return
        running = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        layoutKey = ""
        update()
    }

    /** Moves everything by a few dp, smoothly. */
    private fun drift() {
        val r = Ui.dp(context, 28).toFloat()
        stage.animate().translationX(Random.nextFloat() * 2 * r - r).translationY(Random.nextFloat() * 2 * r - r).setDuration(2500).start()
    }

    fun update() {
        val np = if (Saver.showMusic(context)) source.nowPlaying()?.takeIf { it.playing } else null
        val wx = if (Saver.showWeather(context)) source.weather() else null
        val landscape = resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        val key = "${np != null}|$landscape|${wx != null}"
        if (key != layoutKey) { layoutKey = key; arrange(np != null, landscape) }
        if (wx?.fetchedAt != weatherAt) { weatherAt = wx?.fetchedAt ?: -1L; renderWeather(wx, big = np == null) }
        if (np != null) renderMusic(np)
        contentDescription = buildString {
            append(context.getString(R.string.saver_name))
            if (np != null) append(". ").append(context.getString(R.string.saver_playing, np.title, np.artist))
            if (wx != null) append(". ").append("${wx.place} ${Weather.deg(wx.temp)}, ").append(context.getString(Weather.describe(wx.code)))
        }
    }

    private fun detach(v: View) { (v.parent as? android.view.ViewGroup)?.removeView(v) }

    /** Clock, date and weather; with music, the music sits beside them (landscape) or below (portrait). */
    private fun arrange(withMusic: Boolean, landscape: Boolean) {
        val ctx = context
        listOf(clock, date, weatherBox, music).forEach { detach(it) }
        stage.removeAllViews()
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        clock.textSize = when { !withMusic -> 200f; landscape -> 150f; else -> 130f }
        date.textSize = if (withMusic) 26f else 32f
        val centered = !withMusic || !landscape
        left.gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        left.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        left.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4) })
        left.addView(weatherBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, if (withMusic) 30 else 44) })
        if (!withMusic) {
            stage.orientation = LinearLayout.VERTICAL
            stage.addView(left)
            return
        }
        buildMusic(landscape)
        if (landscape) {
            stage.orientation = LinearLayout.HORIZONTAL
            stage.gravity = Gravity.CENTER_VERTICAL
            stage.addView(left, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            stage.addView(music, LinearLayout.LayoutParams(Ui.dp(ctx, 380), WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 90) })
        } else {
            stage.orientation = LinearLayout.VERTICAL
            stage.gravity = Gravity.CENTER_HORIZONTAL
            stage.addView(left, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            stage.addView(music, LinearLayout.LayoutParams(Ui.dp(ctx, 420), WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 70) })
        }
    }

    private fun buildMusic(landscape: Boolean) {
        val ctx = context
        listOf(art, title, sub, progress, pos, dur, appName, signal, badge).forEach { detach(it) }
        music.removeAllViews()
        val side = Ui.dp(ctx, if (landscape) 300 else 340)
        music.gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        music.addView(art, LinearLayout.LayoutParams(side, side))
        music.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 22) })
        music.addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4) })
        music.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(ctx, 6)).apply { topMargin = Ui.dp(ctx, 18) })
        music.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(dur, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6) })
        music.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or (if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL)
            addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = Ui.dp(ctx, 10) })
            addView(appName, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 14) })
        music.addView(signal, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6) })
        artKey = null
        listOf(title, sub, signal).forEach { it.gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL }
    }

    private fun renderMusic(np: NowPlaying) {
        val key = "${np.title}|${np.album}|${np.app}"
        if (key != artKey) {
            artKey = key
            if (np.art != null) { art.setImageBitmap(np.art); art.setPadding(0, 0, 0, 0); art.imageTintList = null; art.scaleType = ImageView.ScaleType.CENTER_CROP }
            else {
                art.setImageResource(R.drawable.ic_music); art.imageTintList = ColorStateList.valueOf(Ui.TEXT_DIM)
                art.scaleType = ImageView.ScaleType.FIT_CENTER
                val p = Ui.dp(context, 80); art.setPadding(p, p, p, p)
            }
        }
        title.text = np.title
        sub.text = listOf(np.artist, np.album).filter { it.isNotBlank() }.joinToString("  ·  ")
        progress.visibility = if (np.durationMs > 0) View.VISIBLE else View.INVISIBLE
        progress.progress = if (np.durationMs > 0) (np.positionMs * 1000 / np.durationMs).toInt().coerceIn(0, 1000) else 0
        pos.text = if (np.durationMs > 0) Saver.time(np.positionMs) else ""
        dur.text = if (np.durationMs > 0) Saver.time(np.durationMs) else ""
        appName.text = np.app
        signal.text = np.signal ?: ""
        signal.visibility = if (np.signal.isNullOrBlank()) View.GONE else View.VISIBLE
        badge.visibility = if (np.bitPerfect) View.VISIBLE else View.GONE
    }

    private fun renderWeather(r: WeatherReport?, big: Boolean) {
        val ctx = context
        weatherBox.removeAllViews()
        if (r == null) { weatherBox.visibility = View.GONE; return }
        weatherBox.visibility = View.VISIBLE
        fun icon(res: Int, sizeDp: Int, color: Int = Ui.TEXT) = ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, sizeDp), Ui.dp(ctx, sizeDp))
        }
        val s = if (big) 1.25f else 1f
        weatherBox.addView(icon(Weather.icon(r.code, r.isDay), (64 * s).toInt()))
        weatherBox.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, Weather.deg(r.temp), 44f * s, Ui.TEXT, bold = true))
            addView(Ui.text(ctx, "${ctx.getString(Weather.describe(r.code))} · ${r.place}", 18f * s, Ui.TEXT_DIM).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = Ui.dp(ctx, 300)
            })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 14); marginEnd = Ui.dp(ctx, 30) })
        for (d in r.days.drop(1).take(3)) {
            weatherBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val wrap = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }
                addView(Ui.text(ctx, Weather.dayName(d.date), 16f * s, Ui.TEXT_DIM, bold = true).apply { maxLines = 1 }, wrap())
                addView(icon(Weather.icon(d.code), (30 * s).toInt(), Ui.TEXT_DIM))
                addView(Ui.text(ctx, Weather.deg(d.max), 16f * s, Ui.TEXT).apply { maxLines = 1 }, wrap())
                addView(Ui.text(ctx, Weather.deg(d.min), 15f * s, Ui.TEXT_DIM).apply { maxLines = 1 }, wrap())
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 16) })
        }
    }
}

/**
 * Media Home as Android's screen saver ("Daydream"). Android starts it instead of turning the screen off when the tablet
 * hasn't been used for the screen timeout and is plugged in. Touching the screen ends it and shows what was open before.
 * Selected in Android's Settings > Display > Screen saver (Settings > Set up shows the way).
 */
class ScreenSaverDream : DreamService() {
    private var view: ScreenSaverView? = null

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Lang.wrap(base)) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        view = ScreenSaverView(Fit.context(this), LiveSaverSource(this)).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}

/** Settings > Screen saver > Preview: the same screen, full screen; touch to close. */
class ScreenSaverActivity : Activity() {
    private lateinit var view: ScreenSaverView

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Lang.wrap(base)); Fit.apply(this, base) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = ScreenSaverView(this, sourceOverride ?: LiveSaverSource(this))
        view.setOnClickListener { finish() }
        setContentView(view)
        window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onResume() { super.onResume(); view.start() }
    override fun onPause() { view.stop(); super.onPause() }

    companion object {
        /** Screen renders only: fixed music and weather. */
        @Volatile var sourceOverride: SaverSource? = null
    }
}
