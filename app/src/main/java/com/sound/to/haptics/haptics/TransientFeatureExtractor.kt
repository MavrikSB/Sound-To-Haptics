package com.sound.to.haptics.haptics

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RawSpectralFrame(
    val timestampMs: Long,
    val rms: Float,
    val subBass: Float,
    val lowMid: Float,
    val mid: Float,
    val highMid: Float,
    val high: Float,
    val flux: Float,
    val centroidHz: Float,
)

object TransientFeatureExtractor {

    private const val MIN_BYTES = 4096L
    private const val MAX_FRAMES = 1_500_000

    fun extract(pcmFile: File, sampleRate: Int, channelCount: Int): List<RawSpectralFrame>? {
        if (!pcmFile.exists() || pcmFile.length() < MIN_BYTES || sampleRate <= 0 || channelCount <= 0) return null

        val fftSize = TransientConfig.FFT_SIZE
        val hopSamples = (sampleRate * TransientConfig.HOP_MS / 1000L).toInt().coerceAtLeast(1)
        val bytesPerInputFrame = channelCount * 2

        val hannWindow = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
        }
        val slidingBuffer = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val magnitude = FloatArray(fftSize / 2)
        val previousMagnitude = FloatArray(fftSize / 2)

        val binHz = sampleRate.toFloat() / fftSize
        fun binFor(hz: Float): Int = (hz / binHz).toInt().coerceIn(0, fftSize / 2 - 1)
        val subBassRange = binFor(TransientConfig.SUB_BASS_LOW_HZ)..binFor(TransientConfig.SUB_BASS_HIGH_HZ)
        val lowMidRange = binFor(TransientConfig.LOW_MID_LOW_HZ)..binFor(TransientConfig.LOW_MID_HIGH_HZ)
        val midRange = binFor(TransientConfig.MID_LOW_HZ)..binFor(TransientConfig.MID_HIGH_HZ)
        val highMidRange = binFor(TransientConfig.HIGH_MID_LOW_HZ)..binFor(TransientConfig.HIGH_MID_HIGH_HZ)
        val highRange = binFor(TransientConfig.HIGH_LOW_HZ)..binFor(TransientConfig.HIGH_HIGH_HZ)

        val readBuffer = ByteArray(hopSamples * bytesPerInputFrame)
        val frames = ArrayList<RawSpectralFrame>()

        try {
            BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { input ->
                var filledSamples = 0
                var frameTimestampMs = 0L
                while (frames.size < MAX_FRAMES) {
                    val bytesRead = readFully(input, readBuffer)
                    if (bytesRead <= 0) break
                    val hopFrameCount = bytesRead / bytesPerInputFrame
                    if (hopFrameCount <= 0) break

                    if (filledSamples >= fftSize) {
                        System.arraycopy(slidingBuffer, min(hopSamples, fftSize), slidingBuffer, 0, fftSize - hopSamples)
                    }
                    val writeStart = if (filledSamples >= fftSize) fftSize - hopSamples else filledSamples
                    var sampleIndex = 0
                    while (sampleIndex < hopFrameCount) {
                        val byteOffset = sampleIndex * bytesPerInputFrame
                        var left = readPcm16(readBuffer, byteOffset)
                        var right = left
                        if (channelCount > 1) right = readPcm16(readBuffer, byteOffset + 2)
                        val centre = ((left + right) * 0.5f / 32768f).coerceIn(-1f, 1f)
                        val writeIndex = writeStart + sampleIndex
                        if (writeIndex < fftSize) slidingBuffer[writeIndex] = centre
                        sampleIndex++
                    }
                    filledSamples = min(filledSamples + hopFrameCount, fftSize)

                    if (filledSamples >= fftSize) {

                        for (i in 0 until fftSize) {
                            real[i] = slidingBuffer[i] * hannWindow[i]
                            imag[i] = 0f
                        }
                        fft(real, imag)
                        var sumSquares = 0.0
                        for (i in 0 until fftSize / 2) {
                            val re = real[i]
                            val im = imag[i]
                            val mag = sqrt((re * re + im * im).toDouble()).toFloat()
                            magnitude[i] = mag
                            sumSquares += re * re + im * im
                        }
                        val rms = sqrt((sumSquares / (fftSize / 2)).coerceAtLeast(0.0)).toFloat()

                        val subBass = bandEnergy(magnitude, subBassRange)
                        val lowMid = bandEnergy(magnitude, lowMidRange)
                        val mid = bandEnergy(magnitude, midRange)
                        val highMid = bandEnergy(magnitude, highMidRange)
                        val high = bandEnergy(magnitude, highRange)

                        var fluxSum = 0f
                        var weightedFreqSum = 0.0
                        var magnitudeSum = 0.0
                        for (i in 0 until fftSize / 2) {
                            val diff = magnitude[i] - previousMagnitude[i]
                            if (diff > 0f) fluxSum += diff
                            val freq = i * binHz
                            weightedFreqSum += freq * magnitude[i]
                            magnitudeSum += magnitude[i]
                        }
                        val centroidHz = if (magnitudeSum > 1e-6) (weightedFreqSum / magnitudeSum).toFloat() else 0f
                        System.arraycopy(magnitude, 0, previousMagnitude, 0, magnitude.size)

                        frames += RawSpectralFrame(
                            timestampMs = frameTimestampMs,
                            rms = rms,
                            subBass = subBass,
                            lowMid = lowMid,
                            mid = mid,
                            highMid = highMid,
                            high = high,
                            flux = fluxSum / (fftSize / 2),
                            centroidHz = centroidHz,
                        )
                    }
                    frameTimestampMs += TransientConfig.HOP_MS
                }
            }
        } catch (_: Exception) {
            return if (frames.isEmpty()) null else frames
        }
        return if (frames.isEmpty()) null else frames
    }

    private fun bandEnergy(magnitude: FloatArray, range: IntRange): Float {
        var sum = 0.0
        var count = 0
        for (i in range) {
            if (i in magnitude.indices) {
                sum += magnitude[i] * magnitude[i]
                count++
            }
        }
        return if (count > 0) sqrt(sum / count).toFloat() else 0f
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) break
            offset += count
        }
        return offset
    }

    private fun readPcm16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        val raw = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        return raw.toShort().toInt()
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (bit and j != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wReal = cos(angle).toFloat()
            val wImag = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until length / 2) {
                    val evenIndex = i + k
                    val oddIndex = i + k + length / 2
                    val evenReal = real[evenIndex]
                    val evenImag = imag[evenIndex]
                    val oddReal = real[oddIndex] * curReal - imag[oddIndex] * curImag
                    val oddImag = real[oddIndex] * curImag + imag[oddIndex] * curReal
                    real[evenIndex] = evenReal + oddReal
                    imag[evenIndex] = evenImag + oddImag
                    real[oddIndex] = evenReal - oddReal
                    imag[oddIndex] = evenImag - oddImag
                    val nextReal = curReal * wReal - curImag * wImag
                    val nextImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                    curImag = nextImag
                }
                i += length
            }
            length = length shl 1
        }
    }
}
