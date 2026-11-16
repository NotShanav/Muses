package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The album-colour engine, pure (no Compose runtime, no bitmaps) so it can be unit tested.
 *
 * ODEON-FIX (palette v2): the first version only counted strongly coloured pixels, so a cover that is
 * mostly white, black or skin-toned came out as just its red. Now every sampled pixel counts:
 * pixels are grouped into colour clusters in the perceptual OKLab space; the three biggest distinct
 * clusters become the dominant, second and third colour, whether they are vivid, white, black, grey
 * or a skin tone. A slight bias towards vivid colour keeps a small bright accent from losing to noise.
 *
 * Light covers: a white cluster is shown as a soft light grey (never pure white), and the palette
 * reports its overall [FluidPalette.brightness] so the backdrop can darken itself behind the text.
 */

private const val BUCKET_BITS = 4
private const val BUCKETS = 1 shl (BUCKET_BITS * 3)
private const val MAX_CLUSTERS = 14
/** Two colours closer than this (OKLab distance) are one cluster. */
private const val CLUSTER_DISTANCE = 0.10f
/** Chosen colours must be at least this far apart, so the three glows are really different. */
private const val MIN_SEPARATION = 0.16f
/** A second or third colour under this share of the cover is an accent, not a glow. */
private const val MIN_SHARE = 0.06f
/** How much chroma (vividness) adds to a cluster's weight. */
private const val CHROMA_BIAS = 1.2f
/** Below this chroma a colour counts as neutral (white, grey, black). */
private const val NEUTRAL_CHROMA = 0.035f
/** The lightest a glow may be (OKLab L): a soft light grey, never pure white. */
internal const val ALBUM_LIGHT_CAP = 0.80f
/**
 * 360 T4: a chromatic glow is never darker than this (OKLab L). Was 0.30; a dark but coloured cover
 * now reads as visibly tinted instead of near black.
 */
internal const val ALBUM_DARK_FLOOR_CHROMATIC = 0.42f
/** 360 T4: chroma multiplier of a chromatic glow (was 1.12); the result is still gamut-clipped in [fromLch]. */
internal const val ALBUM_CHROMA_BOOST = 1.25f
/** 360 T4: lightness of the base shade under the glows (was 0.18). */
internal const val ALBUM_BASE_LIGHTNESS = 0.22f
/** 360 T4: the least [FluidPalette.intensity] any cover gets, grey covers included (was 0.62). */
internal const val ALBUM_INTENSITY_FLOOR = 0.80f
private const val DARK_FLOOR_NEUTRAL = 0.20f
private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f

private class Lab(val l: Float, val a: Float, val b: Float) {
    val chroma: Float get() = sqrt(a * a + b * b)
    val hue: Float get() = wrapHueDeg(atan2(b, a) * RAD_TO_DEG)
}

private class Cluster(var l: Float, var a: Float, var b: Float, var n: Int) {
    val chroma: Float get() = sqrt(a * a + b * b)
    val hue: Float get() = wrapHueDeg(atan2(b, a) * RAD_TO_DEG)
}

/** A colour as OKLab lightness, chroma and hue (degrees), after it has been adjusted for display. */
private class Lch(val l: Float, val c: Float, val h: Float)

private fun wrapHueDeg(h: Float): Float = ((h % 360f) + 360f) % 360f

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float {
    val x = c.coerceIn(0f, 1f)
    return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
}

