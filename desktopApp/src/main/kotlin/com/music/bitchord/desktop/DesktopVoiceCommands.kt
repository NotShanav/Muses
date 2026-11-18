package com.music.bitchord.desktop

/*
 * Voice commands: offline natural-language interpretation.
 *
 * This is deliberately not a whitelist of complete utterances. It recognises action families,
 * conversational lead-ins, references to the current track, and entities grounded against the
 * user's actual playlist names. The final command remains a typed, deterministic action; no text is
 * evaluated as code and no transcript is persisted here.
 */

internal sealed interface VoiceCommand {
    /** [title] and [artist] are normalised; search is case-insensitive. */
    data class Play(val title: String, val artist: String? = null) : VoiceCommand
    /** A broad discovery request such as "play something upbeat"; candidates are confirmed in UI. */
    data class SearchAndPlay(val query: String) : VoiceCommand
    object Pause : VoiceCommand
    object Resume : VoiceCommand
    object Next : VoiceCommand
    object Previous : VoiceCommand
    object VolumeUp : VoiceCommand
    object VolumeDown : VoiceCommand
    data class Shuffle(val on: Boolean) : VoiceCommand
    object Like : VoiceCommand
    data class AddToPlaylist(val name: String) : VoiceCommand
    data class PlayPlaylist(val name: String) : VoiceCommand
    // VOICE FIX X2 hook: absolute volume and follow-up choices are parser results, not new intents.
    data class SetVolume(val percent: Int) : VoiceCommand
    data class Choose(val number: Int) : VoiceCommand
    object ChooseOther : VoiceCommand
    object AddToLastPlaylist : VoiceCommand
    object WhatsPlaying : VoiceCommand
    object Unknown : VoiceCommand
}

internal object DesktopVoiceCommandParser {

    /** Minimum playlist score (0..1) to accept a match. */
    const val PLAYLIST_THRESHOLD = 0.72
    private const val AMBIGUOUS_GAP = 0.05

    private val LEADING_FILLER: List<List<String>> = listOf(
        listOf("hey", "muses"), listOf("hey", "muse"), listOf("hey", "music"),
        listOf("ok", "muses"), listOf("okay", "muses"), listOf("muses"),
        listOf("could", "you", "please"), listOf("can", "you", "please"),
        listOf("would", "you", "please"), listOf("will", "you", "please"),
        listOf("could", "you"), listOf("can", "you"), listOf("would", "you"), listOf("will", "you"),
        listOf("could", "we"), listOf("can", "we"), listOf("would", "we"), listOf("may", "we"),
        listOf("i", "would", "like", "you", "to"), listOf("i", "want", "you", "to"),
        listOf("i", "would", "like", "to"), listOf("i'd", "like", "to"), listOf("i", "want", "to"),
        listOf("i", "wanna"), listOf("would", "you", "mind"),
        listOf("i", "was", "wondering", "if", "you", "could"),
        listOf("go", "ahead", "and"), listOf("let's"), listOf("lets"),
        listOf("actually"), listOf("maybe"), listOf("please"), listOf("just"),
    ).sortedByDescending { it.size }

    /**
     * Conversational openings that imply a play request without a literal play verb ("I'm in the mood
     * for…", "I was thinking of playing…"). Built from parts (subject + state + optional listening verb)
     * rather than enumerated, so a new subject or tense ("I was", "we're") does not need its own entry.
     */
    private const val LISTEN_VERB = "(?:listening to|listen to|hearing|hear|playing|play)"
    private val PLAY_PRELUDE_STATE = Regex(
        "^(?:" +
            "(?:i'm|im|i am|i was|we're|we are)(?: (?:really|just|kind of))? " +
            "(?:in the mood(?: for| to)?|feeling like|thinking (?:of|about)|up for|craving|after|into|looking for)" +
            "|(?:i|we)(?: (?:really|just))? (?:feel like|fancy|crave|could go for)" +
            "|(?:i'd|i would|we'd|we would) be up for" +
            "|(?:how|what) about" +
            ")(?= |$)(?: $LISTEN_VERB(?= |$))?",
    )
    /** "I'd love to hear…", "I could listen to…": these only count as a play request with a listening verb. */
    private val PLAY_PRELUDE_DESIRE = Regex(
        "^(?:i|we)(?:'d| would)?(?: (?:really|just))? (?:love to|like to|want to|wanna|could) $LISTEN_VERB(?= |$)",
    )

