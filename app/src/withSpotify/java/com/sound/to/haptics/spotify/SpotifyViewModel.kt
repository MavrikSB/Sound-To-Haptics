package com.sound.to.haptics.spotify

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sound.to.haptics.haptics.CaptureDiagnostics
import com.sound.to.haptics.haptics.CaptureDiagnosticsState
import com.sound.to.haptics.haptics.HapticCaptureConverter
import com.sound.to.haptics.haptics.HapticOggSyncPlayer
import com.sound.to.haptics.haptics.MultiLayerAudioAnalyzer
import com.sound.to.haptics.haptics.PlaybackCaptureService
import com.sound.to.haptics.haptics.saveTo
import com.sound.to.haptics.haptics.SystemAudioCaptureRecorder
import com.sound.to.haptics.haptics.TrackHapticCache
import com.sound.to.haptics.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class BrowseStatus { IDLE, LOADING }

private const val TRACK_END_TOLERANCE_MS = 2_000L

/**
 * What the "Track Haptics" panel should show. Driven entirely by whichever
 * track [SpotifyController] reports as actually playing, so it follows
 * Spotify's own skip/previous/autoplay too, not just taps inside this app.
 */
enum class HapticMode {
    IDLE,
    CAPTURING,
    SYNCED,
    NEEDS_PERMISSION,
    UNSUPPORTED,
    CAPTURE_FAILED,
    ENCODE_FAILED,
    PLAYBACK_FAILED,
}

data class TrackHapticUiState(
    val mode: HapticMode = HapticMode.IDLE,
    val sensitivity: Float = 1f,
    val lastError: String? = null,
)

data class SpotifyBrowseState(
    val query: String = "",
    val libraryTracks: List<SpotifyTrackItem> = emptyList(),
    val recentlyPlayed: List<SpotifyTrackItem> = emptyList(),
    val playlists: List<SpotifyPlaylistItem> = emptyList(),
    val searchResults: List<SpotifyTrackItem> = emptyList(),
    val selectedPlaylist: SpotifyPlaylistItem? = null,
    val playlistTracks: List<SpotifyTrackItem> = emptyList(),
    val playlistLoading: Boolean = false,
    val status: BrowseStatus = BrowseStatus.IDLE,
    val error: String? = null,
    val activeUri: String? = null,
) {
    val visibleTracks: List<SpotifyTrackItem>
        get() = if (query.isNotBlank()) searchResults else libraryTracks
}

data class SpotifyScreenState(
    val connection: SpotifyConnectionState = SpotifyConnectionState(),
    val haptics: TrackHapticUiState = TrackHapticUiState(),
    val browse: SpotifyBrowseState = SpotifyBrowseState(),
)

class SpotifyViewModel(application: Application) : AndroidViewModel(application) {

    val controller = SpotifyController(application.applicationContext)

    private val appContext = application.applicationContext
    private val cache = TrackHapticCache(appContext)

