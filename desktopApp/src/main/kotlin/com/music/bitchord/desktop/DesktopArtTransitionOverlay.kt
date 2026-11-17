package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

/**
 * Draws the artwork transition's particles over the whole window. Put it last in the root Box, with
 * no padding or offset, so its pixels are the window pixels the bounds are given in. It has no
 * pointer input and draws nothing (and runs no frame loop) while no transition is active.
 */
@Composable
internal fun DesktopArtTransitionOverlay() {
    val isActive by DesktopArtTransition.active.collectAsState()
    var frame by remember { mutableStateOf(0L) }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        DesktopArtTransition.resetClock()
        while (true) {
            withFrameNanos { nanos ->
                DesktopArtTransition.tick(nanos)
                frame = nanos
            }
        }
    }

    if (!isActive) return

    Canvas(Modifier.fillMaxSize()) {
        // Reading the frame counter here is what redraws this canvas every frame.
        if (frame < 0L) return@Canvas
        val old = DesktopArtTransition.fading
        if (old != null) drawParticles(old)
        val cur = DesktopArtTransition.current
        if (cur != null) drawParticles(cur)
    }
}

/** One draw call per particle (round-rect while it is flying, plain rect once home), plus a soft halo on bright ones in motion. */
private fun DrawScope.drawParticles(sim: ArtParticleSim) {
    val px = sim.px
    val py = sim.py
    val dw = sim.dw
    val dh = sim.dh
    val alpha = sim.alpha
    val rad = sim.rad
    val glow = sim.glow
    val rgb = sim.rgb
    for (i in 0 until sim.count) {
        val a = alpha[i]
        if (a <= 0.004f) continue
        val w = dw[i]
        val h = dh[i]
        if (w <= 0f || h <= 0f) continue
        val cx = px[i]
        val cy = py[i]
        val c = rgb[i]
        val g = glow[i]
        if (g > 0.03f) {
            val gw = w * 2.4f
            val gh = h * 2.4f
            val ga = (a * g * 0.20f * 255f + 0.5f).toInt()
            if (ga > 0) {
                drawRoundRect(
                    color = Color((ga shl 24) or c),
                    topLeft = Offset(cx - gw * 0.5f, cy - gh * 0.5f),
                    size = Size(gw, gh),
                    cornerRadius = CornerRadius(min(gw, gh) * 0.5f),
                )
            }
        }
        val ai = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        val color = Color((ai shl 24) or c)
        val corner = rad[i] * 0.5f * min(w, h)
        if (corner < 0.3f) {
            drawRect(color = color, topLeft = Offset(cx - w * 0.5f, cy - h * 0.5f), size = Size(w, h))
        } else {
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - w * 0.5f, cy - h * 0.5f),
                size = Size(w, h),
                cornerRadius = CornerRadius(corner),
            )
        }
    }
}
