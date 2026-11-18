package com.music.bitchord.desktop

import kotlinx.coroutines.delay

/*
 * VOICE FIX X1: honest results for the two library actions (add the current song to a playlist,
 * play a playlist). Everything here is pure (no UI state, no network, no disk), so it is unit-tested
 * without a window. The adapter in DesktopApp.kt only gathers the candidate lists and performs the
 * edit; the decisions live here.
 *
 * Privacy: nothing in this file logs or stores a name. [VoiceListLog] builds log lines from result
 * KINDS and source codes only. Names exist only inside the results the overlay shows for a moment.
 */

/** Where a matched playlist came from. [code] is what may be logged. */
internal enum class VoiceListSource(val code: String) {
    /** A playlist kept on this computer. Always editable. */
    LOCAL("local"),

    /** One of the account's own playlists (the parser's user list). Editable. */
    USER("user"),

    /** Any other library-shelf playlist with a browse id (saved, YouTube's own). Playable only. */
    SHELF("shelf"),
}

/** One playlist the voice commands may point at. [id] is the app's own id; it is never logged. */
internal data class VoiceListCandidate(
    val title: String,
    val source: VoiceListSource,
    val editable: Boolean,
    val id: String,
)

/** The outcome of add-to-playlist and play-playlist. Replaces the old `Boolean`. */
internal sealed interface VoicePlaylistResult {

    /**
     * It worked. [song] is empty for "play playlist". [alreadyThere] is true when the song was in the
     * list already (nothing was changed). [undo] is null when no inverse exists.
     */
    data class Done(
        val playlist: String,
        val source: VoiceListSource,
        val song: String = "",
        val alreadyThere: Boolean = false,
        val undo: (() -> Unit)? = null,
    ) : VoicePlaylistResult

    object NotFound : VoicePlaylistResult

    /** A playable list matched, but no edit can be aimed at it. */
    data class NotEditable(val name: String) : VoicePlaylistResult

    data class Ambiguous(val options: List<String>) : VoicePlaylistResult

    object NothingPlaying : VoicePlaylistResult

    object LibraryNotLoaded : VoicePlaylistResult

    /** [code] is a short fixed word ("api", "browse", "empty"), never a message from the server. */
    data class ApiFailed(val code: String) : VoicePlaylistResult
}

/** Which of the two actions a message is for. */
internal enum class VoiceListAction { ADD, PLAY }

/** Result to the overlay sentence. Pure. */
internal object VoicePlaylistMessages {

    /** [requested] is the name the owner said; it is shown only where the result has no name of its own. */
    fun forResult(action: VoiceListAction, result: VoicePlaylistResult, requested: String): String = when (result) {
        is VoicePlaylistResult.Done -> when {
            action == VoiceListAction.PLAY -> "Playing " + result.playlist
            result.alreadyThere -> result.song.ifBlank { "This song" } + " is already in " + result.playlist
            else -> "Added " + result.song.ifBlank { "this song" } + " to " + result.playlist
        }
        VoicePlaylistResult.NotFound ->
            if (action == VoiceListAction.ADD) "Couldn't find an editable playlist called $requested"
            else "Couldn't find a playlist called $requested"
        is VoicePlaylistResult.NotEditable -> result.name + " isn't one of your editable playlists"
        is VoicePlaylistResult.Ambiguous ->
            "More than one playlist matches: " + result.options.take(3).joinToString(", ") + ". Say the full name."
        VoicePlaylistResult.NothingPlaying -> "Nothing is playing"
        VoicePlaylistResult.LibraryNotLoaded -> "Your library is still loading. Try again in a moment"
        is VoicePlaylistResult.ApiFailed ->
            when (result.code) {
                CODE_EMPTY -> "That playlist is empty"
                CODE_TRACK -> "That track can't go in an account playlist"
                else -> "Couldn't reach your playlist. Try again"
            }
    }

    const val CODE_API = "api"
    const val CODE_BROWSE = "browse"
    const val CODE_EMPTY = "empty"
    const val CODE_TRACK = "track"
}

/** Log lines made of result KINDS and source codes only. Never a name, never a message. */
internal object VoiceListLog {

    fun kind(result: VoicePlaylistResult): String = when (result) {
        is VoicePlaylistResult.Done -> "done"
        VoicePlaylistResult.NotFound -> "not_found"
        is VoicePlaylistResult.NotEditable -> "not_editable"
        is VoicePlaylistResult.Ambiguous -> "ambiguous"
        VoicePlaylistResult.NothingPlaying -> "nothing_playing"
        VoicePlaylistResult.LibraryNotLoaded -> "library_not_loaded"
        is VoicePlaylistResult.ApiFailed -> "api_failed:" + result.code
    }

    fun line(action: VoiceListAction, result: VoicePlaylistResult, source: VoiceListSource?): String =
        "voice: playlist action=" + action.name.lowercase() + " result=" + kind(result) +
            " source=" + (source?.code ?: "none")
}

/** All confidence numbers for library actions, in one place. Edits are stricter than playback. */
internal object VoiceConfidence {
    /** Playback: the same floor the parser has always used for playlist names. */
    const val PLAY_MIN = 0.72

