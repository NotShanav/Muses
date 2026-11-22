package com.music.bitchord.desktop

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WEB1: route matching, parameters, 404 and 405, and the event JSON shapes. */
class DesktopWebRouterTest {

    private fun router(): DesktopWebRouter = DesktopWebRouter()
        .get("/api/ping") { WebJson.error(200, "x", "x") }
        .get("/api/track/{videoId}") { WebJson.error(200, "x", "x") }
        .post("/api/playlist/{id}/add") { WebJson.error(200, "x", "x") }
        .get("/") { WebJson.error(200, "x", "x") }

    private fun found(method: String, path: String): WebMatch.Found =
        router().match(method, path) as WebMatch.Found

    @Test
    fun `a parameter is captured and decoded`() {
        assertEquals("abc", found("GET", "/api/track/abc").params["videoId"])
        assertEquals("a b", found("GET", "/api/track/a%20b").params["videoId"])
        assertEquals("PL1", found("POST", "/api/playlist/PL1/add").params["id"])
        assertTrue(found("GET", "/api/ping").params.isEmpty())
        assertTrue(found("GET", "/").params.isEmpty())
    }

    @Test
    fun `bad paths do not match`() {
        val r = router()
        assertTrue(r.match("GET", "/api/track/") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/ping/") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track/a%2Fb") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track/a%5Cb") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track/%00") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track/%zz") is WebMatch.NotFound)
        assertTrue(r.match("GET", "/api/track/" + "a".repeat(201)) is WebMatch.NotFound)
        assertTrue(r.match("GET", "/nothing") is WebMatch.NotFound)
        assertTrue(r.match("GET", "api/ping") is WebMatch.NotFound)
    }

    @Test
    fun `a wrong method lists the allowed ones`() {
        val r = router()
        val wrong = r.match("POST", "/api/track/abc") as WebMatch.MethodNotAllowed
        assertEquals(setOf("GET"), wrong.allowed)
        val other = r.match("GET", "/api/playlist/PL1/add") as WebMatch.MethodNotAllowed
        assertEquals(setOf("POST"), other.allowed)
        assertTrue(r.match("get", "/api/ping") is WebMatch.Found)
    }

    @Test
    fun `the first registered route wins`() {
        val r = DesktopWebRouter()
            .get("/api/{x}") { WebJson.error(200, "first", "first") }
            .get("/api/ping") { WebJson.error(200, "second", "second") }
        assertEquals("ping", (r.match("GET", "/api/ping") as WebMatch.Found).params["x"])
    }

    @Test
    fun `post routes carry their body cap and get routes none`() {
        val r = DesktopWebRouter()
            .post("/api/voice", WebLimits.VOICE_BODY) { WebJson.error(200, "x", "x") }
            .post("/api/like/{videoId}") { WebJson.error(200, "x", "x") }
            .get("/api/ping") { WebJson.error(200, "x", "x") }
        assertEquals(WebLimits.VOICE_BODY, (r.match("POST", "/api/voice") as WebMatch.Found).route.maxBody)
        assertEquals(WebLimits.JSON_BODY, (r.match("POST", "/api/like/x") as WebMatch.Found).route.maxBody)
        assertEquals(0, (r.match("GET", "/api/ping") as WebMatch.Found).route.maxBody)
    }

    @Test
    fun `a bad template is refused when the route is made`() {
        assertFailsWith<IllegalArgumentException> { DesktopWebRouter().get("api/ping") { WebJson.error(200, "x", "x") } }
        assertFailsWith<IllegalArgumentException> { DesktopWebRouter().get("/api//ping") { WebJson.error(200, "x", "x") } }
    }

    @Test
    fun `event json shapes follow the contract`() {
        val signed = assertNotNull(WebJson.parseObject(DesktopWebEventHub.signedInJson(true).toByteArray()))
        assertEquals("signedIn", signed["type"]?.jsonPrimitive?.content)
        assertEquals("true", signed["signedIn"]?.jsonPrimitive?.content)
        val off = assertNotNull(WebJson.parseObject(DesktopWebEventHub.signedInJson(false).toByteArray()))
        assertEquals("false", off["signedIn"]?.jsonPrimitive?.content)
    }
}
