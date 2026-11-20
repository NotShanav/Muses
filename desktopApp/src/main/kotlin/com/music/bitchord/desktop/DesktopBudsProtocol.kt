package com.music.bitchord.desktop

/*
 * BUDS B1: earbuds protocol core (pure Kotlin, no Windows, no Compose, no I/O).
 *
 * Wire facts come from QuickBuds docs/PROTOCOL.md (GPL-3.0, "Matches QuickBuds 5.4.5"; the source zip
 * attached was named 6.1.1). This is a small re-implementation, not copied source. See HANDOFF "BUDS B1".
 */

/** The modes the chip offers. Levels (light / medium / deep / smart) are folded into [NOISE_CANCELLING]. */
internal enum class BudsNoiseMode { OFF, NOISE_CANCELLING, TRANSPARENCY, ADAPTIVE }

/** Levels are 0..100 or null (not known / not reported). */
internal data class BudsBattery(
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
) {
    /** A part the update does not carry (null) keeps its old level and charging mark. */
    fun mergedWith(update: BudsBattery): BudsBattery = BudsBattery(
        left = update.left ?: left,
        right = update.right ?: right,
        case = update.case ?: case,
        leftCharging = if (update.left != null) update.leftCharging else leftCharging,
        rightCharging = if (update.right != null) update.rightCharging else rightCharging,
        caseCharging = if (update.case != null) update.caseCharging else caseCharging,
    )

    companion object {
        val EMPTY = BudsBattery(null, null, null, false, false, false)
    }
}

/** Command, event and feature numbers (every value traces to PROTOCOL.md; sections in the comments). */
internal object BudsIds {
    // Section 1: RFCOMM service UUIDs, in the order to try.
    val SERVICE_UUIDS: List<String> = listOf(
        "0000079A-D102-11E1-9B23-00025B00A5A5",
        "00001107-D102-11E1-9B23-00025B00A5A5",
    )

    // Section 4/7/8/9: commands. A reply sets bit 15.
    const val CMD_HANDSHAKE = 0x0100
    const val CMD_PRODUCT_ID = 0x0103
    const val CMD_FIRMWARE = 0x0105
    const val CMD_BATTERY = 0x0106
    const val CMD_WEARING = 0x0109
    const val CMD_ANC_QUERY = 0x010C
    const val CMD_FEATURE_STATUS = 0x010D
    /** Current equalizer preset (section 17): reply `00 <id>`. */
    const val CMD_QUERY_EQ = 0x010F
    const val CMD_BROADCAST_CODES = 0x0200
    const val CMD_PUSH = 0x0204
    const val CMD_REGISTER = 0x0205
    /** One code per command, for buds whose bitmap lacks [CMD_REGISTER] (section 6). */
    const val CMD_REGISTER_ONE = 0x0201
    const val CMD_FEATURE_SWITCH = 0x0403
    const val CMD_SET_ANC = 0x0404
    /** Select a built-in equalizer preset: `0x0406 <id>`, reply `00` (section 17). */
    const val CMD_SET_EQ = 0x0406
    /** Personal (custom) equalizer presets: list read `0x0122`, reply `00 <count> <entries>` (section 17). */
    const val CMD_QUERY_EQ_ALL = 0x0122
    /** Create / save+select / delete a personal preset: `0x0418 <action> <preset>`, reply `00 <id>` (section 17). */
    const val CMD_SAVE_CUSTOM_EQ = 0x0418
    /** Push: the equalizer changed, payload `<id>` (section 10). */
    const val CMD_EQ_CHANGED = 0x0504
    const val CMD_REQ_TIME = 0x0500
    const val CMD_REQ_APP_LIST = 0x050C
    const val CMD_REQ_CAPABILITIES = 0x051C
    const val CMD_REQ_SPATIAL = 0x051D
    const val CMD_FEATURE_PUSH = 0x0503
    const val REPLY_BIT = 0x8000

    const val SEQ_PUSH = 0xFF
    const val SEQ_FIRST = 0x01
    const val SEQ_LAST = 0xFE

    // Section 10: push event codes (payload[0] of 0x0204).
    const val EVENT_BATTERY = 0x01
    const val EVENT_WEARING = 0x02
    const val EVENT_ANC = 0x03
    const val EVENT_GAME_MODE = 0x05

