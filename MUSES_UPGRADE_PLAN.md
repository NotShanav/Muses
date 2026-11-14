# Muses upgrade plan: album-colour background + memory (cycle 3)

This cycle is about the plain Muses music player source (no Hub/Odeon code in this zip). Read only: this file and the last 2 sections of `HANDOFF.md`.

## Conditions
Free-tier Claude accounts, Sonnet 5.5 medium, fresh chat each, limit resets about every 5 hours. Nobody can compile; the owner's GitHub Actions
run is the only compiler. The owner cannot answer questions: decide, write the decision in HANDOFF.md, continue. Never open `DesktopApp.kt` whole.

## Hard rules
1. No new dependencies, no version changes. Only APIs already used in the repo (grep, copy the exact import). Never guess an API.
2. Animate only `graphicsLayer` transforms/alpha and cheap draws. No animating blur radius, no per-frame allocations you can avoid.
   Respect `DesktopAppearanceSettings.reduceAnimation` (snap or short tween) and `reduceDynamicBlur`.
3. Do not change playback, audio, lyrics, library or account logic. Do not delete settings or features.
4. Desktop only (`desktopApp`). Must still compile on Linux. Do not edit `sharedUi`, `shared`, `app`.
5. Compile-review every file you touch: imports, nullability, `@Composable` context, duplicate names (grep before adding), `private` types not
   exposed from `internal` signatures, `Modifier.align` only inside Box/Column/Row scopes.
6. Mark edits to existing files with `// UPGRADE <part>` comments. Append a HANDOFF.md section (never overwrite): Did / Files / Decisions /
   Verified (say plainly NOT compiled) / Deferred / Exact next step. Output the full project as `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`).

## The problem (diagnosed in `DesktopFluidity.kt`, `DesktopPlayerBackground.kt`)
The animated background (`DesktopFluidBackdrop`) is used by the main window (Fluidity mode, `DesktopApp.kt` ~line 2927) and by the full-screen
player's "Album reactive" mode (`DesktopPlayerBackground.kt`). It looks purple/yellow because:
1. Up to 4 glows of different hues are drawn with `BlendMode.Screen`: overlapping red+green = yellow, blue+red = purple.
2. The palette starts as `FluidDefaultPalette` (purple/blue/magenta/cyan) whenever the cover is not cached, and each glow colour is tweened
   separately in RGB (`animateColorAsState`, 1400 ms), so every track change passes through muddy in-between hues and the first track at startup
   always passes through the default purple.
3. A cover with fewer than 4 colours gets neighbouring hues invented (`hueShifted` +-26/52 degrees); grey covers get a silver-blue glow.

## What the owner wants (decided)
- A **three-colour system**: dominant, second, third colour of the cover (a third/second only if it really is present). The glows are built from
  those, plus a lighter tint of the dominant for the fourth glow. Same drift/breathing animation as today (keep the glow motion constants).
- **No additive blending**: normal blending (`SrcOver`), so overlaps do not create new hues.
- **Clean track changes**: old and new palettes cross-dissolve by alpha (two layers), not by tweening colours. The first song fades up from the
  dark neutral base; the default purple palette must NEVER show while a song's cover is loading or after a song has played.
- **Default palette kept only when nothing has been selected** (idle startup).
- **Acrylic/Mica stays as it is**: keep the translucent base (`overMaterial` behaviour) and the glass look. Do NOT make the base opaque.
- No "Vivid" toggle. Keep `DesktopAdvancedAppearance.paletteStrength` and `DesktopFluiditySettings.subtle` working.
- Mode B (Acrylic glass player background) is untouched; the shared backdrop change affects it only through the shared code path, which is fine.

