package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import com.music.bitchord.data.model.artworkAt
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One artwork transition to run. Everything is in window pixels.
 *
 * [sourceBounds] is where the artwork is now (a list row's thumbnail), [pointer] is the click, used
 * only when there are no bounds. With neither, the particles start from just above the pill.
 */
internal data class ArtTransitionRequest(
    val videoId: String,
    val artworkUrl: String?,
    val sourceBounds: Rect?,
    val pointer: Offset?,
)

/** Where the "background" particles pour their colour: the app's backdrop, wired by the host. */
internal fun interface ArtBackdropSink {
    /** [position] is in window pixels, [strength] 0..1. */
    fun inject(color: Color, position: Offset, strength: Float)
}

/**
 * The artwork-disintegration transition: artwork -> 48x48 particles -> the pill's thumbnail.
 *
 * The animation never delays or gates playback. It reads [audioReady] only to decide whether the
 * particles wait in a soft orbit above the pill; nothing else here is touched by the audio side, and
 * every failure path ends with [reveal] = 1 so the real thumbnail is simply shown.
 *
 * Frames are driven by [DesktopArtTransitionOverlay]; without it on screen a watchdog ends the
 * transition after a short stall, so the pill is never left hidden.
 */
internal object DesktopArtTransition {

    private const val ARTWORK_PX = 256
    private const val GRID_CACHE = 16
    private const val LOAD_TIMEOUT_MS = 700L
    private const val STALL_MS = 700L
    private const val HARD_CAP_MS = 12_000L
    private const val MAX_DT_MS = 50f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val grids = DesktopCountLru<IntArray>(GRID_CACHE)

    private val _active = MutableStateFlow(false)
    private val _reveal = MutableStateFlow(1f)
    private val _sourceFade = MutableStateFlow(0f)

    /** True from [start] until the particles are gone (a fast fade-out of a replaced run counts). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** 0..1 while the particles reconstruct in the pill; 1 whenever nothing is running. */
    val reveal: StateFlow<Float> = _reveal.asStateFlow()

    /**
     * Extra, optional: 0..1, how much of the source artwork the particles have taken over (rises
     * over the first ~100 ms). The host may draw the source thumbnail at `1 - sourceFade`.
     */
    val sourceFade: StateFlow<Float> = _sourceFade.asStateFlow()

    /** Live bounds of the pill thumbnail, window px. Read every frame; a zero rect ends the run. */
    @Volatile
    var targetBounds: () -> Rect = { Rect.Zero }

    @Volatile
    var backdropSink: ArtBackdropSink? = null

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var generation = 0

    @Volatile
    private var readyId: String? = null

    @Volatile
    private var pending: ArtParticleSim? = null

    @Volatile
    private var loading = false

    @Volatile
    private var lastTickWallMs = 0L

    @Volatile
    internal var current: ArtParticleSim? = null

    @Volatile
    internal var fading: ArtParticleSim? = null

    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var lastFrameNanos = 0L

    /**
     * Starts a transition. A run already going is cancelled (its particles fade out fast) and this
     * one replaces it. Off, or with "Reduce animation" on, this does nothing but make sure [reveal] is 1.
     */
    fun start(request: ArtTransitionRequest) {
        if (!enabled) {
            println("ArtTransition skipped: disabled")
            cancel()
            return
        }
        if (DesktopAppearanceSettings.reduceAnimation.value) {
            println("ArtTransition skipped: reduceAnimation")
            cancel()
            return
        }
        val url = request.artworkUrl.artworkAt(ARTWORK_PX)
        synchronized(lock) {
            retire()
            if (url.isNullOrBlank()) {
                println("ArtTransition skipped: blank url")
                finishLocked()
                return
            }
            val gen = generation
            val source = resolveSource(request)
            loading = true
            _reveal.value = 0f
            _sourceFade.value = 0f
            _active.value = true
            val t0 = System.currentTimeMillis()
            loadJob = scope.launch {
                val grid = withTimeoutOrNull(LOAD_TIMEOUT_MS) { gridFor(url) }
                synchronized(lock) {
                    if (gen != generation) return@launch
                    if (grid == null) {
                        finishLocked()
                        return@launch
                    }
                    loading = false
                    pending = ArtParticleSim(
                        videoId = request.videoId,
                        grid = grid,
                        srcRect = source,
                        seed = gen * 31 + request.videoId.hashCode(),
                        startReady = readyId == request.videoId,
                    )
                }
            }
            watchJob = scope.launch {
                while (isActive) {
                    delay(100)
                    if (gen != generation) return@launch
                    val now = System.currentTimeMillis()
                    val ref = max(lastTickWallMs, t0)
                    if (now - ref > STALL_MS || now - t0 > HARD_CAP_MS) {
                        synchronized(lock) { if (gen == generation) finishLocked() }
                        return@launch
                    }
                }
            }
        }
    }

    /** Audio for [videoId] is playing (or about to). Only decides whether the particles wait; never blocks. */
    fun audioReady(videoId: String) {
        readyId = videoId
    }

    /** Stops now: a running run's particles fade out fast and [reveal] is 1. */
    fun cancel() {
        synchronized(lock) {
            retire()
            finishLocked()
        }
    }

    /** Called by the overlay when its frame loop (re)starts, so the first dt is not a long gap. */
    internal fun resetClock() {
        lastFrameNanos = 0L
    }

    /** One frame, called by the overlay from `withFrameNanos`. */
    internal fun tick(frameNanos: Long) {
        lastTickWallMs = System.currentTimeMillis()
        var dtMs = if (lastFrameNanos == 0L) 16.7f else (frameNanos - lastFrameNanos) / 1_000_000f
        lastFrameNanos = frameNanos
        if (dtMs > MAX_DT_MS) dtMs = MAX_DT_MS
        if (dtMs < 0f) dtMs = 0f
        val tb = try {
            targetBounds()
        } catch (t: Throwable) {
            Rect.Zero
        }
        val sink = backdropSink
        synchronized(lock) {
            val p = pending
            if (p != null) {
                current = p
                pending = null
            }
        }
        val cur = current
        if (cur != null) {
            cur.step(dtMs, readyId == cur.videoId, tb, sink)
            _reveal.value = cur.reveal
            _sourceFade.value = cur.sourceFade
            if (cur.finished) {
                synchronized(lock) { if (current === cur) finishLocked() }
            }
        }
        val old = fading
        if (old != null) {
            old.step(dtMs, true, tb, null)
            if (old.finished) {
                synchronized(lock) {
                    if (fading === old) fading = null
                    if (current == null && pending == null && !loading) _active.value = false
                }
            }
        }
    }

    // ---- internals (call under lock) -----------------------------------------------------------

    /** Invalidates in-flight work and turns a running run into a fast fade-out. */
    private fun retire() {
        generation++
        loadJob?.cancel()
        watchJob?.cancel()
        loadJob = null
        watchJob = null
        pending = null
        loading = false
        val cur = current
        if (cur != null) {
            cur.beginFade()
            fading = cur
            current = null
        }
    }

    /** Nothing running: the real thumbnail shows. */
    private fun finishLocked() {
        generation++
        current = null
        pending = null
        loading = false
        _reveal.value = 1f
        _sourceFade.value = 0f
        _active.value = fading != null
    }

    /** Artwork views that reported themselves with `Modifier.artTransitionSource`, by layout node. */
    private val sources = HashMap<LayoutCoordinates, String>()
    private var lastPruneSize = 0

    /** Called from `Modifier.artTransitionSource`: remembers where the artwork for [videoId] is drawn. */
    internal fun reportSource(videoId: String, coords: LayoutCoordinates) {
        synchronized(sources) {
            // Amortized prune: only when the map has doubled since the last prune, so hundreds of
            // attached thumbnails do not cost O(n) on every layout.
            if (sources.size > maxOf(96, lastPruneSize * 2)) {
                sources.keys.removeAll { !it.isAttached }
                lastPruneSize = sources.size
            }
            sources[coords] = videoId
        }
    }

    /** Where a reported artwork view for [videoId] is right now (full, unclipped box, window px). */
    private fun lookupSource(videoId: String, artworkUrl: String?, pointer: Offset?): Rect? {
        val urlKey = if (artworkUrl.isNullOrBlank()) null else "url:$artworkUrl"
        var best: Rect? = null
        synchronized(sources) {
            val it = sources.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!e.key.isAttached) {
                    it.remove()
                    continue
                }
                if (e.value != videoId && (urlKey == null || e.value != urlKey)) continue
                val pos = e.key.positionInWindow()
                val r = Rect(pos, Size(e.key.size.width.toFloat(), e.key.size.height.toFloat()))
                if (r.width < 2f || r.height < 2f) continue
                if (pointer != null && r.contains(pointer)) return r
                if (best == null) best = r
            }
        }
        return best
    }

    private fun resolveSource(request: ArtTransitionRequest): Rect {
        val p = request.pointer
        // The live, unclipped box of the view that was clicked beats any bounds passed in.
        val hit = lookupSource(request.videoId, request.artworkUrl, p)
        if (hit != null && (p == null || hit.contains(p))) return hit
        val b = request.sourceBounds
        if (b != null && b.width >= 2f && b.height >= 2f) return b
        if (hit != null) return hit
        if (p != null) return Rect(p.x - 40f, p.y - 40f, p.x + 40f, p.y + 40f)
        val tb = try {
            targetBounds()
        } catch (t: Throwable) {
            Rect.Zero
        }
        val c = tb.center
        return Rect(c.x - 40f, c.y - 220f, c.x + 40f, c.y - 140f)
    }

    private suspend fun gridFor(url: String): IntArray? {
        grids.get(url)?.let { return it }
        // Own job, so a timed-out wait does not stop the download: the next play finds it cached.
        return scope.async(Dispatchers.IO) { computeGrid(url) }.await()
    }

    private suspend fun computeGrid(url: String): IntArray? {
        val bitmap = DesktopArtworkCache.load(url) ?: return null
        val grid = try {
            ArtGridSampler.sample(bitmap)
        } catch (t: Throwable) {
            null
        }
        if (grid != null) grids.put(url, grid)
        return grid
    }
}

