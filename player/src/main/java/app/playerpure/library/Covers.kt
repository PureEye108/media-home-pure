package app.playerpure.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Album covers: embedded pictures or cover.jpg, scaled once and kept in memory and on disk. */
object Covers {
    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "pp-covers").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val missing = HashSet<String>()

    /** Lets tests and screenshots supply pictures without files. */
    @Volatile var override: ((art: String?, path: String) -> Bitmap?)? = null

    private fun key(art: String?, path: String, size: Int) = "${art ?: ""}|${if (art == "emb" || art == null) path else ""}|$size"

    private fun sample(w: Int, h: Int, size: Int): Int { var s = 1; while (w / (s * 2) >= size && h / (s * 2) >= size) s *= 2; return s }

    /** Decodes now (call off the main thread). */
    fun bitmap(ctx: Context, art: String?, path: String, size: Int): Bitmap? {
        override?.let { return it(art, path) }
        val k = key(art, path, size)
        mem.get(k)?.let { return it }
        synchronized(missing) { if (k in missing) return null }
        val disk = File(ctx.cacheDir, "covers/" + md5(k) + ".jpg")
        var bmp: Bitmap? = if (disk.isFile) BitmapFactory.decodeFile(disk.path) else null
        if (bmp == null) {
            val bytes: ByteArray? = when {
                art == "emb" -> Tags.readArt(File(path))
                art != null -> try { File(art).readBytes() } catch (_: Exception) { null }
                else -> null
            }
            if (bytes != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth > 0) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, size) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { full ->
                        val scale = size.toFloat() / maxOf(full.width, full.height)
                        bmp = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true) else full
                        try { disk.parentFile?.mkdirs(); disk.outputStream().use { bmp!!.compress(Bitmap.CompressFormat.JPEG, 90, it) } } catch (_: Exception) {}
                    }
                }
            }
        }
        val b = bmp
        if (b == null) { synchronized(missing) { missing += k }; return null }
        mem.put(k, b)
        return b
    }

    /** Shows the cover in [view] when ready; [fallback] runs if there is none. */
    fun load(view: ImageView, art: String?, path: String, size: Int, fallback: (ImageView) -> Unit) {
        val k = key(art, path, size)
        view.tag = k
        val cached = override?.invoke(art, path) ?: mem.get(k)
        if (cached != null) { view.setImageBitmap(cached); return }
        fallback(view)
        if (art == null && override == null) return
        val ctx = view.context.applicationContext
        pool.execute {
            val b = try { bitmap(ctx, art, path, size) } catch (_: Throwable) { null }
            if (b != null) main.post { if (view.tag == k) view.setImageBitmap(b) }
        }
    }

    fun clear() { mem.evictAll(); synchronized(missing) { missing.clear() } }

    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
