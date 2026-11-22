package com.music.bitchord.desktop

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * WEB1: who may talk to the embedded web server. Everything here is a pure function of its
 * arguments (the clock and the random source are passed in), so the whole security model is
 * unit-tested without a socket.
 *
 * Model:
 *  1. The Host header must be one we expect (localhost, 127.0.0.1, the LAN IP only when LAN is on).
 *     This is the DNS-rebinding defence.
 *  2. A peer that is not loopback is refused unless LAN access is enabled.
 *  3. The session token travels only in an HttpOnly, SameSite=Strict cookie. A loopback page load
 *     is given the cookie; a LAN page load must pair with the 6-digit code first.
 *  4. Every /api call needs the cookie, must be same-origin (Sec-Fetch-Site / Origin), and must carry
 *     the X-Muses-Web header, except media/SSE paths that the browser fetches itself
 *     (<audio>, <img>, EventSource) and so cannot add a header to; those rely on the cookie plus
 *     the same-origin checks.
 *  5. No CORS headers are ever sent.
 */

internal data class WebAuthPolicy(
    val token: String,
    val lanEnabled: Boolean,
    /** Lower-case host names without a port, e.g. "localhost", "127.0.0.1", "192.168.1.5". */
    val allowedHosts: Set<String>,
)

internal data class WebAuthRequest(
    val method: String,
    /** The raw request path, no query. */
    val path: String,
    val hostHeader: String?,
    val remoteIsLoopback: Boolean,
    val cookieHeader: String?,
    val secFetchSite: String?,
    val origin: String?,
    val musesHeader: String?,
)

internal enum class WebAuthDecision { ALLOW, ALLOW_SET_COOKIE, NEEDS_PAIRING, FORBIDDEN }

/** [reason] is a short fixed word for the log; it never holds a token, cookie or user text. */
internal data class WebAuthResult(val decision: WebAuthDecision, val reason: String)

internal object DesktopWebAuth {

    const val COOKIE_NAME = "muses_web"
    const val HEADER_NAME = "X-Muses-Web"
    const val HEADER_VALUE = "1"

    private val allow = WebAuthResult(WebAuthDecision.ALLOW, "ok")

    private fun deny(reason: String) = WebAuthResult(WebAuthDecision.FORBIDDEN, reason)

    /** 128 random bits as 32 lower-case hex digits. */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Six decimal digits, leading zeros kept. */
    fun newPairingCode(random: SecureRandom = SecureRandom()): String =
        "%06d".format(random.nextInt(1_000_000))

