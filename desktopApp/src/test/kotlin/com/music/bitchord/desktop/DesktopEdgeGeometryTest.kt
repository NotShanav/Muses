package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The edge panel's geometry: docking, zones, monitors and slides. No screen needed. */
class DesktopEdgeGeometryTest {

    private val G = DesktopEdgeGeometry

    private fun monitor(
        x: Int, y: Int, w: Int, h: Int,
        left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0,
        scale: Double = 1.0, primary: Boolean = false,
    ) = EdgeMonitor.fromInsets(DisplayRect(x, y, w, h), left, top, right, bottom, scale, primary)

    // ---- docking ----

    @Test
    fun rightDockWithBottomTaskbar() {
        val m = monitor(0, 0, 1920, 1080, bottom = 40, primary = true)
        val layout = G.layout(m, EdgeSide.RIGHT, 420)
        assertEquals(DisplayRect(1920 - 8 - 420, 12, 420, 1040 - 24), layout.open)
        assertEquals(1920, layout.hidden.x)
        assertEquals(layout.open.y, layout.hidden.y)
        assertEquals(layout.open.width, layout.hidden.width)
    }

    @Test
    fun leftDockWithBottomTaskbar() {
        val m = monitor(0, 0, 1920, 1080, bottom = 40)
        val layout = G.layout(m, EdgeSide.LEFT, 420)
        assertEquals(8, layout.open.x)
        assertEquals(-420, layout.hidden.x)
    }

    @Test
    fun taskbarOnTheRightKeepsOpenPanelOffTheTaskbarButHidesBeyondTheScreenEdge() {
        val m = monitor(0, 0, 1920, 1080, right = 48)
        val layout = G.layout(m, EdgeSide.RIGHT, 420)
        assertEquals(1872 - 8 - 420, layout.open.x)
        assertEquals(1920, layout.hidden.x) // monitor edge, not the work edge: no sliver over the taskbar
    }

    @Test
    fun taskbarOnTheLeftShiftsTheLeftDock() {
        val m = monitor(0, 0, 1920, 1080, left = 48)
        val layout = G.layout(m, EdgeSide.LEFT, 420)
        assertEquals(48 + 8, layout.open.x)
        assertEquals(-420, layout.hidden.x)
    }

    @Test
    fun taskbarOnTopMovesTheTop() {
        val m = monitor(0, 0, 1920, 1080, top = 48)
        val layout = G.layout(m, EdgeSide.RIGHT, 420)
        assertEquals(48 + 12, layout.open.y)
        assertEquals(1080 - 48 - 24, layout.open.height)
    }

    @Test
    fun scaleTurnsDpIntoPixels() {
        val m = monitor(0, 0, 2880, 1620, scale = 1.5)
        val layout = G.layout(m, EdgeSide.RIGHT, 420)
        assertEquals(630, layout.open.width)
        assertEquals(18, layout.open.y)
        assertEquals(2880 - 12 - 630, layout.open.x)
    }

    @Test
    fun widthIsClampedToTheRangeAndToTheWorkArea() {
        val m = monitor(0, 0, 1920, 1080)
        assertEquals(340, G.layout(m, EdgeSide.RIGHT, 100).open.width)
        assertEquals(640, G.layout(m, EdgeSide.RIGHT, 900).open.width)
        val narrow = monitor(0, 0, 400, 800)
        assertEquals(400 - 16, G.layout(narrow, EdgeSide.RIGHT, 640).open.width)
    }

    @Test
    fun tinyWorkAreaStillGivesAUsableRectangle() {
        val m = monitor(0, 0, 300, 20)
        val layout = G.layout(m, EdgeSide.RIGHT, 420)
        assertTrue(layout.open.width >= 1 && layout.open.height >= 1)
    }

    @Test
    fun clampToDisplayPullsARectBackInside() {
        val m = monitor(0, 0, 1920, 1080, bottom = 40)
        val clamped = G.clampToDisplay(DisplayRect(1800, -50, 420, 1200), m)
        assertEquals(1920 - 420, clamped.x)
        assertEquals(0, clamped.y)
        assertEquals(1040, clamped.height)
    }

    // ---- monitors ----

    private val left = monitor(0, 0, 1920, 1080, primary = true)
    private val rightSmall = monitor(1920, 0, 1280, 1024)

