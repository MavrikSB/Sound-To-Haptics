package com.sound.to.haptics.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sound.to.haptics.ConversionStage
import com.sound.to.haptics.MainViewModel
import com.sound.to.haptics.UiState
import com.sound.to.haptics.library.HapticSong
import com.sound.to.haptics.settings.PlaybackActivity
import com.sound.to.haptics.ui.components.AnimatedIcon
import com.sound.to.haptics.ui.components.RollingNumber
import com.sound.to.haptics.ui.components.StaggeredEntrance
import com.sound.to.haptics.ui.motion.EmphasizedAccelerate
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.motion.containerTransformTransition
import com.sound.to.haptics.ui.motion.fadeThroughTransition
import com.sound.to.haptics.ui.motion.scaleDurationMs
import com.sound.to.haptics.ui.motion.verticalSettleTransition
import com.sound.to.haptics.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticStudioScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onFileSelected(it) } }

    // Reports this screen's playing state to the shared, app-wide signal so
    // MainActivity can honor Settings → "Keep screen on while playing"
    // without HapticStudioScreen needing to know anything about window
    // flags itself. Cleared on leaving the screen so a forgotten "playing"
    // state can't keep the display awake after navigating away.
    DisposableEffect(uiState.playerState.isPlaying) {
        PlaybackActivity.setPlaying(uiState.playerState.isPlaying)
        onDispose { PlaybackActivity.setPlaying(false) }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column(modifier = Modifier.padding(start = Spacing.Xs)) {
                        Text(
                            text = "Sound To Haptics",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Turn any audio into haptic feedback",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    val showBack = uiState.conversionStage == ConversionStage.SUCCESS ||
                        uiState.conversionStage == ConversionStage.ERROR
                    if (showBack) {
                        BouncyIconButton(onClick = { viewModel.resetToIdle() }) {
                            AnimatedIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = cs.onSurface)
                        }
                    }
                },
                actions = {
                    BouncyIconButton(onClick = { showSheet = true }) {
                        AnimatedIcon(Icons.Rounded.Tune, "Settings", tint = cs.onSurface)
                    }
                    BouncyIconButton(onClick = {
                        scope.launch { snackbarHostState.showSnackbar("Made By RandomBlenderGuy", duration = SnackbarDuration.Short) }
                    }) {
                        AnimatedIcon(Icons.Rounded.Info, "About", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = cs.surfaceContainer,
                    titleContentColor = cs.onSurface
                ),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data, shape = MaterialTheme.shapes.large)
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = uiState.conversionStage,
                    transitionSpec = {
                        if (targetState == ConversionStage.IDLE || initialState == ConversionStage.IDLE) {
                            fadeThroughTransition()
                        } else {
                            containerTransformTransition()
                        }
                    },
                    label = "Screen"
                ) { stage ->
                    when (stage) {
                        ConversionStage.IDLE -> IdleContent(
                            songs = librarySongs,
                            onPlaySong = viewModel::playLibrarySong,
                            onDeleteSong = viewModel::deleteLibrarySong,
                            playingSongTitle = uiState.playerState.fileName.takeIf { uiState.playerState.isPlaying },
                            onUpload = { pickAudioLauncher.launch("audio/*") },
                            onDemo = { viewModel.playDemo() },
                            btConnected = uiState.bluetoothDeviceConnected,
                        )
                        ConversionStage.SUCCESS -> PlayerContent(uiState, viewModel)
                        ConversionStage.ERROR -> ErrorContent(
                            message = uiState.errorMessage,
                            onRetry = { viewModel.resetToIdle() },
                        )
                        else -> LoadingContent(
                            stage = stage,
                            progress = uiState.progress,
                            sourceName = uiState.selectedSourceName,
                            onCancel = viewModel::cancelConversion,
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = cs.surfaceContainerHigh,
            dragHandle = { BottomSheetDefaults.DragHandle(color = cs.onSurface.copy(alpha = 0.25f)) }
        ) { SettingsSheet(uiState, viewModel) }
    }
}

// ─── Idle ───────────────────────────────────────────────────────────────────

@Composable
fun IdleContent(
    songs: List<HapticSong>,
    onPlaySong: (HapticSong) -> Unit,
    onDeleteSong: (HapticSong) -> Unit,
    playingSongTitle: String?,
    onUpload: () -> Unit,
    onDemo: () -> Unit,
    btConnected: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    // Structural change from the old design, not just a decoration change:
    // no card behind the headline at all (it sits directly on the
    // background), and the page is asymmetric left-to-right instead of
    // every element being a centered stack of same-shaped rounded boxes.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(Spacing.Xl))
        StaggeredEntrance(0, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Convert",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = cs.onSurface,
                        lineHeight = MaterialTheme.typography.displayMedium.lineHeight,
                    )
                    Text(
                        "your audio",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = cs.primary,
                        lineHeight = MaterialTheme.typography.displayMedium.lineHeight,
                    )
                }
                // A single equalizer bar cluster living off to the side,
                // not centered behind the text — an asymmetric accent
                // rather than a background texture inside a box.
                MiniEqualizerCluster(modifier = Modifier.padding(top = 10.dp))
            }
        }
        Spacer(Modifier.height(Spacing.Sm))
        StaggeredEntrance(1, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Pick a file below, or try the built-in demo track",
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl),
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.Xxl))

        AnimatedVisibility(
            visible = btConnected,
            enter = fadeIn(tween(300)) + expandVertically(animationSpec = tween(300, easing = EmphasizedDecelerate)),
            exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = tween(200, easing = EmphasizedAccelerate)),
        ) {
            Column(Modifier.padding(horizontal = Spacing.Xl)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = cs.tertiaryContainer,
                    tonalElevation = 1.dp,
                ) {
                    Row(Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedIcon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(22.dp), tint = cs.onTertiaryContainer)
                        Spacer(Modifier.width(Spacing.Md))
                        Text(
                            "Bluetooth connected — audio to headphones, haptics on phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
            }
        }

        // Big rounded-square tiles, inset from the screen edges — not a
        // small centered card like before, but not sharp full-bleed
        // rectangles either (those read as a rendering bug, not a design
        // choice, once actually on screen). 28dp corners is Material You's
        // "large" rounding: enough to feel soft and tappable at this size.
        StaggeredEntrance(2, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl)) {
            CardButton(
                onClick = onUpload,
                modifier = Modifier.fillMaxWidth(),
                containerColor = cs.primary,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl, vertical = Spacing.Xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedIcon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(34.dp), tint = cs.onPrimary)
                    Spacer(Modifier.width(Spacing.Lg))
                    Column(Modifier.weight(1f)) {
                        Text("Select Audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                        Text("MP3 · WAV · OGG · FLAC", style = MaterialTheme.typography.labelMedium, color = cs.onPrimary.copy(alpha = 0.72f))
                    }
                    AnimatedIcon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(26.dp), tint = cs.onPrimary.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(Modifier.height(Spacing.Md))

        // The secondary action is a smaller-radius, flatter tile — different
        // shape weight (not just a lighter color) is what signals
        // "secondary" next to the primary tile above.
        StaggeredEntrance(3, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl)) {
            CardButton(
                onClick = onDemo,
                modifier = Modifier.fillMaxWidth(),
                containerColor = cs.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl, vertical = Spacing.Lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedIcon(Icons.Rounded.PlayCircle, contentDescription = null, modifier = Modifier.size(22.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(Spacing.Md))
                    Text(
                        "Try the demo track instead",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedIcon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(Spacing.Xxl))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Saved haptic songs", style = MaterialTheme.typography.titleLarge, color = cs.onSurface, fontWeight = FontWeight.Bold)
                Text(if (songs.isEmpty()) "Your haptic library is empty" else "${songs.size} song${if (songs.size == 1) "" else "s"} ready to feel", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (songs.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(50), color = cs.primaryContainer) {
                    Text("Persistent", modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm), style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(Spacing.Md))
        if (songs.isEmpty()) {
            EmptyLibraryCard(onConvert = onUpload)
        } else {
            songs.forEachIndexed { index, song ->
                StaggeredEntrance(index + 4, modifier = Modifier.padding(horizontal = Spacing.Xl)) {
                    HapticSongRow(
                        song = song,
                        isPlaying = playingSongTitle == song.title,
                        onPlay = { onPlaySong(song) },
                        onDelete = { onDeleteSong(song) },
                    )
                }
                if (index < songs.lastIndex) Spacer(Modifier.height(Spacing.Sm))
            }
        }
        Spacer(Modifier.height(Spacing.Xxl * 2))
    }
}

/**
 * A small cluster of animated bars — the asymmetric accent that replaces
 * the old full-width WaveformAnim-behind-a-headline treatment. Deliberately
 * compact and off to one side rather than spanning the screen as a
 * background texture.
 */
@Composable
private fun MiniEqualizerCluster(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { index ->
            val transition = rememberInfiniteTransition(label = "IdleEqBar$index")
            val heightFraction by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(scaleDurationMs(620 + index * 110), easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "IdleEqBarHeight$index",
            )
            val tint = when (index % 3) {
                0 -> cs.primary
                1 -> cs.tertiary
                else -> cs.secondary
            }
            Box(
                Modifier
                    .width(5.dp)
                    .height(32.dp * heightFraction)
                    .clip(RoundedCornerShape(50))
                    .background(tint.copy(alpha = 0.7f)),
            )
        }
    }
}

// ─── Player ─────────────────────────────────────────────────────────────────

@Composable
fun PlayerContent(uiState: UiState, viewModel: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.Xxl)
                .navigationBarsPadding()
                .padding(bottom = Spacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.Lg))
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val size = minOf(maxWidth * 0.72f, 260.dp)
                    // Ambient halo behind the disc, matching the startup
                    // screen's glow language — brightens while playing so
                    // the whole hero area feels "lit up" during playback
                    // instead of only the disc itself moving.
                    val haloAlpha by animateFloatAsState(
                        targetValue = if (uiState.playerState.isPlaying) 0.85f else 0.45f,
                        animationSpec = tween(600, easing = EmphasizedDecelerate),
                        label = "PlayerHaloAlpha",
                    )
                    Box(
                        Modifier
                            .size(size * 1.35f)
                            .graphicsLayer { alpha = haloAlpha }
                            .background(
                                Brush.radialGradient(
                                    listOf(cs.primary.copy(alpha = 0.16f), Color.Transparent),
                                ),
                                CircleShape,
                            ),
                    )
                    SpinningArt(discSize = size, isPlaying = uiState.playerState.isPlaying)
                }
                Spacer(Modifier.height(Spacing.Xl))
                Text(
                    uiState.playerState.fileName ?: "Track",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.Xl),
                )
                Spacer(Modifier.height(Spacing.Xs))
                Text(uiState.playerState.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(
                    visible = uiState.bluetoothDeviceConnected,
                    enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                    exit = fadeOut(tween(180)) + shrinkVertically(animationSpec = tween(180)),
                ) {
                    Column {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.tertiaryContainer, tonalElevation = 1.dp) {
                            Row(Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                                AnimatedIcon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.onTertiaryContainer)
                                Spacer(Modifier.width(Spacing.Sm))
                                Text("Split mode: music on Bluetooth, haptics on phone", style = MaterialTheme.typography.labelMedium, color = cs.onTertiaryContainer)
                            }
                        }
                        Spacer(Modifier.height(Spacing.Sm))
                    }
                }

                Slider(
                    value = uiState.playerState.currentPosition.toFloat(),
                    onValueChange = { viewModel.seekTo(it.toInt()) },
                    valueRange = 0f..uiState.playerState.duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary, inactiveTrackColor = cs.onSurface.copy(alpha = 0.12f))
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs), horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(shape = RoundedCornerShape(50), color = cs.surfaceContainerHigh) {
                        RollingNumber(
                            value = uiState.playerState.currentPosition.toFloat(),
                            format = { fmtMs(it.toInt()) },
                            modifier = Modifier.padding(horizontal = Spacing.Sm, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Text(
                        fmtMs(uiState.playerState.duration),
                        modifier = Modifier.padding(vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.Lg))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val playSz = minOf(maxWidth * 0.26f, 104.dp)
                    val sideSz = minOf(maxWidth * 0.17f, 64.dp)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = cs.surfaceContainer.copy(alpha = 0.6f),
                        tonalElevation = 1.dp,
                    ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.Md),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundIconBtn(onClick = { viewModel.skipBackward() }, size = sideSz, bg = cs.surfaceContainerHigh, fg = cs.onSurface) {
                            AnimatedIcon(Icons.Rounded.Replay10, "Back 10s", modifier = Modifier.size(sideSz * 0.42f))
                        }
                        // Breathing halo around the play button while playing —
                        // a quiet, alive pulse that reads as "in motion".
                        Box(contentAlignment = Alignment.Center) {
                            if (uiState.playerState.isPlaying) {
                                val pulse = rememberInfiniteTransition(label = "PlayPulse")
                                val ringScale by pulse.animateFloat(
                                    initialValue = 0.94f,
                                    targetValue = 1.16f,
                                    animationSpec = infiniteRepeatable(tween(scaleDurationMs(1500), easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                    label = "PlayPulseScale",
                                )
                                val ringAlpha by pulse.animateFloat(
                                    initialValue = 0.28f,
                                    targetValue = 0f,
                                    animationSpec = infiniteRepeatable(tween(scaleDurationMs(1500), easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                    label = "PlayPulseAlpha",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(playSz * ringScale)
                                        .clip(CircleShape)
                                        .background(cs.primary.copy(alpha = ringAlpha)),
                                )
                            }
                            RoundIconBtn(onClick = { viewModel.togglePlayPause() }, size = playSz, bg = cs.primary, fg = cs.onPrimary) {
                                AnimatedIcon(
                                    if (uiState.playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (uiState.playerState.isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(playSz * 0.5f),
                                )
                            }
                        }
                        RoundIconBtn(onClick = { viewModel.skipForward() }, size = sideSz, bg = cs.surfaceContainerHigh, fg = cs.onSurface) {
                            AnimatedIcon(Icons.Rounded.Forward10, "Fwd 10s", modifier = Modifier.size(sideSz * 0.42f))
                        }
                    }
                    }
                }
                Spacer(Modifier.height(Spacing.Xl))
            }
        }
    }
}

@Composable
private fun SpinningArt(discSize: Dp, isPlaying: Boolean) {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "Disc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(scaleDurationMs(24_000), easing = LinearEasing)),
        label = "DiscAngle",
    )
    val spin by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(350, easing = EmphasizedDecelerate),
        label = "DiscSpin",
    )
    Surface(
        modifier = Modifier.size(discSize),
        shape = CircleShape,
        color = cs.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Record-like surface: a faint label ring plus a soft arc sheen
            // that slowly sweeps around while playing — the disc visibly
            // spins without ever tilting the artwork.
            // Note: the parameter is named `discSize` (not `size`) so that
            // `size` inside the Canvas lambda resolves to DrawScope.size.
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = angle * spin }) {
                val r = minOf(size.width, size.height) / 2f
                drawCircle(
                    color = cs.primary.copy(alpha = 0.08f),
                    radius = r * 0.36f,
                    center = center,
                )
                drawArc(
                    color = cs.onSurface.copy(alpha = 0.05f),
                    startAngle = -32f,
                    sweepAngle = 54f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.76f, size.height * 0.76f),
                    style = Stroke(width = size.width * 0.07f),
                )
            }
            // The equalizer stays upright and dances — never tilted by the
            // disc rotation.
            EqualizerAnim(modifier = Modifier.fillMaxSize(0.52f), isPlaying = isPlaying)
        }
    }
}

