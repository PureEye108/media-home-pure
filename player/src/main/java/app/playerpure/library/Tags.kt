package app.playerpure.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Base64

/** What the scanner learns about a file. */
class TagInfo {
    var title = ""; var artist = ""; var album = ""; var albumArtist = ""; var genre = ""
    var year = 0; var track = 0; var disc = 0
    var durationMs = 0L; var rate = 0; var bits = 0; var channels = 0; var codec = ""
    var hasArt = false
    var cuesheet: String? = null
    internal var art: ByteArray? = null
}

/**
 * Tag and format readers for FLAC, MP3 (ID3v2), MP4/M4A (ALAC, AAC), Ogg (Vorbis, Opus), WAV, AIFF, DSF and DFF.
 * Plain JVM code (no Android), reading only the parts of each file it needs.
 */
object Tags {
    val AUDIO_EXT = setOf("flac", "wav", "aif", "aiff", "aifc", "m4a", "alac", "mp4", "dsf", "dff", "mp3", "aac", "ogg", "oga", "opus")
    private val LATIN1: Charset = Charsets.ISO_8859_1

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    /** Reads tags and format; falls back to names from the file and folder. Never throws. */
    fun read(file: File): TagInfo {
        val t = TagInfo()
        try { RandomAccessFile(file, "r").use { parse(it, t, false) } } catch (_: Exception) {}
        fallbackNames(file, t)
        return t
    }

    /** The embedded cover picture, if any. */
    fun readArt(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { val t = TagInfo(); parse(it, t, true); t.art }
    } catch (_: Exception) { null }

    private fun parse(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        val head = ByteArray(16)
        f.seek(0); if (f.read(head) < 12) return
        when {
            head.startsWith("fLaC") -> flac(f, 0, t, wantArt)
            head.startsWith("ID3") -> {
                val end = id3(f, 0, t, wantArt)
                f.seek(end); val h2 = ByteArray(4)
                if (f.read(h2) == 4 && h2.startsWith("fLaC")) flac(f, end, t, wantArt) else mp3Info(f, end, t)
            }
            head.startsWith("RIFF") || head.startsWith("RF64") -> wav(f, t, wantArt)
            head.startsWith("FORM") && (head.at(8, "AIFF") || head.at(8, "AIFC")) -> aiff(f, t, wantArt)
            head.startsWith("DSD ") -> dsf(f, t, wantArt)
            head.startsWith("FRM8") -> dff(f, t, wantArt)
            head.at(4, "ftyp") -> mp4(f, t, wantArt)
            head.startsWith("OggS") -> ogg(f, t, wantArt)
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> mp3Info(f, 0, t)
        }
    }

    // ------------------------------------------------------------ helpers
    private fun ByteArray.startsWith(s: String) = at(0, s)
    private fun ByteArray.at(o: Int, s: String): Boolean {
        if (o + s.length > size) return false
        for (i in s.indices) if (this[o + i] != s[i].code.toByte()) return false
        return true
    }
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun be16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun be32(b: ByteArray, o: Int) = (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    private fun be64(b: ByteArray, o: Int) = (be32(b, o) shl 32) or be32(b, o + 4)
    private fun le16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun le32(b: ByteArray, o: Int) = u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)
    private fun le64(b: ByteArray, o: Int) = le32(b, o) or (le32(b, o + 4) shl 32)
    private fun syncsafe(b: ByteArray, o: Int) = (u8(b, o) and 0x7F shl 21) or (u8(b, o + 1) and 0x7F shl 14) or (u8(b, o + 2) and 0x7F shl 7) or (u8(b, o + 3) and 0x7F)

    private fun readAt(f: RandomAccessFile, pos: Long, n: Int): ByteArray? {
        if (n < 0 || n > 64 shl 20 || pos < 0 || pos + n > f.length()) return null
        val b = ByteArray(n); f.seek(pos); f.readFully(b); return b
    }

    private fun firstInt(s: String): Int = Regex("\\d+").find(s)?.value?.take(9)?.toIntOrNull() ?: 0

