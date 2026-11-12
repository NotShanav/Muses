package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/*
 * Muses Hub launcher (H3).
 *
 * THE HAND-OFF PROBLEM: every Hub service uses the same browser profile. When that profile's
 * browser is already running, `browser --app=<url>` does not start a new browser: the new process
 * passes the command line to the running one and exits within a moment. The process we started is
 * then not the one that owns the window, and its PID says nothing about the window's memory.
 *
 * HOW IT IS SOLVED: the "tree" is not "descendants of the PID we started". It is every process
 * whose command line carries this profile's `--user-data-dir=<profile>` flag, plus all their
 * descendants, plus (as a fallback when command lines cannot be read) the roots we started that are
 * still the same process. The browser, its renderers, GPU and utility processes all carry the flag,
 * so a hand-off, a browser that was left running by an earlier Muses run, and a browser we started
 * are all found the same way. Muses itself and its ancestors are never part of the tree.
 *
 * WHAT IS NOT SOLVED (see HANDOFF.md): all live services share ONE browser process tree, so memory
 * and closing cannot be attributed to a single service, and one service's window cannot be closed
 * on its own without a window-level closer (see [HubWindowCloser]).
 */

internal enum class HubLaunchStatus { STARTED, HANDED_OFF, ALREADY_RUNNING, NO_BROWSER, FAILED }

internal data class HubLaunchResult(
    val status: HubLaunchStatus,
    val message: String = "",
    /** The profile tree after launching (shared by every live service). */
    val pids: List<Long> = emptyList(),
) {
    val ok: Boolean
        get() = status == HubLaunchStatus.STARTED ||
            status == HubLaunchStatus.HANDED_OFF ||
            status == HubLaunchStatus.ALREADY_RUNNING
}

internal enum class HubCloseStatus { CLOSED, CLOSED_FORCED, NOT_RUNNING, SHARED_BROWSER_STILL_RUNNING, FAILED }

internal data class HubCloseResult(val status: HubCloseStatus, val message: String = "") {
    val ok: Boolean
        get() = status == HubCloseStatus.CLOSED ||
            status == HubCloseStatus.CLOSED_FORCED ||
            status == HubCloseStatus.NOT_RUNNING
}

/** One process as seen in a snapshot of the machine. */
internal data class HubProcessInfo(
    val pid: Long,
    val parentPid: Long?,
    val commandLine: String?,
    val startMillis: Long?,
)

/** A process we started. Kept so the tree can be found even if command lines are unreadable. */
internal data class HubTrackedRoot(val pid: Long, val startMillis: Long?)

/** Everything that touches real processes. Fake it in tests. */
internal interface HubProcessApi {
    /** All visible processes. */
    fun snapshot(): List<HubProcessInfo>

    /** Politely asks the process to close (Windows: taskkill without /F; elsewhere SIGTERM). */
    fun requestClose(pid: Long)

    /** Kills the process. Only ever called with PIDs from the computed Hub tree. */
    fun forceKill(pid: Long)
}

internal interface HubStartedProcess {
    val pid: Long
    fun isAlive(): Boolean

    /** Exit code, or null while the process is still running. */
    fun exitValue(): Int?
}

internal fun interface HubProcessStarter {
    fun start(command: List<String>): HubStartedProcess
}

/**
 * Closes ONE service window without touching the others. No implementation exists yet; a Windows
 * one (find the window, post WM_CLOSE) is a follow-up. Until [supported] is true, closing a service
 * while another is live is refused instead of guessed.
 */
internal interface HubWindowCloser {
    val supported: Boolean
    fun closeWindowFor(service: HubService): Boolean
}

internal object HubNoWindowCloser : HubWindowCloser {
    override val supported: Boolean = false
    override fun closeWindowFor(service: HubService): Boolean = false
}

/** Pure tree logic over a process snapshot. */
internal object HubProcessTree {
    /** Quotes are dropped: Windows quotes the whole argument, Chromium quotes only the value. */
    private fun norm(text: String) = text.replace("\"", "").replace('/', '\\').lowercase()

