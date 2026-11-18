package com.music.bitchord.desktop

/*
 * Voice commands, part V1: turning recognised words into a command. Pure functions, no I/O, no
 * logging. The text passed in is never stored by this file. Rule based on purpose (no AI chat):
 * anything not understood becomes [VoiceCommand.Unknown] and the caller does nothing.
 */

internal sealed interface VoiceCommand {
    /** [title] and [artist] are lower-case, punctuation stripped (the search is case-insensitive). */
    data class Play(val title: String, val artist: String? = null) : VoiceCommand
    object Pause : VoiceCommand
    object Resume : VoiceCommand
    object Next : VoiceCommand
    object Previous : VoiceCommand
    object VolumeUp : VoiceCommand
    object VolumeDown : VoiceCommand
    data class Shuffle(val on: Boolean) : VoiceCommand
    object Like : VoiceCommand

    /** [name] is the playlist's real name from the list passed to the parser. */
    data class AddToPlaylist(val name: String) : VoiceCommand
    data class PlayPlaylist(val name: String) : VoiceCommand
    object WhatsPlaying : VoiceCommand
    object Unknown : VoiceCommand
}

internal object DesktopVoiceCommandParser {

    /** Minimum playlist score (0..1) to accept a match. */
    const val PLAYLIST_THRESHOLD = 0.72

    /** Two playlists closer than this at the top are an ambiguous match, which is rejected. */
    private const val AMBIGUOUS_GAP = 0.05

    private val LEADING_FILLER: List<List<String>> = listOf(
        listOf("hey", "muses"), listOf("hey", "muse"), listOf("hey", "music"),
        listOf("ok", "muses"), listOf("okay", "muses"), listOf("muses"),
        listOf("could", "you", "please"), listOf("can", "you", "please"),
        listOf("would", "you", "please"), listOf("will", "you", "please"),
        listOf("could", "you"), listOf("can", "you"), listOf("would", "you"), listOf("will", "you"),
        listOf("i", "would", "like", "you", "to"), listOf("i", "want", "you", "to"),
        listOf("i", "would", "like", "to"), listOf("i'd", "like", "to"), listOf("i", "want", "to"),
        listOf("go", "ahead", "and"), listOf("lets"), listOf("let's"),
        listOf("please"), listOf("just"),
    ).sortedByDescending { it.size }

    private val TRAILING_FILLER: List<List<String>> = listOf(
        listOf("thank", "you"), listOf("thanks"), listOf("please"), listOf("for", "me"),
    ).sortedByDescending { it.size }

    /** Mishearings of the FIRST word only (a title is never touched). */
    private val HEAD_FIXES: Map<String, String> = mapOf(
        "plays" to "play", "played" to "play",
        "paws" to "pause", "paused" to "pause", "pours" to "pause",
        "necks" to "next", "nex" to "next",
        "previews" to "previous", "skipped" to "skip",
        "shuffles" to "shuffle", "volum" to "volume",
    )

    /** Words that cannot be an artist ("Stand by me" must not become title "stand", artist "me"). */
    private val NOT_AN_ARTIST: Set<String> = setOf(
        "me", "you", "him", "her", "us", "them", "it", "myself", "yourself", "this", "that",
        "the", "a", "an", "my", "your", "now", "tomorrow", "today",
    )

    private val NUMBER_WORDS: Map<String, String> = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    private val PLAYLIST_NOISE: Set<String> = setOf("my", "the", "playlist", "playlists", "a")

    // ---- regular expressions (all run on normalized text) -----------------------------------

    private val WHATS_PLAYING = Regex(
        "^(?:" +
            "what(?:'?s| is) (?:currently |now )?playing(?: now| right now)?|" +
            "what(?:'?s| is) (?:this|that|the current) (?:song|track|one)|" +
            "what(?:'?s| is) this|" +
            "what (?:song|track) is (?:this|playing|on)|" +
            "which (?:song|track) is (?:this|playing)|" +
            "what am i listening to|what are we listening to|" +
            "who (?:sings|is singing|is playing) this(?: song)?|who is this|" +
            "now playing|what(?:'?s| is) on" +
            ")$",
    )

    private val LIKE = Regex(
        "^(?:i )?(?:like|love|heart|favou?rite)(?: (?:this|it|that|the current|the playing|the))?" +
            "(?: (?:song|track|one))?$|^thumbs up$",
    )

    private val LIKE_TARGETS = setOf(
        "favorites", "favourites", "favorite songs", "favourite songs", "liked songs", "liked",
        "likes", "my likes", "favorite", "favourite", "liked music", "liked tracks",
    )

    private val ADD_TO = Regex(
        "^(?:add|put|save|throw|drop) " +
            "(?:(?:this|it|that)(?: (?:song|track|one))? |" +
            "(?:the )?(?:current|playing) (?:song|track) |" +
            "(?:the )?(?:song|track) )?" +
            "(?:to|into|in|on) (.+)$",
    )

