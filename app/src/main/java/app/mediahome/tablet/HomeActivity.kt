package app.mediahome.tablet

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import app.mediahome.tablet.remote.Remote

/**
 * The appliance home screen.
 * Landscape: big Music tile (with playback controls) on the left, YouTube / Video / Network Library / Browser on the right.
 * Portrait: Music across the top, the other four below.
 * While the web remote is on and no phone or PC has it open, the Browser tile shows the remote's sign-in QR code instead
 * (Settings > Web remote > QR code on the home screen). It turns back into the Browser tile as soon as a remote connects.
 * All apps and Settings are buttons in the header; Backup & Restore is inside Settings.
 * Local weather sits in the header (landscape) or just below it (portrait).
 */
class HomeActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Lang.wrap(base)); Fit.apply(this, base) }

    private lateinit var content: LinearLayout
    private val tileViews = LinkedHashMap<TileId, TileView>()
    private lateinit var headerWeatherSlot: FrameLayout
    private lateinit var portraitWeatherSlot: FrameLayout
    private lateinit var weatherView: LinearLayout
    private lateinit var remotePill: View
    private lateinit var headerRow: LinearLayout
    private lateinit var clockCol: View
    private val headerPills = ArrayList<View>()
    private lateinit var browserSlot: FrameLayout
    private lateinit var qrTile: QrTileView
    private var qrShown: Boolean? = null
    private val presenceTick = object : Runnable {
        override fun run() {
            updateBrowserSlot()
            handler.postDelayed(this, 1000)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather(force = true)
            handler.postDelayed(this, Weather.REFRESH_MS)
        }
    }
    @Volatile private var weatherLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migratePrefs()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pageBackground()
            val p = Ui.dp(this@HomeActivity, 28)
            setPadding(p, p, p, Ui.dp(this@HomeActivity, 18))
        }
        weatherView = buildWeatherView()
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        portraitWeatherSlot = FrameLayout(this)
        root.addView(portraitWeatherSlot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        content = LinearLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = Ui.dp(this@HomeActivity, 22)
        })
        for (tile in Tiles.all) {
            val v = TileView(this, hero = tile.id == TileId.MUSIC, colors = tile.colors)
            v.setOnClickListener { onTile(tile) }
            v.setOnLongClickListener { onTileLongPress(tile) }
            tileViews[tile.id] = v
        }
        qrTile = QrTileView(this, QR_COLORS) { onTile(Tiles.browser) }
        qrTile.setOnClickListener { showRemoteQr() }
        qrTile.setOnLongClickListener { onTileLongPress(Tiles.browser) }
        browserSlot = FrameLayout(this).apply {
            addView(tileViews.getValue(TileId.BROWSER), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(qrTile, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        qrTile.visibility = View.GONE
        layoutContent()

        root.addView(Ui.text(this, getString(R.string.hint_long_press), 15f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            // Phones held sideways: the tiles need that line's height more than the tip does.
            val dm = resources.displayMetrics
            if (minOf(dm.widthPixels, dm.heightPixels) / dm.density < 600) visibility = View.GONE
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@HomeActivity, 12) })

        setContentView(root)
        handleIntent(intent)
        firstRun()
    }

    /** The very first time: open the set-up checklist (it asks for location itself, so the home screen doesn't). */
    private fun firstRun() {
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.SETUP_SHOWN, false)) return
        prefs.edit().putBoolean(Prefs.SETUP_SHOWN, true).putBoolean(Prefs.WEATHER_LOC_ASKED, true).apply()
        startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opens the web-remote settings through this extra (e.g. from adb: am start ... --ez open_remote_settings true). */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_REMOTE_SETTINGS, false) == true) {
            i.removeExtra(EXTRA_OPEN_REMOTE_SETTINGS)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        if (i?.getBooleanExtra(EXTRA_SHOW_REMOTE_QR, false) == true) {
            i.removeExtra(EXTRA_SHOW_REMOTE_QR)
            showRemoteQr()
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 60f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format24Hour = Lang.datePattern(this@HomeActivity)
            format12Hour = Lang.datePattern(this@HomeActivity)
            textSize = 20f
            setTextColor(Ui.TEXT_DIM)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date)
        }
        row.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        headerRow = row; clockCol = col
        headerWeatherSlot = FrameLayout(this)
        row.addView(headerWeatherSlot, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        remotePill = Ui.pill(this, R.drawable.ic_qr, getString(R.string.remote_qr_pill)) { showRemoteQr() }
        remotePill.contentDescription = getString(R.string.remote_qr_title)
        row.addView(remotePill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = Ui.dp(this@HomeActivity, 14) })
        val appsPill = Ui.pill(this, R.drawable.ic_apps, getString(R.string.tile_all_apps)) { Pickers.allApps(this) }
        row.addView(appsPill)
        val settingsPill = Ui.pill(this, R.drawable.ic_settings, getString(R.string.tile_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        row.addView(settingsPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(this@HomeActivity, 14) })
        headerPills.addAll(listOf(remotePill, appsPill, settingsPill))
        row.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) row.post { fitHeader() } }
        return row
    }

    /**
     * 1.10: some languages need more room. If the header buttons, the clock and the weather don't fit side by side,
     * the buttons show only their icons (their names stay for TalkBack); if even then the weather doesn't fit, its
     * 3-day forecast is left out. Decided from full-size measurements, so it never flips back and forth.
     */
    private fun fitHeader() {
        val total = headerRow.width - headerRow.paddingLeft - headerRow.paddingRight
        if (total <= 0) return
        val un = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun w(v: View): Int { if (v.visibility == View.GONE) return 0; v.measure(un, un); return v.measuredWidth }
        fun label(p: View) = (p as LinearLayout).getChildAt(1)
        fun setCompact(on: Boolean) = headerPills.forEach { p ->
            label(p).visibility = if (on) View.GONE else View.VISIBLE
            val h = Ui.dp(this, if (on) 14 else 22); p.setPadding(h, p.paddingTop, h, p.paddingBottom)
        }
        val forecast = (2 until weatherView.childCount).map { weatherView.getChildAt(it) }
        forecast.forEach { it.visibility = View.VISIBLE }
        val gaps = Ui.dp(this, 2 * 14 + 24)
        val weatherIn = weatherView.parent === headerWeatherSlot
        setCompact(false)
        val full = w(clockCol) + (if (weatherIn) w(weatherView) else 0) + headerPills.sumOf { w(it) } + gaps
        if (full <= total) return
        setCompact(true)
        val compact = w(clockCol) + (if (weatherIn) w(weatherView) else 0) + headerPills.sumOf { w(it) } + gaps
        if (compact > total && weatherIn) forecast.forEach { it.visibility = View.GONE }
    }

    /** "Scan to open the remote": a large QR code with the address and PIN, for phones and other tablets. */
    private fun showRemoteQr() {
        val pad = Ui.dp(this, 24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val link = Remote.link(this)
        if (link == null) {
            box.addView(Ui.text(this, getString(R.string.remote_no_network), 20f))
        } else {
            val dm = resources.displayMetrics
            val size = (minOf(dm.widthPixels, dm.heightPixels) * 0.42f).toInt()
            box.addView(ImageView(this).apply {
                setImageBitmap(Qr.bitmap(link, size))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = getString(R.string.remote_qr_title)
            }, LinearLayout.LayoutParams(size, size))
            box.addView(Ui.text(this, getString(R.string.remote_qr_sub), 19f).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(size, WRAP_CONTENT).apply { topMargin = pad })
            box.addView(Ui.text(this, Remote.address(this) + "   \u00b7   " + getString(R.string.remote_pin_line, Remote.pin(this)),
                18f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = pad / 2 })
            box.addView(Ui.text(this, getString(R.string.remote_qr_note), 15f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(size, WRAP_CONTENT).apply { topMargin = pad / 2 })
        }
        Ui.dialog(this).setTitle(R.string.remote_qr_title).setView(box).setPositiveButton(R.string.close, null).show()
    }

    /** One-time resets when tile defaults change (v1.1: Music/YouTube, v1.2: Network Library, v1.8: Player Pure). */
    private fun migratePrefs() {
        val prefs = Prefs.of(this)
        val version = prefs.getInt("prefs_version", 1)
        val e = prefs.edit()
        if (version < 2) e.remove("tile_MUSIC").remove("tile_YOUTUBE")
        if (version < 3) e.remove("tile_NETWORK")   // v1.2: Network Library = Jellyfin or Emby (Plex removed)
        if (version < 4) e.remove("tile_MUSIC")     // v1.8: Player Pure is the main music app (HiBy stays available)
        e.putInt("prefs_version", 4).apply()
    }

    private fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    /** The Browser tile lives inside [browserSlot] (with the QR tile on top of it); every other tile stands alone. */
    private fun slot(id: TileId): View = if (id == TileId.BROWSER) browserSlot else tileViews.getValue(id)

    private fun layoutContent() {
        content.removeAllViews()
        TileId.entries.forEach { detach(slot(it)) }
        val gap = Ui.dp(this, 18)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun pair(a: TileId, b: TileId) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(slot(a), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = gap / 2 })
            addView(slot(b), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = gap / 2 })
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pair(TileId.YOUTUBE, TileId.VIDEO), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = gap / 2 })
            addView(pair(TileId.NETWORK, TileId.BROWSER), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = gap / 2 })
        }
        detach(weatherView)
        if (landscape) {
            headerWeatherSlot.addView(weatherView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        } else {
            portraitWeatherSlot.addView(weatherView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START).apply {
                topMargin = Ui.dp(this@HomeActivity, 16)
            })
        }
        val hero = tileViews.getValue(TileId.MUSIC)
        if (landscape) {
            content.orientation = LinearLayout.HORIZONTAL
            content.addView(hero, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.15f).apply { marginEnd = gap / 2 })
            content.addView(grid, LinearLayout.LayoutParams(0, MATCH_PARENT, 2f).apply { marginStart = gap / 2 })
        } else {
            content.orientation = LinearLayout.VERTICAL
            // 1.8.2: a little more height for Music, so its play buttons are not cut off under the portrait weather row
            content.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.25f).apply { bottomMargin = gap / 2 })
            content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.55f).apply { topMargin = gap / 2 })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutContent()
    }

    override fun onResume() {
        super.onResume()
        refreshTiles()
        applyWindowPrefs()
        Remote.syncService(this)
        remotePill.visibility = if (Remote.enabled(this)) View.VISIBLE else View.GONE
        qrShown = null
        handler.removeCallbacks(presenceTick)
        presenceTick.run()
        renderWeather(Weather.cached(this))
        refreshWeather(force = false)
        askLocationOnce()
        handler.postDelayed(weatherTick, Weather.REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(weatherTick)
        handler.removeCallbacks(presenceTick)
        super.onPause()
    }

    // ---- Browser slot: the remote's QR code until a phone or PC connects --------------------------

    /** Shows the QR code in the Browser tile's place while the remote is on, nobody is connected and there is an address. */
    private fun updateBrowserSlot() {
        val link = if (Remote.enabled(this) && Remote.qrOnHome(this)) Remote.link(this) else null
        val show = link != null && !Remote.clientConnected()
        if (link != null) qrTile.bind(link, Remote.address(this), Remote.pin(this))
        if (show == qrShown) return
        val first = qrShown == null
        qrShown = show
        val browser = tileViews.getValue(TileId.BROWSER)
        val (incoming, outgoing) = if (show) qrTile to browser else browser to qrTile
        outgoing.animate().cancel(); incoming.animate().cancel()
        outgoing.visibility = View.GONE
        outgoing.alpha = 1f
        incoming.visibility = View.VISIBLE
        if (first) incoming.alpha = 1f
        else { incoming.alpha = 0f; incoming.animate().alpha(1f).setDuration(250).start() }
    }

    // ---- Weather -------------------------------------------------------------------------------

    private fun buildWeatherView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
    }

    /**
     * Updates the forecast when it is older than 30 minutes (or [force]). With "current location" on, the tablet's
     * approximate location is looked up first; if it moved more than about 2 km, the forecast is fetched for the new place.
     */
    private fun refreshWeather(force: Boolean) {
        if (!Weather.enabled(this) || weatherLoading) return
        val cached = Weather.cached(this)
        val stale = cached == null || System.currentTimeMillis() - cached.fetchedAt >= Weather.REFRESH_MS
        val locate = Weather.useLocation(this) && Locator.hasPermission(this)
        if (!force && !stale && !(locate && Weather.herePlace(this) == null)) return
        weatherLoading = true
        val app = applicationContext
        fun fetch() = Thread {
            val r = try { Weather.refresh(app) } catch (e: Exception) { null }
            weatherLoading = false
            runOnUiThread { if (!isDestroyed) renderWeather(r ?: Weather.cached(app)) }
        }.start()
        if (!locate) { fetch(); return }
        Locator.current(app) { loc ->
            if (loc == null) { fetch(); return@current }
            Thread {
                val lat = Locator.round(loc.latitude)
                val lon = Locator.round(loc.longitude)
                val name = if (Weather.needsName(app, lat, lon)) Locator.placeName(app, lat, lon) else null
                Weather.saveHere(app, lat, lon, name)
                fetch()
            }.start()
        }
    }

    /** Asks once for approximate location, for the weather (unless the set-up checklist already did). */
    private fun askLocationOnce() {
        if (!Weather.enabled(this) || !Weather.useLocation(this) || Locator.hasPermission(this)) return
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.WEATHER_LOC_ASKED, false)) return
        prefs.edit().putBoolean(Prefs.WEATHER_LOC_ASKED, true).apply()
        requestPermissions(arrayOf(Locator.PERMISSION), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && Locator.hasPermission(this)) refreshWeather(force = true)
    }

    private fun renderWeather(r: WeatherReport?) {
        weatherView.removeAllViews()
        if (!Weather.enabled(this)) { weatherView.visibility = View.GONE; return }
        weatherView.visibility = View.VISIBLE
        if (r == null) {
            weatherView.addView(Ui.text(this, getString(R.string.wx_loading), 17f, Ui.TEXT_DIM))
            weatherView.contentDescription = getString(R.string.wx_loading)
            return
        }
        fun icon(res: Int, sizeDp: Int) = ImageView(this).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@HomeActivity, sizeDp), Ui.dp(this@HomeActivity, sizeDp))
        }
        val condition = getString(Weather.describe(r.code))
        weatherView.addView(icon(Weather.icon(r.code, r.isDay), 54))
        val now = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@HomeActivity, Weather.deg(r.temp), 36f, Ui.TEXT, bold = true))
            addView(Ui.text(this@HomeActivity, "$condition \u00B7 ${r.place}", 16f, Ui.TEXT_DIM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = Ui.dp(this@HomeActivity, 250)
            })
        }
        weatherView.addView(now, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = Ui.dp(this@HomeActivity, 12); marginEnd = Ui.dp(this@HomeActivity, 26)
        })
        val spoken = StringBuilder("${r.place}: ${Weather.deg(r.temp)}, $condition.")
        for (d in r.days.drop(1).take(3)) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // WRAP_CONTENT: otherwise the column is only as wide as the icon and "Th 4" / "周三" get cut
                val wrap = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }
                addView(Ui.text(this@HomeActivity, Weather.dayName(d.date), 15f, Ui.TEXT_DIM, bold = true).apply { maxLines = 1 }, wrap())
                addView(icon(Weather.icon(d.code), 28))
                addView(Ui.text(this@HomeActivity, Weather.deg(d.max), 15f, Ui.TEXT, bold = true), wrap())
                addView(Ui.text(this@HomeActivity, Weather.deg(d.min), 14f, Ui.TEXT_DIM), wrap())
            }
            weatherView.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = Ui.dp(this@HomeActivity, 12)
            })
            spoken.append(" ${Weather.dayName(d.date)} ${getString(Weather.describe(d.code))}, ${Weather.deg(d.max)} / ${Weather.deg(d.min)}.")
        }
        weatherView.contentDescription = spoken.toString()
        if (::headerRow.isInitialized) headerRow.post { fitHeader() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindowPrefs()
    }

    private fun refreshTiles() {
        for (tile in Tiles.all) {
            if (tile.chooser && Apps.savedChoice(this, tile) == null) {
                val installed = Apps.installedCandidates(this, tile)
                val label = if (installed.isEmpty()) {
                    getString(R.string.choose_none_installed, tile.candidates.joinToString(getString(R.string.or_join)) { Apps.knownName(it) })
                } else {
                    installed.joinToString("  \u00B7  ") { Apps.label(this, it) }
                }
                tileViews.getValue(tile.id).bind(tile.icon, getString(tile.title), installed.firstOrNull()?.let { Apps.icon(this, it) }, label)
                continue
            }
            val pkg = Apps.chosenPackage(this, tile)
            val label = when {
                pkg == null -> getString(R.string.not_installed, Apps.knownName(tile.suggestion))
                tile.url != null -> getString(R.string.opens_in, Apps.label(this, pkg))
                else -> Apps.label(this, pkg)
            }
            tileViews.getValue(tile.id).bind(tile.icon, getString(tile.title), pkg?.let { Apps.icon(this, it) }, label)
        }
    }

    private fun applyWindowPrefs() {
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.KEEP_SCREEN_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val controller = window.insetsController ?: return
        if (prefs.getBoolean(Prefs.IMMERSIVE, false)) {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
    }

    private fun onTile(tile: Tile) {
        if (tile.chooser && Apps.savedChoice(this, tile) == null) {
            val installed = Apps.installedCandidates(this, tile)
            when {
                installed.size == 1 -> { Apps.launch(this, installed[0]); return }
                installed.size > 1 -> {
                    Ui.dialog(this)
                        .setTitle(R.string.choose_open_which)
                        .setItems(installed.map { Apps.label(this, it) }.toTypedArray()) { _, which -> Apps.launch(this, installed[which]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return
                }
                else -> {
                    val c = tile.candidates
                    Ui.dialog(this)
                        .setTitle(getString(tile.title))
                        .setMessage(R.string.network_get_message)
                        .setPositiveButton(getString(R.string.get_app, Apps.knownName(c[0]))) { _, _ -> Apps.openStore(this, c[0]) }
                        .setNeutralButton(getString(R.string.get_app, Apps.knownName(c[1]))) { _, _ -> Apps.openStore(this, c[1]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return
                }
            }
        }
        val pkg = Apps.chosenPackage(this, tile)
        if (pkg != null) {
            val ok = if (tile.url != null) Apps.openUrlIn(this, tile.url, pkg) else Apps.launch(this, pkg)
            if (ok) return
        }
        val suggested = tile.suggestion
        Ui.dialog(this)
            .setTitle(getString(tile.title))
            .setMessage(getString(R.string.need_app, Apps.knownName(suggested)))
            .setPositiveButton(R.string.open_play) { _, _ -> Apps.openStore(this, suggested) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onTileLongPress(tile: Tile): Boolean {
        Pickers.chooseApp(this, tile) { refreshTiles() }
        return true
    }

    companion object {
        /** The QR tile's colours: a calm slate, so it reads as different from the app tiles. */
        private val QR_COLORS = intArrayOf(0xFF2A3047.toInt(), 0xFF0F111A.toInt())
        private const val REQ_LOCATION = 41
        const val EXTRA_OPEN_REMOTE_SETTINGS = "open_remote_settings"
        const val EXTRA_SHOW_REMOTE_QR = "show_remote_qr"
    }

    /** This is the home screen: Back has nowhere to go, so it does nothing. */
    @Deprecated("Home screen ignores Back")
    override fun onBackPressed() {
        // Intentionally empty.
    }
}

/** Dialogs shared by the home screen and the Settings page. */
object Pickers {
    fun chooseApp(activity: Activity, tile: Tile, done: () -> Unit) {
        val apps = Apps.launchableApps(activity)
        val labels = ArrayList<String>()
        labels.add(activity.getString(R.string.automatic_choice))
        apps.forEach { labels.add(it.label) }
        Ui.dialog(activity)
            .setTitle(activity.getString(R.string.choose_app_for, activity.getString(tile.title)))
            .setItems(labels.toTypedArray()) { _, which ->
                Apps.setChoice(activity, tile, if (which == 0) null else apps[which - 1].pkg)
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun chooseTileThenApp(activity: Activity, done: () -> Unit) {
        val labels = Tiles.all.map { t ->
            val pkg = Apps.chosenPackage(activity, t)
            activity.getString(t.title) + "  -  " + (pkg?.let { Apps.label(activity, it) } ?: "—")
        }
        Ui.dialog(activity)
            .setTitle(R.string.choose_tile)
            .setItems(labels.toTypedArray()) { _, which -> chooseApp(activity, Tiles.all[which], done) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun allApps(activity: Activity) {
        val apps = Apps.launchableApps(activity)
        Ui.dialog(activity)
            .setTitle(R.string.tile_all_apps)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> Apps.launch(activity, apps[which].pkg) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
