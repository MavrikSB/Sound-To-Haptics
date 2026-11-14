package com.sound.to.haptics.haptics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns raw classified onset candidates plus the bass-sustain signal into
 * the final scheduled [TransientEvent] timeline — this is the "Musical
 * Ranking" + "Haptic Composer" + "Envelope Engine" stages of the spec's
 * signal-flow diagram (section 29), combined into one pass since they share
 * the same per-event state (recently-scheduled neighbors, per-track
 * percentiles) and running them as one pass avoids re-walking the event
 * list three times.
 *
 * Responsibilities, in order:
 *  1. Score every candidate's musical importance (prominence x beat
 *     relevance x type weight) — this becomes priority, not yet intensity.
 *  2. Apply density control: enforce minimum accent spacing, demote weak
 *     events near a stronger one to TEXTURE, cap texture-layer rate.
 *  3. Tag the track's own top percentile of accents as MAJOR.
 *  4. Shape final intensity (soft-knee compression + perceptual curve) and
 *     sharpness (per-type baseline + centroid nudge) for every surviving
 *     event.
 *  5. Build the continuous Layer A (Musical Bed) pulses from sustained
 *     low-frequency frames, independent of the discrete accent/texture
 *     events, then merge everything into one sorted timeline.
 */
object TransientHapticComposer {

    fun compose(
        frames: List<RawSpectralFrame>,
        candidates: List<RawOnsetCandidate>,
        beatEstimate: BeatEstimate,
        bassEmphasis: Float,
        hapticDensity: Float,
        scenePlan: TransientScenePlan? = null,
    ): List<TransientEvent> {
        if (frames.isEmpty()) return emptyList()

        // ── Step 1: classify + score musical importance ────────────────
        data class Scored(val candidate: RawOnsetCandidate, val type: TransientEventType, val priority: Float)

        val scored = candidates.map { candidate ->
            val type = TransientEventClassifier.classify(candidate)
            val typeWeight = when (type) {
                TransientEventType.KICK -> TransientConfig.TYPE_WEIGHT_KICK * TransientConfig.bassWeighted(bassEmphasis)
                TransientEventType.SNARE -> TransientConfig.TYPE_WEIGHT_SNARE
                TransientEventType.HIHAT -> TransientConfig.TYPE_WEIGHT_HIHAT
                TransientEventType.PERCUSSIVE_OTHER -> TransientConfig.TYPE_WEIGHT_PERCUSSIVE_OTHER
                TransientEventType.BASS_BED -> 0f // never produced by the classifier directly
            }
            val beatBonus = if (TransientBeatTracker.isNearBeat(candidate.timestampMs, beatEstimate)) {
                TransientConfig.BEAT_PROXIMITY_BONUS
            } else 0f
            val priority = candidate.onsetScore * candidate.localProminence * typeWeight + beatBonus
            Scored(candidate, type, priority)
        }.sortedBy { it.candidate.timestampMs }

        if (scored.isEmpty()) return buildBedOnly(frames, bassEmphasis)

        // ── Step 2: density control ──────────────────────────────────────
        val minAccentSpacingMs = TransientConfig.densityScaledMinAccentSpacingMs(hapticDensity)
        val maxTexturePerSecond = TransientConfig.densityScaledMaxTextureEventsPerSecond(hapticDensity)

        // Scene plan (Apple/Immersion-style silence/salience) translated into
        // per-time accent + intensity multipliers.
        val scene = scenePlan ?: TransientScenePlan(emptyList())

        // MAJORS are decided from the full candidate priority distribution,
        // before layering, so they survive the weak-beat gate.
        val allPriorities = scored.map { it.priority }.sorted()
        val majorThresholdPre = percentile(allPriorities, TransientConfig.MAJOR_ACCENT_PERCENTILE)
        // Hi-hats fire textures, never accents; MAJORS always survive.
        val weakBeat = beatEstimate.confidence < TransientConfig.MIN_BEAT_CONFIDENCE_FOR_ACCENT

        data class Placed(val scored: Scored, var layer: TransientLayer, var priorityRetained: Float)

        val placed = ArrayList<Placed>()
        var lastAccentTimestamp = Long.MIN_VALUE / 2
        // Sliding window of recent TEXTURE event timestamps to enforce a
        // rate cap (events/second) rather than only a fixed spacing.
        val recentTextureTimestamps = ArrayDeque<Long>()

        for (entry in scored) {
            val ts = entry.candidate.timestampMs
            val sceneBudget = scene.accentBudget(ts)
            // Apple/Immersion rule: silence stays silent. No haptic at all.
            if (sceneBudget <= 0f) continue

            val closeToPreviousAccent = ts - lastAccentTimestamp < minAccentSpacingMs
            val forcedTexture = entry.type == TransientEventType.HIHAT
            // Active accents only when the scene allows them (build/drop) and the
            // beat is either confident or the candidate is a MAJOR.
            val keepAsAccent = !forcedTexture &&
                !closeToPreviousAccent &&
                sceneBudget >= 0.85f &&
                (entry.priority >= majorThresholdPre || !weakBeat)

            if (keepAsAccent) {
                placed += Placed(entry, TransientLayer.ACCENT, sceneBudget.coerceAtLeast(1f))
                lastAccentTimestamp = ts
                continue
            }

            // Too close to a recent accent: this becomes a TEXTURE
            // candidate (suppressed, not deleted — spec section 15: "weak
            // nearby events -> suppress or reduce"), subject to its own
            // rate cap so dense hi-hats/percussion cannot buzz.
            while (recentTextureTimestamps.isNotEmpty() && ts - recentTextureTimestamps.first() > 1000L) {
                recentTextureTimestamps.removeFirst()
            }
            if (recentTextureTimestamps.size < maxTexturePerSecond.toInt().coerceAtLeast(1)) {
                placed += Placed(entry, TransientLayer.TEXTURE, TransientConfig.SUPPRESSED_INTENSITY_RETENTION)
                recentTextureTimestamps.addLast(ts)
            }
            // else: fully suppressed — too dense even for the texture layer.
        }

        // ── Step 3: tag MAJOR accents relative to this track's own priority distribution ──
        val accentPriorities = placed.filter { it.layer == TransientLayer.ACCENT }.map { it.scored.priority }.sorted()
        val majorThreshold = percentile(accentPriorities, TransientConfig.MAJOR_ACCENT_PERCENTILE)
        var lastMajorTimestamp = Long.MIN_VALUE / 2
        val finalEvents = ArrayList<TransientEvent>()

        for (p in placed) {
            val ts = p.scored.candidate.timestampMs
            val isMajor = p.layer == TransientLayer.ACCENT &&
                p.scored.priority >= majorThreshold &&
                ts - lastMajorTimestamp >= TransientConfig.MAJOR_ACCENT_MIN_SPACING_MS
            if (isMajor) lastMajorTimestamp = ts

            val rawIntensity = (p.scored.priority * p.priorityRetained * (if (isMajor) 1.18f else 1f))
            val intensity = shapeIntensity(rawIntensity)
            val sharpness = shapeSharpness(p.scored.type, p.scored.candidate.frame)
            val durationMs = when (p.scored.type) {
                TransientEventType.KICK -> 130L
                TransientEventType.SNARE -> 70L
                TransientEventType.HIHAT -> 30L
                TransientEventType.PERCUSSIVE_OTHER -> 55L
                TransientEventType.BASS_BED -> 0L
            }

            finalEvents += TransientEvent(
                timestampMs = ts,
                type = p.scored.type,
                layer = p.layer,
                intensity = intensity,
                sharpness = sharpness,
                durationMs = durationMs,
                isMajorAccent = isMajor,
            )
        }

        // ── Step 4: Musical Bed (Layer A) — independent continuous pulses ──
        finalEvents += buildBedPulses(frames, bassEmphasis, finalEvents, scene)

        return finalEvents.sortedBy { it.timestampMs }
    }