    private val PAUSE = Regex(
        "^(?:pause(?: (?:the )?(?:music|song|track|playback|playing|it|this))?|" +
            "stop(?: (?:the )?(?:music|song|track|playback|playing))?|hold on|be quiet)$",
    )

    private val RESUME = Regex(
        "^(?:resume(?: (?:the )?(?:music|song|track|playback|playing|it))?|" +
            "continue(?: (?:playing|playback|the music|the song))?|unpause|un pause|keep playing|carry on|" +
            "play(?: (?:the |some )?(?:music|song|track|playback)| it| again)?|" +
            "start(?: (?:the )?(?:music|playing|playback))?)$",
    )

    private val NEXT = Regex(
        "^(?:next(?: (?:song|track|one))?|skip(?: (?:this |the )?(?:song|track|one))?|skip forward|" +
            "(?:play|go to|go on to) (?:the )?next(?: (?:song|track|one))?)$",
    )

    private val PREVIOUS = Regex(
        "^(?:previous(?: (?:song|track|one))?|prev|go back|back|(?:the )?last (?:song|track)|" +
            "(?:play|go to|go back to) (?:the )?(?:previous|last)(?: (?:song|track|one))?)$",
    )

    private val PLAY_HEAD = Regex("^(?:play(?: me)?|start playing|put on|listen to|hear) (.+)$")

    private val VOLUME_HEADS = setOf(
        "volume", "turn", "make", "raise", "lower", "increase", "decrease", "reduce", "louder",
        "quieter", "softer", "bump", "crank", "drop", "set",
    )
    private val VOLUME_UP = setOf("up", "louder", "higher", "increase", "raise", "more", "crank", "bump")
    private val VOLUME_DOWN = setOf("down", "quieter", "lower", "softer", "decrease", "reduce", "less", "drop", "quiet")

    private val SHUFFLE_WORDS = setOf(
        "shuffle", "shuffling", "shuffled", "unshuffle", "on", "off", "turn", "the", "mode", "enable",
        "disable", "stop", "start", "no", "it", "music", "songs", "queue", "set", "to", "and", "dont",
        "don't",
    )
    private val SHUFFLE_OFF = setOf("off", "disable", "stop", "no", "unshuffle", "dont", "don't")

    // ---- public API -------------------------------------------------------------------------

    /**
     * Lower-case, punctuation to spaces (apostrophes inside words are kept), single spaces.
     * "Play Bad Guy, by Billie Eilish." -> "play bad guy by billie eilish".
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '\'' || ch == '\u2019' -> sb.append('\'')
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ')
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun parse(text: String, playlistNames: List<String>): VoiceCommand {
        val tokens = stripFiller(normalize(text).split(' ').filter { it.isNotEmpty() }).toMutableList()
        if (tokens.isEmpty()) return VoiceCommand.Unknown
        HEAD_FIXES[tokens[0]]?.let { tokens[0] = it }
        val s = tokens.joinToString(" ")

        if (WHATS_PLAYING.matches(s)) return VoiceCommand.WhatsPlaying
        if (LIKE.matches(s)) return VoiceCommand.Like

        ADD_TO.matchEntire(s)?.let { m ->
            val rest = m.groupValues[1].removePrefix("playlist ").trim()
            val plain = rest.split(' ').filter { it !in setOf("my", "the", "playlist") }.joinToString(" ")
            if (plain in LIKE_TARGETS) return VoiceCommand.Like
            val name = matchPlaylist(rest, playlistNames) ?: return VoiceCommand.Unknown
            return VoiceCommand.AddToPlaylist(name)
        }

        parseShuffle(tokens)?.let { return it }
        parseVolume(tokens)?.let { return it }

        if (PAUSE.matches(s)) return VoiceCommand.Pause
        if (RESUME.matches(s)) return VoiceCommand.Resume
        if (NEXT.matches(s)) return VoiceCommand.Next
        if (PREVIOUS.matches(s)) return VoiceCommand.Previous

        val play = PLAY_HEAD.matchEntire(s) ?: return VoiceCommand.Unknown
        return parsePlay(play.groupValues[1].trim(), playlistNames)
    }

    /**
     * The playlist in [names] that best matches [query], or null. Case-insensitive, ignores "my",
     * "the", "playlist". Below [PLAYLIST_THRESHOLD], or when two different playlists tie, null:
     * it never guesses.
     */
    fun matchPlaylist(query: String, names: List<String>): String? {
        if (names.isEmpty()) return null
        val scored = names.map { it to playlistScore(query, it) }.sortedByDescending { it.second }
        val best = scored.first()
        if (best.second < PLAYLIST_THRESHOLD) return null
        val rival = scored.firstOrNull { (name, score) ->
            score >= PLAYLIST_THRESHOLD &&
                best.second - score < AMBIGUOUS_GAP &&
                playlistKey(name) != playlistKey(best.first)
        }
        return if (rival != null) null else best.first
    }

