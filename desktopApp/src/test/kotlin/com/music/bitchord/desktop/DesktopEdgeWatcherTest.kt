package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The edge watcher over scripted pointer paths, with a fake clock. No screen, no sleeping. */
class DesktopEdgeWatcherTest {

    private class FakePointer : EdgePointerSource {
        var point: EdgePoint? = null
        var held = false
        var throwOnRead = false
        override fun position(): EdgePoint? {
            if (throwOnRead) error("pointer unavailable")
            return point
        }
        override fun buttonHeld(): Boolean = held
    }

    private class Rig(
        val monitorList: List<EdgeMonitor> = listOf(EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)),
        var config: EdgeWatchConfig = EdgeWatchConfig(),
    ) {
        val pointer = FakePointer()
        var now = 0L
        var mode = EdgeWatchMode.HIDDEN
        var fullscreen = false
        var fullscreenCalls = 0
        val events = ArrayList<EdgeEvent>()

        val watcher = DesktopEdgeWatcher(
            config = { config },
            mode = { mode },
            pointer = pointer,
            monitors = EdgeMonitorSource { monitorList },
            fullscreen = EdgeFullscreenProbe {
                fullscreenCalls++
                fullscreen
            },
            clock = EdgeClock { now },
            onEvent = { events += it },
        )

        /** Moves the cursor to [p] at time [ms] and polls once. Returns the suggested delay. */
        fun at(ms: Long, p: EdgePoint?): Long {
            now = ms
            pointer.point = p
            return watcher.tick()
        }

        val reveals: List<EdgeEvent.RevealRequested> get() = events.filterIsInstance<EdgeEvent.RevealRequested>()
        val samples: List<EdgeEvent.Sample> get() = events.filterIsInstance<EdgeEvent.Sample>()
    }

    private val edge = EdgePoint(1918, 500) // inside the 4 px right zone of a 1920 px monitor
    private val away = EdgePoint(900, 500)

    @Test
    fun brushingTheEdgeFor100msDoesNothing() {
        val rig = Rig()
        rig.at(0, edge)
        rig.at(50, edge)
        rig.at(100, away)
        rig.at(160, away)
        rig.at(240, away)
        assertTrue(rig.reveals.isEmpty())
    }

    @Test
    fun dwellingFor150msReveals() {
        val rig = Rig()
        rig.at(0, edge)
        rig.at(80, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.at(160, edge)
        assertEquals(1, rig.reveals.size)
        assertEquals(160L, rig.reveals[0].atMs)
        assertEquals(edge, rig.reveals[0].point)
        assertTrue(rig.reveals[0].monitor.primary)
    }

    @Test
    fun exactlyTheDwellTimeIsEnough() {
        val rig = Rig()
        rig.at(0, edge)
        rig.at(149, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.at(150, edge)
        assertEquals(1, rig.reveals.size)
    }

    @Test
    fun oneRevealPerEntryUntilTheCursorLeavesAndComesBack() {
        val rig = Rig()
        rig.at(0, edge)
        rig.at(200, edge)
        rig.at(300, edge)
        rig.at(400, edge)
        assertEquals(1, rig.reveals.size)
        rig.at(500, away)
        rig.at(600, edge)
        rig.at(800, edge)
        assertEquals(2, rig.reveals.size)
    }

    @Test
    fun configuredDwellIsHonouredAndClamped() {
        val rig = Rig(config = EdgeWatchConfig(dwellMs = 300))
        rig.at(0, edge)
        rig.at(250, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.at(300, edge)
        assertEquals(1, rig.reveals.size)

        val tiny = Rig(config = EdgeWatchConfig(dwellMs = 1)) // clamped up to 50
        tiny.at(0, edge)
        tiny.at(40, edge)
        assertTrue(tiny.reveals.isEmpty())
        tiny.at(50, edge)
        assertEquals(1, tiny.reveals.size)
    }

    @Test
    fun aHeldButtonBlocksAndRestartsTheDwell() {
        val rig = Rig()
        rig.pointer.held = true
        rig.at(0, edge)
        rig.at(200, edge)
        rig.at(500, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.pointer.held = false
        rig.at(600, edge) // dwell starts again here
        rig.at(700, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.at(760, edge)
        assertEquals(1, rig.reveals.size)
    }

    @Test
    fun fullscreenForegroundSuppressesTheRevealAndIsRecheckedSlowly() {
        val rig = Rig()
        rig.fullscreen = true
        rig.at(0, edge)
        rig.at(160, edge)
        rig.at(240, edge)
        rig.at(320, edge)
        rig.at(400, edge)
        assertTrue(rig.reveals.isEmpty())
        assertEquals(1, rig.fullscreenCalls) // not asked on every poll

        rig.fullscreen = false
        rig.at(600, edge) // 440 ms since the check: still the cached answer
        assertTrue(rig.reveals.isEmpty())
        rig.at(700, edge) // 540 ms since the check: asked again
        assertEquals(1, rig.reveals.size)
        assertEquals(2, rig.fullscreenCalls)
    }

    @Test
    fun fullscreenCheckCanBeSwitchedOff() {
        val rig = Rig(config = EdgeWatchConfig(suppressWhenFullscreen = false))
        rig.fullscreen = true
        rig.at(0, edge)
        rig.at(160, edge)
        assertEquals(1, rig.reveals.size)
        assertEquals(0, rig.fullscreenCalls)
    }

    @Test
    fun theFullscreenProbeIsNotAskedUntilARevealIsDue() {
        val rig = Rig()
        rig.at(0, away)
        rig.at(100, away)
        rig.at(200, edge)
        rig.at(250, edge)
        assertEquals(0, rig.fullscreenCalls)
    }

    @Test
    fun aSharedEdgeBetweenTwoMonitorsNeverReveals() {
        val a = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 0, 1.0, true)
        val b = EdgeMonitor.fromInsets(DisplayRect(1920, 0, 1280, 1024), 0, 0, 0, 0)
        val rig = Rig(monitorList = listOf(a, b))
        val shared = EdgePoint(1919, 500)
        rig.at(0, shared)
        rig.at(200, shared)
        rig.at(400, shared)
        assertTrue(rig.reveals.isEmpty())
        // but the outer edge of the second monitor works, and reports that monitor
        val outer = EdgePoint(3199, 500)
        rig.at(1000, outer)
        rig.at(1200, outer)
        assertEquals(1, rig.reveals.size)
        assertEquals(b, rig.reveals[0].monitor)
    }

    @Test
    fun leftSideAndSegmentAreHonoured() {
        val rig = Rig(config = EdgeWatchConfig(side = EdgeSide.LEFT, segment = EdgeSegment(40, 60)))
        rig.at(0, EdgePoint(1, 100)) // left edge, outside the 40..60 percent slice
        rig.at(300, EdgePoint(1, 100))
        assertTrue(rig.reveals.isEmpty())
        rig.at(400, EdgePoint(1, 540)) // inside the slice
        rig.at(600, EdgePoint(1, 540))
        assertEquals(1, rig.reveals.size)
    }

    @Test
    fun busyModeSendsSamplesAndNeverReveals() {
        val rig = Rig()
        rig.mode = EdgeWatchMode.BUSY
        rig.at(0, edge)
        rig.at(300, edge)
        rig.at(400, away)
        assertTrue(rig.reveals.isEmpty())
        assertEquals(3, rig.samples.size)
        assertTrue(rig.samples[0].inZone)
        assertFalse(rig.samples[2].inZone)
        assertEquals(away, rig.samples[2].point)
    }

    @Test
    fun hiddenModeSendsNoSamples() {
        val rig = Rig()
        rig.at(0, away)
        rig.at(100, away)
        assertTrue(rig.samples.isEmpty())
    }

    @Test
    fun samplesCarryTheButtonState() {
        val rig = Rig()
        rig.mode = EdgeWatchMode.BUSY
        rig.pointer.held = true
        rig.at(0, away)
        assertTrue(rig.samples.single().buttonHeld)
    }

    @Test
    fun afterBeingBusyTheCursorMustLeaveTheZoneBeforeANewReveal() {
        val rig = Rig()
        rig.mode = EdgeWatchMode.BUSY
        rig.at(0, edge)
        rig.mode = EdgeWatchMode.HIDDEN // the panel hid while the cursor sits on the edge
        rig.at(100, edge)
        rig.at(400, edge)
        rig.at(900, edge)
        assertTrue(rig.reveals.isEmpty())
        rig.at(1000, away)
        rig.at(1100, edge)
        rig.at(1300, edge)
        assertEquals(1, rig.reveals.size)
    }

    @Test
    fun noPointerMeansNoEventsAndNoCrash() {
        val rig = Rig()
        rig.at(0, null)
        rig.at(500, null)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun noMonitorsMeansNoEvents() {
        val rig = Rig(monitorList = emptyList())
        rig.at(0, edge)
        rig.at(500, edge)
        assertTrue(rig.reveals.isEmpty())
    }

    @Test
    fun aFailingPointerIsTreatedAsUnknown() {
        val rig = Rig()
        rig.pointer.throwOnRead = true
        rig.at(0, edge)
        rig.at(500, edge)
        assertTrue(rig.events.isEmpty())
    }

    // ---- poll policy ----

    @Test
    fun pollRateIsAdaptive() {
        val hidden = EdgeWatchMode.HIDDEN
        val busy = EdgeWatchMode.BUSY
        assertEquals(80L, EdgePollPolicy.nextDelayMs(hidden, false, 0L, false))
        assertEquals(80L, EdgePollPolicy.nextDelayMs(hidden, false, 4_999L, false))
        assertEquals(150L, EdgePollPolicy.nextDelayMs(hidden, false, 5_000L, false))
        assertEquals(40L, EdgePollPolicy.nextDelayMs(hidden, true, 9_000L, false)) // dwell pending
        assertEquals(40L, EdgePollPolicy.nextDelayMs(busy, false, 9_000L, false))
        assertEquals(150L, EdgePollPolicy.nextDelayMs(hidden, false, 0L, true)) // low power
        assertEquals(40L, EdgePollPolicy.nextDelayMs(busy, false, 0L, true))
    }

    @Test
    fun tickReturnsTheAdaptiveDelay() {
        val rig = Rig()
        assertEquals(80L, rig.at(0, away)) // just moved
        assertEquals(80L, rig.at(1_000, away))
        assertEquals(80L, rig.at(4_900, away))
        assertEquals(150L, rig.at(5_100, away)) // five quiet seconds
        assertEquals(80L, rig.at(5_200, EdgePoint(901, 500))) // moved: back to normal
        assertEquals(40L, rig.at(5_300, edge)) // in the zone: dwell pending
    }

    @Test
    fun lowPowerPollsSlowlyUntilTheCursorIsInTheZone() {
        val rig = Rig(config = EdgeWatchConfig(lowPower = true))
        assertEquals(150L, rig.at(0, away))
        assertEquals(150L, rig.at(150, EdgePoint(10, 10)))
        assertEquals(40L, rig.at(300, edge))
    }

    // ---- config and the fullscreen rule ----

    @Test
    fun configIsCoercedIntoRange() {
        val c = EdgeWatchConfig(zoneWidthPx = 50, dwellMs = 5_000, segment = EdgeSegment(90, 10)).coerced()
        assertEquals(12, c.zoneWidthPx)
        assertEquals(400L, c.dwellMs)
        assertEquals(EdgeSegment.FULL, c.segment)
        assertEquals(2, EdgeWatchConfig(zoneWidthPx = 0).coerced().zoneWidthPx)
        assertEquals(50L, EdgeWatchConfig(dwellMs = -3).coerced().dwellMs)
    }

    @Test
    fun aWindowCoversAMonitorOnlyWhenItContainsAllOfIt() {
        val screen = DisplayRect(0, 0, 1920, 1080)
        assertTrue(EdgeFullscreen.covers(DisplayRect(0, 0, 1920, 1080), screen))
        assertTrue(EdgeFullscreen.covers(DisplayRect(-8, -8, 1936, 1096), screen)) // frame overshoot
        assertFalse(EdgeFullscreen.covers(DisplayRect(0, 0, 1920, 1040), screen)) // maximised, taskbar visible
        assertFalse(EdgeFullscreen.covers(DisplayRect(0, 1, 1920, 1079), screen))
        assertFalse(EdgeFullscreen.covers(DisplayRect(100, 100, 800, 600), screen))
        assertFalse(EdgeFullscreen.covers(DisplayRect(0, 0, 0, 0), screen))
    }

    @Test
    fun fullscreenNeedsNoCaption() { // UI U4
        val screen = DisplayRect(0, 0, 1920, 1080)
        val covering = DisplayRect(-8, -8, 1936, 1096)
        val captioned = 0x00CF0000 // WS_OVERLAPPEDWINDOW: a normal maximised window
        val borderless = 0x14000000 // WS_POPUP or WS_VISIBLE
        // Maximised Chrome with an auto-hide taskbar covers the monitor but keeps its caption.
        assertFalse(EdgeFullscreen.isFullscreen(EdgeFullscreen.covers(covering, screen), captioned))
        // A borderless window covering the monitor is fullscreen.
        assertTrue(EdgeFullscreen.isFullscreen(EdgeFullscreen.covers(covering, screen), borderless))
        // No caption but not covering (a small popup) is not fullscreen.
        assertFalse(EdgeFullscreen.isFullscreen(EdgeFullscreen.covers(DisplayRect(100, 100, 800, 600), screen), borderless))
        assertTrue(EdgeFullscreen.hasCaption(captioned))
        assertFalse(EdgeFullscreen.hasCaption(0x00800000)) // WS_BORDER alone is not a caption
    }

    @Test
    fun desktopAndTaskbarWindowsAreNeverFullscreenApps() {
        assertTrue(EdgeFullscreen.isShellClass("Progman"))
        assertTrue(EdgeFullscreen.isShellClass("WorkerW"))
        assertTrue(EdgeFullscreen.isShellClass("Shell_TrayWnd"))
        assertFalse(EdgeFullscreen.isShellClass("Chrome_WidgetWin_1"))
    }

    @Test
    fun theWindowsProbeFailsClosedWithoutUser32() {
        assertFalse(WindowsFullscreenProbe(api = null).fullscreenForeground())
        assertFalse(EdgeButtons.anyDown(api = null))
    }

    @Test
    fun startAndStopTheLoop() {
        val rig = Rig()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            assertFalse(rig.watcher.isRunning)
            rig.watcher.start(scope)
            assertTrue(rig.watcher.isRunning)
            rig.watcher.start(scope) // second start is a no-op
            rig.watcher.stop()
            assertFalse(rig.watcher.isRunning)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
