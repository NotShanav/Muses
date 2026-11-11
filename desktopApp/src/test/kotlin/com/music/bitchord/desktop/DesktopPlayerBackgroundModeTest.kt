package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mode persistence is a name stored as a string; a bad or missing value must never throw. */
class DesktopPlayerBackgroundModeTest {

    @Test
    fun storedNamesRoundTrip() {
        PlayerBackgroundMode.entries.forEach {
            assertEquals(it, PlayerBackgroundMode.fromStored(it.name))
        }
    }

    @Test
    fun unknownOrMissingFallsBackToAcrylicGlass() {
        assertEquals(PlayerBackgroundMode.ACRYLIC_GLASS, PlayerBackgroundMode.fromStored(null))
        assertEquals(PlayerBackgroundMode.ACRYLIC_GLASS, PlayerBackgroundMode.fromStored(""))
        assertEquals(PlayerBackgroundMode.ACRYLIC_GLASS, PlayerBackgroundMode.fromStored("PURPLE_RAIN"))
    }

    @Test
    fun existingUsersKeepTodaysAppearance() {
        assertEquals(PlayerBackgroundMode.ACRYLIC_GLASS, PlayerBackgroundMode.DEFAULT)
    }
}
