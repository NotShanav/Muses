# HANDOFF

## Account 1 — player background modes, palette cache, MusesMotion

### What was done
- **Player background setting** (Settings > Appearance, under the Fluidity toggles): two chips, "Album reactive" / "Acrylic glass", each with a one-line description. Persisted under key `player_background_mode`, default `ACRYLIC_GLASS`, so existing users see no change until they choose Album reactive.
- **Mode A, ALBUM_REACTIVE**: `DesktopFluidBackdrop(overMaterial = true, scrimStrength = 1.5f)` (palette crossfade 1400 ms, cover-change swell and drift untouched), plus one static glass tint (smoke 0.32, 0.40 solid when Reduce dynamic blur is on), a faint sheen (skipped when Reduce dynamic blur is on) and a top/bottom reading scrim. No blur, no new per-frame work.
- **Mode B, ACRYLIC_GLASS**: unchanged. Fluidity on renders the existing `DesktopFluidPlayerBackdrop`; Fluidity off, the provider returns null and the shared player draws its own mesh.
- **Crossfade**: `DesktopPlayerBackground(mode, artworkUrl, trackKey)` fades alpha via `graphicsLayer` over 220 ms (snap if Reduce animation); the outgoing layer is composed only during the fade. The provided override is one stable lambda that reads the mode itself, so a mode change does not alter the CompositionLocal value (except null <-> non-null, see below).
- **Palette LRU** (24 entries, keyed by artwork URL) in front of `extractFluidPalette`; extraction stays on `Dispatchers.IO`. Only successful extractions are cached.
- **MusesMotion.kt**: shared presets (below).

### Files
Added (desktopApp/src/main/kotlin/com/music/bitchord/desktop/): `DesktopPlayerBackground.kt`, `MusesMotion.kt`, `DesktopCountLru.kt`.
Added tests (desktopApp/src/test/kotlin/com/music/bitchord/desktop/): `DesktopCountLruTest.kt`, `DesktopPlayerBackgroundModeTest.kt`.
Changed: `DesktopFluidity.kt` (only `rememberFluidPalette` + the cache val), `DesktopApp.kt` (provider at ~2838, settings chips at ~5735).
Strings added via `DesktopStrings[key, default]` (English defaults, no catalogue edit): `player_background`, `player_background_subtitle`, `player_background_album`, `player_background_acrylic`, `player_background_album_subtitle`, `player_background_acrylic_subtitle`.

### MusesMotion (internal object, package com.music.bitchord.desktop)
Every preset is generic and takes `reduceMotion: Boolean = reduceNow()`; it returns `reduced()` when true.
- `MusesMotion.expand<T>()`: spring, damping 0.82, stiffness 300 (`EXPAND_DAMPING`, `EXPAND_STIFFNESS`)
- `MusesMotion.collapse<T>()`: spring, damping 0.9, stiffness 380 (`COLLAPSE_DAMPING`, `COLLAPSE_STIFFNESS`)
- `MusesMotion.press<T>()`: spring, damping 0.55, stiffness 520 (`PRESS_DAMPING`, `PRESS_STIFFNESS`)
- `MusesMotion.settle<T>()`: spring, damping 0.8, stiffness 350 (`SETTLE_DAMPING`, `SETTLE_STIFFNESS`)
- `MusesMotion.crossfade<T>()`: tween 280 ms FastOutSlowIn (`CROSSFADE_MS`); 120 ms when reduced
- `MusesMotion.reduced<T>()`: tween 120 ms (`REDUCED_MS`), no overshoot
- `MusesMotion.resolve(spec, reduceMotion)`: your own spec, or `reduced()` when reducing
- `MusesMotion.reduceNow()`: reads `DesktopAppearanceSettings.reduceAnimation.value`
Reads `.value` at call time, so in a composable collect `DesktopAppearanceSettings.reduceAnimation` and pass it in if the animation must restart when the setting flips.
Example: `animateFloatAsState(target, MusesMotion.press(reduce))`.
`fluidEnter` (0.62/380) and `fluidClickable` (0.5/520) were NOT moved onto presets: their values differ, so they are left as they were. `fluidPageLift` tween 420 also untouched.

### Decisions
- Mode switch Album reactive -> Acrylic glass with Fluidity off: the shared player only draws its mesh when the override is null, so this switch is instant, not a crossfade (a crossfade would need NowPlayingScreen changes, which were out of bounds). Settings is not visible while the full-screen player is up, so this is rarely seen. Switches where an override stays non-null do crossfade.
- Contrast reasoning (documented in `DesktopAlbumReactiveBackdrop`): on a pure white sleeve, the lower screen composites to ~0.13 sRGB, ~16:1 against white text; mid screen (lyrics) ~5.7:1. Both pass WCAG AA.

### Verified
**Manual compile review only. Gradle could not be run** (no network in this environment). Checked: every import present, APIs copied from existing calls in this repo (`Animatable`, `tween`, `spring(dampingRatio=, stiffness=)`, `graphicsLayer`, `drawBehind`, `Brush.verticalGradient/linearGradient` with stops, `FilterChip` + `desktopChipColors()`, `DesktopStrings[k, d]`, `entries`), no duplicate declarations, all new code is JVM-only in desktopApp so Android and expect/actual are unaffected. Not run: the two new unit tests (pure logic, no Skia) and the existing suite.

### Known limitations
- **Translucency over the DWM material is not visible in the player.** `NowPlayingScreen`'s root box paints `Color.Black` (shared code, ~line 2030) beneath the override, so Mode A's translucent base (0.46 alpha) shows over black, not over Mica/Acrylic. The `overMaterial = true` path is in place and will show the material as soon as that black is made conditional (for example by a `LocalPlayerBackdropOverride`-aware parameter); I did not touch the player's layout.
- During a crossfade two fluid backdrops run at once for 220 ms.
- Strings are English defaults only.

### Next account
1. Run the GitHub Actions build first and fix any compile error in the files listed above.
2. Use `MusesMotion.*` for new motion; do not hardcode springs.
3. Do not alter `LocalPlayerBackdropOverride` call shape; if you want the material to show through the player, make the black in `NowPlayingScreen` skip painting when the override is non-null and a material is active (verify the Android build).


## Account 2 — desktop navigation, home review, floating mini-player

### What was done
- Replaced the rendered desktop sidebar and compact bottom navigation with a 64 dp top strip at all desktop widths. Home, Explore, and Library are pill destinations; the selected pill glides with `MusesMotion.settle()` using a graphics-layer translation. Search uses the existing `DesktopSearchField`, query state, typeahead, and search callback. Clicking the field selects Search.
- Kept `DesktopTitleBar`, `DesktopTitleBarDragArea`, `DesktopWindowButtons`, and the existing DWM/chrome glass path. The window caption controls remain in the title bar; custom inline caption buttons remain in the drag-area strip when that setting is active.
- Added forward history alongside the existing visit stack. Back and forward restore the same `DesktopNavEntry` state (destination, opened collection/artist, selected mood, show-all shelf, replay, and settings page); an ordinary new visit clears the forward stack.
- Added History, Downloads, Local Music, Settings, account switching, account playlists, and local playlists to the profile menu. The Library page continues to display its Playlists shelf, so account playlists remain discoverable there too.
- Replaced the compact-only player and bottom transport strip with an all-width floating glass mini-player. `DesktopFrame` overlays it above the full-height scrolling page and provides 104 dp bottom scroll padding. It includes artwork, title, artist, progress, previous, play/pause, next, and a More menu for lyrics, queue, and output selection. The player uses the existing Haze-backed `desktopBarGlass`, whose reduced-dynamic-blur fallback is already solid. Entrance motion uses `MusesMotion.settle()` and respects reduce animation.
- Left the shared HomeScreen data and renderer unchanged. Its existing desktop call disables the hero lead layout; existing shelves already retain square art, horizontal rows, Quick picks, Listen again, mixes, and other feed sections. No account or playback logic was changed.

### Mini-player API for Account 3
`DesktopFloatingMiniPlayer` is an `internal` desktop composable in `desktopApp/.../DesktopApp.kt`:

```kotlin
internal fun DesktopFloatingMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    previousEnabled: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onExpandPlayer: () -> Unit,
    artworkModifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMoreLyrics: () -> Unit,
    onMoreQueue: () -> Unit,
    onMoreOutput: () -> Unit,
)
```

`onExpandPlayer` opens the existing full-screen player. `artworkModifier` is applied to the artwork inside `key(song.videoId)`, providing a stable hook for a future shared-element transition.

### Former sidebar and bottom controls mapping

| Former entry/control | New location |
| --- | --- |
| Home | Top navigation pill |
| Explore | Top navigation pill |
| Library | Top navigation pill; retains its Playlists shelf |
| Search | Top-bar Search field; existing query/typeahead route |
| History | Profile menu |
| Downloads | Profile menu |
| Local Music | Profile menu |
| Settings | Profile menu |
| Account playlists shelf | Library Playlists shelf and profile menu playlist entries |
| Local playlists shelf | Library and profile menu playlist entries |
| Account switcher | Profile menu > Switch account; existing `DesktopAccountUi` overlay |
| Previous / play-pause / next | Modern floating mini-player; Legacy top bar or compact mini-player |
| Seek progress | Floating mini-player progress line; full seek remains in full-screen player |
| Shuffle / repeat | Modern mini-player and full-screen player; Legacy top bar and full-screen player |
| Volume | Full-screen player volume controls |
| Queue / lyrics | Mini-player More menu and full-screen player |
| Audio output | Mini-player More menu and full-screen player output control |
| Desktop title / drag / caption buttons | Existing title bar and top-strip drag area |

### Decisions and limitations
- `DesktopFloatingMiniPlayer` uses the Haze source already installed on the page, with `desktopBarGlass` for the one glass surface and the repository's solid fallback when dynamic blur is reduced.
- The top pill and home card hover animations use transform-only motion. Desktop passes its `MusesMotion.Press` spec into optional shared HomeScreen parameters; other callers retain the prior behavior.
- The profile dropdown can become tall when an account has many playlist entries; the Library page remains the primary route to the full playlist shelf.
- No player engine, account, library, lyrics-fetching, or background-mode code was altered.

### Verified
- **Gradle was attempted but did not reach compilation.** Both `./gradlew :desktopApp:compileKotlin :sharedUi:compileKotlinJvm` and the Windows wrapper with `--offline` stalled before emitting Gradle output. `java -version` also stalled; the available Java path resolves to the Windows Java 8 path shim. No successful compile or tests are claimed.
- **Manual source review:** compared the edited `DesktopApp.kt` against the input archive version; checked imports and symbol definitions for `DesktopFloatingMiniPlayer`, `DesktopToolbarButton`, the existing desktop destination enum, `DesktopPlaylist.title`, `DesktopSearchField`, `DesktopFrame`, the visit restoration logic, Compose `key`, and existing animation/glass APIs. At the Account 2 stage, the shared HomeScreen and Android source files were not edited. The later Modern / Legacy follow-up adds optional parameters only to the JVM desktop HomeScreen implementation; Android and other platform source sets remain untouched, with no new expect/actual declarations.
- Not verified: Linux desktop compile, Android compile, UI behavior at narrow widths, and automated tests.

### Next account
1. Compile Windows desktop and JVM shared UI in GitHub Actions and fix any issues reported there.
2. If implementing the player expansion transition, use `onExpandPlayer` and `artworkModifier` above; keep the current full-screen layout unchanged unless shared-element continuity requires a carefully reviewed adjustment.
3. Preserve this mapping table and keep all formerly reachable controls accessible.


### Account 2 build-log follow-up
The GitHub Actions log supplied after packaging identified four source issues: `positionMs` and `durationMs` had been declared on `DesktopTopBar` instead of `DesktopFloatingMiniPlayer`; a local playlist menu item used nonexistent `name` instead of `title`; and the top-strip divider used `Modifier.align` outside a `BoxScope`. These were corrected by moving the timing parameters to the mini-player API, using `DesktopPlaylist.title`, and placing the row and divider inside a `Box`. The corrected project is repackaged. It has not yet been rerun through GitHub Actions, so successful compilation is still unverified.

The next supplied workflow run confirmed the playlist name and mini-player timing-parameter corrections and reported two additional top-bar structure errors. The bottom divider now lives inside the surrounding `Box` scope, and the extra closing brace has been removed. The archive has been refreshed again; another Actions run is needed to confirm the fix.


## Modern / Legacy interface follow-up

### Implemented
- Added one persisted Modern interface switch through `DesktopInterfaceMode` (`MODERN` / `LEGACY`) and `DesktopInterfaceSettings`, using preference key `desktop_interface_mode`. New installs default to Modern; switching it off selects Legacy and restores the classic desktop shell.
- Modern keeps Home / Explore / Library at the upper left with a spring-sliding selection orb. Search is an icon between Back and Forward; it opens the existing Search field and reuses the existing query, search, and typeahead state. The profile menu provides account switching, History, and Settings.
- Modern refines the home feed with 24 dp side gutters, 30 dp shelf spacing, See all actions, compact Quick picks cards, and 1.03 hover lift. It uses the adjustable floating player dock over the full-height page with artwork, track details, elapsed/total time, progress, previous, play/pause, next, shuffle, repeat, and a More menu for lyrics, queue, and output. Downloads, local music, account playlists, and the existing library detail remain reachable from Library device items and shelves.
- The Appearance group now starts with the Modern / Legacy choice, followed by the existing blur, Fluidity, and player-background settings. Advanced > Advanced interface customization adds persistent dock alignment, width, bottom spacing, corner curve, glass contrast, and rim highlight controls. Interface mode is stored separately from `player_background_mode` and the global Fluidity setting.
- Modern search expansion, page entrance, selection-orb movement, mini-player entrance, and button press feedback use `MusesMotion` and respect Reduce animation. Home shelves use 24 dp gutters, 30 dp section spacing, See all links, a compact Quick picks card row, and 1.03 hover lift. The full-screen player and its existing album-reactive background implementation were not changed.

### Known limits and verification
- A rare home-background color flicker was reported by the user but was not reproduced or changed; it is separate from the shell layout work.
- Local Windows/JDK 21 verification passed: `:sharedUi:compileKotlinJvm`, `:desktopApp:compileKotlin`, `:shared:jvmTest`, and `:desktopApp:test`. The first test attempt inside the sandbox failed because Windows registry preferences and the JavaCPP native cache were inaccessible there; the same workflow test tasks passed when rerun outside that restriction.
- Local MSI/distributable packaging was not run because this machine lacks the workflow's CMake/MSVC/WiX setup. GitHub Actions installs those tools and must still confirm the packaged artifacts. Linux and Android builds and visual runtime QA are also unverified. `.github/workflows/windows-desktop-build.yml` and `check.yml` were compared against the input archive and are unchanged.
- Modern mode is the default for installs without a saved interface preference. Existing saved Modern / Legacy preferences are preserved.


### Final package status
- Refreshed Muses-modern-interface.zip from the complete current source tree, including hidden .github workflow files. The package retains the Muses/ top-level directory.
- Source compilation and the workflow test tasks pass locally under JDK 21. Windows MSI/distributable packaging and the GitHub post-package checks are still pending; run the Windows desktop workflow on this package before treating it as a release build.


### Latest CI compile fix
The latest Windows Actions log identified `HomeScreen.kt` importing `composed` from `androidx.compose.runtime`. Compose defines the modifier extension at `androidx.compose.ui.composed`; the import is corrected. The unresolved `composed` error and the three composable-context errors at the hover helper were all consequences of that incorrect import. This correction has not yet been confirmed by a new CI run.

### Follow-up compile diagnostics and fixes
- GitHub's shared UI compile error came from importing `composed` from `androidx.compose.runtime`; changed it to `androidx.compose.ui.composed`. The `HomeScreen.kt` JVM source now compiles.
- GitHub then reported that the `internal` mini-player signature exposed private `DesktopRepeatMode`; changed the enum visibility to `internal`. `:desktopApp:compileKotlin` now passes.
- The matching Windows workflow test tasks passed locally under JDK 21. The GitHub workflow still needs to run its Windows MSI build and package verification steps.


## Desktop advanced customisation follow-up

### Implemented in this pass
- Added `DesktopAdvancedAppearance.kt`, a desktop-owned, schema-versioned JSON settings model with per-field range checks and a searchable setting metadata registry. Existing settings remain in their current desktop preference keys and the advanced screen reads/writes those same stores.
- Added **Settings → Appearance → Advanced customisation** with search, modified-only filtering, a live preview card, per-category reset actions, a global Default preset, five additional built-in presets, named user presets with save/rename/duplicate/delete, and JSON import/export with a 64 KB size limit and a confirmation summary.
- New desktop-only values: typography scale (85–130%, with readable text floors), bundled Muses/System/Serif/Monospace font choice, shelf spacing, Home lead artwork, artwork-palette strength, true-black desktop page background, contrast-checked fixed accent, and mini-player height, artwork visibility/shape, metadata visibility, progress style, glass/solid/accent material, shuffle/repeat shortcut visibility (hidden shortcuts remain in More), and tap-to-expand.
- Existing Modern/Legacy, Fluidity, Reduce dynamic blur, Reduce animations, Player background, floating-player alignment/width/inset/shape/contrast/rim, title-bar, and Windows material settings are reused. Mini-player artwork, player engine, full-screen player and Android source were not modified.
- Updated README and the technical guide to distinguish the desktop customisation options and remaining limits.

### Desktop-only boundary
- Changed files in this pass are `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAdvancedAppearance.kt`, `DesktopApp.kt`, `DesktopFluidity.kt`, `DesktopReplayUi.kt`, plus `README.md`, this handoff, and `docs/APP_OVERVIEW_AND_TECHNICAL_GUIDE.md`.
- No Android source, Android Gradle configuration, shared UI source, playback/source/account code, or workflows were edited.
- Not implemented from the larger concept spec: automatic two-crash safe-mode recovery/launch gesture, custom fonts or images, arbitrary navigation reorder/alignment, full-player layout controls, lyrics presentation controls, advanced colour-role editor, and arbitrary mini-player button ordering. These would require more surface-specific work; no unsupported controls are shown as if they were active.

### Verification
- The Windows Gradle compile was attempted with `gradlew.bat :sharedUi:compileKotlinJvm :desktopApp:compileKotlin` but stalled before Gradle emitted output. The only discoverable Java runtime is the Oracle Java 8 path shim/JRE; a Java 21 JDK is not available in this environment. No compile or test pass is claimed for this pass.
- Manual review covered serializer/model visibility, Compose call contexts, desktop-only references, existing preference setters, mini-player control reachability, default values, import size/schema checks, and the set of edited paths. A Windows/JDK 21 build is still required before packaging. The shared JVM UI compile is a desktop target check; Android was intentionally not built or changed.


### Advanced dock customization follow-up
- Increased the floating player’s default contrast with an adjustable smoked-glass veil and added a configurable light rim, so album colors remain visible while the title, progress, and controls stay readable.
- Added persistent dock placement (left/center/right), maximum width, bottom spacing, corner curve, veil strength, and rim-highlight controls under Settings > Advanced > Advanced interface customization.
- The current glass renderer supports smoked tint, rim highlight, acrylic/Haze blur, and corner shaping. It does not implement physical optical refraction; the panel states this clearly. The full-screen player and its background were not changed.
- Recompiled `:sharedUi:compileKotlinJvm` and `:desktopApp:compileKotlin` and reran `:shared:jvmTest` and `:desktopApp:test` with JDK 21; the Gradle build completed successfully. The final archive must include `DesktopMiniPlayerAppearance.kt` and the updated `DesktopApp.kt`.


## Addendum Phase 10 — Desktop Home Section Manager

### Implemented
- Added a desktop-only Home section registry and post-fetch filter/order pipeline in `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHomeSections.kt`. Discovery runs only when the Home feed reaches its renderer; toggling visibility does not refetch or mutate provider data.
- Added normalized title + item-kind IDs, exact aliases for known Home shelves, and pattern IDs for “Because you listened to…”, “More from…”, “For fans of…”, and Daily Mix titles. The current `HomeShelf` model carries title/items/subtitle/more-link fields only; the parser does not preserve renderer type or a stable shelf identifier. Thus provider IDs cannot be made fully language-independent without changing shared parsing (intentionally left untouched for the desktop-only boundary).
- Added persistent built-in and Seen shelves (200-entry LRU cap), new-shelf auto-visibility preference, show/hide, pin, ordering, bulk hide/show-only-library, clear-seen, unhide-all, and reset actions. Preferences are stored in the existing `DesktopAppearanceConfig`; schema was advanced from 1 to 2 and the old `desktop_appearance_config_v1` value is read and copied forward to `desktop_appearance_config_v2` without deleting the legacy key.
- Added the Home section manager page at Settings → Appearance → Advanced customisation and a Home top-bar shortcut. Hiding the final visible shelf routes to a friendly Customize Home empty state. Hiding a row offers an Undo snackbar.
- Added focused desktop unit coverage in `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHomeSectionsTest.kt` for ID normalization/patterns, type differentiation, filtering/order, new shelf policy, the seen cap, bulk actions, and reset.

### Deliberate Phase 10 scope limits
- `sharedUi` and shared parser/model files remain unchanged per the desktop-only requirement. As a result, per-header inline hide/move context menus, Explore/Library managers, layout overrides, renaming, and item limits are not part of this phase. Home customization is reachable from Settings and the Home top bar. Unknown provider titles retain normalized per-title IDs; provider changes can therefore create a new Seen entry.
- Phases 11–16 were not part of the original Phase 10 implementation. The later desktop customisation follow-up below adds a Customise hub and a functional Pill Studio; the remaining limitations are listed there explicitly.

### Verification
- Added unit tests but did not get an execution result. `gradlew.bat :desktopApp:compileKotlin :desktopApp:test --no-daemon --console=plain` produced no output after roughly 90 seconds and was interrupted; compile and tests are unverified in this environment. Manual review must still be supplemented by Windows and Linux desktop builds.
- No Android source, shared UI, shared parser/model, playback, account, network, workflow, or player-background files were changed in this phase.


## Desktop Pill Studio and dock visual follow-up

### Implemented
- Added `DesktopCustomiseHubPage` as a desktop entry point for Home shelves, the floating player, and existing appearance settings. The dock exposes the Studio from its More menu and a hover edit button; Settings also links to both the hub and Studio.
- Added `DesktopPillStudioPage` with a live preview, draft-only edits, Apply/Cancel, bounded Undo/Redo, reset, six starter presets, and up to eight named user presets stored in the desktop appearance config. Applying a preset or setting does not touch playback state.
- Added a validated, serializable `DesktopPillConfig` (appearance schema v3) and migration from the existing desktop dock preferences. Studio controls cover left/center/right zones and ordering, dock width/position/inset/shape/radius/height/material/contrast/rim, artwork position/size/shape/spin speed/direction/pause behavior, track artwork crossfade, shuffle/repeat visibility, and body click action. Reduce animations disables art spin and opts the track-art transition out.
- The running dock consumes the saved zone order and styling. A light edit affordance appears on hover; the More menu retains lyrics, queue, audio output, hidden shuffle/repeat, and direct Studio access. The existing glass rim now renders once, removing the nested outline that made the dock read like a box inside a box.
- Removed the explicit one-pixel dark divider at the edge of the desktop lyrics/queue side panel, allowing its glass/content edge to meet the page without the black seam.

### Limits
- The Hub is a concise editor launcher, not an arbitrary canvas. Studio exposes three fixed zones and reorder buttons rather than drag-and-drop or freeform pixel positioning. It does not implement true optical refraction, arbitrary gradients, custom image assets, or custom control/icon import. Track artwork supports Crossfade/None; the other animation enum names are reserved and are not offered in the UI. Presets are stored with a bounded JSON payload in the desktop appearance config.
- `DesktopPillStudioPage` is the editor entry composable; the apply hook is `(DesktopPillConfig) -> Unit`. The running dock API remains `DesktopFloatingMiniPlayer(...)`; its `onOpenPillStudio: () -> Unit` hook opens the editor, and `artworkModifier` remains available for the stable artwork host.

### Verification status
- Source review confirmed the Settings routes, dock More/edit entry points, config migration/save path, playback control callbacks, and the former side-panel divider location. No Android source or workflow file was edited.
- A Gradle build/test could not be completed in this environment: the only discovered Java runtime resolves through the Oracle Java 8 path shim and the wrapper previously stalled before Gradle emitted task output. This pass therefore does not claim compilation or runtime visual verification. Run the Windows desktop workflow with JDK 21 to validate the package; Linux desktop compilation is also unverified.

### Windows Actions compile-error follow-up
- The run shown by the user reported composable callback type mismatches where `forEach` expected ordinary `(String) -> Unit` callbacks, plus two unresolved `overlays` references inside `DesktopSettingsScreen`.
- Replaced the three composable `forEach` calls in both the running dock and Studio preview with direct `for` loops, and passed `onOpenCustomiseHub` / `onOpenPillStudio` callbacks into `DesktopSettingsScreen` from its owning scope.
- Checked the sole `DesktopSettingsScreen` call and both new callback signatures. The local Gradle compile was retried but again emitted no output for 40 seconds and was interrupted; the CI errors shown in that screenshot are addressed in source, but a successful Windows workflow run is not yet confirmed.

## Upgrade cycle 3 setup (album colours + memory report)
- Added `MUSES_UPGRADE_PLAN.md` (the plan and the three parts M1, M2, M3) at the repo root.
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopProcessMemory.kt`: a small psapi probe (working set and commit charge), adapted from a reader that already compiled and ran in an earlier build. Used only by part M3. NOT compiled in this setup step.
- Next: run M1, then M2, then M3 as described in the plan.

## Upgrade cycle 3, part M1: album palette engine
### Did
- Moved the cover-pixel work out of `extractFluidPalette(bitmap)` into a pure `internal fun extractAlbumPalette(pixels: IntArray, width: Int, height: Int): FluidPalette?` (new `DesktopAlbumPalette.kt`). `extractFluidPalette(bitmap)` now only reads pixels and calls it.
- Selection is now three colours: hues ranked by weight, up to 3 with hue gap >= 34 degrees, a second/third under 8 percent of the chromatic weight is skipped. `colors` is still 4 long: [0] dominant, [1] second, [2] third, [3] lighter (+0.10 lightness), less saturated (x0.88) tint of the dominant.
- Missing second/third are tonal variants of the dominant (hue +8 / -8 degrees, lightness +-0.06 clamped to 0.46..0.60, saturation x0.92 / x0.84). `hueShifted` by 26/52 degrees is gone from the palette path.
- Grey / black-and-white covers get neutral greys (lightness 0.46..0.60 from average luminance, saturation <= 0.08, average hue only faintly, base lightness 0.06, intensity 0.45). No silver-blue.
- `FluidPalette` has `val shares: List<Float> = listOf(1f, 0f, 0f)` (weights of colors[0..2] as fractions of the chromatic total; missing colour = 0). The renderer does not use it yet.
- Unchanged: 24 hue bins, saturation weighting, near-black/white/grey skip, smoothing, glow lightness clamp 0.46..0.60, `base` formula, `intensity` formula (chromatic case).

### Files
- NEW `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAlbumPalette.kt`
- NEW `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAlbumPaletteTest.kt` (10 tests: solid red, red+blue 70/30, three colours, 3 percent accent, one colour, lightness clamp, grey, black-and-white, tiny image, determinism)
- EDIT `DesktopFluidity.kt` (`// UPGRADE M1`): `FluidPalette` now `internal` + `shares`; `hueGap`, `Hsl`, `toHsl`, `hslColor`, `hueShifted` now `internal` (visibility only); `extractFluidPalette` reduced to a wrapper; `HUE_BINS` moved to the new file (it was only used by the extraction). `rememberFluidPalette`, the renderer and `DesktopPlayerBackground.kt` are untouched (that is M2).

### Decisions (owner could not be asked)
- The dominant hue is always taken when any chromatic hue exists; the 8 percent rule applies to second and third only (a flat spread of many hues would otherwise yield no colour).
- A share is the weight of the chosen bin plus its two neighbours over the chromatic total. Chosen hues are >= 45 degrees apart (bin centres), so these groups never overlap.
- A cover with under 1.5 percent chromatic pixels counts as grey (noise / compression tint), so a near-B&W cover does not get a coloured glow from a few stray pixels.
- Grey shares are `[1, 0, 0]`; the dominant hue is still the bin centre, as before.

### Verified
- NOT compiled and NOT run. No Gradle build or tests were executed here. Review was by reading: imports, nullability, no duplicate top-level names (grepped), visibility of `internal` types in signatures, test assertions traced by hand against the formulas.
- Linux desktop compilation is unverified.

### Deferred
- M2 (renderer, two-layer cross-dissolve, no Screen blend, role alphas), M3 (memory report).

### Exact next step
- Run the Actions build (it also runs `DesktopAlbumPaletteTest`). If it compiles, start M2 in `DesktopFluidity.kt` only: `rememberFluidPalette` returning a nullable "not ready" state, `fluidHasPlayed`, the `Animatable` progress dissolve, and removing `BlendMode.Screen`.

## Upgrade cycle 3, part M2: backdrop renderer
### Did
- `rememberFluidPalette(url)` now returns `FluidPalette?` (null = not ready). Idle startup (no song ever selected) gets the default palette. A selected song with an uncached cover keeps the previous album palette on screen; the first song has none, so only the dark neutral base (`0xFF0B0D12`) is drawn and the glows fade in over 800 ms when the palette arrives. Top-level `@Volatile fluidHasPlayed` / `fluidLastAlbumPalette` mean the default never returns after a song, even if `url` later becomes null. The default also cannot show for a frame once a song is selected.
- Per-slot `animateColorAsState` and the base/intensity tweens are gone. Two-layer dissolve: `to` (on screen) and `from` (fading out) with one `Animatable<Float>` progress over 1400 ms (`snap` when `reduceAnimation`). Base(from) then base(to), glows of `from` at alpha x (1 - p) and of `to` at alpha x p; the `from` layer is skipped at p == 1. A retarget mid-dissolve sets `from = to` and restarts (tiny jump, as planned).
- Glows use normal blending (`BlendMode.Screen` and its import removed). Role alphas dominant 0.85, second 0.70, third 0.60, tint 0.45, capped at 0.95. Kept: `FluidGlows` motion constants, `swell` burst, 30 fps clock, pause when hidden / `reduceAnimation`, `subtle` (2 glows: dominant and second), scrims, translucent base for Acrylic/Mica, `paletteStrength`.
- Comments updated in `DesktopFluidity.kt` and `DesktopPlayerBackground.kt` (comment only). `DesktopFluidBackdrop` signature unchanged.

### Files
- EDIT `DesktopFluidity.kt` (`// UPGRADE M2`): palette state, backdrop body, new private `drawFluidGlow`, constants `DISSOLVE_MS`, `FIRST_GLOW_FADE_MS`, `FluidNeutralBase`, `FluidRoleAlpha`; imports: removed `animateColorAsState`, `BlendMode`, added `drawscope.DrawScope` (same import already used in sharedUi).
- EDIT `DesktopPlayerBackground.kt`: one comment. `DesktopApp.kt` not touched.

### Decisions (owner could not be asked)
- Role alpha = role value x (this glow's `alpha` / first glow's `alpha`), so the existing ratios between glow slots survive; then x `intensity` x `paletteStrength` x swell, capped at 0.95 before the layer alpha.
- `paletteStrength` is now a short 400 ms tween (snap with `reduceAnimation`); each layer uses its own palette `intensity`, because the dissolve replaces the old intensity tween.
- Over a translucent material the outgoing base fades as (1 - p) instead of staying at full base alpha, so the settled and mid-dissolve base never stack darker than 0.46. With an opaque base it stays full under the incoming one.
- If the target becomes null (cover loading with no earlier palette) the view snaps to the neutral base at once instead of dissolving the old palette out.
- Tracks whose cover fails to decode keep the previous palette (or the base only on the first song).

### Verified
- NOT compiled and NOT run (no Gradle here). Reviewed by reading: braces balanced, imports, nullability of `to`/`from`/`target`, `DrawScope` extension called only inside `Canvas`, `@Volatile` top-level vars (JVM). No tests added (none required).

### Deferred
- M3 (memory report). A visual check of a real track change is for the owner after the Actions build.

### Exact next step
- Run the Actions build and look at a song: expect one colour family, a smooth dissolve, no purple flash, first song fading up from dark. Then start M3.

## Upgrade cycle 3, part M3: memory report (diagnostic)
### Did
- New `DesktopMemoryReport.snapshot(label): String` and `write(label): String?` (writes on `Dispatchers.IO`, returns the path). The report holds: label, time, uptime, window-visible flag, JVM args, process working set and commit charge via `ProcessMemoryProbes.forSystem()` (non-Windows: says "not supported, reported as 0"), heap and non-heap used/committed/max, every memory pool, buffer pools (direct, mapped), thread count and thread-name groups (digits folded to `#`; does NOT touch `DesktopPoTokenWebView`), loaded classes, GC counts/time, the artwork cache budget, and the `vmNativeMemory summary` output (or the reason it failed). Every section is wrapped, so nothing can throw to the UI.
- Reports go to `$XDG_DATA_HOME` (default `~/.local/share`) + `bitchord/memory-reports/memory-<yyyyMMdd-HHmmss>-<label>.txt`, the same data-dir rule `DesktopAnalysisRuntime` uses.
- Settings: switch "Collect memory reports (diagnostic)" (persisted, default off, key `memory_report_collect`) and button "Write memory report now" (label `manual`) with the saved path shown under it. With the switch on: `startup-90s` once 90 s after start, and `tray-60s` once per hide, 60 s after the window is hidden (showing the window again cancels it).
- `build.gradle.kts`: `-XX:NativeMemoryTracking=summary` in `jvmArgs` and `"jdk.management"` in `modules(...)`, both marked diagnostic.

