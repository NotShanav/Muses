package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The stored choice, the posture, and what they add up to. */
internal object DesktopTouchSettings {

    internal const val KEY = "convertible_touch_mode"

    private val _mode = MutableStateFlow(
        DesktopTouchMode.fromStored(DesktopPersistence().string(KEY, DesktopTouchMode.DEFAULT.name)),
    )
    private val _posture = MutableStateFlow(DesktopPosture.UNKNOWN)
    private val _active = MutableStateFlow(false)

    val mode: StateFlow<DesktopTouchMode> = _mode
    val posture: StateFlow<DesktopPosture> = _posture

    /** Whether the UI is sized for fingers right now. */
    val active: StateFlow<Boolean> = _active

    init {
        recompute()
    }

    fun setMode(value: DesktopTouchMode) {
        DesktopPersistence().saveString(KEY, value.name)
        _mode.value = value
        recompute()
    }

    fun setPosture(value: DesktopPosture) {
        if (_posture.value == value) return
        _posture.value = value
        recompute()
    }

    private fun recompute() {
        _active.value = DesktopTouchPolicy.resolve(_mode.value, _posture.value)
    }
}

/** True while the UI is sized for touch. Provided once at the window's root. */
internal val LocalDesktopTouchMode = compositionLocalOf { false }

internal val TOUCH_TARGET: Dp = 48.dp

/** This size, or at least a finger's width while touch mode is on. */
@Composable
internal fun Dp.forTouch(): Dp = if (LocalDesktopTouchMode.current) coerceAtLeast(TOUCH_TARGET) else this

/** The window's width in dp, which follows a rotation; for chrome that sheds controls when narrow. */
@Composable
internal fun rememberWindowWidthDp(): Dp {
    val info = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) { info.containerSize.width.toDp() }
}

/**
 * Closes whatever composes this when the screen changes under the window. A popup is placed in
 * window coordinates once, when it opens; after a rotation it would hang where the old layout put
 * it, off the new window or over the wrong control.
 */
@Composable
internal fun DismissOnDisplayChange(onDismiss: () -> Unit) {
    val epoch by DesktopDisplayEvents.epoch.collectAsState()
    val opened = remember { epoch }
    val latest by rememberUpdatedState(onDismiss)
    LaunchedEffect(epoch) { if (epoch != opened) latest() }
}
