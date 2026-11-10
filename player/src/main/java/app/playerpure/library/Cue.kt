package app.playerpure.library

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A CUE sheet: one or more audio files split into tracks. Times are in CD frames (1/75 s). */
class CueSheet(val title: String, val performer: String, val genre: String, val year: Int, val files: List<CueFile>)
class CueFile(val name: String, val tracks: List<CueTrack>)
class CueTrack(val number: Int, val title: String, val performer: String, val start: Long, val end: Long)   // end -1 = file end

object Cue {
    /** CUE files are UTF-8 (with or without BOM) or, in older rips, Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun args(line: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            if (line[i] == '"') {
                val e = line.indexOf('"', i + 1).let { if (it < 0) line.length else it }
                out += line.substring(i + 1, e); i = e + 1
            } else {
                val e = (i until line.length).firstOrNull { line[it].isWhitespace() } ?: line.length
                out += line.substring(i, e); i = e
            }
        }
        return out
    }

    /** "mm:ss:ff" -> CD frames. */
    fun time(s: String): Long {
        val p = s.split(':')
        if (p.size != 3) return -1
        val m = p[0].toLongOrNull() ?: return -1; val sec = p[1].toLongOrNull() ?: return -1; val f = p[2].toLongOrNull() ?: return -1
        return (m * 60 + sec) * 75 + f
    }

    fun parse(text: String): CueSheet? {
        var title = ""; var performer = ""; var genre = ""; var year = 0
        class RawTrack(val n: Int) { var title = ""; var performer = ""; var start = -1L }
        val files = ArrayList<Pair<String, ArrayList<RawTrack>>>()
        var track: RawTrack? = null
        for (raw in text.lineSequence()) {
            val a = args(raw.trim())
            if (a.isEmpty()) continue
            when (a[0].uppercase()) {
                "REM" -> if (a.size >= 3) when (a[1].uppercase()) {
                    "GENRE" -> genre = a.drop(2).joinToString(" ")
                    "DATE" -> year = Regex("\\d{4}").find(a[2])?.value?.toInt() ?: 0
                }
                "TITLE" -> if (a.size >= 2) { if (track != null) track.title = a[1] else title = a[1] }
                "PERFORMER" -> if (a.size >= 2) { if (track != null) track.performer = a[1] else performer = a[1] }
                "FILE" -> if (a.size >= 2) { files += a[1] to ArrayList(); track = null }
                "TRACK" -> if (a.size >= 3 && files.isNotEmpty()) {
                    if (a[2].uppercase() == "AUDIO") { track = RawTrack(a[1].toIntOrNull() ?: 0); files.last().second += track!! } else track = null
                }
                "INDEX" -> if (a.size >= 3 && track != null && a[1].toIntOrNull() == 1) track!!.start = time(a[2])
            }
        }
        val out = files.map { (name, ts) ->
            val valid = ts.filter { it.start >= 0 }
            CueFile(name, valid.mapIndexed { i, t ->
                CueTrack(t.n, t.title, t.performer, t.start, if (i + 1 < valid.size) valid[i + 1].start else -1)
            })
        }.filter { it.tracks.isNotEmpty() }
        return if (out.isEmpty()) null else CueSheet(title, performer, genre, year, out)
    }
}
