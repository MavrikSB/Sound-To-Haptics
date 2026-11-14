package com.sound.to.haptics.haptics

/**
 * Every tunable number used by the Transient Mode musical-haptic pipeline
 * (feature extraction -> onset detection -> classification -> composition ->
 * rendering). Centralized here so none of the DSP/rendering files contain
 * unexplained magic numbers, and so the whole pipeline can be re-tuned (e.g.
 * after listening on a Galaxy S24) by editing values in one place instead of
 * hunting through five files.
 *
 * Every constant below has a one-line rationale. Where a value scales with
 * an existing user-facing setting (haptic density / bass emphasis / haptic
 * strength), that relationship is documented explicitly so the effect on
 * this pipeline stays predictable.
 */
object TransientConfig {

    // ── Analysis window / hop ──────────────────────────────────────────
    // FFT size of 2048 at 44.1kHz gives an ~46.4ms analysis window (inside
    // the 20-40ms range the spec calls out) with a real frequency
    // resolution of sampleRate/fftSize ~= 21.5Hz per bin -- fine enough to
    // separate sub-bass/bass/low-mid without needing a huge transform.
    // Power-of-two is required by the radix-2 FFT in TransientFeatureExtractor.
    const val FFT_SIZE = 2048

    // Hop matches the existing continuous-mode analyzer's 20ms frame so both
    // analyzers can share one decode pass and stay easy to reason about
    // together in the debug visualizer's shared timeline.
    const val HOP_MS = 20L

    // ── Frequency bands (Hz) ────────────────────────────────────────────
    // Deliberately named for what they *mean* perceptually, not just their
    // numeric range -- see MultiLayerHapticAnalysis.kt's own band choices,
    // which this refines with real spectral (FFT) resolution instead of a
    // one-pole filter cascade.
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

    // Spectral centroid is reported in Hz internally, then normalized to
    // 0..1 for scoring/classification by dividing by this reference. 8kHz
    // covers essentially the full perceptually "bright" range for the
    // material this app targets (music, not ultrasonics), so a centroid at
    // or above it saturates to 1.0 rather than needing the true Nyquist.
    const val CENTROID_REFERENCE_HZ = 8_000f

    // ── Local normalization / rolling statistics ───────────────────────
    // Rolling window used to compute "what's typical right now" for flux,
    // RMS and band energy. ~1.5s (75 frames * 20ms) is long enough to
    // smooth over a single bar of music without being so long it can't
    // adapt between a quiet verse and a loud chorus within a few seconds.
    const val LOCAL_STATS_WINDOW_FRAMES = 75

    // Percentile used as "the loud reference level" for local-contrast
    // scoring -- deliberately not the single track-wide peak (one clipped
    // sample would then suppress every other event's contrast score).
    const val LOCAL_LOUD_PERCENTILE = 0.85f

    // ── Onset scoring weights ───────────────────────────────────────────
    // onsetScore = wFlux*fluxNorm + wRise*energyRise + wBand*bandEnergyNorm
    //            + wContrast*localContrast
    // Flux and band energy carry the most weight because they are the most
    // reliable signals that *something new* started (a real spectral
    // change), rather than the track simply being loud right now.
    const val ONSET_WEIGHT_FLUX = 0.36f
    const val ONSET_WEIGHT_ENERGY_RISE = 0.22f
    const val ONSET_WEIGHT_BAND_ENERGY = 0.26f
    const val ONSET_WEIGHT_LOCAL_CONTRAST = 0.16f

    // A frame must clear localMeanScore * ONSET_ADAPTIVE_RATIO to become a
    // candidate at all. This is what makes a quietly mastered track still
    // produce onsets (the local mean is quiet too) while a hot, dense mix
    // doesn't just fire on every sample above some fixed absolute number.
    const val ONSET_ADAPTIVE_RATIO = 1.55f

    // Absolute floor beneath which nothing is ever treated as an onset,
    // regardless of how it compares to the (possibly near-silent) local
    // baseline -- stops true digital silence from producing "onsets" out of
    // amplifier/decoder noise.
    const val ONSET_MIN_ABSOLUTE_ENERGY = 0.012f

    // Minimum time between two accepted onset *candidates* at the detector
    // stage. This is intentionally short (finer than the density controls
    // applied later in the composer) -- it only exists to stop one physical
    // hit's decay tail from being re-detected as several onsets in a row.
    const val ONSET_REFRACTORY_MS = 32L

    // ── Musical event classification thresholds ─────────────────────────
    // Kick-like: energy concentrated below ~150Hz and a low spectral
    // centroid. 300Hz is comfortably above sub-bass/bass but well below
    // where a snare's body or a cymbal would sit.
    const val KICK_MAX_CENTROID_HZ = 320f
    const val KICK_MIN_LOW_BAND_DOMINANCE = 0.55f // low-band share of total onset energy

