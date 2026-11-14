package com.sound.to.haptics.haptics

/**
 * Apple-inspired "scene" planning for Transient mode: the whole-track context
 * the frame-by-frame onset logic does not have.
 *
 * The research is unambiguous (Apple patents, Immersion envelope work, and
 * Aker/Sound2Hap audio–tactile congruence papers): audiences rate haptic
 * events as coherent when they exist *because the music implies them* — not
 * because a rolling window happened to cross an adaptive threshold. That
 * requires phrase-level knowledge this file provides:
 *
 *  - **SILENCE / QUIET** regions: nothing fires there, no matter what the
 *    onset detector says — silence must feel silent (also: battery).
 *  - **SUSTAINED** regions: only low-intensity taps; this is the default
 *    mid-song texture, not a beat-tracked accent stream.
 *  - **BUILD-UP** regions (energy rising across a phrase): accents allowed
 *    at bumped intensity / density — anticipation, the "drop in 2 bars".
 *  - **DROP / PEAK** regions (phrase-synchronous high energy, usually after
 *    a build): accents may fire freely, sharper "drop" shape.
 *
 * The analyzer deliberately emits a small, coarse plan — enough structure
 * to change the composer's output dramatically, not a second music model.
 *
 * Runs once during analysis (off the playback thread). The output feeds
 * [TransientHapticComposer]; it never fires haptics on its own.
 */

enum class SceneRegionKind {
    SILENCE,
    QUIET,
    SUSTAINED,
    BUILD_UP,
    DROP,
}

data class SceneRegion(
    val startMs: Long,
    val endMs: Long,
    val kind: SceneRegionKind,
    /** 0..1 — analyzer's confidence in this label. */
    val confidence: Float,
    /** The window's mean band-energy-ish value used to build the region. */
    val energy: Float,
)

data class TransientScenePlan(
    val regions: List<SceneRegion>,
) {
    /** Inclusive ms ranges during which NO haptic should be scheduled. */
    val silenceRangesMs: List<LongRange> =
        regions.filter { it.kind == SceneRegionKind.SILENCE }.map { it.startMs..it.endMs }

    /**
     * How much accent budget a moment gets. Quieter than normal → demote.
     * Peak phrase → full budget. Returns 0 when haptics should be absent.
     */
    fun accentBudget(atMs: Long): Float = when (regionAt(atMs)?.kind) {
        SceneRegionKind.SILENCE -> 0f
        SceneRegionKind.QUIET -> 0.25f
        SceneRegionKind.SUSTAINED -> 0.85f
        SceneRegionKind.BUILD_UP -> 1.15f
        SceneRegionKind.DROP -> 1.6f
        null -> 0.85f
    }

    /** How intense is this moment relative to the track's own loudness? */
    fun salienceAt(atMs: Long): Float = regionAt(atMs)?.energy ?: 0.4f

    fun regionAt(atMs: Long): SceneRegion? =
        regions.firstOrNull { atMs in it.startMs until it.endMs }
}

/**
 * Computes a coarse [TransientScenePlan] from extracted frames.
 *
 * Method (deliberately simple, deliberately robust):
 *   1. bucket frames into ~500 ms windows; track energy = sum of band energies
 *   2. normalize so the loudest sustained window ≈ 1
 *   3. classify each window:
 *        energy == 0 (or below absolute floor)      → SILENCE
 *        energy <  quantile(0.25)                   → QUIET
 *        energy is rising AND within near the peak  → BUILD_UP / DROP
 *   4. merge adjacent same-kind windows (≥250 ms each already)
 *
 * No neural network. No training data. No network. Everything is local,
 * deterministic, and testable without hardware.
 */
object TransientScenePlanner {

    private const val WINDOW_MS = 480L
    private const val ABSOLUTE_FLOOR = 0.018f
    private const val QUIET_QUANTILE = 0.22f
    private const val SUSTAINED_QUANTILE = 0.55f
    private const val PEAK_QUANTILE = 0.86f
    private const val RISE_LOOKBACK_WINDOWS = 3

