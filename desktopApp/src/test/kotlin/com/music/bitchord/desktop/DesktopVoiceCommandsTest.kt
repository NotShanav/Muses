package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice V1: the rule-based command parser. Pure text in, command out. */
class DesktopVoiceCommandsTest {

    private val names = listOf("Road Trip", "Chill Vibes", "Workout", "Billie Eilish Mix", "Top 10")

    private fun p(text: String, playlists: List<String> = names): VoiceCommand =
        DesktopVoiceCommandParser.parse(text, playlists)

    private fun all(expected: VoiceCommand, vararg phrases: String) {
        for (phrase in phrases) assertEquals(expected, p(phrase), "phrase: $phrase")
    }

    // ---- normalize ----

    @Test
    fun normalizeLowersStripsPunctuationAndKeepsApostrophes() {
        assertEquals("play bad guy by billie eilish", DesktopVoiceCommandParser.normalize("Play Bad Guy, by Billie Eilish."))
        assertEquals("what's playing", DesktopVoiceCommandParser.normalize("  What\u2019s   playing?? "))
        assertEquals("ac dc", DesktopVoiceCommandParser.normalize("AC/DC"))
        assertEquals("", DesktopVoiceCommandParser.normalize("...!?"))
    }

    // ---- transport ----

    @Test
    fun pausePhrasings() = all(
        VoiceCommand.Pause,
        "pause", "Pause.", "pause the music", "pause the song", "stop", "stop the music",
        "please pause", "hey muses pause", "can you please pause", "could you pause the music please",
        "paws", "paused",
    )

    @Test
    fun resumePhrasings() = all(
        VoiceCommand.Resume,
        "resume", "resume playing", "continue", "continue playing", "unpause", "play", "play music",
        "play the music", "play it", "keep playing", "start the music", "hey muses resume",
    )

    @Test
    fun nextPhrasings() = all(
        VoiceCommand.Next,
        "next", "next song", "next track", "skip", "skip this song", "skip the track", "play the next song",
        "play next", "go to the next track", "could you skip", "Next!",
    )

    @Test
    fun previousPhrasings() = all(
        VoiceCommand.Previous,
        "previous", "previous song", "previous track", "go back", "back", "last song",
        "play the previous song", "play the last track", "go back to the previous song",
    )

    // ---- volume ----

    @Test
    fun volumeUpPhrasings() = all(
        VoiceCommand.VolumeUp,
        "volume up", "turn the volume up", "turn it up", "make it louder", "raise the volume",
        "increase the volume", "louder", "turn up the volume", "turn up", "volume up a bit",
    )

    @Test
    fun volumeDownPhrasings() = all(
        VoiceCommand.VolumeDown,
        "volume down", "turn the volume down", "turn it down", "make it quieter", "lower the volume",
        "quieter", "decrease the volume", "turn down", "turn the music down a bit",
    )

    @Test
    fun contradictoryOrUnrelatedVolumeTalkIsUnknown() {
        assertEquals(VoiceCommand.Unknown, p("turn the volume up and down"))
        assertEquals(VoiceCommand.Unknown, p("make me a sandwich"))
        assertEquals(VoiceCommand.Unknown, p("set the volume to ten"))
    }

    // ---- shuffle ----

    @Test
    fun shuffleOn() = all(
        VoiceCommand.Shuffle(true),
        "shuffle", "shuffle on", "turn shuffle on", "turn on shuffle", "enable shuffle", "start shuffling",
    )

    @Test
    fun shuffleOff() = all(
        VoiceCommand.Shuffle(false),
        "shuffle off", "turn shuffle off", "turn off shuffle", "disable shuffle", "stop shuffling",
        "don't shuffle",
    )

    @Test
    fun shuffleWithExtraWordsIsNotGuessed() {
        assertEquals(VoiceCommand.Unknown, p("shuffle my road trip playlist"))
    }

    // ---- like, what's playing ----

    @Test
    fun likePhrasings() = all(
        VoiceCommand.Like,
        "like this", "like this song", "i like this song", "love this song", "thumbs up", "like it",
        "heart this", "favorite this", "add this to favorites", "add this song to my liked songs",
        "put this in favourites",
    )

    @Test
    fun whatsPlayingPhrasings() = all(
        VoiceCommand.WhatsPlaying,
        "what's playing", "What's playing?", "whats playing", "what is playing right now", "what song is this",
        "what is this song", "who sings this", "now playing", "what's this song", "what am I listening to",
    )

    // ---- add to playlist ----

