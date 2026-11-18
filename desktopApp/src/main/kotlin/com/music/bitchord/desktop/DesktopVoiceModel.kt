package com.music.bitchord.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/*
 * Voice commands, part V1: finding the Parakeet model on disk. Pure file-system logic, no JNA, no
 * Windows calls, nothing is loaded or run here. The model is the sherpa-onnx int8 export of NVIDIA
 * Parakeet Unified EN 0.6B (four files). Another export layout (onnx-asr style) exists and does NOT
 * work with the sherpa runner; the locator reports it as WrongFormat so the UI can say so plainly.
 */

/** The four files of a usable model, resolved to real paths (names as they appear on disk). */
internal data class VoiceModelFiles(
    val dir: Path,
    val encoder: Path,
    val decoder: Path,
    val joiner: Path,
    val tokens: Path,
)

/** Result of looking at one folder. */
internal sealed interface VoiceModelScan {
    data class Ready(val files: VoiceModelFiles) : VoiceModelScan
    data class WrongFormat(val dir: Path, val detail: String) : VoiceModelScan
    object NotFound : VoiceModelScan
}

/** One file of the model: expected name, size in bytes and SHA-256 (lower-case hex). */
internal data class VoiceModelFileSpec(val name: String, val size: Long, val sha256: String)

internal object DesktopVoiceModelSpec {

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    /** Default size tolerance used by the locator (1 percent). */
    const val SIZE_TOLERANCE = 0.01

    /** Approximate download size shown in the UI. */
    const val APPROX_DOWNLOAD_MB = 660

    const val LICENCE_NAME = "NVIDIA Open Model License"

    /** Name, size and SHA-256 as published with the sherpa-onnx export (see MUSES_VOICE_PLAN.md). */
    val files: List<VoiceModelFileSpec> = listOf(
        VoiceModelFileSpec(
            ENCODER, 654_040_552L,
            "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0",
        ),
        VoiceModelFileSpec(
            DECODER, 7_257_753L,
            "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6",
        ),
        VoiceModelFileSpec(
            JOINER, 1_735_860L,
            "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a",
        ),
        VoiceModelFileSpec(
            TOKENS, 8_952L,
            "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff",
        ),
    )

    /** Base URLs, tried in order. Each ends with "/"; append the file name with [urlFor]. */
    val sources: List<String> = listOf(
        "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/resolve/main/",
        "https://huggingface.co/heast13/kyrr-models/resolve/main/en-unified/",
    )

    /** Page shown next to the download button (licence notice link). */
    const val LICENCE_URL = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"

    fun urlFor(base: String, fileName: String): String =
        (if (base.endsWith("/")) base else "$base/") + fileName

    /** True when [actual] is within [tolerance] (a fraction, 0.01 = 1 percent) of [expected]. */
    fun sizeMatches(actual: Long, expected: Long, tolerance: Double): Boolean {
        if (actual == expected) return true
        val allowed = expected.toDouble() * tolerance
        return kotlin.math.abs((actual - expected).toDouble()) <= allowed
    }

    /** SHA-256 of a file as lower-case hex, streamed in 1 MB chunks (a 650 MB file never sits in memory). */
    fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        Files.newInputStream(path).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val out = StringBuilder(64)
        for (b in digest.digest()) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

private val SHERPA_NAMES: Set<String> =
    DesktopVoiceModelSpec.files.map { it.name.lowercase() }.toSet()

/** True when [names] (any case) contain all four sherpa-onnx file names. */
internal fun looksLikeSherpaLayout(names: Set<String>, required: Set<String> = SHERPA_NAMES): Boolean {
    val lower = names.map { it.lowercase() }.toSet()
    return required.isNotEmpty() && lower.containsAll(required)
}

/**
 * True for the onnx-asr style export: a joint decoder (`decoder_joint*.onnx`), or an encoder with a
 * `vocab.txt` / `tokenizer.model`. That layout cannot be run by the sherpa program.
 */
internal fun looksLikeOnnxAsrLayout(names: Set<String>): Boolean {
    val lower = names.map { it.lowercase() }.toSet()
    if (lower.any { it.startsWith("decoder_joint") && it.endsWith(".onnx") }) return true
    val hasEncoder = lower.any { it.startsWith("encoder") && it.endsWith(".onnx") }
    val hasVocab = "vocab.txt" in lower || "tokenizer.model" in lower
    return hasEncoder && hasVocab
}

internal object DesktopVoiceModelLocator {

    /** Directories visited per root before the walk gives up. */
    const val MAX_DIRS_PER_ROOT = 200

    private val PREFERRED_HINTS = listOf("handsy", "parakeet", "sherpa")

