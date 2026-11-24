package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlaylistPinsTest {
    private fun order(items: List<String>, pinned: List<String>) =
        DesktopPlaylistPins.order(items, pinned) { it }

    @Test fun noPinsKeepsOrder() = assertEquals(listOf("a", "b", "c"), order(listOf("a", "b", "c"), emptyList()))

    @Test fun pinnedGoFirstInPinOrder() =
        assertEquals(listOf("c", "a", "b", "d"), order(listOf("a", "b", "c", "d"), listOf("c", "a")))

    @Test fun unknownPinsAreIgnored() = assertEquals(listOf("b", "a"), order(listOf("a", "b"), listOf("zzz", "b")))

    @Test fun nothingLostOrRepeated() {
        val result = order(listOf("a", "b", "c"), listOf("b", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result.sorted())
    }

    @Test fun toggleAddsAndRemoves() {
        assertEquals(listOf("x"), DesktopPlaylistPins.toggle(emptyList(), "x"))
        assertEquals(emptyList(), DesktopPlaylistPins.toggle(listOf("x"), "x"))
    }

    @Test fun codecRoundTripsAndDropsBlanks() {
        assertEquals(listOf("a", "b"), DesktopPlaylistPins.decode(DesktopPlaylistPins.encode(listOf("a", "b", "a"))))
        assertEquals(emptyList(), DesktopPlaylistPins.decode(""))
    }
}
