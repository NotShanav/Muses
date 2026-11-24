package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import java.util.concurrent.ConcurrentHashMap

/*
 * UI U6: where on screen a click can legitimately start the art transition.
 *  - SOURCE: the artwork or row of one song (has a video id).
 *  - AREA:   a page whose cards are drawn by the shared UI, which cannot register itself: a press
 *            inside it counts as a click on content, but gives no bounds.
 *  - BLOCK:  transport (the pill): a press there never starts the effect (Next, Previous, ...).
 */

internal data class ArtRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

internal enum class ArtZoneKind { SOURCE, AREA, BLOCK }

internal data class ArtZone(val kind: ArtZoneKind, val videoId: String?, val rect: ArtRect)

internal data class ArtPressDecision(val fire: Boolean, val bounds: ArtRect?)

/** Pure: decides from the press point and the registered zones. No Compose, no Windows. */
internal object ArtSourceRules {
    private val NO = ArtPressDecision(false, null)

    fun decide(videoId: String, press: Pair<Float, Float>?, zones: Collection<ArtZone>): ArtPressDecision {
        if (press == null) return NO // media key, auto-advance or nothing pressed
        val (x, y) = press
        val hit = zones.filter { it.rect.contains(x, y) }
        if (hit.any { it.kind == ArtZoneKind.BLOCK }) return NO
        val sources = hit.filter { it.kind == ArtZoneKind.SOURCE }
        // The smallest source that holds the press: the artwork inside a row beats the row.
        sources.filter { it.videoId == videoId }.minByOrNull { it.rect.area }?.let { return ArtPressDecision(true, it.rect) }
        sources.minByOrNull { it.rect.area }?.let { return ArtPressDecision(true, it.rect) }
        if (hit.any { it.kind == ArtZoneKind.AREA }) return ArtPressDecision(true, null)
        return NO
    }
}

/** The live registry. Entries come and go with the composables that own them. */
internal object DesktopArtSources {
    private val zones = ConcurrentHashMap<Any, ArtZone>()

    fun set(token: Any, zone: ArtZone) { zones[token] = zone }

    fun remove(token: Any) { zones.remove(token) }

    fun snapshot(): List<ArtZone> = zones.values.toList()
}

@Composable
private fun Modifier.artZone(kind: ArtZoneKind, videoId: String?): Modifier {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { DesktopArtSources.remove(token) } }
    return onGloballyPositioned {
        if (!it.isAttached) {
            DesktopArtSources.remove(token)
        } else {
            val b = it.boundsInWindow()
            if (b.width >= 2f && b.height >= 2f) {
                DesktopArtSources.set(token, ArtZone(kind, videoId, ArtRect(b.left, b.top, b.right, b.bottom)))
            } else {
                DesktopArtSources.remove(token)
            }
        }
    }
}

/** This node is the artwork (or row) of [videoId]; a click on it starts the transition from here. */
@Composable
internal fun Modifier.artSource(videoId: String): Modifier = artZone(ArtZoneKind.SOURCE, videoId)

/** A page of shared cards: presses count as content clicks, without bounds. */
@Composable
internal fun Modifier.artSourceArea(): Modifier = artZone(ArtZoneKind.AREA, null)

/** Transport: a press here never starts the transition. */
@Composable
internal fun Modifier.artNoFire(): Modifier = artZone(ArtZoneKind.BLOCK, null)

/** UI U6: what the shared cards call (through `LocalArtSourceModifier`) to mark their artwork as a click source. */
internal val DesktopArtSourceProvider: @Composable (String) -> Modifier = { id -> Modifier.artSource(id) }
