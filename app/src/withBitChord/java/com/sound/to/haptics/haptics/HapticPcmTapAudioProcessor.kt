package com.sound.to.haptics.haptics

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through audio processor wired into ExoPlayer's sink ahead of the
 * silence-skipper/Sonic processors. It never changes the bytes that reach the
 * AudioTrack — audible playback is untouched — it only copies a s16le version
 * of the decoded stream into the armed [PcmCapture], converting from
 * float/32-bit when the decoder produced those instead.
 *
 * The processor reports the same format it was configured with, so ExoPlayer
 * treats it as a no-op in the chain. When no capture is armed it still runs
 * (and passes everything through) — arming is cheap and avoids reconfiguring
 * the sink between tracks.
 */
class HapticPcmTapAudioProcessor : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channelCount = 1
    private var encoding = C.ENCODING_PCM_16BIT

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        HapticPcmTap.onFormat(inputAudioFormat.sampleRate, channelCount, encoding)
        // Pass the audio through unchanged — we are a tap, not a stage.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Copy the input without disturbing the buffer position: the sink
        // hands us the live buffer and expects it back with its position
        // advanced.
        val chunk = ByteArray(remaining)
        inputBuffer.get(chunk)

        HapticPcmTap.onPcm(chunk, sampleRate, channelCount, encoding)

        // Hand the unmodified bytes on exactly once.
        val output = replaceOutputBuffer(remaining)
        output.order(ByteOrder.LITTLE_ENDIAN)
        output.put(chunk)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        super.onQueueEndOfStream()
        HapticPcmTap.onStreamEnded()
    }
}
