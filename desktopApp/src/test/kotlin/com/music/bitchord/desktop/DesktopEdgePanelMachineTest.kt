package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The side panel state machine, driven directly (no watcher, no window, no sleeping). */
class DesktopEdgePanelMachineTest {

    private val monitor = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
    private val open = DesktopEdgeGeometry.layout(monitor, EdgeSide.RIGHT, 420).open

    private class Rig(open: DisplayRect, var delayMs: Long = 700L) {
        val commands = ArrayList<EdgeCommand>()
        val views = ArrayList<EdgeView>()
        var rect: DisplayRect? = open
        val machine = EdgePanelMachine(
            closeDelayMs = { delayMs },
            panelRect = { rect },
            onCommand = { commands += it },
            onView = { views += it },
        )
    }

    private fun rig(delayMs: Long = 700L) = Rig(open, delayMs).also { it.machine.enter() }

    private fun sample(t: Long, x: Int, y: Int, held: Boolean = false) =
        EdgeEvent.Sample(t, EdgePoint(x, y), held, monitor, false)

    private val inside = EdgePoint(1600, 500)
    private val away = EdgePoint(500, 500)

    private fun Rig.near(t: Long) = machine.onSample(sample(t, inside.x, inside.y))
    private fun Rig.far(t: Long, held: Boolean = false) = machine.onSample(sample(t, away.x, away.y, held))

    /** A rig whose panel is open (PEEK). */
    private fun peeking(): Rig {
        val r = rig()
        assertTrue(r.machine.onReveal())
        r.machine.onSlideFinished(opening = true)
        r.commands.clear()
        return r
    }

