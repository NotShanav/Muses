package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.rememberArtworkColors
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Fluidity mode: BitChord's take on the Hermes "fluidity" skin.
 *
 * Hermes does this in CSS (blurred blobs, ::before frost layers, SVG displacement lenses). Compose
 * has none of those, so the same ideas are rebuilt with Compose primitives:
 *
 *   - living backdrop      -> [DesktopFluidBackdrop]: radial-gradient blobs coloured from the album
 *                             art, drifting on slow Lissajous paths. No blur RenderEffect is used,
 *                             a radial gradient is already soft, so only draw calls are animated.
 *   - frosted glass        -> [fluidGlass]: the app's existing Haze blur, plus a sheen and a 1dp
 *                             gradient "refraction rim".
 *   - spring motion        -> [fluidEnter] for dialogs and a spring on the sidebar highlight.
 *   - pause when idle      -> the clock stops while the window is hidden, unfocused or when
 *                             "Reduce animation" is on (Hermes' data-paused).
 *
 * Everything is gated on [DesktopFluiditySettings.enabled]; with it off nothing here runs and the
 * app draws exactly as before.
 */
internal object DesktopFluiditySettings {

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_FLUIDITY, false))
    private val _subtle = MutableStateFlow(DesktopPersistence().boolean(KEY_FLUIDITY_SUBTLE, false))

    /** The master switch: liquid glass surfaces and the album-art backdrop. */
    val enabled: StateFlow<Boolean> = _enabled

    /** Calmer variant: two blobs, no orbs. Cheaper on the GPU. */
    val subtle: StateFlow<Boolean> = _subtle

    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_FLUIDITY, value)
        _enabled.value = value
    }

    fun setSubtle(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_FLUIDITY_SUBTLE, value)
        _subtle.value = value
    }

    internal const val KEY_FLUIDITY = "fluidity_mode"
    internal const val KEY_FLUIDITY_SUBTLE = "fluidity_subtle"
}

// ── Backdrop ─────────────────────────────────────────────────────────────────────────────────────

private const val BLOB_COUNT = 5
private const val FRAME_STEP_SECONDS = 1f / 30f

/** Where each blob lives and how it wanders. Periods run roughly 35 to 60 seconds. */
private class FluidBlob(
    val anchorX: Float,
    val anchorY: Float,
    val amplitude: Float,
    val speedX: Float,
    val speedY: Float,
    val phase: Float,
    val radius: Float,
    val alpha: Float,
)

private val FluidBlobs = listOf(
    FluidBlob(0.20f, 0.25f, 0.14f, 0.115f, 0.093f, 0.0f, 0.62f, 0.80f),
    FluidBlob(0.80f, 0.20f, 0.12f, -0.087f, 0.121f, 1.7f, 0.56f, 0.72f),
    FluidBlob(0.75f, 0.80f, 0.15f, 0.101f, -0.079f, 3.1f, 0.64f, 0.76f),
    FluidBlob(0.25f, 0.78f, 0.13f, -0.126f, 0.104f, 4.6f, 0.52f, 0.66f),
    FluidBlob(0.50f, 0.50f, 0.18f, 0.071f, 0.089f, 5.9f, 0.46f, 0.50f),
)

private val FluidFallbackBase = Color(0xFF3A1C71)

/**
 * The animated backdrop. Colours come from the artwork at [artworkUrl] and crossfade when it
 * changes; with no artwork it falls back to a violet scheme.
 *
 * Meant to sit at the very bottom of a layer (the page, or the full-screen player). A vignette and
 * a vertical scrim are painted over the blobs so text and controls stay readable on any cover.
 */
@Composable
internal fun DesktopFluidBackdrop(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    /** 1 for the page; the full-screen player can ask for more to carry white text. */
    scrimStrength: Float = 1f,
) {
    val subtle by DesktopFluiditySettings.subtle.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val windowVisible by DesktopWindowVisibility.visible.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val running = windowVisible && windowFocused && !reduceAnimation

    val artColors = rememberArtworkColors(artworkUrl).colors.ifEmpty { listOf(FluidFallbackBase) }
    val target = List(BLOB_COUNT) { index ->
        val source = artColors[index % artColors.size]
        // The fifth blob has no swatch of its own, so it is the first one turned round the wheel.
        val shifted = if (index >= artColors.size) source.hueShifted(34f * (index / artColors.size + 1)) else source
        shifted.fluidTuned()
    }
    val colors = target.mapIndexed { index, color ->
        animateColorAsState(color, tween(1400), label = "fluidBlob$index").value
    }
    val base by animateColorAsState(target.first().fluidDimmed(), tween(1400), label = "fluidBase")

    // The clock only advances while running, and keeps its value across a pause, so the blobs
    // resume where they stopped instead of jumping. Capped at 30 fps: the motion is a drift.
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                if (dt >= FRAME_STEP_SECONDS) {
                    time += dt.coerceAtMost(0.25f)
                    last = now
                }
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val reach = max(w, h)
        val t = time
        drawRect(base)

        val blobs = if (subtle) 2 else BLOB_COUNT
        for (i in 0 until blobs) {
            val blob = FluidBlobs[i]
            val centre = Offset(
                x = (blob.anchorX + blob.amplitude * cos(t * blob.speedX * TWO_PI + blob.phase)) * w,
                y = (blob.anchorY + blob.amplitude * sin(t * blob.speedY * TWO_PI + blob.phase * 1.3f)) * h,
            )
            val radius = reach * blob.radius
            val alpha = if (subtle) blob.alpha * 0.7f else blob.alpha
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors[i].copy(alpha = alpha),
                        colors[i].copy(alpha = alpha * 0.42f),
                        Color.Transparent,
                    ),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
            )
        }

        // Three soft glass orbs: a faint bright core and a hairline rim, drifting against the blobs.
        if (!subtle) {
            for (i in 0 until 3) {
                val centre = Offset(
                    x = (0.2f + 0.3f * i + 0.07f * sin(t * (0.05f + 0.013f * i) * TWO_PI + i * 2.1f)) * w,
                    y = (0.3f + 0.2f * i + 0.09f * cos(t * (0.04f + 0.011f * i) * TWO_PI + i * 1.3f)) * h,
                )
                val radius = min(w, h) * (0.10f + 0.03f * i)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.Transparent),
                        center = Offset(centre.x - radius * 0.25f, centre.y - radius * 0.3f),
                        radius = radius * 1.1f,
                    ),
                    radius = radius,
                    center = centre,
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.07f),
                    radius = radius,
                    center = centre,
                    style = Stroke(width = 1.2f),
                )
            }
        }

        // Vignette, then the legibility scrim.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                center = Offset(w / 2f, h / 2f),
                radius = reach * 0.78f,
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = (0.16f * scrimStrength).coerceAtMost(0.9f)),
                    Color.Black.copy(alpha = (0.38f * scrimStrength).coerceAtMost(0.9f)),
                ),
            ),
        )
    }
}

