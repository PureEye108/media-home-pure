package app.mediahome.tablet.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class FileStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var tablet: File
    private lateinit var usb: File
    private lateinit var outside: File
    private lateinit var fs: FileStore

    @Before fun setUp() {
        tablet = tmp.newFolder("tablet"); usb = tmp.newFolder("usb"); outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        fs = FileStore { listOf(FileStore.Root("internal", "Tablet", tablet, false), FileStore.Root("usb-1", "USB drive", usb, true)) }
    }

    private fun status(block: () -> Unit): Int = try { block(); 200 } catch (e: FileStore.FileError) { e.status }

    @Test fun pathsCannotLeaveTheDrive() {
        assertEquals(403, status { fs.resolve("internal", "../outside/secret.txt") })
        assertEquals(403, status { fs.resolve("internal", "Music/../../outside") })
        assertEquals(404, status { fs.resolve("nope", "") })
        assertEquals(File(tablet, "etc").canonicalFile, fs.resolve("internal", "/etc").second)   // stays inside the root
        Files.createSymbolicLink(File(tablet, "link").toPath(), outside.toPath())
        assertEquals(403, status { fs.file("internal", "link/secret.txt") })
        assertEquals(403, status { fs.list("internal", "link") })
    }

    @Test fun listingIsSortedWithFoldersFirstAndHidesDotFiles() {
        File(tablet, "b.flac").writeText("x"); File(tablet, "A.mkv").writeText("x"); File(tablet, ".hidden").writeText("x")
        File(tablet, "zMusic").mkdir(); File(tablet, "Movies").mkdir()
        val j = fs.list("internal", "")
        val names = Regex("\"name\":\"([^\"]+)\"").findAll(j).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Movies", "zMusic", "A.mkv", "b.flac"), names)
        assertTrue(j.contains("\"kind\":\"video\"") && j.contains("\"kind\":\"audio\"") && j.contains("\"kind\":\"folder\""))
        assertTrue(fs.list("internal", "", showHidden = true).contains(".hidden"))
    }

    @Test fun namesAreMadeSafeForUsbDrives() {
        assertEquals("AC_DC_ Live_.flac", FileStore.cleanName(" AC/DC: Live?.flac "))
        assertEquals(400, status { FileStore.cleanName("..") })
        assertEquals(400, status { FileStore.cleanName("   ") })
        assertEquals(400, status { FileStore.cleanName(".Trash-MediaHome") })
        assertEquals("song", FileStore.cleanName("song..."))
    }

    @Test fun createRenameAndTrash() {
        assertEquals("Music", fs.mkdir("usb-1", "", "Music"))
        assertEquals(409, status { fs.mkdir("usb-1", "", "Music") })
        File(usb, "Music/a.flac").writeText("aaa")
        assertEquals("Music/b.flac", fs.rename("usb-1", "Music/a.flac", "b.flac"))
        assertEquals(400, status { fs.rename("usb-1", "", "x") })
        val trashed = fs.trash("usb-1", "Music/b.flac", 0L)
        assertTrue(trashed.startsWith(FileStore.TRASH + "/") && trashed.endsWith("b.flac"))
        assertFalse(File(usb, "Music/b.flac").exists())
        assertEquals(400, status { fs.trash("usb-1", "") })
        assertEquals(400, status { fs.trash("usb-1", FileStore.TRASH) })
        assertFalse(fs.list("usb-1", "").contains(FileStore.TRASH))   // the trash is hidden
    }

    @Test fun emptyTrashDeletesForGoodButNeverFollowsLinks() {
        File(tablet, "old").mkdirs(); File(tablet, "old/x.mp3").writeText("x")
        fs.trash("internal", "old", 0L)
        val bin = File(tablet, FileStore.TRASH)
        Files.createSymbolicLink(File(bin, "evil").toPath(), outside.toPath())
        assertTrue(fs.emptyTrash("internal") >= 3)
        assertTrue(bin.listFiles()!!.isEmpty())
        assertTrue("files outside the trash must survive", File(outside, "secret.txt").exists())
    }

    @Test fun uploadsAreCompleteOrNotSaved() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val f = fs.upload("internal", "", "song.flac", data.size.toLong(), ByteArrayInputStream(data))
        assertTrue(f.readBytes().contentEquals(data))
        val second = fs.upload("internal", "", "song.flac", 3, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals("song (2).flac", second.name)
        assertEquals(400, status { fs.upload("internal", "", "cut.flac", 10, ByteArrayInputStream(byteArrayOf(1, 2))) })
        assertFalse(File(tablet, "cut.flac").exists())
        assertTrue("no partial files left", tablet.listFiles()!!.none { it.name.endsWith(".part") })
        try { fs.upload("internal", "nofolder", "x.mp3", 1, ByteArrayInputStream(byteArrayOf(1))); fail() } catch (e: FileStore.FileError) { assertEquals(404, e.status) }
    }
}
