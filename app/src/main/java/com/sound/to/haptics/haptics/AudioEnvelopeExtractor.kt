package com.sound.to.haptics.haptics

import java.io.File
import com.sound.to.haptics.settings.AppSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object AudioEnvelopeExtractor {

    private const val WINDOW_MS = 18L

    private const val PEAK_WEIGHT = 0.6

    private const val NOISE_FLOOR = 0.02

    private const val MIN_FELT_AMPLITUDE = 18

    private const val RESPONSE_CURVE = 0.72

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

            rawLevels[w] = rms * (1.0 - PEAK_WEIGHT) + peak * PEAK_WEIGHT
            frameIndex = endFrame
        }

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
