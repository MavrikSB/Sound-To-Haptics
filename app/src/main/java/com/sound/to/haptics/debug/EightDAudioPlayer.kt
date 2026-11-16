package com.sound.to.haptics.debug

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class EightDAudioPlayer {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var audioTrack: AudioTrack? = null

    @Volatile var rotationHz: Float = 0.2f
    @Volatile var depth: Float = 1f
    @Volatile var toneHz: Float = 220f
    @Volatile var hapticSyncEnabled: Boolean = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _panPosition = MutableStateFlow(0.5f)
    val panPosition: StateFlow<Float> = _panPosition.asStateFlow()

    fun start(onRotationPeak: () -> Unit) {
        if (_isPlaying.value) return

        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
        _isPlaying.value = true

        job = scope.launch {
            var tonePhase = 0.0
            var panPhase = 0.0
            var lastSign = 1.0
            val framesPerBuffer = 1024
            val buffer = ShortArray(framesPerBuffer * 2)

            while (isActive) {
                for (i in 0 until framesPerBuffer) {
                    val sample = sin(tonePhase) * 0.3
                    val pan = sin(panPhase) * depth.coerceIn(0f, 1f)
                    val leftGain = ((1.0 - pan) / 2.0).coerceIn(0.0, 1.0)
                    val rightGain = ((1.0 + pan) / 2.0).coerceIn(0.0, 1.0)

                    buffer[i * 2] = (sample * leftGain * Short.MAX_VALUE).toInt().toShort()
                    buffer[i * 2 + 1] = (sample * rightGain * Short.MAX_VALUE).toInt().toShort()

                    tonePhase += 2 * PI * toneHz / sampleRate
                    if (tonePhase > 2 * PI) tonePhase -= 2 * PI
                    panPhase += 2 * PI * rotationHz / sampleRate
                    if (panPhase > 2 * PI) panPhase -= 2 * PI

                    if (hapticSyncEnabled) {
                        val sign = if (sin(panPhase) >= 0) 1.0 else -1.0
                        if (sign != lastSign) {
                            lastSign = sign
                            onRotationPeak()
                        }
                    }
                }
                _panPosition.value = ((sin(panPhase) * depth + 1f) / 2f).toFloat()
                track.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        audioTrack = null
        _isPlaying.value = false
        _panPosition.value = 0.5f
    }
}
