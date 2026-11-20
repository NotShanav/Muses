package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * BUDS B1: the session against a fake pair of earbuds in virtual time. No Bluetooth, window or network.
 * The fake uses the production frame parser/builder, so frame bytes are covered by DesktopBudsProtocolTest.
 */
private fun sessionHex(s: String): ByteArray =
    s.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

class DesktopBudsSessionTest {

    /** Virtual time: [delay] parks until [advance] reaches it. Single thread (runBlocking). */
    private class FakeTime {
        var now = 0L
        private val waiters = ArrayList<Pair<Long, CompletableDeferred<Unit>>>()

        suspend fun delay(ms: Long) {
            if (ms <= 0) {
                yield()
                return
            }
            val d = CompletableDeferred<Unit>()
            waiters.add(Pair(now + ms, d))
            d.await()
        }

        suspend fun settle() {
            repeat(40) { yield() }
        }

        suspend fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                settle()
                val next = waiters.filter { it.first <= target }.minByOrNull { it.first } ?: break
                waiters.remove(next)
                if (next.first > now) now = next.first
                next.second.complete(Unit)
            }
            settle()
            now = target
        }
    }

    private class Sent(val time: Long, val frame: BudsFrame)

    /** A pair of buds: answers requests the way the documented firmware does, and can be told to misbehave. */
    private class FakeBuds(private val time: FakeTime, private val chunk: Int = 0) : BudsTransport {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = ArrayList<Sent>()
        private val parser = BudsFrameParser()

        var failWrites = false
        var closedByUs = false
        var bitmap: ByteArray = sessionHex("00 FF 77 5A EA 67 0E 20 07")

        var offered: ByteArray = byteArrayOf(0, 9, 1, 2, 3, 4, 8, 0x0B, 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte())
        var answerAnc = true
        var reportMask = 0x0008
        var refuseSet = false
        var silentSet = false
        var ignoreSet = false
        val features = mutableMapOf(0x05 to 0, 0x06 to 0)
        var refuseFeature = false
        var applyFeature = true
        var pushFeature = false

        override suspend fun write(bytes: ByteArray) {
            if (failWrites) throw java.io.IOException("fake write failure")
            for (f in parser.feed(bytes)) {
                written.add(Sent(time.now, f))
                respond(f)
            }
        }

        override suspend fun read(buffer: ByteArray): Int {
            val bytes = inbound.receiveCatching().getOrNull() ?: return -1
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun close() {
            closedByUs = true
            inbound.close()
        }

        fun push(payload: ByteArray) = emit(BudsFrames.build(0x0204, 0xFF, payload))

        fun emit(bytes: ByteArray) {
            if (chunk <= 0) {
                inbound.trySend(bytes)
                return
            }
            var i = 0
            while (i < bytes.size) {
                val end = minOf(bytes.size, i + chunk)
                inbound.trySend(bytes.copyOfRange(i, end))
                i = end
            }
        }

        private fun reply(f: BudsFrame, payload: ByteArray) = emit(BudsFrames.build(f.cmd or 0x8000, f.seq, payload))

        private fun reportFor(setPayload: ByteArray): Int = when {
            setPayload.size == 4 -> 0x0800                         // Adaptive
            setPayload[2].toInt() == 0x01 -> 0x0008                // Off
            setPayload[2].toInt() == 0x02 -> 0x0020                // ANC: the buds report the last LEVEL (Medium)
            else -> 0x0100                                         // Transparency
        }

        private fun ancPayload(mask: Int) = byteArrayOf(0x01, 0x01, (mask and 0xFF).toByte(), ((mask shr 8) and 0xFF).toByte())

        private fun respond(f: BudsFrame) {
            when (f.cmd) {
                0x0100 -> reply(f, bitmap)
                0x0103 -> reply(f, sessionHex("00 14 54 06"))
                0x0200 -> reply(f, offered)
                0x0205 -> reply(f, sessionHex("01 03 01 00 02 00 03 00"))
                0x010D -> {
                    val ids = f.payload.drop(1).map { it.toInt() and 0xFF }.filter { features.containsKey(it) }
                    val out = ArrayList<Byte>()
                    out.add(0)
                    out.add(ids.size.toByte())
                    for (id in ids) {
                        out.add(id.toByte())
                        out.add((features[id] ?: 0).toByte())
                    }
                    reply(f, out.toByteArray())
                }
                0x010C -> if (answerAnc) reply(f, byteArrayOf(0x00) + ancPayload(reportMask))
                0x0106 -> reply(f, sessionHex("00 03 01 64 02 5A 03 D0"))
                0x0109 -> reply(f, sessionHex("00 03 01 03 02 04 03 04"))
                0x0404 -> {
                    if (silentSet) return
                    if (refuseSet) {
                        reply(f, sessionHex("0E"))
                        return
                    }
                    reply(f, sessionHex("00"))
                    if (!ignoreSet) {
                        reportMask = reportFor(f.payload)
                        push(byteArrayOf(0x03) + ancPayload(reportMask))
                    }
                }
                0x0403 -> {
                    val id = f.payload[0].toInt() and 0xFF
                    val value = f.payload[1].toInt() and 0xFF
                    if (refuseFeature) {
                        reply(f, sessionHex("02"))
                        return
                    }
                    reply(f, sessionHex("00"))
                    if (applyFeature) features[id] = value
                    if (applyFeature && pushFeature) emit(BudsFrames.build(0x0503, 0xFF, byteArrayOf(1, id.toByte(), value.toByte())))
                }
                else -> Unit
            }
        }
    }

    private class Env(val scope: CoroutineScope, val time: FakeTime, val buds: FakeBuds, val session: BudsSession)

    private fun runSession(
        chunk: Int = 0,
        debug: MutableList<String>? = null,
        configure: (FakeBuds) -> Unit = {},
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        val time = FakeTime()
        val buds = FakeBuds(time, chunk)
        configure(buds)
        val log: ((String) -> Unit)? = debug?.let { list -> { s: String -> list.add(s); Unit } }
        val session = BudsSession(
            transport = buds,
            scope = this,
            delayFn = { ms -> time.delay(ms) },
            nowMs = { 0x01020304L * 1000L },
            debugLog = log,
        )
        try {
            block(Env(this, time, buds, session))
        } finally {
            session.close()
        }
    }

    private fun Env.cmds(): List<Int> = buds.written.map { it.frame.cmd }

    // ── init ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun initSequenceRunsInOrderWithTheDocumentedDelays() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        assertEquals(listOf(0x0100, 0x0103, 0x0200, 0x0205, 0x010D, 0x010C, 0x0106, 0x0109), e.cmds())
        assertEquals(listOf(300L, 500L, 700L, 900L, 1100L, 1300L, 1500L, 1700L), e.buds.written.map { it.time })
        assertEquals((1..8).toList(), e.buds.written.map { it.frame.seq })
        val byCmd = e.buds.written.associate { it.frame.cmd to it.frame.payload }
        assertContentEquals(sessionHex("03 01 02 03"), byCmd[0x0205])        // count first: battery, wearing, ANC
        assertContentEquals(sessionHex("03 05 06 28"), byCmd[0x010D])
        assertContentEquals(sessionHex("01 01"), byCmd[0x010C])
    }

    @Test
    fun stateAfterInitFollowsWhatTheBudsReport() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        val s = e.session.state.value
        assertTrue(s.connected)
        assertEquals("065414", s.productId)
        assertEquals(BudsBattery(100, 90, 80, false, false, true), s.battery)
        assertEquals(Pair(true, false), s.inEar)
        assertEquals(BudsNoiseMode.OFF, s.noiseMode)
        assertEquals(setOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY), s.supportedNoiseModes)
        assertTrue(s.lowLatencySupported)
        assertEquals(false, s.lowLatency)
    }

    @Test
    fun framesArrivingInTinyChunksGiveTheSameState() = runSession(chunk = 3) { e ->
        e.session.start()
        e.time.advance(2_000)
        val s = e.session.state.value
        assertEquals("065414", s.productId)
        assertEquals(80, s.battery.case)
        assertEquals(BudsNoiseMode.OFF, s.noiseMode)
    }

    @Test
    fun noiseControlIsHiddenWhenTheBudsOfferNoAncEventAndNeverAnswer() = runSession(
        // Buds without ANC have bit 8 of the bitmap off as well; with it on, noise control shows (next tests).
        configure = { it.offered = sessionHex("00 02 02 01"); it.answerAnc = false; it.bitmap = sessionHex("00 FF 76 5A EA 67 0E 20 07") },
    ) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertContentEquals(sessionHex("02 01 02"), e.buds.written.first { it.frame.cmd == 0x0205 }.frame.payload)
        assertTrue(e.session.state.value.supportedNoiseModes.isEmpty())
        assertNull(e.session.state.value.noiseMode)
    }

    @Test
    fun gameModeEventIsSubscribedOnlyWhenOffered() = runSession(
        configure = { it.offered = sessionHex("00 05 01 02 03 05 08") },
    ) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertContentEquals(sessionHex("04 01 02 03 05"), e.buds.written.first { it.frame.cmd == 0x0205 }.frame.payload)
        e.buds.push(sessionHex("05 01"))
        e.time.settle()
        assertEquals(true, e.session.state.value.lowLatency)
    }

    // ── pushes and the polling rule ──────────────────────────────────────────────────────────────

    @Test
    fun pushesUpdateTheState() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.buds.push(sessionHex("01 03 01 E0 02 10 03 00"))                   // left 96 charging, right 16, case unknown
        e.buds.push(sessionHex("02 02 01 07 02 07"))
        e.buds.push(sessionHex("03 01 01 00 08"))                            // Adaptive
        e.time.settle()
        val s = e.session.state.value
        assertEquals(96, s.battery.left)
        assertTrue(s.battery.leftCharging)
        assertEquals(16, s.battery.right)
        assertEquals(80, s.battery.case)                             // not reported: the old level stays
        assertEquals(Pair(true, true), s.inEar)
        assertEquals(BudsNoiseMode.ADAPTIVE, s.noiseMode)
        assertTrue(BudsNoiseMode.ADAPTIVE in s.supportedNoiseModes)
    }

    @Test
    fun aHoldCycleEchoIsNotAModeChange() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.buds.push(sessionHex("03 02 01 07 00"))
        e.time.settle()
        assertEquals(BudsNoiseMode.OFF, e.session.state.value.noiseMode)
    }

    @Test
    fun statusIsPolledEverySixtySecondsAndWearingIsNot() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.time.advance(60_000)
        assertEquals(2, e.cmds().count { it == 0x0106 })
        assertEquals(2, e.cmds().count { it == 0x010D })
        assertEquals(1, e.cmds().count { it == 0x0109 })
    }

    @Test
    fun theBudsTimeRequestGetsAnAnswer() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.buds.emit(BudsFrames.build(0x0500, 7, sessionHex("00 00 00 00")))
        e.time.settle()
        val answer = e.buds.written.last { it.frame.cmd == 0x8500 }.frame
        assertEquals(7, answer.seq)
        assertContentEquals(sessionHex("00 04 03 02 01"), answer.payload)   // 0x01020304 seconds, little endian
    }

    // ── disconnects ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun lidCloseZeroPushThenSocketDropIsADisconnect() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.buds.push(sessionHex("02 03 01 00 02 00 03 00"))
        e.time.settle()
        val mid = e.session.state.value
        assertEquals(Pair(false, false), mid.inEar)
        assertTrue(mid.connected)                                    // the drop itself decides
        assertEquals(100, mid.battery.left)                          // a zero push clears nothing
        e.buds.inbound.close()                                       // the socket drops: read returns -1
        e.time.settle()
        assertFalse(e.session.state.value.connected)
        assertTrue(e.buds.closedByUs)
        val before = e.buds.written.size
        e.time.advance(130_000)
        assertEquals(before, e.buds.written.size)                    // polling stopped
        assertFalse(e.session.setNoiseMode(BudsNoiseMode.OFF))
    }

    @Test
    fun aFailedWriteEndsTheSession() = runSession(configure = { it.failWrites = true }) { e ->
        e.session.start()
        e.time.advance(1_000)
        assertFalse(e.session.state.value.connected)
        assertTrue(e.buds.closedByUs)
    }

    @Test
    fun closeIsIdempotentAndStopsTheTransport() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        e.session.close()
        e.session.close()
        assertFalse(e.session.state.value.connected)
        assertTrue(e.buds.closedByUs)
    }

    // ── noise mode writes ────────────────────────────────────────────────────────────────────────

    private suspend fun Env.setNoise(mode: BudsNoiseMode): Boolean {
        val r = scope.async { session.setNoiseMode(mode) }
        time.advance(2_500)
        return r.await()
    }

    @Test
    fun setNoiseCancellingSendsTheSetBitsAndConfirmsByMask() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        assertTrue(e.setNoise(BudsNoiseMode.NOISE_CANCELLING))
        val set = e.buds.written.last { it.frame.cmd == 0x0404 }.frame
        assertContentEquals(sessionHex("01 01 02"), set.payload)
        assertEquals(BudsNoiseMode.NOISE_CANCELLING, e.session.state.value.noiseMode)   // buds reported the Medium level bit
    }

    @Test
    fun setAdaptiveSendsFourBytes() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        assertTrue(e.setNoise(BudsNoiseMode.ADAPTIVE))
        assertContentEquals(sessionHex("01 01 00 08"), e.buds.written.last { it.frame.cmd == 0x0404 }.frame.payload)
        assertEquals(BudsNoiseMode.ADAPTIVE, e.session.state.value.noiseMode)
        assertTrue(BudsNoiseMode.ADAPTIVE in e.session.state.value.supportedNoiseModes)
    }

    @Test
    fun setTransparencyThenOffRoundTrips() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        assertTrue(e.setNoise(BudsNoiseMode.TRANSPARENCY))
        assertEquals(BudsNoiseMode.TRANSPARENCY, e.session.state.value.noiseMode)
        assertTrue(e.setNoise(BudsNoiseMode.OFF))
        assertEquals(BudsNoiseMode.OFF, e.session.state.value.noiseMode)
    }

    @Test
    fun aRefusedSetReturnsFalseAndKeepsTheRealMode() = runSession(configure = { it.refuseSet = true }) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertFalse(e.setNoise(BudsNoiseMode.TRANSPARENCY))
        assertEquals(BudsNoiseMode.OFF, e.session.state.value.noiseMode)
    }

    @Test
    fun noAckMeansFalse() = runSession(configure = { it.silentSet = true }) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertFalse(e.setNoise(BudsNoiseMode.TRANSPARENCY))
    }

    @Test
    fun anAckedButNotAppliedSetIsNotReportedAsSuccess() = runSession(configure = { it.ignoreSet = true }) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertFalse(e.setNoise(BudsNoiseMode.TRANSPARENCY))
        assertEquals(BudsNoiseMode.OFF, e.session.state.value.noiseMode)    // the read-back shows the truth
        assertTrue(e.cmds().count { it == 0x010C } >= 2)                    // init read plus the read-back
    }

    // ── low latency ──────────────────────────────────────────────────────────────────────────────

    private suspend fun Env.setLatency(on: Boolean): Boolean {
        val r = scope.async { session.setLowLatency(on) }
        time.advance(3_500)
        return r.await()
    }

    @Test
    fun lowLatencyIsConfirmedByTheReadBack() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        assertTrue(e.setLatency(true))
        assertContentEquals(sessionHex("06 01"), e.buds.written.last { it.frame.cmd == 0x0403 }.frame.payload)
        assertEquals(true, e.session.state.value.lowLatency)
        assertTrue(e.setLatency(false))
        assertEquals(false, e.session.state.value.lowLatency)
    }

    @Test
    fun lowLatencyIsConfirmedByAFeaturePush() = runSession(configure = { it.pushFeature = true }) { e ->
        e.session.start()
        e.time.advance(2_000)
        val queriesBefore = e.cmds().count { it == 0x010D }
        assertTrue(e.setLatency(true))
        assertEquals(queriesBefore, e.cmds().count { it == 0x010D })      // the push was enough: no extra read
    }

    @Test
    fun lowLatencyUsesFeature28WhenListed() = runSession(configure = { it.features[0x28] = 0 }) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertTrue(e.setLatency(true))
        assertContentEquals(sessionHex("28 01"), e.buds.written.last { it.frame.cmd == 0x0403 }.frame.payload)
    }

    @Test
    fun lowLatencyNotAppliedOrRefusedIsFalse() {
        runSession(configure = { it.applyFeature = false }) { e ->
            e.session.start()
            e.time.advance(2_000)
            assertFalse(e.setLatency(true))
            assertEquals(false, e.session.state.value.lowLatency)
        }
        runSession(configure = { it.refuseFeature = true }) { e ->
            e.session.start()
            e.time.advance(2_000)
            assertFalse(e.setLatency(true))
        }
    }

    @Test
    fun lowLatencyWithoutASupportedFeatureWritesNothing() = runSession(
        configure = { it.features.remove(0x06) },
    ) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertFalse(e.session.state.value.lowLatencySupported)
        assertNull(e.session.state.value.lowLatency)
        assertFalse(e.setLatency(true))
        assertTrue(e.cmds().none { it == 0x0403 })
    }

    // ── answers the buds wait for, and the command bitmap ─────────────────────────────────────────

    private fun Env.answers(from: Int): List<Sent> = buds.written.drop(from).filter { it.frame.cmd and 0x8000 != 0 }

    @Test
    fun linkedAndDiagnosticPushesAreAnsweredWithTheirOwnSeq() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        val before = e.buds.written.size
        e.buds.emit(BudsFrames.build(0x0204, 0x21, byteArrayOf(0xF2.toByte(), 1, 2, 3)))
        e.buds.emit(BudsFrames.build(0x0204, 0xFF, byteArrayOf(0xF4.toByte()) + "{\"cmd\":\"a2dp_caton\",\"ver\":\"1\"}".toByteArray()))
        e.buds.emit(BudsFrames.build(0x0204, 0x22, byteArrayOf(0xF4.toByte()) + "not json".toByteArray()))
        e.time.advance(100)
        val got = e.answers(before)
        assertEquals(listOf(0x8204, 0x8204, 0x8204), got.map { it.frame.cmd })
        assertEquals(listOf(0x21, 0xFF, 0x22), got.map { it.frame.seq })
        assertContentEquals(sessionHex("00 F2"), got[0].frame.payload)
        assertContentEquals(byteArrayOf(0, 0xF4.toByte()) + "{\"cmd\":\"a2dp_caton\"}".toByteArray(), got[1].frame.payload)
        assertContentEquals(sessionHex("01 F4"), got[2].frame.payload)
    }

    @Test
    fun timeRequestAndUnknownRequestsAreAnswered() = runSession { e ->
        e.session.start()
        e.time.advance(2_000)
        val before = e.buds.written.size
        e.buds.emit(BudsFrames.build(0x0500, 0x14, byteArrayOf(0, 0, 0, 0)))
        e.buds.emit(BudsFrames.build(0x0600, 0x09, ByteArray(0)))
        e.buds.emit(BudsFrames.build(0x0502, 0x0A, byteArrayOf(5)))           // camera/ring request: no answer
        e.time.advance(100)
        val got = e.answers(before)
        assertEquals(listOf(0x8500, 0x8600), got.map { it.frame.cmd })
        assertContentEquals(sessionHex("00 04 03 02 01"), got[0].frame.payload)
        assertContentEquals(sessionHex("01"), got[1].frame.payload)
    }

    @Test
    fun commandsMissingFromTheBitmapAreNotSent() = runSession(
        configure = { it.bitmap = sessionHex("00 EF 76 1A EA 67 0E 20 07") },   // bits 4 (wearing), 8 (ANC) and 22 (register) cleared
    ) { e ->
        e.session.start()
        e.time.advance(3_000)
        assertEquals(
            listOf(0x0100, 0x0103, 0x0200, 0x0201, 0x0201, 0x0201, 0x010D, 0x0106),
            e.cmds(),
        )
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), e.buds.written.filter { it.frame.cmd == 0x0201 }.map { f -> f.frame.payload.map { it.toInt() } })
        assertTrue(e.session.state.value.supportedNoiseModes.isEmpty())
        assertFalse(e.setNoise(BudsNoiseMode.OFF))                              // refused without sending 0x0404
        assertTrue(e.cmds().none { it == 0x0404 })
    }

    @Test
    fun noiseControlShowsWhenTheBitmapListsItEvenIfEventThreeIsNotOffered() = runSession(
        configure = { it.offered = byteArrayOf(0, 2, 2, 1); it.answerAnc = false },
    ) { e ->
        e.session.start()
        e.time.advance(2_000)
        assertEquals(setOf(BudsNoiseMode.OFF, BudsNoiseMode.NOISE_CANCELLING, BudsNoiseMode.TRANSPARENCY), e.session.state.value.supportedNoiseModes)
    }

    // ── privacy of the debug log ─────────────────────────────────────────────────────────────────

    @Test
    fun debugLogHoldsOnlyControlFrameHexAndIsOffByDefault() {
        val lines = ArrayList<String>()
        runSession(debug = lines) { e ->
            e.session.start()
            e.time.advance(2_000)
            assertTrue(e.setNoise(BudsNoiseMode.OFF))
        }
        assertTrue(lines.any { it.startsWith("TX AA 0A 00 00 04 04") })
        assertTrue(lines.none { it.contains("AA 07 00 00 00 01") })          // the handshake is not a control frame
        assertTrue(lines.all { it.startsWith("TX ") || it.startsWith("RX ") || it.startsWith("END ") })
        runSession { e ->
            e.session.start()
            e.time.advance(2_000)
        }                                                                    // no logger: nothing to capture, nothing thrown
    }
}
