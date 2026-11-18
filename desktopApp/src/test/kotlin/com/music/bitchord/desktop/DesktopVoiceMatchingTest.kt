package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * VOICE FIX X2 (Part A): normaliser, phonetic key, alias table, index, matcher thresholds and type
 * priors. Pure: no microphone, window, model, network or disk.
 */
class DesktopVoiceMatchingTest {

    private fun playlist(title: String, id: String = "pl-$title") =
        VoiceIndexInput(VoiceEntityKind.PLAYLIST, title, id, source = VoiceListSource.USER, editable = true)

    private fun song(title: String, artist: String) =
        VoiceIndexInput(VoiceEntityKind.SONG, title, "song-$title-$artist", artist = artist)

    private fun artist(name: String) = VoiceIndexInput(VoiceEntityKind.ARTIST, name, "artist-$name")

    private val library = VoiceIndexBuilder.build(
        1L,
        listOf(
            playlist("Workout"), playlist("Road Trip"), playlist("Chill Vibes"), playlist("Gym"),
            artist("Billie Eilish"), artist("Coldplay"),
            song("Yellow", "Coldplay"), song("Clocks", "Coldplay"), song("Bad Guy", "Billie Eilish"),
        ),
    )

    private fun matchAt(title: String, score: Double): VoiceMatch =
        VoiceMatch(VoiceIndexBuilder.build(0L, listOf(playlist(title))).entries.single(), score)

    private fun songMatchAt(title: String, score: Double): VoiceMatch =
        VoiceMatch(VoiceIndexBuilder.build(0L, listOf(song(title, "Someone"))).entries.single(), score)

