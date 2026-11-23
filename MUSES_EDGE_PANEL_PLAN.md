# Muses edge panel: the whole app as a floating side window that slides in from the screen edge

Read only: this file and the last 3 sections of `HANDOFF.md`. **Use the zip the owner attached.** Three sequential passes, one after the other: Part 1, Part 2, Part 3. Each returns the full updated `Muses.zip`; pass each returned zip to the next pass.
Do NOT touch the abandoned web-client work (`MUSES_WEB_PLAN.md` and anything it added); leave it as it is.

## The idea (owner's words, tidied)
Muses can shrink into a **floating side window**, like a phone's floating window: a narrow, tall, always-on-top copy of the WHOLE app (browse, search, change songs, volume, queue, lyrics) that is **docked to the screen edge and hidden**. Moving the mouse to the edge makes it **slide in smoothly**; the owner uses it; moving the mouse away makes it **slide out again** so the screen behind is free. A pin keeps it open. It is a mode of the existing window, not a second app.

## What the owner's popup app (Bloom) taught us (read from its source; its tuned numbers are good defaults)
Bloom (Tauri/Rust, not reusable here) watches the cursor every 60 ms with `GetCursorPos`, opens when the cursor is within **8 px of the screen edge**, closes **700 ms after the cursor leaves**, shows and hides each window once at startup because Windows ignores positioning on a never-shown window, and does NOT detect fullscreen apps (the owner's known annoyance). Muses does the same job in Kotlin, adds a real slide animation, fullscreen suppression, and zero focus stealing.

## Facts about the Muses code (verified, do not re-investigate)
- Single process, single window: `Main.kt` creates ONE Compose `Window` (undecorated on Windows via `DesktopPlatform.drawsOwnWindowFrame`, `transparent = DesktopWindowBackdrop.available`, `resizable = true`, `rememberWindowState(1220x780)`). All app state lives in that composition, so the panel MUST be that same window changing mode; a second window cannot host the same app state.
- Already present: `DesktopWindowMode` (placement/maximize state), `DesktopWindowVisibility` (tray hide, pauses animation when not visible), `DesktopDisplayGeometry` (display-aware minimum size and work area), `DesktopDisplayWatch`/`DesktopDisplayWatcher` (size, orientation, touch), `DesktopWindowGuard`, native frame `desktopApp/native/window/window_jni.cpp` (custom frame, resize borders, `WM_NCCALCSIZE`, `WM_NCHITTEST`).
- The responsive layout from the 360 work (`DesktopAdaptive`, Compact/Medium window classes, compact top bar, full-width pill, touch targets) already makes the UI usable at about 380 to 560 dp wide and tall: the panel simply uses a narrow window and the app adapts.
- `Window(...)` is created with no `alwaysOnTop` or `focusable` parameter today: they must become state-driven parameters. `undecorated` cannot change at runtime and is not needed (already undecorated).

## Hard rules
1. No new Gradle dependencies or version changes; only APIs already used in the repo or in the JDK (`java.awt.MouseInfo`, `GraphicsEnvironment`, `Window.setBounds/setAlwaysOnTop/setFocusableWindowState`); copy exact imports; never guess an API. Any Windows call through an OWN minimal JNA interface (pattern of the voice hotkey and buds files). Must compile on Linux (Windows-only parts behind `DesktopPlatform.isWindows`, lazy).
2. Do not change playback, audio, lyrics, library or account logic. Do not delete settings or features. Do not edit `sharedUi`, `shared`, `app`.
3. Compile-review every file (imports, nullability, `@Composable` context, duplicate names via grep, no `private` type in an `internal` signature). Pure logic (geometry, easing, state machine, timers with a fake clock) gets unit tests; no test needs a screen or a window.
4. Battery matters (the owner finds the app heavy): the watcher must cost almost nothing while idle; no animation work while hidden; when hidden the app uses the existing `DesktopWindowVisibility` path so frames stop.
5. Mark edits to existing files `// EDGE <part> hook`. Append `## EDGE <part>` to HANDOFF.md (Did / Files / Decisions / Verified (say plainly NOT compiled) / Deferred / Exact next step). Output the full project as `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`). Never open `DesktopApp.kt` whole: `grep -n` then `sed -n 'A,Bp'`. Short replies; if the limit is near stop and write a **Deferred** list.

