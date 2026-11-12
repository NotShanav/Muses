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


## Hub — H1 (2026-11-13)
### Did
- Added the pure data models and the service registry with JSON persistence, plus unit tests. No UI, no launching, no process code.
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubModels.kt` (`HubService`, `HubServiceState`, `HubBrowserPreference`, `HubSettings`, `HubLimits`).
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubRegistry.kt` (`HubRegistry`, `HubChange`/`HubError`, `HubUrls`, `HubRegistryCodec`, `HubKeyValueStore`, `DesktopHubKeyValueStore`, `HubRegistryStore`).
- Added `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHubRegistryTest.kt`.
- Changed: this file (HANDOFF.md) only. Nothing else was edited; the earlier "Hub H1 - blocked" note in an older copy of this file is superseded by this entry.
### Decisions (and why)
- Persistence follows the repo pattern: `DesktopPersistence().string/saveString` (chunked prefs) with kotlinx.serialization `Json { ignoreUnknownKeys; encodeDefaults }` as in `DesktopAdvancedAppearance`. One key, `hub_registry_v1`, holds `{version, services, settings}`. A payload with `version` above 1 is ignored (defaults), same rule as the appearance config.
- Persistence sits behind `HubKeyValueStore` so tests use a fake and never touch OS preferences. Later accounts should use `HubRegistryStore()` (default arg = real store).
- `HubRegistry` is an immutable data class; every edit returns `HubChange(registry, error?)` and leaves the registry unchanged on error. Errors: INVALID_URL, INVALID_NAME, LIMIT_REACHED (max 24 services), NOT_FOUND. Names are trimmed, 1-40 chars. Ids are slugs of the name (`plex`, `plex-2`) and never change on rename.
- URL rule: http/https only, host required, no whitespace, no `user:pass@`, max 2048 chars. A bare host (`youtube.com`) gets `https://`. Anything with a scheme-like prefix other than http/https (`javascript:`, `file:`, `ftp:`) is refused; as a side effect `host:port` without a scheme is also refused (type the full URL).
- Settings are clamped by `HubSettings.validated()`: warn 256..16384 MB, cap never below warn, max live 1..4. Defaults 1600 / 2048 / 1, browser AUTO (Brave, Chrome, Edge).
- Corrupt or unreadable JSON loads the defaults; the bad value is not deleted until the next `save`. Valid JSON with bad entries keeps the good entries (bad URL/name dropped, duplicate or blank ids re-issued). An empty list that was saved on purpose stays empty; `resetServices()` restores the five defaults.
- `keepAlive` means "may stay live in the background", still subject to the memory budget (HUB_PLAN section 3). `HubServiceState` is runtime-only and never persisted.
### Verified
- NOT compiled and tests NOT run (no Gradle or Kotlin compiler available here). Manual compile review only: imports match the repo's (`kotlinx.serialization.decodeFromString/encodeToString/json.Json`), no Windows or Compose imports (compiles on Linux), no duplicate declarations (grepped `Hub*` names across the module), `internal` types do not leak private ones, `HubRegistry` default argument uses its companion function.
- Walked each test through the code by hand (URL cases, reorder, id suffixing, sanitize, settings clamp).
### Known problems / unverified assumptions
- `coerceInputValues = true` is relied on so an unknown `browserPreference` falls back to AUTO instead of discarding the whole payload; unverified at runtime with Json 1.7.3.
- Java `URI` rejects hosts with underscores, so such URLs are refused.
- `host:port` URLs without a scheme are refused (see URL rule).
### Exact next step for the next account
- H2: create `DesktopHubBrowser.kt` and `DesktopHubBrowserTest.kt` per HUB_ACCOUNTS.md. Use `HubBrowserPreference` from `DesktopHubModels.kt`; do not edit H1 files.


