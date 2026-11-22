package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WEB1: static file rules (path safety, types, caching, ETag, fallback page). No class loader needed. */
class DesktopWebStaticTest {

    @Test
    fun `safe paths resolve and unsafe ones do not`() {
        assertEquals("index.html", DesktopWebStatic.resolve("/"))
        assertEquals("app.js", DesktopWebStatic.resolve("/app.js"))
        assertEquals("js/api.js", DesktopWebStatic.resolve("/js/api.js"))
        assertNull(DesktopWebStatic.resolve("/../secret"))
        assertNull(DesktopWebStatic.resolve("/js/../x"))
        assertNull(DesktopWebStatic.resolve("/%2e%2e/x"))
        assertNull(DesktopWebStatic.resolve("/.env"))
        assertNull(DesktopWebStatic.resolve("/a//b"))
        assertNull(DesktopWebStatic.resolve("/dir/"))
        assertNull(DesktopWebStatic.resolve("/a b"))
        assertNull(DesktopWebStatic.resolve("/a\\b"))
        assertNull(DesktopWebStatic.resolve("/%00"))
        assertNull(DesktopWebStatic.resolve("/\u00fc.js"))
        assertNull(DesktopWebStatic.resolve("/x%zz"))
        assertNull(DesktopWebStatic.resolve("app.js"))
        assertNull(DesktopWebStatic.resolve("/" + "a".repeat(201)))
    }

    @Test
    fun `content types`() {
        assertEquals("text/javascript; charset=utf-8", DesktopWebStatic.contentType("app.js"))
        assertEquals("text/javascript; charset=utf-8", DesktopWebStatic.contentType("a.mjs"))
        assertEquals("text/css; charset=utf-8", DesktopWebStatic.contentType("app.css"))
        assertEquals("text/html; charset=utf-8", DesktopWebStatic.contentType("index.html"))
        assertEquals("application/manifest+json; charset=utf-8", DesktopWebStatic.contentType("manifest.webmanifest"))
        assertEquals("image/png", DesktopWebStatic.contentType("icon.png"))
        assertEquals("image/svg+xml", DesktopWebStatic.contentType("logo.svg"))
        assertEquals("application/octet-stream", DesktopWebStatic.contentType("x.unknown"))
        assertEquals("application/octet-stream", DesktopWebStatic.contentType("noext"))
    }

    @Test
    fun `only hashed assets are cached for good`() {
        val forever = "public, max-age=31536000, immutable"
        assertEquals(forever, DesktopWebStatic.cacheControl("app-3fa9c21b.js"))
        assertEquals(forever, DesktopWebStatic.cacheControl("chunk.3fa9c21b5d.css"))
        assertEquals("no-cache", DesktopWebStatic.cacheControl("app.js"))
        assertEquals("no-cache", DesktopWebStatic.cacheControl("sw.js"))
        assertEquals("no-cache", DesktopWebStatic.cacheControl("index.html"))
        assertEquals("no-cache", DesktopWebStatic.cacheControl("app-1234.js"))
    }

    @Test
    fun `etag follows the content`() {
        val a = DesktopWebStatic.etag("hello".toByteArray())
        assertEquals(a, DesktopWebStatic.etag("hello".toByteArray()))
        assertNotEquals(a, DesktopWebStatic.etag("hello!".toByteArray()))
        assertTrue(a.startsWith("\"") && a.endsWith("\""))
        assertEquals(18, a.length)
    }

    @Test
    fun `if none match recognises the etag`() {
        val tag = "\"abc\""
        assertFalse(DesktopWebStatic.etagMatches(null, tag))
        assertTrue(DesktopWebStatic.etagMatches("\"abc\"", tag))
        assertTrue(DesktopWebStatic.etagMatches("W/\"abc\"", tag))
        assertTrue(DesktopWebStatic.etagMatches("\"x\", \"abc\"", tag))
        assertTrue(DesktopWebStatic.etagMatches("*", tag))
        assertFalse(DesktopWebStatic.etagMatches("\"zzz\"", tag))
    }

    private val loader: (String) -> ByteArray? = { name -> if (name == "web/app.js") "console.log(1)".toByteArray() else null }

    @Test
    fun `a file is served with type etag and cache rule`() {
        val r = DesktopWebStatic.serve("/app.js", null, loader)
        assertEquals(200, r.status)
        assertEquals("text/javascript; charset=utf-8", r.contentType)
        assertEquals("no-cache", r.headers["Cache-Control"])
        assertEquals(DesktopWebStatic.etag(r.body), r.headers["ETag"])
        assertFalse("Content-Security-Policy" in r.headers)
    }

    @Test
    fun `an unchanged file answers 304 without a body`() {
        val first = DesktopWebStatic.serve("/app.js", null, loader)
        val again = DesktopWebStatic.serve("/app.js", first.headers["ETag"], loader)
        assertEquals(304, again.status)
        assertEquals(0, again.body.size)
    }

    @Test
    fun `missing and unsafe paths are 404`() {
        assertEquals(404, DesktopWebStatic.serve("/missing.js", null, loader).status)
        assertEquals(404, DesktopWebStatic.serve("/../x", null, loader).status)
        assertEquals(404, DesktopWebStatic.serve("/.env", null, loader).status)
    }

    @Test
    fun `an empty web folder gives the built in page for the index only`() {
        val root = DesktopWebStatic.serve("/", null, loader)
        assertEquals(200, root.status)
        val text = String(root.body)
        assertTrue("Muses Web is running" in text)
        assertEquals(DesktopWebStatic.CSP, root.headers["Content-Security-Policy"])
        assertEquals(200, DesktopWebStatic.serve("/index.html", null, loader).status)
        assertFalse("<script" in text)
        assertFalse("style=" in text)
    }

    @Test
    fun `the pairing page escapes its message and has no script`() {
        val page = DesktopWebPages.pairing("<b>&\"")
        assertTrue("&lt;b&gt;&amp;&quot;" in page)
        assertFalse("<b>" in page)
        assertFalse("<script" in page)
        assertTrue("action=\"/pair\"" in page)
        assertFalse("role=\"alert\"" in DesktopWebPages.pairing(null))
    }
}
