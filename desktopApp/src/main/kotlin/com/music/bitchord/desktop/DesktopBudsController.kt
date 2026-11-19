package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * BUDS B3: the earbuds lifecycle. While the owner has the switch on it looks (every 8 s, no Bluetooth
 * inquiry) for exactly one connected compatible pair, opens the RFCOMM link, runs a BudsSession and
 * publishes a BudsUiState. When the link drops (lid closed, buds out of range) it goes back to
 * Searching and retries with a back-off. Nothing is scanned or opened while it is stopped.
 * Nothing here logs a name or an address.
 */

/** What the UI (settings line, pill chip) shows. [Problem.code] is one of [BudsProblemCodes] or a [BudsErrorCodes] value. */
internal sealed interface BudsUiState {
    object Off : BudsUiState
    object Searching : BudsUiState
    data class Connecting(val name: String) : BudsUiState
    data class Connected(val name: String, val state: BudsState) : BudsUiState
    data class Problem(val code: String, val message: String) : BudsUiState
}

internal object BudsProblemCodes {
    /** More than one compatible pair is connected; Muses will not guess. */
    const val MULTIPLE = "MULTIPLE"

    /** A pair is connected but "Auto connect" is off; waiting for "Connect now". */
    const val FOUND = "FOUND"
}

/** The pure parts of the controller: back-off schedule and messages. */
internal object BudsControllerLogic {
    const val POLL_MS = 8_000L

    /** Link must have stayed up this long for the failure counter to start over. */
    const val STABLE_LINK_MS = 20_000L

    /** [failures] is the number of failed attempts in a row, counting the one that just failed (1 = first). */
    fun backoffMs(failures: Int): Long = when {
        failures <= 1 -> 8_000L
        failures == 2 -> 16_000L
        failures == 3 -> 30_000L
        else -> 60_000L
    }

    fun messageFor(code: String): String = when (code) {
        BudsErrorCodes.ALREADY_IN_USE -> "Another app is controlling these earbuds. Close it and try again."
        BudsErrorCodes.TIMEOUT ->
            "The earbuds did not answer. If QuickBuds or HeyMelody is open, close it and try again."
        BudsErrorCodes.NOT_CONNECTED ->
            "The earbuds control channel could not be reached. If QuickBuds or HeyMelody is open, close it and try again."
        BudsProblemCodes.MULTIPLE ->
            "More than one pair of compatible earbuds is connected. Disconnect one in Windows Bluetooth settings."
        BudsProblemCodes.FOUND -> "Compatible earbuds are connected. Press Connect now to control them."
        else -> "Could not open the earbuds control channel."
    }

    /** One line for the Settings page. Never contains the device name. */
    fun statusLine(ui: BudsUiState, enabled: Boolean): String = when {
        !enabled -> "Off"
        ui is BudsUiState.Off -> "Off"
        ui is BudsUiState.Searching -> "Looking for connected earbuds in Windows..."
        ui is BudsUiState.Connecting -> "Connecting to your earbuds..."
        ui is BudsUiState.Connected -> "Connected"
        ui is BudsUiState.Problem -> ui.message
        else -> ""
    }
}

