package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Voice V2: the recorder helpers and the recorder with a fake microphone. No real audio device. */
class DesktopVoiceRecorderTest {

    private fun tone(samples: Int, value: Short = 3000): ShortArray = ShortArray(samples) { value }

    /** Fake microphone: [chunks] chunks of a constant sample, then end of line. */
    private class FakeLine(
        var chunks: Int,
        private val sample: Short,
        override val sampleRate: Int = VoiceAudio.SAMPLE_RATE,
    ) : VoiceCaptureLine {
        var reads = 0
        var flushes = 0
        var closed = false
        override fun flushBufferedAudio() { flushes++ }

        override fun read(buffer: ByteArray, length: Int): Int {
            if (chunks <= 0) return -1
            chunks--
            reads++
            var i = 0
            while (i + 1 < length) {
                buffer[i] = (sample.toInt() and 0xff).toByte()
                buffer[i + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
                i += 2
            }
            return length
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun bytesToShortsIsLittleEndianAndDropsOddByte() {
        val bytes = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0x55)
        assertContentEquals(shortArrayOf(1, -1, Short.MIN_VALUE), VoiceAudio.bytesToShorts(bytes))
    }

    @Test
    fun rmsAndPeak() {
        assertEquals(0.0, VoiceAudio.rms(ShortArray(0), 0, 0))
        assertEquals(1000.0, VoiceAudio.rms(tone(100, 1000), 0, 100), 0.001)
        assertEquals(1000.0, VoiceAudio.rms(tone(100, -1000), 0, 500), 0.001)
        assertEquals(700, VoiceAudio.peak(shortArrayOf(10, -700, 300)))
    }

    @Test
    fun trimKeepsSpeechPlusPadding() {
        // 1 s silence, 200 ms tone, 1 s silence. Keep 250 ms either side.
        val pcm = ShortArray(16_000) + tone(3_200) + ShortArray(16_000)
        val trimmed = VoiceAudio.trimSilence(pcm)
        assertEquals(11_200, trimmed.size)
        assertEquals(3000.toShort(), trimmed[4_000])
    }

    @Test
    fun adaptiveTrimRetainsQuietOpeningAudioBeforeLouderSpeech() {
        val quietOpening = tone(1_600, 60)
        val louderSpeech = tone(4_800, 1_000)
        val pcm = ShortArray(8_000) + quietOpening + louderSpeech + ShortArray(8_000)
        val trimmed = VoiceAudio.trimSilence(pcm)
        // The 60-level opening is below the detected gate, but the pre-roll preserves it.
        assertTrue(trimmed.size > quietOpening.size + louderSpeech.size)
        assertTrue(trimmed.any { it.toInt() == 60 })
    }

    @Test
    fun trimOfPureSilenceIsEmpty() {
        assertEquals(0, VoiceAudio.trimSilence(ShortArray(20_000)).size)
        assertEquals(0, VoiceAudio.trimSilence(ShortArray(0)).size)
    }

    @Test
    fun trimNeverGoesOutsideTheClip() {
        val trimmed = VoiceAudio.trimSilence(tone(1_600))
        assertEquals(1_600, trimmed.size)
    }

    @Test
    fun gainRaisesQuietAudioAtMostFourTimes() {
        val out = VoiceAudio.normalizeGain(tone(10, 1000))
        assertEquals(4000.toShort(), out[0])
    }

    @Test
    fun gainLeavesLoudAudioAlone() {
        val loud = tone(10, 30_000)
        assertTrue(VoiceAudio.normalizeGain(loud) === loud)
    }

    @Test
    fun gainScalesMidLevelAudioTowardsTarget() {
        val out = VoiceAudio.normalizeGain(tone(10, 10_000))
        // target 0.6 * 32767 / 10000 = 1.966
        assertEquals((10_000 * (0.6 * 32767.0 / 10_000)).toInt().toShort(), out[0])
    }

    @Test
    fun gainOfSilenceIsUnchanged() {
        val silent = ShortArray(5)
        assertTrue(VoiceAudio.normalizeGain(silent) === silent)
    }


    @Test
    fun native48KhzAudioIsDownsampledTo16KhzWithoutLosingSpeechBand() {
        val sourceRate = 48_000
        val source = ShortArray(sourceRate / 10) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 1_000.0 * i / sourceRate) * 10_000).toInt().toShort()
        }
        val result = VoiceAudio.resampleTo16k(source, sourceRate)
        assertEquals(1_600, result.size)
        assertTrue(VoiceAudio.rms(result, 0, result.size) > 6_000.0)
    }

    @Test
    fun recorderSignalsReadyAfterOpeningAndFlushesBeforeReading() {
        val line = FakeLine(chunks = 5, sample = 3000)
        var ready = false
        val result = runBlocking {
            DesktopVoiceRecorder { line }.record(
                maxMillis = 500,
                onCaptureReady = { ready = true },
                shouldStop = { line.reads >= 3 },
            )
        }
        assertTrue(result.isSuccess)
        assertTrue(ready)
        assertEquals(1, line.flushes)
        assertEquals(3, line.reads)
    }

    @Test
    fun recorderResamplesNative48KhzCapture() {
        val line = FakeLine(chunks = 5, sample = 3000, sampleRate = 48_000)
        val result = runBlocking {
            DesktopVoiceRecorder { line }.record(maxMillis = 500, shouldStop = { false })
        }
        val pcm = assertNotNull(result.getOrNull())
        assertEquals(8_000, pcm.size)
        assertEquals(12_000.toShort(), pcm[0])
    }

    @Test
    fun recorderReturnsTrimmedAndBoostedAudioAndClosesTheLine() {
        val line = FakeLine(chunks = 15, sample = 3000)
        val result = runBlocking { DesktopVoiceRecorder { line }.record(shouldStop = { false }) }
        val pcm = assertNotNull(result.getOrNull())
        assertEquals(24_000, pcm.size)
        assertEquals(12_000.toShort(), pcm[0])
        assertTrue(line.closed)
    }

    @Test
    fun recorderStopsWhenToldTo() {
        val line = FakeLine(chunks = 50, sample = 3000)
        val result = runBlocking { DesktopVoiceRecorder { line }.record(shouldStop = { line.reads >= 3 }) }
        assertTrue(result.isSuccess)
        assertEquals(3, line.reads)
        assertTrue(line.closed)
    }

    @Test
    fun recorderStopsAtMaxMillis() {
        val line = FakeLine(chunks = 100, sample = 3000)
        val result = runBlocking { DesktopVoiceRecorder { line }.record(maxMillis = 500, shouldStop = { false }) }
        assertTrue(result.isSuccess)
        assertEquals(5, line.reads)
    }

    @Test
    fun recorderReportsSilentMicrophone() {
        val line = FakeLine(chunks = 10, sample = 0)
        val result = runBlocking { DesktopVoiceRecorder { line }.record(shouldStop = { false }) }
        assertEquals(VoiceErrors.MIC_SILENT, (result.exceptionOrNull() as VoiceException).code)
        assertTrue(line.closed)
    }

    @Test
    fun recorderReportsTooShortClip() {
        val line = FakeLine(chunks = 1, sample = 3000)
        val result = runBlocking { DesktopVoiceRecorder { line }.record(shouldStop = { false }) }
        assertEquals(VoiceErrors.TOO_SHORT, (result.exceptionOrNull() as VoiceException).code)
    }

    @Test
    fun recorderReportsMissingMicrophone() {
        val result = runBlocking {
            DesktopVoiceRecorder { throw VoiceException(VoiceErrors.NO_MICROPHONE) }.record(shouldStop = { false })
        }
        assertEquals(VoiceErrors.NO_MICROPHONE, (result.exceptionOrNull() as VoiceException).code)
        assertFalse(result.isSuccess)
    }
}
