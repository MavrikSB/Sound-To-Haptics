package com.sound.to.haptics.haptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.sound.to.haptics.settings.AppSettings
import java.io.File
import kotlin.math.abs

/**
 * Plays a cached haptic-enabled OGG file as a second, muted-audio
 * [MediaPlayer] alongside ExoPlayer's real playback. The OGG's third channel
 * remains the physical haptic source; this class only keeps that actual OGG
 * player aligned with the player position reported by the BitChord engine.
 *
 * It deliberately does not decode a sidecar into a VibrationEffect. The
 * sidecar is available for analysis/debug information, but the persisted OGG
 * is the playback artifact and Android's haptic-aware audio path is the
 * source of continuous haptic output.
 */
class HapticOggSyncPlayer(
    private val context: Context,
    private val onPlaybackError: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "SoundToHaptics"
        private const val DRIFT_TOLERANCE_MS = 400
    }

    private var player: MediaPlayer? = null

    /** Set once the OGG reaches its end; cleared by [load] and on seeks backwards. */
    @Volatile
    private var completed = false

    /** Loads [file] and prepares it paused for synchronized playback. */
    fun load(file: File): Boolean {
        release()
        completed = false
        if (!file.exists() || file.length() < 4096L) return false
        val validation = HapticOggValidator.validate(file)
        if (!validation.valid) {
            Log.e(TAG, "BitChord haptic OGG rejected: ${validation.reason}")
            return false
        }

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val phoneOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                setHapticChannelsMuted(false)
                            }
                        }
                        .build()
                )
                setDataSource(file.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && phoneOutput != null) {
                    val routed = setPreferredDevice(phoneOutput)
                    Log.d(TAG, "BitChord haptic OGG player setPreferredDevice(phone) succeeded=$routed")
                } else {
                    Log.w(TAG, "No built-in phone output found for haptic OGG player")
                }
                prepare()
                setOnErrorListener { _, what, extra ->
                    handlePlayerError("BitChord haptic OGG playback failed (decoder error $what/$extra).")
                    true
                }
                setOnCompletionListener { completed = true }
                isLooping = false
                // Keep the OGG's audible stereo branch quiet because BitChord
                // itself supplies the audible track. The haptic channel stays
                // enabled through setHapticChannelsMuted(false); the same
                // strength setting also scales this secondary OGG route.
                val strength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
                setVolume(0.08f * strength, 0.08f * strength)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load BitChord haptic OGG", e)
            release()
            false
        }
    }

    /** Feed this whenever BitChord reports a fresh position/pause reading. */
    fun syncTo(positionMs: Long, paused: Boolean) {
        val p = player ?: return
        try {
            if (paused) {
                if (p.isPlaying) p.pause()
                return
            }
            val safePosition = positionMs.coerceAtLeast(0L)
            val duration = p.duration.takeIf { it > 0 }?.toLong()
            val target = if (duration != null) safePosition.coerceIn(0L, duration) else safePosition
            if (completed) {
                // The OGG played to its end; only resurrect it if BitChord
                // seeks back to a point where it still has content to play.
                if (duration == null || target < duration - 500L) {
                    completed = false
                } else {
                    return
                }
            }
            val drift = abs(p.currentPosition - target.toInt())
            if (drift > DRIFT_TOLERANCE_MS) p.seekTo(target.toInt())
            if (!p.isPlaying) p.start()
        } catch (e: Exception) {
            handlePlayerError("BitChord haptic OGG synchronization failed.", e)
        }
    }

    fun stop() = release()

    private fun handlePlayerError(message: String, error: Exception? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        onPlaybackError(message)
        release()
    }

    private fun release() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        completed = false
    }
}