    // Snare/clap: fast, sharp, energy weighted toward mid/high-mid with a
    // markedly higher centroid than a kick but not as extreme as a hat.
    const val SNARE_MIN_CENTROID_HZ = 900f
    const val SNARE_MAX_CENTROID_HZ = 4_200f
    const val SNARE_MIN_FLUX_SHARPNESS = 0.35f

    // Hi-hat-like: very high centroid, very short-lived, little/no bass.
    const val HIHAT_MIN_CENTROID_HZ = 4_200f
    const val HIHAT_MAX_LOW_BAND_SHARE = 0.18f

    // Bass sustain: low-band energy that persists (low flux) rather than
    // spiking -- used to drive the continuous "musical bed" layer instead
    // of a discrete accent.
    const val BASS_SUSTAIN_MIN_LOW_ENERGY = 0.16f
    const val BASS_SUSTAIN_MAX_FLUX = 0.22f

    // ── Musical-bed (Layer A) smoothing/scheduling ──────────────────────
    // Attack/release time constants (exponential smoothing coefficients,
    // 0..1 per 20ms hop) for the continuous low-frequency bed envelope.
    // Attack is faster than release so the bed can "catch" a rising bass
    // note quickly but decays gently rather than pumping.
    const val BED_ATTACK_COEFF = 0.55f
    const val BED_RELEASE_COEFF = 0.12f

    // Minimum gap between two bed pulses -- keeps the sustained texture a
    // slow, felt "presence" rather than its own rhythm competing with real
    // percussion. ~220ms is close to a slow 8th-note pulse at typical
    // tempos without feeling like a separate beat.
    const val BED_PULSE_INTERVAL_MS = 220L

    // Bed pulses never exceed this fraction of full scale -- it is
    // deliberately the quietest layer in the system (see spec: "should not
    // be constantly strong").
    const val BED_MAX_INTENSITY = 0.34f

    // Below this smoothed bass envelope level, no bed pulse is scheduled at
    // all -- true silence should stay silent rather than idly ticking.
    const val BED_ACTIVATION_FLOOR = 0.09f

    // A bed pulse this close to an already-scheduled accent/texture event
    // is dropped rather than doubling up the motor on the same instant.
    const val BED_COLLISION_GUARD_MS = 45L

    // ── Density control / composer ──────────────────────────────────────
    // Minimum spacing between two ACCENT-layer events. This is the main
    // knob that stops "wall of impacts" -- events closer than this to a
    // stronger neighbor are demoted to TEXTURE (reduced, not deleted).
    const val MIN_ACCENT_SPACING_MS = 85L

    // Texture-layer events are capped at this rate so dense hi-hats become
    // a controlled shimmer, never a buzz (spec Test F).
    const val MAX_TEXTURE_EVENTS_PER_SECOND = 6f

    // A demoted (suppressed) event keeps this fraction of its original
    // intensity when downgraded from ACCENT to TEXTURE, rather than being
    // silently discarded -- dense sections stay felt, just gentler.
    const val SUPPRESSED_INTENSITY_RETENTION = 0.55f

    // Fraction of the track's own 95th-percentile onset priority an event
    // must clear to be tagged MAJOR (a chorus hit, a big drop) -- relative
    // to the *track*, not a fixed absolute number, so quiet and loud
    // masters both get major accents at musically-equivalent moments.
    const val MAJOR_ACCENT_PERCENTILE = 0.93f

    // Minimum spacing between two MAJOR-tagged accents -- a legitimately
    // huge moment shouldn't repeat every 200ms even in a dense breakdown.
    const val MAJOR_ACCENT_MIN_SPACING_MS = 600L

    // Relative priority weighting per musical role -- kicks and snares are
    // the primary rhythmic information a listener should feel first;
    // texture is supporting detail.
    const val TYPE_WEIGHT_KICK = 1.0f
    const val TYPE_WEIGHT_SNARE = 0.94f
    const val TYPE_WEIGHT_PERCUSSIVE_OTHER = 0.7f
    const val TYPE_WEIGHT_HIHAT = 0.45f

    // A small, non-quantizing bonus applied when an onset lands within this
    // tolerance of an estimated beat time -- nudges selection toward
    // rhythmically-supported hits without ever snapping their timestamp.
    const val BEAT_PROXIMITY_TOLERANCE_MS = 70L
    const val BEAT_PROXIMITY_BONUS = 0.12f

    // ── Intensity shaping (applied identically to every event, before the
    // user's haptic-strength multiplier, so relative ordering between
    // event types is always preserved regardless of strength setting) ──
    // Soft-knee compressor: below the knee, intensity passes through
    // linearly; above it, the excess is compressed by KNEE_RATIO so a
    // handful of the loudest hits don't all collapse to the same 100%.
    const val INTENSITY_KNEE = 0.62f
    const val INTENSITY_KNEE_RATIO = 0.45f

