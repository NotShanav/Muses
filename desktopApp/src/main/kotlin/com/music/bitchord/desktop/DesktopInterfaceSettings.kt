package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class DesktopInterfaceMode {
    MODERN,
    LEGACY;

    companion object {
        val DEFAULT = MODERN

        fun fromStored(value: String?): DesktopInterfaceMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** The shell layout is independent from Fluidity and the full-screen player background. */
internal object DesktopInterfaceSettings {
    internal const val KEY = "desktop_interface_mode"

    private val _mode = MutableStateFlow(
        DesktopInterfaceMode.fromStored(
            DesktopPersistence().string(KEY, DesktopInterfaceMode.DEFAULT.name),
        ),
    )

    val mode: StateFlow<DesktopInterfaceMode> = _mode

    fun setMode(value: DesktopInterfaceMode) {
        DesktopPersistence().saveString(KEY, value.name)
        _mode.value = value
    }
}
