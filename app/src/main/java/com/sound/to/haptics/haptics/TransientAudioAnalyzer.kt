package com.sound.to.haptics.haptics

import java.io.File

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

        val scenePlan = TransientScenePlanner.plan(frames)
        val candidates = TransientOnsetDetector.detect(frames)
        val beatEstimate = TransientBeatTracker.track(candidates.map { it.timestampMs })
        val events = TransientHapticComposer.compose(
            frames = frames,
            candidates = candidates,
            beatEstimate = beatEstimate,
            bassEmphasis = bassEmphasis,
            hapticDensity = hapticDensity,
            scenePlan = scenePlan,
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
