package com.music.bitchord.desktop

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/**
 * Glow colours read off a cover, and how vivid that cover is.
 * UPGRADE M1: now `internal` (the pure extraction lives in DesktopAlbumPalette.kt).
 * Roles of [colors] (always 4): [0] dominant, [1] second, [2] third, [3] lighter tint of the dominant.
 */
internal class FluidPalette(
    val colors: List<Color>,
    val base: Color,
    /** 0..1: a mostly black or grey sleeve glows softly, a colourful one at full strength. */
    val intensity: Float,
    /** UPGRADE M1: how much of the cover's colour each of colors[0..2] stands for (0..1, a missing colour is 0). */
    val shares: List<Float> = listOf(1f, 0f, 0f),
)

private val FluidDefaultPalette = FluidPalette(
    colors = listOf(Color(0xFF7C4DFF), Color(0xFF3D7BFF), Color(0xFFD14FD0), Color(0xFF2FB5C8)),
    base = Color(0xFF0B0814),
    intensity = 0.8f,
)

private const val FLUID_ART_PX = 120

/** Palettes already read, by artwork URL, so a track heard before does not decode its cover again. */
private const val FLUID_PALETTE_CACHE_ENTRIES = 24
private val FluidPaletteCache = DesktopCountLru<FluidPalette>(FLUID_PALETTE_CACHE_ENTRIES)

// UPGRADE M2: session-wide memory of what has been shown, so the default palette never returns after a song.
/** True once any song's cover has been asked for this session. */
@Volatile
private var fluidHasPlayed = false

/** The most recent palette read off a real cover. */
@Volatile
private var fluidLastAlbumPalette: FluidPalette? = null

/**
 * The palette for [url], or null while there is nothing to show yet (UPGRADE M2).
 *  - [url] null and nothing ever selected this session: the default palette (idle startup only).
 *  - [url] set and the cover not cached: the PREVIOUS album palette stays on screen; with none yet
 *    (the first song) null, so the caller draws only the base and fades the glows in on arrival.
 *  - [url] null after a song has played: the last album palette is kept, never the default.
 * A cover seen recently is answered from [FluidPaletteCache]; extraction stays on Dispatchers.IO.
 */
@Composable
private fun rememberFluidPalette(url: String?): FluidPalette? {
    var palette by remember {
        mutableStateOf(
            when {
                url != null -> FluidPaletteCache.get(url) ?: fluidLastAlbumPalette
                fluidHasPlayed -> fluidLastAlbumPalette
                else -> FluidDefaultPalette
            },
        )
    }
    LaunchedEffect(url) {
        if (url == null) {
            if (!fluidHasPlayed) {
                palette = FluidDefaultPalette
            } else {
                fluidLastAlbumPalette?.let { palette = it }
            }
            return@LaunchedEffect
        }
        fluidHasPlayed = true
        val cached = FluidPaletteCache.get(url)
        if (cached != null) {
            fluidLastAlbumPalette = cached
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
            fluidLastAlbumPalette = found
            palette = found
        }
    }
    // The idle default must not show for a frame once a song is selected: until its cover is
    // read, show the previous album palette, or nothing.
    return if (url != null && palette === FluidDefaultPalette) fluidLastAlbumPalette else palette
}

/**
 * Reads the cover's pixels and hands them to the pure [extractAlbumPalette] (DesktopAlbumPalette.kt),
 * which does the hue binning and the three-colour choice. UPGRADE M1: the pixel work moved there so it can be tested.
 */
private fun extractFluidPalette(bitmap: ImageBitmap): FluidPalette? {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 2 || height < 2) return null
    val pixels = IntArray(width * height)
    bitmap.readPixels(pixels, 0, 0, width, height)
    return extractAlbumPalette(pixels, width, height)
}

