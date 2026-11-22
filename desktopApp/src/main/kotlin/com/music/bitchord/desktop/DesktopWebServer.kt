package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * WEB1: the embedded web server in two layers.
 *
 *  [DesktopWebGateway]  everything that decides an answer (Host / cookie / pairing checks, static
 *                       files, routing, body caps, error envelope). It never sees a socket, so the
 *                       tests drive it with plain [WebIncoming] values.
 *  [DesktopWebServer]   the JDK's com.sun.net.httpserver.HttpServer (module jdk.httpserver): binds
 *                       127.0.0.1 (0.0.0.0 only with LAN on), turns an HttpExchange into a
 *                       [WebIncoming], runs the gateway on a coroutine scope that is cancelled on
 *                       stop, and writes the [WebResponse] back.
 *
 * Nothing is logged that could hold a token, a cookie, a stream URL or search text: only fixed
 * reason words and exception class names.
 */

/** A request as the gateway sees it. [headers] has lower-case names. */
internal class WebIncoming(
    val method: String,
    val rawPath: String,
    val rawQuery: String?,
    val headers: Map<String, String>,
    val remoteIsLoopback: Boolean,
    /** Reads the body, at most `max` bytes; null when the body is larger. Called only for POST. */
    val readBody: (max: Int) -> ByteArray?,
)

