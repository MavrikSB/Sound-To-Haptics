package com.sound.to.haptics.haptics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransientDiagnosticsState(
    val bpm: Float = 0f,
    val beatConfidence: Float = 0f,
    val rms: List<Float> = emptyList(),
    val bass: List<Float> = emptyList(),
    val mid: List<Float> = emptyList(),
    val high: List<Float> = emptyList(),
    val onsetScore: List<Float> = emptyList(),
    val kickMarkers: List<Boolean> = emptyList(),
    val snareMarkers: List<Boolean> = emptyList(),
    val hihatMarkers: List<Boolean> = emptyList(),
    val hapticIntensityEnvelope: List<Float> = emptyList(),
    val eventCount: Int = 0,
    val accentCount: Int = 0,
    val textureCount: Int = 0,
    val bedCount: Int = 0,
    val majorAccentCount: Int = 0,
    val analysisLatencyMs: Long = 0L,

    val audioHapticOffsetMs: Long = 0L,

    val lastFiredType: TransientEventType? = null,
    val lastFiredIntensity: Float = 0f,
    val lastFiredLayer: TransientLayer? = null,
    val liveEventDensityPerSecond: Float = 0f,
)

object TransientDiagnostics {
    private val _state = MutableStateFlow(TransientDiagnosticsState())
    val state: StateFlow<TransientDiagnosticsState> = _state.asStateFlow()

    fun publish(analysis: TransientAnalysis, frames: List<RawSpectralFrame>, candidates: List<RawOnsetCandidate>, analysisLatencyMs: Long = 0L) {
        val count = 64
        fun <T> sample(values: List<T>, fallback: T): List<T> {
            if (values.isEmpty()) return emptyList()
            return List(count) { index -> values[(index * values.size / count).coerceAtMost(values.lastIndex)] }
        }

        val onsetScoreSeries = FloatArray(frames.size)
        for (c in candidates) if (c.frameIndex in onsetScoreSeries.indices) onsetScoreSeries[c.frameIndex] = c.onsetScore

        fun markerSeries(type: TransientEventType): List<Boolean> {
            val marker = BooleanArray(frames.size)
            for (event in analysis.events) {
                if (event.type != type) continue
                val idx = (event.timestampMs / TransientConfig.HOP_MS).toInt()
                if (idx in marker.indices) marker[idx] = true
            }
            return sample(marker.toList(), false)
        }

        val intensityEnvelope = FloatArray(frames.size)
        for (event in analysis.events) {
            val idx = (event.timestampMs / TransientConfig.HOP_MS).toInt()
            if (idx in intensityEnvelope.indices) intensityEnvelope[idx] = maxOf(intensityEnvelope[idx], event.intensity)
        }

        _state.value = TransientDiagnosticsState(
            bpm = analysis.bpm,
            beatConfidence = analysis.beatConfidence,
            rms = sample(frames.map { it.rms }, 0f),
            bass = sample(frames.map { it.subBass + it.lowMid }, 0f),
            mid = sample(frames.map { it.mid }, 0f),
            high = sample(frames.map { it.highMid + it.high }, 0f),
            onsetScore = sample(onsetScoreSeries.toList(), 0f),
            kickMarkers = markerSeries(TransientEventType.KICK),
            snareMarkers = markerSeries(TransientEventType.SNARE),
            hihatMarkers = markerSeries(TransientEventType.HIHAT),
            hapticIntensityEnvelope = sample(intensityEnvelope.toList(), 0f),
            eventCount = analysis.events.size,
            accentCount = analysis.events.count { it.layer == TransientLayer.ACCENT },
            textureCount = analysis.events.count { it.layer == TransientLayer.TEXTURE },
            bedCount = analysis.events.count { it.layer == TransientLayer.BED },
            majorAccentCount = analysis.events.count { it.isMajorAccent },
            analysisLatencyMs = analysisLatencyMs,
        )
    }

    fun reportFired(type: TransientEventType, layer: TransientLayer, intensity: Float, audioHapticOffsetMs: Long, eventsInLastSecond: Int) {
        _state.value = _state.value.copy(
            lastFiredType = type,
            lastFiredLayer = layer,
            lastFiredIntensity = intensity,
            audioHapticOffsetMs = audioHapticOffsetMs,
            liveEventDensityPerSecond = eventsInLastSecond.toFloat(),
        )
    }

    fun clearLive() {
        _state.value = _state.value.copy(lastFiredType = null, lastFiredLayer = null, lastFiredIntensity = 0f)
    }
}
