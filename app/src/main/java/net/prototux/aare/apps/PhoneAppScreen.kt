package net.prototux.aare.apps

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder
import net.prototux.aare.mirror.rememberKeyguardLocked

/**
 * A phone app full screen on the car, on [PhoneApps]' virtual display.
 *
 * The user's rules (2026-11-08): with the phone locked, messaging and social apps stay closed
 * (unlock first); everything else may show. While driving, only navigation and music: a video
 * app pauses its picture (its sound goes on), like the phone screen mirror.
 */
@Composable
internal fun PhoneAppScreen(
    runtime: CarRuntime,
    component: ComponentName,
    kind: PhoneApps.Kind,
    driving: Boolean,
    modifier: Modifier,
    /** [PhoneApps.card]: the home screen's map card, which leaves the car's back key alone. */
    host: PhoneAppHost = PhoneApps.full,
    /**
     * Bring [component] to the front each time this is shown (a display shared by many apps), or
     * only when the display is new (the map card: what runs there, Gemini included, stays as it is).
     */
    reopen: Boolean = true,
    /**
     * The full-screen display shown small in the home screen's media card (a minimized video): the
     * display keeps its size and density, its picture scaled down into the card. Resized to the
     * card, YouTube laid itself out for a tiny screen, its buttons huge (2026-11-09). The car's
     * back key and bar stay the car's.
     */
    inCard: Boolean = false,
) {
    val context = LocalContext.current
    val locked = rememberKeyguardLocked()
    val ready by host.ready.collectAsState()
    val blockedLocked = locked && kind == PhoneApps.Kind.SOCIAL
    // The voice assistant is for driving: it stays open like navigation and music.
    val blockedDriving = driving && kind != PhoneApps.Kind.NAVIGATION && kind != PhoneApps.Kind.MUSIC && kind != PhoneApps.Kind.ASSISTANT
    val show = !blockedLocked && !blockedDriving
    // Their own size, chosen on the phone: the car UI's density made some apps tablet-sized and tiny.
    // The app's own size, if one was chosen for it (YouTube as a phone), else the general one.
    val setting = runtime.settings.phoneAppDpi(component.packageName)
    fun dpiFor(w: Int, h: Int): Int {
        // Keep the density the display has while it is still the right kind at the new size: a new
        // density means a new display, and its apps would start over (the map card going full screen).
        val now = host.currentDpi
        if (inCard && now > 0) return now
        return when (setting) {
            net.prototux.aare.Settings.PHONE_APPS_DPI_TABLET ->
                if (now > 0 && minOf(w, h) * 160 / now >= 600) now else PhoneApps.tabletDpi(w, h)
            net.prototux.aare.Settings.PHONE_APPS_DPI_PHONE ->
                if (now > 0 && PhoneApps.isPhoneSize(w, h, now)) now else PhoneApps.phoneDpi(w, h)
            else -> setting
        }
    }

    val fullScreen = host === PhoneApps.full && !inCard
    DisposableEffect(Unit) {
        if (fullScreen) runtime.screenBack = { host.back(); true }
        // The view's own surface callback detaches it (only if it's still the one shown).
        onDispose { if (fullScreen) { runtime.screenBack = null; runtime.ui.videoFullscreen = false } }
    }
    // A video app full screen: when its video takes the whole screen (bars hidden), the car's bar
    // hides too. Asked every so often, while it's shown (a small dump of that app's windows).
    if (fullScreen && kind == PhoneApps.Kind.VIDEO && show) {
        androidx.compose.runtime.LaunchedEffect(component) {
            while (true) {
                val hides = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.hidesBars(component.packageName) }
                if (runtime.ui.videoFullscreen != hides) {
                    runtime.ui.videoFullscreen = hides
                    net.prototux.aare.DiagLog.add("phone apps: ${component.packageName} ${if (hides) "full screen, bar hidden" else "not full screen, bar shown"}")
                }
                kotlinx.coroutines.delay(FULLSCREEN_CHECK_MS)
            }
        }
    }

    // In the card: the display's own size, drawn scaled into the view (same proportions).
    val cardSize = if (inCard) host.currentSize else null
    // No display yet (played from home after a reconnection): made full-screen sized, the card's shape.
    fun displaySize(w: Int, h: Int): Pair<Int, Int>? = when {
        !inCard -> null
        cardSize != null -> cardSize
        else -> (w * CARD_FRESH_HEIGHT / h.coerceAtLeast(1)) to CARD_FRESH_HEIGHT
    }
    Box(modifier.background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (show) {
            AndroidView(
                factory = { ctx ->
                    var surface: Surface? = null
                    @SuppressLint("ClickableViewAccessibility")
                    val view = TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val (dw, dh) = displaySize(w, h)?.also { st.setDefaultBufferSize(it.first, it.second) } ?: (w to h)
                                surface = Surface(st)
                                // Opened each time it's shown: an app already running comes to the front as it was.
                                host.attach(context, surface!!, dw, dh, dpiFor(dw, dh)) { fresh -> if (reopen || fresh) host.open(component) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                val (dw, dh) = displaySize(w, h)?.also { st.setDefaultBufferSize(it.first, it.second) } ?: (w to h)
                                surface?.let { host.attach(context, it, dw, dh, dpiFor(dw, dh)) }
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                host.detach(surface)
                                surface?.release(); surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                        // The display is exactly this view's size: its pixels are the app's.
                        setOnTouchListener { _, e ->
                            // A touch on a full-screen video brings up its controls: the car's bar comes with them.
                            if (fullScreen && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) runtime.ui.barPeekAt = android.os.SystemClock.uptimeMillis()
                            host.touch(e); true
                        }
                    }
                    view
                },
                modifier = if (cardSize != null) Modifier.fillMaxSize().wrapContentSize()
                    .aspectRatio(cardSize.first.toFloat() / cardSize.second) else Modifier.fillMaxSize(),
            )
        }
        when {
            blockedLocked -> Placeholder(stringResource(R.string.apps_locked_social), loading = false, icon = Icons.Rounded.Lock)
            blockedDriving -> Placeholder(stringResource(R.string.apps_driving_paused), loading = false, icon = Icons.Rounded.PauseCircle)
            ready == false -> Placeholder(stringResource(R.string.apps_need_shizuku), loading = false, icon = Icons.Rounded.PhoneAndroid)
        }
        // AARE's own keyboard types into the app: the phone's is kept off the car's displays, for
        // the same keyboard everywhere (with the phone locked Android hid it anyway, 2026-11-15).
        // Also in the home screen's map card, smaller. Not while driving, like Android Auto's.
        if ((fullScreen || host === PhoneApps.card) && show && !driving)
            LockedKeyboard(host, Modifier.align(androidx.compose.ui.Alignment.BottomCenter), if (fullScreen) KEY_HEIGHT else CARD_KEY_HEIGHT)
    }
}