    // Section 11: pushes the buds wait for an answer to.
    const val EVENT_LINKED = 0xF2
    const val EVENT_DIAGNOSTIC = 0xF4

    // Section 16: feature switch ids.
    const val FEATURE_STATUS_UNKNOWN_FIRST = 0x05
    const val FEATURE_WEAR = 0x04
    const val FEATURE_GAME_MODE_LEGACY = 0x06
    const val FEATURE_DUAL = 0x11
    const val FEATURE_HIRES = 0x18
    const val FEATURE_SPATIAL = 0x1B
    const val FEATURE_BASSWAVE = 0x1D
    const val FEATURE_GAME_MODE_NEW = 0x28

    /** Ids asked in the periodic status read. Short on purpose: a long list dropped one model's link (section 16). */
    val STATUS_FEATURE_IDS: List<Int> = listOf(FEATURE_STATUS_UNKNOWN_FIRST, FEATURE_GAME_MODE_LEGACY, FEATURE_GAME_MODE_NEW)

    // Section 14: SET bits (0x0404 01 01 <mask>) versus REPORT bits (push 03 / reply 0x810C).
    const val SET_OFF = 0x0001
    const val SET_NOISE_CANCELLING = 0x0002
    const val SET_TRANSPARENCY = 0x0004
    const val SET_ADAPTIVE = 0x0800

    const val REPORT_OFF = 0x0008
    /** ANC generic 0x0002 plus the levels Deep 0x10, Medium 0x20, Light 0x40, Smart 0x80. */
    const val REPORT_ANC_BITS = 0x00F2
    /** Transparency 0x0100 and transparency with voice enhance 0x0200. */
    const val REPORT_TRANSPARENCY_BITS = 0x0300
    const val REPORT_ADAPTIVE = 0x0800

    /** Wear states 3 and 7 are in the ear (section 13); 4 in the case, 1/5 out, 0 closed. */
    fun isInEarState(state: Int): Boolean = state == 3 || state == 7
}

