package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Fluidity mode: liquid glass for Muses desktop, after the Hermes "fluidity" skin.
 *
 * What it does, and how it plays with the settings that were already there:
 *
 *  - The glow backdrop is painted across the whole window, in colours read off the playing
 *    song's artwork. When a window material (Mica / Acrylic) is active the backdrop is translucent,
 *    so the system material and the wallpaper still show through the window: that is the
 *    "transparency". With no material it is an opaque dark base with the same glow.
 *  - While Fluidity is on, "material behind the app" is treated as on, and "Reduce dynamic blur"
 *    has nothing to switch off: fluid panes do not blur anything. They are smoked-glass tints with
 *    a sheen and a rim over a backdrop that is already soft. Blurring a source that animates every
 *    frame is what made the first version lag, so it is gone.
 *  - "Reduce animation" freezes the glow and every spring.
 */
internal object DesktopFluiditySettings {

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_FLUIDITY, false))
    private val _subtle = MutableStateFlow(DesktopPersistence().boolean(KEY_FLUIDITY_SUBTLE, false))

    /** The master switch. */
    val enabled: StateFlow<Boolean> = _enabled

    /** Calmer variant: two blobs, no breathing. Cheaper on the GPU. */
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

// ── Palette: the colours of the cover ────────────────────────────────────────────────────────────

/** Glow colours read off a cover, and how vivid that cover is. */
private class FluidPalette(
    val colors: List<Color>,
    val base: Color,
    /** 0..1: a mostly black or grey sleeve glows softly, a colourful one at full strength. */
    val intensity: Float,
)

private val FluidDefaultPalette = FluidPalette(
    colors = listOf(Color(0xFF7C4DFF), Color(0xFF3D7BFF), Color(0xFFD14FD0), Color(0xFF2FB5C8)),
    base = Color(0xFF0B0814),
    intensity = 0.8f,
)

private const val FLUID_ART_PX = 120
private const val HUE_BINS = 24

/** Palettes already read, by artwork URL, so a track heard before does not decode its cover again. */
private const val FLUID_PALETTE_CACHE_ENTRIES = 24
private val FluidPaletteCache = DesktopCountLru<FluidPalette>(FLUID_PALETTE_CACHE_ENTRIES)

/**
 * The palette for [url]. The last palette stays on screen while the next cover loads, so a track
 * change crossfades from one cover's colours to the next instead of passing through a default.
 * A cover seen recently is answered from [FluidPaletteCache]; extraction stays on Dispatchers.IO.
 */
@Composable
private fun rememberFluidPalette(url: String?): FluidPalette {
    var palette by remember { mutableStateOf(url?.let(FluidPaletteCache::get) ?: FluidDefaultPalette) }
    LaunchedEffect(url) {
        if (url == null) {
            palette = FluidDefaultPalette
            return@LaunchedEffect
        }
        val cached = FluidPaletteCache.get(url)
        if (cached != null) {
            palette = cached
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                DesktopArtworkCache.load(url.artworkAt(FLUID_ART_PX) ?: url)?.let(::extractFluidPalette)
            }.getOrNull()
        }
        if (found != null) {
            FluidPaletteCache.put(url, found)
            palette = found
        }
    }
    return palette
}

/**
 * Hue-binned, saturation-weighted: the colours that make a cover look the way it does, rather than
 * its average (a mean of a red stripe on black is a dull maroon). Near-black and near-white pixels
 * are ignored, so letterbox bars and blank borders do not count.
 */
