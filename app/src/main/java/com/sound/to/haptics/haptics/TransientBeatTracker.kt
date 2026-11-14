package com.sound.to.haptics.haptics

import kotlin.math.abs
import kotlin.math.roundToInt

/** Result of [TransientBeatTracker.track]. */
data class BeatEstimate(
    val bpm: Float,
    val confidence: Float,
    /** Estimated phase offset (ms) of the first beat, for [isNearBeat]. */
    val phaseMs: Long,
)

/**
 * Lightweight, approximate tempo/beat estimator (spec section 16).
 *
 * This deliberately does not aim for perfect BPM recognition or beat-exact
 * quantization. It exists only to give [TransientEventPrioritizer] a
 * *supporting* signal — "is this onset near where a beat would fall?" — so
 * rhythmically-supported hits get a small priority nudge without ever
 * snapping their timestamp (spec: "beat tracking should be a supporting
 * feature, not a timing prison").
 *
 * Method: build a histogram of inter-onset intervals (IOIs) between
 * candidate onset timestamps, restricted to a plausible tempo range
 * ([TransientConfig.MIN_PLAUSIBLE_BPM]..[TransientConfig.MAX_PLAUSIBLE_BPM]),
 * and take the most common interval as the beat period. This is the
 * standard, simple "IOI histogram" approach to tempo estimation — cheap
 * (single pass + small histogram), and robust enough for a *supporting*
 * signal even though a dedicated beat-tracking model would do better.
 */
object TransientBeatTracker {

    fun track(onsetTimestampsMs: List<Long>): BeatEstimate {
        if (onsetTimestampsMs.size < 4) return BeatEstimate(0f, 0f, 0L)

        val minPeriodMs = (60_000f / TransientConfig.MAX_PLAUSIBLE_BPM).toInt()
        val maxPeriodMs = (60_000f / TransientConfig.MIN_PLAUSIBLE_BPM).toInt()

        // Histogram bucketed to 5ms resolution — fine enough to distinguish
        // tempos a few BPM apart without an enormous table.
        val bucketMs = 5
        val bucketCount = (maxPeriodMs - minPeriodMs) / bucketMs + 1
        val histogram = IntArray(bucketCount)

        val sorted = onsetTimestampsMs.sorted()
        for (i in sorted.indices) {
            for (j in i + 1 until min(sorted.size, i + 8)) {
                val interval = (sorted[j] - sorted[i]).toInt()
                if (interval < minPeriodMs) continue
                if (interval > maxPeriodMs) break
                val bucket = (interval - minPeriodMs) / bucketMs
                if (bucket in histogram.indices) histogram[bucket]++
            }
        }

        var bestBucket = -1
        var bestCount = 0
        for (b in histogram.indices) {
            if (histogram[b] > bestCount) {
                bestCount = histogram[b]
                bestBucket = b
            }
        }
        if (bestBucket < 0 || bestCount == 0) return BeatEstimate(0f, 0f, 0L)

        val periodMs = minPeriodMs + bestBucket * bucketMs
        val bpm = 60_000f / periodMs
        val totalVotes = histogram.sum().coerceAtLeast(1)
        val confidence = (bestCount.toFloat() / totalVotes).coerceIn(0f, 1f)
        val phase = sorted.first() % periodMs
        return BeatEstimate(bpm, confidence, phase)
    }

    private fun min(a: Int, b: Int): Int = if (a < b) a else b

    /** True if [timestampMs] falls within [TransientConfig.BEAT_PROXIMITY_TOLERANCE_MS] of an estimated beat grid line. */
    fun isNearBeat(timestampMs: Long, estimate: BeatEstimate): Boolean {
        if (estimate.bpm <= 0f || estimate.confidence < 0.12f) return false
        val periodMs = (60_000f / estimate.bpm).roundToInt()
        if (periodMs <= 0) return false
        val offsetFromPhase = (timestampMs - estimate.phaseMs).mod(periodMs.toLong())
        val distanceToNearestBeat = min(offsetFromPhase.toInt(), periodMs - offsetFromPhase.toInt())
        return distanceToNearestBeat <= TransientConfig.BEAT_PROXIMITY_TOLERANCE_MS
    }
}
