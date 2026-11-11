package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class DesktopMiniPlayerPosition {
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun fromStored(value: String?): DesktopMiniPlayerPosition =
            entries.firstOrNull { it.name == value } ?: CENTER
    }
}

/** Persisted appearance controls for the floating desktop player. */
internal object DesktopMiniPlayerAppearance {
    private const val KEY = "desktop_mini_player_"
    private val persistence = DesktopPersistence()

    private val _position = MutableStateFlow(
        DesktopMiniPlayerPosition.fromStored(persistence.string(KEY + "position", "CENTER")),
    )
    private val _width = MutableStateFlow(persistence.int(KEY + "width", 720).coerceIn(560, 920))
    private val _bottomInset = MutableStateFlow(persistence.int(KEY + "bottom_inset", 16).coerceIn(8, 48))
    private val _cornerRadius = MutableStateFlow(persistence.int(KEY + "corner_radius", 20).coerceIn(12, 30))
    private val _contrast = MutableStateFlow(persistence.float(KEY + "contrast", 0.72f).coerceIn(0.48f, 0.9f))
    private val _rim = MutableStateFlow(persistence.float(KEY + "rim", 0.32f).coerceIn(0f, 0.7f))

    val position: StateFlow<DesktopMiniPlayerPosition> = _position
    val width: StateFlow<Int> = _width
    val bottomInset: StateFlow<Int> = _bottomInset
    val cornerRadius: StateFlow<Int> = _cornerRadius
    val contrast: StateFlow<Float> = _contrast
    val rim: StateFlow<Float> = _rim

    fun setPosition(value: DesktopMiniPlayerPosition) {
        persistence.saveString(KEY + "position", value.name)
        _position.value = value
    }

    fun setWidth(value: Int) {
        val clamped = value.coerceIn(560, 920)
        persistence.saveInt(KEY + "width", clamped)
        _width.value = clamped
    }

    fun setBottomInset(value: Int) {
        val clamped = value.coerceIn(8, 48)
        persistence.saveInt(KEY + "bottom_inset", clamped)
        _bottomInset.value = clamped
    }

    fun setCornerRadius(value: Int) {
        val clamped = value.coerceIn(12, 30)
        persistence.saveInt(KEY + "corner_radius", clamped)
        _cornerRadius.value = clamped
    }

    fun setContrast(value: Float) {
        val clamped = value.coerceIn(0.48f, 0.9f)
        persistence.saveFloat(KEY + "contrast", clamped)
        _contrast.value = clamped
    }

    fun setRim(value: Float) {
        val clamped = value.coerceIn(0f, 0.7f)
        persistence.saveFloat(KEY + "rim", clamped)
        _rim.value = clamped
    }
}
