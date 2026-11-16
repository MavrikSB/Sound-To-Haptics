package com.sound.to.haptics.bitchord

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sound.to.haptics.bitchord.auth.AuthStore
import com.sound.to.haptics.bitchord.auth.CapturedSession
import com.sound.to.haptics.bitchord.auth.GoogleAccountSession
import com.sound.to.haptics.bitchord.auth.YouTubeProfile
import com.sound.to.haptics.bitchord.auth.profileId
import com.sound.to.haptics.bitchord.auth.sessionId
import com.sound.to.haptics.bitchord.data.Http
import com.sound.to.haptics.bitchord.data.YtMusicRepository
import com.sound.to.haptics.bitchord.data.innertube.Innertube
import com.sound.to.haptics.bitchord.data.innertube.PlayerClient
import com.sound.to.haptics.bitchord.data.innertube.StreamResolver
import com.sound.to.haptics.bitchord.data.model.SearchFilter
import com.sound.to.haptics.bitchord.data.model.SearchResult
import com.sound.to.haptics.bitchord.data.model.Song
import com.sound.to.haptics.bitchord.data.model.UserPlaylist
import com.sound.to.haptics.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import com.sound.to.haptics.haptics.HapticAudioShaping
import com.sound.to.haptics.haptics.HapticOggValidator
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

private const val DOWNLOAD_CHUNK_BYTES = 1L * 1024 * 1024

private const val CONVERT_TIMEOUT_MS = 120_000L

enum class BrowseStatus { IDLE, LOADING }

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