/** Hex helper for the opt-in debug log. */
internal object BudsHex {
    fun of(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

/** LEB128 `TotalLen` (section 2: standard LEB128, 7 bits per byte, low first, bit 7 = more). */
internal object BudsLeb128 {
    /** [length] 0 = the bytes end before the number does; -1 = not a valid length (over 3 bytes). */
    internal class Decoded(val value: Int, val length: Int)

    const val MAX_BYTES = 3

    fun encode(value: Int): ByteArray {
        require(value in 0..0x1FFFFF) { "length out of range" }
        val out = ArrayList<Byte>(3)
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, from: Int, end: Int): Decoded {
        var value = 0
        var shift = 0
        var i = from
        var used = 0
        while (i < end && i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            used++
            if (b and 0x80 == 0) return Decoded(value, used)
            if (used >= MAX_BYTES) return Decoded(0, -1)
            shift += 7
            i++
        }
        return Decoded(0, 0)
    }
}

/** One decoded message. [payload] is a private copy. toString never prints payload bytes. */
internal class BudsFrame(val cmd: Int, val seq: Int, val payload: ByteArray) {
    val isPush: Boolean get() = seq == BudsIds.SEQ_PUSH
    val isReply: Boolean get() = cmd and BudsIds.REPLY_BIT != 0
    val baseCmd: Int get() = cmd and 0x7FFF

    override fun equals(other: Any?): Boolean =
        other is BudsFrame && other.cmd == cmd && other.seq == seq && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (cmd * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String = "BudsFrame(cmd=0x${cmd.toString(16)}, seq=$seq, payload=${payload.size}B)"
}

/** Frame and request builders. */
internal object BudsFrames {
    /** `AA <TotalLen LEB128> 00 00 <Cmd LE 2B> <Seq> <PayLen LE 2B> <Payload>`, TotalLen = 7 + PayLen. */
    fun build(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 0xFFFF) { "payload too long" }
        val total = 7 + payload.size
        val len = BudsLeb128.encode(total)
        val out = ByteArray(1 + len.size + total)
        var i = 0
        out[i++] = 0xAA.toByte()
        for (b in len) out[i++] = b
        out[i++] = 0x00
        out[i++] = 0x00
        out[i++] = (cmd and 0xFF).toByte()
        out[i++] = ((cmd shr 8) and 0xFF).toByte()
        out[i++] = (seq and 0xFF).toByte()
        out[i++] = (payload.size and 0xFF).toByte()
        out[i++] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, i)
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun handshake(seq: Int) = build(BudsIds.CMD_HANDSHAKE, seq)
    fun productIdQuery(seq: Int) = build(BudsIds.CMD_PRODUCT_ID, seq)
    fun broadcastQuery(seq: Int) = build(BudsIds.CMD_BROADCAST_CODES, seq)

    /** `0x0205 <count> <ids...>`, count FIRST (section 6). */
    fun registerPushes(seq: Int, eventIds: List<Int>) =
        build(BudsIds.CMD_REGISTER, seq, ByteArray(eventIds.size + 1) { if (it == 0) eventIds.size.toByte() else eventIds[it - 1].toByte() })

    /** `0x010D <count> <featureIds...>`. */
    fun statusQuery(seq: Int, featureIds: List<Int> = BudsIds.STATUS_FEATURE_IDS) =
        build(BudsIds.CMD_FEATURE_STATUS, seq, ByteArray(featureIds.size + 1) { if (it == 0) featureIds.size.toByte() else featureIds[it - 1].toByte() })

    /** `0x010C 01 01`: the current mode. */
    fun ancQuery(seq: Int) = build(BudsIds.CMD_ANC_QUERY, seq, bytes(0x01, 0x01))
    fun batteryQuery(seq: Int) = build(BudsIds.CMD_BATTERY, seq)
    fun wearingQuery(seq: Int) = build(BudsIds.CMD_WEARING, seq)

    /** Payload of `0x0404`: `01 01 <mask LE>`; Adaptive is bit 11, so four bytes `01 01 00 08`. */
    fun setAncPayload(mode: BudsNoiseMode): ByteArray = when (mode) {
        BudsNoiseMode.OFF -> bytes(0x01, 0x01, 0x01)
        BudsNoiseMode.NOISE_CANCELLING -> bytes(0x01, 0x01, 0x02)
        BudsNoiseMode.TRANSPARENCY -> bytes(0x01, 0x01, 0x04)
        BudsNoiseMode.ADAPTIVE -> bytes(0x01, 0x01, 0x00, 0x08)
    }

    /** `01 01 <mask LE>` with one bit set: [bit] / 8 + 1 mask bytes, low byte first (section 14). */
    fun setAncBitPayload(bit: Int): ByteArray {
        require(bit in 0..31) { "bit out of range" }
        val size = bit / 8 + 1
        val out = ByteArray(2 + size)
        out[0] = 0x01
        out[1] = 0x01
        out[2 + bit / 8] = (1 shl (bit % 8)).toByte()
        return out
    }

    fun setAnc(seq: Int, mode: BudsNoiseMode) = build(BudsIds.CMD_SET_ANC, seq, setAncPayload(mode))

    /** `0x0403 <featureId> <01|00>`. */
    fun featureSwitch(seq: Int, featureId: Int, on: Boolean) =
        build(BudsIds.CMD_FEATURE_SWITCH, seq, bytes(featureId and 0xFF, if (on) 1 else 0))

    /** An answer to a request from the buds: the request's command with bit 15 set, the request's seq. */
    fun reply(requestCmd: Int, seq: Int, payload: ByteArray) =
        build(requestCmd or BudsIds.REPLY_BIT, seq, payload)
}

/**
 * Streaming parser. Accepts arbitrary chunks (frames split or joined), resynchronises on 0xAA, rejects
 * impossible lengths and never throws. Slice frames (Slice 01..03) are consumed and dropped: this core
 * needs none of the long messages.
 */
internal class BudsFrameParser {
    private var buf = ByteArray(256)
    private var size = 0

    /** Bytes thrown away while looking for the next frame start (tests and debugging). */
    var droppedBytes: Long = 0
        private set

    fun reset() {
        size = 0
    }

    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<BudsFrame> {
        val out = ArrayList<BudsFrame>(2)
        try {
            if (offset < 0 || length <= 0 || offset + length > chunk.size) return out
            append(chunk, offset, length)
            var pos = 0
            while (pos < size) {
                if (buf[pos] != START) {
                    pos++
                    droppedBytes++
                    continue
                }
                val lebStart = pos + 1
                val leb = BudsLeb128.decode(buf, lebStart, size)
                if (leb.length == 0) break                       // length itself is incomplete
                if (leb.length < 0 || leb.value < MIN_BODY || leb.value > MAX_BODY) {
                    pos++                                       // impossible length: not a frame start
                    droppedBytes++
                    continue
                }
                val bodyStart = lebStart + leb.length
                val total = leb.value
                if (bodyStart + total > size) break              // wait for the rest
                val slice = buf[bodyStart].toInt() and 0xFF
                when {
                    slice in 1..3 -> {                          // slice frame: skip it whole
                        pos = bodyStart + total
                    }
                    slice == 0 && total >= WHOLE_MIN && buf[bodyStart + 1].toInt() == 0 -> {
                        val cmd = (buf[bodyStart + 2].toInt() and 0xFF) or ((buf[bodyStart + 3].toInt() and 0xFF) shl 8)
                        val seq = buf[bodyStart + 4].toInt() and 0xFF
                        val declared = (buf[bodyStart + 5].toInt() and 0xFF) or ((buf[bodyStart + 6].toInt() and 0xFF) shl 8)
                        val n = minOf(declared, total - WHOLE_MIN)   // a payload cut short is kept as far as it goes
                        out.add(BudsFrame(cmd, seq, buf.copyOfRange(bodyStart + 7, bodyStart + 7 + n)))
                        pos = bodyStart + total
                    }
                    else -> {
                        pos++
                        droppedBytes++
                    }
                }
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, size - pos)
                size -= pos
            }
        } catch (e: Exception) {
            size = 0
        }
        return out
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (size + length > MAX_BUFFER) {                        // a stalled half-frame must not grow forever
            droppedBytes += size
            size = 0
        }
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        System.arraycopy(chunk, offset, buf, size, length)
        size += length
    }

    companion object {
        private const val START: Byte = 0xAA.toByte()
        private const val MIN_BODY = 4
        private const val WHOLE_MIN = 7
        private const val MAX_BODY = 2048
        private const val MAX_BUFFER = 8192
    }
}

/** Wear states of the three parts; null = the part was not in the message. */
internal class BudsWearing(val left: Int?, val right: Int?, val case: Int?) {
    val inEar: Pair<Boolean, Boolean>
        get() = Pair(left?.let { BudsIds.isInEarState(it) } ?: false, right?.let { BudsIds.isInEarState(it) } ?: false)

