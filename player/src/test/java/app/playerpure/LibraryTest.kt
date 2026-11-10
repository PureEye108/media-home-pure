package app.playerpure

import app.playerpure.library.Library
import app.playerpure.library.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTest {
    private fun media(name: String) = File(javaClass.classLoader!!.getResource("media/$name")!!.toURI())

    @Test fun scansAlbumsCueSheetsAndChanges() {
        val root = File(System.getProperty("java.io.tmpdir"), "pp-lib-" + System.nanoTime())
        val album = File(root, "Music/John Coltrane/Blue Train (1957)").apply { mkdirs() }
        for (n in listOf("t1.flac", "t2.mp3", "t4.m4a", "t7.wav")) media(n).copyTo(File(album, n))
        val suites = File(root, "Music/Suites").apply { mkdirs() }
        media("image.flac").copyTo(File(suites, "image.flac")); media("image.cue").copyTo(File(suites, "image.cue"))
        media("cover.jpg").copyTo(File(suites, "folder.jpg"))
        File(root, "Music/.hidden").mkdirs(); media("t3.mp3").copyTo(File(root, "Music/.hidden/x.mp3"))
        val skip = File(root, "Music/Backups").apply { mkdirs() }; File(skip, ".nomedia").writeText(""); media("t3.mp3").copyTo(File(skip, "y.mp3"))

        val lib = Library(RuntimeEnvironment.getApplication())
        val r = Root(root.path, "Test", false, File(root, "Music").path)
        lib.mountedRoots = setOf(root.path)
        val prog = Library.Progress()
        lib.scan(listOf(r), prog)
        assertTrue(prog.done)

        val albums = lib.albums()
        assertEquals(albums.map { it.title }.toString(), 2, albums.size)
        val bt = albums.first { it.title == "Blue_Train" }
        assertEquals(4, bt.tracks)            // one album although the WAV has no album artist tag
        assertEquals("emb", bt.art)           // embedded picture
        val cue = albums.first { it.title == "Suites" }
        assertEquals(3, cue.tracks)
        assertTrue(cue.art!!.endsWith("folder.jpg"))
        val ct = lib.albumTracks(cue.key)
        assertEquals(listOf("Prélude", "Allemande", "Courante"), ct.map { it.title })
        assertEquals(listOf(0L, 225L, 487L), ct.map { it.cueStart })
        assertEquals(listOf(225L, 487L, -1L), ct.map { it.cueEnd })
        assertTrue(ct.all { it.path.endsWith("image.flac") && it.codec == "FLAC" && it.rate == 44100 })
        assertEquals(3000L, ct[0].durationMs); assertEquals(2506L, ct[2].durationMs)
        assertEquals("Guest", ct[2].artist)

        // second scan: nothing new; then a file is removed
        val p2 = Library.Progress(); lib.scan(listOf(r), p2)
        assertEquals(0, p2.added)
        File(album, "t2.mp3").delete()
        lib.scan(listOf(r), Library.Progress())
        assertEquals(3, lib.albums().first { it.title == "Blue_Train" }.tracks)
        assertEquals(6, lib.counts().first)
        // an unplugged drive's tracks are hidden, not deleted
        lib.mountedRoots = setOf("/somewhere/else")
        assertEquals(0, lib.albums().size)
        lib.mountedRoots = setOf(root.path)
        assertEquals(2, lib.albums().size)
        assertEquals(listOf("Blue_Train"), lib.search("blue").map { it.album }.distinct())
        assertEquals(3, lib.tracksIn(File(root, "Music/John Coltrane").path).size)
        root.deleteRecursively()
    }
}
