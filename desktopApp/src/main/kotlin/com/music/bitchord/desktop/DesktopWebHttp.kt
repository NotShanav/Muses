package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/*
 * WEB1: the small, pure pieces of the embedded web server. Nothing here touches a socket, a
 * window or the account, so every function is unit-tested without a network.
 *
 *  - [WebLimits]  sizes and counts the server enforces
 *  - [WebRequest] / [WebResponse]  what a handler receives and returns
 *  - [WebPaths]   percent-decoding and query parsing
 *  - [WebBodies]  request body size cap
 *  - [WebJson]    JSON responses and the error envelope `{error, code}`
 *  - [WebSse]     Server-Sent Events text formatting
 *  - [WebRange]   `Range` header parsing (reused by the stream proxy in WEB2)
 */

internal object WebLimits {
    /** Largest JSON request body (like, add-to-playlist, pairing form). */
    const val JSON_BODY = 64 * 1024

    /** 15 s of 16 kHz mono 16-bit WAV is about 480 KB; this leaves room for webm/opus or a header. */
    const val VOICE_BODY = 1024 * 1024

    /** Longest raw query string the server accepts. */
    const val QUERY_CHARS = 4096

    /** Open `/api/events` connections at once. */
    const val MAX_EVENT_STREAMS = 4

    /** Most query parameters read from one request. */
    const val MAX_QUERY_PARAMS = 32

    /** Longest decoded query key or value kept. */
    const val QUERY_VALUE_CHARS = 2048
}

/** One request after routing. [headers] has lower-case names; [body] is already size-capped. */
internal class WebRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val params: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun withParams(found: Map<String, String>): WebRequest =
        WebRequest(method, path, query, headers, found, body)

    fun withBody(bytes: ByteArray): WebRequest =
        WebRequest(method, path, query, headers, params, bytes)
}

internal sealed class WebResponse {

    /** A whole response held in memory (JSON, static files, errors). */
    class Bytes(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    ) : WebResponse()

    /**
     * A response written as it is produced (SSE now, audio bytes in WEB2).
     * [contentLength] >= 0 is sent exactly; a negative value means chunked.
     * [onFinish] always runs once, whether or not the writer ran or failed, so a counter taken
     * for the response can be given back.
     */
    class Stream(
        val status: Int,
        val headers: Map<String, String>,
        val contentLength: Long,
        val onFinish: (() -> Unit)? = null,
        val writer: suspend (OutputStream) -> Unit,
    ) : WebResponse()
}

internal object WebPaths {

    /**
     * Decodes `%XX` escapes as UTF-8. Returns null for a broken escape. `+` becomes a space only
     * when [plusAsSpace] (query strings and forms, never paths).
     */
    fun percentDecode(text: String, plusAsSpace: Boolean): String? {
        if (text.indexOf('%') < 0 && !(plusAsSpace && text.indexOf('+') >= 0)) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' -> {
                    if (i + 2 >= text.length) return null
                    val hi = Character.digit(text[i + 1], 16)
                    val lo = Character.digit(text[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                }
                c == '+' && plusAsSpace -> {
                    out.write(' '.code)
                    i += 1
                }
                c.code < 0x80 -> {
                    out.write(c.code)
                    i += 1
                }
                else -> {
                    val codePoint = text.codePointAt(i)
                    out.write(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8))
                    i += Character.charCount(codePoint)
                }
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** `a=1&b=two%20words` to a map; the first value of a repeated key wins; bad pairs are skipped. */
    fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (result.size >= WebLimits.MAX_QUERY_PARAMS) break
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val key = percentDecode(rawKey, plusAsSpace = true) ?: continue
            val value = percentDecode(rawValue, plusAsSpace = true) ?: continue
            if (key.isEmpty() || key.length > WebLimits.QUERY_VALUE_CHARS || value.length > WebLimits.QUERY_VALUE_CHARS) continue
            if (key !in result) result[key] = value
        }
        return result
    }

    /**
     * Splits a request path into its segments. "/" is an empty list. Returns null for a path that
     * does not start with "/" or has an empty segment ("//", trailing "/"), so "/api/ping/" is not
     * "/api/ping".
     */
    fun segments(path: String): List<String>? {
        if (path.isEmpty() || path[0] != '/') return null
        if (path == "/") return emptyList()
        val parts = path.substring(1).split('/')
        if (parts.any { it.isEmpty() }) return null
        return parts
    }
}

internal object WebBodies {

    /** True when the declared Content-Length is already over [max] (answer 413 before reading). */
    fun declaredTooLarge(contentLength: String?, max: Int): Boolean {
        val declared = contentLength?.trim()?.toLongOrNull() ?: return false
        return declared > max
    }

