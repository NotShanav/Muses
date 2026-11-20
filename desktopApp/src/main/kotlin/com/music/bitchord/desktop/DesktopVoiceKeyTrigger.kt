package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/*
 * Voice: a single plain key (for example "/" or "Z") as the trigger, while the Muses window has focus.
 * A plain key cannot be a global hotkey (it would steal typing in every program), so this one only
 * listens inside Muses and stays silent while any text field is focused. The global combination in
 * DesktopVoiceHotkey is unchanged and keeps working in the tray.
 */

/** Which text fields currently hold keyboard focus. Fed by [trackTextFocus]. */
internal object DesktopTextFocus {
    private val holders = HashSet<Any>()

    fun enter(token: Any) = synchronized(holders) { holders.add(token); Unit }
    fun leave(token: Any) = synchronized(holders) { holders.remove(token); Unit }
    val active: Boolean get() = synchronized(holders) { holders.isNotEmpty() }
}

/** Put on every text field so single-key shortcuts never fire while the person is typing. */
internal fun Modifier.trackTextFocus(): Modifier = composed {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { DesktopTextFocus.leave(token) } }
    this.onFocusChanged { if (it.isFocused) DesktopTextFocus.enter(token) else DesktopTextFocus.leave(token) }
}

internal object DesktopVoiceKeyTrigger {
    @Volatile private var down = false
    @Volatile private var lastEventNanos = 0L

    /** Called first from the window's key handler. True when the event was the trigger and is consumed. */
    fun onKey(event: KeyEvent): Boolean {
        val code = DesktopVoiceSettings.triggerKey.value ?: return false
        if (!DesktopVoiceSettings.enabled.value) return false
        if (event.key.keyCode != code) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (DesktopTextFocus.active) return false
        val controller = DesktopVoiceRuntime.controller.value ?: return false
        val now = System.nanoTime()
        when (event.type) {
            KeyEventType.KeyDown -> {
                // A lost KeyUp (window lost focus while held) must not block the next press forever.
                val stale = (now - lastEventNanos) / 1_000_000L > 1_200L
                lastEventNanos = now
                if (down && !stale) return true // auto-repeat of a held key
                down = true
                val tap = DesktopVoiceSettings.triggerTap.value
                controller.onKeyDown(if (tap) DesktopVoiceSettings.tapSeconds.value * 1000L else 0L)
                return true
            }
            KeyEventType.KeyUp -> {
                if (!down) return false
                down = false
                lastEventNanos = now
                controller.onReleased()
                return true
            }
            else -> return false
        }
    }

    fun label(code: Long): String {
        val key = Key(code)
        voiceVkFromComposeKey(key)?.let { vk -> VoiceHotkey.keyName(vk)?.let { return it } }
        return when (key) {
            Key.Slash -> "/"
            Key.Backslash -> "\\"
            Key.Period -> "."
            Key.Comma -> ","
            Key.Semicolon -> ";"
            Key.Apostrophe -> "'"
            Key.Grave -> "`"
            Key.Minus -> "-"
            Key.Equals -> "="
            Key.LeftBracket -> "["
            Key.RightBracket -> "]"
            else -> key.toString()
        }
    }
}

@Composable
internal fun VoiceKeyTriggerBlock() {
    val code by DesktopVoiceSettings.triggerKey.collectAsState()
    val tap by DesktopVoiceSettings.triggerTap.collectAsState()
    val seconds by DesktopVoiceSettings.tapSeconds.collectAsState()
    var capturing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    Column(Modifier.padding(vertical = 10.dp)) {
        Text("Single-key trigger", style = MaterialTheme.typography.bodyLarge)
        Text(
            "One key (for example / or Z) starts voice while Muses is focused. It does nothing while you type in a text box. " +
                "No modifier keys needed.",
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (capturing) "Press a key..." else code?.let { DesktopVoiceKeyTrigger.label(it) } ?: "Off",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (capturing) DesktopAccent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!capturing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Escape) {
                            capturing = false
                            return@onPreviewKeyEvent true
                        }
                        // Modifiers on their own are not a trigger.
                        if (event.key in setOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight)) {
                            return@onPreviewKeyEvent true
                        }
                        DesktopVoiceSettings.setTriggerKey(event.key.keyCode)
                        capturing = false
                        true
                    }
                    .clickable { capturing = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            TextButton(onClick = { capturing = false; DesktopVoiceSettings.setTriggerKey(null) }) { Text("Clear") }
        }
        if (capturing) Text("Press Esc to cancel.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                colors = desktopChipColors(),
                selected = !tap,
                onClick = { DesktopVoiceSettings.setTriggerTap(false) },
                label = { Text("Hold to speak") },
            )
            FilterChip(
                colors = desktopChipColors(),
                selected = tap,
                onClick = { DesktopVoiceSettings.setTriggerTap(true) },
                label = { Text("Tap once, then speak") },
            )
        }
        if (tap) {
            Text(
                "Speak within $seconds s after the beep. Tap the key again to finish sooner.",
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 8, 12).forEach { n ->
                    FilterChip(
                        colors = desktopChipColors(),
                        selected = seconds == n,
                        onClick = { DesktopVoiceSettings.setTapSeconds(n) },
                        label = { Text("$n s") },
                    )
                }
            }
        }
    }
}
