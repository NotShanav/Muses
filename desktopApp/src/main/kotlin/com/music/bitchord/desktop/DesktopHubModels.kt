package com.music.bitchord.desktop

import kotlinx.serialization.Serializable

/**
 * Muses Hub (beta) pure data. No I/O, no Compose, no platform calls.
 * See HUB_PLAN.md sections 3 and 4.
 */

/** One web service the Hub can open in a browser app-mode window. */
@Serializable
internal data class HubService(
    val id: String,
    val name: String,
    val startUrl: String,
    /** Disabled services stay in the list but are not shown as launch tiles. */
    val enabled: Boolean = true,
    /** May stay live in the background (still subject to the memory budget). */
    val keepAlive: Boolean = false,
)

/** Runtime state of a service. Never persisted. */
internal enum class HubServiceState {
    /** Window closed; only id and start URL are kept. */
    HIBERNATED,

    /** Window open in the background. */
    LIVE,

    /** The service the user is currently using. At most one. */
    ACTIVE,
}

/** Which Chromium browser to launch. AUTO = Brave, then Chrome, then Edge. */
@Serializable
internal enum class HubBrowserPreference { AUTO, BRAVE, CHROME, EDGE }

internal object HubLimits {
    const val DEFAULT_WARN_MB = 1600
    const val DEFAULT_CAP_MB = 2048
    const val DEFAULT_MAX_LIVE = 1
    const val MIN_WARN_MB = 256
    const val MAX_MB = 16384
    const val MAX_LIVE = 4
    const val MAX_SERVICES = 24
    const val MAX_NAME_LENGTH = 40
    const val MAX_URL_LENGTH = 2048
}

@Serializable
internal data class HubSettings(
    val browserPreference: HubBrowserPreference = HubBrowserPreference.AUTO,
    /** Soft warning threshold for total memory (Muses + service process trees). */
    val memoryWarnMb: Int = HubLimits.DEFAULT_WARN_MB,
    /** Hard ceiling; above it the governor closes least-recently-used non-active services. */
    val memoryCapMb: Int = HubLimits.DEFAULT_CAP_MB,
    val maxLiveServices: Int = HubLimits.DEFAULT_MAX_LIVE,
) {
    /** Clamps every number into a safe range; cap is never below warn. */
    fun validated(): HubSettings {
        val warn = memoryWarnMb.coerceIn(HubLimits.MIN_WARN_MB, HubLimits.MAX_MB)
        val cap = memoryCapMb.coerceIn(warn, HubLimits.MAX_MB)
        return copy(
            memoryWarnMb = warn,
            memoryCapMb = cap,
            maxLiveServices = maxLiveServices.coerceIn(1, HubLimits.MAX_LIVE),
        )
    }
}
