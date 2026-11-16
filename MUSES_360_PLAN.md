# Muses cycle 5: 360 / tablet laptop support, touch, window stability, brighter album colours

Plain Muses player only. The owner's laptop is an HP ENVY x360: it folds into tent/tablet modes and rotates between landscape and portrait.
Read only: this file and the last 3 sections of `HANDOFF.md`. **Use the zip the owner attached** (it may contain an earlier, partly working attempt at some
of this by another account: read what is there, keep what works, fix what does not, do not start over blindly).

## Conditions
Free-tier Claude accounts, Sonnet 5.5 medium, fresh chat each, limit resets about every 5 hours. Four accounts work AT THE SAME TIME on the same base zip: account A = T1, B = T2, C = T3, D = T5 and then T4 if its limit allows (otherwise T4 runs later in any free account); the owner sends all four results back to the planner, who merges them. So: **stay inside your own files** (ownership below) and mark every edit to a shared
file with `// 360 <part> hook` so the merge is mechanical. Nobody can compile or see the UI: the owner's GitHub Actions run compiles, the owner tests on the laptop.
The owner cannot answer questions: decide, write the decision in HANDOFF.md, continue. Never open `DesktopApp.kt` whole (8,400+ lines): `grep -n` then `sed -n 'A,Bp'`.
Short replies. If the limit is near: stop adding code, write the HANDOFF section with a **Deferred** list, output the zip.

## Hard rules
1. No new dependencies or version changes. Only APIs already used in the repo or confirmed in a source; copy exact imports. Never guess an API.
2. Animate only `graphicsLayer` transforms/alpha. Respect `DesktopAppearanceSettings.reduceAnimation` and `reduceDynamicBlur`.
3. Do not change playback, audio, lyrics, library or account logic. Do not delete settings/features/strings. Existing persistence keys stay.
4. Must still compile on Linux (Windows-only code behind `DesktopPlatform.isWindows`). Do not edit `sharedUi`, `shared`, `app`.
5. Compile-review every file: imports, nullability, `@Composable` context, duplicate names (grep first), no `private` type in an `internal` signature, `Modifier.align` only
   in Box scope, `weight` only in Row/Column. Pure logic gets unit tests (no test may need a window, a screen or a GPU).
6. HANDOFF: append a section `## 360 <part>` (never overwrite): Did / Files / Decisions / Verified (say plainly NOT compiled) / Deferred / Exact next step.
   Output the full project as `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`).

## Facts about the code (already verified, do not re-investigate from scratch)
- `Main.kt`: `Window(undecorated = true on Windows, transparent = true when Mica/Acrylic is possible, resizable = true)`, opening state 1220x780 dp,
  `composeWindow.minimumSize = 900 x 600 dp` (`MIN_WINDOW_WIDTH_DP`, `MIN_WINDOW_HEIGHT_DP`) converted with the window's scale at creation only. The window
  is NOT re-measured when the display scale or orientation changes. There is no uncaught-exception handling around the window.
- `DesktopWindowsFrame.kt` + `desktopApp/native/window/window_jni.cpp` (JNI DLL `bitchord_window`): the Win32 window keeps WS_THICKFRAME, `WM_NCCALCSIZE` gives the
  whole surface to Compose (when maximized: the monitor work area), `WM_NCHITTEST` turns the outer `resize_border` (frame + padded border, DPI-scaled) of every
  edge into resize handles whenever the window is not maximized. No handling of `WM_DPICHANGED`, `WM_DISPLAYCHANGE`, rotation or `WM_GETMINMAXINFO`.
- A transparent (layered) window lets mouse clicks fall through wherever the window's pixel alpha is 0.
- Responsive layout today: `compact = maxWidth < 980.dp` in a few places (`DesktopApp.kt` ~4944 and the `compact` parameters); ~35 fixed `.width(<=3-4 digit>.dp)` / `widthIn(min = ...)`
  uses across `desktopApp` (grep them).
