package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sin

/*
 * Voice commands, part V2: the microphone. Audio exists only in memory (a growing byte buffer) and is
 * handed to the recognizer as a ShortArray. Nothing is written to disk here and nothing is logged
 * except error codes. The pure helpers (trim, gain, byte conversion) carry the testable logic.
 */

/** A failure with a short CODE the UI can map to a message. The message never contains audio or text. */
internal class VoiceException(val code: String, detail: String? = null) : Exception(detail ?: code)

/** Error codes used by the recorder and the recognizer. */
internal object VoiceErrors {
    const val NO_MICROPHONE = "no_microphone"
    const val MIC_SILENT = "mic_silent"
    const val TOO_SHORT = "too_short"
    const val RECORD_FAILED = "record_failed"
    const val NO_AUDIO = "no_audio"
    const val LOW_MEMORY = "low_memory"
    const val PROGRAM_MISSING = "program_missing"
    const val MODEL_MISSING = "model_missing"
    const val TIMEOUT = "timeout"
    const val PROGRAM_FAILED = "program_failed"
    const val PARSE_FAILED = "parse_failed"
}

/** Pure audio helpers. All sample rates are 16 kHz mono, 16 bit. */
// VOICE V5 hook: capture opens at the device's native rate and is resampled once to 16 kHz.
internal object VoiceAudio {

    const val SAMPLE_RATE = 16_000
    const val DEFAULT_MAX_MILLIS = 12_000

