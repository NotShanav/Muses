package com.music.bitchord.desktop

import java.text.Normalizer

/*
 * VOICE FIX X2 (Part A): spoken-text normalisation, a Metaphone-style phonetic key and the alias
 * table. Pure functions over strings: no UI, no disk, no network, no logging. Nothing here
 * remembers anything.
 *
 * The parser's own `normalize` / `playlistTokens` are NOT replaced; the matcher and the index use
 * this file so that BOTH sides of a comparison (what was said, what is in the library) go through
 * exactly the same steps.
 */

/** Small alias table, kept as data so it is easy to extend. Keys and values are already normalised. */
internal object VoiceAliasTable {
    /** Multi-word keys are allowed (longest key wins). Value is the canonical spelling. */
    val ENTRIES: Map<String, String> = mapOf(
        "work out" to "workout",
        "work outs" to "workouts",
        "billy" to "billie",
        "back street" to "backstreet",
        "ac dc" to "acdc",
        "lo fi" to "lofi",
        "k pop" to "kpop",
        "j pop" to "jpop",
        "r and b" to "rnb",
        "hip hop" to "hiphop",
    )
}

internal object VoiceText {

    /** Dropped from names and from spoken queries so "my gym playlist" equals "Gym". */
    private val NOISE: Set<String> = setOf("the", "my", "playlist", "playlists", "song", "songs", "track", "tracks")

    private val COMBINING_MARKS = Regex("\\p{M}+")

    private val ONES: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /**
     * Lower case, accents off, "&" read as "and", emoji and punctuation dropped, apostrophes removed
     * ("don't" is "dont"). Returns every word, noise included. Numbers are NOT converted here.
     */
    fun words(text: String): List<String> {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        val sb = StringBuilder(folded.length + 8)
        for (ch in folded.lowercase()) {
            if (ch == '&') {
                sb.append(" and ")
            } else if (ch == '\'' || ch == '\u2019') {
                // dropped: "don't" -> "dont"
            } else if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }
    }

    /** "twenty one" -> "21", "forty" -> "40", "one hundred" -> "100". Digits pass through unchanged. */
    fun numbersToDigits(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val tens = TENS[w]
            val ones = ONES[w]
            var value: Int? = null
            var used = 1
            if (tens != null) {
                val next = if (i + 1 < words.size) ONES[words[i + 1]] else null
                if (next != null && next in 1..9) {
                    value = tens + next
                    used = 2
                } else {
                    value = tens
                }
            } else if (ones != null) {
                value = ones
            }
            if (value != null) {
                val after = i + used
                if (after < words.size && words[after] == "hundred" && value in 1..9) {
                    value *= 100
                    used += 1
                }
                out.add(value.toString())
                i += used
            } else {
                out.add(w)
                i += 1
            }
        }
        return out
    }

    /** Applies [VoiceAliasTable] (longest key first, three words down to one). */
    fun applyAliases(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var replaced = false
            var len = 3
            while (len >= 1 && !replaced) {
                if (i + len <= words.size) {
                    val key = words.subList(i, i + len).joinToString(" ")
                    val value = VoiceAliasTable.ENTRIES[key]
                    if (value != null) {
                        out.add(value)
                        i += len
                        replaced = true
                    }
                }
                len -= 1
            }
            if (!replaced) {
                out.add(words[i])
                i += 1
            }
        }
        return out
    }

    /**
     * The tokens used for matching: words, numbers as digits, aliases applied, noise dropped. When
     * everything was noise (a playlist really called "The Song") the noise is kept so the name
     * still has tokens.
     */
    fun tokens(text: String): List<String> {
        val all = applyAliases(numbersToDigits(words(text)))
        val kept = all.filter { it !in NOISE }
        return if (kept.isEmpty()) all else kept
    }
}

/**
 * A compact English phonetic key in the Metaphone family, pure Kotlin. Not Soundex: it keeps
 * consonant structure ("billy" and "billie" are both "BL", "work out" joined is "WRKT"). It is
 * deliberately small; it only has to make mis-heard spellings of the same sound collide.
 */
