package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * STUDIO G1: the liquid-glass shader, compiled once and guarded.
 *
 * A shader problem must never crash the app or blank the pill: the source is compiled inside
 * `runCatching` (any Throwable, so a missing native library counts too), every later call is
 * wrapped, and any failure flips [available] to false for the rest of the session. Callers read
 * [available] and use the classic glass when it is false. [available] is backed by snapshot state,
 * so a composable that read it recomposes into the fallback the moment a runtime failure is noted.
 */
internal object DesktopGlassShader {

    /**
     * Two children: `content` is the layer's own picture, `blurred` the same picture blurred.
     * Coordinates are px inside the glass (0,0 is its top-left corner); `origin` rescues window coordinates. Every uniform is used, so
     * none is optimised away and every name set from Kotlin exists.
     */
    const val SKSL: String = """
uniform shader content;
uniform shader blurred;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float magnify;
uniform float dispersion;
uniform float frost;
uniform float4 tint;
uniform float4 accent;
uniform float specular;
uniform float rim;
uniform float2 lightDir;
uniform float time;
uniform float2 origin;

float sdRoundRect(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + r;
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float2 keepInside(float2 uv) {
  return clamp(uv, float2(0.5), size - float2(0.5));
}

half4 main(float2 fcIn) {
  // Normally fcIn runs 0..size inside the glass. If this Skia build hands over window coordinates
  // instead, the point lies far outside that box; then take the glass's window position off.
  float2 shift = float2(0.0);
  if (fcIn.x < -2.0 || fcIn.y < -2.0 || fcIn.x > size.x + 2.0 || fcIn.y > size.y + 2.0) {
    shift = origin;
  }
  float2 fc = fcIn - shift;
  float2 c = size * 0.5;
  float2 p = fc - c;
  float d = sdRoundRect(p, c, radius);
  float cover = clamp(0.5 - d, 0.0, 1.0);
  if (cover <= 0.0) {
    return half4(0.0);
  }
  float inside = -d;
  float t = clamp(1.0 - inside / max(bevel, 0.0001), 0.0, 1.0);
  float prof = t * t * (3.0 - 2.0 * t);
  float2 g = float2(
    sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
    sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius)
  );
  float2 n = g / max(length(g), 0.0001);
  float2 base = c + (fc - c) * (1.0 - magnify);
  float2 off = -n * strength * prof;
  float2 uvR = keepInside(base + off * (1.0 + dispersion)) + shift;
  float2 uvG = keepInside(base + off) + shift;
  float2 uvB = keepInside(base + off * (1.0 - dispersion)) + shift;
  float4 sr = float4(content.eval(uvR));
  float4 sg = float4(content.eval(uvG));
  float4 sb = float4(content.eval(uvB));
  float4 bl = float4(blurred.eval(uvG));
  float3 sharp = float3(sr.r, sg.g, sb.b);
  float3 col = mix(sharp, bl.rgb, frost * 0.6);
  col = mix(col, tint.rgb, tint.a * 0.15);
  col = mix(col, accent.rgb, accent.a * 0.30 * (0.4 + 0.6 * prof));
  float facing = max(dot(n, lightDir), 0.0);
  float back = max(dot(n, -lightDir), 0.0);
  float spec = (pow(facing, 3.0) * 0.9 + pow(back, 3.0) * 0.35) * pow(t, 2.0) * specular;
  float shimmer = 0.85 + 0.15 * sin(time * 0.9 + (fc.x / max(size.x, 1.0)) * 6.2831853);
  float edge = pow(t, 4.0) * rim * shimmer;
  float top = clamp(1.0 - (fc.y / max(size.y, 1.0)) * 2.2, 0.0, 1.0);
  float shine = clamp(spec + edge * 0.25 + top * 0.05 * specular, 0.0, 1.0);
  // This layer is an overlay on a frosted pane that already carries the blur and the tint (the text
  // stays readable whatever this does). It adds the bent scene only in the rim band, plus the light.
  float ring = clamp(prof * 0.9, 0.0, 1.0) * clamp(sg.a, 0.0, 1.0);
  float a = clamp(ring + shine * (1.0 - ring), 0.0, 1.0) * cover;
  float3 c = clamp((col * ring + float3(shine * (1.0 - ring))) * cover, 0.0, 1.0);
  return half4(half3(c), half(a));
}
"""

