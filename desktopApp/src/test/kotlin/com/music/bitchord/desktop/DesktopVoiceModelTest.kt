package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Voice V1: the model finder. Only empty or tiny temp files are created; no network, no model. */
class DesktopVoiceModelTest {

    private val sherpaNames = listOf(
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
    )

    /** Tests do not enforce sizes: empty files of the right names count. */
    private val anySize = Double.POSITIVE_INFINITY

    private fun touch(dir: Path, vararg names: String) {
        Files.createDirectories(dir)
        for (n in names) Files.write(dir.resolve(n), ByteArray(0))
    }

    private fun withTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("muses-voice-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).forEach { Files.delete(it) } }
        }
    }

    // ---- layout detectors ----

    @Test
    fun sherpaLayoutNeedsAllFourNamesAnyCase() {
        assertTrue(looksLikeSherpaLayout(sherpaNames.toSet()))
        assertTrue(looksLikeSherpaLayout(sherpaNames.map { it.uppercase() }.toSet()))
        assertFalse(looksLikeSherpaLayout(sherpaNames.drop(1).toSet()))
        assertFalse(looksLikeSherpaLayout(emptySet()))
        assertTrue(looksLikeSherpaLayout(sherpaNames.toSet() + "readme.md"))
    }

    @Test
    fun onnxAsrLayoutIsRecognised() {
        assertTrue(looksLikeOnnxAsrLayout(setOf("encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")))
        assertTrue(looksLikeOnnxAsrLayout(setOf("decoder_joint.onnx")))
        assertTrue(looksLikeOnnxAsrLayout(setOf("encoder.onnx", "vocab.txt")))
        assertTrue(looksLikeOnnxAsrLayout(setOf("Encoder.onnx", "tokenizer.model")))
        assertFalse(looksLikeOnnxAsrLayout(sherpaNames.toSet()))
        assertFalse(looksLikeOnnxAsrLayout(setOf("encoder.onnx")))
        assertFalse(looksLikeOnnxAsrLayout(emptySet()))
    }

    // ---- check ----

    @Test
    fun checkReadyWithEmptyFilesWhenSizesAreNotEnforced() = withTemp { root ->
        touch(root, *sherpaNames.toTypedArray())
        val scan = DesktopVoiceModelLocator.check(root, sizeTolerance = anySize)
        val ready = scan as VoiceModelScan.Ready
        assertEquals(root, ready.files.dir)
        assertEquals("encoder.int8.onnx", ready.files.encoder.fileName.toString())
        assertEquals("decoder.int8.onnx", ready.files.decoder.fileName.toString())
        assertEquals("joiner.int8.onnx", ready.files.joiner.fileName.toString())
        assertEquals("tokens.txt", ready.files.tokens.fileName.toString())
    }

    @Test
    fun checkIsCaseInsensitiveAndKeepsRealNames() = withTemp { root ->
        touch(root, "ENCODER.INT8.ONNX", "Decoder.int8.onnx", "joiner.INT8.onnx", "Tokens.TXT")
        val ready = DesktopVoiceModelLocator.check(root, sizeTolerance = anySize) as VoiceModelScan.Ready
        assertTrue(Files.exists(ready.files.encoder))
        assertEquals("Tokens.TXT", ready.files.tokens.fileName.toString())
    }

    @Test
    fun emptyFilesFailTheRealSizeCheck() = withTemp { root ->
        touch(root, *sherpaNames.toTypedArray())
        val scan = DesktopVoiceModelLocator.check(root)
        val wrong = scan as VoiceModelScan.WrongFormat
        assertTrue(wrong.detail.contains("encoder.int8.onnx"))
    }

    @Test
    fun sizeToleranceIsAParameter() = withTemp { root ->
        val spec = listOf(
            VoiceModelFileSpec("encoder.int8.onnx", 100, "x"),
            VoiceModelFileSpec("decoder.int8.onnx", 100, "x"),
            VoiceModelFileSpec("joiner.int8.onnx", 100, "x"),
            VoiceModelFileSpec("tokens.txt", 100, "x"),
        )
        for (n in sherpaNames) Files.write(root.resolve(n), ByteArray(100))
        assertTrue(DesktopVoiceModelLocator.check(root, spec, 0.01) is VoiceModelScan.Ready)
        Files.write(root.resolve("tokens.txt"), ByteArray(150))
        assertTrue(DesktopVoiceModelLocator.check(root, spec, 0.01) is VoiceModelScan.WrongFormat)
        assertTrue(DesktopVoiceModelLocator.check(root, spec, 0.6) is VoiceModelScan.Ready)
        Files.write(root.resolve("tokens.txt"), ByteArray(101))
        assertTrue(DesktopVoiceModelLocator.check(root, spec, 0.02) is VoiceModelScan.Ready)
    }

    @Test
    fun checkReportsTheOnnxAsrLayoutAsWrongFormat() = withTemp { root ->
        touch(root, "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        val wrong = DesktopVoiceModelLocator.check(root, sizeTolerance = anySize) as VoiceModelScan.WrongFormat
        assertEquals(root, wrong.dir)
        assertTrue(wrong.detail.contains("different format"))
    }

    @Test
    fun checkReportsOtherNamesAndMissingFilesAsWrongFormat() = withTemp { root ->
        val other = root.resolve("other")
        touch(other, "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        assertTrue(DesktopVoiceModelLocator.check(other, sizeTolerance = anySize) is VoiceModelScan.WrongFormat)

        val partial = root.resolve("partial")
        touch(partial, "encoder.int8.onnx", "tokens.txt")
        val wrong = DesktopVoiceModelLocator.check(partial, sizeTolerance = anySize) as VoiceModelScan.WrongFormat
        assertTrue(wrong.detail.contains("missing"))
    }

    @Test
    fun checkReturnsNotFoundForEverythingElse() = withTemp { root ->
        assertEquals(VoiceModelScan.NotFound, DesktopVoiceModelLocator.check(root))
        touch(root, "notes.txt", "tokens.txt")
        assertEquals(VoiceModelScan.NotFound, DesktopVoiceModelLocator.check(root))
        assertEquals(VoiceModelScan.NotFound, DesktopVoiceModelLocator.check(root.resolve("missing")))
        assertEquals(VoiceModelScan.NotFound, DesktopVoiceModelLocator.check(root.resolve("notes.txt")))
    }

    // ---- findExisting ----

    @Test
    fun findExistingPrefersHintedFoldersThenReadyOnes() = withTemp { root ->
        touch(root.resolve("zz").resolve("plain"), *sherpaNames.toTypedArray())
        touch(root.resolve("aa").resolve("old"), "encoder.onnx", "decoder_joint.onnx", "vocab.txt")
        touch(root.resolve("apps").resolve("Handsy").resolve("models"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        val found = DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 5, sizeTolerance = anySize)
        assertEquals(3, found.size)
        // Hinted path first (it is a wrong-format copy), then the complete model, then the other wrong one.
        val first = found[0] as VoiceModelScan.WrongFormat
        assertTrue(first.dir.toString().lowercase().contains("handsy"))
        assertTrue(found[1] is VoiceModelScan.Ready)
        assertTrue(found[2] is VoiceModelScan.WrongFormat)
    }

    @Test
    fun findExistingReturnsAReadyModelFromTheHintedFolder() = withTemp { root ->
        touch(root.resolve("a").resolve("parakeet-copy"), *sherpaNames.toTypedArray())
        touch(root.resolve("b").resolve("models"), *sherpaNames.toTypedArray())
        val found = DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 5, sizeTolerance = anySize)
        assertEquals(2, found.size)
        val first = found[0] as VoiceModelScan.Ready
        assertTrue(first.files.dir.toString().contains("parakeet"))
    }

    @Test
    fun findExistingRespectsMaxDepth() = withTemp { root ->
        touch(root.resolve("a").resolve("b").resolve("c"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        assertEquals(0, DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 2).size)
        assertEquals(1, DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 3).size)
    }

    @Test
    fun findExistingIgnoresMissingRootsAndDuplicates() = withTemp { root ->
        touch(root.resolve("m"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        val found = DesktopVoiceModelLocator.findExisting(
            listOf(root.resolve("nope"), root, root, root.resolve("m")),
            maxDepth = 3,
        )
        assertEquals(1, found.size)
    }

    @Test
    fun findExistingStopsAfter200FoldersPerRoot() = withTemp { root ->
        for (i in 0 until 250) Files.createDirectories(root.resolve("d" + i.toString().padStart(3, '0')))
        touch(root.resolve("d249"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        assertEquals(0, DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 3).size)
        // The same model in an early folder is found.
        touch(root.resolve("d001"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        assertEquals(1, DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 3).size)
    }

    @Test
    fun findExistingDoesNotFollowLinksOutOfTheRoot() = withTemp { base ->
        val outside = base.resolve("outside")
        touch(outside.resolve("model"), "encoder-model.onnx", "decoder_joint-model.onnx", "vocab.txt")
        val root = base.resolve("root")
        Files.createDirectories(root)
        try {
            Files.createSymbolicLink(root.resolve("link"), outside)
        } catch (e: Exception) {
            return@withTemp // links not allowed here (for example Windows without the privilege)
        }
        assertEquals(0, DesktopVoiceModelLocator.findExisting(listOf(root), maxDepth = 5).size)
    }

    // ---- defaultRoots ----

    @Test
    fun defaultRootsForWindowsAndLinux() {
        val env = mapOf(
            "APPDATA" to "C:\\Users\\me\\AppData\\Roaming",
            "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
            "USERPROFILE" to "C:\\Users\\me",
            "PROGRAMDATA" to "C:\\ProgramData",
        )
        val win = DesktopVoiceModelLocator.defaultRoots(env = { env[it] }, home = "C:\\Users\\me", windows = true)
        assertEquals(7, win.size)
        assertTrue(win.any { it.toString().contains("ProgramData") })
        assertTrue(win.any { it.fileName.toString() == "Downloads" })
        assertTrue(win.any { it.fileName.toString() == ".cache" })

        val lin = DesktopVoiceModelLocator.defaultRoots(env = { null }, home = "/home/me", windows = false)
        assertEquals(2, lin.size)
        assertTrue(lin.any { it.fileName.toString() == ".cache" })
        assertTrue(lin.any { it.fileName.toString() == "share" })

        assertEquals(0, DesktopVoiceModelLocator.defaultRoots(env = { null }, home = null, windows = false).size)
    }

    // ---- spec, sizes, SHA-256 ----

    @Test
    fun specHoldsTheFourFilesAndTwoSources() {
        val files = DesktopVoiceModelSpec.files
        assertEquals(sherpaNames, files.map { it.name })
        assertEquals(654_040_552L, files[0].size)
        assertEquals(8_952L, files[3].size)
        assertTrue(files.all { it.sha256.length == 64 && it.sha256.all { c -> c in "0123456789abcdef" } })
        assertEquals(2, DesktopVoiceModelSpec.sources.size)
        assertTrue(DesktopVoiceModelSpec.sources.all { it.startsWith("https://") && it.endsWith("/") })
        assertEquals(
            "https://huggingface.co/heast13/kyrr-models/resolve/main/en-unified/tokens.txt",
            DesktopVoiceModelSpec.urlFor(DesktopVoiceModelSpec.sources[1], "tokens.txt"),
        )
        assertEquals("https://x/y/a.bin", DesktopVoiceModelSpec.urlFor("https://x/y", "a.bin"))
    }

    @Test
    fun sizeMatchesUsesAFraction() {
        assertTrue(DesktopVoiceModelSpec.sizeMatches(1000, 1000, 0.0))
        assertTrue(DesktopVoiceModelSpec.sizeMatches(1010, 1000, 0.01))
        assertFalse(DesktopVoiceModelSpec.sizeMatches(1011, 1000, 0.01))
        assertTrue(DesktopVoiceModelSpec.sizeMatches(990, 1000, 0.01))
        assertFalse(DesktopVoiceModelSpec.sizeMatches(0, 1000, 0.01))
        assertTrue(DesktopVoiceModelSpec.sizeMatches(0, 1000, Double.POSITIVE_INFINITY))
    }

    @Test
    fun sha256OfSmallFiles() = withTemp { root ->
        val abc = root.resolve("abc.bin")
        Files.write(abc, "abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DesktopVoiceModelSpec.sha256Hex(abc),
        )
        val empty = root.resolve("empty.bin")
        Files.write(empty, ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DesktopVoiceModelSpec.sha256Hex(empty),
        )
        assertNotNull(DesktopVoiceModelSpec.sha256Hex(abc))
    }
}