    /** True when [commandLine] holds [profileFlag] as a whole argument (not a longer folder name). */
    fun matchesProfile(commandLine: String?, profileFlag: String): Boolean {
        if (commandLine.isNullOrBlank()) return false
        val hay = norm(commandLine)
        val needle = norm(profileFlag)
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) return false
            val end = at + needle.length
            if (end >= hay.length || hay[end] == ' ') return true
            from = at + 1
        }
    }

    private fun ancestry(pid: Long, byPid: Map<Long, HubProcessInfo>): Set<Long> {
        val out = LinkedHashSet<Long>()
        var current: Long? = pid
        while (current != null && out.add(current)) current = byPid[current]?.parentPid
        return out
    }

    /** Sorted PIDs of the Hub tree: profile-flag processes, tracked roots, and all their descendants. */
    fun compute(
        snapshot: List<HubProcessInfo>,
        profileFlag: String,
        tracked: Collection<HubTrackedRoot>,
        ownPid: Long,
    ): List<Long> {
        val byPid = snapshot.associateBy { it.pid }
        val protectedPids = ancestry(ownPid, byPid)
        val seeds = LinkedHashSet<Long>()
        for (p in snapshot) if (matchesProfile(p.commandLine, profileFlag)) seeds += p.pid
        for (t in tracked) {
            val p = byPid[t.pid] ?: continue
            val sameProcess = t.startMillis == null || p.startMillis == null || t.startMillis == p.startMillis
            if (sameProcess) seeds += t.pid
        }
        val children = snapshot
            .mapNotNull { p -> p.parentPid?.let { parent -> parent to p.pid } }
            .groupBy({ it.first }, { it.second })
        val result = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        for (seed in seeds) if (seed !in protectedPids && result.add(seed)) queue.addLast(seed)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            for (child in children[pid].orEmpty()) {
                if (child !in protectedPids && result.add(child)) queue.addLast(child)
            }
        }
        return result.sorted()
    }

    /** Tree members whose parent is not itself in the tree: the browser's top-level processes. */
    fun roots(snapshot: List<HubProcessInfo>, tree: List<Long>): List<Long> {
        val members = tree.toSet()
        val byPid = snapshot.associateBy { it.pid }
        return tree.filter { pid -> byPid[pid]?.parentPid !in members }
    }
}

private fun <T> java.util.Optional<T>.orNull(): T? = if (isPresent) get() else null

/** Real processes via JDK 21 [ProcessHandle]. Compiles everywhere; Windows specifics are guarded. */
internal object HubSystemProcesses : HubProcessApi {
    override fun snapshot(): List<HubProcessInfo> =
        ProcessHandle.allProcesses()
            .map { handle ->
                val info = handle.info()
                HubProcessInfo(
                    pid = handle.pid(),
                    parentPid = handle.parent().orNull()?.pid(),
                    commandLine = info.commandLine().orNull(),
                    startMillis = info.startInstant().orNull()?.toEpochMilli(),
                )
            }
            .collect(java.util.stream.Collectors.toList())

    override fun requestClose(pid: Long) {
        if (DesktopPlatform.isWindows) {
            // Java's destroy() is a hard kill on Windows; taskkill without /F asks the windows to close.
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
        } else {
            ProcessHandle.of(pid).orNull()?.destroy()
        }
    }

    override fun forceKill(pid: Long) {
        ProcessHandle.of(pid).orNull()?.destroyForcibly()
    }
}

internal object HubSystemProcessStarter : HubProcessStarter {
    override fun start(command: List<String>): HubStartedProcess {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return object : HubStartedProcess {
            override val pid: Long = process.pid()
            override fun isAlive(): Boolean = process.isAlive
            override fun exitValue(): Int? = if (process.isAlive) null else process.exitValue()
        }
    }
}

/**
 * Starts and stops Hub browser windows. Blocking work runs on [Dispatchers.IO]; launch/close calls
 * are serialised. [treePids] and [profileTreePids] take a process snapshot, so call them off the UI thread.
 */
