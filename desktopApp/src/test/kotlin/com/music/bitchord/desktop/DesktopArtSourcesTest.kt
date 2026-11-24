package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopArtSourcesTest {
    private val thumb = ArtRect(10f, 10f, 60f, 60f)
    private val card = ArtRect(0f, 0f, 300f, 200f)
    private val page = ArtRect(0f, 0f, 1000f, 800f)
    private val pill = ArtRect(300f, 700f, 700f, 780f)

    private fun zones() = listOf(
        ArtZone(ArtZoneKind.AREA, null, page),
        ArtZone(ArtZoneKind.SOURCE, "a", card),
        ArtZone(ArtZoneKind.SOURCE, "a", thumb),
        ArtZone(ArtZoneKind.BLOCK, null, pill),
    )

    @Test fun clickOnThumbnailUsesTheThumbnailNotTheCard() {
        val d = ArtSourceRules.decide("a", 30f to 30f, zones())
        assertTrue(d.fire); assertEquals(thumb, d.bounds)
    }

    @Test fun clickOnBigCardUsesTheCard() {
        val d = ArtSourceRules.decide("a", 200f to 150f, zones())
        assertTrue(d.fire); assertEquals(card, d.bounds)
    }

    @Test fun nextButtonInThePillNeverFires() {
        assertFalse(ArtSourceRules.decide("b", 500f to 740f, zones()).fire)
    }

    @Test fun mediaKeyOrAutoAdvanceHasNoPress() {
        assertFalse(ArtSourceRules.decide("b", null, zones()).fire)
    }

    @Test fun sharedCardInsideAnAreaFiresWithoutBounds() {
        val d = ArtSourceRules.decide("z", 600f to 300f, zones())
        assertTrue(d.fire); assertNull(d.bounds)
    }

    @Test fun otherSongsSourceIsUsedWhenNoneMatches() {
        assertNotNull(ArtSourceRules.decide("other", 30f to 30f, zones()).bounds)
    }

    @Test fun pressOutsideEverythingDoesNotFire() {
        assertFalse(ArtSourceRules.decide("a", 2000f to 2000f, zones()).fire)
    }
}
