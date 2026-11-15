package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline // STUDIO G5 hook: package fixed from CI log
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/** STUDIO G2: the two pictures the glass can see: the window's backdrop, and the page over it. */
internal enum class GlassSlot { BACKDROP, PAGE }

/**
 * STUDIO G2: what the glass surfaces share. The window records its backdrop and its page into one
 * [GraphicsLayer] each (see [glassSource]); a surface crops the part of them that lies under it.
 *
 * Nothing is recorded unless a surface exists ([consumers] > 0), the window is on screen and the
 * dynamic blur is not reduced ([suspended]); with no glass in use the app draws exactly as before.
 */
@Stable
internal class DesktopGlassState internal constructor(
    private val backdropLayer: GraphicsLayer,
    private val pageLayer: GraphicsLayer,
) {
    /** True while the window is hidden to the tray, or the dynamic blur is reduced. */
    var suspended by mutableStateOf(false)
        internal set

    /** How many glass surfaces are composed right now. */
    var consumers by mutableIntStateOf(0)
        private set

    /** Seconds, for the shader's `time`; only advances while a surface exists and motion is allowed. */
    var time by mutableFloatStateOf(0f)
        internal set

    /** Bumped each time a source re-records, so a surface redraws with the new picture. */
    var revision by mutableIntStateOf(0)
        private set

    /** Whether the sources should record this frame. Read in a draw, so it re-subscribes. */
    val wantsRecording: Boolean get() = consumers > 0 && !suspended

    // Plain fields: written from layout and draw, never observed. A surface that needs a fresh
    // origin gets it with the next [revision].
    private val rootXs = FloatArray(SLOTS)
    private val rootYs = FloatArray(SLOTS)
    private val recorded = BooleanArray(SLOTS)
    private var revisionCounter = 0

    internal fun acquire() {
        consumers += 1
    }

    internal fun release() {
        consumers = max(consumers - 1, 0)
        // A picture recorded before the last surface went away must not be shown when the next
        // one appears; the sources re-record first.
        if (consumers == 0) recorded.fill(false)
    }

    internal fun setOrigin(slot: GlassSlot, root: Offset) {
        rootXs[slot.ordinal] = root.x
        rootYs[slot.ordinal] = root.y
    }

    internal fun layerOf(slot: GlassSlot): GraphicsLayer =
        if (slot == GlassSlot.BACKDROP) backdropLayer else pageLayer

    /** Called from a source's draw. Writes [revision] without reading it, so the draw never re-triggers itself. */
    internal fun noteRecorded(slot: GlassSlot) {
        recorded[slot.ordinal] = true
        revisionCounter += 1
        revision = revisionCounter
    }

    internal fun isRecorded(slot: GlassSlot): Boolean = recorded[slot.ordinal]

    internal val hasAnySource: Boolean get() = recorded[0] || recorded[1]

    internal fun originX(slot: GlassSlot): Float = rootXs[slot.ordinal]

    internal fun originY(slot: GlassSlot): Float = rootYs[slot.ordinal]

    private companion object {
        const val SLOTS = 2
    }
}

/** The state of the window the glass sits in; null outside a frame (then every surface falls back). */
internal val LocalDesktopGlass = staticCompositionLocalOf<DesktopGlassState?> { null }

private const val GLASS_FRAME_STEP_SECONDS = 1f / 30f

/**
 * One [DesktopGlassState] with its clock (30 fps, the Fluidity pattern). The clock runs only while
 * a surface exists, the window is on screen and motion is not reduced; it keeps its value across a pause.
 */
@Composable
internal fun rememberDesktopGlassState(): DesktopGlassState {
    val backdrop = rememberGraphicsLayer()
    val page = rememberGraphicsLayer()
    val state = remember { DesktopGlassState(backdrop, page) }
    val windowVisible by DesktopWindowVisibility.visible.collectAsState()
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    SideEffect { state.suspended = !windowVisible || reduceBlur }
    val running = windowVisible && !reduceAnimation && state.consumers > 0
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                if (dt >= GLASS_FRAME_STEP_SECONDS) {
                    state.time += dt.coerceAtMost(0.25f)
                    last = now
                }
            }
        }
    }
    return state
}

/**
 * Marks this node's content as something the glass can see. Put it on the box that holds the
 * picture to refract, inside (after) any `hazeSource`, which it leaves alone. While nothing needs
 * it, this is a plain `drawContent()`; while something does, the content is recorded into the
 * slot's layer and drawn from there, so it is still drawn once.
 */
internal fun Modifier.glassSource(state: DesktopGlassState?, slot: GlassSlot): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.setOrigin(slot, it.positionInRoot()) }
        .drawWithContent {
            if (!state.wantsRecording) {
                drawContent()
                return@drawWithContent
            }
            val layer = state.layerOf(slot)
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            state.noteRecorded(slot)
        }
}

/** Per-surface scratch: its own small layer's bookkeeping and the last effect, so an unchanged frame reuses it. */
private class GlassSurfaceHolder {
    var rootX = 0f
    var rootY = 0f

