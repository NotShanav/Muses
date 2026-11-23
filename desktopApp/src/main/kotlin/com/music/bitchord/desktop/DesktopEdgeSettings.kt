package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * EDGE 3: the persisted settings of the side panel. [EdgeSettingsValues] is the whole thing in one
 * immutable value; [DesktopEdgeSettingsCodec] is pure (names, ranges, text) and unit tested;
 * [DesktopEdgeSettings] saves, publishes the mapped [EdgePanelConfig] to the controller and keeps the
 * tray in step. With `enabled` false (the default) nothing here starts anything.
 */

/** What Settings edits: the feature switch, the start-up switch and the engine config. */
internal data class EdgeSettingsValues(
    val enabled: Boolean = false,
    val startInPanel: Boolean = false,
    val config: EdgePanelConfig = EdgePanelConfig(),
) {
    /** Every value forced into its allowed range. */
    fun coerced(): EdgeSettingsValues = copy(config = config.coerced())

    /** The mode is entered at start-up only when the feature is on AND the owner asked for it. */
    val startsInPanel: Boolean get() = enabled && startInPanel
}

internal object DesktopEdgeSettingsCodec {

    const val MIN_SEGMENT_SPAN = 5

    fun sideName(side: EdgeSide): String = if (side == EdgeSide.LEFT) "left" else "right"

    fun side(name: String): EdgeSide = if (name.equals("left", ignoreCase = true)) EdgeSide.LEFT else EdgeSide.RIGHT

    fun monitorName(choice: EdgeMonitorChoice): String = choice.name.lowercase()

    fun monitor(name: String): EdgeMonitorChoice =
        EdgeMonitorChoice.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EdgeMonitorChoice.CURSOR

    fun speedName(speed: EdgeSpeed): String = speed.name.lowercase()

    fun speed(name: String): EdgeSpeed =
        EdgeSpeed.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EdgeSpeed.NORMAL

    /** A segment from two stored numbers; anything invalid becomes the full edge. */
    fun segment(fromPercent: Int, toPercent: Int): EdgeSegment = EdgeSegment(fromPercent, toPercent).normalized()

    /** Moves the start of the segment, keeping at least [MIN_SEGMENT_SPAN] percent. */
    fun segmentWithFrom(segment: EdgeSegment, fromPercent: Int): EdgeSegment {
        val s = segment.normalized()
        return EdgeSegment(fromPercent.coerceIn(0, s.toPercent - MIN_SEGMENT_SPAN), s.toPercent)
    }

    /** Moves the end of the segment, keeping at least [MIN_SEGMENT_SPAN] percent. */
    fun segmentWithTo(segment: EdgeSegment, toPercent: Int): EdgeSegment {
        val s = segment.normalized()
        return EdgeSegment(s.fromPercent, toPercent.coerceIn(s.fromPercent + MIN_SEGMENT_SPAN, 100))
    }

    /** The sentence Settings shows so the owner knows where the trigger is. */
    fun zoneReminder(config: EdgePanelConfig): String {
        val c = config.coerced()
        val edge = if (c.side == EdgeSide.LEFT) "left" else "right"
        val where = if (c.segment.isFull) "the whole height" else "${c.segment.fromPercent}% to ${c.segment.toPercent}% of the height"
        val monitor = when (c.monitor) {
            EdgeMonitorChoice.CURSOR -> "of the screen the pointer is on"
            EdgeMonitorChoice.PRIMARY -> "of the main screen"
            EdgeMonitorChoice.PICK -> "of screen ${c.pickedMonitor + 1}"
        }
        return "The trigger is a ${c.zoneWidthPx} px wide strip at the $edge edge $monitor, $where. " +
            "Rest the pointer on it for ${c.dwellMs} ms and the panel slides in."
    }
}

internal object DesktopEdgeSettings {

    private val persistence = DesktopPersistence()

    private val _values = MutableStateFlow(load())
    val values: StateFlow<EdgeSettingsValues> = _values

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /**
     * Called once when the window exists. Publishes the config, keeps the tray menu in step with the
     * mode and, when the owner asked for it, enters the mode (hidden). Safe to call twice.
     */
    fun start() {
        if (started) return
        started = true
        val current = _values.value
        DesktopEdgePanel.config.value = current.config
        syncTray(current.enabled, DesktopEdgePanel.isPanelMode)
        scope.launch {
            DesktopEdgePanel.mode.collect { on -> syncTray(_values.value.enabled, on) }
        }
        if (current.startsInPanel) DesktopEdgePanel.enterPanel(reveal = false)
    }

