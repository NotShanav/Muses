package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * VOICE FIX X2: runs the REAL parser over `src/test/resources/voice/heldout_phrases.tsv` and reports
 * pass rate, MISS rate (said something real, got Unknown) and WRONG-ACTION rate (executed something
 * other than what was meant: far worse than a miss). Fails when the wrong-action rate is above
 * [MAX_WRONG_RATE] and lists every failing phrase in the message. Fix the code, not the phrase,
 * unless the expectation itself was wrong (then say why in HANDOFF.md).
 *
 * No microphone, window, model, network or disk beyond the resource file.
 */
class DesktopVoiceEvalTest {

    private class Case(val line: Int, val phrase: String, val action: String, val args: String)

    private class Corpus(val playlists: List<String>, val cases: List<Case>)

    private fun load(): Corpus {
        val stream = checkNotNull(DesktopVoiceEvalTest::class.java.getResourceAsStream("/voice/heldout_phrases.tsv")) {
            "voice/heldout_phrases.tsv is missing from the test resources"
        }
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        var playlists: List<String> = emptyList()
        val cases = ArrayList<Case>()
        for ((i, raw) in lines.withIndex()) {
            if (raw.isBlank()) continue
            if (raw.startsWith("#playlists\t")) {
                playlists = raw.split('\t').drop(1).filter { it.isNotBlank() }
                continue
            }
            if (raw.startsWith("#")) continue
            val parts = raw.split('\t')
            check(parts.size >= 2) { "heldout_phrases.tsv line " + (i + 1) + ": expected phrase<TAB>action<TAB>args" }
            cases.add(Case(i + 1, parts[0], parts[1], if (parts.size > 2) parts[2] else ""))
        }
        return Corpus(playlists, cases)
    }

    /**
     * Command to (action name, arguments). Objects (Pause, Next, Choose-style cases added later, ...)
     * fall through to their class name with no arguments. When a new command CLASS WITH DATA is added
     * (SetVolume, Choose), add a branch here so its arguments are compared too.
     */
    private fun describe(command: VoiceCommand): Pair<String, String> = when (command) {
        is VoiceCommand.Play -> "Play" to (command.title + "|" + (command.artist ?: ""))
        is VoiceCommand.SearchAndPlay -> "SearchAndPlay" to command.query
        is VoiceCommand.Shuffle -> "Shuffle" to (if (command.on) "on" else "off")
        is VoiceCommand.AddToPlaylist -> "AddToPlaylist" to command.name
        is VoiceCommand.PlayPlaylist -> "PlayPlaylist" to command.name
        // VOICE FIX X2 hook: compare the arguments of the new data commands in held-out evaluation.
        is VoiceCommand.SetVolume -> "SetVolume" to command.percent.toString()
        is VoiceCommand.Choose -> "Choose" to command.number.toString()
        else -> (command::class.simpleName ?: "Unknown") to ""
    }

    private fun normArgs(args: String): String =
        args.split('|').joinToString("|") { DesktopVoiceCommandParser.normalize(it) }

    @Test
    fun heldOutPhrasesReportMetricsAndKeepWrongActionsRare() {
        val corpus = load()
        assertTrue(corpus.cases.size >= 120, "the held-out corpus must not shrink below 120 phrases (has " + corpus.cases.size + ")")
        assertTrue(corpus.playlists.isNotEmpty(), "the #playlists line is missing")

        var passed = 0
        var wrong = 0
        var missed = 0
        val failures = ArrayList<String>()
        for (case in corpus.cases) {
            val command = DesktopVoiceCommandParser.parse(case.phrase, corpus.playlists)
            val (action, args) = describe(command)
            val sameAction = action == case.action
            val sameArgs = case.args.isBlank() && args.isBlank() || normArgs(case.args) == normArgs(args)
            when {
                sameAction && sameArgs -> passed += 1
                action == "Unknown" -> {
                    missed += 1
                    failures.add("[MISS ] line " + case.line + ": \"" + case.phrase + "\" expected " + case.action + " " + case.args)
                }
                else -> {
                    wrong += 1
                    failures.add(
                        "[WRONG] line " + case.line + ": \"" + case.phrase + "\" expected " + case.action + " " + case.args +
                            " but got " + action + " " + args,
                    )
                }
            }
        }
        val total = corpus.cases.size
        val wrongRate = wrong.toDouble() / total
        val report = "voice eval: total=" + total + " passed=" + passed + " (" + percent(passed, total) + ")" +
            " missed=" + missed + " (" + percent(missed, total) + ")" +
            " wrongAction=" + wrong + " (" + percent(wrong, total) + ")"
        println(report)
        failures.forEach { println(it) }
        assertTrue(
            wrongRate <= MAX_WRONG_RATE,
            report + "\nwrong-action rate must be <= " + percent(MAX_WRONG_RATE) + ". Failing phrases:\n" + failures.joinToString("\n"),
        )
    }

    private fun percent(part: Int, total: Int): String = percent(part.toDouble() / total)

    private fun percent(fraction: Double): String = (Math.round(fraction * 1000.0) / 10.0).toString() + "%"

    private companion object {
        const val MAX_WRONG_RATE = 0.03
    }
}