## Part M1: palette engine (pure logic + tests)
Files: new `DesktopAlbumPalette.kt`, new test `DesktopAlbumPaletteTest.kt`; small edits in `DesktopFluidity.kt` (hook M1).
- Move the pixel work out of `extractFluidPalette(bitmap)` into a pure `internal fun extractAlbumPalette(pixels: IntArray, width: Int, height: Int): FluidPalette?`
  so it can be tested. `extractFluidPalette(bitmap)` just reads pixels and calls it. Make `FluidPalette` `internal` (it is `private` now) and the helpers
  `hueGap`, `Hsl`, `toHsl`, `hslColor`, `hueShifted` `internal` (change only the visibility).
- Keep the hue-binning (24 bins, saturation-weighted, skip near-black/near-white/grey pixels, smoothing). Change the selection:
  * Rank hues by weight. Choose up to **three** with hue gap >= 34 degrees. A hue whose weight is under **8 percent** of the chromatic total is
    NOT chosen (a small accent must not become a glow).
  * `colors` stays a list of **4** so the renderer indexing keeps working, with these roles: `[0]` dominant, `[1]` second, `[2]` third, `[3]` a lighter,
    slightly less saturated tint of the dominant (same hue, +0.08 to +0.12 lightness).
  * Missing second/third: fill with tonal variants of the dominant (same hue within +-10 degrees, lightness/saturation shifted). NEVER a new hue family,
    never `hueShifted` by 26/52 degrees.
  * Grey / black-and-white covers (no chromatic hue): neutral greys. Lightness from the cover's average luminance, saturation <= 0.08, may carry the
    average hue faintly. Not silver-blue.
  * Keep lightness clamp 0.46 to 0.60 for glows, `base` = dark shade of the dominant hue (existing formula), `intensity` formula unchanged.
- Add `val shares: List<Float>` (the three weights as fractions) to `FluidPalette` with a default so nothing else breaks; the renderer may use it later.
- Tests (synthetic pixel arrays): solid red, red+blue 70/30 (red dominant, blue second), three-colour cover, 3 percent green accent on red (ignored),
  one-colour cover (all four colours within 12 degrees of the dominant hue), grey, black-and-white, tiny image (2x2) returns null or a palette without
  crashing, deterministic result for the same input.

## Part M2: backdrop renderer (visual behaviour)
Files: `DesktopFluidity.kt` only (hook M2). Public signature of `DesktopFluidBackdrop(artworkUrl, modifier, scrimStrength, overMaterial)` must not change.
- `rememberFluidPalette(url)`: state is nullable "not ready". If `url == null` and nothing has ever been selected this session: the default palette.
  If `url != null` and not cached: keep the PREVIOUS album palette on screen; if there is none yet (first song), draw only the base (no glows) and fade
  the glows in once the palette arrives (about 800 ms, `intensity` 0 to target). Track in a top-level `@Volatile var fluidHasPlayed` so after any song
  the default palette never returns (if `url` later becomes null, keep the last album palette).
- Replace the per-slot `animateColorAsState` and the base tween with a two-layer cross-dissolve: when the palette changes, `from` = what was fully shown,
  `to` = new palette, a single `Animatable<Float>` progress 0 to 1 over 1400 ms (`snap()` when `reduceAnimation`). Draw base(from) then base(to) with
  alpha = progress; draw glows for `from` with alpha x (1 - progress) and for `to` with alpha x progress; skip the `from` layer when progress == 1.
  Retarget mid-dissolve cleanly (the new `from` is the current blend: simplest correct approach is to snap `from = to` and start again, accepting a tiny jump).
- Glows: remove `blendMode = BlendMode.Screen` (use the default). Keep `FluidGlows` motion constants, `swell` burst, 30 fps clock, pause when the
  window is hidden or `reduceAnimation`, `subtle` (2 glows: dominant and second), `paletteStrength`, the scrims, and the translucent base for Acrylic/Mica.
  Per-glow alpha by role: dominant 0.85, second 0.70, third 0.60, tint 0.45 (multiply the existing `glow.alpha` ratios, do not exceed 0.95).
