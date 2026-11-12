package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Desktop-owned appearance values which did not have a legacy preference key. */
@Serializable
internal data class DesktopAppearanceConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val activePresetId: String? = null,
    val textScale: Float = 1f,
    val fontFamily: DesktopFontFamily = DesktopFontFamily.MUSES,
    val density: DesktopUiDensity = DesktopUiDensity.COMFORTABLE,
    val shelfSpacing: Int = 30,
    val homeLeadArtwork: Boolean = false,
    val miniPlayerHeight: DesktopMiniPlayerHeight = DesktopMiniPlayerHeight.DEFAULT,
    val miniPlayerArtwork: Boolean = true,
    val miniPlayerMetadata: Boolean = true,
    val miniPlayerProgress: DesktopMiniProgress = DesktopMiniProgress.THIN,
    val miniPlayerArtShape: DesktopArtworkShape = DesktopArtworkShape.ROUNDED,
    val miniPlayerMaterial: DesktopMiniPlayerMaterial = DesktopMiniPlayerMaterial.GLASS,
    val miniPlayerShowShuffle: Boolean = true,
    val miniPlayerShowRepeat: Boolean = true,
    val miniPlayerTapExpands: Boolean = true,
    val paletteStrength: Float = 1f,
    val amoledDark: Boolean = false,
    val fixedAccentArgb: Long? = null,
) {
    fun validated() = copy(
        schemaVersion = CURRENT_SCHEMA,
        textScale = textScale.takeIf { it.isFinite() }?.coerceIn(0.85f, 1.30f) ?: 1f,
        shelfSpacing = shelfSpacing.coerceIn(20, 44),
        paletteStrength = paletteStrength.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f,
        fixedAccentArgb = fixedAccentArgb?.takeIf { (it ushr 24) == 0xFFL && contrastRatio(it, if (amoledDark) 0xFF000000L else 0xFF202020L) >= 4.5 },
    )

    private fun contrastRatio(foreground: Long, background: Long): Double {
        fun luminance(color: Long): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 0xFF).toDouble() / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
internal enum class DesktopUiDensity { COMPACT, COMFORTABLE, SPACIOUS }
@Serializable
internal enum class DesktopFontFamily { MUSES, SYSTEM, SERIF, MONOSPACE }
@Serializable
internal enum class DesktopMiniPlayerHeight { COMPACT, DEFAULT, LARGE }
@Serializable
internal enum class DesktopMiniProgress { HIDDEN, THIN, FULL }
@Serializable
internal enum class DesktopArtworkShape { SQUARE, ROUNDED, CIRCLE }
@Serializable
internal enum class DesktopMiniPlayerMaterial { GLASS, SOLID, ACCENT }

@Serializable
private data class DesktopAppearanceFile(
    val schemaVersion: Int = DesktopAppearanceConfig.CURRENT_SCHEMA,
    val appVersion: String,
    val appearance: DesktopAppearanceConfig,
    val desktopSettings: DesktopSavedAppearancePreset? = null,
)

internal data class DesktopAppearanceImport(
    val appearance: DesktopAppearanceConfig,
    val desktopSettings: DesktopSavedAppearancePreset?,
)

@Serializable
internal data class DesktopSavedAppearancePreset(
    val name: String,
    val appearance: DesktopAppearanceConfig,
    val interfaceMode: String,
    val fluidity: Boolean,
    val reduceBlur: Boolean,
    val reduceMotion: Boolean,
    val playerBackground: String,
    val dockPosition: String,
    val dockWidth: Int,
    val dockBottomInset: Int,
    val dockCornerRadius: Int,
    val dockContrast: Float,
    val dockRim: Float,
    val titleBar: Boolean = false,
    val windowMaterial: String = "MICA",
)

/** The metadata registry used by search, modified markers, reset, and the editor. */
internal data class DesktopAppearanceSetting(
    val id: String,
    val category: String,
    val label: String,
    val description: String,
    val keywords: Set<String>,
    val expertOnly: Boolean = false,
)

internal object DesktopAppearanceRegistry {
    val settings = listOf(
        DesktopAppearanceSetting("desktop.interface", "Navigation", "Desktop interface", "Switch between the Modern shell and the classic Legacy sidebar.", setOf("modern", "legacy", "navigation", "sidebar")),
        DesktopAppearanceSetting("desktop.text_scale", "Typography", "Text scale", "Scale desktop typography while preserving the minimum readable size.", setOf("font", "size", "text", "readability")),
        DesktopAppearanceSetting("desktop.font_family", "Typography", "Font family", "Choose the bundled Muses font or a system font family.", setOf("font", "serif", "monospace", "system")),
        DesktopAppearanceSetting("desktop.density", "Home and Library", "Home feed density", "Choose compact, comfortable, or spacious vertical spacing for Home shelves.", setOf("spacing", "compact", "comfortable", "spacious")),
        DesktopAppearanceSetting("desktop.shelf_spacing", "Home and Library", "Home shelf spacing", "Set the vertical gap between desktop home shelves.", setOf("home", "library", "feed", "sections")),
        DesktopAppearanceSetting("desktop.home_lead_artwork", "Home and Library", "Home lead artwork", "Enable the larger lead artwork treatment on the Modern home feed.", setOf("home", "hero", "artwork", "layout")),
        DesktopAppearanceSetting("desktop.palette_strength", "Theme and colour", "Artwork palette strength", "Adjust how strongly cover-derived colours affect Fluidity backgrounds.", setOf("palette", "artwork", "colour", "fluidity")),
        DesktopAppearanceSetting("desktop.fixed_accent", "Theme and colour", "Fixed accent colour", "Set an accent colour that passes the desktop contrast floor.", setOf("accent", "colour", "expert")),
        DesktopAppearanceSetting("desktop.amoled", "Theme and colour", "AMOLED dark surfaces", "Use true black for the desktop app background.", setOf("black", "dark", "amoled")),
        DesktopAppearanceSetting("desktop.player_background", "Full player", "Player background", "Choose the existing album-reactive or acrylic-glass background.", setOf("now playing", "album", "acrylic", "background")),
        DesktopAppearanceSetting("desktop.fluidity", "Glass and motion", "Fluidity", "Use the existing artwork-coloured glass surfaces.", setOf("glass", "fluid", "blur", "liquid")),
        DesktopAppearanceSetting("desktop.reduce_blur", "Glass and motion", "Reduce dynamic blur", "Use solid fallbacks where blur or dynamic glass would be distracting.", setOf("glass", "blur", "performance", "accessibility")),
        DesktopAppearanceSetting("desktop.reduce_motion", "Glass and motion", "Reduce animations", "Disable spring-heavy desktop motion where supported.", setOf("motion", "animation", "accessibility")),
        DesktopAppearanceSetting("desktop.dock", "Mini-player", "Floating player appearance", "Position and tune the floating mini-player.", setOf("dock", "mini player", "position", "width", "corner", "contrast", "rim")),
        DesktopAppearanceSetting("desktop.dock_contents", "Mini-player", "Mini-player contents", "Adjust artwork, track text, progress, and optional shortcut visibility.", setOf("artwork", "title", "artist", "progress", "shuffle", "repeat", "tap")),
        DesktopAppearanceSetting("desktop.dock_material", "Mini-player", "Mini-player material", "Choose the floating player glass, solid, or accent-tinted surface.", setOf("glass", "solid", "accent", "background")),
        DesktopAppearanceSetting("desktop.window_material", "Window", "Window material", "Choose the supported Windows desktop material.", setOf("mica", "acrylic", "windows", "title bar")),
    )
}

internal object DesktopAdvancedAppearance {
    private const val CONFIG_KEY = "desktop_appearance_config_v1"
    private const val PRESETS_KEY = "desktop_appearance_user_presets_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistence = DesktopPersistence()

    private fun load(): DesktopAppearanceConfig {
        val raw = persistence.string(CONFIG_KEY, "")
        val decoded = raw.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString<DesktopAppearanceConfig>(it) }.getOrNull()
        }
        // Unknown future versions are ignored safely; known older payloads are forward-filled by
        // defaults and validated field by field.
        return decoded?.takeIf { it.schemaVersion <= DesktopAppearanceConfig.CURRENT_SCHEMA }
            ?.validated() ?: DesktopAppearanceConfig()
    }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<DesktopAppearanceConfig> = _config
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing
    private var hasPreview = false
    private var previewStart: DesktopAppearanceConfig? = null

    fun update(transform: (DesktopAppearanceConfig) -> DesktopAppearanceConfig) {
        val next = resolve(transform)
        persistence.saveString(CONFIG_KEY, json.encodeToString(next))
        _config.value = next
        hasPreview = false
        previewStart = null
        _previewing.value = false
    }

    fun preview(transform: (DesktopAppearanceConfig) -> DesktopAppearanceConfig) {
        if (!hasPreview) previewStart = _config.value
        _config.value = resolve(transform)
        hasPreview = true
        _previewing.value = true
    }

    fun commitPreview() {
        if (!hasPreview) return
        persistence.saveString(CONFIG_KEY, json.encodeToString(_config.value))
        hasPreview = false
        previewStart = null
        _previewing.value = false
    }

    fun cancelPreview() {
        if (!hasPreview) return
        _config.value = previewStart ?: load()
        hasPreview = false
        previewStart = null
        _previewing.value = false
    }

    private fun resolve(transform: (DesktopAppearanceConfig) -> DesktopAppearanceConfig): DesktopAppearanceConfig {
        val previous = _config.value
        val transformed = transform(previous)
        val selection = if (transformed != previous && transformed.activePresetId == previous.activePresetId) null else transformed.activePresetId
        return transformed.copy(activePresetId = selection).validated()
    }

    fun resetTypography() = update { it.copy(textScale = 1f, fontFamily = DesktopFontFamily.MUSES) }
    fun setFixedAccent(argb: Long?): Boolean {
        val checked = _config.value.copy(fixedAccentArgb = argb).validated()
        if (argb != null && checked.fixedAccentArgb == null) return false
        update { it.copy(fixedAccentArgb = argb) }
        return true
    }
    fun resetDensity() = update { it.copy(density = DesktopUiDensity.COMFORTABLE) }
    fun resetHome() = update { it.copy(density = DesktopUiDensity.COMFORTABLE, shelfSpacing = 30, homeLeadArtwork = false) }
    fun resetColors() = update { it.copy(amoledDark = false, fixedAccentArgb = null, paletteStrength = 1f) }
    fun resetAllNewValues() = update { DesktopAppearanceConfig() }

    fun encode(config: DesktopAppearanceConfig = _config.value): String = json.encodeToString(config.validated())

    fun savedPresets(): List<DesktopSavedAppearancePreset> =
        runCatching { json.decodeFromString<List<DesktopSavedAppearancePreset>>(persistence.string(PRESETS_KEY, "[]")) }
            .getOrDefault(emptyList()).take(8)

    fun savePreset(preset: DesktopSavedAppearancePreset): Boolean {
        val name = preset.name.trim().take(32)
        if (name.isBlank()) return false
        val entries = savedPresets().filterNot { it.name.equals(name, ignoreCase = true) }
        if (entries.size >= 8) return false
        persistence.saveString(PRESETS_KEY, json.encodeToString(entries + preset.copy(name = name, appearance = preset.appearance.validated())))
        return true
    }

    fun renamePreset(oldName: String, newName: String): Boolean {
        val name = newName.trim().take(32)
        if (name.isBlank()) return false
        val entries = savedPresets()
        if (entries.any { it.name.equals(name, ignoreCase = true) && !it.name.equals(oldName, ignoreCase = true) }) return false
        val updated = entries.map { if (it.name.equals(oldName, ignoreCase = true)) it.copy(name = name) else it }
        if (updated == entries) return false
        persistence.saveString(PRESETS_KEY, json.encodeToString(updated))
        return true
    }

    fun deletePreset(name: String) {
        persistence.saveString(PRESETS_KEY, json.encodeToString(savedPresets().filterNot { it.name.equals(name, ignoreCase = true) }))
    }
    fun export(version: String): String = json.encodeToString(
        DesktopAppearanceFile(
            appVersion = version,
            appearance = _config.value.validated(),
            desktopSettings = DesktopSavedAppearancePreset(
                name = "Exported appearance",
                appearance = _config.value,
                interfaceMode = DesktopInterfaceSettings.mode.value.name,
                fluidity = DesktopFluiditySettings.enabled.value,
                reduceBlur = DesktopAppearanceSettings.reduceDynamicBlur.value,
                reduceMotion = DesktopAppearanceSettings.reduceAnimation.value,
                playerBackground = DesktopPlayerBackgroundSettings.mode.value.name,
                dockPosition = DesktopMiniPlayerAppearance.position.value.name,
                dockWidth = DesktopMiniPlayerAppearance.width.value,
                dockBottomInset = DesktopMiniPlayerAppearance.bottomInset.value,
                dockCornerRadius = DesktopMiniPlayerAppearance.cornerRadius.value,
                dockContrast = DesktopMiniPlayerAppearance.contrast.value,
                dockRim = DesktopMiniPlayerAppearance.rim.value,
                titleBar = DesktopTitleBarSetting.enabled.value,
                windowMaterial = DesktopWindowBackdrop.selected.value.name,
            ),
        ),
    )

    fun decode(raw: String): DesktopAppearanceImport {
        require(raw.length <= 65_536) { "Appearance file is larger than 64 KB" }
        val wrapper = runCatching { json.decodeFromString<DesktopAppearanceFile>(raw) }.getOrNull()
        require(wrapper == null || wrapper.schemaVersion <= DesktopAppearanceConfig.CURRENT_SCHEMA) {
            "This appearance file uses a newer schema"
        }
        val value = wrapper?.appearance ?: json.decodeFromString<DesktopAppearanceConfig>(raw)
        require(value.schemaVersion <= DesktopAppearanceConfig.CURRENT_SCHEMA) { "This appearance file uses a newer schema" }
        return DesktopAppearanceImport(value.validated(), wrapper?.desktopSettings)
    }
}
