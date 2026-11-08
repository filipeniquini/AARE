package net.prototux.aare.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings

/**
 * Which apps open natively on the car (their Android Auto side: Car App Library screens or a
 * media browser) and which refuse AARE, so they open mirrored on the virtual display instead,
 * like Google Maps in the GTS card. The user's rule (2026-11-08): native wherever possible, as in
 * Android Auto; mirrored otherwise.
 *
 * A result is kept per app version: an update may change its mind, so it is tried again.
 * Only a refusal counts (the app rejected AARE as its host, or needs a newer car API): an app
 * that was just slow or crashed once keeps its native side.
 */
object NativeCompat {
    enum class Result { NATIVE, REFUSED }

    private val _changes = MutableStateFlow(0)
    /** Bumped whenever a result changes (the car's app list is rebuilt then). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun result(context: Context, pkg: String): Result? {
        val key = key(context, pkg) ?: return null
        val entry = Settings(context).nativeResults.firstOrNull { it.substringBeforeLast('=') == key } ?: return null
        return runCatching { Result.valueOf(entry.substringAfterLast('=')) }.getOrNull()
    }

    fun refused(context: Context, pkg: String): Boolean = result(context, pkg) == Result.REFUSED

    /** Whether [pkg] has been tried at its installed version. */
    fun known(context: Context, pkg: String): Boolean = result(context, pkg) != null

    /** The app opened natively. */
    fun worked(context: Context, pkg: String, label: String) = record(context, pkg, Result.NATIVE, "native: $label opens natively")

    /** The app refused AARE as its host: it will open mirrored. */
    fun refusedNow(context: Context, pkg: String, label: String, why: String) =
        record(context, pkg, Result.REFUSED, "native: $label refused ($why), opens mirrored")

    private fun record(context: Context, pkg: String, result: Result, log: String) {
        val key = key(context, pkg) ?: return
        val settings = Settings(context)
        val old = settings.nativeResults
        val updated = old.filterNot { it.substringBeforeLast('=').substringBeforeLast('@') == pkg }.toMutableSet()
        updated += "$key=${result.name}"
        if (updated == old) return
        settings.nativeResults = updated
        DiagLog.add(log)
        _changes.value++
    }

    private fun key(context: Context, pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        "$pkg@${info.longVersionCode}"
    }.getOrNull() // not installed (any more)
}