    private fun playPreludeWords(tokens: List<String>): Int {
        val text = tokens.joinToString(" ")
        val hit = PLAY_PRELUDE_STATE.find(text) ?: PLAY_PRELUDE_DESIRE.find(text) ?: return 0
        return hit.value.split(' ').size
    }

    private val TRAILING_FILLER: List<List<String>> = listOf(
        listOf("thank", "you"), listOf("thanks"), listOf("please"), listOf("for", "me"),
    ).sortedByDescending { it.size }

    /** Only common ASR confusions of command words are corrected, never arbitrary title words. */
    private val HEAD_FIXES: Map<String, String> = mapOf(
        "plays" to "play", "played" to "play", "paws" to "pause", "paused" to "pause",
        "pours" to "pause", "necks" to "next", "nex" to "next", "previews" to "previous",
        "skipped" to "skip", "shuffles" to "shuffle", "volum" to "volume",
    )

    private val NOT_AN_ARTIST: Set<String> = setOf(
        "me", "you", "him", "her", "us", "them", "it", "myself", "yourself", "this", "that",
        "the", "a", "an", "my", "your", "now", "tomorrow", "today",
    )

    private val NUMBER_WORDS: Map<String, String> = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    private val PLAYLIST_NOISE: Set<String> = setOf(
        "my", "the", "playlist", "playlists", "a", "called", "named",
    )
    private val PREPOSITIONS = setOf("to", "into", "in", "on", "onto", "under")

    private val WHATS_PLAYING = Regex(
        "^(?:what(?:'?s| is) (?:currently |now )?playing(?: now| right now)?|" +
            "what(?:'?s| is) (?:this|that|the current) (?:song|track|one)|" +
            "what(?:'?s| is) this|what (?:song|track) is (?:this|playing|on)|" +
            "which (?:song|track) is (?:this|playing)|what am i listening to|what are we listening to|" +
            "who (?:sings|is singing|is playing) this(?: song)?|who is this|" +
            "now playing|what(?:'?s| is) on|what track is on|what is the current track)$",
    )

    private val LIKE = Regex(
        "^(?:i )?(?:like|love|heart|favou?rite)(?: (?:this|it|that|the current|the playing|the))?" +
            "(?: (?:song|track|one))?$|^thumbs up$|^(?:save|keep) (?:this|it|that)(?: (?:song|track|one))?$",
    )
    private val LIKE_TARGETS = setOf(
        "favorites", "favourites", "favorite songs", "favourite songs", "liked songs", "liked",
        "likes", "my likes", "favorite", "favourite", "liked music", "liked tracks", "liked playlist",
    )

    /** "add [this song | the song | the current song | it] to [my X playlist]". */
    private val ADD_TO = Regex(
        "^(?:add|put|save|throw|drop|stick|keep|toss|send) " +
            "(?:(?:this|that|that's|it|the|current|currently|now|playing|song|track|one) )*" +
            "(?:to|into|in|on|onto|under) (.+)$",
    )

    private val PAUSE = Regex(
        "^(?:pause(?: (?:the )?(?:music|song|track|playback|playing|it|this))?" +
            "(?: for (?:a )?(?:second|moment|bit|while))?|" +
            "stop(?: (?:the )?(?:music|song|track|playback|playing|it))?|" +
            "hold on|hold the music|take a break|be quiet|quiet the music|kill the music)$",
    )

    private val RESUME = Regex(
        "^(?:resume(?: (?:the )?(?:music|song|track|playback|playing|it))?|" +
            "continue(?: (?:playing|playback|the music|the song|where you left off))?|" +
            "unpause|un pause|keep playing|keep it going|carry on|start back up|" +
            "play(?: (?:the |some )?(?:music|song|track|playback)| it| again)?|" +
            "start(?: (?:the )?(?:music|playing|playback))?|let it play|go on)$",
    )

    private val NEXT = Regex(
        "^(?:next(?: (?:song|track|one))?|skip(?: (?:this |the )?(?:song|track|one))?|" +
            "skip forward|move on|onto the next(?: (?:one|song|track))?|" +
            "(?:play|go to|go on to|move to) (?:the )?next(?: (?:song|track|one))?|another song|next one please)$",
    )

