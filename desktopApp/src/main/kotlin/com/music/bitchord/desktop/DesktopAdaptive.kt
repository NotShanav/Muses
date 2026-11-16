package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 360 T2: how wide the window is, in the three classes the shell lays itself out by. */
internal enum class DesktopWindowClass { COMPACT, MEDIUM, EXPANDED }

internal enum class DesktopOrientation { PORTRAIT, LANDSCAPE }

internal data class DesktopAdaptive(
    val windowClass: DesktopWindowClass,
    val orientation: DesktopOrientation,
    val widthDp: Float,
    val heightDp: Float,
) {
    val isPortrait: Boolean get() = orientation == DesktopOrientation.PORTRAIT
    val isCompact: Boolean get() = windowClass == DesktopWindowClass.COMPACT

    /** True below the full desktop layout (what the old `maxWidth < 980.dp` meant). */
    val isBelowExpanded: Boolean get() = windowClass != DesktopWindowClass.EXPANDED

    companion object {
        val DEFAULT = DesktopAdaptive(DesktopWindowClass.EXPANDED, DesktopOrientation.LANDSCAPE, 1220f, 780f)
    }
}

/**
 * Pure sizing rules for the narrow layouts, free of Compose so they can be tested. All sizes are dp
 * as floats and none of them ever returns a negative number: a constraint that goes negative is
 * what crashes a layout at an extreme size.
 */
internal object DesktopAdaptiveMath {

    const val COMPACT_BELOW_DP = 600f
    const val EXPANDED_FROM_DP = 980f

    /** A column beside the page needs room; narrower windows get the panel as an overlay. */
    const val SIDE_PANEL_OVERLAY_BELOW_DP = 800f

    const val PILL_MAX_HEIGHT_COMPACT_DP = 72f
    const val PILL_MIN_VEIL_COMPACT = 0.55f
    const val PILL_SIDE_INSET_COMPACT_DP = 12f
    const val PILL_BOTTOM_INSET_COMPACT_DP = 12f
    const val PAGE_GUTTER_COMPACT_DP = 16f
    const val TOUCH_TARGET_DP = 44f

    fun windowClassFor(widthDp: Float): DesktopWindowClass = when {
        !(widthDp >= COMPACT_BELOW_DP) -> DesktopWindowClass.COMPACT // also catches NaN
        widthDp < EXPANDED_FROM_DP -> DesktopWindowClass.MEDIUM
        else -> DesktopWindowClass.EXPANDED
    }

    fun orientationFor(widthDp: Float, heightDp: Float): DesktopOrientation =
        if (heightDp > widthDp) DesktopOrientation.PORTRAIT else DesktopOrientation.LANDSCAPE

    fun adaptiveFor(widthDp: Float, heightDp: Float): DesktopAdaptive {
        val w = if (widthDp.isFinite()) widthDp.coerceAtLeast(0f) else 0f
        val h = if (heightDp.isFinite()) heightDp.coerceAtLeast(0f) else 0f
        return DesktopAdaptive(windowClassFor(w), orientationFor(w, h), w, h)
    }

    /** [preferred], or what is left of [available] after [gutter] on both sides; never negative. */
    fun fitWidth(preferred: Float, available: Float, gutter: Float): Float {
        val room = (available - gutter * 2f).coerceAtLeast(0f)
        return preferred.coerceAtLeast(0f).coerceAtMost(room)
    }

    /** How many columns of at least [minCell] fit in [available] with [gutter] between them. */
    fun columnsFor(available: Float, minCell: Float, gutter: Float, maxColumns: Int): Int {
        if (minCell <= 0f || maxColumns < 1) return 1
        val usable = available.coerceAtLeast(0f)
        val fit = ((usable + gutter) / (minCell + gutter)).toInt()
        return fit.coerceIn(1, maxColumns)
    }

    /** Width of one cell in [columns] across [available], leaving [peek] of the next one showing. */
    fun cellWidth(available: Float, columns: Int, gutter: Float, peek: Float = 0f): Float {
        val n = columns.coerceAtLeast(1)
        val usable = (available - peek.coerceAtLeast(0f) - gutter * (n - 1)).coerceAtLeast(0f)
        return usable / n
    }

    fun sidePanelAsOverlay(widthDp: Float): Boolean = !(widthDp >= SIDE_PANEL_OVERLAY_BELOW_DP)

    /** The pill is never taller than 72 dp in a COMPACT window. */
    fun pillHeight(wantedDp: Float, windowClass: DesktopWindowClass): Float =
        if (windowClass == DesktopWindowClass.COMPACT) wantedDp.coerceAtMost(PILL_MAX_HEIGHT_COMPACT_DP) else wantedDp

    /** The pill's dark veil: raised to [PILL_MIN_VEIL_COMPACT] in COMPACT or portrait windows so page text never shows through. */
    fun pillVeil(veil: Float, windowClass: DesktopWindowClass, isPortrait: Boolean): Float {
        val v = if (veil.isFinite()) veil.coerceIn(0f, 1f) else 0f
        return if (windowClass == DesktopWindowClass.COMPACT || isPortrait) v.coerceAtLeast(PILL_MIN_VEIL_COMPACT) else v
    }

    /** Space the page keeps at its bottom so the last row clears the pill: pill + its bottom inset + a margin. */
    fun pageBottomPadding(pillHeightDp: Float, pillBottomInsetDp: Float, marginDp: Float = 16f): Float =
        (pillHeightDp.coerceAtLeast(0f) + pillBottomInsetDp.coerceAtLeast(0f) + marginDp.coerceAtLeast(0f))

    fun pillSideInset(windowClass: DesktopWindowClass): Float =
        if (windowClass == DesktopWindowClass.COMPACT) PILL_SIDE_INSET_COMPACT_DP else 20f

    fun pillBottomInset(configuredDp: Float, windowClass: DesktopWindowClass): Float =
        if (windowClass == DesktopWindowClass.COMPACT) configuredDp.coerceAtLeast(PILL_BOTTOM_INSET_COMPACT_DP) else configuredDp

    /** At least a finger's width for icon buttons in the top bar of a narrow window. */
    fun topBarTarget(windowClass: DesktopWindowClass, normalDp: Float): Float =
        if (windowClass == DesktopWindowClass.EXPANDED) normalDp else normalDp.coerceAtLeast(TOUCH_TARGET_DP)
}

/** The class of the window the shell is in; EXPANDED until a [DesktopFrame] says otherwise. */
internal val LocalDesktopWindowClass = compositionLocalOf { DesktopWindowClass.EXPANDED }

/** Class, orientation and size together. The merge with T1's `DesktopDisplayWatch.info` is a one-line change here. */
internal val LocalDesktopAdaptive = compositionLocalOf { DesktopAdaptive.DEFAULT }

/** This width, or what the window leaves after [gutter] on each side: for cards and art with a fixed preferred size. */
@Composable
internal fun Dp.fitWindow(gutter: Dp = 16.dp): Dp {
    val width = LocalDesktopAdaptive.current.widthDp
    return DesktopAdaptiveMath.fitWidth(value, width, gutter.value).dp
}
