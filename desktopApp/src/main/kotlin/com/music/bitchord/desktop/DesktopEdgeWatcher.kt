package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * EDGE 1: the edge watcher. It polls the pointer, decides when the cursor has dwelt in the edge
 * zone long enough, and reports `RevealRequested`; while the panel is busy it also reports every
 * pointer `Sample` so the Part 2 state machine can run its hide timers. It owns no window and no
 * UI. The loop is one daemon coroutine; [tick] holds all the logic and is called directly by the
 * tests with fake sources and a fake clock.
 *
 * Threading: events are delivered on the watcher's own thread (Dispatchers.Default). The listener
 * must hand over to the AWT/Compose thread itself before touching a window.
 */

internal interface EdgePointerSource {
    /** The cursor in AWT user space, or null when unknown. */
    fun position(): EdgePoint?

    /** Whether any mouse button is down (dragging a window, a scrollbar, a selection). */
    fun buttonHeld(): Boolean
}

internal fun interface EdgeClock {
    fun nowMs(): Long

    companion object {
        /** Monotonic milliseconds. */
        val SYSTEM = EdgeClock { System.nanoTime() / 1_000_000L }
    }
}

internal fun interface EdgeFullscreenProbe {
    /** True only when a fullscreen app is in front; false on any failure. */
    fun fullscreenForeground(): Boolean
}

internal fun interface EdgeMonitorSource {
    fun monitors(): List<EdgeMonitor>
}

/** What the controller is doing, as far as the poll rate is concerned. */
internal enum class EdgeWatchMode {
    /** The panel is hidden: only the reveal trigger matters. */
    HIDDEN,

    /** REVEALING, HIDING, PEEK, ACTIVE or PINNED: the controller wants fresh samples. */
    BUSY,
}

internal data class EdgeWatchConfig(
    val side: EdgeSide = EdgeSide.RIGHT,
    val zoneWidthPx: Int = DesktopEdgeGeometry.DEFAULT_ZONE_PX,
    val segment: EdgeSegment = EdgeSegment.FULL,
    val dwellMs: Long = DEFAULT_DWELL_MS,
    val requireOuterEdge: Boolean = true,
    val suppressWhenFullscreen: Boolean = true,
    val lowPower: Boolean = false,
) {
    /** The same config with every value forced into its allowed range. */
    fun coerced(): EdgeWatchConfig = copy(
        zoneWidthPx = DesktopEdgeGeometry.clampZonePx(zoneWidthPx),
        segment = segment.normalized(),
        dwellMs = dwellMs.coerceIn(MIN_DWELL_MS, MAX_DWELL_MS),
    )

    companion object {
        const val MIN_DWELL_MS = 50L
        const val MAX_DWELL_MS = 400L
        const val DEFAULT_DWELL_MS = 150L
    }
}

internal sealed interface EdgeEvent {
    /** The cursor has dwelt in the edge zone for the dwell time; reveal the panel on [monitor]. */
    data class RevealRequested(val atMs: Long, val monitor: EdgeMonitor, val point: EdgePoint) : EdgeEvent

    /** One pointer reading; sent only while the watch mode is [EdgeWatchMode.BUSY]. */
    data class Sample(
        val atMs: Long,
        val point: EdgePoint?,
        val buttonHeld: Boolean,
        val monitor: EdgeMonitor?,
        val inZone: Boolean,
    ) : EdgeEvent
}

/** How often to look. Slow when nothing can happen, quick when something is about to. */
internal object EdgePollPolicy {
    const val NORMAL_MS = 80L
    const val IDLE_MS = 150L
    const val BUSY_MS = 40L
    const val IDLE_AFTER_MS = 5_000L

    fun nextDelayMs(mode: EdgeWatchMode, inZone: Boolean, idleForMs: Long, lowPower: Boolean): Long = when {
        mode == EdgeWatchMode.BUSY -> BUSY_MS
        inZone -> BUSY_MS // a dwell is pending: look often enough to time it
        lowPower -> IDLE_MS
        idleForMs >= IDLE_AFTER_MS -> IDLE_MS
        else -> NORMAL_MS
    }
}

/** Times how long the cursor has stayed in the zone without a button held. */
internal class EdgeDwell {
    private var startedAt = -1L
    private var consumed = false

    /** Feeds one reading. */
    fun update(inZone: Boolean, buttonHeld: Boolean, nowMs: Long) {
        if (!inZone || buttonHeld) {
            startedAt = -1L
            consumed = false
        } else if (startedAt < 0L) {
            startedAt = nowMs
        }
    }

    fun isSatisfied(nowMs: Long, dwellMs: Long): Boolean =
        startedAt >= 0L && !consumed && nowMs - startedAt >= dwellMs

    /** The reveal was sent: no more until the cursor has left and come back. */
    fun consume() {
        consumed = true
    }

    fun reset() {
        startedAt = -1L
        consumed = false
    }
}