// UPGRADE M1: visibility only (was private)
internal fun hueGap(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

// ── Backdrop ─────────────────────────────────────────────────────────────────────────────────────

private const val BLOB_COUNT = 4
private const val FRAME_STEP_SECONDS = 1f / 30f
private const val TWO_PI = 6.2831855f

// UPGRADE M2
private const val DISSOLVE_MS = 1400
private const val FIRST_GLOW_FADE_MS = 800

/** The dark neutral base shown while a cover has no palette yet. */
private val FluidNeutralBase = Color(0xFF0B0D12)

/** Glow alpha by role: dominant, second, third, tint of the dominant. */
private val FluidRoleAlpha = floatArrayOf(0.85f, 0.70f, 0.60f, 0.45f)

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
 *
 * UPGRADE M2: glows use normal (SrcOver) blending, so overlaps never invent a new hue. A track
 * change dissolves two layers (old palette out, new palette in) by alpha instead of tweening
 * colours; the first song fades its glows up from the dark neutral base.
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

    val target = rememberFluidPalette(artworkUrl)
    val strength by animateFloatAsState(
        appearance.paletteStrength,
        if (reduceAnimation) snap() else tween(400),
        label = "fluidStrength",
    )

    // UPGRADE M2: two-layer cross-dissolve. `to` is the palette on screen (null = base only),
    // `from` the one fading out; progress 0..1 runs only while they differ.
    var to by remember { mutableStateOf(target) }
    var from by remember { mutableStateOf<FluidPalette?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        if (target == null) {
            // Nothing to show (a cover still loading with no earlier one): the neutral base, at once.
            to = null
            from = null
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        val firstGlow = to == null
        // Retarget mid-dissolve: the old target becomes `from` outright (a tiny jump, accepted).
        from = to
        to = target
        progress.snapTo(0f)
        if (reduceAnimation) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(if (firstGlow) FIRST_GLOW_FADE_MS else DISSOLVE_MS))
        }
        from = null
    }

    // A swell of glow whenever the cover changes, so a skip is something you see.
    val burst = remember { Animatable(0f) }
    LaunchedEffect(target) {
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
        val p = progress.value
        val toPalette = to
        // Skipped once the dissolve is done.
        val fromPalette = if (p < 1f) from else null
        val dissolving = p < 1f

        // Base: old out, new in. Over a translucent material both layers share the one base alpha
        // (old fades as 1 - p) so the sum never stacks darker than a settled base.
        val baseAlpha = if (translucent) 0.46f else 1f
        if (dissolving) {
            val oldBase = fromPalette?.base ?: FluidNeutralBase
            drawRect(oldBase.copy(alpha = if (translucent) baseAlpha * (1f - p) else baseAlpha))
        }
        val newBase = toPalette?.base ?: FluidNeutralBase
        drawRect(newBase.copy(alpha = baseAlpha * (if (dissolving) p else 1f)))

        val glows = if (subtle) 2 else BLOB_COUNT
        for (i in 0 until glows) {
            val glow = FluidGlows[i]
            val centre = Offset(
                x = (glow.anchorX + glow.amplitude * cos(t * glow.speedX * TWO_PI + glow.phase)) * w,
                y = (glow.anchorY + glow.amplitude * sin(t * glow.speedY * TWO_PI + glow.phase * 1.3f)) * h,
            )
            val breathing = if (subtle) 1f else 1f + 0.07f * sin(t * 0.5f + glow.phase)
            val radius = reach * glow.radius * breathing * (1f + 0.18f * swell)
            // Role alpha (dominant, second, third, tint) times this glow's ratio to the first glow.
            val roleAlpha = FluidRoleAlpha[i] * (glow.alpha / FluidGlows[0].alpha)
            val ambient = roleAlpha * strength * (if (subtle) 0.75f else 1f) * (1f + 0.3f * swell)
            if (fromPalette != null) {
                drawFluidGlow(fromPalette, i, centre, radius, ambient, 1f - p)
            }
            if (toPalette != null) {
                drawFluidGlow(toPalette, i, centre, radius, ambient, if (dissolving) p else 1f)
            }
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

/** UPGRADE M2: one glow of one palette layer, normal blending, [layerAlpha] being its share of the dissolve. */
private fun DrawScope.drawFluidGlow(
    palette: FluidPalette,
    index: Int,
    centre: Offset,
    radius: Float,
    ambient: Float,
    layerAlpha: Float,
) {
    val color = palette.colors[index]
    val alpha = (ambient * palette.intensity).coerceAtMost(0.95f) * layerAlpha
    if (alpha <= 0.002f) return
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = alpha),
                0.45f to color.copy(alpha = alpha * 0.45f),
                1f to Color.Transparent,
            ),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
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

// UPGRADE M1: visibility only (was private)
internal class Hsl(val h: Float, val s: Float, val l: Float)

// UPGRADE M1: visibility only (was private)
internal fun Color.toHsl(): Hsl {
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

// UPGRADE M1: visibility only (was private)
internal fun hslColor(h: Float, s: Float, l: Float): Color {
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

// UPGRADE M1: visibility only (was private)
internal fun Color.hueShifted(degrees: Float): Color {
    val hsl = toHsl()
    return hslColor(((hsl.h + degrees) % 360f + 360f) % 360f, hsl.s, hsl.l)
}
