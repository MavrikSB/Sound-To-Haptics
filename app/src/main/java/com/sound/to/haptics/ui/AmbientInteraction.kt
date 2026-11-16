package com.sound.to.haptics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalAmbientInteraction = compositionLocalOf<() -> Unit> { {} }

@Composable
fun rememberAmbientInteraction(): () -> Unit = LocalAmbientInteraction.current