private const val TWO_PI = 6.2831855f

/** What the shared full-screen player is handed in Fluidity mode; see LocalPlayerBackdropOverride. */
internal val DesktopFluidPlayerBackdrop: @Composable (String?, Any?) -> Unit = { artworkUrl, _ ->
    DesktopFluidBackdrop(artworkUrl, scrimStrength = 1.35f)
}

// ── Glass ────────────────────────────────────────────────────────────────────────────────────────

/** The light catching a pane's edge: bright at the top left, a second glint at the bottom right. */
private val FluidRim = Brush.linearGradient(
    listOf(
        Color.White.copy(alpha = 0.55f),
        Color.White.copy(alpha = 0.10f),
        Color.White.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.26f),
    ),
)

/** A faint diagonal sheen over the frost. */
private val FluidSheen = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.45f to Color.White.copy(alpha = 0.025f),
    1f to Color.Transparent,
)

/**
 * A frosted pane: the page behind it blurred (Haze), tinted, with a sheen and a gradient rim.
 *
 * Only transform is ever animated on a pane, never its alpha, so the blur keeps sampling a fully
 * opaque backdrop (the Hermes "backdrop root" rule).
 */
@Composable
internal fun Modifier.fluidGlass(
    shape: Shape,
    solid: Color,
    tintAlpha: Float,
    blur: Dp = 24.dp,
    rimWidth: Dp = 1.dp,
): Modifier {
    val haze = LocalDesktopHaze.current
    val reduceDynamicBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val frosted = if (haze == null || reduceDynamicBlur) {
        this.background(solid.copy(alpha = 0.92f), shape)
    } else {
        this.hazeEffect(state = haze) {
            blurEnabled = true
            backgroundColor = DesktopSurface
            blurRadius = blur
            tints = listOf(HazeTint(DesktopSurface.copy(alpha = tintAlpha)))
            noiseFactor = 0.03f
        }
    }
    return frosted
        .background(FluidSheen, shape)
        .border(rimWidth, FluidRim, shape)
}

/** A block sitting on a glass card: no blur of its own, just a lift and the rim. */
internal fun Modifier.fluidInset(shape: Shape): Modifier =
    clip(shape)
        .background(Color.White.copy(alpha = 0.07f), shape)
        .background(FluidSheen, shape)
        .border(0.8.dp, FluidRim, shape)

// ── Motion ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Rises into place with a little overshoot, like a Hermes drawer or modal. Transform only.
 * A no-op unless Fluidity is on.
 */
internal fun Modifier.fluidEnter(): Modifier = composed {
    val fluid by DesktopFluiditySettings.enabled.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fluid, reduceAnimation) {
        if (!fluid || reduceAnimation) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
        }
    }
    graphicsLayer {
        val p = progress.value
        val s = 0.94f + 0.06f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * 16.dp.toPx()
    }
}

// ── Colour helpers ───────────────────────────────────────────────────────────────────────────────

private class Hsl(val h: Float, val s: Float, val l: Float)

private fun Color.toHsl(): Hsl {
    val maxC = max(red, max(green, blue))
    val minC = min(red, min(green, blue))
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return Hsl(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (maxC) {
        red -> 60f * (((green - blue) / d) % 6f)
        green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }
    return Hsl(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
}

private fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

private fun Color.hueShifted(degrees: Float): Color {
    val hsl = toHsl()
    return hslColor((hsl.h + degrees) % 360f, hsl.s, hsl.l)
}

/** Rich but never muddy or blinding: saturation lifted, lightness held in a band text can sit on. */
private fun Color.fluidTuned(): Color {
    val hsl = toHsl()
    return hslColor(hsl.h, (hsl.s * 1.3f).coerceIn(0.35f, 1f), hsl.l.coerceIn(0.28f, 0.52f))
}

/** The floor under the blobs: the first colour, very dark. */
private fun Color.fluidDimmed(): Color {
    val hsl = toHsl()
    return hslColor(hsl.h, hsl.s.coerceAtMost(0.7f), 0.07f)
}
