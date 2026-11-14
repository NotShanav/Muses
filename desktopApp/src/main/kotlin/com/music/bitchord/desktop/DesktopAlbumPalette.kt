package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * UPGRADE M1: the album-colour engine, pure (no Compose runtime, no bitmaps) so it can be unit tested.
 *
 * A cover becomes a three-colour system: dominant, second, third (only if really present), plus a
 * lighter tint of the dominant. Missing colours are tonal variants of the dominant, never a new hue.
 */

private const val HUE_BINS = 24
private const val MAX_CHOSEN = 3
private const val MIN_HUE_GAP = 34f
/** A hue under this share of the chromatic weight is an accent, not a glow. */
private const val MIN_SHARE = 0.08f
/** Fewer chromatic pixels than this and the cover counts as grey (noise, compression tint). */
private const val NOISE_CHROMATIC_FRACTION = 0.015f
private const val GLOW_L_MIN = 0.46f
private const val GLOW_L_MAX = 0.60f
private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f

private fun binHue(bin: Int): Float = (bin + 0.5f) * 360f / HUE_BINS

private fun wrapHue(h: Float): Float = ((h % 360f) + 360f) % 360f

private fun Hsl.toColor(): Color = hslColor(h, s, l)

/** A tonal variant: the same hue family (small shift), different lightness and saturation. */
private fun tonalVariant(base: Hsl, hueShift: Float, lightnessShift: Float, satScale: Float): Hsl =
    Hsl(
        wrapHue(base.h + hueShift),
        (base.s * satScale).coerceIn(0.4f, 1f),
        (base.l + lightnessShift).coerceIn(GLOW_L_MIN, GLOW_L_MAX),
    )

/**
 * Hue-binned, saturation-weighted (as before): the colours that make a cover look the way it does.
 * Near-black, near-white and grey pixels are ignored. Returns null for an image under 2x2 or for
 * a pixel array that is too short; a grey or black-and-white cover gets neutral greys.
 */
