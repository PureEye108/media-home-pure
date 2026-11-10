package app.mediahome.tablet.remote

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The web remote's file manager: the tablet's own storage and any USB drive.
 *
 * Every path the phone sends is a root id plus a relative path. It is resolved with canonical paths, so `..` and
 * symbolic links can never reach outside a root. Delete moves to a per-drive trash folder (`.Trash-MediaHome`);
 * only "Empty trash" removes files for good. Pure Java file APIs, so it is unit-tested on the PC.
 */
class FileStore(private val roots: () -> List<Root>) {
    class Root(val id: String, val label: String, val dir: File, val removable: Boolean)
    class FileError(val status: Int, message: String) : Exception(message)

    fun rootsJson(): String = Json().put("ok", true).put("roots", roots().map { r ->
        Json().put("id", r.id).put("label", r.label).put("removable", r.removable)
            .put("free", r.dir.usableSpace).put("total", r.dir.totalSpace)
    }).toString()

    private fun root(id: String): Root = roots().firstOrNull { it.id == id } ?: throw FileError(404, "drive not found (unplugged?)")

    /** The file for (root, relative path), guaranteed to be inside the root. */
    fun resolve(rootId: String, rel: String): Pair<Root, File> {
        val r = root(rootId)
        if (rel.any { it == '\u0000' }) throw FileError(400, "bad path")
        val base = r.dir.canonicalFile
        val f = File(base, rel.trim('/')).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw FileError(403, "outside the drive")
        return r to f
    }

    private fun relOf(r: Root, f: File): String {
        val base = r.dir.canonicalFile.path
        return f.canonicalFile.path.removePrefix(base).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    fun list(rootId: String, rel: String, showHidden: Boolean = false): String {
        val (r, dir) = resolve(rootId, rel)
        if (!dir.isDirectory) throw FileError(404, "folder not found")
        val all = dir.listFiles() ?: throw FileError(403, "this folder can't be read")
        val shown = all.filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        val entries = shown.take(MAX_ENTRIES).map { f ->
            Json().put("name", f.name).put("dir", f.isDirectory).put("size", if (f.isDirectory) 0L else f.length())
                .put("modified", f.lastModified()).put("kind", if (f.isDirectory) "folder" else kindOf(f.name))
        }
        return Json().put("ok", true).put("root", r.id).put("label", r.label).put("path", relOf(r, dir))
            .put("free", r.dir.usableSpace).put("truncated", shown.size > MAX_ENTRIES).put("entries", entries).toString()
    }

    fun mkdir(rootId: String, rel: String, name: String): String {
        val (r, dir) = resolve(rootId, rel)
        if (!dir.isDirectory) throw FileError(404, "folder not found")
        val n = cleanName(name)
        val f = File(dir, n)
        if (f.exists()) throw FileError(409, "\"$n\" already exists")
        if (!f.mkdir()) throw FileError(500, "could not create the folder")
        return relOf(r, f)
    }

    fun rename(rootId: String, rel: String, newName: String): String {
        val (r, f) = resolve(rootId, rel)
        if (f == r.dir.canonicalFile) throw FileError(400, "the drive itself can't be renamed")
        if (!f.exists()) throw FileError(404, "not found")
        val n = cleanName(newName)
        val target = File(f.parentFile, n)
        if (target.exists()) throw FileError(409, "\"$n\" already exists")
        if (!f.renameTo(target)) throw FileError(500, "could not rename")
        return relOf(r, target)
    }

    /** Moves a file or folder into the drive's trash folder. */
    fun trash(rootId: String, rel: String, now: Long = System.currentTimeMillis()): String {
        val (r, f) = resolve(rootId, rel)
        val base = r.dir.canonicalFile
        val bin = File(base, TRASH)
        if (f == base || f == bin.canonicalFile) throw FileError(400, "this can't be deleted")
        if (!f.exists()) throw FileError(404, "not found")
        if (!bin.isDirectory && !bin.mkdirs()) throw FileError(500, "could not create the trash folder")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val target = uniqueFile(bin, "$stamp ${f.name}")
        if (!f.renameTo(target)) throw FileError(500, "could not move to the trash")
        return relOf(r, target)
    }

    /** Deletes everything in the drive's trash for good (does not follow links). Returns the number of items removed. */
    fun emptyTrash(rootId: String): Int {
        val (_, base) = resolve(rootId, "")
        val bin = File(base, TRASH)
        if (!bin.exists()) return 0
        var count = 0
        Files.walkFileTree(bin.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file); count++; return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (dir != bin.toPath()) { Files.deleteIfExists(dir); count++ }
                return FileVisitResult.CONTINUE
            }
        })
        return count
    }

    /** A readable regular file (for download and play). */
    fun file(rootId: String, rel: String): File {
        val (_, f) = resolve(rootId, rel)
        if (!f.isFile) throw FileError(404, "file not found")
        if (!f.canRead()) throw FileError(403, "this file can't be read")
        return f
    }

    /**
     * Saves an upload of exactly [length] bytes into folder (root, rel). Written to a hidden ".part" file first,
     * renamed when complete; a same-named file is never overwritten ("name (2).ext").
     */
    fun upload(rootId: String, rel: String, name: String, length: Long, input: InputStream): File {
        val (_, dir) = resolve(rootId, rel)
        if (!dir.isDirectory) throw FileError(404, "folder not found")
        val n = cleanName(name)
        if (dir.usableSpace < length + RESERVE) throw FileError(507, "not enough free space on this drive")
        val part = File(dir, ".$n.${System.nanoTime()}.part")
        try {
            var written = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (written < length) {
                    val k = input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                    if (k < 0) break
                    out.write(buf, 0, k)
                    written += k
                }
            }
            if (written != length) throw FileError(400, "upload interrupted")
            val target = uniqueFile(dir, n)
            if (!part.renameTo(target)) throw FileError(500, "could not save the file")
            return target
        } finally {
            if (part.exists()) part.delete()
        }
    }

    companion object {
        const val TRASH = ".Trash-MediaHome"
        const val MAX_ENTRIES = 5000
        /** Keep this much space free on a drive after an upload. */
        const val RESERVE = 50L * 1024 * 1024

        private val AUDIO = setOf("mp3", "flac", "alac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "dsf", "dff", "ape", "wv", "wma", "mka", "m3u", "m3u8", "cue")
        private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg", "vob", "iso")
        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

        fun kindOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                in AUDIO -> "audio"
                in VIDEO -> "video"
                in IMAGE -> "image"
                else -> "other"
            }
        }

        /**
         * A safe file name: no folders, no control characters, and none of the characters FAT/exFAT drives refuse
         * (they become "_"). Not empty, not "." or "..", at most 200 characters.
         */
        fun cleanName(name: String): String {
            val n = name.trim().map { c -> if (c.code < 32 || c in "/\\:*?\"<>|") '_' else c }.joinToString("").trim().trimEnd('.')
            if (n.isEmpty() || n == "." || n == ".." || n.startsWith(".Trash-")) throw FileError(400, "not a valid name")
            return if (n.length > 200) n.take(200) else n
        }

        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 2
            while (f.exists()) { f = File(dir, "$stem ($i)$ext"); i++ }
            return f
        }
    }
}
