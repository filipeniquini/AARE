package net.prototux.aare.ui.car

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import net.prototux.aare.Settings

/**
 * How much the car screen animates (Settings.carAnimations). Every animation is short (at most
 * [CarMotion.LONG_MS]), moves or fades what is already drawn (no layout each frame), never resizes
 * the mirrored map or video, and never loops: the screen is encoded as video, so a still screen
 * costs the phone almost nothing and a moving one costs a frame each time.
 */
enum class CarMotion {
    /** Screens zoom in, the player and the bar animate, buttons sink under the finger. */
    FULL,
    /** Only what helps the driver: buttons sink under the finger, screens change with a quick fade. */
    REDUCED,
    /** Nothing moves; changes are instant. */
    OFF;

    companion object {
        const val SHORT_MS = 120
        const val MEDIUM_MS = 200
        const val LONG_MS = 250

        fun of(value: String): CarMotion = when (value) {
            Settings.MOTION_REDUCED -> REDUCED
            Settings.MOTION_OFF -> OFF
            else -> FULL
        }
    }
}

val LocalCarMotion = staticCompositionLocalOf { CarMotion.FULL }

/**
 * A tween for a decorative animation: [ms] long when animations are full, instant otherwise
 * (or also when reduced, if [essential]).
 */
@Composable
internal fun <T> carTween(ms: Int, essential: Boolean = false): FiniteAnimationSpec<T> {
    val motion = LocalCarMotion.current
    val on = motion == CarMotion.FULL || (essential && motion == CarMotion.REDUCED)
    return if (on) tween(ms, easing = FastOutSlowInEasing) else snap()
}

/** Whether decorative animations (player, bar, notices) run. */
@Composable
internal fun carMotionFull(): Boolean = LocalCarMotion.current == CarMotion.FULL

/** The touch feedback of one button: give [source] to its clickable and put [modifier] first in its chain. */
@Stable
internal class CarPress(val source: MutableInteractionSource, val modifier: Modifier)

/**
 * A button that sinks a little under the finger and springs back when let go: the finger feels the
 * answer at once, before the screen changes. [pressedScale] near 1 for big cards, lower for buttons.
 * Put [CarPress.modifier] before the button's clip and background, so the whole button scales.
 */
@Composable
internal fun rememberCarPress(pressedScale: Float = 0.92f): CarPress {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val on = LocalCarMotion.current != CarMotion.OFF
    val scale by animateFloatAsState(
        if (pressed && on) pressedScale else 1f,
        // Quick down, a small bounce back up.
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return CarPress(source, Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
}

/**
 * The change between two car screens. Full: the new screen fades in with a slight zoom, like
 * Android Auto's, unless a car app's map is on either side (its SurfaceView can't be zoomed with the
 * rest, it would jump). Reduced: a quick fade. Off: instant.
 */
internal fun AnimatedContentTransitionScope<CarScreen>.carScreenChange(motion: CarMotion): ContentTransform = when (motion) {
    CarMotion.OFF -> EnterTransition.None togetherWith ExitTransition.None
    CarMotion.REDUCED -> fadeIn(tween(160)) togetherWith fadeOut(tween(120))
    CarMotion.FULL -> {
        val zoom = initialState !is CarScreen.App && targetState !is CarScreen.App
        if (zoom) {
            (fadeIn(tween(CarMotion.MEDIUM_MS, easing = FastOutSlowInEasing)) +
                scaleIn(tween(CarMotion.LONG_MS, easing = FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
                (fadeOut(tween(CarMotion.SHORT_MS)) + scaleOut(tween(CarMotion.SHORT_MS), targetScale = 1.02f))
        } else {
            fadeIn(tween(CarMotion.MEDIUM_MS, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(CarMotion.SHORT_MS))
        }
    }
}

/** [clickable] with the press feedback of [press] (see [rememberCarPress]). */
@Composable
internal fun Modifier.clickable(press: CarPress, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = press.source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)

/**
 * Shows [content] with [enter] when it first appears (full animations only); it leaves at once.
 * For what comes and goes on its own (a notice, the call bar, the bar's media widget): no exit
 * animation, so nothing is ever drawn from stale state.
 */
@Composable
internal fun CarAppear(enter: EnterTransition, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!carMotionFull()) { content(); return }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    // [modifier]: the content's place in its parent (an alignment), which must be on this wrapper.
    AnimatedVisibility(shown, modifier, enter = enter, exit = ExitTransition.None) { content() }
}

/** [content] for [target], cross-faded when [key] of it changes (a new song's art, play to pause). */
@Composable
internal fun <T> CarCrossfade(target: T, key: (T) -> Any?, zoom: Boolean = false, content: @Composable (T) -> Unit) {
    val full = carMotionFull()
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            if (!full) EnterTransition.None togetherWith ExitTransition.None using null
            else if (zoom) (fadeIn(tween(CarMotion.SHORT_MS)) + scaleIn(tween(CarMotion.MEDIUM_MS, easing = FastOutSlowInEasing), initialScale = 0.7f)) togetherWith
                (fadeOut(tween(CarMotion.SHORT_MS)) + scaleOut(tween(CarMotion.SHORT_MS), targetScale = 0.7f)) using null
            else fadeIn(tween(CarMotion.LONG_MS)) togetherWith fadeOut(tween(CarMotion.LONG_MS)) using null
        },
        contentKey = key,
        label = "crossfade",
    ) { content(it) }
}

/** A color change (the bar's highlight moving to the app just opened) that fades when animations are full. */
@Composable
internal fun carColor(target: Color): Color =
    animateColorAsState(target, carTween(CarMotion.MEDIUM_MS), label = "color").value