private fun cbrtF(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

private fun toLab(r8: Float, g8: Float, b8: Float): Lab {
    val r = srgbToLinear(r8)
    val g = srgbToLinear(g8)
    val b = srgbToLinear(b8)
    val l = cbrtF(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrtF(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrtF(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return Lab(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

/** OKLab lightness / chroma / hue to a colour; chroma is reduced until the colour fits the sRGB gamut. */
private fun fromLch(l: Float, c: Float, hDeg: Float): Color {
    var chroma = c.coerceAtLeast(0f)
    val hr = hDeg * DEG_TO_RAD
    var tries = 0
    while (true) {
        val a = chroma * cos(hr)
        val b = chroma * sin(hr)
        val l_ = l + 0.3963377774f * a + 0.2158037573f * b
        val m_ = l - 0.1055613458f * a - 0.0638541728f * b
        val s_ = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = l_ * l_ * l_
        val mc = m_ * m_ * m_
        val sc = s_ * s_ * s_
        val r = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        val g = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        val bl = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
        val inGamut = r in -0.002f..1.002f && g in -0.002f..1.002f && bl in -0.002f..1.002f
        if (inGamut || tries >= 14 || chroma < 0.0005f) {
            return Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
        }
        chroma *= 0.88f
        tries++
    }
}

private fun distance(a: Cluster, b: Cluster): Float {
    val dl = a.l - b.l
    val da = a.a - b.a
    val db = a.b - b.b
    return sqrt(dl * dl + da * da + db * db)
}

private fun distance(a: Cluster, b: Lab): Float {
    val dl = a.l - b.l
    val da = a.a - b.a
    val db = a.b - b.b
    return sqrt(dl * dl + da * da + db * db)
}

/** The colour a cluster is drawn as: keep its hue and vividness, move its lightness into the glow range. */
private fun displayOf(cluster: Cluster): Lch {
    val chroma = cluster.chroma
    val neutral = chroma < NEUTRAL_CHROMA
    val floor = if (neutral) DARK_FLOOR_NEUTRAL else ALBUM_DARK_FLOOR_CHROMATIC
    val l = cluster.l.coerceIn(floor, ALBUM_LIGHT_CAP)
    val c = if (neutral) chroma else (chroma * ALBUM_CHROMA_BOOST).coerceAtMost(0.32f) // 360 T4
    return Lch(l, c, cluster.hue)
}

/** A tonal variant of [base]: the same hue family (small shift), different lightness. */
private fun variantOf(base: Lch, hueShift: Float, lightnessShift: Float, chromaScale: Float): Lch =
    Lch(
        (base.l + lightnessShift).coerceIn(DARK_FLOOR_NEUTRAL, ALBUM_LIGHT_CAP),
        base.c * chromaScale,
        wrapHueDeg(base.h + hueShift),
    )

private fun Lch.toColor(): Color = fromLch(l, c, h)

/**
 * Three-colour palette of a cover: [FluidPalette.colors] is `[dominant, second, third, lighter tint of
 * the dominant]`. Returns null for an image under 2x2 or a pixel array that is too short.
 */
internal fun extractAlbumPalette(pixels: IntArray, width: Int, height: Int): FluidPalette? {
    if (width < 2 || height < 2) return null
    val n = width * height
    if (n <= 0 || pixels.size < n) return null

    // 1. Sample and bucket (4 bits per channel), keeping each bucket's mean colour.
    val count = IntArray(BUCKETS)
    val sumR = FloatArray(BUCKETS)
    val sumG = FloatArray(BUCKETS)
    val sumB = FloatArray(BUCKETS)
    var counted = 0
    // An odd stride, so sampling does not line up on one column of the cover.
    val step = (max(1, n / 3600)) or 1
    var i = 0
    while (i < n) {
        val p = pixels[i]
        i += step
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val idx = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        count[idx]++
        sumR[idx] += r / 255f
        sumG[idx] += g / 255f
        sumB[idx] += b / 255f
        counted++
    }
    if (counted == 0) return null

    // 2. Group buckets into clusters of similar colour, biggest buckets first.
    val order = (0 until BUCKETS).filter { count[it] > 0 }.sortedByDescending { count[it] }
    val clusters = ArrayList<Cluster>()
    for (idx in order) {
        val k = count[idx]
        val lab = toLab(sumR[idx] / k, sumG[idx] / k, sumB[idx] / k)
        var nearest: Cluster? = null
        var nearestDistance = Float.MAX_VALUE
        for (cl in clusters) {
            val d = distance(cl, lab)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = cl
            }
        }
        if (nearest != null && (nearestDistance < CLUSTER_DISTANCE || clusters.size >= MAX_CLUSTERS)) {
            val total = nearest.n + k
            nearest.l = (nearest.l * nearest.n + lab.l * k) / total
            nearest.a = (nearest.a * nearest.n + lab.a * k) / total
            nearest.b = (nearest.b * nearest.n + lab.b * k) / total
            nearest.n = total
        } else {
            clusters += Cluster(lab.l, lab.a, lab.b, k)
        }
    }

    // 3. The three biggest clusters that are really different from each other.
    val total = counted.toFloat()
    val ranked = clusters.sortedByDescending { it.n * (1f + CHROMA_BIAS * it.chroma.coerceAtMost(0.3f)) }
    val chosen = ArrayList<Cluster>()
    for (cl in ranked) {
        if (chosen.size == 3) break
        // The dominant colour is always taken; the second and third must carry real weight.
        if (chosen.isNotEmpty() && cl.n / total < MIN_SHARE) continue
        if (chosen.none { distance(it, cl) < MIN_SEPARATION }) chosen += cl
    }
    if (chosen.isEmpty()) return null

    val picks = chosen.map { displayOf(it) }.toMutableList()
    val dominant = picks.first()

    // Missing second / third: tonal variants of the dominant (hue within +-8 degrees), never a new hue family.
    val lighterFirst = if (dominant.l >= 0.55f) -0.07f else 0.07f
    if (picks.size < 2) picks += variantOf(dominant, 8f, lighterFirst, 0.92f)
    if (picks.size < 3) picks += variantOf(dominant, -8f, -lighterFirst, 0.84f)

    // The fourth glow: the dominant's hue, lighter and a little less vivid.
    val tint = Lch((dominant.l + 0.10f).coerceAtMost(ALBUM_LIGHT_CAP + 0.05f), dominant.c * 0.8f, dominant.h)

    val shares = List(3) { idx -> if (idx < chosen.size) (chosen[idx].n / total).coerceIn(0f, 1f) else 0f }
    val shareSum = shares.sum().coerceAtLeast(1e-3f)
    val brightness = (0..2).sumOf { (shares[it] * picks[it].l).toDouble() }.toFloat() / shareSum
    val vividness = (0..2).sumOf { (shares[it] * picks[it].c).toDouble() }.toFloat() / shareSum
    // 360 T4: floor 0.80 (was 0.62), so a grey or dark cover still carries real colour.
    val intensity = (ALBUM_INTENSITY_FLOOR + (1f - ALBUM_INTENSITY_FLOOR) * (vividness / 0.12f).coerceIn(0f, 1f))
        .coerceIn(ALBUM_INTENSITY_FLOOR, 1f)

    return FluidPalette(
        colors = listOf(picks[0].toColor(), picks[1].toColor(), picks[2].toColor(), tint.toColor()),
        base = fromLch(ALBUM_BASE_LIGHTNESS, (dominant.c * 0.35f).coerceAtMost(0.04f), dominant.h), // 360 T4
        intensity = intensity,
        shares = shares,
        brightness = brightness.coerceIn(0f, 1f),
    )
}
