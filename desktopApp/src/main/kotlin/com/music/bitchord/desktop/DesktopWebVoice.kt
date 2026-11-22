package com.music.bitchord.desktop

import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

/*
 * WEB2: `/api/voice`. The page records up to 15 s of 16 kHz mono WAV and POSTs it; this turns it
 * into text with the existing offline recogniser, parses it with the existing command parser, and
 * resolves it with the existing matcher to an ANSWER. Nothing is played or changed on the PC: the
 * answer goes back to the page, which plays in the browser. Audio and text are never stored or
 * logged, and the recogniser's temp files are wiped by the recogniser itself.
 *
 * [resolve] is the port of `DesktopVoiceController.play / searchAndPlay` without the side effects
 * (no `actions.playSong`, no overlay state, no session memory: the page keeps "the last candidates").
 * The controller itself is not touched.
 */

/** What the page should do. [action] is a stable word; [result] is a short sentence to show. */
internal data class WebVoiceAnswer(
    val action: String,
    val result: String,
    val candidates: List<WebItem>? = null,
    val item: WebItem? = null,
    val value: String? = null,
)

internal object WebVoiceActions {
    const val PLAY = "play"
    const val PLAY_PLAYLIST = "playPlaylist"
    const val ADD_TO_PLAYLIST = "addToPlaylist"
    const val ADD_TO_LAST_PLAYLIST = "addToLastPlaylist"
    const val CHOICES = "choices"
    const val CHOOSE = "choose"
    const val CHOOSE_OTHER = "chooseOther"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val VOLUME_UP = "volumeUp"
    const val VOLUME_DOWN = "volumeDown"
    const val SET_VOLUME = "setVolume"
    const val SHUFFLE = "shuffle"
    const val LIKE = "like"
    const val WHATS_PLAYING = "whatsPlaying"
    const val NONE = "none"
}

