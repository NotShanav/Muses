package com.music.bitchord.desktop

/*
 * BUDS contract: the shared seam between the engine (Protocol, Session, Models) and the UI (Chip, SettingsUi).
 * Nobody changes a signature here. A new field or action goes into CONTRACT_REQUEST.md instead.
 */

internal enum class BudsAncLevel { LOW, MEDIUM, HIGH, SMART }

internal data class BudsEqPreset(val id: Int, val label: String)

internal data class BudsEqState(val presets: List<BudsEqPreset>, val selectedId: Int?)

internal data class BudsSwitch(val id: Int, val label: String, val on: Boolean?)

internal data class BudsExtras(
    val ancLevels: Set<BudsAncLevel> = emptySet(),
    val ancLevel: BudsAncLevel? = null,
    val eq: BudsEqState? = null,
    val switches: List<BudsSwitch> = emptyList(),
    val firmware: String? = null,
)
