package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.ROW_ART_PX
import kotlin.math.roundToInt

private val LabWidth = 720.dp
private val LabHeight = 220.dp
private val LabBarWidth = 640.dp
private val LabBarHeight = 80.dp

/** The same smoke the classic glass is made of. */
private val LabTint = Color(0xFF0A0A10)

private val LabAccents: List<Pair<String, Color>> = listOf(
    "Amber" to Color(0xFFFFB347),
    "Rose" to Color(0xFFFF5C8A),
    "Teal" to Color(0xFF2EC4B6),
    "Violet" to Color(0xFF8E6CFF),
    "White" to Color(0xFFFFFFFF),
)

private class LabField(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (DesktopGlassParams) -> Float,
    val set: (DesktopGlassParams, Float) -> DesktopGlassParams,
)

/** One slider per [DesktopGlassParams] field, in the order of the data class. */
private val LabFields: List<LabField> = listOf(
    LabField("Refraction", DesktopGlassParams.REFRACTION, { it.refraction }, { p, v -> p.copy(refraction = v) }),
    LabField("Bevel (dp)", DesktopGlassParams.BEVEL_DP, { it.bevelDp }, { p, v -> p.copy(bevelDp = v) }),
    LabField("Magnify", DesktopGlassParams.MAGNIFY, { it.magnify }, { p, v -> p.copy(magnify = v) }),
    LabField("Dispersion", DesktopGlassParams.DISPERSION, { it.dispersion }, { p, v -> p.copy(dispersion = v) }),
    LabField("Frost", DesktopGlassParams.FROST, { it.frost }, { p, v -> p.copy(frost = v) }),
    LabField("Blur (dp)", DesktopGlassParams.BLUR_DP, { it.blurDp }, { p, v -> p.copy(blurDp = v) }),
    LabField("Specular", DesktopGlassParams.SPECULAR, { it.specular }, { p, v -> p.copy(specular = v) }),
    LabField("Rim", DesktopGlassParams.RIM, { it.rim }, { p, v -> p.copy(rim = v) }),
    LabField("Tint alpha", DesktopGlassParams.TINT_ALPHA, { it.tintAlpha }, { p, v -> p.copy(tintAlpha = v) }),
    LabField("Light angle", DesktopGlassParams.LIGHT_ANGLE_DEG, { it.lightAngleDeg }, { p, v -> p.copy(lightAngleDeg = v) }),
    LabField("Album influence", DesktopGlassParams.ALBUM_INFLUENCE, { it.albumInfluence }, { p, v -> p.copy(albumInfluence = v) }),
)

private fun Float.shown(): String = ((this * 100f).roundToInt() / 100f).toString()

private fun valuesText(p: DesktopGlassParams): String = LabFields.joinToString("\n") { "${it.label}: ${it.get(p).shown()}" }

/**
 * STUDIO G2: Settings > Advanced > Glass lab (experimental). A 640x80 glass bar over a moving test
 * pattern, a slider for every [DesktopGlassParams] field, and the shader's state in plain words.
 * The owner judges the look here and reports back, so the shader can be tuned.
 *
 * The bar sees only the pattern beside it (the lab has its own [DesktopGlassState]), so the page
 * does not need the window's capture and costs nothing once it is closed.
 */
