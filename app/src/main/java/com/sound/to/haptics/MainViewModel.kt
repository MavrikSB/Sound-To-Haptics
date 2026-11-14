package com.sound.to.haptics

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.OpenableColumns
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.sound.to.haptics.haptics.HapticAudioShaping
import com.sound.to.haptics.haptics.HapticOggValidator
import com.sound.to.haptics.haptics.HapticProcessing
import com.sound.to.haptics.haptics.LayeredHapticAnalysis
import com.sound.to.haptics.haptics.MultiLayerAudioAnalyzer
import com.sound.to.haptics.haptics.OnsetEvent
import com.sound.to.haptics.haptics.TransientAnalysis
import com.sound.to.haptics.haptics.TransientAudioAnalyzer
import com.sound.to.haptics.haptics.TransientHapticRenderer
import com.sound.to.haptics.haptics.loadLayeredHapticAnalysis
import com.sound.to.haptics.haptics.loadOnsetEvents
import com.sound.to.haptics.haptics.loadTransientAnalysis
import com.sound.to.haptics.haptics.saveTo
import com.sound.to.haptics.library.HapticLibrary
import com.sound.to.haptics.library.HapticSong
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.settings.HapticEngine
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ConversionStage { IDLE, COPYING, ANALYZING, EXTRACTING, BUILDING, OPTIMIZING, SUCCESS, ERROR }

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Int = 0,
    val duration: Int = 0,
    val fileName: String? = null,
    val artist: String = "Sound To Haptics"
)

data class UiState(
    val conversionStage: ConversionStage = ConversionStage.IDLE,
    val progress: Float = 0f,
    val selectedSourceName: String? = null,
    val errorMessage: String? = null,
    val playerState: PlayerState = PlayerState(),
    val sensitivity: Float = 1f,
    val bassBoost: Float = 0.7f,
    val density: Float = 0.6f,
    val hapticPreviewEnabled: Boolean = true,
    val bluetoothDeviceConnected: Boolean = false,
)

