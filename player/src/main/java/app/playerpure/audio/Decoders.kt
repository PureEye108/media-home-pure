package app.playerpure.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoded stream. PCM is written as interleaved int32, MSB-aligned (so 16- and 24-bit samples pass through
 * untouched); DSD as one byte per channel per frame (frame = 8 DSD samples, oldest bit in the MSB).
 */
interface Dec : Closeable {
    val rate: Int
    val channels: Int
    val bits: Int
    val dsd: Boolean
    val totalFrames: Long
    val codec: String
    val lossless: Boolean
    val frameBytes: Int get() = if (dsd) channels else channels * 4
    /** Reads up to maxFrames into buf (from position 0). Returns frames, 0 at the end, < 0 on error. */
    fun read(buf: ByteBuffer, maxFrames: Int): Int
    fun seek(frame: Long): Boolean
}

fun directBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

object Decoders {
    /** Lossless formats go to the native decoders (bit-exact); everything else to Android's own decoders. */
    fun open(file: File): Dec {
        if (file.path.startsWith("tone:dsd:")) return DsdToneDec(file.path.substringAfterLast(':').toIntOrNull() ?: 2822400)
        if (file.path.startsWith("tone:")) {
            val p = file.path.split(':')
            return ToneDec(p.getOrNull(1)?.toIntOrNull() ?: 44100, p.getOrNull(2)?.toIntOrNull() ?: 24)
        }
        if (Native.load()) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val h = try { Native.decOpen(pfd.fd) } finally { pfd.close() }
            if (h != 0L) return NativeDec(h)
            val err = Native.decLastError()
            if (err != "not-lossless") throw IOException(err)
        }
        return CodecDec(file.path)
    }
}

class NativeDec(private var h: Long) : Dec {
    private val info = Native.decInfo(h)
    override val rate = info[0].toInt()
    override val channels = info[1].toInt()
    override val bits = info[2].toInt()
    override val dsd = info[3] != 0L
    override val totalFrames = info[4]
    override val codec = Native.codecName(info[5].toInt())
    override val lossless = info[6] == 0L
    override fun read(buf: ByteBuffer, maxFrames: Int): Int = if (h == 0L) -1 else Native.decRead(h, buf, maxFrames)
    override fun seek(frame: Long): Boolean = h != 0L && Native.decSeek(h, frame)
    override fun close() { if (h != 0L) { Native.decClose(h); h = 0L } }
}

/** MP3, AAC, Opus, Vorbis and anything else Android can decode, converted to the same int32 layout. */
class CodecDec(path: String) : Dec {
    private val ex = MediaExtractor()
    private val codecObj: MediaCodec
    private var inEos = false
    private var outEos = false
    private val info = MediaCodec.BufferInfo()
    private var pending: IntArray = IntArray(0)
    private var pendingPos = 0
    private var pendingLen = 0          // in samples
    private var skipUntilUs = -1L
    private var floatOut = false
    override var rate = 0; private set
    override var channels = 0; private set
    override val bits: Int get() = if (floatOut) 24 else 16
    override val dsd = false
    override val totalFrames: Long
    override val codec: String
    override val lossless: Boolean

    init {
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IOException("no audio in this file")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        codec = when (mime) {
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"; MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"; MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"; MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"; else -> mime.substringAfter('/').uppercase()
        }
        lossless = mime == MediaFormat.MIMETYPE_AUDIO_FLAC || mime == MediaFormat.MIMETYPE_AUDIO_RAW
        rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
        codecObj = MediaCodec.createDecoderByType(mime)
        val wantFloat = MediaFormat(fmt).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
        try { codecObj.configure(wantFloat, null, null, 0) } catch (e: Exception) { codecObj.reset(); codecObj.configure(fmt, null, null, 0) }
        codecObj.start()
        pump(true)   // learn the real output format (HE-AAC can double the rate)
        totalFrames = if (durUs > 0) durUs * rate / 1_000_000 else -1
    }

    private fun applyFormat(f: MediaFormat) {
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        floatOut = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }

