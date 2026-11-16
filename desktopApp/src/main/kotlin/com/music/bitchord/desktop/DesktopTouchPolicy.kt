package com.music.bitchord.desktop

/** Settings > Appearance > "Convertible / touch mode". */
internal enum class DesktopTouchMode {
    /** Follows the posture Windows reports. */
    AUTO,
    ALWAYS_ON,
    ALWAYS_OFF;

    companion object {
        val DEFAULT = AUTO

        fun fromStored(value: String?): DesktopTouchMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** How a convertible is being held, as far as Windows can say. Tent and stand both read as TABLET. */
internal enum class DesktopPosture {
    LAPTOP,
    TABLET,

    /** Not a convertible, or the native bridge is not there to ask. */
    UNKNOWN,
}

internal object DesktopTouchPolicy {

    /** Whether touch sizing is on for [mode] while the machine is in [posture]. */
    fun resolve(mode: DesktopTouchMode, posture: DesktopPosture): Boolean = when (mode) {
        DesktopTouchMode.ALWAYS_ON -> true
        DesktopTouchMode.ALWAYS_OFF -> false
        DesktopTouchMode.AUTO -> posture == DesktopPosture.TABLET
    }

    /**
     * The posture for the flags `nativePosture` returns: bit 0 slate mode, bit 1 touch hardware.
     * Slate mode only counts on a machine that has touch hardware, because a desktop with no
     * convertible sensor reports 0 for the slate-mode metric too.
     */
    fun postureFromFlags(flags: Int): DesktopPosture = when {
        flags < 0 -> DesktopPosture.UNKNOWN
        flags and 2 == 0 -> DesktopPosture.UNKNOWN
        flags and 1 != 0 -> DesktopPosture.TABLET
        else -> DesktopPosture.LAPTOP
    }
}
