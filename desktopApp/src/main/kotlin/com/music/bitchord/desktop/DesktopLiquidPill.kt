package com.music.bitchord.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline // STUDIO G5 hook: package fixed from CI log
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// STUDIO G3: the liquid-glass pill. Same parameters and callbacks as DesktopFloatingMiniPlayer
// (which stays as the Classic material). Nothing here touches playback: every action is a callback.
// ---------------------------------------------------------------------------------------------

/** Dark glass colour under the text, the same family as the classic veil. */
private val LiquidTint = Color(0xFF080A12)

// ----- pure helpers (unit tested) -----

/**
 * True when the liquid pill should be drawn: the setting asks for it, the glass material is the
 * chosen one (Solid and Accent stay on the classic pill, they are explicit choices) and the shader
 * works. Anything else is the classic pill.
 */
internal fun desktopLiquidPillActive(
    pillMaterial: DesktopPillMaterial,
    miniMaterial: DesktopMiniPlayerMaterial,
    @Suppress("UNUSED_PARAMETER") shaderAvailable: Boolean = true,
): Boolean = (pillMaterial == DesktopPillMaterial.LIQUID_GLASS || pillMaterial == DesktopPillMaterial.GLASS) &&
    miniMaterial == DesktopMiniPlayerMaterial.GLASS

/** True when the refracting overlay is drawn on top of the frosted pane: Liquid material and a working shader. */
internal fun desktopLiquidEdgeActive(pillMaterial: DesktopPillMaterial, shaderAvailable: Boolean): Boolean =
    pillMaterial == DesktopPillMaterial.LIQUID_GLASS && shaderAvailable

/** Share of the classic contrast that becomes the lowest tint the glass may have (text stays readable). */
internal const val LIQUID_TINT_FLOOR_SHARE = 0.55f

/** The tint alpha actually used: the Studio value, but never below a floor taken from the contrast setting. */
internal fun liquidTintAlpha(tintAlpha: Float, contrast: Float): Float {
    val safeContrast = if (contrast.isNaN() || contrast.isInfinite()) 0.72f else contrast.coerceIn(0.48f, 0.9f)
    val own = if (tintAlpha.isNaN() || tintAlpha.isInfinite()) 0f else tintAlpha
    return maxOf(own, safeContrast * LIQUID_TINT_FLOOR_SHARE).coerceIn(0f, 0.9f)
}

/** Bell curve for the track-change light sweep: 0 at both ends, 1 in the middle. */
internal fun liquidSweepAlpha(progress: Float): Float {
    if (progress.isNaN()) return 0f
    return (1f - abs(2f * progress.coerceIn(0f, 1f) - 1f)).coerceIn(0f, 1f)
}

/** Progress as a safe 0..1 fraction. */
internal fun liquidProgressFraction(progress: Float): Float =
    if (progress.isNaN() || progress.isInfinite()) 0f else progress.coerceIn(0f, 1f)

/** Plain colour blend, [amount] 0 = a, 1 = b. */
internal fun liquidMix(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}

private fun liquidTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Remembers the rim outline between draws, like the glass surface does. */
private class LiquidOutlineHolder {
    var outline: Outline? = null
    var shape: Shape? = null
    var size: Size = Size.Zero
}

// ----- host: Liquid or Classic -----