    /** Applies [change], validates, saves and publishes. Equal results do nothing. */
    fun update(change: (EdgeSettingsValues) -> EdgeSettingsValues) {
        val old = _values.value
        val new = change(old).coerced()
        if (new == old) return
        save(new)
        _values.value = new
        DesktopEdgePanel.config.value = new.config
        if (old.enabled && !new.enabled) DesktopEdgePanel.leavePanel()
        syncTray(new.enabled, DesktopEdgePanel.isPanelMode)
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun updateConfig(change: (EdgePanelConfig) -> EdgePanelConfig) = update { it.copy(config = change(it.config)) }

    /** Top-bar button, tray item and shortcut: no effect while the feature is off. */
    fun togglePanelIfEnabled() {
        if (_values.value.enabled) DesktopEdgePanel.togglePanel()
    }

    /** Settings "Test slide": the panel slides out and back in (or just in, when hidden). */
    fun testSlide() {
        if (!_values.value.enabled) return
        if (!DesktopEdgePanel.isPanelMode) {
            DesktopEdgePanel.enterPanel(reveal = true)
            return
        }
        when (DesktopEdgePanel.view.value.state) {
            EdgePanelState.HIDDEN, EdgePanelState.HIDING -> DesktopEdgePanel.showNow()
            else -> {
                DesktopEdgePanel.hideNow()
                scope.launch {
                    kotlinx.coroutines.delay(TEST_SLIDE_PAUSE_MS)
                    DesktopEdgePanel.showNow()
                }
            }
        }
    }

    private fun syncTray(enabled: Boolean, panelOn: Boolean) {
        if (!enabled) {
            DesktopTrayMenu.setEdgePanel(null, null, false)
            return
        }
        DesktopTrayMenu.setEdgePanel(
            toggle = { DesktopEdgePanel.togglePanel() },
            show = { DesktopEdgePanel.showNow() },
            panelOn = panelOn,
        )
    }

    private fun load(): EdgeSettingsValues {
        val p = persistence
        val defaults = EdgePanelConfig()
        val config = EdgePanelConfig(
            side = DesktopEdgeSettingsCodec.side(p.string(KEY_SIDE, "right")),
            widthDp = p.int(KEY_WIDTH, defaults.widthDp),
            monitor = DesktopEdgeSettingsCodec.monitor(p.string(KEY_MONITOR, "cursor")),
            pickedMonitor = p.int(KEY_PICKED, 0),
            zoneWidthPx = p.int(KEY_ZONE, defaults.zoneWidthPx),
            segment = DesktopEdgeSettingsCodec.segment(p.int(KEY_SEG_FROM, 0), p.int(KEY_SEG_TO, 100)),
            dwellMs = p.int(KEY_DWELL, defaults.dwellMs.toInt()).toLong(),
            closeDelayMs = p.int(KEY_CLOSE, defaults.closeDelayMs.toInt()).toLong(),
            speed = DesktopEdgeSettingsCodec.speed(p.string(KEY_SPEED, "normal")),
            suppressWhenFullscreen = p.boolean(KEY_FULLSCREEN, true),
            lowPower = p.boolean(KEY_LOW_POWER, false),
            pinByDefault = p.boolean(KEY_PIN_DEFAULT, false),
        )
        return EdgeSettingsValues(
            enabled = p.boolean(KEY_ENABLED, false),
            startInPanel = p.boolean(KEY_START, false),
            config = config,
        ).coerced()
    }

    private fun save(v: EdgeSettingsValues) {
        val p = persistence
        val c = v.config
        p.saveBoolean(KEY_ENABLED, v.enabled)
        p.saveBoolean(KEY_START, v.startInPanel)
        p.saveString(KEY_SIDE, DesktopEdgeSettingsCodec.sideName(c.side))
        p.saveInt(KEY_WIDTH, c.widthDp)
        p.saveString(KEY_MONITOR, DesktopEdgeSettingsCodec.monitorName(c.monitor))
        p.saveInt(KEY_PICKED, c.pickedMonitor)
        p.saveInt(KEY_ZONE, c.zoneWidthPx)
        p.saveInt(KEY_SEG_FROM, c.segment.fromPercent)
        p.saveInt(KEY_SEG_TO, c.segment.toPercent)
        p.saveInt(KEY_DWELL, c.dwellMs.toInt())
        p.saveInt(KEY_CLOSE, c.closeDelayMs.toInt())
        p.saveString(KEY_SPEED, DesktopEdgeSettingsCodec.speedName(c.speed))
        p.saveBoolean(KEY_FULLSCREEN, c.suppressWhenFullscreen)
        p.saveBoolean(KEY_LOW_POWER, c.lowPower)
        p.saveBoolean(KEY_PIN_DEFAULT, c.pinByDefault)
    }

    private const val TEST_SLIDE_PAUSE_MS = 600L

    private const val KEY_ENABLED = "edge_enabled"
    private const val KEY_START = "edge_start_in_panel"
    private const val KEY_SIDE = "edge_side"
    private const val KEY_WIDTH = "edge_width_dp"
    private const val KEY_MONITOR = "edge_monitor"
    private const val KEY_PICKED = "edge_picked_monitor"
    private const val KEY_ZONE = "edge_zone_px"
    private const val KEY_SEG_FROM = "edge_segment_from"
    private const val KEY_SEG_TO = "edge_segment_to"
    private const val KEY_DWELL = "edge_dwell_ms"
    private const val KEY_CLOSE = "edge_close_ms"
    private const val KEY_SPEED = "edge_speed"
    private const val KEY_FULLSCREEN = "edge_suppress_fullscreen"
    private const val KEY_LOW_POWER = "edge_low_power"
    private const val KEY_PIN_DEFAULT = "edge_pin_default"
}
