package app.mediahome.tablet.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parsing of YouTube Data API v3 search.list answers (the formats documented by Google). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeSearchTest {
    @Test fun firstVideoAndTitle() {
        val body = """{"kind":"youtube#searchListResponse","items":[
            {"kind":"youtube#searchResult","id":{"kind":"youtube#video","videoId":"zqNTltOGh5c"},
             "snippet":{"title":"Miles Davis - Blue in Green (Official Audio) &amp; more &#39;live&#39;"}}]}"""
        val r = YouTubeSearch.parse(200, body)
        assertEquals(YouTubeSearch.Outcome.OK, r.outcome)
        assertEquals("zqNTltOGh5c", r.videoId)
        assertEquals("Miles Davis - Blue in Green (Official Audio) & more 'live'", r.title)
    }

    @Test fun noResultsAndOddItems() {
        assertEquals(YouTubeSearch.Outcome.NONE, YouTubeSearch.parse(200, """{"items":[]}""").outcome)
        assertEquals(YouTubeSearch.Outcome.NONE, YouTubeSearch.parse(200, """{"items":[{"id":{"kind":"youtube#channel","channelId":"UC1"}}]}""").outcome)
        assertEquals(YouTubeSearch.Outcome.NETWORK, YouTubeSearch.parse(200, "<html>proxy error</html>").outcome)
    }

    @Test fun errors() {
        val quota = """{"error":{"code":403,"message":"quota","errors":[{"domain":"youtube.quota","reason":"quotaExceeded"}]}}"""
        assertEquals(YouTubeSearch.Outcome.QUOTA, YouTubeSearch.parse(403, quota).outcome)
        val badKey = """{"error":{"code":400,"message":"API key not valid.","errors":[{"reason":"badRequest"}],"status":"INVALID_ARGUMENT",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}"""
        assertEquals(YouTubeSearch.Outcome.KEY_REJECTED, YouTubeSearch.parse(400, badKey).outcome)
        val blocked = """{"error":{"code":403,"details":[{"reason":"API_KEY_ANDROID_APP_BLOCKED"}]}}"""
        assertEquals(YouTubeSearch.Outcome.KEY_REJECTED, YouTubeSearch.parse(403, blocked).outcome)
        assertEquals(YouTubeSearch.Outcome.NETWORK, YouTubeSearch.parse(503, """{"error":{"code":503}}""").outcome)
        assertEquals(YouTubeSearch.Outcome.NO_KEY, YouTubeSearch.search("x", null).outcome)
    }

    @Test fun listForPicking() {
        val body = """{"items":[
            {"id":{"kind":"youtube#video","videoId":"zqNTltOGh5c"},"snippet":{"title":"Blue in Green","channelTitle":"Miles Davis"}},
            {"id":{"kind":"youtube#channel","channelId":"UC1"},"snippet":{"title":"A channel"}},
            {"id":{"kind":"youtube#video","videoId":"ylXk1LBvIqU"},"snippet":{"title":"So What &amp; More","channelTitle":"Columbia"}}]}"""
        val l = YouTubeSearch.parseList(200, body)
        assertEquals(YouTubeSearch.Outcome.OK, l.outcome)
        assertEquals(listOf("zqNTltOGh5c", "ylXk1LBvIqU"), l.videos.map { it.id })
        assertEquals("So What & More", l.videos[1].title)
        assertEquals("Columbia", l.videos[1].channel)
        assertEquals(YouTubeSearch.Outcome.NONE, YouTubeSearch.parseList(200, """{"items":[]}""").outcome)
        assertEquals(YouTubeSearch.Outcome.NO_KEY, YouTubeSearch.searchList("x", "").outcome)
    }

    /** Public builds have no built-in key (a private build may have one); a key set on the device always wins. */
    @Test fun builtInKeyAndDeviceKey() {
        val built = YouTubeSearch.builtInKey()
        if (built != null) assertTrue(YouTubeSearch.isValidKey(built))
        assertEquals(built, YouTubeSearch.effectiveKey(null))
        assertEquals(built, YouTubeSearch.effectiveKey("  "))
        val mine = "AIza" + "B".repeat(35)
        assertEquals(mine, YouTubeSearch.effectiveKey(" $mine "))
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val bridge = MediaBridge(ctx)
        app.mediahome.tablet.Prefs.of(ctx).edit().remove(app.mediahome.tablet.Prefs.YT_KEY).commit()
        assertEquals(built != null, bridge.hasYouTubeKey()); assertEquals(built, bridge.youTubeKey())
        bridge.setYouTubeKey(mine); assertEquals(mine, bridge.youTubeKey()); assertTrue(bridge.hasYouTubeKey())
        bridge.setYouTubeKey(null); assertEquals(built, bridge.youTubeKey())   // "Remove": back to no key (or the private build's)
    }
}
