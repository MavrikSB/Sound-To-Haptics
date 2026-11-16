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

@Composable
fun Modifier.pressScale(pressedScale: Float = 0.94f): Modifier =
    pressScale(remember { MutableInteractionSource() }, pressedScale)

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