internal class DesktopWebVoice(
    private val library: suspend () -> LibraryPage?,
    private val search: suspend (String) -> List<Song> = { query -> defaultSearch(query) },
    private val recognize: suspend (ShortArray) -> Result<String> = { pcm -> defaultRecognize(pcm) },
) {

    private val slot = Semaphore(1)
    private val holder = VoiceIndexHolder()

    /** `POST /api/voice`. One request at a time; the body was already size-capped by the router. */
    suspend fun handle(body: ByteArray): WebResponse {
        if (!slot.tryAcquire()) return WebJson.error(429, "busy", "Already listening")
        try {
            val wav = WebWav.parse(body)
            if (wav is WebWavResult.Bad) return voiceError(wav.code)
            val samples = (wav as WebWavResult.Ok).samples
            val heard = recognize(samples).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                return voiceError((failure as? VoiceException)?.code ?: "unexpected")
            }
            val answer = if (heard.isBlank()) {
                WebVoiceAnswer(WebVoiceActions.NONE, "Didn't catch that")
            } else {
                resolve(heard)
            }
            return WebJson.ok(WebShapes.voice(heard.trim(), answer.action, answer.result, answer.candidates, answer.item, answer.value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return voiceError("unexpected")
        } finally {
            slot.release()
        }
    }

    private fun voiceError(code: String): WebResponse =
        WebJson.error(WebVoiceErrors.status(code), code, WebVoiceErrors.message(code))

    // ---- Library ---------------------------------------------------------------------------------

    private class Library(val index: VoiceLibraryIndex, val playlists: List<VoiceIndexInput>)

    private suspend fun loadLibrary(): Library {
        val page = runCatching { library() }.getOrElse { if (it is CancellationException) throw it else null }
        return buildLibrary(page)
    }

    private fun buildLibrary(page: LibraryPage?): Library {
        if (page == null) return Library(VoiceLibraryIndex.EMPTY, emptyList())
        val owned = DesktopSearchClient.userPlaylists(page.shelves.flatMap { it.items })
        val playlists = owned.map { list ->
            VoiceIndexInput(
                kind = VoiceEntityKind.PLAYLIST,
                title = list.title,
                id = list.playlistId,
                source = VoiceListSource.USER,
                editable = true,
            )
        }
        val songs = (page.likedSongs + page.librarySongs).distinctBy { it.videoId }
        val inputs = ArrayList<VoiceIndexInput>(playlists)
        songs.mapTo(inputs) { VoiceIndexInput(VoiceEntityKind.SONG, it.title, it.videoId, artist = it.artist) }
        songs.flatMap { song ->
            if (song.artists.isEmpty()) listOf(song.artist to (song.artistId ?: "")) else song.artists.map { it.name to (it.browseId ?: "") }
        }.distinctBy { (name, id) -> id.ifBlank { name } }
            .mapTo(inputs) { (name, id) -> VoiceIndexInput(VoiceEntityKind.ARTIST, name, id) }
        return Library(holder.getFor(inputs), playlists)
    }

    // ---- Resolution --------------------------------------------------------------------------------

    /** Parses [heard] and decides what the page should do. No side effects. */
    suspend fun resolve(heard: String): WebVoiceAnswer {
        val lib = loadLibrary()
        val names = lib.playlists.map { it.title }
        return when (val command = DesktopVoiceCommandParser.parse(heard, names)) {
            is VoiceCommand.Play -> play(command, lib)
            is VoiceCommand.SearchAndPlay -> searchAndPlay(command)
            VoiceCommand.Pause -> WebVoiceAnswer(WebVoiceActions.PAUSE, "Paused")
            VoiceCommand.Resume -> WebVoiceAnswer(WebVoiceActions.RESUME, "Playing")
            VoiceCommand.Next -> WebVoiceAnswer(WebVoiceActions.NEXT, "Next")
            VoiceCommand.Previous -> WebVoiceAnswer(WebVoiceActions.PREVIOUS, "Previous")
            VoiceCommand.VolumeUp -> WebVoiceAnswer(WebVoiceActions.VOLUME_UP, "Volume up")
            VoiceCommand.VolumeDown -> WebVoiceAnswer(WebVoiceActions.VOLUME_DOWN, "Volume down")
            is VoiceCommand.SetVolume -> WebVoiceAnswer(
                WebVoiceActions.SET_VOLUME,
                "Volume ${command.percent} percent",
                value = command.percent.coerceIn(0, 100).toString(),
            )
            is VoiceCommand.Shuffle -> WebVoiceAnswer(
                WebVoiceActions.SHUFFLE,
                if (command.on) "Shuffle on" else "Shuffle off",
                value = command.on.toString(),
            )
            VoiceCommand.Like -> WebVoiceAnswer(WebVoiceActions.LIKE, "Liked")
            is VoiceCommand.Choose -> WebVoiceAnswer(
                WebVoiceActions.CHOOSE,
                "Choice ${command.number}",
                value = command.number.toString(),
            )
            VoiceCommand.ChooseOther -> WebVoiceAnswer(WebVoiceActions.CHOOSE_OTHER, "The other one")
            VoiceCommand.AddToLastPlaylist -> WebVoiceAnswer(WebVoiceActions.ADD_TO_LAST_PLAYLIST, "Adding to the last playlist")
            VoiceCommand.WhatsPlaying -> WebVoiceAnswer(WebVoiceActions.WHATS_PLAYING, "Now playing")
            is VoiceCommand.PlayPlaylist -> playlistAnswer(WebVoiceActions.PLAY_PLAYLIST, command.name, lib)
            is VoiceCommand.AddToPlaylist -> playlistAnswer(WebVoiceActions.ADD_TO_PLAYLIST, command.name, lib)
            VoiceCommand.Unknown -> WebVoiceAnswer(WebVoiceActions.NONE, "Didn't catch that")
        }
    }

    private fun playlistItem(input: VoiceIndexInput): WebItem = WebItem(
        id = input.id,
        type = "playlist",
        title = input.title,
        subtitle = "",
        art = null,
        browseId = "VL" + input.id,
    )

    private fun playlistAnswer(action: String, name: String, lib: Library): WebVoiceAnswer {
        val names = lib.playlists.map { it.title }
        val matched = DesktopVoiceCommandParser.matchPlaylist(name, names)
        if (matched != null) {
            val input = lib.playlists.firstOrNull { it.title == matched }
            if (input != null) {
                val sentence = if (action == WebVoiceActions.ADD_TO_PLAYLIST) "Adding to " else "Playing "
                return WebVoiceAnswer(action, sentence + input.title, item = playlistItem(input))
            }
        }
        val ambiguous = DesktopVoiceCommandParser.ambiguousPlaylists(name, names)
            .mapNotNull { title -> lib.playlists.firstOrNull { it.title == title } }
            .take(VoiceMatchThresholds.MAX_OPTIONS)
        if (ambiguous.isNotEmpty()) {
            return WebVoiceAnswer(WebVoiceActions.CHOICES, "Which playlist?", candidates = ambiguous.map(::playlistItem))
        }
        return WebVoiceAnswer(WebVoiceActions.NONE, "I couldn't find that playlist")
    }

    private fun songItem(song: Song): WebItem = WebItem(
        id = song.videoId,
        type = "song",
        title = song.title,
        subtitle = song.artist,
        art = WebArt.proxyUrl(song.artworkAt(CARD_ART_PX)),
        videoId = song.videoId,
    )

    private fun playAnswer(song: Song): WebVoiceAnswer =
        WebVoiceAnswer(WebVoiceActions.PLAY, "Playing " + labelOf(song), item = songItem(song))

    private fun labelOf(song: Song): String = if (song.artist.isBlank()) song.title else song.title + " by " + song.artist

    private fun choices(songs: List<Song>, sentence: String): WebVoiceAnswer =
        WebVoiceAnswer(WebVoiceActions.CHOICES, sentence, candidates = songs.map(::songItem))

    private suspend fun searchSongs(query: String): List<Song> =
        runCatching { search(query) }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    private fun voiceSong(song: Song) = VoiceSong(song.videoId, song.title, song.artist, song)

    private fun songOf(voice: VoiceSong): Song? = voice.payload as? Song

    private suspend fun searchAndPlay(command: VoiceCommand.SearchAndPlay): WebVoiceAnswer {
        val found = searchSongs(command.query).distinctBy { it.videoId }
        val index = VoiceIndexBuilder.build(
            0L,
            found.map { VoiceIndexInput(VoiceEntityKind.SONG, it.title, it.videoId, artist = it.artist) },
        )
        val outcome = VoiceMatcher.decide(VoiceTerms.of(command.query), index, VoiceMatchMode.PLAY, setOf(VoiceEntityKind.SONG))
        val chosen: List<Song> = when (outcome) {
            // Discovery words rarely appear in a title: keep the search service's own order.
            VoiceMatchOutcome.NoMatch -> found.take(VoiceMatchThresholds.MAX_OPTIONS)
            is VoiceMatchOutcome.Confident -> listOfNotNull(found.firstOrNull { it.videoId == outcome.match.entry.input.id })
            is VoiceMatchOutcome.Candidates -> outcome.options
                .mapNotNull { match -> found.firstOrNull { it.videoId == match.entry.input.id } }
                .take(VoiceMatchThresholds.MAX_OPTIONS)
        }
        if (chosen.isEmpty()) return WebVoiceAnswer(WebVoiceActions.NONE, "Couldn't find anything for that")
        if (outcome is VoiceMatchOutcome.Confident) return playAnswer(chosen.first())
        return choices(chosen, "Here are some options")
    }

    private suspend fun play(command: VoiceCommand.Play, lib: Library): WebVoiceAnswer {
        var title = command.title
        var requestedArtist = command.artist
        var artistOnly = false
        if (lib.index.entries.isNotEmpty()) {
            val words = VoiceText.words(listOfNotNull(command.title, command.artist).joinToString(" "))
            val hint = VoiceTypeHint.from(words, command.artist != null)
            val kinds = setOf(VoiceEntityKind.PLAYLIST, VoiceEntityKind.SONG, VoiceEntityKind.ARTIST)
            when (
                val matched = VoiceMatcher.decide(
                    VoiceTerms.of(command.title), lib.index, VoiceMatchMode.PLAY, kinds, hint,
                    command.artist?.let { VoiceTerms.of(it) },
                )
            ) {
                is VoiceMatchOutcome.Confident -> {
                    val input = matched.match.entry.input
                    when (input.kind) {
                        VoiceEntityKind.PLAYLIST -> return WebVoiceAnswer(
                            WebVoiceActions.PLAY_PLAYLIST,
                            "Playing " + input.title,
                            item = playlistItem(input),
                        )
                        VoiceEntityKind.ARTIST -> {
                            requestedArtist = input.title
                            artistOnly = true
                        }
                        VoiceEntityKind.SONG, VoiceEntityKind.ALBUM -> title = input.title
                    }
                }
                is VoiceMatchOutcome.Candidates -> {
                    val items = matched.options.take(VoiceMatchThresholds.MAX_OPTIONS).mapNotNull { option ->
                        val input = option.entry.input
                        when (input.kind) {
                            VoiceEntityKind.PLAYLIST -> playlistItem(input)
                            VoiceEntityKind.SONG -> WebItem(input.id, "song", input.title, input.artist, null, videoId = input.id)
                            VoiceEntityKind.ARTIST ->
                                if (input.id.isBlank()) null else WebItem(input.id, "artist", input.title, "", null, browseId = input.id)
                            VoiceEntityKind.ALBUM -> null
                        }
                    }
                    if (items.isNotEmpty()) return WebVoiceAnswer(WebVoiceActions.CHOICES, "Did you mean...?", candidates = items)
                }
                VoiceMatchOutcome.NoMatch -> Unit
            }
        }
        val query = if (artistOnly) requestedArtist.orEmpty() else listOfNotNull(title, requestedArtist).joinToString(" ")
        val unique = searchSongs(query).distinctBy { it.videoId }.take(MAX_CANDIDATES)
        val index = VoiceIndexBuilder.build(
            0L,
            unique.map { VoiceIndexInput(VoiceEntityKind.SONG, if (artistOnly) it.artist else it.title, it.videoId, artist = it.artist) },
        )
        val queryTerms = VoiceTerms.of(if (artistOnly) requestedArtist.orEmpty() else title)
        val artistTerms = if (artistOnly) null else requestedArtist?.let { VoiceTerms.of(it) }
        val ranked = VoiceMatcher.rank(queryTerms, index, setOf(VoiceEntityKind.SONG), artist = artistTerms)
        return when (val outcome = VoiceMatcher.decideRanked(ranked, VoiceMatchMode.PLAY)) {
            VoiceMatchOutcome.NoMatch -> {
                if (unique.isEmpty()) return WebVoiceAnswer(WebVoiceActions.NONE, "Couldn't find that")
                // These came back from the search for this very request: offer them rather than nothing.
                val weak = if (artistOnly) {
                    unique
                } else {
                    VoiceSongMatcher.rank(unique.map(::voiceSong), title, requestedArtist).mapNotNull { songOf(it.first) }
                }
                choices(weak.take(VoiceMatchThresholds.MAX_OPTIONS), "Did you mean...?")
            }
            is VoiceMatchOutcome.Confident ->
                unique.firstOrNull { it.videoId == outcome.match.entry.input.id }?.let(::playAnswer)
                    ?: WebVoiceAnswer(WebVoiceActions.NONE, "Couldn't find that")
            is VoiceMatchOutcome.Candidates -> {
                val songs = outcome.options.mapNotNull { option -> unique.firstOrNull { it.videoId == option.entry.input.id } }
                    .take(VoiceMatchThresholds.MAX_OPTIONS)
                if (songs.isEmpty()) WebVoiceAnswer(WebVoiceActions.NONE, "Couldn't find that") else choices(songs, "Did you mean...?")
            }
        }
    }

    companion object {
        const val MAX_CANDIDATES = 10

        /** The existing search, songs only, as the voice controller's adapter does. */
        suspend fun defaultSearch(query: String): List<Song> {
            val results = DesktopSearchClient.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            return results.mapNotNull { result ->
                when (result) {
                    is SearchResult.TopTrack -> result.song
                    is SearchResult.Track -> result.song
                    is SearchResult.Browse -> null
                }
            }
        }

        /** The existing sidecar recogniser, found the way the desktop voice feature finds it. */
        suspend fun defaultRecognize(pcm: ShortArray): Result<String> {
            val program = withContext(Dispatchers.IO) {
                DesktopVoiceSidecar.findExisting(chosen = DesktopVoiceSettings.sidecarExe.value)
            } ?: return Result.failure(VoiceException(VoiceErrors.PROGRAM_MISSING))
            val model = withContext(Dispatchers.IO) {
                DesktopVoiceController.defaultFindModel(DesktopVoiceSettings.modelDir.value)
            } ?: return Result.failure(VoiceException(VoiceErrors.MODEL_MISSING))
            return SherpaSidecarRecognizer(program, model).transcribe(pcm)
        }
    }
}
