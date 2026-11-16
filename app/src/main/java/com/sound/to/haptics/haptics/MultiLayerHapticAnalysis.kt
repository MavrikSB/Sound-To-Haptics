package com.sound.to.haptics.haptics

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object HapticProcessing {

    const val VERSION = 3
    const val FRAME_MS = 20L
    const val MAX_WAVEFORM_STEPS = 6_000
}

enum class HapticEventKind {
    BASS,
    PERCUSSIVE,
    MERGED,
}

data class LayerFrame(
    val timestampMs: Long,
    val bass: Float,
    val vocalFlow: Float,
    val percussion: Float,
)

data class LayeredHapticAnalysis(
    val version: Int,
    val sampleRate: Int,
    val durationMs: Long,
    val bassEmphasis: Float,
    val density: Float,
    val frames: List<LayerFrame>,
    val events: List<OnsetEvent>,
) {
    fun toEnvelope(): HapticEnvelope {

        val groupSize = ((frames.size + HapticProcessing.MAX_WAVEFORM_STEPS - 1) /
            HapticProcessing.MAX_WAVEFORM_STEPS).coerceAtLeast(1)
        val outputSize = (frames.size + groupSize - 1) / groupSize
        val timings = LongArray(outputSize)
        val amplitudes = IntArray(outputSize)
        var eventIndex = 0
        for (outputIndex in 0 until outputSize) {
            val start = outputIndex * groupSize
            val end = (start + groupSize).coerceAtMost(frames.size)
            var peakLevel = 0f
            for (index in start until end) {
                val frame = frames[index]
                while (eventIndex < events.size && events[eventIndex].timestampMs < frame.timestampMs) eventIndex++
                var eventBoost = 0f
                var probe = eventIndex
                while (probe < events.size && events[probe].timestampMs <= frame.timestampMs + HapticProcessing.FRAME_MS) {
                    eventBoost = max(eventBoost, events[probe].strength)
                    probe++
                }
                val level = (
                    frame.vocalFlow * 0.28f +
                        frame.bass * 0.34f +
                        frame.percussion * 0.12f +
                        eventBoost * 0.58f
                    ).coerceIn(0f, 1f)
                peakLevel = max(peakLevel, level)
            }
            timings[outputIndex] = (end - start).toLong() * HapticProcessing.FRAME_MS
            amplitudes[outputIndex] = if (peakLevel < 0.035f) 0
            else (peakLevel.pow(0.78f) * 255f).toInt().coerceIn(0, 255)
        }
        return HapticEnvelope(timings, amplitudes)
    }

    val bassPeak: Float get() = frames.maxOfOrNull { it.bass } ?: 0f
    val vocalFlowPeak: Float get() = frames.maxOfOrNull { it.vocalFlow } ?: 0f
    val percussionPeak: Float get() = frames.maxOfOrNull { it.percussion } ?: 0f
}

data class AnalysisDiagnosticsState(
    val raw: List<Float> = emptyList(),
    val bass: List<Float> = emptyList(),
    val vocalFlow: List<Float> = emptyList(),
    val percussion: List<Float> = emptyList(),
    val mergedEvents: List<Boolean> = emptyList(),
    val eventCount: Int = 0,
)

object AnalysisDiagnostics {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AnalysisDiagnosticsState())
    val state: kotlinx.coroutines.flow.StateFlow<AnalysisDiagnosticsState> = _state.asStateFlow()

    fun publish(analysis: LayeredHapticAnalysis) {
        val count = 64
        fun sample(values: List<Float>): List<Float> {
            if (values.isEmpty()) return emptyList()
            return List(count) { index -> values[(index * values.size / count).coerceAtMost(values.lastIndex)] }
        }
        val frameValues = analysis.frames.map { (it.bass + it.vocalFlow + it.percussion) / 3f }
        val eventTimes = analysis.events.map { it.timestampMs }
        _state.value = AnalysisDiagnosticsState(
            raw = sample(frameValues),
            bass = sample(analysis.frames.map { it.bass }),
            vocalFlow = sample(analysis.frames.map { it.vocalFlow }),
            percussion = sample(analysis.frames.map { it.percussion }),
            mergedEvents = List(count) { index ->
                val start = index * analysis.durationMs / count
                eventTimes.any { it in start..(start + HapticProcessing.FRAME_MS * 2) }
            },
            eventCount = analysis.events.size,
        )
    }
}

object MultiLayerAudioAnalyzer {
    private const val MIN_BYTES = 4096L
    private const val MAX_FRAMES = 1_500_000

    private data class RawFrame(
        val bass: Float,
        val vocal: Float,
        val high: Float,
        val flux: Float,
    )

