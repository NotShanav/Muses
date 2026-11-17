package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The artwork-disintegration transition: pure maths.
 *
 * No Compose, no Skia, no AWT, so every function here can be tested on its own
 * (`DesktopArtTransitionMathTest`). Times are milliseconds on the transition's own clock, positions
 * are pixels. Nothing here allocates: functions that return two numbers write them into an array the
 * caller owns.
 */
internal object ArtTransitionPhysics {

    /** Particles per side: the artwork is sampled into a GRID x GRID mosaic. */
    const val GRID = 48

    /** Particles in all. */
    const val COUNT = GRID * GRID

    // ---- Timeline (ms) -------------------------------------------------------------------------

    /** Lift: the source rect scales up a hair. */
    const val LIFT_END_MS = 80f

    /** Peak lift scale above 1. */
    const val LIFT_SCALE = 0.05f

    /** The diagonal sweep starts here and releases the last particle SWEEP_SPAN_MS later. */
    const val SWEEP_START_MS = 80f
    const val SWEEP_SPAN_MS = 360f

    /** Pill particles leave on their bezier at FLIGHT_BASE + delay * FLIGHT_STAGGER (+ a little noise). */
    const val FLIGHT_BASE_MS = 300f
    const val FLIGHT_STAGGER_MS = 220f
    const val FLIGHT_NOISE_MS = 40f

    /** A flight lasts FLIGHT_DUR_MIN + noise * FLIGHT_DUR_VAR. */
    const val FLIGHT_DUR_MIN_MS = 360f
    const val FLIGHT_DUR_VAR_MS = 140f

    /** Background particles live this long (plus noise) after release. */
    const val BG_LIFE_MS = 520f
    const val BG_LIFE_VAR_MS = 120f

    /** The reveal (the real thumbnail fading in under the particles) starts no earlier than this. */
    const val REVEAL_START_MS = 1100f
    const val REVEAL_MS = 200f

    /** Share of particles that become backdrop ("background") particles. */
    const val BG_FRACTION = 0.35f

    /** Backdrop injections per transition, at most. */
    const val SINK_CHUNKS = 12

    // ---- Small helpers -------------------------------------------------------------------------

    fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Hermite smoothstep between two edges. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    fun easeInCubic(t: Float): Float {
        val c = clamp01(t)
        return c * c * c
    }

    fun easeOutCubic(t: Float): Float {
        val c = 1f - clamp01(t)
        return 1f - c * c * c
    }

    fun easeInOutCubic(t: Float): Float {
        val c = clamp01(t)
        return if (c < 0.5f) {
            4f * c * c * c
        } else {
            val d = -2f * c + 2f
            1f - d * d * d / 2f
        }
    }

    /** 0 at both ends of 0..1, 1 in the middle: the arc a flying particle swells along. */
    fun bump(t: Float): Float = sin(PI.toFloat() * clamp01(t))

    /** Lift scale at [tMs]: 1 rising to 1 + LIFT_SCALE over LIFT_END_MS, then held. */
    fun liftScale(tMs: Float): Float = 1f + LIFT_SCALE * easeOutCubic(tMs / LIFT_END_MS)

    // ---- Grid sampling index math -------------------------------------------------------------

    /** Column of particle [index] in a grid [n] wide. */
    fun cellCol(index: Int, n: Int): Int = index % n

    /** Row of particle [index] in a grid [n] wide. */
    fun cellRow(index: Int, n: Int): Int = index / n

    /** Index of the particle at ([col], [row]). */
    fun cellIndex(col: Int, row: Int, n: Int): Int = row * n + col

    /**
     * The centred square of a [w] x [h] image that gets sampled (what a Crop-scaled thumbnail shows).
     * Writes x, y, side into [out] (three ints).
     */
    fun squareCrop(w: Int, h: Int, out: IntArray) {
        val side = min(w, h)
        out[0] = (w - side) / 2
        out[1] = (h - side) / 2
        out[2] = side
    }

    // ---- Noise ---------------------------------------------------------------------------------

    /** A stable hash of ([a], [b], [seed]) into 0 (inclusive) .. 1 (exclusive). */
    fun hash01(a: Int, b: Int, seed: Int): Float {
        var h = a * 374761393 + b * 668265263 + seed * 362437
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        h *= 0x2C1B3C6D
        h = h xor (h ushr 15)
        return (h and 0xFFFFFF) / 16777216f
    }

