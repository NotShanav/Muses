package com.music.bitchord.desktop

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS B1: frames, LEB128, streaming parser and payload decoders. Pure: no Bluetooth, window or network. */
class DesktopBudsProtocolTest {

    private fun hx(s: String): ByteArray =
        s.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun parseAll(bytes: ByteArray): List<BudsFrame> = BudsFrameParser().feed(bytes)

    // ── worked examples, byte for byte ───────────────────────────────────────────────────────────

    @Test
    fun handshakeFrameMatchesTheWorkedExample() {
        assertContentEquals(hx("AA 07 00 00 00 01 01 00 00"), BudsFrames.handshake(1))
    }

    @Test
    fun setNoiseOffFrameMatchesTheWorkedExample() {
        assertContentEquals(hx("AA 0A 00 00 04 04 06 03 00 01 01 01"), BudsFrames.setAnc(6, BudsNoiseMode.OFF))
    }

    @Test
    fun ancPushFrameMatchesTheWorkedExample() {
        val built = BudsFrames.build(0x0204, 0xFF, hx("03 01 01 08 00"))
        assertContentEquals(hx("AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00"), built)
        val f = parseAll(built).single()
        assertEquals(0x0204, f.cmd)
        assertTrue(f.isPush)
        assertContentEquals(hx("03 01 01 08 00"), f.payload)
    }

    @Test
    fun otherBuildersUseTheDocumentedPayloads() {
        assertContentEquals(hx("AA 0B 00 00 04 04 07 04 00 01 01 00 08"), BudsFrames.setAnc(7, BudsNoiseMode.ADAPTIVE))
        assertContentEquals(hx("01 01 02"), BudsFrames.setAncPayload(BudsNoiseMode.NOISE_CANCELLING))
        assertContentEquals(hx("01 01 04"), BudsFrames.setAncPayload(BudsNoiseMode.TRANSPARENCY))
        assertContentEquals(hx("AA 0B 00 00 05 02 05 04 00 03 01 02 03"), BudsFrames.registerPushes(5, listOf(1, 2, 3)))
        assertContentEquals(hx("AA 0B 00 00 0D 01 09 04 00 03 05 06 28"), BudsFrames.statusQuery(9))
        assertContentEquals(hx("AA 09 00 00 03 04 03 02 00 06 01"), BudsFrames.featureSwitch(3, 0x06, true))
        assertContentEquals(hx("AA 09 00 00 0C 01 02 02 00 01 01"), BudsFrames.ancQuery(2))
    }

    @Test
    fun replyToARequestSetsBit15AndKeepsTheSeq() {
        val f = parseAll(BudsFrames.reply(0x0500, 7, hx("00 04 03 02 01"))).single()
        assertEquals(0x8500, f.cmd)
        assertEquals(7, f.seq)
        assertTrue(f.isReply)
        assertEquals(0x0500, f.baseCmd)
    }