    @Test
    fun monitorAtFindsTheContainingOrTheNearest() {
        val both = listOf(left, rightSmall)
        assertEquals(rightSmall, G.monitorAt(both, EdgePoint(2000, 500)))
        assertEquals(left, G.monitorAt(both, EdgePoint(1919, 500)))
        assertEquals(rightSmall, G.monitorAt(both, EdgePoint(2500, 1050))) // below the short monitor
        assertNull(G.monitorAt(emptyList(), EdgePoint(0, 0)))
    }

    @Test
    fun monitorLeftOfThePrimaryHasNegativeCoordinates() {
        val primary = monitor(0, 0, 1920, 1080, primary = true)
        val far = monitor(-1280, 100, 1280, 1024)
        val both = listOf(primary, far)
        assertEquals(far, G.monitorAt(both, EdgePoint(-1279, 300)))
        assertEquals(8 - 1280, G.layout(far, EdgeSide.LEFT, 420).open.x)
        assertEquals(-1280 - 420, G.layout(far, EdgeSide.LEFT, 420).hidden.x)
        // far's left edge is a real screen edge; primary's left edge is shared with far only where far is.
        assertTrue(G.inEdgeZone(EdgePoint(-1280, 300), far, EdgeSide.LEFT, 4, monitors = both))
        assertFalse(G.inEdgeZone(EdgePoint(0, 500), primary, EdgeSide.LEFT, 4, monitors = both))
        assertTrue(G.inEdgeZone(EdgePoint(0, 50), primary, EdgeSide.LEFT, 4, monitors = both))
    }

    @Test
    fun selectMonitorFollowsTheChoice() {
        val both = listOf(rightSmall, left)
        assertEquals(left, G.selectMonitor(both, EdgeMonitorChoice.PRIMARY, null))
        assertEquals(rightSmall, G.selectMonitor(both, EdgeMonitorChoice.CURSOR, EdgePoint(2000, 10)))
        assertEquals(left, G.selectMonitor(both, EdgeMonitorChoice.CURSOR, null)) // no cursor: primary
        assertEquals(left, G.selectMonitor(both, EdgeMonitorChoice.PICK, null, 0)) // ordered left to right
        assertEquals(rightSmall, G.selectMonitor(both, EdgeMonitorChoice.PICK, null, 1))
        assertEquals(left, G.selectMonitor(both, EdgeMonitorChoice.PICK, null, 9)) // out of range: primary
        assertNull(G.selectMonitor(emptyList(), EdgeMonitorChoice.PRIMARY, null))
    }

    // ---- edge zone ----

    @Test
    fun rightZoneIsTheLastFewPixels() {
        val m = monitor(0, 0, 1920, 1080)
        assertTrue(G.inEdgeZone(EdgePoint(1919, 500), m, EdgeSide.RIGHT, 4))
        assertTrue(G.inEdgeZone(EdgePoint(1916, 500), m, EdgeSide.RIGHT, 4))
        assertFalse(G.inEdgeZone(EdgePoint(1915, 500), m, EdgeSide.RIGHT, 4))
        assertFalse(G.inEdgeZone(EdgePoint(1920, 500), m, EdgeSide.RIGHT, 4)) // outside the monitor
    }

    @Test
    fun leftZoneIsTheFirstFewPixels() {
        val m = monitor(0, 0, 1920, 1080)
        assertTrue(G.inEdgeZone(EdgePoint(0, 0), m, EdgeSide.LEFT, 4))
        assertTrue(G.inEdgeZone(EdgePoint(3, 1079), m, EdgeSide.LEFT, 4))
        assertFalse(G.inEdgeZone(EdgePoint(4, 500), m, EdgeSide.LEFT, 4))
    }

    @Test
    fun zoneWidthIsClampedToTwoToTwelve() {
        val m = monitor(0, 0, 1920, 1080)
        assertFalse(G.inEdgeZone(EdgePoint(1917, 500), m, EdgeSide.RIGHT, 1)) // 1 becomes 2
        assertTrue(G.inEdgeZone(EdgePoint(1918, 500), m, EdgeSide.RIGHT, 1))
        assertTrue(G.inEdgeZone(EdgePoint(1908, 500), m, EdgeSide.RIGHT, 99)) // 99 becomes 12
        assertFalse(G.inEdgeZone(EdgePoint(1907, 500), m, EdgeSide.RIGHT, 99))
    }

