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

/**
 * Personal equalizer presets travel inside [BudsEqState.presets] too: their [BudsEqPreset.id] is this base plus the
 * buds' own preset id, so they can never collide with a built-in id (which is below this base).
 * [BudsEqState.selectedId] uses the same numbering. `setEqPreset(id)` accepts both kinds. No signature changed.
 */
internal const val BUDS_CUSTOM_EQ_ID_BASE = 0x100