internal class DesktopEdgeWatcher(
    private val config: () -> EdgeWatchConfig,
    private val mode: () -> EdgeWatchMode,
    private val pointer: EdgePointerSource,
    private val monitors: EdgeMonitorSource,
    private val fullscreen: EdgeFullscreenProbe,
    private val clock: EdgeClock = EdgeClock.SYSTEM,
    private val onEvent: (EdgeEvent) -> Unit,
) {

    private val dwell = EdgeDwell()
    private var lastPoint: EdgePoint? = null
    private var lastMoveMs = -1L
    private var lastFullscreenCheckMs = -1L
    private var fullscreenBlocked = false

    /** After the panel was busy, the cursor must leave the zone once before a new reveal. */
    private var needsLeave = false
    private var previousMode = EdgeWatchMode.HIDDEN

    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts the poll loop in [scope] on a daemon thread. Does nothing when already running. */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        reset()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val wait = try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    DesktopTrackLog.log("edge: watcher tick failed: ${t.message}")
                    FAILURE_RETRY_MS
                }
                delay(wait)
            }
        }
    }

    /** Stops the loop completely: nothing runs while the side panel mode is off. */
    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    private fun reset() {
        dwell.reset()
        lastPoint = null
        lastMoveMs = -1L
        lastFullscreenCheckMs = -1L
        fullscreenBlocked = false
        needsLeave = false
        previousMode = EdgeWatchMode.HIDDEN
    }

    /**
     * One poll. Returns how many ms to wait before the next. Public to the module so tests drive
     * it with a fake clock.
     */
    internal fun tick(): Long {
        val now = clock.nowMs()
        val cfg = config().coerced()
        val currentMode = mode()

        val point = runCatching { pointer.position() }.getOrNull()
        if (point != lastPoint || lastMoveMs < 0L) {
            lastPoint = point
            lastMoveMs = now
        }

        val list = if (point != null) runCatching { monitors.monitors() }.getOrDefault(emptyList()) else emptyList()
        val monitor = point?.let { DesktopEdgeGeometry.monitorAt(list, it) }
        val inZone = point != null && monitor != null && DesktopEdgeGeometry.inEdgeZone(
            p = point,
            monitor = monitor,
            side = cfg.side,
            zoneWidthPx = cfg.zoneWidthPx,
            segment = cfg.segment,
            monitors = list,
            requireOuterEdge = cfg.requireOuterEdge,
        )

        if (currentMode == EdgeWatchMode.BUSY) {
            val held = point != null && runCatching { pointer.buttonHeld() }.getOrDefault(false)
            dwell.reset()
            needsLeave = true
            previousMode = currentMode
            onEvent(EdgeEvent.Sample(now, point, held, monitor, inZone))
            return EdgePollPolicy.nextDelayMs(currentMode, inZone, now - lastMoveMs, cfg.lowPower)
        }

        // HIDDEN. Coming back from BUSY, the cursor has to leave the zone once before it can reveal.
        if (previousMode == EdgeWatchMode.BUSY) {
            previousMode = EdgeWatchMode.HIDDEN
            dwell.reset()
        }
        if (needsLeave && !inZone) needsLeave = false

        if (inZone && !needsLeave && monitor != null && point != null) {
            val held = runCatching { pointer.buttonHeld() }.getOrDefault(false)
            dwell.update(inZone = true, buttonHeld = held, nowMs = now)
            if (dwell.isSatisfied(now, cfg.dwellMs)) {
                if (!isFullscreenBlocked(cfg, now)) {
                    dwell.consume()
                    onEvent(EdgeEvent.RevealRequested(now, monitor, point))
                } else if (now - lastSuppressLogMs >= 5_000L) { // UI U4 hook
                    lastSuppressLogMs = now
                    runCatching { DesktopTrackLog.log("edge: reveal suppressed: fullscreen foreground (see the probe line above)") }
                }
            }
        } else {
            dwell.update(inZone = false, buttonHeld = false, nowMs = now)
        }
        return EdgePollPolicy.nextDelayMs(currentMode, inZone && !needsLeave, now - lastMoveMs, cfg.lowPower)
    }

    /**
     * The fullscreen check costs native calls, so it runs only once a reveal is otherwise due, and
     * at most every [FULLSCREEN_RECHECK_MS] while the cursor keeps dwelling.
     */
    private var lastSuppressLogMs = -5_000L

    private fun isFullscreenBlocked(cfg: EdgeWatchConfig, now: Long): Boolean {
        if (!cfg.suppressWhenFullscreen) return false
        if (lastFullscreenCheckMs < 0L || now - lastFullscreenCheckMs >= FULLSCREEN_RECHECK_MS) {
            lastFullscreenCheckMs = now
            fullscreenBlocked = runCatching { fullscreen.fullscreenForeground() }.getOrDefault(false)
        }
        return fullscreenBlocked
    }

    companion object {
        const val FULLSCREEN_RECHECK_MS = 500L
        private const val FAILURE_RETRY_MS = 500L

        /** The real thing: `MouseInfo`, `GraphicsEnvironment`, and the Windows fullscreen probe. */
        fun forSystem(
            config: () -> EdgeWatchConfig,
            mode: () -> EdgeWatchMode,
            onEvent: (EdgeEvent) -> Unit,
        ): DesktopEdgeWatcher = DesktopEdgeWatcher(
            config = config,
            mode = mode,
            pointer = AwtEdgePointerSource,
            monitors = AwtEdgeMonitorSource(),
            fullscreen = WindowsFullscreenProbe(),
            onEvent = onEvent,
        )
    }
}
