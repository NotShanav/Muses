package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * Voice commands, part V2: offline recognition through the sherpa-onnx command-line program. The audio
 * goes to ONE temporary WAV in a private folder that is zero-filled and deleted in `finally`. The
 * recognised text is returned to the caller and is never logged or stored here. Logs carry only
 * "start", "finish", the duration and an error CODE.
 */

/** Anything that can turn 16 kHz mono PCM into text. */
internal interface VoiceRecognizer {
    suspend fun transcribe(pcm16kMono: ShortArray): Result<String>
}

/** Pure helpers: WAV bytes, stdout parsing, command line, RAM decision, temp cleanup. */
internal object VoiceRecognizerSupport {

    /** Models need about 1 GB at peak; refuse below this much free physical memory (1.8 GiB). */
    const val MIN_FREE_BYTES: Long = 1_932_735_283L

    const val TIMEOUT_MILLIS: Long = 25_000L

    const val MAX_THREADS = 4

    const val MODEL_TYPE = "nemo_transducer"

    /** A 44-byte PCM WAV header followed by the samples, little-endian, mono, 16 bit. */
    fun wavBytes(pcm: ShortArray, sampleRate: Int = VoiceAudio.SAMPLE_RATE): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(44 + dataBytes)
        fun putInt(at: Int, v: Int) {
            out[at] = (v and 0xff).toByte()
            out[at + 1] = ((v ushr 8) and 0xff).toByte()
            out[at + 2] = ((v ushr 16) and 0xff).toByte()
            out[at + 3] = ((v ushr 24) and 0xff).toByte()
        }
        fun putShort(at: Int, v: Int) {
            out[at] = (v and 0xff).toByte()
            out[at + 1] = ((v ushr 8) and 0xff).toByte()
        }
        fun putTag(at: Int, tag: String) {
            for (i in tag.indices) out[at + i] = tag[i].code.toByte()
        }
        putTag(0, "RIFF")
        putInt(4, 36 + dataBytes)
        putTag(8, "WAVE")
        putTag(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2) // block align
        putShort(34, 16) // bits per sample
        putTag(36, "data")
        putInt(40, dataBytes)
        for (i in pcm.indices) putShort(44 + 2 * i, pcm[i].toInt())
        return out
    }

    /**
     * Finds the recognised text in the program's stdout. Tolerates log lines. Newer builds print a JSON
     * line (`{"text": " hello", ...}`); older ones print the wav path and then the text on its own line.
     * Returns null when no result line is found, "" when the program recognised nothing.
     */
    fun parseSherpaOutput(stdout: String): String? {
        val lines = stdout.lines().map { it.trim() }
        for (line in lines) {
            if (line.startsWith("{") && line.contains("\"text\"")) {
                val text = try {
                    Json.parseToJsonElement(line).jsonObject["text"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
                if (text != null) return text.trim()
            }
        }
        for (i in lines.indices) {
            if (lines[i].endsWith(".wav", ignoreCase = true)) {
                var j = i + 1
                while (j < lines.size && lines[j].isEmpty()) j++
                if (j >= lines.size) return ""
                val next = lines[j]
                if (next.startsWith("----") || next.startsWith("{")) return ""
                return next
            }
        }
        return null
    }

    fun threadCount(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        cores.coerceIn(1, MAX_THREADS)

    /** The exact command. [withModelType] adds `--model-type=nemo_transducer` (dropped on a retry). */
    fun buildCommand(
        exe: Path,
        model: VoiceModelFiles,
        wav: Path,
        threads: Int,
        withModelType: Boolean,
    ): List<String> {
        val args = ArrayList<String>()
        args += exe.toAbsolutePath().toString()
        args += "--encoder=${model.encoder.toAbsolutePath()}"
        args += "--decoder=${model.decoder.toAbsolutePath()}"
        args += "--joiner=${model.joiner.toAbsolutePath()}"
        args += "--tokens=${model.tokens.toAbsolutePath()}"
        if (withModelType) args += "--model-type=$MODEL_TYPE"
        args += "--num-threads=$threads"
        args += wav.toAbsolutePath().toString()
        return args
    }

    /** True when there is enough free memory, or when it cannot be measured (then the run is allowed). */
    fun enoughMemory(freeBytes: Long?, minBytes: Long = MIN_FREE_BYTES): Boolean =
        freeBytes == null || freeBytes < 0 || freeBytes >= minBytes

    /**
     * Overwrites every file in [dir] with zeros, deletes the files, then the folder. Never throws.
     * Returns true when the folder is gone.
     */
    fun wipeTempDir(dir: Path): Boolean {
        try {
            if (!Files.exists(dir)) return true
            Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    runCatching {
                        if (Files.isRegularFile(entry)) {
                            val size = Files.size(entry)
                            Files.newOutputStream(entry).use { out ->
                                val zeros = ByteArray(64 * 1024)
                                var left = size
                                while (left > 0) {
                                    val n = minOf(left, zeros.size.toLong()).toInt()
                                    out.write(zeros, 0, n)
                                    left -= n
                                }
                            }
                        }
                    }
                    runCatching { Files.deleteIfExists(entry) }
                }
            }
        } catch (e: Exception) {
            // Fall through: try to delete the folder anyway.
        }
        runCatching { Files.deleteIfExists(dir) }
        return !Files.exists(dir)
    }
}

