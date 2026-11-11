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
| Previous / play-pause / next | Floating mini-player |
| Seek progress | Floating mini-player progress line; full seek remains in full-screen player |
| Shuffle / repeat | Full-screen player controls |
| Volume | Full-screen player volume controls |
| Queue / lyrics | Mini-player More menu and full-screen player |
| Audio output | Mini-player More menu and full-screen player output control |
| Desktop title / drag / caption buttons | Existing title bar and top-strip drag area |

### Decisions and limitations
- `DesktopFloatingMiniPlayer` uses the Haze source already installed on the page, with `desktopBarGlass` for the one glass surface and the repository's solid fallback when dynamic blur is reduced.
- The top pill animation is transform-only. Card hover-lift changes were not added to the shared HomeScreen because it is shared by Android and desktop while `MusesMotion` is desktop-only; the existing card interaction and shelf layout remain intact.
- The profile dropdown can become tall when an account has many playlist entries; the Library page remains the primary route to the full playlist shelf.
- No player engine, account, library, lyrics-fetching, or background-mode code was altered.

### Verified
- **Gradle was attempted but did not reach compilation.** Both `./gradlew :desktopApp:compileKotlin :sharedUi:compileKotlinJvm` and the Windows wrapper with `--offline` stalled before emitting Gradle output. `java -version` also stalled; the available Java path resolves to the Windows Java 8 path shim. No successful compile or tests are claimed.
- **Manual source review:** compared the edited `DesktopApp.kt` against the input archive version; checked imports and symbol definitions for `DesktopFloatingMiniPlayer`, `DesktopToolbarButton`, the existing desktop destination enum, `DesktopPlaylist.title`, `DesktopSearchField`, `DesktopFrame`, the visit restoration logic, Compose `key`, and existing animation/glass APIs. The shared HomeScreen and Android source files were not edited, so there are no new expect/actual changes.
- Not verified: Linux desktop compile, Android compile, UI behavior at narrow widths, and automated tests.

### Next account
1. Compile Windows desktop and JVM shared UI in GitHub Actions and fix any issues reported there.
2. If implementing the player expansion transition, use `onExpandPlayer` and `artworkModifier` above; keep the current full-screen layout unchanged unless shared-element continuity requires a carefully reviewed adjustment.
3. Preserve this mapping table and keep all formerly reachable controls accessible.


### Account 2 build-log follow-up
The GitHub Actions log supplied after packaging identified four source issues: `positionMs` and `durationMs` had been declared on `DesktopTopBar` instead of `DesktopFloatingMiniPlayer`; a local playlist menu item used nonexistent `name` instead of `title`; and the top-strip divider used `Modifier.align` outside a `BoxScope`. These were corrected by moving the timing parameters to the mini-player API, using `DesktopPlaylist.title`, and placing the row and divider inside a `Box`. The corrected project is repackaged. It has not yet been rerun through GitHub Actions, so successful compilation is still unverified.

The next supplied workflow run confirmed the playlist name and mini-player timing-parameter corrections and reported two additional top-bar structure errors. The bottom divider now lives inside the surrounding `Box` scope, and the extra closing brace has been removed. The archive has been refreshed again; another Actions run is needed to confirm the fix.
