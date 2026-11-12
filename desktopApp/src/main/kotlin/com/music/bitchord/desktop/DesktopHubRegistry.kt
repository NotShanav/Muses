package com.music.bitchord.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Why a registry edit was refused. */
internal enum class HubError { INVALID_URL, INVALID_NAME, LIMIT_REACHED, NOT_FOUND }

/** Result of an edit: the registry to use next (unchanged on error) and the refusal reason, if any. */
internal data class HubChange(val registry: HubRegistry, val error: HubError? = null) {
    val ok: Boolean get() = error == null
}

/** Accepts only http/https URLs. A bare host such as "youtube.com" becomes https. */
internal object HubUrls {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** The cleaned URL, or null when it is not an acceptable http/https URL. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > HubLimits.MAX_URL_LENGTH) return null
        if (text.any { it.isWhitespace() }) return null
        val hasScheme = schemePrefix.containsMatchIn(text)
        val candidate = if (hasScheme) text else "https://$text"
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null
        // Lower-case only the scheme text; the rest of the URL is kept as typed.
        return scheme + candidate.substring(scheme.length)
    }
}

/**
 * The service list plus Hub settings. Immutable: every edit returns a new registry.
 * Persistence is done by [HubRegistryStore].
 */
internal data class HubRegistry(
    val services: List<HubService> = defaultServices(),
    val settings: HubSettings = HubSettings(),
) {
    fun find(id: String): HubService? = services.firstOrNull { it.id == id }

    fun add(name: String, rawUrl: String): HubChange {
        val cleanName = cleanName(name) ?: return fail(HubError.INVALID_NAME)
        val url = HubUrls.normalize(rawUrl) ?: return fail(HubError.INVALID_URL)
        if (services.size >= HubLimits.MAX_SERVICES) return fail(HubError.LIMIT_REACHED)
        val service = HubService(id = uniqueId(cleanName, services.map { it.id }.toSet()), name = cleanName, startUrl = url)
        return HubChange(copy(services = services + service))
    }

    fun remove(id: String): HubChange {
        if (find(id) == null) return fail(HubError.NOT_FOUND)
        return HubChange(copy(services = services.filterNot { it.id == id }))
    }

    fun rename(id: String, name: String): HubChange {
        if (find(id) == null) return fail(HubError.NOT_FOUND)
        val cleanName = cleanName(name) ?: return fail(HubError.INVALID_NAME)
        return replace(id) { it.copy(name = cleanName) }
    }

    fun updateUrl(id: String, rawUrl: String): HubChange {
        if (find(id) == null) return fail(HubError.NOT_FOUND)
        val url = HubUrls.normalize(rawUrl) ?: return fail(HubError.INVALID_URL)
        return replace(id) { it.copy(startUrl = url) }
    }

    fun setEnabled(id: String, enabled: Boolean): HubChange = replace(id) { it.copy(enabled = enabled) }

    fun setKeepAlive(id: String, keepAlive: Boolean): HubChange = replace(id) { it.copy(keepAlive = keepAlive) }

    /** Moves a service to [toIndex] (clamped into the list). */
    fun move(id: String, toIndex: Int): HubChange {
        val from = services.indexOfFirst { it.id == id }
        if (from < 0) return fail(HubError.NOT_FOUND)
        val list = services.toMutableList()
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        return HubChange(copy(services = list))
    }

    /** Moves a service up (negative) or down (positive) by [delta] places. */
    fun moveBy(id: String, delta: Int): HubChange {
        val from = services.indexOfFirst { it.id == id }
        if (from < 0) return fail(HubError.NOT_FOUND)
        return move(id, from + delta)
    }

    fun updateSettings(next: HubSettings): HubRegistry = copy(settings = next.validated())

    /** Restores the default service list; settings are kept. */
    fun resetServices(): HubRegistry = copy(services = defaultServices())

    private fun fail(error: HubError) = HubChange(this, error)

    private fun replace(id: String, edit: (HubService) -> HubService): HubChange {
        if (find(id) == null) return fail(HubError.NOT_FOUND)
        return HubChange(copy(services = services.map { if (it.id == id) edit(it) else it }))
    }

    companion object {
        fun defaults(): HubRegistry = HubRegistry()

        fun defaultServices(): List<HubService> = listOf(
            HubService("youtube", "YouTube", "https://www.youtube.com"),
            HubService("netflix", "Netflix", "https://www.netflix.com"),
            HubService("prime-video", "Prime Video", "https://www.primevideo.com"),
            HubService("jiohotstar", "JioHotstar", "https://www.hotstar.com"),
            HubService("twitch", "Twitch", "https://www.twitch.tv"),
        )

        internal fun cleanName(name: String): String? {
            val text = name.trim()
            return text.takeIf { it.isNotEmpty() && it.length <= HubLimits.MAX_NAME_LENGTH }
        }

        internal fun slug(name: String): String {
            val dashed = name.lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
            return dashed.ifEmpty { "service" }
        }

        internal fun uniqueId(name: String, taken: Set<String>): String {
            val base = slug(name)
            if (base !in taken) return base
            var n = 2
            while ("$base-$n" in taken) n++
            return "$base-$n"
        }
    }
}