/** Free physical memory of the machine, or null when unknown. */
internal fun interface FreeMemoryReader {
    fun freeBytes(): Long?
}

internal object SystemFreeMemory : FreeMemoryReader {
    override fun freeBytes(): Long? = runCatching {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        os?.freeMemorySize
    }.getOrNull()
}

/** What a finished program run left behind. */
internal class VoiceProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/** Runs a command and collects its output. Replaceable in tests. */
internal fun interface VoiceProcessRunner {
    fun run(command: List<String>, workingDir: File, timeoutMillis: Long): VoiceProcessResult
}

/**
 * ProcessBuilder runner: stdout and stderr are read on separate daemon threads, the process is killed
 * after the timeout and ALWAYS destroyed in `finally`. Blocking: call it from a background thread.
 * No console window: the JDK starts console programs without a window when the app has no console.
 */
internal object ProcessBuilderVoiceRunner : VoiceProcessRunner {

    private const val MAX_CAPTURE_CHARS = 200_000

    override fun run(command: List<String>, workingDir: File, timeoutMillis: Long): VoiceProcessResult {
        var process: Process? = null
        try {
            val started = ProcessBuilder(command).directory(workingDir).start()
            process = started
            runCatching { started.outputStream.close() }
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = thread(isDaemon = true, name = "voice-out") { drain(started.inputStream, out) }
            val errThread = thread(isDaemon = true, name = "voice-err") { drain(started.errorStream, err) }
            val finished = started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                destroyTree(started)
                return VoiceProcessResult(null, "", "", timedOut = true)
            }
            outThread.join(2_000)
            errThread.join(2_000)
            return VoiceProcessResult(
                started.exitValue(),
                synchronized(out) { out.toString() },
                synchronized(err) { err.toString() },
                timedOut = false,
            )
        } finally {
            process?.let { destroyTree(it) }
        }
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder) {
        runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    synchronized(into) {
                        if (into.length < MAX_CAPTURE_CHARS) into.append(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun destroyTree(process: Process) {
        runCatching { process.descendants().forEach { child -> runCatching { child.destroyForcibly() } } }
        runCatching { process.destroyForcibly() }
    }
}

/**
 * Recognizer that runs `sherpa-onnx-offline` once per command. Order: refuse on low RAM, check the
 * files, write the WAV, run, parse, and ALWAYS wipe the temp folder in `finally`. The model type flag
 * is tried first; when the program rejects it the run is repeated once without it and the decision is
 * remembered for this process.
 */
internal class SherpaSidecarRecognizer(
    private val exe: Path,
    private val model: VoiceModelFiles,
    private val memory: FreeMemoryReader = SystemFreeMemory,
    private val runner: VoiceProcessRunner = ProcessBuilderVoiceRunner,
    private val tempRoot: Path? = null,
    private val timeoutMillis: Long = VoiceRecognizerSupport.TIMEOUT_MILLIS,
    private val threads: Int = VoiceRecognizerSupport.threadCount(),
) : VoiceRecognizer {

    override suspend fun transcribe(pcm16kMono: ShortArray): Result<String> {
        if (pcm16kMono.isEmpty()) return Result.failure(VoiceException(VoiceErrors.NO_AUDIO))
        if (!VoiceRecognizerSupport.enoughMemory(memory.freeBytes())) {
            DesktopTrackLog.log("voice: recognizer refused, error=${VoiceErrors.LOW_MEMORY}")
            return Result.failure(VoiceException(VoiceErrors.LOW_MEMORY))
        }
        if (!Files.isRegularFile(exe)) return Result.failure(VoiceException(VoiceErrors.PROGRAM_MISSING))
        val missing = listOf(model.encoder, model.decoder, model.joiner, model.tokens).any { !Files.isRegularFile(it) }
        if (missing) return Result.failure(VoiceException(VoiceErrors.MODEL_MISSING))

        val startedAt = System.nanoTime()
        DesktopTrackLog.log("voice: recognizer start")
        val result: Result<String> = try {
            runInterruptible(Dispatchers.IO) { transcribeBlocking(pcm16kMono) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(VoiceException(VoiceErrors.PROGRAM_FAILED, e.javaClass.simpleName))
        }
        val ms = (System.nanoTime() - startedAt) / 1_000_000L
        val code = (result.exceptionOrNull() as? VoiceException)?.code
        DesktopTrackLog.log("voice: recognizer finish in $ms ms" + if (code != null) ", error=$code" else "")
        return result
    }

    private fun transcribeBlocking(pcm: ShortArray): Result<String> {
        val dir = if (tempRoot != null) {
            Files.createDirectories(tempRoot)
            Files.createTempDirectory(tempRoot, "muses-voice-")
        } else {
            Files.createTempDirectory("muses-voice-")
        }
        try {
            val wav = dir.resolve("clip.wav")
            Files.write(wav, VoiceRecognizerSupport.wavBytes(pcm))
            val workingDir = (exe.toAbsolutePath().parent ?: dir).toFile()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L

            fun attempt(withType: Boolean): VoiceProcessResult {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left < 1_000L) return VoiceProcessResult(null, "", "", timedOut = true)
                val command = VoiceRecognizerSupport.buildCommand(exe, model, wav, threads, withType)
                return runner.run(command, workingDir, left)
            }

            val firstWithType = useModelType
            var run = attempt(firstWithType)
            var text = if (run.timedOut) null else VoiceRecognizerSupport.parseSherpaOutput(run.stdout)
            if (!run.timedOut && firstWithType && (text == null || run.exitCode != 0)) {
                // The program may not know --model-type: repeat once without it.
                val retry = attempt(false)
                val retryText = if (retry.timedOut) null else VoiceRecognizerSupport.parseSherpaOutput(retry.stdout)
                if (retryText != null && retry.exitCode == 0) {
                    useModelType = false
                    run = retry
                    text = retryText
                } else {
                    run = retry
                    text = retryText
                }
            }
            return when {
                run.timedOut -> Result.failure(VoiceException(VoiceErrors.TIMEOUT))
                run.exitCode != 0 && text == null -> Result.failure(VoiceException(VoiceErrors.PROGRAM_FAILED, "exit ${run.exitCode}"))
                text == null -> Result.failure(VoiceException(VoiceErrors.PARSE_FAILED))
                else -> Result.success(text)
            }
        } finally {
            VoiceRecognizerSupport.wipeTempDir(dir)
        }
    }

    internal companion object {
        /** Remembered for the life of the process once a run proved the flag is not accepted. */
        @Volatile
        var useModelType: Boolean = true
    }
}
