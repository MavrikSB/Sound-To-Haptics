package com.sound.to.haptics.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sound.to.haptics.settings.PlaybackActivity
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.ui.motion.CountUpSpring
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.motion.MaterialSpringBouncy
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.theme.Spacing
import com.sound.to.haptics.ui.LocalAmbientInteraction

/**
 * A card that responds to touch like a Pixel card: it scales down slightly,
 * rounds its corners, drops its tonal elevation, and shows a ripple while
 * pressed, then springs back on release. Pressing also triggers a light
 * haptic tick so every interaction feels confirmed.
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val ambientInteraction = LocalAmbientInteraction.current
    val musicPlaying by PlaybackActivity.isPlaying.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (settings.reduceMotion) 1f else if (pressed) 0.975f else 1f,
        animationSpec = if (settings.reduceMotion) tween(1) else PressSpring,
        label = "PressableCardScale",
    )
    val elevation by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (pressed) 0.dp else 4.dp,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "PressableCardElevation",
    )
    // Corner "settle": the default Pixel-style card tightens its radius
    // slightly while pressed. Custom shapes keep their own geometry and get
    // the scale + tonal elevation feedback instead. (Detected via the public
    // CornerSize(Dp) factory — DpCornerSize itself is private.)
    val settleShape = (shape as? RoundedCornerShape)?.topStart == CornerSize(28.dp)
    val cornerRadius by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (pressed) 22.dp else 28.dp,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "PressableCardRadius",
    )
    Surface(
        onClick = {
            // UI confirmation must never compete with a musical haptic stream.
            if (!musicPlaying) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ambientInteraction()
            onClick()
        },
        modifier = modifier.scale(scale),
        shape = if (settleShape) RoundedCornerShape(cornerRadius) else shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = elevation,
        interactionSource = interaction,
    ) {
        // M3's clickable Surface content has no receiver scope, so give the
        // Column-scoped content a Column to live in.
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Swaps icons with a spring pop — scale in + fade, old icon fades out fast.
 * Used for play/pause, stop/start, and any stateful icon.
 */
@Composable
fun AnimatedIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (fadeIn(tween(110)) + scaleIn(initialScale = 0.5f, animationSpec = MaterialSpringBouncy))
                .togetherWith(fadeOut(tween(60)))
        },
        label = "AnimatedIcon",
    ) { current ->
        Icon(current, contentDescription, modifier = modifier, tint = tint)
    }
}

/**
 * Shimmering placeholder used for skeleton loading states. Cheap: a single
 * animated gradient sweep, no blur, no per-frame layout work.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "SkeletonShimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "SkeletonX",
    )
    val base = cs.onSurface.copy(alpha = 0.06f)
    val highlight = cs.onSurface.copy(alpha = 0.14f)
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = androidx.compose.ui.geometry.Offset(-200f + x * 500f, 0f),
        end = androidx.compose.ui.geometry.Offset(100f + x * 500f, 0f),
    )
    Box(modifier = modifier.clip(shape).background(brush))
}

/** Skeleton variant that fills its parent's width, useful for list rows. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, trailing: Boolean = false) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        SkeletonBox(Modifier.size(44.dp), RoundedCornerShape(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth(0.6f).height(14.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.4f).height(11.dp), RoundedCornerShape(4.dp))
        }
        if (trailing) SkeletonBox(Modifier.size(20.dp), CircleShape)
    }
}

/**
 * Smoothly rolls a numeric value toward its target using spring physics —
 * sensor reads, percentages, timestamps. The text only reformats when the
 * animated value actually changes, so fast sources stay cheap.
 */
@Composable
fun RollingNumber(
    value: Float,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
) {
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = CountUpSpring,
        label = "RollingNumber",
    )
    Text(
        text = format(animated),
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
    )
}

/**
 * Small tonal pill for live status — "Synced", "Learning…", "Needs access".
 * The colored dot pulses while [active] is true.
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "StatusPill")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "StatusPulse",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) pulse else 0.55f)),
            )
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Section header with a tinted icon chip — consistent across all screens. */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(Spacing.Md))
        androidx.compose.foundation.layout.Column {
            Text(
                title,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Staggered entrance: content fades in and settles from below, delayed by
 * [index] so sequences of siblings animate in one after another.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val settings by AppSettings.state.collectAsState()
    val reduceMotion = settings.reduceMotion
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(1)) else fadeIn(tween(300, delayMillis = index * 60)) +
            slideInVertically(tween(320, delayMillis = index * 60)) { h -> h / 4 },
        exit = if (reduceMotion) fadeOut(tween(1)) else fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
    ) {
        content()
    }
}

/**
 * Animated success checkmark: the halo circle pops in with a bouncy spring,
 * then a rounded stroke draws the check — the "done" moment of Pixel-style
 * confirmation states. Rendered on a single Canvas, so it is cheap enough
 * for inline list rows and status headers.
 */
@Composable
fun SuccessCheckmark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
) {
    val scale = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = MaterialSpringBouncy)
        draw.animateTo(1f, animationSpec = tween(320, easing = EmphasizedDecelerate))
    }
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val radius = size.toPx() / 2f - stroke / 2f
        val center = Offset(size.toPx() / 2f, size.toPx() / 2f)

        drawCircle(
            color = color.copy(alpha = 0.16f),
            radius = radius * scale.value,
            center = center,
        )

        val check = Path().apply {
            val s = radius * 0.52f
            moveTo(center.x - s, center.y * 1.02f)
            lineTo(center.x - s * 0.22f, center.y + s * 0.72f)
            lineTo(center.x + s * 1.05f, center.y - s * 0.55f)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val visible = Path()
        measure.getSegment(0f, measure.length * draw.value, visible, true)
        drawPath(
            path = visible,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
