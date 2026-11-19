package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * BUDS B3: the persisted earbuds settings. Three switches, nothing else is saved: no device name,
 * no address, no battery value. `enabled` is false until the owner turns it on; nothing is scanned
 * or opened while it is off.
 */
internal object DesktopBudsSettings {

    private val persistence = DesktopPersistence()

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    private val _autoConnect = MutableStateFlow(persistence.boolean(KEY_AUTO_CONNECT, true))
    private val _debugFrames = MutableStateFlow(persistence.boolean(KEY_DEBUG_FRAMES, false))

    val enabled: StateFlow<Boolean> = _enabled
    val autoConnect: StateFlow<Boolean> = _autoConnect
    val debugFrames: StateFlow<Boolean> = _debugFrames

    /** Turning it on starts the controller, turning it off stops it and releases the socket. */
    fun setEnabled(value: Boolean) {
        persistence.saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        runCatching { DesktopBudsRuntime.refresh() }
    }

    fun setAutoConnect(value: Boolean) {
        persistence.saveBoolean(KEY_AUTO_CONNECT, value)
        _autoConnect.value = value
    }

    fun setDebugFrames(value: Boolean) {
        persistence.saveBoolean(KEY_DEBUG_FRAMES, value)
        _debugFrames.value = value
    }

    private const val KEY_ENABLED = "buds_enabled"
    private const val KEY_AUTO_CONNECT = "buds_auto_connect"
    private const val KEY_DEBUG_FRAMES = "buds_debug_frames"
}