/** The single call site of the pill: picks the liquid pill, or the classic one as the fallback. */
@Composable
internal fun DesktopPillHost(
    song: Song,
    isPlaying: Boolean,
    previousEnabled: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenPillStudio: () -> Unit,
    artworkModifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMoreLyrics: () -> Unit,
    onMoreQueue: () -> Unit,
    onMoreOutput: () -> Unit,
    /** ODEON-FIX: jump to a position (ms); the progress bar is clickable and draggable. */
    onSeek: (Long) -> Unit = {},
) {
    val custom by DesktopAdvancedAppearance.config.collectAsState()
    val liquid = desktopLiquidPillActive(custom.pillConfig.pillMaterial, custom.miniPlayerMaterial, DesktopGlassShader.available)
    if (liquid) {
        DesktopLiquidPill(
            song = song,
            isPlaying = isPlaying,
            previousEnabled = previousEnabled,
            progress = progress,
            positionMs = positionMs,
            durationMs = durationMs,
            shuffle = shuffle,
            repeatMode = repeatMode,
            onShuffleChange = onShuffleChange,
            onRepeatModeChange = onRepeatModeChange,
            onExpandPlayer = onExpandPlayer,
            onOpenPillStudio = onOpenPillStudio,
            artworkModifier = artworkModifier,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onMoreLyrics = onMoreLyrics,
            onMoreQueue = onMoreQueue,
            onMoreOutput = onMoreOutput,
            onSeek = onSeek,
        )
    } else {
        DesktopFloatingMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            previousEnabled = previousEnabled,
            progress = progress,
            positionMs = positionMs,
            durationMs = durationMs,
            shuffle = shuffle,
            repeatMode = repeatMode,
            onShuffleChange = onShuffleChange,
            onRepeatModeChange = onRepeatModeChange,
            onExpandPlayer = onExpandPlayer,
            onOpenPillStudio = onOpenPillStudio,
            artworkModifier = artworkModifier,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onMoreLyrics = onMoreLyrics,
            onMoreQueue = onMoreQueue,
            onMoreOutput = onMoreOutput,
        )
    }
}

// ----- the pill -----

