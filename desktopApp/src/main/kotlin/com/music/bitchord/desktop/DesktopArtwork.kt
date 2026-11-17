package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.net.URI

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
 */
internal object DesktopArtworkCache {

    /**
     * A sixth of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() = images.clear()

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }
        return runCatching {
            URI(url).toURL().openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use { stream -> stream.readBytes().decodeToImageBitmap() }
        }.getOrNull()?.also { images.put(url, it) }
    }
}


/**
 * Window-space bounds of every live [DesktopArtwork], keyed by the ORIGINAL url (not the sized one),
 * so the artwork transition can launch from the cover that was actually clicked.
 *
 * Each instance owns one [Entry] and overwrites its rect on layout; nothing is allocated per frame.
 */
internal object DesktopArtBounds {
    internal class Entry { @Volatile var rect: Rect? = null }

    private val entries = HashMap<String, MutableList<Entry>>()

    /** Last mouse press in window px, and when. Written by the root observer, read by playCurrent(). */
    @Volatile var lastPress: Offset? = null
    @Volatile var lastPressNanos: Long = 0L

    fun register(url: String, entry: Entry) = synchronized(entries) {
        entries.getOrPut(url) { ArrayList(2) }.add(entry)
    }

    fun unregister(url: String, entry: Entry) = synchronized(entries) {
        entries[url]?.let { list ->
            list.remove(entry)
            if (list.isEmpty()) entries.remove(url)
        }
    }

    /** Instance nearest the pointer (0 distance if it contains it), or the first laid-out one without a pointer. */
    fun nearest(url: String?, pointer: Offset?): Rect? {
        if (url.isNullOrBlank()) return null
        synchronized(entries) {
            val list = entries[url] ?: return null
            var best: Rect? = null
            var bestD = Float.MAX_VALUE
            for (e in list) {
                val r = e.rect ?: continue
                if (pointer == null) return r
                val d = if (r.contains(pointer)) 0f else {
                    val dx = pointer.x - r.center.x
                    val dy = pointer.y - r.center.y
                    dx * dx + dy * dy
                }
                if (d < bestD) { bestD = d; best = r }
            }
            return best
        }
    }
}

/** Artwork, fetched at the size the surface drawing it actually needs. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    px: Int? = null,
) {
    val sized = if (px != null) url.artworkAt(px) else url
    val boundsEntry = remember { DesktopArtBounds.Entry() }
    DisposableEffect(url) {
        if (url != null) DesktopArtBounds.register(url, boundsEntry)
        onDispose {
            boundsEntry.rect = null
            if (url != null) DesktopArtBounds.unregister(url, boundsEntry)
        }
    }
    val modifier = modifier.onGloballyPositioned { boundsEntry.rect = it.boundsInWindow() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized) {
        value = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized)
        }
    }
    if (bitmap == null) {
        Box(modifier.background(Color(0xFF2B2B35)))
    } else {
        Image(
            painter = BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
