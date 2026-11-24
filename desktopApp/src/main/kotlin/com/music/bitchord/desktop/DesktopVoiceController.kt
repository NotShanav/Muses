package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Voice commands, part V3: the controller. Push to talk: pressed = listen, released = recognise,
 * parse, act, show the result for a moment, forget everything.
 *
 * The controller never touches the player: every action is a lambda in [VoiceActions]. The recognised
 * words live only in [DesktopVoiceState.Done] for the 2.5 s the overlay shows them; they are never
 * logged, stored or written. Logs carry "voice command handled" or an error CODE only.
 */

/** A search hit, reduced to what the matcher needs. [payload] carries the app's own object back to [VoiceActions.playSong]. */
internal data class VoiceSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val payload: Any? = null,
)

/**
 * What the controller may do. Changed from the plan's `play(title, artist?)`: the controller needs the
 * search results to score them and to offer the top three, so the adapter exposes [search] and
 * [playSong] instead.
 */
internal class VoiceActions(
    val search: suspend (query: String) -> List<VoiceSong>,
    val playSong: (VoiceSong) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val volumeStep: (delta: Float) -> Unit,
    val setShuffle: (on: Boolean) -> Unit,
    /** True when the current song is now liked (or already was); false when nothing is playing. */
    val likeCurrent: () -> Boolean,
    // VOICE FIX X1 hook: reasoned results instead of Boolean (see DesktopVoiceLibraryOps.kt).
    val addCurrentToPlaylist: suspend (name: String) -> VoicePlaylistResult,
    val playPlaylist: suspend (name: String) -> VoicePlaylistResult,
    val nowPlayingText: () -> String?,
    val playlistNames: () -> List<String>,
    /** Temporarily lower only Muses playback while the microphone is recording. */
    val duckPlayback: (enabled: Boolean) -> Unit = {},
    // VOICE FIX X2 hook: appended defaulted actions preserve existing fakes and call sites.
    val setVolume: (Float) -> Unit = {},
    val libraryIndex: () -> VoiceLibraryIndex = { VoiceLibraryIndex.EMPTY },
)

/** Settings, read at the moment of use (V4 drives them from `DesktopVoiceSettings`). */
internal class VoiceControllerConfig(
    val enabled: () -> Boolean,
    val confirmBeforePlay: () -> Boolean,
    val modelDir: () -> String?,
    val sidecarExe: () -> String?,
    val hotkey: () -> VoiceHotkey,
)

/** IDLE, LISTENING, THINKING, DONE(result), ERROR(code). Strings exist only while a state is shown. */
internal sealed interface DesktopVoiceState {
    object Idle : DesktopVoiceState
    /** Capture is still opening; nothing is shown. Listening starts only when the ready tone has played. */
    object Preparing : DesktopVoiceState
    object Listening : DesktopVoiceState
    object Thinking : DesktopVoiceState

    /** [heard] may be empty. [choices] non-empty means "click one, or press the hotkey for the first". */
    data class Done(
        val heard: String,
        val result: String,
        val canUndo: Boolean = false,
        val choices: List<VoiceSong> = emptyList(),
    ) : DesktopVoiceState

    data class Failed(val code: String, val message: String) : DesktopVoiceState
}

/** Error CODE to a short sentence for the overlay. */
internal object VoiceMessages {
    fun forCode(code: String): String = when (code) {
        VoiceErrors.PROGRAM_MISSING -> "Voice isn't set up: the recognizer program is missing (Settings > Experimental)"
        VoiceErrors.MODEL_MISSING -> "Voice isn't set up: the speech model is missing (Settings > Experimental)"
        VoiceErrors.NO_MICROPHONE, VoiceErrors.MIC_SILENT ->
            "No sound from the microphone. Allow desktop apps to use your microphone in Windows Settings > Privacy > Microphone"
        VoiceErrors.TOO_SHORT, VoiceErrors.NO_AUDIO -> "Hold the keys while you speak"
        VoiceErrors.LOW_MEMORY -> "Not enough free memory for voice (about 1.8 GB needed)"
        VoiceErrors.TIMEOUT -> "That took too long"
        else -> "Couldn't recognise that"
    }
}

/** Scores search hits against the words heard. Pure. */
internal object VoiceSongMatcher {

    /** Below this the best hit is "unsure". */
    const val WEAK = 0.75
    /** Near-equal results are ambiguous even when both match the words well. */
    const val AMBIGUOUS_GAP = 0.08

