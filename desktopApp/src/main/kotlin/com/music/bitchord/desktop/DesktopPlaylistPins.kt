package com.music.bitchord.desktop

/** UI U2: which playlists the listener pinned, and the pure ordering that puts them first. */
internal object DesktopPlaylistPins {
    const val KEY = "pinned_playlist_ids"

    fun decode(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun encode(ids: List<String>): String = ids.distinct().joinToString("\n")

    /** Pins [id] when it is not pinned (newest pin last), unpins it when it is. */
    fun toggle(pinned: List<String>, id: String): List<String> =
        if (id in pinned) pinned - id else pinned + id

    /**
     * Pinned items first, in the order they were pinned; everything else keeps its own order.
     * Ids that match no item are ignored. Never drops or repeats an item.
     */
    fun <T> order(items: List<T>, pinned: List<String>, idOf: (T) -> String): List<T> {
        if (pinned.isEmpty() || items.isEmpty()) return items
        val rank = pinned.withIndex().associate { (index, id) -> id to index }
        val (front, rest) = items.partition { idOf(it) in rank }
        return front.sortedBy { rank.getValue(idOf(it)) } + rest
    }
}
