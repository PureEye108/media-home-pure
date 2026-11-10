package app.playerpure.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import app.playerpure.Hub
import app.playerpure.R
import app.playerpure.audio.Player
import app.playerpure.library.Covers
import app.playerpure.library.Track
import app.playerpure.ui.Ui.MATCH
import app.playerpure.ui.Ui.WRAP
import app.playerpure.ui.Ui.dp
import app.playerpure.ui.Ui.lp

/** A screen inside the main window (Albums, an album, Settings...). */
abstract class Page(val act: MainActivity) {
    abstract val view: View
    abstract val nav: Int
    open fun refresh() {}
    /** Hub state changed (DAC, settings, scanning). Pages with live parts can update in place instead of rebuilding. */
    open fun onHubChanged() = refresh()
}

/** Player Pure's single landscape window: navigation rail, pages, the now-playing bar and the full Now Playing view. */
class MainActivity : Activity(), Player.Listener {

    /** Phones: draw the tablet layout a little smaller so it fits (see [Fit]). */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        Fit.override(base)?.let { applyOverrideConfiguration(it) }
    }
    private lateinit var content: FrameLayout
    private lateinit var rail: LinearLayout
    private val railItems = ArrayList<LinearLayout>()
    private val stack = ArrayList<Page>()
    private lateinit var bar: NowBar
    private var nowPlaying: NowPlayingView? = null
    private lateinit var root: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    private val hubListener: () -> Unit = { stack.lastOrNull()?.onHubChanged(); bar.update(); nowPlaying?.update() }

    companion object {
        const val NAV_ALBUMS = 0; const val NAV_ARTISTS = 1; const val NAV_FOLDERS = 2; const val NAV_QUEUE = 3; const val NAV_DAC = 4; const val NAV_SETTINGS = 5
        const val EXTRA_PAGE = "page"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        root = FrameLayout(this)
        val shell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail = buildRail()
        shell.addView(rail, LinearLayout.LayoutParams(dp(this, 112), MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        column.addView(content, lp(MATCH, 0, 1f))
        bar = NowBar(this)
        column.addView(bar.view, lp(MATCH, dp(this, 92)))
        shell.addView(column, lp(0, MATCH, 1f))
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG2, Ui.BG))
        setContentView(root)
        window.insetsController?.let { it.hide(WindowInsets.Type.statusBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }

        when (intent?.getIntExtra(EXTRA_PAGE, NAV_ALBUMS)) {
            NAV_SETTINGS -> show(SettingsPage(this), true)
            NAV_DAC -> show(DacPage(this), true)
            NAV_QUEUE -> show(QueuePage(this), true)
            else -> show(AlbumsPage(this), true)
        }
        if (hasFileAccess() && Hub.library.counts().first == 0 && Hub.scanning == null && !Hub.prefs.getBoolean("scanned_once", false)) {
            Hub.prefs.edit().putBoolean("scanned_once", true).apply(); Hub.scan()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Hub.connectDac(ask = false)
    }

    override fun onResume() {
        super.onResume()
        Hub.player.listeners += this
        Hub.listeners += hubListener
        Hub.connectDac(ask = true)
        if (Hub.player.playing) app.playerpure.PlaybackServiceStarter.start(this)   // e.g. started from the phone remote
        onPlayerChanged()
        stack.lastOrNull()?.refresh()
        main.post(ticker)
    }

    override fun onPause() {
        Hub.player.listeners -= this
        Hub.listeners -= hubListener
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() { bar.tick(); nowPlaying?.tick(); main.postDelayed(this, 500) }
    }

    override fun onPlayerChanged() {
        bar.update()
        nowPlaying?.update()
        (stack.lastOrNull() as? QueuePage)?.refresh()
        (stack.lastOrNull() as? AlbumPage)?.refresh()
    }

    fun hasFileAccess(): Boolean = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }

    fun askFileAccess() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    // ------------------------------------------------------------ navigation
    private fun buildRail(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(this@MainActivity, 20), 0, dp(this@MainActivity, 16))
        setBackgroundColor(0x40000000)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.pp_ic_launcher_foreground); contentDescription = "Player Pure"
        }, lp(dp(this@MainActivity, 64), dp(this@MainActivity, 64)))
        addView(Ui.text(this@MainActivity, "Player\nPure", 12f, Ui.ACCENT, Ui.MEDIUM, 2).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f },
            lp(WRAP, WRAP).apply { bottomMargin = dp(this@MainActivity, 18) })
        val items = listOf(
            Triple(R.drawable.pp_ic_album, "Albums") { show(AlbumsPage(this@MainActivity), true) },
            Triple(R.drawable.pp_ic_artist, "Artists") { show(ArtistsPage(this@MainActivity), true) },
            Triple(R.drawable.pp_ic_folder, "Folders") { show(FoldersPage(this@MainActivity, null), true) },
            Triple(R.drawable.pp_ic_queue, "Queue") { show(QueuePage(this@MainActivity), true) },
            Triple(R.drawable.pp_ic_usb, "DAC") { show(DacPage(this@MainActivity), true) },
            Triple(R.drawable.pp_ic_tune, "Settings") { show(SettingsPage(this@MainActivity), true) },
        )
        for ((icon, label, go) in items) {
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true; contentDescription = label
                setOnClickListener { go() }
                addView(Ui.icon(this@MainActivity, icon, 28))
                addView(Ui.text(this@MainActivity, label, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = dp(this@MainActivity, 6) })
            }
            railItems += item
            addView(item, lp(dp(this@MainActivity, 92), dp(this@MainActivity, 84)).apply { bottomMargin = dp(this@MainActivity, 6) })
        }
    }

    private fun markRail(nav: Int) {
        railItems.forEachIndexed { i, v ->
            val sel = i == nav
            v.background = if (sel) Ui.pressable(this, Ui.SURFACE2, 22, Ui.ACCENT, 2) else Ui.pressable(this, 0, 22)
            (v.getChildAt(1) as TextView).apply { setTextColor(if (sel) Ui.TEXT else Ui.DIM); typeface = if (sel) Ui.MEDIUM else Ui.REGULAR }
            v.contentDescription = (v.getChildAt(1) as TextView).text.toString() + if (sel) ", selected" else ""
        }
    }

    fun show(p: Page, asRoot: Boolean = false) {
        hideNowPlaying()
        if (asRoot) stack.clear()
        stack += p
        content.removeAllViews()
        content.addView(p.view, FrameLayout.LayoutParams(MATCH, MATCH))
        markRail(p.nav)
        p.refresh()
    }

    fun back(): Boolean {
        if (nowPlaying?.view?.visibility == View.VISIBLE) { hideNowPlaying(); return true }
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        val p = stack.last()
        content.removeAllViews()
        content.addView(p.view, FrameLayout.LayoutParams(MATCH, MATCH))
        markRail(p.nav); p.refresh()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!back()) @Suppress("DEPRECATION") super.onBackPressed() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { Hub.player.toggle(); true }
        KeyEvent.KEYCODE_MEDIA_NEXT -> { Hub.player.next(); true }
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Hub.player.prev(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    fun showNowPlaying() {
        val np = nowPlaying ?: NowPlayingView(this).also { nowPlaying = it; root.addView(it.view, FrameLayout.LayoutParams(MATCH, MATCH)) }
        np.view.visibility = View.VISIBLE
        np.update(); np.tick()
    }

    fun hideNowPlaying() { nowPlaying?.view?.visibility = View.GONE }

    /** Starts playing [tracks] from [start] and opens Now Playing. */
    fun play(tracks: List<Track>, start: Int = 0, open: Boolean = false) {
        if (tracks.isEmpty()) return
        Hub.player.play(tracks, start)
        if (open) main.postDelayed({ showNowPlaying() }, 150)
    }
}