    private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]""")

    fun tokens(text: String): List<String> =
        BRACKETS.replace(text.lowercase(), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }

    /** 0..1: how well [have] covers [want], with a small penalty for extra words. */
    fun tokenScore(want: List<String>, have: List<String>): Double {
        if (want.isEmpty() || have.isEmpty()) return 0.0
        var recall = 0.0
        for (w in want) recall += have.maxOf { h -> wordMatch(w, h) }
        recall /= want.size
        var precision = 0.0
        for (h in have) precision += want.maxOf { w -> wordMatch(w, h) }
        precision /= have.size
        return 0.8 * recall + 0.2 * precision
    }

    private fun wordMatch(a: String, b: String): Double = when {
        a == b -> 1.0
        a.length >= 4 && b.length >= 4 && editDistance(a, b) <= 1 -> 0.8
        else -> 0.0
    }

    fun score(candidate: VoiceSong, wantTitle: String, wantArtist: String?): Double {
        val title = tokenScore(tokens(wantTitle), tokens(candidate.title))
        val artistTokens = wantArtist?.let(::tokens).orEmpty()
        if (artistTokens.isEmpty()) return title
        val artist = tokenScore(artistTokens, tokens(candidate.artist))
        return 0.65 * title + 0.35 * artist
    }

    /** Best first. Equal scores keep the search order. */
    fun rank(candidates: List<VoiceSong>, wantTitle: String, wantArtist: String?): List<Pair<VoiceSong, Double>> =
        candidates.distinctBy { it.videoId }.map { it to score(it, wantTitle, wantArtist) }.sortedByDescending { it.second }

    /** True if two different results are both plausible and too close to choose safely. */
    fun isAmbiguous(ranked: List<Pair<VoiceSong, Double>>): Boolean {
        val first = ranked.getOrNull(0) ?: return false
        val second = ranked.getOrNull(1) ?: return false
        return second.second >= WEAK && first.second - second.second < AMBIGUOUS_GAP
    }

    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}

/** The soft "I'm listening" tick: generated, no asset file. */
internal object VoiceTone {

    fun samples(
        frequency: Double = 880.0,
        millis: Int = 100,
        volume: Double = 0.18,
        sampleRate: Int = VoiceAudio.SAMPLE_RATE,
    ): ShortArray {
        val count = sampleRate * millis / 1000
        val fade = (sampleRate / 100).coerceAtLeast(1) // 10 ms in, 10 ms out
        return ShortArray(count) { i ->
            val edge = minOf(i, count - 1 - i)
            val envelope = if (edge < fade) edge.toDouble() / fade else 1.0
            (sin(2.0 * PI * frequency * i / sampleRate) * volume * envelope * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Blocks for about 100 ms. Never throws (no speaker is simply silence). */
    fun play() {
        runCatching {
            val format = AudioFormat(VoiceAudio.SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            try {
                line.open(format)
                line.start()
                val pcm = samples()
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = ((pcm[i].toInt() shr 8) and 0xff).toByte()
                }
                line.write(bytes, 0, bytes.size)
                line.drain()
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }
    }
}

/** Reads a file once, front to back, into a throwaway buffer: it only warms the OS file cache. */
internal object VoiceWarmup {
    fun read(path: Path, keepGoing: () -> Boolean, chunk: Int = 1 shl 20): Long {
        var total = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(chunk)
            while (keepGoing()) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        }
        return total
    }
}

/**
 * Controller slot, plus the config the controller reads (from the persisted `DesktopVoiceSettings`;
 * everything is off by default). [controller] is published by the app so the overlay (which lives in
 * the application scope, outside the main window) and the Settings page can reach it.
 */
internal object DesktopVoiceRuntime {
    // VOICE V4 hook: the temporary @Volatile fields are gone; the persisted DesktopVoiceSettings
    // (switch, hotkey, folders, confirm) are read each time the controller asks.

    val controller = MutableStateFlow<DesktopVoiceController?>(null)

    fun config(): VoiceControllerConfig = VoiceControllerConfig(
        enabled = { DesktopVoiceSettings.enabled.value },
        confirmBeforePlay = { DesktopVoiceSettings.confirmBeforePlay.value },
        modelDir = { DesktopVoiceSettings.modelDir.value },
        sidecarExe = { DesktopVoiceSettings.sidecarExe.value },
        hotkey = { DesktopVoiceSettings.hotkey.value },
    )
}

internal class DesktopVoiceController(
    private val scope: CoroutineScope,
    private val actions: VoiceActions,
    private val config: VoiceControllerConfig = DesktopVoiceRuntime.config(),
    private val record: suspend (
        maxMillis: Int,
        onCaptureReady: () -> Unit,
        shouldStop: () -> Boolean,
    ) -> Result<ShortArray> = { ms, ready, stop -> DesktopVoiceRecorder().record(ms, ready, stop) },
    private val recognizerFactory: (exe: Path, model: VoiceModelFiles) -> VoiceRecognizer =
        { exe, model -> SherpaSidecarRecognizer(exe, model) },
    private val findProgram: (chosen: String?) -> Path? = { chosen -> DesktopVoiceSidecar.findExisting(chosen = chosen) },
    private val findModel: (chosen: String?) -> VoiceModelFiles? = { chosen -> defaultFindModel(chosen) },
    private val warm: (file: Path, keepGoing: () -> Boolean) -> Unit = { file, keep -> VoiceWarmup.read(file, keep) },
    private val tick: () -> Unit = { VoiceTone.play() },
    private val escapeDown: () -> Boolean = { DesktopVoiceKeys.isDown(DesktopVoiceKeys.VK_ESCAPE) },
    private val doneMillis: Long = DONE_MILLIS,
    private val choiceMillis: Long = CHOICE_MILLIS,
    private val errorMillis: Long = ERROR_MILLIS,
    private val watchdogMillis: Long = WATCHDOG_MILLIS,
    private val escPollMillis: Long = ESC_POLL_MILLIS,
    // VOICE FIX X2 hook: transient follow-up context is owned and cleared by this controller.
    private val session: VoiceSession = VoiceSession(),
) {

    private class Outcome(
        val message: String,
        val undo: (() -> Unit)? = null,
        val choices: List<VoiceSong> = emptyList(),
        val candidateRefs: List<VoiceRef>? = null,
    )

    private val _state = MutableStateFlow<DesktopVoiceState>(DesktopVoiceState.Idle)
    val state: StateFlow<DesktopVoiceState> = _state

    private val lock = Any()
    private var busy = false

    @Volatile
    private var sessionId = 0

    @Volatile
    private var job: Job? = null

    @Volatile
    private var watchdog: Job? = null

    @Volatile
    private var escWatcher: Job? = null
    private var undoAction: (() -> Unit)? = null
    private var pendingChoices: List<VoiceSong> = emptyList()
    private var hotkey: DesktopVoiceHotkey? = null
    private var registered: VoiceHotkey? = null

    @Volatile
    private var held = false

    @Volatile
    private var cancelled = false

    /** 0 = hold to talk; above 0 = tap once, record for at most this long. */
    @Volatile
    private var tapWindowMillis = 0L

    /** When the ready tone finished (nanoTime); 0 until then. The tap timeframe counts from here. */
    @Volatile
    private var listenStartNanos = 0L

    private fun shouldStopRecording(): Boolean {
        if (cancelled || !held) return true
        val window = tapWindowMillis
        val from = listenStartNanos
        return window > 0L && from != 0L && (System.nanoTime() - from) / 1_000_000L >= window
    }

    // ── Hotkey wiring ────────────────────────────────────────────────────────────────────────────

    /** Registers the hotkey when the feature is on. Does nothing at all when it is off. */
    fun start() = refresh()

    /** Applies the current settings: registers, re-registers on a new combination, or unregisters. */
    fun refresh() {
        if (!config.enabled()) {
            // VOICE FIX X2 hook: voice-off clears all in-memory references.
            session.clear()
            stopHotkey()
            if (_state.value !is DesktopVoiceState.Idle) forceIdle("disabled")
            return
        }
        if (!DesktopPlatform.isWindows) return
        val wanted = config.hotkey()
        val current = hotkey
        if (current != null && registered == wanted &&
            current.status.value == DesktopVoiceHotkey.Status.ACTIVE
        ) {
            return
        }
        stopHotkey()
        val created = DesktopVoiceHotkey(onPressed = ::onPressed, onReleased = ::onReleased)
        hotkey = created
        registered = wanted
        created.start(wanted)
    }

    /** Unregisters the hotkey and abandons whatever is running. */
    fun stop() {
        // VOICE FIX X2 hook: disposal/exit clears all in-memory references.
        session.clear()
        stopHotkey()
        forceIdle("stopped")
    }

    /** ACTIVE, TAKEN (another program owns the combination), UNAVAILABLE or STOPPED. */
    fun hotkeyStatus(): DesktopVoiceHotkey.Status = hotkey?.status?.value ?: DesktopVoiceHotkey.Status.STOPPED

    private fun stopHotkey() {
        val current = hotkey
        hotkey = null
        registered = null
        current?.stop()
    }

    // ── Events ───────────────────────────────────────────────────────────────────────────────────

    /** Hotkey down (hold to talk). Any thread. */
    fun onPressed() = onKeyDown(0L)

    /**
     * A trigger went down. [tapMillis] 0 means hold to talk (released by [onReleased]); above 0 means
     * tap once and speak: recording ends after [tapMillis], or at a second tap.
     */
    fun onKeyDown(tapMillis: Long) {
        if (!config.enabled()) return
        var pickFirst = false
        synchronized(lock) {
            if (_state.value is DesktopVoiceState.Done && pendingChoices.isNotEmpty()) {
                pickFirst = true
            } else if (busy) {
                // Single-flight. In tap mode a second tap while listening ends the recording early.
                if (tapMillis > 0L && tapWindowMillis > 0L && _state.value is DesktopVoiceState.Listening) held = false
                return
            } else {
                busy = true
                held = true
                cancelled = false
                tapWindowMillis = tapMillis
                listenStartNanos = 0L
                sessionId++
            }
        }
        if (pickFirst) {
            choose(0)
            return
        }
        val mine = sessionId
        job = scope.launch { runSession() }
        // Only the session that started it may be cut off by it.
        watchdog = scope.launch {
            delay(watchdogMillis)
            if (sessionId == mine) forceIdle("watchdog")
        }
    }

    /** Hotkey up. Any thread. */
    fun onReleased() {
        if (tapWindowMillis > 0L) return // tap mode ends by timeframe or a second tap, not by release
        held = false
    }

    /** Esc, or a click on the overlay's cancel. */
    fun cancel() = forceIdle("cancelled")

    /** The overlay's Undo button: the inverse action, only where one exists. */
    fun undo() {
        var action: (() -> Unit)? = null
        synchronized(lock) {
            action = undoAction
            undoAction = null
        }
        val inverse = action ?: return
        job?.cancel()
        runCatching { inverse() }
        job = scope.launch {
            _state.value = DesktopVoiceState.Done(heard = "", result = "Undone")
            delay(UNDONE_MILLIS)
            reset()
        }
    }

    /** A click on one of the offered songs (or the second hotkey press, which picks the first). */
    fun choose(index: Int) {
        var song: VoiceSong? = null
        synchronized(lock) {
            song = pendingChoices.getOrNull(index)
            if (song != null) pendingChoices = emptyList()
        }
        val picked = song ?: return
        job?.cancel()
        job = scope.launch {
            // VOICE FIX X2 hook: UI-selected songs are remembered just like voice-selected songs.
            runCatching { actions.playSong(picked) }.onSuccess {
                session.rememberPlayed(VoiceRef(VoiceEntityKind.SONG, picked.title, picked.videoId))
            }
            publishDone("", Outcome("Playing " + label(picked), undo = { actions.previous() }))
        }
    }

    // ── The session ──────────────────────────────────────────────────────────────────────────────

    // VOICE V5 hook: capture readiness/ducking and intent/entity resolution remain separate stages.
    private suspend fun runSession() {
        var warmJob: Job? = null
        var playbackDucked = false
        try {
            val program = withContext(Dispatchers.IO) { findProgram(config.sidecarExe()) }
            val model = withContext(Dispatchers.IO) { findModel(config.modelDir()) }
            if (program == null) return fail(VoiceErrors.PROGRAM_MISSING)
            if (model == null) return fail(VoiceErrors.MODEL_MISSING)

            // Not shown yet: the overlay would appear before the ready tone and the owner would start
            // speaking while the microphone is still opening. Listening is published by the tone callback.
            _state.value = DesktopVoiceState.Preparing
            startEscWatcher()
            val warming = scope.launch(Dispatchers.IO) {
                runCatching { warm(model.encoder) { isActive } }
            }
            warmJob = warming

            // Duck before opening the microphone. The ready tick is emitted only after the capture
            // line is open; the recorder then flushes that tick from its input buffer before speech
            // samples are collected, so the user does not lose the first word.
            runCatching { actions.duckPlayback(true) }
            playbackDucked = true
            val recorded = record(
                MAX_RECORD_MILLIS,
                {
                    // Capture line is open. Tone first, then show "Listening" (so the popup and the beep
                    // arrive together); the recorder drops everything captured up to here.
                    runCatching { tick() }
                    if (!cancelled && busy) {
                        listenStartNanos = System.nanoTime()
                        _state.value = DesktopVoiceState.Listening
                    }
                },
            ) { shouldStopRecording() }
            runCatching { actions.duckPlayback(false) }
            playbackDucked = false
            if (cancelled || !currentCoroutineContext().isActive) return // F2: stop() resets `cancelled`, the cancelled job is the reliable signal
            val pcm = recorded.getOrElse { return fail(codeOf(it)) }

            _state.value = DesktopVoiceState.Thinking
            val heard = recognizerFactory(program, model).transcribe(pcm).getOrElse { return fail(codeOf(it)) }
            if (cancelled || !currentCoroutineContext().isActive) return // F2

            val command = DesktopVoiceCommandParser.parse(heard, safePlaylistNames())
            val outcome = execute(command)
            DesktopTrackLog.log("voice command handled")
            publishDone(heard, outcome)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail("unexpected")
        } finally {
            if (playbackDucked) runCatching { actions.duckPlayback(false) }
            warmJob?.cancel()
        }
    }

    private fun safePlaylistNames(): List<String> = runCatching { actions.playlistNames() }.getOrDefault(emptyList())

    private fun codeOf(t: Throwable): String = (t as? VoiceException)?.code ?: "unexpected"

    private suspend fun execute(command: VoiceCommand): Outcome = when (command) {
        is VoiceCommand.Play -> play(command)
        is VoiceCommand.SearchAndPlay -> searchAndPlay(command)
        VoiceCommand.Pause -> Outcome("Paused").also { actions.pause() }
        VoiceCommand.Resume -> Outcome("Playing").also { actions.resume() }
        VoiceCommand.Next -> Outcome("Next").also { actions.next() }
        VoiceCommand.Previous -> Outcome("Previous").also { actions.previous() }
        VoiceCommand.VolumeUp -> Outcome("Volume up").also { actions.volumeStep(VOLUME_STEP) }
        VoiceCommand.VolumeDown -> Outcome("Volume down").also { actions.volumeStep(-VOLUME_STEP) }
        // VOICE FIX X2 hook: absolute volume and choice follow-ups are kept in this exhaustive dispatch.
        is VoiceCommand.SetVolume -> Outcome("Volume ${command.percent} percent").also {
            actions.setVolume((command.percent / 100f).coerceIn(0f, 1f))
        }
        is VoiceCommand.Choose -> chooseCandidate(command.number - 1)
        VoiceCommand.ChooseOther -> chooseOther()
        VoiceCommand.AddToLastPlaylist -> {
            val last = session.lastPlaylist()
            if (last == null) Outcome("I don't know which playlist you mean")
            else playlistOutcome(VoiceListAction.ADD, last.label)
        }
        is VoiceCommand.Shuffle -> Outcome(if (command.on) "Shuffle on" else "Shuffle off")
            .also { actions.setShuffle(command.on) }
        VoiceCommand.Like ->
            if (actions.likeCurrent()) Outcome("Liked") else Outcome("Nothing is playing")
        // VOICE FIX X1 hook: the adapter says exactly why a library action did or did not happen.
        is VoiceCommand.AddToPlaylist -> playlistOutcome(VoiceListAction.ADD, command.name)
        is VoiceCommand.PlayPlaylist -> playlistOutcome(VoiceListAction.PLAY, command.name)
        VoiceCommand.WhatsPlaying -> Outcome(actions.nowPlayingText() ?: "Nothing is playing")
        VoiceCommand.Unknown -> Outcome("Didn't catch that")
    }

    // VOICE FIX X1 hook: maps a [VoicePlaylistResult] to the overlay sentence. The adapter logs the
    // result kind and source code; this function never logs or stores a name.
    private suspend fun playlistOutcome(action: VoiceListAction, name: String): Outcome {
        // VOICE FIX X2 hook: use a ready playlist index before asking the adapter to resolve live state.
        var resolvedName = name
        val index = runCatching { actions.libraryIndex() }.getOrDefault(VoiceLibraryIndex.EMPTY)
        if (index.ofKinds(setOf(VoiceEntityKind.PLAYLIST)).isNotEmpty()) {
            val mode = if (action == VoiceListAction.ADD) VoiceMatchMode.EDIT else VoiceMatchMode.PLAY
            when (val match = VoiceMatcher.decide(VoiceTerms.of(name), index, mode, setOf(VoiceEntityKind.PLAYLIST))) {
                is VoiceMatchOutcome.Confident -> resolvedName = match.match.entry.input.title
                is VoiceMatchOutcome.Candidates -> {
                    val candidates = match.options.distinctBy { it.entry.input.id }.take(3).mapNotNull { option ->
                        val input = option.entry.input
                        input.source?.let { source -> VoiceListCandidate(input.title, source, input.editable, input.id) }
                    }
                    val options = VoiceListResolver.labels(candidates)
                    val refs = candidates.map { VoiceRef(VoiceEntityKind.PLAYLIST, it.title, it.id) }
                    session.rememberCandidates(refs)
                    return Outcome(VoicePlaylistMessages.forResult(action, VoicePlaylistResult.Ambiguous(options), name))
                }
                VoiceMatchOutcome.NoMatch -> Unit
            }
        }
        val result = try {
            if (action == VoiceListAction.ADD) actions.addCurrentToPlaylist(resolvedName) else actions.playPlaylist(resolvedName)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            VoicePlaylistResult.ApiFailed(VoicePlaylistMessages.CODE_API)
        }
        when (result) {
            is VoicePlaylistResult.Done -> {
                val ref = VoiceRef(VoiceEntityKind.PLAYLIST, result.playlist, "")
                session.rememberPlaylist(ref)
                if (action == VoiceListAction.PLAY) session.rememberPlayed(ref)
            }
            is VoicePlaylistResult.Ambiguous -> session.rememberCandidates(result.options.map {
                VoiceRef(VoiceEntityKind.PLAYLIST, it, "")
            })
            else -> Unit
        }
        return Outcome(
            VoicePlaylistMessages.forResult(action, result, name),
            undo = (result as? VoicePlaylistResult.Done)?.undo,
        )
    }

    /** Broad discovery requests deliberately use search ranking; the default is to ask the owner. */
    private suspend fun searchAndPlay(command: VoiceCommand.SearchAndPlay): Outcome {
        val found = try {
            actions.search(command.query)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
        val index = VoiceIndexBuilder.build(0L, found.distinctBy { it.videoId }.map {
            VoiceIndexInput(VoiceEntityKind.SONG, it.title, it.videoId, artist = it.artist)
        })
        val outcome = VoiceMatcher.decide(VoiceTerms.of(command.query), index, VoiceMatchMode.PLAY, setOf(VoiceEntityKind.SONG))
        val choices = when (outcome) {
            // Discovery words ("upbeat", "for studying") rarely appear in a title, so the text matcher
            // cannot judge them. The search service already ranked the hits for this request: offer
            // its top results in its own order rather than claiming nothing was found.
            VoiceMatchOutcome.NoMatch -> found.distinctBy { it.videoId }.take(VoiceMatchThresholds.MAX_OPTIONS)
            is VoiceMatchOutcome.Confident -> listOfNotNull(found.firstOrNull { it.videoId == outcome.match.entry.input.id })
            is VoiceMatchOutcome.Candidates -> outcome.options.mapNotNull { match -> found.firstOrNull { it.videoId == match.entry.input.id } }.take(3)
        }
        if (choices.isEmpty()) return Outcome("Couldn't find anything for that")
        if (config.confirmBeforePlay() || outcome is VoiceMatchOutcome.Candidates) {
            return Outcome("Here are some options", choices = choices)
        }
        val selected = choices.first()
        actions.playSong(selected)
        session.rememberPlayed(VoiceRef(VoiceEntityKind.SONG, selected.title, selected.videoId))
        return Outcome("Playing " + label(selected), undo = { actions.previous() })
    }

    private suspend fun play(command: VoiceCommand.Play): Outcome {
        // VOICE FIX X2 hook: canonicalise against the in-memory index before search when available.
        var title = command.title
        var requestedArtist = command.artist
        var artistOnly = false
        val library = runCatching { actions.libraryIndex() }.getOrDefault(VoiceLibraryIndex.EMPTY)
        val kinds = setOf(VoiceEntityKind.PLAYLIST, VoiceEntityKind.SONG, VoiceEntityKind.ARTIST)
        if (library.entries.isNotEmpty()) {
            val words = VoiceText.words(listOfNotNull(command.title, command.artist).joinToString(" "))
            val hint = VoiceTypeHint.from(words, command.artist != null)
            when (val matched = VoiceMatcher.decide(
                VoiceTerms.of(command.title), library, VoiceMatchMode.PLAY, kinds, hint,
                command.artist?.let { VoiceTerms.of(it) },
            )) {
                is VoiceMatchOutcome.Confident -> {
                    val input = matched.match.entry.input
                    if (input.kind == VoiceEntityKind.PLAYLIST) {
                        return playlistOutcome(VoiceListAction.PLAY, input.title)
                    } else if (input.kind == VoiceEntityKind.ARTIST) {
                        requestedArtist = input.title
                        artistOnly = true
                    } else {
                        title = input.title
                    }
                }
                is VoiceMatchOutcome.Candidates -> {
                    val candidates = matched.options.take(VoiceMatchThresholds.MAX_OPTIONS)
                    val refs = candidates.map { option ->
                        val input = option.entry.input
                        VoiceRef(input.kind, input.title, input.id)
                    }
                    val labels = candidates.map { option ->
                        val input = option.entry.input
                        when (input.kind) {
                            VoiceEntityKind.PLAYLIST -> "playlist " + input.title
                            VoiceEntityKind.SONG -> "song " + input.title + if (input.artist.isBlank()) "" else " by " + input.artist
                            VoiceEntityKind.ARTIST -> "artist " + input.title
                            VoiceEntityKind.ALBUM -> "album " + input.title
                        }
                    }
                    return Outcome("Did you mean " + labels.joinToString(", ") + "? Say the first one.", candidateRefs = refs)
                }
                VoiceMatchOutcome.NoMatch -> Unit
            }
        }
        val query = if (artistOnly) requestedArtist.orEmpty() else listOfNotNull(title, requestedArtist).joinToString(" ")
        val found = try {
            actions.search(query)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emptyList()
        }
        val unique = found.distinctBy { it.videoId }.take(MAX_CANDIDATES)
        val index = VoiceIndexBuilder.build(0L, unique.map {
            VoiceIndexInput(VoiceEntityKind.SONG, if (artistOnly) it.artist else it.title, it.videoId, artist = it.artist)
        })
        val queryTerms = VoiceTerms.of(if (artistOnly) requestedArtist.orEmpty() else title)
        val artistTerms = if (artistOnly) null else requestedArtist?.let { VoiceTerms.of(it) }
        val transientMatches = VoiceMatcher.rank(queryTerms, index, setOf(VoiceEntityKind.SONG), artist = artistTerms)
        val matchOutcome = VoiceMatcher.decideRanked(transientMatches, VoiceMatchMode.PLAY)
        val ranked = when (matchOutcome) {
            // The matcher drops hits below its floor, but these came back from the search service for
            // this very request. Keep the long-standing rule: a weak best hit is offered (top three,
            // best first), or played anyway when "ask before playing" is off. Only an empty result
            // list means "couldn't find that".
            VoiceMatchOutcome.NoMatch -> {
                if (unique.isEmpty()) return Outcome("Couldn't find that")
                val weakQuery = if (artistOnly) requestedArtist.orEmpty() else title
                val weakArtist = if (artistOnly) null else requestedArtist
                val weakRanked = (if (artistOnly) unique else VoiceSongMatcher.rank(unique, weakQuery, weakArtist).map { it.first })
                    .take(VoiceMatchThresholds.MAX_OPTIONS)
                if (config.confirmBeforePlay()) return Outcome("Did you mean...?", choices = weakRanked)
                weakRanked
            }
            is VoiceMatchOutcome.Confident -> listOfNotNull(unique.firstOrNull { it.videoId == matchOutcome.match.entry.input.id })
            is VoiceMatchOutcome.Candidates -> matchOutcome.options.mapNotNull { option ->
                unique.firstOrNull { it.videoId == option.entry.input.id }
            }.take(3)
        }
        if (ranked.isEmpty()) return Outcome("Couldn't find that")
        if (matchOutcome is VoiceMatchOutcome.Candidates) return Outcome("Did you mean...?", choices = ranked)
        val selected = ranked.first()
        actions.playSong(selected)
        session.rememberPlayed(VoiceRef(VoiceEntityKind.SONG, selected.title, selected.videoId))
        return Outcome("Playing " + label(selected), undo = { actions.previous() })
    }

    // VOICE FIX X2 hook: follow-up selection only acts on a live remembered candidate.
    private suspend fun chooseCandidate(zeroBased: Int): Outcome = chooseRef(session.choose(zeroBased))

    private suspend fun chooseOther(): Outcome = chooseRef(session.other())

    private suspend fun chooseRef(ref: VoiceRef?): Outcome {
        if (ref == null) return Outcome("Nothing to choose from")
        return when (ref.kind) {
            VoiceEntityKind.SONG -> {
                val found = try {
                    actions.search(ref.label)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    emptyList()
                }
                val song = found.firstOrNull { it.videoId == ref.id }
                if (song == null) Outcome("Couldn't find that") else {
                    actions.playSong(song)
                    session.rememberPlayed(ref)
                    Outcome("Playing " + label(song), undo = { actions.previous() })
                }
            }
            VoiceEntityKind.PLAYLIST -> playlistOutcome(VoiceListAction.PLAY, ref.label)
            VoiceEntityKind.ARTIST, VoiceEntityKind.ALBUM -> Outcome("Nothing to choose from")
        }
    }

    private fun label(song: VoiceSong): String =
        if (song.artist.isBlank()) song.title else song.title + " - " + song.artist

    private suspend fun publishDone(heard: String, outcome: Outcome) {
        // VOICE FIX X2 hook: the same song options shown by the overlay back the spoken choices.
        if (outcome.candidateRefs != null) {
            session.rememberCandidates(outcome.candidateRefs)
        } else if (outcome.choices.isNotEmpty()) {
            session.rememberCandidates(outcome.choices.map { VoiceRef(VoiceEntityKind.SONG, it.title, it.videoId) })
        }
        synchronized(lock) {
            undoAction = outcome.undo
            pendingChoices = outcome.choices
        }
        _state.value = DesktopVoiceState.Done(
            heard = heard,
            result = outcome.message,
            canUndo = outcome.undo != null,
            choices = outcome.choices,
        )
        delay(if (outcome.choices.isEmpty()) doneMillis else choiceMillis)
        reset()
    }

    private suspend fun fail(code: String) {
        DesktopTrackLog.log("voice: error=$code")
        _state.value = DesktopVoiceState.Failed(code, VoiceMessages.forCode(code))
        delay(errorMillis)
        reset()
    }

    private fun startEscWatcher() {
        escWatcher?.cancel()
        escWatcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (runCatching { escapeDown() }.getOrDefault(false)) {
                    forceIdle("esc")
                    break
                }
                delay(escPollMillis)
            }
        }
    }

    /** Back to IDLE no matter what: cancels the running work and drops every string. */
    private fun forceIdle(reason: String) {
        cancelled = true
        job?.cancel()
        job = null
        if (reason == "watchdog") DesktopTrackLog.log("voice: watchdog returned to idle")
        reset()
    }

    private fun reset() {
        synchronized(lock) {
            held = false
            cancelled = false
            busy = false
            undoAction = null
            pendingChoices = emptyList()
        }
        escWatcher?.cancel()
        escWatcher = null
        watchdog?.cancel()
        watchdog = null
        _state.value = DesktopVoiceState.Idle
    }

    companion object {
        const val MAX_RECORD_MILLIS = 12_000
        const val DONE_MILLIS = 2_500L
        const val CHOICE_MILLIS = 8_000L
        const val ERROR_MILLIS = 3_000L
        const val UNDONE_MILLIS = 1_200L
        const val WATCHDOG_MILLIS = 30_000L
        const val ESC_POLL_MILLIS = 50L
        const val VOLUME_STEP = 0.1f
        const val MAX_CANDIDATES = 10

        /** The chosen folder first, then the folder this app downloads into. Only a complete model counts. */
        fun defaultFindModel(chosen: String?): VoiceModelFiles? {
            val dirs = listOfNotNull(
                chosen?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() },
                runCatching { DesktopVoicePaths.modelDir() }.getOrNull(),
            )
            for (dir in dirs) {
                val scan = DesktopVoiceModelLocator.check(dir)
                if (scan is VoiceModelScan.Ready) return scan.files
            }
            return null
        }
    }
}
