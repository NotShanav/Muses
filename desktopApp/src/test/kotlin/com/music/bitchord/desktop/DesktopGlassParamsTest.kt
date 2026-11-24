package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** STUDIO G1: ranges and validation of the glass parameters. */
class DesktopGlassParamsTest {
    @Test
    fun defaultsAreAlreadyInRange() {
        assertEquals(DesktopGlassParams.Default, DesktopGlassParams.Default.validated())
    }

    @Test
    fun validatedClampsEveryField() {
        val wild = DesktopGlassParams(
            refraction = 5f, bevelDp = -3f, magnify = 9f, dispersion = -1f, frost = 2f, blurDp = 400f,
            specular = 3f, rim = -2f, tintAlpha = 1f, lightAngleDeg = 720f, albumInfluence = 8f,
        ).validated()
        assertEquals(1f, wild.refraction)
        assertEquals(2f, wild.bevelDp)
        assertEquals(0.2f, wild.magnify)
        assertEquals(0f, wild.dispersion)
        assertEquals(1f, wild.frost)
        assertEquals(40f, wild.blurDp)
        assertEquals(1f, wild.specular)
        assertEquals(0f, wild.rim)
        assertEquals(0.9f, wild.tintAlpha)
        assertEquals(360f, wild.lightAngleDeg)
        assertEquals(1f, wild.albumInfluence)
    }

    @Test
    fun nanAndInfinityFallBackToTheDefault() {
        val bad = DesktopGlassParams(
            refraction = Float.NaN, bevelDp = Float.POSITIVE_INFINITY, frost = Float.NEGATIVE_INFINITY,
        ).validated()
        assertEquals(DesktopGlassParams.Default.refraction, bad.refraction)
        assertEquals(DesktopGlassParams.Default.bevelDp, bad.bevelDp)
        assertEquals(DesktopGlassParams.Default.frost, bad.frost)
    }

    @Test
    fun validValuesPassThroughUnchanged() {
        val p = DesktopGlassParams(refraction = 0.25f, blurDp = 12f, lightAngleDeg = 200f)
        assertEquals(p, p.validated())
    }

    @Test
    fun validatedIsIdempotent() {
        val once = DesktopGlassParams(refraction = 9f, tintAlpha = -4f).validated()
        assertEquals(once, once.validated())
        assertTrue(once.tintAlpha in DesktopGlassParams.TINT_ALPHA)
    }

    // UI U9: the defaults follow the liquid-glass references and stay inside every range.
    @Test
    fun u9DefaultsAreClearGlassAndInRange() {
        val d = DesktopGlassParams.Default
        assertEquals(d, d.validated())
        assertEquals(DesktopGlassParams.Nav, DesktopGlassParams.Nav.validated())
        assertTrue(d.refraction >= 0.9f && d.frost <= 0.15f && d.tintAlpha <= 0.25f)
        assertTrue(DesktopGlassParams.Nav.refraction < d.refraction)
    }
}
