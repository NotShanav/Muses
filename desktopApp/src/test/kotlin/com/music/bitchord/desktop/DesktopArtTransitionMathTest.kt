package com.music.bitchord.desktop

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The artwork transition's pure maths: index math, delay field, bezier, curl noise, spring, easing. */
class DesktopArtTransitionMathTest {

    private val p = ArtTransitionPhysics

    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps

    @Test
    fun cellIndexMathRoundTrips() {
        val n = p.GRID
        for (i in intArrayOf(0, 1, 47, 48, 49, 1000, p.COUNT - 1)) {
            assertEquals(i, p.cellIndex(p.cellCol(i, n), p.cellRow(i, n), n))
        }
        assertEquals(0, p.cellCol(48, 48))
        assertEquals(1, p.cellRow(48, 48))
        assertEquals(47, p.cellCol(p.COUNT - 1, n))
        assertEquals(47, p.cellRow(p.COUNT - 1, n))
    }

    @Test
    fun squareCropIsCentred() {
        val out = IntArray(3)
        p.squareCrop(300, 200, out)
        assertEquals(50, out[0]); assertEquals(0, out[1]); assertEquals(200, out[2])
        p.squareCrop(200, 300, out)
        assertEquals(0, out[0]); assertEquals(50, out[1]); assertEquals(200, out[2])
        p.squareCrop(256, 256, out)
        assertEquals(0, out[0]); assertEquals(0, out[1]); assertEquals(256, out[2])
    }

    @Test
    fun hashIsStableAndInRange() {
        for (a in 0 until 60) for (b in 0 until 60) {
            val h = p.hash01(a, b, 7)
            assertTrue(h >= 0f && h < 1f)
            assertEquals(h, p.hash01(a, b, 7))
        }
        assertTrue(p.hash01(3, 4, 1) != p.hash01(3, 4, 2))
    }

    @Test
    fun delayFieldSweepsDiagonallyWithinRange() {
        val n = p.GRID
        var minD = 1f
        var maxD = 0f
        for (r in 0 until n) for (c in 0 until n) {
            val d = p.delayField(c, r, n, 11)
            assertTrue(d >= 0f && d <= 1f)
            minD = minOf(minD, d); maxD = max(maxD, d)
        }
        assertTrue(minD < 0.1f)
        assertTrue(maxD > 0.9f)
        // Averaged over a corner patch, top-left goes before bottom-right.
        var tl = 0f
        var br = 0f
        for (r in 0 until 6) for (c in 0 until 6) {
            tl += p.delayField(c, r, n, 11)
            br += p.delayField(n - 1 - c, n - 1 - r, n, 11)
        }
        assertTrue(tl < br)
    }

    @Test
    fun timelineWindowsHold() {
        for (i in 0 until p.COUNT step 7) {
            val n = p.GRID
            val d = p.delayField(p.cellCol(i, n), p.cellRow(i, n), n, 5)
            val no = p.hash01(i, 1, 5)
            val rel = p.releaseMs(d)
            val fs = p.flightStartMs(d, no)
            val end = fs + p.flightDurMs(no)
            assertTrue(rel >= 80f && rel <= 440f)
            assertTrue(fs >= 300f && fs <= 560f)
            assertTrue(end <= 1100f)
        }
    }

    @Test
    fun sinkChunksStayWithinTwelveAndSweep() {
        assertEquals(0, p.sinkChunk(0f))
        assertEquals(11, p.sinkChunk(1f))
        assertEquals(11, p.sinkChunk(5f))
        var prev = -1f
        for (k in 0 until p.SINK_CHUNKS) {
            val t = p.sinkChunkMs(k)
            assertTrue(t > prev)
            assertTrue(t >= 80f && t <= 440f)
            prev = t
        }
    }

    @Test
    fun bezierHitsEndsAndIsLinearForLinearControls() {
        assertTrue(near(p.bezier(2f, 5f, 9f, 10f, 0f), 2f))
        assertTrue(near(p.bezier(2f, 5f, 9f, 10f, 1f), 10f))
        for (i in 0..10) {
            val t = i / 10f
            assertTrue(near(p.bezier(0f, 1f / 3f, 2f / 3f, 1f, t), t))
        }
    }

    @Test
    fun curlNoiseIsBoundedAndDivergenceFree() {
        val o = FloatArray(2)
        val a = FloatArray(2)
        val b = FloatArray(2)
        val h = 0.01f
        for (i in 0 until 20) {
            val x = i * 0.37f
            val y = i * 0.23f + 1f
            val t = i * 0.11f
            p.curlNoise(x, y, t, o)
            assertTrue(abs(o[0]) <= 1.01f && abs(o[1]) <= 1.01f)
            p.curlNoise(x + h, y, t, a)
            p.curlNoise(x - h, y, t, b)
            val dOxDx = (a[0] - b[0]) / (2 * h)
            p.curlNoise(x, y + h, t, a)
            p.curlNoise(x, y - h, t, b)
            val dOyDy = (a[1] - b[1]) / (2 * h)
            assertTrue(abs(dOxDx + dOyDy) < 0.05f)
        }
    }

    @Test
    fun springOvershootsThenConverges() {
        val k = MusesMotion.SETTLE_STIFFNESS
        val out = FloatArray(2)
        var pos = 0f
        var vel = 0f
        var peak = 0f
        for (i in 0 until 240) {
            p.springStep(pos, vel, 1f, k, 0.5f, 1f / 120f, out)
            pos = out[0]; vel = out[1]
            peak = max(peak, pos)
        }
        assertTrue(peak > 1.05f)
        assertTrue(near(pos, 1f, 0.01f))
        assertTrue(abs(vel) < 0.05f)
    }

