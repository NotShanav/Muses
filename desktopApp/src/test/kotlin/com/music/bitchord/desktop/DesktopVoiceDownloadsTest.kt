package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice V2: progress maths, verification, paths and program lookup. Tiny temp files only, no network. */
class DesktopVoiceDownloadsTest {

    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun withTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("muses-voice-test")
        try {
            block(root)
        } finally {
            DesktopVoiceSidecar.deleteTree(root)
        }
    }

    private fun touch(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(0))
        return path
    }

    @Test
    fun progressMaths() {
        assertEquals(0f, VoiceDownloadMath.fraction(5, 0))
        assertEquals(0f, VoiceDownloadMath.fraction(-1, 10))
        assertEquals(0.5f, VoiceDownloadMath.fraction(5, 10))
        assertEquals(1f, VoiceDownloadMath.fraction(50, 10))
        assertEquals(0.75f, VoiceDownloadMath.overall(50, 25, 100))
        assertEquals(0.4f, VoiceDownloadMath.slice(0.5f, 0f, 0.8f), 0.0001f)
        assertEquals(0.8f, VoiceDownloadMath.slice(3f, 0f, 0.8f), 0.0001f)
    }

    @Test
    fun verifyChecksSizeAndHash() {
        withTemp { root ->
            val file = root.resolve("f.bin")
            Files.write(file, "abc".toByteArray())
            assertNull(VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 3, abcHash)))
            assertNull(VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 3, abcHash.uppercase())))
            assertEquals("bad_size", VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 4, abcHash)))
            assertEquals("bad_hash", VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 3, "00" + abcHash.drop(2))))
            assertNull(VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 4, abcHash), sizeTolerance = 0.5))
            assertEquals("bad_hash", VoiceDownloadMath.verify(file, VoiceModelFileSpec("f.bin", 3, abcHash), hash = { error("x") }))
            assertEquals("bad_size", VoiceDownloadMath.verify(root.resolve("missing"), VoiceModelFileSpec("m", 3, abcHash)))
        }
    }

    @Test
    fun candidateUrlsFollowTheConstantsObject() {
        val urls = DesktopVoiceSidecarSpec.candidateUrls()
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.21/sherpa-onnx-v1.12.21-win-x64-shared.tar.bz2",
            urls.first(),
        )
        assertEquals(DesktopVoiceSidecarSpec.ASSET_SUFFIXES.size, urls.size)
        assertEquals("https://x.test/v9.9.9/sherpa-onnx-v9.9.9-win-x64-shared.tar.bz2", DesktopVoiceSidecarSpec.candidateUrls("9.9.9", "https://x.test").first())
        assertFalse(DesktopVoiceSidecarSpec.VERIFIED)
    }

    @Test
    fun dataDirOnWindowsAndLinux() {
        val win = DesktopVoicePaths.dataDir({ if (it == "LOCALAPPDATA") "LOCAL" else null }, "HOME", true)
        assertEquals(Paths.get("LOCAL").resolve("Muses").resolve("voice"), win)
        val winFallback = DesktopVoicePaths.dataDir({ null }, "HOME", true)
        assertEquals(Paths.get("HOME", "AppData", "Local").resolve("Muses").resolve("voice"), winFallback)
        val lin = DesktopVoicePaths.dataDir({ if (it == "XDG_DATA_HOME") "XDG" else null }, "HOME", false)
        assertEquals(Paths.get("XDG").resolve("muses").resolve("voice"), lin)
        val linFallback = DesktopVoicePaths.dataDir({ null }, "HOME", false)
        assertEquals(Paths.get("HOME", ".local", "share").resolve("muses").resolve("voice"), linFallback)
        assertEquals(win.resolve("program"), DesktopVoicePaths.programDir(win))
        assertEquals(win.resolve("model"), DesktopVoicePaths.modelDir(win))
    }

    @Test
    fun findsTheProgramInEveryPlaceInOrder() {
        withTemp { root ->
            val none = root.resolve("nothing")
            // Nothing anywhere.
            assertNull(DesktopVoiceSidecar.findExisting(null, emptyList(), none, null, ":"))

            // Owner-chosen file wins, and a folder holding the program works too.
            val chosenFile = touch(root.resolve("chosen/sherpa-onnx-offline.exe"))
            assertEquals(chosenFile, DesktopVoiceSidecar.findExisting(chosenFile.toString(), emptyList(), none, null, ":"))
            val chosenDir = root.resolve("chosen")
            assertEquals(chosenFile, DesktopVoiceSidecar.findExisting(chosenDir.toString(), emptyList(), none, null, ":"))
            assertNull(DesktopVoiceSidecar.findExisting("   ", emptyList(), none, null, ":"))

            // Next to the app.
            val appExe = touch(root.resolve("app/SHERPA-ONNX-OFFLINE.EXE"))
            assertEquals(appExe, DesktopVoiceSidecar.findExisting(null, listOf(root.resolve("app")), none, null, ":"))

            // Inside the app data folder, a few levels down.
            val dataExe = touch(root.resolve("data/program/sherpa-onnx-v1/bin/sherpa-onnx-offline.exe"))
            assertEquals(dataExe, DesktopVoiceSidecar.findExisting(null, emptyList(), root.resolve("data"), null, ":"))

            // On PATH.
            val pathExe = touch(root.resolve("onpath/sherpa-onnx-offline.exe"))
            val sep = java.io.File.pathSeparator
            val pathVar = root.resolve("empty").toString() + sep + root.resolve("onpath").toString()
            assertEquals(pathExe, DesktopVoiceSidecar.findExisting(null, emptyList(), none, pathVar, sep))
        }
    }

    @Test
    fun findInDirRespectsDepth() {
        withTemp { root ->
            touch(root.resolve("a/b/c/sherpa-onnx-offline.exe"))
            assertNull(DesktopVoiceSidecar.findInDir(root, 2))
            assertTrue(DesktopVoiceSidecar.findInDir(root, 4) != null)
            assertNull(DesktopVoiceSidecar.findInDir(root.resolve("missing"), 3))
        }
    }

    @Test
    fun deleteTreeRemovesEverythingAndToleratesMissing() {
        val dir = Files.createTempDirectory("muses-voice-del")
        touch(dir.resolve("x/y/z.txt"))
        DesktopVoiceSidecar.deleteTree(dir)
        assertFalse(Files.exists(dir))
        DesktopVoiceSidecar.deleteTree(dir)
    }
}