    /** The lid-close push: every reported part is 0 (the socket drops about 1 s later). */
    val allZero: Boolean
        get() = listOfNotNull(left, right, case).let { it.isNotEmpty() && it.all { s -> s == 0 } }
}

/** Decoders for the payloads. Every function returns null for anything it cannot read; none throws. */
internal object BudsParsers {
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /**
     * `<count> then count * width bytes`, tried at offset 0 and at offset 1 (some replies carry a status byte
     * first). An exact length wins; otherwise the first plausible one (a longer tail is allowed).
     * Returns the offset of the first element and the count, or null.
     */
    private class ListAt(val first: Int, val count: Int)

    private fun locateList(p: ByteArray, width: Int, plausible: (ByteArray, ListAt) -> Boolean): ListAt? {
        var fallback: ListAt? = null
        for (offset in 0..1) {
            if (offset >= p.size) continue
            val count = u(p, offset)
            if (count < 1) continue
            val end = offset + 1 + count * width
            if (end > p.size) continue
            val candidate = ListAt(offset + 1, count)
            if (!plausible(p, candidate)) continue
            if (end == p.size) return candidate
            if (fallback == null) fallback = candidate
        }
        return fallback
    }

    /** Product id: `00 <3 bytes LE>` (or just the 3 bytes). Returned as the id the vendor lists use, e.g. `065414`. */
    fun productId(p: ByteArray): String? {
        val start = when {
            p.size == 4 && u(p, 0) == 0 -> 1
            p.size == 3 -> 0
            else -> return null
        }
        return String.format("%02X%02X%02X", u(p, start + 2), u(p, start + 1), u(p, start))
    }

