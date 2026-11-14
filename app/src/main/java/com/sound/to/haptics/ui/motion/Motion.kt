package com.sound.to.haptics.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntSize

// ─── Duration tokens ────────────────────────────────────────────────────────

private const val BaseDurationShort = 150
private const val BaseDurationMedium = 250
private const val BaseDurationLong = 400

/**
 * Global "how fast should motion play" multiplier. 1x is normal speed;
 * values below 1 slow everything down (great for eyeballing an animation
 * frame-by-frame while chasing a jank bug), values above 1 speed it up.
 * Driven by the debug menu's Motion Speed control (see DebugFlags) — the
 * rest of the app never touches this directly, it just reads the scaled
 * duration tokens below or calls [scaleDurationMs].
 */
@Volatile
private var motionDurationMultiplier: Float = 1f

/** Applies a [MotionSpeed]-style scale (1x normal, 0x = effectively frozen). */
fun setMotionSpeedScale(speedScale: Float) {
    motionDurationMultiplier = if (speedScale <= 0.001f) 12f else 1f / speedScale
    MotionDurationShort = scaleDurationMs(BaseDurationShort)
    MotionDurationMedium = scaleDurationMs(BaseDurationMedium)
    MotionDurationLong = scaleDurationMs(BaseDurationLong)
}

/** Scales any duration by the current global motion speed. Safe to call from non-composable code. */
fun scaleDurationMs(baseMs: Int): Int = (baseMs * motionDurationMultiplier).toInt().coerceIn(1, 20_000)

var MotionDurationShort = BaseDurationShort
    private set
var MotionDurationMedium = BaseDurationMedium
    private set
var MotionDurationLong = BaseDurationLong
    private set

// ─── Material easing curves ─────────────────────────────────────────────────

/** Material 3 "emphasized" easing — the signature curve of Pixel motion. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Emphasized decelerate: fast start, long tail — used for entering elements. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Emphasized accelerate: slow start, fast end — used for exiting elements. */
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

// ─── Spring physics presets ─────────────────────────────────────────────────

/** Standard Material spring: no bounce, medium stiffness — sizing/positions. */
val MaterialSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Signature "Material bouncy" spring — icons, badges, checkmarks. */
val MaterialSpringBouncy = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Press feedback: quick, tactile, slight bounce on release. */
val PressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Snap spring: high stiffness, no bounce — slider thumbs, drag handles. */
val SnapSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

/**
 * Animated target with Material spring physics. Prefer over raw tweens for
 * anything that changes size/position/scale so it responds to interruptions
 * gracefully.
 */
@Composable
fun animateMaterial(target: Float, label: String = "MaterialAnim"): Float {
    val value by animateFloatAsState(
        targetValue = target,
        animationSpec = MaterialSpring,
        label = label,
    )
    return value
}

// ─── Screen transition helpers ──────────────────────────────────────────────

/**
 * Material "fade through": the outgoing screen fades out almost immediately,
 * the incoming one fades in with a subtle scale settle. Used for destination
 * changes where shared-axis movement would feel like too much.
 */
fun AnimatedContentTransitionScope<*>.fadeThroughTransition(
    enterDuration: Int = MotionDurationMedium,
    exitDuration: Int = MotionDurationShort,
): ContentTransform {
    val enter = fadeIn(tween(enterDuration, easing = EmphasizedDecelerate)) +
        scaleIn(initialScale = 0.97f, animationSpec = tween(enterDuration, easing = EmphasizedDecelerate))
    val exit = fadeOut(tween(exitDuration, easing = LinearEasing))
    return enter.togetherWith(exit)
}

/**
 * Material "shared axis" (horizontal): content slides in the direction of
 * travel while the outgoing screen slides away. The classic Pixel tab
 * transition.
 */
fun AnimatedContentTransitionScope<*>.reducedMotionTransition(): ContentTransform =
    fadeIn(tween(1)).togetherWith(fadeOut(tween(1)))

