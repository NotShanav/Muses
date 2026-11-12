package com.music.bitchord.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything is faked: no browser, no processes, no real memory reads, no saved settings. */
class DesktopHubControllerTest {
    private class FakePort : HubLauncherPort {
        var canClose = false
        val running = LinkedHashSet<String>()
        val log = mutableListOf<String>()
        var launchStatus = HubLaunchStatus.STARTED
        var closeStatus = HubCloseStatus.CLOSED
        var browser: String? = "Brave"

        override val canCloseSingleWindow: Boolean
            get() = canClose

        override suspend fun launch(service: HubService): HubLaunchResult {
            log += "launch:${service.id}"
            val result = HubLaunchResult(launchStatus)
            if (result.ok) running += service.id
            return result
        }

        override suspend fun close(service: HubService): HubCloseResult {
            log += "close:${service.id}"
            // Like the real launcher: with several services open and no window closer, nothing can be closed.
            if (!canClose && running.size > 1) return HubCloseResult(HubCloseStatus.SHARED_BROWSER_STILL_RUNNING)
            val result = HubCloseResult(closeStatus)
            if (result.ok) running -= service.id
            return result
        }

        override suspend fun closeAll(): HubCloseResult {
            log += "closeAll"
            running.clear()
            return HubCloseResult(HubCloseStatus.CLOSED)
        }

        override fun profileTreePids(): List<Long> = listOf(1L, 2L)

        override fun runningServiceIds(): Set<String> = running.toSet()

        override fun browserName(): String? = browser
    }

    private class FakeReader : MemoryReader {
        var web = 0L

        override fun workingSetMb(pids: List<Long>): Long = if (pids == listOf(99L)) 500L else web
    }

    private class FakeStore : HubKeyValueStore {
        val data = HashMap<String, String>()
        var writes = 0

        override fun read(key: String): String? = data[key]

        override fun write(key: String, value: String) {
            data[key] = value
            writes++
        }
    }

    private class Rig(
        maxLive: Int = 1,
        canClose: Boolean = false,
        hook: (() -> Unit)? = null,
        autoPoll: Boolean = false,
        pollMs: Long = 3_000L,
    ) {
        val port = FakePort().also { it.canClose = canClose }
        val reader = FakeReader()
        val store = FakeStore()
        var clock = 1_000L
        var activated = 0
        val controller = HubController(
            launcher = port,
            sampler = HubMemorySampler(reader, ownPid = 99L, jvmHeapMb = { 1L }),
            governor = MemoryGovernor(),
            registryStore = HubRegistryStore(store),
            onServiceActivated = hook ?: { activated++; Unit },
            pollIntervalMs = pollMs,
            autoPoll = autoPoll,
            now = { clock },
        )

        init {
            if (maxLive != 1) {
                runBlocking { controller.updateSettings(HubSettings(maxLiveServices = maxLive)) }
            }
        }

        val state: HubControllerState get() = controller.state.value

        fun stateOf(id: String): HubServiceState? = state.services.firstOrNull { it.service.id == id }?.state
    }

    // ---- activate ----

    @Test
    fun initialStateListsAllServicesHibernated() {
        val rig = Rig()
        assertEquals(5, rig.state.services.size)
        assertTrue(rig.state.services.all { it.state == HubServiceState.HIBERNATED })
        assertNull(rig.state.activeId)
        assertNull(rig.state.sample)
        assertFalse(rig.state.canCloseSingleWindow)
    }

