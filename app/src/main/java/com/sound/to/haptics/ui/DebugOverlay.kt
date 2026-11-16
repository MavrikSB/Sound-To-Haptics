package com.sound.to.haptics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun DebugOverlay(
    wireframeBounds: Boolean,
    alignmentGrid: Boolean,
    marginPaddingOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!wireframeBounds && !alignmentGrid && !marginPaddingOverlay) return

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        if (alignmentGrid) {
            val step = 8.dp.toPx()
            val gridColor = Color.Magenta.copy(alpha = 0.16f)
            var x = 0f
            while (x < w) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < h) {
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += step
            }
        }

        if (marginPaddingOverlay) {
            val outer = 24.dp.toPx()
            val inner = 16.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            drawRect(
                color = Color.Cyan.copy(alpha = 0.55f),
                topLeft = Offset(outer, outer),
                size = androidx.compose.ui.geometry.Size(w - outer * 2, h - outer * 2),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
            )
            drawRect(
                color = Color.Yellow.copy(alpha = 0.4f),
                topLeft = Offset(inner, inner),
                size = androidx.compose.ui.geometry.Size(w - inner * 2, h - inner * 2),
                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
            )
        }

        if (wireframeBounds) {
            val borderColor = Color(0xFF00E5FF)
            drawRect(
                color = borderColor,
                topLeft = Offset(1f, 1f),
                size = androidx.compose.ui.geometry.Size(w - 2f, h - 2f),
                style = Stroke(width = 2.dp.toPx()),
            )
            val crossSize = 10.dp.toPx()
            val cx = w / 2f
            val cy = h / 2f
            drawLine(borderColor, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), strokeWidth = 1.5.dp.toPx())
            drawLine(borderColor, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), strokeWidth = 1.5.dp.toPx())
        }
    }
}
