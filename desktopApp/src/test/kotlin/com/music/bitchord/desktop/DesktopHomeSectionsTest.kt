package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHomeSectionsTest {
    private fun shelf(title: String, kind: String = "track") = HomeShelf(
        title = title,
        items = listOf(
            ShelfItem("Item", "Artist", null, if (kind == "track") "video" else null, if (kind == "collection") "browse" else null),
        ),
    )

    @Test
    fun stableIdsNormalizeTitlesAndCollapsePersonalizedPatterns() {
        assertEquals(DesktopHomeSections.identify(shelf("Albums & singles")).sectionId,
            DesktopHomeSections.identify(shelf("Albums and singles")).sectionId)
        assertEquals(DesktopHomeSections.identify(shelf("Because you listened to Artist A")).sectionId,
            DesktopHomeSections.identify(shelf("Because you listened to Artist B")).sectionId)
        assertFalse(DesktopHomeSections.identify(shelf("Trending!", "track")).sectionId ==
            DesktopHomeSections.identify(shelf("Trending!", "collection")).sectionId)
    }

    @Test
    fun hiddenAndOrderedShelfPipelineDoesNotRefetchOrMutateInput() {
        val input = listOf(shelf("Quick picks"), shelf("Albums & singles", "collection"), shelf("Listen again"))
        val prefs = DesktopHomeSectionPrefs(
            hiddenIds = setOf("provider:quick-picks"),
            orderIds = listOf("provider:listen-again", "provider:albums-singles"),
            pinnedIds = setOf("provider:listen-again"),
        )
        val visible = DesktopHomeSections.filterAndOrder(input, prefs)
        assertEquals(listOf("Listen again", "Albums & singles"), visible.map(HomeShelf::title))
        assertEquals(3, input.size)
    }

    @Test
    fun newShelvesRespectAutoShowPreferenceAndSeenListIsBounded() {
        val first = DesktopHomeSections.observe(listOf(shelf("Fresh shelf")), DesktopHomeSectionPrefs(showNewShelvesAutomatically = false), 1_000L)
        assertTrue(DesktopHomeSections.identify(shelf("Fresh shelf")).sectionId in first.hiddenIds)
        assertEquals(1, first.seenShelves.size)

        val crowded = first.copy(seenShelves = (0 until DesktopHomeSections.MAX_SEEN + 5).map { index ->
            DesktopSeenHomeShelf("provider:$index", "Shelf $index", firstSeenMs = index.toLong(), lastSeenMs = index.toLong())
        })
        assertEquals(DesktopHomeSections.MAX_SEEN, crowded.validated().seenShelves.size)
    }

    @Test
    fun bulkActionsAndResetKeepThePageUsable() {
        val prefs = DesktopHomeSectionPrefs(hiddenIds = setOf("provider:quick-picks"), orderIds = listOf("provider:albums-singles"))
        val shown = DesktopHomeSections.unhideAll(prefs)
        assertTrue(shown.hiddenIds.isEmpty())
        assertEquals(DesktopHomeSectionPrefs(), DesktopHomeSections.reset(prefs))
        assertFalse(DesktopHomeSections.showOnlyLibrary(prefs).hiddenIds.contains("provider:from-your-library"))
    }
}
