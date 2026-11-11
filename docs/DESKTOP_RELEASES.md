# Desktop releases and the update check

How a desktop build gets its version, how its files are named on the releases page, and how the
app finds out a newer one exists. Windows already follows all of this; every Linux variant
(AppImage, deb, rpm, and anything added later) has to follow the same protocol. If a Linux file
breaks one of the rules below, the update check silently stops working for Linux users.

## 1. One version, stamped from one place

- The version is a plain string such as `1.8` or `1.8-beta1`. No leading `v`.
- `desktopApp/build.gradle.kts` reads it from `-Pbitchord.version=...` and falls back to a default
  (`appVersion`). CI passes the tag with the `v` removed (`v1.8` becomes `1.8`).
- The app reads the same value at runtime from the `bitchord.version` system property. It is baked
  into every package by `jvmArgs("-Dbitchord.version=$appVersion")` in the `compose.desktop`
  block of `desktopApp/build.gradle.kts`, which covers exe, msi, deb, rpm and the AppImage (it
  wraps the same app image, so `appimage.sh` needs nothing). Do not remove that line, and do not
  add a separate launcher that skips it: the app would then report the hardcoded fallback and the
  update check would compare against the wrong version.
- A pre-release version carries a suffix after a dash: `1.8-beta1`. The update check treats
  `1.8-beta1` as older than `1.8`, and `1.8` as older than `1.9`.

## 2. The installer's own version is numeric

Installer formats reject `-beta1`, so `nativePackageVersion` in `desktopApp/build.gradle.kts`
builds a separate numeric one: `major.minor.desktopVersionCode`, for example `1.8.26`.

- Bump `desktopVersionCode` for **every** build you publish, betas included. Windows only
  upgrades in place when the numeric version goes up; the same number is treated as "already
  installed".
- deb and rpm take the same `nativePackageVersion`, so `apt`/`dnf` upgrade ordering works the same
  way. Do not hand-edit package versions anywhere else.
- Never change the Windows `upgradeUuid`. For Linux, never rename the package (`packageName =
  "Muses"`) or the upgrade path breaks.

## 3. File names on the releases page

The release workflow (`.github/workflows/release.yml`) renames build output to these exact
patterns, where `<v>` is the version from section 1:

| Platform | File |
|---|---|
| Windows | `Muses-<v>-windows-x64-setup.exe` (what the update check downloads) |
| Windows | `Muses-<v>-windows-x64.msi` |
| Windows | `Muses-<v>-windows-x64-portable.zip` |
| Linux | `Muses-<v>-linux-x86_64.AppImage` (preferred by the update check) |
| Linux | `Muses-<v>-linux-amd64.deb` (used if there is no AppImage) |
| Linux | `Muses-<v>-linux-x86_64.rpm` |

Rules:

- Upload files to the release, do not rely on the generated source archives.

## 4. Updates

Muses does not check for updates and never shows an update prompt. A new build is installed by
running the new installer over the old one.

## 5. Checklist for a Linux build

Before publishing, confirm each of these on the built files, not just in the source:

- [ ] Version stamped (`-Pbitchord.version=<v>`). Launch each built file and check Settings shows the
      same version.
- [ ] `desktopVersionCode` is higher than the last published build.
- [ ] The three files are named exactly as in section 3.
- [ ] They are attached to the same release as the Windows files.
- [ ] The release is not a draft or pre-release (for a real release).
- [ ] Install the previous release, then the new one over it: deb with `sudo apt install
      ./Muses-*.deb`, rpm with `sudo dnf upgrade ./Muses-*.rpm`. It must upgrade, not
      reinstall or conflict.
- [ ] With the previous release installed, the "Update available" dialog appears and Download
      opens the correct file for that platform.

## 6. Quick manual test of the check

Run an old build with the version forced low, against the real latest release:

```bash
./gradlew :desktopApp:run -Pbitchord.version=0.1
```

The dialog should appear. Run it with the current published version and it should not.
