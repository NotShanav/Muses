package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * ODEON-FIX: the frosted pane under the glass pill. It is plain Haze blur plus a tint, the same
 * machinery the classic pill uses, so it always works and the pill's text is always readable. The
 * liquid shader ([glassSurface]) is only an overlay that adds the bent rim and the light on top.
 *
 * [tint] is the dark glass colour, [accent] the album colour; [params] sets how much blur and tint.
 * Without Haze (or with the dynamic blur reduced) it is a solid tint, never empty.
 */
@Composable
internal fun Modifier.glassPane(
    shape: Shape,
    params: DesktopGlassParams,
    tint: Color,
    accent: Color,
): Modifier {
    val haze = LocalDesktopHaze.current
    val reduceDynamicBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val p = params.validated()
    val colour = liquidMix(tint, accent, 0.30f * p.albumInfluence)
    val alpha = p.tintAlpha.coerceIn(0.12f, 0.9f)
    if (haze == null || reduceDynamicBlur) {
        return this.background(colour.copy(alpha = maxOf(alpha, 0.6f)), shape)
    }
    val blur = p.blurDp.coerceIn(8f, 40f)
    return this.hazeEffect(state = haze) {
        blurEnabled = true
        backgroundColor = DesktopSurface
        blurRadius = blur.dp
        tints = listOf(HazeTint(colour.copy(alpha = alpha)))
        noiseFactor = 0.05f
    }
}
