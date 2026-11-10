package app.mediahome.tablet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Browser

/** The media tiles on the home screen. All apps, Settings and Backup & Restore live in the header / Settings. */
enum class TileId { MUSIC, YOUTUBE, VIDEO, NETWORK, BROWSER }

data class Tile(
    val id: TileId,
    val title: Int,
    val icon: Int,
    /** Apps this tile can open, most preferred first. */
    val candidates: List<String>,
    /** App suggested when none of the candidates is installed (defaults to the first candidate). */
    val suggest: String? = null,
    /** If set, the tile opens this web address in the chosen app (for example YouTube in Brave). */
    val url: String? = null,
    /** Decorative background gradient (top-left, bottom-right). Meaning never depends on colour. */
    val colors: IntArray,
    /** If true and several candidates are installed, tapping the tile asks which one to open. */
    val chooser: Boolean = false,
) {
    val suggestion: String get() = suggest ?: candidates.first()
}

object Tiles {
    val music = Tile(TileId.MUSIC, R.string.tile_music, R.drawable.ic_music,
        listOf(Apps.PLAYER_PURE, Apps.HIBY, Apps.UAPP, Apps.VLC, Apps.KODI), suggest = Apps.PLAYER_PURE,
        colors = intArrayOf(0xFF3B2163.toInt(), 0xFF120E1C.toInt()))
    val youtube = Tile(TileId.YOUTUBE, R.string.tile_youtube, R.drawable.ic_youtube,
        listOf(Apps.BRAVE, Apps.YOUTUBE), suggest = Apps.BRAVE, url = "https://www.youtube.com/",
        colors = intArrayOf(0xFF4C1C1C.toInt(), 0xFF170D0D.toInt()))
    val video = Tile(TileId.VIDEO, R.string.tile_video, R.drawable.ic_video,
        listOf(Apps.VLC, Apps.KODI),
        colors = intArrayOf(0xFF153752.toInt(), 0xFF0B131B.toInt()))
    val network = Tile(TileId.NETWORK, R.string.tile_network, R.drawable.ic_network,
        listOf(Apps.JELLYFIN, Apps.EMBY), suggest = Apps.JELLYFIN, chooser = true,
        colors = intArrayOf(0xFF17433A.toInt(), 0xFF0A1613.toInt()))
    val browser = Tile(TileId.BROWSER, R.string.tile_browser, R.drawable.ic_browser,
        listOf(Apps.BRAVE),
        colors = intArrayOf(0xFF4D3214.toInt(), 0xFF19110A.toInt()))

    val all: List<Tile> = listOf(music, youtube, video, network, browser)
}

object Prefs {
    const val FILE = "mediahome"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val IMMERSIVE = "immersive"
    const val REMOTE = "web_remote"
    const val REMOTE_PIN = "web_remote_pin"
    const val REMOTE_QR_HOME = "web_remote_qr_home"
    const val WEATHER_ON = "weather_on"
    const val WEATHER_CITY = "weather_city"
    const val WEATHER_LAT = "weather_lat"
    const val WEATHER_LON = "weather_lon"
    const val WEATHER_CACHE = "weather_cache"
    const val WEATHER_USE_LOCATION = "weather_use_location"
    const val WEATHER_HERE_LAT = "weather_here_lat"
    const val WEATHER_HERE_LON = "weather_here_lon"
    const val WEATHER_HERE_NAME = "weather_here_name"
    const val WEATHER_LOC_ASKED = "weather_location_asked"
    const val SAVER_MUSIC = "saver_music"
    const val SAVER_WEATHER = "saver_weather"
    const val YT_KEY = "youtube_api_key"
    const val LANGUAGE = "language"          // 1.10: "" = the tablet's language (Android 11-12; Android 13+ keeps it itself)
    const val SETUP_SHOWN = "setup_shown"     // the first-run set-up checklist has been opened once
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

object Apps {
    /** Player Pure, the bit-perfect player built into this app (not a separate package; see [playerPureIntent]). */
    const val PLAYER_PURE = "app.playerpure"
    const val HIBY = "com.hiby.music"
    const val VLC = "org.videolan.vlc"
    const val KODI = "org.xbmc.kodi"
    const val JELLYFIN = "org.jellyfin.mobile"
    const val EMBY = "com.mb.android"
    const val YOUTUBE = "com.google.android.youtube"
    const val UAPP = "com.extreamsd.usbaudioplayerpro"
    const val BRAVE = "com.brave.browser"
    const val AIRPLAY = "io.github.jqssun.airplay"   // AirPlay Receiver & Server (open source, GPL-3.0)

