package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The palette cache's policy: bounded, and the coldest entry goes first. */
class DesktopCountLruTest {

    @Test
    fun holdsNoMoreThanItsLimit() {
        val lru = DesktopCountLru<Int>(3)
        (1..10).forEach { lru.put("k$it", it) }
        assertEquals(3, lru.size)
        assertTrue(lru.contains("k10"))
        assertFalse(lru.contains("k1"))
    }

    @Test
    fun aReadKeepsAnEntryWarm() {
        val lru = DesktopCountLru<Int>(2)
        lru.put("a", 1)
        lru.put("b", 2)
        assertEquals(1, lru.get("a"))
        lru.put("c", 3)
        assertTrue(lru.contains("a"))
        assertNull(lru.get("b"))
    }

    @Test
    fun replacingAKeyDoesNotGrowIt() {
        val lru = DesktopCountLru<Int>(2)
        lru.put("a", 1)
        lru.put("a", 2)
        assertEquals(1, lru.size)
        assertEquals(2, lru.get("a"))
    }

    @Test
    fun clearEmptiesIt() {
        val lru = DesktopCountLru<Int>(2)
        lru.put("a", 1)
        lru.clear()
        assertEquals(0, lru.size)
    }
}
