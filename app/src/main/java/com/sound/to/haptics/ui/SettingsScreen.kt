package com.sound.to.haptics.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.settings.BackgroundStyle
import com.sound.to.haptics.settings.HapticEngine
import com.sound.to.haptics.settings.ThemeMode
import com.sound.to.haptics.ui.components.SectionHeader
import com.sound.to.haptics.ui.components.StaggeredEntrance
import com.sound.to.haptics.ui.motion.MaterialSpringBouncy
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.theme.Spacing
import android.os.Build

/**
 * App-wide, user-facing settings — theme, background, motion, haptics, and a
 * couple of housekeeping actions. Reads and writes straight through
 * [AppSettings], which is what actually drives MainActivity's theme and
 * RootScreen's background choice; nothing here is a debug-only toggle.
 */
@Composable
fun SettingsScreen(
    onClearHapticCache: () -> Unit,
    onClearLibrary: () -> Unit = {},
    onOpenTutorial: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val settings by AppSettings.state.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearLibraryDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(Spacing.Xl),
            ) {
                SettingsHeader()
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(0) {
                    SettingsSectionCard(title = "Appearance", icon = Icons.Rounded.Palette) {
                        Text(
                            "Theme",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(Spacing.Sm))
                        SegmentedSelector(
                            options = ThemeMode.entries,
                            selected = settings.themeMode,
                            onSelect = AppSettings::setThemeMode,
                            label = { it.label },
                            icon = {
                                when (it) {
                                    ThemeMode.SYSTEM -> Icons.Rounded.PhoneAndroid
                                    ThemeMode.LIGHT -> Icons.Rounded.LightMode
                                    ThemeMode.DARK -> Icons.Rounded.DarkMode
                                }
                            },
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Spacer(Modifier.height(Spacing.Lg))
                            ToggleSettingRow(
                                icon = Icons.Rounded.Contrast,
                                label = "Dynamic color",
                                description = "Match colors to your wallpaper (Android 12+)",
                                checked = settings.dynamicColor,
                                onCheckedChange = AppSettings::setDynamicColor,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(1) {
                    SettingsSectionCard(title = "Background", icon = Icons.Rounded.Wallpaper) {
                        Text(
                            "Behind the UI",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(Spacing.Sm))
                        SegmentedSelector(
                            options = BackgroundStyle.entries,
                            selected = settings.backgroundStyle,
                            onSelect = AppSettings::setBackgroundStyle,
                            label = { it.label },
                        )
                        Spacer(Modifier.height(Spacing.Sm))
                        Text(
                            settings.backgroundStyle.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(2) {
                    SettingsSectionCard(title = "Motion & Haptics", icon = Icons.Rounded.Vibration) {
                        ToggleSettingRow(
                            icon = Icons.Rounded.Settings,
                            label = "Reduce motion",
                            description = "Shorter, and more static animations.",
                            checked = settings.reduceMotion,
                            onCheckedChange = AppSettings::setReduceMotion,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        SliderSettingRow(
                            icon = Icons.Rounded.Vibration,
                            label = "Haptic strength",
                            valueText = "${(settings.hapticStrength * 100).toInt()}%",
                            value = settings.hapticStrength,
                            onChange = AppSettings::setHapticStrength,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        SliderSettingRow(
                            icon = Icons.Rounded.GraphicEq,
                            label = "Bass emphasis",
                            valueText = "${(settings.bassEmphasis * 100).toInt()}%",
                            value = settings.bassEmphasis,
                            onChange = AppSettings::setBassEmphasis,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        SliderSettingRow(
                            icon = Icons.Rounded.Waves,
                            label = "Signal density",
                            valueText = "${(settings.hapticDensity * 100).toInt()}%",
                            value = settings.hapticDensity,
                            onChange = AppSettings::setHapticDensity,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        Text(
                            "Haptic engine",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(Spacing.Sm))
                        SegmentedSelector(
                            options = HapticEngine.entries,
                            selected = settings.hapticEngine,
                            onSelect = AppSettings::setHapticEngine,
                            label = { it.label },
                        )
                        Spacer(Modifier.height(Spacing.Sm))
                        Text(
                            settings.hapticEngine.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        ToggleSettingRow(
                            icon = Icons.Rounded.ScreenLockPortrait,
                            label = "Keep screen on while playing",
                            description = "Prevents the display sleeping mid-track",
                            checked = settings.keepScreenOnWhilePlaying,
                            onCheckedChange = AppSettings::setKeepScreenOnWhilePlaying,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(3) {
                    SettingsSectionCard(title = "Storage", icon = Icons.Rounded.DeleteSweep) {
                        ToggleSettingRow(
                            icon = Icons.Rounded.Settings,
                            label = "Confirm before clearing cache",
                            description = "Ask before deleting cached BitChord haptic files",
                            checked = settings.confirmBeforeClearingCache,
                            onCheckedChange = AppSettings::setConfirmBeforeClearingCache,
                        )
                        Spacer(Modifier.height(Spacing.Md))
                        ToggleSettingRow(
                            icon = Icons.Rounded.DeleteSweep,
                            label = "Confirm before clearing library",
                            description = "Ask before deleting converted offline songs",
                            checked = settings.confirmBeforeClearingLibrary,
                            onCheckedChange = AppSettings::setConfirmBeforeClearingLibrary,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        SettingsActionRow(
                            label = "Clear cached haptic files",
                            description = "Frees space; BitChord songs can be re-cached again",
                            onClick = {
                                if (settings.confirmBeforeClearingCache) showClearCacheDialog = true
                                else onClearHapticCache()
                            },
                            destructive = true,
                        )
                        Spacer(Modifier.height(Spacing.Md))
                        SettingsActionRow(
                            label = "Clear offline library",
                            description = "Delete converted songs saved inside the app",
                            onClick = {
                                if (settings.confirmBeforeClearingLibrary) showClearLibraryDialog = true
                                else onClearLibrary()
                            },
                            destructive = true,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(4) {
                    SettingsSectionCard(title = "Help", icon = Icons.Rounded.PlayCircle) {
                        SettingsActionRow(
                            label = "Replay the introduction",
                            description = "Review bass thuds, beat impacts, flow, and capture basics",
                            onClick = onOpenTutorial,
                            destructive = false,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(5) {
                    SettingsSectionCard(title = "Reset", icon = Icons.Rounded.RestartAlt) {
                        SettingsActionRow(
                            label = "Reset settings to default",
                            description = "Theme, background, and motion preferences only",
                            onClick = { showResetDialog = true },
                            destructive = false,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Xxl))
            }
        }
    }

    if (showClearCacheDialog) {
        SettingsConfirmDialog(
            title = "Clear cached haptics?",
            message = "Cached haptic files for BitChord tracks will be deleted. They'll be regenerated automatically the next time you play those songs.",
            confirmLabel = "Clear",
            onConfirm = {
                onClearHapticCache()
                showClearCacheDialog = false
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }

    if (showClearLibraryDialog) {
        SettingsConfirmDialog(
            title = "Clear offline library?",
            message = "Every converted song and its beat analysis will be removed from this device.",
            confirmLabel = "Delete library",
            onConfirm = {
                onClearLibrary()
                showClearLibraryDialog = false
            },
            onDismiss = { showClearLibraryDialog = false },
        )
    }

    if (showResetDialog) {
        SettingsConfirmDialog(
            title = "Reset settings?",
            message = "Theme, background, and motion preferences go back to their defaults. This doesn't affect cached haptic files.",
            confirmLabel = "Reset",
            onConfirm = {
                AppSettings.resetToDefaults()
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

@Composable
private fun SettingsHeader() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(Spacing.Md))
        Column {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Text("Appearance, motion, and storage", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.Lg)) {
            SectionHeader(icon = icon, title = title)
            Spacer(Modifier.height(Spacing.Lg))
            content()
        }
    }
}

/**
 * A pill-shaped segmented control — the Material You way to pick one of a
 * small closed set of options (theme mode, background style) without the
 * visual weight of a full dialog or dropdown. The selected segment slides
 * into place with a spring rather than popping.
 */
@Composable
private fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: ((T) -> ImageVector)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.95f else 1f,
                animationSpec = PressSpring,
                label = "SegmentScale",
            )
            val bg by animateColorAsState(
                targetValue = if (isSelected) cs.primary else Color.Transparent,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "SegmentBg",
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) cs.onPrimary else cs.onSurfaceVariant,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "SegmentFg",
            )
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f).scale(scale),
                shape = RoundedCornerShape(50),
                color = bg,
                contentColor = fg,
                interactionSource = interaction,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        androidx.compose.material3.Icon(icon(option), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val switchScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.94f,
        animationSpec = MaterialSpringBouncy,
        label = "SettingSwitchBounce",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(Spacing.Sm))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(switchScale),
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.outline,
                uncheckedTrackColor = cs.surfaceContainerHighest,
            ),
        )
    }
}

@Composable
private fun SliderSettingRow(
    icon: ImageVector,
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(Spacing.Md))
            Text(label, color = cs.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, color = cs.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(start = 48.dp),
            colors = SliderDefaults.colors(
                thumbColor = cs.primary,
                activeTrackColor = cs.primary,
                inactiveTrackColor = cs.onSurface.copy(alpha = 0.12f),
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = PressSpring,
        label = "SettingsActionScale",
    )
    val containerColor = if (destructive) cs.errorContainer else cs.secondaryContainer
    val contentColor = if (destructive) cs.onErrorContainer else cs.onSecondaryContainer
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().scale(scale),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Column(Modifier.padding(Spacing.Lg)) {
            Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = MaterialTheme.shapes.large,
    )
}
