package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS personal equalizer presets: the list codec and the model flag. No Bluetooth, window or network. */
class DesktopBudsCustomEqTest {
    private fun hex(s: String): ByteArray =
        s.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    // 00 <count 2> | selected: FA 06 id 06 "Gym" 2 bands (62 Hz +3, 1000 Hz -2) | not selected: FA 06 id 07 "Night" 1 band (250 Hz 0)
    private val list = hex(
        "00 02 " +
            "01 FA 06 06 03 47 79 6D 02 3E 00 03 E8 03 FE " +
            "00 FA 06 07 05 4E 69 67 68 74 01 FA 00 00",
    )

    @Test
    fun parsesAListWithSelectionNamesAndBands() {
        val presets = BudsCustomEq.parseList(list)
        assertNotNull(presets)
        assertEquals(2, presets.size)
        val gym = presets[0]
        assertEquals(6, gym.id)
        assertEquals("Gym", gym.name)
        assertTrue(gym.selected)
        assertEquals(listOf(62, 1000), gym.freqs)
        assertEquals(listOf(3, -2), gym.gains)
        assertContentEquals(hex("FA 06"), gym.tag)
        val night = presets[1]
        assertEquals(7, night.id)
        assertEquals("Night", night.name)
        assertFalse(night.selected)
        assertEquals(listOf(250), night.freqs)
        assertEquals(listOf(0), night.gains)
    }

    @Test
    fun anEmptyListIsAnEmptyListNotAFailure() {
        val empty = BudsCustomEq.parseList(hex("00 00"))
        assertNotNull(empty)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun anyInconsistencyGivesNullNeverAGuess() {
        assertNull(BudsCustomEq.parseList(ByteArray(0)))
        assertNull(BudsCustomEq.parseList(hex("00")))
        assertNull(BudsCustomEq.parseList(hex("02 00")))                                    // a refused status
        assertNull(BudsCustomEq.parseList(hex("00 01 01 FA 06 06")))                         // cut before the name length
        assertNull(BudsCustomEq.parseList(hex("00 01 01 FA 06 06 05 47 79")))                // name runs past the end
        assertNull(BudsCustomEq.parseList(hex("00 01 01 FA 06 06 03 47 79 6D 02 3E 00 03"))) // bands run past the end
        assertNull(BudsCustomEq.parseList(list.copyOf(list.size - 1)))                       // last band cut
        assertNull(BudsCustomEq.parseList(hex("00 02") + list.copyOfRange(2, 17)))            // count says two, one present
    }

    @Test
    fun selectSendsTheWholePresetWithTheSaveActionAndTheEchoedTag() {
        val gym = BudsCustomEq.parseList(list)!![0]
        assertContentEquals(hex("02 FA 06 06 03 47 79 6D 02 3E 00 03 E8 03 FE"), BudsCustomEq.encodeSelect(gym))
        val night = BudsCustomEq.parseList(list)!![1]
        assertContentEquals(hex("02 FA 06 07 05 4E 69 67 68 74 01 FA 00 00"), BudsCustomEq.encodeSelect(night))
    }

    @Test
    fun aTagThatIsNotFA06IsEchoedUnchanged() {
        val p = BudsCustomPreset(1, "A", listOf(100), listOf(1), false, hex("FB 05"))
        assertContentEquals(hex("02 FB 05 01 01 41 01 64 00 01"), BudsCustomEq.encodeSelect(p))
    }

    @Test
    fun selectAndParseRoundTrip() {
        val original = BudsCustomEq.parseList(list)!!
        for (p in original) {
            val encoded = BudsCustomEq.encodeSelect(p)
            val flag: Byte = if (p.selected) 1 else 0
            val asEntry = byteArrayOf(0, 1, flag) + encoded.copyOfRange(1, encoded.size)
            val back = BudsCustomEq.parseList(asEntry)?.single()
            assertNotNull(back)
            assertEquals(p.id, back.id)
            assertEquals(p.name, back.name)
            assertEquals(p.freqs, back.freqs)
            assertEquals(p.gains, back.gains)
        }
    }

    @Test
    fun idsAndLabels() {
        assertEquals(BUDS_CUSTOM_EQ_ID_BASE + 6, BudsCustomEq.chipId(6))
        assertTrue(BudsCustomEq.isChipId(BudsCustomEq.chipId(0)))
        assertFalse(BudsCustomEq.isChipId(5))
        assertFalse(BudsCustomEq.isChipId(BUDS_CUSTOM_EQ_ID_BASE - 1))
        assertEquals("Gym", BudsCustomEq.label(BudsCustomEq.parseList(list)!![0]))
        assertEquals("Custom 4", BudsCustomEq.label(BudsCustomPreset(4, "  ", emptyList(), emptyList(), false, hex("FA 06"))))
    }

    @Test
    fun withSelectedKeepsEverythingElse() {
        val gym = BudsCustomEq.parseList(list)!![0]
        val off = gym.withSelected(false)
        assertFalse(off.selected)
        assertEquals(gym.name, off.name)
        assertEquals(gym.gains, off.gains)
        assertContentEquals(gym.tag, off.tag)
    }

    @Test
    fun onlyTheNordBuds3ProHasPersonalPresets() {
        assertTrue(BudsModelProfiles.forProduct("064414").customEq)
        assertFalse(BudsModelProfiles.DEFAULT.customEq)
        assertFalse(BudsModelProfiles.forProduct("065414").customEq)
    }
}
