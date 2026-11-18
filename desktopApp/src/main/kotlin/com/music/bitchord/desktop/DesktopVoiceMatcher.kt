package com.music.bitchord.desktop

/*
 * VOICE FIX X2 (Part A): the matcher. Text similarity (token overlap + edit distance) and phonetic
 * similarity, ranked candidates, and the MARGIN over the runner-up. Pure: nothing here touches disk,
 * the network or a log, and nothing is remembered.
 *
 * Playlists keep using X1's `VoiceListResolver` and `VoiceConfidence` (they already have a margin
 * and tests); Part B plugs [VoiceMatcher.scoreText] into its `score` parameter. Songs and artists
 * use [VoiceMatcher.decide].
 */

/** Every matching threshold, in one place. Edits are stricter than playback. */
internal object VoiceMatchThresholds {
    /** Below this a candidate is not even offered. */
    const val LOW = 0.62

    /** Playback: execute only when top >= HIGH_PLAY and the margin over the runner-up >= MARGIN_PLAY. */
    const val HIGH_PLAY = 0.80
    const val MARGIN_PLAY = 0.08

    /** Library edits: stricter on both. */
    const val HIGH_EDIT = 0.90
    const val MARGIN_EDIT = 0.15

    /** At most this many candidates are shown to the owner. */
    const val MAX_OPTIONS = 3

    /** Two different kinds (a playlist and a song) both this close to each other mean "ask which". */
    const val CROSS_KIND_MIN = 0.74
    const val CROSS_KIND_MARGIN = 0.12

    /** Added to a candidate whose kind matches a soft hint ("my X" leans playlist). */
    const val PRIOR_BONUS = 0.05

    /** A phonetic-only match never scores above this, so a text match of the same quality wins. */
    const val PHONETIC_CAP = 0.93

    /** Share of a song's score that comes from the artist when the owner named one. */
    const val ARTIST_WEIGHT = 0.30

    /** Single words shorter than this only match by equality (text), as in the parser. */
    const val FUZZY_MIN_LENGTH = 4
}

internal enum class VoiceMatchMode { PLAY, EDIT }

/**
 * [kind] null means "no idea". [explicit] true means the owner said the type ("playlist", "song",
 * "by Y"), so only that kind is searched; false means a soft lean ("my X").
 */
internal data class VoiceTypeHint(val kind: VoiceEntityKind?, val explicit: Boolean) {
    companion object {
        val NONE = VoiceTypeHint(null, false)

        /** [words] are the spoken words ([VoiceText.words]); [hasArtistPart] is true for "X by Y". */
        fun from(words: List<String>, hasArtistPart: Boolean): VoiceTypeHint {
            if (words.any { it == "playlist" || it == "playlists" || it == "mix" || it == "mixtape" }) {
                return VoiceTypeHint(VoiceEntityKind.PLAYLIST, true)
            }
            if (words.any { it == "album" || it == "albums" }) return VoiceTypeHint(VoiceEntityKind.ALBUM, true)
            if (words.any { it == "artist" || it == "band" }) return VoiceTypeHint(VoiceEntityKind.ARTIST, true)
            if (words.any { it == "song" || it == "track" || it == "tune" }) return VoiceTypeHint(VoiceEntityKind.SONG, true)
            if (hasArtistPart) return VoiceTypeHint(VoiceEntityKind.SONG, true)
            if (words.firstOrNull() == "my") return VoiceTypeHint(VoiceEntityKind.PLAYLIST, false)
            return NONE
        }
    }
}

internal class VoiceMatch(val entry: VoiceIndexEntry, val score: Double) {
    override fun toString(): String = "VoiceMatch(redacted)"
}

internal sealed interface VoiceMatchOutcome {
    /** Clear enough to act on. [margin] is the gap to the runner-up (1.0 when there is none). */
    class Confident(val match: VoiceMatch, val margin: Double) : VoiceMatchOutcome

    /** Not clear enough to act on; up to [VoiceMatchThresholds.MAX_OPTIONS] to show the owner. */
    class Candidates(val options: List<VoiceMatch>) : VoiceMatchOutcome

    /** Nothing at or above LOW. */
    object NoMatch : VoiceMatchOutcome
}

internal object VoiceMatcher {

    // ── Scoring ──────────────────────────────────────────────────────────────────────────────────

    /** Convenience for places that still have two plain strings (X1's resolver seam). Normalises both. */
    fun scoreText(query: String, candidate: String): Double = score(VoiceTerms.of(query), VoiceTerms.of(candidate))

    /** 0..1. 1.0 only when the words are the same after normalisation. */
    fun score(a: VoiceTerms, b: VoiceTerms): Double {
        if (a.tokens.isEmpty() || b.tokens.isEmpty()) return 0.0
        if (a.joined == b.joined) return 1.0
        val text = maxOf(tokenOverlap(a.tokens, b.tokens), similarity(a.joined, b.joined))
        val phonetic = phoneticScore(a, b) * VoiceMatchThresholds.PHONETIC_CAP
        return maxOf(text, phonetic)
    }

