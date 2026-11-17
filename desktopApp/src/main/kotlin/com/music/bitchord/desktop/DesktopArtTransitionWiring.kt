package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/*
 * Glue between the floating player pill(s) and DesktopArtTransition. Kept out of DesktopApp.kt so the
 * pill edits stay one-liners. While the transition is not active, everything here is a no-op:
 * alpha 1, normal Crossfade path.
 */

/** True while the transition is running and the artwork is not yet fully revealed. Recomposes only on change. */
@Composable
internal fun rememberArtRevealing(): Boolean {
    val active = DesktopArtTransition.active.collectAsState()
    val reveal = DesktopArtTransition.reveal.collectAsState()
    val revealing by remember { derivedStateOf { active.value && reveal.value < 1f } }
    return revealing
}

/**
 * Reports this node's window-space bounds as the transition's landing rect and, while revealing,
 * fades it in with the reveal value. Alpha is read in the draw phase, so no per-frame recomposition.
 */
@Composable
internal fun Modifier.artTransitionTarget(): Modifier {
    val holder = remember { arrayOf(Rect.Zero) }
    val provider = remember { { holder[0] } }
    DisposableEffect(provider) {
        DesktopArtTransition.targetBounds = provider
        onDispose {
            if (DesktopArtTransition.targetBounds === provider) DesktopArtTransition.targetBounds = { Rect.Zero }
        }
    }
    val active: State<Boolean> = DesktopArtTransition.active.collectAsState()
    val reveal: State<Float> = DesktopArtTransition.reveal.collectAsState()
    return this
        .onGloballyPositioned { holder[0] = it.boundsInWindow() }
        .graphicsLayer { alpha = if (active.value && reveal.value < 1f) reveal.value.coerceIn(0f, 1f) else 1f }
}

/** 0 until ~0.6 of the reveal, then ramps to 1; always 1 when not active. */
internal fun pillRevealTextAlpha(active: Boolean, reveal: Float): Float =
    if (!active || reveal >= 1f) 1f else ((reveal - 0.6f) / 0.4f).coerceIn(0f, 1f)

/** Fades title/artist/progress and the controls with the reveal. The wrapper is always present, so nothing resets when it starts. */
@Composable
internal fun PillRevealFade(content: @Composable () -> Unit) {
    val active = DesktopArtTransition.active.collectAsState()
    val reveal = DesktopArtTransition.reveal.collectAsState()
    Box(Modifier.graphicsLayer { alpha = pillRevealTextAlpha(active.value, reveal.value) }) { content() }
}
