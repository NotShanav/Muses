package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWideLayoutTest {
    @Test
    fun `host without the flag keeps the phone layout`() {
        val l = PlayerWideLayout.of(false, 900.dp, 720.dp, 1280.dp)
        assertFalse(l.active)
        assertEquals(PLAYER_MAX_WIDTH, l.columnMax)
        assertEquals(1f, l.lyricScale, 0f)
        assertEquals(PlayerWideLayout.Off, l)
    }

    @Test
    fun `landscape windows stay on the landscape player`() {
        assertFalse(PlayerWideLayout.of(true, 900.dp, 1280.dp, 800.dp).active)
    }

    @Test
    fun `column grows with the window up to the host maximum`() {
        assertEquals(560f, PlayerWideLayout.columnWidth(540.dp, 900.dp).value, 0.01f)
        assertEquals(660f, PlayerWideLayout.columnWidth(720.dp, 900.dp).value, 0.01f)
        assertEquals(840f, PlayerWideLayout.columnWidth(900.dp, 900.dp).value, 0.01f)
        assertEquals(900f, PlayerWideLayout.columnWidth(1600.dp, 900.dp).value, 0.01f)
    }

    @Test
    fun `missing or too small host maximum is sanitised`() {
        assertEquals(900f, PlayerWideLayout.columnWidth(2000.dp, null).value, 0.01f)
        assertEquals(700f, PlayerWideLayout.columnWidth(2000.dp, 700.dp).value, 0.01f)
        // Never narrower than the phone column.
        assertEquals(560f, PlayerWideLayout.columnWidth(2000.dp, 300.dp).value, 0.01f)
    }

    @Test
    fun `artwork is capped to a share of the window height`() {
        assertEquals(595.2f, PlayerWideLayout.artworkCap(960.dp).value, 0.05f)
        assertEquals(793.6f, PlayerWideLayout.artworkCap(1280.dp).value, 0.05f)
        for ((w, h) in listOf(540 to 960, 720 to 1280, 900 to 1400)) {
            val l = PlayerWideLayout.of(true, 900.dp, w.dp, h.dp)
            assertTrue(l.active)
            val art = minOf(l.columnMax, l.artworkCap)
            assertTrue(art <= l.columnMax)
            assertTrue(art.value <= h * 0.62f + 0.05f || art == PLAYER_MAX_WIDTH)
        }
    }

    @Test
    fun `lyric scale is one for phones and bounded for big windows`() {
        assertEquals(1f, PlayerWideLayout.lyricScale(360.dp), 0f)
        assertEquals(1f, PlayerWideLayout.lyricScale(600.dp), 0f)
        assertEquals(1.15f, PlayerWideLayout.lyricScale(720.dp), 0.001f)
        assertEquals(1.4f, PlayerWideLayout.lyricScale(900.dp), 0.001f)
        assertEquals(1.4f, PlayerWideLayout.lyricScale(3000.dp), 0.001f)
        var last = 0f
        for (w in 300..1200 step 10) {
            val s = PlayerWideLayout.lyricScale(w.dp)
            assertTrue(s in 1f..WIDE_LYRIC_SCALE_MAX)
            assertTrue(s >= last)
            last = s
        }
    }

    @Test
    fun `lyric gap is zero off and grows with the scale on`() {
        assertEquals(0f, wideLyricGap(1f, false).value, 0f)
        assertEquals(0f, wideLyricGap(1.4f, false).value, 0f)
        assertEquals(12f, wideLyricGap(1f, true).value, 0.001f)
        assertEquals(16.8f, wideLyricGap(1.4f, true).value, 0.001f)
    }
}
