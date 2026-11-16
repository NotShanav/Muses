package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Convertible / touch mode": the setting against the posture Windows reports. */
class DesktopTouchModeTest {

    @Test
    fun autoFollowsThePostureAndOnlyTabletMeansTouch() {
        assertTrue(DesktopTouchPolicy.resolve(DesktopTouchMode.AUTO, DesktopPosture.TABLET))
        assertFalse(DesktopTouchPolicy.resolve(DesktopTouchMode.AUTO, DesktopPosture.LAPTOP))
        assertFalse(DesktopTouchPolicy.resolve(DesktopTouchMode.AUTO, DesktopPosture.UNKNOWN))
    }

    @Test
    fun alwaysOnIsTouchInEveryPosture() {
        DesktopPosture.entries.forEach { assertTrue(DesktopTouchPolicy.resolve(DesktopTouchMode.ALWAYS_ON, it)) }
    }

    @Test
    fun alwaysOffIsNeverTouch() {
        DesktopPosture.entries.forEach { assertFalse(DesktopTouchPolicy.resolve(DesktopTouchMode.ALWAYS_OFF, it)) }
    }

    @Test
    fun postureFromTheNativeFlags() {
        assertEquals(DesktopPosture.TABLET, DesktopTouchPolicy.postureFromFlags(0b011)) // slate, touch hardware
        assertEquals(DesktopPosture.LAPTOP, DesktopTouchPolicy.postureFromFlags(0b010)) // touch hardware, laptop
        assertEquals(DesktopPosture.TABLET, DesktopTouchPolicy.postureFromFlags(0b111)) // docked slate
        // A desktop reports slate mode as 0 as well; with no touch hardware it must not read as a tablet.
        assertEquals(DesktopPosture.UNKNOWN, DesktopTouchPolicy.postureFromFlags(0b001))
        assertEquals(DesktopPosture.UNKNOWN, DesktopTouchPolicy.postureFromFlags(0))
        assertEquals(DesktopPosture.UNKNOWN, DesktopTouchPolicy.postureFromFlags(-1))
    }

    @Test
    fun storedModeFallsBackToAuto() {
        assertEquals(DesktopTouchMode.AUTO, DesktopTouchMode.fromStored(null))
        assertEquals(DesktopTouchMode.AUTO, DesktopTouchMode.fromStored("nonsense"))
        assertEquals(DesktopTouchMode.ALWAYS_ON, DesktopTouchMode.fromStored("ALWAYS_ON"))
    }
}
