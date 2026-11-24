package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCaptionPlacementTest {
    private fun place(own: Boolean = true, title: Boolean = false, panel: Boolean = false, modern: Boolean, compact: Boolean = false) =
        DesktopCaptionPlacement.place(own, title, panel, modern, compact)

    @Test fun modernUsesTopBar() = assertEquals(DesktopCaptionPlace.TOP_BAR, place(modern = true))

    @Test fun modernCompactStillTopBar() = assertEquals(DesktopCaptionPlace.TOP_BAR, place(modern = true, compact = true))

    @Test fun legacyWideUsesSidebarOnly() = assertEquals(DesktopCaptionPlace.SIDEBAR, place(modern = false))

    @Test fun legacyNarrowFallsBackToTopBar() = assertEquals(DesktopCaptionPlace.TOP_BAR, place(modern = false, compact = true))

    @Test fun systemFrameTitleBarAndPanelDrawNothing() {
        assertEquals(DesktopCaptionPlace.NONE, place(own = false, modern = true))
        assertEquals(DesktopCaptionPlace.NONE, place(title = true, modern = false))
        assertEquals(DesktopCaptionPlace.NONE, place(panel = true, modern = true))
    }

    @Test fun onlyOnePlaceForAnyInput() {
        for (modern in listOf(true, false)) for (compact in listOf(true, false)) {
            val place = place(modern = modern, compact = compact)
            // The sidebar exists only when wide and Legacy; the top bar always exists.
            val sidebarShown = !modern && !compact
            val drawn = (if (place == DesktopCaptionPlace.TOP_BAR) 1 else 0) + (if (place == DesktopCaptionPlace.SIDEBAR && sidebarShown) 1 else 0)
            assertEquals(1, drawn)
        }
    }
}
