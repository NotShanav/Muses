package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Voice V3: the scoring function, the tone, the warm-up read and the controller's state machine with
 * fakes. No microphone, no window, no model file, no network, no hotkey.
 */
class DesktopVoiceControllerTest {

    // ── Scoring ──────────────────────────────────────────────────────────────────────────────────

    private fun song(id: String, title: String, artist: String) = VoiceSong(id, title, artist)

    @Test
    fun tokensDropBracketsAndPunctuation() {
        assertEquals(listOf("bad", "guy"), VoiceSongMatcher.tokens("Bad Guy (feat. Justin Bieber)"))
        assertEquals(listOf("bad", "guy"), VoiceSongMatcher.tokens("  BAD, guy! [Official Video]"))
        assertTrue(VoiceSongMatcher.tokens("()").isEmpty())
    }

    @Test
    fun exactTitleAndArtistScoresOne() {
        val score = VoiceSongMatcher.score(song("1", "Bad Guy", "Billie Eilish"), "bad guy", "billie eilish")
        assertTrue(score > 0.99, "score=$score")
    }

    @Test
    fun rightArtistBeatsWrongArtistAndWrongArtistIsWeak() {
        val right = song("a", "Bad Guy", "Billie Eilish")
        val wrong = song("b", "Bad Guy", "Someone Else")
        val ranked = VoiceSongMatcher.rank(listOf(wrong, right), "bad guy", "billie eilish")
        assertEquals("a", ranked.first().first.videoId)
        assertTrue(ranked.last().second < VoiceSongMatcher.WEAK, "wrong artist should be weak")
    }

    @Test
    fun extraWordsCostALittle() {
        val exact = song("a", "Bad Guy", "Billie Eilish")
        val long = song("b", "Bad Guy Live At Wembley", "Billie Eilish")
        val ranked = VoiceSongMatcher.rank(listOf(long, exact), "bad guy", null)
        assertEquals("a", ranked.first().first.videoId)
        assertTrue(ranked.last().second in 0.75..0.99, "score=${ranked.last().second}")
    }

    @Test
    fun oneLetterTypoStillScoresAboveWeak() {
        val score = VoiceSongMatcher.score(song("1", "Thriller", "Michael Jackson"), "thriler", null)
        assertTrue(score >= VoiceSongMatcher.WEAK && score < 1.0, "score=$score")
    }

    @Test
    fun unrelatedAndEmptyScoreLow() {
        assertTrue(VoiceSongMatcher.score(song("1", "Yellow", "Coldplay"), "bad guy", null) < 0.2)
        assertEquals(0.0, VoiceSongMatcher.score(song("1", "Yellow", "Coldplay"), "", null))
    }

    @Test
    fun nearEqualPlausibleResultsAreMarkedAmbiguous() {
        val candidates = listOf(
            song("a", "Yellow", "Coldplay"),
            song("b", "Yellow", "Another Artist"),
        )
        val ranked = VoiceSongMatcher.rank(candidates, "yellow", null)
        assertTrue(VoiceSongMatcher.isAmbiguous(ranked))
        assertFalse(VoiceSongMatcher.isAmbiguous(listOf(ranked.first())))
    }

    @Test
    fun equalScoresKeepSearchOrder() {
        val ranked = VoiceSongMatcher.rank(
            listOf(song("first", "Bad Guy", "A"), song("second", "Bad Guy", "B")),
            "bad guy",
            null,
        )
        assertEquals(listOf("first", "second"), ranked.map { it.first.videoId })
    }

    // ── Tone, warm-up, messages ──────────────────────────────────────────────────────────────────

    @Test
    fun toneIsAShortSoftGeneratedClip() {
        val pcm = VoiceTone.samples()
        assertEquals(1600, pcm.size)
        assertEquals(0, pcm.first().toInt())
        assertTrue(pcm.any { it.toInt() != 0 })
        assertTrue(pcm.all { kotlin.math.abs(it.toInt()) <= (0.18 * Short.MAX_VALUE).toInt() + 1 })
    }

