package com.music.bitchord.desktop

/*
 * EDGE 1: the side-panel geometry. Pure Kotlin: no AWT, no Compose, no clock, so every rule here
 * is unit-tested without a screen. All coordinates are AWT user space (what `Window.bounds` and
 * `MouseInfo` use); the display scale is only used to turn dp into those units.
 */

internal enum class EdgeSide { LEFT, RIGHT }

/** Slide durations in ms. FAST/NORMAL/SLOW are the "Animation speed" setting. */
internal enum class EdgeSpeed(val inMs: Long, val outMs: Long) {
    FAST(140L, 120L),
    NORMAL(240L, 200L),
    SLOW(360L, 300L),
}

internal enum class EdgeMonitorChoice { CURSOR, PRIMARY, PICK }

internal data class EdgePoint(val x: Int, val y: Int)

/** One monitor: its full [bounds], its [work] area (taskbar taken out) and its dp scale. */
internal data class EdgeMonitor(
    val bounds: DisplayRect,
    val work: DisplayRect,
    val scale: Double = 1.0,
    val primary: Boolean = false,
) {
    companion object {
        /** Builds the work area from `Toolkit.getScreenInsets` style insets. */
        fun fromInsets(
            bounds: DisplayRect,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            scale: Double = 1.0,
            primary: Boolean = false,
        ): EdgeMonitor {
            val work = DisplayRect(
                bounds.x + left,
                bounds.y + top,
                (bounds.width - left - right).coerceAtLeast(1),
                (bounds.height - top - bottom).coerceAtLeast(1),
            )
            return EdgeMonitor(bounds, work, scale, primary)
        }
    }
}

/**
 * An optional vertical slice of the edge, as percentages of the monitor height. An invalid one
 * (from >= to, or out of 0..100) means the full edge.
 */
internal data class EdgeSegment(val fromPercent: Int, val toPercent: Int) {
    val isFull: Boolean get() = fromPercent <= 0 && toPercent >= 100
    val isValid: Boolean get() = fromPercent in 0..99 && toPercent in 1..100 && fromPercent < toPercent

    /** This segment when valid, otherwise [FULL]. */
    fun normalized(): EdgeSegment = if (isValid) this else FULL

    companion object {
        val FULL = EdgeSegment(0, 100)
    }
}

/** The panel at rest: where it sits open and where it sits fully off the screen. */
internal data class EdgeLayout(val open: DisplayRect, val hidden: DisplayRect) {
    val openX: Int get() = open.x
    val hiddenX: Int get() = hidden.x
}

/**
 * A slide on the X axis. [xAt] depends only on elapsed time, never on frame counts, so it stays
 * smooth when frames drop. [opening] picks the easing: ease-out cubic in, ease-in cubic out.
 */
