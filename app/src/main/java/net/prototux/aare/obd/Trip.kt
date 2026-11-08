package net.prototux.aare.obd

/**
 * The drive so far, for the range: distance and fuel used (from the estimated economy), and the
 * last drive's average, kept so the range shows from the start of the next one.
 * Ported from the user's dongle launcher (`Viagem.java`).
 */
class Trip(
    /** The last drive's average km/l, 0 if none yet. */
    var savedAverage: Float = 0f,
    private val tankLitres: Float = POLO_GTS_TANK_L,
) {
    var distanceKm = 0f
        private set
    var litres = 0f
        private set
    private var lastMs = 0L

    /** Average km/l over this drive, once it's long enough to mean something. */
    fun average(): Float? = if (distanceKm >= 0.3f && litres > 0.01f) distanceKm / litres else null

    fun rangeKm(fuelPercent: Float?): Float? {
        val avg = average() ?: savedAverage.takeIf { it > 0f } ?: return null
        val fuel = fuelPercent ?: return null
        return fuel / 100f * tankLitres * avg
    }

    fun add(r: ObdReadings, nowMs: Long) {
        val delta = if (lastMs == 0L) 0L else (nowMs - lastMs).coerceIn(0L, 5_000L)
        lastMs = nowMs
        val hours = delta / 3_600_000f
        val speed = r.speed ?: 0f
        distanceKm += speed * hours
        val economy = r.economy
        if (economy != null && economy > 0f) litres += speed / economy * hours // l/h = km/h ÷ km/l
        else if (r.rpm != null && r.rpm > 400f) litres += IDLE_LITRES_PER_HOUR * hours
    }

    companion object {
        const val POLO_GTS_TANK_L = 52f
        /** Litres per hour standing with the engine running (approximate). */
        const val IDLE_LITRES_PER_HOUR = 0.7f
    }
}
