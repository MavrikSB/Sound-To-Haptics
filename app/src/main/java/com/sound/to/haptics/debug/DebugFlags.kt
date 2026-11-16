package com.sound.to.haptics.debug

import com.sound.to.haptics.ui.motion.setMotionSpeedScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