internal class DesktopWebGateway(
    private val router: DesktopWebRouter,
    private val token: String,
    private val pairingCode: () -> String,
    private val lanAddresses: () -> List<String>,
    private val staticLoader: (String) -> ByteArray? = DesktopWebStatic::loadFromClasspath,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    var lanEnabled: Boolean = false

    private val guard = DesktopWebPairingGuard()
    private val localHosts: Set<String> = DesktopWebAuth.allowedHosts(false, emptyList())
    private val denied = AtomicInteger()

    @Volatile
    private var lanHosts: Set<String> = emptySet()

    @Volatile
    private var lanHostsAt: Long = Long.MIN_VALUE

    fun resetPairing() = guard.reset()

    /** The checks use the LAN addresses as they are now, re-read at most every 30 seconds. */
    private fun policy(): WebAuthPolicy {
        if (!lanEnabled) return WebAuthPolicy(token, false, localHosts)
        val now = nanoClock()
        if (lanHostsAt == Long.MIN_VALUE || now - lanHostsAt > LAN_REFRESH_NANOS) {
            lanHosts = DesktopWebAuth.allowedHosts(true, lanAddresses())
            lanHostsAt = now
        }
        return WebAuthPolicy(token, true, lanHosts)
    }

    suspend fun handle(incoming: WebIncoming): WebResponse {
        val method = incoming.method.uppercase()
        val path = incoming.rawPath
        if (incoming.rawQuery != null && incoming.rawQuery.length > WebLimits.QUERY_CHARS) {
            return WebJson.error(414, "uri_too_long", "Request is too long")
        }
        val headers = incoming.headers
        val auth = WebAuthRequest(
            method = method,
            path = path,
            hostHeader = headers["host"],
            remoteIsLoopback = incoming.remoteIsLoopback,
            cookieHeader = headers["cookie"],
            secFetchSite = headers["sec-fetch-site"],
            origin = headers["origin"],
            musesHeader = headers[DesktopWebAuth.HEADER_NAME.lowercase()],
        )
        val policy = policy()
        if (path == PAIR_PATH) return pair(incoming, auth, policy)
        val verdict = DesktopWebAuth.check(auth, policy)
        return when (verdict.decision) {
            WebAuthDecision.FORBIDDEN -> forbidden(verdict.reason)
            WebAuthDecision.NEEDS_PAIRING -> pairingPage(401, null)
            WebAuthDecision.ALLOW, WebAuthDecision.ALLOW_SET_COOKIE -> {
                if (DesktopWebAuth.isApiPath(path)) {
                    api(incoming, method)
                } else {
                    val file = DesktopWebStatic.serve(path, headers["if-none-match"], staticLoader)
                    if (verdict.decision == WebAuthDecision.ALLOW_SET_COOKIE) {
                        WebResponse.Bytes(
                            file.status,
                            file.contentType,
                            file.body,
                            file.headers + ("Set-Cookie" to DesktopWebAuth.setCookieValue(token)),
                        )
                    } else {
                        file
                    }
                }
            }
        }
    }

    private fun forbidden(reason: String): WebResponse {
        val count = denied.incrementAndGet()
        if (count <= 20 || count % 100 == 0) log("web: refused a request ($reason), $count so far")
        return WebJson.error(403, "forbidden", "Forbidden")
    }

    private suspend fun api(incoming: WebIncoming, method: String): WebResponse {
        val match = router.match(method, incoming.rawPath)
        if (match is WebMatch.MethodNotAllowed) {
            val allow = match.allowed.joinToString(", ")
            val error = WebJson.error(405, "method_not_allowed", "Method not allowed")
            return WebResponse.Bytes(error.status, error.contentType, error.body, error.headers + ("Allow" to allow))
        }
        val found = match as? WebMatch.Found ?: return WebJson.error(404, "not_found", "Not found")
        val route = found.route
        var body = ByteArray(0)
        if (method == "POST") {
            if (WebBodies.declaredTooLarge(incoming.headers["content-length"], route.maxBody)) {
                return WebJson.error(413, "too_large", "Request body is too large")
            }
            body = incoming.readBody(route.maxBody)
                ?: return WebJson.error(413, "too_large", "Request body is too large")
        }
        val request = WebRequest(
            method = method,
            path = incoming.rawPath,
            query = WebPaths.parseQuery(incoming.rawQuery),
            headers = incoming.headers,
            params = found.params,
            body = body,
        )
        return try {
            route.handler(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log("web: a handler failed (${failure::class.java.simpleName})")
            WebJson.error(500, "internal", "Internal error")
        }
    }

    /** `POST /pair`: the LAN pairing form. Wrong codes are counted and lock pairing for a while. */
    private fun pair(incoming: WebIncoming, auth: WebAuthRequest, policy: WebAuthPolicy): WebResponse {
        val verdict = DesktopWebAuth.checkPairing(auth, policy)
        if (verdict.decision != WebAuthDecision.ALLOW) return forbidden(verdict.reason)
        if (WebBodies.declaredTooLarge(incoming.headers["content-length"], PAIR_BODY)) {
            return WebJson.error(413, "too_large", "Request body is too large")
        }
        val body = incoming.readBody(PAIR_BODY) ?: return WebJson.error(413, "too_large", "Request body is too large")
        val form = WebPaths.parseQuery(String(body, StandardCharsets.UTF_8))
        return when (guard.attempt(form["code"], pairingCode(), clock())) {
            DesktopWebPairingGuard.Outcome.OK -> WebResponse.Bytes(
                303,
                "text/plain; charset=utf-8",
                ByteArray(0),
                mapOf(
                    "Location" to "/",
                    "Set-Cookie" to DesktopWebAuth.setCookieValue(token),
                    "Cache-Control" to "no-store",
                ),
            )
            DesktopWebPairingGuard.Outcome.WRONG -> pairingPage(401, "That code is not right.")
            DesktopWebPairingGuard.Outcome.LOCKED -> pairingPage(429, "Too many wrong codes. Wait a while and try again.")
        }
    }

    private fun pairingPage(status: Int, message: String?): WebResponse.Bytes = WebResponse.Bytes(
        status,
        "text/html; charset=utf-8",
        DesktopWebPages.pairing(message).toByteArray(StandardCharsets.UTF_8),
        mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to DesktopWebStatic.CSP),
    )

    companion object {
        const val PAIR_PATH = "/pair"
        const val PAIR_BODY = 1024
        private const val LAN_REFRESH_NANOS = 30_000_000_000L
    }
}