- The app log is `desktop.log` under `$XDG_STATE_HOME/bitchord/` or `~/.local/state/bitchord/` (on Windows: `C:\Users\<name>\.local\state\bitchord\desktop.log`), written by `DesktopTrackLog`.

- **The full-screen player and lyrics are the phone's, from `sharedUi` (shared with Android)**: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
  has `internal val PLAYER_MAX_WIDTH = 560.dp` (line ~296), used at ~2496 and ~3478 (portrait artwork/controls column and the lyrics column) and in `LandscapePlayer.kt` ~375; the player counts as
  narrow when `windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2` (~480). `PlayerHost` (`PlayerHost.kt`, `PlayerPlatform.host`) is the interface the desktop (`DesktopPlayerHost`) and Android implement.
  So in a portrait window wider than ~600 dp the player is a 560 dp phone column with empty bands on both sides.

## Symptoms the owner saw (HP ENVY x360, Windows 11)
1. Rotating to portrait: scaling breaks, then the UI window disappears or stops; the music keeps playing; the only way back is killing and re-opening the app in Task Manager.
2. Moving the mouse to the extreme right edge or bottom edge of the screen and clicking activates the app BEHIND Muses (Brave was behind it) instead of Muses.
3. In portrait and in tent/tablet modes there are multiple layout and scaling problems, and touch does not work well (small targets, no drag scrolling, hover-only things).
4. Album colours (after the last colour update) look darker and duller than before.
5. Home in a portrait window (screenshot 1): works, but looks cramped and "shabby" for touch: tiny icons in the top bar, small tap targets, the translucent pill lets the page text show through it
   and sits on top of the last row, columns of Recents cut off at the right edge with no cue.
6. Full-screen player in portrait (screenshots 2 and 4): artwork only ~560 px wide with large empty bands on both sides and a big empty gap between the title block and the lyric line / controls; the bottom
   row (lyrics, output, queue, "System default") looks cluttered.
7. Lyrics in portrait (screenshot 3): reads like one dense paragraph in a narrow centre column with lots of empty space either side.
8. Dragging the window to the top edge of the screen (Windows snap-to-maximize) only grows it to about two thirds of the screen height; the owner has to drag it the rest of the way down by hand.

## Parts (all four at the same time; file ownership in brackets)

### T1: Window stability, orientation and the edge-click bug (owns `Main.kt`, `DesktopWindow*.kt`, `desktopApp/native/window/*`, new `DesktopDisplayWatch.kt`, tests)
1. **Crash/disappearing UI.** Find the cause (hypotheses in order: the 900 dp minimum width is wider than a portrait screen, so AWT forces the window wider than the display and Compose lays out in
   an impossible size; an exception thrown in composition/layout at a narrow or extreme size kills the Compose event loop while the process lives on through the tray and audio; the Skia/Direct3D
   surface is lost on rotation or DPI change; the transparent window keeps stale size after the change). Add logging first (`DesktopTrackLog`): window size, display bounds, scale, orientation on
   start and on every change, plus any uncaught exception on the UI thread (a `Thread.setDefaultUncaughtExceptionHandler` that logs, and for the UI thread recreates the window instead of dying).
   Add a **crash guard**: if the window's composition fails, log it and rebuild the window (key the `Window` on a restart counter) without touching the player/tray. Never exit the audio.
2. **Orientation-aware minimum size.** Replace the fixed 900x600 dp minimum with a function of the current display (never larger than the display's usable area; a floor of about 360 dp wide, 480 dp high),
   pure and unit tested. Recompute and re-apply it whenever the window's graphics configuration, scale or the display bounds change (use an AWT `ComponentListener`/`HierarchyBoundsListener`
   or `GraphicsConfiguration` polling already available on the window; no new dependency). When the display rotates, fit the window to the new usable area (maximized stays maximized).