    // ── LEB128 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun lebEncodesShortAndLongForms() {
        assertContentEquals(hx("7F"), BudsLeb128.encode(127))
        assertContentEquals(hx("80 01"), BudsLeb128.encode(128))
        assertContentEquals(hx("83 01"), BudsLeb128.encode(131))      // a 124-byte payload (section 2)
        assertContentEquals(hx("81 02"), BudsLeb128.encode(257))      // a 250-byte reply (section 2)
        assertContentEquals(hx("FF 7F"), BudsLeb128.encode(16383))
        assertContentEquals(hx("80 80 01"), BudsLeb128.encode(16384))
    }

    @Test
    fun lebRoundTrips() {
        val values = (0..5000).toList() + listOf(16383, 16384, 100000, 2097151)
        for (v in values) {
            val enc = BudsLeb128.encode(v)
            val dec = BudsLeb128.decode(enc, 0, enc.size)
            assertEquals(v, dec.value, "value $v")
            assertEquals(enc.size, dec.length, "length $v")
        }
    }

    @Test
    fun lebReportsIncompleteAndInvalid() {
        assertEquals(0, BudsLeb128.decode(hx("83"), 0, 1).length)
        assertEquals(0, BudsLeb128.decode(ByteArray(0), 0, 0).length)
        assertEquals(-1, BudsLeb128.decode(hx("80 80 80 01"), 0, 4).length)
    }

    @Test
    fun longFramesUseTheLongLengthAndParseBack() {
        val p124 = ByteArray(124) { it.toByte() }
        val f124 = BudsFrames.build(0x0204, 0xFF, p124)
        assertContentEquals(hx("AA 83 01 00"), f124.copyOfRange(0, 4))
        val back = parseAll(f124).single()
        assertContentEquals(p124, back.payload)

        val p250 = ByteArray(250) { (it * 3).toByte() }
        val f250 = BudsFrames.build(0x8106, 4, p250)
        assertContentEquals(hx("AA 81 02 00"), f250.copyOfRange(0, 4))
        assertContentEquals(p250, parseAll(f250).single().payload)
    }

    // ── streaming parser ─────────────────────────────────────────────────────────────────────────

    @Test
    fun splitAtEveryByteStillGivesTheFrame() {
        val bytes = BudsFrames.build(0x0204, 0xFF, hx("01 03 01 64 02 64 03 50"))
        val parser = BudsFrameParser()
        val got = ArrayList<BudsFrame>()
        for (b in bytes) got.addAll(parser.feed(byteArrayOf(b)))
        assertEquals(1, got.size)
        assertEquals(0x0204, got[0].cmd)
    }

    @Test
    fun joinedFramesComeOutInOrder() {
        val a = BudsFrames.build(0x8106, 3, hx("00 01 01 64"))
        val b = BudsFrames.handshake(1)
        val c = BudsFrames.build(0x0204, 0xFF, hx("03 01 01 08 00"))
        val frames = parseAll(a + b + c)
        assertEquals(listOf(0x8106, 0x0100, 0x0204), frames.map { it.cmd })
    }

    @Test
    fun anyChunkSizeGivesTheSameFrames() {
        val all = BudsFrames.handshake(1) + BudsFrames.build(0x8106, 3, hx("00 01 01 64")) +
            BudsFrames.build(0x0204, 0xFF, hx("03 01 01 08 00")) + BudsFrames.build(0x0204, 0xFF, ByteArray(150) { 9 })
        val expected = parseAll(all).map { it.cmd }
        for (size in 1..11) {
            val parser = BudsFrameParser()
            val got = ArrayList<Int>()
            var i = 0
            while (i < all.size) {
                val end = minOf(all.size, i + size)
                parser.feed(all, i, end - i).forEach { got.add(it.cmd) }
                i = end
            }
            assertEquals(expected, got, "chunk size $size")
        }
    }

    @Test
    fun resynchronisesOnTheNextStartByte() {
        val valid = BudsFrames.build(0x0204, 0xFF, hx("03 01 01 08 00"))
        val noise = hx("01 02 AA 00 FF 55")
        val frames = parseAll(noise + valid)
        assertEquals(1, frames.size)
        assertEquals(0x0204, frames[0].cmd)
    }

    @Test
    fun impossibleLengthsAreRejected() {
        val valid = BudsFrames.handshake(1)
        // length 3000 (above the cap) and length 2 (below the minimum) are not frame starts
        val frames = parseAll(hx("AA B8 17") + hx("AA 02 00 00") + valid)
        assertEquals(1, frames.size)
        assertEquals(0x0100, frames[0].cmd)
    }

    @Test
    fun aWholeMessageWithANonZeroSecondByteIsNoise() {
        val bad = hx("AA 07 00 01 00 01 01 00 00")
        val frames = parseAll(bad + BudsFrames.handshake(2))
        assertEquals(1, frames.size)
        assertEquals(2, frames[0].seq)
    }

    @Test
    fun sliceFramesAreSkippedWithoutLosingTheNextFrame() {
        val slice = hx("AA 06 01 00 00 DE AD BE")
        val frames = parseAll(slice + BudsFrames.handshake(4))
        assertEquals(1, frames.size)
        assertEquals(4, frames[0].seq)
    }

    @Test
    fun aDeclaredPayloadLongerThanTheBytesIsCutShort() {
        val frames = parseAll(hx("AA 09 00 00 04 02 FF 05 00 03 01"))
        assertEquals(1, frames.size)
        assertContentEquals(hx("03 01"), frames[0].payload)
    }

    @Test
    fun badArgumentsAndRandomBytesNeverThrow() {
        val parser = BudsFrameParser()
        assertTrue(parser.feed(ByteArray(3), 5, 2).isEmpty())
        assertTrue(parser.feed(ByteArray(0)).isEmpty())
        val rnd = Random(1234)
        repeat(2000) {
            val n = rnd.nextInt(1, 64)
            val chunk = ByteArray(n) { if (rnd.nextInt(6) == 0) 0xAA.toByte() else rnd.nextInt(256).toByte() }
            parser.feed(chunk)
        }
        parser.reset()
        assertEquals(1, parser.feed(BudsFrames.handshake(1)).size)
    }

    // ── payload decoders ─────────────────────────────────────────────────────────────────────────

    @Test
    fun batteryPushDecodes() {
        val b = BudsParsers.battery(hx("03 01 64 02 64 03 50"))!!
        assertEquals(100, b.left)
        assertEquals(100, b.right)
        assertEquals(80, b.case)
        assertFalse(b.leftCharging || b.rightCharging || b.caseCharging)
    }

    @Test
    fun batteryChargingBitAndStatusByteReply() {
        val b = BudsParsers.battery(hx("00 03 01 E4 02 5A 03 D0"))!!
        assertEquals(100, b.left)
        assertTrue(b.leftCharging)
        assertEquals(90, b.right)
        assertFalse(b.rightCharging)
        assertEquals(80, b.case)
        assertTrue(b.caseCharging)
    }

    @Test
    fun batteryLidClosedReplyHasOnlyTheOutsideBud() {
        val b = BudsParsers.battery(hx("00 01 01 5A"))!!
        assertEquals(90, b.left)
        assertNull(b.right)
        assertNull(b.case)
    }

    @Test
    fun batteryUnknownLevelsAreLeftOut() {
        val b = BudsParsers.battery(hx("02 01 00 02 64"))!!
        assertNull(b.left)
        assertEquals(100, b.right)
        assertNull(BudsParsers.battery(hx("01 01 7F"))!!.left)        // 127 is not a percentage
    }

    @Test
    fun batteryRejectsBrokenLists() {
        assertNull(BudsParsers.battery(hx("03 01 64 02")))            // runs past the end
        assertNull(BudsParsers.battery(hx("00")))                     // count 0
        assertNull(BudsParsers.battery(ByteArray(0)))
        assertNull(BudsParsers.battery(hx("01 07 50")))               // no such part
    }

    @Test
    fun batteryMergeKeepsPartsTheUpdateLeavesOut() {
        val old = BudsBattery(100, 90, null, false, false, false)
        val merged = old.mergedWith(BudsBattery(null, 80, 70, false, true, false))
        assertEquals(100, merged.left)
        assertEquals(80, merged.right)
        assertTrue(merged.rightCharging)
        assertEquals(70, merged.case)
    }

    @Test
    fun wearingDecodeTable() {
        assertEquals(Pair(true, false), BudsParsers.wearing(hx("03 01 03 02 04 03 04"))!!.inEar)
        assertEquals(Pair(true, true), BudsParsers.wearing(hx("03 01 07 02 07 03 04"))!!.inEar)
        assertEquals(Pair(false, false), BudsParsers.wearing(hx("03 01 01 02 05 03 00"))!!.inEar)
        assertEquals(Pair(false, true), BudsParsers.wearing(hx("01 02 03"))!!.inEar)
    }

    @Test
    fun wearingAcceptsTheLeadingStatusByte() {
        assertEquals(Pair(true, true), BudsParsers.wearing(hx("00 02 01 03 02 03"))!!.inEar)    // offset 1
        assertEquals(Pair(true, false), BudsParsers.wearing(hx("00 01 01 03"))!!.inEar)
        assertNull(BudsParsers.wearing(hx("03 01 03")))                                          // too short
        assertNull(BudsParsers.wearing(hx("01 09 03")))                                          // no such part
    }

    @Test
    fun lidClosePushIsAllZero() {
        val w = BudsParsers.wearing(hx("03 01 00 02 00 03 00"))!!
        assertTrue(w.allZero)
        assertEquals(Pair(false, false), w.inEar)
        assertFalse(BudsParsers.wearing(hx("03 01 01 02 05 03 00"))!!.allZero)
    }

    @Test
    fun ancNotifyUsesAMaskNotAnEnum() {
        fun mode(lo: Int, hi: Int) = BudsParsers.ancNotifyMask(byteArrayOf(3, 1, 1, lo.toByte(), hi.toByte()))
            ?.let { BudsParsers.decodeNoiseMask(it) }
        assertEquals(BudsNoiseMode.OFF, mode(0x08, 0x00))
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, mode(0x02, 0x00))
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, mode(0x80, 0x00))   // Smart
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, mode(0x40, 0x00))   // Light
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, mode(0x20, 0x00))   // Medium
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, mode(0x10, 0x00))   // Deep
        assertEquals(BudsNoiseMode.TRANSPARENCY, mode(0x00, 0x01))
        assertEquals(BudsNoiseMode.TRANSPARENCY, mode(0x00, 0x02))       // with voice enhance
        assertEquals(BudsNoiseMode.ADAPTIVE, mode(0x00, 0x08))
        assertNull(mode(0x00, 0x40))                                     // unknown bit
    }

    @Test
    fun maskDecodeIgnoresSetOnlyBitsAndPrefersAdaptive() {
        assertNull(BudsParsers.decodeNoiseMask(0x0001))
        assertNull(BudsParsers.decodeNoiseMask(0x0004))
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, BudsParsers.decodeNoiseMask(0x00A0))
        assertEquals(BudsNoiseMode.ADAPTIVE, BudsParsers.decodeNoiseMask(0x0802))
    }

    @Test
    fun ancNotifyAcceptsOneToFourModeBytes() {
        assertEquals(0x08, BudsParsers.ancNotifyMask(hx("03 01 01 08")))
        assertEquals(0x0800, BudsParsers.ancNotifyMask(hx("03 01 01 00 08")))
        assertEquals(0x01020304, BudsParsers.ancNotifyMask(hx("03 01 01 04 03 02 01")))
    }

    @Test
    fun ancPushThatIsNotAModeChangeIsIgnored() {
        assertNull(BudsParsers.ancNotifyMask(hx("03 02 01 07 00")))      // hold-cycle echo
        assertNull(BudsParsers.ancNotifyMask(hx("03 01 02 08 00")))
        assertNull(BudsParsers.ancNotifyMask(hx("03 01 01")))            // no mode bytes
        assertNull(BudsParsers.ancNotifyMask(hx("01 01 01 08 00")))      // another event
    }

    @Test
    fun ancReplyMaskWithAndWithoutStatus() {
        assertEquals(0x08, BudsParsers.ancReplyMask(hx("00 01 01 08 00")))
        assertEquals(0x0800, BudsParsers.ancReplyMask(hx("00 01 01 00 08")))
        assertEquals(0x02, BudsParsers.ancReplyMask(hx("01 01 02 00")))
        assertNull(BudsParsers.ancReplyMask(hx("00 02 01 08 00")))
    }

    @Test
    fun featureValuesFromReplyAndPush() {
        assertEquals(mapOf(5 to 0, 6 to 1), BudsParsers.featureValues(hx("00 02 05 00 06 01")))
        assertEquals(mapOf(5 to 0, 6 to 0, 0x28 to 1), BudsParsers.featureValues(hx("00 03 05 00 06 00 28 01")))
        // a push with one extra byte after the last pair (seen on one model)
        assertEquals(mapOf(6 to 1, 0x28 to 0), BudsParsers.featureValues(hx("02 06 01 28 00 FF")))
        assertNull(BudsParsers.featureValues(hx("00")))
    }

    @Test
    fun broadcastCodesAndProductId() {
        assertEquals(setOf(1, 2, 3, 4, 8, 0x0B, 0xF1, 0xF2, 0xF3), BudsParsers.broadcastCodes(hx("00 09 01 02 03 04 08 0B F1 F2 F3")))
        assertEquals(setOf(2, 1), BudsParsers.broadcastCodes(hx("00 02 02 01")))
        assertEquals(setOf(2, 1), BudsParsers.broadcastCodes(hx("02 02 01")))
        assertNull(BudsParsers.broadcastCodes(hx("05 01")))
        assertEquals("065414", BudsParsers.productId(hx("00 14 54 06")))
        assertEquals("065414", BudsParsers.productId(hx("14 54 06")))
        assertNull(BudsParsers.productId(hx("14 54")))
    }

    @Test
    fun gameModePushAndAckStatus() {
        assertEquals(1, BudsParsers.gameModePush(hx("05 01")))
        assertEquals(1, BudsParsers.gameModePush(hx("05 00 00 01")))     // the four product ids with `x x state`
        assertNull(BudsParsers.gameModePush(hx("05")))
        assertEquals(0x0E, BudsParsers.ackStatus(hx("0E")))
        assertNull(BudsParsers.ackStatus(ByteArray(0)))
    }
}
