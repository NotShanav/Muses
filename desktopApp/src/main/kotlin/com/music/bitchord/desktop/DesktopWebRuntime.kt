package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/*
 * WEB1: owns the one server of this process. [refresh] makes the server match the settings (and the
 * `--web` flag); it runs on its own daemon thread so a click in Settings never waits for a port to
 * open or close. The session token is made once per launch and never saved.
 */

internal sealed class DesktopWebStatus {
    object Off : DesktopWebStatus()

    class Running(val port: Int, val lan: Boolean, val lanUrl: String?) : DesktopWebStatus() {
        val localUrl: String get() = "http://localhost:$port/"
    }

    class Failed(val message: String) : DesktopWebStatus()
}

internal object DesktopWebNetwork {
    /** The machine's private IPv4 addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x); empty on any error. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}

internal object DesktopWebRuntime {

    /** Per launch, never saved, only ever sent as an HttpOnly cookie. */
    private val token: String = DesktopWebAuth.newToken()

    private val _pairingCode = MutableStateFlow(DesktopWebAuth.newPairingCode())
    val pairingCode: StateFlow<String> = _pairingCode

    private val _status = MutableStateFlow<DesktopWebStatus>(DesktopWebStatus.Off)
    val status: StateFlow<DesktopWebStatus> = _status

    /** Handlers publish `signedIn`, `libraryChanged` and `voice` events through this (WEB2 uses it). */
    val events = DesktopWebEventHub()

    private val router = DesktopWebRouter().also {
        DesktopWebBasics(DesktopWebHostImpl, events).install(it)
        DesktopWebApi(events = events).install(it) // WEB 2 hook: the contract's data endpoints
    }

    private val gateway = DesktopWebGateway(
        router = router,
        token = token,
        pairingCode = { _pairingCode.value },
        lanAddresses = { DesktopWebNetwork.lanAddresses() },
        log = { line -> DesktopTrackLog.log(line) },
    )

    private val server = DesktopWebServer(gateway) { line -> DesktopTrackLog.log(line) }

    private val control = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Muses-Web-Control").apply { isDaemon = true }
    }

    /** `--web` turns the server on for this launch without changing the saved switch. */
    @Volatile
    private var forced = false

    /** Only touched on the control thread. */
    private var running: WebServerConfig? = null

    fun forceOn() {
        forced = true
    }

    fun newPairingCode() {
        _pairingCode.value = DesktopWebAuth.newPairingCode()
    }

    /** Makes the server match the settings. Safe to call at any time and as often as wanted. */
    fun refresh() {
        runCatching {
            control.execute {
                runCatching { apply() }.onFailure {
                    DesktopTrackLog.log("web: could not apply settings (${it::class.java.simpleName})")
                }
            }
        }
    }

    private fun apply() {
        val wanted = forced || DesktopWebSettings.enabled.value
        if (!wanted) {
            if (server.isRunning) server.stop()
            running = null
            _status.value = DesktopWebStatus.Off
            DesktopTrayMenu.setWebOpener(null)
            return
        }
        val config = WebServerConfig(DesktopWebSettings.port.value, DesktopWebSettings.lan.value)
        if (running == config && server.isRunning) return
        server.start(config).fold(
            onSuccess = { port ->
                running = config
                val lanUrl = if (config.lan) DesktopWebNetwork.lanAddresses().firstOrNull()?.let { "http://$it:$port/" } else null
                _status.value = DesktopWebStatus.Running(port, config.lan, lanUrl)
                DesktopTrayMenu.setWebOpener { openInBrowser() }
            },
            onFailure = {
                running = null
                _status.value = DesktopWebStatus.Failed(
                    "Could not start: ports ${config.port} to ${config.port + DesktopWebServer.PORT_TRIES} are busy or blocked.",
                )
                DesktopTrayMenu.setWebOpener(null)
                DesktopTrackLog.log("web: could not start (${it::class.java.simpleName})")
            },
        )
    }

    /** Opens the page on this computer; the loopback page load is what hands the browser its session. */
    fun openInBrowser() {
        val port = server.boundPort
        if (port > 0) DesktopExternalLinks.open("http://localhost:$port/")
    }

    /** Stops the server now (used by Quit). */
    fun shutdown() {
        runCatching { server.stop() }
    }
}
