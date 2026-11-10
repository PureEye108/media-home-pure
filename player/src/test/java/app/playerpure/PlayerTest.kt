package app.playerpure

import android.os.Looper
import app.playerpure.audio.AudioOut
import app.playerpure.audio.Mode
import app.playerpure.audio.OutFormat
import app.playerpure.audio.Player
import app.playerpure.audio.SrcFormat
import app.playerpure.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** An output that "plays" instantly and records what it was given. */
class FakeOut : AudioOut {
    override val name = "fake"
    override var format: OutFormat? = null
    override val failed: String? = null
    val configs = ArrayList<Int>()
    @Volatile var frames = 0L
    @Volatile var nonZero = 0L
    override fun configure(src: SrcFormat): OutFormat? {
        configs += src.rate
        format = OutFormat(src.rate, src.channels, src.bits, Mode.PCM, true, false, src.rate, 0, "fake ${src.rate}")
        return format
    }
    override fun sameAs(src: SrcFormat) = format?.rate == src.rate
    override fun write(buf: ByteBuffer, offsetBytes: Int, frames: Int): Int {
        for (i in 0 until frames) if (buf.getInt(offsetBytes + i * 8) != 0) { nonZero++; break }
        this.frames += frames; return frames
    }
    override fun played() = frames
    override fun queued() = 0L
    override fun flush() {}
    override fun interrupt() {}
    override fun setPaused(p: Boolean) {}
    override fun setActive(a: Boolean) {}
    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerTest {
    private fun tone(id: Long, rate: Int) = Track(id, "tone:$rate:24", "Tone $rate", "Test", "Tones", "Test", durationMs = 10_000, rate = rate, bits = 24, codec = "PCM")

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun gaplessSameRateAndSwitchOnRateChange() {
        val player = Player { false }
        val out = FakeOut()
        player.setOutput(out)
        val changes = ArrayList<Long>()
        player.listeners += Player.Listener { player.current()?.id?.let { if (changes.lastOrNull() != it) changes += it } }
        player.play(listOf(tone(1, 44100), tone(2, 44100), tone(3, 96000)))
        waitFor { out.frames >= 441_000L * 2 + 960_000 && !player.playing }
        assertEquals(441_000L * 2 + 960_000, out.frames)
        assertEquals(listOf(44100, 96000), out.configs)     // tracks 1 and 2 joined without re-opening the output
        assertFalse(player.playing)
        assertTrue(out.nonZero > 0)
        assertEquals(listOf(1L, 2L, 3L), changes)
        player.quit()
    }

    @Test fun queueEditing() {
        val player = Player { false }
        player.setOutput(FakeOut())
        player.restore(listOf(tone(1, 44100), tone(2, 44100)), 0, 0)
        waitFor { player.queue().size == 2 }
        player.enqueue(listOf(tone(3, 48000)), next = true)
        waitFor { player.queue().size == 3 }
        assertEquals(listOf(1L, 3L, 2L), player.queue().map { it.track.id })
        player.remove(1)
        waitFor { player.queue().size == 2 }
        player.move(1, 0)
        waitFor { player.queue().first().track.id == 2L }
        assertEquals(listOf(2L, 1L), player.queue().map { it.track.id })
        player.setRepeat(1); player.setShuffle(true)
        waitFor { player.shuffle }
        assertEquals(1, player.repeatMode)
        player.clear()
        waitFor { player.queue().isEmpty() }
        player.quit()
    }
}
