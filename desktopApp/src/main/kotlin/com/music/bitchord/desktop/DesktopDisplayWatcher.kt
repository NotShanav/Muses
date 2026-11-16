package com.music.bitchord.desktop

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Frame
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Bumped whenever the screen changed under the window (rotation, scaling, another monitor).
 * Popups and menus that anchor to window coordinates close on it rather than hang where the old
 * layout put them.
 */
internal object DesktopDisplayEvents {
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch
    fun bump() {
        _epoch.value += 1
    }
}

/**
 * Keeps the window in step with its screen while the app runs.
 *
 * Rotating a 2-in-1 or changing the scaling used to leave the window with a minimum size larger
 * than the monitor and a Skia surface that no longer matched the native window. This watches for
 * those changes, re-reads the scale and the work area, re-clamps the window into it, and asks the
 * window to repaint. If the frame clock then stops ticking, [onStale] is called so the host can
 * rebuild the window; playback lives outside the window and is untouched either way.
 *
 * The Windows bridge counts WM_DISPLAYCHANGE, WM_DPICHANGED and work-area changes; without it
 * (Linux, or the library did not load) the same changes are noticed from AWT's own view of the
 * screen. Everything runs under runCatching: a failure is logged, never thrown into the UI.
 */
internal class DesktopDisplayWatcher(
    private val window: Frame,
    private val state: WindowState,
    private val onStale: () -> Unit,
) {

    private data class Signature(
        val nativeGeneration: Long,
        val screen: Rectangle?,
        val scaleX: Double,
        val scaleY: Double,
        val insets: List<Int>,
    )

    private data class Screen(val work: DisplayRect, val scale: Double, val config: GraphicsConfiguration)

    private fun screen(): Screen? {
        val config = window.graphicsConfiguration ?: return null
        val scale = config.defaultTransform.scaleX.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val work = DisplayRect(
            bounds.x + insets.left,
            bounds.y + insets.top,
            (bounds.width - insets.left - insets.right).coerceAtLeast(1),
            (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
        )
        return Screen(work, scale, config)
    }

    private fun signature(): Signature {
        val config = runCatching { window.graphicsConfiguration }.getOrNull()
        val transform = config?.defaultTransform
        val insets = config?.let { Toolkit.getDefaultToolkit().getScreenInsets(it) }
        return Signature(
            nativeGeneration = DesktopWindowsFrame.displayGeneration(),
            screen = config?.bounds,
            scaleX = transform?.scaleX ?: 1.0,
            scaleY = transform?.scaleY ?: 1.0,
            insets = insets?.let { listOf(it.left, it.top, it.right, it.bottom) } ?: emptyList(),
        )
    }

    /** Sets the minimum for the current screen. Called once at start-up and again on every change. */
    fun applyMinimumSize() {
        runCatching {
            val screen = screen() ?: return
            val minimum = DesktopDisplayGeometry.minimumSize(screen.work, screen.scale)
            window.minimumSize = Dimension(minimum.width, minimum.height)
        }.onFailure { DesktopTrackLog.log("display: minimum size failed: ${it.message}") }
    }

    /** Re-reads scale and work area, fixes the minimum, brings the window fully on screen and repaints. */
    private fun adapt() {
        val screen = screen() ?: return
        val minimum = DesktopDisplayGeometry.minimumSize(screen.work, screen.scale)
        window.minimumSize = Dimension(minimum.width, minimum.height)

        when (state.placement) {
            WindowPlacement.Floating -> {
                val bounds = window.bounds
                val wanted = DesktopDisplayGeometry.clampToWorkArea(
                    DisplayRect(bounds.x, bounds.y, bounds.width, bounds.height),
                    screen.work,
                    minimum.width,
                    minimum.height,
                )
                if (wanted != DisplayRect(bounds.x, bounds.y, bounds.width, bounds.height)) {
                    DesktopTrackLog.log("display: moving window into the work area: $bounds -> $wanted")
                    window.setBounds(wanted.x, wanted.y, wanted.width, wanted.height)
                    // Compose's state is in dp and the AWT peer in pixels; say it to both so they agree.
                    state.size = DpSize((wanted.width / screen.scale).dp, (wanted.height / screen.scale).dp)
                }
            }
            else -> {
                // Maximized or full screen: the new monitor's work area, for the native frame
                // (WM_GETMINMAXINFO / WM_NCCALCSIZE read it themselves) and for the AWT fallback.
                window.maximizedBounds = Rectangle(
                    screen.work.x, screen.work.y, screen.work.width, screen.work.height,
                )
            }
        }
        refresh()
    }

    /** Brings the window inside its screen now; for the opening size, which may not fit a portrait screen. */
    fun settle() {
        runCatching { adapt() }.onFailure { DesktopTrackLog.log("display: settle failed: ${it.message}") }
    }

    private fun refresh() {
        window.invalidate()
        window.validate()
        window.repaint()
        DesktopWindowsFrame.refreshFrame()
    }

    /**
     * Whether Compose still produces frames. Asking for one and waiting is the probe: an idle
     * window draws nothing, so there is no free-running tick to watch instead.
     */
    private suspend fun frameClockTicks(): Boolean {
        if (coroutineContext[MonotonicFrameClock] == null) return true
        // A window that is hidden or minimized legitimately draws nothing.
        if (!window.isShowing || (window.extendedState and Frame.ICONIFIED) != 0) return true
        return withTimeoutOrNull(WATCHDOG_MS) { withFrameNanos { } } != null
    }

    /** Runs until cancelled; start it from the window's coroutine scope. */
    suspend fun watch() {
        var seen = signature()
        var posture: Int? = null
        while (true) {
            delay(POLL_MS)
            runCatching {
                val flags = DesktopWindowsFrame.postureFlags()
                if (flags != posture) {
                    posture = flags
                    DesktopTouchSettings.setPosture(DesktopTouchPolicy.postureFromFlags(flags))
                }
            }
            var current = signature()
            if (current == seen) continue
            // A rotation arrives as several messages; act once the last of them has landed.
            while (true) {
                delay(DEBOUNCE_MS)
                val again = signature()
                if (again == current) break
                current = again
            }
            seen = current
            runCatching {
                DesktopDisplayEvents.bump()
                adapt()
            }.onFailure { DesktopTrackLog.log("display: change handler failed: ${it.message}") }

            val alive = runCatching {
                frameClockTicks() || retryAfterNudge()
            }.getOrDefault(true)
            if (!alive) {
                DesktopTrackLog.log("display: frame clock stalled after a display change; rebuilding the window")
                onStale()
                return
            }
        }
    }

    private suspend fun retryAfterNudge(): Boolean {
        DesktopTrackLog.log("display: no frame after a display change; refreshing the surface")
        SwingUtilities.invokeLater { runCatching { nudge() } }
        delay(WATCHDOG_RETRY_MS)
        return frameClockTicks()
    }

    /** Resizes by a pixel and back, which makes Skiko rebuild its surface for the new size. */
    private fun nudge() {
        val size = window.size
        window.setSize(size.width + 1, size.height)
        window.setSize(size.width, size.height)
        refresh()
    }

    private companion object {
        const val POLL_MS = 120L
        const val DEBOUNCE_MS = 200L
        const val WATCHDOG_MS = 2_000L
        const val WATCHDOG_RETRY_MS = 250L
    }
}
