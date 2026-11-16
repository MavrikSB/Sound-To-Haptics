
package com.sound.to.haptics.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close

import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import com.sound.to.haptics.bitchord.BrowseStatus
import com.sound.to.haptics.bitchord.BitChordBrowseState
import com.sound.to.haptics.bitchord.BitChordPlaylistItem
import com.sound.to.haptics.bitchord.BitChordSongItem
import com.sound.to.haptics.bitchord.BitChordViewModel
import com.sound.to.haptics.bitchord.ConvertState
import com.sound.to.haptics.bitchord.ConvertStage
import com.sound.to.haptics.bitchord.auth.WebSessionMode
import com.sound.to.haptics.bitchord.auth.YtMusicLoginScreen
import com.sound.to.haptics.ui.components.AnimatedIcon
import com.sound.to.haptics.ui.components.RollingNumber
import com.sound.to.haptics.ui.PressableOutlinedButton
import com.sound.to.haptics.ui.components.SkeletonRow
import com.sound.to.haptics.ui.components.StaggeredEntrance
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.icons.BitChordLogo
import com.sound.to.haptics.ui.LocalAmbientInteraction
import com.sound.to.haptics.ui.motion.EmphasizedDecelerate
import com.sound.to.haptics.ui.motion.verticalSettleTransition
import com.sound.to.haptics.ui.motion.PressSpring
import com.sound.to.haptics.ui.motion.fadeThroughTransition
import com.sound.to.haptics.ui.motion.reducedMotionTransition
import com.sound.to.haptics.ui.theme.BitChordBrandRed
import com.sound.to.haptics.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

