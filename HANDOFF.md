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
