package com.sound.to.haptics.haptics

object TransientConfig {

    const val FFT_SIZE = 2048

    const val HOP_MS = 20L

    const val SUB_BASS_LOW_HZ = 20f
    const val SUB_BASS_HIGH_HZ = 150f
    const val LOW_MID_LOW_HZ = 150f
    const val LOW_MID_HIGH_HZ = 500f
    const val MID_LOW_HZ = 500f
    const val MID_HIGH_HZ = 2_000f
    const val HIGH_MID_LOW_HZ = 2_000f
    const val HIGH_MID_HIGH_HZ = 6_000f
    const val HIGH_LOW_HZ = 6_000f
    const val HIGH_HIGH_HZ = 14_000f

    const val CENTROID_REFERENCE_HZ = 8_000f

    const val LOCAL_STATS_WINDOW_FRAMES = 75

    const val LOCAL_LOUD_PERCENTILE = 0.85f

    const val ONSET_WEIGHT_FLUX = 0.36f
    const val ONSET_WEIGHT_ENERGY_RISE = 0.22f
    const val ONSET_WEIGHT_BAND_ENERGY = 0.26f
    const val ONSET_WEIGHT_LOCAL_CONTRAST = 0.16f

    const val ONSET_ADAPTIVE_RATIO = 1.8f

    const val ONSET_MIN_ABSOLUTE_ENERGY = 0.012f

    const val ONSET_REFRACTORY_MS = 32L

    const val KICK_MAX_CENTROID_HZ = 320f
    const val KICK_MIN_LOW_BAND_DOMINANCE = 0.55f

    const val SNARE_MIN_CENTROID_HZ = 900f
    const val SNARE_MAX_CENTROID_HZ = 4_200f
    const val SNARE_MIN_FLUX_SHARPNESS = 0.35f

    const val HIHAT_MIN_CENTROID_HZ = 4_200f
    const val HIHAT_MAX_LOW_BAND_SHARE = 0.18f

    const val BASS_SUSTAIN_MIN_LOW_ENERGY = 0.16f
    const val BASS_SUSTAIN_MAX_FLUX = 0.22f

    const val BED_ATTACK_COEFF = 0.55f
    const val BED_RELEASE_COEFF = 0.12f

    const val BED_PULSE_INTERVAL_MS = 480L

    const val BED_MAX_INTENSITY = 0.22f

    const val BED_ACTIVATION_FLOOR = 0.14f

    const val BED_COLLISION_GUARD_MS = 45L

    const val MIN_ACCENT_SPACING_MS = 140L

    const val MAX_TEXTURE_EVENTS_PER_SECOND = 2.0f

    const val SUPPRESSED_INTENSITY_RETENTION = 0.55f

    const val MIN_BEAT_CONFIDENCE_FOR_ACCENT = 0.30f

    const val MAJOR_ACCENT_PERCENTILE = 0.93f

    const val MAJOR_ACCENT_MIN_SPACING_MS = 600L

    const val TYPE_WEIGHT_KICK = 1.0f
    const val TYPE_WEIGHT_SNARE = 0.94f
    const val TYPE_WEIGHT_PERCUSSIVE_OTHER = 0.7f
    const val TYPE_WEIGHT_HIHAT = 0.45f

    const val BEAT_PROXIMITY_TOLERANCE_MS = 70L
    const val BEAT_PROXIMITY_BONUS = 0.12f

    const val INTENSITY_KNEE = 0.62f
    const val INTENSITY_KNEE_RATIO = 0.45f

    const val PERCEPTUAL_GAMMA = 0.72f

    const val MAX_RENDER_INTENSITY = 0.97f

    const val SHARPNESS_KICK_BASE = 0.18f
    const val SHARPNESS_SNARE_BASE = 0.6f
    const val SHARPNESS_HIHAT_BASE = 0.9f
    const val SHARPNESS_PERCUSSIVE_OTHER_BASE = 0.45f
    const val SHARPNESS_BED_BASE = 0.08f
    const val SHARPNESS_CENTROID_INFLUENCE = 0.35f

    const val KICK_BODY_DELAY_MS = 32L
    const val KICK_BODY_SCALE = 0.5f
    const val SNARE_TAIL_DELAY_MS = 16L
    const val SNARE_TAIL_SCALE = 0.4f
    const val MAJOR_TAIL_DELAY_MS = 34L
    const val MAJOR_TAIL_SCALE = 0.32f

    const val MIN_PLAUSIBLE_BPM = 60f
    const val MAX_PLAUSIBLE_BPM = 190f

    const val POLL_INTERVAL_MS = 10L

    const val LOOKAHEAD_MS = 10L

    const val RESYNC_DRIFT_MULTIPLIER = 4L

    const val PROCESSING_VERSION = 2

    fun densityScaledMinAccentSpacingMs(hapticDensity: Float): Long {
        val d = hapticDensity.coerceIn(0f, 1f)

        val factor = 1.5f - d * 0.8f
        return (MIN_ACCENT_SPACING_MS * factor).toLong().coerceAtLeast(40L)
    }

    fun densityScaledMaxTextureEventsPerSecond(hapticDensity: Float): Float {
        val d = hapticDensity.coerceIn(0f, 1f)
        return (MAX_TEXTURE_EVENTS_PER_SECOND * (0.55f + d * 0.9f)).coerceAtLeast(1.5f)
    }

    fun bassWeighted(bassEmphasis: Float): Float = 0.8f + bassEmphasis.coerceIn(0f, 1f) * 0.4f
}
