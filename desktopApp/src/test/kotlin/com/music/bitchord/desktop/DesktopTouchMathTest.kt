package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 360 T3: hit targets, long press timing, drag-to-scroll maths, keyboard throttle. */
class DesktopTouchMathTest {
    @Test
    fun hitTargetGrowsOnlyInTouchMode() {
        assertEquals(48f, DesktopTouchMath.hitTarget(34f, true))
        assertEquals(56f, DesktopTouchMath.hitTarget(56f, true))
        assertEquals(34f, DesktopTouchMath.hitTarget(34f, false))
    }

    @Test
    fun longPressNeedsTimeAndStillness() {
        assertTrue(DesktopTouchMath.isLongPress(500, 0f))
        assertFalse(DesktopTouchMath.isLongPress(499, 0f))
        assertFalse(DesktopTouchMath.isLongPress(900, 20f))
    }

    @Test
    fun contentFollowsTheFinger() {
        assertEquals(-30f, DesktopTouchMath.scrollDelta(30f))
        assertEquals(0f, DesktopTouchMath.scrollDelta(Float.NaN))
    }

    @Test
    fun flingIsOppositeToVelocityAndBounded() {
        assertEquals(-500f, DesktopTouchMath.flingDistance(2000f))
        assertEquals(6000f, DesktopTouchMath.flingDistance(-1_000_000f))
        assertEquals(0f, DesktopTouchMath.flingDistance(Float.POSITIVE_INFINITY))
    }

    @Test
    fun keyboardOncePerFocusAndOnlyOnWindowsInTouchMode() {
        assertTrue(DesktopTouchMath.shouldShowKeyboard(true, true, 10_000, 0))
        assertFalse(DesktopTouchMath.shouldShowKeyboard(true, true, 10_500, 10_000))
        assertTrue(DesktopTouchMath.shouldShowKeyboard(true, true, 12_000, 10_000))
        assertFalse(DesktopTouchMath.shouldShowKeyboard(false, true, 99_000, 0))
        assertFalse(DesktopTouchMath.shouldShowKeyboard(true, false, 99_000, 0))
    }
}
