package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UPGRADE M1: the three-colour album palette, on synthetic covers (rows of flat colour). */
class DesktopAlbumPaletteTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()

    private val size = 100

    /** A 100x100 cover made of horizontal bands: each pair is (colour, rows). */
    private fun bands(vararg parts: Pair<Int, Int>): IntArray {
        val out = IntArray(size * size)
        var row = 0
        for ((colour, rows) in parts) {
            for (y in row until minOf(size, row + rows)) {
                for (x in 0 until size) out[y * size + x] = colour
            }
            row += rows
        }
        return out
    }

    private fun palette(pixels: IntArray, w: Int = size, h: Int = size): FluidPalette =
        assertNotNull(extractAlbumPalette(pixels, w, h))

    private fun hue(c: Color): Float = c.toHsl().h

    private fun assertNearHue(expected: Float, actual: Color, tolerance: Float = 15f) {
        val gap = hueGap(expected, hue(actual))
        assertTrue(gap <= tolerance, "hue ${hue(actual)} is $gap degrees from $expected")
    }

    @Test
    fun solidRedIsRedInEverySlot() {
        val p = palette(bands(red to 100))
        assertEquals(4, p.colors.size)
        p.colors.forEach { assertNearHue(0f, it, 12f) }
        assertTrue(p.shares[0] > 0.9f)
        assertEquals(0f, p.shares[1])
        assertEquals(0f, p.shares[2])
    }

    @Test
    fun redAndBlueSeventyThirtyGivesRedThenBlue() {
        val p = palette(bands(red to 70, blue to 30))
        assertNearHue(0f, p.colors[0])
        assertNearHue(240f, p.colors[1])
        // No third colour exists: it is a variant of the dominant, not a new hue.
        assertNearHue(hue(p.colors[0]), p.colors[2], 10f)
        assertTrue(p.shares[0] > p.shares[1])
        assertTrue(p.shares[1] > 0.15f)
        assertEquals(0f, p.shares[2])
    }

    @Test
    fun threeColourCoverKeepsAllThree() {
        val p = palette(bands(red to 34, green to 33, blue to 33))
        val targets = listOf(0f, 120f, 240f)
        for (t in targets) {
            assertTrue(
                (0..2).any { hueGap(t, hue(p.colors[it])) <= 15f },
                "no glow near hue $t",
            )
        }
        assertTrue(p.shares.all { it > 0.2f })
    }

    @Test
    fun smallAccentIsIgnored() {
        val p = palette(bands(red to 97, green to 3))
        // Every glow stays in the red family; the 3 percent green never becomes a glow.
        p.colors.forEach { assertNearHue(0f, it, 12f) }
        assertEquals(0f, p.shares[1])
    }

    @Test
    fun oneColourCoverStaysInOneHueFamily() {
        val p = palette(bands(blue to 100))
        val lead = hue(p.colors[0])
        p.colors.forEach { assertTrue(hueGap(lead, hue(it)) <= 12f, "hue ${hue(it)} drifted from $lead") }
    }

    @Test
    fun glowLightnessStaysInTheClamp() {
        val p = palette(bands(red to 70, blue to 30))
        for (i in 0..2) {
            val l = p.colors[i].toHsl().l
            assertTrue(l in 0.45f..0.61f, "lightness $l")
        }
        // The tint is lighter than the dominant.
        assertTrue(p.colors[3].toHsl().l > p.colors[0].toHsl().l)
    }

    @Test
    fun greyCoverGetsNeutralGreys() {
        val p = palette(bands(grey to 100))
        p.colors.forEach { assertTrue(it.toHsl().s <= 0.09f, "saturation ${it.toHsl().s}") }
        assertEquals(0.45f, p.intensity)
    }

    @Test
    fun blackAndWhiteCoverGetsNeutralGreys() {
        val p = palette(bands(black to 50, white to 50))
        p.colors.forEach { assertTrue(it.toHsl().s <= 0.09f, "saturation ${it.toHsl().s}") }
        for (i in 0..2) {
            val l = p.colors[i].toHsl().l
            assertTrue(l in 0.45f..0.61f, "lightness $l")
        }
    }

    @Test
    fun tinyImageDoesNotCrash() {
        val two = extractAlbumPalette(intArrayOf(red, red, red, red), 2, 2)
        // Either null or a usable palette is fine; it must not throw.
        if (two != null) assertEquals(4, two.colors.size)
        assertNull(extractAlbumPalette(intArrayOf(red), 1, 1))
        assertNull(extractAlbumPalette(IntArray(0), 0, 0))
        assertNull(extractAlbumPalette(IntArray(3), 10, 10))
    }

    @Test
    fun sameInputGivesSameResult() {
        val pixels = bands(red to 50, blue to 30, green to 20)
        val a = palette(pixels)
        val b = palette(pixels)
        assertEquals(a.colors, b.colors)
        assertEquals(a.base, b.base)
        assertEquals(a.intensity, b.intensity)
        assertEquals(a.shares, b.shares)
    }
}