    @Test
    fun activateLaunchesAndMarksActive() = runBlocking<Unit> {
        val rig = Rig()
        assertTrue(rig.controller.activate("youtube"))
        assertEquals(listOf("launch:youtube"), rig.port.log)
        assertEquals("youtube", rig.state.activeId)
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("netflix"))
        assertEquals("Brave", rig.state.browserName)
        assertEquals(1, rig.activated)
        assertNull(rig.state.message)
    }

    @Test
    fun switchingWithMaxOneClosesTheOpenServiceFirst() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        assertEquals(listOf("launch:youtube", "close:youtube", "launch:netflix"), rig.port.log)
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("netflix"))
        assertEquals(1, rig.state.openCount)
    }

    @Test
    fun maxTwoWithSingleWindowCloseKeepsTheFirstLive() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        assertEquals(listOf("launch:youtube", "launch:netflix"), rig.port.log)
        assertEquals(HubServiceState.LIVE, rig.stateOf("youtube"))
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("netflix"))
    }

    @Test
    fun maxLiveIsIgnoredWhileSingleWindowCloseIsNotAvailable() = runBlocking<Unit> {
        val rig = Rig(maxLive = 3, canClose = false)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        assertEquals(1, rig.state.openCount)
        assertEquals("netflix", rig.state.activeId)
    }

    @Test
    fun overTheLimitTheLeastRecentlyUsedOpenServiceIsClosed() = runBlocking<Unit> {
        val rig = Rig(maxLive = 3, canClose = true)
        rig.clock = 1_000
        rig.controller.activate("youtube")
        rig.clock = 2_000
        rig.controller.activate("netflix")
        rig.clock = 3_000
        rig.controller.activate("prime-video")
        rig.clock = 4_000
        rig.controller.activate("jiohotstar")
        // youtube stopped being active at 2000, netflix and prime-video later
        assertTrue("close:youtube" in rig.port.log)
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertEquals(HubServiceState.LIVE, rig.stateOf("netflix"))
        assertEquals(HubServiceState.LIVE, rig.stateOf("prime-video"))
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("jiohotstar"))
    }

    @Test
    fun activatingTheActiveServiceAgainDoesNothing() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        assertTrue(rig.controller.activate("youtube"))
        assertEquals(listOf("launch:youtube"), rig.port.log)
        assertEquals(1, rig.activated)
        assertTrue(rig.state.message.orEmpty().contains("already open"))
    }

    @Test
    fun activatingALiveServiceSwitchesWithoutLaunching() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        rig.controller.activate("youtube")
        assertEquals(listOf("launch:youtube", "launch:netflix"), rig.port.log)
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("youtube"))
        assertEquals(HubServiceState.LIVE, rig.stateOf("netflix"))
        assertEquals(3, rig.activated)
    }

    @Test
    fun failedLaunchReportsAndDoesNotNotify() = runBlocking<Unit> {
        val rig = Rig()
        rig.port.launchStatus = HubLaunchStatus.NO_BROWSER
        assertFalse(rig.controller.activate("youtube"))
        assertNull(rig.state.activeId)
        assertEquals(0, rig.activated)
        assertTrue(rig.state.message.orEmpty().contains("Brave"))
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
    }

    @Test
    fun failedLaunchAfterClosingThePreviousLeavesItClosed() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.port.launchStatus = HubLaunchStatus.FAILED
        assertFalse(rig.controller.activate("netflix"))
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertNull(rig.state.activeId)
        assertEquals(1, rig.activated)
    }

    @Test
    fun failedCloseOfTheVictimAbortsTheLaunch() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.port.closeStatus = HubCloseStatus.FAILED
        assertFalse(rig.controller.activate("netflix"))
        assertFalse("launch:netflix" in rig.port.log)
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("youtube"))
        assertTrue(rig.state.message.orEmpty().contains("YouTube"))
    }

    @Test
    fun unknownAndDisabledServicesAreRefused() = runBlocking<Unit> {
        val rig = Rig()
        assertFalse(rig.controller.activate("nope"))
        assertNotNull(rig.state.message)
        assertTrue(rig.controller.editRegistry { it.setEnabled("youtube", false) }.ok)
        assertFalse(rig.controller.activate("youtube"))
        assertTrue(rig.port.log.isEmpty())
    }

    @Test
    fun aThrowingHookDoesNotBreakActivation() = runBlocking<Unit> {
        val rig = Rig(hook = { throw IllegalStateException("boom") })
        assertTrue(rig.controller.activate("youtube"))
        assertEquals("youtube", rig.state.activeId)
    }

    // ---- hibernate / close all ----

    @Test
    fun hibernatingTheOnlyOpenServiceClosesItAndStopsMeasuring() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.controller.pollOnce()
        assertNotNull(rig.state.sample)
        assertTrue(rig.controller.hibernate("youtube"))
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertTrue(rig.port.running.isEmpty())
        assertNull(rig.state.sample)
    }

    @Test
    fun hibernatingAClosedServiceIsANoOp() = runBlocking<Unit> {
        val rig = Rig()
        assertTrue(rig.controller.hibernate("youtube"))
        assertTrue(rig.port.log.isEmpty())
    }

    @Test
    fun hibernateRefusedWhenOthersAreOpenAndNoSingleWindowClose() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        rig.port.canClose = false
        assertFalse(rig.controller.hibernate("youtube"))
        assertEquals(HubServiceState.LIVE, rig.stateOf("youtube"))
        assertTrue(rig.state.message.orEmpty().contains("Close all"))
    }

    @Test
    fun closeAllClosesEverything() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        assertTrue(rig.controller.closeAll())
        assertTrue(rig.state.services.all { it.state == HubServiceState.HIBERNATED })
        assertTrue("closeAll" in rig.port.log)
    }

    @Test
    fun aBrowserClosedByHandIsNoticedOnThePoll() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.port.running.clear()
        assertFalse(rig.controller.pollOnce())
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertNull(rig.state.sample)
    }

    // ---- memory ----

    @Test
    fun pollStoresTheSampleAsMusesPlusWeb() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.reader.web = 700
        assertTrue(rig.controller.pollOnce())
        assertEquals(1200L, rig.state.sample?.totalMb)
        assertEquals(MemoryLevel.NORMAL, rig.state.memoryLevel)
        assertNull(rig.state.message)
    }

    @Test
    fun pollWithNothingOpenReturnsFalse() = runBlocking<Unit> {
        val rig = Rig()
        assertFalse(rig.controller.pollOnce())
        assertNull(rig.state.sample)
    }

    @Test
    fun highMemoryShowsAWarning() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.reader.web = 1200 // total 1700 > 1600
        rig.controller.pollOnce()
        assertEquals(MemoryLevel.WARNING, rig.state.memoryLevel)
        assertTrue(rig.state.message.orEmpty().contains("getting high"))
    }

    @Test
    fun overTheCapClosesTheLeastRecentlyUsedBackgroundService() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        rig.reader.web = 2000 // total 2500 > 2048
        assertTrue(rig.controller.pollOnce())
        assertEquals(HubServiceState.HIBERNATED, rig.stateOf("youtube"))
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("netflix"))
        assertEquals("Closed YouTube to free memory.", rig.state.message)
    }

    @Test
    fun theActiveServiceIsNeverClosedByTheGovernor() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.reader.web = 3000
        assertTrue(rig.controller.pollOnce())
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("youtube"))
        assertEquals(MemoryLevel.OVER_CAP, rig.state.memoryLevel)
        assertFalse(rig.port.log.any { it.startsWith("close") })
        assertTrue(rig.state.message.orEmpty().contains("over the limit"))
    }

    @Test
    fun withoutSingleWindowCloseTheGovernorOnlyWarns() = runBlocking<Unit> {
        val rig = Rig(maxLive = 2, canClose = true)
        rig.controller.activate("youtube")
        rig.controller.activate("netflix")
        rig.port.canClose = false
        rig.reader.web = 2000
        rig.controller.pollOnce()
        assertEquals(HubServiceState.LIVE, rig.stateOf("youtube"))
        assertFalse(rig.port.log.any { it.startsWith("close") })
    }

    // ---- registry ----

    @Test
    fun addingAServiceIsSavedAndShown() = runBlocking<Unit> {
        val rig = Rig()
        val writesBefore = rig.store.writes
        val result = rig.controller.editRegistry { it.add("Plex", "https://app.plex.tv") }
        assertTrue(result.ok)
        assertEquals(6, rig.state.services.size)
        assertEquals(writesBefore + 1, rig.store.writes)
    }

    @Test
    fun anInvalidEditIsRefusedAndNotSaved() = runBlocking<Unit> {
        val rig = Rig()
        val result = rig.controller.editRegistry { it.add("Bad", "ftp://example.com") }
        assertFalse(result.ok)
        assertEquals(HubError.INVALID_URL, result.error)
        assertEquals(0, rig.store.writes)
        assertEquals(5, rig.state.services.size)
    }

    @Test
    fun removingAnOpenServiceClosesItFirst() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        assertTrue(rig.controller.editRegistry { it.remove("youtube") }.ok)
        assertTrue("close:youtube" in rig.port.log)
        assertEquals(4, rig.state.services.size)
        assertNull(rig.state.activeId)
    }

    @Test
    fun removalIsAbortedWhenTheOpenServiceCannotBeClosed() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.port.closeStatus = HubCloseStatus.FAILED
        assertFalse(rig.controller.editRegistry { it.remove("youtube") }.ok)
        assertEquals(5, rig.state.services.size)
        assertEquals(HubServiceState.ACTIVE, rig.stateOf("youtube"))
    }

    @Test
    fun settingsAreUpdatedAndClamped() = runBlocking<Unit> {
        val rig = Rig()
        val result = rig.controller.updateSettings(HubSettings(memoryWarnMb = 1000, memoryCapMb = 1500, maxLiveServices = 99))
        assertTrue(result.ok)
        assertEquals(1000, rig.state.settings.memoryWarnMb)
        assertEquals(HubLimits.MAX_LIVE, rig.state.settings.maxLiveServices)
    }

    // ---- misc ----

    @Test
    fun refreshBrowserAndClearMessage() = runBlocking<Unit> {
        val rig = Rig()
        rig.port.browser = "Edge"
        rig.controller.refreshBrowser()
        assertEquals("Edge", rig.state.browserName)
        rig.controller.activate("nope")
        assertNotNull(rig.state.message)
        rig.controller.clearMessage()
        assertNull(rig.state.message)
    }

    @Test
    fun shutdownClosesBrowsersAndStopsEverything() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.controller.shutdown()
        assertTrue("closeAll" in rig.port.log)
        assertTrue(rig.state.services.all { it.state == HubServiceState.HIBERNATED })
        assertFalse(rig.controller.activate("netflix"))
        rig.controller.shutdown() // second call is harmless
        assertEquals(1, rig.port.log.count { it == "closeAll" })
    }

    @Test
    fun shutdownCanLeaveTheBrowserOpen() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.activate("youtube")
        rig.controller.shutdown(closeBrowsers = false)
        assertFalse("closeAll" in rig.port.log)
    }

    @Test
    fun shutdownWithoutEverLaunchingTouchesNoProcesses() = runBlocking<Unit> {
        val rig = Rig()
        rig.controller.shutdown()
        assertTrue(rig.port.log.isEmpty())
    }

    @Test
    fun pollLoopRunsWhileAServiceIsOpenAndStopsAfterClose() = runBlocking<Unit> {
        val rig = Rig(autoPoll = true, pollMs = 10L)
        rig.reader.web = 300
        rig.controller.activate("youtube")
        var waited = 0
        while (rig.state.sample == null && waited < 2_000) {
            delay(20)
            waited += 20
        }
        assertEquals(800L, rig.state.sample?.totalMb)
        rig.controller.closeAll()
        delay(100)
        assertNull(rig.state.sample)
        rig.controller.shutdown()
    }

    // ---- pure messages ----

    @Test
    fun launchFailureMessages() {
        val yt = HubService("youtube", "YouTube", "https://www.youtube.com")
        assertTrue(HubMessages.launchFailure(yt, HubLaunchResult(HubLaunchStatus.NO_BROWSER)).contains("Brave"))
        val failed = HubMessages.launchFailure(yt, HubLaunchResult(HubLaunchStatus.FAILED, "exit code 1"))
        assertTrue(failed.contains("YouTube"))
        assertTrue(failed.contains("exit code 1"))
    }

    @Test
    fun closeFailureAndMemoryMessages() {
        val yt = HubService("youtube", "YouTube", "https://www.youtube.com")
        assertTrue(HubMessages.closeFailure(yt, HubCloseResult(HubCloseStatus.SHARED_BROWSER_STILL_RUNNING)).contains("Close all"))
        assertEquals("Could not close YouTube.", HubMessages.closeFailure(yt, HubCloseResult(HubCloseStatus.FAILED)))
        val s = HubSettings()
        assertTrue(HubMessages.memoryWarning(1700, s, MemoryLevel.WARNING).contains("1700"))
        assertTrue(HubMessages.memoryWarning(2100, s, MemoryLevel.OVER_CAP).contains("2048"))
    }
}
