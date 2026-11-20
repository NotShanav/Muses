package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.key
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

    /**
     * How long a change shows "setting..." before the real state is shown again. The engine needs up to about 2.5 s per
     * action and up to about 5 s for the 3D / High-quality audio pair, so this stays above 5 s.
     */
    const val PENDING_TIMEOUT_MS = 6_000L

    /** More equalizer presets than this scroll sideways instead of sharing the row. */
    const val EQ_SCROLL_THRESHOLD = 4

    /** Feature ids (DesktopBudsModels) that have a known-issue note under their switch. */
    const val SWITCH_HIGH_QUALITY_AUDIO = 0x18
    const val SWITCH_AUDIO_3D = 0x1B

    /** Fixed display order of the ANC levels. */
    private val LEVEL_ORDER = listOf(BudsAncLevel.LOW, BudsAncLevel.MEDIUM, BudsAncLevel.HIGH, BudsAncLevel.SMART)

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


    /** What is drawn: the pending value while a change is on its way, else exactly what the buds reported. */
    fun <T : Any> shown(real: T?, pending: T?): T? = pending ?: real

    /** A pending change is over once the buds report it. (A timeout ends it too, in the composable.) */
    fun <T : Any> settled(real: T?, pending: T?): Boolean = pending == null || real == pending

    /** The ANC levels the buds offer, in the order Low, Medium, High, Smart. */
    fun visibleAncLevels(extras: BudsExtras): List<BudsAncLevel> = LEVEL_ORDER.filter { it in extras.ancLevels }

    /** The level row only exists while noise cancelling is the shown mode and the buds offer levels. */
    fun ancLevelRowVisible(extras: BudsExtras, shownMode: BudsNoiseMode?): Boolean =
        shownMode == BudsNoiseMode.NOISE_CANCELLING && extras.ancLevels.isNotEmpty()

    fun ancLevelLabel(level: BudsAncLevel): String = when (level) {
        BudsAncLevel.LOW -> "Low"
        BudsAncLevel.MEDIUM -> "Medium"
        BudsAncLevel.HIGH -> "High"
        BudsAncLevel.SMART -> "Smart"
    }

    /** The equalizer to draw, or null (no row) when there is none or it has no presets. */
    fun eqRow(extras: BudsExtras): BudsEqState? = extras.eq?.takeIf { it.presets.isNotEmpty() }

    fun eqScrolls(presetCount: Int): Boolean = presetCount > EQ_SCROLL_THRESHOLD

    /** The built-in presets and the personal ones (ids from [BUDS_CUSTOM_EQ_ID_BASE]) as two lists, each in the engine's order. */
    fun splitEq(presets: List<BudsEqPreset>): Pair<List<BudsEqPreset>, List<BudsEqPreset>> =
        presets.partition { it.id < BUDS_CUSTOM_EQ_ID_BASE }

    /** The switch position to draw: the pending value, else the real one (unknown counts as off). */
    fun switchChecked(sw: BudsSwitch, pending: Boolean?): Boolean = pending ?: (sw.on == true)

    /** A switch with an unknown value is dimmed and cannot be clicked. */
    fun switchEnabled(sw: BudsSwitch): Boolean = sw.on != null

    /** Optional one-line note under a switch. Default none; only the two switches with a known issue on the Nord Buds 3 Pro. */
    fun switchNote(id: Int): String? = when (id) {
        SWITCH_AUDIO_3D -> "Works, but the sound may not change"
        SWITCH_HIGH_QUALITY_AUDIO -> "Needs LHDC or LDAC; a PC is often refused"
        else -> null
    }

    fun showBattery(b: BudsBattery): Boolean = b.left != null || b.right != null || b.case != null

    /** Firmware line, or null when unknown (the line is not drawn). */
    fun firmwareLine(firmware: String?): String? = firmware?.trim()?.takeIf { it.isNotEmpty() }?.let { "Firmware $it" }

    /**
     * Everything the popup decides to draw, in one pure place: an empty part means its row or section is not drawn.
     * [shownMode] is the mode being highlighted (pending or real), because the level row follows what is shown.
     */
    fun sections(state: BudsState, shownMode: BudsNoiseMode? = state.noiseMode): BudsChipSections {
        val modes = visibleModes(state)
        return BudsChipSections(
            battery = showBattery(state.battery),
            wearHint = wearHint(state.inEar),
            modes = modes,
            ancLevels = if (modes.isNotEmpty() && ancLevelRowVisible(state.extras, shownMode)) visibleAncLevels(state.extras) else emptyList(),
            eq = eqRow(state.extras),
            switches = state.extras.switches,
            lowLatency = state.lowLatencySupported,
            firmware = firmwareLine(state.extras.firmware),
        )
    }

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
            extras = BudsExtras(
                ancLevels = setOf(BudsAncLevel.LOW, BudsAncLevel.MEDIUM, BudsAncLevel.HIGH, BudsAncLevel.SMART),
                ancLevel = BudsAncLevel.MEDIUM,
                eq = BudsEqState(
                    presets = listOf(
                        BudsEqPreset(3, "Balanced"), BudsEqPreset(4, "Clear Vocals"), BudsEqPreset(5, "Bass"),
                        BudsEqPreset(BUDS_CUSTOM_EQ_ID_BASE + 1, "Gym"), BudsEqPreset(BUDS_CUSTOM_EQ_ID_BASE + 2, "Night"),
                    ),
                    selectedId = 3,
                ),
                switches = listOf(
                    BudsSwitch(0x18, "High-quality audio", false),
                    BudsSwitch(0x1B, "3D audio", true),
                    BudsSwitch(0x11, "Dual connection", true),
                    BudsSwitch(0x04, "Auto play/pause", true),
                    BudsSwitch(0x1D, "Bass boost", null),
                ),
                firmware = "138.138.105",
            ),
        ),
    )
}

