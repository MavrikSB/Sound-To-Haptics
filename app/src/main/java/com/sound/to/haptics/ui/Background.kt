package com.sound.to.haptics.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Path
import com.sound.to.haptics.ui.motion.scaleDurationMs

@Composable
fun DriftingBackground(
    modifier: Modifier = Modifier,
    energy: Float = 0f,
    interactionToken: Int = 0,

    lowFx: Boolean = false,

    frozen: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val seed = remember {
        BackgroundSeed(
            phase = Random.nextFloat() * (2f * Math.PI.toFloat()),
            drift = 0.72f + Random.nextFloat() * 0.52f,
            tilt = Random.nextFloat() * 2f - 1f,
        )
    }
    val interactionGlow = remember { Animatable(0f) }
    LaunchedEffect(interactionToken) {
        if (interactionToken > 0) {
            interactionGlow.snapTo(1f)
            interactionGlow.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }
    val glow = interactionGlow.value
    val musicEnergy = energy.coerceIn(0f, 1f)
    val phaseA: Float
    val phaseB: Float
    val phaseC: Float
    if (frozen) {

        phaseA = 2.1f
        phaseB = 4.4f
        phaseC = 1.3f
    } else {
        val transition = rememberInfiniteTransition(label = "SignalBackground")
        phaseA = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(scaleDurationMs(23_000))),
            label = "SignalBackgroundPhaseA",
        ).value
        phaseB = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(scaleDurationMs(31_000))),
            label = "SignalBackgroundPhaseB",
        ).value
        phaseC = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(scaleDurationMs(47_000))),
            label = "SignalBackgroundPhaseC",
        ).value
    }

    val ribbonPaths = remember { List(4) { Path() } }
    val marksPath = remember { Path() }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val phase = phaseA + seed.phase
        val slowPhase = phaseB * seed.drift + seed.phase * 0.35f
        val ultraSlowPhase = phaseC + seed.tilt
        val movement = 1f + musicEnergy * 1.35f
        val response = glow * 0.2f

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    cs.background,
                    cs.surface.copy(alpha = 0.96f),
                    cs.background,
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cs.primary.copy(alpha = 0.11f + musicEnergy * 0.12f + response), cs.primary.copy(alpha = 0f)),
                center = Offset(w * (0.16f + 0.05f * sin(slowPhase)), h * 0.2f),
                radius = w * 0.62f,
            ),
            radius = w * 0.62f,
            center = Offset(w * (0.16f + 0.05f * sin(slowPhase)), h * 0.2f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cs.tertiary.copy(alpha = 0.075f + musicEnergy * 0.1f + response), cs.tertiary.copy(alpha = 0f)),
                center = Offset(w * 0.86f, h * (0.68f + 0.06f * cos(ultraSlowPhase))),
                radius = w * 0.7f,
            ),
            radius = w * 0.7f,
            center = Offset(w * 0.86f, h * (0.68f + 0.06f * cos(ultraSlowPhase))),
        )

        if (!lowFx) {
            val ribbonLayers = 4
            val segments = 38
            val step = w / segments
            repeat(ribbonLayers) { layer ->
                val yBase = h * (0.16f + layer * 0.2f) + sin(slowPhase + layer) * h * (0.012f + musicEnergy * 0.008f)
                val amp = h * (0.016f + layer * 0.007f) * movement
                val path = ribbonPaths[layer]
                path.reset()
                path.moveTo(0f, yBase)
                for (i in 1..segments) {
                    val x = i * step
                    val y = yBase +
                        sin(i * 0.72f + phase * (0.8f + layer * 0.19f)) * amp +
                        cos(i * 0.31f - slowPhase * (0.6f + layer * 0.08f)) * amp * 0.58f
                    path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = when (layer % 3) {
                        0 -> cs.primary.copy(alpha = 0.1f + musicEnergy * 0.07f + response)
                        1 -> cs.secondary.copy(alpha = 0.075f + musicEnergy * 0.05f)
                        else -> cs.tertiary.copy(alpha = 0.065f + musicEnergy * 0.06f)
                    },
                    style = Stroke(width = (2f + layer).dp.toPx(), cap = StrokeCap.Round),
                )
            }

            marksPath.reset()
            repeat(15) { i ->
                val drift = (i * 0.071f + sin(phase * (0.45f + i * 0.013f) + i) * 0.035f + 1f) % 1f
                val x = w * drift
                val top = h * (0.08f + (i % 6) * 0.14f)
                val height = h * (0.035f + 0.028f * movement * ((sin(slowPhase * (1f + i * 0.03f) + i) + 1f) / 2f))
                marksPath.moveTo(x, top)
                marksPath.lineTo(x + w * 0.12f, top + height)
            }
            drawPath(
                path = marksPath,
                color = cs.primary.copy(alpha = 0.05f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val center = Offset(w * (0.5f + seed.tilt * 0.025f), h * 0.42f)
        if (!lowFx) {
            repeat(4) { ring ->
                val radius = w * (0.18f + ring * 0.11f) +
                    sin(ultraSlowPhase + ring * 1.7f) * 12.dp.toPx()
                drawCircle(
                    color = cs.onSurface.copy(alpha = 0.018f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        if (glow > 0.01f) {
            drawCircle(
                color = cs.primary.copy(alpha = glow * 0.16f),
                radius = w * (0.16f + (1f - glow) * 0.2f),
                center = center,
                style = Stroke(width = (2f + glow * 4f).dp.toPx()),
            )
        }
    }
}

private data class BackgroundSeed(
    val phase: Float,
    val drift: Float,
    val tilt: Float,
)
