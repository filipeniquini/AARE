package net.prototux.aare.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Touch for the phone screen shown in the car: replays the driver's taps and swipes on the phone
 * as accessibility gestures, and tells which app is in front (so the mirror can stay paused
 * while driving unless it's a navigation app). Optional; the user turns it on in the phone's
 * accessibility settings.
 */
class MirrorTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // System UI (shade, volume dialog) and keyboards don't change which app is in front.
        if (pkg == "com.android.systemui" || event.className?.contains("InputMethod") == true) return
        _foregroundApp.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _foregroundApp.value = null
        super.onDestroy()
    }

    /** Taps at ([x], [y]) in phone screen pixels. */
    fun tap(x: Float, y: Float) = stroke(listOf(x to y), 1L)

    /** Drags along [points] (phone screen pixels) over [durationMs]. */
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) = stroke(points, durationMs.coerceIn(1, 2_000))

    fun back() = !locked() && performGlobalAction(GLOBAL_ACTION_BACK)

    // ---------------------------------------------------------------- live touch
    // The car's finger, followed as it moves: the stroke is dispatched in short continued pieces
    // (Android 8+), so a drag scrolls while it happens instead of being replayed on release.
    // Main thread only.

    private val main = Handler(Looper.getMainLooper())
    /** The piece being dispatched or last dispatched; null when no finger is down. */
    private var live: GestureDescription.StrokeDescription? = null
    private var liveEnd: Pair<Float, Float>? = null
    private var liveSentAt = 0L
    private var inFlight = false
    private var lifting = false
    private val pendingPoints = ArrayList<Pair<Float, Float>>()

    /** The finger goes down at ([x], [y]), phone screen pixels. */
    fun touchDown(x: Float, y: Float) {
        if (locked()) return
        pendingPoints.clear()
        lifting = false
        val start = x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(start.first, start.second) }, 0, 1, true)
        live = stroke
        liveEnd = start
        send(stroke)
    }

    /** The finger moves to ([x], [y]). */
    fun touchMove(x: Float, y: Float) {
        if (live == null) return
        pendingPoints += x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
        pump()
    }

    /** The finger lifts at ([x], [y]). */
    fun touchUp(x: Float, y: Float) {
        if (live == null) return
        pendingPoints += x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
        lifting = true
        pump()
    }

    /** Sends what the finger did since the last piece, once that piece is done. */
    private fun pump() {
        val previous = live ?: return
        if (inFlight || (pendingPoints.isEmpty() && !lifting)) return
        val from = liveEnd ?: return
        if (locked()) { live = null; pendingPoints.clear(); return }
        val path = Path().apply {
            moveTo(from.first, from.second)
            pendingPoints.forEach { (x, y) -> lineTo(x, y) }
        }
        val end = pendingPoints.lastOrNull() ?: from
        pendingPoints.clear()
        // The piece lasts about as long as the real movement took, so flings keep their speed.
        val now = android.os.SystemClock.uptimeMillis()
        val duration = (now - liveSentAt).coerceIn(8, 250)
        val keepDown = !lifting
        val next = runCatching { previous.continueStroke(path, 0, duration, keepDown) }.getOrNull()
        if (next == null) { live = null; return }
        live = if (keepDown) next else null
        liveEnd = end
        if (!keepDown) lifting = false
        send(next)
    }

    private fun send(stroke: GestureDescription.StrokeDescription) {
        inFlight = true
        liveSentAt = android.os.SystemClock.uptimeMillis()
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { inFlight = false; pump() }
            override fun onCancelled(gestureDescription: GestureDescription?) { inFlight = false; live = null; pendingPoints.clear() }
        }, main)
        if (!ok) { inFlight = false; live = null; pendingPoints.clear() }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun stroke(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        // Never drive a locked phone from the car, whatever the mirror shows.
        if (locked()) return false
        val path = Path().apply {
            moveTo(points[0].first.coerceAtLeast(0f), points[0].second.coerceAtLeast(0f))
            points.drop(1).forEach { (x, y) -> lineTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: MirrorTouchService? = null
            private set

        private val _foregroundApp = MutableStateFlow<String?>(null)
        /** The app in front on the phone, as last reported; null without the service. */
        val foregroundApp: StateFlow<String?> = _foregroundApp
    }
}
