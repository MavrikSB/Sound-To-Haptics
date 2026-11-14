package com.sound.to.haptics.haptics

import android.content.Context
import android.media.AudioAttributes
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
        /** Bigger than this ⇒ hard seek; smaller corrections get a smooth
         *  playback-rate nudge instead (no Vorbis packet-scan jitter). */
        private const val HARD_SEEK_MS = 600
        /** Below this, no correction — don't even nudge. */
        private const val DEADBAND_MS = 30
        /** Above this, correct via playback speed rather than seeking. */
        private const val SOFT_CORRECT_MS = 260
    }

    private var player: MediaPlayer? = null
    /** Last playback speed we set; avoids spamming the HAL with identical params. */
    private var currentSpeed = 1f

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
            player = MediaPlayer().apply {
                // Match the proven Offline speaker-path attributes exactly:
                // USAGE_MEDIA + CONTENT_MUSIC + haptic channels open, and let
                // the OS route to the default output. An explicit
                // setPreferredDevice(phoneSpeaker) call here was silently
                // suppressing the haptic-over-audio route on this device.
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
                prepare()
                setOnErrorListener { _, what, extra ->
                    handlePlayerError("BitChord haptic OGG playback failed (decoder error $what/$extra).")
                    true
                }
                setOnCompletionListener { completed = true }
                isLooping = false
                // Keep the OGG's audible stereo branch quiet because BitChord
                // itself supplies the audible track. The haptic channel stays
                // open through setHapticChannelsMuted(false) and is read by
                // the audio-haptic path directly — the player volume does not
                // affect it (measured on device at both 0.08f and 1.0f), so
                // the OGG stays near-inaudible while the actuator fires.
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
            // signed drift: positive ⇒ the haptic player is AHEAD of ExoPlayer
            val driftMs = p.currentPosition.toLong() - target.toLong()
            val absDrift = abs(driftMs)
            when {
                absDrift > HARD_SEEK_MS -> {
                    p.seekTo(target.toInt())
                    applySpeed(p, 1f)
                }
                absDrift > SOFT_CORRECT_MS -> {
                    // Behind ⇒ speed up slightly; ahead ⇒ slow down. Every
                    // 250ms poll applies this, so drift converges in a couple
                    // of seconds without an audible seek/jump.
                    val nudge = (driftMs.coerceIn(-2500, 2500) / 2500.0) * 0.25
                    applySpeed(p, (1.0 - nudge).toFloat().coerceIn(0.75f, 1.25f))
                }
                absDrift <= DEADBAND_MS -> applySpeed(p, 1f)
            }
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

    private fun applySpeed(p: MediaPlayer, speed: Float) {
        if (abs(speed - currentSpeed) < 0.01f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.playbackParams = p.playbackParams.setSpeed(speed.coerceIn(0.75f, 1.25f))
            currentSpeed = speed
        }
    }

    private fun release() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        completed = false
        currentSpeed = 1f
    }
}