3. **Edge-click bug.** Make the app reliably receive clicks up to the last pixel of the screen. Check, log and fix, in this order: (a) in maximized state the client rect is the monitor work area, so with
   an auto-hiding taskbar or on rotation the window may stop 1 to 2 px short of the screen edge; (b) `WM_NCHITTEST` resize borders reach into the visible UI at the screen edge (a near-full-screen window
   has resize handles over the right/bottom 8 to 12 px, so a click starts a resize instead of clicking the UI, and `HTTRANSPARENT`-like behaviour can reach the window behind); (c) layered-window
   alpha-0 pixels along the right/bottom edge click through: make sure the root surface paints at least a nearly invisible (alpha about 1/255) fill across the whole window, edge to edge;
   (d) stale window rect after a display change. Prefer: no resize handles when the window fills its monitor, and a full-surface hit-catching fill. Native changes go in `window_jni.cpp`
   (read the whole file first, 855 lines; keep the existing exported functions; the build already compiles it with CMake in the workflow).
4. **Posture/orientation events to the UI.** Provide a small pure state holder `DesktopDisplayInfo` (width dp, height dp, `isPortrait`, `isTouchLikely`) exposed as a `StateFlow` that updates on those events, for T2/T3 to consume (T2 and T3 will read only this class; define it first and keep its API exactly: `DesktopDisplayWatch.info: StateFlow<DesktopDisplayInfo>`,
   `data class DesktopDisplayInfo(val widthDp: Float, val heightDp: Float, val isPortrait: Boolean, val isTouchLikely: Boolean)`). `isTouchLikely` = Windows reports a touch digitizer
   (`GetSystemMetrics(SM_DIGITIZER)` through the JNA already in the repo, with the same safe wrapping as other Windows calls) or the last input event was touch/pen; false off Windows.
5. **Snap-to-top maximize (symptom 8).** Dragging to the top edge must maximize to the full work area of the monitor the window is on, in any orientation. Check and fix: (a) `composeWindow.maximizedBounds` in
   `Main.kt` is computed from the display once, when the maximize button is used, and then stays stale after a rotation or a move to another monitor (never set it when the native frame is installed,
   or refresh it on every display change); (b) there is no `WM_GETMINMAXINFO` handling: add it in `window_jni.cpp` so `ptMaxPosition/ptMaxSize` are the monitor work area (`MonitorFromWindow` + `rcWork`),
   which also covers rotated monitors; (c) `DesktopWindowMode.adopt` / `LaunchedEffect(placement)` may fight the native maximize and restore the old size: make the native maximize authoritative, and make Compose follow it
   without resetting the size. Log `WM_SIZE` (type, rect) and the placement transitions so the owner's `desktop.log` shows what happened.
Acceptance: rotating never leaves the app without a window; the minimum size never exceeds the display; clicks at the far right/bottom pixel hit Muses; drag-to-top maximizes to the full work area; `desktop.log` records every display change.

### T2: Responsive layout for portrait, narrow and tent modes (owns a NEW file `DesktopAdaptive.kt` and the layout edits in `DesktopApp.kt`, `DesktopHomeSections.kt` and the screens under `ui/screens` ONLY if they are desktop-owned; do not edit `sharedUi`)
1. Define `DesktopWindowClass { COMPACT (<600 dp wide), MEDIUM (600 to 979), EXPANDED (>=980) }` and `DesktopOrientation`, pure, tested; expose `LocalDesktopWindowClass` and use
   `DesktopDisplayWatch.info` from T1 only through the agreed data class (until T1 is merged, read the window size from `BoxWithConstraints` in `DesktopFrame` so your part works alone; wire
   `isPortrait` through a `LocalDesktopAdaptive` CompositionLocal with a default so the merge is one line).
2. Replace the single `compact = maxWidth < 980.dp` with the window class; audit EVERY fixed width (grep `\.width(` with 3 to 4 digit dp, `widthIn(min =`, `sizeIn(minWidth`, fixed `Row` of many items)
   and make each shrink gracefully (min of available width), so no layout crashes or clips at 360 to 1280 dp wide and portrait aspect ratios. Constraints must never go negative.
