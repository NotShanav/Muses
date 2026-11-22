package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WEB2: the pure helpers and the adapters that need no network: host allowlists, the proxy URL,
 * range and window planning, content types, the WAV reader, palette hex, JSON shapes, input checks,
 * lyrics split, the model mapping, the library cache, and the voice resolver with fakes.
 */
class DesktopWebApiSupportTest {

    private val goodStream = "https://rr1---sn-abc.googlevideo.com/videoplayback?clen=1000&mime=audio%2Fmp4&x=1"
    private val goodArt = "https://lh3.googleusercontent.com/abc=w120-h120"

    // ---- Hosts and art URL --------------------------------------------------------------------

    @Test
    fun `stream allowlist accepts googlevideo and nothing else`() {
        assertTrue(WebHosts.isAllowedStream(goodStream))
        assertTrue(WebHosts.isAllowedStream("https://googlevideo.com/x"))
        assertFalse(WebHosts.isAllowedStream("http://rr1.googlevideo.com/x"))
        assertFalse(WebHosts.isAllowedStream("https://evilgooglevideo.com/x"))
        assertFalse(WebHosts.isAllowedStream("https://googlevideo.com.evil.com/x"))
        assertFalse(WebHosts.isAllowedStream("https://user@rr1.googlevideo.com/x"))
        assertFalse(WebHosts.isAllowedStream("https://rr1.googlevideo.com:8443/x"))
        assertFalse(WebHosts.isAllowedStream("https://127.0.0.1/x"))
        assertFalse(WebHosts.isAllowedStream("https://localhost/x"))
        assertFalse(WebHosts.isAllowedStream("file:///etc/passwd"))
        assertFalse(WebHosts.isAllowedStream("https://" + "a".repeat(3000) + ".googlevideo.com/"))
        assertFalse(WebHosts.isAllowedStream(null))
        assertFalse(WebHosts.isAllowedStream(""))
        assertFalse(WebHosts.isAllowedStream(goodArt))
    }

    @Test
    fun `art allowlist accepts the image hosts only`() {
        assertTrue(WebHosts.isAllowedArt(goodArt))
        assertTrue(WebHosts.isAllowedArt("https://i.ytimg.com/vi/abc/hq.jpg"))
        assertTrue(WebHosts.isAllowedArt("https://yt3.ggpht.com/a"))
        assertFalse(WebHosts.isAllowedArt("https://example.com/a.jpg"))
        assertFalse(WebHosts.isAllowedArt("https://googleusercontent.com.evil.test/a"))
        assertFalse(WebHosts.isAllowedArt("https://rr1.googlevideo.com/x"))
        assertFalse(WebHosts.isAllowedArt("//lh3.googleusercontent.com/a"))
    }

    @Test
    fun `proxy url is encoded and refuses other hosts`() {
        val proxied = WebArt.proxyUrl(goodArt)
        assertNotNull(proxied)
        assertTrue(proxied.startsWith("/api/art?u=https%3A%2F%2Flh3.googleusercontent.com%2Fabc%3Dw120-h120"))
        assertNull(WebArt.proxyUrl("https://example.com/a.jpg"))
        assertNull(WebArt.proxyUrl(null))
        assertNull(WebArt.proxyUrl(" "))
    }

    // ---- Stream plan --------------------------------------------------------------------------

    @Test
    fun `no range header sends everything with 200`() {
        val send = WebStreamPlan.decide(null, 1000) as WebStreamDecision.Send
        assertEquals(200, send.status)
        assertEquals(0L, send.start)
        assertEquals(999L, send.endInclusive)
        assertEquals(1000L, send.length)
        val headers = WebStreamPlan.headers(send, "audio/mp4")
        assertEquals("bytes", headers["Accept-Ranges"])
        assertNull(headers["Content-Range"])
    }

    @Test
    fun `range headers give 206 and a Content-Range`() {
        val partial = WebStreamPlan.decide("bytes=100-199", 1000) as WebStreamDecision.Send
        assertEquals(206, partial.status)
        assertEquals(100L, partial.length)
        assertEquals("bytes 100-199/1000", WebStreamPlan.headers(partial, "audio/mp4")["Content-Range"])
        val open = WebStreamPlan.decide("bytes=900-", 1000) as WebStreamDecision.Send
        assertEquals(999L, open.endInclusive)
        val suffix = WebStreamPlan.decide("bytes=-100", 1000) as WebStreamDecision.Send
        assertEquals(900L, suffix.start)
        val clamped = WebStreamPlan.decide("bytes=0-99999", 1000) as WebStreamDecision.Send
        assertEquals(999L, clamped.endInclusive)
    }

