package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BUDS E1: the per-model profile and the mode decoding it drives (pure, no session). */
class DesktopBudsModelsTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val nord = BudsModelProfiles.forProduct("064414")

    @Test
    fun theNordBudsProfileDecodesEveryBitOfItsTree() {
        assertEquals(BudsAncReport(BudsNoiseMode.OFF), nord.decodeAnc(0x0001))
        assertEquals(BudsAncReport(BudsNoiseMode.OFF), nord.decodeAnc(0x0008))
        assertEquals(BudsAncReport(BudsNoiseMode.NOISE_CANCELLING), nord.decodeAnc(0x0002))
        assertEquals(BudsAncReport(BudsNoiseMode.TRANSPARENCY), nord.decodeAnc(0x0004))
        assertEquals(BudsAncReport(BudsNoiseMode.TRANSPARENCY), nord.decodeAnc(0x0100))
        assertEquals(BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.HIGH), nord.decodeAnc(0x0010))
        assertEquals(BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.MEDIUM), nord.decodeAnc(0x0020))
        assertEquals(BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.LOW), nord.decodeAnc(0x0040))
        assertEquals(BudsAncReport(BudsNoiseMode.NOISE_CANCELLING, BudsAncLevel.SMART), nord.decodeAnc(0x0080))
    }

    @Test
    fun theNordBudsHaveNoAdaptiveAndNoBitForZero() {
        assertNull(nord.decodeAnc(0x0800))
        assertNull(nord.decodeAnc(0))
        assertNull(nord.decodeAnc(-1))
        assertEquals(BudsNoiseMode.ADAPTIVE, BudsModelProfiles.DEFAULT.decodeAnc(0x0800)?.mode)
    }

    @Test
    fun theLowestSetBitNamesTheMode() {
        assertEquals(BudsNoiseMode.OFF, nord.decodeAnc(0x0009)?.mode)
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, nord.decodeAnc(0x0102)?.mode)
    }

    @Test
    fun levelSetPayloadsUseTheModelsSlots() {
        assertContentEquals(hex("01 01 10"), nord.levelSetPayload(BudsAncLevel.HIGH))
        assertContentEquals(hex("01 01 20"), nord.levelSetPayload(BudsAncLevel.MEDIUM))
        assertContentEquals(hex("01 01 40"), nord.levelSetPayload(BudsAncLevel.LOW))
        assertContentEquals(hex("01 01 80"), nord.levelSetPayload(BudsAncLevel.SMART))
        assertNull(BudsModelProfiles.DEFAULT.levelSetPayload(BudsAncLevel.HIGH))
    }

    @Test
    fun setAncBitPayloadUsesTheFewestMaskBytes() {
        assertContentEquals(hex("01 01 01"), BudsFrames.setAncBitPayload(0))
        assertContentEquals(hex("01 01 02"), BudsFrames.setAncBitPayload(1))
        assertContentEquals(hex("01 01 80"), BudsFrames.setAncBitPayload(7))
        assertContentEquals(hex("01 01 00 01"), BudsFrames.setAncBitPayload(8))
        assertContentEquals(hex("01 01 00 08"), BudsFrames.setAncBitPayload(11))
    }

    @Test
    fun lookupIgnoresCaseAndFallsBackToTheDefault() {
        assertSame(nord, BudsModelProfiles.forProduct("064414"))
        assertEquals(setOf(BudsAncLevel.LOW, BudsAncLevel.MEDIUM, BudsAncLevel.HIGH, BudsAncLevel.SMART), nord.ancLevels)
        assertSame(BudsModelProfiles.DEFAULT, BudsModelProfiles.forProduct("065414"))
        assertSame(BudsModelProfiles.DEFAULT, BudsModelProfiles.forProduct(null))
        assertTrue(BudsModelProfiles.DEFAULT.ancLevels.isEmpty())
        assertEquals(BudsIds.STATUS_FEATURE_IDS, BudsModelProfiles.DEFAULT.statusIds)
    }
}
