package com.sound.to.haptics.haptics

import kotlin.math.abs
import kotlin.math.roundToInt

data class BeatEstimate(
    val bpm: Float,
    val confidence: Float,

    val phaseMs: Long,
)

object TransientBeatTracker {

    fun track(onsetTimestampsMs: List<Long>): BeatEstimate {
        if (onsetTimestampsMs.size < 4) return BeatEstimate(0f, 0f, 0L)

        val minPeriodMs = (60_000f / TransientConfig.MAX_PLAUSIBLE_BPM).toInt()
        val maxPeriodMs = (60_000f / TransientConfig.MIN_PLAUSIBLE_BPM).toInt()

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

    fun isNearBeat(timestampMs: Long, estimate: BeatEstimate): Boolean {
        if (estimate.bpm <= 0f || estimate.confidence < 0.12f) return false
        val periodMs = (60_000f / estimate.bpm).roundToInt()
        if (periodMs <= 0) return false
        val offsetFromPhase = (timestampMs - estimate.phaseMs).mod(periodMs.toLong())
        val distanceToNearestBeat = min(offsetFromPhase.toInt(), periodMs - offsetFromPhase.toInt())
        return distanceToNearestBeat <= TransientConfig.BEAT_PROXIMITY_TOLERANCE_MS
    }
}
