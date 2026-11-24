package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopEdgeSizeCheckTest {
    private val open = DisplayRect(1500, 0, 420, 1040)

    @Test fun sameSizeAtAnotherXIsNotDrift() = assertFalse(EdgeSizeCheck.drifted(open, DisplayRect(1920, 0, 420, 1040)))

    @Test fun oneStrayPixelIsTolerated() = assertFalse(EdgeSizeCheck.drifted(open, DisplayRect(1500, 0, 421, 1039)))

    @Test fun shorterWindowIsDrift() = assertTrue(EdgeSizeCheck.drifted(open, DisplayRect(1500, 0, 420, 832)))

    @Test fun widerWindowIsDrift() = assertTrue(EdgeSizeCheck.drifted(open, DisplayRect(1500, 0, 900, 1040)))
}