    fun plan(frames: List<RawSpectralFrame>): TransientScenePlan {
        if (frames.isEmpty()) return TransientScenePlan(emptyList())

        val windows = bucketWindows(frames)
        if (windows.isEmpty()) return TransientScenePlan(emptyList())

        val energies = windows.map { it.energy.coerceAtLeast(0f) }
        val maxEnergy = energies.maxOrNull()?.takeIf { it > 1e-6f } ?: 1f
        val norm = energies.map { it / maxEnergy }

        val quietThreshold = quantile(norm, QUIET_QUANTILE)
        val sustainedThreshold = quantile(norm, SUSTAINED_QUANTILE)
        val peakThreshold = quantile(norm, PEAK_QUANTILE)

        // Find chunks that look like build → drop: sustained rise in energy
        // culminating above the peak threshold.
        val kinds = MutableList(windows.size) { SceneRegionKind.SUSTAINED }
        for (i in windows.indices) {
            val e = norm[i]
            kinds[i] = when {
                e <= ABSOLUTE_FLOOR -> SceneRegionKind.SILENCE
                e < quietThreshold -> SceneRegionKind.QUIET
                isBuildRise(norm, i, sustainedThreshold, peakThreshold) -> SceneRegionKind.BUILD_UP
                e >= peakThreshold && precededBySustainedRise(norm, i, sustainedThreshold, peakThreshold) ->
                    SceneRegionKind.DROP
                else -> SceneRegionKind.SUSTAINED
            }
        }

        return TransientScenePlan(mergeWindows(windows, kinds, energies, maxEnergy))
    }

    private data class Window(
        val startMs: Long,
        val endMs: Long,
        val energy: Float,
    )

    private fun bucketWindows(frames: List<RawSpectralFrame>): List<Window> {
        val byStart = frames.groupBy { it.timestampMs / WINDOW_MS }
        return byStart.values
            .sortedBy { it.first().timestampMs }
            .map { group ->
                val start = group.first().timestampMs
                val end = group.last().timestampMs + TransientConfig.HOP_MS
                val energy = group.sumOf { frame ->
                    (frame.subBass + frame.lowMid + frame.mid + frame.highMid + frame.high).toDouble()
                }.toFloat() / group.size
                Window(start, end, energy)
            }
    }

    /** Cheap "is it rising steadily toward peak over the last few windows" test. */
    private fun isBuildRise(
        norm: List<Float>,
        i: Int,
        sustainedThreshold: Float,
        peakThreshold: Float,
    ): Boolean {
        if (i >= norm.size - 1) return norm[i] in (sustainedThreshold * 0.6f)..peakThreshold
        val lookback = minOf(RISE_LOOKBACK_WINDOWS, i)
        if (lookback < 2) return false
        val startIdx = i - lookback
        val startE = norm.subList(startIdx, i + 1)
        val monotonicRising = startE.zipWithNext().all { (a, b) -> b >= a - 0.005f }
        val peakAhead = norm.subList(i, minOf(i + 3, norm.size)).maxOrNull() ?: 0f
        return monotonicRising && peakAhead >= peakThreshold * 0.75f &&
            norm[i] < peakThreshold * 0.85f
    }

    private fun precededBySustainedRise(
        norm: List<Float>,
        i: Int,
        sustainedThreshold: Float,
        peakThreshold: Float,
    ): Boolean {
        if (i < 1) return false
        val lookback = minOf(RISE_LOOKBACK_WINDOWS, i + 1)
        val window = norm.subList(i + 1 - lookback, i + 1)
        val rises = window.zipWithNext().all { (a, b) -> b >= a - 0.005f }
        return rises && window.last() >= peakThreshold * 0.7f
    }

    /** Simple percentile without sorting twice; uses sorted indices directly. */
    private fun quantile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun mergeWindows(
        windows: List<Window>,
        kinds: List<SceneRegionKind>,
        energies: List<Float>,
        maxEnergy: Float,
    ): List<SceneRegion> {
        val out = ArrayList<SceneRegion>()
        var currentKind: SceneRegionKind? = null
        var regionStart = 0L
        var regionEnd = 0L
        var regionEnergySum = 0f
        var regionCount = 0

        fun flush() {
            val kind = currentKind ?: return
            val avg = if (regionCount > 0) regionEnergySum / regionCount else 0f
            val confidence = when (kind) {
                SceneRegionKind.SILENCE, SceneRegionKind.QUIET -> (1f - avg / 0.35f).coerceIn(0.4f, 0.95f)
                SceneRegionKind.BUILD_UP -> 0.72f
                SceneRegionKind.DROP -> if (avg >= 0.65f) 0.78f else 0.6f
                SceneRegionKind.SUSTAINED -> 0.65f
            }
            out += SceneRegion(
                startMs = regionStart,
                endMs = regionEnd,
                kind = kind,
                confidence = confidence,
                energy = (avg / maxEnergy).coerceIn(0f, 1f),
            )
        }

        for (i in windows.indices) {
            val w = windows[i]
            val k = kinds[i]
            if (k == currentKind) {
                regionEnd = w.endMs
                regionEnergySum += w.energy
                regionCount++
            } else {
                flush()
                currentKind = k
                regionStart = w.startMs
                regionEnd = w.endMs
                regionEnergySum = w.energy
                regionCount = 1
            }
        }
        flush()

        // Shorten tiny windows into neighbours where possible.
        return out.filter { it.endMs - it.startMs >= 120 }
    }
}
