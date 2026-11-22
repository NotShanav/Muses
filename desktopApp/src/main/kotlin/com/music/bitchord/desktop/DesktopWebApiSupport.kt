package com.music.bitchord.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
 * WEB2: the pure pieces behind the API adapters. Nothing here touches the network, the account, a
 * window or Compose, so all of it is unit-tested without a socket.
 *
 *  - [WebHosts]      strict host allowlists for the stream and artwork proxies
 *  - [WebArt]        the `/api/art?u=` URL the pages receive instead of a raw artwork URL
 *  - [WebStreamPlan] range decision, response headers, upstream windows, content type
 *  - [WebWav]        16 kHz mono 16-bit WAV reader for `/api/voice`
 *  - [WebPalette]    colour to `#rrggbb`
 *  - [WebItem] / [WebTrack] / [WebShapes]  the JSON shapes of the contract
 */

/** Which hosts the proxies may contact. A request can never name a host of its own. */
internal object WebHosts {

    /** Stream bytes come only from here (what [com.music.bitchord.data.innertube.StreamResolver] returns). */
    val STREAM_SUFFIXES: List<String> = listOf("googlevideo.com")

    /** Cover art and avatars come only from here. */
    val ART_SUFFIXES: List<String> = listOf("googleusercontent.com", "ggpht.com", "ytimg.com")

    /**
     * True for an `https` URL with a real host that is, or is a subdomain of, one of [suffixes].
     * Refused: other schemes, user info, a port other than 443, an IP literal, odd host characters,
     * and a name that merely ends with a suffix (`evilgooglevideo.com`).
     */
    fun isAllowed(url: String?, suffixes: List<String>): Boolean {
        if (url.isNullOrBlank() || url.length > MAX_URL_CHARS) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty() || host.length > 253) return false
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        if (host.first() == '.' || host.last() == '.' || host.contains("..")) return false
        return suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    fun isAllowedStream(url: String?): Boolean = isAllowed(url, STREAM_SUFFIXES)

    fun isAllowedArt(url: String?): Boolean = isAllowed(url, ART_SUFFIXES)

    const val MAX_URL_CHARS = 2048
}

/** `/api/art?u=<encoded https url>`: the only way a page refers to artwork. */
internal object WebArt {

    private const val PATH = "/api/art?u="

    /** The proxy URL for [rawUrl], or null when the URL is empty or not on the art allowlist. */
    fun proxyUrl(rawUrl: String?): String? {
        if (!WebHosts.isAllowedArt(rawUrl)) return null
        return PATH + URLEncoder.encode(rawUrl, StandardCharsets.UTF_8)
    }
}

/** Response status for a stream request, from the browser's `Range` header and the file size. */
internal sealed class WebStreamDecision {
    /** [status] is 200 (whole file) or 206 (a range). Bytes [start]..[endInclusive]. */
    class Send(val status: Int, val start: Long, val endInclusive: Long, val total: Long) : WebStreamDecision() {
        val length: Long get() = endInclusive - start + 1
    }

    /** 416: the range starts past the end. */
    class Unsatisfiable(val total: Long) : WebStreamDecision()
}

internal object WebStreamPlan {

    /** One upstream request asks for at most this many bytes (like [DesktopRangeStream], within what Google serves). */
    const val WINDOW_BYTES = 1L shl 20

    /** Bytes copied per write: the bounded buffer, so a whole file is never held in memory. */
    const val COPY_BUFFER = 16 * 1024

    const val MAX_STREAMS = 3
    const val MAX_ART_FETCHES = 8
    const val MAX_ART_BYTES = 4 * 1024 * 1024

    fun decide(rangeHeader: String?, total: Long): WebStreamDecision {
        if (total <= 0L) return WebStreamDecision.Unsatisfiable(maxOf(total, 0L))
        return when (val parsed = WebRange.parse(rangeHeader, total)) {
            WebRangeResult.Full -> WebStreamDecision.Send(200, 0L, total - 1, total)
            is WebRangeResult.Partial -> WebStreamDecision.Send(206, parsed.start, parsed.endInclusive, total)
            WebRangeResult.Unsatisfiable -> WebStreamDecision.Unsatisfiable(total)
        }
    }

