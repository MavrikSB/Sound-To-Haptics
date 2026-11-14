package com.sound.to.haptics.haptics

import java.io.File

/**
 * Top-level entry point for Transient Mode's analysis pipeline — the
 * "Feature Extract -> Musical Event Classifier -> Musical Ranking ->
 * Haptic Composer -> Envelope Engine" chain from the spec's signal-flow
 * diagram (section 29), minus the final Haptic Renderer stage (that's
 * [TransientHapticRenderer], which runs live during playback instead of
 * during analysis).
 *
 * This is intentionally a *separate* analyzer object from
 * [MultiLayerAudioAnalyzer]: the two pipelines answer different questions
 * (continuous bass/flow/percussion mix vs. discrete classified musical
 * events with real spectral features) and are versioned independently
 * ([TransientConfig.PROCESSING_VERSION] vs. [HapticProcessing.VERSION]) so
 * a change to one never silently invalidates the other's cache.
 */
object TransientAudioAnalyzer {

    fun analyze(
        pcmFile: File,
        sampleRate: Int,
        channelCount: Int,
        bassEmphasis: Float = 0.7f,
        hapticDensity: Float = 0.6f,
    ): TransientAnalysis? {
        val frames = TransientFeatureExtractor.extract(pcmFile, sampleRate, channelCount) ?: return null
        if (frames.isEmpty()) return null

        val candidates = TransientOnsetDetector.detect(frames)
        val beatEstimate = TransientBeatTracker.track(candidates.map { it.timestampMs })
        val events = TransientHapticComposer.compose(
            frames = frames,
            candidates = candidates,
            beatEstimate = beatEstimate,
            bassEmphasis = bassEmphasis,
            hapticDensity = hapticDensity,
        )

        val durationMs = frames.last().timestampMs + TransientConfig.HOP_MS
        val analysis = TransientAnalysis(
            version = TransientConfig.PROCESSING_VERSION,
            sampleRate = sampleRate,
            durationMs = durationMs,
            bpm = beatEstimate.bpm,
            beatConfidence = beatEstimate.confidence,
            events = events,
        )
        TransientDiagnostics.publish(analysis, frames, candidates)
        return analysis
    }
}
