package app.playerpure.audio

import java.nio.ByteBuffer

/** The native part (libplayerpure.so): lossless decoders, DSD conversion and the USB audio engine. */
object Native {
    @Volatile var loaded = false
        private set

    fun load(): Boolean {
        if (loaded) return true
        loaded = try { System.loadLibrary("playerpure"); true } catch (e: Throwable) { false }
        return loaded
    }

    // decoders: FLAC, WAV, AIFF, ALAC, DSF, DFF
    @JvmStatic external fun decOpen(fd: Int): Long
    @JvmStatic external fun decLastError(): String
    /** rate, channels, bits, dsd (0/1), totalFrames, codec, float (0/1) */
    @JvmStatic external fun decInfo(h: Long): LongArray
    @JvmStatic external fun decRead(h: Long, buf: ByteBuffer, maxFrames: Int): Int
    @JvmStatic external fun decSeek(h: Long, frame: Long): Boolean
    @JvmStatic external fun decClose(h: Long)

    // DSD -> PCM
    @JvmStatic external fun d2pOpen(dsdRate: Int, channels: Int, outRate: Int): Long
    @JvmStatic external fun d2pProcess(h: Long, src: ByteBuffer, frames: Int, dst: ByteBuffer): Int
    @JvmStatic external fun d2pReset(h: Long)
    @JvmStatic external fun d2pClose(h: Long)

    // USB audio engine
    /** cfg: dataEp, dataMaxPacket, dataInterval, fbEp, fbMaxPacket, highSpeed, rate, channels, subslot, bits, dop, urbs, urbMicros */
    @JvmStatic external fun usbCreate(fd: Int, cfg: IntArray): Long
    @JvmStatic external fun usbRun(h: Long): Int
    @JvmStatic external fun usbWrite(h: Long, buf: ByteBuffer, offsetBytes: Int, frames: Int, gainQ16: Int): Int
    @JvmStatic external fun usbFlush(h: Long)
    @JvmStatic external fun usbInterrupt(h: Long)
    @JvmStatic external fun usbStop(h: Long)
    @JvmStatic external fun usbSetActive(h: Long, active: Boolean)
    @JvmStatic external fun usbSetPaused(h: Long, paused: Boolean)
    /** played, underruns, packets, feedbacks, badFeedback, lastFeedback, queued, error */
    @JvmStatic external fun usbStats(h: Long): LongArray
    @JvmStatic external fun usbDestroy(h: Long)
    @JvmStatic external fun usbReattach(fd: Int, iface: Int): Int
    /** 1 low, 2 full, 3 high, 5 super speed, or a negative error */
    @JvmStatic external fun usbSpeed(fd: Int): Int

    const val CODEC_FLAC = 1
    const val CODEC_WAV = 2
    const val CODEC_AIFF = 3
    const val CODEC_ALAC = 4
    const val CODEC_DSF = 5
    const val CODEC_DFF = 6

    fun codecName(c: Int): String = when (c) {
        CODEC_FLAC -> "FLAC"; CODEC_WAV -> "WAV"; CODEC_AIFF -> "AIFF"; CODEC_ALAC -> "ALAC"
        CODEC_DSF -> "DSF"; CODEC_DFF -> "DFF"; else -> "PCM"
    }
}