    @Test
    fun enterStartsHiddenAndWatchModeIsHidden() {
        val r = rig()
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
        assertEquals(EdgeWatchMode.HIDDEN, r.machine.watchMode())
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun beforeEnterNothingHappens() {
        val r = Rig(open)
        assertFalse(r.machine.onReveal())
        assertFalse(r.machine.showNow())
        assertEquals(EdgeWatchMode.HIDDEN, r.machine.watchMode())
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
    }

    @Test
    fun revealSlidesInThenPeeks() {
        val r = rig()
        assertTrue(r.machine.onReveal())
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_IN), r.commands)
        assertEquals(EdgeWatchMode.BUSY, r.machine.watchMode())
        r.machine.onSlideFinished(opening = true)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        assertFalse(r.machine.view.value.activated)
    }

    @Test
    fun secondRevealWhileOpenIsIgnored() {
        val r = peeking()
        assertFalse(r.machine.onReveal())
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun clickInsideActivatesAndOnlyThenTheWindowIsFocusable() {
        val r = peeking()
        assertFalse(EdgeWindowFlags.of(true, r.machine.view.value, false).focusable)
        r.machine.onClickInside()
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
        assertTrue(r.machine.view.value.activated)
        assertTrue(EdgeWindowFlags.of(true, r.machine.view.value, false).focusable)
    }

    @Test
    fun clickWhileRevealingActivatesWhenTheSlideEnds() {
        val r = rig()
        r.machine.onReveal()
        r.machine.onClickInside()
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
        r.machine.onSlideFinished(opening = true)
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
    }

    @Test
    fun awayForTheCloseDelayHides() {
        val r = peeking()
        r.far(1_000)
        r.far(1_650)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.far(1_700)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_OUT), r.commands)
    }

    @Test
    fun closeDelayIsTheConfiguredOne() {
        val r = rig(delayMs = 1_500L)
        r.machine.onReveal()
        r.machine.onSlideFinished(true)
        r.far(0)
        r.far(1_400)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.far(1_500)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun returningBeforeTheDelayCancelsAndTheClockRestarts() {
        val r = peeking()
        r.far(1_000)
        r.far(1_400)
        r.near(1_450)
        r.far(1_500)
        r.far(2_100) // 600 ms since the second departure
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.far(2_200) // 700 ms
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun heldMouseButtonBlocksAndRestartsTheClock() {
        val r = peeking()
        r.far(0)
        r.far(600)
        r.far(5_000, held = true)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.far(5_100)
        r.far(5_700)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.far(5_800)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun anOpenDialogOrPopupBlocksHiding() {
        val r = peeking()
        r.machine.setDialogOpen(true)
        r.far(0)
        r.far(10_000)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.machine.setDialogOpen(false)
        r.far(10_100)
        r.far(10_700)
        assertEquals(EdgePanelState.PEEK, r.machine.state) // the clock restarted when the dialog closed
        r.far(10_800)
        assertEquals(EdgePanelState.HIDING, r.machine.state)

        val p = peeking()
        p.machine.setPopupOpen(true)
        p.far(0)
        p.far(10_000)
        assertEquals(EdgePanelState.PEEK, p.machine.state)
    }

    @Test
    fun recentTypingDelaysClosingByFourSeconds() {
        val r = peeking()
        r.machine.onClickInside()
        r.machine.onTyped(1_000)
        r.far(1_100)
        r.far(2_000) // away long enough, but typed 1 s ago
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
        r.far(4_900)
        assertEquals(EdgePanelState.ACTIVE, r.machine.state) // 3.9 s since the last key
        r.far(5_000)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun anOldKeystrokeDoesNotDelayAnything() {
        val r = peeking()
        r.machine.onTyped(0)
        r.far(10_000)
        r.far(10_700)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun pinnedNeverHidesAndUnpinningStartsTheClockAgain() {
        val r = peeking()
        r.machine.setPinned(true)
        assertEquals(EdgePanelState.PINNED, r.machine.state)
        r.far(0)
        r.far(60_000)
        assertEquals(EdgePanelState.PINNED, r.machine.state)
        r.machine.setPinned(false)
        assertEquals(EdgePanelState.ACTIVE, r.machine.state) // pinning activated it
        r.far(60_100)
        r.far(60_700)
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
        r.far(60_800)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun pinStaysAcrossAHideAndTheNextRevealIsPinned() {
        val r = peeking()
        r.machine.setPinned(true)
        r.machine.hideNow()
        r.machine.onSlideFinished(opening = false)
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
        assertTrue(r.machine.view.value.pinned)
        r.machine.onReveal()
        r.machine.onSlideFinished(opening = true)
        assertEquals(EdgePanelState.PINNED, r.machine.state)
        assertFalse(r.machine.view.value.activated) // a hover reveal is still not focusable
    }

    @Test
    fun hideNowSlidesOutEvenWithTheCursorOnThePanelAndItDoesNotComeBack() {
        val r = peeking()
        r.machine.hideNow()
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_OUT), r.commands)
        r.near(100) // cursor still on the panel
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        r.far(200) // it leaves...
        r.near(300) // ...and comes back: that cancels the hide
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_OUT, EdgeCommand.SLIDE_IN), r.commands)
    }

    @Test
    fun cursorComingBackDuringAnAutomaticHideTurnsTheSlideRound() {
        val r = peeking()
        r.far(0)
        r.far(700)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        r.near(750)
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_OUT, EdgeCommand.SLIDE_IN), r.commands)
        r.machine.onSlideFinished(opening = true)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
    }

    @Test
    fun aStaleSlideFinishIsIgnored() {
        val r = rig()
        r.machine.onReveal()
        r.machine.hideNow() // overtakes the slide in
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        r.machine.onSlideFinished(opening = true) // the old slide ended after the new command
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        r.machine.onSlideFinished(opening = false)
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
        assertEquals(EdgeCommand.PARK, r.commands.last())
        assertEquals(EdgeWatchMode.HIDDEN, r.machine.watchMode())
    }

    @Test
    fun theCursorLeavingWhileStillRevealingHidesAfterTheDelay() {
        val r = rig()
        r.machine.onReveal()
        r.far(0)
        r.far(700)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun anUnknownCursorOrNoGeometryNeverCloses() {
        val r = peeking()
        r.machine.onSample(EdgeEvent.Sample(0, null, false, null, false))
        r.machine.onSample(EdgeEvent.Sample(60_000, null, false, null, false))
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.rect = null
        r.far(61_000)
        r.far(120_000)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
    }

    @Test
    fun theCursorInsideTheWindowKeepsItOpenWhateverTheGeometrySays() {
        val r = peeking()
        r.machine.setPointerInWindow(true)
        r.far(0)
        r.far(60_000)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.machine.setPointerInWindow(false)
        r.far(60_100)
        r.far(60_800)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun marginAroundThePanelCountsAsNear() {
        val r = peeking()
        val justOutside = EdgePoint(open.x - 23, open.y + 10)
        r.machine.onSample(sample(0, justOutside.x, justOutside.y))
        r.machine.onSample(sample(5_000, justOutside.x, justOutside.y))
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        val tooFar = EdgePoint(open.x - 25, open.y + 10)
        r.machine.onSample(sample(6_000, tooFar.x, tooFar.y))
        r.machine.onSample(sample(6_700, tooFar.x, tooFar.y))
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun focusLostFallsBackToPeekOnlyWhenSafe() {
        val r = peeking()
        r.machine.onClickInside()
        r.machine.onFocusGained()
        r.machine.setDialogOpen(true)
        r.machine.onFocusLost()
        assertEquals(EdgePanelState.ACTIVE, r.machine.state) // a dialog took the focus: stay

        r.machine.setDialogOpen(false)
        r.machine.onFocusGained()
        r.machine.onFocusLost()
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        assertFalse(r.machine.view.value.activated)
    }

    @Test
    fun focusLostWithoutEverGainingItChangesNothing() {
        val r = peeking()
        r.machine.onClickInside()
        r.machine.onFocusLost()
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
    }

    @Test
    fun showNowOpensActivatedFromHiddenOrHiding() {
        val r = rig()
        assertTrue(r.machine.showNow())
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
        assertTrue(r.machine.view.value.activated)
        r.machine.onSlideFinished(true)
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
        r.machine.hideNow()
        assertTrue(r.machine.showNow()) // turns the hide round
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
    }

    @Test
    fun showNowOnAPeekingPanelActivatesIt() {
        val r = peeking()
        r.machine.showNow()
        assertEquals(EdgePanelState.ACTIVE, r.machine.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun leavingResetsEverythingAndUnpins() {
        val r = peeking()
        r.machine.setPinned(true)
        r.machine.leave()
        assertEquals(EdgeView(), r.machine.view.value)
        assertFalse(r.machine.isEnabled)
        assertEquals(EdgeWatchMode.HIDDEN, r.machine.watchMode())
        assertFalse(r.machine.onReveal())
    }

    @Test
    fun startPinnedFromTheSettingMakesTheFirstRevealPinned() {
        val r = Rig(open)
        r.machine.enter(startPinned = true)
        r.machine.onReveal()
        r.machine.onSlideFinished(true)
        assertEquals(EdgePanelState.PINNED, r.machine.state)
    }

    @Test
    fun everyChangeIsPublishedToTheListener() {
        val r = rig()
        val before = r.views.size
        r.machine.onReveal()
        r.machine.onSlideFinished(true)
        assertEquals(before + 2, r.views.size)
        assertEquals(EdgePanelState.PEEK, r.views.last().state)
    }

    // ---- the pure pieces around it ----------------------------------------------------------

    @Test
    fun windowFlagsWithTheModeOffAreTodaysWindow() {
        val flags = EdgeWindowFlags.of(false, EdgeView(EdgePanelState.ACTIVE, pinned = true, activated = true), true)
        assertEquals(EdgeWindowFlags(shown = true, alwaysOnTop = false, focusable = true, resizable = true), flags)
        assertEquals(EdgeWindowFlags.NORMAL, flags)
    }

    @Test
    fun windowFlagsPerState() {
        val hidden = EdgeWindowFlags.of(true, EdgeView(EdgePanelState.HIDDEN), warmup = false)
        assertFalse(hidden.shown)
        assertFalse(hidden.alwaysOnTop)
        assertFalse(hidden.resizable)

        val warm = EdgeWindowFlags.of(true, EdgeView(EdgePanelState.HIDDEN), warmup = true)
        assertTrue(warm.shown)
        assertFalse(warm.alwaysOnTop)

        val peek = EdgeWindowFlags.of(true, EdgeView(EdgePanelState.PEEK), warmup = false)
        assertTrue(peek.shown)
        assertTrue(peek.alwaysOnTop)
        assertFalse(peek.focusable)

        val active = EdgeWindowFlags.of(true, EdgeView(EdgePanelState.ACTIVE, activated = true), warmup = false)
        assertTrue(active.focusable)
        assertFalse(active.resizable)

        val hiding = EdgeWindowFlags.of(true, EdgeView(EdgePanelState.HIDING, activated = true), warmup = false)
        assertTrue(hiding.shown)
        assertTrue(hiding.alwaysOnTop)
    }

    @Test
    fun configCoercionKeepsEveryValueInItsRange() {
        val wild = EdgePanelConfig(
            widthDp = 5_000,
            pickedMonitor = -3,
            zoneWidthPx = 99,
            segment = EdgeSegment(80, 20),
            dwellMs = 1,
            closeDelayMs = 99_999,
        ).coerced()
        assertEquals(DesktopEdgeGeometry.MAX_WIDTH_DP, wild.widthDp)
        assertEquals(0, wild.pickedMonitor)
        assertEquals(DesktopEdgeGeometry.MAX_ZONE_PX, wild.zoneWidthPx)
        assertEquals(EdgeSegment.FULL, wild.segment)
        assertEquals(EdgeWatchConfig.MIN_DWELL_MS, wild.dwellMs)
        assertEquals(EdgePanelConfig.MAX_CLOSE_DELAY_MS, wild.closeDelayMs)
        val low = EdgePanelConfig(widthDp = 10, closeDelayMs = 1).coerced()
        assertEquals(DesktopEdgeGeometry.MIN_WIDTH_DP, low.widthDp)
        assertEquals(EdgePanelConfig.MIN_CLOSE_DELAY_MS, low.closeDelayMs)
        assertEquals(EdgePanelConfig(), EdgePanelConfig().coerced())
    }

    @Test
    fun configMapsToTheWatcherConfig() {
        val w = EdgePanelConfig(
            side = EdgeSide.LEFT,
            zoneWidthPx = 8,
            segment = EdgeSegment(20, 60),
            dwellMs = 200,
            suppressWhenFullscreen = false,
            lowPower = true,
        ).watchConfig()
        assertEquals(EdgeSide.LEFT, w.side)
        assertEquals(8, w.zoneWidthPx)
        assertEquals(EdgeSegment(20, 60), w.segment)
        assertEquals(200L, w.dwellMs)
        assertFalse(w.suppressWhenFullscreen)
        assertTrue(w.lowPower)
        assertTrue(w.requireOuterEdge)
    }

    @Test
    fun frameIntervalFollowsTheRefreshRate() {
        assertEquals(16L, EdgeFrame.intervalMs(0))
        assertEquals(16L, EdgeFrame.intervalMs(-1))
        assertEquals(17L, EdgeFrame.intervalMs(60))
        assertEquals(7L, EdgeFrame.intervalMs(144))
        assertEquals(6L, EdgeFrame.intervalMs(500))
        assertEquals(33L, EdgeFrame.intervalMs(10))
    }

    @Test
    fun restoreKeepsABoundsThatStillFits() {
        val primary = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val saved = DisplayRect(100, 80, 1220, 780)
        assertEquals(saved, EdgeRestore.target(saved, listOf(primary), primary, 1220, 780))
    }

    @Test
    fun restoreClampsIntoTheCurrentWorkArea() {
        val primary = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val saved = DisplayRect(1_500, 700, 1220, 780) // hangs off the right and bottom
        val r = assertNotNull(EdgeRestore.target(saved, listOf(primary), primary, 1220, 780))
        assertTrue(r.right <= primary.work.right)
        assertTrue(r.bottom <= primary.work.bottom)
        assertEquals(1220, r.width)
        assertEquals(780, r.height)
    }

    @Test
    fun restoreFollowsTheMonitorThatHoldsTheWindowAndHandlesNegativeX() {
        val left = EdgeMonitor.fromInsets(DisplayRect(-1280, 0, 1280, 1024), 0, 0, 0, 0, 1.0, false)
        val primary = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val saved = DisplayRect(-1_200, 100, 900, 700)
        val r = assertNotNull(EdgeRestore.target(saved, listOf(left, primary), primary, 1220, 780))
        assertTrue(r.x >= left.work.x && r.right <= left.work.right)
    }

    @Test
    fun restoreOfAMaximizedWindowCentresTheDefaultSize() {
        val primary = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val r = assertNotNull(EdgeRestore.target(null, listOf(primary), primary, 1220, 780))
        assertEquals(DisplayRect(350, 130, 1220, 780), r)
    }

    @Test
    fun restoreOfAWindowOnAMonitorThatIsGoneUsesTheNearestOne() {
        val primary = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val saved = DisplayRect(4_000, 100, 1220, 780) // the monitor it was on was unplugged
        val r = assertNotNull(EdgeRestore.target(saved, listOf(primary), primary, 1220, 780))
        assertTrue(r.right <= primary.work.right)
    }

    @Test
    fun restoreWithNoMonitorGivesNothing() {
        assertNull(EdgeRestore.target(null, emptyList(), null, 1220, 780))
    }
}
