package com.music.bitchord.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * STUDIO S2: puts every customisation setting back to its default, using only setters that already
 * exist (the same ones the "Default" appearance preset and the per-group "Reset" buttons call).
 * Saved user presets, the window material and the title bar are left alone.
 */
internal fun resetAllCustomisation() {
    // STUDIO G5 hook: the pill's own saved presets live inside the pill config; keep them, as the note above promises.
    val keptPillPresets = DesktopAdvancedAppearance.config.value.pillConfig.userPresets
    DesktopAdvancedAppearance.resetAllNewValues()
    if (keptPillPresets.isNotEmpty()) {
        DesktopAdvancedAppearance.update { it.copy(pillConfig = it.pillConfig.copy(userPresets = keptPillPresets)) }
    }
    DesktopInterfaceSettings.setMode(DesktopInterfaceMode.DEFAULT)
    DesktopFluiditySettings.setEnabled(false)
    DesktopFluiditySettings.setSubtle(false)
    DesktopAppearanceSettings.setReduceDynamicBlur(false)
    DesktopAppearanceSettings.setReduceAnimation(false)
    DesktopPlayerBackgroundSettings.set(PlayerBackgroundMode.DEFAULT)
    DesktopMiniPlayerAppearance.setPosition(DesktopMiniPlayerPosition.CENTER)
    DesktopMiniPlayerAppearance.setWidth(720)
    DesktopMiniPlayerAppearance.setBottomInset(16)
    DesktopMiniPlayerAppearance.setCornerRadius(20)
    DesktopMiniPlayerAppearance.setContrast(0.72f)
    DesktopMiniPlayerAppearance.setRim(0.32f)
}

private data class StudioCard(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@Composable
private fun StudioCardView(card: StudioCard, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = card.action),
        color = Color.White.copy(alpha = 0.055f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Icon(card.icon, null, tint = DesktopAccent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(card.subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StudioPageHeader(title: String, subtitle: String, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, color = DesktopSecondary)
        }
        TextButton(onClick = onDone) { Text("Done") }
    }
}

/** The Studio landing page: four cards, each opening the page that already exists for it. */
@Composable
internal fun DesktopStudioHubPage(
    onPillStudio: () -> Unit,
    onHomeLayout: () -> Unit,
    onColours: () -> Unit,
    onPresets: () -> Unit,
    onDone: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StudioPageHeader("Studio", "Make Muses look and feel the way you like.", onDone)
            val cards = listOf(
                StudioCard("Pill", "Arrange the floating player and tune its material and artwork", Icons.Rounded.LibraryMusic, onPillStudio),
                StudioCard("Home layout", "Show, hide, pin and order the shelves on Home", Icons.Rounded.Home, onHomeLayout),
                StudioCard("Colours and typography", "Accent, density, fonts, glass, motion and backgrounds", Icons.Rounded.Palette, onColours),
                StudioCard("Presets and import/export", "Save, apply, share and reset your whole look", Icons.Rounded.Share, onPresets),
            )
            cards.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { card -> StudioCardView(card, Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all customisation") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all customisation?") },
            text = {
                Text(
                    "Colours, typography, density, glass, motion, Home layout, the player background and the pill go back to their defaults. " +
                        "Your saved presets, window material and title bar are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = { resetAllCustomisation(); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** The existing Advanced customisation editors (colours, typography, density, glass, motion) on a page of their own. */
@Composable
internal fun DesktopStudioColoursPage(
    onOpenHomeSections: () -> Unit,
    onDone: () -> Unit,
) {
    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize()) {
            StudioPageHeader("Colours and typography", "Changes preview as you adjust them and save automatically.", onDone)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                DesktopAdvancedCustomizationSettings(onOpenHomeSections)
            }
        }
    }
}

/**
 * Every preset, import and export action in one place. The appearance presets, saved presets,
 * Export JSON and Import JSON are the existing ones (the same composable as in Advanced
 * customisation, showing only that part); the pill's own presets live in Pill Studio's Presets area.
 */
@Composable
internal fun DesktopStudioPresetsPage(
    onPillStudio: () -> Unit,
    onDone: () -> Unit,
) {
    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize()) {
            StudioPageHeader("Presets and import/export", "Appearance presets, saved presets and JSON files.", onDone)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                DesktopAdvancedCustomizationSettings(onOpenHomeSections = {}, presetsOnly = true)
                Spacer(Modifier.height(16.dp))
                Text("Pill presets", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Built-in and saved presets for the floating player are in Pill Studio, under Presets.",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedButton(onClick = onPillStudio) { Text("Open Pill Studio") }
            }
        }
    }
}
