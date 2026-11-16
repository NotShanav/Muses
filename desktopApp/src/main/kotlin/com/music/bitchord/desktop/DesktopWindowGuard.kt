package com.music.bitchord.desktop

/**
 * 360 T1 crash guard. When composition throws, the Compose event loop used to die while the process
 * lived on through the tray and the audio: no window, music playing. [DesktopWindowGuard] logs every
 * uncaught exception and lets `main` rebuild the window; playback never lives in the window.
 */
internal class RestartLimiter(private val maxRestarts: Int, private val windowMillis: Long) {
    private val stamps = ArrayDeque<Long>()

    /** True when a restart at [nowMillis] is allowed; records it. Too many in a short time says no. */
    fun allow(nowMillis: Long): Boolean {
        while (stamps.isNotEmpty() && nowMillis - stamps.first() > windowMillis) stamps.removeFirst()
        if (stamps.size >= maxRestarts) return false
        stamps.addLast(nowMillis)
        return true
    }
}

internal object DesktopWindowGuard {

    /** Whether a thread's failure is the UI's (AWT, Skiko, Compose) rather than a worker's. */
    fun isUiThread(name: String?): Boolean {
        val n = name ?: return false
        return n.startsWith("AWT-EventQueue") || n.contains("skiko", ignoreCase = true) ||
            n.contains("compose", ignoreCase = true)
    }

    fun describe(thread: String?, error: Throwable): String =
        "uncaught on ${thread ?: "?"}: ${error::class.java.name}: ${error.message}\n" +
            error.stackTrace.take(12).joinToString("\n") { "    at $it" }

    /** Logs everything that escapes a thread and never exits: audio and tray outlive the window. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                DesktopTrackLog.log(describe(thread.name, error))
                if (isUiThread(thread.name)) DesktopTrackLog.log("window: UI thread failed; main will rebuild the window")
            }
            runCatching { previous?.uncaughtException(thread, error) }
        }
    }
}