    @Test
    fun verticalSegmentLimitsTheZone() {
        val m = monitor(0, 0, 1920, 1000)
        val seg = EdgeSegment(30, 70) // y 300 until 700
        assertFalse(G.inEdgeZone(EdgePoint(1919, 299), m, EdgeSide.RIGHT, 4, seg))
        assertTrue(G.inEdgeZone(EdgePoint(1919, 300), m, EdgeSide.RIGHT, 4, seg))
        assertTrue(G.inEdgeZone(EdgePoint(1919, 699), m, EdgeSide.RIGHT, 4, seg))
        assertFalse(G.inEdgeZone(EdgePoint(1919, 700), m, EdgeSide.RIGHT, 4, seg))
    }

    @Test
    fun invalidSegmentMeansTheFullEdge() {
        val m = monitor(0, 0, 1920, 1000)
        assertEquals(EdgeSegment.FULL, EdgeSegment(70, 30).normalized())
        assertEquals(EdgeSegment.FULL, EdgeSegment(-5, 120).normalized())
        assertTrue(G.inEdgeZone(EdgePoint(1919, 5), m, EdgeSide.RIGHT, 4, EdgeSegment(70, 30)))
    }

    @Test
    fun sharedEdgeBetweenMonitorsIsNotAScreenEdgeOnlyWhereTheNeighbourIs() {
        val both = listOf(left, rightSmall)
        // left's right edge at y=500: rightSmall (height 1024) is there, so not an edge.
        assertFalse(G.inEdgeZone(EdgePoint(1919, 500), left, EdgeSide.RIGHT, 4, monitors = both))
        // at y=1050 the neighbour has ended: a real edge.
        assertTrue(G.inEdgeZone(EdgePoint(1919, 1050), left, EdgeSide.RIGHT, 4, monitors = both))
        // the shared edge counts when the owner asks for it.
        assertTrue(G.inEdgeZone(EdgePoint(1919, 500), left, EdgeSide.RIGHT, 4, monitors = both, requireOuterEdge = false))
        // the far right edge of rightSmall is an outer edge.
        assertTrue(G.inEdgeZone(EdgePoint(3199, 500), rightSmall, EdgeSide.RIGHT, 4, monitors = both))
        // and rightSmall's left edge is shared with left.
        assertFalse(G.inEdgeZone(EdgePoint(1920, 500), rightSmall, EdgeSide.LEFT, 4, monitors = both))
    }

    @Test
    fun hasNeighbourOnSideMatchesTheProbe() {
        val both = listOf(left, rightSmall)
        assertTrue(G.hasNeighbourOnSide(both, left, EdgeSide.RIGHT, 100))
        assertFalse(G.hasNeighbourOnSide(both, left, EdgeSide.LEFT, 100))
        assertTrue(G.hasNeighbourOnSide(both, rightSmall, EdgeSide.LEFT, 100))
    }

    @Test
    fun panelPlusMarginHasExactBoundaries() {
        val panel = DisplayRect(1000, 100, 400, 800)
        assertTrue(G.insidePanelPlusMargin(EdgePoint(1000, 100), panel, 24))
        assertTrue(G.insidePanelPlusMargin(EdgePoint(976, 76), panel, 24))
        assertFalse(G.insidePanelPlusMargin(EdgePoint(975, 500), panel, 24))
        assertTrue(G.insidePanelPlusMargin(EdgePoint(1423, 500), panel, 24))
        assertFalse(G.insidePanelPlusMargin(EdgePoint(1424, 500), panel, 24))
        assertFalse(G.insidePanelPlusMargin(EdgePoint(1100, 924), panel, 24))
    }

    // ---- slides ----

    private val m1 = monitor(0, 0, 1920, 1080)
    private val layout = G.layout(m1, EdgeSide.RIGHT, 420) // open x 1492, hidden x 1920

    @Test
    fun openingSlideStartsAtHiddenEndsAtOpenAndNeverMovesBackwards() {
        val slide = G.slideTo(layout.hiddenX, layout.openX, layout.openX, layout.hiddenX, 1000L, EdgeSpeed.NORMAL)
        assertEquals(240L, slide.durationMs)
        assertEquals(layout.hiddenX, slide.xAt(1000L))
        assertEquals(layout.openX, slide.xAt(1240L))
        assertEquals(layout.openX, slide.xAt(5000L))
        assertTrue(slide.isFinished(1240L))
        var previous = slide.xAt(1000L)
        for (t in 1000L..1240L step 16L) {
            val x = slide.xAt(t)
            assertTrue(x <= previous, "x went backwards at $t: $x > $previous")
            previous = x
        }
    }

