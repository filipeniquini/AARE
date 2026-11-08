package net.prototux.aare.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import net.prototux.aare.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One call into the Shizuku helper (adb rights), then let it go. Blocking: off the main thread. */
object ShellOnce {
    fun <T> call(context: Context, block: (IShellAudio) -> T): T? {
        if (!CaptureMethods.shizukuAvailable()) return null
        val latch = CountDownLatch(1)
        var shell: IShellAudio? = null
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellAudioService::class.java.name))
            .daemon(false)
            .processNameSuffix("once")
            .tag("once")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                shell = binder?.takeIf { it.pingBinder() }?.let(IShellAudio.Stub::asInterface)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { latch.countDown() }
        }
        return try {
            Handler(Looper.getMainLooper()).post { runCatching { Shizuku.bindUserService(args, connection) } }
            latch.await(10, TimeUnit.SECONDS)
            shell?.let(block)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
