package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS B4: the pure decisions behind the earbuds chip and the new pill element. No Bluetooth, window or network. */
class DesktopBudsChipTest {
    private fun battery(l: Int?, r: Int?, c: Int? = null, lc: Boolean = false, rc: Boolean = false, cc: Boolean = false) =
        BudsBattery(left = l, right = r, case = c, leftCharging = lc, rightCharging = rc, caseCharging = cc)

    private fun state(modes: Set<BudsNoiseMode>) = BudsState(
        connected = true, productId = null, battery = battery(50, 60), inEar = null, noiseMode = null,
        supportedNoiseModes = modes, lowLatency = null, lowLatencySupported = false,
    )

    @Test
    fun lowestBatteryPicksTheWeakerBudAndIgnoresTheCase() {
        assertEquals(BudsChipBattery(40, false), BudsChipLogic.lowestBattery(battery(80, 40, c = 5)))
        assertEquals(BudsChipBattery(40, true), BudsChipLogic.lowestBattery(battery(40, 80, lc = true)))
    }

    @Test
    fun lowestBatteryWithOneBudOrNone() {
        assertEquals(BudsChipBattery(70, false), BudsChipLogic.lowestBattery(battery(null, 70)))
        assertEquals(BudsChipBattery(33, true), BudsChipLogic.lowestBattery(battery(33, null, lc = true)))
        assertNull(BudsChipLogic.lowestBattery(battery(null, null, c = 90)))
        assertEquals("--", BudsChipLogic.chipText(battery(null, null, c = 90)))
        assertEquals("55%", BudsChipLogic.chipText(battery(55, 90)))
    }

    @Test
    fun lowestBatteryTieAndClamp() {
        assertEquals(BudsChipBattery(50, true), BudsChipLogic.lowestBattery(battery(50, 50, rc = true)))
        assertEquals(BudsChipBattery(50, false), BudsChipLogic.lowestBattery(battery(50, 50)))
        assertEquals(BudsChipBattery(100, false), BudsChipLogic.lowestBattery(battery(250, 300)))
    }

    @Test
    fun visibleModesAreOnlyTheReportedOnesInFixedOrder() {
        val all = setOf(BudsNoiseMode.ADAPTIVE, BudsNoiseMode.OFF, BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.NOISE_CANCELLING)
        assertEquals(
            listOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.ADAPTIVE),
            BudsChipLogic.visibleModes(state(all)),
        )
        assertEquals(
            listOf(BudsNoiseMode.OFF, BudsNoiseMode.TRANSPARENCY),
            BudsChipLogic.visibleModes(state(setOf(BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.OFF))),
        )
        assertTrue(BudsChipLogic.visibleModes(state(emptySet())).isEmpty())
    }

    @Test
    fun pendingModeShowsUntilReadBackMatches() {
        assertEquals(BudsNoiseMode.TRANSPARENCY, BudsChipLogic.shownMode(BudsNoiseMode.OFF, BudsNoiseMode.TRANSPARENCY))
        assertEquals(BudsNoiseMode.OFF, BudsChipLogic.shownMode(BudsNoiseMode.OFF, null))
        assertEquals(false, BudsChipLogic.modeSettled(BudsNoiseMode.OFF, BudsNoiseMode.TRANSPARENCY))
        assertEquals(true, BudsChipLogic.modeSettled(BudsNoiseMode.TRANSPARENCY, BudsNoiseMode.TRANSPARENCY))
        assertEquals(true, BudsChipLogic.modeSettled(null, null))
        assertEquals(false, BudsChipLogic.latencySettled(false, true))
        assertEquals(true, BudsChipLogic.latencySettled(true, true))
        assertEquals(true, BudsChipLogic.latencySettled(null, null))
    }

    @Test
    fun wearHintIsTextOnly() {
        assertNull(BudsChipLogic.wearHint(null))
        assertNull(BudsChipLogic.wearHint(Pair(true, true)))
        assertEquals("Not in ears / in case", BudsChipLogic.wearHint(Pair(false, false)))
        assertEquals("Left bud is not in the ear", BudsChipLogic.wearHint(Pair(false, true)))
        assertEquals("Right bud is not in the ear", BudsChipLogic.wearHint(Pair(true, false)))
    }

    @Test
    fun previewStateIsConnectedWithEveryControl() {
        val s = BudsChipLogic.previewState().state
        assertTrue(s.connected && s.lowLatencySupported)
        assertEquals(4, BudsChipLogic.visibleModes(s).size)
        assertEquals("76%", BudsChipLogic.chipText(s.battery))
    }

    @Test
    fun ensureElementInsertsBeforeMoreOnly_whenMissing() {
        val old = mapOf("left" to listOf("artwork"), "center" to listOf("metadata"), "right" to listOf("previous", "play", "next", "more"))
        val fixed = BudsChipLogic.ensureElement(old)
        assertEquals(listOf("previous", "play", "next", "buds", "more"), fixed["right"])
        assertEquals(fixed, BudsChipLogic.ensureElement(fixed))
        val noMore = BudsChipLogic.ensureElement(mapOf("right" to listOf("play")))
        assertEquals(listOf("play", "buds"), noMore["right"])
        val leftPlaced = mapOf("left" to listOf("buds"), "right" to listOf("play", "more"))
        assertEquals(leftPlaced, BudsChipLogic.ensureElement(leftPlaced))
    }

    @Test
    fun defaultConfigPlacesTheChipRightBeforeMore() {
        val right = DesktopPillConfig().validated().zones.getValue("right")
        assertEquals(right.indexOf("more") - 1, right.indexOf("buds"))
    }

    @Test
    fun oldSavedConfigWithoutTheElementStillLoadsAndGainsIt() {
        val loaded = DesktopPillPresets.import(
            """{"schemaVersion":1,"zones":{"left":["artwork"],"center":["metadata"],"right":["previous","play","next","shuffle","repeat","more"]}}""",
        )
        assertNotNull(loaded)
        val right = loaded.zones.getValue("right")
        assertEquals(listOf("previous", "play", "next", "shuffle", "repeat", "buds", "more"), right)
        assertTrue("buds" !in loaded.hiddenElements)
    }

    @Test
    fun configRoundTripKeepsAHiddenOrMovedChip() {
        val moved = DesktopPillConfig(
            zones = mapOf("left" to listOf("artwork", "buds"), "center" to listOf("metadata"), "right" to listOf("play", "more")),
            hiddenElements = setOf("buds"),
        ).validated()
        val back = DesktopPillPresets.import(DesktopPillPresets.export(moved))
        assertNotNull(back)
        assertEquals(moved.copy(userPresets = emptyMap()), back)
        assertEquals(listOf("artwork", "buds"), back.zones.getValue("left"))
        assertTrue("buds" in back.hiddenElements)
    }

    @Test
    fun builtInPresetsKeepTheChip() {
        for (name in DesktopPillPresets.BuiltIn) {
            val applied = DesktopPillPresets.apply(name, DesktopPillConfig().validated())
            assertNotNull(applied, name)
            assertTrue(applied.zones.values.any { "buds" in it }, name)
        }
    }
}
