package com.music.bitchord.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/*
 * EDGE 2: the one Compose-side piece of the side panel. Put on the root of the window; it only
 * tells the controller about presses and about the pointer entering or leaving the window, and does
 * nothing at all while the mode is off. It consumes nothing.
 */
internal fun Modifier.edgePanelPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!DesktopEdgePanel.isPanelMode) continue
            when (event.type) {
                PointerEventType.Press -> DesktopEdgePanel.onPointerPress()
                PointerEventType.Enter -> DesktopEdgePanel.onPointerInside(true)
                PointerEventType.Exit -> DesktopEdgePanel.onPointerInside(false)
                else -> Unit
            }
        }
    }
}
