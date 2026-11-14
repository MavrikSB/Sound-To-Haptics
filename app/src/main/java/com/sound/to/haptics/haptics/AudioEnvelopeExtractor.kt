package com.sound.to.haptics.haptics

import java.io.File
import com.sound.to.haptics.settings.AppSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Legacy PCM-to-waveform extractor retained for compatibility with older
 * diagnostic/serialized envelope data. The active Offline and BitChord paths
 * do not use it: both now preserve and play the haptic-enabled OGG produced
 * by FFmpeg. In particular, a failed OGG playback never falls back to this
 * direct-vibrator representation.
 *
 * If a compatibility caller does request an envelope, this keeps the
 * dynamic-range improvements below rather than flattening the signal into
 * a constant pulse.
 */
object AudioEnvelopeExtractor {

    // Shorter than before (was 40ms) so a kick/snare hit gets its own
    // window instead of being averaged into neighboring material —
    // this is the single biggest factor in whether a pattern reads as
    // "textured" versus "smoothed into a pulse."
    private const val WINDOW_MS = 18L

    // True peak-hold per window (not just RMS) so transients register at
    // full strength — RMS alone rounds off exactly the sharp attacks that
    // make percussion feel percussive.
    private const val PEAK_WEIGHT = 0.6

    // Below this, a window is genuinely silent and stays at 0 rather than
    // being boosted up to a "floor" — preserving quiet-vs-loud contrast is
    // what makes a pattern feel like it's following the music instead of
    // buzzing at a constant level.
    private const val NOISE_FLOOR = 0.02

    // Once past the noise floor, the minimum amplitude a felt hit renders
    // at — below this the vibration motor doesn't reliably register on
    // most hardware, so very quiet-but-present material still needs a
    // small push, just not the aggressive floor the old version used.
    private const val MIN_FELT_AMPLITUDE = 18

    // A gentle upward curve (exponent < 1) so mid-loud material spreads
    // out across more of the 0-255 range instead of everything above
    // "quiet" clustering near the top — this is what gives quieter
    // passages room to feel different from loud ones.
    private const val RESPONSE_CURVE = 0.72

    /**
     * @param gain linear multiplier applied to loudness before mapping to 0-255.
     *   Callers that already shaped/boosted the PCM upstream (e.g. via an FFmpeg
     *   volume filter) should pass 1f here to avoid applying gain twice.
     */
    fun extract(pcmFile: File, sampleRate: Int, channelCount: Int, gain: Float): HapticEnvelope? {
        if (!pcmFile.exists() || pcmFile.length() < 4096) return null

        val bytes = pcmFile.readBytes()
        val bytesPerFrame = 2 * channelCount
        val totalFrames = bytes.size / bytesPerFrame
        if (totalFrames <= 0) return null

        val framesPerWindow = ((sampleRate * WINDOW_MS) / 1000L).toInt().coerceAtLeast(1)
        val windowCount = (totalFrames / framesPerWindow).coerceAtLeast(1)

        val timings = LongArray(windowCount) { WINDOW_MS }
        val rawLevels = DoubleArray(windowCount)

        var frameIndex = 0
        for (w in 0 until windowCount) {
            val endFrame = min(frameIndex + framesPerWindow, totalFrames)
            var sumSquares = 0.0
            var peak = 0.0
            var count = 0
            for (f in frameIndex until endFrame) {
                val byteOffset = f * bytesPerFrame
                var monoSample = 0
                for (c in 0 until channelCount) {
                    val idx = byteOffset + c * 2
                    if (idx + 1 >= bytes.size) continue
                    val raw = (bytes[idx].toInt() and 0xFF) or (bytes[idx + 1].toInt() shl 8)
                    monoSample += raw.toShort()
                }
                monoSample /= channelCount
                val normalized = monoSample / 32768.0
                val absNormalized = abs(normalized)
                sumSquares += normalized * normalized
                if (absNormalized > peak) peak = absNormalized
                count++
            }
            val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
            // Blend RMS (body/sustain) with peak (transient attack) rather
            // than picking one — pure peak alone gets spiky/noisy, pure RMS
            // alone is what produced the flat pulsing this replaces.
            rawLevels[w] = rms * (1.0 - PEAK_WEIGHT) + peak * PEAK_WEIGHT
            frameIndex = endFrame
        }

        // Fast-attack, slower-release shaping across windows: a hit reaches
        // its own level immediately (no smoothing an attack ever needs to
        // wait for), but the trailing edge decays over a couple of windows
        // instead of cutting instantly — this is what keeps short 18ms
        // windows from chattering while still preserving sharp onsets.
        // The same persisted strength slider is applied here too, so a
        // compatibility renderer cannot quietly bypass the user's setting.
        val effectiveGain = gain * AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
        var envelope = 0.0
        val amplitudes = IntArray(windowCount)
        for (w in 0 until windowCount) {
            val level = rawLevels[w]
            envelope = if (level > envelope) level else envelope * 0.82 + level * 0.18

            if (envelope < NOISE_FLOOR) {
                amplitudes[w] = 0
            } else {
                val shaped = envelope.coerceIn(0.0, 1.0).pow(RESPONSE_CURVE)
                val boosted = (shaped * effectiveGain * 255).toInt().coerceIn(0, 255)
                val feltFloor = (MIN_FELT_AMPLITUDE * AppSettings.state.value.hapticStrength).toInt()
                amplitudes[w] = if (effectiveGain <= 0f) 0 else max(boosted, feltFloor)
            }
        }

        return HapticEnvelope(timings, amplitudes)
    }
}
