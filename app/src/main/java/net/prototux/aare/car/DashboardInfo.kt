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

/**
 * The weather where the car is, for the rail, with the next hours for the GTS car card ([forecast]:
 * +3, +6, +9 h) and the clock card ([hours]: every hour of the next 12; [low], [high] and [rain],
 * the day's lowest and highest and its chance of rain in %, when Open-Meteo gave them).
 */
data class Weather(
    val temperature: Int, val unit: String, val code: Int, val day: Boolean,
    val forecast: List<HourForecast> = emptyList(),
    val hours: List<HourForecast> = emptyList(),
    val low: Int? = null, val high: Int? = null, val rain: Int? = null,
)

/** The forecast for one hour of the day (0 to 23, local time), with its chance of rain in % if known. */
data class HourForecast(val hour: Int, val temperature: Int, val code: Int, val day: Boolean, val rain: Int? = null)

/** The next calendar event, for the dashboard. */
data class NextEvent(val title: String, val startMs: Long, val allDay: Boolean, val location: String)

/** The country's next public holiday: its local name and day ("2026-11-15"). */
data class Holiday(val name: String, val date: java.time.LocalDate)

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
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1" +
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
        // Every hour after the current one (index 0 is the current hour).
        val hours = root.optJSONObject("hourly")?.let { h ->
            val times = h.getJSONArray("time")
            val temps = h.getJSONArray("temperature_2m")
            val codes = h.getJSONArray("weather_code")
            val days = h.optJSONArray("is_day")
            val rain = h.optJSONArray("precipitation_probability")
            (1 until times.length()).map { i ->
                // "2026-11-08T22:00": the hour is local time (timezone=auto).
                val hour = times.getString(i).substringAfter('T').substringBefore(':').toIntOrNull() ?: 0
                HourForecast(hour, Math.round(temps.getDouble(i)).toInt(), codes.getInt(i), days?.optInt(i, 1) != 0,
                    rain?.takeIf { !it.isNull(i) }?.optInt(i))
            }
        }.orEmpty()
        val daily = root.optJSONObject("daily")
        fun day(key: String) = daily?.optJSONArray(key)?.takeIf { it.length() > 0 && !it.isNull(0) }?.let { Math.round(it.getDouble(0)).toInt() }
        return Weather(Math.round(current.getDouble("temperature_2m")).toInt(), if (fahrenheit) "°F" else "°", current.getInt("weather_code"),
            current.optInt("is_day", 1) == 1, FORECAST_STEPS.mapNotNull { hours.getOrNull(it - 1) }, hours,
            day("temperature_2m_min"), day("temperature_2m_max"), day("precipitation_probability_max"))
    }

    /**
     * The next national public holiday of [country] (ISO code, the phone's region), from Nager.Date
     * (no account; it gets only the country). Regional holidays are left out. Blocking.
     */
    fun nextHoliday(country: String): Holiday? = runCatching {
        if (!Regex("[A-Z]{2}").matches(country)) return null
        val c = URL("https://date.nager.at/api/v3/NextPublicHolidays/$country").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        try {
            parseHoliday(c.inputStream.bufferedReader().use { it.readText() }, java.time.LocalDate.now())
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.i(TAG, "holiday: ${it.message}") }.getOrNull()

    /** Nager.Date's list to the first national holiday from [today] on. */
    internal fun parseHoliday(json: String, today: java.time.LocalDate): Holiday? {
        val list = org.json.JSONArray(json)
        for (i in 0 until list.length()) {
            val h = list.getJSONObject(i)
            if (!h.optBoolean("global", true)) continue
            val date = runCatching { java.time.LocalDate.parse(h.getString("date")) }.getOrNull() ?: continue
            if (date.isBefore(today)) continue
            return Holiday(h.optString("localName").ifBlank { h.optString("name") }, date)
        }
        return null
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
    fun nextEvent(context: Context): NextEvent? = upcomingEvents(context, 1).firstOrNull()

    /**
     * Up to [max] events in the coming [hours] (12: all-day ones first, then by time; longer: by
     * time only, so a holiday weeks away doesn't come before tomorrow's meeting). Blocking.
     */
    fun upcomingEvents(context: Context, max: Int, hours: Int = 12): List<NextEvent> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000)
            ContentUris.appendId(it, now + hours * 3_600_000L)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION),
                "${CalendarContract.Instances.VISIBLE} = 1", null,
                if (hours <= 12) "${CalendarContract.Instances.ALL_DAY} ASC, ${CalendarContract.Instances.BEGIN} ASC" else "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val events = ArrayList<NextEvent>()
                while (events.size < max && c.moveToNext()) {
                    events += NextEvent(c.getString(0).orEmpty().ifBlank { "Event" }, c.getLong(1), c.getInt(2) == 1, c.getString(3).orEmpty())
                }
                events
            }
        }.getOrNull().orEmpty()
    }
}