// ─── Loading ────────────────────────────────────────────────────────────────

private data class StageInfo(val stage: ConversionStage, val label: String)

private val stageSequence = listOf(
    StageInfo(ConversionStage.COPYING, "Preparing audio"),
    StageInfo(ConversionStage.ANALYZING, "Analyzing audio"),
    StageInfo(ConversionStage.EXTRACTING, "Merging audio tracks"),
    StageInfo(ConversionStage.BUILDING, "Generating haptic track"),
    StageInfo(ConversionStage.OPTIMIZING, "Finishing"),
)

@Composable
fun LoadingContent(
    stage: ConversionStage,
    progress: Float,
    sourceName: String? = null,
    onCancel: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(450, easing = EmphasizedDecelerate),
        label = "LoadingProgress",
    )
    AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.Xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(200.dp)
                        .background(
                            Brush.radialGradient(listOf(cs.primary.copy(alpha = 0.10f), Color.Transparent)),
                            CircleShape,
                        ),
                )
                CircularProgressIndicator(
                    progress = { p },
                    modifier = Modifier.size(124.dp),
                    color = cs.primary,
                    strokeWidth = 6.dp,
                    trackColor = cs.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                RollingNumber(
                    value = p * 100f,
                    format = { "${it.toInt()}%" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.height(Spacing.Xxl))

            AnimatedContent(
                targetState = stage,
                transitionSpec = { verticalSettleTransition() },
                label = "StageHeadline",
            ) { currentStage ->
                Text(
                    stageSequence.firstOrNull { it.stage == currentStage }?.label ?: "Processing",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.Xs))
            sourceName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Preparing ${it.substringBeforeLast('.', it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(Spacing.Xl))

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = cs.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(Spacing.Xl)) {
                    stageSequence.forEachIndexed { index, info ->
                        StaggeredEntrance(index, modifier = Modifier.fillMaxWidth()) {
                            StageRow(info, currentStage = stage)
                        }
                        if (index < stageSequence.lastIndex) Spacer(Modifier.height(Spacing.Md))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Lg))
            PressableOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(0.72f),
            ) {
                AnimatedIcon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.Xs))
                Text("Cancel conversion", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StageRow(info: StageInfo, currentStage: ConversionStage) {
    val cs = MaterialTheme.colorScheme
    val done = currentStage.ordinal > info.stage.ordinal
    val active = currentStage == info.stage
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (done) {
                AnimatedIcon(
                    Icons.Rounded.Check,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = cs.primary,
                )
            } else {
                AnimatedIcon(
                    Icons.Rounded.Circle,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
            // AnimatedContent (not AnimatedVisibility) on purpose: inside a
            // Row/Box scope, AnimatedVisibility's RowScope overload creates an
            // implicit-receiver ambiguity that fails to compile.
            AnimatedContent(
                targetState = active,
                transitionSpec = {
                    (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(tween(100)))
                },
                label = "StageActive",
            ) { isActive ->
                if (isActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = cs.primary,
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.Md))
        Text(
            info.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active || done) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ─── Error ──────────────────────────────────────────────────────────────────

@Composable
fun ErrorContent(message: String? = null, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val iconScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ErrorIconPop",
    )
    Column(
        Modifier.fillMaxSize().padding(Spacing.Xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(150.dp)
                    .scale(iconScale)
                    .background(
                        Brush.radialGradient(listOf(cs.error.copy(alpha = 0.12f), Color.Transparent)),
                        CircleShape,
                    ),
            )
            Surface(
                modifier = Modifier.size(96.dp).scale(iconScale),
                shape = CircleShape,
                color = cs.errorContainer,
                tonalElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedIcon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = cs.onErrorContainer)
                }
            }
        }
        Spacer(Modifier.height(Spacing.Xl))
        Text("Conversion Failed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            message?.takeIf { it.isNotBlank() }
                ?: "The file couldn't be processed. Try a different format.",
            modifier = Modifier.padding(horizontal = Spacing.Xl),
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(Spacing.Xxl))
        CardButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(60.dp), containerColor = cs.primary) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Try Again", fontWeight = FontWeight.SemiBold, color = cs.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ─── Settings ───────────────────────────────────────────────────────────────

@Composable
fun SettingsSheet(uiState: UiState, viewModel: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Xl, vertical = Spacing.Sm)
            .padding(bottom = Spacing.Lg)
    ) {
        Text("Haptic Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(Spacing.Lg))

        StaggeredEntrance(0) {
            SettingRow(label = "Intensity", desc = "Haptic channel strength", value = uiState.sensitivity, onChange = viewModel::updateSensitivity, icon = Icons.Rounded.Vibration)
        }
        Spacer(Modifier.height(Spacing.Md))
        StaggeredEntrance(1) {
            SettingRow(label = "Bass Focus", desc = "Only deep bass triggers the motor", value = uiState.bassBoost, onChange = viewModel::updateBassBoost, icon = Icons.Rounded.GraphicEq)
        }
        Spacer(Modifier.height(Spacing.Md))
        StaggeredEntrance(2) {
            SettingRow(label = "Density", desc = "How much source drives the haptic track", value = uiState.density, onChange = viewModel::updateDensity, icon = Icons.Rounded.Waves)
        }
        Spacer(Modifier.height(Spacing.Md))
        StaggeredEntrance(3) {
            val switchScale by animateFloatAsState(
                targetValue = if (uiState.hapticPreviewEnabled) 1f else 0.94f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "PreviewSwitchBounce",
            )
            Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(Spacing.Lg), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = Spacing.Lg)) {
                        Text("Preview Haptics", color = cs.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                        Text("Vibrate during playback", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    Switch(
                        checked = uiState.hapticPreviewEnabled,
                        onCheckedChange = viewModel::toggleHapticPreview,
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
        }
    }
}

// ─── Reusable ───────────────────────────────────────────────────────────────

@Composable
fun CardButton(onClick: () -> Unit, modifier: Modifier = Modifier, containerColor: Color, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val ambientInteraction = LocalAmbientInteraction.current
    val musicPlaying by PlaybackActivity.isPlaying.collectAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = PressSpring,
        label = "CardButtonScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) 0.dp else 4.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "CardButtonElevation",
    )
    Surface(
        onClick = {
            if (!musicPlaying) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ambientInteraction()
            onClick()
        },
        modifier = modifier.scale(scale),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        tonalElevation = elevation,
        interactionSource = interaction,
    ) {
        content()
    }
}

@Composable
fun RoundIconBtn(onClick: () -> Unit, size: Dp, bg: Color, fg: Color, content: @Composable () -> Unit) {
    val int = remember { MutableInteractionSource() }
    val ambientInteraction = LocalAmbientInteraction.current
    val pressed by int.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = PressSpring,
        label = "RoundIconScale",
    )
    // A small resting shadow that flattens on press — the same "lift while
    // idle, settle while touched" language as PressableCard, so the round
    // transport buttons read as part of the same design system rather than
    // a flatter one-off control.
    val elevation by animateDpAsState(
        targetValue = if (pressed) 1.dp else 5.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "RoundIconElevation",
    )
    FilledIconButton(
        onClick = {
            // Deliberately NOT calling performHapticFeedback here: this button
            // lives in the transport row (skip/play/pause) which is active
            // exactly when the haptic-coupled OGG channel is driving the
            // vibrator. A system "click" vibration steals the motor from
            // that continuous channel and it doesn't reclaim it afterwards,
            // which read as "haptics stop when I tap skip". The bouncy scale
            // + ambient glow below already give plenty of tactile/visual
            // confirmation without touching the vibrator.
            ambientInteraction()
            onClick()
        },
        modifier = Modifier.size(size).scale(scale).shadow(elevation, CircleShape, clip = false),
        shape = CircleShape,
        interactionSource = int,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = fg),
    ) {
        content()
    }
}

