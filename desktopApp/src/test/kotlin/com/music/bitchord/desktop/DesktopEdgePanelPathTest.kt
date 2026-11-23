package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real edge watcher feeding the panel machine over scripted pointer paths, with a fake clock.
 * Nothing sleeps and nothing needs a screen: [Rig.run] advances the clock and polls the watcher.
 */
class DesktopEdgePanelPathTest {

    private class FakePointer : EdgePointerSource {
        var point: EdgePoint? = null
        var held = false
        override fun position(): EdgePoint? = point
        override fun buttonHeld(): Boolean = held
    }

    private class Rig {
        val monitor = EdgeMonitor.fromInsets(DisplayRect(0, 0, 1920, 1080), 0, 0, 0, 40, 1.0, true)
        val layout = DesktopEdgeGeometry.layout(monitor, EdgeSide.RIGHT, 420)
        val pointer = FakePointer()
        val commands = ArrayList<EdgeCommand>()
        var now = 0L
        var fullscreen = false

        val machine = EdgePanelMachine(
            closeDelayMs = { 700L },
            panelRect = { layout.open },
            onCommand = { commands += it },
        )

        val watcher = DesktopEdgeWatcher(
            config = { EdgeWatchConfig(suppressWhenFullscreen = true) },
            mode = { machine.watchMode() },
            pointer = pointer,
            monitors = EdgeMonitorSource { listOf(monitor) },
            fullscreen = EdgeFullscreenProbe { fullscreen },
            clock = EdgeClock { now },
            onEvent = { event ->
                when (event) {
                    is EdgeEvent.RevealRequested -> machine.onReveal()
                    is EdgeEvent.Sample -> machine.onSample(event)
                }
            },
        )

        init {
            machine.enter()
        }

        /** Polls every [stepMs] for [ms] of fake time, the pointer where it is. */
        fun run(ms: Long, stepMs: Long = 10L) {
            var spent = 0L
            while (spent < ms) {
                watcher.tick()
                now += stepMs
                spent += stepMs
            }
        }

        fun at(x: Int, y: Int) {
            pointer.point = EdgePoint(x, y)
        }

        /** Inside the 4 px zone at the right edge. */
        fun edge() = at(1919, 500)

        /** Inside the panel, away from the edge zone. */
        fun onPanel() = at(1600, 500)

        /** Far from the panel. */
        fun awayFromPanel() = at(500, 500)

        /** Brings the panel to PEEK with the cursor on it. */
        fun open() {
            edge()
            run(200)
            assertEquals(listOf(EdgeCommand.SLIDE_IN), commands)
            machine.onSlideFinished(opening = true)
            assertEquals(EdgePanelState.PEEK, machine.state)
            onPanel()
            run(100)
            commands.clear()
        }
    }

    @Test
    fun brushingTheEdgeFor100msRevealsNothing() {
        val r = Rig()
        r.edge()
        r.run(100)
        r.awayFromPanel()
        r.run(500)
        assertTrue(r.commands.isEmpty())
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
    }

    @Test
    fun dwellingFor150msReveals() {
        val r = Rig()
        r.edge()
        r.run(140)
        assertTrue(r.commands.isEmpty())
        r.run(30)
        assertEquals(listOf(EdgeCommand.SLIDE_IN), r.commands)
        assertEquals(EdgePanelState.REVEALING, r.machine.state)
    }

    @Test
    fun holdingTheCursorAtTheEdgeRevealsOnlyOnce() {
        val r = Rig()
        r.edge()
        r.run(3_000)
        assertEquals(listOf(EdgeCommand.SLIDE_IN), r.commands)
    }

    @Test
    fun leaving400msAndReturningDoesNotHide() {
        val r = Rig()
        r.open()
        r.awayFromPanel()
        r.run(400)
        r.onPanel()
        r.run(100)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.awayFromPanel()
        r.run(600) // the 700 ms start again from the second departure
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun leavingFor700msHides() {
        val r = Rig()
        r.open()
        r.awayFromPanel()
        r.run(800)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
        assertEquals(listOf(EdgeCommand.SLIDE_OUT), r.commands)
        assertEquals(EdgeWatchMode.BUSY, r.machine.watchMode()) // still sliding: samples keep coming
        r.machine.onSlideFinished(opening = false)
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
        assertEquals(EdgeWatchMode.HIDDEN, r.machine.watchMode())
        assertEquals(EdgeCommand.PARK, r.commands.last())
    }

    @Test
    fun aHeldMouseButtonKeepsItOpenAndTheClockRestartsOnRelease() {
        val r = Rig()
        r.open()
        r.awayFromPanel()
        r.run(500)
        r.pointer.held = true // dragging a window or a scrollbar
        r.run(3_000)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.pointer.held = false
        r.run(600)
        assertEquals(EdgePanelState.PEEK, r.machine.state)
        r.run(200)
        assertEquals(EdgePanelState.HIDING, r.machine.state)
    }

    @Test
    fun aPinnedPanelNeverHides() {
        val r = Rig()
        r.open()
        r.machine.setPinned(true)
        r.awayFromPanel()
        r.run(10_000)
        assertEquals(EdgePanelState.PINNED, r.machine.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun aFullscreenForegroundAppSuppressesTheReveal() {
        val r = Rig()
        r.fullscreen = true
        r.edge()
        r.run(1_000)
        assertTrue(r.commands.isEmpty())
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
        r.fullscreen = false // the check is repeated every 500 ms while the cursor dwells
        r.run(700)
        assertEquals(listOf(EdgeCommand.SLIDE_IN), r.commands)
    }

    @Test
    fun afterAHideTheCursorMustLeaveTheEdgeOnceBeforeItOpensAgain() {
        val r = Rig()
        r.open()
        r.machine.hideNow()
        r.commands.clear()
        r.edge() // the cursor happens to sit in the edge zone as the panel goes
        r.run(300)
        r.machine.onSlideFinished(opening = false)
        r.commands.clear()
        r.run(1_000)
        assertTrue(r.commands.isEmpty()) // no re-open loop
        r.awayFromPanel()
        r.run(100)
        r.edge()
        r.run(200)
        assertEquals(listOf(EdgeCommand.SLIDE_IN), r.commands)
    }

    @Test
    fun theWatcherIsQuietWhileTheModeIsOff() {
        val r = Rig()
        r.machine.leave()
        r.edge()
        r.run(1_000)
        // The controller stops the watcher in this case; if it did not, the machine still refuses.
        assertTrue(r.commands.isEmpty())
        assertEquals(EdgePanelState.HIDDEN, r.machine.state)
    }
}