- Update the doc comments that mention Screen blending in `DesktopFluidity.kt` and the readability note in `DesktopPlayerBackground.kt`
  (comment only; SrcOver glows are darker, so the stated contrast ratios only improve).
- No unit tests required; compile-review carefully (Animatable, `withFrameNanos`, `drawRect` ordering).

## Part M3: memory report (diagnostic tool, no behaviour change)
Files: new `DesktopMemoryReport.kt`; one settings entry (hook M3: find where the Fluidity / Advanced appearance toggles are drawn, `grep -n "DesktopFluiditySettings"`);
`desktopApp/build.gradle.kts` (two small additions marked diagnostic).
Goal: tell us where the 1.8 GB (window shown) and 1.2 GB (window hidden to the tray) go. The Java heap is already capped at 512 MB (`-Xmx512m`), so most of it
is native/graphics. The window only hides to the tray (`Window(visible = false)` in `Main.kt`); the composition stays alive.
- `DesktopMemoryReport.snapshot(label: String): String`: label, time, uptime, window visible flag (`DesktopWindowVisibility.visible.value`), heap used/committed/max
  and non-heap used/committed (`java.lang.management.ManagementFactory`), every memory pool (Metaspace, CodeHeap, G1 spaces), buffer pools (direct, mapped),
  thread count and a list of thread-name groups with counts (so a JavaFX WebView shows up as "JavaFX Application Thread"/"QuantumRenderer"; do NOT call
  `DesktopPoTokenWebView.available`, it would start JavaFX), loaded class count, GC counts/time, `DesktopArtworkCache` size if exposed (else skip), fluid palette cache
  size if cheap, and the process working set and commit charge of this process using the already-written probe in `DesktopProcessMemory.kt` (added by the owner's setup; do not rewrite it):
  `ProcessMemoryProbes.forSystem()` gives a `ProcessMemoryProbe` with `workingSetMb(listOf(pid))`, `commitMb(pid)` and `supported` (false = non-Windows, report 0 and say so). Own pid: `ProcessHandle.current().pid()`.
- Native memory tracking: add `-XX:NativeMemoryTracking=summary` to the `jvmArgs(...)` lines (comment: diagnostic, remove later) and add `"jdk.management"` to the
  `modules(...)` list so the report can run the `vmNativeMemory` diagnostic command in-process:
  `ManagementFactory.getPlatformMBeanServer().invoke(ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory", arrayOf<Any>(arrayOf("summary")), arrayOf("[Ljava.lang.String;"))`
  wrapped in `runCatching`; if it fails, write the reason in the report instead.
- Write the report to a new folder `memory-reports` under the app's data directory (find how the app resolves its data dir: `grep -rn "user.home\|appData\|dataDir" desktopApp/src/main`; copy that),
  file name `memory-<yyyyMMdd-HHmmss>-<label>.txt`, and return the path.
- Settings UI (plain, reuse existing row/toggle composables): a switch "Collect memory reports (diagnostic)" persisted with `DesktopPersistence` (default off), a button
  "Write memory report now" (label `manual`) showing the saved path, and when the switch is on an automatic report 60 seconds after the window is hidden to the tray
  (label `tray-60s`, once per hide) and one 90 seconds after start (label `startup-90s`). Run collection on `Dispatchers.IO`.
- Acceptance: the pure helper `ProbeMemoryMath.bytesToMb` gets a tiny test, and so does any formatting helper you add; nothing in the report path can throw to the UI (all `runCatching`).

## Order and what the owner does
M1 -> M2 -> M3, one account each, passing `Muses.zip` along. After M2, run the Actions build and look at a song's background (expect: one colour family,
smooth dissolve, no purple flash). After M3, install, switch the diagnostic on, play music, leave the window open ~2 minutes, close it to the tray for ~2 minutes,
reopen it, press "Write memory report now". Send the `memory-reports` files back for memory fixes (cycle M4).
