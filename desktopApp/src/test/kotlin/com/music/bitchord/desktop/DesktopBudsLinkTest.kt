package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The command bitmap, the answers the buds wait for, and per-model reads. Pure: no session, no Windows. */
class DesktopBudsLinkTest {

    private fun hex(s: String): ByteArray =
        s.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val buds4 = hex("00 FF 77 5A EA 67 0E 20 07")

    @Test
    fun bitmapOfBuds4EnablesWhatTheSessionSends() {
        val b = BudsBitmap.parse(buds4)!!
        for (cmd in listOf(0x0205, 0x010C, 0x0404, 0x0109, 0x0403)) assertTrue(b.supports(cmd), "0x%04X".format(cmd))
    }

    @Test
    fun aClearedBitBlocksItsCommandsOnly() {
        val b = BudsBitmap.parse(hex("00 EF 76 1A"))!!        // bits 4, 8 and 22 cleared
        assertFalse(b.supports(0x0109))                          // bit 4
        assertFalse(b.supports(0x010C))                          // bit 8
        assertFalse(b.supports(0x0404))                          // bit 8
        assertFalse(b.supports(0x0205))                          // bit 22
        assertTrue(b.supports(0x0403))                           // bit 7 still on
    }

    @Test
    fun alwaysEnabledAndUnlistedCommandsAreNeverBlocked() {
        val empty = BudsBitmap.parse(hex("00"))!!
        for (cmd in listOf(0x0100, 0x0103, 0x0106, 0x010D, 0x0999)) assertTrue(empty.supports(cmd), "0x%04X".format(cmd))
    }

    @Test
    fun aCommandWithTwoBitsNeedsOnlyOne() {
        assertTrue(BudsBitmap.parse(hex("00 00 00 08"))!!.supports(0x040D))        // bit 19
        assertTrue(BudsBitmap.parse(hex("00 00 00 00 80"))!!.supports(0x040D))     // bit 31
        assertFalse(BudsBitmap.parse(hex("00 00 00 00 00"))!!.supports(0x040D))
    }

    @Test
    fun aBitmapReplyThatIsNotSuccessfulIsNotParsed() {
        assertNull(BudsBitmap.parse(ByteArray(0)))
        assertNull(BudsBitmap.parse(hex("01 FF 77")))
    }

    @Test
    fun linkedPushAnswerIsAlwaysTheSame() = assertContentEquals(hex("00 F2"), BudsPushAnswers.linked())

    @Test
    fun diagnosticAnswerEchoesOnlyTheCmd() {
        assertContentEquals(hex("00 F4"), BudsPushAnswers.diagnostic(ByteArray(0)))
        assertContentEquals(
            byteArrayOf(0, 0xF4.toByte()) + "{\"cmd\":\"a2dp_caton\"}".toByteArray(),
            BudsPushAnswers.diagnostic("{\"cmd\":\"a2dp_caton\",\"ver\":\"1\"}".toByteArray()),
        )
        assertContentEquals(
            byteArrayOf(0, 0xF4.toByte()) + "{\"cmd\":5}".toByteArray(),
            BudsPushAnswers.diagnostic("{ \"ver\": 1, \"cmd\" : 5 }".toByteArray()),
        )
        assertContentEquals(byteArrayOf(0, 0xF4.toByte()) + "{}".toByteArray(), BudsPushAnswers.diagnostic("{\"ver\":\"1\"}".toByteArray()))
        assertContentEquals(hex("01 F4"), BudsPushAnswers.diagnostic("oops".toByteArray()))
    }

    @Test
    fun onlyRequestsOutsideGroupsOneToFiveAndZeroFAreUnknown() {
        assertTrue(BudsPushAnswers.isUnknownRequest(0x0600))
        assertTrue(BudsPushAnswers.isUnknownRequest(0x0700))
        assertFalse(BudsPushAnswers.isUnknownRequest(0xEF01))       // bit 15 set: looks like a reply, never answered (QuickBuds does the same)
        assertFalse(BudsPushAnswers.isUnknownRequest(0x0502))
        assertFalse(BudsPushAnswers.isUnknownRequest(0x0F00))
        assertFalse(BudsPushAnswers.isUnknownRequest(0x8600))       // a reply is never answered
    }

    @Test
    fun statusIdsFollowTheModelAndFallBackToTheDefault() {
        assertEquals(listOf(0x05, 0x04, 0x0B, 0x0C, 0x11, 0x18, 0x06, 0x1B, 0x1D), BudsModelQueries.statusIds("064414"))
        assertEquals(listOf(0x05, 0x04, 0x0B, 0x0C, 0x11, 0x18, 0x06, 0x1B, 0x1D), BudsModelQueries.statusIds("064414".lowercase()))
        assertEquals(BudsIds.STATUS_FEATURE_IDS, BudsModelQueries.statusIds("065414"))
        assertEquals(BudsIds.STATUS_FEATURE_IDS, BudsModelQueries.statusIds(null))
    }
}
