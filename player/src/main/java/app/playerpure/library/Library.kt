package app.playerpure.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class Album(
    val key: String, val title: String, val artist: String, val year: Int, val tracks: Int, val durationMs: Long,
    val firstPath: String, val art: String?, val maxRate: Int, val maxBits: Int, val codec: String, val added: Long,
)

/** A place music is scanned from: a storage volume (tablet or USB drive) and the folder to scan in it. */
data class Root(val path: String, val label: String, val removable: Boolean, val scanPath: String)

/** The music index: an SQLite table of tracks, filled by scanning folders. */
class Library(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tracks(
            id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, cue_index INTEGER NOT NULL DEFAULT 0,
            root TEXT, folder TEXT, mtime INTEGER, size INTEGER,
            title TEXT, artist TEXT, album TEXT, album_artist TEXT, genre TEXT, year INTEGER, track_no INTEGER, disc_no INTEGER,
            duration_ms INTEGER, rate INTEGER, bits INTEGER, channels INTEGER, codec TEXT,
            cue_start INTEGER, cue_end INTEGER, album_key TEXT, art TEXT, added INTEGER,
            UNIQUE(path, cue_index))""")
        db.execSQL("CREATE INDEX idx_album ON tracks(album_key)")
        db.execSQL("CREATE INDEX idx_folder ON tracks(folder)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------ queries
    /** Roots currently mounted: tracks on an unplugged USB drive stay indexed but are hidden. */
    @Volatile var mountedRoots: Set<String> = emptySet()

    private fun rootFilter(): Pair<String, Array<String>> {
        if (mountedRoots.isEmpty()) return "1" to emptyArray()
        return "root IN (" + mountedRoots.joinToString(",") { "?" } + ")" to mountedRoots.toTypedArray()
    }

    private fun track(c: Cursor) = Track(
        id = c.getLong(0), path = c.getString(1), title = c.getString(2) ?: "", artist = c.getString(3) ?: "",
        album = c.getString(4) ?: "", albumArtist = c.getString(5) ?: "", genre = c.getString(6) ?: "", year = c.getInt(7),
        trackNo = c.getInt(8), discNo = c.getInt(9), durationMs = c.getLong(10), rate = c.getInt(11), bits = c.getInt(12),
        channels = c.getInt(13), codec = c.getString(14) ?: "", cueStart = c.getLong(15), cueEnd = c.getLong(16),
        albumKey = c.getString(17) ?: "", art = c.getString(18),
    )

    private val TRACK_COLS = "id, path, title, artist, album, album_artist, genre, year, track_no, disc_no, duration_ms, rate, bits, channels, codec, cue_start, cue_end, album_key, art"
    private val TRACK_ORDER = "disc_no, track_no, path, cue_index"

    private fun tracks(where: String, args: Array<String>, order: String = TRACK_ORDER, limit: String? = null): List<Track> {
        val (rf, ra) = rootFilter()
        val out = ArrayList<Track>()
        readableDatabase.rawQuery("SELECT $TRACK_COLS FROM tracks WHERE ($where) AND $rf ORDER BY $order" + (limit?.let { " LIMIT $it" } ?: ""), args + ra).use { c ->
            while (c.moveToNext()) out += track(c)
        }
        return out
    }

    enum class Sort(val sql: String, val label: String) {
        ARTIST("lower(artist), year, lower(title)", "Artist"),
        TITLE("lower(title)", "Title"),
        YEAR("year DESC, lower(artist)", "Year"),
        ADDED("added DESC", "Recently added"),
    }

    fun albums(sort: Sort = Sort.ARTIST, filter: String? = null, artist: String? = null): List<Album> {
        val (rf, ra) = rootFilter()
        val args = ArrayList<String>()
        var where = rf; args += ra
        if (!filter.isNullOrBlank()) {
            where += " AND (album LIKE ? OR album_artist LIKE ? OR artist LIKE ?)"
            val f = "%${filter.trim()}%"; args += listOf(f, f, f)
        }
        if (artist != null) { where += " AND (CASE WHEN album_artist <> '' THEN album_artist ELSE artist END) = ?"; args += artist }
        val sql = """SELECT album_key, MAX(album) AS title,
                CASE WHEN COUNT(DISTINCT CASE WHEN album_artist <> '' THEN album_artist ELSE artist END) > 1 AND MAX(album_artist) = '' THEN 'Various artists'
                     ELSE MAX(CASE WHEN album_artist <> '' THEN album_artist ELSE artist END) END AS artist,
                MAX(year) AS year, COUNT(*), SUM(duration_ms), MIN(path), MAX(art), MAX(rate), MAX(bits), MAX(codec), MAX(added) AS added
            FROM tracks WHERE $where GROUP BY album_key ORDER BY ${sort.sql}"""
        val out = ArrayList<Album>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += Album(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getInt(4),
                c.getLong(5), c.getString(6) ?: "", c.getString(7), c.getInt(8), c.getInt(9), c.getString(10) ?: "", c.getLong(11))
        }
        return out
    }

    fun album(key: String): Album? = albums().firstOrNull { it.key == key }

    fun albumTracks(key: String): List<Track> = tracks("album_key = ?", arrayOf(key))

    /** Artist name and number of albums, alphabetical. */
    fun artists(): List<Pair<String, Int>> {
        val (rf, ra) = rootFilter()
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery("""SELECT CASE WHEN album_artist <> '' THEN album_artist ELSE artist END AS a, COUNT(DISTINCT album_key)
            FROM tracks WHERE $rf GROUP BY a ORDER BY lower(a)""", ra).use { c ->
            while (c.moveToNext()) { val n = c.getString(0) ?: ""; if (n.isNotBlank()) out += n to c.getInt(1) }
        }
        return out
    }

    fun tracksIn(folder: String): List<Track> = tracks("folder = ? OR folder LIKE ?", arrayOf(folder, "$folder/%"), "folder, $TRACK_ORDER")

    fun search(q: String, limit: Int = 200): List<Track> {
        val f = "%${q.trim()}%"
        return tracks("title LIKE ? OR artist LIKE ? OR album LIKE ?", arrayOf(f, f, f), "lower(artist), lower(album), $TRACK_ORDER", limit.toString())
    }

    fun byIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val map = HashMap<Long, Track>()
        ids.distinct().chunked(500).forEach { part ->
            readableDatabase.rawQuery("SELECT $TRACK_COLS FROM tracks WHERE id IN (${part.joinToString(",")})", null).use { c ->
                while (c.moveToNext()) track(c).let { map[it.id] = it }
            }
        }
        return ids.mapNotNull { map[it] }
    }

    fun counts(): Pair<Int, Int> {
        val (rf, ra) = rootFilter()
        readableDatabase.rawQuery("SELECT COUNT(*), COUNT(DISTINCT album_key) FROM tracks WHERE $rf", ra).use { c ->
            return if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0
        }
    }

    // ------------------------------------------------------------ scanning
    class Progress(@Volatile var files: Int = 0, @Volatile var added: Int = 0, @Volatile var folder: String = "", @Volatile var done: Boolean = false)

    /** Scans [roots] incrementally: unchanged files are skipped, removed files leave the index. */
    fun scan(roots: List<Root>, progress: Progress, cancelled: () -> Boolean = { false }) {
        val db = writableDatabase
        for (root in roots) {
            val base = File(root.scanPath)
            if (!base.isDirectory) continue
            val known = HashMap<String, Pair<Long, Long>>()   // path#cue -> (mtime, size)
            db.rawQuery("SELECT path, cue_index, mtime, size FROM tracks WHERE root = ? AND (path = ? OR path LIKE ?)",
                arrayOf(root.path, root.scanPath, root.scanPath.trimEnd('/') + "/%")).use { c ->
                while (c.moveToNext()) known[c.getString(0) + "#" + c.getInt(1)] = c.getLong(2) to c.getLong(3)
            }
            val seen = HashSet<String>()
            val stack = ArrayDeque<File>(); stack.add(base)
            db.beginTransaction()
            try {
                var batch = 0
                while (stack.isNotEmpty() && !cancelled()) {
                    val dir = stack.removeLast()
                    val list = dir.listFiles() ?: continue
                    if (list.any { it.name == ".nomedia" }) continue
                    progress.folder = dir.path
                    val subdirs = list.filter { it.isDirectory && !skipDir(it) }.sortedBy { it.name.lowercase() }
                    stack.addAll(subdirs.reversed())
                    val files = list.filter { it.isFile }
                    val art = folderArt(dir, files)
                    val covered = HashSet<String>()
                    // CUE sheets first: the files they describe become several tracks
                    for (cueFile in files.filter { it.name.endsWith(".cue", true) }) {
                        val sheet = try { Cue.parse(Cue.decode(cueFile.readBytes())) } catch (_: Exception) { null } ?: continue
                        for (cf in sheet.files) {
                            val audio = resolveCueFile(dir, files, cf.name) ?: continue
                            covered += audio.path
                            val mtime = maxOf(audio.lastModified(), cueFile.lastModified()); val size = audio.length() + cueFile.length()
                            if (cf.tracks.all { known[audio.path + "#" + it.number] == (mtime to size) }) {
                                cf.tracks.forEach { seen += audio.path + "#" + it.number }; progress.files += cf.tracks.size; continue
                            }
                            val info = Tags.read(audio)
                            for (ct in cf.tracks) {
                                val t = TagInfo().also {
                                    it.title = ct.title.ifBlank { "Track ${ct.number}" }
                                    it.artist = ct.performer.ifBlank { sheet.performer.ifBlank { info.artist } }
                                    it.album = sheet.title.ifBlank { info.album }
                                    it.albumArtist = sheet.performer.ifBlank { info.albumArtist }
                                    it.genre = sheet.genre.ifBlank { info.genre }; it.year = if (sheet.year > 0) sheet.year else info.year
                                    it.track = ct.number; it.disc = info.disc
                                    it.rate = info.rate; it.bits = info.bits; it.channels = info.channels; it.codec = info.codec; it.hasArt = info.hasArt
                                    val endFrames = if (ct.end >= 0) ct.end else info.durationMs * 75 / 1000
                                    it.durationMs = ((endFrames - ct.start) * 1000 / 75).coerceAtLeast(0)
                                }
                                upsert(db, root, audio, ct.number, mtime, size, t, ct.start, ct.end, art)
                                seen += audio.path + "#" + ct.number
                                progress.added++
                            }
                            progress.files += cf.tracks.size
                        }
                    }
                    for (f in files) {
                        if (f.path in covered || !Tags.isAudio(f.name)) continue
                        val key = f.path + "#0"
                        val stamp = f.lastModified() to f.length()
                        progress.files++
                        if (known[key] == stamp) { seen += key; continue }
                        val info = Tags.read(f)
                        val embedded = info.cuesheet?.let { Cue.parse(it) }?.files?.firstOrNull()
                        if (embedded != null && embedded.tracks.size > 1) {   // FLAC with an embedded CUESHEET
                            for (ct in embedded.tracks) {
                                val t = TagInfo().also {
                                    it.title = ct.title.ifBlank { "Track ${ct.number}" }; it.artist = ct.performer.ifBlank { info.artist }
                                    it.album = info.album; it.albumArtist = info.albumArtist; it.genre = info.genre; it.year = info.year
                                    it.track = ct.number; it.rate = info.rate; it.bits = info.bits; it.channels = info.channels
                                    it.codec = info.codec; it.hasArt = info.hasArt
                                    val endFrames = if (ct.end >= 0) ct.end else info.durationMs * 75 / 1000
                                    it.durationMs = ((endFrames - ct.start) * 1000 / 75).coerceAtLeast(0)
                                }
                                upsert(db, root, f, ct.number, stamp.first, stamp.second, t, ct.start, ct.end, art)
                                seen += f.path + "#" + ct.number
                            }
                        } else {
                            upsert(db, root, f, 0, stamp.first, stamp.second, info, -1, -1, art)
                            seen += key
                        }
                        progress.added++
                    }
                    if (++batch % 50 == 0) { db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction() }
                }
                if (!cancelled()) for (k in known.keys) if (k !in seen) {
                    val path = k.substringBeforeLast('#'); val cue = k.substringAfterLast('#')
                    db.delete("tracks", "path = ? AND cue_index = ?", arrayOf(path, cue))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        progress.done = true
    }

    private fun skipDir(d: File): Boolean {
        val n = d.name
        return n.startsWith(".") || (n.equals("Android", true) && File(d, "data").isDirectory) ||
            n.equals("LOST.DIR", true) || n.equals("System Volume Information", true) || n.startsWith("\$RECYCLE")
    }

    private fun resolveCueFile(dir: File, files: List<File>, name: String): File? {
        val leaf = name.replace('\\', '/').substringAfterLast('/')
        files.firstOrNull { it.name.equals(leaf, true) }?.let { return it }
        val stem = leaf.substringBeforeLast('.')   // the CUE often names the .wav that was later compressed to .flac
        return files.firstOrNull { it.name.substringBeforeLast('.').equals(stem, true) && Tags.isAudio(it.name) }
    }

    private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")

    private fun folderArt(dir: File, files: List<File>): String? {
        fun pick(list: List<File>): File? {
            val imgs = list.filter { it.isFile && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            for (n in COVER_NAMES) imgs.firstOrNull { it.name.substringBeforeLast('.').equals(n, true) }?.let { return it }
            return imgs.firstOrNull { it.name.contains("cover", true) || it.name.contains("front", true) } ?: imgs.singleOrNull()
        }
        pick(files)?.let { return it.path }
        if (Regex("(?i)^(cd|disc|disk)\\s*\\d+$").matches(dir.name)) dir.parentFile?.listFiles()?.let { p -> pick(p.toList())?.let { return it.path } }
        return null
    }

    /**
     * An album is "same album name in the same folder" (CD1/CD2 sub-folders count as one folder). This keeps a rip
     * together even when its tags disagree about the artist, and keeps two "Greatest Hits" in different folders apart.
     */
    private fun albumKey(t: TagInfo, folder: String): String {
        val base = folder.replace(Regex("(?i)/(cd|disc|disk)\\s*\\d+$"), "")
        return t.album.trim().lowercase() + "|" + base
    }

    private fun upsert(db: SQLiteDatabase, root: Root, f: File, cue: Int, mtime: Long, size: Long, t: TagInfo, cueStart: Long, cueEnd: Long, folderArt: String?) {
        val folder = f.parent ?: ""
        val v = ContentValues().apply {
            put("path", f.path); put("cue_index", cue); put("root", root.path); put("folder", folder)
            put("mtime", mtime); put("size", size)
            put("title", t.title); put("artist", t.artist); put("album", t.album); put("album_artist", t.albumArtist)
            put("genre", t.genre); put("year", t.year); put("track_no", t.track); put("disc_no", t.disc)
            put("duration_ms", t.durationMs); put("rate", t.rate); put("bits", t.bits); put("channels", t.channels); put("codec", t.codec)
            put("cue_start", cueStart); put("cue_end", cueEnd)
            put("album_key", albumKey(t, folder))
            put("art", if (t.hasArt) "emb" else folderArt)
            put("added", System.currentTimeMillis())
        }
        val id = db.insertWithOnConflict("tracks", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) { v.remove("added"); db.update("tracks", v, "path = ? AND cue_index = ?", arrayOf(f.path, cue.toString())) }
    }

    companion object {
        @Volatile private var inst: Library? = null
        fun get(ctx: Context): Library = inst ?: synchronized(this) { inst ?: Library(ctx.applicationContext).also { inst = it } }

        /** Storage volumes: the tablet's own storage and any USB drive. Scans the Music folder if there is one. */
        fun roots(ctx: Context): List<Root> {
            val sm = ctx.getSystemService(StorageManager::class.java)
            val out = ArrayList<Root>()
            for (v in sm?.storageVolumes ?: emptyList()) {
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = v.directory ?: continue
                val music = File(dir, "Music")
                val label = if (v.isPrimary) "Tablet" else (v.getDescription(ctx) ?: "USB drive")
                out += Root(dir.path, label, v.isRemovable, if (music.isDirectory) music.path else dir.path)
            }
            return out
        }
    }
}