## Hub — H2 (2026-11-13)
### Did
- Added browser detection (Brave, Chrome, Edge) and the pure launch-command builder, plus tests. Nothing is launched or created on disk by this code.
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubBrowser.kt` (`HubBrowser`, `HubBrowserEnv`, `HubBrowserFinder`, `HubBrowserLaunch`).
- Added `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHubBrowserTest.kt`.
- Changed: this file (HANDOFF.md) only. `DesktopBrowserSignIn.kt` and the H1 files were only read, not edited.
### Decisions (and why)
- Detection is re-implemented (ideas from `DesktopBrowserSignIn.preferred()`: LOCALAPPDATA with `user.home/AppData/Local` fallback, `ProgramFiles`, `ProgramFiles(x86)`). Testable through `HubBrowserEnv` (injected folders) and an `exists: (Path) -> Boolean` lambda. `findInstalled(preference)` is the real call: Windows only, null elsewhere, uses `Files.isRegularFile`.
- Order: AUTO = Brave, Chrome, Edge. A specific `HubBrowserPreference` is tried first; if that browser is not installed it falls back to the AUTO order so the Hub still works (UI should show the browser actually returned). Per browser, folders are tried per-user, then Program Files, then Program Files (x86). Vivaldi from the sign-in code is deliberately not included (HUB_PLAN names Brave/Chrome/Edge).
- `HubBrowserLaunch.buildLaunchArgs(browser, profileDir, url)` returns the FULL command line, executable first, ready for `ProcessBuilder(list)` (H3: use it as is). Flags, in order: `--app=<url>`, `--user-data-dir=<absolute profile>`, `--no-first-run`, `--no-default-browser-check`, `--disable-background-mode`. `--disable-background-mode` is included so the browser exits when its last window closes and closing a service really frees memory (same flag the sign-in code uses). No `--disable-features` flag: nothing justified it. The URL goes through `HubUrls.normalize` (H1) and the function throws `IllegalArgumentException` for non-http/https input; registry URLs are already valid.
- Profile folder (one for all services, so logins and extensions persist): `%LOCALAPPDATA%\Muses\Hub\Profile`, via `HubBrowserLaunch.profileDir(env)`; sibling of the existing `Muses\Browser Sign In`. It is not created here; H3 should `Files.createDirectories` before launch.
### Verified
- NOT compiled and tests NOT run (no Gradle or Kotlin compiler here). Manual compile review only: imports present and used, no Windows-only or Compose imports (Linux compiles), no duplicate `Hub*` declarations (grepped), `Paths::get` usage copied from the repo, smart-cast on `preferred` checked. Tests walked through by hand (paths use forward slashes so they work on Linux and Windows).
### Known problems / unverified assumptions
- Install paths are the standard ones only; portable or unusual installs (and Brave Beta/Nightly) are not found. A custom path setting is not implemented.
- Whether `--disable-background-mode` is honoured by every Brave/Edge version is unverified; if the browser lingers after the last window closes, H3's tree tracking will show it.
- Whether Widevine, extensions and app-mode extension buttons work is untested (HUB_PLAN section 5).
### Exact next step for the next account
- H3: create `DesktopHubLauncher.kt` and `DesktopHubLauncherTest.kt` per HUB_ACCOUNTS.md. Build the command with `HubBrowserLaunch.buildLaunchArgs(...)`, pick the browser with `HubBrowserFinder.findInstalled(settings.browserPreference)`, create the profile dir first, and handle the "launched process hands off to an already-running browser on the same profile and exits" case.


## Hub — H3 (2026-11-13)
### Did
- Added the launcher: starts a Hub browser window, finds the browser's process tree, closes it gracefully then forcefully, plus tests that use fake processes only.
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubLauncher.kt` (`HubLauncher`, `HubProcessTree`, `HubProcessApi`/`HubSystemProcesses`, `HubProcessStarter`/`HubSystemProcessStarter`, `HubWindowCloser`/`HubNoWindowCloser`, `HubLaunchResult`/`HubLaunchStatus`, `HubCloseResult`/`HubCloseStatus`).
- Added `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHubLauncherTest.kt` (20 tests).
- Changed: this file (HANDOFF.md) only. H1/H2 files and `DesktopApp.kt` were not touched.
### Decisions (and why)
- API (all `HubLauncher`): `suspend launch(service): HubLaunchResult`, `suspend close(service): HubCloseResult`, `suspend closeAll()`, `treePids(service): List<Long>`, `profileTreePids(): List<Long>`, `runningServiceIds(): Set<String>`, `canCloseSingleWindow: Boolean`. Real instance: `HubLauncher.forSystem { settings.browserPreference }`. Launch/close are serialised by a mutex and run on `Dispatchers.IO`. `treePids`/`profileTreePids`/`runningServiceIds` take a full process snapshot (`ProcessHandle.allProcesses()`), so call them off the UI thread; H5's ~3 s poll is fine.
- HOW THE HAND-OFF CASE IS SOLVED: with one shared profile, a second `--app=` launch is passed to the already-running browser and the started process exits within moments. So the tree is NOT "descendants of the PID we started". It is every process whose command line has this profile's `--user-data-dir=<profile>` flag (exact argument match: quotes ignored, case and slash-insensitive, `Profile2` does not match `Profile`), plus all their descendants, plus the roots we started when they are still the same process (PID + start time, so a reused PID is not trusted). Muses itself and its ancestors are always excluded. Launch results: STARTED (fresh browser), HANDED_OFF (browser already running, our process exited with 0, polled up to 8 s), ALREADY_RUNNING, NO_BROWSER, FAILED. A browser closed by hand is noticed on the next call (empty tree clears all sessions).
- Closing: if the service is the only one open, the WHOLE Hub tree is closed. Step 1 (graceful): `requestClose` on the tree's top-level processes only. Windows: `taskkill /PID <pid>` without /F (Java's `destroy()` is a hard kill on Windows); elsewhere SIGTERM. Wait up to 5 s. Step 2: `forceKill` on the PIDs of a freshly computed tree, then wait up to 5 s again (CLOSED_FORCED, or FAILED if something survives). Only PIDs from the computed tree are ever signalled.
- ONE shared tree, no per-service split. `treePids(service)` returns the SHARED tree when that service is open, else empty. **H4/H5: take web-side memory from `profileTreePids()` once. Do not sum `treePids` over services; that counts the same processes several times.**
### Verified
- NOT compiled and tests NOT run (no Gradle or Kotlin compiler here). Manual compile review only: imports present and used, no Windows-only or Compose imports (compiles on Linux; Windows behaviour is behind `DesktopPlatform.isWindows`), no duplicate `Hub*` declarations (grepped), `Optional` handled with a private `orNull()` helper, bracket balance checked, non-local returns only inside inline lambdas, no suspension inside synchronized blocks. Walked every test through the code by hand.
### Known problems / unverified assumptions
- NOT SOLVED: closing ONE service while another is open. All live services share one browser process, so a window can only be closed with a window-level closer (find the window, post WM_CLOSE). `HubWindowCloser` is the seam; the only implementation is `HubNoWindowCloser`, so `close(service)` returns SHARED_BROWSER_STILL_RUNNING and leaves the window open. Until a Windows closer exists, **H5 must treat max live services as 1** (check `canCloseSingleWindow`; to switch services, `close` the active one first, then `launch`). Hibernating while keepAlive/maxLive>1 services are open is not possible yet.
- Memory cannot be attributed per service for the same reason (UI can show one browser total).
- A browser left running by an earlier Muses run is picked up (profile flag) and closed by `closeAll()` or a sole-service `close`; a service opened before a Muses restart is not in `sessions`, so Muses reports it as not running until relaunched (its window is handed off to, not duplicated).
- Unverified on a real machine: whether Windows exposes `commandLine()` for Brave/Chrome/Edge children (the started-root fallback covers the main process only), whether `taskkill` without /F closes app-mode windows promptly, and that `--disable-background-mode` lets the browser exit with its last window. If the command line is unreadable the hand-off case still reports HANDED_OFF only when the profile tree is visible; otherwise it ends as FAILED.
- Launch success means the browser process is running; there is no check that a window is visible.
### Exact next step for the next account
- H4: create `DesktopHubMemory.kt` and `DesktopHubMemoryTest.kt` per HUB_ACCOUNTS.md. `MemoryReader.workingSetMb(pids)` will be fed `launcher.profileTreePids()` (one call, not per service). The governor's "least-recently-used non-active service" rule needs per-service sizes that do not exist; decide and record how the Hibernate action works given the shared tree (it can only target a service when `canCloseSingleWindow` is true).

