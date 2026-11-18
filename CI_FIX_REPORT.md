# CI Fix Report — Muses v1.5.8 beta

## Changes made

1. `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceModel.kt`
   - Replaced `out += Paths.get(base, *more)` with `out.add(Paths.get(base, *more))`.
   - `Path` is iterable, so Kotlin can resolve `+=` as a reassignment through `plus`, which fails for a `val` and produces the `List<Path>` vs `ArrayList<Path>` diagnostic. `add` explicitly mutates the `ArrayList`.

2. `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceSettings.kt`
   - Replaced both `dirs += path` expressions with `dirs.add(path)` for the same `Path`/`plus` overload issue.

3. `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVoiceCommands.kt`
   - Prevented the add-to-playlist matcher from consuming `put on <song>` as an add-to-playlist command. This phrase is now parsed as a play request, while forms such as `put this in the road trip playlist` remain add-to-playlist requests.

## Validation performed

- Compiled the voice-command parser with the locally available Kotlin compiler.
- Executed all 29 methods in `DesktopVoiceCommandsTest`: PASS.
- Specifically verified all eight `playSongWithoutArtist` phrases and the existing `put this in the road trip playlist` behavior.

## Validation limitation

A full Gradle/Windows CI run could not be performed in this environment because the Gradle 8.11.1 distribution was not cached and outbound access to `services.gradle.org` is unavailable. Run the repository's Windows GitHub Actions workflow after applying this patch to validate the complete desktop build, remaining tests, and MSI packaging.
