package com.sound.to.haptics.haptics

import java.io.BufferedInputStream
import java.io.File
import kotlin.math.sqrt

data class PcmValidationResult(
    val valid: Boolean,
    val reason: String,
    val frames: Long,
    val durationMs: Long,
    val rms: Float,
    val peak: Float,
    val clippedSamples: Long,
)

object PcmValidator {
    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val SIGNAL_RMS = 0.0005
    private const val SIGNAL_PEAK = 0.002

    fun validate(
        file: File,
        sampleRate: Int,
        channelCount: Int,
        minDurationMs: Long = 1_000L,
    ): PcmValidationResult {
        if (!file.exists() || file.length() < 4L) {
            return invalid("PCM file is missing or empty")
        }
        if (sampleRate <= 0 || channelCount <= 0) {
            return invalid("PCM format is invalid: ${sampleRate}Hz/${channelCount}ch")
        }
        if (file.length() % 2L != 0L) {
            return invalid("PCM file ends on a partial 16-bit sample")
        }

        var samples = 0L
        var sumSquares = 0.0
        var peak = 0.0
        var clipped = 0L
        try {
            BufferedInputStream(file.inputStream(), READ_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var pendingLowByte = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    var index = 0
                    if (pendingLowByte >= 0) {
                        val value = ((pendingLowByte and 0xff) or (buffer[0].toInt() shl 8)).toShort().toInt()
                        val normalized = value / 32768.0
                        sumSquares += normalized * normalized
                        peak = maxOf(peak, kotlin.math.abs(normalized))
                        if (value == Short.MIN_VALUE.toInt() || value == Short.MAX_VALUE.toInt()) clipped++
                        samples++
                        pendingLowByte = -1
                        index = 1
                    }
                    while (index + 1 < count) {
                        val value = ((buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)).toShort().toInt()
                        val normalized = value / 32768.0
                        sumSquares += normalized * normalized
                        peak = maxOf(peak, kotlin.math.abs(normalized))
                        if (value == Short.MIN_VALUE.toInt() || value == Short.MAX_VALUE.toInt()) clipped++
                        samples++
                        index += 2
                    }
                    if (index < count) pendingLowByte = buffer[index].toInt()
                }
            }
        } catch (e: Exception) {
            return invalid("PCM read failed: ${e.message ?: e.javaClass.simpleName}")
        }

        val frames = samples / channelCount
        val durationMs = frames * 1_000L / sampleRate
        val rms = if (samples > 0) sqrt(sumSquares / samples).toFloat() else 0f
        val peakFloat = peak.toFloat()
        val expectedSamples = sampleRate.toLong() * channelCount * minDurationMs / 1_000L
        return when {
            samples < expectedSamples -> PcmValidationResult(
                valid = false,
                reason = "PCM contains only ${durationMs}ms; at least ${minDurationMs}ms is required",
                frames = frames,
                durationMs = durationMs,
                rms = rms,
                peak = peakFloat,
                clippedSamples = clipped,
            )
            rms < SIGNAL_RMS || peak < SIGNAL_PEAK -> PcmValidationResult(
                valid = false,
                reason = "PCM contains no usable playback signal (RMS=${"%.5f".format(rms)}, peak=${"%.5f".format(peakFloat)})",
                frames = frames,
                durationMs = durationMs,
                rms = rms,
                peak = peakFloat,
                clippedSamples = clipped,
            )
            else -> PcmValidationResult(
                valid = true,
                reason = "PCM validated",
                frames = frames,
                durationMs = durationMs,
                rms = rms,
                peak = peakFloat,
                clippedSamples = clipped,
            )
        }
    }

    private fun invalid(reason: String) = PcmValidationResult(
        valid = false,
        reason = reason,
        frames = 0L,
        durationMs = 0L,
        rms = 0f,
        peak = 0f,
        clippedSamples = 0L,
    )
}
