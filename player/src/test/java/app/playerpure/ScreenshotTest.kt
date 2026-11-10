package app.playerpure

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Looper
import app.playerpure.library.Covers
import app.playerpure.ui.AlbumPage
import app.playerpure.ui.MainActivity
import app.playerpure.ui.QueuePage
import app.playerpure.ui.SettingsPage
import app.playerpure.ui.DacPage
import app.playerpure.audio.DsdOut
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Player Pure's screens at the tablet's size (2560x1600 landscape, 340 dpi) into build/screens.
 * Albums and covers are invented samples (abstract pictures), not real records.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1205dp-h753dp-land-340dpi")
class ScreenshotTest {

    private val albums = listOf(
        Triple("Aria Quartet", "Northern Lights", "FLAC/24/96000"), Triple("Mara Ellison", "Salt & Cedar", "FLAC/16/44100"),
        Triple("The Low Tides", "Harbour Songs", "ALAC/24/192000"), Triple("Kenji Arai Trio", "Blue Hours", "DSF/1/2822400"),
        Triple("Orchestre de Lumière", "Suites for Strings", "FLAC/24/88200"), Triple("Noor & the Lanterns", "Midnight Market", "FLAC/16/44100"),
        Triple("Elm Street Brass", "Copper Sky", "WAV/24/192000"), Triple("Sofia Varga", "Piano Diaries", "FLAC/24/96000"),
        Triple("Desert Radio", "Mirage FM", "MP3/16/44100"), Triple("Ivo Petrov", "Cello Landscapes", "DFF/1/5644800"),
        Triple("Paper Moons", "Small Hours", "FLAC/16/44100"), Triple("Lumen Choir", "Evensong", "FLAC/24/352800"),
        Triple("Rafael Ortiz", "Guitarra Nocturna", "ALAC/16/44100"), Triple("Velvet Static", "Tape Echoes", "FLAC/24/48000"),
    )
    private val songs = listOf("Opening", "First Light", "Long Road Home", "Glass River", "Paper Boats", "The Quiet Hour", "Lanterns", "Undertow", "Coda")

