package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 360 T1: the display info contract and the crash guard's pure parts. */
class DesktopDisplayWatchTest {

    @Test
    fun portraitMeansTallerThanWide() {
        assertTrue(DesktopDisplayWatch.isPortrait(540f, 960f))
        assertFalse(DesktopDisplayWatch.isPortrait(1280f, 800f))
        assertFalse(DesktopDisplayWatch.isPortrait(800f, 800f))
    }

    @Test
    fun pixelsConvertToDpAndNonsenseScaleCountsAsOne() {
        assertEquals(720f, DesktopDisplayWatch.toDp(1080, 1.5), 0.001f)
        assertEquals(100f, DesktopDisplayWatch.toDp(100, 0.0), 0.001f)
        assertEquals(100f, DesktopDisplayWatch.toDp(100, Double.NaN), 0.001f)
        assertEquals(0f, DesktopDisplayWatch.toDp(-5, 1.0), 0.001f)
    }

    @Test
    fun digitizerFlagReadsBitOneAndIgnoresMissingBridge() {
        assertTrue(DesktopDisplayWatch.digitizerPresent(2))
        assertTrue(DesktopDisplayWatch.digitizerPresent(3))
        assertFalse(DesktopDisplayWatch.digitizerPresent(1))
        assertFalse(DesktopDisplayWatch.digitizerPresent(-1))
    }

    @Test
    fun buildNeverProducesNegativeOrNonFiniteSizes() {
        val info = DesktopDisplayWatch.build(-3f, Float.NaN, true)
        assertEquals(0f, info.widthDp)
        assertEquals(0f, info.heightDp)
        assertFalse(info.isPortrait)
        assertTrue(info.isTouchLikely)
        val tall = DesktopDisplayWatch.build(540f, 960f, false)
        assertTrue(tall.isPortrait)
    }

    @Test
    fun restartLimiterAllowsAFewThenRefusesUntilTheWindowPasses() {
        val limiter = RestartLimiter(maxRestarts = 3, windowMillis = 1_000L)
        assertTrue(limiter.allow(0))
        assertTrue(limiter.allow(100))
        assertTrue(limiter.allow(200))
        assertFalse(limiter.allow(300))
        assertTrue(limiter.allow(1_500)) // the early ones have aged out
    }

    @Test
    fun uiThreadsAreRecognisedByName() {
        assertTrue(DesktopWindowGuard.isUiThread("AWT-EventQueue-0"))
        assertTrue(DesktopWindowGuard.isUiThread("skiko-dispatcher"))
        assertFalse(DesktopWindowGuard.isUiThread("DefaultDispatcher-worker-3"))
        assertFalse(DesktopWindowGuard.isUiThread(null))
    }

    @Test
    fun describeKeepsTheMessageAndAFewFrames() {
        val text = DesktopWindowGuard.describe("main", IllegalStateException("boom"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("boom"))
    }
}
