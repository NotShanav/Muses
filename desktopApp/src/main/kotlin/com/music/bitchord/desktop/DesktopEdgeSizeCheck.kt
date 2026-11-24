package com.music.bitchord.desktop

/**
 * UI U5: the panel window's size must equal the size the layout was computed for, in every state.
 * Pure decision; the controller asserts the bounds again (and repaints) when this says they drifted.
 */
internal object EdgeSizeCheck {
    /** One pixel of rounding is fine; anything more means Windows or Compose resized the window. */
    private const val TOLERANCE_PX = 1

    fun drifted(expected: DisplayRect, actual: DisplayRect): Boolean =
        kotlin.math.abs(expected.width - actual.width) > TOLERANCE_PX ||
            kotlin.math.abs(expected.height - actual.height) > TOLERANCE_PX
}