    /** Broadcast codes reply: `00 <count> <codes>` (or `<count> <codes>`): the events the firmware offers. */
    fun broadcastCodes(p: ByteArray): Set<Int>? {
        val at = locateList(p, 1) { _, _ -> true } ?: return null
        return (0 until at.count).map { u(p, at.first + it) }.toSet()
    }

    /**
     * Battery reply `00 <count> <part raw>...` or push (after the event code) `<count> <part raw>...`.
     * Part 1 left, 2 right, 3 case; level = raw & 7F, charging = raw & 80; level 0 (or above 100) = not known,
     * the part is left out (null) so a merge keeps the old value.
     */
    fun battery(p: ByteArray): BudsBattery? {
        val at = locateList(p, 2) { b, l -> (0 until l.count).all { u(b, l.first + it * 2) in 1..3 } } ?: return null
        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        var lc = false
        var rc = false
        var cc = false
        for (i in 0 until at.count) {
            val part = u(p, at.first + i * 2)
            val raw = u(p, at.first + i * 2 + 1)
            val level = (raw and 0x7F).takeIf { it in 1..100 }
            val charging = raw and 0x80 != 0
            when (part) {
                1 -> { left = level; lc = level != null && charging }
                2 -> { right = level; rc = level != null && charging }
                3 -> { case = level; cc = level != null && charging }
            }
        }
        return BudsBattery(left, right, case, lc, rc, cc)
    }

    /** Wearing reply / push: `[status] <count> <part state>...`, parts 1..3. */
    fun wearing(p: ByteArray): BudsWearing? {
        val at = locateList(p, 2) { b, l -> (0 until l.count).all { u(b, l.first + it * 2) in 1..3 } } ?: return null
        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        for (i in 0 until at.count) {
            val state = u(p, at.first + i * 2 + 1)
            when (u(p, at.first + i * 2)) {
                1 -> left = state
                2 -> right = state
                3 -> case = state
            }
        }
        return BudsWearing(left, right, case)
    }

    /**
     * Push `0x0204` with event 03: `03 01 01 <mode LE, 1-4 bytes>`. Returns the mode mask, or null when this
     * is not a mode change (bytes 1 and 2 are not `01 01`: that is the echo of a gesture-cycle write).
     */
    fun ancNotifyMask(payload: ByteArray): Int? {
        if (payload.size < 4 || u(payload, 0) != BudsIds.EVENT_ANC) return null
        if (u(payload, 1) != 1 || u(payload, 2) != 1) return null
        return maskLe(payload, 3)
    }

    /** Reply `0x810C` to `01 01`: `00 01 01 <mode LE>` (or without the status byte). */
    fun ancReplyMask(payload: ByteArray): Int? {
        val offset = when {
            payload.size >= 4 && u(payload, 0) == 0 && u(payload, 1) == 1 && u(payload, 2) == 1 -> 3
            payload.size >= 3 && u(payload, 0) == 1 && u(payload, 1) == 1 -> 2
            else -> return null
        }
        return maskLe(payload, offset)
    }

    private fun maskLe(p: ByteArray, from: Int): Int? {
        if (from >= p.size) return null
        var mask = 0
        for (i in 0 until minOf(4, p.size - from)) mask = mask or (u(p, from + i) shl (8 * i))
        return mask
    }

    /**
     * Report mask to a mode. A mask test, never an enum (the ANC-on stop echoes the last LEVEL):
     * Adaptive 0x0800, Transparency 0x0100/0x0200, any ANC bit 0x02/0x10/0x20/0x40/0x80, Off 0x0008.
     * Anything else (including the SET-only bits 0x0001 and 0x0004) is null.
     */
    fun decodeNoiseMask(mask: Int): BudsNoiseMode? = when {
        mask and BudsIds.REPORT_ADAPTIVE != 0 -> BudsNoiseMode.ADAPTIVE
        mask and BudsIds.REPORT_TRANSPARENCY_BITS != 0 -> BudsNoiseMode.TRANSPARENCY
        mask and BudsIds.REPORT_ANC_BITS != 0 -> BudsNoiseMode.NOISE_CANCELLING
        mask and BudsIds.REPORT_OFF != 0 -> BudsNoiseMode.OFF
        else -> null
    }