    /** `Content-Range`, `Content-Length`, `Accept-Ranges` for a [WebStreamDecision.Send]. */
    fun headers(send: WebStreamDecision.Send, contentType: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Content-Type"] = contentType
        map["Accept-Ranges"] = "bytes"
        map["Cache-Control"] = "private, no-store"
        map["X-Content-Type-Options"] = "nosniff"
        if (send.status == 206) map["Content-Range"] = WebRange.contentRange(send.start, send.endInclusive, send.total)
        return map
    }

    /** The `Content-Range` header of a 416 answer. */
    fun unsatisfiedHeaders(total: Long): Map<String, String> =
        mapOf("Content-Range" to WebRange.unsatisfiedRange(total), "Accept-Ranges" to "bytes")

    /** The upstream `Range` header for the window that starts at [position]; null once the end is passed. */
    fun windowHeader(position: Long, endInclusive: Long, window: Long = WINDOW_BYTES): String? {
        if (position < 0 || position > endInclusive) return null
        val last = minOf(endInclusive, position + window - 1)
        return "bytes=$position-$last"
    }

    /** `clen` (the whole file's size) from a googlevideo URL, or null. */
    fun contentLengthOf(url: String): Long? = queryValue(url, "clen")?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * The type the browser is told. The URL's own `mime` is first (it names what the bytes are),
     * then the upstream header, else `audio/mp4`. Only `audio/*` or `video/webm` shaped values are
     * accepted, without parameters; Opus in WebM is returned as `audio/webm`, honestly.
     */
    fun contentType(url: String, upstream: String?): String {
        val fromUrl = queryValue(url, "mime")?.let(::cleanType)
        if (fromUrl != null) return fromUrl
        val fromHeader = upstream?.let(::cleanType)
        if (fromHeader != null) return fromHeader
        return "audio/mp4"
    }

    private fun cleanType(raw: String): String? {
        val base = raw.substringBefore(';').trim().lowercase()
        if (!TYPE_SHAPE.matches(base)) return null
        return when {
            base == "video/webm" -> "audio/webm"
            base == "video/mp4" -> "audio/mp4"
            base.startsWith("audio/") -> base
            else -> null
        }
    }

    private val TYPE_SHAPE = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,40}")

    /** One raw query value, decoded; null when absent. */
    fun queryValue(url: String, name: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) != name) continue
            return runCatching { URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) }.getOrNull()
        }
        return null
    }

    /** True for the upstream statuses after which the resolved URL is refreshed once. */
    fun shouldRefresh(status: Int): Boolean = status == 403 || status == 410
}

/** What a WAV body held. */
internal sealed class WebWavResult {
    class Ok(val samples: ShortArray) : WebWavResult()
    class Bad(val code: String) : WebWavResult()
}

internal object WebWav {

    const val SAMPLE_RATE = 16_000

    /** 15 s, the contract's cap. */
    const val MAX_SAMPLES = 15 * SAMPLE_RATE

    /** Under 0.2 s there is nothing to recognise. */
    const val MIN_SAMPLES = SAMPLE_RATE / 5

    /** Codes: `bad_audio` (not a WAV we accept), `too_short`, `too_long`. */
    fun parse(bytes: ByteArray): WebWavResult {
        if (bytes.size < 44) return WebWavResult.Bad("bad_audio")
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return WebWavResult.Bad("bad_audio")
        var offset = 12
        var formatOk = false
        var dataStart = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = tag(bytes, offset)
            val declared = leInt(bytes, offset + 4).toLong() and 0xFFFFFFFFL
            val body = offset + 8
            if (id == "fmt ") {
                if (declared < 16 || body + 16 > bytes.size) return WebWavResult.Bad("bad_audio")
                val format = leShort(bytes, body)
                val channels = leShort(bytes, body + 2)
                val rate = leInt(bytes, body + 4)
                val bits = leShort(bytes, body + 14)
                formatOk = format == 1 && channels == 1 && rate == SAMPLE_RATE && bits == 16
                if (!formatOk) return WebWavResult.Bad("bad_audio")
            } else if (id == "data") {
                val room = (bytes.size - body).toLong()
                // A streamed WAV may state 0 or 0xFFFFFFFF: take what is there.
                val length = if (declared == 0L || declared > room) room else declared
                dataStart = body
                dataLength = length.toInt()
                break
            }
            val next = body.toLong() + declared + (declared and 1L)
            if (next > bytes.size) break
            offset = next.toInt()
        }
        if (!formatOk || dataStart < 0) return WebWavResult.Bad("bad_audio")
        val count = dataLength / 2
        if (count < MIN_SAMPLES) return WebWavResult.Bad("too_short")
        if (count > MAX_SAMPLES) return WebWavResult.Bad("too_long")
        val samples = ShortArray(count)
        var at = dataStart
        for (i in 0 until count) {
            samples[i] = leShort(bytes, at).toShort()
            at += 2
        }
        return WebWavResult.Ok(samples)
    }

    private fun tag(b: ByteArray, at: Int): String =
        if (at + 4 > b.size) "" else String(b, at, 4, StandardCharsets.ISO_8859_1)

    private fun leShort(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}