internal class HubLauncher(
    private val findBrowser: () -> HubBrowser?,
    private val profileDir: Path,
    private val processes: HubProcessApi = HubSystemProcesses,
    private val starter: HubProcessStarter = HubSystemProcessStarter,
    private val windowCloser: HubWindowCloser = HubNoWindowCloser,
    private val createProfileDir: (Path) -> Unit = { Files.createDirectories(it) },
    private val ownPid: Long = ProcessHandle.current().pid(),
    private val startTimeoutMs: Long = 8_000,
    private val closeTimeoutMs: Long = 5_000,
    private val pollMs: Long = 200,
) {
    /** Must match the flag [HubBrowserLaunch.buildLaunchArgs] passes. */
    private val profileFlag = "--user-data-dir=${profileDir.toAbsolutePath()}"
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val tracked = ConcurrentHashMap<Long, HubTrackedRoot>()

    /** False until a [HubWindowCloser] exists: then only one service may be live at a time. */
    val canCloseSingleWindow: Boolean get() = windowCloser.supported

    /** Services launched by this launcher that are still believed to be open. */
    fun runningServiceIds(): Set<String> {
        treeNow()
        return sessions.keys.toSet()
    }

    /**
     * The shared Hub tree. Use this ONCE for memory totals: every live service lives in it, so
     * summing [treePids] over several services would count the same processes repeatedly.
     */
    fun profileTreePids(): List<Long> = treeNow()

    /** The shared tree when [service] is open, else empty. Not a per-service split (see file header). */
    fun treePids(service: HubService): List<Long> {
        val tree = treeNow()
        return if (sessions.containsKey(service.id) && tree.isNotEmpty()) tree else emptyList()
    }

    suspend fun launch(service: HubService): HubLaunchResult =
        withContext(Dispatchers.IO) { lock.withLock { launchLocked(service) } }

    suspend fun close(service: HubService): HubCloseResult =
        withContext(Dispatchers.IO) { lock.withLock { closeLocked(service) } }

    /** Closes the whole Hub browser tree and forgets every session. */
    suspend fun closeAll(): HubCloseResult =
        withContext(Dispatchers.IO) { lock.withLock { closeProfileTree() } }

    private fun treeNow(extra: List<HubTrackedRoot> = emptyList()): List<Long> {
        val snapshot = processes.snapshot()
        val tree = HubProcessTree.compute(snapshot, profileFlag, tracked.values + extra, ownPid)
        val alive = snapshot.mapTo(HashSet()) { it.pid }
        tracked.keys.filter { it !in alive }.forEach { tracked.remove(it) }
        if (tree.isEmpty() && extra.isEmpty()) {
            tracked.clear()
            sessions.clear() // the browser is gone (closed by hand or crashed)
        }
        return tree
    }

    private suspend fun launchLocked(service: HubService): HubLaunchResult {
        val before = treeNow()
        if (sessions.containsKey(service.id)) {
            return HubLaunchResult(HubLaunchStatus.ALREADY_RUNNING, "Already open.", before)
        }
        val browser = findBrowser()
            ?: return HubLaunchResult(HubLaunchStatus.NO_BROWSER, "No Brave, Chrome or Edge found.")
        val url = HubUrls.normalize(service.startUrl)
            ?: return HubLaunchResult(HubLaunchStatus.FAILED, "The start URL is not a valid http/https address.")
        runCatching { createProfileDir(profileDir) }.onFailure {
            return HubLaunchResult(HubLaunchStatus.FAILED, "Could not create the Hub profile folder: ${it.message}")
        }
        val command = HubBrowserLaunch.buildLaunchArgs(browser, profileDir, url)
        val process = try {
            starter.start(command)
        } catch (e: Exception) {
            return HubLaunchResult(HubLaunchStatus.FAILED, "Could not start ${browser.name}: ${e.message}")
        }
        val tentative = HubTrackedRoot(process.pid, null)
        val deadline = System.nanoTime() + startTimeoutMs * 1_000_000
        while (true) {
            val alive = process.isAlive()
            val tree = treeNow(listOf(tentative))
            if (alive && before.isEmpty() && tree.isNotEmpty()) {
                // Fresh browser: remember the process (and when it started) in case command lines are unreadable.
                val start = processes.snapshot().firstOrNull { it.pid == process.pid }?.startMillis
                tracked[process.pid] = HubTrackedRoot(process.pid, start)
                sessions[service.id] = System.currentTimeMillis()
                return HubLaunchResult(HubLaunchStatus.STARTED, "Opened in ${browser.name}.", tree)
            }
            if (!alive) {
                val code = process.exitValue()
                return when {
                    tree.isEmpty() -> HubLaunchResult(
                        HubLaunchStatus.FAILED,
                        "${browser.name} exited (code $code) without opening a window.",
                    )
                    before.isNotEmpty() && code == 0 -> {
                        // Hand-off: the running browser opened the window and our process exited.
                        sessions[service.id] = System.currentTimeMillis()
                        HubLaunchResult(HubLaunchStatus.HANDED_OFF, "Opened in the running ${browser.name}.", tree)
                    }
                    before.isEmpty() -> {
                        // Our process exited but a browser is up under the profile (launcher stub).
                        sessions[service.id] = System.currentTimeMillis()
                        HubLaunchResult(HubLaunchStatus.STARTED, "Opened in ${browser.name}.", tree)
                    }
                    else -> HubLaunchResult(
                        HubLaunchStatus.FAILED,
                        "${browser.name} refused the request (code $code).",
                        tree,
                    )
                }
            }
            if (System.nanoTime() >= deadline) {
                return if (tree.isNotEmpty()) {
                    sessions[service.id] = System.currentTimeMillis()
                    HubLaunchResult(HubLaunchStatus.STARTED, "Started; window not confirmed.", tree)
                } else {
                    HubLaunchResult(HubLaunchStatus.FAILED, "${browser.name} did not start in time.")
                }
            }
            delay(pollMs)
        }
    }

    private suspend fun closeLocked(service: HubService): HubCloseResult {
        treeNow()
        if (!sessions.containsKey(service.id)) return HubCloseResult(HubCloseStatus.NOT_RUNNING)
        val others = sessions.keys - service.id
        if (others.isEmpty()) return closeProfileTree()
        // Other services share this browser process: only a window-level close is safe.
        if (windowCloser.supported && windowCloser.closeWindowFor(service)) {
            sessions.remove(service.id)
            return HubCloseResult(HubCloseStatus.CLOSED, "Closed the ${service.name} window.")
        }
        return HubCloseResult(
            HubCloseStatus.SHARED_BROWSER_STILL_RUNNING,
            "${service.name} shares one browser with other open services; its window was left open.",
        )
    }

    private suspend fun closeProfileTree(): HubCloseResult {
        val first = processes.snapshot()
        val tree = HubProcessTree.compute(first, profileFlag, tracked.values, ownPid)
        if (tree.isEmpty()) {
            forgetAll()
            return HubCloseResult(HubCloseStatus.NOT_RUNNING)
        }
        for (root in HubProcessTree.roots(first, tree)) runCatching { processes.requestClose(root) }
        if (waitUntilGone(closeTimeoutMs)) {
            forgetAll()
            return HubCloseResult(HubCloseStatus.CLOSED)
        }
        // Still alive after the grace period: kill what is left, from a fresh tree, never anything else.
        val remaining = HubProcessTree.compute(processes.snapshot(), profileFlag, tracked.values, ownPid)
        for (pid in remaining) runCatching { processes.forceKill(pid) }
        if (waitUntilGone(closeTimeoutMs)) {
            forgetAll()
            return HubCloseResult(HubCloseStatus.CLOSED_FORCED, "Closed after a forced stop.")
        }
        return HubCloseResult(HubCloseStatus.FAILED, "Some browser processes did not stop.")
    }

    private suspend fun waitUntilGone(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val tree = HubProcessTree.compute(processes.snapshot(), profileFlag, tracked.values, ownPid)
            if (tree.isEmpty()) return true
            if (System.nanoTime() >= deadline) return false
            delay(pollMs)
        }
    }

    private fun forgetAll() {
        sessions.clear()
        tracked.clear()
    }

    companion object {
        /** The real launcher: system processes, the user's browser preference, the Muses Hub profile folder. */
        fun forSystem(preference: () -> HubBrowserPreference): HubLauncher = HubLauncher(
            findBrowser = { HubBrowserFinder.findInstalled(preference()) },
            profileDir = HubBrowserLaunch.profileDir(HubBrowserEnv.fromSystem()),
        )
    }
}
