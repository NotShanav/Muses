package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * BACKDROP-STYLE: what kind of background the owner wants behind the app and the player.
 *
 * The album colours are read once per cover (DesktopAlbumPalette.kt). A "look" then restyles that palette
 * cheaply when it is drawn: how bright and how colourful the glows are, how much colour washes the whole
 * window, and how dark the base is. Brightness and colour intensity are two extra sliders on top of the look.
 * Everything here is pure data plus persisted settings, so it can be unit tested.
 */

internal enum class DesktopBackdropLook {
    /** Bright, saturated: colours pushed up, a strong wash, a lighter base. */
    VIVID,

    /** The cover as it is, with a gentle lift. */
    NATURAL,

    /** Calm and dark: muted colours, a low wash. */
    DEEP,
    ;

    companion object {
        val DEFAULT = VIVID

        /** A stored name, or [DEFAULT] when it is missing or unknown. */
        fun fromStored(name: String?): DesktopBackdropLook =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** How one look changes the palette and the layers drawn from it. */
internal data class DesktopBackdropTuning(
    /** Multiplies the chroma (vividness) of every coloured glow. 1 keeps the extracted palette. */
    val chromaScale: Float,
    /** Darkest OKLab lightness a coloured glow may have. */
    val chromaticFloor: Float,
    /** Darkest lightness of a white, grey or black glow. */
    val neutralFloor: Float,
    /** Lightest lightness any glow may have. */
    val lightCap: Float,
    /** Added to every glow's lightness after the floor and the cap (a Deep look sits lower, a Vivid one higher). */
    val lightnessShift: Float,
    /** Lightness of the base under the glows. */
    val baseLightness: Float,
    /** Multiplies the alpha of the four glows (each is still capped below full). */
    val roleAlphaScale: Float,
    /** Alpha of the window-wide wash of the dominant colour. */
    val washAlpha: Float,
    /** Alpha of the base over a translucent window material (Mica/Acrylic). */
    val translucentBaseAlpha: Float,
    /** The palette's intensity never goes below this. */
    val intensityMin: Float,
    /** Multiplies the palette's intensity. */
    val intensityScale: Float,
)

internal object DesktopBackdropTunings {
    fun of(look: DesktopBackdropLook): DesktopBackdropTuning = when (look) {
        DesktopBackdropLook.VIVID -> DesktopBackdropTuning(
            chromaScale = 1.25f,
            chromaticFloor = 0.60f,
            neutralFloor = 0.26f,
            lightCap = 0.86f,
            lightnessShift = 0.03f,
            baseLightness = 0.30f,
            roleAlphaScale = 1.10f,
            washAlpha = 0.34f,
            translucentBaseAlpha = 0.58f,
            intensityMin = 0.92f,
            intensityScale = 1f,
        )
        DesktopBackdropLook.NATURAL -> DesktopBackdropTuning(
            chromaScale = 1f,
            chromaticFloor = ALBUM_DARK_FLOOR_CHROMATIC,
            neutralFloor = 0.20f,
            lightCap = ALBUM_LIGHT_CAP,
            lightnessShift = 0f,
            baseLightness = ALBUM_BASE_LIGHTNESS,
            roleAlphaScale = 1f,
            washAlpha = FLUID_WASH_ALPHA,
            translucentBaseAlpha = 0.46f,
            intensityMin = 0f,
            intensityScale = 1f,
        )
        DesktopBackdropLook.DEEP -> DesktopBackdropTuning(
            chromaScale = 0.80f,
            chromaticFloor = 0.30f,
            neutralFloor = 0.16f,
            lightCap = 0.72f,
            lightnessShift = -0.10f,
            baseLightness = 0.15f,
            roleAlphaScale = 0.78f,
            washAlpha = 0.10f,
            translucentBaseAlpha = 0.40f,
            intensityMin = 0f,
            intensityScale = 0.85f,
        )
    }
}

/** The owner's choices, persisted. Defaults: Vivid, both sliders in the middle, no grain. */
internal object DesktopBackdropSettings {
    internal const val KEY_LOOK = "backdrop_look"
    internal const val KEY_BRIGHTNESS = "backdrop_brightness"
    internal const val KEY_VIBRANCE = "backdrop_vibrance"
    internal const val KEY_GRAIN = "backdrop_grain"

    private val _look = MutableStateFlow(
        DesktopBackdropLook.fromStored(DesktopPersistence().string(KEY_LOOK, DesktopBackdropLook.DEFAULT.name)),
    )
    private val _brightness = MutableStateFlow(readUnit(KEY_BRIGHTNESS))
    private val _vibrance = MutableStateFlow(readUnit(KEY_VIBRANCE))
    private val _grain = MutableStateFlow(DesktopPersistence().boolean(KEY_GRAIN, false))

    val look: StateFlow<DesktopBackdropLook> = _look

    /** 0..1, 0.5 = no change. */
    val brightness: StateFlow<Float> = _brightness

    /** 0..1, 0.5 = no change. */
    val vibrance: StateFlow<Float> = _vibrance

    /** A faint film grain over the background. Off by default: it made the glow look rough. */
    val grain: StateFlow<Boolean> = _grain

    fun setLook(value: DesktopBackdropLook) {
        DesktopPersistence().saveString(KEY_LOOK, value.name)
        _look.value = value
    }

    fun setBrightness(value: Float) {
        val v = unit(value)
        DesktopPersistence().saveString(KEY_BRIGHTNESS, v.toString())
        _brightness.value = v
    }

    fun setVibrance(value: Float) {
        val v = unit(value)
        DesktopPersistence().saveString(KEY_VIBRANCE, v.toString())
        _vibrance.value = v
    }

    fun setGrain(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_GRAIN, value)
        _grain.value = value
    }

    fun reset() {
        setLook(DesktopBackdropLook.DEFAULT)
        setBrightness(0.5f)
        setVibrance(0.5f)
        setGrain(false)
    }

    private fun readUnit(key: String): Float = DesktopBackdropMath.parseUnit(DesktopPersistence().string(key, ""))

    private fun unit(value: Float): Float = DesktopBackdropMath.unit(value)
}

/** The pure number handling for the two sliders (no persistence, so it can be tested anywhere). */
internal object DesktopBackdropMath {
    /** A number kept in 0..1; not-a-number becomes the middle, 0.5. */
    fun unit(value: Float): Float = if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)

    /** A stored 0..1 number, or the middle (0.5) when it is missing or unreadable. */
    fun parseUnit(stored: String?): Float = unit(stored?.toFloatOrNull() ?: 0.5f)
}
