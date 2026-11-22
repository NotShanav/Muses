# Muses Web: the browser client and its server

Muses Web is a **browser client for your own computer**. The Muses desktop app starts a small web server
(the JDK's built-in `HttpServer`, no new libraries); a plain HTML/CSS/JS page served by it plays your music in a
browser tab. Nothing heavy runs in this mode: no Compose window, no animated backdrops, no desktop audio engine.

It is **experimental** and was written without being compiled or run; expect a round of fixes after the first build.

## Start it

1. Desktop app: **Settings > Advanced > Experimental > Muses Web (experimental)** on. Press **Open in browser**
   (or open `http://localhost:8765`). If port 8765 is busy the app tries the next 20; the settings row shows the one in use.
2. **Without the desktop window** (background mode): start the app with the `--web` flag, or switch Muses Web on
   and also **Start without the desktop window**. Only a tray icon appears. Its menu: *Open Muses Web*, *Show the
   desktop window*, *Quit*. Clicking the tray icon opens the web page.
3. Sign in to YouTube Music inside the desktop app once; the web page uses that account. The browser never sees
   your cookies.

## Honest limits

- The desktop app (window or tray) **must be running**. The page is a remote control and a player for it.
- It is **not a public website**. YouTube blocks datacenter addresses and its terms forbid re-hosting; this is a
  personal server for you and your own devices.
- The desktop audio engine (equaliser, spatial sound, crossfade) is **not used**: the browser decodes the stream.
- The microphone only works on `localhost` or HTTPS. Over the network (plain `http://192.168...`) the page works but
  the microphone button explains why it cannot record. HTTPS is not in v1.
- Voice needs the desktop's offline recogniser (program and model) to be set up already under *Settings > Advanced >
  Experimental > Voice commands*; otherwise the page says so.
- Old Safari plays AAC (`audio/mp4`) but not WebM/Opus; the server returns the true type and the browser decides.
- The "Prefer lower bitrate" setting is only a hint (`?q=low` on the stream URL). The server ignores it today.

## Security model

- Binds to `127.0.0.1`. **Other devices on my network** is opt-in; then it binds to all addresses and a **6-digit
  pairing code** (shown in the desktop settings, new on every launch) is entered once per browser. Five wrong codes
  lock pairing for a minute, doubling each time.
- A random 128-bit **session token per launch** is set as an `HttpOnly; SameSite=Strict` cookie when the page is
  loaded from the server. Every `/api` call needs the cookie; every call except `/api/stream/*`, `/api/art` and
  `/api/events` (which the browser fetches itself) also needs the header `X-Muses-Web: 1`.
- `Host` must be `localhost`, `127.0.0.1` or (with LAN on) a LAN IP: this stops DNS-rebinding. `Origin` must equal
  the `Host`, and `Sec-Fetch-Site` must be `same-origin` when sent. **No CORS headers** are ever sent.
- The stream proxy fetches only `https` hosts under `googlevideo.com` and only URLs the resolver itself returned.
  The art proxy allows only `googleusercontent.com`, `ggpht.com` and `ytimg.com`. No endpoint takes an arbitrary URL.
- Caps: 64 KB for JSON bodies, 1 MB for voice audio, 3 streams, 8 art fetches, 4 event streams, 1 voice request.
- Tokens, cookies, stream URLs and search text are never logged. A 403 is logged as a fixed reason word only.
- The page's content-security-policy is `default-src 'self'; connect-src 'self'; img-src 'self' data: blob:;
  media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`: no inline
  scripts or styles, no CDN, no external fonts.
- The token is per launch: after the desktop app restarts, an open tab reloads once by itself to get a new cookie
  (LAN devices pair again).

## The API

All JSON, same origin, session cookie. Errors are `{"error": "<text>", "code": "<id>"}` with the HTTP status.

| Call | Answer |
| --- | --- |
| `GET /api/ping` | `{version, signedIn}` |
| `GET /api/home` | `{shelves:[{title, kind, items:[Item]}]}` |
| `GET /api/search?q=` | `{sections:[{title, kind, items:[Item]}]}` |
| `GET /api/suggest?q=` | `["text", ...]` |
| `GET /api/library` | `{liked:{id:"LM", title, count}, playlists:[Item], saved:[Item]}` |
| `GET /api/playlist/{id}` | `{title, owner, editable, items:[Track]}` (also albums, artists, `LM`) |
| `GET /api/track/{videoId}` | `{videoId, title, artists:[string], album, durationMs, art:{small,large}, palette:[3 hex], liked}` |
| `GET /api/stream/{videoId}` | audio bytes, `Range` supported (`200`, `206`, `416`), `Accept-Ranges: bytes` |
| `GET /api/lyrics/{videoId}` | `{synced:[{ms,text}]\|null, plain:string\|null}` |
| `POST /api/like/{videoId}` | body `{liked}` -> `{liked}` |
| `POST /api/playlist/{id}/add` | body `{videoId}` -> `{ok:true}` |
| `GET /api/art?u=` | the image, allowlisted hosts only, 4 MB cap |
| `GET /api/events` | Server-Sent Events: `{type:"signedIn"\|"libraryChanged"\|"voice", ...}` |
| `POST /api/voice` | 16 kHz mono 16-bit WAV, 0.2 to 15 s -> `{heard?, action, result, candidates?, item?, value?}` |

`Item = {id, type:"song"|"album"|"playlist"|"artist", title, subtitle, art, videoId?, browseId?}`;
`Track = {videoId, title, artists, durationMs, art}`.

**Voice answers** (`action`): `play`, `playPlaylist`, `addToPlaylist`, `addToLastPlaylist`, `choices`, `choose`,
`chooseOther`, `pause`, `resume`, `next`, `previous`, `volumeUp`, `volumeDown`, `setVolume`, `shuffle`, `like`,
`whatsPlaying`, `none`. The server only **answers**; the page carries the action out in the browser, so the PC
plays nothing. Audio and recognised text are never stored.

## What the page keeps in the browser

| Key (localStorage) | Holds |
| --- | --- |
| `muses.theme`, `muses.saver` | theme name, battery saver flag |
| `muses.vol`, `muses.quality` | volume 0 to 1, `low` when the bitrate hint is on |
| `muses.resume` | the queue as video ids, the index, the position in seconds, shuffle and repeat (no titles, no search text) |
| `muses.voice.hold`, `muses.voice.cue` | the two voice options |

`sessionStorage` `muses.reloaded` stops a reload loop. Nothing else is stored; recordings and recognised words
exist only in memory while a voice request runs.

## Battery notes (what is off, and why)

- **No Compose window, no Skia, no backdrops, no JVM UI heap** in background mode: that is most of the desktop cost.
- Audio is decoded by the **browser's own (hardware) decoder**; the JVM only forwards bytes in 16 KB pieces.
- **No polling.** The page uses Server-Sent Events and the audio element's events. The events connection is closed
  while the tab is hidden and reopened when it is shown.
- The progress bar updates at most 4 times a second and not at all while the tab is hidden. Media Session position
  is updated only on play, pause, seek and track change.
- `requestAnimationFrame` runs only for the synced-lyrics highlight, only on the Lyrics tab, only while playing and
  visible. There is no canvas, no animation library and no blur animation.
- One static glass style (`backdrop-filter` on the top bar and mini-player only); the **Battery saver** switch makes
  them solid and removes every transition. `prefers-reduced-motion` does the same for transitions.
- The microphone is opened only for a voice request and released (tracks stopped, audio graph closed) before
  anything is sent. A hidden tab cancels a recording.
- The next song is warmed up with **one** extra connection, 20 seconds before the current one ends.
- With nothing playing the page has no timers running (the 20 s event keep-alive is the server's, while the tab is shown).

## Files

Server (Kotlin, `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`): `DesktopWeb*.kt`. Page:
`desktopApp/src/main/resources/web/` (`index.html`, `app.css`, `app.js`, `sw.js`, `manifest.webmanifest`, `js/*.js`,
`js/pages/*.js`). Optional Node tests (Node 22+): `node desktopApp/web-tests/util.test.mjs`, `player.test.mjs`,
`voice.test.mjs`. Manual checklist: `docs/WEB_UI_CHECKLIST.md`.
