package com.sound.to.haptics.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("System", "Match your device's theme"),
    LIGHT("Light", "Always use light theme"),
    DARK("Dark", "Always use dark theme");

    companion object {
        fun fromOrdinalSafe(ordinal: Int): ThemeMode = entries.getOrElse(ordinal) { SYSTEM }
    }
}

enum class BackgroundStyle(val label: String, val description: String) {
    ANIMATED("Dynamic", "Dynamic movement in the background"),
    STATIC("Static", "Same look, no motion or GPU cost"),
    OFF("Off", "Flat background color, no background");

    companion object {
        fun fromOrdinalSafe(ordinal: Int): BackgroundStyle = entries.getOrElse(ordinal) { ANIMATED }
    }
}

enum class HapticEngine(val label: String, val description: String) {
    CONTINUOUS("Continuous", "Smooth, always present texture"),
    TRANSIENT("Transient (Beta)", "Sharp individual taps on each detected beat, still WIP");

    companion object {
        fun fromOrdinalSafe(ordinal: Int): HapticEngine = entries.getOrElse(ordinal) { CONTINUOUS }
    }
}

data class AppSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.ANIMATED,
    val reduceMotion: Boolean = false,
    val hapticStrength: Float = 1f,
    val bassEmphasis: Float = 0.7f,
    val hapticDensity: Float = 0.6f,
    val keepScreenOnWhilePlaying: Boolean = false,
    val confirmBeforeClearingCache: Boolean = true,
    val confirmBeforeClearingLibrary: Boolean = true,
    val hapticEngine: HapticEngine = HapticEngine.CONTINUOUS,
    val onboardingCompleted: Boolean = false,
)

object AppSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_BACKGROUND_STYLE = "background_style"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    private const val KEY_BASS_EMPHASIS = "bass_emphasis"
    private const val KEY_HAPTIC_DENSITY = "haptic_density"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_while_playing"
    private const val KEY_CONFIRM_CLEAR_CACHE = "confirm_before_clearing_cache"
    private const val KEY_CONFIRM_CLEAR_LIBRARY = "confirm_before_clearing_library"
    private const val KEY_HAPTIC_ENGINE = "haptic_engine"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _state = MutableStateFlow(AppSettingsState())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = AppSettingsState(
            themeMode = ThemeMode.fromOrdinalSafe(prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.ordinal)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            backgroundStyle = BackgroundStyle.fromOrdinalSafe(prefs.getInt(KEY_BACKGROUND_STYLE, BackgroundStyle.ANIMATED.ordinal)),
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            hapticStrength = prefs.getFloat(KEY_HAPTIC_STRENGTH, 1f),
            bassEmphasis = prefs.getFloat(KEY_BASS_EMPHASIS, 0.7f),
            hapticDensity = prefs.getFloat(KEY_HAPTIC_DENSITY, 0.6f),
            keepScreenOnWhilePlaying = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            confirmBeforeClearingCache = prefs.getBoolean(KEY_CONFIRM_CLEAR_CACHE, true),
            confirmBeforeClearingLibrary = prefs.getBoolean(KEY_CONFIRM_CLEAR_LIBRARY, true),
            hapticEngine = HapticEngine.fromOrdinalSafe(prefs.getInt(KEY_HAPTIC_ENGINE, HapticEngine.CONTINUOUS.ordinal)),
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
        )
        initialized = true
    }

    fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        if (initialized) prefs.edit().putInt(KEY_THEME_MODE, mode.ordinal).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.value = _state.value.copy(dynamicColor = enabled)
        if (initialized) prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun setBackgroundStyle(style: BackgroundStyle) {
        _state.value = _state.value.copy(backgroundStyle = style)
        if (initialized) prefs.edit().putInt(KEY_BACKGROUND_STYLE, style.ordinal).apply()
    }

    fun setReduceMotion(enabled: Boolean) {
        _state.value = _state.value.copy(reduceMotion = enabled)
        if (initialized) prefs.edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
    }

    fun setHapticStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1f)
        _state.value = _state.value.copy(hapticStrength = clamped)
        if (initialized) prefs.edit().putFloat(KEY_HAPTIC_STRENGTH, clamped).apply()
    }

    fun setBassEmphasis(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _state.value = _state.value.copy(bassEmphasis = clamped)
        if (initialized) prefs.edit().putFloat(KEY_BASS_EMPHASIS, clamped).apply()
    }

    fun setHapticDensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _state.value = _state.value.copy(hapticDensity = clamped)
        if (initialized) prefs.edit().putFloat(KEY_HAPTIC_DENSITY, clamped).apply()
    }

    fun setKeepScreenOnWhilePlaying(enabled: Boolean) {
        _state.value = _state.value.copy(keepScreenOnWhilePlaying = enabled)
        if (initialized) prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun setConfirmBeforeClearingCache(enabled: Boolean) {
        _state.value = _state.value.copy(confirmBeforeClearingCache = enabled)
        if (initialized) prefs.edit().putBoolean(KEY_CONFIRM_CLEAR_CACHE, enabled).apply()
    }

    fun setConfirmBeforeClearingLibrary(enabled: Boolean) {
        _state.value = _state.value.copy(confirmBeforeClearingLibrary = enabled)
        if (initialized) prefs.edit().putBoolean(KEY_CONFIRM_CLEAR_LIBRARY, enabled).apply()
    }

    fun setHapticEngine(engine: HapticEngine) {
        _state.value = _state.value.copy(hapticEngine = engine)
        if (initialized) prefs.edit().putInt(KEY_HAPTIC_ENGINE, engine.ordinal).apply()
    }

    fun completeOnboarding() {
        _state.value = _state.value.copy(onboardingCompleted = true)
        if (initialized) prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    fun reopenOnboarding() {
        _state.value = _state.value.copy(onboardingCompleted = false)
        if (initialized) prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, false).apply()
    }

    fun resetToDefaults() {
        val completed = _state.value.onboardingCompleted
        _state.value = AppSettingsState(onboardingCompleted = completed)
        if (initialized) {
            prefs.edit().clear().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        }
    }
}

object PlaybackActivity {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun setPlaying(playing: Boolean) {
        if (_isPlaying.value != playing) _isPlaying.value = playing
    }
}
