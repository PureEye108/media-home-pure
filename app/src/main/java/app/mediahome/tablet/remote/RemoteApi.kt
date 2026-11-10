package app.mediahome.tablet.remote

import app.mediahome.tablet.Qr

/** What the web remote can do on the tablet. Implemented by [MediaBridge]; faked in tests. */
interface RemoteDevice {
    fun statusJson(): String
    fun art(): ByteArray?
    fun control(action: String, ms: Long?): Boolean
    fun setVolume(level: Int?, delta: Int?): Boolean
    fun launch(target: String): Boolean
    fun openUrl(url: String, app: String): Boolean
    fun wake(): Boolean
    /** The "scan to connect" link (address + PIN), or null when the tablet has no network address. */
    fun connectLink(): String?
    /** Phone touchpad: move (relative), tap, longpress or scroll at the on-screen pointer. */
    fun pointer(action: String, dx: Int, dy: Int): InputResult
    /** back, home, recents or notifications. */
    fun nav(action: String): InputResult
    /** Phone keyboard: set, type, backspace, clear or enter in the focused text field. */
    fun keyboard(action: String, text: String): InputResult
    /** Search in Brave: engine "web" (Brave's default search engine) or "youtube". */
    fun search(query: String, engine: String): Boolean
    /** True when Media Home has Android's "All files access" (needed by the Files tab). */
    fun hasFileAccess(): Boolean
    /** Plays or shows a file on the tablet (audio in the Music app, video in the Video app). */
    fun playFile(file: java.io.File, kind: String): Boolean
    /** Tells Android's media library about a new or changed file. */
    fun mediaScan(file: java.io.File)
    /** Finds the first YouTube video for [query], plays it in Brave and makes it full screen. */
    fun playFirstVideo(query: String): YouTubeSearch.Result
    /** Saves (or with null, removes) the YouTube Data API key used by [playFirstVideo]. */
    fun setYouTubeKey(key: String?)
    /** YouTube search results to pick from on the phone. */
    fun searchVideos(query: String): YouTubeSearch.ListResult
    /** Plays one YouTube video in Brave, full screen. */
    fun playVideo(videoId: String): Boolean
    /** A YouTube thumbnail (small JPEG), or null. */
    fun thumbnail(videoId: String): ByteArray?
    /** Calls Player Pure (its control provider). Returns Player Pure's JSON answer, or null if it is not installed. */
    fun music(method: String, arg: String?, extras: Map<String, Any>): String?
    /** An album cover from Player Pure (JPEG); key "current" = what is playing. */
    fun musicArt(key: String): ByteArray?
}

/**
 * Routes and security for the web remote.
 * - The page itself is public; every /api/ call needs the PIN in the "X-Pin" header.
 *   A custom header also means other websites can't silently send commands from your browser (CORS preflight is refused).
 * - 10 wrong PINs from one address lock that address out for 10 minutes.
 * - Links are limited to http, https, rtsp, rtmp and smb.
 */
