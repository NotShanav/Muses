package com.music.bitchord.desktop

/*
 * VOICE FIX X2 (Part A): the in-memory library index. Names are normalised and given phonetic keys
 * ONCE when the library changes, never per command. Everything here lives in memory only; nothing
 * is logged, written to disk or sent anywhere. [VoiceIndexHolder.clear] drops it (voice off, exit).
 */

internal enum class VoiceEntityKind { PLAYLIST, ARTIST, SONG, ALBUM }

/** Pre-computed comparison form of one phrase. Cheap to compare, built once. */
internal class VoiceTerms(
    val tokens: List<String>,
    val phonetic: List<String>,
    val compact: String,
    val joined: String,
) {
    companion object {
        fun of(text: String): VoiceTerms {
            val tokens = VoiceText.tokens(text)
            return VoiceTerms(
                tokens = tokens,
                phonetic = VoicePhonetic.keys(tokens),
                compact = VoicePhonetic.compactKey(tokens),
                joined = tokens.joinToString(""),
            )
        }
    }

    val isEmpty: Boolean get() = tokens.isEmpty()

    override fun toString(): String = "VoiceTerms(redacted)"
}

/** What the adapter hands to the builder. [id] is the app's own id (never logged). */
internal data class VoiceIndexInput(
    val kind: VoiceEntityKind,
    val title: String,
    val id: String = "",
    /** Songs only: the artist's name. */
    val artist: String = "",
    /** Playlists only. */
    val source: VoiceListSource? = null,
    val editable: Boolean = false,
) {
    override fun toString(): String = "VoiceIndexInput(" + kind + ")"
}

internal class VoiceIndexEntry(
    val input: VoiceIndexInput,
    val terms: VoiceTerms,
    /** Null unless this is a song with a known artist. */
    val artist: VoiceTerms?,
) {
    override fun toString(): String = "VoiceIndexEntry(" + input.kind + ")"
}

internal class VoiceLibraryIndex(val version: Long, val entries: List<VoiceIndexEntry>) {

    /** Empty [kinds] means every kind. */
    fun ofKinds(kinds: Set<VoiceEntityKind>): List<VoiceIndexEntry> =
        if (kinds.isEmpty()) entries else entries.filter { it.input.kind in kinds }

    companion object {
        val EMPTY = VoiceLibraryIndex(0L, emptyList())
    }
}

internal object VoiceIndexBuilder {

    /** Entries with no usable words are dropped; the same kind + id appears once. */
    fun build(version: Long, inputs: List<VoiceIndexInput>): VoiceLibraryIndex {
        val seen = HashSet<String>()
        val entries = ArrayList<VoiceIndexEntry>(inputs.size)
        for (item in inputs) {
            val terms = VoiceTerms.of(item.title)
            if (terms.isEmpty) continue
            if (item.id.isNotBlank() && !seen.add(item.kind.name + "#" + item.id)) continue
            val artist = if (item.kind == VoiceEntityKind.SONG && item.artist.isNotBlank()) {
                VoiceTerms.of(item.artist).takeIf { !it.isEmpty }
            } else {
                null
            }
            entries.add(VoiceIndexEntry(item, terms, artist))
        }
        return VoiceLibraryIndex(version, entries)
    }
}

/**
 * Keeps ONE index and rebuilds it only when the library version changes. Two ways to give it the
 * version: an explicit counter the app bumps when the library loads or goes stale ([get]), or a
 * content signature computed from the (cheap, un-normalised) inputs ([getFor]).
 */
internal class VoiceIndexHolder {
    private var current: VoiceLibraryIndex = VoiceLibraryIndex.EMPTY
    private var built = false

    /** How many times an index was actually built. Tests use this; nothing else should. */
    var builds: Int = 0
        private set

    @Synchronized
    fun get(version: Long, supply: () -> List<VoiceIndexInput>): VoiceLibraryIndex {
        if (built && current.version == version) return current
        current = VoiceIndexBuilder.build(version, supply())
        built = true
        builds += 1
        return current
    }

    /** Version = a hash of the inputs themselves, so no manual bumping is needed. */
    @Synchronized
    fun getFor(inputs: List<VoiceIndexInput>): VoiceLibraryIndex {
        val signature = inputs.hashCode().toLong()
        if (built && current.version == signature) return current
        current = VoiceIndexBuilder.build(signature, inputs)
        built = true
        builds += 1
        return current
    }

    /** The index built last (empty until one is built). Never builds; safe to call at command time. */
    @Synchronized
    fun current(): VoiceLibraryIndex = current

    @Synchronized
    fun clear() {
        current = VoiceLibraryIndex.EMPTY
        built = false
    }
}
