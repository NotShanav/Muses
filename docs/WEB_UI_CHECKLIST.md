# Muses Web: manual UI checklist (WEB3, extended in WEB4)

Nothing here is automated. Tick each line in a real browser (Chrome or Edge, then Firefox) against
`http://localhost:8765/` with Settings > Advanced > Experimental > Muses Web switched on. Sections 9 to 15
cover playback, hidden tabs, media keys, volume, voice, resume and the settings page.

Optional logic tests (Node 22+, no browser): `node desktopApp/web-tests/util.test.mjs`,
`player.test.mjs` and `voice.test.mjs` (all in `desktopApp/web-tests/`).

## 1. First load and size
- [ ] `/` loads with no red errors in the DevTools console and no blocked-by-CSP messages.
- [ ] Network tab, "Disable cache" on: the first load of Home is about 60 KB of HTML, CSS and JS in total (budget: under 150 KB, images excluded). No request leaves `localhost:8765`.
- [ ] Scripts are loaded as ES modules (`app.js`, then `js/*.js`); pages load lazily (`js/pages/home.js` only on Home).
- [ ] Opening `/api/ping` directly in a new tab gives 403 (no `X-Muses-Web` header); this is by design.
- [ ] The cookie `muses_web` is `HttpOnly` and `SameSite=Strict` (DevTools > Application > Cookies).

## 2. Layout sizes (DevTools device toolbar; also resize the window)
- [ ] 360 x 740 phone: one column, top bar (logo, search, account), bottom tabs (Home, Search, Library, Now playing), mini-player sits above the tabs, nothing scrolls sideways.
- [ ] 700 px tablet: same layout, wider grids, shelves still scroll sideways with snapping.
- [ ] 1024 px and wider: left rail replaces the bottom tabs (and adds Settings), mini-player spans the content area, top bar spans the full width.
- [ ] Every button, tab, row and the seek bar is at least 44 px tall (inspect a few).
- [ ] Safe-area padding looks right on an iPhone-sized emulation (notch, home bar).

## 3. Pages
- [ ] Home: shelves appear (Recents, mixes, Listen again, ...). Clicking a song card plays it and queues the rest of that shelf; clicking an album or playlist opens it.
- [ ] Search: typing 2+ letters shows suggestions after about 0.2 s; Arrow keys move, Enter searches, Esc closes. Results have sections; song rows play.
- [ ] Library: Liked music first, then your playlists, then saved ones. Signed out shows the "Sign in inside the Muses desktop app" message, not a blank page.
- [ ] Playlist: Play, Shuffle, rows with a "More" button (Play next, Add to queue, Add to playlist, Like). Liked music offers "Remove from liked music".
- [ ] Now playing: cover, title, artists, seek bar with times, shuffle, previous, play/pause, next, repeat (off, all, one), like. Tabs: Player, Lyrics, Queue.
- [ ] Lyrics tab: synced lines highlight and follow the song; clicking a line seeks; scrolling by hand pauses the auto-scroll for about 4 s; plain lyrics and "No lyrics" also work.
- [ ] Queue tab: current song marked; clicking another row jumps to it.
- [ ] Unknown hash such as `#/nope` shows "Page not found".
- [ ] Stop the desktop app: pages show "Cannot reach Muses on your computer..." with "Try again"; restart it and "Try again" works (a reload is needed once, the session is per launch).

## 4. Themes and battery saver (Settings)
- [ ] Follow the system, Light, Dark, AMOLED black, Album tint each switch at once, with no flash after a reload (the choice is kept in localStorage).
- [ ] Album tint: with a song playing, the background is a static two or three colour gradient from the cover; changing song cross-fades it (no moving shapes); text stays readable. With nothing playing it is plain dark.
- [ ] Battery saver on: top bar and mini-player become solid (no blur), no transitions anywhere (tint swap is instant), smooth lyric scrolling becomes a jump.
- [ ] Browsers without `backdrop-filter` (or with "reduce transparency") show solid bars.