### Files
- NEW `DesktopMemoryReport.kt` (report, `MemoryReportFormat` helpers, `DesktopMemoryReportSettings`, `DesktopMemoryReportSettingsRows` composable)
- NEW `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopMemoryReportTest.kt` (`ProbeMemoryMath.bytesToMb`, MB formatting, thread grouping, label/file name, snapshot never throws)
- EDIT `DesktopApp.kt` (`// UPGRADE M3`, 3 lines before the Player background row in Settings; the rest of the file was not opened); `Main.kt` (one `LaunchedEffect` starting the schedule); `desktopApp/build.gradle.kts`.
- `DesktopProcessMemory.kt` NOT rewritten.

### Decisions (owner could not be asked)
- The settings composable lives in the new file and receives the existing private `SettingsToggle` as a lambda, so the existing row is reused without widening its visibility. The button row is not hidden by the settings search filter.
- `DesktopArtworkCache` keeps its size private and the fluid palette cache is private to `DesktopFluidity.kt`, so the report shows only the artwork cache budget (plan: "if exposed, else skip"). No visibility changes were made for this.
- The schedule runs from `Main.kt` (application scope) so it works while the window is hidden; the switch is checked when the timer fires, so turning it on later still catches `tray-60s`.
- Thread groups fold every digit run to `#` and the report lists the 80 biggest groups.

### Verified
- NOT compiled and NOT run. Read-reviewed: imports (coroutine/compose ones already used in the repo; `java.lang.management` and `javax.management.ObjectName` are JDK, `java.management` is in the modules list), nullability, `private` `SettingsToggle` only called inside `DesktopApp.kt`, no unnamed catch parameter, no new top-level names clashing (grepped for the helpers).
- NMT needs the JVM flag at launch, so `vmNativeMemory` only has content in a build that carries the new `jvmArgs`; in a dev run without it the report says tracking is off.

### Deferred
- Cycle M4: memory fixes, after the `memory-reports` files come back.

### Exact next step
- Run the Actions build, install, switch the diagnostic on, play music, leave the window open about 2 minutes, close it to the tray for about 2 minutes, reopen, press "Write memory report now", and send back the files in `memory-reports`.

## Upgrade cycle 3, CI fix: palette hue accuracy
### Did
- CI compiled everything; 2 of 364 tests failed in `DesktopAlbumPaletteTest` (`solidRedIsRedInEverySlot`, `smallAccentIsIgnored`, both at the helper `assertNearHue`).
- Cause: `extractAlbumPalette` used the CENTRE of the 15 degree hue bin as the colour's hue, so pure red came out at 7.5 degrees and its tonal variants (+8 degrees) at 15.5 degrees, more than the 12 degree tolerance. It was also a real quality issue (pure red looked slightly orange).
- Fix (`DesktopAlbumPalette.kt` only, tests unchanged): each bin now also accumulates the weighted sin/cos of its pixels' real hue; the chosen colour's hue is the circular mean over the same bin +-1 group that `share()` uses (`hueOf`). Pure red is now exactly 0 degrees, variants are +-8 degrees.
### Verified
- NOT compiled and tests NOT run here. Hand-traced the two failing tests and the red/blue, three-colour, one-colour and grey tests against the new code; they hold.
### Exact next step
- Re-run the Windows workflow; the MSI step was skipped because the test step failed. Then look at the background with real songs and send the memory reports (see M3).

## Cycle 4 setup (settings, Studio, liquid-glass pill)
- Added `MUSES_STUDIO_PLAN.md` (parts S1, S2, G1 to G5) and `design/README.md` (drop `design/pill-reference.png` here).
- Removed the finished `MUSES_UPGRADE_PLAN.md` (parts M1 to M3 are done; their sections above in this file stay as the record).
- `.github/workflows/windows-desktop-build.yml`: renamed from "Windows Desktop - Seek Fix Build" to "Windows Desktop Build"; it now also runs automatically on every push to `main` (markdown, `docs/` and `design/` only changes are ignored) and can still be started by hand; the version label defaults to `1.8-dev` when none is given; old builds on the same branch are cancelled when a newer push arrives; the test-report artifact lost its "Seek-Fix" name. The "Verify packaged SMTC DLL and seek callback" check is kept. NOT run in this setup step.
- `.github/workflows/check.yml` (Check) and `.github/workflows/android.yml` (Android Release APK) no longer run automatically on push or pull request: both are now manual (`workflow_dispatch` only). They are not deleted, so they can still be started from the Actions tab. `release.yml` is unchanged (tags or manual). The Windows Desktop Build is now the only workflow that runs by itself.

## Cycle 4, part S1: settings shell
### Did
- Settings is now a left category rail (200 dp; a scrolling row of chips under 760 dp wide) plus the existing groups of the chosen category. While the search field is not blank every category is searched and the category name heads its matching groups (hidden when none match). Picking a category clears the search. The choice is kept while the app runs (`DesktopSettingsNav`), not saved to disk.
- Categories (rail order): Account and sync (ACCOUNT, LISTEN_TOGETHER); Playback (PLAYBACK, DESKTOP_PLAYBACK, LYRICS); Sound (SOUND_EFFECTS, AUDIO_QUALITY, AUDIO_OUTPUT, OUTPUT_PRECISION); Library and sources (SOURCES, LOCAL_MUSIC, DOWNLOADS, STORAGE); Appearance (APPEARANCE); System (LANGUAGE, MISCELLANEOUS); Advanced and diagnostics (ADVANCED, DIAGNOSTICS).
### Files
- New `DesktopSettingsCategories.kt` (enum, `categoryOf`, `settingsSectionsToShow`, rail, chips, lazy-item helpers); new test `DesktopSettingsCategoriesTest.kt`.
- `DesktopApp.kt` (hooks marked `// STUDIO S1 hook`): `DesktopSettingsSection` private -> internal plus two new entries; `DesktopSettingsScreen` body wrapper and loop; every `if (section == X) item {` became `settingsSectionItem(section, sectionMatches) {`; `SettingsGroup` reports to its category. The parameter list is unchanged.
### Decisions
- Lyrics stays in the Playback category (it was already its own group). The Appearance category is one section until S2 splits its sub-groups.
- Two groups were split by moving rows, no logic touched: spatial audio, Dolby Atmos and Equaliser left the Playback group for a new "Sound effects" group (SOUND_EFFECTS, in Sound); skip silence stayed in Playback. The memory-report rows left Appearance for a new "Diagnostics" group (DIAGNOSTICS, in Advanced). Strings use new keys with English fallbacks.
- Tray/startup toggles stay in MISCELLANEOUS (System), nerd stats in ADVANCED, integrations in the ACCOUNT group; the Licenses footer shows on the Advanced page and during a search.
- Icons: the shared set has no settings icons, so existing ones are reused (Pin, Play, MusicNote, Library, GridView, Performance, FrameRate).
- Page frame widened by 216 dp so group width stays 840 dp beside the rail.
### Verified
- NOT compiled and tests NOT run. Brace/paren balance checked by script; names grepped for clashes; imports copied from existing files.
### Deferred
- Hand-check in the UI: rail highlight, chips at narrow widths, search headers.
### Exact next step
- Run the Windows workflow and look at Settings, then S2.

## Cycle 4, part S2: Appearance sub-groups and Studio landing
### Did
- The Appearance section is now five labelled groups instead of one: Interface (desktop interface mode, full-screen cover art, animated canvas + Spotify Canvas setup), Colours and background (Fluidity, player background, legacy mesh gradient), Home (Home sections row), Studio and customisation (the existing inline Advanced customisation editors plus rows for Studio, Pill Studio), Window and performance (reduce dynamic blur, title bar, window material, app background). Rows were moved, not rewritten; one divider line was dropped.
- The old Customise hub page is replaced by a Studio landing page (route `CUSTOMISE_HUB` kept) with four cards: Pill (opens Pill Studio), Home layout (opens Home sections), Colours and typography (new page `STUDIO_COLOURS` showing the existing Advanced customisation editors), Presets and import/export (new page `STUDIO_PRESETS`). "Reset all customisation" with a confirmation dialog is on the landing page.
### Files
- New `DesktopStudioHub.kt` (`DesktopStudioHubPage`, `DesktopStudioColoursPage`, `DesktopStudioPresetsPage`, `resetAllCustomisation`).
- `DesktopApp.kt` (hooks `// STUDIO S2 hook`): Appearance item reassembled; `DesktopAdvancedCustomizationSettings` is now `internal` with `presetsOnly: Boolean = false` (true hides the search, live preview and all editor groups and keeps the presets, saved presets, Export JSON, Import JSON and Reset all); hub call site and two new `when` branches.
- `DesktopOverlays.kt`: `DesktopSettingsPage` gained `STUDIO_COLOURS`, `STUDIO_PRESETS`.
### Decisions
- The preset/import/export code is local state inside `DesktopAdvancedCustomizationSettings`, not separate functions, so it is reused through `presetsOnly` instead of being copied. Pill presets live in Pill Studio's draft state (applied by Apply), so the presets page links to Pill Studio rather than duplicating them (G4 reorganises that).
- "Reset all customisation" calls only existing setters: `resetAllNewValues`, interface mode DEFAULT, Fluidity off, blur/motion reductions off, player background DEFAULT, dock position/width/inset/corner/contrast/rim to the defaults in `DesktopMiniPlayerAppearance`. Saved user presets, window material and title bar are kept (stated in the dialog).
- `DesktopCustomiseHubPage` in `DesktopPillStudio.kt` is kept, now unused (nothing deleted).
- The Colours card opens a page, not the Appearance settings, so it does not depend on scrolling.
### Verified
- NOT compiled and tests NOT run. Brace/paren balance checked by script; all moved rows were re-counted by length against the original group. No new test (the new code is UI and setter calls).
### Deferred
- Pill Studio and the pages do not remember where they were opened from beyond the existing back history.
- Studio search keywords for the new Appearance group titles are not added to the settings search.
### Exact next step
- Run the Windows workflow; open Settings > Appearance and Studio (Appearance > Studio and customisation > Studio) and check each card, the reset dialog, and Export/Import on the presets page. Then G1.