    private val PREVIOUS = Regex(
        "^(?:(?:the )?previous(?: (?:song|track|one))?|prev|go back|back|" +
            "(?:the )?last (?:song|track|one)|" +
            "(?:play|go to|go back to) (?:the )?(?:previous|last)(?: (?:song|track|one))?)$",
    )

    /** Leading "songs from" / "some music off" wrapper around a playlist reference. */
    private val SOURCE_PREFIX = Regex(
        "^(?:(?:some|any|a few) )?(?:(?:songs|music|tracks|tunes|stuff|something) )?" +
            "(?:from|off|out of|in) (?=my |the |our )",
    )

    private val PLAY_HEAD = Regex(
        "^(?:play(?: me)?|start playing|start(?= my | .*\\b(?:playlist|mix|mixtape|collection)$)|put on|throw on|" +
            "queue up|listen to|hear|" +
            "let me hear|give me|switch to|turn on) (.+)$",
    )

    private val VOLUME_HEADS = setOf(
        "volume", "turn", "make", "raise", "lower", "increase", "decrease", "reduce", "louder",
        "quieter", "softer", "bump", "crank", "drop", "set", "bring",
    )
    private val VOLUME_UP = setOf("up", "louder", "higher", "increase", "raise", "more", "crank", "bump")
    private val VOLUME_DOWN = setOf("down", "quieter", "lower", "softer", "decrease", "reduce", "less", "drop", "quiet")

    private val SHUFFLE_WORDS = setOf(
        "shuffle", "shuffling", "shuffled", "unshuffle", "on", "off", "turn", "the", "mode", "enable",
        "disable", "stop", "start", "no", "it", "music", "songs", "queue", "set", "to", "and", "dont", "don't",
    )
    private val SHUFFLE_OFF = setOf("off", "disable", "stop", "no", "unshuffle", "dont", "don't")

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '\'' || ch == '\u2019' -> sb.append('\'')
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    // VOICE V5 hook: intent recognition is independent of library availability; entity resolution
    // is retried against the live library by the action adapter.
    fun parse(text: String, playlistNames: List<String>): VoiceCommand {
        // VOICE FIX X2 hook: repair ASR corrections and resolve short follow-ups before the old parser.
        val words = VoiceSelfRepair.apply(VoiceText.words(text))
        if (words.isEmpty()) return VoiceCommand.Unknown
        when (val choice = VoiceChoicePhrases.parse(words)) {
            is VoiceChoice.Index -> return VoiceCommand.Choose(choice.zeroBased + 1)
            VoiceChoice.Other -> return VoiceCommand.ChooseOther
            null -> Unit
        }
        VoiceNumbers.volumePercent(words)?.let { return VoiceCommand.SetVolume(it) }
        var tokens = normalize(words.joinToString(" ")).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return VoiceCommand.Unknown

        var impliedPlay = false
        var changed = true
        while (changed && tokens.isNotEmpty()) {
            changed = false
            val moodWords = playPreludeWords(tokens)
            if (moodWords > 0) {
                tokens = tokens.drop(moodWords)
                impliedPlay = true
                changed = true
                continue
            }
            val filler = LEADING_FILLER.firstOrNull { tokens.startsWith(it) }
            if (filler != null) {
                tokens = tokens.drop(filler.size)
                changed = true
                continue
            }
            val trailing = TRAILING_FILLER.firstOrNull { tokens.size > it.size && tokens.endsWith(it) }
            if (trailing != null) {
                tokens = tokens.dropLast(trailing.size)
                changed = true
            }
        }
        if (tokens.isEmpty()) return VoiceCommand.Unknown
        tokens = tokens.toMutableList().also { list -> HEAD_FIXES[list[0]]?.let { list[0] = it } }
        val s = tokens.joinToString(" ")

        if (WHATS_PLAYING.matches(s)) return VoiceCommand.WhatsPlaying
        if (LIKE.matches(s)) return VoiceCommand.Like

        // "Put on" and "throw on" introduce playback; they are not playlist-add operations.
        if (!s.startsWith("put on ") && !s.startsWith("throw on ")) ADD_TO.matchEntire(s)?.let { match ->
            val rest = match.groupValues[1].trim()
            // VOICE FIX X2 hook: resolve conversational references from the in-memory session.
            if (VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words(rest))) return VoiceCommand.AddToLastPlaylist
            val plain = playlistTokens(rest).joinToString(" ")
            if (plain in LIKE_TARGETS || plain in setOf("favorites", "favourites", "liked songs")) return VoiceCommand.Like
            val name = matchPlaylist(rest, playlistNames) ?: playlistReference(rest)
            return if (name.isBlank()) VoiceCommand.Unknown else VoiceCommand.AddToPlaylist(name)
        }