    // Final perceptual curve exponent (x^gamma). gamma < 1 lifts quiet/mid
    // events to stay perceptible on a linear vibrator amplitude scale,
    // matching how Android's own guidance treats 0.5 scale as already "low"
    // rather than "barely there".
    const val PERCEPTUAL_GAMMA = 0.72f

    // Hard ceiling applied after the perceptual curve but before user
    // strength -- keeps a safety margin under 1.0 so the user-strength
    // slider still has visible room to push louder on the loudest hits.
    const val MAX_RENDER_INTENSITY = 0.97f

    // ── Sharpness mapping (independent from intensity) ──────────────────
    // Baseline sharpness per type, refined per-event by normalized centroid.
    const val SHARPNESS_KICK_BASE = 0.18f
    const val SHARPNESS_SNARE_BASE = 0.6f
    const val SHARPNESS_HIHAT_BASE = 0.9f
    const val SHARPNESS_PERCUSSIVE_OTHER_BASE = 0.45f
    const val SHARPNESS_BED_BASE = 0.08f
    const val SHARPNESS_CENTROID_INFLUENCE = 0.35f // how much normalized centroid nudges the base

    // ── Envelope timing (attack/body/decay approximated by sequenced
    // composed primitives -- see TransientHapticRenderer) ───────────────
    const val KICK_BODY_DELAY_MS = 32L
    const val KICK_BODY_SCALE = 0.5f
    const val SNARE_TAIL_DELAY_MS = 16L
    const val SNARE_TAIL_SCALE = 0.4f
    const val MAJOR_TAIL_DELAY_MS = 34L
    const val MAJOR_TAIL_SCALE = 0.32f

    // ── Beat tracking ────────────────────────────────────────────────────
    // Plausible musical tempo range used to constrain the inter-onset
    // interval autocorrelation in TransientBeatTracker -- keeps it from
    // "locking on" to an implausible 400 BPM flutter or a 20 BPM crawl.
    const val MIN_PLAUSIBLE_BPM = 60f
    const val MAX_PLAUSIBLE_BPM = 190f

    // ── Rendering / scheduling ───────────────────────────────────────────
    // Poll interval for the position-tracking loop. Shorter than the old
    // single-purpose beat player's 15ms because this renderer now also
    // schedules a small lookahead fire (see LOOKAHEAD_MS) off the same tick.
    const val POLL_INTERVAL_MS = 10L

    // How far ahead of the polled position an already-due event may be
    // pre-scheduled via a precise postDelayed callback, to shave off poll
    // jitter without making a hit feel early. Half the poll interval keeps
    // the maximum possible schedule error under 5ms either direction.
    const val LOOKAHEAD_MS = 10L

    // A resync (seek/scrub) is assumed if the position jumps by more than
    // this many poll intervals' worth of time between ticks.
    const val RESYNC_DRIFT_MULTIPLIER = 4L

    // ── Cache / versioning ───────────────────────────────────────────────
    // Bumping this invalidates every cached .transient sidecar file the
    // moment the analysis/composition/rendering contract changes, exactly
    // like HapticProcessing.VERSION does for the continuous pipeline. Kept
    // independent of HapticProcessing.VERSION on purpose: Continuous mode's
    // cache must never be invalidated by a Transient-only algorithm change.
    const val PROCESSING_VERSION = 1

    /**
     * Density scaling derived from the user's existing "Haptic Density"
     * setting (AppSettings.hapticDensity, 0..1). 0 tightens spacing/texture
     * caps for a sparser feel; 1 relaxes them for a busier one. Centered so
     * the *default* density (0.6) reproduces the constants above almost
     * exactly.
     */
    fun densityScaledMinAccentSpacingMs(hapticDensity: Float): Long {
        val d = hapticDensity.coerceIn(0f, 1f)
        // 1.5x spacing at density=0, 0.7x spacing at density=1.
        val factor = 1.5f - d * 0.8f
        return (MIN_ACCENT_SPACING_MS * factor).toLong().coerceAtLeast(24L)
    }

    fun densityScaledMaxTextureEventsPerSecond(hapticDensity: Float): Float {
        val d = hapticDensity.coerceIn(0f, 1f)
        return (MAX_TEXTURE_EVENTS_PER_SECOND * (0.55f + d * 0.9f)).coerceAtLeast(1.5f)
    }

    /** Bass-emphasis-aware weighting so an existing user preference still matters here. */
    fun bassWeighted(bassEmphasis: Float): Float = 0.8f + bassEmphasis.coerceIn(0f, 1f) * 0.4f
}
