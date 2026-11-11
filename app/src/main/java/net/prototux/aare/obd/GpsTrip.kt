package net.prototux.aare.obd

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The drive as the car's GPS sees it, for the car card when no OBD2 adapter is read. */
data class GpsReadings(
    /** km/h */
    val speed: Float? = null,
    val distanceKm: Float = 0f,
    /** Since the car connected. */
    val elapsedMs: Long = 0,
    /** km/h over the time spent moving, once there is some. */
    val average: Float? = null,
    /** km/h */
    val top: Float? = null,
    /** m */
    val altitude: Float? = null,
    /** Degrees from north, as last seen moving (standing still, a GPS bearing is noise). */
    val bearing: Float? = null,
)

/**
 * A trip computer from the car's GPS alone (2026-11-11): what the car card shows instead of the
 * adapter's readings while there is none (left at home, switched off by the driver, not wanted).
 * Fed every fix by the car session; starts over with each connection.
 */
object GpsTrip {
    private val _readings = MutableStateFlow<GpsReadings?>(null)

    /** Null until the car's first fix. */
    val readings: StateFlow<GpsReadings?> = _readings.asStateFlow()

    private var startMs = 0L
    private var lastMs = 0L
    private var distanceM = 0.0
    private var movingMs = 0L
    private var topKmh = 0f
    private var bearing: Float? = null

    @Synchronized
    fun start() {
        startMs = 0; lastMs = 0; distanceM = 0.0; movingMs = 0; topKmh = 0f; bearing = null
        _readings.value = null
    }

    /** A fix from the car; [nowMs] on the elapsedRealtime clock. */
    @Synchronized
    fun add(fix: Location, nowMs: Long) {
        if (startMs == 0L) startMs = nowMs
        val kmh = if (fix.hasSpeed()) fix.speed * 3.6f else null
        // Gaps (a tunnel, the GPS waking up) count as a few seconds at most, like the OBD trip.
        val delta = if (lastMs == 0L) 0L else (nowMs - lastMs).coerceIn(0L, MAX_GAP_MS)
        lastMs = nowMs
        if (kmh != null && kmh >= MOVING_KMH) {
            distanceM += fix.speed * delta / 1000.0
            movingMs += delta
            if (kmh > topKmh) topKmh = kmh
            if (fix.hasBearing()) bearing = fix.bearing
        }
        _readings.value = GpsReadings(
            speed = kmh,
            distanceKm = (distanceM / 1000).toFloat(),
            elapsedMs = nowMs - startMs,
            average = if (movingMs >= 30_000) (distanceM / movingMs * 3600).toFloat() else null,
            top = topKmh.takeIf { it > 0f },
            altitude = if (fix.hasAltitude()) fix.altitude.toFloat() else null,
            bearing = bearing,
        )
    }

    private const val MAX_GAP_MS = 5_000L
    /** Under this the car is taken as standing (GPS speed wanders around zero). */
    private const val MOVING_KMH = 3f
}