    /** Compares two strings in time that does not depend on where they differ. */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** `a=1; b=2` to a map (first value of a name wins). Values are not decoded. */
    fun parseCookies(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isNotEmpty() && name !in result) result[name] = value
        }
        return result
    }

    /** The `Set-Cookie` value that hands the page its session. Session cookie: gone with the browser. */
    fun setCookieValue(token: String): String =
        "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"

    /**
     * The host part of a Host header, lower-cased, port removed (`[::1]:8765` keeps its brackets).
     * Returns null for anything that is not a plausible host: empty, odd characters, bad port.
     */
    fun normalizeHost(hostHeader: String?): String? {
        val raw = hostHeader?.trim()?.lowercase() ?: return null
        if (raw.isEmpty() || raw.length > 255) return null
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end < 0) return null
            val rest = raw.substring(end + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1)
                if (port.isEmpty() || !port.all { it in '0'..'9' }) return null
            }
            val inside = raw.substring(1, end)
            if (inside.isEmpty() || inside.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.') }) return null
            return raw.substring(0, end + 1)
        }
        val colon = raw.lastIndexOf(':')
        val host: String
        if (colon >= 0) {
            val port = raw.substring(colon + 1)
            if (port.isEmpty() || !port.all { it in '0'..'9' }) return null
            host = raw.substring(0, colon)
        } else {
            host = raw
        }
        if (host.isEmpty()) return null
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return null
        return host
    }

    /** The hosts the server answers to. LAN addresses count only while LAN access is on. */
    fun allowedHosts(lanEnabled: Boolean, lanAddresses: List<String>): Set<String> {
        val hosts = linkedSetOf("localhost", "127.0.0.1")
        if (lanEnabled) {
            for (address in lanAddresses) {
                val normal = normalizeHost(address) ?: continue
                hosts.add(normal)
            }
        }
        return hosts
    }

    fun isApiPath(path: String): Boolean = path == "/api" || path.startsWith("/api/")

    /**
     * Paths the browser fetches by itself (<audio>, <img>, EventSource), which cannot carry the custom
     * header. They still need the cookie and the same-origin checks.
     */
    fun headerExempt(path: String): Boolean =
        path.startsWith("/api/stream/") || path == "/api/art" || path == "/api/events"

    /** The authority of an Origin header (`http://localhost:8765` gives `localhost:8765`); null if absent or `null`. */
    fun originAuthority(origin: String?): String? {
        val raw = origin?.trim()?.lowercase() ?: return null
        val rest = when {
            raw.startsWith("http://") -> raw.substring(7)
            raw.startsWith("https://") -> raw.substring(8)
            else -> return null
        }
        val authority = rest.substringBefore('/')
        return authority.takeIf { it.isNotEmpty() }
    }

    /**
     * An Origin must name exactly the authority (host and port) of the Host header. Cookies are not
     * isolated by port, so a page on another localhost port must not pass as us.
     */
    fun originMatchesHost(origin: String?, hostHeader: String?): Boolean {
        val authority = originAuthority(origin) ?: return false
        val host = hostHeader?.trim()?.lowercase() ?: return false
        return authority == host
    }

    private fun hostOk(request: WebAuthRequest, policy: WebAuthPolicy): Boolean {
        val host = normalizeHost(request.hostHeader) ?: return false
        return host in policy.allowedHosts
    }

    fun hasValidCookie(request: WebAuthRequest, policy: WebAuthPolicy): Boolean =
        constantTimeEquals(parseCookies(request.cookieHeader)[COOKIE_NAME], policy.token)

    /** The one decision for a normal request (page, asset or API call). */
    fun check(request: WebAuthRequest, policy: WebAuthPolicy): WebAuthResult {
        if (!hostOk(request, policy)) return deny("host")
        if (!request.remoteIsLoopback && !policy.lanEnabled) return deny("remote")
        val cookie = hasValidCookie(request, policy)
        if (!isApiPath(request.path)) {
            if (request.method != "GET" && request.method != "HEAD") return deny("method")
            if (cookie) return allow
            return if (request.remoteIsLoopback) {
                WebAuthResult(WebAuthDecision.ALLOW_SET_COOKIE, "loopback")
            } else {
                WebAuthResult(WebAuthDecision.NEEDS_PAIRING, "pairing")
            }
        }
        if (!cookie) return deny("no-session")
        val site = request.secFetchSite?.trim()?.lowercase()
        if (site != null && site != "same-origin") return deny("cross-site")
        if (request.origin != null && !originMatchesHost(request.origin, request.hostHeader)) return deny("origin")
        if (!headerExempt(request.path) && request.musesHeader?.trim() != HEADER_VALUE) return deny("header")
        return allow
    }

    /** The checks for `POST /pair` before the code itself is looked at. */
    fun checkPairing(request: WebAuthRequest, policy: WebAuthPolicy): WebAuthResult {
        if (!hostOk(request, policy)) return deny("host")
        if (!request.remoteIsLoopback && !policy.lanEnabled) return deny("remote")
        if (!policy.lanEnabled) return deny("pairing-off")
        if (request.method != "POST") return deny("method")
        val site = request.secFetchSite?.trim()?.lowercase()
        if (site != null && site != "same-origin") return deny("cross-site")
        if (request.origin != null && !originMatchesHost(request.origin, request.hostHeader)) return deny("origin")
        return allow
    }
}

/**
 * Slows down guessing of the 6-digit pairing code: [maxFailures] wrong codes in a row lock all
 * pairing for [baseLockMillis], doubling each time it locks (up to an hour). The clock is passed in.
 */
internal class DesktopWebPairingGuard(
    private val maxFailures: Int = 5,
    private val baseLockMillis: Long = 60_000L,
    private val maxLockMillis: Long = 3_600_000L,
) {
    enum class Outcome { OK, WRONG, LOCKED }

    private var failures = 0
    private var level = 0
    private var lockedUntil = Long.MIN_VALUE

    @Synchronized
    fun attempt(given: String?, expected: String, nowMillis: Long): Outcome {
        if (nowMillis < lockedUntil) return Outcome.LOCKED
        if (DesktopWebAuth.constantTimeEquals(given?.trim(), expected)) {
            failures = 0
            level = 0
            return Outcome.OK
        }
        failures += 1
        if (failures >= maxFailures) {
            failures = 0
            val factor = 1L shl level.coerceAtMost(20)
            lockedUntil = nowMillis + (baseLockMillis * factor).coerceAtMost(maxLockMillis)
            level += 1
            return Outcome.LOCKED
        }
        return Outcome.WRONG
    }

    @Synchronized
    fun reset() {
        failures = 0
        level = 0
        lockedUntil = Long.MIN_VALUE
    }
}
