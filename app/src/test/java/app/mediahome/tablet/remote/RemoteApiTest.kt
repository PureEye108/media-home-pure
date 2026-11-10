package app.mediahome.tablet.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the real HTTP server over a real socket, with a fake tablet behind it. */
class RemoteApiTest {
    private class FakeDevice : RemoteDevice {
        val calls = mutableListOf<String>()
        override fun statusJson() = Json().put("ok", true).put("title", "Test \"song\"").put("playing", true).toString()
        override fun art(): ByteArray? = null
        override fun control(action: String, ms: Long?) = true.also { calls += "control:$action:${ms ?: ""}" }
        override fun setVolume(level: Int?, delta: Int?) = true.also { calls += "volume:${level ?: ""}:${delta ?: ""}" }
        override fun launch(target: String) = true.also { calls += "launch:$target" }
        override fun openUrl(url: String, app: String) = true.also { calls += "open:$app:$url" }
        override fun wake() = true.also { calls += "wake" }
        var link: String? = "http://192.168.1.50:8080/#pin=1008"
        override fun connectLink() = link
        var inputResult = InputResult.OK
        override fun pointer(action: String, dx: Int, dy: Int) = inputResult.also { calls += "pointer:$action:$dx:$dy" }
        override fun nav(action: String) = inputResult.also { calls += "nav:$action" }
        override fun keyboard(action: String, text: String) = inputResult.also { calls += "key:$action:$text" }
        override fun search(query: String, engine: String) = true.also { calls += "search:$engine:$query" }
        var fileAccess = true
        override fun hasFileAccess() = fileAccess
        override fun playFile(file: java.io.File, kind: String) = true.also { calls += "play:$kind:${file.name}" }
        override fun mediaScan(file: java.io.File) { calls += "scan:${file.name}" }
        var first = YouTubeSearch.Result(YouTubeSearch.Outcome.OK, "dQw4w9WgXcQ", "A song")
        override fun playFirstVideo(query: String) = first.also { calls += "first:$query" }
        var ytKey: String? = null
        override fun setYouTubeKey(key: String?) { ytKey = key; calls += "key:${key ?: "-"}" }
        var list = YouTubeSearch.ListResult(YouTubeSearch.Outcome.OK, listOf(
            YouTubeSearch.Video("zqNTltOGh5c", "Blue in Green", "Miles Davis"), YouTubeSearch.Video("ylXk1LBvIqU", "So What", "Miles Davis")))
        override fun searchVideos(query: String) = list.also { calls += "search-yt:$query" }
        override fun playVideo(videoId: String) = true.also { calls += "play-yt:$videoId" }
        override fun thumbnail(videoId: String): ByteArray? = if (videoId == "zqNTltOGh5c") byteArrayOf(-1, -40, -1) else null
        var playerPure = true
        override fun music(method: String, arg: String?, extras: Map<String, Any>): String? {
            if (!playerPure) return null
            calls += "pp:$method:${arg ?: ""}:" + extras.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
            return when (method) {
                "status" -> Json().put("installed", true).put("playing", true).put("title", "Blue in Green").put("bitPerfect", true).toString()
                "dac" -> Json().put("connected", true).put("rate", "96").put("unit", "kHz").put("volume", "-32.0 dB").put("hasMute", true).toString()
                "enqueue" -> if (arg == "missing") Json().put("ok", false).put("error", "nothing to add").toString() else Json().put("ok", true).toString()
                else -> Json().put("ok", true).toString()
            }
        }
        override fun musicArt(key: String): ByteArray? = if (key == "current" || key == "a|/m") byteArrayOf(-1, -40, -1) else null
    }

    private lateinit var device: FakeDevice
    private lateinit var server: MiniHttpServer
    private var now = 1_000_000L
    private val base get() = "http://127.0.0.1:${server.localPort}"

