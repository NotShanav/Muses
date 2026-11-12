# Muses Hub — Project Plan (Beta)

Owner's idea in one line: Muses stays a music player, and also becomes a **media hub** — one app where
you click YouTube, Netflix, Hotstar, etc., it opens that service, you can switch between running services
from a dock, and everything is ad-blocked, inside a fixed RAM budget.

This file is the single source of truth for the idea. Read it fully before doing anything.

## 1. Beta goal (what "done" means)
The beta is about **"does it play and is it supported"**, NOT about looks. UI is minimal and plain.
Beta is done when, on the owner's Windows machine:
1. YouTube plays with ads blocked.
2. At least one DRM service (Netflix or Prime Video or Hotstar) plays, or we have a recorded reason it cannot.
3. Switching between 2+ services from inside Muses works; inactive services hibernate.
4. Total RAM (Muses + all web processes) stays at or under 2 GB in normal use. Measured, not guessed.
5. The owner's own Chromium extensions load, or each failure is recorded with its cause.
6. Existing music playback, queue, lyrics, accounts and settings are untouched and still work.

## 2. Architecture decision for the beta (already made — do not re-open)
Services run in a **real Chromium browser launched by Muses in app mode** (no tabs, no address bar),
using a **Muses-owned browser profile folder**. Default browser: **Brave**; fallback Chrome, then Edge.
Why: a real browser already has the video codecs (H.264/AAC), Widevine DRM, Brave Shields ad blocking
and full extension support. Embedded Chromium (KCEF/JCEF) ships without H.264/AAC and Widevine, so
it is the risky choice for a beta whose question is "does it play".

Muses itself is only the **controller**: service list, launcher, dock/switcher, memory governor.

### Phase 2 (NOT part of the beta, do not start): embedded engine
Embedded browser inside the Muses window plus a built-in Rust ad-block engine (Brave's `adblock-rust`,
MPL-2.0) reached through a small JNI wrapper, plus a "Muses add-ons" script injection layer.
Only start after beta results show what actually plays.

## 3. RAM budget (hard numbers)
- Total ceiling: **2 GB** for everything (Muses + every web process). Soft warning at **1.6 GB**.
- Muses alone while playing music is about 0.8 GB, so the web side has roughly 1.2 GB.
- Policy: **one live service at a time**. Others are *hibernated* (window closed, only the service id and
  start URL kept). A second service may stay live in the background only if the total is under 1.6 GB.
- Over 1.6 GB: warn. Over 2 GB: close the least-recently-used non-active service. If still over, warn
  the owner and do nothing destructive to the active one.
- Memory is measured by summing the working set of the browser's whole **process tree** (the browser
  spawns many child processes; killing or counting only the first one is wrong).

## 4. Components (each is its own file — see ownership in HUB_ACCOUNTS.md)
| Component | File | Job |
|---|---|---|
| Models | `DesktopHubModels.kt` | `HubService`, `HubServiceState`, `HubSettings` (pure data) |
| Registry | `DesktopHubRegistry.kt` | add/remove/reorder services, persistence, default list |
| Browser finder | `DesktopHubBrowser.kt` | find Brave/Chrome/Edge, build launch arguments |
| Launcher | `DesktopHubLauncher.kt` | start/stop browser windows, track process tree |
| Memory | `DesktopHubMemory.kt` | `MemoryReader` interface, Windows reader, `MemoryGovernor` policy |
| Controller | `DesktopHubController.kt` | glues registry + launcher + governor; hibernate/resume |
| Page | `DesktopHubPage.kt` | minimal Compose page: service tiles, running list, memory readout |
| Hook | one small edit in `DesktopApp.kt` | a Hub destination + (later) music pause on launch |

Default services (start URLs, user-editable): YouTube https://www.youtube.com, Netflix https://www.netflix.com,
Prime Video https://www.primevideo.com, JioHotstar https://www.hotstar.com, Twitch https://www.twitch.tv.

## 5. Known limits (state these honestly in the UI and in HANDOFF)
- Hibernate = close window and later reopen the start URL. Exact playback position is NOT restored in the beta.
- Brave may ask to install Widevine on first DRM site (unverified). Edge is the DRM fallback.
- Extension toolbar buttons/popups may not be reachable in app-mode windows (unverified, test it).
- Muses cannot cap Chromium's memory from outside; it can only measure and close windows.
- Ad blocking on YouTube is an ongoing cat-and-mouse; per-site "Shields down" is done in Brave itself.
- Blocking ads on YouTube is against YouTube's terms. README disclaimer must mention it before release.

## 6. Hard rules (carried over from the Muses build)
1. **No new dependencies, no version changes.** Use only what the repo already has (JDK 21 APIs,
   `net.java.dev.jna:jna-platform:5.19.1` is already present, kotlinx.coroutines, kotlinx.serialization, Compose).
2. Use only APIs already used elsewhere in the repo, or that you confirmed in sources. Grep first, copy the
   exact import. Never guess an API.
3. Do not touch playback, audio, lyrics, library or account logic. Do not delete features or settings.
4. Windows is the target. Code must still **compile** on Linux (use `DesktopPlatform` checks, no Windows-only
   imports outside Windows-guarded code; JNA Windows classes only inside a clearly separated file).
5. Desktop-only: do not edit `sharedUi`, `shared`, `app` (Android).
6. Pure logic gets unit tests (registry, launch args, memory policy). No tests that need a real browser.
7. Nobody can run Gradle in these accounts. State plainly in HANDOFF whether you compiled (you will not) or
   did a manual compile review (imports, nullability, @Composable context, duplicates).

## 7. Success measurements to record in the final handoff
RAM in MB for: Muses idle; Muses + YouTube; Muses + YouTube + Netflix; after hibernating one.
Pass/fail per service per feature: plays, ads blocked, fullscreen, login persists, extension works.