## Hub — H4 (2026-11-13)
### Did
- Added the memory reader, a sampler (Muses + browser tree) and the memory governor policy, plus 29 unit tests. Nothing here starts, closes or kills anything; the governor only returns advice.
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubMemory.kt` (`MemoryReader`, `UnsupportedMemoryReader`, `WindowsMemoryReader`, `MemoryReaders.forSystem()`, `HubMemoryMath`, `HubMemorySample`, `HubMemorySampler`, `HubGovernorService`, `MemoryAction`, `MemoryLevel`, `MemoryGovernor`).
- Added `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHubMemoryTest.kt`.
- Changed: this file (HANDOFF.md) only. H1/H2/H3 files, `DesktopApp.kt` and `DesktopBrowserSignIn.kt` were not touched.
### Decisions (and why)
- NAMES follow HUB_ACCOUNTS/HUB_PLAN exactly (`MemoryReader`, `MemoryGovernor`, actions `Warn` / `Hibernate(id)` / `None`); helper types use the `Hub` prefix. No other declarations with these names exist in the repo (grepped).
- JNA GAP: jna-platform 5.19.1 has NO `GetProcessMemoryInfo` or `PROCESS_MEMORY_COUNTERS` (checked in its Psapi/Kernel32 sources). `WindowsMemoryReader` declares its own 1-method binding (`Native.load("psapi", ..., W32APIOptions.DEFAULT_OPTIONS)`, same style as JNA's own Psapi) and reads the struct from a raw `Memory` block: offsets `8 + POINTER_SIZE` (WorkingSetSize), size `8 + 8*POINTER_SIZE` (72 bytes on 64-bit). `Kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)` / `CloseHandle` are the platform ones (confirmed in 5.19.1). Everything Windows-only is inside `WindowsMemoryReader` behind `DesktopPlatform.isWindows`; Linux gets `UnsupportedMemoryReader` (returns 0, `supported = false`). The struct offsets are computed from getters, so the class does not touch JNA's native loader on Linux.
- UNITS: MB means MiB (1,048,576 bytes); bytes are summed first, then divided once. Duplicate PIDs are counted once. Unreadable PIDs count as 0 (`lastUnreadablePids` shows how many).
- TOTAL = Muses + web. Muses figure = working set of Muses' own PID via the same reader; if that reads 0 it falls back to the JVM used heap (`Runtime.totalMemory - freeMemory`), which under-counts. Web figure = ONE `reader.workingSetMb(launcher.profileTreePids())` call (H3's rule); Muses' own PID is removed from that list if present so nothing is counted twice. `HubMemorySample.measured` is false when the reader is unsupported (UI should say "not measured").
- GOVERNOR (HUB_PLAN section 3): `Warn` when total > warn (1600); when total > cap (2048) also `Hibernate(id)` of the least-recently-used LIVE service. Plain LRU by `lastActiveMillis` (ties: earlier list entry); `keepAlive` does NOT protect from the cap (the plan says it stays subject to the budget). ACTIVE and HIBERNATED services are never chosen. Nothing to close = warning only, never anything destructive to the active service. Thresholds are strict (`>`).
- HIBERNATE WITH THE SHARED TREE (the question H3 left open): per-service memory does not exist, and one window can only be closed when `launcher.canCloseSingleWindow` is true. So `decide(...)` takes `canCloseSingleWindow` (default FALSE = never emits Hibernate). With max live = 1 there is no non-active LIVE service anyway, so today the governor only warns. If total is over the cap and the only open service is the active one, the user has to close it themselves.
- HYSTERESIS: `Warn` is edge-triggered, sent once each time the level rises (NORMAL -> WARNING -> OVER_CAP); staying in a level sends `None`. A level is left only when the total is `margin` below its threshold (5% of it, min 32 MB: 80 MB at 1600, 102 MB at 2048). After a `Hibernate` there is a 10 s settle period (`MemoryGovernor.DEFAULT_SETTLE_MILLIS`) with no further Hibernate, because the browser frees memory late; at most one Hibernate per `decide`. A clock that moves backwards does not block forever. The governor is stateful (`level`, last hibernate time): `reset()` it when polling stops. Time and the single-window flag are parameters, so tests are deterministic.
- `decide` signature: `decide(totalMb, services: List<HubGovernorService>, settings, nowMillis = now, canCloseSingleWindow = false): List<MemoryAction>`; never empty (`[None]` when idle); settings go through `validated()`.
### Verified
- NOT compiled with Gradle and the Gradle test task was NOT run. Manual compile review done (imports used/present, nullability of `OpenProcess` result, `use` on `Memory` which is `Closeable`, no duplicate declarations, `internal` types do not leak private ones, no Windows-only code runs on Linux).
- EXTRA CHECK (stronger than usual, still not the real build): `DesktopHubMemory.kt` plus the real `DesktopPlatform.kt` and `DesktopHubModels.kt` compiled with a real kotlinc 2.3.20 against hand-written STUBS of the JNA classes and `kotlin.test`, with no errors or warnings, and all 29 tests in `DesktopHubMemoryTest` passed (Linux, via a small reflection runner). This proves the logic and Kotlin syntax, NOT the JNA calls: the stubs copy the 5.19.1 signatures I read, nothing real was called.
### Known problems / unverified assumptions
- UNVERIFIED on Windows: the `GetProcessMemoryInfo` binding and struct offsets. `DesktopHubMemoryTest.windowsReaderCanReadItsOwnProcess` runs only on Windows and will show it on the first Windows run; if it fails, look at `WindowsMemoryReader` first (psapi load, `OpenProcess` access right).
- Working set includes pages shared between Chromium processes, so the summed web figure can read higher than Task Manager's "Memory" column. This errs toward warning/closing early. Private working set is not used (it needs a different call).
- Processes that deny `PROCESS_QUERY_LIMITED_INFORMATION` (elevated or protected) read as 0, so the total can be under-counted; `lastUnreadablePids` exposes it.
- 32-bit JVM offsets are reasoned from the struct layout, not tested.
- Muses' own number is a working set (includes shared pages); if the PID read fails it is JVM heap only and under-counts.
- The governor cannot attribute memory to a service; it only compares the shared total with the limits.
### Exact next step for the next account
- H5: create `DesktopHubController.kt` and `DesktopHubControllerTest.kt`. Every ~3 s while a service is live, OFF the UI thread: `val sample = sampler.sample(launcher.profileTreePids())`, then `governor.decide(sample.totalMb, services, settings, System.currentTimeMillis(), launcher.canCloseSingleWindow)`. Build `HubGovernorService(id, state, lastActiveMillis)` from the controller's own state (record `lastActiveMillis` whenever a service becomes or stops being ACTIVE). Apply `Hibernate(id)` via the controller's hibernate; expose `sample`, `governor.level` and a "warned" flag on the StateFlow (UI shows a persistent warning from `level`, not from the one-shot `Warn`). Call `governor.reset()` when no service is live. Use `MemoryReaders.forSystem()` for the real reader; show "memory not measured" when `sample.measured` is false. Keep max live services at 1 until `canCloseSingleWindow` is true.

## Hub — H5 (2026-11-13)
### Did
- Added the Hub controller: `activate` / `hibernate` / `closeAll`, a ~3 s memory poll that runs only while a service is open, registry editing with saving, and one `StateFlow` for the UI. Plus 36 tests with fakes (no browser, no real memory reads, no real saved settings).
- Fixed a real compile error found in H3's file (see Decisions, "OUTSIDE MY FILES").
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubController.kt` (`HubController`, `HubLauncherPort`, `HubLauncherAdapter`, `HubControllerState`, `HubServiceView`, `HubEditResult`, `HubMessages`).
- Added `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHubControllerTest.kt` (36 tests).
- Changed `DesktopHubLauncher.kt` (H3, NOT my file): 3 lines, `x in sessions` -> `sessions.containsKey(x)`. Nothing else in it changed.
- Changed: this file (HANDOFF.md).
### Decisions (and why)
- OUTSIDE MY FILES: `sessions` in `HubLauncher` is a `ConcurrentHashMap<String, Long>`. In Kotlin, `id in sessions` on a ConcurrentHashMap calls Java's legacy `contains(value)` (= `containsValue`) and kotlinc 2.3.20 rejects it as an ERROR (KT-18053) at 3 places (`treePids`, `launchLocked`, `closeLocked`). It would have failed the first CI build. Fixed with `containsKey`, the intended meaning. The ownership rule was bent on purpose: accounts run one after another, so there is no edit conflict, and leaving a known build breaker was worse. Revert those 3 lines only if you disagree. I checked my own code for the same pattern: none (`LinkedHashMap`/`HashMap`/`Set` are fine).
- SEAM: the controller depends on `HubLauncherPort` (launch, close, closeAll, `profileTreePids`, `runningServiceIds`, `canCloseSingleWindow`, `browserName`), because `HubLauncher` is a final class. `HubLauncherAdapter.forSystem { preference }` wraps the real launcher. The browser preference is read lazily from the controller's current settings, so a settings change applies to the next launch.
- REAL INSTANCE: `HubController.forSystem(onServiceActivated = {})` (real launcher, `MemoryReaders.forSystem()`, `HubRegistryStore()`). H6 passes the music-pause lambda there. The constructor takes everything else injectable (`scope`, `pollIntervalMs`, `autoPoll`, `now`, governor, store).
- STATE: `controller.state: StateFlow<HubControllerState>` = `services: List<HubServiceView(service, state)>` (HIBERNATED / LIVE / ACTIVE), `settings`, `sample` (`HubMemorySample?`: `musesMb`, `webMb`, `totalMb`, `measured`; null when nothing is open), `memoryLevel`, `browserName`, `message` (last note or error, cleared by `clearMessage()`), `canCloseSingleWindow`; helpers `activeId`, `openCount`. Persistent memory warning in the UI = `memoryLevel`, not the one-shot message.
- ACTIVATE: if already ACTIVE -> nothing, message "already open. Switch to its window" (Muses cannot focus a browser window). If LIVE -> becomes ACTIVE, no launch (hook is called). Otherwise: effective max open = `settings.maxLiveServices` only when `canCloseSingleWindow`, else 1. While open >= max, the least recently used OPEN service (including the previous active one) is closed first, then the new one is launched. Closing the victim failing aborts the launch and keeps state. A failed LAUNCH after the previous service was closed leaves nothing open (message explains; no rollback). The hook `onServiceActivated` runs after a successful activate or LIVE->ACTIVE switch, never on failure; exceptions in it are swallowed.
- HIBERNATE (user): closes that service; with others open and no single-window closer the launcher refuses (`SHARED_BROWSER_STILL_RUNNING`) and the message says "Use Close all". The user may close the active one; only the governor never does.
- POLL: one loop, started on the first successful launch, ends itself when nothing is open. Each tick (under the same mutex as every state change): drop services the browser no longer shows (closed by hand), sample once (`sampler.sample(launcher.profileTreePids())`, on `Dispatchers.IO`), run `governor.decide(..., canCloseSingleWindow)`, apply `Warn` (message) and `Hibernate(id)` (only if that service is LIVE). A failing sample sets a message and keeps polling. `pollOnce()` is public for tests/UI.
- REGISTRY: `editRegistry { reg -> reg.add(...) }` returns `HubEditResult(ok, error)`; `updateSettings(settings)` is a shortcut. Open services that are removed or disabled are closed first (a failed close aborts the whole edit). Saved through `HubRegistryStore`; a save failure only sets a message.
- EXIT: `shutdown(closeBrowsers = true)` stops the poll, closes the Hub browser (only if something was ever launched or is open), cancels the scope, ignores later calls; safe to call twice. `cancel()` is the non-suspending fallback (stops everything, does not close the browser). H6 should call `shutdown` from the app's exit path.
### Verified
- NOT compiled with Gradle and the Gradle test task was NOT run. Manual compile review done.
- EXTRA CHECK (real kotlinc 2.3.20, as in H4, but now for all Hub files): H1-H5 main sources (real `DesktopPlatform`, `DesktopHubModels/Registry/Browser/Launcher/Memory/Controller`) compiled against hand-written STUBS of kotlinx.coroutines, kotlinx.serialization, JNA and `kotlin.test`. That is how the H3 error above was found. All 36 H5 tests and the 29 H4 tests pass; H1-H3 tests pass too (102 of 105 ran green). The 3 failing ones are the registry JSON round-trip / corrupt-JSON tests, which fail ONLY because the stub replaces the real serializer; they are expected to pass in the real build. The coroutine stubs run suspend code synchronously, so real dispatcher/mutex timing is NOT tested (the one poll-loop test uses real threads and a short interval).
### Known problems / unverified assumptions
- All H3/H4 caveats still stand (single-window close missing, so max open = 1; process command lines on Windows unverified; JNA memory call unverified).
- Muses cannot bring a service's window to the front; clicking an open service only marks it active and shows a message.
- If the browser is closed by hand, Muses notices at the next poll (<= 3 s).
- `refreshBrowser()` is not called automatically: H6 should call it when the page opens (it fills `browserName` for the "Browser: Brave" line; it is also set after each successful launch).
### Exact next step for the next account
- H6: create `DesktopHubPage.kt` and the one `// MUSES-HUB hook` edit in `DesktopApp.kt` (never open that file whole; `grep -n "DesktopDestination"`). Create ONE `HubController.forSystem(onServiceActivated = ...)` held by the app (e.g. `remember`/top-level in the hook region), collect `controller.state`, call `refreshBrowser()` when the page opens, tiles -> `activate(id)`, running list -> `hibernate(id)` / `closeAll()`, readout from `state.sample` (show "not measured" when `sample.measured` is false), show `state.message`, and call `shutdown()` on app exit. All controller calls are `suspend`: launch them from a coroutine scope, not the UI thread directly. Wire `onServiceActivated` to the existing play/pause function used by the media keys, or leave it a no-op and record why.

