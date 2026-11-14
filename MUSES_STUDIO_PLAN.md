# Muses cycle 4: organised Settings, new Studio, liquid-glass pill

Plain Muses music player only (no Hub/Odeon code). Read only: this file and the last 2 sections of `HANDOFF.md`.
If `design/pill-reference.png` exists, look at it before part G3 (it is the owner's screenshot of the current bottom pill).

## Conditions
Free-tier Claude accounts, Sonnet 5.5 medium, fresh chat each, limit resets about every 5 hours. Nobody can compile or see the UI; the owner's
GitHub Actions run is the compiler and the owner is the eyes. The owner cannot answer questions: decide, write the decision in HANDOFF.md, continue.
Never open `DesktopApp.kt` whole (8,400 lines): `grep -n` and `sed -n 'A,Bp'` on small ranges. Short replies. If the limit is near, stop adding code, write the
HANDOFF section with a **Deferred** list, output the zip.

## Hard rules
1. No new dependencies, no version changes (Compose 1.10.3, Skiko 0.9, Haze 1.7.2 stay). Only APIs already used in the repo or confirmed in a source; copy exact imports.
   **Exception, only for part G1/G2/G3:** Skia shaders are allowed (`org.jetbrains.skia.RuntimeEffect`, `RuntimeShaderBuilder`, `ImageFilter.makeRuntimeShader`,
   `asComposeRenderEffect`), because the owner explicitly wants real refraction. Every shader use must be wrapped so a failure falls back to the current glass (see G1).
2. Animate only `graphicsLayer` transforms/alpha and cheap draws. No per-frame allocations you can avoid. Respect `DesktopAppearanceSettings.reduceAnimation` and `reduceDynamicBlur`.
3. Do not change playback, audio, lyrics, library or account logic. Do not delete settings, strings or features. Settings keep their persistence keys.
4. Desktop only (`desktopApp`). Must still compile on Linux. Do not edit `sharedUi`, `shared`, `app`.
5. Compile review each file: imports, nullability, `@Composable` context, duplicate names (grep before adding), no `private` type in an `internal` signature,
   `Modifier.align` only inside Box/Column/Row scopes. Mark edits to existing files with `// STUDIO <part> hook`.
6. Append a HANDOFF section (never overwrite): Did / Files / Decisions / Verified (say plainly NOT compiled) / Deferred / Exact next step. Output the full project as
   `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`).

## What the owner wants (decided)
A. **Settings organised**: today it is one long flat page of 16 groups (`DesktopSettingsSection` enum, `DesktopSettingsScreen` in `DesktopApp.kt` ~5576-6400, a very long
   parameter list). It must be easy to find things: music settings together, appearance together, advanced separate.
B. **A new, organised Studio** (today: `DesktopCustomiseHubPage`, `DesktopPillStudioPage` in `DesktopPillStudio.kt`, "Advanced customisation" inside Appearance) with
   more customisation: one place for Pill, Home layout, Colours/typography, presets and import/export.
C. **A new bottom pill, built from scratch, in liquid glass**: real refraction (light bending at the edges), reflections/specular rim, chromatic dispersion,
   frost, album-reactive light and colour, proper animations. Keep the existing pill as the fallback "Classic" material.

## Target settings structure (S1 decides details after reading each group's contents)
Left category rail + the existing groups on the right. Search keeps working across ALL categories (when the query is not empty, show matching groups from every
category with the category name above them).
1. **Account and sync**: ACCOUNT, LISTEN_TOGETHER
2. **Playback**: PLAYBACK, DESKTOP_PLAYBACK (crossfade, speed, automix...), LYRICS (as its own group inside this category or its own category: S1 decides)
3. **Sound**: AUDIO_QUALITY, AUDIO_OUTPUT, OUTPUT_PRECISION, equaliser link, spatial audio
4. **Library and sources**: SOURCES, LOCAL_MUSIC, DOWNLOADS, STORAGE
5. **Appearance**: sub-headers inside: Interface, Colours and background, Home, Player pill (opens Studio), Window and performance
6. **System**: LANGUAGE, tray/startup toggles, MISCELLANEOUS
7. **Advanced and diagnostics**: ADVANCED, memory report, nerd stats, licences/integrations
Rule: the group composables and callbacks stay as they are (no logic rewrite). Only grouping, order, headers and navigation change.

