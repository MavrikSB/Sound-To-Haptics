package com.sound.to.haptics.bitchord.playback

// Derived from the Sound To Haptics project's own Offline playback path —
// same haptic-aware attributes as MainViewModel's standard path. The haptic OGG
// itself is the single audio source (like Offline mode), so haptics and audio
// come from one decoder pipeline with one clock — perfectly aligned by
// construction. Copyright (C) Sound To Haptics contributors — GPL-3.0.

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build

/**
 * MediaPlayer-based player for cached BitChord haptic OGGs.
 *
 * Mirrors the proven Offline `startStandardPlayback` path: USAGE_MEDIA,
 * CONTENT_TYPE_MUSIC, and haptic channels open — Android's haptic-aware audio
 * path drives the actuator from the OGG's third (FC) channel while the stereo
 * branch plays normally through the same route.
 *
 * No ExoPlayer, no separate side channel, no position "syncing" between two
 * decoders. Seek, pause and completion are native MediaPlayer operations.
 */
class BitChordLocalPlayer(
    private val context: Context,
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var player: MediaPlayer? = null

    var isPlaying: Boolean = false
        private set

    var durationMs: Long = 0L
        private set

    /** Loads [file] prepared and paused at [startPositionMs], then starts playback. */
    fun load(file: java.io.File, startPositionMs: Long = 0L): Boolean {
        release()
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(hapticAttributes())
            p.setDataSource(file.absolutePath)
            p.setVolume(1f, 1f)
            p.setOnPreparedListener {
                durationMs = it.duration.coerceAtLeast(0).toLong()
                if (startPositionMs > 0L) it.seekTo(startPositionMs.toInt().coerceAtLeast(0))
                it.start()
            }
            p.setOnCompletionListener {
                isPlaying = false
                onCompletion()
            }
            p.setOnErrorListener { _, what, extra ->
                isPlaying = false
                onError("MediaPlayer error $what/$extra")
                true
            }
            p.prepare()
            player = p
            isPlaying = true
            durationMs = p.duration.coerceAtLeast(0).toLong()
            true
        } catch (t: Throwable) {
            runCatching { p.release() }
            onError("Load failed: ${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    private fun hapticAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // The haptic channel is never muted for bit chord playback,
                    // matching the offline standard path.
                    setHapticChannelsMuted(false)
                }
            }
            .build()

    fun start() {
        player?.takeIf { !it.isPlaying }?.start()
        isPlaying = player?.isPlaying == true
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        isPlaying = false
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L).coerceAtMost(durationMs.coerceAtLeast(0L))
        player?.seekTo(clamped.toInt())
    }

    val currentPositionMs: Long
        get() = player?.currentPosition?.coerceAtLeast(0)?.toLong() ?: 0L

    fun release() {
        val p = player ?: return
        player = null
        isPlaying = false
        runCatching { p.stop() }
        runCatching { p.release() }
        durationMs = 0L
    }
}