    /** Feature status reply `00 <count> <id value>...` and feature push `0x0503 <count> <id value>...`. */
    fun featureValues(p: ByteArray): Map<Int, Int>? {
        val at = locateList(p, 2) { _, _ -> true } ?: return null
        val out = LinkedHashMap<Int, Int>()
        for (i in 0 until at.count) out[u(p, at.first + i * 2)] = u(p, at.first + i * 2 + 1)
        return out
    }

    /** Game mode push (event 05): `05 <state>`, or `05 <x> <x> <state>` on four product ids. */
    fun gameModePush(payload: ByteArray): Int? {
        if (payload.isEmpty() || u(payload, 0) != BudsIds.EVENT_GAME_MODE) return null
        return when {
            payload.size == 2 -> u(payload, 1)
            payload.size >= 4 -> u(payload, 3)
            else -> null
        }
    }

    /** Current EQ reply `0x810F`: `00 <id>`. */
    fun eqReply(p: ByteArray): Int? = if (p.size >= 2 && u(p, 0) == 0) u(p, 1) else null

    /** EQ changed push `0x0504`: `<id>`. */
    fun eqPush(p: ByteArray): Int? = if (p.isNotEmpty()) u(p, 0) else null

    /**
     * Firmware reply `0x8105` (section 6): `00 <count>` then UTF-8 `deviceType,versionType,version` triples joined by
     * commas. The `versionType` 2 versions in reply order, joined by dots (`138.138.105`); null when there are none.
     */
    fun firmware(p: ByteArray): String? {
        if (p.size < 3 || u(p, 0) != 0) return null
        val parts = String(p, 2, p.size - 2, Charsets.UTF_8).trim().split(",").map { it.trim() }
        val versions = ArrayList<String>()
        var i = 0
        while (i + 2 < parts.size) {
            if (parts[i + 1] == "2" && parts[i + 2].isNotEmpty() && parts[i + 2].all { it.isLetterOrDigit() }) versions.add(parts[i + 2])
            i += 3
        }
        return versions.joinToString(".").ifEmpty { null }
    }

    /** The status byte of a write reply (`0x8403`, `0x8404`): 00 accepted. */
    fun ackStatus(payload: ByteArray): Int? = payload.firstOrNull()?.let { it.toInt() and 0xFF }
}

/**
 * The command bitmap of the buds (reply to 0x0100, section 5): `00`, then bytes read least significant bit first.
 * A command with a bit that is off must not be sent: some firmwares reset the link when they get one.
 * Commands the table does not list, and the always-enabled ones, are never gated.
 */
internal class BudsBitmap private constructor(private val bits: Set<Int>) {

    fun supports(cmd: Int): Boolean {
        if (cmd in ALWAYS) return true
        val needed = BITS_OF[cmd] ?: return true
        return needed.any { it in bits }
    }

