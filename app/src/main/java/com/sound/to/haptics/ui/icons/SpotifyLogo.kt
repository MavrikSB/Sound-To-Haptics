package com.sound.to.haptics.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun SpotifyLogo(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    waveTint: Color = MaterialTheme.colorScheme.surface,
) {
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val left = (size.width - diameter) * 0.5f
        val top = (size.height - diameter) * 0.5f
        val stroke = Stroke(
            width = diameter * 0.105f,
            cap = StrokeCap.Round,
        )

        fun x(fraction: Float) = left + diameter * fraction
        fun y(fraction: Float) = top + diameter * fraction

        drawCircle(
            color = tint,
            radius = diameter * 0.49f,
            center = androidx.compose.ui.geometry.Offset(
                x(0.5f),
                y(0.5f),
            ),
        )

        fun wave(
            start: Float,
            end: Float,
            baseline: Float,
            lift: Float,
            endDrop: Float,
        ): Path = Path().apply {
            moveTo(x(start), y(baseline))
            val width = end - start
            cubicTo(
                x(start + width * 0.28f), y(baseline - lift),
                x(end - width * 0.22f), y(baseline - lift * 0.92f),
                x(end), y(baseline + endDrop),
            )
        }

        drawPath(wave(0.14f, 0.86f, 0.38f, 0.105f, 0.035f), waveTint, style = stroke)
        drawPath(wave(0.22f, 0.78f, 0.54f, 0.078f, 0.028f), waveTint, style = stroke)
        drawPath(wave(0.30f, 0.70f, 0.68f, 0.052f, 0.020f), waveTint, style = stroke)
    }
}
