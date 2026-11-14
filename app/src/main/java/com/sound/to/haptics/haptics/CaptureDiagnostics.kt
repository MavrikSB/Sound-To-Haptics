package com.sound.to.haptics.haptics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Technical capture facts shared with the debug screen without leaking them into normal UI copy. */
data class CaptureDiagnosticsState(
    val stage: String = "Idle",
    val projectionGranted: Boolean = false,
    val projectionActive: Boolean = false,
    val audioRecordState: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bufferBytes: Int = 0,
    val bytesWritten: Long = 0L,
    val hadSignal: Boolean = false,
    val pcmValid: Boolean = false,
    val pcmDurationMs: Long = 0L,
    val pcmRms: Float = 0f,
    val pcmPeak: Float = 0f,
    val clippedSamples: Long = 0L,
    val lastError: String? = null,
)

object CaptureDiagnostics {
    private val _state = MutableStateFlow(CaptureDiagnosticsState())
    val state: StateFlow<CaptureDiagnosticsState> = _state.asStateFlow()

    fun reset() {
        _state.value = CaptureDiagnosticsState()
    }

    fun permissionResult(granted: Boolean) {
        _state.value = _state.value.copy(
            projectionGranted = granted,
            stage = if (granted) "Projection consent granted" else "Projection consent denied",
            lastError = if (granted) null else "The system playback-capture consent dialog was denied.",
        )
    }

    fun projection(active: Boolean) {
        _state.value = _state.value.copy(
            projectionActive = active,
            stage = if (active) "MediaProjection active" else "MediaProjection stopped",
            lastError = if (active) null else _state.value.lastError,
        )
    }

    fun recorderCreated(sampleRate: Int, channels: Int, bufferBytes: Int, state: Int) {
        _state.value = _state.value.copy(
            stage = "AudioRecord initialized",
            sampleRate = sampleRate,
            channelCount = channels,
            bufferBytes = bufferBytes,
            audioRecordState = state,
            lastError = null,
        )
    }

    fun capturing() {
        _state.value = _state.value.copy(stage = "Capturing PCM", lastError = null)
    }

    fun progress(bytes: Long, signal: Boolean) {
        _state.value = _state.value.copy(bytesWritten = bytes, hadSignal = signal)
    }

    fun failure(message: String) {
        _state.value = _state.value.copy(stage = "Capture failed", lastError = message)
    }

    fun finished(bytes: Long, signal: Boolean) {
        _state.value = _state.value.copy(
            stage = "Capture stopped",
            bytesWritten = bytes,
            hadSignal = signal,
        )
    }

    fun pcmValidated(result: PcmValidationResult) {
        _state.value = _state.value.copy(
            stage = if (result.valid) "PCM validated" else "PCM rejected",
            pcmValid = result.valid,
            pcmDurationMs = result.durationMs,
            pcmRms = result.rms,
            pcmPeak = result.peak,
            clippedSamples = result.clippedSamples,
            lastError = if (result.valid) _state.value.lastError else result.reason,
        )
    }
}
