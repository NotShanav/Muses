package com.music.bitchord.desktop

/*
 * BUDS custom equalizer: the personal presets the buds hold (PROTOCOL.md section 17, QuickBuds protocol/Eq.kt EqCodec).
 *
 * List reply 0x8122:  `00 <count>` then per preset `<flag> <tag 2B> <id> <nameLen> <name> <bandCount> [<freq u16 LE> <gain s8>]...`
 * Select (0x0418):    `02 <tag 2B> <id> <nameLen> <name> <bandCount> [<freq u16 LE> <gain s8>]...` = save AND select, the WHOLE
 *                     preset every time, tag echoed back verbatim. Reply 0x8418 `00 <id>`.
 * Create, delete and band edits are out of scope here: this only lists and selects.
 */

/** One personal preset as the buds listed it. Kept whole because a select sends the whole preset back. */
internal class BudsCustomPreset(
    val id: Int,
    val name: String,
    val freqs: List<Int>,
    val gains: List<Int>,
    val selected: Boolean,
    /** The two gain-range bytes of the entry (FA 06 in every frame seen), echoed back unchanged. */
    val tag: ByteArray,
) {
    fun withSelected(value: Boolean) = BudsCustomPreset(id, name, freqs, gains, value, tag)
}

internal object BudsCustomEq {
    /** 0x0418 action byte: save and select in one. */
    const val ACTION_SAVE = 0x02

    /** The id this preset has in [BudsEqState] (see [BUDS_CUSTOM_EQ_ID_BASE]). */
    fun chipId(presetId: Int): Int = BUDS_CUSTOM_EQ_ID_BASE + presetId

    fun isChipId(id: Int): Boolean = id >= BUDS_CUSTOM_EQ_ID_BASE

    /** The name the buds hold; "Custom <id>" for a blank one. */
    fun label(p: BudsCustomPreset): String = p.name.trim().ifEmpty { "Custom ${p.id}" }

    /** Reply 0x8122 payload. Null on any inconsistency (nothing is shown rather than a guess). */
    fun parseList(payload: ByteArray): List<BudsCustomPreset>? {
        if (payload.size < 2 || u(payload, 0) != 0) return null
        val count = u(payload, 1)
        var o = 2
        val out = ArrayList<BudsCustomPreset>(count)
        repeat(count) {
            if (o + 5 > payload.size) return null
            val flag = u(payload, o)
            val tag = payload.copyOfRange(o + 1, o + 3)
            val id = u(payload, o + 3)
            val nameLen = u(payload, o + 4)
            o += 5
            if (o + nameLen + 1 > payload.size) return null
            val name = String(payload, o, nameLen, Charsets.UTF_8)
            o += nameLen
            val bands = u(payload, o)
            o += 1
            if (o + bands * 3 > payload.size) return null
            val freqs = ArrayList<Int>(bands)
            val gains = ArrayList<Int>(bands)
            repeat(bands) {
                freqs.add(u(payload, o) or (u(payload, o + 1) shl 8))
                gains.add(payload[o + 2].toInt())          // signed byte
                o += 3
            }
            out.add(BudsCustomPreset(id, name, freqs, gains, flag == 1, tag))
        }
        return out
    }

    /** 0x0418 payload that selects [p]: the whole preset with the save action. */
    fun encodeSelect(p: BudsCustomPreset): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out.add(ACTION_SAVE.toByte())
        for (b in p.tag) out.add(b)
        out.add(p.id.toByte())
        out.add(name.size.toByte())
        for (b in name) out.add(b)
        out.add(p.freqs.size.toByte())
        for (i in p.freqs.indices) {
            out.add((p.freqs[i] and 0xFF).toByte())
            out.add(((p.freqs[i] shr 8) and 0xFF).toByte())
            out.add(p.gains[i].toByte())
        }
        return out.toByteArray()
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
