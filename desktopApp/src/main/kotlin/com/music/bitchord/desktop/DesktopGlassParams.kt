package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * STUDIO G1: every knob of the liquid-glass material. Plain numbers with defaults so a saved copy
 * from an older build still loads, and [validated] so a hand-edited or imported one can never feed
 * the shader something outside the range it was written for.
 *
 * Lengths are in dp (the shader works in px; [DesktopGlassMath.uniformsFor] converts).
 */
@Serializable // STUDIO G3 hook: stored inside DesktopPillConfig
internal data class DesktopGlassParams(
    /** How hard the rim bends the scene behind it, 0..1. */
    val refraction: Float = 0.95f,
    /** Width of the refracting band at the rim, dp. */
    val bevelDp: Float = 18f,
    /** Gentle magnification of the centre, 0..0.2. */
    val magnify: Float = 0.06f,
    /** Chromatic split of red and blue at the rim, 0..1. */
    val dispersion: Float = 0.45f,
    /** How much of the blurred copy replaces the sharp one, 0..1. */
    val frost: Float = 0.12f,
    /** Blur radius of the frosted copy, dp. */
    val blurDp: Float = 10f,
    /** Strength of the specular highlight, 0..1. */
    val specular: Float = 0.85f,
    /** Strength of the bright edge line, 0..1. */
    val rim: Float = 0.75f,
    /** Opacity of the dark tint that keeps text readable, 0..0.9. */
    val tintAlpha: Float = 0.2f,
    /** Where the light comes from, degrees: 0 = right, 90 = above, 135 = upper left. */
    val lightAngleDeg: Float = 135f,
    /** How far the album colour tints the glass, 0..1. */
    val albumInfluence: Float = 0.35f,
) {
    /** A copy with every field forced into its range; NaN and infinity fall back to the default. */
    fun validated(): DesktopGlassParams = DesktopGlassParams(
        refraction = refraction.clampedOr(REFRACTION, Default.refraction),
        bevelDp = bevelDp.clampedOr(BEVEL_DP, Default.bevelDp),
        magnify = magnify.clampedOr(MAGNIFY, Default.magnify),
        dispersion = dispersion.clampedOr(DISPERSION, Default.dispersion),
        frost = frost.clampedOr(FROST, Default.frost),
        blurDp = blurDp.clampedOr(BLUR_DP, Default.blurDp),
        specular = specular.clampedOr(SPECULAR, Default.specular),
        rim = rim.clampedOr(RIM, Default.rim),
        tintAlpha = tintAlpha.clampedOr(TINT_ALPHA, Default.tintAlpha),
        lightAngleDeg = lightAngleDeg.clampedOr(LIGHT_ANGLE_DEG, Default.lightAngleDeg),
        albumInfluence = albumInfluence.clampedOr(ALBUM_INFLUENCE, Default.albumInfluence),
    )

    companion object {
        val REFRACTION = 0f..1f
        val BEVEL_DP = 2f..40f
        val MAGNIFY = 0f..0.2f
        val DISPERSION = 0f..1f
        val FROST = 0f..1f
        val BLUR_DP = 0f..40f
        val SPECULAR = 0f..1f
        val RIM = 0f..1f
        val TINT_ALPHA = 0f..0.9f
        val LIGHT_ANGLE_DEG = 0f..360f
        val ALBUM_INFLUENCE = 0f..1f

        /** UI U9: clear glass after the owner's iOS liquid-glass references: strong rim refraction, little frost, bright rim. */
        val Default = DesktopGlassParams()

        /** UI U9: the same material, gentler, for small navigation capsules and floating bars. */
        val Nav = DesktopGlassParams(
            refraction = 0.6f, bevelDp = 10f, magnify = 0.03f, dispersion = 0.3f, frost = 0.2f,
            specular = 0.6f, rim = 0.6f, tintAlpha = 0.25f, albumInfluence = 0.2f,
        )
    }
}

