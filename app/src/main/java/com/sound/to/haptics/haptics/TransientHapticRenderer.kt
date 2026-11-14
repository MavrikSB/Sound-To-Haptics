package com.sound.to.haptics.haptics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioManager
import android.util.Log
import com.sound.to.haptics.settings.AppSettings
import kotlin.math.roundToInt

/**
 * Which rendering strategy this device can actually support, from richest
 * to safest (spec section 21: "rich effect -> simpler expressive effect ->
 * safe minimal fallback", never "rich effect -> 500 identical buzzy
 * vibrations"). Detected once per player instance from real capability
 * queries, never assumed from OEM/model name.
 */
private enum class RenderTier {
    /** Composed primitives including THUD/TICK-family — full envelope-family rendering. */
    PRIMITIVES_RICH,

    /** Composed primitives limited to CLICK + rise/fall (no THUD/TICK) — still expressive, less rounded. */
    PRIMITIVES_BASIC,

    /** No composition support, but real amplitude control — shape attack/body/decay via createWaveform steps. */
    WAVEFORM_AMPLITUDE,

    /** No amplitude control either — safe minimal fallback: fewer, duration-differentiated one-shots only. */
    ONE_SHOT_BASIC,
}

/**
 * The Transient Mode haptic renderer: the "Haptic Renderer" stage of the
 * spec's signal-flow diagram (section 29), and the direct replacement for
 * the old [PrimitiveBeatPlayer] "peak -> fixed thud" behaviour.
 *
 * Renders a pre-composed [TransientAnalysis] event timeline by polling the
 * host [MediaPlayer]'s position (same mechanism the old player used — this
 * project has no sample-accurate alternative without baking a second audio
 * file) but adds a small lookahead scheduling stage (spec section 17): once
 * an event is within [TransientConfig.LOOKAHEAD_MS] of the polled position,
 * it is scheduled via a precise `Handler.postDelayed` instead of waiting for
 * the next coarse poll tick, shaving poll jitter off the perceived
 * audio/haptic offset without ever firing early.
 *
 * Every event type gets a genuinely different physical rendering (attack ->
 * body -> decay approximated by sequencing 1-3 composed primitives with
 * calculated delays/scales, per [TransientConfig]) — this is the actual
 * fix for "every audio peak produces the same thud" (spec section 2).
 *
 * The user's haptic-strength setting is applied here, at final render time,
 * multiplying every primitive's scale — never earlier in the pipeline — so
 * relative intensity ordering between event types (kick > snare > texture)
 * survives any strength setting (spec section 23).
 */
class TransientHapticRenderer(context: Context) {

