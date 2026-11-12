package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

internal enum class DesktopHomeSectionOrigin { APP, PROVIDER, LOCAL }

@Serializable
internal data class DesktopSeenHomeShelf(
    val sectionId: String,
    val displayName: String,
    val origin: String = DesktopHomeSectionOrigin.PROVIDER.name,
    val itemKind: String = "unknown",
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

@Serializable
internal data class DesktopHomeSectionPrefs(
    val hiddenIds: Set<String> = emptySet(),
    val orderIds: List<String> = emptyList(),
    val pinnedIds: Set<String> = emptySet(),
    val showNewShelvesAutomatically: Boolean = true,
    val seenShelves: List<DesktopSeenHomeShelf> = emptyList(),
) {
    fun validated(): DesktopHomeSectionPrefs = copy(
        // Keep unknown IDs so an import from a newer build does not unhide them if they return.
        hiddenIds = hiddenIds.map { it.takeSafeId() }.toSet().take(500).toSet(),
        orderIds = orderIds.map { it.takeSafeId() }.distinct().take(200),
        pinnedIds = pinnedIds.map { it.takeSafeId() }.toSet().take(200).toSet(),
        seenShelves = seenShelves
            .filter { it.sectionId.isNotBlank() }
            .distinctBy(DesktopSeenHomeShelf::sectionId)
            .sortedByDescending(DesktopSeenHomeShelf::lastSeenMs)
            .take(DesktopHomeSections.MAX_SEEN)
            .map { it.copy(displayName = it.displayName.take(160), firstSeenMs = it.firstSeenMs.coerceAtLeast(0), lastSeenMs = it.lastSeenMs.coerceAtLeast(0)) },
    )

    private fun String.takeSafeId(): String = take(180).filterNot { Character.isISOControl(it) }
}

internal data class DesktopHomeSectionDescriptor(
    val sectionId: String,
    val displayName: String,
    val origin: DesktopHomeSectionOrigin,
    val itemKind: String,
    val firstSeenMs: Long? = null,
    val lastSeenMs: Long? = null,
    val locked: Boolean = false,
    val supportsReorder: Boolean = true,
)

/** Desktop-only ID derivation, discovery, and view filter for Home shelves. */
internal object DesktopHomeSections {
    const val MAX_SEEN = 200

    private data class KnownShelf(val id: String, val name: String, val origin: DesktopHomeSectionOrigin, val aliases: Set<String>)

    private val known = listOf(
        KnownShelf("app:recently-played", "Recently played", DesktopHomeSectionOrigin.APP, setOf("recently played", "recently played tracks", "recents")),
        KnownShelf("provider:quick-picks", "Quick picks", DesktopHomeSectionOrigin.PROVIDER, setOf("quick picks", "quick picks for you")),
        KnownShelf("provider:albums-singles", "Albums & singles", DesktopHomeSectionOrigin.PROVIDER, setOf("albums and singles", "albums singles", "new albums and singles")),
        KnownShelf("provider:trending-songs", "Trending songs for you", DesktopHomeSectionOrigin.PROVIDER, setOf("trending songs for you", "trending songs")),
        KnownShelf("provider:daily-discover", "Daily discover", DesktopHomeSectionOrigin.PROVIDER, setOf("daily discover", "discover mix")),
        KnownShelf("provider:from-your-library", "From your library", DesktopHomeSectionOrigin.PROVIDER, setOf("from your library", "from your library gallery")),
        KnownShelf("provider:continue-listening", "Continue listening", DesktopHomeSectionOrigin.PROVIDER, setOf("continue listening", "continue listening to")),
        KnownShelf("provider:mixes", "Mixes", DesktopHomeSectionOrigin.PROVIDER, setOf("mixes", "your mixes", "made for you")),
        KnownShelf("provider:forgotten-favourites", "Forgotten favourites", DesktopHomeSectionOrigin.PROVIDER, setOf("forgotten favourites", "forgotten favorites")),
        KnownShelf("provider:listen-again", "Listen again", DesktopHomeSectionOrigin.PROVIDER, setOf("listen again", "listen again to")),
    )
    private val aliases = known.flatMap { shelf -> shelf.aliases.map(::normalizeTitle).map { it to shelf } }.toMap()

    fun identify(shelf: HomeShelf): DesktopSeenHomeShelf {
        val normalized = normalizeTitle(shelf.title)
        val kind = itemKind(shelf.items)
        val exact = aliases[normalized]
        if (exact != null) {
            return DesktopSeenHomeShelf(exact.id, shelf.title, exact.origin.name, kind, 0, 0)
        }
        val pattern = when {
            PERSONALIZED_BECAUSE.containsMatchIn(normalized) -> "because-you-listened"
            PERSONALIZED_MORE_FROM.containsMatchIn(normalized) -> "more-from"
            PERSONALIZED_FANS.containsMatchIn(normalized) -> "for-fans-of"
            DAILY_MIX.containsMatchIn(normalized) -> "daily-mix"
            else -> null
        }
        val slug = pattern ?: normalized
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(100)
            .ifBlank { "untitled" }
        val id = if (pattern == null) "provider:$slug:$kind" else "provider:pattern:$pattern"
        return DesktopSeenHomeShelf(id, shelf.title, DesktopHomeSectionOrigin.PROVIDER.name, kind, 0, 0)
    }

    fun observe(
        shelves: List<HomeShelf>,
        prefs: DesktopHomeSectionPrefs,
        nowMs: Long,
    ): DesktopHomeSectionPrefs {
        val existing = prefs.seenShelves.associateBy(DesktopSeenHomeShelf::sectionId).toMutableMap()
        val hidden = prefs.hiddenIds.toMutableSet()
        shelves.asSequence().map(::identify).distinctBy(DesktopSeenHomeShelf::sectionId).forEach { current ->
            val previous = existing[current.sectionId]
            if (previous == null && !prefs.showNewShelvesAutomatically) hidden += current.sectionId
            existing[current.sectionId] = current.copy(
                firstSeenMs = previous?.firstSeenMs?.takeIf { it > 0 } ?: nowMs,
                // A shelf is observed on first render and on a later feed refresh, not on every
                // recomposition. The minute guard also makes the operation idempotent.
                lastSeenMs = if (previous == null || nowMs - previous.lastSeenMs >= OBSERVE_INTERVAL_MS) nowMs else previous.lastSeenMs,
            )
        }
        return prefs.copy(
            hiddenIds = hidden,
            seenShelves = existing.values.sortedByDescending(DesktopSeenHomeShelf::lastSeenMs).take(MAX_SEEN),
        ).validated()
    }

    fun descriptors(prefs: DesktopHomeSectionPrefs): List<DesktopHomeSectionDescriptor> {
        val seen = prefs.seenShelves.associateBy(DesktopSeenHomeShelf::sectionId)
        val builtIns = known.map { item ->
            val discovered = seen[item.id]
            DesktopHomeSectionDescriptor(
                sectionId = item.id,
                displayName = discovered?.displayName ?: item.name,
                origin = item.origin,
                itemKind = discovered?.itemKind ?: "unknown",
                firstSeenMs = discovered?.firstSeenMs?.takeIf { it > 0 },
                lastSeenMs = discovered?.lastSeenMs?.takeIf { it > 0 },
            )
        }
        val knownIds = known.map(KnownShelf::id).toSet()
        val extra = prefs.seenShelves.filterNot { it.sectionId in knownIds }.map { item ->
            DesktopHomeSectionDescriptor(
                item.sectionId,
                item.displayName,
                runCatching { DesktopHomeSectionOrigin.valueOf(item.origin) }.getOrDefault(DesktopHomeSectionOrigin.PROVIDER),
                item.itemKind,
                item.firstSeenMs,
                item.lastSeenMs,
            )
        }
        val order = prefs.orderIds.withIndex().associate { it.value to it.index }
        return (builtIns + extra).sortedWith(
            compareByDescending<DesktopHomeSectionDescriptor> { it.sectionId in prefs.pinnedIds }
                .thenBy { order[it.sectionId] ?: Int.MAX_VALUE }
                .thenByDescending { it.lastSeenMs ?: 0L }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )
    }

    fun filterAndOrder(shelves: List<HomeShelf>, prefs: DesktopHomeSectionPrefs): List<HomeShelf> {
        val order = prefs.orderIds.withIndex().associate { it.value to it.index }
        return shelves.withIndex()
            .filterNot { (_, shelf) -> identify(shelf).sectionId in prefs.hiddenIds }
            .sortedWith(
                compareBy<IndexedValue<HomeShelf>> { identify(it.value).sectionId !in prefs.pinnedIds }
                    .thenBy { order[identify(it.value).sectionId] ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map(IndexedValue<HomeShelf>::value)
    }

    fun move(prefs: DesktopHomeSectionPrefs, sectionId: String, delta: Int): DesktopHomeSectionPrefs {
        val rows = descriptors(prefs).map(DesktopHomeSectionDescriptor::sectionId).toMutableList()
        val from = rows.indexOf(sectionId)
        if (from < 0) return prefs
        val to = (from + delta).coerceIn(0, rows.lastIndex)
        if (from == to) return prefs
        rows.add(to, rows.removeAt(from))
        return prefs.copy(orderIds = rows).validated()
    }

    fun hideAllProvider(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs =
        prefs.copy(hiddenIds = prefs.hiddenIds + descriptors(prefs).filter { it.origin == DesktopHomeSectionOrigin.PROVIDER }.map { it.sectionId }).validated()

    fun hideOtherShelves(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs {
        val knownIds = known.map(KnownShelf::id).toSet()
        return prefs.copy(hiddenIds = prefs.hiddenIds + prefs.seenShelves.map(DesktopSeenHomeShelf::sectionId).filterNot { it in knownIds }).validated()
    }

    fun showOnlyLibrary(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs =
        prefs.copy(
            hiddenIds = prefs.hiddenIds + descriptors(prefs)
                .filter { it.sectionId !in setOf("app:recently-played", "provider:from-your-library") }
                .map { it.sectionId },
        ).validated()

    fun unhideAll(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs = prefs.copy(hiddenIds = emptySet()).validated()
    fun clearSeen(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs = prefs.copy(seenShelves = emptyList()).validated()
    fun reset(prefs: DesktopHomeSectionPrefs): DesktopHomeSectionPrefs = DesktopHomeSectionPrefs()

    internal fun normalizeTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun itemKind(items: List<ShelfItem>): String {
        val tracks = items.any { it.videoId != null }
        val collections = items.any { it.browseId != null }
        return when {
            tracks && collections -> "mixed"
            tracks -> "tracks"
            collections -> "collections"
            else -> "other"
        }
    }

    private val PERSONALIZED_BECAUSE = Regex("\\bbecause you listened to\\b")
    private val PERSONALIZED_MORE_FROM = Regex("\\bmore from\\b")
    private val PERSONALIZED_FANS = Regex("\\bfor fans of\\b")
    private val DAILY_MIX = Regex("\\bdaily mix(?:es)?\\b")
    private const val OBSERVE_INTERVAL_MS = 60_000L
}

/** Records provider shelves only after they have reached the desktop Home renderer. */
@Composable
internal fun rememberDesktopManagedHomeState(state: UiState<List<HomeShelf>>): UiState<List<HomeShelf>> {
    val config by DesktopAdvancedAppearance.config.collectAsState()
    val success = state as? UiState.Success<List<HomeShelf>>
    val shelves = success?.data
    val observed = remember(shelves, config.homeSections) {
        shelves?.let { DesktopHomeSections.observe(it, config.homeSections, System.currentTimeMillis()) }
            ?: config.homeSections
    }
    LaunchedEffect(shelves, observed) {
        if (shelves != null && observed != config.homeSections) {
            DesktopAdvancedAppearance.updateHomeSections { observed }
        }
    }
    val visible = remember(shelves, observed) {
        shelves?.let { DesktopHomeSections.filterAndOrder(it, observed) }
    }
    return if (success != null) UiState.Success(visible.orEmpty()) else state
}

@Composable
internal fun DesktopHomeSectionManagerPage(onDismiss: () -> Unit) {
    val config by DesktopAdvancedAppearance.config.collectAsState()
    val prefs = config.homeSections
    val rows = remember(prefs) { DesktopHomeSections.descriptors(prefs) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DesktopSettingsPageFrame {
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Customise Home", style = MaterialTheme.typography.headlineMedium)
                    Text("Hide, pin, and order Home shelves. Changes only affect this desktop view.", color = DesktopSecondary)
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(12.dp))
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::hideAllProvider) }) { Text("Hide provider") }
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::hideOtherShelves) }) { Text("Hide other") }
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::showOnlyLibrary) }) { Text("My library only") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::unhideAll) }) { Text("Unhide all") }
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::clearSeen) }) { Text("Clear seen") }
                    TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections(DesktopHomeSections::reset) }) { Text("Reset page") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show new shelves automatically", style = MaterialTheme.typography.titleSmall)
                    Text("When off, newly discovered provider shelves start hidden.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = prefs.showNewShelvesAutomatically,
                    onCheckedChange = { enabled -> DesktopAdvancedAppearance.updateHomeSections { it.copy(showNewShelvesAutomatically = enabled) } },
                )
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(rows, key = DesktopHomeSectionDescriptor::sectionId) { row ->
                    val hidden = row.sectionId in prefs.hiddenIds
                    val pinned = row.sectionId in prefs.pinnedIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${row.origin.name.lowercase().replaceFirstChar(Char::uppercase)} · ${row.itemKind}" +
                                (row.lastSeenMs?.let { " · Seen ${java.time.Instant.ofEpochMilli(it)}" } ?: " · Built in"),
                                color = DesktopSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        TextButton(onClick = {
                            DesktopAdvancedAppearance.updateHomeSections { it.copy(pinnedIds = if (pinned) it.pinnedIds - row.sectionId else it.pinnedIds + row.sectionId) }
                        }) { Text(if (pinned) "Unpin" else "Pin") }
                        TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections { DesktopHomeSections.move(it, row.sectionId, -1) } }) { Text("↑") }
                        TextButton(onClick = { DesktopAdvancedAppearance.updateHomeSections { DesktopHomeSections.move(it, row.sectionId, 1) } }) { Text("↓") }
                        Switch(checked = !hidden, onCheckedChange = { visible ->
                            DesktopAdvancedAppearance.updateHomeSections { it.copy(hiddenIds = if (visible) it.hiddenIds - row.sectionId else it.hiddenIds + row.sectionId) }
                            if (!visible) {
                                scope.launch {
                                    if (snackbar.showSnackbar("Hidden ${row.displayName}", "Undo") == SnackbarResult.ActionPerformed) {
                                        DesktopAdvancedAppearance.updateHomeSections { it.copy(hiddenIds = it.hiddenIds - row.sectionId) }
                                    }
                                }
                            }
                        })
                    }
                    HorizontalDivider(color = desktopChromeDivider())
                }
            }
            SnackbarHost(snackbar)
        }
    }
}
