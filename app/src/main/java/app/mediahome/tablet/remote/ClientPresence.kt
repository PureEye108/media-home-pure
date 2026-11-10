package app.mediahome.tablet.remote

/**
 * Whether a phone or PC has the web remote open right now.
 * The remote page asks the tablet for its status every 1.5 s while it is on screen (and stops when the phone
 * locks or the page goes to the background), so "a request with the right PIN in the last [timeoutMs]" means connected.
 * Times come from a monotonic clock (SystemClock.elapsedRealtime on the tablet).
 */
class ClientPresence(private val timeoutMs: Long = TIMEOUT_MS) {
    @Volatile private var lastAt = NEVER
    @Volatile var lastAddress: String? = null; private set

    /** A request with the right PIN arrived from [address]. */
    fun seen(address: String, now: Long) {
        lastAt = now
        lastAddress = address
    }

    fun connected(now: Long): Boolean {
        val t = lastAt
        return t != NEVER && now >= t && now - t < timeoutMs
    }

    /** The server stopped: nobody can be connected. */
    fun reset() {
        lastAt = NEVER
        lastAddress = null
    }

    companion object {
        /** About ten missed status requests. */
        const val TIMEOUT_MS = 15_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