    /** Vorbis comment / ID3 / MP4 field names, normalised. */
    private fun apply(t: TagInfo, key: String, value: String) {
        val v = value.trim().trimEnd('\u0000')
        if (v.isEmpty()) return
        when (key.uppercase()) {
            "TITLE" -> if (t.title.isEmpty()) t.title = v
            "ARTIST" -> if (t.artist.isEmpty()) t.artist = v
            "ALBUM" -> if (t.album.isEmpty()) t.album = v
            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> if (t.albumArtist.isEmpty()) t.albumArtist = v
            "GENRE" -> if (t.genre.isEmpty()) t.genre = v.replace(Regex("^\\(\\d+\\)"), "").ifEmpty { v }
            "DATE", "YEAR", "ORIGINALDATE" -> if (t.year == 0) t.year = Regex("\\d{4}").find(v)?.value?.toInt() ?: 0
            "TRACKNUMBER", "TRACK" -> if (t.track == 0) t.track = firstInt(v)
            "DISCNUMBER", "DISC" -> if (t.disc == 0) t.disc = firstInt(v)
            "CUESHEET" -> if (t.cuesheet == null) t.cuesheet = v
        }
    }

    private fun vorbisComments(b: ByteArray, start: Int, t: TagInfo, wantArt: Boolean) {
        var o = start
        if (o + 4 > b.size) return
        val vendor = le32(b, o).toInt(); o += 4 + vendor
        if (vendor < 0 || o + 4 > b.size) return
        val n = le32(b, o).toInt(); o += 4
        for (i in 0 until n) {
            if (o + 4 > b.size) break
            val len = le32(b, o).toInt(); o += 4
            if (len < 0 || o + len > b.size) break
            val s = String(b, o, len, Charsets.UTF_8); o += len
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            val k = s.substring(0, eq)
            if (k.equals("METADATA_BLOCK_PICTURE", true)) {
                t.hasArt = true
                if (wantArt && t.art == null) try { t.art = flacPicture(Base64.getDecoder().decode(s.substring(eq + 1).trim()), 0) } catch (_: Exception) {}
            } else apply(t, k, s.substring(eq + 1))
        }
    }

    /** FLAC PICTURE block body -> image bytes. */
    private fun flacPicture(b: ByteArray, start: Int): ByteArray? {
        var o = start + 4
        val mime = be32(b, o).toInt(); o += 4 + mime
        val desc = be32(b, o).toInt(); o += 4 + desc + 16
        val len = be32(b, o).toInt(); o += 4
        return if (len > 0 && o + len <= b.size) b.copyOfRange(o, o + len) else null
    }

    // ------------------------------------------------------------ FLAC
    private fun flac(f: RandomAccessFile, start: Long, t: TagInfo, wantArt: Boolean) {
        t.codec = "FLAC"
        var pos = start + 4
        val h = ByteArray(4)
        var pictureType = -1
        while (pos + 4 <= f.length()) {
            f.seek(pos); f.readFully(h)
            val last = (u8(h, 0) and 0x80) != 0
            val type = u8(h, 0) and 0x7F
            val len = (u8(h, 1) shl 16) or (u8(h, 2) shl 8) or u8(h, 3)
            when (type) {
                0 -> readAt(f, pos + 4, minOf(len, 34))?.let { b ->
                    if (b.size >= 18) {
                        t.rate = (u8(b, 10) shl 12) or (u8(b, 11) shl 4) or (u8(b, 12) shr 4)
                        t.channels = ((u8(b, 12) shr 1) and 7) + 1
                        t.bits = (((u8(b, 12) and 1) shl 4) or (u8(b, 13) shr 4)) + 1
                        val total = ((u8(b, 13) and 0x0F).toLong() shl 32) or be32(b, 14)
                        if (t.rate > 0) t.durationMs = total * 1000 / t.rate
                    }
                }
                4 -> readAt(f, pos + 4, len)?.let { vorbisComments(it, 0, t, wantArt) }
                6 -> {
                    t.hasArt = true
                    if (wantArt) readAt(f, pos + 4, len)?.let { b ->
                        val ptype = be32(b, 0).toInt()
                        if (t.art == null || (ptype == 3 && pictureType != 3)) { flacPicture(b, 0)?.let { t.art = it; pictureType = ptype } }
                    }
                }
            }
            pos += 4 + len
            if (last) break
        }
    }

