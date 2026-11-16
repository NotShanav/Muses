package com.music.bitchord.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.ceil

/*
 * 360 T4: dither noise for the album backdrop. A dark, slow gradient quantised to 8 bits shows steps;
 * a barely visible grain (about 2 percent) turns each step edge into noise the eye does not see.
 *
 * One 512 x 512 tile is built once (premultiplied BGRA, half of the pixels white and half black, each at
 * alpha [DITHER_ALPHA] / 255) and drawn 1:1 with plain `drawImage` calls, the same call and the same
 * Skia bitmap path the repo already uses (DesktopArtworkMesh, DesktopCanvasVideo). No shader, no
 * per-frame allocation: the loop only builds packed IntOffset / IntSize values.
 */

/** Side of the noise tile in pixels. */
internal const val DITHER_TILE = 512

/** Alpha of a noise pixel out of 255: 5 is about 2 percent. */
internal const val DITHER_ALPHA = 5

private const val DITHER_SEED = 0x5EED1234

/**
 * The tile's pixels as premultiplied ARGB ints: white noise pixels are (a, a, a, a) and black ones
 * (a, 0, 0, 0), so the tile is valid premultiplied data. Pure and deterministic.
 */
internal fun ditherPixels(side: Int = DITHER_TILE, seed: Int = DITHER_SEED, alpha: Int = DITHER_ALPHA): IntArray {
    val a = alpha.coerceIn(0, 255)
    val white = (a shl 24) or (a shl 16) or (a shl 8) or a
    val black = a shl 24
    val out = IntArray(side * side)
    // xorshift32: cheap, deterministic, no allocation.
    var x = if (seed == 0) 1 else seed
    for (i in out.indices) {
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        out[i] = if ((x and 0x100) != 0) white else black
    }
    return out
}

/** How many tiles cover [extent] pixels. */
internal fun ditherTileCount(extent: Float, tile: Int = DITHER_TILE): Int =
    if (extent <= 0f || tile <= 0) 0 else ceil(extent / tile).toInt()

private val ditherTile: ImageBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val side = DITHER_TILE
    val pixels = ditherPixels(side)
    val info = ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val bytes = ByteArray(side * side * 4)
    pixels.forEachIndexed { index, argb ->
        val at = index * 4
        bytes[at] = (argb and 0xFF).toByte()
        bytes[at + 1] = ((argb shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((argb shr 16) and 0xFF).toByte()
        bytes[at + 3] = ((argb shr 24) and 0xFF).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}

/** Draws the noise tile across the whole draw scope. A failure to build the tile is swallowed: no grain is better than no window. */
internal fun DrawScope.drawDither() {
    val image = try {
        ditherTile
    } catch (error: Throwable) {
        return
    }
    val columns = ditherTileCount(size.width)
    val rows = ditherTileCount(size.height)
    val tileSize = IntSize(DITHER_TILE, DITHER_TILE)
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = tileSize,
                dstOffset = IntOffset(column * DITHER_TILE, row * DITHER_TILE),
                dstSize = tileSize,
            )
        }
    }
}