    companion object {
        private const val MAX_BITS = 69

        /** Section 5: bit n enables these commands (PROTOCOL.md). */
        private val TABLE: Map<Int, List<Int>> = mapOf(
            0 to listOf(0x0105),
            1 to listOf(0x0106),
            2 to listOf(0x0107),
            3 to listOf(0x0108, 0x0401, 0x0416),
            4 to listOf(0x0109),
            5 to listOf(0x0400),
            6 to listOf(0x0402),
            7 to listOf(0x0403),
            8 to listOf(0x010C, 0x0404),
            9 to listOf(0x0405),
            10 to listOf(0x0406, 0x010F),
            11 to listOf(0x0407),
            13 to listOf(0x0408),
            14 to listOf(0x0409),
            17 to listOf(0x0114),
            19 to listOf(0x040E, 0x040D, 0x0115, 0x0116),
            20 to listOf(0x040F),
            21 to listOf(0x0410, 0x0119),
            22 to listOf(0x0205),
            23 to listOf(0x0F00),
            25 to listOf(0x0118, 0x0411),
            26 to listOf(0x011A, 0x0412),
            27 to listOf(0x011C, 0x0413),
            29 to listOf(0x0112, 0x040B),
            30 to listOf(0x011E, 0x011F, 0x0415),
            31 to listOf(0x040D),
            33 to listOf(0x0121, 0x0417),
            34 to listOf(0x0122, 0x0418),
            35 to listOf(0x0419),
            36 to listOf(0x011D, 0x0414),
            37 to listOf(0x0123, 0x041A),
            38 to listOf(0x0124, 0x041B),
            39 to listOf(0x0125, 0x041C, 0x0127, 0x041D, 0x041F),
            40 to listOf(0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129),
            41 to listOf(0xEF01),
            42 to listOf(0xEF02),
            43 to listOf(0xEF03, 0x041E),
            44 to listOf(0x0420),
            45 to listOf(0x001C),
            47 to listOf(0x0422, 0x012A),
            48 to listOf(0xEF04),
            49 to listOf(0x0423, 0x012B),
            51 to listOf(0x0424),
            52 to listOf(0xEF06),
            54 to listOf(0x012C),
            55 to listOf(0x0425, 0x012E, 0x0426),
            56 to listOf(0x012F),
            57 to listOf(0x0427, 0x0130),
            58 to listOf(0x0131, 0x0428),
            59 to listOf(0x0429, 0x0132),
            60 to listOf(0x0014),
            61 to listOf(0x042D, 0x0133),
            62 to listOf(0x042E),
            63 to listOf(0xEF07),
            64 to listOf(0xEF08, 0x0136, 0x0433),
            65 to listOf(0xEF09),
            66 to listOf(0x0431, 0x0134),
            67 to listOf(0x0432, 0x0135, 0x0435),
            68 to listOf(0x0117),
        )

        private val ALWAYS: Set<Int> =
            setOf(0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04)

        private val BITS_OF: Map<Int, List<Int>> = HashMap<Int, MutableList<Int>>().also { m ->
            for ((bit, cmds) in TABLE) for (c in cmds) m.getOrPut(c) { ArrayList() }.add(bit)
        }

        /** Null when [payload] is not a successful bitmap reply (then nothing is gated). */
        fun parse(payload: ByteArray): BudsBitmap? {
            if (payload.isEmpty() || payload[0].toInt() != 0) return null
            val on = HashSet<Int>()
            for (i in 1 until payload.size) {
                val v = payload[i].toInt() and 0xFF
                for (b in 0 until 8) {
                    val bit = (i - 1) * 8 + b
                    if (bit < MAX_BITS && (v shr b) and 1 == 1) on.add(bit)
                }
            }
            return BudsBitmap(on)
        }
    }
}

/** Answers to pushes the buds wait for (section 11). Pure. */
internal object BudsPushAnswers {

    /** `0xF2`, the connected-devices info: always `00 F2`, whether or not the list is understood. */
    fun linked(): ByteArray = byteArrayOf(0, BudsIds.EVENT_LINKED.toByte())

    /** `0xF4`, diagnostic JSON: `00 F4 {"cmd":<its cmd>}`; `00 F4 {}` without a cmd; `01 F4` when it is not an object; `00 F4` when empty. */
    fun diagnostic(json: ByteArray): ByteArray {
        val head = byteArrayOf(0, BudsIds.EVENT_DIAGNOSTIC.toByte())
        if (json.isEmpty()) return head
        val text = String(json, Charsets.UTF_8).trim()
        if (!(text.startsWith("{") && text.endsWith("}"))) return byteArrayOf(1, BudsIds.EVENT_DIAGNOSTIC.toByte())
        val value = CMD_VALUE.find(text)?.groupValues?.get(1)
        val echo = if (value != null) "{\"cmd\":$value}" else "{}"
        return head + echo.toByteArray(Charsets.UTF_8)
    }

    private val CMD_VALUE = Regex("\"cmd\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|null)")

    /** A request outside command groups 01-05 and 0F gets `01` ("unknown request"); replies and known groups do not. */
    fun isUnknownRequest(cmd: Int): Boolean {
        if (cmd and BudsIds.REPLY_BIT != 0) return false
        val group = (cmd shr 8) and 0xFF
        return group !in 1..5 && group != 0x0F
    }
}

/** Per-model reads, keyed by the product id from 0x8103. The data lives in [BudsModelProfiles]. */
internal object BudsModelQueries {
    fun statusIds(productId: String?): List<Int> = BudsModelProfiles.forProduct(productId).statusIds
}