## Parts (one account each, in this order)

### S1: Settings shell (edit `DesktopApp.kt` around `DesktopSettingsScreen`, new file `DesktopSettingsCategories.kt`, test)
Add `DesktopSettingsCategory` (enum with title, icon, list of sections) and a pure mapping function `categoryOf(section)` with a test that every `DesktopSettingsSection` belongs to exactly one category.
Keep the `DesktopSettingsScreen` parameter list unchanged. Add a left rail (about 200 dp) with the categories (selected one highlighted, remembered while the app runs), and show only the groups of the
selected category; when `query` is not blank show all groups (existing `LocalSettingsQuery` filtering) so search still finds everything. Make the page work at narrow widths (rail becomes a row of chips).
Acceptance: every old setting is still reachable; search finds settings in any category; no setting removed.

### S2: Appearance and Studio landing (files `DesktopStudioHub.kt`, edits in `DesktopApp.kt` Appearance group ~5996-6214 and `DesktopCustomiseHubPage`)
Split the long Appearance group into labelled sub-groups (Interface, Colours and background, Home, Window and performance) using the existing toggle composables unchanged.
Replace the Customise hub with a "Studio" landing page: four cards (Pill, Home layout, Colours and typography, Presets and import/export) each opening the existing page for it
(Pill Studio, Home sections, Advanced customisation). Presets and import/export: a page that lists the existing preset/import/export actions found in `DesktopAdvancedAppearance.kt` and `DesktopPillStudio.kt`
in one place (reuse the functions; do not rewrite them). Add "Reset all customisation" with a confirmation.
Acceptance: all previous customisation pages still reachable; nothing removed.

