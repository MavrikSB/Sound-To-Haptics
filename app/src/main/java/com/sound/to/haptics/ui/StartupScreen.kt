package com.sound.to.haptics.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sound.to.haptics.ui.theme.Spacing
import com.sound.to.haptics.ui.motion.scaleDurationMs
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StartupScreen(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(110)
        entered = true
    }

    val logoScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.64f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "StartupLogoScale",
    )
    val logoRotation by animateFloatAsState(
        targetValue = if (entered) 0f else -12f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "StartupLogoRotation",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(520, delayMillis = 100),
        label = "StartupAlpha",
    )
    val contentOffset by animateFloatAsState(
        targetValue = if (entered) 0f else 22f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "StartupOffset",
    )

    val scrimAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(700, delayMillis = 60, easing = FastOutSlowInEasing),
        label = "StartupScrim",
    )

    Box(modifier.fillMaxSize().background(cs.background)) {
        DriftingBackground(energy = 0.34f, lowFx = true)

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            cs.background.copy(alpha = 0.35f * scrimAlpha),
                            Color.Transparent,
                            cs.background.copy(alpha = 0.45f * scrimAlpha),
                        ),
                    ),
                ),
        )
        StartupAtmosphere()
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = Spacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(240.dp)
                        .graphicsLayer { alpha = contentAlpha * 0.9f }
                        .background(
                            Brush.radialGradient(
                                listOf(cs.primary.copy(alpha = 0.22f), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )
                Surface(
                    modifier = Modifier
                        .size(148.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                            rotationZ = logoRotation
                        },
                    shape = CircleShape,
                    color = Color.Transparent,
                    shadowElevation = 22.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(cs.primary, cs.secondary, cs.tertiary),
                                ),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(122.dp),
                            shape = CircleShape,
                            color = cs.primaryContainer.copy(alpha = 0.94f),
                            tonalElevation = 4.dp,
                        ) {
                            StartupMark()
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Xl + 4.dp))
            Surface(
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
                shape = RoundedCornerShape(50),
                color = cs.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    "HAPTIC AUDIO",
                    modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 4.dp),
                    color = cs.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.Lg))
            Text(
                "Sound To Haptics",
                modifier = Modifier.graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset
                },
                color = cs.onSurface,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                "Feel every layer of the music",
                modifier = Modifier.graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset
                },
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.Xxl))
            Row(
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                repeat(5) { index ->
                    LoadingBeat(index)
                }
            }
        }
    }
}

@Composable
private fun StartupAtmosphere() {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "StartupAtmosphere")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(scaleDurationMs(12000), easing = LinearEasing)),
        label = "StartupAtmospherePhase",
    )
    val breath by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(scaleDurationMs(3200)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "StartupAtmosphereBreath",
    )

    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height * 0.42f)
        val radius = size.minDimension * 0.22f
        val maxOrbitRadius = size.minDimension * 0.68f
        val widthFactor = size.width * 0.06f
        val heightFactor = size.height * 0.04f
        val strokeWidthBasePx = 1.1f.dp.toPx()
        val strokeWidthIncrementPx = 0.2f.dp.toPx()

        drawCircle(
            brush = Brush.radialGradient(
                listOf(cs.primary.copy(alpha = 0.15f), cs.primary.copy(alpha = 0f)),
                center = center,
                radius = maxOrbitRadius,
            ),
            radius = maxOrbitRadius,
            center = center,
        )
        repeat(3) { index ->
            val orbitMultiplier = 1.65f + index * 0.42f
            val orbit = radius * orbitMultiplier * breath

            val phaseFactorX = 0.32f + index * 0.04f
            val phaseFactorY = 0.26f + index * 0.03f
            val angleOffset = index.toFloat()

            val orbitCenter = Offset(
                center.x + cos(phase * phaseFactorX + angleOffset) * widthFactor,
                center.y + sin(phase * phaseFactorY + angleOffset) * heightFactor,
            )
            drawCircle(
                color = when (index % 3) {
                    0 -> cs.primary.copy(alpha = 0.09f)
                    1 -> cs.secondary.copy(alpha = 0.06f)
                    else -> cs.tertiary.copy(alpha = 0.06f)
                },
                radius = orbit,
                center = orbitCenter,
                style = Stroke(width = strokeWidthBasePx + index * strokeWidthIncrementPx),
            )
        }
    }
}

@Composable
private fun StartupMark() {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "StartupMark")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(scaleDurationMs(1800), easing = LinearEasing)),
        label = "StartupMarkPhase",
    )

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val barWidth = 12.dp.toPx()
        val xOffsetMultiplier = 20.dp.toPx()

        repeat(5) { i ->
            val x = cx + (i - 2) * xOffsetMultiplier
            val waveAmplitude = 0.34f
            val waveBase = 0.42f
            val sinOffset = i * 0.9f

            val wave = waveBase + waveAmplitude * ((sin(phase + sinOffset) + 1f) / 2f)
            val half = size.height * wave * 0.28f
            drawLine(
                color = cs.onPrimaryContainer,
                start = Offset(x, cy - half),
                end = Offset(x, cy + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun LoadingBeat(index: Int) {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "StartupBeat$index")
    val heightFraction by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(scaleDurationMs(560 + index * 90), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "StartupBeatHeight$index",
    )
    val tint = when (index % 3) {
        0 -> cs.primary
        1 -> cs.secondary
        else -> cs.tertiary
    }
    Box(
        Modifier
            .width(6.dp)
            .height(22.dp * heightFraction)
            .clip(RoundedCornerShape(50))
            .background(tint),
    )
}
