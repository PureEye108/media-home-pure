package app.mediahome.tablet.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Is a remote connected?" decides whether the home screen shows the QR code or the Browser tile. */
class ClientPresenceTest {
    @Test fun nobodyAtFirst() {
        val p = ClientPresence()
        assertFalse(p.connected(0)); assertFalse(p.connected(1_000_000)); assertNull(p.lastAddress)
    }

    @Test fun connectedWhileThePageKeepsAsking() {
        val p = ClientPresence(15_000)
        p.seen("192.168.1.20", 100_000)
        assertTrue(p.connected(100_000)); assertTrue(p.connected(114_999))
        assertFalse(p.connected(115_000))                    // about 10 missed status requests: the QR code comes back
        p.seen("192.168.1.20", 116_000)
        assertTrue(p.connected(120_000))
        assertEquals("192.168.1.20", p.lastAddress)
    }

    @Test fun serverStopForgetsEveryone() {
        val p = ClientPresence()
        p.seen("10.0.0.5", 5_000)
        p.reset()
        assertFalse(p.connected(5_001)); assertNull(p.lastAddress)
    }

    @Test fun clockBeforeTheLastRequestIsNotConnected() {
        val p = ClientPresence()
        p.seen("10.0.0.5", 50_000)
        assertFalse(p.connected(49_000))
    }

    @Test fun firstRequestRightAtZeroCounts() {
        val p = ClientPresence(15_000)
        p.seen("10.0.0.5", 0)
        assertTrue(p.connected(0)); assertTrue(p.connected(14_999))
    }
}