private fun Float.clampedOr(range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    if (isNaN() || isInfinite()) fallback else coerceIn(range.start, range.endInclusive)

/**
 * The shader's maths written out in Kotlin, so the parts that decide the look can be tested without
 * a GPU, and the uniform block has one place that fixes its order and length.
 */
internal object DesktopGlassMath {

    /** Uniform names in the order [uniformsFor] writes them. Must match the SkSL declarations. */
    val UNIFORM_NAMES: Array<String> = arrayOf(
        "size", "radius", "bevel", "strength", "magnify", "dispersion", "frost",
        "tint", "accent", "specular", "rim", "lightDir", "time", "origin",
    )

    /** Floats per uniform, parallel to [UNIFORM_NAMES]. */
    val UNIFORM_WIDTHS: IntArray = intArrayOf(2, 1, 1, 1, 1, 1, 1, 4, 4, 1, 1, 2, 1, 2)

    /** Total floats in the block. */
    val UNIFORM_LENGTH: Int = UNIFORM_WIDTHS.sum()

    /** The largest displacement, as a share of the bevel width. */
    const val STRENGTH_OF_BEVEL = 0.55f

    /** Signed distance to a rounded rectangle centred on the origin: negative inside. */
    fun sdRoundRect(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Float {
        val qx = abs(px) - halfW + radius
        val qy = abs(py) - halfH + radius
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
        return min(max(qx, qy), 0f) + outside - radius
    }

    /**
     * 1 at the rim, 0 once [insideDistance] reaches [bevel], eased (smoothstep) between.
     * [insideDistance] is the distance in from the edge, so the rim itself is 0.
     */
    fun edgeProfile(insideDistance: Float, bevel: Float): Float {
        val t = (1f - insideDistance / max(bevel, 1e-4f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The outward unit normal of the rounded rectangle at a point, by the same central difference. */
    fun roundRectNormal(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Pair<Float, Float> {
        val gx = sdRoundRect(px + 1f, py, halfW, halfH, radius) - sdRoundRect(px - 1f, py, halfW, halfH, radius)
        val gy = sdRoundRect(px, py + 1f, halfW, halfH, radius) - sdRoundRect(px, py - 1f, halfW, halfH, radius)
        val len = max(sqrt(gx * gx + gy * gy), 1e-4f)
        return (gx / len) to (gy / len)
    }

    /** Unit vector towards the light, screen coordinates (y grows downwards). */
    fun lightDirX(angleDeg: Float): Float = cos(angleDeg * (PI.toFloat() / 180f))
    fun lightDirY(angleDeg: Float): Float = -sin(angleDeg * (PI.toFloat() / 180f))

    /**
     * Fills [out] with the uniform block for a glass of [widthPx] x [heightPx]. [params] is
     * validated here too, so no caller can pass the shader an out-of-range value. Reuse [out] to
     * avoid an allocation per frame.
     */
    fun uniformsFor(
        params: DesktopGlassParams,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        density: Float,
        tint: Color,
        accent: Color,
        timeSec: Float,
        out: FloatArray = FloatArray(UNIFORM_LENGTH),
        // The glass's position in the window, px. The shader uses it only if this Skia build hands it
        // window coordinates instead of coordinates that start at the glass's own corner.
        originX: Float = 0f,
        originY: Float = 0f,
    ): FloatArray {
        require(out.size >= UNIFORM_LENGTH) { "uniform buffer too small: ${out.size} < $UNIFORM_LENGTH" }
        val p = params.validated()
        val d = if (density.isNaN() || density <= 0f) 1f else density
        val half = min(widthPx, heightPx) * 0.5f
        val bevel = (p.bevelDp * d).coerceIn(1f, max(half, 1f))
        val radius = radiusPx.coerceIn(0f, half)
        out[0] = widthPx
        out[1] = heightPx
        out[2] = radius
        out[3] = bevel
        out[4] = p.refraction * bevel * STRENGTH_OF_BEVEL
        out[5] = p.magnify
        out[6] = p.dispersion
        out[7] = p.frost
        out[8] = tint.red
        out[9] = tint.green
        out[10] = tint.blue
        out[11] = p.tintAlpha
        out[12] = accent.red
        out[13] = accent.green
        out[14] = accent.blue
        out[15] = p.albumInfluence
        out[16] = p.specular
        out[17] = p.rim
        out[18] = lightDirX(p.lightAngleDeg)
        out[19] = lightDirY(p.lightAngleDeg)
        out[20] = if (timeSec.isNaN() || timeSec.isInfinite()) 0f else timeSec
        out[21] = if (originX.isNaN() || originX.isInfinite()) 0f else originX
        out[22] = if (originY.isNaN() || originY.isInfinite()) 0f else originY
        return out
    }
}