    private fun cover(seed: Int, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val h = (seed * 47) % 360f
        val c1 = Color.HSVToColor(floatArrayOf(h, 0.55f, 0.75f)); val c2 = Color.HSVToColor(floatArrayOf((h + 60) % 360, 0.7f, 0.25f))
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (seed % 3) {
            0 -> { p.shader = RadialGradient(size * 0.6f, size * 0.4f, size * 0.35f, 0xCCFFF3D6.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawCircle(size * 0.6f, size * 0.4f, size * 0.35f, p) }
            1 -> { p.color = 0x55FFFFFF; for (i in 0 until 6) c.drawRect(0f, size * (0.15f + i * 0.12f), size.toFloat(), size * (0.15f + i * 0.12f) + size * 0.04f, p) }
            else -> { p.color = 0x66000000; c.drawCircle(size * 0.5f, size * 0.55f, size * 0.3f, p); p.color = 0x99FFFFFF.toInt(); c.drawCircle(size * 0.5f, size * 0.55f, size * 0.06f, p) }
        }
        return b
    }

    private fun idle(ms: Long = 400) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25) }
    }

    private fun shot(act: MainActivity, name: String) {
        idle()
        val root = act.window.decorView
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(root.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(root.height, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/screens/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN ${out.absolutePath} ${root.width}x${root.height}")
    }

    /** Media Home Pure on a phone held sideways (2340x1080 @ 420 dpi): albums and Now Playing. */
    @Test @Config(qualifiers = "w891dp-h411dp-land-420dpi")
    fun phoneScreens() {
        seed()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(act, "phone-01-albums")
        val northern = Hub.library.albums().first { it.title == "Northern Lights" }
        Hub.player.restore(Hub.library.albumTracks(northern.key), 2, 95_000)
        idle(600)
        Hub.player.demo(true, "USB DAC (example)", "FLAC 24/96  \u2192  24-bit / 96 kHz", true, true)
        act.onPlayerChanged()
        act.showNowPlaying()
        shot(act, "phone-03-now-playing")
    }

    private fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Hub.init(app)
        val db = Hub.library.writableDatabase
        val base = "/storage/emulated/0"
        albums.forEachIndexed { ai, (artist, album, fmt) ->
            val (codec, bits, rate) = fmt.split('/')
            val folder = "$base/Music/$artist/$album"
            val n = 6 + ai % 4
            for (k in 0 until n) db.insert("tracks", null, ContentValues().apply {
                put("path", "$folder/%02d.%s".format(k + 1, codec.lowercase())); put("cue_index", 0); put("root", base); put("folder", folder)
                put("title", songs[(k + ai) % songs.size]); put("artist", artist); put("album", album); put("album_artist", artist)
                put("genre", ""); put("year", 1998 + ai); put("track_no", k + 1); put("disc_no", 1)
                put("duration_ms", 180_000L + k * 23_000L + ai * 7_000L); put("rate", rate.toInt()); put("bits", bits.toInt()); put("channels", 2)
                put("codec", codec); put("cue_start", -1); put("cue_end", -1); put("album_key", album.lowercase() + "|" + folder)
                put("art", "sample"); put("added", 1_000_000L + ai)
            })
        }
        Hub.library.mountedRoots = setOf(base)
        Covers.override = { _, path -> cover(albums.indexOfFirst { path.contains("/" + it.second + "/") }.coerceAtLeast(0) + 1, 480) }
    }

    @Test fun screens() {
        seed()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(act, "01-albums")

        val all = Hub.library.albums()
        val northern = all.first { it.title == "Northern Lights" }
        act.show(AlbumPage(act, northern))
        val tracks = Hub.library.albumTracks(northern.key)
        Hub.player.restore(tracks, 2, 95_000)
        idle(600)
        Hub.player.demo(true, "USB DAC (example)", "FLAC 24/96  →  24-bit / 96 kHz", true, true)
        act.onPlayerChanged()
        shot(act, "02-album")

        act.showNowPlaying()
        shot(act, "03-now-playing")
        act.hideNowPlaying()

        Hub.player.enqueue(Hub.library.albumTracks(all.first { it.title == "Blue Hours" }.key), false)
        idle(400)
        act.show(QueuePage(act))
        shot(act, "04-queue")

        Hub.player.demo(false, "Tablet audio (speakers, HDMI, Bluetooth)", "", false, false)
        act.onPlayerChanged()
        act.show(SettingsPage(act))
        shot(act, "05-settings")

        // DAC screen with an example of what a connected DAC could show (no DAC in this test)
        Hub.player.demo(true, "USB DAC (example)", "FLAC 24/96  →  24-bit / 96 kHz", true, true)
        Hub.demoDac = Hub.DacDisplay(connected = true, name = "USB DAC (example)", input = "USB", rate = "96", unit = "kHz",
            format = "24-bit PCM", volume = "-32.0 dB", volumeDb = -32.0, muted = false, hasVolume = true, hasMute = true,
            badge = "BIT-PERFECT", bitPerfect = true, state = "Playing: " + (Hub.player.current()?.title ?: ""), nativeDsd = true)
        Hub.volume = Hub.dbToLevel(-32.0, -99.5, 0.0)
        act.onPlayerChanged()
        act.show(DacPage(act))
        shot(act, "06-dac")
        // live updates must not rebuild or crash the page: in place (same layout) and rebuilt (DSD setting changed)
        Hub.changed(); idle(300)
        Hub.dsdOut = DsdOut.NATIVE; idle(300)
        Hub.dsdOut = DsdOut.PCM; idle(300)
        Hub.demoDac = null
        Hub.changed(); idle(300)
        Covers.override = null
    }
}