### G1: Glass engine (new files `DesktopGlassShader.kt`, `DesktopGlassParams.kt`, tests)
Pure, testable core. `DesktopGlassParams` (data class, all floats with defaults and `coerceIn` validation): `refraction` 0..1, `bevelDp`, `magnify`, `dispersion`, `frost` (blur mix), `blurDp`, `specular`, `rim`, `tintAlpha`, `lightAngleDeg`, `albumInfluence`.
`DesktopGlassShader`: holds the SkSL source (below) as a `const val`, compiles it once with `RuntimeEffect.makeForShader`, inside `runCatching`; exposes `val available: Boolean` (false on any failure or on non-Skia) and
`fun effect(params, sizePx, radiusPx, tint, accent, timeSec): RenderEffect?` using `RuntimeShaderBuilder(effect)` + `uniform(name, value...)` and
`ImageFilter.makeRuntimeShader(builder, arrayOf("content", "blurred"), arrayOf(null, ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP))).asComposeRenderEffect()`.
Create the builder once per size (cache), update uniforms only; no allocation per frame beyond what the API forces.
Pure helpers with tests (mirror the shader maths in Kotlin so they can be tested): `sdRoundRect`, `edgeProfile(insideDistance, bevel)` (smooth 1 at the rim to 0 at the bevel), `uniformsFor(params, ...)` order/length.
Reference shader (start from this; fix SkSL details by careful reading, the owner cannot test it before G2's lab):
```
uniform shader content;     // sharp backdrop crop (the layer's own content)
uniform shader blurred;     // same crop, blurred
uniform float2 size;        // glass size in px
uniform float radius;       // corner radius px
uniform float bevel;        // refraction band width px
uniform float strength;     // max displacement px
uniform float magnify;      // 0..0.2 centre magnification
uniform float dispersion;   // 0..1 chromatic split
uniform float frost;        // 0..1 blur mix
uniform float4 tint;        // rgb + alpha of the glass tint
uniform float4 accent;      // album colour rgb + influence
uniform float specular;     // 0..1
uniform float rim;          // 0..1
uniform float2 lightDir;    // normalised, points towards the light
uniform float time;
float sdRoundRect(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + r;
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
half4 main(float2 fc) {
  float2 c = size * 0.5;
  float2 p = fc - c;
  float d = sdRoundRect(p, c, radius);              // < 0 inside
  float cover = clamp(0.5 - d, 0.0, 1.0);           // anti-aliased mask
  if (cover <= 0.0) return half4(0.0);
  float inside = -d;
  float t = clamp(1.0 - inside / bevel, 0.0, 1.0);  // 1 at the rim, 0 deeper than the bevel
  float prof = t * t * (3.0 - 2.0 * t);
  float2 g = float2(sdRoundRect(p + float2(1,0), c, radius) - sdRoundRect(p - float2(1,0), c, radius),
                    sdRoundRect(p + float2(0,1), c, radius) - sdRoundRect(p - float2(0,1), c, radius));
  float2 n = g / max(length(g), 1e-4);              // outward normal
  float2 base = c + (fc - c) * (1.0 - magnify);     // gentle magnification
  float2 off = -n * strength * prof;                // sample from further inside: edges bend the scene
  float2 uvR = base + off * (1.0 + dispersion);
  float2 uvG = base + off;
  float2 uvB = base + off * (1.0 - dispersion);
  half3 sharp = half3(content.eval(uvR).r, content.eval(uvG).g, content.eval(uvB).b);
  half3 soft = blurred.eval(uvG).rgb;
  half3 col = mix(sharp, soft, frost);
  col = mix(col, tint.rgb, tint.a);
  col = mix(col, accent.rgb, accent.a * 0.35 * (0.4 + 0.6 * prof));
  float facing = max(dot(n, lightDir), 0.0);
  float back = max(dot(n, -lightDir), 0.0);
  float spec = (pow(facing, 3.0) * 0.9 + pow(back, 3.0) * 0.35) * pow(t, 2.0) * specular;
  float edge = pow(t, 4.0) * rim;
  col += half3(spec) + half3(edge * 0.25);
  float top = clamp(1.0 - (fc.y / size.y) * 2.2, 0.0, 1.0);   // soft sheen on the upper part
  col += half3(top * 0.06 * specular);
  return half4(col * cover, cover);
}
```
Failure rule: if the effect cannot be built for any reason, `available` is false and every caller uses the classic glass. A shader problem must NEVER crash the app or blank the pill.

### G2: Backdrop capture and Glass Lab (files `DesktopGlassSurface.kt`; edit `DesktopFrame` page-content Box, hook G2; lab entry in Settings > Advanced)
Pattern already compiled in this repo: `LocalGraphicsContext.current.createGraphicsLayer()` and `layer.record { this@drawWithContent.drawContent() }` (see `sharedUi/.../LongPressOrigin.kt` ~line 116; copy the exact imports from there).
- `GlassSource`: a modifier `Modifier.glassSource(state)` that records the page content into one shared `GraphicsLayer` held by `DesktopGlassState` (provided through a `CompositionLocal`), on top of the existing `hazeSource` (do not remove Haze). Re-record only when the window is visible; skip while the window is hidden to the tray (memory).
- `Modifier.glassSurface(shape, params, tint, accent)`: records the cropped region (translate by the surface's position in root; use `onGloballyPositioned` + `positionInRoot`) into the surface's own small layer, sets that layer's `renderEffect` from G1, draws it, then draws content on top. Clip to the shape. If `!DesktopGlassShader.available` or `reduceDynamicBlur` or the state is missing: fall back to `desktopBarGlass`/`fluidGlass` exactly as the classic pill does.
- `time` uniform: drive it with the existing 30 fps clock pattern used in `DesktopFluidity.kt` (and stop when `reduceAnimation` or the window is hidden).
- **Glass Lab**: a plain page under Settings > Advanced ("Glass lab (experimental)") showing a 640x80 glass bar over a moving test pattern (stripes + album art if playing) with sliders for every `DesktopGlassParams` field, and a text line saying `shader available: yes/no` plus the compile error message if any. Purpose: the owner judges the look and reports back with screenshots so the shader can be tuned.
Acceptance: lab opens, shows state, falls back silently when the shader fails; main app unaffected when glass is off.

### G3: The new pill (new file `DesktopLiquidPill.kt`; edit the single call site of `DesktopFloatingMiniPlayer` in `DesktopApp.kt` ~line 3033, hook G3)
Build the pill from scratch as `DesktopLiquidPill(...)` with the same parameters and callbacks as `DesktopFloatingMiniPlayer` (do not touch playback). Keep `DesktopFloatingMiniPlayer` as the Classic material and choose between them with a new persisted `DesktopPillMaterial.LIQUID_GLASS | CLASSIC` setting (default LIQUID_GLASS; falls back to CLASSIC automatically when the shader is unavailable).
Design (look at `design/pill-reference.png` if present, keep its element layout): rounded pill, artwork left, title/artist, transport, progress; lay elements out by the existing `DesktopPillConfig.zones` so Studio keeps working.
- Material: `glassSurface` with params from the config, accent = album dominant colour (reuse the palette cache used by the backdrop in `DesktopFluidity.kt`: `rememberFluidPalette`/`extractAlbumPalette`), tint dark for readability (text contrast must stay readable over bright covers: enforce a minimum tint alpha from `contrast`).
- Motion (springs from `MusesMotion`, transforms/alpha only): press squish (scale 0.97), hover glow, a light sweep across the rim on track change (the specular angle animates once), a gentle breathing of the rim while playing, play/pause icon morph, artwork cross-fade on track change per `trackAnimation`, entry slide-up/fade. `reduceAnimation` snaps.
- Progress: a thin bar with a glass-lit knob on hover; seek keeps using the existing seek callback only if the current pill already offers one.
Acceptance: all current pill controls work (previous/play/next/shuffle/repeat/more menu/expand, Pill Studio entry); Classic still selectable.

### G4: Studio v2 for the pill (edit `DesktopPillStudio.kt`, `DesktopPillConfig` fields with defaults + `validated()`, tests)
Reorganise Pill Studio into tabs: **Layout** (zones, elements, order, width, position, inset), **Material** (Liquid glass / Classic, every `DesktopGlassParams` slider, shape, corner radius, height, contrast, rim, album influence), **Motion** (track animation, spin, hover expand, press feel), **Artwork** (placement, size, shape, spin), **Presets** (built-in presets: Crystal, Frosted, Aurora (strong album colour), Minimal, Solid; user presets save/load/delete; import/export JSON).
New config fields must have defaults so old saved configs still load (`ignoreUnknownKeys` already on). The live preview uses the real `DesktopLiquidPill` over a test backdrop (stripes + album art).
Tests: config round trip with old JSON, `validated()` clamps new fields, preset apply.

### G5: Integration and CI
Compile-review all new files together, fix CI logs, make sure Classic and Liquid both work with Pill Studio, update `HANDOFF.md` and `README` (a short "Studio and liquid glass" paragraph with the honest limits below).

## Honest limits (put them in the README and the Studio help text)
- The glass refracts what is inside the Muses window (the page), not the desktop behind a translucent Mica/Acrylic window; real desktop refraction is not possible with this stack.
- It is a Skia shader: first compile may take a moment; very low-end GPUs may fall back to Classic.
- Nobody could run the shader before the owner's build; expect one or two tuning rounds using Glass Lab screenshots.

## Owner's route
S1, S2 (then run Actions and look at Settings) -> G1, G2 (run Actions, open Glass lab, send screenshots) -> G3, G4 -> G5. Put your screenshot in `design/pill-reference.png` before G3.
