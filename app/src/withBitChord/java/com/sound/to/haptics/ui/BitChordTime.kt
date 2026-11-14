package com.sound.to.haptics.ui

/** mm:ss for a millisecond position; negative values clamp to 0:00. */
fun formatBitChordTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "%d:%02d".format(minutes, remainder)
}