private fun extractFluidPalette(bitmap: ImageBitmap): FluidPalette? {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 2 || height < 2) return null
    val pixels = IntArray(width * height)
    bitmap.readPixels(pixels, 0, 0, width, height)

    val weight = FloatArray(HUE_BINS)
    val satSum = FloatArray(HUE_BINS)
    val valSum = FloatArray(HUE_BINS)
    var counted = 0
    var chromatic = 0
    // An odd stride, so sampling does not line up on one column of the cover.
    val step = (max(1, pixels.size / 3600)) or 1
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        i += step
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val v = maxC
        val d = maxC - minC
        val s = if (maxC == 0f) 0f else d / maxC
        counted++
        if (v < 0.16f || s < 0.14f) continue
        chromatic++
        var h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        val bin = ((h / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        val w = (0.2f + s) * (0.35f + v)
        weight[bin] += w
        satSum[bin] += s * w
        valSum[bin] += v * w
    }
    if (counted == 0) return null

    // Bins are merged with their neighbours first: one hue straddling a bin edge is one colour.
    val smoothed = FloatArray(HUE_BINS) { weight[it] + 0.5f * (weight[(it + 1) % HUE_BINS] + weight[(it + HUE_BINS - 1) % HUE_BINS]) }
    val ranked = (0 until HUE_BINS).filter { weight[it] > 0f }.sortedByDescending { smoothed[it] }
    val chosen = mutableListOf<Int>()
    for (bin in ranked) {
        if (chosen.size == 4) break
        val hue = (bin + 0.5f) * 360f / HUE_BINS
        if (chosen.none { hueGap(hue, (it + 0.5f) * 360f / HUE_BINS) < 34f }) chosen += bin
    }

    val fraction = chromatic.toFloat() / counted
    val intensity = (0.4f + fraction * 1.6f).coerceIn(0.4f, 1f)

    if (chosen.isEmpty()) {
        // A grey or black-and-white sleeve: a soft silver-blue glow rather than an invented hue.
        return FluidPalette(
            colors = listOf(Color(0xFF9AA6C0), Color(0xFF6F7C99), Color(0xFFB9C2D6), Color(0xFF55627F)),
            base = Color(0xFF0B0D12),
            intensity = 0.45f,
        )
    }

    val found = chosen.map { bin ->
        val w = weight[bin].coerceAtLeast(1e-4f)
        val s = (satSum[bin] / w * 1.15f).coerceIn(0.5f, 1f)
        val v = valSum[bin] / w
        hslColor((bin + 0.5f) * 360f / HUE_BINS, s, (0.42f + v * 0.2f).coerceIn(0.46f, 0.6f))
    }.toMutableList()
    // A cover with fewer than four distinct colours is fanned out around its own, never borrowed.
    var k = 1
    while (found.size < 4) {
        val from = found[(found.size - chosen.size).coerceAtLeast(0) % chosen.size]
        found += from.hueShifted(if (k % 2 == 1) 26f * k else -26f * k)
        k++
    }
    val lead = found.first().toHsl()
    return FluidPalette(found, hslColor(lead.h, lead.s.coerceAtMost(0.6f), 0.07f), intensity)
}

private fun hueGap(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

// ── Backdrop ─────────────────────────────────────────────────────────────────────────────────────

private const val BLOB_COUNT = 4
private const val FRAME_STEP_SECONDS = 1f / 30f
private const val TWO_PI = 6.2831855f

/** Where each glow lives and how it wanders. Speeds are cycles per second: periods of 17 to 29 s. */
private class FluidGlow(
    val anchorX: Float,
    val anchorY: Float,
    val amplitude: Float,
    val speedX: Float,
    val speedY: Float,
    val phase: Float,
    val radius: Float,
    val alpha: Float,
)

private val FluidGlows = listOf(
    FluidGlow(0.18f, 0.22f, 0.20f, 0.052f, 0.041f, 0.0f, 0.66f, 0.80f),
    FluidGlow(0.82f, 0.26f, 0.18f, -0.038f, 0.056f, 1.9f, 0.60f, 0.74f),
    FluidGlow(0.78f, 0.82f, 0.20f, 0.046f, -0.036f, 3.4f, 0.68f, 0.78f),
    FluidGlow(0.22f, 0.80f, 0.19f, -0.058f, 0.047f, 5.0f, 0.58f, 0.70f),
)

/**
 * The glow behind the window, in the colours of the cover at [artworkUrl].
 *
 * With [overMaterial] and a window material (Mica / Acrylic) active, the base is translucent and
 * the material shows through; the full-screen player passes false, because it is a sheet laid over
 * the page and has to be opaque. A vertical scrim keeps text readable over any cover.
 */
@Composable
internal fun DesktopFluidBackdrop(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    scrimStrength: Float = 1f,
    overMaterial: Boolean = true,
) {
    val subtle by DesktopFluiditySettings.subtle.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val windowVisible by DesktopWindowVisibility.visible.collectAsState()
    val material by DesktopWindowBackdrop.active.collectAsState()
    val appearance by DesktopAdvancedAppearance.config.collectAsState()
    val translucent = overMaterial && material != DesktopBackdrop.OFF
    val running = windowVisible && !reduceAnimation

    val palette = rememberFluidPalette(artworkUrl)
    val colors = palette.colors.mapIndexed { index, color ->
        animateColorAsState(color, tween(1400), label = "fluidGlow$index").value
    }
    val base by animateColorAsState(palette.base, tween(1400), label = "fluidBase")
    val intensity by animateFloatAsState(
        palette.intensity * appearance.paletteStrength,
        if (reduceAnimation) snap() else tween(1400),
        label = "fluidIntensity",
    )

    // A swell of glow whenever the cover changes, so a skip is something you see.
    val burst = remember { Animatable(0f) }
    LaunchedEffect(palette) {
        if (reduceAnimation) return@LaunchedEffect
        burst.snapTo(1f)
        burst.animateTo(0f, tween(2000, easing = FastOutSlowInEasing))
    }

    // The clock only advances while running and keeps its value across a pause. Capped at 30 fps.
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
        val swell = burst.value

        drawRect(base.copy(alpha = if (translucent) 0.46f else 1f))

        val glows = if (subtle) 2 else BLOB_COUNT
        for (i in 0 until glows) {
            val glow = FluidGlows[i]
            val centre = Offset(
                x = (glow.anchorX + glow.amplitude * cos(t * glow.speedX * TWO_PI + glow.phase)) * w,
                y = (glow.anchorY + glow.amplitude * sin(t * glow.speedY * TWO_PI + glow.phase * 1.3f)) * h,
            )
            val breathing = if (subtle) 1f else 1f + 0.07f * sin(t * 0.5f + glow.phase)
            val radius = reach * glow.radius * breathing * (1f + 0.18f * swell)
            val alpha = (glow.alpha * intensity * (if (subtle) 0.75f else 1f) * (1f + 0.3f * swell))
                .coerceAtMost(0.95f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to colors[i].copy(alpha = alpha),
                        0.45f to colors[i].copy(alpha = alpha * 0.45f),
                        1f to Color.Transparent,
                    ),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
                blendMode = BlendMode.Screen,
            )
        }

        val top = if (translucent) 0.10f else 0.16f
        val bottom = if (translucent) 0.26f else 0.38f
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = (top * scrimStrength).coerceAtMost(0.9f)),
                    Color.Black.copy(alpha = (bottom * scrimStrength).coerceAtMost(0.9f)),
                ),
            ),
        )
    }
}

