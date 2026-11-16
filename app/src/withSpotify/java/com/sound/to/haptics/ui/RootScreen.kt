package com.sound.to.haptics.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sound.to.haptics.BuildConfig
import com.sound.to.haptics.MainActivity
import com.sound.to.haptics.MainViewModel
import com.sound.to.haptics.debug.DebugToolsScreen
import com.sound.to.haptics.debug.DebugToolsViewModel
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.settings.BackgroundStyle
import com.sound.to.haptics.spotify.SpotifyViewModel
import com.sound.to.haptics.ui.icons.SpotifyLogo
import com.sound.to.haptics.ui.motion.reducedMotionTransition
import com.sound.to.haptics.ui.motion.sharedAxisXTransition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

private data class BottomTab(val label: String, val debugOnly: Boolean = false)

private val bottomTabs = buildList {
    add(BottomTab("Offline"))
    add(BottomTab("Spotify"))
    add(BottomTab("Settings"))
    if (BuildConfig.DEBUG) add(BottomTab("Debug", debugOnly = true))
}
private const val OFFLINE_TAB_INDEX = 0
private const val SPOTIFY_TAB_INDEX = 1
private const val SETTINGS_TAB_INDEX = 2

@Composable
fun RootScreen() {

    var selectedTab by rememberSaveable("navigation_functional_v3") { mutableIntStateOf(OFFLINE_TAB_INDEX) }
    val tabStack = remember { mutableStateListOf(selectedTab) }
    var navForward by remember { mutableStateOf(true) }
    var showStartup by rememberSaveable { mutableStateOf(true) }
    var showOnboarding by rememberSaveable { mutableStateOf(!AppSettings.state.value.onboardingCompleted) }
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }

    LaunchedEffect(bottomTabs.size) {
        if (selectedTab !in bottomTabs.indices) {
            selectedTab = OFFLINE_TAB_INDEX
            tabStack.clear()
            tabStack.add(OFFLINE_TAB_INDEX)
        }
    }

    val offlineViewModel: MainViewModel = viewModel()
    val spotifyViewModel: SpotifyViewModel = viewModel()
    val debugViewModel: DebugToolsViewModel = viewModel()
    val spotifyState by spotifyViewModel.uiState.collectAsState()
    val featureFlags by debugViewModel.featureFlags.collectAsState()
    val appSettings by AppSettings.state.collectAsState()
    var interactionToken by remember { mutableIntStateOf(0) }

    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity) {
        activity?.pendingSpotifyAuthHandler = { code, result, data ->
            spotifyViewModel.controller.handleAuthResult(code, result, data)
        }
        onDispose { activity?.pendingSpotifyAuthHandler = null }
    }

    fun stopLeavingTab(leaving: Int) {
        when (leaving) {
            OFFLINE_TAB_INDEX -> offlineViewModel.stopPlayback()
            SPOTIFY_TAB_INDEX -> spotifyViewModel.stopAll()
        }
    }

    fun navigateTo(index: Int) {
        if (index == selectedTab) return
        navForward = index > selectedTab
        stopLeavingTab(selectedTab)
        tabStack.add(index)
        selectedTab = index
        interactionToken++
    }

    val canGoBack = tabStack.size > 1
    PredictiveBackHandler(enabled = canGoBack) { progress ->
        scope.launch {
            try {
                progress.collect { event -> backProgress.snapTo(event.progress) }
                if (tabStack.size > 1) {
                    val leaving = tabStack.removeAt(tabStack.lastIndex)
                    navForward = false
                    stopLeavingTab(leaving)
                    selectedTab = tabStack.last()
                }
            } finally {
                withContext(NonCancellable) { backProgress.snapTo(0f) }
            }
        }
    }

    val backScale = 1f - backProgress.value * 0.05f
    val backTranslation = backProgress.value * 48f

    LaunchedEffect(Unit) {
        if (!featureFlags.bypassBootAnimation) delay(1450)
        showStartup = false
    }

    CompositionLocalProvider(LocalAmbientInteraction provides { interactionToken++ }) {
        val ambientInteraction = LocalAmbientInteraction.current
        Box(
            Modifier
                .fillMaxSize()

                .background(MaterialTheme.colorScheme.background)
        ) {
            if (featureFlags.dynamicBackground && appSettings.backgroundStyle != BackgroundStyle.OFF) {
                DriftingBackground(
                    energy = if (spotifyState.connection.isConnected && !spotifyState.connection.track.isPaused) {
                        0.64f + 0.36f * abs(sin(spotifyState.connection.track.positionMs / 145f))
                    } else 0.08f,
                    interactionToken = interactionToken,
                    lowFx = featureFlags.simulateLowEndGpu,
                    frozen = appSettings.backgroundStyle == BackgroundStyle.STATIC || appSettings.reduceMotion,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        bottomTabs.forEachIndexed { index, tab ->
                            val selected = selectedTab == index
                            val iconScale by animateFloatAsState(
                                targetValue = if (appSettings.reduceMotion) 1f else if (selected) 1.14f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                label = "BottomNavIconScale",
                            )
                            NavigationBarItem(
                                selected = selected,
                                onClick = { ambientInteraction(); navigateTo(index) },
                                icon = {
                                    Box(Modifier.scale(iconScale)) {
                                        when {
                                            tab.debugOnly -> Icon(Icons.Rounded.BugReport, contentDescription = tab.label)
                                            index == SPOTIFY_TAB_INDEX -> SpotifyLogo(
                                                modifier = Modifier.size(24.dp),
                                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                waveTint = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                            )
                                            index == SETTINGS_TAB_INDEX -> Icon(Icons.Rounded.Settings, contentDescription = tab.label)
                                            else -> Icon(Icons.Rounded.GraphicEq, contentDescription = tab.label)
                                        }
                                    }
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .graphicsLayer {
                            scaleX = backScale
                            scaleY = backScale
                            translationX = backTranslation
                        },
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            if (appSettings.reduceMotion) reducedMotionTransition()
                            else sharedAxisXTransition(forward = navForward)
                        },
                        label = "TabTransition",
                    ) { tab ->
                        when (tab) {
                            OFFLINE_TAB_INDEX -> HapticStudioScreen(offlineViewModel)
                            SPOTIFY_TAB_INDEX -> SpotifyScreen(spotifyViewModel)
                            SETTINGS_TAB_INDEX -> SettingsScreen(
                                onClearHapticCache = debugViewModel::clearSpotifyHapticCache,
                                onClearLibrary = offlineViewModel::clearLibrary,
                                onOpenTutorial = {
                                    AppSettings.reopenOnboarding()
                                    showOnboarding = true
                                },
                            )
                            else -> DebugToolsScreen(debugViewModel)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showStartup && !featureFlags.bypassBootAnimation,
                enter = if (appSettings.reduceMotion) fadeIn(tween(1)) else fadeIn(tween(180)),
                exit = if (appSettings.reduceMotion) fadeOut(tween(1)) else fadeOut(tween(420)),
            ) { StartupScreen() }
            AnimatedVisibility(
                visible = showOnboarding && !showStartup,
                enter = if (appSettings.reduceMotion) fadeIn(tween(1)) else fadeIn(tween(220)),
                exit = if (appSettings.reduceMotion) fadeOut(tween(1)) else fadeOut(tween(180)),
            ) {
                OnboardingScreen(onFinished = { showOnboarding = false })
            }

            if (BuildConfig.DEBUG) {
                val wireframe by debugViewModel.wireframeBounds.collectAsState()
                val grid by debugViewModel.alignmentGrid.collectAsState()
                val margins by debugViewModel.marginPaddingOverlay.collectAsState()
                DebugOverlay(wireframeBounds = wireframe, alignmentGrid = grid, marginPaddingOverlay = margins)
            }
        }
    }
}
