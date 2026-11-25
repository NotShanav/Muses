package com.music.bitchord.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/*
 * EDGE 2: the side panel controller. The panel is the existing Compose window changing mode, never
 * a second window: it keeps the queue, the scroll position and the search text.
 *
 * Threads: the watcher calls in from its own thread; everything that touches the window is posted
 * to the AWT thread. The slide runs on a Swing timer that exists only while a slide is running.
 * With the mode off nothing here runs: no watcher, no timer, no listeners on the window except the
 * ones installed once by attach() (a focus listener and a key dispatcher that return at once).
 */
internal object DesktopEdgePanel {

    /** Everything Part 3 puts in Settings; Part 2 only reads it. Changing it re-docks a live panel. */
    val config = MutableStateFlow(EdgePanelConfig())

    private val _mode = MutableStateFlow(false)

    /** Whether the window is in side panel mode. */
    val mode: StateFlow<Boolean> = _mode
    val isPanelMode: Boolean get() = _mode.value

    private val _flags = MutableStateFlow(EdgeWindowFlags.NORMAL)

    /** What the `Window(...)` call in Main.kt reads. */
    val flags: StateFlow<EdgeWindowFlags> = _flags

    private val clock = EdgeClock.SYSTEM
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitorSource = AwtEdgeMonitorSource()

    @Volatile private var layout: EdgeLayout? = null
    @Volatile private var layoutMonitor: EdgeMonitor? = null
    @Volatile private var pendingMonitor: EdgeMonitor? = null
    @Volatile private var warmup = false
    @Volatile private var wantPanel = false
    @Volatile private var wantReveal = false

    // The fields below are touched on the AWT thread only.
    private var window: Window? = null
    private var windowState: WindowState? = null
    private var savedBounds: DisplayRect? = null
    private var wasMaximized = false
    private var parked = true
    private var slide: EdgeSlide? = null
    private var slideTimer: Timer? = null
    private var slideDeadlineMs = 0L
    private var awaitingSettle = false // REVEAL FIX hook: opening from off screen, window not yet stable
    private var settleTicks = 0
    private var watcher: DesktopEdgeWatcher? = null
    private var keyDispatcher: KeyEventDispatcher? = null
    private var focusListener: WindowFocusListener? = null

    private val machine = EdgePanelMachine(
        closeDelayMs = { config.value.coerced().closeDelayMs },
        panelRect = { layout?.open },
        onCommand = ::onCommand,
        onView = { refreshFlags() },
    )

    /** The panel's state, for the header and the tray. */
    val view: StateFlow<EdgeView> = machine.view

    init {
        // A live change of width, side or monitor re-docks the panel. Idle while nothing changes.
        scope.launch {
            config.drop(1).collect { post { if (_mode.value) redock() } }
        }
    }

    private fun refreshFlags() {
        _flags.value = EdgeWindowFlags.of(_mode.value, machine.view.value, warmup)
    }

    private fun post(block: () -> Unit) {
        val safe = { runCatching { block() }.onFailure { DesktopTrackLog.log("edge: ${it.message}") }; Unit }
        if (SwingUtilities.isEventDispatchThread()) safe() else SwingUtilities.invokeLater { safe() }
    }

    private fun rectOf(b: Rectangle) = DisplayRect(b.x, b.y, b.width, b.height)

    // ---- window ownership ---------------------------------------------------------------------

    /**
     * Called from Main.kt when the Compose window exists (and again after a rebuild of it). If the
     * mode was already on, or was asked for before the window existed, the panel is docked now.
     */
    fun attach(window: Window, state: WindowState) {
        post {
            this.window = window
            this.windowState = state
            installListeners(window)
            when {
                _mode.value -> redock(freshWindow = true)
                wantPanel -> {
                    wantPanel = false
                    enterNow(wantReveal)
                }
            }
        }
    }