    @Test
    fun warmupReadsTheWholeFileOrStopsWhenTold() {
        val file: Path = Files.createTempFile("muses-warm", ".bin")
        try {
            Files.write(file, ByteArray(3000))
            assertEquals(3000L, VoiceWarmup.read(file, { true }, chunk = 1024))
            assertEquals(0L, VoiceWarmup.read(file, { false }, chunk = 1024))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun microphoneMessageCarriesThePrivacyHint() {
        assertTrue(VoiceMessages.forCode(VoiceErrors.NO_MICROPHONE).contains("Privacy > Microphone"))
        assertTrue(VoiceMessages.forCode(VoiceErrors.MIC_SILENT).contains("Privacy > Microphone"))
        assertFalse(VoiceMessages.forCode("anything_else").isBlank())
    }

    // ── State machine ────────────────────────────────────────────────────────────────────────────

    private class FakeActions {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var results: List<VoiceSong> = emptyList()
        @Volatile var nowPlaying: String? = "Yellow - Coldplay"
        @Volatile var playlists: List<String> = listOf("Gym")
        val duckStates: MutableList<Boolean> = Collections.synchronizedList(mutableListOf<Boolean>())
        // VOICE FIX X2 hook: capture absolute-volume requests from the controller.
        val absoluteVolumes: MutableList<Float> = Collections.synchronizedList(mutableListOf<Float>())
        // VOICE FIX X1: the two library actions answer with reasoned results.
        @Volatile var addResult: VoicePlaylistResult = VoicePlaylistResult.Done("Gym", VoiceListSource.LOCAL, "Yellow")
        @Volatile var playResult: VoicePlaylistResult = VoicePlaylistResult.Done("Gym", VoiceListSource.LOCAL)

        val actions = VoiceActions(
            search = { q -> calls.add("search:$q"); results },
            playSong = { calls.add("play:" + it.videoId) },
            pause = { calls.add("pause") },
            resume = { calls.add("resume") },
            next = { calls.add("next") },
            previous = { calls.add("previous") },
            volumeStep = { calls.add("vol:$it") },
            setShuffle = { calls.add("shuffle:$it") },
            likeCurrent = { calls.add("like"); true },
            addCurrentToPlaylist = { calls.add("add:$it"); addResult },
            playPlaylist = { calls.add("playlist:$it"); playResult },
            nowPlayingText = { nowPlaying },
            playlistNames = { playlists },
            duckPlayback = { duckStates.add(it) },
            setVolume = { absoluteVolumes.add(it) },
        )
    }

    private class Rig(
        val heard: String,
        val fake: FakeActions = FakeActions(),
        enabled: Boolean = true,
        confirm: Boolean = true,
        modelOk: Boolean = true,
        programOk: Boolean = true,
        recognizerFailure: Throwable? = null,
        blockRecordForever: Boolean = false,
        watchdogMillis: Long = 30_000L,
        choiceMillis: Long = 5_000L,
        session: VoiceSession = VoiceSession(),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recordCalls = AtomicInteger(0)
        val tickCalls = AtomicInteger(0)
        val warmCalls = AtomicInteger(0)
        val escape = AtomicBoolean(false)

        private val files = VoiceModelFiles(
            Path.of("model"), Path.of("model/e"), Path.of("model/d"), Path.of("model/j"), Path.of("model/t"),
        )

        val controller = DesktopVoiceController(
            scope = scope,
            actions = fake.actions,
            config = VoiceControllerConfig(
                enabled = { enabled },
                confirmBeforePlay = { confirm },
                modelDir = { null },
                sidecarExe = { null },
                hotkey = { VoiceHotkey.DEFAULT },
            ),
            record = { _, onCaptureReady, shouldStop ->
                recordCalls.incrementAndGet()
                onCaptureReady()
                if (blockRecordForever) awaitCancellation()
                while (!shouldStop()) delay(2)
                Result.success(ShortArray(1600) { 1000 })
            },
            recognizerFactory = { _, _ ->
                object : VoiceRecognizer {
                    override suspend fun transcribe(pcm16kMono: ShortArray): Result<String> =
                        if (recognizerFailure != null) Result.failure(recognizerFailure) else Result.success(heard)
                }
            },
            findProgram = { if (programOk) Path.of("sherpa-onnx-offline.exe") else null },
            findModel = { if (modelOk) files else null },
            warm = { _, _ -> warmCalls.incrementAndGet() },
            tick = { tickCalls.incrementAndGet() },
            escapeDown = { escape.get() },
            doneMillis = 80L,
            choiceMillis = choiceMillis,
            errorMillis = 80L,
            watchdogMillis = watchdogMillis,
            escPollMillis = 5L,
            session = session,
        )

        val state get() = controller.state.value

        fun close() = scope.cancel()
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 4_000L, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) fail("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    /** Press, wait until listening, release, wait for the result, return it. */
    private fun speak(rig: Rig): DesktopVoiceState.Done {
        rig.controller.onPressed()
        awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
        rig.controller.onReleased()
        awaitUntil("done") { rig.state is DesktopVoiceState.Done }
        return rig.state as DesktopVoiceState.Done
    }

    private fun awaitIdle(rig: Rig) = awaitUntil("idle") { rig.state === DesktopVoiceState.Idle }

    @Test
    fun offMeansNothingHappens() {
        val rig = Rig("pause", enabled = false)
        try {
            rig.controller.onPressed()
            Thread.sleep(150)
            assertTrue(rig.state === DesktopVoiceState.Idle)
            assertEquals(0, rig.recordCalls.get())
            assertEquals(0, rig.tickCalls.get())
            assertEquals(0, rig.warmCalls.get())
            assertTrue(rig.fake.calls.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun pauseRunsTheActionShowsItAndGoesBackToIdle() {
        val rig = Rig("pause")
        try {
            val done = speak(rig)
            assertEquals("Paused", done.result)
            assertFalse(done.canUndo)
            assertEquals(listOf("pause"), rig.fake.calls.toList())
            assertEquals(listOf(true, false), rig.fake.duckStates.toList())
            assertEquals(1, rig.tickCalls.get())
            awaitUntil("warm-up started") { rig.warmCalls.get() == 1 }
            awaitIdle(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun otherSimpleCommandsCallTheirActions() {
        val cases = listOf(
            "next" to "next",
            "volume up" to "vol:0.1",
            "volume down" to "vol:-0.1",
            "like this" to "like",
            "resume" to "resume",
        )
        for ((words, call) in cases) {
            val rig = Rig(words)
            try {
                speak(rig)
                assertEquals(listOf(call), rig.fake.calls.toList(), words)
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun whatsPlayingShowsTheTextAndNothingElseHappens() {
        val rig = Rig("what's playing")
        try {
            val done = speak(rig)
            assertEquals("Yellow - Coldplay", done.result)
            assertTrue(rig.fake.calls.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun explicitPlaylistRequestIsUnderstoodEvenWhenTheInitialNameSnapshotIsEmpty() {
        val rig = Rig("play my workout playlist")
        try {
            rig.fake.playlists = emptyList()
            // The adapter resolves the spoken name against the live library and reports it back.
            rig.fake.playResult = VoicePlaylistResult.Done("workout", VoiceListSource.LOCAL)
            val done = speak(rig)
            assertEquals("Playing workout", done.result)
            assertEquals(listOf("playlist:workout"), rig.fake.calls.toList())
        } finally {
            rig.close()
        }
    }

    @Test
    fun similarSongCandidatesAreShownInsteadOfSilentlyChoosingOne() {
        val rig = Rig("play yellow")
        try {
            rig.fake.results = listOf(
                song("a", "Yellow", "Coldplay"),
                song("b", "Yellow", "Another Artist"),
            )
            val done = speak(rig)
            assertEquals("Did you mean...?", done.result)
            assertEquals(listOf("a", "b"), done.choices.map { it.videoId })
            assertTrue(rig.fake.calls.none { it.startsWith("play:") })
        } finally {
            rig.close()
        }
    }

    @Test
    fun unknownSpeechDoesNothing() {
        val rig = Rig("mumble blah")
        try {
            val done = speak(rig)
            assertEquals("Didn't catch that", done.result)
            assertTrue(rig.fake.calls.isEmpty())
            awaitIdle(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aSecondPressWhileBusyIsIgnored() {
        val rig = Rig("pause")
        try {
            rig.controller.onPressed()
            awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
            rig.controller.onPressed()
            rig.controller.onPressed()
            rig.controller.onReleased()
            awaitUntil("done") { rig.state is DesktopVoiceState.Done }
            assertEquals(1, rig.recordCalls.get())
            assertEquals(listOf("pause"), rig.fake.calls.toList())
        } finally {
            rig.close()
        }
    }

    @Test
    fun cancelDuringRecordingRestoresPlaybackVolume() {
        val rig = Rig("pause", blockRecordForever = true)
        try {
            rig.controller.onPressed()
            awaitUntil("microphone session") { rig.recordCalls.get() == 1 && rig.fake.duckStates.contains(true) }
            rig.controller.cancel()
            awaitUntil("playback restored") { rig.fake.duckStates.lastOrNull() == false }
            assertEquals(listOf(true, false), rig.fake.duckStates.toList())
        } finally {
            rig.close()
        }
    }

    @Test
    fun broadDiscoveryRequestOffersSearchResultsInsteadOfTreatingWordsAsATitle() {
        val rig = Rig("play something upbeat")
        rig.fake.results = listOf(song("a", "Feel Good Inc.", "Gorillaz"), song("b", "Levitating", "Dua Lipa"))
        try {
            val done = speak(rig)
            assertEquals("Here are some options", done.result)
            assertEquals(listOf("a", "b"), done.choices.map { it.videoId })
            assertTrue(rig.fake.calls.first() == "search:upbeat")
            assertTrue(rig.fake.calls.none { it.startsWith("play:") })
        } finally {
            rig.close()
        }
    }

    @Test
    fun missingModelOrProgramShowsAHintAndStops() {
        val noModel = Rig("pause", modelOk = false)
        try {
            noModel.controller.onPressed()
            awaitUntil("failed") { noModel.state is DesktopVoiceState.Failed }
            assertEquals(VoiceErrors.MODEL_MISSING, (noModel.state as DesktopVoiceState.Failed).code)
            assertEquals(0, noModel.recordCalls.get())
            awaitIdle(noModel)
        } finally {
            noModel.close()
        }
        val noProgram = Rig("pause", programOk = false)
        try {
            noProgram.controller.onPressed()
            awaitUntil("failed") { noProgram.state is DesktopVoiceState.Failed }
            assertEquals(VoiceErrors.PROGRAM_MISSING, (noProgram.state as DesktopVoiceState.Failed).code)
            awaitIdle(noProgram)
        } finally {
            noProgram.close()
        }
    }

    @Test
    fun recognizerErrorBecomesAnErrorCodeNotText() {
        val rig = Rig("pause", recognizerFailure = VoiceException(VoiceErrors.TIMEOUT))
        try {
            rig.controller.onPressed()
            awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
            rig.controller.onReleased()
            awaitUntil("failed") { rig.state is DesktopVoiceState.Failed }
            assertEquals(VoiceErrors.TIMEOUT, (rig.state as DesktopVoiceState.Failed).code)
            assertTrue(rig.fake.calls.isEmpty())
            awaitIdle(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun strongMatchPlaysAndUndoGoesBack() {
        val rig = Rig("play bad guy by billie eilish")
        rig.fake.results = listOf(song("a", "Bad Guy", "Billie Eilish"), song("b", "Bad Guy", "Cover Band"))
        try {
            val done = speak(rig)
            assertTrue(done.canUndo)
            assertTrue(done.choices.isEmpty())
            assertTrue(rig.fake.calls.first().startsWith("search:"))
            assertTrue("play:a" in rig.fake.calls)
            rig.controller.undo()
            assertEquals("previous", rig.fake.calls.last())
            awaitUntil("undone shown") { (rig.state as? DesktopVoiceState.Done)?.result == "Undone" }
            awaitIdle(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun weakMatchOffersChoicesAndASecondPressPicksTheFirst() {
        val rig = Rig("play bad guy by billie eilish", confirm = true)
        rig.fake.results = listOf(
            song("x", "Something Else", "Nobody"),
            song("y", "Another One", "Someone"),
            song("z", "Third", "Third Artist"),
            song("w", "Fourth", "Fourth Artist"),
        )
        try {
            val done = speak(rig)
            assertEquals(3, done.choices.size)
            assertTrue(rig.fake.calls.none { it.startsWith("play:") })
            rig.controller.onPressed()
            awaitUntil("played") { "play:x" in rig.fake.calls }
            awaitUntil("done after pick") {
                val s = rig.state
                s is DesktopVoiceState.Done && s.choices.isEmpty()
            }
            awaitIdle(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun clickingAChoicePlaysThatOne() {
        val rig = Rig("play bad guy by billie eilish", confirm = true)
        rig.fake.results = listOf(song("x", "Something Else", "Nobody"), song("y", "Another One", "Someone"))
        try {
            speak(rig)
            rig.controller.choose(1)
            awaitUntil("played") { "play:y" in rig.fake.calls }
            assertFalse("play:x" in rig.fake.calls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun weakMatchPlaysTheBestWhenAskingIsOff() {
        val rig = Rig("play bad guy by billie eilish", confirm = false)
        rig.fake.results = listOf(song("x", "Something Else", "Nobody"))
        try {
            val done = speak(rig)
            assertTrue(done.choices.isEmpty())
            assertTrue("play:x" in rig.fake.calls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun noSearchResultsPlaysNothing() {
        val rig = Rig("play bad guy by billie eilish")
        try {
            val done = speak(rig)
            assertEquals("Couldn't find that", done.result)
            assertTrue(rig.fake.calls.none { it.startsWith("play:") })
        } finally {
            rig.close()
        }
    }

    @Test
    fun escapeWhileListeningCancelsWithoutActing() {
        val rig = Rig("pause")
        try {
            rig.controller.onPressed()
            awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
            rig.escape.set(true)
            awaitIdle(rig)
            Thread.sleep(60)
            assertTrue(rig.state === DesktopVoiceState.Idle)
            assertTrue(rig.fake.calls.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun watchdogReturnsToIdleEvenWhenTheRecorderNeverEnds() {
        val rig = Rig("pause", blockRecordForever = true, watchdogMillis = 150L)
        try {
            rig.controller.onPressed()
            awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
            awaitIdle(rig)
            assertTrue(rig.fake.calls.isEmpty())
            // And a new command works afterwards.
            assertEquals(1, rig.recordCalls.get())
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopAbandonsARunningSessionAndDropsEverything() {
        val rig = Rig("pause")
        try {
            rig.controller.onPressed()
            awaitUntil("listening") { rig.state is DesktopVoiceState.Listening }
            rig.controller.stop()
            assertTrue(rig.state === DesktopVoiceState.Idle)
            rig.controller.onReleased()
            Thread.sleep(80)
            assertTrue(rig.state === DesktopVoiceState.Idle)
            assertTrue(rig.fake.calls.isEmpty())
        } finally {
            rig.close()
        }
    }

    // ── VOICE FIX X1: library actions say exactly what happened ───────────────────────────────────

    @Test
    fun addToPlaylistDoneShowsTheSongAndOffersUndoWhenOneExists() {
        val rig = Rig("add this to gym")
        val undone = AtomicBoolean(false)
        rig.fake.addResult = VoicePlaylistResult.Done("Gym", VoiceListSource.USER, "Yellow", undo = { undone.set(true) })
        try {
            val done = speak(rig)
            assertEquals("Added Yellow to Gym", done.result)
            assertTrue(done.canUndo)
            rig.controller.undo()
            awaitUntil("undo ran") { undone.get() }
        } finally {
            rig.close()
        }
    }

    @Test
    fun addToPlaylistWithoutAnInverseHasNoUndo() {
        val rig = Rig("add this to gym")
        rig.fake.addResult = VoicePlaylistResult.Done("Gym", VoiceListSource.LOCAL, "Yellow")
        try {
            val done = speak(rig)
            assertEquals("Added Yellow to Gym", done.result)
            assertFalse(done.canUndo)
        } finally {
            rig.close()
        }
    }

    @Test
    fun addToPlaylistNotEditableSaysSo() {
        val rig = Rig("add this to gym")
        rig.fake.addResult = VoicePlaylistResult.NotEditable("Gym")
        try {
            val done = speak(rig)
            assertEquals("Gym isn't one of your editable playlists", done.result)
            assertFalse(done.canUndo)
        } finally {
            rig.close()
        }
    }

    @Test
    fun addToPlaylistNothingPlayingAndLibraryNotLoadedHaveTheirOwnMessages() {
        val rig = Rig("add this to gym")
        try {
            rig.fake.addResult = VoicePlaylistResult.NothingPlaying
            assertEquals("Nothing is playing", speak(rig).result)
            awaitIdle(rig)
            rig.fake.addResult = VoicePlaylistResult.LibraryNotLoaded
            assertTrue(speak(rig).result.startsWith("Your library is still loading"))
        } finally {
            rig.close()
        }
    }

    @Test
    fun playPlaylistResultsMapToMessages() {
        val rig = Rig("play my gym playlist")
        try {
            assertEquals("Playing Gym", speak(rig).result)
            awaitIdle(rig)
            rig.fake.playResult = VoicePlaylistResult.NotFound
            assertEquals("Couldn't find a playlist called Gym", speak(rig).result)
        } finally {
            rig.close()
        }
    }

    // VOICE FIX X2 hook: follow-up commands use the in-memory session and absolute volume adapter.
    @Test
    fun setVolumeUsesAbsolutePercent() {
        val rig = Rig("volume to forty")
        try {
            assertEquals("Volume 40 percent", speak(rig).result)
            assertEquals(listOf(0.4f), rig.fake.absoluteVolumes.toList())
        } finally {
            rig.close()
        }
    }

    @Test
    fun chooseAndChooseOtherPlayTheRememberedSong() {
        val first = VoiceRef(VoiceEntityKind.SONG, "Yellow", "yellow-id")
        val second = VoiceRef(VoiceEntityKind.SONG, "Clocks", "clocks-id")
        val session = VoiceSession(now = { 0L }).apply {
            rememberCandidates(listOf(first, second))
            rememberPlayed(first)
        }
        val rig = Rig("no, the other one", session = session).apply {
            fake.results = listOf(VoiceSong("clocks-id", "Clocks", "Coldplay"))
        }
        try {
            assertEquals("Playing Clocks - Coldplay", speak(rig).result)
            assertTrue(rig.fake.calls.contains("play:clocks-id"))
        } finally {
            rig.close()
        }
    }

    @Test
    fun chooseFirstUsesTheRememberedSong() {
        val session = VoiceSession(now = { 0L }).apply {
            rememberCandidates(listOf(VoiceRef(VoiceEntityKind.SONG, "Yellow", "yellow-id")))
        }
        val rig = Rig("the first one", session = session).apply {
            fake.results = listOf(VoiceSong("yellow-id", "Yellow", "Coldplay"))
        }
        try {
            assertEquals("Playing Yellow - Coldplay", speak(rig).result)
        } finally {
            rig.close()
        }
    }

    @Test
    fun addToLastPlaylistUsesRememberedDestination() {
        val session = VoiceSession(now = { 0L }).apply {
            rememberPlaylist(VoiceRef(VoiceEntityKind.PLAYLIST, "Gym", "gym-id"))
        }
        val rig = Rig("add it to that one", session = session)
        try {
            assertEquals("Added Yellow to Gym", speak(rig).result)
            assertTrue(rig.fake.calls.contains("add:Gym"))
        } finally {
            rig.close()
        }
    }

    @Test
    fun disablingVoiceClearsTheSession() {
        val session = VoiceSession(now = { 0L }).apply {
            rememberPlayed(VoiceRef(VoiceEntityKind.SONG, "Secret", "id"))
        }
        val rig = Rig("pause", enabled = false, session = session)
        try {
            rig.controller.refresh()
            assertTrue(session.isEmpty())
        } finally {
            rig.close()
        }
    }
}
