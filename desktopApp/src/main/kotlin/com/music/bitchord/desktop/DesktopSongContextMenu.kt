package com.music.bitchord.desktop

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * UI U1: calls [onSecondaryPress] when the secondary (right) mouse button goes down on this
 * element, and consumes that press so a `clickable` further in does not also play the row.
 * Place it BEFORE `.clickable` in the modifier chain.
 */
internal fun Modifier.onSecondaryPress(onSecondaryPress: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onSecondaryPress)
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    current.value()
                }
            }
        }
    }
}
