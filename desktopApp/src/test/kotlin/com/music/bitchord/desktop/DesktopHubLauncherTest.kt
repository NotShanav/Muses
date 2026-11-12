package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure parts only: process access is faked, no real browser is started. */
class DesktopHubLauncherTest {
    private val profile = Paths.get("C:/Users/test/AppData/Local/Muses/Hub/Profile")
    private val flag = "--user-data-dir=${profile.toAbsolutePath()}"
    private val brave = HubBrowser("Brave", Paths.get("C:/brave/brave.exe"))
    private val yt = HubService("youtube", "YouTube", "https://www.youtube.com")
    private val nf = HubService("netflix", "Netflix", "https://www.netflix.com")

    private class FakeProcesses : HubProcessApi {
        val procs = LinkedHashMap<Long, HubProcessInfo>()
        var closeWorks = true
        val closeRequests = mutableListOf<Long>()
        val kills = mutableListOf<Long>()

        fun add(pid: Int, parent: Int?, cmd: String?, start: Long? = null) {
            procs[pid.toLong()] = HubProcessInfo(pid.toLong(), parent?.toLong(), cmd, start)
        }

        private fun removeWithDescendants(pid: Long) {
            val kids = procs.values.filter { it.parentPid == pid }.map { it.pid }
            kids.forEach { removeWithDescendants(it) }
            procs.remove(pid)
        }

        override fun snapshot(): List<HubProcessInfo> = procs.values.toList()

        override fun requestClose(pid: Long) {
            closeRequests += pid
            if (closeWorks) removeWithDescendants(pid)
        }

        override fun forceKill(pid: Long) {
            kills += pid
            procs.remove(pid)
        }

        /** The browser vanishes without Muses asking (user closed the window, crash). */
        fun browserGone() = removeWithDescendants(100)
    }

    private class FakeProc(override val pid: Long, private val alive: Boolean, private val code: Int? = null) : HubStartedProcess {
        override fun isAlive() = alive
        override fun exitValue(): Int? = if (alive) null else code
    }

    /** Stays alive for [aliveFor] checks, then exits with 0: the hand-off process. */
    private class LingeringProc(override val pid: Long, private val aliveFor: Int) : HubStartedProcess {
        private var calls = 0
        override fun isAlive() = calls++ < aliveFor
        override fun exitValue(): Int? = if (calls <= aliveFor) null else 0
    }

    private class FakeStarter(private val onStart: (List<String>) -> HubStartedProcess) : HubProcessStarter {
        val commands = mutableListOf<List<String>>()
        override fun start(command: List<String>): HubStartedProcess {
            val process = onStart(command)
            commands += command
            return process
        }
    }

    private class FakeCloser(override val supported: Boolean, private val result: Boolean) : HubWindowCloser {
        val closed = mutableListOf<String>()
        override fun closeWindowFor(service: HubService): Boolean {
            closed += service.id
            return result
        }
    }

    private fun baseProcesses() = FakeProcesses().apply {
        add(1, 0, "java -jar muses.jar")
        add(999, 1, "notepad.exe")
    }

    /** A fresh browser: main process 100 with a readable child 101 and an unreadable child 102. */
    private fun FakeProcesses.startBrowser(command: List<String>) {
        add(100, 1, command.joinToString(" "))
        add(101, 100, "brave.exe --type=renderer $flag")
        add(102, 100, null)
    }

    private fun launcher(
        procs: FakeProcesses,
        starter: HubProcessStarter,
        closer: HubWindowCloser = HubNoWindowCloser,
        found: HubBrowser? = brave,
    ) = HubLauncher(
        findBrowser = { found },
        profileDir = profile,
        processes = procs,
        starter = starter,
        windowCloser = closer,
        createProfileDir = {},
        ownPid = 1,
        startTimeoutMs = 100,
        closeTimeoutMs = 30,
        pollMs = 1,
    )

    /** First launch starts the browser; later ones are handed off to it and exit with 0. */
    private fun sharedStarter(procs: FakeProcesses) = FakeStarter { command ->
        if (procs.procs.containsKey(100L)) {
            FakeProc(300, alive = false, code = 0)
        } else {
            procs.startBrowser(command)
            FakeProc(100, alive = true)
        }
    }