internal object WebPalette {

    /** Shown when a cover cannot be read: three quiet dark tones. */
    val FALLBACK: List<String> = listOf("#1c1c22", "#2a2a35", "#3a3a48")

    /** `#rrggbb` from channels in 0..1 (values outside are clamped). */
    fun hex(red: Float, green: Float, blue: Float): String {
        fun channel(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 255f)
        return String.format("#%02x%02x%02x", channel(red), channel(green), channel(blue))
    }

    /** Exactly three entries: the first three given, padded from [FALLBACK] when fewer. */
    fun three(hexColours: List<String>): List<String> {
        val out = ArrayList<String>(3)
        for (c in hexColours) if (out.size < 3) out.add(c)
        var i = out.size
        while (out.size < 3) {
            out.add(FALLBACK[i])
            i += 1
        }
        return out
    }
}

/** An `Item` of the contract. [art] is already a `/api/art?u=` URL or null. */
internal data class WebItem(
    val id: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val art: String?,
    val videoId: String? = null,
    val browseId: String? = null,
)

/** A `Track` of the contract. */
internal data class WebTrack(
    val videoId: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
    val art: String?,
)

internal object WebShapes {

    fun item(item: WebItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("type", item.type)
        put("title", item.title)
        put("subtitle", item.subtitle)
        put("art", item.art?.let { JsonPrimitive(it) } ?: JsonNull)
        if (item.videoId != null) put("videoId", item.videoId)
        if (item.browseId != null) put("browseId", item.browseId)
    }

    fun items(list: List<WebItem>): JsonArray = buildJsonArray { list.forEach { add(item(it)) } }

    /** `kind`: `songs` when every item is a track, `browse` when none is, else `mixed`. */
    fun kindOf(list: List<WebItem>): String = when {
        list.isNotEmpty() && list.all { it.type == "song" } -> "songs"
        list.none { it.type == "song" } -> "browse"
        else -> "mixed"
    }

    fun shelf(title: String, list: List<WebItem>): JsonObject = buildJsonObject {
        put("title", title)
        put("kind", kindOf(list))
        put("items", items(list))
    }

