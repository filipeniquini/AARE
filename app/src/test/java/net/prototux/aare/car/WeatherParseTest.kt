package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** Open-Meteo's answer, with the hourly list starting at the current hour. */
class WeatherParseTest {
    @Test
    fun currentAndTheNextHours() {
        val hours = (0 until 12).map { "\"2026-11-08T%02d:00\"".format((19 + it) % 24) }.joinToString(",")
        val temps = (0 until 12).map { 21 - it * 0.4 }.joinToString(",")
        val codes = (0 until 12).map { if (it == 3) 80 else 3 }.joinToString(",")
        val days = (0 until 12).map { 0 }.joinToString(",")
        val json = """{"current":{"temperature_2m":21.3,"weather_code":80,"is_day":0},
            "hourly":{"time":[$hours],"temperature_2m":[$temps],"weather_code":[$codes],"is_day":[$days]}}"""
        val w = DashboardInfo.parseWeather(json, fahrenheit = false)
        assertEquals(21, w.temperature)
        assertEquals(listOf(22, 1, 4), w.forecast.map { it.hour })
        assertEquals(listOf(20, 19, 17), w.forecast.map { it.temperature })
        assertEquals(80, w.forecast[0].code)
    }

    @Test
    fun noHourlyStillGivesTheCurrentWeather() {
        val w = DashboardInfo.parseWeather("""{"current":{"temperature_2m":18.6,"weather_code":0}}""", fahrenheit = false)
        assertEquals(19, w.temperature)
        assertEquals(0, w.forecast.size)
    }
}