3. Shell in COMPACT/portrait: top navigation collapses to icons (or a menu), search becomes an icon that opens the search page, Home grids use fewer columns, shelves stay horizontal,
   the floating pill uses the window width (full width minus insets, height not above 72 dp), side panels (queue/lyrics) become full-screen overlays instead of a column beside the page,
   settings category rail becomes the chip row, dialogs and popovers fit the window.
4. Full-screen player: it already scales; verify no fixed sizes break it in portrait (read-only review; fix only desktop-owned layout files).
5. **Touch-ready, tidy Home in COMPACT/portrait (symptom 5).** Bigger top bar targets (at least 44 dp) with proper spacing and the account avatar kept; Recents shown as 2 columns (or one column of larger rows) that
   fit the window, with a visible peek of the next column only where it scrolls; shelf cards a little larger with consistent gutters (16 dp); the page's bottom content padding must clear the pill so the last row is never
   under it; the pill in COMPACT/portrait gets a higher minimum tint (about 0.55) so page text never shows through it, and a safe distance from the bottom edge. Keep all existing sections and data.
6. Tests for the pure classification functions and for any pure sizing helper you add.
Acceptance: at 360x640 dp, 540x960 dp, 720x1280 dp, 1280x800 dp nothing is cut off, overlaps, or crashes; every old feature is still reachable.

