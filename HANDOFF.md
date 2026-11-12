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
