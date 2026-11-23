package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit

/*
 * EDGE 1: the system side of the edge watcher: the pointer (JDK), the monitors (JDK), and on
 * Windows the "mouse button held" and "a fullscreen app is in front" checks through our OWN
 * minimal user32 binding (same pattern as VoiceUser32). Everything fails closed: any error means
 * "no button held", "not fullscreen", "no monitors". Loaded lazily, Windows only.
 */

/** Our own minimal user32 binding for the edge panel. */
internal interface EdgeUser32 : StdCallLibrary {
    fun GetAsyncKeyState(vKey: Int): Short
    fun GetForegroundWindow(): Pointer?
    fun IsWindowVisible(hWnd: Pointer): Boolean
    fun GetWindowRect(hWnd: Pointer, lpRect: Pointer): Boolean
    fun GetClassNameW(hWnd: Pointer, lpClassName: CharArray, nMaxCount: Int): Int
    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntArray): Int
    fun MonitorFromWindow(hWnd: Pointer, dwFlags: Int): Pointer?
    fun GetMonitorInfoW(hMonitor: Pointer, lpmi: Pointer): Boolean
}

/** Loads user32 once, only on Windows. */
internal object EdgeUser32Loader {
    val api: EdgeUser32? by lazy {
        if (!DesktopPlatform.isWindows) {
            null
        } else {
            runCatching {
                Native.load("user32", EdgeUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull()
        }
    }
}

/** The pure part of the fullscreen decision, tested without Windows. */
internal object EdgeFullscreen {

    /** Window classes of the desktop itself: never a fullscreen app. */
    private val SHELL_CLASSES = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")

    fun isShellClass(className: String): Boolean = className in SHELL_CLASSES

    /** A window covers a monitor when its rectangle contains the monitor's whole rectangle. */
    fun covers(window: DisplayRect, monitor: DisplayRect): Boolean =
        window.width > 0 && window.height > 0 && monitor.width > 0 && monitor.height > 0 &&
            window.x <= monitor.x && window.y <= monitor.y &&
            window.right >= monitor.right && window.bottom >= monitor.bottom
}

/**
 * Whether the foreground window is a fullscreen app. Windows' own coordinates for both the window
 * and its monitor (GetWindowRect and GetMonitorInfoW share one space), so DPI scaling cannot make
 * them disagree. Ignores the desktop and taskbar windows and our own process. False on any failure.
 *
 * Decision (not in the plan): a fullscreen window on ANY monitor suppresses the reveal; mapping the
 * cursor's AWT monitor to Windows' monitor would need DPI conversion and a wrong guess is worse
 * than a missed reveal during a game.
 */
internal class WindowsFullscreenProbe(
    private val api: EdgeUser32? = EdgeUser32Loader.api,
    private val ownPid: Int = runCatching { ProcessHandle.current().pid().toInt() }.getOrDefault(-1),
) : EdgeFullscreenProbe {

    override fun fullscreenForeground(): Boolean {
        val user32 = api ?: return false
        return runCatching {
            val hwnd = user32.GetForegroundWindow() ?: return@runCatching false
            if (!user32.IsWindowVisible(hwnd)) return@runCatching false

            val pid = IntArray(1)
            user32.GetWindowThreadProcessId(hwnd, pid)
            if (pid[0] == ownPid) return@runCatching false

            val chars = CharArray(256)
            val length = user32.GetClassNameW(hwnd, chars, chars.size)
            if (length > 0 && EdgeFullscreen.isShellClass(String(chars, 0, length))) return@runCatching false

            val rect = Memory(16)
            if (!user32.GetWindowRect(hwnd, rect)) return@runCatching false
            val window = DisplayRect(
                rect.getInt(0),
                rect.getInt(4),
                rect.getInt(8) - rect.getInt(0),
                rect.getInt(12) - rect.getInt(4),
            )

            val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return@runCatching false
            val info = Memory(40)
            info.setInt(0, 40) // MONITORINFO.cbSize
            if (!user32.GetMonitorInfoW(monitor, info)) return@runCatching false
            val screen = DisplayRect(
                info.getInt(4),
                info.getInt(8),
                info.getInt(12) - info.getInt(4),
                info.getInt(16) - info.getInt(8),
            )
            EdgeFullscreen.covers(window, screen)
        }.getOrDefault(false)
    }

    private companion object {
        const val MONITOR_DEFAULTTONEAREST = 2
    }
}

/** Mouse buttons through GetAsyncKeyState; false everywhere but Windows and on any failure. */
internal object EdgeButtons {
    private const val VK_LBUTTON = 0x01
    private const val VK_RBUTTON = 0x02
    private const val VK_MBUTTON = 0x04

    fun anyDown(api: EdgeUser32? = EdgeUser32Loader.api): Boolean {
        val user32 = api ?: return false
        return runCatching {
            intArrayOf(VK_LBUTTON, VK_RBUTTON, VK_MBUTTON).any { (user32.GetAsyncKeyState(it).toInt() and 0x8000) != 0 }
        }.getOrDefault(false)
    }
}

/** The real pointer: `MouseInfo` (AWT user space, multi-monitor) plus the button check. */
internal object AwtEdgePointerSource : EdgePointerSource {
    override fun position(): EdgePoint? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { EdgePoint(it.x, it.y) }
    }.getOrNull()

    override fun buttonHeld(): Boolean = EdgeButtons.anyDown()
}

/**
 * The real monitors, from `GraphicsEnvironment` and `Toolkit.getScreenInsets`. The list is cached:
 * it is re-read only after [DesktopDisplayEvents] reports a screen change, after [invalidate], or
 * after [maxAgeMs], so an idle watcher never enumerates screens.
 */
internal class AwtEdgeMonitorSource(
    private val clock: EdgeClock = EdgeClock.SYSTEM,
    private val maxAgeMs: Long = 30_000L,
) : EdgeMonitorSource {

    @Volatile private var cached: List<EdgeMonitor> = emptyList()
    @Volatile private var loaded = false
    @Volatile private var readAt = 0L
    @Volatile private var readEpoch = -1

    fun invalidate() {
        loaded = false
    }

    override fun monitors(): List<EdgeMonitor> {
        val now = clock.nowMs()
        val epoch = DesktopDisplayEvents.epoch.value
        if (!loaded || epoch != readEpoch || now - readAt >= maxAgeMs) {
            cached = read()
            readAt = now
            readEpoch = epoch
            loaded = cached.isNotEmpty() // an empty read is retried on the next poll
        }
        return cached
    }

    private fun read(): List<EdgeMonitor> = runCatching {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primaryBounds = env.defaultScreenDevice.defaultConfiguration.bounds
        val toolkit = Toolkit.getDefaultToolkit()
        env.screenDevices.map { device ->
            val config = device.defaultConfiguration
            val b = config.bounds
            val insets = toolkit.getScreenInsets(config)
            val scale = config.defaultTransform.scaleX.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            EdgeMonitor.fromInsets(
                DisplayRect(b.x, b.y, b.width, b.height),
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
                scale,
                primary = b == primaryBounds,
            )
        }
    }.getOrElse { emptyList() }
}
