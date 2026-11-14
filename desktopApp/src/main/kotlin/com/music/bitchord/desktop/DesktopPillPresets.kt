package com.music.bitchord.desktop

import kotlinx.serialization.json.Json

/**
 * STUDIO G4: built-in pill presets and the JSON import/export of one pill. Pure functions, no UI,
 * so they are tested. A preset changes the look (material, glass, shape, height, contrast, rim,
 * motion) and leaves the layout (zones, position, width, inset), the artwork choices and the user's
 * saved presets alone, except where the preset's idea needs it (Minimal hides shuffle and repeat).
 */
internal object DesktopPillPresets {
    val BuiltIn: List<String> = listOf("Crystal", "Frosted", "Aurora", "Minimal", "Solid")

    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Largest import accepted, in characters (a pill config is a few hundred). */
    const val MAX_IMPORT_CHARS = 16_384

    /** The built-in preset [name] applied to [base], or null if there is no such preset. The result is validated. */
    fun apply(name: String, base: DesktopPillConfig): DesktopPillConfig? {
        val liquid = base.copy(pillMaterial = DesktopPillMaterial.LIQUID_GLASS, material = DesktopMiniPlayerMaterial.GLASS)
        val next = when (name) {
            "Crystal" -> liquid.copy(
                shape = DesktopPillShape.PILL,
                contrast = 0.48f,
                rim = 0.5f,
                glass = DesktopGlassParams(
                    refraction = 0.95f, bevelDp = 18f, magnify = 0.06f, dispersion = 0.45f, frost = 0.12f, blurDp = 6f,
                    specular = 0.85f, rim = 0.75f, tintAlpha = 0.18f, lightAngleDeg = 135f, albumInfluence = 0.35f,
                ),
                lightSweep = true, breathing = true, hoverGlow = true,
            )
            "Frosted" -> liquid.copy(
                contrast = 0.62f,
                rim = 0.3f,
                glass = DesktopGlassParams(
                    refraction = 0.45f, bevelDp = 12f, magnify = 0.02f, dispersion = 0.1f, frost = 0.85f, blurDp = 22f,
                    specular = 0.45f, rim = 0.4f, tintAlpha = 0.35f, lightAngleDeg = 135f, albumInfluence = 0.4f,
                ),
            )
            "Aurora" -> liquid.copy(
                contrast = 0.5f,
                rim = 0.4f,
                glass = DesktopGlassParams(
                    refraction = 0.7f, bevelDp = 16f, magnify = 0.04f, dispersion = 0.35f, frost = 0.35f, blurDp = 12f,
                    specular = 0.6f, rim = 0.6f, tintAlpha = 0.22f, lightAngleDeg = 120f, albumInfluence = 1f,
                ),
            )
            "Minimal" -> liquid.copy(
                height = DesktopMiniPlayerHeight.COMPACT,
                contrast = 0.6f,
                rim = 0.2f,
                hiddenElements = base.hiddenElements + setOf("shuffle", "repeat"),
                glass = DesktopGlassParams(
                    refraction = 0.25f, bevelDp = 8f, magnify = 0f, dispersion = 0f, frost = 0.5f, blurDp = 14f,
                    specular = 0.2f, rim = 0.25f, tintAlpha = 0.4f, lightAngleDeg = 135f, albumInfluence = 0.15f,
                ),
                lightSweep = false, breathing = false,
            )
            "Solid" -> base.copy(
                pillMaterial = DesktopPillMaterial.CLASSIC,
                material = DesktopMiniPlayerMaterial.SOLID,
                contrast = 0.85f,
                rim = 0.25f,
            )
            else -> return null
        }
        return next.copy(userPresets = base.userPresets).validated()
    }

    /** One pill as JSON (the user's saved presets are left out, they are the user's own list). */
    fun export(config: DesktopPillConfig): String =
        codec.encodeToString(DesktopPillConfig.serializer(), config.copy(userPresets = emptyMap()).validated())

    /**
     * A pill read from [text], validated; the caller's [keepPresets] stay. Null when the text is too
     * long, is not JSON for a pill, or fails to decode. Unknown keys and missing keys are fine.
     */
    fun import(text: String, keepPresets: Map<String, String> = emptyMap()): DesktopPillConfig? {
        if (text.isBlank() || text.length > MAX_IMPORT_CHARS) return null
        val read = runCatching { codec.decodeFromString(DesktopPillConfig.serializer(), text) }.getOrNull() ?: return null
        return read.copy(userPresets = keepPresets).validated()
    }
}
