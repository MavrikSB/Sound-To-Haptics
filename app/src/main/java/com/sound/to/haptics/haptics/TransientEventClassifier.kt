package com.sound.to.haptics.haptics

import kotlin.math.max
import kotlin.math.min

object TransientEventClassifier {

    fun classify(candidate: RawOnsetCandidate): TransientEventType {
        val frame = candidate.frame
        val totalEnergy = (frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high).coerceAtLeast(1e-5f)
        val lowBandShare = (frame.subBass + frame.lowMid) / totalEnergy
        val centroid = frame.centroidHz

        return when {

            lowBandShare >= TransientConfig.KICK_MIN_LOW_BAND_DOMINANCE &&
                centroid <= TransientConfig.KICK_MAX_CENTROID_HZ -> TransientEventType.KICK

            centroid >= TransientConfig.HIHAT_MIN_CENTROID_HZ &&
                lowBandShare <= TransientConfig.HIHAT_MAX_LOW_BAND_SHARE -> TransientEventType.HIHAT

            centroid in TransientConfig.SNARE_MIN_CENTROID_HZ..TransientConfig.SNARE_MAX_CENTROID_HZ &&
                normalizedFlux(frame) >= TransientConfig.SNARE_MIN_FLUX_SHARPNESS -> TransientEventType.SNARE

            else -> TransientEventType.PERCUSSIVE_OTHER
        }
    }

    fun isBassSustainFrame(frame: RawSpectralFrame): Boolean {
        val lowEnergy = frame.subBass + frame.lowMid
        return lowEnergy >= TransientConfig.BASS_SUSTAIN_MIN_LOW_ENERGY &&
            normalizedFlux(frame) <= TransientConfig.BASS_SUSTAIN_MAX_FLUX
    }

    private fun normalizedFlux(frame: RawSpectralFrame): Float {
        val totalEnergy = (frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high).coerceAtLeast(1e-5f)
        return (frame.flux / totalEnergy).coerceIn(0f, 4f) / 4f
    }
}