/** The JDK HttpServer around a [DesktopWebGateway]. Start and stop are safe to call repeatedly. */
internal class DesktopWebServer(
    private val gateway: DesktopWebGateway,
    private val log: (String) -> Unit = {},
) {
    private var http: HttpServer? = null
    private var workers: ThreadPoolExecutor? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = http != null

    /**
     * Starts listening on 127.0.0.1 (every IPv4 interface when [WebServerConfig.lan]); if the port is
     * taken, tries the next [PORT_TRIES]. Returns the port in use, or the failure.
     */
    @Synchronized
    fun start(config: WebServerConfig): Result<Int> {
        stop()
        val address = InetAddress.getByName(if (config.lan) "0.0.0.0" else "127.0.0.1")
        var created: HttpServer? = null
        var port = config.port
        var failure: IOException? = null
        for (offset in 0..PORT_TRIES) {
            val candidate = config.port + offset
            if (candidate > 65_535) break
            try {
                created = HttpServer.create(InetSocketAddress(address, candidate), 0)
                port = candidate
                break
            } catch (busy: IOException) {
                failure = busy
            }
        }
        val server = created ?: return Result.failure(failure ?: IOException("no free port"))
        val counter = AtomicInteger()
        val pool = ThreadPoolExecutor(
            2,
            4,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(64),
            ThreadFactory { task -> Thread(task, "Muses-Web-${counter.incrementAndGet()}").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("Muses-Web"))
        gateway.lanEnabled = config.lan
        gateway.resetPairing()
        server.executor = pool
        server.createContext("/") { exchange -> dispatch(exchange) }
        server.start()
        http = server
        workers = pool
        scope = newScope
        boundPort = port
        log("web: listening on port $port (${if (config.lan) "network" else "this computer only"})")
        return Result.success(port)
    }

    /** Cancels every running handler, closes the listener (waiting up to a second) and the threads. */
    @Synchronized
    fun stop() {
        val server = http ?: return
        http = null
        boundPort = -1
        scope?.cancel()
        scope = null
        runCatching { server.stop(1) }
        workers?.shutdown()
        workers = null
        log("web: stopped")
    }

    /** The pool thread only hands the exchange to the scope, so long streams never hold the pool. */
    private fun dispatch(exchange: HttpExchange) {
        val running = scope
        if (running == null) {
            runCatching { exchange.close() }
            return
        }
        try {
            running.launch {
                try {
                    respond(exchange)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // A client that went away mid-answer is normal; say only what kind of failure.
                    log("web: a response ended early (${failure::class.java.simpleName})")
                } finally {
                    runCatching { exchange.close() }
                }
            }
        } catch (rejected: Throwable) {
            runCatching { exchange.close() }
        }
    }

    private suspend fun respond(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val headers = HashMap<String, String>()
        for ((name, values) in exchange.requestHeaders) {
            if (name == null || values.isEmpty()) continue
            val key = name.lowercase()
            headers[key] = values.joinToString(if (key == "cookie") "; " else ", ")
        }
        val incoming = WebIncoming(
            method = exchange.requestMethod,
            rawPath = uri.rawPath ?: "/",
            rawQuery = uri.rawQuery,
            headers = headers,
            remoteIsLoopback = exchange.remoteAddress?.address?.isLoopbackAddress == true,
            readBody = { max -> readBody(exchange.requestBody, max) },
        )
        write(exchange, gateway.handle(incoming), head = incoming.method.equals("HEAD", ignoreCase = true))
    }

    private fun readBody(input: InputStream, max: Int): ByteArray? = WebBodies.readLimited(input, max)

    private suspend fun write(exchange: HttpExchange, response: WebResponse, head: Boolean) {
        val out = exchange.responseHeaders
        out.set("X-Content-Type-Options", "nosniff")
        out.set("Referrer-Policy", "no-referrer")
        out.set("Cross-Origin-Resource-Policy", "same-origin")
        out.set("X-Frame-Options", "DENY")
        when (response) {
            is WebResponse.Bytes -> {
                for ((name, value) in response.headers) out.set(name, value)
                out.set("Content-Type", response.contentType)
                if (head || response.body.isEmpty()) {
                    exchange.sendResponseHeaders(response.status, -1L)
                } else {
                    exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                    exchange.responseBody.write(response.body)
                }
            }
            is WebResponse.Stream -> {
                try {
                    for ((name, value) in response.headers) out.set(name, value)
                    val length = when {
                        head || response.contentLength == 0L -> -1L
                        response.contentLength > 0L -> response.contentLength
                        else -> 0L
                    }
                    exchange.sendResponseHeaders(response.status, length)
                    if (!head && response.contentLength != 0L) response.writer(exchange.responseBody)
                } finally {
                    response.onFinish?.invoke()
                }
            }
        }
    }

    companion object {
        /** After the chosen port, this many more are tried. */
        const val PORT_TRIES = 20
    }
}

/** What to listen on. */
internal data class WebServerConfig(val port: Int, val lan: Boolean)