    private val compiled: Result<RuntimeEffect> by lazy {
        runCatching { RuntimeEffect.makeForShader(SKSL) }
    }

    /** A failure after the compile (building a filter, setting a uniform); null until one happens. */
    private var runtimeFailure: String? by mutableStateOf(null)

    /** True when the shader compiled and nothing has failed since. False means: use the classic glass. */
    val available: Boolean
        get() = compiled.isSuccess && runtimeFailure == null

    /** Why the shader is unavailable, for the Glass Lab; null while it is available. */
    val error: String?
        get() = compiled.exceptionOrNull()?.describe() ?: runtimeFailure

    /** Records a failure from a caller (for example a surface whose [effect] came back null). */
    fun reportFailure(message: String) {
        if (runtimeFailure == null) runtimeFailure = message
    }

    // The builder holds no size: every uniform, size included, is rewritten on each call, so one
    // builder serves every glass surface. All access is under [lock] (the UI thread in practice).
    private val lock = Any()
    private var builder: RuntimeShaderBuilder? = null
    private var blurSigma = Float.NaN
    private var blurFilter: ImageFilter? = null
    private val scratch = FloatArray(DesktopGlassMath.UNIFORM_LENGTH)

    /**
     * The render effect for one frame of a glass [widthPx] x [heightPx], or null when the shader is
     * unavailable or anything goes wrong (which also marks it unavailable). The result is a
     * snapshot of the uniforms, so a new one is needed whenever [timeSec] or a parameter changes;
     * the builder and the blur filter are reused.
     */
    fun effect(
        params: DesktopGlassParams,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        tint: Color,
        accent: Color,
        timeSec: Float,
        density: Float = 1f,
        originX: Float = 0f,
        originY: Float = 0f,
    ): RenderEffect? {
        if (!available) return null
        if (widthPx < 1f || heightPx < 1f) return null
        return try {
            synchronized(lock) {
                val shader = compiled.getOrNull() ?: return null
                val b = builder ?: RuntimeShaderBuilder(shader).also { builder = it }
                val u = DesktopGlassMath.uniformsFor(
                    params, widthPx, heightPx, radiusPx, density, tint, accent, timeSec, scratch, originX, originY,
                )
                var at = 0
                for (k in DesktopGlassMath.UNIFORM_NAMES.indices) {
                    val name = DesktopGlassMath.UNIFORM_NAMES[k]
                    when (DesktopGlassMath.UNIFORM_WIDTHS[k]) {
                        1 -> b.uniform(name, u[at])
                        2 -> b.uniform(name, u[at], u[at + 1])
                        4 -> b.uniform(name, u[at], u[at + 1], u[at + 2], u[at + 3])
                    }
                    at += DesktopGlassMath.UNIFORM_WIDTHS[k]
                }
                val sigma = (params.validated().blurDp * density * 0.5f).coerceAtLeast(0.5f)
                val blur = blurFilter?.takeIf { sigma == blurSigma }
                    ?: ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).also {
                        blurFilter = it
                        blurSigma = sigma
                    }
                ImageFilter.makeRuntimeShader(
                    b,
                    arrayOf("content", "blurred"),
                    arrayOf<ImageFilter?>(null, blur),
                ).asComposeRenderEffect()
            }
        } catch (t: Throwable) {
            reportFailure(t.describe())
            null
        }
    }

    private fun Throwable.describe(): String = (message ?: javaClass.simpleName).trim().take(400)
}