/** What the popup draws (see [BudsChipLogic.sections]). Empty or null means: that row is not drawn. */
internal data class BudsChipSections(
    val battery: Boolean,
    val wearHint: String?,
    val modes: List<BudsNoiseMode>,
    val ancLevels: List<BudsAncLevel>,
    val eq: BudsEqState?,
    val switches: List<BudsSwitch>,
    val lowLatency: Boolean,
    val firmware: String?,
) {
    val noise: Boolean get() = modes.isNotEmpty()
    val sound: Boolean get() = eq != null || switches.isNotEmpty() || lowLatency
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
            onSetAncLevel = { level -> scope.launch { DesktopBudsRuntime.controller.setAncLevel(level) } },
            onSetEq = { id -> scope.launch { DesktopBudsRuntime.controller.setEqPreset(id) } },
            onSetSwitch = { id, on -> scope.launch { DesktopBudsRuntime.controller.setSwitch(id, on) } },
        )
    }
}

@Composable
internal fun DesktopBudsChip(
    ui: BudsUiState,
    onSetNoise: (BudsNoiseMode) -> Unit,
    onSetLowLatency: (Boolean) -> Unit,
    modifier: Modifier,
    onSetAncLevel: (BudsAncLevel) -> Unit = {},
    onSetEq: (Int) -> Unit = {},
    onSetSwitch: (Int, Boolean) -> Unit = { _, _ -> },
) {
    if (ui is BudsUiState.Connected) {
        BudsChipBody(ui.name, ui.state, onSetNoise, onSetLowLatency, onSetAncLevel, onSetEq, onSetSwitch, modifier)
    }
}

