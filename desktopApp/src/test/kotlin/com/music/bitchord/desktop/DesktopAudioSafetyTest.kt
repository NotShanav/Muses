package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// AUDIO FIX: pure tests (no audio hardware, no window) for the retry delay, the freeze tracker and
// the thread-dump text.
class DesktopAudioSafetyTest {

    @Test
    fun `retry delay doubles from 250 ms and stops at 2000 ms`() {
        assertEquals(250L, AudioRetryPolicy.delayMs(1))
        assertEquals(500L, AudioRetryPolicy.delayMs(2))
        assertEquals(1_000L, AudioRetryPolicy.delayMs(3))
        assertEquals(2_000L, AudioRetryPolicy.delayMs(4))
        assertEquals(2_000L, AudioRetryPolicy.delayMs(50))
    }

    @Test
    fun `retry delay is sane for zero and negative counts`() {
        assertEquals(250L, AudioRetryPolicy.delayMs(0))
        assertEquals(250L, AudioRetryPolicy.delayMs(-3))
    }

    @Test
    fun `no probe waiting or a young one reports nothing`() {
        val tracker = UiFreezeTracker(limitMs = 4_000L)
        assertEquals(FreezeEvent.NONE, tracker.onCheck(null))
        assertEquals(FreezeEvent.NONE, tracker.onCheck(0L))
        assertEquals(FreezeEvent.NONE, tracker.onCheck(3_999L))
    }

    @Test
    fun `a freeze is reported once and then reported as over`() {
        val tracker = UiFreezeTracker(limitMs = 4_000L)
        assertEquals(FreezeEvent.FROZE, tracker.onCheck(4_000L))
        assertEquals(FreezeEvent.NONE, tracker.onCheck(5_000L))
        assertEquals(FreezeEvent.NONE, tracker.onCheck(9_000L))
        assertEquals(9_000L, tracker.peakMs)
        assertEquals(FreezeEvent.RECOVERED, tracker.onCheck(null))
        assertEquals(FreezeEvent.NONE, tracker.onCheck(null))
    }

    @Test
    fun `a second freeze after recovery is reported again`() {
        val tracker = UiFreezeTracker(limitMs = 4_000L)
        assertEquals(FreezeEvent.FROZE, tracker.onCheck(4_500L))
        assertEquals(FreezeEvent.RECOVERED, tracker.onCheck(100L))
        tracker.resetPeak()
        assertEquals(0L, tracker.peakMs)
        assertEquals(FreezeEvent.FROZE, tracker.onCheck(6_000L))
    }

    @Test
    fun `thread dump names the thread and its frames and trims long stacks`() {
        val thread = Thread.currentThread()
        val frames = Array(5) { StackTraceElement("com.example.Demo", "method$it", "Demo.kt", it + 1) }
        val dump = formatThreadDump(mapOf(thread to frames), maxFrames = 3)

        assertTrue(dump.contains("\"${thread.name}\""))
        assertTrue(dump.contains("com.example.Demo.method0"))
        assertTrue(dump.contains("com.example.Demo.method2"))
        assertTrue(!dump.contains("method3"))
        assertTrue(dump.contains("... 2 more"))
    }

    @Test
    fun `thread dump of nothing is empty`() {
        assertEquals("", formatThreadDump(emptyMap()))
    }
}
