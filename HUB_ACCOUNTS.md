# Muses Hub — Relay Rules and Account Briefs

Read `HUB_PLAN.md` first. This file tells each Claude account what to do and how to hand over.

## Operating conditions
- Every account is a **free-tier Claude account on Sonnet 5.5 (medium)**, with a usage limit that resets
  about every 5 hours. Each account starts in a **fresh chat with no memory of earlier work**.
- So: tasks are small, instructions are self-contained, and **everything important is written to files in
  the repo** (never rely on chat memory).
- The owner cannot answer questions during a task. Decide using these rules, record the decision in HANDOFF.md,
  keep going. Never stop to ask.

## Save your limit (read this)
1. `DesktopApp.kt` is about 8,400 lines. **Never open it whole.** Use `grep -n` and `sed -n 'A,Bp'` on small ranges.
2. Do not re-explain the project back. Do not write long summaries. Short replies.
3. Do one task (below), then stop. If you feel your limit coming, **stop adding code and write your HANDOFF
   entry first**. A finished small piece plus a good handoff beats a big half-done piece.
4. Write each file as soon as it is ready; do not hold work in chat.

## Procedure for EVERY account
1. Unzip the repo. Read `HUB_PLAN.md`, then this file, then the **last 2 sections** of `HANDOFF.md`.
2. If the owner pasted a CI (GitHub Actions) error log into the chat, **fix those errors first**, only in
   the files named by the log, before starting your task.
3. Do your task only. Touch only the files you own (table below). Do not refactor anything else.
4. Add tests for pure logic where your task says so.
5. Manual compile review of every file you wrote: imports present, no unresolved names, nullability, `@Composable`
   context, no duplicate declarations, visibility (`internal` types must not leak `private` types), Linux still compiles.
6. Append a section to `HANDOFF.md` (template at the bottom). **Never overwrite earlier sections.**
7. Output the full updated project as `Muses.zip` (all files, same structure, exclude build output, `.gradle`,
   `.kotlin`). Final message: 5-line summary + zip path. Say clearly it was **not compiled**.

## File ownership (avoids edit conflicts)
Each account creates its own new `DesktopHub*.kt` files in
`desktopApp/src/main/kotlin/com/music/bitchord/desktop/` (tests in `desktopApp/src/test/kotlin/com/music/bitchord/desktop/`).
Only **H6** may edit `DesktopApp.kt`, and only at one clearly marked hook. Nobody edits `DesktopBrowserSignIn.kt`
(copy ideas from it into your own file instead).

## Task list (one account per task; ~one free-tier window each)

### H1 — Models and registry
Files: `DesktopHubModels.kt`, `DesktopHubRegistry.kt`, `DesktopHubRegistryTest.kt`.
- `HubService(id, name, startUrl, enabled, keepAlive)`; `HubServiceState` = HIBERNATED / LIVE / ACTIVE.
- `HubSettings`: browser preference, memory warn MB (1600), memory cap MB (2048), max live services (1).
- Registry: default list from HUB_PLAN §4, add/remove/rename/reorder, validate URL (http/https only),
  JSON persistence. First `grep` how `DesktopPersistence` (`boolean/string/saveBoolean/saveString`) and
  `DesktopAppearanceSettings` store values and follow that pattern; serialize with kotlinx.serialization already used in the repo.
- Tests: defaults, add/remove/reorder, URL validation, JSON round trip, corrupt JSON falls back to defaults.

### H2 — Browser finder and launch arguments
Files: `DesktopHubBrowser.kt`, `DesktopHubBrowserTest.kt`.
- Read `DesktopBrowserSignIn.kt` lines ~40-60 (how it locates Chrome/Edge/Brave). Re-implement detection in your
  own file as a function that takes an injectable "does this path exist" lambda so it is testable.