    /** Playback: the runner-up must trail the winner by at least this much. */
    const val PLAY_MARGIN = 0.05

    /** Library edit: a match below this is never acted on; the owner is asked instead. */
    const val EDIT_MIN = 0.85

    /** Library edit: a clear gap over the runner-up is required. */
    const val EDIT_MARGIN = 0.10

    /** How long a voice action waits for the library to arrive. */
    const val LIBRARY_WAIT_MILLIS = 4_000L

    const val LIBRARY_POLL_MILLIS = 100L

    /** At most this many options are offered when a match is not clear. */
    const val MAX_OPTIONS = 3
}

/** What the resolver decided. */
internal sealed interface VoiceListPick {
    data class Match(val candidate: VoiceListCandidate) : VoiceListPick

    /** Playable but not editable; only produced for edits. */
    data class NotEditable(val candidate: VoiceListCandidate) : VoiceListPick

    /** Not clear enough to act on. [titles] are distinct and ready to show. */
    data class Several(val titles: List<String>) : VoiceListPick

    object None : VoiceListPick
}

/** Picks one candidate for a spoken name. Pure; the scorer is injectable for tests. */
internal object VoiceListResolver {

    fun resolve(
        query: String,
        candidates: List<VoiceListCandidate>,
        forEdit: Boolean,
        score: (String, String) -> Double = { q, title ->
            // VOICE FIX X2 hook: retain the original parser score as a floor and add normalized phonetics.
            maxOf(
                DesktopVoiceCommandParser.playlistScore(spokenForm(q), spokenForm(title)),
                VoiceMatcher.scoreText(spokenForm(q), spokenForm(title)),
            )
        },
    ): VoiceListPick {
        if (query.isBlank() || candidates.isEmpty()) return VoiceListPick.None
        val ranked = candidates.map { it to score(query, it.title) }.sortedByDescending { it.second }
        val top = ranked.first()
        if (top.second < VoiceConfidence.PLAY_MIN) return VoiceListPick.None

        val minScore = if (forEdit) VoiceConfidence.EDIT_MIN else VoiceConfidence.PLAY_MIN
        val margin = if (forEdit) VoiceConfidence.EDIT_MARGIN else VoiceConfidence.PLAY_MARGIN

        // A weak match for an edit is never acted on and never named as "not editable": ask, but only
        // offer lists an edit could actually go to.
        if (top.second < minScore) {
            val options = ranked.filter { it.second >= VoiceConfidence.PLAY_MIN && it.first.editable }.map { it.first }
            return if (options.isEmpty()) VoiceListPick.None else VoiceListPick.Several(labels(options))
        }

        val close = ranked.filter { it.second >= VoiceConfidence.PLAY_MIN && top.second - it.second < margin }
        if (close.size > 1) return VoiceListPick.Several(labels(close.map { it.first }))

        val picked = top.first
        return if (forEdit && !picked.editable) VoiceListPick.NotEditable(picked) else VoiceListPick.Match(picked)
    }

    /**
     * "&" is spoken as "and", so both sides read it that way. Emoji and punctuation are already
     * dropped by the parser's own normalisation, which the scorer applies. (X2 will replace this.)
     */
    fun spokenForm(text: String): String = text.replace("&", " and ")

    /** Distinct, short labels; two lists with the same title are told apart by where they live. */
    fun labels(options: List<VoiceListCandidate>): List<String> {
        val shown = options.take(VoiceConfidence.MAX_OPTIONS)
        val titleCounts = shown.groupingBy { it.title.trim().lowercase() }.eachCount()
        return shown.map { option ->
            if ((titleCounts[option.title.trim().lowercase()] ?: 0) > 1) {
                option.title + if (option.source == VoiceListSource.LOCAL) " (on this computer)" else " (your account)"
            } else {
                option.title
            }
        }
    }

    /** The result for "no usable match", shared by both actions. */
    fun toResult(pick: VoiceListPick?): VoicePlaylistResult? = when (pick) {
        null -> VoicePlaylistResult.LibraryNotLoaded
        is VoiceListPick.Several -> VoicePlaylistResult.Ambiguous(pick.titles)
        is VoiceListPick.NotEditable -> VoicePlaylistResult.NotEditable(pick.candidate.title)
        VoiceListPick.None -> VoicePlaylistResult.NotFound
        is VoiceListPick.Match -> null
    }
}

/** Waits (bounded) for the library to be usable. The clock and the pause are injectable. */
internal object VoiceLibraryWait {

    /**
     * Returns true as soon as [isReady] is true. Calls [refresh] once, up front, when the library is
     * not ready (the caller decides what a refresh is; it is the app's existing reload). Returns
     * false when [timeoutMillis] has passed without it becoming ready.
     */
    suspend fun await(
        isReady: () -> Boolean,
        refresh: () -> Unit,
        timeoutMillis: Long = VoiceConfidence.LIBRARY_WAIT_MILLIS,
        pollMillis: Long = VoiceConfidence.LIBRARY_POLL_MILLIS,
        now: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        if (isReady()) return true
        refresh()
        val deadline = now() + timeoutMillis
        while (now() < deadline) {
            pause(pollMillis)
            if (isReady()) return true
        }
        return isReady()
    }
}