## 5. Reduced motion
- [ ] OS "reduce motion" on (or DevTools > Rendering > Emulate prefers-reduced-motion): no transitions run (press a card, change tint, show a toast, play a song: all instant).
- [ ] With it off and the saver off, the only motion is a small press scale, the toast fade, the progress bar and the tint cross-fade (all `transform` or `opacity`).

## 6. Idle and hidden tab (battery)
- [ ] DevTools > Performance, record 20 s with nothing playing: no recurring timers, no animation frames, no network (the only repeating thing is the server's 20 s SSE keep-alive while the tab is visible).
- [ ] Hide the tab (switch tabs): Network shows the `/api/events` stream closed; show it again and it reconnects.
- [ ] While a song plays on Home, the progress bar updates about 4 times a second, and not at all while the tab is hidden (check the bar after returning: it jumps to the right place).
- [ ] `requestAnimationFrame` runs only on the Lyrics tab while playing and visible (Performance > Frames, or a breakpoint in `tick` in `now.js`).

## 7. Offline shell
- [ ] On `localhost` (a secure context) DevTools > Application > Service Workers shows `sw.js` activated; Cache Storage holds `muses-web-4.0.0` with the shell files and no `/api/` entries.
- [ ] Quit the desktop app, reload the tab: the app shell still appears (with the "Cannot reach Muses" message in pages), nothing is blank.
- [ ] After editing any web file and bumping `VERSION` in `sw.js`: reload; a "A new version of Muses Web is ready. Reload" toast appears; its button reloads into the new version.
- [ ] Over a LAN `http://` address the service worker is not registered (not a secure context); the app still works.

## 7b. Session handling
- [ ] Restart the desktop app while a tab is open, then use any page: the tab reloads once by itself (new cookie); if it still fails it shows the "not allowed" toast with a Reload button instead of looping.

## 8. Keyboard use
- [ ] Tab order: Skip link, logo, search, account, the main content, tabs or rail. Focus ring visible everywhere.
- [ ] The "Skip to content" link jumps to the page.
- [ ] Shelves are focusable and scroll with the arrow keys; cards, rows and the More buttons work with Enter and Space.
- [ ] Menus (More, Add to playlist) open as dialogs, trap focus, close with Esc.
- [ ] Now playing: the seek bar works with the arrow keys; the Player, Lyrics and Queue tabs work with Left and Right.

## 9. Playback
- [ ] Play a song: sound starts, mini-player shows title and cover, progress moves.
- [ ] Seek by dragging the bar and by clicking a lyric line.
- [ ] Next and Previous (Previous after 3 s restarts the song), shuffle, repeat one and repeat all behave as labelled.
- [ ] A song that cannot be played is retried once, then a message appears and the player moves on (at most 3 skips in a row; see the break-on-purpose test below).
- [ ] Seek by clicking and dragging the Now playing bar, with the arrow keys on the bar, and (after step 11) with the OS seek control.
- [ ] Play a song, let it run: when 20 s are left DevTools > Network shows one extra `/api/stream/<next id>` request (and one `/api/track/<next id>`), made once; it is dropped when the song changes.
- [ ] A song that ends with a next song in the queue moves on by itself; at the end of the queue the last song stays, paused, and Play starts it again; with Repeat all it wraps to the first song.
- [ ] Break one song on purpose (stop the desktop app for a moment mid-song, then start it): the page retries the same song once at the same position; if it fails again it shows `"<title>" could not be played. Skipping to the next song.` and moves on.
- [ ] Menus: Play next, Add to queue, then the Queue tab shows them in that order; shuffle keeps the current song first; shuffle off restores the original order.

## 10. Hidden tab and media keys (the main acceptance test)
- [ ] Start a song, switch to another tab for a few minutes: the music keeps playing and moves on to the next song.
- [ ] OS media keys (Play/Pause, Next, Previous) work with the tab hidden; so do the buttons of Bluetooth headphones.
- [ ] The OS media overlay (Windows volume flyout, Chrome's media hub, lock screen on a phone) shows the title, artist and cover, and its seek bar moves; dragging it seeks.
- [ ] After switching back, the progress bar and Now playing are at the right place immediately.
- [ ] If the cover is missing in the overlay but title and buttons work, note it: the OS may fetch the artwork without the session cookie.
- [ ] Pause: the overlay shows paused and the keys resume; with nothing loaded the overlay is gone.

## 11. Volume and mute
- [ ] Now playing has a mute button and a volume slider (not on iPhone/iPad Safari, where the hardware buttons decide: the row is hidden there). Dragging changes the loudness, the mute button silences and restores it, and dragging while muted unmutes.
- [ ] Reload the page: the volume is the same (it is kept in the browser).

## 12. Click-to-speak (on `http://localhost:8765/`, the desktop voice recogniser set up)
- [ ] A microphone button sits in the top bar (left of the account dot) and a large one on Now playing; both have a clear label and work with Enter and Space.
- [ ] Click once: the browser asks for the microphone (first time). "Starting the microphone..." then "Listening..." (the word Listening appears only once sound is flowing). Say "play <a song name>", stop talking: it stops by itself after about half a second of silence, shows "Thinking...", then "Heard: ..." and starts the song in the tab. The PC's own desktop player does not start anything.
- [ ] The first words are not cut off: say the command immediately after the button turns to Listening several times; the heard text starts with your first word.
- [ ] Click a second time while listening: it stops and sends. Stay silent for about 5 s: "I did not hear anything" and nothing is sent (Network shows no `/api/voice`).
- [ ] An unclear request shows up to 3 choices; clicking one plays it; saying "number two" or "the other one" in a follow-up uses the remembered choices for about 30 s.
- [ ] Say "pause", "next", "volume up", "shuffle on", "what is playing", "like this": each acts in the tab (and shows a short answer).
- [ ] Settings > Voice: "Hold to talk" makes press-and-hold record and release send (and Enter/Space still work once per press); "Short beep" plays a beep after "Listening" and the beep is not part of what is recognised.
- [ ] The browser's recording indicator goes off as soon as the request is sent, and also when you switch to another tab while listening (the recording is dropped).
- [ ] Network > `/api/voice`: the body is a small WAV (about 30 KB per second); nothing about the audio or the words appears in localStorage, sessionStorage or the URL.
- [ ] Open the page by `http://<PC's LAN IP>:8765/` from another device (LAN on): the button explains that the microphone needs localhost or HTTPS.
- [ ] Block the microphone in the browser: the message says to allow it for the site. Stop the recogniser setup (no program/model): the page says to set it up in the desktop app.

## 13. Resume
- [ ] Play a song a minute in, close the tab, open the page again: the mini-player shows the same song, paused; Play continues from about the same second. The Queue tab shows the other songs (their names fill in a moment after opening it).
- [ ] DevTools > Application > Local Storage: `muses.resume` holds only video ids, an index and a position (no titles).

## 14. Settings page
- [ ] Theme and Battery saver as before. Playback: "Prefer lower bitrate" toggles and is remembered (it only adds `?q=low` to stream URLs for now). Voice: both options are remembered. Desktop window: the text about the tray menu. About and licences opens and closes with the keyboard.

## 15. Idle (battery)
- [ ] Performance recording of 30 s with nothing playing and no panel open: no timers, no animation frames, no network except the 20 s event keep-alive. After a voice request, the panel hides itself within about 30 s and nothing keeps running afterwards.
- [ ] While a song plays on Home with the tab hidden: no `requestAnimationFrame`, no UI updates (only the audio element, the 10 s resume write and the warm-up near the end).
- [ ] Task Manager: note memory and power of the browser tab plus the desktop process in `--web` mode against the desktop window mode, for the next round.