    /** Called when that window is disposed; a newer window that already attached is left alone. */
    fun detach(window: Window) {
        post {
            if (this.window !== window) return@post
            removeListeners(window)
            stopTimer()
            slide = null
            this.window = null
            windowState = null
        }
    }

    private fun installListeners(w: Window) {
        removeListeners(w)
        val focus = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) = machine.onFocusGained()
            override fun windowLostFocus(e: WindowEvent?) = machine.onFocusLost()
        }
        w.addWindowFocusListener(focus)
        focusListener = focus
        val keys = KeyEventDispatcher { e ->
            if (_mode.value && e.id == KeyEvent.KEY_TYPED && w.isActive) machine.onTyped(clock.nowMs())
            false // never consumes a key
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        keyDispatcher = keys
    }

    private fun removeListeners(w: Window) {
        focusListener?.let { runCatching { w.removeWindowFocusListener(it) } }
        focusListener = null
        keyDispatcher?.let { runCatching { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) } }
        keyDispatcher = null
    }

    // ---- entering and leaving -----------------------------------------------------------------

    /**
     * Enters side panel mode: remembers the normal bounds and maximized state, docks the window
     * hidden at the chosen edge, and starts the edge watcher. With [reveal] the panel then slides
     * in once (for a button the owner just pressed); without it the panel stays hidden until the
     * cursor asks for it (for "start in side panel mode").
     */
    fun enterPanel(reveal: Boolean = false) {
        wantPanel = true
        wantReveal = reveal
        post {
            if (window != null) {
                wantPanel = false
                enterNow(reveal)
            }
        }
    }

    /** Leaves the mode: restores the old size, position and maximized state, and un-pins. */
    fun leavePanel() {
        wantPanel = false
        post { leaveNow() }
    }

    fun togglePanel(reveal: Boolean = true) {
        if (_mode.value) leavePanel() else enterPanel(reveal)
    }

    private fun enterNow(reveal: Boolean) {
        val w = window ?: return
        if (_mode.value) {
            if (reveal) machine.showNow(true)
            return
        }
        wasMaximized = DesktopWindowMode.maximized.value || windowState?.placement == WindowPlacement.Maximized
        savedBounds = if (wasMaximized) null else rectOf(w.bounds)
        DesktopTrackLog.log("edge: entering panel mode, saved=$savedBounds maximized=$wasMaximized")
        if (wasMaximized) {
            // The window must be floating before it can be moved; Main.kt pushes the placement on.
            DesktopWindowMode.toggleMaximized()
            scope.launch {
                repeat(MAXIMIZE_WAIT_STEPS) {
                    if (windowState?.placement == WindowPlacement.Floating) return@repeat
                    delay(MAXIMIZE_WAIT_MS)
                }
                post { dock(reveal) }
            }
        } else {
            dock(reveal)
        }
    }

    private fun selectMonitor(cfg: EdgePanelConfig, cursor: EdgePoint?): EdgeMonitor? =
        DesktopEdgeGeometry.selectMonitor(monitorSource.monitors(), cfg.monitor, cursor, cfg.pickedMonitor)

    private fun layoutFor(monitor: EdgeMonitor, cfg: EdgePanelConfig): EdgeLayout =
        DesktopEdgeGeometry.layout(monitor, cfg.side, cfg.widthDp)

    private fun monitorOfWindow(w: Window): EdgeMonitor? {
        val b = w.bounds
        return DesktopEdgeGeometry.monitorAt(
            monitorSource.monitors(),
            EdgePoint(b.x + b.width / 2, b.y + b.height / 2),
        )
    }

    private fun dock(reveal: Boolean) {
        val w = window ?: return
        val cfg = config.value.coerced()
        monitorSource.invalidate()
        val monitor = selectMonitor(cfg, AwtEdgePointerSource.position()) ?: monitorOfWindow(w)
        if (monitor == null) {
            DesktopTrackLog.log("edge: no monitor found; staying in the normal window")
            if (wasMaximized) {
                wasMaximized = false
                DesktopWindowMode.toggleMaximized()
            }
            return
        }
        val l = layoutFor(monitor, cfg)
        layout = l
        layoutMonitor = monitor
        parked = true
        warmup = true
        _mode.value = true
        machine.enter(startPinned = cfg.pinByDefault)
        refreshFlags()
        applyRect(w, l.hidden, monitor.scale, minimumToo = true)
        startWatcher()
        // The window is shown once off screen so Windows accepts the first real position (Bloom's lesson).
        scope.launch {
            delay(WARMUP_MS)
            post {
                warmup = false
                refreshFlags()
                window?.let { settleSize(it, l.hidden) } // UI U5 hook
                if (reveal) machine.showNow(true)
            }
        }
    }

    private fun leaveNow() {
        if (!_mode.value) return
        DesktopTrackLog.log("edge: leaving panel mode")
        watcher?.stop()
        watcher = null
        stopTimer()
        slide = null
        warmup = false
        _mode.value = false
        machine.leave() // publishes the normal flags: shown, not on top, focusable, resizable
        layout = null
        layoutMonitor = null
        pendingMonitor = null
        parked = true
        val w = window
        if (w != null) {
            monitorSource.invalidate()
            val monitors = monitorSource.monitors()
            val fallback = DesktopEdgeGeometry.selectMonitor(
                monitors,
                EdgeMonitorChoice.CURSOR,
                AwtEdgePointerSource.position(),
            )
            val scale = fallback?.scale ?: 1.0
            val target = EdgeRestore.target(
                saved = savedBounds,
                monitors = monitors,
                fallbackMonitor = fallback,
                defaultWidthPx = DesktopEdgeGeometry.dpToPx(DEFAULT_WINDOW_WIDTH_DP, scale),
                defaultHeightPx = DesktopEdgeGeometry.dpToPx(DEFAULT_WINDOW_HEIGHT_DP, scale),
            )
            if (target != null) {
                val owner = DesktopEdgeGeometry.monitorAt(
                    monitors,
                    EdgePoint(target.x + target.width / 2, target.y + target.height / 2),
                )
                val s = owner?.scale ?: scale
                owner?.let {
                    val min = DesktopDisplayGeometry.minimumSize(it.work, it.scale)
                    w.minimumSize = Dimension(min.width, min.height)
                }
                applyRect(w, target, s, minimumToo = false)
            }
        }
        savedBounds = null
        if (wasMaximized) {
            wasMaximized = false
            DesktopWindowMode.toggleMaximized()
        }
        // Expanding to the full window is a request to see the window.
        DesktopWindowVisibility.show()
    }

    /** Sets the window's bounds and tells Compose's state the same size in dp. */
    private fun applyRect(w: Window, r: DisplayRect, scale: Double, minimumToo: Boolean) {
        if (minimumToo) {
            val minHeight = DesktopEdgeGeometry.dpToPx(DesktopDisplayGeometry.NARROW_MIN_HEIGHT_DP.toInt(), scale)
            w.minimumSize = Dimension(r.width, r.height.coerceAtMost(minHeight))
        }
        w.setBounds(r.x, r.y, r.width, r.height)
        val s = if (scale.isFinite() && scale > 0.0) scale else 1.0
        windowState?.size = DpSize((r.width / s).dp, (r.height / s).dp)
    }

    /**
     * UI U5: the panel never goes through the normal window's repaint path (the display watcher
     * skips it in panel mode), so a window whose size drifted, or whose Compose canvas lagged behind
     * it, showed a flat strip under the content. Compare the real size with the layout's, assert the
     * bounds again when they differ, and always revalidate and repaint like a normal window does.
     */
    private fun settleSize(w: Window, expected: DisplayRect) {
        val b = w.bounds
        val actual = DisplayRect(b.x, b.y, b.width, b.height)
        if (EdgeSizeCheck.drifted(expected, actual)) {
            DesktopTrackLog.log("edge: size drift expected=${expected.width}x${expected.height} actual=${actual.width}x${actual.height}; asserting again")
            w.setBounds(w.x, w.y, expected.width, expected.height)
        }
        runCatching {
            w.invalidate()
            w.validate()
            w.repaint()
            DesktopWindowsFrame.refreshFrame()
        }
    }

    /** Puts the window where the current state says it belongs, after a display or config change. */
    private fun redock(freshWindow: Boolean = false) {
        val w = window ?: return
        val cfg = config.value.coerced()
        monitorSource.invalidate()
        val current = layoutMonitor
        val monitor = when {
            freshWindow && current != null -> DesktopEdgeGeometry.monitorAt(monitorSource.monitors(), centreOf(current))
            else -> null
        } ?: selectMonitor(cfg, AwtEdgePointerSource.position()) ?: monitorOfWindow(w) ?: return
        val l = layoutFor(monitor, cfg)
        layout = l
        layoutMonitor = monitor
        stopTimer()
        slide = null
        val state = machine.state
        val visible = state != EdgePanelState.HIDDEN && state != EdgePanelState.HIDING
        applyRect(w, if (visible) l.open else l.hidden, monitor.scale, minimumToo = true)
        parked = state == EdgePanelState.HIDDEN
        if (freshWindow && parked) {
            // A rebuilt window has never been shown: show it once off screen again.
            warmup = true
            refreshFlags()
            scope.launch {
                delay(WARMUP_MS)
                post { warmup = false; refreshFlags() }
            }
        }
        when (state) {
            EdgePanelState.REVEALING -> machine.onSlideFinished(opening = true)
            EdgePanelState.HIDING -> machine.onSlideFinished(opening = false)
            else -> Unit
        }
        if (_mode.value && watcher == null) startWatcher()
    }

    private fun centreOf(m: EdgeMonitor): EdgePoint =
        EdgePoint(m.bounds.x + m.bounds.width / 2, m.bounds.y + m.bounds.height / 2)

    /** The display changed under the window (rotation, scale, monitors): re-dock. Called by the display watcher. */
    fun onDisplayChanged() {
        post { if (_mode.value) redock() }
    }

    // ---- watcher ------------------------------------------------------------------------------

    private fun startWatcher() {
        watcher?.stop()
        watcher = DesktopEdgeWatcher(
            config = { config.value.coerced().watchConfig() },
            mode = { machine.watchMode() },
            pointer = AwtEdgePointerSource,
            monitors = monitorSource,
            fullscreen = WindowsFullscreenProbe(),
            clock = clock,
            onEvent = { onWatcherEvent(it) },
        ).also { it.start(scope) }
    }

    /** Runs on the watcher's thread. */
    private fun onWatcherEvent(event: EdgeEvent) {
        when (event) {
            is EdgeEvent.RevealRequested -> {
                // A window put away to the tray stays away: the edge does not bring it back.
                if (!DesktopWindowVisibility.visible.value) return
                val cfg = config.value.coerced()
                val target = selectMonitor(cfg, event.point) ?: return
                // A fixed monitor (primary / picked) only reacts on its own edge.
                if (cfg.monitor != EdgeMonitorChoice.CURSOR && target.bounds != event.monitor.bounds) return
                pendingMonitor = target
                machine.onReveal()
            }
            is EdgeEvent.Sample -> machine.onSample(event)
        }
    }

    // ---- commands and the slide ---------------------------------------------------------------

    /** Runs on whichever thread the machine was called from; hands over to the AWT thread. */
    private fun onCommand(command: EdgeCommand) {
        post {
            when (command) {
                EdgeCommand.SLIDE_IN -> beginSlide(opening = true)
                EdgeCommand.SLIDE_OUT -> beginSlide(opening = false)
                EdgeCommand.PARK -> park()
            }
        }
    }

    private fun beginSlide(opening: Boolean) {
        val w = window ?: return
        val cfg = config.value.coerced()
        val slideWasParked = opening && parked // REVEAL FIX hook
        if (opening && parked) {
            // From off screen: choose the monitor now (the cursor's, the primary, or the picked one).
            val monitor = pendingMonitor
                ?: selectMonitor(cfg, AwtEdgePointerSource.position())
                ?: layoutMonitor
                ?: return
            pendingMonitor = null
            val fresh = layoutFor(monitor, cfg)
            layout = fresh
            layoutMonitor = monitor
            applyRect(w, fresh.hidden, monitor.scale, minimumToo = true)
            parked = false
        }
        val l = layout ?: return
        val monitor = layoutMonitor
        val targetX = if (opening) l.openX else l.hiddenX
        // A window sliding past a monitor edge would show on the neighbour: snap on a shared edge.
        val sharedEdge = monitor != null && DesktopEdgeGeometry.hasNeighbourOnSide(
            monitorSource.monitors(),
            monitor,
            cfg.side,
            l.open.y + l.open.height / 2,
        )
        val snap = DesktopAppearanceSettings.reduceAnimation.value || sharedEdge
        val now = clock.nowMs()
        slide = DesktopEdgeGeometry.slideTo(
            currentX = w.x,
            targetX = targetX,
            openX = l.openX,
            hiddenX = l.hiddenX,
            nowMs = now,
            speed = cfg.speed,
            reduceAnimation = snap,
        )
        slideDeadlineMs = now + SHOW_WAIT_MS
        awaitingSettle = opening && (parked || !w.isShowing || slideWasParked) // REVEAL FIX hook
        settleTicks = 0
        startTimer(w)
        stepSlide() // the first frame at once; a snap finishes right here
    }

    private fun startTimer(w: Window) {
        if (slideTimer?.isRunning == true) return
        val hz = runCatching { w.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0 }.getOrDefault(0)
        val interval = EdgeFrame.intervalMs(hz).toInt()
        slideTimer = Timer(interval) { post { stepSlide() } }.apply {
            isRepeats = true
            isCoalesce = true
            start()
        }
    }

    private fun stopTimer() {
        slideTimer?.stop()
        slideTimer = null
    }

    /** One frame: the position comes from elapsed time, so dropped frames only make it coarser, never slower. */
    private fun stepSlide() {
        val w = window
        val s = slide
        val l = layout
        if (w == null || s == null || l == null) {
            stopTimer()
            return
        }
        val now = clock.nowMs()
        // The window may not be on screen yet (the Compose `visible` flag lands a frame later):
        // keep the slide at its start until it is, for at most SHOW_WAIT_MS.
        // REVEAL FIX hook: while waiting, the window is held OFF SCREEN at its final size. Before,
        // it stayed wherever Windows put the freshly shown window (on screen, wrong size) and was
        // only corrected after the slide ended, which was the visible jump.
        if (s.durationMs > 0L && now < slideDeadlineMs) {
            if (!w.isShowing) {
                holdOffscreen(w, l)
                slide = s.copy(fromX = if (s.opening) l.hiddenX else s.fromX, startMs = now)
                return
            }
            if (awaitingSettle) {
                settleTicks = if (holdOffscreen(w, l)) settleTicks + 1 else 0
                if (settleTicks < SETTLE_TICKS) {
                    slide = s.copy(fromX = l.hiddenX, startMs = now)
                    return
                }
            }
        }
        if (awaitingSettle) {
            awaitingSettle = false
            if (s.fromX != l.hiddenX && s.opening) slide = s.copy(fromX = l.hiddenX, startMs = now)
        }
        val s2 = slide ?: return
        val x = s2.xAt(now)
        if (w.x != x) w.setLocation(x, l.open.y)
        if (s2.isFinished(now)) {
            stopTimer()
            slide = null
            val rest = if (s2.opening) l.open else l.hidden
            // REVEAL FIX hook: the size was already right before the slide began, so only the place
            // is set here; settleSize re-asserts the size only if it really drifted.
            if (w.x != rest.x || w.y != rest.y) w.setLocation(rest.x, rest.y)
            settleSize(w, rest) // UI U5 hook
            machine.onSlideFinished(opening = s2.opening)
            if (s2.opening) grantFocus(w)
        }
    }

    /**
     * REVEAL FIX: puts the window at its hidden (off screen) rectangle and reports whether it was
     * ALREADY there with Compose's content the same size as the window. False means "changed
     * something, check again next frame"; the slide starts after SETTLE_TICKS true answers in a row.
     */
    private fun holdOffscreen(w: Window, l: EdgeLayout): Boolean {
        val h = l.hidden
        val b = w.bounds
        var ok = true
        if (b.x != h.x || b.y != h.y || kotlin.math.abs(b.width - h.width) > 1 || kotlin.math.abs(b.height - h.height) > 1) {
            w.setBounds(h.x, h.y, h.width, h.height)
            ok = false
        }
        val content = (w as? javax.swing.RootPaneContainer)?.contentPane
        if (content != null) {
            val ins = w.insets
            val cw = w.width - ins.left - ins.right
            val ch = w.height - ins.top - ins.bottom
            if (kotlin.math.abs(content.width - cw) > 1 || kotlin.math.abs(content.height - ch) > 1) {
                runCatching { w.invalidate(); w.validate() }
                ok = false
            }
        }
        return ok
    }

    private fun park() {
        stopTimer()
        slide = null
        parked = true
        pendingMonitor = null
        val w = window ?: return
        val l = layout ?: return
        w.setBounds(l.hidden.x, l.hidden.y, l.hidden.width, l.hidden.height)
        // `shown` is false now: the window is no longer visible, so Compose draws no frames.
    }

    // ---- what the UI calls --------------------------------------------------------------------

    /** Slides the panel in now (tray "Show panel now", a button, a shortcut). */
    fun showNow() {
        machine.showNow(true)
    }

    /** "Hide" in the panel header. */
    fun hideNow() {
        machine.hideNow()
    }

    fun setPinned(on: Boolean) {
        machine.setPinned(on)
    }

    fun togglePin() {
        machine.setPinned(!machine.view.value.pinned)
    }

    /** A press anywhere in the window: the first one inside the panel makes it focusable. */
    fun onPointerPress() {
        machine.onClickInside()
    }

    /** The pointer entered or left the Compose root. */
    fun onPointerInside(inside: Boolean) {
        machine.setPointerInWindow(inside)
    }

    /** The app has a dialog, sheet or sign-in card open: the panel stays. */
    fun setDialogOpen(open: Boolean) {
        machine.setDialogOpen(open)
    }

    /** A menu or popup is open: the panel stays. */
    fun setPopupOpen(open: Boolean) {
        machine.setPopupOpen(open)
    }

    /** The window was brought back from the tray while the mode is on: show the panel. */
    fun onWindowShownByTray() {
        if (_mode.value && machine.state == EdgePanelState.HIDDEN && !warmup) showNow()
    }

    /** Called once `focusable` has reached the window: gives an activated panel the keyboard. */
    fun grantFocus(w: Window) {
        if (!_mode.value || !machine.view.value.activated) return
        post { runCatching { w.requestFocus() } }
    }

    private const val WARMUP_MS = 250L
    private const val SHOW_WAIT_MS = 450L
    private const val SETTLE_TICKS = 3 // REVEAL FIX hook
    private const val MAXIMIZE_WAIT_STEPS = 30
    private const val MAXIMIZE_WAIT_MS = 30L
    private const val DEFAULT_WINDOW_WIDTH_DP = 1_220
    private const val DEFAULT_WINDOW_HEIGHT_DP = 780
}
