package com.sound.to.haptics.haptics

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCaptureRecorder {

    data class CaptureResult(
        val pcmFile: File,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long,
        val hadSignal: Boolean,
    )

    private data class CaptureFormat(val sampleRate: Int, val channelMask: Int, val channels: Int)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var currentOutFile: File? = null
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    private var currentBufferBytes = 0
    private var captureFailure: String? = null

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile var isPaused: Boolean = false
    @Volatile private var hadSignal = false
    @Volatile var bytesWritten: Long = 0L
        private set

    fun start(
        mediaProjection: MediaProjection,
        outFile: File,
        initialOffsetMs: Long = 0L,
    ): Boolean {
        if (_isCapturing.value) return false
        if (mediaProjection == null) {
            CaptureDiagnostics.failure("MediaProjection was null when AudioRecord was requested.")
            return false
        }

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)

                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            val message = "Playback capture configuration failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, message, e)
            CaptureDiagnostics.failure(message)
            return false
        }

        val selected = createInitializedRecord(captureConfig)
        if (selected == null) {
            CaptureDiagnostics.failure(
                "AudioRecord could not initialize at 48/44.1/32 kHz with mono or stereo playback capture. " +
                    "Spotify may be denying playback capture or another recorder is active."
            )
            return false
        }
        val (record, format, bufferBytes) = selected

        outFile.parentFile?.mkdirs()
        try {

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord state ${record.recordingState} after startRecording")
            }
        } catch (e: Exception) {
            record.release()
            val message = "AudioRecord.startRecording failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, message, e)
            CaptureDiagnostics.failure(message)
            return false
        }

        audioRecord = record
        currentOutFile = outFile
        currentSampleRate = format.sampleRate
        currentChannelCount = format.channels
        currentBufferBytes = bufferBytes
        captureFailure = null
        hadSignal = false
        isPaused = false
        bytesWritten = 0L
        _isCapturing.value = true
        CaptureDiagnostics.recorderCreated(format.sampleRate, format.channels, bufferBytes, record.state)
        CaptureDiagnostics.capturing()

        recordJob = scope.launch {
            try {
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.setLength(0L)
                    val offsetMs = initialOffsetMs.coerceIn(0L, MAX_INITIAL_OFFSET_MS)
                    val offsetFrames = offsetMs * format.sampleRate / 1000L
                    writeSilenceFrames(raf, offsetFrames, format.channels)
                    val buffer = ShortArray((bufferBytes / 2).coerceAtLeast(1024))
                    var smoothed = 0f

                    while (isActive && audioRecord === record) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                            captureFailure = "AudioRecord.read returned $read; the projection or capture route ended."
                            CaptureDiagnostics.failure(captureFailure!!)
                            break
                        }
                        if (read <= 0) continue

                        var sumSquares = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i] / 32768f
                            sumSquares += (sample * sample).toDouble()
                        }
                        val rms = sqrt(sumSquares / read).toFloat()
                        if (rms > SIGNAL_THRESHOLD) hadSignal = true
                        smoothed = smoothed * 0.72f + rms * 0.28f
                        val nextLevel = min(1f, smoothed * 8f)
                        if (kotlin.math.abs(nextLevel - _level.value) > 0.01f) _level.value = nextLevel

                        val framesRead = read / format.channels
                        if (isPaused) {

                            writeSilenceFrames(raf, framesRead.toLong(), format.channels)
                        } else {
                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                val sample = buffer[i].toInt()
                                bytes[i * 2] = (sample and 0xFF).toByte()
                                bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                            }
                            raf.write(bytes)
                            bytesWritten += bytes.size
                        }
                        if (bytesWritten % (64 * 1024) < read * 2) {
                            CaptureDiagnostics.progress(bytesWritten, hadSignal)
                        }
                    }
                }
            } catch (e: Exception) {
                captureFailure = "Capture loop failed: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, captureFailure, e)
                CaptureDiagnostics.failure(captureFailure!!)
            }
        }
        return true
    }

    private fun createInitializedRecord(
        captureConfig: AudioPlaybackCaptureConfiguration,
    ): Triple<AudioRecord, CaptureFormat, Int>? {
        val formats = listOf(
            CaptureFormat(48_000, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureFormat(44_100, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureFormat(48_000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureFormat(44_100, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureFormat(32_000, AudioFormat.CHANNEL_IN_MONO, 1),
        )
        for (format in formats) {
            val minBuffer = AudioRecord.getMinBufferSize(
                format.sampleRate,
                format.channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val bufferBytes = max(minBuffer * 4, format.sampleRate * format.channels * 2 / 5)
            val record = try {
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(format.channelMask)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord ${format.sampleRate}/${format.channels} creation failed", e)
                null
            }
            if (record != null) {
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return Triple(record, format, bufferBytes)
                }
                record.release()
            }
        }
        return null
    }

    private fun writeSilenceFrames(raf: RandomAccessFile, frames: Long, channels: Int) {
        if (frames <= 0L) return
        val bytesPerFrame = channels * 2
        val chunk = ByteArray(16 * 1024)
        var remainingBytes = frames * bytesPerFrame
        while (remainingBytes > 0L) {
            val count = min(remainingBytes, chunk.size.toLong()).toInt()
            raf.write(chunk, 0, count)
            bytesWritten += count
            remainingBytes -= count
        }
    }

    suspend fun stopAndFinish(): CaptureResult? = withContext(Dispatchers.Default) {
        if (!_isCapturing.value) return@withContext null
        val outFile = currentOutFile
        val rec = audioRecord
        audioRecord = null
        try { rec?.stop() } catch (_: Exception) {}
        recordJob?.join()
        recordJob = null
        rec?.release()
        _isCapturing.value = false
        _level.value = 0f
        currentOutFile = null
        CaptureDiagnostics.finished(bytesWritten, hadSignal)

        if (outFile == null || !outFile.exists() || outFile.length() < 4096L) {
            CaptureDiagnostics.failure("Capture produced no usable PCM file.")
            outFile?.delete()
            return@withContext null
        }
        val channels = currentChannelCount.coerceAtLeast(1)
        val sampleRate = currentSampleRate.coerceAtLeast(1)
        val validation = PcmValidator.validate(outFile, sampleRate, channels)
        CaptureDiagnostics.pcmValidated(validation)
        if (!validation.valid) {
            outFile.delete()
            return@withContext null
        }
        CaptureResult(outFile, sampleRate, channels, validation.durationMs, hadSignal)
    }

    fun cancel() {
        val outFile = currentOutFile
        val rec = audioRecord
        audioRecord = null
        try { rec?.stop() } catch (_: Exception) {}
        recordJob?.cancel()
        recordJob = null
        rec?.release()
        _isCapturing.value = false
        _level.value = 0f
        outFile?.takeIf { it.exists() }?.delete()
        currentOutFile = null
        CaptureDiagnostics.reset()
    }

    fun close() {
        cancel()
        scope.cancel()
    }

    companion object {
        private const val SIGNAL_THRESHOLD = 0.002f
        private const val MAX_INITIAL_OFFSET_MS = 6L * 60L * 60L * 1000L
        private const val TAG = "SysAudioCapture"
    }
}
