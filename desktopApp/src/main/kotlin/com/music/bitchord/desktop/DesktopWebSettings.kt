package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * WEB1: the persisted Muses Web settings. Four values, nothing else is saved (no token, no pairing
 * code, no device name): the master switch (off by default, nothing listens while it is off), the
 * port, "allow other devices on my network" (off) and "start without the desktop window".
 */
internal object DesktopWebSettings {

    private val persistence = DesktopPersistence()

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    private val _port = MutableStateFlow(
        DesktopWebSettingsLogic.normalizePort(persistence.int(KEY_PORT, DesktopWebSettingsLogic.DEFAULT_PORT)),
    )
    private val _lan = MutableStateFlow(persistence.boolean(KEY_LAN, false))
    private val _background = MutableStateFlow(persistence.boolean(KEY_BACKGROUND, false))

    val enabled: StateFlow<Boolean> = _enabled
    val port: StateFlow<Int> = _port
    val lan: StateFlow<Boolean> = _lan
    val background: StateFlow<Boolean> = _background

    /** Turning it on starts the server, turning it off stops it and frees the port. */
    fun setEnabled(value: Boolean) {
        persistence.saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        DesktopWebRuntime.refresh()
    }

    /** Restarts the server on the new port if it is running. */
    fun setPort(value: Int) {
        val port = DesktopWebSettingsLogic.clampPort(value)
        persistence.saveInt(KEY_PORT, port)
        _port.value = port
        DesktopWebRuntime.refresh()
    }

    /** Turning LAN access on makes a fresh pairing code; the server restarts bound to the network. */
    fun setLan(value: Boolean) {
        persistence.saveBoolean(KEY_LAN, value)
        _lan.value = value
        if (value) DesktopWebRuntime.newPairingCode()
        DesktopWebRuntime.refresh()
    }

    /** Used at the next start of the app (no window is made while it is on). */
    fun setBackground(value: Boolean) {
        persistence.saveBoolean(KEY_BACKGROUND, value)
        _background.value = value
    }

    private const val KEY_ENABLED = "web_enabled"
    private const val KEY_PORT = "web_port"
    private const val KEY_LAN = "web_lan"
    private const val KEY_BACKGROUND = "web_background"
}

/** The rules for the settings, as pure functions. */
internal object DesktopWebSettingsLogic {
    const val DEFAULT_PORT = 8765
    const val MIN_PORT = 1024

    /** Leaves room for the 20 fallback ports above it. */
    const val MAX_PORT = 65_000

    /** A saved port outside the allowed range is replaced by the default. */
    fun normalizePort(saved: Int): Int = if (saved in MIN_PORT..MAX_PORT) saved else DEFAULT_PORT

    /** A requested port is pulled into the allowed range (used by the stepper). */
    fun clampPort(requested: Int): Int = requested.coerceIn(MIN_PORT, MAX_PORT)

    /** Whether this launch runs without the desktop window. */
    fun backgroundRequested(args: Array<String>, enabled: Boolean, backgroundSetting: Boolean): Boolean =
        args.contains(FLAG) || (enabled && backgroundSetting)

    const val FLAG = "--web"
}
