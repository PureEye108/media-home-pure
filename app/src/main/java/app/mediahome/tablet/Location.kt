package app.mediahome.tablet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

/**
 * Where the tablet is, for the weather. Uses Android's own location (approximate only: Wi-Fi / mobile network,
 * or a coarse GPS fix), never precise location. Coordinates are rounded to 0.01° (about 1 km) before they are saved
 * or sent to Open-Meteo for the forecast. The place name comes from Android's geocoder when it has one.
 */
object Locator {
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    /** A fix younger than this is good enough; no new request is made. */
    private const val FRESH_MS = 20 * 60 * 1000L
    private const val TIMEOUT_MS = 20_000L

    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(ctx: Context): Boolean = try {
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (e: Exception) {
        false
    }

    /** About 1 km: enough for the weather, and the exact position never leaves the tablet. */
    fun round(v: Double): Double = Math.round(v * 100.0) / 100.0

    /** True when two places are more than about 2 km apart (or the old one is unknown): time for a new name and forecast. */
    fun moved(oldLat: Double?, oldLon: Double?, lat: Double, lon: Double): Boolean =
        oldLat == null || oldLon == null || abs(oldLat - lat) > 0.019 || abs(oldLon - lon) > 0.019

    /**
     * The tablet's approximate location: a recent fix if Android has one, otherwise a fresh one (up to 20 s).
     * [done] runs on the main thread with null when location is off, not allowed or not found.
     */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context, done: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !hasPermission(ctx) || !locationOn(ctx)) { main.post { done(null) }; return }
        val providers = try { lm.getProviders(true) } catch (e: Exception) { emptyList() }
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val age = last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 } ?: Long.MAX_VALUE
        if (last != null && age < FRESH_MS) { main.post { done(last) }; return }

        val fused = if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null
        val provider = listOfNotNull(fused, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
        if (provider == null) { main.post { done(last) }; return }
        var finished = false
        val cancel = CancellationSignal()
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            done(l ?: last)
        }
        main.postDelayed({ cancel.cancel(); finish(null) }, TIMEOUT_MS)
        try {
            lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { l -> finish(l) }
        } catch (e: Exception) {
            main.post { finish(null) }
        }
    }

    /** A short place name ("Da Nang", "Thu Duc") from Android's geocoder, or null. Network on some tablets: call off the main thread. */
    @Suppress("DEPRECATION")
    fun placeName(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
            listOf(a.locality, a.subAdminArea, a.adminArea, a.countryName).firstOrNull { !it.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
