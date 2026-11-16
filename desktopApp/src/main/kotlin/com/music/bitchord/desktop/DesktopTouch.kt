package com.music.bitchord.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch

/** 360 T3: pure touch rules, kept free of Compose so they can be tested. */
internal object DesktopTouchMath {
    const val TARGET_DP = 48f
    const val LONG_PRESS_MS = 500L
    const val LONG_PRESS_SLOP_DP = 8f
    const val FLING_FACTOR = 0.25f
    const val FLING_MAX_PX = 6_000f
    const val KEYBOARD_MIN_GAP_MS = 1_500L

    /** A control's size, or at least a finger's width while touch mode is on. */
    fun hitTarget(sizeDp: Float, touch: Boolean): Float = if (touch) sizeDp.coerceAtLeast(TARGET_DP) else sizeDp

    /** The scroll offset change for a finger drag of [dragPx]: the content follows the finger. */
    fun scrollDelta(dragPx: Float): Float = if (dragPx.isFinite()) -dragPx else 0f

    /** How far a release at [velocityPxPerSec] keeps scrolling, bounded. */
    fun flingDistance(velocityPxPerSec: Float): Float {
        if (!velocityPxPerSec.isFinite()) return 0f
        return (-velocityPxPerSec * FLING_FACTOR).coerceIn(-FLING_MAX_PX, FLING_MAX_PX)
    }

    /** Held long enough and still enough to count as a long press. */
    fun isLongPress(heldMs: Long, movedDp: Float): Boolean = heldMs >= LONG_PRESS_MS && movedDp <= LONG_PRESS_SLOP_DP

    /** One touch-keyboard launch per focus: a second request within the gap is ignored. */
    fun shouldShowKeyboard(touch: Boolean, windows: Boolean, nowMs: Long, lastMs: Long): Boolean =
        touch && windows && (lastMs <= 0L || nowMs - lastMs >= KEYBOARD_MIN_GAP_MS)
}

/**
 * A finger drag scrolls [state], with a short fling on release. Only while touch mode is on:
 * Windows delivers touch as mouse events, so with a mouse in touch mode a drag scrolls too, and
 * text selection by dragging is unavailable there. The wheel is unaffected.
 */
@Composable
internal fun Modifier.desktopTouchScroll(state: ScrollableState): Modifier { // 360 T3
    val scope = rememberCoroutineScope()
    if (!LocalDesktopTouchMode.current) return this
    return this.pointerInput(state) {
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().y
                scope.launch {
                    state.animateScrollBy(
                        DesktopTouchMath.flingDistance(velocity),
                        tween(450, easing = FastOutSlowInEasing),
                    )
                }
            },
            onDragCancel = {},
            onVerticalDrag = { change, amount ->
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                state.dispatchRawDelta(DesktopTouchMath.scrollDelta(amount))
            },
        )
    }
}

/** Long press (the platform's timeout, about 500 ms) runs [onLongPress]: the touch stand-in for a right click. */
internal fun Modifier.desktopLongPress(onLongPress: () -> Unit): Modifier = // 360 T3
    this.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }

/** Shows Windows' touch keyboard when a text field gains focus in touch mode. Failures are ignored. */
internal object DesktopTouchKeyboard {
    @Volatile
    private var last = 0L

    private const val TABTIP = "C:\\Program Files\\Common Files\\microsoft shared\\ink\\TabTip.exe"

    fun show(touch: Boolean) {
        val now = System.currentTimeMillis()
        if (!DesktopTouchMath.shouldShowKeyboard(touch, DesktopPlatform.isWindows, now, last)) return
        last = now
        runCatching { ProcessBuilder(TABTIP).start() }
            .onFailure { DesktopTrackLog.log("touch keyboard: ${it.message}") }
    }
}
