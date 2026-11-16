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

private enum class RenderTier {

    PRIMITIVES_RICH,

    PRIMITIVES_BASIC,

    WAVEFORM_AMPLITUDE,

    ONE_SHOT_BASIC,
}

class TransientHapticRenderer(context: Context) {

    companion object {
        private const val TAG = "SoundToHaptics"

        private const val DEFAULT_OUTPUT_LATENCY_MS = 40L

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

    private val outputLatencyMs: Long by lazy {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val frames = runCatching { am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }.getOrNull()?.toLongOrNull()
        val rate = runCatching { am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }.getOrNull()?.toLongOrNull()
        val estimate = if (frames != null && frames > 0 && rate != null && rate > 0) (frames * 1000L) / rate else null

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

    fun load(analysis: TransientAnalysis?) {
        events = analysis?.events?.sortedBy { it.timestampMs }.orEmpty()
        nextEventIndex = 0
        scheduledUpToIndex = 0
        lastPolledPositionMs = -1L
    }

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

    fun notifySeek(newPositionMs: Int) {
        handler.removeCallbacksAndMessages(lookaheadToken)
        resyncIndexToPosition(newPositionMs)
    }

    fun release() {
        stop()
        positionProvider = null

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

        val expectedDrift = if (lastPolledPositionMs < 0) 0L else position - lastPolledPositionMs
        if (expectedDrift < 0 || expectedDrift > TransientConfig.POLL_INTERVAL_MS * TransientConfig.RESYNC_DRIFT_MULTIPLIER) {
            handler.removeCallbacksAndMessages(lookaheadToken)
            resyncIndexToPosition(position)
        }

        while (nextEventIndex < events.size && events[nextEventIndex].timestampMs + outputLatencyMs <= position) {
            fire(events[nextEventIndex])
            nextEventIndex++
            if (scheduledUpToIndex < nextEventIndex) scheduledUpToIndex = nextEventIndex
        }

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

    private fun fireRich(v: Vibrator, event: TransientEvent, scale: Float) {
        val c = VibrationEffect.startComposition()
        when (event.type) {
            TransientEventType.KICK -> {

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

                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                if (hasThud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * 0.28f, 0)
                if (hasTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * TransientConfig.SNARE_TAIL_SCALE, TransientConfig.SNARE_TAIL_DELAY_MS.toInt())
            }
            TransientEventType.HIHAT -> {

                val hihatScale = (scale * 0.7f).coerceAtMost(0.55f)
                if (hasLowTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, hihatScale, 0)
                else if (hasTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, hihatScale, 0)
                else c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, hihatScale * 0.6f, 0)
            }
            TransientEventType.PERCUSSIVE_OTHER -> {

                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
                if (hasRiseFall) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, scale * 0.4f, 6)
            }
            TransientEventType.BASS_BED -> {

                if (hasThud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale, 0)
                else if (hasLowTick) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0)
                else return
            }
        }
        v.vibrate(c.compose())
    }

    private fun fireBasic(v: Vibrator, event: TransientEvent, scale: Float) {
        val c = VibrationEffect.startComposition()
        when (event.type) {
            TransientEventType.KICK -> {

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

    private fun fireWaveform(v: Vibrator, event: TransientEvent, scale: Float) {
        val shape: List<Pair<Long, Float>> = when (event.type) {

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

    private fun fireOneShot(v: Vibrator, event: TransientEvent, scale: Float) {

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