- Preference order: Brave, Chrome, Edge (or the user's setting). Return a `HubBrowser(name, executable)`.
- Pure function `buildLaunchArgs(browser, profileDir, url)` -> list: `--app=<url>`, `--user-data-dir=<profileDir>`,
  `--no-first-run`, `--disable-features=...` only if you can justify it. One profile folder per Muses (shared by all
  services so logins and extensions persist); decide location next to Muses' other data and record it.
- Tests: detection order, missing browser -> null, args contain exact expected flags.

### H3 — Launcher and process tree
Files: `DesktopHubLauncher.kt`, `DesktopHubLauncherTest.kt` (only pure parts).
- Starts a browser window with `ProcessBuilder` (JDK 21). Use `ProcessHandle` to collect the **process tree**
  (`descendants()`). Important: Chromium may hand the new window to an already-running browser process using the
  same profile, so the launched process can exit quickly. Handle that: track the tree by **window/profile**,
  not only the first PID. Record how you solved it, and the case you could not solve.
- API: `launch(service): LaunchResult`, `close(service)`, `treePids(service): List<Long>`. Closing = graceful first,
  destroy after a timeout. Never kill processes that are not in the tree you launched.
- Keep the process-listing step behind an interface so it can be faked in tests.

### H4 — Memory reader and governor
Files: `DesktopHubMemory.kt`, `DesktopHubMemoryTest.kt`.
- `interface MemoryReader { fun workingSetMb(pids: List<Long>): Long }`. Windows implementation using JNA
  (grep existing JNA use in `DesktopWindowsCrypto.kt` for import style; `jna-platform` is already a dependency;
  keep Windows-specific classes inside this file's Windows-only class). Non-Windows: return 0 and say so.
- `MemoryGovernor`: pure policy `decide(totalMb, services, settings): List<Action>`; actions = Warn, Hibernate(id), None.
  Rules exactly as HUB_PLAN §3 (warn at 1600, over 2048 close least-recently-used non-active, never kill the active one).
  Include hysteresis so it does not flap. Total = Muses own heap/working set + all service trees (Muses figure via
  `Runtime`/ProcessHandle; record how).
- Tests: every rule, LRU choice, active service protected, hysteresis.

### H5 — Controller
Files: `DesktopHubController.kt`, `DesktopHubControllerTest.kt` (with fakes).
- Glue: registry + launcher + memory + governor. `activate(id)`, `hibernate(id)`, `closeAll()`. Poll memory on a
  coroutine every ~3 seconds only while at least one service is live. Expose state as `StateFlow` for the UI.
- On `activate`: respect max-live setting by hibernating the least recently used first. Hibernate = close window,
  keep id/startUrl. Optional music hook as a lambda parameter `onServiceActivated: () -> Unit` (default no-op).
- Make sure everything is cancelled on app exit.

### H6 — Hub page and the single hook
Files: `DesktopHubPage.kt` and ONE small edit region in `DesktopApp.kt`.
- Minimal plain Compose page: grid of service tiles (click = activate), list of running services with a Close
  button, memory readout (MB used / 2048), and a "Browser: Brave" line. Style: reuse existing `Desktop*` colours;
  no new design work.
- The hook: find how `DesktopDestination` is defined and used (`grep -n "DesktopDestination"`), add a `HUB`
  entry with the least possible edits, and one entry point to reach it (a menu item in the profile menu or an
  existing nav spot — grep, do not redesign). Mark the edit with the comment `// MUSES-HUB hook`.
- Wire `onServiceActivated` to pause Muses playback ONLY using an existing play/pause function already used by
  the media keys in `DesktopApp.kt` (`togglePlayPauseFromUser` style). If it cannot be done safely, leave it
  as a no-op and record that.

### H7 — Integration review and CI fixing
No new features. Read every `DesktopHub*.kt` file and the hook; compile-review them together; fix CI errors the
owner pastes. Write `HUB_TEST_CHECKLIST.md` (the list in HUB_PLAN §7 as a table the owner can fill in).
Update README with a short Hub section including the YouTube-terms note.

### H8+ — Spare accounts
Used only for: fixing new CI error logs the owner pastes, the owner's extension manifest review
(classify each extension: works / needs rewrite / needs Brave), and bugs found during the owner's test run.

## Handoff template (append to HANDOFF.md)
```
## Hub — <task id> (<date>)
### Did
### Files added/changed
### Decisions (and why)
### Verified   (say plainly: NOT compiled / manual review only; list what you checked)
### Known problems / unverified assumptions
### Exact next step for the next account
```

## Starter prompt to paste into each fresh chat (with Muses.zip, HUB_PLAN.md, HUB_ACCOUNTS.md attached)
> You are working on the Muses repo (attached zip). Read HUB_PLAN.md and HUB_ACCOUNTS.md first, then the last two
> sections of HANDOFF.md. Your task is **<H#>**. Follow the procedure and rules in HUB_ACCOUNTS.md exactly. I cannot
> answer questions; decide yourself and record decisions in HANDOFF.md. Never open DesktopApp.kt whole. Finish with
> the updated Muses.zip and a 5-line summary.
