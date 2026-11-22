package com.music.bitchord.desktop

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.model.withoutRepeatsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/*
 * WEB2: every endpoint of the contract, wired to code that already exists. Nothing here parses
 * YouTube, resolves a stream or reads a window: it calls [DesktopSearchClient] (home, search,
 * library, browse, rate, playlists), [DesktopLyricsClient], [StreamResolver] (through
 * [DesktopWebMedia]), the artwork cache and [extractAlbumPalette], and the voice code (through
 * [DesktopWebVoice]). It never reads composable state (see HANDOFF "WEB1": there is no window in
 * background mode).
 *
 * Errors use the envelope from WEB1. Messages are fixed English strings: no search text, id, URL
 * or exception text ever reaches a log or a response.
 */

/** Pure mapping from the app's models to the contract's `Item` / `Track`. */
internal object WebMap {

    fun songItem(song: Song): WebItem = WebItem(
        id = song.videoId,
        type = "song",
        title = song.title,
        subtitle = song.artist,
        art = WebArt.proxyUrl(song.artworkAt(CARD_ART_PX)),
        videoId = song.videoId,
    )

    fun track(song: Song): WebTrack = WebTrack(
        videoId = song.videoId,
        title = song.title,
        artists = song.artist,
        durationMs = song.durationMillis(),
        art = WebArt.proxyUrl(song.artworkAt(ROW_ART_PX)),
    )

    /** `album`, `artist` or `playlist`; an unknown kind of page is treated as a playlist. */
    fun browseTypeName(type: BrowseType, browseId: String): String = when (if (type == BrowseType.OTHER) browseTypeOf(browseId) else type) {
        BrowseType.ALBUM -> "album"
        BrowseType.ARTIST -> "artist"
        BrowseType.PLAYLIST, BrowseType.OTHER -> "playlist"
    }

    /** A playlist's `id` is its raw id (no `VL`), as `/api/playlist/{id}` takes it; other pages keep their browse id. */
    fun browseItemId(browseId: String, typeName: String): String =
        if (typeName == "playlist") browseId.removePrefix("VL") else browseId

    fun browseItem(item: BrowseItem): WebItem {
        val type = browseTypeName(item.type, item.browseId)
        return WebItem(
            id = browseItemId(item.browseId, type),
            type = type,
            title = item.title,
            subtitle = item.subtitle,
            art = WebArt.proxyUrl(item.thumbnailUrl.artworkAt(CARD_ART_PX)),
            browseId = item.browseId,
        )
    }

    /** A home or library card: a track when it has a video id, else a browsable page; null when it is neither. */
    fun shelfItem(item: ShelfItem): WebItem? {
        val videoId = item.videoId
        if (videoId != null && WebInputs.isVideoId(videoId)) {
            return WebItem(
                id = videoId,
                type = "song",
                title = item.title,
                subtitle = item.subtitle,
                art = WebArt.proxyUrl(item.thumbnailUrl.artworkAt(CARD_ART_PX)),
                videoId = videoId,
            )
        }
        val browseId = item.browseId ?: return null
        val type = browseTypeName(BrowseType.OTHER, browseId)
        return WebItem(
            id = browseItemId(browseId, type),
            type = type,
            title = item.title,
            subtitle = item.subtitle,
            art = WebArt.proxyUrl(item.thumbnailUrl.artworkAt(CARD_ART_PX)),
            browseId = browseId,
        )
    }

    /** The search sections in the order the desktop shows them. Empty sections are dropped. */
    fun searchSections(results: List<SearchResult>): List<Pair<String, List<WebItem>>> {
        val top = ArrayList<WebItem>()
        val songs = ArrayList<WebItem>()
        val albums = ArrayList<WebItem>()
        val artists = ArrayList<WebItem>()
        val playlists = ArrayList<WebItem>()
        for (result in results) {
            when (result) {
                is SearchResult.TopTrack -> top.add(songItem(result.song))
                is SearchResult.Track -> songs.add(songItem(result.song))
                is SearchResult.Browse -> {
                    val item = browseItem(result.item)
                    when (item.type) {
                        "album" -> albums.add(item)
                        "artist" -> artists.add(item)
                        else -> playlists.add(item)
                    }
                }
            }
        }
        return listOf("Top result" to top, "Songs" to songs, "Albums" to albums, "Artists" to artists, "Playlists" to playlists)
            .filter { it.second.isNotEmpty() }
    }
}

