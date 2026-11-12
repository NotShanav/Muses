package com.music.bitchord.desktop

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHubBrowserTest {
    private val local = Paths.get("C:/Users/test/AppData/Local")
    private val pf = Paths.get("C:/Program Files")
    private val pf86 = Paths.get("C:/Program Files (x86)")
    private val env = HubBrowserEnv(local, pf, pf86)

    private val brave = local.resolve("BraveSoftware/Brave-Browser/Application/brave.exe")
    private val chrome = local.resolve("Google/Chrome/Application/chrome.exe")
    private val edge = pf86.resolve("Microsoft/Edge/Application/msedge.exe")

    private fun existing(vararg paths: Path): (Path) -> Boolean = { it in paths.toSet() }

    @Test
    fun autoOrderIsBraveChromeEdge() {
        assertEquals(HubBrowser("Brave", brave), HubBrowserFinder.find(HubBrowserPreference.AUTO, env, existing(brave, chrome, edge)))
        assertEquals(HubBrowser("Chrome", chrome), HubBrowserFinder.find(HubBrowserPreference.AUTO, env, existing(chrome, edge)))
        assertEquals(HubBrowser("Edge", edge), HubBrowserFinder.find(HubBrowserPreference.AUTO, env, existing(edge)))
    }

    @Test
    fun noBrowserInstalledGivesNull() {
        assertNull(HubBrowserFinder.find(HubBrowserPreference.AUTO, env) { false })
        assertNull(HubBrowserFinder.find(HubBrowserPreference.EDGE, env) { false })
    }

    @Test
    fun specificPreferenceWinsWhenInstalledAndFallsBackWhenNot() {
        assertEquals("Edge", HubBrowserFinder.find(HubBrowserPreference.EDGE, env, existing(brave, chrome, edge))?.name)
        assertEquals("Chrome", HubBrowserFinder.find(HubBrowserPreference.CHROME, env, existing(brave, chrome, edge))?.name)
        // Preferred Edge is missing: fall back to the AUTO order.
        assertEquals("Brave", HubBrowserFinder.find(HubBrowserPreference.EDGE, env, existing(brave, chrome))?.name)
        assertEquals("Chrome", HubBrowserFinder.find(HubBrowserPreference.BRAVE, env, existing(chrome, edge))?.name)
    }

    @Test
    fun installFoldersAreTriedPerUserThenProgramFilesThenX86() {
        val chromeLocal = local.resolve("Google/Chrome/Application/chrome.exe")
        val chromePf = pf.resolve("Google/Chrome/Application/chrome.exe")
        val chromePf86 = pf86.resolve("Google/Chrome/Application/chrome.exe")
        assertEquals(chromeLocal, HubBrowserFinder.find(HubBrowserPreference.CHROME, env, existing(chromeLocal, chromePf, chromePf86))?.executable)
        assertEquals(chromePf, HubBrowserFinder.find(HubBrowserPreference.CHROME, env, existing(chromePf, chromePf86))?.executable)
        assertEquals(chromePf86, HubBrowserFinder.find(HubBrowserPreference.CHROME, env, existing(chromePf86))?.executable)
    }

    @Test
    fun missingProgramFilesFoldersAreSkipped() {
        val localOnly = HubBrowserEnv(local)
        val candidates = HubBrowserFinder.candidates(HubBrowserPreference.AUTO, localOnly)
        assertEquals(3, candidates.size)
        assertEquals(listOf("Brave", "Chrome", "Edge"), candidates.map { it.name })
        assertTrue(candidates.all { it.executable.startsWith(local) })
    }

    @Test
    fun candidateListIsCompleteAndOrdered() {
        val names = HubBrowserFinder.candidates(HubBrowserPreference.EDGE, env).map { it.name }
        assertEquals(listOf("Edge", "Edge", "Edge", "Brave", "Brave", "Brave", "Chrome", "Chrome", "Chrome"), names)
    }

    @Test
    fun profileFolderSitsNextToOtherMusesData() {
        assertEquals(local.resolve("Muses").resolve("Hub").resolve("Profile"), HubBrowserLaunch.profileDir(env))
    }

    @Test
    fun launchArgsAreExact() {
        val profile = HubBrowserLaunch.profileDir(env)
        val args = HubBrowserLaunch.buildLaunchArgs(HubBrowser("Brave", brave), profile, "https://www.youtube.com")
        assertEquals(
            listOf(
                brave.toString(),
                "--app=https://www.youtube.com",
                "--user-data-dir=${profile.toAbsolutePath()}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
            ),
            args,
        )
    }

    @Test
    fun launchArgsNormalizeBareHostsAndRefuseBadUrls() {
        val profile = HubBrowserLaunch.profileDir(env)
        val browser = HubBrowser("Edge", edge)
        assertTrue("--app=https://plex.tv" in HubBrowserLaunch.buildLaunchArgs(browser, profile, "plex.tv"))
        assertFailsWith<IllegalArgumentException> { HubBrowserLaunch.buildLaunchArgs(browser, profile, "javascript:alert(1)") }
        assertFailsWith<IllegalArgumentException> { HubBrowserLaunch.buildLaunchArgs(browser, profile, "file:///C:/x") }
        assertFailsWith<IllegalArgumentException> { HubBrowserLaunch.buildLaunchArgs(browser, profile, "") }
    }
}
