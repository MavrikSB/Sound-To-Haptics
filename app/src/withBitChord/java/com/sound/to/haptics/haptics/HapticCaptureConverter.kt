package com.sound.to.haptics.haptics

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The "record once, shape with FFmpeg, cache" step of the BitChord pipeline.
 *
 * This used to turn the capture into a direct VibrationEffect waveform.
 * The active pipeline now keeps the decoded PCM as input to the same FFmpeg
 * shaping and three-channel OGG construction used by Offline mode. Android's
 * haptic-aware MediaPlayer path then plays that persisted OGG, so a sidecar
 * analysis can inform diagnostics without replacing the continuous encoded
 * signal. The OGG path uses the ANDROID_HAPTIC=1 comment and libvorbis, with
 * one necessary difference from Offline mode: the input here is raw PCM fed by
 * [com.sound.to.haptics.haptics.HapticPcmTapAudioProcessor], the ExoPlayer
 * audio-processor tap, with no container or header, so the FFmpeg input side
 * describes the raw format explicitly (`-f s16le -ar -ac`) instead of pointing
 * at a normal audio file.
 *
 * The resulting OGG is played back by BitChordViewModel as a second,
 * muted-stereo MediaPlayer running alongside BitChord's own real playback —
 * the audible track the person hears the whole time is genuinely BitChord's
 * own stream, unmodified; this file only ever contributes its haptic
 * channel. See BitChordViewModel for how playback and position-sync work.
 */
object HapticCaptureConverter {

    private const val TAG = "SoundToHaptics"
    private const val SHAPED_SAMPLE_RATE = 44100

    /**
     * @return the finished haptic-enabled OGG file, or null if FFmpeg failed.
     *   Caller (BitChordViewModel) is responsible for moving/renaming this into the
     *   TrackHapticCache location once satisfied it's valid.
     */
    suspend fun convert(
        pcmFile: File,
        sampleRate: Int,
        channelCount: Int,
        outputOgg: File,
        sensitivity: Float,
        bassBoost: Float = 0.7f,
        density: Float = 0.6f,
    ): File? {
        if (!pcmFile.exists() || pcmFile.length() < 4096) return null

        // FFmpeg writes to a scratch path first, not directly to the final
        // cache location — if the app process dies mid-encode (killed by
        // the system, user force-closes it, etc.), a partial file would be
        // left sitting exactly where TrackHapticCache.exists() looks, and
        // it would be mistaken for a valid cached track next launch. Only
        // renaming into place after FFmpeg reports success avoids that.
        val scratchOgg = File(outputOgg.parentFile, "${outputOgg.name}.encoding.tmp")
        scratchOgg.delete()

        val channelLayout = if (channelCount == 1) "mono" else "stereo"
        val stereoInput = if (channelCount == 1) "pan=stereo|c0=c0|c1=c0," else ""
        // Use the value captured when this learning pass began. Reading the
        // singleton again here could make a slider change halfway through an
        // encode produce a profile whose UI label and DSP settings disagree.
        val effectiveSensitivity = sensitivity.coerceIn(0f, 1f)

        // Same structure as MainViewModel.performFFmpegConversion: split the
        // input into a stereo "real audio" branch and a mono "haptic" branch
        // shaped by the shared filter chain, then merge them into one
        // multichannel stream and encode as libvorbis with the
        // ANDROID_HAPTIC metadata flag that tells MediaPlayer's OGG path
        // this file carries a haptic channel.
        val graph =
            "[0:a]aformat=sample_fmts=fltp:sample_rates=$SHAPED_SAMPLE_RATE:channel_layouts=$channelLayout,$stereoInput" +
                "asplit=2[a1][a2];" +
                // Never loudness-normalize the audible branch: BitChord's own
                // stream is the reference mix, and normalization here causes
                // the captured copy to sound distant when it is routed back.
                "[a1]aresample=$SHAPED_SAMPLE_RATE:resampler=soxr:precision=28,asetpts=N/SR/TB," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo[a_sync];" +
                HapticAudioShaping.filterChain(effectiveSensitivity, bassBoost, density, "[a2]", "[h]") + ";" +
                "[a_sync][h]join=inputs=2:channel_layout=3.0:" +
                "map=0.0-FL|0.1-FR|1.0-FC[out]"

        val cmd = "-y -f s16le -ar $sampleRate -ac $channelCount -i \"${pcmFile.absolutePath}\" " +
            "-vn -filter_complex \"$graph\" -map \"[out]\" -ar $SHAPED_SAMPLE_RATE " +
            "-c:a libvorbis -q:a 10 " +
            "-metadata ANDROID_HAPTIC=1 -metadata title=\"Sound To Haptics (BitChord)\" " +
            "-shortest \"${scratchOgg.absolutePath}\""

        Log.d(TAG, "BitChord FFmpeg->OGG (sensitivity=$effectiveSensitivity bass=$bassBoost density=$density)")

        val success = try {
            // executeAsync (not the synchronous execute()) because it's the
            // one call this project's ffmpeg-kit fork is already proven to
            // expose — see MainViewModel's offline conversion — bridged into
            // this suspend fun with suspendCancellableCoroutine instead of
            // blocking a thread on an API surface that isn't verified here.
            suspendCancellableCoroutine { cont ->
                val session = FFmpegKit.executeAsync(cmd) { s ->
                    if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode))
                    if (!ReturnCode.isSuccess(s.returnCode)) {
                        Log.e(TAG, "BitChord haptic OGG encode failed: ${s.allLogsAsString}")
                    }
                }
                cont.invokeOnCancellation { session.cancel() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "BitChord haptic OGG encode threw", t)
            false
        }

        if (!success || !scratchOgg.exists() || scratchOgg.length() < 4096) {
            scratchOgg.delete()
            return null
        }

        val validation = HapticOggValidator.validate(scratchOgg)
        if (!validation.valid) {
            Log.e(TAG, "BitChord haptic OGG validation failed: ${validation.reason}")
            scratchOgg.delete()
            return null
        }

        outputOgg.delete()
        val moved = scratchOgg.renameTo(outputOgg)
        if (!moved) {
            Log.e(TAG, "Failed to move encoded BitChord haptic OGG into place")
            scratchOgg.delete()
            return null
        }
        return outputOgg
    }
}
