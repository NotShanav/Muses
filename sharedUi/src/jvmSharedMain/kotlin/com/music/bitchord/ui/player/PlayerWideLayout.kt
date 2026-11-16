package com.music.bitchord.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizing for the full-screen player in a portrait window wider than a phone
 * (360 part T5). Pure arithmetic, no composition, so it is unit tested.
 *
 * Only a host that sets [PlayerHost.widePortraitLayout] gets anything other
 * than [Off]; the phone keeps the 560 dp column it has always had.
 */
internal data class PlayerWideLayout(
    /** False means: lay the player out exactly as before. */
    val active: Boolean,
    /** Widest the portrait column may be. [PLAYER_MAX_WIDTH] when not active. */
    val columnMax: Dp,
    /** The sleeve is never taller than this. NO_LIMIT when not active. */
    val artworkCap: Dp,
    /** Largest total extra gap the transport row may be given. */
    val spreadMax: Dp,
    /** Widest the lyrics / output / queue row may be. NO_LIMIT when not active. */
    val actionRowMax: Dp,
    /** Multiplier for lyric type. Exactly 1f when not active. */
    val lyricScale: Float,
) {
    companion object {
        /** The old, phone behaviour. */
        val Off = PlayerWideLayout(
            active = false,
            columnMax = PLAYER_MAX_WIDTH,
            artworkCap = NO_LIMIT,
            spreadMax = NO_LIMIT,
            actionRowMax = NO_LIMIT,
            lyricScale = 1f,
        )

        fun of(
            hostWide: Boolean,
            hostMaxWidth: Dp?,
            windowWidth: Dp,
            windowHeight: Dp,
        ): PlayerWideLayout {
            if (!hostWide) return Off
            // Landscape has its own player; this is for the tall column only.
            if (landscapePlayerAvailable(windowWidth, windowHeight)) return Off
            return PlayerWideLayout(
                active = true,
                columnMax = columnWidth(windowWidth, hostMaxWidth),
                artworkCap = artworkCap(windowHeight),
                spreadMax = WIDE_SPREAD_MAX,
                actionRowMax = WIDE_ACTION_ROW_MAX,
                lyricScale = lyricScale(windowWidth),
            )
        }

        /** Content column: window minus both gutters, at least the phone column, at most [hostMax]. */
        fun columnWidth(windowWidth: Dp, hostMax: Dp?): Dp {
            val ceiling = (hostMax ?: WIDE_DEFAULT_MAX).coerceAtLeast(PLAYER_MAX_WIDTH)
            val wanted = windowWidth - PLAYER_GUTTER * 2
            return wanted.coerceIn(PLAYER_MAX_WIDTH, ceiling)
        }

        /** The sleeve stays under this share of the window's height so the controls fit. */
        fun artworkCap(windowHeight: Dp): Dp =
            (windowHeight * WIDE_ARTWORK_HEIGHT_SHARE).coerceAtLeast(PLAYER_MAX_WIDTH)

        /** 1.0 up to 600 dp wide, growing to [WIDE_LYRIC_SCALE_MAX] at 900 dp and beyond. */
        fun lyricScale(windowWidth: Dp): Float {
            val t = ((windowWidth.value - 600f) / 300f).coerceIn(0f, 1f)
            // Quantised to 0.05 so a drag-resize does not recompose the list per pixel.
            val raw = 1f + (WIDE_LYRIC_SCALE_MAX - 1f) * t
            return (Math.round(raw * 20f) / 20f).coerceIn(1f, WIDE_LYRIC_SCALE_MAX)
        }
    }
}

/** "No limit" for the off state: larger than any window, and plain arithmetic. */
internal val NO_LIMIT = 100_000.dp

/** Used when the host asks for the wide layout but gives no limit. */
internal val WIDE_DEFAULT_MAX = 900.dp
internal const val WIDE_ARTWORK_HEIGHT_SHARE = 0.62f
internal val WIDE_SPREAD_MAX = 120.dp
internal val WIDE_ACTION_ROW_MAX = 520.dp
internal const val WIDE_LYRIC_SCALE_MAX = 1.4f

/** Extra line height multiplier and gap between lyric lines in the wide layout. */
internal const val WIDE_LYRIC_LINE_HEIGHT_BOOST = 1.1f

/** Gap between two lyric rows; zero (the old look) when [scale] is 1. */
internal fun wideLyricGap(scale: Float, active: Boolean): Dp =
    if (!active) 0.dp else (12f * scale).dp
