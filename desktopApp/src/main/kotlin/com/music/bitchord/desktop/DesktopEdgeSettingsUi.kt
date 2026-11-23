package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/*
 * EDGE 3: the "Side panel (experimental)" rows of Settings > Advanced > Experimental. Same row
 * pattern as the voice, earbuds and web rows: the caller supplies the search filter and the toggle
 * row. Sliders keep the value locally while dragging and save once on release, so a drag does not
 * re-dock the panel on every pixel. Nothing but the first switch shows while the feature is off.
 */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopEdgeSettingsRows(
    rowVisible: @Composable (title: String, subtitle: String) -> Boolean,
    toggle: @Composable (title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val values by DesktopEdgeSettings.values.collectAsState()
    val panelOn by DesktopEdgePanel.mode.collectAsState()
    val config = values.config

    toggle(
        "Side panel (experimental)",
        "Shrinks the whole app into a narrow floating window docked to a screen edge. It stays hidden; " +
            "move the pointer to the edge and it slides in, move away and it slides out. " +
            "Off by default: with this off nothing runs and the app is exactly as before.",
        values.enabled,
        DesktopEdgeSettings::setEnabled,
    )

    if (!values.enabled) return

    toggle(
        "Start in side panel mode",
        "Opens Muses as the hidden side panel every time it starts.",
        values.startInPanel,
    ) { on -> DesktopEdgeSettings.update { it.copy(startInPanel = on) } }

    if (rowVisible("Side panel controls", "Enter or leave side panel mode, test the slide")) {
        EdgeInfoBlock("Side panel controls", if (panelOn) "Side panel mode is on." else "Side panel mode is off.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { DesktopEdgePanel.togglePanel() }) {
                    Text(if (panelOn) "Leave side panel mode" else "Enter side panel mode")
                }
                OutlinedButton(onClick = { DesktopEdgeSettings.testSlide() }) { Text("Test slide") }
            }
        }
    }

    if (rowVisible("Side panel side", "Left or right edge")) {
        EdgeInfoBlock("Side", "Which edge of the screen the panel is docked to.") {
            EdgeChoices(
                listOf("Left" to (config.side == EdgeSide.LEFT), "Right" to (config.side == EdgeSide.RIGHT)),
            ) { index -> DesktopEdgeSettings.updateConfig { it.copy(side = if (index == 0) EdgeSide.LEFT else EdgeSide.RIGHT) } }
        }
    }

    if (rowVisible("Side panel monitor", "Which screen the panel opens on")) {
        val screens = remember {
            runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size }.getOrDefault(1).coerceIn(1, 8)
        }
        EdgeInfoBlock("Monitor", "The screen the panel opens on.") {
            EdgeChoices(
                listOf(
                    "Where the pointer is" to (config.monitor == EdgeMonitorChoice.CURSOR),
                    "Main screen" to (config.monitor == EdgeMonitorChoice.PRIMARY),
                    "Pick a screen" to (config.monitor == EdgeMonitorChoice.PICK),
                ),
            ) { index ->
                DesktopEdgeSettings.updateConfig {
                    it.copy(monitor = EdgeMonitorChoice.entries.getOrElse(index) { EdgeMonitorChoice.CURSOR })
                }
            }
            if (config.monitor == EdgeMonitorChoice.PICK) {
                Spacer(Modifier.height(8.dp))
                EdgeChoices(
                    (0 until screens).map { n -> "Screen ${n + 1}" to (config.pickedMonitor == n) },
                ) { index -> DesktopEdgeSettings.updateConfig { it.copy(pickedMonitor = index) } }
            }
        }
    }

    EdgeSlider(
        "Panel width", "How wide the panel is when open.", "${config.widthDp} dp",
        config.widthDp, DesktopEdgeGeometry.MIN_WIDTH_DP..DesktopEdgeGeometry.MAX_WIDTH_DP, 10, rowVisible,
    ) { v -> DesktopEdgeSettings.updateConfig { it.copy(widthDp = v) } }

    EdgeSlider(
        "Edge trigger width", "How thick the invisible strip at the screen edge is.", "${config.zoneWidthPx} px",
        config.zoneWidthPx, DesktopEdgeGeometry.MIN_ZONE_PX..DesktopEdgeGeometry.MAX_ZONE_PX, 1, rowVisible,
    ) { v -> DesktopEdgeSettings.updateConfig { it.copy(zoneWidthPx = v) } }

    if (rowVisible("Side panel trigger zone", "Whole edge or only part of it")) {
        EdgeInfoBlock("Trigger zone", "Use the whole edge, or only a part of its height (useful to keep a corner free).") {
            EdgeChoices(
                listOf("Whole edge" to config.segment.isFull, "Part of the edge" to !config.segment.isFull),
            ) { index ->
                DesktopEdgeSettings.updateConfig {
                    it.copy(segment = if (index == 0) EdgeSegment.FULL else EdgeSegment(25, 75))
                }
            }
        }
    }
    if (!config.segment.isFull) {
        EdgeSlider(
            "Trigger zone starts at", "Percent of the screen height, from the top.", "${config.segment.fromPercent}%",
            config.segment.fromPercent, 0..95, 1, rowVisible,
        ) { v -> DesktopEdgeSettings.updateConfig { it.copy(segment = DesktopEdgeSettingsCodec.segmentWithFrom(it.segment, v)) } }
        EdgeSlider(
            "Trigger zone ends at", "Percent of the screen height, from the top.", "${config.segment.toPercent}%",
            config.segment.toPercent, 5..100, 1, rowVisible,
        ) { v -> DesktopEdgeSettings.updateConfig { it.copy(segment = DesktopEdgeSettingsCodec.segmentWithTo(it.segment, v)) } }
    }

    EdgeSlider(
        "Open delay", "How long the pointer must rest on the edge before the panel opens.", "${config.dwellMs} ms",
        config.dwellMs.toInt(), EdgeWatchConfig.MIN_DWELL_MS.toInt()..EdgeWatchConfig.MAX_DWELL_MS.toInt(), 10, rowVisible,
    ) { v -> DesktopEdgeSettings.updateConfig { it.copy(dwellMs = v.toLong()) } }

    EdgeSlider(
        "Close delay", "How long the pointer must stay away before the panel slides out.", "${config.closeDelayMs} ms",
        config.closeDelayMs.toInt(), EdgePanelConfig.MIN_CLOSE_DELAY_MS.toInt()..EdgePanelConfig.MAX_CLOSE_DELAY_MS.toInt(), 50, rowVisible,
    ) { v -> DesktopEdgeSettings.updateConfig { it.copy(closeDelayMs = v.toLong()) } }

    if (rowVisible("Side panel animation speed", "Fast, normal or slow slide")) {
        EdgeInfoBlock("Animation speed", "How fast the panel slides. \"Reduce animation\" in Appearance makes it jump instead.") {
            EdgeChoices(
                EdgeSpeed.entries.map { s ->
                    (s.name.lowercase().replaceFirstChar { c -> c.uppercase() }) to (config.speed == s)
                },
            ) { index -> DesktopEdgeSettings.updateConfig { it.copy(speed = EdgeSpeed.entries.getOrElse(index) { EdgeSpeed.NORMAL }) } }
        }
    }

    toggle(
        "Hide side panel while a full-screen app is active",
        "Videos and games in full screen are not interrupted by the panel opening.",
        config.suppressWhenFullscreen,
    ) { on -> DesktopEdgeSettings.updateConfig { it.copy(suppressWhenFullscreen = on) } }

    toggle(
        "Low power edge watcher",
        "Checks the pointer every 150 ms instead of every 80 ms while hidden. Uses less power; the panel may react a little later.",
        config.lowPower,
    ) { on -> DesktopEdgeSettings.updateConfig { it.copy(lowPower = on) } }

    toggle(
        "Pin side panel by default",
        "The panel stays open once shown until you unpin it or press Hide.",
        config.pinByDefault,
    ) { on -> DesktopEdgeSettings.updateConfig { it.copy(pinByDefault = on) } }

    if (rowVisible("Side panel trigger edge", "Where the edge zone is, shortcut and tray")) {
        EdgeInfoBlock(
            "Where is the trigger?",
            DesktopEdgeSettingsCodec.zoneReminder(config) +
                " Ctrl+Shift+P switches the mode on and off inside Muses; the tray icon has Side panel mode and Show panel now.",
        )
    }
}

@Composable
private fun EdgeSlider(
    title: String,
    subtitle: String,
    valueLabel: String,
    value: Int,
    range: IntRange,
    step: Int,
    rowVisible: @Composable (title: String, subtitle: String) -> Boolean,
    onCommit: (Int) -> Unit,
) {
    if (!rowVisible("Side panel $title", subtitle)) return
    var drag by remember(value) { mutableStateOf(value.toFloat()) }
    val shown = edgeSnap(drag, range, step)
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (shown == value) valueLabel else shown.toString(), color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        DesktopBareSlider(
            value = drag,
            onValueChange = { drag = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            onValueChangeFinished = { onCommit(edgeSnap(drag, range, step)) },
        )
    }
}

/** A slider position as an allowed integer: rounded to [step] from the range start, kept in range. */
internal fun edgeSnap(raw: Float, range: IntRange, step: Int): Int {
    val s = step.coerceAtLeast(1)
    val n = ((raw - range.first) / s).roundToInt()
    return (range.first + n * s).coerceIn(range.first, range.last)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EdgeChoices(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, (label, selected) ->
            FilterChip(
                colors = desktopChipColors(),
                selected = selected,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun EdgeInfoBlock(
    title: String,
    text: String,
    content: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(text, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