    companion object {
        private const val TAG = "SoundToHaptics"

        /** Used when the system reports nothing (or an out-of-range estimate). */
        private const val DEFAULT_OUTPUT_LATENCY_MS = 40L

        /** True if this device has *any* usable rendering path (even the safest fallback tier). */
        fun isSupported(context: Context): Boolean = vibratorFrom(context)?.hasVibrator() == true

        private fun vibratorFrom(context: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    private val vibrator = vibratorFrom(context)
    private val rendererThread = HandlerThread("TransientHapticRenderer").apply { start() }
    private val handler = Handler(rendererThread.looper)
    private val lookaheadToken = Any()

    /**
     * Output latency, in milliseconds, from the system.
     *
     * `MediaPlayer.currentPosition` is the position written to the audio
     * pipeline — not what the listener hears. The audible sound trails by the
     * pipeline's output latency, so events scheduled against the raw position
     * clock fire *before* the beat is heard — "haptics ahead of the music".
     * Shifting due times forward by [outputLatencyMs] cancels that offset.
     *
     * If a device turns out to report a sink-timed position clock instead
     * (listener hears exactly the reported position), flip the sign of the
     * offset in [tick] — one line. Physical verification required; this
     * direction follows the AudioTrack submission-position semantics.
     */
    private val outputLatencyMs: Long by lazy {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // No PROPERTY_OUTPUT_LATENCY exists; the documented probe is
        // framesPerBuffer / sampleRate for the output path, in ms.
        val frames = runCatching { am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }.getOrNull()?.toLongOrNull()
        val rate = runCatching { am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }.getOrNull()?.toLongOrNull()
        val estimate = if (frames != null && frames > 0 && rate != null && rate > 0) (frames * 1000L) / rate else null
        // Sanity clamp: an estimate outside [4, 500] ms is treated as missing.
        val latency = estimate?.takeIf { it in 4..500 } ?: DEFAULT_OUTPUT_LATENCY_MS
        Log.d(TAG, "output latency ~ ${latency}ms (frames=$frames rate=$rate)")
        latency
    }

    private val tier: RenderTier = detectTier()
    private var hasThud = false
    private var hasTick = false
    private var hasLowTick = false
    private var hasRiseFall = false

    private var events: List<TransientEvent> = emptyList()
    private var nextEventIndex = 0
    private var scheduledUpToIndex = 0
    private var lastPolledPositionMs = -1L
    private var positionProvider: (() -> Int)? = null
    private var isRunning = false

    // For the debug menu's live "events/sec" readout.
    private val recentFireTimestamps = ArrayDeque<Long>()

    init {
        val v = vibrator
        if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasThud = runCatching { v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) }.getOrDefault(false)
            hasLowTick = runCatching { v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) }.getOrDefault(false)
        }
        if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasTick = runCatching { v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) }.getOrDefault(false)
            hasRiseFall = runCatching {
                v.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                )
            }.getOrDefault(false)
        }
    }

    private fun detectTier(): RenderTier {
        val v = vibrator ?: return RenderTier.ONE_SHOT_BASIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val click = runCatching { v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) }.getOrDefault(false)
            val thudOrTick = runCatching {
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) ||
                    v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            }.getOrDefault(false)
            if (click && thudOrTick) return RenderTier.PRIMITIVES_RICH
            if (click) return RenderTier.PRIMITIVES_BASIC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) return RenderTier.WAVEFORM_AMPLITUDE
        return RenderTier.ONE_SHOT_BASIC
    }

    /** Loads the fully-composed event timeline this renderer will play. Safe to call while stopped. */
    fun load(analysis: TransientAnalysis?) {
        events = analysis?.events?.sortedBy { it.timestampMs }.orEmpty()
        nextEventIndex = 0
        scheduledUpToIndex = 0
        lastPolledPositionMs = -1L
    }

    /** Starts polling [positionMsProvider]. Call [stop] on pause; a seek made while stopped is picked up on the next [start]. */
    fun start(positionMsProvider: () -> Int) {
        if (vibrator == null) {
            Log.w(TAG, "TransientHapticRenderer.start() called with no vibrator available")
            return
        }
        positionProvider = positionMsProvider
        resyncIndexToPosition(positionMsProvider())
        isRunning = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacksAndMessages(lookaheadToken)
        TransientDiagnostics.clearLive()
    }

    /**
     * Explicit seek notification (spec section 24: "never allow haptic
     * events from the old timestamp to fire after a seek"). Discards any
     * already-scheduled lookahead callbacks and re-anchors the event index
     * to [newPositionMs] immediately, rather than waiting for the next
     * coarse poll tick's own drift detection to notice — a lookahead-
     * scheduled fire() could otherwise still land a few milliseconds after
     * a seek that happened moments before the next tick.
     */
    fun notifySeek(newPositionMs: Int) {
        handler.removeCallbacksAndMessages(lookaheadToken)
        resyncIndexToPosition(newPositionMs)
    }

    fun release() {
        stop()
        positionProvider = null
        // Done with the thread for good. The analyzer/playback path builds a
        // fresh renderer via lazy {} for the next session, which starts its
        // own thread.
        rendererThread.quitSafely()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            tick()
            handler.postDelayed(this, TransientConfig.POLL_INTERVAL_MS)
        }
    }

    private fun tick() {
        val provider = positionProvider ?: return
        val position = provider()

        // Seek detection: identical logic/rationale to the old PrimitiveBeatPlayer
        // — a position jump bigger than a few poll intervals means a seek
        // happened, so the event index (and any stale lookahead schedules)
        // must be discarded rather than trusted (spec section 24).
        val expectedDrift = if (lastPolledPositionMs < 0) 0L else position - lastPolledPositionMs
        if (expectedDrift < 0 || expectedDrift > TransientConfig.POLL_INTERVAL_MS * TransientConfig.RESYNC_DRIFT_MULTIPLIER) {
            handler.removeCallbacksAndMessages(lookaheadToken)
            resyncIndexToPosition(position)
        }

        // Catch-up: anything already due gets fired immediately. Due means
        // the *heard-time* of the event (media time + output latency) has
        // arrived, not the submission-time of the corresponding audio frame.
        while (nextEventIndex < events.size && events[nextEventIndex].timestampMs + outputLatencyMs <= position) {
            fire(events[nextEventIndex])
            nextEventIndex++
            if (scheduledUpToIndex < nextEventIndex) scheduledUpToIndex = nextEventIndex
        }

        // Lookahead: schedule anything due within the lookahead window via a
        // precisely-delayed callback instead of waiting for the next coarse
        // poll tick (spec section 17) — this is only ever a couple of events
        // at a time (bounded by density control upstream), never unbounded.
        val lookaheadHorizon = position + TransientConfig.LOOKAHEAD_MS
        while (scheduledUpToIndex < events.size && events[scheduledUpToIndex].timestampMs + outputLatencyMs <= lookaheadHorizon) {
            val event = events[scheduledUpToIndex]
            val delay = (event.timestampMs + outputLatencyMs - position).coerceAtLeast(0L)
            handler.postDelayed({ fire(event) }, lookaheadToken, delay)
            scheduledUpToIndex++
        }
        if (nextEventIndex < scheduledUpToIndex) nextEventIndex = scheduledUpToIndex

        lastPolledPositionMs = position.toLong()
    }

    private fun resyncIndexToPosition(positionMs: Int) {
        nextEventIndex = events.indexOfFirst { it.timestampMs > positionMs }.let { if (it < 0) events.size else it }
        scheduledUpToIndex = nextEventIndex
        lastPolledPositionMs = positionMs.toLong()
    }

    private fun fire(event: TransientEvent) {
        val v = vibrator ?: return
        try {
            // Applied here, at final render time, on top of an already fully
            // musically-shaped intensity — never earlier (spec section 23).
            val userStrength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
            val scale = (event.intensity * userStrength).coerceIn(0f, 1f)
            if (scale <= 0.008f) return

            when (tier) {
                RenderTier.PRIMITIVES_RICH -> fireRich(v, event, scale)
                RenderTier.PRIMITIVES_BASIC -> fireBasic(v, event, scale)
                RenderTier.WAVEFORM_AMPLITUDE -> fireWaveform(v, event, scale)
                RenderTier.ONE_SHOT_BASIC -> fireOneShot(v, event, scale)
            }

            val now = System.currentTimeMillis()
            recentFireTimestamps.addLast(now)
            while (recentFireTimestamps.isNotEmpty() && now - recentFireTimestamps.first() > 1000L) recentFireTimestamps.removeFirst()
            val positionNow = positionProvider?.invoke() ?: 0
            TransientDiagnostics.reportFired(event.type, event.layer, scale, (event.timestampMs + outputLatencyMs - positionNow), recentFireTimestamps.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire transient event ${event.type}", e)
        }
    }

    // ── Tier 1: full composed-primitive family (attack/body/decay via sequencing) ──
    private fun fireRich(v: Vibrator, event: TransientEvent, scale: Float) {
        val c = VibrationEffect.startComposition()
        when (event.type) {
            TransientEventType.KICK -> {
                // Rounded, deep, weighty: THUD carries all the low-end
                // roundness Android's own docs describe ("thumpy, low,
                // percussive... knocking on hollow wood"). A second, quieter
                // THUD after a short delay approximates the "slightly
                // longer decay" the spec calls for without needing the
                // API36 envelope builder.
                if (hasThud) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale, 0)
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * TransientConfig.KICK_BODY_SCALE, TransientConfig.KICK_BODY_DELAY_MS.toInt())
                } else {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale, 0)
                }
                if (event.isMajorAccent && hasThud) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * TransientConfig.MAJOR_TAIL_SCALE, TransientConfig.MAJOR_TAIL_DELAY_MS.toInt())
                }
            }
            TransientEventType.SNARE -> {
                // Short, defined, sharper, clearly different from a kick:
                // CLICK for the crack, a light THUD underneath for body
                // (much quieter than a kick's), and a short TICK tail for
                // definition — never THUD alone, which is what made kicks
                // and snares feel identical in the old renderer.
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                if (hasThud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * 0.28f, 0)
                if (hasTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * TransientConfig.SNARE_TAIL_SCALE, TransientConfig.SNARE_TAIL_DELAY_MS.toInt())
            }
            TransientEventType.HIHAT -> {
                // Very short, subtle, crisp — never a full-power vibration.
                val hihatScale = (scale * 0.7f).coerceAtMost(0.55f)
                if (hasLowTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, hihatScale, 0)
                else if (hasTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, hihatScale, 0)
                else c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, hihatScale * 0.6f, 0)
            }
            TransientEventType.PERCUSSIVE_OTHER -> {
                // A real onset that isn't cleanly kick/snare/hat: sharp
                // attack, short decay — CLICK plus a brief fall for shape.
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                if (hasRiseFall) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, scale * 0.4f, 6)
            }
            TransientEventType.BASS_BED -> {
                // Continuous low-level texture: a single soft, rounded
                // pulse, deliberately never combined with CLICK (that
                // would make the "always-on" bed feel like a stream of
                // small hits rather than a gentle presence).
                if (hasThud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale, 0)
                else if (hasLowTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0)
                else return // no primitive on this device can render this softly — skip rather than buzz
            }
        }
        v.vibrate(c.compose())
    }

    // ── Tier 2: CLICK + rise/fall only (no THUD/TICK) ──────────────────
    private fun fireBasic(v: Vibrator, event: TransientEvent, scale: Float) {
        val c = VibrationEffect.startComposition()
        when (event.type) {
            TransientEventType.KICK -> {
                // No THUD available: approximate "rounded, slower attack"
                // with SLOW_RISE, then a QUICK_FALL tail for the decay —
                // the closest available shape to a kick's envelope.
                if (hasRiseFall) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale, 0)
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, scale * TransientConfig.KICK_BODY_SCALE, TransientConfig.KICK_BODY_DELAY_MS.toInt())
                } else {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                }
            }
            TransientEventType.SNARE, TransientEventType.PERCUSSIVE_OTHER -> {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                if (hasRiseFall) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, scale * 0.35f, 5)
            }
            TransientEventType.HIHAT -> {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, (scale * 0.5f).coerceAtMost(0.45f), 0)
            }
            TransientEventType.BASS_BED -> {
                if (!hasRiseFall) return
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale, 0)
            }
        }
        v.vibrate(c.compose())
    }

    // ── Tier 3: legacy on/off amplitude waveform, shaped per type ───────
    private fun fireWaveform(v: Vibrator, event: TransientEvent, scale: Float) {
        val shape: List<Pair<Long, Float>> = when (event.type) {
            // (durationMs, amplitudeFraction) steps approximating attack -> body -> decay.
            TransientEventType.KICK -> listOf(14L to 1f, 40L to 0.55f, 60L to 0.22f)
            TransientEventType.SNARE -> listOf(10L to 1f, 24L to 0.3f)
            TransientEventType.HIHAT -> listOf(10L to 0.55f)
            TransientEventType.PERCUSSIVE_OTHER -> listOf(10L to 0.9f, 20L to 0.35f)
            TransientEventType.BASS_BED -> listOf(30L to 0.4f, 60L to 0.5f, 60L to 0.15f)
        }
        val timings = LongArray(shape.size) { shape[it].first }
        val amplitudes = IntArray(shape.size) { (shape[it].second * scale * 255f).roundToInt().coerceIn(0, 255) }
        v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    // ── Tier 4: safest fallback — duration-differentiated one-shots, reduced scope ──
    private fun fireOneShot(v: Vibrator, event: TransientEvent, scale: Float) {
        // On this tier the vibrator likely has no amplitude control at all
        // (any positive amplitude reads as "full strength" — see Android's
        // own guidance), so texture/bed-layer events are skipped entirely
        // rather than producing an undifferentiated buzz (spec section 21).
        if (event.layer != TransientLayer.ACCENT) return
        val durationMs = when (event.type) {
            TransientEventType.KICK -> if (event.isMajorAccent) 140 else 95
            TransientEventType.SNARE -> 45
            TransientEventType.PERCUSSIVE_OTHER -> 55
            TransientEventType.HIHAT, TransientEventType.BASS_BED -> return
        }
        val amplitude = (scale * 255f).roundToInt().coerceIn(1, 255)
        v.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), amplitude))
    }
}
