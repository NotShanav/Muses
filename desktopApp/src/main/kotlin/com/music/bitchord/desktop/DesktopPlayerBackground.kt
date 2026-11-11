package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The full-screen player's background, chosen in Settings > Appearance > Player background.
 *
 * The choice only ever changes what is painted behind the player; the global Fluidity toggle is left
 * alone. Both modes plug into LocalPlayerBackdropOverride (see desktopPlayerBackdropOverride).
 */

/** What the full-screen player is painted on. */
internal enum class PlayerBackgroundMode(val label: String) {
    /** A glow in the colours of the playing cover, translucent over the window material. */
    ALBUM_REACTIVE("Album reactive"),

    /** The player as it was drawn before this setting: the Fluidity glow, or the artwork mesh. */
    ACRYLIC_GLASS("Acrylic glass"),
    ;

    companion object {
        val DEFAULT = ACRYLIC_GLASS

        /** A stored name back into a mode; anything unreadable is [DEFAULT]. */
        fun fromStored(raw: String?): PlayerBackgroundMode =
            PlayerBackgroundMode.entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

internal object DesktopPlayerBackgroundSettings {

    internal const val KEY = "player_background_mode"

    private val _mode = MutableStateFlow(
        PlayerBackgroundMode.fromStored(DesktopPersistence().string(KEY, PlayerBackgroundMode.DEFAULT.name)),
    )

    /** What Settings shows and writes. */
    val mode: StateFlow<PlayerBackgroundMode> = _mode

    fun set(value: PlayerBackgroundMode) {
        DesktopPersistence().saveString(KEY, value.name)
        _mode.value = value
    }
}

private const val PLAYER_BACKGROUND_FADE_MS = 220

/**
 * What LocalPlayerBackdropOverride is given. Null leaves the shared player drawing its own artwork
 * mesh, which is what Acrylic glass means when Fluidity is off; anything else is one stable renderer
 * that reads the mode itself, so a mode change never swaps the provided value or rebuilds the player.
 */
internal fun desktopPlayerBackdropOverride(
    mode: PlayerBackgroundMode,
    fluidity: Boolean,
): (@Composable (String?, Any?) -> Unit)? =
    if (mode == PlayerBackgroundMode.ALBUM_REACTIVE || fluidity) DesktopPlayerBackdropContent else null

private val DesktopPlayerBackdropContent: @Composable (String?, Any?) -> Unit = { artworkUrl, trackKey ->
    val mode by DesktopPlayerBackgroundSettings.mode.collectAsState()
    DesktopPlayerBackground(mode, artworkUrl, trackKey)
}

/**
 * The player's backdrop for [mode]. A change of mode crossfades on alpha alone (snaps under
 * "Reduce animation"); the outgoing renderer is composed only until the fade ends, so at rest
 * there is exactly one full-screen layer.
 */
@Composable
internal fun DesktopPlayerBackground(mode: PlayerBackgroundMode, artworkUrl: String?, trackKey: Any?) {
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    var current by remember { mutableStateOf(mode) }
    var outgoing by remember { mutableStateOf<PlayerBackgroundMode?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(mode, reduceAnimation) {
        if (mode == current) return@LaunchedEffect
        if (reduceAnimation) {
            outgoing = null
            current = mode
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        outgoing = current
        current = mode
        fade.snapTo(0f)
        fade.animateTo(1f, tween(PLAYER_BACKGROUND_FADE_MS))
        outgoing = null
    }

    Box(Modifier.fillMaxSize()) {
        outgoing?.let { old ->
            PlayerBackgroundLayer(old, artworkUrl, trackKey, Modifier.graphicsLayer { alpha = 1f - fade.value })
        }
        PlayerBackgroundLayer(current, artworkUrl, trackKey, Modifier.graphicsLayer { alpha = fade.value })
    }
}

@Composable
private fun PlayerBackgroundLayer(
    mode: PlayerBackgroundMode,
    artworkUrl: String?,
    trackKey: Any?,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (mode) {
            PlayerBackgroundMode.ALBUM_REACTIVE -> DesktopAlbumReactiveBackdrop(artworkUrl)
            // Today's Fluidity player, untouched. Without Fluidity the override is null and the
            // shared player draws its own mesh, so there is nothing to render here.
            PlayerBackgroundMode.ACRYLIC_GLASS -> {
                val fluidity by DesktopFluiditySettings.enabled.collectAsState()
                if (fluidity) DesktopFluidPlayerBackdrop(artworkUrl, trackKey)
            }
        }
    }
}

// ── Mode A ───────────────────────────────────────────────────────────────────────────────────────

private val AlbumSmoke = Color(0xFF0A0A10)

/** The same diagonal sheen fluidGlass lays over its tint, without the rim. */
private val AlbumSheen = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.08f),
    0.5f to Color.White.copy(alpha = 0.02f),
    1f to Color.Transparent,
)

/**
 * Darkens the top fifth and the lower half progressively: the title bar and the controls sit
 * there. Fractional stops, so one brush serves any window size.
 */
private val AlbumReadingScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.30f),
    0.20f to Color.Transparent,
    0.50f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.55f),
)

private const val ALBUM_TINT = 0.32f
private const val ALBUM_TINT_SOLID = 0.40f
private const val ALBUM_SCRIM_STRENGTH = 1.5f

/**
 * Album reactive: the cover's palette glow over the window material, one glass tint, and a reading
 * scrim. No blur anywhere.
 *
 * Readability, worst case, a cover that glows near white (the glows are capped at HSL lightness 0.6
 * but Screen-blend where they overlap). Alphas multiply transmission. Lower part of the screen:
 * the glow's own scrim (0.38 x 1.5 = 0.57 without a material) leaves 0.43, the tint 0.68, the
 * reading scrim 0.45, so a white pixel ends at 0.43 x 0.68 x 0.45 = 0.13 (sRGB), a relative
 * luminance of about 0.015 and a contrast against white text of about 16:1. Mid screen, where the
 * lyrics are: (0.16 + 0.38) / 2 x 1.5 = 0.41 leaves 0.59, times 0.68 = 0.40, luminance about 0.13,
 * contrast about 5.7:1. Both clear WCAG AA (4.5:1) for white text on a pure white sleeve.
 */
@Composable
private fun DesktopAlbumReactiveBackdrop(artworkUrl: String?) {
    val reduceDynamicBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    Box(Modifier.fillMaxSize()) {
        // overMaterial = true: with Mica or Acrylic active the base is translucent. The palette
        // crossfade, the cover-change swell and the drift all live in DesktopFluidBackdrop.
        DesktopFluidBackdrop(
            artworkUrl = artworkUrl,
            scrimStrength = ALBUM_SCRIM_STRENGTH,
            overMaterial = true,
        )
        // Static layers: they draw once, not per frame.
        Box(
            Modifier
                .fillMaxSize()
                .background(AlbumSmoke.copy(alpha = if (reduceDynamicBlur) ALBUM_TINT_SOLID else ALBUM_TINT))
                .drawBehind {
                    if (!reduceDynamicBlur) drawRect(AlbumSheen)
                    drawRect(AlbumReadingScrim)
                },
        )
    }
}
