package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

/*
 * WEB1: a tiny router with no dependencies. A route is a method, a path template with `{param}`
 * segments and a suspend handler. Matching is pure and tested. The router never reads a socket;
 * [DesktopWebServer] reads the body (capped by [WebRoute.maxBody]) and calls the handler.
 */

internal class WebRoute(
    val method: String,
    val template: String,
    val maxBody: Int,
    val handler: suspend (WebRequest) -> WebResponse,
) {
    /** The template split into segments; "{name}" segments capture. */
    val segments: List<String> = WebPaths.segments(template)
        ?: throw IllegalArgumentException("bad route template: $template")
}

internal sealed class WebMatch {
    class Found(val route: WebRoute, val params: Map<String, String>) : WebMatch()
    class MethodNotAllowed(val allowed: Set<String>) : WebMatch()
    object NotFound : WebMatch()
}

internal class DesktopWebRouter {

    private val routes = ArrayList<WebRoute>()

    fun add(
        method: String,
        template: String,
        maxBody: Int = WebLimits.JSON_BODY,
        handler: suspend (WebRequest) -> WebResponse,
    ): DesktopWebRouter {
        routes.add(WebRoute(method.uppercase(), template, maxBody, handler))
        return this
    }

    fun get(template: String, handler: suspend (WebRequest) -> WebResponse): DesktopWebRouter =
        add("GET", template, 0, handler)

    fun post(
        template: String,
        maxBody: Int = WebLimits.JSON_BODY,
        handler: suspend (WebRequest) -> WebResponse,
    ): DesktopWebRouter = add("POST", template, maxBody, handler)

    /**
     * Finds the route for [method] and the raw (still percent-encoded) [rawPath]. Parameters are
     * decoded; a parameter that is empty, too long, or decodes to something with a slash, a
     * backslash or a NUL does not match.
     */
    fun match(method: String, rawPath: String): WebMatch {
        val wanted = WebPaths.segments(rawPath) ?: return WebMatch.NotFound
        val allowed = LinkedHashSet<String>()
        for (route in routes) {
            val params = capture(route.segments, wanted) ?: continue
            if (route.method == method.uppercase()) return WebMatch.Found(route, params)
            allowed.add(route.method)
        }
        return if (allowed.isEmpty()) WebMatch.NotFound else WebMatch.MethodNotAllowed(allowed)
    }

    private fun capture(template: List<String>, actual: List<String>): Map<String, String>? {
        if (template.size != actual.size) return null
        var params: LinkedHashMap<String, String>? = null
        for (i in template.indices) {
            val want = template[i]
            val have = actual[i]
            if (want.length > 2 && want.startsWith("{") && want.endsWith("}")) {
                val decoded = WebPaths.percentDecode(have, plusAsSpace = false) ?: return null
                if (decoded.isEmpty() || decoded.length > MAX_PARAM_CHARS) return null
                if (decoded.any { it == '/' || it == '\\' || it == '\u0000' }) return null
                if (params == null) params = LinkedHashMap()
                params[want.substring(1, want.length - 1)] = decoded
            } else if (want != have) {
                return null
            }
        }
        return params ?: emptyMap()
    }

    private companion object {
        const val MAX_PARAM_CHARS = 200
    }
}

/** What the server needs to know about the running app, kept to two small questions. */
internal interface DesktopWebHost {
    val version: String
    fun signedIn(): Boolean
}

/** The real answers: the packaged version and the account already signed in inside this app. */
internal object DesktopWebHostImpl : DesktopWebHost {
    override val version: String get() = System.getProperty("bitchord.version") ?: "1.0.0"
    override fun signedIn(): Boolean = DesktopYouTubeAuth.isSignedIn
}

/**
 * Live events for the page: `{type: "signedIn" | "libraryChanged" | "voice", ...}`. A slow page
 * loses the oldest events rather than holding memory.
 */
internal class DesktopWebEventHub {
    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events

    fun publish(json: String) {
        _events.tryEmit(json)
    }

    fun publishSignedIn(signedIn: Boolean) = publish(signedInJson(signedIn))

    fun publishLibraryChanged() = publish(buildJsonObject { put("type", "libraryChanged") }.toString())

    companion object {
        fun signedInJson(signedIn: Boolean): String = buildJsonObject {
            put("type", "signedIn")
            put("signedIn", signedIn)
        }.toString()
    }
}

/** `/api/ping` and `/api/events`, the two endpoints that belong to the server itself. */
internal class DesktopWebBasics(
    private val host: DesktopWebHost,
    private val hub: DesktopWebEventHub,
) {
    private val eventSlots = Semaphore(WebLimits.MAX_EVENT_STREAMS)

    fun install(router: DesktopWebRouter) {
        router.get("/api/ping") { ping() }
        router.get("/api/events") { events() }
    }

    private fun ping(): WebResponse = WebJson.ok(
        buildJsonObject {
            put("version", host.version)
            put("signedIn", host.signedIn())
        },
    )

    private fun events(): WebResponse {
        if (!eventSlots.tryAcquire()) {
            return WebJson.error(429, "too_many_streams", "Too many open event streams")
        }
        val headers = mapOf(
            "Content-Type" to "text/event-stream; charset=utf-8",
            "Cache-Control" to "no-cache, no-store",
            "X-Accel-Buffering" to "no",
        )
        return WebResponse.Stream(
            status = 200,
            headers = headers,
            contentLength = -1L,
            onFinish = { eventSlots.release() },
        ) { out -> pump(out) }
    }

    /**
     * Writes events until the client goes away (a failed write ends it) or the server stops (the
     * coroutine is cancelled). A keep-alive comment goes out every [WebSse.KEEP_ALIVE_MILLIS].
     */
    private suspend fun pump(out: OutputStream) {
        fun send(text: String) {
            synchronized(out) {
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        }
        send(WebSse.RETRY_LINE)
        send(WebSse.event(DesktopWebEventHub.signedInJson(host.signedIn())))
        coroutineScope {
            val keepAlive = launch {
                while (isActive) {
                    delay(WebSse.KEEP_ALIVE_MILLIS)
                    send(WebSse.comment())
                }
            }
            try {
                hub.events.collect { json -> send(WebSse.event(json)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                keepAlive.cancel()
            }
        }
    }
}
