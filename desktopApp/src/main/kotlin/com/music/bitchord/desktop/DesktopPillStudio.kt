package com.music.bitchord.desktop

import com.music.bitchord.data.model.ROW_ART_PX
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
internal enum class DesktopPillArtPlacement { LEFT, RIGHT, HIDDEN }
@Serializable
internal enum class DesktopPillArtSize { SMALL, MEDIUM, LARGE }
@Serializable
internal enum class DesktopPillAnimation { CROSSFADE, SLIDE, POP, NONE }
@Serializable
internal enum class DesktopPillClickAction { OPEN_PLAYER, NOTHING }
@Serializable
internal enum class DesktopPillSpinPause { STOP, EASE_TO_STOP, RETURN_TO_ZERO }
@Serializable
internal enum class DesktopPillShape { PILL, ROUNDED_RECTANGLE, SQUIRCLE, CUSTOM_RADIUS }

/** Desktop-only zone layout and advanced pill styling. Defaults mirror the existing dock. */
@Serializable
internal data class DesktopPillConfig(
    val schemaVersion: Int = 1,
    val zones: Map<String, List<String>> = defaultPillZones(),
    val hiddenElements: Set<String> = emptySet(),
    val dockPosition: String = "CENTER",
    val maxWidth: Int = 720,
    val bottomInset: Int = 16,
    val cornerRadius: Int = 20,
    val shape: DesktopPillShape = DesktopPillShape.ROUNDED_RECTANGLE,
    val material: DesktopMiniPlayerMaterial = DesktopMiniPlayerMaterial.GLASS,
    val contrast: Float = 0.72f,
    val rim: Float = 0.32f,
    val height: DesktopMiniPlayerHeight = DesktopMiniPlayerHeight.DEFAULT,
    val showArtwork: Boolean = true,
    val showMetadata: Boolean = true,
    val artPlacement: DesktopPillArtPlacement = DesktopPillArtPlacement.LEFT,
    val artSize: DesktopPillArtSize = DesktopPillArtSize.MEDIUM,
    val artShape: DesktopArtworkShape = DesktopArtworkShape.ROUNDED,
    val spinWhilePlaying: Boolean = false,
    val spinRpm: Int = 18,
    val spinClockwise: Boolean = true,
    val spinPause: DesktopPillSpinPause = DesktopPillSpinPause.EASE_TO_STOP,
    val trackAnimation: DesktopPillAnimation = DesktopPillAnimation.CROSSFADE,
    val trackAnimationMs: Int = 280,
    val clickAction: DesktopPillClickAction = DesktopPillClickAction.OPEN_PLAYER,
    val expandOnHover: Boolean = false,
    val showTooltips: Boolean = true,
    val userPresets: Map<String, String> = emptyMap(),
) {
    fun validated(): DesktopPillConfig {
        val allowed = PILL_ELEMENTS.toSet()
        val claimed = mutableSetOf<String>()
        val cleanZones = PILL_ZONES.associateWith { zone ->
            zones[zone].orEmpty().filter { it in allowed && claimed.add(it) }.take(12)
        }
        val safeZones = cleanZones.toMutableMap()
        // Playback remains operable and at least one identity cue always survives malformed imports.
        if (safeZones.values.none { "play" in it }) safeZones["right"] = safeZones.getValue("right") + "play"
        val safeHidden = hiddenElements.filter { it in allowed && it != "play" }.toMutableSet()
        if ((safeZones.values.any { "metadata" in it } && "metadata" !in safeHidden).not() &&
            (safeZones.values.any { "artwork" in it } && "artwork" !in safeHidden).not()) {
            safeHidden.remove("metadata")
            if (safeZones.values.none { "metadata" in it }) safeZones["center"] = safeZones.getValue("center") + "metadata"
        }
        return copy(
            schemaVersion = 1,
            zones = safeZones,
            hiddenElements = safeHidden.toSet(),
            dockPosition = dockPosition.takeIf { it in setOf("LEFT", "CENTER", "RIGHT") } ?: "CENTER",
            maxWidth = maxWidth.coerceIn(560, 920),
            bottomInset = bottomInset.coerceIn(0, 120),
            cornerRadius = cornerRadius.coerceIn(0, 48),
            contrast = contrast.takeIf(Float::isFinite)?.coerceIn(0.48f, 0.9f) ?: 0.72f,
            rim = rim.takeIf(Float::isFinite)?.coerceIn(0f, 0.7f) ?: 0.32f,
            spinRpm = spinRpm.coerceIn(5, 60),
            trackAnimationMs = trackAnimationMs.coerceIn(100, 800),
            trackAnimation = if (trackAnimation == DesktopPillAnimation.NONE) DesktopPillAnimation.NONE else DesktopPillAnimation.CROSSFADE,
            userPresets = userPresets.entries.mapNotNull { (name, value) ->
                val safeName = name.trim().take(32)
                safeName.takeIf { it.isNotBlank() && value.length <= 8_192 }?.let { it to value }
            }.take(8).toMap(),
        )
    }
}

