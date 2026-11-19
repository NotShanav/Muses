package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * BUDS B3: back-off, messages and the controller state machine against fakes (device list, transport).
 * Real time but tiny delays; no Bluetooth, window or network.
 */
class DesktopBudsControllerTest {

    // ── pure parts ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun backoffScheduleIs8_16_30_then60() {
        assertEquals(8_000L, BudsControllerLogic.backoffMs(0))
        assertEquals(8_000L, BudsControllerLogic.backoffMs(1))
        assertEquals(16_000L, BudsControllerLogic.backoffMs(2))
        assertEquals(30_000L, BudsControllerLogic.backoffMs(3))
        assertEquals(60_000L, BudsControllerLogic.backoffMs(4))
        assertEquals(60_000L, BudsControllerLogic.backoffMs(50))
    }

    @Test
    fun messagesByCode() {
        assertEquals(
            "Another app is controlling these earbuds. Close it and try again.",
            BudsControllerLogic.messageFor(BudsErrorCodes.ALREADY_IN_USE),
        )
        assertTrue(BudsControllerLogic.messageFor(BudsErrorCodes.TIMEOUT).contains("QuickBuds"))
        assertTrue(BudsControllerLogic.messageFor(BudsErrorCodes.NOT_CONNECTED).contains("HeyMelody"))
        assertTrue(BudsControllerLogic.messageFor(BudsProblemCodes.MULTIPLE).contains("More than one"))
        assertEquals("Could not open the earbuds control channel.", BudsControllerLogic.messageFor(BudsErrorCodes.UNKNOWN))
        assertEquals("Could not open the earbuds control channel.", BudsControllerLogic.messageFor("???"))
    }

    @Test
    fun statusLineByState() {
        assertEquals("Off", BudsControllerLogic.statusLine(BudsUiState.Searching, enabled = false))
        assertEquals("Off", BudsControllerLogic.statusLine(BudsUiState.Off, enabled = true))
        assertTrue(BudsControllerLogic.statusLine(BudsUiState.Searching, enabled = true).startsWith("Looking"))
        val problem = BudsUiState.Problem("X", "Something to read")
        assertEquals("Something to read", BudsControllerLogic.statusLine(problem, enabled = true))
        val line = BudsControllerLogic.statusLine(BudsUiState.Connecting("My Secret Buds"), enabled = true)
        assertFalse(line.contains("Secret"))
    }

    // ── fakes ───────────────────────────────────────────────────────────────────────────────────

    /** Never sends anything; [read] parks until [drop] or [close], then reports closed (-1). */
    private class FakeTransport : BudsTransport {
        private val gone = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        val writes = AtomicInteger(0)

        override suspend fun write(bytes: ByteArray) {
            writes.incrementAndGet()
        }

        override suspend fun read(buffer: ByteArray): Int {
            gone.await()
            return -1
        }

        fun drop() {
            gone.complete(Unit)
        }

        override fun close() {
            closed.set(true)
            gone.complete(Unit)
        }
    }

    private val device = BudsDevice("Test Buds", 0x112233445566L)

    private class Rig(
        val scope: CoroutineScope,
        val devices: () -> List<BudsDevice>,
        val opens: AtomicInteger,
        val waited: AtomicLong,
        val controller: DesktopBudsController,
    )

    private fun rig(
        devices: () -> List<BudsDevice>,
        open: (Int) -> Result<BudsTransport>,
        auto: () -> Boolean = { true },
        now: () -> Long = { 0L },
    ): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger(0)
        val waited = AtomicLong(0)
        val controller = DesktopBudsController(
            scope = scope,
            listDevices = { devices() },
            openTransport = { opens.incrementAndGet().let(open) },
            sessionFactory = { transport, sessionScope, log ->
                BudsSession(transport, sessionScope, delayFn = { ms -> delay(if (ms >= 60_000L) 60_000L else 1L) }, debugLog = log)
            },
            autoConnect = auto,
            debugFrames = { false },
            delayFn = { ms ->
                waited.addAndGet(ms)
                delay(5)
            },
            nowMs = now,
        )
        return Rig(scope, devices, opens, waited, controller)
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for: $what")
            delay(5)
        }
    }

    // ── state machine ───────────────────────────────────────────────────────────────────────────

    @Test
    fun stopWithoutStartIsOffAndHarmless() {
        val r = rig(devices = { emptyList() }, open = { Result.failure(BudsConnectException("UNKNOWN", "x")) })
        r.controller.stop()
        r.controller.stop()
        assertIs<BudsUiState.Off>(r.controller.ui.value)
        assertEquals(0, r.opens.get())
        r.scope.cancel()
    }

    @Test
    fun noCompatibleBudsShowsSearchingAndOpensNothing() = runBlocking {
        val r = rig(devices = { emptyList() }, open = { Result.failure(BudsConnectException("UNKNOWN", "x")) })
        r.controller.start()
        assertIs<BudsUiState.Searching>(r.controller.ui.value)
        awaitUntil("some polling") { r.waited.get() >= 2_000L }
        assertIs<BudsUiState.Searching>(r.controller.ui.value)
        assertEquals(0, r.opens.get())
        r.controller.stop()
        assertIs<BudsUiState.Off>(r.controller.ui.value)
        r.scope.cancel()
    }

    @Test
    fun twoCandidatesIsAProblemAndNothingIsOpened() = runBlocking {
        val other = BudsDevice("Other Buds", 0x665544332211L)
        val r = rig(devices = { listOf(device, other) }, open = { Result.failure(BudsConnectException("UNKNOWN", "x")) })
        r.controller.start()
        awaitUntil("multiple problem") { (r.controller.ui.value as? BudsUiState.Problem)?.code == BudsProblemCodes.MULTIPLE }
        assertEquals(0, r.opens.get())
        r.controller.stop()
        r.scope.cancel()
    }

    @Test
    fun failuresBackOffThenConnect() = runBlocking {
        val transport = FakeTransport()
        val r = rig(
            devices = { listOf(device) },
            open = { n ->
                if (n <= 2) Result.failure(BudsConnectException(BudsErrorCodes.ALREADY_IN_USE, "busy")) else Result.success(transport)
            },
        )
        r.controller.start()
        awaitUntil("first problem") { (r.controller.ui.value as? BudsUiState.Problem)?.code == BudsErrorCodes.ALREADY_IN_USE }
        val problem = r.controller.ui.value as BudsUiState.Problem
        assertEquals("Another app is controlling these earbuds. Close it and try again.", problem.message)
        awaitUntil("connected") { r.controller.ui.value is BudsUiState.Connected }
        // two failures: waits of 8 s and 16 s, nothing else was waited
        assertEquals(24_000L, r.waited.get())
        assertEquals(3, r.opens.get())
        r.controller.stop()
        assertTrue(transport.closed.get())
        assertIs<BudsUiState.Off>(r.controller.ui.value)
        r.scope.cancel()
    }

    @Test
    fun droppedLinkGoesBackToSearchingAndRetriesWhenTheDeviceReturns() = runBlocking {
        val first = FakeTransport()
        val second = FakeTransport()
        val present = AtomicBoolean(true)
        val clock = AtomicLong(0)
        val r = rig(
            devices = { if (present.get()) listOf(device) else emptyList() },
            open = { n -> Result.success(if (n == 1) first else second) },
            now = { clock.get() },
        )
        r.controller.start()
        awaitUntil("connected") { r.controller.ui.value is BudsUiState.Connected }
        // the lid closes: the buds leave the connected list and the socket drops, after a stable stretch
        present.set(false)
        clock.set(30_000L)
        first.drop()
        awaitUntil("searching again") { r.controller.ui.value is BudsUiState.Searching }
        assertTrue(first.closed.get())
        assertEquals(1, r.opens.get())
        // the buds come back
        present.set(true)
        awaitUntil("reconnected") { r.controller.ui.value is BudsUiState.Connected && r.opens.get() == 2 }
        r.controller.stop()
        assertTrue(second.closed.get())
        r.scope.cancel()
    }

    @Test
    fun autoConnectOffWaitsForConnectNow() = runBlocking {
        val transport = FakeTransport()
        val r = rig(devices = { listOf(device) }, open = { Result.success(transport) }, auto = { false })
        r.controller.start()
        awaitUntil("found") { (r.controller.ui.value as? BudsUiState.Problem)?.code == BudsProblemCodes.FOUND }
        assertEquals(0, r.opens.get())
        r.controller.connectNow()
        awaitUntil("connected") { r.controller.ui.value is BudsUiState.Connected }
        assertEquals(1, r.opens.get())
        r.controller.stop()
        r.scope.cancel()
    }

    @Test
    fun startTwiceIsSingleFlight() = runBlocking {
        val transport = FakeTransport()
        val r = rig(devices = { listOf(device) }, open = { Result.success(transport) })
        r.controller.start()
        r.controller.start()
        awaitUntil("connected") { r.controller.ui.value is BudsUiState.Connected }
        delay(100)
        assertEquals(1, r.opens.get())
        r.controller.stop()
        r.scope.cancel()
    }

    @Test
    fun aFailingDeviceListNeverThrows() = runBlocking {
        val calls = AtomicInteger(0)
        val r = rig(
            devices = {
                calls.incrementAndGet()
                throw IllegalStateException("native failure")
            },
            open = { Result.failure(BudsConnectException("UNKNOWN", "x")) },
        )
        r.controller.start()
        awaitUntil("polled twice") { calls.get() >= 2 }
        assertIs<BudsUiState.Searching>(r.controller.ui.value)
        r.controller.stop()
        r.scope.cancel()
    }
}
