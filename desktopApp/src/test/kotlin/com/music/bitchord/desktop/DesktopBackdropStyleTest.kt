package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BACKDROP-STYLE: the looks, the two sliders and the restyle of an extracted palette. All pure. */
class DesktopBackdropStyleTest {

    private val size = 60
    private val red = 0xFFD02020.toInt()
    private val blue = 0xFF2030D0.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun cover(vararg parts: Pair<Int, Int>): FluidPalette {
        val pixels = IntArray(size * size)
        var row = 0
        for ((colour, rows) in parts) {
            for (y in row until minOf(size, row + rows)) for (x in 0 until size) pixels[y * size + x] = colour
            row += rows
        }
        return assertNotNull(extractAlbumPalette(pixels, size, size))
    }

    private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun saturation(c: Color): Float {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        return if (mx <= 0f) 0f else (mx - mn) / mx
    }

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

    private fun gap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    private fun restyled(p: FluidPalette, look: DesktopBackdropLook, brightness: Float = 0.5f, vibrance: Float = 0.5f) =
        restylePalette(p, DesktopBackdropTunings.of(look), brightness, vibrance)

    private fun glowLuminance(p: FluidPalette): Float = p.colors.take(3).sumOf { luminance(it).toDouble() }.toFloat()

    @Test
    fun looksAreOrderedFromVividToDeep() {
        val vivid = DesktopBackdropTunings.of(DesktopBackdropLook.VIVID)
        val natural = DesktopBackdropTunings.of(DesktopBackdropLook.NATURAL)
        val deep = DesktopBackdropTunings.of(DesktopBackdropLook.DEEP)
        assertTrue(vivid.chromaticFloor > natural.chromaticFloor && natural.chromaticFloor > deep.chromaticFloor)
        assertTrue(vivid.washAlpha > natural.washAlpha && natural.washAlpha > deep.washAlpha)
        assertTrue(vivid.baseLightness > natural.baseLightness && natural.baseLightness > deep.baseLightness)
        assertTrue(vivid.roleAlphaScale > natural.roleAlphaScale && natural.roleAlphaScale > deep.roleAlphaScale)
        assertTrue(vivid.chromaScale > natural.chromaScale && natural.chromaScale > deep.chromaScale)
        assertTrue(vivid.lightnessShift > natural.lightnessShift && natural.lightnessShift > deep.lightnessShift)
    }

    @Test
    fun storedLookFallsBackToTheDefault() {
        assertEquals(DesktopBackdropLook.DEEP, DesktopBackdropLook.fromStored("DEEP"))
        assertEquals(DesktopBackdropLook.DEFAULT, DesktopBackdropLook.fromStored("nonsense"))
        assertEquals(DesktopBackdropLook.DEFAULT, DesktopBackdropLook.fromStored(null))
        assertEquals(DesktopBackdropLook.VIVID, DesktopBackdropLook.DEFAULT)
    }

    @Test
    fun sliderNumbersStayInRange() {
        assertEquals(0.5f, DesktopBackdropMath.parseUnit(null))
        assertEquals(0.5f, DesktopBackdropMath.parseUnit("abc"))
        assertEquals(0f, DesktopBackdropMath.parseUnit("-3"))
        assertEquals(1f, DesktopBackdropMath.parseUnit("7"))
        assertEquals(0.5f, DesktopBackdropMath.unit(Float.NaN))
        assertEquals(0.25f, DesktopBackdropMath.parseUnit("0.25"))
    }

    @Test
    fun vividIsBrighterThanNaturalAndNaturalThanDeep() {
        val p = cover(red to 60)
        val vivid = glowLuminance(restyled(p, DesktopBackdropLook.VIVID))
        val natural = glowLuminance(restyled(p, DesktopBackdropLook.NATURAL))
        val deep = glowLuminance(restyled(p, DesktopBackdropLook.DEEP))
        assertTrue(vivid > natural, "vivid $vivid natural $natural")
        assertTrue(natural > deep, "natural $natural deep $deep")
    }

    @Test
    fun naturalWithTheSlidersInTheMiddleKeepsTheCover() {
        val p = cover(red to 40, blue to 20)
        val same = restyled(p, DesktopBackdropLook.NATURAL)
        for (i in 0..2) {
            assertTrue(abs(luminance(p.colors[i]) - luminance(same.colors[i])) < 0.06f, "colour $i changed too much")
            assertTrue(gap(hue(p.colors[i]), hue(same.colors[i])) < 6f, "hue $i moved")
        }
    }

    @Test
    fun brightnessSliderLightensAndDarkens() {
        val p = cover(red to 60)
        val dark = glowLuminance(restyled(p, DesktopBackdropLook.NATURAL, brightness = 0f))
        val mid = glowLuminance(restyled(p, DesktopBackdropLook.NATURAL, brightness = 0.5f))
        val light = glowLuminance(restyled(p, DesktopBackdropLook.NATURAL, brightness = 1f))
        assertTrue(dark < mid && mid < light, "dark $dark mid $mid light $light")
    }

    @Test
    fun vibranceSliderChangesHowColourfulTheGlowsAre() {
        val p = cover(red to 60)
        val flat = restyled(p, DesktopBackdropLook.NATURAL, vibrance = 0f).colors[0]
        val rich = restyled(p, DesktopBackdropLook.NATURAL, vibrance = 1f).colors[0]
        assertTrue(saturation(rich) > saturation(flat), "rich ${saturation(rich)} flat ${saturation(flat)}")
    }

    @Test
    fun whiteAndBlackStayNeutralInEveryLook() {
        val p = cover(white to 30, black to 30)
        for (look in DesktopBackdropLook.entries) {
            val styled = restyled(p, look, vibrance = 1f)
            styled.colors.forEach { assertTrue(saturation(it) < 0.12f, "$look made a grey colourful") }
        }
    }

    @Test
    fun everyLookAndSliderCornerStaysInsideTheValidRange() {
        val p = cover(red to 30, blue to 20, white to 10)
        for (look in DesktopBackdropLook.entries) {
            for (b in listOf(0f, 0.5f, 1f)) for (v in listOf(0f, 0.5f, 1f)) {
                val styled = restyled(p, look, b, v)
                assertEquals(4, styled.colors.size)
                (styled.colors + styled.base).forEach {
                    assertTrue(it.red in 0f..1f && it.green in 0f..1f && it.blue in 0f..1f)
                }
                assertTrue(styled.intensity in 0f..1f)
                assertTrue(styled.brightness in 0f..1f)
            }
        }
    }

    @Test
    fun vividKeepsTheGlowAtFullStrength() {
        val p = cover(black to 50, red to 10)
        assertTrue(restyled(p, DesktopBackdropLook.VIVID).intensity >= 0.9f)
        assertTrue(restyled(p, DesktopBackdropLook.DEEP).intensity < p.intensity + 1e-4f)
    }
}
