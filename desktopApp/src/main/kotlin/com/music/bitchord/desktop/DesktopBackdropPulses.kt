package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Short-lived colour pulses injected by the artwork transition and drawn by DesktopFluidBackdrop as
 * soft radial glows. Fixed ring of slots, so inject() and the per-frame read never allocate collections.
 */
internal object DesktopBackdropPulses : ArtBackdropSink {
    const val DECAY_MS = 600f
    const val SLOTS = 6

    class Pulse {
        @Volatile var color: Color = Color.Transparent
        @Volatile var x: Float = 0f
        @Volatile var y: Float = 0f
        @Volatile var strength: Float = 0f
        @Volatile var startNanos: Long = 0L
    }

    private val slots = Array(SLOTS) { Pulse() }
    private var next = 0

    @Synchronized
    override fun inject(color: Color, position: Offset, strength: Float) {
        val p = slots[next]
        next = (next + 1) % SLOTS
        p.color = color; p.x = position.x; p.y = position.y
        p.strength = strength.coerceIn(0f, 1f)
        p.startNanos = System.nanoTime()
    }

    /** Remaining strength 0..1 of a slot at [nowNanos] (quadratic ease-out); 0 when dead. */
    fun liveStrength(p: Pulse, nowNanos: Long): Float {
        if (p.startNanos == 0L) return 0f
        val age = (nowNanos - p.startNanos) / 1_000_000f
        if (age < 0f || age >= DECAY_MS) return 0f
        val k = 1f - age / DECAY_MS
        return p.strength * k * k
    }

    fun slot(i: Int): Pulse = slots[i]

    /** True if any pulse is still alive; the backdrop uses it to keep drawing frames only while needed. */
    fun anyLive(nowNanos: Long): Boolean {
        for (i in 0 until SLOTS) if (liveStrength(slots[i], nowNanos) > 0f) return true
        return false
    }
}
