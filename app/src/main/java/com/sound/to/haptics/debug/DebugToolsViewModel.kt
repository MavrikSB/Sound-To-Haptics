package com.sound.to.haptics.debug

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sound.to.haptics.BuildConfig
import com.sound.to.haptics.haptics.TrackHapticCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class DeviceInfo(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val device: String = Build.DEVICE,
    val board: String = Build.BOARD,
    val androidVersion: String = Build.VERSION.RELEASE,
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val buildId: String = Build.DISPLAY,
    val fingerprint: String = Build.FINGERPRINT,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val applicationId: String = BuildConfig.APPLICATION_ID,
)

data class HapticCapabilities(
    val hasVibrator: Boolean = false,
    val hasAmplitudeControl: Boolean = false,
    val supportsPredefinedEffects: Boolean = false,
    val supportsComposedPrimitives: Boolean = false,
    val likelySupportsAudioCoupledHaptics: Boolean = false,
)

enum class CalibrationStage { IDLE, RUNNING, DONE }

data class CalibrationState(
    val stage: CalibrationStage = CalibrationStage.IDLE,
    val currentAmplitude: Int = 0,
    val savedThreshold: Int? = null,
)

data class BatteryInfo(
    val percent: Int = 0,
    val isCharging: Boolean = false,
    val temperatureC: Float = 0f,
)

data class MemoryInfo(
    val availableMb: Long = 0,
    val totalMb: Long = 0,
    val lowMemory: Boolean = false,
)

data class DisplayInfo(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val densityDpi: Int = 0,
)

data class PermissionStatus(
    val recordAudio: Boolean = false,
)

data class FrameMetricsInfo(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val droppedFramesLast60s: Int = 0,
    val targetFrameMs: Float = 16.6f,
)

data class HeapInfo(
    val usedMb: Long = 0,
    val maxMb: Long = 0,
)

data class DebugFeatureFlags(
    val dynamicBackground: Boolean = true,
    val monetEngine: Boolean = true,
    val fallbackTheme: Boolean = false,
    val forceDarkTonalElevation: Boolean = false,
    val bypassBootAnimation: Boolean = false,
    val simulateLowEndGpu: Boolean = false,
)

enum class MotionSpeed(val label: String, val scale: Float) {
    QUARTER("0.25x", 0.25f),
    NORMAL("1.0x", 1f),
    DOUBLE("2.0x", 2f),
    FROZEN("0x", 0f),
}

class DebugToolsViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    data class MotionReading(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
        val magnitude: Float get() = sqrt(x * x + y * y + z * z)
    }

    val deviceInfo = DeviceInfo()

    private val prefs: SharedPreferences =
        application.getSharedPreferences("debug_tools", Context.MODE_PRIVATE)

    private val hapticCache = TrackHapticCache(application)

    private val _gyroscope = MutableStateFlow(MotionReading())
    val gyroscope: StateFlow<MotionReading> = _gyroscope.asStateFlow()

    private val _accelerometer = MutableStateFlow(MotionReading())
    val accelerometer: StateFlow<MotionReading> = _accelerometer.asStateFlow()

    private val _magnetometer = MutableStateFlow(MotionReading())
    val magnetometer: StateFlow<MotionReading> = _magnetometer.asStateFlow()

    private val _lightLux = MutableStateFlow(0f)
    val lightLux: StateFlow<Float> = _lightLux.asStateFlow()

    private val _proximityCm = MutableStateFlow(0f)
    val proximityCm: StateFlow<Float> = _proximityCm.asStateFlow()

    private val availableSensors = mutableSetOf<Int>()
    fun hasSensor(type: Int) = type in availableSensors

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val hapticCapabilities: HapticCapabilities by lazy {
        val hasVibrator = vibrator.hasVibrator()
        val hasAmplitude = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
        val predefined = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val composed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        HapticCapabilities(
            hasVibrator = hasVibrator,
            hasAmplitudeControl = hasAmplitude,
            supportsPredefinedEffects = predefined,
            supportsComposedPrimitives = composed,
            likelySupportsAudioCoupledHaptics = hasAmplitude && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
    }

    private val _calibration = MutableStateFlow(
        CalibrationState(savedThreshold = prefs.getInt(KEY_CALIBRATION, -1).takeIf { it >= 0 })
    )
    val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()

    fun startCalibration() {
        _calibration.value = _calibration.value.copy(stage = CalibrationStage.RUNNING, currentAmplitude = CALIBRATION_START)
        testVibration(CALIBRATION_START)
    }

    fun calibrationFeltIt() {
        val amplitude = _calibration.value.currentAmplitude
        prefs.edit().putInt(KEY_CALIBRATION, amplitude).apply()
        _calibration.value = _calibration.value.copy(stage = CalibrationStage.DONE, savedThreshold = amplitude)
    }

    fun calibrationDidntFeelIt() {
        val next = (_calibration.value.currentAmplitude + CALIBRATION_STEP).coerceAtMost(255)
        _calibration.value = _calibration.value.copy(currentAmplitude = next)
        testVibration(next)
        if (next >= 255) calibrationFeltIt()
    }

    fun resetCalibration() {
        prefs.edit().remove(KEY_CALIBRATION).apply()
        _calibration.value = CalibrationState()
    }

    private val _soundLevelDb = MutableStateFlow(0f)
    val soundLevelDb: StateFlow<Float> = _soundLevelDb.asStateFlow()

    private val _isMeteringSound = MutableStateFlow(false)
    val isMeteringSound: StateFlow<Boolean> = _isMeteringSound.asStateFlow()

    private var soundMeterJob: Job? = null

    val eightDPlayer = EightDAudioPlayer()

    private val _isVibratingContinuously = MutableStateFlow(false)
    val isVibratingContinuously: StateFlow<Boolean> = _isVibratingContinuously.asStateFlow()

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping.asStateFlow()
    private var sweepJob: Job? = null

    private val _isSequencing = MutableStateFlow(false)
    val isSequencing: StateFlow<Boolean> = _isSequencing.asStateFlow()
    private var sequencerJob: Job? = null

    private val _battery = MutableStateFlow(BatteryInfo())
    val battery: StateFlow<BatteryInfo> = _battery.asStateFlow()

    private val _memory = MutableStateFlow(MemoryInfo())
    val memory: StateFlow<MemoryInfo> = _memory.asStateFlow()

    val displayInfo: DisplayInfo by lazy {
        val metrics = application.resources.displayMetrics
        DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private var statsJob: Job? = null

    private val _frameMetrics = MutableStateFlow(FrameMetricsInfo())
    val frameMetrics: StateFlow<FrameMetricsInfo> = _frameMetrics.asStateFlow()

    private val _heap = MutableStateFlow(HeapInfo())
    val heap: StateFlow<HeapInfo> = _heap.asStateFlow()

    val featureFlags: StateFlow<DebugFeatureFlags> = DebugFlags.featureFlags
    val wireframeBounds: StateFlow<Boolean> = DebugFlags.wireframeBounds
    val alignmentGrid: StateFlow<Boolean> = DebugFlags.alignmentGrid
    val marginPaddingOverlay: StateFlow<Boolean> = DebugFlags.marginPaddingOverlay
    val motionSpeed: StateFlow<MotionSpeed> = DebugFlags.motionSpeed

    private val _consoleLines = MutableStateFlow(
        listOf(
            "[BOOT][INFO] Diagnostics overlay mounted",
            "[THEME][INFO] Dynamic color engine ready",
            "[GPU][WARN] Background shader falls back to Compose canvas on this build",
        )
    )
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameNanos = 0L
    private var frameWindowStartNanos = 0L
    private var frameCount = 0
    private val slowFrameTimestamps = ArrayDeque<Long>()

    fun startSensors() {
        val sensorTypes = listOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
        )
        availableSensors.clear()
        for (type in sensorTypes) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                availableSensors += type
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }

        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (isActive) {
                refreshBattery()
                refreshMemory()
                refreshHeap()
                delay(2000)
            }
        }
        startFrameMetrics()
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
        statsJob?.cancel()
        statsJob = null
        stopFrameMetrics()
    }

    private fun refreshBattery() {
        val app = getApplication<Application>()
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tenthsOfDegree = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        _battery.value = BatteryInfo(percent, charging, tenthsOfDegree / 10f)
    }

    private fun refreshMemory() {
        val app = getApplication<Application>()
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        _memory.value = MemoryInfo(
            availableMb = info.availMem / (1024 * 1024),
            totalMb = info.totalMem / (1024 * 1024),
            lowMemory = info.lowMemory,
        )
    }

    private fun refreshHeap() {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        _heap.value = HeapInfo(
            usedMb = used / (1024 * 1024),
            maxMb = runtime.maxMemory() / (1024 * 1024),
        )
    }

    private fun startFrameMetrics() {
        if (frameCallback != null) return
        choreographer = Choreographer.getInstance()
        frameWindowStartNanos = 0L
        lastFrameNanos = 0L
        frameCount = 0
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                    val targetMs = 16.6f
                    if (deltaMs > targetMs * 1.5f) {
                        slowFrameTimestamps.addLast(frameTimeNanos)
                    }
                    val cutoff = frameTimeNanos - 60_000_000_000L
                    while (slowFrameTimestamps.isNotEmpty() && slowFrameTimestamps.first() < cutoff) {
                        slowFrameTimestamps.removeFirst()
                    }
                    if (frameWindowStartNanos == 0L) frameWindowStartNanos = frameTimeNanos
                    frameCount += 1
                    val elapsedMs = (frameTimeNanos - frameWindowStartNanos) / 1_000_000f
                    if (elapsedMs >= 500f) {
                        _frameMetrics.value = FrameMetricsInfo(
                            fps = frameCount * 1000f / elapsedMs,
                            frameTimeMs = deltaMs,
                            droppedFramesLast60s = slowFrameTimestamps.size,
                            targetFrameMs = targetMs,
                        )
                        frameWindowStartNanos = frameTimeNanos
                        frameCount = 0
                    }
                }
                lastFrameNanos = frameTimeNanos
                choreographer?.postFrameCallback(this)
            }
        }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopFrameMetrics() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        choreographer = null
    }

    fun setWireframeBounds(enabled: Boolean) {
        DebugFlags.setWireframeBounds(enabled)
        addConsoleLine("LAYOUT", "INFO", "Wireframe bounds ${if (enabled) "enabled" else "disabled"}")
    }

    fun setAlignmentGrid(enabled: Boolean) {
        DebugFlags.setAlignmentGrid(enabled)
        addConsoleLine("LAYOUT", "INFO", "Alignment grid ${if (enabled) "enabled" else "disabled"}")
    }

    fun setMarginPaddingOverlay(enabled: Boolean) {
        DebugFlags.setMarginPaddingOverlay(enabled)
        addConsoleLine("LAYOUT", "INFO", "Margin/padding overlay ${if (enabled) "enabled" else "disabled"}")
    }

    fun setMotionSpeed(speed: MotionSpeed) {
        DebugFlags.setMotionSpeed(speed)
        addConsoleLine("MOTION", "INFO", "Global animation scale set to ${speed.label}")
    }

    fun updateFeatureFlags(transform: (DebugFeatureFlags) -> DebugFeatureFlags) {
        DebugFlags.updateFeatureFlags(transform)
        addConsoleLine("FLAGS", "INFO", "Feature matrix updated")
    }

    private fun addConsoleLine(source: String, severity: String, message: String) {
        val next = "[${source}][${severity}] $message"
        _consoleLines.value = (_consoleLines.value + next).takeLast(80)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> _gyroscope.value = MotionReading(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_ACCELEROMETER -> _accelerometer.value = MotionReading(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_MAGNETIC_FIELD -> _magnetometer.value = MotionReading(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_LIGHT -> _lightLux.value = event.values[0]
            Sensor.TYPE_PROXIMITY -> _proximityCm.value = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(),
        android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val permissionStatus: PermissionStatus get() = PermissionStatus(recordAudio = hasMicPermission())

    fun toggleSoundMeter() {
        if (_isMeteringSound.value) stopSoundMeter() else startSoundMeter()
    }

    private fun startSoundMeter() {
        if (!hasMicPermission() || _isMeteringSound.value) return

        val sampleRate = 44100
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return

        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (_: SecurityException) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        _isMeteringSound.value = true
        soundMeterJob = viewModelScope.launch(Dispatchers.Default) {
            record.startRecording()
            val buffer = ShortArray(minBuffer)
            try {
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i] / 32768.0
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / read)
                    val db = 20 * log10(max(rms, 1e-6)) + 100
                    _soundLevelDb.value = db.toFloat().coerceIn(0f, 100f)
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    private fun stopSoundMeter() {
        soundMeterJob?.cancel()
        soundMeterJob = null
        _isMeteringSound.value = false
        _soundLevelDb.value = 0f
    }

    fun testVibration(amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    fun testPredefinedEffect(effectId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        }
    }

    fun testPrimitive(primitiveId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(primitiveId, 1f).compose())
        }
    }

    fun testWaveform(timings: LongArray, amplitudes: IntArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    fun toggleContinuousVibration(amplitude: Int) {
        if (_isVibratingContinuously.value) {
            vibrator.cancel()
            _isVibratingContinuously.value = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(amplitude.coerceIn(1, 255)), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 1000), 0)
            }
            _isVibratingContinuously.value = true
        }
    }

    fun startAmplitudeSweep() {
        if (_isSweeping.value) return
        _isSweeping.value = true
        sweepJob = viewModelScope.launch {
            val steps = 25
            for (i in 0..steps) {
                if (!isActive) break
                val amplitude = (i * 255 / steps).coerceIn(1, 255)
                testVibration(amplitude)
                delay(220)
            }
            _isSweeping.value = false
        }
    }

    fun stopAmplitudeSweep() {
        sweepJob?.cancel()
        sweepJob = null
        vibrator.cancel()
        _isSweeping.value = false
    }

    fun playHapticSequencer(bpm: Int, beats: Int, amplitude: Int, accented: Boolean) {
        if (_isSequencing.value) return
        val beatMs = (60_000L / bpm.coerceIn(40, 220)).coerceAtLeast(120L)
        val beatCount = beats.coerceIn(1, 32)
        val amp = amplitude.coerceIn(1, 255)
        _isSequencing.value = true
        sequencerJob = viewModelScope.launch {
            for (beat in 0 until beatCount) {
                if (!isActive) break
                val beatAmplitude = if (accented && beat % 4 == 0) 255 else amp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, beatAmplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(80)
                }
                delay(beatMs)
            }
            _isSequencing.value = false
        }
    }

    fun stopHapticSequencer() {
        sequencerJob?.cancel()
        sequencerJob = null
        vibrator.cancel()
        _isSequencing.value = false
    }

    fun cancelAllHaptics() {
        stopAmplitudeSweep()
        stopHapticSequencer()
        if (_isVibratingContinuously.value) _isVibratingContinuously.value = false
        vibrator.cancel()
    }

    fun startEightDAudio() {
        eightDPlayer.start(onRotationPeak = {
            if (eightDPlayer.hapticSyncEnabled) testVibration(200)
        })
    }

    fun stopEightDAudio() = eightDPlayer.stop()

    fun clearBitChordHapticCache() = hapticCache.clearAll()

    private var qualityTestRenderer: com.sound.to.haptics.haptics.TransientHapticRenderer? = null
    private var qualityTestJob: Job? = null

    private val _qualityTestState = MutableStateFlow<QualityTestState>(QualityTestState.Idle)
    val qualityTestState: StateFlow<QualityTestState> = _qualityTestState.asStateFlow()

    sealed interface QualityTestState {
        data object Idle : QualityTestState
        data class Analyzing(val test: com.sound.to.haptics.haptics.TransientQualityTest) : QualityTestState
        data class Playing(val test: com.sound.to.haptics.haptics.TransientQualityTest, val eventCount: Int) : QualityTestState
        data class Failed(val test: com.sound.to.haptics.haptics.TransientQualityTest) : QualityTestState
    }

    fun runQualityTest(test: com.sound.to.haptics.haptics.TransientQualityTest) {
        stopQualityTest()
        _qualityTestState.value = QualityTestState.Analyzing(test)
        qualityTestJob = viewModelScope.launch {
            val analysis = withContext(Dispatchers.Default) {
                com.sound.to.haptics.haptics.TransientQualitySignalGenerator.generate(getApplication(), test)
            }
            if (analysis == null || analysis.events.isEmpty()) {
                _qualityTestState.value = QualityTestState.Failed(test)
                return@launch
            }
            val renderer = com.sound.to.haptics.haptics.TransientHapticRenderer(getApplication()).also { qualityTestRenderer = it }
            renderer.load(analysis)

            val startedAt = android.os.SystemClock.elapsedRealtime()
            renderer.start { (android.os.SystemClock.elapsedRealtime() - startedAt).toInt() }
            _qualityTestState.value = QualityTestState.Playing(test, analysis.events.size)
            delay(analysis.durationMs + 500L)
            if (_qualityTestState.value == QualityTestState.Playing(test, analysis.events.size)) {
                stopQualityTest()
            }
        }
    }

    fun stopQualityTest() {
        qualityTestJob?.cancel()
        qualityTestJob = null
        qualityTestRenderer?.release()
        qualityTestRenderer = null
        _qualityTestState.value = QualityTestState.Idle
    }

    fun buildDebugReport(): String {
        val d = deviceInfo
        val c = hapticCapabilities
        val b = _battery.value
        val m = _memory.value
        return buildString {
            appendLine("${d.manufacturer} ${d.model} — Android ${d.androidVersion} (API ${d.apiLevel})")
            appendLine("Build: ${d.buildId}")
            appendLine("App: ${d.appVersionName} (${d.appVersionCode}), ${d.applicationId}")
            appendLine("Vibrator: ${c.hasVibrator}, amplitude control: ${c.hasAmplitudeControl}")
            appendLine("Predefined effects: ${c.supportsPredefinedEffects}, primitives: ${c.supportsComposedPrimitives}")
            appendLine("Calibrated threshold: ${_calibration.value.savedThreshold ?: "not set"}")
            appendLine("Battery: ${b.percent}%, charging: ${b.isCharging}, temp: ${b.temperatureC}°C")
            appendLine("Memory: ${m.availableMb}MB / ${m.totalMb}MB free, low: ${m.lowMemory}")
            appendLine("Display: ${displayInfo.widthPx}×${displayInfo.heightPx} @ ${displayInfo.densityDpi}dpi")
            appendLine("Debug tools: sound meter=${_isMeteringSound.value}, sweep=${_isSweeping.value}, sequencer=${_isSequencing.value}")
            val capture = com.sound.to.haptics.haptics.CaptureDiagnostics.state.value
            appendLine("Capture: ${capture.stage}, projection=${capture.projectionActive}, format=${capture.sampleRate}Hz/${capture.channelCount}ch, buffer=${capture.bufferBytes}, bytes=${capture.bytesWritten}, signal=${capture.hadSignal}")
            appendLine("PCM validation: valid=${capture.pcmValid}, duration=${capture.pcmDurationMs}ms, rms=${capture.pcmRms}, peak=${capture.pcmPeak}, clipped=${capture.clippedSamples}")
            capture.lastError?.let { appendLine("Capture error: $it") }
            val layers = com.sound.to.haptics.haptics.AnalysisDiagnostics.state.value
            appendLine("Layers: bass=${layers.bass.size}, flow=${layers.vocalFlow.size}, percussion=${layers.percussion.size}, merged events=${layers.eventCount}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
        stopSoundMeter()
        stopAmplitudeSweep()
        stopHapticSequencer()
        stopQualityTest()
        eightDPlayer.stop()
    }

    companion object {
        private const val KEY_CALIBRATION = "min_perceptible_amplitude"
        private const val CALIBRATION_START = 20
        private const val CALIBRATION_STEP = 15
    }
}