    // ── Normaliser ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun tokensDropEmojiPunctuationNoiseAndAccents() {
        assertEquals(listOf("rock", "and", "roll", "classics"), VoiceText.tokens("Rock & Roll Classics"))
        assertEquals(listOf("fire", "hits"), VoiceText.tokens("\uD83D\uDD25 Fire Hits"))
        assertEquals(listOf("weeknd"), VoiceText.tokens("The Weeknd"))
        assertEquals(listOf("beyonce"), VoiceText.tokens("Beyonc\u00E9"))
        assertEquals(listOf("dont", "stop"), VoiceText.words("Don't Stop"))
        assertEquals(listOf("the", "the"), VoiceText.tokens("The The"))
    }

    @Test
    fun numberWordsBecomeDigitsAndAliasesApply() {
        assertEquals(listOf("top", "5"), VoiceText.tokens("My Top Five Playlist"))
        assertEquals(listOf("21", "pilots"), VoiceText.tokens("twenty one pilots"))
        assertEquals(listOf("100"), VoiceText.numbersToDigits(listOf("one", "hundred")))
        assertEquals(listOf("40"), VoiceText.numbersToDigits(listOf("forty")))
        assertEquals(listOf("workout"), VoiceText.tokens("work out"))
        assertEquals(listOf("billie", "eilish"), VoiceText.tokens("Billy Eilish"))
    }

    // ── Phonetic key ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun phoneticKeyMakesMisheardSpellingsCollide() {
        assertEquals(VoicePhonetic.key("billie"), VoicePhonetic.key("billy"))
        assertEquals("BL", VoicePhonetic.key("billie"))
        assertEquals(VoicePhonetic.key("eilish"), VoicePhonetic.key("ilish"))
        assertEquals(VoicePhonetic.key("night"), VoicePhonetic.key("knight"))
        assertEquals(VoicePhonetic.key("phone"), VoicePhonetic.key("fone"))
        assertEquals("WRKT", VoicePhonetic.key("workout"))
        assertEquals(VoicePhonetic.key("workout"), VoicePhonetic.compactKey(listOf("work", "out")))
        assertEquals("", VoicePhonetic.key(""))
        assertEquals("", VoicePhonetic.key("123"))
    }

    // ── Scoring ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun scoreIsOneOnlyForTheSameWordsAndLowForUnrelatedOnes() {
        assertEquals(1.0, VoiceMatcher.scoreText("work out", "Workout"))
        assertEquals(1.0, VoiceMatcher.scoreText("billy eilish", "Billie Eilish"))
        assertEquals(1.0, VoiceMatcher.scoreText("rock and roll classics", "Rock & Roll Classics"))
        assertTrue(VoiceMatcher.scoreText("yellow", "Gym") < VoiceMatchThresholds.LOW)
        assertEquals(0.0, VoiceMatcher.scoreText("", "Gym"))
    }

    @Test
    fun phoneticMatchRescuesAMisspelledName() {
        val s = VoiceMatcher.scoreText("bily eylish", "Billie Eilish")
        assertTrue(s >= 0.9, "score was " + s)
        assertTrue(s <= 1.0)
    }

    // ── Index ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun indexDropsEmptyTitlesAndDuplicateIds() {
        val index = VoiceIndexBuilder.build(
            7L,
            listOf(
                playlist("Gym", id = "same"),
                playlist("Gym again", id = "same"),
                playlist("\uD83D\uDD25", id = "emoji-only"),
                playlist("Workout", id = ""),
                playlist("Workout", id = ""),
            ),
        )
        assertEquals(7L, index.version)
        // "Gym again" is a duplicate id, the emoji-only title has no words; blank ids are never deduped.
        assertEquals(3, index.entries.size)
    }

    @Test
    fun holderBuildsOncePerVersion() {
        val holder = VoiceIndexHolder()
        val inputs = listOf(playlist("Gym"), playlist("Workout"))
        holder.get(1L) { inputs }
        holder.get(1L) { error("must not rebuild for the same version") }
        assertEquals(1, holder.builds)
        holder.get(2L) { inputs }
        assertEquals(2, holder.builds)
    }

    @Test
    fun holderBySignatureRebuildsOnlyWhenContentChanges() {
        val holder = VoiceIndexHolder()
        holder.getFor(listOf(playlist("Gym"), playlist("Workout")))
        holder.getFor(listOf(playlist("Gym"), playlist("Workout")))
        assertEquals(1, holder.builds)
        holder.getFor(listOf(playlist("Gym"), playlist("Workout"), playlist("Road Trip")))
        assertEquals(2, holder.builds)
        assertEquals(3, holder.current().entries.size)
        assertEquals(2, holder.builds, "current() must never build")
        holder.clear()
        assertTrue(holder.current().entries.isEmpty())
        holder.getFor(listOf(playlist("Gym")))
        assertEquals(3, holder.builds)
    }

    // ── Decision ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMisheardPlaylistNameIsMatchedConfidentlyEvenForEdits() {
        val outcome = VoiceMatcher.decide(
            VoiceTerms.of("work out"), library, VoiceMatchMode.EDIT, kinds = setOf(VoiceEntityKind.PLAYLIST),
        )
        val confident = assertIs<VoiceMatchOutcome.Confident>(outcome)
        assertEquals("Workout", confident.match.entry.input.title)
    }

    @Test
    fun anUnknownNameReturnsNothing() {
        val outcome = VoiceMatcher.decide(
            VoiceTerms.of("zzz qqq xxx"), library, VoiceMatchMode.PLAY, kinds = setOf(VoiceEntityKind.PLAYLIST),
        )
        assertTrue(outcome === VoiceMatchOutcome.NoMatch)
    }

    @Test
    fun twoListsWithTheSameTitleAreNeverPickedSilently() {
        val index = VoiceIndexBuilder.build(
            1L,
            listOf(
                VoiceIndexInput(VoiceEntityKind.PLAYLIST, "Gym", "local-gym", source = VoiceListSource.LOCAL, editable = true),
                VoiceIndexInput(VoiceEntityKind.PLAYLIST, "Gym", "user-gym", source = VoiceListSource.USER, editable = true),
            ),
        )
        val outcome = VoiceMatcher.decide(VoiceTerms.of("gym"), index, VoiceMatchMode.PLAY)
        assertEquals(2, assertIs<VoiceMatchOutcome.Candidates>(outcome).options.size)
    }

    @Test
    fun marginAndFloorsDecideBetweenActingAndAsking() {
        fun decide(mode: VoiceMatchMode, vararg scores: Double): VoiceMatchOutcome {
            val ranked = scores.mapIndexed { i, s -> matchAt("List" + i, s) }
            return VoiceMatcher.decideRanked(ranked, mode)
        }
        // Playback.
        assertIs<VoiceMatchOutcome.Confident>(decide(VoiceMatchMode.PLAY, 0.92, 0.70))
        assertIs<VoiceMatchOutcome.Candidates>(decide(VoiceMatchMode.PLAY, 0.85, 0.82))
        assertIs<VoiceMatchOutcome.Candidates>(decide(VoiceMatchMode.PLAY, 0.75))
        assertIs<VoiceMatchOutcome.Confident>(decide(VoiceMatchMode.PLAY, 0.85))
        // Edits are stricter on both.
        assertIs<VoiceMatchOutcome.Candidates>(decide(VoiceMatchMode.EDIT, 0.88))
        assertIs<VoiceMatchOutcome.Candidates>(decide(VoiceMatchMode.EDIT, 0.95, 0.82))
        assertIs<VoiceMatchOutcome.Confident>(decide(VoiceMatchMode.EDIT, 0.95, 0.70))
        assertTrue(decideRankedEmpty() === VoiceMatchOutcome.NoMatch)
    }

    private fun decideRankedEmpty(): VoiceMatchOutcome = VoiceMatcher.decideRanked(emptyList(), VoiceMatchMode.PLAY)

    @Test
    fun candidatesAreCappedAtThree() {
        val ranked = (0 until 6).map { matchAt("List" + it, 0.70) }
        val outcome = VoiceMatcher.decideRanked(ranked, VoiceMatchMode.PLAY)
        assertEquals(VoiceMatchThresholds.MAX_OPTIONS, assertIs<VoiceMatchOutcome.Candidates>(outcome).options.size)
    }

    // ── Type priors ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun explicitWordsAndSoftLeansBecomeHints() {
        assertEquals(VoiceTypeHint(VoiceEntityKind.PLAYLIST, true), VoiceTypeHint.from(listOf("play", "my", "gym", "playlist"), false))
        assertEquals(VoiceTypeHint(VoiceEntityKind.SONG, true), VoiceTypeHint.from(listOf("the", "song", "yellow"), false))
        assertEquals(VoiceTypeHint(VoiceEntityKind.SONG, true), VoiceTypeHint.from(listOf("yellow"), true))
        assertEquals(VoiceTypeHint(VoiceEntityKind.PLAYLIST, false), VoiceTypeHint.from(listOf("my", "gym"), false))
        assertEquals(VoiceTypeHint.NONE, VoiceTypeHint.from(listOf("yellow"), false))
    }

    @Test
    fun anExplicitTypeOnlySearchesThatKind() {
        val ranked = VoiceMatcher.rank(
            VoiceTerms.of("coldplay"), library, hint = VoiceTypeHint(VoiceEntityKind.ARTIST, true),
        )
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.all { it.entry.input.kind == VoiceEntityKind.ARTIST })
    }

    @Test
    fun theDestinationOfAddToPlaylistCanOnlyBeAPlaylist() {
        // "coldplay" is an artist in the library; asked for as a destination it must not match.
        val outcome = VoiceMatcher.decide(
            VoiceTerms.of("coldplay"), library, VoiceMatchMode.EDIT, kinds = setOf(VoiceEntityKind.PLAYLIST),
        )
        assertTrue(outcome === VoiceMatchOutcome.NoMatch)
    }

    @Test
    fun aBarePlayThatIsBothAPlaylistAndASongAsksWhich() {
        val ranked = listOf(matchAt("Road Trip", 0.95), songMatchAt("Road Trip Anthem", 0.85))
        assertIs<VoiceMatchOutcome.Candidates>(VoiceMatcher.decideRanked(ranked, VoiceMatchMode.PLAY, VoiceTypeHint.NONE))
        // The owner said "playlist": no question.
        assertIs<VoiceMatchOutcome.Confident>(
            VoiceMatcher.decideRanked(ranked, VoiceMatchMode.PLAY, VoiceTypeHint(VoiceEntityKind.PLAYLIST, true)),
        )
    }

    // ── Songs and artists ────────────────────────────────────────────────────────────────────────

    @Test
    fun aSongWithItsArtistIsMatchedAndAWrongArtistIsNeverExecuted() {
        val kinds = setOf(VoiceEntityKind.SONG)
        val good = VoiceMatcher.decide(VoiceTerms.of("yellow"), library, VoiceMatchMode.PLAY, kinds, artist = VoiceTerms.of("coldplay"))
        assertEquals("Yellow", assertIs<VoiceMatchOutcome.Confident>(good).match.entry.input.title)

        val wrongArtist = VoiceMatcher.decide(VoiceTerms.of("yellow"), library, VoiceMatchMode.PLAY, kinds, artist = VoiceTerms.of("queen"))
        assertFalse(wrongArtist is VoiceMatchOutcome.Confident)
    }

    @Test
    fun aMisheardArtistIsMatchedThroughTheAliasTable() {
        val outcome = VoiceMatcher.decide(
            VoiceTerms.of("billy eilish"), library, VoiceMatchMode.PLAY, kinds = setOf(VoiceEntityKind.ARTIST),
        )
        assertEquals("Billie Eilish", assertIs<VoiceMatchOutcome.Confident>(outcome).match.entry.input.title)
    }

    // ── Privacy ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun nothingPrintsANameWhenLogged() {
        val entry = library.entries.first()
        assertFalse(entry.toString().contains("Workout"))
        assertFalse(entry.input.toString().contains("Workout"))
        assertFalse(entry.terms.toString().contains("workout"))
        assertFalse(VoiceMatch(entry, 1.0).toString().contains("Workout"))
    }
}
