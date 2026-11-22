package com.music.bitchord.desktop

import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WEB1: the pure HTTP helpers (decoding, query, body cap, JSON envelope, SSE text, Range). */
class DesktopWebHttpTest {

    @Test
    fun `percent decoding handles utf8 plus and broken escapes`() {
        assertEquals("a b", WebPaths.percentDecode("a%20b", plusAsSpace = false))
        assertEquals("a+b", WebPaths.percentDecode("a+b", plusAsSpace = false))
        assertEquals("a b", WebPaths.percentDecode("a+b", plusAsSpace = true))
        assertEquals("\u20ac", WebPaths.percentDecode("%E2%82%AC", plusAsSpace = false))
        assertEquals("abc", WebPaths.percentDecode("abc", plusAsSpace = false))
        assertNull(WebPaths.percentDecode("%zz", plusAsSpace = false))
        assertNull(WebPaths.percentDecode("%2", plusAsSpace = false))
        assertNull(WebPaths.percentDecode("100%", plusAsSpace = false))
    }

    @Test
    fun `query parsing keeps the first value skips bad pairs and caps the count`() {
        val query = WebPaths.parseQuery("q=hello%20world&x=1&q=second&y=a+b")
        assertEquals("hello world", query["q"])
        assertEquals("1", query["x"])
        assertEquals("a b", query["y"])
        assertEquals(mapOf("a" to "", "b" to ""), WebPaths.parseQuery("a&b="))
        assertEquals(mapOf("ok" to "1"), WebPaths.parseQuery("bad=%zz&ok=1&=x"))
        assertTrue(WebPaths.parseQuery(null).isEmpty())
        assertTrue(WebPaths.parseQuery("").isEmpty())
        val many = (1..60).joinToString("&") { "k$it=v" }
        assertEquals(WebLimits.MAX_QUERY_PARAMS, WebPaths.parseQuery(many).size)
    }

    @Test
    fun `path segments reject empty segments and relative paths`() {
        assertEquals(emptyList<String>(), WebPaths.segments("/"))
        assertEquals(listOf("a", "b"), WebPaths.segments("/a/b"))
        assertNull(WebPaths.segments("/a/"))
        assertNull(WebPaths.segments("//"))
        assertNull(WebPaths.segments("a"))
        assertNull(WebPaths.segments(""))
    }

    @Test
    fun `body cap refuses more than the limit and never keeps the excess`() {
        val ten = ByteArray(10) { 1 }
        assertEquals(10, WebBodies.readLimited(ByteArrayInputStream(ten), 10)?.size)
        assertNull(WebBodies.readLimited(ByteArrayInputStream(ten), 9))
        assertEquals(0, WebBodies.readLimited(ByteArrayInputStream(ByteArray(0)), 10)?.size)
        val big = ByteArray(100_000) { 2 }
        assertEquals(100_000, WebBodies.readLimited(ByteArrayInputStream(big), 100_000)?.size)
        assertNull(WebBodies.readLimited(ByteArrayInputStream(big), 99_999))
    }

    @Test
    fun `declared content length over the cap is caught before reading`() {
        assertTrue(WebBodies.declaredTooLarge("11", 10))
        assertTrue(!WebBodies.declaredTooLarge("10", 10))
        assertTrue(!WebBodies.declaredTooLarge(null, 10))
        assertTrue(!WebBodies.declaredTooLarge("abc", 10))
    }

    @Test
    fun `error envelope has error and code and the right status`() {
        val response = WebJson.error(404, "not_found", "Not found")
        assertEquals(404, response.status)
        assertTrue(response.contentType.startsWith("application/json"))
        assertEquals("no-store", response.headers["Cache-Control"])
        val body = assertNotNull(WebJson.parseObject(response.body))
        assertEquals("Not found", body["error"]?.jsonPrimitive?.content)
        assertEquals("not_found", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseObject accepts only a json object`() {
        assertNull(WebJson.parseObject(ByteArray(0)))
        assertNull(WebJson.parseObject("[1]".toByteArray()))
        assertNull(WebJson.parseObject("{".toByteArray()))
        val body = assertNotNull(WebJson.parseObject("""{"liked":true,"extra":1}""".toByteArray()))
        assertEquals("true", body["liked"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sse events put every line in its own data field`() {
        assertEquals("data: hello\n\n", WebSse.event("hello"))
        assertEquals("data: a\ndata: b\n\n", WebSse.event("a\nb"))
        assertEquals("data: a\ndata: b\n\n", WebSse.event("a\r\nb"))
        assertEquals("event: voice\ndata: x\n\n", WebSse.event("x", "voice"))
        assertEquals("data: x\n\n", WebSse.event("x", "bad\nname"))
        assertEquals(": keep-alive\n\n", WebSse.comment())
        assertEquals(": a b\n\n", WebSse.comment("a\nb"))
        assertEquals(20_000L, WebSse.KEEP_ALIVE_MILLIS)
    }

    private fun partial(header: String?, size: Long = 1000): WebRangeResult.Partial =
        WebRange.parse(header, size) as WebRangeResult.Partial

    @Test
    fun `range parsing gives start end and clamps to the size`() {
        val first = partial("bytes=0-99")
        assertEquals(0L, first.start)
        assertEquals(99L, first.endInclusive)
        assertEquals(100L, first.length)
        assertEquals(500L, partial("bytes=500-").start)
        assertEquals(999L, partial("bytes=500-").endInclusive)
        assertEquals(900L, partial("bytes=-100").start)
        assertEquals(999L, partial("bytes=-100").endInclusive)
        assertEquals(0L, partial("bytes=-2000").start)
        assertEquals(999L, partial("bytes=900-5000").endInclusive)
        assertEquals(0L, partial("BYTES=0-0").endInclusive)
        assertEquals(9L, partial(" bytes=0-9 ").endInclusive)
    }

    @Test
    fun `range parsing answers unsatisfiable past the end`() {
        assertTrue(WebRange.parse("bytes=1000-", 1000) is WebRangeResult.Unsatisfiable)
        assertTrue(WebRange.parse("bytes=1000-1005", 1000) is WebRangeResult.Unsatisfiable)
        assertTrue(WebRange.parse("bytes=-0", 1000) is WebRangeResult.Unsatisfiable)
        assertTrue(WebRange.parse("bytes=0-", 0) is WebRangeResult.Unsatisfiable)
    }

    @Test
    fun `range parsing ignores headers it cannot trust`() {
        assertTrue(WebRange.parse(null, 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=5-2", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("items=0-5", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=0-5,10-20", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=abc-", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=-", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=", 1000) is WebRangeResult.Full)
        assertTrue(WebRange.parse("bytes=0-5", -1) is WebRangeResult.Full)
    }

    @Test
    fun `content range text`() {
        assertEquals("bytes 0-99/1000", WebRange.contentRange(0, 99, 1000))
        assertEquals("bytes */1000", WebRange.unsatisfiedRange(1000))
    }
}
