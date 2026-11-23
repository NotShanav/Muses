package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EDGE 3: settings validation, name mapping, slider snapping and the mapping to geometry. No screen needed. */
class DesktopEdgeSettingsTest {

    private val C = DesktopEdgeSettingsCodec

    @Test
    fun defaultsAreOffAndSane() {
        val v = EdgeSettingsValues()
        assertFalse(v.enabled)
        assertFalse(v.startInPanel)
        assertFalse(v.startsInPanel)
        assertEquals(EdgeSide.RIGHT, v.config.side)
        assertEquals(420, v.config.widthDp)
        assertEquals(4, v.config.zoneWidthPx)
        assertEquals(150L, v.config.dwellMs)
        assertEquals(700L, v.config.closeDelayMs)
        assertTrue(v.config.suppressWhenFullscreen)
        assertFalse(v.config.lowPower)
        assertFalse(v.config.pinByDefault)
    }

    @Test
    fun startInPanelNeedsTheFeatureOn() {
        assertFalse(EdgeSettingsValues(enabled = false, startInPanel = true).startsInPanel)
        assertTrue(EdgeSettingsValues(enabled = true, startInPanel = true).startsInPanel)
        assertFalse(EdgeSettingsValues(enabled = true, startInPanel = false).startsInPanel)
    }

    @Test
    fun coercionClampsEveryRange() {
        val low = EdgeSettingsValues(
            config = EdgePanelConfig(widthDp = 10, zoneWidthPx = 0, dwellMs = 1, closeDelayMs = 1, pickedMonitor = -3),
        ).coerced().config
        assertEquals(340, low.widthDp)
        assertEquals(2, low.zoneWidthPx)
        assertEquals(50L, low.dwellMs)
        assertEquals(300L, low.closeDelayMs)
        assertEquals(0, low.pickedMonitor)

        val high = EdgeSettingsValues(
            config = EdgePanelConfig(widthDp = 9_999, zoneWidthPx = 99, dwellMs = 99_999, closeDelayMs = 99_999),
        ).coerced().config
        assertEquals(640, high.widthDp)
        assertEquals(12, high.zoneWidthPx)
        assertEquals(400L, high.dwellMs)
        assertEquals(2_000L, high.closeDelayMs)
    }

    @Test
    fun valuesInsideTheRangeAreKept() {
        val cfg = EdgePanelConfig(widthDp = 500, zoneWidthPx = 8, dwellMs = 200, closeDelayMs = 1_000)
        assertEquals(cfg, EdgeSettingsValues(config = cfg).coerced().config)
    }

    @Test
    fun invalidSegmentBecomesTheFullEdge() {
        assertEquals(EdgeSegment.FULL, C.segment(80, 20))
        assertEquals(EdgeSegment.FULL, C.segment(50, 50))
        assertEquals(EdgeSegment.FULL, C.segment(-5, 500))
        assertEquals(EdgeSegment(10, 60), C.segment(10, 60))
        val v = EdgeSettingsValues(config = EdgePanelConfig(segment = EdgeSegment(90, 10))).coerced()
        assertEquals(EdgeSegment.FULL, v.config.segment)
    }

    @Test
    fun segmentSlidersKeepAMinimumSpan() {
        val s = EdgeSegment(20, 80)
        assertEquals(EdgeSegment(75, 80), C.segmentWithFrom(s, 99))
        assertEquals(EdgeSegment(0, 80), C.segmentWithFrom(s, -4))
        assertEquals(EdgeSegment(20, 25), C.segmentWithTo(s, 1))
        assertEquals(EdgeSegment(20, 100), C.segmentWithTo(s, 500))
        assertTrue(C.segmentWithFrom(s, 50).isValid)
        assertTrue(C.segmentWithTo(s, 50).isValid)
    }