class RemoteApi(
    private val device: RemoteDevice,
    private val pin: () -> String,
    private val page: () -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val files: FileStore? = null,
    /** Called with the client's address for every request that has the right PIN (the home screen's "remote connected"). */
    private val onClient: (String) -> Unit = {},
) {
    private val failures = HashMap<String, Pair<Int, Long>>()
    private val downloads = java.util.concurrent.ConcurrentHashMap<String, Pair<java.io.File, Long>>()
    private val random = java.security.SecureRandom()

    fun handle(r: HttpRequest): HttpResponse {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            return HttpResponse(200, "text/html; charset=utf-8", page(), PAGE_HEADERS)
        }
        if (r.method == "GET" && r.path == "/favicon.ico") return HttpResponse(204, "text/plain", ByteArray(0))
        if (r.method == "GET" && r.path.startsWith("/dl/")) return download(r)
        if (!r.path.startsWith("/api/")) return HttpResponse.json(404, err("not found"))
        if (r.method == "OPTIONS") return HttpResponse.json(405, err("cross-origin requests are not allowed"))

        if (isLockedOut(r.remoteAddress)) return HttpResponse.json(429, err("too many wrong PINs - wait 10 minutes"))
        if (!constantTimeEquals(r.header("x-pin") ?: "", pin())) {
            recordFailure(r.remoteAddress)
            return HttpResponse.json(401, err("PIN required"))
        }
        clearFailures(r.remoteAddress)
        onClient(r.remoteAddress)

        return when ("${r.method} ${r.path}") {
            "GET /api/ping" -> ok()
            "GET /api/status" -> HttpResponse.json(200, device.statusJson())
            "GET /api/art" -> device.art()?.let { HttpResponse(200, "image/jpeg", it) } ?: HttpResponse.json(404, err("no artwork"))
            "POST /api/control" -> {
                val a = r.param("action") ?: return bad("action missing")
                if (a !in ACTIONS) return bad("unknown action")
                result(device.control(a, r.param("ms")?.toLongOrNull()))
            }
            "POST /api/volume" -> {
                val level = r.param("level")?.toIntOrNull()
                val delta = r.param("delta")?.toIntOrNull()
                if (level == null && delta == null) return bad("level or delta missing")
                result(device.setVolume(level, delta))
            }
            "POST /api/launch" -> {
                val t = r.param("target") ?: return bad("target missing")
                if (t !in TARGETS) return bad("unknown target")
                result(device.launch(t))
            }
            "POST /api/open" -> {
                // The link can come as ?url=, as a form field, or as plain text (Android share-sheet apps send the
                // shared text, which may have words around the link).
                val raw = r.param("url")
                    ?: r.body.takeIf { (r.header("content-type") ?: "").startsWith("text/plain", ignoreCase = true) }
                    ?: return bad("url missing")
                val u = extractUrl(raw) ?: return bad("only http, https, rtsp, rtmp or smb links can be opened")
                if (!safeUrl(u)) return bad("only http, https, rtsp, rtmp or smb links can be opened")
                val app = r.param("app") ?: "brave"
                if (app !in APPS) return bad("unknown app")
                result(device.openUrl(u, app))
            }
            "POST /api/wake" -> result(device.wake())
            "POST /api/pointer" -> {
                val a = r.param("action") ?: "move"
                if (a !in POINTER_ACTIONS) return bad("unknown pointer action")
                val dx = r.param("dx")?.toIntOrNull() ?: 0
                val dy = r.param("dy")?.toIntOrNull() ?: 0
                if (Math.abs(dx) > InputLogic.MAX_STEP || Math.abs(dy) > InputLogic.MAX_STEP) return bad("step too large")
                input(device.pointer(a, dx, dy))
            }
            "POST /api/nav" -> {
                val a = r.param("action") ?: return bad("action missing")
                if (a !in NAV_ACTIONS) return bad("unknown action")
                input(device.nav(a))
            }
            "POST /api/keyboard" -> {
                val a = r.param("action") ?: "type"
                if (a !in KEY_ACTIONS) return bad("unknown keyboard action")
                val text = r.param("text") ?: plainBody(r) ?: ""
                if (text.length > MAX_TEXT) return bad("text too long")
                if ((a == "type") && text.isEmpty()) return bad("text missing")
                input(device.keyboard(a, text))
            }
            "POST /api/search" -> {
                val q = (r.param("q") ?: plainBody(r) ?: "").trim()
                if (q.isEmpty()) return bad("search text missing")
                if (q.length > MAX_TEXT) return bad("text too long")
                val engine = r.param("engine") ?: "web"
                if (engine !in ENGINES) return bad("unknown search engine")
                result(device.search(q, engine))
            }
            "POST /api/youtube/first" -> {
                val q = (r.param("q") ?: plainBody(r) ?: "").trim()
                if (q.isEmpty()) return bad("search text missing")
                if (q.length > MAX_TEXT) return bad("text too long")
                val res = device.playFirstVideo(q)
                when (res.outcome) {
                    YouTubeSearch.Outcome.OK -> HttpResponse.json(200, Json().put("ok", true).put("videoId", res.videoId)
                        .put("title", res.title).toString())
                    else -> youtubeError(res.outcome)
                }
            }
            "POST /api/youtube/search" -> {
                val q = (r.param("q") ?: plainBody(r) ?: "").trim()
                if (q.isEmpty()) return bad("search text missing")
                if (q.length > MAX_TEXT) return bad("text too long")
                val res = device.searchVideos(q)
                when (res.outcome) {
                    YouTubeSearch.Outcome.OK, YouTubeSearch.Outcome.NONE -> HttpResponse.json(200, Json().put("ok", true)
                        .put("videos", res.videos.map { Json().put("id", it.id).put("title", it.title).put("channel", it.channel) }).toString())
                    else -> youtubeError(res.outcome)
                }
            }
            "POST /api/youtube/play" -> {
                val id = r.param("id") ?: return bad("video missing")
                if (!YouTubeSearch.isVideoId(id)) return bad("not a YouTube video")
                result(device.playVideo(id))
            }
            "GET /api/youtube/thumb" -> {
                val id = r.param("id") ?: return bad("video missing")
                if (!YouTubeSearch.isVideoId(id)) return bad("not a YouTube video")
                device.thumbnail(id)?.let { HttpResponse(200, "image/jpeg", it) } ?: HttpResponse.json(404, err("no picture"))
            }
            "POST /api/youtube/key" -> {
                if (r.param("action") == "clear") {
                    device.setYouTubeKey(null); return ok()
                }
                val key = (r.param("key") ?: plainBody(r) ?: "").trim()
                if (!YouTubeSearch.isValidKey(key)) return bad("that is not a YouTube API key (it starts with AIza and has 39 characters)")
                device.setYouTubeKey(key)
                ok()
            }
            "GET /api/music/status" -> HttpResponse.json(200, device.music("status", null, emptyMap()) ?: Json().put("installed", false).toString())
            "GET /api/music/albums" -> {
                val q = r.param("q")?.trim()?.takeIf { it.isNotEmpty() }
                if ((q?.length ?: 0) > 200) return bad("search text too long")
                val ex = HashMap<String, Any>()
                ex["offset"] = (r.param("offset")?.toIntOrNull() ?: 0).coerceIn(0, 1_000_000)
                ex["limit"] = (r.param("limit")?.toIntOrNull() ?: 60).coerceIn(1, 200)
                ex["sort"] = (r.param("sort")?.toIntOrNull() ?: 0).coerceIn(0, 3)
                r.param("artist")?.take(500)?.let { ex["artist"] = it }
                music("albums", q, ex)
            }
            "GET /api/music/album" -> music("album", r.param("key")?.takeIf { it.length in 1..4000 } ?: return bad("album missing"), emptyMap())
            "GET /api/music/artists" -> music("artists", null, emptyMap())
            "GET /api/music/search" -> music("search", r.param("q")?.trim()?.takeIf { it.length in 1..200 } ?: return bad("search text missing"), emptyMap())
            "GET /api/music/queue" -> music("queue", null, emptyMap())
            "POST /api/music/play" -> {
                val key = r.param("key")?.takeIf { it.length in 1..4000 } ?: return bad("album missing")
                music("play", key, mapOf("index" to (r.param("index")?.toIntOrNull() ?: 0).coerceAtLeast(0), "shuffle" to (r.param("shuffle") == "1")))
            }
            "POST /api/music/play-track" -> music("playTrack", (r.param("id")?.toLongOrNull() ?: return bad("track missing")).toString(), emptyMap())
            "POST /api/music/enqueue" -> {
                val key = r.param("key")?.takeIf { it.length in 1..4000 } ?: return bad("album missing")
                music("enqueue", key, mapOf("index" to (r.param("index")?.toIntOrNull() ?: -1), "next" to (r.param("next") == "1")))
            }
            "POST /api/music/control" -> {
                val a = r.param("action") ?: return bad("action missing")
                if (a !in MUSIC_ACTIONS) return bad("unknown action")
                val ex = HashMap<String, Any>()
                r.param("ms")?.toLongOrNull()?.let { ex["ms"] = it.coerceAtLeast(0) }
                r.param("mode")?.toIntOrNull()?.let { ex["mode"] = it }
                music("control", a, ex)
            }
            "POST /api/music/queue-jump" -> music("queueJump", null, mapOf("index" to (r.param("index")?.toIntOrNull() ?: return bad("index missing"))))
            "POST /api/music/queue-remove" -> music("queueRemove", null, mapOf("index" to (r.param("index")?.toIntOrNull() ?: return bad("index missing"))))
            "POST /api/music/queue-clear" -> music("queueClear", null, emptyMap())
            "POST /api/music/volume" -> music("volume", null, mapOf("value" to ((r.param("value")?.toIntOrNull() ?: return bad("value missing")).coerceIn(0, 100))))
            "GET /api/music/dac" -> music("dac", null, emptyMap())
            "POST /api/music/mute" -> {
                val on = when (r.param("on")) { "1" -> "1"; "0" -> "0"; null, "toggle" -> "toggle"; else -> return bad("on must be 0 or 1") }
                music("mute", on, emptyMap())
            }
            "POST /api/music/volume-step" -> {
                val db = r.param("db")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return bad("db missing")
                music("volumeStep", null, mapOf("db" to db.coerceIn(-10.0, 10.0)))
            }
            "GET /api/music/art" -> {
                val key = r.param("key")?.takeIf { it.length in 1..4000 } ?: "current"
                device.musicArt(key)?.let { HttpResponse(200, "image/jpeg", it) } ?: HttpResponse.json(404, err("no cover"))
            }
            "GET /api/files/roots" -> files { it.rootsJson().let { j -> HttpResponse.json(200, j) } }
            "GET /api/files/list" -> files { fs ->
                HttpResponse.json(200, fs.list(need(r, "root"), r.param("path") ?: "", r.param("hidden") == "1"))
            }
            "POST /api/files/mkdir" -> files { fs -> okPath(fs.mkdir(need(r, "root"), r.param("path") ?: "", need(r, "name"))) }
            "POST /api/files/rename" -> files { fs -> okPath(fs.rename(need(r, "root"), need(r, "path"), need(r, "name"))) }
            "POST /api/files/delete" -> files { fs -> okPath(fs.trash(need(r, "root"), need(r, "path"), clock())) }
            "POST /api/files/empty-trash" -> files { fs ->
                HttpResponse.json(200, Json().put("ok", true).put("removed", fs.emptyTrash(need(r, "root"))).toString())
            }
            "POST /api/files/upload" -> files { fs ->
                val body = r.bodyStream ?: throw FileStore.FileError(400, "upload body missing")
                val f = fs.upload(need(r, "root"), r.param("path") ?: "", need(r, "name"), r.contentLength, body)
                device.mediaScan(f)
                HttpResponse.json(200, Json().put("ok", true).put("name", f.name).toString())
            }
            "POST /api/files/play" -> files { fs ->
                val f = fs.file(need(r, "root"), need(r, "path"))
                val kind = FileStore.kindOf(f.name)
                if (kind == "other") throw FileStore.FileError(400, "only music, video and pictures can be played")
                result(device.playFile(f, kind))
            }
            "GET /api/files/token" -> files { fs ->
                val f = fs.file(need(r, "root"), need(r, "path"))
                val now = clock()
                downloads.entries.removeIf { it.value.second < now }
                val t = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { b -> "%02x".format(b) }
                downloads[t] = f to now + DOWNLOAD_MS
                HttpResponse.json(200, Json().put("ok", true)
                    .put("url", "/dl/$t/" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")).toString())
            }
            "GET /api/qr" -> device.connectLink()?.let { HttpResponse(200, "image/svg+xml", Qr.svg(it)) }
                ?: HttpResponse.json(409, err("the tablet has no Wi-Fi address yet"))
            else -> if (r.path in KNOWN_PATHS) HttpResponse.json(405, err("method not allowed")) else HttpResponse.json(404, err("not found"))
        }
    }

    @Synchronized
    private fun isLockedOut(ip: String): Boolean {
        val f = failures[ip] ?: return false
        if (clock() - f.second >= LOCKOUT_MS) {
            failures.remove(ip)
            return false
        }
        return f.first >= MAX_FAILURES
    }

    @Synchronized
    private fun recordFailure(ip: String) {
        val f = failures[ip]
        failures[ip] = Pair((f?.first ?: 0) + 1, clock())
    }

    @Synchronized
    private fun clearFailures(ip: String) {
        failures.remove(ip)
    }

    private fun ok() = HttpResponse.json(200, "{\"ok\":true}")
    private fun result(success: Boolean) =
        if (success) ok() else HttpResponse.json(409, err("the tablet could not do that - see the hints on the page"))
    private fun bad(msg: String) = HttpResponse.json(400, err(msg))
    private fun need(r: HttpRequest, name: String): String =
        r.param(name)?.takeIf { it.isNotEmpty() } ?: throw FileStore.FileError(400, "$name missing")
    private fun youtubeError(o: YouTubeSearch.Outcome): HttpResponse = when (o) {
        YouTubeSearch.Outcome.NO_KEY -> coded(409, "no_yt_key", "add your free YouTube key first (see the box below)")
        YouTubeSearch.Outcome.KEY_REJECTED -> coded(409, "yt_key_rejected",
            "YouTube refused the key - check it in Google Cloud (YouTube Data API v3 enabled, restrictions)")
        YouTubeSearch.Outcome.QUOTA -> coded(409, "yt_quota", "today's free YouTube searches are used up - try again tomorrow")
        YouTubeSearch.Outcome.NONE -> HttpResponse.json(404, err("no video found for that"))
        YouTubeSearch.Outcome.NETWORK -> HttpResponse.json(502, err("could not reach YouTube - check the tablet's internet"))
        YouTubeSearch.Outcome.OPEN_FAILED -> HttpResponse.json(409, err("the tablet could not open Brave - see the hints on the page"))
        YouTubeSearch.Outcome.OK -> ok()
    }

    private fun coded(status: Int, code: String, msg: String) =
        HttpResponse.json(status, "{\"ok\":false,\"code\":\"$code\",\"error\":\"" + Json.esc(msg) + "\"}")
    private fun okPath(path: String) = HttpResponse.json(200, Json().put("ok", true).put("path", path).toString())

    /** Runs a Files-tab command: checks that the feature is available and turns file errors into clear answers. */
    private fun files(block: (FileStore) -> HttpResponse): HttpResponse {
        val fs = files ?: return HttpResponse.json(404, err("files are not available"))
        if (!device.hasFileAccess()) {
            return HttpResponse.json(409, "{\"ok\":false,\"code\":\"no_files_access\",\"error\":\"" +
                Json.esc("allow All files access for Media Home on the tablet (see the tip on this page)") + "\"}")
        }
        return try {
            block(fs)
        } catch (e: FileStore.FileError) {
            HttpResponse.json(e.status, err(e.message ?: "file error"))
        } catch (e: java.io.IOException) {
            HttpResponse.json(500, err("drive error: " + (e.message ?: "unknown")))
        } catch (e: SecurityException) {
            HttpResponse.json(403, err("Android did not allow this"))
        }
    }

    /** GET /dl/<token>/<name>: a file download link made by /api/files/token (valid 5 minutes, no PIN needed). */
    private fun download(r: HttpRequest): HttpResponse {
        val token = r.path.removePrefix("/dl/").substringBefore('/')
        val entry = downloads[token]
        if (entry == null || entry.second < clock()) return HttpResponse.json(404, err("link expired - tap Download again"))
        val f = entry.first
        if (!f.isFile) return HttpResponse.json(404, err("file not found"))
        val ascii = f.name.map { c -> if (c.code in 32..126 && c != '"' && c != '\\') c else '_' }.joinToString("")
        val utf8 = java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        return HttpResponse(
            200, "application/octet-stream", ByteArray(0),
            mapOf("Content-Disposition" to "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf8"),
            stream = f.inputStream(), streamLength = f.length(),
        )
    }
    private fun plainBody(r: HttpRequest): String? =
        r.body.takeIf { (r.header("content-type") ?: "").startsWith("text/plain", ignoreCase = true) }
    private fun input(res: InputResult): HttpResponse = when (res) {
        InputResult.OK -> ok()
        InputResult.NO_SERVICE -> HttpResponse.json(409, "{\"ok\":false,\"code\":\"no_service\",\"error\":\"" +
            Json.esc("turn on Mouse & keyboard for Media Home on the tablet (see the tip on this page)") + "\"}")
        InputResult.NO_FIELD -> HttpResponse.json(409, "{\"ok\":false,\"code\":\"no_field\",\"error\":\"" +
            Json.esc("tap a text field on the tablet first") + "\"}")
        InputResult.FAILED -> HttpResponse.json(409, err("the tablet could not do that"))
    }
    private fun err(msg: String) = "{\"ok\":false,\"error\":\"" + Json.esc(msg) + "\"}"

    /** Player Pure's answer as the HTTP response: 404 when it is not installed, 400 when it says ok=false. */
    private fun music(method: String, arg: String?, extras: Map<String, Any>): HttpResponse {
        val j = device.music(method, arg, extras) ?: return HttpResponse.json(404, err("Player Pure is not installed"))
        val failed = OK_FALSE.containsMatchIn(j)
        return HttpResponse.json(if (failed) 400 else 200, j)
    }

    companion object {
        const val MAX_FAILURES = 10
        const val LOCKOUT_MS = 600_000L   // 10 minutes: compensates for the short 4-digit PIN
        val ACTIONS = setOf("play", "pause", "toggle", "next", "prev", "stop", "seek")
        val TARGETS = setOf("MUSIC", "YOUTUBE", "VIDEO", "JELLYFIN", "EMBY", "BROWSER", "AIRPLAY", "KODI", "HOME")
        val APPS = setOf("auto", "brave", "vlc")
        val POINTER_ACTIONS = setOf("move", "tap", "longpress", "scroll")
        val NAV_ACTIONS = setOf("back", "home", "recents", "notifications", "fullscreen")
        val KEY_ACTIONS = setOf("set", "type", "backspace", "clear", "enter")
        val ENGINES = setOf("web", "youtube")
        const val MAX_TEXT = 2000
        const val DOWNLOAD_MS = 5 * 60_000L
        /** Paths whose request body is streamed (large uploads). */
        val STREAMING_PATHS = setOf("/api/files/upload")

        private val MEDIA_EXT = Regex(
            "\\.(mp3|flac|alac|m4a|aac|ogg|opus|wav|aiff?|dsf|dff|ape|wv|mp4|m4v|mkv|webm|avi|mov|ts|m2ts|m3u8?|mpd|pls)$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * For app=auto (used by the phone "send to tablet" shortcuts): stream protocols and media files go to VLC,
         * everything else (YouTube, web pages) opens in Brave.
         */
        fun pickApp(url: String): String {
            val l = url.lowercase()
            if (l.startsWith("rtsp://") || l.startsWith("rtmp://") || l.startsWith("smb://")) return "vlc"
            val path = l.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
            return if (MEDIA_EXT.containsMatchIn(path)) "vlc" else "brave"
        }
        private val KNOWN_PATHS = setOf(
            "/api/ping", "/api/status", "/api/art", "/api/control", "/api/volume", "/api/launch", "/api/open", "/api/wake", "/api/qr", "/api/pointer", "/api/nav", "/api/keyboard", "/api/search",
            "/api/files/roots", "/api/files/list", "/api/files/mkdir", "/api/files/rename", "/api/files/delete",
            "/api/files/empty-trash", "/api/files/upload", "/api/files/play", "/api/files/token", "/api/youtube/first", "/api/youtube/key", "/api/youtube/search", "/api/youtube/play", "/api/youtube/thumb",
            "/api/music/status", "/api/music/albums", "/api/music/album", "/api/music/artists", "/api/music/search", "/api/music/queue",
            "/api/music/play", "/api/music/play-track", "/api/music/enqueue", "/api/music/control", "/api/music/queue-jump",
            "/api/music/queue-remove", "/api/music/queue-clear", "/api/music/volume", "/api/music/art",
            "/api/music/dac", "/api/music/mute", "/api/music/volume-step",
        )
        private val OK_FALSE = Regex("^\\{\\s*\"ok\"\\s*:\\s*false")
        val MUSIC_ACTIONS = setOf("toggle", "play", "pause", "next", "prev", "seek", "repeat", "shuffle")
        private val PAGE_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )

        private val URL_IN_TEXT = Regex("(?i)\\b(?:https?|rtsp|rtmp|smb)://[^\\s<>\"']+")

        /** The first http/https/rtsp/rtmp/smb link in [text] (shared text can be "Title https://..."), or null. */
        fun extractUrl(text: String): String? =
            URL_IN_TEXT.find(text.trim())?.value?.trimEnd('.', ',', ';', '!', '?')

        fun safeUrl(u: String): Boolean {
            if (u.length > 2048 || u.any { it.isWhitespace() || it.code < 32 }) return false
            val l = u.lowercase()
            return listOf("http://", "https://", "rtsp://", "rtmp://", "smb://").any { l.startsWith(it) && l.length > it.length }
        }

        fun constantTimeEquals(given: String, expected: String): Boolean {
            if (expected.isEmpty()) return false
            var diff = given.length xor expected.length
            for (i in expected.indices) {
                val g = if (i < given.length) given[i].code else 0
                diff = diff or (g xor expected[i].code)
            }
            return diff == 0
        }
    }
}

/** Tiny JSON object writer (no Android or third-party dependency, so it is unit-testable). */
class Json {
    private val sb = StringBuilder("{")
    private var first = true

    fun put(key: String, value: Any?): Json {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(esc(key)).append("\":")
        write(value)
        return this
    }

    private fun write(v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Json -> sb.append(v.toString())
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x) }
                sb.append(']')
            }
            else -> sb.append('"').append(esc(v.toString())).append('"')
        }
    }

    override fun toString(): String = "$sb}"

    companion object {
        fun esc(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '<' -> append("\\u003c")
                    else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
        }
    }
}
