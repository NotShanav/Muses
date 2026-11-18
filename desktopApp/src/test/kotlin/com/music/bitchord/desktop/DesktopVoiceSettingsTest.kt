package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice V4: the pure parts of the Settings page. No microphone, window, model, network or saved
 * settings; temp files are empty and deleted in `finally`.
 */
class DesktopVoiceSettingsTest {

    private fun filesIn(dir: Path) = VoiceModelFiles(
        dir,
        dir.resolve(DesktopVoiceModelSpec.ENCODER),
        dir.resolve(DesktopVoiceModelSpec.DECODER),
        dir.resolve(DesktopVoiceModelSpec.JOINER),
        dir.resolve(DesktopVoiceModelSpec.TOKENS),
    )

    private fun withTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("muses-voice-settings-test")
        try {
            block(root)
        } finally {
            DesktopVoiceSidecar.deleteTree(root)
        }
    }

    // ── model status ─────────────────────────────────────────────────────────────────────────

    @Test
    fun readyInTheManagedFolderBeatsAWrongFormatChosenFolder() {
        val chosen = Paths.get("chosen-dir")
        val managed = Paths.get("managed-dir")
        val status = VoiceSettingsLogic.modelStatus(chosen.toString(), managed) { dir ->
            if (VoiceSettingsLogic.sameFolder(dir, chosen)) VoiceModelScan.WrongFormat(dir, "different format")
            else VoiceModelScan.Ready(filesIn(dir))
        }
        assertEquals(VoiceModelStatus.Kind.READY, status.kind)
        assertNotNull(status.files)
        assertTrue(VoiceSettingsLogic.sameFolder(managed, status.dir!!))
    }

    @Test
    fun wrongFormatIsReportedWhenNothingIsReady() {
        val chosen = Paths.get("chosen-dir")
        val managed = Paths.get("managed-dir")
        val status = VoiceSettingsLogic.modelStatus(chosen.toString(), managed) { dir ->
            if (VoiceSettingsLogic.sameFolder(dir, chosen)) VoiceModelScan.WrongFormat(dir, "different format")
            else VoiceModelScan.NotFound
        }
        assertEquals(VoiceModelStatus.Kind.WRONG_FORMAT, status.kind)
        assertNull(status.files)
        val text = VoiceSettingsLogic.modelStatusText(status)
        assertTrue(text.contains("different format"))
        assertTrue(text.contains("download the sherpa version"))
    }

    @Test
    fun nothingFoundIsNotInstalled() {
        val status = VoiceSettingsLogic.modelStatus(null, Paths.get("managed-dir")) { VoiceModelScan.NotFound }
        assertEquals(VoiceModelStatus.Kind.NOT_INSTALLED, status.kind)
        assertEquals("Not installed", VoiceSettingsLogic.modelStatusText(status))
    }

    @Test
    fun withoutAChosenFolderOnlyTheManagedOneIsChecked() {
        val seen = ArrayList<Path>()
        VoiceSettingsLogic.modelStatus(null, Paths.get("managed-dir")) {
            seen += it
            VoiceModelScan.NotFound
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun theSameFolderIsCheckedOnlyOnce() {
        val seen = ArrayList<Path>()
        VoiceSettingsLogic.modelStatus("managed-dir", Paths.get("managed-dir")) {
            seen += it
            VoiceModelScan.NotFound
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun readyStatusNamesTheFolder() {
        val dir = Paths.get("somewhere")
        val status = VoiceSettingsLogic.modelStatus(dir.toString(), Paths.get("managed")) {
            VoiceModelScan.Ready(filesIn(it))
        }
        assertTrue(VoiceSettingsLogic.modelStatusText(status).startsWith("Ready at "))
        assertTrue(VoiceSettingsLogic.modelStatusText(status).contains("somewhere"))
    }

    // ── folders ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun sameFolderIgnoresDotSegments() = withTemp { root ->
        val a = root.resolve("a")
        val b = root.resolve("a").resolve("x").resolve("..")
        assertTrue(VoiceSettingsLogic.sameFolder(a, b))
        assertFalse(VoiceSettingsLogic.sameFolder(a, root.resolve("b")))
        assertTrue(VoiceSettingsLogic.isManagedModelDir(a, b))
        assertFalse(VoiceSettingsLogic.isManagedModelDir(root, a))
    }

    @Test
    fun removeDeletesOnlyTheModelFilesAndKeepsOtherFiles() = withTemp { root ->
        val dir = root.resolve("model")
        Files.createDirectories(dir)
        DesktopVoiceModelSpec.files.forEach { Files.write(dir.resolve(it.name), ByteArray(0)) }
        Files.write(dir.resolve(DesktopVoiceModelSpec.ENCODER + ".part"), ByteArray(0))
        Files.write(dir.resolve("notes.txt"), ByteArray(0))

        assertTrue(VoiceSettingsLogic.removeModelFiles(dir))

        DesktopVoiceModelSpec.files.forEach { assertFalse(Files.exists(dir.resolve(it.name))) }
        assertFalse(Files.exists(dir.resolve(DesktopVoiceModelSpec.ENCODER + ".part")))
        assertTrue(Files.exists(dir.resolve("notes.txt")))
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun removeDeletesTheFolderWhenItIsLeftEmpty() = withTemp { root ->
        val dir = root.resolve("model")
        Files.createDirectories(dir)
        DesktopVoiceModelSpec.files.forEach { Files.write(dir.resolve(it.name), ByteArray(0)) }
        assertTrue(VoiceSettingsLogic.removeModelFiles(dir))
        assertFalse(Files.exists(dir))
    }

    @Test
    fun removeOnAFolderThatDoesNotExistIsHarmless() = withTemp { root ->
        assertTrue(VoiceSettingsLogic.removeModelFiles(root.resolve("missing")))
    }

    // ── program ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyTheSherpaProgramNameIsAccepted() {
        assertTrue(VoiceSettingsLogic.looksLikeProgramName("sherpa-onnx-offline.exe"))
        assertTrue(VoiceSettingsLogic.looksLikeProgramName("Sherpa-Onnx-Offline.EXE"))
        assertTrue(VoiceSettingsLogic.looksLikeProgramName("sherpa-onnx-offline"))
        assertFalse(VoiceSettingsLogic.looksLikeProgramName("notepad.exe"))
        assertFalse(VoiceSettingsLogic.looksLikeProgramName("sherpa-onnx-offline.exe.bak"))
    }

    @Test
    fun programStatusText() {
        assertEquals("Not found", VoiceSettingsLogic.programStatusText(null))
        assertTrue(VoiceSettingsLogic.programStatusText(Paths.get("p", "x.exe")).startsWith("Ready at "))
    }

    // ── hotkey ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun hotkeyNeedsAModifierAndAKnownKey() {
        val ok = VoiceSettingsLogic.hotkeyFrom(0x41, ctrl = true, alt = false, shift = false, win = false)
        assertEquals("Ctrl+A", ok?.describe())
        assertNull(VoiceSettingsLogic.hotkeyFrom(0x41, ctrl = false, alt = false, shift = false, win = false))
        assertNull(VoiceSettingsLogic.hotkeyFrom(null, ctrl = true, alt = true, shift = false, win = false))
        assertNull(VoiceSettingsLogic.hotkeyFrom(0x14, ctrl = true, alt = false, shift = false, win = false))
    }

    @Test
    fun hotkeyKeepsEveryModifierInOrder() {
        val combo = VoiceSettingsLogic.hotkeyFrom(0x20, ctrl = true, alt = true, shift = true, win = true)
        assertEquals("Ctrl+Alt+Shift+Win+Space", combo?.describe())
        val reparsed = VoiceHotkey.parse(combo!!.describe())
        assertEquals(combo, reparsed)
    }

    @Test
    fun theDefaultHotkeyRoundTripsThroughItsSavedText() {
        val saved = VoiceHotkey.DEFAULT.describe()
        assertEquals(VoiceHotkey.DEFAULT, VoiceHotkey.parse(saved))
    }

    @Test
    fun composeKeysMapToWindowsKeyCodes() {
        assertEquals(0x41, voiceVkFromComposeKey(Key.A))
        assertEquals(0x5A, voiceVkFromComposeKey(Key.Z))
        assertEquals(0x30, voiceVkFromComposeKey(Key.Zero))
        assertEquals(0x39, voiceVkFromComposeKey(Key.Nine))
        assertEquals(0x70, voiceVkFromComposeKey(Key.F1))
        assertEquals(0x7B, voiceVkFromComposeKey(Key.F12))
        assertEquals(0x20, voiceVkFromComposeKey(Key.Spacebar))
        assertNull(voiceVkFromComposeKey(Key.CtrlLeft))
        assertNull(voiceVkFromComposeKey(Key.ShiftRight))
    }

    @Test
    fun everyMappedKeyMakesAValidHotkeyWithAModifier() {
        val keys = listOf(
            Key.A, Key.M, Key.Z, Key.Zero, Key.Five, Key.F1, Key.F12, Key.Spacebar, Key.Enter, Key.Tab,
            Key.Backspace, Key.PageUp, Key.PageDown, Key.MoveEnd, Key.MoveHome, Key.DirectionLeft,
            Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.Insert, Key.Delete,
        )
        for (key in keys) {
            val vk = voiceVkFromComposeKey(key)
            assertNotNull(vk, "no code for $key")
            assertTrue(VoiceHotkey(VoiceHotkey.MOD_CONTROL, vk).isValid, "invalid for $key")
        }
    }

    // ── messages ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun errorCodesBecomeSentences() {
        assertTrue(VoiceSettingsLogic.downloadErrorText("network").contains("Could not reach"))
        assertTrue(VoiceSettingsLogic.downloadErrorText("http_404").contains("404"))
        assertTrue(VoiceSettingsLogic.downloadErrorText("bad_hash").contains("deleted"))
        assertTrue(VoiceSettingsLogic.downloadErrorText("bad_size").contains("deleted"))
        assertTrue(VoiceSettingsLogic.downloadErrorText("extract_failed").contains("unpack"))
        assertTrue(VoiceSettingsLogic.downloadErrorText("unsupported_platform").contains("Choose the program"))
        assertEquals("The download failed.", VoiceSettingsLogic.downloadErrorText("something_else"))
    }

    @Test
    fun codesAreReadFromVoiceExceptions() {
        assertEquals("bad_hash", VoiceSettingsLogic.codeOf(VoiceException("bad_hash")))
        assertEquals("download_failed", VoiceSettingsLogic.codeOf(IllegalStateException("x")))
        assertEquals("download_failed", VoiceSettingsLogic.codeOf(null))
        assertEquals(VoiceErrors.NO_MICROPHONE, VoiceSettingsLogic.testCodeOf(VoiceException(VoiceErrors.NO_MICROPHONE)))
        assertEquals(VoiceErrors.RECORD_FAILED, VoiceSettingsLogic.testCodeOf(RuntimeException("x")))
    }

    @Test
    fun noMicrophoneShowsTheWindowsPrivacyHint() {
        val text = VoiceMessages.forCode(VoiceSettingsLogic.testCodeOf(VoiceException(VoiceErrors.NO_MICROPHONE)))
        assertTrue(text.contains("Privacy"))
        assertTrue(text.contains("Microphone"))
    }

    @Test
    fun percentIsClamped() {
        assertEquals("0 %", VoiceSettingsLogic.percent(-1f))
        assertEquals("50 %", VoiceSettingsLogic.percent(0.5f))
        assertEquals("100 %", VoiceSettingsLogic.percent(3f))
    }

    @Test
    fun installStartsIdle() {
        assertEquals(VoiceInstallState.Idle, DesktopVoiceInstall.model.value)
        assertEquals(VoiceInstallState.Idle, DesktopVoiceInstall.program.value)
    }
}
