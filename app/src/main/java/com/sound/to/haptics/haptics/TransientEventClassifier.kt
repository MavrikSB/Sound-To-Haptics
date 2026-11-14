package com.sound.to.haptics.haptics

import kotlin.math.max
import kotlin.math.min

/**
 * Classifies each detected onset candidate into a perceptual musical
 * category (spec section 9), using only cheap heuristics on the already-
 * computed frequency bands, spectral centroid and flux — no instrument
 * recognition model.
 *
 * Rules of thumb encoded here (documented per-branch below with the
 * reasoning, mirroring [TransientConfig]'s own inline rationale):
 *   kick        -> energy concentrated in sub/low bands, low centroid
 *   snare/clap  -> mid/high-mid energy, sharp flux, moderate-high centroid
 *   hi-hat      -> very high centroid, almost no low-band energy
 *   other       -> a real onset that doesn't cleanly fit any of the above
 */
object TransientEventClassifier {

    fun classify(candidate: RawOnsetCandidate): TransientEventType {
        val frame = candidate.frame
        val totalEnergy = (frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high).coerceAtLeast(1e-5f)
        val lowBandShare = (frame.subBass + frame.lowMid) / totalEnergy
        val centroid = frame.centroidHz

        return when {
            // Strong low-frequency dominance + a centroid still sitting in
            // the sub/bass region is the clearest kick signature: nearly
            // all of a kick's audible energy is below ~150-300Hz even
            // though its transient click can nudge the centroid slightly
            // upward, hence checking centroid rather than raw bass alone.
            lowBandShare >= TransientConfig.KICK_MIN_LOW_BAND_DOMINANCE &&
                centroid <= TransientConfig.KICK_MAX_CENTROID_HZ -> TransientEventType.KICK

            // Very bright, almost bass-free, energy skewed high: hats and
            // shakers live almost entirely above a few kHz with essentially
            // no sub-bass content.
            centroid >= TransientConfig.HIHAT_MIN_CENTROID_HZ &&
                lowBandShare <= TransientConfig.HIHAT_MAX_LOW_BAND_SHARE -> TransientEventType.HIHAT

            // Mid/high-mid weighted, sharp attack (high flux relative to the
            // onset's own energy), moderate-high centroid: the classic snare
            // "crack" band, distinct from both the kick's low weight and the
            // hi-hat's much higher centroid.
            centroid in TransientConfig.SNARE_MIN_CENTROID_HZ..TransientConfig.SNARE_MAX_CENTROID_HZ &&
                normalizedFlux(frame) >= TransientConfig.SNARE_MIN_FLUX_SHARPNESS -> TransientEventType.SNARE

            else -> TransientEventType.PERCUSSIVE_OTHER
        }
    }

    /**
     * True when [frame] looks like sustained low-frequency material (a bass
     * sustain / pad) rather than a discrete transient: persistent low-band
     * energy with low spectral flux (nothing sharply "changing" right now).
     * Used by [TransientHapticComposer] to feed Layer A (Musical Bed)
     * instead of scheduling a discrete accent — this is what keeps held
     * bass notes and pads from going completely silent (spec section 9's
     * "do not make sustained audio feel completely empty").
     */
    fun isBassSustainFrame(frame: RawSpectralFrame): Boolean {
        val lowEnergy = frame.subBass + frame.lowMid
        return lowEnergy >= TransientConfig.BASS_SUSTAIN_MIN_LOW_ENERGY &&
            normalizedFlux(frame) <= TransientConfig.BASS_SUSTAIN_MAX_FLUX
    }

    /** Flux normalized by the frame's own total band energy, so it reflects "sharpness of change" rather than absolute loudness. */
    private fun normalizedFlux(frame: RawSpectralFrame): Float {
        val totalEnergy = (frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high).coerceAtLeast(1e-5f)
        return (frame.flux / totalEnergy).coerceIn(0f, 4f) / 4f
    }
}
