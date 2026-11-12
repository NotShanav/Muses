package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Muses Hub controller (H5): glues registry + launcher + memory sampler + governor.
 *
 * What it owns: which services are open (LIVE / ACTIVE), the memory poll, the registry (loaded and
 * saved through HubRegistryStore) and one StateFlow for the UI (H6).
 *
 * SHARED BROWSER (from H3/H4): all open services live in ONE browser process tree, and a single
 * window can only be closed when launcher.canCloseSingleWindow is true. So the effective maximum of
 * open services is 1 until that is true: switching = close the open service, then launch the new one.
 * Memory is one number for the whole tree; the governor can only Warn unless single-window close works.
 *
 * Everything that changes state runs under one mutex, so activate / hibernate / poll never interleave.
 * Blocking process and memory calls are moved to Dispatchers.IO.
 */

/** What the controller needs from the launcher. [HubLauncherAdapter] is the real one; tests fake it. */
internal interface HubLauncherPort {
    val canCloseSingleWindow: Boolean

    suspend fun launch(service: HubService): HubLaunchResult

    suspend fun close(service: HubService): HubCloseResult

    suspend fun closeAll(): HubCloseResult

    /** The shared Hub process tree. Blocking snapshot: call off the UI thread. */
    fun profileTreePids(): List<Long>

    /** Blocking snapshot: call off the UI thread. */
    fun runningServiceIds(): Set<String>

    /** Name of the browser that would be used ("Brave"), or null when none is installed. */
    fun browserName(): String?
}

internal class HubLauncherAdapter(
    private val launcher: HubLauncher,
    private val browserNameProvider: () -> String?,
) : HubLauncherPort {
    override val canCloseSingleWindow: Boolean
        get() = launcher.canCloseSingleWindow

    override suspend fun launch(service: HubService): HubLaunchResult = launcher.launch(service)

    override suspend fun close(service: HubService): HubCloseResult = launcher.close(service)

    override suspend fun closeAll(): HubCloseResult = launcher.closeAll()

    override fun profileTreePids(): List<Long> = launcher.profileTreePids()

    override fun runningServiceIds(): Set<String> = launcher.runningServiceIds()

    override fun browserName(): String? = browserNameProvider()

    companion object {
        fun forSystem(preference: () -> HubBrowserPreference): HubLauncherPort =
            HubLauncherAdapter(HubLauncher.forSystem(preference)) {
                HubBrowserFinder.findInstalled(preference())?.name
            }
    }
}

internal data class HubServiceView(val service: HubService, val state: HubServiceState)

/** Everything the Hub page shows. Immutable snapshot. */
internal data class HubControllerState(
    val services: List<HubServiceView> = emptyList(),
    val settings: HubSettings = HubSettings(),
    /** Latest measurement; null when no service is open (nothing is polled then). */
    val sample: HubMemorySample? = null,
    val memoryLevel: MemoryLevel = MemoryLevel.NORMAL,
    /** Browser that will be used, e.g. "Brave"; null = none found or not checked yet. */
    val browserName: String? = null,
    /** Last user-facing note or error (warning, failed launch, ...). Cleared by [HubController.clearMessage]. */
    val message: String? = null,
    val canCloseSingleWindow: Boolean = false,
) {
    val activeId: String?
        get() = services.firstOrNull { it.state == HubServiceState.ACTIVE }?.service?.id

    val openCount: Int
        get() = services.count { it.state != HubServiceState.HIBERNATED }
}

internal data class HubEditResult(
    val ok: Boolean,
    /** Set when the registry itself refused the edit (bad URL, name, limit, unknown id). */
    val error: HubError? = null,
)

/** Plain-text messages, kept pure so they can be tested. */
internal object HubMessages {
    fun launchFailure(service: HubService, result: HubLaunchResult): String {
        val base = when (result.status) {
            HubLaunchStatus.NO_BROWSER -> "No Brave, Chrome or Edge was found. Install one to use the Hub."
            else -> "Could not open ${service.name}."
        }
        return if (result.status != HubLaunchStatus.NO_BROWSER && result.message.isNotBlank()) {
            "$base ${result.message}"
        } else {
            base
        }
    }

    fun closeFailure(service: HubService, result: HubCloseResult): String =
        when (result.status) {
            HubCloseStatus.SHARED_BROWSER_STILL_RUNNING ->
                "${service.name} shares the browser with other services and cannot be closed on its own yet. Use Close all."

            else -> "Could not close ${service.name}."
        }

    fun memoryWarning(totalMb: Long, settings: HubSettings, level: MemoryLevel): String {
        val s = settings.validated()
        return if (level == MemoryLevel.OVER_CAP) {
            "Memory is over the limit: $totalMb MB of ${s.memoryCapMb} MB. Close a service."
        } else {
            "Memory is getting high: $totalMb MB (warning at ${s.memoryWarnMb} MB)."
        }
    }
}

