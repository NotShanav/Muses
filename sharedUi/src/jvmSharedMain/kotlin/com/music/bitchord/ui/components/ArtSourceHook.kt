package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * UI U6: lets a host (the desktop app) learn where a song's artwork sits on screen, without the
 * shared cards knowing why. Default: nothing is added. The desktop provides a modifier that
 * records the artwork's window bounds for the art transition.
 */
val LocalArtSourceModifier = staticCompositionLocalOf<@Composable (String) -> Modifier> { { Modifier } }

/** Marks this artwork as the click source of [videoId] when the host asked for it; a no-op for null. */
@Composable
fun Modifier.artSourceFor(videoId: String?): Modifier =
    if (videoId == null) this else this.then(LocalArtSourceModifier.current(videoId))
