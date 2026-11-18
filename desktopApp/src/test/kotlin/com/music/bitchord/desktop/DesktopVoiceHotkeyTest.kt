package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice V3: the pure hotkey helper. Nothing here registers a real hotkey. */
class DesktopVoiceHotkeyTest {

    @Test
    fun defaultDescribesAsCtrlAltSpace() {
        assertEquals("Ctrl+Alt+Space", VoiceHotkey.DEFAULT.describe())
        assertTrue(VoiceHotkey.DEFAULT.isValid)
    }

    @Test
    fun parseDefaultAndCaseAndSpaces() {
        assertEquals(VoiceHotkey.DEFAULT, VoiceHotkey.parse("Ctrl+Alt+Space"))
        assertEquals(VoiceHotkey.DEFAULT, VoiceHotkey.parse("ctrl + alt + space"))
        assertEquals(VoiceHotkey.DEFAULT, VoiceHotkey.parse("ALT+CONTROL+SPACE"))
    }

    @Test
    fun parseLettersDigitsAndFunctionKeys() {
        assertEquals(
            VoiceHotkey(VoiceHotkey.MOD_CONTROL or VoiceHotkey.MOD_SHIFT, 0x4B),
            VoiceHotkey.parse("Control+Shift+K"),
        )
        assertEquals(VoiceHotkey(VoiceHotkey.MOD_ALT, 0x78), VoiceHotkey.parse("Alt+F9"))
        assertEquals(VoiceHotkey(VoiceHotkey.MOD_WIN, 0x31), VoiceHotkey.parse("Win+1"))
        assertEquals(VoiceHotkey(VoiceHotkey.MOD_WIN, 0x31), VoiceHotkey.parse("Windows+1"))
    }

    @Test
    fun parseRejectsWhatIsNotAHotkey() {
        assertNull(VoiceHotkey.parse(""))
        assertNull(VoiceHotkey.parse("Space"))
        assertNull(VoiceHotkey.parse("Ctrl"))
        assertNull(VoiceHotkey.parse("Ctrl+Alt"))
        assertNull(VoiceHotkey.parse("Ctrl+A+B"))
        assertNull(VoiceHotkey.parse("Ctrl+Foo"))
        assertNull(VoiceHotkey.parse("Ctrl++"))
        assertNull(VoiceHotkey.parse("Ctrl+F25"))
    }

    @Test
    fun describeOrdersModifiers() {
        val hotkey = VoiceHotkey(
            VoiceHotkey.MOD_WIN or VoiceHotkey.MOD_SHIFT or VoiceHotkey.MOD_CONTROL,
            0x4B,
        )
        assertEquals("Ctrl+Shift+Win+K", hotkey.describe())
        assertEquals(hotkey, VoiceHotkey.parse(hotkey.describe()))
    }

    @Test
    fun describeRoundTripsFunctionAndNamedKeys() {
        for (text in listOf("Ctrl+F1", "Alt+F24", "Ctrl+Alt+Enter", "Shift+Ctrl+Home", "Ctrl+0")) {
            val parsed = assertNotNull(VoiceHotkey.parse(text), text)
            assertEquals(parsed, VoiceHotkey.parse(parsed.describe()), text)
        }
    }

    @Test
    fun validityNeedsAModifierAndAKnownKey() {
        assertFalse(VoiceHotkey(0, VoiceHotkey.VK_SPACE).isValid)
        assertFalse(VoiceHotkey(VoiceHotkey.MOD_CONTROL, 0).isValid)
        assertFalse(VoiceHotkey(VoiceHotkey.MOD_CONTROL, 0xFF).isValid)
        assertTrue(VoiceHotkey(VoiceHotkey.MOD_CONTROL, 0x41).isValid)
    }

    @Test
    fun keyNames() {
        assertEquals("A", VoiceHotkey.keyName(0x41))
        assertEquals("7", VoiceHotkey.keyName(0x37))
        assertEquals("F1", VoiceHotkey.keyName(0x70))
        assertEquals("F24", VoiceHotkey.keyName(0x87))
        assertEquals("Space", VoiceHotkey.keyName(0x20))
        assertNull(VoiceHotkey.keyName(0x07))
    }

    @Test
    fun hotkeyIsUnavailableOffWindowsAndDoesNothing() {
        if (DesktopPlatform.isWindows) return // would register a real hotkey
        var events = 0
        val hotkey = DesktopVoiceHotkey(onPressed = { events++ }, onReleased = { events++ })
        hotkey.start(VoiceHotkey.DEFAULT)
        assertEquals(DesktopVoiceHotkey.Status.UNAVAILABLE, hotkey.status.value)
        hotkey.stop()
        assertEquals(DesktopVoiceHotkey.Status.STOPPED, hotkey.status.value)
        assertEquals(0, events)
        assertFalse(DesktopVoiceKeys.isDown(DesktopVoiceKeys.VK_ESCAPE))
    }
}
