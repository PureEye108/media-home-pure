package app.mediahome.tablet.remote

/** Result of a mouse or keyboard command from the web remote. */
enum class InputResult { OK, NO_SERVICE, NO_FIELD, FAILED }

/** Pure helpers for the phone touchpad and keyboard (kept free of Android types so they can be unit-tested). */
object InputLogic {
    /** Largest single pointer step accepted from the phone, in tablet pixels. */
    const val MAX_STEP = 4000

    /** New cursor position after moving by (dx, dy), kept on screen. */
    fun moveCursor(x: Int, y: Int, dx: Int, dy: Int, width: Int, height: Int): Pair<Int, Int> {
        val sx = dx.coerceIn(-MAX_STEP, MAX_STEP)
        val sy = dy.coerceIn(-MAX_STEP, MAX_STEP)
        return (x + sx).coerceIn(0, maxOf(0, width - 1)) to (y + sy).coerceIn(0, maxOf(0, height - 1))
    }

    /**
     * A scroll swipe from the cursor in the direction the fingers moved (like touching the screen), clamped to
     * the screen and at least [minLen] long so Android treats it as a scroll.
     */
    fun scrollPath(x: Int, y: Int, dx: Int, dy: Int, width: Int, height: Int, minLen: Int = 40): IntArray {
        var ex = dx
        var ey = dy
        val len = Math.hypot(ex.toDouble(), ey.toDouble())
        if (len in 0.5..minLen.toDouble()) {
            ex = (ex * minLen / len).toInt(); ey = (ey * minLen / len).toInt()
        }
        val sx = x.coerceIn(1, maxOf(1, width - 2))
        val sy = y.coerceIn(1, maxOf(1, height - 2))
        return intArrayOf(sx, sy, (sx + ex).coerceIn(1, maxOf(1, width - 2)), (sy + ey).coerceIn(1, maxOf(1, height - 2)))
    }

    /** Text of a field after a keyboard command. [current] is null when the field shows only its hint. */
    fun edit(current: String?, action: String, text: String): String? {
        val now = current ?: ""
        return when (action) {
            "set" -> text
            "type" -> now + text
            "clear" -> ""
            "backspace" -> if (now.isEmpty()) now else now.substring(0, now.offsetByCodePoints(now.length, -1))
            else -> null
        }
    }

    private val FULLSCREEN = Regex("(?i)^(enter )?full ?screen\\b")
    private val PLAY = Regex("(?i)^play( \\(k\\)| video)?$")

    /** YouTube's own Full screen button ("Full screen (f)", "Enter full screen", "Fullscreen"), never "Exit full screen". */
    fun isFullscreenLabel(label: String): Boolean = FULLSCREEN.containsMatchIn(label.trim())

    /** YouTube's Play button ("Play (k)", "Play"), not "Playlist", "Play all" or "Pause". */
    fun isPlayLabel(label: String): Boolean = PLAY.matches(label.trim())

    /**
     * Decides what the "Link or search" box means: a link (opened in Brave) or words to search for.
     * Returns the link to open, or null to search instead.
     */
    fun linkOrNull(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (Regex("(?i)^(https?|rtsp|rtmp|smb)://\\S+$").matches(t)) return t
        // bare domain such as "example.com" or "news.ycombinator.com/item?id=1"
        if (Regex("(?i)^([a-z0-9-]+\\.)+[a-z]{2,24}(:\\d{1,5})?([/?#]\\S*)?$").matches(t)) return "https://$t"
        return null
    }
}