    private val clients = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun up() {
        device = FakeDevice()
        clients.clear()
        val api = RemoteApi(device, { "1008" }, { "<html>remote</html>".toByteArray() }, { now }, onClient = { clients += it })
        server = MiniHttpServer(0) { api.handle(it) }
        server.start()
    }

    @After fun down() = server.stop()

    private fun req(method: String, path: String, pin: String? = "1008"): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (pin != null) c.setRequestProperty("X-Pin", pin)
        if (method == "POST") { c.doOutput = true; c.outputStream.use { } }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to body
    }

    @Test fun pageIsServedWithoutPin() {
        val (code, body) = req("GET", "/", pin = null)
        assertEquals(200, code)
        assertTrue(body.contains("remote"))
    }

    @Test fun apiNeedsCorrectPin() {
        assertEquals(401, req("GET", "/api/status", pin = null).first)
        assertEquals(401, req("GET", "/api/status", pin = "1234").first)
        assertEquals(401, req("GET", "/api/status", pin = "10080").first)
        val (code, body) = req("GET", "/api/status")
        assertEquals(200, code)
        assertTrue(body.contains("\"title\":\"Test \\\"song\\\"\""))
    }

    @Test fun pinInQueryStringIsNotAccepted() {
        assertEquals(401, req("GET", "/api/status?pin=1008", pin = null).first)
    }

    @Test fun commandsReachTheTablet() {
        assertEquals(200, req("POST", "/api/control?action=next").first)
        assertEquals(200, req("POST", "/api/control?action=seek&ms=61000").first)
        assertEquals(200, req("POST", "/api/volume?level=7").first)
        assertEquals(200, req("POST", "/api/launch?target=MUSIC").first)
        assertEquals(200, req("POST", "/api/wake").first)
        assertEquals(listOf("control:next:", "control:seek:61000", "volume:7:", "launch:MUSIC", "wake"), device.calls)
    }

    @Test fun badInputIsRejected() {
        assertEquals(400, req("POST", "/api/control?action=format").first)
        assertEquals(400, req("POST", "/api/launch?target=SETTINGS").first)
        assertEquals(405, req("POST", "/api/status").first)
        assertEquals(405, req("GET", "/api/control?action=next").first)
        assertEquals(404, req("GET", "/api/nope").first)
        assertTrue(device.calls.isEmpty())
    }

    @Test fun onlySafeLinksCanBeOpened() {
        fun open(u: String, app: String = "brave") = req("POST", "/api/open?app=$app&url=" + URLEncoder.encode(u, "UTF-8")).first
        assertEquals(200, open("https://www.youtube.com/watch?v=abc"))
        assertEquals(200, open("rtsp://192.168.1.5/stream", "vlc"))
        assertEquals(400, open("file:///sdcard/secret.txt"))
        assertEquals(400, open("intent://evil#Intent;end"))
        assertEquals(400, open("javascript:alert(1)"))
        assertEquals(400, open("https://x.com", "chrome"))
        assertEquals(2, device.calls.size)
    }

    @Test fun lockoutAfterTenWrongPins() {
        repeat(10) { assertEquals(401, req("GET", "/api/ping", pin = "0000").first) }
        assertEquals(429, req("GET", "/api/ping").first)          // even the right PIN is refused while locked
        now += RemoteApi.LOCKOUT_MS + 1
        assertEquals(200, req("GET", "/api/ping").first)          // unlocked after 10 minutes
    }

    @Test fun safeUrlAndPinCompare() {
        assertTrue(RemoteApi.safeUrl("smb://nas/music"))
        assertFalse(RemoteApi.safeUrl("https://"))
        assertFalse(RemoteApi.safeUrl("https://a b"))
        assertTrue(RemoteApi.constantTimeEquals("1008", "1008"))
        assertFalse(RemoteApi.constantTimeEquals("100", "1008"))
        assertFalse(RemoteApi.constantTimeEquals("", ""))
    }

    @Test fun onlyHomeNetworkClientsAreServed() {
        fun local(ip: String) = MiniHttpServer.isLocalNetwork(java.net.InetAddress.getByName(ip))
        for (ip in listOf("127.0.0.1", "192.168.1.20", "10.0.0.7", "172.20.3.4", "169.254.1.1", "::1", "fe80::1", "fd12:3456::9")) {
            assertTrue("$ip should be allowed", local(ip))
        }
        for (ip in listOf("8.8.8.8", "100.64.0.1", "172.32.0.1", "2001:db8::1", "2405:4800::1")) {
            assertFalse("$ip should be refused", local(ip))
        }
    }

    @Test fun qrCodeNeedsPinAndContainsTheLink() {
        assertEquals(401, req("GET", "/api/qr", pin = null).first)
        val c = URL("$base/api/qr").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Pin", "1008")
        assertEquals(200, c.responseCode)
        assertEquals("image/svg+xml", c.contentType)
        val svg = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(svg.startsWith("<svg") && svg.contains("<path") && !svg.contains("script"))
        device.link = null
        assertEquals(409, req("GET", "/api/qr").first)
    }

    @Test fun airplayTargetAndAutoLinks() {
        assertEquals(200, req("POST", "/api/launch?target=AIRPLAY").first)
        val u = URLEncoder.encode("https://youtu.be/abc?si=x", "UTF-8")
        assertEquals(200, req("POST", "/api/open?app=auto&url=$u").first)
        assertEquals(listOf("launch:AIRPLAY", "open:auto:https://youtu.be/abc?si=x"), device.calls)
        assertEquals("brave", RemoteApi.pickApp("https://www.youtube.com/watch?v=abc"))
        assertEquals("brave", RemoteApi.pickApp("https://music.youtube.com/watch?v=abc&list=x"))
        assertEquals("brave", RemoteApi.pickApp("https://example.com/"))
        assertEquals("vlc", RemoteApi.pickApp("https://radio.example.com/live/stream.m3u8?token=1"))
        assertEquals("vlc", RemoteApi.pickApp("http://nas.local/Music/Album/01%20Song.FLAC"))
        assertEquals("vlc", RemoteApi.pickApp("smb://nas/Movies/film.mkv"))
        assertEquals("vlc", RemoteApi.pickApp("rtsp://192.168.1.5/stream"))
        assertEquals("brave", RemoteApi.pickApp("https://example.com/mp3"))
    }

    private fun postBody(path: String, body: String, type: String, pin: String = "1008"): Int {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("X-Pin", pin)
        c.setRequestProperty("Content-Type", type)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    @Test fun shareSheetShortcutsCanSendLinks() {
        // iPhone Shortcuts "Get contents of URL" with a Form body
        assertEquals(200, postBody("/api/open", "app=auto&url=" + URLEncoder.encode("https://youtu.be/xyz", "UTF-8"),
            "application/x-www-form-urlencoded"))
        // Android share sheet (HTTP Shortcuts) sends the shared text, sometimes with a title around the link
        assertEquals(200, postBody("/api/open?app=auto", "Great song - https://music.youtube.com/watch?v=abc", "text/plain; charset=utf-8"))
        assertEquals(400, postBody("/api/open?app=auto", "no link here", "text/plain"))
        assertEquals(400, postBody("/api/open?app=auto", "file:///sdcard/a.mp3", "text/plain"))
        assertEquals(401, postBody("/api/open?app=auto", "https://youtu.be/xyz", "text/plain", pin = "0000"))
        assertEquals(listOf("open:auto:https://youtu.be/xyz", "open:auto:https://music.youtube.com/watch?v=abc"), device.calls)
        assertEquals("https://example.com/a?b=1", RemoteApi.extractUrl("see https://example.com/a?b=1."))
    }

    @Test fun touchpadAndKeyboardCommands() {
        assertEquals(200, req("POST", "/api/pointer?dx=35&dy=-12").first)
        assertEquals(200, req("POST", "/api/pointer?action=tap").first)
        assertEquals(200, req("POST", "/api/pointer?action=scroll&dx=0&dy=-300").first)
        assertEquals(200, req("POST", "/api/nav?action=back").first)
        val t = URLEncoder.encode("héllo wörld 👋", "UTF-8")
        assertEquals(200, req("POST", "/api/keyboard?action=set&text=$t").first)
        assertEquals(200, postBody("/api/keyboard?action=type", "more text", "text/plain; charset=utf-8"))
        assertEquals(200, req("POST", "/api/keyboard?action=enter").first)
        assertEquals(listOf("pointer:move:35:-12", "pointer:tap:0:0", "pointer:scroll:0:-300", "nav:back",
            "key:set:héllo wörld 👋", "key:type:more text", "key:enter:"), device.calls)
    }

    @Test fun touchpadInputIsValidated() {
        assertEquals(400, req("POST", "/api/pointer?action=drag").first)
        assertEquals(400, req("POST", "/api/pointer?dx=999999").first)
        assertEquals(400, req("POST", "/api/nav?action=power").first)
        assertEquals(400, req("POST", "/api/keyboard?action=paste").first)
        assertEquals(400, req("POST", "/api/keyboard?action=type").first)
        assertEquals(400, req("POST", "/api/keyboard?action=set&text=" + "x".repeat(RemoteApi.MAX_TEXT + 1)).first)
        assertEquals(401, req("POST", "/api/pointer?action=tap", pin = null).first)
        assertTrue(device.calls.isEmpty())
        device.inputResult = InputResult.NO_SERVICE
        val (c1, b1) = req("POST", "/api/pointer?action=tap")
        assertEquals(409, c1); assertTrue(b1.contains("no_service"))
        device.inputResult = InputResult.NO_FIELD
        val (c2, b2) = req("POST", "/api/keyboard?action=set&text=a")
        assertEquals(409, c2); assertTrue(b2.contains("no_field"))
    }

    @Test fun searchInBrave() {
        val q = URLEncoder.encode("lofi hip hop & jazz", "UTF-8")
        assertEquals(200, req("POST", "/api/search?q=$q").first)
        assertEquals(200, req("POST", "/api/search?engine=youtube&q=$q").first)
        assertEquals(400, req("POST", "/api/search?q=%20%20").first)
        assertEquals(400, req("POST", "/api/search?engine=bing&q=x").first)
        assertEquals(listOf("search:web:lofi hip hop & jazz", "search:youtube:lofi hip hop & jazz"), device.calls)
    }

    @Test fun inputLogic() {
        assertEquals(1279 to 0, InputLogic.moveCursor(1200, 10, 500, -50, 1280, 800))
        assertEquals(0 to 799, InputLogic.moveCursor(5, 790, -100, 100, 1280, 800))
        assertEquals("ab", InputLogic.edit("a", "type", "b"))
        assertEquals("b", InputLogic.edit(null, "type", "b"))          // field showing only its hint
        assertEquals("x", InputLogic.edit("hello", "set", "x"))
        assertEquals("", InputLogic.edit("hello", "clear", ""))
        assertEquals("hi ", InputLogic.edit("hi 👋", "backspace", ""))  // removes the whole emoji
        assertEquals("", InputLogic.edit(null, "backspace", ""))
        val s = InputLogic.scrollPath(640, 400, 0, -10, 1280, 800)
        assertEquals(listOf(640, 400, 640, 360), s.toList())            // short swipes are lengthened to 40 px
        val e = InputLogic.scrollPath(640, 790, 0, 500, 1280, 800)
        assertEquals(798, e[3])                                         // kept on screen
        assertEquals("https://example.com", InputLogic.linkOrNull("example.com"))
        assertEquals("https://youtu.be/x", InputLogic.linkOrNull(" https://youtu.be/x "))
        assertEquals("https://news.ycombinator.com/item?id=1", InputLogic.linkOrNull("news.ycombinator.com/item?id=1"))
        assertEquals(null, InputLogic.linkOrNull("lofi hip hop"))
        assertEquals(null, InputLogic.linkOrNull("v1.5"))
        assertEquals(null, InputLogic.linkOrNull("javascript:alert(1)"))
    }

    @Test fun playFirstVideoAndKey() {
        val q = URLEncoder.encode("blue in green miles davis", "UTF-8")
        val (c1, b1) = req("POST", "/api/youtube/first?q=$q")
        assertEquals(200, c1); assertTrue(b1.contains("\"videoId\":\"dQw4w9WgXcQ\""))
        device.first = YouTubeSearch.Result(YouTubeSearch.Outcome.NO_KEY)
        val (c2, b2) = req("POST", "/api/youtube/first?q=$q")
        assertEquals(409, c2); assertTrue(b2.contains("no_yt_key"))
        device.first = YouTubeSearch.Result(YouTubeSearch.Outcome.QUOTA)
        assertTrue(req("POST", "/api/youtube/first?q=$q").second.contains("yt_quota"))
        assertEquals(400, req("POST", "/api/youtube/first?q=").first)
        assertEquals(400, req("POST", "/api/youtube/key?key=hello").first)
        val key = "AIza" + "B".repeat(35)
        assertEquals(200, req("POST", "/api/youtube/key?key=$key").first)
        assertEquals(key, device.ytKey)
        assertEquals(200, req("POST", "/api/youtube/key?action=clear").first)
        assertEquals(null, device.ytKey)
        assertEquals(401, req("POST", "/api/youtube/key?key=$key", pin = null).first)
        assertEquals(200, req("POST", "/api/nav?action=fullscreen").first)
    }

    @Test fun youtubeLabels() {
        for (l in listOf("Full screen (f)", "Full screen", "Enter full screen", "Fullscreen", " full screen ")) assertTrue(l, InputLogic.isFullscreenLabel(l))
        for (l in listOf("Exit full screen (f)", "Fullscreenshot tips", "Theater mode (t)", "")) assertFalse(l, InputLogic.isFullscreenLabel(l))
        for (l in listOf("Play (k)", "Play", "Play video")) assertTrue(l, InputLogic.isPlayLabel(l))
        for (l in listOf("Pause (k)", "Playlist", "Play all", "Autoplay is on")) assertFalse(l, InputLogic.isPlayLabel(l))
        assertTrue(YouTubeSearch.isValidKey("AIza" + "x".repeat(35)))
        assertFalse(YouTubeSearch.isValidKey("AIza123"))
        assertTrue(YouTubeSearch.url("rock & roll", "K").contains("q=rock+%26+roll") && YouTubeSearch.url("a", "K").contains("type=video&maxResults=1"))
    }

    @Test fun searchAndPickOnThePhone() {
        val q = URLEncoder.encode("miles davis", "UTF-8")
        val (c, b) = req("POST", "/api/youtube/search?q=$q")
        assertEquals(200, c)
        assertTrue(b.contains("\"id\":\"zqNTltOGh5c\"") && b.contains("\"title\":\"So What\"") && b.contains("\"channel\":\"Miles Davis\""))
        assertEquals(200, req("POST", "/api/youtube/play?id=ylXk1LBvIqU").first)
        assertEquals(400, req("POST", "/api/youtube/play?id=../../etc").first)
        assertEquals(200, req("GET", "/api/youtube/thumb?id=zqNTltOGh5c").first)
        assertEquals(404, req("GET", "/api/youtube/thumb?id=ylXk1LBvIqU").first)
        assertEquals(400, req("GET", "/api/youtube/thumb?id=x").first)
        device.list = YouTubeSearch.ListResult(YouTubeSearch.Outcome.NO_KEY)
        assertTrue(req("POST", "/api/youtube/search?q=$q").second.contains("no_yt_key"))
        assertEquals(listOf("search-yt:miles davis", "play-yt:ylXk1LBvIqU", "search-yt:miles davis"), device.calls)
    }

    @Test fun playerPureMusicTab() {
        val (c, b) = req("GET", "/api/music/status")
        assertEquals(200, c); assertTrue(b.contains("\"bitPerfect\":true"))
        assertEquals(200, req("GET", "/api/music/albums?q=blue&offset=60&limit=500&sort=9").first)
        assertEquals(200, req("POST", "/api/music/play?key=" + URLEncoder.encode("a|/m", "UTF-8") + "&index=3").first)
        assertEquals(200, req("POST", "/api/music/play?key=a&shuffle=1").first)
        assertEquals(400, req("POST", "/api/music/play").first)
        assertEquals(400, req("POST", "/api/music/enqueue?key=missing&next=1").first)   // Player Pure said ok=false
        assertEquals(200, req("POST", "/api/music/control?action=seek&ms=61000").first)
        assertEquals(400, req("POST", "/api/music/control?action=format-disk").first)
        assertEquals(200, req("POST", "/api/music/volume?value=250").first)
        assertEquals(400, req("POST", "/api/music/queue-jump").first)
        assertEquals(200, req("GET", "/api/music/art?key=" + URLEncoder.encode("a|/m", "UTF-8")).first)
        assertEquals(404, req("GET", "/api/music/art?key=zzz").first)
        assertEquals(401, req("GET", "/api/music/queue", pin = null).first)
        assertEquals(listOf(
            "pp:status::", "pp:albums:blue:limit=200,offset=60,sort=3", "pp:play:a|/m:index=3,shuffle=false", "pp:play:a:index=0,shuffle=true",
            "pp:enqueue:missing:index=-1,next=true", "pp:control:seek:ms=61000", "pp:volume::value=100",
        ), device.calls)
        device.playerPure = false
        assertTrue(req("GET", "/api/music/status").second.contains("\"installed\":false"))
        assertEquals(404, req("POST", "/api/music/mute?on=1").first)
    }

    @Test fun playerPureDacControls() {
        val (c, b) = req("GET", "/api/music/dac")
        assertEquals(200, c); assertTrue(b.contains("\"rate\":\"96\""))
        assertEquals(200, req("POST", "/api/music/mute?on=1").first)
        assertEquals(200, req("POST", "/api/music/mute?on=0").first)
        assertEquals(200, req("POST", "/api/music/mute").first)
        assertEquals(400, req("POST", "/api/music/mute?on=loud").first)
        assertEquals(200, req("POST", "/api/music/volume-step?db=-1").first)
        assertEquals(200, req("POST", "/api/music/volume-step?db=40").first)       // clamped to +10 dB
        assertEquals(400, req("POST", "/api/music/volume-step?db=NaN").first)
        assertEquals(400, req("POST", "/api/music/volume-step").first)
        assertEquals(401, req("POST", "/api/music/mute?on=1", pin = null).first)
        assertEquals(listOf(
            "pp:dac::", "pp:mute:1:", "pp:mute:0:", "pp:mute:toggle:", "pp:volumeStep::db=-1.0", "pp:volumeStep::db=10.0",
        ), device.calls)
        device.playerPure = false
        assertEquals(404, req("GET", "/api/music/queue").first)
    }

    @Test fun onlyRequestsWithThePinCountAsAConnectedRemote() {
        assertEquals(200, req("GET", "/").first)                       // the page itself is public: not a remote yet
        assertEquals(401, req("GET", "/api/status", pin = "0000").first)
        assertEquals(401, req("GET", "/api/status", pin = null).first)
        assertTrue(clients.isEmpty())
        assertEquals(200, req("GET", "/api/status").first)
        assertEquals(200, req("GET", "/api/music/status").first)
        assertEquals(listOf("127.0.0.1", "127.0.0.1"), clients.toList())
    }
}