    private fun buildBedOnly(frames: List<RawSpectralFrame>, bassEmphasis: Float): List<TransientEvent> =
        buildBedPulses(frames, bassEmphasis, emptyList())

    /**
     * Builds Layer A: a slow-moving, heavily-limited stream of low-intensity
     * pulses that tracks the smoothed bass/sub-bass envelope. This is what
     * keeps sustained bass/pad material from going silent between discrete
     * accents (spec section 9/19) without turning into its own competing
     * rhythm (spec section 10's "should not be constantly strong").
     */
    private fun buildBedPulses(
        frames: List<RawSpectralFrame>,
        bassEmphasis: Float,
        existingEvents: List<TransientEvent>,
        scene: TransientScenePlan? = null,
    ): List<TransientEvent> {
        val bedEvents = ArrayList<TransientEvent>()
        var smoothedBass = 0f
        var lastPulseTimestamp = Long.MIN_VALUE / 2
        val weight = TransientConfig.bassWeighted(bassEmphasis)

        // Reference scale so the bed's envelope is relative to this track's
        // own bass range, not an arbitrary absolute FFT magnitude.
        val subBassPeak = frames.maxOfOrNull { it.subBass + it.lowMid } ?: 0f
        if (subBassPeak <= 1e-5f) return emptyList()

        for (frame in frames) {
            // Apple/Immersion compliance: silence produces no events.
            if (scene != null && scene.accentBudget(frame.timestampMs) <= 0f) {
                smoothedBass *= 0.5f
                continue
            }
            val lowEnergyNorm = ((frame.subBass + frame.lowMid) / subBassPeak).coerceIn(0f, 1f)
            val isSustain = TransientEventClassifier.isBassSustainFrame(frame)
            val target = if (isSustain) lowEnergyNorm else lowEnergyNorm * 0.6f
            val coeff = if (target > smoothedBass) TransientConfig.BED_ATTACK_COEFF else TransientConfig.BED_RELEASE_COEFF
            smoothedBass += coeff * (target - smoothedBass)

            if (smoothedBass < TransientConfig.BED_ACTIVATION_FLOOR) continue
            if (frame.timestampMs - lastPulseTimestamp < TransientConfig.BED_PULSE_INTERVAL_MS) continue

            // Skip a bed pulse landing right on top of an already-scheduled
            // accent/texture event — the two would otherwise double up the
            // motor on the exact same instant (spec section 19).
            val collides = existingEvents.any { kotlin.math.abs(it.timestampMs - frame.timestampMs) < TransientConfig.BED_COLLISION_GUARD_MS }
            if (collides) continue

            val intensity = (smoothedBass * weight * TransientConfig.BED_MAX_INTENSITY).coerceIn(0f, TransientConfig.BED_MAX_INTENSITY)
            bedEvents += TransientEvent(
                timestampMs = frame.timestampMs,
                type = TransientEventType.BASS_BED,
                layer = TransientLayer.BED,
                intensity = intensity,
                sharpness = TransientConfig.SHARPNESS_BED_BASE,
                durationMs = 180L,
            )
            lastPulseTimestamp = frame.timestampMs
        }
        return bedEvents
    }