    @Test
    fun namesRoundTripAndUnknownNamesFallBack() {
        EdgeSide.entries.forEach { assertEquals(it, C.side(C.sideName(it))) }
        EdgeMonitorChoice.entries.forEach { assertEquals(it, C.monitor(C.monitorName(it))) }
        EdgeSpeed.entries.forEach { assertEquals(it, C.speed(C.speedName(it))) }
        assertEquals(EdgeSide.RIGHT, C.side("nonsense"))
        assertEquals(EdgeSide.LEFT, C.side("LEFT"))
        assertEquals(EdgeMonitorChoice.CURSOR, C.monitor(""))
        assertEquals(EdgeSpeed.NORMAL, C.speed("warp"))
    }

    @Test
    fun sliderSnappingStaysOnTheStepAndInRange() {
        assertEquals(420, edgeSnap(423f, 340..640, 10))
        assertEquals(430, edgeSnap(425f, 340..640, 10))
        assertEquals(640, edgeSnap(10_000f, 340..640, 10))
        assertEquals(340, edgeSnap(-5f, 340..640, 10))
        assertEquals(7, edgeSnap(6.6f, 2..12, 1))
        assertEquals(700, edgeSnap(712f, 300..2000, 50))
    }

    @Test
    fun configMapsToTheWatcher() {
        val cfg = EdgePanelConfig(
            side = EdgeSide.LEFT, zoneWidthPx = 9, segment = EdgeSegment(10, 60),
            dwellMs = 250, suppressWhenFullscreen = false, lowPower = true,
        )
        val w = cfg.watchConfig()
        assertEquals(EdgeSide.LEFT, w.side)
        assertEquals(9, w.zoneWidthPx)
        assertEquals(EdgeSegment(10, 60), w.segment)
        assertEquals(250L, w.dwellMs)
        assertFalse(w.suppressWhenFullscreen)
        assertTrue(w.lowPower)
    }

    @Test
    fun configMapsToTheDockedRectangle() {
        val m = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val left = DesktopEdgeGeometry.layout(m, EdgeSide.LEFT, EdgePanelConfig(widthDp = 500).coerced().widthDp)
        assertEquals(500, left.open.width)
        assertEquals(8, left.open.x)
        assertEquals(-500, left.hidden.x)

        val tooWide = EdgePanelConfig(side = EdgeSide.RIGHT, widthDp = 9_999).coerced()
        val right = DesktopEdgeGeometry.layout(m, tooWide.side, tooWide.widthDp)
        assertEquals(640, right.open.width)
        assertEquals(1920 - 8 - 640, right.open.x)
        assertEquals(1920, right.hidden.x)
    }

    @Test
    fun zoneReminderNamesTheEdgeWidthAndDwell() {
        val text = C.zoneReminder(EdgePanelConfig())
        assertTrue("4 px" in text)
        assertTrue("right edge" in text)
        assertTrue("whole height" in text)
        assertTrue("150 ms" in text)
        val partial = C.zoneReminder(
            EdgePanelConfig(side = EdgeSide.LEFT, zoneWidthPx = 6, segment = EdgeSegment(25, 75), monitor = EdgeMonitorChoice.PICK, pickedMonitor = 1),
        )
        assertTrue("6 px" in partial)
        assertTrue("left edge" in partial)
        assertTrue("25% to 75%" in partial)
        assertTrue("screen 2" in partial)
    }

    @Test
    fun shortcutIsCtrlShiftPOnly() {
        val s = DesktopEdgeShortcut
        assertTrue(s.matches(down = true, ctrl = true, shift = true, alt = false, isP = true))
        assertFalse(s.matches(down = false, ctrl = true, shift = true, alt = false, isP = true))
        assertFalse(s.matches(down = true, ctrl = false, shift = true, alt = false, isP = true))
        assertFalse(s.matches(down = true, ctrl = true, shift = false, alt = false, isP = true))
        assertFalse(s.matches(down = true, ctrl = true, shift = true, alt = true, isP = true))
        assertFalse(s.matches(down = true, ctrl = true, shift = true, alt = false, isP = false))
    }
}
