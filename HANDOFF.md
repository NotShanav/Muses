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
