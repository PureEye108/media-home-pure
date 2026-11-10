package app.playerpure.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * An open USB DAC. Player Pure takes the DAC's audio interfaces away from Android's own USB audio driver
 * (so nothing resamples or mixes), sets the sample rate and format itself, and gives them back on close.
 */
class UsbLink private constructor(
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    val dac: UsbDac.Device,
    val highSpeed: Boolean,
    val speedText: String,
) {
    val name: String = listOfNotNull(device.manufacturerName?.trim(), device.productName?.trim())
        .distinct().joinToString(" ").ifBlank { "USB DAC" }
    val fd: Int get() = conn.fileDescriptor
    val log = StringBuilder()
    private val claimed = ArrayList<UsbInterface>()
    private val sourceRates = HashMap<Int, List<Int>>()     // UAC2 clock source id -> rates
    private var activeIface = -1

    /** One way to time an alternate setting: an optional clock selector pin, then a clock source. */
    private data class ClockPath(val selector: Int, val pin: Int, val source: Int)

    private fun clockPaths(a: UsbDac.Alt): List<ClockPath> {
        val start = dac.terminalClock[a.terminalLink] ?: return dac.clocks.values.filter { it.kind == 0x0A }.map { ClockPath(-1, 0, it.id) }
        val out = ArrayList<ClockPath>()
        fun walk(id: Int, sel: Int, pin: Int, depth: Int) {
            if (depth > 6) return
            val c = dac.clocks[id] ?: return
            when (c.kind) {
                0x0A -> out += ClockPath(sel, pin, id)
                0x0C -> c.sources.firstOrNull()?.let { walk(it, sel, pin, depth + 1) }
                0x0B -> c.sources.forEachIndexed { k, s -> walk(s, if (sel < 0) id else sel, if (sel < 0) k + 1 else pin, depth + 1) }
            }
        }
        walk(start, -1, 0, 0)
        return out
    }

    /** Rates an alternate setting can run at (UAC2: from the DAC's clocks; UAC1: null = read from the descriptors). */
    fun ratesFor(a: UsbDac.Alt): List<Int>? =
        if (!dac.uac2) null else clockPaths(a).flatMap { sourceRates[it.source] ?: emptyList() }.distinct().sorted()

    fun allRates(): List<Int> = UsbDac.allRates(dac) { ratesFor(it) }

    private fun iface(id: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == id && it.alternateSetting == alt }

    private fun ctrl(type: Int, req: Int, value: Int, index: Int, data: ByteArray?, len: Int): Int =
        conn.controlTransfer(type, req, value, index, data, len, 1000)

    private fun queryClockRates() {
        val sources = dac.clocks.values.filter { it.kind == 0x0A }.map { it.id }
        for (id in sources) {
            val head = ByteArray(2)
            val n = ctrl(0xA1, 0x02, 0x0100, (id shl 8) or dac.acIface, head, 2)
            val count = if (n >= 2) (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8) else 0
            var rates = emptyList<Int>()
            if (count in 1..64) {
                val full = ByteArray(2 + count * 12)
                val m = ctrl(0xA1, 0x02, 0x0100, (id shl 8) or dac.acIface, full, full.size)
                rates = UsbDac.ratesFromRange(full, m)
            }
            if (rates.isEmpty()) {   // some DACs answer RANGE badly: fall back to the current rate
                val cur = ByteArray(4)
                if (ctrl(0xA1, 0x01, 0x0100, (id shl 8) or dac.acIface, cur, 4) == 4) rates = listOf(le32(cur))
            }
            sourceRates[id] = rates
            log.append("clock #$id rates: ${rates.joinToString(" ")}\n")
        }
    }

    /**
     * Switches the DAC to [c]. Returns the engine settings (see Native.usbCreate).
     * UAC2: rate first, then the alternate setting. UAC1: alternate setting first, then the endpoint's rate.
     */
    /** [dsd]: 0 PCM, 1 DoP, 2 native DSD; add 4 to send native DSD newest byte first. */
    fun start(c: UsbDac.Choice, dsd: Int): IntArray {
        val a = c.alt
        stopStream()
        val zero = iface(a.iface, 0) ?: throw IOException("interface ${a.iface} has no idle setting")
        val active = iface(a.iface, a.alt) ?: throw IOException("interface ${a.iface} alt ${a.alt} missing")
        conn.setInterface(zero)
        if (dac.uac2) {
            val path = clockPaths(a).firstOrNull { c.rate in (sourceRates[it.source] ?: emptyList()) } ?: clockPaths(a).firstOrNull()
            if (path != null) {
                if (path.selector >= 0) {
                    val r = ctrl(0x21, 0x01, 0x0100, (path.selector shl 8) or dac.acIface, byteArrayOf(path.pin.toByte()), 1)
                    log.append("clock selector #${path.selector} -> pin ${path.pin}: $r\n")
                }
                val r = ctrl(0x21, 0x01, 0x0100, (path.source shl 8) or dac.acIface, le32(c.rate), 4)
                val cur = ByteArray(4)
                val g = ctrl(0xA1, 0x01, 0x0100, (path.source shl 8) or dac.acIface, cur, 4)
                log.append("set rate ${c.rate} on clock #${path.source}: $r, DAC reports ${if (g == 4) le32(cur) else -1}\n")
            }
            if (!conn.setInterface(active)) throw IOException("the DAC refused interface ${a.iface} alt ${a.alt}")
        } else {
            if (!conn.setInterface(active)) throw IOException("the DAC refused interface ${a.iface} alt ${a.alt}")
            val b = byteArrayOf(c.rate.toByte(), (c.rate shr 8).toByte(), (c.rate shr 16).toByte())
            val r = ctrl(0x22, 0x01, 0x0100, a.dataEp, b, 3)
            val cur = ByteArray(3)
            val g = ctrl(0xA2, 0x81, 0x0100, a.dataEp, cur, 3)
            log.append("set rate ${c.rate} on endpoint 0x%02x: $r, DAC reports ${if (g == 3) (cur[0].toInt() and 0xFF) or ((cur[1].toInt() and 0xFF) shl 8) or ((cur[2].toInt() and 0xFF) shl 16) else -1}\n".format(a.dataEp))
        }
        activeIface = a.iface
        val interval = if (highSpeed) (a.dataBInterval - 1).coerceIn(0, 3) else 0
        log.append("streaming: interface ${a.iface} alt ${a.alt}, ${c.rate} Hz, ${a.bits}-bit in ${a.subslot} bytes, ${a.channels} ch${when (dsd and 3) { 1 -> ", DoP"; 2 -> ", native DSD" + (if ((dsd and 4) != 0) " (swapped)" else ""); else -> "" }}\n")
        return intArrayOf(a.dataEp, a.dataMaxPacket, interval, a.fbEp, a.fbMaxPacket, if (highSpeed) 1 else 0,
            c.rate, a.channels, a.subslot, a.bits, dsd, 12, 4000)
    }

    /** Puts the streaming interface back to its idle setting (no bandwidth reserved). */
    fun stopStream() {
        if (activeIface >= 0) { iface(activeIface, 0)?.let { conn.setInterface(it) }; activeIface = -1 }
    }

    // ---- DAC volume (feature unit), in 1/256 dB
    private var volMin = 0; private var volMax = 0; private var volChannels = emptyList<Int>()
    val hasVolume: Boolean get() = volChannels.isNotEmpty() && volMax > volMin

    private fun initVolume() {
        val f = dac.volumeUnit ?: return
        val chans = if (f.volume.getOrElse(0) { false }) listOf(0) else listOf(1, 2)
        val ch = chans[0]
        if (dac.uac2) {
            val b = ByteArray(2 + 6)
            if (ctrl(0xA1, 0x02, (0x02 shl 8) or ch, (f.id shl 8) or dac.acIface, b, b.size) >= 8) {
                volMin = s16(b, 2); volMax = s16(b, 4)
            }
        } else {
            val b = ByteArray(2)
            if (ctrl(0xA1, 0x82, (0x02 shl 8) or ch, (f.id shl 8) or dac.acIface, b, 2) == 2) volMin = s16(b, 0)
            if (ctrl(0xA1, 0x83, (0x02 shl 8) or ch, (f.id shl 8) or dac.acIface, b, 2) == 2) volMax = s16(b, 0)
        }
        if (volMax > volMin) volChannels = chans
        log.append("DAC volume: ${if (hasVolume) "%.1f to %.1f dB".format(volMin / 256.0, volMax / 256.0) else "not available (set the volume on the DAC or amplifier)"}\n")
    }

    /** Sets the DAC's own volume: 1.0 = its maximum, 0 = its minimum; linear in decibels over the top 60 dB. */
    fun setVolume(level: Float) {
        val f = dac.volumeUnit ?: return
        if (!hasVolume) return
        val db = levelToDb(level, volMin, volMax)
        val v = (db * 256).toInt().coerceIn(volMin, volMax)
        val b = byteArrayOf(v.toByte(), (v shr 8).toByte())
        for (ch in volChannels) ctrl(0x21, 0x01, (0x02 shl 8) or ch, (f.id shl 8) or dac.acIface, b, 2)
    }

    /** The DAC's volume as it reports it now, in dB (null if it has no volume control or doesn't answer). */
    fun readVolumeDb(): Double? {
        val f = dac.volumeUnit ?: return null
        if (!hasVolume) return null
        val b = ByteArray(2)
        val n = ctrl(0xA1, if (dac.uac2) 0x01 else 0x81, (0x02 shl 8) or volChannels[0], (f.id shl 8) or dac.acIface, b, 2)
        return if (n == 2) s16(b, 0) / 256.0 else null
    }

    val volumeRangeDb: Pair<Double, Double>? get() = if (hasVolume) volMin / 256.0 to volMax / 256.0 else null

    // ---- DAC mute (feature unit)
    private var muteChannels = emptyList<Int>()
    val hasMute: Boolean get() = muteChannels.isNotEmpty()

    private fun initMute() {
        val f = dac.muteUnit ?: return
        muteChannels = if (f.mute.getOrElse(0) { false }) listOf(0) else listOf(1, 2)
        log.append("DAC mute: available (feature unit #${f.id}), now ${readMute()?.let { if (it) "muted" else "not muted" } ?: "unknown"}\n")
    }

    fun setMute(on: Boolean) {
        val f = dac.muteUnit ?: return
        for (ch in muteChannels) ctrl(0x21, 0x01, (0x01 shl 8) or ch, (f.id shl 8) or dac.acIface, byteArrayOf(if (on) 1 else 0), 1)
    }

    fun readMute(): Boolean? {
        val f = dac.muteUnit ?: return null
        if (muteChannels.isEmpty()) return null
        val b = ByteArray(1)
        val n = ctrl(0xA1, if (dac.uac2) 0x01 else 0x81, (0x01 shl 8) or muteChannels[0], (f.id shl 8) or dac.acIface, b, 1)
        return if (n == 1) b[0].toInt() != 0 else null
    }

    fun report(): String = buildString {
        append("DAC: $name\n")
        append(String.format(java.util.Locale.ROOT, "USB id %04x:%04x, %s\n", device.vendorId, device.productId, speedText))
        append(UsbDac.describe(dac))
        append("rates it accepts: ${allRates().joinToString(" ")}\n")
        append(log)
    }

    fun close() {
        try { stopStream() } catch (_: Exception) {}
        val fdNow = try { conn.fileDescriptor } catch (_: Exception) { -1 }
        for (i in claimed) try { conn.releaseInterface(i) } catch (_: Exception) {}
        if (fdNow >= 0 && Native.loaded) for (i in claimed.map { it.id }.distinct()) Native.usbReattach(fdNow, i)
        claimed.clear()
        conn.close()
    }

    companion object {
        fun isAudio(d: UsbDevice): Boolean = (0 until d.interfaceCount).any {
            val i = d.getInterface(it); i.interfaceClass == 1 && i.interfaceSubclass == 2
        }

        fun findDac(ctx: Context): UsbDevice? {
            val um = ctx.getSystemService(UsbManager::class.java) ?: return null
            return um.deviceList.values.firstOrNull { isAudio(it) }
        }

        /** Opens the DAC (the app must already have USB permission for it). */
        fun open(ctx: Context, d: UsbDevice): UsbLink {
            if (!Native.load()) throw IOException("native audio library missing")
            val um = ctx.getSystemService(UsbManager::class.java) ?: throw IOException("no USB host support")
            val conn = um.openDevice(d) ?: throw IOException("Android did not allow Player Pure to open the DAC")
            try {
                val raw = conn.rawDescriptors ?: throw IOException("could not read the DAC's descriptors")
                val dac = UsbDac.parse(raw)
                if (dac.alts.none { it.pcm }) throw IOException("this USB device has no PCM playback setting")
                val speed = Native.usbSpeed(conn.fileDescriptor)
                val hs = when { speed >= 3 -> true; speed in 1..2 -> false; else -> dac.uac2 }
                val speedText = when (speed) { 1 -> "low speed"; 2 -> "full speed (USB 1.1)"; 3 -> "high speed (USB 2.0)"; 5, 6 -> "SuperSpeed"; else -> if (hs) "high speed (assumed)" else "full speed (assumed)" }
                val link = UsbLink(d, conn, dac, hs, speedText)
                link.log.append("descriptors: ").append(raw.joinToString("") { "%02x".format(it) }).append('\n')
                // take the control and playback interfaces from Android's driver
                for (id in listOf(dac.acIface) + dac.playbackIfaces) {
                    val intf = link.iface(id, 0) ?: continue
                    if (!conn.claimInterface(intf, true)) throw IOException("could not take interface $id from Android")
                    link.claimed += intf
                }
                if (dac.uac2) link.queryClockRates()
                link.initVolume()
                link.initMute()
                return link
            } catch (e: Exception) {
                conn.close()
                throw if (e is IOException) e else IOException(e.message ?: e.toString())
            }
        }

        /** Slider position (0..1) to dB: the top 60 dB of the DAC's range, linear in dB; 0 = the DAC's minimum. */
        fun levelToDb(level: Float, min256: Int, max256: Int): Double =
            if (level <= 0f) min256 / 256.0 else maxOf(min256 / 256.0, max256 / 256.0 - 60.0 * (1.0 - level.coerceAtMost(1f)))

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun le32(b: ByteArray) = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        private fun s16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
    }
}
