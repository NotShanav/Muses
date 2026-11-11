package com.music.bitchord.desktop

/**
 * A small least-recently-used map bounded by entry count rather than bytes; the artwork cache's
 * [DesktopByteBudgetLru] is the byte-sized sibling. Kept free of Compose and Skia so it can be
 * tested on its own.
 */
internal class DesktopCountLru<V : Any>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "a cache needs room for at least one entry" }
    }

    /** Access-ordered, so iteration hands back the coldest entry first. */
    private val entries = LinkedHashMap<String, V>(32, 0.75f, true)

    val size: Int get() = synchronized(entries) { entries.size }

    fun get(key: String): V? = synchronized(entries) { entries[key] }

    fun contains(key: String): Boolean = synchronized(entries) { entries.containsKey(key) }

    fun put(key: String, value: V) = synchronized(entries) {
        entries[key] = value
        // The entry that has just arrived is the newest, so eviction never takes it.
        val coldest = entries.keys.iterator()
        while (entries.size > maxEntries && coldest.hasNext()) {
            coldest.next()
            coldest.remove()
        }
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