    /**
     * Sweep delay 0..1 for the particle at ([col], [row]): mostly the diagonal (top-left first,
     * bottom-right last), roughened with hash noise so the front is not a straight line.
     */
    fun delayField(col: Int, row: Int, n: Int, seed: Int): Float {
        val span = max(1, 2 * (n - 1)).toFloat()
        val diagonal = (col + row) / span
        val noise = hash01(col, row, seed)
        return clamp01(diagonal * 0.72f + noise * 0.28f)
    }

    /** When a particle with sweep [delay] lets go of the artwork. */
    fun releaseMs(delay: Float): Float = SWEEP_START_MS + clamp01(delay) * SWEEP_SPAN_MS

    /** When a pill particle starts its bezier flight. [noise] is a 0..1 hash. */
    fun flightStartMs(delay: Float, noise: Float): Float =
        FLIGHT_BASE_MS + clamp01(delay) * FLIGHT_STAGGER_MS + clamp01(noise) * FLIGHT_NOISE_MS

    /** How long that flight lasts. */
    fun flightDurMs(noise: Float): Float = FLIGHT_DUR_MIN_MS + clamp01(noise) * FLIGHT_DUR_VAR_MS

    /** Which of the [SINK_CHUNKS] backdrop injections a particle with sweep [delay] belongs to. */
    fun sinkChunk(delay: Float): Int = min(SINK_CHUNKS - 1, (clamp01(delay) * SINK_CHUNKS).toInt())

    /** When chunk [k] is injected into the backdrop. */
    fun sinkChunkMs(k: Int): Float = SWEEP_START_MS + (k + 0.6f) / SINK_CHUNKS * SWEEP_SPAN_MS

    /**
     * Curl noise at ([x], [y]) and time [t] (seconds-ish): the rotated gradient of a smooth potential,
     * so the field is divergence free and particles swirl instead of bunching. Writes the vector into
     * out[0], out[1]; each component stays within about +-1.
     */
    fun curlNoise(x: Float, y: Float, t: Float, out: FloatArray) {
        val a = 1.7f
        val b = 2.1f
        val c = 1.3f
        val p1 = a * x + 0.9f * t
        val p2 = b * y - 0.7f * t
        val p3 = c * (x + y) + 1.3f * t
        // psi = sin(p1) * sin(p2) + 0.5 * sin(p3)
        val dPsiDx = a * cos(p1) * sin(p2) + 0.5f * c * cos(p3)
        val dPsiDy = b * sin(p1) * cos(p2) + 0.5f * c * cos(p3)
        out[0] = dPsiDy / 2.8f
        out[1] = -dPsiDx / 2.8f
    }

    // ---- Bezier --------------------------------------------------------------------------------

    /** One axis of a cubic bezier with control values [p0]..[p3] at [t] in 0..1. */
    fun bezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }

    // ---- Spring --------------------------------------------------------------------------------

    /**
     * One step of an underdamped spring (mass 1, [stiffness] as in Compose's spring, damping ratio
     * [dampingRatio] below 1) toward [target], in closed form so any [dtSec] is stable. Takes the
     * current [pos] and [vel], writes the new ones into out[0] and out[1].
     */
    fun springStep(
        pos: Float,
        vel: Float,
        target: Float,
        stiffness: Float,
        dampingRatio: Float,
        dtSec: Float,
        out: FloatArray,
    ) {
        if (dtSec <= 0f) {
            out[0] = pos
            out[1] = vel
            return
        }
        val zeta = min(0.999f, max(0.05f, dampingRatio))
        val w0 = sqrt(max(1f, stiffness))
        val wd = w0 * sqrt(1f - zeta * zeta)
        val x0 = pos - target
        val decay = exp(-zeta * w0 * dtSec)
        val cs = cos(wd * dtSec)
        val sn = sin(wd * dtSec)
        val b = (vel + zeta * w0 * x0) / wd
        val x = decay * (x0 * cs + b * sn)
        val v = decay * (-zeta * w0 * (x0 * cs + b * sn) + (-x0 * wd * sn + b * wd * cs))
        out[0] = target + x
        out[1] = v
    }
}
