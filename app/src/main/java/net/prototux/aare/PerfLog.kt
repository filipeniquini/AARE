package net.prototux.aare

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Performance during a car session, every [PERIOD_MS], as a spreadsheet (perf.csv in the app's
 * files): the car session's own figures (video, audio, link, OBD) next to the phone's (CPU,
 * memory, temperature, battery, thermal state) and how late the main thread ran (the car UI and
 * the touch handling live there). The connection log keeps the events; this keeps the numbers of
 * a whole drive, to see later what to optimize. Read it with `run-as net.prototux.aare cat files/perf.csv`.
 */
object PerfLog {
    private const val PERIOD_MS = 5_000L
    private const val PING_MS = 200L
    private const val MAX_BYTES = 4L * 1024 * 1024

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts sampling for one car session; [session] gives that session's figures, already as
     * totals or current values (this turns totals ending in "_total" into per-period deltas).
     */
    @Synchronized
    fun start(context: Context, car: String, session: () -> Map<String, Any?>) {
        stop()
        val app = context.applicationContext
        val file = File(app.filesDir, "perf.csv")
        if (file.length() > MAX_BYTES) runCatching { file.renameTo(File(app.filesDir, "perf-old.csv")) }
        val t = HandlerThread("aare-perf", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        val sampler = Sampler(app, file, car, session, h)
        h.post { sampler.begin() }
        main.post(sampler.ping)
        h.postDelayed(sampler, PERIOD_MS)
        DiagLog.add("perf: a line every ${PERIOD_MS / 1000} s in perf.csv")
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        handler?.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(PING_TOKEN)
        t.quitSafely()
        thread = null; handler = null
    }

    private val PING_TOKEN = Any()

    private class Sampler(
        private val context: Context,
        private val file: File,
        private val car: String,
        private val session: () -> Map<String, Any?>,
        private val handler: Handler,
    ) : Runnable {
        private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
        private var header: List<String>? = null
        private val lastTotals = HashMap<String, Double>()
        private var lastCpuMs = Process.getElapsedCpuTime()
        private var lastWallMs = SystemClock.elapsedRealtime()
        private val startedMs = SystemClock.elapsedRealtime()

        // Main thread lateness: a ping every PING_MS, how much later than asked it ran.
        @Volatile private var lagMaxMs = 0L
        @Volatile private var lagSumMs = 0L
        @Volatile private var lagCount = 0
        @Volatile private var lagOver100 = 0
        private var pingDue = 0L
        val ping: Runnable = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (pingDue != 0L) {
                    val late = (now - pingDue).coerceAtLeast(0)
                    if (late > lagMaxMs) lagMaxMs = late
                    lagSumMs += late; lagCount++
                    if (late > 100) lagOver100++
                }
                pingDue = now + PING_MS
                main.postAtTime(this, PING_TOKEN, pingDue)
            }
        }

        fun begin() = append("# ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())} session with $car, ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")

        override fun run() {
            runCatching { write(sample()) }.onFailure { android.util.Log.w("AarePerf", "sample", it) }
            handler.postDelayed(this, PERIOD_MS)
        }

        private fun sample(): LinkedHashMap<String, Any?> {
            val row = LinkedHashMap<String, Any?>()
            val wall = SystemClock.elapsedRealtime()
            row["time"] = stamp.format(Date())
            row["s"] = (wall - startedMs) / 1000
            // Phone: AARE's CPU (percent of one core), memory, battery, heat.
            val cpu = Process.getElapsedCpuTime()
            row["cpu_pct"] = if (wall > lastWallMs) (cpu - lastCpuMs) * 100 / (wall - lastWallMs) else 0
            lastCpuMs = cpu; lastWallMs = wall
            val rt = Runtime.getRuntime()
            row["heap_mb"] = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            row["native_mb"] = Debug.getNativeHeapAllocatedSize() / 1_048_576
            row["pss_mb"] = Debug.getPss() / 1024
            // The whole phone's memory: with it full, Android spends CPU (and heat) swapping.
            val phone = net.prototux.aare.host.PhoneMemory.read()
            row["phone_avail_mb"] = phone?.availableMb
            row["phone_swap_free_mb"] = phone?.swapFreeMb
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            row["batt_pct"] = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            row["batt_c"] = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10.0 }
            row["plugged"] = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val bm = context.getSystemService(BatteryManager::class.java)
            row["batt_ma"] = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { it / 1000 }
            val pm = context.getSystemService(PowerManager::class.java)
            row["thermal"] = pm?.currentThermalStatus
            row["headroom"] = if (Build.VERSION.SDK_INT >= 30) pm?.getThermalHeadroom(10)?.takeIf { !it.isNaN() }?.let { "%.2f".format(Locale.US, it) } else null
            // The main thread (car UI, touches): how late it ran.
            row["main_lag_avg_ms"] = if (lagCount > 0) lagSumMs / lagCount else 0
            row["main_lag_max_ms"] = lagMaxMs
            row["main_lag_over100"] = lagOver100
            lagMaxMs = 0; lagSumMs = 0; lagCount = 0; lagOver100 = 0
            // The session's figures; totals become per-period counts.
            for ((k, v) in runCatching { session() }.getOrDefault(emptyMap())) {
                if (k.endsWith("_total") && v is Number) {
                    val now = v.toDouble()
                    val before = lastTotals.put(k, now)
                    row[k.removeSuffix("_total")] = if (before == null || now < before) 0 else (now - before).toLong()
                } else row[k] = v
            }
            return row
        }

        private fun write(row: Map<String, Any?>) {
            val keys = row.keys.toList()
            val sb = StringBuilder()
            // A new header whenever the columns change (an audio stream started, the car went away).
            if (keys != header) { header = keys; sb.append(keys.joinToString(",")).append('\n') }
            sb.append(row.values.joinToString(",") { v -> v?.toString()?.replace(',', ';') ?: "" }).append('\n')
            append(sb.toString())
        }

        private fun append(text: String) {
            runCatching { file.appendText(text) }
        }
    }
}
