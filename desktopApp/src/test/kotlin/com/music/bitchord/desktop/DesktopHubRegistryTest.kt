package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHubRegistryTest {
    private class FakeStore(var value: String? = null) : HubKeyValueStore {
        override fun read(key: String): String? = value
        override fun write(key: String, value: String) { this.value = value }
    }

    private fun ids(r: HubRegistry) = r.services.map { it.id }

    @Test
    fun defaultsMatchThePlan() {
        val r = HubRegistry.defaults()
        assertEquals(listOf("youtube", "netflix", "prime-video", "jiohotstar", "twitch"), ids(r))
        assertEquals("https://www.youtube.com", r.find("youtube")?.startUrl)
        assertEquals("https://www.hotstar.com", r.find("jiohotstar")?.startUrl)
        assertTrue(r.services.all { it.enabled && !it.keepAlive })
        assertEquals(HubBrowserPreference.AUTO, r.settings.browserPreference)
        assertEquals(1600, r.settings.memoryWarnMb)
        assertEquals(2048, r.settings.memoryCapMb)
        assertEquals(1, r.settings.maxLiveServices)
    }

    @Test
    fun addRemoveRename() {
        var r = HubRegistry.defaults()
        val added = r.add("  Plex  ", "plex.tv")
        assertTrue(added.ok)
        r = added.registry
        assertEquals("plex", r.services.last().id)
        assertEquals("Plex", r.services.last().name)
        assertEquals("https://plex.tv", r.services.last().startUrl)

        r = r.add("Plex", "https://app.plex.tv").registry
        assertEquals("plex-2", r.services.last().id)

        r = r.rename("plex", "My Plex").registry
        assertEquals("My Plex", r.find("plex")?.name)
        assertEquals("plex", r.find("plex")?.id)

        r = r.remove("plex-2").registry
        assertNull(r.find("plex-2"))
        assertEquals(HubError.NOT_FOUND, r.remove("nope").error)
        assertEquals(HubError.NOT_FOUND, r.rename("nope", "X").error)
    }

    @Test
    fun refusedEditsLeaveTheRegistryUnchanged() {
        val r = HubRegistry.defaults()
        val badName = r.add("   ", "https://a.com")
        assertEquals(HubError.INVALID_NAME, badName.error)
        assertEquals(r, badName.registry)
        assertEquals(HubError.INVALID_NAME, r.add("x".repeat(HubLimits.MAX_NAME_LENGTH + 1), "https://a.com").error)
        assertEquals(HubError.INVALID_URL, r.add("Bad", "ftp://a.com").error)
        assertEquals(HubError.INVALID_NAME, r.rename("youtube", " ").error)
        assertEquals(HubError.INVALID_URL, r.updateUrl("youtube", "javascript:alert(1)").error)
        assertEquals("https://www.youtube.com", r.updateUrl("youtube", "javascript:alert(1)").registry.find("youtube")?.startUrl)
    }

    @Test
    fun serviceLimitIsEnforced() {
        var r = HubRegistry(services = emptyList())
        repeat(HubLimits.MAX_SERVICES) { r = r.add("S$it", "https://s$it.com").registry }
        assertEquals(HubLimits.MAX_SERVICES, r.services.size)
        assertEquals(HubError.LIMIT_REACHED, r.add("One more", "https://more.com").error)
    }

    @Test
    fun reorder() {
        var r = HubRegistry.defaults()
        r = r.move("twitch", 0).registry
        assertEquals(listOf("twitch", "youtube", "netflix", "prime-video", "jiohotstar"), ids(r))
        r = r.moveBy("twitch", 2).registry
        assertEquals(listOf("youtube", "netflix", "twitch", "prime-video", "jiohotstar"), ids(r))
        r = r.move("youtube", 99).registry
        assertEquals("youtube", ids(r).last())
        r = r.moveBy("youtube", -99).registry
        assertEquals("youtube", ids(r).first())
        assertEquals(5, r.services.size)
        assertEquals(HubError.NOT_FOUND, r.move("nope", 0).error)
    }

    @Test
    fun flagsAndSettings() {
        var r = HubRegistry.defaults()
        r = r.setEnabled("netflix", false).registry.setKeepAlive("youtube", true).registry
        assertFalse(r.find("netflix")!!.enabled)
        assertTrue(r.find("youtube")!!.keepAlive)
        r = r.updateSettings(HubSettings(memoryWarnMb = 100, memoryCapMb = 50, maxLiveServices = 99))
        assertEquals(HubLimits.MIN_WARN_MB, r.settings.memoryWarnMb)
        assertEquals(HubLimits.MIN_WARN_MB, r.settings.memoryCapMb)
        assertEquals(HubLimits.MAX_LIVE, r.settings.maxLiveServices)
        r = r.resetServices()
        assertTrue(r.find("netflix")!!.enabled)
    }

    @Test
    fun urlValidation() {
        assertEquals("https://www.youtube.com", HubUrls.normalize("https://www.youtube.com"))
        assertEquals("https://youtube.com", HubUrls.normalize("  youtube.com "))
        assertEquals("http://localhost:3000/x", HubUrls.normalize("http://localhost:3000/x"))
        assertEquals("https://Example.com/A", HubUrls.normalize("HTTPS://Example.com/A"))
        assertNull(HubUrls.normalize(""))
        assertNull(HubUrls.normalize("   "))
        assertNull(HubUrls.normalize("ftp://example.com"))
        assertNull(HubUrls.normalize("file:///C:/x"))
        assertNull(HubUrls.normalize("javascript:alert(1)"))
        assertNull(HubUrls.normalize("data:text/html,hi"))
        assertNull(HubUrls.normalize("https://"))
        assertNull(HubUrls.normalize("https://exa mple.com"))
        assertNull(HubUrls.normalize("https://user:pw@example.com"))
        assertNull(HubUrls.normalize("//example.com"))
        assertNull(HubUrls.normalize("https://example.com/" + "a".repeat(HubLimits.MAX_URL_LENGTH)))
    }

    @Test
    fun jsonRoundTrip() {
        val fake = FakeStore()
        val store = HubRegistryStore(fake)
        val r = HubRegistry.defaults()
            .add("Plex", "plex.tv").registry
            .setKeepAlive("youtube", true).registry
            .move("twitch", 0).registry
            .updateSettings(HubSettings(HubBrowserPreference.EDGE, 1500, 1900, 2))
        store.save(r)
        assertNotNull(fake.value)
        assertEquals(r, store.load())
        assertEquals(r, HubRegistryCodec.decode(HubRegistryCodec.encode(r)))
    }

    @Test
    fun missingOrCorruptJsonFallsBackToDefaults() {
        assertEquals(HubRegistry.defaults(), HubRegistryStore(FakeStore(null)).load())
        for (bad in listOf("", "{not json", "[]", "null", "42", "{\"version\":\"x\"}", "{\"services\":5}", "{\"version\":99}")) {
            assertEquals(HubRegistry.defaults(), HubRegistryStore(FakeStore(bad)).load(), "payload: $bad")
        }
    }

    @Test
    fun decodeSanitizesBadEntries() {
        val raw = """{"version":1,"services":[
            {"id":"a","name":"Good","startUrl":"https://a.com"},
            {"id":"a","name":"Dup id","startUrl":"https://b.com"},
            {"id":"","name":"No id","startUrl":"c.com"},
            {"id":"d","name":"","startUrl":"https://d.com"},
            {"id":"e","name":"Bad url","startUrl":"javascript:alert(1)"}
          ],"settings":{"browserPreference":"NOPE","memoryWarnMb":2000,"memoryCapMb":1000,"maxLiveServices":0},"extra":true}"""
        val r = HubRegistryCodec.decode(raw)!!
        assertEquals(listOf("a", "dup-id", "no-id"), ids(r))
        assertEquals("https://c.com", r.find("no-id")?.startUrl)
        assertEquals(HubBrowserPreference.AUTO, r.settings.browserPreference)
        assertEquals(2000, r.settings.memoryWarnMb)
        assertEquals(2000, r.settings.memoryCapMb)
        assertEquals(1, r.settings.maxLiveServices)
    }

    @Test
    fun emptyListIsKeptWhenSavedOnPurpose() {
        val fake = FakeStore()
        val store = HubRegistryStore(fake)
        store.save(HubRegistry(services = emptyList()))
        assertTrue(store.load().services.isEmpty())
    }
}