    private val knownNames = mapOf(
        PLAYER_PURE to "Player Pure", HIBY to "HiBy Music", VLC to "VLC", KODI to "Kodi", JELLYFIN to "Jellyfin", EMBY to "Emby",
        YOUTUBE to "YouTube", UAPP to "USB Audio Player PRO", BRAVE to "Brave",
    )

    fun knownName(pkg: String): String = knownNames[pkg] ?: pkg

    fun isLaunchable(ctx: Context, pkg: String): Boolean =
        pkg == PLAYER_PURE || ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Opens the built-in Player Pure (its own task, so Home and Back behave like a separate app). */
    fun playerPureIntent(ctx: Context): Intent =
        Intent(ctx, app.playerpure.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app a tile opens: the user's choice if still installed, else the best installed candidate. */
    fun chosenPackage(ctx: Context, tile: Tile): String? {
        val saved = Prefs.of(ctx).getString(key(tile), null)
        if (saved != null && isLaunchable(ctx, saved)) return saved
        return tile.candidates.firstOrNull { isLaunchable(ctx, it) }
    }

    /** The app the user explicitly picked for this tile (long-press), if it is still installed. */
    fun savedChoice(ctx: Context, tile: Tile): String? {
        val saved = Prefs.of(ctx).getString(key(tile), null)
        return if (saved != null && isLaunchable(ctx, saved)) saved else null
    }

    fun installedCandidates(ctx: Context, tile: Tile): List<String> = tile.candidates.filter { isLaunchable(ctx, it) }

    fun setChoice(ctx: Context, tile: Tile, pkg: String?) {
        val e = Prefs.of(ctx).edit()
        if (pkg == null) e.remove(key(tile)) else e.putString(key(tile), pkg)
        e.apply()
    }

    private fun key(tile: Tile) = "tile_" + tile.id.name

    fun label(ctx: Context, pkg: String): String {
        if (pkg == PLAYER_PURE || pkg == ctx.packageName) return knownName(PLAYER_PURE)   // music playing in this app = Player Pure
        val pm = ctx.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            knownName(pkg)
        }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = if (pkg == PLAYER_PURE) ctx.getDrawable(app.playerpure.R.mipmap.pp_ic_launcher) else try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    data class LaunchableApp(val pkg: String, val label: String)

    fun launchableApps(ctx: Context): List<LaunchableApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return (found.map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { LaunchableApp(it, label(ctx, it)) } + LaunchableApp(PLAYER_PURE, knownName(PLAYER_PURE)))
            .sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = if (pkg == PLAYER_PURE) playerPureIntent(ctx) else ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Opens [url] in [pkg] (e.g. the YouTube website in Brave). The application-id extra asks
     * Chromium-based browsers to reuse the same tab each time instead of opening new ones.
     * Falls back to simply launching the app.
     */
    fun openUrlIn(ctx: Context, url: String, pkg: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(pkg)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra(Browser.EXTRA_APPLICATION_ID, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(ctx.packageManager) != null) {
            try {
                ctx.startActivity(view)
                return true
            } catch (e: RuntimeException) {
                // fall through to a plain launch
            }
        }
        return launch(ctx, pkg)
    }

    /** Opens the app's official Google Play page (the user installs it themselves). */
    fun openStore(activity: Activity, pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(market)
        } catch (e: RuntimeException) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Ui.tryStart(activity, web)
        }
    }
}
