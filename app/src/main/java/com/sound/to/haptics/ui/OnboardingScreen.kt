package com.sound.to.haptics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.theme.Spacing

private data class TutorialPage(
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: @Composable () -> Unit,
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme
    val settings by AppSettings.state.collectAsState()
    val pages = remember {
        listOf(
            TutorialPage(
                "Welcome to Sound To Haptics",
                "Sound To Haptics is a revolutionary app designed to convert any audio into haptic feedback.",
                Icons.Rounded.Vibration,
            ) { SignalArtwork(cs.primary, cs.tertiary, mode = 0) },
            TutorialPage(
                "Offline Music",
                "Import a local song, let the analyzer build its haptic layers, and keep the converted OGG in your private library between launches.",
                Icons.Rounded.GraphicEq,
            ) { SignalArtwork(cs.primary, cs.secondary, mode = 1) },
            TutorialPage(
                "BitChord",
                "Connect BitChord to your YouTube Music account. The app plays through ExoPlayer and learns each track from the decoded audio itself.",
                Icons.Rounded.MusicNote,
            ) { SignalArtwork(cs.tertiary, cs.primary, mode = 2) },
            TutorialPage(
                "Feel Different Parts of Music",
                "Bass, beats, and vocals are carefully analyzed by the app as much as it can.",
                Icons.Rounded.GraphicEq,
            ) { LayerLegend(cs) },
            TutorialPage(
                "Haptic Strength",
                "Control the strength and intensity of the haptic feedback easily to your comfort, may not work with some devices.",
                Icons.Rounded.Tune,
            ) { StrengthArtwork(cs) },
            TutorialPage(
                "Start Listening",
                "Choose Offline or BitChord below and begin feeling your music.",
                Icons.Rounded.SkipNext,
            ) { SignalArtwork(cs.primary, cs.error, mode = 3) },
        )
    }

    BackHandler(enabled = page > 0) { page-- }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Spacing.Xl, vertical = Spacing.Lg),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) {
                    IconButton(onClick = { page-- }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Text("A short introduction", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    AppSettings.completeOnboarding()
                    onFinished()
                }) {
                    Text("Skip", color = cs.primary, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(Spacing.Lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                pages.indices.forEach { index ->
                    val selected by animateFloatAsState(
                        targetValue = if (index == page) 1f else 0.34f,
                        animationSpec = if (settings.reduceMotion) tween(1) else PressSpring,
                        label = "TutorialProgress",
                    )
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = selected)))
                }
            }
            Spacer(Modifier.height(Spacing.Xl))

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (settings.reduceMotion) {
                        fadeIn(tween(1)).togetherWith(fadeOut(tween(1)))
                    } else {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220)))
                            .togetherWith(fadeOut(tween(120)))
                    }
                },
                label = "TutorialPage",
                modifier = Modifier.weight(1f),
            ) { index ->
                val current = pages[index]
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(Modifier.size(88.dp), CircleShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Icon(current.icon, null, modifier = Modifier.size(42.dp)) }
                    }
                    Spacer(Modifier.height(Spacing.Xl))
                    current.accent()
                    Spacer(Modifier.height(Spacing.Xl))
                    Text(current.title, color = cs.onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.Sm))
                    Text(current.body, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(0.38f), shape = RoundedCornerShape(50)) { Text("Back") }
                }
                Button(
                    onClick = {
                        if (page == pages.lastIndex) {
                            AppSettings.completeOnboarding()
                            onFinished()
                        } else page++
                    },
                    modifier = Modifier.weight(if (page > 0) 0.62f else 1f).height(54.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary),
                ) {
                    Text(if (page == pages.lastIndex) "Start listening" else "Next", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SignalArtwork(first: Color, second: Color, mode: Int) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = Spacing.Xl)) {
        val path = Path()
        val base = size.height * 0.52f
        val steps = 32
        for (i in 0..steps) {
            val x = size.width * i / steps
            val envelope = when (mode) {
                0 -> 0.26f + 0.25f * kotlin.math.sin(i * 0.7f).let { kotlin.math.abs(it) }
                1 -> if (i % 7 == 0) 0.78f else 0.16f + (i % 4) * 0.035f
                2 -> 0.22f + 0.28f * kotlin.math.abs(kotlin.math.sin(i * 0.34f))
                else -> if (i in 12..18) 0.78f else 0.12f
            }
            val y = base - envelope * size.height * 0.44f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, first, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        drawLine(second.copy(alpha = 0.20f), androidx.compose.ui.geometry.Offset(0f, base), androidx.compose.ui.geometry.Offset(size.width, base), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun LayerLegend(cs: androidx.compose.material3.ColorScheme) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        LegendRow("Bass", "THUD", cs.primary, 0.84f)
        LegendRow("Beats", "IMPACT", cs.secondary, 0.62f)
        LegendRow("Vocals / movement", "SWISH", cs.tertiary, 0.42f)
    }
}

@Composable
private fun LegendRow(label: String, result: String, color: Color, strength: Float) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = CircleShape,
            color = color,
        ) { }
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, Modifier.weight(1f), color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
        Text("→ $result", color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(Spacing.Sm))
        Box(Modifier.width(48.dp).height(6.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(strength).height(6.dp).background(color, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun StrengthArtwork(cs: androidx.compose.material3.ColorScheme) {
    val values = listOf(0.18f, 0.32f, 0.48f, 0.68f, 0.86f)
    Row(Modifier.fillMaxWidth().height(84.dp).padding(horizontal = Spacing.Xxl), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        values.forEach { value ->
            Box(Modifier.weight(1f).fillMaxWidth().height((value * 84).dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.25f + value * 0.65f)))
        }
    }
}
