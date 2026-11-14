package com.sound.to.haptics.debug

import com.sound.to.haptics.ui.motion.setMotionSpeedScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide store for everything the debug menu controls.
 *
 * This exists as a singleton (rather than living only inside
 * [DebugToolsViewModel]) because a few of these flags — [featureFlags]'
 * `monetEngine`/`fallbackTheme`/`forceDarkTonalElevation` in particular —
 * need to reach [com.sound.to.haptics.ui.theme.HapticStudioTheme], which
 * is composed in MainActivity *above* RootScreen (and therefore above
 * DebugToolsViewModel's own ViewModelStore). Everything else that reads
 * these flags (RootScreen, Background.kt, Motion.kt) just collects them
 * from here too, so there's a single source of truth either way.
 *
 * [DebugToolsViewModel] doesn't hold its own copies of this state anymore —
 * its wireframeBounds/alignmentGrid/marginPaddingOverlay/motionSpeed/
 * featureFlags properties are thin pass-throughs to this object, so the
 * debug screen's code didn't need to change at all.
 */
object DebugFlags {

    private val _featureFlags = MutableStateFlow(DebugFeatureFlags())
    val featureFlags: StateFlow<DebugFeatureFlags> = _featureFlags.asStateFlow()

    fun updateFeatureFlags(transform: (DebugFeatureFlags) -> DebugFeatureFlags) {
        _featureFlags.value = transform(_featureFlags.value)
    }

    private val _motionSpeed = MutableStateFlow(MotionSpeed.NORMAL)
    val motionSpeed: StateFlow<MotionSpeed> = _motionSpeed.asStateFlow()

    fun setMotionSpeed(speed: MotionSpeed) {
        _motionSpeed.value = speed
        setMotionSpeedScale(speed.scale)
    }

    private val _wireframeBounds = MutableStateFlow(false)
    val wireframeBounds: StateFlow<Boolean> = _wireframeBounds.asStateFlow()
    fun setWireframeBounds(enabled: Boolean) { _wireframeBounds.value = enabled }

    private val _alignmentGrid = MutableStateFlow(false)
    val alignmentGrid: StateFlow<Boolean> = _alignmentGrid.asStateFlow()
    fun setAlignmentGrid(enabled: Boolean) { _alignmentGrid.value = enabled }

    private val _marginPaddingOverlay = MutableStateFlow(false)
    val marginPaddingOverlay: StateFlow<Boolean> = _marginPaddingOverlay.asStateFlow()
    fun setMarginPaddingOverlay(enabled: Boolean) { _marginPaddingOverlay.value = enabled }
}