/**
 * AARE's keyboard over a phone app, when the app asks for the keyboard:
 * each key goes straight into the app's field (shown above it). Closed with its own key until the
 * app asks again; gone when the app is done with it (a search sent).
 */
@Composable
private fun LockedKeyboard(host: PhoneAppHost, modifier: Modifier, keyHeight: androidx.compose.ui.unit.Dp) {
    var wanted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dismissed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(host) {
        while (true) {
            val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.imeShown() }
            if (!now) dismissed = false
            wanted = now
            kotlinx.coroutines.delay(KEYBOARD_CHECK_MS)
        }
    }
    if (!wanted || dismissed) return
    val sink = androidx.compose.runtime.remember(host) {
        object : net.prototux.aare.host.templates.KeyboardSink {
            override fun type(text: String) = host.type(text)
            override fun backspace() = host.key(android.view.KeyEvent.KEYCODE_DEL)
            override fun submit() = host.key(android.view.KeyEvent.KEYCODE_ENTER)
            override fun hide() { dismissed = true }
        }
    }
    net.prototux.aare.host.templates.CarKeyboard(
        net.prototux.aare.host.templates.KeyboardMode.Text, sink,
        Icons.AutoMirrored.Rounded.KeyboardReturn, modifier, keyHeight = keyHeight,
    )
}

/** How often the app is asked whether it wants the keyboard. */
private const val KEYBOARD_CHECK_MS = 1_000L
private val KEY_HEIGHT = androidx.compose.ui.unit.Dp(50f)
private val CARD_KEY_HEIGHT = androidx.compose.ui.unit.Dp(38f)

/** How often a video app is asked whether it's full screen. */
private const val FULLSCREEN_CHECK_MS = 1_500L

/** The height of a display first made for the media card: about the car screen's, so apps lay out as there. */
private const val CARD_FRESH_HEIGHT = 653