private val LocalReducedMotion = compositionLocalOf { false }

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
private fun Modifier.thumbnailBorder(shape: RoundedCornerShape): Modifier {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return this.then(
        border(
            1.dp,
            if (dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
            shape,
        ),
    )
}

@Composable
private fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    var online by remember { mutableStateOf(true) }
    DisposableEffect(connectivity) {
        if (connectivity == null) {
            online = true
            return@DisposableEffect onDispose {}
        }
        online = connectivity.activeNetwork
            ?.let { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            ?: false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            override fun onLost(network: Network) {
                online = false
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    return online
}

@Composable
fun BitChordScreen(viewModel: BitChordViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val cs = MaterialTheme.colorScheme
    val signedIn = uiState.connection.isSignedIn
    val selectedCollection = uiState.browse.selectedCollection
    val ambientInteraction = LocalAmbientInteraction.current
    var showSignOut by remember { mutableStateOf(false) }
    val online = rememberIsOnline()
    val reducedMotion = rememberReducedMotion()

    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }

    val isActivelyPlaying = uiState.connection.isPlaying
    DisposableEffect(isActivelyPlaying) {
        com.sound.to.haptics.settings.PlaybackActivity.setPlaying(isActivelyPlaying)
        onDispose { com.sound.to.haptics.settings.PlaybackActivity.setPlaying(false) }
    }

    val convertState by viewModel.convertState.collectAsState()
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

    if (uiState.connection.signingIn) {
        BitChordSignInScreen(
            onClose = viewModel::cancelLogin,
            onCaptured = viewModel::onSessionCaptured,
        )
        return
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveContentWidth(modifier = Modifier.fillMaxSize()) {
            val drillTarget = when {
                nowPlayingOpen && signedIn && convertState.active -> "converting"
                nowPlayingOpen && signedIn -> "now-playing"
                else -> "feed"
            }
            AnimatedContent(
                targetState = drillTarget,
                transitionSpec = { if (reducedMotion) reducedMotionTransition() else fadeThroughTransition() },
                label = "DrillTransition",
            ) { target ->
                when (target) {
                    "converting" -> ConvertingScreen(
                    state = convertState,
                    onCancel = { viewModel.cancelConvert(); nowPlayingOpen = false },
                )
                "now-playing" -> NowPlayingScreen(
                    viewModel = viewModel,
                    item = uiState.connection.currentItem,
                    positionMs = uiState.connection.positionMs,
                    durationMs = uiState.connection.durationMs,
                    isPlaying = uiState.connection.isPlaying,
                    isBuffering = uiState.connection.isBuffering,
                    error = uiState.connection.error,
                    queueVisible = uiState.browse.queueVisible,
                    queue = uiState.browse.queue,
                    playingFrom = uiState.browse.selectedCollection?.name
                        ?: if (uiState.browse.query.isNotBlank()) "your search" else null,
                    onToggleQueue = viewModel::toggleQueue,
                    onRetry = viewModel::retryPlayback,
                    onClose = { nowPlayingOpen = false },
                )
                "feed" -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = Spacing.Lg)
                    .padding(top = Spacing.Lg, bottom = Spacing.Lg),
            ) {

                StaggeredEntrance(0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BitChordMark(modifier = Modifier.size(52.dp), logoSize = 36.dp)
                        Spacer(Modifier.width(Spacing.Md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "BitChord",
                                    color = cs.onSurface,
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.3).sp),
                                )
                            }
                            Text(
                                "YouTube Music client",
                                color = cs.onSurfaceVariant.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        SessionChip(
                            signedIn = signedIn,
                            accountName = uiState.connection.accountName,
                            onClick = { if (signedIn) showSignOut = true },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.Lg))

                AnimatedVisibility(
                    visible = !online,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                ) {
                    Column {
                        OfflineBanner(onRetry = viewModel::retryLibrary)
                        Spacer(Modifier.height(Spacing.Lg))
                    }
                }

                if (!signedIn) {
                    ConnectHero(
                        error = uiState.connection.error,
                        onConnect = viewModel::beginLogin,
                    )
                } else {
                    Column {
                        AnimatedVisibility(
                            visible = uiState.connection.error != null,
                            enter = fadeIn(tween(200)) + expandVertically(),
                            exit = fadeOut(tween(150)) + shrinkVertically(),
                        ) {
                            PlaybackErrorBanner(
                                uiState.connection.error,
                                onRetry = viewModel::retryPlayback,
                            )
                        }

                        if (selectedCollection == null) {
                            SearchBar(
                                query = uiState.browse.query,
                                onQueryChange = viewModel::updateQuery,
                                onSearch = viewModel::search,
                            )
                            Spacer(Modifier.height(Spacing.Lg))

                            if (uiState.browse.query.isBlank()) {
                                if (uiState.browse.playlists.isNotEmpty()) {
                                    BrandedSectionHeader(
                                        title = "Your playlists",
                                        subtitle = "Saved collections and liked music",
                                        modifier = Modifier.padding(bottom = Spacing.Sm),
                                    )
                                    PlaylistStrip(
                                        playlists = uiState.browse.playlists,
                                        onPlaylistClick = viewModel::openPlaylist,
                                        onLikedClick = viewModel::openLikedSongs,
                                    )
                                    Spacer(Modifier.height(Spacing.Lg))
                                }
                            }

                            LibrarySection(
                                browse = uiState.browse,
                                onTrackClick = { song ->
                                    ambientInteraction()
                                    viewModel.playTrack(song)
                                    nowPlayingOpen = true
                                },
                                onSuggestionClick = { suggestion ->
                                    viewModel.updateQuery(suggestion)
                                    viewModel.search()
                                },
                                onRetry = {
                                    if (uiState.browse.query.isNotBlank()) viewModel.search() else viewModel.retryLibrary()
                                },
                            )
                        } else {
                            CollectionDetail(
                                collection = selectedCollection,
                                tracks = uiState.browse.collectionTracks,
                                loading = uiState.browse.collectionLoading,
                                 activeVideoId = uiState.connection.currentItem?.videoId,
                                onBack = viewModel::closeCollection,
                                onTrackClick = { song ->
                                    ambientInteraction()
                                    viewModel.playCollectionTrack(song)
                                    nowPlayingOpen = true
                                },
                            )
                        }
                    }
                }
            }
            }
            }
        }

        if (showSignOut) {
            AlertDialog(
                onDismissRequest = { showSignOut = false },
                title = { Text("Sign out of BitChord?") },
                text = {
                    Text("Your YouTube Music session will be forgotten on this device. Playlists and likes stay with your account; cached haptic profiles stay on this device.")
                },
                confirmButton = {
                    TextButton(onClick = { showSignOut = false; viewModel.signOut() }) {
                        Text("Sign out", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOut = false }) { Text("Cancel") }
                },
            )
        }
    }
    }
}

