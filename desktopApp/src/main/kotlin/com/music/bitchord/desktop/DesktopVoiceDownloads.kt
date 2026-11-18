package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/*
 * Voice commands, part V2: finding and downloading the recognizer program (sherpa-onnx-offline) and
 * the Parakeet model. Nothing here runs unless the owner starts it. Nothing about audio or recognised
 * text is handled here. Downloads are never bundled in the installer.
 */

/** Everything about the recognizer program download in ONE place. */
// VOICE V5 hook: release asset/version verified against the official Windows x64 download table.
internal object DesktopVoiceSidecarSpec {

    /** Verified against the official sherpa-onnx Windows x64 download table on 2026-11-18. */
    const val VERIFIED = true
    const val VERSION = "2.0.1"
    const val RELEASES_PAGE = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1"
    const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    /** Prefer the multithreaded static-runtime shared build without TTS; fall back to the MD build. */
    val ASSET_SUFFIXES: List<String> = listOf(
        "win-x64-shared-MT-Release-no-tts.tar.bz2",
        "win-x64-shared-MD-Release-no-tts.tar.bz2",
        "win-x64-shared-MT-Release.tar.bz2",
        "win-x64-shared-MD-Release.tar.bz2",
    )

    const val EXE_NAME = "sherpa-onnx-offline.exe"
    const val EXE_NAME_POSIX = "sherpa-onnx-offline"

    /** Smallest believable archive; official shared Windows archives are substantially larger. */
    const val MIN_ARCHIVE_BYTES = 1_000_000L

    fun candidateUrls(version: String = VERSION, base: String = RELEASE_BASE): List<String> =
        ASSET_SUFFIXES.map { "$base/v$version/sherpa-onnx-v$version-$it" }
}

/** Where the voice files live. Pure: environment and platform are parameters. */
internal object DesktopVoicePaths {

    fun dataDir(
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
        windows: Boolean = DesktopPlatform.isWindows,
    ): Path {
        val root: Path = if (windows) {
            env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Paths::get)
                ?: Paths.get(home, "AppData", "Local")
        } else {
            env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Paths::get)
                ?: Paths.get(home, ".local", "share")
        }
        return root.resolve(if (windows) "Muses" else "muses").resolve("voice")
    }

    fun programDir(base: Path = dataDir()): Path = base.resolve("program")

    /** Where "Download" puts the model by default (V4 may let the owner pick another folder). */
    fun modelDir(base: Path = dataDir()): Path = base.resolve("model")
}

/** Progress maths and download verification, pure. */
internal object VoiceDownloadMath {

