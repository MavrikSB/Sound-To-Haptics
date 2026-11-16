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

private const val BaseDurationShort = 150
private const val BaseDurationMedium = 250
private const val BaseDurationLong = 400

@Volatile
private var motionDurationMultiplier: Float = 1f

fun setMotionSpeedScale(speedScale: Float) {
    motionDurationMultiplier = if (speedScale <= 0.001f) 12f else 1f / speedScale
    MotionDurationShort = scaleDurationMs(BaseDurationShort)
    MotionDurationMedium = scaleDurationMs(BaseDurationMedium)
    MotionDurationLong = scaleDurationMs(BaseDurationLong)
}

fun scaleDurationMs(baseMs: Int): Int = (baseMs * motionDurationMultiplier).toInt().coerceIn(1, 20_000)

var MotionDurationShort = BaseDurationShort
    private set
var MotionDurationMedium = BaseDurationMedium
    private set
var MotionDurationLong = BaseDurationLong
    private set

val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

val MaterialSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

val MaterialSpringBouncy = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

val PressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

val SnapSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

@Composable
fun animateMaterial(target: Float, label: String = "MaterialAnim"): Float {
    val value by animateFloatAsState(
        targetValue = target,
        animationSpec = MaterialSpring,
        label = label,
    )
    return value
}

fun AnimatedContentTransitionScope<*>.fadeThroughTransition(
    enterDuration: Int = MotionDurationMedium,
    exitDuration: Int = MotionDurationShort,
): ContentTransform {
    val enter = fadeIn(tween(enterDuration, easing = EmphasizedDecelerate)) +
        scaleIn(initialScale = 0.97f, animationSpec = tween(enterDuration, easing = EmphasizedDecelerate))
    val exit = fadeOut(tween(exitDuration, easing = LinearEasing))
    return enter.togetherWith(exit)
}

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

fun AnimatedContentTransitionScope<*>.containerTransformTransition(): ContentTransform {
    val sizeSpec = tween<IntSize>(scaleDurationMs(450), easing = EmphasizedDecelerate)
    return (fadeIn(tween(scaleDurationMs(300), easing = EmphasizedDecelerate)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(scaleDurationMs(350), easing = EmphasizedDecelerate)))
        .togetherWith(fadeOut(tween(scaleDurationMs(200), easing = EmphasizedAccelerate)) + scaleOut(targetScale = 1.05f, animationSpec = tween(scaleDurationMs(200), easing = EmphasizedAccelerate)))
        .using(SizeTransform(clip = true) { _, _ -> sizeSpec })
}

fun AnimatedContentTransitionScope<*>.verticalSettleTransition(): ContentTransform {
    val enter = fadeIn(tween(scaleDurationMs(220), easing = EmphasizedDecelerate)) +
        androidx.compose.animation.slideInVertically(tween(scaleDurationMs(220), easing = EmphasizedDecelerate)) { h -> h / 3 }
    val exit = fadeOut(tween(scaleDurationMs(120), easing = EmphasizedAccelerate)) +
        androidx.compose.animation.slideOutVertically(tween(scaleDurationMs(120), easing = EmphasizedAccelerate)) { h -> -h / 3 }
    return enter.togetherWith(exit)
}

val CountUpSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

val PulseEasing = LinearOutSlowInEasing