    // ---- launching -------------------------------------------------------------------------

    @Test
    fun freshLaunchStartsTheBrowserAndTracksItsTree() = runBlocking {
        val procs = baseProcesses()
        val starter = sharedStarter(procs)
        val l = launcher(procs, starter)
        val result = l.launch(yt)
        assertEquals(HubLaunchStatus.STARTED, result.status)
        assertTrue(result.ok)
        val tree = listOf(100L, 101L, 102L)
        assertEquals(tree, result.pids)
        assertEquals(tree, l.treePids(yt))
        assertEquals(tree, l.profileTreePids())
        assertEquals(setOf("youtube"), l.runningServiceIds())
        assertEquals(listOf(HubBrowserLaunch.buildLaunchArgs(brave, profile, yt.startUrl)), starter.commands)
        assertEquals(emptyList(), l.treePids(nf))
    }

    @Test
    fun noBrowserMeansNothingIsStarted() = runBlocking {
        val procs = baseProcesses()
        val starter = sharedStarter(procs)
        val result = launcher(procs, starter, found = null).launch(yt)
        assertEquals(HubLaunchStatus.NO_BROWSER, result.status)
        assertFalse(result.ok)
        assertTrue(starter.commands.isEmpty())
    }

    @Test
    fun badUrlAndStartFailuresAreReportedNotThrown() = runBlocking {
        val procs = baseProcesses()
        val starter = sharedStarter(procs)
        val bad = HubService("x", "X", "javascript:alert(1)")
        assertEquals(HubLaunchStatus.FAILED, launcher(procs, starter).launch(bad).status)
        assertTrue(starter.commands.isEmpty())

        val throwing = FakeStarter { throw IOException("boom") }
        val result = launcher(procs, throwing).launch(yt)
        assertEquals(HubLaunchStatus.FAILED, result.status)
        assertTrue(result.message.contains("boom"))
    }

    @Test
    fun launchingAnOpenServiceAgainDoesNotOpenASecondWindow() = runBlocking {
        val procs = baseProcesses()
        val starter = sharedStarter(procs)
        val l = launcher(procs, starter)
        l.launch(yt)
        val again = l.launch(yt)
        assertEquals(HubLaunchStatus.ALREADY_RUNNING, again.status)
        assertEquals(1, starter.commands.size)
    }

    @Test
    fun handOffToARunningBrowserIsRecognised() = runBlocking {
        val procs = baseProcesses()
        procs.add(200, 1, "brave.exe $flag")
        procs.add(201, 200, "brave.exe --type=gpu-process $flag")
        val starter = FakeStarter { FakeProc(300, alive = false, code = 0) }
        val l = launcher(procs, starter)
        val result = l.launch(nf)
        assertEquals(HubLaunchStatus.HANDED_OFF, result.status)
        assertEquals(listOf(200L, 201L), result.pids)
        assertEquals(setOf("netflix"), l.runningServiceIds())
        assertTrue(procs.kills.isEmpty() && procs.closeRequests.isEmpty())
    }

    @Test
    fun handOffProcessThatLingersForAMomentIsWaitedFor() = runBlocking {
        val procs = baseProcesses()
        procs.add(200, 1, "brave.exe $flag")
        val starter = FakeStarter { LingeringProc(300, aliveFor = 3) }
        val result = launcher(procs, starter).launch(nf)
        assertEquals(HubLaunchStatus.HANDED_OFF, result.status)
    }

