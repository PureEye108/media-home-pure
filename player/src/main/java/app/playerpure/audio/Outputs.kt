package app.playerpure.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SrcFormat(val rate: Int, val channels: Int, val bits: Int, val dsd: Boolean)

enum class Mode { PCM, DOP, NATIVE_DSD, DSD_TO_PCM }

/** How DSD files go to a USB DAC: converted to PCM (works everywhere), native DSD (raw), or DoP. */
enum class DsdOut { PCM, NATIVE, DOP }

/** DSD frames per output frame in each mode (DoP packs 2, native DSD packs 4). */
val Mode.dsdGroup: Int get() = when (this) { Mode.DOP -> 2; Mode.NATIVE_DSD -> 4; else -> 1 }

/**
 * What an output was set up for. [unitRate]: frames handed to write() per second (DSD frames for DoP).
 * [pcmRate]: the rate DSD is converted to when mode is DSD_TO_PCM.
 */
data class OutFormat(
    val rate: Int, val channels: Int, val bits: Int, val mode: Mode,
    val bitPerfect: Boolean, val upmixMono: Boolean, val unitRate: Int, val pcmRate: Int, val label: String,
)

/** Where decoded audio goes. PCM write units are int32 MSB-aligned frames; in DoP and native DSD modes, DSD frames. */
interface AudioOut {
    val name: String
    val format: OutFormat?
    /** Prepares for a stream; may restart the hardware. Returns null if it can't play this format. */
    fun configure(src: SrcFormat): OutFormat?
    /** Would [src] play with the current set-up unchanged (so tracks join without a gap)? */
    fun sameAs(src: SrcFormat): Boolean
    fun write(buf: ByteBuffer, offsetBytes: Int, frames: Int): Int
    fun played(): Long
    fun queued(): Long
    fun flush()
    fun interrupt()
    fun setPaused(p: Boolean)
    fun setActive(a: Boolean)
    fun close()
    val failed: String?
}

/** Straight to a USB DAC through Player Pure's own driver: bit-perfect when no volume is applied. */
class UsbOut(
    val link: UsbLink,
    private val dsdOut: () -> DsdOut,
    private val dsdSwap: () -> Boolean,
    private val onFail: (String) -> Unit,
) : AudioOut {
    override val name: String get() = link.name
    override var format: OutFormat? = null; private set
    @Volatile override var failed: String? = null; private set
    @Volatile var gainQ16 = 65536
    private var h = 0L
    private var thread: Thread? = null
    private var playedBase = 0L
    private var lastChoice: UsbDac.Choice? = null
    private var lastDsd = 0

    private fun dsdCode(mode: Mode): Int = when (mode) {
        Mode.DOP -> 1
        Mode.NATIVE_DSD -> 2 + (if (dsdSwap()) 4 else 0)
        else -> 0
    }

    private fun plan(src: SrcFormat): Triple<UsbDac.Choice, Mode, Int>? {
        val rf = { a: UsbDac.Alt -> link.ratesFor(a) }
        if (src.dsd) {
            val want = dsdOut()
            if (want == DsdOut.NATIVE) UsbDac.chooseNativeDsd(link.dac, src.rate, src.channels, rf)?.let {
                return Triple(it, Mode.NATIVE_DSD, it.rate)
            }
            val dopRate = src.rate / 16
            if (want == DsdOut.DOP) UsbDac.choose(link.dac, dopRate, src.channels, 24, rf)?.takeIf { it.alt.bits >= 24 }?.let {
                return Triple(it, Mode.DOP, dopRate)
            }
            for (r in listOf(176400, 88200, 352800)) {
                if (src.rate % (r * 8) != 0) continue
                UsbDac.choose(link.dac, r, src.channels, 24, rf)?.let { return Triple(it, Mode.DSD_TO_PCM, r) }
            }
            return null
        }
        return UsbDac.choose(link.dac, src.rate, src.channels, src.bits, rf)?.let { Triple(it, Mode.PCM, src.rate) }
    }

    override fun sameAs(src: SrcFormat): Boolean {
        val p = plan(src) ?: return false
        val f = format ?: return false
        return h != 0L && p.first == lastChoice && dsdCode(p.second) == lastDsd && p.second == f.mode && p.third == f.rate
    }

    override fun configure(src: SrcFormat): OutFormat? {
        val p = plan(src) ?: return null
        val (choice, mode, rate) = p
        val code = dsdCode(mode)
        // DSD sent as DoP or native is never scaled (digital volume only acts on PCM)
        val bitPerfect = when (mode) { Mode.PCM -> choice.bitPerfect && gainQ16 == 65536; Mode.DOP, Mode.NATIVE_DSD -> true; Mode.DSD_TO_PCM -> false }
        if (h != 0L && choice == lastChoice && code == lastDsd && format?.mode == mode && format?.rate == rate) {
            format = format!!.copy(bitPerfect = bitPerfect)
            return format
        }
        stopEngine()
        val cfg = link.start(choice, code)
        h = Native.usbCreate(link.fd, cfg)
        playedBase = 0L
        lastChoice = choice; lastDsd = code
        val a = choice.alt
        val label = when (mode) {
            Mode.DOP -> "DSD${src.rate / 44100} as DoP (${fmtRate(rate)})"
            Mode.NATIVE_DSD -> "DSD${src.rate / 44100} native"
            Mode.DSD_TO_PCM -> "${a.bits}-bit / ${fmtRate(rate)} (from DSD)"
            Mode.PCM -> "${a.bits}-bit / ${fmtRate(rate)}"
        }
        format = OutFormat(rate, a.channels, a.bits, mode, bitPerfect, choice.upmixMono,
            if (mode == Mode.DOP || mode == Mode.NATIVE_DSD) src.rate / 8 else rate, if (mode == Mode.DSD_TO_PCM) rate else 0, label)
        val handle = h
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rc = Native.usbRun(handle)
            if (rc < 0) {
                failed = if (rc == -19) "The DAC was disconnected" else "USB streaming stopped (error ${-rc})"
                onFail(failed!!)
            }
        }, "pp-usb").apply { start() }
        return format
    }

    override fun write(buf: ByteBuffer, offsetBytes: Int, frames: Int): Int =
        if (h == 0L || failed != null) -1 else Native.usbWrite(h, buf, offsetBytes, frames, gainQ16)

    override fun played(): Long = if (h == 0L) playedBase else Native.usbStats(h)[0]
    override fun queued(): Long = if (h == 0L) 0 else Native.usbStats(h)[6]
    fun stats(): LongArray? = if (h == 0L) null else Native.usbStats(h)
    override fun flush() { if (h != 0L) Native.usbFlush(h) }
    override fun interrupt() { if (h != 0L) Native.usbInterrupt(h) }
    override fun setPaused(p: Boolean) { if (h != 0L) Native.usbSetPaused(h, p) }
    override fun setActive(a: Boolean) { if (h != 0L) Native.usbSetActive(h, a) }

    private fun stopEngine() {
        if (h == 0L) return
        Native.usbStop(h)
        thread?.join(2000)
        thread = null
        Native.usbDestroy(h)
        h = 0L
        try { link.stopStream() } catch (_: Exception) {}
        format = null; lastChoice = null
    }

    override fun close() {
        stopEngine()
        try { link.close() } catch (_: Exception) {}
    }

    companion object {
        fun fmtRate(r: Int): String = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000.0)
    }
}