private enum class PlaybackSource { NONE, FILE, DEMO }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SoundToHaptics"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val library = HapticLibrary(application.applicationContext)
    private val _librarySongs = MutableStateFlow<List<HapticSong>>(emptyList())
    /** Persistent converted songs, loaded from filesDir rather than cacheDir. */
    val librarySongs: StateFlow<List<HapticSong>> = _librarySongs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var hapticOnlyPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    // Musical-haptic Transient engine — see AppSettings.HapticEngine and
    // TransientHapticRenderer's kdoc. Lives alongside the continuous-OGG
    // players above rather than replacing them: whichever engine is
    // selected, this app still needs the OGG's stereo branch as the actual
    // audible track, so playback always goes through mediaPlayer either
    // way — the continuous OGG haptic channel remains the source of truth;
    // only the preview mute and optional transient accent layer vary with
    // the user's settings.
    private val transientRenderer by lazy { TransientHapticRenderer(getApplication<Application>()) }
    private var currentOnsetEvents: List<OnsetEvent> = emptyList()
    private var currentLayerAnalysis: LayeredHapticAnalysis? = null
    private var currentTransientAnalysis: TransientAnalysis? = null

    private var currentHapticFile: String? = null
    private var currentPlaybackSource = PlaybackSource.NONE
    private var currentPlaybackPath: String? = null
    private var activeLibrarySong: HapticSong? = null
    private var pendingSourceName: String = "Haptic track"
    private var conversionJob: Job? = null
    private var conversionPostProcessJob: Job? = null
    private var conversionGeneration = 0L
    private var activeConversionCanceller: (() -> Unit)? = null
    private var activeInputFile: File? = null
    private var activeOutputFile: File? = null
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Bluetooth device monitoring ────────────────────────────────────────

    private var cachedBluetoothDevice: AudioDeviceInfo? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // This fires for ANY output route disappearing — wired
                // headphones unplugged as much as Bluetooth disconnecting —
                // and is Android's documented signal that playback is about
                // to jump to the (loud) built-in speaker. The previous
                // version only reacted to this when a Bluetooth device was
                // known to have just dropped, so unplugging wired
                // headphones left both MediaPlayer instances still trying
                // to play into a route that had just vanished — on some
                // devices that wedges the audio HAL badly enough that nothing
                // plays again until the phone is rebooted. Pausing
                // immediately here, for every route change, is the fix.
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    pausePlaybackForRouteLoss()
                    refreshBluetoothState()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    refreshBluetoothState()
                }
            }
        }
    }

    /**
     * Pauses both playback paths the instant the current output route is
     * about to disappear, before Android reroutes audio anywhere else.
     * Doesn't release/recreate the players (that already happens correctly
     * in [releasePlayersAndRestart] for the Bluetooth-specific case above);
     * this just stops them from continuing to push audio into a route
     * that's gone, which is what was leaving the audio HAL in a bad state
     * on wired-headphone disconnect.
     */
    private fun pausePlaybackForRouteLoss() {
        if (mediaPlayer?.isPlaying != true && hapticOnlyPlayer?.isPlaying != true) return
        try { mediaPlayer?.pause() } catch (_: Exception) {}
        try { hapticOnlyPlayer?.pause() } catch (_: Exception) {}
        transientRenderer.stop()
        _uiState.update { it.copy(playerState = it.playerState.copy(isPlaying = false)) }
        progressJob?.cancel()
    }

    init {
        // Library discovery is intentionally off the main thread. It also
        // means songs created by an earlier process are visible immediately
        // after a cold start, even if the UI was previously on another tab.
        viewModelScope.launch(Dispatchers.IO) {
            _librarySongs.value = library.scan()
        }
        viewModelScope.launch {
            AppSettings.state
                .map { it.hapticStrength }
                .distinctUntilChanged()
                .collect { strength ->
                    _uiState.update { it.copy(sensitivity = strength) }
                }
        }
        viewModelScope.launch {
            AppSettings.state
                .map { it.bassEmphasis }
                .distinctUntilChanged()
                .collect { value -> _uiState.update { it.copy(bassBoost = value) } }
        }
        viewModelScope.launch {
            AppSettings.state
                .map { it.hapticDensity }
                .distinctUntilChanged()
                .collect { value -> _uiState.update { it.copy(density = value) } }
        }
        viewModelScope.launch {
            AppSettings.state
                .map { it.hapticEngine }
                .distinctUntilChanged()
                .collect {
                    // Engine changes are a deliberate user action. Re-arm
                    // the current song at its exact position so continuous
                    // texture and transient thud/click mode never fight over
                    // the motor at the same time.
                    val player = mediaPlayer ?: return@collect
                    if (currentPlaybackSource != PlaybackSource.FILE) return@collect
                    val path = currentPlaybackPath ?: return@collect
                    val position = player.currentPosition
                    val wasPlaying = player.isPlaying
                    playFromFile(path, activeLibrarySong)
                    seekTo(position)
                    if (!wasPlaying) stopPlayback()
                }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
        }
        application.registerReceiver(bluetoothReceiver, filter)
        refreshBluetoothState()
    }

    private fun refreshBluetoothState() {
        val wasBt = _uiState.value.bluetoothDeviceConnected
        val hasBt: Boolean
        val btDevice: AudioDeviceInfo?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            btDevice = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            hasBt = btDevice != null
        } else {
            @Suppress("DEPRECATION")
            hasBt = BluetoothAdapter.getDefaultAdapter()
                ?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            btDevice = null
        }

        cachedBluetoothDevice = btDevice
        _uiState.update { it.copy(bluetoothDeviceConnected = hasBt) }

        // If BT disconnected mid-playback, restart in standard mode
        if (wasBt && !hasBt && mediaPlayer?.isPlaying == true) {
            val file = currentHapticFile ?: return
            val pos = mediaPlayer?.currentPosition ?: 0
            viewModelScope.launch {
                releasePlayersAndRestart(file, pos)
            }
        }
    }

    private fun findBuiltInSpeaker(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
    }

    // ─── Audio file selection ───────────────────────────────────────────────

    fun onFileSelected(uri: Uri) {
        // FFmpegKit runs independently of the coroutine that launched it, so
        // cancelling only the coroutine is not enough. Invalidate the request,
        // cancel the native session, and remove its scratch files before a new
        // conversion starts; an old callback can never promote stale output.
        cancelActiveConversion()
        startConversion(uri, conversionGeneration)
    }

    fun cancelConversion() {
        cancelActiveConversion()
        _uiState.update {
            it.copy(
                conversionStage = ConversionStage.IDLE,
                progress = 0f,
                selectedSourceName = null,
                errorMessage = null,
            )
        }
    }

    private fun cancelActiveConversion() {
        conversionGeneration++
        conversionJob?.cancel()
        conversionJob = null
        conversionPostProcessJob?.cancel()
        conversionPostProcessJob = null
        activeConversionCanceller?.invoke()
        activeConversionCanceller = null
        activeInputFile?.delete()
        activeOutputFile?.delete()
        activeInputFile = null
        activeOutputFile = null
    }

    private fun startConversion(sourceUri: Uri, generation: Long) {
        conversionJob = viewModelScope.launch {
            var inputFile: File? = null
            var outputFile: File? = null
            try {
                val context = getApplication<Application>().applicationContext
                _uiState.update {
                    it.copy(
                        conversionStage = ConversionStage.COPYING,
                        progress = 0.06f,
                        selectedSourceName = null,
                        errorMessage = null,
                    )
                }

                // Content-provider queries and the source copy are disk work;
                // keep them off the main thread so the progress screen remains
                // responsive even when the picker returns cloud-backed media.
                val sourceName = withContext(Dispatchers.IO) { getDisplayName(sourceUri, context) }
                val ext = withContext(Dispatchers.IO) { getFileExtension(sourceUri, context) ?: "audio" }
                if (generation != conversionGeneration) return@launch
                pendingSourceName = sourceName
                _uiState.update { it.copy(selectedSourceName = sourceName, progress = 0.08f) }

                // Source and intermediate files are temporary by design. Only
                // HapticLibrary.addConverted() promotes a successful result
                // into filesDir/haptic_library where it survives restarts.
                inputFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.$ext")
                activeInputFile = inputFile
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { src ->
                        FileOutputStream(inputFile!!).use { dst -> src.copyTo(dst) }
                    } ?: throw IllegalStateException("Could not read source file")
                }
                if (generation != conversionGeneration) return@launch

                _uiState.update { it.copy(conversionStage = ConversionStage.ANALYZING, progress = 0.25f) }
                delay(120)
                _uiState.update { it.copy(conversionStage = ConversionStage.EXTRACTING, progress = 0.48f) }
                delay(120)
                _uiState.update { it.copy(conversionStage = ConversionStage.BUILDING, progress = 0.68f) }

                outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.ogg")
                activeOutputFile = outputFile
                performFFmpegConversion(inputFile!!, outputFile!!, _uiState.value, sourceName, generation)
            } catch (e: CancellationException) {
                inputFile?.delete()
                outputFile?.delete()
                throw e
            } catch (e: Exception) {
                inputFile?.delete()
                outputFile?.delete()
                Log.e(TAG, "Conversion failed", e)
                if (generation == conversionGeneration) {
                    activeInputFile = null
                    activeOutputFile = null
                    _uiState.update {
                        it.copy(
                            conversionStage = ConversionStage.ERROR,
                            errorMessage = e.message?.takeIf { message -> message.isNotBlank() }
                                ?: "The audio file could not be processed.",
                        )
                    }
                }
            }
        }
    }

    private fun getDisplayName(uri: Uri, ctx: Context): String {
        var displayName: String? = null
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        return displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Haptic track.audio"
    }

    private fun getFileExtension(uri: Uri, ctx: Context): String? =
        if (uri.scheme == "content") MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(ctx.contentResolver.getType(uri))
        else MimeTypeMap.getFileExtensionFromUrl(uri.toString())

    // ─── FFmpeg conversion with source-faithful output ──────────────────────

    private fun performFFmpegConversion(
        input: File,
        output: File,
        s: UiState,
        sourceName: String = pendingSourceName,
        generation: Long,
    ) {
        // The audible branch must stay neutral. loudnorm changes stereo
        // dynamics and can create the distant/phasey "space" sound that was
        // reported after conversion. Only the extra third channel is shaped.
        // The output is explicitly kept as stereo audio + one mono haptic
        // channel, which is the Android audio-coupled haptics layout.
        val graph =
            "[0:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,asplit=2[a1][a2];" +
                    "[a1]aresample=44100:resampler=soxr:precision=28,asetpts=N/SR/TB," +
                    "aformat=sample_fmts=fltp:channel_layouts=stereo[a_sync];" +
                    HapticAudioShaping.filterChain(s.sensitivity, s.bassBoost, s.density, "[a2]", "[h]") + ";" +
                    // join is explicit about the Android order: left, right,
                    // then the single haptic channel. amerge can infer 2.1
                    // for a stereo+mono pair, which makes Vorbis write the
                    // third sample as an LFE channel instead.
                    "[a_sync][h]join=inputs=2:channel_layout=3.0:" +
                    "map=0.0-FL|0.1-FR|1.0-FC[out]"

        val cmd = "-y -i \"${input.absolutePath}\" -vn -filter_complex \"$graph\" " +
                "-map \"[out]\" -map_metadata 0 -ar 44100 -c:a libvorbis -q:a 10 " +
                "-metadata ANDROID_HAPTIC=1 " +
                "-metadata title=\"Sound To Haptics\" -shortest \"${output.absolutePath}\""

        Log.d("SoundToHaptics", "FFmpeg source-faithful stereo + bass haptic channel")

        val session = FFmpegKit.executeAsync(cmd) { result ->
            if (generation != conversionGeneration) {
                input.delete()
                output.delete()
            } else {
                activeConversionCanceller = null
                if (ReturnCode.isSuccess(result.returnCode)) {
                    conversionPostProcessJob = viewModelScope.launch {
                        try {
                            if (generation != conversionGeneration) {
                                input.delete()
                                output.delete()
                                return@launch
                            }
                            _uiState.update { it.copy(conversionStage = ConversionStage.OPTIMIZING, progress = 0.88f) }

                            // FFmpeg success is not sufficient: the file must
                            // still be a real, seekable haptic OGG before it is
                            // promoted into persistent library storage. In
                            // particular, an ordinary Vorbis OGG without the
                            // ANDROID_HAPTIC comment would be a silent haptic
                            // regression even though its audio plays.
                            val validation = withContext(Dispatchers.IO) {
                                HapticOggValidator.validate(output)
                            }
                            if (!validation.valid) {
                                throw IllegalStateException("Converted haptic OGG validation failed: ${validation.reason}")
                            }

                            // Analyze the same decoded source that feeds the
                            // converted OGG. The result is a versioned three-layer
                            // timeline: bass weight, sustained vocal/melodic flow,
                            // and percussive impacts. Both offline and Spotify
                            // use this exact representation after their input
                            // mechanisms converge on PCM.
                            currentLayerAnalysis = analyzeLayersOrEmpty(input, s.bassBoost, s.density)
                            currentOnsetEvents = currentLayerAnalysis?.events.orEmpty()
                            // Independent musical-haptic analysis for
                            // Transient Mode — real spectral features,
                            // classified events, multi-layer composition.
                            // Uses its own decode call (same source file,
                            // same FFmpeg decode parameters) rather than
                            // sharing MultiLayerAudioAnalyzer's PCM buffer,
                            // since the two analyzers are intentionally
                            // fully independent pipelines (see
                            // TransientAudioAnalyzer's kdoc) and this keeps
                            // that separation clean; the decode itself is
                            // fast relative to the FFT-based analysis pass.
                            currentTransientAnalysis = analyzeTransientOrEmpty(input, s.bassBoost, s.density)
                            if (generation != conversionGeneration) {
                                input.delete()
                                output.delete()
                                return@launch
                            }
                            val durationMs = validation.durationMs
                            val artist = withContext(Dispatchers.IO) { readArtist(input) }
                            val song = withContext(Dispatchers.IO) {
                                val saved = library.addConverted(
                                    convertedFile = output,
                                    originalName = sourceName,
                                    durationMs = durationMs,
                                    artist = artist,
                                )
                                // A successful conversion starts playing
                                // immediately, so it should also become the
                                // most-recently-felt item in the library.
                                library.markPlayed(saved) ?: saved
                            }
                            if (generation != conversionGeneration) {
                                withContext(Dispatchers.IO) { library.delete(song) }
                                return@launch
                            }
                            currentLayerAnalysis?.let { analysis ->
                                withContext(Dispatchers.IO) { analysis.saveTo(song.layerFile) }
                            }
                            currentTransientAnalysis?.let { analysis ->
                                withContext(Dispatchers.IO) { analysis.saveTo(song.transientFile) }
                            }
                            if (currentOnsetEvents.isNotEmpty()) {
                                // Keep the compact legacy sidecar too so older
                                // builds can still render the beat layer after
                                // an in-place app update.
                                withContext(Dispatchers.IO) { currentOnsetEvents.saveTo(song.onsetFile) }
                            }
                            input.delete()
                            output.delete()
                            activeInputFile = null
                            activeOutputFile = null
                            refreshLibrary()

                            delay(150)
                            if (generation != conversionGeneration) return@launch
                            activeLibrarySong = song
                            currentHapticFile = song.filePath
                            _uiState.update {
                                it.copy(
                                    conversionStage = ConversionStage.SUCCESS,
                                    progress = 1f,
                                    selectedSourceName = song.title,
                                    errorMessage = null,
                                )
                            }
                            playFromFile(song.filePath, song)
                        } catch (e: CancellationException) {
                            input.delete()
                            output.delete()
                            throw e
                        } catch (e: Exception) {
                            input.delete()
                            output.delete()
                            Log.e(TAG, "Conversion finalization failed", e)
                            if (generation == conversionGeneration) {
                                activeInputFile = null
                                activeOutputFile = null
                                _uiState.update {
                                    it.copy(
                                        conversionStage = ConversionStage.ERROR,
                                        errorMessage = e.message?.takeIf { message -> message.isNotBlank() }
                                            ?: "The converted track could not be saved.",
                                    )
                                }
                            }
                        } finally {
                            if (generation == conversionGeneration) conversionPostProcessJob = null
                        }
                    }
                } else {
                    Log.e(TAG, "FFmpeg failed: ${result.allLogsAsString}")
                    input.delete()
                    output.delete()
                    activeInputFile = null
                    activeOutputFile = null
                    _uiState.update {
                        it.copy(
                            conversionStage = ConversionStage.ERROR,
                            errorMessage = "The audio encoder could not finish this file. Try another audio format.",
                        )
                    }
                }
            }
        }
        if (generation == conversionGeneration) {
            activeConversionCanceller = { session.cancel() }
        } else {
            session.cancel()
        }
    }

    private fun readArtist(file: File): String = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Imported audio"
        } finally {
            retriever.release()
        }
    }.getOrDefault("Imported audio")

    private fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            _librarySongs.value = library.scan()
        }
    }

    fun playLibrarySong(song: HapticSong) {
        if (!song.file.exists()) {
            _uiState.update {
                it.copy(
                    conversionStage = ConversionStage.ERROR,
                    errorMessage = "This saved haptic file is missing. Re-import the original audio to play it again.",
                )
            }
            refreshLibrary()
            return
        }
        if (song.processingVersion != HapticProcessing.VERSION) {
            _uiState.update {
                it.copy(
                    conversionStage = ConversionStage.ERROR,
                    errorMessage = "This saved track was made by an older haptic engine. Re-import the original audio to build the new bass, flow, and impact layers.",
                )
            }
            return
        }

        // Validate the exact persistent artifact before opening it. A missing
        // or damaged OGG is an error, not permission to regenerate from an
        // unavailable source or silently substitute primitive clicks.
        viewModelScope.launch {
            val validation = withContext(Dispatchers.IO) {
                HapticOggValidator.validate(song.file)
            }
            if (!validation.valid) {
                Log.e(TAG, "Saved haptic OGG rejected: ${validation.reason} path=${song.filePath}")
                _uiState.update {
                    it.copy(
                        conversionStage = ConversionStage.ERROR,
                        errorMessage = "This saved haptic track is invalid and could not be played: ${validation.reason}",
                    )
                }
                return@launch
            }

            activeLibrarySong = song
            currentHapticFile = song.filePath
            currentOnsetEvents = loadOnsetEvents(song.onsetFile).orEmpty()
            _uiState.update {
                it.copy(
                    conversionStage = ConversionStage.SUCCESS,
                    progress = 1f,
                    selectedSourceName = song.title,
                    errorMessage = null,
                    playerState = it.playerState.copy(fileName = song.title, artist = song.artist),
                )
            }
            withContext(Dispatchers.IO) {
                library.markPlayed(song)
                _librarySongs.value = library.scan()
            }
            playFromFile(song.filePath, song)
        }
    }

    fun deleteLibrarySong(song: HapticSong) {
        if (activeLibrarySong?.id == song.id) {
            releaseAllPlayers()
            activeLibrarySong = null
            currentHapticFile = null
            currentPlaybackPath = null
            currentPlaybackSource = PlaybackSource.NONE
            currentLayerAnalysis = null
            currentOnsetEvents = emptyList()
            currentTransientAnalysis = null
            _uiState.update { it.copy(conversionStage = ConversionStage.IDLE, errorMessage = null, playerState = PlayerState()) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            library.delete(song)
            _librarySongs.value = library.scan()
        }
    }

    fun clearLibrary() {
        cancelActiveConversion()
        releaseAllPlayers()
        activeLibrarySong = null
        currentHapticFile = null
        currentPlaybackPath = null
        currentPlaybackSource = PlaybackSource.NONE
        currentLayerAnalysis = null
        currentOnsetEvents = emptyList()
        currentTransientAnalysis = null
        _uiState.update { it.copy(conversionStage = ConversionStage.IDLE, errorMessage = null, playerState = PlayerState()) }
        viewModelScope.launch(Dispatchers.IO) {
            library.clear()
            _librarySongs.value = library.scan()
        }
    }

    /**
     * Decodes a source to bounded, interleaved PCM and runs the shared
     * three-layer analyser off the UI thread. Analysis failure never destroys
     * an otherwise valid audible/haptic OGG conversion; it only disables the
     * optional transient layer for that item.
     */
    private suspend fun analyzeLayersOrEmpty(sourceFile: File, bassEmphasis: Float, density: Float): LayeredHapticAnalysis? {
        val context = getApplication<Application>().applicationContext
        val analysisPcm = File(context.cacheDir, "analysis_${System.currentTimeMillis()}.pcm")
        return try {
            val cmd = "-y -i \"${sourceFile.absolutePath}\" -vn -ac 2 -ar 44100 -f s16le \"${analysisPcm.absolutePath}\""
            val success = withContext(Dispatchers.Default) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val session = FFmpegKit.executeAsync(cmd) { result ->
                        if (cont.isActive) cont.resume(ReturnCode.isSuccess(result.returnCode))
                    }
                    cont.invokeOnCancellation { session.cancel() }
                }
            }
            if (!success) {
                Log.e(TAG, "Layer analysis decode failed")
                null
            } else {
                withContext(Dispatchers.Default) {
                    MultiLayerAudioAnalyzer.analyze(
                        analysisPcm,
                        sampleRate = 44100,
                        channelCount = 2,
                        bassEmphasis = bassEmphasis,
                        density = density,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Layer analysis failed", e)
            null
        } finally {
            analysisPcm.delete()
        }
    }

    /**
     * Runs the independent Transient Mode musical-haptic analysis pipeline
     * ([TransientAudioAnalyzer]) against the same source file, using its
     * own short-lived decode to raw stereo PCM. Kept entirely separate from
     * [analyzeLayersOrEmpty]/[MultiLayerAudioAnalyzer] (continuous mode's
     * pipeline) — a failure here never affects the continuous OGG
     * conversion or its own layer analysis; it only leaves Transient Mode
     * without a musical event timeline for this track (falls back to no
     * transient accents, same as Continuous-only tracks converted before
     * this feature existed).
     */
    private suspend fun analyzeTransientOrEmpty(sourceFile: File, bassEmphasis: Float, density: Float): TransientAnalysis? {
        val context = getApplication<Application>().applicationContext
        val analysisPcm = File(context.cacheDir, "transient_analysis_${System.currentTimeMillis()}.pcm")
        return try {
            val cmd = "-y -i \"${sourceFile.absolutePath}\" -vn -ac 2 -ar 44100 -f s16le \"${analysisPcm.absolutePath}\""
            val success = withContext(Dispatchers.Default) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val session = FFmpegKit.executeAsync(cmd) { result ->
                        if (cont.isActive) cont.resume(ReturnCode.isSuccess(result.returnCode))
                    }
                    cont.invokeOnCancellation { session.cancel() }
                }
            }
            if (!success) {
                Log.e(TAG, "Transient analysis decode failed")
                null
            } else {
                withContext(Dispatchers.Default) {
                    TransientAudioAnalyzer.analyze(
                        analysisPcm,
                        sampleRate = 44100,
                        channelCount = 2,
                        bassEmphasis = bassEmphasis,
                        hapticDensity = density,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transient analysis failed", e)
            null
        } finally {
            analysisPcm.delete()
        }
    }

    // ─── Playback ───────────────────────────────────────────────────────────

    fun playFromFile(path: String, song: HapticSong? = activeLibrarySong) {
        currentPlaybackSource = PlaybackSource.FILE
        currentPlaybackPath = path
        activeLibrarySong = song ?: library.findByPath(path)
        currentHapticFile = path
        currentLayerAnalysis = activeLibrarySong
            ?.takeIf { it.processingVersion == HapticProcessing.VERSION }
            ?.let { loadLayeredHapticAnalysis(it.layerFile) }
        currentOnsetEvents = currentLayerAnalysis?.events
            ?: activeLibrarySong?.let { loadOnsetEvents(it.onsetFile).orEmpty() }
            ?: emptyList()
        // TransientAnalysis is versioned independently (TransientConfig.
        // PROCESSING_VERSION, checked inside loadTransientAnalysis) — a
        // library entry converted before this feature existed, or by an
        // older Transient algorithm, simply yields null here rather than
        // being gated by the continuous pipeline's HapticProcessing.VERSION.
        currentTransientAnalysis = activeLibrarySong?.let { loadTransientAnalysis(it.transientFile) }
        refreshBluetoothState()
        releaseAllPlayers()
        try {
            if (_uiState.value.bluetoothDeviceConnected) startBluetoothSplitPlayback(path)
            else startStandardPlayback(path)
        } catch (e: Exception) {
            reportPlaybackError("This haptic track could not be played on the current audio route.", e)
        }
    }

    private fun startStandardPlayback(path: String) {
        // The converted OGG is the Offline haptic player. Its third channel
        // carries the continuous signal produced by FFmpeg and is routed by
        // Android's haptic-aware MediaPlayer path. Layer analysis remains a
        // sidecar for diagnostics and optional sparse transient accents; it
        // never replaces this OGG playback.
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(buildHapticAudioAttributes())
            setDataSource(path)
            prepare()
            setOnErrorListener { _, what, extra ->
                reportPlaybackError("The saved haptic OGG stopped playing (decoder error $what/$extra).")
                true
            }
            setOnCompletionListener {
                transientRenderer.stop()
                onPlaybackFinished()
            }
            start()
        }
        onPlaybackStarted()
        if (isTransientEngineActive() && _uiState.value.hapticPreviewEnabled) {
            transientRenderer.load(currentTransientAnalysis)
            transientRenderer.start { mediaPlayer?.currentPosition ?: 0 }
        }
    }

    private fun startBluetoothSplitPlayback(path: String) {
        val btDevice = cachedBluetoothDevice
        val speaker = findBuiltInSpeaker()

        Log.d(
            TAG,
            "BT split playback: btDevice=${btDevice?.type} speaker=${speaker?.type} " +
                "(null here means routing will fall back to default and haptics likely won't be heard)"
        )

        // Player 1: full-quality stereo to Bluetooth headphones
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setHapticChannelsMuted(true)
                        }
                    }
                    .build()
            )
            setDataSource(path)
            // setPreferredDevice before prepare(): several OEM audio HALs
            // treat the preferred-device hint as informational rather than
            // binding if it arrives after the track has already been
            // prepared/routed by default policy, which on this project's
            // testing device meant this call was silently a no-op — the
            // previous version called it after prepare() and both players
            // ended up on whichever device Android picked by default (in
            // practice, always the Bluetooth device, since that's the
            // active route), so no haptics were ever actually reaching the
            // phone's vibrator-adjacent speaker path.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                val routed = setPreferredDevice(btDevice)
                Log.d(TAG, "Stereo player setPreferredDevice(bt) succeeded=$routed")
            }
            prepare()

            setOnErrorListener { _, what, extra ->
                reportPlaybackError("The saved haptic OGG could not play on the Bluetooth route (decoder error $what/$extra).")
                true
            }
            setOnCompletionListener {
                hapticOnlyPlayer?.let { try { it.pause() } catch (_: Exception) {} }
                onPlaybackFinished()
            }
        }

        // Player 2: haptic-carrying playback, explicitly forced onto the
        // phone's own output (never the Bluetooth device) so its haptic
        // channel has a chance of reaching the vibrator.
        hapticOnlyPlayer = MediaPlayer().apply {
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
            setDataSource(path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speaker != null) {
                val routed = setPreferredDevice(speaker)
                Log.d(TAG, "Haptic player setPreferredDevice(speaker) succeeded=$routed")
            } else {
                Log.w(TAG, "No built-in speaker device found — haptic player will use default routing, which may follow the Bluetooth device instead")
            }
            prepare()
            setOnErrorListener { _, what, extra ->
                reportPlaybackError("The haptic OGG channel could not play on the phone route (decoder error $what/$extra).")
                true
            }
            // A clearly-nonzero, if still quiet, volume — not the earlier
            // 0.02f. The actual bug here was the missing/late device
            // routing above; this stays modest so it doesn't add an
            // audible second copy of the song once routing is fixed, but
            // isn't so close to zero it risks being treated as "not really
            // playing" on stricter audio HALs.
            val strength = AppSettings.state.value.hapticStrength.coerceIn(0f, 1f)
            setVolume(0.08f * strength, 0.08f * strength)
        }

        // Start both simultaneously with a tiny stagger to help the audio HAL
        mediaPlayer?.start()
        mainHandler.postDelayed({ hapticOnlyPlayer?.start() }, 16)
        onPlaybackStarted()
    }

    private fun releasePlayersAndRestart(path: String, position: Int) {
        releaseAllPlayers()
        try {
            startStandardPlayback(path)
            mediaPlayer?.seekTo(position)
        } catch (e: Exception) {
            reportPlaybackError("This haptic track could not be restarted after the audio route changed.", e)
        }
    }

    fun playDemo() {
        releaseAllPlayers()
        try {
            currentPlaybackSource = PlaybackSource.DEMO
            currentPlaybackPath = null
            activeLibrarySong = null
            currentOnsetEvents = emptyList()
            currentLayerAnalysis = null
            currentTransientAnalysis = null
            startDemoPlayback()
            _uiState.update { it.copy(conversionStage = ConversionStage.SUCCESS, errorMessage = null) }
        } catch (e: Exception) {
            Log.e("SoundToHaptics", "Demo unavailable", e)
        }
    }

    private fun startDemoPlayback(position: Int = 0, autoStart: Boolean = true) {
        val ctx = getApplication<Application>().applicationContext
        val afd = ctx.assets.openFd("Exported.ogg")
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(buildHapticAudioAttributes())
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            prepare()
            setOnErrorListener { _, what, extra ->
                reportPlaybackError("The demo haptic OGG could not play (decoder error $what/$extra).")
                true
            }
            setOnCompletionListener { onPlaybackFinished() }
            seekTo(position.coerceIn(0, duration.coerceAtLeast(0)))
            if (autoStart) start()
        }
        onPlaybackStarted(isDemo = true, isPlaying = autoStart)
    }

    /**
     * Whether the discrete-beat engine should be used for the CURRENT
     * playback path. Deliberately excludes Bluetooth split playback for
     * now: that path already juggles two MediaPlayers on two separate
     * routes to solve the "haptics over BT" problem, and layering a third,
     * independent vibrator-owning mechanism on top of that without being
     * able to test it on real BT hardware is a real risk rather than a
     * validated improvement. Continuous mode remains the only option
     * whenever a Bluetooth device is connected; this is intentional
     * scoping, not an oversight.
     *
     * Also excludes the demo track: it's a bundled asset with its own
     * baked-in continuous haptic channel and no corresponding onset data
     * (layer analysis only ever runs against a real conversion — see
     * analyzeLayersOrEmpty), so there's nothing for the transient engine to
     * play there. The demo has no sidecar onset timeline, so starting an empty
     * transient layer would add no value; its continuous OGG channel remains
     * enabled.
     */
    private fun isTransientEngineActive(): Boolean {
        if (_uiState.value.bluetoothDeviceConnected) return false
        if (currentPlaybackSource != PlaybackSource.FILE) return false
        val ctx = getApplication<Application>()
        return AppSettings.state.value.hapticEngine == HapticEngine.TRANSIENT &&
            TransientHapticRenderer.isSupported(ctx)
    }

    private fun buildHapticAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The continuous OGG channel is never muted for Offline
                // playback. Transient mode, when explicitly selected, is an
                // optional sparse accent layer over this signal rather than
                // a replacement for it.
                setHapticChannelsMuted(!_uiState.value.hapticPreviewEnabled)
            }
        }
        .build()

    private fun onPlaybackStarted(isDemo: Boolean = false, isPlaying: Boolean = true) {
        val p = mediaPlayer ?: return
        _uiState.update {
            it.copy(playerState = it.playerState.copy(
                isPlaying = isPlaying,
                duration = p.duration,
                currentPosition = p.currentPosition,
                fileName = if (isDemo) "Every End" else activeLibrarySong?.title ?: "Haptic Track",
                artist = if (isDemo) "Sound To Haptics" else activeLibrarySong?.artist ?: "Imported audio",
            ))
        }
        if (isPlaying) startProgressTracker()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val p = mediaPlayer
                if (p != null && p.isPlaying) {
                    _uiState.update { it.copy(playerState = it.playerState.copy(currentPosition = p.currentPosition)) }
                }
                delay(100)
            }
        }
    }

    fun stopPlayback() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        hapticOnlyPlayer?.takeIf { it.isPlaying }?.pause()
        transientRenderer.stop()
        _uiState.update { it.copy(playerState = it.playerState.copy(isPlaying = false)) }
    }

    fun togglePlayPause() {
        val p = mediaPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            hapticOnlyPlayer?.pause()
            transientRenderer.stop()
            _uiState.update { it.copy(playerState = it.playerState.copy(isPlaying = false)) }
        } else {
            p.start()
            hapticOnlyPlayer?.let { if (!it.isPlaying) it.start() }
            if (isTransientEngineActive() && _uiState.value.hapticPreviewEnabled) {
                transientRenderer.start { mediaPlayer?.currentPosition ?: 0 }
            }
            _uiState.update { it.copy(playerState = it.playerState.copy(isPlaying = true)) }
            startProgressTracker()
        }
    }

    fun seekTo(pos: Int) {
        mediaPlayer?.seekTo(pos)
        hapticOnlyPlayer?.seekTo(pos)
        transientRenderer.notifySeek(pos)
        _uiState.update { it.copy(playerState = it.playerState.copy(currentPosition = pos)) }
    }

    fun skipForward() {
        val p = mediaPlayer ?: return
        seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
    }

    fun skipBackward() {
        val p = mediaPlayer ?: return
        seekTo((p.currentPosition - 10000).coerceAtLeast(0))
    }

    private fun reportPlaybackError(message: String, error: Exception? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        releaseAllPlayers()
        _uiState.update {
            it.copy(
                conversionStage = ConversionStage.ERROR,
                playerState = it.playerState.copy(isPlaying = false),
                errorMessage = message,
            )
        }
    }

    private fun onPlaybackFinished() {
        progressJob?.cancel()
        progressJob = null
        transientRenderer.stop()
        _uiState.update { it.copy(playerState = it.playerState.copy(isPlaying = false, currentPosition = 0)) }
    }

    fun resetToIdle() {
        releaseAllPlayers()
        currentPlaybackSource = PlaybackSource.NONE
        currentPlaybackPath = null
        activeLibrarySong = null
        currentLayerAnalysis = null
        currentOnsetEvents = emptyList()
        currentTransientAnalysis = null
        _uiState.update {
            it.copy(
                conversionStage = ConversionStage.IDLE,
                progress = 0f,
                selectedSourceName = null,
                errorMessage = null,
                playerState = PlayerState(),
            )
        }
    }

    fun updateSensitivity(v: Float) {
        // One user-facing strength value is shared by the encoded Offline
        // OGG conversion, optional transient accents, and Spotify capture.
        AppSettings.setHapticStrength(v)
        _uiState.update { it.copy(sensitivity = v.coerceIn(0f, 1f)) }
    }
    fun updateBassBoost(v: Float) {
        AppSettings.setBassEmphasis(v)
        _uiState.update { it.copy(bassBoost = v.coerceIn(0f, 1f)) }
    }
    fun updateDensity(v: Float) {
        AppSettings.setHapticDensity(v)
        _uiState.update { it.copy(density = v.coerceIn(0f, 1f)) }
    }

    fun toggleHapticPreview(enabled: Boolean) {
        _uiState.update { it.copy(hapticPreviewEnabled = enabled) }
        val player = mediaPlayer ?: return
        val pos = player.currentPosition
        val wasPlaying = player.isPlaying

        when (currentPlaybackSource) {
            PlaybackSource.FILE -> {
                val file = currentPlaybackPath ?: currentHapticFile ?: return
                playFromFile(file)
                seekTo(pos)
                if (!wasPlaying) stopPlayback()
            }
            PlaybackSource.DEMO -> {
                releaseAllPlayers()
                try {
                    startDemoPlayback(position = pos, autoStart = wasPlaying)
                    _uiState.update { it.copy(conversionStage = ConversionStage.SUCCESS) }
                } catch (e: Exception) {
                    Log.e("SoundToHaptics", "Demo restart failed", e)
                }
            }
            PlaybackSource.NONE -> Unit
        }
    }

    private fun releaseAllPlayers() {
        progressJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        transientRenderer.stop()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { hapticOnlyPlayer?.release() } catch (_: Exception) {}
        hapticOnlyPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveConversion()
        try { getApplication<Application>().unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        releaseAllPlayers()
        transientRenderer.release()
    }
}