    var outlineShape: Shape? = null
    var outlineSize = Size.Zero
    var outlineDirection = LayoutDirection.Ltr
    var outline: Outline? = null
    var radius = 0f

    var seenRevision = 0
    var effect: RenderEffect? = null
    var keyParams: DesktopGlassParams? = null
    var keyTint = Color.Unspecified
    var keyAccent = Color.Unspecified
    var keyTime = Float.NaN
    var keyWidth = 0f
    var keyHeight = 0f
    var keyRadius = 0f
    var keyDensity = 0f
    var keyRootX = Float.NaN
    var keyRootY = Float.NaN
}

/**
 * STUDIO G2: a pane of liquid glass: the part of the window under this node is cropped into its own
 * small layer, refracted by the G1 shader, drawn, and the node's content goes on top.
 *
 * Falls back to the classic pane ([desktopBarGlass], with [fallbackRim] as the pill passes it) when
 * there is no [LocalDesktopGlass], when the dynamic blur is reduced, or when the shader is
 * unavailable, and again, by recomposition, if a frame ever fails. Until the first picture has been
 * recorded the pane is a plain smoked fill, never empty.
 *
 * [tint] is the dark glass colour, [accent] the album colour; both are plain colours, the amounts
 * come from [params].
 */
@Composable
internal fun Modifier.glassSurface(
    shape: Shape,
    params: DesktopGlassParams,
    tint: Color,
    accent: Color,
    fallbackRim: Float? = null,
): Modifier {
    val state = LocalDesktopGlass.current
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    if (state == null || reduceBlur || !DesktopGlassShader.available) {
        // ODEON-FIX: this is only the refracting overlay. Without it the pane under it (glassPane) is all
        // there is, which is still readable frosted glass; it is never an empty hole.
        return this
    }
    DisposableEffect(state) {
        state.acquire()
        onDispose { state.release() }
    }
    val layer = rememberGraphicsLayer()
    val holder = remember { GlassSurfaceHolder() }
    return this
        .onGloballyPositioned {
            val at = it.positionInRoot()
            holder.rootX = at.x
            holder.rootY = at.y
        }
        .drawWithContent {
            // Read here so a new recording or a clock tick redraws this surface.
            holder.seenRevision = state.revision
            val time = state.time
            val width = size.width
            val height = size.height
            if (width < 1f || height < 1f) {
                drawContent()
                return@drawWithContent
            }
            if (holder.outline == null || holder.outlineShape != shape || holder.outlineSize != size ||
                holder.outlineDirection != layoutDirection
            ) {
                val made = shape.createOutline(size, layoutDirection, this)
                holder.outline = made
                holder.outlineShape = shape
                holder.outlineSize = size
                holder.outlineDirection = layoutDirection
                holder.radius = when (made) {
                    is Outline.Rounded -> made.roundRect.topLeftCornerRadius.x
                    is Outline.Rectangle -> 0f
                    is Outline.Generic -> min(width, height) / 2f
                }
                holder.effect = null
            }
            val ready = state.hasAnySource
            val effect = if (ready) {
                val same = holder.effect != null && holder.keyParams == params && holder.keyTint == tint &&
                    holder.keyAccent == accent && holder.keyTime == time && holder.keyWidth == width &&
                    holder.keyHeight == height && holder.keyRadius == holder.radius && holder.keyDensity == density &&
                    holder.keyRootX == holder.rootX && holder.keyRootY == holder.rootY
                if (same) {
                    holder.effect
                } else {
                    DesktopGlassShader.effect(params, width, height, holder.radius, tint, accent, time, density, holder.rootX, holder.rootY)
                        .also {
                            holder.effect = it
                            holder.keyParams = params
                            holder.keyTint = tint
                            holder.keyAccent = accent
                            holder.keyTime = time
                            holder.keyWidth = width
                            holder.keyHeight = height
                            holder.keyRadius = holder.radius
                            holder.keyDensity = density
                            holder.keyRootX = holder.rootX
                            holder.keyRootY = holder.rootY
                        }
                }
            } else {
                null
            }
            if (effect == null) {
                // Not recorded yet, or the shader failed this frame (which also flips `available`).
                // Only the overlay is missing; the frosted pane under it is still there.
                drawContent()
                return@drawWithContent
            }
            layer.clip = true
            layer.renderEffect = effect
            val rootX = holder.rootX
            val rootY = holder.rootY
            layer.record {
                // Source-local (a, b) lands at surface-local (a + sourceRoot - surfaceRoot).
                if (state.isRecorded(GlassSlot.BACKDROP)) {
                    translate(state.originX(GlassSlot.BACKDROP) - rootX, state.originY(GlassSlot.BACKDROP) - rootY) {
                        drawLayer(state.layerOf(GlassSlot.BACKDROP))
                    }
                }
                if (state.isRecorded(GlassSlot.PAGE)) {
                    translate(state.originX(GlassSlot.PAGE) - rootX, state.originY(GlassSlot.PAGE) - rootY) {
                        drawLayer(state.layerOf(GlassSlot.PAGE))
                    }
                }
            }
            drawLayer(layer)
            drawContent()
        }
}
