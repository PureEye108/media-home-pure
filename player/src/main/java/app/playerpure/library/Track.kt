package app.playerpure.library

/** One playable track. CUE tracks share a file and carry start/end positions in CD frames (1/75 s). */
data class Track(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val durationMs: Long = 0,
    val rate: Int = 0,
    val bits: Int = 0,
    val channels: Int = 2,
    val codec: String = "",
    val cueStart: Long = -1,
    val cueEnd: Long = -1,
    val albumKey: String = "",
    val art: String? = null,
) {
    val folder: String get() = path.substringBeforeLast('/')
    val displayArtist: String get() = artist.ifBlank { albumArtist }.ifBlank { "Unknown artist" }
    val isDsd: Boolean get() = codec == "DSF" || codec == "DFF" || codec == "DSD"

    /** "FLAC 24/96", "DSD64", "MP3". */
    val formatLabel: String get() = formatLabel(codec, rate, bits)

    companion object {
        fun formatLabel(codec: String, rate: Int, bits: Int): String = when {
            codec == "DSF" || codec == "DFF" || codec == "DSD" -> "DSD" + (rate / 44100)
            rate <= 0 -> codec
            codec in LOSSY -> codec
            else -> "$codec $bits/" + (if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(rate / 1000.0))
        }
        val LOSSY = setOf("MP3", "AAC", "Opus", "Vorbis", "OGG")
    }
}