/** What the shared full-screen player is handed in Fluidity mode; see LocalPlayerBackdropOverride. */
internal val DesktopFluidPlayerBackdrop: @Composable (String?, Any?) -> Unit = { artworkUrl, _ ->
    DesktopFluidBackdrop(artworkUrl, scrimStrength = 1.35f, overMaterial = false)
}

// ── Glass ────────────────────────────────────────────────────────────────────────────────────────

/** The light catching a pane's edge: bright at the top left, a second glint at the bottom right. */
private val FluidRim = Brush.linearGradient(
    listOf(
        Color.White.copy(alpha = 0.50f),
        Color.White.copy(alpha = 0.10f),
        Color.White.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.24f),
    ),
)

/** A diagonal sheen over the tint. */
private val FluidSheen = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.11f),
    0.5f to Color.White.copy(alpha = 0.03f),
    1f to Color.Transparent,
)

private val FluidChromeSheen = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f)),
)

private val FluidSmoke = Color(0xFF0A0A10)

/**
 * A pane of smoked glass: a dark tint of [tint] opacity, a sheen and a gradient rim. No blur, so
 * it costs a few fills however much is moving behind it.
 */
internal fun Modifier.fluidGlass(shape: Shape, tint: Float, rimWidth: Dp = 1.dp): Modifier =
    background(FluidSmoke.copy(alpha = tint), shape)
        .background(FluidSheen, shape)
        .border(rimWidth, FluidRim, shape)

/** The window's own chrome (sidebar, top bar): a light smoke over the glow, no rim, no blur. */
internal fun Modifier.fluidChrome(): Modifier =
    background(FluidSmoke.copy(alpha = 0.34f))
        .background(FluidChromeSheen)

/** A block sitting on a glass card: a lift and the rim. */
internal fun Modifier.fluidInset(shape: Shape): Modifier =
    clip(shape)
        .background(Color.White.copy(alpha = 0.07f), shape)
        .background(FluidSheen, shape)
        .border(0.8.dp, FluidRim, shape)

// ── Motion ───────────────────────────────────────────────────────────────────────────────────────

/** Rises into place with a little overshoot, like a Hermes modal. Transform only. */
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

/**
 * The page lifts into place whenever [key] changes: a short rise and fade, so moving between
 * Home, Library and Search is a movement rather than a cut. A no-op unless Fluidity is on.
 */
internal fun Modifier.fluidPageLift(key: Any?): Modifier = composed {
    val fluid by DesktopFluiditySettings.enabled.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(key, fluid, reduceAnimation) {
        if (!fluid || reduceAnimation) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = 0.35f + 0.65f * p
        translationY = (1f - p) * 18.dp.toPx()
    }
}

/**
 * A click target that answers the pointer: it presses in on a spring and lights up under the
 * cursor. Without Fluidity it is a plain [clickable], as before.
 */
internal fun Modifier.fluidClickable(onClick: () -> Unit): Modifier = composed {
    val fluid by DesktopFluiditySettings.enabled.collectAsState()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (fluid && pressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "fluidPress",
    )
    val glow by animateFloatAsState(
        targetValue = if (fluid && hovered) 1f else 0f,
        animationSpec = tween(180),
        label = "fluidHover",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawBehind { if (glow > 0.01f) drawRect(Color.White.copy(alpha = 0.085f * glow)) }
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
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
    return hslColor(((hsl.h + degrees) % 360f + 360f) % 360f, hsl.s, hsl.l)
}
