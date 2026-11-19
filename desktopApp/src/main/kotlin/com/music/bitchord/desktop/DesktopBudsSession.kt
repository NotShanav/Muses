package com.music.bitchord.desktop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/*
 * BUDS B1: one earbuds link over an abstract transport. Pure Kotlin: no Windows, no Compose, no network.
 * B2 supplies the RFCOMM transport; B3 owns the lifecycle.
 */

/** Everything the UI needs. [inEar] is (left, right) or null when no wear message was seen. */
internal data class BudsState(
    val connected: Boolean,
    val productId: String?,
    val battery: BudsBattery,
    val inEar: Pair<Boolean, Boolean>?,
    val noiseMode: BudsNoiseMode?,
    val supportedNoiseModes: Set<BudsNoiseMode>,
    val lowLatency: Boolean?,
    val lowLatencySupported: Boolean,
) {
    companion object {
        val INITIAL = BudsState(
            connected = false,
            productId = null,
            battery = BudsBattery.EMPTY,
            inEar = null,
            noiseMode = null,
            supportedNoiseModes = emptySet(),
            lowLatency = null,
            lowLatencySupported = false,
        )
    }
}

/** A byte pipe to the buds. [read] returns the byte count, 0 for "nothing yet", -1 once closed. */
internal interface BudsTransport : AutoCloseable {
    suspend fun write(bytes: ByteArray)
    suspend fun read(buffer: ByteArray): Int
}

/**
 * Runs the init sequence, keeps [state] from pushes and replies, polls every 60 s, and offers two writes that
 * return true only when the buds confirm the new state by reading it back.
 *
 * [delayFn] and [nowMs] are injectable so tests run in virtual time. [debugLog] stays null unless the
 * owner turns the debug switch on; it then receives hex of control frames only (never names or addresses).
 */