    /** [done] of [total] as 0..1; unknown or zero totals give 0. */
    fun fraction(done: Long, total: Long): Float {
        if (total <= 0L || done <= 0L) return 0f
        return (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** Overall progress over several files: [completedBytes] already finished plus [currentBytes] of the running one. */
    fun overall(completedBytes: Long, currentBytes: Long, totalBytes: Long): Float =
        fraction(completedBytes + currentBytes, totalBytes)

    /** Maps a 0..1 [part] into the slice [from]..[to] of a larger operation. */
    fun slice(part: Float, from: Float, to: Float): Float = from + (to - from) * part.coerceIn(0f, 1f)

    /**
     * Checks a finished file against [spec]. Returns null when it is fine, otherwise an error code
     * ("bad_size" or "bad_hash"). [hash] is a parameter so tests need no big file.
     */
    fun verify(
        file: Path,
        spec: VoiceModelFileSpec,
        sizeTolerance: Double = 0.0,
        hash: (Path) -> String = DesktopVoiceModelSpec::sha256Hex,
    ): String? {
        val size = try {
            Files.size(file)
        } catch (e: Exception) {
            return "bad_size"
        }
        if (!DesktopVoiceModelSpec.sizeMatches(size, spec.size, sizeTolerance)) return "bad_size"
        val actual = try {
            hash(file)
        } catch (e: Exception) {
            return "bad_hash"
        }
        return if (actual.equals(spec.sha256, ignoreCase = true)) null else "bad_hash"
    }
}

/** Shared HTTP streaming used by both downloads. */
internal object VoiceHttp {

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Streams [url] into [target] (overwritten). [onBytes] gets the bytes written so far. Throws
     * [VoiceException] with "http_<status>" or "network" on failure and rethrows cancellation. The
     * partial file is left for the caller to delete.
     */
    suspend fun downloadTo(url: String, target: Path, onBytes: (Long) -> Unit) {
        val response: HttpResponse<InputStream> = try {
            client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Muses-Desktop")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VoiceException("network", e.javaClass.simpleName)
        }
        response.body().use { input ->
            if (response.statusCode() !in 200..299) throw VoiceException("http_${response.statusCode()}")
            try {
                Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        currentCoroutineContextEnsureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onBytes(written)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw VoiceException("network", e.javaClass.simpleName)
            }
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }
}

internal object DesktopVoiceSidecar {

    private val EXE_NAMES = listOf(DesktopVoiceSidecarSpec.EXE_NAME, DesktopVoiceSidecarSpec.EXE_NAME_POSIX)

    /**
     * Looks for the program in this order: the owner-chosen path ([chosen]: the file itself or a folder
     * holding it, set in Settings by V4), [appDirs] (next to the app), the app data `voice` folder
     * (searched a few levels deep), then every folder on PATH. Returns the first match, or null.
     */
    fun findExisting(
        chosen: String? = null,
        appDirs: List<Path> = defaultAppDirs(),
        dataDir: Path = DesktopVoicePaths.dataDir(),
        pathVariable: String? = System.getenv("PATH"),
        pathSeparator: String = java.io.File.pathSeparator,
    ): Path? {
        val chosenPath = chosen?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() }
        if (chosenPath != null) {
            if (Files.isRegularFile(chosenPath)) return chosenPath
            if (Files.isDirectory(chosenPath)) findInDir(chosenPath, 3)?.let { return it }
        }
        for (dir in appDirs) findInDir(dir, 1)?.let { return it }
        if (Files.isDirectory(dataDir)) findInDir(dataDir, 5)?.let { return it }
        if (!pathVariable.isNullOrBlank()) {
            for (entry in pathVariable.split(pathSeparator)) {
                if (entry.isBlank()) continue
                val dir = runCatching { Paths.get(entry.trim().trim('"')) }.getOrNull() ?: continue
                findInDir(dir, 1)?.let { return it }
            }
        }
        return null
    }

    /** First regular file named like the program in [dir], looking [maxDepth] levels down (links are not followed). */
    fun findInDir(dir: Path, maxDepth: Int): Path? = try {
        if (!Files.isDirectory(dir)) {
            null
        } else {
            Files.walk(dir, maxDepth).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase() in EXE_NAMES }
                    .findFirst()
                    .orElse(null)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun defaultAppDirs(): List<Path> {
        val out = ArrayList<Path>()
        runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let { Paths.get(it).parent?.let(out::add) }
        }
        runCatching { System.getProperty("user.dir")?.let { out.add(Paths.get(it)) } }
        return out
    }

    /**
     * Downloads the Windows archive, extracts it with the system `tar.exe` into the app data folder and
     * returns the path of `sherpa-onnx-offline.exe`. The archive is deleted afterwards. Windows only.
     * Progress: 0..0.85 download, 0.85..1 extraction.
     */
    suspend fun download(onProgress: (Float) -> Unit): Result<Path> = withContext(Dispatchers.IO) {
        if (!DesktopPlatform.isWindows) {
            return@withContext Result.failure(VoiceException("unsupported_platform"))
        }
        val dataDir = DesktopVoicePaths.dataDir()
        val programDir = DesktopVoicePaths.programDir(dataDir)
        val archive = dataDir.resolve("program.tar.bz2.part")
        var extractedOk = false
        try {
            Files.createDirectories(dataDir)
            DesktopTrackLog.log("voice: program download start")
            var lastError: VoiceException? = null
            var downloaded = false
            for (url in DesktopVoiceSidecarSpec.candidateUrls()) {
                try {
                    VoiceHttp.downloadTo(url, archive) { bytes ->
                        // Size unknown up front: show a slow curve that never reaches the end by itself.
                        val shown = VoiceDownloadMath.fraction(bytes, 20L * 1024 * 1024)
                        onProgress(VoiceDownloadMath.slice(shown, 0f, 0.8f))
                    }
                    if (Files.size(archive) < DesktopVoiceSidecarSpec.MIN_ARCHIVE_BYTES) {
                        throw VoiceException("bad_size")
                    }
                    downloaded = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VoiceException) {
                    lastError = e
                    runCatching { Files.deleteIfExists(archive) }
                }
            }
            if (!downloaded) {
                return@withContext Result.failure(lastError ?: VoiceException("network"))
            }
            onProgress(0.85f)
            Files.createDirectories(programDir)
            val tar = systemTar()
            val process = ProcessBuilder(tar, "-xjf", archive.toAbsolutePath().toString(), "-C", programDir.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            try {
                if (!process.waitFor(180, TimeUnit.SECONDS)) {
                    return@withContext Result.failure(VoiceException("extract_timeout"))
                }
            } finally {
                runCatching { process.destroyForcibly() }
            }
            if (process.exitValue() != 0) return@withContext Result.failure(VoiceException("extract_failed"))
            val exe = findInDir(programDir, 6)
                ?: return@withContext Result.failure(VoiceException("program_not_in_archive"))
            extractedOk = true
            onProgress(1f)
            DesktopTrackLog.log("voice: program download done")
            Result.success(exe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(VoiceException("download_failed", e.javaClass.simpleName))
        } finally {
            runCatching { Files.deleteIfExists(archive) }
            if (!extractedOk) runCatching { deleteTree(programDir) }
        }
    }

    private fun systemTar(): String {
        val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
        if (root != null) {
            val candidate = Paths.get(root, "System32", "tar.exe")
            if (Files.isRegularFile(candidate)) return candidate.toString()
        }
        return "tar"
    }

    /** Deletes a folder and everything in it. Never throws. */
    internal fun deleteTree(dir: Path) {
        runCatching {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder<Path>()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}

internal object DesktopVoiceModelDownloader {

    /**
     * Downloads the four model files into [targetDir]. Each file goes to `<name>.part`, then size and
     * SHA-256 are verified and the file is renamed. A file that is already complete and verified is
     * kept. When a file fails on one source the next source is tried; partials are deleted on failure
     * and on cancel. Not resumable (the plan does not require it).
     */
    suspend fun download(targetDir: Path, onProgress: (Float) -> Unit): Result<VoiceModelFiles> =
        withContext(Dispatchers.IO) {
            val specs = DesktopVoiceModelSpec.files
            val total = specs.sumOf { it.size }
            var completed = 0L
            try {
                Files.createDirectories(targetDir)
                DesktopTrackLog.log("voice: model download start")
                onProgress(0f)
                for (spec in specs) {
                    val finalPath = targetDir.resolve(spec.name)
                    if (Files.isRegularFile(finalPath) && VoiceDownloadMath.verify(finalPath, spec) == null) {
                        completed += spec.size
                        onProgress(VoiceDownloadMath.overall(completed, 0L, total))
                        continue
                    }
                    var lastError: VoiceException? = null
                    var done = false
                    for (base in DesktopVoiceModelSpec.sources) {
                        val part = targetDir.resolve(spec.name + ".part")
                        try {
                            val url = DesktopVoiceModelSpec.urlFor(base, spec.name)
                            VoiceHttp.downloadTo(url, part) { bytes ->
                                onProgress(VoiceDownloadMath.overall(completed, bytes, total))
                            }
                            val problem = VoiceDownloadMath.verify(part, spec)
                            if (problem != null) throw VoiceException(problem)
                            moveInto(part, finalPath)
                            done = true
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: VoiceException) {
                            lastError = e
                        } finally {
                            runCatching { Files.deleteIfExists(part) }
                        }
                    }
                    if (!done) return@withContext Result.failure(lastError ?: VoiceException("download_failed"))
                    completed += spec.size
                    onProgress(VoiceDownloadMath.overall(completed, 0L, total))
                }
                when (val scan = DesktopVoiceModelLocator.check(targetDir)) {
                    is VoiceModelScan.Ready -> {
                        DesktopTrackLog.log("voice: model download done")
                        onProgress(1f)
                        Result.success(scan.files)
                    }
                    else -> Result.failure(VoiceException("model_not_ready"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(VoiceException("download_failed", e.javaClass.simpleName))
            } finally {
                for (spec in specs) runCatching { Files.deleteIfExists(targetDir.resolve(spec.name + ".part")) }
            }
        }

    private fun moveInto(part: Path, finalPath: Path) {
        try {
            Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
