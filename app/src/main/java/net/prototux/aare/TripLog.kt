package net.prototux.aare

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every car trip's logs, kept for later: a folder per trip under the app's external files
 * (`Android/data/net.prototux.aare/files/viagens/2026-11-14_08h12/`), never deleted by the app.
 * In it, everything AARE writes to logcat (all its tags and levels, the protocol trace included),
 * in parts of [PART_MS] (`2026-11-14_08h12_parte02_08h42.log`), the performance table
 * (`..._desempenho.csv`) and a crash's stack trace. A session starting within [SAME_TRIP_MS] of the
 * last one's end (a wireless drop, a quick stop) carries on in the same folder.
 *
 * The user asked for it (2026-11-14): small problems on the road are forgotten by the time they
 * get home. Read with `adb pull /sdcard/Android/data/net.prototux.aare/files/viagens`.
 */
object TripLog {
    private const val TAG = "AareTrip"
    private const val PART_MS = 30 * 60_000L
    private const val SAME_TRIP_MS = 5 * 60_000L
    /** Stops writing (and says so) below this much free space; old trips are never deleted. */
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024
    /** How far back logcat is read at the start: the wireless search and the connection before the session. */
    private const val LEAD_MS = 60_000L

    private val folderName = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.US)
    private val hourName = SimpleDateFormat("HH'h'mm", Locale.US)
    private val logcatSince = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)

    private var dir: File? = null
    private var reader: Thread? = null
    private var logcat: java.lang.Process? = null
    private var out: Writer? = null
    private var part = 0
    private var partStarted = 0L
    @Volatile private var full = false

    /** The folder holding every trip's folder. */
    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "viagens")

    /** The current trip's folder, if a car session is on. */
    val current: File? @Synchronized get() = dir

    /** A car session starts (any thread): opens or continues the trip and starts copying logcat. */
    @Synchronized
    fun begin(context: Context, how: String) {
        if (reader != null) return
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val root = root(app).apply { mkdirs() }
        // A folder's own date changes only when a file is added: its newest file's says when it was last written.
        fun written(f: File) = f.listFiles()?.maxOfOrNull { it.lastModified() } ?: f.lastModified()
        val last = root.listFiles { f -> f.isDirectory }?.maxByOrNull { written(it) }
        val again = last != null && now - written(last) < SAME_TRIP_MS
        val d = if (again) last!! else File(root, folderName.format(Date(now))).apply { mkdirs() }
        dir = d
        full = false
        part = 0
        // Back within the same trip: on in its last part while that's under [PART_MS] old.
        val lastPart = if (again) d.listFiles { f -> f.name.endsWith(".log") }?.maxByOrNull { it.name } else null
        val lastStart = lastPart?.let { partStart(it.name, now) }
        if (lastPart != null && lastStart != null && now - lastStart < PART_MS) {
            part = Regex("""_parte(\d+)_""").find(lastPart.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            partStarted = lastStart
            out = runCatching { java.io.FileWriter(lastPart, true).buffered() }.getOrNull()
        } else {
            part = d.listFiles { f -> f.name.endsWith(".log") }?.size ?: 0
            openPart(now)
        }
        line(if (again) "=== AARE reconnected ($how), same trip ===" else "=== Trip started ($how) ===")
        if (part == 1 && !again) header(app)
        // Our own process's lines only (no permission needed), from a little before now.
        val since = logcatSince.format(Date(now - if (again) 0 else LEAD_MS))
        val p = runCatching {
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-T", since)
                .redirectErrorStream(true).start()
        }.onFailure { Log.w(TAG, "can't read logcat", it) }.getOrNull()
        logcat = p
        if (p == null) { line("(logcat not readable: only the crash and performance files)"); return }
        reader = Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { copy(it) }
            }
        }, "aare-trip-log").apply { isDaemon = true; start() }
    }

    /** The session ended (any thread): a closing line, then logcat is stopped once the teardown's lines are in. */
    fun end(reason: String) {
        synchronized(this) {
            if (dir == null) return
            line("=== Session ended: $reason ===")
        }
        Thread({
            Thread.sleep(2_000)
            synchronized(this) {
                logcat?.destroy(); logcat = null
                reader = null
                runCatching { out?.close() }; out = null
                dir = null
            }
        }, "aare-trip-end").start()
    }

    /** A line of the performance table (from [PerfLog]). */
    @Synchronized
    fun perf(text: String) {
        val d = dir ?: return
        if (full) return
        runCatching { File(d, "${d.name}_desempenho.csv").appendText(text) }
    }

    /** AARE is crashing: the stack trace written now, before the process dies, and also to a file of all crashes. */
    fun crash(thread: Thread, error: Throwable) {
        val text = "${stamp.format(Date())}  CRASH in ${thread.name}: ${Log.getStackTraceString(error)}"
        runCatching {
            synchronized(this) {
                out?.let { it.write("\n$text\n"); it.flush() }
                dir?.let { File(it, "${it.name}_falha.txt").appendText(text + "\n") }
            }
        }
        runCatching { File(root(AareApp.instance).apply { mkdirs() }, "falhas.txt").appendText(text + "\n") }
    }

    @Synchronized
    private fun copy(text: String) {
        val now = System.currentTimeMillis()
        if (now - partStarted >= PART_MS) openPart(now)
        val w = out ?: return
        if (full) return
        runCatching { w.write(text); w.write("\n") }
        // Flushed by line: a crash or a kill must not lose the last minutes.
        runCatching { w.flush() }
    }

    /** When a part began, from its name's time (`..._parte02_08h42.log`): today, or yesterday past midnight. */
    private fun partStart(name: String, now: Long): Long? {
        val m = Regex("""_(\d{2})h(\d{2})\.log$""").find(name) ?: return null
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > now + 60_000) c.add(java.util.Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    private fun line(text: String) {
        val w = out ?: return
        runCatching { w.write("${stamp.format(Date())}  $text\n"); w.flush() }
    }

    private fun openPart(now: Long) {
        val d = dir ?: return
        runCatching { out?.close() }
        out = null
        partStarted = now
        if (d.usableSpace < MIN_FREE_BYTES) {
            if (!full) Log.w(TAG, "phone almost full: trip log stopped")
            full = true
            return
        }
        part++
        val f = File(d, "%s_parte%02d_%s.log".format(Locale.US, d.name, part, hourName.format(Date(now))))
        out = runCatching { java.io.FileWriter(f, true).buffered() }.getOrNull()
        if (part > 1) line("=== Part $part of the trip ${d.name} ===")
    }

    /** What's needed to read the logs later: the phone, the app, the settings (nothing secret). */
    private fun header(context: Context) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        line("phone ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), AARE $version")
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        val secret = Regex("key|pin|cert|token|pass|secret|address|mac", RegexOption.IGNORE_CASE)
        prefs.keys.sorted().filterNot { secret.containsMatchIn(it) }.forEach { k ->
            line("setting $k = ${prefs[k].toString().replace('\n', '|').take(160)}")
        }
    }
}
