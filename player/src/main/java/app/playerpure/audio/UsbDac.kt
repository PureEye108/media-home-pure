package app.playerpure.audio

/**
 * Reads a USB DAC's descriptors (USB Audio Class 1 and 2) and picks the playback setting that carries a file
 * unchanged. Pure Kotlin, no Android types, so it can be unit-tested with real descriptor dumps.
 */
object UsbDac {

    data class Alt(
        val iface: Int, val alt: Int,
        val channels: Int, val subslot: Int, val bits: Int,
        val pcm: Boolean,
        val rates: List<Int>?,            // UAC1 discrete rates (null = continuous range or UAC2)
        val rateMin: Int, val rateMax: Int,
        val dataEp: Int, val dataMaxPacket: Int, val dataBInterval: Int, val syncType: Int,
        val fbEp: Int, val fbMaxPacket: Int,
        val terminalLink: Int,
        val rawDsd: Boolean = false,      // UAC2 "raw data" format: native DSD on XMOS-based DACs (Topping and others)
    )

    /** Every interface setting in the descriptors (number, alternate setting, class, subclass, protocol, endpoints). */
    data class Iface(val num: Int, val alt: Int, val cls: Int, val sub: Int, val proto: Int, val endpoints: Int)

    data class Clock(val id: Int, val kind: Int, val sources: List<Int>)   // kind: 0x0A source, 0x0B selector, 0x0C multiplier
    data class Feature(val id: Int, val source: Int, val volume: List<Boolean>, val mute: List<Boolean>)   // per channel, 0 = master

    data class Device(
        val vendorId: Int, val productId: Int, val bcdUsb: Int,
        val uac2: Boolean, val acIface: Int,
        val alts: List<Alt>,
        val terminalClock: Map<Int, Int>,     // input terminal id -> clock entity id (UAC2)
        val clocks: Map<Int, Clock>,
        val features: List<Feature>,
        val ifaces: List<Iface> = emptyList(),
    ) {
        /** UAC2: the clock source that times this alternate setting (following selectors and multipliers). */
        fun clockSourceFor(a: Alt): Int? {
            var id = terminalClock[a.terminalLink] ?: return clocks.values.firstOrNull { it.kind == 0x0A }?.id
            repeat(8) {
                val c = clocks[id] ?: return null
                if (c.kind == 0x0A) return id
                id = c.sources.firstOrNull() ?: return null
            }
            return null
        }
        val playbackIfaces: List<Int> get() = alts.map { it.iface }.distinct()
        /** First feature unit with a volume control on the master channel or on channels 1 and 2. */
        val volumeUnit: Feature? get() = features.firstOrNull { f ->
            f.volume.getOrElse(0) { false } || (f.volume.getOrElse(1) { false } && f.volume.getOrElse(2) { false })
        }
        /** First feature unit with a mute control on the master channel or on channels 1 and 2. */
        val muteUnit: Feature? get() = features.firstOrNull { f ->
            f.mute.getOrElse(0) { false } || (f.mute.getOrElse(1) { false } && f.mute.getOrElse(2) { false })
        }
        val hasNativeDsd: Boolean get() = alts.any { it.rawDsd }
    }

