package com.music.bitchord.desktop

import java.util.Locale

/**
 * BUDS B1: which Bluetooth names look like earbuds of the OnePlus / OPPO / realme / DIZO family that speak
 * the vendor protocol. Pure and data driven. A renamed device will not match (the owner can rename it back).
 * Trademarks belong to their owners; the names are used only to say which devices work.
 */
internal object DesktopBudsNames {

    /** Patterns run over the lower-case name with every non letter/digit turned into one space. */
    private val PATTERNS: List<Regex> = listOf(
        // OnePlus
        """\boneplus buds\b""",
        """\boneplus nord buds\b""",
        """\bnord buds\b""",
        """\boneplus bullets wireless\b""",
        // OPPO (Enco is specific enough to match without the brand)
        """\boppo enco\b""",
        """\benco (air|x|free|buds|w|r|m|e|clip)""",
        // realme
        """\brealme buds\b""",
        """\brealme techlife buds\b""",
        // DIZO
        """\bdizo wireless\b""",
        """\bdizo go ?pods\b""",
    ).map { Regex(it) }

    fun isLikelyCompatible(deviceName: String): Boolean {
        val n = normalise(deviceName)
        if (n.isEmpty()) return false
        return PATTERNS.any { it.containsMatchIn(n) }
    }

    private fun normalise(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
}
