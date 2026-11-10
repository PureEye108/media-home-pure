package app.mediahome.tablet

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone

data class Place(val name: String, val lat: Double, val lon: Double, val detail: String)
data class DayForecast(val date: LocalDate, val code: Int, val max: Double, val min: Double, val rainChance: Int?)
data class WeatherReport(val place: String, val temp: Double, val code: Int, val isDay: Boolean, val days: List<DayForecast>, val fetchedAt: Long)

/**
 * Local weather for the home screen and the screen saver from Open-Meteo (free, no account or API key; data by Open-Meteo.com).
 * Where (1.9): the tablet's **current location** by default ([Locator], approximate, rounded to about 1 km).
 * If location is off or not allowed: the city chosen in Settings, else the city of the tablet's time zone.
 * Choosing a fixed city in Settings switches the current location off. Network calls must run off the main thread.
 */
enum class WeatherSource { LOCATION, CITY, TIME_ZONE }

object Weather {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
    const val REFRESH_MS = 30 * 60 * 1000L

    fun enabled(ctx: Context): Boolean = Prefs.of(ctx).getBoolean(Prefs.WEATHER_ON, true)

    /** Forecast for the tablet's current location (default) instead of a fixed city. */
    fun useLocation(ctx: Context): Boolean = Prefs.of(ctx).getBoolean(Prefs.WEATHER_USE_LOCATION, true)

    fun setUseLocation(ctx: Context, on: Boolean) {
        Prefs.of(ctx).edit().putBoolean(Prefs.WEATHER_USE_LOCATION, on).remove(Prefs.WEATHER_CACHE).apply()
    }

    /** The last approximate location found for the tablet (rounded), with its place name. */
    fun herePlace(ctx: Context): Place? {
        val p = Prefs.of(ctx)
        val lat = p.getString(Prefs.WEATHER_HERE_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(Prefs.WEATHER_HERE_LON, null)?.toDoubleOrNull() ?: return null
        return Place(p.getString(Prefs.WEATHER_HERE_NAME, null) ?: ctx.getString(R.string.wx_here_unnamed), lat, lon, "")
    }

    /** True when a new place name is worth looking up (first fix, moved, or no name yet). */
    fun needsName(ctx: Context, lat: Double, lon: Double): Boolean {
        val here = herePlace(ctx)
        return here == null || Locator.moved(here.lat, here.lon, lat, lon) || Prefs.of(ctx).getString(Prefs.WEATHER_HERE_NAME, null) == null
    }

    /**
     * Saves the tablet's rounded location ([name] null = keep the old name if it hasn't moved).
     * Returns true when it moved (or is new): the cached forecast is dropped so the next refresh uses the new place.
     */
    fun saveHere(ctx: Context, lat: Double, lon: Double, name: String?): Boolean {
        val p = Prefs.of(ctx)
        val old = herePlace(ctx)
        val moved = old == null || Locator.moved(old.lat, old.lon, lat, lon)
        val e = p.edit().putString(Prefs.WEATHER_HERE_LAT, lat.toString()).putString(Prefs.WEATHER_HERE_LON, lon.toString())
        when {
            name != null -> e.putString(Prefs.WEATHER_HERE_NAME, name)
            moved -> e.remove(Prefs.WEATHER_HERE_NAME)
        }
        if (moved && useLocation(ctx)) e.remove(Prefs.WEATHER_CACHE)
        e.apply()
        return moved
    }

    /** The rule for where the forecast is for (unit-tested). A null place means "look up the time zone's city". */
    fun choose(useLocation: Boolean, here: Place?, city: Place?): Pair<Place?, WeatherSource> = when {
        useLocation && here != null -> here to WeatherSource.LOCATION
        city != null -> city to WeatherSource.CITY
        else -> null to WeatherSource.TIME_ZONE
    }

    fun source(ctx: Context): WeatherSource = choose(useLocation(ctx), herePlace(ctx), savedPlace(ctx)).second

    fun savedPlace(ctx: Context): Place? {
        val p = Prefs.of(ctx)
        val name = p.getString(Prefs.WEATHER_CITY, null) ?: return null
        val lat = p.getString(Prefs.WEATHER_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(Prefs.WEATHER_LON, null)?.toDoubleOrNull() ?: return null
        return Place(name, lat, lon, "")
    }

    /** Saves the chosen city (null = none) and clears the cached forecast. Choosing a city switches the current location off. */
    fun savePlace(ctx: Context, place: Place?) {
        val e = Prefs.of(ctx).edit().remove(Prefs.WEATHER_CACHE)
        if (place != null) e.putBoolean(Prefs.WEATHER_USE_LOCATION, false)
        if (place == null) {
            e.remove(Prefs.WEATHER_CITY).remove(Prefs.WEATHER_LAT).remove(Prefs.WEATHER_LON)
        } else {
            e.putString(Prefs.WEATHER_CITY, place.name).putString(Prefs.WEATHER_LAT, place.lat.toString())
                .putString(Prefs.WEATHER_LON, place.lon.toString())
        }
        e.apply()
    }

    fun cityFromTimeZone(): String = TimeZone.getDefault().id.substringAfterLast('/').replace('_', ' ')

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "MediaHome/1.9 (home-screen weather)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** City search (network). */
    fun search(name: String): List<Place> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        return parsePlaces(get("$GEOCODE?name=$q&count=8&language=$lang&format=json"))
    }

    fun parsePlaces(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val detail = listOf(r.optString("admin1", ""), r.optString("country", "")).filter { it.isNotEmpty() }.joinToString(", ")
            Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"), detail)
        }
    }

