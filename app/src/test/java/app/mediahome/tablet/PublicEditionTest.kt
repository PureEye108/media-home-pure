package app.mediahome.tablet

import android.provider.Settings
import app.mediahome.tablet.remote.Remote
import app.mediahome.tablet.remote.YouTubeSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the public edition does differently: its own PIN, Player Pure inside the app, no built-in YouTube key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicEditionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun pinIsMadeUpOnceAndKept() {
        Prefs.of(app).edit().remove(Prefs.REMOTE_PIN).commit()
        val first = Remote.pin(app)
        assertTrue(first, Remote.validPin(first))
        assertEquals(4, first.length)
        assertEquals(first, Remote.pin(app))
    }

    @Test fun randomPinsAreNeverTheObviousOnes() {
        val rnd = java.util.Random(7)
        val weak = setOf("0000", "1111", "1234", "4321", "9999")
        val seen = HashSet<String>()
        repeat(20_000) { val p = Remote.newPin(rnd); assertTrue(p, Remote.validPin(p) && p.length == 4 && p !in weak); seen += p }
        assertTrue("spread over many values", seen.size > 5_000)
    }

    @Test fun changingThePin() {
        assertTrue(Remote.setPin(app, " 482613 "))
        assertEquals("482613", Remote.pin(app))
        for (bad in listOf("", "123", "123456789", "12a4", "12 34")) assertFalse(bad, Remote.setPin(app, bad))
        assertEquals("482613", Remote.pin(app))
        assertTrue(Remote.link(app) == null || Remote.link(app)!!.endsWith("#pin=482613"))
    }

    @Test fun playerPureIsInsideThisApp() {
        assertTrue(Apps.isLaunchable(app, Apps.PLAYER_PURE))
        assertEquals("Player Pure", Apps.label(app, Apps.PLAYER_PURE))
        assertEquals("Player Pure", Apps.label(app, app.packageName))   // music playing in this app is Player Pure
        val i = Apps.playerPureIntent(app)
        assertEquals("app.playerpure.ui.MainActivity", i.component?.className)
        assertEquals(app.packageName, i.component?.packageName)
        assertTrue(Apps.launchableApps(app).any { it.pkg == Apps.PLAYER_PURE })
        assertEquals(Apps.PLAYER_PURE, Apps.chosenPackage(app, Tiles.music))
    }

    @Test fun screenSaverIsRecognisedUnderThisAppsId() {
        val cr = app.contentResolver
        Settings.Secure.putInt(cr, "screensaver_enabled", 1)
        Settings.Secure.putInt(cr, "screensaver_activate_on_sleep", 1)
        Settings.Secure.putString(cr, "screensaver_components", "com.other/.Clock")
        assertEquals(false, Saver.selected(app))
        Settings.Secure.putString(cr, "screensaver_components", "${app.packageName}/app.mediahome.tablet.ScreenSaverDream")
        assertEquals(true, Saver.selected(app))
        assertTrue(Saver.components(app).all { it.startsWith(app.packageName + "/") })
    }

    @Test fun noYouTubeKeyIsBuiltIn() {
        // Public builds have no signing/youtube.properties: every user adds their own key.
        assertEquals(null, YouTubeSearch.builtInKey())
        assertEquals(null, YouTubeSearch.effectiveKey(null))
    }

    @Test fun keyRestrictionValues() {
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", YouTubeSearch.sha1Hex("abc".toByteArray()))
        assertEquals("A9:99:3E", YouTubeSearch.colonHex("A9993E"))
        YouTubeSearch.identify(app)
        assertEquals(app.packageName, YouTubeSearch.androidPackage)
    }
}
