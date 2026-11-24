package com.music.bitchord.desktop

/** UI U3: where the minimise / maximise / close buttons are drawn. Exactly one place, never two. */
internal enum class DesktopCaptionPlace { NONE, TOP_BAR, SIDEBAR }

internal object DesktopCaptionPlacement {
    /**
     * Modern: the top bar. Legacy: the sidebar head when the sidebar is on screen, otherwise
     * (narrow window, sidebar hidden) the top bar. Nothing when the system draws the frame, when the
     * title bar setting is on, or in the side panel.
     */
    fun place(
        drawsOwnWindowFrame: Boolean,
        titleBarEnabled: Boolean,
        panelMode: Boolean,
        modernInterface: Boolean,
        compact: Boolean,
    ): DesktopCaptionPlace = when {
        !drawsOwnWindowFrame || titleBarEnabled || panelMode -> DesktopCaptionPlace.NONE
        modernInterface -> DesktopCaptionPlace.TOP_BAR
        compact -> DesktopCaptionPlace.TOP_BAR
        else -> DesktopCaptionPlace.SIDEBAR
    }
}