fun AnimatedContentTransitionScope<*>.sharedAxisXTransition(forward: Boolean): ContentTransform {
    val inOffset = { full: Int -> if (forward) full / 5 else -full / 5 }
    val outOffset = { full: Int -> if (forward) -full / 8 else full / 8 }
    return (slideInHorizontally(tween(MotionDurationLong, easing = EmphasizedDecelerate), initialOffsetX = inOffset) +
        fadeIn(tween(MotionDurationLong, easing = EmphasizedDecelerate)))
        .togetherWith(
            slideOutHorizontally(tween(MotionDurationMedium, easing = EmphasizedAccelerate), targetOffsetX = outOffset) +
                fadeOut(tween(MotionDurationMedium, easing = EmphasizedAccelerate))
        )
}

/**
 * Material "shared axis" (vertical): the incoming content settles down from
 * above while the outgoing one lifts away — the natural language for content
 * that drops into place (sheets expanding, panels stacking, status flips).
 */
fun AnimatedContentTransitionScope<*>.sharedAxisYTransition(forward: Boolean): ContentTransform {
    val inOffset = { full: Int -> if (forward) full / 5 else -full / 5 }
    val outOffset = { full: Int -> if (forward) -full / 8 else full / 8 }
    return (androidx.compose.animation.slideInVertically(tween(MotionDurationLong, easing = EmphasizedDecelerate), initialOffsetY = inOffset) +
        fadeIn(tween(MotionDurationLong, easing = EmphasizedDecelerate)))
        .togetherWith(
            androidx.compose.animation.slideOutVertically(tween(MotionDurationMedium, easing = EmphasizedAccelerate), targetOffsetY = outOffset) +
                fadeOut(tween(MotionDurationMedium, easing = EmphasizedAccelerate))
        )
}

/**
 * Confirmation pop: content scales up with a bouncy spring and fades in —
 * used for completion moments (checks, "done" states, saved badges).
 */
fun AnimatedContentTransitionScope<*>.popInTransition(): ContentTransform {
    val enter = scaleIn(
        initialScale = 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
    ) + fadeIn(tween(MotionDurationShort, easing = LinearEasing))
    val exit = scaleOut(
        targetScale = 0.92f,
        animationSpec = tween(MotionDurationShort, easing = EmphasizedAccelerate),
    ) + fadeOut(tween(MotionDurationShort, easing = LinearEasing))
    return enter.togetherWith(exit)
}

/**
 * Material "container transform": the incoming content grows and fades in
 * over the outgoing one, which shrinks and fades away — used for state
 * changes inside a screen (idle → loading → player).
 */
fun AnimatedContentTransitionScope<*>.containerTransformTransition(): ContentTransform {
    val sizeSpec = tween<IntSize>(scaleDurationMs(450), easing = EmphasizedDecelerate)
    return (fadeIn(tween(scaleDurationMs(300), easing = EmphasizedDecelerate)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(scaleDurationMs(350), easing = EmphasizedDecelerate)))
        .togetherWith(fadeOut(tween(scaleDurationMs(200), easing = EmphasizedAccelerate)) + scaleOut(targetScale = 1.05f, animationSpec = tween(scaleDurationMs(200), easing = EmphasizedAccelerate)))
        .using(SizeTransform(clip = true) { _, _ -> sizeSpec })
}

/** Soft vertical settle for inline content swaps (stage text, status rows). */
fun AnimatedContentTransitionScope<*>.verticalSettleTransition(): ContentTransform {
    val enter = fadeIn(tween(scaleDurationMs(220), easing = EmphasizedDecelerate)) +
        androidx.compose.animation.slideInVertically(tween(scaleDurationMs(220), easing = EmphasizedDecelerate)) { h -> h / 3 }
    val exit = fadeOut(tween(scaleDurationMs(120), easing = EmphasizedAccelerate)) +
        androidx.compose.animation.slideOutVertically(tween(scaleDurationMs(120), easing = EmphasizedAccelerate)) { h -> -h / 3 }
    return enter.togetherWith(exit)
}

/** Standard value "count-up" animation spec. */
val CountUpSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Gentle pulse used for live indicators (recording, connecting). */
val PulseEasing = LinearOutSlowInEasing
