package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ODEON-FIX: the three-colour album palette (v2), on synthetic covers made of flat bands. White, black and skin tones count. */
class DesktopAlbumPaletteTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()
    private val peach = 0xFFFFCDB2.toInt()

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

    /** HSV hue in degrees (0 for greys). */
    private fun hue(c: Color): Float {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        val d = mx - mn
        if (d < 1e-4f) return 0f
        var h = when (mx) {
            c.red -> 60f * (((c.green - c.blue) / d) % 6f)
            c.green -> 60f * (((c.blue - c.red) / d) + 2f)
            else -> 60f * (((c.red - c.green) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return h
    }

    private fun saturation(c: Color): Float {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        return if (mx <= 0f) 0f else (mx - mn) / mx
    }

    private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun hueGapDeg(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    private fun assertNearHue(expected: Float, actual: Color, tolerance: Float) {
        val gap = hueGapDeg(expected, hue(actual))
        assertTrue(gap <= tolerance, "hue ${hue(actual)} is $gap degrees from $expected (tolerance $tolerance)")
    }

    @Test
    fun solidRedStaysInOneRedFamily() {
        val p = palette(bands(red to 100))
        assertEquals(4, p.colors.size)
        val reference = hue(p.colors[0])
        assertTrue(p.colors[0].red > p.colors[0].green && p.colors[0].red > p.colors[0].blue)
        p.colors.forEach { assertNearHue(reference, it, 25f) }
    }

    @Test
    fun redAndBlueGiveRedFirstAndBlueSecond() {
        val p = palette(bands(red to 70, blue to 30))
        assertTrue(p.colors[0].red > p.colors[0].blue, "dominant should be red")
        assertTrue(p.colors[1].blue > p.colors[1].red, "second should be blue")
        assertTrue(p.shares[0] > p.shares[1])
    }

    @Test
    fun whiteBackgroundWithRedKeepsTheWhite() {
        // A cover that is mostly white with a red figure: white must be one of the colours, as a light neutral.
        val p = palette(bands(white to 60, red to 40))
        val light = p.colors.take(3).filter { saturation(it) < 0.15f && luminance(it) > 0.55f }
        assertTrue(light.isNotEmpty(), "a light neutral colour should be present")
        assertTrue(p.colors.take(3).any { it.red > it.green + 0.2f && it.red > it.blue + 0.2f }, "the red should be present too")
        assertTrue(p.brightness > 0.5f, "a mostly white cover should report a high brightness")
    }

    @Test
    fun whiteBlackAndRedAreAllRepresented() {
        val p = palette(bands(white to 40, black to 35, red to 25))
        val top3 = p.colors.take(3)
        assertTrue(top3.any { saturation(it) < 0.15f && luminance(it) > 0.55f }, "a light neutral")
        assertTrue(top3.any { saturation(it) < 0.25f && luminance(it) < 0.30f }, "a dark neutral")
        assertTrue(top3.any { it.red > it.green + 0.2f && it.red > it.blue + 0.2f }, "the red")
    }

    @Test
    fun skinToneIsNotTurnedIntoRed() {
        val p = palette(bands(peach to 75, red to 25))
        val dominant = p.colors[0]
        // Warm and light, much less saturated than the red.
        assertTrue(dominant.red > dominant.green && dominant.green >= dominant.blue, "warm tone")
        assertTrue(saturation(dominant) < 0.6f, "skin tone should not be fully saturated")
        assertTrue(luminance(dominant) > 0.45f)
    }

    @Test
    fun smallAccentIsIgnored() {
        val p = palette(bands(red to 97, green to 3))
        val reference = hue(p.colors[0])
        p.colors.forEach { assertNearHue(reference, it, 25f) }
        assertEquals(0f, p.shares[1], 1e-6f)
    }

    @Test
    fun greyCoverGivesGreys() {
        val p = palette(bands(grey to 100))
        p.colors.forEach { assertTrue(saturation(it) < 0.12f, "grey cover colour should be neutral, saturation ${saturation(it)}") }
    }

    @Test
    fun blackAndWhiteCoverStaysNeutral() {
        val p = palette(bands(white to 50, black to 50))
        p.colors.forEach { assertTrue(saturation(it) < 0.12f) }
        val lums = p.colors.take(3).map { luminance(it) }
        assertTrue(lums.max() - lums.min() > 0.25f, "one light and one dark glow expected")
    }

    @Test
    fun lightCoverIsBrighterThanDarkCover() {
        val light = palette(bands(white to 80, grey to 20))
        val dark = palette(bands(black to 80, grey to 20))
        assertTrue(light.brightness > dark.brightness + 0.2f)
    }

    @Test
    fun threeColourCoverGivesThreeColours() {
        val p = palette(bands(red to 34, green to 33, blue to 33))
        val hues = p.colors.take(3).map { hue(it) }
        assertTrue(hueGapDeg(hues[0], hues[1]) > 60f)
        assertTrue(hueGapDeg(hues[0], hues[2]) > 60f)
        assertTrue(hueGapDeg(hues[1], hues[2]) > 60f)
    }

    @Test
    fun colourStaysInsideTheValidRange() {
        val p = palette(bands(red to 30, green to 30, blue to 20, white to 10, black to 10))
        (p.colors + p.base).forEach {
            assertTrue(it.red in 0f..1f && it.green in 0f..1f && it.blue in 0f..1f)
        }
        assertTrue(p.intensity in 0.5f..1f)
        assertTrue(p.brightness in 0f..1f)
    }

    @Test
    fun tinyOrShortInputDoesNotCrash() {
        assertEquals(null, extractAlbumPalette(IntArray(1), 1, 1))
        assertEquals(null, extractAlbumPalette(IntArray(3), 2, 2))
        val tiny = extractAlbumPalette(intArrayOf(red, red, blue, blue), 2, 2)
        assertNotNull(tiny)
    }

    @Test
    fun sameInputSameResult() {
        val a = palette(bands(red to 50, white to 30, black to 20))
        val b = palette(bands(red to 50, white to 30, black to 20))
        assertEquals(a.colors, b.colors)
        assertEquals(a.base, b.base)
    }
}
