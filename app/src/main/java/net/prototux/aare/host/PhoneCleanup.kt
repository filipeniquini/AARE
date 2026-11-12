package net.prototux.aare.host

import android.content.Context
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.audio.ShellOnce
import java.io.File
import java.util.concurrent.Executors

/** The phone's memory as a whole (/proc/meminfo), in MB: what's still available and the free swap. */
object PhoneMemory {
    data class Figures(val availableMb: Long, val swapFreeMb: Long)

    fun read(): Figures? = runCatching {
        val values = HashMap<String, Long>()
        File("/proc/meminfo").forEachLine { line ->
            val name = line.substringBefore(':')
            if (name == "MemAvailable" || name == "SwapFree") values[name] = line.substringAfter(':').trim().substringBefore(' ').toLong() / 1024
        }
        Figures(values["MemAvailable"] ?: return null, values["SwapFree"] ?: -1)
    }.getOrNull()
}

/**
 * Lightens the phone for a car session (2026-11-09: with its memory and swap full, the phone
 * spent CPU just moving memory around, and an app picked by the user, Link to Windows, used 8x
 * AARE's CPU in the background). When the car connects: the apps the user picked are paused
 * (force stop) and, if on, the cached background apps ended (am kill-all, as Android does when
 * memory runs short: they keep notifications). When it leaves, the paused apps may run again.
 * Both through Shizuku, off the main thread.
 */
object PhoneCleanup {
    private const val SETTLE_MS = 2_000L
    private const val RETRY_MS = 3_000L
    /** Never paused: AARE, Shizuku (AARE's rights), the system and Google Play services. */
    val NEVER_PAUSED = setOf("net.prototux.aare", "android", "moe.shizuku.privileged.api", "com.google.android.gms", "com.google.android.gsf")
    /** One at a time, in order: a quick reconnection unpauses, then pauses again. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aare-cleanup") }

    /**
     * [inUse]: packages the car session needs now (map card, music), never paused; with the last
     * media and navigation apps they are spared by the clean-up too (a paused player keeps its place).
     */
    fun atConnect(context: Context, settings: Settings, inUse: Set<String>) {
        val keep = inUse + listOfNotNull(settings.lastMediaApp, settings.lastPhoneMedia, settings.lastNavApp)
        val toPause = settings.pauseInCar - inUse - context.packageName
        val clean = settings.cleanUpInCar
        if (toPause.isEmpty() && !clean) return
        val app = context.applicationContext
        worker.execute {
            val before = PhoneMemory.read()
            // Remembered before pausing: if AARE is gone when the car leaves, the next start frees them.
            settings.pausedNow = settings.pausedNow + toPause
            fun run() = ShellOnce.call(app) { shell ->
                val paused = toPause.filter { shell.pauseApp(it, true) }
                paused to (if (clean) shell.killAllBackground(keep.toTypedArray()) else -1)
            }
            // Right after a reconnection the helper wasn't there yet twice (2026-11-11 drive, "no
            // Shizuku" a second after the session started): one more try a moment later.
            val result = run() ?: run { Thread.sleep(RETRY_MS); run() }
            if (result == null) { DiagLog.add("phone clean-up: no Shizuku"); return@execute }
            Thread.sleep(SETTLE_MS)
            val after = PhoneMemory.read()
            val (paused, cleaned) = result
            val freed = if (before != null && after != null) after.availableMb - before.availableMb else null
            DiagLog.add("phone clean-up: ${if (cleaned >= 0) "$cleaned background apps ended (kept ${keep.joinToString()})" else "no clean-up"}" +
                (if (paused.isNotEmpty()) ", paused ${paused.joinToString()}" else "") +
                (if (before != null && after != null) "; available ${before.availableMb}→${after.availableMb} MB (${if (freed!! >= 0) "+" else ""}$freed), swap free ${before.swapFreeMb}→${after.swapFreeMb} MB" else ""))
        }
    }

    /** The car left (or AARE starts again after it was gone): the paused apps may run again. */
    fun atDisconnect(context: Context, settings: Settings) {
        val paused = settings.pausedNow
        if (paused.isEmpty()) return
        val app = context.applicationContext
        worker.execute {
            val done = ShellOnce.call(app) { shell -> paused.filter { shell.pauseApp(it, false) } }
            if (done != null) settings.pausedNow = settings.pausedNow - done.toSet()
            DiagLog.add("phone clean-up: running again ${done?.joinToString() ?: "(no Shizuku)"}")
        }
    }
}
