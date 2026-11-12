# Muses Hub — Test Checklist (beta)

Fill this in on your Windows machine. Source: HUB_PLAN.md sections 1 and 7, plus the unverified
assumptions listed in HANDOFF.md (H3, H4, H5, H6). Nothing in the Hub has been compiled or run yet,
so work top to bottom: section 0 first, because every later section depends on it.

Mark each cell: `PASS`, `FAIL` (add a note), or `n/a`. Put anything odd in the Notes column.
Open it from the profile menu (Modern layout) or the sidebar (Legacy layout): **Media Hub**.

## 0. Build and start
| # | Check | Result | Notes |
|---|---|---|---|
| 0.1 | CI `./gradlew :shared:jvmTest :desktopApp:test` is green (if red, paste the log to the next account) | | |
| 0.2 | Muses starts, existing music playback / queue / lyrics / accounts / settings behave as before | | |
| 0.3 | Media Hub opens from the profile menu (Modern) | | |
| 0.4 | Media Hub opens from the sidebar (Legacy) | | |
| 0.5 | Windows-only test `windowsReaderCanReadItsOwnProcess` passed (memory reader works) | | |

## 1. Beta goals (HUB_PLAN section 1)
| # | Goal | Result | Notes |
|---|---|---|---|
| 1 | YouTube plays with ads blocked | | |
| 2 | At least one DRM service plays (Netflix, Prime Video or JioHotstar), or the reason it cannot is written down | | |
| 3 | Switching between 2+ services works; the one you left is closed (hibernated) | | |
| 4 | Total RAM stays at or under 2 GB in normal use (measured, see section 3) | | |
| 5 | Your own Chromium extensions load, or each failure is recorded with its cause (see section 4) | | |
| 6 | Music playback, queue, lyrics, accounts and settings are untouched | | |

## 2. Per service (plays / ads blocked / fullscreen / login persists / extension works)
`Ads blocked` only applies to YouTube (and Twitch if you test it). `Login persists` = still signed in after
closing the service in Muses and opening it again, and after restarting Muses.

| Service | Opens | Plays | Ads blocked | Fullscreen | Login persists | Extension works | Notes |
|---|---|---|---|---|---|---|---|
| YouTube | | | | | | | |
| Netflix | | | | | | n/a | |
| Prime Video | | | | | | n/a | |
| JioHotstar | | | | | | n/a | |
| Twitch | | | | | | | |

DRM detail (fill in for each DRM service you tried):

| Service | Widevine prompt or install asked by the browser? | Which browser was used (Brave / Edge) | Result with Brave | Result with Edge |
|---|---|---|---|---|
| Netflix | | | | |
| Prime Video | | | | |
| JioHotstar | | | | |

## 3. Memory (RAM in MB; read the numbers on the Hub page and compare with Task Manager)
Muses figure is its own working set. The service figure is the whole browser tree summed, so it can read
higher than Task Manager's Memory column (shared pages are counted per process).

| Situation | Hub page: total | Hub page: Muses | Hub page: services | Task Manager (Muses + browser) | Notes |
|---|---|---|---|---|---|
| Muses idle (nothing playing, Hub page shows "nothing open") | | | | | |
| Muses playing music only | | | | | |
| Muses + YouTube | | | | | |
| Muses + YouTube + Netflix (switching; only one open at a time for now) | | | | | |
| After closing one service | | | | | |
| After Close all | | | | | |

| # | Check | Result | Notes |
|---|---|---|---|
| 3.1 | Page shows "not measured" only on non-Windows; on Windows it shows real numbers | | |
| 3.2 | Warning appears above 1600 MB (bar turns amber, note appears once, not repeatedly) | | |
| 3.3 | Above 2048 MB the page says over the limit; the active service is NOT closed | | |
| 3.4 | Memory readout disappears ("nothing open") after Close all | | |

## 4. Extensions (your own Chromium extensions)
Extensions live in the Muses Hub browser profile (`%LOCALAPPDATA%\Muses\Hub\Profile`), not in your normal browser profile.

| Extension | Loads in the Hub browser? | Works on the page? | Toolbar button / popup reachable in the app window? | Cause if it fails / notes |
|---|---|---|---|---|
| | | | | |
| | | | | |
| | | | | |

## 5. Hub behaviour
| # | Check | Result | Notes |
|---|---|---|---|
| 5.1 | Click a tile: the browser window opens at the service's start page; the tile shows "Open" | | |
| 5.2 | Browser line says the right browser (Brave, else Chrome, else Edge) | | |
| 5.3 | With no browser installed the page says so and tiles show a clear message instead of crashing | | |
| 5.4 | Starting a service pauses Muses only if it was playing; it does not resume by itself | | |
| 5.5 | Clicking a second service closes the first, then opens the second (only one open at a time for now) | | |
| 5.6 | Clicking the already open service shows "already open" and does not open a second window | | |
| 5.7 | Close (single service) closes the window and the browser process tree ends (check Task Manager) | | |
| 5.8 | Close all closes everything | | |
| 5.9 | Closing the browser window by hand: within about 3 seconds the page shows the service as not open | | |
| 5.10 | Leaving the Hub page while a service is opening does not break it (come back, state is correct) | | |
| 5.11 | Closing Muses by the window X closes the Hub browser (no leftover brave/chrome/msedge from the Hub profile) | | |
| 5.12 | Quitting from the tray closes the Hub browser; Quit does not hang (more than 10 s is a FAIL) | | |
| 5.13 | Muses started again while a Hub browser from an earlier run is still open: nothing crashes (a stale browser is picked up by its profile) | | |

## 6. Unverified assumptions from the handoffs (each one is a thing nobody has run)
| # | Assumption | Result | Notes |
|---|---|---|---|
| 6.1 | Windows exposes process command lines for Brave/Chrome/Edge child processes (the tree is found by `--user-data-dir`) | | |
| 6.2 | `taskkill /PID` without /F closes an app-mode window promptly (graceful close works) | | |
| 6.3 | `--disable-background-mode` lets the browser exit when its last window closes | | |
| 6.4 | A second `--app=` launch on the same profile is handed to the running browser (no second browser appears) | | |
| 6.5 | The psapi memory call and struct offsets read correct numbers (compare with Task Manager) | | |
| 6.6 | Brave asks to install Widevine on the first DRM site (or already has it) | | |
| 6.7 | Extension buttons are reachable in app-mode windows | | |

## 7. Known limits you should see (these are expected, not failures)
- One service open at a time: a single window cannot be closed while another is open.
- Muses cannot bring an open service's window to the front; use the taskbar.
- Closing a service loses the playback position; reopening starts at the home page.
- Memory cannot be split per service; it is one browser total.
- Ad blocking is the browser's (Brave Shields), and it can stop working on YouTube at any time.
- A start URL typed as `host:port` (for example `localhost:8080`) is refused; type `http://localhost:8080`.

## 8. Result
| Item | Value |
|---|---|
| Date tested | |
| Windows version | |
| Browser and version | |
| Muses build (commit or CI run) | |
| Beta goals met (count of 6) | |
| Blocking problems to give to the next account | |
