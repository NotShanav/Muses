package com.music.bitchord.desktop

import kotlinx.coroutines.delay

/**
 * Windows' native frame kept underneath Muses's Compose-drawn traffic-light controls.
 *
 * The Win32 window remains an overlapped window, so DWM owns its shadow, transitions, taskbar
 * maximization and resize border. Only the caption drawing is replaced by the app. This object is
 * never loaded on Linux, where the window manager continues to provide the ordinary decoration.
 */
internal object DesktopWindowsFrame {

    private val available: Boolean by lazy {
        DesktopPlatform.isWindows &&
            runCatching { DesktopAnalysisRuntime.loadNative(LIBRARY) }
                .onFailure { DesktopTrackLog.log("native Windows frame unavailable: ${it.message}") }
                .isSuccess
    }

    @Volatile
    private var installed = false

    suspend fun install(title: String): Boolean {
        if (!available) return false
        repeat(INSTALL_ATTEMPTS) {
            val ready = runCatching { nativeInstall(title) }.getOrElse {
                DesktopTrackLog.log("native Windows frame install failed: ${it.message}")
                false
            }
            if (ready) {
                installed = true
                return true
            }
            delay(INSTALL_RETRY_MILLIS)
        }
        DesktopTrackLog.log("native Windows frame could not find the visible app window")
        return false
    }

    fun minimize(): Boolean = installed && runCatching { nativeMinimize() }.getOrDefault(false)

    fun toggleMaximize(): Boolean =
        installed && runCatching { nativeToggleMaximize() }.getOrDefault(false)

    /**
     * Hands the press in progress to Windows as a caption drag. False when there is no native
     * frame, and the caller moves the window itself.
     */
    fun startDrag(): Boolean = installed && runCatching { nativeStartDrag() }.getOrDefault(false)

    @JvmStatic
    private external fun nativeStartDrag(): Boolean

    /** Counts display, DPI and work-area changes the window has been told about. 0 without the bridge. */
    fun displayGeneration(): Long =
        if (installed) runCatching { nativeDisplayGeneration() }.getOrDefault(0L) else 0L

    /** Flags for [DesktopTouchPolicy.postureFromFlags], or -1 without the bridge. */
    fun postureFlags(): Int =
        if (installed) runCatching { nativePosture() }.getOrDefault(-1) else -1

    /** The work area of the window's monitor as {left, top, right, bottom, dpi}, or null. */
    fun workArea(): IntArray? =
        if (installed) runCatching { nativeWorkArea() }.getOrNull() else null

    /** Has DWM redraw the frame and re-applies its margins; the cheap fix for a stale surface. */
    fun refreshFrame(): Boolean =
        installed && runCatching { nativeRefreshFrame() }.getOrDefault(false)

    @JvmStatic
    private external fun nativeDisplayGeneration(): Long

    @JvmStatic
    private external fun nativePostureGeneration(): Long

    @JvmStatic
    private external fun nativePosture(): Int

    @JvmStatic
    private external fun nativeWorkArea(): IntArray?

    @JvmStatic
    private external fun nativeRefreshFrame(): Boolean

    /**
     * Puts DWM's material behind the window — [DesktopBackdrop.nativeKind] — or takes it away.
     * False without the native frame, or on a Windows too old to have one.
     */
    fun setBackdrop(kind: Int): Boolean =
        installed && runCatching { nativeSetBackdrop(kind) }.getOrDefault(false)

    @JvmStatic
    private external fun nativeSetBackdrop(kind: Int): Boolean

    @JvmStatic
    private external fun nativeInstall(title: String): Boolean

    @JvmStatic
    private external fun nativeMinimize(): Boolean

    @JvmStatic
    private external fun nativeToggleMaximize(): Boolean

    private const val LIBRARY = "bitchord_window"
    private const val INSTALL_ATTEMPTS = 20
    private const val INSTALL_RETRY_MILLIS = 100L
}
