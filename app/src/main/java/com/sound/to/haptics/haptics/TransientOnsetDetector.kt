package com.sound.to.haptics.haptics

import kotlin.math.max
import kotlin.math.min

data class RawOnsetCandidate(
    val frameIndex: Int,
    val timestampMs: Long,
    val onsetScore: Float,
    val frame: RawSpectralFrame,

    val localProminence: Float,
)

object TransientOnsetDetector {

    fun detect(frames: List<RawSpectralFrame>): List<RawOnsetCandidate> {
        if (frames.isEmpty()) return emptyList()
        val n = frames.size
        val windowSize = TransientConfig.LOCAL_STATS_WINDOW_FRAMES

        val energyRing = FloatArray(windowSize)
        val fluxRing = FloatArray(windowSize)
        var energySum = 0f
        var fluxSum = 0f
        var ringFill = 0

        var loudReference = 0f

        val candidates = ArrayList<RawOnsetCandidate>()
        var lastAcceptedFrame = -10_000
        val refractoryFrames = (TransientConfig.ONSET_REFRACTORY_MS / TransientConfig.HOP_MS).toInt().coerceAtLeast(1)

        for (i in 0 until n) {
            val frame = frames[i]
            val totalBandEnergy = frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high

            val localMeanEnergy = if (ringFill > 0) energySum / ringFill else frame.rms
            val localMeanFlux = if (ringFill > 0) fluxSum / ringFill else frame.flux

            loudReference = if (frame.rms > loudReference) {
                loudReference * 0.7f + frame.rms * 0.3f
            } else {
                loudReference * 0.995f + frame.rms * 0.005f
            }

            val fluxNorm = safeDiv(frame.flux, max(localMeanFlux * TransientConfig.ONSET_ADAPTIVE_RATIO, 1e-4f))
            val energyRise = safeDiv(max(0f, frame.rms - localMeanEnergy), max(localMeanEnergy, 1e-4f))
            val bandEnergyNorm = safeDiv(totalBandEnergy, max(localMeanEnergy * 3f, 1e-4f))
            val localContrast = safeDiv(frame.rms, max(loudReference, 1e-4f))

            val onsetScore = (
                TransientConfig.ONSET_WEIGHT_FLUX * fluxNorm.coerceIn(0f, 3f) +
                    TransientConfig.ONSET_WEIGHT_ENERGY_RISE * energyRise.coerceIn(0f, 3f) +
                    TransientConfig.ONSET_WEIGHT_BAND_ENERGY * bandEnergyNorm.coerceIn(0f, 3f) +
                    TransientConfig.ONSET_WEIGHT_LOCAL_CONTRAST * localContrast.coerceIn(0f, 1.5f)
                ) / (
                TransientConfig.ONSET_WEIGHT_FLUX + TransientConfig.ONSET_WEIGHT_ENERGY_RISE +
                    TransientConfig.ONSET_WEIGHT_BAND_ENERGY + TransientConfig.ONSET_WEIGHT_LOCAL_CONTRAST
                )

            val clearsAdaptiveBar = frame.rms > localMeanEnergy * TransientConfig.ONSET_ADAPTIVE_RATIO ||
                frame.flux > localMeanFlux * TransientConfig.ONSET_ADAPTIVE_RATIO
            val clearsAbsoluteFloor = frame.rms > TransientConfig.ONSET_MIN_ABSOLUTE_ENERGY
            val clearsRefractory = i - lastAcceptedFrame >= refractoryFrames

            val clearsProminence = onsetScore > 0.34f

            if (clearsAdaptiveBar && clearsAbsoluteFloor && clearsRefractory && clearsProminence) {
                val prominence = safeDiv(frame.rms, max(localMeanEnergy, 1e-4f))
                candidates += RawOnsetCandidate(
                    frameIndex = i,
                    timestampMs = frame.timestampMs,
                    onsetScore = onsetScore.coerceIn(0f, 1.5f),
                    frame = frame,
                    localProminence = prominence,
                )
                lastAcceptedFrame = i
            }

            val ringIndex = i % windowSize
            if (ringFill >= windowSize) {
                energySum -= energyRing[ringIndex]
                fluxSum -= fluxRing[ringIndex]
            } else {
                ringFill++
            }
            energyRing[ringIndex] = frame.rms
            fluxRing[ringIndex] = frame.flux
            energySum += frame.rms
            fluxSum += frame.flux
        }
        return candidates
    }

    private fun safeDiv(a: Float, b: Float): Float = if (b <= 1e-6f) 0f else a / b
}