/** The bar at the bottom: what's playing, transport, and the signal path. */
class NowBar(private val act: MainActivity) {
    private val ctx = act
    private val coverView = Ui.cover(ctx, 10)
    private val title = Ui.text(ctx, "", 17f, Ui.TEXT, Ui.MEDIUM)
    private val artist = Ui.text(ctx, "", 14f, Ui.DIM)
    private val format = Ui.text(ctx, "", 14f, Ui.DIM).apply { gravity = Gravity.END }
    private val badge = Ui.text(ctx, "", 12f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.1f; setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4)) }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.STROKE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val play = Ui.roundButton(ctx, R.drawable.pp_ic_play, "Play", 60, 32, filled = true) { Hub.player.toggle() }
    private var shownId = -2L

    val view: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.SURFACE)
        addView(progress, lp(MATCH, dp(ctx, 3)))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), 0, dp(ctx, 24), 0)
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true; contentDescription = "Open Now Playing"
                background = Ui.pressable(ctx, 0, 14)
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                setOnClickListener { act.showNowPlaying() }
                addView(coverView, lp(dp(ctx, 64), dp(ctx, 64)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title); addView(artist, lp(WRAP, WRAP).apply { topMargin = dp(ctx, 4) })
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(ctx, 14) })
            }
            addView(left, lp(0, MATCH, 1f))
            addView(Ui.roundButton(ctx, R.drawable.pp_ic_prev, "Previous", 52, 30) { Hub.player.prev() })
            addView(play, lp(dp(ctx, 60), dp(ctx, 60)).apply { leftMargin = dp(ctx, 10); rightMargin = dp(ctx, 10) })
            addView(Ui.roundButton(ctx, R.drawable.pp_ic_next, "Next", 52, 30) { Hub.player.next() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(format, lp(WRAP, WRAP))
                addView(badge, lp(WRAP, WRAP).apply { topMargin = dp(ctx, 6) })
            }, lp(0, MATCH, 1f))
        }, lp(MATCH, 0, 1f))
    }

    fun update() {
        val p = Hub.player
        val t = p.current()
        title.text = t?.title ?: "Nothing playing"
        artist.text = t?.let { "${it.displayArtist} · ${it.album}" } ?: "Choose an album to start"
        (play.getChildAt(0) as ImageView).setImageResource(if (p.playing) R.drawable.pp_ic_pause else R.drawable.pp_ic_play)
        play.contentDescription = if (p.playing) "Pause" else "Play"
        format.text = if (t == null) "" else if (p.outputLine.isNotEmpty() && p.playing) p.outputLine else t.formatLabel
        val (txt, outline) = when {
            t == null -> "" to false
            p.bitPerfect -> "BIT-PERFECT · USB" to true
            p.usbActive -> "USB · NOT BIT-PERFECT" to false
            else -> "ANDROID AUDIO" to false
        }
        badge.text = txt
        badge.setTextColor(if (outline) Ui.ACCENT else Ui.DIM)
        badge.background = if (txt.isEmpty()) null else Ui.rounded(ctx, 0, 999, if (outline) Ui.ACCENT else Ui.STROKE, if (outline) 1.5f else 1f)
        if (t?.id != shownId) {
            shownId = t?.id ?: -1
            if (t == null) coverView.setImageDrawable(null)
            else Covers.load(coverView, t.art, t.path, dp(ctx, 64)) { it.setImageBitmap(Ui.placeholder(ctx, t.album, dp(ctx, 64))) }
        }
        tick()
    }

    fun tick() {
        val p = Hub.player
        val d = p.durationMs()
        progress.progress = if (d > 0) (p.positionMs() * 1000 / d).toInt().coerceIn(0, 1000) else 0
    }
}