/** The signed-in library, fetched at most once a minute and shared by `/api/library`, likes and voice. */
internal class DesktopWebLibrary(
    private val fetch: suspend () -> Result<LibraryPage> = { DesktopSearchClient.library() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var page: LibraryPage? = null
    private var at = 0L

    /** Ids of the liked songs on the first page of Liked music (the account's likes, as far as this page shows). */
    @Volatile
    var likedIds: Set<String> = emptySet()
        private set

    suspend fun get(force: Boolean = false): Result<LibraryPage> = mutex.withLock {
        val held = page
        if (!force && held != null && now() - at < TTL_MILLIS) return@withLock Result.success(held)
        val result = fetch()
        val fresh = result.getOrNull()
        if (fresh != null) {
            page = fresh
            at = now()
            likedIds = fresh.likedSongs.mapTo(HashSet()) { it.videoId }
        }
        result
    }

    /** Called after a like or a playlist change, so the next read is fresh. */
    fun invalidate() {
        at = 0L
    }

    suspend fun peek(): LibraryPage? = get().getOrNull()

    private companion object {
        const val TTL_MILLIS = 60_000L
    }
}

internal class DesktopWebApi(
    private val media: DesktopWebMedia = DesktopWebMedia(),
    private val library: DesktopWebLibrary = DesktopWebLibrary(),
    private val events: DesktopWebEventHub,
    private val isSignedIn: () -> Boolean = { DesktopYouTubeAuth.isSignedIn },
) {

    private val voice = DesktopWebVoice(library = { library.peek() })

    fun install(router: DesktopWebRouter) {
        router.get("/api/home") { home() }
        router.get("/api/search") { search(it) }
        router.get("/api/suggest") { suggest(it) }
        router.get("/api/library") { libraryPage() }
        router.get("/api/playlist/{id}") { playlist(it) }
        router.get("/api/track/{videoId}") { trackDetails(it) }
        router.get("/api/stream/{videoId}") { media.stream(it.params["videoId"].orEmpty(), it.headers["range"]) }
        router.get("/api/lyrics/{videoId}") { lyrics(it) }
        router.post("/api/like/{videoId}") { like(it) }
        router.post("/api/playlist/{id}/add") { addToPlaylist(it) }
        router.get("/api/art") { media.art(it.query["u"]) }
        router.post("/api/voice", WebLimits.VOICE_BODY) { voice.handle(it.body) }
    }

    // ---- Errors ----------------------------------------------------------------------------------

    private fun upstream(): WebResponse = WebJson.error(502, "upstream", "YouTube Music could not be reached")

    private fun badRequest(message: String): WebResponse = WebJson.error(400, "bad_request", message)

    private fun signedOut(): WebResponse = WebJson.error(401, "signed_out", "Sign in inside the desktop app first")

    /** Runs [block]; a cancelled coroutine stays cancelled, anything else is a 502 with a fixed message. */
    private suspend fun guarded(block: suspend () -> WebResponse): WebResponse = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        upstream()
    }

    // ---- Home ------------------------------------------------------------------------------------

    @Volatile
    private var homeCache: Pair<Long, JsonElement>? = null

    private suspend fun home(): WebResponse = guarded {
        val held = homeCache
        if (held != null && System.currentTimeMillis() - held.first < HOME_TTL_MILLIS) {
            return@guarded WebJson.ok(held.second)
        }
        val first = DesktopSearchClient.home().getOrElse { return@guarded upstream() }
        var shelves = first.shelves
        var token = first.continuation
        var pages = 0
        while (token != null && pages < HOME_EXTRA_PAGES) {
            val more = DesktopSearchClient.moreHome(token).getOrNull() ?: break
            shelves = shelves + more.shelves.withoutRepeatsOf(shelves)
            token = more.continuation
            pages += 1
        }
        val body = buildJsonObject {
            put(
                "shelves",
                buildJsonArray {
                    for (shelf in shelves) {
                        val items = shelf.items.mapNotNull(WebMap::shelfItem)
                        if (items.isNotEmpty()) add(WebShapes.shelf(shelf.title, items))
                    }
                },
            )
        }
        homeCache = System.currentTimeMillis() to body
        WebJson.ok(body)
    }

    // ---- Search and suggestions --------------------------------------------------------------------

    private suspend fun search(request: WebRequest): WebResponse = guarded {
        val text = WebInputs.query(request.query["q"]) ?: return@guarded badRequest("Missing or invalid search text")
        val results = DesktopSearchClient.search(text, SearchFilter.ALL).getOrElse { return@guarded upstream() }
        val sections = WebMap.searchSections(results)
        WebJson.ok(
            buildJsonObject {
                put("sections", buildJsonArray { sections.forEach { (title, items) -> add(WebShapes.shelf(title, items)) } })
            },
        )
    }

    private suspend fun suggest(request: WebRequest): WebResponse = guarded {
        val text = WebInputs.query(request.query["q"]) ?: return@guarded WebJson.ok(JsonArray(emptyList()))
        val list = DesktopSearchClient.searchSuggestions(text).getOrElse { return@guarded upstream() }
        WebJson.ok(WebShapes.strings(list.filter { it.isNotBlank() }.take(MAX_SUGGESTIONS)))
    }

    // ---- Library and playlists ---------------------------------------------------------------------

    private suspend fun libraryPage(): WebResponse = guarded {
        if (!isSignedIn()) return@guarded signedOut()
        val page = library.get().getOrElse { return@guarded upstream() }
        val all = page.shelves.flatMap { it.items }
        val owned = DesktopSearchClient.userPlaylists(all).map { it.browseId }.toSet()
        fun isOwned(item: ShelfItem): Boolean = item.browseId.let { it != null && it in owned }
        val playlists = all.filter(::isOwned).mapNotNull(WebMap::shelfItem).distinctBy { it.id }
        val saved = all.filterNot(::isOwned).mapNotNull(WebMap::shelfItem).distinctBy { it.id }
        WebJson.ok(
            buildJsonObject {
                put(
                    "liked",
                    buildJsonObject {
                        put("id", LIKED_ID)
                        put("title", "Liked music")
                        put("count", page.likedSongs.size)
                    },
                )
                put("playlists", WebShapes.items(playlists))
                put("saved", WebShapes.items(saved))
            },
        )
    }

    private suspend fun playlist(request: WebRequest): WebResponse = guarded {
        val id = WebInputs.playlistId(request.params["id"].orEmpty()) ?: return@guarded badRequest("Invalid playlist id")
        if (id == LIKED_ID) {
            if (!isSignedIn()) return@guarded signedOut()
            val page = library.get().getOrElse { return@guarded upstream() }
            return@guarded WebJson.ok(WebShapes.playlist("Liked music", "You", false, tracksOf(page.likedSongs)))
        }
        val collection = DesktopSearchClient.browse(WebInputs.browseIdFor(id)).getOrElse { return@guarded upstream() }
        val songs = ArrayList(collection.songs)
        var token = collection.continuation
        var pages = 0
        while (token != null && pages < PLAYLIST_EXTRA_PAGES && songs.size < MAX_TRACKS) {
            val more = DesktopSearchClient.moreCollectionSongs(token).getOrNull() ?: break
            songs.addAll(more.first)
            token = more.second
            pages += 1
        }
        WebJson.ok(
            WebShapes.playlist(
                title = collection.title,
                owner = collection.subtitle,
                editable = collection.owned == true,
                tracks = tracksOf(songs),
            ),
        )
    }

    private fun tracksOf(songs: List<Song>): List<WebTrack> = songs
        .filter { !it.isUnresolvedSpotify && WebInputs.isVideoId(it.videoId) }
        .take(MAX_TRACKS)
        .map(WebMap::track)

    // ---- Track details, palette, lyrics ------------------------------------------------------------

    /** What a track page needs, kept for [TRACK_TTL_MILLIS]; `liked` is not kept, it is read fresh. */
    private class TrackInfo(val song: Song, val palette: List<String>, val at: Long)

    private val tracks = ConcurrentHashMap<String, TrackInfo>()

    private suspend fun trackInfo(videoId: String): TrackInfo? {
        tracks[videoId]?.takeIf { System.currentTimeMillis() - it.at < TRACK_TTL_MILLIS }?.let { return it }
        val song = DesktopSearchClient.trackLinks(videoId).getOrNull() ?: return null
        val palette = paletteOf(song.artworkAt(CARD_ART_PX))
        val info = TrackInfo(song, palette, System.currentTimeMillis())
        if (tracks.size >= MAX_TRACKS_KEPT) tracks.clear()
        tracks[videoId] = info
        return info
    }

    /**
     * The three display colours of a cover as `#rrggbb`: the cover is read through the existing
     * artwork cache and [extractAlbumPalette] picks the colours. A cover that cannot be read gives
     * the quiet fallback. Only an art-allowlisted URL is ever fetched.
     */
    private suspend fun paletteOf(url: String?): List<String> {
        if (!WebHosts.isAllowedArt(url)) return WebPalette.FALLBACK
        val bitmap = withTimeoutOrNull(PALETTE_TIMEOUT_MILLIS) { DesktopArtworkCache.load(url) }
            ?: return WebPalette.FALLBACK
        return withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 2 || height < 2) return@withContext WebPalette.FALLBACK
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels, 0, 0, width, height)
            val found = extractAlbumPalette(pixels, width, height) ?: return@withContext WebPalette.FALLBACK
            WebPalette.three(found.colors.take(3).map { WebPalette.hex(it.red, it.green, it.blue) })
        }
    }

    private fun isLiked(videoId: String): Boolean {
        val override = LikeState.overrides.value[videoId]
        return if (override != null) override == LikeStatus.LIKE else videoId in library.likedIds
    }

    private suspend fun trackDetails(request: WebRequest): WebResponse = guarded {
        val videoId = request.params["videoId"].orEmpty()
        if (!WebInputs.isVideoId(videoId)) return@guarded WebJson.error(404, "not_found", "Unknown track")
        val info = trackInfo(videoId) ?: return@guarded WebJson.error(404, "not_found", "Unknown track")
        val song = info.song
        val artists = song.artists.map { it.name }.filter { it.isNotBlank() }.ifEmpty { listOf(song.artist) }
        WebJson.ok(
            WebShapes.trackDetails(
                videoId = videoId,
                title = song.title,
                artists = artists,
                album = song.albumName?.takeIf { it.isNotBlank() },
                durationMs = song.durationMillis(),
                artSmall = WebArt.proxyUrl(song.artworkAt(ROW_ART_PX)),
                artLarge = WebArt.proxyUrl(song.artworkAt(com.music.bitchord.data.model.PLAYER_ART_PX)),
                palette = info.palette,
                liked = isLiked(videoId),
            ),
        )
    }

    private suspend fun lyrics(request: WebRequest): WebResponse = guarded {
        val videoId = request.params["videoId"].orEmpty()
        if (!WebInputs.isVideoId(videoId)) return@guarded WebJson.error(404, "not_found", "Unknown track")
        val song = (trackInfo(videoId) ?: return@guarded WebJson.error(404, "not_found", "Unknown track")).song
        val found = DesktopLyricsClient.lookup(song, song.durationMillis()).getOrNull()
        val shape = if (found == null) {
            WebLyrics.Shape(null, null)
        } else {
            WebLyrics.shape(found.lines.map { it.timeMs to it.text })
        }
        WebJson.ok(WebShapes.lyrics(shape.synced, shape.plain))
    }

    // ---- Like and add to playlist ------------------------------------------------------------------

    private suspend fun like(request: WebRequest): WebResponse = guarded {
        val videoId = request.params["videoId"].orEmpty()
        if (!WebInputs.isVideoId(videoId)) return@guarded WebJson.error(404, "not_found", "Unknown track")
        if (!isSignedIn()) return@guarded signedOut()
        val body = WebJson.parseObject(request.body) ?: return@guarded badRequest("Expected {\"liked\": true|false}")
        val liked = WebInputs.boolField(body, "liked") ?: return@guarded badRequest("Expected {\"liked\": true|false}")
        val status = if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT
        DesktopSearchClient.rate(videoId, status).getOrElse { return@guarded upstream() }
        LikeState.set(videoId, status)
        library.invalidate()
        events.publishLibraryChanged()
        WebJson.ok(buildJsonObject { put("liked", liked) })
    }

    private suspend fun addToPlaylist(request: WebRequest): WebResponse = guarded {
        val id = WebInputs.playlistId(request.params["id"].orEmpty()) ?: return@guarded badRequest("Invalid playlist id")
        if (id == LIKED_ID) return@guarded badRequest("Use the like button for Liked music")
        if (!isSignedIn()) return@guarded signedOut()
        val body = WebJson.parseObject(request.body) ?: return@guarded badRequest("Expected {\"videoId\": \"...\"}")
        val videoId = WebInputs.stringField(body, "videoId")?.takeIf(WebInputs::isVideoId)
            ?: return@guarded badRequest("Expected {\"videoId\": \"...\"}")
        // The same call the player's add button and the voice command use.
        DesktopSearchClient.addToPlaylist(id, listOf(videoId)).getOrElse { return@guarded upstream() }
        library.invalidate()
        events.publishLibraryChanged()
        WebJson.ok(buildJsonObject { put("ok", true) })
    }

    private companion object {
        const val LIKED_ID = "LM"
        const val HOME_TTL_MILLIS = 60_000L
        const val HOME_EXTRA_PAGES = 2
        const val PLAYLIST_EXTRA_PAGES = 8
        const val MAX_TRACKS = 1000
        const val MAX_SUGGESTIONS = 10
        const val TRACK_TTL_MILLIS = 10 * 60_000L
        const val MAX_TRACKS_KEPT = 64
        const val PALETTE_TIMEOUT_MILLIS = 5_000L
    }
}
