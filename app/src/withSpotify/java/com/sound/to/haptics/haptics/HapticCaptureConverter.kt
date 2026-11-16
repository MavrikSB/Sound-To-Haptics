package com.sound.to.haptics.haptics

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object HapticCaptureConverter {

    private const val TAG = "SoundToHaptics"
    private const val SHAPED_SAMPLE_RATE = 44100

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

        val scratchOgg = File(outputOgg.parentFile, "${outputOgg.name}.encoding.tmp")
        scratchOgg.delete()

        val channelLayout = if (channelCount == 1) "mono" else "stereo"
        val stereoInput = if (channelCount == 1) "pan=stereo|c0=c0|c1=c0," else ""

        val effectiveSensitivity = sensitivity.coerceIn(0f, 1f)

        val graph =
            "[0:a]aformat=sample_fmts=fltp:sample_rates=$SHAPED_SAMPLE_RATE:channel_layouts=$channelLayout,$stereoInput" +
                "asplit=2[a1][a2];" +

                "[a1]aresample=$SHAPED_SAMPLE_RATE:resampler=soxr:precision=28,asetpts=N/SR/TB," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo[a_sync];" +
                HapticAudioShaping.filterChain(effectiveSensitivity, bassBoost, density, "[a2]", "[h]") + ";" +
                "[a_sync][h]join=inputs=2:channel_layout=3.0:" +
                "map=0.0-FL|0.1-FR|1.0-FC[out]"

        val cmd = "-y -f s16le -ar $sampleRate -ac $channelCount -i \"${pcmFile.absolutePath}\" " +
            "-vn -filter_complex \"$graph\" -map \"[out]\" -ar $SHAPED_SAMPLE_RATE " +
            "-c:a libvorbis -q:a 10 " +
            "-metadata ANDROID_HAPTIC=1 -metadata title=\"Sound To Haptics (Spotify)\" " +
            "-shortest \"${scratchOgg.absolutePath}\""

        Log.d(TAG, "Spotify FFmpeg->OGG (sensitivity=$effectiveSensitivity bass=$bassBoost density=$density)")

        val success = try {

            suspendCancellableCoroutine { cont ->
                val session = FFmpegKit.executeAsync(cmd) { s ->
                    if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode))
                    if (!ReturnCode.isSuccess(s.returnCode)) {
                        Log.e(TAG, "Spotify haptic OGG encode failed: ${s.allLogsAsString}")
                    }
                }
                cont.invokeOnCancellation { session.cancel() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify haptic OGG encode threw", e)
            false
        }

        if (!success || !scratchOgg.exists() || scratchOgg.length() < 4096) {
            scratchOgg.delete()
            return null
        }

        val validation = HapticOggValidator.validate(scratchOgg)
        if (!validation.valid) {
            Log.e(TAG, "Spotify haptic OGG validation failed: ${validation.reason}")
            scratchOgg.delete()
            return null
        }

        outputOgg.delete()
        val moved = scratchOgg.renameTo(outputOgg)
        if (!moved) {
            Log.e(TAG, "Failed to move encoded Spotify haptic OGG into place")
            scratchOgg.delete()
            return null
        }
        return outputOgg
    }
}
