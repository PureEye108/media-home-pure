package app.mediahome.tablet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** 1.10: Media Home in 11 languages; the tablet's language by default; the phone remote follows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LangTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun closestSupportedLanguage() {
        assertEquals("vi", Lang.supported(Locale.forLanguageTag("vi-VN")))
        assertEquals("zh-CN", Lang.supported(Locale.forLanguageTag("zh-Hans-SG")))
        assertEquals("zh-CN", Lang.supported(Locale.forLanguageTag("zh-CN")))
        assertEquals("en", Lang.supported(Locale.forLanguageTag("zh-TW")))      // Traditional Chinese isn't included: English
        assertEquals("pt-BR", Lang.supported(Locale.forLanguageTag("pt-PT")))
        assertEquals("de", Lang.supported(Locale.forLanguageTag("de-AT")))
        assertEquals("en", Lang.supported(Locale.forLanguageTag("th-TH")))
        assertEquals(11, Lang.OPTIONS.size)
    }

    @Test fun remotePageGetsTheLanguageSafely() {
        val page = "<!doctype html>\n<html lang=\"en\">\n<head><script type=\"application/json\" id=\"i18n\">{}</script></head>"
        val out = Lang.injectRemote(page, "vi", """{"t":{"x":"</script><b>"}}""")
        assertTrue(out.contains("<html lang=\"vi\">"))
        assertTrue(out.contains("<\\/script><b>"))                              // can't close the script element early
        assertTrue(!out.contains("\"x\":\"</script>"))
    }

    /** Every language's phone-remote words: present, valid, the same keys as each other, placeholders kept. */
    @Test fun remoteWordFiles() {
        val files = Lang.OPTIONS.map { it.first }.mapNotNull { Lang.remoteAsset(it) }
        assertEquals(10, files.size)
        var keys: Set<String>? = null
        for (f in files) {
            val o = JSONObject(ctx.assets.open(f).use { it.readBytes().toString(Charsets.UTF_8) })
            val t = o.getJSONObject("t"); val e = o.getJSONObject("e")
            val ks = (t.keys().asSequence().map { "t:$it" } + e.keys().asSequence().map { "e:$it" }).toSet()
            if (keys == null) keys = ks else assertEquals("same keys as the other languages: $f", keys, ks)
            for (k in t.keys()) {
                val v = t.getString(k)
                assertTrue("$f: empty $k", v.isNotBlank())
                if (!k.contains('.')) assertEquals("$f placeholders in $k", Regex("\\{\\d\\}").findAll(k).count(), Regex("\\{\\d\\}").findAll(v).count())
            }
        }
        assertTrue(keys!!.size > 230)
    }

    @Test @Config(qualifiers = "vi")
    fun vietnameseResources() {
        assertEquals("Media Home Pure", ctx.getString(R.string.app_name))
        assertNotNull(ctx.getString(R.string.saver_on, 10))
        assertTrue(ctx.getString(R.string.section_language).isNotBlank())
        assertTrue(ctx.getString(R.string.section_weather) != "Weather")
    }
}
