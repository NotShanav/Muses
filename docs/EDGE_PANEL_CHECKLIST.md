# Side panel: manual checklist (EDGE 3)

Nothing here was run by the author: the code was written without a compiler or a screen. Tick each line on a real PC and send `desktop.log` (lines starting with `edge:`) for anything that fails.

Turn it on: Settings > Advanced and diagnostics > Experimental > **Side panel (experimental)**. Everything below assumes it is on, defaults unchanged (right edge, 420 dp, 4 px strip, 150 ms open delay, 700 ms close delay).

## 0. Off means off
- [ ] With the switch off: no "Side panel" entry in the account menu, no tray items, Ctrl+Shift+P does nothing, the window looks and moves as before, Task Manager shows no extra activity.

## 1. Enter, reveal, hide, leave
- [ ] Account menu > Side panel: the window shrinks to a tall narrow card, docks to the right edge and slides in once.
- [ ] Panel header shows Muses, Pin, Expand, Hide; no red/yellow/green caption buttons; the top bar cannot drag the window.
- [ ] Move the pointer away for about a second: it slides out. Window is gone from the screen.
- [ ] Touch the edge for under 150 ms (brush): nothing. Rest on it for 150 ms: it slides in.
- [ ] Leave for 400 ms and come back: no hide. Leave for 700 ms: it hides.
- [ ] Expand: the old window returns with its old size and place (maximized stays maximized). Ctrl+Shift+P enters again.

## 2. Focus and typing
- [ ] Reveal while typing in another app (Notepad): your typing keeps going to Notepad.
- [ ] Click inside the panel, open Search, type: the text appears. Keep typing: the panel stays open at least 4 s after the last key.
- [ ] Open the account menu or a dialog in the panel: the panel stays until it is closed.
- [ ] Click Pin: it stays open with the pointer far away. Click Pin again, then move away: it hides. Hide works while pinned.

## 3. Two monitors
- [ ] "Where the pointer is": touching the right edge of monitor 2 opens it on monitor 2; same for monitor 1 (outer edges only).
- [ ] "Main screen": touching the other monitor's edge does nothing.
- [ ] "Pick a screen" > Screen 2 opens there.
- [ ] The edge shared by two monitors never triggers; the outer part of a taller monitor does.
- [ ] A monitor to the left of the main one (negative x) docks correctly on its left and right edges.

## 4. Taskbar on each side
- [ ] Taskbar at the bottom, top, left, right: the panel stays clear of it (never underneath), with the 12 dp margins and 8 dp gap.

## 5. Scaling
- [ ] 100 %, 125 %, 150 %: the panel keeps about its width in dp, the strip is the set number of pixels, the slide is smooth, text is not blurry.
- [ ] Monitors with different scaling side by side: the panel size follows the monitor it opens on.

## 6. Fullscreen and dragging
- [ ] Fullscreen video (browser, VLC) and a fullscreen game: touching the edge does not open the panel. With "Hide side panel while a full-screen app is active" off it opens.
- [ ] Drag a window across the edge (button held): no reveal. Drag a scrollbar to the edge: no reveal. A held button also stops a hide.

## 7. Tray
- [ ] Tray menu has "Side panel mode: off (click to enter)"; after entering it reads "...on (click to leave)" and "Show panel now" appears; Show panel now slides it in.
- [ ] Close to tray while in the mode, then Show from the tray: the panel slides in.
- [ ] While hidden to the tray, touching the edge does nothing.

## 8. Settings
- [ ] Side, monitor, width, strip width, trigger zone (whole / part), open delay, close delay, speed, fullscreen, low power, pin by default all change the behaviour at once (the panel re-docks when the side or width changes).
- [ ] Test slide: slides out and back in.
- [ ] Start in side panel mode: restart the app; it starts hidden at the edge. Switch the feature off while in the mode: the old window returns.
- [ ] Search in Settings for "panel": the rows are found.
- [ ] "Where is the trigger?" text matches the values.

## 9. Layout at 340 to 640 dp
- [ ] At 340, 420 and 640 dp: Home, Search, Library, a playlist, the queue, full-screen player and lyrics fill the width without horizontal scrolling; the floating pill fits; dialogs, menus and the account menu stay inside the window; touch targets are 44 dp.

## 10. Rotation and display changes
- [ ] Rotate a monitor or change its scaling with the panel open: it re-docks, nothing is left half off screen.
- [ ] Unplug the monitor the panel was on: it moves to a remaining monitor; leaving the mode restores the old window on a visible monitor.

## 11. Idle cost
- [ ] Task Manager, mode on and panel hidden, 2 minutes idle: CPU stays near 0 %, no GPU use; the poll slows after 5 s without movement. Low power watcher on: slower again.
- [ ] Mode off: no edge activity at all.