    /** Reads at most [max] bytes; returns null when the stream holds more (never keeps the excess). */
    fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(max, 8192).coerceAtLeast(32))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

internal object WebJson {

    private val parser = Json { ignoreUnknownKeys = true }

    private const val JSON_TYPE = "application/json; charset=utf-8"
    private val noStore = mapOf("Cache-Control" to "no-store")

    fun ok(element: JsonElement, status: Int = 200): WebResponse.Bytes =
        WebResponse.Bytes(status, JSON_TYPE, element.toString().toByteArray(StandardCharsets.UTF_8), noStore)

    /**
     * The error envelope: `{"error": "<short English message>", "code": "<stable machine id>"}`.
     * The HTTP status carries the number.
     */
    fun error(status: Int, code: String, message: String): WebResponse.Bytes =
        ok(
            buildJsonObject {
                put("error", message)
                put("code", code)
            },
            status,
        )

    /** The body as a JSON object, or null when it is empty, not JSON, or not an object. */
    fun parseObject(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        return runCatching { parser.parseToJsonElement(String(body, StandardCharsets.UTF_8)) as? JsonObject }
            .getOrNull()
    }
}

internal object WebSse {

    /** A comment line is sent this often so proxies and browsers keep the connection open. */
    const val KEEP_ALIVE_MILLIS = 20_000L

    /** Tells EventSource to wait 3 s before reconnecting. */
    const val RETRY_LINE = "retry: 3000\n\n"

    /**
     * One event. Every line of [data] gets its own `data:` field, so a newline in the payload cannot
     * end the event early. [type] (the optional `event:` name) is dropped if it holds a line break.
     */
    fun event(data: String, type: String? = null): String = buildString {
        if (type != null && type.isNotEmpty() && type.none { it == '\n' || it == '\r' }) {
            append("event: ").append(type).append('\n')
        }
        for (line in data.split("\r\n", "\n", "\r")) {
            append("data: ").append(line).append('\n')
        }
        append('\n')
    }

    /** A comment (ignored by EventSource), used as the keep-alive. */
    fun comment(text: String = "keep-alive"): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ')
        return ": $clean\n\n"
    }
}

/** What a `Range` request header asks for, given the size of the thing. */
internal sealed class WebRangeResult {
    /** No usable header (absent, malformed, other unit, or several ranges): answer 200 with everything. */
    object Full : WebRangeResult()

    /** Answer 206 with bytes [start]..[endInclusive]. */
    class Partial(val start: Long, val endInclusive: Long) : WebRangeResult() {
        val length: Long get() = endInclusive - start + 1
    }

    /** The range starts past the end: answer 416 with `Content-Range: bytes * / size`. */
    object Unsatisfiable : WebRangeResult()
}

internal object WebRange {

    /**
     * Parses one `bytes=` range against [size]: `a-b`, `a-` and the suffix form `-n`. End values past
     * the end are clamped. Anything the helper cannot trust (other units, several ranges, junk, a
     * reversed range) is [WebRangeResult.Full], as RFC 9110 tells a server to ignore such a header.
     */
    fun parse(header: String?, size: Long): WebRangeResult {
        if (header == null || size < 0) return WebRangeResult.Full
        val text = header.trim()
        if (!text.regionMatches(0, "bytes=", 0, 6, ignoreCase = true)) return WebRangeResult.Full
        val spec = text.substring(6).trim()
        if (spec.isEmpty() || spec.contains(',')) return WebRangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return WebRangeResult.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull() ?: return WebRangeResult.Full
            if (suffix <= 0L || size == 0L) return WebRangeResult.Unsatisfiable
            val start = (size - suffix).coerceAtLeast(0L)
            return WebRangeResult.Partial(start, size - 1)
        }
        val start = first.toLongOrNull() ?: return WebRangeResult.Full
        if (start < 0) return WebRangeResult.Full
        val explicitEnd: Long? = if (last.isEmpty()) null else (last.toLongOrNull() ?: return WebRangeResult.Full)
        if (explicitEnd != null && explicitEnd < start) return WebRangeResult.Full
        if (start >= size) return WebRangeResult.Unsatisfiable
        return WebRangeResult.Partial(start, minOf(explicitEnd ?: (size - 1), size - 1))
    }

    /** `Content-Range` for a partial answer. */
    fun contentRange(start: Long, endInclusive: Long, size: Long): String = "bytes $start-$endInclusive/$size"

    /** `Content-Range` for a 416 answer. */
    fun unsatisfiedRange(size: Long): String = "bytes */$size"
}
