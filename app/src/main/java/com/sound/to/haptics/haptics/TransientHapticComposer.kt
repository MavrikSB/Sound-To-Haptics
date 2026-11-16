package com.sound.to.haptics.haptics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

        data class Scored(val candidate: RawOnsetCandidate, val type: TransientEventType, val priority: Float)

        val scored = candidates.map { candidate ->
            val type = TransientEventClassifier.classify(candidate)
            val typeWeight = when (type) {
                TransientEventType.KICK -> TransientConfig.TYPE_WEIGHT_KICK * TransientConfig.bassWeighted(bassEmphasis)
                TransientEventType.SNARE -> TransientConfig.TYPE_WEIGHT_SNARE
                TransientEventType.HIHAT -> TransientConfig.TYPE_WEIGHT_HIHAT
                TransientEventType.PERCUSSIVE_OTHER -> TransientConfig.TYPE_WEIGHT_PERCUSSIVE_OTHER
                TransientEventType.BASS_BED -> 0f
            }
            val beatBonus = if (TransientBeatTracker.isNearBeat(candidate.timestampMs, beatEstimate)) {
                TransientConfig.BEAT_PROXIMITY_BONUS
            } else 0f
            val priority = candidate.onsetScore * candidate.localProminence * typeWeight + beatBonus
            Scored(candidate, type, priority)
        }.sortedBy { it.candidate.timestampMs }

        if (scored.isEmpty()) return buildBedOnly(frames, bassEmphasis)

        val minAccentSpacingMs = TransientConfig.densityScaledMinAccentSpacingMs(hapticDensity)
        val maxTexturePerSecond = TransientConfig.densityScaledMaxTextureEventsPerSecond(hapticDensity)

        val scene = scenePlan ?: TransientScenePlan(emptyList())

        val allPriorities = scored.map { it.priority }.sorted()
        val majorThresholdPre = percentile(allPriorities, TransientConfig.MAJOR_ACCENT_PERCENTILE)

        val weakBeat = beatEstimate.confidence < TransientConfig.MIN_BEAT_CONFIDENCE_FOR_ACCENT

        data class Placed(val scored: Scored, var layer: TransientLayer, var priorityRetained: Float)

        val placed = ArrayList<Placed>()
        var lastAccentTimestamp = Long.MIN_VALUE / 2

        val recentTextureTimestamps = ArrayDeque<Long>()

        for (entry in scored) {
            val ts = entry.candidate.timestampMs
            val sceneBudget = scene.accentBudget(ts)

            if (sceneBudget <= 0f) continue

            val closeToPreviousAccent = ts - lastAccentTimestamp < minAccentSpacingMs
            val forcedTexture = entry.type == TransientEventType.HIHAT

            val keepAsAccent = !forcedTexture &&
                !closeToPreviousAccent &&
                sceneBudget >= 0.85f &&
                (entry.priority >= majorThresholdPre || !weakBeat)

            if (keepAsAccent) {
                placed += Placed(entry, TransientLayer.ACCENT, sceneBudget.coerceAtLeast(1f))
                lastAccentTimestamp = ts
                continue
            }

            while (recentTextureTimestamps.isNotEmpty() && ts - recentTextureTimestamps.first() > 1000L) {
                recentTextureTimestamps.removeFirst()
            }
            if (recentTextureTimestamps.size < maxTexturePerSecond.toInt().coerceAtLeast(1)) {
                placed += Placed(entry, TransientLayer.TEXTURE, TransientConfig.SUPPRESSED_INTENSITY_RETENTION)
                recentTextureTimestamps.addLast(ts)
            }

        }

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

        finalEvents += buildBedPulses(frames, bassEmphasis, finalEvents, scene)

        return finalEvents.sortedBy { it.timestampMs }
    }

    private fun buildBedOnly(frames: List<RawSpectralFrame>, bassEmphasis: Float): List<TransientEvent> =
        buildBedPulses(frames, bassEmphasis, emptyList())

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

        val subBassPeak = frames.maxOfOrNull { it.subBass + it.lowMid } ?: 0f
        if (subBassPeak <= 1e-5f) return emptyList()

        for (frame in frames) {

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
        if (sortedValues.isEmpty()) return Float.MAX_VALUE
        val index = ((sortedValues.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
    }
}
