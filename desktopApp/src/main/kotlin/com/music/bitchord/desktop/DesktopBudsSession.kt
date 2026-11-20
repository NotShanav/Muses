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
    /** Everything beyond the base popup (ANC levels, equalizer, switches, firmware). Contract: DesktopBudsContract.kt. */
    val extras: BudsExtras = BudsExtras(),
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

    /** Why the link ended, when the transport knows (debug log only). Never holds a name or an address. */
    val closeReason: String? get() = null
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

    private val startedAt: Long = nowMs()

    /** The command bitmap from the 0x0100 reply; null until it arrives (then nothing is held back). */
    @Volatile private var bitmap: BudsBitmap? = null

    @Volatile private var offeredCodes: Set<Int>? = null
    @Volatile private var profile: BudsModelProfile = BudsModelProfiles.DEFAULT
    @Volatile private var rereadJob: Job? = null

    @Volatile private var eqRequested: Int? = null
    @Volatile private var eqPicked: Int? = null
    @Volatile private var eqPickedAt: Long = 0L
    private val eqObservations = AtomicInteger(0)

    /** The id 0x010F / push 0x0504 reported last (a built-in slot or a personal preset's own id). */
    @Volatile private var eqCurrent: Int? = null

    /** The personal presets from the last 0x8122 list (empty until read, or when the model has none). */
    @Volatile private var customList: List<BudsCustomPreset> = emptyList()
    private val customObservations = AtomicInteger(0)
    private val switchRequested = ConcurrentHashMap<Int, Boolean>()
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
        shutdown("closed by the app")
    }

    suspend fun setNoiseMode(mode: BudsNoiseMode): Boolean = opMutex.withLock { doSetNoiseMode(mode) }

    suspend fun setLowLatency(on: Boolean): Boolean = opMutex.withLock { doSetLowLatency(on) }

    /** True only when the buds confirmed the level by reading the mode back. */
    suspend fun setAncLevel(level: BudsAncLevel): Boolean = opMutex.withLock { doSetAncLevel(level) }

    /**
     * Selects a built-in equalizer preset; true only when the buds confirmed it. Rapid clicks are coalesced: a call
     * that finds a newer request waiting returns false without writing, and the newest one is written.
     */
    suspend fun setEqPreset(id: Int): Boolean {
        eqRequested = id
        return opMutex.withLock {
            if (eqRequested != id) false else doSetEqPreset(id)
        }
    }

    /** Sets one of [BudsExtras.switches]; true only when the buds confirmed it. Rapid repeats per id are coalesced the same way. */
    suspend fun setSwitch(id: Int, on: Boolean): Boolean {
        switchRequested[id] = on
        return opMutex.withLock {
            if (switchRequested[id] != on) false else doSetSwitch(id, on)
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────

    private fun shutdown(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        debugLog?.invoke("END $reason (+${nowMs() - startedAt}ms)")
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
        var why = "read loop stopped"
        try {
            while (currentCoroutineContext().isActive && isOpen()) {
                val n = transport.read(buffer)
                if (n < 0) {
                    why = "link ended: " + (transport.closeReason ?: "no detail")
                    break
                }
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
            why = "read threw " + e.javaClass.simpleName
        } finally {
            shutdown(why)
        }
    }

    private fun handleFrame(f: BudsFrame) {
        logFrame("RX", f.cmd, f.seq, f.payload)
        if (f.isReply) replies[f.cmd] = f
        when (f.cmd) {
            BudsIds.CMD_PUSH -> handlePush(f)
            BudsIds.CMD_FEATURE_PUSH -> BudsParsers.featureValues(f.payload)?.let { applyFeatures(it) }
            BudsIds.CMD_HANDSHAKE or BudsIds.REPLY_BIT -> {
                BudsBitmap.parse(f.payload)?.let { bitmap = it }
                recomputeSupported()
            }
            BudsIds.CMD_PRODUCT_ID or BudsIds.REPLY_BIT ->
                BudsParsers.productId(f.payload)?.let { id ->
                    profile = BudsModelProfiles.forProduct(id)
                    _state.update { it.copy(productId = id) }
                    recomputeSupported()
                }
            BudsIds.CMD_BROADCAST_CODES or BudsIds.REPLY_BIT -> {
                BudsParsers.broadcastCodes(f.payload)?.let { offeredCodes = it }
                recomputeSupported()
            }
            BudsIds.CMD_BATTERY or BudsIds.REPLY_BIT -> BudsParsers.battery(f.payload)?.let { applyBattery(it) }
            BudsIds.CMD_WEARING or BudsIds.REPLY_BIT -> BudsParsers.wearing(f.payload)?.let { applyWearing(it) }
            BudsIds.CMD_ANC_QUERY or BudsIds.REPLY_BIT -> BudsParsers.ancReplyMask(f.payload)?.let { applyAncMask(it) }
            BudsIds.CMD_QUERY_EQ or BudsIds.REPLY_BIT -> BudsParsers.eqReply(f.payload)?.let { applyEq(it) }
            BudsIds.CMD_EQ_CHANGED -> BudsParsers.eqPush(f.payload)?.let { applyEq(it) }
            BudsIds.CMD_QUERY_EQ_ALL or BudsIds.REPLY_BIT -> BudsCustomEq.parseList(f.payload)?.let { applyCustomList(it) }
            BudsIds.CMD_FIRMWARE or BudsIds.REPLY_BIT ->
                BudsParsers.firmware(f.payload)?.let { v -> _state.update { it.copy(extras = it.extras.copy(firmware = v)) } }
            BudsIds.CMD_FEATURE_STATUS or BudsIds.REPLY_BIT -> {
                if (BudsParsers.ackStatus(f.payload) == 0) BudsParsers.featureValues(f.payload)?.let { applyFeatures(it) }
            }
            BudsIds.CMD_REQ_TIME, BudsIds.CMD_REQ_APP_LIST, BudsIds.CMD_REQ_CAPABILITIES, BudsIds.CMD_REQ_SPATIAL ->
                answerRequest(f)
            // Outside command groups 01-05 and 0F the vendor app answers "unknown request" (section 11).
            else -> if (BudsPushAnswers.isUnknownRequest(f.cmd)) replyTo(f, byteArrayOf(1))
        }
    }

    private fun handlePush(f: BudsFrame) {
        val p = f.payload
        if (p.isEmpty()) return
        when (p[0].toInt() and 0xFF) {
            // Some firmwares wait for these two answers and reset the link when none comes (section 11).
            BudsIds.EVENT_LINKED -> replyTo(f, BudsPushAnswers.linked())
            BudsIds.EVENT_DIAGNOSTIC -> replyTo(f, BudsPushAnswers.diagnostic(p.copyOfRange(1, p.size)))
            BudsIds.EVENT_BATTERY -> BudsParsers.battery(p.copyOfRange(1, p.size))?.let { applyBattery(it) }
            BudsIds.EVENT_WEARING -> BudsParsers.wearing(p.copyOfRange(1, p.size))?.let { applyWearing(it) }
            BudsIds.EVENT_ANC -> BudsParsers.ancNotifyMask(p)?.let { applyAncMask(it) }
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
        replyTo(f, payload)
    }

    /** An answer to something the buds sent: the same command with bit 15 set, the same seq. */
    private fun replyTo(f: BudsFrame, payload: ByteArray) {
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

    /**
     * A mode report (reply 0x810C or push 03). The model's own tree decides first (it knows the parent bits 0 and 2
     * as well as the report bits 3 and 8); the generic table is the fallback. A mask that names no mode is logged
     * (hex only) and changes nothing.
     */
    private fun applyAncMask(mask: Int) {
        val report = profile.decodeAnc(mask)
            ?: BudsParsers.decodeNoiseMask(mask)?.let { BudsAncReport(it) }
        if (report == null) {
            debugLog?.invoke("ANC report names no mode, mask=" + String.format("%04X", mask))
            return
        }
        applyAnc(report)
    }

    private fun applyAnc(report: BudsAncReport) {
        observedModes = observedModes + report.mode
        modeObservations.incrementAndGet()
        // A plain ANC bit names no level: the last known level stays. A level this model does not offer is not shown.
        val level = report.level?.takeIf { it in profile.ancLevels }
        _state.update {
            it.copy(noiseMode = report.mode, extras = if (level != null) it.extras.copy(ancLevel = level) else it.extras)
        }
        recomputeSupported()
    }

    /** Noise control is offered when the firmware offers event 03 or a mode has been seen; Adaptive only once seen. */
    private fun recomputeSupported() {
        // The bitmap is the firmware's own list of what it does: noise control is there when it has the ANC command,
        // even if the offered events lack 03 (Nord Buds 3 Pro offers battery and wearing only).
        val bitmapSays = bitmap?.supports(BudsIds.CMD_SET_ANC)
        val ancOffered = bitmapSays != false &&
            (offeredCodes?.contains(BudsIds.EVENT_ANC) == true || observedModes.isNotEmpty() || bitmapSays == true)
        val modes = if (ancOffered) BASE_MODES + observedModes else emptySet()
        val levels: Set<BudsAncLevel> = if (ancOffered) profile.ancLevels else emptySet()
        _state.update {
            val eq = eqNow()
            val switches = switchesNow()
            if (it.supportedNoiseModes == modes && it.extras.ancLevels == levels && it.extras.eq == eq && it.extras.switches == switches) it
            else it.copy(supportedNoiseModes = modes, extras = it.extras.copy(ancLevels = levels, eq = eq, switches = switches))
        }
    }

    private fun applyFeatures(values: Map<Int, Int>) {
        featureValues = featureValues + values
        featureObservations.incrementAndGet()
        val id = lowLatencyFeatureId()
        val switches = switchesNow()
        _state.update {
            it.copy(
                lowLatencySupported = id != null,
                lowLatency = id?.let { i -> (featureValues[i] ?: 0) != 0 },
                extras = it.extras.copy(switches = switches),
            )
        }
    }

    private fun featureOn(id: Int): Boolean? = featureValues[id]?.let { it != 0 }

    /** The model's switches in display order, only those the buds answered in a status read (a missing id is a switch they lack). */
    private fun switchesNow(): List<BudsSwitch> {
        if (!supports(BudsIds.CMD_FEATURE_SWITCH)) return emptyList()
        return profile.switches.filter { featureValues.containsKey(it.id) }.map { it.copy(on = featureOn(it.id)) }
    }

    /** Personal presets are offered when the model has them and the bitmap lists both the read and the select command. */
    private fun customEqOn(): Boolean =
        profile.customEq && supports(BudsIds.CMD_QUERY_EQ_ALL) && supports(BudsIds.CMD_SAVE_CUSTOM_EQ)

    /**
     * The equalizer row: the model's built-in presets, then the personal ones (ids [BUDS_CUSTOM_EQ_ID_BASE] + their own id).
     * The selection is the reported current id when it names one of them, else nothing.
     */
    private fun eqNow(): BudsEqState? {
        val builtins = profile.eqPresets
        if (builtins.isEmpty() || !supports(BudsIds.CMD_SET_EQ)) return null
        val custom = if (customEqOn()) customList else emptyList()
        val presets = builtins + custom.map { BudsEqPreset(BudsCustomEq.chipId(it.id), BudsCustomEq.label(it)) }
        return BudsEqState(presets, selectedEqId(builtins, custom))
    }

    /** A built-in id and a personal id share one id space on the buds; when both match, the list's own "selected" flag decides. */
    private fun selectedEqId(builtins: List<BudsEqPreset>, custom: List<BudsCustomPreset>): Int? {
        val current = eqCurrent ?: return null
        val isBuiltin = builtins.any { it.id == current }
        val mine = custom.firstOrNull { it.id == current }
        return when {
            mine != null && (!isBuiltin || mine.selected) -> BudsCustomEq.chipId(mine.id)
            isBuiltin -> current
            else -> null
        }
    }

    /** The 0x8122 list always wins, also when it is empty. */
    private fun applyCustomList(list: List<BudsCustomPreset>) {
        customList = list
        customObservations.incrementAndGet()
        _state.update { it.copy(extras = it.extras.copy(eq = eqNow())) }
    }

    /**
     * The buds report [id] as the current preset (reply 0x810F or push 0x0504). While a pick of ours is unconfirmed
     * and under [EQ_HOLD_MS] old, a report of another preset is ignored: a read answered before the buds applied the
     * change must not make the highlight jump back (QuickBuds fixed the same bug).
     */
    private fun applyEq(id: Int) {
        val want = eqPicked
        if (want != null) {
            if (id == want || nowMs() - eqPickedAt >= EQ_HOLD_MS) eqPicked = null else return
        }
        eqCurrent = id
        eqObservations.incrementAndGet()
        _state.update { it.copy(extras = it.extras.copy(eq = eqNow())) }
        // An id that is neither built-in nor in the list we hold: a personal preset we have not read (or were renumbered). Read the list.
        if (customEqOn() && profile.eqPresets.none { it.id == id } && customList.none { it.id == id }) {
            sessionScope.launch { send(BudsIds.CMD_QUERY_EQ_ALL, ByteArray(0)) }
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
        if (supports(BudsIds.CMD_REGISTER)) {
            send(BudsIds.CMD_REGISTER, ByteArray(ids.size + 1) { if (it == 0) ids.size.toByte() else ids[it - 1].toByte() })
            delayFn(INIT_STEP_MS)
        } else {
            // No 0x0205 in the bitmap: one code per command (section 6).
            for (id in ids) {
                send(BudsIds.CMD_REGISTER_ONE, byteArrayOf(id.toByte()))
                delayFn(INIT_STEP_MS)
            }
        }
        sendStatusQuery()
        delayFn(INIT_STEP_MS)
        if (supports(BudsIds.CMD_ANC_QUERY)) {
            send(BudsIds.CMD_ANC_QUERY, byteArrayOf(1, 1))
            delayFn(INIT_STEP_MS)
        }
        send(BudsIds.CMD_BATTERY, ByteArray(0))
        if (supports(BudsIds.CMD_WEARING)) {
            delayFn(INIT_STEP_MS)
            send(BudsIds.CMD_WEARING, ByteArray(0))
        }
        // Model extras last, same spacing: the current equalizer preset and the firmware version.
        if (profile.eqPresets.isNotEmpty() && supports(BudsIds.CMD_QUERY_EQ)) {
            delayFn(INIT_STEP_MS)
            send(BudsIds.CMD_QUERY_EQ, ByteArray(0))
            if (customEqOn()) {
                delayFn(INIT_STEP_MS)
                send(BudsIds.CMD_QUERY_EQ_ALL, ByteArray(0))
            }
        }
        if (profile.readsFirmware && supports(BudsIds.CMD_FIRMWARE)) {
            delayFn(INIT_STEP_MS)
            send(BudsIds.CMD_FIRMWARE, ByteArray(0))
        }
    }

    /** False only when the buds' bitmap is known and lacks the command; the vendor app never sends such a command. */
    private fun supports(cmd: Int): Boolean = bitmap?.supports(cmd) ?: true

    private suspend fun pollLoop() {
        while (isOpen()) {
            delayFn(POLL_INTERVAL_MS)
            if (!isOpen()) return
            send(BudsIds.CMD_BATTERY, ByteArray(0))     // wearing is pushed, never polled
            delayFn(INIT_STEP_MS)
            sendStatusQuery()
            if (profile.eqPresets.isNotEmpty() && supports(BudsIds.CMD_QUERY_EQ)) {
                delayFn(INIT_STEP_MS)
                send(BudsIds.CMD_QUERY_EQ, ByteArray(0))
                if (customEqOn()) {
                    delayFn(INIT_STEP_MS)
                    send(BudsIds.CMD_QUERY_EQ_ALL, ByteArray(0))
                }
            }
        }
    }

    private suspend fun sendStatusQuery() {
        val ids = BudsModelQueries.statusIds(_state.value.productId)
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
        if (!isOpen() || !supports(BudsIds.CMD_SET_ANC)) return false
        val observedBefore = modeObservations.get()
        replies.remove(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT)
        if (!send(BudsIds.CMD_SET_ANC, BudsFrames.setAncPayload(mode))) return false
        if (!awaitAccepted(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT, ACK_WAIT_MS)) return false
        return confirmAnc { modeObservations.get() > observedBefore && _state.value.noiseMode == mode }
    }

    private suspend fun doSetAncLevel(level: BudsAncLevel): Boolean {
        if (!isOpen() || !supports(BudsIds.CMD_SET_ANC)) return false
        val payload = profile.levelSetPayload(level) ?: return false
        val observedBefore = modeObservations.get()
        replies.remove(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT)
        if (!send(BudsIds.CMD_SET_ANC, payload)) return false
        if (!awaitAccepted(BudsIds.CMD_SET_ANC or BudsIds.REPLY_BIT, ACK_WAIT_MS)) return false
        return confirmAnc {
            modeObservations.get() > observedBefore &&
                _state.value.noiseMode == BudsNoiseMode.NOISE_CANCELLING &&
                _state.value.extras.ancLevel == level
        }
    }

    /**
     * An ack of 00 means "accepted", not "applied". Give the push a moment to confirm, then read the real state.
     * Either way one more read follows later: a read answered before the buds applied the change must not leave
     * the display on the old mode.
     */
    private suspend fun confirmAnc(confirmed: () -> Boolean): Boolean {
        if (awaitCondition(PUSH_WAIT_MS, confirmed)) {
            scheduleAncReread()
            return true
        }
        val sent = send(BudsIds.CMD_ANC_QUERY, byteArrayOf(1, 1))
        val ok = sent && awaitCondition(READBACK_WAIT_MS, confirmed)
        scheduleAncReread()
        return ok
    }

    /** One late read after a mode write; a newer write replaces the pending one. */
    private fun scheduleAncReread() {
        if (!supports(BudsIds.CMD_ANC_QUERY)) return
        rereadJob?.cancel()
        rereadJob = sessionScope.launch {
            delayFn(ANC_REREAD_MS)
            if (isOpen()) send(BudsIds.CMD_ANC_QUERY, byteArrayOf(1, 1))
        }
    }

    private suspend fun doSetLowLatency(on: Boolean): Boolean {
        if (!isOpen() || !supports(BudsIds.CMD_FEATURE_SWITCH)) return false
        val id = lowLatencyFeatureId() ?: return false
        return writeFeature(id, on)
    }

    /**
     * One feature write, confirmed by read-back. The buds apply a change up to about 1 s after the ack (game mode
     * measured), so a read sooner returns the old value: give a push time to confirm, then read that id.
     */
    private suspend fun writeFeature(id: Int, on: Boolean): Boolean {
        val observedBefore = featureObservations.get()
        replies.remove(BudsIds.CMD_FEATURE_SWITCH or BudsIds.REPLY_BIT)
        if (!send(BudsIds.CMD_FEATURE_SWITCH, byteArrayOf(id.toByte(), (if (on) 1 else 0).toByte()))) return false
        if (!awaitAccepted(BudsIds.CMD_FEATURE_SWITCH or BudsIds.REPLY_BIT, ACK_WAIT_MS)) return false
        val confirmed = { featureObservations.get() > observedBefore && featureOn(id) == on }
        if (awaitCondition(PUSH_WAIT_MS, confirmed)) return true
        if (!send(BudsIds.CMD_FEATURE_STATUS, byteArrayOf(1, id.toByte()))) return false
        return awaitCondition(READ_AFTER_WAIT_MS, confirmed)
    }

    /**
     * A switch of the popup. 3D audio and High-quality audio exclude each other (section 18): turning one on first
     * turns the other off, in the order the section gives. A failed Hi-Res write puts 3D audio back.
     */
    private suspend fun doSetSwitch(id: Int, on: Boolean): Boolean {
        if (!isOpen() || !supports(BudsIds.CMD_FEATURE_SWITCH)) return false
        if (profile.switches.none { it.id == id } || !featureValues.containsKey(id)) return false
        if (id == BudsIds.FEATURE_SPATIAL && on && featureOn(BudsIds.FEATURE_HIRES) == true) {
            if (!writeFeature(BudsIds.FEATURE_SPATIAL, true)) return false
            writeFeature(BudsIds.FEATURE_HIRES, false)
            return true
        }
        if (id == BudsIds.FEATURE_HIRES && on && featureOn(BudsIds.FEATURE_SPATIAL) == true) {
            if (!writeFeature(BudsIds.FEATURE_SPATIAL, false)) return false
            if (!writeFeature(BudsIds.FEATURE_HIRES, true)) {
                writeFeature(BudsIds.FEATURE_SPATIAL, true)
                return false
            }
            return true
        }
        return writeFeature(id, on)
    }

    private suspend fun doSetEqPreset(id: Int): Boolean {
        if (!isOpen() || !supports(BudsIds.CMD_SET_EQ)) return false
        if (BudsCustomEq.isChipId(id)) return doSetCustomPreset(id - BUDS_CUSTOM_EQ_ID_BASE)
        if (profile.eqPresets.none { it.id == id }) return false
        val observedBefore = eqObservations.get()
        replies.remove(BudsIds.CMD_SET_EQ or BudsIds.REPLY_BIT)
        eqPicked = id
        eqPickedAt = nowMs()
        if (!send(BudsIds.CMD_SET_EQ, byteArrayOf(id.toByte())) ||
            !awaitAccepted(BudsIds.CMD_SET_EQ or BudsIds.REPLY_BIT, ACK_WAIT_MS)
        ) {
            eqPicked = null
            return false
        }
        val confirmed = { eqObservations.get() > observedBefore && _state.value.extras.eq?.selectedId == id }
        if (awaitCondition(PUSH_WAIT_MS, confirmed)) return true
        if (supports(BudsIds.CMD_QUERY_EQ) && !send(BudsIds.CMD_QUERY_EQ, ByteArray(0))) return false
        return awaitCondition(READBACK_WAIT_MS, confirmed)
    }

    /**
     * Selects one of the buds' personal presets: 0x0418 with the whole preset (save and select), ack, then read the list and
     * the current id back (twice at most). True only when the buds report it as the selected one.
     */
    private suspend fun doSetCustomPreset(presetId: Int): Boolean {
        if (!isOpen() || !customEqOn()) return false
        val preset = customList.firstOrNull { it.id == presetId } ?: return false
        val observedBefore = eqObservations.get()
        val listsBefore = customObservations.get()
        val ackCmd = BudsIds.CMD_SAVE_CUSTOM_EQ or BudsIds.REPLY_BIT
        replies.remove(ackCmd)
        eqPicked = presetId
        eqPickedAt = nowMs()
        if (!send(BudsIds.CMD_SAVE_CUSTOM_EQ, BudsCustomEq.encodeSelect(preset)) || !awaitAccepted(ackCmd, ACK_WAIT_MS)) {
            eqPicked = null
            return false
        }
        val wanted = BudsCustomEq.chipId(presetId)
        val confirmed = {
            eqObservations.get() > observedBefore && customObservations.get() > listsBefore &&
                _state.value.extras.eq?.selectedId == wanted
        }
        for (attempt in 1..2) {
            if (!send(BudsIds.CMD_QUERY_EQ_ALL, ByteArray(0))) return false
            if (supports(BudsIds.CMD_QUERY_EQ) && !send(BudsIds.CMD_QUERY_EQ, ByteArray(0))) return false
            if (awaitCondition(READBACK_WAIT_MS, confirmed)) return true
        }
        return false
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
            shutdown("write failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    private fun logFrame(direction: String, cmd: Int, seq: Int, payload: ByteArray) {
        val log = debugLog ?: return
        val at = nowMs() - startedAt
        val head = payload.firstOrNull()?.toInt()?.and(0xFF)
        if ((cmd and 0x7FFF) == BudsIds.CMD_PUSH && (head == BudsIds.EVENT_LINKED || head == BudsIds.EVENT_DIAGNOSTIC)) {
            // Connected-devices info and diagnostic JSON can hold names or addresses: size only.
            log("$direction push ${String.format("%02X", head ?: 0)} len=${payload.size} payload-not-logged (+${at}ms)")
            return
        }
        if (!isControlFrame(cmd, payload)) return
        log("$direction ${BudsHex.of(BudsFrames.build(cmd, seq, payload))} (+${at}ms)")
    }

    private fun isControlFrame(cmd: Int, payload: ByteArray): Boolean {
        val base = cmd and 0x7FFF
        val isReply = cmd and BudsIds.REPLY_BIT != 0
        return when (base) {
            BudsIds.CMD_FEATURE_SWITCH, BudsIds.CMD_SET_ANC, BudsIds.CMD_ANC_QUERY,
            BudsIds.CMD_FEATURE_STATUS, BudsIds.CMD_FEATURE_PUSH, BudsIds.CMD_SET_EQ, BudsIds.CMD_EQ_CHANGED -> true
            // What the buds say about themselves and what they ask of us: answers only, never our own plain queries.
            BudsIds.CMD_HANDSHAKE, BudsIds.CMD_PRODUCT_ID, BudsIds.CMD_BROADCAST_CODES, BudsIds.CMD_REGISTER,
            BudsIds.CMD_BATTERY, BudsIds.CMD_WEARING, BudsIds.CMD_QUERY_EQ -> isReply
            BudsIds.CMD_REQ_TIME, BudsIds.CMD_REQ_APP_LIST, BudsIds.CMD_REQ_CAPABILITIES, BudsIds.CMD_REQ_SPATIAL -> true
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
        const val ANC_REREAD_MS = 1_500L
        const val EQ_HOLD_MS = 4_000L
    }
}
