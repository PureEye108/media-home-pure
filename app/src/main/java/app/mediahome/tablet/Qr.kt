package app.mediahome.tablet

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * QR codes for "scan to open the web remote". Encoding uses ZXing core (Apache 2.0); drawing is done here.
 * Nothing is sent anywhere: the code is made on the tablet from the tablet's own address.
 */
object Qr {
    /** White border around the code, in modules (the QR standard asks for 4). */
    const val QUIET = 4

    /** The code's modules (true = dark), without the border. Medium error correction (about 15% damage). */
    fun modules(text: String): Array<BooleanArray> {
        val m = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
    }

    /** Black-on-white bitmap close to [sizePx] square, sharp edges, with the border included. */
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val mods = modules(text)
        val n = mods.size + 2 * QUIET
        val scale = maxOf(1, sizePx / n)
        val side = n * scale
        val px = IntArray(side * side) { Color.WHITE }
        for (y in mods.indices) for (x in mods.indices) {
            if (!mods[y][x]) continue
            for (dy in 0 until scale) {
                val row = ((y + QUIET) * scale + dy) * side + (x + QUIET) * scale
                for (dx in 0 until scale) px[row + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    }

    /** The same code as a small SVG image (one path, no scripts), for the web page. */
    fun svg(text: String): ByteArray {
        val mods = modules(text)
        val n = mods.size + 2 * QUIET
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $n $n\" shape-rendering=\"crispEdges\">")
        sb.append("<rect width=\"$n\" height=\"$n\" fill=\"#fff\"/><path fill=\"#000\" d=\"")
        for (y in mods.indices) {
            var x = 0
            while (x < mods.size) {
                if (!mods[y][x]) { x++; continue }
                val start = x
                while (x < mods.size && mods[y][x]) x++
                sb.append("M").append(start + QUIET).append(' ').append(y + QUIET)
                    .append("h").append(x - start).append("v1h-").append(x - start).append('z')
            }
        }
        sb.append("\"/></svg>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
