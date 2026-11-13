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
        val aodOff = settings.aodOffInCar
        if (toPause.isEmpty() && !clean && !aodOff) return
        val app = context.applicationContext
        worker.execute {
            val before = PhoneMemory.read()
            // Remembered before pausing: if AARE is gone when the car leaves, the next start frees them.
            settings.pausedNow = settings.pausedNow + toPause
            // The same for the always-on display: owed back before it is turned off.
            if (aodOff) settings.aodTurnedOff = true
            // One helper call for all: two at once (or back to back) lost one (2026-11-12 test).
            fun run() = ShellOnce.call(app) { shell ->
                val paused = toPause.filter { shell.pauseApp(it, true) }
                val aod = if (aodOff) shell.setAlwaysOnDisplay(false) else null
                Triple(paused, if (clean) shell.killAllBackground(keep.toTypedArray()) else -1, aod)
            }
            // Right after a reconnection the helper wasn't there yet twice (2026-11-11 drive, "no
            // Shizuku" a second after the session started): one more try a moment later.
            val first = run()
            val result = first ?: run { Thread.sleep(RETRY_MS); run() }
            // Not owed when it was off already, as the first answer says (an unanswered first try
            // may have turned it off itself: then it stays owed).
            if (aodOff && first != null && first.third != "true") settings.aodTurnedOff = false
            if (result == null) { DiagLog.add("phone clean-up: no Shizuku"); return@execute }
            if (aodOff) DiagLog.add("always-on display: " + when (result.third) {
                "true" -> "off while the car is connected"
                null -> "can't turn it off"
                else -> "was off already"
            })
            if (toPause.isEmpty() && !clean) return@execute
            Thread.sleep(SETTLE_MS)
            val after = PhoneMemory.read()
            val (paused, cleaned) = result
            val freed = if (before != null && after != null) after.availableMb - before.availableMb else null
            DiagLog.add("phone clean-up: ${if (cleaned >= 0) "$cleaned background apps ended (kept ${keep.joinToString()})" else "no clean-up"}" +
                (if (paused.isNotEmpty()) ", paused ${paused.joinToString()}" else "") +
                (if (before != null && after != null) "; available ${before.availableMb}→${after.availableMb} MB (${if (freed!! >= 0) "+" else ""}$freed), swap free ${before.swapFreeMb}→${after.swapFreeMb} MB" else ""))
        }
    }

    /**
     * The car left (or AARE starts again after it was gone): the paused apps may run again, and
     * the always-on display comes back if AARE turned it off (see [Settings.aodOffInCar]).
     */
    fun atDisconnect(context: Context, settings: Settings) {
        val paused = settings.pausedNow
        val aodBack = settings.aodTurnedOff
        if (paused.isEmpty() && !aodBack && settings.nightModeBefore == null) return
        val app = context.applicationContext
        worker.execute {
            // Read here, in order: a theme change queued just before may have saved it.
            val themeBack = settings.nightModeBefore
            fun run() = ShellOnce.call(app) { shell ->
                Triple(paused.filter { shell.pauseApp(it, false) }, aodBack && shell.setAlwaysOnDisplay(true) != null,
                    themeBack != null && shell.setNightMode(themeBack))
            }
            val result = run() ?: run { Thread.sleep(RETRY_MS); run() }
            if (result != null) settings.pausedNow = settings.pausedNow - result.first.toSet()
            if (result?.second == true) settings.aodTurnedOff = false
            if (result?.third == true) settings.nightModeBefore = null
            if (paused.isNotEmpty()) DiagLog.add("phone clean-up: running again ${result?.first?.joinToString() ?: "(no Shizuku)"}")
            if (aodBack) DiagLog.add("always-on display: ${if (result?.second == true) "on again" else "can't turn it back on, next time"}")
            if (themeBack != null) DiagLog.add("phone theme: ${if (result?.third == true) "back to night mode $themeBack" else "can't put it back, next time"}")
        }
    }

    /**
     * The car screen went light or dark: the phone's mode the same, so the mirrored map apps
     * follow (see [Settings.themeFollowsCar]). The phone's own mode is saved first, once, and
     * given back when the car leaves ([atDisconnect]), even after a crash.
     */
    fun phoneTheme(context: Context, settings: Settings, night: Boolean) {
        val app = context.applicationContext
        worker.execute {
            val mode = if (night) "yes" else "no"
            fun run() = ShellOnce.call(app) { shell ->
                // Read before the first change (read only: a retry can't save AARE's own choice).
                if (settings.nightModeBefore == null) {
                    val now = shell.nightMode()
                    // A schedule of the user's own ("custom") can't be put back the same way:
                    // then the phone's mode is left alone.
                    if (now !in setOf("yes", "no", "auto")) return@call false
                    settings.nightModeBefore = now
                }
                shell.setNightMode(mode)
            }
            val done = run() ?: run { Thread.sleep(RETRY_MS); run() }
            DiagLog.add("phone theme: ${if (done == true) "night mode $mode, as the car screen" else "can't change it"}")
        }
    }
}
