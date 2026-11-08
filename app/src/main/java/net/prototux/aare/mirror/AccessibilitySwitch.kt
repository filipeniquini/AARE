package net.prototux.aare.mirror

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.IShellAudio
import net.prototux.aare.audio.ShellAudioService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AARE's accessibility service (touch for the mirrored phone screen) only while the car is
 * connected, when the user asks for it: banks refuse to open while an accessibility service is
 * on (anti remote-control fraud), and the user's bank refused because of AARE's (2026-11-08).
 * Off: the service turns itself off. On: through Shizuku, which may write that setting; only
 * AARE's own entry is touched.
 */
object AccessibilitySwitch {
    private val component = "net.prototux.aare/" + MirrorTouchService::class.java.name

    /** The car connected: turn the service on (background thread). */
    fun carConnected(context: Context) {
        val app = context.applicationContext
        Thread({
            val ok = setThroughShell(app, true)
            DiagLog.add(if (ok) "accessibility: on for the drive" else "accessibility: couldn't turn it on (Shizuku?)")
        }, "aare-a11y-on").start()
    }

    /** The car is gone: the service turns itself off. */
    fun carGone(context: Context) {
        val service = MirrorTouchService.instance
        if (service != null) {
            service.disableSelf()
            DiagLog.add("accessibility: off until the next drive")
        } else {
            // Not running (yet): make sure it isn't left in the enabled list either.
            val app = context.applicationContext
            Thread({ setThroughShell(app, false) }, "aare-a11y-off").start()
        }
    }

    private fun setThroughShell(context: Context, enabled: Boolean): Boolean {
        if (!CaptureMethods.shizukuAvailable()) return false
        val latch = CountDownLatch(1)
        var shell: IShellAudio? = null
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellAudioService::class.java.name))
            .daemon(false)
            .processNameSuffix("a11y")
            .tag("a11y")
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
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { Shizuku.bindUserService(args, connection) } }
            latch.await(10, TimeUnit.SECONDS)
            shell?.setOwnAccessibilityService(component, enabled) == true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
