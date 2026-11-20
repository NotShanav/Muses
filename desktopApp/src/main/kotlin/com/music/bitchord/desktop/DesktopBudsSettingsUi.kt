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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * BUDS B3: the "Earbuds (experimental)" rows of Settings > Advanced > Experimental. Same row pattern as
 * the voice rows: the caller supplies the search filter and the toggle row.
 */

/** Credits link shown in the settings (the project the protocol knowledge comes from). */
internal const val BUDS_CREDIT_URL = "https://github.com/spizganed/QuickBuds"

@Composable
internal fun DesktopBudsSettingsRows(
    rowVisible: @Composable (title: String, subtitle: String) -> Boolean,
    toggle: @Composable (title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val enabled by DesktopBudsSettings.enabled.collectAsState()

    toggle(
        "Earbuds (experimental)",
        "Battery, noise control, equalizer and switches for OnePlus, OPPO, realme and DIZO earbuds, shown as a chip in the floating pill. " +
            "Pair them in Windows Bluetooth settings first; Muses never pairs. Only one app can control the earbuds at a time. " +
            "Off by default: nothing is scanned or opened until you turn this on.",
        enabled,
        DesktopBudsSettings::setEnabled,
    )

    if (!enabled) return

    val ui by DesktopBudsRuntime.controller.ui.collectAsState()
    val auto by DesktopBudsSettings.autoConnect.collectAsState()
    val debug by DesktopBudsSettings.debugFrames.collectAsState()

    if (rowVisible("Earbuds status", "Looking for connected earbuds")) {
        BudsInfoBlock("Earbuds status", BudsControllerLogic.statusLine(ui, enabled)) {
            val current = ui
            if (current is BudsUiState.Problem && current.code == BudsProblemCodes.FOUND) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DesktopBudsRuntime.controller.connectNow() }) { Text("Connect now") }
                }
            }
        }
    }

    toggle(
        "Auto connect",
        "Connect as soon as compatible earbuds are connected in Windows. When off, you press Connect now.",
        auto,
        DesktopBudsSettings::setAutoConnect,
    )

    toggle(
        "Debug frames",
        "Writes the bytes of control commands (noise mode, switches) to the app's error output. " +
            "Never writes device names or addresses. Off by default.",
        debug,
        DesktopBudsSettings::setDebugFrames,
    )

    if (rowVisible("Earbuds credits", "QuickBuds protocol documentation GPL-3.0")) {
        BudsInfoBlock(
            "Earbuds credits",
            "The earbuds protocol knowledge comes from the QuickBuds project (GPL-3.0) and the projects it credits. " +
                "OnePlus, OPPO, realme, DIZO and HeyMelody are trademarks of their owners; Muses is not affiliated with them.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { DesktopExternalLinks.open(BUDS_CREDIT_URL) }) { Text("Open QuickBuds on GitHub") }
            }
        }
    }
}

@Composable
private fun BudsInfoBlock(
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
