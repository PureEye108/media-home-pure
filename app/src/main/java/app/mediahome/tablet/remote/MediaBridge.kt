package app.mediahome.tablet.remote

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Browser
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import app.mediahome.tablet.Apps
import app.mediahome.tablet.Dac
import app.mediahome.tablet.Prefs
import app.mediahome.tablet.Tiles
import app.mediahome.tablet.Ui
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connects the web remote to Android:
 * - "What's playing" and transport controls come from active media sessions (needs Notification access for Media Home).
 *   Without it, play/pause/next/previous still work through media keys.
 * - Opening apps from the remote needs "Display over other apps" (Android blocks background app launches otherwise).
 * - Volume uses the media stream; no permission needed.
 */
class MediaBridge(private val ctx: Context) : RemoteDevice {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)

    init { YouTubeSearch.identify(ctx) }

    fun hasNotificationAccess(): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listener) == true

    fun canLaunchApps(): Boolean = Settings.canDrawOverlays(ctx)

    private fun controller(): MediaController? {
        if (!hasNotificationAccess()) return null
        return try {
            val list = sessions.getActiveSessions(listener)
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun artBitmap(md: MediaMetadata?): Bitmap? = md?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    /** What is playing, for the screen saver: title, artist, cover, position, and Player Pure's signal path. */
    fun nowPlaying(): app.mediahome.tablet.NowPlaying? {
        val c = controller() ?: return null
        val md = c.metadata ?: return null
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.description?.title?.toString()
        if (title.isNullOrBlank()) return null
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        var pos = st?.position ?: 0L
        if (st != null && playing && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (dur > 0 && pos > dur) pos = dur
        var signal: String? = null
        var bitPerfect = false
        if (c.packageName == ctx.packageName) {   // the only player inside this app is Player Pure
            ppCall("status", null, emptyMap())?.getString("json")?.let { j ->
                try {
                    val o = org.json.JSONObject(j)
                    signal = listOf(o.optString("outputLine").ifBlank { o.optString("format") }, o.optString("output"))
                        .filter { it.isNotBlank() }.joinToString("  \u00b7  ").ifBlank { null }
                    bitPerfect = o.optBoolean("bitPerfect", false)
                } catch (e: Exception) { }
            }
        }
        return app.mediahome.tablet.NowPlaying(
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            app = Apps.label(ctx, c.packageName),
            playing = playing, positionMs = pos.coerceAtLeast(0), durationMs = dur.coerceAtLeast(0),
            art = artBitmap(md), signal = signal, bitPerfect = bitPerfect,
        )
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        null -> "none"
        else -> "idle"
    }

    override fun statusJson(): String {
        val c = controller()
        val md = c?.metadata
        val st = c?.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: 0L
        if (st != null && playing && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (dur > 0 && pos > dur) pos = dur
        if (pos < 0) pos = 0
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val apps = Json()
            .put("MUSIC", tileLabel(Tiles.music))
            .put("YOUTUBE", tileLabel(Tiles.youtube))
            .put("VIDEO", tileLabel(Tiles.video))
            .put("BROWSER", tileLabel(Tiles.browser))
            .put("JELLYFIN", if (Apps.isLaunchable(ctx, Apps.JELLYFIN)) "Jellyfin" else null)
            .put("EMBY", if (Apps.isLaunchable(ctx, Apps.EMBY)) "Emby" else null)
            .put("AIRPLAY", if (Apps.isLaunchable(ctx, Apps.AIRPLAY)) "AirPlay Receiver" else null)
            .put("KODI", if (Apps.isLaunchable(ctx, Apps.KODI)) "Kodi (DLNA player)" else null)
        return Json()
            .put("ok", true)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("access", hasNotificationAccess())
            .put("canLaunch", canLaunchApps())
            .put("app", c?.packageName?.let { Apps.label(ctx, it) })
            .put("state", stateName(st?.state))
            .put("playing", playing)
            .put("canSeek", st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("durationMs", dur)
            .put("positionMs", pos)
            .put("hasArt", artBitmap(md) != null)
            .put("artKey", listOf(c?.packageName ?: "", title ?: "", album ?: "").joinToString("|"))
            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("apps", apps)
            .put("dac", Dac.attached(ctx).joinToString(", ").ifEmpty { null })
            .put("input", hasInputService())
            .put("files", hasFileAccess())
            .put("lang", app.mediahome.tablet.Lang.current(ctx))
            .put("ytKey", hasYouTubeKey())
            .toString()
    }

    private fun tileLabel(tile: app.mediahome.tablet.Tile): String? = Apps.chosenPackage(ctx, tile)?.let { Apps.label(ctx, it) }

    override fun art(): ByteArray? {
        val bmp = artBitmap(controller()?.metadata) ?: return null
        val max = 600
        val scaled = if (bmp.width > max || bmp.height > max) {
            val f = max.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    override fun control(action: String, ms: Long?): Boolean {
        val c = controller()
        if (c != null) {
            val t = c.transportControls
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "prev" -> t.skipToPrevious()
                "stop" -> t.stop()
                "seek" -> { if (ms == null) return false; t.seekTo(ms.coerceAtLeast(0)) }
                else -> return false
            }
            return true
        }
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        Ui.mediaKey(ctx, key)
        return true
    }

    override fun setVolume(level: Int?, delta: Int?): Boolean {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (level != null) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            return true
        }
        if (delta != null && delta != 0) {
            val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            repeat(minOf(Math.abs(delta), 5)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
            return true
        }
        return false
    }

    private fun start(intent: Intent): Boolean {
        if (!canLaunchApps()) return false
        wake()
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun launch(target: String): Boolean {
        if (target == "HOME") {
            return start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName))
        }
        if (target == "YOUTUBE") {
            val pkg = Apps.chosenPackage(ctx, Tiles.youtube) ?: return false
            val url = Tiles.youtube.url ?: return false
            return openUrlInPackage(url, pkg)
        }
        val pkg = when (target) {
            "MUSIC" -> Apps.chosenPackage(ctx, Tiles.music)
            "VIDEO" -> Apps.chosenPackage(ctx, Tiles.video)
            "BROWSER" -> Apps.chosenPackage(ctx, Tiles.browser)
            "JELLYFIN" -> Apps.JELLYFIN.takeIf { Apps.isLaunchable(ctx, it) }
            "EMBY" -> Apps.EMBY.takeIf { Apps.isLaunchable(ctx, it) }
            "AIRPLAY" -> Apps.AIRPLAY.takeIf { Apps.isLaunchable(ctx, it) }
            "KODI" -> Apps.KODI.takeIf { Apps.isLaunchable(ctx, it) }
            else -> null
        } ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(intent)
    }

    private fun openUrlInPackage(url: String, pkg: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(pkg)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra(Browser.EXTRA_APPLICATION_ID, ctx.packageName)
        if (view.resolveActivity(ctx.packageManager) != null && start(view)) return true
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(launch)
    }

    override fun openUrl(url: String, app: String): Boolean = when (app) {
        "auto" -> openUrl(url, RemoteApi.pickApp(url))
        "brave" -> openUrlInPackage(url, Apps.BRAVE)
        "vlc" -> {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").setPackage(Apps.VLC)
            i.resolveActivity(ctx.packageManager) != null && start(i)
        }
        else -> false
    }

    override fun connectLink(): String? = Remote.link(ctx)

    fun hasInputService(): Boolean = RemoteInputService.instance != null

    override fun hasFileAccess(): Boolean = try {
        Environment.isExternalStorageManager()
    } catch (e: RuntimeException) {
        false
    }

    override fun mediaScan(file: File) {
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
    }

    override fun playFile(file: File, kind: String): Boolean {
        if (!canLaunchApps()) return false
        // Ask Android's media library for the file's content:// address (other apps can't open file:// paths).
        val done = CountDownLatch(1)
        var uri: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null) { _, u -> uri = u; done.countDown() }
        done.await(10, TimeUnit.SECONDS)
        val u = uri ?: return false
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: when (kind) { "audio" -> "audio/*"; "video" -> "video/*"; else -> "image/*" }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pkg = when (kind) {
            "audio" -> Apps.chosenPackage(ctx, Tiles.music)
            "video" -> Apps.chosenPackage(ctx, Tiles.video)
            else -> null
        }
        if (pkg != null) {
            val direct = Intent(view).setPackage(pkg)
            if (direct.resolveActivity(ctx.packageManager) != null && start(direct)) return true
        }
        return view.resolveActivity(ctx.packageManager) != null && start(view)
    }

    override fun pointer(action: String, dx: Int, dy: Int): InputResult {
        val s = RemoteInputService.instance ?: return InputResult.NO_SERVICE
        wake()
        return when (action) {
            "move" -> { s.move(dx, dy); InputResult.OK }
            "tap" -> if (s.tap(longPress = false)) InputResult.OK else InputResult.FAILED
            "longpress" -> if (s.tap(longPress = true)) InputResult.OK else InputResult.FAILED
            "scroll" -> if (s.scroll(dx, dy)) InputResult.OK else InputResult.FAILED
            else -> InputResult.FAILED
        }
    }

    override fun nav(action: String): InputResult {
        val s = RemoteInputService.instance ?: return InputResult.NO_SERVICE
        wake()
        if (action == "fullscreen") return if (s.fullscreenVideo(3_000)) InputResult.OK else InputResult.FAILED
        return if (s.global(action)) InputResult.OK else InputResult.FAILED
    }

    /** The key used for YouTube searches: one set on this tablet, else the one built into Media Home. */
    fun youTubeKey(): String? = YouTubeSearch.effectiveKey(Prefs.of(ctx).getString(Prefs.YT_KEY, null))

    fun hasYouTubeKey(): Boolean = youTubeKey() != null

    override fun setYouTubeKey(key: String?) {
        val e = Prefs.of(ctx).edit()
        if (key == null) e.remove(Prefs.YT_KEY) else e.putString(Prefs.YT_KEY, key.trim())
        e.apply()
    }

    override fun playFirstVideo(query: String): YouTubeSearch.Result {
        val r = YouTubeSearch.search(query, youTubeKey())
        val id = r.videoId
        if (r.outcome != YouTubeSearch.Outcome.OK || id == null) return r
        if (!playVideo(id)) return YouTubeSearch.Result(YouTubeSearch.Outcome.OPEN_FAILED)
        return r
    }

    override fun searchVideos(query: String): YouTubeSearch.ListResult =
        YouTubeSearch.searchList(query, youTubeKey(), 12)

    /** Opens the video's YouTube page in Brave, then presses YouTube's Full screen button (needs Mouse & keyboard). */
    override fun playVideo(videoId: String): Boolean {
        if (!openUrlInPackage(YouTubeSearch.watchUrl(videoId), Apps.BRAVE)) return false
        RemoteInputService.instance?.fullscreenVideoSoon()
        return true
    }

    private val thumbs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 60
    }

    /** The video's small thumbnail from YouTube's image server (passed on to the phone; cached for repeat views). */
    override fun thumbnail(videoId: String): ByteArray? {
        synchronized(thumbs) { thumbs[videoId]?.let { return it } }
        return try {
            val c = java.net.URL("https://i.ytimg.com/vi/$videoId/mqdefault.jpg").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 6_000
            try {
                if (c.responseCode != 200) return null
                val bytes = c.inputStream.use { s ->
                    val out = ByteArrayOutputStream(); val buf = ByteArray(16_384); var total = 0
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > 300_000) return null; out.write(buf, 0, n) }
                    out.toByteArray()
                }
                synchronized(thumbs) { thumbs[videoId] = bytes }
                bytes
            } finally { c.disconnect() }
        } catch (e: java.io.IOException) {
            null
        }
    }

    // ---------------------------------------------------------------- Player Pure
    /** Player Pure is built into this app (the :player module); its provider is named after this app's id. */
    private val playerPure = Uri.parse("content://${ctx.packageName}.playerpure.control")

    private fun ppCall(method: String, arg: String?, extras: Map<String, Any>): android.os.Bundle? {
        val b = android.os.Bundle()
        for ((k, v) in extras) when (v) {
            is Int -> b.putInt(k, v); is Long -> b.putLong(k, v); is Double -> b.putDouble(k, v); is Boolean -> b.putBoolean(k, v); is String -> b.putString(k, v)
        }
        return try { ctx.contentResolver.call(playerPure, method, arg, b) } catch (e: Exception) { null }   // not installed, or not allowed
    }

    /**
     * Starting music from the phone: if Player Pure is not already playing in the background, open it on the tablet first
     * so Android lets it keep playing (a music app needs to be on screen once to start its playback service).
     */
    private fun ensurePlayerPureForeground() {
        val status = ppCall("status", null, emptyMap())?.getString("json") ?: return
        val running = try { org.json.JSONObject(status).optBoolean("serviceRunning", false) } catch (e: Exception) { false }
        if (running) return
        start(Apps.playerPureIntent(ctx))
        SystemClock.sleep(700)
    }

    override fun music(method: String, arg: String?, extras: Map<String, Any>): String? {
        val starts = method == "play" || method == "playTrack" || method == "queueJump" || (method == "control" && (arg == "toggle" || arg == "play"))
        if (starts) ensurePlayerPureForeground()
        return ppCall(method, arg, extras)?.getString("json")
    }

    override fun musicArt(key: String): ByteArray? = ppCall("art", key, emptyMap())?.getByteArray("jpeg")

    override fun keyboard(action: String, text: String): InputResult {
        val s = RemoteInputService.instance ?: return InputResult.NO_SERVICE
        return s.keyboard(action, text)
    }

    override fun search(query: String, engine: String): Boolean {
        val q = URLEncoder.encode(query, "UTF-8")
        if (engine == "youtube") return openUrlInPackage("https://www.youtube.com/results?search_query=$q", Apps.BRAVE)
        // Brave's own search with the engine chosen in Brave; falls back to Brave Search on the web.
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).setPackage(Apps.BRAVE)
        if (i.resolveActivity(ctx.packageManager) != null && start(i)) return true
        return openUrlInPackage("https://search.brave.com/search?q=$q", Apps.BRAVE)
    }

    @Suppress("DEPRECATION", "WakelockTimeout")
    override fun wake(): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        if (pm.isInteractive) return true
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "mediahome:remote-wake",
        )
        wl.acquire(5_000)
        return true
    }
}
