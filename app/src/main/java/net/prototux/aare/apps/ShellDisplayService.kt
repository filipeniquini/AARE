package net.prototux.aare.apps

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with adb (shell) rights, which may create a
 * trusted virtual display, open any app on it and inject input into it: the way scrcpy's
 * --new-display works. The display has its own focus and stays usable with the phone locked,
 * and nothing of it shows on the phone's screen.
 */
class ShellDisplayService : IShellDisplay.Stub {
    private val context: Context?
    @Volatile private var display: VirtualDisplay? = null

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    constructor(context: Context?) {
        this.context = context
    }

    override fun create(surface: Surface?, width: Int, height: Int, densityDpi: Int): Int {
        release()
        return try {
            val dm = displayManager()
            val d = dm.createVirtualDisplay(NAME, width, height, densityDpi, surface, FLAGS)
            display = d
            keyboardHere(d.display.displayId)
            d.display.displayId
        } catch (e: Exception) {
            Log.w(TAG, "can't create the virtual display", e)
            -1
        }
    }

    override fun setSurface(surface: Surface?) {
        display?.surface = surface
    }

    override fun resize(width: Int, height: Int, densityDpi: Int) {
        display?.resize(width, height, densityDpi)
    }

    override fun launch(component: String): Boolean {
        val id = display?.display?.displayId ?: return false
        // Fixed program, the component as one argument: no shell parsing.
        return run("am", "start", "--display", id.toString(), "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", component)
    }

    override fun touch(event: MotionEvent?) {
        val e = event ?: return
        val id = display?.display?.displayId ?: return
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        if (e.actionMasked == MotionEvent.ACTION_DOWN) wakeIfAsleep(id)
        inject(e, id)
        e.recycle()
    }

    /**
     * With the phone's screen off, Android drops touches on every display of its power group, and
     * this display stays in the phone's (Android 17 ignores OWN_DISPLAY_GROUP for it): mirrored
     * apps ignored the driver while the phone was locked (2026-11-08 drive). Wake it first, as
     * `input -d <display> keyevent WAKEUP` does; the phone shows its lock screen until it times out.
     */
    private fun wakeIfAsleep(displayId: Int) {
        if (interactive() != false) return
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(t, t, action, KeyEvent.KEYCODE_WAKEUP, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId, WAIT_FOR_RESULT)
        }
        // The touch right after must find the display awake.
        val until = SystemClock.uptimeMillis() + 500
        while (interactive() == false && SystemClock.uptimeMillis() < until) SystemClock.sleep(10)
    }

    private var power: Any? = null

    /** IPowerManager.isInteractive() (hidden), null if it can't be asked. */
    @SuppressLint("PrivateApi")
    private fun interactive(): Boolean? = runCatching {
        val pm = power ?: run {
            val binder = HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null, "getService", "power")
            HiddenApiBypass.invoke(Class.forName("android.os.IPowerManager\$Stub"), null, "asInterface", binder)!!.also { power = it }
        }
        HiddenApiBypass.invoke(pm.javaClass, pm, "isInteractive") as Boolean
    }.getOrNull()

    override fun key(keyCode: Int) {
        val id = display?.display?.displayId ?: return
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(t, t, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
        }
    }

    override fun topPackage(): String? {
        val id = display?.display?.displayId ?: return null
        // "Display #<id>" then its activities, top first: the first record names the package.
        val out = output("dumpsys", "activity", "activities") ?: return null
        val section = out.substringAfter("Display #$id ", "").substringBefore("Display #")
        return Regex("ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/").find(section)?.groupValues?.get(1)
    }

    override fun release() {
        display?.release()
        display = null
    }

    override fun destroy() {
        release()
        exitProcess(0)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The keyboard opens on this display, not on the phone's (without system decorations it
     * would fall back to the phone): IWindowManager.setDisplayImePolicy(id, LOCAL), as scrcpy's
     * --display-ime-policy=local.
     */
    @SuppressLint("PrivateApi")
    private fun keyboardHere(displayId: Int) {
        try {
            val binder = HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null, "getService", "window")
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val wm = HiddenApiBypass.invoke(stub, null, "asInterface", binder)!!
            HiddenApiBypass.invoke(wm.javaClass, wm, "setDisplayImePolicy", displayId, 0 /* DISPLAY_IME_POLICY_LOCAL */)
        } catch (e: Exception) {
            Log.w(TAG, "can't keep the keyboard on the display", e)
        }
    }

    private fun inject(event: InputEvent, displayId: Int, mode: Int = ASYNC) {
        try {
            HiddenApiBypass.invoke(event.javaClass, event, "setDisplayId", displayId)
            val manager = inputManager()
            HiddenApiBypass.invoke(manager.javaClass, manager, "injectInputEvent", event, mode)
        } catch (e: Exception) {
            Log.w(TAG, "can't inject input", e)
        }
    }

    private var input: Any? = null

    /** InputManagerGlobal on Android 14+, InputManager before; both hidden. */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = input ?: run {
        val global = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
        val m = if (global != null) HiddenApiBypass.invoke(global, null, "getInstance")
        else HiddenApiBypass.invoke(Class.forName("android.hardware.input.InputManager"), null, "getInstance")
        input = m
        m!!
    }

    /** A DisplayManager that says "the shell", so the display may be trusted and owned by this uid. */
    private fun displayManager(): DisplayManager {
        HiddenApiBypass.addHiddenApiExemptions("")
        val shell = ShellContext(context ?: systemContext())
        val c = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        c.isAccessible = true
        return c.newInstance(shell)
    }

    private fun run(vararg command: String): Boolean = try {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0 && "Error" !in text
        if (!ok) Log.w(TAG, "${command.first()} failed: $text")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "${command.first()} failed", e)
        false
    }

    private fun output(vararg command: String): String? = try {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (_: Exception) {
        null
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;")
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private companion object {
        const val TAG = "AareShellDisplay"
        /** InputManager injection modes (hidden). */
        const val ASYNC = 0
        const val WAIT_FOR_RESULT = 1
        const val NAME = "AARE apps"
        const val SHELL_PACKAGE = "com.android.shell"

        // DisplayManager's flags (the hidden ones by value), as scrcpy sets them for --new-display.
        const val PUBLIC = 1
        const val PRESENTATION = 1 shl 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        // No SHOULD_SHOW_SYSTEM_DECORATIONS: with it the phone's status bar and taskbar showed on the
        // car, and the taskbar opened any app past the car's rules.
        val FLAGS = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
            TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS
    }
}