    @Test
    fun springStepIsTimestepIndependent() {
        val k = MusesMotion.SETTLE_STIFFNESS
        val z = MusesMotion.SETTLE_DAMPING
        val one = FloatArray(2)
        p.springStep(10f, 3f, 0f, k, z, 0.1f, one)
        val out = FloatArray(2)
        var pos = 10f
        var vel = 3f
        for (i in 0 until 10) {
            p.springStep(pos, vel, 0f, k, z, 0.01f, out)
            pos = out[0]; vel = out[1]
        }
        assertTrue(near(pos, one[0], 0.01f))
        assertTrue(near(vel, one[1], 0.05f))
        p.springStep(4f, 2f, 0f, k, z, 0f, out)
        assertEquals(4f, out[0]); assertEquals(2f, out[1])
    }

    @Test
    fun easingEndpointsAndMonotonic() {
        assertTrue(near(p.easeInOutCubic(0f), 0f) && near(p.easeInOutCubic(1f), 1f))
        assertTrue(near(p.easeOutCubic(0f), 0f) && near(p.easeOutCubic(1f), 1f))
        assertTrue(near(p.easeInCubic(0f), 0f) && near(p.easeInCubic(1f), 1f))
        assertEquals(0f, p.easeOutCubic(-3f)); assertEquals(1f, p.easeOutCubic(3f))
        var prev = -1f
        for (i in 0..20) {
            val v = p.easeInOutCubic(i / 20f)
            assertTrue(v >= prev)
            prev = v
        }
        assertTrue(near(p.bump(0f), 0f) && near(p.bump(1f), 0f) && near(p.bump(0.5f), 1f))
        assertEquals(0f, p.smoothstep(2f, 4f, 1f)); assertEquals(1f, p.smoothstep(2f, 4f, 9f))
    }

    @Test
    fun coverGeometryMatchesCropScaling() {
        assertEquals(300f, p.coverSide(200f, 300f))
        assertEquals(300f, p.coverSide(300f, 200f))
        assertTrue(near(p.coverOffset(0.5f / 48f, 300f), -150f + 150f / 48f, 1e-2f))
        assertTrue(near(p.coverOffset(0.5f, 300f), 0f))
        // Portrait 200x300 box: a cell 100 px left of centre is on screen, 140 px left is cropped away.
        assertTrue(!p.outsideCover(-100f, 100f, 6.25f))
        assertTrue(p.outsideCover(-140f, 100f, 6.25f))
        // A square box shows every cell.
        for (c in 0 until p.GRID) {
            val off = p.coverOffset((c + 0.5f) / p.GRID, 200f)
            assertTrue(!p.outsideCover(off, 100f, 200f / p.GRID))
        }
    }

    @Test
    fun liftIsSmallAndSettlesAtEightyMs() {
        assertTrue(near(p.liftScale(0f), 1f))
        assertTrue(near(p.liftScale(80f), 1f + p.LIFT_SCALE))
        assertTrue(near(p.liftScale(900f), 1f + p.LIFT_SCALE))
    }

    // ---- UI U7: whole-area spawn, particle budget, landing on the pill art -------------------------

    @Test
    fun everyParticleStartsInsideTheSourceRectangle() {
        val n = p.GRID
        // A big wide card (cropped square) and a small thumbnail.
        for ((w, h) in listOf(400f to 180f, 48f to 48f, 120f to 300f)) {
            val cx = 500f
            val cy = 300f
            val side = p.coverSide(w, h)
            for (i in 0 until p.COUNT) {
                val fx = (p.cellCol(i, n) + 0.5f) / n
                val fy = (p.cellRow(i, n) + 0.5f) / n
                val x = p.spawnCoord(cx, fx, side, w / 2f)
                val y = p.spawnCoord(cy, fy, side, h / 2f)
                assertTrue(x >= cx - w / 2f - 1e-3f && x <= cx + w / 2f + 1e-3f, "x $x outside for ${w}x$h")
                assertTrue(y >= cy - h / 2f - 1e-3f && y <= cy + h / 2f + 1e-3f, "y $y outside for ${w}x$h")
            }
        }
    }

    @Test
    fun atTheEndEveryParticleIsAtItsCellOfThePillArtwork() {
        val n = p.GRID
        val tw = 56f
        val th = 56f
        val cx = 300f
        val cy = 700f
        val side = p.coverSide(tw, th)
        for (i in 0 until p.COUNT) {
            val fx = (p.cellCol(i, n) + 0.5f) / n
            val fy = (p.cellRow(i, n) + 0.5f) / n
            val hx = cx + p.coverOffset(fx, side)
            val hy = cy + p.coverOffset(fy, side)
            assertTrue(hx > cx - tw / 2f - 1f && hx < cx + tw / 2f + 1f, "home x $hx")
            assertTrue(hy > cy - th / 2f - 1f && hy < cy + th / 2f + 1f, "home y $hy")
        }
    }

    @Test
    fun particleBudgetScalesWithAreaAndIsCapped() {
        assertTrue(near(p.MIN_KEEP_FRACTION, p.keepFraction(0f)))
        assertTrue(near(1f, p.keepFraction(p.FULL_AREA_PX)))
        assertTrue(near(1f, p.keepFraction(p.FULL_AREA_PX * 10f)))
        assertTrue(p.keepFraction(2_304f) < p.keepFraction(50_000f))
        var kept = 0
        val keep = p.keepFraction(2_304f)
        for (i in 0 until p.COUNT) if (p.cellKept(p.cellCol(i, p.GRID), p.cellRow(i, p.GRID), 7, keep)) kept++
        assertTrue(kept <= p.COUNT)
        assertTrue(kept > (p.COUNT * 0.3f).toInt() && kept < (p.COUNT * 0.5f).toInt(), "kept $kept")
    }
}
