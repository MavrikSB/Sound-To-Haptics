package com.sound.to.haptics.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sound.to.haptics.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Legacy direct-vibrator renderer for old serialized envelope experiments.
 *
 * The active Offline and BitChord playback paths do not instantiate this
 * class: their persisted haptic-enabled OGG is the source of truth and is
 * played through Android's haptic-aware MediaPlayer channel. It remains
 * isolated for compatibility/debug tooling and is intentionally not a
 * fallback when OGG playback fails.
 *
 * If a caller uses this compatibility component directly, it stays in step
 * with the supplied position and only restarts a waveform after meaningful
 * drift rather than on every position update.
 */
class VibrationEnvelopePlayer(context: Context) {

    companion object {
        private const val DRIFT_TOLERANCE_MS = 400L
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    data class EngineState(val isActive: Boolean = false)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var envelope: HapticEnvelope? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var tickerJob: Job? = null

    private var anchorPositionMs = 0L
    private var anchorRealtimeMs = 0L
    private var isPaused = true

    // Where the currently-running waveform thinks it is, so drift can be measured.
    private var drivenAtPositionMs = -1L
    private var drivenAtRealtimeMs = 0L
    private var drivenStrength = -1f

    /** Loads a serialized pattern and arms the sync loop. */
    fun load(file: File): Boolean = load(loadHapticEnvelope(file))

    /** Loads an in-memory pattern and arms the sync loop. */
    fun load(pattern: HapticEnvelope?): Boolean {
        if (pattern == null || pattern.timingsMs.isEmpty() || pattern.amplitudes.size != pattern.timingsMs.size) return false
        release()
        envelope = pattern
        _state.value = EngineState(isActive = true)
        startTicker()
        return true
    }

    /**
     * Feed this whenever you have a fresh position/pause reading.
     *
     * Callers like BitChordViewModel call this on every emission from
     * BitChord's player position flow, which fires far more often
     * than once a second even during ordinary uninterrupted playback — not
     * just on real seeks or pause/resume. The previous version restarted
     * the entire vibration waveform (driveFrom) unconditionally on every
     * call here, with no drift check at all (that check only existed in
     * the separate internal ticker below), which tore down and restarted
     * the pattern many times a second and is exactly what read as "tiny
     * rapid vibrations that increase in intensity randomly" — each restart
     * only got to play for a moment before being replaced by the next one,
     * often jumping to a different point in the dynamic range each time.
     *
     * Now this only restarts the waveform when something actually changed
     * enough to matter: a pause/resume transition, or the reported position
     * drifting from where the currently-running waveform should be by more
     * than the same ~400ms tolerance the ticker uses. Routine same-position
     * updates just refresh the anchor so drift stays measured accurately,
     * without touching the vibrator at all.
     */
    fun syncTo(positionMs: Long, paused: Boolean) {
        val pauseStateChanged = paused != isPaused
        anchorPositionMs = positionMs
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        isPaused = paused

        if (paused) {
            if (pauseStateChanged) {
                vibrator.cancel()
                drivenAtPositionMs = -1L
            }
            return
        }

        val currentStrength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
        if (pauseStateChanged || drivenAtPositionMs < 0 || abs(currentStrength - drivenStrength) > 0.01f) {
            driveFrom(positionMs)
            return
        }

        val impliedByWaveform = drivenAtPositionMs + (SystemClock.elapsedRealtime() - drivenAtRealtimeMs)
        if (abs(positionMs - impliedByWaveform) > DRIFT_TOLERANCE_MS) {
            driveFrom(positionMs)
        }
        // else: still tracking closely enough, let the current waveform keep playing.
    }

    private fun driveFrom(positionMs: Long) {
        val env = envelope ?: return
        val slice = env.sliceFrom(positionMs)
        if (slice == null) {
            vibrator.cancel()
            drivenAtPositionMs = -1L
            return
        }
        val (timings, amplitudes) = slice
        val strength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
        val scaledAmplitudes = amplitudes.map { (it * strength).toInt().coerceIn(0, 255) }.toIntArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, scaledAmplitudes, -1))
            } catch (_: Exception) {
            }
        }
        drivenAtPositionMs = positionMs
        drivenAtRealtimeMs = SystemClock.elapsedRealtime()
        drivenStrength = strength
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(700)
                if (isPaused) continue
                val expected = anchorPositionMs + (SystemClock.elapsedRealtime() - anchorRealtimeMs)
                val impliedByWaveform = if (drivenAtPositionMs >= 0) {
                    drivenAtPositionMs + (SystemClock.elapsedRealtime() - drivenAtRealtimeMs)
                } else {
                    -1L
                }
                val currentStrength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
                if (impliedByWaveform < 0 ||
                    abs(expected - impliedByWaveform) > DRIFT_TOLERANCE_MS ||
                    abs(currentStrength - drivenStrength) > 0.01f
                ) {
                    driveFrom(expected)
                }
            }
        }
    }

    fun stop() = release()

    private fun release() {
        tickerJob?.cancel()
        tickerJob = null
        vibrator.cancel()
        envelope = null
        drivenAtPositionMs = -1L
        drivenStrength = -1f
        _state.value = EngineState(isActive = false)
    }
}