## Cycle 4, parts G1 + G2: glass engine, backdrop capture, Glass Lab
### Did
- **G1 (engine):** `DesktopGlassParams` (11 float fields, defaults, ranges, `validated()` that clamps and replaces NaN/infinity with the default); `DesktopGlassMath` (Kotlin mirror of the shader: `sdRoundRect`, `edgeProfile`, `roundRectNormal`, light direction, and `uniformsFor` which fixes the uniform order/length: 13 uniforms, 21 floats); `DesktopGlassShader` (the SkSL as a `const val`, compiled once in `runCatching`, `available`, `error`, `reportFailure`, `effect(...)` returning a Compose `RenderEffect?` via `RuntimeShaderBuilder` + `ImageFilter.makeRuntimeShader(builder, arrayOf("content","blurred"), arrayOf(null, blur))`).
- **G2 (capture + lab):** `DesktopGlassState` (two shared `GraphicsLayer`s, BACKDROP and PAGE, plus consumer count, clock, revision), `rememberDesktopGlassState()` (30 fps clock that stops when no surface exists, the window is hidden or `reduceAnimation` is on), `Modifier.glassSource(state, slot)`, `Modifier.glassSurface(shape, params, tint, accent, fallbackRim)`, and the Glass Lab page (Settings > Advanced > "Glass lab (experimental)").
- Both requests (G1 and G2) arrived in one message; G2 needs G1, so both were done in this pass, G1 first.
### Files
- New: `DesktopGlassParams.kt`, `DesktopGlassShader.kt`, `DesktopGlassSurface.kt`, `DesktopGlassLab.kt`; tests `DesktopGlassParamsTest.kt`, `DesktopGlassMathTest.kt`.
- Edited (all marked `// STUDIO G2 hook`): `DesktopApp.kt` (`DesktopFrame`: state, provider, three `glassSource` modifiers; `DesktopSettingsScreen`: trailing `onOpenGlassLab: () -> Unit = {}` plus one `SettingsNavigationRow` in the Advanced options group; the `when` got a `GLASS_LAB` branch and the call site passes the callback), `DesktopOverlays.kt` (`GLASS_LAB` page entry).
### Decisions
- Imports and API calls were checked against Skiko's source on GitHub (`RuntimeEffect.makeForShader`, `RuntimeShaderBuilder.uniform(name, ...)`, `ImageFilter.makeRuntimeShader(builder, Array<String>, Array<ImageFilter?>)`, `ImageFilter.makeBlur`) and against `NowPlayingScreen.kt` / `LongPressOrigin.kt` for `rememberGraphicsLayer()`, `layer.record { this@drawWithContent.drawContent() }`, `drawLayer`. `asComposeRenderEffect` and `GraphicsLayer.renderEffect` / `.clip` were NOT checkable offline; they are standard Compose Desktop 1.10 API.
- One shared `RuntimeShaderBuilder` instead of one per size: size is a uniform and every uniform is rewritten on each call, so the builder holds no size. The blur `ImageFilter` is cached per sigma. A new `RenderEffect` is still made when a uniform changes (the API snapshots the uniforms); `glassSurface` reuses the last one when nothing changed.
- `effect(...)` takes an extra trailing `density: Float = 1f`: `bevelDp` and `blurDp` are dp and the shader works in px.
- Shader tweaks to the plan's reference (not its look): `float` maths with a final `half` conversion (avoids half/float overload doubts), sample coordinates clamped into the glass, output alpha = max(backdrop alpha, tint alpha) x coverage so a transparent window still shows tinted glass, and `time` drives a slow rim shimmer (also keeps every uniform used, so none is optimised away). Blur sigma = `blurDp * density / 2`.
- Two source slots (BACKDROP and PAGE), not one: with Fluidity on, the glow is a sibling of the page box and would otherwise be invisible to the glass. A surface draws BACKDROP then PAGE, each translated by (its root position - surface root position). The glass does not see the pill itself (the pill is not inside either source box).
- Sources record only while `consumers > 0 && !suspended` (suspended = window hidden to the tray or reduce dynamic blur on); otherwise `glassSource` is a plain `drawContent()`, so the app draws as before when no glass is in use.
- Fallback is the classic `desktopBarGlass(shape, fallbackRim)` when there is no state, reduce dynamic blur is on, or the shader is unavailable; `available` is snapshot state, so a runtime failure recomposes every surface into the classic pane. Before the first recording a surface draws a plain smoked fill (never empty).
- Redraw of a surface is triggered by a `revision` counter that the source bumps in its draw (written without being read there, so no self-loop). Consequence: glass lags the page by one frame.
- The lab has its own `DesktopGlassState` and test pattern, so it does not depend on the window capture. Album art in the lab is the current track's cover; the album colour is picked from five swatches because the Fluidity palette function is private.
- No G2 unit test: `GraphicsLayer` cannot be built in a plain JVM test. The pure parts are tested in G1.
### Verified
- NOT compiled and tests NOT run. No Skiko jar or Gradle was available offline. Done: Skiko API signatures read from source; bracket balance checked by script; names grepped for clashes; every uniform name is asserted to be declared in the SkSL by a test; imports copied from files that already compile.
### Deferred
- Unknown until the owner's build: whether the SkSL compiles (the Lab shows the error text), whether the shader's `fc` is relative to the glass's top-left in this Compose version (if the refraction looks shifted, this is the first thing to check), and whether `GraphicsLayer.clip` + `renderEffect` crop as expected.
- `positionInRoot` is read on layout only; a pure `graphicsLayer` slide of an ancestor (the pill's entry animation in G3) may leave the crop one step behind until the next layout.
- Source layers keep their last picture in memory after the last surface goes away (the picture is dropped from use, not discarded).
- The pill still uses the classic glass; `glassSurface` is used only by the Lab until G3.
### Exact next step
- Run the Windows workflow, open Settings > Advanced > Glass lab, send screenshots and the text under "shader available". Then G3.

## Cycle 4, parts G3 + G4: liquid-glass pill and Studio v2 for the pill
### Did
- **G3 (pill):** `DesktopLiquidPill` built from scratch with the same parameters and callbacks as `DesktopFloatingMiniPlayer` (plus an optional `configOverride`, used only by the Studio preview). It lays out by `DesktopPillConfig.zones` and draws on `glassSurface` (G2) with the album colour from `rememberFluidPalette` as accent and a dark tint whose alpha never drops below `contrast x 0.55`. Motion: press squish, hover glow, one light sweep along the rim per track change, breathing rim while playing, play/pause cross-fade, artwork cross-fade per `trackAnimation`, spin, slide-up/fade entry. `reduceAnimation` snaps everything (no infinite transition is created). Progress is a thin bar with a glass-lit knob on hover. All controls are the classic ones (previous, play, next, shuffle, repeat, more menu, expand, Pill Studio entry).
- **Chooser:** `DesktopPillHost` is now the single call site (it wraps the old call). Liquid is drawn only when `pillMaterial == LIQUID_GLASS`, the background material is Glass, and `DesktopGlassShader.available`; otherwise `DesktopFloatingMiniPlayer` (Classic) as before. If the shader fails at runtime, `available` flips and the pill recomposes into Classic.
- **G4 (Studio):** Pill Studio inspector is now five tabs: Layout (element zone/order, position, width, inset, body action), Material (Liquid glass / Classic, classic background, all 11 glass sliders with the params' own ranges, shape, corner radius, height, contrast, rim, honest-limits text), Motion (track change, transition, press squish, hover expand, hover glow, light sweep, breathing rim, spin), Artwork (placement, size, shape, spin switch), Presets (built-in Crystal, Frosted, Aurora, Minimal, Solid; the old layout presets; user presets save/rename/delete/apply; Export/Import JSON by file chooser). The live preview is the real `DesktopLiquidPill` over a striped test backdrop with its own `DesktopGlassState`; it falls back to the old preview when Classic is chosen or the shader is unavailable.
### Files
- New: `DesktopLiquidPill.kt` (`DesktopPillHost`, `DesktopLiquidPill`, pure helpers), `DesktopPillPresets.kt` (built-ins, `export`, `import`); tests `DesktopLiquidPillTest.kt`, `DesktopPillPresetsTest.kt`.
- Edited (marked `// STUDIO G3 hook` / `G4 hook`): `DesktopApp.kt` (call site renamed to `DesktopPillHost`; `DesktopSpringButton` private -> internal), `DesktopFluidity.kt` (`rememberFluidPalette` private -> internal), `DesktopGlassParams.kt` (`@Serializable`), `DesktopPillStudio.kt` (new `DesktopPillMaterial` enum; `DesktopPillConfig` new fields `pillMaterial`, `glass`, `pressSquish`, `lightSweep`, `breathing`, `hoverGlow`, all with defaults and validated; tabs, preview, new private helpers).
### Decisions
- `design/pill-reference.png` does not exist (only `design/README.md`), so the element layout is the classic pill's. Please add the screenshot before G5 tuning.
- Both G3 and G4 arrived in one message; G4 needs G3's pill for its preview, so G3 was done first.
- Persistence: `pillMaterial` and `glass` live inside `DesktopPillConfig` (already persisted and imported/exported with the appearance JSON), not as separate settings keys; old saved configs load with LIQUID_GLASS and default glass (tested). Default is LIQUID_GLASS as the plan says, so every existing user sees the new pill once the shader works.
- Solid and Accent background materials stay on the Classic pill (they are explicit choices); only Glass becomes liquid. The Material tab "Liquid glass" chip sets the background to Glass.
- The progress knob only marks the position: the classic pill has no seek callback, so none was added.
- Glass `tintAlpha` is raised to at least `contrast x 0.55` for readability, so the Studio tint slider cannot go below that floor in practice.
- The preview ignores the legacy appearance values (`DesktopMiniPlayerAppearance`, `miniPlayer*` flags) and uses the draft, because the draft already folds them in.
- `trackAnimation` still only offers Crossfade and None (as `validated()` already forced); SLIDE/POP values are treated as crossfade.
- Presets "Looks" keep layout, position, artwork and the user's presets; Minimal also hides shuffle and repeat and sets the compact height (same as the old Minimal layout preset, which still exists and gives the same layout).
### Verified
- **NOT compiled and tests NOT run.** No Gradle or Kotlin toolchain was available. Done by hand: imports copied from files that compile or checked as standard Compose; brace/paren balance of every edited file checked by script; names grepped for clashes; tests written for the pure parts (tint floor, material choice, sweep curve, old-JSON loading, validated clamps, every built-in preset, export/import round trip, rejection of bad or huge input).
- Not checked at all (needs a build and eyes): the look, the shader itself, the rim sweep and breathing, and the Studio tab layout.
### Deferred
- Likely first CI errors to look for: `drawOutline(outline, brush, alpha = ...)` overload resolution, `animateColorAsState` wrapper, `Song(...)` named-argument call in the preview, `items(PillGlassFields, key = ...)` inside the `when`.
- `glassSurface` reads `positionInRoot` on layout only; the pill's entry slide (14 dp) and press scale are graphicsLayer transforms, so the refracted crop can be a few px off while they run (G2 note). If visible, move the glass inside the transformed layer or re-read the position per frame.
- "Reset all customisation" (Studio hub) does not reset the new fields (`pillMaterial`, glass, motion flags); they reset through "Reset pill" in Pill Studio.
- The Studio preview does not play the sweep on demand (it plays once when the preview opens); a "replay" button is not added.
- README paragraph "Studio and liquid glass" and the honest limits are G5.
### Exact next step
- Run the Windows workflow (fix compile errors first, from the list above). Then play a track: check the pill (refraction at the rim, text readable over a bright cover, Classic fallback when Settings > Advanced > Glass lab says shader unavailable). Open Pill Studio and go through the five tabs; try each built-in preset; Export then Import a pill JSON. Send screenshots, then G5.

## Cycle 4, part G5: integration review and docs
### Did
- Read-through compile review of every Studio file together: `DesktopGlassParams.kt` (with `DesktopGlassMath`), `DesktopGlassShader.kt`, `DesktopGlassSurface.kt`, `DesktopGlassLab.kt` (first 120 lines), `DesktopLiquidPill.kt`, `DesktopPillPresets.kt`, `DesktopPillStudio.kt`, `DesktopStudioHub.kt` (reset part), plus the hooks in `DesktopApp.kt` by grep only (the file was never opened whole).
- One code change: "Reset all customisation" now keeps the pill's own saved presets (they live inside `DesktopPillConfig.userPresets`, so the full reset wiped them although the hub text says saved presets are kept). Marked `// STUDIO G5 hook` in `DesktopStudioHub.kt`.
- README: added "Studio and liquid glass (desktop)" with the three honest limits. The Studio Material tab already carries the same limit text from G4.
### Files
- Edited: `desktopApp/.../DesktopStudioHub.kt` (`resetAllCustomisation`), `README.md`, `HANDOFF.md`.
### Decisions
- No CI log was provided, so there was nothing to fix from a build; the review was by reading only.
- Correction to the G3+G4 Deferred list: "Reset all customisation does not reset the new pill fields" was wrong. `resetAllNewValues()` is `update { DesktopAppearanceConfig() }`, which replaces the whole config including `pillConfig` (material, glass, motion flags go back to their defaults). Only the pill's saved presets were lost; that is what G5 fixed.
- Classic and Liquid with Pill Studio, as read in the code: `DesktopPillHost` picks Liquid only for `LIQUID_GLASS` + Glass background + `DesktopGlassShader.available`; the Studio preview uses the same `desktopLiquidPillActive` test and shows the old preview otherwise, so the preview matches what the dock will draw. Solid and Accent backgrounds stay on Classic by design.
- Identifiers the liquid pill and Studio depend on were grepped and exist with matching signatures: `MusesMotion.press/settle/crossfade`, `rememberFluidPalette`, `DesktopAccent`, `DesktopArtwork(px=)`, `DesktopSpringButton`, `DesktopRepeatMode.next()/label()`, `BitChordIcons.Shuffle/Repeat`, `Song(videoId, title, artist, thumbnailUrl)`, `desktopBarGlass`. No duplicate top-level names across the desktop package and balanced brackets in every Studio file (script check).
- Left alone on purpose: the pre-existing `DesktopTempoBufferTest.kt` is not valid UTF-8 (a cp1252 dash in a comment, byte 0x97). It is outside Studio; if CI complains about encoding, re-save it as UTF-8.
- `design/pill-reference.png` still does not exist; the pill layout is the classic pill's.
### Verified
- **NOT compiled and tests NOT run.** No Kotlin or Gradle toolchain, and no Skiko or Compose jars, were available. Everything above is reading, grep and a bracket/duplicate-name script. The look, the shader and all animations are unseen.
### Deferred
- `asComposeRenderEffect`, `GraphicsLayer.renderEffect` and `.clip` are standard Compose Desktop 1.10 API but were not checkable offline; they are the first things to look at if CI fails in `DesktopGlassShader.kt` / `DesktopGlassSurface.kt`.
- From G3+G4 and still open: possible overload errors in `drawOutline(outline, brush, alpha = ...)`, the `Song(...)` call in the preview, `items(PillGlassFields, key = ...)` inside the `when`; the refracted crop can lag a few px during the pill's entry slide and press scale (`positionInRoot` is read on layout only); no "replay sweep" button in the preview; the glass lags the page by one frame.
- Whether the SkSL compiles and whether `fc` is relative to the glass's top-left in this Compose version (shifted refraction would point there). Glass lab shows the error text.
### Exact next step
- Run the Windows workflow and paste the first compile errors, if any. If it builds: Settings > Advanced > Glass lab (screenshot + "shader available" line), play a track and look at the pill, go through the five Pill Studio tabs and the built-in looks, then Reset all customisation and check a saved pill preset survives. Add `design/pill-reference.png`, then tune the shader from the screenshots.

### G5 CI fix 1
- CI error: `Unresolved reference 'drawOutline'` (DesktopLiquidPill.kt, DesktopGlassSurface.kt; the "Cannot infer type R" at DesktopGlassSurface.kt:286 followed from it). Cause: wrong import package `androidx.compose.ui.graphics.drawscope.drawOutline`; the repo's other files use `androidx.compose.ui.graphics.drawOutline`. Both imports fixed. NOT recompiled; more errors may be hidden behind this one.

### G5 CI fix 2
- After fix 1 the main code and test code compiled. Tests: 400 run, 2 failed, both mine and both test-side: `DesktopGlassMathTest.uniformsForWritesTheBlockInOrder` (colour `near` checks, eps 1e-3) and `DesktopLiquidPillTest.mixIsAPlainBlend` (eps 1e-6). Cause: a Compose sRGB `Color` stores 8 bits per channel, so 0.5 reads back as 128/255 = 0.50196. Tolerances widened to 0.01 in those assertions; no production code changed. NOT re-run.

## ODEON-FIX round (owner's test of S1 to G5): glass pill, Studio, seek bar, album colours
### What the owner saw
Liquid pill looked fully transparent with jumbled text; Studio sliders and preview did nothing visible; the progress line could not be clicked and the time was cut off; Classic pill artwork stuck out of the pill; backgrounds had rings (grey cover) and ignored whites, blacks and skin tones.
### Did
- **Pill is never empty any more.** New `DesktopGlassPane.kt` (`Modifier.glassPane`): Haze blur plus a dark/album tint, the same machinery the Classic pill uses. `DesktopLiquidPill` now draws this pane first; the refracting shader (`glassSurface`) became an overlay on top (`desktopLiquidEdgeActive` = Liquid material and a working shader). If the shader draws nothing or fails, the pill is still readable frosted glass.
- **Three materials:** `DesktopPillMaterial { LIQUID_GLASS, GLASS, CLASSIC }`. Studio Material tab has three chips (Liquid glass / Glass / Classic) and updated help text. `desktopLiquidPillActive` is true for Liquid or Glass on the Glass background (no longer needs the shader). Tests updated.
- **Shader:** probable cause of the invisible glass = it assumed its coordinates start at the glass's own corner (flagged as unverified in G2). Added an `origin` uniform (glass position in the window, px) and a rescue in the SkSL: if the incoming coordinate lies outside the glass box, the origin is subtracted (and added back when sampling). The shader now outputs only a rim ring (the bent scene), the specular light and the rim line; the interior is left to the frosted pane. Uniform block grew from 21 to 23 floats (`DesktopGlassMath`, test updated).
- **Progress bar:** the pill has `onSeek: (Long) -> Unit` (wired to `::seekPlayer` in `DesktopApp.kt`); the bar can be clicked or dragged, shows a knob while scrubbing, and the time sits in the same 16 dp row (it was cut off at the bottom). Metadata block height is now title + artist + 16 dp.
- **Artwork:** capped to `dockHeight - 14.dp` and horizontal padding 16 dp, in both Classic (`DesktopApp.kt`) and Liquid pills.
- **Glass Lab:** draws `glassPane` + `glassSurface`, like the pill.
- **Colours (palette v2, `DesktopAlbumPalette.kt` rewritten):** every sampled pixel counts. 4-bit RGB buckets are grouped into clusters in OKLab (distance 0.10); the three biggest clusters at least 0.16 apart (a slight chroma bias) become dominant/second/third; white is shown as soft light grey (OKLab L capped at 0.80), black as dark grey (floor 0.20), skin tones keep their warmth. Missing colours are tonal variants (hue +-8). `FluidPalette.brightness` (new) is the share-weighted lightness; the backdrop adds a black scrim of up to 45% for light covers.
- **Rings:** radial gradient stops used to end in `Color.Transparent` (black, alpha 0), which pulls the edge towards black and draws rings. All stops now keep the glow colour and only the alpha falls (5 stops, smoother).
- Palette tests rewritten for v2 (white+red, white+black+red, skin tone, grey, black and white, brightness, range, determinism).
### Files
New: `DesktopGlassPane.kt`. Changed: `DesktopGlassShader.kt`, `DesktopGlassSurface.kt`, `DesktopGlassParams.kt`, `DesktopGlassLab.kt`, `DesktopLiquidPill.kt`, `DesktopPillStudio.kt`, `DesktopFluidity.kt`, `DesktopAlbumPalette.kt`, `DesktopApp.kt` (two small edits marked `ODEON-FIX`). Tests: `DesktopGlassMathTest`, `DesktopLiquidPillTest`, `DesktopAlbumPaletteTest`.
### Verified
NOT compiled and tests NOT run here. Brackets balanced by script; tests hand-traced against the code (OKLab values worked through for white/red/black/peach/grey).
### Unverified / next
- Whether the coordinate rescue really fixes the Liquid overlay: check the Glass lab (Settings > Advanced) and the pill; with the rescue off the pill is still frosted glass.
- No dither noise yet (rings should be gone from the gradient fix; if faint banding remains on dark grey covers, add a noise overlay).
- Light covers: scrim formula `((brightness - 0.55) / 0.25) * 0.45`; tune by eye. `ALBUM_LIGHT_CAP` (0.80) is the whitest a glow may be.
- Studio saved configs keep their old values (a saved Frost 1.0 / Blur 40 stays until Reset pill or a preset is applied).

## 360 T1: window stability, orientation, edge clicks
### Did
- The zip already held an earlier attempt (`DesktopDisplayGeometry`, `DesktopDisplayWatcher`, native `WM_GETMINMAXINFO`, `WM_NCHITTEST` that gives no resize strip on edges lying on the work area, display/posture counters, 1/255 alpha root fill, restart-on-stale-frame-clock). Read it, kept it, filled the gaps.
- **Contract:** new `DesktopDisplayWatch.kt`: `data class DesktopDisplayInfo(widthDp, heightDp, isPortrait, isTouchLikely)` and `DesktopDisplayWatch.info: StateFlow<DesktopDisplayInfo>`, API exactly as in the plan. Published on start, on every window resize (AWT `ComponentListener`), on every display change and on posture change.
- **Crash guard:** new `DesktopWindowGuard.kt` (`install()` logs every uncaught exception, never exits; `RestartLimiter`). `Main.kt`: `desktopMain()` now loops around `application{}` (`desktopApplication()`): if the window's event loop dies by exception, it is logged and the window is rebuilt (max 5 per minute), playback/tray untouched. A normal exit still goes through `application{}`'s own process exit.
- **Logging:** `display[start|adapt]` lines (window bounds, placement, screen, work area, scale, native work area, dp, portrait, touch, minimum), `native WM_SIZE type=... rect=[...]` (new native `nativeLastSize`), placement transitions in `Main.kt`, posture flags.
- **Minimum size:** narrow height floor 560 -> 480 dp (plan). Rule unchanged: never larger than the work area.
- **Snap to top:** `WM_SIZE` with `SIZE_MAXIMIZED` posts `WM_BITCHORD_FIXMAX`; if the window rect is not within 2 px of the monitor work area it is set to it (max 2 tries per maximize, so it cannot loop). `Main.kt`'s `LaunchedEffect(placement)` now only assigns a placement Compose does not already have and never touches size.
### Files
New: `DesktopDisplayWatch.kt`, `DesktopWindowGuard.kt`, `DesktopDisplayWatchTest.kt`. Edited: `Main.kt`, `DesktopDisplayWatcher.kt`, `DesktopDisplayGeometry.kt` (480), `DesktopDisplayGeometryTest.kt`, `DesktopWindowsFrame.kt`, `desktopApp/native/window/window_jni.cpp`.
### Decisions
- `isTouchLikely` = touch digitizer reported by `nativePosture` flags bit 1 (`SM_TABLETPC`/`SM_DIGITIZER`, already in the DLL) instead of JNA; false off Windows or without the bridge. "Last input was touch/pen" is NOT implemented: AWT mouse events carry no pointer type and the frame's window proc does not see the Skiko child window's messages.
- `widthDp/heightDp` are the window's content size, `isPortrait` is the window's shape (taller than wide), not the monitor's.
- The crash guard loops in `main` rather than keying `Window`, because a composition failure ends `application{}` itself; a key could not revive that. `exitProcessOnExit` was not used (unconfirmed offline).
- Edge-click causes (a) to (d) were already handled by the earlier attempt; read and left as is.
### Verified
**NOT compiled, tests NOT run, C++ NOT built.** Bracket balance by script, tests hand-traced.
### Deferred
- Whether drag-to-top now fills the work area: read `desktop.log` for `native WM_SIZE type=MAXIMIZED rect=...` against `work=`.
- Touch/pen "last input" signal; `WM_DPICHANGED` suggested rect is left to AWT.
### Exact next step
Run the Windows workflow; on the laptop rotate, snap to top, click the far right/bottom pixel, then send `desktop.log`.

## 360 T2: responsive layout for portrait, narrow and tent modes
### Did
- New `DesktopAdaptive.kt`: `DesktopWindowClass {COMPACT <600, MEDIUM 600-979, EXPANDED >=980}`, `DesktopOrientation`, `DesktopAdaptive`, pure `DesktopAdaptiveMath` (classes, fitWidth, columnsFor, cellWidth, side-panel overlay rule, pill height cap 72 dp in COMPACT, pill veil floor 0.55 in COMPACT/portrait, page bottom padding, pill insets, 44 dp top-bar target), `LocalDesktopWindowClass`, `LocalDesktopAdaptive`, `Dp.fitWindow()`.
- `DesktopFrame` (`DesktopApp.kt`): class and orientation come from its `BoxWithConstraints` (works alone), provided to the whole shell and its overlays; `compact` = below EXPANDED; queue/lyrics panel is a full-page overlay below 800 dp (`DesktopSidePanel.kt`); pill side inset 12 dp and bottom inset at least 12 dp in COMPACT; page bottom padding computed from the pill height so the last row clears it; classic pill capped at 72 dp and veil floor raised; top-bar buttons at least 44 dp outside EXPANDED.
- Fixed widths: replay card 300 and playlist art 286 shrink to the window (`fitWindow`), legacy now-playing slot min 220 -> 96, equalizer preset list 168 -> 112 in COMPACT.
### Files
New: `DesktopAdaptive.kt`, `DesktopAdaptiveTest.kt`. Edited (all `// 360 T2 hook`): `DesktopApp.kt`, `DesktopSidePanel.kt`, `DesktopReplayUi.kt`, `DesktopEqualizerUi.kt`.
### Decisions
- Not wired to `DesktopDisplayWatch.info`: the frame's own constraints are what layout actually has (title bar excluded). Merge is one line in `DesktopFrame` if wanted.
- Search was already an icon that opens the search page, the settings rail already becomes chips below 760 dp, and the top bar already sheds controls below 520 dp (earlier attempt): kept.
### Verified
**NOT compiled, tests NOT run.** Brackets compared to the original file by script; tests hand-traced.
### Deferred
- **Home Recents (2 columns, peek) and shelf card sizes are in `sharedUi/.../HomeScreen.kt`**, which this part may not edit; needs a defaulted parameter by whoever owns sharedUi.
- `DesktopLiquidPill.kt` has no 72 dp cap and no veil floor yet (same two lines as the classic pill).
- Dialogs and popovers were not audited beyond the song menu; the full-screen player was not reviewed.
### Exact next step
Compile; at 360x640, 540x960, 720x1280 and 1280x800 dp check the top bar, pill, side panel overlay and the bottom of Home.

## 360 T3: touch support
### Did
- Earlier attempt already had the setting (`DesktopTouchMode` AUTO/ALWAYS_ON/ALWAYS_OFF, persisted under `convertible_touch_mode`, settings chips in `DesktopApp.kt`), `LocalDesktopTouchMode` provided in `Main.kt`, `Dp.forTouch()` on caption buttons, the top bar, the pill, queue rows and the thin slider. Kept as is (the plan's new `DesktopTouchSettings.kt` would have duplicated `DesktopTouchSettings` in `DesktopTouchMode.kt`).
- New `DesktopTouch.kt`: `DesktopTouchMath` (hit target, long-press timing, drag-to-scroll delta, fling distance, keyboard throttle), `Modifier.desktopTouchScroll(state)` (vertical finger drag scrolls with a short fling; active only in touch mode), `Modifier.desktopLongPress {}`, `DesktopTouchKeyboard.show()` (TabTip.exe via `ProcessBuilder`, Windows + touch mode only, once per focus, failures logged and ignored).
- Hooks in `DesktopApp.kt` (`// 360 T3 hook`): `desktopTouchScroll` on eight `LazyColumn`s (settings list, history, search/library pages, licences, artist page, sidebar playlists, etc.); touch keyboard on search-field focus.
### Files
New: `DesktopTouch.kt`, `DesktopTouchMathTest.kt`. Edited: `DesktopApp.kt`.
### Decisions
- AUTO stays "tablet posture" (not "digitizer present"): on a touch laptop the digitizer is always there, so it would keep touch sizing on in laptop posture. `ALWAYS_ON` covers tent mode if Windows reports laptop.
- Windows delivers touch as mouse events, so in touch mode a mouse drag also scrolls (no text selection by drag there). Documented in the modifier.
### Verified
**NOT compiled, tests NOT run.** `VelocityTracker`, `detectVerticalDragGestures`, `animateScrollBy`, `dispatchRawDelta` are standard Compose API but were not checkable offline; look there first if CI fails in `DesktopTouch.kt`.
### Deferred
- Not applied: horizontal shelves/Recents (`sharedUi`), the player's scrub bar (`sharedUi`), Home page scroller (`HomeScreen.kt`, `sharedUi`).
- `desktopLongPress` is defined but unused: the app has no right-click menus (menus are buttons, track rows already have `onLongPress`).
- Hover-only controls (card actions, pill knob, tooltips) were not audited. Pill progress bar already drags (ODEON-FIX); no larger thumb.
### Exact next step
Compile; in touch mode, drag the settings list and the history page with a finger, tap the search box and confirm the touch keyboard opens.

## 360 T5: full-screen player and lyrics in portrait / tablet shapes
### Did
- `PlayerHost` got two defaulted members (`widePortraitLayout=false`, `maxContentWidth=null`); only `DesktopPlayerHost` overrides them (true, 900.dp). Android compiles and behaves unchanged.
- New `PlayerWideLayout.kt` (pure: column width, artwork cap 62% of height, control-spread max 120 dp, action-row max 520 dp, lyric scale 1.0 up to 600 dp growing to 1.4 at 900 dp, lyric line gap). Off in landscape and when the host flag is off.
- `NowPlayingScreen.kt`: `wide` value; the two `PLAYER_MAX_WIDTH` column uses now use `wide.columnMax`; sleeve capped; control spread cap raised; bottom action row held to 520 dp and centred in wide mode; `typeScale`/`wideLayout` passed to both `LyricsPanel` calls.
- `PlayerLyrics.kt`: defaulted params `typeScale`, `wideLayout`; lead, sub and backing type scaled, line height x1.1, gap between lines in wide mode. Defaults give the exact old values.
### Files
New: `PlayerWideLayout.kt`, `sharedUi/src/jvmTest/.../ui/player/PlayerWideLayoutTest.kt`. Edited (all `// 360 T5 hook`): `PlayerHost.kt`, `NowPlayingScreen.kt`, `PlayerLyrics.kt`, `DesktopPlayerHost.kt`.
### Decisions
- Kept the 30 dp `PLAYER_GUTTER` (plan said 24 dp): `PlayerQueue.kt` and the lyrics list bleed by that constant and `PlayerQueue.kt` is not owned by this part.
- Bottom row targets were already 44 dp (`PlayerControls.kt`, not owned); only its width and grouping changed, from `NowPlayingScreen.kt`.
- `LandscapePlayer.kt` untouched.
### Verified
**NOT compiled, tests NOT run.** Brackets balanced by script; test values hand-traced.
### Deferred
- Look at real screenshots at 540x960, 720x1280, 900x1400. `Arrangement.spacedBy` between lyric rows relies on scroll maths using layout info (it does), but is unchecked on screen.
### Exact next step
Compile; open the player at the three sizes and lyrics at 720x1280.

## 360 T4: album colours brighter and richer
### Did
- `DesktopAlbumPalette.kt`: chromatic floor 0.30 -> 0.42, chroma boost 1.12 -> 1.25, base L 0.18 -> 0.22, intensity floor 0.62 -> 0.80 (named `internal const` values).
- `DesktopFluidity.kt`: role alphas 0.95/0.85/0.75/0.55; fuller radial falloff; full-window dominant-colour wash (20% alpha) under the glows, dissolving with them; light-cover scrim starts at brightness 0.62, max 0.35 (`fluidLightScrim`); `drawDither()` after the scrim.
- New `DesktopDither.kt`: 512x512 premultiplied noise tile (about 2% alpha), built once, drawn with `drawImage` only (same Skia bitmap path as `DesktopArtworkMesh`); no shader, no per-frame allocation; failure to build is swallowed.
- Tests added to `DesktopAlbumPaletteTest.kt`.
### Files
New: `DesktopDither.kt`. Edited: `DesktopAlbumPalette.kt`, `DesktopFluidity.kt`, `DesktopAlbumPaletteTest.kt`.
### Decisions
- `paletteStrength` default left at 1f (`DesktopApp.kt` compares against it).
- No `ShaderBrush`/`ImageShader`: not used anywhere in the repo, so not confirmable offline.
### Verified
**NOT compiled, tests NOT run.** Noise balance (50.03% white) and navy/black/base colour values checked in Python replicas of the maths.
### Deferred
- Tune by eye on the laptop: wash alpha, glow falloff, dither visibility.
### Exact next step
Compile; compare a saturated, a dark and a white cover.

## BACKDROP-STYLE: owner-chosen background look, grain off (after the 360 round)
### Owner's report
Vertical and tablet layouts work well. The 360 T4 colour update made the backgrounds grainy (the dither noise), duller and darker, and the colour recognition looked as if it chose darker colours. The ring banding is gone. The owner wants bright colours and a setting to choose the kind of background.
### Did
- **Grain is off by default.** `drawDither()` only runs when the new setting "Grain texture" is on (the rings it was added for were fixed by the gradient stops).
- **Background looks** (new `DesktopBackdropStyle.kt`): `DesktopBackdropLook { VIVID, NATURAL, DEEP }` with a tuning table (`DesktopBackdropTuning`: chroma scale, lightness floors/cap/shift, base lightness, glow alpha scale, wash alpha, translucent base alpha, intensity min/scale). Default VIVID. NATURAL equals the T4 look.
- **Two sliders**: Brightness (moves every glow +-0.15 OKLab lightness) and Colour intensity (chroma x0.5 to x1.5), 0.5 = no change. Persisted as strings (`backdrop_look`, `backdrop_brightness`, `backdrop_vibrance`, `backdrop_grain`).
- **Restyle, not re-extraction**: `restylePalette(palette, tuning, brightness, vibrance)` (end of `DesktopAlbumPalette.kt`, pure) restyles the extracted palette when it is drawn, so changing look or sliders is instant and cheap. `DesktopFluidBackdrop` (`DesktopFluidity.kt`) applies it, uses the look's wash alpha, glow alpha scale and translucent base alpha, and a change on the SAME cover updates at once (no 1.4 s dissolve, no swell per slider tick); a new cover still dissolves.
- **Settings UI**: Settings > Appearance > Colours and background: chips Vivid / Natural / Deep, short description, the two sliders, a Grain toggle and "Reset background colours" (`// BACKDROP-STYLE hook` in `DesktopApp.kt`). It also drives the player's Album reactive background because both use `DesktopFluidBackdrop`.
- Tests: `DesktopBackdropStyleTest` (ordering of looks, stored-value fallback, slider range, vivid > natural > deep, natural keeps the cover, sliders move lightness/saturation, greys stay neutral, ranges, intensity).
### Verified
NOT compiled, tests NOT run. Bracket counts compared with the original by script; tests hand-traced for the OKLab behaviour (a Deep look is darker through `lightnessShift`, not through chroma alone).
### Unverified / to tune by eye
- Default Vivid values (floor 0.60, chroma x1.25, wash 0.34, base alpha 0.58 over Acrylic/Mica). The higher translucent base alpha trades a little of the Acrylic see-through for cleaner colour; lower `translucentBaseAlpha` in `DesktopBackdropTunings` if the glass feel matters more.
- Grain is still available but off; `DesktopDither.kt` unchanged.

## ART-TRANSITION ENGINE (account A): artwork-disintegration transition, engine only, NOT wired in
New files only, package `com.music.bitchord.desktop`, all `internal`: `DesktopArtTransition.kt` (controller, `ArtParticleSim`, `ArtGridSampler`), `DesktopArtTransitionPhysics.kt` (pure maths), `DesktopArtTransitionOverlay.kt` (composable), test `DesktopArtTransitionMathTest.kt`. No existing file touched. The CONTRACT is matched exactly; extras are listed below.
### For account B (wiring)
- Put `DesktopArtTransitionOverlay()` last in the root Box, no padding/offset: Canvas pixels must be the window pixels that `sourceBounds`, `pointer` and `targetBounds()` use. It has no pointer input and runs no frame loop while `active` is false.
- Set `DesktopArtTransition.targetBounds` to the live pill thumbnail bounds (window px). A zero or tiny rect ends the run at once with reveal = 1. Optional: set `backdropSink` (called on the UI thread, at most 12 times per run, colour = average of one slice of background particles, position = their average source position, strength 0.15..1).
- Call `start(ArtTransitionRequest(...))` when a song is picked and `audioReady(videoId)` when its audio is actually playing. Either order works (`audioReady` before `start` for the same id counts as ready; a replay of the same id right after it also skips the hold). Playback must not wait on anything here.
- Hide the pill's real thumbnail while `reveal < 1`; draw it at `reveal` alpha (the particles fade out as reveal rises, so it is a crossfade). While nothing runs, reveal is 1.
- Extra, optional: `DesktopArtTransition.sourceFade` (0..1, rises over ~30..100 ms). Draw the source thumbnail at `1 - sourceFade` so it does not sit under the leaving particles. Without it the source simply stays visible.
- `enabled = false` or `DesktopAppearanceSettings.reduceAnimation` true: `start()` just calls `cancel()` (reveal = 1).
### Decisions (no questions allowed)
- Timeline on the sim's own clock, which starts when the sampled grid is ready (not at `start()`); dt clamped to 50 ms. Lift 0-80 ms (+5%); sweep releases particles at 80 + 360 * delay ms (delay = 0.72 diagonal + 0.28 hash noise); pill particles fly at 300 + 220 * delay (+<=40) ms for 360-500 ms (all home by ~1100); spring settle uses `MusesMotion.SETTLE_STIFFNESS/SETTLE_DAMPING`; reveal 0 -> 1 over 200 ms starting at max(1100, last settle start + 120).
- Reveal starts only once audio is ready AND every pill particle has begun settling. Not ready when particles arrive: they orbit above the thumbnail (ellipse centred 0.30 x height above its top); ready releases them with a 0-140 ms stagger. The orbit is visual only: after 6.5 s of sim time without audio the particles settle anyway.
- Loading gets 700 ms (artwork `artworkAt(256)` via `DesktopArtworkCache.load`, then `toAwtImage()`, centre square crop, two bilinear Graphics2D downscales to 48x48). Failure, null url or timeout: finish instantly, reveal = 1. Grids cached in a 16-entry `DesktopCountLru` by sized url. reveal is 0 from `start()` until then.
- 35% of cells (hash) are background particles: drift away from the pill (direction +-0.7 rad), grow up to ~4x, fade by ~950 ms, never reach the pill.
- A watchdog ends a run (reveal = 1) if no overlay frame arrives for 700 ms, or after 12 s, so a missing overlay can never leave the thumbnail hidden.
- `start()` while running: the old sim keeps drawing for 150 ms with alpha fading to 0, then goes; only one fading sim is kept.
- No source bounds: a 80x80 box around `pointer`; neither: a box above the pill.
- Draw loop: FloatArrays, no per-frame allocation. Deviation from "one drawRect": `drawRoundRect` while flying/orbiting (rounded feel), `drawRect` once home; bright particles in motion get one extra low-alpha halo round-rect.
### Verified
NOT compiled, tests NOT run. Written against the existing APIs only (`DesktopArtworkCache.load`, `DesktopCountLru`, `MusesMotion`, `DesktopAppearanceSettings.reduceAnimation`, `String?.artworkAt`, `toAwtImage`); the spring was derived by hand and `springStepIsTimestepIndependent` checks it.
### Unverified / to tune by eye
- Sizes at real thumbnail scale (a ~56 px pill thumbnail gives ~1.2 px cells; `FLIGHT_MIN_DOT` 1.6 px keeps flying particles visible), sweep and flight stagger, orbit radius, halo strength, `toAwtImage` availability in this Compose version.

### ART-TRANSITION ENGINE, update 2 (account A): source location and scaling
Bug seen: particles came only from a small patch at the pointer, not the whole artwork. Cause: `start()` got `sourceBounds = null`, so the engine used its 80x80 px box around the click. Fixes, all in the engine files:
- NEW `Modifier.artTransitionSource(videoId)` (in DesktopArtTransitionOverlay.kt). **Account B: add it to the artwork view of EVERY card/row/tile that can start playback** (Recents, Song mixes, Listen again, lists, search...). The engine then finds the clicked view itself at `start()`: it uses the full unclipped box of the reported view for that videoId (the one containing the pointer wins), then `request.sourceBounds`, then any reported view, then the pointer box. Passing `sourceBounds` is still fine but no longer required.
- Scaling is "cover"/Crop-aware: the square artwork is as big as the box's longer side, centred; cells the box crops away start invisible and fade in as they let go. Same for the pill: home cells are a `max(w,h)` square centred on `targetBounds()`; cells outside a non-square thumbnail fade out on arrival. If the pill/cards use FillBounds or Fit instead of Crop, tell me and the mapping changes.
- Contract unchanged. Not compiled, tests not run (new test: `coverGeometryMatchesCropScaling`).

## Artwork transition wiring (not compiled)

Confirmed by grep first: nothing in DesktopApp.kt, DesktopFluidity.kt, DesktopLiquidPill.kt referenced the engine.

Touched (all in desktopApp/.../desktop/):
- DesktopArtTransition.kt: println diagnostics in start() (disabled / reduceAnimation / blank url); lookupSource/resolveSource also match "url:<request.artworkUrl>"; reportSource prune amortized (`lastPruneSize`, prune when size > max(96, 2x last)).
- DesktopArtTransitionWiring.kt: added `DesktopArtPress`, `Modifier.artPressTracker()` (Initial pass, never consumes, window coords via positionInWindow origin), `maybeStartArtTransition(...)` (logs "no fresh press" / "same song" / "disabled"), `ArtTransitionHostEffects()` (sets backdropSink once; maps setting to `enabled`).
- DesktopArtwork.kt: onGloballyPositioned reports "url:" + original url.
- DesktopApp.kt: `artPressTracker()` on DesktopFrame root Box (~4971); `ArtTransitionHostEffects()` + `DesktopArtTransitionOverlay()` as last children of that Box (~5077); playCurrent (~813) calls maybeStartArtTransition before playbackEngine.load; isLoading true->false LaunchedEffect -> audioReady (~1712); DesktopPillArtwork: artTransitionTarget + instant path while revealing; pill elements except artwork wrapped in PillRevealFade; legacy mini player 40dp art gets artTransitionTarget; settings chips "Artwork transition" (~7088) + modified-marker.
- DesktopLiquidPill.kt: same (target + instant path via new `transitionTarget` param, fade wrapper); both disabled for the Studio preview (`preview`) so it cannot steal targetBounds.
- DesktopAdvancedAppearance.kt: `DesktopArtTransitionMode {OFF, EVERY_CLICK, ON_CHANGE}`, config field `artTransitionMode` (default EVERY_CLICK, persisted in the existing JSON config), registry entry "desktop.art_transition".
- DesktopFluidity.kt: pulses drawn as SrcOver radial glows in the Canvas draw lambda, before the light scrim; no state read, existing 30 fps clock redraws.

Decisions:
- Root Box is in DesktopFrame (~4971), not at DesktopFluidBackdrop (~2906, which sits in a backdrop lambda); overlay placed there.
- Step 3 vs 8: ON_CHANGE requires a different videoId; EVERY_CLICK requires only a fresh press (re-clicking the playing song replays the animation). Auto-advance never animates in either mode.
- Press position = pointer-local + root Box window origin.
- Press tracker is not shared with the window title bar region differences; Box assumed at window origin otherwise.

Uncovered / risks:
- Only one `targetBounds` exists: if two live pills compose at once the last wins. Full-player bar (~4303) and DesktopPillStudio artwork are not targets.
- resolveSource falls back to any artwork with the same url (could be the pill itself when replaying the same song).
- Pill element wrappers add a Box + graphicsLayer per element even when inactive (visually identical, tiny cost).
- Reveal fade starts at ~0.6 of reveal per helper; unchanged.
- Strings use DesktopStrings[key, default]; no catalogue entries added for other languages.

## Artwork transition: silent-abort fix (not compiled)

Cause (as suspected): start() sampled the 256px url, which no on-screen thumbnail had loaded, so gridFor() had to download inside LOAD_TIMEOUT_MS=700 and finishLocked() ran with no log.

Changes:
- DesktopArtwork.kt: `DesktopArtworkCache.cached(url)` (peek, never downloads); after a bitmap loads, `DesktopArtTransition.noteLoaded(originalUrl = url, loadedUrl = sized)`.
- DesktopArtTransition.kt: `loadedUrls` LRU (256 entries, not 64: strings are cheap and a list can show 100+ thumbnails before a click); gridFor(original, url) order = grid cache -> noted loaded url if still cached -> cached 256px -> download 256px (own `scope.async`, so it keeps running after the timeout = background warm-up). A ~120px source is sampled as is.
- Logs for every exit: disabled, reduceAnimation, blank url, superseded, grid null/timeout, stall, hard cap, plus which grid branch and which resolveSource branch + final Rect.
- Target zero: the load coroutine waits up to 400 ms (25 ms polls) for a non-zero rect; tick()/resolveSource() use `liveTarget()` which falls back to a 48px box at bottom-centre (windowSize set by the root Box's onGloballyPositioned in artPressTracker) and logs once per process.
- Landing target: registry in the engine (`registerTarget/unregisterTarget/bestTarget`); `targetBounds` default now returns the most recently registered target with non-empty bounds. `artTransitionTarget()` registers only on the invisible->visible transition (attached, size >= 2px), unregisters when it becomes invisible and on dispose. LiquidPill with `transitionTarget=false` (Studio preview) never calls it; Studio's own artwork uses plain DesktopArtwork.
- Spinner: neither the app pill nor the liquid pill has an isLoading spinner/placeholder (grep found none). The only one tied to the transition is the top-bar play button (DesktopApp.kt ~4285), now hidden while `DesktopArtTransition.active`. The pill thumbnail is already alpha 0 while revealing. NowPlayingScreen's loading state untouched.

Risks: a grid sampled from a small thumbnail is cached under the 256 key, so that run keeps the lower detail until the cache entry is evicted. The fix for "first play after a cold start" still falls back to the old path if the download exceeds 700 ms, but now logs it and the next play is instant.

## VOICE V1: model finder and command parser (NOT compiled)

### Did
- `DesktopVoiceModel.kt`: `VoiceModelFiles`, `VoiceModelScan` (Ready / WrongFormat / NotFound), `VoiceModelFileSpec`, `DesktopVoiceModelSpec` (four files with size + SHA-256, two source base URLs, `urlFor`, `sizeMatches`, streaming `sha256Hex` in 1 MB chunks), top-level `looksLikeSherpaLayout` / `looksLikeOnnxAsrLayout`, `DesktopVoiceModelLocator` (`check`, `findExisting`, `defaultRoots`).
- `DesktopVoiceCommands.kt`: `VoiceCommand` (Play, Pause, Resume, Next, Previous, VolumeUp, VolumeDown, Shuffle(on), Like, AddToPlaylist, PlayPlaylist, WhatsPlaying, Unknown) and `DesktopVoiceCommandParser` (`parse`, plus `normalize`, `matchPlaylist`, `playlistScore`, `PLAYLIST_THRESHOLD`). Pure, no I/O, no logging; the text is never stored.
- Tests: `DesktopVoiceModelTest`, `DesktopVoiceCommandsTest`. Only empty or tiny temp files (deleted in `finally`); no microphone, window, model, or network.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceModel.kt`, `DesktopVoiceCommands.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVoiceModelTest.kt`, `DesktopVoiceCommandsTest.kt`. No existing file edited (so no `// VOICE V1 hook` markers). No Gradle change.

### Decisions (no questions allowed)
- Contract names kept. Small additive, defaulted parameters: `check(dir, spec, sizeTolerance)`, `findExisting(roots, maxDepth, spec, sizeTolerance)`, `defaultRoots(env, home, windows)`. Size tolerance is a fraction (default 0.01); tests pass `Double.POSITIVE_INFINITY` so empty files count.
- `DesktopVoiceModelSpec.sources` are BASE urls ending in "/"; append the file name with `urlFor(base, name)`. `LICENCE_URL` points at the first source's model page (not checked online).
- `check`: Ready needs the four names (any case) and sizes within tolerance; right names but wrong size is WrongFormat ("incomplete download?"); onnx-asr layout is WrongFormat ("different format"); encoder .onnx with other or missing names is WrongFormat; anything else NotFound.
- `findExisting` is blocking: call it off the UI thread. BFS, folders whose name has "handsy"/"parakeet"/"sherpa" visited first, 200 folders per root, links are not followed and every folder must stay under the root's real path (also stops junctions). A folder that is a model is not searched deeper. Results: hinted path first, then Ready before WrongFormat.
- Parser output is lower-case normalized text (apostrophes inside words kept). Leading filler: hey muses, can/could/would/will you, i want to, i'd like to, let's, please, just. Trailing: please, thanks, thank you, for me. Misheard words are fixed only in the FIRST word (plays, paws, necks...), never in a title.
- Bare "play" = Resume; "stop" = Pause. "play my X": tried as a playlist first, if no playlist matches it is a song titled "my X". Bare "play X" is ALWAYS a song, even if X equals a playlist name. The word "playlist" anywhere = explicit playlist intent: no match gives Unknown.
- "the song X"/"the track X" loses the filler; a bare "song X" is kept (Song 2 by Blur). Side effect: a title that starts with "the song" loses those words.
- "by" splits on the LAST " by ". Nothing after it, or a single word like me/you/it/the after it, means the whole text is the title ("stand by me"). Extends the plan's rule; list is `NOT_AN_ARTIST`.
- Playlist matcher: tokens minus my/the/playlist/a, number words to digits, typo-tolerant only for words of 4+ letters, score = max(token overlap both ways, whole-string similarity). Threshold 0.72. Two different playlists within 0.05 at the top = ambiguous = Unknown (never guess). Returns the playlist's real name.
- "add this to favorites/liked songs" = Like. "shuffle <anything else>" is Unknown. Volume ignores amounts ("a bit", "ten"): V3 decides the step.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler in this chat). Every test case was hand-traced against the code, including the edit-distance and ambiguity cases. Imports checked, no `private` type in an `internal` signature, no new names clashing with the repo (grep). Windows-only code: none in V1 (only `DesktopPlatform.isWindows` as a default argument).
Compile watch-list if the build complains: `DesktopVoiceCommandParser::playlistScore` bound reference in a test; `sumOf`/`maxOf` Double lambdas in `playlistScore`; the symlink test returns early when links are not allowed.

### Deferred
- "shuffle my X playlist", volume amounts, "play X from the album Y", spelled-out numbers inside song titles.
- Model download URLs, hashes and licence link are copied from the plan, not checked online.

### Exact next step
V2: add `DesktopVoiceRecorder.kt`, `DesktopVoiceRecognizer.kt`, `DesktopVoiceDownloads.kt` using `DesktopVoiceModelSpec.files` / `sources` / `urlFor` / `sizeMatches` / `sha256Hex` and `VoiceModelFiles` from V1. Build once after V2.

## VOICE V2: recorder, offline recognizer, downloads (NOT compiled)

### Did
- `DesktopVoiceRecorder.kt`: `VoiceException(code)`, `VoiceErrors` (codes), pure `VoiceAudio` (rms, peak, bytesToShorts, trimSilence, normalizeGain), `VoiceCaptureLine` + `openSystemCaptureLine()` (javax.sound.sampled, 16 kHz mono 16-bit LE), `DesktopVoiceRecorder.record(maxMillis = 12000, shouldStop)`: 100 ms chunks into a ByteArrayOutputStream, line closed in `finally`, silence trimmed, gentle gain. Memory only, nothing written, nothing logged.
- `DesktopVoiceRecognizer.kt`: `VoiceRecognizer`, `VoiceRecognizerSupport` (`wavBytes`, `parseSherpaOutput`, `buildCommand`, `enoughMemory`, `threadCount`, `wipeTempDir`), `FreeMemoryReader` + `SystemFreeMemory` (ManagementFactory, `com.sun.management.OperatingSystemMXBean.freeMemorySize`), `VoiceProcessRunner` + `ProcessBuilderVoiceRunner` (stdout/stderr on two daemon threads, 25 s timeout, destroy in `finally`), `SherpaSidecarRecognizer` (RAM guard 1.8 GiB, one temp WAV in a private temp folder, zero-fill + delete + folder delete in `finally`, runs inside `runInterruptible` so cancel kills the process). Logs only "recognizer start" / "finish in N ms" / "error=CODE".
- `DesktopVoiceDownloads.kt`: `DesktopVoiceSidecarSpec` (the ONE constants object: version, asset names, URLs, `VERIFIED = false`), `DesktopVoicePaths` (`dataDir` = `%LOCALAPPDATA%\Muses\voice`, Linux `$XDG_DATA_HOME|~/.local/share/muses/voice`; `programDir`, `modelDir`), `VoiceDownloadMath` (fraction/overall/slice/verify), `VoiceHttp.downloadTo`, `DesktopVoiceSidecar` (`findExisting`, `findInDir`, `download`, `deleteTree`), `DesktopVoiceModelDownloader.download`.
- Tests: `DesktopVoiceRecorderTest`, `DesktopVoiceRecognizerTest`, `DesktopVoiceDownloadsTest` (fake microphone line, fake process runner, tiny temp files; no device, no model, no network).

### Files
New only: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceRecorder.kt`, `DesktopVoiceRecognizer.kt`, `DesktopVoiceDownloads.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVoiceRecorderTest.kt`, `DesktopVoiceRecognizerTest.kt`, `DesktopVoiceDownloadsTest.kt`. No existing file edited (no `// VOICE V2 hook` markers). No Gradle change, no new dependency (`java.desktop`, `java.management`, `jdk.management`, `java.net.http` are already in the jlink module list; kotlinx-serialization-json is already used).

### Decisions (no questions allowed)
- Contract names kept. Additive, defaulted parameters only: `DesktopVoiceRecorder(openLine)`, `SherpaSidecarRecognizer(exe, model, memory, runner, tempRoot, timeoutMillis, threads)`, `DesktopVoiceSidecar.findExisting(chosen, appDirs, dataDir, pathVariable, pathSeparator)` (V4 passes the owner-chosen path as `chosen`), `download(...)` as in the plan.
- Error CODES (strings in `VoiceErrors`, also `http_<status>`, `network`, `bad_size`, `bad_hash`, `extract_failed`, `extract_timeout`, `program_not_in_archive`, `unsupported_platform`, `download_failed`, `model_not_ready`): V3/V4 map them to messages. `no_microphone` and `mic_silent` both get the Windows privacy hint (Settings > Privacy > Microphone).
- Silence trimming threshold: 20 ms windows with RMS >= 300; 150 ms padding; clips under 250 ms after trimming are `too_short`; a clip whose peak is under 8 is `mic_silent`. Gain is only ever raised, max 4x, towards 60 % of full scale.
- Recognizer: `--model-type=nemo_transducer` is tried first. If that run fails or prints no result, it is repeated ONCE without the flag; a success on the retry remembers "no flag" for the process (`SherpaSidecarRecognizer.useModelType`). The 25 s budget is shared by both runs.
- stdout parsing: a JSON line with a `"text"` field first (newer builds), else the line after a line ending in `.wav` (older builds). No result line = `parse_failed`; recognised nothing = "" (the V1 parser then gives Unknown).
- No console window: nothing extra is done. The JDK starts a console program without a window when the app itself has no console (the packaged app has none). Not testable here; if a black window flashes on the laptop, V3/V4 must switch to an own `CreateProcessW` with CREATE_NO_WINDOW through JNA.
- Free RAM unknown (null) = allowed. Below 1.8 GiB (1,932,735,283 bytes) = `low_memory`.
- Program download: tries the asset names in `DesktopVoiceSidecarSpec.ASSET_SUFFIXES` in order (a 404 moves to the next), extracts with `%SystemRoot%\System32\tar.exe` (else `tar`) into `<voice>\program`, finds `sherpa-onnx-offline(.exe)` up to 6 levels down, deletes the archive in `finally`, removes a half-extracted folder on failure. Download progress is a slow curve over an assumed 20 MB (size unknown), 0.85 = download done, 1.0 = extracted.
- **UNVERIFIED:** program version `1.12.21`, the asset names, and whether that runtime runs the Parakeet unified model. The release page could not be read here; only older names (`sherpa-onnx-v1.9.x-win-x64-shared.tar.bz2`) were seen on a Hugging Face mirror. `VERIFIED = false` in the constants object. "Choose the program yourself" must stay a first-class path in V4.
- Model download: files go to `<name>.part`, size must match EXACTLY and SHA-256 must match, then rename (atomic move, plain move as fallback). An already complete, verified file is kept. Each file tries both sources in order. `.part` files are deleted on failure and on cancel; good finished files are never deleted. Not resumable. Default target `DesktopVoicePaths.modelDir()`; V4's "Remove model" may delete only that folder (the files this app downloaded).
- `findExisting` order: owner-chosen path (file, or folder holding it, 3 levels), app folders (the running executable's folder, `user.dir`; 1 level), voice data folder (5 levels), PATH. Accepts `sherpa-onnx-offline.exe` and `sherpa-onnx-offline`, any case.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler in this chat). Every test case was hand-traced against the code (trim arithmetic, gain, WAV header offsets, parser cases, fake-line read counts, retry path). Imports checked, braces/parentheses balanced by script, no `private` type in an `internal` signature, no duplicate top-level names (grep over the repo). Windows-only code: none uses JNA; the only platform check is `DesktopPlatform.isWindows` in `DesktopVoiceSidecar.download` and `DesktopVoicePaths.dataDir`.
Compile watch-list if the build complains: `Result<String>` as a lambda return type in `runInterruptible`; `com.sun.management.OperatingSystemMXBean.freeMemorySize` (JDK 14+ name); `Comparator.reverseOrder<Path>()` in `deleteTree`; trailing-lambda constructor calls `DesktopVoiceRecorder { line }` in tests; SAM lambdas for `FreeMemoryReader` and `VoiceProcessRunner` in tests; `assertEquals(Float, Float, Float)` tolerance overloads.

### Deferred
- A `"text": null` JSON value would be read as the text "null" (not guarded).
- No resume for downloads; no hash for the program archive (none is known).
- The recorder has no input-device chooser (system default only).
- The warm-up read of the encoder file (V3, on hotkey down) is not part of V2.

### Exact next step
V3: `DesktopVoiceHotkey.kt`, `DesktopVoiceController.kt`, `DesktopVoiceOverlay.kt` plus the `// VOICE V3 hook` adapter in `DesktopApp.kt` (grep, never open it whole). Use `DesktopVoiceRecorder.record`, `SherpaSidecarRecognizer.transcribe`, `DesktopVoiceSidecar.findExisting(chosen = <V4 setting>)`, `DesktopVoiceModelLocator.check` and `VoiceErrors` codes. Build once after V2 (the owner's GitHub Actions) before relying on it.

## VOICE V3: hotkey, controller, overlay (NOT compiled)

### Did
- `DesktopVoiceHotkey.kt`: pure `VoiceHotkey(modifiers, vk)` with `describe()` ("Ctrl+Alt+Space"), `parse()`, `isValid`, `DEFAULT`; own minimal JNA `VoiceUser32` (`RegisterHotKey`, `UnregisterHotKey`, `GetMessage`, `PostThreadMessage`, `GetAsyncKeyState`; `Native.load("user32", ..., W32APIOptions.DEFAULT_OPTIONS)` like `WindowsProcessMemoryProbe`, `GetCurrentThreadId` from the already-used `Kernel32`); `DesktopVoiceKeys.isDown(vk)`; `DesktopVoiceHotkey` (own daemon thread + message loop, `MOD_NOREPEAT`, WM_HOTKEY = pressed, 20 ms `GetAsyncKeyState` poll = released, 20 s hold cap, `UnregisterHotKey` on the same thread, `WM_QUIT` posted by `stop()`; `status` ACTIVE / TAKEN / UNAVAILABLE / STOPPED). Everything off Windows reports UNAVAILABLE and does nothing.
- `DesktopVoiceController.kt`: `VoiceSong`, `VoiceActions` (the lambdas), `VoiceControllerConfig`, `DesktopVoiceState` (Idle, Listening, Thinking, Done, Failed(code, message)), `VoiceMessages` (code to sentence, mic privacy hint included), `VoiceSongMatcher` (pure scoring, `WEAK` = 0.75), `VoiceTone` (generated 100 ms tick), `VoiceWarmup` (sequential read to warm the OS cache), `DesktopVoiceRuntime` (temporary settings holder and controller slot), `DesktopVoiceController` (`start`/`refresh`/`stop`, `onPressed`/`onReleased`, `cancel`, `undo`, `choose(i)`, `hotkeyStatus()`).
- `DesktopVoiceOverlay.kt`: `DesktopVoiceOverlayHost()` (called from `Main.kt`) shows an undecorated, transparent, always-on-top, non-focusable `Window` only while the controller is not Idle; `DesktopVoicePill` uses the existing `fluidGlass` look.
- Hooks: `DesktopApp.kt` (`// VOICE V3 hook`, one adapter block after the `DesktopPlayerSettings` SideEffect, ~line 2798: `remember { DesktopVoiceController(...VoiceActions(...)) }` + a `DisposableEffect` that publishes it in `DesktopVoiceRuntime.controller`, calls `start()`, and `stop()`s on dispose); `Main.kt` (`// VOICE V3 hook`, `DesktopVoiceOverlayHost()` as last statement of `desktopApplication()`).
- Tests: `DesktopVoiceHotkeyTest`, `DesktopVoiceControllerTest` (scoring, tone, warm-up, messages, state machine with fake recorder, recognizer, program, model and actions; real coroutines with tiny delays, no mic, window, model, network or hotkey).

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceHotkey.kt`, `DesktopVoiceController.kt`, `DesktopVoiceOverlay.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVoiceHotkeyTest.kt`, `DesktopVoiceControllerTest.kt`. Edited: `DesktopApp.kt` (one block, marked), `Main.kt` (two lines, marked). No Gradle change, no new dependency, `sharedUi`/`shared`/`app` untouched.

### Decisions (no questions allowed)
- **Contract change (minimal):** the plan's `play(title, artist?)` lambda became `search(query): List<VoiceSong>` + `playSong(VoiceSong)`, because the controller must score the results and offer the top three. `VoiceSong.payload` carries the app's own `Song` back. `likeCurrent()` returns Boolean (false = nothing playing). All other lambda names as in the plan.
- **Settings are temporary:** `DesktopVoiceRuntime` holds `enabled` (false), `confirmBeforePlay` (true), `modelDir`, `sidecarExe`, `hotkey` (Ctrl+Alt+Space) as plain `@Volatile` fields, not persisted. V4 either drives these from `DesktopVoiceSettings` or builds the controller config from it (the hook calls the default `DesktopVoiceRuntime.config()`); after changing `enabled` or `hotkey` V4 calls `DesktopVoiceRuntime.controller.value?.refresh()`. With `enabled == false`, `start()` registers nothing and `onPressed()` returns at once.
- **Controller lifetime:** built in `BitChordDesktopApp` with `rememberCoroutineScope()`, so it lives as long as that composition (the window may be hidden in the tray, the composition stays). A display-change window rebuild (`windowEpoch`) re-creates it and re-registers the hotkey. V4 may move ownership to an app-level scope if wanted.
- Actions run on the UI thread (the controller's scope), exactly where a click would run them. Recording, recognising, warm-up and the tick run in `Dispatchers.IO` inside.
- **Hold-to-talk without a hook:** hotkey thread = message loop; the release poll runs on that same thread (a second press cannot arrive meanwhile because of `MOD_NOREPEAT`). `Translate/DispatchMessage` are not bound (not needed for hotkeys).
- **Esc:** polled with `GetAsyncKeyState(VK_ESCAPE)` every 50 ms only while a session is active (Listening, Thinking, showing a result); no global Esc registration. **Second hotkey press** while choices are shown picks the first one; during Listening/Thinking it is ignored (single-flight). **Watchdog:** 30 s per session, bound to the session id so a late watchdog cannot cut off a newer session.
- **"Play X by Y":** query = "title artist" through the app's own `DesktopMusicSources.search(query, SearchFilter.SONGS)` (songs from `TopTrack`/`Track` rows), first 10 hits scored. Score = 0.8 recall + 0.2 precision over words (brackets removed, 1-letter typo accepted for words of 4+ letters); with an artist 0.65 title + 0.35 artist. Best < 0.75 and `confirmBeforePlay` on: the overlay offers the top three (click, or hotkey for the first; 8 s timeout). Off: plays the best anyway.
- **Undo** exists only for a played song: it calls `previous` (restarts the song if more than 10 s in; not an exact inverse, because one-off queues keep the old queue behind the new song). Add-to-playlist, like and playlist play have no undo (no existing removal/unlike function was wrapped).
- **Playlists:** only the local playlists (`playlists` in `DesktopApp.kt`). Add uses the existing `addToPlaylist(playlist)` by setting `playlistTarget` to the current song first. Like only ever LIKEs (`rate(song, LikeStatus.LIKE)` if not already liked), never toggles it off. Play playlist = `playSongs(playlist.songs)`.
- Volume: step 0.1 through `DesktopPlayerHost.onVolumeChange` (same path as the slider; it persists).
- **Privacy:** recognised words exist only in `DesktopVoiceState.Done.heard` for the display time (2.5 s; 8 s with choices; 1.2 s after undo), then the state is `Idle` and `undoAction`/`pendingChoices` are cleared. Logs: "voice command handled", "voice: error=CODE", "voice: hotkey registered", "voice: watchdog returned to idle"; never words.
- The tick plays BEFORE recording starts (so it is not recorded); recording therefore starts about 100 ms after the press.
- Overlay position is computed in the window's own AWT units (screen bounds and window size are in the same units), after a short delay so the window has its size.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler in this chat). Hand-traced against the code: hotkey parse/describe cases, the scoring examples, every state-machine test including the second-press, Esc and watchdog paths, and the parser phrases the tests rely on (read in `DesktopVoiceCommands.kt`). Brace/paren balance checked by script, imports checked, no new top-level name clashes (grep), no `private` type in an `internal` signature, `Modifier.weight` only inside `Row`, no `Modifier.align` used. Windows-only code is behind `VoiceUser32Loader.api` (null off Windows, loaded lazily).
Compile watch-list if the build complains: `Window(... focusable = false, alwaysOnTop = true ...)` parameter names (Compose 1.10.3); `WindowState.size = DpSize(...)` setter; `Memory.close()`/`clear()`; `return` inside `runCatching { }` in `placeNearBottomCentre`; the default argument `{ chosen -> defaultFindModel(chosen) }` (companion function in a constructor default); `scope.launch(Dispatchers.IO) { runCatching { warm(model.encoder) { isActive } } }`; `Outcome(...).also { }` chains in `execute`; test lambdas typed `(Int, () -> Boolean) -> Result<ShortArray>`.

### Deferred
- The overlay `Window` is a JFrame: Windows may flash a taskbar button each time it opens. If seen on the laptop, switch to `DialogWindow` or set `WS_EX_TOOLWINDOW` through `DesktopWindowsFrame`.
- The overlay window is created per command, so "Listening..." can appear a fraction of a second after the tick. A resident hidden window was rejected (the plan says created only when not Idle).
- No account (online) playlists, no "undo add to playlist", no Esc while the tray is the only thing visible other than what the poll sees (it works anywhere, but nothing else cancels).
- `hotkeyStatus()` TAKEN is only readable by polling; V4 shows it after `refresh()`.
- Black console window flash from the recognizer program (see V2 note) still untested.

### Exact next step
V4: `DesktopVoiceSettings.kt` (persisted, same names as `DesktopVoiceRuntime` fields; setting `enabled`/`hotkey` calls `DesktopVoiceRuntime.controller.value?.refresh()`), `DesktopVoiceSettingsUi.kt` (Experimental group, model and program install flow, hotkey capture using `VoiceHotkey.parse`/`describe`, test button, privacy box), README section, owner test checklist. Feed `DesktopVoiceRuntime.enabled/modelDir/sidecarExe/hotkey/confirmBeforePlay` from the settings (or replace `DesktopVoiceRuntime.config()` in the hook). Build once (GitHub Actions) after V4; V3 and V2 have never been compiled.

## VOICE V4: settings, install flow, privacy notes, integration (NOT compiled)

### Did
- `DesktopVoiceSettings.kt`: `DesktopVoiceSettings` (persisted via `DesktopPersistence`, strings/booleans only: `voice_enabled` false, `voice_hotkey` text such as "Ctrl+Alt+Space", `voice_model_dir`, `voice_sidecar_exe`, `voice_confirm_before_play` true; `StateFlow` getters, `set*` setters; `setEnabled`/`setHotkey` call `DesktopVoiceRuntime.controller.value?.refresh()`), `VoiceInstallState`, `DesktopVoiceInstall` (model and program downloads in the object's OWN scope, so leaving the page does not stop them; Cancel does; success fills in the matching setting), `VoiceModelStatus`, `VoiceSettingsLogic` (pure: `modelStatus`, status texts, `sameFolder`, `isManagedModelDir`, `removeModelFiles`, `looksLikeProgramName`, `hotkeyFrom`, `codeOf`, `testCodeOf`, `downloadErrorText`, `percent`).
- `DesktopVoiceSettingsUi.kt`: `DesktopVoiceSettingsRows(rowVisible, toggle)` and `voiceVkFromComposeKey`. Master switch, privacy box (always shown), and, only while the switch is on: Model (status, Find on this computer, Choose folder, Download about 660 MB with progress and Cancel, Remove model / Forget this folder with a confirm dialog, licence line and link), Recognizer program (status, Find, Choose file, Download, unverified-link note and releases link), Hotkey (click-to-capture field, Reset, Esc cancels, "taken" and "Windows only" messages), "Ask before playing when unsure", Test (records 4 s, shows what was heard on the page only).
- Hooks: `DesktopApp.kt` (`// VOICE V4 hook`, one `SettingsGroup("Experimental")` inside the existing ADVANCED section item, next to "Advanced options"); `DesktopVoiceController.kt` (`// VOICE V4 hook`: `DesktopVoiceRuntime` lost its temporary `@Volatile` fields, `config()` now reads `DesktopVoiceSettings`).
- README: "Voice commands (experimental, desktop)" section with the honest limits.
- Tests: `DesktopVoiceSettingsTest` (model status with a fake `check`, folder identity, `removeModelFiles` on empty temp files, program name check, hotkey building and round trip, Compose key to Windows key code, error texts and the mic privacy hint, percent, install starts idle). No microphone, window, model, network or saved settings.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceSettings.kt`, `DesktopVoiceSettingsUi.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVoiceSettingsTest.kt`.
Edited: `DesktopApp.kt` (one marked block, found with grep, never opened whole), `DesktopVoiceController.kt` (the `DesktopVoiceRuntime` object and its comment), `README.md`, `HANDOFF.md`. No Gradle change, no new dependency, `sharedUi`/`shared`/`app` untouched.

### Decisions (no questions allowed)
- **Where:** a group "Experimental" inside the existing ADVANCED category (fewest edits: no new enum value, so `DesktopSettingsCategoriesTest` is unaffected). Search works: the group is a normal `SettingsGroup`, and every block asks the page's own `settingsRowVisible` (passed in as a lambda, because those helpers are `private` in `DesktopApp.kt`).
- **Switch off = almost nothing on screen and nothing running:** only the switch and the privacy text show. The model/program/hotkey/test blocks appear only after the switch is on. Turning it on does NOT scan the disk or download anything by itself; the owner presses a button.
- **Never run a mismatching model:** "Choose folder" only stores a folder when `check` says Ready; a wrong-format folder shows the plan's sentence and is not stored. "Find on this computer" lists Ready folders with "Use this" and wrong-format ones as text only.
- **Remove model:** deletes the four model file names (and `.part` leftovers) only when the folder IS `DesktopVoicePaths.modelDir()` (the one this app downloads into), then the folder if empty. For a folder the owner chose it is "Forget this folder": the setting is cleared, nothing is deleted.
- **Hotkey capture:** while capturing, the live hotkey is released with `controller.stop()` (otherwise pressing the current combination would start a recording) and re-registered with `refresh()` afterwards. A combination needs at least one of Ctrl/Alt/Shift/Win plus a key that `VoiceHotkey.keyName` knows (letters, digits, F1 to F12, Space, Enter, Tab, Backspace, arrows, Home/End/PageUp/PageDown, Insert, Delete). Esc without modifiers cancels. "Taken" is shown by polling `hotkeyStatus()` once a second while the block is on screen.
- **Test button** uses `DesktopVoiceRecorder` and `SherpaSidecarRecognizer` directly (not the controller). The text lives in `remember` state and is cleared in `onDispose` when the page is left. Errors go through `VoiceMessages.forCode` (so the Windows microphone privacy hint appears for `no_microphone` and `mic_silent`; a static hint line is also under the button).
- **Program path rule:** "Choose file" accepts only a file named `sherpa-onnx-offline(.exe)` (any case).
- The Experimental group and the Linux build: the controller still does nothing off Windows; the page works but the hotkey cannot register (no message is shown for STOPPED).
- The download link of the recognizer program is still UNVERIFIED (`DesktopVoiceSidecarSpec.VERIFIED = false`); the page says so, links the releases page and keeps "Choose file" next to it.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler in this chat). Hand-checked: brace/paren balance by script, imports (only `getValue`/`setValue` look unused by text and are needed for `by`), no duplicate top-level names (grep over `desktopApp/src`), no `private` type in an `internal` signature (`VoiceTestPhase` is private and only used inside private functions), `Modifier.weight` only in `Row`, no `Modifier.align`, Windows-only code none (no JNA in V4). Every test case hand-traced.
Compile watch-list if the build complains: `produceState<VoiceModelStatus?>(null, a, b, c) { }` overload; `Key.Spacebar/MoveEnd/MoveHome/DirectionLeft/Insert/Delete/CtrlLeft/ShiftRight` constants and `isMetaPressed`; `getOrElse { return Result.failure(it) }` inside the private suspend `runVoiceTest`; trailing-lambda `check` argument of `VoiceSettingsLogic.modelStatus` in tests; `DesktopVoiceSidecar.deleteTree` used from the test; the `rowVisible` lambda literal in the `DesktopApp.kt` hook being inferred as `@Composable`; `buildMap { put(Key, Int) }` with the declared `Map<Key, Int>` type.

### Deferred
- `setEnabled`/`setHotkey` call `controller.refresh()` on the UI thread; registering the hotkey waits briefly for the message-loop thread. If a freeze is seen on toggling, move `apply()` to a background dispatcher.
- No online (account) playlists, no undo for add/like (V3 deferrals unchanged).
- No resume for downloads; no hash for the program archive.
- A hidden taskbar flash of the overlay window and a possible black console flash of the recognizer program (V2/V3 notes) are still untested.
- The controller is still built in `BitChordDesktopApp` (a window rebuild re-creates it); the page reaches it through `DesktopVoiceRuntime.controller`.

### Exact next step
Run the owner's GitHub Actions build (V2, V3 and V4 have never been compiled) and fix compile errors from the watch-lists in the V2, V3 and V4 sections. Then run the checklist below.

### Owner test checklist (Windows laptop)
1. Settings > Advanced > Experimental: the switch is off; nothing else shows except the privacy text. Search "voice" finds it. Check `%LOCALAPPDATA%\Muses\voice` does not exist yet.
2. Turn the switch on: Model "Not installed", Recognizer program "Not found" (unless one is next to the app or on PATH). Still nothing downloaded.
3. Model: press "Find on this computer". With the Handsy folder: a folder holding `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` gives "Use this"; a folder with `decoder_joint.onnx` gives the "different format" sentence. Or press Download (about 660 MB), watch the progress, press Cancel once and check the `.part` file is gone.
4. Program: "Find", "Choose file..." (pick `sherpa-onnx-offline.exe`) or "Download" (link unverified).
5. Hotkey: click the field, press `Ctrl+Alt+V`: it shows "Ctrl+Alt+V". Reset returns to Ctrl+Alt+Space. Try a combination another program owns: "taken" message.
6. Test: press "Record 4 seconds", say "play bad guy by billie eilish": the words show on the page; leave the page and come back: they are gone.
7. Hide Muses to the tray, hold the hotkey, say "play bad guy by billie eilish", release: the overlay shows, the song plays, the overlay disappears. Check `%TEMP%` for leftover voice folders (there must be none) and the log for recognised words (there must be none).
8. Switch off: the hotkey no longer does anything. Restart the app with the switch on: the hotkey works at once.

### VOICE V4 CI fix 1 (NOT compiled)
- CI error in `DesktopVoiceSettingsTest.kt` lines 78 and 88: `seen += it` where `seen` is `ArrayList<Path>` and `it` is a `Path`. A `java.nio.file.Path` is itself an `Iterable<Path>`, so Kotlin read `+=` as `seen = seen + it` ("val cannot be reassigned", "List<Path> but ArrayList<Path> expected"). Fixed with `seen.add(it)`. Same style change in `DesktopVoiceRecognizerTest.kt` (`seen.add(command)`), not needed but safe.
- Rule for later edits: never use `+=` / `plus` with a `Path` on a collection; use `.add(...)`. Main code already does (`dirs.add(...)`, `out.add(...)`). All other `+=` in the voice files were checked: strings, ints, lists of strings, pairs.
- Only 4 compile errors were reported and all were this one cause, so the other test files compile; the next CI step will run the tests, where assertion failures may still appear.
- The warning `DesktopVoiceSettingsUi.kt:290 Condition is always 'true'` is `!DesktopVoiceSidecarSpec.VERIFIED` (a const false). Harmless; it goes away when the link is verified and the flag flips.

## VOICE V5: broader natural phrasing, live playlist resolution, capture fixes and model-format diagnosis

### Did
- Reworked `DesktopVoiceCommandParser` so action recognition is independent of whether the playlist catalogue is populated at that exact moment. Explicit playlist requests now remain `PlayPlaylist(name)` intents; `add this to <name>` remains `AddToPlaylist(name)`. If resolution fails, the controller explains that the playlist could not be found instead of reporting `Didn't catch that`.
- Broadened conversational play openings and action phrasing: e.g. `I'm in the mood for ...`, `I feel like ...`, `I'd love to hear ...`, `I could go for ...`, `I'm up for ...`, `How about playing ...`, `put on`, `throw on`, `queue up`, and related forms. Added `SearchAndPlay` for broad requests such as `play something upbeat`; matching results are offered for confirmation by default.
- Added live playlist reads in `DesktopApp.kt`. The long-lived remembered controller no longer relies on the first, possibly-empty library snapshot. Voice lookup now sees local playlists and browsable playlists from the current account library. Existing add actions handle local lists and owned account playlists; playback of account playlists uses the existing browse/continuation APIs. No shared library or playback engine logic was rewritten.
- Improved track matching: very close top song matches are treated as ambiguous and can be presented as choices rather than silently selecting the first result.
- Corrected recording order: open the microphone first, emit the ready tick only after capture is open, then flush the capture buffer so the tick is not transcribed. Playback ducking is applied to Muses output only while recording and restored afterwards, including error/cancel cleanup.
- Improved microphone handling: capture at a supported native input rate and resample to 16 kHz mono in memory; adaptive silence gating and longer pre/post roll reduce the risk of clipping low-level opening consonants.
- Updated the Sherpa-ONNX Windows sidecar metadata to the official v2.0.1 Windows x64 shared Release asset naming and kept a visible link to the upstream release in Settings.
- **Important new diagnosis about Handy:** the current Handy documentation publishes Parakeet Unified EN 0.6B as a single 731 MB Q8_0 `.gguf` file. Muses' existing Sherpa runner requires four separate `.onnx`/`.txt` files. These are different exported formats and runtimes, even though they are derived from the same upstream Parakeet model family. The owner's screenshot reports 697 MB, consistent with the published 731 MB decimal / ~697 MiB GGUF size. This is a strong format clue, not proof of the exact local file; the owner should inspect Handy's model file to confirm. Handy's current project docs also describe Silero VAD and transcribe.cpp; Muses does not yet reproduce that exact audio/inference pipeline.
- Added detection for Handy's Parakeet GGUF file. The model finder now reports it as a known incompatible format and explains that the current Sherpa recognizer needs the Sherpa-ONNX export. Corrected folder-priority naming from the previous `handsy` spelling to `handy`.
- Updated README limits and voice description; kept the feature marked experimental until Windows laptop testing is complete.

### Files
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceCommands.kt`.
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceController.kt`.
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceRecorder.kt`.
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceModel.kt` (GGUF detection and Handy path priority).
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceDownloads.kt` (Sherpa v2.0.1 release metadata).
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceSettingsUi.kt` (always-visible upstream release link).
- Edited `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt` (live playlist adapter, account-playlist action adapters, playback ducking hook; every added section marked `// VOICE V5 hook`).
- Edited tests: `DesktopVoiceCommandsTest.kt`, `DesktopVoiceRecorderTest.kt`, `DesktopVoiceModelTest.kt`, and `DesktopVoiceDownloadsTest.kt`.
- Edited `README.md`; appended this section to `HANDOFF.md`.
- No Gradle dependencies or version changes. No edits to `sharedUi`, `shared`, or `app`.

### Decisions
- Keep the ASR model/runtime and the command interpreter separate. Do not use an LLM or network service for command interpretation in this version; all action execution remains typed and deterministic. The expanded phrasing layer improves coverage but is not a general-purpose conversational model.
- The current Muses recognizer remains Sherpa-ONNX with the four-file Parakeet export. Handy's GGUF file is not passed to the Sherpa executable. The finder explains the mismatch rather than attempting to run an incompatible file.
- Do not add Handy's Silero VAD model or transcribe.cpp runtime in this cycle. Handy's VAD is a separate model/pipeline component, not a setting that can simply be enabled on Sherpa's CLI. First compare raw recordings and recognised text on the owner's Windows machine; consider a GGUF/transcribe.cpp backend or a VAD stage only after that comparison.
- Long-audio chunking is deferred: the Muses interaction is push-to-talk with a 12-second cap and the Sherpa Parakeet Unified export is explicitly non-streaming. Chunking short utterances without an evidence-based need would add complexity and may split song/artist names.
- Playlist intent is no longer rejected just because the initial library snapshot is empty. The controller resolves against live state at execution time. Unresolved playlists are not played or edited. Playlist-specific interactive candidate selection is deferred; ambiguous names currently fail safely rather than selecting arbitrarily.
- Broad mood/search requests use Muses' existing song search and candidate-confirmation flow; they do not invent unsupported playback actions.
- The 22% duck level is a starting point; tune it only after owner testing with speakers/headphones. Only the Muses playback engine's own output level is affected; Windows master volume and persisted user volume are not changed.

### Verified
- Parser standalone Kotlin harness: **20/20 targeted cases passed**, including `Play my workout playlist`, `I'm in the mood for my workout playlist`, `add this to jazz`, title/artist splitting, and broad discovery intent.
- Recorder standalone Kotlin harness: passed adaptive pre-roll, 48 kHz to 16 kHz resampling, capture-ready/tick/flush ordering, line close, and record-length checks.
- Controller standalone Kotlin harness with fake recorder/recognizer/actions: passed missing-snapshot playlist intent dispatch, action result, and duck/restore sequence.
- The project model locator and full desktop application were **NOT Gradle compiled**. The actual Windows audio device, Handy-vs-Muses same-audio comparison, account-library playlist playback, hotkey/overlay, and sidecar download have **NOT** been tested on the owner's laptop.
- Full Gradle build could not be run in this environment: the wrapper attempted to download Gradle 8.11.1 and failed DNS resolution for `services.gradle.org`. No claim of a successful app build is made.

### Deferred
- Inspect the actual Handy model file on the owner's machine. The screenshot's 697 MB display strongly suggests the single Q8_0 GGUF export, but only the file extension/name can confirm it.
- Compare one identical Muses-recorded WAV in Handy and Muses. This isolates capture/preprocessing from runtime/export differences.
- Consider an optional GGUF/transcribe.cpp recognizer backend (or align Muses to Handy's exact backend) only after proving the current Sherpa pipeline is the accuracy bottleneck. This would be a deliberate backend decision, not a drop-in model-file swap.
- Consider Silero VAD after the identical-audio comparison. Handy's documentation describes Silero VAD; Muses currently uses adaptive silence trimming, not that model.
- No multi-turn conversational entity memory yet (`play X` then `add it to ...`); no playlist selection carousel; no configurable duck percentage; no offline intent model; no speaker/noise-specific calibration.
- End-to-end Windows testing remains outstanding. The README correctly keeps the feature experimental.

### Exact next step
Run the GitHub Actions Windows desktop build on this zip and fix any compile failures. Then, on the laptop, open Handy's model folder and check whether the selected model is `parakeet-unified-en-0.6b-Q8_0.gguf` (or another `.gguf`) or the four Sherpa files. Next test `Play my workout playlist`, `I'm in the mood for my workout playlist`, `add this to workout`, and `play bad guy by billie eilish`; check capture starts before the tick, first words are present, music ducks/restores, and no temp files remain. Finally compare one identical WAV in both apps before deciding whether to change the ASR backend.

## VOICE FIX X1: reasoned results for add/play playlist, honest messages, safer library edits (NOT compiled)

### Did
- **Result type** `VoicePlaylistResult` (new `DesktopVoiceLibraryOps.kt`): `Done(playlist, source, song, alreadyThere, undo)`, `NotFound`, `NotEditable(name)`, `Ambiguous(options)`, `NothingPlaying`, `LibraryNotLoaded`, `ApiFailed(code)`. `VoiceActions.addCurrentToPlaylist` and `playPlaylist` now return it (no `Boolean` left for these two; `likeCurrent` and the others are unchanged).
- **Controller** (`// VOICE FIX X1 hook`): `execute` calls one private `playlistOutcome(action, name)`; `VoicePlaylistMessages.forResult` maps each result to the overlay sentence ("X isn't one of your editable playlists", "Nothing is playing", "Your library is still loading. Try again in a moment", "Added <song> to <playlist>", "<song> is already in <playlist>", "That playlist is empty", ...). The old `playlistMiss` and the controller's own `nowPlayingText() == null` check are gone (the adapter decides).
- **Adapter fix** (`DesktopApp.kt`, `// VOICE FIX X1 hook`, helpers just before the V3 hook, because local funs cannot see `addToPlaylist` earlier): `voiceListCandidates()` builds ONE candidate set for both actions: local lists (editable), shelf lists with a `browseId` tagged `USER` + editable when `DesktopSearchClient.userPlaylists` contains them, otherwise `SHELF` + playable only. `addCurrentToPlaylist` looks in that whole set but edits only an editable match; a playable-but-not-editable match returns `NotEditable(name)`.
- **Library wait:** if there is no match AND the library is not `Success`, or is stale, or (signed in) the shelf is empty, the adapter calls the app's own `reloadLibrary()` (guarded: never while already `Loading`) and waits up to 4 s (`VoiceLibraryWait`, 100 ms polls); still not there gives `LibraryNotLoaded`, otherwise it resolves once more.
- **Current song:** `selectedSong`, the same value the player sheet's own add and like buttons use (`val current = selectedSong` in the player sheet, `playlistTarget = it` for the dialog). `null` gives `NothingPlaying`.
- **Safer edits:** `VoiceConfidence` (one place): `PLAY_MIN 0.72`, `PLAY_MARGIN 0.05`, `EDIT_MIN 0.85`, `EDIT_MARGIN 0.10`, wait 4 s, 3 options max. For an edit, a match under 0.85 is never acted on or named: the owner gets `Ambiguous([editable options])` or `NotFound`. A runner-up within the margin always gives `Ambiguous` (also when the winner is not editable and the rival is), never a silent pick. Two lists with the same title are shown as "Gym (on this computer)" / "Gym (your account)".
- **Undo:** account playlist add keeps the `setVideoId` YouTube returns and Undo calls the existing `DesktopSearchClient.removeFromPlaylist(playlistId, listOf(setVideoId to videoId))`. **No Undo** for a local playlist (no existing remove action was found for local lists) and none when YouTube returns no `setVideoId` (Innertube says that can happen). Nothing new was written for removal.
- Matching names: both sides go through the parser's existing `playlistScore` (it already drops emoji and punctuation, number words to digits) after a tiny `"&" -> " and "` step (`VoiceListResolver.spokenForm`; X2 replaces it). "work out" vs "Workout" scores 0.875, above `EDIT_MIN`.
- **Privacy unchanged:** logs only `voice: playlist action=add|play result=<kind> source=local|user|shelf|none` (`VoiceListLog`, tested to contain no names). The names exist only in the result shown by the overlay. Nothing is stored.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceLibraryOps.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVoiceLibraryOpsTest.kt`.
Edited: `DesktopVoiceController.kt` (VoiceActions two signatures, `execute`, new `playlistOutcome`, removed `playlistMiss`), `DesktopApp.kt` (one helper block, the two adapter lambdas replaced; found with grep, never opened whole), `DesktopVoiceControllerTest.kt` (fake returns results; 5 new tests), `HANDOFF.md`, `MUSES_VOICE_FIX_PLAN.md` copied into the repo root. No Gradle change, no dependency, `sharedUi` / `shared` / `app` untouched.

### Decisions (no questions allowed)
- **`MUSES_VOICE_NOTES.md` was NOT in the zip** (only `MUSES_VOICE_FIX_PLAN.md` was attached). I worked from the plan, the last three HANDOFF sections and the code. If the notes list a failure case not covered here, it is not done.
- Refresh only when there is no match and the library may be out of date (not on every command), so our own edits (`libraryStale = true` after an add) do not make the next command wait 4 s.
- `ApiFailed` codes are fixed words (`api`, `browse`, `empty`, `track`), never server text. "`empty`" is reused for a playlist with no songs; "`track`" for a non-YouTube track aimed at an account list (the player dialog's `canUseAccount` rule).
- Already-in-list (local only) returns `Done(alreadyThere = true)`: honest message, nothing changed, no Undo.
- Shelf lists are never edited by voice, even if they might be editable on YouTube: only the parser's own `userPlaylists` set counts, same as the player's dialog.
- Contract change for X2: `VoiceActions.addCurrentToPlaylist/playPlaylist` return `VoicePlaylistResult`; `VoiceListResolver.resolve(query, candidates, forEdit, score)` is the seam where X2's matcher plugs in (inject its scorer).

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler here). Hand-checked: brace/paren balance by script on every touched file; no duplicate top-level names (grep over the repo); no remaining `Boolean` in the two actions; `when` over the sealed results is exhaustive; smart casts only on local `val`s; no `+=` on `Path`; every new test hand-traced (scores for "work out", emoji, "&", margins, the fake clock).
Compile watch-list if the build complains: callable references to local funs (`::voiceLibraryReady`, `::voiceRefreshLibrary`) in `resolveVoiceList`; the parenthesised lambda assigned to `val undo: (() -> Unit)?`; `when (val pick = ...)` with a `null ->` first branch then `toResult(pick)` (takes `VoiceListPick?`); `mapTo(HashSet()) { it.playlistId }`; `assertIs<T>()` return value used inline in tests; test functions written as `= runBlocking { ... }` ending in an assertion (must return Unit).

### Deferred
- Playlist choice by voice ("the first one") is X2 (`VoiceSession`); `Ambiguous` currently only names the options.
- No Undo for local playlists or when YouTube omits the entry id (see above).
- Live-device checks not done: adding to an owned playlist, a saved-only playlist (expect "isn't one of your editable playlists"), a cold library, mis-heard names.

### Exact next step
Build in GitHub Actions and fix compile errors from the watch-list, then run X2 on this zip (it starts with `heldout_phrases.tsv`).


## VOICE FIX X2 PART A: normaliser, phonetic key, index, matcher, session, held-out phrases (NOT compiled)

### Did
Half of X2, the pure-logic half, so another agent can wire it (Part B instructions: `MUSES_VOICE_X2_PART_B_CODEX.md`, repo root).
- **Held-out phrases FIRST (plan item 1):** `desktopApp/src/test/resources/voice/heldout_phrases.tsv`, 179 phrases (phrase, expected action, arguments): every command in several phrasings, filler, mishearings ("work out", "billy eilish essentials"), numbers ("volume to forty"), self-repair, follow-ups ("the first one", "no the other one", "add it to that one") and 23 phrases that must be `Unknown`. `DesktopVoiceEvalTest` runs the REAL parser over it and prints pass / MISS / WRONG-ACTION rates, asserts wrong-action <= 3 percent, and prints every failing phrase. Written before reading the parser's regexes in detail, then checked against them only to avoid impossible expectations.
- **Normaliser + phonetic key + alias table** (`DesktopVoiceText.kt`): lower case, accents off, emoji and punctuation dropped, `&` is "and", number words to digits ("twenty one" is 21), ignore the/my/playlist/song, aliases as data (`VoiceAliasTable`: "work out"="workout", "billy"="billie", ...). `VoicePhonetic.key` is a compact Metaphone-style key in pure Kotlin (not Soundex); I ported it to Python and checked the keys used in tests (billie/billy = BL, eilish/ilish = ALX, night/knight = NT, workout = WRKT).
- **Library index** (`DesktopVoiceIndex.kt`): `VoiceIndexBuilder.build` precomputes tokens, phonetic keys and compact keys once. `VoiceIndexHolder` rebuilds only when the version changes (`get(version){...}`) or when the content signature changes (`getFor(inputs)`); `current()` never builds; `clear()` for voice-off. Same kind+id appears once.
- **Matcher** (`DesktopVoiceMatcher.kt`): token overlap + edit distance + phonetic similarity (capped at 0.93 so an equal text match wins), artist blended in for "X by Y" (30 percent), ranked candidates, margin over the runner-up. Execute only when top >= HIGH and margin >= MARGIN, otherwise up to 3 candidates, below LOW nothing. All numbers in `VoiceMatchThresholds` (LOW 0.62, play 0.80/0.08, edit 0.90/0.15, cross-kind 0.74/0.12). Type priors: explicit words win, "my X" leans playlist (+0.05), "X by Y" is a song, destination of add-to-playlist is searched as PLAYLIST only (caller passes `kinds`), a bare "play X" close to both a playlist and a song is `Candidates`.
- **Session** (`DesktopVoiceSession.kt`): `VoiceSession` in memory, 2 minute TTL, injectable clock, last played / candidates / playlist, `choose`, `other`, `clear`; `toString` redacted everywhere. Plus pure helpers: `VoiceChoicePhrases` ("the first one", "number two", "no the other one", "that one"), `VoiceSelfRepair` (drop um/uh and conservative "like"; keep the text after actually / i mean / scratch that, and after no / wait / sorry only when a command or "the/my" follows, so "No Scrubs", "Sorry", "Wait for it", "Like a Prayer" survive), `VoiceNumbers.volumePercent`.
- **Unit tests** (fake clock, fake index): `DesktopVoiceMatchingTest.kt`, `DesktopVoiceSessionTest.kt`. All values hand-traced.

### Files
New: `DesktopVoiceText.kt`, `DesktopVoiceIndex.kt`, `DesktopVoiceMatcher.kt`, `DesktopVoiceSession.kt` (main); `DesktopVoiceEvalTest.kt`, `DesktopVoiceMatchingTest.kt`, `DesktopVoiceSessionTest.kt`, `src/test/resources/voice/heldout_phrases.tsv`; `MUSES_VOICE_X2_PART_B_CODEX.md` (root). Existing files NOT touched in Part A (no parser, controller, adapter or X1 change yet), so no `// VOICE FIX X2 hook` marks exist yet.

### Decisions (no questions allowed)
- `MUSES_VOICE_NOTES.md` is still absent from the zip; worked from the plan only.
- `Ambiguous(options)` as a `VoiceCommand` is NOT needed: ambiguity is an outcome (`VoiceMatchOutcome.Candidates`, X1's `VoicePlaylistResult.Ambiguous`). New commands for Part B: `SetVolume(percent)`, `Choose(number)` (1-based), `ChooseOther`, `AddToLastPlaylist`.
- Playlists keep X1's `VoiceConfidence` (it has a margin and tests); `VoiceMatchThresholds` is used for songs and artists. Part B feeds `VoiceMatcher.scoreText` into the resolver's `score` seam as `maxOf(old, new)`.
- The eval never lowers the bar for itself: `describe` falls back to the class name for objects; Part B must add `SetVolume` and `Choose` arguments to it.
- Tough phrases on purpose: `turn on the lights`, `the first one hundred songs`, `no scrubs`, `play sorry by justin bieber`, `no shuffle`.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler here). Done: brace/paren balance by script on all 7 new files; no duplicate top-level names (grep); the phonetic keys by a Python port; every test value hand-traced (scores for "work out", "billy eilish", "bily eylish" = 0.93, wrong artist stays below HIGH, margins, session TTL boundary at exactly 120000 ms, self-repair cases).
Compile watch-list: `VoiceSession.fresh<T>` over private `Stamped<T>`; smart cast on `hint.kind`; `assertIs` return values; `sumOf` on `Double`; `when` statements with `if` branches and no `else`.

### Deferred (all of Part B)
Parser front door (self-repair, choose, volume, add-to-last-playlist), new `VoiceCommand` cases, controller session wiring, `VoiceActions.setVolume` / `libraryIndex`, matcher in the controller's song search, index fill in `DesktopApp.kt`, resolver scorer change, controller tests, the eval iteration to <= 3 percent wrong-action. Exact steps: `MUSES_VOICE_X2_PART_B_CODEX.md`.

### Exact next step
Hand `Muses.zip` and `MUSES_VOICE_X2_PART_B_CODEX.md` to Codex: start at B0, then B1 (baseline eval), then B2 onward.

## VOICE FIX X2 PART B

<!-- // VOICE FIX X2 hook -->

### Did
- Wired the Part A normalizer and self-repair into the parser before its existing pipeline. Added `SetVolume(percent)`, `Choose(number)` (1-based), `ChooseOther`, and `AddToLastPlaylist`. The eval now compares arguments for the two data commands.
- Added the controller-owned, two-minute `VoiceSession`; it remembers successful songs/playlists and the songs or playlists offered as candidates. Voice-off and controller disposal clear it. `DesktopApp` clears the in-memory library index on voice-off and exit.
- Added absolute volume through the existing player volume state, and used the matcher for transient song search results and the loaded library index. The index is filled on `Dispatchers.Default` from playlists and the songs/credited artists already present in `LibraryPage`; command-time code only reads `current()`.
- Changed the playlist resolver's default scorer to `maxOf(old parser score, VoiceMatcher.scoreText(...))`, preserving the former score as a floor. Kept `VoiceConfidence` and `VoiceMatchThresholds` separate.
- Tightened `turn on` and `switch to` so unrelated text such as “turn on the lights” returns `Unknown`. Moved the existing “play some music” resume case ahead of the generic “some” search wrapper.
- Added controller tests for absolute volume, choosing the first/other song, add-to-last-playlist, and clearing session on voice-off. The held-out phrase file was not edited and no existing expectation was changed.

### Files
- Edited only under `desktopApp`: `DesktopApp.kt`, `DesktopVoiceCommands.kt`, `DesktopVoiceController.kt`, `DesktopVoiceLibraryOps.kt`, `DesktopVoiceControllerTest.kt`, and `DesktopVoiceEvalTest.kt`.
- Appended this handoff section as required. No Gradle file, dependency, version, `sharedUi`, `shared`, or `app` file was edited.

### Decisions
- `Ambiguous(options)` is an outcome, not a `VoiceCommand`; plan item 7 listed `Ambiguous(options)` as a command, but X1 already has `VoicePlaylistResult.Ambiguous` and Part A has `VoiceMatchOutcome.Candidates`.
- For `turn on` / `switch to`, accept only when the rest begins with `my`, `the`, or `some` and includes `playlist`, `mix`, `mixtape`, `collection`, `music`, or `radio`, or when the request already has the playlist preference. Everything else is `Unknown`.
- The new `VoiceActions.setVolume` and `VoiceActions.libraryIndex` parameters are last and defaulted. The resolver's default scorer is the maximum of the previous parser score and the matcher score. `VoiceSession` is also a last, defaulted controller constructor parameter for fake-clock tests.
- `LibraryPage` already contains `likedSongs`, `librarySongs`, and song artist credits, so those available songs and credited artists are indexed along with playlists. No broader catalog is fetched or built during a command.
- The stretch for choosing between same-title playlists by exact ID was skipped. Duplicate playlist choices can be spoken, but the existing adapter actions accept names, so an exact source/ID selection is not yet wired.

### Verified
- Baseline eval: **could not run**. The requested Gradle wrapper test process produced no output and stalled; it was stopped. Final eval: **could not run** for the same reason. Baseline and final metrics are unavailable.
- **NOT compiled. Tests NOT run.**
- Hand-reviewed the Part A files and all touched Kotlin files for imports, nullability, exhaustive `when` branches, duplicate command names, and private types in internal signatures. `VoiceCommand.execute` handles every case; the eval's `else` handles the new object commands and has explicit data-command branches. The protected held-out phrases remain unchanged.
- The required disk-API grep over `DesktopVoiceText.kt`, `DesktopVoiceIndex.kt`, `DesktopVoiceMatcher.kt`, and `DesktopVoiceSession.kt` found no `File`, `Files.`, `readText`, or `writeText` references.

### Deferred
- Run the held-out eval and iterate until wrong-action is at most 3%, or record every remaining failure. No baseline/final rate can be claimed without that run.
- Compile and run `./gradlew :desktopApp:test --tests '*Voice*'` in an environment with a working JDK/Gradle wrapper; address compiler/test failures.
- Playlist ambiguity selection by exact ID/source (Part B8).
- The index covers loaded liked/library songs and their credited artists, not the full YouTube catalog or every artist/album shelf item.

### Exact next step
Run `./gradlew :desktopApp:test --tests '*Voice*'` with a working JDK, then inspect all `[WRONG]` lines before `[MISS ]` lines from `DesktopVoiceEvalTest`; fix code and rerun until wrong-action is at most 3%. Check the duplicate-playlist choice path before claiming Part B8.


## BUDS B1: earbuds protocol core, pure Kotlin (NOT compiled)

### Did
- Frame builder and streaming parser (`BudsFrames`, `BudsFrameParser`): LEB128 `TotalLen` (short and long form), arbitrary chunking (split or joined), resync on `0xAA`, impossible lengths rejected (below 4 or above 2048), slice frames skipped whole, a declared payload longer than the bytes present is cut short, never throws. Payload starts at index 9 (body offset 7 after the length field).
- Command / event / feature numbers as named constants (`BudsIds`), each traced to a PROTOCOL.md section in its comment.
- Decoders (`BudsParsers`): product id, broadcast codes, battery (merge-friendly: unknown or zero levels become null), wearing (offset 0/1 tolerance, exact length preferred), ANC notify mask (only when bytes 1-2 are `01 01`), ANC reply mask, `decodeNoiseMask` (a mask test, never an enum), feature values (reply and `0x0503` push), game-mode push, ack status.
- Builders: handshake, product id, broadcast query, register pushes (count first), status query, ANC query, battery, wearing, set ANC (Adaptive is four bytes `01 01 00 08`), feature switch, reply to a request from the buds.
- `BudsSession`: init at 300 ms then 200 ms apart (handshake, product id, broadcast, register, feature status, ANC read, battery, wearing), applies pushes and replies, polls battery + feature status every 60 s (wearing never polled), `setNoiseMode` (ack 00 then ANC read-back, true only if a fresh observation matches, about 2 s bound), `setLowLatency` (ack, then up to 0.9 s for a push, then a read; about 2.3 s worst case), closed transport (`read` = -1) or failed write sets `connected = false` and stops. `delayFn` and `nowMs` are injectable (virtual time).
- `DesktopBudsNames.isLikelyCompatible`: data-driven regex list over a normalised name.
- `docs/third_party/BUDS_CREDITS.md` and one README line (see Decisions).

### Files
New (all under `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`): `DesktopBudsProtocol.kt`, `DesktopBudsSession.kt`, `DesktopBudsNames.kt`.
New tests (`desktopApp/src/test/kotlin/com/music/bitchord/desktop/`): `DesktopBudsProtocolTest.kt`, `DesktopBudsSessionTest.kt`, `DesktopBudsNamesTest.kt`.
New docs: `docs/third_party/BUDS_CREDITS.md`; `MUSES_BUDS_PLAN.md` copied into the repo root (it was attached, not in the zip). Edited: `README.md` (one line), `HANDOFF.md`.
No existing Kotlin file was touched, so there are no `// BUDS B1 hook` marks. `DesktopApp.kt` was never opened. No Gradle file, dependency or version changed; `sharedUi`, `shared`, `app` untouched. No Windows, Compose, JNA or file import in the B1 files (only `kotlinx.coroutines`, `java.util.concurrent`, `java.util.Locale`).

### Decisions (no questions allowed)
- **LEB128 follows the source, not the plan.** The plan says "for >= 128 subtract 1 ... decode adds 1 back". QuickBuds `docs/PROTOCOL.md` section 2 says that does NOT fit and gives `AA 83 01` for 131 and `AA 81 02` for 257, and `core/src/wire.rs` (6.1.1) uses standard LEB128. I implemented standard LEB128. Short frames are identical either way. Tests pin 131 -> `83 01`, 257 -> `81 02`.
- Battery and wearing payloads are PAIRS `[part][value]`, not triples as the plan text says. A reply carries a leading status byte `00`; a push does not. Parsers try offset 0 and 1 and keep the exact-length plausible one.
- Level `0`, or above 100 after `& 0x7F`, means "not known": that part is null and a merge keeps the old value (PROTOCOL.md section 13). A zero lid-close push clears nothing; it only sets `inEar = (false, false)`.
- `productId` is the id the vendor lists use (the 3 bytes little endian read as hex: `00 14 54 06` -> `065414`). Not a device address.
- `supportedNoiseModes`: empty until ANC is offered (event 03 in the broadcast codes) or a mode is observed; then OFF, NOISE_CANCELLING, TRANSPARENCY; ADAPTIVE is added only after the buds have reported it. Reason: no listed fact says which models have Adaptive. `setNoiseMode(ADAPTIVE)` is still allowed and is judged by the read-back.
- Registered events = {battery 1, wearing 2, ANC 3, game mode 5} intersected with what the buds offer; if the offer is unknown or empty, `01 02 03`. Event 05 is subscribed only when offered.
- Feature status asks only ids `05 06 28` (short list: a long list dropped one model's link, PROTOCOL.md section 16). Low latency id is `0x28` if listed, else `0x06` (the plan's rule).
- Replies are matched by command only (not seq): some models answer with their own seq.
- Our request seqs run 1..254 and wrap; `0xFF` is a push.
- Plan extension from PROTOCOL.md section 11: the session answers the buds' own requests `0x0500` (time, `00` + unix seconds u32 LE), `0x050C` (`00 01 00`), `0x051C` (`00 00 00`), `0x051D` (`01`), because some firmwares reset the link when unanswered. Nothing else is answered. The time sent is the PC clock; it goes only to the buds.
- `BudsState.connected` becomes true on `start()`, false on close, read -1, or a write failure.
- `BudsSession(transport, scope, delayFn, nowMs, debugLog)`: `debugLog` stays null unless B3 passes a logger when the debug switch is on; it then gets hex of control frames only (0x0403/0x0404/0x010C/0x010D/0x0503 and ANC / game-mode pushes), never the handshake, never names or addresses.
- The session runs under a child `SupervisorJob` of the given scope: `close()` cancels only the session and closes the transport; the caller's scope stays alive.
- Credits file and README line were added now (the plan lists them under B3) so GPL attribution ships with the first code that uses the protocol. B3 only needs to check them. No commit hash exists (the zip has no `.git`): the file records "QuickBuds 6.1.1 zip, PROTOCOL.md dated 2026-11-12, header says 5.4.5". The addresses of OPPO-Pods-Win and DevPods are the plan's names and are marked unchecked.
- Tests use `runBlocking` and a small virtual clock inside the test file (no `kotlinx-coroutines-test`, which is not a dependency).

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler here). Done: brace/paren/bracket balance by script on all 6 Kotlin files; grep: no duplicate top-level names in the repo; import list checked (no Windows/Compose); expected frame hex and LEB values recomputed with an independent Python port (worked examples from the plan byte for byte); every parser and session test traced by hand, including the virtual-time schedule (init writes at 300, 500, ..., 1700 ms; polls at 61 700 and 61 900 ms).
Compile watch-list if the build complains: `when (f.cmd)` branches written as `BudsIds.X or BudsIds.REPLY_BIT`; `private class ListAt` inside `BudsParsers` used in a private function signature; `debug?.let { list -> { s: String -> list.add(s); Unit } }` in the session test; `configure = { it.x = ... }` lambdas ending in an assignment; test functions written as `= runSession { ... }` (must return Unit); `ChannelResult.getOrNull()`; `Env` extension functions on a private nested class.

### Deferred
- **Other brands' ANC encodings.** Decoding uses the OnePlus Buds 4 report table. realme models report with their write bits (Transparency `0x02`, Light `0x04`, Deep `0x08`, ...), which this decoder would read wrongly (`0x02` as ANC). Needs a model gate or a capture; none exists.
- **Adaptive discovery:** no protocol fact says which models offer it, so it appears only after the buds report it. A product-id table (a data entry) could list it; B3/B4 may add one.
- **Low-latency quirks:** on Buds Pro 3 style firmware `28` acks but reads back `0` (PROTOCOL.md 21.5); the plan's "0x28 first" rule then makes `setLowLatency` return false there. Not handled.
- Slice (multi-frame) messages are skipped; no long reply is needed by B1. A half-received frame whose rest never arrives can swallow the next frame until its declared length is consumed (cap 2048 bytes); upstream has the same weakness.
- No handling of the Fast Pair message stream (battery-only fallback) and no model table, by plan.
- Windows RFCOMM transport (B2), controller and settings (B3), chip and pill hooks (B4) are not started.

### Exact next step
Run **B2** on this zip (`DesktopBudsWindows.kt`: JNA ws2_32 / bthprops, `WindowsRfcommTransport` implementing `BudsTransport`: `read` returns 0 on a receive timeout, -1 when closed). Then build in GitHub Actions and fix compile errors from the watch-list above.


## BUDS B2: Windows Bluetooth transport and device discovery (NOT compiled)

### Did
- `DesktopBudsDevices.connectedCompatible()`: lists the Bluetooth devices Windows already knows (`BluetoothFindFirstDevice` / `FindNext` / `FindDeviceClose` from `bthprops.cpl`, `fIssueInquiry = false`, never a scan), keeps those with `fConnected` true and `DesktopBudsNames.isLikelyCompatible(name)`, one entry per address. Blocking native call: callers use `Dispatchers.IO`. No cache. Empty list on non-Windows or on any failure.
- `WindowsRfcommTransport(device, uuids) : BudsTransport` and `WindowsRfcommTransport.open(device): Result<WindowsRfcommTransport>`: for each UUID in `BudsIds.SERVICE_UUIDS` order (079A, then 1107) creates an `AF_BTH` / `SOCK_STREAM` / `BTHPROTO_RFCOMM` socket and `connect`s with `serviceClassId` = the UUID and `port = 0` (Windows resolves the channel by SDP). The connect runs on a daemon worker thread with a 6 s bound; on timeout the socket is closed, which makes the blocked connect return. First success wins.
- `read` loops on a 500 ms receive timeout and checks coroutine cancellation each turn (so a cancelled reader stops within about 0.5 s); it returns bytes read, or -1 when the buds close or the link breaks. It never returns 0. `write` sends in a loop with a 5 s send timeout and throws `IOException` on failure (the session turns that into a disconnect). `close()` is idempotent, closes the socket (a blocked read then returns -1) and releases Winsock; the last release calls `WSACleanup` (`BudsWinsock` + pure `BudsRefCounter`).
- Failure type: `Result.failure(BudsConnectException(code, message))`, codes `ALREADY_IN_USE`, `NOT_CONNECTED`, `TIMEOUT`, `UNKNOWN` (`BudsErrorCodes`), mapped from `WSAGetLastError` by `BudsSocketErrors.toCode`. The code reported is the one from the FIRST UUID (079A), because the second UUID times out on most models and would hide the real reason.
- Every native call is wrapped; nothing throws to the caller. JNA is loaded lazily (`BudsNative.ws2` / `.bth` are `by lazy` and null off Windows). Off Windows: discovery is empty and `open` returns failure code `UNKNOWN` ("works on Windows only").

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBudsWindows.kt`; `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBudsWindowsTest.kt`. Edited: `HANDOFF.md`. No existing Kotlin file touched (no `// BUDS B2 hook` marks), no Gradle change, no dependency (JNA and jna-platform were already there), `sharedUi` / `shared` / `app` untouched, `DesktopApp.kt` never opened.
Contract note for B3: `BudsDevice(name, address)` has a redacted `toString` (short hash only); `BudsConnectException.code` is what B3 maps to messages; `BudsDevice`, `BudsConnectException`, `BudsErrorCodes` live in `DesktopBudsWindows.kt`.

### Decisions (no questions allowed)
- **Native structures are raw byte blocks, not JNA `Structure` subclasses** (the plan said Structure). `DesktopProcessMemory.kt` documents Kotlin/JNA field-reflection pitfalls and already uses raw `Memory`; I followed that. The layouts live in pure builders / parsers that are unit tested: `BudsSockaddr` (30 bytes, packed), `BudsSearchParams` (40 bytes on 64 bit, 32 on 32 bit), `BudsDeviceInfoLayout` (560 bytes), `BudsGuid`.
- `SOCKET` is declared as a JNA `Pointer` (pointer sized, so it is right on 32 and 64 bit); `INVALID_SOCKET` is detected as all-ones (`-1`, or `0xFFFFFFFF`).
- `BluetoothGetDeviceInfo` was not needed (the find loop already returns `fConnected`) and is not declared.
- The WSAStartup version is 2.2; the `WSADATA` buffer is 512 bytes (larger than the real struct on both widths).
- Winsock error mapping (see Unverified): refused / address in use / access denied / already connected give `ALREADY_IN_USE`; timed out gives `TIMEOUT`; network or host down, reset, aborted, not connected, address unavailable, address family unsupported give `NOT_CONNECTED`; anything else `UNKNOWN`.
- Nothing is logged in this file (no names, no addresses); `BudsAddress.shortHash` exists for B3 if it wants a log tag.

### Unverified (written from memory of the Win32 headers, no Windows machine here)
- `SOCKADDR_BTH` is packed (`pshpack1`): family @0, `btAddr` @2, GUID @10, port @26, size 30.
- `BLUETOOTH_DEVICE_SEARCH_PARAMS`: `cTimeoutMultiplier` is a `UCHAR` @24 followed by padding, `hRadio` aligned to the pointer size (32 on x64).
- `BLUETOOTH_DEVICE_INFO` = 560 bytes: address @8, class @16, `fConnected` @20, `fRemembered` @24, `fAuthenticated` @28, two `SYSTEMTIME` @32 and @48, name @64 (`WCHAR[248]`).
- `Native.load("bthprops.cpl", ...)` with the extension (as the plan says); if JNA cannot find it, discovery silently returns empty. If that happens, try `"bthprops"`.
- `SO_RCVTIMEO` / `SO_SNDTIMEO` take a `DWORD` of milliseconds on Windows, and `recv` then fails with `WSAETIMEDOUT` (10060).
- Which Winsock error an "another app holds the service" failure really gives (the plan itself marks one RFCOMM client per service as unverified). The `ALREADY_IN_USE` mapping is a best guess; real devices may report `TIMEOUT` or `NOT_CONNECTED` instead.
- `fConnected` is true for a paired device that is currently connected at the ACL level; if some earbuds report it false while playing audio, discovery would miss them.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler, no Windows). Done: brace / paren / bracket balance by script on both files; no duplicate top-level names in the repo (grep); the FNV short hash recomputed in Python (the two test addresses give different values); GUID, sockaddr and device-info expectations hand-traced; every test hand-traced. The tests cover only the pure parts (MAC string to address and back, GUID byte order, native layouts, error mapping, the connected-and-compatible filter over fake lists, the reference counter, redacted `toString`, and "off Windows nothing happens"). No test needs Bluetooth, a window or the network.
Compile watch-list: `ULong` arithmetic in `BudsAddress.shortHash`; `Memory(...).use { }` nested lambdas returning a value in `BudsNative.enumerate`; smart cast of `s` (a `val Pointer?`) inside the worker lambda in `tryService`; `Result<Unit>.map { transport }` and `Result` as a function return type; `const val AF_BTH: Short = 32`; `private fun connectBlocking()` called from the companion `open`; the `onLast = { runCatching { ... } }` lambda whose value must be ignored (`() -> Unit`).

### Deferred
- Run on a real Windows machine with earbuds: whether `bthprops.cpl` loads, whether `fConnected` is true for connected earbuds, which Winsock error a busy service gives, and whether 079A connects on the owner's model.
- No retry or back-off here (B3 owns that); no SDP query to learn the model; no pairing (Muses never pairs).

### Exact next step
Build in GitHub Actions now (owner's route: build after B2) and fix errors from the two compile watch-lists (B1 and B2). Then run **B3** on the zip: `DesktopBudsController` (poll `DesktopBudsDevices.connectedCompatible()` on `Dispatchers.IO`, open with `WindowsRfcommTransport.open`, run `BudsSession(transport, scope, debugLog = ...)`), settings, UI group, `docs/BUDS_SYNC.md` (the credits file already exists; B3 only needs to check it).


## BUDS B3: controller, settings, sync guide (NOT compiled)

### Did
- `DesktopBudsSettings` (persisted, `DesktopPersistence`): `enabled` (false), `autoConnect` (true), `debugFrames` (false). Setting `enabled` calls `DesktopBudsRuntime.refresh()`.
- `DesktopBudsController(scope, ...)`: `ui: StateFlow<BudsUiState>`, `start()` (single flight), `stop()` (cancels the loop, closes session and socket, shows `Off`), `setNoiseMode`, `setLowLatency`, plus one addition `connectNow()`. Loop: every 8 s `DesktopBudsDevices.connectedCompatible()` on `Dispatchers.IO` (default lambda); 0 candidates -> `Searching`; more than one -> `Problem(MULTIPLE)` and nothing is opened; exactly one -> `Connecting` -> `WindowsRfcommTransport.open` -> `BudsSession` -> `Connected(name, state)` (state republished on every change). Open failure -> `Problem(code, message)` then back-off; session end (lid close, link drop) -> `Searching`, back-off, retry when the device is listed again. All dependencies (device list, transport opener, session factory, switches, delay, clock) are constructor parameters with real defaults, so tests use fakes. Nothing throws out of the loop; the socket is closed in a `finally`.
- `BudsControllerLogic` (pure): `backoffMs` 8 s, 16 s, 30 s, then 60 s; `messageFor(code)`; `statusLine(ui, enabled)` (never contains the device name).
- `DesktopBudsRuntime` (object in `DesktopBudsController.kt`): owns the one controller and its scope. It does nothing (no scan, no socket) until `refresh()` sees the switch on. `shutdown()` on app exit.
- `DesktopBudsSettingsUi.kt`: rows inside the existing Experimental group: master switch with honest description (brands, pair in Windows first, one controlling app), status line, "Connect now" button (only when auto connect is off and a pair was found), "Auto connect", "Debug frames", credits text with an "Open QuickBuds on GitHub" button.
- `docs/BUDS_SYNC.md` written. `docs/third_party/BUDS_CREDITS.md` and the README line (both from B1) were checked and left as they are.

### Files
New (main, `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`): `DesktopBudsController.kt`, `DesktopBudsSettings.kt`, `DesktopBudsSettingsUi.kt`. New test: `DesktopBudsControllerTest.kt`. New doc: `docs/BUDS_SYNC.md`.
Edited: `DesktopApp.kt`, two small blocks, both marked `// BUDS B3 hook` (found only by `grep -n` and `sed -n`, the file was never opened whole): the earbuds rows right after the voice rows in the "Experimental" `SettingsGroup`, and a `DisposableEffect(Unit)` right after the voice controller's one (calls `DesktopBudsRuntime.refresh()`, `shutdown()` on dispose). `HANDOFF.md`.
No Gradle file, dependency or version changed. `sharedUi`, `shared`, `app` untouched. No B1 or B2 file touched.

### Decisions (no questions allowed)
- **Contract additions, old names kept:** `DesktopBudsController.connectNow()` and `DesktopBudsRuntime` (not in the plan). Reason: "Auto connect" off needs a way to connect, and B4 needs one place to read `ui` without creating its own controller. **B4: do NOT create a second controller; read `DesktopBudsRuntime.controller.ui` and call `DesktopBudsRuntime.controller.setNoiseMode/setLowLatency`.** The plan's "wire the controller into the app scope in DesktopApp.kt" is already done by the B3 hook.
- Settings placement: no new `DesktopSettingsSection` or category; the rows sit in the existing Experimental group (Advanced and diagnostics), like voice.
- More than one compatible pair connected: show `Problem(MULTIPLE)` and open nothing (the plan says "exactly one candidate").
- Autoconnect off: state `Problem(FOUND, ...)` ("press Connect now"); `connectNow()` allows one attempt. Its flag is cleared whenever no single candidate is present, so it cannot spin the scan.
- Back-off counting: `failures` counts consecutive failures; a link that was up at least 20 s resets it to 1 when it drops. First failure waits 8 s, then 16, 30, 60. The wait is sliced into 500 ms pieces so `connectNow()` and `stop()` act quickly.
- Messages: `ALREADY_IN_USE` is the plan's text verbatim. `TIMEOUT` and `NOT_CONNECTED` also point at QuickBuds / HeyMelody, because B2 itself says the real Winsock code for "service busy" is unverified and the device was listed as connected (a healthy link). `UNKNOWN` and any other code: "Could not open the earbuds control channel."
- Battery saver: the repo has no such flag (grep), so it is ignored, as the plan allows.
- Debug frames: when the switch is on at the moment a session starts, the session gets a logger that writes `buds: <hex>` to `System.err` (control frames only, as B1 defined). A changed switch applies from the next session.
- **BUDS_SYNC.md uses the real upstream section numbers**, not the plan's (plan: 2 frame, 3 commands, 5 ANC, 7 battery, 8 wearing, 9 features). In QuickBuds 6.1.1 `PROTOCOL.md`: 1 transport, 2 frame, 3 sequence and replies, 6 init, 8 subscriptions, 10 pushes, 11 requests from the buds, 13 battery and wearing, 14 ANC, 16 feature switches, 21 per-model rules.
- **Optional item 5 (battery chip for other Bluetooth audio devices): skipped.** The repo has no existing code that reads the Windows device battery property (grep for `DEVPKEY`, `SetupDi`, `BatteryLevel`, `CM_Get_DevNode_Property` found nothing), so there is no safe way I can confirm.
- The credits link opens in the system browser through the existing `DesktopExternalLinks.open`; nothing is fetched by Muses.

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler or Gradle here; nothing Bluetooth was touched). Done: brace / paren / bracket balance by script on all Buds files; grep for duplicate top-level names (`BudsUiState`, `BudsProblemCodes`, `BudsControllerLogic`, `DesktopBudsController`, `DesktopBudsRuntime`, `DesktopBudsSettings`, `DesktopBudsSettingsRows`, `BUDS_CREDIT_URL`: each defined once); imports checked by hand against the repo's other files (`catch (_: ...)`, `DesktopExternalLinks.open`, `DesktopSecondary`, `DisposableEffect` already used there); every controller test traced by hand against the loop, including the wait totals (two failures: 8 000 + 16 000 ms = 24 000 ms exactly).
Compile watch-list: `Result<BudsTransport>` as the return type of a function-type constructor parameter (default `WindowsRfcommTransport.open(device)` relies on `Result<out T>` covariance); `Result.failure<BudsTransport>(...)` inside a `try` expression; `return` inside `synchronized { }` in `start()`; `currentCoroutineContext().isActive`; the `if (st.connected) publish(...) else ended.complete(Unit)` statement as the last line of a `collect` lambda; test lambdas `open = { n -> if (n <= 2) Result.failure(...) else Result.success(transport) }` (type inference to `Result<BudsTransport>`); test functions written as `= runBlocking { ... }` must end in a `Unit` expression (they end with `r.scope.cancel()`).
Tests are timing-based with 5 ms fake delays and a 5 s timeout per wait; if one is flaky on CI, raise the fake delay, not the code.

### Deferred
- Everything on real hardware: whether the controller finds the earbuds (B2 unverified items), the real error for "another app is connected", `fConnected` behaviour while audio plays.
- No Settings search text for the "Connect now" row beyond the status block's title and subtitle.
- Optional battery-only chip for other brands (see Decisions).
- B4: the chip, pill hooks, Studio element, Glass lab shader fix, `DesktopApp.kt` pill wiring.

### Exact next step
Build in GitHub Actions and fix compile errors from the B1, B2 and B3 watch-lists. Then run **B4** on this zip: use `DesktopBudsRuntime.controller.ui` (and its `setNoiseMode` / `setLowLatency`) for the chip; do not create another controller.


## BUDS B4: earbuds chip in the pill, Studio element, shader check (NOT compiled)

### Did
- **Step 0:** `DesktopGlassShader.kt` already uses `outRgb` (no second `c`). Scanned the whole SkSL `main` for repeated names: none. No edit.
- `DesktopBudsChip.kt` (new): `DesktopBudsChip(ui, onSetNoise, onSetLowLatency, modifier)` draws nothing unless `ui is Connected`. Chip: Headphones icon (already imported in `DesktopApp.kt`), lowest bud percent (`--` if unknown), a lightning mark when that bud charges, 44 dp tall target. Tap opens a `DropdownMenu` (same style as the pill's "more"): device name, Left / Right / Case rows (missing ones hidden), a text hint from `inEar`, a segmented control of ONLY the reported modes, a Low latency switch when supported. A change shows "Setting..." until the read-back matches or 3.5 s pass, then the real state is shown. Only alpha animation, 0 ms with `reduceAnimation`.
- `DesktopBudsPillSlot(preview, modifier)`: reads `DesktopBudsRuntime.controller.ui` (no second controller). `preview = true` shows FAKE data with no-op callbacks.
- `BudsChipLogic` (pure): `lowestBattery`, `visibleModes`, labels, `wearHint`, pending/settled rules, `ensureElement`, `previewState`.
- New pill element `buds`: in `PILL_ELEMENTS`, default right zone just before `more`, label "Earbuds" in the Studio list (checkbox like the others), Studio simple preview text "Buds 82%", Liquid pill (real preview uses the fake chip) and Classic pill draw the slot. `validated()` runs `BudsChipLogic.ensureElement`, so old saved or imported layouts without it gain the chip before `more`. Presets keep the layout, so they keep the chip.
- Controller wiring: nothing new. The B3 `DisposableEffect` in `DesktopApp.kt` already calls `DesktopBudsRuntime.refresh()` / `shutdown()`.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBudsChip.kt`; test `DesktopBudsChipTest.kt`.
Edited, each marked `// BUDS B4 hook`: `DesktopPillStudio.kt` (element list, default zones, `validated()`, preview text, label), `DesktopLiquidPill.kt` (one `"buds"` branch), `DesktopApp.kt` (one `"buds"` branch in the Classic pill, found by `grep -n`, `sed -n`; never opened whole). `DesktopPillPresets.kt` needed no change. No Gradle change, no new dependency; `sharedUi`, `shared`, `app` untouched.

### Decisions (no questions allowed)
- Chip percent = lowest of the two buds (case never counts); tie prefers the charging bud; unknown = `--`.
- Missing `buds` in a layout is re-added on every `validated()`. To hide the chip use the checkbox (`hiddenElements`), as for other elements; dragging it out of every zone is not possible.
- Segmented control is my own row of clickable boxes (short labels Off / ANC / Transparency / Adaptive) instead of `FilterChip`, so four modes fit in a 300 dp popup.
- Charging mark and rows use the text character U+26A1, since no Bolt icon import is confirmed in the repo.
- With the feature off or no earbuds the slot draws nothing, so the pill looks as before (the 4 dp row spacing of an empty element is the only trace: an element that draws nothing adds no spacing in `Row`).

### Verified
**NOT compiled. Tests NOT run** (no Kotlin compiler here). Done: brace / paren balance by script on the three touched or new Kotlin files; grep: each new top-level name defined once; imports checked by hand; tests traced by hand against `ensureElement`, `validated()` and `DesktopPillPresets.import/export/apply`.
Compile watch-list: `if (open) DismissOnDisplayChange { open = false }` inside a `Box`; `Switch` / `DropdownMenu` content lambdas using `weight` (Row/Column scope only); `animateFloatAsState` delegate; `assertNotNull(loaded)` smart cast in the new test; the `ensureElement` call returning `Map` then `.toMutableMap()`.

### Deferred
- Everything on real hardware (B2 unverified list still applies), including how the popup looks over the Liquid glass pill.
- Pending timeout (3.5 s) is a guess; tune after a real test.
- The Studio preview in the simple (non-liquid) renderer shows only text, not the real chip.

### Exact next step
Build in GitHub Actions and fix errors from the B1 to B4 watch-lists. Then test with real earbuds: pair in Windows, Settings > Experimental > Earbuds on, close QuickBuds / HeyMelody, check the chip and the popup.


## AUDIO FIX

### Did
Read the last 3 HANDOFF sections, then only the audio output path. Findings on the six points (all from reading the code; nothing reproduced):
1. **Confirmed.** `write_audio` set `g_audio_reroute` on `DEVICE_INVALIDATED` and `continue`d; `reroute_locked` then found the same endpoint id and returned without rebuilding, so the loop spun with `g_audio_mutex` held. Everything that takes that mutex blocked behind it.
2. **Fix, native (`window_jni.cpp`).** New `g_audio_invalidated` flag. `mark_stream_dead_locked` keeps the frames heard so far, releases the client, sets the flag and schedules a retry; `reroute_locked` rebuilds when the flag is set even if the endpoint id is unchanged. Back-off is a timestamp (`g_next_rebuild_ms`: 50, 100, 200, then 250 ms), never a sleep under the lock: while it has not elapsed `write_audio` sees no client, counts the block as played in real time (at least 50 ms), unlocks and returns. The failure counter is cleared by the first write the new stream accepts, so a stream that dies again at once keeps backing off. "Dead stream" HRESULTs: DEVICE_INVALIDATED, SERVICE_NOT_RUNNING, RESOURCES_INVALIDATED, DEVICE_IN_USE, UNSUPPORTED_FORMAT, NOT_INITIALIZED, ENDPOINT_CREATE_FAILED, RPC_E_DISCONNECTED, RPC_S_SERVER_UNAVAILABLE (raw values, see Unverified). Handled in write (padding, GetBuffer, ReleaseBuffer), `played_frames_locked`, pause, resume, flush, drain. A full buffer whose padding does not change for 1.5 s while running counts as dead (stall). `nativeDrain` is bounded (1 s without progress, 3 s total, not at all when paused). `nativeFramesPlayed` uses `try_lock` and returns the last count when the stream is busy.
3. **Locks.** `g_enum_mutex` (creation of the shared enumerator only) and `g_info_mutex` (leaf; device name and last error). Order: `g_audio_mutex`, then `g_enum_mutex`. `list_devices` takes no audio lock. `nativeDeviceName` / `nativeLastError` take only the leaf lock, so the pipeline panel (UI thread) cannot wait behind a rebuild. The enumerator and the static watcher are never released or unregistered, so no callback can outlive them.
4. **Notifications.** Callbacks only set atomics, write a trace record and raise a flag (never throw: try/catch). One worker thread waits 250 ms and makes one JNI call per burst. Class and method id are cached (global ref) in `JNI_OnLoad`, with a retry from `nativeOpen` / `nativeDevices`; exceptions are always cleared.
5. **Kotlin.** `DesktopAudioDevices.devices` (StateFlow) is the cached list; a refresh loop on `Dispatchers.IO` debounces 200 ms, waits at most 1.5 s per enumeration, retries with 250/500/1000/2000 ms (4 retries), and keeps the last good list. `DesktopAudioOutputUi` and `DesktopPlayerHost` read it instead of calling `available()` while composing. `label()` (Settings) is now `@Composable` and reads the cache. Playback writes, `framesPlayed`, pause, resume, flush, drain run on the `Muses-Audio` thread only; `pipeline()` (composition) only touches the lock-free getters.
6. **Idle.** The sink is opened only when a track is loaded, so with an empty queue the only native work is the device list, and it ran on the UI thread. I found one more suspect there: the AWT thread is a single-threaded COM apartment, and the first `nativeDevices` call from it created the shared enumerator in that apartment (and so registered the notification client there, with callbacks delivered through the UI message loop, and raw-pointer use of it from the audio thread). Now `get_enumerator` refuses to create it on a non-MTA thread and `list_devices` returns nothing there; Kotlin no longer calls it from the UI thread. This is a hypothesis, not a proven cause.

Instrumentation: `audio-trace.log` (native ring of 256 records, written by a watchdog thread every 100 ms on request, every 2 s while an error persists, and while `g_audio_mutex` stays held over 300 ms: which entry point holds it and which wait); `DesktopFreezeWatchdog` (daemon, probes the AWT queue each second after a 10 s start grace, one all-threads dump into `desktop.log` per freeze, under "=== UI FREEZE ..."); `-XX:ErrorFile=$APPDIR/hs_err_pid%p.log`.

### Files
Edited: `desktopApp/native/window/window_jni.cpp` (audio section rewritten, `JNI_OnLoad`, `nativeSetTracePath`, `nativeFlushTrace`; frame/window code untouched), `DesktopWindowsAudio.kt`, `DesktopAudioDevices.kt`, `DesktopAudioOutputUi.kt`, `DesktopPlayerHost.kt`, `DesktopTrackLog.kt` (exposes `directory`), `Main.kt` (one line), `desktopApp/build.gradle.kts` (one jvmArg), `HANDOFF.md`. New: `DesktopAudioSafety.kt`, `DesktopAudioSafetyTest.kt`. All marked `// AUDIO FIX` (the native section carries it in its comments). `DesktopApp.kt` never opened. No new dependency, no Gradle flag changed.

### Decisions
- Back-off is a timestamp, not a sleep; during it audio is silent and the track position advances in real time (as the old no-client path did). The spec said 50 to 250 ms; I kept to that.
- Stream builds (`Activate` / `Initialize`) still run under `g_audio_mutex`, so pause / resume / flush / close on the audio thread wait behind a slow build. Only the audio thread can wait; the UI thread and the device list cannot. Moving the build outside the lock is riskier to write blind.
- Trace flushing is done by the watchdog thread, never under the audio lock; a crash within ~100 ms of an error can lose the newest records.
- Device names never enter the trace (endpoint-id hash only); `hs_err` files are the JVM's own and can hold paths and module names.
- No C++ test target exists, so no native tests; the pure Kotlin helpers are tested.

### Verified
**NOT compiled. Tests NOT run. Nothing was run on Windows or with audio.** Done: brace/paren balance by script; the new native audio section and JNI tail were syntax-checked with host g++ (`-fsyntax-only -Wall -Wextra`) against hand-written stub headers, which proves typos and types in my code only, not the real Windows/JNI headers or MinGW; Kotlin hand-read against the repo's imports; the three test groups hand-traced (retry delay values, freeze tracker sequence, dump text). Compile watch-list: `ULONGLONG{25} << step`, `std::atomic<jclass>` / `<jmethodID>`, `thread_local` under `-static` MinGW, `constexpr HRESULT` from `static_cast<HRESULT>(0x...u)`, `@Composable fun label()` in an `internal object`, `Deferred<List<...>?>` with `takeIf { it.isActive }`.

### Unverified assumptions about Windows audio (could not check here)
- The raw HRESULT values (`0x88890004` invalidated, `…10` service not running, `…26` resources invalidated, `…0A` in use, `…08` unsupported, `…01` not initialized, `…0F` endpoint create) are from memory.
- That Windows Sonic / DTS / a Bluetooth profile switch invalidates a shared-mode stream with the endpoint id unchanged (your finding 1), and that `GetCurrentPadding` or `GetBuffer` then returns one of those codes rather than silently stalling (the 1.5 s stall rule is a guess for that case).
- That `Stop` / `Release` on an invalidated client return at once and never block; that `Activate` / `Initialize` may block for seconds.
- That a rebuild 50 ms after invalidation is early enough to succeed; if not, it retries every 250 ms.
- That `OnPropertyValueChanged` bursts during an effect switch (only traced, nothing acts on them), and how many `OnDevice*` callbacks one switch produces.
- That `CoInitializeEx(MTA)` on a Java IO/audio thread returns S_OK, and that the AWT thread is an STA (it returned RPC_E_CHANGED_MODE, which the old code ignored).
- That the static `EndpointWatcher` is safe to leave registered at process exit.
- That jpackage expands `$APPDIR` in a java-option; the per-user MSI install folder being writable. A portable zip may behave differently. `gradle run` writes nothing.
- That `FindClass` in `JNI_OnLoad` sees the app class (System.load is called from an app class); the fallback from `nativeOpen` / `nativeDevices` covers it otherwise.

### Deferred
- Building the stream outside the audio lock; a hung `Activate` still stalls the audio thread (the UI stays alive, and the trace shows who holds the lock).
- Proactive rebuild while paused (it happens at resume or at the next write).
- The paused-with-full-buffer wait in `write_audio` (legitimate while paused; unbounded by design).
- Reading `hs_err` / `audio-trace.log` back into Settings diagnostics.

### Exact next step
Build in GitHub Actions (the DLL with the new native code, then Kotlin). On the HP ENVY: play, then switch Windows Sonic / DTS / output while playing, paused and idle; expect a short silence then recovery, never a frozen window. If anything still freezes, send `audio-trace.log` (in `%USERPROFILE%\.local\state\bitchord`, next to `desktop.log`) and the "UI FREEZE" block from `desktop.log`: the trace's `holder=` line and the thread dump say who held what.

## Muses fixes: art transition, voice sync, single-key trigger, pill, search (NOT compiled)

- Art transition source: `lookupSource` now ignores views that are off-screen or are the pill thumbnail, and picks the view under the click, else the nearest to it, else the biggest. The mouse-box fallback is gone: with no view on screen (next button, media key, full player) it starts from the player estimate or the pill thumbnail. Press freshness 600 ms -> 4 s, consumed once; media keys count (`DesktopArtPress.noteKey`); artwork load timeout 700 ms -> 2.5 s.
- Settings: Experimental has "Artwork snap transition" (maps to mode OFF / EVERY_CLICK).
- Pill: shuffle/repeat/"1" are white (active) or dim white (inactive), no artwork colour, liquid and classic pill.
- Search: the open search field now ends left of the Back arrow (end padding 134 dp + button + 8 dp), so Back no longer sits on top of it; the island is not touched.
- Voice sync: new state `Preparing` (overlay window made, nothing drawn). `Listening` is published only after the ready tone returned, so popup and beep arrive together and the recorder's flush drops only the tone.
- Single-key trigger: `DesktopVoiceKeyTrigger.kt`; settings keys `voice_trigger_key`, `voice_trigger_mode`, `voice_tap_seconds`. Works only with the Muses window focused and no text field focused (`Modifier.trackTextFocus()` on all text fields). Tap mode: record up to N s, second tap ends early.
- Art transition (follow-up): source rect uses localToWindow corners (includes hover scale); the clicked artwork view fades out with `sourceFade` (`DesktopArtTransition.sourceCoords/isSource`, applied in `DesktopArtwork`) so the album visibly turns into the particles.
- Full-screen player: no transition at all (maybeStartArtTransition skips and a running one is cancelled when the player opens).


## WEB1: embedded web server, auth, settings, background mode (NOT compiled)

### Did
- **Server** (`DesktopWebServer.kt`): JDK `HttpServer`, 127.0.0.1 by default (0.0.0.0 only with LAN on), default port 8765 then the next 20 if busy, executor 2 to 4 daemon threads (bounded queue 64). The pool thread only hands the exchange to a coroutine scope (`SupervisorJob + Dispatchers.IO`, cancelled on stop), so long streams and SSE never hold the pool. `stop()` cancels the scope, `server.stop(1)`, shuts the pool.
- **Two layers**: `DesktopWebGateway` (pure: auth, pairing, static, routing, body caps, error envelope; takes a `WebIncoming`, returns a `WebResponse`) and `DesktopWebServer` (socket adapter). All acceptance cases are unit-tested on the gateway with no socket.
- **Auth** (`DesktopWebAuth.kt`, pure): 128-bit token per launch (hex), `HttpOnly; SameSite=Strict` cookie `muses_web`, Host allowlist (`localhost`, `127.0.0.1`, LAN IPs only while LAN is on), non-loopback refused unless LAN, `Sec-Fetch-Site` and `Origin` checks, custom header `X-Muses-Web: 1`, constant-time compares, 6-digit pairing code with lockout (`DesktopWebPairingGuard`: 5 wrong codes lock all pairing for 60 s, doubling each time, cap 1 h).
- **Router** (`DesktopWebRouter.kt`) + helpers (`DesktopWebHttp.kt`): `{param}` templates, percent-decoding, query parsing, body caps (64 KB JSON, 1 MB voice, 1 KB pairing), error envelope, SSE formatting, `WebRange.parse` (for WEB2's stream proxy), `DesktopWebEventHub`, `/api/ping`, `/api/events` (retry line, initial `signedIn` event, keep-alive comment every 20 s, max 4 streams, slot released in `onFinish`).
- **Static** (`DesktopWebStatic.kt`): classpath folder `web/`, safe path resolver, content types, `Cache-Control: public, max-age=31536000, immutable` only for names with an 8+ hex hash, else `no-cache` + ETag/304, strict CSP on HTML, built-in "Muses Web is running" page when `web/index.html` is missing, LAN pairing page (no script, no inline style). `desktopApp/src/main/resources/web/.gitkeep` kept the folder (removed in WEB3, real files are there now).
- **Settings** (`DesktopWebSettings.kt`, `DesktopWebSettingsUi.kt`): keys `web_enabled` (off), `web_port` (8765), `web_lan` (off), `web_background` (off). Rows in Settings > Advanced > Experimental: master switch, status + "Open in browser", port stepper, "Allow other devices on my network", pairing code + "New code" (only with LAN on), background-mode switch.
- **Runtime** (`DesktopWebRuntime.kt`): one server per process, `refresh()` on a daemon control thread, status flow, `openInBrowser()`.
- **Background mode** (`DesktopWebBackground.kt`, `Main.kt`): `--web` flag or (switch on + background setting) starts the server and the tray icon only. No Compose `application`, no `Window`, no backdrop is created. Tray menu there: Open Muses Web / Show the desktop window / Quit. "Show the desktop window" stops the background tray and falls through to the normal `desktopMain()`.
- **Build**: `"jdk.httpserver"` added to `modules(...)`. No Gradle dependency added.

### Files
New (main): `DesktopWebHttp.kt`, `DesktopWebAuth.kt`, `DesktopWebRouter.kt`, `DesktopWebStatic.kt`, `DesktopWebServer.kt`, `DesktopWebSettings.kt`, `DesktopWebSettingsUi.kt`, `DesktopWebRuntime.kt`, `DesktopWebBackground.kt`, `resources/web/.gitkeep`.
New (tests): `DesktopWebHttpTest.kt`, `DesktopWebAuthTest.kt`, `DesktopWebRouterTest.kt`, `DesktopWebStaticTest.kt`, `DesktopWebGatewayTest.kt`, `DesktopWebSettingsTest.kt`.
Edited, each marked `// WEB 1 hook`: `Main.kt` (`main(args)`, start + background), `DesktopTrayMenu.kt` (ids 11/12, `setWebOpener`, `bindBackground`, `leaveBackground`, background entry list), `DesktopApp.kt` (5 lines in the Experimental group, found with `grep -n`, never opened whole), `desktopApp/build.gradle.kts` (module). `MUSES_WEB_PLAN.md` copied to the repo root. `sharedUi`, `shared`, `app` untouched.

### Decisions (no questions allowed)
- **Error envelope** is `{"error": "<short English message>", "code": "<machine id>"}` (`forbidden`, `not_found`, `method_not_allowed`, `too_large`, `uri_too_long`, `too_many_streams`, `internal`); the HTTP status carries the number.
- **Header check**: every `/api` call needs the cookie and `X-Muses-Web: 1`, except `/api/stream/*`, `/api/art` and `/api/events`, which the browser fetches itself (`<audio>`, `<img>`, `EventSource`) and cannot add a header to. Those rely on the cookie plus `Sec-Fetch-Site` (must be `same-origin` when sent; `none` and `same-site` are refused on /api) plus `Origin`. WEB3's `api.js` must send the header on every `fetch`.
- **Origin must equal the Host authority** (host and port), not just the host, because cookies are not isolated by port.
- **One token for everyone**: a LAN device that pairs gets the same cookie value. The token is per launch, so LAN devices re-pair after the desktop app restarts; a loopback page load renews itself.
- **Pairing lock is global** (not per IP), escalating; the code is new on every launch and every time LAN is switched on.
- `/pair` accepts only a form body `code=123456`; success is `303` to `/` with the cookie.
- **403 reasons are logged as fixed words only** (host, remote, no-session, cross-site, origin, header, method, pairing-off), first 20 then every 100th. Never a token, cookie, URL, query or exception message.
- **Tray click in background mode opens the web page**, not the heavy window (window is a menu choice).
- **Background mode is skipped** (window opens) on non-Linux when `SystemTray` is unsupported, so the process cannot become unquittable. On Linux the D-Bus tray cannot be detected up front; if no tray host exists the process can only be stopped from the task manager (logged).
- `--web` forces the server on for that launch only and does not change the saved switch; the background setting only counts when the master switch is on.
- Port control is a stepper (-, +, Reset), not a text field, to avoid focus handling written blind.
- Hostname access (`http://PCNAME:8765`) is not allowlisted; only `localhost`, `127.0.0.1` and LAN IPs, as the plan says.
- Status strings in the UI are English literals (not in the string catalogues), like the voice and earbuds rows.

### Constraints WEB3 must respect (set by the CSP in `DesktopWebStatic.CSP`)
`default-src 'self'; connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`. So: no inline `<script>`, no inline `<style>` and no `style="..."` attributes in the HTML (use `app.css`; setting `el.style.x` from JS is fine), no CDN, no external fonts. Service worker and AudioWorklet files must be same-origin files.

### Playback state that depends on a composed window (for WEB2 and the owner)
Found by reading `Main.kt`, `DesktopTrayMenu.kt` and the tray wiring around `DesktopApp.kt` line 2350 to 2410:
- `playbackEngine`, `selectedSong`, `playNext/playPrevious`, the home/search state (`homeState`, `loadMoreHome`) and the **composed tray controller** all live inside the `BitChordDesktopApp` composition. In background mode none of them exists: no desktop playback, no tray play/next/previous, no media keys or Windows media session (browser Media Session replaces them in WEB4).
- WEB2 must call the data layer directly (`DesktopSearchClient.browse/moreHome`, `StreamResolver`, lyrics, library persistence) and never read composable state. `main()` already installs `PlayerPlatform`, `InnerTubeXResolver`, `StreamResolver.maxKbps`, logging and the lyrics cache before the window, so those work without it.
- `DesktopYouTubeAuth.isSignedIn` (used by `/api/ping`) reads a plain field and needs no window.
- The `DesktopFreezeWatchdog` AWT probe runs in background mode with no window; it should just see an idle EDT, but watch for false "UI FREEZE" dumps.

### Verified
**NOT compiled. Tests NOT run. Nothing was run, no socket was opened, no browser used** (no Kotlin compiler here). Done: bracket balance by a small lexer on every new and touched Kotlin file; no block-comment nesting; each new top-level name is unique in the repo; imports and APIs checked by hand against what the repo already uses (`DesktopPersistence.boolean/int/saveInt`, `DesktopStrings[...]`, `DesktopExternalLinks.open`, `Res.readBytes`, `DesktopStatusNotifierController`); all tests traced by hand (lock times, range cases, decode cases, gateway flows).
Compile watch-list: `return@buildList` inside `entries()` in `DesktopTrayMenu`; `Result<Int>` as a return type; `server.createContext("/") { exchange -> ... }` SAM lambda; destructuring `for ((name, values) in exchange.requestHeaders)`; `DesktopWebStatic::loadFromClasspath` and `System::currentTimeMillis` as default parameters; `throw` as the whole body of a `suspend` route lambda in a test; smart cast of `incoming.rawQuery`; `hub.events.collect { }` lambda form; `ThreadFactory { }` SAM constructor.
Unverified assumptions: a `com.sun.net.httpserver` exchange may be answered from another thread after `handle` returned (I rely on it); `Res.readBytes` works outside a composition (called under `runBlocking` in background mode); switching background to window leaves exactly one tray icon (the old one is stopped before the composed one starts); `jdk.httpserver` in the jlink image is enough at run time; loader lookup of `web/...` through the context class loader in the packaged app.

### Deferred
- No test opens a real socket (start/stop/port fallback/SSE over HTTP/`HEAD`) because tests may not need a network; do it by hand (below).
- Real `web/` files (WEB3), all real endpoints (WEB2), HTTPS for LAN microphone, per-device tokens, persisted tokens, hostname allowlist.
- Background mode on Linux cannot detect a missing tray host.
- Translations of the new settings strings.

### Exact next step
Build in GitHub Actions and fix errors from the watch-list. Then by hand: Settings > Advanced > Experimental > Muses Web on, click "Open in browser": expect "Muses Web is running" at `http://localhost:8765/`; paste `http://localhost:8765/api/ping` in a new tab: expect 403 (no header, by design). Start with `--web`: expect only a tray icon, menu Open Muses Web / Show the desktop window / Quit, Task Manager memory far below the window mode (note the numbers). Then run WEB2 with this zip.


## WEB2: API adapters, stream proxy, voice endpoint (NOT compiled)

### Did
Every endpoint of the contract in `MUSES_WEB_PLAN.md` now answers, wired to code that already exists (no logic duplicated, `DesktopApp.kt` never opened, no Gradle change, `sharedUi` / `shared` / `app` untouched).
- **Data** (`DesktopWebApi.kt`): `/api/home` (first page + up to 2 continuations through `DesktopSearchClient.home/moreHome`, repeats dropped with `withoutRepeatsOf`, 60 s cache), `/api/search` (`SearchFilter.ALL`), `/api/suggest` (`searchSuggestions`), `/api/library` and `/api/playlist/{id}` (`library()`, `browse()`, `moreCollectionSongs()`), `/api/track/{videoId}` (`trackLinks()` + palette), `/api/lyrics/{videoId}` (`DesktopLyricsClient.lookup`), `POST /api/like/{videoId}` (`DesktopSearchClient.rate` + `LikeState.set`), `POST /api/playlist/{id}/add` (`DesktopSearchClient.addToPlaylist`, the call the player button and voice use). Like and add publish `libraryChanged` on the event hub.
- **Palette** (`/api/track` field `palette`): the cover is read through `DesktopArtworkCache.load`, `extractAlbumPalette` picks the colours, the first three become `#rrggbb`; 5 s timeout, quiet dark fallback.
- **Stream proxy** (`DesktopWebMedia.kt`, `/api/stream/{videoId}`): `StreamResolver.resolve` (it already caches the URL for 20 min and forgets a refused one), allowlist `*.googlevideo.com` over https, size from the URL's `clen` (else a 1-byte probe), `WebRange.parse` from WEB1, status 200/206/416 with `Content-Range`, `Content-Length`, `Accept-Ranges`. Upstream is read in windows of 1 MiB and copied through a 16 KB buffer (never a whole file in memory). A 403 or 410 on the first window calls `StreamResolver.onPlaybackRefused`, resolves again and retries once. Redirects are followed by hand (max 3), each hop re-checked against the allowlist. A client abort makes the write throw; `onFinish` (WEB1) closes the upstream stream and returns the slot.
- **Art proxy** (`/api/art?u=`): hosts `googleusercontent.com`, `ggpht.com`, `ytimg.com` only, https, no user info, port 443, no redirect off the list, image types only, 4 MB cap, `Cache-Control: private, max-age=86400`. All art fields in the JSON are already `/api/art?u=...` URLs (or null), so a page never holds a raw artwork URL.
- **Limits**: 3 streams, 8 art fetches, 1 voice request at once; beyond that 429 (`too_many_streams`, `too_many_requests`, `busy`).
- **Voice** (`DesktopWebVoice.kt`, `POST /api/voice`): WAV (16 kHz mono 16-bit, 0.2 to 15 s) -> `SherpaSidecarRecognizer` (found as the desktop feature finds it) -> `DesktopVoiceCommandParser.parse` -> `VoiceMatcher` / `VoiceSongMatcher` against the account's library index and a song search -> an ANSWER. Nothing is played or changed on the PC, no overlay state, no session memory; `DesktopVoiceController` was not touched (the resolver is a side-effect-free port of its `play` / `searchAndPlay`). Audio and text are not stored or logged.
- **Hook**: one line in `DesktopWebRuntime.kt` (`// WEB 2 hook`) installs `DesktopWebApi` on the router.

### Files
New (main): `DesktopWebApiSupport.kt` (pure helpers and JSON shapes), `DesktopWebMedia.kt`, `DesktopWebVoice.kt`, `DesktopWebApi.kt`. New (test): `DesktopWebApiSupportTest.kt`. Edited: `DesktopWebRuntime.kt` (1 hook), `HANDOFF.md`.

### Response shapes the contract left open (WEB3 and WEB4 must use these)
- `Item.id`: song = videoId; playlist = the RAW playlist id (no `VL`; `browseId` keeps the `VL...` form); album / artist = browse id. `/api/playlist/{id}` takes a playlist id, an album id (`MPREb_...`, `OLAK5uy_...`), an artist id (`UC...`), or `LM` (Liked music).
- `/api/home` -> `{shelves:[{title, kind, items}]}`; `/api/search` -> `{sections:[{title, kind, items}]}` (titles "Top result", "Songs", "Albums", "Artists", "Playlists", empty ones dropped); `kind` = `songs` | `browse` | `mixed` (added field). `/api/suggest` -> a JSON array of strings (empty array for blank text).
- `/api/library` -> `{liked:{id:"LM", title, count}, playlists:[Item], saved:[Item]}`. `playlists` are the library items that can be edited targets (`parseUserPlaylists`); the library feed does not say owned vs saved, so the certain answer is `editable` on `/api/playlist/{id}`.
- `/api/playlist/{id}` -> `{title, owner, editable, items:[Track]}`; `owner` is the page's subtitle text; `Track.artists` is one string (the byline); up to 1000 tracks, unresolved Spotify rows and non-video ids dropped.
- `/api/track/{videoId}`: `artists` is an array of names; `album` is a string or null; `art:{small,large}` are proxied URLs; added field `videoId`.
- `/api/lyrics/{videoId}` -> `{synced:[{ms,text}]|null, plain:string|null}`. Synced when the lines carry more than one distinct non-negative start time. No lyrics is 200 with both null. `plain` is also filled when synced.
- Like body `{liked:true|false}` -> `{liked}`. Add body `{videoId}` -> `{ok:true}`. Signed-out library / like / add -> 401 `signed_out`.
- `/api/voice` -> `{heard?, action, result, candidates?:[Item], item?:Item, value?:string}` (added `item`, `value`). `action` is one of: `play` (item = song), `playPlaylist` (item = playlist), `addToPlaylist` (item = playlist; the PAGE sends the add with its current track), `addToLastPlaylist`, `choices` (candidates, up to 3), `choose` (value = number 1..), `chooseOther`, `pause`, `resume`, `next`, `previous`, `volumeUp`, `volumeDown`, `setVolume` (value = percent), `shuffle` (value = "true"/"false"), `like`, `whatsPlaying`, `none` (nothing found / not understood). The page remembers the last `candidates` for `choose` / `chooseOther`. Errors use the envelope: 400 `bad_audio` | `too_short` | `too_long`, 503 `program_missing` | `model_missing`, 504 `timeout`, 429 `busy`, 502 `unexpected`.
- Errors elsewhere: 400 `bad_request`, 404 `not_found`, 429 as above, 502 `upstream` / `no_stream` / `too_large`; messages are fixed English text.

### Decisions (no questions allowed)
- **No second URL cache**: `StreamResolver` already keeps resolved URLs (20 min, 32 entries) and benches a refused one; the proxy reuses it.
- **No ffmpeg for voice**: the repo ships ffmpeg paths for other features but not for decoding browser audio, so `/api/voice` takes WAV only; WEB4's `voice.js` must produce 16 kHz mono 16-bit WAV (the plan's own fallback).
- **Content type**: from the URL's `mime`, else the upstream header, else `audio/mp4`; Opus in WebM is returned as `audio/webm`; no codec parameters.
- **Voice always asks when unsure**: a weak or ambiguous match returns `choices` (the desktop's "ask before playing" setting is not read; it belongs to the desktop overlay).
- **Voice library**: the account's own playlists, liked and library songs and their artists (from the shared 60 s library cache). Playlists kept only on this computer (the desktop's local ones) are not included.
- **Library cache**: `DesktopWebLibrary` (60 s, one fetch at a time) feeds `/api/library`, voice and the "liked" flag; like and add invalidate it. `liked` = this session's `LikeState` override, else membership in the first page of Liked music.
- **Tracks**: details and palette kept 10 min (64 entries); `liked` is always read fresh.
- **Header check (from WEB1)**: `/api/stream/*` and `/api/art` need the cookie but not `X-Muses-Web`, so `<audio>` and `<img>` can load them; everything else, including `/api/voice`, needs the header.
- `HEAD` on the stream opens the first window and closes it again (the server skips the writer and runs `onFinish`).

### Verified
**NOT compiled. Tests NOT run. Nothing was run, no socket opened, no browser or account used** (no Kotlin compiler here). Done: bracket balance by script on all new files; every new top-level name checked unique across `desktopApp`; imports and signatures checked by hand against the real sources (`DesktopSearchClient`, `StreamResolver`, `LikeState`, `DesktopLyricsClient`, `DesktopArtworkCache`, `extractAlbumPalette`, the voice parser / matcher / recogniser, `WebRange`, `WebResponse.Stream` and how `DesktopWebServer` writes it); tests hand-traced for range cases, window planning, WAV cases, content types and the voice resolver with fakes.
Compile watch-list: `return@guarded` from inside `getOrElse { }` lambdas in `DesktopWebApi`; local `fun isOwned` used as `::isOwned`; `when` over `VoiceCommand` (18 cases, no `else`) and over `VoiceEntityKind` (4 cases) in `DesktopWebVoice`; `getOrElse { return voiceError(...) }` in `handle`; `readPixels(pixels, 0, 0, w, h)` and `Color.red/green/blue` in `paletteOf`; `String.format("#%02x...")`; `catch (_: Throwable)`; `System::currentTimeMillis` default parameter; test helpers `fun x() = runBlocking { ... }` ending in an assertion.
Unverified assumptions: googlevideo URLs carry `clen` and `mime` (else the probe and upstream header are used); googlevideo accepts 1 MiB `Range` windows with the headers from `StreamResolver.mediaHeadersFor`; `DesktopArtworkCache.load` works outside a composition and its `HttpURLConnection` follows only harmless redirects for an art-allowlisted start URL; `trackLinks` returns duration and artists; `LyricLine.timeMs` is negative or equal for unsynced lyrics; the sidecar recogniser tolerates a call from the web server thread while the desktop voice feature is idle.

### Deferred
- Home supplements (`homeSupplement`, Explore / mood shelves) and more than 3 pages of home.
- Artist and album pages as their own endpoints (the page uses `/api/playlist/{id}` for albums); radio / queue endpoint (`DesktopSearchClient.radio`) for "next up".
- `voice` SSE event, local desktop playlists in voice, webm/opus voice upload.
- No test opens a real socket or touches YouTube; stream proxy behaviour (206, aborts, refresh) is only unit-tested at the planning level.

### Exact next step
Build in GitHub Actions and fix errors from the watch-list. By hand: Settings > Advanced > Experimental > Muses Web on; with the page's cookie and header (browser devtools `fetch('/api/home', {headers:{'X-Muses-Web':'1'}})` on `http://localhost:8765/`) check `/api/home`, `/api/search?q=...`, `/api/track/<id>` (palette has 3 colours), then open `/api/stream/<id>` in the address bar (plays in the tab; seeking sends `Range`), `/api/art?u=` with an allowed and a foreign URL (second one must be 400). Then run WEB3 with this zip.


## WEB3: the web UI, light by design (JS/CSS/HTML only; NOT compiled, NOT run in a browser)

### Did
The browser app is now real: `desktopApp/src/main/resources/web/` (served by the WEB1 server from the classpath folder `web/`). Plain HTML, CSS and ES modules, no framework, no CDN, no build step, no Gradle change, no Kotlin file touched (`DesktopApp.kt` never opened).
- **Shell**: hash router (`#/home`, `#/search?q=`, `#/library`, `#/playlist/<id>`, `#/now`, `#/settings`), top bar (logo, search with typeahead, account dot), bottom mini-player, bottom tabs on phone and tablet, a left rail from 1024 px. Semantic HTML, skip link, focus rings, 44 px targets, `viewport-fit=cover` with safe-area padding. Page modules load lazily with `import()`.
- **Performance and battery**: no animation library, no canvas, no JS animation. One static glass style (`backdrop-filter` on the top bar and mini-player only; solid when unsupported, when the saver is on, or with reduced transparency). CSS transitions only on `transform`/`opacity`, only inside `prefers-reduced-motion: no-preference` and with the saver off. `content-visibility: auto` on rows, cards and sections; images are `loading="lazy" decoding="async"` with explicit sizes; shelves use CSS scroll-snap. No polling: SSE plus the audio element's own events. Progress updates at most 4 times a second (`timeupdate` throttled to 250 ms) and not at all while the tab is hidden; the SSE connection is closed while the tab is hidden and reopened (with 3 s to 60 s back-off on failure) when it is shown; `requestAnimationFrame` runs only for the synced-lyrics highlight, only on the Lyrics tab, only while playing and visible.
- **Themes**: System, Light, Dark, AMOLED black, Album tint, plus a Battery saver switch, in `localStorage` (`muses.theme`, `muses.saver`). `js/boot.js` (a tiny classic script) applies them before first paint. Album tint is two fixed layers with a static gradient from the `/api/track` palette (hex-validated), cross-faded with opacity; no moving shapes.
- **Pages**: Home (the server's shelves), Search (typeahead from `/api/suggest`, sections), Library (Liked music, playlists, saved), Playlist (also albums, artists, `LM`; Play, Shuffle, per-row More menu: Play next, Add to queue, Add to playlist, Like), Now Playing (cover, title, scrubber, shuffle/prev/play/next/repeat, like, tabs Player / Lyrics / Queue), Settings (theme, saver, account state, a note that the desktop audio engine is not used).
- **PWA**: `manifest.webmanifest`, `sw.js` (version string `muses-web-3.0.0`, caches only the app shell, never `/api/*` or `/pair`, never audio), update prompt toast with Reload (`js/pwa.js`). Icons come from `Logo.png`: `icons/icon-64.png`, `icon-192.png`, `icon-512.png` (palette-quantised, 1.4 KB, 4 KB, 14 KB).
- **`js/api.js`** is the only module that calls the server: `credentials: same-origin`, `X-Muses-Web: 1` on every fetch, the `{error, code}` envelope turned into `ApiError`, one retry for GET on a network error or 502/503/504, abort signals per navigation, a 403 raises an `auth` event.
- **Size**: first load of Home is about 59 KB uncompressed (HTML 6.7, CSS 13.4, 13 small JS files about 40 KB); all web files together are about 78 KB plus icons. Budget was about 150 KB.

### Files
New, all under `desktopApp/src/main/resources/web/`: `index.html`, `app.css`, `app.js`, `manifest.webmanifest`, `sw.js`, `icons/icon-{64,192,512}.png`, `js/{boot,util,dom,api,events,theme,player,ui,mini,searchbox,router,pwa}.js`, `js/pages/{home,search,library,playlist,now,settings}.js`.
New elsewhere: `docs/WEB_UI_CHECKLIST.md` (the manual checklist), `desktopApp/web-tests/util.test.mjs` and `player.test.mjs` (optional Node 22+ logic tests, not in the Gradle build).
Removed: `resources/web/.gitkeep` (the folder now has real files). The WEB1 tests inject their own loader, so none depends on an empty folder (checked).

### Decisions (no questions allowed)
- **A minimal `player.js` exists already** because Now Playing, the mini-player and "play" on every page need playback. It has one `<audio>`, a queue (play list, play next, enqueue, jump), shuffle (reorders the queue, current first; off restores the original order), repeat off/all/one, previous (restarts after 3 s), a media-error skip (at most 3 in a row), like. **WEB4 extends this file and keeps these names**: `player.on(type, fn)` with `track`, `details`, `queue`, `state`, `time`, `error`; `playList`, `enqueue`, `playNext`, `jumpTo`, `toggle`, `play`, `pause`, `seek`, `next`, `prev`, `toggleShuffle`, `cycleRepeat`, `like`, getters `current`, `queue`, `index`, `details`, `playing`, `shuffle`, `repeat`, `time`, `duration`, `audio`. Still to do in WEB4: preload the next track, resume from `localStorage`, the per-track retry, volume and mute, Media Session.
- **Settings view is small** (theme, saver, account, about this mode). WEB4 adds stream quality hint, voice options, "Open desktop window", licences in `js/pages/settings.js`.
- **Navigations are network-first in the service worker.** The server hands out the session cookie only on a page load, so a cache-first shell would leave a restarted browser without a cookie. The cache is only the offline fallback. A new `VERSION` in `sw.js` installs in the background and the page offers "Reload"; bump it with every web change.
- **Session renewal**: the token is per desktop launch. A 403 from the API reloads the page once (a page load renews the cookie); a second 403 in the same tab session shows a sticky "not allowed" toast with a Reload button instead of looping. `sessionStorage` key `muses.reloaded`.
- **No inline anything** (CSP from WEB1): no inline script or style, no `style=` attributes, `el.style.setProperty` only for the progress bar and tint layers; all server text goes in through `textContent`.
- **Like is a state the list does not carry**: `Track` has no `liked` field, so row menus offer "Like" (and "Remove from liked music" on `LM`), and the heart on Now Playing uses the `liked` flag of `/api/track`.
- **Covers are resized by the page**: for `lh3.googleusercontent.com` / `ggpht.com` cover URLs ending in `=w544-h544...` or `=s544`, `js/util.js artSrc` asks the proxy for a size close to what is shown (the `u` parameter is rewritten; the server still allowlists the host). Other URLs are unchanged.
- **Search text is never stored**: not in localStorage, not in the page title, only in the URL hash and the field.
- **Top bar slot for WEB4's microphone button**: `<div class="top-actions" id="top-actions">` in `index.html`; put the button before `#avatar`. `js/api.js` already exports `postRaw(path, raw, type)` for `/api/voice`, and `js/events.js` forwards a `voice` SSE event as a `CustomEvent` on `bus` with no change.
- The "Search" tab and the top search field are both kept; the tab goes to `#/search` and focuses the field.

### Verified
**No Kotlin was changed in WEB3, so there is nothing new to compile; the Kotlin of WEB1 and WEB2 is still NOT compiled and its tests NOT run. The web UI was NOT opened in any browser, NOT run against the real server, and no real endpoint was called.**
What was actually done (here, by script): `node --check` on every JS file (all pass, `boot.js` as a classic script); a script that checks every `import {...}` resolves to an existing export and every dynamic `import()` to an existing file (0 problems); every icon used exists in the sprite; every `getElementById` id exists in `index.html`; the `SHELL` list in `sw.js` equals the files on disk; `index.html` has no `style=` and no inline script. Two Node logic tests were run and pass: `util.test.mjs` (8 groups: time format, hash parsing, cover resize, lyric search, shuffle, colour and tint validation, track mapping) and `player.test.mjs` (9 groups against a fake `<audio>`: start, next/end/repeat all, previous, repeat one, shuffle on/off order, play next and enqueue, end of queue, error skipping, tracks without id).
Watch-list for the first real browser run (things only a browser can show): the sprite `<use href="#i-...">` icons render (the `<svg class="sprite">` is 0 x 0); `content-visibility: auto` with `contain-intrinsic-size` on rows and cards (scroll jumps?); `dialog.showModal()` menus; `crossorigin="use-credentials"` on the manifest link; the settings gear path is typed from memory and may look slightly off; the tint layers sit at `z-index: -1` above the `html` background (the body is transparent on purpose); `EventSource` reconnect while the server restarts; seek bar drag vs `timeupdate`.
Unverified assumptions about the server (WEB1/WEB2) that the UI relies on: `/api/home` items have `type`, `title`, `subtitle`, `art`; song `Item.id` is the videoId; non-song `Item.id` works in `/api/playlist/{id}`; `/api/track` returns `art:{small,large}` and `palette`; cover URLs from the art proxy keep the original size suffix inside the `u` parameter; the first `signedIn` SSE event after connect carries the current state.

### Deferred
- WEB4: the rest of the player, Media Session, click-to-speak, full Settings, README and `docs/WEB_SERVER.md`, battery notes, extended checklist (see decisions).
- Home supplements, artist and album pages as their own views, "next up" radio (also deferred in WEB2).
- A size parameter on `/api/art` (the page rewrites Google cover URLs instead).
- Translations: all UI strings are English literals.
- Icons for the maskable PWA purpose; a hostname (`http://PCNAME:8765`) is still not allowlisted (WEB1).

### Exact next step
Build in GitHub Actions (WEB1 and WEB2 Kotlin are still the unknown). Then by hand, with Muses Web on: open `http://localhost:8765/` and go through `docs/WEB_UI_CHECKLIST.md` sections 1 to 8; note any CSP message in the console and report it. Optional before that: `node desktopApp/web-tests/util.test.mjs` and `node desktopApp/web-tests/player.test.mjs` (Node 22+). Then run WEB4 with the returned zip.


## WEB4: the player, click-to-speak and polish (JS/CSS/HTML and docs only; Kotlin NOT compiled, web UI NOT run in a browser)

### Did
Only files under `desktopApp/src/main/resources/web/`, `desktopApp/web-tests/`, `docs/`, `README.md` and this file changed. **No Kotlin file and no Gradle file was touched** (`DesktopApp.kt` never opened).
- **Player** (`js/player.js`, same names as WEB3, extended): `playNow`, `seekBy`, `setVolume`, `toggleMute`, `volume`, `muted`, `volumeSettable`, `quality`/`setQuality`, `restore`, `hydrateQueue`, `parseResume`; new event `volume`. Warm-up of the next track: when the current song has 20 s left, `/api/track/<next>` is requested, then ONE second hidden `Audio` gets `preload="auto"` and the stream URL (once per track, dropped on every track change; never when repeat is one or at the end of the queue). Resume: `muses.resume` in `localStorage` holds ids, index, position in seconds, shuffle and repeat (no titles, no search text; at most 200 ids), written on pause, track change, queue edits, tab hidden, `pagehide`, and at most every 10 s while playing from the existing `timeupdate` (no timer). One automatic retry per track (same URL, position kept), then the message `"<title>" could not be played. Skipping to the next song.` and a skip (at most 3 skips in a row, as in WEB3). End of queue: last song stays, paused, position reset in the resume record; Play restarts it. Volume and mute: `muses.vol`; the slider row is hidden where `audio.volume` is read-only (iOS Safari).
- **Media Session** (`js/mediasession.js`): metadata (title, artist, album, three artwork sizes), play, pause, stop, previous, next, seekbackward/forward, seekto, `playbackState`, `setPositionState` only on play, pause, seek, duration change and track change (the OS interpolates; no timer). Pure `metaFor` and `positionFor` are exported and tested.
- **Click-to-speak** (`js/voice.js` plus pure `js/voice-core.js` and `js/voice-worklet.js`): microphone button in the top bar (inserted before the account dot by `initVoice`) and a large one on Now playing (`attach(button)`). Microphone first, `Listening` only on the first audio frame, optional beep afterwards with the frames of the beep dropped; energy end-pointer (noise floor from the first 300 ms, 600 ms silence, 8 s cap, 5 s without speech = nothing sent); manual stop by a second click; optional "Hold to talk" (pointer press/release, keyboard still toggles); `AudioWorklet` capture with a `ScriptProcessorNode` fallback; resample to 16 kHz (box average), 16-bit mono WAV, 300 ms of near-silence (a few LSB of repeatable noise, not digital zero) before and after; `POST /api/voice` via `postRaw`; panel with `Heard: ...`, up to 3 choices, auto-hide after 6 s (30 s with choices); the answer is executed in the tab (play, playlist, add to playlist, add to last playlist, choose, choose other, pause, resume, next, previous, volume up/down/set, shuffle, like, what is playing). Microphone tracks are stopped and the audio context closed before the request is sent; a hidden tab cancels a recording; works on `localhost`/HTTPS only with a clear message otherwise.
- **Settings** (`js/pages/settings.js`): theme, battery saver, Playback (low-bitrate hint), Voice (hold to talk, beep, why the microphone may not work), Desktop window (tray text), account, About and licences.
- **Shell hooks**: `app.js` (`player.restore()`, `initMediaSession()`, `initVoice()`), `index.html` (three sprite icons `mic`, `volume`, `mute`), `js/pages/now.js` (microphone button, volume row, queue names and `hydrateQueue`), `js/api.js` (`streamUrl(videoId, low)`), `app.css` (appended block), `sw.js` (`VERSION` now `muses-web-4.0.0`, four new files in `SHELL`).
- **Docs**: README section "Muses Web", `docs/WEB_SERVER.md` (API, security model, headless mode, what the page stores, battery notes), `docs/WEB_UI_CHECKLIST.md` extended (sections 9 to 15: playback, hidden tab and media keys, volume, voice, resume, settings, idle).
- **Tests** (Node 22+, not in Gradle): `player.test.mjs` extended to 17 groups (retry, retry keeps position, volume, quality hint, warm-up once, no warm-up at the end, resume writes ids only, `parseResume`, restore into a fresh player), new `voice.test.mjs` (20 groups: end-pointer cases, resampling, padding, WAV header, size limits, `planFor` for every action, `pickCandidate`, `volumeAfter`, `metaFor`, `positionFor`).

### Files
Changed: `web/js/player.js`, `web/js/api.js`, `web/js/pages/now.js`, `web/js/pages/settings.js`, `web/app.js`, `web/app.css`, `web/index.html`, `web/sw.js`, `README.md`, `docs/WEB_UI_CHECKLIST.md`, `desktopApp/web-tests/player.test.mjs`, `HANDOFF.md`.
New: `web/js/mediasession.js`, `web/js/voice.js`, `web/js/voice-core.js`, `web/js/voice-worklet.js`, `docs/WEB_SERVER.md`, `desktopApp/web-tests/voice.test.mjs`.

### Decisions (no questions allowed)
- **`voice-core.js` and `voice-worklet.js` exist beside `voice.js`**: the pure part (end-pointer, WAV, plan mapping) is testable in Node without a browser; the worklet must be its own same-origin file (CSP, `addModule`).
- **No MediaRecorder fallback**: the plan allowed it, but the server takes WAV only (WEB2), so a webm recording would need a decoder first. The fallback for browsers without `AudioWorklet` is `ScriptProcessorNode` (deprecated but present everywhere), which also gives live frames for the end-pointer. No microphone API at all = clear message.
- **Beep is off by default** (Settings > Voice). Frames during the beep (90 ms + 60 ms) are dropped, so a user who speaks at once could lose the first 150 ms; the 300 ms padding does not cover speech that is dropped. Off by default means no risk for the plan's main requirement.
- **Hold to talk is a setting, default off**; in that mode silence does not stop the recording, only release or the 8 s cap.
- **Nothing is sent when no speech was heard** (manual stop included): "I did not hear anything".
- **Voice choices** (`chooseOther` = the second candidate; `choose` = the n-th) use candidates kept in memory for 30 s and dropped. "Last playlist" is kept in memory in this tab for 10 minutes (title and id of a playlist, not search text).
- **Restore on page load** sets `preload="metadata"` for the saved song, so opening the page with a saved queue makes one small stream request (and a `/api/track` call) even if nothing is played. Chosen so the saved position shows correctly.
- **Warm-up is a warm-up, not gapless playback**: the second `Audio` makes the server resolve the stream and fills the browser's cache; the main element still loads the next song itself (there may be a short gap).
- **"Open desktop window" is text, not a button**: no endpoint exists for it and WEB4 was not allowed to add Kotlin blind. The page says: tray icon > Show the desktop window.
- **Quality hint** (`?q=low` on the stream URL, setting stored as `muses.quality`) is honest in the UI: the server's stream route ignores query parameters today (read from `DesktopWebApi.kt`: only the path parameter and `Range` are used).
- **Voice panel is solid** (no `backdrop-filter`): the plan allows blur only on the top bar and mini-player.
- Queue entries restored from ids show `Song` until `hydrateQueue(8)` fills their names from `/api/track` (one request at a time, once per track).
- About text says the icons follow Google's Material Icons (Apache 2.0); WEB3 did not record where the sprite paths came from. **Check this and fix the sentence in `js/pages/settings.js` if the icons were drawn by hand.**

### Verified
**The Kotlin of WEB1 and WEB2 is still NOT compiled and its tests NOT run. Nothing in the web UI was opened in a browser, nothing was run against the real server, no microphone was used, no real song was played.**
What was actually run here: `node --check` on every JS file; the three Node test files pass (`util.test.mjs` 8 groups, `player.test.mjs` 17, `voice.test.mjs` 20; they use a fake `Audio`, `document`, `fetch` and `localStorage`); a script confirmed that every `import {...}` resolves to an existing export and every dynamic `import()` to an existing file, that every icon used exists in the sprite, that every `getElementById` id exists in `index.html`, that the `SHELL` list in `sw.js` equals the files on disk, that `index.html` has no `style=` and no inline script, that no `setInterval` is used and that the only `setTimeout` calls are the existing debounce, toast and retry ones plus two in `voice.js` (panel hide, candidate expiry, both started only by a voice request). First load of Home (HTML, CSS, `boot.js`, `app.js`, the 13 static modules, manifest) is about 101 KB raw (about 37 KB gzipped); budget was about 150 KB.
Watch-list for the first real browser run: `AudioWorklet` `addModule('/js/voice-worklet.js')` under the CSP (it falls under `script-src`, which falls back to `default-src 'self'`; if the console shows a CSP error the code silently uses the `ScriptProcessorNode` fallback); the 0-gain node that keeps the graph pulled; echo cancellation and noise suppression changing levels so that the end-pointer thresholds (0.012 to 0.08 RMS, three times the first 300 ms) need tuning; `setPointerCapture` for hold to talk; OS overlays not showing the cover (artwork is fetched through `/api/art`, which needs the cookie); `setPositionState` after `durationchange` with a stream whose duration is `Infinity` at first; the `preload` warm-up making a second range request that counts against the server's 3-stream limit (a second tab can get 429); sprite paths for the new icons (typed from memory); `details.about` styling; the `hidden` attribute on `.vol-row` (the class sets no `display`, so `hidden` should work).
Unverified server assumptions the page relies on (from WEB2): `/api/voice` accepts our WAV (RIFF, `fmt ` 16 kHz mono 16-bit, `data` at offset 36, 44-byte header); `answer.item.id` for a playlist works in `/api/playlist/{id}`; `answer.value` for `choose` is a number string; a stream request with `?q=low` is routed like one without the query.

### Deferred
- A server endpoint plus button for "Open desktop window" (needs Kotlin in `DesktopWebRuntime` and the tray code).
- Honouring the quality hint on the server (needs a per-request cap in `StreamResolver`, not touched).
- True gapless playback (swap the warmed element in, or a second main element).
- HTTPS for LAN microphone use, a `voice` SSE event, webm/opus upload, radio-based "next up".
- Translations (all page strings are English literals), maskable PWA icons.
- A "forget saved queue" button (the resume record can be removed in DevTools or by clearing site data).

### Exact next step
Build in GitHub Actions and fix the compile errors of WEB1 and WEB2 (watch-lists in those sections). Then, Muses Web on, open `http://localhost:8765/` and run `docs/WEB_UI_CHECKLIST.md` sections 9 to 15 (play, seek, hidden tab with media keys, voice on localhost, battery saver, reduced motion, offline shell). Report: any CSP line in the console, whether the voice end-pointer cuts or lingers, whether the OS overlay shows the cover, and the Task Manager numbers for `--web` mode against the window mode. Optional before that: `node desktopApp/web-tests/player.test.mjs`, `voice.test.mjs`, `util.test.mjs`.


## EDGE 1: geometry and edge detection (Kotlin NOT compiled, tests NOT run)

### Did
Part 1 of `MUSES_EDGE_PANEL_PLAN.md`: the pure geometry and the edge watcher, as new files only. **No existing Kotlin, Gradle or resource file was edited** (so there are no `// EDGE 1 hook` markers); `DesktopApp.kt` never opened; the abandoned web work untouched.
- `DesktopEdgeGeometry`: monitor selection (cursor / primary / pick N, stable left-to-right order), work area from insets, docked open and hidden rectangles for Left/Right, panel width clamp (340 to 640 dp, never wider than the work area minus the gaps), edge-zone test (2 to 12 px, optional vertical segment in percent, shared-edge rule), panel-plus-24 px test, slide maths (ease-out cubic in, ease-in cubic out, durations Fast 140/120, Normal 240/200, Slow 360/300 ms, `reduceAnimation` = duration 0), interruption (`retarget` continues from the current x, duration proportional to the distance left), clamp to the display.
- `DesktopEdgeWatcher`: poll loop on one daemon coroutine (`Dispatchers.Default`), all logic in `tick()` with injectable `EdgePointerSource`, `EdgeMonitorSource`, `EdgeFullscreenProbe`, `EdgeClock`. Dwell (default 150 ms, clamped 50 to 400), adaptive poll (80 ms; 150 ms after 5 s without movement or in low power; 40 ms in the zone or while BUSY), events `RevealRequested` and `Sample`.
- `DesktopEdgeWindows`: own minimal `EdgeUser32` (GetAsyncKeyState, GetForegroundWindow, IsWindowVisible, GetWindowRect, GetClassNameW, GetWindowThreadProcessId, MonitorFromWindow, GetMonitorInfoW), lazy and Windows only; `WindowsFullscreenProbe`, `EdgeButtons`, `AwtEdgePointerSource` (`MouseInfo`), `AwtEdgeMonitorSource` (`GraphicsEnvironment` + `Toolkit.getScreenInsets`, cached).
- The plan file was not in the repo root under the name the task gave; the attached `MUSES_EDGE_PANEL_PLAN_3_BALANCED_PARTS.md` was copied to the root as `MUSES_EDGE_PANEL_PLAN.md`.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEdgeGeometry.kt`, `DesktopEdgeWatcher.kt`, `DesktopEdgeWindows.kt`; tests `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopEdgeGeometryTest.kt` (29 tests), `DesktopEdgeWatcherTest.kt` (26 tests); root `MUSES_EDGE_PANEL_PLAN.md`. Changed: `HANDOFF.md`.

### API Part 2 builds on
- `DesktopEdgeWatcher.forSystem(config, mode, onEvent)`; `start(scope)`, `stop()`, `isRunning`. `mode()` must return `EdgeWatchMode.HIDDEN` while the panel is hidden and `BUSY` for REVEALING/HIDING/PEEK/ACTIVE/PINNED.
- Events arrive on the watcher thread, not the AWT thread: Part 2 must hand over (e.g. `SwingUtilities.invokeLater` or a main-dispatcher launch) before touching the window. `Sample` is sent only in BUSY, so the 700 ms hide timer and the 24 px test (`insidePanelPlusMargin`) belong to Part 2 and run on those samples.
- `DesktopEdgeGeometry.layout(monitor, side, widthDp)` gives `open` and `hidden`; `retarget(running, restX, targetX, layout, now, speed, reduce)` gives the next `EdgeSlide`; `slide.xAt(now)` per frame.
- `AwtEdgeMonitorSource.invalidate()` is available; it also refreshes by itself when `DesktopDisplayEvents.epoch` changes.

### Decisions (no questions allowed)
- **Units**: everything is AWT user space; dp to units uses `GraphicsConfiguration.defaultTransform.scaleX`, the same assumption `DesktopDisplayWatcher` already makes.
- **Hidden position** is beyond the monitor's own edge (not the work-area edge), so a taskbar on that side leaves no sliver.
- **Shared edges** (a neighbour monitor at that height) are not a screen edge and never trigger (`requireOuterEdge`, default true); on the outer part of a taller monitor the zone works. Part 3 may expose the switch.
- **Fullscreen**: a fullscreen foreground window on ANY monitor suppresses the reveal. Mapping the AWT monitor to the Windows monitor needs DPI conversion; a wrong guess is worse than a missed reveal. Window and monitor rectangles both come from Windows (`GetWindowRect`, `GetMonitorInfoW`), so scaling cannot make them disagree. Shell classes `Progman`, `WorkerW`, `Shell_TrayWnd`, `Shell_SecondaryTrayWnd` and our own process are ignored. The probe runs only when a reveal is otherwise due and at most every 500 ms.
- **One reveal per entry**: after `RevealRequested` the cursor must leave the zone before another. After the panel was BUSY and goes HIDDEN, the cursor must also leave the zone once (no re-open loop after a keyboard hide).
- **Held button** (left, right or middle) restarts the dwell; the watcher asks `GetAsyncKeyState` only when it matters (in the zone, or BUSY).
- **Neighbour overlap while sliding**: a window sliding past a monitor edge is visible on a monitor next to it. `hasNeighbourOnSide` tells Part 2 when that happens. Not solved in Part 1; Part 2 may accept it (the slide is 240 ms) or snap without animation on such an edge.
- Taskbar/Alt-Tab hiding is not touched here (Part 2/3, only if a safe API exists).

### Verified
**NOT compiled and NOT run: no Kotlin compiler or Gradle was available here, and no screen.** What was done: every new file was read through for imports, nullability, and `internal` visibility; a grep confirmed none of the new type names exists elsewhere in `desktopApp/src`; the expected values in the 55 tests were traced by hand against the code (zone boundaries, two monitors of different heights, a monitor at negative x, taskbar on each side, 150 percent scale, interrupted slide continuity, dwell/fullscreen/held-button scripts with the fake clock). No Gradle dependency was added or changed.
Watch-list for the first build: `EdgeUser32` through `Native.load` with `W32APIOptions.DEFAULT_OPTIONS` (same as `VoiceUser32`; `GetClassNameW` takes a `CharArray`, `GetWindowThreadProcessId` an `IntArray`); `Memory.getInt(0..16)` literal-to-Long conversions; `fun interface EdgeClock` with a companion; the `startAndStopTheLoop` test uses a real `Dispatchers.Default` job but no sleeping.

### Deferred
- Everything of Parts 2 and 3: state machine, window integration, slide driver, header, settings, tray, checklist.
- Mapping the cursor's monitor to the Windows monitor for a per-monitor fullscreen test.
- Handling the neighbour-monitor overlap during a slide.

### Exact next step
Build (compile check) with this zip, fix any error in the three new files or two tests, then run Part 2 with the returned zip.


## EDGE 2: panel controller, window integration and engine tests (Kotlin NOT compiled, tests NOT run)

### Did
Part 2 of `MUSES_EDGE_PANEL_PLAN.md` (the task called it E2). The panel is the existing Compose window changing mode; nothing is a second window. `DesktopApp.kt` was never opened whole (one `grep -n` and one 12-line `sed`, one anchored insertion).
- **State machine** (`DesktopEdgePanelMachine.kt`, pure): `HIDDEN / REVEALING / PEEK / ACTIVE / PINNED / HIDING` as a `StateFlow<EdgeView>` (state + sticky pin + "activated"). Inputs: watcher reveal and samples, press inside, typed key, focus gained/lost, pointer in window, dialog/popup open, pin, hide now, show now, slide finished. Output: commands `SLIDE_IN / SLIDE_OUT / PARK`. Also in this file: `EdgePanelConfig` (every Part 3 setting with defaults, `coerced()`, `watchConfig()`), `EdgeWindowFlags` (what `Window(...)` reads), `EdgeFrame.intervalMs`, `EdgeRestore.target`.
- **Controller** (`DesktopEdgePanel.kt`): `enterPanel(reveal=false)`, `leavePanel()`, `togglePanel()`, `showNow()`, `hideNow()`, `setPinned/togglePin`, `onDisplayChanged()`, `setDialogOpen/setPopupOpen`, `attach/detach(window, state)`, `grantFocus`. Docks hidden, runs the edge watcher only while the mode is on, slides with a Swing `Timer` that exists only while a slide runs (interval from the monitor's refresh rate, position from elapsed time via `EdgeSlide.xAt`, interruption continues from `window.x`), parks the window after a hide. Mode off: no watcher, no timer, no per-frame work.
- **Window integration** (`Main.kt`, marked `// EDGE 2 hook`): `visible = visible && edgeFlags.shown`, `alwaysOnTop`, `focusable`, `resizable` come from `DesktopEdgePanel.flags` (with the mode off they equal today's `true/false/true/true`); `DisposableEffect` attach/detach; `LaunchedEffect(focusable)` calls `requestFocus()` once the first click made it focusable; the opening-size block is skipped while in panel mode (a rebuilt window keeps the panel size); `Modifier.edgePanelPointer()` on the root Box (`DesktopEdgePanelHost.kt`, press and enter/exit only, consumes nothing).
- **Other hooks**: `DesktopDisplayWatcher` (`applyMinimumSize` returns and `adapt()` re-docks instead of clamping a normal window while in panel mode), `DesktopWindowVisibility.show()` (tray "show" slides the panel in), `DesktopOverlays.anyDialogOpen`, and one `LaunchedEffect` in `DesktopApp.kt` after `val overlays` that feeds it to `setDialogOpen`.
- **Tests**: `DesktopEdgePanelMachineTest` (40 tests: every transition, delays, held button, dialog/popup, typing, pin stickiness, hide-now, stale slide finish, unknown cursor, margin boundary 23/25 px, focus rules, flags, config clamping and mapping, frame interval, restore with negative x / unplugged monitor / maximized) and `DesktopEdgePanelPathTest` (10 tests: the REAL `DesktopEdgeWatcher` with fake pointer, monitor, fullscreen probe and clock feeding the machine: brush 100 ms = nothing, dwell 150 ms = reveal once, leave 400 ms and return = no hide, leave 700 ms = hide, held button, pinned, fullscreen foreground, no re-open loop after a hide, mode off). Geometry and interrupted-slide continuity are covered by the 29 tests of Part 1 (`DesktopEdgeGeometryTest`).

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEdgePanelMachine.kt`, `DesktopEdgePanel.kt`, `DesktopEdgePanelHost.kt`; tests `DesktopEdgePanelMachineTest.kt`, `DesktopEdgePanelPathTest.kt`.
Changed (hooks only): `Main.kt`, `DesktopDisplayWatcher.kt`, `DesktopWindowVisibility.kt`, `DesktopOverlays.kt`, `DesktopApp.kt`, `HANDOFF.md`.
No Gradle file, no `sharedUi`/`shared`/`app` file, no audio/lyrics/library/account file, nothing of the web work was touched.

### Contract for Part 3 (names Part 3 must use, not re-invent)
- Settings: build an `EdgePanelConfig` from `DesktopEdgeSettings` and assign `DesktopEdgePanel.config.value = ...` (a live change re-docks a visible panel by itself). Fields: side, widthDp, monitor (+ pickedMonitor), zoneWidthPx, segment, dwellMs, closeDelayMs (300..2000), speed, suppressWhenFullscreen, lowPower, pinByDefault. `coerced()` does the validation.
- Enter/leave: top-bar button and shortcut call `DesktopEdgePanel.togglePanel()` (reveals once, so the owner sees where the window went); "Start in side panel mode" calls `enterPanel(reveal = false)` once the window exists (safe before it exists: it waits for `attach`). Tray: `togglePanel()`, `showNow()`.
- Header: reads `DesktopEdgePanel.view` (`state`, `pinned`); buttons call `togglePin()`, `leavePanel()` (Expand), `hideNow()`. Mode flag for the layout: `DesktopEdgePanel.mode`.
- "Test slide" button: `hideNow()` then `showNow()` (or `showNow()` when hidden).
- Popups that are not in `DesktopOverlays` (menus) should call `setPopupOpen(true/false)`.

### Decisions (no questions allowed)
- **`enterPanel()` docks hidden** (plan and acceptance); the button path uses `togglePanel()`/`enterPanel(reveal = true)`, which slides in once so the owner does not watch the window vanish. Start-up uses `reveal = false`.
- **Pin is sticky across a hide** (next reveal is PINNED, still not focusable until a click); leaving the mode un-pins. "Hide" works while pinned.
- **Typing rule**: the app cannot see which Compose text field has focus, so a typed character (AWT `KEY_TYPED` while the window is active) delays closing until 4 s after the last one; the 700 ms away time must also have passed. A dialog or popup open blocks closing and restarts the away clock when it closes.
- **A held mouse button** anywhere restarts the away clock (not only while over the panel).
- **Unknown cursor or no geometry never closes the panel.** The cursor inside the Compose root (enter/exit events) also counts as near.
- **Tray hidden = feature paused**: an edge reveal is ignored while `DesktopWindowVisibility.visible` is false. Tray "show" while in the mode slides the panel in.
- **Fixed monitor choice** (primary / picked): a dwell on another monitor's edge is ignored; with "cursor" the panel opens on the cursor's monitor. The slide **snaps** (no animation) on an edge shared with a neighbour monitor (Part 1's deferred overlap).
- **First show** (Bloom's lesson): after docking the window is "shown" off screen for 250 ms (`warmup`), then `visible` goes false. A rebuilt window in panel mode does the same.
- **Swing `Timer`** instead of a coroutine for the slide: same thread (AWT), JDK only, exists only while sliding. The slide waits (at most 300 ms) for the window to be showing before it starts moving, because `visible` reaches AWT one recomposition later.
- **Focus**: `focusable` is false until the first press inside (`activated`); then `requestFocus()` after the flag has reached the window. Focus lost while ACTIVE (no dialog, cursor away) returns to PEEK and non-focusable.
- **Leaving** restores the saved bounds clamped to the monitor that holds them (or a centred 1220x780 dp when the window was maximized, then maximizes again), sets the normal minimum size, un-pins, and shows the window.
- **Taskbar / Alt-Tab hiding: not done.** No safe existing API (it would need `WS_EX_TOOLWINDOW` through a new JNA/native call); the panel stays in Alt-Tab.
- **`resizable = false` in panel mode** is passed through Compose as the plan says; the native frame (`window_jni.cpp`) was not edited, so its resize borders may still react. Every slide end re-asserts the full panel rectangle, which undoes any stray resize. Not verified.

### Verified
**NOT compiled and NOT run: no Kotlin compiler, Gradle or screen was available.** Done by hand: brace/paren balance of every touched file by script; every new type name grepped for duplicates in `desktopApp/src` (none); imports, nullability, visibility (all `internal`, no private type in an internal signature) read through; the expected values of all 50 new tests traced against the code, including the 700 ms and 4 s boundaries, the 23/25 px margin and the restore maths. No Gradle dependency added; only JDK/AWT/Swing and APIs already used in the repo (`Window(alwaysOnTop, focusable)` is already used by `DesktopVoiceOverlay`, `awaitPointerEventScope` by `DesktopArtTransitionWiring`).
Watch-list for the first build and run: `::onCommand` bound reference in the `machine` initializer; `Timer(interval) { post { ... } }` SAM conversion; `config.drop(1).collect { }` in `init`; Compose applying `alwaysOnTop`/`focusable`/`resizable` changes to an undecorated transparent window with the custom native frame (toggling `resizable` may nudge the size by a few px or change the frame style: if so, drop that parameter and keep the minimum size); `requestFocus()` after a click on a non-focusable window (Windows may refuse; if typing does not work after the first click, call `toFront()` too); the first reveal after `warmup`; the slide start waiting for `isShowing`; `DesktopDisplayWatcher.adapt()` during a rotation in panel mode; Windows maximized -> floating wait (30 x 30 ms) before docking.

### Deferred
- All of Part 3: panel header (`DesktopEdgePanelHeader.kt`), Settings (`DesktopEdgeSettings.kt`, `DesktopEdgeSettingsUi.kt`), top-bar button, tray items, in-app shortcut, layout checks at 340..640 dp, `docs/EDGE_PANEL_CHECKLIST.md`. Until then the mode can only be entered from code (`DesktopEdgePanel.enterPanel()`), and no UI shows pin / expand / hide.
- Hiding the panel from Alt-Tab and the taskbar.
- Mapping the cursor's monitor to the Windows monitor for a per-monitor fullscreen test (still "any monitor").
- Controller-level code (AWT, Timer, window flags) has no unit test: it needs a window. Only its pure parts are tested.

### Exact next step
Build (compile check) with this zip and fix any error in the three new files, the two new tests and the five hooks. Run `desktopApp` tests (`DesktopEdgePanel*Test`, `DesktopEdge*Test`). Then run Part 3 with the returned zip. Optional before that, to see the engine work with no UI: call `DesktopEdgePanel.enterPanel(reveal = true)` from a temporary debug line and watch `desktop.log` (`edge:` lines).


## EDGE 3: panel UX, settings, tray, shortcut and checklist (Kotlin NOT compiled, tests NOT run)

### Did
Part 3 of `MUSES_EDGE_PANEL_PLAN.md` (the task called it E3). Built on the contract of EDGE 2 (`DesktopEdgePanel.config/mode/view/togglePanel/enterPanel/leavePanel/showNow/hideNow/togglePin/setPopupOpen`, `EdgePanelConfig.coerced()`); no engine file (`DesktopEdgePanel*`, `DesktopEdgeGeometry`, `DesktopEdgeWatcher`, `DesktopEdgeWindows`) was changed. `DesktopApp.kt` never opened whole (greps and short `sed` slices, then exact-match anchored edits by script that assert each anchor occurs once).
- **Panel header** (`DesktopEdgePanelHeader.kt`): 48 dp bar with the app name, **Pin** (accent colour when pinned), **Expand** (`leavePanel()`), **Hide** (`hideNow()`, chevron points to the docked edge); 44 dp targets; no drag. `DesktopEdgeTitleSlot()` shows it instead of `DesktopTitleBar()` while the mode is on.
- **Settings** (`DesktopEdgeSettings.kt`): `EdgeSettingsValues` (enabled, startInPanel, `EdgePanelConfig`), pure `DesktopEdgeSettingsCodec` (names, segment sliders, zone reminder text), persisted through `DesktopPersistence` (keys `edge_*`), `start()`, `update/updateConfig/setEnabled`, `testSlide()`, `togglePanelIfEnabled()`. Every change is coerced, saved and published to `DesktopEdgePanel.config` (the controller re-docks a visible panel by itself); switching the feature off while in the mode calls `leavePanel()`.
- **Settings UI** (`DesktopEdgeSettingsUi.kt`): group rows inside the existing Advanced > Experimental group: enable; start in panel mode; enter/leave and **Test slide**; side; monitor (pointer / main / pick screen 1..N); width 340..640; trigger width 2..12 px; zone whole edge or from X% to Y%; open delay 50..400; close delay 300..2000; speed; hide while fullscreen; low power watcher; pin by default; "Where is the trigger?" text (strip width, edge, delay, shortcut, tray). Nothing but the first switch shows while the feature is off. Sliders save once on release.
- **Tray** (`DesktopTrayMenu.kt`): "Side panel mode: off/on (click to enter/leave)" and, while the mode is on, "Show panel now". Present only while the feature is enabled; with it off the menu and its revision are unchanged.
- **In-app shortcut**: Ctrl+Shift+P (`DesktopEdgeShortcut`), consumed only when the feature is on; not a global hotkey.
- **Hooks (`// EDGE 3 hook`)**: `DesktopApp.kt` (6 places: `DesktopEdgeSettings.start()` effect, Settings rows, title slot, `inlineCaption` in 3 top-bar functions so no caption buttons or reserve in the panel, shortcut in the root key handler, account-menu entry plus popup-open effect), `DesktopWindowChrome.kt` (`DesktopTitleBarDragArea` is a plain box in panel mode: no move, no double-click maximize), `DesktopTrayMenu.kt`.
- **Layout check (item 2)**: grep of every `width/widthIn/requiredWidth/sizeIn` with 340 dp or more in `desktopApp/src/main`: all remaining ones are `max =` caps (account sheet 380/520, pill 440, search 420, playlist 480, listen together 360), the old fixed minimums were already removed by the 360 work. No layout file needed a change. Not exhaustive for widths below 340 dp and not seen on screen.
- **Tests**: `DesktopEdgeSettingsTest` (12 tests: defaults, start-in-panel needs the feature, every range clamp, kept values, invalid segment, segment slider minimum span, name round trips and fallbacks, slider snapping, config to watcher, config to docked rectangle for both sides and an over-wide value, reminder text, shortcut predicate). **Docs**: `docs/EDGE_PANEL_CHECKLIST.md` (two monitors, taskbar on each side, 100/125/150 %, fullscreen, drag across the edge, typing, tray, leaving, rotation, idle).

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEdgePanelHeader.kt`, `DesktopEdgeSettings.kt`, `DesktopEdgeSettingsUi.kt`; test `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopEdgeSettingsTest.kt`; `docs/EDGE_PANEL_CHECKLIST.md`.
Changed (hooks only): `DesktopApp.kt`, `DesktopWindowChrome.kt`, `DesktopTrayMenu.kt`, `HANDOFF.md`. No Gradle, `sharedUi`, `shared`, `app`, playback, lyrics, library, account or web file touched.

### Decisions (no questions allowed)
- **"Side panel" entry is in the account menu of the top bar, not a new button.** The top bar's search-field width and offsets are fixed sums of the button sizes (`134.dp + buttonSize + 8.dp`, `captionReserve`); an extra button would have broken them in a layout nobody could look at. The menu works at every width. Add a real button once someone can see the bar.
- **The panel's way out is Expand / the shortcut / the tray**; Hide only slides out. Pin is the header button; the "pin by default" setting is the engine's `pinByDefault`.
- **Feature switch gates everything**: button, tray items, shortcut and start-up mode do nothing while "Side panel (experimental)" is off (default off).
- **Sliders commit on release**, so a drag does not re-dock the panel on every step. Segment sliders keep at least 5 percent between start and end. Choosing "Part of the edge" starts from 25..75 percent.
- **Screens to pick**: the count comes from `GraphicsEnvironment.screenDevices` once per Settings view (cap 8, 1 on failure); the list order is the engine's left-to-right order, so "Screen 1" is the leftmost.
- **Test slide**: enters the mode with one reveal when it is off; when open, hides, waits 600 ms and shows again; when hidden, shows.
- **Account menu counts as a popup** (`setPopupOpen`), so the panel does not slide away under it. Other menus/dropdowns in the app were NOT wired (Part 2's `overlays.anyDialogOpen` covers dialogs only); if a dropdown closes the panel under the cursor, wire it the same way.
- **Taskbar / Alt-Tab hiding**: still not done (no safe existing API), unchanged from Part 2.
- **Optional width grip on the inner edge: not done** (plan marked it optional); width is a Settings slider.

### Verified
**NOT compiled and NOT run: no Kotlin compiler, Gradle or screen was available.** Done by hand: brace/paren balance of every touched file by script; duplicate-name grep for all new names (each exists once); imports, nullability, `@Composable` context, visibility (all `internal`, no private type in an internal signature) read through; the expected values of the 12 tests traced against the code (rounding of `roundToInt` at .5, segment clamps, dock coordinates for both sides). No Gradle dependency added; only APIs already used in the repo (`FilterChip`, `FlowRow`, `DesktopBareSlider`, `DesktopSpringButton`, `DesktopPersistence`, `Icons.Rounded.ChevronLeft/Fullscreen`, `BitChordIcons.Pin`).
Watch-list for the first build: `DesktopEdgePanel::togglePin` bound references as `() -> Unit`; `toggle(...) { on -> ... }` trailing-lambda calls on a `@Composable` function-typed parameter; `FlowRow` with `@OptIn(ExperimentalLayoutApi::class)`; `replaceFirstChar { c -> c.uppercase() }`; non-local `return` inside `synchronized` in `DesktopTrayMenu.setEdgePanel`; the `inlineCaption` lines now call `collectAsState()` (composable context in all three functions was assumed from the existing `titleBarEnabled` collect above each).

### Deferred
- A real top-bar button (see Decisions); width grip; hiding the panel from Alt-Tab/taskbar.
- Wiring other dropdown menus to `setPopupOpen`.
- Per-monitor fullscreen test; the neighbour-monitor overlap during a slide beyond the snap rule (from Parts 1 and 2).
- Translations: the new strings are English literals (the tray ones go through `DesktopStrings` with English defaults).
- Everything of the checklist (nothing was seen on a screen).

### Exact next step
Build (compile check) the zip and fix errors in the three new files, `DesktopEdgeSettingsTest` and the hooks (watch-list above); run `DesktopEdge*Test`. Then Settings > Advanced > Experimental > Side panel on, and walk `docs/EDGE_PANEL_CHECKLIST.md` sections 0 to 3 first. Send `desktop.log` (`edge:` lines) if the panel misbehaves.

## UI U1: pill pencil and song-row menus (NOT compiled)

### Did
- Removed the hover pencil on the floating pill (it was an overlay `Box` aligned TopEnd, so the layout has no gap). "Customise pill…" menu item and the Settings tile "Floating pill" are untouched.
- Playlist/album/collection page song rows: right-click opens the existing song menu (Play next, Add to queue, etc.); a "…" (`DesktopMoreButton` via the existing `songMenu`) was added to the row's action cluster.
- No new queue logic: both menu items use the existing `DesktopSongActions.onPlayNext/onAddToQueue`.

### Files
New: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSongContextMenu.kt` (`Modifier.onSecondaryPress`).
Changed (hooks marked `// UI U1 hook`): `DesktopApp.kt` only (pill pencil removed; `songContextMenu` helper beside `songMenu`; `DesktopCollectionPage` and `DesktopCollectionSongRow` got `menu` and `contextMenu` parameters; `collectionActionsWidth` +34 dp).

### Decisions
- Right-click is consumed in the Initial pass and the modifier sits before `.clickable`, so a right-click does not also play the row.
- The menu opens as a popup anchored to the row (not at the pointer); `DesktopSongMenuFor` positions it.
- Action cluster is 170 dp (with remove) / 136 dp (without) so the duration column still lines up.
- `dockHovered` in the pill function is now unused (warning at most); left in place on purpose.
- Extras (Home Recents, Library lists) NOT done.

### Verified
NOT compiled, tests NOT run. By hand: brace/paren balance of both files, single definition of `onSecondaryPress` and `songContextMenu`, imports in the new file, call-site parameter match (only one call site of each changed function).
Watch-list: `composed` import and `isSecondaryPressed` import in the new file; `songContextMenu` as a local `@Composable fun` inside the same scope as `songMenu`; `Icons.Rounded.Edit` is still used elsewhere in `DesktopApp.kt` (line ~8114), so no unused-import issue.

### Deferred
- Right-click on Home "Recents" rows and Library lists (Extras); menu at pointer position.

### Exact next step
U2 (Library playlists, pinning, playlist menu). If CI later flags U1, check the watch-list above first.

## UI U2: Library playlists, pinning, playlist menu (NOT compiled)

### Did
- Pinning: `DesktopPlaylistPins` (pure order/toggle/codec), persisted under key `pinned_playlist_ids`. Pinned playlists sort first in the Library "Playlists" shelf, in its "Show all" grid, and in the Legacy sidebar list (account and local).
- Playlist menu (`DesktopPlaylistMenu.kt`): Play, Shuffle, Play next, Add to queue, Pin/Unpin, and Rename/Delete only for playlists in the account's own list (`userPlaylists`). Opens on right-click or long-press of a playlist card (the shared cards already route right-click to `onShelfItemLongPress`) in the Library shelf and the Show-all grid.
- Library already had "Show all" (`libraryShowAll`, `LibraryGridPage`) which shows the full playlist grid; left as is.

### Files
New: `DesktopPlaylistPins.kt`, `DesktopPlaylistMenu.kt`, test `DesktopPlaylistPinsTest.kt`.
Hooked (`// UI U2 hook`): `DesktopApp.kt` only.

### Decisions
- Playlist actions open the playlist page and run when its songs have loaded (`pendingPlaylistAction` + `LaunchedEffect`); this reuses the existing loader, so no new fetch code. Side effect: Play/Shuffle/Queue also navigate to the playlist. Queue calls are the existing `enqueue`.
- Rename/Delete reuse the existing dialogs, which act on the opened page, so they too open the playlist first.
- Menu is a small centred dialog because the shared cards give no pointer position.
- Local (on-device) playlists: menu works, but no Rename/Delete (same as the page today). Pin keys: shelf `browseId`; local = `desktop-playlist:` + id.

### Verified
NOT compiled, tests NOT run. Hand-checked: balance, types (`UserPlaylist.browseId` = "VL"+id, matched with `removePrefix("VL")`), `UiState.Success`/`LibraryPage.copy`, no duplicate names.
Watch-list: `UiState.Success(success.data.copy(...))` inference in `pinnedLibraryState`; the local `@Composable fun row` in `DesktopPlaylistMenu`; `LaunchedEffect` placed at body level of the main composable (next to the one at "collectionReloads").

### Deferred
- Right-click menu on Legacy sidebar playlist rows and on "On device" cards; menu at the pointer; pin for albums.

### Exact next step
U3.

## UI U3: one set of window buttons and the Desktop interface switch (NOT compiled)

### Did
- Cause of the double buttons: in Legacy, the wide layout showed the sidebar (head draws buttons) AND `DesktopLegacyTopBar` (draws them too). Modern had one site only.
- New pure rule `DesktopCaptionPlacement.place(...)`: Modern -> top bar; Legacy wide -> sidebar head only; Legacy narrow (sidebar hidden) -> top bar; none when the system frame is used, the title bar setting is on, or in the side panel (existing rule kept). The three `inlineCaption` sites now use it, so buttons follow the layout live.
- "Desktop interface" switch: no `enabled = false` exists anywhere. Both rows (Interface group and Advanced "Navigation shell") call `DesktopInterfaceSettings.setMode`, whose flow drives `modernInterface`, so it already switches live. It looked greyed because `desktopSwitchColors()` draws an OFF switch as a 10% white track and 35% white thumb. I added "Now: Modern/Legacy" to the row's subtitle and left the colours alone (they are used by every switch).

### Files
New: `DesktopCaptionPlacement.kt`, test `DesktopCaptionPlacementTest.kt`. Hooked (`// UI U3 hook`): `DesktopApp.kt` (3 `inlineCaption` definitions, interface subtitle).

### Decisions
- Kept style and side of the buttons; only the duplicate went. Did not change `desktopSwitchColors`; if the owner wants OFF switches clearer app-wide, raise `uncheckedTrackColor`/`uncheckedThumbColor` alpha there (one place).

### Verified
NOT compiled, tests NOT run. Hand-checked: named arguments match, brace balance 0, only three `DesktopWindowButtons()` call sites exist, `DesktopEdgePanel.mode` is a `StateFlow<Boolean>`.
Watch-list: `DesktopStrings[...] + " Now: ..."` string template with nested quotes in the Interface group.

### Deferred
- If the switch still looks dead on the owner's PC, send a screenshot of ON and OFF; then change the switch colours.

### Exact next step
Gate A: push to GitHub, run the Windows workflow, send the raw error log to the PM (F prompt for fixes). Then U4.

## UI U4: side panel does not open next to a maximized window (NOT compiled)

### Did
- Log first: the fullscreen probe writes one `edge: fullscreen probe class=... window=... monitor=... style=0x... covers=... caption=... fullscreen=...` line (at most every 3 s, only when a reveal is due). The watcher writes `edge: reveal suppressed: fullscreen foreground` (at most every 5 s) when the probe blocks a reveal.
- New rule: fullscreen = `covers(window, monitor)` AND no `WS_CAPTION` (`EdgeFullscreen.isFullscreen(covers, style)`). Style read with `GetWindowLongW(hwnd, GWL_STYLE = -16)` added to our own `EdgeUser32` interface. Likely cause fixed: a maximised Chrome with an auto-hide taskbar (or a window whose frame overshoot covers the monitor) satisfied `covers` but still has a caption.
- Not used: `SHQueryUserNotificationState` (the caption test is enough and needs no new binding).

### Files
`DesktopEdgeWindows.kt`, `DesktopEdgeWatcher.kt` (hooks `// UI U4 hook`), test `DesktopEdgeWatcherTest.kt` (new test `fullscreenNeedsNoCaption`: maximised with caption is not fullscreen, borderless covering is, a small popup is not).

### Decisions
- Fails closed as before: any error means "not fullscreen".
- Chrome in F11 (real fullscreen) has no caption, so it is still blocked, as intended.

### Reveal path re-read (other things a maximised window could trigger)
- `needsLeave`: after the panel was BUSY, the cursor must leave the zone once before a reveal.
- `buttonHeld` resets the dwell: a mouse button held (e.g. dragging a window or scrollbar into the edge) never reveals.
- `requireOuterEdge` and `inEdgeZone` use AWT monitor rects; on a scaled (DPI) multi-monitor setup these can differ from the physical edge. Not changed.
- Always-on-top ordering lives in the panel host (`DesktopEdgePanelHost.kt`), not read this part.

### Verified
NOT compiled, tests NOT run. Hand-checked: brace balance of the three files, constant and name usage (`GWL_STYLE` in the probe's companion, `WS_CAPTION` in `EdgeFullscreen`), `length` in scope for the class name.

### Deferred
Nothing in code. If it still fails on the owner's PC with Chrome maximised, send the `edge:` lines from `desktop.log` (the probe line shows `covers`, `caption` and the class; if `fullscreen=false` and no reveal, the cause is zone, button held or `needsLeave`, not fullscreen).

### Exact next step
U5 (panel grey strip). Gate A first if not yet run.

## UI U5: side panel grey strip (NOT compiled)

### Did
- Read the dock/show sequence. Facts: the slide moves X only (height is fixed); `applyRect` sets AWT bounds and Compose's `windowState.size` in dp; the minimum-size rule of the normal window is already skipped in panel mode (`applyMinimumSize` and `adapt` both return early). What the panel did NOT get: the normal window's `refresh()` (invalidate, validate, repaint, `DesktopWindowsFrame.refreshFrame()`), because `adapt-panel` skips it. So a Compose canvas that lags the native window (or a window that Windows resized) left the unpainted area, which shows as flat grey over a material or the window ground. The cause is not proven, only the missing step is.
- New `settleSize(w, expected)` in `DesktopEdgePanel`: compares the real window size with the layout's (`EdgeSizeCheck.drifted`, 1 px tolerance), asserts the bounds again if they differ (logs `edge: size drift expected=WxH actual=WxH; asserting again`), then always invalidate, validate, repaint and `refreshFrame()`. Called after the warm-up (window parked, before the first reveal) and when a slide finishes (open or hidden).

### Files
New: `DesktopEdgeSizeCheck.kt`, test `DesktopEdgeSizeCheckTest.kt`. Hooked (`// UI U5 hook`): `DesktopEdgePanel.kt` (2 calls plus the function).

### Decisions
- Did not hide the window until the first frame (AWT opacity on a transparent window is risky) and did not paint a solid root in panel mode (it only paints inside the Compose canvas, so it cannot cover the strip).
- Bounds at rest equal Compose's layout height when `drifted` is false; when it is true the log line says so.

### Verified
NOT compiled, tests NOT run. Hand-checked: brace balance, `DisplayRect` fields, `l` in scope inside the warm-up lambda, `refreshFrame()` exists in `DesktopWindowsFrame`.

### Deferred
If the strip stays: send the `edge: size drift` lines (or their absence) from `desktop.log`. No drift line and a strip means the canvas lags, not the window; next try would be `windowState.size` re-assert at the same moment or a one-frame delay before the reveal.

### Exact next step
U6 (art transition starts from the clicked artwork). Gate A first if not run.

## UI U6: art transition starts from the clicked artwork (NOT compiled)

### Did
- New registry `DesktopArtSources` + pure rule `ArtSourceRules.decide(videoId, press, zones)`. Three zone kinds: SOURCE (artwork or row of one song, with bounds), AREA (a page of shared cards, no bounds), BLOCK (the pill).
- `maybeStartArtTransition` now asks the rule: no press (media key, auto-advance) = no effect; press inside a BLOCK (pill: Next, Previous, ...) = no effect; press inside the smallest SOURCE of that song = effect with `sourceBounds` = that rect; inside another song's SOURCE = that rect; inside an AREA only = effect with `sourceBounds = null` (pointer fallback, as before). The "full player open" skip and the Off / Every song click / Only when the song changes setting are untouched.
- Hooks: `.artSource(videoId)` on `DesktopCollectionSongRow` (playlist/album rows); `.artSourceArea()` on the main page Box; `.artNoFire()` on both pills (classic dock in `DesktopApp.kt`, `DesktopLiquidPill.kt`).
- Fix to U1: `DesktopLiquidPill.kt` had its own hover pencil (`onOpenPillStudio`); U1 had only removed the classic dock's. Removed here too. The parameter stays (unused) so call sites compile.

### Files
New: `DesktopArtSources.kt`, test `DesktopArtSourcesTest.kt`. Hooked (`// UI U6 hook`): `DesktopArtTransitionWiring.kt`, `DesktopApp.kt`, `DesktopLiquidPill.kt`.

### Decisions
- The Home "Recents" thumbnails and big cards, Song mixes and Listen again cards are drawn by `sharedUi` (`HomeScreen.kt`, Coil `AsyncImage`), which this round may not touch, so they cannot register their own bounds. They fire through the AREA fallback: the Next-button and media-key problems are fixed, but for those cards `sourceBounds` is still null. The plan's "Done when" for the Recents card is therefore NOT met yet; it is met for desktop-owned rows.

### Verified
NOT compiled, tests NOT run. Hand-checked: brace/paren balance of all four files, `Rect` import, modifier order (artSource before the row's `.clickable`), no duplicate names.
Watch-list: `x in left..right` on Float; `private @Composable fun Modifier.artZone` used from three public wrappers.

### Deferred
- Bounds for shared cards. Needs one small allowed change in `sharedUi`: a CompositionLocal `LocalArtSourceModifier: (String) -> Modifier` read at the four `AsyncImage` sites in `HomeScreen.kt` (about lines 522, 843, 940, 1185) and applied to their artwork; desktop would provide `{ id -> Modifier.artSource(id) }`. Ask the owner to allow it, then it is a 15-line change.
- Search result rows, queue rows, Library lists (desktop-owned, not hooked yet).

### Exact next step
U7 (dots from the whole source rectangle) can use `sourceBounds` as soon as it is non-null; with null it keeps the pointer. Run Gate A before it.

### UI U6 follow-up (owner approved the sharedUi hook; NOT compiled)
- New `sharedUi/.../components/ArtSourceHook.kt`: `LocalArtSourceModifier` (default adds nothing) and `Modifier.artSourceFor(videoId)`.
- Applied at the four `HomeScreen.kt` artwork sites: Recents rows (`CompactTrackRow`, new optional `videoId` param, passed from its wrapper and from the artist page in `DesktopApp.kt`), Song mixes / Listen again row artwork, hero cards, square cards. Only `AsyncImage` modifiers and one parameter changed; no layout change.
- Desktop provides `DesktopArtSourceProvider` (in `DesktopArtSources.kt`) at the existing `CompositionLocalProvider` next to `LocalShelfRowChrome`. Home cards with a video id now register their artwork rect, so `sourceBounds` is non-null for a Recents thumbnail and a big card; items without a video id (albums, playlists) still fall back to the page AREA.
- Watch-list: `staticCompositionLocalOf<@Composable (String) -> Modifier> { { Modifier } }` (lambda default); `artSourceFor` is `@Composable` and is used only inside composable argument lists.
- The "Done when" of U6 is now met in code, still NOT compiled.

## UI U7: art transition: whole-area dots into the pill art (NOT compiled)

### Did
- Audit: the engine (`ArtParticleSim`, 48x48 mosaic sampled from the thumbnail, bezier flight to `artTransitionTarget()`, then reveal of the real pill art) already existed and already uses `sourceBounds` (U6). Missing pieces were added, not rewritten.
- Spawn: every particle now starts inside `sourceBounds` (`ArtTransitionPhysics.spawnCoord`, clamped); cells that a wide card crops still start invisible and fade in as they release (`srcHidden` now uses the unclamped offset).
- Budget: `keepFraction(area)` draws 40% of the cells for a tiny thumbnail up to 100% from 90,000 px squared; hard cap = `COUNT` (2304). Thinned cells are skipped in `step` (alpha 0, no per-frame work).
- Settings: the on/off switch already exists (Advanced > Experimental, `artTransitionMode` OFF; plus the Off / Every song click / Only when the song changes chips). Left unchanged.

### Files
Edited (`// UI U7 hook`): `DesktopArtTransition.kt`, `DesktopArtTransitionPhysics.kt`. Tests added to `DesktopArtTransitionMathTest.kt` (start inside rect, end at pill cells, budget).

### Decisions
- `reduceAnimation` stays "no particles": the pill shows the new artwork through its normal image swap. I did not verify that swap fades; a dedicated crossfade was not added.
- Pill-side cells are not thinned further than the source side, so the reformed art stays dense.

### Verified
NOT compiled, tests NOT run. Hand-checked: names unique (grep), `kept`/`keepFrac` declared before `init`, no new imports needed.

### Deferred
- Explicit crossfade for `reduceAnimation`.
- Run Gate B (all U1-U7) on CI; send the raw log to the PM.

### Exact next step
Push, run the Windows workflow (Gate B), send the error log with the F prompt.


## UI U8: Appearance settings, shorter and grouped (NOT compiled)

### Did
- Appearance top level now has six groups: Interface (Desktop interface, Convertible / touch mode), Background (Fluidity mode, Subtle effects, Background colours), Player (Full-screen cover art, Animated canvas, Spotify Canvas setup), Floating pill (row opens Pill Studio), Glass (row opens Glass Lab), Advanced appearance (row expands the rest in place).
- The rest (Player background, Home sections, Studio and customisation incl. Reset all customisation, Window and performance) sits under "Advanced appearance". It shows when that row is expanded and always while the settings search field has text, so search still finds every moved setting.
- Advanced > Diagnostics: the group is now collapsible (row "Memory report") and starts collapsed; search shows the rows. It holds only the memory-report rows today, so that is the only diagnostic group.

### Files
Only `DesktopApp.kt` (hooks marked `// UI U8 hook`). No new files.

### Decisions
- "Opens" for Advanced appearance is an in-place expand, not a new page: no new navigation state needed.
- Pill and Glass rows are shortcuts; the original rows (Pill Studio in Studio group, "Glass lab (experimental)" in Advanced > Advanced options) stay. Nothing deleted.
- Expand state is not saved across restarts (`remember`).

### Verified
NOT compiled, tests NOT run. Hand-checked: brace and paren counts unchanged vs. before the edit; no variable shared between the split groups; `LocalSettingsQuery` is in the same file. No pure logic extracted, so no new test.
Watch-list: `if (...) {` wrapping several `SettingsGroup` calls inside `settingsSectionItem`.

### Deferred
- Per-group collapse inside Advanced appearance; saving the expand state.
- Gate B (U1 to U8) not run yet; owner is not pushing for now.

### Exact next step
U9 (liquid glass look) needs the owner's reference screenshots; otherwise audit only.

## UI U9: liquid glass look (NOT compiled)

Reference used: the owner's three screenshots (iOS liquid glass: clear body, strong bent rim, bright specular rim, slight colour split, soft frost, text stays readable).

### Audit (why the look did not show)
- Engine is sound: window backdrop and page are recorded into layers and refracted by the shader (`glassSurface`). Shader failure falls back to the frosted pane, never a hole.
- Only the floating pill used it (default `pillMaterial = LIQUID_GLASS`, so it was on by default when the shader compiles). The top navigation and floating bars used the classic frosted pane only.
- The shader is an overlay that adds the bent rim and light; the pane under it (`glassPane`, Haze blur + tint) set the look. The old defaults (frost 0.35, tint 0.3, refraction 0.7, rim 0.5) gave a dark smoky pill, not clear glass.
- Whether the shader compiles on the owner's PC is unknown from code: Advanced > Glass Lab shows `DesktopGlassShader.error`. If it shows an error, send that text.

### Did
- New defaults (`DesktopGlassParams.Default`, applied by "reset appearance"): refraction 0.95, bevel 18 dp, magnify 0.06, dispersion 0.45, frost 0.12, blur 10 dp, specular 0.85, rim 0.75, tint 0.20, light 135 deg, album colour 0.35. Same as the Crystal preset except blur; the pill still keeps a text-contrast tint floor (`liquidTintAlpha`).
- New `DesktopGlassParams.Nav` (refraction 0.6, bevel 10, dispersion 0.3, frost 0.2, tint 0.25, rim 0.6) for small capsules.
- New `Modifier.glassOverlay(shape, params)` (adds nothing when the shader or glass state is missing). Hooked into `desktopBarGlass` (floating nav and mini pills; new `liquid` flag, off for the 6 dp window-button chip) and into the top-nav selector capsule.
- Shader numbers only: rim exponent 4 to 3, top sheen 0.05 to 0.08.

### Files
`DesktopGlassParams.kt`, `DesktopGlassShader.kt`, `DesktopGlassSurface.kt`, `DesktopApp.kt` (`// UI U9 hook`); test added to `DesktopGlassParamsTest.kt`.

### Decisions
- The full-width Modern top bar strip is not given a rim: a bent edge on a bar that meets the page would show as a seam. The glass is on its selector capsule instead.
- Existing saved pill configs keep their own numbers; only fresh or reset configs get the new defaults.

### Verified
NOT compiled, tests NOT run. Brace and paren counts of `DesktopApp.kt` unchanged. Watch-list: `glassSurface` is called from a modifier chain already used by the pill; a second overlay on the same screen means more layer recording (check CPU in Glass Lab).

### Deferred
- Tuning by eye against the references needs a running build; send a screenshot after CI.
- Gate B (U1 to U9) not run.

### Exact next step
Push, run the Windows workflow (Gate B), send the raw log with the F prompt.

## UI F1: compile fix after CI run 1 (NOT compiled)
- Error: `DesktopApp.kt:2296:9 Unresolved reference 'openLibraryItem'`. Cause: `runPlaylistAction` (U2) called the local function `openLibraryItem` before its declaration; Kotlin local functions cannot be used ahead of where they are declared.
- Fix: moved `runPlaylistAction` to directly after `openLibraryItem` (no code change). Same-file uses at the `::openLibraryItem` references are after both.
- Only this error was in the log (the rest were warnings). Later errors may appear once this one is out of the way: send the next raw log.