internal data class EdgeSlide(
    val fromX: Int,
    val toX: Int,
    val startMs: Long,
    val durationMs: Long,
    val opening: Boolean,
) {
    fun isFinished(nowMs: Long): Boolean = durationMs <= 0L || nowMs - startMs >= durationMs

    fun xAt(nowMs: Long): Int {
        if (isFinished(nowMs)) return toX
        val p = ((nowMs - startMs).toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val eased = if (opening) DesktopEdgeGeometry.easeOutCubic(p) else DesktopEdgeGeometry.easeInCubic(p)
        return Math.round(fromX + (toX - fromX) * eased).toInt()
    }
}

internal object DesktopEdgeGeometry {

    const val MIN_WIDTH_DP = 340
    const val MAX_WIDTH_DP = 640
    const val DEFAULT_WIDTH_DP = 420
    const val MARGIN_DP = 12
    const val GAP_DP = 8

    const val MIN_ZONE_PX = 2
    const val MAX_ZONE_PX = 12
    const val DEFAULT_ZONE_PX = 4

    /** The cursor may wander this far outside the panel before it counts as "away". */
    const val HIDE_MARGIN_PX = 24

    fun easeOutCubic(p: Double): Double {
        val q = 1.0 - p.coerceIn(0.0, 1.0)
        return 1.0 - q * q * q
    }

    fun easeInCubic(p: Double): Double {
        val q = p.coerceIn(0.0, 1.0)
        return q * q * q
    }

    private fun safeScale(scale: Double): Double = if (scale.isFinite() && scale > 0.0) scale else 1.0

    fun dpToPx(dp: Int, scale: Double): Int = Math.round(dp * safeScale(scale)).toInt()

    // ---- monitors ----------------------------------------------------------------------------

    /** Left to right, then top to bottom: a stable order for "pick monitor N". */
    fun ordered(monitors: List<EdgeMonitor>): List<EdgeMonitor> =
        monitors.sortedWith(compareBy({ it.bounds.x }, { it.bounds.y }))

    private fun contains(rect: DisplayRect, x: Int, y: Int): Boolean =
        x >= rect.x && x < rect.right && y >= rect.y && y < rect.bottom

    private fun distanceSquared(rect: DisplayRect, p: EdgePoint): Long {
        val dx = when {
            p.x < rect.x -> rect.x - p.x
            p.x >= rect.right -> p.x - (rect.right - 1)
            else -> 0
        }.toLong()
        val dy = when {
            p.y < rect.y -> rect.y - p.y
            p.y >= rect.bottom -> p.y - (rect.bottom - 1)
            else -> 0
        }.toLong()
        return dx * dx + dy * dy
    }

    /** The monitor holding [p]; failing that the nearest one; null only when there are none. */
    fun monitorAt(monitors: List<EdgeMonitor>, p: EdgePoint): EdgeMonitor? {
        monitors.firstOrNull { contains(it.bounds, p.x, p.y) }?.let { return it }
        return monitors.minByOrNull { distanceSquared(it.bounds, p) }
    }

    /** The monitor the panel docks to for the "Monitor" setting. [pickedIndex] indexes [ordered]. */
    fun selectMonitor(
        monitors: List<EdgeMonitor>,
        choice: EdgeMonitorChoice,
        cursor: EdgePoint?,
        pickedIndex: Int = 0,
    ): EdgeMonitor? {
        if (monitors.isEmpty()) return null
        val primary = monitors.firstOrNull { it.primary } ?: ordered(monitors).first()
        return when (choice) {
            EdgeMonitorChoice.PRIMARY -> primary
            EdgeMonitorChoice.CURSOR -> cursor?.let { monitorAt(monitors, it) } ?: primary
            EdgeMonitorChoice.PICK -> ordered(monitors).getOrNull(pickedIndex) ?: primary
        }
    }

    /**
     * Whether another monitor touches [monitor]'s [side] at height [y]. On such an edge the cursor
     * crosses to the neighbour instead of stopping, so it is not a screen edge. Also tells the
     * controller that a slide would show the panel on the neighbour while it travels.
     */
    fun hasNeighbourOnSide(monitors: List<EdgeMonitor>, monitor: EdgeMonitor, side: EdgeSide, y: Int): Boolean {
        val probeX = if (side == EdgeSide.RIGHT) monitor.bounds.right else monitor.bounds.x - 1
        return monitors.any { it !== monitor && it.bounds != monitor.bounds && contains(it.bounds, probeX, y) }
    }

    // ---- panel rectangle ---------------------------------------------------------------------

    /** The panel width in AWT units: [widthDp] clamped to 340..640 dp and to what the work area holds. */
    fun panelWidthPx(widthDp: Int, work: DisplayRect, scale: Double): Int {
        val wantedDp = widthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
        val gap = dpToPx(GAP_DP, scale)
        val room = (work.width - 2 * gap).coerceAtLeast(1)
        return dpToPx(wantedDp, scale).coerceAtMost(room).coerceAtLeast(1)
    }

    /**
     * Open and hidden rectangles of the panel on [monitor]. Open: [GAP_DP] from the work-area edge
     * (taskbar respected), [MARGIN_DP] from the top and bottom of the work area. Hidden: the same
     * size and height, entirely beyond the monitor's own edge (not the work edge), so a taskbar on
     * that side never leaves a sliver on screen.
     */
    fun layout(monitor: EdgeMonitor, side: EdgeSide, widthDp: Int = DEFAULT_WIDTH_DP): EdgeLayout {
        val work = monitor.work
        val width = panelWidthPx(widthDp, work, monitor.scale)
        val gap = dpToPx(GAP_DP, monitor.scale)
        val margin = dpToPx(MARGIN_DP, monitor.scale)
        val height = (work.height - 2 * margin).coerceAtLeast(1)
        val y = if (work.height - 2 * margin >= 1) work.y + margin else work.y
        val openX = if (side == EdgeSide.RIGHT) work.right - gap - width else work.x + gap
        val hiddenX = if (side == EdgeSide.RIGHT) monitor.bounds.right else monitor.bounds.x - width
        return EdgeLayout(
            open = DisplayRect(openX, y, width, height),
            hidden = DisplayRect(hiddenX, y, width, height),
        )
    }

    /** [rect] moved and shrunk until it lies inside [monitor]'s work area (for the open position). */
    fun clampToDisplay(rect: DisplayRect, monitor: EdgeMonitor): DisplayRect =
        DesktopDisplayGeometry.clampToWorkArea(
            rect,
            monitor.work,
            minWidth = rect.width.coerceAtMost(monitor.work.width),
            minHeight = rect.height.coerceAtMost(monitor.work.height),
        )

    // ---- cursor tests ------------------------------------------------------------------------

    fun clampZonePx(zonePx: Int): Int = zonePx.coerceIn(MIN_ZONE_PX, MAX_ZONE_PX)

    /**
     * Whether [p] is in the edge trigger zone of [monitor]: within [zoneWidthPx] of the [side]
     * edge, and (when [segment] is not the full edge) inside that vertical slice. With
     * [requireOuterEdge] an edge shared with another monitor at that height does not count.
     */
    fun inEdgeZone(
        p: EdgePoint,
        monitor: EdgeMonitor,
        side: EdgeSide,
        zoneWidthPx: Int,
        segment: EdgeSegment = EdgeSegment.FULL,
        monitors: List<EdgeMonitor> = listOf(monitor),
        requireOuterEdge: Boolean = true,
    ): Boolean {
        val b = monitor.bounds
        if (!contains(b, p.x, p.y)) return false
        val zone = clampZonePx(zoneWidthPx).coerceAtMost(b.width)
        val horizontal = if (side == EdgeSide.RIGHT) p.x >= b.right - zone else p.x < b.x + zone
        if (!horizontal) return false
        val seg = segment.normalized()
        if (!seg.isFull) {
            val top = b.y + b.height * seg.fromPercent / 100
            val bottom = b.y + b.height * seg.toPercent / 100
            if (p.y < top || p.y >= bottom) return false
        }
        if (requireOuterEdge && hasNeighbourOnSide(monitors, monitor, side, p.y)) return false
        return true
    }

    /** Whether [p] is inside [panel] grown by [marginPx] on every side. */
    fun insidePanelPlusMargin(p: EdgePoint, panel: DisplayRect, marginPx: Int = HIDE_MARGIN_PX): Boolean =
        p.x >= panel.x - marginPx && p.x < panel.right + marginPx &&
            p.y >= panel.y - marginPx && p.y < panel.bottom + marginPx

    // ---- slides ------------------------------------------------------------------------------

    /**
     * A slide from [currentX] to [targetX], where [openX] and [hiddenX] are the two rest positions.
     * The duration is the full duration for the speed times the part of the full travel that is
     * left, so an interrupted slide continues from where it is (its first [EdgeSlide.xAt] equals
     * [currentX]) and takes proportionally less. [reduceAnimation] makes it a snap (duration 0).
     */
    fun slideTo(
        currentX: Int,
        targetX: Int,
        openX: Int,
        hiddenX: Int,
        nowMs: Long,
        speed: EdgeSpeed,
        reduceAnimation: Boolean = false,
    ): EdgeSlide {
        val opening = targetX == openX
        val travel = Math.abs(hiddenX - openX)
        val remaining = Math.abs(targetX - currentX)
        val full = if (opening) speed.inMs else speed.outMs
        val duration = when {
            reduceAnimation || remaining == 0 || travel == 0 -> 0L
            else -> Math.round(full * (remaining.toDouble() / travel.toDouble()).coerceIn(0.0, 1.0))
                .coerceAtLeast(1L)
        }
        return EdgeSlide(currentX, targetX, nowMs, duration, opening)
    }

    /** Continues from a slide that is still running, or from [restX] when none is. */
    fun retarget(
        running: EdgeSlide?,
        restX: Int,
        targetX: Int,
        layout: EdgeLayout,
        nowMs: Long,
        speed: EdgeSpeed,
        reduceAnimation: Boolean = false,
    ): EdgeSlide {
        val current = running?.xAt(nowMs) ?: restX
        return slideTo(current, targetX, layout.openX, layout.hiddenX, nowMs, speed, reduceAnimation)
    }
}