data class HomeTileItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
)

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
    val isPlaying: Boolean = false,
    val currentItem: BitChordSongItem? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connection = MutableStateFlow(BitChordConnectionState())
    private val _haptics = MutableStateFlow(
        TrackHapticUiState(sensitivity = AppSettings.state.value.hapticStrength)
    )
    private val _browse = MutableStateFlow(BitChordBrowseState())

    private val transientRenderer by lazy { TransientHapticRenderer(appContext) }

    private var libraryJob: Job? = null
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var collectionJob: Job? = null
    private var progressJob: Job? = null
    private var convertJob: Job? = null

    private var convertAbortedByUser = false

    private val _convert = MutableStateFlow(ConvertState())
    val convertState: StateFlow<ConvertState> = _convert.asStateFlow()

    private var activeVideoId: String? = null
    private var activeIndex: Int = -1
    private var currentItem: BitChordSongItem? = null
    private var mediaPlayer: MediaPlayer? = null
    private var hapticOnlyPlayer: MediaPlayer? = null
    private var lastRequestedItem: BitChordSongItem? = null

    val uiState: StateFlow<BitChordScreenState> =
        combine(_connection, _haptics, _browse) { connection, haptics, browse ->
            BitChordScreenState(connection, haptics, browse)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BitChordScreenState())

    init {
        BitChordStreamHooks.initInnerTubeX(appContext)
        restoreSession()
        _connection.update { it.copy(isControllerConnected = true) }

        viewModelScope.launch {
            AppSettings.state.map { it.hapticStrength }.distinctUntilChanged().collect { strength ->
                _haptics.update { it.copy(sensitivity = strength) }
            }
        }
    }

    fun isSignedIn(): Boolean = authStore.isSignedIn

    fun beginLogin() {
        _connection.update { it.copy(signingIn = true, error = null) }
    }

    fun cancelLogin() {
        _connection.update { it.copy(signingIn = false) }
    }

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
        stopAll()
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

    fun retryLibrary() {
        if (!isSignedIn()) return
        loadLibrary()
    }

    private fun restoreSession() {

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
    }

    private fun handleExpiredSession() {
        Log.w(TAG, "session rejected by YouTube Music; clearing it")
        stopAll()
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

    fun playTrack(item: BitChordSongItem) {
        val queue = _browse.value.visibleTracks
        val startIndex = queue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        playQueue(queue.ifEmpty { listOf(item) }, startIndex)
    }

    fun playCollectionTrack(item: BitChordSongItem) {
        val queue = _browse.value.collectionTracks
        val startIndex = queue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        playQueue(queue, startIndex)
    }

    private fun playQueue(items: List<BitChordSongItem>, startIndex: Int) {
        val start = items.getOrNull(startIndex) ?: items.firstOrNull() ?: return
        val videoId = start.videoId

        if (activeVideoId != null && activeVideoId == videoId && mediaPlayer != null) {
            return
        }
        if (!videoId.isNullOrBlank() && !cache.exists(videoId)) {

            beginConvertThenPlay(start, items, startIndex)
            return
        }
        startPlayback(items, startIndex)
    }

    private fun startPlayback(items: List<BitChordSongItem>, startIndex: Int) {
        val start = items.getOrNull(startIndex) ?: return
        val ogg = cache.fileFor(start.videoId)
        if (!ogg.exists() || ogg.length() == 0L) {

            _connection.update { it.copy(error = "The cached haptic track is missing. Try again to rebuild it.") }
            return
        }
        _browse.update { it.copy(activeVideoId = start.videoId, queue = items, queueVisible = false) }
        activeVideoId = start.videoId
        activeIndex = startIndex
        currentItem = start
        lastRequestedItem = start
        releasePlayers()
        playOggFile(ogg)
    }

    private fun playOggFile(ogg: File) {
        val ctx = getApplication<Application>().applicationContext
        val btDevice = bluetoothDevice()
        val useBluetoothSplit = btDevice != null

        mediaPlayer = MediaPlayer().apply {
            if (useBluetoothSplit) {

                setAudioAttributes(buildNeutralAudioAttributes(bluetooth = true))
                setDataSource(ogg.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setPreferredDevice(btDevice)
                }
                setVolume(1.0f, 1.0f)
            } else {

                setAudioAttributes(buildHapticAudioAttributes())
                setDataSource(ogg.absolutePath)
                findBuiltInSpeaker()?.let { speaker ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setPreferredDevice(speaker)
                }
                setVolume(1.0f, 1.0f)
            }
            setOnErrorListener { _, what, extra ->
                _connection.update {
                    it.copy(
                        error = "The haptic track stopped playing (decoder error $what/$extra).",
                        isPlaying = false,
                    )
                }
                releasePlayers()
                true
            }
            setOnCompletionListener { onPlaybackCompleted() }
            prepare()
            start()
        }
        if (useBluetoothSplit) {

            hapticOnlyPlayer = MediaPlayer().apply {
                setAudioAttributes(buildHapticAudioAttributes())
                setDataSource(ogg.absolutePath)
                findBuiltInSpeaker()?.let { speaker ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setPreferredDevice(speaker)
                }
                val strength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
                setVolume(0.08f * strength, 0.08f * strength)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "BitChord haptic leg failed (decoder error $what/$extra)")
                    true
                }
                prepare()
                start()
            }
        } else {
            hapticOnlyPlayer = null
        }
        val item = currentItem
        val duration = mediaPlayer?.duration?.takeIf { it > 0 }?.toLong() ?: item?.durationMs ?: 0L
        _connection.update {
            it.copy(
                isPlaying = true,
                isBuffering = false,
                currentItem = item,
                positionMs = 0L,
                durationMs = duration,
            )
        }
        _haptics.update { it.copy(mode = HapticMode.SYNCED, lastError = null) }
        if (isTransientEngineActive() && activeVideoId != null) {
            loadTransientFor(activeVideoId!!)
        }
        startProgressTracker()
    }

    private fun buildNeutralAudioAttributes(bluetooth: Boolean = false) = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .apply {
            if (bluetooth) {
                setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setHapticChannelsMuted(true)
            }
        }
        .build()

    private fun bluetoothDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

    private fun buildHapticAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .apply {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setHapticChannelsMuted(false)
            }
        }
        .build()

    private fun findBuiltInSpeaker(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }

    private fun releasePlayers() {
        progressJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        transientRenderer.stop()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { hapticOnlyPlayer?.release() } catch (_: Exception) {}
        hapticOnlyPlayer = null
    }

    fun cancelConvert() {
        convertAbortedByUser = true
        convertJob?.cancel()
        convertJob = null
        _convert.value = ConvertState()
    }

    private fun beginConvertThenPlay(head: BitChordSongItem, items: List<BitChordSongItem>, startIndex: Int) {
        val videoId = head.videoId
        convertJob?.cancel()
        convertAbortedByUser = false
        convertJob = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                withTimeout(CONVERT_TIMEOUT_MS) {
                    convertThenPlay(head, items, startIndex, app, videoId)
                }

                _convert.value = ConvertState()
                if (!cache.exists(videoId)) {
                    reportUnplayable("This track couldn't be prepared for playback. Check your connection and tap retry.")
                    return@launch
                }
                startPlayback(items, startIndex)
            } catch (e: CancellationException) {
                if (convertAbortedByUser) {

                    convertAbortedByUser = false
                    return@launch
                }
                Log.w(TAG, "conversion failed for $videoId (${e.javaClass.simpleName})", e)
                _convert.value = ConvertState()
                reportUnplayable("This track couldn't be prepared — it timed out or the network dropped. Tap retry.")
            } catch (e: Exception) {
                Log.w(TAG, "conversion failed for $videoId", e)
                _convert.value = ConvertState()
                reportUnplayable("This track couldn't be prepared for playback. Tap retry.")
            }
        }
    }

    private fun reportUnplayable(message: String) {
        _connection.update { it.copy(error = message, isPlaying = false) }
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = message) }
        releasePlayers()
    }

    private suspend fun convertThenPlay(
        head: BitChordSongItem,
        items: List<BitChordSongItem>,
        startIndex: Int,
        app: Application,
        videoId: String,
    ) {
            _convert.update {
                ConvertState(
                    trackId = videoId,
                    title = head.title,
                    artist = head.artist,
                    stage = ConvertStage.DOWNLOADING,
                    progress = 0.05f,
                )
            }

            val dlAudio = File(app.cacheDir, "bitchord_dl_${videoId}_${System.nanoTime()}.m4a")
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
                Log.w(TAG, "download step failed for $videoId", e)
                cleanupQuietly(dlAudio, File(app.cacheDir, "${dlAudio.name}.tmp"))
                throw IllegalStateException("The track couldn't be downloaded: ${e.message ?: e.javaClass.simpleName}", e)
            }

            _convert.update { it.copy(stage = ConvertStage.ANALYZING, progress = 0.55f) }
            val bass = AppSettings.state.value.bassEmphasis
            val density = AppSettings.state.value.hapticDensity
            val analysisPcm = File(app.cacheDir, "bitchord_analyze_${System.nanoTime()}.pcm")
            val decodedOk = ffmpegDecodeToPcm(dlAudio, analysisPcm)
            val analysis = if (decodedOk) {
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

            val encoded = encodeHapticOgg(dlAudio, cache.fileFor(videoId))
            cleanupQuietly(analysisPcm, dlAudio, File(app.cacheDir, "${dlAudio.name}.tmp"))
            if (!encoded) {
                Log.w(TAG, "haptic OGG encode failed for $videoId")
                throw IllegalStateException("The downloaded track couldn't be converted to a haptic file.")
            }
            val validation = withContext(Dispatchers.IO) {
                runCatching { HapticOggValidator.validate(cache.fileFor(videoId)) }.getOrNull()
            }
            if (validation == null || !validation.valid) {
                cache.delete(videoId)
                throw IllegalStateException("Converted haptic OGG validation failed: ${validation?.reason ?: "unknown"}")
            }
    }

    private suspend fun encodeHapticOgg(source: File, output: File): Boolean = withContext(Dispatchers.Default) {
        val sensitivity = AppSettings.state.value.hapticStrength
        val bass = AppSettings.state.value.bassEmphasis
        val density = AppSettings.state.value.hapticDensity
        val graph =
            "[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,asplit=2[a1][a2];" +
                "[a1]aresample=44100:resampler=soxr:precision=28,asetpts=N/SR/TB," +
                "aformat=sample_fmts=fltp:channel_layouts=stereo[a_sync];" +
                HapticAudioShaping.filterChain(sensitivity, bass, density, "[a2]", "[h]") + ";" +
                "[a_sync][h]join=inputs=2:channel_layout=3.0:" +
                "map=0.0-FL|0.1-FR|1.0-FC[out]"
        val cmd = "-y -i \"${source.absolutePath}\" -vn -filter_complex \"$graph\" " +
            "-map \"[out]\" -map_metadata 0 -ar 44100 -c:a libvorbis -q:a 10 " +
            "-metadata ANDROID_HAPTIC=1 " +
            "-metadata title=\"Sound To Haptics\" -shortest \"${output.absolutePath}\""
        Log.d(TAG, "FFmpeg source-faithful stereo + bass haptic channel (bitchord)")
        suspendCancellableCoroutine<Boolean> { cont ->
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(cmd) { result ->
                if (cont.isActive) {
                    cont.resume(com.arthenica.ffmpegkit.ReturnCode.isSuccess(result.returnCode))
                }
            }
            cont.invokeOnCancellation { session.cancel() }
        }
    }

    private fun downloadWithHeaders(url: String, headers: Map<String, String>, spool: File, dest: File): Boolean = try {
        val parsed = url.toHttpUrlOrNull()
        val total = parsed?.queryParameter("clen")?.toLongOrNull()
        val rangeBytes = minOf(
            DOWNLOAD_CHUNK_BYTES,
            PlayerClient.rangeBytesFor(url),
        )
        val client = Http.client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        Log.w(
            TAG,
            "download GET host=${parsed?.host} client=${parsed?.queryParameter("c")} " +
                "cver=${parsed?.queryParameter("cver")} clen=$total rangeBytes=$rangeBytes",
        )
        var written = 0L
        var failed = false
        FileOutputStream(spool).use { outs ->
            do {
                val before = written
                val start = written
                val end = if (total != null) minOf(total, start + rangeBytes) else Long.MAX_VALUE
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    if (end != Long.MAX_VALUE) header("Range", "bytes=$start-${end - 1}")
                }.get().build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "download failed: HTTP ${response.code} ${response.message} url=${url.take(140)}")
                        failed = true
                        return@use
                    }
                    val type = response.header("Content-Type")

                    if (type != null && (type.startsWith("text/") || type.startsWith("application/json") || type.contains("html"))) {
                        Log.w(TAG, "download refused: content-type=$type url=${url.take(140)}")
                        failed = true
                        return@use
                    }
                    response.body.byteStream().use { ins ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                            written += n
                        }
                    }
                    Log.w(TAG, "download chunk ok: type=$type bytes=$written")
                }
                if (failed) break
                if (written == before) {

                    Log.w(TAG, "download stalled at $written bytes: no content-length progress")
                    failed = true
                }
            } while (total == null || written < total)
        }
        if (failed || written <= 0L) {
            spool.delete()
            false
        } else {
            spool.renameTo(dest)
            val ok = dest.exists() && dest.length() > 0
            Log.w(TAG, "download done: bytes=${dest.length()} ok=$ok")
            ok
        }
    } catch (e: Exception) {
        Log.w(TAG, "download threw: ${e.javaClass.simpleName}: ${e.message}")
        spool.delete()
        false
    }

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

    fun retryPlayback() {
        val item = lastRequestedItem ?: return
        playTrack(item)
    }

    fun togglePlayPause() {
        val p = mediaPlayer
        if (p == null) {

            val item = currentItem ?: return
            val queue = _browse.value.queue
            val index = queue.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
            startPlayback(queue, index)
            return
        }
        if (p.isPlaying) {
            p.pause()
            hapticOnlyPlayer?.pause()
            transientRenderer.stop()
            _connection.update { it.copy(isPlaying = false) }
        } else {
            p.start()
            hapticOnlyPlayer?.let { if (!it.isPlaying) runCatching { it.start() } }
            activeVideoId?.let { loadTransientFor(it) }
            startProgressTracker()
            _connection.update { it.copy(isPlaying = true) }
        }
    }

    fun skipNext() {
        skipTrack(direction = 1)
    }

    fun skipPrevious() {
        val position = mediaPlayer?.currentPosition?.toLong() ?: _connection.value.positionMs
        if (position > 3_000L) {
            seekTo(0)
            return
        }
        skipTrack(direction = -1)
    }

    private fun skipTrack(direction: Int): Boolean {
        val queue = _browse.value.queue
        val index = queue.indexOfFirst { it.videoId == activeVideoId }
        if (index < 0) return false
        val raw = if (direction > 0) index + 1 else index - 1
        if (raw !in queue.indices) return false
        return startOrConvert(queue, raw)
    }

    private fun startOrConvert(queue: List<BitChordSongItem>, index: Int): Boolean {
        val track = queue.getOrNull(index) ?: return false
        if (track.videoId.isBlank()) return false
        if (cache.exists(track.videoId)) {
            startPlayback(queue, index)
            return true
        }
        return convertForTransition(track)
    }

    fun seekTo(positionMs: Long) {
        val duration = mediaPlayer?.duration?.takeIf { it > 0 }?.toLong()
            ?: _connection.value.durationMs.takeIf { it > 0 }
        val clamped = if (duration != null) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        try { mediaPlayer?.seekTo(clamped.toInt()) } catch (_: Exception) {}
        try { hapticOnlyPlayer?.seekTo(clamped.toInt()) } catch (_: Exception) {}
        runCatching { transientRenderer.notifySeek(clamped.toInt()) }
        _connection.update { it.copy(positionMs = clamped) }
    }

    fun skipForward() = seekTo((_connection.value.positionMs + 10_000L).coerceAtMost(_connection.value.durationMs))

    fun skipBackward() = seekTo((_connection.value.positionMs - 10_000L).coerceAtLeast(0L))

    fun toggleQueue() {
        _browse.update { it.copy(queueVisible = !it.queueVisible) }
    }

    fun seekToQueueItem(index: Int) {
        val queue = _browse.value.queue
        if (index < 0 || index >= queue.size) return
        if (index == activeIndex) {

            seekTo(0)
            return
        }
        startOrConvert(queue, index)
    }

    private fun convertForTransition(track: BitChordSongItem): Boolean {
        val videoId = track.videoId
        if (videoId.isNullOrBlank() || cache.exists(videoId)) return false
        val queue = _browse.value.queue
        val index = queue.indexOfFirst { it.videoId == videoId }
        if (index < 0) return false
        releasePlayers()
        beginConvertThenPlay(track, queue, index)
        return true
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                try {
                    val p = mediaPlayer
                    if (p != null && p.isPlaying) {
                        _connection.update { current ->
                            current.copy(
                                positionMs = p.currentPosition.toLong(),
                                durationMs = p.duration.takeIf { it > 0 }?.toLong() ?: current.durationMs,
                            )
                        }
                    }
                } catch (_: Exception) {

                }
                delay(100)
            }
        }
    }

    private fun onPlaybackCompleted() {
        hapticOnlyPlayer?.let { try { it.pause() } catch (_: Exception) {} }
        transientRenderer.stop()
        val queue = _browse.value.queue
        val next = activeIndex + 1
        releasePlayers()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
        if (next in queue.indices) {
            startOrConvert(queue, next)
            return
        }
        activeVideoId = null
        activeIndex = -1
        currentItem = null
        _browse.update { it.copy(activeVideoId = null) }
        _connection.update { it.copy(isPlaying = false, positionMs = 0L) }
    }

    val likedOverrides: StateFlow<Map<String, com.sound.to.haptics.bitchord.data.model.LikeStatus>> =
        com.sound.to.haptics.bitchord.data.LikeState.overrides

    fun toggleLike(videoId: String) {
        if (videoId.isBlank()) return
        val liked = likedOverrides.value[videoId] == com.sound.to.haptics.bitchord.data.model.LikeStatus.LIKE
        val next = if (liked) {
            com.sound.to.haptics.bitchord.data.model.LikeStatus.INDIFFERENT
        } else {
            com.sound.to.haptics.bitchord.data.model.LikeStatus.LIKE
        }
        com.sound.to.haptics.bitchord.data.LikeState.set(videoId, next)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.sound.to.haptics.bitchord.data.innertube.Innertube.rate(videoId, next)
                }
            } catch (e: Exception) {
                Log.e(TAG, "like toggle rejected for $videoId", e)
                com.sound.to.haptics.bitchord.data.LikeState.set(
                    videoId,
                    if (liked) com.sound.to.haptics.bitchord.data.model.LikeStatus.LIKE
                    else com.sound.to.haptics.bitchord.data.model.LikeStatus.INDIFFERENT,
                )
            }
        }
    }

    fun updateSensitivity(v: Float) {
        AppSettings.setHapticStrength(v)
        _haptics.update { it.copy(sensitivity = v.coerceIn(0f, 1f)) }
    }

    fun onScreenVisible() {

    }

    private fun resetPlaybackState() {
        activeVideoId = null
        activeIndex = -1
        currentItem = null
        _connection.update {
            it.copy(isPlaying = false, currentItem = null, positionMs = 0L, durationMs = 0L)
        }
        _browse.update { it.copy(activeVideoId = null) }
    }

    private fun loadTransientFor(trackId: String) {
        if (!isTransientEngineActive()) return
        val analysis = loadTransientAnalysis(cache.transientAnalysisFile(trackId)) ?: return
        transientRenderer.load(analysis)
        if (mediaPlayer?.isPlaying == true) {
            transientRenderer.start { mediaPlayer?.currentPosition?.coerceAtLeast(0) ?: 0 }
        }
    }

    private fun isTransientEngineActive(): Boolean {
        val ctx = getApplication<Application>()
        if (AppSettings.state.value.hapticEngine != HapticEngine.TRANSIENT) return false
        if (!TransientHapticRenderer.isSupported(ctx)) return false

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

    fun stopAll() {
        releasePlayers()
        _haptics.update { it.copy(mode = HapticMode.IDLE, lastError = null) }
        resetPlaybackState()
    }

    fun recaptureCurrentTrack() {
        val trackId = activeVideoId ?: return
        val queue = _browse.value.queue
        val index = queue.indexOfFirst { it.videoId == trackId }.let { if (it >= 0) it else activeIndex }
        val item = queue.getOrNull(index) ?: return
        cache.delete(trackId)
        releasePlayers()
        beginConvertThenPlay(item, queue, index.coerceAtLeast(0))
    }

    fun stopLearning() {

    }

    override fun onCleared() {
        super.onCleared()
        stopAll()
        transientRenderer.release()
    }

    companion object {
        private const val LIKED_COLLECTION_ID = "liked"
    }
}
