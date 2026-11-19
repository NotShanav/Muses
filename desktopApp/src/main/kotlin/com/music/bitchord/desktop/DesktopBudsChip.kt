package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// BUDS B4: the earbuds chip in the pill. Pure decisions live in BudsChipLogic (unit tested); the composables only draw.

/** Lowest bud level shown on the chip, with the charging mark of that bud. */
internal data class BudsChipBattery(val percent: Int, val charging: Boolean)

internal object BudsChipLogic {
    /** The pill element id used in [DesktopPillConfig] zones and hiddenElements. */
    const val ELEMENT_ID = "buds"

    /** How long a noise-mode / latency change shows "setting..." before the real state is shown again. */
    const val PENDING_TIMEOUT_MS = 3_500L

    private val MODE_ORDER = listOf(
        BudsNoiseMode.OFF,
        BudsNoiseMode.NOISE_CANCELLING,
        BudsNoiseMode.TRANSPARENCY,
        BudsNoiseMode.ADAPTIVE,
    )

    /** The lowest level of the two buds (the case never counts); null when neither is known. A tie prefers the charging bud. */
    fun lowestBattery(b: BudsBattery): BudsChipBattery? {
        val left = b.left?.let { BudsChipBattery(it.coerceIn(0, 100), b.leftCharging) }
        val right = b.right?.let { BudsChipBattery(it.coerceIn(0, 100), b.rightCharging) }
        if (left == null) return right
        if (right == null) return left
        return when {
            left.percent < right.percent -> left
            right.percent < left.percent -> right
            else -> if (right.charging && !left.charging) right else left
        }
    }

    /** Modes the earbuds reported, in a fixed display order. Empty means: show no mode control. */
    fun visibleModes(state: BudsState): List<BudsNoiseMode> = MODE_ORDER.filter { it in state.supportedNoiseModes }

    fun modeShortLabel(mode: BudsNoiseMode): String = when (mode) {
        BudsNoiseMode.OFF -> "Off"
        BudsNoiseMode.NOISE_CANCELLING -> "ANC"
        BudsNoiseMode.TRANSPARENCY -> "Transparency"
        BudsNoiseMode.ADAPTIVE -> "Adaptive"
    }

    fun modeLongLabel(mode: BudsNoiseMode): String = when (mode) {
        BudsNoiseMode.OFF -> "Noise control off"
        BudsNoiseMode.NOISE_CANCELLING -> "Noise cancelling"
        BudsNoiseMode.TRANSPARENCY -> "Transparency"
        BudsNoiseMode.ADAPTIVE -> "Adaptive"
    }

    /** Text only: a hint when a bud is out of the ear or both are in the case. Null when both are in, or unknown. */
    fun wearHint(inEar: Pair<Boolean, Boolean>?): String? {
        if (inEar == null) return null
        val (left, right) = inEar
        return when {
            left && right -> null
            !left && !right -> "Not in ears / in case"
            !left -> "Left bud is not in the ear"
            else -> "Right bud is not in the ear"
        }
    }

    /** The mode to highlight: the one being set while a change is pending, else the real one. */
    fun shownMode(real: BudsNoiseMode?, pending: BudsNoiseMode?): BudsNoiseMode? = pending ?: real

    /** A pending mode change is settled once the read-back equals it. */
    fun modeSettled(real: BudsNoiseMode?, pending: BudsNoiseMode?): Boolean = pending == null || real == pending

    fun latencySettled(real: Boolean?, pending: Boolean?): Boolean = pending == null || real == pending

    /** Chip text: lowest percent, or a dash when no level is known yet. */
    fun chipText(b: BudsBattery): String = lowestBattery(b)?.let { "${it.percent}%" } ?: "--"

    /** Spoken description for the chip. */
    fun chipDescription(b: BudsBattery): String =
        lowestBattery(b)?.let { "Earbuds, ${it.percent} percent${if (it.charging) ", charging" else ""}" } ?: "Earbuds"

    /**
     * Old saved layouts have no earbuds element. A layout that does not place it in any zone gets it in the right zone
     * just before "more" (at the end when "more" is not there). Everything else stays as it was.
     */
    fun ensureElement(zones: Map<String, List<String>>): Map<String, List<String>> {
        if (zones.values.any { ELEMENT_ID in it }) return zones
        val right = zones["right"].orEmpty()
        val at = right.indexOf("more")
        val next = if (at >= 0) right.toMutableList().apply { add(at, ELEMENT_ID) } else right + ELEMENT_ID
        return zones + ("right" to next)
    }

    /** FAKE data for the Studio preview, so the chip can be arranged without earbuds. */
    fun previewState(): BudsUiState.Connected = BudsUiState.Connected(
        name = "Earbuds",
        state = BudsState(
            connected = true,
            productId = null,
            battery = BudsBattery(left = 82, right = 76, case = 90, leftCharging = false, rightCharging = false, caseCharging = true),
            inEar = Pair(true, true),
            noiseMode = BudsNoiseMode.NOISE_CANCELLING,
            supportedNoiseModes = setOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.ADAPTIVE),
            lowLatency = false,
            lowLatencySupported = true,
        ),
    )
}

