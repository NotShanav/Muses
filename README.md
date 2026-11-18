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


### Studio and liquid glass (desktop)
Desktop **Settings** is now organised into categories (Account and sync, Playback, Sound, Library and sources, Appearance, System, Advanced and diagnostics); search still looks through all of them. Customisation lives in one **Studio**: Pill, Home layout, Colours and typography, and Presets and import/export (with a confirmed "Reset all customisation"). **Pill Studio** has Layout, Material, Motion, Artwork and Presets tabs; the floating player can be drawn as **Liquid glass** (a Skia shader with edge refraction, a specular rim, chromatic dispersion, frost and album-reactive colour) or as the **Classic** pill, which is also the automatic fallback.

Honest limits:
- The glass refracts what is inside the Muses window (the page), not the desktop behind a translucent Mica/Acrylic window; real desktop refraction is not possible with this stack.
- It is a Skia shader: the first compile may take a moment, and very low-end GPUs may fall back to Classic. **Settings → Advanced → Glass lab (experimental)** shows whether the shader is available and why not.
- The shader could not be run before it reached a real build, so expect one or two tuning rounds using Glass lab screenshots.


### Voice commands (experimental, desktop)
Under **Settings → Advanced → Experimental** you can turn on hands-free voice commands. It is **off by default**; nothing is loaded, recorded or downloaded until you switch it on. Hold the hotkey (default `Ctrl+Alt+Space`, changeable; it works while Muses is hidden in the tray), speak naturally, and release to run the command.

The offline command interpreter recognises a broader range of ways to express the supported actions, rather than requiring one exact sentence. For example, playlist playback can be requested with *play my workout playlist*, *put on workout*, or *I'm in the mood for my workout playlist*. It supports playback, playlist, volume, shuffle, like, and now-playing intents; broad discovery requests can show matching tracks for confirmation. Playlist lookup includes local playlists and browsable playlists from the signed-in YouTube Music library. Track names can still be misheard or ambiguous, so Muses offers candidate tracks rather than silently choosing when confidence is low. A playlist that cannot be matched confidently is not played or edited; playlist disambiguation currently reports a failed match rather than offering playlist candidates.

Recognition runs **offline on the CPU** with the NVIDIA Parakeet unified English 0.6B model (int8, about 660 MB, NVIDIA Open Model License) through the sherpa-onnx offline program. Neither is bundled with Muses: the settings page can find an existing copy, let you choose a folder or file, or download them when you press the button. The recognizer process is started for a command and exits afterwards; no resident recognition service is kept running. Playback is ducked while the microphone is recording and restored afterwards.

Privacy: audio stays in memory except for one short temporary WAV used by the recognizer; that file is overwritten and deleted in cleanup. No audio, transcript or recognised text is saved to history, settings or logs. Speech recognition and command interpretation run offline. Voice-triggered song search, streaming, and account-playlist browsing still use Muses' existing music-service connections when those actions require them; model and program downloads happen only when you start them.

Honest limits:
- English speech recognition; song, artist and playlist names may be misheard, and the command interpreter does not understand arbitrary open-ended requests. It supports a defined set of music actions with varied natural phrasing.
- About 1 GB of RAM is used while a command is processed; with under 1.8 GB free it refuses. The model is loaded per command and then unloaded, so a cold start can take a few seconds.
- The global hotkey is Windows-only. Windows must allow desktop apps to use the microphone (Settings → Privacy → Microphone).
- The Sherpa-ONNX Windows program is downloaded from the project's official v2.0.1 release; it is not bundled with Muses.