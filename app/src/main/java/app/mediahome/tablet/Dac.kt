package app.mediahome.tablet

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Which USB DAC is plugged in (for the bit-perfect guide in Settings and the web remote).
 * Only lists devices; it never opens them, so it cannot interfere with HiBy Music's exclusive USB access.
 */
object Dac {
    fun attached(ctx: Context): List<String> {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
        return try {
            um.deviceList.values.filter { isAudio(it) }.map { name(it) }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    private fun isAudio(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private fun name(d: UsbDevice): String {
        fun safe(f: () -> String?): String? = try { f()?.trim()?.takeIf { it.isNotEmpty() } } catch (e: RuntimeException) { null }
        val maker = safe { d.manufacturerName }
        val product = safe { d.productName }
        val label = when {
            product == null -> maker
            maker == null || product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
        return label ?: String.format("USB audio %04x:%04x", d.vendorId, d.productId)
    }
}