    fun track(track: WebTrack): JsonObject = buildJsonObject {
        put("videoId", track.videoId)
        put("title", track.title)
        put("artists", track.artists)
        put("durationMs", track.durationMs)
        put("art", track.art?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun strings(list: List<String>): JsonArray = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

    /** `/api/track/{videoId}` -> `{title, artists, album, durationMs, art:{small,large}, palette, liked}` (+ `videoId`). */
    fun trackDetails(
        videoId: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long,
        artSmall: String?,
        artLarge: String?,
        palette: List<String>,
        liked: Boolean,
    ): JsonObject = buildJsonObject {
        put("videoId", videoId)
        put("title", title)
        put("artists", strings(artists))
        put("album", album?.let { JsonPrimitive(it) } ?: JsonNull)
        put("durationMs", durationMs)
        put(
            "art",
            buildJsonObject {
                put("small", artSmall?.let { JsonPrimitive(it) } ?: JsonNull)
                put("large", artLarge?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        put("palette", strings(WebPalette.three(palette)))
        put("liked", liked)
    }

    /** `/api/lyrics/{videoId}` -> `{synced:[{ms,text}]|null, plain:string|null}`. */
    fun lyrics(synced: List<Pair<Long, String>>?, plain: String?): JsonObject = buildJsonObject {
        put(
            "synced",
            if (synced == null) {
                JsonNull
            } else {
                buildJsonArray {
                    synced.forEach { (ms, text) ->
                        add(
                            buildJsonObject {
                                put("ms", ms)
                                put("text", text)
                            },
                        )
                    }
                }
            },
        )
        put("plain", plain?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** `/api/playlist/{id}` -> `{title, owner, editable, items:[Track]}`. */
    fun playlist(title: String, owner: String, editable: Boolean, tracks: List<WebTrack>): JsonObject = buildJsonObject {
        put("title", title)
        put("owner", owner)
        put("editable", editable)
        put("items", buildJsonArray { tracks.forEach { add(track(it)) } })
    }

    /** `/api/voice` answer. [item] and [value] are the only fields added to the contract (see HANDOFF). */
    fun voice(
        heard: String?,
        action: String,
        result: String,
        candidates: List<WebItem>?,
        item: WebItem? = null,
        value: String? = null,
    ): JsonObject = buildJsonObject {
        if (heard != null) put("heard", heard)
        put("action", action)
        put("result", result)
        if (candidates != null) put("candidates", items(candidates))
        if (item != null) put("item", item(item))
        if (value != null) put("value", value)
    }
}

/** Query and body validation shared by the handlers. */
internal object WebInputs {

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,80}")

    const val MAX_QUERY_CHARS = 200

    fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

    /** A playlist id as the contract carries it: the raw id, or `LM` for Liked music. A `VL` prefix is accepted and dropped. */
    fun playlistId(raw: String): String? {
        val id = raw.removePrefix("VL")
        return if (PLAYLIST_ID.matches(id)) id else null
    }

    /** The browse id a page id stands for: albums, artists and `FE...` pages as they are, a playlist id gets the `VL` prefix. */
    fun browseIdFor(id: String): String = when {
        id.startsWith("VL") -> id
        id.startsWith("MPREb_") || id.startsWith("OLAK5uy_") || id.startsWith("UC") || id.startsWith("FE") -> id
        else -> "VL$id"
    }

    /** A trimmed search text, or null when blank or over [MAX_QUERY_CHARS]. */
    fun query(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_QUERY_CHARS) return null
        if (text.any { it.isISOControl() }) return null
        return text
    }

    /** `{"liked": true}` -> true; null when the field is missing or not a boolean. */
    fun boolField(body: JsonObject, name: String): Boolean? =
        (body[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    fun stringField(body: JsonObject, name: String): String? =
        (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** True when [element] is the JSON null. */
    fun isNull(element: JsonElement?): Boolean = element == null || element is JsonNull
}

/** Lyrics lines to the contract's `synced` / `plain` split. */
internal object WebLyrics {

    class Shape(val synced: List<Pair<Long, String>>?, val plain: String?)

    /**
     * [lines] are (start ms, text). Synced when the lines carry more than one distinct, non-negative
     * start time (unsynced lyrics have none, or the same one for every line). [plain] is the text
     * of the lines either way; both are null when there is no text at all.
     */
    fun shape(lines: List<Pair<Long, String>>): Shape {
        val withText = lines.filter { it.second.isNotBlank() }
        if (withText.isEmpty()) return Shape(null, null)
        val starts = withText.map { it.first }.filter { it >= 0L }.distinct()
        val synced = if (starts.size > 1) lines.filter { it.first >= 0L } else null
        return Shape(synced, withText.joinToString("\n") { it.second })
    }
}

/** Voice failures to an HTTP status and a short message. Codes are [VoiceErrors] plus `bad_audio`, `too_long`. */
internal object WebVoiceErrors {

    fun status(code: String): Int = when (code) {
        VoiceErrors.PROGRAM_MISSING, VoiceErrors.MODEL_MISSING, VoiceErrors.LOW_MEMORY -> 503
        VoiceErrors.TIMEOUT -> 504
        "bad_audio", "too_short", "too_long", VoiceErrors.NO_AUDIO, VoiceErrors.MIC_SILENT -> 400
        else -> 502
    }

    fun message(code: String): String = when (code) {
        VoiceErrors.PROGRAM_MISSING -> "Speech recognition is not installed on the computer"
        VoiceErrors.MODEL_MISSING -> "The speech model is not installed on the computer"
        VoiceErrors.LOW_MEMORY -> "Not enough free memory for speech recognition"
        VoiceErrors.TIMEOUT -> "Speech recognition took too long"
        "bad_audio" -> "The audio must be 16 kHz mono 16-bit WAV"
        "too_short" -> "That was too short"
        "too_long" -> "That was too long"
        VoiceErrors.NO_AUDIO, VoiceErrors.MIC_SILENT -> "No sound was heard"
        else -> "Speech recognition failed"
    }
}
