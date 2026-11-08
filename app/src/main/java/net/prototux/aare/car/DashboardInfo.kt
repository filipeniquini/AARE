package net.prototux.aare.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** The weather where the car is, for the rail, with the next hours for the GTS car card. */
data class Weather(val temperature: Int, val unit: String, val code: Int, val day: Boolean, val forecast: List<HourForecast> = emptyList())

/** The forecast for one hour of the day (0 to 23, local time). */
data class HourForecast(val hour: Int, val temperature: Int, val code: Int, val day: Boolean)

/** The next calendar event, for the dashboard. */
data class NextEvent(val title: String, val startMs: Long, val allDay: Boolean, val location: String)

/**
 * What Android Auto shows around the map: the weather and the next calendar event. The weather
 * comes from Open-Meteo (no account, no Google), asked for an approximate position only.
 */
object DashboardInfo {
    private const val TAG = "AareDashboard"

    /** Blocking: call off the main thread. */
    fun weather(location: Location): Weather? = runCatching {
        // ~10 km is plenty for the weather, and says less about where the car is.
        val lat = "%.1f".format(Locale.ROOT, location.latitude)
        val lon = "%.1f".format(Locale.ROOT, location.longitude)
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&forecast_hours=$FORECAST_HOURS&timezone=auto" + if (fahrenheit) "&temperature_unit=fahrenheit" else "")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        try {
            parseWeather(c.inputStream.bufferedReader().use { it.readText() }, fahrenheit)
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.i(TAG, "weather: ${it.message}") }.getOrNull()

    /** Hours ahead the forecast asks for, and the steps the car card shows (now +3 h, +6 h, +9 h). */
    private const val FORECAST_HOURS = 12
    private val FORECAST_STEPS = listOf(3, 6, 9)

    /** Open-Meteo's answer to [Weather]; the hourly list starts at the current hour. */
    internal fun parseWeather(json: String, fahrenheit: Boolean): Weather {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val forecast = root.optJSONObject("hourly")?.let { h ->
            val times = h.getJSONArray("time")
            val temps = h.getJSONArray("temperature_2m")
            val codes = h.getJSONArray("weather_code")
            val days = h.optJSONArray("is_day")
            FORECAST_STEPS.filter { it < times.length() }.map { i ->
                // "2026-11-08T22:00": the hour is local time (timezone=auto).
                val hour = times.getString(i).substringAfter('T').substringBefore(':').toIntOrNull() ?: 0
                HourForecast(hour, Math.round(temps.getDouble(i)).toInt(), codes.getInt(i), days?.optInt(i, 1) != 0)
            }
        }.orEmpty()
        return Weather(Math.round(current.getDouble("temperature_2m")).toInt(), if (fahrenheit) "°F" else "°", current.getInt("weather_code"),
            current.optInt("is_day", 1) == 1, forecast)
    }

    /** The phone's last known position, when the car doesn't send one. */
    @SuppressLint("MissingPermission")
    fun phoneLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** The next event in the coming 12 hours (timed events first), or null. Blocking. */
    fun nextEvent(context: Context): NextEvent? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000)
            ContentUris.appendId(it, now + 12 * 3_600_000)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.ALL_DAY} ASC, ${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                if (c.moveToFirst()) NextEvent(c.getString(0).orEmpty().ifBlank { "Event" }, c.getLong(1), c.getInt(2) == 1, c.getString(3).orEmpty()) else null
            }
        }.getOrNull()
    }
}