@Composable
internal fun DesktopLiquidPill(
    song: Song,
    isPlaying: Boolean,
    previousEnabled: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenPillStudio: () -> Unit,
    artworkModifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMoreLyrics: () -> Unit,
    onMoreQueue: () -> Unit,
    onMoreOutput: () -> Unit,
    /** ODEON-FIX: jump to a position (ms); the progress bar is clickable and draggable. */
    onSeek: (Long) -> Unit = {},
    /** STUDIO G4: the Pill Studio preview draws the draft instead of the saved config (and ignores the legacy appearance values). */
    configOverride: DesktopPillConfig? = null,
) {
    var moreOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val maxWidth by DesktopMiniPlayerAppearance.width.collectAsState()
    val cornerRadius by DesktopMiniPlayerAppearance.cornerRadius.collectAsState()
    val surfaceContrast by DesktopMiniPlayerAppearance.contrast.collectAsState()
    val rimStrength by DesktopMiniPlayerAppearance.rim.collectAsState()
    val custom by DesktopAdvancedAppearance.config.collectAsState()
    val pill = configOverride ?: custom.pillConfig
    val preview = configOverride != null
    val showShuffle = preview || custom.miniPlayerShowShuffle
    val showRepeat = preview || custom.miniPlayerShowRepeat
    val contrastNow = configOverride?.contrast ?: surfaceContrast
    val rimNow = configOverride?.rim ?: rimStrength

    // Entry: slide up and fade in, again for every new track (same as the classic pill).
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(song.videoId, reduceMotion) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, MusesMotion.settle(reduceMotion))
    }
    // Light sweep across the rim on track change: one pass, then it rests at 1.
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(song.videoId, reduceMotion) {
        if (reduceMotion || !pill.lightSweep) {
            sweep.snapTo(1f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
        }
    }
    // Press squish and hover feel: transforms only, read inside graphicsLayer.
    val pressScale = animateFloatAsState(
        targetValue = when {
            pressed -> pill.pressSquish
            hovered && pill.expandOnHover -> 1.015f
            else -> 1f
        },
        animationSpec = MusesMotion.press(reduceMotion),
        label = "liquidPillPress",
    )
    val glow = animateFloatAsState(
        targetValue = if (hovered && pill.hoverGlow) 1f else 0f,
        animationSpec = MusesMotion.crossfade(reduceMotion),
        label = "liquidPillGlow",
    )
    // Gentle breathing of the rim while playing; nothing at all when motion is reduced.
    val breathing = isPlaying && !reduceMotion && pill.breathing
    val breath: State<Float> = if (breathing) {
        rememberInfiniteTransition(label = "liquidPillBreath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "liquidPillBreathValue",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    // Album colour: the same palette the Fluidity backdrop uses, eased between tracks.
    val palette = rememberFluidPalette(song.thumbnailUrl)
    val accentTarget = palette?.colors?.firstOrNull() ?: DesktopAccent
    val accent by animateColorAsStateCompat(accentTarget, reduceMotion)

    val shape: Shape = when (pill.shape) {
        DesktopPillShape.PILL -> RoundedCornerShape(percent = 50)
        DesktopPillShape.ROUNDED_RECTANGLE -> RoundedCornerShape((configOverride?.cornerRadius ?: cornerRadius).coerceIn(0, 48).dp)
        DesktopPillShape.SQUIRCLE -> RoundedCornerShape(percent = 36)
        DesktopPillShape.CUSTOM_RADIUS -> RoundedCornerShape(pill.cornerRadius.coerceIn(0, 48).dp)
    }
    val dockHeight = when (configOverride?.height ?: custom.miniPlayerHeight) {
        DesktopMiniPlayerHeight.COMPACT -> 60.dp
        DesktopMiniPlayerHeight.DEFAULT -> 72.dp
        DesktopMiniPlayerHeight.LARGE -> 84.dp
    }
    val artShape: Shape = when (configOverride?.artShape ?: custom.miniPlayerArtShape) {
        DesktopArtworkShape.SQUARE -> RoundedCornerShape(0.dp)
        DesktopArtworkShape.ROUNDED -> RoundedCornerShape(10.dp)
        DesktopArtworkShape.CIRCLE -> CircleShape
    }
    val artSizeRaw = when (pill.artSize) {
        DesktopPillArtSize.SMALL -> 36.dp
        DesktopPillArtSize.MEDIUM -> (dockHeight - 24.dp).coerceIn(40.dp, 52.dp)
        DesktopPillArtSize.LARGE -> (dockHeight - 12.dp).coerceIn(48.dp, 64.dp)
    }
    // ODEON-FIX: the artwork must fit inside the pill, with room from the rounded ends.
    val artSize = minOf(artSizeRaw, dockHeight - 14.dp)
    val showArt = (preview || custom.miniPlayerArtwork) && "artwork" in pill.zones.values.flatten() && "artwork" !in pill.hiddenElements &&
        pill.artPlacement != DesktopPillArtPlacement.HIDDEN
    val showMetadata = (preview || custom.miniPlayerMetadata) && "metadata" in pill.zones.values.flatten() && "metadata" !in pill.hiddenElements

    // Artwork spin: same behaviour as the classic pill; read inside graphicsLayer, no recomposition.
    val spinAnim = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, reduceMotion, pill.spinWhilePlaying, pill.spinRpm, pill.spinClockwise, pill.spinPause) {
        if (reduceMotion || !pill.spinWhilePlaying || !isPlaying) {
            when {
                reduceMotion -> spinAnim.snapTo(0f)
                pill.spinPause == DesktopPillSpinPause.RETURN_TO_ZERO -> spinAnim.animateTo(0f, spring(stiffness = 420f))
                pill.spinPause == DesktopPillSpinPause.EASE_TO_STOP -> {
                    val stopAt = (spinAnim.value / 360f).roundToInt() * 360f
                    spinAnim.animateTo(stopAt, spring(stiffness = 320f))
                }
                else -> spinAnim.stop()
            }
            return@LaunchedEffect
        }
        val direction = if (pill.spinClockwise) 360f else -360f
        while (true) {
            spinAnim.animateTo(
                targetValue = spinAnim.value + direction,
                animationSpec = tween(durationMillis = (60_000 / pill.spinRpm.coerceIn(5, 60)).coerceAtLeast(250), easing = LinearEasing),
            )
        }
    }

    val glassParams = remember(pill.glass, contrastNow) {
        pill.glass.copy(tintAlpha = liquidTintAlpha(pill.glass.tintAlpha, contrastNow))
    }
    val outlineHolder = remember { LiquidOutlineHolder() }
    val edgeOn = desktopLiquidEdgeActive(pill.pillMaterial, DesktopGlassShader.available)
    val rimColor = liquidMix(Color.White, accent, 0.45f * glassParams.albumInfluence)
    val fillColor = liquidMix(accent, Color.White, 0.35f)
    val fraction = liquidProgressFraction(progress)
    // Same rules as the plain pill: a phone-sized window sheds shuffle and repeat into More.
    val pillNarrow = DesktopDisplayGeometry.isNarrow(rememberWindowWidthDp().value)
    val pillTouch = LocalDesktopTouchMode.current

    Box(
        Modifier
            .widthIn(max = (configOverride?.maxWidth ?: maxWidth).dp)
            .fillMaxWidth()
            .height(dockHeight)
            .graphicsLayer {
                val s = pressScale.value
                scaleX = s
                scaleY = s
                alpha = entrance.value
                translationY = (1f - entrance.value) * 14.dp.toPx()
            }
            .clip(shape)
            .glassPane(shape, glassParams, LiquidTint, accent)
            .then(if (edgeOn) Modifier.glassSurface(shape, glassParams, LiquidTint, accent, rimNow) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                if (pill.clickAction == DesktopPillClickAction.OPEN_PLAYER && custom.miniPlayerTapExpands && !preview) onExpandPlayer()
            },
    ) {
        // Light layer between the glass and the controls: rim, breathing, hover glow, sweep.
        val hoverBrushColor = Color.White
        Box(
            Modifier.matchParentSize().drawBehind {
                if (outlineHolder.outline == null || outlineHolder.shape != shape || outlineHolder.size != size) {
                    outlineHolder.outline = shape.createOutline(size, layoutDirection, this)
                    outlineHolder.shape = shape
                    outlineHolder.size = size
                }
                val outline = outlineHolder.outline ?: return@drawBehind
                val glowValue = glow.value
                val sweepValue = sweep.value
                val rimAlpha = (0.12f + 0.10f * breath.value + 0.24f * glowValue) * (0.4f + glassParams.rim)
                drawOutline(outline, rimColor.copy(alpha = rimAlpha.coerceIn(0f, 1f)), style = Stroke(width = 1.2.dp.toPx()))
                if (glowValue > 0.01f) {
                    drawOutline(
                        outline,
                        Brush.verticalGradient(
                            colors = listOf(hoverBrushColor.copy(alpha = 0.14f), Color.Transparent),
                            startY = 0f,
                            endY = size.height,
                        ),
                        alpha = glowValue,
                    )
                }
                if (sweepValue > 0.001f && sweepValue < 0.999f) {
                    val width = size.width
                    val centre = -0.25f * width + sweepValue * 1.5f * width
                    val band = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.75f * liquidSweepAlpha(sweepValue)), Color.Transparent),
                        start = Offset(centre - 0.12f * width, 0f),
                        end = Offset(centre + 0.12f * width, size.height),
                    )
                    drawOutline(outline, band, style = Stroke(width = 2.2.dp.toPx()))
                    drawOutline(outline, band, alpha = 0.16f)
                }
            },
        )
        val drawPillElementRaw: @Composable (String) -> Unit = { id ->
            if (id !in pill.hiddenElements) when (id) {
                "artwork" -> if (showArt) LiquidPillArtwork(
                    song = song,
                    modifier = artworkModifier,
                    size = artSize,
                    shape = artShape,
                    rotation = { spinAnim.value },
                    instantTrackChange = reduceMotion || pill.trackAnimation == DesktopPillAnimation.NONE,
                    durationMs = pill.trackAnimationMs,
                    transitionTarget = !preview,
                )
                "metadata" -> if (showMetadata || custom.miniPlayerProgress != DesktopMiniProgress.HIDDEN) Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (showMetadata) {
                        Text(
                            song.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(song.artist, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (custom.miniPlayerProgress != DesktopMiniProgress.HIDDEN) {
                        // ODEON-FIX: the bar and the time share one 16 dp row (the time used to fall off the
                        // bottom of the pill), and the bar can be clicked or dragged to seek.
                        var scrub by remember { mutableStateOf<Float?>(null) }
                        val shownFraction = scrub ?: fraction
                        Row(
                            Modifier.fillMaxWidth().height(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val barHeight = if (custom.miniPlayerProgress == DesktopMiniProgress.FULL) 4.dp else 2.5.dp
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .pointerInput(durationMs) {
                                        detectTapGestures { offset ->
                                            if (durationMs > 0L && size.width > 0) {
                                                onSeek((offset.x / size.width * durationMs).toLong().coerceIn(0L, durationMs))
                                            }
                                        }
                                    }
                                    .pointerInput(durationMs) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { offset -> if (size.width > 0) scrub = (offset.x / size.width).coerceIn(0f, 1f) },
                                            onDragEnd = {
                                                val target = scrub
                                                if (target != null && durationMs > 0L) onSeek((target * durationMs).toLong())
                                                scrub = null
                                            },
                                            onDragCancel = { scrub = null },
                                            onHorizontalDrag = { change, _ ->
                                                if (size.width > 0) scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                                            },
                                        )
                                    }
                                    .drawBehind {
                                        val bar = barHeight.toPx()
                                        val centreY = size.height / 2f
                                        val radius = bar / 2f
                                        drawRoundRect(
                                            color = Color.White.copy(alpha = 0.20f),
                                            topLeft = Offset(0f, centreY - radius),
                                            size = Size(size.width, bar),
                                            cornerRadius = CornerRadius(radius, radius),
                                        )
                                        val filled = size.width * shownFraction
                                        if (filled > 0f) {
                                            drawRoundRect(
                                                color = fillColor,
                                                topLeft = Offset(0f, centreY - radius),
                                                size = Size(filled, bar),
                                                cornerRadius = CornerRadius(radius, radius),
                                            )
                                        }
                                        // Knob while hovered or scrubbing.
                                        val knob = maxOf(glow.value, if (scrub != null) 1f else 0f)
                                        if (knob > 0.01f) {
                                            val x = filled.coerceIn(5.dp.toPx(), (size.width - 5.dp.toPx()).coerceAtLeast(5.dp.toPx()))
                                            drawCircle(color = accent.copy(alpha = 0.35f * knob), radius = 8.dp.toPx() * knob, center = Offset(x, centreY))
                                            drawCircle(color = Color.White.copy(alpha = 0.95f * knob), radius = 4.dp.toPx() * knob, center = Offset(x, centreY))
                                        }
                                    },
                            )
                            Text(
                                "${liquidTime(positionMs)} / ${liquidTime(durationMs)}",
                                color = Color.White.copy(alpha = 0.62f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                "previous" -> if (!(pillNarrow && pillTouch)) DesktopSpringButton(onClick = onPrevious, enabled = previousEnabled, size = 34.dp) {
                    Icon(Icons.Rounded.FastRewind, "Previous", tint = if (previousEnabled) Color.White else Color.White.copy(alpha = 0.35f), modifier = Modifier.size(19.dp))
                }
                "play" -> DesktopSpringButton(onClick = onPlayPause, size = 38.dp) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f), CircleShape)
                            .border(0.8.dp, Color.White.copy(alpha = 0.30f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Play and pause cross-fade, so the icon morphs instead of jumping.
                        Crossfade(
                            targetState = isPlaying,
                            animationSpec = tween(durationMillis = if (reduceMotion) 0 else 180),
                            label = "liquidPlayPause",
                        ) { playing ->
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                }
                "next" -> DesktopSpringButton(onClick = onNext, size = 34.dp) {
                    Icon(Icons.Rounded.FastForward, DesktopStrings["widget_next", "Next"], tint = Color.White, modifier = Modifier.size(19.dp))
                }
                "shuffle" -> if (showShuffle && !pillNarrow) DesktopSpringButton(onClick = { onShuffleChange(!shuffle) }, size = 34.dp) {
                    Icon(BitChordIcons.Shuffle, "Shuffle", tint = if (shuffle) accent else Color.White.copy(alpha = 0.62f), modifier = Modifier.size(17.dp))
                }
                "repeat" -> if (showRepeat && !pillNarrow) DesktopSpringButton(onClick = { onRepeatModeChange(repeatMode.next()) }, size = 34.dp) {
                    if (repeatMode == DesktopRepeatMode.ONE) {
                        Text("1", color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(
                            BitChordIcons.Repeat,
                            "Repeat ${repeatMode.label()}",
                            tint = if (repeatMode == DesktopRepeatMode.OFF) Color.White.copy(alpha = 0.62f) else accent,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                "buds" -> DesktopBudsPillSlot(preview = preview) // BUDS B4 hook
                "more" -> Box {
                    DesktopSpringButton(onClick = { moreOpen = true }, size = 34.dp) {
                        Icon(Icons.Rounded.MoreHoriz, "More playback options", tint = Color.White.copy(alpha = 0.72f))
                    }
                    if (moreOpen) DismissOnDisplayChange { moreOpen = false }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        if (pillNarrow && pillTouch) {
                            DropdownMenuItem(text = { Text("Previous") }, onClick = { moreOpen = false; onPrevious() })
                        }
                        if (!showShuffle || pillNarrow || "shuffle" in pill.hiddenElements) {
                            DropdownMenuItem(text = { Text(if (shuffle) "Turn shuffle off" else "Turn shuffle on") }, onClick = { moreOpen = false; onShuffleChange(!shuffle) })
                        }
                        if (!showRepeat || pillNarrow || "repeat" in pill.hiddenElements) {
                            DropdownMenuItem(text = { Text("Repeat: ${repeatMode.label()}") }, onClick = { moreOpen = false; onRepeatModeChange(repeatMode.next()) })
                        }
                        DropdownMenuItem(text = { Text("Lyrics") }, onClick = { moreOpen = false; onMoreLyrics() })
                        DropdownMenuItem(text = { Text("Queue") }, onClick = { moreOpen = false; onMoreQueue() })
                        DropdownMenuItem(text = { Text("Audio output") }, onClick = { moreOpen = false; onMoreOutput() })
                        DropdownMenuItem(text = { Text("Customise pill…") }, onClick = { moreOpen = false; onOpenPillStudio() })
                    }
                }
            }
        }
        // Artwork transition: title/artist/progress and controls fade in with the reveal (not in the Studio preview).
        val drawPillElement: @Composable (String) -> Unit = { id ->
            if (id == "artwork" || preview) drawPillElementRaw(id) else PillRevealFade { drawPillElementRaw(id) }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (pillNarrow) 10.dp else 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (id in pill.zones["left"].orEmpty()) drawPillElement(id)
            }
            Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (id in pill.zones["center"].orEmpty()) drawPillElement(id)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (id in pill.zones["right"].orEmpty()) drawPillElement(id)
            }
        }
        if (hovered) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).zIndex(2f)) {
            DesktopSpringButton(onClick = onOpenPillStudio, size = 26.dp) {
                Icon(Icons.Rounded.Edit, "Customise pill", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Artwork with a thin glass edge, an optional spin and the cross-fade the Studio asks for. */
@Composable
private fun LiquidPillArtwork(
    song: Song,
    modifier: Modifier,
    size: Dp,
    shape: Shape,
    rotation: () -> Float,
    instantTrackChange: Boolean,
    durationMs: Int,
    transitionTarget: Boolean = false,
) {
    // Artwork transition: only the live pill (not the Studio preview) is the landing target.
    val artRevealing = rememberArtRevealing()
    Box(
        modifier.size(size).then(if (transitionTarget) Modifier.artTransitionTarget() else Modifier)
            .clip(shape).border(0.8.dp, Color.White.copy(alpha = 0.22f), shape),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation() }) {
            val art = song.videoId to song.thumbnailUrl
            if (instantTrackChange || (transitionTarget && artRevealing)) {
                DesktopArtwork(art.second, Modifier.fillMaxSize(), px = ROW_ART_PX)
            } else {
                Crossfade(
                    targetState = art,
                    animationSpec = tween(durationMillis = durationMs.coerceIn(100, 800)),
                    label = "liquidPillArtworkTrackChange",
                ) { (_, thumbnail) -> DesktopArtwork(thumbnail, Modifier.fillMaxSize(), px = ROW_ART_PX) }
            }
        }
    }
}

/** Eases the album colour between tracks (snaps when motion is reduced). */
@Composable
private fun animateColorAsStateCompat(target: Color, reduceMotion: Boolean): State<Color> =
    androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = MusesMotion.crossfade(reduceMotion),
        label = "liquidPillAccent",
    )
