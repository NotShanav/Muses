<div align="center">

<br/>

<img src="Logo.png" alt="Muses app icon" width="200" />

# Muses

### Aesthetic YouTube Music Client

<br/>

[**Features**](#features) · [**Download**](#download) · [**Disclaimer**](#disclaimer) · [**License**](#license)

</div>

> [!IMPORTANT]
> Muses is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<div align="center">

<img src="Banner.png" alt="Muses banner" width="100%" />

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- **Search, browse and play** anything available on YouTube Music.
- **Hi-Res lossless audio** — FLAC/ALAC from a configured module source, with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable 0–12s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** integration.
- **Background playback** via a proper foreground media session.
- **Apple-like lyrics animation** — credit to [binimum](https://github.com/binimum/am-lyrics).

#### Connectivity & Accounts
- **Sign in with your Google account** for personalized content.
- **Spotify integration** — connect your account to play your playlists and Liked Songs.
- **Discord Rich Presence** — in-app login, live track/artist/album and progress.
- **Scrobbling** to Last.fm and ListenBrainz.
- **Pluggable sources** — add, edit, test and health-check module sources.

    </td>
    <td width="50%" valign="top">

#### Experience
- **Animated album canvas** — motion artwork on the now-playing screen.
- **Word-synced lyrics** — word/syllable-level highlighting from multiple sources.
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).
- **Dynamic, artwork-driven theming** — Material palette extracted from album art.
- **Frosted-glass UI** — Telegram-style translucent bars via Haze, Material 3 theming.

#### Controls & Tweaks
- **Per-network audio quality** — separate quality ceilings for Wi-Fi and mobile data.
- **Playback speed control** (0.5×–2.0×) and **skip silence**.
- **Sleep timer** — fixed presets or "stop after this track".
- **System equalizer** integration.
- **Stats for nerds** — codec, bit depth, sample rate, and more on the now-playing screen.

    </td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="download"></a>Download</h1>

Muses is built from source. For the Windows and Linux desktop app, see [DESKTOP.md](DESKTOP.md).

</div>

---

<div align="center">

<h1><a id="disclaimer"></a>Disclaimer & Legal Notice</h1>

Muses is an independent third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

* **No Media Hosting:** Muses does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public or user-authenticated APIs.
* **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
* **Copyleft:** Muses is free software under the GPLv3. Any distribution must come with the Corresponding Source under the same license.

</div>

---

<div align="center">

<h1><a id="license"></a>License</h1>

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details, and [NOTICE.md](NOTICE.md) for where this code comes from.

</div>


### Desktop navigation and floating player
The desktop Appearance settings offer **Modern** and **Legacy** interface modes. Modern uses compact Home / Explore / Library navigation, an expanding search field, a floating glass player dock, and a refined home feed with compact Quick picks. Under **Settings → Advanced → Advanced interface customization**, adjust the dock alignment, width, bottom spacing, corner curve, glass contrast, and rim highlight. Legacy restores the classic sidebar layout. The full-screen player remains unchanged; its separate **Player background** setting controls the album-reactive color animation.

For a source-grounded product, architecture, feature, and build guide, see [docs/APP_OVERVIEW_AND_TECHNICAL_GUIDE.md](docs/APP_OVERVIEW_AND_TECHNICAL_GUIDE.md).

Desktop users can open **Settings → Appearance → Advanced customisation** for desktop-only typography, colour, home spacing and artwork, presets, mini-player contents/material, and JSON appearance import/export. Existing desktop shell, Fluidity, player-background, motion, window, and dock settings are reused there. Android settings are unchanged.



### Media Hub (beta, Windows)
Muses can also act as a small **media hub**: **Media Hub** (profile menu in the Modern layout, sidebar in the Legacy layout) lists YouTube, Netflix, Prime Video, JioHotstar and Twitch, and clicking one opens that service in a real Brave, Chrome or Edge window (app mode, no tabs or address bar) that uses its own Muses-owned browser profile. Muses itself only controls the windows: it starts them, closes them, pauses its own music when a service starts, and measures memory so that Muses plus the browser stays under a 2 GB budget (a warning is shown at 1.6 GB). Because a real browser is used, video codecs, Widevine DRM, the browser's ad blocking and your own Chromium extensions come from the browser, not from Muses.

Beta limits: one service is open at a time, closing a service does not keep the playback position, Muses cannot bring an open service's window to the front, and DRM services and extensions are untested. The Hub is not part of the music player and does not change playback, accounts or settings. The test plan is in [HUB_TEST_CHECKLIST.md](HUB_TEST_CHECKLIST.md) and the design is in [HUB_PLAN.md](HUB_PLAN.md).

> [!WARNING]
> Blocking ads on YouTube is against YouTube's Terms of Service. The Hub does not block ads itself; any ad blocking comes from the browser you choose (for example Brave Shields) and is your own responsibility and risk. Do not use the Hub on YouTube if you do not accept that. Muses is not affiliated with YouTube, Google, Netflix, Amazon, JioHotstar, Twitch or any browser vendor.
