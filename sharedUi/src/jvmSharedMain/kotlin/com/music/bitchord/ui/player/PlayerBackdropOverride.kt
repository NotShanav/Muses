package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * A host's own backdrop for the full-screen player.
 *
 * Null everywhere by default, which leaves the player drawing its artwork mesh exactly as before.
 * The desktop app provides one while its Fluidity mode is on. It receives the artwork URL and a key
 * that changes with the track, and fills the player behind the controls.
 */
val LocalPlayerBackdropOverride =
    compositionLocalOf<(@Composable (artworkUrl: String?, trackKey: Any?) -> Unit)?> { null }
