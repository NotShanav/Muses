package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rotation, scaling and the compact layout: the rules that keep the window on its screen. */
class DesktopDisplayGeometryTest {

    private fun work(widthPx: Int, heightPx: Int) = DisplayRect(0, 0, widthPx, heightPx)

    @Test
    fun minimumNeverExceedsTheWorkAreaAtAnyScaleOrOrientation() {
        val screens = listOf(
            1920 to 1040, 1080 to 1860, // landscape and portrait, taskbar taken out
            1366 to 728, 768 to 1326,
            3840 to 2100, 2160 to 3800,
        )
        for ((w, h) in screens) for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
            val area = work(w, h)
            val min = DesktopDisplayGeometry.minimumSize(area, scale)
            assertTrue(min.width in 1..area.width, "width $w x $h @ $scale -> ${min.width}")
            assertTrue(min.height in 1..area.height, "height $w x $h @ $scale -> ${min.height}")
        }
    }

    @Test
    fun portraitScreenGetsThePortraitMinimum() {
        // 1080 x 1920 at 150% is 720 x 1280 dp: narrower than the old fixed 900dp minimum.
        val min = DesktopDisplayGeometry.minimumSize(work(1080, 1860), 1.5)
        assertEquals(540, min.width) // 360dp
        assertEquals(840, min.height) // 560dp
    }

    @Test
    fun landscapeScreenKeepsTheDesktopMinimum() {
        val min = DesktopDisplayGeometry.minimumSize(work(1920, 1040), 1.0)
        assertEquals(900, min.width)
        assertEquals(600, min.height)
    }

    @Test
    fun a200PercentLandscapeScreenIsClampedToItsHeight() {
        // 1920 x 1080 at 200% is 960 x 540 dp: the 600dp height minimum would not fit.
        val min = DesktopDisplayGeometry.minimumSize(work(1920, 1040), 2.0)
        assertEquals(1800, min.width)
        assertEquals(1040, min.height)
    }

    @Test
    fun aNonsenseScaleDoesNotBreakTheMinimum() {
        val min = DesktopDisplayGeometry.minimumSize(work(1920, 1040), 0.0)
        assertTrue(min.width > 0 && min.height > 0)
    }

    @Test
    fun aWindowLargerThanTheNewWorkAreaEndsUpInsideIt() {
        val area = work(1080, 1860)
        val window = DisplayRect(100, 50, 1830, 1170) // the landscape window, before rotation
        val min = DesktopDisplayGeometry.minimumSize(area, 1.5)
        val fixed = DesktopDisplayGeometry.clampToWorkArea(window, area, min.width, min.height)
        assertInside(fixed, area)
        assertEquals(1080, fixed.width)
    }

    @Test
    fun aWindowOutsideTheWorkAreaIsMovedBackIn() {
        val area = DisplayRect(1920, 0, 1080, 1860) // a rotated monitor to the right of the main one
        val window = DisplayRect(1500, 1700, 700, 500) // half off the left and the bottom
        val fixed = DesktopDisplayGeometry.clampToWorkArea(window, area, 540, 840)
        assertInside(fixed, area)
    }

    @Test
    fun aWindowAlreadyInsideIsLeftAlone() {
        val area = work(1920, 1040)
        val window = DisplayRect(200, 100, 1000, 700)
        assertEquals(window, DesktopDisplayGeometry.clampToWorkArea(window, area, 900, 600))
    }

    @Test
    fun theMinimumHoldsEvenWhenTheWindowIsSmaller() {
        val area = work(1920, 1040)
        val fixed = DesktopDisplayGeometry.clampToWorkArea(DisplayRect(0, 0, 100, 100), area, 900, 600)
        assertEquals(900, fixed.width)
        assertEquals(600, fixed.height)
    }

    @Test
    fun compactBreakpoint() {
        assertTrue(DesktopDisplayGeometry.isCompact(360f))
        assertTrue(DesktopDisplayGeometry.isCompact(720f))
        assertTrue(DesktopDisplayGeometry.isCompact(979.9f))
        assertFalse(DesktopDisplayGeometry.isCompact(980f))
        assertFalse(DesktopDisplayGeometry.isCompact(1280f))
    }

    @Test
    fun narrowBreakpoint() {
        assertTrue(DesktopDisplayGeometry.isNarrow(360f))
        assertFalse(DesktopDisplayGeometry.isNarrow(720f))
    }

    private fun assertInside(inner: DisplayRect, outer: DisplayRect) {
        assertTrue(inner.x >= outer.x && inner.y >= outer.y, "$inner starts outside $outer")
        assertTrue(inner.right <= outer.right && inner.bottom <= outer.bottom, "$inner ends outside $outer")
    }
}
