package com.sound.to.haptics.ui

import java.util.Locale

internal fun formatSpotifyTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
}
