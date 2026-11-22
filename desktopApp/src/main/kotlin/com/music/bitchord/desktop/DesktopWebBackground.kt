package com.music.bitchord.desktop

import bitchord.desktopapp.generated.resources.Res
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/*
 * WEB1: background (window-less) mode. Started by the `--web` flag or by the setting "Start without
 * the desktop window". No Compose application, window, backdrop or animation exists while this runs:
 * the process is the web server plus a tray icon whose menu is Open Muses Web / Show the desktop
 * window / Quit. "Show the desktop window" returns from [runUntilWindowRequested], and Main then
 * starts the normal Compose application, which behaves exactly as today.
 *
 * What is NOT available here, because it lives inside the composed window today (see HANDOFF):
 * the desktop player (queue, selected song, audio engine), the tray's play / next / previous, media
 * keys and the Windows media session. The browser plays its own audio; WEB2 only needs the library,
 * search and stream code, which do not depend on a window.
 */
internal object DesktopWebBackground {

    /** Whether this launch runs without a window. */
    fun requested(args: Array<String>): Boolean =
        DesktopWebSettingsLogic.backgroundRequested(
            args,
            DesktopWebSettings.enabled.value,
            DesktopWebSettings.background.value,
        )

    /**
     * Blocks the calling (main) thread until the user picks "Show the desktop window". Returns at
     * once, so the window opens, when there is no tray to quit or reopen from: a window-less process
     * with no icon could only be stopped from the task manager.
     */
    fun runUntilWindowRequested() {
        if (!DesktopPlatform.isLinux && !DesktopAwtTray.isSupported()) {
            DesktopTrackLog.log("web: no system tray here, so background mode is skipped and the window opens")
            return
        }
        val showWindow = CountDownLatch(1)
        // A click on the icon opens the web page; opening the heavy window is a deliberate menu choice.
        val tray = DesktopStatusNotifierController(
            onActivate = { DesktopWebRuntime.openInBrowser() },
            onPlayPause = {},
        )
        DesktopTrayMenu.bindBackground(
            onOpenWeb = { DesktopWebRuntime.openInBrowser() },
            onShowWindow = { showWindow.countDown() },
            onQuit = {
                DesktopWebRuntime.shutdown()
                exitProcess(0)
            },
        )
        tray.start()
        runCatching { runBlocking { Res.readBytes("drawable/logo.png") } }.onSuccess(tray::setIcon)
        DesktopTrackLog.log("web: background mode, no window is created")
        try {
            showWindow.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        tray.stop()
        DesktopTrayMenu.leaveBackground()
        DesktopTrackLog.log("web: showing the desktop window")
    }
}
