package com.sound.to.haptics.haptics

import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** One named synthetic test signal + its expected perceptual result, for developer verification (spec section 31). */
enum class TransientQualityTest(val label: String, val expectation: String) {
    PURE_LOW_PULSE("A — Pure low pulse", "Rounded, deep impact"),
    PURE_HIGH_PULSE("B — Pure high pulse", "Small, crisp sensation"),
    KICK_PATTERN("C — Repeated kick pattern", "Consistent rhythm, no buzz"),
    SUSTAINED_BASS("D — Sustained bass", "Continuous, subtle texture"),
    KICK_AND_SNARE("E — Kick + snare", "Kick rounded/heavier, snare sharper/shorter"),
    DENSE_HIHATS("F — Dense hi-hats", "Controlled texture, not hundreds of identical impacts"),
    QUIET_MUSIC("G — Quiet music", "Subtle but still perceptible"),
    LOUD_MASTERED("H — Loud mastered music", "Strong but not permanently maxed out"),
}

/**
 * Generates synthetic PCM signals with known, deliberate characteristics
 * (spec section 31's Tests A-H) and runs them through the *real* Transient
 * Mode analysis pipeline ([TransientAudioAnalyzer]) — this is a genuine
 * end-to-end pipeline test, not a canned/hand-authored event list, so it
 * actually exercises feature extraction, onset detection, classification
 * and composition exactly as a real track would.
 *
 * Used only from the debug menu ([DebugToolsViewModel.runQualityTest]); has
 * no effect on and is not reachable from any real playback path.
 */
object TransientQualitySignalGenerator {

    private const val SAMPLE_RATE = 44100

    fun generate(context: android.content.Context, test: TransientQualityTest): TransientAnalysis? {
        val pcmFile = File(context.cacheDir, "transient_quality_${test.name}.pcm")
        writeSignal(pcmFile, test)
        val analysis = TransientAudioAnalyzer.analyze(pcmFile, SAMPLE_RATE, channelCount = 1, bassEmphasis = 0.7f, hapticDensity = 0.6f)
        pcmFile.delete()
        return analysis
    }

