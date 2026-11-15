package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** STUDIO G3: the pure parts of the liquid pill and the new config fields. */
class DesktopLiquidPillTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun frostedPillIsUsedForGlassAndLiquidEvenWithoutTheShader() {
        assertTrue(desktopLiquidPillActive(DesktopPillMaterial.LIQUID_GLASS, DesktopMiniPlayerMaterial.GLASS, true))
        assertTrue(desktopLiquidPillActive(DesktopPillMaterial.LIQUID_GLASS, DesktopMiniPlayerMaterial.GLASS, false))
        assertTrue(desktopLiquidPillActive(DesktopPillMaterial.GLASS, DesktopMiniPlayerMaterial.GLASS, false))
        assertFalse(desktopLiquidPillActive(DesktopPillMaterial.CLASSIC, DesktopMiniPlayerMaterial.GLASS, true))
        assertFalse(desktopLiquidPillActive(DesktopPillMaterial.LIQUID_GLASS, DesktopMiniPlayerMaterial.SOLID, true))
        assertFalse(desktopLiquidPillActive(DesktopPillMaterial.LIQUID_GLASS, DesktopMiniPlayerMaterial.ACCENT, true))
    }

    @Test
    fun theBendingOverlayNeedsLiquidAndAWorkingShader() {
        assertTrue(desktopLiquidEdgeActive(DesktopPillMaterial.LIQUID_GLASS, true))
        assertFalse(desktopLiquidEdgeActive(DesktopPillMaterial.LIQUID_GLASS, false))
        assertFalse(desktopLiquidEdgeActive(DesktopPillMaterial.GLASS, true))
        assertFalse(desktopLiquidEdgeActive(DesktopPillMaterial.CLASSIC, true))
    }

    @Test
    fun tintNeverDropsBelowTheContrastFloor() {
        val floor = 0.72f * LIQUID_TINT_FLOOR_SHARE
        assertEquals(floor, liquidTintAlpha(0f, 0.72f), 1e-6f)
        assertEquals(0.7f, liquidTintAlpha(0.7f, 0.72f), 1e-6f)
        assertEquals(0.9f, liquidTintAlpha(5f, 0.72f), 1e-6f)
        assertTrue(liquidTintAlpha(Float.NaN, Float.NaN) > 0f)
        // a contrast outside the setting's range is clamped before the floor is taken
        assertEquals(0.9f * LIQUID_TINT_FLOOR_SHARE, liquidTintAlpha(0f, 3f), 1e-6f)
    }

    @Test
    fun sweepIsABellAndProgressIsSafe() {
        assertEquals(0f, liquidSweepAlpha(0f))
        assertEquals(1f, liquidSweepAlpha(0.5f))
        assertEquals(0f, liquidSweepAlpha(1f))
        assertEquals(0f, liquidSweepAlpha(Float.NaN))
        assertEquals(0f, liquidProgressFraction(-1f))
        assertEquals(1f, liquidProgressFraction(9f))
        assertEquals(0f, liquidProgressFraction(Float.NaN))
        assertEquals(0.4f, liquidProgressFraction(0.4f))
    }

    @Test
    fun mixIsAPlainBlend() {
        val mid = liquidMix(Color.Black, Color.White, 0.5f)
        assertEquals(0.5f, mid.red, 0.01f) // STUDIO G5 hook: 8-bit sRGB channel, so 128/255
        assertEquals(Color.Black, liquidMix(Color.Black, Color.White, -4f))
        assertEquals(Color.White, liquidMix(Color.Black, Color.White, 4f))
    }

    @Test
    fun oldSavedConfigStillLoadsWithLiquidDefaults() {
        val old = """{"schemaVersion":1,"maxWidth":700,"contrast":0.6,"someFutureField":true}"""
        val loaded = json.decodeFromString(DesktopPillConfig.serializer(), old).validated()
        assertEquals(700, loaded.maxWidth)
        assertEquals(DesktopPillMaterial.LIQUID_GLASS, loaded.pillMaterial)
        assertEquals(DesktopGlassParams.Default, loaded.glass)
    }

    @Test
    fun newFieldsRoundTripAndAreValidated() {
        val config = DesktopPillConfig(
            pillMaterial = DesktopPillMaterial.CLASSIC,
            glass = DesktopGlassParams(refraction = 0.2f, frost = 0.9f),
        )
        val text = json.encodeToString(DesktopPillConfig.serializer(), config)
        assertEquals(config, json.decodeFromString(DesktopPillConfig.serializer(), text))
        val wild = DesktopPillConfig(glass = DesktopGlassParams(refraction = 9f, blurDp = -5f)).validated()
        assertEquals(1f, wild.glass.refraction)
        assertEquals(0f, wild.glass.blurDp)
    }
}