    @Test
    fun `a range past the end is 416 and junk is ignored`() {
        val bad = WebStreamPlan.decide("bytes=2000-", 1000)
        assertTrue(bad is WebStreamDecision.Unsatisfiable)
        assertEquals("bytes */1000", WebStreamPlan.unsatisfiedHeaders(1000)["Content-Range"])
        val junk = WebStreamPlan.decide("bytes=a-b", 1000) as WebStreamDecision.Send
        assertEquals(200, junk.status)
        val several = WebStreamPlan.decide("bytes=0-1,5-6", 1000) as WebStreamDecision.Send
        assertEquals(200, several.status)
        assertTrue(WebStreamPlan.decide(null, 0) is WebStreamDecision.Unsatisfiable)
    }

    @Test
    fun `upstream windows are bounded and stop at the end`() {
        assertEquals("bytes=0-1048575", WebStreamPlan.windowHeader(0, 5_000_000))
        assertEquals("bytes=1048576-2097151", WebStreamPlan.windowHeader(1_048_576, 5_000_000))
        assertEquals("bytes=4000000-4999999", WebStreamPlan.windowHeader(4_000_000, 4_999_999))
        assertEquals("bytes=10-19", WebStreamPlan.windowHeader(10, 99, window = 10))
        assertNull(WebStreamPlan.windowHeader(5_000_000, 4_999_999))
        assertNull(WebStreamPlan.windowHeader(-1, 10))
    }

    @Test
    fun `size and type come from the url`() {
        assertEquals(1000L, WebStreamPlan.contentLengthOf(goodStream))
        assertNull(WebStreamPlan.contentLengthOf("https://a.googlevideo.com/x?clen=abc"))
        assertNull(WebStreamPlan.contentLengthOf("https://a.googlevideo.com/x"))
        assertEquals("audio/mp4", WebStreamPlan.contentType(goodStream, null))
        assertEquals("audio/webm", WebStreamPlan.contentType("https://a.googlevideo.com/x?mime=audio%2Fwebm", "audio/mp4"))
        assertEquals("audio/webm", WebStreamPlan.contentType("https://a.googlevideo.com/x?mime=video%2Fwebm", null))
        assertEquals("audio/webm", WebStreamPlan.contentType("https://a.googlevideo.com/x", "audio/webm; codecs=opus"))
        assertEquals("audio/mp4", WebStreamPlan.contentType("https://a.googlevideo.com/x", "text/html"))
        assertEquals("audio/mp4", WebStreamPlan.contentType("https://a.googlevideo.com/x?mime=text%2Fhtml", null))
        assertTrue(WebStreamPlan.shouldRefresh(403))
        assertTrue(WebStreamPlan.shouldRefresh(410))
        assertFalse(WebStreamPlan.shouldRefresh(404))
        assertFalse(WebStreamPlan.shouldRefresh(200))
    }

    // ---- WAV ----------------------------------------------------------------------------------

