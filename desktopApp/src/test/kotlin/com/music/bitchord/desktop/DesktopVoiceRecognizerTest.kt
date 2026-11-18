package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice V2: WAV bytes, output parsing, command line, RAM guard, cleanup and the recognizer with a fake program. */
class DesktopVoiceRecognizerTest {

    private fun withTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("muses-voice-test")
        try {
            block(root)
        } finally {
            VoiceRecognizerSupport.wipeTempDir(root)
            DesktopVoiceSidecar.deleteTree(root)
        }
    }

    private fun fakeModel(dir: Path): VoiceModelFiles {
        fun touch(name: String): Path = dir.resolve(name).also { Files.write(it, ByteArray(0)) }
        return VoiceModelFiles(dir, touch("encoder.int8.onnx"), touch("decoder.int8.onnx"), touch("joiner.int8.onnx"), touch("tokens.txt"))
    }

    private val okJson = "Started\nDone!\n\n/tmp/x/clip.wav\n" +
        "{\"lang\": \"\", \"emotion\": \"\", \"event\": \"\", \"text\": \" play bad guy\", \"timestamps\": [], \"tokens\": []}\n" +
        "----\nnum threads: 2\nElapsed seconds: 0.4 s\n"

    private val pcm = ShortArray(1600) { 1000 }

    // ---- WAV ----

    @Test
    fun wavHeaderIsCorrect() {
        val wav = VoiceRecognizerSupport.wavBytes(shortArrayOf(1, -2, 3))
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(36 + 6, int(wav, 4))
        assertEquals(16, int(wav, 16))
        assertEquals(16_000, int(wav, 24))
        assertEquals(32_000, int(wav, 28))
        assertEquals(6, int(wav, 40))
        assertEquals(1, wav[44].toInt())
        assertEquals(0xFE, wav[46].toInt() and 0xff) // -2 little endian
        assertEquals(0xFF, wav[47].toInt() and 0xff)
    }

    private fun int(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    // ---- output parsing ----

    @Test
    fun parsesJsonLineAndTrims() {
        assertEquals("play bad guy", VoiceRecognizerSupport.parseSherpaOutput(okJson))
    }

    @Test
    fun parsesOlderFormatWithTextAfterTheWavLine() {
        val out = "Creating recognizer\n/tmp/a/clip.wav\nplay next song\n----\nnum threads: 1\n"
        assertEquals("play next song", VoiceRecognizerSupport.parseSherpaOutput(out))
    }

    @Test
    fun emptyRecognitionIsEmptyText() {
        assertEquals("", VoiceRecognizerSupport.parseSherpaOutput("{\"text\": \"\", \"tokens\": []}"))
        assertEquals("", VoiceRecognizerSupport.parseSherpaOutput("clip.wav\n----\n"))
    }

    @Test
    fun noResultLineIsNull() {
        assertNull(VoiceRecognizerSupport.parseSherpaOutput(""))
        assertNull(VoiceRecognizerSupport.parseSherpaOutput("Started\nsome log line\nElapsed seconds: 1 s\n"))
    }

    @Test
    fun brokenJsonFallsBackToWavLineRule() {
        val out = "clip.wav\n{\"text\": broken\n"
        assertEquals("", VoiceRecognizerSupport.parseSherpaOutput(out))
    }

    // ---- command, RAM, threads ----

    @Test
    fun commandHasAllFlagsAndModelTypeIsOptional() {
        withTemp { root ->
            val model = fakeModel(root)
            val exe = root.resolve("sherpa-onnx-offline.exe")
            val wav = root.resolve("clip.wav")
            val with = VoiceRecognizerSupport.buildCommand(exe, model, wav, 3, true)
            assertTrue("--model-type=nemo_transducer" in with)
            assertTrue("--num-threads=3" in with)
            assertTrue(with.any { it.startsWith("--encoder=") && it.endsWith("encoder.int8.onnx") })
            assertTrue(with.any { it.startsWith("--tokens=") && it.endsWith("tokens.txt") })
            assertEquals(wav.toAbsolutePath().toString(), with.last())
            val without = VoiceRecognizerSupport.buildCommand(exe, model, wav, 3, false)
            assertFalse(without.any { it.startsWith("--model-type") })
        }
    }

    @Test
    fun ramGuardDecision() {
        assertTrue(VoiceRecognizerSupport.enoughMemory(null))
        assertFalse(VoiceRecognizerSupport.enoughMemory(1_000_000_000L))
        assertTrue(VoiceRecognizerSupport.enoughMemory(VoiceRecognizerSupport.MIN_FREE_BYTES))
        assertTrue(VoiceRecognizerSupport.enoughMemory(8_000_000_000L))
    }

    @Test
    fun threadCountIsBetweenOneAndFour() {
        assertEquals(1, VoiceRecognizerSupport.threadCount(0))
        assertEquals(2, VoiceRecognizerSupport.threadCount(2))
        assertEquals(4, VoiceRecognizerSupport.threadCount(16))
    }

    // ---- cleanup ----

    @Test
    fun wipeRemovesFilesAndFolder() {
        val dir = Files.createTempDirectory("muses-voice-wipe")
        Files.write(dir.resolve("clip.wav"), ByteArray(70_000) { 7 })
        Files.write(dir.resolve("other.txt"), "x".toByteArray())
        assertTrue(VoiceRecognizerSupport.wipeTempDir(dir))
        assertFalse(Files.exists(dir))
        assertTrue(VoiceRecognizerSupport.wipeTempDir(dir)) // already gone
    }

    // ---- recognizer with a fake program ----

    @Test
    fun transcribeReturnsTextAndLeavesNothingBehind() {
        withTemp { root ->
            val model = fakeModel(root)
            val exe = root.resolve("sherpa-onnx-offline.exe").also { Files.write(it, ByteArray(0)) }
            val temp = root.resolve("tmp")
            var wavExistedDuringRun = false
            var commandSeen: List<String> = emptyList()
            val runner = VoiceProcessRunner { command, _, _ ->
                commandSeen = command
                wavExistedDuringRun = Files.isRegularFile(Path.of(command.last()))
                VoiceProcessResult(0, okJson, "", false)
            }
            val recognizer = SherpaSidecarRecognizer(exe, model, { 4_000_000_000L }, runner, temp)
            val result = runBlocking { recognizer.transcribe(pcm) }
            assertEquals("play bad guy", result.getOrNull())
            assertTrue(wavExistedDuringRun)
            assertTrue("--model-type=nemo_transducer" in commandSeen)
            Files.newDirectoryStream(temp).use { assertFalse(it.iterator().hasNext()) }
        }
    }

    @Test
    fun lowMemoryRefusesWithoutRunningTheProgram() {
        withTemp { root ->
            val model = fakeModel(root)
            val exe = root.resolve("sherpa-onnx-offline.exe").also { Files.write(it, ByteArray(0)) }
            var ran = false
            val runner = VoiceProcessRunner { _, _, _ -> ran = true; VoiceProcessResult(0, okJson, "", false) }
            val result = runBlocking { SherpaSidecarRecognizer(exe, model, { 500_000_000L }, runner).transcribe(pcm) }
            assertEquals(VoiceErrors.LOW_MEMORY, (result.exceptionOrNull() as VoiceException).code)
            assertFalse(ran)
        }
    }

    @Test
    fun timeoutIsReportedAndCleanedUp() {
        withTemp { root ->
            val model = fakeModel(root)
            val exe = root.resolve("sherpa-onnx-offline.exe").also { Files.write(it, ByteArray(0)) }
            val temp = root.resolve("tmp")
            val runner = VoiceProcessRunner { _, _, _ -> VoiceProcessResult(null, "", "", true) }
            val result = runBlocking { SherpaSidecarRecognizer(exe, model, { 4_000_000_000L }, runner, temp).transcribe(pcm) }
            assertEquals(VoiceErrors.TIMEOUT, (result.exceptionOrNull() as VoiceException).code)
            Files.newDirectoryStream(temp).use { assertFalse(it.iterator().hasNext()) }
        }
    }

    @Test
    fun modelTypeFlagIsDroppedWhenTheProgramRejectsIt() {
        withTemp { root ->
            val model = fakeModel(root)
            val exe = root.resolve("sherpa-onnx-offline.exe").also { Files.write(it, ByteArray(0)) }
            val seen = ArrayList<List<String>>()
            val runner = VoiceProcessRunner { command, _, _ ->
                seen.add(command)
                if (command.any { it.startsWith("--model-type") }) {
                    VoiceProcessResult(1, "", "unknown option", false)
                } else {
                    VoiceProcessResult(0, okJson, "", false)
                }
            }
            try {
                val result = runBlocking {
                    SherpaSidecarRecognizer(exe, model, { 4_000_000_000L }, runner).transcribe(pcm)
                }
                assertEquals("play bad guy", result.getOrNull())
                assertEquals(2, seen.size)
                assertFalse(seen[1].any { it.startsWith("--model-type") })
            } finally {
                SherpaSidecarRecognizer.useModelType = true
            }
        }
    }

    @Test
    fun missingProgramAndEmptyAudioAreReported() {
        withTemp { root ->
            val model = fakeModel(root)
            val missingExe = root.resolve("nope.exe")
            val runner = VoiceProcessRunner { _, _, _ -> VoiceProcessResult(0, okJson, "", false) }
            val a = runBlocking { SherpaSidecarRecognizer(missingExe, model, { 4_000_000_000L }, runner).transcribe(pcm) }
            assertEquals(VoiceErrors.PROGRAM_MISSING, (a.exceptionOrNull() as VoiceException).code)
            val b = runBlocking { SherpaSidecarRecognizer(missingExe, model, { 4_000_000_000L }, runner).transcribe(ShortArray(0)) }
            assertEquals(VoiceErrors.NO_AUDIO, (b.exceptionOrNull() as VoiceException).code)
        }
    }
}