/** JSON shape stored on disk. */
@Serializable
internal data class HubRegistryData(
    val version: Int = HubRegistryCodec.CURRENT_VERSION,
    val services: List<HubService> = emptyList(),
    val settings: HubSettings = HubSettings(),
)

internal object HubRegistryCodec {
    const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(registry: HubRegistry): String =
        json.encodeToString(HubRegistryData(CURRENT_VERSION, registry.services, registry.settings))

    /** Null for corrupt JSON or a payload from a newer, unknown version. */
    fun decode(raw: String): HubRegistry? {
        val data = runCatching { json.decodeFromString<HubRegistryData>(raw) }.getOrNull() ?: return null
        if (data.version > CURRENT_VERSION) return null
        return HubRegistry(sanitize(data.services), data.settings.validated())
    }

    /** Drops entries with a bad URL or name, fixes blank or duplicate ids, enforces the size cap. */
    private fun sanitize(services: List<HubService>): List<HubService> {
        val out = ArrayList<HubService>()
        val ids = HashSet<String>()
        for (raw in services) {
            if (out.size >= HubLimits.MAX_SERVICES) break
            val name = HubRegistry.cleanName(raw.name) ?: continue
            val url = HubUrls.normalize(raw.startUrl) ?: continue
            val wanted = raw.id.trim()
            val id = if (wanted.isEmpty() || wanted in ids) HubRegistry.uniqueId(name, ids) else wanted
            ids += id
            out += raw.copy(id = id, name = name, startUrl = url)
        }
        return out
    }
}

/** Tiny key/value seam so persistence can be faked in tests. */
internal interface HubKeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Production store: the same OS-backed [DesktopPersistence] every other desktop setting uses. */
internal class DesktopHubKeyValueStore(
    private val persistence: DesktopPersistence = DesktopPersistence(),
) : HubKeyValueStore {
    override fun read(key: String): String? = persistence.string(key, "").ifBlank { null }

    override fun write(key: String, value: String) {
        persistence.saveString(key, value)
    }
}

/** Loads and saves the registry. A missing, corrupt, or too-new payload yields the defaults. */
internal class HubRegistryStore(
    private val store: HubKeyValueStore = DesktopHubKeyValueStore(),
) {
    fun load(): HubRegistry {
        val raw = store.read(KEY) ?: return HubRegistry.defaults()
        return HubRegistryCodec.decode(raw) ?: HubRegistry.defaults()
    }

    fun save(registry: HubRegistry) {
        store.write(KEY, HubRegistryCodec.encode(registry))
    }

    companion object {
        const val KEY = "hub_registry_v1"
    }
}