    fun analyze(
        pcmFile: File,
        sampleRate: Int,
        channelCount: Int,
        bassEmphasis: Float = 0.7f,
        density: Float = 0.6f,
    ): LayeredHapticAnalysis? {
        if (!pcmFile.exists() || pcmFile.length() < MIN_BYTES || sampleRate <= 0 || channelCount <= 0) return null
        val samplesPerFrame = (sampleRate * HapticProcessing.FRAME_MS / 1000L).toInt().coerceAtLeast(1)
        val bytesPerFrame = channelCount * 2
        val buffer = ByteArray(samplesPerFrame * bytesPerFrame)
        val raw = ArrayList<RawFrame>()
        val bassAlpha = (2.0 * Math.PI * 150.0 / sampleRate).coerceIn(0.01, 1.0)
        val lowMidAlpha = (2.0 * Math.PI * 260.0 / sampleRate).coerceIn(0.01, 1.0)
        val highMidAlpha = (2.0 * Math.PI * 3000.0 / sampleRate).coerceIn(0.01, 1.0)
        val percussionAlpha = (2.0 * Math.PI * 1800.0 / sampleRate).coerceIn(0.01, 1.0)
        var bassState = 0.0
        var lowMidState = 0.0
        var highMidState = 0.0
        var percussionState = 0.0
        var previousEnergy = 0.0

        try {
            BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { input ->
                while (raw.size < MAX_FRAMES) {
                    val bytesRead = readWindow(input, buffer)
                    if (bytesRead <= 0) break
                    val samples = bytesRead / bytesPerFrame
                    if (samples <= 0) break
                    var bassEnergy = 0.0
                    var vocalEnergy = 0.0
                    var highEnergy = 0.0
                    var positiveFlux = 0.0
                    var totalEnergy = 0.0
                    var previousAbs = 0.0
                    var sampleIndex = 0
                    while (sampleIndex < samples) {
                        var left = readPcm16(buffer, sampleIndex * bytesPerFrame)
                        var right = left
                        if (channelCount > 1) right = readPcm16(buffer, sampleIndex * bytesPerFrame + 2)
                        val centre = ((left + right) * 0.5).coerceIn(-32768.0, 32767.0) / 32768.0
                        val x = centre
                        bassState += bassAlpha * (x - bassState)
                        lowMidState += lowMidAlpha * (x - lowMidState)
                        highMidState += highMidAlpha * (x - highMidState)
                        percussionState += percussionAlpha * (x - percussionState)
                        val bassSample = bassState
                        val vocalSample = highMidState - lowMidState
                        val highSample = x - percussionState
                        bassEnergy += bassSample * bassSample
                        vocalEnergy += vocalSample * vocalSample
                        highEnergy += highSample * highSample
                        val absSample = abs(x)
                        positiveFlux += (absSample - previousAbs).coerceAtLeast(0.0)
                        previousAbs = absSample
                        totalEnergy += x * x
                        sampleIndex++
                    }
                    val count = samples.toDouble().coerceAtLeast(1.0)
                    val rms = sqrt(totalEnergy / count)
                    val bass = sqrt(bassEnergy / count)
                    val vocal = sqrt(vocalEnergy / count)
                    val high = sqrt(highEnergy / count)
                    val flux = (positiveFlux / count) + max(0.0, rms - previousEnergy) * 0.75
                    previousEnergy = rms
                    raw += RawFrame(bass.toFloat(), vocal.toFloat(), high.toFloat(), flux.toFloat())
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (raw.isEmpty()) return null

        val bassScale = percentile(raw.map { it.bass }, 0.90f).coerceAtLeast(0.001f)
        val vocalScale = percentile(raw.map { it.vocal }, 0.90f).coerceAtLeast(0.001f)
        val highScale = percentile(raw.map { it.high }, 0.90f).coerceAtLeast(0.001f)
        val fluxScale = percentile(raw.map { it.flux }, 0.90f).coerceAtLeast(0.001f)
        val fluxFloor = percentile(raw.map { it.flux }, 0.50f)

        val bass = FloatArray(raw.size)
        val flow = FloatArray(raw.size)
        val percussion = FloatArray(raw.size)
        var flowState = 0f
        for (i in raw.indices) {
            val frame = raw[i]
            bass[i] = (normalise(frame.bass, bassScale) * (0.8f + bassEmphasis.coerceIn(0f, 1f) * 0.4f)).coerceIn(0f, 1f)
            val mid = normalise(frame.vocal, vocalScale)

            flowState = if (mid > flowState) flowState * 0.58f + mid * 0.42f else flowState * 0.88f + mid * 0.12f
            flow[i] = (flowState * (0.78f + (1f - normalise(frame.high, highScale)) * 0.22f)).coerceIn(0f, 1f)
            val flux = normalise(frame.flux, fluxScale)
            val high = normalise(frame.high, highScale)
            percussion[i] = ((flux * 0.68f + high * 0.32f) * (0.78f + density.coerceIn(0f, 1f) * 0.44f)).coerceIn(0f, 1f)
        }

        val candidates = ArrayList<OnsetEvent>()
        var lastBass = -10_000
        var lastPercussion = -10_000
        for (i in raw.indices) {
            val bassBaseline = movingAverage(bass, i, 12)
            val percussionBaseline = movingAverage(percussion, i, 8)
            val bassRise = bass[i] - if (i == 0) 0f else bass[i - 1]
            val bassHit = i - lastBass >= 8 && bass[i] > 0.52f && bass[i] > bassBaseline * 1.30f && bassRise > 0.035f
            val percussionHit = i - lastPercussion >= 4 && percussion[i] > 0.46f && percussion[i] > percussionBaseline * 1.28f && raw[i].flux > fluxFloor
            if (bassHit) {
                candidates += OnsetEvent(
                    timestampMs = i * HapticProcessing.FRAME_MS,
                    strength = (0.62f + bass[i] * 0.38f).coerceIn(0f, 1f),
                    kind = HapticEventKind.BASS,
                    durationMs = 105L,
                )
                lastBass = i
            }
            if (percussionHit) {
                candidates += OnsetEvent(
                    timestampMs = i * HapticProcessing.FRAME_MS,
                    strength = (0.52f + percussion[i] * 0.48f).coerceIn(0f, 1f),
                    kind = HapticEventKind.PERCUSSIVE,
                    durationMs = 48L,
                )
                lastPercussion = i
            }
        }

        val events = mergeEvents(candidates)
        val frames = raw.indices.map { i ->
            LayerFrame(
                timestampMs = i * HapticProcessing.FRAME_MS,
                bass = bass[i],
                vocalFlow = flow[i],
                percussion = percussion[i],
            )
        }
        val analysis = LayeredHapticAnalysis(
            version = HapticProcessing.VERSION,
            sampleRate = sampleRate,
            durationMs = frames.size * HapticProcessing.FRAME_MS,
            bassEmphasis = bassEmphasis.coerceIn(0f, 1f),
            density = density.coerceIn(0f, 1f),
            frames = frames,
            events = events,
        )
        AnalysisDiagnostics.publish(analysis)
        return analysis
    }

    private fun mergeEvents(candidates: List<OnsetEvent>): List<OnsetEvent> {
        val merged = ArrayList<OnsetEvent>()
        for (candidate in candidates.sortedBy { it.timestampMs }) {
            val previous = merged.lastOrNull()
            if (previous != null && candidate.timestampMs - previous.timestampMs <= 45L) {
                val kind = if (previous.kind != candidate.kind) HapticEventKind.MERGED else previous.kind
                val index = merged.lastIndex
                merged[index] = previous.copy(
                    strength = max(previous.strength, candidate.strength),
                    kind = kind,
                    durationMs = max(previous.durationMs, candidate.durationMs),
                )
            } else {
                merged += candidate
            }
        }
        return merged
    }

    private fun readWindow(input: BufferedInputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) break
            offset += count
        }
        return offset
    }

    private fun readPcm16(bytes: ByteArray, offset: Int): Double {
        if (offset + 1 >= bytes.size) return 0.0
        val raw = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        return raw.toShort().toDouble()
    }

    private fun normalise(value: Float, scale: Float): Float = (value / scale).coerceIn(0f, 1f)

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.lastIndex * fraction.coerceIn(0f, 1f))).toInt()]
    }

    private fun movingAverage(values: FloatArray, index: Int, lookback: Int): Float {
        val start = (index - lookback).coerceAtLeast(0)
        var sum = 0f
        var count = 0
        for (i in start until index) {
            sum += values[i]
            count++
        }
        return if (count == 0) values[index] * 0.55f else sum / count
    }
}

