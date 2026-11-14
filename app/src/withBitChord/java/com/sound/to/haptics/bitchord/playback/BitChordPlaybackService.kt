package com.sound.to.haptics.bitchord.playback

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sound.to.haptics.MainActivity
import com.sound.to.haptics.bitchord.BitChordStreamHooks
import com.sound.to.haptics.bitchord.data.Http
import com.sound.to.haptics.haptics.HapticPcmTapAudioProcessor

/**
 * Media3 [MediaSessionService] that plays BitChord's actual YouTube Music
 * streams in-app.
 *
 * Two pieces of BitChord's playback engine are wired in here verbatim:
 *  - [BitChordResolverDataSourceFactory] resolves `bitchord://watch?v=…`
 *    URIs at play time through BitChord's stream resolver (InnerTubeX with
 *    NewPipe as the failsafe) — the same real stream a YouTube client plays,
 *    never a demo or preview clip.
 *  - The audio chain carries a [HapticPcmTapAudioProcessor] ahead of the
 *    silence-skipper and Sonic, so the haptic pipeline sees the decoded
 *    stream itself while the audible path stays untouched.
 *
 * Audio focus is handled by Media3 (`setAudioAttributes(..., handleAudioFocus
 * = true)`), background playback comes from the media-notification service,
 * and Bluetooth/noisy-cable routing uses `setHandleAudioBecomingNoisy(true)`.
 */
class BitChordPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        BitChordStreamHooks.ensureInstalled(this)

        val resolverFactory = BitChordResolverDataSourceFactory(Http.client)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(
                        enableAudioTrackPlaybackParams || enableFloatOutput,
                    )
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            // Our PCM tap runs first: the haptic signal sees the
                            // decode exactly as the decoder produced it, before
                            // silence-skipping or time-stretching touch it.
                            arrayOf(HapticPcmTapAudioProcessor()),
                            SilenceSkippingAudioProcessor(
                                SilenceSkippingAudioProcessor.DEFAULT_MINIMUM_SILENCE_DURATION_US,
                                SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                                SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                                SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                                SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                            ),
                            SonicAudioProcessor(),
                        ),
                    )
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolverFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == true) {
            // Being swiped away while audible: pause instead of vanishing.
            player.pause()
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
