@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sound.to.haptics.debug

import android.hardware.Sensor
import android.os.VibrationEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sound.to.haptics.haptics.AnalysisDiagnostics
import com.sound.to.haptics.haptics.CaptureDiagnostics
import com.sound.to.haptics.haptics.TransientDiagnostics
import com.sound.to.haptics.haptics.TransientQualityTest
import com.sound.to.haptics.ui.AdaptiveContentWidth
import com.sound.to.haptics.ui.PressableFilledTonalButton
import com.sound.to.haptics.ui.PressableOutlinedButton
import com.sound.to.haptics.ui.pressScale
import com.sound.to.haptics.ui.components.AnimatedIcon
import com.sound.to.haptics.ui.components.RollingNumber
import com.sound.to.haptics.ui.components.SectionHeader
import com.sound.to.haptics.ui.components.StaggeredEntrance
import com.sound.to.haptics.ui.components.SuccessCheckmark
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.motion.fadeThroughTransition
import com.sound.to.haptics.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The developer debug menu. Only reachable from a `BuildConfig.DEBUG` build
 * — see RootScreen for the gating that keeps this out of release builds.
 */
@Composable
fun DebugToolsScreen(viewModel: DebugToolsViewModel = viewModel()) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        viewModel.startSensors()
        onDispose {
            viewModel.stopSensors()
            viewModel.stopEightDAudio()
            viewModel.stopQualityTest()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(cs.background)) {
        AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(Spacing.Xl),
            ) {
                DebugMenuHeader()
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(0) {
                    SectionCard(title = "Performance Overlay", icon = Icons.Rounded.Speed) {
                        PerformanceOverlaySection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(1) {
                    SectionCard(title = "Layout & Motion Inspector", icon = Icons.Rounded.Tune) {
                        LayoutMotionInspectorSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(2) {
                    SectionCard(title = "Feature Toggles", icon = Icons.Rounded.Science) {
                        FeatureToggleSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(3) {
                    SectionCard(title = "Console Log Viewer", icon = Icons.Rounded.Storage) {
                        ConsoleLogSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                StaggeredEntrance(4) {
                    SectionCard(title = "Device", icon = Icons.Rounded.Science) {
                        DeviceInfoSection(viewModel.deviceInfo)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(5) {
                    SectionCard(title = "System", icon = Icons.Rounded.Speed) {
                        SystemStatsSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(6) {
                    SectionCard(title = "Haptic Capabilities", icon = Icons.Rounded.Vibration) {
                        HapticCapabilitiesSection(viewModel.hapticCapabilities)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(7) {
                    SectionCard(title = "Haptic Testing", icon = Icons.Rounded.Bolt) {
                        HapticTestingSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(8) {
                    SectionCard(title = "Haptic Sequencer", icon = Icons.Rounded.GraphicEq) {
                        HapticSequencerSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(9) {
                    SectionCard(title = "Haptic Calibration", icon = Icons.Rounded.Tune) {
                        HapticCalibrationSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(10) {
                    SectionCard(title = "8D Audio Demo", icon = Icons.Rounded.GraphicEq) {
                        EightDAudioSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(11) {
                    SectionCard(title = "Motion Sensors", icon = Icons.Rounded.Science) {
                        MotionSensorsSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(12) {
                    SectionCard(title = "Sound Meter", icon = Icons.Rounded.Mic) {
                        SoundMeterSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(13) {
                    SectionCard(title = "Storage & Reports", icon = Icons.Rounded.Storage) {
                        StorageSection(viewModel, onCopyReport = {
                            clipboard.setText(AnnotatedString(viewModel.buildDebugReport()))
                        })
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(14) {
                    SectionCard(title = "Musical Layer Analysis", icon = Icons.Rounded.GraphicEq) {
                        AnalysisDiagnosticsSection()
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(15) {
                    SectionCard(title = "Transient Mode Diagnostics", icon = Icons.Rounded.GraphicEq) {
                        TransientDiagnosticsSection(viewModel)
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                StaggeredEntrance(16) {
                    SectionCard(title = "Spotify Capture Diagnostics", icon = Icons.Rounded.GraphicEq) {
                        CaptureDiagnosticsSection()
                    }
                }
                Spacer(Modifier.height(Spacing.Xxl))
            }
        }
    }
}

@Composable
private fun DebugMenuHeader() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = cs.onErrorContainer.copy(alpha = 0.12f), contentColor = cs.onErrorContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    "DEBUG MENU",
                    color = cs.onErrorContainer,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                "Not included in release builds.",
                color = cs.onErrorContainer.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
private fun PerformanceOverlaySection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val frame by viewModel.frameMetrics.collectAsState()
    val heap by viewModel.heap.collectAsState()
    val framePressure = (frame.frameTimeMs / frame.targetFrameMs).coerceIn(0f, 1.4f)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        MetricTile("FPS", "%.1f".format(frame.fps), modifier = Modifier.weight(1f))
        MetricTile("Frame", "%.1fms".format(frame.frameTimeMs), modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(Spacing.Md))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        MetricTile("Heap", "${heap.usedMb} / ${heap.maxMb} MB", modifier = Modifier.weight(1f))
        MetricTile("Drops 60s", frame.droppedFramesLast60s.toString(), modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(Spacing.Lg))
    Text("Frame budget", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.Sm))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(framePressure.coerceIn(0.03f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(if (framePressure > 1f) cs.error else cs.tertiary)
        )
    }
    Text(
        "Target %.1fms; warning starts above %.1fms.".format(frame.targetFrameMs, frame.targetFrameMs * 1.5f),
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = Spacing.Xs),
    )
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, tonalElevation = 1.dp, modifier = modifier) {
        Column(Modifier.padding(Spacing.Md)) {
            Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                color = cs.onSurface,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun LayoutMotionInspectorSection(viewModel: DebugToolsViewModel) {
    val wireframe by viewModel.wireframeBounds.collectAsState()
    val grid by viewModel.alignmentGrid.collectAsState()
    val margins by viewModel.marginPaddingOverlay.collectAsState()
    val motionSpeed by viewModel.motionSpeed.collectAsState()

    ToggleRow("Wireframe bounds", wireframe, viewModel::setWireframeBounds)
    ToggleRow("Alignment grid", grid, viewModel::setAlignmentGrid)
    ToggleRow("Margins / padding", margins, viewModel::setMarginPaddingOverlay)
    Spacer(Modifier.height(Spacing.Md))
    Text("Speed", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.Sm))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        MotionSpeed.values().forEach { speed ->
            MotionSpeedChip(
                speed = speed,
                selected = speed == motionSpeed,
                onClick = { viewModel.setMotionSpeed(speed) },
            )
        }
    }
}

@Composable
private fun MotionSpeedChip(speed: MotionSpeed, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) cs.secondaryContainer else Color.Transparent,
            contentColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        ),
    ) {
        Text(speed.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FeatureToggleSection(viewModel: DebugToolsViewModel) {
    val flags by viewModel.featureFlags.collectAsState()

    ToggleRow("Dynamic background", flags.dynamicBackground) {
        viewModel.updateFeatureFlags { current -> current.copy(dynamicBackground = it) }
    }
    ToggleRow("Monet engine", flags.monetEngine) {
        viewModel.updateFeatureFlags { current -> current.copy(monetEngine = it) }
    }
    ToggleRow("Fallback theme", flags.fallbackTheme) {
        viewModel.updateFeatureFlags { current -> current.copy(fallbackTheme = it) }
    }
    ToggleRow("Force dark tonal elevation", flags.forceDarkTonalElevation) {
        viewModel.updateFeatureFlags { current -> current.copy(forceDarkTonalElevation = it) }
    }
    ToggleRow("Bypass boot animation", flags.bypassBootAnimation) {
        viewModel.updateFeatureFlags { current -> current.copy(bypassBootAnimation = it) }
    }
    ToggleRow("Simulate low-end GPU", flags.simulateLowEndGpu) {
        viewModel.updateFeatureFlags { current -> current.copy(simulateLowEndGpu = it) }
    }
}

@Composable
private fun ConsoleLogSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val lines by viewModel.consoleLines.collectAsState()

    Surface(shape = MaterialTheme.shapes.large, color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.Md)) {
            Text("Filter: INFO / WARN / ERROR / DEBUG", color = cs.tertiary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(Spacing.Sm))
            lines.takeLast(8).forEach { line ->
                Text("> $line", color = Color.White.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = Spacing.Lg))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = cs.tertiary),
        )
    }
}

@Composable
private fun DeviceInfoSection(info: DeviceInfo) {
    Column {
        InfoRow("Manufacturer", info.manufacturer)
        InfoRow("Model", info.model)
        InfoRow("Device / Board", "${info.device} / ${info.board}")
        InfoRow("Android", "${info.androidVersion} (API ${info.apiLevel})")
        InfoRow("Build", info.buildId)
        InfoRow("Fingerprint", info.fingerprint)
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.Sm), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        InfoRow("Package", info.applicationId)
        InfoRow("App Version", "${info.appVersionName} (${info.appVersionCode})")
    }
}

@Composable
private fun SystemStatsSection(viewModel: DebugToolsViewModel) {
    val battery by viewModel.battery.collectAsState()
    val memory by viewModel.memory.collectAsState()
    val display = viewModel.displayInfo
    val permissions = viewModel.permissionStatus

    InfoRow("Battery", "${battery.percent}%${if (battery.isCharging) " (charging)" else ""}")
    InfoRow("Battery temp", "%.1f°C".format(battery.temperatureC))
    InfoRow("Memory free", "${memory.availableMb} MB / ${memory.totalMb} MB")
    AnimatedVisibility(
        visible = memory.lowMemory,
        enter = fadeIn(androidx.compose.animation.core.tween(200)) + expandVertically(),
        exit = fadeOut(androidx.compose.animation.core.tween(150)) + shrinkVertically(),
    ) {
        Text("Low memory right now", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
    InfoRow("Screen", "${display.widthPx}×${display.heightPx} @ ${display.densityDpi}dpi")
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.Sm), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    CapabilityRow("Microphone permission", permissions.recordAudio)
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            color = cs.onSurface,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = Spacing.Lg),
        )
    }
}

@Composable
private fun HapticCapabilitiesSection(capabilities: HapticCapabilities) {
    Column {
        CapabilityRow("Vibrator", capabilities.hasVibrator)
        CapabilityRow("Amplitude control", capabilities.hasAmplitudeControl)
        CapabilityRow("Predefined effects", capabilities.supportsPredefinedEffects)
        CapabilityRow("Composed primitives", capabilities.supportsComposedPrimitives)
        CapabilityRow("Audio-coupled haptics*", capabilities.likelySupportsAudioCoupledHaptics)
        Text(
            "* best guess, not a real platform check",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = Spacing.Sm),
        )
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = cs.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        AnimatedIcon(
            if (supported) Icons.Rounded.Check else Icons.Rounded.Close,
            contentDescription = if (supported) "Yes" else "No",
            modifier = Modifier.size(18.dp),
            tint = if (supported) SUPPORTED_COLOR else cs.error,
        )
    }
}

@Composable
private fun HapticTestingSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    var amplitude by rememberSaveable { mutableFloatStateOf(128f) }
    val isVibratingContinuously by viewModel.isVibratingContinuously.collectAsState()
    val isSweeping by viewModel.isSweeping.collectAsState()

    Text("Amplitude", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    RollingNumber(
        value = amplitude,
        format = { "${it.roundToInt()} / 255" },
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = amplitude,
        onValueChange = { amplitude = it },
        valueRange = 1f..255f,
        colors = SliderDefaults.colors(thumbColor = cs.tertiary, activeTrackColor = cs.tertiary),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), modifier = Modifier.fillMaxWidth()) {
        PressableFilledTonalButton(onClick = { viewModel.testVibration(amplitude.roundToInt()) }, modifier = Modifier.weight(1f)) {
            AnimatedIcon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Xs))
            Text("Pulse")
        }
        val holdContainer by animateColorAsState(
            targetValue = if (isVibratingContinuously) cs.error.copy(alpha = 0.2f) else cs.secondaryContainer,
            animationSpec = androidx.compose.animation.core.tween(200, easing = EmphasizedDecelerate),
            label = "HoldButtonColor",
        )
        PressableFilledTonalButton(
            onClick = { viewModel.toggleContinuousVibration(amplitude.roundToInt()) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = holdContainer),
        ) {
            Text(if (isVibratingContinuously) "Stop" else "Hold")
        }
    }

    Spacer(Modifier.height(Spacing.Lg))
    Text("Amplitude sweep", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Text("Ramps 0 to 255 so you can feel where the motor kicks in.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(Spacing.Sm))
    PressableFilledTonalButton(
        onClick = { if (isSweeping) viewModel.stopAmplitudeSweep() else viewModel.startAmplitudeSweep() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isSweeping) "Stop Sweep" else "Start Sweep")
    }

    if (viewModel.hapticCapabilities.supportsPredefinedEffects) {
        Spacer(Modifier.height(Spacing.Lg))
        Text("Presets", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.Sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            EffectChip("Click") { viewModel.testPredefinedEffect(VibrationEffect.EFFECT_CLICK) }
            EffectChip("Double Click") { viewModel.testPredefinedEffect(VibrationEffect.EFFECT_DOUBLE_CLICK) }
            EffectChip("Heavy Click") { viewModel.testPredefinedEffect(VibrationEffect.EFFECT_HEAVY_CLICK) }
            EffectChip("Tick") { viewModel.testPredefinedEffect(VibrationEffect.EFFECT_TICK) }
        }
    }

    if (viewModel.hapticCapabilities.supportsComposedPrimitives) {
        Spacer(Modifier.height(Spacing.Lg))
        Text("Primitives", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.Sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            EffectChip("Click") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK) }
            EffectChip("Thud") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD) }
            EffectChip("Spin") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN) }
            EffectChip("Quick Rise") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE) }
            EffectChip("Slow Rise") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE) }
            EffectChip("Quick Fall") { viewModel.testPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL) }
        }
    }

    Spacer(Modifier.height(Spacing.Lg))
    Text("Patterns", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.Sm))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        EffectChip("Heartbeat") {
            viewModel.testWaveform(longArrayOf(0, 100, 100, 200, 400), intArrayOf(0, 255, 0, 180, 0))
        }
        EffectChip("SOS") {
            viewModel.testWaveform(
                longArrayOf(0, 100, 100, 100, 100, 100, 100, 300, 100, 300, 100, 300, 100, 100, 100, 100, 100, 100),
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
            )
        }
        EffectChip("Ramp Up") {
            viewModel.testWaveform(longArrayOf(0, 80, 80, 80, 80, 80), intArrayOf(0, 60, 120, 180, 220, 255))
        }
        EffectChip("Double Tap") {
            viewModel.testWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, 255, 0, 255))
        }
        EffectChip("Triple Tap") {
            viewModel.testWaveform(longArrayOf(0, 50, 70, 50, 70, 50), intArrayOf(0, 255, 0, 255, 0, 255))
        }
        EffectChip("Long Buzz") {
            viewModel.testWaveform(longArrayOf(0, 1500), intArrayOf(0, 200))
        }
    }
}

@Composable
private fun HapticSequencerSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    var bpm by rememberSaveable { mutableFloatStateOf(120f) }
    var beats by rememberSaveable { mutableFloatStateOf(8f) }
    var amplitude by rememberSaveable { mutableFloatStateOf(190f) }
    var accented by rememberSaveable { mutableStateOf(true) }
    val isSequencing by viewModel.isSequencing.collectAsState()

    Text(
        "Build a repeatable test groove for checking timing, intensity, and motor response.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(Spacing.Lg))

    LabeledSlider("Tempo", "${bpm.roundToInt()} BPM", bpm, 40f..220f) { bpm = it }
    LabeledSlider("Beats", "${beats.roundToInt()} hits", beats, 1f..32f) { beats = it }
    LabeledSlider("Amplitude", "${amplitude.roundToInt()} / 255", amplitude, 1f..255f) { amplitude = it }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.Lg)) {
            Text("Accent every 4th beat", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("Useful for feeling drift and missed pulses.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Switch(
            checked = accented,
            onCheckedChange = { accented = it },
            colors = SwitchDefaults.colors(checkedTrackColor = cs.tertiary),
        )
    }

    Spacer(Modifier.height(Spacing.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), modifier = Modifier.fillMaxWidth()) {
        PressableFilledTonalButton(
            onClick = {
                if (isSequencing) {
                    viewModel.stopHapticSequencer()
                } else {
                    viewModel.playHapticSequencer(
                        bpm = bpm.roundToInt(),
                        beats = beats.roundToInt(),
                        amplitude = amplitude.roundToInt(),
                        accented = accented,
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            AnimatedIcon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(if (isSequencing) "Stop Sequence" else "Play Sequence")
        }
        PressableOutlinedButton(onClick = viewModel::cancelAllHaptics, modifier = Modifier.weight(0.72f)) {
            Text("Panic Stop")
        }
    }
}

@Composable
private fun EffectChip(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        interactionSource = interaction,
        modifier = Modifier.pressScale(interaction),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HapticCalibrationSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val calibration by viewModel.calibration.collectAsState()

    Text(
        "Finds the softest vibration you can feel.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(Spacing.Md))

    AnimatedContent(
        targetState = calibration.stage,
        transitionSpec = { fadeThroughTransition(enterDuration = 220, exitDuration = 100) },
        label = "CalibrationStage",
    ) { stage ->
        Column {
            when (stage) {
                CalibrationStage.IDLE -> {
                    calibration.savedThreshold?.let {
                        Text("Saved: $it / 255", color = cs.tertiary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(Spacing.Md))
                    }
                    PressableFilledTonalButton(onClick = viewModel::startCalibration, modifier = Modifier.fillMaxWidth()) {
                        Text(if (calibration.savedThreshold == null) "Start" else "Redo")
                    }
                }
                CalibrationStage.RUNNING -> {
                    Text(
                        "Testing ${calibration.currentAmplitude} / 255 — feel that?",
                        color = cs.onSurface,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Spacing.Md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                        PressableOutlinedButton(onClick = viewModel::calibrationDidntFeelIt, modifier = Modifier.weight(1f)) {
                            Text("Nothing")
                        }
                        PressableFilledTonalButton(onClick = viewModel::calibrationFeltIt, modifier = Modifier.weight(1f)) {
                            Text("Felt it")
                        }
                    }
                }
                CalibrationStage.DONE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuccessCheckmark(size = 22.dp, strokeWidth = 2.5.dp, color = SUPPORTED_COLOR)
                        Spacer(Modifier.width(Spacing.Sm))
                        Text(
                            "Calibrated: ${calibration.savedThreshold} / 255",
                            color = SUPPORTED_COLOR,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(Spacing.Md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                        PressableOutlinedButton(onClick = viewModel::resetCalibration, modifier = Modifier.weight(1f)) {
                            Text("Reset")
                        }
                        PressableFilledTonalButton(onClick = viewModel::startCalibration, modifier = Modifier.weight(1f)) {
                            Text("Redo")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EightDAudioSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val isPlaying by viewModel.eightDPlayer.isPlaying.collectAsState()
    val panPosition by viewModel.eightDPlayer.panPosition.collectAsState()
    var rotationHz by rememberSaveable { mutableFloatStateOf(0.2f) }
    var depth by rememberSaveable { mutableFloatStateOf(1f) }
    var toneHz by rememberSaveable { mutableFloatStateOf(220f) }
    var hapticSync by rememberSaveable { mutableStateOf(false) }

    val animatedPan by animateFloatAsState(
        targetValue = panPosition.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "PanPosition",
    )

    Text(
        "A tone that swings left and right on a loop.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(Spacing.Lg))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.08f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val clampedPan = animatedPan.coerceIn(0.02f, 0.98f)
        Spacer(modifier = Modifier.weight(clampedPan))
        Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(cs.tertiary))
        Spacer(modifier = Modifier.weight(1f - clampedPan))
    }
    Spacer(Modifier.height(Spacing.Xs))
    Text(
        if (isPlaying) "${(panPosition * 100).roundToInt()}% right" else "Stopped",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )

    Spacer(Modifier.height(Spacing.Lg))
    LabeledSlider("Speed", "%.2f Hz".format(rotationHz), rotationHz, 0.05f..1.5f) {
        rotationHz = it
        viewModel.eightDPlayer.rotationHz = it
    }
    LabeledSlider("Depth", "${(depth * 100).roundToInt()}%", depth, 0f..1f) {
        depth = it
        viewModel.eightDPlayer.depth = it
    }
    LabeledSlider("Tone", "${toneHz.roundToInt()} Hz", toneHz, 80f..880f) {
        toneHz = it
        viewModel.eightDPlayer.toneHz = it
    }

    Spacer(Modifier.height(Spacing.Xs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Pulse haptics with it", color = cs.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = hapticSync,
            onCheckedChange = {
                hapticSync = it
                viewModel.eightDPlayer.hapticSyncEnabled = it
            },
            colors = SwitchDefaults.colors(checkedTrackColor = cs.tertiary),
        )
    }

    Spacer(Modifier.height(Spacing.Md))
    PressableFilledTonalButton(
        onClick = {
            if (isPlaying) viewModel.stopEightDAudio() else {
                viewModel.eightDPlayer.rotationHz = rotationHz
                viewModel.eightDPlayer.depth = depth
                viewModel.eightDPlayer.toneHz = toneHz
                viewModel.eightDPlayer.hapticSyncEnabled = hapticSync
                viewModel.startEightDAudio()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isPlaying) "Stop" else "Play")
    }
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(valueText, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = cs.tertiary, activeTrackColor = cs.tertiary),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MotionSensorsSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    SensorBlock("Gyroscope", "rad/s", viewModel.gyroscope, viewModel.hasSensor(Sensor.TYPE_GYROSCOPE))
    Spacer(Modifier.height(Spacing.Lg))
    SensorBlock("Accelerometer", "m/s²", viewModel.accelerometer, viewModel.hasSensor(Sensor.TYPE_ACCELEROMETER))
    Spacer(Modifier.height(Spacing.Lg))
    SensorBlock("Magnetometer", "µT", viewModel.magnetometer, viewModel.hasSensor(Sensor.TYPE_MAGNETIC_FIELD))
    Spacer(Modifier.height(Spacing.Lg))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Light", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            if (viewModel.hasSensor(Sensor.TYPE_LIGHT)) {
                RollingNumber(
                    value = viewModel.lightLux.collectAsState().value,
                    format = { "%.0f lux".format(it) },
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Text("None", color = cs.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
        }
        Column {
            Text("Proximity", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            if (viewModel.hasSensor(Sensor.TYPE_PROXIMITY)) {
                RollingNumber(
                    value = viewModel.proximityCm.collectAsState().value,
                    format = { "%.1f cm".format(it) },
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Text("None", color = cs.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun SensorBlock(label: String, unit: String, flow: StateFlow<DebugToolsViewModel.MotionReading>, available: Boolean) {
    val cs = MaterialTheme.colorScheme
    Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Spacing.Sm))
    if (!available) {
        Text("Not on this device.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    val reading by flow.collectAsState()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AxisValue("X", reading.x)
        AxisValue("Y", reading.y)
        AxisValue("Z", reading.z)
    }
    RollingNumber(
        value = reading.magnitude,
        format = { "Magnitude: %.2f $unit".format(it) },
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = Spacing.Xs),
    )
}

@Composable
private fun AxisValue(label: String, value: Float) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        RollingNumber(
            value = value,
            format = { "%.2f".format(it) },
            color = cs.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun SoundMeterSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val levelDb by viewModel.soundLevelDb.collectAsState()
    val isActive by viewModel.isMeteringSound.collectAsState()
    val hasPermission = viewModel.hasMicPermission()

    if (!hasPermission) {
        Text(
            "Needs mic access. Grant it from the Spotify tab, then come back.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val barFraction by animateFloatAsState(
        targetValue = (levelDb / 100f).coerceIn(0.02f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "SoundBar",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(barFraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(cs.tertiary)
        )
    }
    Spacer(Modifier.height(Spacing.Sm))
    RollingNumber(
        value = levelDb,
        format = { if (isActive) "%.1f dB".format(it) else "Stopped" },
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(Spacing.Md))
    PressableFilledTonalButton(onClick = viewModel::toggleSoundMeter, modifier = Modifier.fillMaxWidth()) {
        AnimatedIcon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(if (isActive) "Stop" else "Start")
    }
}

@Composable
private fun AnalysisDiagnosticsSection() {
    val cs = MaterialTheme.colorScheme
    val analysis by AnalysisDiagnostics.state.collectAsState()
    if (analysis.bass.isEmpty()) {
        Text("Run an Offline conversion or finish a Spotify learning pass to populate the analyzer timeline.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    LayerGraph("RAW / MIX", analysis.raw, cs.onSurfaceVariant)
    LayerGraph("BASS / THUD", analysis.bass, cs.primary)
    LayerGraph("FLOW / SWISH", analysis.vocalFlow, cs.tertiary)
    LayerGraph("BEATS / IMPACT", analysis.percussion, cs.secondary)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        analysis.mergedEvents.forEach { event ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (event) 0.86f else 0.08f)
                    .clip(CircleShape)
                    .background(if (event) cs.error else cs.onSurface.copy(alpha = 0.10f)),
            )
        }
    }
    Spacer(Modifier.height(Spacing.Xs))
    Text("MERGED • ${analysis.eventCount} coalesced physical events", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun LayerGraph(label: String, values: List<Float>, color: Color) {
    val cs = MaterialTheme.colorScheme
    Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = Spacing.Sm))
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        values.forEach { value ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(value.coerceIn(0.04f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.78f)),
            )
        }
    }
}

/**
 * Developer-only Transient Mode diagnostics section (spec section 30):
 * shows the last analyzed track's BPM/beat-confidence estimate, per-band
 * spectral series with kick/snare/hi-hat onset markers, the resulting
 * haptic intensity envelope, live "what just fired" readout during
 * playback, and a synthetic quality-test harness (spec section 31, Tests
 * A-H) that runs the real analysis+rendering pipeline against known
 * signals so a developer can verify each event type feels distinct on the
 * actual device without needing a specific real track.
 */
@Composable
private fun TransientDiagnosticsSection(viewModel: DebugToolsViewModel) {
    val cs = MaterialTheme.colorScheme
    val diag by TransientDiagnostics.state.collectAsState()
    val qualityState by viewModel.qualityTestState.collectAsState()

    // ── Last real analysis summary ──────────────────────────────────────
    if (diag.rms.isEmpty()) {
        Text(
            "Run an Offline conversion in Transient Mode to populate this timeline, or run a synthetic quality test below.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            MetricTile("BPM (est.)", if (diag.bpm > 0f) "%.0f".format(diag.bpm) else "—", modifier = Modifier.weight(1f))
            MetricTile("Confidence", "%.0f%%".format(diag.beatConfidence * 100), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.Md))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            MetricTile("Accents", diag.accentCount.toString(), modifier = Modifier.weight(1f))
            MetricTile("Texture", diag.textureCount.toString(), modifier = Modifier.weight(1f))
            MetricTile("Bed", diag.bedCount.toString(), modifier = Modifier.weight(1f))
            MetricTile("Major", diag.majorAccentCount.toString(), modifier = Modifier.weight(1f))
        }

        LayerGraph("RMS", diag.rms, cs.onSurfaceVariant)
        LayerGraph("BASS", diag.bass, cs.primary)
        LayerGraph("MID", diag.mid, cs.tertiary)
        LayerGraph("HIGH", diag.high, cs.secondary)
        LayerGraph("ONSET SCORE", diag.onsetScore, cs.error)

        Spacer(Modifier.height(Spacing.Sm))
        Text("EVENT MARKERS  •  kick / snare / hi-hat", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        MarkerRow(diag.kickMarkers, cs.primary)
        MarkerRow(diag.snareMarkers, cs.error)
        MarkerRow(diag.hihatMarkers, cs.tertiary)

        LayerGraph("HAPTIC INTENSITY", diag.hapticIntensityEnvelope, SUPPORTED_COLOR)

        Spacer(Modifier.height(Spacing.Sm))
        Text("${diag.eventCount} total scheduled events", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }

    // ── Live "what just fired" readout ──────────────────────────────────
    AnimatedVisibility(
        visible = diag.lastFiredType != null,
        enter = fadeIn(androidx.compose.animation.core.tween(150)),
        exit = fadeOut(androidx.compose.animation.core.tween(150)),
    ) {
        Column(Modifier.padding(top = Spacing.Md)) {
            HorizontalDivider(modifier = Modifier.padding(bottom = Spacing.Md), color = cs.onSurface.copy(alpha = 0.08f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Live: ${diag.lastFiredLayer?.name ?: "—"} / ${diag.lastFiredType?.name ?: "—"}",
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "int %.2f  •  offset %dms  •  %.0f/s".format(diag.lastFiredIntensity, diag.audioHapticOffsetMs, diag.liveEventDensityPerSecond),
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    // ── Synthetic quality-test harness (spec section 31) ────────────────
    Spacer(Modifier.height(Spacing.Lg))
    HorizontalDivider(modifier = Modifier.padding(bottom = Spacing.Md), color = cs.onSurface.copy(alpha = 0.08f))
    Text("Quality Tests", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    Text(
        "Generates a known synthetic signal and plays the real analysis result live through the haptics motor.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(Spacing.Sm))

    val activeTest = when (val s = qualityState) {
        is DebugToolsViewModel.QualityTestState.Analyzing -> s.test
        is DebugToolsViewModel.QualityTestState.Playing -> s.test
        is DebugToolsViewModel.QualityTestState.Failed -> s.test
        DebugToolsViewModel.QualityTestState.Idle -> null
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        TransientQualityTest.entries.forEach { test ->
            val isActive = test == activeTest
            OutlinedButton(
                onClick = { if (isActive) viewModel.stopQualityTest() else viewModel.runQualityTest(test) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isActive) cs.tertiaryContainer else Color.Transparent,
                    contentColor = if (isActive) cs.onTertiaryContainer else cs.onSurfaceVariant,
                ),
            ) {
                Text(test.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    Spacer(Modifier.height(Spacing.Sm))
    when (val s = qualityState) {
        is DebugToolsViewModel.QualityTestState.Analyzing -> {
            Text("Analyzing ${s.test.label}...", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        is DebugToolsViewModel.QualityTestState.Playing -> {
            Text(
                "Playing ${s.test.label}  •  ${s.eventCount} events  •  expect: ${s.test.expectation}",
                color = cs.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is DebugToolsViewModel.QualityTestState.Failed -> {
            Text("${s.test.label} produced no events — check onset thresholds.", color = cs.error, style = MaterialTheme.typography.bodySmall)
        }
        DebugToolsViewModel.QualityTestState.Idle -> Unit
    }
}

@Composable
private fun MarkerRow(markers: List<Boolean>, color: Color) {
    if (markers.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(10.dp).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        markers.forEach { hit ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (hit) 1f else 0.15f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (hit) color else color.copy(alpha = 0.12f)),
            )
        }
    }
}

@Composable
private fun CaptureDiagnosticsSection() {
    val cs = MaterialTheme.colorScheme
    val diagnostics by CaptureDiagnostics.state.collectAsState()
    val rows = listOf(
        "Stage" to diagnostics.stage,
        "Projection" to if (diagnostics.projectionActive) "active" else if (diagnostics.projectionGranted) "granted / inactive" else "not granted",
        "AudioRecord" to if (diagnostics.audioRecordState == 0) "not initialized" else diagnostics.audioRecordState.toString(),
        "Format" to if (diagnostics.sampleRate > 0) "${diagnostics.sampleRate} Hz • ${diagnostics.channelCount} channel(s)" else "—",
        "Buffer" to if (diagnostics.bufferBytes > 0) "${diagnostics.bufferBytes} bytes" else "—",
        "PCM written" to "${diagnostics.bytesWritten} bytes",
        "PCM validation" to if (diagnostics.pcmValid) "valid • ${diagnostics.pcmDurationMs}ms" else "not valid",
        "RMS / peak" to "%.5f / %.5f".format(diagnostics.pcmRms, diagnostics.pcmPeak),
        "Clipped samples" to diagnostics.clippedSamples.toString(),
        "Signal" to if (diagnostics.hadSignal) "detected" else "not detected",
    )
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, color = cs.onSurface, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    diagnostics.lastError?.takeIf { it.isNotBlank() }?.let { error ->
        Spacer(Modifier.height(Spacing.Sm))
        Surface(shape = MaterialTheme.shapes.medium, color = cs.errorContainer.copy(alpha = 0.45f)) {
            Text(error, color = cs.onErrorContainer, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(Spacing.Md))
        }
    }
    Spacer(Modifier.height(Spacing.Sm))
    Text(
        "These values are intentionally kept out of the normal Spotify error copy. They identify whether consent, MediaProjection, AudioRecord initialization, PCM delivery, or encoding is the failing stage.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun StorageSection(viewModel: DebugToolsViewModel, onCopyReport: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        "Clears saved Spotify vibration patterns. They'll be rebuilt next time you play each track.",
        color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(Spacing.Md))
    PressableOutlinedButton(onClick = viewModel::clearSpotifyHapticCache, modifier = Modifier.fillMaxWidth()) {
        AnimatedIcon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text("Clear Spotify Cache")
    }
    Spacer(Modifier.height(Spacing.Md))
    PressableFilledTonalButton(onClick = onCopyReport, modifier = Modifier.fillMaxWidth()) {
        AnimatedIcon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text("Copy Debug Report")
    }
}

private val SUPPORTED_COLOR = Color(0xFF4CAF50)
