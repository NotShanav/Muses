package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 360 T2: window classes and sizing helpers at 360 to 1280 dp wide. */
class DesktopAdaptiveTest {

    @Test
    fun classesFollowTheBreakpoints() {
        assertEquals(DesktopWindowClass.COMPACT, DesktopAdaptiveMath.windowClassFor(360f))
        assertEquals(DesktopWindowClass.COMPACT, DesktopAdaptiveMath.windowClassFor(599.9f))
        assertEquals(DesktopWindowClass.MEDIUM, DesktopAdaptiveMath.windowClassFor(600f))
        assertEquals(DesktopWindowClass.MEDIUM, DesktopAdaptiveMath.windowClassFor(979f))
        assertEquals(DesktopWindowClass.EXPANDED, DesktopAdaptiveMath.windowClassFor(980f))
        assertEquals(DesktopWindowClass.EXPANDED, DesktopAdaptiveMath.windowClassFor(1280f))
        assertEquals(DesktopWindowClass.COMPACT, DesktopAdaptiveMath.windowClassFor(Float.NaN))
    }

    @Test
    fun orientationIsPortraitOnlyWhenTaller() {
        assertEquals(DesktopOrientation.PORTRAIT, DesktopAdaptiveMath.orientationFor(540f, 960f))
        assertEquals(DesktopOrientation.LANDSCAPE, DesktopAdaptiveMath.orientationFor(1280f, 800f))
        assertEquals(DesktopOrientation.LANDSCAPE, DesktopAdaptiveMath.orientationFor(700f, 700f))
    }

    @Test
    fun theFourAcceptanceSizesClassifyAsExpected() {
        val sizes = listOf(
            Triple(360f to 640f, DesktopWindowClass.COMPACT, true),
            Triple(540f to 960f, DesktopWindowClass.COMPACT, true),
            Triple(720f to 1280f, DesktopWindowClass.MEDIUM, true),
            Triple(1280f to 800f, DesktopWindowClass.EXPANDED, false),
        )
        for ((size, cls, portrait) in sizes) {
            val a = DesktopAdaptiveMath.adaptiveFor(size.first, size.second)
            assertEquals(cls, a.windowClass, "$size")
            assertEquals(portrait, a.isPortrait, "$size")
        }
    }

    @Test
    fun adaptiveNeverCarriesNegativeOrNonFiniteSizes() {
        val a = DesktopAdaptiveMath.adaptiveFor(-5f, Float.POSITIVE_INFINITY)
        assertEquals(0f, a.widthDp)
        assertEquals(0f, a.heightDp)
    }

    @Test
    fun fitWidthShrinksButNeverGoesNegative() {
        assertEquals(300f, DesktopAdaptiveMath.fitWidth(300f, 1280f, 16f))
        assertEquals(296f, DesktopAdaptiveMath.fitWidth(300f, 328f, 16f))
        assertEquals(0f, DesktopAdaptiveMath.fitWidth(300f, 20f, 16f))
        assertEquals(0f, DesktopAdaptiveMath.fitWidth(-10f, 500f, 16f))
    }

    @Test
    fun columnsFitTheWindowAndStayInRange() {
        assertEquals(1, DesktopAdaptiveMath.columnsFor(100f, 150f, 16f, 6))
        assertEquals(2, DesktopAdaptiveMath.columnsFor(328f, 150f, 16f, 6))
        assertEquals(6, DesktopAdaptiveMath.columnsFor(3000f, 150f, 16f, 6))
        assertEquals(1, DesktopAdaptiveMath.columnsFor(-50f, 150f, 16f, 6))
        assertEquals(1, DesktopAdaptiveMath.columnsFor(500f, 0f, 16f, 6))
    }

    @Test
    fun cellWidthLeavesAPeekAndNeverGoesNegative() {
        assertEquals(156f, DesktopAdaptiveMath.cellWidth(328f, 2, 16f), 0.001f)
        assertEquals(138f, DesktopAdaptiveMath.cellWidth(328f, 2, 16f, peek = 36f), 0.001f)
        assertEquals(0f, DesktopAdaptiveMath.cellWidth(10f, 3, 16f))
    }

    @Test
    fun sidePanelBecomesAnOverlayInNarrowWindows() {
        assertTrue(DesktopAdaptiveMath.sidePanelAsOverlay(360f))
        assertTrue(DesktopAdaptiveMath.sidePanelAsOverlay(799f))
        assertFalse(DesktopAdaptiveMath.sidePanelAsOverlay(800f))
        assertFalse(DesktopAdaptiveMath.sidePanelAsOverlay(1280f))
    }

    @Test
    fun pillIsCappedAndVeiledInCompactWindows() {
        assertEquals(72f, DesktopAdaptiveMath.pillHeight(84f, DesktopWindowClass.COMPACT))
        assertEquals(60f, DesktopAdaptiveMath.pillHeight(60f, DesktopWindowClass.COMPACT))
        assertEquals(84f, DesktopAdaptiveMath.pillHeight(84f, DesktopWindowClass.EXPANDED))
        assertEquals(0.55f, DesktopAdaptiveMath.pillVeil(0.2f, DesktopWindowClass.COMPACT, false))
        assertEquals(0.55f, DesktopAdaptiveMath.pillVeil(0.2f, DesktopWindowClass.EXPANDED, true))
        assertEquals(0.2f, DesktopAdaptiveMath.pillVeil(0.2f, DesktopWindowClass.EXPANDED, false))
        assertEquals(0.8f, DesktopAdaptiveMath.pillVeil(0.8f, DesktopWindowClass.COMPACT, true))
        assertEquals(1f, DesktopAdaptiveMath.pillVeil(7f, DesktopWindowClass.COMPACT, true))
    }

    @Test
    fun pageClearsThePillAndInsetsHaveAFloor() {
        assertEquals(100f, DesktopAdaptiveMath.pageBottomPadding(72f, 12f))
        assertTrue(DesktopAdaptiveMath.pageBottomPadding(84f, 0f) >= 84f)
        assertEquals(12f, DesktopAdaptiveMath.pillBottomInset(0f, DesktopWindowClass.COMPACT))
        assertEquals(0f, DesktopAdaptiveMath.pillBottomInset(0f, DesktopWindowClass.EXPANDED))
        assertEquals(12f, DesktopAdaptiveMath.pillSideInset(DesktopWindowClass.COMPACT))
        assertEquals(20f, DesktopAdaptiveMath.pillSideInset(DesktopWindowClass.MEDIUM))
    }

    @Test
    fun topBarTargetsGrowToFingerSizeOutsideExpanded() {
        assertEquals(44f, DesktopAdaptiveMath.topBarTarget(DesktopWindowClass.COMPACT, 34f))
        assertEquals(44f, DesktopAdaptiveMath.topBarTarget(DesktopWindowClass.MEDIUM, 32f))
        assertEquals(34f, DesktopAdaptiveMath.topBarTarget(DesktopWindowClass.EXPANDED, 34f))
    }
}
