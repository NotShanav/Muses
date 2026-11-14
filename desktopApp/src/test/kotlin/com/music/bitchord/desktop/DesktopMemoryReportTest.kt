package com.music.bitchord.desktop

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UPGRADE M3: the pure helpers of the memory report and the probe's byte maths. */
class DesktopMemoryReportTest {

    @Test
    fun bytesToMbRoundsDownAndClampsNegatives() {
        assertEquals(0L, ProbeMemoryMath.bytesToMb(0L))
        assertEquals(0L, ProbeMemoryMath.bytesToMb(-5L))
        assertEquals(0L, ProbeMemoryMath.bytesToMb(1_048_575L))
        assertEquals(1L, ProbeMemoryMath.bytesToMb(1_048_576L))
        assertEquals(512L, ProbeMemoryMath.bytesToMb(512L * 1024L * 1024L))
    }

    @Test
    fun mbFormatsOneDecimalAndUndefinedAsNotAvailable() {
        assertEquals("0.0 MB", MemoryReportFormat.mb(0L))
        assertEquals("1.5 MB", MemoryReportFormat.mb(1_572_864L))
        assertEquals("512.0 MB", MemoryReportFormat.mb(512L * 1024L * 1024L))
        assertEquals("n/a", MemoryReportFormat.mb(-1L))
    }

    @Test
    fun threadNamesGroupByStrippingNumbers() {
        val groups = MemoryReportFormat.threadGroups(
            listOf("pool-1-thread-1", "pool-2-thread-7", "main", "DefaultDispatcher-worker-3", "DefaultDispatcher-worker-4", "DefaultDispatcher-worker-5"),
        )
        assertEquals("DefaultDispatcher-worker-#" to 3, groups.first())
        assertEquals(2, groups.first { it.first == "pool-#-thread-#" }.second)
        assertEquals(1, groups.first { it.first == "main" }.second)
    }

    @Test
    fun labelsAreFileSafeAndNeverEmpty() {
        assertEquals("tray-60s", MemoryReportFormat.safeLabel("tray-60s"))
        assertEquals("a_b_c", MemoryReportFormat.safeLabel("a b/c"))
        assertEquals("report", MemoryReportFormat.safeLabel(""))
        assertTrue(MemoryReportFormat.safeLabel("x".repeat(100)).length <= 40)
    }

    @Test
    fun fileNameFollowsThePattern() {
        val name = MemoryReportFormat.fileName("manual", LocalDateTime.of(2026, 11, 14, 9, 5, 7))
        assertEquals("memory-20261114-090507-manual.txt", name)
    }

    @Test
    fun snapshotNeverThrowsAndNamesItsSections() {
        val text = DesktopMemoryReport.snapshot("test")
        assertTrue(text.contains("label: test"))
        assertTrue(text.contains("Java heap and non-heap"))
    }
}
