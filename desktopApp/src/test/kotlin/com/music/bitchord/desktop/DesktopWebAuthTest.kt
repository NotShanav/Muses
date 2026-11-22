package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WEB1: token, cookie, Host, Origin and pairing checks. Pure, no sockets. */
class DesktopWebAuthTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val local = WebAuthPolicy(token, false, DesktopWebAuth.allowedHosts(false, emptyList()))
    private val lan = WebAuthPolicy(token, true, DesktopWebAuth.allowedHosts(true, listOf("192.168.1.5")))

    private fun request(
        method: String = "GET",
        path: String = "/",
        host: String? = "localhost:8765",
        loopback: Boolean = true,
        cookie: String? = null,
        site: String? = null,
        origin: String? = null,
        header: String? = null,
    ) = WebAuthRequest(method, path, host, loopback, cookie, site, origin, header)

    private val session = "muses_web=$token"

    @Test
    fun `token is 128 bits of hex and the pairing code is six digits`() {
        assertTrue(Regex("[0-9a-f]{32}").matches(DesktopWebAuth.newToken()))
        repeat(200) { assertTrue(Regex("[0-9]{6}").matches(DesktopWebAuth.newPairingCode())) }
    }

    @Test
    fun `constant time equals is strict`() {
        assertTrue(DesktopWebAuth.constantTimeEquals("abc", "abc"))
        assertFalse(DesktopWebAuth.constantTimeEquals("abc", "abd"))
        assertFalse(DesktopWebAuth.constantTimeEquals("abc", "abcd"))
        assertFalse(DesktopWebAuth.constantTimeEquals(null, "abc"))
        assertFalse(DesktopWebAuth.constantTimeEquals("abc", null))
    }

    @Test
    fun `cookies are parsed with the first value winning`() {
        assertEquals(mapOf("a" to "1", "muses_web" to "tok", "b" to "2"), DesktopWebAuth.parseCookies("a=1; muses_web=tok; b=2"))
        assertEquals("x", DesktopWebAuth.parseCookies("muses_web=x; muses_web=y")["muses_web"])
        assertTrue(DesktopWebAuth.parseCookies(";;=;").isEmpty())
        assertTrue(DesktopWebAuth.parseCookies(null).isEmpty())
        assertTrue(DesktopWebAuth.parseCookies("  ").isEmpty())
    }

    @Test
    fun `the session cookie is http only and same site strict`() {
        val value = DesktopWebAuth.setCookieValue(token)
        assertTrue(value.startsWith("muses_web=$token"))
        assertTrue("HttpOnly" in value)
        assertTrue("SameSite=Strict" in value)
        assertTrue("Path=/" in value)
    }

    @Test
    fun `host header is normalised and odd values are rejected`() {
        assertEquals("localhost", DesktopWebAuth.normalizeHost("localhost:8765"))
        assertEquals("localhost", DesktopWebAuth.normalizeHost("LOCALHOST"))
        assertEquals("127.0.0.1", DesktopWebAuth.normalizeHost("127.0.0.1:1"))
        assertEquals("[::1]", DesktopWebAuth.normalizeHost("[::1]:8765"))
        assertEquals("[::1]", DesktopWebAuth.normalizeHost("[::1]"))
        assertEquals("evil.com", DesktopWebAuth.normalizeHost("evil.com"))
        assertNull(DesktopWebAuth.normalizeHost(null))
        assertNull(DesktopWebAuth.normalizeHost(""))
        assertNull(DesktopWebAuth.normalizeHost("local host"))
        assertNull(DesktopWebAuth.normalizeHost("localhost:"))
        assertNull(DesktopWebAuth.normalizeHost("localhost:abc"))
        assertNull(DesktopWebAuth.normalizeHost("a_b"))
        assertNull(DesktopWebAuth.normalizeHost("[::1"))
        assertNull(DesktopWebAuth.normalizeHost("[::1]x"))
        assertNull(DesktopWebAuth.normalizeHost("host:80:90"))
    }

    @Test
    fun `lan addresses are allowed only while lan is on`() {
        assertEquals(setOf("localhost", "127.0.0.1"), DesktopWebAuth.allowedHosts(false, listOf("192.168.1.5")))
        val on = DesktopWebAuth.allowedHosts(true, listOf("192.168.1.5", "bad host"))
        assertTrue("192.168.1.5" in on)
        assertFalse("bad host" in on)
    }

    @Test
    fun `origin must match the host header exactly`() {
        assertEquals("localhost:8765", DesktopWebAuth.originAuthority("http://localhost:8765"))
        assertEquals("x.com", DesktopWebAuth.originAuthority("https://x.com/path"))
        assertNull(DesktopWebAuth.originAuthority("null"))
        assertNull(DesktopWebAuth.originAuthority(null))
        assertNull(DesktopWebAuth.originAuthority("ftp://x"))
        assertTrue(DesktopWebAuth.originMatchesHost("http://localhost:8765", "localhost:8765"))
        assertFalse(DesktopWebAuth.originMatchesHost("http://localhost:9999", "localhost:8765"))
        assertFalse(DesktopWebAuth.originMatchesHost("http://localhost:8765", null))
        assertFalse(DesktopWebAuth.originMatchesHost(null, "localhost:8765"))
    }

    @Test
    fun `media and event paths are exempt from the custom header only`() {
        assertTrue(DesktopWebAuth.headerExempt("/api/stream/abc"))
        assertTrue(DesktopWebAuth.headerExempt("/api/art"))
        assertTrue(DesktopWebAuth.headerExempt("/api/events"))
        assertFalse(DesktopWebAuth.headerExempt("/api/ping"))
        assertFalse(DesktopWebAuth.headerExempt("/api/streamx"))
        assertFalse(DesktopWebAuth.headerExempt("/api/art/x"))
        assertTrue(DesktopWebAuth.isApiPath("/api"))
        assertTrue(DesktopWebAuth.isApiPath("/api/x"))
        assertFalse(DesktopWebAuth.isApiPath("/apix"))
        assertFalse(DesktopWebAuth.isApiPath("/"))
    }

    @Test
    fun `a loopback page load without a cookie gets one and a page load with it does not`() {
        assertEquals(WebAuthDecision.ALLOW_SET_COOKIE, DesktopWebAuth.check(request(), local).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(request(cookie = session), local).decision)
        assertEquals(WebAuthDecision.ALLOW_SET_COOKIE, DesktopWebAuth.check(request(cookie = "muses_web=wrong"), local).decision)
    }

    @Test
    fun `an api call needs the cookie and the header`() {
        val path = "/api/ping"
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(path = path, header = "1"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(path = path, cookie = "muses_web=wrong", header = "1"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(path = path, cookie = session), local).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(request(path = path, cookie = session, header = "1"), local).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(request(path = "/api/stream/x", cookie = session), local).decision)
    }

    @Test
    fun `a foreign host is refused everywhere`() {
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(host = "evil.example:8765"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(host = null), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(host = "192.168.1.5:8765"), local).decision)
        assertEquals(
            WebAuthDecision.FORBIDDEN,
            DesktopWebAuth.check(request(path = "/api/ping", host = "evil.example", cookie = session, header = "1"), lan).decision,
        )
    }

    @Test
    fun `cross site and wrong origin requests are refused`() {
        val base = request(path = "/api/ping", cookie = session, header = "1")
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(base.copy(secFetchSite = "same-origin"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(base.copy(secFetchSite = "cross-site"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(base.copy(secFetchSite = "same-site"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(base.copy(secFetchSite = "none"), local).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(base.copy(origin = "http://localhost:8765"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(base.copy(origin = "http://localhost:9999"), local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(base.copy(origin = "null"), local).decision)
    }

    @Test
    fun `a peer on the network is refused unless lan is on and then must pair`() {
        val remote = request(host = "192.168.1.5:8765", loopback = false)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(remote, local).decision)
        assertEquals(WebAuthDecision.NEEDS_PAIRING, DesktopWebAuth.check(remote, lan).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(remote.copy(cookieHeader = session), lan).decision)
        val api = remote.copy(path = "/api/ping", cookieHeader = session, musesHeader = "1")
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(api, lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(api.copy(cookieHeader = null), lan).decision)
    }

    @Test
    fun `pages accept only get and head`() {
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.check(request(method = "POST", cookie = session), local).decision)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.check(request(method = "HEAD", cookie = session), local).decision)
    }

    @Test
    fun `pairing is allowed only with lan on from a known host by post`() {
        val remote = request(method = "POST", path = "/pair", host = "192.168.1.5:8765", loopback = false)
        assertEquals(WebAuthDecision.ALLOW, DesktopWebAuth.checkPairing(remote, lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(remote, local).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(remote.copy(method = "GET"), lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(remote.copy(hostHeader = "evil.example"), lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(remote.copy(secFetchSite = "cross-site"), lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(remote.copy(origin = "http://evil.example"), lan).decision)
        assertEquals(WebAuthDecision.FORBIDDEN, DesktopWebAuth.checkPairing(request(method = "POST", path = "/pair"), local).decision)
    }

    @Test
    fun `pairing guard locks after repeated wrong codes and recovers`() {
        val guard = DesktopWebPairingGuard(maxFailures = 5, baseLockMillis = 60_000L, maxLockMillis = 3_600_000L)
        repeat(4) { assertEquals(DesktopWebPairingGuard.Outcome.WRONG, guard.attempt("000000", "123456", 0L)) }
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("000000", "123456", 0L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("123456", "123456", 59_999L))
        repeat(4) { assertEquals(DesktopWebPairingGuard.Outcome.WRONG, guard.attempt("000000", "123456", 60_000L)) }
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("000000", "123456", 60_000L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("123456", "123456", 179_999L))
        assertEquals(DesktopWebPairingGuard.Outcome.OK, guard.attempt(" 123456 ", "123456", 180_000L))
    }

    @Test
    fun `pairing lock time doubles and is capped`() {
        val guard = DesktopWebPairingGuard(maxFailures = 1, baseLockMillis = 1_000L, maxLockMillis = 2_500L)
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("x", "123456", 0L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("123456", "123456", 999L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("x", "123456", 1_000L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("123456", "123456", 2_999L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("x", "123456", 3_000L))
        assertEquals(DesktopWebPairingGuard.Outcome.LOCKED, guard.attempt("123456", "123456", 5_499L))
        assertEquals(DesktopWebPairingGuard.Outcome.OK, guard.attempt("123456", "123456", 5_500L))
    }
}
