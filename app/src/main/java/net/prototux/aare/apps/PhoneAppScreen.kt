package net.prototux.aare.apps

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
    fun dpiFor(w: Int, h: Int) = if (setting == net.prototux.aare.Settings.PHONE_APPS_DPI_TABLET) PhoneApps.tabletDpi(w, h) else setting

    DisposableEffect(Unit) {
        val full = host === PhoneApps.full
        if (full) runtime.screenBack = { host.back(); true }
        onDispose { if (full) runtime.screenBack = null; host.detach() }
    }

    Box(modifier.background(Color.Black)) {
        if (show) {
            AndroidView(
                factory = { ctx ->
                    var surface: Surface? = null
                    @SuppressLint("ClickableViewAccessibility")
                    val view = TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surface = Surface(st)
                                // Opened each time it's shown: an app already running comes to the front as it was.
                                host.attach(context, surface!!, w, h, dpiFor(w, h)) { host.open(component) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                surface?.let { host.attach(context, it, w, h, dpiFor(w, h)) }
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                host.detach()
                                surface?.release(); surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                        // The display is exactly this view's size: its pixels are the app's.
                        setOnTouchListener { _, e -> host.touch(e); true }
                    }
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            blockedLocked -> Placeholder(stringResource(R.string.apps_locked_social), loading = false, icon = Icons.Rounded.Lock)
            blockedDriving -> Placeholder(stringResource(R.string.apps_driving_paused), loading = false, icon = Icons.Rounded.PauseCircle)
            ready == false -> Placeholder(stringResource(R.string.apps_need_shizuku), loading = false, icon = Icons.Rounded.PhoneAndroid)
        }
    }
}
