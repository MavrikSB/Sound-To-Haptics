package com.sound.to.haptics.bitchord

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sound.to.haptics.bitchord.auth.AuthStore
import com.sound.to.haptics.bitchord.auth.CapturedSession
import com.sound.to.haptics.bitchord.auth.GoogleAccountSession
import com.sound.to.haptics.bitchord.auth.YouTubeProfile
import com.sound.to.haptics.bitchord.auth.profileId
import com.sound.to.haptics.bitchord.auth.sessionId
import com.sound.to.haptics.bitchord.data.Http
import com.sound.to.haptics.bitchord.data.YtMusicRepository
import com.sound.to.haptics.bitchord.data.innertube.Innertube
import com.sound.to.haptics.bitchord.data.innertube.StreamResolver
import com.sound.to.haptics.bitchord.data.model.SearchFilter
import com.sound.to.haptics.bitchord.data.model.SearchResult
import com.sound.to.haptics.bitchord.data.model.Song
import com.sound.to.haptics.bitchord.data.model.UserPlaylist
import com.sound.to.haptics.bitchord.data.model.durationMillis
import com.sound.to.haptics.bitchord.playback.BitChordPlaybackService
import com.sound.to.haptics.bitchord.playback.BitChordResolverDataSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import com.sound.to.haptics.haptics.CaptureDiagnostics
import com.sound.to.haptics.haptics.CaptureDiagnosticsState
import com.sound.to.haptics.haptics.HapticCaptureConverter
import com.sound.to.haptics.haptics.HapticOggSyncPlayer
import com.sound.to.haptics.haptics.HapticPcmTap
import com.sound.to.haptics.haptics.MultiLayerAudioAnalyzer
import com.sound.to.haptics.haptics.TrackHapticCache
import com.sound.to.haptics.haptics.TransientAudioAnalyzer
import com.sound.to.haptics.haptics.TransientHapticRenderer
import com.sound.to.haptics.haptics.loadTransientAnalysis
import com.sound.to.haptics.haptics.saveTo
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.settings.HapticEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BitChord"
private const val SEEK_JUMP_TOLERANCE_MS = 750L
/** Any backward movement beyond the player's own reporting noise abandons a capture. */
private const val CAPTURE_BACKWARD_TOLERANCE_MS = 50L

enum class BrowseStatus { IDLE, LOADING }

/** Haptic-engine state for a track, mirroring Offline mode's pipeline states. */
enum class HapticMode {
    IDLE,
    CAPTURING,
    SYNCED,
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

/**
 * The "tap a track" conversion surface, modeled on Offline mode's
 * Solving → Analyzing → Building haptic track → Finishing states.
 * Loading audio off the wire happens first; the FFmpeg pass is the same
 * one Offline runs; the output is cached, so repeat plays skip straight
 * into SYNCED playback from the first second.
 */
enum class ConvertStage { DOWNLOADING, ANALYZING, BUILDING }

data class ConvertState(
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val stage: ConvertStage = ConvertStage.DOWNLOADING,
    val progress: Float = 0f,
) {
    val active: Boolean get() = trackId != null
}

data class BitChordSongItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
)

/** One tile on a Home/Discovery shelf — a track, playlist, album or artist. */
data class HomeTileItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
)

/** A shelf of the YouTube Music home feed, parsed for this screen. */
data class HomeShelfItem(
    val title: String,
    val subtitle: String,
    val items: List<HomeTileItem>,
)

data class BitChordPlaylistItem(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val subtitle: String = "Playlist",
)

data class BitChordBrowseState(
    val query: String = "",
    val libraryTracks: List<BitChordSongItem> = emptyList(),
    val recentlyPlayed: List<BitChordSongItem> = emptyList(),
    val playlists: List<BitChordPlaylistItem> = emptyList(),
    val searchResults: List<BitChordSongItem> = emptyList(),
    val searchSuggestions: List<String> = emptyList(),
    val selectedCollection: BitChordPlaylistItem? = null,
    val collectionTracks: List<BitChordSongItem> = emptyList(),
    val collectionLoading: Boolean = false,
    val status: BrowseStatus = BrowseStatus.IDLE,
    val error: String? = null,
    val activeVideoId: String? = null,
    val queue: List<BitChordSongItem> = emptyList(),
    val queueVisible: Boolean = false,
    val homeShelves: List<HomeShelfItem> = emptyList(),
    val homeLoading: Boolean = false,
    val homeError: String? = null,
) {
    val visibleTracks: List<BitChordSongItem>
        get() = if (query.isNotBlank()) searchResults else libraryTracks
}

