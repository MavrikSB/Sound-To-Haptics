package com.sound.to.haptics.haptics

import java.util.Locale

/**
 * The FFmpeg filter chain that turns a stereo mix into the mono "haptic"
 * signal: lowpass (bass-focus) -> mono downmix (density) -> bass boost ->
 * percussive compressor -> gain -> limiter.
 *
 * This used to be duplicated by hand: the offline converter had its own
 * copy inlined in [com.sound.to.haptics.MainViewModel], and the BitChord
 * capture pipeline shaped audio with a completely separate, simpler RMS
 * extractor that ignored bass-focus/density entirely. Both now call
 * [filterChain] so a track is shaped identically no matter which pipeline
 * produced it, and tuning the DSP once tunes both.
 *
 * This chain used to run `loudnorm` on the haptic branch, copied from the
 * audible-stereo branch where it's the right tool (it's designed to bring a
 * whole track's *average* loudness to a consistent target for comfortable
 * listening). That's exactly the wrong job for something driving a
 * vibrator: loudnorm's slow-moving AGC pulls quiet passages up and loud
 * passages down toward the same average level, which is what made a whole
 * song "load onto the haptics" as one continuous hum instead of feeling
 * like distinct hits — the motor was being asked to move almost as much
 * during quiet parts as during an actual kick/snare hit. `acompressor`
 * below replaces it: a short attack still lets the front of a transient
 * through before the compressor engages (so a beat still hits hard), a
 * fast release means it lets go again immediately after rather than
 * smearing into the next moment, and a real `bass` low-shelf boost adds
 * low-end weight that the lowpass alone (which only selects which
 * frequencies pass through, without boosting them) never provided.
 */
object HapticAudioShaping {

    /** Haptic channel gain: zero really means silent; full strength reaches 1.2x before limiting. */
    fun hapticGain(sensitivity: Float): String =
        "%.2f".format(Locale.US, sensitivity.coerceIn(0f, 1f) * 1.2f)

    /** Lowpass cutoff: 800 Hz (no bass focus) down to 80 Hz (full bass focus). */
    fun lowpassHz(bassBoost: Float): Int =
        ((1f - bassBoost.coerceIn(0f, 1f)) * 720f + 80f).toInt()

    /**
     * Per-channel coefficient for the mono haptic downmix.
     *
     * Keep the sum of both coefficients close to unity. The previous range
     * reached 1.8x before the compressor, which made the haptic branch spend
     * too much of its headroom in limiting and made the bass feel smeared.
     */
    fun densityMix(density: Float): String =
        "%.2f".format(Locale.US, 0.5f + density.coerceIn(0f, 1f) * 0.15f)

    /** Low-shelf boost applied under ~150Hz: 0dB (bassBoost=0) up to +9dB (bassBoost=1). */
    fun bassBoostDb(bassBoost: Float): String =
        "%.1f".format(Locale.US, bassBoost.coerceIn(0f, 1f) * 9f)

    /**
     * Builds the `[inputLabel] ... [outputLabel]` filter fragment. [inputLabel]
     * must already be a stereo, fltp, 44.1kHz stream (callers wrap raw/compressed
     * input with an `aformat`/`asplit` stage first — see MainViewModel and
     * HapticCaptureConverter for the two call sites).
     */
    fun filterChain(
        sensitivity: Float,
        bassBoost: Float,
        density: Float,
        inputLabel: String,
        outputLabel: String,
    ): String {
        val gain = hapticGain(sensitivity)
        val lp = lowpassHz(bassBoost)
        val mix = densityMix(density)
        val bassDb = bassBoostDb(bassBoost)
        // Three intentionally different layers make the single mono haptic
        // channel feel like music rather than a full-song buzz:
        //   bass  = low-frequency weight / THUD
        //   impact = short, compressed kick attack / CLICK
        //   flow = a wider, slower envelope for risers, swells and SWISH
        // They are mixed before the final limiter so loud drops hit harder,
        // while the flow layer keeps sustained musical movement present.
        return "$inputLabel" +
            "asplit=3[bassIn][impactIn][flowIn];" +
            "[bassIn]lowpass=f=$lp,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "bass=g=$bassDb:f=120:w=0.6," +
            "acompressor=threshold=0.08:ratio=7:attack=10:release=110:makeup=3[bass];" +
            "[impactIn]highpass=f=1200,lowpass=f=8500,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "acompressor=threshold=0.06:ratio=10:attack=3:release=72:makeup=5,volume=1.05[impact];" +
            // Mid/centre energy is the smooth musical-flow proxy. It is
            // intentionally separate from the high-band impact branch so
            // vocals and sustained melodic content do not become a stream
            // of clicks. The full DSP layer analysis is persisted alongside
            // the OGG for the transient renderer and debug visualizer.
            "[flowIn]highpass=f=180,lowpass=f=3200,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "acompressor=threshold=0.14:ratio=3:attack=28:release=260:makeup=2,volume=0.42[flow];" +
            "[bass][impact][flow]amix=inputs=3:weights=1.0 0.72 0.62:normalize=0," +
            // A gentle gate removes motor chatter from digital silence while
            // leaving real quiet musical phrases enough room to breathe.
            "agate=threshold=0.018:ratio=2.5:attack=15:release=180," +
            "volume=$gain,alimiter=limit=1.0:attack=5:release=70," +
            // Keep the haptic timeline sample-accurate with the untouched
            // stereo branch. async=1 can insert/drop samples and produces
            // non-monotonic timestamps when the two branches are merged.
            "aresample=44100:resampler=soxr:precision=28,asetpts=N/SR/TB," +
            "aformat=sample_fmts=fltp:channel_layouts=mono" +
            outputLabel
    }

    /**
     * A lighter-weight variant of [filterChain]: bass-focus + mono downmix
     * only, no compressor/limiter/gain shaping. Kept for callers that need
     * a raw, unprocessed onset-analysis-friendly signal — analysis needs to
     * see a track's genuine, unprocessed dynamic peaks; compressing or
     * gain-staging the signal first would flatten exactly the loud/quiet
     * contrast an onset detector uses to tell a real beat from ordinary
     * variation. (Not currently on any call path — Transient Mode's own
     * analysis pipeline decodes independently via FFmpeg's `-f s16le`
     * directly, and Continuous mode's layer analysis does the same; this
     * remains available for any future analysis path that wants FFmpeg-
     * side bass-focus shaping instead.)
     */
    fun analysisFilterChain(bassBoost: Float, density: Float, inputLabel: String, outputLabel: String): String {
        val lp = lowpassHz(bassBoost)
        val mix = densityMix(density)
        return "$inputLabel" +
            "lowpass=f=$lp,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "aresample=44100:async=1,aformat=sample_fmts=s16:channel_layouts=mono" +
            outputLabel
    }
}