@Composable
private fun BitChordMark(
    modifier: Modifier = Modifier,
    logoSize: androidx.compose.ui.unit.Dp = 23.dp,
) {
    Box(
        modifier = modifier
            .shadow(5.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFFFB5C74), Color(0xFFFA233B))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        BitChordLogo(modifier = Modifier.size(logoSize), tint = Color.White)
    }
}

@Composable
private fun SessionChip(
    signedIn: Boolean,
    accountName: String?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (signedIn) BitChordBrandRed.copy(alpha = 0.14f) else cs.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (signedIn) BitChordBrandRed else cs.onSurfaceVariant.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (signedIn) (accountName ?: "Signed in") else "Sign in",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (signedIn) BitChordBrandRed else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val heroPoints = listOf(
    Icons.Rounded.MusicNote to "Your YouTube Music library, likes, and playlists ",
    Icons.Rounded.Vibration to "Haptics synced to every track, automatically",
    Icons.Rounded.DownloadDone to "Profiles cached on your phone after the first play",
)

@Composable
private fun ConnectHero(
    error: String?,
    onConnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.Xxl, horizontal = Spacing.Xl),
        ) {
            StaggeredEntrance(0) { BitChordMark(modifier = Modifier.size(128.dp), logoSize = 91.dp) }
            Spacer(Modifier.height(Spacing.Xl))
            StaggeredEntrance(1) {
                Text(
                    "Feel every song",
                    color = cs.onSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Spacing.Xl))
            Column(modifier = Modifier.fillMaxWidth()) {
                heroPoints.forEachIndexed { index, (icon, text) ->
                    StaggeredEntrance(index + 2) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = BitChordBrandRed.copy(alpha = 0.12f),
                            ) {
                                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = BitChordBrandRed,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(Spacing.Md))
                            Text(
                                text,
                                color = cs.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Xl))
            ConnectButton(onConnect = { ambientInteraction(); onConnect() })
            Text(
                "Uses your own YouTube Music account, nothing is being logged.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.Md),
            )
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(tween(250)) + expandVertically(animationSpec = tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Text(
                    error.orEmpty(),
                    color = cs.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.Md),
                )
            }
        }
    }

}