## Behaviour (decided)
- **Modes:** `NORMAL` (today's window) and `PANEL`. Entering PANEL remembers the normal bounds and maximized state and restores them on leaving. The window stays the same Compose window and keeps all state (queue, scroll, search).
- **Panel geometry:** docked to the chosen side (Right default, Left) of the chosen monitor's WORK area (taskbar respected); default width 420 dp (range 340 to 640), height = work area height minus a top and bottom margin of 12 dp (floating card look; rounded corners are already provided by the OS/backdrop), a gap of 8 dp from the edge when open. No title bar buttons that conflict: a slim panel header with **Pin**, **Expand to full window**, **Hide**.
- **States:** `HIDDEN` (off screen, window not visible, no frames), `REVEALING` and `HIDING` (slide), `PEEK` (open, opened by hover, auto-hides), `ACTIVE` (the owner clicked inside: it stays until the mouse has been away AND nothing is being edited), `PINNED` (stays open until unpinned).
- **Open trigger:** the cursor is inside the edge zone (default 4 px wide, over the full height of that monitor, optionally only a vertical segment given as a percentage range) continuously for a **dwell of 150 ms** (prevents accidental reveals when the cursor merely brushes the edge). Suppressed when the foreground window is a fullscreen app (its rect covers the monitor; default ON, can be switched off) and when a mouse button is held (dragging a window or a scrollbar).
- **Close trigger:** the cursor has been outside the panel rectangle plus a 24 px margin for **700 ms** and the panel is not PINNED, the mouse button is not held, no popup/menu/dialog of the app is open, and no text field has focus with unsent text (a text field with focus delays closing by an extra 4 s only when the user typed in the last 4 s). Moving back before the delay ends cancels the hide.
- **Animation:** slide on the X axis only, 240 ms ease-out cubic in, 200 ms ease-in out (setting "Animation speed": Fast 140 ms, Normal, Slow 360 ms; `reduceAnimation` makes it a 1-frame snap), driven at the monitor refresh rate (about 16 ms) by a coroutine that calls `window.setBounds` (or `setLocation`) from the AWT/Compose main thread ONLY while a slide is running; no timer runs during steady states except the edge watcher. Position is computed from elapsed time (not frame counts) so it stays smooth when frames drop; interrupted slides continue from the current position.
- **Focus:** a hover reveal must NOT steal focus from the app the owner is using: show with `focusableWindowState = false` (PEEK); the first click inside turns focus on and calls `requestFocus()` (ACTIVE) so typing works. Always on top while the panel is visible; not in the taskbar/Alt-Tab while in PANEL if a safe API exists (otherwise leave it and say so).
- **Edge watcher:** `MouseInfo.getPointerInfo()` (JDK, multi-monitor aware, in AWT user-space coordinates) every 80 ms while PANEL mode is on and the panel is HIDDEN; **adaptive**: 150 ms after 5 s with no cursor movement, back to 80 ms on movement; 40 ms while REVEALING/HIDING/PEEK checks; stop completely when PANEL mode is off. Geometry in one pure object with a fake-clock tested state machine. DPI: all coordinates in AWT user space; the monitor is found with `GraphicsEnvironment` screen devices and `GraphicsConfiguration.bounds` plus the work-area insets (`Toolkit.getScreenInsets`).
- **Entering/leaving:** a button in the existing title/top bar area ("Side panel"), a tray menu item, the Settings switch "Start in side panel mode", and a global-free keyboard shortcut inside the app. Leaving restores the previous size/position (clamped to the current display) and un-pins.

---

## Part 1 of 3: Geometry and edge detection

### Geometry and watcher foundation
1. `DesktopEdgeGeometry`: monitor selection, work area, docked rectangle for a side/width/margins, edge-zone test (with the optional vertical segment), panel-plus-margin test, slide position at time t (ease-out cubic / ease-in) with interruption support, clamping to the display. All pure, tested (both sides, two monitors side by side with different sizes, a monitor to the left of the primary with negative x, work-area insets from a taskbar on each side, interrupted slide, speed settings).
2. `DesktopEdgeWatcher`: the adaptive poll loop on a daemon coroutine, using an injectable `PointerSource` and `Clock` (tests use fakes), plus the fullscreen check: Windows only, own minimal JNA (`GetForegroundWindow`, `GetWindowRect`, `MonitorFromWindow`/`GetMonitorInfoW` or compare with the AWT monitor bounds; ignore the shell/desktop window class `Progman`/`WorkerW` and our own window); returns false on any failure. Mouse-button-held check through `MouseInfo`/`GetAsyncKeyState` (own interface) with a safe default of false.

---

## Part 2 of 3: Panel controller, window integration and engine tests

### Controller and window integration

3. `DesktopEdgePanel` state machine `HIDDEN/REVEALING/PEEK/ACTIVE/PINNED/HIDING` as a `StateFlow`, driven by the watcher events and window events (a window mouse-enter/exit via the Compose pointer events at the root, `windowOpened/Closed`, focus gained/lost); the dwell and hide timers use the fake-clock-friendly clock; the `Window` call in `Main.kt` gets `alwaysOnTop` and `focusable` from this state and `resizable = false` in PANEL, `visible` combined with `DesktopWindowVisibility` (do not break the tray hide).
4. Mode switching in `DesktopEdgePanel`: `enterPanel()` saves normal bounds and maximized state (through the existing `DesktopWindowMode`), sets the minimum size to the panel width, docks; `leavePanel()` restores. First show must work on Windows even though the window was never positioned (Bloom's lesson): show once off-screen before the first reveal.
5. Tests: the state machine over scripted pointer paths (brush the edge for 100 ms: nothing; dwell 150 ms: reveal; leave 400 ms and return: no hide; leave 700 ms: hide; mouse button held: no hide; pinned: no hide; fullscreen foreground: no reveal), geometry tests above, interrupted-slide continuity.
Acceptance: with PANEL mode off nothing runs; on, the window docks hidden, reveals from the edge with a slide, hides after the delay, never steals focus until clicked, and leaving PANEL restores the old window.

---

## Part 3 of 3: Panel UX, settings and polish

### Panel UX, settings and polish (new files `DesktopEdgePanelHeader.kt`, `DesktopEdgeSettings.kt`, `DesktopEdgeSettingsUi.kt`; hooks in the top bar/title bar, tray, Settings)
1. Panel header: slim bar with the app name, **Pin**, **Expand** (leave PANEL, restore), **Hide** (slide out now); replaces the normal caption controls while in PANEL; follows the compact/touch sizes (44 dp targets); no drag-to-move; a thin grip on the inner edge may resize the width (optional, persisted).
2. Layout inside the panel: rely on the existing Compact/Medium responsive layout; check and fix any fixed width or overlay that breaks at 340 to 640 dp (the pill uses the window width, popups and menus stay inside the window, the full-screen player and lyrics fill the width). Only desktop-owned layout files may change.
3. Settings (`DesktopEdgeSettings` persisted with `DesktopPersistence`) and a group "Side panel (experimental)" in the Experimental/Advanced area (look at `DesktopSettingsCategory` and the voice/earbuds groups): enable; start in panel mode; side (Left/Right); monitor (the one the cursor is on / primary / pick); width; edge trigger width (2 to 12 px); trigger zone (full edge or from X% to Y% of the height); open dwell (50 to 400 ms); close delay (300 to 2000 ms); animation speed; hide while a fullscreen app is active; low power watcher (150 ms poll); pin by default; a live "Test slide" button.
4. Tray menu: "Side panel mode" toggle; "Show panel now"; plus a reminder in the Settings text that the edge zone is 4 px wide and where it is.
5. Pure tests for the settings validation/clamping and the mapping to geometry; a manual checklist in `docs/EDGE_PANEL_CHECKLIST.md` (two monitors, taskbar on each side, 100/125/150 percent scaling, fullscreen video, dragging a window across the edge, typing in search with the panel open, tray hide/show, leaving the mode, rotation).
Acceptance: the owner can turn the feature on in Settings, dock to either side, tune dwell/delay/width, and every control in the panel works; with the feature off the app is exactly as before.

## Owner's route
Put `MUSES_EDGE_PANEL_PLAN.md` in the root of the latest zip. Run Part 1, then Part 2, then Part 3, passing the returned zip on each time. Build after each part (compile check). Test with the checklist; send `desktop.log` if the panel misbehaves.
