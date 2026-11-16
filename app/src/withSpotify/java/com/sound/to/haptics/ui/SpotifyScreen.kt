package com.sound.to.haptics.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sound.to.haptics.spotify.BrowseStatus
import com.sound.to.haptics.spotify.HapticMode
import com.sound.to.haptics.spotify.SpotifyBrowseState
import com.sound.to.haptics.spotify.SpotifyTrack
import com.sound.to.haptics.spotify.SpotifyTrackItem
import com.sound.to.haptics.spotify.SpotifyPlaylistItem
import com.sound.to.haptics.spotify.SpotifyViewModel
import com.sound.to.haptics.spotify.TrackHapticUiState
import com.sound.to.haptics.ui.components.AnimatedIcon
import com.sound.to.haptics.ui.components.RollingNumber
import com.sound.to.haptics.ui.components.SkeletonRow
import com.sound.to.haptics.ui.components.StaggeredEntrance
import com.sound.to.haptics.ui.icons.SpotifyLogo
import com.sound.to.haptics.ui.LocalAmbientInteraction
import com.sound.to.haptics.ui.motion.EmphasizedAccelerate
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.motion.fadeThroughTransition
import com.sound.to.haptics.ui.theme.Spacing
import com.sound.to.haptics.ui.theme.SpotifyGreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SpotifyScreen(viewModel: SpotifyViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val cs = MaterialTheme.colorScheme
    val selectedPlaylist = uiState.browse.selectedPlaylist
    val ambientInteraction = LocalAmbientInteraction.current

    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }

    val isActivelyPlaying = uiState.connection.isConnected && !uiState.connection.track.isPaused
    androidx.compose.runtime.DisposableEffect(isActivelyPlaying) {
        com.sound.to.haptics.settings.PlaybackActivity.setPlaying(isActivelyPlaying)
        onDispose { com.sound.to.haptics.settings.PlaybackActivity.setPlaying(false) }
    }

    val captureConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onCaptureConsentResult(result.resultCode, result.data) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.createCaptureConsentIntent()?.let { captureConsentLauncher.launch(it) }
        } else {
            viewModel.onCapturePermissionDenied()
        }
    }

    val requestCaptureAccess: () -> Unit = {
        if (viewModel.hasRecordAudioPermission()) {
            viewModel.createCaptureConsentIntent()?.let { captureConsentLauncher.launch(it) }
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = Spacing.Xl)
                    .padding(top = Spacing.Lg, bottom = Spacing.Xl),
            ) {
                StaggeredEntrance(0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                SpotifyLogo(modifier = Modifier.size(24.dp), tint = SpotifyGreen, waveTint = Color.White)
                            }
                        }
                        Spacer(Modifier.width(Spacing.Md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Spotify Mode",
                                color = cs.onSurface,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                "Haptics in your favourite songs on Spotify.",
                                color = cs.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Surface(shape = RoundedCornerShape(50), color = if (uiState.connection.isConnected) SpotifyGreen.copy(alpha = 0.16f) else cs.surfaceContainerHigh) {
                            Text(
                                if (uiState.connection.isConnected) "Connected" else if (uiState.connection.isConnecting) "Connecting" else "Offline",
                                modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.connection.isConnected) SpotifyGreen else cs.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.Xl))

                AnimatedVisibility(
                    visible = !uiState.connection.isConnected,
                    enter = fadeIn(tween(300)) + expandVertically(animationSpec = tween(300, easing = EmphasizedDecelerate)),
                    exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = tween(200, easing = EmphasizedAccelerate)),
                ) {
                    ConnectCard(
                        isConnecting = uiState.connection.isConnecting,
                        error = uiState.connection.error?.let { "Spotify is unavailable right now. Open Spotify, then try again." },
                        onConnect = { activity?.let { viewModel.beginLogin(it) } ?: viewModel.connect() },
                    )
                }

                AnimatedVisibility(
                    visible = uiState.connection.isConnected,
                    enter = fadeIn(tween(400, delayMillis = 100)) + expandVertically(animationSpec = tween(350, easing = EmphasizedDecelerate)),
                    exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = tween(200)),
                ) {
                    Column {
                        NowPlayingCard(viewModel, uiState.connection.track)
                        Spacer(Modifier.height(Spacing.Xl))
                        TrackHapticsPanel(
                            haptics = uiState.haptics,
                            captureLevel = viewModel.captureLevel,
                            onGrantAccess = requestCaptureAccess,
                            onRecapture = viewModel::recaptureCurrentTrack,
                            onStopLearning = viewModel::stopLearning,
                            onSensitivity = viewModel::updateSensitivity,
                        )
                        Spacer(Modifier.height(Spacing.Xxl))
                        if (selectedPlaylist == null) {
                            SearchBar(
                                query = uiState.browse.query,
                                onQueryChange = viewModel::updateQuery,
                                onSearch = viewModel::search,
                            )
                            Spacer(Modifier.height(Spacing.Lg))
                            if (uiState.browse.query.isBlank() && uiState.browse.playlists.isNotEmpty()) {
                                PlaylistStrip(
                                    playlists = uiState.browse.playlists,
                                    onPlaylistClick = viewModel::openPlaylist,
                                )
                                Spacer(Modifier.height(Spacing.Lg))
                            }
                            TrackList(
                                browse = uiState.browse,
                                onTrackClick = {
                                    ambientInteraction()
                                    viewModel.playTrack(it)
                                },
                            )
                        } else {
                            PlaylistDetail(
                                playlist = selectedPlaylist,
                                tracks = uiState.browse.playlistTracks,
                                loading = uiState.browse.playlistLoading,
                                onBack = viewModel::closePlaylist,
                                onTrackClick = {
                                    ambientInteraction()
                                    viewModel.playTrack(it)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.Lg))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = cs.surfaceContainerHigh,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "The first time you play a track, the app captures its own audio output directly — not the microphone — while it plays. Once the track finishes, that capture is converted into a haptic-enabled file and saved on your device. From then on, haptics play back in sync automatically every time you listen to that song.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.Lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val ambientInteraction = LocalAmbientInteraction.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search songs or artists") },
        leadingIcon = { AnimatedIcon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)),
                exit = fadeOut(tween(100)),
            ) {
                IconButton(onClick = { ambientInteraction(); onQueryChange(""); onSearch() }) {
                    AnimatedIcon(Icons.Rounded.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SpotifyGreen,
            cursorColor = SpotifyGreen,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun PlaylistStrip(
    playlists: List<SpotifyPlaylistItem>,
    onPlaylistClick: (SpotifyPlaylistItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Your playlists", style = MaterialTheme.typography.titleLarge, color = cs.onSurface, fontWeight = FontWeight.Bold)
                Text("Jump back into a familiar mood", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(Spacing.Md))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            items(playlists, key = { it.id }) { playlist ->
                val interaction = remember(playlist.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.96f else 1f, PressSpring, label = "PlaylistCardScale")
                Surface(
                    onClick = { onPlaylistClick(playlist) },
                    modifier = Modifier.width(156.dp).scale(scale),
                    shape = RoundedCornerShape(22.dp),
                    color = cs.surfaceContainer,
                    tonalElevation = 2.dp,
                    interactionSource = interaction,
                ) {
                    Column(Modifier.padding(Spacing.Md)) {
                        if (playlist.imageUrl.isNullOrBlank()) {
                            Surface(Modifier.fillMaxWidth().height(118.dp), RoundedCornerShape(16.dp), color = SpotifyGreen.copy(alpha = 0.2f)) {
                                Box(contentAlignment = Alignment.Center) { SpotifyLogo(Modifier.size(42.dp), tint = SpotifyGreen, waveTint = Color.White) }
                            }
                        } else {
                            AsyncImage(
                                model = playlist.imageUrl,
                                contentDescription = playlist.name,
                                modifier = Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Spacer(Modifier.height(Spacing.Md))
                        Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.trackCount} songs", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: SpotifyPlaylistItem,
    tracks: List<SpotifyTrackItem>,
    loading: Boolean,
    onBack: () -> Unit,
    onTrackClick: (SpotifyTrackItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Spotify library") }
                Spacer(Modifier.width(Spacing.Sm))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${playlist.trackCount} songs", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Surface(onClick = { onBack(); }, shape = RoundedCornerShape(50), color = SpotifyGreen, contentColor = Color.Black) {
                    Text("Back", modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(Spacing.Md))
            if (loading) {
                repeat(4) { TrackRowSkeleton() }
            } else if (tracks.isEmpty()) {
                Text("This playlist has no playable tracks yet.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Spacing.Lg))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(tracks, key = { it.uri }) { track ->
                        TrackRow(track = track, isActive = false, onClick = { onTrackClick(track) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(
    browse: SpotifyBrowseState,
    onTrackClick: (SpotifyTrackItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current
    val tracks = browse.visibleTracks

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            ) {
                Text(
                    if (browse.query.isNotBlank()) "Results" else if (browse.libraryTracks == browse.recentlyPlayed && browse.recentlyPlayed.isNotEmpty()) "Recently played" else "Your top tracks",
                    color = cs.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (browse.status == BrowseStatus.LOADING) {
                    CircularProgressIndicator(
                        color = SpotifyGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            val listState = when {
                tracks.isEmpty() && browse.status == BrowseStatus.LOADING -> 0
                browse.error != null && tracks.isEmpty() -> 1
                tracks.isEmpty() -> 2
                else -> 3
            }
            AnimatedContent(
                targetState = listState to browse.query.isNotBlank(),
                transitionSpec = { fadeThroughTransition() },
                label = "TrackListState",
            ) { (state, _) ->
                when (state) {
                    0 -> Column(Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)) {
                        repeat(4) { index ->
                            StaggeredEntrance(index) {
                                TrackRowSkeleton()
                                Spacer(Modifier.height(Spacing.Sm))
                            }
                        }
                    }
                    1 -> AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(250)) + expandVertically(),
                    ) {
                        Text(
                            browse.error.orEmpty(),
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
                        )
                    }
                    2 -> Text(
                        "Nothing here yet — search for something.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    ) {
                        items(tracks, key = { it.uri }) { track ->
                            TrackRow(
                                track = track,
                                isActive = track.uri == browse.activeUri,
                                onClick = { onTrackClick(track) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: SpotifyTrackItem,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = PressSpring,
        label = "TrackRowScale",
    )
    val background by animateColorAsState(
        targetValue = if (isActive) SpotifyGreen.copy(alpha = 0.13f) else Color.Transparent,
        animationSpec = tween(250, easing = EmphasizedDecelerate),
        label = "TrackRowBackground",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = {
                    ambientInteraction()
                    onClick()
                },
            )
            .padding(horizontal = Spacing.Lg, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.onSurface.copy(alpha = 0.08f))
        ) {
            if (track.albumArtUrl != null) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                track.artist,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AnimatedContent(
            targetState = isActive,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(initialScale = 0.4f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)))
                    .togetherWith(fadeOut(tween(90)))
            },
            label = "TrackRowIcon",
        ) { active ->
            if (active) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    MiniEqualizer(color = SpotifyGreen)
                }
            } else {
                AnimatedIcon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(20.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MiniEqualizer(color: Color) {
    val transition = rememberInfiniteTransition(label = "MiniEqualizer")
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val h by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(300 + i * 120, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "MiniEq$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

@Composable
private fun TrackRowSkeleton() {
    SkeletonRow(modifier = Modifier.fillMaxWidth(), trailing = true)
}

@Composable
private fun ConnectCard(isConnecting: Boolean, error: String?, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current
    val pulse = rememberInfiniteTransition(label = "ConnectPulse")
    val logoScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "LogoPulse",
    )

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(Spacing.Xxl),
        ) {
            Surface(
                modifier = Modifier.size(84.dp).scale(if (isConnecting) logoScale else 1f),
                shape = CircleShape,
                color = SpotifyGreen.copy(alpha = 0.15f),
                tonalElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SpotifyLogo(modifier = Modifier.size(48.dp), tint = SpotifyGreen, waveTint = Color.White)
                }
            }
            Spacer(Modifier.height(Spacing.Lg))
            Text(
                "Connect Spotify",
                color = cs.onSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Log in to Spotify to feel haptics in your favourite music.",
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.Sm, bottom = Spacing.Xl),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    ambientInteraction()
                    onConnect()
                },
                enabled = !isConnecting,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Black.copy(alpha = 0.7f),
                    disabledContentColor = Color.White.copy(alpha = 0.8f),
                ),
                modifier = Modifier.height(52.dp),
            ) {
                AnimatedVisibility(
                    visible = isConnecting,
                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)),
                    exit = fadeOut(tween(100)),
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(if (isConnecting) Spacing.Sm else 0.dp))
                Text(
                    if (isConnecting) "Connecting…" else "Connect to Spotify",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Text(
                    error.orEmpty(),
                    color = cs.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.Md),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingCard(viewModel: SpotifyViewModel, track: SpotifyTrack) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {

            track.albumArtUri?.takeIf { it.isNotBlank() }?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(28.dp)
                        .scale(1.12f)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .alpha(0.22f),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(cs.surfaceContainer.copy(alpha = 0.74f)))
            }

            Box(
                Modifier
                    .size(180.dp)
                    .align(Alignment.TopStart)
                    .offset((-40).dp, (-40).dp)
                    .background(
                        Brush.radialGradient(listOf(SpotifyGreen.copy(alpha = 0.14f), Color.Transparent)),
                        CircleShape,
                    ),
            )
            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.Xl)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = SpotifyGreen.copy(alpha = 0.2f),
                    ) {
                        if (track.albumArtUri.isNullOrBlank()) {
                            Box(contentAlignment = Alignment.Center) {
                                SpotifyLogo(modifier = Modifier.size(32.dp), tint = SpotifyGreen, waveTint = Color.White)
                            }
                        } else {
                            AsyncImage(
                                model = track.albumArtUri,
                                contentDescription = "Album artwork",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.Lg))
                    Column {
                        Text(
                            track.title.ifBlank { "Nothing playing" },
                            color = cs.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist.ifBlank { "Open Spotify and pick a track" },
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))
                val duration = track.durationMs.coerceAtLeast(1L)
                Slider(
                    value = (track.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { fraction -> viewModel.seekTo((fraction * duration).toLong()) },
                    colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen),
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatSpotifyTime(track.positionMs), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(formatSpotifyTime(track.durationMs), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(Spacing.Lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableTonalIconButton(
                        onClick = {
                            ambientInteraction()
                            viewModel.skipPrevious()
                        },
                        size = 52.dp,
                        fg = cs.onSurface,
                    ) {
                        AnimatedIcon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                    }
                    Spacer(Modifier.width(Spacing.Lg))

                    Box(contentAlignment = Alignment.Center) {
                        if (!track.isPaused) {
                            val pulse = rememberInfiniteTransition(label = "SpotifyPlayPulse")
                            val ringScale by pulse.animateFloat(
                                initialValue = 0.94f,
                                targetValue = 1.18f,
                                animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                label = "SpotifyPlayPulseScale",
                            )
                            val ringAlpha by pulse.animateFloat(
                                initialValue = 0.32f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                label = "SpotifyPlayPulseAlpha",
                            )
                            Box(
                                Modifier
                                    .size(72.dp * ringScale)
                                    .background(SpotifyGreen.copy(alpha = ringAlpha), CircleShape),
                            )
                        }
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val playElevation by androidx.compose.animation.core.animateDpAsState(
                            targetValue = if (pressed) 1.dp else 6.dp,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "SpotifyPlayElevation",
                        )
                        FilledIconButton(
                            onClick = {
                                ambientInteraction()
                                viewModel.togglePlayPause()
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .pressScale(interaction, pressedScale = 0.9f)
                                .shadow(playElevation, CircleShape, clip = false),
                            interactionSource = interaction,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SpotifyGreen,
                                contentColor = Color.Black,
                            ),
                        ) {
                            AnimatedIcon(
                                if (track.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                "Play/Pause",
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.Lg))
                    PressableTonalIconButton(
                        onClick = {
                            ambientInteraction()
                            viewModel.skipNext()
                        },
                        size = 52.dp,
                        fg = cs.onSurface,
                    ) {
                        AnimatedIcon(Icons.Rounded.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun PressableTonalIconButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    fg: Color,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val ambientInteraction = LocalAmbientInteraction.current
    FilledTonalIconButton(
        onClick = {
            ambientInteraction()
            onClick()
        },
        modifier = Modifier.size(size).pressScale(interaction, pressedScale = 0.88f),
        interactionSource = interaction,
        colors = IconButtonDefaults.filledTonalIconButtonColors(contentColor = fg),
    ) {
        content()
    }
}

@Composable
private fun TrackHapticsPanel(
    haptics: TrackHapticUiState,
    captureLevel: StateFlow<Float>,
    onGrantAccess: () -> Unit,
    onRecapture: () -> Unit,
    onStopLearning: () -> Unit,
    onSensitivity: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    val (statusText, statusColor) = when (haptics.mode) {
        HapticMode.SYNCED -> "Synced" to SpotifyGreen
        HapticMode.CAPTURING -> "Learning this track…" to cs.primary
        HapticMode.NEEDS_PERMISSION -> "Needs one-time access" to cs.onSurfaceVariant
        HapticMode.UNSUPPORTED -> "Needs Android 10 or newer" to cs.onSurfaceVariant
        HapticMode.IDLE -> "Waiting for playback" to cs.onSurfaceVariant
        HapticMode.CAPTURE_FAILED -> "Capture failed — try again" to cs.error
        HapticMode.ENCODE_FAILED -> "Couldn't build the haptic file" to cs.error
        HapticMode.PLAYBACK_FAILED -> "Couldn't play the haptic file" to cs.error
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.Xl)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        "Track Haptics",
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AnimatedContent(
                        targetState = haptics.mode,
                        transitionSpec = {
                            (fadeIn(tween(220, easing = EmphasizedDecelerate)) +
                                androidx.compose.animation.slideInVertically(tween(220, easing = EmphasizedDecelerate)) { h -> h / 3 })
                                .togetherWith(fadeOut(tween(120)) +
                                    androidx.compose.animation.slideOutVertically(tween(120)) { h -> -h / 3 })
                        },
                        label = "HapticStatus",
                    ) { _ ->
                        Text(statusText, color = statusColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                AnimatedContent(
                    targetState = haptics.mode,
                    transitionSpec = { fadeThroughTransition(enterDuration = 200, exitDuration = 90) },
                    label = "HapticAction",
                ) { mode ->
                    when (mode) {
                        HapticMode.NEEDS_PERMISSION -> PressableFilledTonalButton(
                            onClick = onGrantAccess,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = SpotifyGreen.copy(alpha = 0.24f)
                            ),
                        ) {
                            Text("Allow", fontWeight = FontWeight.SemiBold)
                        }
                        HapticMode.SYNCED -> PressableFilledTonalButton(onClick = onRecapture) {
                            AnimatedIcon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.Xs))
                            Text("Redo", fontWeight = FontWeight.SemiBold)
                        }
                        HapticMode.CAPTURING -> PressableFilledTonalButton(
                            onClick = onStopLearning,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = cs.error.copy(alpha = 0.2f)
                            ),
                        ) {
                            AnimatedIcon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.Xs))
                            Text("Stop", fontWeight = FontWeight.SemiBold)
                        }
                        HapticMode.CAPTURE_FAILED, HapticMode.ENCODE_FAILED, HapticMode.PLAYBACK_FAILED ->
                            PressableFilledTonalButton(onClick = onRecapture) {
                                Text("Retry", fontWeight = FontWeight.SemiBold)
                            }
                        else -> Spacer(Modifier.height(36.dp))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Lg))
            CaptureLevelBar(mode = haptics.mode, levelFlow = captureLevel)

            Spacer(Modifier.height(Spacing.Xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Haptic Intensity",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                RollingNumber(
                    value = haptics.sensitivity * 100f,
                    format = { "${it.toInt()}%" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = SpotifyGreen,
                )
            }
            Slider(
                value = haptics.sensitivity,
                onValueChange = onSensitivity,
                colors = SliderDefaults.colors(
                    thumbColor = SpotifyGreen,
                    activeTrackColor = SpotifyGreen,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Applied the next time this track is captured. Already-synced tracks keep their original intensity until you tap Redo.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            AnimatedVisibility(
                visible = haptics.mode == HapticMode.NEEDS_PERMISSION,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Text(
                    "Recording your own device's audio needs two one-time approvals: microphone access (required by Android for any audio capture, even though this doesn't use the mic itself) and a system prompt to capture on-device audio playback. Once granted, this only has to happen once — future tracks reuse it automatically.",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.Md),
                )
            }
            AnimatedVisibility(
                visible = haptics.mode == HapticMode.CAPTURE_FAILED,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Column {
                    Text(
                        "Not enough audio came through to build haptics from. This can happen if Spotify opts a track's playback out of capture, or if the capture was interrupted. Tap Retry or play the track again.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Spacing.Md),
                    )
                    ErrorDetailBox(haptics.lastError)
                }
            }
            AnimatedVisibility(
                visible = haptics.mode == HapticMode.ENCODE_FAILED,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Column {
                    Text(
                        "The recording was fine, but converting it into a haptic file didn't work this time. Tap Retry to try again.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Spacing.Md),
                    )
                    ErrorDetailBox(haptics.lastError)
                }
            }
            AnimatedVisibility(
                visible = haptics.mode == HapticMode.PLAYBACK_FAILED,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Column {
                    Text(
                        "The haptic file exists, but this device wouldn't play it back. Tap Retry to record it fresh.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Spacing.Md),
                    )
                    ErrorDetailBox(haptics.lastError)
                }
            }
        }
    }
}

@Composable
private fun ErrorDetailBox(detail: String?) {
    if (detail.isNullOrBlank()) return
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = cs.errorContainer.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Sm)
            .heightIn(max = 120.dp),
    ) {
        SelectionContainer {
            Text(
                detail,
                color = cs.onErrorContainer,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.Md),
            )
        }
    }
}

@Composable
private fun CaptureLevelBar(mode: HapticMode, levelFlow: StateFlow<Float>) {
    val cs = MaterialTheme.colorScheme
    val level by levelFlow.collectAsState()
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f).coerceAtLeast(0.02f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "CaptureLevel",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(
                    when (mode) {
                        HapticMode.CAPTURING -> animatedLevel
                        HapticMode.SYNCED -> 1f
                        else -> 0.02f
                    }
                )
                .clip(RoundedCornerShape(50))
                .background(
                    when (mode) {
                        HapticMode.SYNCED, HapticMode.CAPTURING ->
                            Brush.horizontalGradient(listOf(SpotifyGreen, cs.primary))
                        HapticMode.CAPTURE_FAILED, HapticMode.ENCODE_FAILED, HapticMode.PLAYBACK_FAILED ->
                            Brush.horizontalGradient(listOf(cs.error, cs.error.copy(alpha = 0.5f)))
                        else ->
                            Brush.horizontalGradient(listOf(cs.onSurface.copy(alpha = 0.2f), cs.onSurface.copy(alpha = 0.1f)))
                    }
                )
        )
    }
}