@Composable
private fun ConvertingScreen(
    state: ConvertState,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val progress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(450, easing = EmphasizedDecelerate),
        label = "ConvertProgress",
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
                    progress = { progress },
                    modifier = Modifier.size(124.dp),
                    color = cs.primary,
                    strokeWidth = 6.dp,
                    trackColor = cs.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                RollingNumber(
                    value = progress * 100f,
                    format = { "${it.toInt()}%" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.height(Spacing.Xxl))

            AnimatedContent(
                targetState = state.stage,
                transitionSpec = { verticalSettleTransition() },
                label = "StageHeadline",
            ) { currentStage ->
                Text(
                    convertStageSequence.firstOrNull { it.stage == currentStage }?.label ?: "Processing",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.Xs))
            state.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Preparing $it",
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
                    convertStageSequence.forEachIndexed { index, info ->
                        StaggeredEntrance(index, modifier = Modifier.fillMaxWidth()) {
                            ConvertStageRow(info, currentStage = state.stage)
                        }
                        if (index < convertStageSequence.lastIndex) Spacer(Modifier.height(Spacing.Md))
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

private data class ConvertStageSpec(val stage: ConvertStage, val label: String)

private val convertStageSequence = listOf(
    ConvertStageSpec(ConvertStage.DOWNLOADING, "Preparing audio"),
    ConvertStageSpec(ConvertStage.ANALYZING, "Analyzing audio"),
    ConvertStageSpec(ConvertStage.BUILDING, "Generating haptic track"),
)

@Composable
private fun ConvertStageRow(info: ConvertStageSpec, currentStage: ConvertStage) {
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

@Composable
private fun NowPlayingScreen(
    viewModel: BitChordViewModel,
    item: BitChordSongItem?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    error: String?,
    queueVisible: Boolean,
    queue: List<BitChordSongItem>,
    playingFrom: String?,
    onToggleQueue: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ambientInteraction = LocalAmbientInteraction.current
    val track = item
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background),
    ) {

        item?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { artwork ->
            AsyncImage(
                model = artworkModel(artwork),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(70.dp)
                    .scale(1.06f)
                    .alpha(0.38f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.42f), Color.Black.copy(alpha = 0.60f), Color(0xFF0C0B0A)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Spacing.Lg)
                .padding(vertical = Spacing.Sm),
        ) {

            val backInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { ambientInteraction(); onClose() },
                    interactionSource = backInteraction,
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(backInteraction, pressedScale = 0.78f),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to library",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Xs))

            Spacer(Modifier.weight(0.8f))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.64f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(BitChordBrandRed.copy(alpha = 0.30f))
                        .thumbnailBorder(RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track?.thumbnailUrl.isNullOrBlank()) {
                        BitChordLogo(modifier = Modifier.size(128.dp), tint = Color.White.copy(alpha = 0.9f))
                    } else {
                        ArtworkImage(
                            url = track.thumbnailUrl,
                            contentDescription = "Album artwork",
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 84.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    track?.title?.ifBlank { "Nothing playing" } ?: "Nothing playing",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    track?.artist?.ifBlank { "Pick a song from your library" } ?: "Pick a song from your library",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))

                val duration = (durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L).coerceAtLeast(1L)

                var draggingFraction by remember(track?.videoId) { mutableFloatStateOf(0f) }
                var draggingActive by remember(track?.videoId) { mutableStateOf(false) }
                val currentFraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val sliderFraction = if (draggingActive) draggingFraction else currentFraction
                val sliderPosition = if (draggingActive) (sliderFraction * duration).toLong() else positionMs.coerceIn(0L, duration)
                Slider(
                    value = sliderFraction,
                    onValueChange = { fraction ->
                        draggingActive = true
                        draggingFraction = fraction.coerceIn(0f, 1f)
                    },
                    onValueChangeFinished = {
                        val target = (draggingFraction * duration).toLong()
                        draggingActive = false
                        viewModel.seekTo(target)
                    },
                    enabled = track != null,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.92f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                    ),
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatBitChordTime(sliderPosition), color = Color.White.copy(alpha = 0.60f), style = MaterialTheme.typography.labelSmall)
                    Text(formatBitChordTime(duration), color = Color.White.copy(alpha = 0.60f), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(Spacing.Lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportGhostButton(
                        onClick = { ambientInteraction(); viewModel.skipPrevious() },
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(Spacing.Xl))

                    Box(contentAlignment = Alignment.Center) {
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val playElevation by androidx.compose.animation.core.animateDpAsState(
                            targetValue = if (pressed) 1.dp else 0.dp,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "BitChordPlayElevation",
                        )
                        FilledIconButton(
                            onClick = { ambientInteraction(); viewModel.togglePlayPause() },
                            enabled = track != null,
                            modifier = Modifier
                                .size(72.dp)
                                .pressScale(interaction, pressedScale = 0.9f)
                                .shadow(playElevation, CircleShape, clip = false),
                            interactionSource = interaction,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White.copy(alpha = 0.35f),
                                disabledContentColor = Color.Black.copy(alpha = 0.45f),
                            ),
                        ) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                            } else {
                                AnimatedIcon(
                                    if (!isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    "Play/Pause",
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(Spacing.Xl))
                    TransportGhostButton(
                        onClick = { ambientInteraction(); viewModel.skipNext() },
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                if (isBuffering) {
                    Spacer(Modifier.height(Spacing.Sm))
                    Text(
                        "Loading audio…",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(Spacing.Md))
                    PlaybackErrorBanner(error, onRetry = { ambientInteraction(); onRetry() })
                }
            Spacer(Modifier.weight(1.2f))
        }

        AnimatedVisibility(
            visible = queueVisible,
            enter = fadeIn(tween(160)) + slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)) { it },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onToggleQueue),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(0.55f)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = cs.surfaceContainer,
            ) {
                QueuePanel(
                    queue = queue,
                    currentVideoId = track?.videoId,
                    onTrackClick = { index -> viewModel.seekToQueueItem(index) },
                    onClose = onToggleQueue,
                )
            }
        }
    }
}

@Composable
private fun BrandedSectionHeader(
    title: String,
    subtitle: String,
    prominent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Xs, vertical = 10.dp),
    ) {
        Text(
            title,
            color = cs.onSurface,
            fontWeight = if (prominent) FontWeight.ExtraBold else FontWeight.SemiBold,
            style = if (prominent) MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.2).sp) else MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaybackErrorBanner(message: String?, onRetry: () -> Unit) {
    if (message.isNullOrBlank()) return
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.errorContainer.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = cs.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.Md))
            Text(
                message,
                color = cs.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.Sm))
            TextButton(
                onClick = onRetry,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.Md),
            ) {
                Text("Retry", fontWeight = FontWeight.Bold, color = cs.onErrorContainer)
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
private fun TransportGhostButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val ambientInteraction = LocalAmbientInteraction.current
    IconButton(
        onClick = { ambientInteraction(); onClick() },
        interactionSource = interaction,
        modifier = Modifier
            .size(64.dp)
            .pressScale(interaction, pressedScale = 0.82f),
    ) {
        content()
    }
}