    /**
     * Soft-knee compression + perceptual curve (spec section 12):
     * below the knee, intensity passes through unchanged; above it, the
     * excess is compressed so the loudest handful of hits don't all
     * collapse to the same maximum. The result is then raised to a gamma
     * < 1 so quieter/mid events remain clearly perceptible on a vibrator's
     * effectively-linear amplitude response, matching Android's own
     * documented framing of 0.5 scale as already "low" rather than
     * "barely there".
     */
    private fun shapeIntensity(raw: Float): Float {
        val clamped = raw.coerceIn(0f, 4f)
        val knee = TransientConfig.INTENSITY_KNEE
        val compressed = if (clamped <= knee) {
            clamped
        } else {
            knee + (clamped - knee) * TransientConfig.INTENSITY_KNEE_RATIO
        }
        val normalized = (compressed / (knee + (4f - knee) * TransientConfig.INTENSITY_KNEE_RATIO)).coerceIn(0f, 1f)
        val perceptual = normalized.pow(TransientConfig.PERCEPTUAL_GAMMA)
        return (perceptual * TransientConfig.MAX_RENDER_INTENSITY).coerceIn(0f, TransientConfig.MAX_RENDER_INTENSITY)
    }

    /**
     * Sharpness is a dimension independent of intensity (spec section 13):
     * a loud kick must stay round/low-sharpness, a quiet hi-hat must stay
     * crisp/high-sharpness. Each type has a fixed baseline; the frame's own
     * normalized centroid only nudges it within a bounded range so type
     * identity dominates over incidental spectral noise.
     */
    private fun shapeSharpness(type: TransientEventType, frame: RawSpectralFrame): Float {
        val base = when (type) {
            TransientEventType.KICK -> TransientConfig.SHARPNESS_KICK_BASE
            TransientEventType.SNARE -> TransientConfig.SHARPNESS_SNARE_BASE
            TransientEventType.HIHAT -> TransientConfig.SHARPNESS_HIHAT_BASE
            TransientEventType.PERCUSSIVE_OTHER -> TransientConfig.SHARPNESS_PERCUSSIVE_OTHER_BASE
            TransientEventType.BASS_BED -> TransientConfig.SHARPNESS_BED_BASE
        }
        val centroidNorm = (frame.centroidHz / TransientConfig.CENTROID_REFERENCE_HZ).coerceIn(0f, 1f)
        val nudge = (centroidNorm - 0.5f) * TransientConfig.SHARPNESS_CENTROID_INFLUENCE
        return (base + nudge).coerceIn(0f, 1f)
    }

    private fun percentile(sortedValues: List<Float>, fraction: Float): Float {
        if (sortedValues.isEmpty()) return Float.MAX_VALUE // nothing ever qualifies as MAJOR with no accents
        val index = ((sortedValues.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
    }
}
