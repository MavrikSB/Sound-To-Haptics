package com.sound.to.haptics.spotify

import android.app.Activity
import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SpotifyTrack(
    val uri: String? = null,
    val title: String = "",
    val artist: String = "",
    val albumArtUri: String? = null,
    val isPaused: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class SpotifyConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val track: SpotifyTrack = SpotifyTrack(),

    val accessToken: String? = null,
)

class SpotifyController(private val appContext: Context) {

    private val _state = MutableStateFlow(SpotifyConnectionState())
    val state: StateFlow<SpotifyConnectionState> = _state.asStateFlow()

    private var appRemote: SpotifyAppRemote? = null

    fun beginLogin(activity: Activity) {
        val builder = AuthorizationRequest.Builder(
            SpotifyConfig.CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            SpotifyConfig.REDIRECT_URI
        )
        builder.setScopes(
            arrayOf(
                "app-remote-control",
                "user-read-playback-state",
                "user-top-read",
                "user-library-read",
                "user-read-recently-played",
                "playlist-read-private",
                "playlist-read-collaborative",
            )
        )
        val request = builder.build()
        AuthorizationClient.openLoginActivity(activity, SpotifyConfig.AUTH_REQUEST_CODE, request)
    }

    fun handleAuthResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        if (requestCode != SpotifyConfig.AUTH_REQUEST_CODE) return false
        val response = AuthorizationClient.getResponse(resultCode, data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                _state.update { it.copy(accessToken = response.accessToken) }
                connect()
            }
            AuthorizationResponse.Type.ERROR -> _state.update { it.copy(error = response.error) }
            else -> Unit
        }
        return true
    }

    fun connect() {
        if (_state.value.isConnected || _state.value.isConnecting) return
        _state.update { it.copy(isConnecting = true, error = null) }
        val params = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(appContext, params, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                appRemote = remote
                _state.update { it.copy(isConnected = true, isConnecting = false, error = null) }
                subscribeToPlayerState(remote)
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("SpotifyController", "Connect failed", throwable)
                _state.update {
                    it.copy(isConnected = false, isConnecting = false, error = throwable.message ?: "Connection failed")
                }
            }
        })
    }

    private fun subscribeToPlayerState(remote: SpotifyAppRemote) {
        remote.playerApi.subscribeToPlayerState().setEventCallback { playerState: PlayerState ->
            val track = playerState.track
            _state.update {
                it.copy(
                    track = SpotifyTrack(
                        uri = track?.uri,
                        title = track?.name.orEmpty(),
                        artist = track?.artist?.name.orEmpty(),
                        albumArtUri = track?.imageUri?.raw,
                        isPaused = playerState.isPaused,
                        positionMs = playerState.playbackPosition,
                        durationMs = track?.duration ?: 0L,
                    )
                )
            }
        }
    }

    fun togglePlayPause() {
        val remote = appRemote ?: return
        if (_state.value.track.isPaused) remote.playerApi.resume() else remote.playerApi.pause()
    }

    fun pause() {
        appRemote?.playerApi?.pause()
    }

    fun skipNext() = appRemote?.playerApi?.skipNext()
    fun skipPrevious() = appRemote?.playerApi?.skipPrevious()
    fun seekTo(positionMs: Long) = appRemote?.playerApi?.seekTo(positionMs.coerceAtLeast(0L))

    fun play(uri: String) = appRemote?.playerApi?.play(uri)

    fun disconnect() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
        _state.update { SpotifyConnectionState() }
    }
}