    private fun tokenOverlap(a: List<String>, b: List<String>): Double {
        val ab = a.sumOf { x -> b.maxOf { y -> tokenSimilarity(x, y) } } / a.size
        val ba = b.sumOf { y -> a.maxOf { x -> tokenSimilarity(x, y) } } / b.size
        return (ab + ba) / 2.0
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < VoiceMatchThresholds.FUZZY_MIN_LENGTH || b.length < VoiceMatchThresholds.FUZZY_MIN_LENGTH) return 0.0
        val sim = similarity(a, b)
        return if (sim >= 0.6) sim else 0.0
    }

    private fun phoneticScore(a: VoiceTerms, b: VoiceTerms): Double {
        val ab = a.phonetic.sumOf { x -> b.phonetic.maxOf { y -> phoneticTokenSimilarity(x, y) } } / a.phonetic.size
        val ba = b.phonetic.sumOf { y -> a.phonetic.maxOf { x -> phoneticTokenSimilarity(x, y) } } / b.phonetic.size
        val overlap = (ab + ba) / 2.0
        val compact = when {
            a.compact.length < 3 || b.compact.length < 3 -> 0.0
            a.compact == b.compact -> 1.0
            else -> similarity(a.compact, b.compact).let { if (it >= 0.8 && minOf(a.compact.length, b.compact.length) >= 4) it else 0.0 }
        }
        return maxOf(overlap, compact)
    }

    private fun phoneticTokenSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return if (a.length >= 2) 1.0 else 0.6
        if (minOf(a.length, b.length) < 3) return 0.0
        val sim = similarity(a, b)
        return if (sim >= 0.75) sim * 0.9 else 0.0
    }

    /** 1 - editDistance / longest. Own copy: the parser's is private. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longest
    }

    fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    // ── Ranking and the decision ─────────────────────────────────────────────────────────────────

    /**
     * Every entry at or above LOW, best first (ties broken by title so the order is stable).
     * [artist] is the "by Y" part, used only for songs that have a known artist.
     */
    fun rank(
        query: VoiceTerms,
        index: VoiceLibraryIndex,
        kinds: Set<VoiceEntityKind> = emptySet(),
        hint: VoiceTypeHint = VoiceTypeHint.NONE,
        artist: VoiceTerms? = null,
    ): List<VoiceMatch> {
        if (query.isEmpty) return emptyList()
        val searched = if (hint.explicit && hint.kind != null) setOf(hint.kind) else kinds
        val out = ArrayList<VoiceMatch>()
        for (entry in index.ofKinds(searched)) {
            var s = score(query, entry.terms)
            val entryArtist = entry.artist
            if (artist != null && !artist.isEmpty && entryArtist != null) {
                val w = VoiceMatchThresholds.ARTIST_WEIGHT
                s = s * (1.0 - w) + score(artist, entryArtist) * w
            }
            if (s >= VoiceMatchThresholds.LOW && !hint.explicit && hint.kind != null && entry.input.kind == hint.kind) {
                s = minOf(1.0, s + VoiceMatchThresholds.PRIOR_BONUS)
            }
            if (s >= VoiceMatchThresholds.LOW) out.add(VoiceMatch(entry, s))
        }
        return out.sortedWith(compareByDescending<VoiceMatch> { it.score }.thenBy { it.entry.input.title })
    }

    /** The rule from the plan: act only when top >= HIGH and margin >= MARGIN; otherwise offer candidates. */
    fun decide(
        query: VoiceTerms,
        index: VoiceLibraryIndex,
        mode: VoiceMatchMode,
        kinds: Set<VoiceEntityKind> = emptySet(),
        hint: VoiceTypeHint = VoiceTypeHint.NONE,
        artist: VoiceTerms? = null,
    ): VoiceMatchOutcome = decideRanked(rank(query, index, kinds, hint, artist), mode, hint)

    /** Split out so the decision is testable over hand-made rankings. [ranked] must be best first. */
    fun decideRanked(ranked: List<VoiceMatch>, mode: VoiceMatchMode, hint: VoiceTypeHint = VoiceTypeHint.NONE): VoiceMatchOutcome {
        if (ranked.isEmpty()) return VoiceMatchOutcome.NoMatch
        val top = ranked[0]
        val high = if (mode == VoiceMatchMode.EDIT) VoiceMatchThresholds.HIGH_EDIT else VoiceMatchThresholds.HIGH_PLAY
        val needed = if (mode == VoiceMatchMode.EDIT) VoiceMatchThresholds.MARGIN_EDIT else VoiceMatchThresholds.MARGIN_PLAY
        val gap = if (ranked.size > 1) top.score - ranked[1].score else 1.0

        // A bare "play X" that is both a playlist and a song (both clearly above the floor) is asked about.
        var clash = false
        if (hint.kind == null || !hint.explicit) {
            val topKind = top.entry.input.kind
            val other = ranked.firstOrNull { it.entry.input.kind != topKind }
            if (other != null &&
                other.score >= VoiceMatchThresholds.CROSS_KIND_MIN &&
                top.score - other.score < VoiceMatchThresholds.CROSS_KIND_MARGIN
            ) {
                clash = true
            }
        }

        if (top.score >= high && gap >= needed && !clash) return VoiceMatchOutcome.Confident(top, gap)
        return VoiceMatchOutcome.Candidates(ranked.take(VoiceMatchThresholds.MAX_OPTIONS))
    }
}
