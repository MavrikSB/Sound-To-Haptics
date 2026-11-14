package com.sound.to.haptics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Shared tactile cue for the procedural background. The shell owns the token;
 * any interactive surface can emit a cue without coupling itself to the
 * navigation implementation.
 */
val LocalAmbientInteraction = compositionLocalOf<() -> Unit> { {} }

@Composable
fun rememberAmbientInteraction(): () -> Unit = LocalAmbientInteraction.current