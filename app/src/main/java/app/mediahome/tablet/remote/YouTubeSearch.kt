package app.mediahome.tablet.remote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Play first video": finds the top YouTube result for some words with YouTube's official Data API v3
 * (search.list, 100 of the free 10,000 daily quota units per search). Needs the user's own free API key (docs/YOUTUBE-KEY.md).
 * The key can be restricted to this app: requests carry the app's package name and signing-certificate SHA-1.
 */
object YouTubeSearch {
    enum class Outcome { OK, NO_KEY, NONE, KEY_REJECTED, QUOTA, NETWORK, OPEN_FAILED }
    class Result(val outcome: Outcome, val videoId: String? = null, val title: String? = null)
    class Video(val id: String, val title: String, val channel: String)
    class ListResult(val outcome: Outcome, val videos: List<Video> = emptyList())

    /** This app's package name and signing-certificate SHA-1 (hex), sent with each request for the key's "Android apps" restriction. */
    @Volatile var androidPackage: String? = null
    @Volatile var androidCert: String? = null

    /** Reads the package name and the SHA-1 of the signing certificate (each build/fork has its own). */
    fun identify(ctx: android.content.Context) {
        if (androidPackage == ctx.packageName && androidCert != null) return
        androidPackage = ctx.packageName
        androidCert = try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val info = if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            else pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            cert?.let { sha1Hex(it) }
        } catch (e: Exception) { null }
    }

    fun sha1Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02X", it) }

    /** "27:1C:B1:..." as Google Cloud shows it. */
    fun colonHex(hex: String): String = hex.chunked(2).joinToString(":")

    private val KEY = Regex("^AIza[0-9A-Za-z_-]{35}$")
    private val VIDEO_ID = Regex("^[0-9A-Za-z_-]{11}$")

    fun isValidKey(key: String): Boolean = KEY.matches(key.trim())

    /** The key built into this app (signing/youtube.properties at build time), or null. */
    fun builtInKey(): String? = app.mediahome.tablet.BuildConfig.YT_BUILTIN_KEY.takeIf { isValidKey(it) }

    /** A key set on the tablet (Settings or the web remote) wins; otherwise the built-in one. */
    fun effectiveKey(saved: String?): String? = saved?.trim()?.takeIf { it.isNotEmpty() } ?: builtInKey()

    fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

    fun url(query: String, key: String, max: Int = 1): String =
        "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=$max" +
            "&q=" + URLEncoder.encode(query, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /** Reads search.list's answer (or its error): the first video. */
    fun parse(status: Int, body: String): Result {
        val l = parseList(status, body)
        val v = l.videos.firstOrNull() ?: return Result(if (l.outcome == Outcome.OK) Outcome.NONE else l.outcome)
        return Result(Outcome.OK, v.id, v.title)
    }

    /** Reads search.list's answer (or its error): all videos, in YouTube's order. */
    fun parseList(status: Int, body: String): ListResult {
        val json = try { JSONObject(body) } catch (e: Exception) { return ListResult(Outcome.NETWORK) }
        if (status != 200) {
            val err = json.optJSONObject("error")
            val reasons = buildList {
                val list = err?.optJSONArray("errors")
                if (list != null) for (i in 0 until list.length()) add(list.optJSONObject(i)?.optString("reason") ?: "")
                err?.optJSONArray("details")?.let { d ->
                    for (i in 0 until d.length()) add(d.optJSONObject(i)?.optString("reason") ?: "")
                }
            }
            return when {
                reasons.any { it.contains("quota", ignoreCase = true) || it.contains("limitexceeded", ignoreCase = true) } -> ListResult(Outcome.QUOTA)
                status == 400 || status == 403 -> ListResult(Outcome.KEY_REJECTED)
                else -> ListResult(Outcome.NETWORK)
            }
        }
        val items = json.optJSONArray("items") ?: return ListResult(Outcome.NONE)
        val out = ArrayList<Video>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optJSONObject("id")?.optString("videoId") ?: continue
            if (!VIDEO_ID.matches(id)) continue
            val sn = item.optJSONObject("snippet")
            out += Video(id, unescape(sn?.optString("title").orEmpty()), unescape(sn?.optString("channelTitle").orEmpty()))
        }
        return ListResult(if (out.isEmpty()) Outcome.NONE else Outcome.OK, out)
    }

    /** search.list titles come HTML-escaped ("Rock &amp; Roll"). */
    fun unescape(s: String): String = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&amp;", "&")

    fun search(query: String, key: String?): Result {
        val l = searchList(query, key, 1)
        val v = l.videos.firstOrNull() ?: return Result(if (l.outcome == Outcome.OK) Outcome.NONE else l.outcome)
        return Result(Outcome.OK, v.id, v.title)
    }

    /** Up to [max] videos for [query] (one search.list call, 100 quota units whatever [max] is). */
    fun searchList(query: String, key: String?, max: Int = 12): ListResult {
        if (key.isNullOrBlank()) return ListResult(Outcome.NO_KEY)
        return try {
            val c = URL(url(query, key.trim(), max.coerceIn(1, 25))).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            androidPackage?.let { c.setRequestProperty("X-Android-Package", it) }
            androidCert?.let { c.setRequestProperty("X-Android-Cert", it) }
            c.setRequestProperty("User-Agent", "MediaHomePure/1.0 (YouTube search)")
            try {
                val code = c.responseCode
                val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                parseList(code, body)
            } finally {
                c.disconnect()
            }
        } catch (e: java.io.IOException) {
            ListResult(Outcome.NETWORK)
        }
    }
}