    @Test
    fun addToPlaylistUsesTheRealPlaylistName() {
        assertEquals(VoiceCommand.AddToPlaylist("Road Trip"), p("add this to road trip"))
        assertEquals(VoiceCommand.AddToPlaylist("Workout"), p("add this song to my workout playlist"))
        assertEquals(VoiceCommand.AddToPlaylist("Chill Vibes"), p("add to playlist chill vibes"))
        assertEquals(VoiceCommand.AddToPlaylist("Road Trip"), p("put this in the road trip playlist"))
        assertEquals(VoiceCommand.AddToPlaylist("Road Trip"), p("Hey Muses, please add this to Road Trip."))
        assertEquals(VoiceCommand.AddToPlaylist("Billie Eilish Mix"), p("save this to billy eilish mix"))
    }

    @Test
    fun addToUnknownPlaylistStillRecognisesTheIntent() {
        assertEquals(VoiceCommand.AddToPlaylist("jazz"), p("add this to jazz"))
        assertEquals(VoiceCommand.AddToPlaylist("anything"), p("add this to anything", emptyList()))
    }

    // ---- play song ----

    @Test
    fun playSongWithoutArtist() {
        assertEquals(VoiceCommand.Play("bad guy", null), p("play bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("Play Bad Guy."))
        assertEquals(VoiceCommand.Play("bad guy", null), p("plays bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("play me bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("start playing bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("put on bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("i want to hear bad guy"))
        assertEquals(VoiceCommand.Play("bad guy", null), p("listen to bad guy"))
    }

    @Test
    fun playSongWithArtist() {
        assertEquals(VoiceCommand.Play("bad guy", "billie eilish"), p("play bad guy by billie eilish"))
        assertEquals(VoiceCommand.Play("bad guy", "billie eilish"), p("Hey Muses, can you play Bad Guy by Billie Eilish please?"))
        assertEquals(VoiceCommand.Play("bad guy", "billie eilish"), p("please play the song bad guy by billie eilish"))
    }

    @Test
    fun playSplitsOnTheLastBy() {
        assertEquals(VoiceCommand.Play("stand by me", "ben e king"), p("Play Stand by Me by Ben E. King."))
    }

    @Test
    fun playWithNothingAfterByKeepsEverythingAsTheTitle() {
        assertEquals(VoiceCommand.Play("stand by", null), p("play stand by"))
        assertEquals(VoiceCommand.Play("by", null), p("play by"))
    }

    @Test
    fun playStandByMeIsNotSplit() {
        assertEquals(VoiceCommand.Play("stand by me", null), p("play stand by me"))
        assertEquals(VoiceCommand.Play("stay by you", null), p("play stay by you"))
    }

    @Test
    fun playKeepsApostrophesAndBareSongWord() {
        assertEquals(VoiceCommand.Play("don't stop believin", "journey"), p("play don't stop believin by journey"))
        assertEquals(VoiceCommand.Play("song 2", "blur"), p("play song 2 by blur"))
    }

    // ---- play playlist ----

    @Test
    fun playPlaylistPhrasings() {
        assertEquals(VoiceCommand.PlayPlaylist("Road Trip"), p("play my road trip playlist"))
        assertEquals(VoiceCommand.PlayPlaylist("Road Trip"), p("play playlist road trip"))
        assertEquals(VoiceCommand.PlayPlaylist("Road Trip"), p("play the playlist road trip"))
        assertEquals(VoiceCommand.PlayPlaylist("Chill Vibes"), p("play my chill vibes"))
        assertEquals(VoiceCommand.PlayPlaylist("Workout"), p("Play my workout."))
        assertEquals(VoiceCommand.PlayPlaylist("Road Trip"), p("play my road tripp playlist"))
        assertEquals(VoiceCommand.PlayPlaylist("Billie Eilish Mix"), p("play my billy eilish mix playlist"))
        assertEquals(VoiceCommand.PlayPlaylist("Top 10"), p("play my top ten playlist"))
    }

    @Test
    fun conversationalPlayPhrasingsResolveTheSamePlaylist() {
        val expected = VoiceCommand.PlayPlaylist("Workout")
        all(
            expected,
            "I'm in the mood for my workout playlist",
            "I am in the mood for my workout playlist",
            "I feel like listening to my workout playlist",
            "I feel like my workout playlist",
            "I'm feeling like listening to my workout playlist",
            "I'd love to hear my workout playlist",
            "I would like to listen to my workout playlist",
            "I could go for my workout playlist",
            "I'm up for my workout playlist",
            "I am up for my workout playlist",
            "I'm in the mood to listen to my workout playlist",
            "I was thinking of playing my workout playlist",
            "How about playing my workout playlist",
            "Can we put on my workout playlist",
            "Could we listen to my workout playlist",
            "Let's listen to my workout playlist",
            "Throw on my workout playlist",
            "Put on the workout mix",
        )
    }

    @Test
    fun broadDiscoveryPhrasingsBecomeSearchRequests() {
        assertEquals(VoiceCommand.SearchAndPlay("upbeat"), p("play something upbeat"))
        assertEquals(VoiceCommand.SearchAndPlay("relaxing music"), p("put on some relaxing music"))
        assertEquals(VoiceCommand.SearchAndPlay("music"), p("I'm craving something"))
    }

    @Test
    fun conversationalTransportAndVolumePhrasings() {
        all(VoiceCommand.Pause, "hold the music", "take a break", "pause for a moment", "kill the music")
        all(VoiceCommand.Resume, "keep it going", "start back up", "let it play", "go on")
        all(VoiceCommand.Next, "move on", "onto the next one", "another song", "next one please")
        all(VoiceCommand.Previous, "go to the last one", "the previous one")
        all(VoiceCommand.VolumeUp, "a little louder", "a bit louder", "bring the volume up")
        all(VoiceCommand.VolumeDown, "a little quieter", "a bit softer", "bring the volume down")
    }

    @Test
    fun playMyWithoutAMatchingPlaylistIsASong() {
        assertEquals(VoiceCommand.Play("my girl", null), p("play my girl"))
        assertEquals(VoiceCommand.Play("my heart will go on", "celine dion"), p("play my heart will go on by celine dion"))
    }

    @Test
    fun explicitPlaylistIntentDoesNotDependOnACompleteLibrarySnapshot() {
        // Parsing identifies what the user asked for. The action layer resolves the name against
        // the live library and reports a missing playlist; it must not say it failed to hear them.
        assertEquals(VoiceCommand.PlayPlaylist("jazz"), p("play playlist jazz"))
        assertEquals(VoiceCommand.PlayPlaylist("sleepy"), p("play my sleepy playlist", emptyList()))
        assertEquals(VoiceCommand.AddToPlaylist("jazz"), p("add this to my jazz playlist", emptyList()))
    }

    @Test
    fun ambiguousPlaylistIsNotGuessed() {
        val two = listOf("Chill Vibes", "Chill Hits")
        assertEquals(VoiceCommand.PlayPlaylist("chill"), p("play my chill playlist", two))
        assertEquals(VoiceCommand.PlayPlaylist("Chill Vibes"), p("play my chill vibes playlist", two))
    }

    // ---- unknown ----

    @Test
    fun unknownSpeechIsUnknown() {
        for (phrase in listOf("", "   ", "hello there", "banana", "what is the meaning of life", "muses", "please", "hey muses")) {
            assertEquals(VoiceCommand.Unknown, p(phrase), "phrase: $phrase")
        }
    }

    // ---- playlist matcher ----

    @Test
    fun playlistScoreExactExtraWordsTypoAndNoMatch() {
        val s = DesktopVoiceCommandParser::playlistScore
        assertEquals(1.0, s("road trip", "Road Trip"), 1e-9)
        assertEquals(1.0, s("my road trip playlist", "Road Trip"), 1e-9)
        assertEquals(1.0, s("the Road Trip playlist", "road trip"), 1e-9)
        assertTrue(s("road trip songs", "Road Trip") >= DesktopVoiceCommandParser.PLAYLIST_THRESHOLD)
        assertTrue(s("road tripp", "Road Trip") >= DesktopVoiceCommandParser.PLAYLIST_THRESHOLD)
        assertTrue(s("billy eilish", "Billie Eilish") >= DesktopVoiceCommandParser.PLAYLIST_THRESHOLD)
        assertTrue(s("workout", "Road Trip") < DesktopVoiceCommandParser.PLAYLIST_THRESHOLD)
        assertEquals(0.0, s("my playlist", "Road Trip"), 1e-9)
        assertEquals(0.0, s("road trip", "playlist"), 1e-9)
    }

    @Test
    fun matchPlaylistReturnsTheOriginalNameOrNull() {
        assertEquals("Road Trip", DesktopVoiceCommandParser.matchPlaylist("ROAD TRIP", names))
        assertEquals("Billie Eilish Mix", DesktopVoiceCommandParser.matchPlaylist("billy eilish mix", names))
        assertNull(DesktopVoiceCommandParser.matchPlaylist("jazz", names))
        assertNull(DesktopVoiceCommandParser.matchPlaylist("road trip", emptyList()))
        // Same name twice (different case) is not ambiguous.
        assertEquals("Road Trip", DesktopVoiceCommandParser.matchPlaylist("road trip", listOf("Road Trip", "road trip")))
    }

    @Test
    fun exactNameWinsOverALongerNeighbour() {
        val two = listOf("Chill", "Chill Vibes")
        assertEquals("Chill", DesktopVoiceCommandParser.matchPlaylist("chill", two))
    }
}
