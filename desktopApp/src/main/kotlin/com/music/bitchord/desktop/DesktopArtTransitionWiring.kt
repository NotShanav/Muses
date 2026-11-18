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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInWindow
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
    val token = remember { Any() }
    val registered = remember { booleanArrayOf(false) }
    // Registered only while attached and visible (non-empty bounds); the last one to become visible wins.
    DisposableEffect(token) {
        onDispose {
            DesktopArtTransition.unregisterTarget(token)
            registered[0] = false
            holder[0] = Rect.Zero
        }
    }
    val active: State<Boolean> = DesktopArtTransition.active.collectAsState()
    val reveal: State<Float> = DesktopArtTransition.reveal.collectAsState()
    return this
        .onGloballyPositioned {
            val r = if (it.isAttached) it.boundsInWindow() else Rect.Zero
            holder[0] = r
            val visible = r.width >= 2f && r.height >= 2f
            if (visible && !registered[0]) {
                DesktopArtTransition.registerTarget(token, provider)
                registered[0] = true
            } else if (!visible && registered[0]) {
                DesktopArtTransition.unregisterTarget(token)
                registered[0] = false
            }
        }
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

/**
 * Last mouse press in window coordinates, recorded by [artPressTracker]. Read by playCurrent() to
 * decide whether a song change was a click (animate) or an auto-advance (do not animate).
 */
internal object DesktopArtPress {
    @Volatile var position: Offset? = null
    @Volatile var nanos: Long = 0L

    const val FRESH_MS = 600L

    /** The last press if it happened within [FRESH_MS], else null. */
    fun fresh(): Offset? {
        val p = position ?: return null
        val age = (System.nanoTime() - nanos) / 1_000_000L
        return if (nanos != 0L && age in 0..FRESH_MS) p else null
    }
}

/**
 * Observes mouse presses on the Initial pass (before any child) without consuming anything and
 * records them in window coordinates. Put on the app's root Box.
 */
@Composable
internal fun Modifier.artPressTracker(): Modifier {
    val origin = remember { arrayOf(Offset.Zero) }
    return this
        .onGloballyPositioned {
            origin[0] = it.positionInWindow()
            DesktopArtTransition.windowSize = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        val c = event.changes.firstOrNull() ?: continue
                        DesktopArtPress.position = origin[0] + c.position
                        DesktopArtPress.nanos = System.nanoTime()
                    }
                }
            }
        }
}

/**
 * Called from playCurrent(). Starts the transition only for a click (a press within [DesktopArtPress.FRESH_MS]),
 * never for auto-advance, and, in ON_CHANGE mode, only when the song actually changed.
 */
internal fun maybeStartArtTransition(
    videoId: String,
    thumbnailUrl: String?,
    previousVideoId: String?,
    mode: DesktopArtTransitionMode,
) {
    if (mode == DesktopArtTransitionMode.OFF) {
        println("ArtTransition skipped: disabled")
        return
    }
    val press = DesktopArtPress.fresh()
    if (press == null) {
        println("ArtTransition skipped: no fresh press")
        return
    }
    if (mode == DesktopArtTransitionMode.ON_CHANGE && videoId == previousVideoId) {
        println("ArtTransition skipped: same song")
        return
    }
    DesktopArtTransition.start(ArtTransitionRequest(videoId, thumbnailUrl, sourceBounds = null, pointer = press))
}

/** One-time host wiring: backdrop sink at startup, and the setting mapped onto DesktopArtTransition.enabled. */
@Composable
internal fun ArtTransitionHostEffects() {
    val config by DesktopAdvancedAppearance.config.collectAsState()
    DisposableEffect(Unit) {
        DesktopArtTransition.backdropSink = DesktopBackdropPulses
        onDispose { if (DesktopArtTransition.backdropSink === DesktopBackdropPulses) DesktopArtTransition.backdropSink = null }
    }
    DisposableEffect(config.artTransitionMode) {
        DesktopArtTransition.enabled = config.artTransitionMode != DesktopArtTransitionMode.OFF
        onDispose { }
    }
}
