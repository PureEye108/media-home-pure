package app.mediahome.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherTest {
    @Test fun parsesOpenMeteoForecast() {
        val r = Weather.parseForecast("Ho Chi Minh City", SampleWeather.FORECAST, 123L)
        assertEquals(31.0, r.temp, 0.01)
        assertEquals(2, r.code)
        assertEquals(4, r.days.size)
        assertEquals(95, r.days[3].code)
        assertEquals(80, r.days[1].rainChance)
        assertEquals(R.string.wx_storm, Weather.describe(95))
        assertEquals(R.drawable.wx_rain, Weather.icon(80))
        assertEquals("31°", Weather.deg(30.6))
    }

    @Test fun parsesGeocodingResults() {
        val p = Weather.parsePlaces(SampleWeather.GEOCODE)
        assertEquals("Ho Chi Minh City", p[0].name)
        assertTrue(p[0].detail.contains("Vietnam"))
    }
}

/** 1.9: the weather follows the tablet's current location by default; a chosen city or the time zone are the fallbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherPlaceTest {
    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val danang = Place("Da Nang", 16.05, 108.2, "")
    private val hanoi = Place("Hanoi", 21.03, 105.85, "")

    @Test fun ruleCurrentLocationFirstThenCityThenTimeZone() {
        assertEquals(danang to WeatherSource.LOCATION, Weather.choose(true, danang, hanoi))
        assertEquals(hanoi to WeatherSource.CITY, Weather.choose(true, null, hanoi))      // location off / not allowed / not found yet
        assertEquals(hanoi to WeatherSource.CITY, Weather.choose(false, danang, hanoi))   // a fixed city was chosen
        assertEquals(null to WeatherSource.TIME_ZONE, Weather.choose(true, null, null))
    }

    @Test fun roundedToAboutOneKilometre() {
        assertEquals(16.05, Locator.round(16.05439), 1e-9)
        assertEquals(108.21, Locator.round(108.20871), 1e-9)
        assertEquals(-33.87, Locator.round(-33.8688), 1e-9)
        assertTrue(Locator.moved(null, null, 16.05, 108.2))
        assertTrue(!Locator.moved(16.05, 108.20, 16.06, 108.21))        // about 1.5 km: same place, same name
        assertTrue(Locator.moved(16.05, 108.20, 16.08, 108.20))         // about 3 km: new place
    }

    @Test fun currentLocationIsTheDefaultAndChoosingACityTurnsItOff() {
        Prefs.of(ctx).edit().clear().commit()
        assertTrue(Weather.useLocation(ctx))
        Weather.savePlace(ctx, hanoi)
        assertTrue(!Weather.useLocation(ctx))
        assertEquals(WeatherSource.CITY, Weather.source(ctx))
        Weather.setUseLocation(ctx, true)
        assertEquals(WeatherSource.CITY, Weather.source(ctx))           // no location found yet: the city meanwhile
        Weather.saveHere(ctx, 16.05, 108.2, "Da Nang")
        assertEquals(WeatherSource.LOCATION, Weather.source(ctx))
        assertEquals("Da Nang", Weather.herePlace(ctx)!!.name)
    }

    @Test fun movingDropsTheOldForecastButStayingKeepsIt() {
        Prefs.of(ctx).edit().clear().commit()
        assertTrue(Weather.saveHere(ctx, 16.05, 108.2, "Da Nang"))
        Prefs.of(ctx).edit().putString(Prefs.WEATHER_CACHE, "x").commit()
        assertTrue(!Weather.saveHere(ctx, 16.06, 108.2, null))          // a few hundred metres: nothing changes
        assertEquals("x", Prefs.of(ctx).getString(Prefs.WEATHER_CACHE, null))
        assertEquals("Da Nang", Weather.herePlace(ctx)!!.name)
        assertTrue(!Weather.needsName(ctx, 16.06, 108.2))
        assertTrue(Weather.saveHere(ctx, 21.03, 105.85, null))          // carried to Hanoi
        assertEquals(null, Prefs.of(ctx).getString(Prefs.WEATHER_CACHE, null))
        assertTrue(Weather.needsName(ctx, 21.03, 105.85))
        assertEquals(ctx.getString(R.string.wx_here_unnamed), Weather.herePlace(ctx)!!.name)
    }

    @Test fun screenSaverTimes() {
        assertEquals("0:00", Saver.time(0)); assertEquals("3:07", Saver.time(187_400)); assertEquals("1:02:03", Saver.time(3_723_000))
        assertEquals("0:00", Saver.time(-5))
    }
}

/** Sample responses in Open-Meteo's documented format (values are illustrative). */
object SampleWeather {
    const val FORECAST = """{"timezone":"Asia/Ho_Chi_Minh","current":{"time":"2026-11-10T09:00","interval":900,"temperature_2m":31.0,"weather_code":2,"is_day":1},
      "daily":{"time":["2026-11-10","2026-11-11","2026-11-12","2026-11-13"],"weather_code":[2,80,3,95],
      "temperature_2m_max":[33.1,32.4,31.8,30.9],"temperature_2m_min":[24.6,24.9,25.2,24.8],"precipitation_probability_max":[20,80,35,90]}}"""
    const val GEOCODE = """{"results":[{"name":"Ho Chi Minh City","latitude":10.82302,"longitude":106.62965,"country":"Vietnam","admin1":"Ho Chi Minh City"}]}"""
}