@Composable
private fun QueuePanel(
    queue: List<BitChordSongItem>,
    currentVideoId: String?,
    onTrackClick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Md),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Up next",
                        color = cs.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${queue.size} track" + if (queue.size == 1) "" else "s",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close queue")
                }
            }
            Spacer(Modifier.height(Spacing.Sm))
            if (queue.isEmpty()) {
                Text(
                    "Your queue is empty - play something first.",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Spacing.Lg),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    itemsIndexed(queue, key = { _, item -> item.videoId }) { index, item ->
                        val isActive = item.videoId == currentVideoId
                        val interaction = remember(item.videoId) { MutableInteractionSource() }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(interactionSource = interaction, indication = ripple()) { onTrackClick(index) }
                                .background(if (isActive) BitChordBrandRed.copy(alpha = 0.10f) else Color.Transparent)
                                .padding(horizontal = Spacing.Sm, vertical = 10.dp),
                        ) {
                            Text(
                                "${index + 1}",
                                color = cs.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(26.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    color = cs.onSurface,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.artist,
                                    color = cs.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isActive) {
                                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    MiniEqualizer(color = BitChordBrandRed)
                                }
                            }
                        }
                    }
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
        placeholder = { Text("Songs, artists, albums...") },
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
            focusedBorderColor = BitChordBrandRed,
            cursorColor = BitChordBrandRed,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun PlaylistStrip(
    playlists: List<BitChordPlaylistItem>,
    onPlaylistClick: (BitChordPlaylistItem) -> Unit,
    onLikedClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val artworkShape = RoundedCornerShape(18.dp)
    Column {

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = Spacing.Lg),
        ) {
            item(key = "__liked__") {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.94f else 1f, PressSpring, label = "LikedCardScale")
                Column(
                    modifier = Modifier
                        .width(158.dp)
                        .scale(scale)
                        .clickable(interactionSource = interaction, indication = ripple()) { onLikedClick() },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(artworkShape)
                            .thumbnailBorder(artworkShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFFFB5C74), Color(0xFFFA233B)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(Spacing.Sm))
                    Text(
                        "Liked music",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Your favourites", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                val interaction = remember(playlist.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.94f else 1f, PressSpring, label = "PlaylistCardScale")
                Column(
                    modifier = Modifier
                        .width(158.dp)
                        .scale(scale)
                        .clickable(interactionSource = interaction, indication = ripple()) { onPlaylistClick(playlist) },
                ) {
                    if (playlist.thumbnailUrl.isNullOrBlank()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(artworkShape)
                                .thumbnailBorder(artworkShape)
                                .background(BitChordBrandRed.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            BitChordLogo(Modifier.size(48.dp), tint = BitChordBrandRed)
                        }
                    } else {
                        ArtworkImage(
                            url = playlist.thumbnailUrl,
                            contentDescription = playlist.name,
                            shape = artworkShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .thumbnailBorder(artworkShape)
                                .background(cs.surfaceContainerHigh),
                            iconSize = 48.dp,
                        )
                    }
                    Spacer(Modifier.height(Spacing.Sm))
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playlist.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionDetail(
    collection: BitChordPlaylistItem,
    tracks: List<BitChordSongItem>,
    loading: Boolean,
    activeVideoId: String?,
    onBack: () -> Unit,
    onTrackClick: (BitChordSongItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to library")
                }
                Spacer(Modifier.width(Spacing.Sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        collection.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = cs.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        collection.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Md))
            if (loading) {
                repeat(4) { TrackRowSkeleton() }
            } else if (tracks.isEmpty()) {
                Text(
                    "This playlist has no playable tracks yet.",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Spacing.Lg),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    itemsIndexed(tracks, key = { _, item -> item.videoId }) { _, track ->
                        TrackRow(track = track, isActive = track.videoId == activeVideoId, onClick = { onTrackClick(track) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySection(
    browse: BitChordBrowseState,
    onTrackClick: (BitChordSongItem) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tracks = browse.visibleTracks
    val isSearching = browse.query.isNotBlank()
    val title = when {
        isSearching -> "Results"
        browse.libraryTracks.isNotEmpty() && browse.libraryTracks == browse.recentlyPlayed -> "Recently played"
        else -> "Your library"
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (tracks.isNotEmpty()) {
                        Text(
                            "${tracks.size} track" + if (tracks.size == 1) "" else "s",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (browse.status == BrowseStatus.LOADING) {
                    CircularProgressIndicator(
                        color = BitChordBrandRed,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            when {
                tracks.isNotEmpty() -> {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        itemsIndexed(tracks, key = { _, item -> item.videoId }) { _, track ->
                            TrackRow(
                                track = track,
                                isActive = track.videoId == browse.activeVideoId,
                                onClick = { onTrackClick(track) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                browse.status == BrowseStatus.LOADING -> {
                    Column(Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)) {
                        repeat(4) { index ->
                            StaggeredEntrance(index) {
                                TrackRowSkeleton()
                            }
                            if (index < 3) Spacer(Modifier.height(Spacing.Sm))
                        }
                    }
                }
                browse.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Xl, vertical = Spacing.Xxl),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = cs.errorContainer.copy(alpha = 0.5f),
                        ) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.Md))
                        Text(
                            browse.error,
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Spacing.Md))
                        FilledTonalButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = BitChordBrandRed.copy(alpha = 0.16f),
                                contentColor = BitChordBrandRed,
                            ),
                        ) {
                            Text("Try again", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                isSearching && browse.searchSuggestions.isNotEmpty() -> {
                    Column(Modifier.padding(vertical = Spacing.Sm)) {
                        Text(
                            "Suggestions",
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
                        )
                        browse.searchSuggestions.forEach { suggestion ->
                            val interaction = remember { MutableInteractionSource() }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(interactionSource = interaction, indication = ripple()) {
                                        onSuggestionClick(suggestion)
                                    }
                                    .padding(horizontal = Spacing.Lg, vertical = 12.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Spacing.Md))
                                Text(
                                    suggestion,
                                    color = cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        if (isSearching) "Nothing found - check the spelling or try fewer words."
                        else "Nothing here yet. Search something, or play a track to start a library.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: BitChordSongItem,
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
        targetValue = if (isActive) BitChordBrandRed.copy(alpha = 0.13f) else Color.Transparent,
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
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp))
                .background(BitChordBrandRed.copy(alpha = 0.10f)),
        ) {
            ArtworkImage(
                url = track.thumbnailUrl,
                contentDescription = null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxSize(),
                iconSize = 22.dp,
            )
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
                    MiniEqualizer(color = BitChordBrandRed)
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
private fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 44.dp,
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isNullOrBlank() || failed) {
        Box(
            modifier = modifier.clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFFB5C74), Color(0xFFFA233B)))),
            )
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        AsyncImage(
            model = artworkModel(url),
            contentDescription = contentDescription,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
    }
}

private fun hiResArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val bumped = Regex("""=w\d+-h\d+""").replaceFirst(url, "=w1080-h1080")
    return if (bumped != url) bumped else Regex("""=s\d+""").replaceFirst(url, "=s1080")
}

@Composable
private fun artworkModel(url: String?): Any? {
    val context = LocalContext.current
    if (url.isNullOrBlank()) return null
    return remember(url) {
        val hiRes = hiResArtworkUrl(url) ?: url
        val key = "artwork:$hiRes"
        coil.request.ImageRequest.Builder(context)
            .data(hiRes)
            .memoryCacheKey(key)
            .placeholderMemoryCacheKey(key)
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}

@Composable
private fun MiniEqualizer(color: Color) {

    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "MiniEqualizer")
    val h0 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "MiniEq0",
    )
    val h1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "MiniEq1",
    )
    val h2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(540, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "MiniEq2",
    )
    val heights = if (reducedMotion) floatArrayOf(0.55f, 0.9f, 0.4f) else floatArrayOf(h0, h1, h2)
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(heights[i])
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
private fun BitChordSignInScreen(
    onClose: () -> Unit,
    onCaptured: (com.sound.to.haptics.bitchord.auth.CapturedSession) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var pageReady by remember { mutableStateOf(false) }
    var captureRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var attemptError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close sign in")
                }
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    "Sign in to BitChord",
                    color = cs.onSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        attemptError = null
                        captureRequest += 1
                    },
                    enabled = pageReady,
                ) {
                    Text("I'm signed in", fontWeight = FontWeight.Bold)
                }
            }
            YtMusicLoginScreen(
                mode = WebSessionMode.SIGN_IN,
                onCaptured = onCaptured,
                captureRequest = captureRequest,
                onCaptureUnavailable = {
                    attemptError = "Not signed in yet - finish signing in first."
                },
                onPageReady = { ready -> pageReady = ready },
                modifier = Modifier.fillMaxSize(),
            )
        }
        attemptError?.let { message ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = cs.errorContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.Lg),
            ) {
                Text(
                    message,
                    color = cs.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Spacing.Lg),
                )
            }
        }
    }
}

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.errorContainer.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = cs.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.Md))
            Text(
                "You are offline - playback and sync need a connection. Already-saved haptic profiles still work.",
                color = cs.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.Sm))
            TextButton(onClick = onRetry) {
                Text("Retry", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConnectButton(onConnect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = { onConnect() },
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitChordBrandRed,
            contentColor = Color.White,
        ),
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .pressScale(interaction, pressedScale = 0.95f),
    ) {
        Text(
            "Continue with Google",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