private val PILL_ZONES = listOf("left", "center", "right")
private val PILL_ELEMENTS = listOf("artwork", "metadata", "previous", "play", "next", "shuffle", "repeat", "more")
private val PillPresetJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private fun defaultPillZones() = mapOf(
    "left" to listOf("artwork"),
    "center" to listOf("metadata"),
    "right" to listOf("previous", "play", "next", "shuffle", "repeat", "more"),
)

internal fun migrateLegacyPillConfig(appearance: DesktopAppearanceConfig): DesktopPillConfig = DesktopPillConfig(
    dockPosition = DesktopMiniPlayerAppearance.position.value.name,
    maxWidth = DesktopMiniPlayerAppearance.width.value,
    bottomInset = DesktopMiniPlayerAppearance.bottomInset.value,
    cornerRadius = DesktopMiniPlayerAppearance.cornerRadius.value,
    contrast = DesktopMiniPlayerAppearance.contrast.value,
    rim = DesktopMiniPlayerAppearance.rim.value,
    height = appearance.miniPlayerHeight,
    material = appearance.miniPlayerMaterial,
    showArtwork = appearance.miniPlayerArtwork,
    showMetadata = appearance.miniPlayerMetadata,
    artSize = when (appearance.miniPlayerHeight) {
        DesktopMiniPlayerHeight.COMPACT -> DesktopPillArtSize.SMALL
        DesktopMiniPlayerHeight.DEFAULT -> DesktopPillArtSize.MEDIUM
        DesktopMiniPlayerHeight.LARGE -> DesktopPillArtSize.LARGE
    },
    artShape = appearance.miniPlayerArtShape,
).validated()