    @Test
    fun easingShapes() {
        // ease-out is front-loaded, ease-in is back-loaded; both meet at the end.
        val inSlide = G.slideTo(1920, 1492, 1492, 1920, 0L, EdgeSpeed.NORMAL)
        val outSlide = G.slideTo(1492, 1920, 1492, 1920, 0L, EdgeSpeed.NORMAL)
        val inTravelled = 1920 - inSlide.xAt(60L) // a quarter of the time
        val outTravelled = outSlide.xAt(50L) - 1492
        assertTrue(inTravelled > 428 / 2, "ease-out should have covered over half after a quarter: $inTravelled")
        assertTrue(outTravelled < 428 / 10, "ease-in should have covered under a tenth after a quarter: $outTravelled")
        assertEquals(0.875, G.easeOutCubic(0.5), 1e-9)
        assertEquals(0.125, G.easeInCubic(0.5), 1e-9)
    }

    @Test
    fun speedsSetTheDuration() {
        fun inMs(speed: EdgeSpeed) = G.slideTo(1920, 1492, 1492, 1920, 0L, speed).durationMs
        fun outMs(speed: EdgeSpeed) = G.slideTo(1492, 1920, 1492, 1920, 0L, speed).durationMs
        assertEquals(140L, inMs(EdgeSpeed.FAST))
        assertEquals(240L, inMs(EdgeSpeed.NORMAL))
        assertEquals(360L, inMs(EdgeSpeed.SLOW))
        assertEquals(200L, outMs(EdgeSpeed.NORMAL))
    }

    @Test
    fun reduceAnimationSnapsInOneStep() {
        val slide = G.slideTo(1920, 1492, 1492, 1920, 500L, EdgeSpeed.NORMAL, reduceAnimation = true)
        assertEquals(0L, slide.durationMs)
        assertTrue(slide.isFinished(500L))
        assertEquals(1492, slide.xAt(500L))
    }

    @Test
    fun slideToWhereItAlreadyIsTakesNoTime() {
        val slide = G.slideTo(1492, 1492, 1492, 1920, 0L, EdgeSpeed.NORMAL)
        assertEquals(0L, slide.durationMs)
        assertEquals(1492, slide.xAt(0L))
    }

    @Test
    fun interruptedSlideContinuesFromWhereItIs() {
        val opening = G.retarget(null, layout.hiddenX, layout.openX, layout, 0L, EdgeSpeed.NORMAL)
        val interruptAt = 80L
        val xNow = opening.xAt(interruptAt)
        assertTrue(xNow < layout.hiddenX && xNow > layout.openX)

        val closing = G.retarget(opening, layout.hiddenX, layout.hiddenX, layout, interruptAt, EdgeSpeed.NORMAL)
        assertEquals(xNow, closing.fromX)
        assertEquals(xNow, closing.xAt(interruptAt)) // no jump at the turn
        assertEquals(layout.hiddenX, closing.xAt(interruptAt + closing.durationMs))
        // It has less far to go than a full close, so it takes proportionally less.
        val expected = Math.round(200.0 * (layout.hiddenX - xNow) / (layout.hiddenX - layout.openX))
        assertEquals(expected, closing.durationMs)
        assertTrue(closing.durationMs in 1L until 200L)
        assertFalse(closing.opening)
    }

    @Test
    fun interruptedCloseThenReopenIsAlsoContinuous() {
        val closing = G.retarget(null, layout.openX, layout.hiddenX, layout, 0L, EdgeSpeed.NORMAL)
        val xNow = closing.xAt(150L)
        val reopening = G.retarget(closing, layout.openX, layout.openX, layout, 150L, EdgeSpeed.NORMAL)
        assertEquals(xNow, reopening.xAt(150L))
        assertEquals(layout.openX, reopening.xAt(150L + reopening.durationMs))
        assertNotNull(reopening)
    }

    @Test
    fun leftSideSlidesTheOtherWay() {
        val l = G.layout(m1, EdgeSide.LEFT, 420) // open 8, hidden -420
        val slide = G.slideTo(l.hiddenX, l.openX, l.openX, l.hiddenX, 0L, EdgeSpeed.NORMAL)
        assertEquals(-420, slide.xAt(0L))
        assertEquals(8, slide.xAt(240L))
        assertTrue(slide.xAt(100L) > -420)
    }

    @Test
    fun clockGoingBackwardsIsHarmless() {
        val slide = G.slideTo(1920, 1492, 1492, 1920, 1000L, EdgeSpeed.NORMAL)
        assertEquals(1920, slide.xAt(900L)) // before the start: still at the start
    }
}
