package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Muses Hub page (H6). Deliberately plain: the beta asks "does it play and is it supported", not
 * "does it look good". Everything shown comes from [HubController.state]; every action is a call on
 * the controller, launched from the page's coroutine scope (the controller's calls are suspend and
 * do their blocking work off the UI thread themselves).
 *
 * Colours are the shared Desktop* constants and the theme's typography. Nothing here touches playback.
 */

private const val HUB_TILES_PER_ROW = 3
private val HubWarnColor = Color(0xFFFFB74D)
private val HubErrorColor = Color(0xFFFF5252)
private val HubCardColor = Color.White.copy(alpha = 0.06f)

@Composable
internal fun DesktopHubPage(controller: HubController, contentPadding: PaddingValues) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    // While one action runs the buttons ignore clicks, so a double click cannot queue a second launch.
    var busy by remember { mutableStateOf(false) }
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // Leaving the page must not cancel a launch or close half way through: the
                // controller's operation always finishes, and the state it publishes stays true.
                withContext(NonCancellable) { block() }
            } finally {
                busy = false
            }
        }
    }

    // Fills the "Browser: ..." line; the controller also sets it after every successful launch.
    LaunchedEffect(controller) { controller.refreshBrowser() }

    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DesktopPageGutter,
                    end = DesktopPageGutter,
                    top = 8.dp,
                    bottom = 32.dp + contentPadding.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Media Hub", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "Beta. Each service opens in its own browser window, with the browser's own ad blocking.",
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )

            HubBrowserLine(state)
            HubMemoryBlock(state)

            state.message?.let { note ->
                HubMessageRow(note, onDismiss = { scope.launch { controller.clearMessage() } })
            }

            HubSectionTitle("Services")
            val tiles = state.services.filter { it.service.enabled }
            if (tiles.isEmpty()) {
                Text("No services are enabled.", style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
            } else {
                tiles.chunked(HUB_TILES_PER_ROW).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { item ->
                            HubTile(
                                item = item,
                                enabled = !busy,
                                onClick = { runAction { controller.activate(item.service.id) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Keeps the last row's tiles the same width as the others.
                        repeat(HUB_TILES_PER_ROW - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            HorizontalDivider(color = DesktopDivider)

            HubSectionTitle("Running")
            val running = state.services.filter { it.state != HubServiceState.HIBERNATED }
            if (running.isEmpty()) {
                Text("Nothing is open.", style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
            } else {
                running.forEach { item ->
                    HubRunningRow(
                        item = item,
                        enabled = !busy,
                        onClose = { runAction { controller.hibernate(item.service.id) } },
                    )
                }
                OutlinedButton(onClick = { runAction { controller.closeAll() } }, enabled = !busy) {
                    Text("Close all")
                }
            }

            HorizontalDivider(color = DesktopDivider)

            HubBetaLimits(state)
        }
    }
}

@Composable
private fun HubSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HubBrowserLine(state: HubControllerState) {
    val name = state.browserName
    Text(
        text = if (name != null) "Browser: $name" else "Browser: none found. Install Brave, Chrome or Edge.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (name != null) Color.White else HubWarnColor,
    )
}

@Composable
private fun HubMemoryBlock(state: HubControllerState) {
    val sample = state.sample
    val cap = state.settings.validated().memoryCapMb
    val warn = state.settings.validated().memoryWarnMb
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sample == null) {
            Text(
                "Memory: nothing open (limit $cap MB for Muses and all services together)",
                style = MaterialTheme.typography.bodyMedium,
                color = DesktopSecondary,
            )
        } else if (!sample.measured) {
            Text(
                "Memory: not measured on this system",
                style = MaterialTheme.typography.bodyMedium,
                color = DesktopSecondary,
            )
        } else {
            val barColor = when (state.memoryLevel) {
                MemoryLevel.NORMAL -> DesktopAccent
                MemoryLevel.WARNING -> HubWarnColor
                MemoryLevel.OVER_CAP -> HubErrorColor
            }
            Text(
                "Memory: ${sample.totalMb} MB / $cap MB   (Muses ${sample.musesMb} MB, services ${sample.webMb} MB)",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            val fraction = if (cap > 0) (sample.totalMb.toFloat() / cap.toFloat()).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(DesktopDivider)) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(barColor))
            }
            when (state.memoryLevel) {
                MemoryLevel.NORMAL -> Unit
                MemoryLevel.WARNING -> Text(
                    "Above the warning level ($warn MB).",
                    style = MaterialTheme.typography.bodySmall,
                    color = HubWarnColor,
                )
                MemoryLevel.OVER_CAP -> Text(
                    "Above the limit ($cap MB). Close a service to free memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HubErrorColor,
                )
            }
        }
    }
}

@Composable
private fun HubMessageRow(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HubCardColor).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun HubTile(item: HubServiceView, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val open = item.state != HubServiceState.HIBERNATED
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (open) DesktopAccent.copy(alpha = 0.18f) else HubCardColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            item.service.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            hubStateLabel(item.state),
            style = MaterialTheme.typography.bodySmall,
            color = if (open) Color.White else DesktopSecondary,
        )
    }
}

@Composable
private fun HubRunningRow(item: HubServiceView, enabled: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.service.name, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hubStateLabel(item.state), style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        }
        TextButton(onClick = onClose, enabled = enabled) { Text("Close") }
    }
}

@Composable
private fun HubBetaLimits(state: HubControllerState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HubSectionTitle("Beta limits")
        val lines = buildList {
            add("Closing a service closes its window. Opening it again starts at its home page; the playback position is not restored.")
            if (!state.canCloseSingleWindow) add("Only one service is open at a time for now: opening another closes the current one.")
            add("Muses cannot bring an open service's window to the front. Use the taskbar to switch to it.")
            add("Ad blocking is done by the browser (for Brave: Shields). On YouTube it can stop working at any time.")
            add("Memory is the browser's working set, which can read higher than Task Manager.")
        }
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        }
    }
}

private fun hubStateLabel(state: HubServiceState): String = when (state) {
    HubServiceState.HIBERNATED -> "Not open"
    HubServiceState.LIVE -> "Open in the background"
    HubServiceState.ACTIVE -> "Open"
}