        parseShuffle(tokens)?.let { return it }
        parseVolume(tokens)?.let { return it }
        if (PAUSE.matches(s)) return VoiceCommand.Pause
        if (RESUME.matches(s)) return VoiceCommand.Resume
        if (NEXT.matches(s)) return VoiceCommand.Next
        if (PREVIOUS.matches(s)) return VoiceCommand.Previous

        val play = PLAY_HEAD.matchEntire(s)
        if (play != null) {
            // VOICE FIX X2 hook: "turn on" and "switch to" only introduce grounded playlist/music requests.
            val rest = play.groupValues[1].trim()
            val looseHead = s.startsWith("turn on ") || s.startsWith("switch to ")
            val groundedPlaylist = impliedPlay ||
                ((rest.startsWith("my ") || rest.startsWith("the ") || rest.startsWith("some ")) &&
                    Regex("\\b(?:playlist|mix|mixtape|collection|music|radio)\\b").containsMatchIn(rest))
            if (looseHead && !groundedPlaylist) return VoiceCommand.Unknown
            return parsePlay(rest, playlistNames, impliedPlay || (looseHead && groundedPlaylist))
        }
        if (impliedPlay) return parsePlay(s, playlistNames, true)
        return VoiceCommand.Unknown
    }

    fun matchPlaylist(query: String, names: List<String>): String? {
        if (names.isEmpty()) return null
        val best = names.map { it to playlistScore(query, it) }.maxByOrNull { it.second } ?: return null
        if (best.second < PLAYLIST_THRESHOLD) return null
        return if (ambiguousPlaylists(query, names).isNotEmpty()) null else best.first
    }

    /**
     * Distinct playlists that match [query] about equally well. More than one entry means the request is
     * ambiguous: the caller should offer these rather than guess. Empty when there is a clear winner or no match.
     */
    fun ambiguousPlaylists(query: String, names: List<String>): List<String> {
        val scored = names.map { it to playlistScore(query, it) }.filter { it.second >= PLAYLIST_THRESHOLD }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        val tied = scored.filter { top - it.second < AMBIGUOUS_GAP }.distinctBy { playlistKey(it.first) }.map { it.first }
        return if (tied.size > 1) tied else emptyList()
    }

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

    private fun parsePlay(
        rest: String,
        playlistNames: List<String>,
        preferPlaylist: Boolean = false,
    ): VoiceCommand {
        var body = rest.trim()
        if (body.isBlank()) return VoiceCommand.Unknown

        // "play songs from my workout playlist" names the same playlist as "play my workout playlist".
        val unwrapped = SOURCE_PREFIX.replaceFirst(body, "")

        // Natural speech often calls the same playlist a "mix", "collection", or simply "my X".
        val explicitPlaylist = Regex("\\b(?:playlist|playlists|collection|mix|mixtape)\\b").containsMatchIn(unwrapped)
        if (explicitPlaylist) {
            // Understanding the request must not depend on the library snapshot being available.
            // Resolve against current library names at action time; retain the spoken name when no
            // catalogue match is available so a missing playlist is reported as such, not as ASR failure.
            val name = matchPlaylist(unwrapped, playlistNames) ?: playlistReference(unwrapped)
            return if (name.isBlank()) VoiceCommand.Unknown else VoiceCommand.PlayPlaylist(name)
        }
        if (unwrapped.startsWith("my ") || preferPlaylist) {
            matchPlaylist(unwrapped, playlistNames)?.let { return VoiceCommand.PlayPlaylist(it) }
        }

        val tokens = body.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return VoiceCommand.Unknown
        if (tokens.size > 2 && tokens[0] == "the" && (tokens[1] == "song" || tokens[1] == "track")) {
            body = tokens.drop(2).joinToString(" ")
        } else if (tokens.size > 2 && tokens[0] == "song" && tokens[1] in setOf("called", "named", "titled")) {
            body = tokens.drop(2).joinToString(" ")
        }

        // VOICE FIX X2 hook: keep the established resume meaning ahead of the broad "some" search wrapper.
        if (body in setOf("music", "the music", "some music", "songs", "some songs", "the songs")) {
            return VoiceCommand.Resume
        }
        val generic = listOf("something", "anything", "some", "a song", "a track", "some tunes")
            .firstOrNull { body == it || body.startsWith("$it ") }
        if (generic != null) {
            val query = body.removePrefix(generic).trim().removePrefix("that is ").removePrefix("that's ").trim()
            return if (query.isBlank()) VoiceCommand.SearchAndPlay("music") else VoiceCommand.SearchAndPlay(query)
        }
        if (body in setOf("music", "the music", "some music", "songs", "some songs", "the songs")) {
            return VoiceCommand.Resume
        }

        // Split on the LAST "by", preserving titles such as "Stand by Me".
        val words = body.split(' ').filter { it.isNotEmpty() }
        val byAt = words.lastIndexOf("by")
        if (byAt > 0 && byAt < words.size - 1) {
            val title = words.subList(0, byAt).joinToString(" ")
            val artistWords = words.subList(byAt + 1, words.size)
            if (!(artistWords.size == 1 && artistWords[0] in NOT_AN_ARTIST)) {
                return VoiceCommand.Play(title, artistWords.joinToString(" "))
            }
        }
        return VoiceCommand.Play(body, null)
    }

    private fun parseShuffle(tokens: List<String>): VoiceCommand.Shuffle? {
        if (tokens.size > 8) return null
        if (tokens.none { it == "shuffle" || it == "shuffling" || it == "shuffled" || it == "unshuffle" }) return null
        if (tokens.any { it !in SHUFFLE_WORDS }) return null
        return VoiceCommand.Shuffle(on = tokens.none { it in SHUFFLE_OFF })
    }

    private fun parseVolume(tokens: List<String>): VoiceCommand? {
        if (tokens.size > 10) return null
        val gentleLead = tokens.size >= 2 && tokens[0] == "a" && tokens[1] in setOf("bit", "little")
        if (tokens[0] !in VOLUME_HEADS && !gentleLead) return null
        val up = tokens.any { it in VOLUME_UP }
        val down = tokens.any { it in VOLUME_DOWN }
        if (up == down) return null
        val aboutSound = tokens.size <= 2 || tokens.any {
            it == "volume" || it == "louder" || it == "quieter" || it == "softer" || it == "it" ||
                it == "music" || it == "sound" || it == "bit" || it == "little"
        }
        if (!aboutSound) return null
        return if (up) VoiceCommand.VolumeUp else VoiceCommand.VolumeDown
    }

    /** Removes grammatical wrappers while preserving the actual playlist title words. */
    private fun playlistReference(text: String): String {
        val words = normalize(text).replace("'", "").split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.firstOrNull() in setOf("my", "the", "a", "an")) words.removeAt(0)
        if (words.firstOrNull() in setOf("playlist", "playlists", "mix", "mixtape", "collection")) {
            words.removeAt(0)
            if (words.firstOrNull() in setOf("called", "named", "titled")) words.removeAt(0)
        }
        if (words.lastOrNull() in setOf("playlist", "playlists", "mix", "mixtape", "collection")) {
            words.removeAt(words.lastIndex)
        }
        while (words.firstOrNull() in setOf("my", "the", "a", "an")) words.removeAt(0)
        while (words.lastOrNull() in setOf("please", "thanks", "thank", "you")) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim()
    }

    private fun playlistTokens(text: String): List<String> =
        normalize(text).replace("'", "").split(' ')
            .filter { it.isNotEmpty() && it !in PLAYLIST_NOISE }
            .map { NUMBER_WORDS[it] ?: it }

    private fun playlistKey(name: String): String = playlistTokens(name).joinToString(" ")

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

    private fun List<String>.startsWith(prefix: List<String>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    private fun List<String>.endsWith(suffix: List<String>): Boolean =
        size >= suffix.size && subList(size - suffix.size, size) == suffix
}
