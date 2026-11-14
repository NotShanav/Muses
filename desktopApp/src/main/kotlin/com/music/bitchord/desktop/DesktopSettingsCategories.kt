package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * STUDIO S1: the categories the settings page is split into, in the order the rail lists them.
 *
 * Every [DesktopSettingsSection] is in exactly one category (see [categoryOf] and its test). The
 * groups themselves are unchanged; this only decides which page of Settings they are on.
 *
 * [titleKey]/[defaultTitle] are plain strings so that the mapping can be tested without a UI;
 * [icon] is a getter so that nothing loads an icon until the rail draws it.
 */
internal enum class DesktopSettingsCategory(
    val titleKey: String,
    val defaultTitle: String,
    val sections: List<DesktopSettingsSection>,
) {
    ACCOUNT_SYNC(
        "settings_cat_account", "Account and sync",
        listOf(DesktopSettingsSection.ACCOUNT, DesktopSettingsSection.LISTEN_TOGETHER),
    ),
    PLAYBACK(
        "settings_cat_playback", "Playback",
        listOf(
            DesktopSettingsSection.PLAYBACK,
            DesktopSettingsSection.DESKTOP_PLAYBACK,
            DesktopSettingsSection.LYRICS,
        ),
    ),
    SOUND(
        "settings_cat_sound", "Sound",
        listOf(
            DesktopSettingsSection.SOUND_EFFECTS,
            DesktopSettingsSection.AUDIO_QUALITY,
            DesktopSettingsSection.AUDIO_OUTPUT,
            DesktopSettingsSection.OUTPUT_PRECISION,
        ),
    ),
    LIBRARY(
        "settings_cat_library", "Library and sources",
        listOf(
            DesktopSettingsSection.SOURCES,
            DesktopSettingsSection.LOCAL_MUSIC,
            DesktopSettingsSection.DOWNLOADS,
            DesktopSettingsSection.STORAGE,
        ),
    ),
    APPEARANCE(
        "settings_cat_appearance", "Appearance",
        listOf(DesktopSettingsSection.APPEARANCE),
    ),
    SYSTEM(
        "settings_cat_system", "System",
        listOf(DesktopSettingsSection.LANGUAGE, DesktopSettingsSection.MISCELLANEOUS),
    ),
    ADVANCED(
        "settings_cat_advanced", "Advanced and diagnostics",
        listOf(DesktopSettingsSection.ADVANCED, DesktopSettingsSection.DIAGNOSTICS),
    );

    fun title(): String = DesktopStrings[titleKey, defaultTitle]

    /** There is no settings icon in the shared set, so these are the closest existing ones. */
    val icon: ImageVector
        get() = when (this) {
            ACCOUNT_SYNC -> BitChordIcons.Pin
            PLAYBACK -> BitChordIcons.Play
            SOUND -> BitChordIcons.MusicNote
            LIBRARY -> BitChordIcons.Library
            APPEARANCE -> BitChordIcons.GridView
            SYSTEM -> BitChordIcons.Performance
            ADVANCED -> BitChordIcons.FrameRate
        }
}

/** The category a section is shown under. Pure, and tested to be total and unambiguous. */
internal fun categoryOf(section: DesktopSettingsSection): DesktopSettingsCategory =
    DesktopSettingsCategory.entries.first { section in it.sections }

/**
 * The sections to put on the page: just the chosen category's, or, while the search field is not
 * blank, every category's in rail order (the groups then filter themselves on the query).
 */
internal fun settingsSectionsToShow(
    selected: DesktopSettingsCategory,
    searching: Boolean,
): List<DesktopSettingsSection> =
    if (searching) DesktopSettingsCategory.entries.flatMap { it.sections } else selected.sections

/** The category chosen in Settings, kept for as long as the app runs (not saved to disk). */
internal object DesktopSettingsNav {
    var selected by mutableStateOf(DesktopSettingsCategory.ACCOUNT_SYNC)
}

/**
 * Where a group tells its category that it has something to show under the current search, so the
 * category name above the results can hide when none of its groups match.
 */
internal val LocalSettingsCategoryMatches = compositionLocalOf<MutableList<String>?> { null }

/** One settings section as a list item that knows which category's match list it reports to. */
internal fun LazyListScope.settingsSectionItem(
    section: DesktopSettingsSection,
    categoryMatches: MutableList<String>?,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = "settings-section-${section.name}") {
        CompositionLocalProvider(LocalSettingsCategoryMatches provides categoryMatches) {
            content()
        }
    }
}

/** The category name that heads a run of search results, hidden while none of its groups match. */
internal fun LazyListScope.settingsCategoryHeaderItem(
    category: DesktopSettingsCategory,
    categoryMatches: List<String>,
) {
    item(key = "settings-category-${category.name}") {
        if (categoryMatches.isNotEmpty()) {
            Text(
                category.title(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
        }
    }
}

/** The rail down the left of a wide Settings page. */
@Composable
internal fun DesktopSettingsCategoryRail(
    selected: DesktopSettingsCategory,
    dimmed: Boolean,
    onSelect: (DesktopSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DesktopSettingsCategory.entries.forEach { category ->
            val isSelected = category == selected && !dimmed
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .desktopRowClickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    category.icon,
                    null,
                    tint = if (isSelected) DesktopAccent else DesktopSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    category.title(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else DesktopSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The same choices as a scrolling row of chips, for a Settings page too narrow for the rail. */
@Composable
internal fun DesktopSettingsCategoryChips(
    selected: DesktopSettingsCategory,
    dimmed: Boolean,
    onSelect: (DesktopSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DesktopSettingsCategory.entries.forEach { category ->
            val isSelected = category == selected && !dimmed
            Text(
                category.title(),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.Black else Color.White,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.10f))
                    .desktopRowClickable { onSelect(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
