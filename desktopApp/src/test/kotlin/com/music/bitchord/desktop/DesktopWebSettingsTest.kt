package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WEB1: setting rules, the `--web` flag, and the text of the status row. */
class DesktopWebSettingsTest {

    @Test
    fun `a saved port outside the range falls back to the default`() {
        assertEquals(8765, DesktopWebSettingsLogic.normalizePort(8765))
        assertEquals(9000, DesktopWebSettingsLogic.normalizePort(9000))
        assertEquals(8765, DesktopWebSettingsLogic.normalizePort(80))
        assertEquals(8765, DesktopWebSettingsLogic.normalizePort(70_000))
        assertEquals(8765, DesktopWebSettingsLogic.normalizePort(-1))
    }

    @Test
    fun `the stepper pulls a port into range`() {
        assertEquals(1024, DesktopWebSettingsLogic.clampPort(80))
        assertEquals(65_000, DesktopWebSettingsLogic.clampPort(70_000))
        assertEquals(8766, DesktopWebSettingsLogic.clampPort(8766))
    }

    @Test
    fun `background mode needs the flag or both settings`() {
        assertTrue(DesktopWebSettingsLogic.backgroundRequested(arrayOf("--web"), false, false))
        assertTrue(DesktopWebSettingsLogic.backgroundRequested(emptyArray(), true, true))
        assertFalse(DesktopWebSettingsLogic.backgroundRequested(emptyArray(), true, false))
        assertFalse(DesktopWebSettingsLogic.backgroundRequested(emptyArray(), false, true))
        assertFalse(DesktopWebSettingsLogic.backgroundRequested(arrayOf("--other"), false, false))
    }

    @Test
    fun `status text`() {
        assertEquals("x", WebSettingsText.statusLine(DesktopWebStatus.Failed("x")))
        assertEquals("Running at http://localhost:8765/", WebSettingsText.statusLine(DesktopWebStatus.Running(8765, false, null)))
        val lan = WebSettingsText.statusLine(DesktopWebStatus.Running(8765, true, "http://192.168.1.5:8765/"))
        assertTrue("http://localhost:8765/" in lan && "http://192.168.1.5:8765/" in lan)
        assertTrue("address not found" in WebSettingsText.statusLine(DesktopWebStatus.Running(8765, true, null)))
    }

    @Test
    fun `the server tries twenty more ports`() {
        assertEquals(20, DesktopWebServer.PORT_TRIES)
    }
}
