package com.music.bitchord.desktop

/*
 * BUDS models: per-model data, keyed by the product id from 0x8103 (for example "064414").
 * Every value traces to QuickBuds assets/models.json and docs/PROTOCOL.md sections 14 and 21.3.
 */

/** What one ANC report bit means: a mode, and for the level bits the level. A plain ANC bit has no level. */
internal data class BudsAncReport(val mode: BudsNoiseMode, val level: BudsAncLevel? = null)

internal class BudsModelProfile(
    val productId: String?,
    /** Feature ids of the periodic status read (models.json `statusQuery`). */
    val statusIds: List<Int>,
    /** REPORT slot (bit position) to meaning. Parents and children of the model's ancModes tree, as the firmware reports them. */
    private val ancReportBySlot: Map<Int, BudsAncReport>,
    /** SET slot (bit position) of each level this model has. Empty = no level row. */
    val ancLevelSlots: Map<BudsAncLevel, Int>,
    /** Built-in equalizer presets: the 0x0406 id (models.json `eqPresets` slot) and the label. Empty = no equalizer row. */
    val eqPresets: List<BudsEqPreset> = emptyList(),
    /** Switches this model shows, in display order (id from PROTOCOL.md section 16). Only ids the buds answer are shown. */
    val switches: List<BudsSwitch> = emptyList(),
    /** True when the firmware version (0x0105) is read after init. */
    val readsFirmware: Boolean = false,
    /** models.json `customEq`: the buds keep personal equalizer presets (listed with 0x0122, selected with 0x0418). */
    val customEq: Boolean = false,
) {
    val ancLevels: Set<BudsAncLevel> get() = ancLevelSlots.keys

    /** The lowest set bit names the mode (QuickBuds AncModes.modeForRaw); null when the model's tree has no such bit. */
    fun decodeAnc(mask: Int): BudsAncReport? {
        if (mask <= 0) return null
        return ancReportBySlot[Integer.numberOfTrailingZeros(mask)]
    }

    /** `01 01 <mask LE>` that selects [level], or null when this model has no such level. */
    fun levelSetPayload(level: BudsAncLevel): ByteArray? {
        val slot = ancLevelSlots[level] ?: return null
        return BudsFrames.setAncBitPayload(slot)
    }
}

internal object BudsModelProfiles {
    private val OFF = BudsAncReport(BudsNoiseMode.OFF)
    private val ANC = BudsAncReport(BudsNoiseMode.NOISE_CANCELLING)
    private val TRANSPARENCY = BudsAncReport(BudsNoiseMode.TRANSPARENCY)
    private val ADAPTIVE = BudsAncReport(BudsNoiseMode.ADAPTIVE)

    /** Tree of the Buds 4 style (section 21.3): Off 0 (child 3), ANC 1, Transparency 2 (child 8), levels 4/5/6/7. */
    private fun tree(withAdaptive: Boolean): Map<Int, BudsAncReport> {
        val m = LinkedHashMap<Int, BudsAncReport>()
        m[0] = OFF
        m[3] = OFF
        m[1] = ANC
        m[2] = TRANSPARENCY
        m[8] = TRANSPARENCY
        m[4] = BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.HIGH)       // mode 4, Deep
        m[5] = BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.MEDIUM)     // mode 8, Medium
        m[6] = BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.LOW)        // mode 3, Light
        m[7] = BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.SMART)      // mode 7, Smart
        if (withAdaptive) m[11] = ADAPTIVE
        return m
    }

    /** Used for a product id that is not listed (or not read yet): modes decode, but no level row is offered. */
    val DEFAULT = BudsModelProfile(
        productId = null,
        statusIds = BudsIds.STATUS_FEATURE_IDS,
        ancReportBySlot = tree(withAdaptive = true),
        ancLevelSlots = emptyMap(),
    )

    /** OnePlus Nord Buds 3 Pro: models.json ancModes, slots 7 Smart, 4 Deep, 5 Medium, 6 Light; no Adaptive. */
    private val NORD_BUDS_3_PRO = BudsModelProfile(
        productId = "064414",
        statusIds = listOf(0x05, 0x04, 0x0B, 0x0C, 0x11, 0x18, 0x06, 0x1B, 0x1D),
        ancReportBySlot = tree(withAdaptive = false),
        ancLevelSlots = linkedMapOf(
            BudsAncLevel.LOW to 6,
            BudsAncLevel.MEDIUM to 5,
            BudsAncLevel.HIGH to 4,
            BudsAncLevel.SMART to 7,
        ),
        // models.json eqPresets: mode 11 slot 3, mode 14 slot 4, mode 12 slot 5; names from QuickBuds EqActivity / strings.xml.
        eqPresets = listOf(
            BudsEqPreset(3, "Balanced"),
            BudsEqPreset(4, "Clear Vocals"),
            BudsEqPreset(5, "Bass"),
        ),
        // Display order of the popup. Labels from strings.xml (row_hires_title, row_spatial_title, row_dual_title,
        // row_wear_title as "Auto play/pause", eq_bass_boost). Low latency (06) stays in its own row.
        switches = listOf(
            BudsSwitch(BudsIds.FEATURE_HIRES, "High-quality audio", null),
            BudsSwitch(BudsIds.FEATURE_SPATIAL, "3D audio", null),
            BudsSwitch(BudsIds.FEATURE_DUAL, "Dual connection", null),
            BudsSwitch(BudsIds.FEATURE_WEAR, "Auto play/pause", null),
            BudsSwitch(BudsIds.FEATURE_BASSWAVE, "Bass boost", null),
        ),
        readsFirmware = true,
        customEq = true,                                   // models.json "customEq":1 for 064414
    )

    private val BY_ID: Map<String, BudsModelProfile> = listOf(NORD_BUDS_3_PRO).associateBy { it.productId!! }

    fun forProduct(productId: String?): BudsModelProfile = productId?.let { BY_ID[it.uppercase()] } ?: DEFAULT
}