/** Reads the real artwork pixels into a GRID x GRID mosaic of opaque ARGB ints. */
internal object ArtGridSampler {

    fun sample(bitmap: ImageBitmap): IntArray? {
        val src = bitmap.toAwtImage()
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return null
        val crop = IntArray(3)
        ArtTransitionPhysics.squareCrop(w, h, crop)
        val square = src.getSubimage(crop[0], crop[1], crop[2], crop[2])
        val grid = ArtTransitionPhysics.GRID
        // Two bilinear halvings read closer to a box average than one big jump.
        val mid = if (crop[2] > grid * 4) scaled(square, grid * 2) else square
        val fin = scaled(mid, grid)
        val out = IntArray(grid * grid)
        fin.getRGB(0, 0, grid, grid, out, 0, grid)
        for (i in out.indices) out[i] = out[i] or 0xFF000000.toInt()
        return out
    }

    private fun scaled(img: BufferedImage, size: Int): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return out
    }
}

/**
 * One run's particles. Plain FloatArrays that [step] fills and the overlay reads; both happen on the
 * UI thread, so there is no locking. Built on a background thread (the constructor does the per-particle setup).
 */
internal class ArtParticleSim(
    val videoId: String,
    grid: IntArray,
    srcRect: Rect,
    seed: Int,
    startReady: Boolean,
) {
    private val ph = ArtTransitionPhysics
    private val n = ArtTransitionPhysics.GRID

    val count = ArtTransitionPhysics.COUNT

    // What the overlay draws (centres and sizes in window px; alpha already includes every fade).
    val px = FloatArray(count)
    val py = FloatArray(count)
    val dw = FloatArray(count)
    val dh = FloatArray(count)
    val alpha = FloatArray(count)
    val rad = FloatArray(count)
    val glow = FloatArray(count)
    val rgb = IntArray(count)

    private val isBg = BooleanArray(count)
    private val state = ByteArray(count)
    private val sweepDelay = FloatArray(count)
    private val noise = FloatArray(count)
    private val noise2 = FloatArray(count)
    private val relMs = FloatArray(count)
    private val fsMs = FloatArray(count)
    private val fdMs = FloatArray(count)
    private val off1 = FloatArray(count)
    private val off2 = FloatArray(count)
    private val ang = FloatArray(count)
    private val hx = FloatArray(count)
    private val hy = FloatArray(count)
    private val sx = FloatArray(count)
    private val sy = FloatArray(count)
    private val sx0 = FloatArray(count)
    private val sy0 = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val dirX = FloatArray(count)
    private val dirY = FloatArray(count)
    private val glowAmt = FloatArray(count)
    private val srcHidden = BooleanArray(count)

    private val chunkR = FloatArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkG = FloatArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkB = FloatArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkX = FloatArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkY = FloatArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkN = IntArray(ArtTransitionPhysics.SINK_CHUNKS)
    private val chunkFired = BooleanArray(ArtTransitionPhysics.SINK_CHUNKS)
    private var bgCount = 0

    private val srcCx = srcRect.left + srcRect.width * 0.5f
    private val srcCy = srcRect.top + srcRect.height * 0.5f
    // The artwork is a square drawn "cover" into the source box, so it is as big as the longer side.
    private val srcSide = ArtTransitionPhysics.coverSide(srcRect.width, srcRect.height)
    private val srcW = srcSide
    private val srcHalfW = srcRect.width * 0.5f
    private val srcHalfH = srcRect.height * 0.5f
    private val cellSW = srcSide / ArtTransitionPhysics.GRID
    private val cellSH = srcSide / ArtTransitionPhysics.GRID

    private val tmp = FloatArray(2)
    private val tmp2 = FloatArray(2)

    /** The sim's own clock, ms. */
    var tMs = 0f
        private set

    var reveal = 0f
        private set

    var sourceFade = 0f
        private set

    var finished = false
        private set

    private var dirsReady = false
    private var readyAtMs = -1f
    private var holdMix = if (startReady) 0f else 1f
    private var lastSettleBeginMs = 0f
    private var pillPending = Int.MAX_VALUE
    private var fadeAgeMs = -1f

    // Per-frame values shared with the per-particle helpers.
    private var fL = 0f
    private var fT = 0f
    private var fCx = 0f
    private var fCy = 0f
    private var fS = 0f
    private var fTw = 0f
    private var fTh = 0f
    private var fCw = 0f
    private var fCh = 0f
    private var fOcx = 0f
    private var fOcy = 0f
    private var fOx = 0f
    private var fOy = 0f
    private var fLift = 1f
    private var fHold = 0f
    private var fReady = false
    private var fDtMs = 0f
    private var fDtSec = 0f
    private var fTSec = 0f
    private var fRevealFade = 0f

    init {
        for (p in 0 until count) {
            val col = ph.cellCol(p, n)
            val row = ph.cellRow(p, n)
            val argb = grid[p]
            rgb[p] = argb and 0xFFFFFF
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            glowAmt[p] = ph.clamp01((lum - 0.6f) / 0.4f)
            val d = ph.delayField(col, row, n, seed)
            sweepDelay[p] = d
            noise[p] = ph.hash01(col, row, seed + 1)
            noise2[p] = ph.hash01(col, row, seed + 2)
            relMs[p] = ph.releaseMs(d)
            fsMs[p] = ph.flightStartMs(d, noise[p])
            fdMs[p] = ph.flightDurMs(noise2[p])
            off1[p] = (ph.hash01(col, row, seed + 3) - 0.5f) * 0.9f
            off2[p] = (ph.hash01(col, row, seed + 4) - 0.5f) * 0.6f
            ang[p] = ph.hash01(col, row, seed + 5) * 6.2831855f
            hx[p] = (col + 0.5f) / n
            hy[p] = (row + 0.5f) / n
            sx[p] = srcCx + ph.coverOffset(hx[p], srcSide)
            sy[p] = srcCy + ph.coverOffset(hy[p], srcSide)
            // Cells of the square that the box crops away start invisible and fade in as they let go.
            srcHidden[p] = ph.outsideCover(sx[p] - srcCx, srcHalfW, cellSW) ||
                ph.outsideCover(sy[p] - srcCy, srcHalfH, cellSH)
            val bg = ph.hash01(col, row, seed + 101) < ArtTransitionPhysics.BG_FRACTION
            isBg[p] = bg
            if (bg) {
                val k = ph.sinkChunk(d)
                chunkR[k] += r
                chunkG[k] += g
                chunkB[k] += b
                chunkX[k] += sx[p]
                chunkY[k] += sy[p]
                chunkN[k] += 1
                bgCount++
            }
        }
    }

    /** Turns this run into a quick fade-out (a newer run replaced it). */
    fun beginFade() {
        if (fadeAgeMs < 0f) fadeAgeMs = 0f
    }

    /**
     * Advances [dtMs] (already clamped by the caller). [audioReady] only decides whether the particles
     * wait above the pill. [tb] is the pill thumbnail's bounds right now. A zero [tb] ends the run.
     */
    fun step(dtMs: Float, audioReady: Boolean, tb: Rect, sink: ArtBackdropSink?) {
        if (finished) return
        val tw = tb.width
        val th = tb.height
        if (tw < 2f || th < 2f) {
            finished = true
            return
        }
        tMs += dtMs
        if (!dirsReady) {
            prepareDirections(tb)
            dirsReady = true
        }

        var fadeMul = 1f
        val fadingOut = fadeAgeMs >= 0f
        if (fadingOut) {
            fadeAgeMs += dtMs
            fadeMul = 1f - ph.clamp01(fadeAgeMs / FADE_OUT_MS)
            if (fadeMul <= 0f) {
                finished = true
                return
            }
        }

        if (audioReady && readyAtMs < 0f) readyAtMs = tMs
        // Visual-only safety: never orbit forever if the audio side never reports.
        if (readyAtMs < 0f && tMs > HOLD_TIMEOUT_MS) readyAtMs = tMs
        val ready = readyAtMs >= 0f
        val hmTarget = if (ready) 0f else 1f
        val hmStep = dtMs / 220f
        holdMix = if (holdMix < hmTarget) min(hmTarget, holdMix + hmStep) else max(hmTarget, holdMix - hmStep)

        if (ready && pillPending == 0) {
            val rs = max(ArtTransitionPhysics.REVEAL_START_MS, lastSettleBeginMs + 120f)
            reveal = ph.smoothstep(rs, rs + ArtTransitionPhysics.REVEAL_MS, tMs)
        } else {
            reveal = 0f
        }
        sourceFade = ph.smoothstep(30f, 100f, tMs)

        if (!fadingOut) fireSink(sink)

        fL = tb.left
        fT = tb.top
        fTw = tw
        fTh = th
        // The pill thumbnail shows the square artwork "cover" too: a square as big as its longer side, centred.
        fCx = tb.left + tw * 0.5f
        fCy = tb.top + th * 0.5f
        fS = ph.coverSide(tw, th)
        fCw = fS / n
        fCh = fS / n
        fOcx = tb.left + tw * 0.5f
        fOcy = tb.top - th * 0.30f
        fLift = ph.liftScale(tMs)
        fHold = holdMix
        fReady = ready
        fDtMs = dtMs
        fDtSec = dtMs / 1000f
        fTSec = tMs / 1000f
        fRevealFade = ph.easeInOutCubic(reveal)

        var stillPending = 0
        for (p in 0 until count) {
            if (isBg[p]) stepBg(p) else stillPending += stepPill(p)
            alpha[p] = alpha[p] * fadeMul
        }
        pillPending = stillPending

        if (reveal >= 1f && tMs > 1000f) finished = true
    }

    private fun prepareDirections(tb: Rect) {
        val tc = tb.center
        var ux = srcCx - tc.x
        var uy = srcCy - tc.y
        val len = sqrt(ux * ux + uy * uy)
        if (len < 1f) {
            ux = -1f
            uy = 0f
        } else {
            ux /= len
            uy /= len
        }
        for (p in 0 until count) {
            if (!isBg[p]) continue
            val ja = (noise[p] - 0.5f) * 1.4f
            val c = cos(ja)
            val s = sin(ja)
            dirX[p] = ux * c - uy * s
            dirY[p] = ux * s + uy * c
        }
    }

    private fun fireSink(sink: ArtBackdropSink?) {
        for (k in 0 until ArtTransitionPhysics.SINK_CHUNKS) {
            if (chunkFired[k] || tMs < ph.sinkChunkMs(k)) continue
            chunkFired[k] = true
            val cnt = chunkN[k]
            if (cnt <= 0 || sink == null) continue
            val inv = 1f / cnt
            val strength = ph.clamp01(cnt.toFloat() / max(1, bgCount) * ArtTransitionPhysics.SINK_CHUNKS * 0.5f)
            sink.inject(
                Color(chunkR[k] * inv, chunkG[k] * inv, chunkB[k] * inv),
                Offset(chunkX[k] * inv, chunkY[k] * inv),
                max(0.15f, strength),
            )
        }
    }

    private fun orbit(p: Int) {
        val a = ang[p] + fTSec * 2.4f * (0.8f + 0.5f * noise[p])
        val rx = fTw * (0.34f + 0.16f * noise2[p])
        val ry = fTh * (0.10f + 0.08f * noise2[p])
        fOx = fOcx + cos(a) * rx
        fOy = fOcy + sin(a) * ry
    }

    private fun setPos(p: Int, nx: Float, ny: Float) {
        if (fDtSec > 0f) {
            vx[p] = (nx - px[p]) / fDtSec
            vy[p] = (ny - py[p]) / fDtSec
        }
        px[p] = nx
        py[p] = ny
    }

    private fun enterSettle(p: Int) {
        state[p] = 4
        lastSettleBeginMs = tMs
    }

    /** Background particle: lets go, drifts back and away, grows, softens, fades. */
    private fun stepBg(p: Int) {
        val t = tMs
        val rel = relMs[p]
        val bx = srcCx + (sx[p] - srcCx) * fLift
        val by = srcCy + (sy[p] - srcCy) * fLift
        val baseW = cellSW * fLift * 1.06f
        val baseH = cellSH * fLift * 1.06f
        glow[p] = 0f
        if (t < rel) {
            px[p] = bx
            py[p] = by
            dw[p] = baseW
            dh[p] = baseH
            alpha[p] = if (srcHidden[p]) 0f else ph.smoothstep(20f, 100f, t)
            rad[p] = 0f
            return
        }
        val life = ArtTransitionPhysics.BG_LIFE_MS + noise2[p] * ArtTransitionPhysics.BG_LIFE_VAR_MS
        val u = ph.clamp01((t - rel) / life)
        if (u >= 1f) {
            alpha[p] = 0f
            return
        }
        val e = ph.easeOutCubic(u)
        val col = ph.cellCol(p, n)
        val row = ph.cellRow(p, n)
        ph.curlNoise(col * 0.09f, row * 0.09f, fTSec * 0.8f + noise[p] * 3f, tmp)
        val dist = srcW * (0.9f + noise[p] * 1.4f)
        px[p] = bx + (dirX[p] * e + tmp[0] * 0.35f * u) * dist
        py[p] = by + (dirY[p] * e + tmp[1] * 0.35f * u) * dist
        val grow = 1f + (2.2f + noise2[p] * 1.6f) * e
        dw[p] = max(baseW * grow, 1.4f * e)
        dh[p] = max(baseH * grow, 1.4f * e)
        alpha[p] = 0.9f * (1f - ph.easeInCubic(u)) * (if (srcHidden[p]) ph.clamp01(u * 5f) else 1f)
        rad[p] = 1f
    }

    /** Pill particle. Returns 1 while it has not started to settle (the reveal waits for all of them). */
    private fun stepPill(p: Int): Int {
        val t = tMs
        val col = ph.cellCol(p, n)
        val row = ph.cellRow(p, n)
        val rel = relMs[p]
        val bx = srcCx + (sx[p] - srcCx) * fLift
        val by = srcCy + (sy[p] - srcCy) * fLift
        val baseW = cellSW * fLift * 1.06f
        val baseH = cellSH * fLift * 1.06f
        var st = state[p].toInt()

        if (st == 0) {
            if (t < rel) {
                px[p] = bx
                py[p] = by
                dw[p] = baseW
                dh[p] = baseH
                alpha[p] = if (srcHidden[p]) 0f else ph.smoothstep(20f, 100f, t)
                rad[p] = 0f
                glow[p] = 0f
                return 1
            }
            if (t < fsMs[p]) {
                val u = ph.clamp01((t - rel) / max(1f, fsMs[p] - rel))
                ph.curlNoise(col * 0.11f, row * 0.11f, fTSec + noise[p] * 3f, tmp)
                val amp = srcW * 0.22f * ph.easeOutCubic(u)
                px[p] = bx + tmp[0] * amp
                py[p] = by + tmp[1] * amp
                dw[p] = baseW * (1f + 0.4f * u)
                dh[p] = baseH * (1f + 0.4f * u)
                alpha[p] = if (srcHidden[p]) u else 1f
                rad[p] = u
                glow[p] = glowAmt[p] * u * 0.5f
                return 1
            }
            state[p] = 2
            st = 2
            sx0[p] = px[p]
            sy0[p] = py[p]
        }

        val hxp = fCx + ph.coverOffset(hx[p], fS)
        val hyp = fCy + ph.coverOffset(hy[p], fS)
        // Cells that land outside a non-square thumbnail fade out on arrival instead of spilling over.
        val homeOut = ph.outsideCover(hxp - fCx, fTw * 0.5f, fCw) || ph.outsideCover(hyp - fCy, fTh * 0.5f, fCh)

        if (st == 2) {
            val pr = ph.clamp01((t - fsMs[p]) / fdMs[p])
            val e = ph.easeInOutCubic(pr)
            val dx = hxp - sx0[p]
            val dy = hyp - sy0[p]
            val dist = sqrt(dx * dx + dy * dy) + 0.001f
            val ov = 0.10f * fTw * (0.4f + noise[p])
            orbit(p)
            val tex = ph.lerp(hxp + dx / dist * ov, fOx, fHold)
            val tey = ph.lerp(hyp + dy / dist * ov, fOy, fHold)
            val ddx = tex - sx0[p]
            val ddy = tey - sy0[p]
            val dd = sqrt(ddx * ddx + ddy * ddy) + 0.001f
            val nx = -ddy / dd
            val ny = ddx / dd
            val c1x = sx0[p] + ddx * 0.28f + nx * off1[p] * dd
            val c1y = sy0[p] + ddy * 0.28f + ny * off1[p] * dd
            val c2x = sx0[p] + ddx * 0.72f + nx * off2[p] * dd
            val c2y = sy0[p] + ddy * 0.72f + ny * off2[p] * dd
            var npx = ph.bezier(sx0[p], c1x, c2x, tex, e)
            var npy = ph.bezier(sy0[p], c1y, c2y, tey, e)
            val env = ph.bump(pr)
            ph.curlNoise(col * 0.11f + 3f, row * 0.11f, fTSec * 1.6f + noise[p] * 3f, tmp)
            val wob = min(dd * 0.10f, 28f) * env
            npx += tmp[0] * wob
            npy += tmp[1] * wob
            setPos(p, npx, npy)
            val swell = 1f + 0.9f * env
            dw[p] = max(ph.lerp(baseW, fCw, e) * swell, FLIGHT_MIN_DOT * env)
            dh[p] = max(ph.lerp(baseH, fCh, e) * swell, FLIGHT_MIN_DOT * env)
            alpha[p] = (1f - 0.12f * env) * (1f - fRevealFade) * (if (homeOut) 1f - ph.smoothstep(0.55f, 0.95f, pr) else 1f)
            rad[p] = 1f
            glow[p] = glowAmt[p] * env
            if (pr >= 1f) {
                if (fReady) enterSettle(p) else state[p] = 3
            }
            return 1
        }

        if (st == 3) {
            orbit(p)
            ph.curlNoise(col * 0.11f + 7f, row * 0.11f, fTSec * 0.8f, tmp)
            val bob = fTw * 0.02f
            setPos(p, fOx + tmp[0] * bob, fOy + tmp[1] * bob)
            val k = min(1f, fDtMs / 120f)
            dw[p] += (max(fCw * 1.6f, FLIGHT_MIN_DOT) - dw[p]) * k
            dh[p] += (max(fCh * 1.6f, FLIGHT_MIN_DOT) - dh[p]) * k
            alpha[p] = 0.9f * (1f - fRevealFade)
            rad[p] = 1f
            glow[p] = glowAmt[p]
            if (fReady && t >= readyAtMs + noise2[p] * 140f) enterSettle(p)
            return 1
        }

        if (st == 4) {
            ph.springStep(px[p], vx[p], hxp, MusesMotion.SETTLE_STIFFNESS, MusesMotion.SETTLE_DAMPING, fDtSec, tmp)
            ph.springStep(py[p], vy[p], hyp, MusesMotion.SETTLE_STIFFNESS, MusesMotion.SETTLE_DAMPING, fDtSec, tmp2)
            px[p] = tmp[0]
            vx[p] = tmp[1]
            py[p] = tmp2[0]
            vy[p] = tmp2[1]
            val k = min(1f, fDtMs / 90f)
            dw[p] += (fCw + SEAM_PX - dw[p]) * k
            dh[p] += (fCh + SEAM_PX - dh[p]) * k
            rad[p] = max(0f, rad[p] - fDtMs / 160f)
            glow[p] = max(0f, glow[p] - fDtMs / 200f)
            alpha[p] = if (homeOut) 0f else 1f - fRevealFade
            val ex = px[p] - hxp
            val ey = py[p] - hyp
            if (ex * ex + ey * ey < 0.04f && vx[p] * vx[p] + vy[p] * vy[p] < 4f && rad[p] <= 0f) state[p] = 5
            return 0
        }

        // Home: exactly on its pixel of the pill thumbnail, following it if it moves.
        px[p] = hxp
        py[p] = hyp
        dw[p] = fCw + SEAM_PX
        dh[p] = fCh + SEAM_PX
        rad[p] = 0f
        glow[p] = 0f
        alpha[p] = if (homeOut) 0f else 1f - fRevealFade
        return 0
    }

    private companion object {
        /** Particles fade out this fast when a newer transition replaces them. */
        const val FADE_OUT_MS = 150f

        /** The orbit is only visual: after this long without audio the particles settle anyway. */
        const val HOLD_TIMEOUT_MS = 6500f

        /** A flying particle is never smaller than this, so it reads as a soft dot, not a speck. */
        const val FLIGHT_MIN_DOT = 1.6f

        /** Home cells overlap by this much so antialiasing leaves no hairline seams. */
        const val SEAM_PX = 0.75f
    }
}