    @Test
    fun browserThatExitsWithoutAWindowIsAFailure() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, FakeStarter { FakeProc(300, alive = false, code = 1) })
        val result = l.launch(yt)
        assertEquals(HubLaunchStatus.FAILED, result.status)
        assertTrue(l.runningServiceIds().isEmpty())
    }

    @Test
    fun refusedHandOffIsAFailure() = runBlocking {
        val procs = baseProcesses()
        procs.add(200, 1, "brave.exe $flag")
        val l = launcher(procs, FakeStarter { FakeProc(300, alive = false, code = 1) })
        assertEquals(HubLaunchStatus.FAILED, l.launch(yt).status)
        assertTrue(l.runningServiceIds().isEmpty())
    }

    // ---- the tree --------------------------------------------------------------------------

    @Test
    fun commandLineMatchingIsExactToTheProfile() {
        assertTrue(HubProcessTree.matchesProfile("brave.exe $flag --foo", flag))
        assertTrue(HubProcessTree.matchesProfile("brave.exe --foo $flag", flag))
        assertTrue(HubProcessTree.matchesProfile("\"$flag\" --foo", flag))
        assertTrue(HubProcessTree.matchesProfile(flag.uppercase(), flag))
        assertTrue(HubProcessTree.matchesProfile(flag.replace('\\', '/'), flag))
        assertFalse(HubProcessTree.matchesProfile("brave.exe ${flag}2 --foo", flag))
        assertFalse(HubProcessTree.matchesProfile("brave.exe --user-data-dir=C:/other", flag))
        assertFalse(HubProcessTree.matchesProfile(null, flag))
        assertFalse(HubProcessTree.matchesProfile("  ", flag))

        val spaced = "--user-data-dir=C:\\Users\\a b\\Muses\\Hub\\Profile"
        assertTrue(HubProcessTree.matchesProfile("chrome.exe --user-data-dir=\"C:\\Users\\a b\\Muses\\Hub\\Profile\" --x", spaced))
        assertTrue(HubProcessTree.matchesProfile("chrome.exe \"$spaced\" --x", spaced))
        assertFalse(HubProcessTree.matchesProfile("chrome.exe --user-data-dir=\"C:\\Users\\a b\\Muses\\Hub\\Profile2\" --x", spaced))
    }

    @Test
    fun treeHoldsProfileProcessesTrackedRootsAndDescendantsOnly() {
        val snap = listOf(
            HubProcessInfo(1, 0, "java muses", null),
            HubProcessInfo(50, 1, "brave.exe $flag", 1),
            HubProcessInfo(51, 50, "brave.exe --type=renderer $flag", 1),
            HubProcessInfo(52, 51, null, 1),
            HubProcessInfo(60, 1, "notepad.exe", 1),
            HubProcessInfo(70, 1, "brave.exe --user-data-dir=C:/Other", 1),
            HubProcessInfo(71, 70, "brave.exe --type=gpu --user-data-dir=C:/Other", 1),
            HubProcessInfo(80, 1, null, 5),
            HubProcessInfo(81, 80, null, 5),
            HubProcessInfo(90, 1, null, 9),
        )
        val tracked = listOf(HubTrackedRoot(80, 5), HubTrackedRoot(90, 5), HubTrackedRoot(404, null))
        assertEquals(listOf(50L, 51L, 52L, 80L, 81L), HubProcessTree.compute(snap, flag, tracked, ownPid = 1))
        assertEquals(listOf(50L, 80L), HubProcessTree.roots(snap, listOf(50L, 51L, 52L, 80L, 81L)))
    }

    @Test
    fun musesAndItsAncestorsAreNeverInTheTree() {
        val snap = listOf(
            HubProcessInfo(5, 0, "launcher.exe $flag", null),
            HubProcessInfo(1, 5, "java muses $flag", null),
            HubProcessInfo(60, 1, "notepad.exe", null),
            HubProcessInfo(50, 1, "brave.exe $flag", null),
        )
        assertEquals(listOf(50L), HubProcessTree.compute(snap, flag, listOf(HubTrackedRoot(1, null)), ownPid = 1))
    }

    @Test
    fun unreadableCommandLinesStillTrackTheProcessWeStarted() = runBlocking {
        val procs = baseProcesses()
        val starter = FakeStarter {
            procs.add(100, 1, null, start = 7) // Windows hid the command line
            procs.add(101, 100, null, start = 7)
            FakeProc(100, alive = true)
        }
        val l = launcher(procs, starter)
        assertEquals(HubLaunchStatus.STARTED, l.launch(yt).status)
        assertEquals(listOf(100L, 101L), l.profileTreePids())
    }

    // ---- closing ---------------------------------------------------------------------------

    @Test
    fun soleServiceClosesGracefullyAndOnlyItsRoots() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, sharedStarter(procs))
        l.launch(yt)
        val result = l.close(yt)
        assertEquals(HubCloseStatus.CLOSED, result.status)
        assertEquals(listOf(100L), procs.closeRequests)
        assertTrue(procs.kills.isEmpty())
        assertTrue(l.runningServiceIds().isEmpty())
        assertTrue(procs.procs.keys.containsAll(listOf(1L, 999L)))
    }

    @Test
    fun stubbornBrowserIsKilledAfterTheTimeoutButNothingElseIs() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, sharedStarter(procs))
        l.launch(yt)
        procs.closeWorks = false
        val result = l.close(yt)
        assertEquals(HubCloseStatus.CLOSED_FORCED, result.status)
        assertEquals(setOf(100L, 101L, 102L), procs.kills.toSet())
        assertFalse(procs.kills.contains(1L) || procs.kills.contains(999L))
        assertTrue(procs.procs.keys.containsAll(listOf(1L, 999L)))
    }

    @Test
    fun closingOneOfTwoServicesIsRefusedWithoutAWindowCloser() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, sharedStarter(procs))
        l.launch(yt)
        l.launch(nf)
        assertFalse(l.canCloseSingleWindow)
        val result = l.close(yt)
        assertEquals(HubCloseStatus.SHARED_BROWSER_STILL_RUNNING, result.status)
        assertFalse(result.ok)
        assertTrue(procs.closeRequests.isEmpty() && procs.kills.isEmpty())
        assertEquals(setOf("youtube", "netflix"), l.runningServiceIds())
    }

    @Test
    fun windowCloserClosesOneServiceWithoutTouchingProcesses() = runBlocking {
        val procs = baseProcesses()
        val closer = FakeCloser(supported = true, result = true)
        val l = launcher(procs, sharedStarter(procs), closer)
        l.launch(yt)
        l.launch(nf)
        assertTrue(l.canCloseSingleWindow)
        assertEquals(HubCloseStatus.CLOSED, l.close(yt).status)
        assertEquals(listOf("youtube"), closer.closed)
        assertEquals(setOf("netflix"), l.runningServiceIds())
        assertTrue(procs.closeRequests.isEmpty() && procs.kills.isEmpty())
    }

    @Test
    fun failingWindowCloserLeavesBothServicesOpen() = runBlocking {
        val procs = baseProcesses()
        val closer = FakeCloser(supported = true, result = false)
        val l = launcher(procs, sharedStarter(procs), closer)
        l.launch(yt)
        l.launch(nf)
        assertEquals(HubCloseStatus.SHARED_BROWSER_STILL_RUNNING, l.close(yt).status)
        assertEquals(listOf("youtube"), closer.closed)
        assertEquals(setOf("youtube", "netflix"), l.runningServiceIds())
        assertTrue(procs.closeRequests.isEmpty() && procs.kills.isEmpty())
    }

    @Test
    fun closeAllClosesTheWholeTreeOnce() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, sharedStarter(procs))
        l.launch(yt)
        l.launch(nf)
        assertEquals(HubCloseStatus.CLOSED, l.closeAll().status)
        assertEquals(listOf(100L), procs.closeRequests)
        assertTrue(l.runningServiceIds().isEmpty())
        assertEquals(HubCloseStatus.NOT_RUNNING, l.closeAll().status)
    }

    @Test
    fun closingAServiceThatIsNotOpenDoesNothing() = runBlocking {
        val procs = baseProcesses()
        val l = launcher(procs, sharedStarter(procs))
        assertEquals(HubCloseStatus.NOT_RUNNING, l.close(yt).status)
        assertTrue(procs.closeRequests.isEmpty() && procs.kills.isEmpty())
    }

    @Test
    fun browserClosedByHandIsNoticedAndRelaunchWorks() = runBlocking {
        val procs = baseProcesses()
        val starter = sharedStarter(procs)
        val l = launcher(procs, starter)
        l.launch(yt)
        procs.browserGone()
        assertTrue(l.runningServiceIds().isEmpty())
        assertTrue(l.treePids(yt).isEmpty())
        assertEquals(HubCloseStatus.NOT_RUNNING, l.close(yt).status)
        assertEquals(HubLaunchStatus.STARTED, l.launch(yt).status)
        assertEquals(2, starter.commands.size)
        assertNull(procs.kills.firstOrNull())
    }
}
