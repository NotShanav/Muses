package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons

/*
 * EDGE 3: the slim bar at the top of the side panel: app name, Pin, Expand (leave the mode and
 * restore the window) and Hide (slide out now). It replaces the title bar while the mode is on, so
 * the caption buttons and the drag-to-move area are gone; the panel is moved by its edge, not by
 * hand. Targets are 44 dp.
 */

private val HEADER_HEIGHT = 48.dp
private val HEADER_TARGET = 44.dp

/** The title bar's place in the window: the panel header in side panel mode, the normal bar otherwise. */
@Composable
internal fun DesktopEdgeTitleSlot() {
    val panelOn by DesktopEdgePanel.mode.collectAsState()
    if (panelOn) DesktopEdgePanelHeader() else DesktopTitleBar()
}

@Composable
internal fun DesktopEdgePanelHeader(modifier: Modifier = Modifier) {
    val view by DesktopEdgePanel.view.collectAsState()
    val values by DesktopEdgeSettings.values.collectAsState()
    val rightSide = values.config.side == EdgeSide.RIGHT
    Row(
        modifier = modifier.fillMaxWidth().height(HEADER_HEIGHT).desktopWindowGlass().padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            "Muses",
            color = DesktopSecondary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        DesktopSpringButton(onClick = DesktopEdgePanel::togglePin, size = HEADER_TARGET) {
            Icon(
                BitChordIcons.Pin,
                if (view.pinned) "Unpin side panel" else "Pin side panel open",
                tint = if (view.pinned) DesktopAccent else DesktopSecondary,
                modifier = Modifier.size(19.dp),
            )
        }
        DesktopSpringButton(onClick = DesktopEdgePanel::leavePanel, size = HEADER_TARGET) {
            Icon(Icons.Rounded.Fullscreen, "Expand to full window", tint = DesktopSecondary, modifier = Modifier.size(20.dp))
        }
        DesktopSpringButton(onClick = DesktopEdgePanel::hideNow, size = HEADER_TARGET) {
            // The chevron points at the edge the panel slides into.
            Icon(
                Icons.Rounded.ChevronLeft,
                "Hide side panel",
                tint = DesktopSecondary,
                modifier = Modifier.size(22.dp).rotate(if (rightSide) 180f else 0f),
            )
        }
    }
}

/** The in-app shortcut: Ctrl+Shift+P toggles side panel mode. Not a global hotkey; inert while the feature is off. */
internal object DesktopEdgeShortcut {

    /** True when [event] is the shortcut. Pure, so it can be reasoned about without a window. */
    fun matches(down: Boolean, ctrl: Boolean, shift: Boolean, alt: Boolean, isP: Boolean): Boolean =
        down && ctrl && shift && !alt && isP

    /** Returns true (consumes the key) only when the feature is on and the shortcut was pressed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!matches(event.type == KeyEventType.KeyDown, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.key == Key.P)) {
            return false
        }
        if (!DesktopEdgeSettings.values.value.enabled) return false
        DesktopEdgeSettings.togglePanelIfEnabled()
        return true
    }
}
