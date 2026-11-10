package app.playerpure

import app.playerpure.library.Cue
import app.playerpure.library.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TagsTest {
    private fun media(name: String) = File(javaClass.classLoader!!.getResource("media/$name")!!.toURI())

    private fun checkTags(name: String, codec: String, rate: Int, bits: Int, durMs: Long, art: Boolean, full: Boolean = true) {
        val t = Tags.read(media(name))
        assertEquals("$name codec", codec, t.codec)
        assertEquals("$name rate", rate, t.rate)
        assertEquals("$name bits", bits, t.bits)
        assertEquals("$name channels", 2, t.channels)
        assertTrue("$name duration ${t.durationMs}", kotlin.math.abs(t.durationMs - durMs) <= 60)
        assertEquals("$name title", "Blue_Train", t.title)
        assertEquals("$name artist", "John_Coltrane", t.artist)
        assertEquals("$name album", "Blue_Train", t.album)
        if (full) {
            assertEquals("$name album artist", "John_Coltrane", t.albumArtist)
            assertEquals("$name year", 1957, t.year)
            assertEquals("$name track", 1, t.track)
            assertEquals("$name genre", "Jazz", t.genre)
        }
        assertEquals("$name art", art, t.hasArt)
        if (art) {
            val b = Tags.readArt(media(name))
            assertNotNull("$name art bytes", b)
            assertTrue("$name art is a JPEG", b!!.size > 100 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8)
        }
    }

    @Test fun flac() = checkTags("t1.flac", "FLAC", 96000, 24, 2000, true)
    @Test fun mp3Id3v23WithPicture() = checkTags("t2.mp3", "MP3", 44100, 16, 2037, true)
    @Test fun mp3Id3v24() = checkTags("t3.mp3", "MP3", 44100, 16, 2037, false)
    @Test fun alacM4a() = checkTags("t4.m4a", "ALAC", 96000, 24, 2000, true)
    @Test fun opus() = checkTags("t5.opus", "Opus", 48000, 16, 2000, false)
    @Test fun vorbis() = checkTags("t6.ogg", "Vorbis", 44100, 16, 2000, false)
    @Test fun wavInfo() = checkTags("t7.wav", "WAV", 44100, 16, 2000, false, full = false)
    @Test fun aiffId3() = checkTags("t8.aiff", "AIFF", 44100, 24, 2000, false)
    @Test fun aacM4a() = checkTags("t9.m4a", "AAC", 44100, 16, 2000, false)

    @Test fun dsdFormats() {
        for (n in listOf("t10.dsf", "t11.dff")) {
            val t = Tags.read(media(n))
            assertEquals(n, 2822400, t.rate); assertEquals(n, 2, t.channels); assertEquals(n, 1000L, t.durationMs)
            assertEquals(n, if (n.endsWith("dsf")) "DSF" else "DFF", t.codec)
        }
    }

    @Test fun namesFromFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pp-names/Miles Davis/Kind of Blue (1959)/CD1").apply { mkdirs() }
        val f = File(dir, "03 - Blue in Green.flac").apply { writeBytes(ByteArray(10)) }
        val t = Tags.read(f)
        assertEquals("Blue in Green", t.title); assertEquals(3, t.track)
        assertEquals("Kind of Blue", t.album); assertEquals("Miles Davis", t.artist); assertEquals(1959, t.year)
    }

    @Test fun cueSheet() {
        val sheet = Cue.parse(Cue.decode(media("image.cue").readBytes()))!!
        assertEquals("Suites", sheet.title)
        assertEquals("Orchestre de Café", sheet.performer)   // Windows-1252 text
        assertEquals("Classical", sheet.genre); assertEquals(1985, sheet.year)
        val f = sheet.files.single()
        assertEquals("image.wav", f.name)
        assertEquals(listOf("Prélude", "Allemande", "Courante"), f.tracks.map { it.title })
        assertEquals(listOf(0L, 3 * 75L, 6 * 75 + 37L), f.tracks.map { it.start })   // INDEX 01, not the INDEX 00 pre-gap
        assertEquals(listOf(225L, 487L, -1L), f.tracks.map { it.end })
        assertEquals("Guest", f.tracks[2].performer)
        assertEquals(4500L + 75 + 2, Cue.time("01:01:02"))
    }
}
