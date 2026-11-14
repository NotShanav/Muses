package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** STUDIO G4: new config fields, built-in presets, import and export. */
class DesktopPillPresetsTest {
    @Test
    fun oldConfigWithoutNewFieldsLoadsWithDefaults() {
        val loaded = DesktopPillPresets.import("""{"schemaVersion":1,"maxWidth":640,"spinWhilePlaying":true}""")
        assertNotNull(loaded)
        assertEquals(640, loaded.maxWidth)
        assertEquals(0.97f, loaded.pressSquish)
        assertTrue(loaded.lightSweep && loaded.breathing && loaded.hoverGlow)
        assertEquals(DesktopPillMaterial.LIQUID_GLASS, loaded.pillMaterial)
    }

    @Test
    fun validatedClampsTheNewMotionField() {
        assertEquals(0.9f, DesktopPillConfig(pressSquish = 0.1f).validated().pressSquish)
        assertEquals(1f, DesktopPillConfig(pressSquish = 7f).validated().pressSquish)
        assertEquals(0.97f, DesktopPillConfig(pressSquish = Float.NaN).validated().pressSquish)
    }

    @Test
    fun everyBuiltInPresetAppliesAndStaysValid() {
        val base = DesktopPillConfig(userPresets = mapOf("mine" to "{}"), dockPosition = "LEFT", maxWidth = 800)
        for (name in DesktopPillPresets.BuiltIn) {
            val applied = DesktopPillPresets.apply(name, base)
            assertNotNull(applied, name)
            assertEquals(applied.validated(), applied, name)
            assertEquals(base.userPresets, applied.userPresets, name)
            assertEquals("LEFT", applied.dockPosition, name)
            assertEquals(800, applied.maxWidth, name)
        }
        assertNull(DesktopPillPresets.apply("nope", base))
    }

    @Test
    fun presetsChooseTheRightMaterial() {
        val base = DesktopPillConfig(material = DesktopMiniPlayerMaterial.SOLID, pillMaterial = DesktopPillMaterial.CLASSIC)
        val crystal = DesktopPillPresets.apply("Crystal", base)!!
        assertEquals(DesktopPillMaterial.LIQUID_GLASS, crystal.pillMaterial)
        assertEquals(DesktopMiniPlayerMaterial.GLASS, crystal.material)
        val solid = DesktopPillPresets.apply("Solid", crystal)!!
        assertEquals(DesktopPillMaterial.CLASSIC, solid.pillMaterial)
        assertEquals(DesktopMiniPlayerMaterial.SOLID, solid.material)
    }

    @Test
    fun auroraUsesTheMostAlbumColourAndMinimalHidesExtras() {
        val base = DesktopPillConfig()
        val aurora = DesktopPillPresets.apply("Aurora", base)!!
        assertTrue(DesktopPillPresets.BuiltIn.filter { it != "Aurora" && it != "Solid" }
            .all { aurora.glass.albumInfluence >= DesktopPillPresets.apply(it, base)!!.glass.albumInfluence })
        val minimal = DesktopPillPresets.apply("Minimal", base)!!
        assertTrue("shuffle" in minimal.hiddenElements && "repeat" in minimal.hiddenElements)
        assertEquals(DesktopMiniPlayerHeight.COMPACT, minimal.height)
    }

    @Test
    fun exportImportRoundTripKeepsTheLookButNotTheUsersPresets() {
        val config = DesktopPillPresets.apply("Frosted", DesktopPillConfig(userPresets = mapOf("a" to "{}")))!!
        val text = DesktopPillPresets.export(config)
        assertTrue("\"a\"" !in text)
        val back = DesktopPillPresets.import(text, keepPresets = mapOf("keep" to "{}"))!!
        assertEquals(config.copy(userPresets = mapOf("keep" to "{}")), back)
    }

    @Test
    fun importRejectsGarbageAndHugeText() {
        assertNull(DesktopPillPresets.import(""))
        assertNull(DesktopPillPresets.import("not json"))
        assertNull(DesktopPillPresets.import("x".repeat(DesktopPillPresets.MAX_IMPORT_CHARS + 1)))
        val wild = DesktopPillPresets.import("""{"maxWidth":99999,"glass":{"refraction":50}}""")!!
        assertEquals(920, wild.maxWidth)
        assertEquals(1f, wild.glass.refraction)
    }
}
