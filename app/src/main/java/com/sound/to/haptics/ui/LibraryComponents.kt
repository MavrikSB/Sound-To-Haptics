package com.sound.to.haptics.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sound.to.haptics.library.HapticSong
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.theme.Spacing
import java.util.Locale

@Composable
fun HapticSongRow(
    song: HapticSong,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showDeleteDialog by remember(song.id) { mutableStateOf(false) }
    val interaction = remember(song.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, PressSpring, label = "SongRowScale")
    Surface(
        modifier = Modifier.fillMaxWidth().scale(scale),
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(56.dp), RoundedCornerShape(16.dp), color = Color.Transparent) {
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.LibraryMusic, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Haptic OGG  •  ${formatDuration(song.durationMs)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            SongIconButton(onClick = onPlay, icon = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, description = if (isPlaying) "Playing" else "Play ${song.title}", filled = true)
            SongIconButton(onClick = { showDeleteDialog = true }, icon = Icons.Rounded.MoreVert, description = "Options for ${song.title}", filled = false)
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${song.title}?") },
            text = { Text("This removes the saved haptic track from this device. The original audio is not changed.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SongIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, filled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, PressSpring, label = "SongIconScale")
    Surface(onClick = onClick, modifier = Modifier.size(42.dp).scale(scale), shape = CircleShape, color = if (filled) cs.primary else cs.surfaceContainerHighest, contentColor = if (filled) cs.onPrimary else cs.onSurfaceVariant, interactionSource = interaction) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
fun EmptyLibraryCard(onConvert: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.Xl, horizontal = Spacing.Lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(Modifier.size(62.dp), CircleShape, color = cs.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(Spacing.Md))
            Text("Your haptic library is empty", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.Xs))
            Text("Convert a song and it will stay here between launches.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.Lg))
            Surface(onClick = onConvert, shape = RoundedCornerShape(50), color = cs.primary, contentColor = cs.onPrimary) {
                Text("Convert a song", modifier = Modifier.padding(horizontal = Spacing.Xl, vertical = Spacing.Md), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