@Composable
private fun BudsChipBody(
    name: String,
    state: BudsState,
    onSetNoise: (BudsNoiseMode) -> Unit,
    onSetLowLatency: (Boolean) -> Unit,
    onSetAncLevel: (BudsAncLevel) -> Unit,
    onSetEq: (Int) -> Unit,
    onSetSwitch: (Int, Boolean) -> Unit,
    modifier: Modifier,
) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    var open by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<BudsNoiseMode?>(null) }
    var pendingLatency by remember { mutableStateOf<Boolean?>(null) }
    var pendingLevel by remember { mutableStateOf<BudsAncLevel?>(null) }
    var pendingEq by remember { mutableStateOf<Int?>(null) }

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
    LaunchedEffect(pendingLevel, state.extras.ancLevel) {
        if (pendingLevel != null) {
            if (BudsChipLogic.settled(state.extras.ancLevel, pendingLevel)) {
                pendingLevel = null
            } else {
                delay(BudsChipLogic.PENDING_TIMEOUT_MS)
                pendingLevel = null
            }
        }
    }
    LaunchedEffect(pendingEq, state.extras.eq?.selectedId) {
        if (pendingEq != null) {
            if (BudsChipLogic.settled(state.extras.eq?.selectedId, pendingEq)) {
                pendingEq = null
            } else {
                delay(BudsChipLogic.PENDING_TIMEOUT_MS)
                pendingEq = null
            }
        }
    }

    val low = BudsChipLogic.lowestBattery(state.battery)
    val tint = Color.White.copy(alpha = 0.78f)
    val anyPending = pendingMode != null || pendingLatency != null || pendingLevel != null || pendingEq != null
    val settingAlpha by animateFloatAsState(
        targetValue = if (anyPending) 1f else 0f,
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
            val shownMode = BudsChipLogic.shownMode(state.noiseMode, pendingMode)
            val sec = BudsChipLogic.sections(state, shownMode)
            // Same width as before; a max height and a vertical scroll keep a long popup inside the screen and clear of the pill.
            Column(
                Modifier
                    .width(300.dp)
                    .heightIn(max = BUDS_POPUP_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)

                if (sec.battery || sec.wearHint != null) {
                    BudsSection("Battery") {
                        BudsBatteryRow("Left", state.battery.left, state.battery.leftCharging)
                        BudsBatteryRow("Right", state.battery.right, state.battery.rightCharging)
                        BudsBatteryRow("Case", state.battery.case, state.battery.caseCharging)
                        sec.wearHint?.let { Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }

                if (sec.noise) {
                    BudsSection("Noise control") {
                        BudsSegmented(
                            items = sec.modes,
                            selected = shownMode,
                            label = { BudsChipLogic.modeShortLabel(it) },
                            onPick = { mode ->
                                if (mode != state.noiseMode || pendingMode != null) {
                                    pendingMode = mode
                                    onSetNoise(mode)
                                }
                            },
                        )
                        val line = if (pendingMode != null) "Setting..." else state.noiseMode?.let { BudsChipLogic.modeLongLabel(it) }
                        if (line != null) Text(line, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        if (sec.ancLevels.isNotEmpty()) {
                            Text("Cancelling level", color = DesktopSecondary, style = MaterialTheme.typography.labelMedium)
                            BudsSegmented(
                                items = sec.ancLevels,
                                selected = BudsChipLogic.shown(state.extras.ancLevel, pendingLevel),
                                label = { BudsChipLogic.ancLevelLabel(it) },
                                onPick = { level ->
                                    if (level != state.extras.ancLevel || pendingLevel != null) {
                                        pendingLevel = level
                                        onSetAncLevel(level)
                                    }
                                },
                            )
                            if (pendingLevel != null) Text("Setting...", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (sec.sound) {
                    BudsSection("Sound") {
                        val eq = sec.eq
                        if (eq != null) {
                            Text("Equalizer", color = DesktopSecondary, style = MaterialTheme.typography.labelMedium)
                            val (builtIn, personal) = BudsChipLogic.splitEq(eq.presets)
                            val pickEq = { id: Int ->
                                if (id != eq.selectedId || pendingEq != null) {
                                    pendingEq = id
                                    onSetEq(id)
                                }
                            }
                            val shownEq = BudsChipLogic.shown(eq.selectedId, pendingEq)
                            if (builtIn.isNotEmpty()) BudsEqRow(presets = builtIn, selectedId = shownEq, onPick = pickEq)
                            if (personal.isNotEmpty()) {
                                Text("My presets", color = DesktopSecondary, style = MaterialTheme.typography.labelMedium)
                                BudsEqRow(presets = personal, selectedId = shownEq, onPick = pickEq)
                            }
                            if (pendingEq != null) Text("Setting...", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        for (sw in sec.switches) {
                            key(sw.id) { BudsSwitchRow(sw, onSetSwitch) }
                        }
                        if (sec.lowLatency) {
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

                sec.firmware?.let { Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Highest the popup grows before it scrolls. */
private val BUDS_POPUP_MAX_HEIGHT = 420.dp

@Composable
private fun BudsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = DesktopSecondary, style = MaterialTheme.typography.labelMedium)
        content()
    }
}

/** A segmented control: equal-width cells, the selected one in the accent colour. */
@Composable
private fun <T> BudsSegmented(items: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in items) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (item == selected) DesktopAccent.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable { onPick(item) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Equalizer presets as chips: they share the row up to four, then the row scrolls sideways. */
@Composable
private fun BudsEqRow(presets: List<BudsEqPreset>, selectedId: Int?, onPick: (Int) -> Unit) {
    val scrolls = BudsChipLogic.eqScrolls(presets.size)
    val rowModifier = if (scrolls) Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in presets) {
            val chipModifier = if (scrolls) Modifier else Modifier.weight(1f)
            Box(
                chipModifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (preset.id == selectedId) DesktopAccent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f))
                    .clickable { onPick(preset.id) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One earbuds switch: label (and an optional note) left, switch right. Dimmed and not clickable while the value is unknown. */
@Composable
private fun BudsSwitchRow(sw: BudsSwitch, onSet: (Int, Boolean) -> Unit) {
    var pending by remember(sw.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pending, sw.on) {
        if (pending != null) {
            if (BudsChipLogic.settled(sw.on, pending)) {
                pending = null
            } else {
                delay(BudsChipLogic.PENDING_TIMEOUT_MS)
                pending = null
            }
        }
    }
    val enabled = BudsChipLogic.switchEnabled(sw)
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sw.label, style = MaterialTheme.typography.bodyMedium)
            val note = if (pending != null) "Setting..." else BudsChipLogic.switchNote(sw.id)
            if (note != null) Text(note, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = BudsChipLogic.switchChecked(sw, pending),
            onCheckedChange = { on ->
                pending = on
                onSet(sw.id, on)
            },
            enabled = enabled,
        )
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