    /** Fetches a fresh forecast for the current location (or the city) and caches it. Network; call off the main thread. */
    fun refresh(ctx: Context): WeatherReport? {
        val place = choose(useLocation(ctx), herePlace(ctx), savedPlace(ctx)).first
            ?: search(cityFromTimeZone()).firstOrNull() ?: return cached(ctx)
        val url = "$FORECAST?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=4"
        val json = get(url)
        val now = System.currentTimeMillis()
        val report = parseForecast(place.name, json, now)
        Prefs.of(ctx).edit()
            .putString(Prefs.WEATHER_CACHE, JSONObject().put("place", place.name).put("at", now).put("json", json).toString())
            .apply()
        return report
    }

    fun cached(ctx: Context): WeatherReport? = try {
        Prefs.of(ctx).getString(Prefs.WEATHER_CACHE, null)?.let {
            val o = JSONObject(it)
            parseForecast(o.getString("place"), o.getString("json"), o.getLong("at"))
        }
    } catch (e: Exception) {
        null
    }

    fun parseForecast(place: String, json: String, fetchedAt: Long): WeatherReport {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val time = d.getJSONArray("time")
        val code = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val rain = d.optJSONArray("precipitation_probability_max")
        val days = (0 until time.length()).map { i ->
            DayForecast(
                LocalDate.parse(time.getString(i)), code.optInt(i, 0), max.optDouble(i, 0.0), min.optDouble(i, 0.0),
                rain?.let { if (it.isNull(i)) null else it.optInt(i) },
            )
        }
        return WeatherReport(place, cur.getDouble("temperature_2m"), cur.optInt("weather_code", 0), cur.optInt("is_day", 1) == 1, days, fetchedAt)
    }

    /** Short day name ("Wed", "Th 4", "水") kept on one line (Vietnamese names have a space). */
    fun dayName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replace(' ', '\u00A0')

    fun describe(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_overcast
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67 -> R.string.wx_rain
        in 71..77 -> R.string.wx_snow
        in 80..82 -> R.string.wx_showers
        85, 86 -> R.string.wx_snow_showers
        in 95..99 -> R.string.wx_storm
        else -> R.string.wx_unknown
    }

    fun icon(code: Int, isDay: Boolean = true): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.wx_sun else R.drawable.wx_moon
        2 -> R.drawable.wx_partly
        3 -> R.drawable.wx_cloud
        45, 48 -> R.drawable.wx_fog
        in 51..67, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_storm
        else -> R.drawable.wx_cloud
    }

    fun deg(t: Double): String = "${Math.round(t)}°"
}
