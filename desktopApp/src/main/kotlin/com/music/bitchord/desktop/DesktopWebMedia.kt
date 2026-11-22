package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore

/*
 * WEB2: `/api/stream/{videoId}` and `/api/art?u=`.
 *
 * The stream proxy only forwards bytes: the browser's `<audio>` decodes them. It asks the existing
 * [StreamResolver] for the URL (which caches it for its validity window and knows how to refuse a
 * dead one), then reads the requested byte range from upstream in windows of [WebStreamPlan.WINDOW_BYTES]
 * and copies each through a 16 KB buffer. A whole file is never held in memory.
 *
 * Neither proxy takes a URL from the request except `/api/art?u=`, and that one is checked against
 * [WebHosts.ART_SUFFIXES] before and after every redirect. Nothing here logs a URL, a header or a
 * video id.
 */
internal class DesktopWebMedia(
    private val resolve: suspend (String) -> String = { id -> StreamResolver.resolve(id) },
    private val headersFor: (String) -> Map<String, String> = { url -> StreamResolver.mediaHeadersFor(url) },
    private val onRefused: (String, Int) -> Unit = { url, code -> StreamResolver.onPlaybackRefused(url, code) },
    private val client: HttpClient = defaultClient,
) {

    private val streamSlots = Semaphore(WebStreamPlan.MAX_STREAMS)
    private val artSlots = Semaphore(WebStreamPlan.MAX_ART_FETCHES)

    // ---- Stream --------------------------------------------------------------------------------

    /** What opening the first window gave. */
    private class Opened(
        val url: String,
        val decision: WebStreamDecision.Send,
        val contentType: String,
        val first: InputStream,
    )

    suspend fun stream(videoId: String, rangeHeader: String?): WebResponse {
        if (!WebInputs.isVideoId(videoId)) return WebJson.error(404, "not_found", "Unknown track")
        if (!streamSlots.tryAcquire()) return WebJson.error(429, "too_many_streams", "Too many streams at once")
        var handedOver = false
        try {
            var refreshed = false
            var url = resolveAllowed(videoId) ?: return WebJson.error(502, "no_stream", "No stream is available")
            while (true) {
                val attempt = tryOpen(url, rangeHeader)
                when (attempt) {
                    is Attempt.Done -> {
                        handedOver = true
                        return attempt.response
                    }
                    is Attempt.Refused -> {
                        onRefused(url, attempt.status)
                        if (refreshed) return WebJson.error(502, "upstream", "The stream was refused")
                        refreshed = true
                        url = resolveAllowed(videoId) ?: return WebJson.error(502, "no_stream", "No stream is available")
                    }
                    Attempt.Failed -> return WebJson.error(502, "upstream", "The stream could not be read")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WebJson.error(502, "upstream", "The stream could not be read")
        } finally {
            if (!handedOver) streamSlots.release()
        }
    }

    private sealed class Attempt {
        class Done(val response: WebResponse) : Attempt()
        class Refused(val status: Int) : Attempt()
        object Failed : Attempt()
    }

    /** Resolves through the existing resolver and refuses a URL that is not on the stream allowlist. */
    private suspend fun resolveAllowed(videoId: String): String? {
        val url = runCatching { resolve(videoId) }.getOrElse { if (it is CancellationException) throw it else null }
            ?: return null
        return url.takeIf { WebHosts.isAllowedStream(it) }
    }

    private suspend fun tryOpen(url: String, rangeHeader: String?): Attempt = withContext(Dispatchers.IO) {
        val total = WebStreamPlan.contentLengthOf(url) ?: probeTotal(url)
            ?: return@withContext Attempt.Failed
        val decision = when (val d = WebStreamPlan.decide(rangeHeader, total)) {
            is WebStreamDecision.Unsatisfiable -> {
                val response = WebResponse.Bytes(416, "text/plain; charset=utf-8", ByteArray(0), WebStreamPlan.unsatisfiedHeaders(d.total))
                return@withContext Attempt.Done(response)
            }
            is WebStreamDecision.Send -> d
        }
        val window = WebStreamPlan.windowHeader(decision.start, decision.endInclusive)
            ?: return@withContext Attempt.Failed
        val reply = send(url, window, WebHosts::isAllowedStream) ?: return@withContext Attempt.Failed
        val status = reply.statusCode()
        if (WebStreamPlan.shouldRefresh(status)) {
            closeQuietly(reply.body())
            return@withContext Attempt.Refused(status)
        }
        // A server that ignored the range would send bytes from 0: only usable when that is the start.
        val usable = status == 206 || (status == 200 && decision.start == 0L)
        if (!usable) {
            closeQuietly(reply.body())
            return@withContext Attempt.Failed
        }
        val type = WebStreamPlan.contentType(url, reply.headers().firstValue("content-type").orElse(null))
        val opened = Opened(url, decision, type, reply.body())
        Attempt.Done(response(opened))
    }

    /** The whole file's size from a one-byte probe, when the URL carries no `clen`. */
    private fun probeTotal(url: String): Long? {
        val reply = send(url, "bytes=0-0", WebHosts::isAllowedStream) ?: return null
        closeQuietly(reply.body())
        val stated = reply.headers().firstValue("content-range").orElse(null) ?: return null
        return stated.substringAfterLast('/').trim().toLongOrNull()?.takeIf { it > 0 }
    }

    private fun response(opened: Opened): WebResponse.Stream {
        val decision = opened.decision
        return WebResponse.Stream(
            status = decision.status,
            headers = WebStreamPlan.headers(decision, opened.contentType),
            contentLength = decision.length,
            onFinish = {
                closeQuietly(opened.first)
                streamSlots.release()
            },
        ) { out -> pump(opened, out) }
    }

    /**
     * Copies the range window by window. The first window is already open. A window that ends early
     * continues from where it stopped; a failed later window ends the body, and the browser's own
     * retry (a fresh `Range` request) picks up from there.
     */
    private suspend fun pump(opened: Opened, out: OutputStream) {
        val end = opened.decision.endInclusive
        var position = opened.decision.start
        var input: InputStream? = opened.first
        while (position <= end) {
            val current = input ?: withContext(Dispatchers.IO) { openWindow(opened.url, position, end) } ?: return
            input = null
            val expected = minOf(WebStreamPlan.WINDOW_BYTES, end - position + 1)
            val copied = try {
                copy(current, out, expected)
            } finally {
                closeQuietly(current)
            }
            if (copied <= 0L) return
            position += copied
        }
    }

    private fun openWindow(url: String, position: Long, end: Long): InputStream? {
        val range = WebStreamPlan.windowHeader(position, end) ?: return null
        val reply = send(url, range, WebHosts::isAllowedStream) ?: return null
        val status = reply.statusCode()
        if (status != 206) {
            if (WebStreamPlan.shouldRefresh(status)) onRefused(url, status)
            closeQuietly(reply.body())
            return null
        }
        return reply.body()
    }

    /** Copies at most [limit] bytes through the bounded buffer; stops early when the coroutine is cancelled. */
    private suspend fun copy(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(WebStreamPlan.COPY_BUFFER)
        var total = 0L
        while (total < limit) {
            currentCoroutineContext().ensureActive()
            val want = minOf(buffer.size.toLong(), limit - total).toInt()
            val n = withContext(Dispatchers.IO) { input.read(buffer, 0, want) }
            if (n < 0) break
            if (n == 0) continue
            withContext(Dispatchers.IO) {
                out.write(buffer, 0, n)
                out.flush()
            }
            total += n
        }
        return total
    }

    // ---- Artwork -------------------------------------------------------------------------------

    suspend fun art(rawUrl: String?): WebResponse {
        if (!WebHosts.isAllowedArt(rawUrl)) return WebJson.error(400, "bad_url", "That image is not available")
        if (!artSlots.tryAcquire()) return WebJson.error(429, "too_many_requests", "Too many image requests at once")
        try {
            return withContext(Dispatchers.IO) { fetchArt(rawUrl!!) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WebJson.error(502, "upstream", "The image could not be read")
        } finally {
            artSlots.release()
        }
    }

    private fun fetchArt(url: String): WebResponse {
        val reply = send(url, null, WebHosts::isAllowedArt)
            ?: return WebJson.error(502, "upstream", "The image could not be read")
        val body = reply.body()
        try {
            if (reply.statusCode() != 200) return WebJson.error(502, "upstream", "The image could not be read")
            val type = reply.headers().firstValue("content-type").orElse("").substringBefore(';').trim().lowercase()
            if (type !in IMAGE_TYPES) return WebJson.error(502, "upstream", "The image could not be read")
            val declared = reply.headers().firstValue("content-length").orElse(null)
            if (WebBodies.declaredTooLarge(declared, WebStreamPlan.MAX_ART_BYTES)) {
                return WebJson.error(502, "too_large", "The image is too large")
            }
            val bytes = WebBodies.readLimited(body, WebStreamPlan.MAX_ART_BYTES)
                ?: return WebJson.error(502, "too_large", "The image is too large")
            return WebResponse.Bytes(
                200,
                type,
                bytes,
                mapOf("Cache-Control" to "private, max-age=86400", "X-Content-Type-Options" to "nosniff"),
            )
        } finally {
            closeQuietly(body)
        }
    }

    // ---- Shared HTTP ---------------------------------------------------------------------------

    /**
     * One GET, following at most [MAX_HOPS] redirects, each one re-checked with [allowed]. The
     * body is left open for the caller. Returns null for a refused redirect or a failed request.
     * [range] is the whole `Range` header value, or null.
     */
    private fun send(url: String, range: String?, allowed: (String?) -> Boolean): HttpResponse<InputStream>? {
        var current = url
        var hops = 0
        while (true) {
            if (!allowed(current)) return null
            val builder = HttpRequest.newBuilder(URI.create(current))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            if (range != null) builder.header("Range", range)
            // The media headers (what the minting client sends) go only to stream hosts, never to art.
            if (WebHosts.isAllowedStream(current)) {
                headersFor(current).forEach { (name, value) -> runCatching { builder.header(name, value) } }
            }
            val reply = runCatching { client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream()) }
                .getOrNull() ?: return null
            val status = reply.statusCode()
            if (status !in REDIRECTS) return reply
            val location = reply.headers().firstValue("location").orElse(null)
            closeQuietly(reply.body())
            if (location == null || hops >= MAX_HOPS) return null
            hops += 1
            current = runCatching { URI.create(current).resolve(location).toString() }.getOrNull() ?: return null
        }
    }

    private fun closeQuietly(stream: InputStream?) {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val MAX_HOPS = 3
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")

        val defaultClient: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}
