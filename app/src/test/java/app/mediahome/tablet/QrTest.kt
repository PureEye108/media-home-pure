package app.mediahome.tablet

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The QR codes are read back with an independent decoder (ZXing's reader) to prove phones can scan them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QrTest {
    private val link = "http://192.168.100.200:8080/#pin=1008"

    private fun decode(pixels: IntArray, w: Int, h: Int): String =
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))).text

    @Test fun modulesDecodeToTheLink() {
        val mods = Qr.modules(link)
        val scale = 6
        val n = (mods.size + 2 * Qr.QUIET) * scale
        val px = IntArray(n * n) { -1 }   // white
        for (y in mods.indices) for (x in mods.indices) if (mods[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) px[((y + Qr.QUIET) * scale + dy) * n + (x + Qr.QUIET) * scale + dx] = 0xFF000000.toInt()
        }
        assertEquals(link, decode(px, n, n))
    }

    @Test fun bitmapDecodesToTheLink() {
        val bmp = Qr.bitmap(link, 600)
        assertTrue(bmp.width in 500..600 && bmp.width == bmp.height)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertEquals(link, decode(px, bmp.width, bmp.height))
    }

    @Test fun svgIsSquareAndScriptFree() {
        val svg = Qr.svg(link).toString(Charsets.UTF_8)
        val n = Qr.modules(link).size + 2 * Qr.QUIET
        assertTrue(svg.contains("viewBox=\"0 0 $n $n\""))
        assertTrue(!svg.contains("<script") && !svg.contains("href"))
        // Saved so the web page test can show (and decode) a real code.
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/qr-sample.svg").writeBytes(Qr.svg(link))
    }
}