/** First stop for desktop appearance editors; each tile opens an existing editor or the pill studio. */
@Composable
internal fun DesktopCustomiseHubPage(
    onHomeSections: () -> Unit,
    onPillStudio: () -> Unit,
    onAppearance: () -> Unit,
    onDone: () -> Unit,
) {
    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Customise", style = MaterialTheme.typography.headlineMedium)
                    Text("Your desktop listening space, arranged your way.", color = DesktopSecondary)
                }
                TextButton(onClick = onDone) { Text("Done") }
            }
            Spacer(Modifier.height(20.dp))
            listOf(
                HubItem("Home", "Show, hide, pin, and order Home shelves", Icons.Rounded.Home, onHomeSections),
                HubItem("Floating pill", "Arrange the mini-player and tune its glass and artwork", Icons.Rounded.LibraryMusic, onPillStudio),
                HubItem("Theme and motion", "Modern / Legacy, artwork colour, glass, and motion settings", Icons.Rounded.Palette, onAppearance),
            ).forEach { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = item.action),
                    color = Color.White.copy(alpha = 0.055f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, null, tint = DesktopAccent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = DesktopSecondary, style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("More editors can be added here as their controls become available.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class HubItem(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val action: () -> Unit)

/** Full-page draft editor. State is committed only by Apply; Cancel discards all draft edits. */
@Composable
internal fun DesktopPillStudioPage(
    songTitle: String?,
    artist: String?,
    artworkUrl: String?,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onApply: (DesktopPillConfig) -> Unit,
) {
    val appearance by DesktopAdvancedAppearance.config.collectAsState()
    val live = appearance.pillConfig
    val legacyPosition by DesktopMiniPlayerAppearance.position.collectAsState()
    val legacyWidth by DesktopMiniPlayerAppearance.width.collectAsState()
    val legacyInset by DesktopMiniPlayerAppearance.bottomInset.collectAsState()
    val legacyRadius by DesktopMiniPlayerAppearance.cornerRadius.collectAsState()
    val legacyContrast by DesktopMiniPlayerAppearance.contrast.collectAsState()
    val legacyRim by DesktopMiniPlayerAppearance.rim.collectAsState()
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val seed = remember(live, legacyPosition, legacyWidth, legacyInset, legacyRadius, legacyContrast, legacyRim, appearance.miniPlayerArtwork, appearance.miniPlayerMetadata, appearance.miniPlayerHeight, appearance.miniPlayerArtShape, appearance.miniPlayerMaterial, appearance.miniPlayerShowShuffle, appearance.miniPlayerShowRepeat, appearance.miniPlayerTapExpands) {
        val hidden = live.hiddenElements.toMutableSet().apply {
            if (appearance.miniPlayerArtwork) remove("artwork") else add("artwork")
            if (appearance.miniPlayerMetadata) remove("metadata") else add("metadata")
            if (appearance.miniPlayerShowShuffle) remove("shuffle") else add("shuffle")
            if (appearance.miniPlayerShowRepeat) remove("repeat") else add("repeat")
        }
        live.copy(
            dockPosition = legacyPosition.name,
            maxWidth = legacyWidth,
            bottomInset = legacyInset,
            cornerRadius = legacyRadius,
            contrast = legacyContrast,
            rim = legacyRim,
            height = appearance.miniPlayerHeight,
            material = appearance.miniPlayerMaterial,
            artShape = appearance.miniPlayerArtShape,
            hiddenElements = hidden,
            showArtwork = true,
            showMetadata = true,
            clickAction = if (appearance.miniPlayerTapExpands) DesktopPillClickAction.OPEN_PLAYER else DesktopPillClickAction.NOTHING,
        ).validated()
    }
    var draft by remember(seed) { mutableStateOf(seed) }
    val undo = remember { mutableStateListOf<DesktopPillConfig>() }
    val redo = remember { mutableStateListOf<DesktopPillConfig>() }
    var selected by remember { mutableStateOf("artwork") }
    var presetName by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    var changed by remember(draft, seed) { mutableStateOf(draft != seed) }
    fun edit(transform: (DesktopPillConfig) -> DesktopPillConfig) {
        undo.add(draft)
        if (undo.size > 50) undo.removeAt(0)
        redo.clear()
        draft = transform(draft).validated()
        changed = draft != seed
    }
    fun applyPreset(name: String) {
        val classic = migrateLegacyPillConfig(appearance)
        val value = when (name) {
            "Classic" -> classic
            "Compact" -> classic.copy(height = DesktopMiniPlayerHeight.COMPACT, artSize = DesktopPillArtSize.SMALL, spinWhilePlaying = false)
            "Vinyl" -> classic.copy(artShape = DesktopArtworkShape.CIRCLE, artSize = DesktopPillArtSize.LARGE, spinWhilePlaying = true)
            "Big Art" -> classic.copy(height = DesktopMiniPlayerHeight.LARGE, artSize = DesktopPillArtSize.LARGE)
            "Minimal" -> classic.copy(hiddenElements = classic.hiddenElements + setOf("shuffle", "repeat"), height = DesktopMiniPlayerHeight.COMPACT)
            "Controls Only" -> classic.copy(hiddenElements = classic.hiddenElements + "artwork", showMetadata = true)
            else -> null
        }
        value?.let { preset -> edit { preset.copy(userPresets = it.userPresets) } }
    }

        DesktopSettingsPageFrame(maxWidth = 1200.dp) {
        Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pill Studio", style = MaterialTheme.typography.headlineMedium)
                    Text("Arrange the floating player. Changes stay in this draft until Apply.", color = DesktopSecondary)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel and close") }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(0.95f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f)).padding(14.dp)) {
                    Text("ELEMENTS", style = MaterialTheme.typography.labelMedium, color = DesktopSecondary)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PILL_ELEMENTS, key = { it }) { id ->
                            val zone = draft.zones.entries.firstOrNull { id in it.value }?.key ?: "right"
                            val visible = id !in draft.hiddenElements
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (selected == id) DesktopAccent.copy(alpha = 0.14f) else Color.Transparent).clickable { selected = id }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = visible, enabled = id != "play", onCheckedChange = { show -> edit { updateElement(it, id, visible = show) } })
                                Column(Modifier.weight(1f)) {
                                    Text(id.pillLabel(), maxLines = 1)
                                    Text("${zone.replaceFirstChar(Char::uppercase)} zone", color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Text("Play / Pause is required. Keep art or track text visible.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1.35f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LIVE PREVIEW", style = MaterialTheme.typography.labelMedium, color = DesktopSecondary)
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF111116)), contentAlignment = Alignment.Center) {
                        PillStudioPreview(draft, songTitle, artist, artworkUrl, isPlaying)
                    }
                    Text("Preview uses the current track when available.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f)).padding(14.dp)) {
                    Text("INSPECTOR · ${selected.pillLabel()}", style = MaterialTheme.typography.labelMedium, color = DesktopSecondary)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                        item { Text("Dock", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopMiniPlayerHeight.entries.forEach { option -> FilterChip(selected = draft.height == option, onClick = { edit { it.copy(height = option) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopMiniPlayerPosition.entries.forEach { option -> FilterChip(selected = draft.dockPosition == option.name, onClick = { edit { it.copy(dockPosition = option.name) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { PillStudioSlider("Maximum width", draft.maxWidth.toFloat(), 560f..920f, "${draft.maxWidth} dp") { value -> edit { it.copy(maxWidth = value.roundToInt()) } } }
                        item { PillStudioSlider("Bottom spacing", draft.bottomInset.toFloat(), 0f..120f, "${draft.bottomInset} dp") { value -> edit { it.copy(bottomInset = value.roundToInt()) } } }
                        item { PillStudioSlider("Corner radius", draft.cornerRadius.toFloat(), 0f..48f, "${draft.cornerRadius} dp") { value -> edit { it.copy(cornerRadius = value.roundToInt()) } } }
                        item { Text("Pill shape", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopPillShape.entries.forEach { option -> FilterChip(selected = draft.shape == option, onClick = { edit { it.copy(shape = option) } }, label = { Text(option.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) }) } } }
                        item { PillStudioSlider("Glass contrast", draft.contrast, 0.48f..0.9f, "${(draft.contrast * 100).toInt()}%") { value -> edit { it.copy(contrast = value) } } }
                        item { PillStudioSlider("Rim highlight", draft.rim, 0f..0.7f, "${(draft.rim * 100).toInt()}%") { value -> edit { it.copy(rim = value) } } }
                        item { Text("Artwork", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopPillArtPlacement.entries.forEach { option -> FilterChip(selected = draft.artPlacement == option, onClick = { edit { config ->
                            when (option) {
                                DesktopPillArtPlacement.LEFT -> config.moveElement("artwork", "left").copy(artPlacement = option, hiddenElements = config.hiddenElements - "artwork")
                                DesktopPillArtPlacement.RIGHT -> config.moveElement("artwork", "right").copy(artPlacement = option, hiddenElements = config.hiddenElements - "artwork")
                                DesktopPillArtPlacement.HIDDEN -> config.copy(artPlacement = option, hiddenElements = config.hiddenElements + "artwork")
                            }
                        } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopPillArtSize.entries.forEach { option -> FilterChip(selected = draft.artSize == option, onClick = { edit { it.copy(artSize = option) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopArtworkShape.entries.forEach { option -> FilterChip(selected = draft.artShape == option, onClick = { edit { it.copy(artShape = option) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Spin art while playing")
                                    Text("Vinyl-style rotation; disabled when Reduce animations is on", color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(draft.spinWhilePlaying, { value -> edit { it.copy(spinWhilePlaying = value) } }, enabled = !reduceMotion)
                            }
                        }
                        if (reduceMotion) item { Text("Reduce animations is on. Spin remains disabled until that accessibility setting is turned off.", color = DesktopSecondary, style = MaterialTheme.typography.labelSmall) }
                        if (draft.spinWhilePlaying) item { PillStudioSlider("Spin speed", draft.spinRpm.toFloat(), 5f..60f, "${draft.spinRpm} RPM") { value -> edit { it.copy(spinRpm = value.roundToInt()) } } }
                        if (draft.spinWhilePlaying) item { Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { DesktopPillSpinPause.entries.forEach { option -> FilterChip(selected = draft.spinPause == option, onClick = { edit { it.copy(spinPause = option) } }, label = { Text(option.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) }) } } }
                        if (draft.spinWhilePlaying) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Clockwise", Modifier.weight(1f)); Switch(draft.spinClockwise, { value -> edit { it.copy(spinClockwise = value) } }) } }
                        item { Text("Track change", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(DesktopPillAnimation.CROSSFADE, DesktopPillAnimation.NONE).forEach { option -> FilterChip(selected = draft.trackAnimation == option, onClick = { edit { it.copy(trackAnimation = option) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { PillStudioSlider("Transition", draft.trackAnimationMs.toFloat(), 100f..800f, "${draft.trackAnimationMs} ms") { value -> edit { it.copy(trackAnimationMs = value.roundToInt()) } } }
                        item { Text("Background material", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopMiniPlayerMaterial.entries.forEach { option -> FilterChip(selected = draft.material == option, onClick = { edit { it.copy(material = option) } }, label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                        item { Text("Body action", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { DesktopPillClickAction.entries.forEach { option -> FilterChip(selected = draft.clickAction == option, onClick = { edit { it.copy(clickAction = option) } }, label = { Text(if (option == DesktopPillClickAction.OPEN_PLAYER) "Open player" else "Do nothing") }) } } }
                        item { Text("Element zone", style = MaterialTheme.typography.titleSmall) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PILL_ZONES.forEach { zone -> FilterChip(selected = draft.zones.entries.firstOrNull { selected in it.value }?.key == zone, onClick = { edit { config ->
                            when {
                                selected != "artwork" -> config.moveElement(selected, zone)
                                zone == "left" -> config.moveElement(selected, zone).copy(artPlacement = DesktopPillArtPlacement.LEFT, hiddenElements = config.hiddenElements - "artwork")
                                zone == "right" -> config.moveElement(selected, zone).copy(artPlacement = DesktopPillArtPlacement.RIGHT, hiddenElements = config.hiddenElements - "artwork")
                                else -> config.moveElement(selected, zone)
                            }
                        } }, label = { Text(zone.replaceFirstChar(Char::uppercase)) }) } } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { edit { it.moveElement(selected, draft.zones.entries.firstOrNull { z -> selected in z.value }?.key ?: "right", -1) } }) { Text("Move left") }
                            TextButton(onClick = { edit { it.moveElement(selected, draft.zones.entries.firstOrNull { z -> selected in z.value }?.key ?: "right", 1) } }) { Text("Move right") }
                        } }
                        item { Text("Presets", style = MaterialTheme.typography.titleSmall) }
                        item { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(listOf("Classic", "Compact", "Vinyl"), listOf("Big Art", "Minimal", "Controls Only")).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { row.forEach { name -> TextButton(onClick = { applyPreset(name) }) { Text(name, maxLines = 1) } } }
                            }
                        } }
                        item {
                            OutlinedTextField(presetName, { presetName = it.take(32) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Preset name") })
                        }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = presetName.isNotBlank() && (presetName in draft.userPresets || draft.userPresets.size < 8), onClick = {
                                val safeName = presetName.trim().take(32)
                                val encoded = PillPresetJson.encodeToString(draft.copy(userPresets = emptyMap()))
                                edit { it.copy(userPresets = it.userPresets + (safeName to encoded)) }
                                selectedPreset = safeName
                            }) { Text("Save / duplicate") }
                            TextButton(enabled = selectedPreset != null && presetName.isNotBlank(), onClick = {
                                val old = selectedPreset ?: return@TextButton
                                val encoded = draft.userPresets[old] ?: return@TextButton
                                edit { it.copy(userPresets = it.userPresets - old + (presetName.trim().take(32) to encoded)) }
                                selectedPreset = presetName.trim().take(32)
                            }) { Text("Rename") }
                            TextButton(enabled = selectedPreset != null, onClick = {
                                val old = selectedPreset ?: return@TextButton
                                edit { it.copy(userPresets = it.userPresets - old) }
                                selectedPreset = null
                            }) { Text("Delete") }
                        } }
                        item { Column { draft.userPresets.forEach { (name, encoded) ->
                            TextButton(onClick = {
                                selectedPreset = name
                                presetName = name
                                val restored = runCatching { PillPresetJson.decodeFromString<DesktopPillConfig>(encoded) }.getOrNull()
                                if (restored != null) edit { restored.copy(userPresets = draft.userPresets) }
                            }) { Text("Apply · $name") }
                        } } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { edit { migrateLegacyPillConfig(appearance).copy(userPresets = draft.userPresets) } }) { Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(5.dp)); Text("Reset pill") }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = undo.isNotEmpty(), onClick = { redo.add(draft); draft = undo.removeAt(undo.lastIndex); changed = draft != seed }) { Icon(Icons.Rounded.Undo, "Undo"); Text("Undo") }
                TextButton(enabled = redo.isNotEmpty(), onClick = { undo.add(draft); draft = redo.removeAt(redo.lastIndex); changed = draft != seed }) { Icon(Icons.Rounded.Redo, "Redo"); Text("Redo") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(enabled = changed, onClick = { onApply(draft.validated()) }) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Apply") }
            }
        }
    }
}

@Composable
private fun PillStudioPreview(config: DesktopPillConfig, songTitle: String?, artist: String?, artworkUrl: String?, isPlaying: Boolean) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val spin = remember { Animatable(0f) }
    LaunchedEffect(config.spinWhilePlaying, config.spinRpm, config.spinClockwise, isPlaying, reduceMotion) {
        if (!config.spinWhilePlaying || !isPlaying || reduceMotion) {
            if (reduceMotion) spin.snapTo(0f) else spin.stop()
            return@LaunchedEffect
        }
        val direction = if (config.spinClockwise) 360f else -360f
        while (true) spin.animateTo(spin.value + direction, tween((60_000 / config.spinRpm.coerceIn(5, 60)).coerceAtLeast(250), easing = LinearEasing))
    }
    val shape = when (config.shape) {
        DesktopPillShape.PILL -> RoundedCornerShape(percent = 50)
        DesktopPillShape.ROUNDED_RECTANGLE -> RoundedCornerShape(20.dp)
        DesktopPillShape.SQUIRCLE -> RoundedCornerShape(percent = 36)
        DesktopPillShape.CUSTOM_RADIUS -> RoundedCornerShape(config.cornerRadius.dp)
    }
    val artShape = when (config.artShape) { DesktopArtworkShape.SQUARE -> RoundedCornerShape(0.dp); DesktopArtworkShape.ROUNDED -> RoundedCornerShape(9.dp); DesktopArtworkShape.CIRCLE -> CircleShape }
    val height = when (config.height) { DesktopMiniPlayerHeight.COMPACT -> 60.dp; DesktopMiniPlayerHeight.DEFAULT -> 72.dp; DesktopMiniPlayerHeight.LARGE -> 84.dp }
    val drawElement: @Composable (String) -> Unit = { id ->
        if (id !in config.hiddenElements) when (id) {
            "artwork" -> if (config.showArtwork && config.artPlacement != DesktopPillArtPlacement.HIDDEN) {
                if (artworkUrl != null) DesktopArtwork(artworkUrl, Modifier.size(config.artSize.dpSize()).clip(artShape).graphicsLayer { rotationZ = if (config.spinWhilePlaying && isPlaying && !reduceMotion) spin.value else 0f }, px = ROW_ART_PX)
                else Box(Modifier.size(config.artSize.dpSize()).clip(artShape).background(DesktopAccent.copy(alpha = 0.48f)), contentAlignment = Alignment.Center) { Text("M", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            }
            "metadata" -> if (config.showMetadata) Column(Modifier.fillMaxWidth()) {
                Text(songTitle?.takeIf(String::isNotBlank) ?: "Demo track", maxLines = 1, color = Color.White)
                Text(artist?.takeIf(String::isNotBlank) ?: "Muses · preview", maxLines = 1, color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
            }
            "previous" -> Text("‹", color = Color.White, style = MaterialTheme.typography.titleMedium)
            "play" -> Text("▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
            "next" -> Text("›", color = Color.White, style = MaterialTheme.typography.titleMedium)
            "shuffle" -> Text("⤨", color = DesktopSecondary)
            "repeat" -> Text("↻", color = DesktopSecondary)
            "more" -> Text("···", color = DesktopSecondary)
        }
    }
    Row(
        Modifier.widthIn(max = config.maxWidth.dp).fillMaxWidth(0.94f).height(height)
            .clip(shape).background(Color(0xFF090A10).copy(alpha = config.contrast), shape).desktopBarGlass(shape, config.rim)
            .background(Color(0xFF080A12).copy(alpha = config.contrast * 0.45f), shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in config.zones["left"].orEmpty()) drawElement(id)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in config.zones["center"].orEmpty()) drawElement(id)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in config.zones["right"].orEmpty()) drawElement(id)
        }
    }
}

@Composable
private fun PillStudioSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(label, color = DesktopSecondary, style = MaterialTheme.typography.labelSmall) }
        Slider(value, onChange, valueRange = range)
    }
}

private fun DesktopPillArtSize.dpSize() = when (this) { DesktopPillArtSize.SMALL -> 32.dp; DesktopPillArtSize.MEDIUM -> 44.dp; DesktopPillArtSize.LARGE -> 56.dp }
private fun String.pillLabel() = when (this) { "artwork" -> "Album artwork"; "metadata" -> "Title and artist"; "previous" -> "Previous"; "play" -> "Play / Pause"; "next" -> "Next"; "shuffle" -> "Shuffle"; "repeat" -> "Repeat"; "more" -> "More options"; else -> this }
private fun updateElement(config: DesktopPillConfig, id: String, visible: Boolean): DesktopPillConfig {
    val next = config.copy(
        hiddenElements = if (visible) config.hiddenElements - id else config.hiddenElements + id,
    )
    return if (id == "artwork" && visible && next.artPlacement == DesktopPillArtPlacement.HIDDEN) {
        next.moveElement("artwork", "left").copy(artPlacement = DesktopPillArtPlacement.LEFT)
    } else next
}

private fun DesktopPillConfig.moveElement(id: String, zone: String, delta: Int = 0): DesktopPillConfig {
    val currentZones = zones.mapValues { it.value.toMutableList() }.toMutableMap()
    val currentZone = currentZones.entries.firstOrNull { id in it.value }?.key ?: "right"
    if (currentZone != zone) {
        currentZones[currentZone]?.remove(id)
        currentZones.getOrPut(zone) { mutableListOf() }.add(id)
    }
    val items = currentZones.getOrPut(zone) { mutableListOf() }
    val from = items.indexOf(id)
    if (from >= 0 && delta != 0) {
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to != from) items.add(to, items.removeAt(from))
    }
    return copy(zones = currentZones.mapValues { it.value.toList() })
}
