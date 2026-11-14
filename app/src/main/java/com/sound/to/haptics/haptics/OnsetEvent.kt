package com.sound.to.haptics.haptics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * A single detected beat/transient, with strength already normalized to
 * 0..1. This remains the Continuous-mode pipeline's onset representation
 * ([MultiLayerAudioAnalyzer]'s `events` list, persisted as the legacy
 * `.onsets` sidecar and folded into [LayeredHapticAnalysis.toEnvelope]'s
 * continuous waveform). It has nothing to do with Transient Mode anymore —
 * see [TransientEvent] for that pipeline's much richer, classified event
 * representation.
 *
 * Historical note: this file used to also contain `BeatOnsetDetector`, a
 * single-band energy-flux onset detector that was never actually wired
 * into any live playback path in this project (confirmed by exhaustive
 * search — nothing called `BeatOnsetDetector.detect(...)` anywhere). It has
 * been removed as obsolete/dead code rather than kept around as unused
 * scaffolding; the real, actively-used onset detection for Continuous mode
 * lives in [MultiLayerAudioAnalyzer], and Transient Mode now has its own
 * dedicated, much more capable detector in [TransientOnsetDetector].
 */
data class OnsetEvent(
    val timestampMs: Long,
    val strength: Float,
    val kind: HapticEventKind = HapticEventKind.PERCUSSIVE,
    val durationMs: Long = 48L,
)

private const val ONSET_FILE_MAGIC = 0x534F4E32

fun List<OnsetEvent>.saveTo(file: File) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(ONSET_FILE_MAGIC)
        out.writeInt(size)
        for (event in this) {
            out.writeLong(event.timestampMs)
            out.writeFloat(event.strength)
            out.writeInt(event.kind.ordinal)
            out.writeLong(event.durationMs)
        }
    }
}

fun loadOnsetEvents(file: File): List<OnsetEvent>? {
    if (!file.exists()) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val first = input.readInt()
            val versioned = first == ONSET_FILE_MAGIC
            val count = if (versioned) input.readInt() else first
            if (count < 0 || count > 500_000) return null
            List(count) {
                val timestamp = input.readLong()
                val strength = input.readFloat()
                if (versioned) {
                    val kind = HapticEventKind.entries.getOrElse(input.readInt()) { HapticEventKind.PERCUSSIVE }
                    val duration = input.readLong().coerceIn(1L, 2_000L)
                    OnsetEvent(timestamp, strength, kind, duration)
                } else {
                    OnsetEvent(timestamp, strength)
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