/**
 * Android's normal audio path (tablet speakers, headphones, HDMI, Bluetooth). Android resamples to its mixer
 * rate here, so it is never bit-perfect; used when no USB DAC is connected or allowed.
 */
class TrackOut : AudioOut {
    override val name = "Tablet audio (speakers, HDMI, Bluetooth)"
    override var format: OutFormat? = null; private set
    override val failed: String? = null
    private var track: AudioTrack? = null
    private var fbuf = FloatArray(0)
    private var playedBase = 0L
    private var written = 0L
    private var lastHead = 0L
    private var wraps = 0L
    @Volatile private var interrupted = false

    override fun sameAs(src: SrcFormat): Boolean {
        val f = format ?: return false
        val rate = if (src.dsd) 176400 else src.rate
        return track != null && f.rate == rate && f.channels == src.channels.coerceAtMost(2) && (f.mode == Mode.DSD_TO_PCM) == src.dsd
    }

    override fun configure(src: SrcFormat): OutFormat? {
        if (sameAs(src)) return format
        release()
        if (src.channels > 2) return null
        val rate = if (src.dsd) 176400 else src.rate
        val ch = src.channels.coerceIn(1, 2)
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val t = try {
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min * 2, rate * ch * 4 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { return null }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return null }
        t.play()
        track = t; playedBase = 0; written = 0; lastHead = 0; wraps = 0
        format = OutFormat(rate, ch, 24, if (src.dsd) Mode.DSD_TO_PCM else Mode.PCM, false, false, rate,
            if (src.dsd) rate else 0, "Android mixer (resampled)")
        return format
    }

    override fun write(buf: ByteBuffer, offsetBytes: Int, frames: Int): Int {
        val t = track ?: return -1
        val ch = format!!.channels
        val ib = buf.duplicate().order(ByteOrder.nativeOrder()).apply { position(offsetBytes) }.slice().order(ByteOrder.nativeOrder()).asIntBuffer()
        val n = frames * ch
        if (fbuf.size < n) fbuf = FloatArray(n)
        for (i in 0 until n) fbuf[i] = ib.get(i) / 2147483648f
        var done = 0
        interrupted = false
        while (done < n && !interrupted) {
            val chunk = minOf(n - done, ch * 2048)
            val r = t.write(fbuf, done, chunk, AudioTrack.WRITE_BLOCKING)
            if (r <= 0) break
            done += r
        }
        written += done / ch
        return done / ch
    }

    override fun played(): Long {
        val t = track ?: return playedBase
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head < lastHead) wraps++
        lastHead = head
        return playedBase + head + (wraps shl 32)
    }

    override fun queued(): Long = (written - played()).coerceAtLeast(0)

    override fun flush() {
        val t = track ?: return
        t.pause(); t.flush()
        playedBase = written; lastHead = 0; wraps = 0
        t.play()
    }

    override fun interrupt() { interrupted = true }
    override fun setPaused(p: Boolean) { track?.let { if (p) it.pause() else it.play() } }
    override fun setActive(a: Boolean) {}

    private fun release() {
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null; format = null
    }

    override fun close() = release()
}
