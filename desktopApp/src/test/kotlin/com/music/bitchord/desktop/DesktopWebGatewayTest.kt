package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WEB1 acceptance, without a socket: the gateway is driven with plain [WebIncoming] values.
 * "GET / serves a page, /api/ping answers only with the cookie, a foreign Host or no cookie is 403."
 */
class DesktopWebGatewayTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val cookie = "muses_web=$token"

    private class FakeHost(val signed: Boolean = true) : DesktopWebHost {
        override val version: String = "9.9"
        override fun signedIn(): Boolean = signed
    }

    private fun gateway(
        lan: Boolean = false,
        loader: (String) -> ByteArray? = { null },
        configure: (DesktopWebRouter) -> Unit = {},
    ): DesktopWebGateway {
        val router = DesktopWebRouter()
        DesktopWebBasics(FakeHost(), DesktopWebEventHub()).install(router)
        configure(router)
        val gateway = DesktopWebGateway(
            router,
            token,
            { "123456" },
            { listOf("192.168.1.5") },
            loader,
            { 0L },
            { 0L },
            { },
        )
        gateway.lanEnabled = lan
        return gateway
    }

    private fun incoming(
        method: String = "GET",
        path: String = "/",
        query: String? = null,
        host: String? = "localhost:8765",
        loopback: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): WebIncoming {
        val all = HashMap<String, String>()
        if (host != null) all["host"] = host
        all.putAll(headers)
        return WebIncoming(method, path, query, all, loopback) { max -> if (body.size > max) null else body }
    }

    private val api = mapOf("cookie" to cookie, "x-muses-web" to "1")

    private fun status(response: WebResponse): Int = when (response) {
        is WebResponse.Bytes -> response.status
        is WebResponse.Stream -> response.status
    }

    private fun bytes(response: WebResponse): WebResponse.Bytes = response as WebResponse.Bytes

    private fun code(response: WebResponse): String? =
        WebJson.parseObject(bytes(response).body)?.get("code")?.jsonPrimitive?.content

    @Test
    fun `the page loads on this computer and sets the session cookie`() {
        runBlocking {
            val r = bytes(gateway().handle(incoming()))
            assertEquals(200, r.status)
            val set = assertNotNull(r.headers["Set-Cookie"])
            assertTrue(set.startsWith("muses_web=$token"))
            assertTrue("HttpOnly" in set && "SameSite=Strict" in set)
            assertTrue("Muses Web is running" in String(r.body))
        }
    }

    @Test
    fun `ping answers only with the cookie and the header`() {
        runBlocking {
            val g = gateway()
            assertEquals(403, status(g.handle(incoming(path = "/api/ping", headers = mapOf("x-muses-web" to "1")))))
            assertEquals(403, status(g.handle(incoming(path = "/api/ping", headers = mapOf("cookie" to cookie)))))
            val ok = bytes(g.handle(incoming(path = "/api/ping", headers = api)))
            assertEquals(200, ok.status)
            val body = assertNotNull(WebJson.parseObject(ok.body))
            assertEquals("9.9", body["version"]?.jsonPrimitive?.content)
            assertEquals("true", body["signedIn"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a forbidden answer is the error envelope without details`() {
        runBlocking {
            val r = gateway().handle(incoming(path = "/api/ping"))
            assertEquals(403, status(r))
            assertEquals("forbidden", code(r))
            assertFalse(token in String(bytes(r).body))
        }
    }

    @Test
    fun `a foreign host is refused for pages and api calls`() {
        runBlocking {
            val g = gateway()
            assertEquals(403, status(g.handle(incoming(host = "evil.example:8765"))))
            assertEquals(403, status(g.handle(incoming(host = null))))
            assertEquals(403, status(g.handle(incoming(path = "/api/ping", host = "evil.example", headers = api))))
        }
    }

    @Test
    fun `cross site and wrong origin calls are refused`() {
        runBlocking {
            val g = gateway()
            val path = "/api/ping"
            assertEquals(403, status(g.handle(incoming(path = path, headers = api + ("sec-fetch-site" to "cross-site")))))
            assertEquals(403, status(g.handle(incoming(path = path, headers = api + ("origin" to "http://localhost:9999")))))
            assertEquals(200, status(g.handle(incoming(path = path, headers = api + ("origin" to "http://localhost:8765")))))
        }
    }

    @Test
    fun `unknown api paths are 404 and wrong methods are 405 with an allow header`() {
        runBlocking {
            val g = gateway()
            val missing = g.handle(incoming(path = "/api/nope", headers = api))
            assertEquals(404, status(missing))
            assertEquals("not_found", code(missing))
            val wrong = bytes(g.handle(incoming(method = "POST", path = "/api/ping", headers = api)))
            assertEquals(405, wrong.status)
            assertEquals("GET", wrong.headers["Allow"])
            assertEquals("method_not_allowed", code(wrong))
        }
    }

    @Test
    fun `a handler that throws gives a plain 500 that leaks nothing`() {
        runBlocking {
            val g = gateway { it.get("/api/boom") { throw IllegalStateException("secret search text") } }
            val r = g.handle(incoming(path = "/api/boom", headers = api))
            assertEquals(500, status(r))
            assertEquals("internal", code(r))
            assertFalse("secret" in String(bytes(r).body))
        }
    }

    @Test
    fun `post bodies are capped by the declared length and by the real length`() {
        runBlocking {
            val g = gateway {
                it.post("/api/echo", 10) { request -> WebJson.ok(JsonPrimitive(request.body.size)) }
            }
            val post = api + ("origin" to "http://localhost:8765")
            val ten = g.handle(incoming(method = "POST", path = "/api/echo", headers = post, body = ByteArray(10)))
            assertEquals(200, status(ten))
            assertEquals("10", String(bytes(ten).body))
            val eleven = g.handle(incoming(method = "POST", path = "/api/echo", headers = post, body = ByteArray(11)))
            assertEquals(413, status(eleven))
            assertEquals("too_large", code(eleven))
            val declared = g.handle(
                incoming(method = "POST", path = "/api/echo", headers = post + ("content-length" to "11"), body = ByteArray(1)),
            )
            assertEquals(413, status(declared))
        }
    }

    @Test
    fun `an overlong query is refused`() {
        runBlocking {
            val r = gateway().handle(incoming(path = "/api/ping", query = "q=" + "a".repeat(WebLimits.QUERY_CHARS), headers = api))
            assertEquals(414, status(r))
        }
    }

    @Test
    fun `at most four event streams are open and a finished one frees its slot`() {
        runBlocking {
            val g = gateway()
            val open = (1..4).map { g.handle(incoming(path = "/api/events", headers = mapOf("cookie" to cookie))) }
            open.forEach { assertTrue(it is WebResponse.Stream) }
            val stream = open[0] as WebResponse.Stream
            assertEquals("text/event-stream; charset=utf-8", stream.headers["Content-Type"])
            assertTrue(stream.contentLength < 0)
            val fifth = g.handle(incoming(path = "/api/events", headers = mapOf("cookie" to cookie)))
            assertEquals(429, status(fifth))
            assertEquals("too_many_streams", code(fifth))
            stream.onFinish?.invoke()
            assertTrue(g.handle(incoming(path = "/api/events", headers = mapOf("cookie" to cookie))) is WebResponse.Stream)
        }
    }

    @Test
    fun `static files carry an etag and answer 304 on the second visit`() {
        runBlocking {
            val loader: (String) -> ByteArray? = { name -> if (name == "web/index.html") "<html>ok</html>".toByteArray() else null }
            val g = gateway(loader = loader)
            val first = bytes(g.handle(incoming()))
            assertEquals(200, first.status)
            val tag = assertNotNull(first.headers["ETag"])
            assertTrue(first.headers.containsKey("Set-Cookie"))
            val second = bytes(g.handle(incoming(headers = mapOf("cookie" to cookie, "if-none-match" to tag))))
            assertEquals(304, second.status)
            assertFalse(second.headers.containsKey("Set-Cookie"))
        }
    }

    @Test
    fun `a device on the network is refused while lan access is off`() {
        runBlocking {
            val g = gateway(lan = false)
            assertEquals(403, status(g.handle(incoming(host = "192.168.1.5:8765", loopback = false))))
            val pair = incoming(method = "POST", path = "/pair", body = "code=123456".toByteArray())
            assertEquals(403, status(g.handle(pair)))
        }
    }

    @Test
    fun `a device on the network pairs once with the code then uses the api`() {
        runBlocking {
            val g = gateway(lan = true)
            val host = "192.168.1.5:8765"
            val page = bytes(g.handle(incoming(host = host, loopback = false)))
            assertEquals(401, page.status)
            assertTrue("Pair with Muses" in String(page.body))
            val wrong = g.handle(incoming("POST", "/pair", null, host, false, emptyMap(), "code=000000".toByteArray()))
            assertEquals(401, status(wrong))
            val right = bytes(g.handle(incoming("POST", "/pair", null, host, false, emptyMap(), "code=123456".toByteArray())))
            assertEquals(303, right.status)
            assertEquals("/", right.headers["Location"])
            assertTrue(assertNotNull(right.headers["Set-Cookie"]).startsWith("muses_web=$token"))
            val ping = g.handle(incoming(path = "/api/ping", host = host, loopback = false, headers = api))
            assertEquals(200, status(ping))
        }
    }

    @Test
    fun `wrong pairing codes lock pairing after five tries`() {
        runBlocking {
            val g = gateway(lan = true)
            val host = "192.168.1.5:8765"
            val results = (1..5).map {
                status(g.handle(incoming("POST", "/pair", null, host, false, emptyMap(), "code=000000".toByteArray())))
            }
            assertEquals(listOf(401, 401, 401, 401, 429), results)
            val locked = g.handle(incoming("POST", "/pair", null, host, false, emptyMap(), "code=123456".toByteArray()))
            assertEquals(429, status(locked))
        }
    }

    @Test
    fun `the pairing form is not read past its cap`() {
        runBlocking {
            val g = gateway(lan = true)
            val big = ByteArray(2000) { 'a'.code.toByte() }
            val r = g.handle(incoming("POST", "/pair", null, "192.168.1.5:8765", false, emptyMap(), big))
            assertEquals(413, status(r))
        }
    }
}
