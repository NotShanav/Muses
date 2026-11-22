package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * WEB1: the "Muses Web (experimental)" rows of Settings > Advanced > Experimental. Same row pattern
 * as the voice and earbuds rows: the caller supplies the search filter and the toggle row.
 * The port is a stepper (not a text field) on purpose: no keyboard focus handling to get wrong.
 */
@Composable
internal fun DesktopWebSettingsRows(
    rowVisible: @Composable (title: String, subtitle: String) -> Boolean,
    toggle: @Composable (title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val enabled by DesktopWebSettings.enabled.collectAsState()

    toggle(
        "Muses Web (experimental)",
        "Serves a light version of Muses to a browser tab, run by this app: simple themes, no animated backdrops, " +
            "a click-to-speak button. The desktop app has to stay running (it can run in the background without its window). " +
            "Browser playback does not use the desktop audio engine (no equalizer, spatial audio or crossfade). " +
            "This is a personal server, not a website. Off by default: nothing listens until you turn this on.",
        enabled,
        DesktopWebSettings::setEnabled,
    )

    if (!enabled) return

    val status by DesktopWebRuntime.status.collectAsState()
    val port by DesktopWebSettings.port.collectAsState()
    val lan by DesktopWebSettings.lan.collectAsState()
    val background by DesktopWebSettings.background.collectAsState()
    val code by DesktopWebRuntime.pairingCode.collectAsState()

    if (rowVisible("Muses Web status", "Address of the web page and open in browser")) {
        val current = status
        WebInfoBlock("Muses Web status", WebSettingsText.statusLine(current)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = current is DesktopWebStatus.Running,
                    onClick = { DesktopWebRuntime.openInBrowser() },
                ) { Text("Open in browser") }
            }
        }
    }

    if (rowVisible("Muses Web port", "The port the web page listens on")) {
        WebInfoBlock(
            "Port",
            "Muses tries the next 20 ports when this one is busy. Allowed range ${DesktopWebSettingsLogic.MIN_PORT} to ${DesktopWebSettingsLogic.MAX_PORT}.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { DesktopWebSettings.setPort(port - 1) }) { Text("-") }
                Text(port.toString(), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { DesktopWebSettings.setPort(port + 1) }) { Text("+") }
                OutlinedButton(onClick = { DesktopWebSettings.setPort(DesktopWebSettingsLogic.DEFAULT_PORT) }) { Text("Reset") }
            }
        }
    }

    toggle(
        "Allow other devices on my network",
        "Lets a phone or tablet on your Wi-Fi open the page after entering the pairing code. " +
            "Only turn this on on a network you trust. The microphone does not work over plain http on another device.",
        lan,
        DesktopWebSettings::setLan,
    )

    if (lan && rowVisible("Muses Web pairing code", "Six digit code to enter once in the browser")) {
        WebInfoBlock(
            "Pairing code",
            "Enter this code on the other device the first time you open the page. It changes every time the app starts.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = { DesktopWebRuntime.newPairingCode() }) { Text("New code") }
            }
        }
    }

    toggle(
        "Start without the desktop window (background mode)",
        "At the next start Muses runs only the web server and a tray icon: no window and no animated backdrops are made, " +
            "which uses far less memory and battery. Use the tray menu to open Muses Web, show the desktop window or quit. " +
            "Starting Muses with --web does the same for one launch.",
        background,
        DesktopWebSettings::setBackground,
    )
}

/** The text of the status row, kept pure so it can be tested. */
internal object WebSettingsText {
    fun statusLine(status: DesktopWebStatus): String = when (status) {
        is DesktopWebStatus.Off -> "Starting up or turned off."
        is DesktopWebStatus.Failed -> status.message
        is DesktopWebStatus.Running -> buildString {
            append("Running at ").append(status.localUrl)
            if (status.lan) {
                append(if (status.lanUrl != null) "; on your network at ${status.lanUrl}" else "; on your network (address not found)")
            }
        }
    }
}

@Composable
private fun WebInfoBlock(
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