## Hub — H6 (2026-11-13)
### Did
- Added the Hub page and the single hook in `DesktopApp.kt`: a plain Media Hub page (service tiles, running list with Close / Close all, memory readout, browser line, message row, beta limits), one `HubController` held by the app, an entry point in each layout, music pause on activation, and Hub shutdown on exit.
### Files added/changed
- Added `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHubPage.kt` (`DesktopHubPage(controller, contentPadding)`; everything else in it is private and `Hub`-prefixed).
- Changed `DesktopApp.kt` only at six places marked `// MUSES-HUB hook (n/6)` (+28 lines, no deletions, CRLF kept): (1) `HUB("hub", "Media Hub")` in `DesktopDestination`; (2) controller creation + `DisposableEffect` right after `pauseFromUser()`; (3) `hubController.shutdown()` in the tray's `onQuit`; (4) one `destination == DesktopDestination.HUB -> DesktopHubPage(hubController, contentPadding)` branch after LOCAL_MUSIC; (5) "Media Hub" item in the modern top bar's profile menu; (6) "Media Hub" item in the classic sidebar.
- Changed: this file (HANDOFF.md). No H1-H5 file was touched.
### Decisions (and why)
- ENTRY POINTS: the profile menu exists only in the modern layout and the sidebar only in the classic one, so there is one item in each (both call the existing `onDestinationSelected`, so no new parameters). The classic sidebar item reuses `BitChordIcons.Play` (no better icon exists; no new design work).
- MUSIC PAUSE: wired. `onServiceActivated` pauses only when `playbackEngine.state.value.isPlaying`, through the existing `pauseFromUser()` (the one MPRIS/media keys use; it respects Listen Together locking and tells party sync). It is read through `rememberUpdatedState` because the controller is created once with `remember`. The controller runs the hook in the caller's coroutine; the page launches on the UI scope, so it runs on the UI thread, like the media keys.
- EXIT: window close reaches `onDispose` of a `DisposableEffect(hubController)`, which starts a non-daemon thread running `runBlocking { shutdown() }` (the UI is not held up by the browser's graceful-close wait, and the JVM stays alive until it finishes). The tray's Quit does `exitProcess`, so there `onQuit` runs `runBlocking { shutdown() }` before it. `shutdown()` is safe to call twice and does nothing if nothing was ever launched. Closing Muses therefore closes the Hub browser (HUB_PLAN: Muses owns the profile; if you would rather keep the browser open after Muses exits, pass `closeBrowsers = false` in both places).
- ACTIONS survive leaving the page: every click runs `withContext(NonCancellable)` on the page's scope, so navigating away mid-launch cannot cancel a launch or close half way. A `busy` flag ignores clicks while one action runs (no queued double launches).
- PAGE: reads `controller.state` only; `refreshBrowser()` on open. Memory shows "nothing open" (sample null), "not measured on this system" (`measured == false`), else total / cap MB with Muses and services split, a bar, and a coloured warning from `memoryLevel` (persistent, not the one-shot message). Disabled services are not shown as tiles. A tile of an open service is highlighted and clicking it just shows the controller's "already open" note. The limits block is generated from state (the "only one service" line shows while `canCloseSingleWindow` is false).
- The page has no tests: it is layout over controller state and the task asked for none. `DesktopApp.kt` was only read with `grep -n` and `sed -n` ranges, never whole.
### Verified
- NOT compiled with Gradle, Gradle tests NOT run, and no Kotlin compiler was available in this account (no kotlinc, no network), so no stub compile either. Manual compile review only: every import in `DesktopHubPage.kt` copied from imports already used in `DesktopApp.kt` (checked one by one; none unused, none missing), brace/paren balance counted, no duplicate `Hub*` declarations (a first helper named `HubLimits` clashed with H1's object and was renamed `HubBetaLimits`), `PAGE_GUTTER` is a `Dp`, `weight` is only used inside `Row` scopes, smart casts avoided (`if/else if` on `sample`), nothing private from `DesktopApp.kt` is used (the raised-surface colour is private there, so the page uses `Color.White` at 6% alpha). Hook edits: diffed against the original (only the six marked places differ, line endings unchanged), `rememberUpdatedState`/`DisposableEffect` already imported, `pauseFromUser` and `playbackEngine` are declared above the new code, `hubController` is declared before the tray's `onQuit` and the content `when`, no exhaustive `when` over `DesktopDestination` exists (grepped), and `DesktopDestination` stays private (the page never sees it).
### Known problems / unverified assumptions
- All H3/H4/H5 caveats stand (one open service at a time; no per-service memory; Windows command-line and JNA memory reads unverified).
- Until `refreshBrowser()` finishes the browser line can briefly say "none found" (state has no "unknown" value).
- The compact bottom nav does not list the Hub; it is reached from the profile menu (modern) or the sidebar (classic). While the Hub is open the compact nav highlights Library (its existing fallback).
- `runBlocking` on the tray thread at Quit can take up to about 10 s if the browser ignores the graceful close; no timeout was added.
- The page is untested in the real UI: tile widths (3 per row) and scrolling need a look on a real window.
### Exact next step for the next account
- H7: compile-review all `DesktopHub*.kt` files together with the six hook places (`grep -n "MUSES-HUB hook" DesktopApp.kt`), fix any CI errors the owner pastes, write `HUB_TEST_CHECKLIST.md` (HUB_PLAN section 7 as a fill-in table) and add the README Hub section with the YouTube-terms note.

## Hub — H7 (2026-11-13)
### Did
- Integration review of all seven `DesktopHub*.kt` files plus the six `// MUSES-HUB hook` places in `DesktopApp.kt`, read together. No CI log was pasted, so there was nothing to fix first. No code needed changing.
- Wrote `HUB_TEST_CHECKLIST.md` (HUB_PLAN section 1 goals, per-service and memory tables from section 7, extension table, behaviour checks, and every unverified assumption from H3-H6 as a row). Added a Hub section to `README.md` with the YouTube-terms warning.
### Files added/changed
- Added `HUB_TEST_CHECKLIST.md`.
- Changed `README.md` (appended one section at the end, CRLF kept) and this file. No source file was changed.
### Decisions (and why)
- No source edits: nothing in the review was a compile error or a wrong cross-file call, and fixing style would break the "no refactor" rule. The one oddity found is listed under known problems instead.
- README: the Hub is described as a controller for the user's own browser, ad blocking is attributed to the browser, and the YouTube-terms note is a visible warning block, as HUB_PLAN section 5 requires before release. The existing README tail (a repeated desktop paragraph) was left alone.
- The checklist includes a row for the Windows-only memory test and for the tray-Quit wait, because those are the two places a hang or a wrong number is most likely.
### Verified
- NOT compiled with Gradle, Gradle tests NOT run, no Kotlin compiler available in this account (no kotlinc, no network). Manual compile review only, across files: (1) every call between files matches its declaration (controller to launcher port, sampler, governor, registry store and codec; page to controller state and actions; hook to `HubController.forSystem`, `shutdown`, `DesktopHubPage`); (2) no duplicate top-level names among Hub files and none clashing with any other declaration in the repo (grepped all class/object/interface/typealias names); (3) the KT-18053 trap (`x in ConcurrentHashMap`) is gone: remaining `in`/`!in` uses are on `LinkedHashMap`, `HashSet`, `Set`, `Map` of plain types; (4) sealed `MemoryAction` is matched exhaustively; (5) `open` as a property name is legal (soft keyword); (6) property initialisation order in `HubController` is safe (`_state` is built after the fields `snapshot()` reads); (7) `internal` types never leak `private` ones; (8) Linux still compiles (JNA and Windows calls only inside `WindowsMemoryReader` and behind `DesktopPlatform.isWindows`); (9) the build already has the serialization plugin, kotlinx-serialization-json 1.7.3, coroutines and jna-platform 5.19.1, and nothing new was added.
- CI (`check.yml`) runs `./gradlew :shared:jvmTest :desktopApp:test`, then `:desktopApp:createDistributable`; the Hub tests (about 120) run in the first step.
### Known problems / unverified assumptions
- Everything in H3-H6 "Known problems" still stands (one open service at a time, shared memory total, Windows command lines, taskkill graceful close, psapi binding, Widevine and extension buttons all unverified on a real machine). The checklist has a row for each.
- `HubUrls.normalize` treats `host:port` without a scheme (for example `localhost:8080`) as a URL with scheme `localhost` and refuses it; `http://localhost:8080` works. Only matters when a custom service is added (no UI for that exists yet).
- There is no UI to add, remove or reorder services or to change settings; the controller supports it (`editRegistry`, `updateSettings`) but the page only shows the default list.
- The 3 registry tests that failed in H5's stub run (JSON round trip, corrupt JSON) are expected to pass only in the real build.
### Exact next step for the next account
- Owner: build or let CI build, then fill in `HUB_TEST_CHECKLIST.md` on Windows. Paste any CI error log or failed rows into a fresh chat for H8 (CI fixing, extension manifest review, bugs from the test run). Phase 2 (embedded engine) must not start until the checklist shows what actually plays.
