package com.sound.to.haptics.haptics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Perceptual category assigned by [TransientEventClassifier]. These are
 * heuristic buckets, not real instrument recognition (see spec section 9):
 * "the system does not need to perfectly identify instruments, it needs to
 * produce useful perceptual categories."
 */
enum class TransientEventType {
    /** Strong low-frequency energy, fast attack, low spectral centroid. */
    KICK,

    /** Strong mid/high-frequency energy with fast spectral flux, short duration. */
    SNARE,

    /** High-frequency energy, very short, little bass. */
    HIHAT,

    /** A real onset that doesn't cleanly match kick/snare/hat (e.g. toms, claps, guitar pick attacks). */
    PERCUSSIVE_OTHER,

    /** Not a discrete onset at all — persistent low-frequency energy driving the continuous bed. */
    BASS_BED,
}

/** Which of the four conceptual haptic layers (spec section 10) an event belongs to. */
enum class TransientLayer {
    /** Layer A — sustained low-frequency musical energy, gently rising and falling. */
    BED,

    /** Layer B — significant attacks: kick, snare, clap, strong percussion, major accents. */
    ACCENT,

    /** Layer C — heavily-limited selection of smaller events (hats, fine rhythmic detail). */
    TEXTURE,
}

/**
 * One scheduled haptic event in Transient Mode's musical rendering timeline.
 *
 * [intensity] and [sharpness] are two independent perceptual dimensions
 * (spec section 13): a kick can be high-intensity/low-sharpness while a
 * hi-hat is low-intensity/high-sharpness. Both are already fully shaped
 * (onset strength -> spectral relevance -> local prominence -> soft-knee
 * compression -> perceptual curve) by the time this object exists; the
 * user's haptic-strength multiplier is deliberately NOT baked in here — it
 * is applied only in [TransientHapticRenderer] at final render time so
 * relative ordering between event types survives any strength setting.
 */
data class TransientEvent(
    val timestampMs: Long,
    val type: TransientEventType,
    val layer: TransientLayer,
    val intensity: Float,
    val sharpness: Float,
    val durationMs: Long,
    val isMajorAccent: Boolean = false,
)

/**
 * The full result of analyzing one track for Transient Mode: the scheduled
 * event timeline plus the beat-tracking estimate that helped prioritize it.
 * [bpm]/[beatConfidence] are exposed mainly for the debug menu (spec
 * section 30) — playback itself never quantizes to them (spec section 16).
 */
data class TransientAnalysis(
    val version: Int,
    val sampleRate: Int,
    val durationMs: Long,
    val bpm: Float,
    val beatConfidence: Float,
    val events: List<TransientEvent>,
)

private const val TRANSIENT_MAGIC = 0x54484150 // "THAP"

/**
 * Serializes a [TransientAnalysis] next to its haptic OGG (see
 * [com.sound.to.haptics.library.HapticSong.transientFile]), exactly like
 * [LayeredHapticAnalysis.saveTo] does for the continuous pipeline. Kept as
 * its own compact binary format rather than reusing [LayeredHapticAnalysis]'s
 * on-disk shape, since the two pipelines' data no longer overlap (frames vs.
 * scheduled events) and are versioned independently (TransientConfig vs.
 * HapticProcessing) so a Transient-only algorithm change can never
 * accidentally invalidate the Continuous mode cache, or vice versa.
 */
fun TransientAnalysis.saveTo(file: File) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(TRANSIENT_MAGIC)
        out.writeInt(version)
        out.writeInt(sampleRate)
        out.writeLong(durationMs)
        out.writeFloat(bpm)
        out.writeFloat(beatConfidence)
        out.writeInt(events.size)
        events.forEach { event ->
            out.writeLong(event.timestampMs)
            out.writeInt(event.type.ordinal)
            out.writeInt(event.layer.ordinal)
            out.writeFloat(event.intensity)
            out.writeFloat(event.sharpness)
            out.writeLong(event.durationMs)
            out.writeBoolean(event.isMajorAccent)
        }
    }
}

fun loadTransientAnalysis(file: File): TransientAnalysis? {
    if (!file.exists() || file.length() < 24L) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != TRANSIENT_MAGIC) return null
            val version = input.readInt()
            if (version != TransientConfig.PROCESSING_VERSION) return null
            val sampleRate = input.readInt()
            val durationMs = input.readLong()
            val bpm = input.readFloat()
            val beatConfidence = input.readFloat()
            val count = input.readInt().coerceIn(0, 500_000)
            val events = List(count) {
                val timestamp = input.readLong()
                val type = TransientEventType.entries.getOrElse(input.readInt()) { TransientEventType.PERCUSSIVE_OTHER }
                val layer = TransientLayer.entries.getOrElse(input.readInt()) { TransientLayer.ACCENT }
                val intensity = input.readFloat()
                val sharpness = input.readFloat()
                val duration = input.readLong().coerceIn(1L, 4_000L)
                val isMajor = input.readBoolean()
                TransientEvent(timestamp, type, layer, intensity, sharpness, duration, isMajor)
            }
            TransientAnalysis(version, sampleRate, durationMs, bpm, beatConfidence, events)
        }
    } catch (_: Exception) {
        null
    }
}
