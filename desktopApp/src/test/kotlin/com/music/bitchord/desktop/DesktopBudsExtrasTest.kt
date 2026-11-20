package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS E2/E3: the parsers for the equalizer and firmware replies, and the Nord Buds 3 Pro profile data. */
class DesktopBudsExtrasTest {

    private fun bytes(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun eqReplyReadsTheIdAfterTheStatusByte() {
        assertEquals(4, BudsParsers.eqReply(bytes("00 04")))
        assertNull(BudsParsers.eqReply(bytes("01 04")))
        assertNull(BudsParsers.eqReply(bytes("00")))
        assertNull(BudsParsers.eqReply(ByteArray(0)))
    }

    @Test
    fun eqPushReadsTheFirstByte() {
        assertEquals(5, BudsParsers.eqPush(bytes("05")))
        assertNull(BudsParsers.eqPush(ByteArray(0)))
    }

    @Test
    fun firmwareJoinsTheTypeTwoVersionsWithDots() {
        val text = "1,2,138,2,2,138,3,1,01,3,2,105"
        assertEquals("138.138.105", BudsParsers.firmware(byteArrayOf(0, 4) + text.toByteArray()))
    }

    @Test
    fun firmwareReturnsNullForRefusalsAndJunk() {
        assertNull(BudsParsers.firmware(byteArrayOf(1, 4) + "1,2,138".toByteArray()))
        assertNull(BudsParsers.firmware(byteArrayOf(0, 1)))
        assertNull(BudsParsers.firmware(byteArrayOf(0, 1) + "1,1,5".toByteArray()))       // no type-2 version
        assertNull(BudsParsers.firmware(byteArrayOf(0, 1) + "1,2,a b".toByteArray()))     // not a version
        assertNull(BudsParsers.firmware(ByteArray(0)))
    }

    @Test
    fun theNordProfileCarriesItsEqualizerSwitchesAndFirmwareFlag() {
        val p = BudsModelProfiles.forProduct("064414")
        assertEquals(listOf(3 to "Balanced", 4 to "Clear Vocals", 5 to "Bass"), p.eqPresets.map { it.id to it.label })
        assertEquals(listOf(0x18, 0x1B, 0x11, 0x04, 0x1D), p.switches.map { it.id })
        assertEquals(true, p.readsFirmware)
        val d = BudsModelProfiles.DEFAULT
        assertTrue(d.eqPresets.isEmpty())
        assertTrue(d.switches.isEmpty())
        assertEquals(false, d.readsFirmware)
    }
}
