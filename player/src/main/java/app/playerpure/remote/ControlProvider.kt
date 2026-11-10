package app.playerpure.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import app.playerpure.Hub
import app.playerpure.audio.PlaybackService
import app.playerpure.library.Covers
import app.playerpure.library.Library
import app.playerpure.library.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Lets the web remote browse and control Player Pure (content://<app id>.playerpure.control, method calls only).
 * Not exported: only Media Home Pure itself (the same app) can call it.
 */
class ControlProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun json(o: Any) = Bundle().apply { putString("json", o.toString()) }
    private fun ok() = json(JSONObject().put("ok", true))

    private fun trackJson(t: Track, i: Int) = JSONObject().put("i", i).put("id", t.id).put("title", t.title).put("artist", t.displayArtist)
        .put("album", t.album).put("durationMs", t.durationMs).put("format", t.formatLabel)

    private fun dacJson(): JSONObject {
        Hub.refreshDac()
        val d = Hub.dacDisplay()
        return JSONObject().put("connected", d.connected).put("name", d.name).put("input", d.input)
            .put("rate", d.rate).put("unit", d.unit).put("format", d.format)
            .put("volume", d.volume).put("volumeDb", d.volumeDb ?: JSONObject.NULL)
            .put("muted", d.muted).put("hasVolume", d.hasVolume).put("hasMute", d.hasMute)
            .put("badge", d.badge).put("bitPerfect", d.bitPerfect).put("state", d.state).put("nativeDsd", d.nativeDsd)
            .put("dsdOut", Hub.dsdOut.name)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        Hub.init(ctx)
        val p = Hub.player
        val lib = Hub.library
        return when (method) {
            "status" -> {
                val t = p.current()
                json(JSONObject()
                    .put("installed", true).put("playing", p.playing)
                    .put("title", t?.title ?: "").put("artist", t?.displayArtist ?: "").put("album", t?.album ?: "")
                    .put("albumKey", t?.albumKey ?: "").put("trackId", t?.id ?: -1)
                    .put("positionMs", p.positionMs()).put("durationMs", p.durationMs())
                    .put("index", p.currentIndex()).put("queueSize", p.queue().size)
                    .put("format", t?.formatLabel ?: "").put("output", p.outputName).put("outputLine", p.outputLine)
                    .put("bitPerfect", p.bitPerfect).put("usb", p.usbActive).put("dac", Hub.dacStatus)
                    .put("volumeKind", Hub.volumeKind() ?: "none").put("volume", (Hub.volume * 100).toInt())
                    .put("repeat", p.repeatMode).put("shuffle", p.shuffle)
                    .put("serviceRunning", PlaybackService.running)
                    .put("message", p.message ?: "")
                    .put("dacDisplay", dacJson()))
            }
            "dac" -> json(dacJson())
            "albums" -> {
                val sort = Library.Sort.entries.getOrElse(extras?.getInt("sort", 0) ?: 0) { Library.Sort.ARTIST }
                val all = lib.albums(sort, arg, extras?.getString("artist"))
                val off = (extras?.getInt("offset", 0) ?: 0).coerceAtLeast(0)
                val lim = (extras?.getInt("limit", 60) ?: 60).coerceIn(1, 500)
                val arr = JSONArray()
                for (a in all.drop(off).take(lim)) arr.put(JSONObject().put("key", a.key).put("title", a.title).put("artist", a.artist)
                    .put("year", a.year).put("tracks", a.tracks).put("format", Track.formatLabel(a.codec, a.maxRate, a.maxBits)))
                json(JSONObject().put("total", all.size).put("albums", arr))
            }
            "album" -> {
                val key = arg ?: ""
                val tracks = lib.albumTracks(key)
                val a = lib.album(key)
                val arr = JSONArray(); tracks.forEachIndexed { i, t -> arr.put(trackJson(t, i)) }
                json(JSONObject().put("key", key).put("title", a?.title ?: tracks.firstOrNull()?.album ?: "")
                    .put("artist", a?.artist ?: "").put("year", a?.year ?: 0).put("tracks", arr))
            }
            "artists" -> json(JSONArray().apply { for ((n, c) in lib.artists()) put(JSONObject().put("name", n).put("albums", c)) })
            "search" -> json(JSONArray().apply { lib.search(arg ?: "", 100).forEachIndexed { i, t -> put(trackJson(t, i).put("albumKey", t.albumKey)) } })
            "queue" -> {
                val cur = p.currentIndex()
                json(JSONArray().apply { p.queue().forEachIndexed { i, q -> put(trackJson(q.track, i).put("current", i == cur)) } })
            }
            "play" -> {
                val tracks = lib.albumTracks(arg ?: "")
                if (tracks.isEmpty()) json(JSONObject().put("ok", false).put("error", "album not found")) else {
                    val shuffle = extras?.getBoolean("shuffle", false) == true
                    val start = (extras?.getInt("index", 0) ?: 0).coerceIn(0, tracks.size - 1)
                    if (shuffle) p.play(tracks.shuffled()) else p.play(tracks, start)
                    ok()
                }
            }
            "playTrack" -> {
                val t = lib.byIds(listOfNotNull(arg?.toLongOrNull()))
                if (t.isEmpty()) json(JSONObject().put("ok", false).put("error", "track not found")) else {
                    val album = lib.albumTracks(t[0].albumKey)
                    val i = album.indexOfFirst { it.id == t[0].id }
                    if (i >= 0) p.play(album, i) else p.play(t)
                    ok()
                }
            }
            "enqueue" -> {
                var tracks = lib.albumTracks(arg ?: "")
                val ti = extras?.getInt("index", -1) ?: -1
                if (ti >= 0) tracks = tracks.getOrNull(ti)?.let { listOf(it) } ?: emptyList()
                if (tracks.isEmpty()) json(JSONObject().put("ok", false).put("error", "nothing to add")) else {
                    p.enqueue(tracks, extras?.getBoolean("next", false) == true); ok()
                }
            }
            "control" -> {
                when (arg) {
                    "toggle" -> p.toggle(); "play" -> p.resume(); "pause" -> p.pause()
                    "next" -> p.next(); "prev" -> p.prev()
                    "seek" -> p.seekTo(extras?.getLong("ms", 0) ?: 0)
                    "repeat" -> p.setRepeat(((extras?.getInt("mode", -1) ?: -1).takeIf { it in 0..2 }) ?: ((p.repeatMode + 1) % 3))
                    "shuffle" -> p.setShuffle(!p.shuffle)
                    else -> return json(JSONObject().put("ok", false).put("error", "unknown action"))
                }
                ok()
            }
            "queueJump" -> { p.playAt(extras?.getInt("index", 0) ?: 0); ok() }
            "queueRemove" -> { p.remove(extras?.getInt("index", -1) ?: -1); ok() }
            "queueClear" -> { p.clear(); ok() }
            "volume" -> {
                if (Hub.volumeKind().let { it == "dac" || it == "digital" }) { Hub.volume = (extras?.getInt("value", 100) ?: 100) / 100f; ok() }
                else json(JSONObject().put("ok", false).put("error", "Set the volume on the DAC or amplifier (or turn on Digital volume in Player Pure)"))
            }
            "volumeStep" -> {
                if (Hub.nudgeDacVolume((extras?.getDouble("db", 0.0) ?: 0.0).coerceIn(-10.0, 10.0))) ok()
                else json(JSONObject().put("ok", false).put("error", "This DAC has no volume control over USB"))
            }
            "mute" -> {
                if (!Hub.hasDacMute()) json(JSONObject().put("ok", false).put("error", "This DAC has no mute over USB (use its remote or front button)"))
                else { Hub.setDacMute(when (arg) { "1", "on", "true" -> true; "0", "off", "false" -> false; else -> Hub.dacMuted != true }); ok() }
            }
            "art" -> {
                val t = if (arg == null || arg == "current") p.current() else lib.albumTracks(arg).firstOrNull()
                val b: Bitmap? = t?.let { try { Covers.bitmap(ctx, it.art, it.path, 360) } catch (_: Throwable) { null } }
                Bundle().apply {
                    if (b != null) putByteArray("jpeg", ByteArrayOutputStream().also { o -> b.compress(Bitmap.CompressFormat.JPEG, 85, o) }.toByteArray())
                }
            }
            else -> json(JSONObject().put("ok", false).put("error", "unknown method"))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
