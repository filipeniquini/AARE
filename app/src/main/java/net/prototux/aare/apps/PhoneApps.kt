package net.prototux.aare.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.audio.CaptureMethods
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Any phone app on the car screen: opened on a virtual display the size of the car's (created
 * by [ShellDisplayService] through Shizuku), drawn into the car UI, with the driver's touches
 * sent back to it. Apps keep running there while the car shows something else, and nothing of
 * them shows on the phone's own screen. One display for all of them, like a second phone screen.
 */
object PhoneApps {
    /** A launchable phone app. */
    class App(val component: ComponentName, val label: String, val icon: Bitmap?, val category: Int)

    enum class Kind { NAVIGATION, MUSIC, VIDEO, SOCIAL, ASSISTANT, OTHER }

    /** Gemini, opened mirrored in place of Google's car assistant (which only Android Auto may bind). */
    const val GEMINI = "com.google.android.apps.bard"
    /** Where Gemini's screens live (the Gemini app hands over to the Google app). */
    val ASSISTANT_PACKAGES = setOf(GEMINI, "com.google.android.googlequicksearchbox")

    fun available() = CaptureMethods.shizukuAvailable()

    /**
     * The biggest density at which a [width]×[height] display is still a tablet to apps (smallest
     * width 600 dp): YouTube and others switch to their tablet layout there (the user's wish,
     * 2026-11-08). About 174 dpi on the VW Play's full screen, 168 in the GTS map card.
     */
    fun tabletDpi(width: Int, height: Int): Int = (minOf(width, height) * 160 / TABLET_SW_DP).coerceIn(100, 320)
    private const val TABLET_SW_DP = 600

    /** Apps opened full screen from the car's grid. */
    val full = PhoneAppHost("apps")

    /** The navigation app in the GTS home screen's big card (Google Maps by default). */
    val card = PhoneAppHost("card")

    /** The car is gone: both displays and their helpers go. */
    fun release() { full.release(); card.release() }

    // ---------------------------------------------------------------- the phone's apps

    /** Launchable apps on the phone (package manager work: off the main thread). */
    fun query(context: Context, iconSizePx: Int): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            val icon = runCatching { ri.loadIcon(pm).toBitmap(iconSizePx) }.getOrNull()
            App(ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString(), icon, ai.applicationInfo.category)
        }.distinctBy { it.component.packageName }
    }

    private fun android.graphics.drawable.Drawable.toBitmap(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        setBounds(0, 0, size, size)
        draw(c)
        return b
    }

    /**
     * What kind of app, for the driving and locked-phone rules: the category the app declares,
     * plus well-known apps that don't declare one.
     */
    fun kind(packageName: String, category: Int): Kind = when {
        packageName in ASSISTANT_PACKAGES -> Kind.ASSISTANT
        packageName in SOCIAL -> Kind.SOCIAL
        packageName in NAVIGATION -> Kind.NAVIGATION
        packageName in MUSIC -> Kind.MUSIC
        packageName in VIDEO -> Kind.VIDEO
        category == ApplicationInfo.CATEGORY_SOCIAL -> Kind.SOCIAL
        category == ApplicationInfo.CATEGORY_MAPS -> Kind.NAVIGATION
        category == ApplicationInfo.CATEGORY_AUDIO -> Kind.MUSIC
        category == ApplicationInfo.CATEGORY_VIDEO -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /** Messaging and social apps: not opened from the car while the phone is locked (the user's choice). */
    val SOCIAL = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.facebook.katana", "com.facebook.orca",
        "com.facebook.lite", "com.facebook.mlite", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android", "com.instagram.barcelona",
        "com.snapchat.android", "com.discord", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.linkedin.android", "com.pinterest", "com.reddit.frontpage", "com.bereal.ft", "com.kwai.video",
    )
    private val NAVIGATION = setOf("com.google.android.apps.maps", "com.waze", "app.comaps.google", "app.organicmaps", "com.here.app.maps", "com.sygic.aura")
    private val MUSIC = setOf("com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app", "com.amazon.mp3", "com.apple.android.music")
    private val VIDEO = setOf("com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "com.hbo.hbonow", "com.globo.globotv")
}

/**
 * One virtual display for phone apps, with its own Shizuku helper process: [PhoneApps.full] for
 * apps full screen, [PhoneApps.card] for the navigation app in the GTS home screen's big card,
 * so both can show at once.
 */