    /** 0..1: 1 means the same words (after dropping "my", "the", "playlist"), typos cost a little. */
    fun playlistScore(query: String, candidate: String): Double {
        val q = playlistTokens(query)
        val c = playlistTokens(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        val q2c = q.sumOf { qt -> c.maxOf { ct -> tokenSimilarity(qt, ct) } } / q.size
        val c2q = c.sumOf { ct -> q.maxOf { qt -> tokenSimilarity(qt, ct) } } / c.size
        val tokenScore = (q2c + c2q) / 2.0
        val whole = similarity(q.joinToString(" "), c.joinToString(" "))
        return maxOf(tokenScore, whole)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun stripFiller(input: List<String>): List<String> {
        var t = input
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (p in LEADING_FILLER) {
                if (t.size >= p.size && t.subList(0, p.size) == p) {
                    t = t.subList(p.size, t.size)
                    changed = true
                    break
                }
            }
            if (changed) continue
            for (p in TRAILING_FILLER) {
                if (t.size > p.size && t.subList(t.size - p.size, t.size) == p) {
                    t = t.subList(0, t.size - p.size)
                    changed = true
                    break
                }
            }
        }
        return t
    }

    private fun parseShuffle(tokens: List<String>): VoiceCommand.Shuffle? {
        if (tokens.size > 6) return null
        if (tokens.none { it == "shuffle" || it == "shuffling" || it == "shuffled" || it == "unshuffle" }) return null
        if (tokens.any { it !in SHUFFLE_WORDS }) return null
        return VoiceCommand.Shuffle(on = tokens.none { it in SHUFFLE_OFF })
    }

    private fun parseVolume(tokens: List<String>): VoiceCommand? {
        if (tokens.size > 8 || tokens[0] !in VOLUME_HEADS) return null
        val up = tokens.any { it in VOLUME_UP }
        val down = tokens.any { it in VOLUME_DOWN }
        if (up == down) return null
        val aboutSound = tokens.size <= 2 || tokens.any {
            it == "volume" || it == "louder" || it == "quieter" || it == "softer" || it == "it" ||
                it == "music" || it == "sound"
        }
        if (!aboutSound) return null
        return if (up) VoiceCommand.VolumeUp else VoiceCommand.VolumeDown
    }

    /** [rest] is everything after the play verb, normalized. */
    private fun parsePlay(rest: String, playlistNames: List<String>): VoiceCommand {
        var tokens = rest.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return VoiceCommand.Unknown

        // Natural phrasing: "put on X" means play X, not a title beginning with "on".
        if (tokens.size > 1 && tokens[0] == "on") tokens = tokens.drop(1)

        // Playlist forms: the word "playlist" is explicit intent; "my X" is tried as a playlist first.
        if ("playlist" in tokens || "playlists" in tokens) {
            val name = matchPlaylist(rest, playlistNames) ?: return VoiceCommand.Unknown
            return VoiceCommand.PlayPlaylist(name)
        }
        if (tokens[0] == "my" && tokens.size > 1) {
            val name = matchPlaylist(rest, playlistNames)
            if (name != null) return VoiceCommand.PlayPlaylist(name)
        }

        // "the song X" / "the track X" is only a filler; a bare "song X" stays (Song 2 by Blur).
        var body = tokens
        if (body.size > 2 && body[0] == "the" && (body[1] == "song" || body[1] == "track")) body = body.drop(2)
        if (body.isEmpty()) return VoiceCommand.Unknown

        val byAt = body.lastIndexOf("by")
        if (byAt > 0 && byAt < body.size - 1) {
            val title = body.subList(0, byAt).joinToString(" ")
            val artist = body.subList(byAt + 1, body.size).joinToString(" ")
            val artistWords = body.subList(byAt + 1, body.size)
            if (!(artistWords.size == 1 && artistWords[0] in NOT_AN_ARTIST)) {
                return VoiceCommand.Play(title, artist)
            }
        }
        // Nothing after the last "by" (or a lone "me"/"you"): the whole text is the title.
        return VoiceCommand.Play(body.joinToString(" "), null)
    }

    private fun playlistTokens(text: String): List<String> =
        normalize(text).replace("'", "").split(' ')
            .filter { it.isNotEmpty() && it !in PLAYLIST_NOISE }
            .map { NUMBER_WORDS[it] ?: it }

    private fun playlistKey(name: String): String = playlistTokens(name).joinToString(" ")

    /** Token similarity: soft (typos) only for words of 4+ letters, short words must match exactly. */
    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 4 || b.length < 4) return 0.0
        val sim = similarity(a, b)
        return if (sim >= 0.6) sim else 0.0
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longest
    }

    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
