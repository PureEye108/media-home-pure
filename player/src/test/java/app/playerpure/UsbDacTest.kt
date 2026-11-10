package app.playerpure

import app.playerpure.audio.UsbDac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Descriptor sets modelled on typical DACs: a UAC2 desktop DAC (high speed, async) and a UAC1 dongle (full speed). */
class UsbDacTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }

    // UAC2 desktop DAC: clock source 0x29 (via selector 0x28), input terminal 2 (USB streaming), feature unit 10 with
    // master volume, alt 1 = 16-bit/2, alt 2 = 24-bit/4, alt 3 = 32-bit/4, async OUT 0x01 + feedback IN 0x81
    private fun uac2(rawDsd: Boolean = false): ByteArray {
        val d = ArrayList<Byte>()
        d += b(18, 1, 0x00, 0x02, 0xEF, 2, 1, 64, 0x2B, 0x15, 0x99, 0x00, 0, 1, 1, 2, 3, 1)       // device 152b:0099
        d += b(9, 2, 0, 0, 3, 1, 0, 0x80, 250)                                                      // config
        d += b(8, 0x0B, 0, 2, 1, 0, 0x20, 0)                                                        // IAD
        d += b(9, 4, 0, 0, 0, 1, 1, 0x20, 0)                                                        // AC interface 0, UAC2
        d += b(9, 0x24, 1, 0, 2, 0x0A, 0x40, 0, 0)                                                  // AC header
        d += b(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0, 0)                                               // clock source 0x29
        d += b(8, 0x24, 0x0B, 0x28, 1, 0x29, 0x03, 0)                                               // clock selector 0x28 -> 0x29
        d += b(17, 0x24, 2, 2, 0x01, 0x01, 0, 0x28, 2, 3, 0, 0, 0, 0, 0, 0, 0)                     // input terminal 2, clock 0x28
        d += b(18, 0x24, 6, 10, 2, 0x0F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)                        // feature unit 10 (master mute+volume)
        d += b(12, 0x24, 3, 20, 0x01, 0x03, 0, 10, 0x28, 0, 0, 0)                                    // output terminal 20 (speaker)
        d += b(9, 4, 1, 0, 0, 1, 2, 0x20, 0)                                                        // AS interface 1 alt 0
        fun alt(n: Int, subslot: Int, bitsRes: Int, maxPacket: Int, raw: Boolean = false) {
            d += b(9, 4, 1, n, 2, 1, 2, 0x20, 0)
            if (raw) d += b(16, 0x24, 1, 2, 0, 1, 0, 0, 0, 0x80, 2, 3, 0, 0, 0, 0)                  // AS general: raw data (XMOS native DSD)
            else d += b(16, 0x24, 1, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)                         // AS general: terminal 2, PCM, 2 ch
            d += b(6, 0x24, 2, 1, subslot, bitsRes)
            d += b(7, 5, 0x01, 0x05, maxPacket and 0xFF, maxPacket shr 8, 1)                        // iso OUT async, interval 1
            d += b(8, 0x25, 1, 0, 0, 0, 0, 0)
            d += b(7, 5, 0x81, 0x11, 4, 0, 4)                                                       // feedback IN, 4 bytes
        }
        alt(1, 2, 16, 392); alt(2, 4, 24, 784); alt(3, 4, 32, 784)
        if (rawDsd) {
            alt(4, 4, 32, 784, raw = true)
            d += b(9, 4, 2, 0, 1, 3, 0, 0, 0)                                                       // HID interface 2 (buttons/display on some DACs)
            d += b(7, 5, 0x83, 0x03, 64, 0, 10)
        }
        return d.toByteArray()
    }

    // UAC1 dongle: full speed, alt 1 = 16-bit, alt 2 = 24-bit in 3 bytes, rates 44.1/48/96, adaptive, no feedback
    private fun uac1(): ByteArray {
        val d = ArrayList<Byte>()
        d += b(18, 1, 0x10, 0x01, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 1, 2, 0, 1)
        d += b(9, 2, 0, 0, 2, 1, 0, 0x80, 50)
        d += b(9, 4, 0, 0, 0, 1, 1, 0, 0)
        d += b(9, 0x24, 1, 0, 1, 0x28, 0, 1, 1)
        d += b(12, 0x24, 2, 1, 0x01, 0x01, 0, 2, 3, 0, 0, 0)
        d += b(10, 0x24, 6, 2, 1, 1, 0x03, 0x00, 0x00, 0)                                           // FU 2: master mute+vol, ch1/2 none
        d += b(9, 0x24, 3, 3, 0x02, 0x03, 0, 2, 0)
        d += b(9, 4, 1, 0, 0, 1, 2, 0, 0)
        fun alt(n: Int, subframe: Int, bitsRes: Int, maxPacket: Int) {
            d += b(9, 4, 1, n, 1, 1, 2, 0, 0)
            d += b(7, 0x24, 1, 1, 1, 1, 0)                                                          // terminal 1, PCM
            d += b(17, 0x24, 2, 1, 2, subframe, bitsRes, 3, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00, 0x00, 0x77, 0x01)
            d += b(9, 5, 0x01, 0x09, maxPacket and 0xFF, maxPacket shr 8, 1, 0, 0)                 // iso OUT adaptive
            d += b(7, 0x25, 1, 1, 0, 0, 0)
        }
        alt(1, 2, 16, 388); alt(2, 3, 24, 582)
        return d.toByteArray()
    }

    private val uac2Rates = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

    @Test fun parsesUac2() {
        val dev = UsbDac.parse(uac2())
        assertTrue(dev.uac2); assertEquals(0, dev.acIface); assertEquals(0x152B, dev.vendorId)
        assertEquals(3, dev.alts.size)
        val a2 = dev.alts[1]
        assertEquals(1, a2.iface); assertEquals(2, a2.alt); assertEquals(2, a2.channels); assertEquals(4, a2.subslot); assertEquals(24, a2.bits)
        assertEquals(0x01, a2.dataEp); assertEquals(784, a2.dataMaxPacket); assertEquals(1, a2.syncType)
        assertEquals(0x81, a2.fbEp); assertEquals(4, a2.fbMaxPacket); assertTrue(a2.pcm)
        assertEquals(0x29, dev.clockSourceFor(a2))
        assertNotNull(dev.volumeUnit); assertEquals(10, dev.volumeUnit!!.id)
    }

    @Test fun picksSmallestAltThatHoldsEveryBit() {
        val dev = UsbDac.parse(uac2())
        val r = { _: UsbDac.Alt -> uac2Rates }
        val c16 = UsbDac.choose(dev, 44100, 2, 16, r)!!
        assertEquals(1, c16.alt.alt); assertTrue(c16.bitPerfect)
        val c24 = UsbDac.choose(dev, 96000, 2, 24, r)!!
        assertEquals(2, c24.alt.alt); assertTrue(c24.bitPerfect)
        val c32 = UsbDac.choose(dev, 384000, 2, 32, r)!!
        assertEquals(3, c32.alt.alt)
        assertNull(UsbDac.choose(dev, 705600, 2, 24, r))   // rate the DAC doesn't have
        val mono = UsbDac.choose(dev, 48000, 1, 16, r)!!
        assertTrue(mono.upmixMono)
    }

    @Test fun parsesUac1() {
        val dev = UsbDac.parse(uac1())
        assertFalse(dev.uac2)
        assertEquals(2, dev.alts.size)
        val a = dev.alts[1]
        assertEquals(3, a.subslot); assertEquals(24, a.bits); assertEquals(listOf(44100, 48000, 96000), a.rates)
        assertEquals(2, a.syncType); assertEquals(0, a.fbEp)
        val c = UsbDac.choose(dev, 96000, 2, 24) { null }!!
        assertEquals(2, c.alt.alt); assertTrue(c.bitPerfect)
        assertNull(UsbDac.choose(dev, 192000, 2, 24) { null })
        // a 24-bit file to a 16-bit-only setting would not be bit-perfect
        val only16 = dev.copy(alts = dev.alts.take(1))
        assertFalse(UsbDac.choose(only16, 44100, 2, 24) { null }!!.bitPerfect)
        assertEquals(listOf(44100, 48000, 96000), UsbDac.allRates(dev) { null })
        assertNotNull(dev.volumeUnit)
    }

    @Test fun nativeDsdAltOnXmosDac() {
        val dev = UsbDac.parse(uac2(rawDsd = true))
        val r = { _: UsbDac.Alt -> uac2Rates }
        assertEquals(4, dev.alts.size)
        val raw = dev.alts[3]
        assertTrue(raw.rawDsd); assertFalse(raw.pcm); assertEquals(4, raw.subslot); assertEquals(32, raw.bits)
        assertTrue(dev.hasNativeDsd)
        assertFalse(UsbDac.parse(uac2()).hasNativeDsd)
        // PCM never lands on the raw alt
        assertEquals(3, UsbDac.choose(dev, 192000, 2, 32, r)!!.alt.alt)
        // DSD64 = 2.8224 MHz -> 88.2 kHz frames of 32 DSD bits; DSD256 -> 352.8 kHz
        val d64 = UsbDac.chooseNativeDsd(dev, 2822400, 2, r)!!
        assertEquals(4, d64.alt.alt); assertEquals(88200, d64.rate); assertTrue(d64.bitPerfect); assertFalse(d64.upmixMono)
        assertEquals(352800, UsbDac.chooseNativeDsd(dev, 11289600, 2, r)!!.rate)
        assertTrue(UsbDac.chooseNativeDsd(dev, 2822400, 1, r)!!.upmixMono)
        assertNull(UsbDac.chooseNativeDsd(dev, 22579200, 2, r))     // DSD512 -> 705.6 kHz, not offered here
        assertNull(UsbDac.chooseNativeDsd(UsbDac.parse(uac2()), 2822400, 2, r))
        // every interface is listed for the DAC report, the HID one included
        assertTrue(dev.ifaces.any { it.num == 2 && it.cls == 3 })
        assertTrue(UsbDac.describe(dev).contains("native DSD (raw)"))
    }

    @Test fun muteControl() {
        assertNotNull(UsbDac.parse(uac2()).muteUnit)
        assertNotNull(UsbDac.parse(uac1()).muteUnit)
    }

    @Test fun rangeAnswer() {
        // two sub-ranges: 44100..44100 and 48000..384000 step 48000
        val bytes = byteArrayOf(2, 0) + le(44100) + le(44100) + le(0) + le(48000) + le(384000) + le(48000)
        assertEquals(listOf(44100, 48000, 96000, 192000, 384000), UsbDac.ratesFromRange(bytes, bytes.size))
    }

    @Test fun highBandwidthPacketSize() {
        assertEquals(1024 * 3, UsbDac.maxPacketBytes(0x1400))
        assertEquals(784, UsbDac.maxPacketBytes(784))
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
