package net.prototux.aare.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.prototux.aare.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Google's Android Auto app answers the car before AARE does, so it has to be off while AARE is
 * used in the car. This switches it off and back on through Shizuku, instead of the phone's
 * Settings > Apps.
 */
object AndroidAutoSwitch {
    const val PACKAGE = "com.google.android.projection.gearhead"

    enum class State { NOT_INSTALLED, ENABLED, DISABLED }

    fun state(context: Context): State {
        val info = runCatching {
            context.packageManager.getApplicationInfo(PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return State.NOT_INSTALLED
        return if (info.enabled) State.ENABLED else State.DISABLED
    }

    /** Whether the switch can work now: Shizuku running and AARE allowed to use it. */
    fun available(): Boolean = CaptureMethods.shizukuAvailable()

    /** Turns Android Auto on or off; true once the phone reports the new state. */
    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!available()) return false
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellAudioService::class.java.name))
            .daemon(false)
            .processNameSuffix("switch")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val service = CompletableDeferred<IShellAudio?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.complete(binder?.takeIf { it.pingBinder() }?.let(IShellAudio.Stub::asInterface))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service.complete(null)
            }
        }
        val done = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
                val shell = service.await() ?: return@withTimeoutOrNull false
                withContext(Dispatchers.IO) { shell.setAndroidAutoEnabled(enabled) }
            } == true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        return done && state(context) == if (enabled) State.ENABLED else State.DISABLED
    }

    private const val TIMEOUT_MS = 10_000L
}