@Composable
internal fun DesktopGlassLabPage(artworkUrl: String?, onDismiss: () -> Unit) {
    val labState = rememberDesktopGlassState()
    var params by remember { mutableStateOf(DesktopGlassParams.Default) }
    var accent by remember { mutableStateOf(LabAccents.first().second) }
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val available = DesktopGlassShader.available
    val error = DesktopGlassShader.error

    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glass lab (experimental)", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Tune the liquid-glass material over a test pattern. It refracts what is inside the Muses window, not the desktop behind it.",
                        color = DesktopSecondary,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    if (available) "shader available: yes" else "shader available: no",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (available) DesktopAccent else Color(0xFFFF6B6B),
                )
                if (error != null) {
                    SelectionContainer { Text("error: $error", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }
                }
                if (reduceBlur) {
                    Text(
                        "Reduce dynamic blur is on, so the bar shows the classic glass. Turn it off in Appearance to see the shader.",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (reduceAnimation) {
                    Text("Reduce animation is on: the pattern and the rim shimmer stand still.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                LabCaptureStatus(labState)
                Spacer(Modifier.height(12.dp))

                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    CompositionLocalProvider(LocalDesktopGlass provides labState) {
                        Box(Modifier.width(LabWidth).height(LabHeight).clip(RoundedCornerShape(16.dp))) {
                            GlassLabPattern(labState, artworkUrl, Modifier.fillMaxSize().glassSource(labState, GlassSlot.PAGE))
                            Row(
                                Modifier
                                    .align(Alignment.Center)
                                    .size(LabBarWidth, LabBarHeight)
                                    .glassPane(RoundedCornerShape(LabBarHeight / 2), params, LabTint, accent)
                                    .glassSurface(RoundedCornerShape(LabBarHeight / 2), params, LabTint, accent),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text("Glass lab", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Album colour", color = DesktopSecondary, modifier = Modifier.width(120.dp))
                    LabAccents.forEach { (_, colour) ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colour)
                                .border(if (colour == accent) 2.dp else 0.5.dp, Color.White.copy(alpha = if (colour == accent) 0.95f else 0.3f), CircleShape)
                                .clickable { accent = colour },
                        )
                    }
                    TextButton(onClick = { params = DesktopGlassParams.Default }) { Text("Reset to defaults") }
                }
                Spacer(Modifier.height(4.dp))
                LabFields.forEach { field ->
                    val value = field.get(params).coerceIn(field.range.start, field.range.endInclusive)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.label, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = value,
                            onValueChange = { params = field.set(params, it).validated() },
                            valueRange = field.range,
                            modifier = Modifier.weight(1f),
                        )
                        Text(value.shown(), modifier = Modifier.width(56.dp), color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Values (select and copy into a message):", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(valuesText(params), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Its own scope so the counter's recompositions do not touch the rest of the page. */
@Composable
private fun LabCaptureStatus(state: DesktopGlassState) {
    Text(
        "glass surfaces in use: ${state.consumers}   frames captured: ${state.revision}",
        color = DesktopSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Diagonal stripes, a fine grid, a moving dot and (when something is playing) the cover: all straight lines, so bending shows. */
@Composable
private fun GlassLabPattern(state: DesktopGlassState, artworkUrl: String?, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val t = state.time
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF1B2A49), Color(0xFF7A2E65), Color(0xFFE0A458))))
            val stripe = 28.dp.toPx()
            val shift = (t * 40.dp.toPx()) % (stripe * 2f)
            var x = -size.height - stripe * 2f + shift
            while (x < size.width + size.height) {
                drawLine(
                    Color.White.copy(alpha = 0.85f),
                    Offset(x, size.height),
                    Offset(x + size.height, 0f),
                    strokeWidth = stripe * 0.45f,
                )
                x += stripe * 2f
            }
            val grid = 16.dp.toPx()
            var gy = 0f
            while (gy < size.height) {
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += grid
            }
            var gx = 0f
            while (gx < size.width) {
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += grid
            }
            val travel = size.width + 100.dp.toPx()
            drawCircle(
                Color(0xFFFFE066),
                radius = 26.dp.toPx(),
                center = Offset(((t * 60.dp.toPx()) % travel) - 50.dp.toPx(), size.height * 0.22f),
            )
        }
        if (artworkUrl != null) {
            DesktopArtwork(
                artworkUrl,
                Modifier
                    .align(Alignment.CenterStart)
                    .size(120.dp)
                    .offset {
                        val travel = (LabWidth - 120.dp).roundToPx().coerceAtLeast(1)
                        val phase = (state.time * 0.12f) % 2f
                        val across = if (phase < 1f) phase else 2f - phase
                        IntOffset((travel * across).toInt(), 0)
                    }
                    .clip(RoundedCornerShape(12.dp)),
                px = ROW_ART_PX,
            )
        }
    }
}