    /** Feeds and drains the codec until some PCM is ready (or the end). */
    private fun pump(untilFormat: Boolean = false): Boolean {
        var guard = 0
        while (pendingPos >= pendingLen && !outEos && guard++ < 10_000) {
            if (!inEos) {
                val ii = codecObj.dequeueInputBuffer(2_000)
                if (ii >= 0) {
                    val b = codecObj.getInputBuffer(ii)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) { codecObj.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                    else { codecObj.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codecObj.dequeueOutputBuffer(info, 2_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { applyFormat(codecObj.outputFormat); if (untilFormat) return true; continue }
            if (oi < 0) continue
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
            val ob = codecObj.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
            ob.position(info.offset); ob.limit(info.offset + info.size)
            val samples = if (floatOut) info.size / 4 else info.size / 2
            if (pending.size < samples) pending = IntArray(samples)
            if (floatOut) {
                val fb = ob.asFloatBuffer()
                for (i in 0 until samples) {
                    val v = fb.get(i).coerceIn(-1f, 1f)
                    pending[i] = if (v >= 1f) Int.MAX_VALUE else (v * 2147483648.0).toInt()
                }
            } else {
                val sb = ob.asShortBuffer()
                for (i in 0 until samples) pending[i] = sb.get(i).toInt() shl 16
            }
            pendingPos = 0; pendingLen = samples
            if (skipUntilUs >= 0 && channels > 0) {   // after a seek: drop audio before the target
                val frames = samples / channels
                val drop = ((skipUntilUs - info.presentationTimeUs) * rate / 1_000_000).coerceIn(0, frames.toLong()).toInt()
                pendingPos = drop * channels
                skipUntilUs = -1
            }
            codecObj.releaseOutputBuffer(oi, false)
            if (untilFormat) return true
        }
        return pendingPos < pendingLen
    }

    override fun read(buf: ByteBuffer, maxFrames: Int): Int {
        if (channels <= 0) return -1
        val ib = buf.order(ByteOrder.nativeOrder()).asIntBuffer()
        var done = 0
        val cap = minOf(maxFrames, buf.capacity() / (channels * 4))
        while (done < cap) {
            if (pendingPos >= pendingLen && !pump()) break
            val take = minOf((pendingLen - pendingPos) / channels, cap - done)
            if (take <= 0) break
            ib.position(done * channels)
            ib.put(pending, pendingPos, take * channels)
            pendingPos += take * channels; done += take
        }
        return done
    }

    override fun seek(frame: Long): Boolean {
        val us = frame * 1_000_000 / rate.coerceAtLeast(1)
        ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codecObj.flush()
        inEos = false; outEos = false; pendingPos = 0; pendingLen = 0
        skipUntilUs = us
        return true
    }

    override fun close() {
        try { codecObj.stop() } catch (_: Exception) {}
        codecObj.release(); ex.release()
    }
}

/** DAC test signal: 1 kHz sine at -20 dBFS, stereo, 10 seconds, with TPDF dither at [bits]. */
class ToneDec(override val rate: Int, override val bits: Int) : Dec {
    override val channels = 2
    override val dsd = false
    override val totalFrames = rate * 10L
    override val codec = "PCM"
    override val lossless = true
    private var pos = 0L
    private val rnd = java.util.Random(1)
    override fun read(buf: ByteBuffer, maxFrames: Int): Int {
        val n = minOf(maxFrames.toLong(), totalFrames - pos, (buf.capacity() / 8).toLong()).toInt()
        val amp = 0.1 * ((1L shl (bits - 1)) - 1)
        val shift = 32 - bits
        for (i in 0 until n) {
            val s = amp * Math.sin(2 * Math.PI * 1000.0 * (pos + i) / rate) + rnd.nextDouble() - rnd.nextDouble()
            val v = Math.round(s).toInt() shl shift
            buf.putInt(i * 8, v); buf.putInt(i * 8 + 4, v)
        }
        pos += n
        return n
    }
    override fun seek(frame: Long): Boolean { pos = frame.coerceIn(0, totalFrames); return true }
    override fun close() {}
}

/**
 * DSD test signal: 1 kHz sine, -20 dB below the SACD reference level, stereo, 10 seconds, made by a
 * second-order sigma-delta modulator (the same kind of bitstream a DSD file holds).
 */
class DsdToneDec(override val rate: Int) : Dec {
    override val channels = 2
    override val bits = 1
    override val dsd = true
    override val totalFrames = rate / 8L * 10
    override val codec = "DSD"
    override val lossless = true
    private var pos = 0L
    private var s = 0.0; private var c = 1.0                   // sine and cosine, advanced by rotation
    private val cw = Math.cos(2 * Math.PI * 1000.0 / rate); private val sw = Math.sin(2 * Math.PI * 1000.0 / rate)
    private var i1 = 0.0; private var i2 = 0.0; private var y = 1.0
    private val amp = 0.5 * 0.1                                 // SACD 0 dB = 50% modulation; -20 dB

    override fun read(buf: ByteBuffer, maxFrames: Int): Int {
        val n = minOf(maxFrames.toLong(), totalFrames - pos, (buf.capacity() / 2).toLong()).toInt()
        for (f in 0 until n) {
            var b = 0
            for (k in 0 until 8) {
                val x = amp * s
                i1 += x - y; i2 += i1 - y
                y = if (i2 >= 0) 1.0 else -1.0
                b = (b shl 1) or (if (y > 0) 1 else 0)
                val ns = s * cw + c * sw; c = c * cw - s * sw; s = ns
            }
            val r = 1.0 / Math.sqrt(s * s + c * c); s *= r; c *= r   // keep the oscillator on the unit circle
            buf.put(2 * f, b.toByte()); buf.put(2 * f + 1, b.toByte())
        }
        pos += n
        return n
    }

    override fun seek(frame: Long): Boolean {
        pos = frame.coerceIn(0, totalFrames)
        val ph = 2 * Math.PI * 1000.0 * (pos * 8) / rate
        s = Math.sin(ph); c = Math.cos(ph); i1 = 0.0; i2 = 0.0; y = 1.0
        return true
    }

    override fun close() {}
}