    /** RMS (0..32768) of a slice of [pcm]; 0 for an empty slice. */
    fun rms(pcm: ShortArray, from: Int, toExclusive: Int): Double {
        val start = max(0, from)
        val end = min(pcm.size, toExclusive)
        if (end <= start) return 0.0
        var sum = 0.0
        for (i in start until end) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (end - start))
    }

    /** Largest absolute sample value. */
    fun peak(pcm: ShortArray): Int {
        var p = 0
        for (s in pcm) p = max(p, abs(s.toInt()))
        return p
    }

    /** Little-endian 16-bit bytes to samples. An odd trailing byte is dropped. */
    fun bytesToShorts(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val count = min(length, bytes.size) / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /**
     * Cuts leading and trailing silence. A 20 ms window is "voiced" when its RMS is at least
     * [threshold]. [padMillis] of audio is kept on both sides so soft consonants survive. When no
     * window is voiced the result is empty.
     */
    fun trimSilence(
        pcm: ShortArray,
        threshold: Double = -1.0,
        windowMillis: Int = 10,
        padMillis: Int = 250,
    ): ShortArray {
        if (pcm.isEmpty()) return pcm
        // A fixed 300-RMS cutoff can remove quiet opening consonants. Adapt the gate to the clip's
        // peak, keep a low floor, and preserve a quarter-second around detected speech.
        val gate = if (threshold >= 0.0) threshold else (peak(pcm) * 0.02).coerceIn(90.0, 250.0)
        val window = max(1, SAMPLE_RATE * windowMillis / 1000)
        var first = -1
        var last = -1
        var at = 0
        while (at < pcm.size) {
            val end = min(pcm.size, at + window)
            if (rms(pcm, at, end) >= gate) {
                if (first < 0) first = at
                last = end
            }
            at += window
        }
        if (first < 0) return ShortArray(0)
        val pad = SAMPLE_RATE * padMillis / 1000
        val from = max(0, first - pad)
        val to = min(pcm.size, last + pad)
        return pcm.copyOfRange(from, to)
    }

    /**
     * Gentle gain: when the loudest sample is below [targetPeak] (fraction of full scale) the audio is
     * raised towards it, at most [maxGain] times. Loud audio is never touched (no clipping, no loss).
     */
    fun normalizeGain(pcm: ShortArray, targetPeak: Double = 0.6, maxGain: Double = 4.0): ShortArray {
        val peak = peak(pcm)
        if (peak <= 0) return pcm
        val wanted = targetPeak * 32767.0 / peak
        val gain = min(maxGain, wanted)
        if (gain <= 1.05) return pcm
        return ShortArray(pcm.size) { i ->
            (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Downsamples native microphone PCM to the 16 kHz required by Parakeet using a low-pass sinc filter. */
    fun resampleTo16k(pcm: ShortArray, sourceRate: Int): ShortArray {
        if (pcm.isEmpty() || sourceRate == SAMPLE_RATE) return pcm
        require(sourceRate > SAMPLE_RATE) { "Source sample rate must be at least 16 kHz" }
        val ratio = SAMPLE_RATE.toDouble() / sourceRate.toDouble()
        val outputSize = (pcm.size * ratio).toInt().coerceAtLeast(1)
        val cutoff = 0.5 * ratio * 0.94
        val radius = 24
        val output = ShortArray(outputSize)
        for (outIndex in output.indices) {
            val sourcePosition = outIndex / ratio
            val centre = sourcePosition.toInt()
            var weighted = 0.0
            var weightSum = 0.0
            val first = max(0, centre - radius + 1)
            val last = min(pcm.lastIndex, centre + radius)
            for (sourceIndex in first..last) {
                val distance = sourcePosition - sourceIndex
                val absolute = kotlin.math.abs(distance)
                if (absolute >= radius) continue
                val window = 0.5 + 0.5 * kotlin.math.cos(PI * distance / radius)
                val phase = 2.0 * cutoff * distance
                val sinc = if (kotlin.math.abs(phase) < 1e-10) 1.0 else sin(PI * phase) / (PI * phase)
                val weight = 2.0 * cutoff * sinc * window
                weighted += pcm[sourceIndex] * weight
                weightSum += weight
            }
            val sample = if (kotlin.math.abs(weightSum) > 1e-10) weighted / weightSum else 0.0
            output[outIndex] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    /** Duration in milliseconds of [samples] at 16 kHz. */
    fun millis(samples: Int): Long = samples.toLong() * 1000L / SAMPLE_RATE
}

/** One open microphone line. Small so the recorder can be tested with a fake. */
internal interface VoiceCaptureLine {
    /** Native PCM rate. The recorder resamples to 16 kHz before giving audio to Parakeet. */
    val sampleRate: Int get() = VoiceAudio.SAMPLE_RATE
    /** Discards samples accumulated during the ready tone or while the capture line was opening. */
    fun flushBufferedAudio() {}
    /** Reads up to [length] bytes; returns the count, or a value <= 0 when the line has stopped. */
    fun read(buffer: ByteArray, length: Int): Int
    fun close()
}

/** Opens the system microphone at the best commonly supported native rate, then resamples in memory. */
internal fun openSystemCaptureLine(): VoiceCaptureLine {
    val candidateRates = listOf(48_000, 44_100, 32_000, 24_000, 22_050, VoiceAudio.SAMPLE_RATE)
    var lastFailure: Exception? = null
    for (rate in candidateRates) {
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = try {
            if (!AudioSystem.isLineSupported(info)) continue
            AudioSystem.getLine(info) as TargetDataLine
        } catch (e: Exception) {
            lastFailure = e
            continue
        }
        try {
            // Four 100 ms chunks of headroom, scaled to the native device rate.
            val bufferBytes = max(rate / 10 * 2 * 4, VoiceRecorderMath.CHUNK_BYTES * 4)
            line.open(format, bufferBytes)
            line.start()
            return object : VoiceCaptureLine {
                override val sampleRate: Int = rate
                override fun flushBufferedAudio() { line.flush() }
                override fun read(buffer: ByteArray, length: Int): Int = line.read(buffer, 0, length)
                override fun close() {
                    runCatching { line.stop() }
                    runCatching { line.close() }
                }
            }
        } catch (e: Exception) {
            lastFailure = e
            runCatching { line.close() }
        }
    }
    // IllegalArgumentException, SecurityException, LineUnavailableException: no usable microphone.
    throw VoiceException(VoiceErrors.NO_MICROPHONE, lastFailure?.javaClass?.simpleName)
}

internal object VoiceRecorderMath {
    /** 100 ms of 16 kHz mono 16-bit audio, retained for tests and default-format devices. */
    const val CHUNK_BYTES = VoiceAudio.SAMPLE_RATE / 10 * 2

    fun chunkBytes(sampleRate: Int): Int = sampleRate / 10 * 2

    /** Maximum native-format bytes for [maxMillis]. */
    fun maxBytes(maxMillis: Int, sampleRate: Int = VoiceAudio.SAMPLE_RATE): Int =
        (sampleRate.toLong() * 2L * maxMillis / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Shortest clip worth sending to the recognizer. */
    const val MIN_SPEECH_MILLIS = 250L

    /** A clip whose loudest sample is below this is treated as "the microphone gives nothing". */
    const val SILENT_PEAK = 8

}

/**
 * Records from the microphone into memory: 16 kHz, mono, 16 bit, read in 100 ms chunks until
 * [shouldStop] says so, [maxMillis] of audio is reached, or the coroutine is cancelled. The line is
 * always closed in `finally`. Leading and trailing silence is trimmed and the gain is raised gently.
 */
internal class DesktopVoiceRecorder(
    private val openLine: () -> VoiceCaptureLine = ::openSystemCaptureLine,
) {

    suspend fun record(
        maxMillis: Int = VoiceAudio.DEFAULT_MAX_MILLIS,
        onCaptureReady: () -> Unit = {},
        shouldStop: () -> Boolean,
    ): Result<ShortArray> = withContext(Dispatchers.IO) {
        var line: VoiceCaptureLine? = null
        try {
            line = openLine()
            val sampleRate = line.sampleRate
            if (sampleRate < VoiceAudio.SAMPLE_RATE) {
                return@withContext Result.failure(VoiceException(VoiceErrors.RECORD_FAILED, "unsupported sample rate"))
            }
            val limit = VoiceRecorderMath.maxBytes(maxMillis, sampleRate)
            val bytes = ByteArrayOutputStream(min(limit, 1 shl 20))
            val chunk = ByteArray(VoiceRecorderMath.chunkBytes(sampleRate))
            // Signal readiness only after the input line is started. The ready tone is deliberately
            // played here, not before opening the microphone. Flush its acoustic/electrical tail.
            runCatching { onCaptureReady() }
            runCatching { line.flushBufferedAudio() }
            while (bytes.size() < limit) {
                ensureActive()
                val read = line.read(chunk, chunk.size)
                if (read <= 0) break
                bytes.write(chunk, 0, read)
                if (shouldStop()) break
            }
            val native = VoiceAudio.bytesToShorts(bytes.toByteArray())
            val raw = VoiceAudio.resampleTo16k(native, sampleRate)
            if (VoiceAudio.peak(raw) < VoiceRecorderMath.SILENT_PEAK) {
                return@withContext Result.failure(VoiceException(VoiceErrors.MIC_SILENT))
            }
            val trimmed = VoiceAudio.trimSilence(raw)
            if (VoiceAudio.millis(trimmed.size) < VoiceRecorderMath.MIN_SPEECH_MILLIS) {
                return@withContext Result.failure(VoiceException(VoiceErrors.TOO_SHORT))
            }
            Result.success(VoiceAudio.normalizeGain(trimmed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: VoiceException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(VoiceException(VoiceErrors.RECORD_FAILED, e.javaClass.simpleName))
        } finally {
            runCatching { line?.close() }
        }
    }
}
