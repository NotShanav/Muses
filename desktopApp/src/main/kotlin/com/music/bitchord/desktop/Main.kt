package com.music.bitchord.desktop

import androidx.compose.runtime.CompositionLocalProvider
import com.music.bitchord.ui.LocalForegroundOverride
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator
import com.music.bitchord.data.lyrics.LyricsTranslation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

fun main(args: Array<String>) {
    // AUDIO FIX: reports a frozen UI thread (all stacks into desktop.log) for the next debugging round.
    DesktopFreezeWatchdog.start()
    // The player is the phone's, from the shared UI module; this is what it reads underneath.
    PlayerPlatform.install(DesktopPlayerHost)
    com.music.bitchord.ui.AppUi.install(DesktopAppUiHost)
    // The data layer both apps share logs through here, and keeps translated
    // lyrics beside the rest of the desktop's cache.
    DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    LyricsTranslation.cacheDir = DesktopMediaCache.directory.toFile()
    // YouTube playback is the phone's StreamResolver over InnerTubeX, with BotGuard PoTokens
    // minted in JavaFX's WebView where the phone uses Android's.
    TrackLog.echo = { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    StreamResolver.maxKbps = {
        DesktopStreamClient.ceiling.get() ?: DesktopSourceRegistry.ceiling(null).maxKbps
    }
    InnerTubeXResolver.init(
        filesDir = DesktopMediaCache.directory.toFile(),
        store = DesktopInnerTubeXStore,
        poTokenProvider = PoTokenGenerator(
            createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
            available = { DesktopPoTokenWebView.available },
        ).asTokenProvider(),
    )
    // WEB 1 hook: Muses Web. `--web` turns the server on for this launch; with it (or the setting
    // "Start without the desktop window") no window is made until "Show the desktop window".
    if (DesktopWebSettingsLogic.FLAG in args) DesktopWebRuntime.forceOn()
    DesktopWebRuntime.refresh()
    if (DesktopWebBackground.requested(args)) DesktopWebBackground.runUntilWindowRequested()
    desktopMain()
}

private fun desktopMain() {
    // 360 T1: log everything that escapes a thread, and never let a failed composition leave the
    // process without a window: application{} returns normally only through exitApplication(),
    // which exits the process itself, so getting past it means the window's event loop failed.
    DesktopWindowGuard.install()
    val limiter = RestartLimiter(maxRestarts = 5, windowMillis = 60_000L)
    while (true) {
        val failure = runCatching { desktopApplication() }.exceptionOrNull()
        if (failure != null) {
            DesktopTrackLog.log(DesktopWindowGuard.describe("main", failure))
        } else {
            DesktopTrackLog.log("window: application ended without a request to exit")
        }
        if (!limiter.allow(System.currentTimeMillis())) {
            DesktopTrackLog.log("window: too many restarts in a minute; leaving playback and the tray running")
            return
        }
        DesktopTrackLog.log("window: rebuilding the window (playback is not touched)")
    }
}

private fun desktopApplication() = application {
    // Closing puts the window away rather than ending the process, while there is a tray icon to
    // bring it back from — see [DesktopWindowVisibility].
    val visible by DesktopWindowVisibility.visible.collectAsState()
    // Hoisted so the player can fill the screen and the caption buttons can maximize — see
    // [DesktopWindowMode].
    val placement by DesktopWindowMode.placement.collectAsState()
    val state = rememberWindowState(width = 1_220.dp, height = 780.dp)
    // EDGE 2 hook: side panel mode drives these four window flags; with the mode off they are today's values.
    val edgeFlags by DesktopEdgePanel.flags.collectAsState()
    // Bumped by [DesktopDisplayWatcher] when the window stopped drawing after a display change.
    // Rebuilding the Window gives it a new native peer and a new Skia surface; the player, the queue
    // and the audio engine live outside the composition, so playback carries on through it.
    var windowEpoch by remember { mutableIntStateOf(0) }
    // 360 T1: the native frame's maximize is authoritative; only push a placement Compose does not
    // already have, and never touch the size.
    LaunchedEffect(placement) {
        if (state.placement != placement) {
            DesktopTrackLog.log("window: placement ${state.placement} -> $placement (requested by the app)")
            state.placement = placement
        }
    }
    // UPGRADE M3: the diagnostic memory report schedule (does nothing unless its switch is on).
    LaunchedEffect(Unit) { DesktopMemoryReportSettings.runSchedule() }
    // ...and back, for the times the window is moved between placements by something that is not
    // us; see [DesktopWindowMode.adopt].
    LaunchedEffect(state) {
        snapshotFlow { state.placement }.collect {
            DesktopTrackLog.log("window: placement is now $it, size ${state.size}")
            DesktopWindowMode.adopt(it)
        }
    }
    key(windowEpoch) {
    Window(
        onCloseRequest = { if (DesktopWindowVisibility.onCloseRequest()) exitApplication() },
        visible = visible && edgeFlags.shown, // EDGE 2 hook
        title = "Muses",
        icon = painterResource(Res.drawable.logo),
        state = state,
        // No system title bar on Windows, where the application draws its own instead — see
        // [DesktopWindowChrome].
        undecorated = DesktopPlatform.drawsOwnWindowFrame,
        // For Mica and Acrylic behind the sidebar and top bar: only a transparent window lets
        // DWM's material show through the chrome. Fixed at creation, so it is on wherever the
        // material is possible and the chosen one can be switched live — see [DesktopWindowBackdrop].
        transparent = DesktopWindowBackdrop.available,
        resizable = edgeFlags.resizable, // EDGE 2 hook
        alwaysOnTop = edgeFlags.alwaysOnTop, // EDGE 2 hook
        focusable = edgeFlags.focusable, // EDGE 2 hook
    ) {
        val composeWindow = window
        // EDGE 2 hook: the panel controller owns this window's size and place while the mode is on.
        DisposableEffect(composeWindow) {
            DesktopEdgePanel.attach(composeWindow, state)
            onDispose { DesktopEdgePanel.detach(composeWindow) }
        }
        // EDGE 2 hook: a hover reveal never takes focus; once the owner clicked, `focusable` is on and focus follows.
        LaunchedEffect(edgeFlags.focusable) { DesktopEdgePanel.grantFocus(composeWindow) }
        val openingSize = remember { state.size }
        LaunchedEffect(composeWindow) {
            // AWT's default is white, and it is what shows for the frame or two a moved or resized
            // window takes to repaint: a white band along its edges.
            // Not over a transparent window, whose clear background is what the material shows
            // through; DWM moves that window, so there is no band to hide.
            if (!DesktopWindowBackdrop.available) {
                composeWindow.background = java.awt.Color.BLACK
                composeWindow.contentPane.background = java.awt.Color.BLACK
            }
            // AWT measures this in device pixels while Compose's window state is in dp. The minimum
            // is worked out from the screen the window is on, and never exceeds its work area: a
            // portrait screen is narrower than the old fixed 900dp minimum. See
            // [DesktopDisplayGeometry.minimumSize].
            val watcher = DesktopDisplayWatcher(composeWindow, state) { windowEpoch += 1 }
            watcher.applyMinimumSize()
            val transform = composeWindow.graphicsConfiguration.defaultTransform
            // Setting AWT's minimum during the first composition can resize the native peer to
            // that minimum without updating Compose's state. Compose would then keep laying out a
            // 1220dp surface inside a 900dp window, cropping the right edge and putting its resize
            // boundary off-screen until a maximize/restore cycle synchronised the two. Re-assert
            // the requested opening size on the native peer so both layers start in agreement.
            // EDGE 2 hook: a window rebuilt in panel mode keeps the panel's size; the controller re-docks it.
            if (!DesktopEdgePanel.isPanelMode) {
                composeWindow.size = Dimension(
                    (openingSize.width.value * transform.scaleX).roundToInt(),
                    (openingSize.height.value * transform.scaleY).roundToInt(),
                )
                // A portrait or small screen may be narrower than the opening size.
                watcher.settle()
            }
            if (DesktopPlatform.isWindows && DesktopWindowsFrame.install("Muses")) {
                DesktopWindowBackdrop.apply()
            }
            // Follows rotation, scaling and monitor changes from here on; returns only when the
            // window has to be rebuilt.
            watcher.watch()
        }
        val actions = remember {
            DesktopWindowActions(
                minimize = {
                    if (!DesktopWindowsFrame.minimize()) state.isMinimized = true
                },
                toggleMaximize = {
                    if (!DesktopWindowsFrame.toggleMaximize()) {
                        // An undecorated AWT window otherwise maximizes to the monitor bounds on
                        // Windows when the native frame bridge is unavailable. Give AWT the
                        // monitor's usable work area before Compose switches the placement.
                        if (DesktopPlatform.isWindows && !DesktopWindowMode.maximized.value) {
                            val screen = composeWindow.graphicsConfiguration.bounds
                            val insets = Toolkit.getDefaultToolkit().getScreenInsets(
                                composeWindow.graphicsConfiguration,
                            )
                            composeWindow.maximizedBounds = Rectangle(
                                screen.x + insets.left,
                                screen.y + insets.top,
                                screen.width - insets.left - insets.right,
                                screen.height - insets.top - insets.bottom,
                            )
                        }
                        DesktopWindowMode.toggleMaximized()
                    }
                },
                // The same door the system's close button went through, so the tray keeps the
                // process alive exactly as it did before.
                close = { if (DesktopWindowVisibility.onCloseRequest()) exitApplication() },
            )
        }
        val touchMode by DesktopTouchSettings.active.collectAsState()
        CompositionLocalProvider(
            LocalDesktopWindowActions provides actions,
            LocalDesktopWindowScope provides this,
            LocalDesktopTouchMode provides touchMode,
            // TRAY FIX: "on screen" is the window being shown, not the window having focus.
            LocalForegroundOverride provides (visible && edgeFlags.shown && !state.isMinimized),
        ) {
            // A transparent window lets the click through wherever a pixel has alpha 0, including the
            // few at its outer edge that the app leaves unpainted — which is how a click at the
            // screen's right or bottom edge reached the browser behind. One alpha step in 255 is
            // invisible and makes every pixel of the window ours.
            Box(
                Modifier.fillMaxSize().edgePanelPointer().then( // EDGE 2 hook
                    if (DesktopWindowBackdrop.available) {
                        Modifier.background(Color.Black.copy(alpha = 1f / 255f))
                    } else {
                        Modifier
                    },
                ),
            ) {
                BitChordDesktopApp()
            }
        }
    }
    }
    // VOICE V3 hook: the voice overlay's own small window (exists only while a command is running).
    DesktopVoiceOverlayHost()
}
