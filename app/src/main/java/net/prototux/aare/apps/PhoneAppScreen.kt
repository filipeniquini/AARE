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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val setting = runtime.settings.phoneAppsDpi
    fun dpiFor(w: Int, h: Int): Int {
        if (setting != net.prototux.aare.Settings.PHONE_APPS_DPI_TABLET) return setting
        // Keep the density the display has while it is still a tablet at the new size: a new
        // density means a new display, and its apps would start over (the map card going full screen).
        val now = host.currentDpi
        if (inCard && now > 0) return now
        return if (now > 0 && minOf(w, h) * 160 / now >= 600) now else PhoneApps.tabletDpi(w, h)
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
    }
}

/** How often a video app is asked whether it's full screen. */
private const val FULLSCREEN_CHECK_MS = 1_500L

/** The height of a display first made for the media card: about the car screen's, so apps lay out as there. */
private const val CARD_FRESH_HEIGHT = 653
