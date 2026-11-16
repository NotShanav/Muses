package com.music.bitchord.desktop

/** A rectangle in the window system's units (what AWT's `Window.bounds` is measured in). */
internal data class DisplayRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

internal data class DisplaySize(val width: Int, val height: Int)

/**
 * The window's size rules, kept free of AWT and Compose so they can be tested: where the minimum
 * size sits for a given screen, where a window must be to be fully on it, and where the layout
 * switches to its compact form.
 */
internal object DesktopDisplayGeometry {

    /** Below this width the shell uses its compact layout. */
    const val COMPACT_BREAKPOINT_DP = 980f

    /** Below this width (a phone-sized window, or a portrait screen) the chrome sheds secondary controls. */
    const val NARROW_BREAKPOINT_DP = 520f

    const val MIN_WIDTH_DP = 900.0
    const val MIN_HEIGHT_DP = 600.0

    /** What the window may shrink to when the screen is narrower than [MIN_WIDTH_DP], e.g. portrait. */
    const val NARROW_MIN_WIDTH_DP = 360.0
    const val NARROW_MIN_HEIGHT_DP = 480.0

    fun isCompact(widthDp: Float): Boolean = widthDp < COMPACT_BREAKPOINT_DP

    fun isNarrow(widthDp: Float): Boolean = widthDp < NARROW_BREAKPOINT_DP

    /**
     * The smallest size the window may be dragged to on [work], in the same units as [work].
     *
     * The landscape minimum while the work area is wide enough to hold it, the portrait one
     * otherwise, and never larger than the work area itself: a minimum bigger than the screen is
     * what let AWT and Compose disagree about the window's size after a rotation.
     */
    fun minimumSize(work: DisplayRect, scale: Double): DisplaySize {
        val s = if (scale.isFinite() && scale > 0.0) scale else 1.0
        val workWidthDp = work.width / s
        val narrow = workWidthDp < MIN_WIDTH_DP
        val wantedWidth = Math.round((if (narrow) NARROW_MIN_WIDTH_DP else MIN_WIDTH_DP) * s).toInt()
        val wantedHeight = Math.round((if (narrow) NARROW_MIN_HEIGHT_DP else MIN_HEIGHT_DP) * s).toInt()
        return DisplaySize(
            wantedWidth.coerceAtMost(work.width).coerceAtLeast(1),
            wantedHeight.coerceAtMost(work.height).coerceAtLeast(1),
        )
    }

    /**
     * [window] moved and shrunk until it lies entirely inside [work]. Never smaller than the
     * minimum, which [minimumSize] keeps within [work].
     */
    fun clampToWorkArea(window: DisplayRect, work: DisplayRect, minWidth: Int, minHeight: Int): DisplayRect {
        val width = window.width.coerceIn(minWidth.coerceAtMost(work.width), work.width)
        val height = window.height.coerceIn(minHeight.coerceAtMost(work.height), work.height)
        val x = window.x.coerceIn(work.x, work.right - width)
        val y = window.y.coerceIn(work.y, work.bottom - height)
        return DisplayRect(x, y, width, height)
    }
}