    private val mediaProjectionManager: MediaProjectionManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(MediaProjectionManager::class.java)
        } else null
    }
    private var mediaProjection: MediaProjection? = null
    private var recorder: SystemAudioCaptureRecorder? = null
    private var levelJob: Job? = null
    private var libraryJob: Job? = null
    private var searchJob: Job? = null
    private var playlistJob: Job? = null

    private val _captureLevel = MutableStateFlow(0f)
    /** Kept separate from [uiState] on purpose — this ticks ~10-15x/sec while capturing,
     * and folding it into the combined screen state would recompose the whole screen
     * on every tick instead of just the small VU meter that actually needs it. */
    val captureLevel: StateFlow<Float> = _captureLevel.asStateFlow()
    val captureDiagnostics: StateFlow<CaptureDiagnosticsState> = CaptureDiagnostics.state

    private var activeTrackId: String? = null
    private var activeTrackSnapshot: SpotifyTrack? = null
    private var pendingCaptureTrackId: String? = null

    private val _browse = MutableStateFlow(SpotifyBrowseState())
    private val _haptics = MutableStateFlow(
        TrackHapticUiState(sensitivity = AppSettings.state.value.hapticStrength)
    )
    private val syncEngine = HapticOggSyncPlayer(appContext) { message ->
        _haptics.update {
            it.copy(mode = HapticMode.PLAYBACK_FAILED, lastError = message)
        }
    }

    val uiState: StateFlow<SpotifyScreenState> =
        combine(controller.state, _haptics, _browse) { connection, haptics, browse ->
            SpotifyScreenState(connection, haptics, browse)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpotifyScreenState())

    init {
        viewModelScope.launch {
            AppSettings.state.map { it.hapticStrength }.distinctUntilChanged().collect { strength ->
                _haptics.update { it.copy(sensitivity = strength) }
            }
        }
        viewModelScope.launch {
            controller.state
                .map { it.accessToken }
                .distinctUntilChanged()
                .collect { token -> if (token != null) loadLibrary(token) }
        }

        // Reacts to whichever track is actually playing right now: starts a
        // capture pass for a track we've never heard before, or loads its
        // cached haptic file straight into sync playback if we have.
        viewModelScope.launch {
            controller.state
                .map { it.track }
                .distinctUntilChanged { old, new -> trackIdOf(old) == trackIdOf(new) }
                .collect { track -> onActiveTrackChanged(track) }
        }

        // Keeps the haptic replay locked to Spotify's real playback position,
        // and pauses an in-progress capture so a paused track doesn't bake a
        // silent gap into the recording.
        viewModelScope.launch {
            controller.state.collect { state ->
                val track = state.track
                if (trackIdOf(track) == activeTrackId) activeTrackSnapshot = track
                if (
                    track.isPaused &&
                    _haptics.value.mode == HapticMode.CAPTURING &&
                    activeTrackId != null &&
                    track.durationMs > 0L &&
                    track.positionMs >= track.durationMs - TRACK_END_TOLERANCE_MS
                ) {
                    // Some Spotify transitions briefly report the same track
                    // paused at its final position instead of immediately
                    // emitting the next track. Finalize once here so a normal
                    // end still produces a cache entry.
                    finalizeTrack(activeTrackId, discard = false)
                    syncEngine.stop()
                }
                if (_haptics.value.mode == HapticMode.SYNCED) {
                    syncEngine.syncTo(track.positionMs, track.isPaused)
                }
                recorder?.isPaused = track.isPaused
            }
        }
    }

    fun beginLogin(activity: Activity) = controller.beginLogin(activity)
    fun connect() = controller.connect()
    fun togglePlayPause() = controller.togglePlayPause()
    fun skipNext() = controller.skipNext()
    fun skipPrevious() = controller.skipPrevious()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    /** Re-arms capture/sync after the Spotify tab was temporarily left. */
    fun onScreenVisible() {
        val state = controller.state.value
        if (state.isConnected && trackIdOf(state.track) != null && activeTrackId == null) {
            onActiveTrackChanged(state.track)
        }
    }

    private fun loadLibrary(token: String) {
        libraryJob?.cancel()
        _browse.update { it.copy(status = BrowseStatus.LOADING, error = null) }
        libraryJob = viewModelScope.launch {
            val topOrSaved = SpotifyWebApi.getTopTracks(token).ifEmpty { SpotifyWebApi.getSavedTracks(token) }
            val recent = SpotifyWebApi.getRecentlyPlayed(token)
            val playlists = SpotifyWebApi.getUserPlaylists(token)
            val tracks = topOrSaved.ifEmpty { recent }
            _browse.update {
                it.copy(
                    libraryTracks = tracks,
                    recentlyPlayed = recent,
                    playlists = playlists,
                    status = BrowseStatus.IDLE,
                    error = if (tracks.isEmpty() && playlists.isEmpty()) "Couldn't load your library — try the search bar." else null,
                )
            }
        }
    }

    fun updateQuery(query: String) {
        searchJob?.cancel()
        _browse.update {
            it.copy(
                query = query,
                // Do not keep showing results for the previous query while a
                // new one is being typed; the next explicit Search action
                // owns the result list.
                searchResults = if (query.trim() == it.query.trim()) it.searchResults else emptyList(),
                status = BrowseStatus.IDLE,
                error = null,
            )
        }
    }

    fun search() {
        searchJob?.cancel()
        val token = controller.state.value.accessToken
        val query = _browse.value.query.trim()
        if (token == null || query.isEmpty()) {
            _browse.update { it.copy(searchResults = emptyList(), status = BrowseStatus.IDLE, error = null) }
            return
        }
        _browse.update { it.copy(status = BrowseStatus.LOADING, error = null) }
        searchJob = viewModelScope.launch {
            val results = SpotifyWebApi.search(token, query)
            // A newer query may have completed while this request was in
            // flight. Only the latest query is allowed to replace the result
            // list, preventing stale rows after rapid IME submits.
            if (_browse.value.query.trim() != query) return@launch
            _browse.update {
                it.copy(
                    searchResults = results,
                    status = BrowseStatus.IDLE,
                    error = if (results.isEmpty()) "No tracks found for \"$query\"." else null,
                )
            }
        }
    }

    /**
     * Starts [item] playing in the real Spotify app via App Remote. Capture
     * or haptic-sync playback isn't triggered from here directly — it's
     * driven reactively by [controller]'s reported player state once Spotify
     * actually starts the track (see the collector in [init]), which is what
     * lets skip/previous/autoplay inside Spotify itself pick up haptics too.
     */
    fun playTrack(item: SpotifyTrackItem) {
        _browse.update { it.copy(activeUri = item.uri) }
        controller.play(item.uri)
    }

    fun openPlaylist(item: SpotifyPlaylistItem) {
        playlistJob?.cancel()
        val token = controller.state.value.accessToken
        _browse.update { it.copy(selectedPlaylist = item, playlistTracks = emptyList(), playlistLoading = token != null) }
        if (token == null) return
        playlistJob = viewModelScope.launch {
            val tracks = SpotifyWebApi.getPlaylistTracks(token, item.id)
            if (_browse.value.selectedPlaylist?.id != item.id) return@launch
            _browse.update { it.copy(playlistTracks = tracks, playlistLoading = false) }
        }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        _browse.update { it.copy(selectedPlaylist = null, playlistTracks = emptyList(), playlistLoading = false) }
    }

    fun playPlaylist(item: SpotifyPlaylistItem) {
        _browse.update { it.copy(activeUri = item.uri) }
        controller.play(item.uri)
    }

    fun updateSensitivity(v: Float) {
        // Spotify capture and replay use the same persisted strength as
        // Offline's encoded OGG path and its optional transient accents.
        AppSettings.setHapticStrength(v)
        _haptics.update { it.copy(sensitivity = v.coerceIn(0f, 1f)) }
    }

    /** Discards any cached haptics for the current track and captures it again from scratch. */
    fun recaptureCurrentTrack() {
        val trackId = activeTrackId ?: return
        if (_haptics.value.mode == HapticMode.CAPTURING) finalizeTrack(trackId, discard = true)
        cache.delete(trackId)
        syncEngine.stop()
        beginHapticsFor(trackId, controller.state.value.track)
    }

    /** Stops the in-progress pass without caching a partial, out-of-sync track. */
    fun stopLearning() {
        val trackId = activeTrackId ?: return
        if (_haptics.value.mode != HapticMode.CAPTURING) return
        finalizeTrack(trackId, discard = true)
        syncEngine.stop()
        _haptics.update {
            it.copy(
                mode = HapticMode.CAPTURE_FAILED,
                lastError = "Learning stopped. Play this track again to build its full haptic profile.",
            )
        }
    }

    // ---- Permission + system-consent flow ----

    fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun createCaptureConsentIntent(): Intent? = mediaProjectionManager?.createScreenCaptureIntent()

    fun onCapturePermissionDenied() {
        CaptureDiagnostics.permissionResult(false)
        if (activeTrackId != null) {
            pendingCaptureTrackId = activeTrackId
            _haptics.update {
                it.copy(
                    mode = HapticMode.NEEDS_PERMISSION,
                    lastError = "Playback-capture access is needed to learn this Spotify track.",
                )
            }
        }
    }

    fun onCaptureConsentResult(resultCode: Int, data: Intent?) {
        val manager = mediaProjectionManager
        if (manager == null || resultCode != Activity.RESULT_OK || data == null) {
            CaptureDiagnostics.permissionResult(false)
            // Keep NEEDS_PERMISSION visible so the user can retry without
            // having to restart Spotify playback.
            return
        }
        CaptureDiagnostics.permissionResult(true)
        viewModelScope.launch {
            try {
                appContext.startForegroundService(Intent(appContext, PlaybackCaptureService::class.java))
                val ready = withTimeoutOrNull(3_000L) {
                    PlaybackCaptureService.isForeground.first { it }
                } != null
                if (!ready) {
                    val message = "The playback-capture service did not become active."
                    CaptureDiagnostics.failure(message)
                    _haptics.update { it.copy(mode = HapticMode.CAPTURE_FAILED, lastError = "Spotify haptics couldn't start. Try again while Spotify is playing.") }
                    return@launch
                }

                val projection = manager.getMediaProjection(resultCode, data)
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handleProjectionStopped()
                    }
                }, Handler(Looper.getMainLooper()))
                mediaProjection = projection
                CaptureDiagnostics.projection(true)

                val trackId = pendingCaptureTrackId
                pendingCaptureTrackId = null
                if (trackId != null && trackId == activeTrackId) {
                    startCapture(trackId, projection)
                }
            } catch (e: Exception) {
                val message = "MediaProjection initialization failed: ${e.message ?: e.javaClass.simpleName}"
                CaptureDiagnostics.failure(message)
                _haptics.update { it.copy(mode = HapticMode.CAPTURE_FAILED, lastError = "Spotify haptics couldn't start. Check capture access and retry.") }
                appContext.stopService(Intent(appContext, PlaybackCaptureService::class.java))
            }
        }
    }

    private fun handleProjectionStopped() {
        mediaProjection = null
        CaptureDiagnostics.projection(false)
        val activeRecorder = recorder
        recorder = null
        activeRecorder?.cancel()
        levelJob?.cancel()
        levelJob = null
        _captureLevel.value = 0f
        appContext.stopService(Intent(appContext, PlaybackCaptureService::class.java))
        if (_haptics.value.mode == HapticMode.CAPTURING) {
            _haptics.update {
                it.copy(
                    mode = HapticMode.CAPTURE_FAILED,
                    lastError = "Spotify haptics couldn't start. Android playback capture stopped before audio arrived.",
                )
            }
        }
    }

    // ---- Capture / replay orchestration ----

    private fun onActiveTrackChanged(track: SpotifyTrack) {
        val newId = trackIdOf(track)
        if (newId == activeTrackId) return
        val previousId = activeTrackId
        val previousTrack = activeTrackSnapshot
        activeTrackId = newId
        activeTrackSnapshot = track

        // Only a capture that reached the end of its source track is safe to
        // cache. If the person skips away mid-song, discard the partial PCM
        // rather than later replaying a short profile against a full Spotify
        // timeline.
        val previousFinished = previousTrack?.durationMs?.let { duration ->
            duration > 0L && previousTrack.positionMs >= duration - TRACK_END_TOLERANCE_MS
        } == true
        finalizeTrack(previousId, discard = previousId != null && !previousFinished)
        syncEngine.stop()

        if (newId == null) {
            _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
            return
        }
        beginHapticsFor(newId, track)
    }

    private fun beginHapticsFor(trackId: String, track: SpotifyTrack) {
        if (cache.exists(trackId)) {
            if (syncEngine.load(cache.fileFor(trackId))) {
                // Spotify's SDK only pushes a new state on the next play/pause/seek
                // event — encoding finishing isn't one of those. Without this, a
                // freshly-loaded player just sits prepared and silent until the
                // person happens to interact with Spotify again.
                syncEngine.syncTo(track.positionMs, track.isPaused)
                _haptics.update { it.copy(mode = HapticMode.SYNCED, lastError = null) }
            } else {
                cache.delete(trackId)
                _haptics.update {
                    it.copy(mode = HapticMode.PLAYBACK_FAILED, lastError = "The saved haptic file couldn't be played back. It's been cleared — try recording it again.")
                }
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _haptics.update { it.copy(mode = HapticMode.UNSUPPORTED, lastError = null) }
            return
        }
        if (!hasRecordAudioPermission()) {
            pendingCaptureTrackId = trackId
            _haptics.update { it.copy(mode = HapticMode.NEEDS_PERMISSION, lastError = null) }
            return
        }
        val projection = mediaProjection
        if (projection == null) {
            pendingCaptureTrackId = trackId
            _haptics.update { it.copy(mode = HapticMode.NEEDS_PERMISSION, lastError = null) }
            return
        }
        startCapture(trackId, projection)
    }

    private fun startCapture(trackId: String, projection: MediaProjection) {
        val rec = SystemAudioCaptureRecorder()
        val trackPosition = controller.state.value.track.positionMs.coerceAtLeast(0L)
        val started = rec.start(
            mediaProjection = projection,
            outFile = cache.scratchPcmFile(trackId),
            initialOffsetMs = trackPosition,
        )
        if (!started) {
            _haptics.update {
                it.copy(mode = HapticMode.CAPTURE_FAILED, lastError = "Spotify haptics couldn't start. Android couldn't initialize playback capture; open Debug to see the device-level reason.")
            }
            return
        }
        recorder = rec
        _haptics.update { it.copy(mode = HapticMode.CAPTURING, lastError = null) }

        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            rec.level.collect { level ->
                if (_haptics.value.mode == HapticMode.CAPTURING) {
                    _captureLevel.value = level
                }
            }
        }
    }

    /**
     * Ends whatever [recorder] is doing for [trackId]. When [discard] is
     * false (the normal "track finished/changed" path) a completed pass with
     * real signal in it gets turned into a vibration pattern via
     * [HapticCaptureConverter] and cached. When [discard] is true (e.g.
     * leaving the Spotify section entirely) the partial capture is thrown
     * away instead, since a pattern built from less than the full track
     * would drift out of sync with the real thing on a later replay.
     */
    private fun finalizeTrack(trackId: String?, discard: Boolean) {
        trackId ?: return
        val rec = recorder ?: return
        levelJob?.cancel()
        levelJob = null
        _captureLevel.value = 0f
        // Detach it before launching finalization so a second player-state
        // emission cannot finalize the same recorder twice.
        recorder = null

        if (discard) {
            rec.cancel()
            appContext.stopService(Intent(appContext, PlaybackCaptureService::class.java))
            return
        }

        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }

        viewModelScope.launch {
            val result = rec.stopAndFinish()
            appContext.stopService(Intent(appContext, PlaybackCaptureService::class.java))

            if (result != null && result.hadSignal && result.durationMs > 1500) {
                val converted = withContext(Dispatchers.Default) {
                    // Capture and offline conversion converge here: one
                    // shared OGG shaping path plus the same versioned DSP
                    // layer timeline used by the transient renderer.
                    val output = HapticCaptureConverter.convert(
                        pcmFile = result.pcmFile,
                        sampleRate = result.sampleRate,
                        channelCount = result.channelCount,
                        outputOgg = cache.fileFor(trackId),
                        sensitivity = _haptics.value.sensitivity,
                        bassBoost = AppSettings.state.value.bassEmphasis,
                        density = AppSettings.state.value.hapticDensity,
                    )
                    val analysis = output?.let {
                        MultiLayerAudioAnalyzer.analyze(
                            result.pcmFile,
                            result.sampleRate,
                            result.channelCount,
                            bassEmphasis = AppSettings.state.value.bassEmphasis,
                            density = AppSettings.state.value.hapticDensity,
                        )
                    }
                    output to analysis
                }
                result.pcmFile.takeIf { it.exists() }?.delete()
                val outputOgg = converted.first
                val analysis = converted.second
                if (outputOgg != null) {
                    if (analysis != null) {
                        runCatching { analysis.saveTo(cache.analysisFile(trackId)) }
                    } else {
                        cache.analysisFile(trackId).delete()
                    }
                    if (trackId == activeTrackId) {
                        if (syncEngine.load(cache.fileFor(trackId))) {
                            val liveTrack = controller.state.value.track
                            syncEngine.syncTo(liveTrack.positionMs, liveTrack.isPaused)
                            _haptics.update { it.copy(mode = HapticMode.SYNCED, lastError = null) }
                        } else {
                            cache.delete(trackId)
                            _haptics.update {
                                it.copy(mode = HapticMode.PLAYBACK_FAILED, lastError = "The saved Spotify haptic profile couldn't be played back on this device.")
                            }
                        }
                    }
                } else {
                    cache.delete(trackId)
                    if (trackId == activeTrackId) {
                        _haptics.update {
                            it.copy(mode = HapticMode.ENCODE_FAILED, lastError = "Spotify audio was captured, but the haptic profile could not be encoded.")
                        }
                    }
                }
            } else {
                cache.delete(trackId)
                result?.pcmFile?.takeIf { it.exists() }?.delete()
                val reason = if (result == null || !result.hadSignal) {
                    "Spotify haptics couldn't start because no usable playback audio arrived. Open Debug for the capture diagnostics, then retry."
                } else {
                    "Spotify haptics couldn't build a profile from this short capture."
                }
                if (trackId == activeTrackId) {
                    _haptics.update { it.copy(mode = HapticMode.CAPTURE_FAILED, lastError = reason) }
                }
            }
        }
    }

    /** Stops everything Spotify-side — used when the app switches to another section. */
    fun stopAll() {
        controller.pause()
        val previous = activeTrackId
        activeTrackId = null
        activeTrackSnapshot = null
        finalizeTrack(previous, discard = true)
        mediaProjection?.stop()
        mediaProjection = null
        CaptureDiagnostics.projection(false)
        appContext.stopService(Intent(appContext, PlaybackCaptureService::class.java))
        syncEngine.stop()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
    }

    private fun trackIdOf(track: SpotifyTrack): String? {
        track.uri?.let { return it }
        if (track.title.isBlank() && track.artist.isBlank()) return null
        return "${track.title}::${track.artist}"
    }

    override fun onCleared() {
        super.onCleared()
        stopAll()
        controller.disconnect()
    }
}
