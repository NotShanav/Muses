package com.music.bitchord.desktop

/*
 * VOICE FIX X2 (Part A): short-lived context and the small pure helpers around it.
 *
 * [VoiceSession] holds the last played entity, the last candidate list and the last playlist
 * mentioned, IN MEMORY ONLY, for [TTL_MILLIS]. It is never written anywhere, never logged
 * (toString is redacted) and must be cleared by the controller on app exit and whenever voice is
 * switched off ([clear]). It exists so "the first one", "no, the other one" and "add it to that
 * one" can work.
 */

/** One remembered thing. [label] is shown to the owner; [id] is the app's own id. Never logged. */
internal data class VoiceRef(val kind: VoiceEntityKind, val label: String, val id: String) {
    override fun toString(): String = "VoiceRef(" + kind + ")"
}

internal class VoiceSession(
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = TTL_MILLIS,
) {
    companion object {
        /** Two minutes. */
        const val TTL_MILLIS = 120_000L
    }

    private class Stamped<T>(val value: T, val at: Long)

    private var played: Stamped<VoiceRef>? = null
    private var candidates: Stamped<List<VoiceRef>>? = null
    private var playlist: Stamped<VoiceRef>? = null

    private fun <T> fresh(item: Stamped<T>?): T? {
        if (item == null) return null
        return if (now() - item.at <= ttlMillis) item.value else null
    }

    @Synchronized
    fun rememberPlayed(ref: VoiceRef) {
        played = Stamped(ref, now())
        if (ref.kind == VoiceEntityKind.PLAYLIST) playlist = Stamped(ref, now())
    }

    @Synchronized
    fun rememberPlaylist(ref: VoiceRef) {
        playlist = Stamped(ref, now())
    }

    /** Keeps at most [VoiceMatchThresholds.MAX_OPTIONS] (the number the overlay shows). */
    @Synchronized
    fun rememberCandidates(list: List<VoiceRef>) {
        candidates = if (list.isEmpty()) null else Stamped(list.take(VoiceMatchThresholds.MAX_OPTIONS), now())
    }

    @Synchronized
    fun lastPlayed(): VoiceRef? = fresh(played)

    @Synchronized
    fun lastPlaylist(): VoiceRef? = fresh(playlist)

    @Synchronized
    fun lastCandidates(): List<VoiceRef> = fresh(candidates) ?: emptyList()

    /** "The first one" is [zeroBased] 0. Null when there are no live candidates or the number is too big. */
    @Synchronized
    fun choose(zeroBased: Int): VoiceRef? {
        if (zeroBased < 0) return null
        return fresh(candidates)?.getOrNull(zeroBased)
    }

    /**
     * "No, the other one": the first live candidate that is not the thing just played. Null when
     * nothing was played (it would be a guess) or there is no other candidate.
     */
    @Synchronized
    fun other(): VoiceRef? {
        val exclude = fresh(played) ?: return null
        return fresh(candidates)?.firstOrNull { it != exclude }
    }

    @Synchronized
    fun clear() {
        played = null
        candidates = null
        playlist = null
    }

    @Synchronized
    fun isEmpty(): Boolean = fresh(played) == null && fresh(candidates) == null && fresh(playlist) == null

    override fun toString(): String = "VoiceSession(redacted)"
}

/** What a "follow-up" phrase asks for. */
internal sealed interface VoiceChoice {
    /** "The first one" is zeroBased 0. At most the third (index 2). */
    data class Index(val zeroBased: Int) : VoiceChoice

    /** "No, the other one". */
    object Other : VoiceChoice
}

internal object VoiceChoicePhrases {

    private val LEAD = setOf(
        "no", "nope", "nah", "actually", "yeah", "yes", "i", "mean", "meant", "want", "the", "number", "option",
        "choice", "pick", "choose", "select", "go", "with", "um", "uh", "please", "just",
    )
    private val ORDINAL: Map<String, Int> = mapOf(
        "first" to 0, "1st" to 0, "one" to 0, "1" to 0,
        "second" to 1, "2nd" to 1, "two" to 1, "2" to 1,
        "third" to 2, "3rd" to 2, "three" to 2, "3" to 2,
    )
    private val TAILS = setOf(listOf<String>(), listOf("one"), listOf("option"), listOf("choice"))

    /**
     * [words] come from [VoiceText.words]. Returns null unless the WHOLE phrase is a follow-up, so
     * "play the first one hundred songs" or "next" are never taken for one. Whether there are live
     * candidates is the controller's question, not this function's.
     */
    fun parse(words: List<String>): VoiceChoice? {
        var start = 0
        while (start < words.size && words[start] in LEAD) start += 1
        val rest = words.drop(start)
        if (rest.isEmpty()) return null

        if (rest[0] == "other" && rest.drop(1) in TAILS) return VoiceChoice.Other
        if (rest == listOf("not", "that", "one") || rest == listOf("not", "that") ||
            rest == listOf("different", "one") || rest == listOf("different")
        ) {
            return VoiceChoice.Other
        }

        val index = ORDINAL[rest[0]] ?: return null
        return if (rest.drop(1) in TAILS) VoiceChoice.Index(index) else null
    }