data class BitChordConnectionState(
    val isControllerConnected: Boolean = false,
    val isSignedIn: Boolean = false,
    val signingIn: Boolean = false,
    val accountName: String? = null,
    val error: String? = null,
    val currentItem: BitChordSongItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class BitChordScreenState(
    val connection: BitChordConnectionState = BitChordConnectionState(),
    val haptics: TrackHapticUiState = TrackHapticUiState(),
    val browse: BitChordBrowseState = BitChordBrowseState(),
    val capabilities: ProviderCapabilities = BitChordProviderCapabilities.current,
)

internal fun Song.toItem() = BitChordSongItem(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMillis(),
)

internal fun UserPlaylist.toItem() = BitChordPlaylistItem(
    id = playlistId,
    name = title,
    thumbnailUrl = thumbnailUrl,
    subtitle = subtitle.ifBlank { "Playlist" },
)

class BitChordViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val cache = TrackHapticCache(appContext)
    private val authStore = AuthStore(appContext)

    private val _connection = MutableStateFlow(BitChordConnectionState())
    private val _haptics = MutableStateFlow(
        TrackHapticUiState(sensitivity = AppSettings.state.value.hapticStrength)
    )
    private val _browse = MutableStateFlow(BitChordBrowseState())

    private val _captureLevel = MutableStateFlow(0f)
    /** Kept separate from [uiState] so the VU meter doesn't recompose the whole screen. */
    val captureLevel: StateFlow<Float> = _captureLevel.asStateFlow()
    val captureDiagnostics: StateFlow<CaptureDiagnosticsState> = CaptureDiagnostics.state

    private val syncEngine = HapticOggSyncPlayer(appContext) { message ->
        _haptics.update { it.copy(mode = HapticMode.PLAYBACK_FAILED, lastError = message) }
    }
    private val transientRenderer by lazy { TransientHapticRenderer(appContext) }

    private var libraryJob: Job? = null
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var collectionJob: Job? = null
    private var progressJob: Job? = null
    private var convertJob: Job? = null

    /** The tap-time "converting" surface — cleared before playback starts or when it falls back. */
    private val _convert = MutableStateFlow(ConvertState())
    val convertState: StateFlow<ConvertState> = _convert.asStateFlow()

    private var activeVideoId: String? = null
    private var lastRequestedItem: BitChordSongItem? = null
    private var pauseAnchorPositionMs: Long? = null
    private var lastPollPositionMs: Long? = null

    val uiState: StateFlow<BitChordScreenState> =
        combine(_connection, _haptics, _browse) { connection, haptics, browse ->
            BitChordScreenState(connection, haptics, browse)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BitChordScreenState())

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, BitChordPlaybackService::class.java)),
    ).setApplicationLooper(Looper.getMainLooper()).buildAsync()

    @Volatile
    private var controller: MediaController? = null

    init {
        BitChordStreamHooks.initInnerTubeX(appContext)
        restoreSession()

        viewModelScope.launch {
            AppSettings.state.map { it.hapticStrength }.distinctUntilChanged().collect { strength ->
                _haptics.update { it.copy(sensitivity = strength) }
            }
        }

        viewModelScope.launch {
            val built = runCatching { awaitListenerFuture(controllerFuture) }.getOrElse { error ->
                Log.e(TAG, "controller failed to build", error)
                _connection.update { it.copy(error = "The music player couldn't start. ${error.message}") }
                return@launch
            }
            controller = built
            _connection.update { it.copy(isControllerConnected = true) }
            wirePlayerListener(built)
            startPositionLoop()
        }
    }

    // ─── Auth ────────────────────────────────────────────────────────────────

    /** True when the stored session is present and shaped like a signed-in one. */
    fun isSignedIn(): Boolean = authStore.isSignedIn

    fun beginLogin() {
        _connection.update { it.copy(signingIn = true, error = null) }
    }

    fun cancelLogin() {
        _connection.update { it.copy(signingIn = false) }
    }

    /** Called by the sign-in WebView relay after a page confirmed itself signed in. */
    fun onSessionCaptured(session: CapturedSession) {
        viewModelScope.launch {
            _connection.update { it.copy(signingIn = true, error = null) }
            val ok = completeSignIn(session)
            if (ok) {
                _connection.update { it.copy(signingIn = false, isSignedIn = true, error = null) }
                loadLibrary()
            } else {
                _connection.update {
                    it.copy(
                        signingIn = false,
                        isSignedIn = false,
                        error = "That session couldn't be verified with YouTube Music. Try signing in again.",
                    )
                }
            }
        }
    }

    private suspend fun completeSignIn(session: CapturedSession): Boolean {
        if (!session.loggedIn) return false
        return withContext(Dispatchers.IO) {
            val previousCookie = Innertube.cookie
            Innertube.cookie = session.cookie
            Innertube.adoptSessionScope(
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                visitorData = session.visitorData,
                clientVersion = session.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            }
            if (account == null) {
                Innertube.cookie = previousCookie
                return@withContext false
            }

            val existing = authStore.sessions.firstOrNull {
                account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
            }
            val accountId = existing?.accountId ?: sessionId(session.cookie, null)
            val selectedProfileId = profileId(session.pageId, session.dataSyncId, account.name)
            val previous = authStore.sessions.firstOrNull { it.accountId == accountId }
            val profile = YouTubeProfile(
                profileId = selectedProfileId,
                name = account.name,
                handle = account.email,
                avatar = account.thumbnailUrl,
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                isBrandAccount = session.pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = previous?.profiles.orEmpty().filterNot { known ->
                known.profileId == profile.profileId ||
                    (hasRealIdentity && known.profileId.startsWith("profile:"))
            } + profile
            val stored = GoogleAccountSession(
                accountId = accountId,
                cookie = session.cookie,
                name = account.name,
                email = account.email,
                profiles = profiles,
                activeProfileId = profile.profileId,
            )
            authStore.upsertSession(stored)
            authStore.cookie = session.cookie
            Innertube.cookie = session.cookie
            Innertube.selectChannel(profile.pageId, profile.dataSyncId, profile.authUser)
            StreamResolver.onSessionChanged()
            _connection.update { it.copy(accountName = account.name) }
            true
        }
    }

    fun signOut() {
        stopControllerPlayback()
        stopHaptics()
        authStore.signOut()
        Innertube.cookie = null
        Innertube.selectChannel(null, null, null)
        StreamResolver.onSessionChanged()
        resetPlaybackState()
        _connection.update {
            it.copy(isSignedIn = false, accountName = null, signingIn = false, error = null)
        }
        _browse.update {
            it.copy(
                libraryTracks = emptyList(),
                recentlyPlayed = emptyList(),
                playlists = emptyList(),
                searchResults = emptyList(),
                searchSuggestions = emptyList(),
                selectedCollection = null,
                collectionTracks = emptyList(),
                queue = emptyList(),
                activeVideoId = null,
                error = null,
                homeShelves = emptyList(),
                homeLoading = false,
                homeError = null,
            )
        }
    }

    /** Lets the UI retry failed library/home fetches. */
    fun retryLibrary() {
        if (!isSignedIn()) return
        loadLibrary()
        loadHome()
    }

    private fun restoreSession() {
        // A corrupted keystore or prefs payload must not take the app down at
        // launch — fall back to signed-out rather than crash.
        val session = runCatching {
            authStore.activeSession?.takeIf { authStore.isSignedIn }
        }.getOrElse { e ->
            Log.e(TAG, "stored session unreadable; clearing it", e)
            runCatching { authStore.signOut() }
            null
        }
        if (session == null) {
            _connection.update { it.copy(isSignedIn = false) }
            return
        }
        Innertube.cookie = session.cookie
        val activeProfile = session.profiles.firstOrNull { it.profileId == session.activeProfileId }
        Innertube.selectChannel(
            activeProfile?.pageId,
            activeProfile?.dataSyncId,
            activeProfile?.authUser,
        )
        _connection.update { it.copy(isSignedIn = true, accountName = activeProfile?.name) }
        loadLibrary()
        loadHome()
    }

    /** Clears a dead session and asks the user to sign in again. */
    private fun handleExpiredSession() {
        Log.w(TAG, "session rejected by YouTube Music; clearing it")
        stopControllerPlayback()
        stopHaptics()
        authStore.signOut()
        Innertube.cookie = null
        Innertube.selectChannel(null, null, null)
        StreamResolver.onSessionChanged()
        _connection.update {
            it.copy(
                isSignedIn = false,
                accountName = null,
                error = "Your YouTube Music session expired or was revoked. Sign in again.",
            )
        }
        _browse.update { BitChordBrowseState() }
    }

    // ─── Browse / search ─────────────────────────────────────────────────────

    // ─── Home / discovery ─────────────────────────────────────────────────────

    /**
     * Maps a transport/service failure to honest copy. Never invents detail:
     * everything not recognized falls back to a connection message we can
     * stand behind.
     */
    private fun serviceStatusCopy(raw: String?, fallback: String): String {
        val r = raw.orEmpty()
        return when {
            r.contains("429") -> "YouTube Music is rate-limiting — try again in a moment."
            Regex("50[0234]").containsMatchIn(r) -> "YouTube Music is having server trouble — try again shortly."
            r.contains("401") || r.contains("403") -> "Your session was rejected — sign in again."
            r.contains("UnknownHost", ignoreCase = true) ||
                r.contains("Connect", ignoreCase = true) ||
                r.contains("Socket", ignoreCase = true) ||
                r.contains("timeout", ignoreCase = true) ->
                "Can't reach YouTube Music — check your connection and try again."
            r.isBlank() -> "Can't reach YouTube Music — check your connection and try again."
            else -> fallback
        }
    }

    private fun loadHome() {
        homeJob?.cancel()
        _browse.update { it.copy(homeLoading = true, homeError = null) }
        homeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { YtMusicRepository.home() }
            val feed = result.getOrNull()
            val rawError = result.exceptionOrNull()?.message
            val shelves = feed?.shelves.orEmpty()
                .filter { it.layout != com.sound.to.haptics.bitchord.data.model.ShelfLayout.VIDEO }
                .mapNotNull { shelf ->
                    val items = shelf.items
                        .filter { it.videoId != null || it.browseId != null }
                        .take(12)
                        .map { item ->
                            HomeTileItem(
                                key = item.videoId ?: "browse:${item.browseId}:${item.title}",
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                videoId = item.videoId,
                                browseId = item.browseId,
                            )
                        }
                    if (items.isEmpty()) null
                    else HomeShelfItem(shelf.title, shelf.subtitle, items)
                }
                .take(3)
            _browse.update {
                it.copy(
                    homeShelves = shelves,
                    homeLoading = false,
                    // Empty home can mean "nothing recommended right now" just as
                    // well as "request failed" — surface the difference honestly.
                    homeError = if (shelves.isEmpty()) {
                        serviceStatusCopy(rawError, "Discovery couldn't load — try again.")
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** A Home tile either plays (tracks) or opens its collection (playlists/albums). */
    fun openHomeTile(item: HomeTileItem) {
        if (item.videoId != null) {
            playTrack(
                BitChordSongItem(
                    videoId = item.videoId,
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    durationMs = 0L,
                ),
            )
            return
        }
        val browseId = item.browseId
        if (!browseId.isNullOrBlank()) {
            openCollection(
                BitChordPlaylistItem(
                    id = browseId,
                    name = item.title,
                    thumbnailUrl = item.thumbnailUrl,
                    subtitle = item.subtitle,
                ),
                withVLPrefix = false,
            )
        }
    }

    private fun loadLibrary() {
        libraryJob?.cancel()
        _browse.update { it.copy(status = BrowseStatus.LOADING, error = null) }
        libraryJob = viewModelScope.launch {
            val libraryResult = withContext(Dispatchers.IO) { YtMusicRepository.library() }
            if (libraryResult.isFailure) {
                // A 401/403 survives the repository's single refresh-and-retry too
                // often to be transient. Confirm the session is really dead
                // before clearing it — one extra request, off the UI thread.
                val account = withContext(Dispatchers.IO) { YtMusicRepository.account() }
                if (account.isFailure && isSignedIn()) {
                    handleExpiredSession()
                    return@launch
                }
            }
            val historyResult = withContext(Dispatchers.IO) { YtMusicRepository.history() }
            val playlistsResult = withContext(Dispatchers.IO) { YtMusicRepository.userPlaylists() }
            val library = libraryResult.getOrNull()
            val history = historyResult.getOrNull()
            val playlists = playlistsResult.getOrNull()
            val liked = library?.likedSongs.orEmpty().map { it.toItem() }
            val historyItems = history.orEmpty().map { it.toItem() }
            val libraryTracks = library?.librarySongs.orEmpty().map { it.toItem() }
                .ifEmpty { liked }
                .ifEmpty { historyItems }
            val rawError = libraryResult.exceptionOrNull()?.message
                ?: historyResult.exceptionOrNull()?.message
            _browse.update {
                it.copy(
                    libraryTracks = libraryTracks,
                    recentlyPlayed = historyItems.take(20),
                    playlists = playlists.orEmpty().map { it.toItem() },
                    status = BrowseStatus.IDLE,
                    error = if (libraryTracks.isEmpty() && playlists.isNullOrEmpty()) {
                        serviceStatusCopy(rawError, "Couldn't load your library — try the search bar.")
                    } else null,
                )
            }
        }
    }

    fun updateQuery(query: String) {
        searchJob?.cancel()
        suggestionJob?.cancel()
        _browse.update {
            it.copy(
                query = query,
                searchResults = if (query.trim() == it.query.trim()) it.searchResults else emptyList(),
                status = BrowseStatus.IDLE,
                error = null,
            )
        }
        if (query.trim().length >= 2) {
            suggestionJob = viewModelScope.launch {
                delay(250)
                if (_browse.value.query.trim() != query.trim()) return@launch
                val suggestions = withContext(Dispatchers.IO) {
                    YtMusicRepository.searchSuggestions(query.trim()).getOrNull()
                }.orEmpty()
                if (_browse.value.query.trim() == query.trim()) {
                    _browse.update { it.copy(searchSuggestions = suggestions.take(6)) }
                }
            }
        } else {
            _browse.update { it.copy(searchSuggestions = emptyList()) }
        }
    }

    fun search() {
        searchJob?.cancel()
        val query = _browse.value.query.trim()
        if (query.isEmpty()) {
            _browse.update { it.copy(searchResults = emptyList(), status = BrowseStatus.IDLE, error = null) }
            return
        }
        _browse.update { it.copy(status = BrowseStatus.LOADING, error = null, searchSuggestions = emptyList()) }
        searchJob = viewModelScope.launch {
            val searchResult = withContext(Dispatchers.IO) {
                YtMusicRepository.search(query, SearchFilter.SONGS)
            }
            if (_browse.value.query.trim() != query) return@launch
            val thrown = searchResult.exceptionOrNull()
            val results = searchResult.getOrNull()
            val tracks = results.orEmpty().mapNotNull { result ->
                when (result) {
                    is SearchResult.TopTrack -> result.song.toItem()
                    is SearchResult.Track -> result.song.toItem()
                    is SearchResult.Browse -> null
                }
            }
            _browse.update {
                it.copy(
                    searchResults = tracks,
                    status = BrowseStatus.IDLE,
                    error = when {
                        // "Failed" and "no results" are different states — the
                        // failure deserves honest copy and a working retry.
                        thrown != null ->
                            serviceStatusCopy(thrown.message, "Search failed — try again.")
                        tracks.isEmpty() -> "No tracks found for \"$query\"."
                        else -> null
                    },
                )
            }
        }
    }

    fun openLikedSongs() {
        val collection = BitChordPlaylistItem(
            id = LIKED_COLLECTION_ID,
            name = "Liked music",
            thumbnailUrl = null,
            subtitle = "Playlist",
        )
        _browse.update {
            it.copy(selectedCollection = collection, collectionLoading = true, collectionTracks = emptyList())
        }
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            val library = withContext(Dispatchers.IO) { YtMusicRepository.library().getOrNull() }
            val tracks = library?.likedSongs.orEmpty().map { it.toItem() }
            if (_browse.value.selectedCollection?.id != LIKED_COLLECTION_ID) return@launch
            _browse.update { it.copy(collectionTracks = tracks, collectionLoading = false) }
        }
    }

    fun openPlaylist(item: BitChordPlaylistItem) = openCollection(item, withVLPrefix = true)

    private fun openCollection(item: BitChordPlaylistItem, withVLPrefix: Boolean) {
        _browse.update {
            it.copy(selectedCollection = item, collectionLoading = true, collectionTracks = emptyList())
        }
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                YtMusicRepository.browseSongs(if (withVLPrefix) "VL${item.id}" else item.id).getOrNull()
            }
            if (_browse.value.selectedCollection?.id != item.id) return@launch
            val tracks = page?.songs.orEmpty().map { it.toItem() }
            _browse.update { it.copy(collectionTracks = tracks, collectionLoading = false) }
        }
    }

    fun closeCollection() {
        collectionJob?.cancel()
        _browse.update { it.copy(selectedCollection = null, collectionTracks = emptyList(), collectionLoading = false) }
    }

    // ─── Playback ────────────────────────────────────────────────────────────

    private fun toMediaItem(item: BitChordSongItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(item.videoId)
            .setUri(BitChordResolverDataSourceFactory.watchUri(item.videoId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setArtworkUri(item.thumbnailUrl?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply { putLong(DURATION_EXTRA, item.durationMs) })
                    .build()
            )
            .build()

    /** Plays [item] from a browse list; the rest of the visible list follows it. */
    fun playTrack(item: BitChordSongItem) {
        val queue = _browse.value.visibleTracks
        val startIndex = queue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        playQueue(queue.ifEmpty { listOf(item) }, startIndex)
    }

    /** Plays a collection's track list from [item]'s position. */
    fun playCollectionTrack(item: BitChordSongItem) {
        val queue = _browse.value.collectionTracks
        val startIndex = queue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        playQueue(queue, startIndex)
    }

    private fun playQueue(items: List<BitChordSongItem>, startIndex: Int) {
        val start = items.getOrNull(startIndex) ?: items.firstOrNull() ?: return
        val videoId = start.videoId
        if (!videoId.isNullOrBlank() && !cache.exists(videoId)) {
            // First tap goes through the full Offline pipeline: resolve the
            // best downloadable rendition, decode to raw PCM, shape it with
            // the same FFmpeg graph Offline runs, cache the haptic OGG — and
            // only then start playback, so haptics are live from second 0
            // instead of after a capture pass.
            beginConvertThenPlay(start, items, startIndex)
            return
        }
        startPlayback(items, startIndex)
    }

    private fun startPlayback(items: List<BitChordSongItem>, startIndex: Int) {
        val c = controller ?: return
        val start = items.getOrNull(startIndex) ?: items.firstOrNull() ?: return
        _browse.update { it.copy(activeVideoId = start.videoId, queue = items, queueVisible = false) }
        lastRequestedItem = start
        c.setMediaItems(items.map(::toMediaItem), startIndex, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    /** Cancels an in-flight first-tap conversion and clears the converting surface. */
    fun cancelConvert() {
        convertJob?.cancel()
        convertJob = null
        _convert.value = ConvertState()
    }

    private fun beginConvertThenPlay(head: BitChordSongItem, items: List<BitChordSongItem>, startIndex: Int) {
        val videoId = head.videoId
        convertJob?.cancel()
        convertJob = viewModelScope.launch {
            val app = getApplication<Application>()
            _convert.update {
                ConvertState(
                    trackId = videoId,
                    title = head.title,
                    artist = head.artist,
                    stage = ConvertStage.DOWNLOADING,
                    progress = 0.05f,
                )
            }
            val downloadedAlready = findCompleteDownload(app.cacheDir, videoId)
            val dlAudio = downloadedAlready ?: File(app.cacheDir, "bitchord_dl_${videoId}_${System.nanoTime()}.m4a")
            if (downloadedAlready != null) {
                Log.d(TAG, "reusing completed local audio for $videoId: ${downloadedAlready.name}")
                // Sweep any abandoned partial downloads of the same track.
                runCatching {
                    app.cacheDir.listFiles { f ->
                        f.name.startsWith("bitchord_dl_${videoId}_") && f.name.endsWith(".tmp")
                    }?.forEach { it.delete() }
                }
            } else {
            try {
                val stream = withContext(Dispatchers.IO) {
                    StreamResolver.resolveForDownload(videoId, maxKbps = 256, requireM4a = false)
                }
                val url = stream.url
                if (url.isBlank()) throw IllegalStateException("download resolution returned nothing")
                val headers = StreamResolver.mediaHeadersFor(url)
                val spool = File(app.cacheDir, "${dlAudio.name}.tmp")
                val written = withContext(Dispatchers.IO) { downloadWithHeaders(url, headers, spool, dlAudio) }
                if (!written) throw IllegalStateException("download produced no bytes")
            } catch (e: CancellationException) {
                cleanupQuietly(dlAudio, File(app.cacheDir, "${dlAudio.name}.tmp"))
                throw e
            } catch (e: Exception) {
                // Couldn't load a local copy even though the stream may still
                // play fine — fall back to the capture-armed stream so the tap
                // never dead-ends. Haptics catch up during the first play.
                Log.w(TAG, "download step failed; falling back to capture-armed stream", e)
                cleanupQuietly(dlAudio, File(app.cacheDir, "${dlAudio.name}.tmp"))
                _convert.value = ConvertState()
                startPlayback(items, startIndex)
                return@launch
            }
            }

            _convert.update { it.copy(stage = ConvertStage.ANALYZING, progress = 0.55f) }
            val bass = AppSettings.state.value.bassEmphasis
            val density = AppSettings.state.value.hapticDensity
            val analysisPcm = File(app.cacheDir, "bitchord_analyze_${System.nanoTime()}.pcm")
            val analysis = if (ffmpegDecodeToPcm(dlAudio, analysisPcm)) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        TransientAudioAnalyzer.analyze(
                            analysisPcm,
                            sampleRate = 44100,
                            channelCount = 2,
                            bassEmphasis = bass,
                            hapticDensity = density,
                        )
                    }.getOrNull()
                }
            } else null
            if (analysis != null) runCatching { analysis.saveTo(cache.transientAnalysisFile(videoId)) }

            _convert.update { it.copy(stage = ConvertStage.BUILDING, progress = 0.8f) }
            val ogg = try {
                HapticCaptureConverter.convert(
                    pcmFile = analysisPcm,
                    sampleRate = 44100,
                    channelCount = 2,
                    outputOgg = cache.fileFor(videoId),
                    sensitivity = AppSettings.state.value.hapticStrength,
                    bassBoost = bass,
                    density = density,
                )
            } finally {
                analysisPcm.delete()
                dlAudio.delete()
            }
            _convert.value = ConvertState()
            if (ogg == null) {
                Log.w(TAG, "haptic OGG encode failed for $videoId; falling back to capture-armed stream")
                startPlayback(items, startIndex)
                return@launch
            }
            startPlayback(items, startIndex)
        }
    }

    /** GET [url] with [headers] into a cache file. False on transport failure.
     *  Note: OkHttp's per-call timeout is applied via a pooled client clone —
     *  a connection that accepts TCP but never sends bytes would otherwise
     *  sit in [copyTo] forever. */
    private fun downloadWithHeaders(url: String, headers: Map<String, String>, spool: File, dest: File): Boolean = try {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.get().build()
        Http.client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
            .newCall(req)
            .execute()
            .use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "download failed: HTTP ${response.code}")
                false
            } else {
                response.body.byteStream().use { ins ->
                    FileOutputStream(spool).use { outs -> ins.copyTo(outs, bufferSize = 1 shl 16) }
                }
                spool.renameTo(dest)
                dest.exists() && dest.length() > 0
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "download threw: ${e.message}")
        spool.delete()
        false
    }

    /** One FFmpeg pass: container → raw stereo s16le 44.1 kHz for analysis + encode. */
    private suspend fun ffmpegDecodeToPcm(source: File, pcm: File): Boolean = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            val cmd = "-y -i \"${source.absolutePath}\" -vn -ac 2 -ar 44100 -f s16le \"${pcm.absolutePath}\""
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(cmd) { result ->
                if (cont.isActive) cont.resume(com.arthenica.ffmpegkit.ReturnCode.isSuccess(result.returnCode))
            }
            cont.invokeOnCancellation { session.cancel() }
        }
    }

    private fun cleanupQuietly(vararg files: File) {
        files.forEach { runCatching { it.delete() } }
    }

    /** A previously completed, non-temp download of [videoId], if one exists.
     *  Replayed taps skip the network leg and go straight to Analyzing —
     *  re-fetching 4 MB of already-downloaded audio is the single most common
     *  way this flow "never finishes" on a slow link. */
    private fun findCompleteDownload(cacheDir: File, videoId: String): File? =
        cacheDir.listFiles { file ->
            file.name.startsWith("bitchord_dl_${videoId}_") &&
                file.name.endsWith(".m4a") &&
                file.length() > 262_144L // > 256 KB — a real rendition, not a fragment
        }?.maxByOrNull { it.length() }

    /** Replays the last requested track after a failure. */
    fun retryPlayback() {
        val item = lastRequestedItem ?: return
        playTrack(item)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipNext() {
        controller?.let { c -> if (c.hasNextMediaItem()) c.seekToNextMediaItem() }
    }

    fun skipPrevious() {
        controller?.let { c ->
            if (c.currentPosition > 3_000L) {
                c.seekTo(0)
            } else if (c.hasPreviousMediaItem()) {
                c.seekToPreviousMediaItem()
            } else {
                c.seekTo(0)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        val before = c.currentPosition
        val backwardLimit = if (_haptics.value.mode == HapticMode.CAPTURING) CAPTURE_BACKWARD_TOLERANCE_MS else SEEK_JUMP_TOLERANCE_MS
        if (clamped - before > SEEK_JUMP_TOLERANCE_MS) {
            // Forward jump: no audio flows for the skipped stretch, but the
            // OGG must still span it — hole-punch it.
            if (_haptics.value.mode == HapticMode.CAPTURING) {
                HapticPcmTap.activeCapture?.recordHole(before, clamped)
            }
        } else if (before - clamped > backwardLimit) {
            // Backward seeks cannot be hole-punched; abandon the learning pass.
            HapticPcmTap.cancel()
            _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
        }
        c.seekTo(clamped)
        lastPollPositionMs = clamped
        // The pause-anchor path must not record the same jump a second time.
        pauseAnchorPositionMs = null
        if (_haptics.value.mode == HapticMode.SYNCED) {
            syncEngine.syncTo(clamped, !c.isPlaying)
        }
        _connection.update { it.copy(positionMs = clamped) }
    }

    fun skipForward() = seekTo((_connection.value.positionMs + 10_000L).coerceAtMost(_connection.value.durationMs))

    fun skipBackward() = seekTo((_connection.value.positionMs - 10_000L).coerceAtLeast(0L))

    fun toggleQueue() {
        _browse.update { it.copy(queueVisible = !it.queueVisible) }
        refreshQueue()
    }

    /** Plays the queue item at [index]. */
    fun seekToQueueItem(index: Int) {
        val c = controller ?: return
        if (index < 0 || index >= c.mediaItemCount) return
        val currentIndex = c.currentMediaItemIndex
        if (index == currentIndex) {
            // Restart the current track rather than no-op'ing.
            seekTo(0)
        } else {
            // Switching queue items goes through the same transition path as
            // skipNext — haptics for the old track finalize naturally there.
            c.seekTo(index, 0L)
        }
        lastPollPositionMs = null
        refreshQueue()
    }

    private fun refreshQueue() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).mapNotNull { index ->
            c.getMediaItemAt(index).let(::songItemFromMediaItem)
        }
        if (items.isNotEmpty()) {
            _browse.update { it.copy(queue = items) }
        }
    }

    private fun songItemFromMediaItem(mediaItem: MediaItem): BitChordSongItem? {
        val videoId = mediaItem.mediaId.takeIf { it.isNotBlank() }
        val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
        if (videoId.isNullOrBlank() && title.isBlank()) return null
        return BitChordSongItem(
            videoId = videoId.orEmpty(),
            title = title,
            artist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
            durationMs = mediaItem.mediaMetadata.extras?.getLong(DURATION_EXTRA) ?: 0L,
        )
    }

    private fun currentSongItem(): BitChordSongItem? =
        controller?.currentMediaItem?.let(::songItemFromMediaItem)

    private fun wirePlayerListener(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem?.let(::songItemFromMediaItem)
                onActiveTrackChanged(item, reason)
                _connection.update {
                    it.copy(currentItem = item, positionMs = 0L, durationMs = item?.durationMs ?: 0L)
                }
                refreshQueue()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _connection.update { it.copy(isPlaying = isPlaying) }
                val position = c.currentPosition.coerceAtLeast(0L)
                if (!isPlaying) {
                    pauseAnchorPositionMs = position
                } else {
                    val anchor = pauseAnchorPositionMs
                    if (anchor != null && position - anchor > 150L) {
                        HapticPcmTap.activeCapture?.recordHole(anchor, position)
                    }
                    pauseAnchorPositionMs = null
                }
                if (_haptics.value.mode == HapticMode.SYNCED) {
                    syncEngine.syncTo(c.currentPosition, !isPlaying)
                }
                if (isPlaying) {
                    if (isTransientEngineActive()) {
                        transientRenderer.start { c.currentPosition.toInt() }
                    }
                } else {
                    transientRenderer.stop()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _connection.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error", error)
                val reason = when {
                    isPermanentlyUnplayable(error) ->
                        "This track can't be played — it may be age-restricted or not available in your region."
                    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Couldn't reach YouTube Music — check your connection and try again."
                    else ->
                        "This track couldn't be played. Tap retry."
                }
                _connection.update { it.copy(error = reason) }
                // Leave nothing running behind a dead track.
                syncEngine.stop()
                transientRenderer.stop()
                HapticPcmTap.cancel()
                _haptics.update {
                    it.copy(mode = HapticMode.IDLE, lastError = reason)
                }
                _captureLevel.value = 0f
            }
        })
    }

    /** Raw playback failures get mapped to actionable copy; nothing falls through raw. */
    private fun isPermanentlyUnplayable(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause.javaClass.simpleName == "PermanentlyUnplayableException") return true
            val next = cause.cause
            cause = if (next === cause) null else next
        }
        return false
    }

    private fun startPositionLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    val position = c.currentPosition.coerceAtLeast(0L)
                    val duration = c.duration.takeIf { it > 0 } ?: _connection.value.durationMs
                    _connection.update { current -> current.copy(positionMs = position, durationMs = duration) }
                    val item = currentSongItem()
                    if (item != null && _browse.value.activeVideoId != item.videoId) {
                        _browse.update { it.copy(activeVideoId = item.videoId) }
                    }
                    // Backward scrubs caught by the poll rather than by seekTo:
                    // abandon the learning pass. Forward jumps land through seekTo.
                    if (_haptics.value.mode == HapticMode.CAPTURING) {
                        val last = lastPollPositionMs
                        if (last != null && position - last < -CAPTURE_BACKWARD_TOLERANCE_MS) {
                            HapticPcmTap.cancel()
                            _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
                        }
                    }
                    lastPollPositionMs = position
                    if (_haptics.value.mode == HapticMode.CAPTURING) {
                        _captureLevel.value = HapticPcmTap.activeCapture?.level ?: 0f
                    }
                    if (_haptics.value.mode == HapticMode.SYNCED) {
                        syncEngine.syncTo(position, !c.isPlaying)
                    }
                }
                delay(250)
            }
        }
    }

    // ─── Haptics orchestration ───────────────────────────────────────────────

    fun updateSensitivity(v: Float) {
        AppSettings.setHapticStrength(v)
        _haptics.update { it.copy(sensitivity = v.coerceIn(0f, 1f)) }
    }

    /** Re-arms capture/sync after the BitChord tab was temporarily left. */
    fun onScreenVisible() {
        val c = controller ?: return
        val item = currentSongItem()
        if (item != null && activeVideoId == null) {
            onActiveTrackChanged(item, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        }
    }

    /** Back to a clean slate: nothing active, nothing primed, nothing half-learned. */
    private fun resetPlaybackState() {
        activeVideoId = null
        pauseAnchorPositionMs = null
        lastPollPositionMs = null
        _captureLevel.value = 0f
        _connection.update {
            it.copy(isPlaying = false, currentItem = null, positionMs = 0L, durationMs = 0L)
        }
        _browse.update { it.copy(activeVideoId = null) }
    }

    private fun onActiveTrackChanged(item: BitChordSongItem?, reason: Int) {
        val newId = item?.videoId
        val previousId = activeVideoId
        // Anchors always reset — never carry a stale position or pause anchor
        // into a new track, even for a same-song repeat.
        lastPollPositionMs = null
        pauseAnchorPositionMs = null
        if (newId == previousId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            // Same song, new position: the position loop reconciles haptics.
            return
        }
        val sameSongRepeat = newId == previousId
        val previousIdTrack = previousId ?: newId
        if (newId == null) {
            finalizeCapture(previousIdTrack, discard = false)
            syncEngine.stop()
            transientRenderer.stop()
            resetPlaybackState()
            _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
            return
        }
        if (sameSongRepeat) {
            // A repeated song is a track boundary too: cache the finished pass
            // and start a fresh one for the repeat.
            activeVideoId = newId
            finalizeCapture(previousIdTrack, discard = false)
            syncEngine.stop()
            transientRenderer.stop()
            _browse.update { it.copy(activeVideoId = newId) }
            beginHapticsFor(newId, 0L)
            return
        }
        val previousFinished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        activeVideoId = newId

        // Stop whatever belonged to the previous track before loading the next one.
        finalizeCapture(previousId, discard = previousId != null && !previousFinished)
        syncEngine.stop()
        transientRenderer.stop()

        _browse.update { it.copy(activeVideoId = newId) }
        beginHapticsFor(newId, controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
    }

    private fun beginHapticsFor(trackId: String, positionMs: Long) {
        if (cache.exists(trackId)) {
            if (syncEngine.load(cache.fileFor(trackId))) {
                val c = controller
                syncEngine.syncTo(positionMs, c == null || !c.isPlaying)
                loadTransientFor(trackId)
                _haptics.update { it.copy(mode = HapticMode.SYNCED, lastError = null) }
            } else {
                cache.delete(trackId)
                _haptics.update {
                    it.copy(
                        mode = HapticMode.PLAYBACK_FAILED,
                        lastError = "The saved haptic file couldn't be played back. It's been cleared — try again.",
                    )
                }
            }
            return
        }
        // Learn from the decoded PCM the tap sees — no permission prompt, no mic, no capture stream.
        HapticPcmTap.arm(
            HapticPcmTap.ArmRequest(
                scratchFile = cache.scratchPcmFile(trackId),
                initialOffsetMs = positionMs,
            )
        )
        _haptics.update { it.copy(mode = HapticMode.CAPTURING, lastError = null) }
    }

    private fun loadTransientFor(trackId: String) {
        if (!isTransientEngineActive()) return
        val analysis = loadTransientAnalysis(cache.transientAnalysisFile(trackId)) ?: return
        transientRenderer.load(analysis)
        val c = controller
        if (c != null && c.isPlaying) {
            transientRenderer.start { c.currentPosition.toInt() }
        }
    }

    private fun isTransientEngineActive(): Boolean {
        val ctx = getApplication<Application>()
        if (AppSettings.state.value.hapticEngine != HapticEngine.TRANSIENT) return false
        if (!TransientHapticRenderer.isSupported(ctx)) return false
        // Mirror the offline rule: continuous haptics only while a Bluetooth
        // output is connected; the phone's own motor still delivers them,
        // but transient accents on top of that route are untestable there.
        return !isBluetoothOutputConnected()
    }

    private fun isBluetoothOutputConnected(): Boolean {
        val ctx = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return false
        val audioManager = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    /**
     * Ends the in-progress learning pass for [trackId], if any, on a background
     * thread. When [discard] is true the partial capture is thrown away — a
     * profile built from less than the track would drift out of sync with the
     * real thing on a later replay.
     *
     * The [generationKey] mechanism stops a stale pass from deleting or
     * overwriting a freshly-built profile: [recaptureCurrentTrack] bumps the
     * counter, and any still-running conversion for that track aborts before
     * it touches the cache.
     */
    private val hapticGenerations = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun finalizeCapture(trackId: String?, discard: Boolean) {
        trackId ?: return
        val active = HapticPcmTap.detachActive() ?: return
        val generation = hapticGenerations.merge(trackId, 1) { old, _ -> old + 1 } ?: 1
        _captureLevel.value = 0f
        if (discard) {
            viewModelScope.launch(Dispatchers.Default) { active.cancel() }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val result = active.finish()
            if (result == null || !result.hadSignal || result.durationMs < 1500L) {
                if (hapticGenerations[trackId] != generation) return@launch
                cache.delete(trackId)
                val reason = if (result == null) {
                    "No usable audio arrived while this track played."
                } else {
                    "This capture was too short to build a profile from."
                }
                withContext(Dispatchers.Main) {
                    if (trackId == activeVideoId) {
                        _haptics.update { it.copy(mode = HapticMode.CAPTURE_FAILED, lastError = reason) }
                    }
                }
                return@launch
            }
            runCatching {
                val conversion = convertFor(trackId, result)
                if (hapticGenerations[trackId] != generation) {
                    // A redo or a track change superseded this pass; leave the
                    // cache exactly as the newer pass left it.
                    result.pcmFile.takeIf { it.exists() }?.delete()
                    return@runCatching
                }
                applyConversion(trackId, result, conversion)
            }.onFailure { error ->
                Log.e(TAG, "haptic conversion failed", error)
                if (hapticGenerations[trackId] == generation) {
                    withContext(Dispatchers.Main) {
                        if (trackId == activeVideoId) {
                            cache.delete(trackId)
                            _haptics.update {
                                it.copy(
                                    mode = HapticMode.ENCODE_FAILED,
                                    lastError = "The track was captured, but the haptic profile couldn't be encoded.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** FFmpeg + analyzers; runs on a background coroutine. */
    private suspend fun convertFor(
        trackId: String,
        result: com.sound.to.haptics.haptics.PcmCapture.Result,
    ): Triple<java.io.File?, com.sound.to.haptics.haptics.LayeredHapticAnalysis?, com.sound.to.haptics.haptics.TransientAnalysis?> {
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
        val transient = output?.let {
            TransientAudioAnalyzer.analyze(
                result.pcmFile,
                result.sampleRate,
                result.channelCount,
                bassEmphasis = AppSettings.state.value.bassEmphasis,
                hapticDensity = AppSettings.state.value.hapticDensity,
            )
        }
        result.pcmFile.takeIf { it.exists() }?.delete()
        return Triple(output, analysis, transient)
    }

    private suspend fun applyConversion(
        trackId: String,
        result: com.sound.to.haptics.haptics.PcmCapture.Result,
        conversion: Triple<java.io.File?, com.sound.to.haptics.haptics.LayeredHapticAnalysis?, com.sound.to.haptics.haptics.TransientAnalysis?>,
    ) {
        val (outputOgg, analysis, transient) = conversion
        if (outputOgg == null) {
            cache.delete(trackId)
            withContext(Dispatchers.Main) {
                if (trackId == activeVideoId) {
                    _haptics.update {
                        it.copy(
                            mode = HapticMode.ENCODE_FAILED,
                            lastError = "The track was captured, but the haptic profile couldn't be encoded.",
                        )
                    }
                }
            }
            return
        }
        if (analysis != null) {
            runCatching { analysis.saveTo(cache.analysisFile(trackId)) }
        } else {
            cache.analysisFile(trackId).delete()
        }
        if (transient != null) {
            runCatching { transient.saveTo(cache.transientAnalysisFile(trackId)) }
        } else {
            cache.transientAnalysisFile(trackId).delete()
        }
        withContext(Dispatchers.Main) {
            if (trackId == activeVideoId) {
                if (syncEngine.load(outputOgg)) {
                    val c = controller
                    syncEngine.syncTo(c?.currentPosition ?: 0L, c == null || !c.isPlaying)
                    loadTransientFor(trackId)
                    _haptics.update { it.copy(mode = HapticMode.SYNCED, lastError = null) }
                } else {
                    cache.delete(trackId)
                    _haptics.update {
                        it.copy(
                            mode = HapticMode.PLAYBACK_FAILED,
                            lastError = "The saved haptic profile couldn't be played back on this device.",
                        )
                    }
                }
            }
        }
    }

    private fun onPlaybackEnded() {
        finalizeCapture(activeVideoId, discard = false)
        syncEngine.stop()
        transientRenderer.stop()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
        resetPlaybackState()
    }

    /** Stops everything — used when the app switches to another section. */
    fun stopAll() {
        controller?.pause()
        val previous = activeVideoId
        finalizeCapture(previous, discard = true)
        syncEngine.stop()
        transientRenderer.stop()
        HapticPcmTap.cancel()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
        resetPlaybackState()
    }

    private fun stopControllerPlayback() {
        controller?.pause()
        controller?.clearMediaItems()
    }

    private fun stopHaptics() {
        HapticPcmTap.cancel()
        syncEngine.stop()
        transientRenderer.stop()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
    }

    fun recaptureCurrentTrack() {
        val trackId = activeVideoId ?: return
        finalizeCapture(trackId, discard = _haptics.value.mode == HapticMode.CAPTURING)
        cache.delete(trackId)
        syncEngine.stop()
        transientRenderer.stop()
        beginHapticsFor(trackId, controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
    }

    /** Stops the in-progress pass without caching a partial, out-of-sync track. */
    fun stopLearning() {
        val trackId = activeVideoId ?: return
        if (_haptics.value.mode != HapticMode.CAPTURING) return
        finalizeCapture(trackId, discard = true)
        syncEngine.stop()
        _haptics.update {
            it.copy(
                mode = HapticMode.CAPTURE_FAILED,
                lastError = "Learning stopped. Play this track again to build its full haptic profile.",
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAll()
        controller?.release()
        controller = null
    }

    companion object {
        private const val DURATION_EXTRA = "bitchord_duration_ms"
        private const val LIKED_COLLECTION_ID = "liked"
    }
}

private suspend fun <T> awaitListenerFuture(
    future: com.google.common.util.concurrent.ListenableFuture<T>,
): T = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    future.addListener({
        try {
            cont.resume(future.get())
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, { it.run() })
    cont.invokeOnCancellation { future.cancel(true) }
}

