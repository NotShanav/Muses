package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * VOICE FIX X1: result-to-message mapping, the "playable but not editable" decision over fake
 * candidate lists (local / user / shelf), and the library-ready wait with a fake clock. Pure: no
 * microphone, window, model, network or disk.
 */
class DesktopVoiceLibraryOpsTest {

    private fun local(title: String) = VoiceListCandidate(title, VoiceListSource.LOCAL, editable = true, id = "local-$title")
    private fun user(title: String) = VoiceListCandidate(title, VoiceListSource.USER, editable = true, id = "VL-user-$title")
    private fun shelf(title: String) = VoiceListCandidate(title, VoiceListSource.SHELF, editable = false, id = "VL-shelf-$title")

    private val library = listOf(
        local("Gym"),
        user("Road Trip"),
        user("Workout"),
        shelf("Chill Vibes"),
        shelf("Study Beats"),
    )

    // ── Messages ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyResultHasAnAccurateMessage() {
        fun add(r: VoicePlaylistResult, name: String = "x") = VoicePlaylistMessages.forResult(VoiceListAction.ADD, r, name)
        fun play(r: VoicePlaylistResult, name: String = "x") = VoicePlaylistMessages.forResult(VoiceListAction.PLAY, r, name)

        assertEquals("Added Yellow to Gym", add(VoicePlaylistResult.Done("Gym", VoiceListSource.USER, "Yellow")))
        assertEquals("Added this song to Gym", add(VoicePlaylistResult.Done("Gym", VoiceListSource.USER)))
        assertEquals(
            "Yellow is already in Gym",
            add(VoicePlaylistResult.Done("Gym", VoiceListSource.LOCAL, "Yellow", alreadyThere = true)),
        )
        assertEquals("Playing Gym", play(VoicePlaylistResult.Done("Gym", VoiceListSource.SHELF)))
        assertEquals("Chill Vibes isn't one of your editable playlists", add(VoicePlaylistResult.NotEditable("Chill Vibes")))
        assertEquals("Nothing is playing", add(VoicePlaylistResult.NothingPlaying))
        assertTrue(add(VoicePlaylistResult.LibraryNotLoaded).startsWith("Your library is still loading"))
        assertEquals("Couldn't find an editable playlist called Jazz", add(VoicePlaylistResult.NotFound, "Jazz"))
        assertEquals("Couldn't find a playlist called Jazz", play(VoicePlaylistResult.NotFound, "Jazz"))
        assertEquals(
            "More than one playlist matches: A, B. Say the full name.",
            add(VoicePlaylistResult.Ambiguous(listOf("A", "B"))),
        )
        assertEquals("That playlist is empty", play(VoicePlaylistResult.ApiFailed(VoicePlaylistMessages.CODE_EMPTY)))
        assertEquals(
            "That track can't go in an account playlist",
            add(VoicePlaylistResult.ApiFailed(VoicePlaylistMessages.CODE_TRACK)),
        )
        assertEquals("Couldn't reach your playlist. Try again", add(VoicePlaylistResult.ApiFailed(VoicePlaylistMessages.CODE_API)))
    }

    @Test
    fun ambiguousMessageListsAtMostThreeOptions() {
        val text = VoicePlaylistMessages.forResult(
            VoiceListAction.ADD, VoicePlaylistResult.Ambiguous(listOf("A", "B", "C", "D")), "x",
        )
        assertEquals("More than one playlist matches: A, B, C. Say the full name.", text)
    }

    @Test
    fun logLinesCarryKindsAndSourceCodesOnly() {
        val notEditable = VoicePlaylistResult.NotEditable("Secret Name")
        val line = VoiceListLog.line(VoiceListAction.ADD, notEditable, VoiceListSource.SHELF)
        assertEquals("voice: playlist action=add result=not_editable source=shelf", line)
        assertFalse(line.contains("Secret"))
        val done = VoicePlaylistResult.Done("Secret Name", VoiceListSource.USER, "Secret Song")
        val doneLine = VoiceListLog.line(VoiceListAction.PLAY, done, VoiceListSource.USER)
        assertEquals("voice: playlist action=play result=done source=user", doneLine)
        assertEquals("voice: playlist action=add result=nothing_playing source=none",
            VoiceListLog.line(VoiceListAction.ADD, VoicePlaylistResult.NothingPlaying, null))
        assertEquals("voice: playlist action=play result=api_failed:browse source=shelf",
            VoiceListLog.line(VoiceListAction.PLAY, VoicePlaylistResult.ApiFailed("browse"), VoiceListSource.SHELF))
    }

    // ── "Playable but not editable" ──────────────────────────────────────────────────────────────

    @Test
    fun anOwnedListIsEditedAndALocalOneToo() {
        val owned = VoiceListResolver.resolve("road trip", library, forEdit = true)
        assertEquals(VoiceListSource.USER, assertIs<VoiceListPick.Match>(owned).candidate.source)
        val onThisComputer = VoiceListResolver.resolve("gym", library, forEdit = true)
        assertEquals(VoiceListSource.LOCAL, assertIs<VoiceListPick.Match>(onThisComputer).candidate.source)
    }

    @Test
    fun aSavedListIsPlayableButNotEditable() {
        val edit = VoiceListResolver.resolve("chill vibes", library, forEdit = true)
        assertEquals("Chill Vibes", assertIs<VoiceListPick.NotEditable>(edit).candidate.title)
        assertEquals(
            VoicePlaylistResult.NotEditable("Chill Vibes"),
            VoiceListResolver.toResult(edit),
        )
        val play = VoiceListResolver.resolve("chill vibes", library, forEdit = false)
        val matched = assertIs<VoiceListPick.Match>(play).candidate
        assertEquals(VoiceListSource.SHELF, matched.source)
    }

    @Test
    fun nothingCloseIsNotFound() {
        val pick = VoiceListResolver.resolve("jazz classics", library, forEdit = true)
        assertTrue(pick is VoiceListPick.None)
        assertEquals(VoicePlaylistResult.NotFound, VoiceListResolver.toResult(pick))
        assertTrue(VoiceListResolver.resolve("", library, forEdit = true) is VoiceListPick.None)
        assertTrue(VoiceListResolver.resolve("gym", emptyList(), forEdit = true) is VoiceListPick.None)
    }

    @Test
    fun missingLibraryResultComesFromANullPick() {
        assertEquals(VoicePlaylistResult.LibraryNotLoaded, VoiceListResolver.toResult(null))
        assertEquals(null, VoiceListResolver.toResult(VoiceListPick.Match(local("Gym"))))
    }

    // ── Spelling, emoji, "&" ─────────────────────────────────────────────────────────────────────

    @Test
    fun spellingDifferencesStillMatch() {
        val pick = VoiceListResolver.resolve("work out", library, forEdit = true)
        assertEquals("Workout", assertIs<VoiceListPick.Match>(pick).candidate.title)
    }

    @Test
    fun emojiInTheTitleDoesNotBreakMatching() {
        val fire = "\uD83D\uDD25"
        val list = listOf(user("$fire Workout $fire"), user("Road Trip"))
        val pick = VoiceListResolver.resolve("workout", list, forEdit = true)
        assertEquals("$fire Workout $fire", assertIs<VoiceListPick.Match>(pick).candidate.title)
    }

    @Test
    fun ampersandIsHeardAsAnd() {
        val list = listOf(user("Rock & Roll"), user("Road Trip"))
        val pick = VoiceListResolver.resolve("rock and roll", list, forEdit = true)
        assertEquals("Rock & Roll", assertIs<VoiceListPick.Match>(pick).candidate.title)
    }

    // ── Confidence, with a fake scorer ───────────────────────────────────────────────────────────

    private fun scorer(vararg scores: Pair<String, Double>): (String, String) -> Double {
        val table = scores.toMap()
        return { _, title -> table[title] ?: 0.0 }
    }

    @Test
    fun editsAreStricterThanPlayback() {
        val list = listOf(user("A"))
        val s = scorer("A" to 0.80)
        assertTrue(VoiceConfidence.EDIT_MIN > VoiceConfidence.PLAY_MIN)
        assertEquals("A", assertIs<VoiceListPick.Match>(VoiceListResolver.resolve("x", list, false, s)).candidate.title)
        // The same 0.80 is never acted on for an edit: the owner is asked.
        assertEquals(listOf("A"), assertIs<VoiceListPick.Several>(VoiceListResolver.resolve("x", list, true, s)).titles)
    }

    @Test
    fun aWeakMatchOnANonEditableListIsNeitherEditedNorNamed() {
        val list = listOf(shelf("S"))
        val pick = VoiceListResolver.resolve("x", list, true, scorer("S" to 0.80))
        assertTrue(pick is VoiceListPick.None)
    }

    @Test
    fun belowTheFloorIsNothingAtAll() {
        val pick = VoiceListResolver.resolve("x", listOf(user("A")), false, scorer("A" to 0.60))
        assertTrue(pick is VoiceListPick.None)
    }

    @Test
    fun closeRunnerUpIsAskedNeverSilentlyPicked() {
        val list = listOf(user("A"), user("B"))
        val close = scorer("A" to 0.95, "B" to 0.92)
        assertEquals(listOf("A", "B"), assertIs<VoiceListPick.Several>(VoiceListResolver.resolve("x", list, false, close)).titles)
        assertEquals(listOf("A", "B"), assertIs<VoiceListPick.Several>(VoiceListResolver.resolve("x", list, true, close)).titles)
    }

    @Test
    fun editsNeedABiggerMarginThanPlayback() {
        val list = listOf(user("A"), user("B"))
        val gap = scorer("A" to 0.95, "B" to 0.89)
        assertEquals("A", assertIs<VoiceListPick.Match>(VoiceListResolver.resolve("x", list, false, gap)).candidate.title)
        assertIs<VoiceListPick.Several>(VoiceListResolver.resolve("x", list, true, gap))
    }

    @Test
    fun aNonEditableWinnerWithACloseEditableRivalIsStillAskedAbout() {
        val list = listOf(shelf("Workout Saved"), user("Workout"))
        val pick = VoiceListResolver.resolve("x", list, true, scorer("Workout Saved" to 0.96, "Workout" to 0.94))
        assertEquals(listOf("Workout Saved", "Workout"), assertIs<VoiceListPick.Several>(pick).titles)
    }

    @Test
    fun sameTitleInTwoPlacesIsToldApartByWhereItLives() {
        val list = listOf(local("Gym"), user("Gym"))
        val pick = VoiceListResolver.resolve("gym", list, true)
        assertEquals(
            listOf("Gym (on this computer)", "Gym (your account)"),
            assertIs<VoiceListPick.Several>(pick).titles,
        )
    }

    // ── Library wait, fake clock ─────────────────────────────────────────────────────────────────

    @Test
    fun readyLibraryNeedsNoWaitAndNoRefresh() = runBlocking {
        var refreshes = 0
        val ok = VoiceLibraryWait.await(isReady = { true }, refresh = { refreshes++ }, now = { 0L }, pause = { })
        assertTrue(ok)
        assertEquals(0, refreshes)
    }

    @Test
    fun libraryThatArrivesInTimeIsWaitedForAfterOneRefresh() = runBlocking {
        var clock = 0L
        var checks = 0
        var refreshes = 0
        val ok = VoiceLibraryWait.await(
            isReady = { checks++ >= 3 },
            refresh = { refreshes++ },
            timeoutMillis = 4_000L,
            pollMillis = 100L,
            now = { clock },
            pause = { clock += it },
        )
        assertTrue(ok)
        assertEquals(1, refreshes)
        assertEquals(300L, clock)
    }

    @Test
    fun libraryThatNeverArrivesGivesUpAfterTheBound() = runBlocking {
        var clock = 0L
        var refreshes = 0
        val ok = VoiceLibraryWait.await(
            isReady = { false },
            refresh = { refreshes++ },
            timeoutMillis = 4_000L,
            pollMillis = 100L,
            now = { clock },
            pause = { clock += it },
        )
        assertFalse(ok)
        assertEquals(1, refreshes)
        assertTrue(clock >= 4_000L, "clock=$clock")
        assertTrue(clock <= 4_100L, "clock=$clock")
    }
}
