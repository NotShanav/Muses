package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the layout needs to know about the window it is in. 360 T1 contract: T2 and T3 read only this.
 *
 * [widthDp] and [heightDp] are the window's content size in dp (what Compose lays out in), not the
 * monitor's. [isPortrait] is the window's own shape (taller than wide), which is what a layout
 * cares about; a narrow window on a landscape monitor is portrait too.
 * [isTouchLikely] is true when Windows reports a touch digitizer; false off Windows.
 */
internal data class DesktopDisplayInfo(
    val widthDp: Float,
    val heightDp: Float,
    val isPortrait: Boolean,
    val isTouchLikely: Boolean,
)

internal object DesktopDisplayWatch {

    private val _info = MutableStateFlow(DesktopDisplayInfo(1220f, 780f, false, false))

    /** Updated on start, on every window resize, and on every rotation, scale or monitor change. */
    val info: StateFlow<DesktopDisplayInfo> = _info

    /** Portrait means strictly taller than wide; a square window counts as landscape. */
    fun isPortrait(widthDp: Float, heightDp: Float): Boolean = heightDp > widthDp

    /** Pixels to dp for [scale]; a missing or nonsense scale counts as 1. */
    fun toDp(pixels: Int, scale: Double): Float {
        val s = if (scale.isFinite() && scale > 0.0) scale else 1.0
        return (pixels.coerceAtLeast(0) / s).toFloat()
    }

    /** `GetSystemMetrics(SM_DIGITIZER)`: bit 0 integrated touch, bit 1 external touch. */
    fun digitizerPresent(nativePostureFlags: Int): Boolean = nativePostureFlags >= 0 && (nativePostureFlags and 2) != 0

    fun build(widthDp: Float, heightDp: Float, touch: Boolean): DesktopDisplayInfo {
        val w = if (widthDp.isFinite()) widthDp.coerceAtLeast(0f) else 0f
        val h = if (heightDp.isFinite()) heightDp.coerceAtLeast(0f) else 0f
        return DesktopDisplayInfo(w, h, isPortrait(w, h), touch)
    }

    fun publish(widthDp: Float, heightDp: Float, touch: Boolean) {
        _info.value = build(widthDp, heightDp, touch)
    }
}
