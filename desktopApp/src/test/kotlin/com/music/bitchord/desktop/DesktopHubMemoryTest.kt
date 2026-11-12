package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure logic only: readers are faked, time is passed in. The one Windows test skips itself elsewhere. */
class DesktopHubMemoryTest {
    private val settings = HubSettings() // warn 1600, cap 2048
    private val t0 = 1_000_000L

    private fun svc(id: String, state: HubServiceState, last: Long = 0L) = HubGovernorService(id, state, last)

    private fun MemoryGovernor.go(
        total: Long,
        services: List<HubGovernorService>,
        now: Long = t0,
        canClose: Boolean = true,
        s: HubSettings = settings,
    ) = decide(total, services, s, now, canClose)

    private val activeOnly = listOf(svc("youtube", HubServiceState.ACTIVE, last = 0L))

    // ---- governor: thresholds ----

    @Test
    fun belowWarnDoesNothing() {
        val g = MemoryGovernor()
        assertEquals(listOf(MemoryAction.None), g.go(1000, activeOnly))
        assertEquals(MemoryLevel.NORMAL, g.level)
    }

    @Test
    fun thresholdsAreStrict() {
        val g = MemoryGovernor()
        assertEquals(listOf(MemoryAction.None), g.go(1600, activeOnly))
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(1601, activeOnly))
        assertEquals(MemoryLevel.WARNING, g.level)
    }

    @Test
    fun zeroAndNegativeTotalsAreNormal() {
        val g = MemoryGovernor()
        assertEquals(listOf(MemoryAction.None), g.go(0, activeOnly))
        assertEquals(listOf(MemoryAction.None), g.go(-5, activeOnly))
        assertEquals(MemoryLevel.NORMAL, g.level)
    }

    @Test
    fun warnIsSentOnceWhileStayingInWarningZone() {
        val g = MemoryGovernor()
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(1700, activeOnly))
        assertEquals(listOf(MemoryAction.None), g.go(1710, activeOnly))
        assertEquals(listOf(MemoryAction.None), g.go(1650, activeOnly))
    }

    // ---- governor: hysteresis ----

    @Test
    fun warningLevelIsLeftOnlyAfterTheMargin() {
        val g = MemoryGovernor()
        g.go(1700, activeOnly)
        // margin at 1600 is 80 MB: back to NORMAL only at <= 1520
        assertEquals(listOf(MemoryAction.None), g.go(1590, activeOnly))
        assertEquals(MemoryLevel.WARNING, g.level)
        g.go(1521, activeOnly)
        assertEquals(MemoryLevel.WARNING, g.level)
        g.go(1520, activeOnly)
        assertEquals(MemoryLevel.NORMAL, g.level)
        // a new climb warns again
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(1650, activeOnly))
    }

    @Test
    fun hoveringAroundTheWarnLimitWarnsOnce() {
        val g = MemoryGovernor()
        val results = listOf(1590L, 1610L, 1595L, 1605L, 1599L, 1602L).map { g.go(it, activeOnly) }
        assertEquals(1, results.count { it.contains(MemoryAction.Warn) })
    }

    @Test
    fun overCapLevelHasItsOwnMargin() {
        val g = MemoryGovernor()
        g.go(2100, activeOnly)
        assertEquals(MemoryLevel.OVER_CAP, g.level)
        // below the cap but within the 102 MB margin (cap 2048 -> 1946): still OVER_CAP, no new warning
        assertEquals(listOf(MemoryAction.None), g.go(2040, activeOnly))
        assertEquals(MemoryLevel.OVER_CAP, g.level)
        assertEquals(listOf(MemoryAction.None), g.go(2060, activeOnly))
        // at 1946 it steps down to WARNING without any action
        assertEquals(listOf(MemoryAction.None), g.go(1946, activeOnly))
        assertEquals(MemoryLevel.WARNING, g.level)
    }

    @Test
    fun escalationWarnsAgain() {
        val g = MemoryGovernor()
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(1700, activeOnly))
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(2100, activeOnly))
    }

    // ---- governor: hibernate choice ----

    @Test
    fun overCapClosesLeastRecentlyUsedNonActiveService() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
            svc("twitch", HubServiceState.LIVE, last = 50),
        )
        assertEquals(
            listOf(MemoryAction.Warn, MemoryAction.Hibernate("twitch")),
            g.go(2100, services),
        )
    }

    @Test
    fun lruTieGoesToTheEarlierEntry() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("a", HubServiceState.LIVE, last = 5),
            svc("b", HubServiceState.LIVE, last = 5),
        )
        assertEquals(listOf(MemoryAction.Warn, MemoryAction.Hibernate("a")), g.go(2100, services))
    }

    @Test
    fun activeServiceIsNeverClosedEvenIfOldest() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 0),
            svc("hibernated", HubServiceState.HIBERNATED, last = 0),
        )
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(2500, services))
        // still over: nothing destructive, no repeated warning
        assertEquals(listOf(MemoryAction.None), g.go(2600, services))
    }

    @Test
    fun hibernatedServicesAreNotCandidates() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("old", HubServiceState.HIBERNATED, last = 1),
            svc("live", HubServiceState.LIVE, last = 500),
        )
        assertEquals(listOf(MemoryAction.Warn, MemoryAction.Hibernate("live")), g.go(2100, services))
    }

    @Test
    fun exactlyAtCapOnlyWarns() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
        )
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(2048, services))
    }

    @Test
    fun noHibernateWhenSingleWindowCloseIsNotPossible() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
        )
        val actions = g.go(2100, services, canClose = false)
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), actions)
        assertFalse(actions.any { it is MemoryAction.Hibernate })
        // the default for the parameter is also "cannot close"
        val g2 = MemoryGovernor()
        assertFalse(g2.decide(2100, services, settings, t0).any { it is MemoryAction.Hibernate })
    }

    // ---- governor: settle period ----

    @Test
    fun settlePeriodPreventsClosingASecondServiceTooSoon() {
        val g = MemoryGovernor()
        val first = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
            svc("twitch", HubServiceState.LIVE, last = 50),
        )
        assertEquals(listOf(MemoryAction.Warn, MemoryAction.Hibernate("twitch")), g.go(2100, first, now = t0))

        val afterFirst = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
            svc("twitch", HubServiceState.HIBERNATED, last = 50),
        )
        // memory not released yet: still over, but within the settle window
        assertEquals(listOf(MemoryAction.None), g.go(2100, afterFirst, now = t0 + 3_000))
        // settle window over: next least-recently-used goes; no second warning (level already OVER_CAP)
        assertEquals(
            listOf<MemoryAction>(MemoryAction.Hibernate("netflix")),
            g.go(2100, afterFirst, now = t0 + MemoryGovernor.DEFAULT_SETTLE_MILLIS),
        )
    }

    @Test
    fun clockMovingBackwardsDoesNotBlockForever() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("a", HubServiceState.LIVE, last = 1),
            svc("b", HubServiceState.LIVE, last = 2),
        )
        g.go(2100, services, now = t0)
        val afterFirst = listOf(services[0], svc("a", HubServiceState.HIBERNATED, 1), services[2])
        assertEquals(
            listOf<MemoryAction>(MemoryAction.Hibernate("b")),
            g.go(2100, afterFirst, now = t0 - 60_000),
        )
    }

    // ---- governor: settings and reset ----

    @Test
    fun capIsNeverBelowWarn() {
        // validated() lifts the cap to the warn value, so 1500 is below both
        val g = MemoryGovernor()
        val odd = HubSettings(memoryWarnMb = 2000, memoryCapMb = 1000)
        assertEquals(listOf(MemoryAction.None), g.go(1500, activeOnly, s = odd))
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(2001, activeOnly, s = odd))
        assertEquals(MemoryLevel.OVER_CAP, g.level)
    }

    @Test
    fun customLimitsAreUsed() {
        val g = MemoryGovernor()
        val small = HubSettings(memoryWarnMb = 500, memoryCapMb = 800)
        assertEquals(listOf<MemoryAction>(MemoryAction.Warn), g.go(501, activeOnly, s = small))
        assertEquals(MemoryLevel.WARNING, g.level)
    }

    @Test
    fun resetForgetsLevelAndSettleTime() {
        val g = MemoryGovernor()
        val services = listOf(
            svc("youtube", HubServiceState.ACTIVE, last = 900),
            svc("netflix", HubServiceState.LIVE, last = 100),
        )
        assertEquals(listOf(MemoryAction.Warn, MemoryAction.Hibernate("netflix")), g.go(2100, services, now = t0))
        g.reset()
        assertEquals(MemoryLevel.NORMAL, g.level)
        // same instant, would be inside the settle window if state had been kept
        assertEquals(listOf(MemoryAction.Warn, MemoryAction.Hibernate("netflix")), g.go(2100, services, now = t0))
    }

    // ---- sampler ----

    private class FakeReader(
        var own: Long = 0L,
        var web: Long = 0L,
        override val supported: Boolean = true,
        private val ownPid: Long = 99L,
    ) : MemoryReader {
        val calls = mutableListOf<List<Long>>()

        override fun workingSetMb(pids: List<Long>): Long {
            calls += pids
            return if (pids == listOf(ownPid)) own else web
        }
    }

    @Test
    fun totalIsMusesPlusWebTree() {
        val reader = FakeReader(own = 800, web = 600)
        val sample = HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 1L }).sample(listOf(1L, 2L, 3L))
        assertEquals(800, sample.musesMb)
        assertEquals(600, sample.webMb)
        assertEquals(1400, sample.totalMb)
        assertTrue(sample.measured)
    }

    @Test
    fun webTreeIsReadWithOneCall() {
        val reader = FakeReader(own = 800, web = 600)
        HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 1L }).sample(listOf(1L, 2L, 3L))
        assertEquals(listOf(listOf(99L), listOf(1L, 2L, 3L)), reader.calls)
    }

    @Test
    fun emptyWebTreeDoesNotCallTheReaderForIt() {
        val reader = FakeReader(own = 800, web = 600)
        val sample = HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 1L }).sample(emptyList())
        assertEquals(0, sample.webMb)
        assertEquals(listOf(listOf(99L)), reader.calls)
    }

    @Test
    fun musesOwnPidIsRemovedFromTheWebList() {
        val reader = FakeReader(own = 800, web = 600)
        HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 1L }).sample(listOf(1L, 99L, 2L))
        assertEquals(listOf(listOf(99L), listOf(1L, 2L)), reader.calls)
    }

    @Test
    fun ownMemoryFallsBackToJvmHeapWhenReaderReturnsZero() {
        val reader = FakeReader(own = 0, web = 0, supported = false)
        val sample = HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 123L }).sample(emptyList())
        assertEquals(123, sample.musesMb)
        assertFalse(sample.measured)
    }

    // ---- readers and helpers ----

    @Test
    fun unsupportedReaderReturnsZeroAndSaysSo() {
        assertEquals(0L, UnsupportedMemoryReader.workingSetMb(listOf(1L, 2L)))
        assertFalse(UnsupportedMemoryReader.supported)
    }

    @Test
    fun systemReaderMatchesThePlatform() {
        assertEquals(DesktopPlatform.isWindows, MemoryReaders.forSystem().supported)
    }

    @Test
    fun windowsReaderIsHarmlessOffWindows() {
        if (DesktopPlatform.isWindows) return
        assertEquals(0L, WindowsMemoryReader().workingSetMb(listOf(1L, 2L)))
    }

    @Test
    fun windowsReaderCanReadItsOwnProcess() {
        if (!DesktopPlatform.isWindows) return
        val own = ProcessHandle.current().pid()
        val mb = WindowsMemoryReader().workingSetMb(listOf(own, own)) // duplicate PID counted once
        assertTrue(mb > 0L, "a running JVM uses more than 0 MB")
    }

    @Test
    fun bytesToMbUsesMebibytes() {
        assertEquals(0L, HubMemoryMath.bytesToMb(0))
        assertEquals(0L, HubMemoryMath.bytesToMb(-1))
        assertEquals(0L, HubMemoryMath.bytesToMb(1_048_575))
        assertEquals(1L, HubMemoryMath.bytesToMb(1_048_576))
        assertEquals(512L, HubMemoryMath.bytesToMb(512L * 1_048_576 + 5))
    }
}