internal fun extractAlbumPalette(pixels: IntArray, width: Int, height: Int): FluidPalette? {
    if (width < 2 || height < 2) return null
    val n = width * height
    if (n <= 0 || pixels.size < n) return null

    val weight = FloatArray(HUE_BINS)
    val satSum = FloatArray(HUE_BINS)
    val valSum = FloatArray(HUE_BINS)
    // Per bin, the weighted sum of the unit hue vector: gives the true hue of a bin, not its centre.
    val sinSum = FloatArray(HUE_BINS)
    val cosSum = FloatArray(HUE_BINS)
    var counted = 0
    var chromatic = 0
    var luminanceSum = 0f
    var saturationSum = 0f
    var hueSin = 0f
    var hueCos = 0f
    // An odd stride, so sampling does not line up on one column of the cover.
    val step = (max(1, n / 3600)) or 1
    var i = 0
    while (i < n) {
        val p = pixels[i]
        i += step
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val v = maxC
        val d = maxC - minC
        val s = if (maxC == 0f) 0f else d / maxC
        counted++
        luminanceSum += 0.2126f * r + 0.7152f * g + 0.0722f * b
        saturationSum += s
        if (v < 0.16f || s < 0.14f) continue
        chromatic++
        var h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        val bin = ((h / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        val w = (0.2f + s) * (0.35f + v)
        weight[bin] += w
        satSum[bin] += s * w
        valSum[bin] += v * w
        sinSum[bin] += sin(h * DEG_TO_RAD) * w
        cosSum[bin] += cos(h * DEG_TO_RAD) * w
        hueSin += sin(h * DEG_TO_RAD) * w
        hueCos += cos(h * DEG_TO_RAD) * w
    }
    if (counted == 0) return null

    val fraction = chromatic.toFloat() / counted
    val chromaTotal = weight.sum()
    if (chromatic == 0 || fraction < NOISE_CHROMATIC_FRACTION || chromaTotal <= 0f) {
        val avgHue = if (chromatic == 0) 0f else wrapHue(atan2(hueSin, hueCos) * RAD_TO_DEG)
        return neutralPalette(luminanceSum / counted, saturationSum / counted, avgHue)
    }

    // Bins are merged with their neighbours first: one hue straddling a bin edge is one colour.
    val smoothed = FloatArray(HUE_BINS) { weight[it] + 0.5f * (weight[(it + 1) % HUE_BINS] + weight[(it + HUE_BINS - 1) % HUE_BINS]) }
    val ranked = (0 until HUE_BINS).filter { weight[it] > 0f }.sortedByDescending { smoothed[it] }

    // Chosen bins are >= 3 bins apart (45 degrees), so the bin +-1 groups below never overlap.
    fun share(bin: Int): Float =
        (weight[bin] + weight[(bin + 1) % HUE_BINS] + weight[(bin + HUE_BINS - 1) % HUE_BINS]) / chromaTotal

    // The real hue of a chosen bin: circular mean over the same bin +-1 group that share() counts. A bin
    // centre would be up to 7.5 degrees off (pure red would come out orange-ish).
    fun hueOf(bin: Int): Float {
        val before = (bin + HUE_BINS - 1) % HUE_BINS
        val after = (bin + 1) % HUE_BINS
        val sn = sinSum[before] + sinSum[bin] + sinSum[after]
        val cs = cosSum[before] + cosSum[bin] + cosSum[after]
        return if (sn == 0f && cs == 0f) binHue(bin) else wrapHue(atan2(sn, cs) * RAD_TO_DEG)
    }

    val chosen = mutableListOf<Int>()
    for (bin in ranked) {
        if (chosen.size == MAX_CHOSEN) break
        // The dominant hue is always taken; the second and third must carry real weight.
        if (chosen.isNotEmpty() && share(bin) < MIN_SHARE) break
        val hue = binHue(bin)
        if (chosen.none { hueGap(hue, binHue(it)) < MIN_HUE_GAP }) chosen += bin
    }

    val picks = chosen.map { bin ->
        val w = weight[bin].coerceAtLeast(1e-4f)
        val s = (satSum[bin] / w * 1.15f).coerceIn(0.5f, 1f)
        val v = valSum[bin] / w
        Hsl(hueOf(bin), s, (0.42f + v * 0.2f).coerceIn(GLOW_L_MIN, GLOW_L_MAX))
    }.toMutableList()
    val dominant = picks.first()

    // Missing second / third: tonal variants of the dominant (hue within +-8 degrees), alternating lighter/darker.
    val lighterFirst = if (dominant.l >= 0.53f) -0.06f else 0.06f
    if (picks.size < 2) picks += tonalVariant(dominant, 8f, lighterFirst, 0.92f)
    if (picks.size < 3) picks += tonalVariant(dominant, -8f, -lighterFirst, 0.84f)

    // The fourth glow: the dominant's hue, lighter and a little less saturated.
    val tint = Hsl(dominant.h, (dominant.s * 0.88f).coerceIn(0f, 1f), (dominant.l + 0.10f).coerceAtMost(0.72f))

    val shares = List(3) { idx -> if (idx < chosen.size) share(chosen[idx]).coerceIn(0f, 1f) else 0f }
    val intensity = (0.4f + fraction * 1.6f).coerceIn(0.4f, 1f)
    return FluidPalette(
        colors = listOf(picks[0].toColor(), picks[1].toColor(), picks[2].toColor(), tint.toColor()),
        base = hslColor(dominant.h, dominant.s.coerceAtMost(0.6f), 0.07f),
        intensity = intensity,
        shares = shares,
    )
}

/**
 * A grey or black-and-white sleeve: neutral greys. Lightness follows the cover's average luminance
 * (kept inside the glow clamp), saturation is at most 0.08 and may carry the average hue faintly.
 */
private fun neutralPalette(avgLuminance: Float, avgSaturation: Float, hue: Float): FluidPalette {
    val s = avgSaturation.coerceIn(0f, 0.08f)
    val l0 = GLOW_L_MIN + (GLOW_L_MAX - GLOW_L_MIN) * avgLuminance.coerceIn(0f, 1f)
    val colors = listOf(
        hslColor(hue, s, l0),
        hslColor(hue, s, (l0 + 0.04f).coerceAtMost(GLOW_L_MAX)),
        hslColor(hue, s, (l0 - 0.04f).coerceAtLeast(GLOW_L_MIN)),
        hslColor(hue, s, (l0 + 0.10f).coerceAtMost(0.72f)),
    )
    return FluidPalette(
        colors = colors,
        base = hslColor(hue, s, 0.06f),
        intensity = 0.45f,
        shares = listOf(1f, 0f, 0f),
    )
}