@Composable
fun BouncyIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val ambientInteraction = LocalAmbientInteraction.current
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "BouncyIconButtonScale",
    )
    Surface(
        onClick = {
            // Same reasoning as RoundIconBtn above — this button backs the
            // top-bar Settings/Info actions, which the person taps *while*
            // a track (and its haptics) is playing.
            ambientInteraction()
            onClick()
        },
        modifier = Modifier.size(48.dp).scale(scale),
        shape = CircleShape,
        color = Color.Transparent,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
fun SettingRow(
    label: String,
    desc: String,
    value: Float,
    onChange: (Float) -> Unit,
    icon: ImageVector = Icons.Rounded.Tune,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.Lg, top = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    AnimatedIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.Md))
                    RollingNumber(
                        value = value * 100f,
                        format = { "${it.toInt()}%" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(desc, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(Spacing.Sm))
                Slider(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = cs.primary,
                        activeTrackColor = cs.primary,
                        inactiveTrackColor = cs.onSurface.copy(alpha = 0.12f),
                    )
                )
            }
        }
    }
}

@Composable
fun WaveformAnim(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "Waveform")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(scaleDurationMs(3000), easing = LinearEasing)),
        label = "WaveformPhase",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2
        val pts = 50
        val step = w / pts
        for (i in 0 until pts) {
            val x = i * step
            val lh = 60.dp.toPx() * (0.3f + 0.7f * kotlin.math.abs(sin((i.toDouble() / pts * 6 * Math.PI) + phase.toDouble()).toFloat()))
            drawLine(cs.onSurface.copy(alpha = 0.12f), Offset(x, cy - lh / 2), Offset(x, cy + lh / 2), 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun EqualizerAnim(modifier: Modifier, isPlaying: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            val t = rememberInfiniteTransition(label = "EQ$i")
            // While playing the bars dance between 20% and full height;
            // while paused they breathe gently at a clearly visible height
            // (no more tiny idle dots).
            val h by t.animateFloat(
                initialValue = 0.2f,
                targetValue = if (isPlaying) 1f else 0.34f,
                animationSpec = infiniteRepeatable(
                    tween(scaleDurationMs(if (isPlaying) 380 + i * 140 else 1000 + i * 180), easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "Bar$i",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary)
            )
        }
    }
}

private fun fmtMs(ms: Int): String {
    val sec = ms / 1000
    return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
}