class PhoneAppHost(private val name: String) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aare-phone-apps-$name") }
    @Volatile private var service: IShellDisplay? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    @Volatile private var displayId = -1
    @Volatile private var size = Triple(0, 0, 0)

    private val _ready = MutableStateFlow<Boolean?>(null)
    /** Whether the display exists: null while setting up, false if it can't be made (no Shizuku). */
    val ready: StateFlow<Boolean?> = _ready

    
    /** Shows the display in [surface] ([width]×[height] pixels), creating it the first time. */
    fun attach(context: Context, surface: Surface, width: Int, height: Int, dpi: Int, then: () -> Unit = {}) = worker.execute {
        val s = bind(context) ?: run { _ready.value = false; return@execute }
        try {
            // A new size (density) for the apps: start the display afresh. Resizing it in place only
            // reloaded some resources: YouTube's icons shrank but its layout stayed (2026-11-08).
            if (displayId >= 0 && size.third != dpi) {
                DiagLog.add("phone apps ($name): size changed to ${dpi}dpi, reopening the display")
                s.release()
                displayId = -1
            }
            if (displayId < 0) {
                displayId = s.create(surface, width, height, dpi)
                size = Triple(width, height, dpi)
                _ready.value = displayId >= 0
                DiagLog.add(if (displayId >= 0) "phone apps ($name): display $displayId ${width}x$height ${dpi}dpi" else "phone apps ($name): can't create the display")
            } else {
                s.setSurface(surface)
                // Same density, another size (the car's insets changed): resizing is enough.
                if (size != Triple(width, height, dpi)) { s.resize(width, height, dpi); size = Triple(width, height, dpi) }
                _ready.value = true
            }
            if (displayId >= 0) then()
        } catch (e: Exception) {
            Log.w(TAG, "attach", e)
            _ready.value = false
        }
    }

    /** The car shows something else: the apps keep running, drawing nowhere. */
    fun detach() = worker.execute { runCatching { service?.setSurface(null) } }

    /** Opens [app] on the display (after [attach]). */
    fun open(app: ComponentName) = worker.execute {
        val ok = runCatching { service?.launch(app.flattenToString()) }.getOrNull() == true
        if (!ok) DiagLog.add("phone apps ($name): couldn't open ${app.packageName}")
    }

    /** When the current gesture started, on this phone's uptime clock. */
    private var gestureDown = 0L

    /**
     * A fresh event in the view's own pixels (the display's) on the phone's clock. A copy of the
     * view's event kept its offset on the car screen: fine full screen at the top left corner, but
     * the map card sits further in, so its touches landed outside the display.
     */
    fun touch(event: MotionEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureDown = now
        val n = event.pointerCount
        val props = Array(n) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i, it); it.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().also { c ->
            event.getPointerCoords(i, c)
            c.x = event.getX(i); c.y = event.getY(i)
            if (c.pressure <= 0f) c.pressure = 1f
            if (c.size <= 0f) c.size = 1f
        } }
        val fresh = MotionEvent.obtain(gestureDown, now, event.action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        worker.execute { runCatching { service?.touch(fresh) }; fresh.recycle() }
    }

    fun back() = worker.execute { runCatching { service?.key(android.view.KeyEvent.KEYCODE_BACK) } }

    /** The package in front on the display, or null (blocking: off the main thread). */
    fun topPackage(): String? = runCatching { service?.topPackage() }.getOrNull()

    /** The car is gone: close the display (its apps close too) and the helper process. */
    fun release() = worker.execute {
        runCatching { service?.release() }
        displayId = -1
        _ready.value = null
        val a = args; val c = connection
        if (a != null && c != null) runCatching { Shizuku.unbindUserService(a, c, true) }
        service = null; connection = null; args = null
    }

    private fun bind(context: Context): IShellDisplay? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (!PhoneApps.available()) return null
        val latch = CountDownLatch(1)
        val a = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellDisplayService::class.java.name))
            .daemon(false)
            .processNameSuffix(name)
            .tag(name)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.takeIf { it.pingBinder() }?.let(IShellDisplay.Stub::asInterface)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                displayId = -1
                _ready.value = null
            }
        }
        runCatching { Shizuku.bindUserService(a, c) }.onFailure { Log.w(TAG, "Shizuku bind", it); return null }
        args = a; connection = c
        latch.await(5, TimeUnit.SECONDS)
        return service
    }

    private companion object { const val TAG = "AarePhoneApps" }
}

