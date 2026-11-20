package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
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

    /** The decoded bitmap if it is already held; never downloads. */
    fun cached(url: String?): ImageBitmap? = if (url.isNullOrBlank()) null else images.get(url)

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
    // Lets the artwork transition find the thumbnail that was clicked. Keyed by the ORIGINAL url.
    val reportKey = if (url.isNullOrBlank()) null else "url:$url"
    val myCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val sourceFade = DesktopArtTransition.sourceFade.collectAsState()
    val reported = if (reportKey == null) modifier else modifier
        .onGloballyPositioned { myCoords[0] = it; DesktopArtTransition.reportSource(reportKey, it) }
        // The clicked artwork dissolves into the particles: it fades out while they leave it.
        .graphicsLayer { alpha = if (DesktopArtTransition.isSource(myCoords[0])) 1f - sourceFade.value else 1f }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized) {
        val loaded = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized)
        }
        // Artwork transition: remember which (already cached) size this thumbnail really drew.
        if (loaded != null) DesktopArtTransition.noteLoaded(originalUrl = url, loadedUrl = sized)
        value = loaded
    }
    if (bitmap == null) {
        Box(reported.background(Color(0xFF2B2B35)))
    } else {
        Image(
            painter = BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = reported,
        )
    }
}