/** Full-screen Now Playing: large cover, signal path, seek bar and big controls. */
class NowPlayingView(private val act: MainActivity) {
    private val ctx = act
    private val bgArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val coverView = Ui.cover(ctx, 22)
    private val title = Ui.text(ctx, "", 36f, Ui.TEXT, Ui.MEDIUM, 2)
    private val artist = Ui.text(ctx, "", 22f, Ui.TEXT)
    private val album = Ui.text(ctx, "", 18f, Ui.DIM)
    private val source = Ui.text(ctx, "", 17f, Ui.TEXT, Ui.MEDIUM)
    private val output = Ui.text(ctx, "", 15f, Ui.DIM, lines = 2)
    private val badge = Ui.text(ctx, "", 13f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.12f; setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5)) }
    private val pos = Ui.text(ctx, "0:00", 15f, Ui.DIM)
    private val dur = Ui.text(ctx, "0:00", 15f, Ui.DIM)
    private val upNext = Ui.text(ctx, "", 15f, Ui.DIM)
    private var dragging = false
    private val seek = SeekBar(ctx).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        contentDescription = "Position"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) pos.text = Ui.time(Hub.player.durationMs() * v / 1000) }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { dragging = false; Hub.player.seekTo(Hub.player.durationMs() * s.progress / 1000) }
        })
    }
    private val play = Ui.roundButton(ctx, R.drawable.pp_ic_play, "Play", 96, 48, filled = true) { Hub.player.toggle() }
    private val shuffle = Ui.roundButton(ctx, R.drawable.pp_ic_shuffle, "Shuffle", 60, 28) { Hub.player.setShuffle(!Hub.player.shuffle) }
    private val repeat = Ui.roundButton(ctx, R.drawable.pp_ic_repeat, "Repeat", 60, 28) { Hub.player.setRepeat((Hub.player.repeatMode + 1) % 3) }
    private val volumeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val volumeLabel = Ui.text(ctx, "", 14f, Ui.DIM)
    private val volume = SeekBar(ctx).apply {
        max = 100; contentDescription = "Volume"
        progressTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
        thumbTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) Hub.volume = v / 100f }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
    private var shownId = -2L

    val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(Ui.BG)
        isClickable = true
        addView(bgArt, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE6000000.toInt(), 0xCC0B0C10.toInt(), 0xF20B0C10.toInt())) },
            FrameLayout.LayoutParams(MATCH, MATCH))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 56), dp(ctx, 40), dp(ctx, 56), dp(ctx, 40))
        }
        row.addView(FitSquare(ctx).apply { addView(coverView) }, lp(0, MATCH, 0.9f))
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        info.addView(Ui.label(ctx, "Now playing"))
        info.addView(title, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 10) })
        info.addView(artist, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 10) })
        info.addView(album, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 6) })
        info.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, 0x33FFFFFF, 18, 0x33FFFFFF, 1)
            setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(source, lp(0, WRAP, 1f)); addView(badge)
            })
            addView(output, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 6) })
        }, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 24) })
        info.addView(seek, lp(MATCH, dp(ctx, 40)).apply { topMargin = dp(ctx, 20) })
        info.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            addView(pos, lp(0, WRAP, 1f)); addView(dur)
        })
        info.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(shuffle)
            addView(Ui.roundButton(ctx, R.drawable.pp_ic_prev, "Previous", 76, 40) { Hub.player.prev() }, lp(dp(ctx, 76), dp(ctx, 76)).apply { leftMargin = dp(ctx, 18) })
            addView(play, lp(dp(ctx, 96), dp(ctx, 96)).apply { leftMargin = dp(ctx, 18); rightMargin = dp(ctx, 18) })
            addView(Ui.roundButton(ctx, R.drawable.pp_ic_next, "Next", 76, 40) { Hub.player.next() }, lp(dp(ctx, 76), dp(ctx, 76)).apply { rightMargin = dp(ctx, 18) })
            addView(repeat)
        }, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 12) })
        volumeRow.addView(Ui.icon(ctx, R.drawable.pp_ic_volume, 24, Ui.DIM))
        volumeRow.addView(volume, lp(0, dp(ctx, 40), 1f))
        volumeRow.addView(volumeLabel)
        info.addView(volumeRow, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 10) })
        info.addView(upNext, lp(MATCH, WRAP).apply { topMargin = dp(ctx, 14) })
        row.addView(info, lp(0, MATCH, 1f).apply { leftMargin = dp(ctx, 48) })
        addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(Ui.roundButton(ctx, R.drawable.pp_ic_close, "Close Now Playing", 56, 28) { act.hideNowPlaying() },
            FrameLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56), Gravity.TOP or Gravity.END).apply { topMargin = dp(ctx, 16); rightMargin = dp(ctx, 16) })
    }

    fun update() {
        val p = Hub.player
        val t = p.current()
        title.text = t?.title ?: "Nothing playing"
        artist.text = t?.displayArtist ?: ""
        album.text = t?.let { listOf(it.album, if (it.year > 0) it.year.toString() else "").filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: ""
        (play.getChildAt(0) as ImageView).setImageResource(if (p.playing) R.drawable.pp_ic_pause else R.drawable.pp_ic_play)
        play.contentDescription = if (p.playing) "Pause" else "Play"
        source.text = t?.let { "Source: " + describeSource(it) } ?: ""
        output.text = when {
            t == null -> ""
            p.bitPerfect -> "Output: ${p.outputName}, ${p.outputLine.substringAfter("→").trim()}. Every bit reaches the DAC unchanged."
            p.usbActive -> "Output: ${p.outputName}, ${p.outputLine.substringAfter("→").trim()}" + if (Hub.softVolume) " (digital volume is on)" else ""
            else -> "Output: ${p.outputName}. Android resamples this path; connect the USB DAC for bit-perfect playback."
        }
        badge.text = when { t == null -> ""; p.bitPerfect -> "BIT-PERFECT"; p.usbActive -> "USB"; else -> "RESAMPLED" }
        badge.background = Ui.rounded(ctx, 0, 999, if (p.bitPerfect) Ui.ACCENT else Ui.STROKE, if (p.bitPerfect) 1.5f else 1f)
        badge.setTextColor(if (p.bitPerfect) Ui.ACCENT else Ui.DIM)
        shuffle.alpha = if (p.shuffle) 1f else 0.55f
        shuffle.background = if (p.shuffle) Ui.rounded(ctx, Ui.SURFACE2, 999, Ui.TEXT, 2) else null
        shuffle.contentDescription = if (p.shuffle) "Shuffle on" else "Shuffle off"
        (repeat.getChildAt(0) as ImageView).setImageResource(if (p.repeatMode == 2) R.drawable.pp_ic_repeat_one else R.drawable.pp_ic_repeat)
        repeat.alpha = if (p.repeatMode > 0) 1f else 0.55f
        repeat.background = if (p.repeatMode > 0) Ui.rounded(ctx, Ui.SURFACE2, 999, Ui.TEXT, 2) else null
        repeat.contentDescription = when (p.repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }
        val kind = Hub.volumeKind()
        volumeRow.visibility = if (kind == "dac" || kind == "digital") View.VISIBLE else View.GONE
        volumeLabel.text = if (kind == "dac") "  DAC volume" else "  Digital volume"
        volume.progress = (Hub.volume * 100).toInt()
        val q = p.queue(); val i = p.currentIndex()
        upNext.text = q.getOrNull(i + 1)?.track?.let { "Up next: ${it.title} · ${it.displayArtist}" } ?: if (t != null) "Last track in the queue" else ""
        if (t?.id != shownId) {
            shownId = t?.id ?: -1
            if (t == null) { coverView.setImageDrawable(null); bgArt.setImageDrawable(null) }
            else {
                val size = dp(ctx, 640)
                Covers.load(coverView, t.art, t.path, size) { it.setImageBitmap(Ui.placeholder(ctx, t.album, 512)) }
                Covers.load(bgArt, t.art, t.path, size) { it.setImageBitmap(Ui.placeholder(ctx, t.album, 256)) }
                if (android.os.Build.VERSION.SDK_INT >= 31) try { bgArt.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP)) } catch (_: Throwable) {}
            }
        }
        tick()
    }

    fun tick() {
        if (dragging) return
        val p = Hub.player
        val d = p.durationMs(); val ms = p.positionMs()
        seek.progress = if (d > 0) (ms * 1000 / d).toInt().coerceIn(0, 1000) else 0
        pos.text = Ui.time(ms); dur.text = Ui.time(d)
    }

    private fun describeSource(t: Track): String = when {
        t.isDsd -> "DSD${t.rate / 44100} (${"%.4f".format(t.rate / 1_000_000.0)} MHz), ${t.codec}"
        t.codec in Track.LOSSY -> "${t.codec}, ${UsbOutFmt.rate(t.rate)} (lossy)"
        else -> "${t.codec} ${t.bits}-bit / ${UsbOutFmt.rate(t.rate)}"
    }
}

private object UsbOutFmt { fun rate(r: Int) = app.playerpure.audio.UsbOut.fmtRate(r) }

/** Centres its child as the largest square that fits. */
class FitSquare(ctx: android.content.Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(minOf(w, h), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i); val x = (r - l - c.measuredWidth) / 2; val y = (b - t - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}

/** Keeps a child square: height follows width (or width follows height). */
class SquareFrame(ctx: android.content.Context, private val byHeight: Boolean = false) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (byHeight) {
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val spec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            super.onMeasure(spec, spec)
        } else {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val spec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            super.onMeasure(spec, spec)
        }
    }
}