    private fun writeSignal(file: File, test: TransientQualityTest) {
        val durationSeconds = when (test) {
            TransientQualityTest.SUSTAINED_BASS, TransientQualityTest.QUIET_MUSIC, TransientQualityTest.LOUD_MASTERED -> 6
            else -> 4
        }
        val totalSamples = SAMPLE_RATE * durationSeconds
        val samples = FloatArray(totalSamples)
        val random = Random(42)

        fun addPulse(centerSample: Int, freqHz: Double, amplitude: Float, decaySamples: Int) {
            val start = (centerSample - decaySamples / 4).coerceAtLeast(0)
            val end = (centerSample + decaySamples).coerceAtMost(totalSamples - 1)
            for (i in start..end) {
                val t = (i - centerSample).toDouble() / SAMPLE_RATE
                val envelope = if (t < 0) 1.0 else Math.exp(-t * SAMPLE_RATE / decaySamples.toDouble() * 3.0)
                samples[i] += (amplitude * envelope * sin(2.0 * PI * freqHz * i / SAMPLE_RATE)).toFloat()
            }
        }

        when (test) {
            TransientQualityTest.PURE_LOW_PULSE -> {
                // A handful of isolated 60Hz thumps — pure sub-bass, no
                // harmonics, no mid/high content at all.
                for (beat in 0 until durationSeconds) addPulse(beat * SAMPLE_RATE + SAMPLE_RATE / 2, 60.0, 0.85f, SAMPLE_RATE / 6)
            }
            TransientQualityTest.PURE_HIGH_PULSE -> {
                // Isolated 9kHz ticks — pure high-frequency, no bass at all.
                for (beat in 0 until durationSeconds) addPulse(beat * SAMPLE_RATE + SAMPLE_RATE / 2, 9000.0, 0.6f, SAMPLE_RATE / 40)
            }
            TransientQualityTest.KICK_PATTERN -> {
                // A steady 120 BPM four-on-the-floor kick — every beat
                // identical in strength, testing rhythm consistency without
                // any density-control edge cases.
                val beatSamples = (SAMPLE_RATE * 60.0 / 120.0).toInt()
                var t = beatSamples / 2
                while (t < totalSamples) {
                    addPulse(t, 55.0, 0.8f, SAMPLE_RATE / 8)
                    t += beatSamples
                }
            }
            TransientQualityTest.SUSTAINED_BASS -> {
                // A held low note with slow amplitude movement (a swell),
                // no discrete transient anywhere — must drive the
                // continuous Musical Bed layer, not silence.
                for (i in 0 until totalSamples) {
                    val slow = 0.5 + 0.5 * sin(2.0 * PI * i / (SAMPLE_RATE * 3.0))
                    samples[i] += (0.5 * slow * sin(2.0 * PI * 70.0 * i / SAMPLE_RATE)).toFloat()
                }
            }
            TransientQualityTest.KICK_AND_SNARE -> {
                // Alternating kick (low, rounded) and snare (broadband
                // mid/high, sharp) at a steady tempo — the classic test for
                // "these two must feel different."
                val beatSamples = (SAMPLE_RATE * 60.0 / 100.0).toInt()
                var t = beatSamples / 2
                var isKick = true
                while (t < totalSamples) {
                    if (isKick) {
                        addPulse(t, 55.0, 0.85f, SAMPLE_RATE / 8)
                    } else {
                        addPulse(t, 1800.0, 0.7f, SAMPLE_RATE / 20)
                        addPulse(t, 3200.0, 0.5f, SAMPLE_RATE / 24)
                        // A touch of broadband noise gives the snare its
                        // characteristic sharp flux, distinct from a pure tone.
                        for (i in t until (t + SAMPLE_RATE / 30).coerceAtMost(totalSamples)) {
                            samples[i] += (random.nextFloat() - 0.5f) * 0.25f
                        }
                    }
                    isKick = !isKick
                    t += beatSamples
                }
            }
            TransientQualityTest.DENSE_HIHATS -> {
                // Very fast 16th-note hi-hats at 160 BPM — the key density-
                // control stress test (spec Test F): must not become an
                // uncontrolled buzz.
                val stepSamples = (SAMPLE_RATE * 60.0 / 160.0 / 4.0).toInt().coerceAtLeast(1)
                var t = stepSamples / 2
                while (t < totalSamples) {
                    addPulse(t, 9500.0, 0.45f, SAMPLE_RATE / 60)
                    t += stepSamples
                }
            }
            TransientQualityTest.QUIET_MUSIC -> {
                // A full musical pattern (kick+snare+hats) but at very low
                // overall amplitude — must still be perceptible (spec Test G),
                // not silent, thanks to local/adaptive normalization.
                val beatSamples = (SAMPLE_RATE * 60.0 / 100.0).toInt()
                var t = beatSamples / 2
                var beatIndex = 0
                while (t < totalSamples) {
                    if (beatIndex % 2 == 0) addPulse(t, 55.0, 0.09f, SAMPLE_RATE / 8)
                    else addPulse(t, 2200.0, 0.06f, SAMPLE_RATE / 20)
                    if (beatIndex % 1 == 0) addPulse(t + beatSamples / 2, 9000.0, 0.03f, SAMPLE_RATE / 50)
                    beatIndex++
                    t += beatSamples
                }
            }
            TransientQualityTest.LOUD_MASTERED -> {
                // The same musical pattern as QUIET_MUSIC but heavily
                // compressed/near-clipped throughout (spec Test H) — must
                // not stay permanently maxed out; local normalization
                // should still reveal dynamic differences between hits.
                val beatSamples = (SAMPLE_RATE * 60.0 / 100.0).toInt()
                var t = beatSamples / 2
                var beatIndex = 0
                // A dense, loud broadband bed simulating heavy mastering,
                // with slightly stronger pulses layered on top so a real
                // dynamic difference still technically exists in the signal
                // (density control must still surface it, not flatten it).
                for (i in 0 until totalSamples) samples[i] += (random.nextFloat() - 0.5f) * 0.5f
                while (t < totalSamples) {
                    if (beatIndex % 2 == 0) addPulse(t, 55.0, 0.95f, SAMPLE_RATE / 8)
                    else addPulse(t, 2200.0, 0.9f, SAMPLE_RATE / 20)
                    beatIndex++
                    t += beatSamples
                }
                // Simulate brickwall limiting.
                for (i in samples.indices) samples[i] = samples[i].coerceIn(-0.98f, 0.98f)
            }
        }

        FileOutputStream(file).use { out ->
            val buffer = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val clamped = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                buffer[i * 2] = (clamped and 0xFF).toByte()
                buffer[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
            }
            out.write(buffer)
        }
    }
}
