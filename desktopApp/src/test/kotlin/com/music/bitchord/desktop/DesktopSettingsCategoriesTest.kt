package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSettingsCategoriesTest {
    @Test
    fun everySectionBelongsToExactlyOneCategory() {
        DesktopSettingsSection.entries.forEach { section ->
            val owners = DesktopSettingsCategory.entries.filter { section in it.sections }
            assertEquals(1, owners.size, "$section is in ${owners.size} categories")
            assertEquals(owners.single(), categoryOf(section))
        }
    }

    @Test
    fun categoriesListNoSectionTwiceAndNoUnknownSection() {
        val listed = DesktopSettingsCategory.entries.flatMap { it.sections }
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(DesktopSettingsSection.entries.toSet(), listed.toSet())
    }

    @Test
    fun noCategoryIsEmpty() {
        DesktopSettingsCategory.entries.forEach { assertTrue(it.sections.isNotEmpty(), "${it.name} is empty") }
    }

    @Test
    fun searchShowsEverySectionInRailOrder() {
        val all = settingsSectionsToShow(DesktopSettingsCategory.SOUND, searching = true)
        assertEquals(DesktopSettingsSection.entries.size, all.size)
        assertEquals(DesktopSettingsCategory.entries.flatMap { it.sections }, all)
    }

    @Test
    fun withoutSearchOnlyTheChosenCategoryShows() {
        val sound = settingsSectionsToShow(DesktopSettingsCategory.SOUND, searching = false)
        assertEquals(DesktopSettingsCategory.SOUND.sections, sound)
        assertTrue(DesktopSettingsSection.PLAYBACK !in sound)
    }
}
