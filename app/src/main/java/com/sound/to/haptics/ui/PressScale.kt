package com.sound.to.haptics.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.LocalAmbientInteraction

/**
 * Shrinks a composable slightly while pressed and springs back on release —
 * the same tactile signature Pixel apps use on cards, chips, and buttons.
 * Pair with `.clickable(interactionSource = interactionSource, ...)` so the
 * source actually tracks the press.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.94f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val settings by AppSettings.state.collectAsState()
    val scale by animateFloatAsState(
        targetValue = if (settings.reduceMotion) 1f else if (pressed) pressedScale else 1f,
        animationSpec = if (settings.reduceMotion) tween(1) else PressSpring,
        label = "PressScale"
    )
    return this.scale(scale)
}

/** Convenience overload for call sites that don't need to share the interaction source elsewhere. */
@Composable
fun Modifier.pressScale(pressedScale: Float = 0.94f): Modifier =
    pressScale(remember { MutableInteractionSource() }, pressedScale)

/**
 * Drop-in replacements for the stock M3 buttons with the same press-scale
 * built in, plus an animated tonal elevation so the container color subtly
 * lifts while pressed — all in one, so call sites keep their simple API.
 * Deliberately skips a synthetic system haptic tick on press (see the note
 * inside PressableFilledTonalButton) since these buttons can be reachable
 * while real audio-driven haptics are playing.
 */
@Composable
fun PressableFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ambientInteraction = LocalAmbientInteraction.current
    val elevation by animateDpAsState(
        targetValue = if (pressed) 8.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "BtnTonalElevation",
    )
    FilledTonalButton(
        onClick = {
            // No performHapticFeedback: these buttons are reachable while a
            // haptic-audio track or a live vibration test is running, and a
            // system click vibration would steal the motor from it. See the
            // note on RoundIconBtn in HapticStudioScreen.kt for the full story.
            ambientInteraction()
            onClick()
        },
        modifier = modifier.pressScale(interaction, pressedScale = 0.96f),
        interactionSource = interaction,
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = elevation,
            pressedElevation = elevation,
        ),
        content = content,
    )
}

@Composable
fun PressableOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val ambientInteraction = LocalAmbientInteraction.current
    OutlinedButton(
        onClick = {
            ambientInteraction()
            onClick()
        },
        modifier = modifier.pressScale(interaction, pressedScale = 0.96f),
        interactionSource = interaction,
        content = content,
    )
}