    private fun wav(samples: Int, rate: Int = 16_000, channels: Int = 1, bits: Int = 16, declaredData: Int? = null): ByteArray {
        val data = ByteArray(samples * 2) { (it % 7).toByte() }
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray())
        int(36 + data.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int(16)
        short(1)
        short(channels)
        int(rate)
        int(rate * channels * bits / 8)
        short(channels * bits / 8)
        short(bits)
        out.write("data".toByteArray())
        int(declaredData ?: data.size)
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `a 16 kHz mono wav is read`() {
        val result = WebWav.parse(wav(16_000))
        assertTrue(result is WebWavResult.Ok)
        assertEquals(16_000, (result as WebWavResult.Ok).samples.size)
    }

    @Test
    fun `other formats and sizes are refused with a code`() {
        assertEquals("bad_audio", (WebWav.parse(wav(16_000, rate = 44_100)) as WebWavResult.Bad).code)
        assertEquals("bad_audio", (WebWav.parse(wav(16_000, channels = 2)) as WebWavResult.Bad).code)
        assertEquals("bad_audio", (WebWav.parse(wav(16_000, bits = 8)) as WebWavResult.Bad).code)
        assertEquals("bad_audio", (WebWav.parse(ByteArray(10)) as WebWavResult.Bad).code)
        assertEquals("bad_audio", (WebWav.parse("not a wav file at all, just text........................".toByteArray()) as WebWavResult.Bad).code)
        assertEquals("too_short", (WebWav.parse(wav(100)) as WebWavResult.Bad).code)
        assertEquals("too_long", (WebWav.parse(wav(WebWav.MAX_SAMPLES + 1)) as WebWavResult.Bad).code)
        assertTrue(WebWav.parse(wav(WebWav.MAX_SAMPLES)) is WebWavResult.Ok)
    }

    @Test
    fun `a streamed wav with a zero data size takes what is there`() {
        val result = WebWav.parse(wav(8_000, declaredData = 0))
        assertEquals(8_000, (result as WebWavResult.Ok).samples.size)
        val unknown = WebWav.parse(wav(8_000, declaredData = -1))
        assertEquals(8_000, (unknown as WebWavResult.Ok).samples.size)
    }

    // ---- Palette ------------------------------------------------------------------------------

    @Test
    fun `palette hex is lower case and clamped`() {
        assertEquals("#ff0000", WebPalette.hex(1f, 0f, 0f))
        assertEquals("#000000", WebPalette.hex(-1f, -0.5f, 0f))
        assertEquals("#ffffff", WebPalette.hex(2f, 1f, 1.5f))
        assertEquals("#808080", WebPalette.hex(0.5f, 0.5f, 0.5f))
    }

    @Test
    fun `a palette is always exactly three colours`() {
        assertEquals(listOf("#111111", "#222222", "#333333"), WebPalette.three(listOf("#111111", "#222222", "#333333", "#444444")))
        val padded = WebPalette.three(listOf("#111111"))
        assertEquals(3, padded.size)
        assertEquals("#111111", padded[0])
        assertEquals(WebPalette.FALLBACK[1], padded[1])
        assertEquals(WebPalette.FALLBACK, WebPalette.three(emptyList()))
    }

    // ---- JSON shapes --------------------------------------------------------------------------

    @Test
    fun `item json carries the contract fields`() {
        val song = WebShapes.item(WebItem("abc", "song", "T", "A", "/api/art?u=x", videoId = "abc"))
        assertEquals(setOf("id", "type", "title", "subtitle", "art", "videoId"), song.keys)
        val album = WebShapes.item(WebItem("MPREb_1", "album", "T", "A", null, browseId = "MPREb_1"))
        assertEquals(JsonNull, album["art"])
        assertTrue("browseId" in album.keys)
        assertFalse("videoId" in album.keys)
    }

    @Test
    fun `shelf kind says what the items are`() {
        val song = WebItem("a", "song", "t", "s", null, videoId = "a")
        val album = WebItem("b", "album", "t", "s", null, browseId = "b")
        assertEquals("songs", WebShapes.kindOf(listOf(song, song)))
        assertEquals("browse", WebShapes.kindOf(listOf(album)))
        assertEquals("mixed", WebShapes.kindOf(listOf(song, album)))
        val shelf = WebShapes.shelf("Recents", listOf(song))
        assertEquals("Recents", shelf["title"]!!.jsonPrimitive.content)
        assertEquals(1, shelf["items"]!!.jsonArray.size)
    }

    @Test
    fun `track details have the contract shape and a three colour palette`() {
        val json = WebShapes.trackDetails("vid", "T", listOf("A", "B"), null, 1234L, "/s", "/l", listOf("#111111"), true)
        assertEquals(setOf("videoId", "title", "artists", "album", "durationMs", "art", "palette", "liked"), json.keys)
        assertEquals(2, json["artists"]!!.jsonArray.size)
        assertEquals(JsonNull, json["album"])
        assertEquals(setOf("small", "large"), json["art"]!!.jsonObject.keys)
        assertEquals(3, json["palette"]!!.jsonArray.size)
        assertEquals("1234", json["durationMs"]!!.jsonPrimitive.content)
        assertEquals("true", json["liked"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lyrics json has synced and plain, each possibly null`() {
        val none = WebShapes.lyrics(null, null)
        assertEquals(JsonNull, none["synced"])
        assertEquals(JsonNull, none["plain"])
        val some = WebShapes.lyrics(listOf(0L to "a", 1500L to "b"), "a\nb")
        val synced = some["synced"]!!.jsonArray
        assertEquals(2, synced.size)
        assertEquals("1500", synced[1].jsonObject["ms"]!!.jsonPrimitive.content)
        assertEquals("b", synced[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `playlist and voice shapes`() {
        val tracks = listOf(WebTrack("v", "T", "A", 1000L, null))
        val list = WebShapes.playlist("P", "Me", true, tracks)
        assertEquals(setOf("title", "owner", "editable", "items"), list.keys)
        assertEquals(setOf("videoId", "title", "artists", "durationMs", "art"), list["items"]!!.jsonArray[0].jsonObject.keys)
        val plain = WebShapes.voice(null, "pause", "Paused", null)
        assertEquals(setOf("action", "result"), plain.keys)
        val full = WebShapes.voice("play x", "choices", "Which?", listOf(WebItem("a", "song", "t", "s", null, videoId = "a")), value = "2")
        assertEquals(setOf("heard", "action", "result", "candidates", "value"), full.keys)
    }

    // ---- Inputs and lyrics --------------------------------------------------------------------

    @Test
    fun `ids and queries are checked`() {
        assertTrue(WebInputs.isVideoId("dQw4w9WgXcQ"))
        assertFalse(WebInputs.isVideoId("short"))
        assertFalse(WebInputs.isVideoId("dQw4w9WgXc!"))
        assertEquals("PL123", WebInputs.playlistId("VLPL123"))
        assertEquals("LM", WebInputs.playlistId("LM"))
        assertNull(WebInputs.playlistId("a"))
        assertNull(WebInputs.playlistId("../etc"))
        assertEquals("VLPL123", WebInputs.browseIdFor("PL123"))
        assertEquals("VLPL123", WebInputs.browseIdFor("VLPL123"))
        assertEquals("MPREb_abc", WebInputs.browseIdFor("MPREb_abc"))
        assertEquals("UCabc", WebInputs.browseIdFor("UCabc"))
        assertEquals("hello", WebInputs.query("  hello "))
        assertNull(WebInputs.query("   "))
        assertNull(WebInputs.query(null))
        assertNull(WebInputs.query("x".repeat(201)))
        assertNull(WebInputs.query("a\u0000b"))
    }

    @Test
    fun `body fields are read strictly`() {
        val body = WebJson.parseObject("""{"liked": true, "videoId": "abcdefghijk", "n": 5, "s": "true"}""".toByteArray())!!
        assertEquals(true, WebInputs.boolField(body, "liked"))
        assertNull(WebInputs.boolField(body, "s"))
        assertNull(WebInputs.boolField(body, "n"))
        assertNull(WebInputs.boolField(body, "missing"))
        assertEquals("abcdefghijk", WebInputs.stringField(body, "videoId"))
        assertNull(WebInputs.stringField(body, "n"))
    }

    @Test
    fun `lyrics are split into synced and plain`() {
        val synced = WebLyrics.shape(listOf(0L to "one", 2000L to "two", 4000L to ""))
        assertNotNull(synced.synced)
        assertEquals(3, synced.synced!!.size)
        assertEquals("one\ntwo", synced.plain)
        val plain = WebLyrics.shape(listOf(-1L to "one", -1L to "two"))
        assertNull(plain.synced)
        assertEquals("one\ntwo", plain.plain)
        val same = WebLyrics.shape(listOf(0L to "one", 0L to "two"))
        assertNull(same.synced)
        val empty = WebLyrics.shape(listOf(0L to " "))
        assertNull(empty.synced)
        assertNull(empty.plain)
    }

    @Test
    fun `voice failures map to statuses`() {
        assertEquals(503, WebVoiceErrors.status(VoiceErrors.MODEL_MISSING))
        assertEquals(503, WebVoiceErrors.status(VoiceErrors.PROGRAM_MISSING))
        assertEquals(504, WebVoiceErrors.status(VoiceErrors.TIMEOUT))
        assertEquals(400, WebVoiceErrors.status("bad_audio"))
        assertEquals(400, WebVoiceErrors.status("too_short"))
        assertEquals(502, WebVoiceErrors.status("unexpected"))
        assertTrue(WebVoiceErrors.message("bad_audio").contains("WAV"))
    }

    // ---- Model mapping ------------------------------------------------------------------------

    private fun song(id: String = "dQw4w9WgXcQ", title: String = "Song", artist: String = "Artist", thumb: String? = goodArt) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = thumb, durationText = "3:05")

    @Test
    fun `a song maps to an item and a track`() {
        val item = WebMap.songItem(song())
        assertEquals("song", item.type)
        assertEquals("dQw4w9WgXcQ", item.id)
        assertEquals("dQw4w9WgXcQ", item.videoId)
        assertNotNull(item.art)
        assertTrue(item.art!!.contains("w480-h480"))
        val track = WebMap.track(song())
        assertEquals(185_000L, track.durationMs)
        val foreign = WebMap.songItem(song(thumb = "https://example.com/a.jpg"))
        assertNull(foreign.art)
    }

    @Test
    fun `browse items are typed and ids are normalised`() {
        val album = WebMap.browseItem(BrowseItem("MPREb_abc", "Al", "Ar", goodArt, BrowseType.ALBUM))
        assertEquals("album", album.type)
        assertEquals("MPREb_abc", album.id)
        val playlist = WebMap.browseItem(BrowseItem("VLPL123", "Pl", "Me", null, BrowseType.PLAYLIST))
        assertEquals("playlist", playlist.type)
        assertEquals("PL123", playlist.id)
        assertEquals("VLPL123", playlist.browseId)
        val artist = WebMap.browseItem(BrowseItem("UC123", "Ar", "", null, BrowseType.ARTIST))
        assertEquals("artist", artist.type)
        val guessed = WebMap.browseItem(BrowseItem("UC999", "Ar", "", null, BrowseType.OTHER))
        assertEquals("artist", guessed.type)
    }

    @Test
    fun `shelf cards become songs or pages or nothing`() {
        val track = WebMap.shelfItem(ShelfItem("T", "S", null, "dQw4w9WgXcQ", null))
        assertEquals("song", track!!.type)
        val page = WebMap.shelfItem(ShelfItem("T", "S", null, null, "MPREb_abc"))
        assertEquals("album", page!!.type)
        assertNull(WebMap.shelfItem(ShelfItem("T", "S", null, null, null)))
        val badVideo = WebMap.shelfItem(ShelfItem("T", "S", null, "x", "VLPL1"))
        assertEquals("playlist", badVideo!!.type)
    }

    @Test
    fun `search results are grouped in the desktop order and empty sections dropped`() {
        val results = listOf(
            SearchResult.Browse(BrowseItem("UC1", "Artist", "", null, BrowseType.ARTIST)),
            SearchResult.Track(song(id = "aaaaaaaaaaa")),
            SearchResult.TopTrack(song(id = "bbbbbbbbbbb")),
            SearchResult.Browse(BrowseItem("MPREb_1", "Album", "", null, BrowseType.ALBUM)),
        )
        val sections = WebMap.searchSections(results)
        assertEquals(listOf("Top result", "Songs", "Albums", "Artists"), sections.map { it.first })
        assertEquals("bbbbbbbbbbb", sections[0].second[0].id)
        assertTrue(WebMap.searchSections(emptyList()).isEmpty())
    }

    // ---- Library cache ------------------------------------------------------------------------

    @Test
    fun `the library is fetched once a minute and can be invalidated`() = runBlocking {
        var clock = 1_000L
        var fetches = 0
        val page = LibraryPage(likedSongs = listOf(song(id = "likedlikedl")), librarySongs = emptyList(), shelves = emptyList())
        val library = DesktopWebLibrary(fetch = { fetches += 1; Result.success(page) }, now = { clock })
        library.get()
        library.get()
        assertEquals(1, fetches)
        assertEquals(setOf("likedlikedl"), library.likedIds)
        clock += 61_000L
        library.get()
        assertEquals(2, fetches)
        library.invalidate()
        library.get()
        assertEquals(3, fetches)
        library.get(force = true)
        assertEquals(4, fetches)
    }

    @Test
    fun `a failed library fetch is returned and not kept`() = runBlocking {
        var fetches = 0
        val library = DesktopWebLibrary(fetch = { fetches += 1; Result.failure(IllegalStateException("x")) }, now = { 0L })
        assertTrue(library.get().isFailure)
        assertTrue(library.get().isFailure)
        assertEquals(2, fetches)
        assertNull(library.peek())
    }

    // ---- Voice resolver with fakes ------------------------------------------------------------

    private fun voice(songs: List<Song> = emptyList(), heard: String = "pause") = DesktopWebVoice(
        library = { null },
        search = { songs },
        recognize = { Result.success(heard) },
    )

    private fun bodyOf(response: WebResponse): JsonObject =
        WebJson.parseObject((response as WebResponse.Bytes).body)!!

    @Test
    fun `simple commands resolve without any search`() = runBlocking {
        val v = voice()
        assertEquals("pause", v.resolve("pause").action)
        assertEquals("next", v.resolve("next").action)
        assertEquals("previous", v.resolve("previous").action)
        assertEquals("none", v.resolve("").action)
    }

    @Test
    fun `a clear song request resolves to play with an item and nothing is executed`() = runBlocking {
        val songs = listOf(song(id = "bohemianrhap", title = "Bohemian Rhapsody", artist = "Queen"))
        val answer = voice(songs).resolve("play bohemian rhapsody")
        assertEquals("play", answer.action)
        assertEquals("bohemianrhap", answer.item!!.videoId)
        assertTrue(answer.result.startsWith("Playing"))
    }

    @Test
    fun `nothing found says so`() = runBlocking {
        val answer = voice(emptyList()).resolve("play zzzzqqqq")
        assertEquals("none", answer.action)
        assertNull(answer.item)
    }

    @Test
    fun `the voice endpoint answers in the contract shape`() = runBlocking {
        val response = voice(heard = "pause").handle(wav(16_000))
        val json = bodyOf(response)
        assertEquals("pause", json["action"]!!.jsonPrimitive.content)
        assertEquals("pause", json["heard"]!!.jsonPrimitive.content)
        assertTrue("result" in json.keys)
        assertFalse("candidates" in json.keys)
    }

    @Test
    fun `the voice endpoint refuses bad audio and reports recogniser failures`() = runBlocking {
        val bad = voice().handle("nope".toByteArray()) as WebResponse.Bytes
        assertEquals(400, bad.status)
        assertEquals("bad_audio", bodyOf(bad)["code"]!!.jsonPrimitive.content)
        val failing = DesktopWebVoice(
            library = { null },
            search = { emptyList() },
            recognize = { Result.failure(VoiceException(VoiceErrors.MODEL_MISSING)) },
        )
        val missing = failing.handle(wav(16_000)) as WebResponse.Bytes
        assertEquals(503, missing.status)
        assertEquals("model_missing", bodyOf(missing)["code"]!!.jsonPrimitive.content)
        val blank = voice(heard = "  ").handle(wav(16_000))
        assertEquals("none", bodyOf(blank)["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the router serves every contract path`() {
        val router = DesktopWebRouter()
        DesktopWebApi(events = DesktopWebEventHub()).install(router)
        val gets = listOf(
            "/api/home", "/api/search", "/api/suggest", "/api/library", "/api/playlist/PL1",
            "/api/track/dQw4w9WgXcQ", "/api/stream/dQw4w9WgXcQ", "/api/lyrics/dQw4w9WgXcQ", "/api/art",
        )
        for (path in gets) assertTrue(router.match("GET", path) is WebMatch.Found, path)
        assertTrue(router.match("POST", "/api/like/dQw4w9WgXcQ") is WebMatch.Found)
        assertTrue(router.match("POST", "/api/playlist/PL1/add") is WebMatch.Found)
        assertTrue(router.match("POST", "/api/voice") is WebMatch.Found)
        assertTrue(router.match("GET", "/api/fetch") is WebMatch.NotFound)
        assertTrue(router.match("POST", "/api/home") is WebMatch.MethodNotAllowed)
    }
}
