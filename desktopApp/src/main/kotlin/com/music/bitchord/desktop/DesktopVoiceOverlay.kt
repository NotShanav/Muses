package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit
import kotlinx.coroutines.delay

/*
 * Voice commands, part V3: the overlay. A small undecorated, always-on-top, non-focusable window near
 * the bottom centre of the screen. It exists only while the controller is not IDLE (so it shows even
 * when the main window is hidden in the tray) and is disposed afterwards. The words it shows are the
 * controller's state for a moment; nothing here keeps them.
 */

private val PillText = Color.White
private val PillSubtle = Color.White.copy(alpha = 0.68f)
private val PillAccent = Color(0xFFFF6B6B)
private val PillChip = Color.White.copy(alpha = 0.14f)

/**
 * Call once from the application scope (`Main.kt`), next to the main window. Shows a window only
 * while there is a controller and it is busy or showing a result.
 */
@Composable
internal fun DesktopVoiceOverlayHost() {
    val controller by DesktopVoiceRuntime.controller.collectAsState()
    val current = controller
    if (current != null) DesktopVoiceOverlayFor(current)
}

@Composable
private fun DesktopVoiceOverlayFor(controller: DesktopVoiceController) {
    val state by controller.state.collectAsState()
    val shown = state
    if (shown is DesktopVoiceState.Idle) return

    val choiceCount = (shown as? DesktopVoiceState.Done)?.choices?.size ?: 0
    val heightDp = if (choiceCount > 0) CHOICES_HEIGHT_DP else PILL_HEIGHT_DP
    val windowState = rememberWindowState(width = WIDTH_DP.dp, height = heightDp.dp)
    Window(
        onCloseRequest = controller::cancel,
        state = windowState,
        title = "Muses voice",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val awtWindow = window
        LaunchedEffect(choiceCount) {
            windowState.size = DpSize(WIDTH_DP.dp, heightDp.dp)
            // Positioned in the window's own AWT units (the screen's bounds are in the same units), so
            // the display scale never enters into it.
            delay(80)
            placeNearBottomCentre(awtWindow)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            DesktopVoicePill(
                state = shown,
                onUndo = controller::undo,
                onChoose = controller::choose,
            )
        }
    }
}

private const val WIDTH_DP = 460
private const val PILL_HEIGHT_DP = 112
private const val CHOICES_HEIGHT_DP = 236

private fun placeNearBottomCentre(window: java.awt.Window) {
    runCatching {
        val config = window.graphicsConfiguration ?: return
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        val x = bounds.x + (bounds.width - w) / 2
        val y = bounds.y + bounds.height - insets.bottom - h - maxOf(24, h / 4)
        window.setLocation(x, y)
    }
}

/** The pill itself: the app's own glass look ([fluidGlass]), plain white text, no theme needed. */
@Composable
internal fun DesktopVoicePill(
    state: DesktopVoiceState,
    onUndo: () -> Unit,
    onChoose: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .fluidGlass(shape, tint = 0.86f)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            DesktopVoiceState.Idle -> Unit
            DesktopVoiceState.Listening -> StatusRow("Listening...", dot = true)
            DesktopVoiceState.Thinking -> StatusRow("Thinking...", dot = false)
            is DesktopVoiceState.Failed -> Text(
                state.message,
                color = PillText,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            is DesktopVoiceState.Done -> DonePart(state, onUndo, onChoose)
        }
    }
}

@Composable
private fun StatusRow(text: String, dot: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dot) Box(Modifier.size(10.dp).background(PillAccent, CircleShape))
        Text(text, color = PillText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DonePart(state: DesktopVoiceState.Done, onUndo: () -> Unit, onChoose: (Int) -> Unit) {
    if (state.heard.isNotBlank()) {
        Text(state.heard, color = PillSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.result,
            color = PillText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.canUndo) {
            Box(
                Modifier
                    .background(PillChip, RoundedCornerShape(14.dp))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Undo", color = PillText, fontSize = 13.sp) }
        }
    }
    state.choices.forEachIndexed { index, song ->
        Box(
            Modifier
                .fillMaxWidth()
                .background(PillChip, RoundedCornerShape(14.dp))
                .clickable { onChoose(index) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (song.artist.isBlank()) song.title else song.title + " - " + song.artist,
                color = PillText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