internal class DesktopBudsController(
    scope: CoroutineScope,
    private val listDevices: suspend () -> List<BudsDevice> =
        { withContext(Dispatchers.IO) { DesktopBudsDevices.connectedCompatible() } },
    private val openTransport: suspend (BudsDevice) -> Result<BudsTransport> =
        { device -> WindowsRfcommTransport.open(device) },
    private val sessionFactory: (BudsTransport, CoroutineScope, ((String) -> Unit)?) -> BudsSession =
        { transport, sessionScope, log -> BudsSession(transport, sessionScope, debugLog = log) },
    private val autoConnect: () -> Boolean = { DesktopBudsSettings.autoConnect.value },
    private val debugFrames: () -> Boolean = { DesktopBudsSettings.debugFrames.value },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val rootJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope: CoroutineScope = CoroutineScope(scope.coroutineContext + rootJob)

    private val _ui = MutableStateFlow<BudsUiState>(BudsUiState.Off)
    val ui: StateFlow<BudsUiState> = _ui.asStateFlow()

    private val lock = Any()
    private var loop: Job? = null

    @Volatile private var session: BudsSession? = null
    private val connectNowRequested = AtomicBoolean(false)

    /** Single flight: a second call while running does nothing. */
    fun start() {
        synchronized(lock) {
            if (loop?.isActive == true) return
            _ui.value = BudsUiState.Searching
            loop = controllerScope.launch { runLoop() }
        }
    }

    /** Cancels the loop, closes the session and the socket, and shows Off. Safe to call at any time, any number of times. */
    fun stop() {
        val running: Job? = synchronized(lock) {
            val current = loop
            loop = null
            current
        }
        running?.cancel()
        val s = session
        session = null
        if (s != null) runCatching { s.close() }
        connectNowRequested.set(false)
        _ui.value = BudsUiState.Off
    }

    /** For "Auto connect" off: connect to the pair that was found, once. */
    fun connectNow() {
        connectNowRequested.set(true)
    }

    suspend fun setNoiseMode(m: BudsNoiseMode) {
        val s = session ?: return
        try {
            s.setNoiseMode(m)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    suspend fun setLowLatency(on: Boolean) {
        val s = session ?: return
        try {
            s.setLowLatency(on)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    // ── the loop ────────────────────────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        var failures = 0
        while (currentJobActive()) {
            val candidates = try {
                listDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            when {
                candidates.isEmpty() -> {
                    connectNowRequested.set(false)
                    publish(BudsUiState.Searching)
                    pause(BudsControllerLogic.POLL_MS)
                }
                candidates.size > 1 -> {
                    connectNowRequested.set(false)
                    publishProblem(BudsProblemCodes.MULTIPLE)
                    pause(BudsControllerLogic.POLL_MS)
                }
                else -> {
                    val device = candidates[0]
                    if (!autoConnect() && !connectNowRequested.getAndSet(false)) {
                        publishProblem(BudsProblemCodes.FOUND)
                        pause(BudsControllerLogic.POLL_MS)
                    } else {
                        connectNowRequested.set(false)
                        val lasted = connectOnce(device)
                        connectNowRequested.set(false)
                        if (lasted == null) {
                            failures++
                            pause(BudsControllerLogic.backoffMs(failures))
                        } else {
                            // The link was up and then dropped (lid closed, out of range).
                            failures = if (lasted >= BudsControllerLogic.STABLE_LINK_MS) 1 else failures + 1
                            publish(BudsUiState.Searching)
                            pause(BudsControllerLogic.backoffMs(failures))
                        }
                    }
                }
            }
        }
    }

    private suspend fun currentJobActive(): Boolean = currentCoroutineContext().isActive

    /**
     * One attempt. Returns null when the link could not be opened (a Problem is published), or how long a
     * working link lasted in milliseconds once it ended. Always releases the socket.
     */
    private suspend fun connectOnce(device: BudsDevice): Long? {
        publish(BudsUiState.Connecting(device.name))
        val opened = try {
            openTransport(device)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Result.failure<BudsTransport>(BudsConnectException(BudsErrorCodes.UNKNOWN, "open failed"))
        }
        val transport = opened.getOrNull()
        if (transport == null) {
            val code = (opened.exceptionOrNull() as? BudsConnectException)?.code ?: BudsErrorCodes.UNKNOWN
            publishProblem(code)
            return null
        }
        val logger: (String) -> Unit = { line -> System.err.println("buds: $line") }
        val log: ((String) -> Unit)? = if (debugFrames()) logger else null
        val s = try {
            sessionFactory(transport, controllerScope, log)
        } catch (_: Throwable) {
            runCatching { transport.close() }
            publishProblem(BudsErrorCodes.UNKNOWN)
            return null
        }
        session = s
        val startedAt = nowMs()
        val ended = CompletableDeferred<Unit>()
        try {
            s.start()
            val watcher = controllerScope.launch {
                s.state.collect { st ->
                    if (st.connected) publish(BudsUiState.Connected(device.name, st)) else ended.complete(Unit)
                }
            }
            try {
                ended.await()
            } finally {
                watcher.cancel()
            }
        } finally {
            if (session === s) session = null
            runCatching { s.close() }
            runCatching { transport.close() }
        }
        return nowMs() - startedAt
    }

    /** Publishes only while the controller is running, so a loop that is being cancelled cannot overwrite Off. */
    private fun publish(value: BudsUiState) {
        synchronized(lock) {
            if (loop != null) _ui.value = value
        }
    }

    private fun publishProblem(code: String) {
        publish(BudsUiState.Problem(code, BudsControllerLogic.messageFor(code)))
    }

    /** Sleeps in short slices so [connectNow] is noticed quickly. */
    private suspend fun pause(totalMs: Long) {
        var left = totalMs
        while (left > 0 && currentJobActive()) {
            if (connectNowRequested.get()) return
            val slice = if (left < SLICE_MS) left else SLICE_MS
            delayFn(slice)
            left -= slice
        }
    }

    private companion object {
        const val SLICE_MS = 500L
    }
}

/**
 * The one controller of the running app. Created with the object but does nothing (no scan, no socket) until
 * [refresh] sees the switch on. Both the Settings page and (B4) the pill read [controller].ui.
 */
internal object DesktopBudsRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller: DesktopBudsController = DesktopBudsController(scope)

    /** Applies the saved switch: start when on, stop (and release the socket) when off. */
    fun refresh() {
        if (DesktopBudsSettings.enabled.value) controller.start() else controller.stop()
    }

    /** App exit: release everything. The next [refresh] can start again. */
    fun shutdown() {
        controller.stop()
    }
}