internal object VoicePhonetic {

    private const val VOWELS = "aeiou"

    /** Key of one word. Letters only (a to z); empty when the word has none. */
    fun key(word: String): String {
        val w = StringBuilder()
        for (ch in word.lowercase()) if (ch in 'a'..'z') w.append(ch)
        val s = w.toString()
        if (s.isEmpty()) return ""

        // Initial exceptions.
        var start = 0
        val out = StringBuilder()
        if (s.length >= 2) {
            val two = s.substring(0, 2)
            when (two) {
                "ae", "gn", "kn", "pn", "wr" -> start = 1
                "wh" -> {
                    out.append('W')
                    start = 2
                }
            }
        }
        if (start == 0 && s[0] == 'x') {
            out.append('S')
            start = 1
        }

        var i = start
        while (i < s.length) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            val next2 = if (i + 2 < s.length) s[i + 2] else ' '

            // Doubled letters count once (except "c", as in "accept").
            if (c == prev && c != 'c') {
                i += 1
                continue
            }

            when (c) {
                'a', 'e', 'i', 'o', 'u' -> if (i == start && out.isEmpty()) out.append('A')
                'b' -> if (!(prev == 'm' && i == s.length - 1)) out.append('B')
                'c' -> {
                    if (next == 'i' && next2 == 'a') {
                        out.append('X')
                    } else if (next == 'h') {
                        out.append(if (prev == 's') 'K' else 'X')
                        i += 1
                    } else if (next == 'i' || next == 'e' || next == 'y') {
                        if (prev != 's') out.append('S')
                    } else {
                        out.append('K')
                    }
                }
                'd' -> {
                    if (next == 'g' && (next2 == 'e' || next2 == 'i' || next2 == 'y')) {
                        out.append('J')
                        i += 1
                    } else {
                        out.append('T')
                    }
                }
                'g' -> {
                    if (next == 'h') {
                        // "gh" before a vowel is a hard sound; otherwise silent ("night", "though").
                        if (next2 != ' ' && next2 in VOWELS) {
                            out.append('K')
                        }
                        i += 1
                    } else if (next == 'n' && i + 2 >= s.length) {
                        // silent: "sign"
                    } else if (next == 'i' || next == 'e' || next == 'y') {
                        out.append('J')
                    } else {
                        out.append('K')
                    }
                }
                'h' -> {
                    val beforeVowel = next != ' ' && next in VOWELS
                    val afterSoftener = prev == 'c' || prev == 'g' || prev == 'p' || prev == 's' || prev == 't'
                    if (beforeVowel && !afterSoftener) out.append('H')
                }
                'k' -> if (prev != 'c') out.append('K')
                'p' -> {
                    if (next == 'h') {
                        out.append('F')
                        i += 1
                    } else {
                        out.append('P')
                    }
                }
                'q' -> out.append('K')
                's' -> {
                    if (next == 'h') {
                        out.append('X')
                        i += 1
                    } else if (next == 'i' && (next2 == 'o' || next2 == 'a')) {
                        out.append('X')
                    } else {
                        out.append('S')
                    }
                }
                't' -> {
                    if (next == 'i' && (next2 == 'o' || next2 == 'a')) {
                        out.append('X')
                    } else if (next == 'h') {
                        out.append('0')
                        i += 1
                    } else if (!(next == 'c' && next2 == 'h')) {
                        out.append('T')
                    }
                }
                'v' -> out.append('F')
                'w' -> if (next != ' ' && next in VOWELS) out.append('W')
                'x' -> out.append("KS")
                'y' -> if (next != ' ' && next in VOWELS) out.append('Y')
                'z' -> out.append('S')
                else -> out.append(c.uppercaseChar()) // f j l m n r
            }
            i += 1
        }
        return out.toString()
    }

    /** One key per token (same order). */
    fun keys(tokens: List<String>): List<String> = tokens.map { key(it) }

    /** Key of the tokens run together, so "work out" and "workout" meet. */
    fun compactKey(tokens: List<String>): String = key(tokens.joinToString(""))
}