### T3: Touch support (owns new files `DesktopTouch.kt`, `DesktopTouchSettings.kt`, tests; hooks in `DesktopApp.kt` marked `// 360 T3 hook`)
Windows delivers touch to AWT as mouse events, so Compose sees a mouse. Make touch good without breaking mouse use:
1. **Touch mode**: setting `Auto / Touch / Mouse` (persisted with `DesktopPersistence`, default Auto; Auto = `isTouchLikely` from T1's `DesktopDisplayInfo` once merged, until then just the setting).
   In touch mode: minimum 48 dp hit targets for icon buttons, rows, chips, sliders (apply through shared button/row composables in `DesktopApp.kt` with a `LocalDesktopTouchMode`; do not redesign them).
2. **Drag to scroll**: a modifier `Modifier.desktopTouchScroll(state)` for lazy lists, grids and scroll columns, so a finger drag scrolls the page (with the platform's fling); mouse wheel keeps working; mouse drags still select
   text. Apply it to the main page scroller, Home shelves, the queue, search results and settings lists.
3. **No hover-only controls in touch mode**: anything revealed on hover (card actions, pill knob, tooltips, the Pill/Studio hover states) must also be reachable by tap or a visible control.
4. **Long press = right click**: where the app has context menus (right click), long press (about 500 ms) opens the same menu.
5. **Sliders and scrub**: volume, seek (the pill's progress bar and the full-screen player) respond to touch drags with a larger thumb in touch mode.
6. **Text input**: when a text field gains focus in touch mode on Windows, show the touch keyboard (`TabTip.exe` via `ProcessBuilder`, wrapped, once per focus, ignore failures); hide nothing.
7. Pure logic tests: hit-target size function, long-press timing helper, drag-to-scroll delta maths.
Acceptance: with only a finger the owner can scroll every page, open menus, scrub, type in search, and press every control; mouse use is unchanged.

### T5: Full-screen player and lyrics in portrait / tablet shapes (owns `sharedUi/.../ui/player/NowPlayingScreen.kt`, `PlayerLyrics.kt`, `PlayerHost.kt`, `DesktopPlayerHost`'s new member only; this is the ONE part allowed to edit `sharedUi`)
The player is shared with Android, so **Android behaviour must stay exactly as it is**: add everything as a defaulted member of `PlayerHost` (for example `val widePortraitLayout: Boolean get() = false` and
`val maxContentWidth: Dp? get() = null`), implemented only by `DesktopPlayerHost`; Android keeps the defaults and compiles unchanged. Read `PlayerHost.kt`, then the three `PLAYER_MAX_WIDTH` uses.
1. **Artwork and layout in portrait windows wider than a phone (symptom 6).** When the desktop flag is on: let the column grow with the window (artwork width = window width minus a 24 dp gutter, capped so the
   artwork never exceeds about 62 percent of the window height and the controls still fit), place title/artist, the lyric line, scrubber, transport, volume and the bottom row from the full height with even
   spacing so there is no big hole in the middle, and keep a sensible maximum (for example 900 dp) so a huge portrait monitor still looks designed.
2. **Bottom row (lyrics, output device, queue, "System default")**: group and space it cleanly with at least 44 dp targets; no clutter, nothing removed.
3. **Lyrics (symptom 7).** Widen the lyrics column to the same gutter, scale the line type with the width (larger for wider windows within sane limits), give more line spacing and a bigger gap between lines so it does not read as a paragraph,
   keep the existing active-line highlight, blur and sync behaviour. The header (small artwork, title, artist, heart, more) uses the same gutter.
4. Pure helpers (artwork size, column width, type scale from window width and height) with unit tests in the `sharedUi` test source set already used by the project (or in `desktopApp/src/test` if the helper is placed in desktopApp).
5. The landscape player (`LandscapePlayer.kt`) and Android layouts stay as they are.
Acceptance: at 540x960, 720x1280 and 900x1400 dp the portrait player and lyrics fill the width with even spacing; at phone sizes and on Android nothing changes.

### T4: Album colours brighter and richer (small; runs after T5 in account D, or later in any free account; owns `DesktopAlbumPalette.kt`, `DesktopFluidity.kt`, `DesktopAlbumPaletteTest.kt`, new `DesktopDither.kt`)
The last update made backgrounds look darker and duller than before. Causes in the code (verify, then fix): glows are now drawn with normal blending instead of additive `Screen`; role alphas are
0.85/0.70/0.60/0.45 of an already lower base; chromatic glows may be as dark as OKLab L 0.30; neutral covers cap `intensity` at 0.62; the base shade is L 0.18; a lightness-based scrim is added for bright covers;
the radial stops are softer. Fix, keeping everything that works (white, grey, black and skin tones must still appear; no purple/yellow chaos; clean dissolves; no default-palette flash):
1. Raise the visual weight without bringing back hue mixing: role alphas about 0.95/0.85/0.75/0.55, chroma boost 1.12 to about 1.25 (still gamut-clipped), chromatic lightness floor about 0.42, neutral `intensity` floor about
   0.80, base L about 0.22, and a large faint "wash" of the dominant colour (full-window radial or vertical gradient at about 16 to 24 percent alpha) under the glows so the page carries colour even between glows.
2. Keep light covers readable (the brightness scrim stays, but only starts above brightness 0.62 and never darkens more than about 0.35), and keep `DesktopAdvancedAppearance.paletteStrength` working (its default may be nudged to taste, but the slider range stays).
3. Add dither noise to kill banding on dark gradients: `DesktopDither.kt` with a cheap pre-generated noise tile drawn over the backdrop at about 2 percent alpha (`ImageBitmap`/`ShaderBrush` already used elsewhere in the repo; if no
   safe API is confirmable, skip it and say so in HANDOFF). No per-frame allocation.
4. Update `DesktopAlbumPaletteTest` for any changed expectation and add tests for the new constants' invariants (colours in range, brightness in 0..1, whites/blacks/skin still present).
Acceptance: a saturated cover looks clearly vivid, a dark cover is visibly tinted not black, white/grey/black/skin still show, text on every cover stays readable.

## What the owner does
1. Put `MUSES_360_PLAN.md` in the root of the LATEST source zip (the one with the other account's attempt), attach it to four fresh chats: A = T1, B = T2, C = T3, D = T5 (then T4 if D still has limit).
2. Collect the four returned zips and upload them in ONE message to the planner chat; the planner merges them and returns one zip.
3. After the build, test on the laptop: rotate, tent mode, tablet mode, touch scrolling, edge clicks, colours. If the UI still disappears, send `desktop.log` (path above).
