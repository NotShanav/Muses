package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VOICE FIX X2 (Part A): short-lived context with a fake clock, follow-up phrases, self-repair and
 * absolute-volume numbers. Pure: no microphone, window, model, network or disk.
 */
class DesktopVoiceSessionTest {

    private fun ref(label: String, kind: VoiceEntityKind = VoiceEntityKind.SONG) = VoiceRef(kind, label, "id-$label")

    // ── Session ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun candidatesCanBeChosenByPositionAndAreCapped() {
        val s = VoiceSession(now = { 0L })
        s.rememberCandidates(listOf(ref("a"), ref("b"), ref("c"), ref("d")))
        assertEquals(3, s.lastCandidates().size)
        assertEquals(ref("a"), s.choose(0))
        assertEquals(ref("c"), s.choose(2))
        assertNull(s.choose(3))
        assertNull(s.choose(-1))
    }

    @Test
    fun everythingExpiresAfterTwoMinutes() {
        var t = 0L
        val s = VoiceSession(now = { t })
        s.rememberPlayed(ref("a"))
        s.rememberCandidates(listOf(ref("a"), ref("b")))
        s.rememberPlaylist(ref("Gym", VoiceEntityKind.PLAYLIST))
        t = VoiceSession.TTL_MILLIS
        assertEquals(ref("a"), s.lastPlayed())
        assertEquals(2, s.lastCandidates().size)
        assertEquals("Gym", s.lastPlaylist()?.label)
        t = VoiceSession.TTL_MILLIS + 1
        assertNull(s.lastPlayed())
        assertTrue(s.lastCandidates().isEmpty())
        assertNull(s.lastPlaylist())
        assertNull(s.choose(0))
        assertTrue(s.isEmpty())
    }

    @Test
    fun theOtherOneIsTheCandidateThatWasNotJustPlayed() {
        val s = VoiceSession(now = { 0L })
        s.rememberCandidates(listOf(ref("a"), ref("b")))
        assertNull(s.other(), "with nothing played, 'the other one' would be a guess")
        s.rememberPlayed(ref("a"))
        assertEquals(ref("b"), s.other())
        s.rememberPlayed(ref("b"))
        assertEquals(ref("a"), s.other())
    }

    @Test
    fun playingAPlaylistAlsoRemembersItAsTheLastPlaylist() {
        val s = VoiceSession(now = { 0L })
        s.rememberPlayed(ref("Gym", VoiceEntityKind.PLAYLIST))
        assertEquals("Gym", s.lastPlaylist()?.label)
        s.rememberPlayed(ref("Yellow"))
        assertEquals("Gym", s.lastPlaylist()?.label, "playing a song must not forget the playlist")
    }

    @Test
    fun clearForgetsEverythingAndNothingPrintsANameWhenLogged() {
        val s = VoiceSession(now = { 0L })
        s.rememberPlayed(ref("Secret Song"))
        s.rememberCandidates(listOf(ref("Secret Song")))
        assertFalse(s.toString().contains("Secret"))
        assertFalse(ref("Secret Song").toString().contains("Secret"))
        s.clear()
        assertTrue(s.isEmpty())
        assertNull(s.lastPlayed())
        assertTrue(s.lastCandidates().isEmpty())
    }

    // ── Follow-up phrases ────────────────────────────────────────────────────────────────────────

    private fun choice(text: String): VoiceChoice? = VoiceChoicePhrases.parse(VoiceText.words(text))

    @Test
    fun followUpPhrasesAreRecognisedOnlyWhenTheWholePhraseIsOne() {
        assertEquals(VoiceChoice.Index(0), choice("the first one"))
        assertEquals(VoiceChoice.Index(0), choice("1st"))
        assertEquals(VoiceChoice.Index(1), choice("second one"))
        assertEquals(VoiceChoice.Index(1), choice("number two"))
        assertEquals(VoiceChoice.Index(2), choice("the third one"))
        assertEquals(VoiceChoice.Index(1), choice("i mean the second one"))
        assertEquals(VoiceChoice.Other, choice("no the other one"))
        assertEquals(VoiceChoice.Other, choice("the other one"))
        assertEquals(VoiceChoice.Other, choice("not that one"))
        assertNull(choice("next"))
        assertNull(choice("play the first one"))
        assertNull(choice("the first one hundred songs"))
        assertNull(choice(""))
    }

    @Test
    fun theLastPlaylistCanBeReferredToByThatOneOrTheSamePlaylist() {
        assertTrue(VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words("that one")))
        assertTrue(VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words("the same playlist")))
        assertTrue(VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words("that playlist")))
        assertFalse(VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words("my workout")))
    }

    // ── Self-repair ──────────────────────────────────────────────────────────────────────────────

    private fun repair(text: String): String = VoiceSelfRepair.apply(VoiceText.words(text)).joinToString(" ")

    @Test
    fun theCorrectionReplacesWhatCameBefore() {
        assertEquals("play the road trip playlist", repair("play my, um, workout, actually the road trip playlist"))
        assertEquals("add this to my workout playlist", repair("add this to my gym no i mean my workout playlist"))
        assertEquals("volume to fifty", repair("volume to forty actually fifty"))
        assertEquals("play clocks by coldplay", repair("play yellow sorry play clocks by coldplay"))
        assertEquals("previous", repair("next wait previous"))
        assertEquals("the other one", repair("no the other one"))
    }

    @Test
    fun fillersGoButTitleWordsStay() {
        assertEquals("play yellow by coldplay", repair("play uh yellow by like coldplay"))
        assertEquals("pause", repair("uh pause"))
        assertEquals("play no scrubs", repair("play no scrubs"))
        assertEquals("play sorry by justin bieber", repair("play sorry by justin bieber"))
        assertEquals("play wait for it", repair("play wait for it"))
        assertEquals("play like a prayer", repair("play like a prayer"))
        assertEquals("i like this", repair("i like this"))
        assertEquals("no shuffle", repair("no shuffle"))
    }

    // ── Absolute volume ──────────────────────────────────────────────────────────────────────────

    private fun volume(text: String): Int? = VoiceNumbers.volumePercent(VoiceText.words(text))

    @Test
    fun absoluteVolumeUnderstandsWordsAndDigits() {
        assertEquals(40, volume("volume to forty"))
        assertEquals(40, volume("volume to 40"))
        assertEquals(50, volume("set volume to fifty percent"))
        assertEquals(30, volume("turn the volume to thirty"))
        assertEquals(70, volume("volume at seventy"))
        assertEquals(20, volume("make the volume 20"))
        assertEquals(100, volume("set the volume to one hundred"))
        assertEquals(0, volume("volume to zero"))
        assertEquals(30, volume("turn it to thirty"))
    }

    @Test
    fun absoluteVolumeRejectsOtherNumbers() {
        assertNull(volume("volume to 140"))
        assertNull(volume("set an alarm for seven"))
        assertNull(volume("turn the volume up"))
        assertNull(volume("play top five"))
        assertNull(volume("volume to forty and fifty"))
    }
}
