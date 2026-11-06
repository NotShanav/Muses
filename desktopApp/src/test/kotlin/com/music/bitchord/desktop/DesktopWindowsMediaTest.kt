package com.music.bitchord.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which media key does what.
 *
 * The native side hands back a number and nothing else, so a transposed pair
 * here means the previous-track key skips forward — on a platform this test
 * does not run on, and with no compiler error to say so.
 */
class DesktopWindowsMediaTest {

    private val pressed = mutableListOf<String>()
    private val seeks = mutableListOf<Long>()

    private fun install() {
        DesktopWindowsMedia.start(
            DesktopWindowsMedia.Controller(
                onPlay = { pressed += "play" },
                onPause = { pressed += "pause" },
                onNext = { pressed += "next" },
                onPrevious = { pressed += "previous" },
                onStop = { pressed += "stop" },
                onSeek = { seeks += it },
            ),
        )
    }

    @AfterTest
    fun tearDown() = DesktopWindowsMedia.stop()

    @Test
    fun everyButtonReachesItsOwnControl() {
        install()
        (0..4).forEach(DesktopWindowsMedia::onButton)
        assertEquals(listOf("play", "pause", "next", "previous", "stop"), pressed)
    }

    @Test
    fun anUnknownButtonIsIgnoredRatherThanGuessed() {
        install()
        DesktopWindowsMedia.onButton(99)
        DesktopWindowsMedia.onButton(-1)
        assertEquals(emptyList(), pressed)
    }

    @Test
    fun aSeekRequestReachesTheControlInMilliseconds() {
        install()
        DesktopWindowsMedia.onSeek(120_000L)
        DesktopWindowsMedia.onSeek(0L)
        assertEquals(listOf(120_000L, 0L), seeks)
    }

    @Test
    fun aNegativeSeekIsHeldAtTheStartOfTheTrack() {
        install()
        DesktopWindowsMedia.onSeek(-5_000L)
        assertEquals(listOf(0L), seeks)
    }

    @Test
    fun aSeekWithNoControlInstalledIsIgnored() {
        DesktopWindowsMedia.onSeek(60_000L)
        assertEquals(emptyList(), seeks)
    }
}