    /** True for "that one", "that playlist", "the same playlist": the destination words of "add it to ...". */
    fun isLastPlaylistReference(words: List<String>): Boolean {
        val w = words.filter { it != "the" && it != "my" && it != "a" }
        return w == listOf("that") || w == listOf("that", "one") || w == listOf("that", "playlist") ||
            w == listOf("same") || w == listOf("same", "one") || w == listOf("same", "playlist") ||
            w == listOf("last", "playlist") || w == listOf("last", "one")
    }
}

/** Fillers and self-corrections in the recognised words. Pure; works on [VoiceText.words]. */
internal object VoiceSelfRepair {

    private val FILLERS = setOf("um", "umm", "uh", "uhh", "er", "erm", "hmm", "mm", "ah")

    /** "like" is a filler only after these (so "i like this", "like a prayer", "feel like" survive). */
    private val LIKE_FILLER_AFTER = setOf("by", "and", "but", "so", "then", "is", "was")

    /** "sorry" and "no" are also song titles, so they only count as a repair when a command or a "the/my" follows. */
    private val STRONG_MARKERS: List<List<String>> = listOf(
        listOf("scratch", "that"), listOf("or", "rather"), listOf("i", "mean"), listOf("actually"),
    )
    private val WEAK_MARKERS: List<List<String>> = listOf(listOf("no"), listOf("wait"), listOf("sorry"))

    /** After a weak marker the tail must look like a new command or a corrected object. */
    private val WEAK_TAIL_STARTS = setOf(
        "play", "add", "put", "pause", "stop", "next", "previous", "skip", "volume", "shuffle", "like", "resume",
        "turn", "set", "make", "what", "who", "throw", "queue", "start", "save", "the", "my", "i",
    )
    private val COMMAND_HEADS = setOf(
        "play", "add", "put", "pause", "stop", "next", "previous", "skip", "volume", "shuffle", "like", "resume",
        "turn", "set", "make", "what", "who", "throw", "queue", "start", "save", "hear", "listen", "go",
    )
    private val ADD_VERBS = setOf("add", "put", "save", "throw", "drop", "stick", "keep", "toss", "send")
    private val PREPOSITIONS = setOf("to", "into", "in", "onto", "on", "under")

    fun apply(words: List<String>): List<String> {
        val cleaned = dropFillers(words)
        var p = cleaned.size - 1
        while (p >= 0) {
            val strong = markerAt(cleaned, p, STRONG_MARKERS)
            val weak = if (strong == 0) markerAt(cleaned, p, WEAK_MARKERS) else 0
            val len = if (strong > 0) strong else weak
            if (len > 0) {
                val tail = cleaned.subList(p + len, cleaned.size)
                val acceptable = tail.isNotEmpty() && (strong > 0 || tail[0] in WEAK_TAIL_STARTS) &&
                    !(cleaned.getOrNull(p) == "no" && tail[0] == "shuffle")
                if (acceptable) return rebuild(cleaned.subList(0, p), tail)
            }
            p -= 1
        }
        return cleaned
    }

    private fun dropFillers(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        for ((i, w) in words.withIndex()) {
            if (w in FILLERS) continue
            if (w == "like" && i > 0 && i < words.size - 1 && words[i - 1] in LIKE_FILLER_AFTER) continue
            out.add(w)
        }
        return out
    }

    private fun markerAt(words: List<String>, at: Int, markers: List<List<String>>): Int {
        for (marker in markers) {
            if (at + marker.size <= words.size && words.subList(at, at + marker.size) == marker) return marker.size
        }
        return 0
    }

    /** [before] is what was said before the marker; [tail] is the correction. */
    private fun rebuild(before: List<String>, tail: List<String>): List<String> {
        if (tail[0] in COMMAND_HEADS) return tail
        val play = before.indexOfFirst { it == "play" || it == "queue" }
        if (play >= 0) return before.subList(0, play + 1) + tail
        val verb = before.indexOfFirst { it in ADD_VERBS }
        if (verb >= 0) {
            val prep = before.withIndex().indexOfFirst { (i, w) -> i > verb && w in PREPOSITIONS }
            if (prep >= 0) return before.subList(0, prep + 1) + tail
        }
        val volume = before.indexOfFirst { it == "volume" }
        if (volume >= 0) {
            val prep = before.withIndex().indexOfFirst { (i, w) -> i > volume && (w == "to" || w == "at") }
            if (prep >= 0) return before.subList(0, prep + 1) + tail
        }
        return tail
    }
}

/** Absolute volume ("volume to forty"). Pure. */
internal object VoiceNumbers {

    private val TIME_WORDS = setOf("minute", "minutes", "second", "seconds", "hour", "hours", "timer", "alarm")
    private val SET_HEADS = setOf("turn", "set", "make", "bring", "put")

    /** A percent 0..100, or null when the words are not an absolute-volume request. [words] from [VoiceText.words]. */
    fun volumePercent(words: List<String>): Int? {
        if (words.isEmpty() || words.any { it in TIME_WORDS }) return null
        val converted = VoiceText.numbersToDigits(words)
        val digits = converted.filter { w -> w.all { it.isDigit() } }
        if (digits.size != 1) return null
        val value = digits[0].toIntOrNull() ?: return null
        if (value !in 0..100) return null
        val aboutSound = converted.any { it == "volume" || it == "sound" }
        val turnItTo = converted.take(3).any { it in SET_HEADS } && converted.contains("it") && converted.contains("to")
        return if (aboutSound || turnItTo) value else null
    }
}