    /**
     * Looks at one folder. [sizeTolerance] is a fraction of the expected size; tests pass
     * `Double.POSITIVE_INFINITY` so empty files of the right names count. Never throws.
     */
    fun check(
        dir: Path,
        spec: List<VoiceModelFileSpec> = DesktopVoiceModelSpec.files,
        sizeTolerance: Double = DesktopVoiceModelSpec.SIZE_TOLERANCE,
    ): VoiceModelScan {
        val listing = listFiles(dir) ?: return VoiceModelScan.NotFound
        if (listing.isEmpty()) return VoiceModelScan.NotFound
        val names = listing.keys
        val required = spec.map { it.name.lowercase() }.toSet()

        if (looksLikeSherpaLayout(names, required)) {
            val bad = ArrayList<String>()
            for (s in spec) {
                val path = listing[s.name.lowercase()] ?: continue
                val actual = try {
                    Files.size(path)
                } catch (e: IOException) {
                    -1L
                }
                if (actual < 0 || !DesktopVoiceModelSpec.sizeMatches(actual, s.size, sizeTolerance)) bad += s.name
            }
            if (bad.isNotEmpty()) {
                return VoiceModelScan.WrongFormat(
                    dir,
                    "The model files are there but the size of " + bad.joinToString(", ") +
                        " does not match (incomplete download?).",
                )
            }
            fun pick(name: String): Path = listing.getValue(name.lowercase())
            return VoiceModelScan.Ready(
                VoiceModelFiles(
                    dir = dir,
                    encoder = pick(DesktopVoiceModelSpec.ENCODER),
                    decoder = pick(DesktopVoiceModelSpec.DECODER),
                    joiner = pick(DesktopVoiceModelSpec.JOINER),
                    tokens = pick(DesktopVoiceModelSpec.TOKENS),
                ),
            )
        }

        if (looksLikeOnnxAsrLayout(names)) {
            return VoiceModelScan.WrongFormat(
                dir,
                "It is a model in a different format (onnx-asr layout). Muses needs the sherpa-onnx version.",
            )
        }

        val hasEncoderOnnx = names.any { it.endsWith(".onnx") && it.contains("encoder") }
        if (hasEncoderOnnx) {
            val missing = required.filter { it !in names }
            return if (missing.size < required.size) {
                VoiceModelScan.WrongFormat(dir, "Some model files are missing: " + missing.joinToString(", ") + ".")
            } else {
                VoiceModelScan.WrongFormat(
                    dir,
                    "It is a model with different file names. Muses needs the sherpa-onnx int8 version.",
                )
            }
        }
        return VoiceModelScan.NotFound
    }

    /**
     * Walks [roots] breadth first (folders whose name hints at the model first) down to [maxDepth],
     * never leaving a root through a link, skipping unreadable folders, at most
     * [MAX_DIRS_PER_ROOT] folders per root. Results with a path containing "handsy", "parakeet" or
     * "sherpa" come first, then complete models before wrong-format ones. Run it off the UI thread.
     */
    fun findExisting(
        roots: List<Path> = defaultRoots(),
        maxDepth: Int = 5,
        spec: List<VoiceModelFileSpec> = DesktopVoiceModelSpec.files,
        sizeTolerance: Double = DesktopVoiceModelSpec.SIZE_TOLERANCE,
    ): List<VoiceModelScan> {
        val seen = HashSet<String>()
        val found = ArrayList<Pair<Path, VoiceModelScan>>()
        for (root in roots.distinct()) {
            val rootReal = try {
                if (!Files.isDirectory(root)) continue
                root.toRealPath()
            } catch (e: Exception) {
                continue
            }
            val queue = ArrayDeque<Pair<Path, Int>>()
            queue.addLast(root to 0)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_DIRS_PER_ROOT) {
                val (dir, depth) = queue.removeFirst()
                visited++
                val scan = check(dir, spec, sizeTolerance)
                if (scan !is VoiceModelScan.NotFound) {
                    val key = dir.toAbsolutePath().normalize().toString().lowercase()
                    if (seen.add(key)) found += dir to scan
                    continue
                }
                if (depth >= maxDepth) continue
                val kids = subDirs(dir, rootReal)
                val hinted = kids.filter { hasHint(it.fileName.toString()) }
                val others = kids.filter { !hasHint(it.fileName.toString()) }
                for (k in hinted.asReversed()) queue.addFirst(k to depth + 1)
                for (k in others) queue.addLast(k to depth + 1)
            }
        }
        return found
            .sortedWith(
                compareBy<Pair<Path, VoiceModelScan>>(
                    { if (hasHint(it.first.toString())) 0 else 1 },
                    { if (it.second is VoiceModelScan.Ready) 0 else 1 },
                ),
            )
            .map { it.second }
    }

    /**
     * Where an existing copy is likely to be. Windows: %APPDATA%, %LOCALAPPDATA%, and under
     * %USERPROFILE% `.cache`, Documents, Downloads, Desktop, plus %PROGRAMDATA%. Elsewhere: the home
     * `.cache` and `.local/share`. Existing or not; [findExisting] skips missing ones.
     */
    fun defaultRoots(
        env: (String) -> String? = { System.getenv(it) },
        home: String? = System.getProperty("user.home"),
        windows: Boolean = DesktopPlatform.isWindows,
    ): List<Path> {
        val out = ArrayList<Path>()
        fun add(base: String?, vararg more: String) {
            if (base.isNullOrBlank()) return
            try {
                out += Paths.get(base, *more)
            } catch (e: Exception) {
                // An unusable path simply is not a root.
            }
        }
        if (windows) {
            add(env("APPDATA"))
            add(env("LOCALAPPDATA"))
            val profile = env("USERPROFILE") ?: home
            for (sub in listOf(".cache", "Documents", "Downloads", "Desktop")) add(profile, sub)
            add(env("PROGRAMDATA"))
        } else {
            add(home, ".cache")
            add(home, ".local", "share")
        }
        return out.distinct()
    }

    private fun hasHint(text: String): Boolean {
        val lower = text.lowercase()
        return PREFERRED_HINTS.any { lower.contains(it) }
    }

    /** Regular files of [dir] by lower-case name, or null when it is not a readable folder. */
    private fun listFiles(dir: Path): Map<String, Path>? = try {
        if (!Files.isDirectory(dir)) {
            null
        } else {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.associateBy { it.fileName.toString().lowercase() }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Child folders of [dir] that are real folders (no links) and still under [rootReal]; sorted by name. */
    private fun subDirs(dir: Path, rootReal: Path): List<Path> = try {
        Files.newDirectoryStream(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .filter {
                    try {
                        it.toRealPath().startsWith(rootReal)
                    } catch (e: Exception) {
                        false
                    }
                }
                .sortedBy { it.fileName.toString().lowercase() }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