/**
 * Pill element "buds": reads the one runtime controller (never creates another). [preview] shows FAKE data with no-op
 * callbacks (Studio). Draws nothing unless the earbuds are connected.
 */
@Composable
internal fun DesktopBudsPillSlot(preview: Boolean, modifier: Modifier = Modifier) {
    if (preview) {
        DesktopBudsChip(BudsChipLogic.previewState(), onSetNoise = {}, onSetLowLatency = {}, modifier = modifier)
    } else {
        val ui by DesktopBudsRuntime.controller.ui.collectAsState()
        val scope = rememberCoroutineScope()
        DesktopBudsChip(
            ui = ui,
            onSetNoise = { mode -> scope.launch { DesktopBudsRuntime.controller.setNoiseMode(mode) } },
            onSetLowLatency = { on -> scope.launch { DesktopBudsRuntime.controller.setLowLatency(on) } },
            modifier = modifier,
        )
    }
}

@Composable
internal fun DesktopBudsChip(
    ui: BudsUiState,
    onSetNoise: (BudsNoiseMode) -> Unit,
    onSetLowLatency: (Boolean) -> Unit,
    modifier: Modifier,
) {
    if (ui is BudsUiState.Connected) {
        BudsChipBody(ui.name, ui.state, onSetNoise, onSetLowLatency, modifier)
    }
}

@Composable
private fun BudsChipBody(
    name: String,
    state: BudsState,
    onSetNoise: (BudsNoiseMode) -> Unit,
    onSetLowLatency: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    var open by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<BudsNoiseMode?>(null) }
    var pendingLatency by remember { mutableStateOf<Boolean?>(null) }

    // A change shows "setting..." until the read-back matches, or until the timeout; then the REAL state is shown.
    LaunchedEffect(pendingMode, state.noiseMode) {
        if (pendingMode != null) {
            if (BudsChipLogic.modeSettled(state.noiseMode, pendingMode)) {
                pendingMode = null
            } else {
                delay(BudsChipLogic.PENDING_TIMEOUT_MS)
                pendingMode = null
            }
        }
    }
    LaunchedEffect(pendingLatency, state.lowLatency) {
        if (pendingLatency != null) {
            if (BudsChipLogic.latencySettled(state.lowLatency, pendingLatency)) {
                pendingLatency = null
            } else {
                delay(BudsChipLogic.PENDING_TIMEOUT_MS)
                pendingLatency = null
            }
        }
    }

    val low = BudsChipLogic.lowestBattery(state.battery)
    val tint = Color.White.copy(alpha = 0.78f)
    val settingAlpha by animateFloatAsState(
        targetValue = if (pendingMode != null || pendingLatency != null) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 160),
        label = "budsSetting",
    )

    Box(modifier) {
        Row(
            Modifier
                .alpha(1f - 0.35f * settingAlpha)
                .height(44.dp)
                .defaultMinSize(minWidth = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Rounded.Headphones, BudsChipLogic.chipDescription(state.battery), tint = tint, modifier = Modifier.size(18.dp))
            Text(BudsChipLogic.chipText(state.battery), color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (low?.charging == true) Text("\u26A1", color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (open) DismissOnDisplayChange { open = false }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(300.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BudsBatteryRow("Left", state.battery.left, state.battery.leftCharging)
                BudsBatteryRow("Right", state.battery.right, state.battery.rightCharging)
                BudsBatteryRow("Case", state.battery.case, state.battery.caseCharging)
                BudsChipLogic.wearHint(state.inEar)?.let {
                    Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                val modes = BudsChipLogic.visibleModes(state)
                if (modes.isNotEmpty()) {
                    val shown = BudsChipLogic.shownMode(state.noiseMode, pendingMode)
                    Text("Noise control", color = DesktopSecondary, style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (mode in modes) {
                            val selected = mode == shown
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) DesktopAccent.copy(alpha = 0.45f) else Color.Transparent)
                                    .clickable {
                                        if (mode != state.noiseMode || pendingMode != null) {
                                            pendingMode = mode
                                            onSetNoise(mode)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    BudsChipLogic.modeShortLabel(mode),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    val line = if (pendingMode != null) "Setting..." else state.noiseMode?.let { BudsChipLogic.modeLongLabel(it) }
                    if (line != null) Text(line, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (state.lowLatencySupported) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Low latency", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (pendingLatency != null) "Setting..." else "Less delay for video and games",
                                color = DesktopSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = pendingLatency ?: (state.lowLatency == true),
                            onCheckedChange = { on ->
                                pendingLatency = on
                                onSetLowLatency(on)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudsBatteryRow(label: String, percent: Int?, charging: Boolean) {
    if (percent == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            "${percent.coerceIn(0, 100)}%${if (charging) " \u26A1" else ""}",
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
