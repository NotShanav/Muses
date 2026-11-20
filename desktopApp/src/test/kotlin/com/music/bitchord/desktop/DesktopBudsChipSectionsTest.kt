package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS U1-U3: the pure decisions behind the new popup rows (ANC level, equalizer, switches, firmware). No Bluetooth, window or network. */
class DesktopBudsChipSectionsTest {
    private val allModes = setOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY)
    private val allLevels = setOf(BudsAncLevel.LOW, BudsAncLevel.MEDIUM, BudsAncLevel.HIGH, BudsAncLevel.SMART)
    private val eq = BudsEqState(listOf(BudsEqPreset(3, "Balanced"), BudsEqPreset(4, "Clear Vocals"), BudsEqPreset(5, "Bass")), 3)
    private val switches = listOf(BudsSwitch(0x18, "High-quality audio", false), BudsSwitch(0x1B, "3D audio", true))

    private fun state(
        mode: BudsNoiseMode? = null,
        modes: Set<BudsNoiseMode> = emptySet(),
        battery: BudsBattery = BudsBattery.EMPTY,
        latency: Boolean = false,
        extras: BudsExtras = BudsExtras(),
    ) = BudsState(
        connected = true, productId = null, battery = battery, inEar = null, noiseMode = mode,
        supportedNoiseModes = modes, lowLatency = null, lowLatencySupported = latency, extras = extras,
    )

    // ---- ANC level row

    @Test
    fun ancLevelsAreOrderedLowMediumHighSmartAndFilteredByWhatTheBudsOffer() {
        assertEquals(
            listOf(BudsAncLevel.LOW, BudsAncLevel.MEDIUM, BudsAncLevel.HIGH, BudsAncLevel.SMART),
            BudsChipLogic.visibleAncLevels(BudsExtras(ancLevels = allLevels)),
        )
        assertEquals(
            listOf(BudsAncLevel.MEDIUM, BudsAncLevel.SMART),
            BudsChipLogic.visibleAncLevels(BudsExtras(ancLevels = setOf(BudsAncLevel.SMART, BudsAncLevel.MEDIUM))),
        )
        assertTrue(BudsChipLogic.visibleAncLevels(BudsExtras()).isEmpty())
    }

    @Test
    fun ancLevelLabelsAreShortEnglish() {
        assertEquals(
            listOf("Low", "Medium", "High", "Smart"),
            BudsAncLevel.values().map { BudsChipLogic.ancLevelLabel(it) },
        )
    }

    @Test
    fun ancLevelRowShowsOnlyForNoiseCancellingWithLevels() {
        val withLevels = BudsExtras(ancLevels = allLevels)
        assertTrue(BudsChipLogic.ancLevelRowVisible(withLevels, BudsNoiseMode.NOISE_CANCELLING))
        assertFalse(BudsChipLogic.ancLevelRowVisible(withLevels, BudsNoiseMode.OFF))
        assertFalse(BudsChipLogic.ancLevelRowVisible(withLevels, BudsNoiseMode.TRANSPARENCY))
        assertFalse(BudsChipLogic.ancLevelRowVisible(withLevels, null))
        assertFalse(BudsChipLogic.ancLevelRowVisible(BudsExtras(), BudsNoiseMode.NOISE_CANCELLING))
    }

    @Test
    fun ancLevelRowFollowsThePendingModeNotOnlyTheRealOne() {
        val s = state(mode = BudsNoiseMode.OFF, modes = allModes, extras = BudsExtras(ancLevels = allLevels))
        assertTrue(BudsChipLogic.sections(s).ancLevels.isEmpty())
        assertEquals(4, BudsChipLogic.sections(s, shownMode = BudsNoiseMode.NOISE_CANCELLING).ancLevels.size)
    }

    // ---- equalizer row

    @Test
    fun eqRowIsHiddenWhenThereIsNoEqualizerOrNoPresets() {
        assertNull(BudsChipLogic.eqRow(BudsExtras()))
        assertNull(BudsChipLogic.eqRow(BudsExtras(eq = BudsEqState(emptyList(), null))))
        assertNotNull(BudsChipLogic.eqRow(BudsExtras(eq = eq)))
    }

    @Test
    fun eqRowScrollsSidewaysOnlyAboveFourPresets() {
        assertFalse(BudsChipLogic.eqScrolls(0))
        assertFalse(BudsChipLogic.eqScrolls(3))
        assertFalse(BudsChipLogic.eqScrolls(4))
        assertTrue(BudsChipLogic.eqScrolls(5))
    }

    @Test
    fun personalPresetsGoInTheirOwnRowAfterTheBuiltInOnes() {
        val builtIn = listOf(BudsEqPreset(3, "Balanced"), BudsEqPreset(4, "Clear Vocals"))
        val personal = listOf(BudsEqPreset(BUDS_CUSTOM_EQ_ID_BASE + 6, "Gym"), BudsEqPreset(BUDS_CUSTOM_EQ_ID_BASE + 7, "Night"))
        assertEquals(Pair(builtIn, personal), BudsChipLogic.splitEq(builtIn + personal))
        val none = emptyList<BudsEqPreset>()
        assertEquals(Pair(builtIn, none), BudsChipLogic.splitEq(builtIn))
        assertEquals(Pair(none, personal), BudsChipLogic.splitEq(personal))
        assertEquals(Pair(none, none), BudsChipLogic.splitEq(none))
    }

    @Test
    fun aBuiltInIdIsNeverTakenForAPersonalOne() {
        // Built-in ids stay far below the personal base, so no built-in preset can land in the "My presets" row.
        assertTrue((0..0xFF).all { id -> BudsChipLogic.splitEq(listOf(BudsEqPreset(id, "x"))).second.isEmpty() })
    }

    // ---- switches

    @Test
    fun switchPositionIsPendingThenReal_unknownCountsAsOff() {
        assertTrue(BudsChipLogic.switchChecked(BudsSwitch(1, "x", false), pending = true))
        assertFalse(BudsChipLogic.switchChecked(BudsSwitch(1, "x", true), pending = false))
        assertTrue(BudsChipLogic.switchChecked(BudsSwitch(1, "x", true), pending = null))
        assertFalse(BudsChipLogic.switchChecked(BudsSwitch(1, "x", false), pending = null))
        assertFalse(BudsChipLogic.switchChecked(BudsSwitch(1, "x", null), pending = null))
    }

    @Test
    fun aSwitchWithAnUnknownValueIsNotClickable() {
        assertFalse(BudsChipLogic.switchEnabled(BudsSwitch(1, "x", null)))
        assertTrue(BudsChipLogic.switchEnabled(BudsSwitch(1, "x", false)))
        assertTrue(BudsChipLogic.switchEnabled(BudsSwitch(1, "x", true)))
    }

    @Test
    fun onlyThe3dAndHighQualitySwitchesHaveANote() {
        assertNotNull(BudsChipLogic.switchNote(0x1B))
        assertNotNull(BudsChipLogic.switchNote(0x18))
        for (id in listOf(0x11, 0x04, 0x1D, 0x00, 0xFF)) assertNull(BudsChipLogic.switchNote(id), "id $id")
    }

    // ---- firmware and battery

    @Test
    fun firmwareLineIsHiddenWhenUnknownOrBlank() {
        assertEquals("Firmware 138.138.105", BudsChipLogic.firmwareLine("138.138.105"))
        assertEquals("Firmware 1.2", BudsChipLogic.firmwareLine("  1.2 "))
        assertNull(BudsChipLogic.firmwareLine(null))
        assertNull(BudsChipLogic.firmwareLine(""))
        assertNull(BudsChipLogic.firmwareLine("   "))
    }

    @Test
    fun batterySectionNeedsAtLeastOneKnownLevel() {
        assertFalse(BudsChipLogic.showBattery(BudsBattery.EMPTY))
        assertTrue(BudsChipLogic.showBattery(BudsBattery(null, null, 40, false, false, false)))
        assertTrue(BudsChipLogic.showBattery(BudsBattery(10, null, null, false, false, false)))
    }

    // ---- timing

    @Test
    fun pendingTimeoutCoversTheSlowestEngineAction() {
        // The engine needs up to about 2.5 s per action and up to about 5 s for the 3D / High-quality audio pair.
        assertTrue(BudsChipLogic.PENDING_TIMEOUT_MS > 5_000L)
    }

    // ---- the highlight always follows the buds

    @Test
    fun genericPendingShowsTheClickUntilTheBudsReportItThenTheRealValue() {
        assertEquals(BudsAncLevel.HIGH, BudsChipLogic.shown(BudsAncLevel.LOW, BudsAncLevel.HIGH))
        assertFalse(BudsChipLogic.settled(BudsAncLevel.LOW, BudsAncLevel.HIGH))
        assertTrue(BudsChipLogic.settled(BudsAncLevel.HIGH, BudsAncLevel.HIGH))
        assertTrue(BudsChipLogic.settled<BudsAncLevel>(null, null))
        assertEquals(5, BudsChipLogic.shown(3, 5))
        assertEquals(3, BudsChipLogic.shown(3, null))
        assertEquals(false, BudsChipLogic.shown(true, false)) // a pending "off" must not fall through to the real "on"
        assertNull(BudsChipLogic.shown<Int>(null, null))
    }

    @Test
    fun afterSettlingOrTimingOutTheUiShowsTheRealStateExactly() {
        // Timeout case: the buds never confirmed, pending is cleared, the shown value is the real one again.
        val real = BudsAncLevel.LOW
        val pendingCleared: BudsAncLevel? = null
        assertEquals(real, BudsChipLogic.shown(real, pendingCleared))
        val eqCleared: Int? = null
        assertEquals(3, BudsChipLogic.shown(eq.selectedId, eqCleared))
        // Unknown real value stays unknown: nothing is invented.
        assertNull(BudsChipLogic.shown<BudsAncLevel>(null, null))
    }

    @Test
    fun aChangeFromTheBudsMovesEveryHighlightWithoutAClick() {
        // No pending anywhere (nobody clicked): a gesture or the phone app changes the buds, the UI follows each step.
        for (mode in listOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.OFF)) {
            assertEquals(mode, BudsChipLogic.shownMode(mode, null))
        }
        for (level in BudsAncLevel.values()) {
            assertEquals(level, BudsChipLogic.shown(level, null))
        }
        for (preset in eq.presets) {
            val s = state(extras = BudsExtras(eq = eq.copy(selectedId = preset.id)))
            assertEquals(preset.id, BudsChipLogic.shown(BudsChipLogic.eqRow(s.extras)?.selectedId, null))
        }
        for (on in listOf(true, false, true)) {
            assertEquals(on, BudsChipLogic.switchChecked(BudsSwitch(1, "x", on), null))
        }
    }

    @Test
    fun aGestureThatLeavesNoiseCancellingHidesTheLevelRowAtOnce() {
        val extras = BudsExtras(ancLevels = allLevels, ancLevel = BudsAncLevel.HIGH)
        val before = state(mode = BudsNoiseMode.NOISE_CANCELLING, modes = allModes, extras = extras)
        val after = before.copy(noiseMode = BudsNoiseMode.TRANSPARENCY)
        assertEquals(4, BudsChipLogic.sections(before).ancLevels.size)
        assertTrue(BudsChipLogic.sections(after).ancLevels.isEmpty())
    }

    // ---- preview

    @Test
    fun previewShowsEveryRowIncludingADimmedSwitch() {
        val s = BudsChipLogic.previewState().state
        val sec = BudsChipLogic.sections(s)
        assertTrue(sec.battery && sec.noise && sec.sound)
        assertEquals(4, sec.ancLevels.size)
        assertEquals(5, sec.eq?.presets?.size)
        assertEquals(2, BudsChipLogic.splitEq(sec.eq?.presets.orEmpty()).second.size)
        assertEquals(5, sec.switches.size)
        assertTrue(sec.switches.any { it.on == null })
        assertTrue(sec.lowLatency)
        assertEquals("Firmware 138.138.105", sec.firmware)
    }

    // ---- table of combinations

    private data class Case(
        val name: String,
        val s: BudsState,
        val noise: Boolean,
        val levels: Int,
        val eqRow: Boolean,
        val switchCount: Int,
        val sound: Boolean,
        val firmware: Boolean,
        val battery: Boolean,
    )

    @Test
    fun sectionsForManyCombinationsOfStateData() {
        val ancOnly = BudsExtras(ancLevels = allLevels, ancLevel = BudsAncLevel.MEDIUM)
        val cases = listOf(
            Case("all empty", BudsState.INITIAL, false, 0, false, 0, false, false, false),
            Case("only battery", state(battery = BudsBattery(50, 60, 70, false, false, false)), false, 0, false, 0, false, false, true),
            Case("only ANC", state(BudsNoiseMode.NOISE_CANCELLING, allModes, extras = ancOnly), true, 4, false, 0, false, false, false),
            Case("ANC levels but mode off", state(BudsNoiseMode.OFF, allModes, extras = ancOnly), true, 0, false, 0, false, false, false),
            Case("ANC levels but no modes reported", state(BudsNoiseMode.NOISE_CANCELLING, emptySet(), extras = ancOnly), false, 0, false, 0, false, false, false),
            Case("ANC mode, no levels", state(BudsNoiseMode.NOISE_CANCELLING, allModes), true, 0, false, 0, false, false, false),
            Case("only EQ", state(extras = BudsExtras(eq = eq)), false, 0, true, 0, true, false, false),
            Case("EQ with no presets", state(extras = BudsExtras(eq = BudsEqState(emptyList(), null))), false, 0, false, 0, false, false, false),
            Case("only switches", state(extras = BudsExtras(switches = switches)), false, 0, false, 2, true, false, false),
            Case("only low latency", state(latency = true), false, 0, false, 0, true, false, false),
            Case("only firmware", state(extras = BudsExtras(firmware = "1.0.0")), false, 0, false, 0, false, true, false),
            Case("blank firmware", state(extras = BudsExtras(firmware = " ")), false, 0, false, 0, false, false, false),
            Case(
                "everything",
                state(BudsNoiseMode.NOISE_CANCELLING, allModes, BudsBattery(1, 2, 3, false, false, false), true, BudsExtras(allLevels, BudsAncLevel.LOW, eq, switches, "9.9")),
                true, 4, true, 2, true, true, true,
            ),
        )
        for (c in cases) {
            val sec = BudsChipLogic.sections(c.s)
            assertEquals(c.noise, sec.noise, "${c.name}: noise")
            assertEquals(c.levels, sec.ancLevels.size, "${c.name}: levels")
            assertEquals(c.eqRow, sec.eq != null, "${c.name}: eq")
            assertEquals(c.switchCount, sec.switches.size, "${c.name}: switches")
            assertEquals(c.sound, sec.sound, "${c.name}: sound")
            assertEquals(c.firmware, sec.firmware != null, "${c.name}: firmware")
            assertEquals(c.battery, sec.battery, "${c.name}: battery")
        }
    }

    @Test
    fun sectionsPassTheSwitchListThroughInOrderAndNeverInventOne() {
        val s = state(extras = BudsExtras(switches = switches))
        assertEquals(switches, BudsChipLogic.sections(s).switches)
        assertTrue(BudsChipLogic.sections(state()).switches.isEmpty())
    }
}
