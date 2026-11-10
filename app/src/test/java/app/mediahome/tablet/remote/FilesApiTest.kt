package app.mediahome.tablet.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The Files tab API over a real socket: streamed uploads, download links, play, permissions. */
class FilesApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val calls = mutableListOf<String>()
    private var access = true
    private var now = 1_000_000L
    private lateinit var server: MiniHttpServer
    private lateinit var tablet: File
    private val base get() = "http://127.0.0.1:${server.localPort}"

    private inner class Device : RemoteDevice {
        override fun statusJson() = "{}"
        override fun art(): ByteArray? = null
        override fun control(action: String, ms: Long?) = true
        override fun setVolume(level: Int?, delta: Int?) = true
        override fun launch(target: String) = true
        override fun openUrl(url: String, app: String) = true
        override fun wake() = true
        override fun connectLink(): String? = null
        override fun pointer(action: String, dx: Int, dy: Int) = InputResult.OK
        override fun nav(action: String) = InputResult.OK
        override fun keyboard(action: String, text: String) = InputResult.OK
        override fun search(query: String, engine: String) = true
        override fun hasFileAccess() = access
        override fun playFile(file: File, kind: String) = true.also { calls += "play:$kind:${file.name}" }
        override fun mediaScan(file: File) { calls += "scan:${file.name}" }
        override fun playFirstVideo(query: String) = YouTubeSearch.Result(YouTubeSearch.Outcome.NO_KEY)
        override fun setYouTubeKey(key: String?) {}
        override fun searchVideos(query: String) = YouTubeSearch.ListResult(YouTubeSearch.Outcome.NO_KEY)
        override fun playVideo(videoId: String) = true
        override fun thumbnail(videoId: String): ByteArray? = null
        override fun music(method: String, arg: String?, extras: Map<String, Any>): String? = null
        override fun musicArt(key: String): ByteArray? = null
    }

    @Before fun up() {
        tablet = tmp.newFolder("tablet")
        val fs = FileStore { listOf(FileStore.Root("internal", "Tablet", tablet, false)) }
        val api = RemoteApi(Device(), { "1008" }, { ByteArray(0) }, { now }, fs)
        server = MiniHttpServer(0, RemoteApi.STREAMING_PATHS) { api.handle(it) }
        server.start()
    }

    @After fun down() = server.stop()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, path: String, pin: String? = "1008", body: ByteArray? = null): Pair<Int, ByteArray> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (pin != null) c.setRequestProperty("X-Pin", pin)
        if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size.toLong()); c.outputStream.use { it.write(body) } }
        else if (method == "POST") { c.doOutput = true; c.outputStream.use { } }
        val code = c.responseCode
        val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    @Test fun uploadListDownload() {
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it * 7 % 256).toByte() }   // bigger than the normal 64 KB limit
        val (code, body) = request("POST", "/api/files/upload?root=internal&path=&name=" + enc("Tiếng Việt – song.flac"), body = data)
        assertEquals(String(body), 200, code)
        val saved = File(tablet, "Tiếng Việt – song.flac")
        assertTrue(saved.readBytes().contentEquals(data))
        assertEquals(listOf("scan:Tiếng Việt – song.flac"), calls)

        val list = String(request("GET", "/api/files/list?root=internal&path=").second)
        assertTrue(list, list.contains("Tiếng Việt – song.flac") && list.contains("\"kind\":\"audio\""))

        val tok = String(request("GET", "/api/files/token?root=internal&path=" + enc(saved.name)).second)
        val url = Regex("\"url\":\"([^\"]+)\"").find(tok)!!.groupValues[1]
        val c = URL(base + url).openConnection() as HttpURLConnection   // no PIN needed for the link
        assertEquals(200, c.responseCode)
        assertTrue(c.getHeaderField("Content-Disposition").contains("filename*=UTF-8''"))
        assertTrue(c.inputStream.use { it.readBytes() }.contentEquals(data))

        now += RemoteApi.DOWNLOAD_MS + 1
        assertEquals(404, (URL(base + url).openConnection() as HttpURLConnection).responseCode)   // link expired
    }

    @Test fun uploadNeedsThePin() {
        val (code, _) = request("POST", "/api/files/upload?root=internal&name=x.mp3", pin = null, body = ByteArray(10))
        assertEquals(401, code)
        assertTrue(tablet.listFiles()!!.isEmpty())
    }

    @Test fun manageAndPlay() {
        assertEquals(200, request("POST", "/api/files/mkdir?root=internal&path=&name=Music").first)
        File(tablet, "Music/a.mp3").writeText("x"); File(tablet, "notes.txt").writeText("x")
        assertEquals(200, request("POST", "/api/files/play?root=internal&path=Music/a.mp3").first)
        assertEquals(400, request("POST", "/api/files/play?root=internal&path=notes.txt").first)
        assertEquals(200, request("POST", "/api/files/rename?root=internal&path=Music/a.mp3&name=b.mp3").first)
        assertEquals(200, request("POST", "/api/files/delete?root=internal&path=Music/b.mp3").first)
        assertTrue(String(request("POST", "/api/files/empty-trash?root=internal").second).contains("\"removed\":1"))
        assertEquals(403, request("GET", "/api/files/list?root=internal&path=" + enc("../")).first)
        assertEquals(400, request("POST", "/api/files/rename?root=internal&path=Music&name=").first)
        assertEquals(listOf("play:audio:a.mp3"), calls)
    }

    @Test fun withoutAllFilesAccessTheTabExplainsWhatToDo() {
        access = false
        val (code, body) = request("GET", "/api/files/roots")
        assertEquals(409, code)
        assertTrue(String(body).contains("no_files_access"))
    }
}