internal class BudsSession(
    private val transport: BudsTransport,
    scope: CoroutineScope,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val debugLog: ((String) -> Unit)? = null,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)

    private val _state = MutableStateFlow(BudsState.INITIAL)
    val state: StateFlow<BudsState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val seqCounter = AtomicInteger(BudsIds.SEQ_FIRST - 1)
    private val writeMutex = Mutex()
    private val opMutex = Mutex()

    /** Last reply seen per reply command (matched by command, not seq: some models answer with their own seq). */
    private val replies = ConcurrentHashMap<Int, BudsFrame>()

    @Volatile private var offeredCodes: Set<Int>? = null
    @Volatile private var featureValues: Map<Int, Int> = emptyMap()
    @Volatile private var observedModes: Set<BudsNoiseMode> = emptySet()
    private val modeObservations = AtomicInteger(0)
    private val featureObservations = AtomicInteger(0)

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        _state.update { it.copy(connected = true) }
        sessionScope.launch { readLoop() }
        sessionScope.launch {
            runInit()
            pollLoop()
        }
    }

    /** Idempotent. Stops the work of this session only (the caller's scope stays alive) and closes the transport. */
    fun close() {
        shutdown()
    }

    suspend fun setNoiseMode(mode: BudsNoiseMode): Boolean = opMutex.withLock { doSetNoiseMode(mode) }

    suspend fun setLowLatency(on: Boolean): Boolean = opMutex.withLock { doSetLowLatency(on) }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────

    private fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        _state.update { it.copy(connected = false) }
        job.cancel()
        try {
            transport.close()
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun isOpen(): Boolean = !closed.get()

    // ── reading ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun readLoop() {
        val buffer = ByteArray(1024)
        val parser = BudsFrameParser()
        try {
            while (currentCoroutineContext().isActive && isOpen()) {
                val n = transport.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    yield()
                    continue
                }
                for (frame in parser.feed(buffer, 0, n)) handleFrame(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a transport failure ends the link the same way a close does
        } finally {
            shutdown()
        }
    }

    private fun handleFrame(f: BudsFrame) {
        logFrame("RX", f.cmd, f.seq, f.payload)
        if (f.isReply) replies[f.cmd] = f
        when (f.cmd) {
            BudsIds.CMD_PUSH -> handlePush(f.payload)
            BudsIds.CMD_FEATURE_PUSH -> BudsParsers.featureValues(f.payload)?.let { applyFeatures(it) }
            BudsIds.CMD_PRODUCT_ID or BudsIds.REPLY_BIT ->
                BudsParsers.productId(f.payload)?.let { id -> _state.update { it.copy(productId = id) } }
            BudsIds.CMD_BROADCAST_CODES or BudsIds.REPLY_BIT -> {
                BudsParsers.broadcastCodes(f.payload)?.let { offeredCodes = it }
                recomputeSupported()
            }
            BudsIds.CMD_BATTERY or BudsIds.REPLY_BIT -> BudsParsers.battery(f.payload)?.let { applyBattery(it) }
            BudsIds.CMD_WEARING or BudsIds.REPLY_BIT -> BudsParsers.wearing(f.payload)?.let { applyWearing(it) }
            BudsIds.CMD_ANC_QUERY or BudsIds.REPLY_BIT ->
                BudsParsers.ancReplyMask(f.payload)?.let { BudsParsers.decodeNoiseMask(it) }?.let { applyMode(it) }
            BudsIds.CMD_FEATURE_STATUS or BudsIds.REPLY_BIT -> {
                if (BudsParsers.ackStatus(f.payload) == 0) BudsParsers.featureValues(f.payload)?.let { applyFeatures(it) }
            }
            BudsIds.CMD_REQ_TIME, BudsIds.CMD_REQ_APP_LIST, BudsIds.CMD_REQ_CAPABILITIES, BudsIds.CMD_REQ_SPATIAL ->
                answerRequest(f)
            else -> Unit
        }
    }

    private fun handlePush(p: ByteArray) {
        if (p.isEmpty()) return
        when (p[0].toInt() and 0xFF) {
            BudsIds.EVENT_BATTERY -> BudsParsers.battery(p.copyOfRange(1, p.size))?.let { applyBattery(it) }
            BudsIds.EVENT_WEARING -> BudsParsers.wearing(p.copyOfRange(1, p.size))?.let { applyWearing(it) }
            BudsIds.EVENT_ANC ->
                BudsParsers.ancNotifyMask(p)?.let { BudsParsers.decodeNoiseMask(it) }?.let { applyMode(it) }
            BudsIds.EVENT_GAME_MODE -> BudsParsers.gameModePush(p)?.let { state ->
                val id = lowLatencyFeatureId() ?: BudsIds.FEATURE_GAME_MODE_LEGACY
                applyFeatures(mapOf(id to state))
            }
            else -> Unit
        }
    }

    /** Requests from the buds some firmwares wait for (PROTOCOL.md section 11); no other push is answered. */
    private fun answerRequest(f: BudsFrame) {
        val payload = when (f.cmd) {
            BudsIds.CMD_REQ_TIME -> {
                val s = (nowMs() / 1000L).toInt()
                byteArrayOf(0, s.toByte(), (s shr 8).toByte(), (s shr 16).toByte(), (s shr 24).toByte())
            }
            BudsIds.CMD_REQ_APP_LIST -> byteArrayOf(0, 1, 0)
            BudsIds.CMD_REQ_CAPABILITIES -> byteArrayOf(0, 0, 0)
            else -> byteArrayOf(1)
        }
        val bytes = BudsFrames.reply(f.cmd, f.seq, payload)
        sessionScope.launch { writeBytes(bytes, f.cmd or BudsIds.REPLY_BIT, f.seq, payload) }
    }

    // ── state updates ────────────────────────────────────────────────────────────────────────────

    private fun applyBattery(update: BudsBattery) {
        _state.update { it.copy(battery = it.battery.mergedWith(update)) }
    }

    private fun applyWearing(w: BudsWearing) {
        _state.update { it.copy(inEar = w.inEar) }
    }

    private fun applyMode(mode: BudsNoiseMode) {
        observedModes = observedModes + mode
        modeObservations.incrementAndGet()
        _state.update { it.copy(noiseMode = mode) }
        recomputeSupported()
    }

    /** Noise control is offered when the firmware offers event 03 or a mode has been seen; Adaptive only once seen. */
    private fun recomputeSupported() {
        val ancOffered = offeredCodes?.contains(BudsIds.EVENT_ANC) == true || observedModes.isNotEmpty()
        val modes = if (ancOffered) BASE_MODES + observedModes else emptySet()
        _state.update { if (it.supportedNoiseModes == modes) it else it.copy(supportedNoiseModes = modes) }
    }

    private fun applyFeatures(values: Map<Int, Int>) {
        featureValues = featureValues + values
        featureObservations.incrementAndGet()
        val id = lowLatencyFeatureId()
        _state.update {
            it.copy(lowLatencySupported = id != null, lowLatency = id?.let { i -> (featureValues[i] ?: 0) != 0 })
        }
    }

    /** Game mode lives under 0x28 on newer devices, else 0x06 (section 16). */
    private fun lowLatencyFeatureId(): Int? = when {
        featureValues.containsKey(BudsIds.FEATURE_GAME_MODE_NEW) -> BudsIds.FEATURE_GAME_MODE_NEW
        featureValues.containsKey(BudsIds.FEATURE_GAME_MODE_LEGACY) -> BudsIds.FEATURE_GAME_MODE_LEGACY
        else -> null
    }

    // ── init and poll ────────────────────────────────────────────────────────────────────────────

    private suspend fun runInit() {
        delayFn(INIT_FIRST_DELAY_MS)
        send(BudsIds.CMD_HANDSHAKE, ByteArray(0))
        delayFn(INIT_STEP_MS)
        send(BudsIds.CMD_PRODUCT_ID, ByteArray(0))
        delayFn(INIT_STEP_MS)
        send(BudsIds.CMD_BROADCAST_CODES, ByteArray(0))
        delayFn(INIT_STEP_MS)
        awaitCondition(BROADCAST_WAIT_MS) { offeredCodes != null }
        val ids = registerIds(offeredCodes)
        send(BudsIds.CMD_REGISTER, ByteArray(ids.size + 1) { if (it == 0) ids.size.toByte() else ids[it - 1].toByte() })
        delayFn(INIT_STEP_MS)
        sendStatusQuery()
        delayFn(INIT_STEP_MS)
        send(BudsIds.CMD_ANC_QUERY, byteArrayOf(1, 1))
        delayFn(INIT_STEP_MS)
        send(BudsIds.CMD_BATTERY, ByteArray(0))
        delayFn(INIT_STEP_MS)
        send(BudsIds.CMD_WEARING, ByteArray(0))
    }

    private suspend fun pollLoop() {
        while (isOpen()) {
            delayFn(POLL_INTERVAL_MS)
            if (!isOpen()) return
            send(BudsIds.CMD_BATTERY, ByteArray(0))     // wearing is pushed, never polled
            delayFn(INIT_STEP_MS)
            sendStatusQuery()
        }
    }

    private suspend fun sendStatusQuery() {
        val ids = BudsIds.STATUS_FEATURE_IDS
        send(BudsIds.CMD_FEATURE_STATUS, ByteArray(ids.size + 1) { if (it == 0) ids.size.toByte() else ids[it - 1].toByte() })
    }

    /** Events to subscribe to: battery, wearing, ANC (and game mode when offered), only those the buds offer. */
    private fun registerIds(offered: Set<Int>?): List<Int> {
        val wanted = listOf(BudsIds.EVENT_BATTERY, BudsIds.EVENT_WEARING, BudsIds.EVENT_ANC, BudsIds.EVENT_GAME_MODE)
        if (offered.isNullOrEmpty()) return wanted.take(3)
        val ids = wanted.filter { it in offered }
        return ids.ifEmpty { wanted.take(3) }
    }

    // ── writes the owner asks for ────────────────────────────────────────────────────────────────

    private suspend fun doSetNoiseMode(mode: BudsNoiseMode): Boolean {
        if (!isOpen()) return false
        val observedBefore = modeObservations.get()
        replies.remove(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT)
        if (!send(BudsIds.CMD_SET_ANC, BudsFrames.setAncPayload(mode))) return false
        if (!awaitAccepted(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT, ACK_WAIT_MS)) return false
        // Read the real state back: an ack of 00 means "accepted", not "applied".
        if (!send(BudsIds.CMD_ANC_QUERY, byteArrayOf(1, 1))) return false
        return awaitCondition(READBACK_WAIT_MS) {
            modeObservations.get() > observedBefore && _state.value.noiseMode == mode
        }
    }

    private suspend fun doSetLowLatency(on: Boolean): Boolean {
        if (!isOpen()) return false
        val id = lowLatencyFeatureId() ?: return false
        val observedBefore = featureObservations.get()
        replies.remove(BudsIds.CMD_FEATURE_SWITCH or BudsIds.REPLY_BIT)
        if (!send(BudsIds.CMD_FEATURE_SWITCH, byteArrayOf(id.toByte(), (if (on) 1 else 0).toByte()))) return false
        if (!awaitAccepted(BudsIds.CMD_FEATURE_SWITCH or BudsIds.REPLY_BIT, ACK_WAIT_MS)) return false
        // The buds apply a game-mode change about 1 s after the ack, so a read sooner returns the old value:
        // give a push time to confirm, then read.
        val confirmed = { featureObservations.get() > observedBefore && _state.value.lowLatency == on }
        if (awaitCondition(PUSH_WAIT_MS, confirmed)) return true
        if (!send(BudsIds.CMD_FEATURE_STATUS, byteArrayOf(1, id.toByte()))) return false
        return awaitCondition(READ_AFTER_WAIT_MS, confirmed)
    }

    private suspend fun awaitAccepted(replyCmd: Int, timeoutMs: Long): Boolean {
        if (!awaitCondition(timeoutMs) { replies.containsKey(replyCmd) }) return false
        return BudsParsers.ackStatus(replies[replyCmd]?.payload ?: return false) == 0
    }

    /** Polls [cond] with the injected delay (virtual-time friendly). False on timeout or when the link closed. */
    private suspend fun awaitCondition(timeoutMs: Long, cond: () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (cond()) return true
            if (!isOpen() || waited >= timeoutMs) return false
            delayFn(POLL_STEP_MS)
            waited += POLL_STEP_MS
        }
    }

    // ── sending ──────────────────────────────────────────────────────────────────────────────────

    private fun nextSeq(): Int {
        while (true) {
            val cur = seqCounter.get()
            val next = if (cur >= BudsIds.SEQ_LAST) BudsIds.SEQ_FIRST else cur + 1
            if (seqCounter.compareAndSet(cur, next)) return next
        }
    }

    private suspend fun send(cmd: Int, payload: ByteArray): Boolean {
        val seq = nextSeq()
        return writeBytes(BudsFrames.build(cmd, seq, payload), cmd, seq, payload)
    }

    private suspend fun writeBytes(bytes: ByteArray, cmd: Int, seq: Int, payload: ByteArray): Boolean {
        if (!isOpen()) return false
        logFrame("TX", cmd, seq, payload)
        return try {
            writeMutex.withLock { transport.write(bytes) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            shutdown()
            false
        }
    }

    private fun logFrame(direction: String, cmd: Int, seq: Int, payload: ByteArray) {
        val log = debugLog ?: return
        if (!isControlFrame(cmd, payload)) return
        log("$direction ${BudsHex.of(BudsFrames.build(cmd, seq, payload))}")
    }

    private fun isControlFrame(cmd: Int, payload: ByteArray): Boolean {
        val base = cmd and 0x7FFF
        return when (base) {
            BudsIds.CMD_FEATURE_SWITCH, BudsIds.CMD_SET_ANC, BudsIds.CMD_ANC_QUERY,
            BudsIds.CMD_FEATURE_STATUS, BudsIds.CMD_FEATURE_PUSH -> true
            BudsIds.CMD_PUSH -> payload.firstOrNull()?.toInt()?.let { it == BudsIds.EVENT_ANC || it == BudsIds.EVENT_GAME_MODE } == true
            else -> false
        }
    }

    private companion object {
        val BASE_MODES = setOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY)

        const val INIT_FIRST_DELAY_MS = 300L
        const val INIT_STEP_MS = 200L
        const val BROADCAST_WAIT_MS = 600L
        const val POLL_INTERVAL_MS = 60_000L
        const val POLL_STEP_MS = 25L
        const val ACK_WAIT_MS = 800L
        const val READBACK_WAIT_MS = 1_200L
        const val PUSH_WAIT_MS = 900L
        const val READ_AFTER_WAIT_MS = 600L
    }
}