private const val LAYER_MAGIC = 0x53484C32

fun LayeredHapticAnalysis.saveTo(file: File) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(LAYER_MAGIC)
        out.writeInt(version)
        out.writeInt(sampleRate)
        out.writeLong(durationMs)
        out.writeFloat(bassEmphasis)
        out.writeFloat(density)
        out.writeInt(frames.size)
        frames.forEach { frame ->
            out.writeLong(frame.timestampMs)
            out.writeFloat(frame.bass)
            out.writeFloat(frame.vocalFlow)
            out.writeFloat(frame.percussion)
        }
        out.writeInt(events.size)
        events.forEach { event ->
            out.writeLong(event.timestampMs)
            out.writeFloat(event.strength)
            out.writeInt(event.kind.ordinal)
            out.writeLong(event.durationMs)
        }
    }
}

fun loadLayeredHapticAnalysis(file: File): LayeredHapticAnalysis? {
    if (!file.exists() || file.length() < 32L) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != LAYER_MAGIC) return null
            val version = input.readInt()
            if (version != HapticProcessing.VERSION) return null
            val sampleRate = input.readInt()
            val durationMs = input.readLong()
            val bassEmphasis = input.readFloat()
            val density = input.readFloat()
            val frameCount = input.readInt().coerceIn(0, 1_500_000)
            val frames = List(frameCount) {
                LayerFrame(input.readLong(), input.readFloat(), input.readFloat(), input.readFloat())
            }
            val eventCount = input.readInt().coerceIn(0, 500_000)
            val events = List(eventCount) {
                val timestamp = input.readLong()
                val strength = input.readFloat()
                val kind = HapticEventKind.entries.getOrElse(input.readInt()) { HapticEventKind.PERCUSSIVE }
                val duration = input.readLong().coerceIn(1L, 2_000L)
                OnsetEvent(timestamp, strength, kind, duration)
            }
            LayeredHapticAnalysis(version, sampleRate, durationMs, bassEmphasis, density, frames, events)
        }
    } catch (_: Exception) {
        null
    }
}
