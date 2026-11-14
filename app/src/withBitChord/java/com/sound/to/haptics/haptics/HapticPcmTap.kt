package com.sound.to.haptics.haptics

import android.util.Log
import androidx.media3.common.C
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Arms and owns the PCM learning pass that runs alongside ExoPlayer.
 *
 * Flow (all coordinated by the owning ViewModel):
 *  1. [arm] — the ViewModel arms a pass before playing a track with no cached
 *     haptics, passing the scratch file and the position the track starts from.
 *     Nothing is created yet: the decoder hasn't spoken.
 *  2. [onFormat] — the tap sees the track's decoded format (44.1/48 kHz, mono
 *     or stereo, 16/32-bit or float). The pass starts lazily here, with the
 *     exact format the player will actually emit, and the file is prepended
 *     with silence up to the armed position so the cached profile covers the
 *     absolute timeline. If a pass is armed and a format is already known,
 *     [arm] starts it immediately instead of waiting for the sink — the
 *     decoder usually reconfigures before the main-thread arm lands, and
 *     waiting for it would lose the whole pass.
 *  3. [onPcm] — decoded frames are converted to s16le and appended.
 *  4. [onStreamEnded] — decode ended; the file is left alone until the
 *     ViewModel finalizes it at the track boundary.
 *
 * Discarding passes: [cancel] throws the scratch file away. The ViewModel
 * calls it whenever the track changes mid-pass, the user seeks backwards, or
 * the app leaves the music section — the same "partial profiles are worse
 * than none" rule the rest of the haptic pipeline follows.
 */
object HapticPcmTap {

    private const val TAG = "HapticPcmTap"

    data class ArmRequest(val scratchFile: File, val initialOffsetMs: Long)

    @Volatile private var armed: ArmRequest? = null
    @Volatile private var capture: PcmCapture? = null

    /** Format the sink last configured for, so [arm] can start a pass immediately. */
    @Volatile private var lastFormat: Triple<Int, Int, Int>? = null

    /** The in-flight capture, if the tap has started one. */
    val activeCapture: PcmCapture? get() = capture

    val isCapturing: Boolean get() = capture?.isCapturing() == true

    fun arm(request: ArmRequest) {
        armed = request
        // If the sink already told us its format (the common case — the sink
        // reconfigures for the new track before the main-thread transition
        // callback arms us), start the pass right away. [onFormat] stays the
        // authority: if the decoder ends up disagreeing, the pass restarts
        // under the real format.
        lastFormat?.let { (sampleRate, channelCount, _) ->
            ensureCapture(request, sampleRate, channelCount)
        }
    }

    fun onFormat(sampleRate: Int, channelCount: Int, encoding: Int) {
        val format = Triple(sampleRate, channelCount, encoding)
        val formatChanged = lastFormat != format
        lastFormat = format
        val request = armed
        if (request == null) return
        if (capture?.isCapturing() == true) {
            if (formatChanged) {
                // The decoder chose a different format than the pass was armed
                // under — restart cleanly under the real one.
                Log.w(TAG, "format changed mid-arm ($lastFormat); restarting capture")
                capture?.cancel()
                capture = null
                ensureCapture(request, sampleRate, channelCount).let { started ->
                    if (started) {
                        CaptureDiagnostics.recorderCreated(sampleRate, channelCount, 0, 1)
                        CaptureDiagnostics.capturing()
                    }
                }
            }
            return
        }
        if (ensureCapture(request, sampleRate, channelCount)) {
            CaptureDiagnostics.recorderCreated(sampleRate, channelCount, 0, 1)
            CaptureDiagnostics.capturing()
        } else {
            Log.e(TAG, "capture could not start for format ${sampleRate}Hz ch=$channelCount enc=$encoding")
            CaptureDiagnostics.failure("The haptic tap couldn't open its scratch file.")
        }
    }

    fun onPcm(chunk: ByteArray, sampleRate: Int, channelCount: Int, encoding: Int) {
        if (capture == null) {
            // A chunk arrived before any onFormat — start the pass under this
            // chunk's format rather than dropping the prefix entirely.
            armed?.let { request ->
                if (ensureCapture(request, sampleRate, channelCount)) {
                    CaptureDiagnostics.recorderCreated(sampleRate, channelCount, 0, 1)
                    CaptureDiagnostics.capturing()
                }
            }
        }
        val active = capture ?: return
        when (encoding) {
            C.ENCODING_PCM_16BIT -> active.offer(chunk)
            C.ENCODING_PCM_FLOAT -> active.offer(floatToS16(chunk))
            C.ENCODING_PCM_32BIT -> active.offer(s32ToS16(chunk))
            else -> {
                // Encoding we can't faithfully convert (e.g. 24-bit): drop the
                // chunk rather than bake garbage into the profile. Rare in
                // practice — decoders give us 16-bit or float.
                Log.w(TAG, "dropping PCM chunk with unsupported encoding $encoding")
            }
        }
    }

    fun onStreamEnded() {
        // Nothing to do here; finalization is the ViewModel's call so it can
        // hole-punch against the player timeline first.
    }

    /**
     * Detaches the in-flight capture atomically so the caller can finalize it
     * on a background thread while an arm for the next track proceeds on main.
     * Returns null when there is nothing to finalize.
     */
    fun detachActive(): PcmCapture? {
        val active = capture ?: return null
        capture = null
        armed = null
        return active
    }

    /** Throws the pass away, e.g. on a backward seek or a track change mid-pass. */
    fun cancel() {
        armed = null
        capture?.cancel()
        capture = null
    }

    private fun ensureCapture(request: ArmRequest, sampleRate: Int, channelCount: Int): Boolean {
        if (capture?.isCapturing() == true) return true
        val created = PcmCapture()
        val ok = created.start(request.scratchFile, sampleRate, channelCount, request.initialOffsetMs)
        return if (ok) {
            capture = created
            true
        } else {
            false
        }
    }

    private fun floatToS16(chunk: ByteArray): ByteArray {
        val floatCount = chunk.size / 4
        val out = ByteArray(floatCount * 2)
        val input = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        repeat(floatCount) { index ->
            val sample = input.getFloat(index * 4)
            val clamped = sample.coerceIn(-1f, 1f)
            val s = (clamped * 32767f).toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun s32ToS16(chunk: ByteArray): ByteArray {
        // Little-endian 32-bit: the high two bytes carry the s16-relevant range.
        val out = ByteArray(chunk.size / 2)
        var i = 0
        var o = 0
        while (i + 3 < chunk.size) {
            out[o++] = chunk[i + 2]
            out[o++] = chunk[i + 3]
            i += 4
        }
        return out
    }
}