internal class HubController(
    private val launcher: HubLauncherPort,
    private val sampler: HubMemorySampler,
    private val governor: MemoryGovernor = MemoryGovernor(),
    private val registryStore: HubRegistryStore = HubRegistryStore(),
    /** Called after a service becomes the active one (H6: pause Muses playback). Default: nothing. */
    private val onServiceActivated: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    /** Tests turn this off and call [pollOnce] themselves. */
    private val autoPoll: Boolean = true,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val opLock = Mutex()

    // Everything below is touched only while holding opLock (or during construction).
    private var registry: HubRegistry = registryStore.load()
    private val open = LinkedHashMap<String, HubServiceState>()
    private val lastActive = HashMap<String, Long>()
    private var sample: HubMemorySample? = null
    private var browserName: String? = null
    private var message: String? = null
    private var pollJob: Job? = null
    private var launchedAny = false

    @Volatile
    private var closed = false

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<HubControllerState> = _state.asStateFlow()

    // ---- public API ----

    /** Makes [id] the active service: launches it if needed, closing the least recently used first when over the limit. */
    suspend fun activate(id: String): Boolean = opLock.withLock {
        if (closed) return@withLock false
        syncOpen()
        val service = registry.find(id) ?: return@withLock fail("That service no longer exists.")
        if (!service.enabled) return@withLock fail("${service.name} is disabled.")

        when (open[id]) {
            HubServiceState.ACTIVE -> {
                message = "${service.name} is already open. Switch to its window."
                publish()
                return@withLock true
            }

            HubServiceState.LIVE -> {
                makeActive(id)
                message = "${service.name} is already open. Switch to its window."
                publish()
                notifyActivated()
                return@withLock true
            }

            else -> Unit
        }

        // Respect the maximum number of open services: close the least recently used first.
        val maxOpen = if (launcher.canCloseSingleWindow) registry.settings.validated().maxLiveServices else 1
        while (open.size >= maxOpen) {
            val victimId = leastRecentlyUsedOpenId() ?: break
            val victim = registry.find(victimId)
            if (victim != null) {
                val closeResult = launcher.close(victim)
                if (!closeResult.ok) return@withLock fail(HubMessages.closeFailure(victim, closeResult))
            }
            dropOpen(victimId)
        }

        val result = launcher.launch(service)
        if (!result.ok) return@withLock fail(HubMessages.launchFailure(service, result))
        launchedAny = true
        makeActive(id)
        browserName = readBrowserName()
        message = null
        ensurePolling()
        publish()
        notifyActivated()
        true
    }

    /** Closes [id]'s window (its state becomes HIBERNATED; id and start URL stay in the registry). */
    suspend fun hibernate(id: String): Boolean = opLock.withLock {
        if (closed) false else hibernateLocked(id)
    }

    /** Closes every Hub window and the whole Hub browser tree. */
    suspend fun closeAll(): Boolean = opLock.withLock {
        if (closed) return@withLock false
        val result = launcher.closeAll()
        if (!result.ok) return@withLock fail("Could not close all Hub windows.")
        open.clear()
        resetMemory()
        message = null
        publish()
        true
    }

    /** One memory check now (the poll loop calls this). Returns true while at least one service is open. */
    suspend fun pollOnce(): Boolean = opLock.withLock { pollLocked() }

    /** Adds, removes, renames, reorders or changes settings. Open services that disappear or get disabled are closed first. */
    suspend fun editRegistry(edit: (HubRegistry) -> HubChange): HubEditResult = opLock.withLock {
        if (closed) return@withLock HubEditResult(ok = false)
        val change = edit(registry)
        if (!change.ok) return@withLock HubEditResult(ok = false, error = change.error)
        val next = change.registry
        syncOpen()
        for (id in open.keys.toList()) {
            val stays = next.find(id)
            if (stays != null && stays.enabled) continue
            val old = registry.find(id)
            if (old != null) {
                val closeResult = launcher.close(old)
                if (!closeResult.ok) {
                    fail(HubMessages.closeFailure(old, closeResult))
                    return@withLock HubEditResult(ok = false)
                }
            }
            dropOpen(id)
        }
        registry = next
        lastActive.keys.retainAll(next.services.mapTo(HashSet()) { it.id })
        try {
            registryStore.save(next)
        } catch (e: Exception) {
            message = "Could not save the Hub settings."
        }
        publish()
        HubEditResult(ok = true)
    }

    suspend fun updateSettings(settings: HubSettings): HubEditResult =
        editRegistry { HubChange(it.updateSettings(settings)) }

    /** Re-reads which browser would be used (call when the page opens or the preference changes). */
    suspend fun refreshBrowser() {
        opLock.withLock {
            if (closed) return@withLock
            browserName = readBrowserName()
            publish()
        }
    }

    suspend fun clearMessage() {
        opLock.withLock {
            message = null
            publish()
        }
    }

    /** App exit: stops polling, optionally closes the Hub browser, cancels the scope. Safe to call twice. */
    suspend fun shutdown(closeBrowsers: Boolean = true) {
        opLock.withLock {
            if (closed) return@withLock
            closed = true
            pollJob?.cancel()
            pollJob = null
            if (closeBrowsers && (launchedAny || open.isNotEmpty())) {
                try {
                    launcher.closeAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Exiting anyway; nothing useful to do with the error.
                }
            }
            open.clear()
            resetMemory()
            publish()
        }
        scope.cancel()
    }

    /** Non-suspending last resort for app exit: stops everything but does not close the browser. */
    fun cancel() {
        closed = true
        pollJob?.cancel()
        scope.cancel()
    }

    // ---- internals (call only while holding opLock) ----

    private suspend fun hibernateLocked(id: String): Boolean {
        syncOpen()
        if (id !in open) return true
        val service = registry.find(id)
        if (service != null) {
            val result = launcher.close(service)
            if (!result.ok) return fail(HubMessages.closeFailure(service, result))
        }
        dropOpen(id)
        message = null
        publish()
        return true
    }

    private suspend fun pollLocked(): Boolean {
        if (closed) return false
        syncOpen()
        if (open.isEmpty()) {
            resetMemory()
            pollJob = null
            publish()
            return false
        }
        val measured = try {
            withContext(Dispatchers.IO) { sampler.sample(launcher.profileTreePids()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Could not read memory use."
            publish()
            return true
        }
        sample = measured
        val inputs = open.map { (id, st) -> HubGovernorService(id, st, lastActive[id] ?: 0L) }
        val settings = registry.settings
        val actions = governor.decide(measured.totalMb, inputs, settings, now(), launcher.canCloseSingleWindow)
        for (action in actions) {
            when (action) {
                MemoryAction.None -> Unit
                MemoryAction.Warn ->
                    message = HubMessages.memoryWarning(measured.totalMb, settings, governor.level)

                is MemoryAction.Hibernate -> {
                    // Never the active service, whatever the governor says.
                    if (open[action.id] == HubServiceState.LIVE && hibernateLocked(action.id)) {
                        val name = registry.find(action.id)?.name ?: action.id
                        message = "Closed $name to free memory."
                    }
                }
            }
        }
        val more = open.isNotEmpty()
        if (!more) {
            resetMemory()
            pollJob = null
        }
        publish()
        return more
    }

    /** Drops services the browser no longer shows (closed by hand). */
    private suspend fun syncOpen() {
        if (open.isEmpty()) return
        val running = try {
            withContext(Dispatchers.IO) { launcher.runningServiceIds() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val gone = open.keys.filter { it !in running }
        for (id in gone) open.remove(id)
        if (gone.isNotEmpty() && open.isEmpty()) resetMemory()
    }

    private fun makeActive(id: String) {
        val stamp = now()
        for (key in open.keys.toList()) {
            if (open[key] == HubServiceState.ACTIVE) {
                open[key] = HubServiceState.LIVE
                lastActive[key] = stamp
            }
        }
        open[id] = HubServiceState.ACTIVE
        lastActive[id] = stamp
    }

    /** Oldest `lastActive` among open services; ties go to the earlier entry. */
    private fun leastRecentlyUsedOpenId(): String? =
        open.keys.minByOrNull { lastActive[it] ?: 0L }

    private fun dropOpen(id: String) {
        open.remove(id)
        if (open.isEmpty()) resetMemory()
    }

    private fun resetMemory() {
        sample = null
        governor.reset()
    }

    private suspend fun readBrowserName(): String? =
        try {
            withContext(Dispatchers.IO) { launcher.browserName() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun ensurePolling() {
        if (!autoPoll || closed || pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                if (!pollOnce()) break
                delay(pollIntervalMs)
            }
        }
    }

    private fun notifyActivated() {
        try {
            onServiceActivated()
        } catch (e: Exception) {
            // The music hook must never break the Hub.
        }
    }

    private fun fail(text: String): Boolean {
        message = text
        publish()
        return false
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): HubControllerState = HubControllerState(
        services = registry.services.map { HubServiceView(it, open[it.id] ?: HubServiceState.HIBERNATED) },
        settings = registry.settings,
        sample = sample,
        memoryLevel = governor.level,
        browserName = browserName,
        message = message,
        canCloseSingleWindow = launcher.canCloseSingleWindow,
    )

    companion object {
        const val DEFAULT_POLL_MS: Long = 3_000L

        /** The real controller: real launcher, real memory reader, saved registry. `onServiceActivated` is H6's music hook. */
        fun forSystem(onServiceActivated: () -> Unit = {}): HubController {
            val store = HubRegistryStore()
            // The launcher reads the preference lazily, so a settings change applies to the next launch.
            lateinit var controller: HubController
            val port = HubLauncherAdapter.forSystem { controller.currentBrowserPreference() }
            controller = HubController(
                launcher = port,
                sampler = HubMemorySampler(MemoryReaders.forSystem()),
                registryStore = store,
                onServiceActivated = onServiceActivated,
            )
            return controller
        }
    }

    /** Read from the launcher thread: [_state] holds an immutable snapshot, so this is safe. */
    private fun currentBrowserPreference(): HubBrowserPreference =
        _state.value.settings.browserPreference
}
