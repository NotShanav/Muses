package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * EDGE 2: the side panel's state machine and the small pure pieces around it. No AWT, no Compose,
 * no clock of its own (every call that needs the time gets it from the caller, or from the
 * watcher's sample), so the whole thing is tested with scripted pointer paths and a fake clock.
 *
 * The controller (DesktopEdgePanel) owns the window; this class only decides.
 */

internal enum class EdgePanelState { HIDDEN, REVEALING, PEEK, ACTIVE, PINNED, HIDING }

/**
 * What the outside world can read. [activated] means the owner clicked inside, so the window may
 * take keyboard focus; [pinned] is the sticky pin (it survives a hide, not leaving the mode).
 */
internal data class EdgeView(
    val state: EdgePanelState = EdgePanelState.HIDDEN,
    val pinned: Boolean = false,
    val activated: Boolean = false,
)

/** What the machine asks the controller to do with the window. */
internal enum class EdgeCommand {
    /** Start (or continue towards) the open position. */
    SLIDE_IN,

    /** Start (or continue towards) the hidden position. */
    SLIDE_OUT,

    /** The slide out is done: the window may leave the screen entirely. */
    PARK,
}

/** The settings the controller reads, in one place. Part 3 persists and edits it. */
internal data class EdgePanelConfig(
    val side: EdgeSide = EdgeSide.RIGHT,
    val widthDp: Int = DesktopEdgeGeometry.DEFAULT_WIDTH_DP,
    val monitor: EdgeMonitorChoice = EdgeMonitorChoice.CURSOR,
    val pickedMonitor: Int = 0,
    val zoneWidthPx: Int = DesktopEdgeGeometry.DEFAULT_ZONE_PX,
    val segment: EdgeSegment = EdgeSegment.FULL,
    val dwellMs: Long = EdgeWatchConfig.DEFAULT_DWELL_MS,
    val closeDelayMs: Long = DEFAULT_CLOSE_DELAY_MS,
    val speed: EdgeSpeed = EdgeSpeed.NORMAL,
    val suppressWhenFullscreen: Boolean = true,
    val lowPower: Boolean = false,
    val pinByDefault: Boolean = false,
) {
    /** The same config with every value forced into its allowed range. */
    fun coerced(): EdgePanelConfig = copy(
        widthDp = widthDp.coerceIn(DesktopEdgeGeometry.MIN_WIDTH_DP, DesktopEdgeGeometry.MAX_WIDTH_DP),
        pickedMonitor = pickedMonitor.coerceAtLeast(0),
        zoneWidthPx = DesktopEdgeGeometry.clampZonePx(zoneWidthPx),
        segment = segment.normalized(),
        dwellMs = dwellMs.coerceIn(EdgeWatchConfig.MIN_DWELL_MS, EdgeWatchConfig.MAX_DWELL_MS),
        closeDelayMs = closeDelayMs.coerceIn(MIN_CLOSE_DELAY_MS, MAX_CLOSE_DELAY_MS),
    )

    /** What the edge watcher needs from this config. */
    fun watchConfig(): EdgeWatchConfig = EdgeWatchConfig(
        side = side,
        zoneWidthPx = zoneWidthPx,
        segment = segment,
        dwellMs = dwellMs,
        requireOuterEdge = true,
        suppressWhenFullscreen = suppressWhenFullscreen,
        lowPower = lowPower,
    ).coerced()

    companion object {
        const val DEFAULT_CLOSE_DELAY_MS = 700L
        const val MIN_CLOSE_DELAY_MS = 300L
        const val MAX_CLOSE_DELAY_MS = 2_000L
    }
}

/**
 * What the Window call in Main.kt reads. With the mode off this is exactly today's window:
 * shown, not on top, focusable, resizable.
 */
internal data class EdgeWindowFlags(
    val shown: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val focusable: Boolean = true,
    val resizable: Boolean = true,
) {
    companion object {
        val NORMAL = EdgeWindowFlags()

        /**
         * [warmup] keeps a hidden window "shown" (off screen) for a moment after entering the mode:
         * Windows ignores positioning on a window that was never shown (Bloom's lesson).
         */
        fun of(panelMode: Boolean, view: EdgeView, warmup: Boolean): EdgeWindowFlags {
            if (!panelMode) return NORMAL
            val visible = view.state != EdgePanelState.HIDDEN
            return EdgeWindowFlags(
                shown = visible || warmup,
                alwaysOnTop = visible,
                // Focus only after the first click inside: a hover reveal never steals it.
                focusable = view.activated,
                resizable = false,
            )
        }
    }
}

/** How often a slide draws a frame. */
internal object EdgeFrame {
    const val DEFAULT_MS = 16L
    private const val MIN_MS = 6L
    private const val MAX_MS = 33L

    /** One frame at the monitor's refresh rate; an unknown rate (0 or less) means about 60 Hz. */
    fun intervalMs(refreshHz: Int): Long {
        if (refreshHz <= 0) return DEFAULT_MS
        return Math.round(1000.0 / refreshHz).coerceIn(MIN_MS, MAX_MS)
    }
}

/** Where the normal window goes when the mode is left. */
internal object EdgeRestore {

    /**
     * The rectangle to restore: [saved] (the bounds before the mode, null when the window was
     * maximized) moved and shrunk into the work area of the monitor that holds it, or a centred
     * default size on [fallbackMonitor]. Null only when there is no monitor at all.
     */
    fun target(
        saved: DisplayRect?,
        monitors: List<EdgeMonitor>,
        fallbackMonitor: EdgeMonitor?,
        defaultWidthPx: Int,
        defaultHeightPx: Int,
    ): DisplayRect? {
        val centre = saved?.let { EdgePoint(it.x + it.width / 2, it.y + it.height / 2) }
        val monitor = centre?.let { DesktopEdgeGeometry.monitorAt(monitors, it) }
            ?: fallbackMonitor
            ?: return null
        val minimum = DesktopDisplayGeometry.minimumSize(monitor.work, monitor.scale)
        val base = saved ?: DisplayRect(
            monitor.work.x + (monitor.work.width - defaultWidthPx) / 2,
            monitor.work.y + (monitor.work.height - defaultHeightPx) / 2,
            defaultWidthPx,
            defaultHeightPx,
        )
        return DesktopDisplayGeometry.clampToWorkArea(base, monitor.work, minimum.width, minimum.height)
    }
}

/**
 * HIDDEN / REVEALING / PEEK / ACTIVE / PINNED / HIDING.
 *
 * Inputs: the watcher's [onReveal] and [onSample], the window's [onClickInside], [onTyped],
 * [onFocusGained]/[onFocusLost], [setPointerInWindow], the app's [setDialogOpen]/[setPopupOpen],
 * the header's [setPinned]/[hideNow], and the controller's [onSlideFinished]. Output: [view] and
 * the commands. Every public method is synchronized because the watcher calls from its own thread.
 * [onCommand] is always the last statement of the method that sends it; the controller may answer
 * on the same thread (a snap slide finishes at once and calls [onSlideFinished], which is re-entrant).
 */
internal class EdgePanelMachine(
    private val closeDelayMs: () -> Long,
    private val panelRect: () -> DisplayRect?,
    private val onCommand: (EdgeCommand) -> Unit,
    private val onView: (EdgeView) -> Unit = {},
) {

    private val _view = MutableStateFlow(EdgeView())
    val view: StateFlow<EdgeView> = _view
    val state: EdgePanelState get() = _view.value.state

    private var enabled = false
    private var pinned = false
    private var activated = false
    private var awayStartMs = -1L
    private var lastTypedMs = -1L
    private var manualHide = false
    private var focusSeen = false

    @Volatile private var dialogOpen = false
    @Volatile private var popupOpen = false
    @Volatile private var pointerInWindow = false

    val isEnabled: Boolean get() = enabled

    /** What the watcher should be told: only a hidden panel is "HIDDEN"; every other state is BUSY. */
    fun watchMode(): EdgeWatchMode =
        if (enabled && state != EdgePanelState.HIDDEN) EdgeWatchMode.BUSY else EdgeWatchMode.HIDDEN

    private fun publish(next: EdgePanelState) {
        val v = EdgeView(next, pinned, activated)
        _view.value = v
        onView(v)
    }

    private fun resetTimers() {
        awayStartMs = -1L
        manualHide = false
    }

    // ---- mode ---------------------------------------------------------------------------------

    @Synchronized
    fun enter(startPinned: Boolean = false) {
        enabled = true
        pinned = startPinned
        activated = false
        lastTypedMs = -1L
        focusSeen = false
        resetTimers()
        publish(EdgePanelState.HIDDEN)
    }

    /** Leaving the mode un-pins and forgets everything. */
    @Synchronized
    fun leave() {
        enabled = false
        pinned = false
        activated = false
        lastTypedMs = -1L
        focusSeen = false
        resetTimers()
        publish(EdgePanelState.HIDDEN)
    }

    // ---- reveal -------------------------------------------------------------------------------

    /** The watcher's dwell was satisfied. Returns whether a reveal started. */
    @Synchronized
    fun onReveal(): Boolean {
        if (!enabled || state != EdgePanelState.HIDDEN) return false
        activated = false
        resetTimers()
        publish(EdgePanelState.REVEALING)
        onCommand(EdgeCommand.SLIDE_IN)
        return true
    }

    /**
     * Opens the panel without the mouse (tray, a button, the keyboard). With [activate] it may
     * take keyboard focus straight away. Returns false when the mode is off.
     */
    @Synchronized
    fun showNow(activate: Boolean = true): Boolean {
        if (!enabled) return false
        when (state) {
            EdgePanelState.HIDDEN, EdgePanelState.HIDING -> {
                activated = activate
                resetTimers()
                publish(EdgePanelState.REVEALING)
                onCommand(EdgeCommand.SLIDE_IN)
            }
            else -> if (activate && !activated) {
                activated = true
                publish(if (state == EdgePanelState.PEEK) EdgePanelState.ACTIVE else state)
            }
        }
        return true
    }

    // ---- pointer ------------------------------------------------------------------------------

    private fun cursorNear(sample: EdgeEvent.Sample): Boolean {
        if (pointerInWindow) return true
        val p = sample.point ?: return true // an unknown cursor never closes the panel
        val rect = panelRect() ?: return true // no geometry yet: stay open
        return DesktopEdgeGeometry.insidePanelPlusMargin(p, rect)
    }

    private fun startHide() {
        awayStartMs = -1L
        publish(EdgePanelState.HIDING)
        onCommand(EdgeCommand.SLIDE_OUT)
    }

    /** One pointer reading from the watcher (sent only while the panel is not HIDDEN). */
    @Synchronized
    fun onSample(sample: EdgeEvent.Sample) {
        if (!enabled) return
        val near = cursorNear(sample)
        when (state) {
            EdgePanelState.HIDDEN -> Unit
            EdgePanelState.PINNED -> awayStartMs = -1L
            EdgePanelState.HIDING -> {
                if (!near) {
                    manualHide = false
                } else if (!manualHide) {
                    // The cursor came back before the panel was gone: turn round from where it is.
                    publish(EdgePanelState.REVEALING)
                    onCommand(EdgeCommand.SLIDE_IN)
                }
            }
            else -> { // REVEALING, PEEK, ACTIVE
                if (near || sample.buttonHeld || dialogOpen || popupOpen) {
                    awayStartMs = -1L
                    return
                }
                if (awayStartMs < 0L) awayStartMs = sample.atMs
                val typing = lastTypedMs >= 0L && sample.atMs - lastTypedMs < TYPING_EXTRA_MS
                if (sample.atMs - awayStartMs >= closeDelayMs() && !typing) startHide()
            }
        }
    }

    /** The owner pressed inside the window: from now on it may take keyboard focus. */
    @Synchronized
    fun onClickInside() {
        if (!enabled || activated) return
        if (state == EdgePanelState.HIDDEN || state == EdgePanelState.HIDING) return
        activated = true
        publish(if (state == EdgePanelState.PEEK) EdgePanelState.ACTIVE else state)
    }

    /** A character was typed in the window; closing waits until [TYPING_EXTRA_MS] after the last one. */
    @Synchronized
    fun onTyped(nowMs: Long) {
        if (enabled) lastTypedMs = nowMs
    }

    @Synchronized
    fun onFocusGained() {
        focusSeen = true
    }

    /** Focus went elsewhere: an ACTIVE panel (not pinned, no dialog, cursor away) falls back to PEEK. */
    @Synchronized
    fun onFocusLost() {
        if (!enabled) return
        if (focusSeen && state == EdgePanelState.ACTIVE && !dialogOpen && !popupOpen && !pointerInWindow) {
            activated = false
            publish(EdgePanelState.PEEK)
        }
        focusSeen = false
    }

    fun setPointerInWindow(inside: Boolean) {
        pointerInWindow = inside
    }

    fun setDialogOpen(open: Boolean) {
        dialogOpen = open
    }

    fun setPopupOpen(open: Boolean) {
        popupOpen = open
    }

    // ---- header buttons -----------------------------------------------------------------------

    /**
     * The pin. Sticky: it survives a hide (the next reveal stays open) and ends only when
     * unpinned or when the mode is left.
     */
    @Synchronized
    fun setPinned(on: Boolean) {
        if (!enabled) return
        pinned = on
        awayStartMs = -1L
        val next = when (state) {
            EdgePanelState.PEEK, EdgePanelState.ACTIVE ->
                if (on) EdgePanelState.PINNED else state
            EdgePanelState.PINNED ->
                if (on) EdgePanelState.PINNED else if (activated) EdgePanelState.ACTIVE else EdgePanelState.PEEK
            else -> state
        }
        if (on && (next == EdgePanelState.PINNED || next == EdgePanelState.REVEALING)) activated = true
        publish(next)
    }

    /** "Hide" in the header: slide out now, even when pinned. The cursor on the panel does not bring it back. */
    @Synchronized
    fun hideNow() {
        if (!enabled) return
        when (state) {
            EdgePanelState.REVEALING, EdgePanelState.PEEK, EdgePanelState.ACTIVE, EdgePanelState.PINNED -> {
                manualHide = true
                startHide()
            }
            else -> Unit
        }
    }

    // ---- slide --------------------------------------------------------------------------------

    /**
     * The controller's slide reached its end. [opening] says which slide it was; one that does not
     * match the state (it was overtaken by a newer command) is ignored.
     */
    @Synchronized
    fun onSlideFinished(opening: Boolean) {
        when {
            state == EdgePanelState.REVEALING && opening -> publish(
                when {
                    pinned -> EdgePanelState.PINNED
                    activated -> EdgePanelState.ACTIVE
                    else -> EdgePanelState.PEEK
                },
            )
            state == EdgePanelState.HIDING && !opening -> {
                activated = false
                resetTimers()
                publish(EdgePanelState.HIDDEN)
                onCommand(EdgeCommand.PARK)
            }
        }
    }

    companion object {
        /** Closing waits this long after the last typed character. */
        const val TYPING_EXTRA_MS = 4_000L
    }
}
