package app.mediahome.tablet

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Before
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
 * Renders each screen to a PNG in build/screens: a 10-inch tablet (1600x2560 @ 340 dpi) and a phone (1080x2340 @ 420 dpi),
 * with common media apps "installed" so tiles show real names. Used for the README and manual pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {

    @Before
    fun installApps() {
        fakeApp(Apps.HIBY, "HiBy Music")
        fakeApp(Apps.BRAVE, "Brave")
        fakeApp(Apps.VLC, "VLC")
        fakeApp(Apps.JELLYFIN, "Jellyfin")
        fakeApp(Apps.EMBY, "Emby")
        fakeApp(Apps.YOUTUBE, "YouTube")
        fakeApp(Apps.AIRPLAY, "AirPlay Receiver")
        fakeApp(Apps.KODI, "Kodi")
        // Robolectric has no Wi-Fi: use an example address so the QR code and address are shown.
        app.mediahome.tablet.remote.Remote.ipOverride = "192.168.1.50"
        // Sample weather (illustrative values) and the web remote switched on, so previews show both.
        val prefs = Prefs.of(RuntimeEnvironment.getApplication())
        prefs.edit()
            .putString(Prefs.WEATHER_CACHE, org.json.JSONObject()
                .put("place", "Ho Chi Minh City").put("at", System.currentTimeMillis()).put("json", SampleWeather.FORECAST).toString())
            .putBoolean(Prefs.REMOTE, true)
            .putString(Prefs.REMOTE_PIN, "4826")          // an example PIN (each device makes up its own)
            .putBoolean(Prefs.SETUP_SHOWN, true)          // draw the home screen, not the first-run checklist
            .commit()
    }

    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val ai = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label; name = label }
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val component = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(component)
        spm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun <T : Activity> shot(cls: Class<T>, name: String) {
        val activity = Robolectric.buildActivity(cls).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()     // the QR tile draws its code after the first layout
        val root = activity.window.decorView
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

    /** Web remote on and no phone connected: the Browser tile shows the remote's QR code. */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun homeLandscape() {
        app.mediahome.tablet.remote.Remote.clients.reset()
        shot(HomeActivity::class.java, "home-landscape")
    }

    @Test @Config(qualifiers = "w753dp-h1205dp-port-340dpi")
    fun homePortrait() {
        app.mediahome.tablet.remote.Remote.clients.reset()
        shot(HomeActivity::class.java, "home-portrait")
    }

    /** A phone has the remote open: the Browser tile is back. */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun homeLandscapeRemoteConnected() {
        app.mediahome.tablet.remote.Remote.clients.seen("192.168.1.20", android.os.SystemClock.elapsedRealtime())
        try { shot(HomeActivity::class.java, "home-landscape-remote-connected") } finally { app.mediahome.tablet.remote.Remote.clients.reset() }
    }

    /** The home screen switches between the two by itself: QR code, then a phone connects, then it goes away again. */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun browserSlotFollowsTheRemote() {
        val remote = app.mediahome.tablet.remote.Remote
        remote.clients.reset()
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        fun qrVisible(): Boolean {
            fun find(v: android.view.View): QrTileView? = when (v) {
                is QrTileView -> v
                is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            return find(activity.window.decorView)!!.visibility == android.view.View.VISIBLE
        }
        org.junit.Assert.assertTrue("QR code while nobody is connected", qrVisible())
        remote.clients.seen("192.168.1.20", android.os.SystemClock.elapsedRealtime())
        looper.idleFor(java.time.Duration.ofMillis(1100))
        org.junit.Assert.assertFalse("Browser tile once a phone connects", qrVisible())
        looper.idleFor(java.time.Duration.ofMillis(16_000))
        org.junit.Assert.assertTrue("QR code again after the phone stops asking", qrVisible())
        Prefs.of(activity).edit().putBoolean(Prefs.REMOTE_QR_HOME, false).commit()
        looper.idleFor(java.time.Duration.ofMillis(1100))
        org.junit.Assert.assertFalse("switched off in Settings: always the Browser tile", qrVisible())
        Prefs.of(activity).edit().remove(Prefs.REMOTE_QR_HOME).putBoolean(Prefs.REMOTE, false).commit()
        looper.idleFor(java.time.Duration.ofMillis(1100))
        org.junit.Assert.assertFalse("web remote off: always the Browser tile", qrVisible())
    }

    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun settings() = shot(SettingsActivity::class.java, "settings")

    /** The whole Settings page as one tall image (to check the web remote QR card below the fold). */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun settingsFull() = fullShot(SettingsActivity::class.java, "settings-full")

    // ---- Public edition: the set-up checklist, and phones ----
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi") fun setup() = shot(SetupActivity::class.java, "setup")
    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi") fun setupPhoneFull() = fullShot(SetupActivity::class.java, "phone-setup-full")
    @Test @Config(qualifiers = "vi-w411dp-h891dp-port-420dpi") fun setupPhoneVi() = shot(SetupActivity::class.java, "phone-setup-vi")
    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun homePhone() { app.mediahome.tablet.remote.Remote.clients.reset(); shot(HomeActivity::class.java, "phone-home") }
    @Test @Config(qualifiers = "vi-w411dp-h891dp-port-420dpi")
    fun homePhoneVi() { app.mediahome.tablet.remote.Remote.clients.reset(); shot(HomeActivity::class.java, "phone-home-vi") }
    @Test @Config(qualifiers = "w891dp-h411dp-land-420dpi")
    fun homePhoneLandscape() { app.mediahome.tablet.remote.Remote.clients.reset(); shot(HomeActivity::class.java, "phone-home-landscape") }
    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi") fun settingsPhone() = shot(SettingsActivity::class.java, "phone-settings")
    @Test @Config(qualifiers = "w891dp-h411dp-land-420dpi") fun saverPhone() = saverShot("phone-screen-saver", playing = true)

    /** The first time the home screen opens it starts the set-up checklist; afterwards it doesn't. */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun firstRunOpensSetup() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.of(app).edit().putBoolean(Prefs.SETUP_SHOWN, false).commit()
        val first = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        org.junit.Assert.assertEquals(SetupActivity::class.java.name, shadowOf(first).nextStartedActivity?.component?.className)
        val again = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        org.junit.Assert.assertNull(shadowOf(again).nextStartedActivity)
    }

    private fun <T : Activity> fullShot(cls: Class<T>, name: String) {
        val activity = Robolectric.buildActivity(cls).setup().get()
        fun find(v: android.view.View): android.widget.ScrollView? = when (v) {
            is android.widget.ScrollView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val list = find(activity.window.decorView)!!.getChildAt(0)
        val bmp = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0E1015.toInt())
        list.draw(canvas)
        val out = File("build/screens/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN ${out.absolutePath} ${list.width}x${list.height}")
    }

    // ---- Other languages (1.10): check that longer words still fit ----
    @Test @Config(qualifiers = "vi-w1205dp-h753dp-land-340dpi") fun homeVi() = shot(HomeActivity::class.java, "lang-vi-home")
    @Test @Config(qualifiers = "zh-rCN-w1205dp-h753dp-land-340dpi") fun homeZh() = shot(HomeActivity::class.java, "lang-zh-home")
    @Test @Config(qualifiers = "hi-w1205dp-h753dp-land-340dpi") fun homeHi() = shot(HomeActivity::class.java, "lang-hi-home")
    @Test @Config(qualifiers = "de-w1205dp-h753dp-land-340dpi") fun homeDe() = shot(HomeActivity::class.java, "lang-de-home")
    @Test @Config(qualifiers = "ru-w753dp-h1205dp-port-340dpi") fun homeRuPortrait() = shot(HomeActivity::class.java, "lang-ru-home-portrait")
    @Test @Config(qualifiers = "ja-w1205dp-h753dp-land-340dpi") fun homeJa() = shot(HomeActivity::class.java, "lang-ja-home")
    @Test @Config(qualifiers = "vi-w1205dp-h753dp-land-340dpi") fun settingsVi() = shot(SettingsActivity::class.java, "lang-vi-settings")
    @Test @Config(qualifiers = "fr-w1205dp-h753dp-land-340dpi") fun settingsFr() = shot(SettingsActivity::class.java, "lang-fr-settings")
    @Test @Config(qualifiers = "ko-w1205dp-h753dp-land-340dpi") fun saverKo() = saverShot("lang-ko-saver", playing = true)

    // ---- Screen saver (example music and weather; covers are abstract pictures, names invented) ----

    private fun cover(): Bitmap {
        val n = 600
        val px = IntArray(n * n) { i ->
            val x = i % n; val y = i / n
            val r = 60 + x * 150 / n; val g = 40 + y * 80 / n; val b = 140 + (x + y) * 90 / (2 * n)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun saverSource(playing: Boolean) = object : SaverSource {
        override fun nowPlaying() = if (!playing) null else NowPlaying(
            title = "Glass River", artist = "Aria Quartet", album = "Northern Lights", app = "Player Pure",
            playing = true, positionMs = 101_000, durationMs = 249_000, art = cover(),
            signal = "FLAC 24/96  \u2192  24-bit / 96 kHz  \u00b7  USB DAC", bitPerfect = true,
        )
        override fun weather() = Weather.parseForecast("Da Nang", SampleWeather.FORECAST, System.currentTimeMillis())
    }

    private fun saverShot(name: String, playing: Boolean) {
        ScreenSaverActivity.sourceOverride = saverSource(playing)
        try { shot(ScreenSaverActivity::class.java, name) } finally { ScreenSaverActivity.sourceOverride = null }
    }

    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun screenSaverMusic() = saverShot("screen-saver-music", playing = true)

    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun screenSaverClock() = saverShot("screen-saver-clock", playing = false)

    @Test @Config(qualifiers = "w753dp-h1205dp-port-340dpi")
    fun screenSaverMusicPortrait() = saverShot("screen-saver-music-portrait", playing = true)

    /** The home screen with the "Scan to open the remote" dialog drawn on top (dimmed background). */
    @Test @Config(qualifiers = "w1205dp-h753dp-land-340dpi")
    fun remoteQrDialog() {
        val intent = Intent(RuntimeEnvironment.getApplication(), HomeActivity::class.java).putExtra(HomeActivity.EXTRA_SHOW_REMOTE_QR, true)
        val activity = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        root.draw(canvas)
        canvas.drawColor(0x99000000.toInt())
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val v = dialog.window!!.decorView
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(root.width, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(root.height, android.view.View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        canvas.save()
        canvas.translate(((root.width - v.measuredWidth) / 2).toFloat(), ((root.height - v.measuredHeight) / 2).toFloat())
        v.draw(canvas)
        canvas.restore()
        val out = File("build/screens/remote-qr-dialog.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN ${out.absolutePath} ${root.width}x${root.height}")
    }
}
