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
    /** The displays by slot (0: apps full screen, 1: the map card). */
    private val displays = java.util.concurrent.ConcurrentHashMap<Int, VirtualDisplay>()

    private fun idOf(slot: Int): Int? = displays[slot]?.display?.displayId

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    constructor(context: Context?) {
        this.context = context
    }

    override fun create(slot: Int, surface: Surface?, width: Int, height: Int, densityDpi: Int): Int {
        release(slot)
        return try {
            val dm = displayManager()
            val d = dm.createVirtualDisplay("$NAME $slot", width, height, densityDpi, surface, FLAGS)
            displays[slot] = d
            keyboardHere(d.display.displayId)
            keepAwake(slot, d.display.displayId)
            d.display.displayId
        } catch (e: Exception) {
            Log.w(TAG, "can't create the virtual display", e)
            -1
        }
    }

    override fun setSurface(slot: Int, surface: Surface?) {
        displays[slot]?.surface = surface
    }

    override fun resize(slot: Int, width: Int, height: Int, densityDpi: Int) {
        displays[slot]?.resize(width, height, densityDpi)
    }

    override fun launch(slot: Int, component: String): Boolean {
        val id = idOf(slot) ?: return false
        // Fixed program, the component as one argument: no shell parsing.
        return run("am", "start", "--display", id.toString(), "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", component)
    }

    override fun touch(slot: Int, event: MotionEvent?) {
        val e = event ?: return
        val id = idOf(slot) ?: return
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
        // The phone's own panel stays lit (its lock screen) until it times out: powering just the
        // panel off (cmd display power-off, scrcpy's way) put Android back to sleep within 2 s on
        // Android 17, and touches were dropped again (tested 2026-11-08).
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

    override fun key(slot: Int, keyCode: Int) {
        val id = idOf(slot) ?: return
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(t, t, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
        }
    }

    override fun topPackage(slot: Int): String? {
        val id = idOf(slot) ?: return null
        // "Display #<id>" then its activities, top first: the first record names the package.
        val out = output("dumpsys", "activity", "activities") ?: return null
        val section = out.substringAfter("Display #$id ", "").substringBefore("Display #")
        return Regex("ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/").find(section)?.groupValues?.get(1)
    }

    override fun hidesBars(slot: Int, packageName: String?): Boolean {
        val pkg = packageName?.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*([.][A-Za-z0-9_]+)+").matches(it) } ?: return false
        val id = idOf(slot) ?: return false
        val onThis = Regex("""mDisplayId=$id\b""")
        // Only that app's windows (a name filter keeps the dump small), then the visible one on this display.
        val out = output("dumpsys", "window", pkg) ?: return false
        return out.split(Regex("""\n\s*Window #""")).any { w ->
            onThis.containsMatchIn(w) && w.contains("mViewVisibility=0x0") && immersive(w)
        }
    }

    /**
     * A window that asked for the system bars hidden. Android 17 lists them under "Requested
     * non-default-visibility types" (the status bar, normally shown, among them: hidden) and in
     * the legacy "vsysui=... FULLSCREEN" (YouTube full screen, 2026-11-09); older versions print
     * "mRequestedVisibleTypes=" (the bars still wanted) or the FULLSCREEN window flag.
     */
    private fun immersive(window: String): Boolean {
        fun line(key: String) = Regex(Regex.escape(key) + """([^\n]*)""").find(window)?.groupValues?.get(1)
        line("Requested non-default-visibility types:")?.let { if (it.contains("statusBars")) return true }
        line("mRequestedVisibleTypes=")?.let { if (!it.contains("statusBars")) return true }
        val fullscreen = Regex("""(^|\s)FULLSCREEN(\s|$)""")
        return listOfNotNull(line("vsysui="), line(" fl=")).any { fullscreen.containsMatchIn(it) }
    }

    override fun closeTasks(slot: Int): Int {
        val id = idOf(slot) ?: return 0
        val atm = taskManager() ?: return 0
        val roots = runCatching { HiddenApiBypass.invoke(atm.javaClass, atm, "getAllRootTaskInfosOnDisplay", id) as List<*> }
            .onFailure { Log.w(TAG, "root tasks", it) }.getOrNull() ?: return 0
        var closed = 0
        for (r in roots) {
            val info = r as? android.app.TaskInfo ?: continue
            // removeTask takes it off Recents too; "am stack remove" is the same by the shell.
            val ok = runCatching { HiddenApiBypass.invoke(atm.javaClass, atm, "removeTask", info.taskId) as? Boolean ?: true }
                .onFailure { Log.w(TAG, "remove task ${info.taskId}", it) }.getOrDefault(false) ||
                run("am", "stack", "remove", info.taskId.toString())
            if (ok) closed++
        }
        return closed
    }

    override fun moveCallsAway(slot: Int): String? {
        val id = idOf(slot) ?: return null
        val atm = taskManager() ?: return null
        val roots = runCatching { HiddenApiBypass.invoke(atm.javaClass, atm, "getAllRootTaskInfosOnDisplay", id) as List<*> }
            .onFailure { Log.w(TAG, "root tasks", it) }.getOrNull() ?: return null
        val moved = ArrayList<String>()
        for (r in roots) {
            val info = r as? android.app.TaskInfo ?: continue
            val top = info.topActivity ?: continue
            if (!callScreen(top)) continue
            runCatching { HiddenApiBypass.invoke(atm.javaClass, atm, "moveRootTaskToDisplay", info.taskId, 0) }
                .onSuccess { moved += top.flattenToShortString() }
                .onFailure { Log.w(TAG, "move ${top.packageName}", it) }
        }
        return moved.takeIf { it.isNotEmpty() }?.joinToString()
    }

    /**
     * A call's screen: the phone's dialer and call manager, or an app's call activity (WhatsApp's
     * VoipActivity...). With the car's display focused, Android opened them there, over the app
     * shown on the car, and the app behind came up when the call ended (2026-11-09 drive).
     */
    private fun callScreen(c: android.content.ComponentName): Boolean {
        if (c.packageName in CALL_PACKAGES) return true
        val name = c.className.substringAfterLast('.')
        return CALL_WORDS.any { name.contains(it, ignoreCase = true) }
    }

    private var atmCache: Any? = null

    /** IActivityTaskManager (hidden), null if it can't be had. */
    @SuppressLint("PrivateApi")
    private fun taskManager(): Any? = atmCache ?: runCatching {
        HiddenApiBypass.invoke(Class.forName("android.app.ActivityTaskManager"), null, "getService")
    }.getOrNull()?.also { atmCache = it }

    override fun release(slot: Int) {
        awake.remove(slot)?.let { runCatching { it.release() } }
        displays.remove(slot)?.release()
    }

    private val awake = java.util.concurrent.ConcurrentHashMap<Int, android.os.PowerManager.WakeLock>()

    /**
     * The display has a power group of its own (OWN_DISPLAY_GROUP), which Android put to sleep
     * after some 10 s without a touch on it: the map card, never touched while driving, went black
     * on the car and came back for a few seconds after each touch (2026-11-12). A screen wake lock
     * on that display keeps its group awake, as a navigation app keeps a screen on; the phone's
     * own screen is another group and stays as it is.
     */
    @SuppressLint("PrivateApi")
    private fun keepAwake(slot: Int, displayId: Int) {
        awake.remove(slot)?.let { runCatching { it.release() } }
        runCatching {
            @Suppress("DEPRECATION")
            val level = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
            val pm = powerManager()
            val lock = HiddenApiBypass.invoke(pm.javaClass, pm, "newWakeLock", level, "aare:apps-display-$slot", displayId)
                as android.os.PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            awake[slot] = lock
        }.onFailure { Log.w(TAG, "can't keep display $displayId awake", it) }
    }

    /** A PowerManager that says "the shell" (the wake lock's package must be this uid's). */
    @SuppressLint("PrivateApi")
    private fun powerManager(): android.os.PowerManager {
        val shell = ShellContext(context ?: systemContext())
        fun service(name: String, stub: String): Any? = runCatching {
            val binder = HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null, "getService", name)
            HiddenApiBypass.invoke(Class.forName("$stub\$Stub"), null, "asInterface", binder)
        }.getOrNull()
        val c = android.os.PowerManager::class.java.declaredConstructors.first { it.parameterTypes.firstOrNull() == Context::class.java }
        c.isAccessible = true
        val args = c.parameterTypes.map { t ->
            when {
                t == Context::class.java -> shell
                t.name == "android.os.IPowerManager" -> service("power", "android.os.IPowerManager")
                t.name == "android.os.IThermalService" -> service("thermalservice", "android.os.IThermalService")
                t == android.os.Handler::class.java -> android.os.Handler(android.os.Looper.getMainLooper() ?: android.os.Looper.myLooper()!!)
                else -> null
            }
        }
        return c.newInstance(*args.toTypedArray()) as android.os.PowerManager
    }

    override fun destroy() {
        displays.keys.toList().forEach(::release)
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
        /** The phone's call screens. */
        val CALL_PACKAGES = setOf("com.android.server.telecom", "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.incallui", "com.android.incallui")
        /** Words in an app's call activity name (WhatsApp's VoipActivityV2, Telegram's VoIPActivity...). */
        val CALL_WORDS = listOf("Voip", "InCall", "CallActivity", "IncomingCall", "OngoingCall", "Calling")

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
        // OWN_DISPLAY_GROUP gives the display a power group of its own, which [keepAwake] keeps
        // awake (without it the map card went black some 10 s after the last touch, 2026-11-12).
        // PUBLIC stays: on a private display Android refused the keyboard's window ("does not have
        // access"), and nothing could be typed on the car.
        val FLAGS = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
            TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS
    }
}