    val STANDARD_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)

    fun maxPacketBytes(w: Int): Int = (w and 0x7FF) * (1 + ((w shr 11) and 3))

    fun parse(raw: ByteArray): Device {
        fun u8(i: Int) = raw[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

        var vid = 0; var pid = 0; var bcd = 0
        var uac2 = false; var acIface = -1
        val alts = ArrayList<Alt>()
        val termClock = HashMap<Int, Int>()
        val clocks = HashMap<Int, Clock>()
        val features = ArrayList<Feature>()

        // state of the interface being read
        var ifNum = -1; var ifAlt = 0; var ifClass = 0; var ifSub = 0; var ifProto = 0
        val ifaces = ArrayList<Iface>()
        var asTerminal = 0; var asPcm = false; var asRaw = false; var asChannels = 0; var asSubslot = 0; var asBits = 0
        var asRates: MutableList<Int>? = null; var asMin = 0; var asMax = 0
        var dataEp = 0; var dataMax = 0; var dataInt = 0; var sync = 0; var fbEp = 0; var fbMax = 0; var synchAddr = 0
        var inFbEp = 0; var inFbMax = 0

        fun finishAlt() {
            if (ifClass == 1 && ifSub == 2 && ifAlt > 0 && dataEp != 0 && asChannels > 0) {
                val fe = if (fbEp != 0) fbEp else if (synchAddr != 0 && synchAddr == inFbEp) inFbEp else if (inFbEp != 0 && sync == 1) inFbEp else 0
                val fm = if (fe == fbEp && fbEp != 0) fbMax else inFbMax
                alts += Alt(ifNum, ifAlt, asChannels, asSubslot, asBits, asPcm, asRates?.toList(), asMin, asMax,
                    dataEp, dataMax, dataInt, sync, fe, if (fe != 0) fm else 0, asTerminal, asRaw)
            }
            asTerminal = 0; asPcm = false; asRaw = false; asChannels = 0; asSubslot = 0; asBits = 0; asRates = null; asMin = 0; asMax = 0
            dataEp = 0; dataMax = 0; dataInt = 0; sync = 0; fbEp = 0; fbMax = 0; synchAddr = 0; inFbEp = 0; inFbMax = 0
        }

        var i = 0
        while (i + 2 <= raw.size) {
            val len = u8(i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(i + 1)
            when (type) {
                1 -> if (len >= 12) { bcd = u16(i + 2); vid = u16(i + 8); pid = u16(i + 10) }
                4 -> if (len >= 9) {
                    finishAlt()
                    ifNum = u8(i + 2); ifAlt = u8(i + 3); ifClass = u8(i + 5); ifSub = u8(i + 6); ifProto = u8(i + 7)
                    ifaces += Iface(ifNum, ifAlt, ifClass, ifSub, ifProto, u8(i + 4))
                    if (ifClass == 1 && ifSub == 1) { acIface = ifNum; uac2 = ifProto == 0x20 }
                }
                0x24 -> if (ifClass == 1 && len >= 3) {
                    val sub = u8(i + 2)
                    if (ifSub == 1) {   // AudioControl
                        if (uac2) when (sub) {
                            2 -> if (len >= 8) termClock[u8(i + 3)] = u8(i + 7)
                            0x0A -> if (len >= 4) clocks[u8(i + 3)] = Clock(u8(i + 3), 0x0A, emptyList())
                            0x0B -> if (len >= 5) {
                                val n = u8(i + 4)
                                clocks[u8(i + 3)] = Clock(u8(i + 3), 0x0B, (0 until n).filter { 5 + it < len }.map { u8(i + 5 + it) })
                            }
                            0x0C -> if (len >= 5) clocks[u8(i + 3)] = Clock(u8(i + 3), 0x0C, listOf(u8(i + 4)))
                            6 -> if (len >= 10) {
                                val n = (len - 6) / 4
                                val vol = ArrayList<Boolean>(); val mute = ArrayList<Boolean>()
                                for (c in 0 until n) { val bm = u32(i + 5 + c * 4); mute += (bm and 3) == 3; vol += ((bm shr 2) and 3) == 3 }
                                features += Feature(u8(i + 3), u8(i + 4), vol, mute)
                            }
                        } else when (sub) {
                            6 -> if (len >= 7) {
                                val size = u8(i + 5).coerceAtLeast(1)
                                val n = (len - 7) / size
                                val vol = ArrayList<Boolean>(); val mute = ArrayList<Boolean>()
                                for (c in 0 until n) { val bm = u8(i + 6 + c * size); mute += (bm and 1) != 0; vol += (bm and 2) != 0 }
                                features += Feature(u8(i + 3), u8(i + 4), vol, mute)
                            }
                        }
                    } else if (ifSub == 2) {   // AudioStreaming
                        if (uac2) when (sub) {
                            1 -> if (len >= 11) {
                                asTerminal = u8(i + 3); asChannels = u8(i + 10)
                                val formats = u32(i + 6)
                                asPcm = (formats and 1) != 0 && u8(i + 5) == 1
                                asRaw = (formats ushr 31) == 1 && u8(i + 5) == 1
                            }
                            2 -> if (len >= 6 && u8(i + 3) == 1) { asSubslot = u8(i + 4); asBits = u8(i + 5) }
                        } else when (sub) {
                            1 -> if (len >= 7) { asTerminal = u8(i + 3); asPcm = u16(i + 5) == 1 }
                            2 -> if (len >= 8 && u8(i + 3) == 1) {
                                asChannels = u8(i + 4); asSubslot = u8(i + 5); asBits = u8(i + 6)
                                val n = u8(i + 7)
                                if (n == 0 && len >= 14) { asMin = u24(i + 8); asMax = u24(i + 11) }
                                else { asRates = (0 until n).filter { 8 + it * 3 + 3 <= len }.map { u24(i + 8 + it * 3) }.toMutableList() }
                            }
                        }
                    }
                }
                5 -> if (ifClass == 1 && ifSub == 2 && len >= 7) {
                    val addr = u8(i + 2); val attr = u8(i + 3); val mp = u16(i + 4); val interval = u8(i + 6)
                    if ((attr and 3) == 1) {
                        val usage = (attr shr 4) and 3
                        if ((addr and 0x80) == 0) {
                            dataEp = addr; dataMax = maxPacketBytes(mp); dataInt = interval; sync = (attr shr 2) and 3
                            if (len >= 9) synchAddr = u8(i + 8)
                        } else if (usage == 1) { fbEp = addr; fbMax = maxPacketBytes(mp) }
                        else if (len >= 7) { inFbEp = addr; inFbMax = maxPacketBytes(mp) }   // UAC1 sync endpoint (usage bits unused)
                    }
                }
            }
            i += len
        }
        finishAlt()
        return Device(vid, pid, bcd, uac2, acIface, alts, termClock, clocks, features, ifaces)
    }

    /** UAC2 RANGE answer: wNumSubRanges then (min, max, res) as 32-bit values. Returns standard rates inside. */
    fun ratesFromRange(b: ByteArray, n: Int): List<Int> {
        if (n < 2) return emptyList()
        fun u32(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        val count = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val out = sortedSetOf<Int>()
        for (k in 0 until count) {
            val o = 2 + k * 12
            if (o + 12 > n) break
            val min = u32(o); val max = u32(o + 4); val res = u32(o + 8)
            for (r in STANDARD_RATES) if (r in min..max && (res == 0 || (r - min) % res == 0)) out += r
            if (min == max && min > 0) out += min
        }
        return out.toList()
    }

    fun altSupports(a: Alt, rate: Int, uac2Rates: List<Int>?): Boolean = when {
        uac2Rates != null -> rate in uac2Rates
        a.rates != null -> rate in a.rates
        else -> rate in a.rateMin..a.rateMax
    }

    data class Choice(val alt: Alt, val rate: Int, val bitPerfect: Boolean, val upmixMono: Boolean)

    /**
     * Picks the alternate setting for a stream. [ratesFor] gives a UAC2 alt's clock rates (null for UAC1).
     * Prefers the same channel count, then the smallest bit depth that holds every source bit.
     */
    fun choose(dev: Device, rate: Int, channels: Int, bits: Int, ratesFor: (Alt) -> List<Int>?): Choice? {
        val ok = dev.alts.filter { it.pcm && it.subslot in 1..4 && it.bits > 0 && altSupports(it, rate, ratesFor(it)) }
        var cands = ok.filter { it.channels == channels }
        var upmix = false
        if (cands.isEmpty() && channels == 1) { cands = ok.filter { it.channels == 2 }; upmix = true }
        if (cands.isEmpty()) return null
        val fits = cands.filter { it.bits >= bits }
        val best = if (fits.isNotEmpty()) fits.minWith(compareBy<Alt>({ it.bits }, { it.subslot }, { it.alt }))
                   else cands.maxWith(compareBy<Alt>({ it.bits }, { -it.subslot }))
        // the packet must hold one service interval of audio
        return Choice(best, rate, best.bits >= bits, upmix)
    }

    /**
     * Native DSD: the DAC's raw-data setting at DSD rate / 32 (DSD64 -> 88.2 kHz, 4 DSD bytes per channel per frame).
     * Returns null if the DAC has no such setting or not at this rate.
     */
    fun chooseNativeDsd(dev: Device, dsdRate: Int, channels: Int, ratesFor: (Alt) -> List<Int>?): Choice? {
        val rate = dsdRate / 32
        val ok = dev.alts.filter { it.rawDsd && it.subslot == 4 && altSupports(it, rate, ratesFor(it)) }
        val same = ok.firstOrNull { it.channels == channels }
        if (same != null) return Choice(same, rate, true, false)
        if (channels == 1) ok.firstOrNull { it.channels == 2 }?.let { return Choice(it, rate, true, true) }
        return null
    }

    /** Every rate the device can play (any alt), for the settings screen. */
    fun allRates(dev: Device, ratesFor: (Alt) -> List<Int>?): List<Int> =
        dev.alts.filter { it.pcm }.flatMap { a -> STANDARD_RATES.filter { altSupports(a, it, ratesFor(a)) } }.distinct().sorted()

    fun describe(dev: Device): String = buildString {
        append(String.format(java.util.Locale.ROOT, "USB %04x:%04x, USB Audio Class %s, control interface %d\n", dev.vendorId, dev.productId, if (dev.uac2) "2" else "1", dev.acIface))
        for (a in dev.alts) {
            append(String.format(java.util.Locale.ROOT, "  interface %d alt %d: %s %d ch, %d-bit in %d bytes, ep 0x%02x max %d interval %d sync %s, feedback %s",
                a.iface, a.alt, when { a.pcm -> "PCM"; a.rawDsd -> "native DSD (raw)"; else -> "other format" }, a.channels, a.bits, a.subslot, a.dataEp, a.dataMaxPacket, a.dataBInterval,
                when (a.syncType) { 1 -> "async"; 2 -> "adaptive"; 3 -> "sync"; else -> "none" },
                if (a.fbEp != 0) String.format(java.util.Locale.ROOT, "0x%02x (%d bytes)", a.fbEp, a.fbMaxPacket) else "none"))
            if (a.rates != null) append(", rates ").append(a.rates.joinToString(" "))
            else if (a.rateMax > 0) append(", rates ${a.rateMin}-${a.rateMax}")
            append('\n')
        }
        if (dev.clocks.isNotEmpty()) append("  clocks: ").append(dev.clocks.values.joinToString { "#${it.id} " + when (it.kind) { 0x0A -> "source"; 0x0B -> "selector ${it.sources}"; else -> "multiplier ${it.sources}" } }).append('\n')
        for (f in dev.features) append("  feature unit #${f.id}: volume ${f.volume}, mute ${f.mute}\n")
        if (dev.ifaces.isNotEmpty()) append("  all interfaces: ").append(dev.ifaces.joinToString { i ->
            "${i.num}/${i.alt} " + when (i.cls) { 1 -> if (i.sub == 1) "audio control" else if (i.sub == 2) "audio streaming" else "audio"
                3 -> "HID"; 0xFE -> if (i.sub == 1) "DFU (firmware update)" else "app-specific"; 0xFF -> "vendor-specific"
                else -> String.format(java.util.Locale.ROOT, "class 0x%02x", i.cls) } + " (${i.endpoints} ep)" }).append('\n')
    }
}
