package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STUDIO G1: the Kotlin mirror of the shader's maths, and the uniform block's layout. Nothing here
 * touches Skia, so it runs without the native library. (The shader string is read as plain text.)
 */
class DesktopGlassMathTest {
    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun sdRoundRectIsNegativeInsideZeroOnTheEdgeAndPositiveOutside() {
        near(-40f, DesktopGlassMath.sdRoundRect(0f, 0f, 100f, 40f, 20f))
        near(0f, DesktopGlassMath.sdRoundRect(100f, 0f, 100f, 40f, 20f))
        near(0f, DesktopGlassMath.sdRoundRect(0f, -40f, 100f, 40f, 20f))
        near(10f, DesktopGlassMath.sdRoundRect(110f, 0f, 100f, 40f, 20f))
    }

    @Test
    fun theRoundedCornerCutsInsideTheBoundingBox() {
        // The box's own corner is outside the rounded shape by about r(sqrt2 - 1).
        near(8.284f, DesktopGlassMath.sdRoundRect(100f, 40f, 100f, 40f, 20f), 0.01f)
    }

    @Test
    fun edgeProfileIsOneAtTheRimAndZeroAtTheBevel() {
        near(1f, DesktopGlassMath.edgeProfile(0f, 14f))
        near(1f, DesktopGlassMath.edgeProfile(-3f, 14f))
        near(0f, DesktopGlassMath.edgeProfile(14f, 14f))
        near(0f, DesktopGlassMath.edgeProfile(60f, 14f))
        near(0.5f, DesktopGlassMath.edgeProfile(7f, 14f))
    }

    @Test
    fun edgeProfileNeverIncreasesGoingDeeper() {
        var last = 2f
        for (i in 0..30) {
            val v = DesktopGlassMath.edgeProfile(i.toFloat(), 20f)
            assertTrue(v <= last + 1e-6f, "profile rose at $i")
            last = v
        }
    }

    @Test
    fun edgeProfileSurvivesAZeroBevel() {
        val v = DesktopGlassMath.edgeProfile(5f, 0f)
        assertTrue(!v.isNaN() && v in 0f..1f)
    }

    @Test
    fun normalsPointOutwards() {
        val (rx, ry) = DesktopGlassMath.roundRectNormal(99f, 0f, 100f, 40f, 20f)
        near(1f, rx); near(0f, ry)
        val (tx, ty) = DesktopGlassMath.roundRectNormal(0f, -39f, 100f, 40f, 20f)
        near(0f, tx); near(-1f, ty)
    }

    @Test
    fun lightAngleMapsToScreenDirections() {
        near(1f, DesktopGlassMath.lightDirX(0f)); near(0f, DesktopGlassMath.lightDirY(0f))
        near(0f, DesktopGlassMath.lightDirX(90f)); near(-1f, DesktopGlassMath.lightDirY(90f)) // up
        near(-0.7071f, DesktopGlassMath.lightDirX(135f)); near(-0.7071f, DesktopGlassMath.lightDirY(135f))
    }

    @Test
    fun uniformLayoutIsConsistent() {
        assertEquals(DesktopGlassMath.UNIFORM_NAMES.size, DesktopGlassMath.UNIFORM_WIDTHS.size)
        assertEquals(21, DesktopGlassMath.UNIFORM_LENGTH)
        assertTrue(DesktopGlassMath.UNIFORM_WIDTHS.all { it == 1 || it == 2 || it == 4 })
        assertEquals(DesktopGlassMath.UNIFORM_NAMES.size, DesktopGlassMath.UNIFORM_NAMES.toSet().size)
    }

    @Test
    fun everyUniformNameIsDeclaredInTheShaderSource() {
        DesktopGlassMath.UNIFORM_NAMES.forEachIndexed { i, name ->
            val type = when (DesktopGlassMath.UNIFORM_WIDTHS[i]) {
                1 -> "float"; 2 -> "float2"; else -> "float4"
            }
            assertTrue("uniform $type $name;" in DesktopGlassShader.SKSL, "missing: uniform $type $name;")
        }
        assertTrue("uniform shader content;" in DesktopGlassShader.SKSL)
        assertTrue("uniform shader blurred;" in DesktopGlassShader.SKSL)
    }

    @Test
    fun uniformsForWritesTheBlockInOrder() {
        val p = DesktopGlassParams(
            refraction = 0.5f, bevelDp = 10f, magnify = 0.1f, dispersion = 0.2f, frost = 0.3f,
            specular = 0.8f, rim = 0.4f, tintAlpha = 0.25f, lightAngleDeg = 0f, albumInfluence = 0.6f,
        )
        val u = DesktopGlassMath.uniformsFor(
            p, 640f, 80f, 40f, 2f, Color(0.1f, 0.2f, 0.3f), Color(0.9f, 0.8f, 0.7f), 3f,
        )
        assertEquals(DesktopGlassMath.UNIFORM_LENGTH, u.size)
        assertEquals(640f, u[0]); assertEquals(80f, u[1])
        assertEquals(40f, u[2])                       // radius, already <= half the height
        assertEquals(20f, u[3])                       // bevel 10 dp at density 2
        near(0.5f * 20f * DesktopGlassMath.STRENGTH_OF_BEVEL, u[4])
        assertEquals(0.1f, u[5]); assertEquals(0.2f, u[6]); assertEquals(0.3f, u[7])
        // STUDIO G5 hook: an sRGB Color keeps 8 bits per channel, so colours round-trip to about 0.004.
        near(0.1f, u[8], 0.01f); near(0.2f, u[9], 0.01f); near(0.3f, u[10], 0.01f); assertEquals(0.25f, u[11])
        near(0.9f, u[12], 0.01f); near(0.8f, u[13], 0.01f); near(0.7f, u[14], 0.01f); assertEquals(0.6f, u[15])
        assertEquals(0.8f, u[16]); assertEquals(0.4f, u[17])
        near(1f, u[18]); near(0f, u[19])
        assertEquals(3f, u[20])
    }

    @Test
    fun uniformsForClampsRadiusAndBevelToTheGlass() {
        val u = DesktopGlassMath.uniformsFor(
            DesktopGlassParams(bevelDp = 40f), 200f, 30f, 500f, 1f, Color.Black, Color.White, 0f,
        )
        assertEquals(15f, u[2])   // radius limited to half of the short side
        assertEquals(15f, u[3])   // bevel likewise
    }

    @Test
    fun uniformsForValidatesAndReusesTheBuffer() {
        val buffer = FloatArray(DesktopGlassMath.UNIFORM_LENGTH)
        val u = DesktopGlassMath.uniformsFor(
            DesktopGlassParams(frost = 9f, rim = Float.NaN), 100f, 50f, 25f, Float.NaN, Color.Black,
            Color.White, Float.NaN, buffer,
        )
        assertTrue(u === buffer)
        assertEquals(1f, u[7])
        assertEquals(DesktopGlassParams.Default.rim, u[17])
        assertEquals(0f, u[20])
        assertTrue(u.none { it.isNaN() })
    }

    @Test
    fun aTooSmallBufferIsRejected() {
        val failed = runCatching {
            DesktopGlassMath.uniformsFor(
                DesktopGlassParams(), 100f, 50f, 25f, 1f, Color.Black, Color.White, 0f, FloatArray(3),
            )
        }.isFailure
        assertTrue(failed)
    }
}