    // ------------------------------------------------------------ ID3v2 (returns the position after the tag)
    private fun id3(f: RandomAccessFile, start: Long, t: TagInfo, wantArt: Boolean): Long {
        val h = readAt(f, start, 10) ?: return start
        if (!h.startsWith("ID3")) return start
        val ver = u8(h, 3); val flags = u8(h, 5)
        val size = syncsafe(h, 6)
        val end = start + 10 + size + (if ((flags and 0x10) != 0) 10 else 0)
        var b = readAt(f, start + 10, size) ?: return end
        if ((flags and 0x80) != 0 && ver < 4) b = deunsync(b)
        id3Frames(b, ver, flags, t, wantArt)
        return end
    }

    private fun deunsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) { out.write(b[i].toInt()); if (u8(b, i) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++; i++ }
        return out.toByteArray()
    }

    private fun id3Text(b: ByteArray, o: Int, end: Int): String {
        if (o >= end) return ""
        val enc = u8(b, o)
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> LATIN1 }
        val s = String(b, o + 1, end - o - 1, cs)
        return s.split('\u0000').firstOrNull { it.isNotBlank() } ?: ""
    }

    /** Index just after a string terminated in encoding [enc] starting at [o]. */
    private fun skipString(b: ByteArray, o: Int, enc: Int, end: Int): Int {
        var i = o
        if (enc == 1 || enc == 2) { while (i + 1 < end && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2; return i + 2 }
        while (i < end && b[i].toInt() != 0) i++
        return i + 1
    }

    private fun id3Frames(b: ByteArray, ver: Int, tagFlags: Int, t: TagInfo, wantArt: Boolean) {
        var o = 0
        if ((tagFlags and 0x40) != 0 && ver >= 3) o += if (ver == 4) syncsafe(b, 0) else (be32(b, 0).toInt() + 4)
        val idLen = if (ver == 2) 3 else 4
        val hdr = if (ver == 2) 6 else 10
        var pictureType = -1
        while (o + hdr <= b.size) {
            if (b[o].toInt() == 0) break
            val id = String(b, o, idLen, LATIN1)
            val size = when (ver) { 2 -> (u8(b, o + 3) shl 16) or (u8(b, o + 4) shl 8) or u8(b, o + 5); 3 -> be32(b, o + 4).toInt(); else -> syncsafe(b, o + 4) }
            val fflags = if (ver >= 3) u8(b, o + 9) else 0
            var s = o + hdr
            var e = s + size
            if (size <= 0 || e > b.size) break
            var data = b; var ds = s; var de = e
            if (ver == 4 && (fflags and 0x02) != 0) {   // frame-level unsynchronisation
                val start = if ((fflags and 0x01) != 0) s + 4 else s
                data = deunsync(b.copyOfRange(start, e)); ds = 0; de = data.size
            } else if (ver == 4 && (fflags and 0x01) != 0) ds += 4
            when (id) {
                "TIT2", "TT2" -> apply(t, "TITLE", id3Text(data, ds, de))
                "TPE1", "TP1" -> apply(t, "ARTIST", id3Text(data, ds, de))
                "TALB", "TAL" -> apply(t, "ALBUM", id3Text(data, ds, de))
                "TPE2", "TP2" -> apply(t, "ALBUMARTIST", id3Text(data, ds, de))
                "TCON", "TCO" -> apply(t, "GENRE", id3Text(data, ds, de))
                "TRCK", "TRK" -> apply(t, "TRACKNUMBER", id3Text(data, ds, de))
                "TPOS", "TPA" -> apply(t, "DISCNUMBER", id3Text(data, ds, de))
                "TYER", "TYE", "TDRC", "TDOR" -> apply(t, "DATE", id3Text(data, ds, de))
                "TXXX", "TXX" -> {
                    val enc = u8(data, ds)
                    val descEnd = skipString(data, ds + 1, enc, de)
                    val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> LATIN1 }
                    val desc = String(data, ds + 1, (descEnd - ds - 1 - (if (enc == 1 || enc == 2) 2 else 1)).coerceAtLeast(0), cs)
                    val value = if (descEnd < de) String(data, descEnd, de - descEnd, cs).trimEnd('\u0000') else ""
                    apply(t, desc, value)
                }
                "APIC", "PIC" -> {
                    t.hasArt = true
                    if (wantArt) {
                        val enc = u8(data, ds)
                        var p = if (id == "PIC") ds + 4 else skipString(data, ds + 1, 0, de)
                        val ptype = if (p < de) u8(data, p) else 0
                        p = skipString(data, p + 1, enc, de)
                        if (p < de && (t.art == null || (ptype == 3 && pictureType != 3))) { t.art = data.copyOfRange(p, de); pictureType = ptype }
                    }
                }
            }
            o = e
        }
    }

    // ------------------------------------------------------------ MP3 stream info
    private fun mp3Info(f: RandomAccessFile, start: Long, t: TagInfo) {
        t.codec = "MP3"
        val b = readAt(f, start, minOf(65536L, f.length() - start).toInt()) ?: return
        var i = 0
        while (i + 4 < b.size) {
            if (u8(b, i) == 0xFF && (u8(b, i + 1) and 0xE0) == 0xE0) {
                val v = (u8(b, i + 1) shr 3) and 3; val layer = (u8(b, i + 1) shr 1) and 3
                val bri = u8(b, i + 2) shr 4; val sri = (u8(b, i + 2) shr 2) and 3
                if (v != 1 && layer == 1 && bri in 1..14 && sri < 3) {
                    val mpeg1 = v == 3
                    val rates = when (v) { 3 -> intArrayOf(44100, 48000, 32000); 2 -> intArrayOf(22050, 24000, 16000); else -> intArrayOf(11025, 12000, 8000) }
                    val br1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                    val br2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                    t.rate = rates[sri]; t.bits = 16
                    val mono = (u8(b, i + 3) shr 6) == 3
                    t.channels = if (mono) 1 else 2
                    val spf = if (mpeg1) 1152 else 576
                    val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                    val x = i + 4 + side
                    var frames = 0L
                    if (x + 12 <= b.size && (b.at(x, "Xing") || b.at(x, "Info")) && (be32(b, x + 4) and 1L) != 0L) frames = be32(b, x + 8)
                    else if (i + 36 + 18 <= b.size && b.at(i + 36, "VBRI")) frames = be32(b, i + 36 + 14)
                    t.durationMs = if (frames > 0) frames * spf * 1000 / t.rate
                                   else (f.length() - start - i) * 8 / ((if (mpeg1) br1 else br2)[bri].toLong())
                    return
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------ WAV / RF64
    private fun wav(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        t.codec = "WAV"
        var pos = 12L; var dataSize = -1L; var blockAlign = 0; var ds64 = -1L
        val h = ByteArray(8)
        while (pos + 8 <= f.length()) {
            f.seek(pos); f.readFully(h)
            val id = String(h, 0, 4, LATIN1); var size = le32(h, 4)
            when (id) {
                "ds64" -> readAt(f, pos + 8, 24)?.let { ds64 = le64(it, 8) }
                "fmt " -> readAt(f, pos + 8, minOf(size, 40).toInt())?.let { b ->
                    t.channels = le16(b, 2); t.rate = le32(b, 4).toInt(); blockAlign = le16(b, 12); t.bits = le16(b, 14)
                    if (le16(b, 0) == 0xFFFE && b.size >= 20 && le16(b, 18) > 0) t.bits = le16(b, 18)
                }
                "data" -> { dataSize = if (size == 0xFFFFFFFFL && ds64 >= 0) ds64 else size; if (size == 0xFFFFFFFFL) size = dataSize }
                "LIST" -> readAt(f, pos + 8, minOf(size, 1 shl 20).toInt())?.let { b ->
                    if (b.startsWith("INFO")) {
                        var o = 4
                        while (o + 8 <= b.size) {
                            val k = String(b, o, 4, LATIN1); val l = le32(b, o + 4).toInt()
                            if (l < 0 || o + 8 + l > b.size) break
                            val v = String(b, o + 8, l, Charsets.UTF_8).trimEnd('\u0000')
                            when (k) { "INAM" -> apply(t, "TITLE", v); "IART" -> apply(t, "ARTIST", v); "IPRD" -> apply(t, "ALBUM", v)
                                "ICRD" -> apply(t, "DATE", v); "IGNR" -> apply(t, "GENRE", v); "ITRK", "IPRT" -> apply(t, "TRACKNUMBER", v) }
                            o += 8 + l + (l and 1)
                        }
                    }
                }
                "id3 ", "ID3 " -> id3(f, pos + 8, t, wantArt)
            }
            if (size < 0) break
            pos += 8 + size + (size and 1L)
        }
        if (dataSize > 0 && blockAlign > 0 && t.rate > 0) t.durationMs = dataSize / blockAlign * 1000 / t.rate
    }

    // ------------------------------------------------------------ AIFF
    private fun extended(b: ByteArray, o: Int): Double {
        val exp = ((u8(b, o) and 0x7F) shl 8) or u8(b, o + 1)
        val mant = be64(b, o + 2)
        if (exp == 0 && mant == 0L) return 0.0
        return (mant ushr 1).toDouble() * Math.pow(2.0, (exp - 16383 - 62).toDouble())
    }

    private fun aiff(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        t.codec = "AIFF"
        var pos = 12L
        val h = ByteArray(8)
        while (pos + 8 <= f.length()) {
            f.seek(pos); f.readFully(h)
            val id = String(h, 0, 4, LATIN1); val size = be32(h, 4)
            when (id) {
                "COMM" -> readAt(f, pos + 8, 18)?.let { b ->
                    t.channels = be16(b, 0); val frames = be32(b, 2); t.bits = be16(b, 6); t.rate = extended(b, 8).toInt()
                    if (t.rate > 0) t.durationMs = frames * 1000 / t.rate
                }
                "NAME" -> readAt(f, pos + 8, minOf(size, 4096).toInt())?.let { apply(t, "TITLE", String(it, LATIN1)) }
                "AUTH" -> readAt(f, pos + 8, minOf(size, 4096).toInt())?.let { apply(t, "ARTIST", String(it, LATIN1)) }
                "ID3 " -> id3(f, pos + 8, t, wantArt)
            }
            pos += 8 + size + (size and 1L)
        }
    }

    // ------------------------------------------------------------ DSF / DFF
    private fun dsf(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        t.codec = "DSF"
        val b = readAt(f, 0, 80) ?: return
        val meta = le64(b, 20)
        t.channels = le32(b, 52).toInt(); t.rate = le32(b, 56).toInt(); t.bits = 1
        val samples = le64(b, 64)
        if (t.rate > 0) t.durationMs = samples * 1000 / t.rate
        if (meta > 0 && meta < f.length()) id3(f, meta, t, wantArt)
    }

    private fun dff(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        t.codec = "DFF"; t.bits = 1
        val h = ByteArray(12)
        var pos = 16L; var dataLen = 0L
        while (pos + 12 <= f.length()) {
            f.seek(pos); f.readFully(h)
            val id = String(h, 0, 4, LATIN1); val size = be64(h, 4)
            if (size < 0) break
            when (id) {
                "PROP" -> {
                    var p = pos + 16; val pe = pos + 12 + size
                    while (p + 12 <= pe) {
                        f.seek(p); f.readFully(h)
                        val sid = String(h, 0, 4, LATIN1); val ssz = be64(h, 4)
                        if (sid == "FS  ") readAt(f, p + 12, 4)?.let { t.rate = be32(it, 0).toInt() }
                        if (sid == "CHNL") readAt(f, p + 12, 2)?.let { t.channels = be16(it, 0) }
                        p += 12 + ssz + (ssz and 1L)
                    }
                }
                "DSD " -> dataLen = size
                "DIIN" -> {
                    var p = pos + 12; val pe = pos + 12 + size
                    while (p + 12 <= pe) {
                        f.seek(p); f.readFully(h)
                        val sid = String(h, 0, 4, LATIN1); val ssz = be64(h, 4)
                        if ((sid == "DIAR" || sid == "DITI") && ssz in 4..4096) readAt(f, p + 12, ssz.toInt())?.let {
                            val n = be32(it, 0).toInt().coerceAtMost(it.size - 4)
                            if (n > 0) apply(t, if (sid == "DIAR") "ARTIST" else "TITLE", String(it, 4, n, LATIN1))
                        }
                        p += 12 + ssz + (ssz and 1L)
                    }
                }
                "ID3 " -> id3(f, pos + 12, t, wantArt)
            }
            pos += 12 + size + (size and 1L)
        }
        if (t.rate > 0 && t.channels > 0) t.durationMs = dataLen / t.channels * 8 * 1000 / t.rate
    }

    // ------------------------------------------------------------ MP4 / M4A
    private fun mp4(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        t.codec = "AAC"
        mp4Boxes(f, 0, f.length(), t, wantArt, 0)
    }

    private fun mp4Boxes(f: RandomAccessFile, start: Long, end: Long, t: TagInfo, wantArt: Boolean, depth: Int) {
        if (depth > 8) return
        var pos = start
        val h = ByteArray(16)
        while (pos + 8 <= end) {
            f.seek(pos); f.readFully(h, 0, 8)
            var size = be32(h, 0); val type = String(h, 4, 4, LATIN1)
            var body = pos + 8
            if (size == 1L) { f.readFully(h, 8, 8); size = be64(h, 8); body = pos + 16 }
            else if (size == 0L) size = end - pos
            if (size < 8 || pos + size > end) break
            val boxEnd = pos + size
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl", "udta", "ilst" -> mp4Boxes(f, body, boxEnd, t, wantArt, depth + 1)
                "meta" -> mp4Boxes(f, body + 4, boxEnd, t, wantArt, depth + 1)
                "mdhd" -> readAt(f, body, 32)?.let { b ->
                    val v1 = u8(b, 0) == 1
                    val scale = if (v1) be32(b, 20) else be32(b, 12)
                    val dur = if (v1) be64(b, 24) else be32(b, 16)
                    if (scale > 0 && t.durationMs == 0L) t.durationMs = dur * 1000 / scale
                }
                "stsd" -> readAt(f, body, minOf(size - 8, 256).toInt())?.let { b ->
                    if (b.size >= 8 + 36) {
                        val fmt = String(b, 12, 4, LATIN1)
                        t.channels = be16(b, 8 + 8 + 16); t.bits = be16(b, 8 + 8 + 18); t.rate = (be32(b, 8 + 8 + 24) shr 16).toInt()
                        if (fmt == "alac") {
                            t.codec = "ALAC"
                            val c = 8 + 8 + 28
                            if (b.size >= c + 12 + 24 && b.at(c + 4, "alac")) {
                                val cfg = c + 12
                                t.bits = u8(b, cfg + 5); t.channels = u8(b, cfg + 9); t.rate = be32(b, cfg + 20).toInt()
                            }
                        } else if (fmt == "mp4a") t.bits = 16
                    }
                }
                else -> if (depth >= 1 && (type.startsWith("©") || type in setOf("aART", "trkn", "disk", "gnre", "covr"))) {
                    mp4Item(f, type, body, boxEnd, t, wantArt)
                }
            }
            pos = boxEnd
        }
    }

    private fun mp4Item(f: RandomAccessFile, type: String, start: Long, end: Long, t: TagInfo, wantArt: Boolean) {
        if (type == "covr") { t.hasArt = true; if (!wantArt) return }
        val b = readAt(f, start, minOf(end - start, (16 shl 20).toLong()).toInt()) ?: return
        if (b.size < 16 || !b.at(4, "data")) return
        val len = be32(b, 0).toInt().coerceAtMost(b.size)
        if (len < 16) return
        val payload = b.copyOfRange(16, len)
        when (type) {
            "©nam" -> apply(t, "TITLE", String(payload, Charsets.UTF_8))
            "©ART" -> apply(t, "ARTIST", String(payload, Charsets.UTF_8))
            "©alb" -> apply(t, "ALBUM", String(payload, Charsets.UTF_8))
            "aART" -> apply(t, "ALBUMARTIST", String(payload, Charsets.UTF_8))
            "©gen" -> apply(t, "GENRE", String(payload, Charsets.UTF_8))
            "©day" -> apply(t, "DATE", String(payload, Charsets.UTF_8))
            "trkn" -> if (payload.size >= 4 && t.track == 0) t.track = be16(payload, 2)
            "disk" -> if (payload.size >= 4 && t.disc == 0) t.disc = be16(payload, 2)
            "covr" -> if (t.art == null) t.art = payload
        }
    }

    // ------------------------------------------------------------ Ogg (Vorbis, Opus)
    private fun ogg(f: RandomAccessFile, t: TagInfo, wantArt: Boolean) {
        val packets = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        var pos = 0L
        val h = ByteArray(27)
        while (packets.size < 2 && pos + 27 <= f.length()) {
            f.seek(pos); f.readFully(h)
            if (!h.startsWith("OggS")) break
            val nseg = u8(h, 26)
            val lace = ByteArray(nseg); f.readFully(lace)
            var dataPos = pos + 27 + nseg
            for (k in 0 until nseg) {
                val l = u8(lace, k)
                if (cur.size() + l > (16 shl 20)) return
                val seg = ByteArray(l); f.seek(dataPos); f.readFully(seg); cur.write(seg); dataPos += l
                if (l < 255) { packets += cur.toByteArray(); cur.reset(); if (packets.size >= 2) break }
            }
            pos = pos + 27 + nseg + lace.sumOf { it.toInt() and 0xFF }
        }
        if (packets.isEmpty()) return
        val id = packets[0]
        var preskip = 0
        if (id.size >= 16 && id[0].toInt() == 1 && id.at(1, "vorbis")) { t.codec = "Vorbis"; t.channels = u8(id, 11); t.rate = le32(id, 12).toInt() }
        else if (id.size >= 16 && id.startsWith("OpusHead")) { t.codec = "Opus"; t.channels = u8(id, 9); preskip = le16(id, 10); t.rate = 48000 }
        else return
        t.bits = 16
        if (packets.size > 1) {
            val c = packets[1]
            if (t.codec == "Vorbis" && c.size > 7 && c[0].toInt() == 3) vorbisComments(c, 7, t, wantArt)
            if (t.codec == "Opus" && c.startsWith("OpusTags")) vorbisComments(c, 8, t, wantArt)
        }
        // duration from the last page's granule position
        val tail = minOf(65536L, f.length())
        val b = readAt(f, f.length() - tail, tail.toInt()) ?: return
        var i = b.size - 27
        while (i >= 0) { if (b.at(i, "OggS")) break; i-- }
        if (i >= 0 && t.rate > 0) {
            val g = le64(b, i + 6) - preskip
            if (g > 0) t.durationMs = g * 1000 / t.rate
        }
    }

    // ------------------------------------------------------------ names from the file system
    private val LEADING = Regex("^(\\d{1,2}[-.]?)?(\\d{1,3})\\s*[-._)]\\s*")

    fun fallbackNames(file: File, t: TagInfo) {
        val base = file.name.substringBeforeLast('.')
        if (t.title.isBlank()) {
            val m = LEADING.find(base)
            t.title = if (m != null && m.range.last + 1 < base.length) base.substring(m.range.last + 1) else base
            if (t.track == 0 && m != null) t.track = m.groupValues[2].toIntOrNull() ?: 0
        }
        val folder = file.parentFile
        val albumDir = if (folder != null && Regex("(?i)^(cd|disc|disk)\\s*\\d+$").matches(folder.name)) folder.parentFile else folder
        if (t.album.isBlank()) t.album = albumDir?.name?.replace(Regex("\\s*\\(\\d{4}\\)$"), "") ?: ""
        if (t.artist.isBlank() && t.albumArtist.isBlank()) t.artist = albumDir?.parentFile?.name ?: ""
        if (t.year == 0) albumDir?.name?.let { n -> Regex("\\((\\d{4})\\)").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { t.year = it } }
    }
}
