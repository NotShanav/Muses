package com.music.bitchord.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Muses desktop motion: one place for the springs and tweens, so the shell, the player and the
 * lists all move with the same feel.
 *
 * Every preset is a function taking the type it animates (`MusesMotion.expand<Float>()`), and
 * answers with [reduced] instead when "Reduce animation" is on. The default for [reduceMotion] reads
 * the setting at the moment of the call, which is when an animation starts, so a composable that
 * should restart on a change collects [DesktopAppearanceSettings.reduceAnimation] and passes it in.
 * Animate transforms and alpha only (graphicsLayer); none of these is for a blur radius.
 */
internal object MusesMotion {

    /** Expand damping ratio: a panel or card opening settles with a slight overshoot. */
    const val EXPAND_DAMPING = 0.82f

    /** Expand stiffness. */
    const val EXPAND_STIFFNESS = 300f

    /** Collapse damping ratio: closing is quicker and barely overshoots. */
    const val COLLAPSE_DAMPING = 0.9f

    /** Collapse stiffness. */
    const val COLLAPSE_STIFFNESS = 380f

    /** Press damping ratio: a pressed control springs in and bounces back visibly. */
    const val PRESS_DAMPING = 0.55f

    /** Press stiffness. */
    const val PRESS_STIFFNESS = 520f

    /** Settle damping ratio: something dropped into place (a reorder, a snap-back). */
    const val SETTLE_DAMPING = 0.8f

    /** Settle stiffness. */
    const val SETTLE_STIFFNESS = 350f

    /** Crossfade length in milliseconds: one surface giving way to another. */
    const val CROSSFADE_MS = 280

    /** Length in milliseconds of the stand-in for any spring under "Reduce animation". */
    const val REDUCED_MS = 120

    /** Expand: spring, damping 0.82, stiffness 300. */
    fun <T> expand(reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = EXPAND_DAMPING, stiffness = EXPAND_STIFFNESS)

    /** Collapse: spring, damping 0.9, stiffness 380. */
    fun <T> collapse(reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = COLLAPSE_DAMPING, stiffness = COLLAPSE_STIFFNESS)

    /** Press: spring, damping 0.55, stiffness 520. */
    fun <T> press(reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = PRESS_DAMPING, stiffness = PRESS_STIFFNESS)

    /** Settle: spring, damping 0.8, stiffness 350. */
    fun <T> settle(reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = SETTLE_DAMPING, stiffness = SETTLE_STIFFNESS)

    /** Crossfade: tween, 280 ms, fast-out-slow-in. Already overshoot-free, so only the length shrinks when reduced. */
    fun <T> crossfade(reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        tween(if (reduceMotion) REDUCED_MS else CROSSFADE_MS, easing = FastOutSlowInEasing)

    /** The reduced-motion alternative: a 120 ms tween, no overshoot. */
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(REDUCED_MS, easing = FastOutSlowInEasing)

    /** Hands back [spec] as it is, or [reduced] when "Reduce animation" is on; for a spec that is not one of the presets. */
    fun <T> resolve(spec: FiniteAnimationSpec<T>, reduceMotion: Boolean = reduceNow()): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spec

    /** Whether "Reduce animation" is on right now. */
    fun reduceNow(): Boolean = DesktopAppearanceSettings.reduceAnimation.value
}
